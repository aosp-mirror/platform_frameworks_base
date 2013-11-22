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
// #define LOG_NNDEBUG 0
#define LOG_TAG "CameraMetadata-JNI"
#include <utils/Log.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_os_Parcel.h"
#include "android_runtime/AndroidRuntime.h"

#include <camera/CameraMetadata.h>
#include <nativehelper/ScopedUtfChars.h>
#include <nativehelper/ScopedPrimitiveArray.h>

#if defined(LOG_NNDEBUG)
#if !LOG_NNDEBUG
#define ALOGVV ALOGV
#endif
#else
#define ALOGVV(...)
#endif

// fully-qualified class name
#define CAMERA_METADATA_CLASS_NAME "android/hardware/camera2/impl/CameraMetadataNative"

using namespace android;

struct fields_t {
    jfieldID    metadata_ptr;
};

static fields_t fields;

namespace {
struct Helpers {
    static size_t getTypeSize(uint8_t type) {
        if (type >= NUM_TYPES) {
            ALOGE("%s: Invalid type specified (%ud)", __FUNCTION__, type);
            return static_cast<size_t>(-1);
        }

        return camera_metadata_type_size[type];
    }

    static status_t updateAny(CameraMetadata *metadata,
                          uint32_t tag,
                          uint32_t type,
                          const void *data,
                          size_t dataBytes) {

        if (type >= NUM_TYPES) {
            ALOGE("%s: Invalid type specified (%ud)", __FUNCTION__, type);
            return INVALID_OPERATION;
        }

        size_t typeSize = getTypeSize(type);

        if (dataBytes % typeSize != 0) {
            ALOGE("%s: Expected dataBytes (%ud) to be divisible by typeSize "
                  "(%ud)", __FUNCTION__, dataBytes, typeSize);
            return BAD_VALUE;
        }

        size_t dataCount = dataBytes / typeSize;

        switch(type) {
#define METADATA_UPDATE(runtime_type, compile_type)                            \
            case runtime_type: {                                               \
                const compile_type *dataPtr =                                  \
                        static_cast<const compile_type*>(data);                \
                return metadata->update(tag, dataPtr, dataCount);              \
            }                                                                  \

            METADATA_UPDATE(TYPE_BYTE,     uint8_t);
            METADATA_UPDATE(TYPE_INT32,    int32_t);
            METADATA_UPDATE(TYPE_FLOAT,    float);
            METADATA_UPDATE(TYPE_INT64,    int64_t);
            METADATA_UPDATE(TYPE_DOUBLE,   double);
            METADATA_UPDATE(TYPE_RATIONAL, camera_metadata_rational_t);

            default: {
                // unreachable
                ALOGE("%s: Unreachable", __FUNCTION__);
                return INVALID_OPERATION;
            }
        }

#undef METADATA_UPDATE
    }
};
} // namespace {}

extern "C" {

static void CameraMetadata_classInit(JNIEnv *env, jobject thiz);
static jint CameraMetadata_getTagFromKey(JNIEnv *env, jobject thiz, jstring keyName);
static jint CameraMetadata_getTypeFromTag(JNIEnv *env, jobject thiz, jint tag);

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

static jlong CameraMetadata_allocateCopy(JNIEnv *env, jobject thiz,
        jobject other) {
    ALOGV("%s", __FUNCTION__);

    CameraMetadata* otherMetadata =
            CameraMetadata_getPointerThrow(env, other, "other");

    // In case of exception, return
    if (otherMetadata == NULL) return NULL;

    // Clone native metadata and return new pointer
    return reinterpret_cast<jlong>(new CameraMetadata(*otherMetadata));
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

static jbyteArray CameraMetadata_readValues(JNIEnv *env, jobject thiz, jint tag) {
    ALOGV("%s (tag = %d)", __FUNCTION__, tag);

    CameraMetadata* metadata = CameraMetadata_getPointerThrow(env, thiz);
    if (metadata == NULL) return NULL;

    int tagType = get_camera_metadata_tag_type(tag);
    if (tagType == -1) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                             "Tag (%d) did not have a type", tag);
        return NULL;
    }
    size_t tagSize = Helpers::getTypeSize(tagType);

    camera_metadata_entry entry = metadata->find(tag);
    if (entry.count == 0) {
         if (!metadata->exists(tag)) {
             ALOGV("%s: Tag %d does not have any entries", __FUNCTION__, tag);
             return NULL;
         } else {
             // OK: we will return a 0-sized array.
             ALOGV("%s: Tag %d had an entry, but it had 0 data", __FUNCTION__,
                   tag);
         }
    }

    jsize byteCount = entry.count * tagSize;
    jbyteArray byteArray = env->NewByteArray(byteCount);
    if (env->ExceptionCheck()) return NULL;

    // Copy into java array from native array
    ScopedByteArrayRW arrayWriter(env, byteArray);
    memcpy(arrayWriter.get(), entry.data.u8, byteCount);

    return byteArray;
}

static void CameraMetadata_writeValues(JNIEnv *env, jobject thiz, jint tag, jbyteArray src) {
    ALOGV("%s (tag = %d)", __FUNCTION__, tag);

    CameraMetadata* metadata = CameraMetadata_getPointerThrow(env, thiz);
    if (metadata == NULL) return;

    int tagType = get_camera_metadata_tag_type(tag);
    if (tagType == -1) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                             "Tag (%d) did not have a type", tag);
        return;
    }
    size_t tagSize = Helpers::getTypeSize(tagType);

    status_t res;

    if (src == NULL) {
        // If array is NULL, delete the entry
        if (metadata->exists(tag)) {
            res = metadata->erase(tag);
            ALOGV("%s: Erase values (res = %d)", __FUNCTION__, res);
        } else {
            res = OK;
            ALOGV("%s: Don't need to erase", __FUNCTION__);
        }
    } else {
        // Copy from java array into native array
        ScopedByteArrayRO arrayReader(env, src);
        if (arrayReader.get() == NULL) return;

        res = Helpers::updateAny(metadata, static_cast<uint32_t>(tag),
                                 tagType, arrayReader.get(), arrayReader.size());

        ALOGV("%s: Update values (res = %d)", __FUNCTION__, res);
    }

    if (res == OK) {
        return;
    } else if (res == BAD_VALUE) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                             "Src byte array was poorly formed");
    } else if (res == INVALID_OPERATION) {
        jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
                             "Internal error while trying to update metadata");
    } else {
        jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
                             "Unknown error (%d) while trying to update "
                            "metadata", res);
    }
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
// static methods
  { "nativeClassInit",
    "()V",
    (void *)CameraMetadata_classInit },
  { "nativeGetTagFromKey",
    "(Ljava/lang/String;)I",
    (void *)CameraMetadata_getTagFromKey },
  { "nativeGetTypeFromTag",
    "(I)I",
    (void *)CameraMetadata_getTypeFromTag },
// instance methods
  { "nativeAllocate",
    "()J",
    (void*)CameraMetadata_allocate },
  { "nativeAllocateCopy",
    "(L" CAMERA_METADATA_CLASS_NAME ";)J",
    (void *)CameraMetadata_allocateCopy },
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
  { "nativeReadValues",
    "(I)[B",
    (void *)CameraMetadata_readValues },
  { "nativeWriteValues",
    "(I[B)V",
    (void *)CameraMetadata_writeValues },
// Parcelable interface
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
int register_android_hardware_camera2_CameraMetadata(JNIEnv *env)
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

static jint CameraMetadata_getTagFromKey(JNIEnv *env, jobject thiz, jstring keyName) {

    ScopedUtfChars keyScoped(env, keyName);
    const char *key = keyScoped.c_str();
    if (key == NULL) {
        // exception thrown by ScopedUtfChars
        return 0;
    }
    size_t keyLength = strlen(key);

    ALOGV("%s (key = '%s')", __FUNCTION__, key);

    // First, find the section by the longest string match
    const char *section = NULL;
    size_t sectionIndex = 0;
    size_t sectionLength = 0;
    for (size_t i = 0; i < ANDROID_SECTION_COUNT; ++i) {
        const char *str = camera_metadata_section_names[i];
        ALOGVV("%s: Trying to match against section '%s'",
               __FUNCTION__, str);
        if (strstr(key, str) == key) { // key begins with the section name
            size_t strLength = strlen(str);

            ALOGVV("%s: Key begins with section name", __FUNCTION__);

            // section name is the longest we've found so far
            if (section == NULL || sectionLength < strLength) {
                section = str;
                sectionIndex = i;
                sectionLength = strLength;

                ALOGVV("%s: Found new best section (idx %d)", __FUNCTION__, sectionIndex);
            }
        }
    }

    // TODO: vendor ext
    // TODO: Make above get_camera_metadata_section_from_name ?

    if (section == NULL) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                             "Could not find section name for key '%s')", key);
        return 0;
    } else {
        ALOGV("%s: Found matched section '%s' (%d)",
              __FUNCTION__, section, sectionIndex);
    }

    // Get the tag name component of the key
    const char *keyTagName = key + sectionLength + 1; // x.y.z -> z
    if (sectionLength + 1 >= keyLength) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                             "Key length too short for key '%s')", key);
    }

    // Match rest of name against the tag names in that section only
    uint32_t tagBegin, tagEnd; // [tagBegin, tagEnd)
    tagBegin = camera_metadata_section_bounds[sectionIndex][0];
    tagEnd = camera_metadata_section_bounds[sectionIndex][1];

    uint32_t tag;
    for (tag = tagBegin; tag < tagEnd; ++tag) {
        const char *tagName = get_camera_metadata_tag_name(tag);

        if (strcmp(keyTagName, tagName) == 0) {
            ALOGV("%s: Found matched tag '%s' (%d)",
                  __FUNCTION__, tagName, tag);
            break;
        }
    }

    // TODO: vendor ext
    // TODO: Make above get_camera_metadata_tag_from_name ?

    if (tag == tagEnd) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                             "Could not find tag name for key '%s')", key);
        return 0;
    }

    return tag;
}

static jint CameraMetadata_getTypeFromTag(JNIEnv *env, jobject thiz, jint tag) {
    int tagType = get_camera_metadata_tag_type(tag);
    if (tagType == -1) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                             "Tag (%d) did not have a type", tag);
        return -1;
    }

    return tagType;
}

} // extern "C"
