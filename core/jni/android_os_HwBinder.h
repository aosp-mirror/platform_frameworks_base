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

#ifndef _ANDROID_OS_HW_BINDER_H
#define _ANDROID_OS_HW_BINDER_H

#include <android-base/macros.h>
#include <hwbinder/Binder.h>
#include <jni.h>
#include <utils/RefBase.h>

namespace android {

struct JHwBinderHolder;

struct JHwBinder : public hardware::BHwBinder {
    static void InitClass(JNIEnv *env);

    static sp<JHwBinderHolder> SetNativeContext(
            JNIEnv *env, jobject thiz, const sp<JHwBinderHolder> &context);

    static sp<JHwBinder> GetNativeBinder(JNIEnv *env, jobject thiz);

    JHwBinder(JNIEnv *env, jobject thiz);

protected:
    virtual ~JHwBinder();

    virtual status_t onTransact(
            uint32_t code,
            const hardware::Parcel &data,
            hardware::Parcel *reply,
            uint32_t flags,
            TransactCallback callback);

private:
    jobject mObject;

    DISALLOW_COPY_AND_ASSIGN(JHwBinder);
};

int register_android_os_HwBinder(JNIEnv *env);

bool validateCanUseHwBinder(const sp<hardware::IBinder>& binder);

}  // namespace android

#endif  // _ANDROID_OS_HW_BINDER_H


