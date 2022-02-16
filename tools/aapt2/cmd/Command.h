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
#include <ostream>
#include <string>
#include <unordered_set>
#include <vector>

#include "androidfw/StringPiece.h"

#include "util/Maybe.h"

namespace aapt {

class Command {
 public:
  explicit Command(const android::StringPiece& name) : name_(name.to_string()),
                                                       short_name_(""),
                                                       full_subcommand_name_(name.to_string()) {}

  explicit Command(const android::StringPiece& name, const android::StringPiece& short_name)
      : name_(name.to_string()), short_name_(short_name.to_string()),
        full_subcommand_name_(name.to_string()) {}

  virtual ~Command() = default;

  // Behavior flags used with the following functions that change how the command flags are parsed
  // displayed.
  enum : uint32_t {
    // Indicates the arguments are file or folder paths. On Windows, paths that exceed the maximum
    // path length will be converted to use the extended path prefix '//?/'. Without this
    // conversion, files with long paths cannot be opened.
    kPath = 1 << 0,
  };

  void AddRequiredFlag(const android::StringPiece& name, const android::StringPiece& description,
                       std::string* value, uint32_t flags = 0);

  void AddRequiredFlagList(const android::StringPiece& name,
                           const android::StringPiece& description, std::vector<std::string>* value,
                           uint32_t flags = 0);

  void AddOptionalFlag(const android::StringPiece& name, const android::StringPiece& description,
                       Maybe<std::string>* value, uint32_t flags = 0);

  void AddOptionalFlagList(const android::StringPiece& name,
                           const android::StringPiece& description, std::vector<std::string>* value,
                           uint32_t flags = 0);

  void AddOptionalFlagList(const android::StringPiece& name,
                           const android::StringPiece& description,
                           std::unordered_set<std::string>* value);

  void AddOptionalSwitch(const android::StringPiece& name, const android::StringPiece& description,
                         bool* value);

  void AddOptionalSubcommand(std::unique_ptr<Command>&& subcommand, bool experimental = false);

  void SetDescription(const android::StringPiece& name);

  // Prints the help menu of the command.
  void Usage(std::ostream* out);

  // Parses the command line arguments, sets the flag variable values, and runs the action of
  // the command. If the arguments fail to parse to the command and its subcommands, then the action
  // will not be run and the usage will be printed instead.
  int Execute(const std::vector<android::StringPiece>& args, std::ostream* outError);

  // The action to preform when the command is executed.
  virtual int Action(const std::vector<std::string>& args) = 0;

 private:
  DISALLOW_COPY_AND_ASSIGN(Command);

  struct Flag {
    explicit Flag(const android::StringPiece& name, const android::StringPiece& description,
                  const bool is_required, const size_t num_args,
                  std::function<bool(const android::StringPiece& value)>&& action)
        : name(name.to_string()), description(description.to_string()), is_required(is_required),
          num_args(num_args), action(std::move(action)) {}

    const std::string name;
    const std::string description;
    const bool is_required;
    const size_t num_args;
    const std::function<bool(const android::StringPiece& value)> action;
    bool found = false;
  };

  const std::string name_;
  const std::string short_name_;
  std::string description_ = "";
  std::string full_subcommand_name_;

  std::vector<Flag> flags_;
  std::vector<std::unique_ptr<Command>> subcommands_;
  std::vector<std::unique_ptr<Command>> experimental_subcommands_;
};

}  // namespace aapt

#endif  // AAPT_COMMAND_H
