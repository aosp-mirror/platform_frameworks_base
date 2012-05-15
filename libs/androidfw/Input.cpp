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

#define LOG_TAG "Input"
//#define LOG_NDEBUG 0

#include <math.h>
#include <limits.h>

#include <androidfw/Input.h>

#ifdef HAVE_ANDROID_OS
#include <binder/Parcel.h>

#include "SkPoint.h"
#include "SkMatrix.h"
#include "SkScalar.h"
#endif

namespace android {

// --- InputEvent ---

void InputEvent::initialize(int32_t deviceId, int32_t source) {
    mDeviceId = deviceId;
    mSource = source;
}

void InputEvent::initialize(const InputEvent& from) {
    mDeviceId = from.mDeviceId;
    mSource = from.mSource;
}

// --- KeyEvent ---

bool KeyEvent::hasDefaultAction(int32_t keyCode) {
    switch (keyCode) {
        case AKEYCODE_HOME:
        case AKEYCODE_BACK:
        case AKEYCODE_CALL:
        case AKEYCODE_ENDCALL:
        case AKEYCODE_VOLUME_UP:
        case AKEYCODE_VOLUME_DOWN:
        case AKEYCODE_VOLUME_MUTE:
        case AKEYCODE_POWER:
        case AKEYCODE_CAMERA:
        case AKEYCODE_HEADSETHOOK:
        case AKEYCODE_MENU:
        case AKEYCODE_NOTIFICATION:
        case AKEYCODE_FOCUS:
        case AKEYCODE_SEARCH:
        case AKEYCODE_MEDIA_PLAY:
        case AKEYCODE_MEDIA_PAUSE:
        case AKEYCODE_MEDIA_PLAY_PAUSE:
        case AKEYCODE_MEDIA_STOP:
        case AKEYCODE_MEDIA_NEXT:
        case AKEYCODE_MEDIA_PREVIOUS:
        case AKEYCODE_MEDIA_REWIND:
        case AKEYCODE_MEDIA_RECORD:
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
        case AKEYCODE_VOLUME_MUTE:
        case AKEYCODE_MUTE:
        case AKEYCODE_POWER:
        case AKEYCODE_HEADSETHOOK:
        case AKEYCODE_MEDIA_PLAY:
        case AKEYCODE_MEDIA_PAUSE:
        case AKEYCODE_MEDIA_PLAY_PAUSE:
        case AKEYCODE_MEDIA_STOP:
        case AKEYCODE_MEDIA_NEXT:
        case AKEYCODE_MEDIA_PREVIOUS:
        case AKEYCODE_MEDIA_REWIND:
        case AKEYCODE_MEDIA_RECORD:
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


// --- PointerCoords ---

float PointerCoords::getAxisValue(int32_t axis) const {
    if (axis < 0 || axis > 63) {
        return 0;
    }

    uint64_t axisBit = 1LL << axis;
    if (!(bits & axisBit)) {
        return 0;
    }
    uint32_t index = __builtin_popcountll(bits & (axisBit - 1LL));
    return values[index];
}

status_t PointerCoords::setAxisValue(int32_t axis, float value) {
    if (axis < 0 || axis > 63) {
        return NAME_NOT_FOUND;
    }

    uint64_t axisBit = 1LL << axis;
    uint32_t index = __builtin_popcountll(bits & (axisBit - 1LL));
    if (!(bits & axisBit)) {
        if (value == 0) {
            return OK; // axes with value 0 do not need to be stored
        }
        uint32_t count = __builtin_popcountll(bits);
        if (count >= MAX_AXES) {
            tooManyAxes(axis);
            return NO_MEMORY;
        }
        bits |= axisBit;
        for (uint32_t i = count; i > index; i--) {
            values[i] = values[i - 1];
        }
    }
    values[index] = value;
    return OK;
}

static inline void scaleAxisValue(PointerCoords& c, int axis, float scaleFactor) {
    float value = c.getAxisValue(axis);
    if (value != 0) {
        c.setAxisValue(axis, value * scaleFactor);
    }
}

void PointerCoords::scale(float scaleFactor) {
    // No need to scale pressure or size since they are normalized.
    // No need to scale orientation since it is meaningless to do so.
    scaleAxisValue(*this, AMOTION_EVENT_AXIS_X, scaleFactor);
    scaleAxisValue(*this, AMOTION_EVENT_AXIS_Y, scaleFactor);
    scaleAxisValue(*this, AMOTION_EVENT_AXIS_TOUCH_MAJOR, scaleFactor);
    scaleAxisValue(*this, AMOTION_EVENT_AXIS_TOUCH_MINOR, scaleFactor);
    scaleAxisValue(*this, AMOTION_EVENT_AXIS_TOOL_MAJOR, scaleFactor);
    scaleAxisValue(*this, AMOTION_EVENT_AXIS_TOOL_MINOR, scaleFactor);
}

#ifdef HAVE_ANDROID_OS
status_t PointerCoords::readFromParcel(Parcel* parcel) {
    bits = parcel->readInt64();

    uint32_t count = __builtin_popcountll(bits);
    if (count > MAX_AXES) {
        return BAD_VALUE;
    }

    for (uint32_t i = 0; i < count; i++) {
        values[i] = parcel->readInt32();
    }
    return OK;
}

status_t PointerCoords::writeToParcel(Parcel* parcel) const {
    parcel->writeInt64(bits);

    uint32_t count = __builtin_popcountll(bits);
    for (uint32_t i = 0; i < count; i++) {
        parcel->writeInt32(values[i]);
    }
    return OK;
}
#endif

void PointerCoords::tooManyAxes(int axis) {
    ALOGW("Could not set value for axis %d because the PointerCoords structure is full and "
            "cannot contain more than %d axis values.", axis, int(MAX_AXES));
}

bool PointerCoords::operator==(const PointerCoords& other) const {
    if (bits != other.bits) {
        return false;
    }
    uint32_t count = __builtin_popcountll(bits);
    for (uint32_t i = 0; i < count; i++) {
        if (values[i] != other.values[i]) {
            return false;
        }
    }
    return true;
}

void PointerCoords::copyFrom(const PointerCoords& other) {
    bits = other.bits;
    uint32_t count = __builtin_popcountll(bits);
    for (uint32_t i = 0; i < count; i++) {
        values[i] = other.values[i];
    }
}


// --- PointerProperties ---

bool PointerProperties::operator==(const PointerProperties& other) const {
    return id == other.id
            && toolType == other.toolType;
}

void PointerProperties::copyFrom(const PointerProperties& other) {
    id = other.id;
    toolType = other.toolType;
}


// --- MotionEvent ---

void MotionEvent::initialize(
        int32_t deviceId,
        int32_t source,
        int32_t action,
        int32_t flags,
        int32_t edgeFlags,
        int32_t metaState,
        int32_t buttonState,
        float xOffset,
        float yOffset,
        float xPrecision,
        float yPrecision,
        nsecs_t downTime,
        nsecs_t eventTime,
        size_t pointerCount,
        const PointerProperties* pointerProperties,
        const PointerCoords* pointerCoords) {
    InputEvent::initialize(deviceId, source);
    mAction = action;
    mFlags = flags;
    mEdgeFlags = edgeFlags;
    mMetaState = metaState;
    mButtonState = buttonState;
    mXOffset = xOffset;
    mYOffset = yOffset;
    mXPrecision = xPrecision;
    mYPrecision = yPrecision;
    mDownTime = downTime;
    mPointerProperties.clear();
    mPointerProperties.appendArray(pointerProperties, pointerCount);
    mSampleEventTimes.clear();
    mSamplePointerCoords.clear();
    addSample(eventTime, pointerCoords);
}

void MotionEvent::copyFrom(const MotionEvent* other, bool keepHistory) {
    InputEvent::initialize(other->mDeviceId, other->mSource);
    mAction = other->mAction;
    mFlags = other->mFlags;
    mEdgeFlags = other->mEdgeFlags;
    mMetaState = other->mMetaState;
    mButtonState = other->mButtonState;
    mXOffset = other->mXOffset;
    mYOffset = other->mYOffset;
    mXPrecision = other->mXPrecision;
    mYPrecision = other->mYPrecision;
    mDownTime = other->mDownTime;
    mPointerProperties = other->mPointerProperties;

    if (keepHistory) {
        mSampleEventTimes = other->mSampleEventTimes;
        mSamplePointerCoords = other->mSamplePointerCoords;
    } else {
        mSampleEventTimes.clear();
        mSampleEventTimes.push(other->getEventTime());
        mSamplePointerCoords.clear();
        size_t pointerCount = other->getPointerCount();
        size_t historySize = other->getHistorySize();
        mSamplePointerCoords.appendArray(other->mSamplePointerCoords.array()
                + (historySize * pointerCount), pointerCount);
    }
}

void MotionEvent::addSample(
        int64_t eventTime,
        const PointerCoords* pointerCoords) {
    mSampleEventTimes.push(eventTime);
    mSamplePointerCoords.appendArray(pointerCoords, getPointerCount());
}

const PointerCoords* MotionEvent::getRawPointerCoords(size_t pointerIndex) const {
    return &mSamplePointerCoords[getHistorySize() * getPointerCount() + pointerIndex];
}

float MotionEvent::getRawAxisValue(int32_t axis, size_t pointerIndex) const {
    return getRawPointerCoords(pointerIndex)->getAxisValue(axis);
}

float MotionEvent::getAxisValue(int32_t axis, size_t pointerIndex) const {
    float value = getRawPointerCoords(pointerIndex)->getAxisValue(axis);
    switch (axis) {
    case AMOTION_EVENT_AXIS_X:
        return value + mXOffset;
    case AMOTION_EVENT_AXIS_Y:
        return value + mYOffset;
    }
    return value;
}

const PointerCoords* MotionEvent::getHistoricalRawPointerCoords(
        size_t pointerIndex, size_t historicalIndex) const {
    return &mSamplePointerCoords[historicalIndex * getPointerCount() + pointerIndex];
}

float MotionEvent::getHistoricalRawAxisValue(int32_t axis, size_t pointerIndex,
        size_t historicalIndex) const {
    return getHistoricalRawPointerCoords(pointerIndex, historicalIndex)->getAxisValue(axis);
}

float MotionEvent::getHistoricalAxisValue(int32_t axis, size_t pointerIndex,
        size_t historicalIndex) const {
    float value = getHistoricalRawPointerCoords(pointerIndex, historicalIndex)->getAxisValue(axis);
    switch (axis) {
    case AMOTION_EVENT_AXIS_X:
        return value + mXOffset;
    case AMOTION_EVENT_AXIS_Y:
        return value + mYOffset;
    }
    return value;
}

ssize_t MotionEvent::findPointerIndex(int32_t pointerId) const {
    size_t pointerCount = mPointerProperties.size();
    for (size_t i = 0; i < pointerCount; i++) {
        if (mPointerProperties.itemAt(i).id == pointerId) {
            return i;
        }
    }
    return -1;
}

void MotionEvent::offsetLocation(float xOffset, float yOffset) {
    mXOffset += xOffset;
    mYOffset += yOffset;
}

void MotionEvent::scale(float scaleFactor) {
    mXOffset *= scaleFactor;
    mYOffset *= scaleFactor;
    mXPrecision *= scaleFactor;
    mYPrecision *= scaleFactor;

    size_t numSamples = mSamplePointerCoords.size();
    for (size_t i = 0; i < numSamples; i++) {
        mSamplePointerCoords.editItemAt(i).scale(scaleFactor);
    }
}

#ifdef HAVE_ANDROID_OS
static inline float transformAngle(const SkMatrix* matrix, float angleRadians) {
    // Construct and transform a vector oriented at the specified clockwise angle from vertical.
    // Coordinate system: down is increasing Y, right is increasing X.
    SkPoint vector;
    vector.fX = SkFloatToScalar(sinf(angleRadians));
    vector.fY = SkFloatToScalar(-cosf(angleRadians));
    matrix->mapVectors(& vector, 1);

    // Derive the transformed vector's clockwise angle from vertical.
    float result = atan2f(SkScalarToFloat(vector.fX), SkScalarToFloat(-vector.fY));
    if (result < - M_PI_2) {
        result += M_PI;
    } else if (result > M_PI_2) {
        result -= M_PI;
    }
    return result;
}

void MotionEvent::transform(const SkMatrix* matrix) {
    float oldXOffset = mXOffset;
    float oldYOffset = mYOffset;

    // The tricky part of this implementation is to preserve the value of
    // rawX and rawY.  So we apply the transformation to the first point
    // then derive an appropriate new X/Y offset that will preserve rawX and rawY.
    SkPoint point;
    float rawX = getRawX(0);
    float rawY = getRawY(0);
    matrix->mapXY(SkFloatToScalar(rawX + oldXOffset), SkFloatToScalar(rawY + oldYOffset),
            & point);
    float newX = SkScalarToFloat(point.fX);
    float newY = SkScalarToFloat(point.fY);
    float newXOffset = newX - rawX;
    float newYOffset = newY - rawY;

    mXOffset = newXOffset;
    mYOffset = newYOffset;

    // Apply the transformation to all samples.
    size_t numSamples = mSamplePointerCoords.size();
    for (size_t i = 0; i < numSamples; i++) {
        PointerCoords& c = mSamplePointerCoords.editItemAt(i);
        float x = c.getAxisValue(AMOTION_EVENT_AXIS_X) + oldXOffset;
        float y = c.getAxisValue(AMOTION_EVENT_AXIS_Y) + oldYOffset;
        matrix->mapXY(SkFloatToScalar(x), SkFloatToScalar(y), &point);
        c.setAxisValue(AMOTION_EVENT_AXIS_X, SkScalarToFloat(point.fX) - newXOffset);
        c.setAxisValue(AMOTION_EVENT_AXIS_Y, SkScalarToFloat(point.fY) - newYOffset);

        float orientation = c.getAxisValue(AMOTION_EVENT_AXIS_ORIENTATION);
        c.setAxisValue(AMOTION_EVENT_AXIS_ORIENTATION, transformAngle(matrix, orientation));
    }
}

status_t MotionEvent::readFromParcel(Parcel* parcel) {
    size_t pointerCount = parcel->readInt32();
    size_t sampleCount = parcel->readInt32();
    if (pointerCount == 0 || pointerCount > MAX_POINTERS || sampleCount == 0) {
        return BAD_VALUE;
    }

    mDeviceId = parcel->readInt32();
    mSource = parcel->readInt32();
    mAction = parcel->readInt32();
    mFlags = parcel->readInt32();
    mEdgeFlags = parcel->readInt32();
    mMetaState = parcel->readInt32();
    mButtonState = parcel->readInt32();
    mXOffset = parcel->readFloat();
    mYOffset = parcel->readFloat();
    mXPrecision = parcel->readFloat();
    mYPrecision = parcel->readFloat();
    mDownTime = parcel->readInt64();

    mPointerProperties.clear();
    mPointerProperties.setCapacity(pointerCount);
    mSampleEventTimes.clear();
    mSampleEventTimes.setCapacity(sampleCount);
    mSamplePointerCoords.clear();
    mSamplePointerCoords.setCapacity(sampleCount * pointerCount);

    for (size_t i = 0; i < pointerCount; i++) {
        mPointerProperties.push();
        PointerProperties& properties = mPointerProperties.editTop();
        properties.id = parcel->readInt32();
        properties.toolType = parcel->readInt32();
    }

    while (sampleCount-- > 0) {
        mSampleEventTimes.push(parcel->readInt64());
        for (size_t i = 0; i < pointerCount; i++) {
            mSamplePointerCoords.push();
            status_t status = mSamplePointerCoords.editTop().readFromParcel(parcel);
            if (status) {
                return status;
            }
        }
    }
    return OK;
}

status_t MotionEvent::writeToParcel(Parcel* parcel) const {
    size_t pointerCount = mPointerProperties.size();
    size_t sampleCount = mSampleEventTimes.size();

    parcel->writeInt32(pointerCount);
    parcel->writeInt32(sampleCount);

    parcel->writeInt32(mDeviceId);
    parcel->writeInt32(mSource);
    parcel->writeInt32(mAction);
    parcel->writeInt32(mFlags);
    parcel->writeInt32(mEdgeFlags);
    parcel->writeInt32(mMetaState);
    parcel->writeInt32(mButtonState);
    parcel->writeFloat(mXOffset);
    parcel->writeFloat(mYOffset);
    parcel->writeFloat(mXPrecision);
    parcel->writeFloat(mYPrecision);
    parcel->writeInt64(mDownTime);

    for (size_t i = 0; i < pointerCount; i++) {
        const PointerProperties& properties = mPointerProperties.itemAt(i);
        parcel->writeInt32(properties.id);
        parcel->writeInt32(properties.toolType);
    }

    const PointerCoords* pc = mSamplePointerCoords.array();
    for (size_t h = 0; h < sampleCount; h++) {
        parcel->writeInt64(mSampleEventTimes.itemAt(h));
        for (size_t i = 0; i < pointerCount; i++) {
            status_t status = (pc++)->writeToParcel(parcel);
            if (status) {
                return status;
            }
        }
    }
    return OK;
}
#endif

bool MotionEvent::isTouchEvent(int32_t source, int32_t action) {
    if (source & AINPUT_SOURCE_CLASS_POINTER) {
        // Specifically excludes HOVER_MOVE and SCROLL.
        switch (action & AMOTION_EVENT_ACTION_MASK) {
        case AMOTION_EVENT_ACTION_DOWN:
        case AMOTION_EVENT_ACTION_MOVE:
        case AMOTION_EVENT_ACTION_UP:
        case AMOTION_EVENT_ACTION_POINTER_DOWN:
        case AMOTION_EVENT_ACTION_POINTER_UP:
        case AMOTION_EVENT_ACTION_CANCEL:
        case AMOTION_EVENT_ACTION_OUTSIDE:
            return true;
        }
    }
    return false;
}


// --- PooledInputEventFactory ---

PooledInputEventFactory::PooledInputEventFactory(size_t maxPoolSize) :
        mMaxPoolSize(maxPoolSize) {
}

PooledInputEventFactory::~PooledInputEventFactory() {
    for (size_t i = 0; i < mKeyEventPool.size(); i++) {
        delete mKeyEventPool.itemAt(i);
    }
    for (size_t i = 0; i < mMotionEventPool.size(); i++) {
        delete mMotionEventPool.itemAt(i);
    }
}

KeyEvent* PooledInputEventFactory::createKeyEvent() {
    if (!mKeyEventPool.isEmpty()) {
        KeyEvent* event = mKeyEventPool.top();
        mKeyEventPool.pop();
        return event;
    }
    return new KeyEvent();
}

MotionEvent* PooledInputEventFactory::createMotionEvent() {
    if (!mMotionEventPool.isEmpty()) {
        MotionEvent* event = mMotionEventPool.top();
        mMotionEventPool.pop();
        return event;
    }
    return new MotionEvent();
}

void PooledInputEventFactory::recycle(InputEvent* event) {
    switch (event->getType()) {
    case AINPUT_EVENT_TYPE_KEY:
        if (mKeyEventPool.size() < mMaxPoolSize) {
            mKeyEventPool.push(static_cast<KeyEvent*>(event));
            return;
        }
        break;
    case AINPUT_EVENT_TYPE_MOTION:
        if (mMotionEventPool.size() < mMaxPoolSize) {
            mMotionEventPool.push(static_cast<MotionEvent*>(event));
            return;
        }
        break;
    }
    delete event;
}

} // namespace android
