/*
 * Copyright (C) 2008 The Android Open Source Project
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

#define LOG_TAG "KeyLayoutMap"

#include <stdlib.h>
#include <android/keycodes.h>
#include <ui/Keyboard.h>
#include <ui/KeyLayoutMap.h>
#include <utils/Log.h>
#include <utils/Errors.h>
#include <utils/Tokenizer.h>
#include <utils/Timers.h>

// Enables debug output for the parser.
#define DEBUG_PARSER 0

// Enables debug output for parser performance.
#define DEBUG_PARSER_PERFORMANCE 0

// Enables debug output for mapping.
#define DEBUG_MAPPING 0


namespace android {

static const char* WHITESPACE = " \t\r";

// --- KeyLayoutMap ---

KeyLayoutMap::KeyLayoutMap() {
}

KeyLayoutMap::~KeyLayoutMap() {
}

status_t KeyLayoutMap::load(const String8& filename, KeyLayoutMap** outMap) {
    *outMap = NULL;

    Tokenizer* tokenizer;
    status_t status = Tokenizer::open(filename, &tokenizer);
    if (status) {
        ALOGE("Error %d opening key layout map file %s.", status, filename.string());
    } else {
        KeyLayoutMap* map = new KeyLayoutMap();
        if (!map) {
            ALOGE("Error allocating key layout map.");
            status = NO_MEMORY;
        } else {
#if DEBUG_PARSER_PERFORMANCE
            nsecs_t startTime = systemTime(SYSTEM_TIME_MONOTONIC);
#endif
            Parser parser(map, tokenizer);
            status = parser.parse();
#if DEBUG_PARSER_PERFORMANCE
            nsecs_t elapsedTime = systemTime(SYSTEM_TIME_MONOTONIC) - startTime;
            ALOGD("Parsed key layout map file '%s' %d lines in %0.3fms.",
                    tokenizer->getFilename().string(), tokenizer->getLineNumber(),
                    elapsedTime / 1000000.0);
#endif
            if (status) {
                delete map;
            } else {
                *outMap = map;
            }
        }
        delete tokenizer;
    }
    return status;
}

status_t KeyLayoutMap::mapKey(int32_t scanCode, int32_t* keyCode, uint32_t* flags) const {
    ssize_t index = mKeys.indexOfKey(scanCode);
    if (index < 0) {
#if DEBUG_MAPPING
        ALOGD("mapKey: scanCode=%d ~ Failed.", scanCode);
#endif
        *keyCode = AKEYCODE_UNKNOWN;
        *flags = 0;
        return NAME_NOT_FOUND;
    }

    const Key& k = mKeys.valueAt(index);
    *keyCode = k.keyCode;
    *flags = k.flags;

#if DEBUG_MAPPING
    ALOGD("mapKey: scanCode=%d ~ Result keyCode=%d, flags=0x%08x.", scanCode, *keyCode, *flags);
#endif
    return NO_ERROR;
}

status_t KeyLayoutMap::findScanCodesForKey(int32_t keyCode, Vector<int32_t>* outScanCodes) const {
    const size_t N = mKeys.size();
    for (size_t i=0; i<N; i++) {
        if (mKeys.valueAt(i).keyCode == keyCode) {
            outScanCodes->add(mKeys.keyAt(i));
        }
    }
    return NO_ERROR;
}

status_t KeyLayoutMap::mapAxis(int32_t scanCode, AxisInfo* outAxisInfo) const {
    ssize_t index = mAxes.indexOfKey(scanCode);
    if (index < 0) {
#if DEBUG_MAPPING
        ALOGD("mapAxis: scanCode=%d ~ Failed.", scanCode);
#endif
        return NAME_NOT_FOUND;
    }

    *outAxisInfo = mAxes.valueAt(index);

#if DEBUG_MAPPING
    ALOGD("mapAxis: scanCode=%d ~ Result mode=%d, axis=%d, highAxis=%d, "
            "splitValue=%d, flatOverride=%d.",
            scanCode,
            outAxisInfo->mode, outAxisInfo->axis, outAxisInfo->highAxis,
            outAxisInfo->splitValue, outAxisInfo->flatOverride);
#endif
    return NO_ERROR;
}


// --- KeyLayoutMap::Parser ---

KeyLayoutMap::Parser::Parser(KeyLayoutMap* map, Tokenizer* tokenizer) :
        mMap(map), mTokenizer(tokenizer) {
}

KeyLayoutMap::Parser::~Parser() {
}

status_t KeyLayoutMap::Parser::parse() {
    while (!mTokenizer->isEof()) {
#if DEBUG_PARSER
        ALOGD("Parsing %s: '%s'.", mTokenizer->getLocation().string(),
                mTokenizer->peekRemainderOfLine().string());
#endif

        mTokenizer->skipDelimiters(WHITESPACE);

        if (!mTokenizer->isEol() && mTokenizer->peekChar() != '#') {
            String8 keywordToken = mTokenizer->nextToken(WHITESPACE);
            if (keywordToken == "key") {
                mTokenizer->skipDelimiters(WHITESPACE);
                status_t status = parseKey();
                if (status) return status;
            } else if (keywordToken == "axis") {
                mTokenizer->skipDelimiters(WHITESPACE);
                status_t status = parseAxis();
                if (status) return status;
            } else {
                ALOGE("%s: Expected keyword, got '%s'.", mTokenizer->getLocation().string(),
                        keywordToken.string());
                return BAD_VALUE;
            }

            mTokenizer->skipDelimiters(WHITESPACE);
            if (!mTokenizer->isEol()) {
                ALOGE("%s: Expected end of line, got '%s'.",
                        mTokenizer->getLocation().string(),
                        mTokenizer->peekRemainderOfLine().string());
                return BAD_VALUE;
            }
        }

        mTokenizer->nextLine();
    }
    return NO_ERROR;
}

status_t KeyLayoutMap::Parser::parseKey() {
    String8 scanCodeToken = mTokenizer->nextToken(WHITESPACE);
    char* end;
    int32_t scanCode = int32_t(strtol(scanCodeToken.string(), &end, 0));
    if (*end) {
        ALOGE("%s: Expected key scan code number, got '%s'.", mTokenizer->getLocation().string(),
                scanCodeToken.string());
        return BAD_VALUE;
    }
    if (mMap->mKeys.indexOfKey(scanCode) >= 0) {
        ALOGE("%s: Duplicate entry for key scan code '%s'.", mTokenizer->getLocation().string(),
                scanCodeToken.string());
        return BAD_VALUE;
    }

    mTokenizer->skipDelimiters(WHITESPACE);
    String8 keyCodeToken = mTokenizer->nextToken(WHITESPACE);
    int32_t keyCode = getKeyCodeByLabel(keyCodeToken.string());
    if (!keyCode) {
        ALOGE("%s: Expected key code label, got '%s'.", mTokenizer->getLocation().string(),
                keyCodeToken.string());
        return BAD_VALUE;
    }

    uint32_t flags = 0;
    for (;;) {
        mTokenizer->skipDelimiters(WHITESPACE);
        if (mTokenizer->isEol()) break;

        String8 flagToken = mTokenizer->nextToken(WHITESPACE);
        uint32_t flag = getKeyFlagByLabel(flagToken.string());
        if (!flag) {
            ALOGE("%s: Expected key flag label, got '%s'.", mTokenizer->getLocation().string(),
                    flagToken.string());
            return BAD_VALUE;
        }
        if (flags & flag) {
            ALOGE("%s: Duplicate key flag '%s'.", mTokenizer->getLocation().string(),
                    flagToken.string());
            return BAD_VALUE;
        }
        flags |= flag;
    }

#if DEBUG_PARSER
    ALOGD("Parsed key: scanCode=%d, keyCode=%d, flags=0x%08x.", scanCode, keyCode, flags);
#endif
    Key key;
    key.keyCode = keyCode;
    key.flags = flags;
    mMap->mKeys.add(scanCode, key);
    return NO_ERROR;
}

status_t KeyLayoutMap::Parser::parseAxis() {
    String8 scanCodeToken = mTokenizer->nextToken(WHITESPACE);
    char* end;
    int32_t scanCode = int32_t(strtol(scanCodeToken.string(), &end, 0));
    if (*end) {
        ALOGE("%s: Expected axis scan code number, got '%s'.", mTokenizer->getLocation().string(),
                scanCodeToken.string());
        return BAD_VALUE;
    }
    if (mMap->mAxes.indexOfKey(scanCode) >= 0) {
        ALOGE("%s: Duplicate entry for axis scan code '%s'.", mTokenizer->getLocation().string(),
                scanCodeToken.string());
        return BAD_VALUE;
    }

    AxisInfo axisInfo;

    mTokenizer->skipDelimiters(WHITESPACE);
    String8 token = mTokenizer->nextToken(WHITESPACE);
    if (token == "invert") {
        axisInfo.mode = AxisInfo::MODE_INVERT;

        mTokenizer->skipDelimiters(WHITESPACE);
        String8 axisToken = mTokenizer->nextToken(WHITESPACE);
        axisInfo.axis = getAxisByLabel(axisToken.string());
        if (axisInfo.axis < 0) {
            ALOGE("%s: Expected inverted axis label, got '%s'.",
                    mTokenizer->getLocation().string(), axisToken.string());
            return BAD_VALUE;
        }
    } else if (token == "split") {
        axisInfo.mode = AxisInfo::MODE_SPLIT;

        mTokenizer->skipDelimiters(WHITESPACE);
        String8 splitToken = mTokenizer->nextToken(WHITESPACE);
        axisInfo.splitValue = int32_t(strtol(splitToken.string(), &end, 0));
        if (*end) {
            ALOGE("%s: Expected split value, got '%s'.",
                    mTokenizer->getLocation().string(), splitToken.string());
            return BAD_VALUE;
        }

        mTokenizer->skipDelimiters(WHITESPACE);
        String8 lowAxisToken = mTokenizer->nextToken(WHITESPACE);
        axisInfo.axis = getAxisByLabel(lowAxisToken.string());
        if (axisInfo.axis < 0) {
            ALOGE("%s: Expected low axis label, got '%s'.",
                    mTokenizer->getLocation().string(), lowAxisToken.string());
            return BAD_VALUE;
        }

        mTokenizer->skipDelimiters(WHITESPACE);
        String8 highAxisToken = mTokenizer->nextToken(WHITESPACE);
        axisInfo.highAxis = getAxisByLabel(highAxisToken.string());
        if (axisInfo.highAxis < 0) {
            ALOGE("%s: Expected high axis label, got '%s'.",
                    mTokenizer->getLocation().string(), highAxisToken.string());
            return BAD_VALUE;
        }
    } else {
        axisInfo.axis = getAxisByLabel(token.string());
        if (axisInfo.axis < 0) {
            ALOGE("%s: Expected axis label, 'split' or 'invert', got '%s'.",
                    mTokenizer->getLocation().string(), token.string());
            return BAD_VALUE;
        }
    }

    for (;;) {
        mTokenizer->skipDelimiters(WHITESPACE);
        if (mTokenizer->isEol()) {
            break;
        }
        String8 keywordToken = mTokenizer->nextToken(WHITESPACE);
        if (keywordToken == "flat") {
            mTokenizer->skipDelimiters(WHITESPACE);
            String8 flatToken = mTokenizer->nextToken(WHITESPACE);
            axisInfo.flatOverride = int32_t(strtol(flatToken.string(), &end, 0));
            if (*end) {
                ALOGE("%s: Expected flat value, got '%s'.",
                        mTokenizer->getLocation().string(), flatToken.string());
                return BAD_VALUE;
            }
        } else {
            ALOGE("%s: Expected keyword 'flat', got '%s'.",
                    mTokenizer->getLocation().string(), keywordToken.string());
            return BAD_VALUE;
        }
    }

#if DEBUG_PARSER
    ALOGD("Parsed axis: scanCode=%d, mode=%d, axis=%d, highAxis=%d, "
            "splitValue=%d, flatOverride=%d.",
            scanCode,
            axisInfo.mode, axisInfo.axis, axisInfo.highAxis,
            axisInfo.splitValue, axisInfo.flatOverride);
#endif
    mMap->mAxes.add(scanCode, axisInfo);
    return NO_ERROR;
}

};
