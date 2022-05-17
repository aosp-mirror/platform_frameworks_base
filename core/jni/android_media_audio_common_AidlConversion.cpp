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

#define LOG_TAG "AidlConversion"

#include <sstream>
#include <type_traits>

#include <android_os_Parcel.h>
#include <binder/Parcel.h>
#include <jni.h>
#include <log/log.h>
#include <media/AidlConversion.h>
#include <system/audio.h>

#include "core_jni_helpers.h"

namespace {

using namespace android;
using media::audio::common::AudioChannelLayout;
using media::audio::common::AudioEncapsulationMode;
using media::audio::common::AudioFormatDescription;
using media::audio::common::AudioStreamType;
using media::audio::common::AudioUsage;

#define PACKAGE "android/media/audio/common"
#define CLASSNAME PACKAGE "/AidlConversion"

// Used for creating messages.
template <typename T>
struct type_info {
    static constexpr const char* name = "";
};
#define TYPE_NAME_QUOTE(x) #x
#define TYPE_NAME_STRINGIFY(x) TYPE_NAME_QUOTE(x)
#define TYPE_NAME(n)                                                \
    template <>                                                     \
    struct type_info<n> {                                           \
        static constexpr const char* name = TYPE_NAME_STRINGIFY(n); \
    }

TYPE_NAME(AudioChannelLayout);
TYPE_NAME(AudioEncapsulationMode);
TYPE_NAME(AudioFormatDescription);
TYPE_NAME(AudioStreamType);
TYPE_NAME(AudioUsage);
TYPE_NAME(audio_encapsulation_mode_t);
TYPE_NAME(audio_stream_type_t);
TYPE_NAME(audio_usage_t);

template <typename AidlType, typename LegacyType, typename ConvFunc>
int aidl2legacy(JNIEnv* env, AidlType aidl, const ConvFunc& conv, LegacyType fallbackValue) {
    const auto result = conv(aidl);
    if (result.ok()) {
        return result.value();
    }
    std::ostringstream msg;
    msg << "Failed to convert " << type_info<AidlType>::name << " value "
        << static_cast<std::underlying_type_t<AidlType>>(aidl);
    jniThrowException(env, "java/lang/IllegalArgumentException", msg.str().c_str());
    return fallbackValue;
}

template <typename LegacyType, typename AidlType, typename ConvFunc>
int legacy2aidl(JNIEnv* env, LegacyType legacy, const ConvFunc& conv, AidlType fallbackValue) {
    const auto result = conv(legacy);
    if (result.ok()) {
        return static_cast<std::underlying_type_t<AidlType>>(result.value());
    }
    std::ostringstream msg;
    msg << "Failed to convert legacy " << type_info<LegacyType>::name << " value " << legacy;
    jniThrowException(env, "java/lang/IllegalArgumentException", msg.str().c_str());
    return static_cast<std::underlying_type_t<AidlType>>(fallbackValue);
}

template <typename AidlType, typename ConvFunc>
int aidlParcel2legacy(JNIEnv* env, jobject jParcel, const ConvFunc& conv, int fallbackValue) {
    if (Parcel* parcel = parcelForJavaObject(env, jParcel); parcel != nullptr) {
        AidlType aidl{};
        if (status_t status = aidl.readFromParcel(parcel); status == OK) {
            auto legacy = conv(aidl);
            if (legacy.ok()) {
                return legacy.value();
            }
        } else {
            ALOGE("aidl2legacy: Failed to read from parcel: %s", statusToString(status).c_str());
        }
        std::ostringstream msg;
        msg << "Failed to convert " << type_info<AidlType>::name << " value " << aidl.toString();
        jniThrowException(env, "java/lang/IllegalArgumentException", msg.str().c_str());
    } else {
        ALOGE("aidl2legacy: Failed to retrieve the native parcel from Java parcel");
    }
    return fallbackValue;
}

template <typename LegacyType, typename ConvFunc>
jobject legacy2aidlParcel(JNIEnv* env, LegacyType legacy, const ConvFunc& conv) {
    auto aidl = conv(legacy);
    if (!aidl.ok()) {
        std::ostringstream msg;
        msg << "Failed to convert legacy " << type_info<LegacyType>::name << " value " << legacy;
        jniThrowException(env, "java/lang/IllegalArgumentException", msg.str().c_str());
        return 0;
    }
    if (jobject jParcel = createJavaParcelObject(env); jParcel != 0) {
        if (Parcel* parcel = parcelForJavaObject(env, jParcel); parcel != nullptr) {
            if (status_t status = aidl.value().writeToParcel(parcel); status == OK) {
                parcel->setDataPosition(0);
                return jParcel;
            } else {
                ALOGE("legacy2aidl: Failed to write to parcel: %s, aidl value: %s",
                      statusToString(status).c_str(), aidl.value().toString().c_str());
            }
        } else {
            ALOGE("legacy2aidl: Failed to retrieve the native parcel from Java parcel");
        }
        env->DeleteLocalRef(jParcel);
    } else {
        ALOGE("legacy2aidl: Failed to create Java parcel");
    }
    return 0;
}

int aidl2legacy_AudioChannelLayout_Parcel_audio_channel_mask_t(JNIEnv* env, jobject,
                                                               jobject jParcel, jboolean isInput) {
    return aidlParcel2legacy<AudioChannelLayout>(
            env, jParcel,
            [isInput](const AudioChannelLayout& l) {
                return aidl2legacy_AudioChannelLayout_audio_channel_mask_t(l, isInput);
            },
            AUDIO_CHANNEL_INVALID);
}

jobject legacy2aidl_audio_channel_mask_t_AudioChannelLayout_Parcel(
        JNIEnv* env, jobject, int /*audio_channel_mask_t*/ legacy, jboolean isInput) {
    return legacy2aidlParcel(
            env, static_cast<audio_channel_mask_t>(legacy), [isInput](audio_channel_mask_t m) {
                return legacy2aidl_audio_channel_mask_t_AudioChannelLayout(m, isInput);
            });
}

int aidl2legacy_AudioFormatDescription_Parcel_audio_format_t(JNIEnv* env, jobject,
                                                             jobject jParcel) {
    return aidlParcel2legacy<
            AudioFormatDescription>(env, jParcel, aidl2legacy_AudioFormatDescription_audio_format_t,
                                    AUDIO_FORMAT_INVALID);
}

jobject legacy2aidl_audio_format_t_AudioFormatDescription_Parcel(JNIEnv* env, jobject,
                                                                 int /*audio_format_t*/ legacy) {
    return legacy2aidlParcel(env, static_cast<audio_format_t>(legacy),
                             legacy2aidl_audio_format_t_AudioFormatDescription);
}

jint aidl2legacy_AudioEncapsulationMode_audio_encapsulation_mode_t(JNIEnv* env, jobject,
                                                                   jint aidl) {
    return aidl2legacy(env, AudioEncapsulationMode(static_cast<int32_t>(aidl)),
                       android::aidl2legacy_AudioEncapsulationMode_audio_encapsulation_mode_t,
                       AUDIO_ENCAPSULATION_MODE_NONE);
}

jint legacy2aidl_audio_encapsulation_mode_t_AudioEncapsulationMode(JNIEnv* env, jobject,
                                                                   jint legacy) {
    return legacy2aidl(env, static_cast<audio_encapsulation_mode_t>(legacy),
                       android::legacy2aidl_audio_encapsulation_mode_t_AudioEncapsulationMode,
                       AudioEncapsulationMode::INVALID);
}

jint aidl2legacy_AudioStreamType_audio_stream_type_t(JNIEnv* env, jobject, jint aidl) {
    return aidl2legacy(env, AudioStreamType(static_cast<int32_t>(aidl)),
                       android::aidl2legacy_AudioStreamType_audio_stream_type_t,
                       AUDIO_STREAM_DEFAULT);
}

jint legacy2aidl_audio_stream_type_t_AudioStreamType(JNIEnv* env, jobject, jint legacy) {
    return legacy2aidl(env, static_cast<audio_stream_type_t>(legacy),
                       android::legacy2aidl_audio_stream_type_t_AudioStreamType,
                       AudioStreamType::INVALID);
}

jint aidl2legacy_AudioUsage_audio_usage_t(JNIEnv* env, jobject, jint aidl) {
    return aidl2legacy(env, AudioUsage(static_cast<int32_t>(aidl)),
                       android::aidl2legacy_AudioUsage_audio_usage_t, AUDIO_USAGE_UNKNOWN);
}

jint legacy2aidl_audio_usage_t_AudioUsage(JNIEnv* env, jobject, jint legacy) {
    return legacy2aidl(env, static_cast<audio_usage_t>(legacy),
                       android::legacy2aidl_audio_usage_t_AudioUsage, AudioUsage::INVALID);
}

const JNINativeMethod gMethods[] = {
        {"aidl2legacy_AudioChannelLayout_Parcel_audio_channel_mask_t", "(Landroid/os/Parcel;Z)I",
         reinterpret_cast<void*>(aidl2legacy_AudioChannelLayout_Parcel_audio_channel_mask_t)},
        {"legacy2aidl_audio_channel_mask_t_AudioChannelLayout_Parcel", "(IZ)Landroid/os/Parcel;",
         reinterpret_cast<void*>(legacy2aidl_audio_channel_mask_t_AudioChannelLayout_Parcel)},
        {"aidl2legacy_AudioFormatDescription_Parcel_audio_format_t", "(Landroid/os/Parcel;)I",
         reinterpret_cast<void*>(aidl2legacy_AudioFormatDescription_Parcel_audio_format_t)},
        {"legacy2aidl_audio_format_t_AudioFormatDescription_Parcel", "(I)Landroid/os/Parcel;",
         reinterpret_cast<void*>(legacy2aidl_audio_format_t_AudioFormatDescription_Parcel)},
        {"aidl2legacy_AudioEncapsulationMode_audio_encapsulation_mode_t", "(I)I",
         reinterpret_cast<void*>(aidl2legacy_AudioEncapsulationMode_audio_encapsulation_mode_t)},
        {"legacy2aidl_audio_encapsulation_mode_t_AudioEncapsulationMode", "(I)I",
         reinterpret_cast<void*>(legacy2aidl_audio_encapsulation_mode_t_AudioEncapsulationMode)},
        {"aidl2legacy_AudioStreamType_audio_stream_type_t", "(I)I",
         reinterpret_cast<void*>(aidl2legacy_AudioStreamType_audio_stream_type_t)},
        {"legacy2aidl_audio_stream_type_t_AudioStreamType", "(I)I",
         reinterpret_cast<void*>(legacy2aidl_audio_stream_type_t_AudioStreamType)},
        {"aidl2legacy_AudioUsage_audio_usage_t", "(I)I",
         reinterpret_cast<void*>(aidl2legacy_AudioUsage_audio_usage_t)},
        {"legacy2aidl_audio_usage_t_AudioUsage", "(I)I",
         reinterpret_cast<void*>(legacy2aidl_audio_usage_t_AudioUsage)},
};

} // namespace

int register_android_media_audio_common_AidlConversion(JNIEnv* env) {
    return RegisterMethodsOrDie(env, CLASSNAME, gMethods, NELEM(gMethods));
}
