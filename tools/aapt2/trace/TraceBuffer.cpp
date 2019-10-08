/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "TraceBuffer.h"

#include <chrono>
#include <sstream>
#include <unistd.h>
#include <vector>

#include <inttypes.h>

#include "android-base/utf8.h"

#include "util/Files.h"

namespace aapt {
namespace tracebuffer {

namespace {

constexpr char kBegin = 'B';
constexpr char kEnd = 'E';

struct TracePoint {
  pid_t tid;
  int64_t time;
  std::string tag;
  char type;
};

std::vector<TracePoint> traces;

int64_t GetTime() noexcept {
  auto now = std::chrono::steady_clock::now();
  return std::chrono::duration_cast<std::chrono::microseconds>(now.time_since_epoch()).count();
}

} // namespace anonymous

void AddWithTime(const std::string& tag, char type, int64_t time) noexcept {
  TracePoint t = {getpid(), time, tag, type};
  traces.emplace_back(t);
}

void Add(const std::string& tag, char type) noexcept {
  AddWithTime(tag, type, GetTime());
}




void Flush(const std::string& basePath) {
  TRACE_CALL();
  if (basePath.empty()) {
    return;
  }

  std::stringstream s;
  s << basePath << aapt::file::sDirSep << "report_aapt2_" << getpid() << ".json";
  FILE* f = android::base::utf8::fopen(s.str().c_str(), "a");
  if (f == nullptr) {
    return;
  }

  for(const TracePoint& trace : traces) {
    fprintf(f, "{\"ts\" : \"%" PRIu64 "\", \"ph\" : \"%c\", \"tid\" : \"%d\" , \"pid\" : \"%d\", "
            "\"name\" : \"%s\" },\n", trace.time, trace.type, 0, trace.tid, trace.tag.c_str());
  }
  fclose(f);
  traces.clear();
}

} // namespace tracebuffer

void BeginTrace(const std::string& tag) {
  tracebuffer::Add(tag, tracebuffer::kBegin);
}

void EndTrace() {
  tracebuffer::Add("", tracebuffer::kEnd);
}

Trace::Trace(const std::string& tag) {
  tracebuffer::Add(tag, tracebuffer::kBegin);
}

Trace::Trace(const std::string& tag, const std::vector<android::StringPiece>& args) {
  std::stringstream s;
  s << tag;
  s << " ";
  for (auto& arg : args) {
    s << arg.to_string();
    s << " ";
  }
  tracebuffer::Add(s.str(), tracebuffer::kBegin);
}

Trace::~Trace() {
  tracebuffer::Add("", tracebuffer::kEnd);
}

FlushTrace::FlushTrace(const std::string& basepath, const std::string& tag)
    : basepath_(basepath)  {
  tracebuffer::Add(tag, tracebuffer::kBegin);
}

FlushTrace::FlushTrace(const std::string& basepath, const std::string& tag,
    const std::vector<android::StringPiece>& args) : basepath_(basepath) {
  std::stringstream s;
  s << tag;
  s << " ";
  for (auto& arg : args) {
    s << arg.to_string();
    s << " ";
  }
  tracebuffer::Add(s.str(), tracebuffer::kBegin);
}

FlushTrace::FlushTrace(const std::string& basepath, const std::string& tag,
    const std::vector<std::string>& args) : basepath_(basepath){
  std::stringstream s;
  s << tag;
  s << " ";
  for (auto& arg : args) {
    s << arg;
    s << " ";
  }
  tracebuffer::Add(s.str(), tracebuffer::kBegin);
}

FlushTrace::~FlushTrace() {
  tracebuffer::Add("", tracebuffer::kEnd);
  tracebuffer::Flush(basepath_);
}

} // namespace aapt

