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

#ifndef AAPT_FLAGS_H
#define AAPT_FLAGS_H

#include "util/Maybe.h"
#include "util/StringPiece.h"

#include <functional>
#include <ostream>
#include <string>
#include <vector>

namespace aapt {

class Flags {
public:
    Flags& requiredFlag(const StringPiece& name, const StringPiece& description,
                        std::string* value);
    Flags& requiredFlagList(const StringPiece& name, const StringPiece& description,
                            std::vector<std::string>* value);
    Flags& optionalFlag(const StringPiece& name, const StringPiece& description,
                        Maybe<std::string>* value);
    Flags& optionalFlagList(const StringPiece& name, const StringPiece& description,
                            std::vector<std::string>* value);
    Flags& optionalSwitch(const StringPiece& name, const StringPiece& description,
                          bool* value);

    void usage(const StringPiece& command, std::ostream* out);

    bool parse(const StringPiece& command, const std::vector<StringPiece>& args,
               std::ostream* outError);

    const std::vector<std::string>& getArgs();

private:
    struct Flag {
        std::string name;
        std::string description;
        std::function<bool(const StringPiece& value)> action;
        bool required;
        size_t numArgs;

        bool parsed;
    };

    std::vector<Flag> mFlags;
    std::vector<std::string> mArgs;
};

} // namespace aapt

#endif // AAPT_FLAGS_H
