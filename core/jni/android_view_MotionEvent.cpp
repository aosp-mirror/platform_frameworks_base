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

#define LOG_TAG "MotionEvent-JNI"

#include "JNIHelp.h"

#include <android_runtime/AndroidRuntime.h>
#include <utils/Log.h>
#include <androidfw/Input.h>
#include "android_os_Parcel.h"
#include "android_view_MotionEvent.h"
#include "android_util_Binder.h"
#include "android/graphics/Matrix.h"

#include "SkMatrix.h"


namespace android {

// ----------------------------------------------------------------------------

static struct {
    jclass clazz;

    jmethodID obtain;
    jmethodID recycle;

    jfieldID mNativePtr;
} gMotionEventClassInfo;

static struct {
    jfieldID mPackedAxisBits;
    jfieldID mPackedAxisValues;
    jfieldID x;
    jfieldID y;
    jfieldID pressure;
    jfieldID size;
    jfieldID touchMajor;
    jfieldID touchMinor;
    jfieldID toolMajor;
    jfieldID toolMinor;
    jfieldID orientation;
} gPointerCoordsClassInfo;

static struct {
    jfieldID id;
    jfieldID toolType;
} gPointerPropertiesClassInfo;

// ----------------------------------------------------------------------------

MotionEvent* android_view_MotionEvent_getNativePtr(JNIEnv* env, jobject eventObj) {
    if (!eventObj) {
        return NULL;
    }
    return reinterpret_cast<MotionEvent*>(
            env->GetIntField(eventObj, gMotionEventClassInfo.mNativePtr));
}

static void android_view_MotionEvent_setNativePtr(JNIEnv* env, jobject eventObj,
        MotionEvent* event) {
    env->SetIntField(eventObj, gMotionEventClassInfo.mNativePtr,
            reinterpret_cast<int>(event));
}

jobject android_view_MotionEvent_obtainAsCopy(JNIEnv* env, const MotionEvent* event) {
    jobject eventObj = env->CallStaticObjectMethod(gMotionEventClassInfo.clazz,
            gMotionEventClassInfo.obtain);
    if (env->ExceptionCheck() || !eventObj) {
        ALOGE("An exception occurred while obtaining a motion event.");
        LOGE_EX(env);
        env->ExceptionClear();
        return NULL;
    }

    MotionEvent* destEvent = android_view_MotionEvent_getNativePtr(env, eventObj);
    if (!destEvent) {
        destEvent = new MotionEvent();
        android_view_MotionEvent_setNativePtr(env, eventObj, destEvent);
    }

    destEvent->copyFrom(event, true);
    return eventObj;
}

status_t android_view_MotionEvent_recycle(JNIEnv* env, jobject eventObj) {
    env->CallVoidMethod(eventObj, gMotionEventClassInfo.recycle);
    if (env->ExceptionCheck()) {
        ALOGW("An exception occurred while recycling a motion event.");
        LOGW_EX(env);
        env->ExceptionClear();
        return UNKNOWN_ERROR;
    }
    return OK;
}

// ----------------------------------------------------------------------------

static const jint HISTORY_CURRENT = -0x80000000;

static bool validatePointerCount(JNIEnv* env, jint pointerCount) {
    if (pointerCount < 1) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                "pointerCount must be at least 1");
        return false;
    }
    return true;
}

static bool validatePointerPropertiesArray(JNIEnv* env, jobjectArray pointerPropertiesObjArray,
        size_t pointerCount) {
    if (!pointerPropertiesObjArray) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                "pointerProperties array must not be null");
        return false;
    }
    size_t length = size_t(env->GetArrayLength(pointerPropertiesObjArray));
    if (length < pointerCount) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                "pointerProperties array must be large enough to hold all pointers");
        return false;
    }
    return true;
}

static bool validatePointerCoordsObjArray(JNIEnv* env, jobjectArray pointerCoordsObjArray,
        size_t pointerCount) {
    if (!pointerCoordsObjArray) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                "pointerCoords array must not be null");
        return false;
    }
    size_t length = size_t(env->GetArrayLength(pointerCoordsObjArray));
    if (length < pointerCount) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                "pointerCoords array must be large enough to hold all pointers");
        return false;
    }
    return true;
}

static bool validatePointerIndex(JNIEnv* env, jint pointerIndex, size_t pointerCount) {
    if (pointerIndex < 0 || size_t(pointerIndex) >= pointerCount) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                "pointerIndex out of range");
        return false;
    }
    return true;
}

static bool validateHistoryPos(JNIEnv* env, jint historyPos, size_t historySize) {
    if (historyPos < 0 || size_t(historyPos) >= historySize) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                "historyPos out of range");
        return false;
    }
    return true;
}

static bool validatePointerCoords(JNIEnv* env, jobject pointerCoordsObj) {
    if (!pointerCoordsObj) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                "pointerCoords must not be null");
        return false;
    }
    return true;
}

static bool validatePointerProperties(JNIEnv* env, jobject pointerPropertiesObj) {
    if (!pointerPropertiesObj) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                "pointerProperties must not be null");
        return false;
    }
    return true;
}

static void pointerCoordsToNative(JNIEnv* env, jobject pointerCoordsObj,
        float xOffset, float yOffset, PointerCoords* outRawPointerCoords) {
    outRawPointerCoords->clear();
    outRawPointerCoords->setAxisValue(AMOTION_EVENT_AXIS_X,
            env->GetFloatField(pointerCoordsObj, gPointerCoordsClassInfo.x) - xOffset);
    outRawPointerCoords->setAxisValue(AMOTION_EVENT_AXIS_Y,
            env->GetFloatField(pointerCoordsObj, gPointerCoordsClassInfo.y) - yOffset);
    outRawPointerCoords->setAxisValue(AMOTION_EVENT_AXIS_PRESSURE,
            env->GetFloatField(pointerCoordsObj, gPointerCoordsClassInfo.pressure));
    outRawPointerCoords->setAxisValue(AMOTION_EVENT_AXIS_SIZE,
            env->GetFloatField(pointerCoordsObj, gPointerCoordsClassInfo.size));
    outRawPointerCoords->setAxisValue(AMOTION_EVENT_AXIS_TOUCH_MAJOR,
            env->GetFloatField(pointerCoordsObj, gPointerCoordsClassInfo.touchMajor));
    outRawPointerCoords->setAxisValue(AMOTION_EVENT_AXIS_TOUCH_MINOR,
            env->GetFloatField(pointerCoordsObj, gPointerCoordsClassInfo.touchMinor));
    outRawPointerCoords->setAxisValue(AMOTION_EVENT_AXIS_TOOL_MAJOR,
            env->GetFloatField(pointerCoordsObj, gPointerCoordsClassInfo.toolMajor));
    outRawPointerCoords->setAxisValue(AMOTION_EVENT_AXIS_TOOL_MINOR,
            env->GetFloatField(pointerCoordsObj, gPointerCoordsClassInfo.toolMinor));
    outRawPointerCoords->setAxisValue(AMOTION_EVENT_AXIS_ORIENTATION,
            env->GetFloatField(pointerCoordsObj, gPointerCoordsClassInfo.orientation));

    uint64_t bits = env->GetLongField(pointerCoordsObj, gPointerCoordsClassInfo.mPackedAxisBits);
    if (bits) {
        jfloatArray valuesArray = jfloatArray(env->GetObjectField(pointerCoordsObj,
                gPointerCoordsClassInfo.mPackedAxisValues));
        if (valuesArray) {
            jfloat* values = static_cast<jfloat*>(
                    env->GetPrimitiveArrayCritical(valuesArray, NULL));

            uint32_t index = 0;
            do {
                uint32_t axis = __builtin_ctzll(bits);
                uint64_t axisBit = 1LL << axis;
                bits &= ~axisBit;
                outRawPointerCoords->setAxisValue(axis, values[index++]);
            } while (bits);

            env->ReleasePrimitiveArrayCritical(valuesArray, values, JNI_ABORT);
            env->DeleteLocalRef(valuesArray);
        }
    }
}

static jfloatArray obtainPackedAxisValuesArray(JNIEnv* env, uint32_t minSize,
        jobject outPointerCoordsObj) {
    jfloatArray outValuesArray = jfloatArray(env->GetObjectField(outPointerCoordsObj,
            gPointerCoordsClassInfo.mPackedAxisValues));
    if (outValuesArray) {
        uint32_t size = env->GetArrayLength(outValuesArray);
        if (minSize <= size) {
            return outValuesArray;
        }
        env->DeleteLocalRef(outValuesArray);
    }
    uint32_t size = 8;
    while (size < minSize) {
        size *= 2;
    }
    outValuesArray = env->NewFloatArray(size);
    env->SetObjectField(outPointerCoordsObj,
            gPointerCoordsClassInfo.mPackedAxisValues, outValuesArray);
    return outValuesArray;
}

static void pointerCoordsFromNative(JNIEnv* env, const PointerCoords* rawPointerCoords,
        float xOffset, float yOffset, jobject outPointerCoordsObj) {
    env->SetFloatField(outPointerCoordsObj, gPointerCoordsClassInfo.x,
            rawPointerCoords->getAxisValue(AMOTION_EVENT_AXIS_X) + xOffset);
    env->SetFloatField(outPointerCoordsObj, gPointerCoordsClassInfo.y,
            rawPointerCoords->getAxisValue(AMOTION_EVENT_AXIS_Y) + yOffset);
    env->SetFloatField(outPointerCoordsObj, gPointerCoordsClassInfo.pressure,
            rawPointerCoords->getAxisValue(AMOTION_EVENT_AXIS_PRESSURE));
    env->SetFloatField(outPointerCoordsObj, gPointerCoordsClassInfo.size,
            rawPointerCoords->getAxisValue(AMOTION_EVENT_AXIS_SIZE));
    env->SetFloatField(outPointerCoordsObj, gPointerCoordsClassInfo.touchMajor,
            rawPointerCoords->getAxisValue(AMOTION_EVENT_AXIS_TOUCH_MAJOR));
    env->SetFloatField(outPointerCoordsObj, gPointerCoordsClassInfo.touchMinor,
            rawPointerCoords->getAxisValue(AMOTION_EVENT_AXIS_TOUCH_MINOR));
    env->SetFloatField(outPointerCoordsObj, gPointerCoordsClassInfo.toolMajor,
            rawPointerCoords->getAxisValue(AMOTION_EVENT_AXIS_TOOL_MAJOR));
    env->SetFloatField(outPointerCoordsObj, gPointerCoordsClassInfo.toolMinor,
            rawPointerCoords->getAxisValue(AMOTION_EVENT_AXIS_TOOL_MINOR));
    env->SetFloatField(outPointerCoordsObj, gPointerCoordsClassInfo.orientation,
            rawPointerCoords->getAxisValue(AMOTION_EVENT_AXIS_ORIENTATION));

    const uint64_t unpackedAxisBits = 0
            | (1LL << AMOTION_EVENT_AXIS_X)
            | (1LL << AMOTION_EVENT_AXIS_Y)
            | (1LL << AMOTION_EVENT_AXIS_PRESSURE)
            | (1LL << AMOTION_EVENT_AXIS_SIZE)
            | (1LL << AMOTION_EVENT_AXIS_TOUCH_MAJOR)
            | (1LL << AMOTION_EVENT_AXIS_TOUCH_MINOR)
            | (1LL << AMOTION_EVENT_AXIS_TOOL_MAJOR)
            | (1LL << AMOTION_EVENT_AXIS_TOOL_MINOR)
            | (1LL << AMOTION_EVENT_AXIS_ORIENTATION);

    uint64_t outBits = 0;
    uint64_t remainingBits = rawPointerCoords->bits & ~unpackedAxisBits;
    if (remainingBits) {
        uint32_t packedAxesCount = __builtin_popcountll(remainingBits);
        jfloatArray outValuesArray = obtainPackedAxisValuesArray(env, packedAxesCount,
                outPointerCoordsObj);
        if (!outValuesArray) {
            return; // OOM
        }

        jfloat* outValues = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(
                outValuesArray, NULL));

        const float* values = rawPointerCoords->values;
        uint32_t index = 0;
        do {
            uint32_t axis = __builtin_ctzll(remainingBits);
            uint64_t axisBit = 1LL << axis;
            remainingBits &= ~axisBit;
            outBits |= axisBit;
            outValues[index++] = rawPointerCoords->getAxisValue(axis);
        } while (remainingBits);

        env->ReleasePrimitiveArrayCritical(outValuesArray, outValues, 0);
        env->DeleteLocalRef(outValuesArray);
    }
    env->SetLongField(outPointerCoordsObj, gPointerCoordsClassInfo.mPackedAxisBits, outBits);
}

static void pointerPropertiesToNative(JNIEnv* env, jobject pointerPropertiesObj,
        PointerProperties* outPointerProperties) {
    outPointerProperties->clear();
    outPointerProperties->id = env->GetIntField(pointerPropertiesObj,
            gPointerPropertiesClassInfo.id);
    outPointerProperties->toolType = env->GetIntField(pointerPropertiesObj,
            gPointerPropertiesClassInfo.toolType);
}

static void pointerPropertiesFromNative(JNIEnv* env, const PointerProperties* pointerProperties,
        jobject outPointerPropertiesObj) {
    env->SetIntField(outPointerPropertiesObj, gPointerPropertiesClassInfo.id,
            pointerProperties->id);
    env->SetIntField(outPointerPropertiesObj, gPointerPropertiesClassInfo.toolType,
            pointerProperties->toolType);
}


// ----------------------------------------------------------------------------

static jint android_view_MotionEvent_nativeInitialize(JNIEnv* env, jclass clazz,
        jint nativePtr,
        jint deviceId, jint source, jint action, jint flags, jint edgeFlags,
        jint metaState, jint buttonState,
        jfloat xOffset, jfloat yOffset, jfloat xPrecision, jfloat yPrecision,
        jlong downTimeNanos, jlong eventTimeNanos,
        jint pointerCount, jobjectArray pointerPropertiesObjArray,
        jobjectArray pointerCoordsObjArray) {
    if (!validatePointerCount(env, pointerCount)
            || !validatePointerPropertiesArray(env, pointerPropertiesObjArray, pointerCount)
            || !validatePointerCoordsObjArray(env, pointerCoordsObjArray, pointerCount)) {
        return 0;
    }

    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    if (!event) {
        event = new MotionEvent();
    }

    PointerProperties pointerProperties[pointerCount];
    PointerCoords rawPointerCoords[pointerCount];

    for (jint i = 0; i < pointerCount; i++) {
        jobject pointerPropertiesObj = env->GetObjectArrayElement(pointerPropertiesObjArray, i);
        if (!pointerPropertiesObj) {
            goto Error;
        }
        pointerPropertiesToNative(env, pointerPropertiesObj, &pointerProperties[i]);
        env->DeleteLocalRef(pointerPropertiesObj);

        jobject pointerCoordsObj = env->GetObjectArrayElement(pointerCoordsObjArray, i);
        if (!pointerCoordsObj) {
            jniThrowNullPointerException(env, "pointerCoords");
            goto Error;
        }
        pointerCoordsToNative(env, pointerCoordsObj, xOffset, yOffset, &rawPointerCoords[i]);
        env->DeleteLocalRef(pointerCoordsObj);
    }

    event->initialize(deviceId, source, action, flags, edgeFlags, metaState, buttonState,
            xOffset, yOffset, xPrecision, yPrecision,
            downTimeNanos, eventTimeNanos, pointerCount, pointerProperties, rawPointerCoords);

    return reinterpret_cast<jint>(event);

Error:
    if (!nativePtr) {
        delete event;
    }
    return 0;
}

static jint android_view_MotionEvent_nativeCopy(JNIEnv* env, jclass clazz,
        jint destNativePtr, jint sourceNativePtr, jboolean keepHistory) {
    MotionEvent* destEvent = reinterpret_cast<MotionEvent*>(destNativePtr);
    if (!destEvent) {
        destEvent = new MotionEvent();
    }
    MotionEvent* sourceEvent = reinterpret_cast<MotionEvent*>(sourceNativePtr);
    destEvent->copyFrom(sourceEvent, keepHistory);
    return reinterpret_cast<jint>(destEvent);
}

static void android_view_MotionEvent_nativeDispose(JNIEnv* env, jclass clazz,
        jint nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    delete event;
}

static void android_view_MotionEvent_nativeAddBatch(JNIEnv* env, jclass clazz,
        jint nativePtr, jlong eventTimeNanos, jobjectArray pointerCoordsObjArray,
        jint metaState) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    size_t pointerCount = event->getPointerCount();
    if (!validatePointerCoordsObjArray(env, pointerCoordsObjArray, pointerCount)) {
        return;
    }

    PointerCoords rawPointerCoords[pointerCount];

    for (size_t i = 0; i < pointerCount; i++) {
        jobject pointerCoordsObj = env->GetObjectArrayElement(pointerCoordsObjArray, i);
        if (!pointerCoordsObj) {
            jniThrowNullPointerException(env, "pointerCoords");
            return;
        }
        pointerCoordsToNative(env, pointerCoordsObj,
                event->getXOffset(), event->getYOffset(), &rawPointerCoords[i]);
        env->DeleteLocalRef(pointerCoordsObj);
    }

    event->addSample(eventTimeNanos, rawPointerCoords);
    event->setMetaState(event->getMetaState() | metaState);
}

static jint android_view_MotionEvent_nativeGetDeviceId(JNIEnv* env, jclass clazz,
        jint nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getDeviceId();
}

static jint android_view_MotionEvent_nativeGetSource(JNIEnv* env, jclass clazz,
        jint nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getSource();
}

static void android_view_MotionEvent_nativeSetSource(JNIEnv* env, jclass clazz,
        jint nativePtr, jint source) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    event->setSource(source);
}

static jint android_view_MotionEvent_nativeGetAction(JNIEnv* env, jclass clazz,
        jint nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getAction();
}

static void android_view_MotionEvent_nativeSetAction(JNIEnv* env, jclass clazz,
        jint nativePtr, jint action) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    event->setAction(action);
}

static jboolean android_view_MotionEvent_nativeIsTouchEvent(JNIEnv* env, jclass clazz,
        jint nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->isTouchEvent();
}

static jint android_view_MotionEvent_nativeGetFlags(JNIEnv* env, jclass clazz,
        jint nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getFlags();
}

static void android_view_MotionEvent_nativeSetFlags(JNIEnv* env, jclass clazz,
        jint nativePtr, jint flags) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    event->setFlags(flags);
}

static jint android_view_MotionEvent_nativeGetEdgeFlags(JNIEnv* env, jclass clazz,
        jint nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getEdgeFlags();
}

static void android_view_MotionEvent_nativeSetEdgeFlags(JNIEnv* env, jclass clazz,
        jint nativePtr, jint edgeFlags) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    event->setEdgeFlags(edgeFlags);
}

static jint android_view_MotionEvent_nativeGetMetaState(JNIEnv* env, jclass clazz,
        jint nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getMetaState();
}

static jint android_view_MotionEvent_nativeGetButtonState(JNIEnv* env, jclass clazz,
        jint nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getButtonState();
}

static void android_view_MotionEvent_nativeOffsetLocation(JNIEnv* env, jclass clazz,
        jint nativePtr, jfloat deltaX, jfloat deltaY) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->offsetLocation(deltaX, deltaY);
}

static jfloat android_view_MotionEvent_nativeGetXOffset(JNIEnv* env, jclass clazz,
        jint nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getXOffset();
}

static jfloat android_view_MotionEvent_nativeGetYOffset(JNIEnv* env, jclass clazz,
        jint nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getYOffset();
}

static jfloat android_view_MotionEvent_nativeGetXPrecision(JNIEnv* env, jclass clazz,
        jint nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getXPrecision();
}

static jfloat android_view_MotionEvent_nativeGetYPrecision(JNIEnv* env, jclass clazz,
        jint nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getYPrecision();
}

static jlong android_view_MotionEvent_nativeGetDownTimeNanos(JNIEnv* env, jclass clazz,
        jint nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getDownTime();
}

static void android_view_MotionEvent_nativeSetDownTimeNanos(JNIEnv* env, jclass clazz,
        jint nativePtr, jlong downTimeNanos) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    event->setDownTime(downTimeNanos);
}

static jint android_view_MotionEvent_nativeGetPointerCount(JNIEnv* env, jclass clazz,
        jint nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return jint(event->getPointerCount());
}

static jint android_view_MotionEvent_nativeGetPointerId(JNIEnv* env, jclass clazz,
        jint nativePtr, jint pointerIndex) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    size_t pointerCount = event->getPointerCount();
    if (!validatePointerIndex(env, pointerIndex, pointerCount)) {
        return -1;
    }
    return event->getPointerId(pointerIndex);
}

static jint android_view_MotionEvent_nativeGetToolType(JNIEnv* env, jclass clazz,
        jint nativePtr, jint pointerIndex) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    size_t pointerCount = event->getPointerCount();
    if (!validatePointerIndex(env, pointerIndex, pointerCount)) {
        return -1;
    }
    return event->getToolType(pointerIndex);
}

static jint android_view_MotionEvent_nativeFindPointerIndex(JNIEnv* env, jclass clazz,
        jint nativePtr, jint pointerId) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return jint(event->findPointerIndex(pointerId));
}

static jint android_view_MotionEvent_nativeGetHistorySize(JNIEnv* env, jclass clazz,
        jint nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return jint(event->getHistorySize());
}

static jlong android_view_MotionEvent_nativeGetEventTimeNanos(JNIEnv* env, jclass clazz,
        jint nativePtr, jint historyPos) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    if (historyPos == HISTORY_CURRENT) {
        return event->getEventTime();
    } else {
        size_t historySize = event->getHistorySize();
        if (!validateHistoryPos(env, historyPos, historySize)) {
            return 0;
        }
        return event->getHistoricalEventTime(historyPos);
    }
}

static jfloat android_view_MotionEvent_nativeGetRawAxisValue(JNIEnv* env, jclass clazz,
        jint nativePtr, jint axis, jint pointerIndex, jint historyPos) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    size_t pointerCount = event->getPointerCount();
    if (!validatePointerIndex(env, pointerIndex, pointerCount)) {
        return 0;
    }

    if (historyPos == HISTORY_CURRENT) {
        return event->getRawAxisValue(axis, pointerIndex);
    } else {
        size_t historySize = event->getHistorySize();
        if (!validateHistoryPos(env, historyPos, historySize)) {
            return 0;
        }
        return event->getHistoricalRawAxisValue(axis, pointerIndex, historyPos);
    }
}

static jfloat android_view_MotionEvent_nativeGetAxisValue(JNIEnv* env, jclass clazz,
        jint nativePtr, jint axis, jint pointerIndex, jint historyPos) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    size_t pointerCount = event->getPointerCount();
    if (!validatePointerIndex(env, pointerIndex, pointerCount)) {
        return 0;
    }

    if (historyPos == HISTORY_CURRENT) {
        return event->getAxisValue(axis, pointerIndex);
    } else {
        size_t historySize = event->getHistorySize();
        if (!validateHistoryPos(env, historyPos, historySize)) {
            return 0;
        }
        return event->getHistoricalAxisValue(axis, pointerIndex, historyPos);
    }
}

static void android_view_MotionEvent_nativeGetPointerCoords(JNIEnv* env, jclass clazz,
        jint nativePtr, jint pointerIndex, jint historyPos, jobject outPointerCoordsObj) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    size_t pointerCount = event->getPointerCount();
    if (!validatePointerIndex(env, pointerIndex, pointerCount)
            || !validatePointerCoords(env, outPointerCoordsObj)) {
        return;
    }

    const PointerCoords* rawPointerCoords;
    if (historyPos == HISTORY_CURRENT) {
        rawPointerCoords = event->getRawPointerCoords(pointerIndex);
    } else {
        size_t historySize = event->getHistorySize();
        if (!validateHistoryPos(env, historyPos, historySize)) {
            return;
        }
        rawPointerCoords = event->getHistoricalRawPointerCoords(pointerIndex, historyPos);
    }
    pointerCoordsFromNative(env, rawPointerCoords, event->getXOffset(), event->getYOffset(),
            outPointerCoordsObj);
}

static void android_view_MotionEvent_nativeGetPointerProperties(JNIEnv* env, jclass clazz,
        jint nativePtr, jint pointerIndex, jobject outPointerPropertiesObj) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    size_t pointerCount = event->getPointerCount();
    if (!validatePointerIndex(env, pointerIndex, pointerCount)
            || !validatePointerProperties(env, outPointerPropertiesObj)) {
        return;
    }

    const PointerProperties* pointerProperties = event->getPointerProperties(pointerIndex);
    pointerPropertiesFromNative(env, pointerProperties, outPointerPropertiesObj);
}

static void android_view_MotionEvent_nativeScale(JNIEnv* env, jclass clazz,
        jint nativePtr, jfloat scale) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    event->scale(scale);
}

static void android_view_MotionEvent_nativeTransform(JNIEnv* env, jclass clazz,
        jint nativePtr, jobject matrixObj) {
    SkMatrix* matrix = android_graphics_Matrix_getSkMatrix(env, matrixObj);
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    event->transform(matrix);
}

static jint android_view_MotionEvent_nativeReadFromParcel(JNIEnv* env, jclass clazz,
        jint nativePtr, jobject parcelObj) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    if (!event) {
        event = new MotionEvent();
    }

    Parcel* parcel = parcelForJavaObject(env, parcelObj);

    status_t status = event->readFromParcel(parcel);
    if (status) {
        if (!nativePtr) {
            delete event;
        }
        jniThrowRuntimeException(env, "Failed to read MotionEvent parcel.");
        return 0;
    }
    return reinterpret_cast<jint>(event);
}

static void android_view_MotionEvent_nativeWriteToParcel(JNIEnv* env, jclass clazz,
        jint nativePtr, jobject parcelObj) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    Parcel* parcel = parcelForJavaObject(env, parcelObj);

    status_t status = event->writeToParcel(parcel);
    if (status) {
        jniThrowRuntimeException(env, "Failed to write MotionEvent parcel.");
    }
}

// ----------------------------------------------------------------------------

static JNINativeMethod gMotionEventMethods[] = {
    /* name, signature, funcPtr */
    { "nativeInitialize",
            "(IIIIIIIIFFFFJJI[Landroid/view/MotionEvent$PointerProperties;"
                    "[Landroid/view/MotionEvent$PointerCoords;)I",
            (void*)android_view_MotionEvent_nativeInitialize },
    { "nativeCopy",
            "(IIZ)I",
            (void*)android_view_MotionEvent_nativeCopy },
    { "nativeDispose",
            "(I)V",
            (void*)android_view_MotionEvent_nativeDispose },
    { "nativeAddBatch",
            "(IJ[Landroid/view/MotionEvent$PointerCoords;I)V",
            (void*)android_view_MotionEvent_nativeAddBatch },
    { "nativeGetDeviceId",
            "(I)I",
            (void*)android_view_MotionEvent_nativeGetDeviceId },
    { "nativeGetSource",
            "(I)I",
            (void*)android_view_MotionEvent_nativeGetSource },
    { "nativeSetSource",
            "(II)I",
            (void*)android_view_MotionEvent_nativeSetSource },
    { "nativeGetAction",
            "(I)I",
            (void*)android_view_MotionEvent_nativeGetAction },
    { "nativeSetAction",
            "(II)V",
            (void*)android_view_MotionEvent_nativeSetAction },
    { "nativeIsTouchEvent",
            "(I)Z",
            (void*)android_view_MotionEvent_nativeIsTouchEvent },
    { "nativeGetFlags",
            "(I)I",
            (void*)android_view_MotionEvent_nativeGetFlags },
    { "nativeSetFlags",
            "(II)V",
            (void*)android_view_MotionEvent_nativeSetFlags },
    { "nativeGetEdgeFlags",
            "(I)I",
            (void*)android_view_MotionEvent_nativeGetEdgeFlags },
    { "nativeSetEdgeFlags",
            "(II)V",
            (void*)android_view_MotionEvent_nativeSetEdgeFlags },
    { "nativeGetMetaState",
            "(I)I",
            (void*)android_view_MotionEvent_nativeGetMetaState },
    { "nativeGetButtonState",
            "(I)I",
            (void*)android_view_MotionEvent_nativeGetButtonState },
    { "nativeOffsetLocation",
            "(IFF)V",
            (void*)android_view_MotionEvent_nativeOffsetLocation },
    { "nativeGetXOffset",
            "(I)F",
            (void*)android_view_MotionEvent_nativeGetXOffset },
    { "nativeGetYOffset",
            "(I)F",
            (void*)android_view_MotionEvent_nativeGetYOffset },
    { "nativeGetXPrecision",
            "(I)F",
            (void*)android_view_MotionEvent_nativeGetXPrecision },
    { "nativeGetYPrecision",
            "(I)F",
            (void*)android_view_MotionEvent_nativeGetYPrecision },
    { "nativeGetDownTimeNanos",
            "(I)J",
            (void*)android_view_MotionEvent_nativeGetDownTimeNanos },
    { "nativeSetDownTimeNanos",
            "(IJ)V",
            (void*)android_view_MotionEvent_nativeSetDownTimeNanos },
    { "nativeGetPointerCount",
            "(I)I",
            (void*)android_view_MotionEvent_nativeGetPointerCount },
    { "nativeGetPointerId",
            "(II)I",
            (void*)android_view_MotionEvent_nativeGetPointerId },
    { "nativeGetToolType",
            "(II)I",
            (void*)android_view_MotionEvent_nativeGetToolType },
    { "nativeFindPointerIndex",
            "(II)I",
            (void*)android_view_MotionEvent_nativeFindPointerIndex },
    { "nativeGetHistorySize",
            "(I)I",
            (void*)android_view_MotionEvent_nativeGetHistorySize },
    { "nativeGetEventTimeNanos",
            "(II)J",
            (void*)android_view_MotionEvent_nativeGetEventTimeNanos },
    { "nativeGetRawAxisValue",
            "(IIII)F",
            (void*)android_view_MotionEvent_nativeGetRawAxisValue },
    { "nativeGetAxisValue",
            "(IIII)F",
            (void*)android_view_MotionEvent_nativeGetAxisValue },
    { "nativeGetPointerCoords",
            "(IIILandroid/view/MotionEvent$PointerCoords;)V",
            (void*)android_view_MotionEvent_nativeGetPointerCoords },
    { "nativeGetPointerProperties",
            "(IILandroid/view/MotionEvent$PointerProperties;)V",
            (void*)android_view_MotionEvent_nativeGetPointerProperties },
    { "nativeScale",
            "(IF)V",
            (void*)android_view_MotionEvent_nativeScale },
    { "nativeTransform",
            "(ILandroid/graphics/Matrix;)V",
            (void*)android_view_MotionEvent_nativeTransform },
    { "nativeReadFromParcel",
            "(ILandroid/os/Parcel;)I",
            (void*)android_view_MotionEvent_nativeReadFromParcel },
    { "nativeWriteToParcel",
            "(ILandroid/os/Parcel;)V",
            (void*)android_view_MotionEvent_nativeWriteToParcel },
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className);

#define GET_STATIC_METHOD_ID(var, clazz, methodName, fieldDescriptor) \
        var = env->GetStaticMethodID(clazz, methodName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find static method" methodName);

#define GET_METHOD_ID(var, clazz, methodName, fieldDescriptor) \
        var = env->GetMethodID(clazz, methodName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find method" methodName);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find field " fieldName);

int register_android_view_MotionEvent(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/view/MotionEvent",
            gMotionEventMethods, NELEM(gMotionEventMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    FIND_CLASS(gMotionEventClassInfo.clazz, "android/view/MotionEvent");
    gMotionEventClassInfo.clazz = jclass(env->NewGlobalRef(gMotionEventClassInfo.clazz));

    GET_STATIC_METHOD_ID(gMotionEventClassInfo.obtain, gMotionEventClassInfo.clazz,
            "obtain", "()Landroid/view/MotionEvent;");
    GET_METHOD_ID(gMotionEventClassInfo.recycle, gMotionEventClassInfo.clazz,
            "recycle", "()V");
    GET_FIELD_ID(gMotionEventClassInfo.mNativePtr, gMotionEventClassInfo.clazz,
            "mNativePtr", "I");

    jclass clazz;
    FIND_CLASS(clazz, "android/view/MotionEvent$PointerCoords");

    GET_FIELD_ID(gPointerCoordsClassInfo.mPackedAxisBits, clazz,
            "mPackedAxisBits", "J");
    GET_FIELD_ID(gPointerCoordsClassInfo.mPackedAxisValues, clazz,
            "mPackedAxisValues", "[F");
    GET_FIELD_ID(gPointerCoordsClassInfo.x, clazz,
            "x", "F");
    GET_FIELD_ID(gPointerCoordsClassInfo.y, clazz,
            "y", "F");
    GET_FIELD_ID(gPointerCoordsClassInfo.pressure, clazz,
            "pressure", "F");
    GET_FIELD_ID(gPointerCoordsClassInfo.size, clazz,
            "size", "F");
    GET_FIELD_ID(gPointerCoordsClassInfo.touchMajor, clazz,
            "touchMajor", "F");
    GET_FIELD_ID(gPointerCoordsClassInfo.touchMinor, clazz,
            "touchMinor", "F");
    GET_FIELD_ID(gPointerCoordsClassInfo.toolMajor, clazz,
            "toolMajor", "F");
    GET_FIELD_ID(gPointerCoordsClassInfo.toolMinor, clazz,
            "toolMinor", "F");
    GET_FIELD_ID(gPointerCoordsClassInfo.orientation, clazz,
            "orientation", "F");

    FIND_CLASS(clazz, "android/view/MotionEvent$PointerProperties");

    GET_FIELD_ID(gPointerPropertiesClassInfo.id, clazz,
            "id", "I");
    GET_FIELD_ID(gPointerPropertiesClassInfo.toolType, clazz,
            "toolType", "I");

    return 0;
}

} // namespace android
