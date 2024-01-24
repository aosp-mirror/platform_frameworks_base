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
#define LOG_TAG "GnssJni"

#include "Gnss.h"

#include <binder/IServiceManager.h>

#include "Utils.h"

namespace android::gnss {

using hardware::Return;

using GnssLocationAidl = hardware::gnss::GnssLocation;
using GnssLocation_V1_0 = hardware::gnss::V1_0::GnssLocation;
using GnssLocation_V2_0 = hardware::gnss::V2_0::GnssLocation;
using IAGnssAidl = hardware::gnss::IAGnss;
using IAGnssRilAidl = hardware::gnss::IAGnssRil;
using IGnssAidl = hardware::gnss::IGnss;
using IGnss_V1_0 = hardware::gnss::V1_0::IGnss;
using IGnss_V1_1 = hardware::gnss::V1_1::IGnss;
using IGnss_V2_0 = hardware::gnss::V2_0::IGnss;
using IGnss_V2_1 = hardware::gnss::V2_1::IGnss;
using IGnssAntennaInfoAidl = hardware::gnss::IGnssAntennaInfo;
using IGnssCallbackAidl = hardware::gnss::IGnssCallback;
using IGnssCallback_V1_0 = hardware::gnss::V1_0::IGnssCallback;
using IGnssCallback_V2_0 = hardware::gnss::V2_0::IGnssCallback;
using IGnssCallback_V2_1 = hardware::gnss::V2_1::IGnssCallback;
using IGnssConfigurationAidl = android::hardware::gnss::IGnssConfiguration;
using IGnssDebugAidl = hardware::gnss::IGnssDebug;
using android::hardware::gnss::IGnssPsds;

namespace {

GnssLocationAidl createGnssLocation(jint gnssLocationFlags, jdouble latitudeDegrees,
                                    jdouble longitudeDegrees, jdouble altitudeMeters,
                                    jfloat speedMetersPerSec, jfloat bearingDegrees,
                                    jfloat horizontalAccuracyMeters, jfloat verticalAccuracyMeters,
                                    jfloat speedAccuracyMetersPerSecond,
                                    jfloat bearingAccuracyDegrees, jlong timestamp,
                                    jint elapsedRealtimeFlags, jlong elapsedRealtimeNanos,
                                    jdouble elapsedRealtimeUncertaintyNanos) {
    GnssLocationAidl location;
    location.gnssLocationFlags = static_cast<int>(gnssLocationFlags);
    location.latitudeDegrees = static_cast<double>(latitudeDegrees);
    location.longitudeDegrees = static_cast<double>(longitudeDegrees);
    location.altitudeMeters = static_cast<double>(altitudeMeters);
    location.speedMetersPerSec = static_cast<double>(speedMetersPerSec);
    location.bearingDegrees = static_cast<double>(bearingDegrees);
    location.horizontalAccuracyMeters = static_cast<double>(horizontalAccuracyMeters);
    location.verticalAccuracyMeters = static_cast<double>(verticalAccuracyMeters);
    location.speedAccuracyMetersPerSecond = static_cast<double>(speedAccuracyMetersPerSecond);
    location.bearingAccuracyDegrees = static_cast<double>(bearingAccuracyDegrees);
    location.timestampMillis = static_cast<uint64_t>(timestamp);

    location.elapsedRealtime.flags = static_cast<int>(elapsedRealtimeFlags);
    location.elapsedRealtime.timestampNs = static_cast<uint64_t>(elapsedRealtimeNanos);
    location.elapsedRealtime.timeUncertaintyNs =
            static_cast<double>(elapsedRealtimeUncertaintyNanos);

    return location;
}

GnssLocation_V1_0 createGnssLocation_V1_0(jint gnssLocationFlags, jdouble latitudeDegrees,
                                          jdouble longitudeDegrees, jdouble altitudeMeters,
                                          jfloat speedMetersPerSec, jfloat bearingDegrees,
                                          jfloat horizontalAccuracyMeters,
                                          jfloat verticalAccuracyMeters,
                                          jfloat speedAccuracyMetersPerSecond,
                                          jfloat bearingAccuracyDegrees, jlong timestamp) {
    GnssLocation_V1_0 location;
    location.gnssLocationFlags = static_cast<uint16_t>(gnssLocationFlags);
    location.latitudeDegrees = static_cast<double>(latitudeDegrees);
    location.longitudeDegrees = static_cast<double>(longitudeDegrees);
    location.altitudeMeters = static_cast<double>(altitudeMeters);
    location.speedMetersPerSec = static_cast<float>(speedMetersPerSec);
    location.bearingDegrees = static_cast<float>(bearingDegrees);
    location.horizontalAccuracyMeters = static_cast<float>(horizontalAccuracyMeters);
    location.verticalAccuracyMeters = static_cast<float>(verticalAccuracyMeters);
    location.speedAccuracyMetersPerSecond = static_cast<float>(speedAccuracyMetersPerSecond);
    location.bearingAccuracyDegrees = static_cast<float>(bearingAccuracyDegrees);
    location.timestamp = static_cast<uint64_t>(timestamp);

    return location;
}

GnssLocation_V2_0 createGnssLocation_V2_0(jint gnssLocationFlags, jdouble latitudeDegrees,
                                          jdouble longitudeDegrees, jdouble altitudeMeters,
                                          jfloat speedMetersPerSec, jfloat bearingDegrees,
                                          jfloat horizontalAccuracyMeters,
                                          jfloat verticalAccuracyMeters,
                                          jfloat speedAccuracyMetersPerSecond,
                                          jfloat bearingAccuracyDegrees, jlong timestamp,
                                          jint elapsedRealtimeFlags, jlong elapsedRealtimeNanos,
                                          jdouble elapsedRealtimeUncertaintyNanos) {
    GnssLocation_V2_0 location;
    location.v1_0 = createGnssLocation_V1_0(gnssLocationFlags, latitudeDegrees, longitudeDegrees,
                                            altitudeMeters, speedMetersPerSec, bearingDegrees,
                                            horizontalAccuracyMeters, verticalAccuracyMeters,
                                            speedAccuracyMetersPerSecond, bearingAccuracyDegrees,
                                            timestamp);

    location.elapsedRealtime.flags = static_cast<uint16_t>(elapsedRealtimeFlags);
    location.elapsedRealtime.timestampNs = static_cast<uint64_t>(elapsedRealtimeNanos);
    location.elapsedRealtime.timeUncertaintyNs =
            static_cast<uint64_t>(elapsedRealtimeUncertaintyNanos);

    return location;
}

} // anonymous namespace

// Implementation of GnssHal, which unifies all versions of GNSS HALs

GnssHal::GnssHal() {
    gnssHalAidl = waitForVintfService<IGnssAidl>();
    if (gnssHalAidl != nullptr) {
        ALOGD("Successfully got GNSS AIDL handle. Version=%d.", gnssHalAidl->getInterfaceVersion());
        if (gnssHalAidl->getInterfaceVersion() >= 2) {
            return;
        }
    }

    ALOGD("Trying IGnss_V2_1::getService()");
    gnssHal_V2_1 = IGnss_V2_1::getService();
    if (gnssHal_V2_1 != nullptr) {
        gnssHal_V2_0 = gnssHal_V2_1;
        gnssHal_V1_1 = gnssHal_V2_1;
        gnssHal = gnssHal_V2_1;
        return;
    }

    ALOGD("gnssHal 2.1 was null, trying 2.0");
    gnssHal_V2_0 = IGnss_V2_0::getService();
    if (gnssHal_V2_0 != nullptr) {
        gnssHal_V1_1 = gnssHal_V2_0;
        gnssHal = gnssHal_V2_0;
        return;
    }

    ALOGD("gnssHal 2.0 was null, trying 1.1");
    gnssHal_V1_1 = IGnss_V1_1::getService();
    if (gnssHal_V1_1 != nullptr) {
        gnssHal = gnssHal_V1_1;
        return;
    }

    ALOGD("gnssHal 1.1 was null, trying 1.0");
    gnssHal = IGnss_V1_0::getService();
}

jboolean GnssHal::isSupported() {
    return (gnssHalAidl != nullptr || gnssHal != nullptr) ? JNI_TRUE : JNI_FALSE;
}

void GnssHal::linkToDeath() {
    if (gnssHalAidl != nullptr) {
        gnssHalDeathRecipientAidl = new GnssDeathRecipientAidl();
        status_t linked = IInterface::asBinder(gnssHalAidl)->linkToDeath(gnssHalDeathRecipientAidl);
        if (linked != OK) {
            ALOGE("Unable to link to GNSS AIDL HAL death notification");
        } else {
            ALOGD("Successfully linked to GNSS AIDL HAl death notification");
        }
    }

    if (gnssHal != nullptr) {
        gnssHalDeathRecipient = new GnssDeathRecipient();
        hardware::Return<bool> linked = gnssHal->linkToDeath(gnssHalDeathRecipient, /*cookie*/ 0);
        if (!linked.isOk()) {
            ALOGE("Transaction error in linking to GnssHAL death: %s",
                  linked.description().c_str());
        } else if (!linked) {
            ALOGW("Unable to link to GnssHal death notifications");
        } else {
            ALOGD("Link to death notification successful");
        }
    }
}

jboolean GnssHal::setCallback() {
    if (gnssHalAidl != nullptr) {
        sp<IGnssCallbackAidl> gnssCbIfaceAidl =
                new GnssCallbackAidl(gnssHalAidl->getInterfaceVersion());
        auto status = gnssHalAidl->setCallback(gnssCbIfaceAidl);
        if (!checkAidlStatus(status, "IGnssAidl setCallback() failed.")) {
            return JNI_FALSE;
        }
    }
    if (gnssHal != nullptr) {
        Return<bool> result = false;
        sp<IGnssCallback_V2_1> gnssCbIface = new GnssCallbackHidl();
        if (gnssHal_V2_1 != nullptr) {
            result = gnssHal_V2_1->setCallback_2_1(gnssCbIface);
        } else if (gnssHal_V2_0 != nullptr) {
            result = gnssHal_V2_0->setCallback_2_0(gnssCbIface);
        } else if (gnssHal_V1_1 != nullptr) {
            result = gnssHal_V1_1->setCallback_1_1(gnssCbIface);
        } else {
            result = gnssHal->setCallback(gnssCbIface);
        }
        if (!checkHidlReturn(result, "IGnss setCallback() failed.")) {
            return JNI_FALSE;
        }
    }
    return JNI_TRUE;
}

void GnssHal::close() {
    if (gnssHalAidl != nullptr) {
        auto status = gnssHalAidl->close();
        checkAidlStatus(status, "IGnssAidl close() failed.");
    }

    if (gnssHal != nullptr) {
        auto result = gnssHal->cleanup();
        checkHidlReturn(result, "IGnss cleanup() failed.");
    }
}

jboolean GnssHal::start() {
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        auto status = gnssHalAidl->start();
        return checkAidlStatus(status, "IGnssAidl start() failed.");
    }

    if (gnssHal == nullptr) {
        return JNI_FALSE;
    }

    auto result = gnssHal->start();
    return checkHidlReturn(result, "IGnss start() failed.");
}

jboolean GnssHal::stop() {
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        auto status = gnssHalAidl->stop();
        return checkAidlStatus(status, "IGnssAidl stop() failed.");
    }

    if (gnssHal == nullptr) {
        return JNI_FALSE;
    }

    auto result = gnssHal->stop();
    return checkHidlReturn(result, "IGnss stop() failed.");
}

jboolean GnssHal::startSvStatus() {
    isSvStatusRegistered = true;
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        auto status = gnssHalAidl->startSvStatus();
        return checkAidlStatus(status, "IGnssAidl startSvStatus() failed.");
    }
    if (gnssHal == nullptr) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

jboolean GnssHal::stopSvStatus() {
    isSvStatusRegistered = false;
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        auto status = gnssHalAidl->stopSvStatus();
        return checkAidlStatus(status, "IGnssAidl stopSvStatus() failed.");
    }
    if (gnssHal == nullptr) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

jboolean GnssHal::startNmea() {
    isNmeaRegistered = true;
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        auto status = gnssHalAidl->startNmea();
        return checkAidlStatus(status, "IGnssAidl startNmea() failed.");
    }
    if (gnssHal == nullptr) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

jboolean GnssHal::stopNmea() {
    isNmeaRegistered = false;
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        auto status = gnssHalAidl->stopNmea();
        return checkAidlStatus(status, "IGnssAidl stopNmea() failed.");
    }
    if (gnssHal == nullptr) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

jint GnssHal::readNmea(jbyteArray& nmeaArray, jint& buffer_size) {
    // this should only be called from within a call to reportNmea
    JNIEnv* env = getJniEnv();
    jbyte* nmea = reinterpret_cast<jbyte*>(env->GetPrimitiveArrayCritical(nmeaArray, 0));
    int length = GnssCallbackHidl::sNmeaStringLength;
    if (length > buffer_size) {
        length = buffer_size;
    }
    memcpy(nmea, GnssCallbackHidl::sNmeaString, length);
    env->ReleasePrimitiveArrayCritical(nmeaArray, nmea, JNI_ABORT);
    return (jint)length;
}

jboolean GnssHal::setPositionMode(jint mode, jint recurrence, jint min_interval,
                                  jint preferred_accuracy, jint preferred_time,
                                  jboolean low_power_mode) {
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        IGnssAidl::PositionModeOptions options;
        options.mode = static_cast<IGnssAidl::GnssPositionMode>(mode);
        options.recurrence = static_cast<IGnssAidl::GnssPositionRecurrence>(recurrence);
        options.minIntervalMs = min_interval;
        options.preferredAccuracyMeters = preferred_accuracy;
        options.preferredTimeMs = preferred_time;
        options.lowPowerMode = low_power_mode;
        auto status = gnssHalAidl->setPositionMode(options);
        return checkAidlStatus(status, "IGnssAidl setPositionMode() failed.");
    }

    Return<bool> result = false;
    if (gnssHal_V1_1 != nullptr) {
        result = gnssHal_V1_1->setPositionMode_1_1(static_cast<IGnss_V1_0::GnssPositionMode>(mode),
                                                   static_cast<IGnss_V1_0::GnssPositionRecurrence>(
                                                           recurrence),
                                                   min_interval, preferred_accuracy, preferred_time,
                                                   low_power_mode);
    } else if (gnssHal != nullptr) {
        result = gnssHal->setPositionMode(static_cast<IGnss_V1_0::GnssPositionMode>(mode),
                                          static_cast<IGnss_V1_0::GnssPositionRecurrence>(
                                                  recurrence),
                                          min_interval, preferred_accuracy, preferred_time);
    }
    return checkHidlReturn(result, "IGnss setPositionMode() failed.");
}

void GnssHal::deleteAidingData(jint flags) {
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        auto status = gnssHalAidl->deleteAidingData(static_cast<IGnssAidl::GnssAidingData>(flags));
        checkAidlStatus(status, "IGnssAidl deleteAidingData() failed.");
        return;
    }

    if (gnssHal == nullptr) {
        return;
    }

    auto result = gnssHal->deleteAidingData(static_cast<IGnss_V1_0::GnssAidingData>(flags));
    checkHidlReturn(result, "IGnss deleteAidingData() failed.");
}

void GnssHal::injectTime(jlong time, jlong timeReference, jint uncertainty) {
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        auto status = gnssHalAidl->injectTime(time, timeReference, uncertainty);
        checkAidlStatus(status, "IGnssAidl injectTime() failed.");
        return;
    }

    if (gnssHal == nullptr) {
        return;
    }
    auto result = gnssHal->injectTime(time, timeReference, uncertainty);
    checkHidlReturn(result, "IGnss injectTime() failed.");
}

void GnssHal::injectLocation(jint gnssLocationFlags, jdouble latitudeDegrees,
                             jdouble longitudeDegrees, jdouble altitudeMeters,
                             jfloat speedMetersPerSec, jfloat bearingDegrees,
                             jfloat horizontalAccuracyMeters, jfloat verticalAccuracyMeters,
                             jfloat speedAccuracyMetersPerSecond, jfloat bearingAccuracyDegrees,
                             jlong timestamp, jint elapsedRealtimeFlags, jlong elapsedRealtimeNanos,
                             jdouble elapsedRealtimeUncertaintyNanos) {
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        GnssLocationAidl location =
                createGnssLocation(gnssLocationFlags, latitudeDegrees, longitudeDegrees,
                                   altitudeMeters, speedMetersPerSec, bearingDegrees,
                                   horizontalAccuracyMeters, verticalAccuracyMeters,
                                   speedAccuracyMetersPerSecond, bearingAccuracyDegrees, timestamp,
                                   elapsedRealtimeFlags, elapsedRealtimeNanos,
                                   elapsedRealtimeUncertaintyNanos);
        auto status = gnssHalAidl->injectLocation(location);
        checkAidlStatus(status, "IGnssAidl injectLocation() failed.");
        return;
    }

    if (gnssHal == nullptr) {
        return;
    }
    auto result =
            gnssHal->injectLocation(latitudeDegrees, longitudeDegrees, horizontalAccuracyMeters);
    checkHidlReturn(result, "IGnss injectLocation() failed.");
}

void GnssHal::injectBestLocation(jint gnssLocationFlags, jdouble latitudeDegrees,
                                 jdouble longitudeDegrees, jdouble altitudeMeters,
                                 jfloat speedMetersPerSec, jfloat bearingDegrees,
                                 jfloat horizontalAccuracyMeters, jfloat verticalAccuracyMeters,
                                 jfloat speedAccuracyMetersPerSecond, jfloat bearingAccuracyDegrees,
                                 jlong timestamp, jint elapsedRealtimeFlags,
                                 jlong elapsedRealtimeNanos,
                                 jdouble elapsedRealtimeUncertaintyNanos) {
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        GnssLocationAidl location =
                createGnssLocation(gnssLocationFlags, latitudeDegrees, longitudeDegrees,
                                   altitudeMeters, speedMetersPerSec, bearingDegrees,
                                   horizontalAccuracyMeters, verticalAccuracyMeters,
                                   speedAccuracyMetersPerSecond, bearingAccuracyDegrees, timestamp,
                                   elapsedRealtimeFlags, elapsedRealtimeNanos,
                                   elapsedRealtimeUncertaintyNanos);
        auto status = gnssHalAidl->injectBestLocation(location);
        checkAidlStatus(status, "IGnssAidl injectBestLocation() failed.");
        return;
    }

    if (gnssHal_V2_0 != nullptr) {
        GnssLocation_V2_0 location =
                createGnssLocation_V2_0(gnssLocationFlags, latitudeDegrees, longitudeDegrees,
                                        altitudeMeters, speedMetersPerSec, bearingDegrees,
                                        horizontalAccuracyMeters, verticalAccuracyMeters,
                                        speedAccuracyMetersPerSecond, bearingAccuracyDegrees,
                                        timestamp, elapsedRealtimeFlags, elapsedRealtimeNanos,
                                        elapsedRealtimeUncertaintyNanos);
        auto result = gnssHal_V2_0->injectBestLocation_2_0(location);
        checkHidlReturn(result, "IGnss injectBestLocation_2_0() failed.");
        return;
    }

    if (gnssHal_V1_1 != nullptr) {
        GnssLocation_V1_0 location =
                createGnssLocation_V1_0(gnssLocationFlags, latitudeDegrees, longitudeDegrees,
                                        altitudeMeters, speedMetersPerSec, bearingDegrees,
                                        horizontalAccuracyMeters, verticalAccuracyMeters,
                                        speedAccuracyMetersPerSecond, bearingAccuracyDegrees,
                                        timestamp);
        auto result = gnssHal_V1_1->injectBestLocation(location);
        checkHidlReturn(result, "IGnss injectBestLocation() failed.");
        return;
    }

    ALOGE("IGnss injectBestLocation() is called but gnssHal_V1_1 is not available.");
}

std::unique_ptr<AGnssInterface> GnssHal::getAGnssInterface() {
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        sp<IAGnssAidl> agnssAidl;
        auto status = gnssHalAidl->getExtensionAGnss(&agnssAidl);
        if (checkAidlStatus(status, "Unable to get a handle to AGnss interface.")) {
            return std::make_unique<gnss::AGnss>(agnssAidl);
        }
    } else if (gnssHal_V2_0 != nullptr) {
        auto agnss_V2_0 = gnssHal_V2_0->getExtensionAGnss_2_0();
        if (checkHidlReturn(agnss_V2_0, "Unable to get a handle to AGnss_V2_0")) {
            return std::make_unique<gnss::AGnss_V2_0>(agnss_V2_0);
        }
    } else if (gnssHal != nullptr) {
        auto agnss_V1_0 = gnssHal->getExtensionAGnss();
        if (checkHidlReturn(agnss_V1_0, "Unable to get a handle to AGnss_V1_0")) {
            return std::make_unique<gnss::AGnss_V1_0>(agnss_V1_0);
        }
    }
    return nullptr;
}

std::unique_ptr<AGnssRilInterface> GnssHal::getAGnssRilInterface() {
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        sp<IAGnssRilAidl> agnssRilAidl;
        auto status = gnssHalAidl->getExtensionAGnssRil(&agnssRilAidl);
        if (checkAidlStatus(status, "Unable to get a handle to AGnssRil interface.")) {
            return std::make_unique<gnss::AGnssRil>(agnssRilAidl);
        }
    } else if (gnssHal_V2_0 != nullptr) {
        auto agnssRil_V2_0 = gnssHal_V2_0->getExtensionAGnssRil_2_0();
        if (checkHidlReturn(agnssRil_V2_0, "Unable to get a handle to AGnssRil_V2_0")) {
            return std::make_unique<gnss::AGnssRil_V2_0>(agnssRil_V2_0);
        }
    } else if (gnssHal != nullptr) {
        auto agnssRil_V1_0 = gnssHal->getExtensionAGnssRil();
        if (checkHidlReturn(agnssRil_V1_0, "Unable to get a handle to AGnssRil_V1_0")) {
            return std::make_unique<gnss::AGnssRil_V1_0>(agnssRil_V1_0);
        }
    }
    return nullptr;
}

std::unique_ptr<GnssNavigationMessageInterface> GnssHal::getGnssNavigationMessageInterface() {
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        sp<hardware::gnss::IGnssNavigationMessageInterface> gnssNavigationMessage;
        auto status = gnssHalAidl->getExtensionGnssNavigationMessage(&gnssNavigationMessage);
        if (checkAidlStatus(status,
                            "Unable to get a handle to GnssNavigationMessage AIDL interface.")) {
            return std::make_unique<gnss::GnssNavigationMessageAidl>(gnssNavigationMessage);
        }
    } else if (gnssHal != nullptr) {
        auto gnssNavigationMessage = gnssHal->getExtensionGnssNavigationMessage();
        if (checkHidlReturn(gnssNavigationMessage,
                            "Unable to get a handle to GnssNavigationMessage interface.")) {
            return std::make_unique<gnss::GnssNavigationMessageHidl>(gnssNavigationMessage);
        }
    }
    return nullptr;
}

std::unique_ptr<GnssMeasurementInterface> GnssHal::getGnssMeasurementInterface() {
    // Allow all causal combinations between IGnss.hal and IGnssMeasurement.hal. That means,
    // 2.1@IGnss can be paired with {1.0, 1,1, 2.0, 2.1}@IGnssMeasurement
    // 2.0@IGnss can be paired with {1.0, 1,1, 2.0}@IGnssMeasurement
    // 1.1@IGnss can be paired {1.0, 1.1}@IGnssMeasurement
    // 1.0@IGnss is paired with 1.0@IGnssMeasurement
    if (gnssHalAidl != nullptr) {
        sp<hardware::gnss::IGnssMeasurementInterface> gnssMeasurement;
        auto status = gnssHalAidl->getExtensionGnssMeasurement(&gnssMeasurement);
        if (checkAidlStatus(status, "Unable to get a handle to GnssMeasurement AIDL interface.")) {
            return std::make_unique<android::gnss::GnssMeasurement>(gnssMeasurement);
        }
    }
    if (gnssHal_V2_1 != nullptr) {
        auto gnssMeasurement = gnssHal_V2_1->getExtensionGnssMeasurement_2_1();
        if (checkHidlReturn(gnssMeasurement, "Unable to get a handle to GnssMeasurement_V2_1")) {
            return std::make_unique<android::gnss::GnssMeasurement_V2_1>(gnssMeasurement);
        }
    }
    if (gnssHal_V2_0 != nullptr) {
        auto gnssMeasurement = gnssHal_V2_0->getExtensionGnssMeasurement_2_0();
        if (checkHidlReturn(gnssMeasurement, "Unable to get a handle to GnssMeasurement_V2_0")) {
            return std::make_unique<android::gnss::GnssMeasurement_V2_0>(gnssMeasurement);
        }
    }
    if (gnssHal_V1_1 != nullptr) {
        auto gnssMeasurement = gnssHal_V1_1->getExtensionGnssMeasurement_1_1();
        if (checkHidlReturn(gnssMeasurement, "Unable to get a handle to GnssMeasurement_V1_1")) {
            return std::make_unique<android::gnss::GnssMeasurement_V1_1>(gnssMeasurement);
        }
    }
    if (gnssHal != nullptr) {
        auto gnssMeasurement = gnssHal->getExtensionGnssMeasurement();
        if (checkHidlReturn(gnssMeasurement, "Unable to get a handle to GnssMeasurement_V1_0")) {
            return std::make_unique<android::gnss::GnssMeasurement_V1_0>(gnssMeasurement);
        }
    }
    return nullptr;
}

std::unique_ptr<GnssDebugInterface> GnssHal::getGnssDebugInterface() {
    // Allow all causal combinations between IGnss.hal and IGnssDebug.hal. That means,
    // 2.0@IGnss can be paired with {1.0, 2.0}@IGnssDebug
    // 1.0@IGnss is paired with 1.0@IGnssDebug
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        sp<IGnssDebugAidl> gnssDebugAidl;
        auto status = gnssHalAidl->getExtensionGnssDebug(&gnssDebugAidl);
        if (checkAidlStatus(status, "Unable to get a handle to GnssDebug interface.")) {
            return std::make_unique<gnss::GnssDebug>(gnssDebugAidl);
        }
    }
    if (gnssHal_V2_0 != nullptr) {
        auto gnssDebug_V2_0 = gnssHal_V2_0->getExtensionGnssDebug_2_0();
        if (checkHidlReturn(gnssDebug_V2_0, "Unable to get a handle to GnssDebug_V2_0.")) {
            return std::make_unique<gnss::GnssDebug_V2_0>(gnssDebug_V2_0);
        }
    }
    if (gnssHal != nullptr) {
        auto gnssDebug_V1_0 = gnssHal->getExtensionGnssDebug();
        if (checkHidlReturn(gnssDebug_V1_0, "Unable to get a handle to GnssDebug_V1_0.")) {
            return std::make_unique<gnss::GnssDebug_V1_0>(gnssDebug_V1_0);
        }
    }
    return nullptr;
}

std::unique_ptr<GnssConfigurationInterface> GnssHal::getGnssConfigurationInterface() {
    if (gnssHalAidl != nullptr) {
        sp<IGnssConfigurationAidl> gnssConfigurationAidl;
        auto status = gnssHalAidl->getExtensionGnssConfiguration(&gnssConfigurationAidl);
        if (checkAidlStatus(status,
                            "Unable to get a handle to GnssConfiguration AIDL interface.")) {
            return std::make_unique<android::gnss::GnssConfiguration>(gnssConfigurationAidl);
        }
    } else if (gnssHal_V2_1 != nullptr) {
        auto gnssConfiguration = gnssHal_V2_1->getExtensionGnssConfiguration_2_1();
        if (checkHidlReturn(gnssConfiguration,
                            "Unable to get a handle to GnssConfiguration_V2_1")) {
            return std::make_unique<android::gnss::GnssConfiguration_V2_1>(gnssConfiguration);
        }
    } else if (gnssHal_V2_0 != nullptr) {
        auto gnssConfiguration = gnssHal_V2_0->getExtensionGnssConfiguration_2_0();
        if (checkHidlReturn(gnssConfiguration,
                            "Unable to get a handle to GnssConfiguration_V2_0")) {
            return std::make_unique<android::gnss::GnssConfiguration_V2_0>(gnssConfiguration);
        }
    } else if (gnssHal_V1_1 != nullptr) {
        auto gnssConfiguration = gnssHal_V1_1->getExtensionGnssConfiguration_1_1();
        if (checkHidlReturn(gnssConfiguration,
                            "Unable to get a handle to GnssConfiguration_V1_1")) {
            return std::make_unique<gnss::GnssConfiguration_V1_1>(gnssConfiguration);
        }
    } else if (gnssHal != nullptr) {
        auto gnssConfiguration = gnssHal->getExtensionGnssConfiguration();
        if (checkHidlReturn(gnssConfiguration,
                            "Unable to get a handle to GnssConfiguration_V1_0")) {
            return std::make_unique<gnss::GnssConfiguration_V1_0>(gnssConfiguration);
        }
    }
    return nullptr;
}

std::unique_ptr<GnssGeofenceInterface> GnssHal::getGnssGeofenceInterface() {
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        sp<hardware::gnss::IGnssGeofence> gnssGeofence;
        auto status = gnssHalAidl->getExtensionGnssGeofence(&gnssGeofence);
        if (checkAidlStatus(status, "Unable to get a handle to GnssGeofence AIDL interface.")) {
            return std::make_unique<gnss::GnssGeofenceAidl>(gnssGeofence);
        }
    } else if (gnssHal != nullptr) {
        auto gnssGeofencing = gnssHal->getExtensionGnssGeofencing();
        if (checkHidlReturn(gnssGeofencing, "Unable to get a handle to GnssGeofencing")) {
            return std::make_unique<gnss::GnssGeofenceHidl>(gnssGeofencing);
        }
    }
    return nullptr;
}

std::unique_ptr<GnssBatchingInterface> GnssHal::getGnssBatchingInterface() {
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        sp<android::hardware::gnss::IGnssBatching> gnssBatchingAidl;
        auto status = gnssHalAidl->getExtensionGnssBatching(&gnssBatchingAidl);
        if (checkAidlStatus(status, "Unable to get a handle to GnssBatching interface.")) {
            return std::make_unique<gnss::GnssBatching>(gnssBatchingAidl);
        }
    }
    if (gnssHal_V2_0 != nullptr) {
        auto gnssBatching_V2_0 = gnssHal_V2_0->getExtensionGnssBatching_2_0();
        if (checkHidlReturn(gnssBatching_V2_0, "Unable to get a handle to GnssBatching_V2_0")) {
            return std::make_unique<gnss::GnssBatching_V2_0>(gnssBatching_V2_0);
        }
    }
    if (gnssHal != nullptr) {
        auto gnssBatching_V1_0 = gnssHal->getExtensionGnssBatching();
        if (checkHidlReturn(gnssBatching_V1_0, "Unable to get a handle to GnssBatching")) {
            return std::make_unique<gnss::GnssBatching_V1_0>(gnssBatching_V1_0);
        }
    }
    return nullptr;
}

std::unique_ptr<MeasurementCorrectionsInterface> GnssHal::getMeasurementCorrectionsInterface() {
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        sp<android::hardware::gnss::measurement_corrections::IMeasurementCorrectionsInterface>
                gnssMeasurementCorrectionsAidl;
        auto status =
                gnssHalAidl->getExtensionMeasurementCorrections(&gnssMeasurementCorrectionsAidl);
        if (checkAidlStatus(status,
                            "Unable to get a handle to GnssVisibilityControl AIDL interface.")) {
            return std::make_unique<gnss::MeasurementCorrectionsIface_Aidl>(
                    gnssMeasurementCorrectionsAidl);
        }
    }
    if (gnssHal_V2_1 != nullptr) {
        auto gnssCorrections = gnssHal_V2_1->getExtensionMeasurementCorrections_1_1();
        if (checkHidlReturn(gnssCorrections,
                            "Unable to get a handle to GnssMeasurementCorrections HIDL "
                            "interface")) {
            return std::make_unique<gnss::MeasurementCorrectionsIface_V1_1>(gnssCorrections);
        }
    }
    if (gnssHal_V2_0 != nullptr) {
        auto gnssCorrections = gnssHal_V2_0->getExtensionMeasurementCorrections();
        if (checkHidlReturn(gnssCorrections,
                            "Unable to get a handle to GnssMeasurementCorrections HIDL "
                            "interface")) {
            return std::make_unique<gnss::MeasurementCorrectionsIface_V1_0>(gnssCorrections);
        }
    }
    return nullptr;
}

std::unique_ptr<GnssVisibilityControlInterface> GnssHal::getGnssVisibilityControlInterface() {
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        sp<android::hardware::gnss::visibility_control::IGnssVisibilityControl>
                gnssVisibilityControlAidl;
        auto status = gnssHalAidl->getExtensionGnssVisibilityControl(&gnssVisibilityControlAidl);
        if (checkAidlStatus(status,
                            "Unable to get a handle to GnssVisibilityControl AIDL interface.")) {
            return std::make_unique<gnss::GnssVisibilityControlAidl>(gnssVisibilityControlAidl);
        }
    } else if (gnssHal_V2_0 != nullptr) {
        auto gnssVisibilityControlHidl = gnssHal_V2_0->getExtensionVisibilityControl();
        if (checkHidlReturn(gnssVisibilityControlHidl,
                            "Unable to get a handle to GnssVisibilityControl HIDL interface")) {
            return std::make_unique<gnss::GnssVisibilityControlHidl>(gnssVisibilityControlHidl);
        }
    }
    return nullptr;
}

std::unique_ptr<GnssAntennaInfoInterface> GnssHal::getGnssAntennaInfoInterface() {
    if (gnssHalAidl != nullptr && gnssHalAidl->getInterfaceVersion() >= 2) {
        sp<IGnssAntennaInfoAidl> gnssAntennaInfoAidl;
        auto status = gnssHalAidl->getExtensionGnssAntennaInfo(&gnssAntennaInfoAidl);
        if (checkAidlStatus(status, "Unable to get a handle to GnssAntennaInfo interface.")) {
            return std::make_unique<gnss::GnssAntennaInfoAidl>(gnssAntennaInfoAidl);
        }
    } else if (gnssHal_V2_1 != nullptr) {
        auto gnssAntennaInfo_V2_1 = gnssHal_V2_1->getExtensionGnssAntennaInfo();
        if (checkHidlReturn(gnssAntennaInfo_V2_1,
                            "Unable to get a handle to GnssAntennaInfo_V2_1")) {
            return std::make_unique<gnss::GnssAntennaInfo_V2_1>(gnssAntennaInfo_V2_1);
        }
    }
    return nullptr;
}

std::unique_ptr<GnssPsdsInterface> GnssHal::getGnssPsdsInterface() {
    if (gnssHalAidl != nullptr) {
        sp<IGnssPsds> gnssPsdsAidl;
        auto status = gnssHalAidl->getExtensionPsds(&gnssPsdsAidl);
        if (checkAidlStatus(status, "Unable to get a handle to PSDS interface.")) {
            return std::make_unique<gnss::GnssPsdsAidl>(gnssPsdsAidl);
        }
    } else if (gnssHal != nullptr) {
        auto gnssXtra = gnssHal->getExtensionXtra();
        if (checkHidlReturn(gnssXtra, "Unable to get a handle to XTRA interface.")) {
            return std::make_unique<gnss::GnssPsdsHidl>(gnssXtra);
        }
    }
    return nullptr;
}

sp<hardware::gnss::IGnssPowerIndication> GnssHal::getGnssPowerIndicationInterface() {
    if (gnssHalAidl != nullptr) {
        sp<hardware::gnss::IGnssPowerIndication> gnssPowerIndication;
        auto status = gnssHalAidl->getExtensionGnssPowerIndication(&gnssPowerIndication);
        if (checkAidlStatus(status, "Unable to get a handle to GnssPowerIndication")) {
            return gnssPowerIndication;
        }
    }
    return nullptr;
}

sp<hardware::gnss::V1_0::IGnssNi> GnssHal::getGnssNiInterface() {
    if (gnssHal != nullptr) {
        auto gnssNi = gnssHal->getExtensionGnssNi();
        if (checkHidlReturn(gnssNi, "Unable to get a handle to GnssNi")) {
            return gnssNi;
        }
    }
    return nullptr;
}

} // namespace android::gnss
