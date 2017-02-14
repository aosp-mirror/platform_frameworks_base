/*
 * Copyright 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef _ANDROID_MEDIA_VOLUME_SHAPER_H_
#define _ANDROID_MEDIA_VOLUME_SHAPER_H_

#include <media/VolumeShaper.h>

namespace android {

// This entire class is inline as it is used from both core and media
struct VolumeShaperHelper {
    struct fields_t {
        // VolumeShaper.Configuration
        jclass    coClazz;
        jmethodID coConstructId;
        jfieldID  coTypeId;
        jfieldID  coIdId;
        jfieldID  coInterpolatorTypeId;
        jfieldID  coOptionFlagsId;
        jfieldID  coDurationMsId;
        jfieldID  coTimesId;
        jfieldID  coVolumesId;

        // VolumeShaper.Operation
        jclass    opClazz;
        jmethodID opConstructId;
        jfieldID  opFlagsId;
        jfieldID  opReplaceIdId;

        // VolumeShaper.State
        jclass    stClazz;
        jmethodID stConstructId;
        jfieldID  stVolumeId;
        jfieldID  stXOffsetId;

        void init(JNIEnv *env) {
            jclass lclazz = env->FindClass("android/media/VolumeShaper$Configuration");
            if (lclazz == nullptr) {
                return;
            }
            coClazz = (jclass)env->NewGlobalRef(lclazz);
            if (coClazz == nullptr) {
                return;
            }
            coConstructId = env->GetMethodID(coClazz, "<init>", "(IIIID[F[F)V");
            coTypeId = env->GetFieldID(coClazz, "mType", "I");
            coIdId = env->GetFieldID(coClazz, "mId", "I");
            coInterpolatorTypeId = env->GetFieldID(coClazz, "mInterpolatorType", "I");
            coOptionFlagsId = env->GetFieldID(coClazz, "mOptionFlags", "I");
            coDurationMsId = env->GetFieldID(coClazz, "mDurationMs", "D");
            coTimesId = env->GetFieldID(coClazz, "mTimes", "[F");
            coVolumesId = env->GetFieldID(coClazz, "mVolumes", "[F");
            env->DeleteLocalRef(lclazz);

            lclazz = env->FindClass("android/media/VolumeShaper$Operation");
            if (lclazz == nullptr) {
                return;
            }
            opClazz = (jclass)env->NewGlobalRef(lclazz);
            if (opClazz == nullptr) {
                return;
            }
            opConstructId = env->GetMethodID(opClazz, "<init>", "(II)V");
            opFlagsId = env->GetFieldID(opClazz, "mFlags", "I");
            opReplaceIdId = env->GetFieldID(opClazz, "mReplaceId", "I");
            env->DeleteLocalRef(lclazz);

            lclazz = env->FindClass("android/media/VolumeShaper$State");
            if (lclazz == nullptr) {
                return;
            }
            stClazz = (jclass)env->NewGlobalRef(lclazz);
            if (stClazz == nullptr) {
                return;
            }
            stConstructId = env->GetMethodID(stClazz, "<init>", "(FF)V");
            stVolumeId = env->GetFieldID(stClazz, "mVolume", "F");
            stXOffsetId = env->GetFieldID(stClazz, "mXOffset", "F");
            env->DeleteLocalRef(lclazz);
        }

        void exit(JNIEnv *env) {
            env->DeleteGlobalRef(coClazz);
            coClazz = nullptr;
        }
    };

    static sp<VolumeShaper::Configuration> convertJobjectToConfiguration(
            JNIEnv *env, const fields_t &fields, jobject jshaper) {
        sp<VolumeShaper::Configuration> configuration = new VolumeShaper::Configuration();

        configuration->setType(
            (VolumeShaper::Configuration::Type)env->GetIntField(jshaper, fields.coTypeId));
        configuration->setId(
            (int)env->GetIntField(jshaper, fields.coIdId));
        if (configuration->getType() == VolumeShaper::Configuration::TYPE_SCALE) {
            configuration->setInterpolatorType(
                (VolumeShaper::Configuration::InterpolatorType)
                env->GetIntField(jshaper, fields.coInterpolatorTypeId));
            configuration->setOptionFlags(
                (VolumeShaper::Configuration::OptionFlag)
                env->GetIntField(jshaper, fields.coOptionFlagsId));
            configuration->setDurationMs(
                    (double)env->GetDoubleField(jshaper, fields.coDurationMsId));

            // convert point arrays
            jobject xobj = env->GetObjectField(jshaper, fields.coTimesId);
            jfloatArray *xarray = reinterpret_cast<jfloatArray*>(&xobj);
            jsize xlen = env->GetArrayLength(*xarray);
            /* const */ float * const x =
                    env->GetFloatArrayElements(*xarray, nullptr /* isCopy */);
            jobject yobj = env->GetObjectField(jshaper, fields.coVolumesId);
            jfloatArray *yarray = reinterpret_cast<jfloatArray*>(&yobj);
            jsize ylen = env->GetArrayLength(*yarray);
            /* const */ float * const y =
                    env->GetFloatArrayElements(*yarray, nullptr /* isCopy */);
            if (xlen != ylen) {
                ALOGE("array size must match");
                return nullptr;
            }
            for (jsize i = 0; i < xlen; ++i) {
                configuration->emplace(x[i], y[i]);
            }
            env->ReleaseFloatArrayElements(*xarray, x, JNI_ABORT); // no need to copy back
            env->ReleaseFloatArrayElements(*yarray, y, JNI_ABORT);
        }
        return configuration;
    }

    static jobject convertVolumeShaperToJobject(
            JNIEnv *env, const fields_t &fields,
            const sp<VolumeShaper::Configuration> &configuration) {
        jfloatArray xarray = nullptr;
        jfloatArray yarray = nullptr;
        if (configuration->getType() == VolumeShaper::Configuration::TYPE_SCALE) {
            // convert curve arrays
            jfloatArray xarray = env->NewFloatArray(configuration->size());
            jfloatArray yarray = env->NewFloatArray(configuration->size());
            float * const x = env->GetFloatArrayElements(xarray, nullptr /* isCopy */);
            float * const y = env->GetFloatArrayElements(yarray, nullptr /* isCopy */);
            float *xptr = x, *yptr = y;
            for (const auto &pt : *configuration.get()) {
                *xptr++ = pt.first;
                *yptr++ = pt.second;
            }
            env->ReleaseFloatArrayElements(xarray, x, 0 /* mode */);
            env->ReleaseFloatArrayElements(yarray, y, 0 /* mode */);
        }

        // prepare constructor args
        jvalue args[7];
        args[0].i = (jint)configuration->getType();
        args[1].i = (jint)configuration->getId();
        args[2].i = (jint)configuration->getInterpolatorType();
        args[3].i = (jint)configuration->getOptionFlags();
        args[4].d = (jdouble)configuration->getDurationMs();
        args[5].l = xarray;
        args[6].l = yarray;
        jobject jshaper = env->NewObjectA(fields.coClazz, fields.coConstructId, args);
        return jshaper;
    }

    static sp<VolumeShaper::Operation> convertJobjectToOperation(
            JNIEnv *env, const fields_t &fields, jobject joperation) {
        VolumeShaper::Operation::Flag flags =
            (VolumeShaper::Operation::Flag)env->GetIntField(joperation, fields.opFlagsId);
        int replaceId = env->GetIntField(joperation, fields.opReplaceIdId);

        sp<VolumeShaper::Operation> operation = new VolumeShaper::Operation(flags, replaceId);
        return operation;
    }

    static jobject convertOperationToJobject(
            JNIEnv *env, const fields_t &fields, const sp<VolumeShaper::Operation> &operation) {
        // prepare constructor args
        jvalue args[2];
        args[0].i = (jint)operation->getFlags();
        args[1].i = (jint)operation->getReplaceId();

        jobject joperation = env->NewObjectA(fields.opClazz, fields.opConstructId, args);
        return joperation;
    }

    static sp<VolumeShaper::State> convertJobjectToState(
            JNIEnv *env, const fields_t &fields, jobject jstate) {
        float volume = env->GetFloatField(jstate, fields.stVolumeId);
        float xOffset = env->GetFloatField(jstate, fields.stXOffsetId);

        sp<VolumeShaper::State> state = new VolumeShaper::State(volume, xOffset);
        return state;
    }

    static jobject convertStateToJobject(
            JNIEnv *env, const fields_t &fields, const sp<VolumeShaper::State> &state) {
        // prepare constructor args
        jvalue args[2];
        args[0].f = (jfloat)state->getVolume();
        args[1].f = (jfloat)state->getXOffset();

        jobject jstate = env->NewObjectA(fields.stClazz, fields.stConstructId, args);
        return jstate;
    }
};

}  // namespace android

#endif // _ANDROID_MEDIA_VOLUME_SHAPER_H_
