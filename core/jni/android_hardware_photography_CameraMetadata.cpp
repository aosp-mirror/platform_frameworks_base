/*
**
** Copyright 2013, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

// #define LOG_NDEBUG 0
#define LOG_TAG "CameraMetadata-JNI"
#include <utils/Log.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_os_Parcel.h"
#include "android_runtime/AndroidRuntime.h"

#include <camera/CameraMetadata.h>

// fully-qualified class name
#define CAMERA_METADATA_CLASS_NAME "android/hardware/photography/CameraMetadata"

using namespace android;

struct fields_t {
    jfieldID    metadata_ptr;
};

static fields_t fields;

extern "C" {

static void CameraMetadata_classInit(JNIEnv *env, jobject thiz);

// Less safe access to native pointer. Does NOT throw any Java exceptions if NULL.
static CameraMetadata* CameraMetadata_getPointerNoThrow(JNIEnv *env, jobject thiz) {

    if (thiz == NULL) {
        return NULL;
    }

    return reinterpret_cast<CameraMetadata*>(env->GetLongField(thiz, fields.metadata_ptr));
}

// Safe access to native pointer from object. Throws if not possible to access.
static CameraMetadata* CameraMetadata_getPointerThrow(JNIEnv *env, jobject thiz,
                                                 const char* argName = "this") {

    if (thiz == NULL) {
        ALOGV("%s: Throwing java.lang.NullPointerException for null reference",
              __FUNCTION__);
        jniThrowNullPointerException(env, argName);
        return NULL;
    }

    CameraMetadata* metadata = CameraMetadata_getPointerNoThrow(env, thiz);
    if (metadata == NULL) {
        ALOGV("%s: Throwing java.lang.IllegalStateException for closed object",
              __FUNCTION__);
        jniThrowException(env, "java/lang/IllegalStateException",
                            "Metadata object was already closed");
        return NULL;
    }

    return metadata;
}

static jlong CameraMetadata_allocate(JNIEnv *env, jobject thiz) {
    ALOGV("%s", __FUNCTION__);

    return reinterpret_cast<jlong>(new CameraMetadata());
}

static jboolean CameraMetadata_isEmpty(JNIEnv *env, jobject thiz) {
    ALOGV("%s", __FUNCTION__);

    CameraMetadata* metadata = CameraMetadata_getPointerThrow(env, thiz);

    if (metadata == NULL) {
        ALOGW("%s: Returning early due to exception being thrown",
               __FUNCTION__);
        return JNI_TRUE; // actually throws java exc.
    }

    jboolean empty = metadata->isEmpty();

    ALOGV("%s: Empty returned %d, entry count was %d",
          __FUNCTION__, empty, metadata->entryCount());

    return empty;
}

static jint CameraMetadata_getEntryCount(JNIEnv *env, jobject thiz) {
    ALOGV("%s", __FUNCTION__);

    CameraMetadata* metadata = CameraMetadata_getPointerThrow(env, thiz);

    if (metadata == NULL) return 0; // actually throws java exc.

    return metadata->entryCount();
}

// idempotent. calling more than once has no effect.
static void CameraMetadata_close(JNIEnv *env, jobject thiz) {
    ALOGV("%s", __FUNCTION__);

    CameraMetadata* metadata = CameraMetadata_getPointerNoThrow(env, thiz);

    if (metadata != NULL) {
        delete metadata;
        env->SetLongField(thiz, fields.metadata_ptr, 0);
    }

    LOG_ALWAYS_FATAL_IF(CameraMetadata_getPointerNoThrow(env, thiz) != NULL,
                        "Expected the native ptr to be 0 after #close");
}

static void CameraMetadata_swap(JNIEnv *env, jobject thiz, jobject other) {
    ALOGV("%s", __FUNCTION__);

    CameraMetadata* metadata = CameraMetadata_getPointerThrow(env, thiz);

    // order is important: we can't call another JNI method
    // if there is an exception pending
    if (metadata == NULL) return;

    CameraMetadata* otherMetadata = CameraMetadata_getPointerThrow(env, other, "other");

    if (otherMetadata == NULL) return;

    metadata->swap(*otherMetadata);
}

static void CameraMetadata_readFromParcel(JNIEnv *env, jobject thiz, jobject parcel) {
    ALOGV("%s", __FUNCTION__);
    CameraMetadata* metadata = CameraMetadata_getPointerThrow(env, thiz);
    if (metadata == NULL) {
        return;
    }

    Parcel* parcelNative = parcelForJavaObject(env, parcel);
    if (parcelNative == NULL) {
        jniThrowNullPointerException(env, "parcel");
        return;
    }

    status_t err;
    if ((err = metadata->readFromParcel(parcelNative)) != OK) {
        jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
                             "Failed to read from parcel (error code %d)", err);
        return;
    }
}

static void CameraMetadata_writeToParcel(JNIEnv *env, jobject thiz, jobject parcel) {
    ALOGV("%s", __FUNCTION__);
    CameraMetadata* metadata = CameraMetadata_getPointerThrow(env, thiz);
    if (metadata == NULL) {
        return;
    }

    Parcel* parcelNative = parcelForJavaObject(env, parcel);
    if (parcelNative == NULL) {
        jniThrowNullPointerException(env, "parcel");
        return;
    }

    status_t err;
    if ((err = metadata->writeToParcel(parcelNative)) != OK) {
        jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
                                  "Failed to write to parcel (error code %d)", err);
        return;
    }
}

} // extern "C"

//-------------------------------------------------

static JNINativeMethod gCameraMetadataMethods[] = {
  { "nativeClassInit",
    "()V",
    (void *)CameraMetadata_classInit },
  { "nativeAllocate",
    "()J",
    (void*)CameraMetadata_allocate },
  { "nativeIsEmpty",
    "()Z",
    (void*)CameraMetadata_isEmpty },
  { "nativeGetEntryCount",
    "()I",
    (void*)CameraMetadata_getEntryCount },
  { "nativeClose",
    "()V",
    (void*)CameraMetadata_close },
  { "nativeSwap",
    "(L" CAMERA_METADATA_CLASS_NAME ";)V",
    (void *)CameraMetadata_swap },
  { "nativeReadFromParcel",
    "(Landroid/os/Parcel;)V",
    (void *)CameraMetadata_readFromParcel },
  { "nativeWriteToParcel",
    "(Landroid/os/Parcel;)V",
    (void *)CameraMetadata_writeToParcel },
};

struct field {
    const char *class_name;
    const char *field_name;
    const char *field_type;
    jfieldID   *jfield;
};

static int find_fields(JNIEnv *env, field *fields, int count)
{
    for (int i = 0; i < count; i++) {
        field *f = &fields[i];
        jclass clazz = env->FindClass(f->class_name);
        if (clazz == NULL) {
            ALOGE("Can't find %s", f->class_name);
            return -1;
        }

        jfieldID field = env->GetFieldID(clazz, f->field_name, f->field_type);
        if (field == NULL) {
            ALOGE("Can't find %s.%s", f->class_name, f->field_name);
            return -1;
        }

        *(f->jfield) = field;
    }

    return 0;
}

// Get all the required offsets in java class and register native functions
int register_android_hardware_photography_CameraMetadata(JNIEnv *env)
{
    // Register native functions
    return AndroidRuntime::registerNativeMethods(env,
            CAMERA_METADATA_CLASS_NAME,
            gCameraMetadataMethods,
            NELEM(gCameraMetadataMethods));
}

extern "C" {
static void CameraMetadata_classInit(JNIEnv *env, jobject thiz) {
    // XX: Why do this separately instead of doing it in the register function?
    ALOGV("%s", __FUNCTION__);

    field fields_to_find[] = {
        { CAMERA_METADATA_CLASS_NAME, "mMetadataPtr", "J", &fields.metadata_ptr },
    };

    // Do this here instead of in register_native_methods,
    // since otherwise it will fail to find the fields.
    if (find_fields(env, fields_to_find, NELEM(fields_to_find)) < 0)
        return;

    jclass clazz = env->FindClass(CAMERA_METADATA_CLASS_NAME);
}
} // extern "C"
