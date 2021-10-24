package xiangshan.mem
//add by tjz
import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import utils._
import xiangshan._
import xiangshan.backend.decode.ImmUnion
import xiangshan.cache._
import xiangshan.mem.strideprefetch._
import xiangshan.cache.mmu.{TlbRequestIO, TlbReq, TlbResp, TlbCmd}

class L1DPIO(implicit p: Parameters) extends XSBundle {
  val l1dpin = Flipped(DecoupledIO(new RptResp))
  val dtlb = new TlbRequestIO()
  val toStridePipe = new DCacheToL1DPrefetch
}

//L1DPrefetch Pipeline Stage 0
//query DTLB to get paddr
class L1DPUnit_S0(implicit p: Parameters) extends XSModule with HasLoadHelper{
  val io = IO(new Bundle{
    val in = Flipped(DecoupledIO(new RptResp))
    val out = DecoupledIO(new LsPipelineBundle)
    val dcacheReq = DecoupledIO(new DCacheWordReq)
    val dtlbReq = DecoupledIO(new TlbReq)
  })

  val s0_vaddr = io.in.bits.respVaddr

  //query DTLB
  io.dtlbReq.valid := io.in.valid
  io.dtlbReq.bits.vaddr := s0_vaddr
  io.dtlbReq.bits.cmd := TlbCmd.read
  io.dtlbReq.bits.roqIdx := DontCare
  io.dtlbReq.bits.debug.pc := DontCare
  io.dtlbReq.bits.debug.isFirstIssue := DontCare

  //query dcache to read meta
  io.dcacheReq.valid := io.in.valid
  io.dcacheReq.bits.cmd := MemoryOpConstants.M_PFR
  io.dcacheReq.bits.addr := s0_vaddr
  io.dcacheReq.bits.mask := genWmask(io.out.bits.vaddr, "b00".U)
  io.dcacheReq.bits.data := DontCare
  io.dcacheReq.bits.instrtype := LOAD_SOURCE.U
  io.dcacheReq.bits.id   := DontCare
  
  io.out.valid := io.in.valid
  io.out.bits := DontCare
  io.out.bits.vaddr := s0_vaddr
  io.out.bits.uop := io.in.bits.exuin.uop
  io.out.bits.mask := genWmask(io.out.bits.vaddr, "b00".U)

  io.in.ready := io.out.ready
  //exception check
  val addrAligned = LookupTree("b00".U, List(
      "b00".U    -> true.B,
      "b01".U    -> (io.out.bits.vaddr(0) === 0.U),
      "b10".U    -> (io.out.bits.vaddr(1,0) === 0.U),
      "b11".U    -> (io.out.bits.vaddr(2,0) === 0.U) 
  ))
  io.out.bits.uop.cf.exceptionVec(storeAddrMisaligned) := !addrAligned
}

// L1DPrefetch Pipeline Stage 1
// Dtlb resp(send the paddr to dcache for matching)
// and if it causes an exception, ignore this prefetch opreation
class L1DPUnit_S1(implicit p: Parameters) extends XSModule with HasLoadHelper{
  val io = IO(new Bundle{
    val in = Flipped(DecoupledIO(new LsPipelineBundle))
    val dtlbResp = Flipped(DecoupledIO(new TlbResp))
    val dcachePaddr = Output(UInt(PAddrBits.W))
    val dcacheKill = Output(Bool())
  })

  val s1_paddr = io.dtlbResp.bits.paddr
  val s1_tlb_miss = io.dtlbResp.bits.miss
  val wrongaddr = Wire(Bool())
  wrongaddr := (s1_paddr <= 0x80000000L.U)

  io.in.ready := true.B
  io.dtlbResp.ready := true.B

  io.dcachePaddr := s1_paddr
  io.dcacheKill := s1_tlb_miss || wrongaddr
}

class L1DPrefetchUnit(implicit p: Parameters) extends XSModule with HasLoadHelper{
  val io = IO(new L1DPIO)

  val l1dp_s0 = Module(new L1DPUnit_S0)
  val l1dp_s1 = Module(new L1DPUnit_S1)

  io.toStridePipe.prefetch.resp.ready := true.B

  l1dp_s0.io.in <> io.l1dpin
  l1dp_s0.io.dtlbReq <> io.dtlb.req
  l1dp_s0.io.dcacheReq <> io.toStridePipe.prefetch.req

  PipelineConnect(l1dp_s0.io.out, l1dp_s1.io.in, true.B, false.B)
  
  l1dp_s1.io.dtlbResp <> io.dtlb.resp
  l1dp_s1.io.dcachePaddr <> io.toStridePipe.s1_paddr
  l1dp_s1.io.dcacheKill <> io.toStridePipe.s1_kill

  //l1dp_s1.io.replayOut <> io.toReplayUnit
  when(io.l1dpin.fire()) {
    XSDebug("l1dpu_fire(): %x l1dpu_vaddr: %x\n", io.l1dpin.fire(), io.l1dpin.bits.respVaddr)
  }   
  when(io.dtlb.resp.fire()) {
    XSDebug("Dtlb_paddr: %x Dtlb_miss: %x\n", l1dp_s1.io.dtlbResp.bits.paddr, l1dp_s1.io.dtlbResp.bits.miss)
  }   
  XSPerfAccumulate("prefetch_tlb_miss", l1dp_s1.io.dtlbResp.bits.miss)
  XSPerfAccumulate("prefetch_tlb_hit", ~l1dp_s1.io.dtlbResp.bits.miss)
  XSPerfAccumulate("toStridePipe", io.toStridePipe.prefetch.req.fire())
}
