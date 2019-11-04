/*
 * Copyright 2019 The Android Open Source Project
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

#define LOG_TAG "TvTuner-JNI"
#include <utils/Log.h>

#include "android_media_tv_Tuner.h"
#include "android_runtime/AndroidRuntime.h"

#include <android/hardware/tv/tuner/1.0/ITuner.h>
#include <media/stagefright/foundation/ADebug.h>

#pragma GCC diagnostic ignored "-Wunused-function"

using ::android::hardware::Void;
using ::android::hardware::hidl_vec;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterMainType;
using ::android::hardware::tv::tuner::V1_0::DemuxTsFilterType;
using ::android::hardware::tv::tuner::V1_0::ITuner;
using ::android::hardware::tv::tuner::V1_0::Result;

struct fields_t {
    jfieldID context;
    jmethodID frontendInitID;
    jmethodID filterInitID;
};

static fields_t gFields;

namespace android {
/////////////// FilterCallback ///////////////////////
//TODO: implement filter callback
Return<void> FilterCallback::onFilterEvent(const DemuxFilterEvent& /* filterEvent */) {
    ALOGD("FilterCallback::onFilterEvent");
    return Void();
}
Return<void> FilterCallback::onFilterStatus(const DemuxFilterStatus /*status*/) {
    ALOGD("FilterCallback::onFilterStatu");
    return Void();
}

/////////////// Tuner ///////////////////////
sp<ITuner> JTuner::mTuner;

JTuner::JTuner(JNIEnv *env, jobject thiz)
    : mClass(NULL) {
    jclass clazz = env->GetObjectClass(thiz);
    CHECK(clazz != NULL);

    mClass = (jclass)env->NewGlobalRef(clazz);
    mObject = env->NewWeakGlobalRef(thiz);
    if (mTuner == NULL) {
        mTuner = getTunerService();
    }
}

JTuner::~JTuner() {
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    env->DeleteGlobalRef(mClass);
    mTuner = NULL;
    mClass = NULL;
    mObject = NULL;
}

sp<ITuner> JTuner::getTunerService() {
    if (mTuner == nullptr) {
        mTuner = ITuner::getService();

        if (mTuner == nullptr) {
            ALOGW("Failed to get tuner service.");
        }
    }
    return mTuner;
}

jobject JTuner::getFrontendIds() {
    ALOGD("JTuner::getFrontendIds()");
    hidl_vec<FrontendId> feIds;
    mTuner->getFrontendIds([&](Result, const hidl_vec<FrontendId>& frontendIds) {
        feIds = frontendIds;
    });
    if (feIds.size() == 0) {
        ALOGW("Frontend isn't available");
        return NULL;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jclass arrayListClazz = env->FindClass("java/util/ArrayList");
    jmethodID arrayListAdd = env->GetMethodID(arrayListClazz, "add", "(Ljava/lang/Object;)Z");
    jobject obj = env->NewObject(arrayListClazz, env->GetMethodID(arrayListClazz, "<init>", "()V"));

    jclass integerClazz = env->FindClass("java/lang/Integer");
    jmethodID intInit = env->GetMethodID(integerClazz, "<init>", "(I)V");

    for (int i=0; i < feIds.size(); i++) {
       jobject idObj = env->NewObject(integerClazz, intInit, feIds[i]);
       env->CallBooleanMethod(obj, arrayListAdd, idObj);
    }
    return obj;
}

jobject JTuner::openFrontendById(int id) {
    mTuner->openFrontendById(id, [&](Result, const sp<IFrontend>& frontend) {
        mFe = frontend;
    });
    if (mFe == nullptr) {
        ALOGE("Failed to open frontend");
        return NULL;
    }

    jint jId = (jint) id;
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    // TODO: add more fields to frontend
    return env->NewObject(
            env->FindClass("android/media/tv/tuner/Tuner$Frontend"),
            gFields.frontendInitID,
            (jint) jId);
}

bool JTuner::openDemux() {
    if (mTuner == nullptr) {
        return false;
    }
    if (mDemux != nullptr) {
        return true;
    }
    mTuner->openDemux([&](Result, uint32_t demuxId, const sp<IDemux>& demux) {
        mDemux = demux;
        mDemuxId = demuxId;
        ALOGD("open demux, id = %d", demuxId);
    });
    if (mDemux == nullptr) {
        return false;
    }
    return true;
}

jobject JTuner::openFilter(DemuxFilterType type, int bufferSize) {
    if (mDemux == NULL) {
        if (!openDemux()) {
            return NULL;
        }
    }

    sp<IFilter> f;
    mDemux->openFilter(type, bufferSize, new FilterCallback,
            [&](Result, const sp<IFilter>& filter) {
                f = filter;
            });
    if (f == NULL) {
        ALOGD("Failed to open filter, type = %d", type.mainType);
        return NULL;
    }
    int fId;
    f->getId([&](Result, uint32_t filterId) {
        fId = filterId;
    });
    mFilters[fId] = f;

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    return env->NewObject(
            env->FindClass("android/media/tv/tuner/Tuner$Filter"),
            gFields.filterInitID,
            mObject,
            (jint) fId);
}

}  // namespace android

////////////////////////////////////////////////////////////////////////////////

using namespace android;

static sp<JTuner> setTuner(JNIEnv *env, jobject thiz, const sp<JTuner> &tuner) {
    sp<JTuner> old = (JTuner *)env->GetLongField(thiz, gFields.context);

    if (tuner != NULL) {
        tuner->incStrong(thiz);
    }
    if (old != NULL) {
        old->decStrong(thiz);
    }
    env->SetLongField(thiz, gFields.context, (jlong)tuner.get());

    return old;
}

static sp<JTuner> getTuner(JNIEnv *env, jobject thiz) {
    return (JTuner *)env->GetLongField(thiz, gFields.context);
}

static void android_media_tv_Tuner_native_init(JNIEnv *env) {
    jclass clazz = env->FindClass("android/media/tv/tuner/Tuner");
    CHECK(clazz != NULL);

    gFields.context = env->GetFieldID(clazz, "mNativeContext", "J");
    CHECK(gFields.context != NULL);

    jclass frontendClazz = env->FindClass("android/media/tv/tuner/Tuner$Frontend");
    gFields.frontendInitID = env->GetMethodID(frontendClazz, "<init>", "(I)V");

    jclass filterClazz = env->FindClass("android/media/tv/tuner/Tuner$Filter");
    gFields.filterInitID =
            env->GetMethodID(filterClazz, "<init>", "(Landroid/media/tv/tuner/Tuner;I)V");
}

static void android_media_tv_Tuner_native_setup(JNIEnv *env, jobject thiz) {
    sp<JTuner> tuner = new JTuner(env, thiz);
    setTuner(env,thiz, tuner);
}

static jobject android_media_tv_Tuner_get_frontend_ids(JNIEnv *env, jobject thiz) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->getFrontendIds();
}

static jobject android_media_tv_Tuner_open_frontend_by_id(JNIEnv *env, jobject thiz, jint id) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->openFrontendById(id);
}

static jobject android_media_tv_Tuner_open_filter(
        JNIEnv *env, jobject thiz, jint type, jint subType, jint bufferSize) {
    sp<JTuner> tuner = getTuner(env, thiz);
    DemuxFilterType filterType {
        .mainType = static_cast<DemuxFilterMainType>(type),
    };

    // TODO: other sub types
    filterType.subType.tsFilterType(static_cast<DemuxTsFilterType>(subType));

    return tuner->openFilter(filterType, bufferSize);
}

static const JNINativeMethod gMethods[] = {
    { "nativeInit", "()V", (void *)android_media_tv_Tuner_native_init },
    { "nativeSetup", "()V", (void *)android_media_tv_Tuner_native_setup },
    { "nativeGetFrontendIds", "()Ljava/util/List;",
            (void *)android_media_tv_Tuner_get_frontend_ids },
    { "nativeOpenFrontendById", "(I)Landroid/media/tv/tuner/Tuner$Frontend;",
            (void *)android_media_tv_Tuner_open_frontend_by_id },
    { "nativeOpenFilter", "(III)Landroid/media/tv/tuner/Tuner$Filter;",
            (void *)android_media_tv_Tuner_open_filter },
};

static int register_android_media_tv_Tuner(JNIEnv *env) {
    return AndroidRuntime::registerNativeMethods(
            env, "android/media/tv/tuner/Tuner", gMethods, NELEM(gMethods));
}

jint JNI_OnLoad(JavaVM* vm, void* /* reserved */)
{
    JNIEnv* env = NULL;
    jint result = -1;

    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        ALOGE("ERROR: GetEnv failed\n");
        return result;
    }
    assert(env != NULL);

    if (register_android_media_tv_Tuner(env) != JNI_OK) {
        ALOGE("ERROR: Tuner native registration failed\n");
        return result;
    }
    return JNI_VERSION_1_4;
}
