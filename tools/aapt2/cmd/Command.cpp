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

#include "android-base/stringprintf.h"
#include "android-base/utf8.h"
#include "androidfw/StringPiece.h"

#include "trace/TraceBuffer.h"
#include "util/Util.h"

using android::base::StringPrintf;
using android::StringPiece;

namespace aapt {

std::string GetSafePath(const StringPiece& arg) {
#ifdef _WIN32
  // If the path exceeds the maximum path length for Windows, encode the path using the
  // extended-length prefix
  std::wstring path16;
  CHECK(android::base::UTF8PathToWindowsLongPath(arg.data(), &path16))
      << "Failed to convert file path to UTF-16: file path " << arg.data();

  std::string path8;
  CHECK(android::base::WideToUTF8(path16, &path8))
      << "Failed to convert file path back to UTF-8: file path " << arg.data();

  return path8;
#else
  return arg.to_string();
#endif
}

void Command::AddRequiredFlag(const StringPiece& name, const StringPiece& description,
                              std::string* value, uint32_t flags) {
  auto func = [value, flags](const StringPiece& arg) -> bool {
    *value = (flags & Command::kPath) ? GetSafePath(arg) : arg.to_string();
    return true;
  };

  flags_.emplace_back(Flag(name, description, /* required */ true, /* num_args */ 1, func));
}

void Command::AddRequiredFlagList(const StringPiece& name, const StringPiece& description,
                                  std::vector<std::string>* value, uint32_t flags) {
  auto func = [value, flags](const StringPiece& arg) -> bool {
    value->push_back((flags & Command::kPath) ? GetSafePath(arg) : arg.to_string());
    return true;
  };

  flags_.emplace_back(Flag(name, description, /* required */ true, /* num_args */ 1, func));
}

void Command::AddOptionalFlag(const StringPiece& name, const StringPiece& description,
                              Maybe<std::string>* value, uint32_t flags) {
  auto func = [value, flags](const StringPiece& arg) -> bool {
    *value = (flags & Command::kPath) ? GetSafePath(arg) : arg.to_string();
    return true;
  };

  flags_.emplace_back(Flag(name, description, /* required */ false, /* num_args */ 1, func));
}

void Command::AddOptionalFlagList(const StringPiece& name, const StringPiece& description,
                                  std::vector<std::string>* value, uint32_t flags) {
  auto func = [value, flags](const StringPiece& arg) -> bool {
    value->push_back((flags & Command::kPath) ? GetSafePath(arg) : arg.to_string());
    return true;
  };

  flags_.emplace_back(Flag(name, description, /* required */ false, /* num_args */ 1, func));
}

void Command::AddOptionalFlagList(const StringPiece& name, const StringPiece& description,
                                  std::unordered_set<std::string>* value) {
  auto func = [value](const StringPiece& arg) -> bool {
    value->insert(arg.to_string());
    return true;
  };

  flags_.emplace_back(Flag(name, description, /* required */ false, /* num_args */ 1, func));
}

void Command::AddOptionalSwitch(const StringPiece& name, const StringPiece& description,
                                bool* value) {
  auto func = [value](const StringPiece& arg) -> bool {
    *value = true;
    return true;
  };

  flags_.emplace_back(Flag(name, description, /* required */ false, /* num_args */ 0, func));
}

void Command::AddOptionalSubcommand(std::unique_ptr<Command>&& subcommand, bool experimental) {
  subcommand->full_subcommand_name_ = StringPrintf("%s %s", name_.data(), subcommand->name_.data());
  if (experimental) {
    experimental_subcommands_.push_back(std::move(subcommand));
  } else {
    subcommands_.push_back(std::move(subcommand));
  }
}

void Command::SetDescription(const StringPiece& description) {
  description_ = description.to_string();
}

void Command::Usage(std::ostream* out) {
  constexpr size_t kWidth = 50;

  *out << full_subcommand_name_;

  if (!subcommands_.empty()) {
    *out << " [subcommand]";
  }

  *out << " [options]";
  for (const Flag& flag : flags_) {
    if (flag.is_required) {
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

int Command::Execute(const std::vector<StringPiece>& args, std::ostream* out_error) {
  TRACE_NAME_ARGS("Command::Execute", args);
  std::vector<std::string> file_args;

  for (size_t i = 0; i < args.size(); i++) {
    const StringPiece& arg = args[i];
    if (*(arg.data()) != '-') {
      // Continue parsing as the subcommand if the first argument matches one of the subcommands
      if (i == 0) {
        for (auto& subcommand : subcommands_) {
          if (arg == subcommand->name_ || (!subcommand->short_name_.empty()
                                           && arg == subcommand->short_name_)) {
            return subcommand->Execute(
                std::vector<StringPiece>(args.begin() + 1, args.end()), out_error);
          }
        }
        for (auto& subcommand : experimental_subcommands_) {
          if (arg == subcommand->name_ || (!subcommand->short_name_.empty()
                                           && arg == subcommand->short_name_)) {
            return subcommand->Execute(
              std::vector<StringPiece>(args.begin() + 1, args.end()), out_error);
          }
        }
      }

      file_args.push_back(GetSafePath(arg));
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
        flag.found = true;
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
    if (flag.is_required && !flag.found) {
      *out_error << "missing required flag " << flag.name << "\n\n";
      Usage(out_error);
      return 1;
    }
  }

  return Action(file_args);
}

}  // namespace aapt
