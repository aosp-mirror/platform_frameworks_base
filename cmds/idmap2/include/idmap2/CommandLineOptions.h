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

#ifndef IDMAP2_INCLUDE_IDMAP2_COMMANDLINEOPTIONS_H_
#define IDMAP2_INCLUDE_IDMAP2_COMMANDLINEOPTIONS_H_

#include <functional>
#include <memory>
#include <ostream>
#include <string>
#include <vector>

#include "idmap2/Result.h"

namespace android::idmap2 {

/*
 * Utility class to convert a command line, including options (--path foo.txt),
 * into data structures (options.path = "foo.txt").
 */
class CommandLineOptions {
 public:
  static std::unique_ptr<std::vector<std::string>> ConvertArgvToVector(int argc, const char** argv);

  explicit CommandLineOptions(const std::string& name) : name_(name) {
  }

  CommandLineOptions& OptionalFlag(const std::string& name, const std::string& description,
                                   bool* value);
  CommandLineOptions& MandatoryOption(const std::string& name, const std::string& description,
                                      std::string* value);
  CommandLineOptions& MandatoryOption(const std::string& name, const std::string& description,
                                      std::vector<std::string>* value);
  CommandLineOptions& OptionalOption(const std::string& name, const std::string& description,
                                     std::string* value);
  CommandLineOptions& OptionalOption(const std::string& name, const std::string& description,
                                     std::vector<std::string>* value);
  Result<Unit> Parse(const std::vector<std::string>& argv) const;
  void Usage(std::ostream& out) const;

 private:
  struct Option {
    std::string name;
    std::string description;
    std::function<void(const std::string& value)> action;
    enum {
      COUNT_OPTIONAL,
      COUNT_EXACTLY_ONCE,
      COUNT_ONCE_OR_MORE,
      COUNT_OPTIONAL_ONCE_OR_MORE,
    } count;
    bool argument;
  };

  mutable std::vector<Option> options_;
  std::string name_;
};

}  // namespace android::idmap2

#endif  // IDMAP2_INCLUDE_IDMAP2_COMMANDLINEOPTIONS_H_
