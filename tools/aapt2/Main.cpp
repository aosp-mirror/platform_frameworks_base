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

#include "util/StringPiece.h"

#include <iostream>
#include <vector>

namespace aapt {

extern int compile(const std::vector<StringPiece>& args);
extern int link(const std::vector<StringPiece>& args);
extern int dump(const std::vector<StringPiece>& args);
extern int diff(const std::vector<StringPiece>& args);

} // namespace aapt

int main(int argc, char** argv) {
    if (argc >= 2) {
        argv += 1;
        argc -= 1;

        std::vector<aapt::StringPiece> args;
        for (int i = 1; i < argc; i++) {
            args.push_back(argv[i]);
        }

        aapt::StringPiece command(argv[0]);
        if (command == "compile" || command == "c") {
            return aapt::compile(args);
        } else if (command == "link" || command == "l") {
            return aapt::link(args);
        } else if (command == "dump" || command == "d") {
            return aapt::dump(args);
        } else if (command == "diff") {
            return aapt::diff(args);
        }
        std::cerr << "unknown command '" << command << "'\n";
    } else {
        std::cerr << "no command specified\n";
    }

    std::cerr << "\nusage: aapt2 [compile|link|dump|diff] ..." << std::endl;
    return 1;
}
