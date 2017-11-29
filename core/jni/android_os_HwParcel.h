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

#ifndef ANDROID_OS_HW_PARCEL_H
#define ANDROID_OS_HW_PARCEL_H

#include "hwbinder/EphemeralStorage.h"

#include <android-base/macros.h>
#include <hwbinder/IBinder.h>
#include <hwbinder/Parcel.h>
#include <jni.h>
#include <utils/RefBase.h>

namespace android {

struct JHwParcel : public RefBase {
    static void InitClass(JNIEnv *env);

    static sp<JHwParcel> SetNativeContext(
            JNIEnv *env, jobject thiz, const sp<JHwParcel> &context);

    static sp<JHwParcel> GetNativeContext(JNIEnv *env, jobject thiz);

    static jobject NewObject(JNIEnv *env);

    JHwParcel(JNIEnv *env, jobject thiz);

    void setParcel(hardware::Parcel *parcel, bool assumeOwnership);
    hardware::Parcel *getParcel();

    EphemeralStorage *getStorage();

    void setTransactCallback(::android::hardware::IBinder::TransactCallback cb);

    void send();
    bool wasSent() const;

protected:
    virtual ~JHwParcel();

private:
    hardware::Parcel *mParcel;
    bool mOwnsParcel;

    EphemeralStorage mStorage;

    ::android::hardware::IBinder::TransactCallback mTransactCallback;
    bool mWasSent;

    DISALLOW_COPY_AND_ASSIGN(JHwParcel);
};

void signalExceptionForError(JNIEnv *env, status_t err, bool canThrowRemoteException = false);
int register_android_os_HwParcel(JNIEnv *env);

}  // namespace android

#endif  // ANDROID_OS_HW_PARCEL_H
