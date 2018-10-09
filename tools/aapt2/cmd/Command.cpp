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

#include "Command.h"

#include <iomanip>
#include <iostream>
#include <string>
#include <vector>

#include "androidfw/StringPiece.h"

#include "util/Util.h"

using android::StringPiece;

namespace aapt {

void Command::AddRequiredFlag(const StringPiece& name,
    const StringPiece& description, std::string* value) {
  auto func = [value](const StringPiece& arg) -> bool {
    *value = arg.to_string();
    return true;
  };

  flags_.push_back(Flag{name.to_string(), description.to_string(), func, true, 1, false});
}

void Command::AddRequiredFlagList(const StringPiece& name,
    const StringPiece& description,
    std::vector<std::string>* value) {
  auto func = [value](const StringPiece& arg) -> bool {
    value->push_back(arg.to_string());
    return true;
  };

  flags_.push_back(Flag{name.to_string(), description.to_string(), func, true, 1, false});
}

void Command::AddOptionalFlag(const StringPiece& name,
    const StringPiece& description,
    Maybe<std::string>* value) {
  auto func = [value](const StringPiece& arg) -> bool {
    *value = arg.to_string();
    return true;
  };

  flags_.push_back(Flag{name.to_string(), description.to_string(), func, false, 1, false});
}

void Command::AddOptionalFlagList(const StringPiece& name,
    const StringPiece& description,
    std::vector<std::string>* value) {
  auto func = [value](const StringPiece& arg) -> bool {
    value->push_back(arg.to_string());
    return true;
  };

  flags_.push_back(Flag{name.to_string(), description.to_string(), func, false, 1, false});
}

void Command::AddOptionalFlagList(const StringPiece& name,
    const StringPiece& description,
    std::unordered_set<std::string>* value) {
  auto func = [value](const StringPiece& arg) -> bool {
    value->insert(arg.to_string());
    return true;
  };

  flags_.push_back(Flag{name.to_string(), description.to_string(), func, false, 1, false});
}

void Command::AddOptionalSwitch(const StringPiece& name,
    const StringPiece& description, bool* value) {
  auto func = [value](const StringPiece& arg) -> bool {
    *value = true;
    return true;
  };

  flags_.push_back(Flag{name.to_string(), description.to_string(), func, false, 0, false});
}

void Command::AddOptionalSubcommand(std::unique_ptr<Command>&& subcommand, bool experimental) {
  subcommand->fullname_ = name_ + " " + subcommand->name_;
  if (experimental) {
    experimental_subcommands_.push_back(std::move(subcommand));
  } else {
    subcommands_.push_back(std::move(subcommand));
  }
}

void Command::SetDescription(const android::StringPiece& description) {
  description_ = description.to_string();
}

void Command::Usage(std::ostream* out) {
  constexpr size_t kWidth = 50;

  *out << fullname_;

  if (!subcommands_.empty()) {
    *out << " [subcommand]";
  }

  *out << " [options]";
  for (const Flag& flag : flags_) {
    if (flag.required) {
      *out << " " << flag.name << " arg";
    }
  }

  *out << " files...\n";

  if (!subcommands_.empty()) {
    *out << "\nSubcommands:\n";
    for (auto& subcommand : subcommands_) {
      std::string argline = subcommand->name_;

      // Split the description by newlines and write out the argument (which is
      // empty after the first line) followed by the description line. This will make sure
      // that multiline descriptions are still right justified and aligned.
      for (StringPiece line : util::Tokenize(subcommand->description_, '\n')) {
        *out << " " << std::setw(kWidth) << std::left << argline << line << "\n";
        argline = " ";
      }
    }
  }

  *out << "\nOptions:\n";

  for (const Flag& flag : flags_) {
    std::string argline = flag.name;
    if (flag.num_args > 0) {
      argline += " arg";
    }

    // Split the description by newlines and write out the argument (which is
    // empty after the first line) followed by the description line. This will make sure
    // that multiline descriptions are still right justified and aligned.
    for (StringPiece line : util::Tokenize(flag.description, '\n')) {
      *out << " " << std::setw(kWidth) << std::left << argline << line << "\n";
      argline = " ";
    }
  }
  *out << " " << std::setw(kWidth) << std::left << "-h"
       << "Displays this help menu\n";
  out->flush();
}

int Command::Execute(const std::vector<android::StringPiece>& args, std::ostream* out_error) {
  std::vector<std::string> file_args;

  for (size_t i = 0; i < args.size(); i++) {
    StringPiece arg = args[i];
    if (*(arg.data()) != '-') {
      // Continue parsing as the subcommand if the first argument matches one of the subcommands
      if (i == 0) {
        for (auto& subcommand : subcommands_) {
          if (arg == subcommand->name_ || arg==subcommand->short_name_) {
            return subcommand->Execute(
                std::vector<android::StringPiece>(args.begin() + 1, args.end()), out_error);
          }
        }
        for (auto& subcommand : experimental_subcommands_) {
          if (arg == subcommand->name_ || arg==subcommand->short_name_) {
            return subcommand->Execute(
              std::vector<android::StringPiece>(args.begin() + 1, args.end()), out_error);
          }
        }
      }

      file_args.push_back(arg.to_string());
      continue;
    }

    if (arg == "-h" || arg == "--help") {
      Usage(out_error);
      return 1;
    }

    bool match = false;
    for (Flag& flag : flags_) {
      if (arg == flag.name) {
        if (flag.num_args > 0) {
          i++;
          if (i >= args.size()) {
            *out_error << flag.name << " missing argument.\n\n";
            Usage(out_error);
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
      Usage(out_error);
      return 1;
    }
  }

  for (const Flag& flag : flags_) {
    if (flag.required && !flag.parsed) {
      *out_error << "missing required flag " << flag.name << "\n\n";
      Usage(out_error);
      return 1;
    }
  }

  return Action(file_args);
}

}  // namespace aapt
