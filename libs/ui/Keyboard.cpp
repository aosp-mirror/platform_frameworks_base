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

#include <ui/Keyboard.h>
#include <ui/KeycodeLabels.h>
#include <utils/Errors.h>
#include <utils/Log.h>
#include <cutils/properties.h>

namespace android {

static void selectKeyMap(KeyMapInfo& keyMapInfo, const String8& keyMapName, bool defaultKeyMap) {
    if (keyMapInfo.keyMapName.isEmpty()) {
        keyMapInfo.keyMapName.setTo(keyMapName);
        keyMapInfo.isDefaultKeyMap = defaultKeyMap;
    }
}

static bool probeKeyMap(KeyMapInfo& keyMapInfo, const String8& keyMapName, bool defaultKeyMap) {
    const char* root = getenv("ANDROID_ROOT");

    // TODO Consider also looking somewhere in a writeable partition like /data for a
    //      custom keymap supplied by the user for this device.
    bool haveKeyLayout = !keyMapInfo.keyLayoutFile.isEmpty();
    if (!haveKeyLayout) {
        keyMapInfo.keyLayoutFile.setTo(root);
        keyMapInfo.keyLayoutFile.append("/usr/keylayout/");
        keyMapInfo.keyLayoutFile.append(keyMapName);
        keyMapInfo.keyLayoutFile.append(".kl");
        if (access(keyMapInfo.keyLayoutFile.string(), R_OK)) {
            keyMapInfo.keyLayoutFile.clear();
        } else {
            haveKeyLayout = true;
        }
    }

    bool haveKeyCharacterMap = !keyMapInfo.keyCharacterMapFile.isEmpty();
    if (!haveKeyCharacterMap) {
        keyMapInfo.keyCharacterMapFile.setTo(root);
        keyMapInfo.keyCharacterMapFile.append("/usr/keychars/");
        keyMapInfo.keyCharacterMapFile.append(keyMapName);
        keyMapInfo.keyCharacterMapFile.append(".kcm");
        if (access(keyMapInfo.keyCharacterMapFile.string(), R_OK)) {
            keyMapInfo.keyCharacterMapFile.clear();
        } else {
            haveKeyCharacterMap = true;
        }
    }

    if (haveKeyLayout || haveKeyCharacterMap) {
        selectKeyMap(keyMapInfo, keyMapName, defaultKeyMap);
    }
    return haveKeyLayout && haveKeyCharacterMap;
}

status_t resolveKeyMap(const String8& deviceName, KeyMapInfo& outKeyMapInfo) {
    // As an initial key map name, try using the device name.
    String8 keyMapName(deviceName);
    char* p = keyMapName.lockBuffer(keyMapName.size());
    while (*p) {
        if (*p == ' ') *p = '_';
        p++;
    }
    keyMapName.unlockBuffer();

    if (probeKeyMap(outKeyMapInfo, keyMapName, false)) return OK;

    // TODO Consider allowing the user to configure a specific key map somehow.

    // Try the Generic key map.
    // TODO Apply some additional heuristics here to figure out what kind of
    //      generic key map to use (US English, etc.).
    keyMapName.setTo("Generic");
    if (probeKeyMap(outKeyMapInfo, keyMapName, true)) return OK;

    // Give up!
    keyMapName.setTo("unknown");
    selectKeyMap(outKeyMapInfo, keyMapName, true);
    LOGE("Could not determine key map for device '%s'.", deviceName.string());
    return NAME_NOT_FOUND;
}

void setKeyboardProperties(int32_t deviceId, const String8& deviceName,
        const KeyMapInfo& keyMapInfo) {
    char propName[PROPERTY_KEY_MAX];
    snprintf(propName, sizeof(propName), "hw.keyboards.%u.devname", deviceId);
    property_set(propName, deviceName.string());
    snprintf(propName, sizeof(propName), "hw.keyboards.%u.keymap", deviceId);
    property_set(propName, keyMapInfo.keyMapName.string());
    snprintf(propName, sizeof(propName), "hw.keyboards.%u.klfile", deviceId);
    property_set(propName, keyMapInfo.keyLayoutFile.string());
    snprintf(propName, sizeof(propName), "hw.keyboards.%u.kcmfile", deviceId);
    property_set(propName, keyMapInfo.keyCharacterMapFile.string());
}

void clearKeyboardProperties(int32_t deviceId) {
    char propName[PROPERTY_KEY_MAX];
    snprintf(propName, sizeof(propName), "hw.keyboards.%u.devname", deviceId);
    property_set(propName, "");
    snprintf(propName, sizeof(propName), "hw.keyboards.%u.keymap", deviceId);
    property_set(propName, "");
    snprintf(propName, sizeof(propName), "hw.keyboards.%u.klfile", deviceId);
    property_set(propName, "");
    snprintf(propName, sizeof(propName), "hw.keyboards.%u.kcmfile", deviceId);
    property_set(propName, "");
}

status_t getKeyCharacterMapFile(int32_t deviceId, String8& outKeyCharacterMapFile) {
    char propName[PROPERTY_KEY_MAX];
    char fn[PROPERTY_VALUE_MAX];
    snprintf(propName, sizeof(propName), "hw.keyboards.%u.kcmfile", deviceId);
    if (property_get(propName, fn, "") > 0) {
        outKeyCharacterMapFile.setTo(fn);
        return OK;
    }

    const char* root = getenv("ANDROID_ROOT");
    char path[PATH_MAX];
    if (deviceId == DEVICE_ID_VIRTUAL_KEYBOARD) {
        snprintf(path, sizeof(path), "%s/usr/keychars/Virtual.kcm", root);
        if (!access(path, R_OK)) {
            outKeyCharacterMapFile.setTo(path);
            return OK;
        }
    }

    snprintf(path, sizeof(path), "%s/usr/keychars/Generic.kcm", root);
    if (!access(path, R_OK)) {
        outKeyCharacterMapFile.setTo(path);
        return OK;
    }

    LOGE("Can't find any key character map files (also tried %s)", path);
    return NAME_NOT_FOUND;
}

static int lookupLabel(const char* literal, const KeycodeLabel *list) {
    while (list->literal) {
        if (strcmp(literal, list->literal) == 0) {
            return list->value;
        }
        list++;
    }
    return list->value;
}

int32_t getKeyCodeByLabel(const char* label) {
    return int32_t(lookupLabel(label, KEYCODES));
}

uint32_t getKeyFlagByLabel(const char* label) {
    return uint32_t(lookupLabel(label, FLAGS));
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


} // namespace android
