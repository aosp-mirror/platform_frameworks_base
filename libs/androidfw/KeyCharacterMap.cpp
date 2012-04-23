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

#define LOG_TAG "KeyCharacterMap"

#include <stdlib.h>
#include <string.h>
#include <android/keycodes.h>
#include <androidfw/Keyboard.h>
#include <androidfw/KeyCharacterMap.h>

#if HAVE_ANDROID_OS
#include <binder/Parcel.h>
#endif

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
static const char* WHITESPACE_OR_PROPERTY_DELIMITER = " \t\r,:";

struct Modifier {
    const char* label;
    int32_t metaState;
};
static const Modifier modifiers[] = {
        { "shift", AMETA_SHIFT_ON },
        { "lshift", AMETA_SHIFT_LEFT_ON },
        { "rshift", AMETA_SHIFT_RIGHT_ON },
        { "alt", AMETA_ALT_ON },
        { "lalt", AMETA_ALT_LEFT_ON },
        { "ralt", AMETA_ALT_RIGHT_ON },
        { "ctrl", AMETA_CTRL_ON },
        { "lctrl", AMETA_CTRL_LEFT_ON },
        { "rctrl", AMETA_CTRL_RIGHT_ON },
        { "meta", AMETA_META_ON },
        { "lmeta", AMETA_META_LEFT_ON },
        { "rmeta", AMETA_META_RIGHT_ON },
        { "sym", AMETA_SYM_ON },
        { "fn", AMETA_FUNCTION_ON },
        { "capslock", AMETA_CAPS_LOCK_ON },
        { "numlock", AMETA_NUM_LOCK_ON },
        { "scrolllock", AMETA_SCROLL_LOCK_ON },
};

#if DEBUG_MAPPING
static String8 toString(const char16_t* chars, size_t numChars) {
    String8 result;
    for (size_t i = 0; i < numChars; i++) {
        result.appendFormat(i == 0 ? "%d" : ", %d", chars[i]);
    }
    return result;
}
#endif


// --- KeyCharacterMap ---

sp<KeyCharacterMap> KeyCharacterMap::sEmpty = new KeyCharacterMap();

KeyCharacterMap::KeyCharacterMap() :
    mType(KEYBOARD_TYPE_UNKNOWN) {
}

KeyCharacterMap::KeyCharacterMap(const KeyCharacterMap& other) :
    RefBase(), mType(other.mType), mKeysByScanCode(other.mKeysByScanCode),
    mKeysByUsageCode(other.mKeysByUsageCode) {
    for (size_t i = 0; i < other.mKeys.size(); i++) {
        mKeys.add(other.mKeys.keyAt(i), new Key(*other.mKeys.valueAt(i)));
    }
}

KeyCharacterMap::~KeyCharacterMap() {
    for (size_t i = 0; i < mKeys.size(); i++) {
        Key* key = mKeys.editValueAt(i);
        delete key;
    }
}

status_t KeyCharacterMap::load(const String8& filename,
        Format format, sp<KeyCharacterMap>* outMap) {
    outMap->clear();

    Tokenizer* tokenizer;
    status_t status = Tokenizer::open(filename, &tokenizer);
    if (status) {
        ALOGE("Error %d opening key character map file %s.", status, filename.string());
    } else {
        status = load(tokenizer, format, outMap);
        delete tokenizer;
    }
    return status;
}

status_t KeyCharacterMap::loadContents(const String8& filename, const char* contents,
        Format format, sp<KeyCharacterMap>* outMap) {
    outMap->clear();

    Tokenizer* tokenizer;
    status_t status = Tokenizer::fromContents(filename, contents, &tokenizer);
    if (status) {
        ALOGE("Error %d opening key character map.", status);
    } else {
        status = load(tokenizer, format, outMap);
        delete tokenizer;
    }
    return status;
}

status_t KeyCharacterMap::load(Tokenizer* tokenizer,
        Format format, sp<KeyCharacterMap>* outMap) {
    status_t status = OK;
    sp<KeyCharacterMap> map = new KeyCharacterMap();
    if (!map.get()) {
        ALOGE("Error allocating key character map.");
        status = NO_MEMORY;
    } else {
#if DEBUG_PARSER_PERFORMANCE
        nsecs_t startTime = systemTime(SYSTEM_TIME_MONOTONIC);
#endif
        Parser parser(map.get(), tokenizer, format);
        status = parser.parse();
#if DEBUG_PARSER_PERFORMANCE
        nsecs_t elapsedTime = systemTime(SYSTEM_TIME_MONOTONIC) - startTime;
        ALOGD("Parsed key character map file '%s' %d lines in %0.3fms.",
                tokenizer->getFilename().string(), tokenizer->getLineNumber(),
                elapsedTime / 1000000.0);
#endif
        if (!status) {
            *outMap = map;
        }
    }
    return status;
}

sp<KeyCharacterMap> KeyCharacterMap::combine(const sp<KeyCharacterMap>& base,
        const sp<KeyCharacterMap>& overlay) {
    if (overlay == NULL) {
        return base;
    }
    if (base == NULL) {
        return overlay;
    }

    sp<KeyCharacterMap> map = new KeyCharacterMap(*base.get());
    for (size_t i = 0; i < overlay->mKeys.size(); i++) {
        int32_t keyCode = overlay->mKeys.keyAt(i);
        Key* key = overlay->mKeys.valueAt(i);
        ssize_t oldIndex = map->mKeys.indexOfKey(keyCode);
        if (oldIndex >= 0) {
            delete map->mKeys.valueAt(oldIndex);
            map->mKeys.editValueAt(oldIndex) = new Key(*key);
        } else {
            map->mKeys.add(keyCode, new Key(*key));
        }
    }

    for (size_t i = 0; i < overlay->mKeysByScanCode.size(); i++) {
        map->mKeysByScanCode.replaceValueFor(overlay->mKeysByScanCode.keyAt(i),
                overlay->mKeysByScanCode.valueAt(i));
    }

    for (size_t i = 0; i < overlay->mKeysByUsageCode.size(); i++) {
        map->mKeysByUsageCode.replaceValueFor(overlay->mKeysByUsageCode.keyAt(i),
                overlay->mKeysByUsageCode.valueAt(i));
    }
    return map;
}

sp<KeyCharacterMap> KeyCharacterMap::empty() {
    return sEmpty;
}

int32_t KeyCharacterMap::getKeyboardType() const {
    return mType;
}

char16_t KeyCharacterMap::getDisplayLabel(int32_t keyCode) const {
    char16_t result = 0;
    const Key* key;
    if (getKey(keyCode, &key)) {
        result = key->label;
    }
#if DEBUG_MAPPING
    ALOGD("getDisplayLabel: keyCode=%d ~ Result %d.", keyCode, result);
#endif
    return result;
}

char16_t KeyCharacterMap::getNumber(int32_t keyCode) const {
    char16_t result = 0;
    const Key* key;
    if (getKey(keyCode, &key)) {
        result = key->number;
    }
#if DEBUG_MAPPING
    ALOGD("getNumber: keyCode=%d ~ Result %d.", keyCode, result);
#endif
    return result;
}

char16_t KeyCharacterMap::getCharacter(int32_t keyCode, int32_t metaState) const {
    char16_t result = 0;
    const Key* key;
    const Behavior* behavior;
    if (getKeyBehavior(keyCode, metaState, &key, &behavior)) {
        result = behavior->character;
    }
#if DEBUG_MAPPING
    ALOGD("getCharacter: keyCode=%d, metaState=0x%08x ~ Result %d.", keyCode, metaState, result);
#endif
    return result;
}

bool KeyCharacterMap::getFallbackAction(int32_t keyCode, int32_t metaState,
        FallbackAction* outFallbackAction) const {
    outFallbackAction->keyCode = 0;
    outFallbackAction->metaState = 0;

    bool result = false;
    const Key* key;
    const Behavior* behavior;
    if (getKeyBehavior(keyCode, metaState, &key, &behavior)) {
        if (behavior->fallbackKeyCode) {
            outFallbackAction->keyCode = behavior->fallbackKeyCode;
            outFallbackAction->metaState = metaState & ~behavior->metaState;
            result = true;
        }
    }
#if DEBUG_MAPPING
    ALOGD("getFallbackKeyCode: keyCode=%d, metaState=0x%08x ~ Result %s, "
            "fallback keyCode=%d, fallback metaState=0x%08x.",
            keyCode, metaState, result ? "true" : "false",
            outFallbackAction->keyCode, outFallbackAction->metaState);
#endif
    return result;
}

char16_t KeyCharacterMap::getMatch(int32_t keyCode, const char16_t* chars, size_t numChars,
        int32_t metaState) const {
    char16_t result = 0;
    const Key* key;
    if (getKey(keyCode, &key)) {
        // Try to find the most general behavior that maps to this character.
        // For example, the base key behavior will usually be last in the list.
        // However, if we find a perfect meta state match for one behavior then use that one.
        for (const Behavior* behavior = key->firstBehavior; behavior; behavior = behavior->next) {
            if (behavior->character) {
                for (size_t i = 0; i < numChars; i++) {
                    if (behavior->character == chars[i]) {
                        result = behavior->character;
                        if ((behavior->metaState & metaState) == behavior->metaState) {
                            goto ExactMatch;
                        }
                        break;
                    }
                }
            }
        }
    ExactMatch: ;
    }
#if DEBUG_MAPPING
    ALOGD("getMatch: keyCode=%d, chars=[%s], metaState=0x%08x ~ Result %d.",
            keyCode, toString(chars, numChars).string(), metaState, result);
#endif
    return result;
}

bool KeyCharacterMap::getEvents(int32_t deviceId, const char16_t* chars, size_t numChars,
        Vector<KeyEvent>& outEvents) const {
    nsecs_t now = systemTime(SYSTEM_TIME_MONOTONIC);

    for (size_t i = 0; i < numChars; i++) {
        int32_t keyCode, metaState;
        char16_t ch = chars[i];
        if (!findKey(ch, &keyCode, &metaState)) {
#if DEBUG_MAPPING
            ALOGD("getEvents: deviceId=%d, chars=[%s] ~ Failed to find mapping for character %d.",
                    deviceId, toString(chars, numChars).string(), ch);
#endif
            return false;
        }

        int32_t currentMetaState = 0;
        addMetaKeys(outEvents, deviceId, metaState, true, now, &currentMetaState);
        addKey(outEvents, deviceId, keyCode, currentMetaState, true, now);
        addKey(outEvents, deviceId, keyCode, currentMetaState, false, now);
        addMetaKeys(outEvents, deviceId, metaState, false, now, &currentMetaState);
    }
#if DEBUG_MAPPING
    ALOGD("getEvents: deviceId=%d, chars=[%s] ~ Generated %d events.",
            deviceId, toString(chars, numChars).string(), int32_t(outEvents.size()));
    for (size_t i = 0; i < outEvents.size(); i++) {
        ALOGD("  Key: keyCode=%d, metaState=0x%08x, %s.",
                outEvents[i].getKeyCode(), outEvents[i].getMetaState(),
                outEvents[i].getAction() == AKEY_EVENT_ACTION_DOWN ? "down" : "up");
    }
#endif
    return true;
}

status_t KeyCharacterMap::mapKey(int32_t scanCode, int32_t usageCode, int32_t* outKeyCode) const {
    if (usageCode) {
        ssize_t index = mKeysByUsageCode.indexOfKey(usageCode);
        if (index >= 0) {
#if DEBUG_MAPPING
    ALOGD("mapKey: scanCode=%d, usageCode=0x%08x ~ Result keyCode=%d.",
            scanCode, usageCode, *outKeyCode);
#endif
            *outKeyCode = mKeysByUsageCode.valueAt(index);
            return OK;
        }
    }
    if (scanCode) {
        ssize_t index = mKeysByScanCode.indexOfKey(scanCode);
        if (index >= 0) {
#if DEBUG_MAPPING
    ALOGD("mapKey: scanCode=%d, usageCode=0x%08x ~ Result keyCode=%d.",
            scanCode, usageCode, *outKeyCode);
#endif
            *outKeyCode = mKeysByScanCode.valueAt(index);
            return OK;
        }
    }

#if DEBUG_MAPPING
        ALOGD("mapKey: scanCode=%d, usageCode=0x%08x ~ Failed.", scanCode, usageCode);
#endif
    *outKeyCode = AKEYCODE_UNKNOWN;
    return NAME_NOT_FOUND;
}

bool KeyCharacterMap::getKey(int32_t keyCode, const Key** outKey) const {
    ssize_t index = mKeys.indexOfKey(keyCode);
    if (index >= 0) {
        *outKey = mKeys.valueAt(index);
        return true;
    }
    return false;
}

bool KeyCharacterMap::getKeyBehavior(int32_t keyCode, int32_t metaState,
        const Key** outKey, const Behavior** outBehavior) const {
    const Key* key;
    if (getKey(keyCode, &key)) {
        const Behavior* behavior = key->firstBehavior;
        while (behavior) {
            if (matchesMetaState(metaState, behavior->metaState)) {
                *outKey = key;
                *outBehavior = behavior;
                return true;
            }
            behavior = behavior->next;
        }
    }
    return false;
}

bool KeyCharacterMap::matchesMetaState(int32_t eventMetaState, int32_t behaviorMetaState) {
    // Behavior must have at least the set of meta states specified.
    // And if the key event has CTRL, ALT or META then the behavior must exactly
    // match those, taking into account that a behavior can specify that it handles
    // one, both or either of a left/right modifier pair.
    if ((eventMetaState & behaviorMetaState) == behaviorMetaState) {
        const int32_t EXACT_META_STATES =
                AMETA_CTRL_ON | AMETA_CTRL_LEFT_ON | AMETA_CTRL_RIGHT_ON
                | AMETA_ALT_ON | AMETA_ALT_LEFT_ON | AMETA_ALT_RIGHT_ON
                | AMETA_META_ON | AMETA_META_LEFT_ON | AMETA_META_RIGHT_ON;
        int32_t unmatchedMetaState = eventMetaState & ~behaviorMetaState & EXACT_META_STATES;
        if (behaviorMetaState & AMETA_CTRL_ON) {
            unmatchedMetaState &= ~(AMETA_CTRL_LEFT_ON | AMETA_CTRL_RIGHT_ON);
        } else if (behaviorMetaState & (AMETA_CTRL_LEFT_ON | AMETA_CTRL_RIGHT_ON)) {
            unmatchedMetaState &= ~AMETA_CTRL_ON;
        }
        if (behaviorMetaState & AMETA_ALT_ON) {
            unmatchedMetaState &= ~(AMETA_ALT_LEFT_ON | AMETA_ALT_RIGHT_ON);
        } else if (behaviorMetaState & (AMETA_ALT_LEFT_ON | AMETA_ALT_RIGHT_ON)) {
            unmatchedMetaState &= ~AMETA_ALT_ON;
        }
        if (behaviorMetaState & AMETA_META_ON) {
            unmatchedMetaState &= ~(AMETA_META_LEFT_ON | AMETA_META_RIGHT_ON);
        } else if (behaviorMetaState & (AMETA_META_LEFT_ON | AMETA_META_RIGHT_ON)) {
            unmatchedMetaState &= ~AMETA_META_ON;
        }
        return !unmatchedMetaState;
    }
    return false;
}

bool KeyCharacterMap::findKey(char16_t ch, int32_t* outKeyCode, int32_t* outMetaState) const {
    if (!ch) {
        return false;
    }

    for (size_t i = 0; i < mKeys.size(); i++) {
        const Key* key = mKeys.valueAt(i);

        // Try to find the most general behavior that maps to this character.
        // For example, the base key behavior will usually be last in the list.
        const Behavior* found = NULL;
        for (const Behavior* behavior = key->firstBehavior; behavior; behavior = behavior->next) {
            if (behavior->character == ch) {
                found = behavior;
            }
        }
        if (found) {
            *outKeyCode = mKeys.keyAt(i);
            *outMetaState = found->metaState;
            return true;
        }
    }
    return false;
}

void KeyCharacterMap::addKey(Vector<KeyEvent>& outEvents,
        int32_t deviceId, int32_t keyCode, int32_t metaState, bool down, nsecs_t time) {
    outEvents.push();
    KeyEvent& event = outEvents.editTop();
    event.initialize(deviceId, AINPUT_SOURCE_KEYBOARD,
            down ? AKEY_EVENT_ACTION_DOWN : AKEY_EVENT_ACTION_UP,
            0, keyCode, 0, metaState, 0, time, time);
}

void KeyCharacterMap::addMetaKeys(Vector<KeyEvent>& outEvents,
        int32_t deviceId, int32_t metaState, bool down, nsecs_t time,
        int32_t* currentMetaState) {
    // Add and remove meta keys symmetrically.
    if (down) {
        addLockedMetaKey(outEvents, deviceId, metaState, time,
                AKEYCODE_CAPS_LOCK, AMETA_CAPS_LOCK_ON, currentMetaState);
        addLockedMetaKey(outEvents, deviceId, metaState, time,
                AKEYCODE_NUM_LOCK, AMETA_NUM_LOCK_ON, currentMetaState);
        addLockedMetaKey(outEvents, deviceId, metaState, time,
                AKEYCODE_SCROLL_LOCK, AMETA_SCROLL_LOCK_ON, currentMetaState);

        addDoubleEphemeralMetaKey(outEvents, deviceId, metaState, true, time,
                AKEYCODE_SHIFT_LEFT, AMETA_SHIFT_LEFT_ON,
                AKEYCODE_SHIFT_RIGHT, AMETA_SHIFT_RIGHT_ON,
                AMETA_SHIFT_ON, currentMetaState);
        addDoubleEphemeralMetaKey(outEvents, deviceId, metaState, true, time,
                AKEYCODE_ALT_LEFT, AMETA_ALT_LEFT_ON,
                AKEYCODE_ALT_RIGHT, AMETA_ALT_RIGHT_ON,
                AMETA_ALT_ON, currentMetaState);
        addDoubleEphemeralMetaKey(outEvents, deviceId, metaState, true, time,
                AKEYCODE_CTRL_LEFT, AMETA_CTRL_LEFT_ON,
                AKEYCODE_CTRL_RIGHT, AMETA_CTRL_RIGHT_ON,
                AMETA_CTRL_ON, currentMetaState);
        addDoubleEphemeralMetaKey(outEvents, deviceId, metaState, true, time,
                AKEYCODE_META_LEFT, AMETA_META_LEFT_ON,
                AKEYCODE_META_RIGHT, AMETA_META_RIGHT_ON,
                AMETA_META_ON, currentMetaState);

        addSingleEphemeralMetaKey(outEvents, deviceId, metaState, true, time,
                AKEYCODE_SYM, AMETA_SYM_ON, currentMetaState);
        addSingleEphemeralMetaKey(outEvents, deviceId, metaState, true, time,
                AKEYCODE_FUNCTION, AMETA_FUNCTION_ON, currentMetaState);
    } else {
        addSingleEphemeralMetaKey(outEvents, deviceId, metaState, false, time,
                AKEYCODE_FUNCTION, AMETA_FUNCTION_ON, currentMetaState);
        addSingleEphemeralMetaKey(outEvents, deviceId, metaState, false, time,
                AKEYCODE_SYM, AMETA_SYM_ON, currentMetaState);

        addDoubleEphemeralMetaKey(outEvents, deviceId, metaState, false, time,
                AKEYCODE_META_LEFT, AMETA_META_LEFT_ON,
                AKEYCODE_META_RIGHT, AMETA_META_RIGHT_ON,
                AMETA_META_ON, currentMetaState);
        addDoubleEphemeralMetaKey(outEvents, deviceId, metaState, false, time,
                AKEYCODE_CTRL_LEFT, AMETA_CTRL_LEFT_ON,
                AKEYCODE_CTRL_RIGHT, AMETA_CTRL_RIGHT_ON,
                AMETA_CTRL_ON, currentMetaState);
        addDoubleEphemeralMetaKey(outEvents, deviceId, metaState, false, time,
                AKEYCODE_ALT_LEFT, AMETA_ALT_LEFT_ON,
                AKEYCODE_ALT_RIGHT, AMETA_ALT_RIGHT_ON,
                AMETA_ALT_ON, currentMetaState);
        addDoubleEphemeralMetaKey(outEvents, deviceId, metaState, false, time,
                AKEYCODE_SHIFT_LEFT, AMETA_SHIFT_LEFT_ON,
                AKEYCODE_SHIFT_RIGHT, AMETA_SHIFT_RIGHT_ON,
                AMETA_SHIFT_ON, currentMetaState);

        addLockedMetaKey(outEvents, deviceId, metaState, time,
                AKEYCODE_SCROLL_LOCK, AMETA_SCROLL_LOCK_ON, currentMetaState);
        addLockedMetaKey(outEvents, deviceId, metaState, time,
                AKEYCODE_NUM_LOCK, AMETA_NUM_LOCK_ON, currentMetaState);
        addLockedMetaKey(outEvents, deviceId, metaState, time,
                AKEYCODE_CAPS_LOCK, AMETA_CAPS_LOCK_ON, currentMetaState);
    }
}

bool KeyCharacterMap::addSingleEphemeralMetaKey(Vector<KeyEvent>& outEvents,
        int32_t deviceId, int32_t metaState, bool down, nsecs_t time,
        int32_t keyCode, int32_t keyMetaState,
        int32_t* currentMetaState) {
    if ((metaState & keyMetaState) == keyMetaState) {
        *currentMetaState = updateMetaState(keyCode, down, *currentMetaState);
        addKey(outEvents, deviceId, keyCode, *currentMetaState, down, time);
        return true;
    }
    return false;
}

void KeyCharacterMap::addDoubleEphemeralMetaKey(Vector<KeyEvent>& outEvents,
        int32_t deviceId, int32_t metaState, bool down, nsecs_t time,
        int32_t leftKeyCode, int32_t leftKeyMetaState,
        int32_t rightKeyCode, int32_t rightKeyMetaState,
        int32_t eitherKeyMetaState,
        int32_t* currentMetaState) {
    bool specific = false;
    specific |= addSingleEphemeralMetaKey(outEvents, deviceId, metaState, down, time,
            leftKeyCode, leftKeyMetaState, currentMetaState);
    specific |= addSingleEphemeralMetaKey(outEvents, deviceId, metaState, down, time,
            rightKeyCode, rightKeyMetaState, currentMetaState);

    if (!specific) {
        addSingleEphemeralMetaKey(outEvents, deviceId, metaState, down, time,
                leftKeyCode, eitherKeyMetaState, currentMetaState);
    }
}

void KeyCharacterMap::addLockedMetaKey(Vector<KeyEvent>& outEvents,
        int32_t deviceId, int32_t metaState, nsecs_t time,
        int32_t keyCode, int32_t keyMetaState,
        int32_t* currentMetaState) {
    if ((metaState & keyMetaState) == keyMetaState) {
        *currentMetaState = updateMetaState(keyCode, true, *currentMetaState);
        addKey(outEvents, deviceId, keyCode, *currentMetaState, true, time);
        *currentMetaState = updateMetaState(keyCode, false, *currentMetaState);
        addKey(outEvents, deviceId, keyCode, *currentMetaState, false, time);
    }
}

#if HAVE_ANDROID_OS
sp<KeyCharacterMap> KeyCharacterMap::readFromParcel(Parcel* parcel) {
    sp<KeyCharacterMap> map = new KeyCharacterMap();
    map->mType = parcel->readInt32();
    size_t numKeys = parcel->readInt32();
    if (parcel->errorCheck()) {
        return NULL;
    }

    for (size_t i = 0; i < numKeys; i++) {
        int32_t keyCode = parcel->readInt32();
        char16_t label = parcel->readInt32();
        char16_t number = parcel->readInt32();
        if (parcel->errorCheck()) {
            return NULL;
        }

        Key* key = new Key();
        key->label = label;
        key->number = number;
        map->mKeys.add(keyCode, key);

        Behavior* lastBehavior = NULL;
        while (parcel->readInt32()) {
            int32_t metaState = parcel->readInt32();
            char16_t character = parcel->readInt32();
            int32_t fallbackKeyCode = parcel->readInt32();
            if (parcel->errorCheck()) {
                return NULL;
            }

            Behavior* behavior = new Behavior();
            behavior->metaState = metaState;
            behavior->character = character;
            behavior->fallbackKeyCode = fallbackKeyCode;
            if (lastBehavior) {
                lastBehavior->next = behavior;
            } else {
                key->firstBehavior = behavior;
            }
            lastBehavior = behavior;
        }

        if (parcel->errorCheck()) {
            return NULL;
        }
    }
    return map;
}

void KeyCharacterMap::writeToParcel(Parcel* parcel) const {
    parcel->writeInt32(mType);

    size_t numKeys = mKeys.size();
    parcel->writeInt32(numKeys);
    for (size_t i = 0; i < numKeys; i++) {
        int32_t keyCode = mKeys.keyAt(i);
        const Key* key = mKeys.valueAt(i);
        parcel->writeInt32(keyCode);
        parcel->writeInt32(key->label);
        parcel->writeInt32(key->number);
        for (const Behavior* behavior = key->firstBehavior; behavior != NULL;
                behavior = behavior->next) {
            parcel->writeInt32(1);
            parcel->writeInt32(behavior->metaState);
            parcel->writeInt32(behavior->character);
            parcel->writeInt32(behavior->fallbackKeyCode);
        }
        parcel->writeInt32(0);
    }
}
#endif


// --- KeyCharacterMap::Key ---

KeyCharacterMap::Key::Key() :
        label(0), number(0), firstBehavior(NULL) {
}

KeyCharacterMap::Key::Key(const Key& other) :
        label(other.label), number(other.number),
        firstBehavior(other.firstBehavior ? new Behavior(*other.firstBehavior) : NULL) {
}

KeyCharacterMap::Key::~Key() {
    Behavior* behavior = firstBehavior;
    while (behavior) {
        Behavior* next = behavior->next;
        delete behavior;
        behavior = next;
    }
}


// --- KeyCharacterMap::Behavior ---

KeyCharacterMap::Behavior::Behavior() :
        next(NULL), metaState(0), character(0), fallbackKeyCode(0) {
}

KeyCharacterMap::Behavior::Behavior(const Behavior& other) :
        next(other.next ? new Behavior(*other.next) : NULL),
        metaState(other.metaState), character(other.character),
        fallbackKeyCode(other.fallbackKeyCode) {
}


// --- KeyCharacterMap::Parser ---

KeyCharacterMap::Parser::Parser(KeyCharacterMap* map, Tokenizer* tokenizer, Format format) :
        mMap(map), mTokenizer(tokenizer), mFormat(format), mState(STATE_TOP) {
}

KeyCharacterMap::Parser::~Parser() {
}

status_t KeyCharacterMap::Parser::parse() {
    while (!mTokenizer->isEof()) {
#if DEBUG_PARSER
        ALOGD("Parsing %s: '%s'.", mTokenizer->getLocation().string(),
                mTokenizer->peekRemainderOfLine().string());
#endif

        mTokenizer->skipDelimiters(WHITESPACE);

        if (!mTokenizer->isEol() && mTokenizer->peekChar() != '#') {
            switch (mState) {
            case STATE_TOP: {
                String8 keywordToken = mTokenizer->nextToken(WHITESPACE);
                if (keywordToken == "type") {
                    mTokenizer->skipDelimiters(WHITESPACE);
                    status_t status = parseType();
                    if (status) return status;
                } else if (keywordToken == "map") {
                    mTokenizer->skipDelimiters(WHITESPACE);
                    status_t status = parseMap();
                    if (status) return status;
                } else if (keywordToken == "key") {
                    mTokenizer->skipDelimiters(WHITESPACE);
                    status_t status = parseKey();
                    if (status) return status;
                } else {
                    ALOGE("%s: Expected keyword, got '%s'.", mTokenizer->getLocation().string(),
                            keywordToken.string());
                    return BAD_VALUE;
                }
                break;
            }

            case STATE_KEY: {
                status_t status = parseKeyProperty();
                if (status) return status;
                break;
            }
            }

            mTokenizer->skipDelimiters(WHITESPACE);
            if (!mTokenizer->isEol() && mTokenizer->peekChar() != '#') {
                ALOGE("%s: Expected end of line or trailing comment, got '%s'.",
                        mTokenizer->getLocation().string(),
                        mTokenizer->peekRemainderOfLine().string());
                return BAD_VALUE;
            }
        }

        mTokenizer->nextLine();
    }

    if (mState != STATE_TOP) {
        ALOGE("%s: Unterminated key description at end of file.",
                mTokenizer->getLocation().string());
        return BAD_VALUE;
    }

    if (mMap->mType == KEYBOARD_TYPE_UNKNOWN) {
        ALOGE("%s: Keyboard layout missing required keyboard 'type' declaration.",
                mTokenizer->getLocation().string());
        return BAD_VALUE;
    }

    if (mFormat == FORMAT_BASE) {
        if (mMap->mType == KEYBOARD_TYPE_OVERLAY) {
            ALOGE("%s: Base keyboard layout must specify a keyboard 'type' other than 'OVERLAY'.",
                    mTokenizer->getLocation().string());
            return BAD_VALUE;
        }
    } else if (mFormat == FORMAT_OVERLAY) {
        if (mMap->mType != KEYBOARD_TYPE_OVERLAY) {
            ALOGE("%s: Overlay keyboard layout missing required keyboard "
                    "'type OVERLAY' declaration.",
                    mTokenizer->getLocation().string());
            return BAD_VALUE;
        }
    }

    return NO_ERROR;
}

status_t KeyCharacterMap::Parser::parseType() {
    if (mMap->mType != KEYBOARD_TYPE_UNKNOWN) {
        ALOGE("%s: Duplicate keyboard 'type' declaration.",
                mTokenizer->getLocation().string());
        return BAD_VALUE;
    }

    KeyboardType type;
    String8 typeToken = mTokenizer->nextToken(WHITESPACE);
    if (typeToken == "NUMERIC") {
        type = KEYBOARD_TYPE_NUMERIC;
    } else if (typeToken == "PREDICTIVE") {
        type = KEYBOARD_TYPE_PREDICTIVE;
    } else if (typeToken == "ALPHA") {
        type = KEYBOARD_TYPE_ALPHA;
    } else if (typeToken == "FULL") {
        type = KEYBOARD_TYPE_FULL;
    } else if (typeToken == "SPECIAL_FUNCTION") {
        type = KEYBOARD_TYPE_SPECIAL_FUNCTION;
    } else if (typeToken == "OVERLAY") {
        type = KEYBOARD_TYPE_OVERLAY;
    } else {
        ALOGE("%s: Expected keyboard type label, got '%s'.", mTokenizer->getLocation().string(),
                typeToken.string());
        return BAD_VALUE;
    }

#if DEBUG_PARSER
    ALOGD("Parsed type: type=%d.", type);
#endif
    mMap->mType = type;
    return NO_ERROR;
}

status_t KeyCharacterMap::Parser::parseMap() {
    String8 keywordToken = mTokenizer->nextToken(WHITESPACE);
    if (keywordToken == "key") {
        mTokenizer->skipDelimiters(WHITESPACE);
        return parseMapKey();
    }
    ALOGE("%s: Expected keyword after 'map', got '%s'.", mTokenizer->getLocation().string(),
            keywordToken.string());
    return BAD_VALUE;
}

status_t KeyCharacterMap::Parser::parseMapKey() {
    String8 codeToken = mTokenizer->nextToken(WHITESPACE);
    bool mapUsage = false;
    if (codeToken == "usage") {
        mapUsage = true;
        mTokenizer->skipDelimiters(WHITESPACE);
        codeToken = mTokenizer->nextToken(WHITESPACE);
    }

    char* end;
    int32_t code = int32_t(strtol(codeToken.string(), &end, 0));
    if (*end) {
        ALOGE("%s: Expected key %s number, got '%s'.", mTokenizer->getLocation().string(),
                mapUsage ? "usage" : "scan code", codeToken.string());
        return BAD_VALUE;
    }
    KeyedVector<int32_t, int32_t>& map =
            mapUsage ? mMap->mKeysByUsageCode : mMap->mKeysByScanCode;
    if (map.indexOfKey(code) >= 0) {
        ALOGE("%s: Duplicate entry for key %s '%s'.", mTokenizer->getLocation().string(),
                mapUsage ? "usage" : "scan code", codeToken.string());
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

#if DEBUG_PARSER
    ALOGD("Parsed map key %s: code=%d, keyCode=%d.",
            mapUsage ? "usage" : "scan code", code, keyCode);
#endif
    map.add(code, keyCode);
    return NO_ERROR;
}

status_t KeyCharacterMap::Parser::parseKey() {
    String8 keyCodeToken = mTokenizer->nextToken(WHITESPACE);
    int32_t keyCode = getKeyCodeByLabel(keyCodeToken.string());
    if (!keyCode) {
        ALOGE("%s: Expected key code label, got '%s'.", mTokenizer->getLocation().string(),
                keyCodeToken.string());
        return BAD_VALUE;
    }
    if (mMap->mKeys.indexOfKey(keyCode) >= 0) {
        ALOGE("%s: Duplicate entry for key code '%s'.", mTokenizer->getLocation().string(),
                keyCodeToken.string());
        return BAD_VALUE;
    }

    mTokenizer->skipDelimiters(WHITESPACE);
    String8 openBraceToken = mTokenizer->nextToken(WHITESPACE);
    if (openBraceToken != "{") {
        ALOGE("%s: Expected '{' after key code label, got '%s'.",
                mTokenizer->getLocation().string(), openBraceToken.string());
        return BAD_VALUE;
    }

#if DEBUG_PARSER
    ALOGD("Parsed beginning of key: keyCode=%d.", keyCode);
#endif
    mKeyCode = keyCode;
    mMap->mKeys.add(keyCode, new Key());
    mState = STATE_KEY;
    return NO_ERROR;
}

status_t KeyCharacterMap::Parser::parseKeyProperty() {
    Key* key = mMap->mKeys.valueFor(mKeyCode);
    String8 token = mTokenizer->nextToken(WHITESPACE_OR_PROPERTY_DELIMITER);
    if (token == "}") {
        mState = STATE_TOP;
        return finishKey(key);
    }

    Vector<Property> properties;

    // Parse all comma-delimited property names up to the first colon.
    for (;;) {
        if (token == "label") {
            properties.add(Property(PROPERTY_LABEL));
        } else if (token == "number") {
            properties.add(Property(PROPERTY_NUMBER));
        } else {
            int32_t metaState;
            status_t status = parseModifier(token, &metaState);
            if (status) {
                ALOGE("%s: Expected a property name or modifier, got '%s'.",
                        mTokenizer->getLocation().string(), token.string());
                return status;
            }
            properties.add(Property(PROPERTY_META, metaState));
        }

        mTokenizer->skipDelimiters(WHITESPACE);
        if (!mTokenizer->isEol()) {
            char ch = mTokenizer->nextChar();
            if (ch == ':') {
                break;
            } else if (ch == ',') {
                mTokenizer->skipDelimiters(WHITESPACE);
                token = mTokenizer->nextToken(WHITESPACE_OR_PROPERTY_DELIMITER);
                continue;
            }
        }

        ALOGE("%s: Expected ',' or ':' after property name.",
                mTokenizer->getLocation().string());
        return BAD_VALUE;
    }

    // Parse behavior after the colon.
    mTokenizer->skipDelimiters(WHITESPACE);

    Behavior behavior;
    bool haveCharacter = false;
    bool haveFallback = false;

    do {
        char ch = mTokenizer->peekChar();
        if (ch == '\'') {
            char16_t character;
            status_t status = parseCharacterLiteral(&character);
            if (status || !character) {
                ALOGE("%s: Invalid character literal for key.",
                        mTokenizer->getLocation().string());
                return BAD_VALUE;
            }
            if (haveCharacter) {
                ALOGE("%s: Cannot combine multiple character literals or 'none'.",
                        mTokenizer->getLocation().string());
                return BAD_VALUE;
            }
            behavior.character = character;
            haveCharacter = true;
        } else {
            token = mTokenizer->nextToken(WHITESPACE);
            if (token == "none") {
                if (haveCharacter) {
                    ALOGE("%s: Cannot combine multiple character literals or 'none'.",
                            mTokenizer->getLocation().string());
                    return BAD_VALUE;
                }
                haveCharacter = true;
            } else if (token == "fallback") {
                mTokenizer->skipDelimiters(WHITESPACE);
                token = mTokenizer->nextToken(WHITESPACE);
                int32_t keyCode = getKeyCodeByLabel(token.string());
                if (!keyCode) {
                    ALOGE("%s: Invalid key code label for fallback behavior, got '%s'.",
                            mTokenizer->getLocation().string(),
                            token.string());
                    return BAD_VALUE;
                }
                if (haveFallback) {
                    ALOGE("%s: Cannot combine multiple fallback key codes.",
                            mTokenizer->getLocation().string());
                    return BAD_VALUE;
                }
                behavior.fallbackKeyCode = keyCode;
                haveFallback = true;
            } else {
                ALOGE("%s: Expected a key behavior after ':'.",
                        mTokenizer->getLocation().string());
                return BAD_VALUE;
            }
        }

        mTokenizer->skipDelimiters(WHITESPACE);
    } while (!mTokenizer->isEol() && mTokenizer->peekChar() != '#');

    // Add the behavior.
    for (size_t i = 0; i < properties.size(); i++) {
        const Property& property = properties.itemAt(i);
        switch (property.property) {
        case PROPERTY_LABEL:
            if (key->label) {
                ALOGE("%s: Duplicate label for key.",
                        mTokenizer->getLocation().string());
                return BAD_VALUE;
            }
            key->label = behavior.character;
#if DEBUG_PARSER
            ALOGD("Parsed key label: keyCode=%d, label=%d.", mKeyCode, key->label);
#endif
            break;
        case PROPERTY_NUMBER:
            if (key->number) {
                ALOGE("%s: Duplicate number for key.",
                        mTokenizer->getLocation().string());
                return BAD_VALUE;
            }
            key->number = behavior.character;
#if DEBUG_PARSER
            ALOGD("Parsed key number: keyCode=%d, number=%d.", mKeyCode, key->number);
#endif
            break;
        case PROPERTY_META: {
            for (Behavior* b = key->firstBehavior; b; b = b->next) {
                if (b->metaState == property.metaState) {
                    ALOGE("%s: Duplicate key behavior for modifier.",
                            mTokenizer->getLocation().string());
                    return BAD_VALUE;
                }
            }
            Behavior* newBehavior = new Behavior(behavior);
            newBehavior->metaState = property.metaState;
            newBehavior->next = key->firstBehavior;
            key->firstBehavior = newBehavior;
#if DEBUG_PARSER
            ALOGD("Parsed key meta: keyCode=%d, meta=0x%x, char=%d, fallback=%d.", mKeyCode,
                    newBehavior->metaState, newBehavior->character, newBehavior->fallbackKeyCode);
#endif
            break;
        }
        }
    }
    return NO_ERROR;
}

status_t KeyCharacterMap::Parser::finishKey(Key* key) {
    // Fill in default number property.
    if (!key->number) {
        char16_t digit = 0;
        char16_t symbol = 0;
        for (Behavior* b = key->firstBehavior; b; b = b->next) {
            char16_t ch = b->character;
            if (ch) {
                if (ch >= '0' && ch <= '9') {
                    digit = ch;
                } else if (ch == '(' || ch == ')' || ch == '#' || ch == '*'
                        || ch == '-' || ch == '+' || ch == ',' || ch == '.'
                        || ch == '\'' || ch == ':' || ch == ';' || ch == '/') {
                    symbol = ch;
                }
            }
        }
        key->number = digit ? digit : symbol;
    }
    return NO_ERROR;
}

status_t KeyCharacterMap::Parser::parseModifier(const String8& token, int32_t* outMetaState) {
    if (token == "base") {
        *outMetaState = 0;
        return NO_ERROR;
    }

    int32_t combinedMeta = 0;

    const char* str = token.string();
    const char* start = str;
    for (const char* cur = str; ; cur++) {
        char ch = *cur;
        if (ch == '+' || ch == '\0') {
            size_t len = cur - start;
            int32_t metaState = 0;
            for (size_t i = 0; i < sizeof(modifiers) / sizeof(Modifier); i++) {
                if (strlen(modifiers[i].label) == len
                        && strncmp(modifiers[i].label, start, len) == 0) {
                    metaState = modifiers[i].metaState;
                    break;
                }
            }
            if (!metaState) {
                return BAD_VALUE;
            }
            if (combinedMeta & metaState) {
                ALOGE("%s: Duplicate modifier combination '%s'.",
                        mTokenizer->getLocation().string(), token.string());
                return BAD_VALUE;
            }

            combinedMeta |= metaState;
            start = cur + 1;

            if (ch == '\0') {
                break;
            }
        }
    }
    *outMetaState = combinedMeta;
    return NO_ERROR;
}

status_t KeyCharacterMap::Parser::parseCharacterLiteral(char16_t* outCharacter) {
    char ch = mTokenizer->nextChar();
    if (ch != '\'') {
        goto Error;
    }

    ch = mTokenizer->nextChar();
    if (ch == '\\') {
        // Escape sequence.
        ch = mTokenizer->nextChar();
        if (ch == 'n') {
            *outCharacter = '\n';
        } else if (ch == 't') {
            *outCharacter = '\t';
        } else if (ch == '\\') {
            *outCharacter = '\\';
        } else if (ch == '\'') {
            *outCharacter = '\'';
        } else if (ch == '"') {
            *outCharacter = '"';
        } else if (ch == 'u') {
            *outCharacter = 0;
            for (int i = 0; i < 4; i++) {
                ch = mTokenizer->nextChar();
                int digit;
                if (ch >= '0' && ch <= '9') {
                    digit = ch - '0';
                } else if (ch >= 'A' && ch <= 'F') {
                    digit = ch - 'A' + 10;
                } else if (ch >= 'a' && ch <= 'f') {
                    digit = ch - 'a' + 10;
                } else {
                    goto Error;
                }
                *outCharacter = (*outCharacter << 4) | digit;
            }
        } else {
            goto Error;
        }
    } else if (ch >= 32 && ch <= 126 && ch != '\'') {
        // ASCII literal character.
        *outCharacter = ch;
    } else {
        goto Error;
    }

    ch = mTokenizer->nextChar();
    if (ch != '\'') {
        goto Error;
    }

    // Ensure that we consumed the entire token.
    if (mTokenizer->nextToken(WHITESPACE).isEmpty()) {
        return NO_ERROR;
    }

Error:
    ALOGE("%s: Malformed character literal.", mTokenizer->getLocation().string());
    return BAD_VALUE;
}

} // namespace android
