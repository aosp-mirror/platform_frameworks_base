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

#include "android_view_MotionEvent.h"

#include <android/graphics/matrix.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <attestation/HmacKeyManager.h>
#include <input/Input.h>
#include <log/log.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedUtfChars.h>

#include <sstream>

#include "android_os_Parcel.h"
#include "android_util_Binder.h"
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
    jfieldID isResampled;
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

static void android_view_MotionEvent_setNativePtr(JNIEnv* env, ScopedLocalRef<jobject>& eventObj,
                                                  MotionEvent* event) {
    env->SetLongField(eventObj.get(), gMotionEventClassInfo.mNativePtr,
                      reinterpret_cast<jlong>(event));
}

ScopedLocalRef<jobject> android_view_MotionEvent_obtainAsCopy(JNIEnv* env,
                                                              const MotionEvent& event) {
    std::unique_ptr<MotionEvent> destEvent = std::make_unique<MotionEvent>();
    destEvent->copyFrom(&event, true);
    return android_view_MotionEvent_obtainFromNative(env, std::move(destEvent));
}

ScopedLocalRef<jobject> android_view_MotionEvent_obtainFromNative(
        JNIEnv* env, std::unique_ptr<MotionEvent> event) {
    if (event == nullptr) {
        return ScopedLocalRef<jobject>(env);
    }
    ScopedLocalRef<jobject> eventObj(env,
                                     env->CallStaticObjectMethod(gMotionEventClassInfo.clazz,
                                                                 gMotionEventClassInfo.obtain));
    if (env->ExceptionCheck() || !eventObj.get()) {
        LOGE_EX(env);
        LOG_ALWAYS_FATAL("An exception occurred while obtaining a Java motion event.");
    }
    MotionEvent* oldEvent = android_view_MotionEvent_getNativePtr(env, eventObj.get());
    delete oldEvent;
    android_view_MotionEvent_setNativePtr(env, eventObj, event.release());
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

static bool validatePointerIndex(JNIEnv* env, jint pointerIndex, const MotionEvent& event) {
    if (pointerIndex < 0 || size_t(pointerIndex) >= event.getPointerCount()) {
        std::stringstream message;
        message << "invalid pointerIndex " << pointerIndex << " for " << event;
        jniThrowException(env, "java/lang/IllegalArgumentException", message.str().c_str());
        return false;
    }
    return true;
}

static bool validateHistoryPos(JNIEnv* env, jint historyPos, const MotionEvent& event) {
    if (historyPos < 0 || size_t(historyPos) >= event.getHistorySize()) {
        std::stringstream message;
        message << "historyPos " << historyPos << " out of range for " << event;
        jniThrowException(env, "java/lang/IllegalArgumentException", message.str().c_str());
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

static PointerCoords pointerCoordsToNative(JNIEnv* env, jobject pointerCoordsObj) {
    PointerCoords out{};
    out.setAxisValue(AMOTION_EVENT_AXIS_X,
                     env->GetFloatField(pointerCoordsObj, gPointerCoordsClassInfo.x));
    out.setAxisValue(AMOTION_EVENT_AXIS_Y,
                     env->GetFloatField(pointerCoordsObj, gPointerCoordsClassInfo.y));
    out.setAxisValue(AMOTION_EVENT_AXIS_PRESSURE,
                     env->GetFloatField(pointerCoordsObj, gPointerCoordsClassInfo.pressure));
    out.setAxisValue(AMOTION_EVENT_AXIS_SIZE,
                     env->GetFloatField(pointerCoordsObj, gPointerCoordsClassInfo.size));
    out.setAxisValue(AMOTION_EVENT_AXIS_TOUCH_MAJOR,
                     env->GetFloatField(pointerCoordsObj, gPointerCoordsClassInfo.touchMajor));
    out.setAxisValue(AMOTION_EVENT_AXIS_TOUCH_MINOR,
                     env->GetFloatField(pointerCoordsObj, gPointerCoordsClassInfo.touchMinor));
    out.setAxisValue(AMOTION_EVENT_AXIS_TOOL_MAJOR,
                     env->GetFloatField(pointerCoordsObj, gPointerCoordsClassInfo.toolMajor));
    out.setAxisValue(AMOTION_EVENT_AXIS_TOOL_MINOR,
                     env->GetFloatField(pointerCoordsObj, gPointerCoordsClassInfo.toolMinor));
    out.setAxisValue(AMOTION_EVENT_AXIS_ORIENTATION,
                     env->GetFloatField(pointerCoordsObj, gPointerCoordsClassInfo.orientation));
    out.setAxisValue(AMOTION_EVENT_AXIS_RELATIVE_X,
                     env->GetFloatField(pointerCoordsObj, gPointerCoordsClassInfo.relativeX));
    out.setAxisValue(AMOTION_EVENT_AXIS_RELATIVE_Y,
                     env->GetFloatField(pointerCoordsObj, gPointerCoordsClassInfo.relativeY));
    out.isResampled = env->GetBooleanField(pointerCoordsObj, gPointerCoordsClassInfo.isResampled);

    BitSet64 bits =
            BitSet64(env->GetLongField(pointerCoordsObj, gPointerCoordsClassInfo.mPackedAxisBits));
    if (!bits.isEmpty()) {
        jfloatArray valuesArray = jfloatArray(
                env->GetObjectField(pointerCoordsObj, gPointerCoordsClassInfo.mPackedAxisValues));
        if (valuesArray) {
            jfloat* values =
                    static_cast<jfloat*>(env->GetPrimitiveArrayCritical(valuesArray, NULL));

            uint32_t index = 0;
            do {
                uint32_t axis = bits.clearFirstMarkedBit();
                out.setAxisValue(axis, values[index++]);
            } while (!bits.isEmpty());

            env->ReleasePrimitiveArrayCritical(valuesArray, values, JNI_ABORT);
            env->DeleteLocalRef(valuesArray);
        }
    }
    return out;
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
                                    const BitSet64& axesBitsToCopy, jobject outPointerCoordsObj) {
    BitSet64 bits = axesBitsToCopy;
    uint64_t outBits = 0;
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

static PointerProperties pointerPropertiesToNative(JNIEnv* env, jobject pointerPropertiesObj) {
    PointerProperties out{};
    out.id = env->GetIntField(pointerPropertiesObj, gPointerPropertiesClassInfo.id);
    const int32_t toolType =
            env->GetIntField(pointerPropertiesObj, gPointerPropertiesClassInfo.toolType);
    out.toolType = static_cast<ToolType>(toolType);
    return out;
}

static void pointerPropertiesFromNative(JNIEnv* env, const PointerProperties* pointerProperties,
        jobject outPointerPropertiesObj) {
    env->SetIntField(outPointerPropertiesObj, gPointerPropertiesClassInfo.id,
            pointerProperties->id);
    env->SetIntField(outPointerPropertiesObj, gPointerPropertiesClassInfo.toolType,
            static_cast<int32_t>(pointerProperties->toolType));
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

    ui::Transform transform;
    transform.set(xOffset, yOffset);
    const ui::Transform inverseTransform = transform.inverse();

    std::vector<PointerProperties> pointerProperties;
    pointerProperties.reserve(pointerCount);
    std::vector<PointerCoords> rawPointerCoords;
    rawPointerCoords.reserve(pointerCount);

    for (jint i = 0; i < pointerCount; i++) {
        jobject pointerPropertiesObj = env->GetObjectArrayElement(pointerPropertiesObjArray, i);
        if (!pointerPropertiesObj) {
            return 0;
        }
        pointerProperties.emplace_back(pointerPropertiesToNative(env, pointerPropertiesObj));
        env->DeleteLocalRef(pointerPropertiesObj);

        jobject pointerCoordsObj = env->GetObjectArrayElement(pointerCoordsObjArray, i);
        if (!pointerCoordsObj) {
            jniThrowNullPointerException(env, "pointerCoords");
            return 0;
        }
        rawPointerCoords.emplace_back(pointerCoordsToNative(env, pointerCoordsObj));
        PointerCoords& coords = rawPointerCoords.back();
        if (coords.getAxisValue(AMOTION_EVENT_AXIS_ORIENTATION) != 0.f) {
            flags |= AMOTION_EVENT_PRIVATE_FLAG_SUPPORTS_ORIENTATION |
                    AMOTION_EVENT_PRIVATE_FLAG_SUPPORTS_DIRECTIONAL_ORIENTATION;
        }
        MotionEvent::calculateTransformedCoordsInPlace(coords, source, flags, inverseTransform);
        env->DeleteLocalRef(pointerCoordsObj);
    }

    static const ui::Transform kIdentityTransform;
    event->initialize(InputEvent::nextId(), deviceId, source, ui::LogicalDisplayId{displayId},
                      INVALID_HMAC, action, 0, flags, edgeFlags, metaState, buttonState,
                      static_cast<MotionClassification>(classification), transform, xPrecision,
                      yPrecision, AMOTION_EVENT_INVALID_CURSOR_POSITION,
                      AMOTION_EVENT_INVALID_CURSOR_POSITION, kIdentityTransform, downTimeNanos,
                      eventTimeNanos, pointerCount, pointerProperties.data(),
                      rawPointerCoords.data());

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

    const ui::Transform inverseTransform = event->getTransform().inverse();

    std::vector<PointerCoords> rawPointerCoords;
    rawPointerCoords.reserve(pointerCount);

    for (size_t i = 0; i < pointerCount; i++) {
        jobject pointerCoordsObj = env->GetObjectArrayElement(pointerCoordsObjArray, i);
        if (!pointerCoordsObj) {
            jniThrowNullPointerException(env, "pointerCoords");
            return;
        }
        rawPointerCoords.emplace_back(pointerCoordsToNative(env, pointerCoordsObj));
        MotionEvent::calculateTransformedCoordsInPlace(rawPointerCoords.back(), event->getSource(),
                                                       event->getFlags(), inverseTransform);
        env->DeleteLocalRef(pointerCoordsObj);
    }

    event->addSample(eventTimeNanos, rawPointerCoords.data(), event->getId());
    event->setMetaState(event->getMetaState() | metaState);
}

static void android_view_MotionEvent_nativeGetPointerCoords(JNIEnv* env, jclass clazz,
        jlong nativePtr, jint pointerIndex, jint historyPos, jobject outPointerCoordsObj) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    if (!validatePointerIndex(env, pointerIndex, *event) ||
        !validatePointerCoords(env, outPointerCoordsObj)) {
        return;
    }
    if (historyPos != HISTORY_CURRENT && !validateHistoryPos(env, historyPos, *event)) {
        return;
    }

    // Obtain the following axis values directly from the MotionEvent instead of from the raw
    // PointerCoords.
    const static std::array<std::pair<int32_t /*axis*/, jfieldID>, 11> kAxesFromMotionEvent = {{
            {AMOTION_EVENT_AXIS_X, gPointerCoordsClassInfo.x},
            {AMOTION_EVENT_AXIS_Y, gPointerCoordsClassInfo.y},
            {AMOTION_EVENT_AXIS_PRESSURE, gPointerCoordsClassInfo.pressure},
            {AMOTION_EVENT_AXIS_SIZE, gPointerCoordsClassInfo.size},
            {AMOTION_EVENT_AXIS_TOUCH_MAJOR, gPointerCoordsClassInfo.touchMajor},
            {AMOTION_EVENT_AXIS_TOUCH_MINOR, gPointerCoordsClassInfo.touchMinor},
            {AMOTION_EVENT_AXIS_TOOL_MAJOR, gPointerCoordsClassInfo.toolMajor},
            {AMOTION_EVENT_AXIS_TOOL_MINOR, gPointerCoordsClassInfo.toolMinor},
            {AMOTION_EVENT_AXIS_ORIENTATION, gPointerCoordsClassInfo.orientation},
            {AMOTION_EVENT_AXIS_RELATIVE_X, gPointerCoordsClassInfo.relativeX},
            {AMOTION_EVENT_AXIS_RELATIVE_Y, gPointerCoordsClassInfo.relativeY},
    }};
    for (const auto& [axis, fieldId] : kAxesFromMotionEvent) {
        const float value = historyPos == HISTORY_CURRENT
                ? event->getAxisValue(axis, pointerIndex)
                : event->getHistoricalAxisValue(axis, pointerIndex, historyPos);
        env->SetFloatField(outPointerCoordsObj, fieldId, value);
    }

    const PointerCoords* rawPointerCoords = historyPos == HISTORY_CURRENT
            ? event->getRawPointerCoords(pointerIndex)
            : event->getHistoricalRawPointerCoords(pointerIndex, historyPos);

    BitSet64 bits = BitSet64(rawPointerCoords->bits);
    for (const auto [axis, _] : kAxesFromMotionEvent) {
        bits.clearBit(axis);
    }
    pointerCoordsFromNative(env, rawPointerCoords, bits, outPointerCoordsObj);

    const bool isResampled = historyPos == HISTORY_CURRENT
            ? event->isResampled(pointerIndex, event->getHistorySize())
            : event->isResampled(pointerIndex, historyPos);
    env->SetBooleanField(outPointerCoordsObj, gPointerCoordsClassInfo.isResampled, isResampled);
}

static void android_view_MotionEvent_nativeGetPointerProperties(JNIEnv* env, jclass clazz,
        jlong nativePtr, jint pointerIndex, jobject outPointerPropertiesObj) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    if (!validatePointerIndex(env, pointerIndex, *event) ||
        !validatePointerProperties(env, outPointerPropertiesObj)) {
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
    return static_cast<jint>(MotionEvent::getAxisFromLabel(axisLabel.c_str()).value_or(-1));
}

// ---------------- @FastNative ----------------------------------

static jint android_view_MotionEvent_nativeGetPointerId(JNIEnv* env, jclass clazz,
        jlong nativePtr, jint pointerIndex) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    if (!validatePointerIndex(env, pointerIndex, *event)) {
        return -1;
    }
    return event->getPointerId(pointerIndex);
}

static jint android_view_MotionEvent_nativeGetToolType(JNIEnv* env, jclass clazz,
        jlong nativePtr, jint pointerIndex) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    if (!validatePointerIndex(env, pointerIndex, *event)) {
        return -1;
    }
    return static_cast<jint>(event->getToolType(pointerIndex));
}

static jlong android_view_MotionEvent_nativeGetEventTimeNanos(JNIEnv* env, jclass clazz,
        jlong nativePtr, jint historyPos) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    if (historyPos == HISTORY_CURRENT) {
        return event->getEventTime();
    } else {
        if (!validateHistoryPos(env, historyPos, *event)) {
            return 0;
        }
        return event->getHistoricalEventTime(historyPos);
    }
}

static jfloat android_view_MotionEvent_nativeGetRawAxisValue(JNIEnv* env, jclass clazz,
        jlong nativePtr, jint axis,
        jint pointerIndex, jint historyPos) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    if (!validatePointerIndex(env, pointerIndex, *event)) {
        return 0;
    }

    if (historyPos == HISTORY_CURRENT) {
        return event->getRawAxisValue(axis, pointerIndex);
    } else {
        if (!validateHistoryPos(env, historyPos, *event)) {
            return 0;
        }
        return event->getHistoricalRawAxisValue(axis, pointerIndex, historyPos);
    }
}

static jfloat android_view_MotionEvent_nativeGetAxisValue(JNIEnv* env, jclass clazz,
        jlong nativePtr, jint axis, jint pointerIndex, jint historyPos) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    if (!validatePointerIndex(env, pointerIndex, *event)) {
        return 0;
    }

    if (historyPos == HISTORY_CURRENT) {
        return event->getAxisValue(axis, pointerIndex);
    } else {
        if (!validateHistoryPos(env, historyPos, (*event))) {
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

static jlong android_view_MotionEvent_nativeCopy(CRITICAL_JNI_PARAMS_COMMA jlong destNativePtr,
                                                 jlong sourceNativePtr, jboolean keepHistory) {
    MotionEvent* destEvent = reinterpret_cast<MotionEvent*>(destNativePtr);
    if (!destEvent) {
        destEvent = new MotionEvent();
    }
    MotionEvent* sourceEvent = reinterpret_cast<MotionEvent*>(sourceNativePtr);
    destEvent->copyFrom(sourceEvent, keepHistory);
    return reinterpret_cast<jlong>(destEvent);
}

static jlong android_view_MotionEvent_nativeSplit(CRITICAL_JNI_PARAMS_COMMA jlong destNativePtr,
                                                  jlong sourceNativePtr, jint idBits) {
    MotionEvent* destEvent = reinterpret_cast<MotionEvent*>(destNativePtr);
    if (!destEvent) {
        destEvent = new MotionEvent();
    }
    MotionEvent* sourceEvent = reinterpret_cast<MotionEvent*>(sourceNativePtr);
    destEvent->splitFrom(*sourceEvent, static_cast<std::bitset<MAX_POINTER_ID + 1>>(idBits),
                         InputEvent::nextId());
    return reinterpret_cast<jlong>(destEvent);
}

static jint android_view_MotionEvent_nativeGetId(CRITICAL_JNI_PARAMS_COMMA jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getId();
}

static jint android_view_MotionEvent_nativeGetDeviceId(CRITICAL_JNI_PARAMS_COMMA jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getDeviceId();
}

static jint android_view_MotionEvent_nativeGetSource(CRITICAL_JNI_PARAMS_COMMA jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getSource();
}

static void android_view_MotionEvent_nativeSetSource(CRITICAL_JNI_PARAMS_COMMA jlong nativePtr,
                                                     jint source) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    event->setSource(source);
}

static jint android_view_MotionEvent_nativeGetDisplayId(CRITICAL_JNI_PARAMS_COMMA jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return static_cast<jint>(event->getDisplayId().val());
}

static void android_view_MotionEvent_nativeSetDisplayId(CRITICAL_JNI_PARAMS_COMMA jlong nativePtr,
                                                        jint displayId) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    event->setDisplayId(ui::LogicalDisplayId{displayId});
}

static jint android_view_MotionEvent_nativeGetAction(CRITICAL_JNI_PARAMS_COMMA jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getAction();
}

static void android_view_MotionEvent_nativeSetAction(CRITICAL_JNI_PARAMS_COMMA jlong nativePtr,
                                                     jint action) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    event->setAction(action);
}

static int android_view_MotionEvent_nativeGetActionButton(
        CRITICAL_JNI_PARAMS_COMMA jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getActionButton();
}

static void android_view_MotionEvent_nativeSetActionButton(
        CRITICAL_JNI_PARAMS_COMMA jlong nativePtr, jint button) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    event->setActionButton(button);
}

static jboolean android_view_MotionEvent_nativeIsTouchEvent(
        CRITICAL_JNI_PARAMS_COMMA jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->isTouchEvent();
}

static jint android_view_MotionEvent_nativeGetFlags(CRITICAL_JNI_PARAMS_COMMA jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    // Prevent private flags from being used in Java.
    return event->getFlags() & ~AMOTION_EVENT_PRIVATE_FLAG_MASK;
}

static void android_view_MotionEvent_nativeSetFlags(CRITICAL_JNI_PARAMS_COMMA jlong nativePtr,
                                                    jint flags) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    // Prevent private flags from being used from Java.
    const int32_t privateFlags = event->getFlags() & AMOTION_EVENT_PRIVATE_FLAG_MASK;
    event->setFlags((flags & ~AMOTION_EVENT_PRIVATE_FLAG_MASK) | privateFlags);
}

static jint android_view_MotionEvent_nativeGetEdgeFlags(CRITICAL_JNI_PARAMS_COMMA jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getEdgeFlags();
}

static void android_view_MotionEvent_nativeSetEdgeFlags(CRITICAL_JNI_PARAMS_COMMA jlong nativePtr,
                                                        jint edgeFlags) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    event->setEdgeFlags(edgeFlags);
}

static jint android_view_MotionEvent_nativeGetMetaState(CRITICAL_JNI_PARAMS_COMMA jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getMetaState();
}

static jint android_view_MotionEvent_nativeGetButtonState(
        CRITICAL_JNI_PARAMS_COMMA jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getButtonState();
}

static void android_view_MotionEvent_nativeSetButtonState(CRITICAL_JNI_PARAMS_COMMA jlong nativePtr,
                                                          jint buttonState) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    event->setButtonState(buttonState);
}

static jint android_view_MotionEvent_nativeGetClassification(
        CRITICAL_JNI_PARAMS_COMMA jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return static_cast<jint>(event->getClassification());
}

static void android_view_MotionEvent_nativeOffsetLocation(CRITICAL_JNI_PARAMS_COMMA jlong nativePtr,
                                                          jfloat deltaX, jfloat deltaY) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->offsetLocation(deltaX, deltaY);
}

static jfloat android_view_MotionEvent_nativeGetRawXOffset(
        CRITICAL_JNI_PARAMS_COMMA jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getRawXOffset();
}

static jfloat android_view_MotionEvent_nativeGetRawYOffset(
        CRITICAL_JNI_PARAMS_COMMA jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getRawYOffset();
}

static jfloat android_view_MotionEvent_nativeGetXPrecision(
        CRITICAL_JNI_PARAMS_COMMA jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getXPrecision();
}

static jfloat android_view_MotionEvent_nativeGetYPrecision(
        CRITICAL_JNI_PARAMS_COMMA jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getYPrecision();
}

static jfloat android_view_MotionEvent_nativeGetXCursorPosition(
        CRITICAL_JNI_PARAMS_COMMA jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getXCursorPosition();
}

static jfloat android_view_MotionEvent_nativeGetYCursorPosition(
        CRITICAL_JNI_PARAMS_COMMA jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getYCursorPosition();
}

static void android_view_MotionEvent_nativeSetCursorPosition(
        CRITICAL_JNI_PARAMS_COMMA jlong nativePtr, jfloat x, jfloat y) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    event->setCursorPosition(x, y);
}

static jlong android_view_MotionEvent_nativeGetDownTimeNanos(
        CRITICAL_JNI_PARAMS_COMMA jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return event->getDownTime();
}

static void android_view_MotionEvent_nativeSetDownTimeNanos(
        CRITICAL_JNI_PARAMS_COMMA jlong nativePtr, jlong downTimeNanos) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    event->setDownTime(downTimeNanos);
}

static jint android_view_MotionEvent_nativeGetPointerCount(
        CRITICAL_JNI_PARAMS_COMMA jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return jint(event->getPointerCount());
}

static jint android_view_MotionEvent_nativeFindPointerIndex(
        CRITICAL_JNI_PARAMS_COMMA jlong nativePtr, jint pointerId) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return jint(event->findPointerIndex(pointerId));
}

static jint android_view_MotionEvent_nativeGetHistorySize(
        CRITICAL_JNI_PARAMS_COMMA jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    return jint(event->getHistorySize());
}

static void android_view_MotionEvent_nativeScale(CRITICAL_JNI_PARAMS_COMMA jlong nativePtr,
                                                 jfloat scale) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    event->scale(scale);
}

static jint android_view_MotionEvent_nativeGetSurfaceRotation(jlong nativePtr) {
    MotionEvent* event = reinterpret_cast<MotionEvent*>(nativePtr);
    auto rotation = event->getSurfaceRotation();
    if (rotation) {
        return static_cast<jint>(rotation.value());
    } else {
        return -1;
    }
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
        {"nativeSplit", "(JJI)J", (void*)android_view_MotionEvent_nativeSplit},
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
        {"nativeGetRawXOffset", "(J)F", (void*)android_view_MotionEvent_nativeGetRawXOffset},
        {"nativeGetRawYOffset", "(J)F", (void*)android_view_MotionEvent_nativeGetRawYOffset},
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
        {"nativeGetSurfaceRotation", "(J)I",
         (void*)android_view_MotionEvent_nativeGetSurfaceRotation},
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
    gPointerCoordsClassInfo.isResampled = GetFieldIDOrDie(env, clazz, "isResampled", "Z");

    clazz = FindClassOrDie(env, "android/view/MotionEvent$PointerProperties");

    gPointerPropertiesClassInfo.id = GetFieldIDOrDie(env, clazz, "id", "I");
    gPointerPropertiesClassInfo.toolType = GetFieldIDOrDie(env, clazz, "toolType", "I");

    return res;
}

} // namespace android
