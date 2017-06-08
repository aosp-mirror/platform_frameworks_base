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

#include <iostream>
#include <vector>

#include "androidfw/StringPiece.h"

#include "Diagnostics.h"

namespace aapt {

// DO NOT UPDATE, this is more of a marketing version.
static const char* sMajorVersion = "2";

// Update minor version whenever a feature or flag is added.
static const char* sMinorVersion = "17";

int PrintVersion() {
  std::cerr << "Android Asset Packaging Tool (aapt) " << sMajorVersion << "."
            << sMinorVersion << std::endl;
  return 0;
}

extern int Compile(const std::vector<android::StringPiece>& args, IDiagnostics* diagnostics);
extern int Link(const std::vector<android::StringPiece>& args, IDiagnostics* diagnostics);
extern int Dump(const std::vector<android::StringPiece>& args);
extern int Diff(const std::vector<android::StringPiece>& args);
extern int Optimize(const std::vector<android::StringPiece>& args);

}  // namespace aapt

int main(int argc, char** argv) {
  if (argc >= 2) {
    argv += 1;
    argc -= 1;

    std::vector<android::StringPiece> args;
    for (int i = 1; i < argc; i++) {
      args.push_back(argv[i]);
    }

    android::StringPiece command(argv[0]);
    if (command == "compile" || command == "c") {
      aapt::StdErrDiagnostics diagnostics;
      return aapt::Compile(args, &diagnostics);
    } else if (command == "link" || command == "l") {
      aapt::StdErrDiagnostics diagnostics;
      return aapt::Link(args, &diagnostics);
    } else if (command == "dump" || command == "d") {
      return aapt::Dump(args);
    } else if (command == "diff") {
      return aapt::Diff(args);
    } else if (command == "optimize") {
      return aapt::Optimize(args);
    } else if (command == "version") {
      return aapt::PrintVersion();
    }
    std::cerr << "unknown command '" << command << "'\n";
  } else {
    std::cerr << "no command specified\n";
  }

  std::cerr << "\nusage: aapt2 [compile|link|dump|diff|optimize|version] ..."
            << std::endl;
  return 1;
}
