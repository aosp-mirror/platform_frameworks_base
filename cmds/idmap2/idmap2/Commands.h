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

bool Create(const std::vector<std::string>& args, std::ostream& out_error);
bool Dump(const std::vector<std::string>& args, std::ostream& out_error);
bool Lookup(const std::vector<std::string>& args, std::ostream& out_error);
bool Scan(const std::vector<std::string>& args, std::ostream& out_error);
bool Verify(const std::vector<std::string>& args, std::ostream& out_error);

#endif  // IDMAP2_IDMAP2_COMMANDS_H_
