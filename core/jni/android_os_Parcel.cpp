/*
 * Copyright (C) 2012 The Android Open Source Project
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

#define LOG_TAG "Parcel"
//#define LOG_NDEBUG 0

#include "android_os_Parcel.h"
#include "android_util_Binder.h"

#include <nativehelper/JNIHelp.h>

#include <fcntl.h>
#include <stdio.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <binder/IInterface.h>
#include <binder/IPCThreadState.h>
#include <cutils/atomic.h>
#include <utils/Log.h>
#include <utils/SystemClock.h>
#include <utils/List.h>
#include <utils/KeyedVector.h>
#include <binder/Parcel.h>
#include <binder/ProcessState.h>
#include <binder/IServiceManager.h>
#include <utils/threads.h>
#include <utils/String8.h>

#include <nativehelper/ScopedUtfChars.h>
#include <nativehelper/ScopedLocalRef.h>

#include <android_runtime/AndroidRuntime.h>

#include "core_jni_helpers.h"

//#undef ALOGV
//#define ALOGV(...) fprintf(stderr, __VA_ARGS__)

#define DEBUG_DEATH 0
#if DEBUG_DEATH
#define LOGDEATH ALOGD
#else
#define LOGDEATH ALOGV
#endif

namespace android {

static struct parcel_offsets_t
{
    jclass clazz;
    jfieldID mNativePtr;
    jmethodID obtain;
    jmethodID recycle;
} gParcelOffsets;

Parcel* parcelForJavaObject(JNIEnv* env, jobject obj)
{
    if (obj) {
        Parcel* p = (Parcel*)env->GetLongField(obj, gParcelOffsets.mNativePtr);
        if (p != NULL) {
            return p;
        }
        jniThrowException(env, "java/lang/IllegalStateException", "Parcel has been finalized!");
    }
    return NULL;
}

jobject createJavaParcelObject(JNIEnv* env)
{
    return env->CallStaticObjectMethod(gParcelOffsets.clazz, gParcelOffsets.obtain);
}

void recycleJavaParcelObject(JNIEnv* env, jobject parcelObj)
{
    env->CallVoidMethod(parcelObj, gParcelOffsets.recycle);
}

static jint android_os_Parcel_dataSize(jlong nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    return parcel ? parcel->dataSize() : 0;
}

static jint android_os_Parcel_dataAvail(jlong nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    return parcel ? parcel->dataAvail() : 0;
}

static jint android_os_Parcel_dataPosition(jlong nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    return parcel ? parcel->dataPosition() : 0;
}

static jint android_os_Parcel_dataCapacity(jlong nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    return parcel ? parcel->dataCapacity() : 0;
}

static jlong android_os_Parcel_setDataSize(JNIEnv* env, jclass clazz, jlong nativePtr, jint size)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        const status_t err = parcel->setDataSize(size);
        if (err != NO_ERROR) {
            signalExceptionForError(env, clazz, err);
        }
        return parcel->getOpenAshmemSize();
    }
    return 0;
}

static void android_os_Parcel_setDataPosition(jlong nativePtr, jint pos)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        parcel->setDataPosition(pos);
    }
}

static void android_os_Parcel_setDataCapacity(JNIEnv* env, jclass clazz, jlong nativePtr, jint size)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        const status_t err = parcel->setDataCapacity(size);
        if (err != NO_ERROR) {
            signalExceptionForError(env, clazz, err);
        }
    }
}

static jboolean android_os_Parcel_pushAllowFds(jlong nativePtr, jboolean allowFds)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    jboolean ret = JNI_TRUE;
    if (parcel != NULL) {
        ret = (jboolean)parcel->pushAllowFds(allowFds);
    }
    return ret;
}

static void android_os_Parcel_restoreAllowFds(jlong nativePtr, jboolean lastValue)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        parcel->restoreAllowFds((bool)lastValue);
    }
}

static void android_os_Parcel_writeByteArray(JNIEnv* env, jclass clazz, jlong nativePtr,
                                             jobject data, jint offset, jint length)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel == NULL) {
        return;
    }

    const status_t err = parcel->writeInt32(length);
    if (err != NO_ERROR) {
        signalExceptionForError(env, clazz, err);
        return;
    }

    void* dest = parcel->writeInplace(length);
    if (dest == NULL) {
        signalExceptionForError(env, clazz, NO_MEMORY);
        return;
    }

    jbyte* ar = (jbyte*)env->GetPrimitiveArrayCritical((jarray)data, 0);
    if (ar) {
        memcpy(dest, ar + offset, length);
        env->ReleasePrimitiveArrayCritical((jarray)data, ar, 0);
    }
}

static void android_os_Parcel_writeBlob(JNIEnv* env, jclass clazz, jlong nativePtr, jobject data,
                                        jint offset, jint length) {
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel == NULL) {
        return;
    }

    if (data == NULL) {
        const status_t err = parcel->writeInt32(-1);
        if (err != NO_ERROR) {
            signalExceptionForError(env, clazz, err);
        }
        return;
    }

    const status_t err = parcel->writeInt32(length);
    if (err != NO_ERROR) {
        signalExceptionForError(env, clazz, err);
        return;
    }

    android::Parcel::WritableBlob blob;
    android::status_t err2 = parcel->writeBlob(length, false, &blob);
    if (err2 != NO_ERROR) {
        signalExceptionForError(env, clazz, err2);
        return;
    }

    jbyte* ar = (jbyte*)env->GetPrimitiveArrayCritical((jarray)data, 0);
    if (ar == NULL) {
        memset(blob.data(), 0, length);
    } else {
        memcpy(blob.data(), ar + offset, length);
        env->ReleasePrimitiveArrayCritical((jarray)data, ar, 0);
    }

    blob.release();
}

static void android_os_Parcel_writeInt(JNIEnv* env, jclass clazz, jlong nativePtr, jint val) {
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        const status_t err = parcel->writeInt32(val);
        if (err != NO_ERROR) {
            signalExceptionForError(env, clazz, err);
        }
    }
}

static void android_os_Parcel_writeLong(JNIEnv* env, jclass clazz, jlong nativePtr, jlong val)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        const status_t err = parcel->writeInt64(val);
        if (err != NO_ERROR) {
            signalExceptionForError(env, clazz, err);
        }
    }
}

static void android_os_Parcel_writeFloat(JNIEnv* env, jclass clazz, jlong nativePtr, jfloat val)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        const status_t err = parcel->writeFloat(val);
        if (err != NO_ERROR) {
            signalExceptionForError(env, clazz, err);
        }
    }
}

static void android_os_Parcel_writeDouble(JNIEnv* env, jclass clazz, jlong nativePtr, jdouble val)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        const status_t err = parcel->writeDouble(val);
        if (err != NO_ERROR) {
            signalExceptionForError(env, clazz, err);
        }
    }
}

static void android_os_Parcel_writeString(JNIEnv* env, jclass clazz, jlong nativePtr, jstring val)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        status_t err = NO_MEMORY;
        if (val) {
            const jchar* str = env->GetStringCritical(val, 0);
            if (str) {
                err = parcel->writeString16(
                    reinterpret_cast<const char16_t*>(str),
                    env->GetStringLength(val));
                env->ReleaseStringCritical(val, str);
            }
        } else {
            err = parcel->writeString16(NULL, 0);
        }
        if (err != NO_ERROR) {
            signalExceptionForError(env, clazz, err);
        }
    }
}

static void android_os_Parcel_writeStrongBinder(JNIEnv* env, jclass clazz, jlong nativePtr, jobject object)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        const status_t err = parcel->writeStrongBinder(ibinderForJavaObject(env, object));
        if (err != NO_ERROR) {
            signalExceptionForError(env, clazz, err);
        }
    }
}

static jlong android_os_Parcel_writeFileDescriptor(JNIEnv* env, jclass clazz, jlong nativePtr, jobject object)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        const status_t err =
                parcel->writeDupFileDescriptor(jniGetFDFromFileDescriptor(env, object));
        if (err != NO_ERROR) {
            signalExceptionForError(env, clazz, err);
        }
        return parcel->getOpenAshmemSize();
    }
    return 0;
}

static jbyteArray android_os_Parcel_createByteArray(JNIEnv* env, jclass clazz, jlong nativePtr)
{
    jbyteArray ret = NULL;

    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        int32_t len = parcel->readInt32();

        // sanity check the stored length against the true data size
        if (len >= 0 && len <= (int32_t)parcel->dataAvail()) {
            ret = env->NewByteArray(len);

            if (ret != NULL) {
                jbyte* a2 = (jbyte*)env->GetPrimitiveArrayCritical(ret, 0);
                if (a2) {
                    const void* data = parcel->readInplace(len);
                    if (data) {
                        memcpy(a2, data, len);
                    }
                    env->ReleasePrimitiveArrayCritical(ret, a2, 0);
                    if (!data) {
                        ret = NULL;
                    }
                }
            }
        }
    }

    return ret;
}

static jboolean android_os_Parcel_readByteArray(JNIEnv* env, jclass clazz, jlong nativePtr,
                                                jobject dest, jint destLen)
{
    jboolean ret = JNI_FALSE;
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel == NULL) {
        return ret;
    }

    int32_t len = parcel->readInt32();
    if (len >= 0 && len <= (int32_t)parcel->dataAvail() && len == destLen) {
        jbyte* ar = (jbyte*)env->GetPrimitiveArrayCritical((jarray)dest, 0);
        if (ar) {
            const void* data = parcel->readInplace(len);
            if (data) {
                memcpy(ar, data, len);
                ret = JNI_TRUE;
            } else {
                ret = JNI_FALSE;
            }

            env->ReleasePrimitiveArrayCritical((jarray)dest, ar, 0);
        }
    }
    return ret;
}

static jbyteArray android_os_Parcel_readBlob(JNIEnv* env, jclass clazz, jlong nativePtr)
{
    jbyteArray ret = NULL;

    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        int32_t len = parcel->readInt32();
        if (len >= 0) {
            android::Parcel::ReadableBlob blob;
            android::status_t err = parcel->readBlob(len, &blob);
            if (err != NO_ERROR) {
                signalExceptionForError(env, clazz, err);
                return NULL;
            }

            ret = env->NewByteArray(len);
            if (ret != NULL) {
                jbyte* a2 = (jbyte*)env->GetPrimitiveArrayCritical(ret, 0);
                if (a2) {
                    memcpy(a2, blob.data(), len);
                    env->ReleasePrimitiveArrayCritical(ret, a2, 0);
                }
            }
            blob.release();
        }
    }

    return ret;
}

static jint android_os_Parcel_readInt(jlong nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        return parcel->readInt32();
    }
    return 0;
}

static jlong android_os_Parcel_readLong(jlong nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        return parcel->readInt64();
    }
    return 0;
}

static jfloat android_os_Parcel_readFloat(jlong nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        return parcel->readFloat();
    }
    return 0;
}

static jdouble android_os_Parcel_readDouble(jlong nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        return parcel->readDouble();
    }
    return 0;
}

static jstring android_os_Parcel_readString(JNIEnv* env, jclass clazz, jlong nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        size_t len;
        const char16_t* str = parcel->readString16Inplace(&len);
        if (str) {
            return env->NewString(reinterpret_cast<const jchar*>(str), len);
        }
        return NULL;
    }
    return NULL;
}

static jobject android_os_Parcel_readStrongBinder(JNIEnv* env, jclass clazz, jlong nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        return javaObjectForIBinder(env, parcel->readStrongBinder());
    }
    return NULL;
}

static jobject android_os_Parcel_readFileDescriptor(JNIEnv* env, jclass clazz, jlong nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        int fd = parcel->readFileDescriptor();
        if (fd < 0) return NULL;
        fd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
        if (fd < 0) return NULL;
        jobject jifd = jniCreateFileDescriptor(env, fd);
        if (jifd == NULL) {
            close(fd);
        }
        return jifd;
    }
    return NULL;
}

static jlong android_os_Parcel_create(JNIEnv* env, jclass clazz)
{
    Parcel* parcel = new Parcel();
    return reinterpret_cast<jlong>(parcel);
}

static jlong android_os_Parcel_freeBuffer(JNIEnv* env, jclass clazz, jlong nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        parcel->freeData();
        return parcel->getOpenAshmemSize();
    }
    return 0;
}

static void android_os_Parcel_destroy(JNIEnv* env, jclass clazz, jlong nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    delete parcel;
}

static jbyteArray android_os_Parcel_marshall(JNIEnv* env, jclass clazz, jlong nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel == NULL) {
       return NULL;
    }

    // do not marshall if there are binder objects in the parcel
    if (parcel->objectsCount())
    {
        jniThrowException(env, "java/lang/RuntimeException", "Tried to marshall a Parcel that contained Binder objects.");
        return NULL;
    }

    jbyteArray ret = env->NewByteArray(parcel->dataSize());

    if (ret != NULL)
    {
        jbyte* array = (jbyte*)env->GetPrimitiveArrayCritical(ret, 0);
        if (array != NULL)
        {
            memcpy(array, parcel->data(), parcel->dataSize());
            env->ReleasePrimitiveArrayCritical(ret, array, 0);
        }
    }

    return ret;
}

static jlong android_os_Parcel_unmarshall(JNIEnv* env, jclass clazz, jlong nativePtr,
                                          jbyteArray data, jint offset, jint length)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel == NULL || length < 0) {
       return 0;
    }

    jbyte* array = (jbyte*)env->GetPrimitiveArrayCritical(data, 0);
    if (array)
    {
        parcel->setDataSize(length);
        parcel->setDataPosition(0);

        void* raw = parcel->writeInplace(length);
        memcpy(raw, (array + offset), length);

        env->ReleasePrimitiveArrayCritical(data, array, 0);
    }
    return parcel->getOpenAshmemSize();
}

static jint android_os_Parcel_compareData(JNIEnv* env, jclass clazz, jlong thisNativePtr,
                                          jlong otherNativePtr)
{
    Parcel* thisParcel = reinterpret_cast<Parcel*>(thisNativePtr);
    if (thisParcel == NULL) {
       return 0;
    }
    Parcel* otherParcel = reinterpret_cast<Parcel*>(otherNativePtr);
    if (otherParcel == NULL) {
       return thisParcel->getOpenAshmemSize();
    }

    return thisParcel->compareData(*otherParcel);
}

static jlong android_os_Parcel_appendFrom(JNIEnv* env, jclass clazz, jlong thisNativePtr,
                                          jlong otherNativePtr, jint offset, jint length)
{
    Parcel* thisParcel = reinterpret_cast<Parcel*>(thisNativePtr);
    if (thisParcel == NULL) {
       return 0;
    }
    Parcel* otherParcel = reinterpret_cast<Parcel*>(otherNativePtr);
    if (otherParcel == NULL) {
       return thisParcel->getOpenAshmemSize();
    }

    status_t err = thisParcel->appendFrom(otherParcel, offset, length);
    if (err != NO_ERROR) {
        signalExceptionForError(env, clazz, err);
    }
    return thisParcel->getOpenAshmemSize();
}

static jboolean android_os_Parcel_hasFileDescriptors(jlong nativePtr)
{
    jboolean ret = JNI_FALSE;
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        if (parcel->hasFileDescriptors()) {
            ret = JNI_TRUE;
        }
    }
    return ret;
}

static void android_os_Parcel_writeInterfaceToken(JNIEnv* env, jclass clazz, jlong nativePtr,
                                                  jstring name)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        // In the current implementation, the token is just the serialized interface name that
        // the caller expects to be invoking
        const jchar* str = env->GetStringCritical(name, 0);
        if (str != NULL) {
            parcel->writeInterfaceToken(String16(
                  reinterpret_cast<const char16_t*>(str),
                  env->GetStringLength(name)));
            env->ReleaseStringCritical(name, str);
        }
    }
}

static void android_os_Parcel_enforceInterface(JNIEnv* env, jclass clazz, jlong nativePtr, jstring name)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        const jchar* str = env->GetStringCritical(name, 0);
        if (str) {
            IPCThreadState* threadState = IPCThreadState::self();
            const int32_t oldPolicy = threadState->getStrictModePolicy();
            const bool isValid = parcel->enforceInterface(
                reinterpret_cast<const char16_t*>(str),
                env->GetStringLength(name),
                threadState);
            env->ReleaseStringCritical(name, str);
            if (isValid) {
                const int32_t newPolicy = threadState->getStrictModePolicy();
                if (oldPolicy != newPolicy) {
                    // Need to keep the Java-level thread-local strict
                    // mode policy in sync for the libcore
                    // enforcements, which involves an upcall back
                    // into Java.  (We can't modify the
                    // Parcel.enforceInterface signature, as it's
                    // pseudo-public, and used via AIDL
                    // auto-generation...)
                    set_dalvik_blockguard_policy(env, newPolicy);
                }
                return;     // everything was correct -> return silently
            }
        }
    }

    // all error conditions wind up here
    jniThrowException(env, "java/lang/SecurityException",
            "Binder invocation to an incorrect interface");
}

static jlong android_os_Parcel_getGlobalAllocSize(JNIEnv* env, jclass clazz)
{
    return Parcel::getGlobalAllocSize();
}

static jlong android_os_Parcel_getGlobalAllocCount(JNIEnv* env, jclass clazz)
{
    return Parcel::getGlobalAllocCount();
}

static jlong android_os_Parcel_getBlobAshmemSize(jlong nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        return parcel->getBlobAshmemSize();
    }
    return 0;
}

static jint android_os_Parcel_readCallingWorkSourceUid(jlong nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        return parcel->readCallingWorkSourceUid();
    }
    return IPCThreadState::kUnsetWorkSource;
}

static jboolean android_os_Parcel_replaceCallingWorkSourceUid(jlong nativePtr, jint uid)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        return parcel->replaceCallingWorkSourceUid(uid);
    }
    return false;
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gParcelMethods[] = {
    // @CriticalNative
    {"nativeDataSize",            "(J)I", (void*)android_os_Parcel_dataSize},
    // @CriticalNative
    {"nativeDataAvail",           "(J)I", (void*)android_os_Parcel_dataAvail},
    // @CriticalNative
    {"nativeDataPosition",        "(J)I", (void*)android_os_Parcel_dataPosition},
    // @CriticalNative
    {"nativeDataCapacity",        "(J)I", (void*)android_os_Parcel_dataCapacity},
    // @FastNative
    {"nativeSetDataSize",         "(JI)J", (void*)android_os_Parcel_setDataSize},
    // @CriticalNative
    {"nativeSetDataPosition",     "(JI)V", (void*)android_os_Parcel_setDataPosition},
    // @FastNative
    {"nativeSetDataCapacity",     "(JI)V", (void*)android_os_Parcel_setDataCapacity},

    // @CriticalNative
    {"nativePushAllowFds",        "(JZ)Z", (void*)android_os_Parcel_pushAllowFds},
    // @CriticalNative
    {"nativeRestoreAllowFds",     "(JZ)V", (void*)android_os_Parcel_restoreAllowFds},

    {"nativeWriteByteArray",      "(J[BII)V", (void*)android_os_Parcel_writeByteArray},
    {"nativeWriteBlob",           "(J[BII)V", (void*)android_os_Parcel_writeBlob},
    // @FastNative
    {"nativeWriteInt",            "(JI)V", (void*)android_os_Parcel_writeInt},
    // @FastNative
    {"nativeWriteLong",           "(JJ)V", (void*)android_os_Parcel_writeLong},
    // @FastNative
    {"nativeWriteFloat",          "(JF)V", (void*)android_os_Parcel_writeFloat},
    // @FastNative
    {"nativeWriteDouble",         "(JD)V", (void*)android_os_Parcel_writeDouble},
    {"nativeWriteString",         "(JLjava/lang/String;)V", (void*)android_os_Parcel_writeString},
    {"nativeWriteStrongBinder",   "(JLandroid/os/IBinder;)V", (void*)android_os_Parcel_writeStrongBinder},
    {"nativeWriteFileDescriptor", "(JLjava/io/FileDescriptor;)J", (void*)android_os_Parcel_writeFileDescriptor},

    {"nativeCreateByteArray",     "(J)[B", (void*)android_os_Parcel_createByteArray},
    {"nativeReadByteArray",       "(J[BI)Z", (void*)android_os_Parcel_readByteArray},
    {"nativeReadBlob",            "(J)[B", (void*)android_os_Parcel_readBlob},
    // @CriticalNative
    {"nativeReadInt",             "(J)I", (void*)android_os_Parcel_readInt},
    // @CriticalNative
    {"nativeReadLong",            "(J)J", (void*)android_os_Parcel_readLong},
    // @CriticalNative
    {"nativeReadFloat",           "(J)F", (void*)android_os_Parcel_readFloat},
    // @CriticalNative
    {"nativeReadDouble",          "(J)D", (void*)android_os_Parcel_readDouble},
    {"nativeReadString",          "(J)Ljava/lang/String;", (void*)android_os_Parcel_readString},
    {"nativeReadStrongBinder",    "(J)Landroid/os/IBinder;", (void*)android_os_Parcel_readStrongBinder},
    {"nativeReadFileDescriptor",  "(J)Ljava/io/FileDescriptor;", (void*)android_os_Parcel_readFileDescriptor},

    {"nativeCreate",              "()J", (void*)android_os_Parcel_create},
    {"nativeFreeBuffer",          "(J)J", (void*)android_os_Parcel_freeBuffer},
    {"nativeDestroy",             "(J)V", (void*)android_os_Parcel_destroy},

    {"nativeMarshall",            "(J)[B", (void*)android_os_Parcel_marshall},
    {"nativeUnmarshall",          "(J[BII)J", (void*)android_os_Parcel_unmarshall},
    {"nativeCompareData",         "(JJ)I", (void*)android_os_Parcel_compareData},
    {"nativeAppendFrom",          "(JJII)J", (void*)android_os_Parcel_appendFrom},
    // @CriticalNative
    {"nativeHasFileDescriptors",  "(J)Z", (void*)android_os_Parcel_hasFileDescriptors},
    {"nativeWriteInterfaceToken", "(JLjava/lang/String;)V", (void*)android_os_Parcel_writeInterfaceToken},
    {"nativeEnforceInterface",    "(JLjava/lang/String;)V", (void*)android_os_Parcel_enforceInterface},

    {"getGlobalAllocSize",        "()J", (void*)android_os_Parcel_getGlobalAllocSize},
    {"getGlobalAllocCount",       "()J", (void*)android_os_Parcel_getGlobalAllocCount},

    // @CriticalNative
    {"nativeGetBlobAshmemSize",       "(J)J", (void*)android_os_Parcel_getBlobAshmemSize},

    // @CriticalNative
    {"nativeReadCallingWorkSourceUid", "(J)I", (void*)android_os_Parcel_readCallingWorkSourceUid},
    // @CriticalNative
    {"nativeReplaceCallingWorkSourceUid", "(JI)Z", (void*)android_os_Parcel_replaceCallingWorkSourceUid},
};

const char* const kParcelPathName = "android/os/Parcel";

int register_android_os_Parcel(JNIEnv* env)
{
    jclass clazz = FindClassOrDie(env, kParcelPathName);

    gParcelOffsets.clazz = MakeGlobalRefOrDie(env, clazz);
    gParcelOffsets.mNativePtr = GetFieldIDOrDie(env, clazz, "mNativePtr", "J");
    gParcelOffsets.obtain = GetStaticMethodIDOrDie(env, clazz, "obtain", "()Landroid/os/Parcel;");
    gParcelOffsets.recycle = GetMethodIDOrDie(env, clazz, "recycle", "()V");

    return RegisterMethodsOrDie(env, kParcelPathName, gParcelMethods, NELEM(gParcelMethods));
}

};
