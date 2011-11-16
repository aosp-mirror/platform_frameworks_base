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
#include <ui/Keyboard.h>
#include <ui/KeyCharacterMap.h>
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

KeyCharacterMap::KeyCharacterMap() :
    mType(KEYBOARD_TYPE_UNKNOWN) {
}

KeyCharacterMap::~KeyCharacterMap() {
    for (size_t i = 0; i < mKeys.size(); i++) {
        Key* key = mKeys.editValueAt(i);
        delete key;
    }
}

status_t KeyCharacterMap::load(const String8& filename, KeyCharacterMap** outMap) {
    *outMap = NULL;

    Tokenizer* tokenizer;
    status_t status = Tokenizer::open(filename, &tokenizer);
    if (status) {
        LOGE("Error %d opening key character map file %s.", status, filename.string());
    } else {
        KeyCharacterMap* map = new KeyCharacterMap();
        if (!map) {
            LOGE("Error allocating key character map.");
            status = NO_MEMORY;
        } else {
#if DEBUG_PARSER_PERFORMANCE
            nsecs_t startTime = systemTime(SYSTEM_TIME_MONOTONIC);
#endif
            Parser parser(map, tokenizer);
            status = parser.parse();
#if DEBUG_PARSER_PERFORMANCE
            nsecs_t elapsedTime = systemTime(SYSTEM_TIME_MONOTONIC) - startTime;
            LOGD("Parsed key character map file '%s' %d lines in %0.3fms.",
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
    LOGD("getDisplayLabel: keyCode=%d ~ Result %d.", keyCode, result);
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
    LOGD("getNumber: keyCode=%d ~ Result %d.", keyCode, result);
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
    LOGD("getCharacter: keyCode=%d, metaState=0x%08x ~ Result %d.", keyCode, metaState, result);
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
    LOGD("getFallbackKeyCode: keyCode=%d, metaState=0x%08x ~ Result %s, "
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
    LOGD("getMatch: keyCode=%d, chars=[%s], metaState=0x%08x ~ Result %d.",
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
            LOGD("getEvents: deviceId=%d, chars=[%s] ~ Failed to find mapping for character %d.",
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
    LOGD("getEvents: deviceId=%d, chars=[%s] ~ Generated %d events.",
            deviceId, toString(chars, numChars).string(), int32_t(outEvents.size()));
    for (size_t i = 0; i < outEvents.size(); i++) {
        LOGD("  Key: keyCode=%d, metaState=0x%08x, %s.",
                outEvents[i].getKeyCode(), outEvents[i].getMetaState(),
                outEvents[i].getAction() == AKEY_EVENT_ACTION_DOWN ? "down" : "up");
    }
#endif
    return true;
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
            if ((behavior->metaState & metaState) == behavior->metaState) {
                *outKey = key;
                *outBehavior = behavior;
                return true;
            }
            behavior = behavior->next;
        }
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


// --- KeyCharacterMap::Key ---

KeyCharacterMap::Key::Key() :
        label(0), number(0), firstBehavior(NULL) {
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


// --- KeyCharacterMap::Parser ---

KeyCharacterMap::Parser::Parser(KeyCharacterMap* map, Tokenizer* tokenizer) :
        mMap(map), mTokenizer(tokenizer), mState(STATE_TOP) {
}

KeyCharacterMap::Parser::~Parser() {
}

status_t KeyCharacterMap::Parser::parse() {
    while (!mTokenizer->isEof()) {
#if DEBUG_PARSER
        LOGD("Parsing %s: '%s'.", mTokenizer->getLocation().string(),
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
                } else if (keywordToken == "key") {
                    mTokenizer->skipDelimiters(WHITESPACE);
                    status_t status = parseKey();
                    if (status) return status;
                } else {
                    LOGE("%s: Expected keyword, got '%s'.", mTokenizer->getLocation().string(),
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
            if (!mTokenizer->isEol()) {
                LOGE("%s: Expected end of line, got '%s'.",
                        mTokenizer->getLocation().string(),
                        mTokenizer->peekRemainderOfLine().string());
                return BAD_VALUE;
            }
        }

        mTokenizer->nextLine();
    }

    if (mState != STATE_TOP) {
        LOGE("%s: Unterminated key description at end of file.",
                mTokenizer->getLocation().string());
        return BAD_VALUE;
    }

    if (mMap->mType == KEYBOARD_TYPE_UNKNOWN) {
        LOGE("%s: Missing required keyboard 'type' declaration.",
                mTokenizer->getLocation().string());
        return BAD_VALUE;
    }

    return NO_ERROR;
}

status_t KeyCharacterMap::Parser::parseType() {
    if (mMap->mType != KEYBOARD_TYPE_UNKNOWN) {
        LOGE("%s: Duplicate keyboard 'type' declaration.",
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
    } else {
        LOGE("%s: Expected keyboard type label, got '%s'.", mTokenizer->getLocation().string(),
                typeToken.string());
        return BAD_VALUE;
    }

#if DEBUG_PARSER
    LOGD("Parsed type: type=%d.", type);
#endif
    mMap->mType = type;
    return NO_ERROR;
}

status_t KeyCharacterMap::Parser::parseKey() {
    String8 keyCodeToken = mTokenizer->nextToken(WHITESPACE);
    int32_t keyCode = getKeyCodeByLabel(keyCodeToken.string());
    if (!keyCode) {
        LOGE("%s: Expected key code label, got '%s'.", mTokenizer->getLocation().string(),
                keyCodeToken.string());
        return BAD_VALUE;
    }
    if (mMap->mKeys.indexOfKey(keyCode) >= 0) {
        LOGE("%s: Duplicate entry for key code '%s'.", mTokenizer->getLocation().string(),
                keyCodeToken.string());
        return BAD_VALUE;
    }

    mTokenizer->skipDelimiters(WHITESPACE);
    String8 openBraceToken = mTokenizer->nextToken(WHITESPACE);
    if (openBraceToken != "{") {
        LOGE("%s: Expected '{' after key code label, got '%s'.",
                mTokenizer->getLocation().string(), openBraceToken.string());
        return BAD_VALUE;
    }

#if DEBUG_PARSER
    LOGD("Parsed beginning of key: keyCode=%d.", keyCode);
#endif
    mKeyCode = keyCode;
    mMap->mKeys.add(keyCode, new Key());
    mState = STATE_KEY;
    return NO_ERROR;
}

status_t KeyCharacterMap::Parser::parseKeyProperty() {
    String8 token = mTokenizer->nextToken(WHITESPACE_OR_PROPERTY_DELIMITER);
    if (token == "}") {
        mState = STATE_TOP;
        return NO_ERROR;
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
                LOGE("%s: Expected a property name or modifier, got '%s'.",
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

        LOGE("%s: Expected ',' or ':' after property name.",
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
                LOGE("%s: Invalid character literal for key.",
                        mTokenizer->getLocation().string());
                return BAD_VALUE;
            }
            if (haveCharacter) {
                LOGE("%s: Cannot combine multiple character literals or 'none'.",
                        mTokenizer->getLocation().string());
                return BAD_VALUE;
            }
            behavior.character = character;
            haveCharacter = true;
        } else {
            token = mTokenizer->nextToken(WHITESPACE);
            if (token == "none") {
                if (haveCharacter) {
                    LOGE("%s: Cannot combine multiple character literals or 'none'.",
                            mTokenizer->getLocation().string());
                    return BAD_VALUE;
                }
                haveCharacter = true;
            } else if (token == "fallback") {
                mTokenizer->skipDelimiters(WHITESPACE);
                token = mTokenizer->nextToken(WHITESPACE);
                int32_t keyCode = getKeyCodeByLabel(token.string());
                if (!keyCode) {
                    LOGE("%s: Invalid key code label for fallback behavior, got '%s'.",
                            mTokenizer->getLocation().string(),
                            token.string());
                    return BAD_VALUE;
                }
                if (haveFallback) {
                    LOGE("%s: Cannot combine multiple fallback key codes.",
                            mTokenizer->getLocation().string());
                    return BAD_VALUE;
                }
                behavior.fallbackKeyCode = keyCode;
                haveFallback = true;
            } else {
                LOGE("%s: Expected a key behavior after ':'.",
                        mTokenizer->getLocation().string());
                return BAD_VALUE;
            }
        }

        mTokenizer->skipDelimiters(WHITESPACE);
    } while (!mTokenizer->isEol());

    // Add the behavior.
    Key* key = mMap->mKeys.valueFor(mKeyCode);
    for (size_t i = 0; i < properties.size(); i++) {
        const Property& property = properties.itemAt(i);
        switch (property.property) {
        case PROPERTY_LABEL:
            if (key->label) {
                LOGE("%s: Duplicate label for key.",
                        mTokenizer->getLocation().string());
                return BAD_VALUE;
            }
            key->label = behavior.character;
#if DEBUG_PARSER
            LOGD("Parsed key label: keyCode=%d, label=%d.", mKeyCode, key->label);
#endif
            break;
        case PROPERTY_NUMBER:
            if (key->number) {
                LOGE("%s: Duplicate number for key.",
                        mTokenizer->getLocation().string());
                return BAD_VALUE;
            }
            key->number = behavior.character;
#if DEBUG_PARSER
            LOGD("Parsed key number: keyCode=%d, number=%d.", mKeyCode, key->number);
#endif
            break;
        case PROPERTY_META: {
            for (Behavior* b = key->firstBehavior; b; b = b->next) {
                if (b->metaState == property.metaState) {
                    LOGE("%s: Duplicate key behavior for modifier.",
                            mTokenizer->getLocation().string());
                    return BAD_VALUE;
                }
            }
            Behavior* newBehavior = new Behavior(behavior);
            newBehavior->metaState = property.metaState;
            newBehavior->next = key->firstBehavior;
            key->firstBehavior = newBehavior;
#if DEBUG_PARSER
            LOGD("Parsed key meta: keyCode=%d, meta=0x%x, char=%d, fallback=%d.", mKeyCode,
                    newBehavior->metaState, newBehavior->character, newBehavior->fallbackKeyCode);
#endif
            break;
        }
        }
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
                LOGE("%s: Duplicate modifier combination '%s'.",
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
    LOGE("%s: Malformed character literal.", mTokenizer->getLocation().string());
    return BAD_VALUE;
}

} // namespace android
