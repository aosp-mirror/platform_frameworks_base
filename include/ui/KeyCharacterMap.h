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

#ifndef _UI_KEY_CHARACTER_MAP_H
#define _UI_KEY_CHARACTER_MAP_H

#include <stdint.h>
#include <utils/Vector.h>

using namespace android;

class KeyCharacterMap
{
public:
    ~KeyCharacterMap();

    // see the javadoc for android.text.method.KeyCharacterMap for what
    // these do
    unsigned short get(int keycode, int meta);
    unsigned short getNumber(int keycode);
    unsigned short getMatch(int keycode, const unsigned short* chars,
                            int charsize, uint32_t modifiers);
    unsigned short getDisplayLabel(int keycode);
    bool getKeyData(int keycode, unsigned short *displayLabel,
                    unsigned short *number, unsigned short* results);
    inline unsigned int getKeyboardType() { return m_type; }
    bool getEvents(uint16_t* chars, size_t len,
                   Vector<int32_t>* keys, Vector<uint32_t>* modifiers);

    static KeyCharacterMap* load(int id);

    enum {
        NUMERIC = 1,
        Q14 = 2,
        QWERTY = 3 // or AZERTY or whatever
    };

#define META_MASK 3

private:
    struct Key
    {
        int32_t keycode;
        uint16_t display_label;
        uint16_t number;
        uint16_t data[META_MASK + 1];
    };

    KeyCharacterMap();
    static KeyCharacterMap* try_file(const char* filename);
    Key* find_key(int keycode);
    bool find_char(uint16_t c, uint32_t* key, uint32_t* mods);

    unsigned int m_type;
    unsigned int m_keyCount;
    Key* m_keys;
};

#endif // _UI_KEY_CHARACTER_MAP_H
