/*
 * Copyright (C) 2023 The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "NativeWorkSourceParcelTest"

#include "jni.h"
#include "ParcelHelper.h"

#include <android_util_Binder.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <utils/Log.h>

#include <android/WorkSource.h>
#include <android-base/stringprintf.h>
#include <android-base/strings.h>

using namespace android::os;
using android::base::StringPrintf;

namespace android {

static jobject nativeObtainWorkSourceParcel(JNIEnv* env, jobject /* obj */, jintArray uidArray,
            jobjectArray nameArray, jint parcelEndMarker) {
    std::vector<int32_t> uids;
    std::optional<std::vector<std::optional<String16>>> names = std::nullopt;

    if (uidArray != nullptr) {
        ScopedIntArrayRO workSourceUids(env, uidArray);
        for (int i = 0; i < workSourceUids.size(); i++) {
            uids.push_back(static_cast<int32_t>(workSourceUids[i]));
        }
    }

    if (nameArray != nullptr) {
        std::vector<std::optional<String16>> namesVec;
        for (jint i = 0; i < env->GetArrayLength(nameArray); i++) {
            jstring string = static_cast<jstring>(env->GetObjectArrayElement(nameArray, i));
            const char *rawString = env->GetStringUTFChars(string, 0);
            namesVec.push_back(std::make_optional<String16>(String16(rawString)));
        }
        names = std::make_optional(std::move(namesVec));
    }

    WorkSource ws = WorkSource(uids, names);
    jobject wsParcel = nativeObtainParcel(env);
    Parcel* parcel = nativeGetParcelData(env, wsParcel);

    // write WorkSource and if no error write end marker
    status_t err = ws.writeToParcel(parcel) ?: parcel->writeInt32(parcelEndMarker);

    if (err != OK) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                            StringPrintf("WorkSource writeToParcel failed %d", err).c_str());
    }
    parcel->setDataPosition(0);
    return wsParcel;
}

static void nativeUnparcelAndVerifyWorkSource(JNIEnv* env, jobject /* obj */, jobject wsParcel,
        jintArray uidArray, jobjectArray nameArray, jint parcelEndMarker) {
    WorkSource ws = {};
    Parcel* parcel = nativeGetParcelData(env, wsParcel);
    int32_t endMarker;

    status_t err = ws.readFromParcel(parcel);
    if (err != OK) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                StringPrintf("WorkSource readFromParcel failed: %d", err).c_str());
    }
    err = parcel->readInt32(&endMarker);
    if (err != OK) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                StringPrintf("Failed to read endMarker: %d", err).c_str());
    }
    // Now we have a native WorkSource object, verify it.
    int32_t dataAvailable = parcel->dataAvail();
    if (dataAvailable > 0) { // not all data read from the parcel
        jniThrowException(env, "java/lang/IllegalArgumentException",
                StringPrintf("WorkSource contains more data than native read (%d)",
                dataAvailable).c_str());
    } else if (endMarker != parcelEndMarker) { // more date than available read from parcel
        jniThrowException(env, "java/lang/IllegalArgumentException",
                StringPrintf("WorkSource contains less data than native read").c_str());
    }

    if (uidArray != nullptr) {
        ScopedIntArrayRO workSourceUids(env, uidArray);
        for (int i = 0; i < workSourceUids.size(); i++) {
            if (ws.getUids().at(i) != static_cast<int32_t>(workSourceUids[i])) {
                jniThrowException(env, "java/lang/IllegalArgumentException",
                            StringPrintf("WorkSource uid not equal %d %d",
                            ws.getUids().at(i), static_cast<int32_t>(workSourceUids[i])).c_str());
            }
        }
    } else {
        if (ws.getUids().size() != 0) {
            jniThrowException(env, "java/lang/IllegalArgumentException",
                    StringPrintf("WorkSource parcel size not 0").c_str());
        }
    }

    if (nameArray != nullptr) {
        std::vector<std::optional<String16>> namesVec;
        for (jint i = 0; i < env->GetArrayLength(nameArray); i++) {
            jstring string = (jstring) (env->GetObjectArrayElement(nameArray, i));
            const char *rawString = env->GetStringUTFChars(string, 0);
            if (String16(rawString) != ws.getNames()->at(i)) {
                jniThrowException(env, "java/lang/IllegalArgumentException",
                            StringPrintf("WorkSource uid not equal %s", rawString).c_str());
            }
        }
    } else {
        if (ws.getNames() != std::nullopt) {
            jniThrowException(env, "java/lang/IllegalArgumentException",
                    StringPrintf("WorkSource parcel name not empty").c_str());
        }
    }
}

extern "C" jint JNI_OnLoad(JavaVM* vm, void* /* reserved */)
{
    JNIEnv* env;

    const JNINativeMethod workSourceMethodTable[] = {
        /* name, signature, funcPtr */
        { "nativeObtainWorkSourceParcel", "([I[Ljava/lang/String;I)Landroid/os/Parcel;",
                (void*) nativeObtainWorkSourceParcel },
        { "nativeUnparcelAndVerifyWorkSource", "(Landroid/os/Parcel;[I[Ljava/lang/String;I)V",
                (void*) nativeUnparcelAndVerifyWorkSource },
    };

    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    loadParcelClass(env);

    jniRegisterNativeMethods(env, "android/os/WorkSourceParcelTest", workSourceMethodTable,
                sizeof(workSourceMethodTable) / sizeof(JNINativeMethod));

    return JNI_VERSION_1_6;
}

} /* namespace android */
