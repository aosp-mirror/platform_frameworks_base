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
#include "androidfw/StringPiece.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"

#include "ConfigDescription.h"
#include "Debug.h"
#include "ResourceTable.h"
#include "ResourceUtils.h"
#include "ResourceValues.h"
#include "ValueVisitor.h"
#include "io/File.h"
#include "process/IResourceTableConsumer.h"

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

IDiagnostics* GetDiagnostics();

inline ResourceName ParseNameOrDie(const android::StringPiece& str) {
  ResourceNameRef ref;
  CHECK(ResourceUtils::ParseResourceName(str, &ref)) << "invalid resource name";
  return ref.ToResourceName();
}

inline ConfigDescription ParseConfigOrDie(const android::StringPiece& str) {
  ConfigDescription config;
  CHECK(ConfigDescription::Parse(str, &config)) << "invalid configuration";
  return config;
}

template <typename T = Value>
T* GetValueForConfigAndProduct(ResourceTable* table, const android::StringPiece& res_name,
                               const ConfigDescription& config,
                               const android::StringPiece& product) {
  Maybe<ResourceTable::SearchResult> result = table->FindResource(ParseNameOrDie(res_name));
  if (result) {
    ResourceConfigValue* config_value = result.value().entry->FindValue(config, product);
    if (config_value) {
      return ValueCast<T>(config_value->value.get());
    }
  }
  return nullptr;
}

template <>
Value* GetValueForConfigAndProduct<Value>(ResourceTable* table,
                                          const android::StringPiece& res_name,
                                          const ConfigDescription& config,
                                          const android::StringPiece& product);

template <typename T = Value>
T* GetValueForConfig(ResourceTable* table, const android::StringPiece& res_name,
                     const ConfigDescription& config) {
  return GetValueForConfigAndProduct<T>(table, res_name, config, {});
}

template <typename T = Value>
T* GetValue(ResourceTable* table, const android::StringPiece& res_name) {
  return GetValueForConfig<T>(table, res_name, {});
}

class TestFile : public io::IFile {
 public:
  explicit TestFile(const android::StringPiece& path) : source_(path) {}

  std::unique_ptr<io::IData> OpenAsData() override {
    return {};
  }

  const Source& GetSource() const override {
    return source_;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(TestFile);

  Source source_;
};

}  // namespace test

// Workaround gtest bug (https://github.com/google/googletest/issues/443)
// that does not select base class operator<< for derived class T.
template <typename T>
typename std::enable_if<std::is_base_of<Value, T>::value, std::ostream&>::type operator<<(
    std::ostream& out, const T& value) {
  value.Print(&out);
  return out;
}

template std::ostream& operator<<<Item>(std::ostream&, const Item&);
template std::ostream& operator<<<Reference>(std::ostream&, const Reference&);
template std::ostream& operator<<<Id>(std::ostream&, const Id&);
template std::ostream& operator<<<RawString>(std::ostream&, const RawString&);
template std::ostream& operator<<<String>(std::ostream&, const String&);
template std::ostream& operator<<<StyledString>(std::ostream&, const StyledString&);
template std::ostream& operator<<<FileReference>(std::ostream&, const FileReference&);
template std::ostream& operator<<<BinaryPrimitive>(std::ostream&, const BinaryPrimitive&);
template std::ostream& operator<<<Attribute>(std::ostream&, const Attribute&);
template std::ostream& operator<<<Style>(std::ostream&, const Style&);
template std::ostream& operator<<<Array>(std::ostream&, const Array&);
template std::ostream& operator<<<Plural>(std::ostream&, const Plural&);

// Add a print method to Maybe.
template <typename T>
void PrintTo(const Maybe<T>& value, std::ostream* out) {
  if (value) {
    *out << ::testing::PrintToString(value.value());
  } else {
    *out << "Nothing";
  }
}

namespace test {

MATCHER_P(StrEq, a,
          std::string(negation ? "isn't" : "is") + " equal to " +
              ::testing::PrintToString(android::StringPiece16(a))) {
  return android::StringPiece16(arg) == a;
}

MATCHER_P(ValueEq, a,
          std::string(negation ? "isn't" : "is") + " equal to " + ::testing::PrintToString(a)) {
  return arg.Equals(&a);
}

}  // namespace test
}  // namespace aapt

#endif /* AAPT_TEST_COMMON_H */
