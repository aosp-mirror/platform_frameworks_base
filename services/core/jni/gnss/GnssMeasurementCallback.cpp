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

#define LOG_TAG "GnssMeasCbJni"

#include "GnssMeasurementCallback.h"

namespace android::gnss {

using binder::Status;
using hardware::gnss::CorrelationVector;
using hardware::gnss::ElapsedRealtime;
using hardware::gnss::GnssClock;
using hardware::gnss::GnssData;
using hardware::gnss::GnssMeasurement;
using hardware::gnss::SatellitePvt;

jclass class_arrayList;
jclass class_clockInfo;
jclass class_correlationVectorBuilder;
jclass class_gnssMeasurementsEvent;
jclass class_gnssMeasurement;
jclass class_gnssClock;
jclass class_positionEcef;
jclass class_satellitePvtBuilder;
jclass class_velocityEcef;

jmethodID method_arrayListAdd;
jmethodID method_arrayListCtor;
jmethodID method_correlationVectorBuilderBuild;
jmethodID method_correlationVectorBuilderCtor;
jmethodID method_correlationVectorBuilderSetFrequencyOffsetMetersPerSecond;
jmethodID method_correlationVectorBuilderSetMagnitude;
jmethodID method_correlationVectorBuilderSetSamplingStartMeters;
jmethodID method_correlationVectorBuilderSetSamplingWidthMeters;
jmethodID method_gnssMeasurementsEventCtor;
jmethodID method_gnssMeasurementsSetCorrelationVectors;
jmethodID method_gnssMeasurementsSetSatellitePvt;
jmethodID method_gnssClockCtor;
jmethodID method_gnssMeasurementCtor;
jmethodID method_reportMeasurementData;
jmethodID method_satellitePvtBuilderBuild;
jmethodID method_satellitePvtBuilderCtor;
jmethodID method_satellitePvtBuilderSetPositionEcef;
jmethodID method_satellitePvtBuilderSetVelocityEcef;
jmethodID method_satellitePvtBuilderSetClockInfo;
jmethodID method_satellitePvtBuilderSetIonoDelayMeters;
jmethodID method_satellitePvtBuilderSetTropoDelayMeters;
jmethodID method_positionEcef;
jmethodID method_velocityEcef;
jmethodID method_clockInfo;

void GnssMeasurement_class_init_once(JNIEnv* env, jclass& clazz) {
    method_reportMeasurementData = env->GetMethodID(clazz, "reportMeasurementData",
                                                    "(Landroid/location/GnssMeasurementsEvent;)V");
    jclass gnssMeasurementsEventClass = env->FindClass("android/location/GnssMeasurementsEvent");
    class_gnssMeasurementsEvent = (jclass)env->NewGlobalRef(gnssMeasurementsEventClass);
    method_gnssMeasurementsEventCtor =
            env->GetMethodID(class_gnssMeasurementsEvent, "<init>",
                             "(Landroid/location/GnssClock;[Landroid/location/GnssMeasurement;)V");

    jclass gnssMeasurementClass = env->FindClass("android/location/GnssMeasurement");
    class_gnssMeasurement = (jclass)env->NewGlobalRef(gnssMeasurementClass);
    method_gnssMeasurementCtor = env->GetMethodID(class_gnssMeasurement, "<init>", "()V");
    method_gnssMeasurementsSetSatellitePvt =
            env->GetMethodID(class_gnssMeasurement, "setSatellitePvt",
                             "(Landroid/location/SatellitePvt;)V");
    method_gnssMeasurementsSetCorrelationVectors =
            env->GetMethodID(class_gnssMeasurement, "setCorrelationVectors",
                             "(Ljava/util/Collection;)V");

    jclass gnssClockClass = env->FindClass("android/location/GnssClock");
    class_gnssClock = (jclass)env->NewGlobalRef(gnssClockClass);
    method_gnssClockCtor = env->GetMethodID(class_gnssClock, "<init>", "()V");

    jclass satellitePvtBuilder = env->FindClass("android/location/SatellitePvt$Builder");
    class_satellitePvtBuilder = (jclass)env->NewGlobalRef(satellitePvtBuilder);
    method_satellitePvtBuilderCtor = env->GetMethodID(class_satellitePvtBuilder, "<init>", "()V");
    method_satellitePvtBuilderSetPositionEcef =
            env->GetMethodID(class_satellitePvtBuilder, "setPositionEcef",
                             "(Landroid/location/SatellitePvt$PositionEcef;)"
                             "Landroid/location/SatellitePvt$Builder;");
    method_satellitePvtBuilderSetVelocityEcef =
            env->GetMethodID(class_satellitePvtBuilder, "setVelocityEcef",
                             "(Landroid/location/SatellitePvt$VelocityEcef;)"
                             "Landroid/location/SatellitePvt$Builder;");
    method_satellitePvtBuilderSetClockInfo =
            env->GetMethodID(class_satellitePvtBuilder, "setClockInfo",
                             "(Landroid/location/SatellitePvt$ClockInfo;)"
                             "Landroid/location/SatellitePvt$Builder;");
    method_satellitePvtBuilderSetIonoDelayMeters =
            env->GetMethodID(class_satellitePvtBuilder, "setIonoDelayMeters",
                             "(D)Landroid/location/SatellitePvt$Builder;");
    method_satellitePvtBuilderSetTropoDelayMeters =
            env->GetMethodID(class_satellitePvtBuilder, "setTropoDelayMeters",
                             "(D)Landroid/location/SatellitePvt$Builder;");
    method_satellitePvtBuilderBuild = env->GetMethodID(class_satellitePvtBuilder, "build",
                                                       "()Landroid/location/SatellitePvt;");

    jclass positionEcefClass = env->FindClass("android/location/SatellitePvt$PositionEcef");
    class_positionEcef = (jclass)env->NewGlobalRef(positionEcefClass);
    method_positionEcef = env->GetMethodID(class_positionEcef, "<init>", "(DDDD)V");

    jclass velocityEcefClass = env->FindClass("android/location/SatellitePvt$VelocityEcef");
    class_velocityEcef = (jclass)env->NewGlobalRef(velocityEcefClass);
    method_velocityEcef = env->GetMethodID(class_velocityEcef, "<init>", "(DDDD)V");

    jclass clockInfoClass = env->FindClass("android/location/SatellitePvt$ClockInfo");
    class_clockInfo = (jclass)env->NewGlobalRef(clockInfoClass);
    method_clockInfo = env->GetMethodID(class_clockInfo, "<init>", "(DDD)V");

    jclass correlationVectorBuilder = env->FindClass("android/location/CorrelationVector$Builder");
    class_correlationVectorBuilder = (jclass)env->NewGlobalRef(correlationVectorBuilder);
    method_correlationVectorBuilderCtor =
            env->GetMethodID(class_correlationVectorBuilder, "<init>", "()V");
    method_correlationVectorBuilderSetMagnitude =
            env->GetMethodID(class_correlationVectorBuilder, "setMagnitude",
                             "([I)Landroid/location/CorrelationVector$Builder;");
    method_correlationVectorBuilderSetFrequencyOffsetMetersPerSecond =
            env->GetMethodID(class_correlationVectorBuilder, "setFrequencyOffsetMetersPerSecond",
                             "(D)Landroid/location/CorrelationVector$Builder;");
    method_correlationVectorBuilderSetSamplingStartMeters =
            env->GetMethodID(class_correlationVectorBuilder, "setSamplingStartMeters",
                             "(D)Landroid/location/CorrelationVector$Builder;");
    method_correlationVectorBuilderSetSamplingWidthMeters =
            env->GetMethodID(class_correlationVectorBuilder, "setSamplingWidthMeters",
                             "(D)Landroid/location/CorrelationVector$Builder;");
    method_correlationVectorBuilderBuild =
            env->GetMethodID(class_correlationVectorBuilder, "build",
                             "()Landroid/location/CorrelationVector;");

    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    class_arrayList = (jclass)env->NewGlobalRef(arrayListClass);
    method_arrayListCtor = env->GetMethodID(class_arrayList, "<init>", "()V");
    method_arrayListAdd = env->GetMethodID(class_arrayList, "add", "(Ljava/lang/Object;)Z");
}

void setMeasurementData(JNIEnv* env, jobject& callbacksObj, jobject clock,
                        jobjectArray measurementArray) {
    jobject gnssMeasurementsEvent =
            env->NewObject(class_gnssMeasurementsEvent, method_gnssMeasurementsEventCtor, clock,
                           measurementArray);

    env->CallVoidMethod(callbacksObj, method_reportMeasurementData, gnssMeasurementsEvent);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    env->DeleteLocalRef(gnssMeasurementsEvent);
}

template <class T_Measurement, class T_Flags>
void setMeasurementFields_V1_0(const T_Measurement& measurement, JavaObject& object) {
    uint32_t flags = static_cast<uint32_t>(measurement.flags);

    SET(Svid, static_cast<int32_t>(measurement.svid));
    SET(TimeOffsetNanos, measurement.timeOffsetNs);
    SET(State, static_cast<int32_t>(measurement.state));
    SET(ReceivedSvTimeNanos, measurement.receivedSvTimeInNs);
    SET(ReceivedSvTimeUncertaintyNanos, measurement.receivedSvTimeUncertaintyInNs);
    SET(PseudorangeRateMetersPerSecond, measurement.pseudorangeRateMps);
    SET(PseudorangeRateUncertaintyMetersPerSecond, measurement.pseudorangeRateUncertaintyMps);
    SET(AccumulatedDeltaRangeState,
        (static_cast<int32_t>(measurement.accumulatedDeltaRangeState) &
         ~ADR_STATE_HALF_CYCLE_REPORTED)); // Half Cycle state not reported from Hardware in V1_0
    SET(AccumulatedDeltaRangeMeters, measurement.accumulatedDeltaRangeM);
    SET(AccumulatedDeltaRangeUncertaintyMeters, measurement.accumulatedDeltaRangeUncertaintyM);

    // Intentionally not copying deprecated fields of carrierCycles,
    // carrierPhase, carrierPhaseUncertainty

    SET(MultipathIndicator, static_cast<int32_t>(measurement.multipathIndicator));

    if (flags & static_cast<uint32_t>(T_Flags::HAS_SNR)) {
        SET(SnrInDb, measurement.snrDb);
    }

    if (flags & static_cast<uint32_t>(T_Flags::HAS_AUTOMATIC_GAIN_CONTROL)) {
        SET(AutomaticGainControlLevelInDb, measurement.agcLevelDb);
    }
}

template <class T_Measurement, class T_Flags>
void setMeasurementFields_V2_1(const T_Measurement& measurement, JavaObject& object) {
    SET(BasebandCn0DbHz, measurement.basebandCN0DbHz);

    if (measurement.flags & T_Flags::HAS_FULL_ISB) {
        SET(FullInterSignalBiasNanos, measurement.fullInterSignalBiasNs);
    }

    if (measurement.flags & T_Flags::HAS_FULL_ISB_UNCERTAINTY) {
        SET(FullInterSignalBiasUncertaintyNanos, measurement.fullInterSignalBiasUncertaintyNs);
    }

    if (measurement.flags & T_Flags::HAS_SATELLITE_ISB) {
        SET(SatelliteInterSignalBiasNanos, measurement.satelliteInterSignalBiasNs);
    }

    if (measurement.flags & T_Flags::HAS_SATELLITE_ISB_UNCERTAINTY) {
        SET(SatelliteInterSignalBiasUncertaintyNanos,
            measurement.satelliteInterSignalBiasUncertaintyNs);
    }
}

template <class T_Clock, class T_Flags>
void setClockFields_V1_0(const T_Clock& clock, JavaObject& object) {
    uint32_t flags = static_cast<uint32_t>(clock.gnssClockFlags);
    if (flags & static_cast<uint32_t>(T_Flags::HAS_LEAP_SECOND)) {
        SET(LeapSecond, static_cast<int32_t>(clock.leapSecond));
    }

    if (flags & static_cast<uint32_t>(T_Flags::HAS_TIME_UNCERTAINTY)) {
        SET(TimeUncertaintyNanos, clock.timeUncertaintyNs);
    }

    if (flags & static_cast<uint32_t>(T_Flags::HAS_FULL_BIAS)) {
        SET(FullBiasNanos, clock.fullBiasNs);
    }

    if (flags & static_cast<uint32_t>(T_Flags::HAS_BIAS)) {
        SET(BiasNanos, clock.biasNs);
    }

    if (flags & static_cast<uint32_t>(T_Flags::HAS_BIAS_UNCERTAINTY)) {
        SET(BiasUncertaintyNanos, clock.biasUncertaintyNs);
    }

    if (flags & static_cast<uint32_t>(T_Flags::HAS_DRIFT)) {
        SET(DriftNanosPerSecond, clock.driftNsps);
    }

    if (flags & static_cast<uint32_t>(T_Flags::HAS_DRIFT_UNCERTAINTY)) {
        SET(DriftUncertaintyNanosPerSecond, clock.driftUncertaintyNsps);
    }

    SET(TimeNanos, clock.timeNs);
    SET(HardwareClockDiscontinuityCount, clock.hwClockDiscontinuityCount);
}

template <class T_Clock, class T_Flags>
void setClockFields_V2_1(const T_Clock& clock, JavaObject& object) {
    JNIEnv* env = getJniEnv();
    SET(ReferenceConstellationTypeForIsb,
        static_cast<int32_t>(clock.referenceSignalTypeForIsb.constellation));
    SET(ReferenceCarrierFrequencyHzForIsb, clock.referenceSignalTypeForIsb.carrierFrequencyHz);

    jstring referenceCodeTypeForIsb =
            env->NewStringUTF(clock.referenceSignalTypeForIsb.codeType.c_str());
    SET(ReferenceCodeTypeForIsb, referenceCodeTypeForIsb);
    env->DeleteLocalRef(referenceCodeTypeForIsb);
}

template <class T_ElapsedRealtime, class T_Flags>
void setElapsedRealtimeFields(const T_ElapsedRealtime& elapsedRealtime, JavaObject& object) {
    uint32_t flags = static_cast<uint32_t>(elapsedRealtime.flags);
    if (flags & T_Flags::HAS_TIMESTAMP_NS) {
        SET(ElapsedRealtimeNanos, static_cast<uint64_t>(elapsedRealtime.timestampNs));
    }
    if (flags & T_Flags::HAS_TIME_UNCERTAINTY_NS) {
        SET(ElapsedRealtimeUncertaintyNanos,
            static_cast<double>(elapsedRealtime.timeUncertaintyNs));
    }
}

// Implementation of GnssMeasurementCallbackAidl class.

Status GnssMeasurementCallbackAidl::gnssMeasurementCb(const GnssData& data) {
    ALOGD("%s", __func__);
    translateAndSetGnssData(data);
    return Status::ok();
}

void GnssMeasurementCallbackAidl::translateAndSetGnssData(const GnssData& data) {
    JNIEnv* env = getJniEnv();

    JavaObject gnssClockJavaObject(env, class_gnssClock, method_gnssClockCtor);
    translateGnssClock(env, data, gnssClockJavaObject);
    jobject clock = gnssClockJavaObject.get();

    jobjectArray measurementArray = translateAllGnssMeasurements(env, data.measurements);
    setMeasurementData(env, mCallbacksObj, clock, measurementArray);

    env->DeleteLocalRef(clock);
    env->DeleteLocalRef(measurementArray);
}

void GnssMeasurementCallbackAidl::translateSingleGnssMeasurement(JNIEnv* env,
                                                                 const GnssMeasurement& measurement,
                                                                 JavaObject& object) {
    setMeasurementFields_V1_0<GnssMeasurement, GnssMeasurement>(measurement, object);
    setMeasurementFields_V2_1<GnssMeasurement, GnssMeasurement>(measurement, object);

    SET(Cn0DbHz, measurement.antennaCN0DbHz);
    SET(ConstellationType, static_cast<int32_t>(measurement.signalType.constellation));

    if (measurement.flags & static_cast<uint32_t>(GnssMeasurement::HAS_CARRIER_FREQUENCY)) {
        SET(CarrierFrequencyHz, static_cast<float>(measurement.signalType.carrierFrequencyHz));
    }

    if (measurement.flags & static_cast<uint32_t>(GnssMeasurement::HAS_SATELLITE_PVT)) {
        const SatellitePvt& satellitePvt = measurement.satellitePvt;
        jobject positionEcef = env->NewObject(class_positionEcef, method_positionEcef,
                                              satellitePvt.satPosEcef.posXMeters,
                                              satellitePvt.satPosEcef.posYMeters,
                                              satellitePvt.satPosEcef.posZMeters,
                                              satellitePvt.satPosEcef.ureMeters);
        jobject velocityEcef =
                env->NewObject(class_velocityEcef, method_velocityEcef,
                               satellitePvt.satVelEcef.velXMps, satellitePvt.satVelEcef.velYMps,
                               satellitePvt.satVelEcef.velZMps, satellitePvt.satVelEcef.ureRateMps);
        jobject clockInfo = env->NewObject(class_clockInfo, method_clockInfo,
                                           satellitePvt.satClockInfo.satHardwareCodeBiasMeters,
                                           satellitePvt.satClockInfo.satTimeCorrectionMeters,
                                           satellitePvt.satClockInfo.satClkDriftMps);
        jobject satellitePvtBuilderObject =
                env->NewObject(class_satellitePvtBuilder, method_satellitePvtBuilderCtor);

        env->CallObjectMethod(satellitePvtBuilderObject, method_satellitePvtBuilderSetPositionEcef,
                              positionEcef);
        env->CallObjectMethod(satellitePvtBuilderObject, method_satellitePvtBuilderSetVelocityEcef,
                              velocityEcef);
        env->CallObjectMethod(satellitePvtBuilderObject, method_satellitePvtBuilderSetClockInfo,
                              clockInfo);
        env->CallObjectMethod(satellitePvtBuilderObject,
                              method_satellitePvtBuilderSetIonoDelayMeters,
                              satellitePvt.ionoDelayMeters);
        env->CallObjectMethod(satellitePvtBuilderObject,
                              method_satellitePvtBuilderSetTropoDelayMeters,
                              satellitePvt.tropoDelayMeters);
        jobject satellitePvtObject =
                env->CallObjectMethod(satellitePvtBuilderObject, method_satellitePvtBuilderBuild);

        env->CallVoidMethod(object.get(), method_gnssMeasurementsSetSatellitePvt,
                            satellitePvtObject);

        env->DeleteLocalRef(positionEcef);
        env->DeleteLocalRef(velocityEcef);
        env->DeleteLocalRef(clockInfo);
        env->DeleteLocalRef(satellitePvtBuilderObject);
        env->DeleteLocalRef(satellitePvtObject);
    }

    if (measurement.flags & static_cast<uint32_t>(GnssMeasurement::HAS_CORRELATION_VECTOR)) {
        jobject correlationVectorList = env->NewObject(class_arrayList, method_arrayListCtor);
        for (uint16_t i = 0; i < measurement.correlationVectors.size(); ++i) {
            const CorrelationVector& correlationVector = measurement.correlationVectors[i];
            const std::vector<int32_t>& magnitudeVector = correlationVector.magnitude;

            jsize numMagnitude = magnitudeVector.size();
            jintArray magnitudeArray = env->NewIntArray(numMagnitude);
            env->SetIntArrayRegion(magnitudeArray, 0, numMagnitude,
                                   reinterpret_cast<const jint*>(magnitudeVector.data()));

            jobject correlationVectorBuilderObject =
                    env->NewObject(class_correlationVectorBuilder,
                                   method_correlationVectorBuilderCtor);
            env->CallObjectMethod(correlationVectorBuilderObject,
                                  method_correlationVectorBuilderSetMagnitude, magnitudeArray);
            env->CallObjectMethod(correlationVectorBuilderObject,
                                  method_correlationVectorBuilderSetFrequencyOffsetMetersPerSecond,
                                  correlationVector.frequencyOffsetMps);
            env->CallObjectMethod(correlationVectorBuilderObject,
                                  method_correlationVectorBuilderSetSamplingStartMeters,
                                  correlationVector.samplingStartM);
            env->CallObjectMethod(correlationVectorBuilderObject,
                                  method_correlationVectorBuilderSetSamplingWidthMeters,
                                  correlationVector.samplingWidthM);
            jobject correlationVectorObject =
                    env->CallObjectMethod(correlationVectorBuilderObject,
                                          method_correlationVectorBuilderBuild);

            env->CallBooleanMethod(correlationVectorList, method_arrayListAdd,
                                   correlationVectorObject);

            env->DeleteLocalRef(magnitudeArray);
            env->DeleteLocalRef(correlationVectorBuilderObject);
            env->DeleteLocalRef(correlationVectorObject);
        }
        env->CallVoidMethod(object.get(), method_gnssMeasurementsSetCorrelationVectors,
                            correlationVectorList);
        env->DeleteLocalRef(correlationVectorList);
    }

    jstring codeType = env->NewStringUTF(measurement.signalType.codeType.c_str());
    SET(CodeType, codeType);
    env->DeleteLocalRef(codeType);
}

jobjectArray GnssMeasurementCallbackAidl::translateAllGnssMeasurements(
        JNIEnv* env, const std::vector<GnssMeasurement>& measurements) {
    if (measurements.size() == 0) {
        return nullptr;
    }

    jobjectArray gnssMeasurementArray =
            env->NewObjectArray(measurements.size(), class_gnssMeasurement,
                                nullptr /* initialElement */);

    for (uint16_t i = 0; i < measurements.size(); ++i) {
        JavaObject object(env, class_gnssMeasurement, method_gnssMeasurementCtor);
        translateSingleGnssMeasurement(env, measurements[i], object);
        jobject gnssMeasurement = object.get();
        env->SetObjectArrayElement(gnssMeasurementArray, i, gnssMeasurement);
        env->DeleteLocalRef(gnssMeasurement);
    }

    return gnssMeasurementArray;
}

void GnssMeasurementCallbackAidl::translateGnssClock(JNIEnv* env, const GnssData& data,
                                                     JavaObject& object) {
    setElapsedRealtimeFields<ElapsedRealtime, ElapsedRealtime>(data.elapsedRealtime, object);
    setClockFields_V1_0<GnssClock, GnssClock>(data.clock, object);
    setClockFields_V2_1<GnssClock, GnssClock>(data.clock, object);
}

// Implementation of GnssMeasurementCallbackHidl class.

hardware::Return<void> GnssMeasurementCallbackHidl::gnssMeasurementCb_2_1(
        const hardware::gnss::V2_1::IGnssMeasurementCallback::GnssData& data) {
    translateAndSetGnssData(data);
    return hardware::Void();
}

hardware::Return<void> GnssMeasurementCallbackHidl::gnssMeasurementCb_2_0(
        const hardware::gnss::V2_0::IGnssMeasurementCallback::GnssData& data) {
    translateAndSetGnssData(data);
    return hardware::Void();
}

hardware::Return<void> GnssMeasurementCallbackHidl::gnssMeasurementCb(
        const hardware::gnss::V1_1::IGnssMeasurementCallback::GnssData& data) {
    translateAndSetGnssData(data);
    return hardware::Void();
}

hardware::Return<void> GnssMeasurementCallbackHidl::GnssMeasurementCb(
        const hardware::gnss::V1_0::IGnssMeasurementCallback::GnssData& data) {
    translateAndSetGnssData(data);
    return hardware::Void();
}

template <>
size_t GnssMeasurementCallbackHidl::getMeasurementCount<
        hardware::gnss::V1_0::IGnssMeasurementCallback::GnssData>(
        const hardware::gnss::V1_0::IGnssMeasurementCallback::GnssData& data) {
    return data.measurementCount;
}

// Preallocate object as: JavaObject object(env, "android/location/GnssMeasurement");
template <>
void GnssMeasurementCallbackHidl::translateSingleGnssMeasurement<
        hardware::gnss::V1_0::IGnssMeasurementCallback::GnssMeasurement>(
        const hardware::gnss::V1_0::IGnssMeasurementCallback::GnssMeasurement& measurement,
        JavaObject& object) {
    setMeasurementFields_V1_0<hardware::gnss::V1_0::IGnssMeasurementCallback::GnssMeasurement,
                              GnssMeasurementFlags>(measurement, object);

    SET(ConstellationType, static_cast<int32_t>(measurement.constellation));
    SET(Cn0DbHz, measurement.cN0DbHz);
    if (measurement.flags & static_cast<uint32_t>(GnssMeasurementFlags::HAS_CARRIER_FREQUENCY)) {
        SET(CarrierFrequencyHz, measurement.carrierFrequencyHz);
    }
}

// Preallocate object as: JavaObject object(env, "android/location/GnssMeasurement");
template <>
void GnssMeasurementCallbackHidl::translateSingleGnssMeasurement<
        hardware::gnss::V1_1::IGnssMeasurementCallback::GnssMeasurement>(
        const hardware::gnss::V1_1::IGnssMeasurementCallback::GnssMeasurement& measurement_V1_1,
        JavaObject& object) {
    translateSingleGnssMeasurement(measurement_V1_1.v1_0, object);

    // Set the V1_1 flag, and mark that new field has valid information for Java Layer
    SET(AccumulatedDeltaRangeState,
        (static_cast<int32_t>(measurement_V1_1.accumulatedDeltaRangeState) |
         ADR_STATE_HALF_CYCLE_REPORTED));
}

// Preallocate object as: JavaObject object(env, "android/location/GnssMeasurement");
template <>
void GnssMeasurementCallbackHidl::translateSingleGnssMeasurement<
        hardware::gnss::V2_0::IGnssMeasurementCallback::GnssMeasurement>(
        const hardware::gnss::V2_0::IGnssMeasurementCallback::GnssMeasurement& measurement_V2_0,
        JavaObject& object) {
    JNIEnv* env = getJniEnv();
    translateSingleGnssMeasurement(measurement_V2_0.v1_1, object);

    jstring codeType = env->NewStringUTF(measurement_V2_0.codeType.c_str());
    SET(CodeType, codeType);

    // Overwrite with v2_0.state since V2_0.v1_1.v1_0.state is deprecated.
    SET(State, static_cast<int32_t>(measurement_V2_0.state));

    // Overwrite with v2_0.constellation since V2_0.v1_1.v1_0.constellation is deprecated.
    SET(ConstellationType, static_cast<int32_t>(measurement_V2_0.constellation));

    if (codeType) {
        env->DeleteLocalRef(codeType);
    }
}

// Preallocate object as: JavaObject object(env, "android/location/GnssMeasurement");
template <>
void GnssMeasurementCallbackHidl::translateSingleGnssMeasurement<
        hardware::gnss::V2_1::IGnssMeasurementCallback::GnssMeasurement>(
        const hardware::gnss::V2_1::IGnssMeasurementCallback::GnssMeasurement& measurement_V2_1,
        JavaObject& object) {
    translateSingleGnssMeasurement(measurement_V2_1.v2_0, object);

    setMeasurementFields_V2_1<hardware::gnss::V2_1::IGnssMeasurementCallback::GnssMeasurement,
                              GnssMeasurementFlags>(measurement_V2_1, object);
}

template <>
void GnssMeasurementCallbackHidl::translateGnssClock(
        const hardware::gnss::V1_0::IGnssMeasurementCallback::GnssClock& clock,
        JavaObject& object) {
    setClockFields_V1_0<hardware::gnss::V1_0::IGnssMeasurementCallback::GnssClock,
                        GnssClockFlags>(clock, object);
}

template <>
void GnssMeasurementCallbackHidl::translateGnssClock(
        const hardware::gnss::V2_1::IGnssMeasurementCallback::GnssClock& clock,
        JavaObject& object) {
    setClockFields_V2_1<hardware::gnss::V2_1::IGnssMeasurementCallback::GnssClock,
                        GnssClockFlags>(clock, object);
    translateGnssClock(clock.v1_0, object);
}

template <>
void GnssMeasurementCallbackHidl::translateGnssClock(
        const hardware::gnss::V2_0::IGnssMeasurementCallback::GnssData& data, JavaObject& object) {
    setElapsedRealtimeFields<hardware::gnss::V2_0::ElapsedRealtime,
                             hardware::gnss::V2_0::ElapsedRealtimeFlags>(data.elapsedRealtime,
                                                                         object);
    translateGnssClock(data.clock, object);
}

template <>
void GnssMeasurementCallbackHidl::translateGnssClock(
        const hardware::gnss::V2_1::IGnssMeasurementCallback::GnssData& data, JavaObject& object) {
    auto elapsedRealtime = data.elapsedRealtime;
    uint16_t flags = static_cast<uint16_t>(elapsedRealtime.flags);
    if (flags & hardware::gnss::V2_0::ElapsedRealtimeFlags::HAS_TIMESTAMP_NS) {
        SET(ElapsedRealtimeNanos, static_cast<uint64_t>(elapsedRealtime.timestampNs));
    }
    if (flags & hardware::gnss::V2_0::ElapsedRealtimeFlags::HAS_TIME_UNCERTAINTY_NS) {
        SET(ElapsedRealtimeUncertaintyNanos,
            static_cast<double>(elapsedRealtime.timeUncertaintyNs));
    }
    translateGnssClock(data.clock, object);
}

} // namespace android::gnss
