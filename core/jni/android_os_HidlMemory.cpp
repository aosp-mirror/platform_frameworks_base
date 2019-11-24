/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include "android_os_HidlMemory.h"
#include "core_jni_helpers.h"
#include "android_os_NativeHandle.h"

#define PACKAGE_PATH    "android/os"
#define CLASS_NAME      "HidlMemory"
#define CLASS_PATH      PACKAGE_PATH "/" CLASS_NAME

namespace android {

namespace {

static struct {
    jclass clazz;
    jfieldID nativeContext;  // long
    jmethodID constructor;   // HidlMemory(String, long, NativeHandle)
    jmethodID getName;       // String HidlMemory.getName()
    jmethodID getSize;       // int HidlMemory.getSize()
    jmethodID getHandle;     // NativeHandle HidlMemory.getHandle()
} gFields;

std::string stringFromJava(JNIEnv* env, jstring jstr) {
    ScopedUtfChars s(env, jstr);
    return s.c_str();
}

jstring stringToJava(JNIEnv* env, const std::string& cstr) {
    return env->NewStringUTF(cstr.c_str());
}

static void nativeFinalize(JNIEnv* env, jobject jobj) {
    jlong jNativeContext = env->GetLongField(jobj, gFields.nativeContext);
    JHidlMemory* native = reinterpret_cast<JHidlMemory*>(jNativeContext);
    delete native;
}

static JNINativeMethod gMethods[] = {
        {"nativeFinalize", "()V", (void*) nativeFinalize},
};

}  // namespace

JHidlMemory::~JHidlMemory() {
    if (mObj) {
        // Must manually delete the underlying handle - hidl_memory doesn't own
        // it.
        native_handle_delete(const_cast<native_handle_t*>(mObj->handle()));
    }
}

/* static */ const hardware::hidl_memory* JHidlMemory::fromJava(JNIEnv* env,
                                                                jobject jobj) {
    // Try to get the result from cache.
    env->MonitorEnter(jobj);
    JHidlMemory* obj = getNativeContext(env, jobj);
    if (!obj->mObj) {
        // Create and cache.
        obj->mObj = javaToNative(env, jobj);
    }
    env->MonitorExit(jobj);
    return obj->mObj.get();
}

/* static */ jobject JHidlMemory::toJava(JNIEnv* env,
                                         const hardware::hidl_memory& cobj) {
    if (cobj.size() > std::numeric_limits<jlong>::max()) {
        return nullptr;
    }
    jstring jname = stringToJava(env, cobj.name());
    jlong jsize = static_cast<jlong>(cobj.size());
    jobject jhandle =
            JNativeHandle::MakeJavaNativeHandleObj(env, cobj.handle());

    // We're sharing the handle of cobj, so the Java instance doesn't own it.
    return env->NewObject(gFields.clazz,
                          gFields.constructor,
                          jname,
                          jsize,
                          jhandle,
                          false);
}

/* static */ std::unique_ptr<hardware::hidl_memory> JHidlMemory::javaToNative(
        JNIEnv* env,
        jobject jobj) {
    jstring jname =
            static_cast<jstring>(env->CallObjectMethod(jobj, gFields.getName));
    jlong jsize = env->CallLongMethod(jobj, gFields.getSize);
    jobject jhandle = env->CallObjectMethod(jobj, gFields.getHandle);

    if (jsize > std::numeric_limits<size_t>::max()) {
        return nullptr;
    }

    std::string cname = stringFromJava(env, jname);
    size_t csize = jsize;
    // We created the handle here, we're responsible to call
    // native_handle_delete() on it. However, we don't assume ownership of the
    // underlying fd, so we shouldn't call native_handle_close() on it.
    native_handle_t* chandle = JNativeHandle::MakeCppNativeHandle(env, jhandle,
                                                                  nullptr);
    // hidl_memory doesn't take ownership of the handle here, so won't delete
    // or close it.
    return std::make_unique<hardware::hidl_memory>(cname, chandle, csize);
}

/* static */ JHidlMemory* JHidlMemory::getNativeContext(JNIEnv* env,
                                                        jobject jobj) {
    env->MonitorEnter(jobj);
    jlong jNativeContext = env->GetLongField(jobj, gFields.nativeContext);
    JHidlMemory* native = reinterpret_cast<JHidlMemory*>(jNativeContext);
    if (!native) {
        native = new JHidlMemory();
        env->SetLongField(jobj,
                          gFields.nativeContext,
                          reinterpret_cast<jlong>(native));
    }
    env->MonitorExit(jobj);
    return native;
}

int register_android_os_HidlMemory(JNIEnv* env) {
    jclass clazz = FindClassOrDie(env, CLASS_PATH);
    gFields.clazz = MakeGlobalRefOrDie(env, clazz);

    gFields.nativeContext = GetFieldIDOrDie(env, clazz, "mNativeContext", "J");

    gFields.constructor = GetMethodIDOrDie(env,
                                           clazz,
                                           "<init>",
                                           "(Ljava/lang/String;JL" PACKAGE_PATH "/NativeHandle;)V");
    gFields.getName =
            GetMethodIDOrDie(env, clazz, "getName", "()Ljava/lang/String;");
    gFields.getSize = GetMethodIDOrDie(env, clazz, "getSize", "()J");
    gFields.getHandle = GetMethodIDOrDie(env,
                                         clazz,
                                         "getHandle",
                                         "()L" PACKAGE_PATH "/NativeHandle;");

    RegisterMethodsOrDie(env, CLASS_PATH, gMethods, NELEM(gMethods));

    return 0;
}

}  // namespace android

