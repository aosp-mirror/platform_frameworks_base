/*
 * Copyright (C) 2008 The Android Open Source Project
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

#include "JNIHelp.h"

#include <fcntl.h>
#include <sys/stat.h>
#include <stdio.h>

#include <utils/Log.h>

#include <android_runtime/AndroidRuntime.h>

namespace android
{

static struct file_descriptor_offsets_t
{
    jclass mClass;
    jmethodID mConstructor;
    jfieldID mDescriptor;
} gFileDescriptorOffsets;

static struct socket_offsets_t
{
    jfieldID mSocketImpl;
} gSocketOffsets;

static struct socket_impl_offsets_t
{
    jfieldID mFileDescriptor;
} gSocketImplOffsets;

static struct parcel_file_descriptor_offsets_t
{
    jclass mClass;
    jfieldID mFileDescriptor;
} gParcelFileDescriptorOffsets;

static jobject android_os_ParcelFileDescriptor_getFileDescriptorFromSocket(JNIEnv* env,
    jobject clazz, jobject object)
{
    jobject socketImpl = env->GetObjectField(object, gSocketOffsets.mSocketImpl);
    jobject fileDescriptor = env->GetObjectField(socketImpl, gSocketImplOffsets.mFileDescriptor);
    jint fd = env->GetIntField(fileDescriptor, gFileDescriptorOffsets.mDescriptor);
    jobject fileDescriptorClone = env->NewObject(gFileDescriptorOffsets.mClass,
        gFileDescriptorOffsets.mConstructor);
    if (fileDescriptorClone != NULL) {
        env->SetIntField(fileDescriptorClone, gFileDescriptorOffsets.mDescriptor, dup(fd));
    }
    return fileDescriptorClone;
}

static jint getFd(JNIEnv* env, jobject clazz)
{
    jobject descriptor = env->GetObjectField(clazz, gParcelFileDescriptorOffsets.mFileDescriptor);
    if (descriptor == NULL) return -1;
    return env->GetIntField(descriptor, gFileDescriptorOffsets.mDescriptor);
}

static jlong android_os_ParcelFileDescriptor_getStatSize(JNIEnv* env,
    jobject clazz)
{
    jint fd = getFd(env, clazz);
    if (fd < 0) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "bad file descriptor");
        return -1;
    }
    
    struct stat st;
    if (fstat(fd, &st) != 0) {
        return -1;
    }
    
    if (S_ISREG(st.st_mode) || S_ISLNK(st.st_mode)) {
        return st.st_size;
    }
    
    return -1;
}

static jlong android_os_ParcelFileDescriptor_seekTo(JNIEnv* env,
    jobject clazz, jlong pos)
{
    jint fd = getFd(env, clazz);
    if (fd < 0) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "bad file descriptor");
        return -1;
    }
    
    return lseek(fd, pos, SEEK_SET);
}

static const JNINativeMethod gParcelFileDescriptorMethods[] = {
    {"getFileDescriptorFromSocket", "(Ljava/net/Socket;)Ljava/io/FileDescriptor;",
        (void*)android_os_ParcelFileDescriptor_getFileDescriptorFromSocket},
    {"getStatSize", "()J",
        (void*)android_os_ParcelFileDescriptor_getStatSize},
    {"seekTo", "(J)J",
        (void*)android_os_ParcelFileDescriptor_seekTo}
};

const char* const kParcelFileDescriptorPathName = "android/os/ParcelFileDescriptor";

int register_android_os_ParcelFileDescriptor(JNIEnv* env)
{
    jclass clazz;

    clazz = env->FindClass("java/net/Socket");
    LOG_FATAL_IF(clazz == NULL, "Unable to find class java.net.Socket");
    gSocketOffsets.mSocketImpl = env->GetFieldID(clazz, "impl", "Ljava/net/SocketImpl;");
    LOG_FATAL_IF(gSocketOffsets.mSocketImpl == NULL,
        "Unable to find impl field in java.net.Socket");

    clazz = env->FindClass("java/net/SocketImpl");
    LOG_FATAL_IF(clazz == NULL, "Unable to find class java.net.SocketImpl");
    gSocketImplOffsets.mFileDescriptor = env->GetFieldID(clazz, "fd", "Ljava/io/FileDescriptor;");
    LOG_FATAL_IF(gSocketImplOffsets.mFileDescriptor == NULL,
                 "Unable to find fd field in java.net.SocketImpl");

    clazz = env->FindClass("java/io/FileDescriptor");
    LOG_FATAL_IF(clazz == NULL, "Unable to find class java.io.FileDescriptor");
    gFileDescriptorOffsets.mClass = (jclass) env->NewGlobalRef(clazz);
    gFileDescriptorOffsets.mConstructor = env->GetMethodID(clazz, "<init>", "()V");
    gFileDescriptorOffsets.mDescriptor = env->GetFieldID(clazz, "descriptor", "I");
    LOG_FATAL_IF(gFileDescriptorOffsets.mDescriptor == NULL,
                 "Unable to find descriptor field in java.io.FileDescriptor");
    
    clazz = env->FindClass(kParcelFileDescriptorPathName);
    LOG_FATAL_IF(clazz == NULL, "Unable to find class android.os.ParcelFileDescriptor");
    gParcelFileDescriptorOffsets.mClass = (jclass) env->NewGlobalRef(clazz);
    gParcelFileDescriptorOffsets.mFileDescriptor = env->GetFieldID(clazz, "mFileDescriptor", "Ljava/io/FileDescriptor;");
    LOG_FATAL_IF(gParcelFileDescriptorOffsets.mFileDescriptor == NULL,
                 "Unable to find mFileDescriptor field in android.os.ParcelFileDescriptor");

    return AndroidRuntime::registerNativeMethods(
        env, kParcelFileDescriptorPathName,
        gParcelFileDescriptorMethods, NELEM(gParcelFileDescriptorMethods));
}

}
