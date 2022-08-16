/*
 * Copyright (C) 2022 The Android Open Source Project
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

#ifndef _ANDROID_SERVER_MEASUREMENTCORRECTIONS_H
#define _ANDROID_SERVER_MEASUREMENTCORRECTIONS_H

#pragma once

#ifndef LOG_TAG
#error LOG_TAG must be defined before including this file.
#endif

#include <android/hardware/gnss/measurement_corrections/BnMeasurementCorrectionsInterface.h>
#include <log/log.h>

#include "MeasurementCorrectionsCallback.h"
#include "jni.h"

namespace android::gnss {

namespace {
extern jmethodID method_correctionsGetLatitudeDegrees;
extern jmethodID method_correctionsGetLongitudeDegrees;
extern jmethodID method_correctionsGetAltitudeMeters;
extern jmethodID method_correctionsGetHorPosUncMeters;
extern jmethodID method_correctionsGetVerPosUncMeters;
extern jmethodID method_correctionsGetToaGpsNanosecondsOfWeek;
extern jmethodID method_correctionsGetSingleSatCorrectionList;
extern jmethodID method_correctionsHasEnvironmentBearing;
extern jmethodID method_correctionsGetEnvironmentBearingDegrees;
extern jmethodID method_correctionsGetEnvironmentBearingUncertaintyDegrees;
extern jmethodID method_listSize;
extern jmethodID method_listGet;
extern jmethodID method_correctionSatFlags;
extern jmethodID method_correctionSatConstType;
extern jmethodID method_correctionSatId;
extern jmethodID method_correctionSatCarrierFreq;
extern jmethodID method_correctionSatIsLosProb;
extern jmethodID method_correctionSatEpl;
extern jmethodID method_correctionSatEplUnc;
extern jmethodID method_correctionSatRefPlane;
extern jmethodID method_correctionSatExcessPathInfos;
extern jmethodID method_correctionPlaneLatDeg;
extern jmethodID method_correctionPlaneLngDeg;
extern jmethodID method_correctionPlaneAltDeg;
extern jmethodID method_correctionPlaneAzimDeg;
} // anonymous namespace

void MeasurementCorrections_class_init_once(JNIEnv* env, jclass clazz);

class MeasurementCorrectionsInterface {
public:
    virtual ~MeasurementCorrectionsInterface() {}
    virtual jboolean setCorrections(JNIEnv* env, jobject correctionsObj) = 0;
    virtual jboolean setCallback(
            const std::unique_ptr<MeasurementCorrectionsCallback>& callback) = 0;
};

class MeasurementCorrectionsIface_Aidl : public MeasurementCorrectionsInterface {
public:
    MeasurementCorrectionsIface_Aidl(
            const sp<android::hardware::gnss::measurement_corrections::
                             IMeasurementCorrectionsInterface>& iMeasurementCorrections);
    jboolean setCorrections(JNIEnv* env, jobject correctionsObj) override;
    jboolean setCallback(const std::unique_ptr<MeasurementCorrectionsCallback>& callback) override;

private:
    const sp<android::hardware::gnss::measurement_corrections::IMeasurementCorrectionsInterface>
            mIMeasurementCorrectionsAidl;
};

class MeasurementCorrectionsIface_V1_0 : public MeasurementCorrectionsInterface {
public:
    MeasurementCorrectionsIface_V1_0(
            const sp<android::hardware::gnss::measurement_corrections::V1_0::
                             IMeasurementCorrections>& iMeasurementCorrections);
    jboolean setCorrections(JNIEnv* env, jobject correctionsObj) override;
    jboolean setCallback(const std::unique_ptr<MeasurementCorrectionsCallback>& callback) override;

private:
    const sp<android::hardware::gnss::measurement_corrections::V1_0::IMeasurementCorrections>
            mIMeasurementCorrections_V1_0;
};

class MeasurementCorrectionsIface_V1_1 : public MeasurementCorrectionsInterface {
public:
    MeasurementCorrectionsIface_V1_1(
            const sp<android::hardware::gnss::measurement_corrections::V1_1::
                             IMeasurementCorrections>& iMeasurementCorrections);
    jboolean setCorrections(JNIEnv* env, jobject correctionsObj) override;
    jboolean setCallback(const std::unique_ptr<MeasurementCorrectionsCallback>& callback) override;

private:
    const sp<android::hardware::gnss::measurement_corrections::V1_1::IMeasurementCorrections>
            mIMeasurementCorrections_V1_1;
};

struct MeasurementCorrectionsUtil {
    static android::hardware::gnss::measurement_corrections::V1_0::SingleSatCorrection
    getSingleSatCorrection_1_0_withoutConstellation(JNIEnv* env, jobject singleSatCorrectionObj);
    static android::hardware::gnss::measurement_corrections::SingleSatCorrection
    getSingleSatCorrection_Aidl(JNIEnv* env, jobject singleSatCorrectionObj);
    static void getSingleSatCorrectionList_1_1(
            JNIEnv* env, jobject singleSatCorrectionList,
            hardware::hidl_vec<
                    android::hardware::gnss::measurement_corrections::V1_1::SingleSatCorrection>&
                    list);
    static void getSingleSatCorrectionList_1_0(
            JNIEnv* env, jobject singleSatCorrectionList,
            hardware::hidl_vec<
                    android::hardware::gnss::measurement_corrections::V1_0::SingleSatCorrection>&
                    list);
    static void getSingleSatCorrectionList_Aidl(
            JNIEnv* env, jobject singleSatCorrectionList,
            std::vector<android::hardware::gnss::measurement_corrections::SingleSatCorrection>&
                    list);
    template <class T>
    static bool translateMeasurementCorrections(JNIEnv* env, jobject correctionsObj,
                                                T& corrections);
    template <class T>
    static void setReflectingPlane(JNIEnv* env, jobject reflectingPlaneObj, T& reflectingPlane);
    template <class T>
    static void setReflectingPlaneAzimuthDegrees(T& reflectingPlane, double azimuthDegreeRefPlane);

    static std::vector<
            android::hardware::gnss::measurement_corrections::SingleSatCorrection::ExcessPathInfo>
    getExcessPathInfoList(JNIEnv* env, jobject correctionsObj);
    static android::hardware::gnss::measurement_corrections::SingleSatCorrection::ExcessPathInfo
    getExcessPathInfo(JNIEnv* env, jobject correctionsObj);
};

template <class T>
void MeasurementCorrectionsUtil::setReflectingPlane(JNIEnv* env, jobject reflectingPlaneObj,
                                                    T& reflectingPlane) {
    jdouble latitudeDegreesRefPlane =
            env->CallDoubleMethod(reflectingPlaneObj, method_correctionPlaneLatDeg);
    jdouble longitudeDegreesRefPlane =
            env->CallDoubleMethod(reflectingPlaneObj, method_correctionPlaneLngDeg);
    jdouble altitudeDegreesRefPlane =
            env->CallDoubleMethod(reflectingPlaneObj, method_correctionPlaneAltDeg);
    jdouble azimuthDegreeRefPlane =
            env->CallDoubleMethod(reflectingPlaneObj, method_correctionPlaneAzimDeg);
    reflectingPlane.latitudeDegrees = latitudeDegreesRefPlane;
    reflectingPlane.longitudeDegrees = longitudeDegreesRefPlane;
    reflectingPlane.altitudeMeters = altitudeDegreesRefPlane;
    setReflectingPlaneAzimuthDegrees<T>(reflectingPlane, azimuthDegreeRefPlane);
}

} // namespace android::gnss

#endif // _ANDROID_SERVER_MEASUREMENTCORRECTIONS_H
