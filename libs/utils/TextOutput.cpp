/*
 * Copyright (C) 2005 The Android Open Source Project
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

#include <utils/TextOutput.h>

#include <utils/Debug.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

namespace android {

// ---------------------------------------------------------------------------

TextOutput::TextOutput() { 
}

TextOutput::~TextOutput() { 
}

// ---------------------------------------------------------------------------

TextOutput& operator<<(TextOutput& to, bool val)
{
    if (val) to.print("true", 4);
    else to.print("false", 5);
    return to;
}

TextOutput& operator<<(TextOutput& to, int val)
{
    char buf[16];
    sprintf(buf, "%d", val);
    to.print(buf, strlen(buf));
    return to;
}

TextOutput& operator<<(TextOutput& to, long val)
{
    char buf[16];
    sprintf(buf, "%ld", val);
    to.print(buf, strlen(buf));
    return to;
}

TextOutput& operator<<(TextOutput& to, unsigned int val)
{
    char buf[16];
    sprintf(buf, "%u", val);
    to.print(buf, strlen(buf));
    return to;
}

TextOutput& operator<<(TextOutput& to, unsigned long val)
{
    char buf[16];
    sprintf(buf, "%lu", val);
    to.print(buf, strlen(buf));
    return to;
}

TextOutput& operator<<(TextOutput& to, long long val)
{
    char buf[32];
    sprintf(buf, "%Ld", val);
    to.print(buf, strlen(buf));
    return to;
}

TextOutput& operator<<(TextOutput& to, unsigned long long val)
{
    char buf[32];
    sprintf(buf, "%Lu", val);
    to.print(buf, strlen(buf));
    return to;
}

static TextOutput& print_float(TextOutput& to, double value)
{
    char buf[64];
    sprintf(buf, "%g", value);
    if( !strchr(buf, '.') && !strchr(buf, 'e') &&
        !strchr(buf, 'E') ) {
        strncat(buf, ".0", sizeof(buf)-1);
    }
    to.print(buf, strlen(buf));
    return to;
}

TextOutput& operator<<(TextOutput& to, float val)
{
    return print_float(to,val);
}

TextOutput& operator<<(TextOutput& to, double val)
{
    return print_float(to,val);
}

TextOutput& operator<<(TextOutput& to, const void* val)
{
    char buf[16];
    sprintf(buf, "%p", val);
    to.print(buf, strlen(buf));
    return to;
}

static void textOutputPrinter(void* cookie, const char* txt)
{
    ((TextOutput*)cookie)->print(txt, strlen(txt));
}

TextOutput& operator<<(TextOutput& to, const TypeCode& val)
{
    printTypeCode(val.typeCode(), textOutputPrinter, (void*)&to);
    return to;
}

HexDump::HexDump(const void *buf, size_t size, size_t bytesPerLine)
    : mBuffer(buf)
    , mSize(size)
    , mBytesPerLine(bytesPerLine)
    , mSingleLineCutoff(16)
    , mAlignment(4)
    , mCArrayStyle(false)
{
    if (bytesPerLine >= 16) mAlignment = 4;
    else if (bytesPerLine >= 8) mAlignment = 2;
    else mAlignment = 1;
}

TextOutput& operator<<(TextOutput& to, const HexDump& val)
{
    printHexData(0, val.buffer(), val.size(), val.bytesPerLine(),
        val.singleLineCutoff(), val.alignment(), val.carrayStyle(),
        textOutputPrinter, (void*)&to);
    return to;
}

}; // namespace android
