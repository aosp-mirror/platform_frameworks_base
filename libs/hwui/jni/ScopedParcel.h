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
#include "SkData.h"

#ifdef __ANDROID__  // Layoutlib does not support parcel
#include <android-base/unique_fd.h>
#include <android/binder_parcel.h>
#include <android/binder_parcel_jni.h>
#include <android/binder_parcel_platform.h>
#include <cutils/ashmem.h>
#include <renderthread/RenderProxy.h>

class ScopedParcel {
public:
    explicit ScopedParcel(JNIEnv* env, jobject parcel) {
        mParcel = AParcel_fromJavaParcel(env, parcel);
    }

    ~ScopedParcel() { AParcel_delete(mParcel); }

    int32_t readInt32();

    uint32_t readUint32();

    float readFloat();

    void writeInt32(int32_t value) { AParcel_writeInt32(mParcel, value); }

    void writeUint32(uint32_t value) { AParcel_writeUint32(mParcel, value); }

    void writeFloat(float value) { AParcel_writeFloat(mParcel, value); }

    bool allowFds() const { return AParcel_getAllowFds(mParcel); }

    std::optional<sk_sp<SkData>> readData();

    void writeData(const std::optional<sk_sp<SkData>>& optData);

    AParcel* get() { return mParcel; }

private:
    AParcel* mParcel;
};

enum class BlobType : int32_t {
    IN_PLACE,
    ASHMEM,
};

#endif  // __ANDROID__ // Layoutlib does not support parcel