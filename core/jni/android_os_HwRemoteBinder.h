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

#ifndef ANDROID_OS_HW_REMOTE_BINDER_H
#define ANDROID_OS_HW_REMOTE_BINDER_H

#include <android-base/macros.h>
#include <hwbinder/Binder.h>
#include <jni.h>
#include <utils/List.h>
#include <utils/Mutex.h>
#include <utils/RefBase.h>

namespace android {

// Per-IBinder death recipient bookkeeping.  This is how we reconcile local jobject
// death recipient references passed in through JNI with the permanent corresponding
// HwBinderDeathRecipient objects.

class HwBinderDeathRecipient;

class HwBinderDeathRecipientList : public RefBase {
    List< sp<HwBinderDeathRecipient> > mList;
    Mutex mLock;

public:
    HwBinderDeathRecipientList();
    ~HwBinderDeathRecipientList();

    void add(const sp<HwBinderDeathRecipient>& recipient);
    void remove(const sp<HwBinderDeathRecipient>& recipient);
    sp<HwBinderDeathRecipient> find(jobject recipient);

    Mutex& lock();  // Use with care; specifically for mutual exclusion during binder death
};

struct JHwRemoteBinder : public RefBase {
    static void InitClass(JNIEnv *env);

    static sp<JHwRemoteBinder> SetNativeContext(
            JNIEnv *env, jobject thiz, const sp<JHwRemoteBinder> &context);

    static sp<JHwRemoteBinder> GetNativeContext(JNIEnv *env, jobject thiz);

    static jobject NewObject(JNIEnv *env, const sp<hardware::IBinder> &binder);

    JHwRemoteBinder(
            JNIEnv *env, jobject thiz, const sp<hardware::IBinder> &binder);

    sp<hardware::IBinder> getBinder() const;
    void setBinder(const sp<hardware::IBinder> &binder);
    sp<HwBinderDeathRecipientList> getDeathRecipientList() const;

protected:
    virtual ~JHwRemoteBinder();

private:
    jobject mObject;

    sp<hardware::IBinder> mBinder;
    sp<HwBinderDeathRecipientList> mDeathRecipientList;
    DISALLOW_COPY_AND_ASSIGN(JHwRemoteBinder);
};

int register_android_os_HwRemoteBinder(JNIEnv *env);

}  // namespace android

#endif  // ANDROID_OS_HW_REMOTE_BINDER_H

