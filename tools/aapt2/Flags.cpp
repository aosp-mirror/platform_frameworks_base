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

#include "Flags.h"

#include <iomanip>
#include <iostream>
#include <string>
#include <vector>

#include "androidfw/StringPiece.h"

#include "util/Util.h"

using android::StringPiece;

namespace aapt {

Flags& Flags::RequiredFlag(const StringPiece& name,
                           const StringPiece& description, std::string* value) {
  auto func = [value](const StringPiece& arg) -> bool {
    *value = arg.to_string();
    return true;
  };

  flags_.push_back(Flag{name.to_string(), description.to_string(), func, true, 1, false});
  return *this;
}

Flags& Flags::RequiredFlagList(const StringPiece& name,
                               const StringPiece& description,
                               std::vector<std::string>* value) {
  auto func = [value](const StringPiece& arg) -> bool {
    value->push_back(arg.to_string());
    return true;
  };

  flags_.push_back(Flag{name.to_string(), description.to_string(), func, true, 1, false});
  return *this;
}

Flags& Flags::OptionalFlag(const StringPiece& name,
                           const StringPiece& description,
                           Maybe<std::string>* value) {
  auto func = [value](const StringPiece& arg) -> bool {
    *value = arg.to_string();
    return true;
  };

  flags_.push_back(Flag{name.to_string(), description.to_string(), func, false, 1, false});
  return *this;
}

Flags& Flags::OptionalFlagList(const StringPiece& name,
                               const StringPiece& description,
                               std::vector<std::string>* value) {
  auto func = [value](const StringPiece& arg) -> bool {
    value->push_back(arg.to_string());
    return true;
  };

  flags_.push_back(Flag{name.to_string(), description.to_string(), func, false, 1, false});
  return *this;
}

Flags& Flags::OptionalFlagList(const StringPiece& name,
                               const StringPiece& description,
                               std::unordered_set<std::string>* value) {
  auto func = [value](const StringPiece& arg) -> bool {
    value->insert(arg.to_string());
    return true;
  };

  flags_.push_back(Flag{name.to_string(), description.to_string(), func, false, 1, false});
  return *this;
}

Flags& Flags::OptionalSwitch(const StringPiece& name,
                             const StringPiece& description, bool* value) {
  auto func = [value](const StringPiece& arg) -> bool {
    *value = true;
    return true;
  };

  flags_.push_back(Flag{name.to_string(), description.to_string(), func, false, 0, false});
  return *this;
}

void Flags::Usage(const StringPiece& command, std::ostream* out) {
  constexpr size_t kWidth = 50;

  *out << command << " [options]";
  for (const Flag& flag : flags_) {
    if (flag.required) {
      *out << " " << flag.name << " arg";
    }
  }

  *out << " files...\n\nOptions:\n";

  for (const Flag& flag : flags_) {
    std::string argline = flag.name;
    if (flag.num_args > 0) {
      argline += " arg";
    }

    // Split the description by newlines and write out the argument (which is
    // empty after
    // the first line) followed by the description line. This will make sure
    // that multiline
    // descriptions are still right justified and aligned.
    for (StringPiece line : util::Tokenize(flag.description, '\n')) {
      *out << " " << std::setw(kWidth) << std::left << argline << line << "\n";
      argline = " ";
    }
  }
  *out << " " << std::setw(kWidth) << std::left << "-h"
       << "Displays this help menu\n";
  out->flush();
}

bool Flags::Parse(const StringPiece& command,
                  const std::vector<StringPiece>& args,
                  std::ostream* out_error) {
  for (size_t i = 0; i < args.size(); i++) {
    StringPiece arg = args[i];
    if (*(arg.data()) != '-') {
      args_.push_back(arg.to_string());
      continue;
    }

    if (arg == "-h" || arg == "--help") {
      Usage(command, out_error);
      return false;
    }

    bool match = false;
    for (Flag& flag : flags_) {
      if (arg == flag.name) {
        if (flag.num_args > 0) {
          i++;
          if (i >= args.size()) {
            *out_error << flag.name << " missing argument.\n\n";
            Usage(command, out_error);
            return false;
          }
          flag.action(args[i]);
        } else {
          flag.action({});
        }
        flag.parsed = true;
        match = true;
        break;
      }
    }

    if (!match) {
      *out_error << "unknown option '" << arg << "'.\n\n";
      Usage(command, out_error);
      return false;
    }
  }

  for (const Flag& flag : flags_) {
    if (flag.required && !flag.parsed) {
      *out_error << "missing required flag " << flag.name << "\n\n";
      Usage(command, out_error);
      return false;
    }
  }
  return true;
}

const std::vector<std::string>& Flags::GetArgs() { return args_; }

}  // namespace aapt
