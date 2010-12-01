/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef _UI_KEYBOARD_H
#define _UI_KEYBOARD_H

#include <ui/Input.h>
#include <utils/Errors.h>
#include <utils/String8.h>
#include <utils/PropertyMap.h>

namespace android {

enum {
    /* Device id of the built in keyboard. */
    DEVICE_ID_BUILT_IN_KEYBOARD = 0,

    /* Device id of a generic virtual keyboard with a full layout that can be used
     * to synthesize key events. */
    DEVICE_ID_VIRTUAL_KEYBOARD = -1,
};

struct KeyMapInfo {
    String8 keyLayoutFile;
    String8 keyCharacterMapFile;
    bool isDefaultKeyMap;

    KeyMapInfo() : isDefaultKeyMap(false) {
    }

    bool isComplete() {
        return !keyLayoutFile.isEmpty() && !keyCharacterMapFile.isEmpty();
    }
};

/**
 * Resolves the key map to use for a particular keyboard device.
 */
extern status_t resolveKeyMap(const String8& deviceName,
        const PropertyMap* deviceConfiguration, KeyMapInfo& outKeyMapInfo);

/**
 * Sets keyboard system properties.
 */
extern void setKeyboardProperties(int32_t deviceId, const String8& deviceName,
        const KeyMapInfo& keyMapInfo);

/**
 * Clears keyboard system properties.
 */
extern void clearKeyboardProperties(int32_t deviceId);

/**
 * Gets the key character map filename for a device using inspecting system properties
 * and then falling back on a default key character map if necessary.
 * Returns a NAME_NOT_FOUND if none found.
 */
extern status_t getKeyCharacterMapFile(int32_t deviceId, String8& outKeyCharacterMapFile);

/**
 * Gets a key code by its short form label, eg. "HOME".
 * Returns 0 if unknown.
 */
extern int32_t getKeyCodeByLabel(const char* label);

/**
 * Gets a key flag by its short form label, eg. "WAKE".
 * Returns 0 if unknown.
 */
extern uint32_t getKeyFlagByLabel(const char* label);

/**
 * Updates a meta state field when a key is pressed or released.
 */
extern int32_t updateMetaState(int32_t keyCode, bool down, int32_t oldMetaState);

} // namespace android

#endif // _UI_KEYBOARD_H
