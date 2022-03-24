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

// Define LOG_TAG before <log/log.h> to overwrite the default value.
#define LOG_TAG "MeasurementCorrectionsJni"

#include "MeasurementCorrections.h"

#include "Utils.h"

using IMeasurementCorrections_V1_0 =
        android::hardware::gnss::measurement_corrections::V1_0::IMeasurementCorrections;
using IMeasurementCorrections_V1_1 =
        android::hardware::gnss::measurement_corrections::V1_1::IMeasurementCorrections;
using IMeasurementCorrections_Aidl =
        android::hardware::gnss::measurement_corrections::IMeasurementCorrectionsInterface;
using MeasurementCorrections_V1_0 =
        android::hardware::gnss::measurement_corrections::V1_0::MeasurementCorrections;
using MeasurementCorrections_V1_1 =
        android::hardware::gnss::measurement_corrections::V1_1::MeasurementCorrections;
using MeasurementCorrections_Aidl =
        android::hardware::gnss::measurement_corrections::MeasurementCorrections;
using GnssSingleSatCorrectionFlags_V1_0 =
        android::hardware::gnss::measurement_corrections::V1_0::GnssSingleSatCorrectionFlags;
using SingleSatCorrection_V1_0 =
        android::hardware::gnss::measurement_corrections::V1_0::SingleSatCorrection;
using SingleSatCorrection_V1_1 =
        android::hardware::gnss::measurement_corrections::V1_1::SingleSatCorrection;
using SingleSatCorrection_Aidl =
        android::hardware::gnss::measurement_corrections::SingleSatCorrection;
using ReflectingPlane_V1_0 =
        android::hardware::gnss::measurement_corrections::V1_0::ReflectingPlane;
using ReflectingPlane_Aidl = android::hardware::gnss::measurement_corrections::ReflectingPlane;
using ExcessPathInfo = SingleSatCorrection_Aidl::ExcessPathInfo;
using GnssConstellationType_V1_0 = android::hardware::gnss::V1_0::GnssConstellationType;
using GnssConstellationType_V2_0 = android::hardware::gnss::V2_0::GnssConstellationType;
using GnssConstellationType_Aidl = android::hardware::gnss::GnssConstellationType;

namespace android::gnss {

namespace {
jmethodID method_correctionsGetLatitudeDegrees;
jmethodID method_correctionsGetLongitudeDegrees;
jmethodID method_correctionsGetAltitudeMeters;
jmethodID method_correctionsGetHorPosUncMeters;
jmethodID method_correctionsGetVerPosUncMeters;
jmethodID method_correctionsGetToaGpsNanosecondsOfWeek;
jmethodID method_correctionsGetSingleSatCorrectionList;
jmethodID method_correctionsHasEnvironmentBearing;
jmethodID method_correctionsGetEnvironmentBearingDegrees;
jmethodID method_correctionsGetEnvironmentBearingUncertaintyDegrees;
jmethodID method_listSize;
jmethodID method_listGet;
jmethodID method_correctionSatFlags;
jmethodID method_correctionSatConstType;
jmethodID method_correctionSatId;
jmethodID method_correctionSatCarrierFreq;
jmethodID method_correctionSatIsLosProb;
jmethodID method_correctionSatEpl;
jmethodID method_correctionSatEplUnc;
jmethodID method_correctionSatRefPlane;
jmethodID method_correctionSatAttenuation;
jmethodID method_correctionSatExcessPathInfoList;
jmethodID method_correctionPlaneLatDeg;
jmethodID method_correctionPlaneLngDeg;
jmethodID method_correctionPlaneAltDeg;
jmethodID method_correctionPlaneAzimDeg;
jmethodID method_excessPathInfoFlags;
jmethodID method_excessPathInfoEpl;
jmethodID method_excessPathInfoEplUnc;
jmethodID method_excessPathInfoRefPlane;
jmethodID method_excessPathInfoAttenuation;
} // anonymous namespace

void MeasurementCorrections_class_init_once(JNIEnv* env, jclass clazz) {
    jclass measCorrClass = env->FindClass("android/location/GnssMeasurementCorrections");
    method_correctionsGetLatitudeDegrees =
            env->GetMethodID(measCorrClass, "getLatitudeDegrees", "()D");
    method_correctionsGetLongitudeDegrees =
            env->GetMethodID(measCorrClass, "getLongitudeDegrees", "()D");
    method_correctionsGetAltitudeMeters =
            env->GetMethodID(measCorrClass, "getAltitudeMeters", "()D");
    method_correctionsGetHorPosUncMeters =
            env->GetMethodID(measCorrClass, "getHorizontalPositionUncertaintyMeters", "()D");
    method_correctionsGetVerPosUncMeters =
            env->GetMethodID(measCorrClass, "getVerticalPositionUncertaintyMeters", "()D");
    method_correctionsGetToaGpsNanosecondsOfWeek =
            env->GetMethodID(measCorrClass, "getToaGpsNanosecondsOfWeek", "()J");
    method_correctionsGetSingleSatCorrectionList =
            env->GetMethodID(measCorrClass, "getSingleSatelliteCorrectionList",
                             "()Ljava/util/List;");
    method_correctionsHasEnvironmentBearing =
            env->GetMethodID(measCorrClass, "hasEnvironmentBearing", "()Z");
    method_correctionsGetEnvironmentBearingDegrees =
            env->GetMethodID(measCorrClass, "getEnvironmentBearingDegrees", "()F");
    method_correctionsGetEnvironmentBearingUncertaintyDegrees =
            env->GetMethodID(measCorrClass, "getEnvironmentBearingUncertaintyDegrees", "()F");

    jclass corrListClass = env->FindClass("java/util/List");
    method_listSize = env->GetMethodID(corrListClass, "size", "()I");
    method_listGet = env->GetMethodID(corrListClass, "get", "(I)Ljava/lang/Object;");

    jclass singleSatCorrClass = env->FindClass("android/location/GnssSingleSatCorrection");
    method_correctionSatFlags =
            env->GetMethodID(singleSatCorrClass, "getSingleSatelliteCorrectionFlags", "()I");
    method_correctionSatConstType =
            env->GetMethodID(singleSatCorrClass, "getConstellationType", "()I");
    method_correctionSatId = env->GetMethodID(singleSatCorrClass, "getSatelliteId", "()I");
    method_correctionSatCarrierFreq =
            env->GetMethodID(singleSatCorrClass, "getCarrierFrequencyHz", "()F");
    method_correctionSatIsLosProb =
            env->GetMethodID(singleSatCorrClass, "getProbabilityLineOfSight", "()F");
    method_correctionSatEpl =
            env->GetMethodID(singleSatCorrClass, "getExcessPathLengthMeters", "()F");
    method_correctionSatEplUnc =
            env->GetMethodID(singleSatCorrClass, "getExcessPathLengthUncertaintyMeters", "()F");
    method_correctionSatRefPlane = env->GetMethodID(singleSatCorrClass, "getReflectingPlane",
                                                    "()Landroid/location/GnssReflectingPlane;");
    method_correctionSatAttenuation =
            env->GetMethodID(singleSatCorrClass, "getCombinedAttenuationDb", "()F");
    method_correctionSatExcessPathInfoList =
            env->GetMethodID(singleSatCorrClass, "getGnssExcessPathInfoList", "()Ljava/util/List;");

    jclass refPlaneClass = env->FindClass("android/location/GnssReflectingPlane");
    method_correctionPlaneLatDeg = env->GetMethodID(refPlaneClass, "getLatitudeDegrees", "()D");
    method_correctionPlaneLngDeg = env->GetMethodID(refPlaneClass, "getLongitudeDegrees", "()D");
    method_correctionPlaneAltDeg = env->GetMethodID(refPlaneClass, "getAltitudeMeters", "()D");
    method_correctionPlaneAzimDeg = env->GetMethodID(refPlaneClass, "getAzimuthDegrees", "()D");

    jclass excessPathInfoClass = env->FindClass("android/location/GnssExcessPathInfo");
    method_excessPathInfoFlags = env->GetMethodID(excessPathInfoClass, "getFlags", "()I");
    method_excessPathInfoEpl =
            env->GetMethodID(excessPathInfoClass, "getExcessPathLengthMeters", "()F");
    method_excessPathInfoEplUnc =
            env->GetMethodID(excessPathInfoClass, "getExcessPathLengthUncertaintyMeters", "()F");
    method_excessPathInfoRefPlane = env->GetMethodID(excessPathInfoClass, "getReflectingPlane",
                                                     "()Landroid/location/GnssReflectingPlane;");
    method_excessPathInfoAttenuation =
            env->GetMethodID(excessPathInfoClass, "getAttenuationDb", "()F");
}

template <>
bool MeasurementCorrectionsUtil::translateMeasurementCorrections(
        JNIEnv* env, jobject correctionsObj, MeasurementCorrections_V1_0& corrections) {
    jobject singleSatCorrectionList =
            env->CallObjectMethod(correctionsObj, method_correctionsGetSingleSatCorrectionList);
    if (singleSatCorrectionList == nullptr) return false;
    auto len = env->CallIntMethod(singleSatCorrectionList, method_listSize);

    jdouble latitudeDegreesCorr =
            env->CallDoubleMethod(correctionsObj, method_correctionsGetLatitudeDegrees);
    jdouble longitudeDegreesCorr =
            env->CallDoubleMethod(correctionsObj, method_correctionsGetLongitudeDegrees);
    jdouble altitudeDegreesCorr =
            env->CallDoubleMethod(correctionsObj, method_correctionsGetAltitudeMeters);
    jdouble horizontalPositionUncertaintyMeters =
            env->CallDoubleMethod(correctionsObj, method_correctionsGetHorPosUncMeters);
    jdouble verticalPositionUncertaintyMeters =
            env->CallDoubleMethod(correctionsObj, method_correctionsGetVerPosUncMeters);
    jlong toaGpsNanosOfWeek =
            env->CallLongMethod(correctionsObj, method_correctionsGetToaGpsNanosecondsOfWeek);

    corrections.latitudeDegrees = latitudeDegreesCorr;
    corrections.longitudeDegrees = longitudeDegreesCorr;
    corrections.altitudeMeters = altitudeDegreesCorr;
    corrections.horizontalPositionUncertaintyMeters = horizontalPositionUncertaintyMeters;
    corrections.verticalPositionUncertaintyMeters = verticalPositionUncertaintyMeters;
    corrections.toaGpsNanosecondsOfWeek = static_cast<uint64_t>(toaGpsNanosOfWeek);

    hardware::hidl_vec<SingleSatCorrection_V1_0> list(len);
    MeasurementCorrectionsUtil::getSingleSatCorrectionList_1_0(env, singleSatCorrectionList, list);
    env->DeleteLocalRef(singleSatCorrectionList);
    corrections.satCorrections = list;
    return true;
}

template <>
bool MeasurementCorrectionsUtil::translateMeasurementCorrections(
        JNIEnv* env, jobject correctionsObj, MeasurementCorrections_V1_1& corrections) {
    jobject singleSatCorrectionList =
            env->CallObjectMethod(correctionsObj, method_correctionsGetSingleSatCorrectionList);
    if (singleSatCorrectionList == nullptr) return false;
    auto len = env->CallIntMethod(singleSatCorrectionList, method_listSize);

    MeasurementCorrections_V1_0 measurementCorrections_1_0;
    translateMeasurementCorrections<MeasurementCorrections_V1_0>(env, correctionsObj,
                                                                 measurementCorrections_1_0);
    measurementCorrections_1_0.satCorrections.resize(0);

    jboolean hasEnvironmentBearingCorr =
            env->CallBooleanMethod(correctionsObj, method_correctionsHasEnvironmentBearing);
    jfloat environmentBearingDegreesCorr =
            env->CallFloatMethod(correctionsObj, method_correctionsGetEnvironmentBearingDegrees);
    jfloat environmentBearingUncertaintyDegreesCorr =
            env->CallFloatMethod(correctionsObj,
                                 method_correctionsGetEnvironmentBearingUncertaintyDegrees);

    hardware::hidl_vec<SingleSatCorrection_V1_1> list(len);
    MeasurementCorrectionsUtil::getSingleSatCorrectionList_1_1(env, singleSatCorrectionList, list);
    env->DeleteLocalRef(singleSatCorrectionList);

    corrections.v1_0 = measurementCorrections_1_0;
    corrections.hasEnvironmentBearing = static_cast<bool>(hasEnvironmentBearingCorr);
    corrections.environmentBearingDegrees = environmentBearingDegreesCorr;
    corrections.environmentBearingUncertaintyDegrees = environmentBearingUncertaintyDegreesCorr;
    corrections.satCorrections = list;
    return true;
}

template <>
bool MeasurementCorrectionsUtil::translateMeasurementCorrections(
        JNIEnv* env, jobject correctionsObj, MeasurementCorrections_Aidl& corrections) {
    jobject singleSatCorrectionList =
            env->CallObjectMethod(correctionsObj, method_correctionsGetSingleSatCorrectionList);
    if (singleSatCorrectionList == nullptr) return false;
    auto len = env->CallIntMethod(singleSatCorrectionList, method_listSize);

    jdouble latitudeDegreesCorr =
            env->CallDoubleMethod(correctionsObj, method_correctionsGetLatitudeDegrees);
    jdouble longitudeDegreesCorr =
            env->CallDoubleMethod(correctionsObj, method_correctionsGetLongitudeDegrees);
    jdouble altitudeDegreesCorr =
            env->CallDoubleMethod(correctionsObj, method_correctionsGetAltitudeMeters);
    jdouble horizontalPositionUncertaintyMeters =
            env->CallDoubleMethod(correctionsObj, method_correctionsGetHorPosUncMeters);
    jdouble verticalPositionUncertaintyMeters =
            env->CallDoubleMethod(correctionsObj, method_correctionsGetVerPosUncMeters);
    jlong toaGpsNanosOfWeek =
            env->CallLongMethod(correctionsObj, method_correctionsGetToaGpsNanosecondsOfWeek);

    corrections.latitudeDegrees = static_cast<double>(latitudeDegreesCorr);
    corrections.longitudeDegrees = static_cast<double>(longitudeDegreesCorr);
    corrections.altitudeMeters = static_cast<double>(altitudeDegreesCorr);
    corrections.horizontalPositionUncertaintyMeters =
            static_cast<double>(horizontalPositionUncertaintyMeters);
    corrections.verticalPositionUncertaintyMeters =
            static_cast<double>(verticalPositionUncertaintyMeters);
    corrections.toaGpsNanosecondsOfWeek = static_cast<int64_t>(toaGpsNanosOfWeek);

    jboolean hasEnvironmentBearingCorr =
            env->CallBooleanMethod(correctionsObj, method_correctionsHasEnvironmentBearing);
    jfloat environmentBearingDegreesCorr =
            env->CallFloatMethod(correctionsObj, method_correctionsGetEnvironmentBearingDegrees);
    jfloat environmentBearingUncertaintyDegreesCorr =
            env->CallFloatMethod(correctionsObj,
                                 method_correctionsGetEnvironmentBearingUncertaintyDegrees);

    std::vector<SingleSatCorrection_Aidl> list(len);
    MeasurementCorrectionsUtil::getSingleSatCorrectionList_Aidl(env, singleSatCorrectionList, list);
    env->DeleteLocalRef(singleSatCorrectionList);

    corrections.hasEnvironmentBearing = static_cast<bool>(hasEnvironmentBearingCorr);
    corrections.environmentBearingDegrees = environmentBearingDegreesCorr;
    corrections.environmentBearingUncertaintyDegrees = environmentBearingUncertaintyDegreesCorr;
    corrections.satCorrections = list;
    return true;
}

// Implementation of MeasurementCorrections (AIDL HAL)

MeasurementCorrectionsIface_Aidl::MeasurementCorrectionsIface_Aidl(
        const sp<IMeasurementCorrections_Aidl>& iMeasurementCorrections)
      : mIMeasurementCorrectionsAidl(iMeasurementCorrections) {
    assert(mIMeasurementCorrectionsAidl != nullptr);
}

jboolean MeasurementCorrectionsIface_Aidl::setCorrections(JNIEnv* env, jobject correctionsObj) {
    MeasurementCorrections_Aidl measurementCorrections_aidl;
    if (!MeasurementCorrectionsUtil::translateMeasurementCorrections<
                MeasurementCorrections_Aidl>(env, correctionsObj, measurementCorrections_aidl)) {
        ALOGI("Empty correction list injected....Returning with no HAL injection");
        return JNI_TRUE;
    }
    auto status = mIMeasurementCorrectionsAidl->setCorrections(measurementCorrections_aidl);
    return checkAidlStatus(status, "IMeasurementCorrectionsAidl setCorrections() failed");
}

jboolean MeasurementCorrectionsIface_Aidl::setCallback(
        const std::unique_ptr<MeasurementCorrectionsCallback>& callback) {
    auto status = mIMeasurementCorrectionsAidl->setCallback(callback->getAidl());
    return checkAidlStatus(status, "IMeasurementCorrectionsAidl setCallback() failed.");
}

// Implementation of MeasurementCorrectionsIface_V1_0

MeasurementCorrectionsIface_V1_0::MeasurementCorrectionsIface_V1_0(
        const sp<IMeasurementCorrections_V1_0>& iMeasurementCorrections)
      : mIMeasurementCorrections_V1_0(iMeasurementCorrections) {
    assert(mIMeasurementCorrections_V1_0 != nullptr);
}

jboolean MeasurementCorrectionsIface_V1_0::setCorrections(JNIEnv* env, jobject correctionsObj) {
    MeasurementCorrections_V1_0 measurementCorrections_1_0;
    if (!MeasurementCorrectionsUtil::translateMeasurementCorrections<
                MeasurementCorrections_V1_0>(env, correctionsObj, measurementCorrections_1_0)) {
        ALOGI("Empty correction list injected....Returning with no HAL injection");
        return JNI_TRUE;
    }
    auto result = mIMeasurementCorrections_V1_0->setCorrections(measurementCorrections_1_0);
    return checkHidlReturn(result, "IMeasurementCorrections 1.0 setCorrections() failed.");
}

jboolean MeasurementCorrectionsIface_V1_0::setCallback(
        const std::unique_ptr<MeasurementCorrectionsCallback>& callback) {
    auto result = mIMeasurementCorrections_V1_0->setCallback(callback->getHidl());
    return checkHidlReturn(result, "IMeasurementCorrections_V1_0 setCallback() failed.");
}

// Implementation of MeasurementCorrectionsIface_V1_1

MeasurementCorrectionsIface_V1_1::MeasurementCorrectionsIface_V1_1(
        const sp<IMeasurementCorrections_V1_1>& iMeasurementCorrections)
      : mIMeasurementCorrections_V1_1(iMeasurementCorrections) {
    assert(mIMeasurementCorrections_V1_1 != nullptr);
}

jboolean MeasurementCorrectionsIface_V1_1::setCorrections(JNIEnv* env, jobject correctionsObj) {
    MeasurementCorrections_V1_1 measurementCorrections_1_1;
    if (!MeasurementCorrectionsUtil::translateMeasurementCorrections<
                MeasurementCorrections_V1_1>(env, correctionsObj, measurementCorrections_1_1)) {
        ALOGI("Empty correction list injected....Returning with no HAL injection");
        return JNI_TRUE;
    }
    auto result = mIMeasurementCorrections_V1_1->setCorrections_1_1(measurementCorrections_1_1);
    return checkHidlReturn(result, "IMeasurementCorrections 1.1 setCorrections() failed.");
}

jboolean MeasurementCorrectionsIface_V1_1::setCallback(
        const std::unique_ptr<MeasurementCorrectionsCallback>& callback) {
    auto result = mIMeasurementCorrections_V1_1->setCallback(callback->getHidl());
    return checkHidlReturn(result, "IMeasurementCorrections_V1_1 setCallback() failed.");
}

SingleSatCorrection_V1_0
MeasurementCorrectionsUtil::getSingleSatCorrection_1_0_withoutConstellation(
        JNIEnv* env, jobject singleSatCorrectionObj) {
    uint16_t corrFlags = static_cast<uint16_t>(
            env->CallIntMethod(singleSatCorrectionObj, method_correctionSatFlags));
    jint satId = env->CallIntMethod(singleSatCorrectionObj, method_correctionSatId);
    jfloat carrierFreqHz =
            env->CallFloatMethod(singleSatCorrectionObj, method_correctionSatCarrierFreq);
    jfloat probSatIsLos =
            env->CallFloatMethod(singleSatCorrectionObj, method_correctionSatIsLosProb);
    jfloat eplMeters = env->CallFloatMethod(singleSatCorrectionObj, method_correctionSatEpl);
    jfloat eplUncMeters = env->CallFloatMethod(singleSatCorrectionObj, method_correctionSatEplUnc);

    ReflectingPlane_V1_0 reflectingPlane;
    if ((corrFlags & GnssSingleSatCorrectionFlags_V1_0::HAS_REFLECTING_PLANE) != 0) {
        jobject reflectingPlaneObj =
                env->CallObjectMethod(singleSatCorrectionObj, method_correctionSatRefPlane);
        MeasurementCorrectionsUtil::setReflectingPlane<ReflectingPlane_V1_0>(env,
                                                                             reflectingPlaneObj,
                                                                             reflectingPlane);
        env->DeleteLocalRef(reflectingPlaneObj);
    }
    SingleSatCorrection_V1_0 singleSatCorrection = {
            .singleSatCorrectionFlags = corrFlags,
            .svid = static_cast<uint16_t>(satId),
            .carrierFrequencyHz = carrierFreqHz,
            .probSatIsLos = probSatIsLos,
            .excessPathLengthMeters = eplMeters,
            .excessPathLengthUncertaintyMeters = eplUncMeters,
            .reflectingPlane = reflectingPlane,
    };
    return singleSatCorrection;
}

SingleSatCorrection_Aidl MeasurementCorrectionsUtil::getSingleSatCorrection_Aidl(
        JNIEnv* env, jobject singleSatCorrectionObj) {
    int32_t corrFlags = static_cast<int32_t>(
            env->CallIntMethod(singleSatCorrectionObj, method_correctionSatFlags));
    jint constType = env->CallIntMethod(singleSatCorrectionObj, method_correctionSatConstType);
    jint satId = env->CallIntMethod(singleSatCorrectionObj, method_correctionSatId);
    jfloat carrierFreqHz =
            env->CallFloatMethod(singleSatCorrectionObj, method_correctionSatCarrierFreq);
    jfloat probSatIsLos =
            env->CallFloatMethod(singleSatCorrectionObj, method_correctionSatIsLosProb);
    jfloat eplMeters = env->CallFloatMethod(singleSatCorrectionObj, method_correctionSatEpl);
    jfloat eplUncMeters = env->CallFloatMethod(singleSatCorrectionObj, method_correctionSatEplUnc);
    jfloat attenuationDb =
            env->CallFloatMethod(singleSatCorrectionObj, method_correctionSatAttenuation);
    std::vector<ExcessPathInfo> excessPathInfos =
            MeasurementCorrectionsUtil::getExcessPathInfoList(env, singleSatCorrectionObj);

    SingleSatCorrection_Aidl singleSatCorrection;
    singleSatCorrection.singleSatCorrectionFlags = corrFlags;
    singleSatCorrection.constellation = static_cast<GnssConstellationType_Aidl>(constType);
    singleSatCorrection.svid = static_cast<int32_t>(satId);
    singleSatCorrection.carrierFrequencyHz = carrierFreqHz;
    singleSatCorrection.probSatIsLos = probSatIsLos;
    singleSatCorrection.combinedExcessPathLengthMeters = eplMeters;
    singleSatCorrection.combinedExcessPathLengthUncertaintyMeters = eplUncMeters;
    singleSatCorrection.combinedAttenuationDb = attenuationDb;
    singleSatCorrection.excessPathInfos = excessPathInfos;

    return singleSatCorrection;
}

void MeasurementCorrectionsUtil::getSingleSatCorrectionList_1_0(
        JNIEnv* env, jobject singleSatCorrectionList,
        hardware::hidl_vec<SingleSatCorrection_V1_0>& list) {
    for (uint16_t i = 0; i < list.size(); ++i) {
        jobject singleSatCorrectionObj =
                env->CallObjectMethod(singleSatCorrectionList, method_listGet, i);
        SingleSatCorrection_V1_0 singleSatCorrection =
                getSingleSatCorrection_1_0_withoutConstellation(env, singleSatCorrectionObj);

        jint constType = env->CallIntMethod(singleSatCorrectionObj, method_correctionSatConstType);

        singleSatCorrection.constellation = static_cast<GnssConstellationType_V1_0>(constType),

        list[i] = singleSatCorrection;
        env->DeleteLocalRef(singleSatCorrectionObj);
    }
}

void MeasurementCorrectionsUtil::getSingleSatCorrectionList_1_1(
        JNIEnv* env, jobject singleSatCorrectionList,
        hardware::hidl_vec<SingleSatCorrection_V1_1>& list) {
    for (uint16_t i = 0; i < list.size(); ++i) {
        jobject singleSatCorrectionObj =
                env->CallObjectMethod(singleSatCorrectionList, method_listGet, i);

        SingleSatCorrection_V1_0 singleSatCorrection_1_0 =
                getSingleSatCorrection_1_0_withoutConstellation(env, singleSatCorrectionObj);

        jint constType = env->CallIntMethod(singleSatCorrectionObj, method_correctionSatConstType);

        SingleSatCorrection_V1_1 singleSatCorrection_1_1 = {
                .v1_0 = singleSatCorrection_1_0,
                .constellation = static_cast<GnssConstellationType_V2_0>(constType),
        };

        list[i] = singleSatCorrection_1_1;
        env->DeleteLocalRef(singleSatCorrectionObj);
    }
}

void MeasurementCorrectionsUtil::getSingleSatCorrectionList_Aidl(
        JNIEnv* env, jobject singleSatCorrectionList, std::vector<SingleSatCorrection_Aidl>& list) {
    for (uint16_t i = 0; i < list.size(); ++i) {
        jobject singleSatCorrectionObj =
                env->CallObjectMethod(singleSatCorrectionList, method_listGet, i);

        SingleSatCorrection_Aidl singleSatCorrection_Aidl =
                getSingleSatCorrection_Aidl(env, singleSatCorrectionObj);

        list[i] = singleSatCorrection_Aidl;
        env->DeleteLocalRef(singleSatCorrectionObj);
    }
}

template <>
void MeasurementCorrectionsUtil::setReflectingPlaneAzimuthDegrees<ReflectingPlane_V1_0>(
        ReflectingPlane_V1_0& reflectingPlane, double azimuthDegreeRefPlane) {
    reflectingPlane.azimuthDegrees = azimuthDegreeRefPlane;
}

template <>
void MeasurementCorrectionsUtil::setReflectingPlaneAzimuthDegrees<ReflectingPlane_Aidl>(
        ReflectingPlane_Aidl& reflectingPlane, double azimuthDegreeRefPlane) {
    reflectingPlane.reflectingPlaneAzimuthDegrees = azimuthDegreeRefPlane;
}

std::vector<ExcessPathInfo> MeasurementCorrectionsUtil::getExcessPathInfoList(
        JNIEnv* env, jobject singleSatCorrectionObj) {
    jobject excessPathInfoListObj =
            env->CallObjectMethod(singleSatCorrectionObj, method_correctionSatExcessPathInfoList);

    int len = env->CallIntMethod(excessPathInfoListObj, method_listSize);
    std::vector<ExcessPathInfo> list(len);
    for (int i = 0; i < len; ++i) {
        jobject excessPathInfoObj = env->CallObjectMethod(excessPathInfoListObj, method_listGet, i);
        list[i] = getExcessPathInfo(env, excessPathInfoObj);
        env->DeleteLocalRef(excessPathInfoObj);
    }
    env->DeleteLocalRef(excessPathInfoListObj);
    return list;
}

ExcessPathInfo MeasurementCorrectionsUtil::getExcessPathInfo(JNIEnv* env,
                                                             jobject excessPathInfoObj) {
    ExcessPathInfo excessPathInfo;
    jint flags = env->CallIntMethod(excessPathInfoObj, method_excessPathInfoFlags);
    excessPathInfo.excessPathInfoFlags = flags;
    if ((flags & ExcessPathInfo::EXCESS_PATH_INFO_HAS_EXCESS_PATH_LENGTH) != 0) {
        jfloat epl = env->CallFloatMethod(excessPathInfoObj, method_excessPathInfoEpl);
        excessPathInfo.excessPathLengthMeters = epl;
    }
    if ((flags & ExcessPathInfo::EXCESS_PATH_INFO_HAS_EXCESS_PATH_LENGTH_UNC) != 0) {
        jfloat eplUnc = env->CallFloatMethod(excessPathInfoObj, method_excessPathInfoEplUnc);
        excessPathInfo.excessPathLengthUncertaintyMeters = eplUnc;
    }
    if ((flags & ExcessPathInfo::EXCESS_PATH_INFO_HAS_REFLECTING_PLANE) != 0) {
        ReflectingPlane_Aidl reflectingPlane;
        jobject reflectingPlaneObj =
                env->CallObjectMethod(excessPathInfoObj, method_excessPathInfoRefPlane);
        MeasurementCorrectionsUtil::setReflectingPlane<ReflectingPlane_Aidl>(env,
                                                                             reflectingPlaneObj,
                                                                             reflectingPlane);
        env->DeleteLocalRef(reflectingPlaneObj);
        excessPathInfo.reflectingPlane = reflectingPlane;
    }
    if ((flags & ExcessPathInfo::EXCESS_PATH_INFO_HAS_ATTENUATION) != 0) {
        jfloat attenuation =
                env->CallFloatMethod(excessPathInfoObj, method_excessPathInfoAttenuation);
        excessPathInfo.attenuationDb = attenuation;
    }
    return excessPathInfo;
}

} // namespace android::gnss
