package freechips.rocketchip.tilelink

import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.subsystem.BaseSubsystem
import org.chipsalliance.cde.config.Parameters

// The trait is attached to DigitalTop of Chipyard system, informing it indeed
// has the ability to attach GPU tracer node onto the system bus
trait CanHaveMemtraceCore { this: BaseSubsystem =>
  implicit val p: Parameters

  p(MemtraceCoreKey).map { param =>
    // Safe to use get as WithMemtraceCore requires WithNLanes to be defined
    val simtParam = p(SIMTCoreKey).get
    val config = defaultConfig.copy(numLanes = simtParam.nLanes)
    val numLanes = simtParam.nLanes
    val filename = param.tracefilename
    val tracer = LazyModule(
      new MemTraceDriver(config, filename, param.traceHasSource)(p)
    )
    val coreSideLogger = LazyModule(
      new MemTraceLogger(numLanes, filename, loggerName = "coreside")
    )
    val memSideLogger = LazyModule(
      new MemTraceLogger(numLanes + 1, filename, loggerName = "memside")
    )
    // Must use :=* to ensure the N edges from Tracer doesn't get merged into 1
    // when connecting to SBus
    println(
      s"============ MemTraceDriver instantiated [filename=${param.tracefilename}]"
    )
    val upstream = p(CoalescerKey) match {
      case Some(coalParam) => {
        val coal = LazyModule(new CoalescingUnit(coalParam))
        println(s"============ CoalescingUnit instantiated [numLanes=${coalParam.numLanes}]")
        coal.cpuNode :=* coreSideLogger.node :=* tracer.node // N lanes
        memSideLogger.node :=* coal.aggregateNode            // N+1 lanes
        memSideLogger.node
      }
      case None => tracer.node
    }
    sbus.fromPort(Some("gpu-tracer"))() :=* upstream
  }
}