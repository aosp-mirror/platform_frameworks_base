/*
 * Copyright (C) 2020 The Android Open Source Project
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

#ifndef _ANDROID_SERVER_GNSS_GNSSMEASUREMENTCALLBACK_H
#define _ANDROID_SERVER_GNSS_GNSSMEASUREMENTCALLBACK_H

#pragma once

#ifndef LOG_TAG
#error LOG_TAG must be defined before including this file.
#endif

#include <android/hardware/gnss/1.0/IGnssMeasurement.h>
#include <android/hardware/gnss/1.1/IGnssMeasurement.h>
#include <android/hardware/gnss/2.0/IGnssMeasurement.h>
#include <android/hardware/gnss/2.1/IGnssMeasurement.h>
#include <android/hardware/gnss/BnGnssMeasurementCallback.h>
#include <log/log.h>
#include "Utils.h"
#include "jni.h"

namespace android::gnss {

extern jclass class_gnssMeasurementsEvent;
extern jclass class_gnssMeasurement;
extern jclass class_gnssClock;

extern jmethodID method_gnssMeasurementsEventCtor;
extern jmethodID method_gnssClockCtor;
extern jmethodID method_gnssMeasurementCtor;
extern jmethodID method_reportMeasurementData;

void GnssMeasurement_class_init_once(JNIEnv* env, jclass& clazz);

void setMeasurementData(JNIEnv* env, jobject& callbacksObj, jobject clock,
                        jobjectArray measurementArray);

class GnssMeasurementCallbackAidl : public hardware::gnss::BnGnssMeasurementCallback {
public:
    GnssMeasurementCallbackAidl(jobject& callbacksObj) : mCallbacksObj(callbacksObj) {}
    android::binder::Status gnssMeasurementCb(const hardware::gnss::GnssData& data) override;

private:
    void translateSingleGnssMeasurement(JNIEnv* env,
                                        const hardware::gnss::GnssMeasurement& measurement,
                                        JavaObject& object);

    jobjectArray translateAllGnssMeasurements(
            JNIEnv* env, const std::vector<hardware::gnss::GnssMeasurement>& measurements);

    void translateAndSetGnssData(const hardware::gnss::GnssData& data);

    void translateGnssClock(JNIEnv* env, const hardware::gnss::GnssData& data, JavaObject& object);

    jobject& mCallbacksObj;
};

/*
 * GnssMeasurementCallback implements the callback methods required for the
 * GnssMeasurement interface.
 */
class GnssMeasurementCallbackHidl : public hardware::gnss::V2_1::IGnssMeasurementCallback {
public:
    GnssMeasurementCallbackHidl(jobject& callbacksObj) : mCallbacksObj(callbacksObj) {}
    hardware::Return<void> gnssMeasurementCb_2_1(
            const hardware::gnss::V2_1::IGnssMeasurementCallback::GnssData& data) override;
    hardware::Return<void> gnssMeasurementCb_2_0(
            const hardware::gnss::V2_0::IGnssMeasurementCallback::GnssData& data) override;
    hardware::Return<void> gnssMeasurementCb(
            const hardware::gnss::V1_1::IGnssMeasurementCallback::GnssData& data) override;
    hardware::Return<void> GnssMeasurementCb(
            const hardware::gnss::V1_0::IGnssMeasurementCallback::GnssData& data) override;

private:
    template <class T>
    void translateSingleGnssMeasurement(const T& measurement, JavaObject& object);

    template <class T>
    jobjectArray translateAllGnssMeasurements(JNIEnv* env, const T* measurements, size_t count);

    template <class T>
    void translateAndSetGnssData(const T& data);

    template <class T>
    size_t getMeasurementCount(const T& data);

    template <class T>
    void translateGnssClock(const T& data, JavaObject& object);

    jobject& mCallbacksObj;
};

class GnssMeasurementCallback {
public:
    GnssMeasurementCallback(jobject& callbacksObj) : mCallbacksObj(callbacksObj) {}
    sp<GnssMeasurementCallbackAidl> getAidl() {
        if (callbackAidl == nullptr) {
            callbackAidl = sp<GnssMeasurementCallbackAidl>::make(mCallbacksObj);
        }
        return callbackAidl;
    }

    sp<GnssMeasurementCallbackHidl> getHidl() {
        if (callbackHidl == nullptr) {
            callbackHidl = sp<GnssMeasurementCallbackHidl>::make(mCallbacksObj);
        }
        return callbackHidl;
    }

private:
    jobject& mCallbacksObj;
    sp<GnssMeasurementCallbackAidl> callbackAidl;
    sp<GnssMeasurementCallbackHidl> callbackHidl;
};

template <class T>
void GnssMeasurementCallbackHidl::translateAndSetGnssData(const T& data) {
    JNIEnv* env = getJniEnv();

    JavaObject gnssClockJavaObject(env, class_gnssClock, method_gnssClockCtor);
    translateGnssClock(data, gnssClockJavaObject);
    jobject clock = gnssClockJavaObject.get();

    size_t count = getMeasurementCount(data);
    jobjectArray measurementArray =
            translateAllGnssMeasurements(env, data.measurements.data(), count);
    setMeasurementData(env, mCallbacksObj, clock, measurementArray);

    env->DeleteLocalRef(clock);
    env->DeleteLocalRef(measurementArray);
}

template <>
size_t GnssMeasurementCallbackHidl::getMeasurementCount<
        hardware::gnss::V1_0::IGnssMeasurementCallback::GnssData>(
        const hardware::gnss::V1_0::IGnssMeasurementCallback::GnssData& data);

template <class T>
size_t GnssMeasurementCallbackHidl::getMeasurementCount(const T& data) {
    return data.measurements.size();
}

// Preallocate object as: JavaObject object(env, "android/location/GnssMeasurement");
template <>
void GnssMeasurementCallbackHidl::translateSingleGnssMeasurement<
        hardware::gnss::V1_0::IGnssMeasurementCallback::GnssMeasurement>(
        const hardware::gnss::V1_0::IGnssMeasurementCallback::GnssMeasurement& measurement,
        JavaObject& object);

// Preallocate object as: JavaObject object(env, "android/location/GnssMeasurement");
template <>
void GnssMeasurementCallbackHidl::translateSingleGnssMeasurement<
        hardware::gnss::V1_1::IGnssMeasurementCallback::GnssMeasurement>(
        const hardware::gnss::V1_1::IGnssMeasurementCallback::GnssMeasurement& measurement_V1_1,
        JavaObject& object);

// Preallocate object as: JavaObject object(env, "android/location/GnssMeasurement");
template <>
void GnssMeasurementCallbackHidl::translateSingleGnssMeasurement<
        hardware::gnss::V2_0::IGnssMeasurementCallback::GnssMeasurement>(
        const hardware::gnss::V2_0::IGnssMeasurementCallback::GnssMeasurement& measurement_V2_0,
        JavaObject& object);

// Preallocate object as: JavaObject object(env, "android/location/GnssMeasurement");
template <>
void GnssMeasurementCallbackHidl::translateSingleGnssMeasurement<
        hardware::gnss::V2_1::IGnssMeasurementCallback::GnssMeasurement>(
        const hardware::gnss::V2_1::IGnssMeasurementCallback::GnssMeasurement& measurement_V2_1,
        JavaObject& object);

template <class T>
void GnssMeasurementCallbackHidl::translateGnssClock(const T& data, JavaObject& object) {
    translateGnssClock(data.clock, object);
}

template <>
void GnssMeasurementCallbackHidl::translateGnssClock(
        const hardware::gnss::V1_0::IGnssMeasurementCallback::GnssClock& clock, JavaObject& object);

template <>
void GnssMeasurementCallbackHidl::translateGnssClock(
        const hardware::gnss::V2_1::IGnssMeasurementCallback::GnssClock& clock, JavaObject& object);

template <>
void GnssMeasurementCallbackHidl::translateGnssClock(
        const hardware::gnss::V2_0::IGnssMeasurementCallback::GnssData& data, JavaObject& object);

template <>
void GnssMeasurementCallbackHidl::translateGnssClock(
        const hardware::gnss::V2_1::IGnssMeasurementCallback::GnssData& data, JavaObject& object);

template <class T>
jobjectArray GnssMeasurementCallbackHidl::translateAllGnssMeasurements(JNIEnv* env,
                                                                       const T* measurements,
                                                                       size_t count) {
    if (count == 0) {
        return nullptr;
    }

    jobjectArray gnssMeasurementArray =
            env->NewObjectArray(count, class_gnssMeasurement, nullptr /* initialElement */);

    for (uint16_t i = 0; i < count; ++i) {
        JavaObject object(env, class_gnssMeasurement, method_gnssMeasurementCtor);
        translateSingleGnssMeasurement(measurements[i], object);
        jobject gnssMeasurement = object.get();
        env->SetObjectArrayElement(gnssMeasurementArray, i, gnssMeasurement);
        env->DeleteLocalRef(gnssMeasurement);
    }

    return gnssMeasurementArray;
}

} // namespace android::gnss

#endif // _ANDROID_SERVER_GNSS_GNSSMEASUREMENTCALLBACK_H