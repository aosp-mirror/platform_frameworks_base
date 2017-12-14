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

#include "AbiFilter.h"

#include <memory>

#include "io/Util.h"

namespace aapt {

std::unique_ptr<AbiFilter> AbiFilter::FromAbiList(const std::vector<configuration::Abi>& abi_list) {
  std::unordered_set<std::string> abi_set;
  for (auto& abi : abi_list) {
    abi_set.insert(configuration::AbiToString(abi).to_string());
  }
  // Make unique by hand as the constructor is private.
  return std::unique_ptr<AbiFilter>(new AbiFilter(abi_set));
}

bool AbiFilter::Keep(const std::string& path) {
  // We only care about libraries.
  if (!util::StartsWith(path, kLibPrefix)) {
    return true;
  }

  auto abi_end = path.find('/', kLibPrefixLen);
  if (abi_end == std::string::npos) {
    // Ignore any files in the top level lib directory.
    return true;
  }

  // Strip the lib/ prefix.
  const std::string& path_abi = path.substr(kLibPrefixLen, abi_end - kLibPrefixLen);
  return (abis_.find(path_abi) != abis_.end());
}

}  // namespace aapt
