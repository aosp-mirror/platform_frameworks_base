/*
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

#include <memory>

#include <binder/Parcel.h>

#include "LowpanCredentialTest.h"

using android::net::lowpan::LowpanCredential;

/**
 * Reads exactly one LowpanCredential from 'parcelData' assuming that it is a Parcel. Any bytes afterward
 * are ignored.
 */
static LowpanCredential unmarshall(JNIEnv* env, jbyteArray parcelData) {
    const int length = env->GetArrayLength(parcelData);

    std::unique_ptr<uint8_t> bytes(new uint8_t[length]);
    env->GetByteArrayRegion(parcelData, 0, length, reinterpret_cast<jbyte*>(bytes.get()));

    android::Parcel p;
    p.setData(bytes.get(), length);

    LowpanCredential value;
    value.readFromParcel(&p);
    return value;
}

/**
 * Creates a Java byte[] array and writes the contents of 'addr' to it as a Parcel containing
 * exactly one object.
 *
 * Every LowpanCredential maps to a unique parcel object, so both 'marshall(e, unmarshall(e, x))' and
 * 'unmarshall(e, marshall(e, x))' should be fixed points.
 */
static jbyteArray marshall(JNIEnv* env, const LowpanCredential& addr) {
    android::Parcel p;
    addr.writeToParcel(&p);
    const int length = p.dataSize();

    jbyteArray parcelData = env->NewByteArray(length);
    env->SetByteArrayRegion(parcelData, 0, length, reinterpret_cast<const jbyte*>(p.data()));

    return parcelData;
}

extern "C"
JNIEXPORT jbyteArray Java_android_net_lowpan_LowpanCredentialTest_readAndWriteNative(JNIEnv* env, jclass,
        jbyteArray inParcel) {
    const LowpanCredential value = unmarshall(env, inParcel);
    return marshall(env, value);
}
