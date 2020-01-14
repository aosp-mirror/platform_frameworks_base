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

#include "androidfw/DynamicLibManager.h"

namespace android {

uint8_t DynamicLibManager::GetAssignedId(const std::string& library_package_name) {
  auto lib_entry = shared_lib_package_ids_.find(library_package_name);
  if (lib_entry != shared_lib_package_ids_.end()) {
    return lib_entry->second;
  }

  return shared_lib_package_ids_[library_package_name] = next_package_id_++;
}

uint8_t DynamicLibManager::FindUnassignedId(uint8_t start_package_id) {
  return (start_package_id < next_package_id_) ? next_package_id_ : start_package_id;
}

} // namespace android
