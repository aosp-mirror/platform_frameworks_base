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

#ifndef AAPT_COMMAND_H
#define AAPT_COMMAND_H

#include <functional>
#include <optional>
#include <ostream>
#include <string>
#include <unordered_set>
#include <vector>

#include "androidfw/StringPiece.h"

namespace aapt {

class Command {
 public:
  explicit Command(android::StringPiece name) : name_(name), full_subcommand_name_(name){};

  explicit Command(android::StringPiece name, android::StringPiece short_name)
      : name_(name), short_name_(short_name), full_subcommand_name_(name){};

  Command(Command&&) = default;
  Command& operator=(Command&&) = default;

  virtual ~Command() = default;

  // Behavior flags used with the following functions that change how the command flags are parsed
  // displayed.
  enum : uint32_t {
    // Indicates the arguments are file or folder paths. On Windows, paths that exceed the maximum
    // path length will be converted to use the extended path prefix '//?/'. Without this
    // conversion, files with long paths cannot be opened.
    kPath = 1 << 0,
  };

  void AddRequiredFlag(android::StringPiece name, android::StringPiece description,
                       std::string* value, uint32_t flags = 0);

  void AddRequiredFlagList(android::StringPiece name, android::StringPiece description,
                           std::vector<std::string>* value, uint32_t flags = 0);

  void AddOptionalFlag(android::StringPiece name, android::StringPiece description,
                       std::optional<std::string>* value, uint32_t flags = 0);

  void AddOptionalFlagList(android::StringPiece name, android::StringPiece description,
                           std::vector<std::string>* value, uint32_t flags = 0);

  void AddOptionalFlagList(android::StringPiece name, android::StringPiece description,
                           std::unordered_set<std::string>* value);

  void AddOptionalSwitch(android::StringPiece name, android::StringPiece description, bool* value);

  void AddOptionalSubcommand(std::unique_ptr<Command>&& subcommand, bool experimental = false);

  void SetDescription(android::StringPiece name);

  // Prints the help menu of the command.
  void Usage(std::ostream* out);

  // Parses the command line arguments, sets the flag variable values, and runs the action of
  // the command. If the arguments fail to parse to the command and its subcommands, then the action
  // will not be run and the usage will be printed instead.
  int Execute(const std::vector<android::StringPiece>& args, std::ostream* outError);

  // The action to preform when the command is executed.
  virtual int Action(const std::vector<std::string>& args) = 0;

 private:
  struct Flag {
    explicit Flag(android::StringPiece name, android::StringPiece description,
                  const bool is_required, const size_t num_args,
                  std::function<bool(android::StringPiece value)>&& action)
        : name(name),
          description(description),
          is_required(is_required),
          num_args(num_args),
          action(std::move(action)) {
    }

    const std::string name;
    const std::string description;
    const bool is_required;
    const size_t num_args;
    const std::function<bool(android::StringPiece value)> action;
    bool found = false;
  };

  std::string name_;
  std::string short_name_;
  std::string description_ = "";
  std::string full_subcommand_name_;

  std::vector<Flag> flags_;
  std::vector<std::unique_ptr<Command>> subcommands_;
  std::vector<std::unique_ptr<Command>> experimental_subcommands_;
};

}  // namespace aapt

#endif  // AAPT_COMMAND_H
