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
        float rawX,
        float rawY,
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
    mRawX = rawX;
    mRawY = rawY;
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
    if (xOffset != 0 || yOffset != 0) {
        for (size_t i = 0; i < mSamplePointerCoords.size(); i++) {
            PointerCoords& pointerCoords = mSamplePointerCoords.editItemAt(i);
            pointerCoords.x += xOffset;
            pointerCoords.y += yOffset;
        }
    }
}

} // namespace android

// NDK APIs

using android::InputEvent;
using android::KeyEvent;
using android::MotionEvent;

int32_t input_event_get_type(const input_event_t* event) {
    return reinterpret_cast<const InputEvent*>(event)->getType();
}

int32_t input_event_get_device_id(const input_event_t* event) {
    return reinterpret_cast<const InputEvent*>(event)->getDeviceId();
}

int32_t input_event_get_nature(const input_event_t* event) {
    return reinterpret_cast<const InputEvent*>(event)->getNature();
}

int32_t key_event_get_action(const input_event_t* key_event) {
    return reinterpret_cast<const KeyEvent*>(key_event)->getAction();
}

int32_t key_event_get_flags(const input_event_t* key_event) {
    return reinterpret_cast<const KeyEvent*>(key_event)->getFlags();
}

int32_t key_event_get_key_code(const input_event_t* key_event) {
    return reinterpret_cast<const KeyEvent*>(key_event)->getKeyCode();
}

int32_t key_event_get_scan_code(const input_event_t* key_event) {
    return reinterpret_cast<const KeyEvent*>(key_event)->getScanCode();
}

int32_t key_event_get_meta_state(const input_event_t* key_event) {
    return reinterpret_cast<const KeyEvent*>(key_event)->getMetaState();
}
int32_t key_event_get_repeat_count(const input_event_t* key_event) {
    return reinterpret_cast<const KeyEvent*>(key_event)->getRepeatCount();
}

int64_t key_event_get_down_time(const input_event_t* key_event) {
    return reinterpret_cast<const KeyEvent*>(key_event)->getDownTime();
}

int64_t key_event_get_event_time(const input_event_t* key_event) {
    return reinterpret_cast<const KeyEvent*>(key_event)->getEventTime();
}

int32_t motion_event_get_action(const input_event_t* motion_event) {
    return reinterpret_cast<const MotionEvent*>(motion_event)->getAction();
}

int32_t motion_event_get_meta_state(const input_event_t* motion_event) {
    return reinterpret_cast<const MotionEvent*>(motion_event)->getMetaState();
}

int32_t motion_event_get_edge_flags(const input_event_t* motion_event) {
    return reinterpret_cast<const MotionEvent*>(motion_event)->getEdgeFlags();
}

int64_t motion_event_get_down_time(const input_event_t* motion_event) {
    return reinterpret_cast<const MotionEvent*>(motion_event)->getDownTime();
}

int64_t motion_event_get_event_time(const input_event_t* motion_event) {
    return reinterpret_cast<const MotionEvent*>(motion_event)->getEventTime();
}

float motion_event_get_x_precision(const input_event_t* motion_event) {
    return reinterpret_cast<const MotionEvent*>(motion_event)->getXPrecision();
}

float motion_event_get_y_precision(const input_event_t* motion_event) {
    return reinterpret_cast<const MotionEvent*>(motion_event)->getYPrecision();
}

size_t motion_event_get_pointer_count(const input_event_t* motion_event) {
    return reinterpret_cast<const MotionEvent*>(motion_event)->getPointerCount();
}

int32_t motion_event_get_pointer_id(const input_event_t* motion_event, size_t pointer_index) {
    return reinterpret_cast<const MotionEvent*>(motion_event)->getPointerId(pointer_index);
}

float motion_event_get_raw_x(const input_event_t* motion_event) {
    return reinterpret_cast<const MotionEvent*>(motion_event)->getRawX();
}

float motion_event_get_raw_y(const input_event_t* motion_event) {
    return reinterpret_cast<const MotionEvent*>(motion_event)->getRawY();
}

float motion_event_get_x(const input_event_t* motion_event, size_t pointer_index) {
    return reinterpret_cast<const MotionEvent*>(motion_event)->getX(pointer_index);
}

float motion_event_get_y(const input_event_t* motion_event, size_t pointer_index) {
    return reinterpret_cast<const MotionEvent*>(motion_event)->getY(pointer_index);
}

float motion_event_get_pressure(const input_event_t* motion_event, size_t pointer_index) {
    return reinterpret_cast<const MotionEvent*>(motion_event)->getPressure(pointer_index);
}

float motion_event_get_size(const input_event_t* motion_event, size_t pointer_index) {
    return reinterpret_cast<const MotionEvent*>(motion_event)->getSize(pointer_index);
}

size_t motion_event_get_history_size(const input_event_t* motion_event) {
    return reinterpret_cast<const MotionEvent*>(motion_event)->getHistorySize();
}

int64_t motion_event_get_historical_event_time(input_event_t* motion_event,
        size_t history_index) {
    return reinterpret_cast<const MotionEvent*>(motion_event)->getHistoricalEventTime(
            history_index);
}

float motion_event_get_historical_x(input_event_t* motion_event, size_t pointer_index,
        size_t history_index) {
    return reinterpret_cast<const MotionEvent*>(motion_event)->getHistoricalX(
            pointer_index, history_index);
}

float motion_event_get_historical_y(input_event_t* motion_event, size_t pointer_index,
        size_t history_index) {
    return reinterpret_cast<const MotionEvent*>(motion_event)->getHistoricalY(
            pointer_index, history_index);
}

float motion_event_get_historical_pressure(input_event_t* motion_event, size_t pointer_index,
        size_t history_index) {
    return reinterpret_cast<const MotionEvent*>(motion_event)->getHistoricalPressure(
            pointer_index, history_index);
}

float motion_event_get_historical_size(input_event_t* motion_event, size_t pointer_index,
        size_t history_index) {
    return reinterpret_cast<const MotionEvent*>(motion_event)->getHistoricalSize(
            pointer_index, history_index);
}
