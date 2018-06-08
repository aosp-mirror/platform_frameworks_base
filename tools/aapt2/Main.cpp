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

#ifdef _WIN32
// clang-format off
#include <windows.h>
#include <shellapi.h>
// clang-format on
#endif

#include <iostream>
#include <vector>

#include "android-base/stringprintf.h"
#include "android-base/utf8.h"
#include "androidfw/StringPiece.h"

#include "Diagnostics.h"
#include "util/Files.h"
#include "util/Util.h"

using ::android::StringPiece;
using ::android::base::StringPrintf;

namespace aapt {

// DO NOT UPDATE, this is more of a marketing version.
static const char* sMajorVersion = "2";

// Update minor version whenever a feature or flag is added.
static const char* sMinorVersion = "19";

static void PrintVersion() {
  std::cerr << StringPrintf("Android Asset Packaging Tool (aapt) %s:%s", sMajorVersion,
                            sMinorVersion)
            << std::endl;
}

static void PrintUsage() {
  std::cerr << "\nusage: aapt2 [compile|link|dump|diff|optimize|convert|version] ..." << std::endl;
}

extern int Compile(const std::vector<StringPiece>& args, IDiagnostics* diagnostics);
extern int Link(const std::vector<StringPiece>& args, IDiagnostics* diagnostics);
extern int Dump(const std::vector<StringPiece>& args);
extern int Diff(const std::vector<StringPiece>& args);
extern int Optimize(const std::vector<StringPiece>& args);
extern int Convert(const std::vector<StringPiece>& args);

static int ExecuteCommand(const StringPiece& command, const std::vector<StringPiece>& args,
                          IDiagnostics* diagnostics) {
  if (command == "compile" || command == "c") {
    return Compile(args, diagnostics);
  } else if (command == "link" || command == "l") {
    return Link(args, diagnostics);
  } else if (command == "dump" || command == "d") {
    return Dump(args);
  } else if (command == "diff") {
    return Diff(args);
  } else if (command == "optimize") {
    return Optimize(args);
  } else if (command == "convert") {
    return Convert(args);
  } else if (command == "version") {
    PrintVersion();
    return 0;
  }
  diagnostics->Error(DiagMessage() << "unknown command '" << command << "'");
  return -1;
}

static void RunDaemon(IDiagnostics* diagnostics) {
  std::cout << "Ready" << std::endl;

  // Run in daemon mode. The first line of input is the command. This can be 'quit' which ends
  // the daemon mode. Each subsequent line is a single parameter to the command. The end of a
  // invocation is signaled by providing an empty line. At any point, an EOF signal or the
  // command 'quit' will end the daemon mode.
  while (true) {
    std::vector<std::string> raw_args;
    for (std::string line; std::getline(std::cin, line) && !line.empty();) {
      raw_args.push_back(line);
    }

    if (!std::cin) {
      break;
    }

    // An empty command does nothing.
    if (raw_args.empty()) {
      continue;
    }

    if (raw_args[0] == "quit") {
      break;
    }

    std::vector<StringPiece> args;
    args.insert(args.end(), ++raw_args.begin(), raw_args.end());
    int ret = ExecuteCommand(raw_args[0], args, diagnostics);
    if (ret != 0) {
      std::cerr << "Error" << std::endl;
    }
    std::cerr << "Done" << std::endl;
  }
  std::cout << "Exiting daemon" << std::endl;
}

}  // namespace aapt

int MainImpl(int argc, char** argv) {
  if (argc < 2) {
    std::cerr << "no command specified\n";
    aapt::PrintUsage();
    return -1;
  }

  argv += 1;
  argc -= 1;

  aapt::StdErrDiagnostics diagnostics;

  // Collect the arguments starting after the program name and command name.
  std::vector<StringPiece> args;
  for (int i = 1; i < argc; i++) {
    args.push_back(argv[i]);
  }

  const StringPiece command(argv[0]);
  if (command != "daemon" && command != "m") {
    // Single execution.
    const int result = aapt::ExecuteCommand(command, args, &diagnostics);
    if (result < 0) {
      aapt::PrintUsage();
    }
    return result;
  }

  aapt::RunDaemon(&diagnostics);
  return 0;
}

int main(int argc, char** argv) {
#ifdef _WIN32
  LPWSTR* wide_argv = CommandLineToArgvW(GetCommandLineW(), &argc);
  CHECK(wide_argv != nullptr) << "invalid command line parameters passed to process";

  std::vector<std::string> utf8_args;
  for (int i = 0; i < argc; i++) {
    std::string utf8_arg;
    if (!::android::base::WideToUTF8(wide_argv[i], &utf8_arg)) {
      std::cerr << "error converting input arguments to UTF-8" << std::endl;
      return 1;
    }
    utf8_args.push_back(std::move(utf8_arg));
  }
  LocalFree(wide_argv);

  std::unique_ptr<char* []> utf8_argv(new char*[utf8_args.size()]);
  for (int i = 0; i < argc; i++) {
    utf8_argv[i] = const_cast<char*>(utf8_args[i].c_str());
  }
  argv = utf8_argv.get();
#endif
  return MainImpl(argc, argv);
}
