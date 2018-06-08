/*
 * Copyright (C) 2017 The Android Open Source Project
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

#ifndef TEXT_PARSER_BASE_H
#define TEXT_PARSER_BASE_H

#include <utils/Errors.h>
#include <utils/String8.h>

using namespace android;
using namespace std;

/**
 * Base class for text parser
 */
class TextParserBase {
public:
    String8 name;

    TextParserBase(String8 name) : name(name) {};
    virtual ~TextParserBase() {};

    virtual status_t Parse(const int in, const int out) const = 0;
};

/**
 * No op parser returns what it reads
 */
class NoopParser : public TextParserBase {
public:
    NoopParser() : TextParserBase(String8("NoopParser")) {};
    ~NoopParser() {};

    virtual status_t Parse(const int in, const int out) const;
};

/**
 * This parser is used for testing only, results in timeout.
 */
class TimeoutParser : public TextParserBase {
public:
    TimeoutParser() : TextParserBase(String8("TimeoutParser")) {};
    ~TimeoutParser() {};

    virtual status_t Parse(const int /** in */, const int /** out */) const { while (true); };
};

/**
 * This parser is used for testing only, results in reversed input text.
 */
class ReverseParser : public TextParserBase {
public:
    ReverseParser() : TextParserBase(String8("ReverseParser")) {};
    ~ReverseParser() {};

    virtual status_t Parse(const int in, const int out) const;
};

#endif // TEXT_PARSER_BASE_H
