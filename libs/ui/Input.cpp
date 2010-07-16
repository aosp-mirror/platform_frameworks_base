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

void InputEvent::initialize(int32_t deviceId, int32_t source) {
    mDeviceId = deviceId;
    mSource = source;
}

void InputEvent::initialize(const InputEvent& from) {
    mDeviceId = from.mDeviceId;
    mSource = from.mSource;
}

// class KeyEvent

bool KeyEvent::hasDefaultAction(int32_t keyCode) {
    switch (keyCode) {
        case AKEYCODE_HOME:
        case AKEYCODE_BACK:
        case AKEYCODE_CALL:
        case AKEYCODE_ENDCALL:
        case AKEYCODE_VOLUME_UP:
        case AKEYCODE_VOLUME_DOWN:
        case AKEYCODE_POWER:
        case AKEYCODE_CAMERA:
        case AKEYCODE_HEADSETHOOK:
        case AKEYCODE_MENU:
        case AKEYCODE_NOTIFICATION:
        case AKEYCODE_FOCUS:
        case AKEYCODE_SEARCH:
        case AKEYCODE_MEDIA_PLAY_PAUSE:
        case AKEYCODE_MEDIA_STOP:
        case AKEYCODE_MEDIA_NEXT:
        case AKEYCODE_MEDIA_PREVIOUS:
        case AKEYCODE_MEDIA_REWIND:
        case AKEYCODE_MEDIA_FAST_FORWARD:
        case AKEYCODE_MUTE:
            return true;
    }
    
    return false;
}

bool KeyEvent::hasDefaultAction() const {
    return hasDefaultAction(getKeyCode());
}

bool KeyEvent::isSystemKey(int32_t keyCode) {
    switch (keyCode) {
        case AKEYCODE_MENU:
        case AKEYCODE_SOFT_RIGHT:
        case AKEYCODE_HOME:
        case AKEYCODE_BACK:
        case AKEYCODE_CALL:
        case AKEYCODE_ENDCALL:
        case AKEYCODE_VOLUME_UP:
        case AKEYCODE_VOLUME_DOWN:
        case AKEYCODE_MUTE:
        case AKEYCODE_POWER:
        case AKEYCODE_HEADSETHOOK:
        case AKEYCODE_MEDIA_PLAY_PAUSE:
        case AKEYCODE_MEDIA_STOP:
        case AKEYCODE_MEDIA_NEXT:
        case AKEYCODE_MEDIA_PREVIOUS:
        case AKEYCODE_MEDIA_REWIND:
        case AKEYCODE_MEDIA_FAST_FORWARD:
        case AKEYCODE_CAMERA:
        case AKEYCODE_FOCUS:
        case AKEYCODE_SEARCH:
            return true;
    }
    
    return false;
}

bool KeyEvent::isSystemKey() const {
    return isSystemKey(getKeyCode());
}

void KeyEvent::initialize(
        int32_t deviceId,
        int32_t source,
        int32_t action,
        int32_t flags,
        int32_t keyCode,
        int32_t scanCode,
        int32_t metaState,
        int32_t repeatCount,
        nsecs_t downTime,
        nsecs_t eventTime) {
    InputEvent::initialize(deviceId, source);
    mAction = action;
    mFlags = flags;
    mKeyCode = keyCode;
    mScanCode = scanCode;
    mMetaState = metaState;
    mRepeatCount = repeatCount;
    mDownTime = downTime;
    mEventTime = eventTime;
}

void KeyEvent::initialize(const KeyEvent& from) {
    InputEvent::initialize(from);
    mAction = from.mAction;
    mFlags = from.mFlags;
    mKeyCode = from.mKeyCode;
    mScanCode = from.mScanCode;
    mMetaState = from.mMetaState;
    mRepeatCount = from.mRepeatCount;
    mDownTime = from.mDownTime;
    mEventTime = from.mEventTime;
}

// class MotionEvent

void MotionEvent::initialize(
        int32_t deviceId,
        int32_t source,
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
    InputEvent::initialize(deviceId, source);
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
