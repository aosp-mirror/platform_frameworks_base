/**
 * Copyright (C) 2017 The Android Open Source Project
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

#define LOG_TAG "radio.convert.jni"
#define LOG_NDEBUG 0

#include "com_android_server_radio_convert.h"

#include <core_jni_helpers.h>
#include <utils/Log.h>
#include <JNIHelp.h>

namespace android {
namespace server {
namespace radio {
namespace convert {

using hardware::hidl_vec;

using V1_0::Band;
using V1_0::Deemphasis;
using V1_0::Rds;

static struct {
    struct {
        jfieldID descriptor;
    } BandConfig;
    struct {
        jclass clazz;
        jmethodID cstor;
        jfieldID stereo;
        jfieldID rds;
        jfieldID ta;
        jfieldID af;
        jfieldID ea;
    } FmBandConfig;
    struct {
        jclass clazz;
        jmethodID cstor;
        jfieldID stereo;
    } AmBandConfig;

    struct {
        jfieldID region;
        jfieldID type;
        jfieldID lowerLimit;
        jfieldID upperLimit;
        jfieldID spacing;
    } BandDescriptor;
} gjni;

static Rds RdsForRegion(bool rds, Region region) {
    if (!rds) return Rds::NONE;

    switch(region) {
        case Region::ITU_1:
        case Region::OIRT:
        case Region::JAPAN:
        case Region::KOREA:
            return Rds::WORLD;
        case Region::ITU_2:
            return Rds::US;
        default:
            ALOGE("Unexpected region: %d", region);
            return Rds::NONE;
    }
}

static Deemphasis DeemphasisForRegion(Region region) {
    switch(region) {
        case Region::KOREA:
        case Region::ITU_2:
            return Deemphasis::D75;
        case Region::ITU_1:
        case Region::OIRT:
        case Region::JAPAN:
            return Deemphasis::D50;
        default:
            ALOGE("Unexpected region: %d", region);
            return Deemphasis::D50;
    }
}

JavaRef BandConfigFromHal(JNIEnv *env, const V1_0::BandConfig &config, Region region) {
    EnvWrapper wrap(env);

    jint spacing = config.spacings.size() > 0 ? config.spacings[0] : 0;
    ALOGW_IF(config.spacings.size() == 0, "No channel spacing specified");

    switch (config.type) {
        case Band::FM:
        case Band::FM_HD: {
            auto& fm = config.ext.fm;
            return wrap(env->NewObject(gjni.FmBandConfig.clazz, gjni.FmBandConfig.cstor,
                    region, config.type, config.lowerLimit, config.upperLimit, spacing,
                    fm.stereo, fm.rds != Rds::NONE, fm.ta, fm.af, fm.ea));
        }
        case Band::AM:
        case Band::AM_HD: {
            auto& am = config.ext.am;
            return wrap(env->NewObject(gjni.AmBandConfig.clazz, gjni.AmBandConfig.cstor,
                    region, config.type, config.lowerLimit, config.upperLimit, spacing,
                    am.stereo));
        }
        default:
            ALOGE("Unsupported band type: %d", config.type);
            return nullptr;
    }
}

V1_0::BandConfig BandConfigToHal(JNIEnv *env, jobject jConfig, Region &region) {
    auto jDescriptor = env->GetObjectField(jConfig, gjni.BandConfig.descriptor);
    if (jDescriptor == nullptr) {
        ALOGE("Descriptor is missing");
        return {};
    }

    region = static_cast<Region>(env->GetIntField(jDescriptor, gjni.BandDescriptor.region));

    V1_0::BandConfig config = {};
    config.type = static_cast<Band>(env->GetIntField(jDescriptor, gjni.BandDescriptor.type));
    config.antennaConnected = false;  // just don't set it
    config.lowerLimit = env->GetIntField(jDescriptor, gjni.BandDescriptor.lowerLimit);
    config.upperLimit = env->GetIntField(jDescriptor, gjni.BandDescriptor.upperLimit);
    config.spacings = hidl_vec<uint32_t>({
        static_cast<uint32_t>(env->GetIntField(jDescriptor, gjni.BandDescriptor.spacing))
    });

    if (env->IsInstanceOf(jConfig, gjni.FmBandConfig.clazz)) {
        auto& fm = config.ext.fm;
        fm.deemphasis = DeemphasisForRegion(region);
        fm.stereo = env->GetBooleanField(jConfig, gjni.FmBandConfig.stereo);
        fm.rds = RdsForRegion(env->GetBooleanField(jConfig, gjni.FmBandConfig.rds), region);
        fm.ta = env->GetBooleanField(jConfig, gjni.FmBandConfig.ta);
        fm.af = env->GetBooleanField(jConfig, gjni.FmBandConfig.af);
        fm.ea = env->GetBooleanField(jConfig, gjni.FmBandConfig.ea);
    } else if (env->IsInstanceOf(jConfig, gjni.AmBandConfig.clazz)) {
        auto& am = config.ext.am;
        am.stereo = env->GetBooleanField(jConfig, gjni.AmBandConfig.stereo);
    } else {
        ALOGE("Unexpected band config type");
        return {};
    }

    return config;
}


} // namespace convert
} // namespace radio
} // namespace server

void register_android_server_radio_convert(JNIEnv *env) {
    using namespace server::radio::convert;

    auto bandConfigClass = FindClassOrDie(env, "android/hardware/radio/RadioManager$BandConfig");
    gjni.BandConfig.descriptor = GetFieldIDOrDie(env, bandConfigClass,
            "mDescriptor", "Landroid/hardware/radio/RadioManager$BandDescriptor;");

    auto fmBandConfigClass = FindClassOrDie(env,
            "android/hardware/radio/RadioManager$FmBandConfig");
    gjni.FmBandConfig.clazz = MakeGlobalRefOrDie(env, fmBandConfigClass);
    gjni.FmBandConfig.cstor = GetMethodIDOrDie(env, fmBandConfigClass,
            "<init>", "(IIIIIZZZZZ)V");
    gjni.FmBandConfig.stereo = GetFieldIDOrDie(env, fmBandConfigClass, "mStereo", "Z");
    gjni.FmBandConfig.rds = GetFieldIDOrDie(env, fmBandConfigClass, "mRds", "Z");
    gjni.FmBandConfig.ta = GetFieldIDOrDie(env, fmBandConfigClass, "mTa", "Z");
    gjni.FmBandConfig.af = GetFieldIDOrDie(env, fmBandConfigClass, "mAf", "Z");
    gjni.FmBandConfig.ea = GetFieldIDOrDie(env, fmBandConfigClass, "mEa", "Z");

    auto amBandConfigClass = FindClassOrDie(env,
            "android/hardware/radio/RadioManager$AmBandConfig");
    gjni.AmBandConfig.clazz = MakeGlobalRefOrDie(env, amBandConfigClass);
    gjni.AmBandConfig.cstor = GetMethodIDOrDie(env, amBandConfigClass, "<init>", "(IIIIIZ)V");
    gjni.AmBandConfig.stereo = GetFieldIDOrDie(env, amBandConfigClass, "mStereo", "Z");

    auto bandDescriptorClass = FindClassOrDie(env,
            "android/hardware/radio/RadioManager$BandDescriptor");
    gjni.BandDescriptor.region = GetFieldIDOrDie(env, bandDescriptorClass, "mRegion", "I");
    gjni.BandDescriptor.type = GetFieldIDOrDie(env, bandDescriptorClass, "mType", "I");
    gjni.BandDescriptor.lowerLimit = GetFieldIDOrDie(env, bandDescriptorClass, "mLowerLimit", "I");
    gjni.BandDescriptor.upperLimit = GetFieldIDOrDie(env, bandDescriptorClass, "mUpperLimit", "I");
    gjni.BandDescriptor.spacing = GetFieldIDOrDie(env, bandDescriptorClass, "mSpacing", "I");
}

} // namespace android
