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

#define LOG_TAG "Keyboard"

#include <stdlib.h>
#include <unistd.h>
#include <limits.h>

#include <androidfw/Keyboard.h>
#include <androidfw/KeycodeLabels.h>
#include <androidfw/KeyLayoutMap.h>
#include <androidfw/KeyCharacterMap.h>
#include <androidfw/InputDevice.h>
#include <utils/Errors.h>
#include <utils/Log.h>
#include <cutils/properties.h>

namespace android {

// --- KeyMap ---

KeyMap::KeyMap() {
}

KeyMap::~KeyMap() {
}

status_t KeyMap::load(const InputDeviceIdentifier& deviceIdenfifier,
        const PropertyMap* deviceConfiguration) {
    // Use the configured key layout if available.
    if (deviceConfiguration) {
        String8 keyLayoutName;
        if (deviceConfiguration->tryGetProperty(String8("keyboard.layout"),
                keyLayoutName)) {
            status_t status = loadKeyLayout(deviceIdenfifier, keyLayoutName);
            if (status == NAME_NOT_FOUND) {
                ALOGE("Configuration for keyboard device '%s' requested keyboard layout '%s' but "
                        "it was not found.",
                        deviceIdenfifier.name.string(), keyLayoutName.string());
            }
        }

        String8 keyCharacterMapName;
        if (deviceConfiguration->tryGetProperty(String8("keyboard.characterMap"),
                keyCharacterMapName)) {
            status_t status = loadKeyCharacterMap(deviceIdenfifier, keyCharacterMapName);
            if (status == NAME_NOT_FOUND) {
                ALOGE("Configuration for keyboard device '%s' requested keyboard character "
                        "map '%s' but it was not found.",
                        deviceIdenfifier.name.string(), keyLayoutName.string());
            }
        }

        if (isComplete()) {
            return OK;
        }
    }

    // Try searching by device identifier.
    if (probeKeyMap(deviceIdenfifier, String8::empty())) {
        return OK;
    }

    // Fall back on the Generic key map.
    // TODO Apply some additional heuristics here to figure out what kind of
    //      generic key map to use (US English, etc.) for typical external keyboards.
    if (probeKeyMap(deviceIdenfifier, String8("Generic"))) {
        return OK;
    }

    // Try the Virtual key map as a last resort.
    if (probeKeyMap(deviceIdenfifier, String8("Virtual"))) {
        return OK;
    }

    // Give up!
    ALOGE("Could not determine key map for device '%s' and no default key maps were found!",
            deviceIdenfifier.name.string());
    return NAME_NOT_FOUND;
}

bool KeyMap::probeKeyMap(const InputDeviceIdentifier& deviceIdentifier,
        const String8& keyMapName) {
    if (!haveKeyLayout()) {
        loadKeyLayout(deviceIdentifier, keyMapName);
    }
    if (!haveKeyCharacterMap()) {
        loadKeyCharacterMap(deviceIdentifier, keyMapName);
    }
    return isComplete();
}

status_t KeyMap::loadKeyLayout(const InputDeviceIdentifier& deviceIdentifier,
        const String8& name) {
    String8 path(getPath(deviceIdentifier, name,
            INPUT_DEVICE_CONFIGURATION_FILE_TYPE_KEY_LAYOUT));
    if (path.isEmpty()) {
        return NAME_NOT_FOUND;
    }

    status_t status = KeyLayoutMap::load(path, &keyLayoutMap);
    if (status) {
        return status;
    }

    keyLayoutFile.setTo(path);
    return OK;
}

status_t KeyMap::loadKeyCharacterMap(const InputDeviceIdentifier& deviceIdentifier,
        const String8& name) {
    String8 path(getPath(deviceIdentifier, name,
            INPUT_DEVICE_CONFIGURATION_FILE_TYPE_KEY_CHARACTER_MAP));
    if (path.isEmpty()) {
        return NAME_NOT_FOUND;
    }

    status_t status = KeyCharacterMap::load(path,
            KeyCharacterMap::FORMAT_BASE, &keyCharacterMap);
    if (status) {
        return status;
    }

    keyCharacterMapFile.setTo(path);
    return OK;
}

String8 KeyMap::getPath(const InputDeviceIdentifier& deviceIdentifier,
        const String8& name, InputDeviceConfigurationFileType type) {
    return name.isEmpty()
            ? getInputDeviceConfigurationFilePathByDeviceIdentifier(deviceIdentifier, type)
            : getInputDeviceConfigurationFilePathByName(name, type);
}


// --- Global functions ---

bool isEligibleBuiltInKeyboard(const InputDeviceIdentifier& deviceIdentifier,
        const PropertyMap* deviceConfiguration, const KeyMap* keyMap) {
    if (!keyMap->haveKeyCharacterMap()
            || keyMap->keyCharacterMap->getKeyboardType()
                    == KeyCharacterMap::KEYBOARD_TYPE_SPECIAL_FUNCTION) {
        return false;
    }

    if (deviceConfiguration) {
        bool builtIn = false;
        if (deviceConfiguration->tryGetProperty(String8("keyboard.builtIn"), builtIn)
                && builtIn) {
            return true;
        }
    }

    return strstr(deviceIdentifier.name.string(), "-keypad");
}

static int lookupValueByLabel(const char* literal, const KeycodeLabel *list) {
    while (list->literal) {
        if (strcmp(literal, list->literal) == 0) {
            return list->value;
        }
        list++;
    }
    return list->value;
}

static const char* lookupLabelByValue(int value, const KeycodeLabel *list) {
    while (list->literal) {
        if (list->value == value) {
            return list->literal;
        }
        list++;
    }
    return NULL;
}

int32_t getKeyCodeByLabel(const char* label) {
    return int32_t(lookupValueByLabel(label, KEYCODES));
}

uint32_t getKeyFlagByLabel(const char* label) {
    return uint32_t(lookupValueByLabel(label, FLAGS));
}

int32_t getAxisByLabel(const char* label) {
    return int32_t(lookupValueByLabel(label, AXES));
}

const char* getAxisLabel(int32_t axisId) {
    return lookupLabelByValue(axisId, AXES);
}

static int32_t setEphemeralMetaState(int32_t mask, bool down, int32_t oldMetaState) {
    int32_t newMetaState;
    if (down) {
        newMetaState = oldMetaState | mask;
    } else {
        newMetaState = oldMetaState &
                ~(mask | AMETA_ALT_ON | AMETA_SHIFT_ON | AMETA_CTRL_ON | AMETA_META_ON);
    }

    if (newMetaState & (AMETA_ALT_LEFT_ON | AMETA_ALT_RIGHT_ON)) {
        newMetaState |= AMETA_ALT_ON;
    }

    if (newMetaState & (AMETA_SHIFT_LEFT_ON | AMETA_SHIFT_RIGHT_ON)) {
        newMetaState |= AMETA_SHIFT_ON;
    }

    if (newMetaState & (AMETA_CTRL_LEFT_ON | AMETA_CTRL_RIGHT_ON)) {
        newMetaState |= AMETA_CTRL_ON;
    }

    if (newMetaState & (AMETA_META_LEFT_ON | AMETA_META_RIGHT_ON)) {
        newMetaState |= AMETA_META_ON;
    }
    return newMetaState;
}

static int32_t toggleLockedMetaState(int32_t mask, bool down, int32_t oldMetaState) {
    if (down) {
        return oldMetaState;
    } else {
        return oldMetaState ^ mask;
    }
}

int32_t updateMetaState(int32_t keyCode, bool down, int32_t oldMetaState) {
    int32_t mask;
    switch (keyCode) {
    case AKEYCODE_ALT_LEFT:
        return setEphemeralMetaState(AMETA_ALT_LEFT_ON, down, oldMetaState);
    case AKEYCODE_ALT_RIGHT:
        return setEphemeralMetaState(AMETA_ALT_RIGHT_ON, down, oldMetaState);
    case AKEYCODE_SHIFT_LEFT:
        return setEphemeralMetaState(AMETA_SHIFT_LEFT_ON, down, oldMetaState);
    case AKEYCODE_SHIFT_RIGHT:
        return setEphemeralMetaState(AMETA_SHIFT_RIGHT_ON, down, oldMetaState);
    case AKEYCODE_SYM:
        return setEphemeralMetaState(AMETA_SYM_ON, down, oldMetaState);
    case AKEYCODE_FUNCTION:
        return setEphemeralMetaState(AMETA_FUNCTION_ON, down, oldMetaState);
    case AKEYCODE_CTRL_LEFT:
        return setEphemeralMetaState(AMETA_CTRL_LEFT_ON, down, oldMetaState);
    case AKEYCODE_CTRL_RIGHT:
        return setEphemeralMetaState(AMETA_CTRL_RIGHT_ON, down, oldMetaState);
    case AKEYCODE_META_LEFT:
        return setEphemeralMetaState(AMETA_META_LEFT_ON, down, oldMetaState);
    case AKEYCODE_META_RIGHT:
        return setEphemeralMetaState(AMETA_META_RIGHT_ON, down, oldMetaState);
    case AKEYCODE_CAPS_LOCK:
        return toggleLockedMetaState(AMETA_CAPS_LOCK_ON, down, oldMetaState);
    case AKEYCODE_NUM_LOCK:
        return toggleLockedMetaState(AMETA_NUM_LOCK_ON, down, oldMetaState);
    case AKEYCODE_SCROLL_LOCK:
        return toggleLockedMetaState(AMETA_SCROLL_LOCK_ON, down, oldMetaState);
    default:
        return oldMetaState;
    }
}

bool isMetaKey(int32_t keyCode) {
    switch (keyCode) {
    case AKEYCODE_ALT_LEFT:
    case AKEYCODE_ALT_RIGHT:
    case AKEYCODE_SHIFT_LEFT:
    case AKEYCODE_SHIFT_RIGHT:
    case AKEYCODE_SYM:
    case AKEYCODE_FUNCTION:
    case AKEYCODE_CTRL_LEFT:
    case AKEYCODE_CTRL_RIGHT:
    case AKEYCODE_META_LEFT:
    case AKEYCODE_META_RIGHT:
    case AKEYCODE_CAPS_LOCK:
    case AKEYCODE_NUM_LOCK:
    case AKEYCODE_SCROLL_LOCK:
        return true;
    default:
        return false;
    }
}


} // namespace android
