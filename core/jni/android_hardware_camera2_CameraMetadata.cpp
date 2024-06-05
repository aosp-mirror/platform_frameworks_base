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
#include <memory>
#define LOG_TAG "CameraMetadata-JNI"
#include <utils/Errors.h>
#include <utils/Log.h>
#include <utils/RefBase.h>
#include <utils/Vector.h>
#include <utils/SortedVector.h>
#include <utils/KeyedVector.h>
#include <stdio.h>
#include <string.h>
#include <vector>

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include "android_os_Parcel.h"
#include "core_jni_helpers.h"
#include "android_runtime/android_hardware_camera2_CameraMetadata.h"

#include <android/hardware/ICameraService.h>
#include <binder/IServiceManager.h>
#include <camera/CameraMetadata.h>
#include <camera_metadata_hidden.h>
#include <camera/VendorTagDescriptor.h>
#include <nativehelper/ScopedUtfChars.h>
#include <nativehelper/ScopedPrimitiveArray.h>

#include <sys/types.h> // for socketpair
#include <sys/socket.h> // for socketpair

// fully-qualified class name
#define CAMERA_METADATA_CLASS_NAME "android/hardware/camera2/impl/CameraMetadataNative"
#define CHARACTERISTICS_KEY_CLASS_NAME "android/hardware/camera2/CameraCharacteristics$Key"
#define REQUEST_KEY_CLASS_NAME "android/hardware/camera2/CaptureRequest$Key"
#define RESULT_KEY_CLASS_NAME "android/hardware/camera2/CaptureResult$Key"

using namespace android;

static struct metadata_java_key_offsets_t {
    jclass mCharacteristicsKey;
    jclass mResultKey;
    jclass mRequestKey;
    jmethodID mCharacteristicsConstr;
    jmethodID mResultConstr;
    jmethodID mRequestConstr;
    jclass mByteArray;
    jclass mInt32Array;
    jclass mFloatArray;
    jclass mInt64Array;
    jclass mDoubleArray;
    jclass mRationalArray;
    jclass mArrayList;
    jmethodID mArrayListConstr;
    jmethodID mArrayListAdd;
} gMetadataOffsets;

struct fields_t {
    jfieldID    metadata_ptr;
};

static fields_t fields;

namespace android {

status_t CameraMetadata_getNativeMetadata(JNIEnv* env, jobject thiz,
        /*out*/CameraMetadata* metadata) {
    if (!thiz) {
        ALOGE("%s: Invalid java metadata object.", __FUNCTION__);
        return BAD_VALUE;
    }

    if (!metadata) {
        ALOGE("%s: Invalid output metadata object.", __FUNCTION__);
        return BAD_VALUE;
    }
    auto nativePtr = reinterpret_cast<std::shared_ptr<CameraMetadata> *>(
            env->GetLongField(thiz, fields.metadata_ptr));
    if (nativePtr == NULL) {
        ALOGE("%s: Invalid native pointer in java metadata object.", __FUNCTION__);
        return BAD_VALUE;
    }
    *metadata = *(nativePtr->get());
    return OK;
}

} /*namespace android*/

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
            ALOGE("%s: Expected dataBytes (%zu) to be divisible by typeSize "
                  "(%zu)", __FUNCTION__, dataBytes, typeSize);
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

static void CameraMetadata_setVendorId(JNIEnv* env, jclass thiz, jlong ptr,
        jlong vendorId);
static jobject CameraMetadata_getAllVendorKeys(JNIEnv* env, jclass thiz, jlong ptr,
        jclass keyType);
static jint CameraMetadata_getTagFromKey(JNIEnv *env, jclass thiz, jstring keyName,
        jlong vendorId);
static jint CameraMetadata_getTagFromKeyLocal(JNIEnv *env, jclass thiz, jlong ptr,
        jstring keyName);
static jint CameraMetadata_getTypeFromTag(JNIEnv *env, jclass thiz, jint tag, jlong vendorId);
static jint CameraMetadata_getTypeFromTagLocal(JNIEnv *env, jclass thiz, jlong ptr, jint tag);
static jint CameraMetadata_setupGlobalVendorTagDescriptor(JNIEnv *env, jclass thiz);

static std::shared_ptr<CameraMetadata>* CameraMetadata_getSharedPtr(jlong metadataLongPtr) {
    return reinterpret_cast<std::shared_ptr<CameraMetadata>* >(metadataLongPtr);
}

// Less safe access to native pointer. Does NOT throw any Java exceptions if NULL.
static CameraMetadata* CameraMetadata_getPointerNoThrow(jlong ptr) {
    auto metadata = CameraMetadata_getSharedPtr(ptr);
    if (metadata == nullptr) {
        return nullptr;
    }
    return metadata->get();
}

// Safe access to native pointer from object. Throws if not possible to access.
static CameraMetadata* CameraMetadata_getPointerThrow(JNIEnv *env, jlong ptr,
                                                 const char* argName = "this") {
    CameraMetadata* metadata = CameraMetadata_getPointerNoThrow(ptr);
    if (metadata == nullptr) {
        ALOGV("%s: Throwing java.lang.IllegalStateException for closed object",
              __FUNCTION__);
        jniThrowException(env, "java/lang/IllegalStateException",
                            "Metadata object was already closed");
        return nullptr;
    }

    return metadata;
}

static jlong CameraMetadata_allocate(JNIEnv *env, jclass thiz) {
    ALOGV("%s", __FUNCTION__);

    return reinterpret_cast<jlong>(new std::shared_ptr<CameraMetadata>(new CameraMetadata()));
}

static jlong CameraMetadata_allocateCopy(JNIEnv *env, jclass thiz, jlong other) {
    ALOGV("%s", __FUNCTION__);

    CameraMetadata* otherMetadata =
            CameraMetadata_getPointerThrow(env, other);
    // In case of exception, return
    if (otherMetadata == NULL) return NULL;

    // Clone native metadata and return new pointer.
    auto clonedMetadata = new CameraMetadata(*otherMetadata);
    return reinterpret_cast<jlong>(new std::shared_ptr<CameraMetadata>(clonedMetadata));
}


static jboolean CameraMetadata_isEmpty(JNIEnv *env, jclass thiz, jlong ptr) {
    ALOGV("%s", __FUNCTION__);

    CameraMetadata* metadata = CameraMetadata_getPointerThrow(env, ptr);

    if (metadata == NULL) {
        ALOGW("%s: Returning early due to exception being thrown",
               __FUNCTION__);
        return JNI_TRUE; // actually throws java exc.
    }

    jboolean empty = metadata->isEmpty();

    ALOGV("%s: Empty returned %d, entry count was %zu",
          __FUNCTION__, empty, metadata->entryCount());

    return empty;
}

static jint CameraMetadata_getEntryCount(JNIEnv *env, jclass thiz, jlong ptr) {
    ALOGV("%s", __FUNCTION__);

    CameraMetadata* metadata = CameraMetadata_getPointerThrow(env, ptr);

    if (metadata == NULL) return 0; // actually throws java exc.

    return metadata->entryCount();
}

static void CameraMetadata_update(JNIEnv *env, jclass thiz, jlong dst, jlong src) {
    ALOGV("%s", __FUNCTION__);

    CameraMetadata* metadataDst = CameraMetadata_getPointerThrow(env, dst);
    CameraMetadata* metadataSrc = CameraMetadata_getPointerThrow(env, src);

    if (((metadataDst == NULL) || (metadataDst->isEmpty())) ||
            ((metadataSrc == NULL) || metadataSrc->isEmpty())) {
        return;
    }

    auto metaBuffer = metadataSrc->getAndLock();
    camera_metadata_ro_entry_t entry;
    auto entryCount = get_camera_metadata_entry_count(metaBuffer);
    for (size_t i = 0; i < entryCount; i++) {
        auto stat = get_camera_metadata_ro_entry(metaBuffer, i, &entry);
        if (stat != NO_ERROR) {
            ALOGE("%s: Failed to retrieve source metadata!", __func__);
            metadataSrc->unlock(metaBuffer);
            return;
        }
        switch (entry.type) {
            case TYPE_BYTE:
                metadataDst->update(entry.tag, entry.data.u8, entry.count);
                break;
            case TYPE_INT32:
                metadataDst->update(entry.tag, entry.data.i32, entry.count);
                break;
            case TYPE_FLOAT:
                metadataDst->update(entry.tag, entry.data.f, entry.count);
                break;
            case TYPE_INT64:
                metadataDst->update(entry.tag, entry.data.i64, entry.count);
                break;
            case TYPE_DOUBLE:
                metadataDst->update(entry.tag, entry.data.d, entry.count);
                break;
            case TYPE_RATIONAL:
                metadataDst->update(entry.tag, entry.data.r, entry.count);
                break;
            default:
                ALOGE("%s: Unsupported tag type: %d!", __func__, entry.type);
        }
    }
    metadataSrc->unlock(metaBuffer);
}

static jlong CameraMetadata_getBufferSize(JNIEnv *env, jclass thiz, jlong ptr) {
    ALOGV("%s", __FUNCTION__);

    CameraMetadata* metadata = CameraMetadata_getPointerThrow(env, ptr);

    if (metadata == NULL) return 0;

    return metadata->bufferSize();
}

// idempotent. calling more than once has no effect.
static void CameraMetadata_close(JNIEnv *env, jclass thiz, jlong ptr) {
    ALOGV("%s", __FUNCTION__);

    auto metadata = CameraMetadata_getSharedPtr(ptr);
    if (metadata != nullptr) {
        delete metadata;
    }
}

static void CameraMetadata_swap(JNIEnv *env, jclass thiz, jlong ptr, jlong other) {
    ALOGV("%s", __FUNCTION__);

    auto metadata = CameraMetadata_getSharedPtr(ptr);
    auto otherMetadata = CameraMetadata_getSharedPtr(other);

    if (metadata == NULL) return;
    if (otherMetadata == NULL) return;

    // Need to swap shared pointers, not CameraMetadata, since the latter may be in use
    // by an NDK client, and we don't want to swap their data out from under them.
    metadata->swap(*otherMetadata);
}

static jbyteArray CameraMetadata_readValues(JNIEnv *env, jclass thiz, jint tag, jlong ptr) {
    ALOGV("%s (tag = %d)", __FUNCTION__, tag);

    CameraMetadata* metadata = CameraMetadata_getPointerThrow(env, ptr);
    if (metadata == NULL) return NULL;

    const camera_metadata_t *metaBuffer = metadata->getAndLock();
    int tagType = get_local_camera_metadata_tag_type(tag, metaBuffer);
    metadata->unlock(metaBuffer);
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

static void CameraMetadata_writeValues(JNIEnv *env, jclass thiz, jint tag, jbyteArray src,
        jlong ptr) {
    ALOGV("%s (tag = %d)", __FUNCTION__, tag);

    CameraMetadata* metadata = CameraMetadata_getPointerThrow(env, ptr);
    if (metadata == NULL) return;

    const camera_metadata_t *metaBuffer = metadata->getAndLock();
    int tagType = get_local_camera_metadata_tag_type(tag, metaBuffer);
    metadata->unlock(metaBuffer);
    if (tagType == -1) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                             "Tag (%d) did not have a type", tag);
        return;
    }

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

struct DumpMetadataParams {
    int writeFd;
    const CameraMetadata* metadata;
};

static void* CameraMetadata_writeMetadataThread(void* arg) {
    DumpMetadataParams* p = static_cast<DumpMetadataParams*>(arg);

    /*
     * Write the dumped data, and close the writing side FD.
     */
    p->metadata->dump(p->writeFd, /*verbosity*/2);

    if (close(p->writeFd) < 0) {
        ALOGE("%s: Failed to close writeFd (errno = %#x, message = '%s')",
                __FUNCTION__, errno, strerror(errno));
    }

    return NULL;
}

static void CameraMetadata_dump(JNIEnv *env, jclass thiz, jlong ptr) {
    ALOGV("%s", __FUNCTION__);
    CameraMetadata* metadata = CameraMetadata_getPointerThrow(env, ptr);
    if (metadata == NULL) {
        return;
    }

    /*
     * Create a socket pair for local streaming read/writes.
     *
     * The metadata will be dumped into the write side,
     * and then read back out (and logged) via the read side.
     */

    int writeFd, readFd;
    {

        int sv[2];
        if (socketpair(AF_LOCAL, SOCK_STREAM, /*protocol*/0, &sv[0]) < 0) {
            jniThrowExceptionFmt(env, "java/io/IOException",
                    "Failed to create socketpair (errno = %#x, message = '%s')",
                    errno, strerror(errno));
            return;
        }
        writeFd = sv[0];
        readFd = sv[1];
    }

    /*
     * Create a thread for doing the writing.
     *
     * The reading and writing must be concurrent, otherwise
     * the write will block forever once it exhausts the capped
     * buffer size (from getsockopt).
     */
    pthread_t writeThread;
    DumpMetadataParams params = {
        writeFd,
        metadata
    };

    {
        int threadRet = pthread_create(&writeThread, /*attr*/NULL,
                CameraMetadata_writeMetadataThread, (void*)&params);

        if (threadRet != 0) {
            close(writeFd);
            close(readFd);

            jniThrowExceptionFmt(env, "java/io/IOException",
                    "Failed to create thread for writing (errno = %#x, message = '%s')",
                    threadRet, strerror(threadRet));
            return;
        }
    }

    /*
     * Read out a byte until stream is complete. Write completed lines
     * to ALOG.
     */
    {
        char out[] = {'\0', '\0'}; // large enough to append as a string
        String8 logLine;

        // Read one byte at a time! Very slow but avoids complicated \n scanning.
        ssize_t res;
        while ((res = TEMP_FAILURE_RETRY(read(readFd, &out[0], /*count*/1))) > 0) {
            if (out[0] == '\n') {
                ALOGD("%s", logLine.c_str());
                logLine.clear();
            } else {
                logLine.append(out);
            }
        }

        if (res < 0) {
            jniThrowExceptionFmt(env, "java/io/IOException",
                    "Failed to read from fd (errno = %#x, message = '%s')",
                    errno, strerror(errno));
            //return;
        } else if (!logLine.empty()) {
            ALOGD("%s", logLine.c_str());
        }

        close(readFd);
    }

    int res;

    // Join until thread finishes. Ensures params/metadata is valid until then.
    if ((res = pthread_join(writeThread, /*retval*/NULL)) != 0) {
        ALOGE("%s: Failed to join thread (errno = %#x, message = '%s')",
                __FUNCTION__, res, strerror(res));
    }
}

static void CameraMetadata_readFromParcel(JNIEnv *env, jclass thiz, jobject parcel, jlong ptr) {
    ALOGV("%s", __FUNCTION__);
    CameraMetadata* metadata = CameraMetadata_getPointerThrow(env, ptr);
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

    // Update vendor descriptor cache if necessary
    auto vendorId = metadata->getVendorId();
    if ((vendorId != CAMERA_METADATA_INVALID_VENDOR_ID) &&
            !VendorTagDescriptorCache::isVendorCachePresent(vendorId)) {
        ALOGW("%s: Tag vendor id missing or cache not initialized, trying to update!",
                __FUNCTION__);
        CameraMetadata_setupGlobalVendorTagDescriptor(env, thiz);
    }
}

static void CameraMetadata_writeToParcel(JNIEnv *env, jclass thiz, jobject parcel, jlong ptr) {
    ALOGV("%s", __FUNCTION__);
    CameraMetadata* metadata = CameraMetadata_getPointerThrow(env, ptr);
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

static const JNINativeMethod gCameraMetadataMethods[] = {
// static methods
  { "nativeSetVendorId",
    "(JJ)V",
    (void *)CameraMetadata_setVendorId },
  { "nativeGetTagFromKey",
    "(Ljava/lang/String;J)I",
    (void *)CameraMetadata_getTagFromKey },
  { "nativeGetTypeFromTag",
    "(IJ)I",
    (void *)CameraMetadata_getTypeFromTag },
  { "nativeSetupGlobalVendorTagDescriptor",
    "()I",
    (void*)CameraMetadata_setupGlobalVendorTagDescriptor },
// instance methods
  { "nativeAllocate",
    "()J",
    (void*)CameraMetadata_allocate },
  { "nativeAllocateCopy",
    "(J)J",
    (void *)CameraMetadata_allocateCopy },
  { "nativeUpdate",
    "(JJ)V",
    (void*)CameraMetadata_update },
  { "nativeIsEmpty",
    "(J)Z",
    (void*)CameraMetadata_isEmpty },
  { "nativeGetEntryCount",
    "(J)I",
    (void*)CameraMetadata_getEntryCount },
  { "nativeGetBufferSize",
    "(J)J",
    (void*)CameraMetadata_getBufferSize },
  { "nativeClose",
    "(J)V",
    (void*)CameraMetadata_close },
  { "nativeSwap",
    "(JJ)V",
    (void *)CameraMetadata_swap },
  { "nativeGetTagFromKeyLocal",
    "(JLjava/lang/String;)I",
    (void *)CameraMetadata_getTagFromKeyLocal },
  { "nativeGetTypeFromTagLocal",
    "(JI)I",
    (void *)CameraMetadata_getTypeFromTagLocal },
  { "nativeReadValues",
    "(IJ)[B",
    (void *)CameraMetadata_readValues },
  { "nativeWriteValues",
    "(I[BJ)V",
    (void *)CameraMetadata_writeValues },
  { "nativeDump",
    "(J)V",
    (void *)CameraMetadata_dump },
  { "nativeGetAllVendorKeys",
    "(JLjava/lang/Class;)Ljava/util/ArrayList;",
    (void *)CameraMetadata_getAllVendorKeys},
// Parcelable interface
  { "nativeReadFromParcel",
    "(Landroid/os/Parcel;J)V",
    (void *)CameraMetadata_readFromParcel },
  { "nativeWriteToParcel",
    "(Landroid/os/Parcel;J)V",
    (void *)CameraMetadata_writeToParcel },
};

// Get all the required offsets in java class and register native functions
int register_android_hardware_camera2_CameraMetadata(JNIEnv *env)
{

    // Store global references to Key-related classes and methods used natively
    jclass characteristicsKeyClazz = FindClassOrDie(env, CHARACTERISTICS_KEY_CLASS_NAME);
    jclass requestKeyClazz = FindClassOrDie(env, REQUEST_KEY_CLASS_NAME);
    jclass resultKeyClazz = FindClassOrDie(env, RESULT_KEY_CLASS_NAME);
    gMetadataOffsets.mCharacteristicsKey = MakeGlobalRefOrDie(env, characteristicsKeyClazz);
    gMetadataOffsets.mRequestKey = MakeGlobalRefOrDie(env, requestKeyClazz);
    gMetadataOffsets.mResultKey = MakeGlobalRefOrDie(env, resultKeyClazz);
    gMetadataOffsets.mCharacteristicsConstr = GetMethodIDOrDie(env,
            gMetadataOffsets.mCharacteristicsKey, "<init>",
            "(Ljava/lang/String;Ljava/lang/Class;J)V");
    gMetadataOffsets.mRequestConstr = GetMethodIDOrDie(env,
            gMetadataOffsets.mRequestKey, "<init>", "(Ljava/lang/String;Ljava/lang/Class;J)V");
    gMetadataOffsets.mResultConstr = GetMethodIDOrDie(env,
            gMetadataOffsets.mResultKey, "<init>", "(Ljava/lang/String;Ljava/lang/Class;J)V");

    // Store global references for primitive array types used by Keys
    jclass byteClazz = FindClassOrDie(env, "[B");
    jclass int32Clazz = FindClassOrDie(env, "[I");
    jclass floatClazz = FindClassOrDie(env, "[F");
    jclass int64Clazz = FindClassOrDie(env, "[J");
    jclass doubleClazz = FindClassOrDie(env, "[D");
    jclass rationalClazz = FindClassOrDie(env, "[Landroid/util/Rational;");
    gMetadataOffsets.mByteArray = MakeGlobalRefOrDie(env, byteClazz);
    gMetadataOffsets.mInt32Array = MakeGlobalRefOrDie(env, int32Clazz);
    gMetadataOffsets.mFloatArray = MakeGlobalRefOrDie(env, floatClazz);
    gMetadataOffsets.mInt64Array = MakeGlobalRefOrDie(env, int64Clazz);
    gMetadataOffsets.mDoubleArray = MakeGlobalRefOrDie(env, doubleClazz);
    gMetadataOffsets.mRationalArray = MakeGlobalRefOrDie(env, rationalClazz);

    // Store global references for ArrayList methods used
    jclass arrayListClazz = FindClassOrDie(env, "java/util/ArrayList");
    gMetadataOffsets.mArrayList = MakeGlobalRefOrDie(env, arrayListClazz);
    gMetadataOffsets.mArrayListConstr = GetMethodIDOrDie(env, gMetadataOffsets.mArrayList,
            "<init>", "(I)V");
    gMetadataOffsets.mArrayListAdd = GetMethodIDOrDie(env, gMetadataOffsets.mArrayList,
            "add", "(Ljava/lang/Object;)Z");

    jclass cameraMetadataClazz = FindClassOrDie(env, CAMERA_METADATA_CLASS_NAME);
    fields.metadata_ptr = GetFieldIDOrDie(env, cameraMetadataClazz, "mMetadataPtr", "J");

    // Register native functions
    return RegisterMethodsOrDie(env,
            CAMERA_METADATA_CLASS_NAME,
            gCameraMetadataMethods,
            NELEM(gCameraMetadataMethods));
}

extern "C" {

static jint CameraMetadata_getTypeFromTagLocal(JNIEnv *env, jclass thiz, jlong ptr, jint tag) {
    CameraMetadata* metadata = CameraMetadata_getPointerNoThrow(ptr);
    metadata_vendor_id_t vendorId = CAMERA_METADATA_INVALID_VENDOR_ID;
    if (metadata) {
        vendorId = metadata->getVendorId();
    }

    int tagType = get_local_camera_metadata_tag_type_vendor_id(tag, vendorId);
    if (tagType == -1) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                             "Tag (%d) did not have a type", tag);
        return -1;
    }

    return tagType;
}

static jint CameraMetadata_getTagFromKeyLocal(JNIEnv *env, jclass thiz, jlong ptr,
        jstring keyName) {
    ScopedUtfChars keyScoped(env, keyName);
    const char *key = keyScoped.c_str();
    if (key == NULL) {
        // exception thrown by ScopedUtfChars
        return 0;
    }
    ALOGV("%s (key = '%s')", __FUNCTION__, key);

    uint32_t tag = 0;
    sp<VendorTagDescriptor> vTags;
    CameraMetadata* metadata = CameraMetadata_getPointerNoThrow(ptr);
    if (metadata) {
        sp<VendorTagDescriptorCache> cache = VendorTagDescriptorCache::getGlobalVendorTagCache();
        if (cache.get()) {
            auto vendorId = metadata->getVendorId();
            cache->getVendorTagDescriptor(vendorId, &vTags);
        }
    }

    status_t res = CameraMetadata::getTagFromName(key, vTags.get(), &tag);
    if (res != OK) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                             "Could not find tag for key '%s')", key);
    }
    return tag;
}

static jobject CameraMetadata_getAllVendorKeys(JNIEnv* env, jclass thiz, jlong ptr,
        jclass keyType) {
    metadata_vendor_id_t vendorId = CAMERA_METADATA_INVALID_VENDOR_ID;
    // Get all vendor tags
    sp<VendorTagDescriptor> vTags = VendorTagDescriptor::getGlobalVendorTagDescriptor();
    if (vTags.get() == nullptr) {
        sp<VendorTagDescriptorCache> cache = VendorTagDescriptorCache::getGlobalVendorTagCache();
        if (cache.get() == nullptr) {
            // No vendor tags.
            return nullptr;
        }

        CameraMetadata* metadata = CameraMetadata_getPointerThrow(env, ptr);
        if (metadata == NULL) return NULL;

        vendorId = metadata->getVendorId();
        cache->getVendorTagDescriptor(vendorId, &vTags);
        if (vTags.get() == nullptr) {
            return nullptr;
        }
    }

    int count = vTags->getTagCount();
    if (count <= 0) {
        // No vendor tags.
        return NULL;
    }

    std::vector<uint32_t> tagIds(count, /*initializer value*/0);
    vTags->getTagArray(&tagIds[0]);

    // Which key class/constructor should we use?
    jclass keyClazz;
    jmethodID keyConstr;
    if (env->IsSameObject(keyType, gMetadataOffsets.mCharacteristicsKey)) {
        keyClazz = gMetadataOffsets.mCharacteristicsKey;
        keyConstr = gMetadataOffsets.mCharacteristicsConstr;
    } else if (env->IsSameObject(keyType, gMetadataOffsets.mResultKey)) {
        keyClazz = gMetadataOffsets.mResultKey;
        keyConstr = gMetadataOffsets.mResultConstr;
    } else if (env->IsSameObject(keyType, gMetadataOffsets.mRequestKey)) {
        keyClazz = gMetadataOffsets.mRequestKey;
        keyConstr = gMetadataOffsets.mRequestConstr;
    } else {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                "Invalid key class given as argument.");
        return NULL;
    }

    // Allocate arrayList to return
    jobject arrayList = env->NewObject(gMetadataOffsets.mArrayList,
            gMetadataOffsets.mArrayListConstr, static_cast<jint>(count));
    if (env->ExceptionCheck()) {
        return NULL;
    }

    for (uint32_t id : tagIds) {
        const char* section = vTags->getSectionName(id);
        const char* tag = vTags->getTagName(id);
        int type = vTags->getTagType(id);

        size_t totalLen = strlen(section) + strlen(tag) + 2;
        std::vector<char> fullName(totalLen, 0);
        snprintf(&fullName[0], totalLen, "%s.%s", section, tag);

        jstring name = env->NewStringUTF(&fullName[0]);

        if (env->ExceptionCheck()) {
            return NULL;
        }

        jclass valueClazz;
        switch (type) {
            case TYPE_BYTE:
                valueClazz = gMetadataOffsets.mByteArray;
                break;
            case TYPE_INT32:
                valueClazz = gMetadataOffsets.mInt32Array;
                break;
            case TYPE_FLOAT:
                valueClazz = gMetadataOffsets.mFloatArray;
                break;
            case TYPE_INT64:
                valueClazz = gMetadataOffsets.mInt64Array;
                break;
            case TYPE_DOUBLE:
                valueClazz = gMetadataOffsets.mDoubleArray;
                break;
            case TYPE_RATIONAL:
                valueClazz = gMetadataOffsets.mRationalArray;
                break;
            default:
                jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
                        "Invalid type %d given for key %s", type, &fullName[0]);
                return NULL;
        }

        jobject key = env->NewObject(keyClazz, keyConstr, name, valueClazz, vendorId);
        if (env->ExceptionCheck()) {
            return NULL;
        }

        env->CallBooleanMethod(arrayList, gMetadataOffsets.mArrayListAdd, key);
        if (env->ExceptionCheck()) {
            return NULL;
        }

        env->DeleteLocalRef(name);
        env->DeleteLocalRef(key);
    }

    return arrayList;
}

static void CameraMetadata_setVendorId(JNIEnv *env, jclass thiz, jlong ptr,
        jlong vendorId) {
    ALOGV("%s", __FUNCTION__);

    CameraMetadata* metadata = CameraMetadata_getPointerThrow(env, ptr);

    if (metadata == NULL) {
        ALOGW("%s: Returning early due to exception being thrown",
               __FUNCTION__);
        return;
    }
    if (metadata->isEmpty()) {
        std::unique_ptr<CameraMetadata> emptyBuffer = std::make_unique<CameraMetadata>(10);
        metadata->swap(*emptyBuffer);
    }

    camera_metadata_t *meta = const_cast<camera_metadata_t *>(metadata->getAndLock());
    set_camera_metadata_vendor_id(meta, vendorId);
    metadata->unlock(meta);
}

static jint CameraMetadata_getTagFromKey(JNIEnv *env, jclass thiz, jstring keyName,
        jlong vendorId) {
    ScopedUtfChars keyScoped(env, keyName);
    const char *key = keyScoped.c_str();
    if (key == NULL) {
        // exception thrown by ScopedUtfChars
        return 0;
    }
    ALOGV("%s (key = '%s')", __FUNCTION__, key);

    uint32_t tag = 0;
    sp<VendorTagDescriptor> vTags =
            VendorTagDescriptor::getGlobalVendorTagDescriptor();
    if (vTags.get() == nullptr) {
        sp<VendorTagDescriptorCache> cache = VendorTagDescriptorCache::getGlobalVendorTagCache();
        if (cache.get() != nullptr) {
            cache->getVendorTagDescriptor(vendorId, &vTags);
        }
    }

    status_t res = CameraMetadata::getTagFromName(key, vTags.get(), &tag);
    if (res != OK) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                             "Could not find tag for key '%s')", key);
    }
    return tag;
}

static jint CameraMetadata_getTypeFromTag(JNIEnv *env, jclass thiz, jint tag, jlong vendorId) {
    int tagType = get_local_camera_metadata_tag_type_vendor_id(tag, vendorId);
    if (tagType == -1) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                             "Tag (%d) did not have a type", tag);
        return -1;
    }

    return tagType;
}

static jint CameraMetadata_setupGlobalVendorTagDescriptor(JNIEnv *env, jclass thiz) {
    const String16 NAME("media.camera");
    sp<hardware::ICameraService> cameraService;
    status_t err = getService(NAME, /*out*/&cameraService);

    if (err != OK) {
        ALOGE("%s: Failed to get camera service, received error %s (%d)", __FUNCTION__,
                strerror(-err), err);
        return hardware::ICameraService::ERROR_DISCONNECTED;
    }

    sp<VendorTagDescriptor> desc = new VendorTagDescriptor();
    binder::Status res = cameraService->getCameraVendorTagDescriptor(/*out*/desc.get());

    if (res.serviceSpecificErrorCode() == hardware::ICameraService::ERROR_DISCONNECTED) {
        // No camera module available, not an error on devices with no cameras
        VendorTagDescriptor::clearGlobalVendorTagDescriptor();
        return OK;
    } else if (!res.isOk()) {
        VendorTagDescriptor::clearGlobalVendorTagDescriptor();
        ALOGE("%s: Failed to setup vendor tag descriptors: %s", __FUNCTION__,
              res.toString8().c_str());
        return res.serviceSpecificErrorCode();
    }
    if (0 < desc->getTagCount()) {
        err = VendorTagDescriptor::setAsGlobalVendorTagDescriptor(desc);
    } else {
        sp<VendorTagDescriptorCache> cache = new VendorTagDescriptorCache();
        binder::Status res = cameraService->getCameraVendorTagCache(/*out*/cache.get());
        if (res.serviceSpecificErrorCode() == hardware::ICameraService::ERROR_DISCONNECTED) {
            // No camera module available, not an error on devices with no cameras
            VendorTagDescriptorCache::clearGlobalVendorTagCache();
            return OK;
        } else if (!res.isOk()) {
            VendorTagDescriptorCache::clearGlobalVendorTagCache();
            ALOGE("%s: Failed to setup vendor tag cache: %s", __FUNCTION__,
                  res.toString8().c_str());
            return res.serviceSpecificErrorCode();
        }

        err = VendorTagDescriptorCache::setAsGlobalVendorTagCache(cache);
    }

    if (err != OK) {
        return hardware::ICameraService::ERROR_INVALID_OPERATION;
    }
    return OK;
}

} // extern "C"
