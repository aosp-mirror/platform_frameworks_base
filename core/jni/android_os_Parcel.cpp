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

#include "JNIHelp.h"

#include <fcntl.h>
#include <stdio.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <utils/Atomic.h>
#include <binder/IInterface.h>
#include <binder/IPCThreadState.h>
#include <utils/Log.h>
#include <utils/SystemClock.h>
#include <utils/List.h>
#include <utils/KeyedVector.h>
#include <cutils/logger.h>
#include <binder/Parcel.h>
#include <binder/ProcessState.h>
#include <binder/IServiceManager.h>
#include <utils/threads.h>
#include <utils/String8.h>

#include <ScopedUtfChars.h>
#include <ScopedLocalRef.h>

#include <android_runtime/AndroidRuntime.h>

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
        Parcel* p = (Parcel*)env->GetIntField(obj, gParcelOffsets.mNativePtr);
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

static jint android_os_Parcel_dataSize(JNIEnv* env, jclass clazz, jint nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    return parcel ? parcel->dataSize() : 0;
}

static jint android_os_Parcel_dataAvail(JNIEnv* env, jclass clazz, jint nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    return parcel ? parcel->dataAvail() : 0;
}

static jint android_os_Parcel_dataPosition(JNIEnv* env, jclass clazz, jint nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    return parcel ? parcel->dataPosition() : 0;
}

static jint android_os_Parcel_dataCapacity(JNIEnv* env, jclass clazz, jint nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    return parcel ? parcel->dataCapacity() : 0;
}

static void android_os_Parcel_setDataSize(JNIEnv* env, jclass clazz, jint nativePtr, jint size)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        const status_t err = parcel->setDataSize(size);
        if (err != NO_ERROR) {
            signalExceptionForError(env, clazz, err);
        }
    }
}

static void android_os_Parcel_setDataPosition(JNIEnv* env, jclass clazz, jint nativePtr, jint pos)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        parcel->setDataPosition(pos);
    }
}

static void android_os_Parcel_setDataCapacity(JNIEnv* env, jclass clazz, jint nativePtr, jint size)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        const status_t err = parcel->setDataCapacity(size);
        if (err != NO_ERROR) {
            signalExceptionForError(env, clazz, err);
        }
    }
}

static jboolean android_os_Parcel_pushAllowFds(JNIEnv* env, jclass clazz, jint nativePtr, jboolean allowFds)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    jboolean ret = JNI_TRUE;
    if (parcel != NULL) {
        ret = (jboolean)parcel->pushAllowFds(allowFds);
    }
    return ret;
}

static void android_os_Parcel_restoreAllowFds(JNIEnv* env, jclass clazz, jint nativePtr, jboolean lastValue)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        parcel->restoreAllowFds((bool)lastValue);
    }
}

static void android_os_Parcel_writeNative(JNIEnv* env, jclass clazz, jint nativePtr, jobject data,
                                          jint offset, jint length)
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

static void android_os_Parcel_writeInt(JNIEnv* env, jclass clazz, jint nativePtr, jint val) {
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    const status_t err = parcel->writeInt32(val);
    if (err != NO_ERROR) {
        signalExceptionForError(env, clazz, err);
    }
}

static void android_os_Parcel_writeLong(JNIEnv* env, jclass clazz, jint nativePtr, jlong val)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        const status_t err = parcel->writeInt64(val);
        if (err != NO_ERROR) {
            signalExceptionForError(env, clazz, err);
        }
    }
}

static void android_os_Parcel_writeFloat(JNIEnv* env, jclass clazz, jint nativePtr, jfloat val)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        const status_t err = parcel->writeFloat(val);
        if (err != NO_ERROR) {
            signalExceptionForError(env, clazz, err);
        }
    }
}

static void android_os_Parcel_writeDouble(JNIEnv* env, jclass clazz, jint nativePtr, jdouble val)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        const status_t err = parcel->writeDouble(val);
        if (err != NO_ERROR) {
            signalExceptionForError(env, clazz, err);
        }
    }
}

static void android_os_Parcel_writeString(JNIEnv* env, jclass clazz, jint nativePtr, jstring val)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        status_t err = NO_MEMORY;
        if (val) {
            const jchar* str = env->GetStringCritical(val, 0);
            if (str) {
                err = parcel->writeString16(str, env->GetStringLength(val));
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

static void android_os_Parcel_writeStrongBinder(JNIEnv* env, jclass clazz, jint nativePtr, jobject object)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        const status_t err = parcel->writeStrongBinder(ibinderForJavaObject(env, object));
        if (err != NO_ERROR) {
            signalExceptionForError(env, clazz, err);
        }
    }
}

static void android_os_Parcel_writeFileDescriptor(JNIEnv* env, jclass clazz, jint nativePtr, jobject object)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        const status_t err =
                parcel->writeDupFileDescriptor(jniGetFDFromFileDescriptor(env, object));
        if (err != NO_ERROR) {
            signalExceptionForError(env, clazz, err);
        }
    }
}

static jbyteArray android_os_Parcel_createByteArray(JNIEnv* env, jclass clazz, jint nativePtr)
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
                    memcpy(a2, data, len);
                    env->ReleasePrimitiveArrayCritical(ret, a2, 0);
                }
            }
        }
    }

    return ret;
}

static jint android_os_Parcel_readInt(JNIEnv* env, jclass clazz, jint nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        return parcel->readInt32();
    }
    return 0;
}

static jlong android_os_Parcel_readLong(JNIEnv* env, jclass clazz, jint nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        return parcel->readInt64();
    }
    return 0;
}

static jfloat android_os_Parcel_readFloat(JNIEnv* env, jclass clazz, jint nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        return parcel->readFloat();
    }
    return 0;
}

static jdouble android_os_Parcel_readDouble(JNIEnv* env, jclass clazz, jint nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        return parcel->readDouble();
    }
    return 0;
}

static jstring android_os_Parcel_readString(JNIEnv* env, jclass clazz, jint nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        size_t len;
        const char16_t* str = parcel->readString16Inplace(&len);
        if (str) {
            return env->NewString(str, len);
        }
        return NULL;
    }
    return NULL;
}

static jobject android_os_Parcel_readStrongBinder(JNIEnv* env, jclass clazz, jint nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        return javaObjectForIBinder(env, parcel->readStrongBinder());
    }
    return NULL;
}

static jobject android_os_Parcel_readFileDescriptor(JNIEnv* env, jclass clazz, jint nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        int fd = parcel->readFileDescriptor();
        if (fd < 0) return NULL;
        fd = dup(fd);
        if (fd < 0) return NULL;
        return jniCreateFileDescriptor(env, fd);
    }
    return NULL;
}

static jobject android_os_Parcel_openFileDescriptor(JNIEnv* env, jclass clazz,
                                                    jstring name, jint mode)
{
    if (name == NULL) {
        jniThrowNullPointerException(env, NULL);
        return NULL;
    }
    const jchar* str = env->GetStringCritical(name, 0);
    if (str == NULL) {
        // Whatever, whatever.
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return NULL;
    }
    String8 name8(str, env->GetStringLength(name));
    env->ReleaseStringCritical(name, str);
    int flags=0;
    switch (mode&0x30000000) {
        case 0:
        case 0x10000000:
            flags = O_RDONLY;
            break;
        case 0x20000000:
            flags = O_WRONLY;
            break;
        case 0x30000000:
            flags = O_RDWR;
            break;
    }

    if (mode&0x08000000) flags |= O_CREAT;
    if (mode&0x04000000) flags |= O_TRUNC;
    if (mode&0x02000000) flags |= O_APPEND;

    int realMode = S_IRWXU|S_IRWXG;
    if (mode&0x00000001) realMode |= S_IROTH;
    if (mode&0x00000002) realMode |= S_IWOTH;

    int fd = open(name8.string(), flags, realMode);
    if (fd < 0) {
        jniThrowException(env, "java/io/FileNotFoundException", strerror(errno));
        return NULL;
    }
    jobject object = jniCreateFileDescriptor(env, fd);
    if (object == NULL) {
        close(fd);
    }
    return object;
}

static jobject android_os_Parcel_dupFileDescriptor(JNIEnv* env, jclass clazz, jobject orig)
{
    if (orig == NULL) {
        jniThrowNullPointerException(env, NULL);
        return NULL;
    }
    int origfd = jniGetFDFromFileDescriptor(env, orig);
    if (origfd < 0) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "bad FileDescriptor");
        return NULL;
    }

    int fd = dup(origfd);
    if (fd < 0) {
        jniThrowIOException(env, errno);
        return NULL;
    }
    jobject object = jniCreateFileDescriptor(env, fd);
    if (object == NULL) {
        close(fd);
    }
    return object;
}

static void android_os_Parcel_closeFileDescriptor(JNIEnv* env, jclass clazz, jobject object)
{
    if (object == NULL) {
        jniThrowNullPointerException(env, NULL);
        return;
    }
    int fd = jniGetFDFromFileDescriptor(env, object);
    if (fd >= 0) {
        jniSetFileDescriptorOfFD(env, object, -1);
        //ALOGI("Closing ParcelFileDescriptor %d\n", fd);
        close(fd);
    }
}

static void android_os_Parcel_clearFileDescriptor(JNIEnv* env, jclass clazz, jobject object)
{
    if (object == NULL) {
        jniThrowNullPointerException(env, NULL);
        return;
    }
    int fd = jniGetFDFromFileDescriptor(env, object);
    if (fd >= 0) {
        jniSetFileDescriptorOfFD(env, object, -1);
    }
}

static jint android_os_Parcel_create(JNIEnv* env, jclass clazz)
{
    Parcel* parcel = new Parcel();
    return reinterpret_cast<jint>(parcel);
}

static void android_os_Parcel_freeBuffer(JNIEnv* env, jclass clazz, jint nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        parcel->freeData();
    }
}

static void android_os_Parcel_destroy(JNIEnv* env, jclass clazz, jint nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    delete parcel;
}

static jbyteArray android_os_Parcel_marshall(JNIEnv* env, jclass clazz, jint nativePtr)
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

static void android_os_Parcel_unmarshall(JNIEnv* env, jclass clazz, jint nativePtr,
                                         jbyteArray data, jint offset, jint length)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel == NULL || length < 0) {
       return;
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
}

static void android_os_Parcel_appendFrom(JNIEnv* env, jclass clazz, jint thisNativePtr,
                                         jint otherNativePtr, jint offset, jint length)
{
    Parcel* thisParcel = reinterpret_cast<Parcel*>(thisNativePtr);
    if (thisParcel == NULL) {
       return;
    }
    Parcel* otherParcel = reinterpret_cast<Parcel*>(otherNativePtr);
    if (otherParcel == NULL) {
       return;
    }

    status_t err = thisParcel->appendFrom(otherParcel, offset, length);
    if (err != NO_ERROR) {
        signalExceptionForError(env, clazz, err);
    }
}

static jboolean android_os_Parcel_hasFileDescriptors(JNIEnv* env, jclass clazz, jint nativePtr)
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

static void android_os_Parcel_writeInterfaceToken(JNIEnv* env, jclass clazz, jint nativePtr,
                                                  jstring name)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        // In the current implementation, the token is just the serialized interface name that
        // the caller expects to be invoking
        const jchar* str = env->GetStringCritical(name, 0);
        if (str != NULL) {
            parcel->writeInterfaceToken(String16(str, env->GetStringLength(name)));
            env->ReleaseStringCritical(name, str);
        }
    }
}

static void android_os_Parcel_enforceInterface(JNIEnv* env, jclass clazz, jint nativePtr, jstring name)
{
    jboolean ret = JNI_FALSE;

    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        const jchar* str = env->GetStringCritical(name, 0);
        if (str) {
            IPCThreadState* threadState = IPCThreadState::self();
            const int32_t oldPolicy = threadState->getStrictModePolicy();
            const bool isValid = parcel->enforceInterface(
                String16(str, env->GetStringLength(name)),
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

// ----------------------------------------------------------------------------

static const JNINativeMethod gParcelMethods[] = {
    {"nativeDataSize",            "(I)I", (void*)android_os_Parcel_dataSize},
    {"nativeDataAvail",           "(I)I", (void*)android_os_Parcel_dataAvail},
    {"nativeDataPosition",        "(I)I", (void*)android_os_Parcel_dataPosition},
    {"nativeDataCapacity",        "(I)I", (void*)android_os_Parcel_dataCapacity},
    {"nativeSetDataSize",         "(II)V", (void*)android_os_Parcel_setDataSize},
    {"nativeSetDataPosition",     "(II)V", (void*)android_os_Parcel_setDataPosition},
    {"nativeSetDataCapacity",     "(II)V", (void*)android_os_Parcel_setDataCapacity},

    {"nativePushAllowFds",        "(IZ)Z", (void*)android_os_Parcel_pushAllowFds},
    {"nativeRestoreAllowFds",     "(IZ)V", (void*)android_os_Parcel_restoreAllowFds},

    {"nativeWriteByteArray",      "(I[BII)V", (void*)android_os_Parcel_writeNative},
    {"nativeWriteInt",            "(II)V", (void*)android_os_Parcel_writeInt},
    {"nativeWriteLong",           "(IJ)V", (void*)android_os_Parcel_writeLong},
    {"nativeWriteFloat",          "(IF)V", (void*)android_os_Parcel_writeFloat},
    {"nativeWriteDouble",         "(ID)V", (void*)android_os_Parcel_writeDouble},
    {"nativeWriteString",         "(ILjava/lang/String;)V", (void*)android_os_Parcel_writeString},
    {"nativeWriteStrongBinder",   "(ILandroid/os/IBinder;)V", (void*)android_os_Parcel_writeStrongBinder},
    {"nativeWriteFileDescriptor", "(ILjava/io/FileDescriptor;)V", (void*)android_os_Parcel_writeFileDescriptor},

    {"nativeCreateByteArray",     "(I)[B", (void*)android_os_Parcel_createByteArray},
    {"nativeReadInt",             "(I)I", (void*)android_os_Parcel_readInt},
    {"nativeReadLong",            "(I)J", (void*)android_os_Parcel_readLong},
    {"nativeReadFloat",           "(I)F", (void*)android_os_Parcel_readFloat},
    {"nativeReadDouble",          "(I)D", (void*)android_os_Parcel_readDouble},
    {"nativeReadString",          "(I)Ljava/lang/String;", (void*)android_os_Parcel_readString},
    {"nativeReadStrongBinder",    "(I)Landroid/os/IBinder;", (void*)android_os_Parcel_readStrongBinder},
    {"nativeReadFileDescriptor",  "(I)Ljava/io/FileDescriptor;", (void*)android_os_Parcel_readFileDescriptor},

    {"openFileDescriptor",        "(Ljava/lang/String;I)Ljava/io/FileDescriptor;", (void*)android_os_Parcel_openFileDescriptor},
    {"dupFileDescriptor",         "(Ljava/io/FileDescriptor;)Ljava/io/FileDescriptor;", (void*)android_os_Parcel_dupFileDescriptor},
    {"closeFileDescriptor",       "(Ljava/io/FileDescriptor;)V", (void*)android_os_Parcel_closeFileDescriptor},
    {"clearFileDescriptor",       "(Ljava/io/FileDescriptor;)V", (void*)android_os_Parcel_clearFileDescriptor},

    {"nativeCreate",              "()I", (void*)android_os_Parcel_create},
    {"nativeFreeBuffer",          "(I)V", (void*)android_os_Parcel_freeBuffer},
    {"nativeDestroy",             "(I)V", (void*)android_os_Parcel_destroy},

    {"nativeMarshall",            "(I)[B", (void*)android_os_Parcel_marshall},
    {"nativeUnmarshall",          "(I[BII)V", (void*)android_os_Parcel_unmarshall},
    {"nativeAppendFrom",          "(IIII)V", (void*)android_os_Parcel_appendFrom},
    {"nativeHasFileDescriptors",  "(I)Z", (void*)android_os_Parcel_hasFileDescriptors},
    {"nativeWriteInterfaceToken", "(ILjava/lang/String;)V", (void*)android_os_Parcel_writeInterfaceToken},
    {"nativeEnforceInterface",    "(ILjava/lang/String;)V", (void*)android_os_Parcel_enforceInterface},
};

const char* const kParcelPathName = "android/os/Parcel";

int register_android_os_Parcel(JNIEnv* env)
{
    jclass clazz;

    clazz = env->FindClass(kParcelPathName);
    LOG_FATAL_IF(clazz == NULL, "Unable to find class android.os.Parcel");

    gParcelOffsets.clazz = (jclass) env->NewGlobalRef(clazz);
    gParcelOffsets.mNativePtr = env->GetFieldID(clazz, "mNativePtr", "I");
    gParcelOffsets.obtain = env->GetStaticMethodID(clazz, "obtain",
                                                   "()Landroid/os/Parcel;");
    gParcelOffsets.recycle = env->GetMethodID(clazz, "recycle", "()V");

    return AndroidRuntime::registerNativeMethods(
        env, kParcelPathName,
        gParcelMethods, NELEM(gParcelMethods));
}

};
