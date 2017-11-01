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

#ifndef ANDROID_OS_HW_BLOB_H
#define ANDROID_OS_HW_BLOB_H

#include <android-base/macros.h>
#include <jni.h>
#include <hidl/HidlSupport.h>
#include <hwbinder/Parcel.h>
#include <utils/RefBase.h>
#include <utils/Vector.h>

namespace android {

struct JHwBlob : public RefBase {
    static void InitClass(JNIEnv *env);

    static sp<JHwBlob> SetNativeContext(
            JNIEnv *env, jobject thiz, const sp<JHwBlob> &context);

    static sp<JHwBlob> GetNativeContext(JNIEnv *env, jobject thiz);

    static jobject NewObject(JNIEnv *env, const void *ptr, size_t handle);
    static jobject NewObject(JNIEnv *env, size_t size);

    JHwBlob(JNIEnv *env, jobject thiz, size_t size);

    void setTo(const void *ptr, size_t handle);

    status_t getHandle(size_t *handle) const;

    status_t read(size_t offset, void *data, size_t size) const;
    status_t write(size_t offset, const void *data, size_t size);

    status_t getString(
            size_t offset, const android::hardware::hidl_string **s) const;

    const void *data() const;
    void *data();

    size_t size() const;

    status_t putBlob(size_t offset, const sp<JHwBlob> &blob);

    status_t writeToParcel(hardware::Parcel *parcel) const;

    status_t writeEmbeddedToParcel(
            hardware::Parcel *parcel,
            size_t parentHandle,
            size_t parentOffset) const;

protected:
    virtual ~JHwBlob();

private:
    struct BlobInfo {
        size_t mOffset;
        sp<JHwBlob> mBlob;
    };

    void *mBuffer;
    size_t mSize;
    bool mOwnsBuffer;

    size_t mHandle;

    Vector<BlobInfo> mSubBlobs;

    DISALLOW_COPY_AND_ASSIGN(JHwBlob);
};

int register_android_os_HwBlob(JNIEnv *env);

}  // namespace android

#endif  // ANDROID_OS_HW_BLOB_H

