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
  char type;
  pid_t tid;
  int64_t time;
  std::string tag;
};

std::vector<TracePoint> traces;
bool enabled = true;
constinit std::chrono::steady_clock::time_point startTime = {};

int64_t GetTime() noexcept {
  auto now = std::chrono::steady_clock::now();
  if (startTime == decltype(tracebuffer::startTime){}) {
    startTime = now;
  }
  return std::chrono::duration_cast<std::chrono::microseconds>(now - startTime).count();
}

void AddWithTime(std::string tag, char type, int64_t time) noexcept {
  TracePoint t = {type, getpid(), time, std::move(tag)};
  traces.emplace_back(std::move(t));
}

void Add(std::string tag, char type) noexcept {
  AddWithTime(std::move(tag), type, GetTime());
}

void Flush(const std::string& basePath) {
  if (basePath.empty()) {
    return;
  }
  BeginTrace(__func__);  // We can't do much here, only record that it happened.

  std::ostringstream s;
  s << basePath << aapt::file::sDirSep << "report_aapt2_" << getpid() << ".json";
  FILE* f = android::base::utf8::fopen(s.str().c_str(), "a");
  if (f == nullptr) {
    return;
  }

  // Wrap the trace in a JSON array [] to make Chrome/Perfetto UI handle it.
  char delimiter = '[';
  for (const TracePoint& trace : traces) {
    fprintf(f,
            "%c{\"ts\" : \"%" PRIu64
            "\", \"ph\" : \"%c\", \"tid\" : \"%d\" , \"pid\" : \"%d\", \"name\" : \"%s\" }\n",
            delimiter, trace.time, trace.type, 0, trace.tid, trace.tag.c_str());
    delimiter = ',';
  }
  if (!traces.empty()) {
    fprintf(f, "]");
  }
  fclose(f);
  traces.clear();
}

}  // namespace

} // namespace tracebuffer

void BeginTrace(std::string tag) {
  if (!tracebuffer::enabled) return;
  tracebuffer::Add(std::move(tag), tracebuffer::kBegin);
}

void EndTrace(std::string tag) {
  if (!tracebuffer::enabled) return;
  tracebuffer::Add(std::move(tag), tracebuffer::kEnd);
}

bool Trace::enable(bool value) {
  return tracebuffer::enabled = value;
}

Trace::Trace(const char* tag) {
  if (!tracebuffer::enabled) return;
  tag_.assign(tag);
  tracebuffer::Add(tag_, tracebuffer::kBegin);
}

Trace::Trace(std::string tag) : tag_(std::move(tag)) {
  if (!tracebuffer::enabled) return;
  tracebuffer::Add(tag_, tracebuffer::kBegin);
}

template <class SpanOfStrings>
std::string makeTag(std::string_view tag, const SpanOfStrings& args) {
  std::ostringstream s;
  s << tag;
  if (!args.empty()) {
    for (const auto& arg : args) {
      s << ' ';
      s << arg;
    }
  }
  return std::move(s).str();
}

Trace::Trace(std::string_view tag, const std::vector<android::StringPiece>& args) {
  if (!tracebuffer::enabled) return;
  tag_ = makeTag(tag, args);
  tracebuffer::Add(tag_, tracebuffer::kBegin);
}

Trace::~Trace() {
  if (!tracebuffer::enabled) return;
  tracebuffer::Add(std::move(tag_), tracebuffer::kEnd);
}

FlushTrace::FlushTrace(std::string_view basepath, std::string_view tag) {
  if (!Trace::enable(!basepath.empty())) return;
  basepath_.assign(basepath);
  tag_.assign(tag);
  tracebuffer::Add(tag_, tracebuffer::kBegin);
}

FlushTrace::FlushTrace(std::string_view basepath, std::string_view tag,
                       const std::vector<android::StringPiece>& args) {
  if (!Trace::enable(!basepath.empty())) return;
  basepath_.assign(basepath);
  tag_ = makeTag(tag, args);
  tracebuffer::Add(tag_, tracebuffer::kBegin);
}

FlushTrace::FlushTrace(std::string_view basepath, std::string_view tag,
                       const std::vector<std::string>& args) {
  if (!Trace::enable(!basepath.empty())) return;
  basepath_.assign(basepath);
  tag_ = makeTag(tag, args);
  tracebuffer::Add(tag_, tracebuffer::kBegin);
}

FlushTrace::~FlushTrace() {
  if (!tracebuffer::enabled) return;
  tracebuffer::Add(std::move(tag_), tracebuffer::kEnd);
  tracebuffer::Flush(basepath_);
}

}  // namespace aapt
