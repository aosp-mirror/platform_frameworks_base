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

#ifndef PAGE_TYPE_INFO_PARSER_H
#define PAGE_TYPE_INFO_PARSER_H

#include "TextParserBase.h"

using namespace android;

/**
 * PageTypeInfo parser, parses text to protobuf in /proc/pageinfotype
 */
class PageTypeInfoParser : public TextParserBase {
public:
    PageTypeInfoParser() : TextParserBase(String8("PageTypeInfo")) {};
    ~PageTypeInfoParser() {};

    virtual status_t Parse(const int in, const int out) const;
};

#endif  // PAGE_TYPE_INFO_PARSER_H
