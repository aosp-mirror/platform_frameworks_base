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

#ifndef AAPT_FLAGS_H
#define AAPT_FLAGS_H

#include <functional>
#include <ostream>
#include <string>
#include <unordered_set>
#include <vector>

#include "androidfw/StringPiece.h"

#include "util/Maybe.h"

namespace aapt {

class Flags {
 public:
  Flags& RequiredFlag(const android::StringPiece& name, const android::StringPiece& description,
                      std::string* value);
  Flags& RequiredFlagList(const android::StringPiece& name, const android::StringPiece& description,
                          std::vector<std::string>* value);
  Flags& OptionalFlag(const android::StringPiece& name, const android::StringPiece& description,
                      Maybe<std::string>* value);
  Flags& OptionalFlagList(const android::StringPiece& name, const android::StringPiece& description,
                          std::vector<std::string>* value);
  Flags& OptionalFlagList(const android::StringPiece& name, const android::StringPiece& description,
                          std::unordered_set<std::string>* value);
  Flags& OptionalSwitch(const android::StringPiece& name, const android::StringPiece& description,
                        bool* value);

  void Usage(const android::StringPiece& command, std::ostream* out);

  bool Parse(const android::StringPiece& command, const std::vector<android::StringPiece>& args,
             std::ostream* outError);

  const std::vector<std::string>& GetArgs();

 private:
  struct Flag {
    std::string name;
    std::string description;
    std::function<bool(const android::StringPiece& value)> action;
    bool required;
    size_t num_args;

    bool parsed;
  };

  std::vector<Flag> flags_;
  std::vector<std::string> args_;
};

}  // namespace aapt

#endif  // AAPT_FLAGS_H
