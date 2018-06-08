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

namespace aapt {
namespace test {

IDiagnostics* GetDiagnostics();

inline ResourceName ParseNameOrDie(const android::StringPiece& str) {
  ResourceNameRef ref;
  CHECK(ResourceUtils::ParseResourceName(str, &ref)) << "invalid resource name: " << str;
  return ref.ToResourceName();
}

inline ConfigDescription ParseConfigOrDie(const android::StringPiece& str) {
  ConfigDescription config;
  CHECK(ConfigDescription::Parse(str, &config)) << "invalid configuration: " << str;
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

  std::unique_ptr<io::InputStream> OpenInputStream() override {
    return OpenAsData();
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

template <typename T>
class ValueEqImpl : public ::testing::MatcherInterface<T> {
 public:
  explicit ValueEqImpl(const Value* expected) : expected_(expected) {
  }

  bool MatchAndExplain(T x, ::testing::MatchResultListener* listener) const override {
    return expected_->Equals(&x);
  }

  void DescribeTo(::std::ostream* os) const override {
    *os << "is equal to " << *expected_;
  }

  void DescribeNegationTo(::std::ostream* os) const override {
    *os << "is not equal to " << *expected_;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(ValueEqImpl);

  const Value* expected_;
};

template <typename TValue>
class ValueEqMatcher {
 public:
  ValueEqMatcher(TValue expected) : expected_(std::move(expected)) {
  }

  template <typename T>
  operator ::testing::Matcher<T>() const {
    return ::testing::Matcher<T>(new ValueEqImpl<T>(&expected_));
  }

 private:
  TValue expected_;
};

template <typename TValue>
class ValueEqPointerMatcher {
 public:
  ValueEqPointerMatcher(const TValue* expected) : expected_(expected) {
  }

  template <typename T>
  operator ::testing::Matcher<T>() const {
    return ::testing::Matcher<T>(new ValueEqImpl<T>(expected_));
  }

 private:
  const TValue* expected_;
};

template <typename TValue,
          typename = typename std::enable_if<!std::is_pointer<TValue>::value, void>::type>
inline ValueEqMatcher<TValue> ValueEq(TValue value) {
  return ValueEqMatcher<TValue>(std::move(value));
}

template <typename TValue>
inline ValueEqPointerMatcher<TValue> ValueEq(const TValue* value) {
  return ValueEqPointerMatcher<TValue>(value);
}

MATCHER_P(StrValueEq, a,
          std::string(negation ? "isn't" : "is") + " equal to " + ::testing::PrintToString(a)) {
  return *(arg.value) == a;
}

MATCHER_P(HasValue, name,
          std::string(negation ? "does not have" : "has") + " value " +
              ::testing::PrintToString(name)) {
  return GetValueForConfig<Value>(&(*arg), name, {}) != nullptr;
}

MATCHER_P2(HasValue, name, config,
           std::string(negation ? "does not have" : "has") + " value " +
               ::testing::PrintToString(name) + " for config " + ::testing::PrintToString(config)) {
  return GetValueForConfig<Value>(&(*arg), name, config) != nullptr;
}

}  // namespace test
}  // namespace aapt

#endif /* AAPT_TEST_COMMON_H */
