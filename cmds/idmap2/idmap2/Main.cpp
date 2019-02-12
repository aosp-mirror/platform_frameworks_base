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

#include <cstdlib>  // EXIT_{FAILURE,SUCCESS}
#include <functional>
#include <iostream>
#include <map>
#include <memory>
#include <ostream>
#include <string>
#include <vector>

#include "idmap2/CommandLineOptions.h"
#include "idmap2/SysTrace.h"

#include "Commands.h"

using android::idmap2::CommandLineOptions;

using NameToFunctionMap =
    std::map<std::string, std::function<bool(const std::vector<std::string>&, std::ostream&)>>;

namespace {

void PrintUsage(const NameToFunctionMap& commands, std::ostream& out) {
  out << "usage: idmap2 [";
  for (auto iter = commands.cbegin(); iter != commands.cend(); iter++) {
    if (iter != commands.cbegin()) {
      out << "|";
    }
    out << iter->first;
  }
  out << "]" << std::endl;
}

}  // namespace

int main(int argc, char** argv) {
  SYSTRACE << "main";
  const NameToFunctionMap commands = {
      {"create", Create}, {"dump", Dump}, {"lookup", Lookup}, {"scan", Scan}, {"verify", Verify},
  };
  if (argc <= 1) {
    PrintUsage(commands, std::cerr);
    return EXIT_FAILURE;
  }
  const std::unique_ptr<std::vector<std::string>> args =
      CommandLineOptions::ConvertArgvToVector(argc - 1, const_cast<const char**>(argv + 1));
  if (!args) {
    std::cerr << "error: failed to parse command line options" << std::endl;
    return EXIT_FAILURE;
  }
  const auto iter = commands.find(argv[1]);
  if (iter == commands.end()) {
    std::cerr << argv[1] << ": command not found" << std::endl;
    PrintUsage(commands, std::cerr);
    return EXIT_FAILURE;
  }
  return iter->second(*args, std::cerr) ? EXIT_SUCCESS : EXIT_FAILURE;
}
