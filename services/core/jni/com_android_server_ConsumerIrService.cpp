/*
 * Copyright (C) 2013 The Android Open Source Project
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

#define LOG_TAG "ConsumerIrService"

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include "android_runtime/AndroidRuntime.h"

#include <stdlib.h>
#include <utils/misc.h>
#include <utils/Log.h>
#include <android/hardware/ir/1.0/IConsumerIr.h>
#include <nativehelper/ScopedPrimitiveArray.h>

using ::android::hardware::ir::V1_0::IConsumerIr;
using ::android::hardware::ir::V1_0::ConsumerIrFreqRange;
using ::android::hardware::hidl_vec;

namespace android {

static sp<IConsumerIr> mHal;

static jboolean halOpen(JNIEnv* /* env */, jobject /* obj */) {
    // TODO(b/31632518)
    mHal = IConsumerIr::getService();
    return mHal != nullptr;
}

static jint halTransmit(JNIEnv *env, jobject /* obj */, jint carrierFrequency,
   jintArray pattern) {
    ScopedIntArrayRO cPattern(env, pattern);
    if (cPattern.get() == NULL) {
        return -EINVAL;
    }
    hidl_vec<int32_t> patternVec;
    patternVec.setToExternal(const_cast<int32_t*>(cPattern.get()), cPattern.size());

    bool success = mHal->transmit(carrierFrequency, patternVec);
    return success ? 0 : -1;
}

static jintArray halGetCarrierFrequencies(JNIEnv *env, jobject /* obj */) {
    int len;
    hidl_vec<ConsumerIrFreqRange> ranges;
    bool success;

    auto cb = [&](bool s, hidl_vec<ConsumerIrFreqRange> vec) {
            ranges = vec;
            success = s;
    };
    mHal->getCarrierFreqs(cb);

    if (!success) {
        return NULL;
    }
    len = ranges.size();

    int i;
    ScopedIntArrayRW freqsOut(env, env->NewIntArray(len*2));
    jint *arr = freqsOut.get();
    if (arr == NULL) {
        return NULL;
    }
    for (i = 0; i < len; i++) {
        arr[i*2] = static_cast<jint>(ranges[i].min);
        arr[i*2+1] = static_cast<jint>(ranges[i].max);
    }

    return freqsOut.getJavaArray();
}

static const JNINativeMethod method_table[] = {
    { "halOpen", "()Z", (void *)halOpen },
    { "halTransmit", "(I[I)I", (void *)halTransmit },
    { "halGetCarrierFrequencies", "()[I", (void *)halGetCarrierFrequencies},
};

int register_android_server_ConsumerIrService(JNIEnv *env) {
    return jniRegisterNativeMethods(env, "com/android/server/ConsumerIrService",
            method_table, NELEM(method_table));
}

};
