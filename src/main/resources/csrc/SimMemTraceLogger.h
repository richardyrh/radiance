#include <vector>
#include <fstream>

class MemTraceLogger;

struct MemTraceLine {
  bool valid = false;
  long cycle = 0;
  char loadstore[10];
  int core_id = 0;
  int lane_id = 0;
  unsigned long address = 0;
  unsigned long data = 0;
  int log_data_size = 0;
};

class MemTraceLogger {
public:
  MemTraceLogger(const std::string &filename);
  ~MemTraceLogger();
  // void parse();
  // MemTraceLine read_trace_at(const long cycle, const int lane_id);
  // bool finished() const { return read_pos == trace.cend(); }

  std::ifstream infile;
  // std::vector<MemTraceLine> trace;
  // std::vector<MemTraceLine>::const_iterator read_pos;
};

extern "C" void memtracelogger_init(const char *filename);
extern "C" void memtracelogger_log(unsigned char trace_log_valid,
                                   unsigned long trace_log_cycle,
                                   unsigned long trace_log_address,
                                   unsigned int trace_log_lane_id,
                                   unsigned char *trace_log_ready);
