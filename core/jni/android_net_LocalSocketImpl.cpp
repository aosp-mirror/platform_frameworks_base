/*
 * Copyright (C) 2006 The Android Open Source Project
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

#define LOG_TAG "LocalSocketImpl"

#include <nativehelper/JNIPlatformHelp.h>
#include "jni.h"
#include "utils/Log.h"
#include "utils/misc.h"

#include <stdio.h>
#include <string.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <stdlib.h>
#include <errno.h>
#include <unistd.h>
#include <sys/ioctl.h>

#include <android-base/cmsg.h>
#include <android-base/macros.h>
#include <cutils/sockets.h>
#include <netinet/tcp.h>
#include <nativehelper/ScopedUtfChars.h>

using android::base::ReceiveFileDescriptorVector;
using android::base::SendFileDescriptorVector;

namespace android {

static jfieldID field_inboundFileDescriptors;
static jfieldID field_outboundFileDescriptors;
static jclass class_Credentials;
static jclass class_FileDescriptor;
static jmethodID method_CredentialsInit;

/* private native void connectLocal(FileDescriptor fd,
 * String name, int namespace) throws IOException
 */
static void
socket_connect_local(JNIEnv *env, jobject object,
                        jobject fileDescriptor, jstring name, jint namespaceId)
{
    int ret;
    int fd;

    if (name == NULL) {
        jniThrowNullPointerException(env, NULL);
        return;
    }

    fd = jniGetFDFromFileDescriptor(env, fileDescriptor);

    if (env->ExceptionCheck()) {
        return;
    }

    ScopedUtfChars nameUtf8(env, name);

    ret = socket_local_client_connect(
                fd,
                nameUtf8.c_str(),
                namespaceId,
                SOCK_STREAM);

    if (ret < 0) {
        jniThrowIOException(env, errno);
        return;
    }
}

#define DEFAULT_BACKLOG 4

/* private native void bindLocal(FileDescriptor fd, String name, namespace)
 * throws IOException;
 */

static void
socket_bind_local (JNIEnv *env, jobject object, jobject fileDescriptor,
                jstring name, jint namespaceId)
{
    int ret;
    int fd;

    if (name == NULL) {
        jniThrowNullPointerException(env, NULL);
        return;
    }

    fd = jniGetFDFromFileDescriptor(env, fileDescriptor);

    if (env->ExceptionCheck()) {
        return;
    }

    ScopedUtfChars nameUtf8(env, name);

    ret = socket_local_server_bind(fd, nameUtf8.c_str(), namespaceId);

    if (ret < 0) {
        jniThrowIOException(env, errno);
        return;
    }
}

/**
 * Reads data from a socket into buf, processing any ancillary data
 * and adding it to thisJ.
 *
 * Returns the length of normal data read, or -1 if an exception has
 * been thrown in this function.
 */
static ssize_t socket_read_all(JNIEnv *env, jobject thisJ, int fd,
        void *buffer, size_t len)
{
    ssize_t ret;
    std::vector<android::base::unique_fd> received_fds;

    ret = ReceiveFileDescriptorVector(fd, buffer, len, 64, &received_fds);

    if (ret < 0) {
        if (errno == EPIPE) {
            // Treat this as an end of stream
            return 0;
        }

        jniThrowIOException(env, errno);
        return -1;
    }

    if (received_fds.size() > 0) {
        jobjectArray fdArray = env->NewObjectArray(received_fds.size(), class_FileDescriptor, NULL);

        if (fdArray == NULL) {
            // NewObjectArray has thrown.
            return -1;
        }

        for (size_t i = 0; i < received_fds.size(); i++) {
            jobject fdObject = jniCreateFileDescriptor(env, received_fds[i].get());

            if (env->ExceptionCheck()) {
                return -1;
            }

            env->SetObjectArrayElement(fdArray, i, fdObject);

            if (env->ExceptionCheck()) {
                return -1;
            }
        }

        for (auto &fd : received_fds) {
            // The fds are stored in java.io.FileDescriptors now.
            static_cast<void>(fd.release());
        }

        env->SetObjectField(thisJ, field_inboundFileDescriptors, fdArray);
    }

    return ret;
}

/**
 * Writes all the data in the specified buffer to the specified socket.
 *
 * Returns 0 on success or -1 if an exception was thrown.
 */
static int socket_write_all(JNIEnv *env, jobject object, int fd,
        void *buf, size_t len)
{
    struct msghdr msg;
    unsigned char *buffer = (unsigned char *)buf;
    memset(&msg, 0, sizeof(msg));

    jobjectArray outboundFds
            = (jobjectArray)env->GetObjectField(
                object, field_outboundFileDescriptors);

    if (env->ExceptionCheck()) {
        return -1;
    }

    int countFds = outboundFds == NULL ? 0 : env->GetArrayLength(outboundFds);
    std::vector<int> fds;

    // Add any pending outbound file descriptors to the message
    if (outboundFds != NULL) {
        if (env->ExceptionCheck()) {
            return -1;
        }

        for (int i = 0; i < countFds; i++) {
            jobject fdObject = env->GetObjectArrayElement(outboundFds, i);
            if (env->ExceptionCheck()) {
                return -1;
            }

            fds.push_back(jniGetFDFromFileDescriptor(env, fdObject));
            if (env->ExceptionCheck()) {
                return -1;
            }
        }
    }

    ssize_t rc = SendFileDescriptorVector(fd, buffer, len, fds);

    while (rc != len) {
        if (rc == -1) {
            jniThrowIOException(env, errno);
            return -1;
        }

        buffer += rc;
        len -= rc;

        rc = send(fd, buffer, len, MSG_NOSIGNAL);
    }

    return 0;
}

static jint socket_read (JNIEnv *env, jobject object, jobject fileDescriptor)
{
    int fd;
    int err;

    if (fileDescriptor == NULL) {
        jniThrowNullPointerException(env, NULL);
        return (jint)-1;
    }

    fd = jniGetFDFromFileDescriptor(env, fileDescriptor);

    if (env->ExceptionCheck()) {
        return (jint)0;
    }

    unsigned char buf;

    err = socket_read_all(env, object, fd, &buf, 1);

    if (err < 0) {
        // socket_read_all has already thrown
        return (jint)0;
    }

    if (err == 0) {
        // end of file
        return (jint)-1;
    }

    return (jint)buf;
}

static jint socket_readba (JNIEnv *env, jobject object,
        jbyteArray buffer, jint off, jint len, jobject fileDescriptor)
{
    int fd;
    jbyte* byteBuffer;
    int ret;

    if (fileDescriptor == NULL || buffer == NULL) {
        jniThrowNullPointerException(env, NULL);
        return (jint)-1;
    }

    if (off < 0 || len < 0 || (off + len) > env->GetArrayLength(buffer)) {
        jniThrowException(env, "java/lang/ArrayIndexOutOfBoundsException", NULL);
        return (jint)-1;
    }

    if (len == 0) {
        // because socket_read_all returns 0 on EOF
        return 0;
    }

    fd = jniGetFDFromFileDescriptor(env, fileDescriptor);

    if (env->ExceptionCheck()) {
        return (jint)-1;
    }

    byteBuffer = env->GetByteArrayElements(buffer, NULL);

    if (NULL == byteBuffer) {
        // an exception will have been thrown
        return (jint)-1;
    }

    ret = socket_read_all(env, object,
            fd, byteBuffer + off, len);

    // A return of -1 above means an exception is pending

    env->ReleaseByteArrayElements(buffer, byteBuffer, 0);

    return (jint) ((ret == 0) ? -1 : ret);
}

static void socket_write (JNIEnv *env, jobject object,
        jint b, jobject fileDescriptor)
{
    int fd;
    int err;

    if (fileDescriptor == NULL) {
        jniThrowNullPointerException(env, NULL);
        return;
    }

    fd = jniGetFDFromFileDescriptor(env, fileDescriptor);

    if (env->ExceptionCheck()) {
        return;
    }

    err = socket_write_all(env, object, fd, &b, 1);
    UNUSED(err);
    // A return of -1 above means an exception is pending
}

static void socket_writeba (JNIEnv *env, jobject object,
        jbyteArray buffer, jint off, jint len, jobject fileDescriptor)
{
    int fd;
    int err;
    jbyte* byteBuffer;

    if (fileDescriptor == NULL || buffer == NULL) {
        jniThrowNullPointerException(env, NULL);
        return;
    }

    if (off < 0 || len < 0 || (off + len) > env->GetArrayLength(buffer)) {
        jniThrowException(env, "java/lang/ArrayIndexOutOfBoundsException", NULL);
        return;
    }

    fd = jniGetFDFromFileDescriptor(env, fileDescriptor);

    if (env->ExceptionCheck()) {
        return;
    }

    byteBuffer = env->GetByteArrayElements(buffer,NULL);

    if (NULL == byteBuffer) {
        // an exception will have been thrown
        return;
    }

    err = socket_write_all(env, object, fd,
            byteBuffer + off, len);
    UNUSED(err);
    // A return of -1 above means an exception is pending

    env->ReleaseByteArrayElements(buffer, byteBuffer, JNI_ABORT);
}

static jobject socket_get_peer_credentials(JNIEnv *env,
        jobject object, jobject fileDescriptor)
{
    int err;
    int fd;

    if (fileDescriptor == NULL) {
        jniThrowNullPointerException(env, NULL);
        return NULL;
    }

    fd = jniGetFDFromFileDescriptor(env, fileDescriptor);

    if (env->ExceptionCheck()) {
        return NULL;
    }

    struct ucred creds;

    memset(&creds, 0, sizeof(creds));
    socklen_t szCreds = sizeof(creds);

    err = getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &creds, &szCreds);

    if (err < 0) {
        jniThrowIOException(env, errno);
        return NULL;
    }

    if (szCreds == 0) {
        return NULL;
    }

    return env->NewObject(class_Credentials, method_CredentialsInit,
            creds.pid, creds.uid, creds.gid);
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
     /* name, signature, funcPtr */
    {"connectLocal", "(Ljava/io/FileDescriptor;Ljava/lang/String;I)V",
                                                (void*)socket_connect_local},
    {"bindLocal", "(Ljava/io/FileDescriptor;Ljava/lang/String;I)V", (void*)socket_bind_local},
    {"read_native", "(Ljava/io/FileDescriptor;)I", (void*) socket_read},
    {"readba_native", "([BIILjava/io/FileDescriptor;)I", (void*) socket_readba},
    {"writeba_native", "([BIILjava/io/FileDescriptor;)V", (void*) socket_writeba},
    {"write_native", "(ILjava/io/FileDescriptor;)V", (void*) socket_write},
    {"getPeerCredentials_native",
            "(Ljava/io/FileDescriptor;)Landroid/net/Credentials;",
            (void*) socket_get_peer_credentials}
};

int register_android_net_LocalSocketImpl(JNIEnv *env)
{
    jclass clazz;

    clazz = env->FindClass("android/net/LocalSocketImpl");

    if (clazz == NULL) {
        goto error;
    }

    field_inboundFileDescriptors = env->GetFieldID(clazz,
            "inboundFileDescriptors", "[Ljava/io/FileDescriptor;");

    if (field_inboundFileDescriptors == NULL) {
        goto error;
    }

    field_outboundFileDescriptors = env->GetFieldID(clazz,
            "outboundFileDescriptors", "[Ljava/io/FileDescriptor;");

    if (field_outboundFileDescriptors == NULL) {
        goto error;
    }

    class_Credentials = env->FindClass("android/net/Credentials");

    if (class_Credentials == NULL) {
        goto error;
    }

    class_Credentials = (jclass)env->NewGlobalRef(class_Credentials);

    class_FileDescriptor = env->FindClass("java/io/FileDescriptor");

    if (class_FileDescriptor == NULL) {
        goto error;
    }

    class_FileDescriptor = (jclass)env->NewGlobalRef(class_FileDescriptor);

    method_CredentialsInit
            = env->GetMethodID(class_Credentials, "<init>", "(III)V");

    if (method_CredentialsInit == NULL) {
        goto error;
    }

    return jniRegisterNativeMethods(env,
        "android/net/LocalSocketImpl", gMethods, NELEM(gMethods));

error:
    ALOGE("Error registering android.net.LocalSocketImpl");
    return -1;
}

};
