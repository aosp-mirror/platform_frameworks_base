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

#include "Flags.h"
#include "util/StringPiece.h"
#include "util/Util.h"

#include <iomanip>
#include <iostream>
#include <string>
#include <vector>

namespace aapt {

Flags& Flags::requiredFlag(const StringPiece& name, const StringPiece& description,
                         std::string* value) {
    auto func = [value](const StringPiece& arg) -> bool {
        *value = arg.toString();
        return true;
    };

    mFlags.push_back(Flag{ name.toString(), description.toString(), func, true, 1, false});
    return *this;
}

Flags& Flags::requiredFlagList(const StringPiece& name, const StringPiece& description,
                               std::vector<std::string>* value) {
    auto func = [value](const StringPiece& arg) -> bool {
        value->push_back(arg.toString());
        return true;
    };

    mFlags.push_back(Flag{ name.toString(), description.toString(), func, true, 1, false });
    return *this;
}

Flags& Flags::optionalFlag(const StringPiece& name, const StringPiece& description,
                           Maybe<std::string>* value) {
    auto func = [value](const StringPiece& arg) -> bool {
        *value = arg.toString();
        return true;
    };

    mFlags.push_back(Flag{ name.toString(), description.toString(), func, false, 1, false });
    return *this;
}

Flags& Flags::optionalFlagList(const StringPiece& name, const StringPiece& description,
                               std::vector<std::string>* value) {
    auto func = [value](const StringPiece& arg) -> bool {
        value->push_back(arg.toString());
        return true;
    };

    mFlags.push_back(Flag{ name.toString(), description.toString(), func, false, 1, false });
    return *this;
}

Flags& Flags::optionalSwitch(const StringPiece& name, const StringPiece& description,
                             bool* value) {
    auto func = [value](const StringPiece& arg) -> bool {
        *value = true;
        return true;
    };

    mFlags.push_back(Flag{ name.toString(), description.toString(), func, false, 0, false });
    return *this;
}

void Flags::usage(const StringPiece& command, std::ostream* out) {
    constexpr size_t kWidth = 50;

    *out << command << " [options]";
    for (const Flag& flag : mFlags) {
        if (flag.required) {
            *out << " " << flag.name << " arg";
        }
    }

    *out << " files...\n\nOptions:\n";

    for (const Flag& flag : mFlags) {
        std::string argLine = flag.name;
        if (flag.numArgs > 0) {
            argLine += " arg";
        }

        // Split the description by newlines and write out the argument (which is empty after
        // the first line) followed by the description line. This will make sure that multiline
        // descriptions are still right justified and aligned.
        for (StringPiece line : util::tokenize<char>(flag.description, '\n')) {
            *out << " " << std::setw(kWidth) << std::left << argLine << line << "\n";
            argLine = " ";
        }
    }
    *out << " " << std::setw(kWidth) << std::left << "-h" << "Displays this help menu\n";
    out->flush();
}

bool Flags::parse(const StringPiece& command, const std::vector<StringPiece>& args,
                  std::ostream* outError) {
    for (size_t i = 0; i < args.size(); i++) {
        StringPiece arg = args[i];
        if (*(arg.data()) != '-') {
            mArgs.push_back(arg.toString());
            continue;
        }

        if (arg == "-h" || arg == "--help") {
            usage(command, outError);
            return false;
        }

        bool match = false;
        for (Flag& flag : mFlags) {
            if (arg == flag.name) {
                if (flag.numArgs > 0) {
                    i++;
                    if (i >= args.size()) {
                        *outError << flag.name << " missing argument.\n\n";
                        usage(command, outError);
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
            *outError << "unknown option '" << arg << "'.\n\n";
            usage(command, outError);
            return false;
        }
    }

    for (const Flag& flag : mFlags) {
        if (flag.required && !flag.parsed) {
            *outError << "missing required flag " << flag.name << "\n\n";
            usage(command, outError);
            return false;
        }
    }
    return true;
}

const std::vector<std::string>& Flags::getArgs() {
    return mArgs;
}

} // namespace aapt
