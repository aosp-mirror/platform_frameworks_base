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

jclass class_gnssMeasurementsEvent;
jclass class_gnssMeasurement;
jclass class_gnssClock;

jmethodID method_gnssMeasurementsEventCtor;
jmethodID method_gnssClockCtor;
jmethodID method_gnssMeasurementCtor;
jmethodID method_reportMeasurementData;

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

    jclass gnssClockClass = env->FindClass("android/location/GnssClock");
    class_gnssClock = (jclass)env->NewGlobalRef(gnssClockClass);
    method_gnssClockCtor = env->GetMethodID(class_gnssClock, "<init>", "()V");
}

hardware::Return<void> GnssMeasurementCallback::gnssMeasurementCb_2_1(
        const hardware::gnss::V2_1::IGnssMeasurementCallback::GnssData& data) {
    translateAndSetGnssData(data);
    return hardware::Void();
}

hardware::Return<void> GnssMeasurementCallback::gnssMeasurementCb_2_0(
        const hardware::gnss::V2_0::IGnssMeasurementCallback::GnssData& data) {
    translateAndSetGnssData(data);
    return hardware::Void();
}

hardware::Return<void> GnssMeasurementCallback::gnssMeasurementCb(
        const hardware::gnss::V1_1::IGnssMeasurementCallback::GnssData& data) {
    translateAndSetGnssData(data);
    return hardware::Void();
}

hardware::Return<void> GnssMeasurementCallback::GnssMeasurementCb(
        const hardware::gnss::V1_0::IGnssMeasurementCallback::GnssData& data) {
    translateAndSetGnssData(data);
    return hardware::Void();
}

template <>
size_t GnssMeasurementCallback::getMeasurementCount<
        hardware::gnss::V1_0::IGnssMeasurementCallback::GnssData>(
        const hardware::gnss::V1_0::IGnssMeasurementCallback::GnssData& data) {
    return data.measurementCount;
}

// Preallocate object as: JavaObject object(env, "android/location/GnssMeasurement");
template <>
void GnssMeasurementCallback::translateSingleGnssMeasurement<
        hardware::gnss::V1_0::IGnssMeasurementCallback::GnssMeasurement>(
        const hardware::gnss::V1_0::IGnssMeasurementCallback::GnssMeasurement* measurement,
        JavaObject& object) {
    uint32_t flags = static_cast<uint32_t>(measurement->flags);

    SET(Svid, static_cast<int32_t>(measurement->svid));
    SET(ConstellationType, static_cast<int32_t>(measurement->constellation));
    SET(TimeOffsetNanos, measurement->timeOffsetNs);
    SET(State, static_cast<int32_t>(measurement->state));
    SET(ReceivedSvTimeNanos, measurement->receivedSvTimeInNs);
    SET(ReceivedSvTimeUncertaintyNanos, measurement->receivedSvTimeUncertaintyInNs);
    SET(Cn0DbHz, measurement->cN0DbHz);
    SET(PseudorangeRateMetersPerSecond, measurement->pseudorangeRateMps);
    SET(PseudorangeRateUncertaintyMetersPerSecond, measurement->pseudorangeRateUncertaintyMps);
    SET(AccumulatedDeltaRangeState,
        (static_cast<int32_t>(measurement->accumulatedDeltaRangeState) &
         ~ADR_STATE_HALF_CYCLE_REPORTED)); // Half Cycle state not reported from Hardware in V1_0
    SET(AccumulatedDeltaRangeMeters, measurement->accumulatedDeltaRangeM);
    SET(AccumulatedDeltaRangeUncertaintyMeters, measurement->accumulatedDeltaRangeUncertaintyM);

    if (flags & static_cast<uint32_t>(GnssMeasurementFlags::HAS_CARRIER_FREQUENCY)) {
        SET(CarrierFrequencyHz, measurement->carrierFrequencyHz);
    }

    // Intentionally not copying deprecated fields of carrierCycles,
    // carrierPhase, carrierPhaseUncertainty

    SET(MultipathIndicator, static_cast<int32_t>(measurement->multipathIndicator));

    if (flags & static_cast<uint32_t>(GnssMeasurementFlags::HAS_SNR)) {
        SET(SnrInDb, measurement->snrDb);
    }

    if (flags & static_cast<uint32_t>(GnssMeasurementFlags::HAS_AUTOMATIC_GAIN_CONTROL)) {
        SET(AutomaticGainControlLevelInDb, measurement->agcLevelDb);
    }
}

// Preallocate object as: JavaObject object(env, "android/location/GnssMeasurement");
template <>
void GnssMeasurementCallback::translateSingleGnssMeasurement<
        hardware::gnss::V1_1::IGnssMeasurementCallback::GnssMeasurement>(
        const hardware::gnss::V1_1::IGnssMeasurementCallback::GnssMeasurement* measurement_V1_1,
        JavaObject& object) {
    translateSingleGnssMeasurement(&(measurement_V1_1->v1_0), object);

    // Set the V1_1 flag, and mark that new field has valid information for Java Layer
    SET(AccumulatedDeltaRangeState,
        (static_cast<int32_t>(measurement_V1_1->accumulatedDeltaRangeState) |
         ADR_STATE_HALF_CYCLE_REPORTED));
}

// Preallocate object as: JavaObject object(env, "android/location/GnssMeasurement");
template <>
void GnssMeasurementCallback::translateSingleGnssMeasurement<
        hardware::gnss::V2_0::IGnssMeasurementCallback::GnssMeasurement>(
        const hardware::gnss::V2_0::IGnssMeasurementCallback::GnssMeasurement* measurement_V2_0,
        JavaObject& object) {
    JNIEnv* env = getJniEnv();
    translateSingleGnssMeasurement(&(measurement_V2_0->v1_1), object);

    jstring codeType = env->NewStringUTF(measurement_V2_0->codeType.c_str());
    SET(CodeType, codeType);

    // Overwrite with v2_0.state since v2_0->v1_1->v1_0.state is deprecated.
    SET(State, static_cast<int32_t>(measurement_V2_0->state));

    // Overwrite with v2_0.constellation since v2_0->v1_1->v1_0.constellation is deprecated.
    SET(ConstellationType, static_cast<int32_t>(measurement_V2_0->constellation));

    if (codeType) {
        env->DeleteLocalRef(codeType);
    }
}

// Preallocate object as: JavaObject object(env, "android/location/GnssMeasurement");
template <>
void GnssMeasurementCallback::translateSingleGnssMeasurement<
        hardware::gnss::V2_1::IGnssMeasurementCallback::GnssMeasurement>(
        const hardware::gnss::V2_1::IGnssMeasurementCallback::GnssMeasurement* measurement_V2_1,
        JavaObject& object) {
    translateSingleGnssMeasurement(&(measurement_V2_1->v2_0), object);

    SET(BasebandCn0DbHz, measurement_V2_1->basebandCN0DbHz);

    if (measurement_V2_1->flags & GnssMeasurementFlags::HAS_FULL_ISB) {
        SET(FullInterSignalBiasNanos, measurement_V2_1->fullInterSignalBiasNs);
    }

    if (measurement_V2_1->flags & GnssMeasurementFlags::HAS_FULL_ISB_UNCERTAINTY) {
        SET(FullInterSignalBiasUncertaintyNanos,
            measurement_V2_1->fullInterSignalBiasUncertaintyNs);
    }

    if (measurement_V2_1->flags & GnssMeasurementFlags::HAS_SATELLITE_ISB) {
        SET(SatelliteInterSignalBiasNanos, measurement_V2_1->satelliteInterSignalBiasNs);
    }

    if (measurement_V2_1->flags & GnssMeasurementFlags::HAS_SATELLITE_ISB_UNCERTAINTY) {
        SET(SatelliteInterSignalBiasUncertaintyNanos,
            measurement_V2_1->satelliteInterSignalBiasUncertaintyNs);
    }
}

template <>
void GnssMeasurementCallback::translateGnssClock(
        JavaObject& object,
        const hardware::gnss::V1_0::IGnssMeasurementCallback::GnssClock& clock) {
    uint32_t flags = static_cast<uint32_t>(clock.gnssClockFlags);
    if (flags & static_cast<uint32_t>(GnssClockFlags::HAS_LEAP_SECOND)) {
        SET(LeapSecond, static_cast<int32_t>(clock.leapSecond));
    }

    if (flags & static_cast<uint32_t>(GnssClockFlags::HAS_TIME_UNCERTAINTY)) {
        SET(TimeUncertaintyNanos, clock.timeUncertaintyNs);
    }

    if (flags & static_cast<uint32_t>(GnssClockFlags::HAS_FULL_BIAS)) {
        SET(FullBiasNanos, clock.fullBiasNs);
    }

    if (flags & static_cast<uint32_t>(GnssClockFlags::HAS_BIAS)) {
        SET(BiasNanos, clock.biasNs);
    }

    if (flags & static_cast<uint32_t>(GnssClockFlags::HAS_BIAS_UNCERTAINTY)) {
        SET(BiasUncertaintyNanos, clock.biasUncertaintyNs);
    }

    if (flags & static_cast<uint32_t>(GnssClockFlags::HAS_DRIFT)) {
        SET(DriftNanosPerSecond, clock.driftNsps);
    }

    if (flags & static_cast<uint32_t>(GnssClockFlags::HAS_DRIFT_UNCERTAINTY)) {
        SET(DriftUncertaintyNanosPerSecond, clock.driftUncertaintyNsps);
    }

    SET(TimeNanos, clock.timeNs);
    SET(HardwareClockDiscontinuityCount, clock.hwClockDiscontinuityCount);
}

template <>
void GnssMeasurementCallback::translateGnssClock(
        JavaObject& object,
        const hardware::gnss::V2_1::IGnssMeasurementCallback::GnssClock& clock) {
    JNIEnv* env = getJniEnv();
    SET(ReferenceConstellationTypeForIsb,
        static_cast<int32_t>(clock.referenceSignalTypeForIsb.constellation));
    SET(ReferenceCarrierFrequencyHzForIsb, clock.referenceSignalTypeForIsb.carrierFrequencyHz);

    jstring referenceCodeTypeForIsb =
            env->NewStringUTF(clock.referenceSignalTypeForIsb.codeType.c_str());
    SET(ReferenceCodeTypeForIsb, referenceCodeTypeForIsb);

    translateGnssClock(object, clock.v1_0);

    if (referenceCodeTypeForIsb) {
        env->DeleteLocalRef(referenceCodeTypeForIsb);
    }
}

template <>
void GnssMeasurementCallback::translateGnssClock(
        JavaObject& object, const hardware::gnss::V2_0::IGnssMeasurementCallback::GnssData& data) {
    auto elapsedRealtime = data.elapsedRealtime;
    uint16_t flags = static_cast<uint16_t>(elapsedRealtime.flags);
    if (flags & hardware::gnss::V2_0::ElapsedRealtimeFlags::HAS_TIMESTAMP_NS) {
        SET(ElapsedRealtimeNanos, static_cast<uint64_t>(elapsedRealtime.timestampNs));
    }
    if (flags & hardware::gnss::V2_0::ElapsedRealtimeFlags::HAS_TIME_UNCERTAINTY_NS) {
        SET(ElapsedRealtimeUncertaintyNanos,
            static_cast<double>(elapsedRealtime.timeUncertaintyNs));
    }
    translateGnssClock(object, data.clock);
}

template <>
void GnssMeasurementCallback::translateGnssClock(
        JavaObject& object, const hardware::gnss::V2_1::IGnssMeasurementCallback::GnssData& data) {
    auto elapsedRealtime = data.elapsedRealtime;
    uint16_t flags = static_cast<uint16_t>(elapsedRealtime.flags);
    if (flags & hardware::gnss::V2_0::ElapsedRealtimeFlags::HAS_TIMESTAMP_NS) {
        SET(ElapsedRealtimeNanos, static_cast<uint64_t>(elapsedRealtime.timestampNs));
    }
    if (flags & hardware::gnss::V2_0::ElapsedRealtimeFlags::HAS_TIME_UNCERTAINTY_NS) {
        SET(ElapsedRealtimeUncertaintyNanos,
            static_cast<double>(elapsedRealtime.timeUncertaintyNs));
    }
    translateGnssClock(object, data.clock);
}

void GnssMeasurementCallback::setMeasurementData(JNIEnv* env, jobject clock,
                                                 jobjectArray measurementArray) {
    jobject gnssMeasurementsEvent =
            env->NewObject(class_gnssMeasurementsEvent, method_gnssMeasurementsEventCtor, clock,
                           measurementArray);

    env->CallVoidMethod(mCallbacksObj, method_reportMeasurementData, gnssMeasurementsEvent);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    env->DeleteLocalRef(gnssMeasurementsEvent);
}

} // namespace android::gnss
