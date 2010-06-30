//
// Copyright 2010 The Android Open Source Project
//
// Provides a pipe-based transport for native events in the NDK.
//
#define LOG_TAG "Input"

//#define LOG_NDEBUG 0

#include <ui/Input.h>

namespace android {

// class InputEvent

void InputEvent::initialize(int32_t deviceId, int32_t nature) {
    mDeviceId = deviceId;
    mNature = nature;
}

// class KeyEvent

bool KeyEvent::hasDefaultAction(int32_t keyCode) {
    switch (keyCode) {
        case KEYCODE_HOME:
        case KEYCODE_BACK:
        case KEYCODE_CALL:
        case KEYCODE_ENDCALL:
        case KEYCODE_VOLUME_UP:
        case KEYCODE_VOLUME_DOWN:
        case KEYCODE_POWER:
        case KEYCODE_CAMERA:
        case KEYCODE_HEADSETHOOK:
        case KEYCODE_MENU:
        case KEYCODE_NOTIFICATION:
        case KEYCODE_FOCUS:
        case KEYCODE_SEARCH:
        case KEYCODE_MEDIA_PLAY_PAUSE:
        case KEYCODE_MEDIA_STOP:
        case KEYCODE_MEDIA_NEXT:
        case KEYCODE_MEDIA_PREVIOUS:
        case KEYCODE_MEDIA_REWIND:
        case KEYCODE_MEDIA_FAST_FORWARD:
        case KEYCODE_MUTE:
            return true;
    }
    
    return false;
}

bool KeyEvent::hasDefaultAction() const {
    return hasDefaultAction(getKeyCode());
}

bool KeyEvent::isSystemKey(int32_t keyCode) {
    switch (keyCode) {
        case KEYCODE_MENU:
        case KEYCODE_SOFT_RIGHT:
        case KEYCODE_HOME:
        case KEYCODE_BACK:
        case KEYCODE_CALL:
        case KEYCODE_ENDCALL:
        case KEYCODE_VOLUME_UP:
        case KEYCODE_VOLUME_DOWN:
        case KEYCODE_MUTE:
        case KEYCODE_POWER:
        case KEYCODE_HEADSETHOOK:
        case KEYCODE_MEDIA_PLAY_PAUSE:
        case KEYCODE_MEDIA_STOP:
        case KEYCODE_MEDIA_NEXT:
        case KEYCODE_MEDIA_PREVIOUS:
        case KEYCODE_MEDIA_REWIND:
        case KEYCODE_MEDIA_FAST_FORWARD:
        case KEYCODE_CAMERA:
        case KEYCODE_FOCUS:
        case KEYCODE_SEARCH:
            return true;
    }
    
    return false;
}

bool KeyEvent::isSystemKey() const {
    return isSystemKey(getKeyCode());
}

void KeyEvent::initialize(
        int32_t deviceId,
        int32_t nature,
        int32_t action,
        int32_t flags,
        int32_t keyCode,
        int32_t scanCode,
        int32_t metaState,
        int32_t repeatCount,
        nsecs_t downTime,
        nsecs_t eventTime) {
    InputEvent::initialize(deviceId, nature);
    mAction = action;
    mFlags = flags;
    mKeyCode = keyCode;
    mScanCode = scanCode;
    mMetaState = metaState;
    mRepeatCount = repeatCount;
    mDownTime = downTime;
    mEventTime = eventTime;
}

// class MotionEvent

void MotionEvent::initialize(
        int32_t deviceId,
        int32_t nature,
        int32_t action,
        int32_t edgeFlags,
        int32_t metaState,
        float xOffset,
        float yOffset,
        float xPrecision,
        float yPrecision,
        nsecs_t downTime,
        nsecs_t eventTime,
        size_t pointerCount,
        const int32_t* pointerIds,
        const PointerCoords* pointerCoords) {
    InputEvent::initialize(deviceId, nature);
    mAction = action;
    mEdgeFlags = edgeFlags;
    mMetaState = metaState;
    mXOffset = xOffset;
    mYOffset = yOffset;
    mXPrecision = xPrecision;
    mYPrecision = yPrecision;
    mDownTime = downTime;
    mPointerIds.clear();
    mPointerIds.appendArray(pointerIds, pointerCount);
    mSampleEventTimes.clear();
    mSamplePointerCoords.clear();
    addSample(eventTime, pointerCoords);
}

void MotionEvent::addSample(
        int64_t eventTime,
        const PointerCoords* pointerCoords) {
    mSampleEventTimes.push(eventTime);
    mSamplePointerCoords.appendArray(pointerCoords, getPointerCount());
}

void MotionEvent::offsetLocation(float xOffset, float yOffset) {
    mXOffset += xOffset;
    mYOffset += yOffset;
}

} // namespace android
