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

std::string GetSafePath(StringPiece arg) {
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
  return std::string(arg);
#endif
}

void Command::AddRequiredFlag(StringPiece name, StringPiece description, std::string* value,
                              uint32_t flags) {
  auto func = [value, flags](StringPiece arg, std::ostream*) -> bool {
    *value = (flags & Command::kPath) ? GetSafePath(arg) : std::string(arg);
    return true;
  };

  flags_.emplace_back(
      Flag(name, description, /* required */ true, /* num_args */ 1, std::move(func)));
}

void Command::AddRequiredFlagList(StringPiece name, StringPiece description,
                                  std::vector<std::string>* value, uint32_t flags) {
  auto func = [value, flags](StringPiece arg, std::ostream*) -> bool {
    value->push_back((flags & Command::kPath) ? GetSafePath(arg) : std::string(arg));
    return true;
  };

  flags_.emplace_back(
      Flag(name, description, /* required */ true, /* num_args */ 1, std::move(func)));
}

void Command::AddOptionalFlag(StringPiece name, StringPiece description,
                              std::optional<std::string>* value, uint32_t flags) {
  auto func = [value, flags](StringPiece arg, std::ostream*) -> bool {
    *value = (flags & Command::kPath) ? GetSafePath(arg) : std::string(arg);
    return true;
  };

  flags_.emplace_back(
      Flag(name, description, /* required */ false, /* num_args */ 1, std::move(func)));
}

void Command::AddOptionalFlagList(StringPiece name, StringPiece description,
                                  std::vector<std::string>* value, uint32_t flags) {
  auto func = [value, flags](StringPiece arg, std::ostream*) -> bool {
    value->push_back((flags & Command::kPath) ? GetSafePath(arg) : std::string(arg));
    return true;
  };

  flags_.emplace_back(
      Flag(name, description, /* required */ false, /* num_args */ 1, std::move(func)));
}

void Command::AddOptionalFlagList(StringPiece name, StringPiece description,
                                  std::unordered_set<std::string>* value) {
  auto func = [value](StringPiece arg, std::ostream* out_error) -> bool {
    value->emplace(arg);
    return true;
  };

  flags_.emplace_back(
      Flag(name, description, /* required */ false, /* num_args */ 1, std::move(func)));
}

void Command::AddOptionalSwitch(StringPiece name, StringPiece description, bool* value) {
  auto func = [value](StringPiece arg, std::ostream* out_error) -> bool {
    *value = true;
    return true;
  };

  flags_.emplace_back(
      Flag(name, description, /* required */ false, /* num_args */ 0, std::move(func)));
}

void Command::AddOptionalSubcommand(std::unique_ptr<Command>&& subcommand, bool experimental) {
  subcommand->full_subcommand_name_ = StringPrintf("%s %s", name_.data(), subcommand->name_.data());
  if (experimental) {
    experimental_subcommands_.push_back(std::move(subcommand));
  } else {
    subcommands_.push_back(std::move(subcommand));
  }
}

void Command::SetDescription(StringPiece description) {
  description_ = std::string(description);
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
  out->flush();
}

const std::string& Command::addEnvironmentArg(const Flag& flag, const char* env) {
  if (*env && flag.num_args > 0) {
    return environment_args_.emplace_back(flag.name + '=' + env);
  }
  return flag.name;
}

//
// Looks for the flags specified in the environment and adds them to |args|.
// Expected format:
// - _AAPT2_UPPERCASE_NAME are added before all of the command line flags, so it's
//   a default for the flag that may get overridden by the command line.
// - AAPT2_UPPERCASE_NAME_ are added after them, making this to be the final value
//   even if there was something on the command line.
// - All dashes in the flag name get replaced with underscores, the rest of it is
//   intact.
//
// E.g.
//  --set-some-flag becomes either _AAPT2_SET_SOME_FLAG or AAPT2_SET_SOME_FLAG_
//  --set-param=2 is _AAPT2_SET_SOME_FLAG=2
//
// Values get passed as it, with no processing or quoting.
//
// This way one can make sure aapt2 has the flags they need even when it is
// launched in a way they can't control, e.g. deep inside a build.
//
void Command::parseFlagsFromEnvironment(std::vector<StringPiece>& args) {
  // If the first argument is a subcommand then skip it and prepend the flags past that (the root
  // command should only have a single '-h' flag anyway).
  const int insert_pos = args.empty() ? 0 : args.front().starts_with('-') ? 0 : 1;

  std::string env_name;
  for (const Flag& flag : flags_) {
    // First, the prefix version.
    env_name.assign("_AAPT2_");
    // Append the uppercased flag name, skipping all dashes in front and replacing them with
    // underscores later.
    auto name_start = flag.name.begin();
    while (name_start != flag.name.end() && *name_start == '-') {
      ++name_start;
    }
    std::transform(name_start, flag.name.end(), std::back_inserter(env_name),
                   [](char c) { return c == '-' ? '_' : toupper(c); });
    if (auto prefix_env = getenv(env_name.c_str())) {
      args.insert(args.begin() + insert_pos, addEnvironmentArg(flag, prefix_env));
    }
    // Now reuse the same name variable to construct a suffix version: append the
    // underscore and just skip the one in front.
    env_name += '_';
    if (auto suffix_env = getenv(env_name.c_str() + 1)) {
      args.push_back(addEnvironmentArg(flag, suffix_env));
    }
  }
}

int Command::Execute(std::vector<StringPiece>& args, std::ostream* out_error) {
  TRACE_NAME_ARGS("Command::Execute", args);
  std::vector<std::string> file_args;

  parseFlagsFromEnvironment(args);

  for (size_t i = 0; i < args.size(); i++) {
    StringPiece arg = args[i];
    if (*(arg.data()) != '-') {
      // Continue parsing as a subcommand if the first argument matches one of the subcommands
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

    static constexpr auto matchShortArg = [](std::string_view arg, const Flag& flag) static {
      return flag.name.starts_with("--") &&
             arg.compare(0, 2, std::string_view(flag.name.c_str() + 1, 2)) == 0;
    };

    bool match = false;
    for (Flag& flag : flags_) {
      // Allow both "--arg value" and "--arg=value" syntax, and look for the cases where we can
      // safely deduce the "--arg" flag from the short "-a" version when there's no value expected
      bool matched_current = false;
      if (arg.starts_with(flag.name) &&
          (arg.size() == flag.name.size() || (flag.num_args > 0 && arg[flag.name.size()] == '='))) {
        matched_current = true;
      } else if (flag.num_args == 0 && matchShortArg(arg, flag)) {
        matched_current = true;
        // It matches, now need to make sure no other flag would match as well.
        // This is really inefficient, but we don't expect to have enough flags for it to matter
        // (famous last words).
        for (const Flag& other_flag : flags_) {
          if (&other_flag == &flag) {
            continue;
          }
          if (matchShortArg(arg, other_flag)) {
            matched_current = false;  // ambiguous, skip this match
            break;
          }
        }
      }
      if (!matched_current) {
        continue;
      }

      if (flag.num_args > 0) {
        if (arg.size() == flag.name.size()) {
          i++;
          if (i >= args.size()) {
            *out_error << flag.name << " missing argument.\n\n";
            Usage(out_error);
            return 1;
          }
          arg = args[i];
        } else {
          arg.remove_prefix(flag.name.size() + 1);
          // Disallow empty arguments after '='.
          if (arg.empty()) {
            *out_error << flag.name << " has empty argument.\n\n";
            Usage(out_error);
            return 1;
          }
        }
        if (!flag.action(arg, out_error)) {
          return 1;
        }
      } else {
        if (!flag.action({}, out_error)) {
          return 1;
        }
      }
      flag.found = true;
      match = true;
      break;
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
