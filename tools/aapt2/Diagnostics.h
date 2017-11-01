/*
 * Copyright (C) 2015 The Android Open Source Project
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

#ifndef AAPT_DIAGNOSTICS_H
#define AAPT_DIAGNOSTICS_H

#include <iostream>
#include <sstream>
#include <string>

#include "android-base/macros.h"
#include "androidfw/StringPiece.h"

#include "Source.h"
#include "util/Util.h"

namespace aapt {

struct DiagMessageActual {
  Source source;
  std::string message;
};

struct DiagMessage {
 public:
  DiagMessage() = default;

  explicit DiagMessage(const android::StringPiece& src) : source_(src) {}

  explicit DiagMessage(const Source& src) : source_(src) {}

  explicit DiagMessage(size_t line) : source_(Source().WithLine(line)) {}

  template <typename T>
  DiagMessage& operator<<(const T& value) {
    message_ << value;
    return *this;
  }

  DiagMessageActual Build() const {
    return DiagMessageActual{source_, message_.str()};
  }

 private:
  Source source_;
  std::stringstream message_;
};

template <>
inline DiagMessage& DiagMessage::operator<<(const ::std::u16string& value) {
  message_ << android::StringPiece16(value);
  return *this;
}

struct IDiagnostics {
  virtual ~IDiagnostics() = default;

  enum class Level { Note, Warn, Error };

  virtual void Log(Level level, DiagMessageActual& actualMsg) = 0;

  virtual void Error(const DiagMessage& message) {
    DiagMessageActual actual = message.Build();
    Log(Level::Error, actual);
  }

  virtual void Warn(const DiagMessage& message) {
    DiagMessageActual actual = message.Build();
    Log(Level::Warn, actual);
  }

  virtual void Note(const DiagMessage& message) {
    DiagMessageActual actual = message.Build();
    Log(Level::Note, actual);
  }
};

class StdErrDiagnostics : public IDiagnostics {
 public:
  StdErrDiagnostics() = default;

  void Log(Level level, DiagMessageActual& actual_msg) override {
    const char* tag;

    switch (level) {
      case Level::Error:
        num_errors_++;
        if (num_errors_ > 20) {
          return;
        }
        tag = "error";
        break;

      case Level::Warn:
        tag = "warn";
        break;

      case Level::Note:
        tag = "note";
        break;
    }

    if (!actual_msg.source.path.empty()) {
      std::cerr << actual_msg.source << ": ";
    }
    std::cerr << tag << ": " << actual_msg.message << "." << std::endl;
  }

 private:
  size_t num_errors_ = 0;

  DISALLOW_COPY_AND_ASSIGN(StdErrDiagnostics);
};

class SourcePathDiagnostics : public IDiagnostics {
 public:
  SourcePathDiagnostics(const Source& src, IDiagnostics* diag)
      : source_(src), diag_(diag) {}

  void Log(Level level, DiagMessageActual& actual_msg) override {
    actual_msg.source.path = source_.path;
    diag_->Log(level, actual_msg);
  }

 private:
  Source source_;
  IDiagnostics* diag_;

  DISALLOW_COPY_AND_ASSIGN(SourcePathDiagnostics);
};

}  // namespace aapt

#endif /* AAPT_DIAGNOSTICS_H */
