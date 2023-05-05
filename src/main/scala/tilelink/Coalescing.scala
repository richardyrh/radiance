// See LICENSE.SiFive for license details.

package freechips.rocketchip.tilelink

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
// import freechips.rocketchip.devices.tilelink.TLTestRAM
import freechips.rocketchip.util.MultiPortQueue
import freechips.rocketchip.unittest._

trait InFlightTableSizeEnum extends ChiselEnum {
  val INVALID: Type
  val FOUR: Type
  def logSizeToEnum(x: UInt): Type
  def enumToLogSize(x: Type): UInt
}

object DefaultInFlightTableSizeEnum extends InFlightTableSizeEnum {
  val INVALID = Value(0.U)
  val FOUR = Value(1.U)

  def logSizeToEnum(x: UInt): Type = {
    MuxCase(INVALID, Seq(
      (x === 2.U) -> FOUR
    ))
  }

  def enumToLogSize(x: Type): UInt = {
    MuxCase(0.U, Seq(
      (x === FOUR) -> 2.U
    ))
  }
}

case class CoalescerConfig(
  enable: Boolean,        // globally enable or disable coalescing
  numLanes: Int,          // number of lanes (or threads) in a warp
  queueDepth: Int,        // request window per lane
  waitTimeout: Int,       // max cycles to wait before forced fifo dequeue, per lane
  addressWidth: Int,      // assume <= 32
  dataBusWidth: Int,      // memory-side downstream TileLink data bus size
                          // this has to be at least larger than the word size for
                          // the coalescer to perform well
  // watermark = 2,       // minimum buffer occupancy to start coalescing
  wordSizeInBytes: Int,   // 32-bit system
  wordWidth: Int,         // log(WORD_SIZE)
  numOldSrcIds: Int,      // num of outstanding requests per lane, from processor
  numNewSrcIds: Int,      // num of outstanding coalesced requests
  respQueueDepth: Int,    // depth of the response fifo queues
  coalLogSizes: Seq[Int], // list of coalescer sizes to try in the MonoCoalescers
                          // each size is log(byteSize)
  sizeEnum: InFlightTableSizeEnum,
  arbiterOutputs: Int
) {
  // maximum coalesced size
  def maxCoalLogSize: Int = coalLogSizes.max
}


object defaultConfig extends CoalescerConfig(
  enable = true,
  numLanes = 4,
  queueDepth = 1,
  waitTimeout = 8,
  addressWidth = 24,
  dataBusWidth = 3, // 2^3=8 bytes, 64 bit bus
  // watermark = 2,
  wordSizeInBytes = 4,
  wordWidth = 2,
  numOldSrcIds = 16,
  numNewSrcIds = 4,
  respQueueDepth = 4,
  coalLogSizes = Seq(3),
  sizeEnum = DefaultInFlightTableSizeEnum,
  arbiterOutputs = 4
)

class CoalescingUnit(config: CoalescerConfig)(implicit p: Parameters) extends LazyModule {
  // Nexus node that captures the incoming TL requests, rewrites coalescable requests,
  // and arbitrates between non-coalesced and coalesced requests to a fix number of outputs
  // before sending it out to memory. This node is what's visible to upstream and downstream nodes.

  // WIP:
//  val node = TLNexusNode(
//    clientFn  = c => c.head,
//    managerFn = m => m.head  // assuming arbiter generated ids are distinct between edges
//  )
//  node.in.map(_._2).foreach(edge => require(edge.manager.beatBytes == config.wordSizeInBytes,
//    s"input edges into coalescer node does not have beatBytes = ${config.wordSizeInBytes}"))
//  node.out.map(_._2).foreach(edge => require(edge.manager.beatBytes == config.maxCoalLogSize,
//    s"output edges into coalescer node does not have beatBytes = ${config.maxCoalLogSize}"))

  val aggregateNode = TLIdentityNode()
  val cpuNode = TLIdentityNode()

  // Number of maximum in-flight coalesced requests.  The upper bound of this
  // value would be the sourceId range of a single lane.
  val numInflightCoalRequests = config.numNewSrcIds

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

  // merge coalescerNode and cpuNode
  aggregateNode :=* coalescerNode
  aggregateNode :=* TLWidthWidget(config.wordSizeInBytes) :=* cpuNode

  lazy val module = new CoalescingUnitImp(this, config)
}

class ReqQueueEntry(sourceWidth: Int, sizeWidth: Int, addressWidth: Int, maxSize: Int) extends Bundle {
  val op = UInt(1.W) // 0=READ 1=WRITE
  val address = UInt(addressWidth.W)
  val size = UInt(sizeWidth.W)
  val source = UInt(sourceWidth.W)
  val mask = UInt((1 << maxSize).W) // write only
  val data = UInt((8 * (1 << maxSize)).W) // write only

  def toTLA(edgeOut: TLEdgeOut): TLBundleA = {
    val (plegal, pbits) = edgeOut.Put(
      fromSource = this.source,
      toAddress = this.address,
      lgSize = this.size,
      data = this.data,
    )
    val (glegal, gbits) = edgeOut.Get(
      fromSource = this.source,
      toAddress = this.address,
      lgSize = this.size
    )
    val legal = Mux(this.op.asBool, plegal, glegal)
    val bits = Mux(this.op.asBool, pbits, gbits)
    assert(legal, "unhandled illegal TL req gen")
    bits
  }
}

class RespQueueEntry(sourceWidth: Int, sizeWidth: Int, maxSize: Int) extends Bundle {
  val op = UInt(1.W) // 0=READ 1=WRITE
  val size = UInt(sizeWidth.W)
  val source = UInt(sourceWidth.W)
  val data = UInt((8 * (1 << maxSize)).W) // read only
  val error = Bool()

  def toTLD(edgeIn: TLEdgeIn): TLBundleD = {
    val apBits = edgeIn.AccessAck(
      toSource = this.source,
      lgSize = this.size
    )
    val agBits = edgeIn.AccessAck(
      toSource = this.source,
      lgSize = this.size,
      data = this.data
    )
    Mux(this.op.asBool, apBits, agBits)
  }

  def fromTLD(bundle: TLBundleD): Unit = {
    this.source := bundle.source
    this.op := TLUtils.DOpcodeIsStore(bundle.opcode)
    this.size := bundle.size
    this.data := bundle.data
    this.error := bundle.denied
  }
}

class ReqSourceGen(sourceWidth: Int) extends Module {
  val io = IO(new Bundle {
    val gen = Input(Bool())
    val id = Output(Valid(UInt(sourceWidth.W)))
  })

  val head = RegInit(UInt(sourceWidth.W), 0.U)

  head := Mux(io.gen, head + 1.U, head)

  // FIXME: keep track of ones in use & set invalid when out
  io.id.valid := true.B
  io.id.bits := head
}

class CoalShiftQueue[T <: Data](gen: T, entries: Int, config: CoalescerConfig) extends Module {
  val io = IO(new Bundle {
    val queue = new Bundle {
      val enq = Vec(config.numLanes, DeqIO(gen.cloneType))
      val deq = Vec(config.numLanes, EnqIO(gen.cloneType))
    }
    val invalidate = Input(Valid(Vec(config.numLanes, UInt(entries.W))))
    val coalescable = Input(Vec(config.numLanes, Bool()))
    val mask = Output(Vec(config.numLanes, UInt(entries.W)))
    val elts = Output(Vec(config.numLanes, Vec(entries, gen)))
  })

//  val eltPrototype = Wire(Valid(gen))
//  eltPrototype.bits := DontCare
//  eltPrototype.valid := false.B

  val elts = Reg(Vec(config.numLanes, Vec(entries, Valid(gen))))
  val writePtr = RegInit(VecInit(Seq.fill(config.numLanes)(0.asUInt(log2Ceil(entries + 1).W))))
  val deqDone = RegInit(VecInit(Seq.fill(config.numLanes)(false.B)))

  private def resetElts = {
    elts.foreach { laneQ =>
      laneQ.foreach { entry =>
        entry.valid := false.B
        entry.bits := DontCare
      }
    }
  }
  when (reset.asBool) {
    resetElts
  }

  val controlSignals = Wire(Vec(config.numLanes, new Bundle {
    val shift = Bool()
    val full = Bool()
    val empty = Bool()
  }))

  // shift hint is when the heads have no more coalescable left this or next cycle
  val shiftHint = !(io.coalescable zip io.invalidate.bits.map(_(0))).map { case (c, i) =>
    c && !(io.invalidate.valid && i)
  }.reduce(_ || _)
  val syncedEnqValid = io.queue.enq.map(_.valid).reduce(_ || _)
  val syncedDeqValid = io.queue.deq.map(_.valid).reduce(_ || _)

  for (i <- 0 until config.numLanes) {
    val enq = io.queue.enq(i)
    val deq = io.queue.deq(i)
    val ctrl = controlSignals(i)

    ctrl.full := writePtr(i) === entries.U
    ctrl.empty := writePtr(i) === 0.U
    // shift when no outstanding dequeue, no more coalescable chunks, and not empty
    ctrl.shift := !syncedDeqValid && shiftHint && !ctrl.empty

    // dequeue is valid when:
    // head entry is valid, has not been processed by downstream, and is not coalescable
    deq.bits := elts.map(_.head.bits)(i)
    deq.valid := elts.map(_.head.valid)(i) && !deqDone(i) && !io.coalescable(i)

    // can take new entries if not empty, or if full but shifting
    enq.ready := (!ctrl.full) || ctrl.shift

    when (ctrl.shift) {
      // shift, invalidate tail, invalidate coalesced requests
      elts(i).zipWithIndex.foreach { case (elt, j) =>
        if (j == entries - 1) { // tail
          elt.valid := false.B
        } else {
          elt.bits := elts(i)(j + 1).bits
          elt.valid := elts(i)(j + 1).valid && !(io.invalidate.valid && io.invalidate.bits(i)(j + 1))
        }
      }
      // reset dequeue mask when new entries are shifted in
      deqDone(i) := false.B
      // enqueue
      when (enq.ready && syncedEnqValid) { // to allow drift, swap for enq.fire
        elts(i)(writePtr(i) - 1.U).bits := enq.bits
        elts(i)(writePtr(i) - 1.U).valid := enq.valid
      }.otherwise {
        writePtr(i) := writePtr(i) - 1.U
      }
    }.otherwise {
      // invalidate coalesced requests
      when (io.invalidate.valid) {
        (elts(i) zip io.invalidate.bits(i).asBools).map { case (elt, inv) =>
          elt.valid := elt.valid && !inv
        }
      }
      // enqueue
      when (enq.ready && syncedEnqValid) {
        elts(i)(writePtr(i)).bits := enq.bits
        elts(i)(writePtr(i)).valid := enq.valid
        writePtr(i) := writePtr(i) + 1.U
      }
      deqDone(i) := deqDone(i) || deq.fire
    }
  }

  val queueInSync = controlSignals.map(_ === controlSignals.head).reduce(_ && _) &&
    writePtr.map(_ === writePtr.head).reduce(_ && _)
  assert(queueInSync, "shift queue lanes are not in sync")

  io.mask := elts.map(x => VecInit(x.map(_.valid)).asUInt)
  io.elts := elts.map(x => VecInit(x.map(_.bits)))
}

// Software model: coalescer.py
class MonoCoalescer(coalLogSize: Int, windowT: CoalShiftQueue[ReqQueueEntry],
                    config: CoalescerConfig) extends Module {
  val io = IO(new Bundle {
    val window = Input(windowT.io.cloneType)
    val results = Output(new Bundle {
      val leaderIdx = Output(UInt(log2Ceil(config.numLanes).W))
      val baseAddr = Output(UInt(config.addressWidth.W))
      val matchOH = Output(Vec(config.numLanes, UInt(config.queueDepth.W)))
      // number of entries matched with this leader lane's head.
      // maximum is numLanes * queueDepth
      val matchCount = Output(UInt(log2Ceil(config.numLanes * config.queueDepth + 1).W))
      val coverageHits = Output(UInt((1 << config.maxCoalLogSize).W))
      val canCoalesce = Output(Vec(config.numLanes, Bool()))
    })
  })

  io := DontCare

  // Combinational logic to drive output from window contents.
  // The leader lanes only compare their heads against all entries of the
  // follower lanes.
  val leaders = io.window.elts.map(_.head)
  val leadersValid = io.window.mask.map(_.asBools.head)

  // When doing spatial-only coalescing, queues should never drift from each
  // other, i.e. the queue heads should always contain mem requests from the
  // same instruction.
  // FIXME: This relies on the MemTraceDriver's behavior of generating TL
  // requests with full source info even when the corresponding lane is not
  // active.
  def testNoQueueDrift: Bool = leaders.map(_.source === leaders.head.source).reduce(_ || _)
  def printQueueHeads = {
    leaders.zipWithIndex.foreach{ case (head, i) =>
      printf(s"ReqQueueEntry[${i}].head = v:%d, source:%d, addr:%x\n",
        leadersValid(i), head.source, head.address)
    }
  }
  when (leadersValid.reduce(_ || _)) {
    assert(testNoQueueDrift, "unexpected drift between lane request queues")
    // printQueueHeads
  }

  val size = coalLogSize
  val addrMask = (((1 << config.addressWidth) - 1) - ((1 << size) - 1)).U
  def canMatch(req0: ReqQueueEntry, req0v: Bool, req1: ReqQueueEntry, req1v: Bool): Bool = {
    (req0.op === req1.op) &&
    (req0v && req1v) &&
    ((req0.address & this.addrMask) === (req1.address & this.addrMask))
  }

  // Gives a 2-D table of Bools representing match at every queue entry,
  // for each lane (so 3-D in total).
  // dimensions: (leader lane, follower lane, follower entry)
  val matchTablePerLane = (leaders zip leadersValid).map { case (leader, leaderValid) =>
    (io.window.elts zip io.window.mask).map { case (followers, followerValids) =>
      // compare leader's head against follower's every queue entry
      (followers zip followerValids.asBools).map { case (follower, followerValid) =>
        canMatch(follower, followerValid, leader, leaderValid)
        // disabling halving optimization because it does not give the correct
        // per-lane coalescable indication to the shift queue
          // // match leader to only followers at lanes >= leader idx
          // // this halves the number of comparators
          // if (followerIndex < leaderIndex) false.B
          // else canMatch(follower, followerValid, leader, leaderValid)
      }
    }
  }

  val matchCounts = matchTablePerLane.map(table =>
      table.map(PopCount(_)) // sum up each column
           .reduce(_ +& _))
  val canCoalesce = matchCounts.map(_ > 1.U)

  // Elect the leader out of all potential leaders that have matchCounts > 1.
  // TODO: potentially expensive: magnitude comparator
  // Maybe choose leftmost leader (priority encoder) instead of argmax
  val chosenLeaderIdx = matchCounts.zipWithIndex.map {
    case (c, i) => (c, i.U)
  }.reduce[(UInt, UInt)] { case ((c0, i), (c1, j)) =>
    (Mux(c0 >= c1, c0, c1), Mux(c0 >= c1, i, j))
  }._2

  val chosenLeader = VecInit(leaders)(chosenLeaderIdx)
  // matchTable for the chosen lane, but converted to a Vec[UInt]
  val chosenMatches = VecInit(matchTablePerLane.map{ table =>
    VecInit(table.map(VecInit(_).asUInt))
  })(chosenLeaderIdx)
  val chosenMatchCount = VecInit(matchCounts)(chosenLeaderIdx)

  // coverage calculation
  def getOffsetSlice(addr: UInt) = addr(size - 1, config.wordWidth)
  // 2-D table flattened to 1-D
  val offsets = io.window.elts.flatMap(_.map(req => getOffsetSlice(req.address)))
  val valids = io.window.mask.flatMap(_.asBools)
  // indicates for each word in the coalesced chunk whether it is accessed by
  // any of the requests in the queue. e.g. if [ 1 1 1 1 ], all of the four
  // words in the coalesced data coming back will be accessed by some request
  // and we've reached 100% bandwidth utilization.
  val hits = Seq.tabulate(1 << (size - config.wordWidth)) { target =>
    (offsets zip valids).map { case (offset, valid) => valid && (offset === target.U) }.reduce(_ || _)
  }

  // debug prints
  when (leadersValid.reduce(_ || _)) {
    matchCounts.zipWithIndex.foreach { case (count, i) =>
      printf(s"lane[${i}] matchCount = %d\n", count);
    }
    printf("chosenLeader = lane %d\n", chosenLeaderIdx)
    printf("chosenLeader matches = [ ")
    chosenMatches.foreach { m => printf("%d ", m) }
    printf("]\n")
    printf("chosenMatchCount = %d\n", chosenMatchCount)

    printf("hits = [ ")
    hits.foreach { m => printf("%d ", m) }
    printf("]\n")
  }

  io.results.leaderIdx := chosenLeaderIdx
  io.results.baseAddr := chosenLeader.address & addrMask
  io.results.matchOH := chosenMatches
  io.results.matchCount := chosenMatchCount
  io.results.coverageHits := PopCount(hits)
  io.results.canCoalesce := canCoalesce
}

// Software model: coalescer.py
class MultiCoalescer(windowT: CoalShiftQueue[ReqQueueEntry], coalReqT: ReqQueueEntry,
                     config: CoalescerConfig) extends Module {
  val io = IO(new Bundle {
    // coalescing window, connected to the contents of the request queues
    val window = Input(windowT.io.cloneType)
    // generated coalesced request
    val coalReq = DecoupledIO(coalReqT.cloneType)
    // invalidate signals going into each request queue's head
    val invalidate = Output(Valid(Vec(config.numLanes, UInt(config.queueDepth.W))))
    // whether a lane is coalescable
    val coalescable = Output(Vec(config.numLanes, Bool()))
  })

  val coalescers = config.coalLogSizes.map(size => Module(new MonoCoalescer(size, windowT, config)))
  coalescers.foreach(_.io.window := io.window)

  def normalize(valPerSize: Seq[UInt]): Seq[UInt] = {
    (valPerSize zip config.coalLogSizes).map { case (hits, size) =>
      (hits << (config.maxCoalLogSize - size).U).asUInt
    }
  }

  def argMax(x: Seq[UInt]): UInt = {
    x.zipWithIndex.map {
      case (a, b) => (a, b.U)
    }.reduce[(UInt, UInt)] { case ((a, i), (b, j)) =>
      (Mux(a >= b, a, b), Mux(a >= b, i, j)) // TODO: tie-breaker
    }._2
  }

  // normalize to maximum coalescing size so that we can do fair comparisons
  // between coalescing results of different sizes
  val normalizedMatches = normalize(coalescers.map(_.io.results.matchCount))
  val normalizedHits = normalize(coalescers.map(_.io.results.coverageHits))

  val chosenSizeIdx = Wire(UInt(log2Ceil(config.coalLogSizes.size).W))
  val chosenValid = Wire(Bool())
  // minimum 25% coverage
  val minCoverage = 1.max(1 << ((config.maxCoalLogSize - 2) - 2))

  when (normalizedHits.map(_ > minCoverage.U).reduce(_ || _)) {
    chosenSizeIdx := argMax(normalizedHits)
    chosenValid := true.B
    printf("coalescing success by coverage policy\n")
  }.elsewhen(normalizedMatches.map(_ > 1.U).reduce(_ || _)) {
    chosenSizeIdx := argMax(normalizedMatches)
    chosenValid := true.B
    printf("coalescing success by matches policy\n")
  }.otherwise {
    chosenSizeIdx := DontCare
    chosenValid := false.B
  }

  def debugPolicyPrint() = {
    printf("matchCount[0]=%d\n", coalescers(0).io.results.matchCount)
    printf("normalizedMatches[0]=%d\n", normalizedMatches(0))
    printf("coverageHits[0]=%d\n", coalescers(0).io.results.coverageHits)
    printf("normalizedHits[0]=%d\n", normalizedHits(0))
    printf("minCoverage=%d\n", minCoverage.U)
  }

  // create coalesced request
  val chosenBundle = VecInit(coalescers.map(_.io.results))(chosenSizeIdx)
  val chosenSize = VecInit(coalescers.map(_.size.U))(chosenSizeIdx)

  // flatten requests and matches
  val flatReqs = io.window.elts.flatten
  val flatMatches = chosenBundle.matchOH.flatMap(_.asBools)

  // check for word alignment in addresses
  assert(io.window.elts.flatMap(_.map(req => req.address(config.wordWidth - 1, 0) === 0.U)).zip(
    io.window.mask.flatMap(_.asBools)).map { case (aligned, valid) => (!valid) || aligned }.reduce(_ || _),
    "one or more addresses used for coalescing is not word-aligned")

  // note: this is word-level coalescing. if finer granularity is needed, need to modify code
  val numWords = (1.U << (chosenSize - config.wordWidth.U)).asUInt
  val maxWords = 1 << (config.maxCoalLogSize - config.wordWidth)
  val addrMask = Wire(UInt(config.maxCoalLogSize.W))
  addrMask := (1.U << chosenSize).asUInt - 1.U

  val data = Wire(Vec(maxWords, UInt((config.wordSizeInBytes * 8).W)))
  val mask = Wire(Vec(maxWords, UInt(config.wordSizeInBytes.W)))

  for (i <- 0 until maxWords) {
    val sel = flatReqs.zip(flatMatches).map { case (req, m) =>
      // note: ANDing against addrMask is to conform to active byte lanes requirements
      // if aligning to LSB suffices, we should add the bitwise AND back
      m && ((req.address(config.maxCoalLogSize - 1, config.wordWidth)/* & addrMask*/) === i.U)
    }
    // TODO: SW uses priority encoder, not sure about behavior of MuxCase
    data(i) := MuxCase(DontCare, flatReqs.zip(sel).map { case (req, s) =>
      s -> req.data
    })
    mask(i) := MuxCase(0.U, flatReqs.zip(sel).map { case (req, s) =>
      s -> req.mask
    })
  }

  val sourceGen = Module(new ReqSourceGen(log2Ceil(config.numNewSrcIds)))
  sourceGen.io.gen := io.coalReq.fire // use up a source ID only when request is created

  val coalesceValid = chosenValid && sourceGen.io.id.valid

  io.coalReq.bits.source := sourceGen.io.id.bits
  io.coalReq.bits.mask := mask.asUInt
  io.coalReq.bits.data := data.asUInt
  io.coalReq.bits.size := chosenSize
  io.coalReq.bits.address := chosenBundle.baseAddr
  io.coalReq.bits.op := io.window.elts(chosenBundle.leaderIdx).head.op
  io.coalReq.valid := coalesceValid

  io.invalidate.bits := chosenBundle.matchOH
  io.invalidate.valid := io.coalReq.fire // invalidate only when fire

  io.coalescable := coalescers.map(_.io.results.canCoalesce.asUInt).reduce(_ | _).asBools

  dontTouch(io.invalidate) // debug

  def disable = {
    io.coalReq.valid := false.B
    io.invalidate.valid := false.B
    io.coalescable.foreach { _ := false.B }
  }
  if (!config.enable) disable
}

class CoalescingUnitImp(outer: CoalescingUnit, config: CoalescerConfig) extends LazyModuleImp(outer) {
  require(outer.cpuNode.in.length == config.numLanes,
    s"number of incoming edges (${outer.cpuNode.in.length}) is not the same as " +
    s"config.numLanes (${config.numLanes})")
  require(outer.cpuNode.in.head._1.params.sourceBits == log2Ceil(config.numOldSrcIds),
    s"TL param sourceBits (${outer.cpuNode.in.head._1.params.sourceBits}) " +
    s"mismatch with log2(config.numOldSrcIds) (${log2Ceil(config.numOldSrcIds)})")
  require(outer.cpuNode.in.head._1.params.addressBits == config.addressWidth,
    s"TL param addressBits (${outer.cpuNode.in.head._1.params.addressBits}) " +
    s"mismatch with config.addressWidth (${config.addressWidth})")

  val sourceWidth = outer.cpuNode.in.head._1.params.sourceBits
  // note we are using word size. assuming all coalescer inputs are word sized
  val reqQueueEntryT = new ReqQueueEntry(sourceWidth, config.wordWidth, config.addressWidth, config.wordSizeInBytes)
  val reqQueues = Module(new CoalShiftQueue(reqQueueEntryT, config.queueDepth, config))

  val coalReqT = new ReqQueueEntry(sourceWidth, log2Ceil(config.maxCoalLogSize),
    config.addressWidth, config.maxCoalLogSize)
  val coalescer = Module(new MultiCoalescer(reqQueues, coalReqT, config))
  coalescer.io.window := reqQueues.io
  reqQueues.io.coalescable := coalescer.io.coalescable
  reqQueues.io.invalidate := coalescer.io.invalidate

  // Per-lane request and response queues
  //
  // Override IdentityNode implementation so that we can instantiate
  // queues between input and output edges to buffer requests and responses.
  // See IdentityNode definition in `diplomacy/Nodes.scala`.
  (outer.cpuNode.in zip outer.cpuNode.out).zipWithIndex.foreach {
    case (((tlIn, _), (tlOut, edgeOut)), lane) =>
      // Request queue
      val req = Wire(reqQueueEntryT)

      req.op := TLUtils.AOpcodeIsStore(tlIn.a.bits.opcode)
      req.source := tlIn.a.bits.source
      req.address := tlIn.a.bits.address
      req.data := tlIn.a.bits.data
      req.size := tlIn.a.bits.size
      // FIXME: req.data is still containing TL-aligned data.  This is fine if
      // we're simply passing through this data out the other end, but not if
      // the outgoing TL edge (tlOut) has different data width from the incoming
      // edge (tlIn).  Possible TODO to only store the relevant portion of the
      // data, at the cost of re-aligning at the outgoing end.
      req.mask := tlIn.a.bits.mask

      val enq = reqQueues.io.queue.enq(lane)
      val deq = reqQueues.io.queue.deq(lane)
      enq.valid := tlIn.a.valid
      enq.bits := req
      deq.ready := true.B // TODO: deq.ready should respect downstream arbiter
      tlOut.a.valid := deq.valid
      tlOut.a.bits := deq.bits.toTLA(edgeOut)

      // debug
      // when (tlIn.a.valid) {
      //   TLPrintf(s"tlIn(${lane}).a",
      //     tlIn.a.bits.address,
      //     tlIn.a.bits.size,
      //     tlIn.a.bits.mask,
      //     TLUtils.AOpcodeIsStore(tlIn.a.bits.opcode),
      //     tlIn.a.bits.data,
      //     0.U
      //   )
      // }
      // when (tlOut.a.valid) {
      //   TLPrintf(s"tlOut(${lane}).a",
      //     tlOut.a.bits.address,
      //     tlOut.a.bits.size,
      //     tlOut.a.bits.mask,
      //     TLUtils.AOpcodeIsStore(tlOut.a.bits.opcode),
      //     tlOut.a.bits.data,
      //     0.U
      //   )
      // }
  }

  val (tlCoal, edgeCoal) = outer.coalescerNode.out.head
  tlCoal.a.valid := coalescer.io.coalReq.valid
  tlCoal.a.bits := coalescer.io.coalReq.bits.toTLA(edgeCoal)
  coalescer.io.coalReq.ready := tlCoal.a.ready
  tlCoal.b.ready := true.B
  tlCoal.c.valid := false.B
  // tlCoal.d.ready := true.B // this should be connected to uncoalescer's ready, done below.
  tlCoal.e.valid := false.B


  // ==================================================================
  // ******************************************************************
  // ************************* REORG BOUNDARY *************************
  // ******************************************************************
  // ==================================================================

  // The maximum number of requests from a single lane that can go into a
  // coalesced request.  Upper bound is min(DEPTH, 2**sourceWidth).
  val numPerLaneReqs = config.queueDepth

  val respQueueEntryT = new RespQueueEntry(sourceWidth, log2Ceil(config.maxCoalLogSize), config.maxCoalLogSize)
  val respQueues = Seq.tabulate(config.numLanes) { _ =>
    Module(
      new MultiPortQueue(
        respQueueEntryT,
        // enq_lanes = 1 + M, where 1 is the response for the original per-lane
        // requests that didn't get coalesced, and M is the maximum number of
        // single-lane requests that can go into a coalesced request.
        // (`numPerLaneReqs`).
        // TODO: potentially expensive, because this generates more FFs.
        // Rather than enqueueing all responses in a single cycle, consider
        // enqueueing one by one (at the cost of possibly stalling downstream).
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
        config.respQueueDepth
      )
    )
  }
  val respQueueNoncoalPort = 0
  val respQueueUncoalPortOffset = 1

  (outer.cpuNode.in zip outer.cpuNode.out).zipWithIndex.foreach {
    case (((tlIn, edgeIn), (tlOut, _)), lane) =>
      // Response queue
      //
      // This queue will serialize non-coalesced responses along with
      // coalesced responses and serve them back to the core side.
      val respQueue = respQueues(lane)
      val resp = Wire(respQueueEntryT)
      resp.fromTLD(tlOut.d.bits)

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
      tlIn.d.bits := respQueue.io.deq(respQueueNoncoalPort).bits.toTLD(edgeIn)

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

  // Construct new entry for the inflight table
  // FIXME: don't instantiate inflight table entry type here.  It leaks the table's impl
  // detail to the coalescer

  // richard: I think a good idea is to pass Valid[ReqQueueEntry] generated by
  // the coalescer directly into the uncoalescer, so that we can offload the
  // logic to generate the Inflight Entry into the uncoalescer, where it should be.
  // this also reduces top level clutter.

  val uncoalescer = Module(new Uncoalescer(config))

  val newEntry = Wire(uncoalescer.inflightTable.entryT)
  newEntry.source := coalescer.io.coalReq.bits.source

  assert (config.maxCoalLogSize <= config.dataBusWidth,
    "multi-beat coalesced reads/writes are currently not supported")
  assert (
    tlCoal.params.dataBits == (1 << config.dataBusWidth) * 8,
    s"tlCoal param `dataBits` (${tlCoal.params.dataBits}) mismatches coalescer constant"
    + s" (${(1 << config.dataBusWidth) * 8})"
  )
  val reqQueueHeads = reqQueues.io.queue.deq.map(_.bits)
  // Do a 2-D copy from every (numLanes * queueDepth) invalidate output of the
  // coalescer to every (numLanes * queueDepth) entry in the inflight table.
  (newEntry.lanes zip coalescer.io.invalidate.bits).zipWithIndex
    .foreach { case ((laneEntry, laneInv), lane) =>
      (laneEntry.reqs zip laneInv.asBools).zipWithIndex
        .foreach { case ((reqEntry, inv), i) =>
          val req = reqQueues.io.elts(lane)(i)
          when ((coalescer.io.invalidate.valid && inv)) {
            printf(s"coalescer: reqQueue($lane)($i) got invalidated (source=%d)\n", req.source)
          }
          reqEntry.valid := (coalescer.io.invalidate.valid && inv)
          reqEntry.source := req.source
          reqEntry.offset := ((req.address % (1 << config.maxCoalLogSize).U) >> config.wordWidth)
          reqEntry.sizeEnum := config.sizeEnum.logSizeToEnum(req.size)
          // TODO: load/store op
        }
    }
  dontTouch(newEntry)

  uncoalescer.io.coalReqValid := coalescer.io.coalReq.valid
  uncoalescer.io.newEntry := newEntry
  // Cleanup: custom <>?
  uncoalescer.io.coalResp.valid := tlCoal.d.valid
  uncoalescer.io.coalResp.bits.source := tlCoal.d.bits.source
  uncoalescer.io.coalResp.bits.data := tlCoal.d.bits.data
  tlCoal.d.ready := uncoalescer.io.coalResp.ready

  // Connect uncoalescer results back into each lane's response queue
  (respQueues zip uncoalescer.io.uncoalResps).zipWithIndex.foreach { case ((q, perLaneResps), lane) =>
    perLaneResps.zipWithIndex.foreach { case (resp, i) =>
      // TODO: rather than crashing, deassert tlOut.d.ready to stall downtream
      // cache.  This should ideally not happen though.
      assert(
        q.io.enq(respQueueUncoalPortOffset + i).ready,
        s"respQueue: enq port for ${i}-th uncoalesced response is blocked for lane ${lane}"
      )
      q.io.enq(respQueueUncoalPortOffset + i).valid := resp.valid
      q.io.enq(respQueueUncoalPortOffset + i).bits := resp.bits
      // debug
      // when (resp.valid) {
      //   printf(s"${i}-th uncoalesced response came back from lane ${lane}\n")
      // }
      // dontTouch(q.io.enq(respQueueCoalPortOffset))
    }
  }

  // Debug
  dontTouch(coalescer.io.coalReq)
  val coalRespData = tlCoal.d.bits.data
  dontTouch(coalRespData)

  dontTouch(tlCoal.a)
  dontTouch(tlCoal.d)
}

// Protocol-agnostic bundle that represents a coalesced response.
//
// Having this makes it easier to:
//   * do unit tests -- no need to deal with TileLink in the chiseltest code
//   * adapt coalescer to custom protocols like a custom L1 cache interface.
//
// FIXME: overlaps with RespQueueEntry. Trait-ify
class CoalescedResponseBundle(config: CoalescerConfig) extends Bundle {
  val source = UInt(log2Ceil(config.numNewSrcIds).W)
  val data = UInt((8 * (1 << config.maxCoalLogSize)).W)
}

class Uncoalescer(config: CoalescerConfig) extends Module {
  // notes to hansung:
  //  val numLanes: Int, <-> config.NUM_LANES
  //  val numPerLaneReqs: Int, <-> config.DEPTH
  //  val sourceWidth: Int, <-> log2ceil(config.NUM_OLD_IDS)
  //  val sizeWidth: Int, <-> config.sizeEnum.width
  //  val coalDataWidth: Int, <-> (1 << config.MAX_SIZE)
  //  val numInflightCoalRequests: Int <-> config.NUM_NEW_IDS
  val inflightTable = Module(new InflightCoalReqTable(config))
  val io = IO(new Bundle {
    val coalReqValid = Input(Bool())
    // FIXME: receive ReqQueueEntry and construct newEntry inside uncoalescer
    val newEntry = Input(inflightTable.entryT.cloneType)
    val coalResp = Flipped(Decoupled(new CoalescedResponseBundle(config)))
    val uncoalResps = Output(
      Vec(
        config.numLanes,
        Vec(
          config.queueDepth,
          ValidIO(
            new RespQueueEntry(log2Ceil(config.numOldSrcIds), config.wordWidth, config.wordSizeInBytes)
          )
        )
      )
    )
  })

  // Populate inflight table
  inflightTable.io.enq.valid := io.coalReqValid
  inflightTable.io.enq.bits := io.newEntry

  // Look up the table with incoming coalesced responses
  inflightTable.io.lookup.ready := io.coalResp.valid
  inflightTable.io.lookupSourceId := io.coalResp.bits.source
  io.coalResp.ready := true.B // FIXME, see sw model implementation

  assert(
    !((io.coalReqValid === true.B) && (io.coalResp.valid === true.B) &&
      (io.newEntry.source === io.coalResp.bits.source)),
    "inflight table: enqueueing and looking up the same srcId at the same cycle is not handled"
  )

  // Un-coalescing logic
  //
  def getCoalescedDataChunk(data: UInt, dataWidth: Int, offset: UInt, logSize: UInt): UInt = {
    assert(logSize === 2.U, "currently only supporting 4-byte accesses. TODO")

    // sizeInBits should be simulation-only construct
    val sizeInBits = ((1.U << logSize) << 3.U).asUInt
    assert(
      (dataWidth > 0).B && (dataWidth.U % sizeInBits === 0.U),
      s"coalesced data width ($dataWidth) not evenly divisible by core req size ($sizeInBits)"
    )

    val numChunks = dataWidth / 32
    val chunks = Wire(Vec(numChunks, UInt(32.W)))
    val offsets = (0 until numChunks)
    (chunks zip offsets).foreach { case (c, o) =>
      // FIXME: whether to take the offset from MSB or LSB depends on
      // endianness.  Right now we're assuming little endian
      c := data(32 * (o + 1) - 1, 32 * o)
      // If taking from MSB:
      // c := (data >> (dataWidth - (o + 1) * 32)) & sizeMask
    }
    chunks(offset) // MUX
  }

  // Un-coalesce responses back to individual lanes
  val found = inflightTable.io.lookup.bits
  (found.lanes zip io.uncoalResps).foreach { case (perLane, ioPerLane) =>
    perLane.reqs.zipWithIndex.foreach { case (oldReq, i) =>
      val ioOldReq = ioPerLane(i)

      // TODO: spatial-only coalescing: only looking at 0th srcId entry
      ioOldReq.valid := false.B
      ioOldReq.bits := DontCare

      when(inflightTable.io.lookup.valid && oldReq.valid) {
        ioOldReq.valid := oldReq.valid
        ioOldReq.bits.source := oldReq.source
        val logSize = found.sizeEnumT.enumToLogSize(oldReq.sizeEnum)
        ioOldReq.bits.size := logSize
        ioOldReq.bits.data :=
          getCoalescedDataChunk(
            io.coalResp.bits.data,
            io.coalResp.bits.data.getWidth,
            oldReq.offset,
            logSize
          )
      }
    }
  }
}

// InflightCoalReqTable is a table structure that records
// for each unanswered coalesced request which lane the request originated
// from, what their original TileLink sourceId were, etc.  We use this info to
// split the coalesced response back to individual per-lane responses with the
// right metadata.
class InflightCoalReqTable(config: CoalescerConfig) extends Module {
  val offsetBits = config.maxCoalLogSize - config.wordWidth // assumes word offset
  val entryT = new InflightCoalReqTableEntry(
    config.numLanes,
    config.queueDepth,
    log2Ceil(config.numOldSrcIds),
    config.maxCoalLogSize,
    config.sizeEnum
  )

  val entries = config.numNewSrcIds
  val sourceWidth = log2Ceil(config.numOldSrcIds)

  println(s"=========== table sourceWidth: ${sourceWidth}")
  println(s"=========== table offsetBits: ${offsetBits}")
  println(s"=========== table sizeEnumBits: ${entryT.sizeEnumT.getWidth}")

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
      val bits = entryT.cloneType
    }
  )

  when(reset.asBool) {
    (0 until entries).foreach { i =>
      table(i).valid := false.B
      table(i).bits.lanes.foreach { l =>
        l.reqs.foreach { r =>
          r.valid := false.B
          r.source := 0.U
          r.offset := 0.U
          r.sizeEnum := config.sizeEnum.INVALID
        }
      }
    }
  }

  val full = Wire(Bool())
  full := (0 until entries).map( table(_).valid ).reduce( _ && _ )
  assert(!full, "inflight table is full and blocking coalescer")
  dontTouch(full)

  // Enqueue logic
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
    val sizeEnumT: InFlightTableSizeEnum
) extends Bundle {
  class PerCoreReq extends Bundle {
    val valid = Bool() // FIXME: delete this
    // FIXME: oldId and newId shares the same width
    val source = UInt(sourceWidth.W)
    val offset = UInt(offsetBits.W)
    val sizeEnum = sizeEnumT()
  }
  class PerLane extends Bundle {
    val reqs = Vec(numPerLaneReqs, new PerCoreReq)
  }
  // sourceId of the coalesced response that just came back.  This will be the
  // key that queries the table.
  val source = UInt(sourceWidth.W)
  val lanes = Vec(numLanes, new PerLane)
}

object TLUtils {
  def AOpcodeIsStore(opcode: UInt): Bool = {
    // 0: PutFullData, 1: PutPartialData, 4: Get
    assert(
      opcode === TLMessages.PutFullData || opcode === TLMessages.Get,
      "unhandled TL A opcode found"
    )
    Mux(opcode === TLMessages.PutFullData, true.B, false.B)
  }
  def DOpcodeIsStore(opcode: UInt): Bool = {
    assert(
      opcode === TLMessages.AccessAck || opcode === TLMessages.AccessAckData,
      "unhandled TL D opcode found"
    )
    Mux(opcode === TLMessages.AccessAck, true.B, false.B)
  }
}

class MemTraceDriver(config: CoalescerConfig, filename: String)(implicit
    p: Parameters
) extends LazyModule {
  // Create N client nodes together
  val laneNodes = Seq.tabulate(config.numLanes) { i =>
    val clientParam = Seq(
      TLMasterParameters.v1(
        name = "MemTraceDriver" + i.toString,
        sourceId = IdRange(0, config.numOldSrcIds)
        // visibility = Seq(AddressSet(0x0000, 0xffffff))
      )
    )
    TLClientNode(Seq(TLMasterPortParameters.v1(clientParam)))
  }

  // Combine N outgoing client node into 1 idenity node for diplomatic
  // connection.
  val node = TLIdentityNode()
  laneNodes.foreach { l => node := l }

  lazy val module = new MemTraceDriverImp(this, config, filename)
}

trait HasTraceLine {
  val valid: UInt
  val source: UInt
  val address: UInt
  val is_store: UInt
  val size: UInt
  val data: UInt
}

// Used for both request and response.  Response had address set to 0
// NOTE: these widths have to agree with what's hardcoded in Verilog.
class TraceLine extends Bundle with HasTraceLine {
  val valid = Bool()
  val source = UInt(32.W)
  val address = UInt(64.W) // FIXME: in Verilog this is the same as data width
  val is_store = Bool()
  val size = UInt(8.W) // this is log2(bytesize) as in TL A bundle
  val data = UInt(64.W)
}

class MemTraceDriverImp(outer: MemTraceDriver, config: CoalescerConfig, traceFile: String)
    extends LazyModuleImp(outer)
    with UnitTestModule {

  val globalClkCounter    = RegInit(1.U(64.W))
  val traceReadCycle      = RegInit(1.U(64.W))
  val downstreamSQready   = WireInit(true.B)

  //make the downstream only ready 1/4 of the time
  //This is to test Tracer System's ability to hold on requests
  //FIXME
  downstreamSQready       := (globalClkCounter(1,0) =/= 0.U)
  //Connect Signals to Verilog BlackBox
  val sim = Module(new SimMemTrace(traceFile, config.numLanes))
  sim.io.clock := clock
  sim.io.reset := reset.asBool
  sim.io.trace_read.ready := downstreamSQready
  //FIXME - 1.U hardcoded, currently there is a delay between chisel and verilog
  sim.io.trace_read.cycle := traceReadCycle


  // Read output from Verilog BlackBox
  // Split output of SimMemTrace, which is flattened across all lanes,back to each lane's.
  val laneReqs = Wire(Vec(config.numLanes, new TraceLine))
  val addrW = laneReqs(0).address.getWidth
  val sizeW = laneReqs(0).size.getWidth
  val dataW = laneReqs(0).data.getWidth
  laneReqs.zipWithIndex.foreach { case (req, i) =>
    req.valid := sim.io.trace_read.valid(i)
    // TODO: driver trace doesn't contain source id
    req.source := 0.U
    req.address := sim.io.trace_read.address(addrW * (i + 1) - 1, addrW * i)
    req.is_store := sim.io.trace_read.is_store(i)
    req.size := sim.io.trace_read.size(sizeW * (i + 1) - 1, sizeW * i)
    req.data := sim.io.trace_read.data(dataW * (i + 1) - 1, dataW * i)
  }

  globalClkCounter       := globalClkCounter + 1.U
  val existValidReq       = WireInit(false.B)
  existValidReq          := laneReqs.map(_.valid).reduce(_||_)
  val validReqBlocked     = WireInit(false.B)
  validReqBlocked        := !downstreamSQready && existValidReq
  //Debug
  dontTouch(downstreamSQready)
  dontTouch(existValidReq)
  dontTouch(validReqBlocked)
  // Do Not Update TraceReadCycle if downstream is blocking
  when(!validReqBlocked){
    traceReadCycle       := traceReadCycle + 1.U
  }

  // To prevent collision of sourceId with a current in-flight message,
  // just use a counter that increments indefinitely as the sourceId of new
  // messages.
  val sourceIdCounter = RegInit(0.U(64.W))
  sourceIdCounter := sourceIdCounter + 1.U

  // Issue here is that Vortex mem range is not within Chipyard Mem range
  // In default setting, all mem-req for program data must be within
  // 0X80000000 -> 0X90000000
  def hashToValidPhyAddr(addr: UInt): UInt = {
    Cat(8.U(4.W), addr(27, 0))
  }

  // Generate TL requests corresponding to the trace lines
  (outer.laneNodes zip laneReqs).foreach { case (node, req) =>
    // Core only makes accesses of granularity larger than a word, so we want
    // the trace driver to act so as well.
    // That means if req.size is smaller than word size, we need to pad data
    // with zeros to generate a word-size request, and set mask accordingly.
    val offsetInWord = req.address % config.wordSizeInBytes.U
    val subword = req.size < log2Ceil(config.wordSizeInBytes).U

    // `mask` is currently unused
    val mask = Wire(UInt(config.wordSizeInBytes.W))
    val wordData = Wire(UInt((config.wordSizeInBytes * 8 * 2).W))
    val sizeInBytes = Wire(UInt((sizeW + 1).W))
    sizeInBytes := (1.U) << req.size
    mask := Mux(subword, (~((~0.U(64.W)) << sizeInBytes)) << offsetInWord, ~0.U)
    wordData := Mux(subword, req.data << (offsetInWord * 8.U), req.data)
    val wordAlignedAddress = req.address & ~((1 << log2Ceil(config.wordSizeInBytes)) - 1).U(addrW.W)
    val wordAlignedSize = Mux(subword, 2.U, req.size)

    // when(req.valid && subword) {
    //   printf(
    //     "address=%x, size=%d, data=%x, addressMask=%x, wordAlignedAddress=%x, mask=%x, wordData=%x\n",
    //     req.address,
    //     req.size,
    //     req.data,
    //     ~((1 << log2Ceil(config.WORD_SIZE)) - 1).U(addrW.W),
    //     wordAlignedAddress,
    //     mask,
    //     wordData
    //   )
    // }

    val (tlOut, edge) = node.out(0)
    val (plegal, pbits) = edge.Put(
      fromSource = sourceIdCounter,
      toAddress = hashToValidPhyAddr(wordAlignedAddress),
      lgSize = wordAlignedSize, // trace line already holds log2(size)
      // data should be aligned to beatBytes
      data = (wordData << (8.U * (wordAlignedAddress % edge.manager.beatBytes.U))).asUInt
    )
    val (glegal, gbits) = edge.Get(
      fromSource = sourceIdCounter,
      toAddress = hashToValidPhyAddr(wordAlignedAddress),
      lgSize = wordAlignedSize
    )
    val legal = Mux(req.is_store, plegal, glegal)
    val bits = Mux(req.is_store, pbits, gbits)

    when(tlOut.a.valid) {
      TLPrintf(
        "MemTraceDriver",
        tlOut.a.bits.address,
        tlOut.a.bits.size,
        tlOut.a.bits.mask,
        req.is_store,
        tlOut.a.bits.data,
        req.data
      )
    }

    assert(legal, "illegal TL req gen")
    tlOut.a.valid := req.valid
    tlOut.a.bits := bits
    tlOut.b.ready := true.B
    tlOut.c.valid := false.B
    tlOut.d.ready := true.B
    tlOut.e.valid := false.B

    println(s"======= MemTraceDriver: TL data width: ${tlOut.params.dataBits}")

    dontTouch(tlOut.a)
    dontTouch(tlOut.d)
  }

  // Give some slack time after trace EOF to the downstream system so that we
  // make sure to receive all outstanding responses.
  val finishCounter = RegInit(200.U(64.W))
  when(sim.io.trace_read.finished) {
    finishCounter := finishCounter - 1.U
  }
  io.finished := (finishCounter === 0.U)
  // when(io.finished) {
  //   assert(
  //     false.B,
  //     "\n\n\nsimulation Successfully finished\n\n\n (this assertion intentional fail upon MemTracer termination)"
  //   )
  // }
}


class SimMemTrace(filename: String, numLanes: Int)
    extends BlackBox(
      Map("FILENAME" -> filename, "NUM_LANES" -> numLanes)
    )
    with HasBlackBoxResource {
  val traceLineT = new TraceLine
  val addrW = traceLineT.address.getWidth
  val sizeW = traceLineT.size.getWidth
  val dataW = traceLineT.data.getWidth

  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())

    // These names have to match declarations in the Verilog code, eg.
    // trace_read_address.
    val trace_read = new Bundle { // can't use HasTraceLine because this doesn't have source
      val ready = Input(Bool())
      val valid = Output(UInt(numLanes.W))
      // Chisel can't interface with Verilog 2D port, so flatten all lanes into
      // single wide 1D array.
      // TODO: assumes 64-bit address.
      val cycle = Input(UInt(64.W))
      val address = Output(UInt((addrW * numLanes).W))
      val is_store = Output(UInt(numLanes.W))
      val size = Output(UInt((sizeW * numLanes).W))
      val data = Output(UInt((dataW * numLanes).W))
      val finished = Output(Bool())
    }
  })

  addResource("/vsrc/SimMemTrace.v")
  addResource("/csrc/SimMemTrace.cc")
  addResource("/csrc/SimMemTrace.h")
}

class MemTraceLogger(
    numLanes: Int,
    // base filename for the generated trace files. full filename will be
    // suffixed depending on `reqEnable`/`respEnable`/`loggerName`.
    filename: String,
    reqEnable: Boolean = true,
    respEnable: Boolean = true,
    // filename suffix that is unique to this logger module.
    loggerName: String = ".logger"
)(implicit
    p: Parameters
) extends LazyModule {
  val node = TLIdentityNode()

  // val beatBytes = 8 // FIXME: hardcoded
  // val node = TLManagerNode(Seq.tabulate(numLanes) { _ =>
  //   TLSlavePortParameters.v1(
  //     Seq(
  //       TLSlaveParameters.v1(
  //         address = List(AddressSet(0x0000, 0xffffff)), // FIXME: hardcoded
  //         supportsGet = TransferSizes(1, beatBytes),
  //         supportsPutPartial = TransferSizes(1, beatBytes),
  //         supportsPutFull = TransferSizes(1, beatBytes)
  //       )
  //     ),
  //     beatBytes = beatBytes
  //   )
  // })

  // Copied from freechips.rocketchip.trailingZeros which only supports Scala
  // integers
  def trailingZeros(x: UInt): UInt = {
    Mux(x === 0.U, x.widthOption.get.U, Log2(x & -x))
  }

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val numReqs = Output(UInt(64.W))
      val numResps = Output(UInt(64.W))
      val reqBytes = Output(UInt(64.W))
      val respBytes = Output(UInt(64.W))
    })

    val numReqs = RegInit(0.U(64.W))
    val numResps = RegInit(0.U(64.W))
    val reqBytes = RegInit(0.U(64.W))
    val respBytes = RegInit(0.U(64.W))
    io.numReqs := numReqs
    io.numResps := numResps
    io.reqBytes := reqBytes
    io.respBytes := respBytes

    val simReq =
      if (reqEnable)
        Some(Module(new SimMemTraceLogger(false, s"${filename}.${loggerName}.req", numLanes)))
      else None
    val simResp =
      if (respEnable)
        Some(Module(new SimMemTraceLogger(true, s"${filename}.${loggerName}.resp", numLanes)))
      else None
    if (simReq.isDefined) {
      simReq.get.io.clock := clock
      simReq.get.io.reset := reset.asBool
    }
    if (simResp.isDefined) {
      simResp.get.io.clock := clock
      simResp.get.io.reset := reset.asBool
    }

    val laneReqs = Wire(Vec(numLanes, new TraceLine))
    val laneResps = Wire(Vec(numLanes, new TraceLine))

    assert(
      numLanes == node.in.length,
      "`numLanes` does not match the number of TL edges connected to the MemTraceLogger"
    )

    // snoop on the TileLink edges to log traffic
    ((node.in zip node.out) zip (laneReqs zip laneResps)).foreach {
      case (((tlIn, _), (tlOut, _)), (req, resp)) =>
        tlOut.a <> tlIn.a
        tlIn.d <> tlOut.d

        // requests on TL A channel
        //
        req.valid := tlIn.a.valid
        req.size := tlIn.a.bits.size
        req.is_store := TLUtils.AOpcodeIsStore(tlIn.a.bits.opcode)
        req.source := tlIn.a.bits.source
        // TL always carries the exact unaligned address that the client
        // originally requested, so no postprocessing required
        req.address := tlIn.a.bits.address

        // TL data
        //
        // When tlIn.a.bits.size is smaller than the data bus width, need to
        // figure out which byte lanes we actually accessed so that
        // we can write that to the memory trace.
        // See Section 4.5 Byte Lanes in spec 1.8.1

        // This assert only holds true for PutFullData and not PutPartialData,
        // where HIGH bits in the mask may not be contiguous.
        assert(
          PopCount(tlIn.a.bits.mask) === (1.U << tlIn.a.bits.size),
          "mask HIGH bits do not match the TL size.  This should have been handled by the TL generator logic"
        )
        val trailingZerosInMask = trailingZeros(tlIn.a.bits.mask)
        val dataW = tlIn.params.dataBits
        val mask = ~(~(0.U(dataW.W)) << ((1.U << tlIn.a.bits.size) * 8.U))
        req.data := mask & (tlIn.a.bits.data >> (trailingZerosInMask * 8.U))
        // when (req.valid) {
        //   printf("trailingZerosInMask=%d, mask=%x, data=%x\n", trailingZerosInMask, mask, req.data)
        // }

        when(req.valid) {
          TLPrintf(
            s"MemTraceLogger (${loggerName}:downstream)",
            tlIn.a.bits.address,
            tlIn.a.bits.size,
            tlIn.a.bits.mask,
            req.is_store,
            tlIn.a.bits.data,
            req.data
          )
        }

        // responses on TL D channel
        //
        resp.valid := tlOut.d.valid
        resp.size := tlOut.d.bits.size
        resp.is_store := TLUtils.DOpcodeIsStore(tlOut.d.bits.opcode)
        resp.source := tlOut.d.bits.source
        // NOTE: TL D channel doesn't carry address nor mask, so there's no easy
        // way to figure out which bytes the master actually use.  Since we
        // don't care too much about addresses in the trace anyway, just store
        // the entire bits.
        resp.address := 0.U
        resp.data := tlOut.d.bits.data
    }

    // stats
    val numReqsThisCycle =
      laneReqs.map { l => Mux(l.valid, 1.U(64.W), 0.U(64.W)) }.reduce { (v0, v1) => v0 + v1 }
    val numRespsThisCycle =
      laneResps.map { l => Mux(l.valid, 1.U(64.W), 0.U(64.W)) }.reduce { (v0, v1) => v0 + v1 }
    val reqBytesThisCycle =
      laneReqs.map { l => Mux(l.valid, 1.U(64.W) << l.size, 0.U(64.W)) }.reduce { (b0, b1) =>
        b0 + b1
      }
    val respBytesThisCycle =
      laneResps.map { l => Mux(l.valid, 1.U(64.W) << l.size, 0.U(64.W)) }.reduce { (b0, b1) =>
        b0 + b1
      }
    numReqs := numReqs + numReqsThisCycle
    numResps := numResps + numRespsThisCycle
    reqBytes := reqBytes + reqBytesThisCycle
    respBytes := respBytes + respBytesThisCycle

    // Flatten per-lane signals to the Verilog blackbox input.
    //
    // This is a clunky workaround of the fact that Chisel doesn't allow partial
    // assignment to a bitfield range of a wide signal.
    def flattenTrace(traceLogIO: Bundle with HasTraceLine, perLane: Vec[TraceLine]) = {
      // these will get optimized out
      val vecValid = Wire(Vec(numLanes, chiselTypeOf(perLane(0).valid)))
      val vecSource = Wire(Vec(numLanes, chiselTypeOf(perLane(0).source)))
      val vecAddress = Wire(Vec(numLanes, chiselTypeOf(perLane(0).address)))
      val vecIsStore = Wire(Vec(numLanes, chiselTypeOf(perLane(0).is_store)))
      val vecSize = Wire(Vec(numLanes, chiselTypeOf(perLane(0).size)))
      val vecData = Wire(Vec(numLanes, chiselTypeOf(perLane(0).data)))
      perLane.zipWithIndex.foreach { case (l, i) =>
        vecValid(i) := l.valid
        vecSource(i) := l.source
        vecAddress(i) := l.address
        vecIsStore(i) := l.is_store
        vecSize(i) := l.size
        vecData(i) := l.data
      }
      traceLogIO.valid := vecValid.asUInt
      traceLogIO.source := vecSource.asUInt
      traceLogIO.address := vecAddress.asUInt
      traceLogIO.is_store := vecIsStore.asUInt
      traceLogIO.size := vecSize.asUInt
      traceLogIO.data := vecData.asUInt
    }

    if (simReq.isDefined) {
      flattenTrace(simReq.get.io.trace_log, laneReqs)
      assert(
        simReq.get.io.trace_log.ready === true.B,
        "MemTraceLogger is expected to be always ready"
      )
    }
    if (simResp.isDefined) {
      flattenTrace(simResp.get.io.trace_log, laneResps)
      assert(
        simResp.get.io.trace_log.ready === true.B,
        "MemTraceLogger is expected to be always ready"
      )
    }
  }
}

// MemTraceLogger is bidirectional, and `isResponse` is how the DPI module tells
// itself whether it's logging the request stream or the response stream.  This
// is necessary because we have to generate slightly different trace format
// depending on this, e.g. response trace will not contain an address column.
class SimMemTraceLogger(isResponse: Boolean, filename: String, numLanes: Int)
    extends BlackBox(
      Map(
        "IS_RESPONSE" -> (if (isResponse) 1 else 0),
        "FILENAME" -> filename,
        "NUM_LANES" -> numLanes
      )
    )
    with HasBlackBoxResource {
  val traceLineT = new TraceLine
  val sourceW = traceLineT.source.getWidth
  val addrW = traceLineT.address.getWidth
  val sizeW = traceLineT.size.getWidth
  val dataW = traceLineT.data.getWidth

  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())

    val trace_log = new Bundle with HasTraceLine {
      val valid = Input(UInt(numLanes.W))
      val source = Input(UInt((sourceW * numLanes).W))
      // Chisel can't interface with Verilog 2D port, so flatten all lanes into
      // single wide 1D array.
      // TODO: assumes 64-bit address.
      val address = Input(UInt((addrW * numLanes).W))
      val is_store = Input(UInt(numLanes.W))
      val size = Input(UInt((sizeW * numLanes).W))
      val data = Input(UInt((dataW * numLanes).W))
      val ready = Output(Bool())
    }
  })

  addResource("/vsrc/SimMemTraceLogger.v")
  addResource("/csrc/SimMemTraceLogger.cc")
  addResource("/csrc/SimMemTrace.h")
}

class TLPrintf {}

object TLPrintf {
  def apply(
      printer: String,
      address: UInt,
      size: UInt,
      mask: UInt,
      is_store: Bool,
      tlData: UInt,
      reqData: UInt
  ) = {
    printf(s"${printer}: TL addr=%x, size=%d, mask=%x, store=%d", address, size, mask, is_store)
    when(is_store) {
      printf(", tlData=%x, reqData=%x", tlData, reqData)
    }
    printf("\n")
  }
}

// Synthesizable unit tests

class DummyDriver(config: CoalescerConfig)(implicit p: Parameters)
  extends LazyModule {
  val laneNodes = Seq.tabulate(config.numLanes) { i =>
    val clientParam = Seq(
      TLMasterParameters.v1(
        name = "dummy-core-node-" + i.toString,
        sourceId = IdRange(0, defaultConfig.numOldSrcIds)
        // visibility = Seq(AddressSet(0x0000, 0xffffff))
      )
    )
    TLClientNode(Seq(TLMasterPortParameters.v1(clientParam)))
  }

  // Combine N outgoing client node into 1 idenity node for diplomatic
  // connection.
  val node = TLIdentityNode()
  laneNodes.foreach { l => node := l }

  lazy val module = new DummyDriverImp(this, config)
}

class DummyDriverImp(outer: DummyDriver, config: CoalescerConfig)
    extends LazyModuleImp(outer)
    with UnitTestModule {
  val sourceIdCounter = RegInit(0.U(log2Ceil(config.numOldSrcIds).W))
  sourceIdCounter := sourceIdCounter + 1.U

  val finishCounter = RegInit(10000.U(64.W))
  finishCounter := finishCounter - 1.U
  io.finished := (finishCounter === 0.U)

  outer.laneNodes.zipWithIndex.foreach { case (node, lane) =>
    assert(node.out.length == 1)

    // generate dummy traffic to coalescer to prevent it from being optimized
    // out during synthesis
    val address = Wire(UInt(config.addressWidth.W))
    address := Cat((finishCounter + (lane.U % 3.U)), 0.U(config.wordWidth.W))
    val (tl, edge) = node.out(0)
    val (legal, bits) = edge.Put(
      fromSource = sourceIdCounter,
      toAddress = address,
      lgSize = 2.U,
      data = finishCounter + (lane.U % 3.U)
    )
    assert(legal, "illegal TL req gen")
    tl.a.valid := true.B
    tl.a.bits := bits
    tl.b.ready := true.B
    tl.c.valid := false.B
    tl.d.ready := true.B
    tl.e.valid := false.B
  }

  val dataSum = outer.laneNodes.map { node =>
    val tl = node.out(0)._1
    val data = Mux(tl.d.valid, tl.d.bits.data, 0.U)
    data
  }.reduce (_ +& _)
  // this doesn't make much sense, but it prevents the entire uncoalescer from
  // being optimized away
  finishCounter := finishCounter + dataSum
}

// A dummy harness around the coalescer for use in VLSI flow.
// Should not instantiate any memtrace modules.
class DummyCoalescer(implicit p: Parameters) extends LazyModule {
  val driver = LazyModule(new DummyDriver(defaultConfig))
  val rams = Seq.fill(defaultConfig.numLanes + 1)( // +1 for coalesced edge
    LazyModule(
      // NOTE: beatBytes here sets the data bitwidth of the upstream TileLink
      // edges globally, by way of Diplomacy communicating the TL slave
      // parameters to the upstream nodes.
      new TLRAM(address = AddressSet(0x0000, 0xffffff),
        beatBytes = (1 << defaultConfig.dataBusWidth))
    )
  )

  val coal = LazyModule(new CoalescingUnit(defaultConfig))

  coal.cpuNode :=* driver.node
  rams.foreach(_.node := coal.aggregateNode)

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with UnitTestModule {
    io.finished := driver.module.io.finished
  }
}

class DummyCoalescerTest(timeout: Int = 500000)(implicit p: Parameters)
    extends UnitTest(timeout) {
  val dut = Module(LazyModule(new DummyCoalescer).module)
  dut.io.start := io.start
  io.finished := dut.io.finished
}

// tracedriver --> coalescer --> tracelogger --> tlram
class TLRAMCoalescerLogger(implicit p: Parameters) extends LazyModule {
  // val filename = "test.trace"
  val filename = "vecadd.core1.thread4.trace"
  // val filename = "nvbit.vecadd.n100000.filter_sm0.trace"
  // TODO: use parameters for numLanes
  val numLanes = defaultConfig.numLanes

  val driver = LazyModule(new MemTraceDriver(defaultConfig, filename))
  val coreSideLogger = LazyModule(
    new MemTraceLogger(numLanes, filename, loggerName = "coreside")
  )
  val coal = LazyModule(new CoalescingUnit(defaultConfig))
  val memSideLogger = LazyModule(new MemTraceLogger(numLanes + 1, filename, loggerName = "memside"))
  val rams = Seq.fill(numLanes + 1)( // +1 for coalesced edge
    LazyModule(
      // NOTE: beatBytes here sets the data bitwidth of the upstream TileLink
      // edges globally, by way of Diplomacy communicating the TL slave
      // parameters to the upstream nodes.
      new TLRAM(address = AddressSet(0x0000, 0xffffff),
        beatBytes = (1 << defaultConfig.dataBusWidth))
    )
  )

  memSideLogger.node :=* coal.aggregateNode
  coal.cpuNode :=* coreSideLogger.node :=* driver.node
  rams.foreach { r => r.node := memSideLogger.node }

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with UnitTestModule {
    driver.module.io.start := io.start
    io.finished := driver.module.io.finished

    when(io.finished) {
      printf(
        "numReqs=%d, numResps=%d, reqBytes=%d, respBytes=%d\n",
        coreSideLogger.module.io.numReqs,
        coreSideLogger.module.io.numResps,
        coreSideLogger.module.io.reqBytes,
        coreSideLogger.module.io.respBytes
      )
      assert(
        (coreSideLogger.module.io.numReqs === coreSideLogger.module.io.numResps) &&
          (coreSideLogger.module.io.reqBytes === coreSideLogger.module.io.respBytes),
        "FAIL: requests and responses traffic to the coalescer do not match"
      )
    }
  }
}

class TLRAMCoalescerLoggerTest(timeout: Int = 500000)(implicit p: Parameters)
    extends UnitTest(timeout) {
  val dut = Module(LazyModule(new TLRAMCoalescerLogger).module)
  dut.io.start := io.start
  io.finished := dut.io.finished
}

// tracedriver --> coalescer --> tlram
class TLRAMCoalescer(implicit p: Parameters) extends LazyModule {
  // TODO: use parameters for numLanes
  val numLanes = 4
  val filename = "vecadd.core1.thread4.trace"
  val coal = LazyModule(new CoalescingUnit(defaultConfig))
  val driver = LazyModule(new MemTraceDriver(defaultConfig, filename))
  val rams = Seq.fill(numLanes + 1)( // +1 for coalesced edge
    LazyModule(
      // NOTE: beatBytes here sets the data bitwidth of the upstream TileLink
      // edges globally, by way of Diplomacy communicating the TL slave
      // parameters to the upstream nodes.
      new TLRAM(address = AddressSet(0x0000, 0xffffff),
        beatBytes = (1 << defaultConfig.dataBusWidth))
    )
  )

  coal.cpuNode :=* driver.node
  rams.foreach { r => r.node := coal.aggregateNode }

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with UnitTestModule {
    driver.module.io.start := io.start
    io.finished := driver.module.io.finished
  }
}

class TLRAMCoalescerTest(timeout: Int = 500000)(implicit p: Parameters) extends UnitTest(timeout) {
  val dut = Module(LazyModule(new TLRAMCoalescer).module)
  dut.io.start := io.start
  io.finished := dut.io.finished
}
