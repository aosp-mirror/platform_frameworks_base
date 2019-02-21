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

#ifndef AAPT_TRACEBUFFER_H
#define AAPT_TRACEBUFFER_H

#include <string>
#include <vector>

#include <androidfw/StringPiece.h>

namespace aapt {

// Record timestamps for beginning and end of a task and generate systrace json fragments.
// This is an in-process ftrace which has the advantage of being platform independent.
// These methods are NOT thread-safe since aapt2 is not multi-threaded.

// Convenience RIAA object to automatically finish an event when object goes out of scope.
class Trace {
public:
  Trace(const std::string& tag);
  Trace(const std::string& tag, const std::vector<android::StringPiece>& args);
  ~Trace();
};

// Manual markers.
void BeginTrace(const std::string& tag);
void EndTrace();

// A master trace is required to flush events to disk. Events are formatted in systrace
// json format.
class FlushTrace {
public:
  explicit FlushTrace(const std::string& basepath, const std::string& tag);
  explicit FlushTrace(const std::string& basepath, const std::string& tag,
      const std::vector<android::StringPiece>& args);
  explicit FlushTrace(const std::string& basepath, const std::string& tag,
      const std::vector<std::string>& args);
  ~FlushTrace();
private:
  std::string basepath_;
};

#define TRACE_CALL() Trace __t(__func__)
#define TRACE_NAME(tag) Trace __t(tag)
#define TRACE_NAME_ARGS(tag, args) Trace __t(tag, args)

#define TRACE_FLUSH(basename, tag) FlushTrace __t(basename, tag)
#define TRACE_FLUSH_ARGS(basename, tag, args) FlushTrace __t(basename, tag, args)
} // namespace aapt
#endif //AAPT_TRACEBUFFER_H
