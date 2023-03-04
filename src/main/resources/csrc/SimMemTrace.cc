#ifndef NO_VPI
#include <vpi_user.h>
#include <svdpi.h>
#endif
#include <string>
#include <cstdio>
#include <cassert>
#include <unistd.h>
#include "SimMemTrace.h"

MemTraceReader::MemTraceReader(const std::string &filename) {
  char cwd[4096];
  if (getcwd(cwd, sizeof(cwd))) {
    printf("MemTraceReader: current working dir: %s\n", cwd);
  }

  infile.open(filename);
  if (infile.fail()) {
    fprintf(stderr, "failed to open file %s\n", filename.c_str());
  }
}

MemTraceReader::~MemTraceReader() {
  infile.close();
  printf("MemTraceReader destroyed\n");
}

// Parse trace file in its entirety and store it into internal structure.
// TODO: might block for a long time when the trace gets big, check if need to
// be broken down
void MemTraceReader::parse() {
  MemTraceLine line;

  printf("MemTraceReader: started parsing\n");

  while (infile >> line.cycle >> line.loadstore >> line.core_id >>
         line.thread_id >> std::hex >> line.address >> line.data >> std::dec >>
         line.data_size) {
    line.valid = true;
    trace.push_back(line);
  }
  read_pos = trace.cbegin();

  printf("MemTraceReader: finished parsing\n");
}

// Try to read a memory request that might have happened at a given cycle, on
// given thread.  In case no request happened at that point, return an empty
// line with .valid = false.
MemTraceLine MemTraceReader::read_trace_at(const long cycle,
                                           const int thread_id) {
  MemTraceLine line;
  line.valid = false;

  printf("tick(): cycle=%ld\n", cycle);

  if (finished()) {
    return line;
  }

  line = *read_pos;
  // It should always be guaranteed that the next line is not read yet.
  if (line.cycle < cycle) {
    fprintf(stderr, "line.cycle=%ld, cycle=%ld\n", line.cycle, cycle);
    assert(false && "some trace lines are left unread in the past");
  }

  if (line.cycle > cycle) {
    // It's not ready to read this line yet.
    return MemTraceLine{};
  } else if (line.cycle == cycle) {
    printf("fire! cycle=%ld, valid=%d\n", cycle, line.valid);
    // FIXME! Currently thread_id is assumed to be in round-robin order, e.g.
    // 0->1->2->3->0->..., both in the trace file and the order the caller calls
    // this function.  If this is not true, we cannot simply monotonically
    // increment read_pos.
    ++read_pos;
  }

  return line;
}

extern "C" void memtrace_init(const char *filename) {
  printf("memtrace_init: filename=[%s]\n", filename);

  reader = std::make_unique<MemTraceReader>(filename);
  // parse file upfront
  reader->parse();
}

extern "C" void memtrace_query(unsigned char trace_read_ready,
                               unsigned long trace_read_cycle,
                               int trace_read_thread_id,
                               unsigned char *trace_read_valid,
                               unsigned long *trace_read_address,
                               unsigned char *trace_read_finished) {
  printf("memtrace_query(cycle=%ld, tid=%d)\n", trace_read_cycle,
         trace_read_thread_id);

  if (!trace_read_ready) {
    return;
  }

  auto line = reader->read_trace_at(trace_read_cycle, trace_read_thread_id);
  *trace_read_valid = line.valid;
  *trace_read_address = line.address;
  // This means finished and valid will go up at the same cycle.  Need to
  // handle this without skipping the last line.
  *trace_read_finished = reader->finished();

  return;
}
