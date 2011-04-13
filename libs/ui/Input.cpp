//
// Copyright 2010 The Android Open Source Project
//
// Provides a pipe-based transport for native events in the NDK.
//
#define LOG_TAG "Input"

//#define LOG_NDEBUG 0

// Log debug messages about keymap probing.
#define DEBUG_PROBE 0

// Log debug messages about velocity tracking.
#define DEBUG_VELOCITY 0

#include <stdlib.h>
#include <unistd.h>
#include <ctype.h>

#include <ui/Input.h>

#include <math.h>

#ifdef HAVE_ANDROID_OS
#include <binder/Parcel.h>

#include "SkPoint.h"
#include "SkMatrix.h"
#include "SkScalar.h"
#endif

namespace android {

static const char* CONFIGURATION_FILE_DIR[] = {
        "idc/",
        "keylayout/",
        "keychars/",
};

static const char* CONFIGURATION_FILE_EXTENSION[] = {
        ".idc",
        ".kl",
        ".kcm",
};

static bool isValidNameChar(char ch) {
    return isascii(ch) && (isdigit(ch) || isalpha(ch) || ch == '-' || ch == '_');
}

static void appendInputDeviceConfigurationFileRelativePath(String8& path,
        const String8& name, InputDeviceConfigurationFileType type) {
    path.append(CONFIGURATION_FILE_DIR[type]);
    for (size_t i = 0; i < name.length(); i++) {
        char ch = name[i];
        if (!isValidNameChar(ch)) {
            ch = '_';
        }
        path.append(&ch, 1);
    }
    path.append(CONFIGURATION_FILE_EXTENSION[type]);
}

String8 getInputDeviceConfigurationFilePathByDeviceIdentifier(
        const InputDeviceIdentifier& deviceIdentifier,
        InputDeviceConfigurationFileType type) {
    if (deviceIdentifier.vendor !=0 && deviceIdentifier.product != 0) {
        if (deviceIdentifier.version != 0) {
            // Try vendor product version.
            String8 versionPath(getInputDeviceConfigurationFilePathByName(
                    String8::format("Vendor_%04x_Product_%04x_Version_%04x",
                            deviceIdentifier.vendor, deviceIdentifier.product,
                            deviceIdentifier.version),
                    type));
            if (!versionPath.isEmpty()) {
                return versionPath;
            }
        }

        // Try vendor product.
        String8 productPath(getInputDeviceConfigurationFilePathByName(
                String8::format("Vendor_%04x_Product_%04x",
                        deviceIdentifier.vendor, deviceIdentifier.product),
                type));
        if (!productPath.isEmpty()) {
            return productPath;
        }
    }

    // Try device name.
    return getInputDeviceConfigurationFilePathByName(deviceIdentifier.name, type);
}

String8 getInputDeviceConfigurationFilePathByName(
        const String8& name, InputDeviceConfigurationFileType type) {
    // Search system repository.
    String8 path;
    path.setTo(getenv("ANDROID_ROOT"));
    path.append("/usr/");
    appendInputDeviceConfigurationFileRelativePath(path, name, type);
#if DEBUG_PROBE
    LOGD("Probing for system provided input device configuration file: path='%s'", path.string());
#endif
    if (!access(path.string(), R_OK)) {
#if DEBUG_PROBE
        LOGD("Found");
#endif
        return path;
    }

    // Search user repository.
    // TODO Should only look here if not in safe mode.
    path.setTo(getenv("ANDROID_DATA"));
    path.append("/system/devices/");
    appendInputDeviceConfigurationFileRelativePath(path, name, type);
#if DEBUG_PROBE
    LOGD("Probing for system user input device configuration file: path='%s'", path.string());
#endif
    if (!access(path.string(), R_OK)) {
#if DEBUG_PROBE
        LOGD("Found");
#endif
        return path;
    }

    // Not found.
#if DEBUG_PROBE
    LOGD("Probe failed to find input device configuration file: name='%s', type=%d",
            name.string(), type);
#endif
    return String8();
}


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

float* PointerCoords::editAxisValue(int32_t axis) {
    if (axis < 0 || axis > 63) {
        return NULL;
    }

    uint64_t axisBit = 1LL << axis;
    if (!(bits & axisBit)) {
        return NULL;
    }
    uint32_t index = __builtin_popcountll(bits & (axisBit - 1LL));
    return &values[index];
}

static inline void scaleAxisValue(PointerCoords& c, int axis, float scaleFactor) {
    float* value = c.editAxisValue(axis);
    if (value) {
        *value *= scaleFactor;
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
    LOGW("Could not set value for axis %d because the PointerCoords structure is full and "
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


// --- MotionEvent ---

void MotionEvent::initialize(
        int32_t deviceId,
        int32_t source,
        int32_t action,
        int32_t flags,
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
    mFlags = flags;
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

void MotionEvent::copyFrom(const MotionEvent* other, bool keepHistory) {
    InputEvent::initialize(other->mDeviceId, other->mSource);
    mAction = other->mAction;
    mFlags = other->mFlags;
    mEdgeFlags = other->mEdgeFlags;
    mMetaState = other->mMetaState;
    mXOffset = other->mXOffset;
    mYOffset = other->mYOffset;
    mXPrecision = other->mXPrecision;
    mYPrecision = other->mYPrecision;
    mDownTime = other->mDownTime;
    mPointerIds = other->mPointerIds;

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
    size_t pointerCount = mPointerIds.size();
    for (size_t i = 0; i < pointerCount; i++) {
        if (mPointerIds.itemAt(i) == pointerId) {
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
        float* xPtr = c.editAxisValue(AMOTION_EVENT_AXIS_X);
        float* yPtr = c.editAxisValue(AMOTION_EVENT_AXIS_Y);
        if (xPtr && yPtr) {
            float x = *xPtr + oldXOffset;
            float y = *yPtr + oldYOffset;
            matrix->mapXY(SkFloatToScalar(x), SkFloatToScalar(y), & point);
            *xPtr = SkScalarToFloat(point.fX) - newXOffset;
            *yPtr = SkScalarToFloat(point.fY) - newYOffset;
        }

        float* orientationPtr = c.editAxisValue(AMOTION_EVENT_AXIS_ORIENTATION);
        if (orientationPtr) {
            *orientationPtr = transformAngle(matrix, *orientationPtr);
        }
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
    mXOffset = parcel->readFloat();
    mYOffset = parcel->readFloat();
    mXPrecision = parcel->readFloat();
    mYPrecision = parcel->readFloat();
    mDownTime = parcel->readInt64();

    mPointerIds.clear();
    mPointerIds.setCapacity(pointerCount);
    mSampleEventTimes.clear();
    mSampleEventTimes.setCapacity(sampleCount);
    mSamplePointerCoords.clear();
    mSamplePointerCoords.setCapacity(sampleCount * pointerCount);

    for (size_t i = 0; i < pointerCount; i++) {
        mPointerIds.push(parcel->readInt32());
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
    size_t pointerCount = mPointerIds.size();
    size_t sampleCount = mSampleEventTimes.size();

    parcel->writeInt32(pointerCount);
    parcel->writeInt32(sampleCount);

    parcel->writeInt32(mDeviceId);
    parcel->writeInt32(mSource);
    parcel->writeInt32(mAction);
    parcel->writeInt32(mFlags);
    parcel->writeInt32(mEdgeFlags);
    parcel->writeInt32(mMetaState);
    parcel->writeFloat(mXOffset);
    parcel->writeFloat(mYOffset);
    parcel->writeFloat(mXPrecision);
    parcel->writeFloat(mYPrecision);
    parcel->writeInt64(mDownTime);

    for (size_t i = 0; i < pointerCount; i++) {
        parcel->writeInt32(mPointerIds.itemAt(i));
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


// --- VelocityTracker ---

VelocityTracker::VelocityTracker() {
    clear();
}

void VelocityTracker::clear() {
    mIndex = 0;
    mMovements[0].idBits.clear();
    mActivePointerId = -1;
}

void VelocityTracker::clearPointers(BitSet32 idBits) {
    BitSet32 remainingIdBits(mMovements[mIndex].idBits.value & ~idBits.value);
    mMovements[mIndex].idBits = remainingIdBits;

    if (mActivePointerId >= 0 && idBits.hasBit(mActivePointerId)) {
        mActivePointerId = !remainingIdBits.isEmpty() ? remainingIdBits.firstMarkedBit() : -1;
    }
}

void VelocityTracker::addMovement(nsecs_t eventTime, BitSet32 idBits, const Position* positions) {
    if (++mIndex == HISTORY_SIZE) {
        mIndex = 0;
    }

    while (idBits.count() > MAX_POINTERS) {
        idBits.clearBit(idBits.lastMarkedBit());
    }

    Movement& movement = mMovements[mIndex];
    movement.eventTime = eventTime;
    movement.idBits = idBits;
    uint32_t count = idBits.count();
    for (uint32_t i = 0; i < count; i++) {
        movement.positions[i] = positions[i];
    }

    if (mActivePointerId < 0 || !idBits.hasBit(mActivePointerId)) {
        mActivePointerId = count != 0 ? idBits.firstMarkedBit() : -1;
    }

#if DEBUG_VELOCITY
    LOGD("VelocityTracker: addMovement eventTime=%lld, idBits=0x%08x, activePointerId=%d",
            eventTime, idBits.value, mActivePointerId);
    for (BitSet32 iterBits(idBits); !iterBits.isEmpty(); ) {
        uint32_t id = iterBits.firstMarkedBit();
        uint32_t index = idBits.getIndexOfBit(id);
        iterBits.clearBit(id);
        float vx, vy;
        bool available = getVelocity(id, &vx, &vy);
        if (available) {
            LOGD("  %d: position (%0.3f, %0.3f), vx=%0.3f, vy=%0.3f, speed=%0.3f",
                    id, positions[index].x, positions[index].y, vx, vy, sqrtf(vx * vx + vy * vy));
        } else {
            assert(vx == 0 && vy == 0);
            LOGD("  %d: position (%0.3f, %0.3f), velocity not available",
                    id, positions[index].x, positions[index].y);
        }
    }
#endif
}

void VelocityTracker::addMovement(const MotionEvent* event) {
    int32_t actionMasked = event->getActionMasked();

    switch (actionMasked) {
    case AMOTION_EVENT_ACTION_DOWN:
        // Clear all pointers on down before adding the new movement.
        clear();
        break;
    case AMOTION_EVENT_ACTION_POINTER_DOWN: {
        // Start a new movement trace for a pointer that just went down.
        // We do this on down instead of on up because the client may want to query the
        // final velocity for a pointer that just went up.
        BitSet32 downIdBits;
        downIdBits.markBit(event->getActionIndex());
        clearPointers(downIdBits);
        break;
    }
    case AMOTION_EVENT_ACTION_OUTSIDE:
    case AMOTION_EVENT_ACTION_CANCEL:
    case AMOTION_EVENT_ACTION_SCROLL:
    case AMOTION_EVENT_ACTION_UP:
    case AMOTION_EVENT_ACTION_POINTER_UP:
        // Ignore these actions because they do not convey any new information about
        // pointer movement.  We also want to preserve the last known velocity of the pointers.
        // Note that ACTION_UP and ACTION_POINTER_UP always report the last known position
        // of the pointers that went up.  ACTION_POINTER_UP does include the new position of
        // pointers that remained down but we will also receive an ACTION_MOVE with this
        // information if any of them actually moved.  Since we don't know how many pointers
        // will be going up at once it makes sense to just wait for the following ACTION_MOVE
        // before adding the movement.
        return;
    }

    size_t pointerCount = event->getPointerCount();
    if (pointerCount > MAX_POINTERS) {
        pointerCount = MAX_POINTERS;
    }

    BitSet32 idBits;
    for (size_t i = 0; i < pointerCount; i++) {
        idBits.markBit(event->getPointerId(i));
    }

    nsecs_t eventTime;
    Position positions[pointerCount];

    size_t historySize = event->getHistorySize();
    for (size_t h = 0; h < historySize; h++) {
        eventTime = event->getHistoricalEventTime(h);
        for (size_t i = 0; i < pointerCount; i++) {
            positions[i].x = event->getHistoricalX(i, h);
            positions[i].y = event->getHistoricalY(i, h);
        }
        addMovement(eventTime, idBits, positions);
    }

    eventTime = event->getEventTime();
    for (size_t i = 0; i < pointerCount; i++) {
        positions[i].x = event->getX(i);
        positions[i].y = event->getY(i);
    }
    addMovement(eventTime, idBits, positions);
}

bool VelocityTracker::getVelocity(uint32_t id, float* outVx, float* outVy) const {
    const Movement& newestMovement = mMovements[mIndex];
    if (newestMovement.idBits.hasBit(id)) {
        // Find the oldest sample that contains the pointer and that is not older than MAX_AGE.
        nsecs_t minTime = newestMovement.eventTime - MAX_AGE;
        uint32_t oldestIndex = mIndex;
        uint32_t numTouches = 1;
        do {
            uint32_t nextOldestIndex = (oldestIndex == 0 ? HISTORY_SIZE : oldestIndex) - 1;
            const Movement& nextOldestMovement = mMovements[nextOldestIndex];
            if (!nextOldestMovement.idBits.hasBit(id)
                    || nextOldestMovement.eventTime < minTime) {
                break;
            }
            oldestIndex = nextOldestIndex;
        } while (++numTouches < HISTORY_SIZE);

        // Calculate an exponentially weighted moving average of the velocity estimate
        // at different points in time measured relative to the oldest sample.
        // This is essentially an IIR filter.  Newer samples are weighted more heavily
        // than older samples.  Samples at equal time points are weighted more or less
        // equally.
        //
        // One tricky problem is that the sample data may be poorly conditioned.
        // Sometimes samples arrive very close together in time which can cause us to
        // overestimate the velocity at that time point.  Most samples might be measured
        // 16ms apart but some consecutive samples could be only 0.5sm apart because
        // the hardware or driver reports them irregularly or in bursts.
        float accumVx = 0;
        float accumVy = 0;
        uint32_t index = oldestIndex;
        uint32_t samplesUsed = 0;
        const Movement& oldestMovement = mMovements[oldestIndex];
        const Position& oldestPosition =
                oldestMovement.positions[oldestMovement.idBits.getIndexOfBit(id)];
        nsecs_t lastDuration = 0;

        while (numTouches-- > 1) {
            if (++index == HISTORY_SIZE) {
                index = 0;
            }
            const Movement& movement = mMovements[index];
            nsecs_t duration = movement.eventTime - oldestMovement.eventTime;

            // If the duration between samples is small, we may significantly overestimate
            // the velocity.  Consequently, we impose a minimum duration constraint on the
            // samples that we include in the calculation.
            if (duration >= MIN_DURATION) {
                const Position& position = movement.positions[movement.idBits.getIndexOfBit(id)];
                float scale = 1000000000.0f / duration; // one over time delta in seconds
                float vx = (position.x - oldestPosition.x) * scale;
                float vy = (position.y - oldestPosition.y) * scale;

                accumVx = (accumVx * lastDuration + vx * duration) / (duration + lastDuration);
                accumVy = (accumVy * lastDuration + vy * duration) / (duration + lastDuration);

                lastDuration = duration;
                samplesUsed += 1;
            }
        }

        // Make sure we used at least one sample.
        if (samplesUsed != 0) {
            // Scale the velocity linearly if the window of samples is small.
            nsecs_t totalDuration = newestMovement.eventTime - oldestMovement.eventTime;
            if (totalDuration < MIN_WINDOW) {
                float scale = float(totalDuration) / float(MIN_WINDOW);
                accumVx *= scale;
                accumVy *= scale;
            }

            *outVx = accumVx;
            *outVy = accumVy;
            return true;
        }
    }

    // No data available for this pointer.
    *outVx = 0;
    *outVy = 0;
    return false;
}


// --- InputDeviceInfo ---

InputDeviceInfo::InputDeviceInfo() {
    initialize(-1, String8("uninitialized device info"));
}

InputDeviceInfo::InputDeviceInfo(const InputDeviceInfo& other) :
        mId(other.mId), mName(other.mName), mSources(other.mSources),
        mKeyboardType(other.mKeyboardType),
        mMotionRanges(other.mMotionRanges) {
}

InputDeviceInfo::~InputDeviceInfo() {
}

void InputDeviceInfo::initialize(int32_t id, const String8& name) {
    mId = id;
    mName = name;
    mSources = 0;
    mKeyboardType = AINPUT_KEYBOARD_TYPE_NONE;
    mMotionRanges.clear();
}

const InputDeviceInfo::MotionRange* InputDeviceInfo::getMotionRange(
        int32_t axis, uint32_t source) const {
    size_t numRanges = mMotionRanges.size();
    for (size_t i = 0; i < numRanges; i++) {
        const MotionRange& range = mMotionRanges.itemAt(i);
        if (range.axis == axis && range.source == source) {
            return &range;
        }
    }
    return NULL;
}

void InputDeviceInfo::addSource(uint32_t source) {
    mSources |= source;
}

void InputDeviceInfo::addMotionRange(int32_t axis, uint32_t source, float min, float max,
        float flat, float fuzz) {
    MotionRange range = { axis, source, min, max, flat, fuzz };
    mMotionRanges.add(range);
}

void InputDeviceInfo::addMotionRange(const MotionRange& range) {
    mMotionRanges.add(range);
}

} // namespace android
