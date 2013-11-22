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
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

#include <stdlib.h>
#include <utils/misc.h>
#include <utils/Log.h>
#include <hardware/hardware.h>
#include <hardware/consumerir.h>
#include <ScopedPrimitiveArray.h>

namespace android {

static jint halOpen(JNIEnv *env, jobject obj) {
    hw_module_t const* module;
    consumerir_device_t *dev;
    int err;

    err = hw_get_module(CONSUMERIR_HARDWARE_MODULE_ID, &module);
    if (err != 0) {
        ALOGE("Can't open consumer IR HW Module, error: %d", err);
        return 0;
    }

    err = module->methods->open(module, CONSUMERIR_TRANSMITTER,
            (hw_device_t **) &dev);
    if (err < 0) {
        ALOGE("Can't open consumer IR transmitter, error: %d", err);
        return 0;
    }

    return reinterpret_cast<jint>(dev);
}

static jint halTransmit(JNIEnv *env, jobject obj, jint halObject,
   jint carrierFrequency, jintArray pattern) {
    int ret;

    consumerir_device_t *dev = reinterpret_cast<consumerir_device_t*>(halObject);
    ScopedIntArrayRO cPattern(env, pattern);
    if (cPattern.get() == NULL) {
        return -EINVAL;
    }
    jsize patternLength = cPattern.size();

    ret = dev->transmit(dev, carrierFrequency, cPattern.get(), patternLength);

    return reinterpret_cast<jint>(ret);
}

static jintArray halGetCarrierFrequencies(JNIEnv *env, jobject obj,
    jint halObject) {
    consumerir_device_t *dev = (consumerir_device_t *) halObject;
    consumerir_freq_range_t *ranges;
    int len;

    len = dev->get_num_carrier_freqs(dev);
    if (len <= 0)
        return NULL;

    ranges = new consumerir_freq_range_t[len];

    len = dev->get_carrier_freqs(dev, len, ranges);
    if (len <= 0) {
        delete[] ranges;
        return NULL;
    }

    int i;
    ScopedIntArrayRW freqsOut(env, env->NewIntArray(len*2));
    jint *arr = freqsOut.get();
    if (arr == NULL) {
        delete[] ranges;
        return NULL;
    }
    for (i = 0; i < len; i++) {
        arr[i*2] = ranges[i].min;
        arr[i*2+1] = ranges[i].max;
    }

    delete[] ranges;
    return freqsOut.getJavaArray();
}

static JNINativeMethod method_table[] = {
    { "halOpen", "()I", (void *)halOpen },
    { "halTransmit", "(II[I)I", (void *)halTransmit },
    { "halGetCarrierFrequencies", "(I)[I", (void *)halGetCarrierFrequencies},
};

int register_android_server_ConsumerIrService(JNIEnv *env) {
    return jniRegisterNativeMethods(env, "com/android/server/ConsumerIrService",
            method_table, NELEM(method_table));
}

};
