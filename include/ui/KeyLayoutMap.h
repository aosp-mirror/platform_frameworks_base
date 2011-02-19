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

#ifndef _UI_KEY_LAYOUT_MAP_H
#define _UI_KEY_LAYOUT_MAP_H

#include <stdint.h>
#include <utils/Errors.h>
#include <utils/KeyedVector.h>
#include <utils/Tokenizer.h>

namespace android {

/**
 * Describes a mapping from keyboard scan codes and joystick axes to Android key codes and axes.
 */
class KeyLayoutMap {
public:
    ~KeyLayoutMap();

    static status_t load(const String8& filename, KeyLayoutMap** outMap);

    status_t mapKey(int32_t scanCode, int32_t* keyCode, uint32_t* flags) const;
    status_t findScanCodesForKey(int32_t keyCode, Vector<int32_t>* outScanCodes) const;

    status_t mapAxis(int32_t scanCode, int32_t* axis) const;

private:
    struct Key {
        int32_t keyCode;
        uint32_t flags;
    };

    KeyedVector<int32_t, Key> mKeys;
    KeyedVector<int32_t, int32_t> mAxes;

    KeyLayoutMap();

    class Parser {
        KeyLayoutMap* mMap;
        Tokenizer* mTokenizer;

    public:
        Parser(KeyLayoutMap* map, Tokenizer* tokenizer);
        ~Parser();
        status_t parse();

    private:
        status_t parseKey();
        status_t parseAxis();
    };
};

} // namespace android

#endif // _UI_KEY_LAYOUT_MAP_H
