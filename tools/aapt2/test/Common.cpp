/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "test/Common.h"

using android::ConfigDescription;

namespace aapt {
namespace test {

struct TestDiagnosticsImpl : public IDiagnostics {
  void Log(Level level, DiagMessageActual& actual_msg) override {
    switch (level) {
      case Level::Note:
        return;

      case Level::Warn:
        std::cerr << actual_msg.source << ": warn: " << actual_msg.message << "." << std::endl;
        break;

      case Level::Error:
        std::cerr << actual_msg.source << ": error: " << actual_msg.message << "." << std::endl;
        break;
    }
  }
};

IDiagnostics* GetDiagnostics() {
  static TestDiagnosticsImpl diag;
  return &diag;
}

template <>
Value* GetValueForConfigAndProduct<Value>(ResourceTable* table,
                                          const android::StringPiece& res_name,
                                          const ConfigDescription& config,
                                          const android::StringPiece& product) {
  std::optional<ResourceTable::SearchResult> result = table->FindResource(ParseNameOrDie(res_name));
  if (result) {
    ResourceConfigValue* config_value = result.value().entry->FindValue(config, product);
    if (config_value) {
      return config_value->value.get();
    }
  }
  return nullptr;
}

}  // namespace test
}  // namespace aapt
