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

//#define LOG_NDEBUG 0
#define LOG_TAG "JHwRemoteBinder"
#include <android-base/logging.h>

#include "android_os_HwRemoteBinder.h"

#include "android_os_HwParcel.h"

#include <JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>
#include <hidl/IServiceManager.h>
#include <hwbinder/Status.h>
#include <nativehelper/ScopedLocalRef.h>

#include "core_jni_helpers.h"

using android::AndroidRuntime;

#define PACKAGE_PATH    "android/os"
#define CLASS_NAME      "HwRemoteBinder"
#define CLASS_PATH      PACKAGE_PATH "/" CLASS_NAME

namespace android {

static struct fields_t {
    jfieldID contextID;
    jmethodID constructID;

} gFields;

// static
void JHwRemoteBinder::InitClass(JNIEnv *env) {
    ScopedLocalRef<jclass> clazz(env, FindClassOrDie(env, CLASS_PATH));

    gFields.contextID =
        GetFieldIDOrDie(env, clazz.get(), "mNativeContext", "J");

    gFields.constructID = GetMethodIDOrDie(env, clazz.get(), "<init>", "()V");
}

// static
sp<JHwRemoteBinder> JHwRemoteBinder::SetNativeContext(
        JNIEnv *env, jobject thiz, const sp<JHwRemoteBinder> &context) {
    sp<JHwRemoteBinder> old =
        (JHwRemoteBinder *)env->GetLongField(thiz, gFields.contextID);

    if (context != NULL) {
        context->incStrong(NULL /* id */);
    }

    if (old != NULL) {
        old->decStrong(NULL /* id */);
    }

    env->SetLongField(thiz, gFields.contextID, (long)context.get());

    return old;
}

// static
sp<JHwRemoteBinder> JHwRemoteBinder::GetNativeContext(
        JNIEnv *env, jobject thiz) {
    return (JHwRemoteBinder *)env->GetLongField(thiz, gFields.contextID);
}

// static
jobject JHwRemoteBinder::NewObject(
        JNIEnv *env, const sp<hardware::IBinder> &binder) {
    ScopedLocalRef<jclass> clazz(env, FindClassOrDie(env, CLASS_PATH));

    // XXX Have to look up the constructor here because otherwise that static
    // class initializer isn't called and gFields.constructID is undefined :(

    jmethodID constructID = GetMethodIDOrDie(env, clazz.get(), "<init>", "()V");

    jobject obj = env->NewObject(clazz.get(), constructID);
    JHwRemoteBinder::GetNativeContext(env, obj)->setBinder(binder);

    return obj;
}

JHwRemoteBinder::JHwRemoteBinder(
        JNIEnv *env, jobject thiz, const sp<hardware::IBinder> &binder)
    : mBinder(binder) {
    jclass clazz = env->GetObjectClass(thiz);
    CHECK(clazz != NULL);

    mClass = (jclass)env->NewGlobalRef(clazz);
    mObject = env->NewWeakGlobalRef(thiz);
}

JHwRemoteBinder::~JHwRemoteBinder() {
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    env->DeleteWeakGlobalRef(mObject);
    mObject = NULL;

    env->DeleteGlobalRef(mClass);
    mClass = NULL;
}

sp<hardware::IBinder> JHwRemoteBinder::getBinder() {
    return mBinder;
}

void JHwRemoteBinder::setBinder(const sp<hardware::IBinder> &binder) {
    mBinder = binder;
}

}  // namespace android

////////////////////////////////////////////////////////////////////////////////

using namespace android;

static void releaseNativeContext(void *nativeContext) {
    sp<JHwRemoteBinder> binder = (JHwRemoteBinder *)nativeContext;

    if (binder != NULL) {
        binder->decStrong(NULL /* id */);
    }
}

static jlong JHwRemoteBinder_native_init(JNIEnv *env) {
    JHwRemoteBinder::InitClass(env);

    return reinterpret_cast<jlong>(&releaseNativeContext);
}

static void JHwRemoteBinder_native_setup_empty(JNIEnv *env, jobject thiz) {
    sp<JHwRemoteBinder> context =
        new JHwRemoteBinder(env, thiz, NULL /* service */);

    JHwRemoteBinder::SetNativeContext(env, thiz, context);
}

static void JHwRemoteBinder_native_transact(
        JNIEnv *env,
        jobject thiz,
        jint code,
        jobject requestObj,
        jobject replyObj,
        jint flags) {
    sp<hardware::IBinder> binder =
        JHwRemoteBinder::GetNativeContext(env, thiz)->getBinder();

    if (requestObj == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", NULL);
        return;
    }

    const hardware::Parcel *request =
        JHwParcel::GetNativeContext(env, requestObj)->getParcel();

    hardware::Parcel *reply =
        JHwParcel::GetNativeContext(env, replyObj)->getParcel();

    status_t err = binder->transact(code, *request, reply, flags);
    signalExceptionForError(env, err);
}

static JNINativeMethod gMethods[] = {
    { "native_init", "()J", (void *)JHwRemoteBinder_native_init },

    { "native_setup_empty", "()V",
        (void *)JHwRemoteBinder_native_setup_empty },

    { "transact",
        "(IL" PACKAGE_PATH "/HwParcel;L" PACKAGE_PATH "/HwParcel;I)V",
        (void *)JHwRemoteBinder_native_transact },
};

namespace android {

int register_android_os_HwRemoteBinder(JNIEnv *env) {
    return RegisterMethodsOrDie(env, CLASS_PATH, gMethods, NELEM(gMethods));
}

}  // namespace android

