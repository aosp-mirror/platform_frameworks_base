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
#ifndef AAPT_DIAGNOSTICS_H_
#define AAPT_DIAGNOSTICS_H_

#include <iostream>
#include <sstream>
#include <string>

#include "android-base/macros.h"
#include "androidfw/IDiagnostics.h"
#include "androidfw/Source.h"
#include "androidfw/StringPiece.h"
#include "util/Util.h"

namespace aapt {
class StdErrDiagnostics : public android::IDiagnostics {
 public:
  StdErrDiagnostics() = default;

  void Log(Level level, android::DiagMessageActual& actual_msg) override {
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

}  // namespace aapt

#endif /* AAPT_DIAGNOSTICS_H_ */
