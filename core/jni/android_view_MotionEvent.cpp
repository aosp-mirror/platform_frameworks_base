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

#include <nativehelper/JNIHelp.h>

#include <android/graphics/matrix.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <attestation/HmacKeyManager.h>
#include <gui/constants.h>
#include <input/Input.h>
#include <nativehelper/ScopedUtfChars.h>
#include <utils/Log.h>
#include "android_os_Parcel.h"
#include "android_util_Binder.h"
#include "android_view_MotionEvent.h"

#include "core_jni_helpers.h"

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
    jfieldID relativeX;
    jfieldID relativeY;
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
            env->GetLongField(eventObj, gMotionEventClassInfo.mNativePtr));
}

static void android_view_MotionEvent_setNativePtr(JNIEnv* env, jobject eventObj,
        MotionEvent* event) {
    env->SetLongField(eventObj, gMotionEventClassInfo.mNativePtr,
            reinterpret_cast<jlong>(event));
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
    outRawPointerCoords->setAxisValue(AMOTION_EVENT_AXIS_RELATIVE_X,
                                      env->GetFloatField(pointerCoordsObj,
                                                         gPointerCoordsClassInfo.relativeX));
    outRawPointerCoords->setAxisValue(AMOTION_EVENT_AXIS_RELATIVE_Y,
                                      env->GetFloatField(pointerCoordsObj,
                                                         gPointerCoordsClassInfo.relativeY));

    BitSet64 bits =
            BitSet64(env->GetLongField(pointerCoordsObj, gPointerCoordsClassInfo.mPackedAxisBits));
    if (!bits.isEmpty()) {
        jfloatArray valuesArray = jfloatArray(env->GetObjectField(pointerCoordsObj,
                gPointerCoordsClassInfo.mPackedAxisValues));
        if (valuesArray) {
            jfloat* values = static_cast<jfloat*>(
                    env->GetPrimitiveArrayCritical(valuesArray, NULL));

            uint32_t index = 0;
            do {
                uint32_t axis = bits.clearFirstMarkedBit();
                outRawPointerCoords->setAxisValue(axis, values[index++]);
            } while (!bits.isEmpty());

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
                                    ui::Transform transform, jobject outPointerCoordsObj) {
    float rawX = rawPointerCoords->getAxisValue(AMOTION_EVENT_AXIS_X);
    float rawY = rawPointerCoords->getAxisValue(AMOTION_EVENT_AXIS_Y);
    vec2 transformed = transform.transform(rawX, rawY);

    float rawRelX = rawPointerCoords->getAxisValue(AMOTION_EVENT_AXIS_RELATIVE_X);
    float rawRelY = rawPointerCoords->getAxisValue(AMOTION_EVENT_AXIS_RELATIVE_Y);
    // Apply only rotation and scale, not translation.
    const vec2 transformedOrigin = transform.transform(0, 0);
    const vec2 transformedRel = transform.transform(rawRelX, rawRelY) - transformedOrigin;

    env->SetFloatField(outPointerCoordsObj, gPointerCoordsClassInfo.x, transformed.x);
    env->SetFloatField(outPointerCoordsObj, gPointerCoordsClassInfo.y, transformed.y);
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
    env->SetFloatField(outPointerCoordsObj, gPointerCoordsClassInfo.relativeX, transformedRel.x);
    env->SetFloatField(outPointerCoordsObj, gPointerCoordsClassInfo.relativeY, transformedRel.y);

    uint64_t outBits = 0;
    BitSet64 bits = BitSet64(rawPointerCoords->bits);
    bits.clearBit(AMOTION_EVENT_AXIS_X);
    bits.clearBit(AMOTION_EVENT_AXIS_Y);
    bits.clearBit(AMOTION_EVENT_AXIS_PRESSURE);
    bits.clearBit(AMOTION_EVENT_AXIS_SIZE);
    bits.clearBit(AMOTION_EVENT_AXIS_TOUCH_MAJOR);
    bits.clearBit(AMOTION_EVENT_AXIS_TOUCH_MINOR);
    bits.clearBit(AMOTION_EVENT_AXIS_TOOL_MAJOR);
    bits.clearBit(AMOTION_EVENT_AXIS_TOOL_MINOR);
    bits.clearBit(AMOTION_EVENT_AXIS_ORIENTATION);
    bits.clearBit(AMOTION_EVENT_AXIS_RELATIVE_X);
    bits.clearBit(AMOTION_EVENT_AXIS_RELATIVE_Y);
    if (!bits.isEmpty()) {
        uint32_t packedAxesCount = bits.count();
        jfloatArray outValuesArray = obtainPackedAxisValuesArray(env, packedAxesCount,
                outPointerCoordsObj);
        if (!outValuesArray) {
            return; // OOM
        }

        jfloat* outValues = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(
                outValuesArray, NULL));

        uint32_t index = 0;
        do {
            uint32_t axis = bits.clearFirstMarkedBit();
            outBits |= BitSet64::valueForBit(axis);
            outValues[index++] = rawPointerCoords->getAxisValue(axis);
        } while (!bits.isEmpty());

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

static jlong android_view_MotionEvent_nativeInitialize(
        JNIEnv* env, jclass clazz, jlong nativePtr, jint deviceId, jint source, jint displayId,
        jint action, jint flags, jint edgeFlags, jint metaState, jint buttonState,
        jint classification, jfloat xOffset, jfloat yOffset, jfloat xPrecision, jfloat yPrecision,
        jlong downTimeNanos, jlong eventTimeNanos, jint pointerCount,
        jobjectArray pointerPropertiesObjArray, jobjectArray pointerCoordsObjArray) {
    if (!validatePointerCount(env, pointerCount)
            || !validatePointerPropertiesArray(env, pointerPropertiesObjArray, pointerCount)
            || !validatePointerCoordsObjArray(env, pointerCoordsObjArray, pointerCount)) {
        return 0;
    }

    std::unique_ptr<MotionEvent> event;
    if (nativePtr) {
        event = std::unique_ptr<MotionEvent>(reinterpret_cast<MotionEvent*>(nativePtr));
    } else {
        event = std::make_unique<MotionEvent>();
    }

    PointerProperties pointerProperties[pointerCount];
    PointerCoords rawPointerCoords[pointerCount];

    for (jint i = 0; i < pointerCount; i++) {
        jobject pointerPropertiesObj = env->GetObjectArrayElement(pointerPropertiesObjArray, i);
        if (!pointerPropertiesObj) {
            return 0;
        }
        pointerPropertiesToNative(env, pointerPropertiesObj, &pointerProperties[i]);
        env->DeleteLocalRef(pointerPropertiesObj);

        jobject pointerCoordsObj = env->GetObjectArrayElement(pointerCoordsObjArray, i);
        if (!pointerCoordsObj) {
            jniThrowNullPointerException(env, "pointerCoords");
            return 0;
        }
        pointerCoordsToNative(env, pointerCoordsObj, xOffset, yOffset, &rawPointerCoords[i]);
        env->DeleteLocalRef(pointerCoordsObj);
    }

    ui::Transform transform;
    transform.set(xOffset, yOffset);
    event->initialize(InputEvent::nextId(), deviceId, source, displayId, INVALID_HMAC, action, 0,
                      flags, edgeFlags, metaState, buttonState,
                      static_cast<MotionClassification>(classification), transform, xPrecision,
                      yPrecision, AMOTION_EVENT_INVALID_CURSOR_POSITION,
                      AMOTION_EVENT_INVALID_CURSOR_POSITION, INVALID_DISPLAY_SIZE,
                      INVALID_DISPLAY_SIZE, downTimeNanos, eventTimeNanos, pointerCount,
                      pointerProperties, rawPointerCoords);

    return reinterpret_cast<jlong>(event.release());
}

static void android_view_MotionEvent_nativeDispose(JNIEnv* env, jclass clazz,
        jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    delete event;
}

static void android_view_MotionEvent_nativeAddBatch(JNIEnv* env, jclass clazz,
        jlong nativePtr, jlong eventTimeNanos, jobjectArray pointerCoordsObjArray,
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

static void android_view_MotionEvent_nativeGetPointerCoords(JNIEnv* env, jclass clazz,
        jlong nativePtr, jint pointerIndex, jint historyPos, jobject outPointerCoordsObj) {
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
    pointerCoordsFromNative(env, rawPointerCoords, event->getTransform(), outPointerCoordsObj);
}

static void android_view_MotionEvent_nativeGetPointerProperties(JNIEnv* env, jclass clazz,
        jlong nativePtr, jint pointerIndex, jobject outPointerPropertiesObj) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    size_t pointerCount = event->getPointerCount();
    if (!validatePointerIndex(env, pointerIndex, pointerCount)
            || !validatePointerProperties(env, outPointerPropertiesObj)) {
        return;
    }

    const PointerProperties* pointerProperties = event->getPointerProperties(pointerIndex);
    pointerPropertiesFromNative(env, pointerProperties, outPointerPropertiesObj);
}

static jlong android_view_MotionEvent_nativeReadFromParcel(JNIEnv* env, jclass clazz,
        jlong nativePtr, jobject parcelObj) {
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
    return reinterpret_cast<jlong>(event);
}

static void android_view_MotionEvent_nativeWriteToParcel(JNIEnv* env, jclass clazz,
        jlong nativePtr, jobject parcelObj) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    Parcel* parcel = parcelForJavaObject(env, parcelObj);

    status_t status = event->writeToParcel(parcel);
    if (status) {
        jniThrowRuntimeException(env, "Failed to write MotionEvent parcel.");
    }
}

static jstring android_view_MotionEvent_nativeAxisToString(JNIEnv* env, jclass clazz,
        jint axis) {
    return env->NewStringUTF(MotionEvent::getLabel(static_cast<int32_t>(axis)));
}

static jint android_view_MotionEvent_nativeAxisFromString(JNIEnv* env, jclass clazz,
        jstring label) {
    ScopedUtfChars axisLabel(env, label);
    return static_cast<jint>(MotionEvent::getAxisFromLabel(axisLabel.c_str()));
}

// ---------------- @FastNative ----------------------------------

static jint android_view_MotionEvent_nativeGetPointerId(JNIEnv* env, jclass clazz,
        jlong nativePtr, jint pointerIndex) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    size_t pointerCount = event->getPointerCount();
    if (!validatePointerIndex(env, pointerIndex, pointerCount)) {
        return -1;
    }
    return event->getPointerId(pointerIndex);
}

static jint android_view_MotionEvent_nativeGetToolType(JNIEnv* env, jclass clazz,
        jlong nativePtr, jint pointerIndex) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    size_t pointerCount = event->getPointerCount();
    if (!validatePointerIndex(env, pointerIndex, pointerCount)) {
        return -1;
    }
    return event->getToolType(pointerIndex);
}

static jlong android_view_MotionEvent_nativeGetEventTimeNanos(JNIEnv* env, jclass clazz,
        jlong nativePtr, jint historyPos) {
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
        jlong nativePtr, jint axis,
        jint pointerIndex, jint historyPos) {
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
        jlong nativePtr, jint axis, jint pointerIndex, jint historyPos) {
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

static void android_view_MotionEvent_nativeTransform(JNIEnv* env, jclass clazz,
        jlong nativePtr, jobject matrixObj) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);

    std::array<float, 9> matrix;
    AMatrix_getContents(env, matrixObj, matrix.data());
    event->transform(matrix);
}

static void android_view_MotionEvent_nativeApplyTransform(JNIEnv* env, jclass clazz,
                                                          jlong nativePtr, jobject matrixObj) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);

    std::array<float, 9> matrix;
    AMatrix_getContents(env, matrixObj, matrix.data());
    event->applyTransform(matrix);
}

// ----------------- @CriticalNative ------------------------------

static jlong android_view_MotionEvent_nativeCopy(jlong destNativePtr, jlong sourceNativePtr,
        jboolean keepHistory) {
    MotionEvent* destEvent = reinterpret_cast<MotionEvent*>(destNativePtr);
    if (!destEvent) {
        destEvent = new MotionEvent();
    }
    MotionEvent* sourceEvent = reinterpret_cast<MotionEvent*>(sourceNativePtr);
    destEvent->copyFrom(sourceEvent, keepHistory);
    return reinterpret_cast<jlong>(destEvent);
}

static jint android_view_MotionEvent_nativeGetId(jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getId();
}

static jint android_view_MotionEvent_nativeGetDeviceId(jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getDeviceId();
}

static jint android_view_MotionEvent_nativeGetSource(jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getSource();
}

static void android_view_MotionEvent_nativeSetSource(jlong nativePtr, jint source) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    event->setSource(source);
}

static jint android_view_MotionEvent_nativeGetDisplayId(jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getDisplayId();
}

static void android_view_MotionEvent_nativeSetDisplayId(jlong nativePtr, jint displayId) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->setDisplayId(displayId);
}

static jint android_view_MotionEvent_nativeGetAction(jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getAction();
}

static void android_view_MotionEvent_nativeSetAction(jlong nativePtr, jint action) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    event->setAction(action);
}

static int android_view_MotionEvent_nativeGetActionButton(jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getActionButton();
}

static void android_view_MotionEvent_nativeSetActionButton(jlong nativePtr, jint button) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    event->setActionButton(button);
}

static jboolean android_view_MotionEvent_nativeIsTouchEvent(jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->isTouchEvent();
}

static jint android_view_MotionEvent_nativeGetFlags(jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getFlags();
}

static void android_view_MotionEvent_nativeSetFlags(jlong nativePtr, jint flags) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    event->setFlags(flags);
}

static jint android_view_MotionEvent_nativeGetEdgeFlags(jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getEdgeFlags();
}

static void android_view_MotionEvent_nativeSetEdgeFlags(jlong nativePtr, jint edgeFlags) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    event->setEdgeFlags(edgeFlags);
}

static jint android_view_MotionEvent_nativeGetMetaState(jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getMetaState();
}

static jint android_view_MotionEvent_nativeGetButtonState(jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getButtonState();
}

static void android_view_MotionEvent_nativeSetButtonState(jlong nativePtr, jint buttonState) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    event->setButtonState(buttonState);
}

static jint android_view_MotionEvent_nativeGetClassification(jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return static_cast<jint>(event->getClassification());
}

static void android_view_MotionEvent_nativeOffsetLocation(jlong nativePtr, jfloat deltaX,
        jfloat deltaY) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->offsetLocation(deltaX, deltaY);
}

static jfloat android_view_MotionEvent_nativeGetXOffset(jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getXOffset();
}

static jfloat android_view_MotionEvent_nativeGetYOffset(jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getYOffset();
}

static jfloat android_view_MotionEvent_nativeGetXPrecision(jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getXPrecision();
}

static jfloat android_view_MotionEvent_nativeGetYPrecision(jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getYPrecision();
}

static jfloat android_view_MotionEvent_nativeGetXCursorPosition(jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getXCursorPosition();
}

static jfloat android_view_MotionEvent_nativeGetYCursorPosition(jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getYCursorPosition();
}

static void android_view_MotionEvent_nativeSetCursorPosition(jlong nativePtr, jfloat x, jfloat y) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    event->setCursorPosition(x, y);
}

static jlong android_view_MotionEvent_nativeGetDownTimeNanos(jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getDownTime();
}

static void android_view_MotionEvent_nativeSetDownTimeNanos(jlong nativePtr, jlong downTimeNanos) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    event->setDownTime(downTimeNanos);
}

static jint android_view_MotionEvent_nativeGetPointerCount(jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return jint(event->getPointerCount());
}

static jint android_view_MotionEvent_nativeFindPointerIndex(jlong nativePtr, jint pointerId) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return jint(event->findPointerIndex(pointerId));
}

static jint android_view_MotionEvent_nativeGetHistorySize(jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return jint(event->getHistorySize());
}

static void android_view_MotionEvent_nativeScale(jlong nativePtr, jfloat scale) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    event->scale(scale);
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gMotionEventMethods[] = {
        /* name, signature, funcPtr */
        {"nativeInitialize",
         "(JIIIIIIIIIFFFFJJI[Landroid/view/MotionEvent$PointerProperties;"
         "[Landroid/view/MotionEvent$PointerCoords;)J",
         (void*)android_view_MotionEvent_nativeInitialize},
        {"nativeDispose", "(J)V", (void*)android_view_MotionEvent_nativeDispose},
        {"nativeAddBatch", "(JJ[Landroid/view/MotionEvent$PointerCoords;I)V",
         (void*)android_view_MotionEvent_nativeAddBatch},
        {"nativeReadFromParcel", "(JLandroid/os/Parcel;)J",
         (void*)android_view_MotionEvent_nativeReadFromParcel},
        {"nativeWriteToParcel", "(JLandroid/os/Parcel;)V",
         (void*)android_view_MotionEvent_nativeWriteToParcel},
        {"nativeAxisToString", "(I)Ljava/lang/String;",
         (void*)android_view_MotionEvent_nativeAxisToString},
        {"nativeAxisFromString", "(Ljava/lang/String;)I",
         (void*)android_view_MotionEvent_nativeAxisFromString},
        {"nativeGetPointerProperties", "(JILandroid/view/MotionEvent$PointerProperties;)V",
         (void*)android_view_MotionEvent_nativeGetPointerProperties},
        {"nativeGetPointerCoords", "(JIILandroid/view/MotionEvent$PointerCoords;)V",
         (void*)android_view_MotionEvent_nativeGetPointerCoords},

        // --------------- @FastNative ----------------------
        {"nativeGetPointerId", "(JI)I", (void*)android_view_MotionEvent_nativeGetPointerId},
        {"nativeGetToolType", "(JI)I", (void*)android_view_MotionEvent_nativeGetToolType},
        {"nativeGetEventTimeNanos", "(JI)J",
         (void*)android_view_MotionEvent_nativeGetEventTimeNanos},
        {"nativeGetRawAxisValue", "(JIII)F", (void*)android_view_MotionEvent_nativeGetRawAxisValue},
        {"nativeGetAxisValue", "(JIII)F", (void*)android_view_MotionEvent_nativeGetAxisValue},
        {"nativeTransform", "(JLandroid/graphics/Matrix;)V",
         (void*)android_view_MotionEvent_nativeTransform},
        {"nativeApplyTransform", "(JLandroid/graphics/Matrix;)V",
         (void*)android_view_MotionEvent_nativeApplyTransform},

        // --------------- @CriticalNative ------------------

        {"nativeCopy", "(JJZ)J", (void*)android_view_MotionEvent_nativeCopy},
        {"nativeGetId", "(J)I", (void*)android_view_MotionEvent_nativeGetId},
        {"nativeGetDeviceId", "(J)I", (void*)android_view_MotionEvent_nativeGetDeviceId},
        {"nativeGetSource", "(J)I", (void*)android_view_MotionEvent_nativeGetSource},
        {"nativeSetSource", "(JI)V", (void*)android_view_MotionEvent_nativeSetSource},
        {"nativeGetDisplayId", "(J)I", (void*)android_view_MotionEvent_nativeGetDisplayId},
        {"nativeSetDisplayId", "(JI)V", (void*)android_view_MotionEvent_nativeSetDisplayId},
        {"nativeGetAction", "(J)I", (void*)android_view_MotionEvent_nativeGetAction},
        {"nativeSetAction", "(JI)V", (void*)android_view_MotionEvent_nativeSetAction},
        {"nativeGetActionButton", "(J)I", (void*)android_view_MotionEvent_nativeGetActionButton},
        {"nativeSetActionButton", "(JI)V", (void*)android_view_MotionEvent_nativeSetActionButton},
        {"nativeIsTouchEvent", "(J)Z", (void*)android_view_MotionEvent_nativeIsTouchEvent},
        {"nativeGetFlags", "(J)I", (void*)android_view_MotionEvent_nativeGetFlags},
        {"nativeSetFlags", "(JI)V", (void*)android_view_MotionEvent_nativeSetFlags},
        {"nativeGetEdgeFlags", "(J)I", (void*)android_view_MotionEvent_nativeGetEdgeFlags},
        {"nativeSetEdgeFlags", "(JI)V", (void*)android_view_MotionEvent_nativeSetEdgeFlags},
        {"nativeGetMetaState", "(J)I", (void*)android_view_MotionEvent_nativeGetMetaState},
        {"nativeGetButtonState", "(J)I", (void*)android_view_MotionEvent_nativeGetButtonState},
        {"nativeSetButtonState", "(JI)V", (void*)android_view_MotionEvent_nativeSetButtonState},
        {"nativeGetClassification", "(J)I",
         (void*)android_view_MotionEvent_nativeGetClassification},
        {"nativeOffsetLocation", "(JFF)V", (void*)android_view_MotionEvent_nativeOffsetLocation},
        {"nativeGetXOffset", "(J)F", (void*)android_view_MotionEvent_nativeGetXOffset},
        {"nativeGetYOffset", "(J)F", (void*)android_view_MotionEvent_nativeGetYOffset},
        {"nativeGetXPrecision", "(J)F", (void*)android_view_MotionEvent_nativeGetXPrecision},
        {"nativeGetYPrecision", "(J)F", (void*)android_view_MotionEvent_nativeGetYPrecision},
        {"nativeGetXCursorPosition", "(J)F",
         (void*)android_view_MotionEvent_nativeGetXCursorPosition},
        {"nativeGetYCursorPosition", "(J)F",
         (void*)android_view_MotionEvent_nativeGetYCursorPosition},
        {"nativeSetCursorPosition", "(JFF)V",
         (void*)android_view_MotionEvent_nativeSetCursorPosition},
        {"nativeGetDownTimeNanos", "(J)J", (void*)android_view_MotionEvent_nativeGetDownTimeNanos},
        {"nativeSetDownTimeNanos", "(JJ)V", (void*)android_view_MotionEvent_nativeSetDownTimeNanos},
        {"nativeGetPointerCount", "(J)I", (void*)android_view_MotionEvent_nativeGetPointerCount},
        {"nativeFindPointerIndex", "(JI)I", (void*)android_view_MotionEvent_nativeFindPointerIndex},
        {"nativeGetHistorySize", "(J)I", (void*)android_view_MotionEvent_nativeGetHistorySize},
        {"nativeScale", "(JF)V", (void*)android_view_MotionEvent_nativeScale},
};

int register_android_view_MotionEvent(JNIEnv* env) {
    int res = RegisterMethodsOrDie(env, "android/view/MotionEvent", gMotionEventMethods,
                                   NELEM(gMotionEventMethods));

    gMotionEventClassInfo.clazz = FindClassOrDie(env, "android/view/MotionEvent");
    gMotionEventClassInfo.clazz = MakeGlobalRefOrDie(env, gMotionEventClassInfo.clazz);

    gMotionEventClassInfo.obtain = GetStaticMethodIDOrDie(env, gMotionEventClassInfo.clazz,
            "obtain", "()Landroid/view/MotionEvent;");
    gMotionEventClassInfo.recycle = GetMethodIDOrDie(env, gMotionEventClassInfo.clazz,
            "recycle", "()V");
    gMotionEventClassInfo.mNativePtr = GetFieldIDOrDie(env, gMotionEventClassInfo.clazz,
            "mNativePtr", "J");

    jclass clazz = FindClassOrDie(env, "android/view/MotionEvent$PointerCoords");

    gPointerCoordsClassInfo.mPackedAxisBits = GetFieldIDOrDie(env, clazz, "mPackedAxisBits", "J");
    gPointerCoordsClassInfo.mPackedAxisValues = GetFieldIDOrDie(env, clazz, "mPackedAxisValues",
                                                                "[F");
    gPointerCoordsClassInfo.x = GetFieldIDOrDie(env, clazz, "x", "F");
    gPointerCoordsClassInfo.y = GetFieldIDOrDie(env, clazz, "y", "F");
    gPointerCoordsClassInfo.pressure = GetFieldIDOrDie(env, clazz, "pressure", "F");
    gPointerCoordsClassInfo.size = GetFieldIDOrDie(env, clazz, "size", "F");
    gPointerCoordsClassInfo.touchMajor = GetFieldIDOrDie(env, clazz, "touchMajor", "F");
    gPointerCoordsClassInfo.touchMinor = GetFieldIDOrDie(env, clazz, "touchMinor", "F");
    gPointerCoordsClassInfo.toolMajor = GetFieldIDOrDie(env, clazz, "toolMajor", "F");
    gPointerCoordsClassInfo.toolMinor = GetFieldIDOrDie(env, clazz, "toolMinor", "F");
    gPointerCoordsClassInfo.orientation = GetFieldIDOrDie(env, clazz, "orientation", "F");
    gPointerCoordsClassInfo.relativeX = GetFieldIDOrDie(env, clazz, "relativeX", "F");
    gPointerCoordsClassInfo.relativeY = GetFieldIDOrDie(env, clazz, "relativeY", "F");

    clazz = FindClassOrDie(env, "android/view/MotionEvent$PointerProperties");

    gPointerPropertiesClassInfo.id = GetFieldIDOrDie(env, clazz, "id", "I");
    gPointerPropertiesClassInfo.toolType = GetFieldIDOrDie(env, clazz, "toolType", "I");

    return res;
}

} // namespace android
