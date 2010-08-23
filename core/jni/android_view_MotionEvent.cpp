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
#include "android/graphics/Matrix.h"

#include <math.h>
#include "SkMatrix.h"
#include "SkScalar.h"

// Number of float items per entry in a DVM sample data array
#define NUM_SAMPLE_DATA 9

#define SAMPLE_X 0
#define SAMPLE_Y 1
#define SAMPLE_PRESSURE 2
#define SAMPLE_SIZE 3
#define SAMPLE_TOUCH_MAJOR 4
#define SAMPLE_TOUCH_MINOR 5
#define SAMPLE_TOOL_MAJOR 6
#define SAMPLE_TOOL_MINOR 7
#define SAMPLE_ORIENTATION 8


namespace android {

// ----------------------------------------------------------------------------

static struct {
    jclass clazz;

    jmethodID obtain;
    jmethodID recycle;

    jfieldID mDeviceId;
    jfieldID mSource;
    jfieldID mDownTimeNano;
    jfieldID mAction;
    jfieldID mXOffset;
    jfieldID mYOffset;
    jfieldID mXPrecision;
    jfieldID mYPrecision;
    jfieldID mEdgeFlags;
    jfieldID mMetaState;
    jfieldID mFlags;
    jfieldID mNumPointers;
    jfieldID mNumSamples;
    jfieldID mPointerIdentifiers;
    jfieldID mDataSamples;
    jfieldID mEventTimeNanoSamples;
    jfieldID mLastDataSampleIndex;
    jfieldID mLastEventTimeNanoSampleIndex;
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

    env->SetIntField(eventObj, gMotionEventClassInfo.mDeviceId,
            event->getDeviceId());
    env->SetIntField(eventObj, gMotionEventClassInfo.mSource,
            event->getSource());
    env->SetLongField(eventObj, gMotionEventClassInfo.mDownTimeNano,
            event->getDownTime());
    env->SetIntField(eventObj, gMotionEventClassInfo.mAction,
            event->getAction());
    env->SetFloatField(eventObj, gMotionEventClassInfo.mXOffset,
            event->getXOffset());
    env->SetFloatField(eventObj, gMotionEventClassInfo.mYOffset,
            event->getYOffset());
    env->SetFloatField(eventObj, gMotionEventClassInfo.mXPrecision,
            event->getXPrecision());
    env->SetFloatField(eventObj, gMotionEventClassInfo.mYPrecision,
            event->getYPrecision());
    env->SetIntField(eventObj, gMotionEventClassInfo.mEdgeFlags,
            event->getEdgeFlags());
    env->SetIntField(eventObj, gMotionEventClassInfo.mMetaState,
            event->getMetaState());
    env->SetIntField(eventObj, gMotionEventClassInfo.mFlags,
            event->getFlags());
    env->SetIntField(eventObj, gMotionEventClassInfo.mNumPointers,
            numPointers);
    env->SetIntField(eventObj, gMotionEventClassInfo.mNumSamples,
            numSamples);
    env->SetIntField(eventObj, gMotionEventClassInfo.mLastDataSampleIndex,
            (numSamples - 1) * numPointers * NUM_SAMPLE_DATA);
    env->SetIntField(eventObj, gMotionEventClassInfo.mLastEventTimeNanoSampleIndex,
            numSamples - 1);

    jintArray pointerIdentifierArray = jintArray(env->GetObjectField(eventObj,
            gMotionEventClassInfo.mPointerIdentifiers));
    jfloatArray dataSampleArray = jfloatArray(env->GetObjectField(eventObj,
            gMotionEventClassInfo.mDataSamples));
    jlongArray eventTimeNanoSampleArray = jlongArray(env->GetObjectField(eventObj,
            gMotionEventClassInfo.mEventTimeNanoSamples));

    jint* pointerIdentifiers = (jint*)env->GetPrimitiveArrayCritical(pointerIdentifierArray, NULL);
    jfloat* dataSamples = (jfloat*)env->GetPrimitiveArrayCritical(dataSampleArray, NULL);
    jlong* eventTimeNanoSamples = (jlong*)env->GetPrimitiveArrayCritical(
            eventTimeNanoSampleArray, NULL);

    const int32_t* srcPointerIdentifiers = event->getPointerIds();
    jint* destPointerIdentifiers = pointerIdentifiers;
    for (jint i = 0; i < numPointers; i++) {
        *(destPointerIdentifiers++) = *(srcPointerIdentifiers++);
    }

    const nsecs_t* srcSampleEventTimes = event->getSampleEventTimes();
    jlong* destEventTimeNanoSamples = eventTimeNanoSamples;
    for (jint i = 0; i < numSamples; i++) {
        *(destEventTimeNanoSamples++) = *(srcSampleEventTimes++);
    }

    const PointerCoords* srcSamplePointerCoords = event->getSamplePointerCoords();
    jfloat* destDataSamples = dataSamples;
    jint numItems = numSamples * numPointers;
    for (jint i = 0; i < numItems; i++) {
        *(destDataSamples++) = srcSamplePointerCoords->x;
        *(destDataSamples++) = srcSamplePointerCoords->y;
        *(destDataSamples++) = srcSamplePointerCoords->pressure;
        *(destDataSamples++) = srcSamplePointerCoords->size;
        *(destDataSamples++) = srcSamplePointerCoords->touchMajor;
        *(destDataSamples++) = srcSamplePointerCoords->touchMinor;
        *(destDataSamples++) = srcSamplePointerCoords->toolMajor;
        *(destDataSamples++) = srcSamplePointerCoords->toolMinor;
        *(destDataSamples++) = srcSamplePointerCoords->orientation;
        srcSamplePointerCoords += 1;
    }

    env->ReleasePrimitiveArrayCritical(pointerIdentifierArray, pointerIdentifiers, 0);
    env->ReleasePrimitiveArrayCritical(dataSampleArray, dataSamples, 0);
    env->ReleasePrimitiveArrayCritical(eventTimeNanoSampleArray, eventTimeNanoSamples, 0);

    env->DeleteLocalRef(pointerIdentifierArray);
    env->DeleteLocalRef(dataSampleArray);
    env->DeleteLocalRef(eventTimeNanoSampleArray);
    return eventObj;
}

void android_view_MotionEvent_toNative(JNIEnv* env, jobject eventObj,
        MotionEvent* event) {
    jint deviceId = env->GetIntField(eventObj, gMotionEventClassInfo.mDeviceId);
    jint source = env->GetIntField(eventObj, gMotionEventClassInfo.mSource);
    jlong downTimeNano = env->GetLongField(eventObj, gMotionEventClassInfo.mDownTimeNano);
    jint action = env->GetIntField(eventObj, gMotionEventClassInfo.mAction);
    jfloat xOffset = env->GetFloatField(eventObj, gMotionEventClassInfo.mXOffset);
    jfloat yOffset = env->GetFloatField(eventObj, gMotionEventClassInfo.mYOffset);
    jfloat xPrecision = env->GetFloatField(eventObj, gMotionEventClassInfo.mXPrecision);
    jfloat yPrecision = env->GetFloatField(eventObj, gMotionEventClassInfo.mYPrecision);
    jint edgeFlags = env->GetIntField(eventObj, gMotionEventClassInfo.mEdgeFlags);
    jint metaState = env->GetIntField(eventObj, gMotionEventClassInfo.mMetaState);
    jint flags = env->GetIntField(eventObj, gMotionEventClassInfo.mFlags);
    jint numPointers = env->GetIntField(eventObj, gMotionEventClassInfo.mNumPointers);
    jint numSamples = env->GetIntField(eventObj, gMotionEventClassInfo.mNumSamples);
    jintArray pointerIdentifierArray = jintArray(env->GetObjectField(eventObj,
            gMotionEventClassInfo.mPointerIdentifiers));
    jfloatArray dataSampleArray = jfloatArray(env->GetObjectField(eventObj,
            gMotionEventClassInfo.mDataSamples));
    jlongArray eventTimeNanoSampleArray = jlongArray(env->GetObjectField(eventObj,
            gMotionEventClassInfo.mEventTimeNanoSamples));

    LOG_FATAL_IF(numPointers == 0, "numPointers was zero");
    LOG_FATAL_IF(numSamples == 0, "numSamples was zero");

    jint* pointerIdentifiers = (jint*)env->GetPrimitiveArrayCritical(pointerIdentifierArray, NULL);
    jfloat* dataSamples = (jfloat*)env->GetPrimitiveArrayCritical(dataSampleArray, NULL);
    jlong* eventTimeNanoSamples = (jlong*)env->GetPrimitiveArrayCritical(
            eventTimeNanoSampleArray, NULL);

    jfloat* srcDataSamples = dataSamples;
    jlong* srcEventTimeNanoSamples = eventTimeNanoSamples;

    jlong sampleEventTime = *(srcEventTimeNanoSamples++);
    PointerCoords samplePointerCoords[MAX_POINTERS];
    for (jint j = 0; j < numPointers; j++) {
        samplePointerCoords[j].x = *(srcDataSamples++);
        samplePointerCoords[j].y = *(srcDataSamples++);
        samplePointerCoords[j].pressure = *(srcDataSamples++);
        samplePointerCoords[j].size = *(srcDataSamples++);
        samplePointerCoords[j].touchMajor = *(srcDataSamples++);
        samplePointerCoords[j].touchMinor = *(srcDataSamples++);
        samplePointerCoords[j].toolMajor = *(srcDataSamples++);
        samplePointerCoords[j].toolMinor = *(srcDataSamples++);
        samplePointerCoords[j].orientation = *(srcDataSamples++);
    }

    event->initialize(deviceId, source, action, flags, edgeFlags, metaState,
            xOffset, yOffset, xPrecision, yPrecision, downTimeNano, sampleEventTime,
            numPointers, pointerIdentifiers, samplePointerCoords);

    for (jint i = 1; i < numSamples; i++) {
        sampleEventTime = *(srcEventTimeNanoSamples++);
        for (jint j = 0; j < numPointers; j++) {
            samplePointerCoords[j].x = *(srcDataSamples++);
            samplePointerCoords[j].y = *(srcDataSamples++);
            samplePointerCoords[j].pressure = *(srcDataSamples++);
            samplePointerCoords[j].size = *(srcDataSamples++);
            samplePointerCoords[j].touchMajor = *(srcDataSamples++);
            samplePointerCoords[j].touchMinor = *(srcDataSamples++);
            samplePointerCoords[j].toolMajor = *(srcDataSamples++);
            samplePointerCoords[j].toolMinor = *(srcDataSamples++);
            samplePointerCoords[j].orientation = *(srcDataSamples++);
        }
        event->addSample(sampleEventTime, samplePointerCoords);
    }

    env->ReleasePrimitiveArrayCritical(pointerIdentifierArray, pointerIdentifiers, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(dataSampleArray, dataSamples, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(eventTimeNanoSampleArray, eventTimeNanoSamples, JNI_ABORT);

    env->DeleteLocalRef(pointerIdentifierArray);
    env->DeleteLocalRef(dataSampleArray);
    env->DeleteLocalRef(eventTimeNanoSampleArray);
}

void android_view_MotionEvent_recycle(JNIEnv* env, jobject eventObj) {
    env->CallVoidMethod(eventObj, gMotionEventClassInfo.recycle);
    if (env->ExceptionCheck()) {
        LOGW("An exception occurred while recycling a motion event.");
        LOGW_EX(env);
        env->ExceptionClear();
    }
}

static inline float transformAngle(const SkMatrix* matrix, float angleRadians) {
    // Construct and transform a vector oriented at the specified clockwise angle from vertical.
    // Coordinate system: down is increasing Y, right is increasing X.
    SkPoint vector;
    vector.fX = SkFloatToScalar(sinf(angleRadians));
    vector.fY = SkFloatToScalar(- cosf(angleRadians));
    matrix->mapVectors(& vector, 1);

    // Derive the transformed vector's clockwise angle from vertical.
    float result = atan2f(SkScalarToFloat(vector.fX), SkScalarToFloat(- vector.fY));
    if (result < - M_PI_2) {
        result += M_PI;
    } else if (result > M_PI_2) {
        result -= M_PI;
    }
    return result;
}

static void android_view_MotionEvent_nativeTransform(JNIEnv* env,
        jobject eventObj, jobject matrixObj) {
    SkMatrix* matrix = android_graphics_Matrix_getSkMatrix(env, matrixObj);

    jfloat oldXOffset = env->GetFloatField(eventObj, gMotionEventClassInfo.mXOffset);
    jfloat oldYOffset = env->GetFloatField(eventObj, gMotionEventClassInfo.mYOffset);
    jint numPointers = env->GetIntField(eventObj, gMotionEventClassInfo.mNumPointers);
    jint numSamples = env->GetIntField(eventObj, gMotionEventClassInfo.mNumSamples);
    jfloatArray dataSampleArray = jfloatArray(env->GetObjectField(eventObj,
            gMotionEventClassInfo.mDataSamples));
    jfloat* dataSamples = (jfloat*)env->GetPrimitiveArrayCritical(dataSampleArray, NULL);

    // The tricky part of this implementation is to preserve the value of
    // rawX and rawY.  So we apply the transformation to the first point
    // then derive an appropriate new X/Y offset that will preserve rawX and rawY.
    SkPoint point;
    jfloat rawX = dataSamples[SAMPLE_X];
    jfloat rawY = dataSamples[SAMPLE_Y];
    matrix->mapXY(SkFloatToScalar(rawX + oldXOffset), SkFloatToScalar(rawY + oldYOffset),
            & point);
    jfloat newX = SkScalarToFloat(point.fX);
    jfloat newY = SkScalarToFloat(point.fY);
    jfloat newXOffset = newX - rawX;
    jfloat newYOffset = newY - rawY;

    dataSamples[SAMPLE_ORIENTATION] = transformAngle(matrix, dataSamples[SAMPLE_ORIENTATION]);

    // Apply the transformation to all samples.
    jfloat* currentDataSample = dataSamples;
    jfloat* endDataSample = dataSamples + numPointers * numSamples * NUM_SAMPLE_DATA;
    for (;;) {
        currentDataSample += NUM_SAMPLE_DATA;
        if (currentDataSample == endDataSample) {
            break;
        }

        jfloat x = currentDataSample[SAMPLE_X] + oldXOffset;
        jfloat y = currentDataSample[SAMPLE_Y] + oldYOffset;
        matrix->mapXY(SkFloatToScalar(x), SkFloatToScalar(y), & point);
        currentDataSample[SAMPLE_X] = SkScalarToFloat(point.fX) - newXOffset;
        currentDataSample[SAMPLE_Y] = SkScalarToFloat(point.fY) - newYOffset;

        currentDataSample[SAMPLE_ORIENTATION] = transformAngle(matrix,
                currentDataSample[SAMPLE_ORIENTATION]);
    }

    env->ReleasePrimitiveArrayCritical(dataSampleArray, dataSamples, 0);

    env->SetFloatField(eventObj, gMotionEventClassInfo.mXOffset, newXOffset);
    env->SetFloatField(eventObj, gMotionEventClassInfo.mYOffset, newYOffset);

    env->DeleteLocalRef(dataSampleArray);
}

// ----------------------------------------------------------------------------

static JNINativeMethod gMotionEventMethods[] = {
    /* name, signature, funcPtr */
    { "nativeTransform",
            "(Landroid/graphics/Matrix;)V",
            (void*)android_view_MotionEvent_nativeTransform },
};

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
    int res = jniRegisterNativeMethods(env, "android/view/MotionEvent",
            gMotionEventMethods, NELEM(gMotionEventMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    FIND_CLASS(gMotionEventClassInfo.clazz, "android/view/MotionEvent");

    GET_STATIC_METHOD_ID(gMotionEventClassInfo.obtain, gMotionEventClassInfo.clazz,
            "obtain", "(II)Landroid/view/MotionEvent;");
    GET_METHOD_ID(gMotionEventClassInfo.recycle, gMotionEventClassInfo.clazz,
            "recycle", "()V");

    GET_FIELD_ID(gMotionEventClassInfo.mDeviceId, gMotionEventClassInfo.clazz,
            "mDeviceId", "I");
    GET_FIELD_ID(gMotionEventClassInfo.mSource, gMotionEventClassInfo.clazz,
            "mSource", "I");
    GET_FIELD_ID(gMotionEventClassInfo.mDownTimeNano, gMotionEventClassInfo.clazz,
            "mDownTimeNano", "J");
    GET_FIELD_ID(gMotionEventClassInfo.mAction, gMotionEventClassInfo.clazz,
            "mAction", "I");
    GET_FIELD_ID(gMotionEventClassInfo.mXOffset, gMotionEventClassInfo.clazz,
            "mXOffset", "F");
    GET_FIELD_ID(gMotionEventClassInfo.mYOffset, gMotionEventClassInfo.clazz,
            "mYOffset", "F");
    GET_FIELD_ID(gMotionEventClassInfo.mXPrecision, gMotionEventClassInfo.clazz,
            "mXPrecision", "F");
    GET_FIELD_ID(gMotionEventClassInfo.mYPrecision, gMotionEventClassInfo.clazz,
            "mYPrecision", "F");
    GET_FIELD_ID(gMotionEventClassInfo.mEdgeFlags, gMotionEventClassInfo.clazz,
            "mEdgeFlags", "I");
    GET_FIELD_ID(gMotionEventClassInfo.mMetaState, gMotionEventClassInfo.clazz,
            "mMetaState", "I");
    GET_FIELD_ID(gMotionEventClassInfo.mFlags, gMotionEventClassInfo.clazz,
            "mFlags", "I");
    GET_FIELD_ID(gMotionEventClassInfo.mNumPointers, gMotionEventClassInfo.clazz,
            "mNumPointers", "I");
    GET_FIELD_ID(gMotionEventClassInfo.mNumSamples, gMotionEventClassInfo.clazz,
            "mNumSamples", "I");
    GET_FIELD_ID(gMotionEventClassInfo.mPointerIdentifiers, gMotionEventClassInfo.clazz,
            "mPointerIdentifiers", "[I");
    GET_FIELD_ID(gMotionEventClassInfo.mDataSamples, gMotionEventClassInfo.clazz,
            "mDataSamples", "[F");
    GET_FIELD_ID(gMotionEventClassInfo.mEventTimeNanoSamples, gMotionEventClassInfo.clazz,
            "mEventTimeNanoSamples", "[J");
    GET_FIELD_ID(gMotionEventClassInfo.mLastDataSampleIndex, gMotionEventClassInfo.clazz,
            "mLastDataSampleIndex", "I");
    GET_FIELD_ID(gMotionEventClassInfo.mLastEventTimeNanoSampleIndex, gMotionEventClassInfo.clazz,
            "mLastEventTimeNanoSampleIndex", "I");

    return 0;
}

} // namespace android
