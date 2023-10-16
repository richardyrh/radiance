/*
package rocket

import chisel3._
import chisel3.util._
import chisel3.experimental._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.unittest._


// Test setup

// ┌───────────────────┐
// │                   │
// │                   │
// │   MemTraceDriver  │
// │                   │
// │                   │
// └─────────┬─────────┘
//           │
//           │
// ┌─────────▼─────────┐
// │                   │
// │                   │
// │  CoalescingUnit   │
// │                   │
// │                   │
// └─────────┬─────────┘
//           │
//           │
// ┌─────────▼─────────┐
// │                   │
// │   XBar            │
// │                   │
// └─────────┬─────────┘
//           │
//           │
// ┌─────────▼─────────┐
// │                   │
// │                   │
// │  VortexFatBank    │
// │                   │
// │                   │
// └─────────┬─────────┘
//           │
// ┌─────────▼─────────┐
// │                   │
// │                   │
// │   TLRAM           │
// │                   │
// │                   │
// └───────────────────┘

class VortexFatBankTest(filename: String, timeout: Int = 500000)(implicit
    p: Parameters
) extends UnitTest(timeout) {
    val dut = Module(LazyModule(new VortexFatBankTestbench(filename)).module)
    dut.io.start := io.start
    io.finished := dut.io.finished
}

class VortexFatBankTestbench(filename: String)(implicit p: Parameters)
    extends LazyModule {
    val numLanes = p(SIMTCoreKey).get.nLanes
    val config = defaultConfig.copy(
        numLanes = numLanes,
        addressWidth = 32
    )

    val driver = LazyModule(new MemTraceDriver(config, filename))
    val coreSideLogger = LazyModule(
        new MemTraceLogger(numLanes, filename, loggerName = "coreside")
    )
    val coal = LazyModule(new CoalescingUnit(config))
    val memSideLogger = LazyModule(
        new MemTraceLogger(numLanes + 1, filename, loggerName = "memside")
    )
    val xbar = LazyModule(new TLXbar)

    val vortexFatBank = LazyModule(new VortexFatBank)

    val ram = LazyModule(
            // NOTE: beatBytes here sets the data bitwidth of the upstream TileLink
            // edges globally, by way of Diplomacy communicating the TL slave
            // parameters to the upstream nodes.
            new TLRAM(
                address = AddressSet(0x0, BigInt("0xFFFFFFFF", 16)),
                beatBytes = (1 << config.dataBusWidth)
            )
        )
    
    ram.node := vortexFatBank.bankToL2Node
    vortexFatBank.coalToBankNode := xbar.node :=* memSideLogger.node
    memSideLogger.node :=* coal.aggregateNode
    coal.cpuNode :=* coreSideLogger.node :=* driver.node
    
    lazy val module = new Impl
    class Impl extends LazyModuleImp(this) with UnitTestModule {
        // io.start is unused since MemTraceDriver doesn't accept io.start
        io.finished := driver.module.io.finished

        // TODO: check correctness
    }
}
*/