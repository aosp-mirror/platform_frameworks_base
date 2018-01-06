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

#ifndef AAPT2_ABISPLITTER_H
#define AAPT2_ABISPLITTER_H

#include <memory>
#include <string>
#include <unordered_set>
#include <vector>

#include "configuration/ConfigurationParser.h"
#include "filter/Filter.h"

namespace aapt {

/**
 * Filters native library paths by ABI. ABIs present in the filter list are kept and all over
 * libraries are removed. The filter is only applied to native library paths (this under lib/).
 */
class AbiFilter : public IPathFilter {
 public:
  virtual ~AbiFilter() = default;

  /** Factory method to create a filter from a list of configuration::Abi. */
  static std::unique_ptr<AbiFilter> FromAbiList(const std::vector<configuration::Abi>& abi_list);

  /** Returns true if the path is for a native library in the list of desired ABIs. */
  bool Keep(const std::string& path) override;

 private:
  explicit AbiFilter(std::unordered_set<std::string> abis) : abis_(std::move(abis)) {
  }

  /** The path prefix to where all native libs end up inside an APK file. */
  static constexpr const char* kLibPrefix = "lib/";
  static constexpr size_t kLibPrefixLen = 4;
  const std::unordered_set<std::string> abis_;
};

}  // namespace aapt

#endif  // AAPT2_ABISPLITTER_H
