/*
 * Copyright (C) 2018 The Android Open Source Project
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

#ifndef IDMAP2_IDMAP2_COMMANDS_H_
#define IDMAP2_IDMAP2_COMMANDS_H_

#include <string>
#include <vector>

#include "idmap2/Result.h"

android::idmap2::Result<android::idmap2::Unit> Create(const std::vector<std::string>& args);
android::idmap2::Result<android::idmap2::Unit> CreateMultiple(const std::vector<std::string>& args);
android::idmap2::Result<android::idmap2::Unit> Dump(const std::vector<std::string>& args);
android::idmap2::Result<android::idmap2::Unit> Lookup(const std::vector<std::string>& args);
android::idmap2::Result<android::idmap2::Unit> Scan(const std::vector<std::string>& args);

#endif  // IDMAP2_IDMAP2_COMMANDS_H_
