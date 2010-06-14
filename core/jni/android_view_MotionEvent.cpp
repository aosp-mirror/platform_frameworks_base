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
#include <ui/Input.h>
#include "android_view_MotionEvent.h"

// Number of float items per entry in a DVM sample data array
#define NUM_SAMPLE_DATA 4

namespace android {

// ----------------------------------------------------------------------------

static struct {
    jclass clazz;

    jmethodID obtain;
    jmethodID recycle;

    jfieldID mDownTime;
    jfieldID mEventTimeNano;
    jfieldID mAction;
    jfieldID mRawX;
    jfieldID mRawY;
    jfieldID mXPrecision;
    jfieldID mYPrecision;
    jfieldID mDeviceId;
    jfieldID mEdgeFlags;
    jfieldID mMetaState;
    jfieldID mNumPointers;
    jfieldID mNumSamples;
    jfieldID mPointerIdentifiers;
    jfieldID mDataSamples;
    jfieldID mTimeSamples;
} gMotionEventClassInfo;

// ----------------------------------------------------------------------------

jobject android_view_MotionEvent_fromNative(JNIEnv* env, const MotionEvent* event) {
    jint numPointers = jint(event->getPointerCount());
    jint numHistoricalSamples = jint(event->getHistorySize());
    jint numSamples = numHistoricalSamples + 1;

    jobject eventObj = env->CallStaticObjectMethod(gMotionEventClassInfo.clazz,
            gMotionEventClassInfo.obtain, numPointers, numSamples);
    if (env->ExceptionCheck()) {
        LOGE("An exception occurred while obtaining a motion event.");
        LOGE_EX(env);
        env->ExceptionClear();
        return NULL;
    }

    // MotionEvent.mEventTimeNano is the time of the oldest sample because
    // MotionEvent.addBatch does not update it as successive samples are added.
    jlong eventTimeNano = numHistoricalSamples != 0
            ? event->getHistoricalEventTime(0)
            : event->getEventTime();

    env->SetLongField(eventObj, gMotionEventClassInfo.mDownTime,
            nanoseconds_to_milliseconds(event->getDownTime()));
    env->SetLongField(eventObj, gMotionEventClassInfo.mEventTimeNano,
            eventTimeNano);
    env->SetIntField(eventObj, gMotionEventClassInfo.mAction,
            event->getAction());
    env->SetFloatField(eventObj, gMotionEventClassInfo.mRawX,
            event->getRawX());
    env->SetFloatField(eventObj, gMotionEventClassInfo.mRawY,
            event->getRawY());
    env->SetFloatField(eventObj, gMotionEventClassInfo.mXPrecision,
            event->getXPrecision());
    env->SetFloatField(eventObj, gMotionEventClassInfo.mYPrecision,
            event->getYPrecision());
    env->SetIntField(eventObj, gMotionEventClassInfo.mDeviceId,
            event->getDeviceId());
    env->SetIntField(eventObj, gMotionEventClassInfo.mEdgeFlags,
            event->getEdgeFlags());
    env->SetIntField(eventObj, gMotionEventClassInfo.mMetaState,
            event->getMetaState());
    env->SetIntField(eventObj, gMotionEventClassInfo.mNumPointers,
            numPointers);
    env->SetIntField(eventObj, gMotionEventClassInfo.mNumSamples,
            numSamples);

    jintArray pointerIdentifierArray = jintArray(env->GetObjectField(eventObj,
            gMotionEventClassInfo.mPointerIdentifiers));
    jfloatArray dataSampleArray = jfloatArray(env->GetObjectField(eventObj,
            gMotionEventClassInfo.mDataSamples));
    jlongArray timeSampleArray = jlongArray(env->GetObjectField(eventObj,
            gMotionEventClassInfo.mTimeSamples));

    jint* pointerIdentifiers = (jint*)env->GetPrimitiveArrayCritical(pointerIdentifierArray, NULL);
    jfloat* dataSamples = (jfloat*)env->GetPrimitiveArrayCritical(dataSampleArray, NULL);
    jlong* timeSamples = (jlong*)env->GetPrimitiveArrayCritical(timeSampleArray, NULL);

    for (jint i = 0; i < numPointers; i++) {
        pointerIdentifiers[i] = event->getPointerId(i);
    }

    // Most recent data is in first slot of the DVM array, followed by the oldest,
    // and then all others are in order.

    jfloat* currentDataSample = dataSamples;
    jlong* currentTimeSample = timeSamples;

    *(currentTimeSample++) = nanoseconds_to_milliseconds(event->getEventTime());
    for (jint j = 0; j < numPointers; j++) {
        *(currentDataSample++) = event->getX(j);
        *(currentDataSample++) = event->getY(j);
        *(currentDataSample++) = event->getPressure(j);
        *(currentDataSample++) = event->getSize(j);
    }

    for (jint i = 0; i < numHistoricalSamples; i++) {
        *(currentTimeSample++) = nanoseconds_to_milliseconds(event->getHistoricalEventTime(i));
        for (jint j = 0; j < numPointers; j++) {
            *(currentDataSample++) = event->getHistoricalX(j, i);
            *(currentDataSample++) = event->getHistoricalY(j, i);
            *(currentDataSample++) = event->getHistoricalPressure(j, i);
            *(currentDataSample++) = event->getHistoricalSize(j, i);
        }
    }

    env->ReleasePrimitiveArrayCritical(pointerIdentifierArray, pointerIdentifiers, 0);
    env->ReleasePrimitiveArrayCritical(dataSampleArray, dataSamples, 0);
    env->ReleasePrimitiveArrayCritical(timeSampleArray, timeSamples, 0);

    env->DeleteLocalRef(pointerIdentifierArray);
    env->DeleteLocalRef(dataSampleArray);
    env->DeleteLocalRef(timeSampleArray);
    return eventObj;
}

void android_view_MotionEvent_toNative(JNIEnv* env, jobject eventObj, int32_t nature,
        MotionEvent* event) {
    // MotionEvent.mEventTimeNano is the time of the oldest sample because
    // MotionEvent.addBatch does not update it as successive samples are added.
    jlong downTime = env->GetLongField(eventObj, gMotionEventClassInfo.mDownTime);
    jlong eventTimeNano = env->GetLongField(eventObj, gMotionEventClassInfo.mEventTimeNano);
    jint action = env->GetIntField(eventObj, gMotionEventClassInfo.mAction);
    jfloat rawX = env->GetFloatField(eventObj, gMotionEventClassInfo.mRawX);
    jfloat rawY = env->GetFloatField(eventObj, gMotionEventClassInfo.mRawY);
    jfloat xPrecision = env->GetFloatField(eventObj, gMotionEventClassInfo.mXPrecision);
    jfloat yPrecision = env->GetFloatField(eventObj, gMotionEventClassInfo.mYPrecision);
    jint deviceId = env->GetIntField(eventObj, gMotionEventClassInfo.mDeviceId);
    jint edgeFlags = env->GetIntField(eventObj, gMotionEventClassInfo.mEdgeFlags);
    jint metaState = env->GetIntField(eventObj, gMotionEventClassInfo.mMetaState);
    jint numPointers = env->GetIntField(eventObj, gMotionEventClassInfo.mNumPointers);
    jint numSamples = env->GetIntField(eventObj, gMotionEventClassInfo.mNumSamples);
    jintArray pointerIdentifierArray = jintArray(env->GetObjectField(eventObj,
            gMotionEventClassInfo.mPointerIdentifiers));
    jfloatArray dataSampleArray = jfloatArray(env->GetObjectField(eventObj,
            gMotionEventClassInfo.mDataSamples));
    jlongArray timeSampleArray = jlongArray(env->GetObjectField(eventObj,
            gMotionEventClassInfo.mTimeSamples));

    LOG_FATAL_IF(numPointers == 0, "numPointers was zero");
    LOG_FATAL_IF(numSamples == 0, "numSamples was zero");

    jint* pointerIdentifiers = (jint*)env->GetPrimitiveArrayCritical(pointerIdentifierArray, NULL);
    jfloat* dataSamples = (jfloat*)env->GetPrimitiveArrayCritical(dataSampleArray, NULL);
    jlong* timeSamples = (jlong*)env->GetPrimitiveArrayCritical(timeSampleArray, NULL);

    // Most recent data is in first slot of the DVM array, followed by the oldest,
    // and then all others are in order.  eventTimeNano is the time of the oldest sample
    // since MotionEvent.addBatch does not update it.

    jint numHistoricalSamples = numSamples - 1;
    jint dataSampleStride = numPointers * NUM_SAMPLE_DATA;

    const jfloat* currentDataSample;
    const jlong* currentTimeSample;
    if (numHistoricalSamples == 0) {
        currentDataSample = dataSamples;
        currentTimeSample = timeSamples;
    } else {
        currentDataSample = dataSamples + dataSampleStride;
        currentTimeSample = timeSamples + 1;
    }

    PointerCoords pointerCoords[MAX_POINTERS];
    for (jint j = 0; j < numPointers; j++) {
        pointerCoords[j].x = *(currentDataSample++);
        pointerCoords[j].y = *(currentDataSample++);
        pointerCoords[j].pressure = *(currentDataSample++);
        pointerCoords[j].size = *(currentDataSample++);
    }

    event->initialize(deviceId, nature, action, edgeFlags, metaState,
            rawX, rawY, xPrecision, yPrecision,
            milliseconds_to_nanoseconds(downTime), eventTimeNano,
            numPointers, pointerIdentifiers, pointerCoords);

    while (numHistoricalSamples > 0) {
        numHistoricalSamples -= 1;
        if (numHistoricalSamples == 0) {
            currentDataSample = dataSamples;
            currentTimeSample = timeSamples;
        }

        nsecs_t sampleEventTime = milliseconds_to_nanoseconds(*(currentTimeSample++));

        for (jint j = 0; j < numPointers; j++) {
            pointerCoords[j].x = *(currentDataSample++);
            pointerCoords[j].y = *(currentDataSample++);
            pointerCoords[j].pressure = *(currentDataSample++);
            pointerCoords[j].size = *(currentDataSample++);
        }

        event->addSample(sampleEventTime, pointerCoords);
    }

    env->ReleasePrimitiveArrayCritical(pointerIdentifierArray, pointerIdentifiers, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(dataSampleArray, dataSamples, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(timeSampleArray, timeSamples, JNI_ABORT);

    env->DeleteLocalRef(pointerIdentifierArray);
    env->DeleteLocalRef(dataSampleArray);
    env->DeleteLocalRef(timeSampleArray);
}

void android_view_MotionEvent_recycle(JNIEnv* env, jobject eventObj) {
    env->CallVoidMethod(eventObj, gMotionEventClassInfo.recycle);
    if (env->ExceptionCheck()) {
        LOGW("An exception occurred while recycling a motion event.");
        LOGW_EX(env);
        env->ExceptionClear();
    }
}

// ----------------------------------------------------------------------------

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className); \
        var = jclass(env->NewGlobalRef(var));

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
    FIND_CLASS(gMotionEventClassInfo.clazz, "android/view/MotionEvent");

    GET_STATIC_METHOD_ID(gMotionEventClassInfo.obtain, gMotionEventClassInfo.clazz,
            "obtain", "(II)Landroid/view/MotionEvent;");
    GET_METHOD_ID(gMotionEventClassInfo.recycle, gMotionEventClassInfo.clazz,
            "recycle", "()V");

    GET_FIELD_ID(gMotionEventClassInfo.mDownTime, gMotionEventClassInfo.clazz,
            "mDownTime", "J");
    GET_FIELD_ID(gMotionEventClassInfo.mEventTimeNano, gMotionEventClassInfo.clazz,
            "mEventTimeNano", "J");
    GET_FIELD_ID(gMotionEventClassInfo.mAction, gMotionEventClassInfo.clazz,
            "mAction", "I");
    GET_FIELD_ID(gMotionEventClassInfo.mRawX, gMotionEventClassInfo.clazz,
            "mRawX", "F");
    GET_FIELD_ID(gMotionEventClassInfo.mRawY, gMotionEventClassInfo.clazz,
            "mRawY", "F");
    GET_FIELD_ID(gMotionEventClassInfo.mXPrecision, gMotionEventClassInfo.clazz,
            "mXPrecision", "F");
    GET_FIELD_ID(gMotionEventClassInfo.mYPrecision, gMotionEventClassInfo.clazz,
            "mYPrecision", "F");
    GET_FIELD_ID(gMotionEventClassInfo.mDeviceId, gMotionEventClassInfo.clazz,
            "mDeviceId", "I");
    GET_FIELD_ID(gMotionEventClassInfo.mEdgeFlags, gMotionEventClassInfo.clazz,
            "mEdgeFlags", "I");
    GET_FIELD_ID(gMotionEventClassInfo.mMetaState, gMotionEventClassInfo.clazz,
            "mMetaState", "I");
    GET_FIELD_ID(gMotionEventClassInfo.mNumPointers, gMotionEventClassInfo.clazz,
            "mNumPointers", "I");
    GET_FIELD_ID(gMotionEventClassInfo.mNumSamples, gMotionEventClassInfo.clazz,
            "mNumSamples", "I");
    GET_FIELD_ID(gMotionEventClassInfo.mPointerIdentifiers, gMotionEventClassInfo.clazz,
            "mPointerIdentifiers", "[I");
    GET_FIELD_ID(gMotionEventClassInfo.mDataSamples, gMotionEventClassInfo.clazz,
            "mDataSamples", "[F");
    GET_FIELD_ID(gMotionEventClassInfo.mTimeSamples, gMotionEventClassInfo.clazz,
            "mTimeSamples", "[J");

    return 0;
}

} // namespace android
