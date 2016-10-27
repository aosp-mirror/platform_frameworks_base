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

#ifndef AAPT_TEST_COMMON_H
#define AAPT_TEST_COMMON_H

#include <iostream>

#include "android-base/logging.h"
#include "android-base/macros.h"
#include "gtest/gtest.h"

#include "ConfigDescription.h"
#include "Debug.h"
#include "ResourceTable.h"
#include "ResourceUtils.h"
#include "ValueVisitor.h"
#include "io/File.h"
#include "process/IResourceTableConsumer.h"
#include "util/StringPiece.h"

//
// GTEST 1.7 doesn't explicitly cast to bool, which causes explicit operators to
// fail to compile.
//
#define AAPT_ASSERT_TRUE(v) ASSERT_TRUE(bool(v))
#define AAPT_ASSERT_FALSE(v) ASSERT_FALSE(bool(v))
#define AAPT_EXPECT_TRUE(v) EXPECT_TRUE(bool(v))
#define AAPT_EXPECT_FALSE(v) EXPECT_FALSE(bool(v))

namespace aapt {
namespace test {

struct DummyDiagnosticsImpl : public IDiagnostics {
  void Log(Level level, DiagMessageActual& actual_msg) override {
    switch (level) {
      case Level::Note:
        return;

      case Level::Warn:
        std::cerr << actual_msg.source << ": warn: " << actual_msg.message
                  << "." << std::endl;
        break;

      case Level::Error:
        std::cerr << actual_msg.source << ": error: " << actual_msg.message
                  << "." << std::endl;
        break;
    }
  }
};

inline IDiagnostics* GetDiagnostics() {
  static DummyDiagnosticsImpl diag;
  return &diag;
}

inline ResourceName ParseNameOrDie(const StringPiece& str) {
  ResourceNameRef ref;
  CHECK(ResourceUtils::ParseResourceName(str, &ref)) << "invalid resource name";
  return ref.ToResourceName();
}

inline ConfigDescription ParseConfigOrDie(const StringPiece& str) {
  ConfigDescription config;
  CHECK(ConfigDescription::Parse(str, &config)) << "invalid configuration";
  return config;
}

template <typename T>
T* GetValueForConfigAndProduct(ResourceTable* table,
                               const StringPiece& res_name,
                               const ConfigDescription& config,
                               const StringPiece& product) {
  Maybe<ResourceTable::SearchResult> result =
      table->FindResource(ParseNameOrDie(res_name));
  if (result) {
    ResourceConfigValue* config_value =
        result.value().entry->FindValue(config, product);
    if (config_value) {
      return ValueCast<T>(config_value->value.get());
    }
  }
  return nullptr;
}

template <typename T>
T* GetValueForConfig(ResourceTable* table, const StringPiece& res_name,
                     const ConfigDescription& config) {
  return GetValueForConfigAndProduct<T>(table, res_name, config, {});
}

template <typename T>
T* GetValue(ResourceTable* table, const StringPiece& res_name) {
  return GetValueForConfig<T>(table, res_name, {});
}

class TestFile : public io::IFile {
 public:
  explicit TestFile(const StringPiece& path) : source_(path) {}

  std::unique_ptr<io::IData> OpenAsData() override { return {}; }

  const Source& GetSource() const override { return source_; }

 private:
  DISALLOW_COPY_AND_ASSIGN(TestFile);

  Source source_;
};

}  // namespace test
}  // namespace aapt

#endif /* AAPT_TEST_COMMON_H */
