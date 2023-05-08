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

#ifndef _ANDROID_DIAGNOSTICS_H
#define _ANDROID_DIAGNOSTICS_H

#include <sstream>
#include <string>

#include "Source.h"
#include "android-base/macros.h"
#include "androidfw/StringPiece.h"

namespace android {

struct DiagMessageActual {
  Source source;
  std::string message;
};

struct DiagMessage {
 public:
  DiagMessage() = default;

  explicit DiagMessage(android::StringPiece src) : source_(src) {
  }

  explicit DiagMessage(const Source& src) : source_(src) {
  }

  explicit DiagMessage(size_t line) : source_(Source().WithLine(line)) {
  }

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
  message_ << value;
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

  virtual void SetVerbose(bool val) {
    verbose_ = val;
  }

  virtual bool IsVerbose() {
    return verbose_;
  }

  private:
    bool verbose_ = false;
};

class SourcePathDiagnostics : public IDiagnostics {
 public:
  SourcePathDiagnostics(const Source& src, IDiagnostics* diag) : source_(src), diag_(diag) {
  }

  void Log(Level level, DiagMessageActual& actual_msg) override {
    actual_msg.source.path = source_.path;
    diag_->Log(level, actual_msg);
    if (level == Level::Error) {
      error = true;
    }
  }

  bool HadError() {
    return error;
  }

  void SetVerbose(bool val) override {
    diag_->SetVerbose(val);
  }

  bool IsVerbose() override {
    return diag_->IsVerbose();
  }

 private:
  Source source_;
  IDiagnostics* diag_;
  bool error = false;

  DISALLOW_COPY_AND_ASSIGN(SourcePathDiagnostics);
};

class NoOpDiagnostics : public IDiagnostics {
 public:
  NoOpDiagnostics() = default;

  void Log(Level level, DiagMessageActual& actual_msg) override {
    (void)level;
    (void)actual_msg;
  }

  DISALLOW_COPY_AND_ASSIGN(NoOpDiagnostics);
};

}  // namespace android

#endif /* _ANDROID_DIAGNOSTICS_H */
