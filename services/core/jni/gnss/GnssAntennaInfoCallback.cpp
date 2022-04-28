/*
 * Copyright (C) 2021 The Android Open Source Project
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
#define LOG_TAG "GnssAntInfoCbJni"

#include "GnssAntennaInfoCallback.h"
#include "Utils.h"

namespace android::gnss {

using android::hardware::hidl_vec;
using android::hardware::Return;
using android::hardware::Void;
using binder::Status;

using IGnssAntennaInfoCallbackAidl = android::hardware::gnss::IGnssAntennaInfoCallback;
using IGnssAntennaInfoCallback_V2_1 = android::hardware::gnss::V2_1::IGnssAntennaInfoCallback;

namespace {
jclass class_gnssAntennaInfoBuilder;
jclass class_phaseCenterOffset;
jclass class_sphericalCorrections;
jclass class_arrayList;
jclass class_doubleArray;

jmethodID method_reportAntennaInfo;
jmethodID method_gnssAntennaInfoBuilderCtor;
jmethodID method_phaseCenterOffsetCtor;
jmethodID method_sphericalCorrectionsCtor;
jmethodID method_arrayListCtor;
jmethodID method_arrayListAdd;
jmethodID method_gnssAntennaInfoBuilderSetCarrierFrequencyMHz;
jmethodID method_gnssAntennaInfoBuilderSetPhaseCenterOffset;
jmethodID method_gnssAntennaInfoBuilderSetPhaseCenterVariationCorrections;
jmethodID method_gnssAntennaInfoBuilderSetSignalGainCorrections;
jmethodID method_gnssAntennaInfoBuilderBuild;
} // anonymous namespace

void GnssAntennaInfo_class_init_once(JNIEnv* env, jclass& clazz) {
    method_reportAntennaInfo = env->GetMethodID(clazz, "reportAntennaInfo", "(Ljava/util/List;)V");
    jclass gnssAntennaInfoBuilder = env->FindClass("android/location/GnssAntennaInfo$Builder");
    class_gnssAntennaInfoBuilder = (jclass)env->NewGlobalRef(gnssAntennaInfoBuilder);
    method_gnssAntennaInfoBuilderCtor =
            env->GetMethodID(class_gnssAntennaInfoBuilder, "<init>", "()V");
    method_gnssAntennaInfoBuilderSetCarrierFrequencyMHz =
            env->GetMethodID(class_gnssAntennaInfoBuilder, "setCarrierFrequencyMHz",
                             "(D)Landroid/location/GnssAntennaInfo$Builder;");
    method_gnssAntennaInfoBuilderSetPhaseCenterOffset =
            env->GetMethodID(class_gnssAntennaInfoBuilder, "setPhaseCenterOffset",
                             "(Landroid/location/GnssAntennaInfo$PhaseCenterOffset;)"
                             "Landroid/location/GnssAntennaInfo$Builder;");
    method_gnssAntennaInfoBuilderSetPhaseCenterVariationCorrections =
            env->GetMethodID(class_gnssAntennaInfoBuilder, "setPhaseCenterVariationCorrections",
                             "(Landroid/location/GnssAntennaInfo$SphericalCorrections;)"
                             "Landroid/location/GnssAntennaInfo$Builder;");
    method_gnssAntennaInfoBuilderSetSignalGainCorrections =
            env->GetMethodID(class_gnssAntennaInfoBuilder, "setSignalGainCorrections",
                             "(Landroid/location/GnssAntennaInfo$SphericalCorrections;)"
                             "Landroid/location/GnssAntennaInfo$Builder;");
    method_gnssAntennaInfoBuilderBuild = env->GetMethodID(class_gnssAntennaInfoBuilder, "build",
                                                          "()Landroid/location/GnssAntennaInfo;");

    jclass phaseCenterOffsetClass =
            env->FindClass("android/location/GnssAntennaInfo$PhaseCenterOffset");
    class_phaseCenterOffset = (jclass)env->NewGlobalRef(phaseCenterOffsetClass);
    method_phaseCenterOffsetCtor = env->GetMethodID(class_phaseCenterOffset, "<init>", "(DDDDDD)V");

    jclass sphericalCorrectionsClass =
            env->FindClass("android/location/GnssAntennaInfo$SphericalCorrections");
    class_sphericalCorrections = (jclass)env->NewGlobalRef(sphericalCorrectionsClass);
    method_sphericalCorrectionsCtor =
            env->GetMethodID(class_sphericalCorrections, "<init>", "([[D[[D)V");

    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    class_arrayList = (jclass)env->NewGlobalRef(arrayListClass);
    method_arrayListCtor = env->GetMethodID(class_arrayList, "<init>", "()V");
    method_arrayListAdd = env->GetMethodID(class_arrayList, "add", "(Ljava/lang/Object;)Z");

    jclass doubleArrayClass = env->FindClass("[D");
    class_doubleArray = (jclass)env->NewGlobalRef(doubleArrayClass);
}

binder::Status GnssAntennaInfoCallbackAidl::gnssAntennaInfoCb(
        const std::vector<IGnssAntennaInfoCallbackAidl::GnssAntennaInfo>& gnssAntennaInfos) {
    GnssAntennaInfoCallbackUtil::translateAndReportGnssAntennaInfo(gnssAntennaInfos);
    return Status::ok();
}

Return<void> GnssAntennaInfoCallback_V2_1::gnssAntennaInfoCb(
        const hidl_vec<IGnssAntennaInfoCallback_V2_1::GnssAntennaInfo>& gnssAntennaInfos) {
    GnssAntennaInfoCallbackUtil::translateAndReportGnssAntennaInfo(gnssAntennaInfos);
    return Void();
}

template <template <class...> class T_vector, class T_info>
jobjectArray GnssAntennaInfoCallbackUtil::translate2dDoubleArray(JNIEnv* env,
                                                                 const T_vector<T_info>& array) {
    jsize numRows = array.size();
    if (numRows == 0) {
        // Empty array
        return NULL;
    }
    jsize numCols = array[0].row.size();
    if (numCols <= 1) {
        // phi angle separation is computed as 180.0 / (numColumns - 1), so can't be < 2.
        return NULL;
    }

    // Allocate array of double arrays
    jobjectArray returnArray = env->NewObjectArray(numRows, class_doubleArray, NULL);

    // Create each double array
    for (uint8_t i = 0; i < numRows; i++) {
        jdoubleArray doubleArray = env->NewDoubleArray(numCols);
        env->SetDoubleArrayRegion(doubleArray, (jsize)0, numCols, array[i].row.data());
        env->SetObjectArrayElement(returnArray, (jsize)i, doubleArray);
        env->DeleteLocalRef(doubleArray);
    }
    return returnArray;
}

template <template <class...> class T_vector, class T_info>
jobject GnssAntennaInfoCallbackUtil::translateAllGnssAntennaInfos(
        JNIEnv* env, const T_vector<T_info>& gnssAntennaInfos) {
    jobject arrayList = env->NewObject(class_arrayList,
                                       method_arrayListCtor); // Create new ArrayList instance

    for (auto gnssAntennaInfo : gnssAntennaInfos) {
        jobject gnssAntennaInfoObject = translateSingleGnssAntennaInfo(env, gnssAntennaInfo);

        env->CallBooleanMethod(arrayList, method_arrayListAdd,
                               gnssAntennaInfoObject); // Add the antennaInfo to the ArrayList

        // Delete Local Refs
        env->DeleteLocalRef(gnssAntennaInfoObject);
    }
    return arrayList;
}

template <class T>
jobject GnssAntennaInfoCallbackUtil::translatePhaseCenterOffset(JNIEnv* env,
                                                                const T& gnssAntennaInfo) {
    jobject phaseCenterOffset =
            env->NewObject(class_phaseCenterOffset, method_phaseCenterOffsetCtor,
                           gnssAntennaInfo.phaseCenterOffsetCoordinateMillimeters.x,
                           gnssAntennaInfo.phaseCenterOffsetCoordinateMillimeters.xUncertainty,
                           gnssAntennaInfo.phaseCenterOffsetCoordinateMillimeters.y,
                           gnssAntennaInfo.phaseCenterOffsetCoordinateMillimeters.yUncertainty,
                           gnssAntennaInfo.phaseCenterOffsetCoordinateMillimeters.z,
                           gnssAntennaInfo.phaseCenterOffsetCoordinateMillimeters.zUncertainty);

    return phaseCenterOffset;
}

template <>
jobject GnssAntennaInfoCallbackUtil::translatePhaseCenterVariationCorrections(
        JNIEnv* env, const IGnssAntennaInfoCallbackAidl::GnssAntennaInfo& gnssAntennaInfo) {
    if (gnssAntennaInfo.phaseCenterVariationCorrectionMillimeters.empty() ||
        gnssAntennaInfo.phaseCenterVariationCorrectionUncertaintyMillimeters.empty()) {
        return NULL;
    }

    jobjectArray phaseCenterVariationCorrectionsArray =
            translate2dDoubleArray(env, gnssAntennaInfo.phaseCenterVariationCorrectionMillimeters);
    jobjectArray phaseCenterVariationCorrectionsUncertaintiesArray =
            translate2dDoubleArray(env,
                                   gnssAntennaInfo
                                           .phaseCenterVariationCorrectionUncertaintyMillimeters);

    if (phaseCenterVariationCorrectionsArray == NULL ||
        phaseCenterVariationCorrectionsUncertaintiesArray == NULL) {
        env->DeleteLocalRef(phaseCenterVariationCorrectionsArray);
        env->DeleteLocalRef(phaseCenterVariationCorrectionsUncertaintiesArray);
        return NULL;
    }

    jobject phaseCenterVariationCorrections =
            env->NewObject(class_sphericalCorrections, method_sphericalCorrectionsCtor,
                           phaseCenterVariationCorrectionsArray,
                           phaseCenterVariationCorrectionsUncertaintiesArray);

    env->DeleteLocalRef(phaseCenterVariationCorrectionsArray);
    env->DeleteLocalRef(phaseCenterVariationCorrectionsUncertaintiesArray);

    return phaseCenterVariationCorrections;
}

template <>
jobject GnssAntennaInfoCallbackUtil::translatePhaseCenterVariationCorrections(
        JNIEnv* env, const IGnssAntennaInfoCallback_V2_1::GnssAntennaInfo& gnssAntennaInfo) {
    if (gnssAntennaInfo.phaseCenterVariationCorrectionMillimeters == NULL ||
        gnssAntennaInfo.phaseCenterVariationCorrectionUncertaintyMillimeters == NULL) {
        return NULL;
    }

    jobjectArray phaseCenterVariationCorrectionsArray =
            translate2dDoubleArray(env, gnssAntennaInfo.phaseCenterVariationCorrectionMillimeters);
    jobjectArray phaseCenterVariationCorrectionsUncertaintiesArray =
            translate2dDoubleArray(env,
                                   gnssAntennaInfo
                                           .phaseCenterVariationCorrectionUncertaintyMillimeters);

    if (phaseCenterVariationCorrectionsArray == NULL ||
        phaseCenterVariationCorrectionsUncertaintiesArray == NULL) {
        env->DeleteLocalRef(phaseCenterVariationCorrectionsArray);
        env->DeleteLocalRef(phaseCenterVariationCorrectionsUncertaintiesArray);
        return NULL;
    }

    jobject phaseCenterVariationCorrections =
            env->NewObject(class_sphericalCorrections, method_sphericalCorrectionsCtor,
                           phaseCenterVariationCorrectionsArray,
                           phaseCenterVariationCorrectionsUncertaintiesArray);

    env->DeleteLocalRef(phaseCenterVariationCorrectionsArray);
    env->DeleteLocalRef(phaseCenterVariationCorrectionsUncertaintiesArray);

    return phaseCenterVariationCorrections;
}

template <>
jobject GnssAntennaInfoCallbackUtil::translateSignalGainCorrections(
        JNIEnv* env, const IGnssAntennaInfoCallbackAidl::GnssAntennaInfo& gnssAntennaInfo) {
    if (gnssAntennaInfo.signalGainCorrectionDbi.empty() ||
        gnssAntennaInfo.signalGainCorrectionUncertaintyDbi.empty()) {
        return NULL;
    }
    jobjectArray signalGainCorrectionsArray =
            translate2dDoubleArray(env, gnssAntennaInfo.signalGainCorrectionDbi);
    jobjectArray signalGainCorrectionsUncertaintiesArray =
            translate2dDoubleArray(env, gnssAntennaInfo.signalGainCorrectionUncertaintyDbi);

    if (signalGainCorrectionsArray == NULL || signalGainCorrectionsUncertaintiesArray == NULL) {
        env->DeleteLocalRef(signalGainCorrectionsArray);
        env->DeleteLocalRef(signalGainCorrectionsUncertaintiesArray);
        return NULL;
    }

    jobject signalGainCorrections =
            env->NewObject(class_sphericalCorrections, method_sphericalCorrectionsCtor,
                           signalGainCorrectionsArray, signalGainCorrectionsUncertaintiesArray);

    env->DeleteLocalRef(signalGainCorrectionsArray);
    env->DeleteLocalRef(signalGainCorrectionsUncertaintiesArray);

    return signalGainCorrections;
}

template <>
jobject GnssAntennaInfoCallbackUtil::translateSignalGainCorrections(
        JNIEnv* env, const IGnssAntennaInfoCallback_V2_1::GnssAntennaInfo& gnssAntennaInfo) {
    if (gnssAntennaInfo.signalGainCorrectionDbi == NULL ||
        gnssAntennaInfo.signalGainCorrectionUncertaintyDbi == NULL) {
        return NULL;
    }
    jobjectArray signalGainCorrectionsArray =
            translate2dDoubleArray(env, gnssAntennaInfo.signalGainCorrectionDbi);
    jobjectArray signalGainCorrectionsUncertaintiesArray =
            translate2dDoubleArray(env, gnssAntennaInfo.signalGainCorrectionUncertaintyDbi);

    if (signalGainCorrectionsArray == NULL || signalGainCorrectionsUncertaintiesArray == NULL) {
        env->DeleteLocalRef(signalGainCorrectionsArray);
        env->DeleteLocalRef(signalGainCorrectionsUncertaintiesArray);
        return NULL;
    }

    jobject signalGainCorrections =
            env->NewObject(class_sphericalCorrections, method_sphericalCorrectionsCtor,
                           signalGainCorrectionsArray, signalGainCorrectionsUncertaintiesArray);

    env->DeleteLocalRef(signalGainCorrectionsArray);
    env->DeleteLocalRef(signalGainCorrectionsUncertaintiesArray);

    return signalGainCorrections;
}

template <class T>
jobject GnssAntennaInfoCallbackUtil::translateSingleGnssAntennaInfo(JNIEnv* env,
                                                                    const T& gnssAntennaInfo) {
    jobject phaseCenterOffset = translatePhaseCenterOffset(env, gnssAntennaInfo);

    // Nullable
    jobject phaseCenterVariationCorrections =
            translatePhaseCenterVariationCorrections(env, gnssAntennaInfo);

    // Nullable
    jobject signalGainCorrections = translateSignalGainCorrections(env, gnssAntennaInfo);

    // Get builder
    jobject gnssAntennaInfoBuilderObject =
            env->NewObject(class_gnssAntennaInfoBuilder, method_gnssAntennaInfoBuilderCtor);

    // Set fields
    callObjectMethodIgnoringResult(env, gnssAntennaInfoBuilderObject,
                                   method_gnssAntennaInfoBuilderSetCarrierFrequencyMHz,
                                   getCarrierFrequencyMHz(gnssAntennaInfo));
    callObjectMethodIgnoringResult(env, gnssAntennaInfoBuilderObject,
                                   method_gnssAntennaInfoBuilderSetPhaseCenterOffset,
                                   phaseCenterOffset);
    callObjectMethodIgnoringResult(env, gnssAntennaInfoBuilderObject,
                                   method_gnssAntennaInfoBuilderSetPhaseCenterVariationCorrections,
                                   phaseCenterVariationCorrections);
    callObjectMethodIgnoringResult(env, gnssAntennaInfoBuilderObject,
                                   method_gnssAntennaInfoBuilderSetSignalGainCorrections,
                                   signalGainCorrections);

    // build
    jobject gnssAntennaInfoObject =
            env->CallObjectMethod(gnssAntennaInfoBuilderObject, method_gnssAntennaInfoBuilderBuild);

    // Delete Local Refs
    env->DeleteLocalRef(phaseCenterOffset);
    env->DeleteLocalRef(phaseCenterVariationCorrections);
    env->DeleteLocalRef(signalGainCorrections);

    return gnssAntennaInfoObject;
}

template <template <class...> class T_vector, class T_info>
void GnssAntennaInfoCallbackUtil::translateAndReportGnssAntennaInfo(
        const T_vector<T_info>& gnssAntennaInfos) {
    JNIEnv* env = getJniEnv();

    jobject arrayList = translateAllGnssAntennaInfos(env, gnssAntennaInfos);

    reportAntennaInfo(env, arrayList);

    env->DeleteLocalRef(arrayList);
}

void GnssAntennaInfoCallbackUtil::reportAntennaInfo(JNIEnv* env, const jobject antennaInfosArray) {
    env->CallVoidMethod(mCallbacksObj, method_reportAntennaInfo, antennaInfosArray);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

} // namespace android::gnss
