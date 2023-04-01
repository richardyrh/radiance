// See LICENSE.SiFive for license details.

package freechips.rocketchip.tilelink

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
// import freechips.rocketchip.devices.tilelink.TLTestRAM
import freechips.rocketchip.util.MultiPortQueue
import freechips.rocketchip.unittest._

class CoalescingUnit(numLanes: Int = 1)(implicit p: Parameters) extends LazyModule {

  // Identity node that captures the incoming TL requests and passes them
  // through the other end, dropping coalesced requests.  This node is what
  // will be visible to upstream and downstream nodes.
  val node = TLIdentityNode()

  // Number of maximum in-flight coalesced requests.  The upper bound of this
  // value would be the sourceId range of a single lane.
  val numInflightCoalRequests = 4

  // Master node that actually generates coalesced requests.
  protected val coalParam = Seq(
    TLMasterParameters.v1(
      name = "CoalescerNode",
      sourceId = IdRange(0, numInflightCoalRequests)
    )
  )
  val coalescerNode = TLClientNode(
    Seq(TLMasterPortParameters.v1(coalParam))
  )

  // Connect master node as the first inward edge of the IdentityNode
  node :=* coalescerNode

  lazy val module = new CoalescingUnitImp(this, numLanes)
}

class ReqQueueEntry(val sourceWidth: Int, val addressWidth: Int) extends Bundle {
  val source = UInt(sourceWidth.W)
  val address = UInt(addressWidth.W)
  val data = UInt(64.W /* FIXME hardcoded */ ) // write data
}

class RespQueueEntry(val sourceWidth: Int, val dataWidthInBits: Int) extends Bundle {
  val source = UInt(sourceWidth.W)
  val data = UInt(dataWidthInBits.W) // read data
}

class CoalescingUnitImp(outer: CoalescingUnit, numLanes: Int) extends LazyModuleImp(outer) {
  // Make sure IdentityNode is connected to an upstream node, not just the
  // coalescer TL master node
  assert(outer.node.in.length >= 2)

  val wordSize = 4

  val reqQueueDepth = 4 // FIXME test
  val respQueueDepth = 2 // FIXME test

  val sourceWidth = outer.node.in(1)._1.params.sourceBits
  val addressWidth = outer.node.in(1)._1.params.addressBits
  val reqQueueEntryT = new ReqQueueEntry(sourceWidth, addressWidth)
  val reqQueues = Seq.tabulate(numLanes) { _ =>
    Module(new CoalShiftQueue(reqQueueEntryT, reqQueueDepth))
  }

  // The maximum number of requests from a single lane that can go into a
  // coalesced request.  Upper bound is 2**sourceWidth.
  val numPerLaneReqs = 2

  val respQueueEntryT = new RespQueueEntry(sourceWidth, wordSize * 8)
  val respQueues = Seq.tabulate(numLanes) { _ =>
    Module(
      new MultiPortQueue(
        respQueueEntryT,
        // enq_lanes = 1 + M, where 1 is the response for the original per-lane
        // requests that didn't get coalesced, and M is the maximum number of
        // single-lane requests that can go into a coalesced request.
        // (`numPerLaneReqs`).
        1 + numPerLaneReqs,
        // deq_lanes = 1 because we're serializing all responses to 1 port that
        // goes back to the core.
        1,
        // lanes. Has to be at least max(enq_lanes, deq_lanes)
        1 + numPerLaneReqs,
        // Depth of each lane queue.
        // XXX queue depth is set to an arbitrarily high value that doesn't
        // make queue block up in the middle of the simulation.  Ideally there
        // should be a more logical way to set this, or we should handle
        // response queue blocking.
        respQueueDepth
      )
    )
  }
  val respQueueNoncoalPort = 0
  val respQueueCoalPortOffset = 1

  // did coalescing succeed at all?
  val coalReqValid = Wire(Bool())

  // Per-lane request and response queues
  //
  // Override IdentityNode implementation so that we can instantiate
  // queues between input and output edges to buffer requests and responses.
  // See IdentityNode definition in `diplomacy/Nodes.scala`.
  (outer.node.in zip outer.node.out).zipWithIndex.foreach {
    case (((tlIn, edgeIn), (tlOut, _)), 0) =>
      assert(
        edgeIn.master.masters(0).name == "CoalescerNode",
        "First edge is not connected to the coalescer master node"
      )
      // Edge from the coalescer TL master node should simply bypass the identity node,
      // except for connecting the outgoing edge to the inflight table, which is done
      // down below.
      tlOut.a <> tlIn.a
      tlIn.d <> tlOut.d
    case (((tlIn, edgeIn), (tlOut, edgeOut)), i) =>
      // Request queue
      //
      val lane = i - 1
      val reqQueue = reqQueues(lane)
      val req = Wire(reqQueueEntryT)
      req.source := tlIn.a.bits.source
      req.address := tlIn.a.bits.address
      req.data := tlIn.a.bits.data

      reqQueue.io.enq.valid := tlIn.a.valid
      reqQueue.io.enq.bits := req
      // TODO: deq.ready should respect downstream ready
      reqQueue.io.deq.ready := true.B
      reqQueue.io.invalidate := 0.U

      printf(s"reqQueue(${lane}).count=%d\n", reqQueue.io.count)

      // Invalidate coalesced requests
      // FIXME: hardcoded lanes
      val invalidate = coalReqValid && (lane == 0 || lane == 2).B
      tlOut.a.valid := reqQueue.io.deq.valid && !invalidate

      val reqHead = reqQueue.io.deq.bits
      // FIXME: generate Get or Put according to read/write
      val (reqLegal, reqBits) = edgeOut.Get(
        fromSource = reqHead.source,
        // `toAddress` should be aligned to 2**lgSize
        toAddress = reqHead.address,
        lgSize = 0.U
      )
      assert(reqLegal, "unhandled illegal TL req gen")
      tlOut.a.bits := reqBits

      // Response queue
      //
      // This queue will serialize non-coalesced responses along with
      // coalesced responses and serve them back to the core side.
      val respQueue = respQueues(lane)
      val resp = Wire(respQueueEntryT)
      resp.source := tlOut.d.bits.source
      resp.data := tlOut.d.bits.data

      // Queue up responses that didn't get coalesced originally ("noncoalesced" responses).
      // Coalesced (but uncoalesced back) responses will also be enqueued into the same queue.
      assert(
        respQueue.io.enq(respQueueNoncoalPort).ready,
        "respQueue: enq port for noncoalesced response is blocked"
      )
      respQueue.io.enq(respQueueNoncoalPort).valid := tlOut.d.valid
      respQueue.io.enq(respQueueNoncoalPort).bits := resp
      // TODO: deq.ready should respect upstream ready
      respQueue.io.deq(respQueueNoncoalPort).ready := true.B

      tlIn.d.valid := respQueue.io.deq(respQueueNoncoalPort).valid
      val respHead = respQueue.io.deq(respQueueNoncoalPort).bits
      val respBits = edgeIn.AccessAck(
        toSource = respHead.source,
        lgSize = 0.U,
        data = respHead.data
      )
      tlIn.d.bits := respBits

      // Debug only
      val inflightCounter = RegInit(UInt(32.W), 0.U)
      when(tlOut.a.valid) {
        // don't inc/dec on simultaneous req/resp
        when(!tlOut.d.valid) {
          inflightCounter := inflightCounter + 1.U
        }
      }.elsewhen(tlOut.d.valid) {
        inflightCounter := inflightCounter - 1.U
      }

      dontTouch(inflightCounter)
      dontTouch(tlIn.a)
      dontTouch(tlIn.d)
      dontTouch(tlOut.a)
      dontTouch(tlOut.d)
  }

  // Generate coalesced requests
  val coalSourceId = RegInit(0.U(2.W /* FIXME hardcoded */ ))
  coalSourceId := coalSourceId + 1.U

  val (tlCoal, edgeCoal) = outer.coalescerNode.out(0)
  val coalReqAddress = Wire(UInt(tlCoal.params.addressBits.W))
  // TODO: bogus address
  coalReqAddress := (0xabcd.U + coalSourceId) << 4
  // FIXME: coalesce lane 0 and lane 2's queue head whenever they're valid
  coalReqValid := reqQueues(0).io.deq.valid && reqQueues(2).io.deq.valid
  when(coalReqValid) {
    // invalidate original requests due to coalescing
    reqQueues(0).io.invalidate := 0x1.U
    reqQueues(2).io.invalidate := 0x1.U
  }

  val (legal, bits) = edgeCoal.Get(
    fromSource = coalSourceId,
    // `toAddress` should be aligned to 2**lgSize
    toAddress = coalReqAddress,
    // 64 bits = 8 bytes = 2**(3) bytes
    lgSize = 3.U
  )
  assert(legal, "unhandled illegal TL req gen")
  tlCoal.a.valid := coalReqValid
  tlCoal.a.bits := bits
  tlCoal.b.ready := true.B
  tlCoal.c.valid := false.B
  tlCoal.d.ready := true.B
  tlCoal.e.valid := false.B

  // Construct new entry for the inflight table
  // FIXME: don't instantiate inflight table entry type here.  It leaks the table's impl
  // detail outside to the coalescer
  val offsetBits = 4 // FIXME hardcoded
  val sizeBits = 2 // FIXME hardcoded
  val newEntry = Wire(
    new InflightCoalReqTableEntry(numLanes, numPerLaneReqs, sourceWidth, offsetBits, sizeBits)
  )
  newEntry.source := coalSourceId
  newEntry.lanes.foreach { l =>
    l.reqs.foreach { r =>
      // TODO: this part needs the actual coalescing logic to work
      r.valid := false.B
      r.offset := 1.U
      r.size := 2.U
    }
  }
  newEntry.lanes(0).reqs(0).valid := true.B
  newEntry.lanes(2).reqs(0).valid := true.B
  dontTouch(newEntry)

  // Uncoalescer module sncoalesces responses back to each lane
  val coalDataWidth = tlCoal.params.dataBits
  val uncoalescer = Module(
    new UncoalescingUnit(
      numLanes,
      numPerLaneReqs,
      sourceWidth,
      coalDataWidth,
      outer.numInflightCoalRequests
    )
  )

  uncoalescer.io.coalReqValid := coalReqValid
  uncoalescer.io.newEntry := newEntry
  uncoalescer.io.coalRespValid := tlCoal.d.valid
  uncoalescer.io.coalRespSrcId := tlCoal.d.bits.source
  uncoalescer.io.coalRespData := tlCoal.d.bits.data

  // Queue up uncoalesced responses into each lane's response queue
  (respQueues zip uncoalescer.io.uncoalResps).foreach { case (q, lanes) =>
    lanes.zipWithIndex.foreach { case (resp, i) =>
      assert(
        q.io.enq(respQueueCoalPortOffset + i).ready,
        s"respQueue: enq port for 0-th coalesced response is blocked"
      )
      q.io.enq(respQueueCoalPortOffset + i).valid := resp.valid
      q.io.enq(respQueueCoalPortOffset + i).bits := resp.bits
    // dontTouch(q.io.enq(respQueueCoalPortOffset))
    }
  }

  // Debug
  dontTouch(coalReqValid)
  dontTouch(coalReqAddress)
  val coalRespData = tlCoal.d.bits.data
  dontTouch(coalRespData)

  dontTouch(tlCoal.a)
  dontTouch(tlCoal.d)
}

class UncoalescingUnit(
    val numLanes: Int,
    val numPerLaneReqs: Int,
    val sourceWidth: Int,
    val coalDataWidth: Int,
    val numInflightCoalRequests: Int
) extends Module {
  val inflightTable = Module(
    new InflightCoalReqTable(numLanes, numPerLaneReqs, sourceWidth, numInflightCoalRequests)
  )
  val wordSize = 4 // FIXME duplicate

  val io = IO(new Bundle {
    val coalReqValid = Input(Bool())
    val newEntry = Input(inflightTable.entryT)
    val coalRespValid = Input(Bool())
    val coalRespSrcId = Input(UInt(sourceWidth.W))
    val coalRespData = Input(UInt(coalDataWidth.W))
    val uncoalResps = Output(
      Vec(numLanes, Vec(numPerLaneReqs, ValidIO(new RespQueueEntry(sourceWidth, wordSize * 8))))
    )
  })

  // Populate inflight table
  inflightTable.io.enq.valid := io.coalReqValid
  inflightTable.io.enq.bits := io.newEntry

  // Look up the table with incoming coalesced responses
  inflightTable.io.lookup.ready := io.coalRespValid
  inflightTable.io.lookupSourceId := io.coalRespSrcId

  assert(
    !((io.coalReqValid === true.B) && (io.coalRespValid === true.B) &&
      (io.newEntry.source === io.coalRespSrcId)),
    "inflight table: enqueueing and looking up the same srcId at the same cycle is not handled"
  )

  // Un-coalescing logic
  //
  // FIXME: `size` should be UInt, not Int
  def getCoalescedDataChunk(data: UInt, dataWidth: Int, offset: UInt, byteSize: Int): UInt = {
    val bitSize = byteSize * 8
    val sizeMask = (1.U << bitSize) - 1.U
    assert(dataWidth % bitSize == 0, "coalesced data width not evenly divisible by size")
    val numChunks = dataWidth / bitSize
    val chunks = Wire(Vec(numChunks, UInt(bitSize.W)))
    val offsets = (0 until numChunks)
    (chunks zip offsets).foreach { case (c, o) =>
      // Take [(off-1)*size:off*size] starting from MSB
      c := (data >> (dataWidth - (o + 1) * bitSize)) & sizeMask
    }
    chunks(offset) // MUX
  }

  // Un-coalesce responses back to individual lanes
  val found = inflightTable.io.lookup.bits
  (found.lanes zip io.uncoalResps).foreach { case (lane, ioLane) =>
    lane.reqs.zipWithIndex.foreach { case (req, i) =>
      val ioReq = ioLane(i)

      // FIXME: only looking at 0th srcId entry

      ioReq.valid := false.B
      ioReq.bits := DontCare

      when(inflightTable.io.lookup.valid) {
        ioReq.valid := req.valid
        ioReq.bits.source := 0.U

        // FIXME: disregard size enum for now
        val byteSize = 4
        ioReq.bits.data :=
          getCoalescedDataChunk(io.coalRespData, coalDataWidth, req.offset, byteSize)
      }
    }
  }
}

// InflightCoalReqTable is a table structure that records
// for each unanswered coalesced request which lane the request originated
// from, what their original TileLink sourceId were, etc.  We use this info to
// split the coalesced response back to individual per-lane responses with the
// right metadata.
class InflightCoalReqTable(
    val numLanes: Int,
    val numPerLaneReqs: Int,
    val sourceWidth: Int,
    val entries: Int
) extends Module {
  val offsetBits = 4 // FIXME hardcoded
  val sizeBits = 2 // FIXME hardcoded
  val entryT =
    new InflightCoalReqTableEntry(numLanes, numPerLaneReqs, sourceWidth, offsetBits, sizeBits)

  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(entryT))
    // TODO: return actual stuff
    val lookup = Decoupled(entryT)
    // TODO: put this inside decoupledIO
    val lookupSourceId = Input(UInt(sourceWidth.W))
  })

  val table = Mem(
    entries,
    new Bundle {
      val valid = Bool()
      val bits =
        new InflightCoalReqTableEntry(numLanes, numPerLaneReqs, sourceWidth, offsetBits, sizeBits)
    }
  )

  when(reset.asBool) {
    (0 until entries).foreach { i =>
      table(i).valid := false.B
      table(i).bits.lanes.foreach { l =>
        l.reqs.foreach { r =>
          r.offset := 0.U
          r.size := 0.U
        }
      }
    }
  }

  val full = Wire(Bool())
  full := (0 until entries)
    .map { i => table(i).valid }
    .reduce { (v0, v1) => v0 && v1 }
  // Inflight table should never be full.  It should have enough number of
  // entries to keep track of all outstanding core-side requests; otherwise,
  // it will stall the core issuing logic.
  assert(!full, "table is blocking coalescer")
  dontTouch(full)

  // Enqueue logic
  //
  io.enq.ready := !full
  val enqFire = io.enq.ready && io.enq.valid
  when(enqFire) {
    // TODO: handle enqueueing and looking up the same entry in the same cycle?
    val entryToWrite = table(io.enq.bits.source)
    assert(
      !entryToWrite.valid,
      "tried to enqueue to an already occupied entry"
    )
    entryToWrite.valid := true.B
    entryToWrite.bits := io.enq.bits
  }

  // Lookup logic
  //
  io.lookup.valid := table(io.lookupSourceId).valid
  io.lookup.bits := table(io.lookupSourceId).bits
  val lookupFire = io.lookup.ready && io.lookup.valid
  // Dequeue as soon as lookup succeeds
  when(lookupFire) {
    table(io.lookupSourceId).valid := false.B
  }

  dontTouch(io.lookup)
}

class InflightCoalReqTableEntry(
    val numLanes: Int,
    // Maximum number of requests from a single lane that can get coalesced into a single request
    val numPerLaneReqs: Int,
    val sourceWidth: Int,
    val offsetBits: Int,
    val sizeBits: Int
) extends Bundle {
  class CoreReq extends Bundle {
    val valid = Bool()
    val offset = UInt(offsetBits.W)
    val size = UInt(sizeBits.W)
  }
  class PerLane extends Bundle {
    // FIXME: if numPerLaneReqs != 2 ** sourceWidth, we need to store srcId as well
    val reqs = Vec(numPerLaneReqs, new CoreReq)
  }
  // sourceId of the coalesced response that just came back.  This will be the
  // key that queries the table.
  val source = UInt(sourceWidth.W)
  val lanes = Vec(numLanes, new PerLane)
}

// Mostly copied from freechips.rocketchip.util.ShiftQueue, except that every
// queue entry and its valid signal are exposed as output IO.
// If `pipe` is true, support enqueueing to a full queue when also dequeueing.
//
// TODO: support deadline
class CoalShiftQueue[T <: Data](
    gen: T,
    val entries: Int,
    pipe: Boolean = true,
    flow: Boolean = false
) extends Module {
  val io = IO(new QueueIO(gen, entries) {
    val invalidate = Input(UInt(entries.W))
    val mask = Output(UInt(entries.W))
    val elts = Output(Vec(entries, gen))
  })

  private val valid = RegInit(VecInit(Seq.fill(entries) { false.B }))
  // "Used" flag is 1 for every entry between the current queue head and tail,
  // even if that entry has been invalidated:
  //
  //  used: 000011111
  // valid: 000011011
  //            │ │ └─ head
  //            │ └────invalidated
  //            └──────tail
  //
  // Need this because we can't tell where to enqueue simply by looking at the
  // valid bits.
  private val used = RegInit(UInt(entries.W), 0.U)
  private val elts = Reg(Vec(entries, gen))

  def pad(mask: Int => Bool) = { i: Int =>
    if (i == -1) true.B else if (i == entries) false.B else mask(i)
  }
  def paddedUsed = pad({ i: Int => used(i) })
  def validAfterInv(i: Int) = Mux(io.invalidate(i), false.B, valid(i))

  val shift = io.deq.ready || (used =/= 0.U) && !validAfterInv(0)
  for (i <- 0 until entries) {
    val wdata = if (i == entries - 1) io.enq.bits else Mux(!used(i + 1), io.enq.bits, elts(i + 1))
    val wen = Mux(
      shift,
      (io.enq.fire && !paddedUsed(i + 1) && used(i)) || pad(validAfterInv)(i + 1),
      // enqueue to the first empty slot above the top
      (io.enq.fire && paddedUsed(i - 1) && !used(i)) || !validAfterInv(i)
    )
    when(wen) { elts(i) := wdata }

    valid(i) := Mux(
      shift,
      (io.enq.fire && !paddedUsed(i + 1) && used(i)) || pad(validAfterInv)(i + 1),
      // TODO: handle enqueueing to invalidated tail?
      (io.enq.fire && paddedUsed(i - 1) && !used(i)) || validAfterInv(i)
    )
  }

  when(io.enq.fire) {
    when(!io.deq.fire) {
      used := (used << 1.U) | 1.U
    }
  }.elsewhen(io.deq.fire) {
    used := used >> 1.U
  }

  io.enq.ready := !valid(entries - 1)
  // We don't want to invalidate deq.valid response right away even when
  // io.invalidate(head) is true.
  // Coalescing unit consumes queue head's validity, and produces its new
  // validity.  Deasserting deq.valid right away will result in a combinational
  // cycle.
  io.deq.valid := valid(0)
  io.deq.bits := elts.head

  assert(!flow, "flow-through is not implemented")
  if (flow) {
    when(io.enq.valid) { io.deq.valid := true.B }
    when(!valid(0)) { io.deq.bits := io.enq.bits }
  }

  if (pipe) {
    when(io.deq.ready) { io.enq.ready := true.B }
  }

  io.mask := valid.asUInt
  io.elts := elts
  io.count := PopCount(io.mask)
}

class MemTraceDriver(numLanes: Int = 1)(implicit p: Parameters) extends LazyModule {
  // Create N client nodes together
  val laneNodes = Seq.tabulate(numLanes) { i =>
    val clientParam = Seq(
      TLMasterParameters.v1(
        name = "MemTraceDriver" + i.toString,
        sourceId = IdRange(0, 0x10)
        // visibility = Seq(AddressSet(0x0000, 0xffffff))
      )
    )
    TLClientNode(Seq(TLMasterPortParameters.v1(clientParam)))
  }

  // Combine N outgoing client node into 1 idenity node for diplomatic
  // connection.
  val node = TLIdentityNode()
  laneNodes.foreach { l => node := l }

  lazy val module = new MemTraceDriverImp(this, numLanes)
}

class TraceReq extends Bundle {
  val valid = Bool()
  val address = UInt(64.W)
  val is_store = Bool()
  val mask = UInt(8.W)
  val data = UInt(64.W)
}

class MemTraceDriverImp(outer: MemTraceDriver, numLanes: Int)
    extends LazyModuleImp(outer)
    with UnitTestModule {
  val sim = Module(
    new SimMemTrace(filename = "vecadd.core1.thread4.trace", numLanes)
  )
  sim.io.clock := clock
  sim.io.reset := reset.asBool
  sim.io.trace_read.ready := true.B

  // Split output of SimMemTrace, which is flattened across all lanes,
  // back to each lane's.

  // Maybe this part can be improved, since now we are still mannually shifting everything
  val laneReqs = Wire(Vec(numLanes, new TraceReq))
  laneReqs.zipWithIndex.foreach { case (req, i) =>
    req.valid := (sim.io.trace_read.valid >> i)
    req.address := (sim.io.trace_read.address >> (64 * i))
    req.is_store := (sim.io.trace_read.is_store >> i)
    req.mask := (sim.io.trace_read.store_mask >> (8 * i))
    req.data := (sim.io.trace_read.data >> (64 * i))

  }

  // To prevent collision of sourceId with a current in-flight message,
  // just use a counter that increments indefinitely as the sourceId of new
  // messages.
  val sourceIdCounter = RegInit(0.U(64.W))
  sourceIdCounter := sourceIdCounter + 1.U

  // Connect each lane to its respective TL node.
  (outer.laneNodes zip laneReqs).foreach { case (node, req) =>
    val (tlOut, edge) = node.out(0)

    val (plegal, pbits) = edge.Put(
      fromSource = sourceIdCounter,
      toAddress = req.address,
      // Memory trace addresses are not necessarily aligned to word boundaries
      // so leave lgSize to 0
      // NOTE: this is in bytes not bits
      lgSize = 0.U,
      data = req.data
    )
    val (glegal, gbits) = edge.Get(
      fromSource = sourceIdCounter,
      toAddress = req.address,
      lgSize = 0.U
    )
    val legal = Mux(req.is_store, plegal, glegal)
    val bits = Mux(req.is_store, pbits, gbits)
    assert(legal, "illegal TL req gen")
    tlOut.a.valid := req.valid
    tlOut.a.bits := bits
    tlOut.b.ready := true.B
    tlOut.c.valid := false.B
    tlOut.d.ready := true.B
    tlOut.e.valid := false.B

    dontTouch(tlOut.a)
  }

  io.finished := sim.io.trace_read.finished

  // Clock Counter, for debugging purpose
  val clkcount = RegInit(0.U(64.W))
  clkcount := clkcount + 1.U
  dontTouch(clkcount)
}

class SimMemTrace(val filename: String, numLanes: Int)
    extends BlackBox(
      Map("FILENAME" -> filename, "NUM_LANES" -> numLanes)
    )
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())

    // These names have to match declarations in the Verilog code, eg.
    // trace_read_address.
    val trace_read = new Bundle {
      val ready = Input(Bool())
      val valid = Output(UInt(numLanes.W))
      // Chisel can't interface with Verilog 2D port, so flatten all lanes into
      // single wide 1D array.
      // TODO: assumes 64-bit address.
      val address = Output(UInt((64 * numLanes).W))
      val is_store = Output(UInt(numLanes.W))
      val store_mask = Output(UInt((8 * numLanes).W))
      val data = Output(UInt((64 * numLanes).W))
      val finished = Output(Bool())
    }
  })

  addResource("/vsrc/SimMemTrace.v")
  addResource("/csrc/SimMemTrace.cc")
  addResource("/csrc/SimMemTrace.h")
}

class CoalConnectTrace(implicit p: Parameters) extends LazyModule {
  // TODO: use parameters for numLanes
  val numLanes = 4
  val coal = LazyModule(new CoalescingUnit(numLanes))
  val driver = LazyModule(new MemTraceDriver(numLanes))

  coal.node :=* driver.node

  // Use TLTestRAM as bogus downstream TL manager nodes
  // TODO: swap this out with a memtrace logger
  val rams = Seq.tabulate(numLanes + 1) { _ =>
    LazyModule(
      // TODO: properly propagate beatBytes?
      new TLRAM(address = AddressSet(0x0000, 0xffffff), beatBytes = 8)
    )
  }
  // Connect all (N+1) outputs of coal to separate TestRAM modules
  rams.foreach { r => r.node := coal.node }

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with UnitTestModule {
    driver.module.io.start := io.start
    io.finished := driver.module.io.finished
  }
}

class CoalescingUnitTest(timeout: Int = 500000)(implicit p: Parameters) extends UnitTest(timeout) {
  val dut = Module(LazyModule(new CoalConnectTrace).module)
  dut.io.start := io.start
  io.finished := dut.io.finished
}
