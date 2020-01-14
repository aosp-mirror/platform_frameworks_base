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

#ifndef ANDROIDFW_DYNAMICLIBMANAGER_H
#define ANDROIDFW_DYNAMICLIBMANAGER_H

#include <string>
#include <unordered_map>

#include "android-base/macros.h"

namespace android {

// Manages assigning resource ids for dynamic resources.
class DynamicLibManager {
 public:
  DynamicLibManager() = default;

  // Retrieves the assigned package id for the library.
  uint8_t GetAssignedId(const std::string& library_package_name);

  // Queries in ascending order for the first available package id that is not currently assigned to
  // a library.
  uint8_t FindUnassignedId(uint8_t start_package_id);

 private:
  DISALLOW_COPY_AND_ASSIGN(DynamicLibManager);

  uint8_t next_package_id_ = 0x02;
  std::unordered_map<std::string, uint8_t> shared_lib_package_ids_;
};

} // namespace android

#endif //ANDROIDFW_DYNAMICLIBMANAGER_H
