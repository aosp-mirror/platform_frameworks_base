/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "UidRangeTest.h"

using android::net::UidRange;

extern "C"
JNIEXPORT jbyteArray Java_android_net_UidRangeTest_readAndWriteNative(JNIEnv* env, jclass,
        jbyteArray inParcel) {
    const UidRange range = unmarshall(env, inParcel);
    return marshall(env, range);
}

extern "C"
JNIEXPORT jint Java_android_net_UidRangeTest_getStart(JNIEnv* env, jclass, jbyteArray inParcel) {
    const UidRange range = unmarshall(env, inParcel);
    return range.getStart();
}

extern "C"
JNIEXPORT jint Java_android_net_UidRangeTest_getStop(JNIEnv* env, jclass, jbyteArray inParcel) {
    const UidRange range = unmarshall(env, inParcel);
    return range.getStop();
}


/**
 * Reads exactly one UidRange from 'parcelData' assuming that it is a Parcel. Any bytes afterward
 * are ignored.
 */
UidRange unmarshall(JNIEnv* env, jbyteArray parcelData) {
    const int length = env->GetArrayLength(parcelData);

    std::unique_ptr<uint8_t> bytes(new uint8_t[length]);
    env->GetByteArrayRegion(parcelData, 0, length, reinterpret_cast<jbyte*>(bytes.get()));

    android::Parcel p;
    p.setData(bytes.get(), length);

    UidRange range;
    range.readFromParcel(&p);
    return range;
}

/**
 * Creates a Java byte[] array and writes the contents of 'range' to it as a Parcel containing
 * exactly one object.
 *
 * Every UidRange maps to a unique parcel object, so both 'marshall(e, unmarshall(e, x))' and
 * 'unmarshall(e, marshall(e, x))' should be fixed points.
 */
jbyteArray marshall(JNIEnv* env, const UidRange& range) {
    android::Parcel p;
    range.writeToParcel(&p);
    const int length = p.dataSize();

    jbyteArray parcelData = env->NewByteArray(length);
    env->SetByteArrayRegion(parcelData, 0, length, reinterpret_cast<const jbyte*>(p.data()));

    return parcelData;
}
