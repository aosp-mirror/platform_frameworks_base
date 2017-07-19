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

#include <nativehelper/JNIHelp.h>
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

#include <cutils/sockets.h>
#include <netinet/tcp.h>
#include <nativehelper/ScopedUtfChars.h>

namespace android {

template <typename T>
void UNUSED(T t) {}

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
 * Processes ancillary data, handling only
 * SCM_RIGHTS. Creates appropriate objects and sets appropriate
 * fields in the LocalSocketImpl object. Returns 0 on success
 * or -1 if an exception was thrown.
 */
static int socket_process_cmsg(JNIEnv *env, jobject thisJ, struct msghdr * pMsg)
{
    struct cmsghdr *cmsgptr;

    for (cmsgptr = CMSG_FIRSTHDR(pMsg);
            cmsgptr != NULL; cmsgptr = CMSG_NXTHDR(pMsg, cmsgptr)) {

        if (cmsgptr->cmsg_level != SOL_SOCKET) {
            continue;
        }

        if (cmsgptr->cmsg_type == SCM_RIGHTS) {
            int *pDescriptors = (int *)CMSG_DATA(cmsgptr);
            jobjectArray fdArray;
            int count
                = ((cmsgptr->cmsg_len - CMSG_LEN(0)) / sizeof(int));

            if (count < 0) {
                jniThrowException(env, "java/io/IOException",
                    "invalid cmsg length");
                return -1;
            }

            fdArray = env->NewObjectArray(count, class_FileDescriptor, NULL);

            if (fdArray == NULL) {
                return -1;
            }

            for (int i = 0; i < count; i++) {
                jobject fdObject
                        = jniCreateFileDescriptor(env, pDescriptors[i]);

                if (env->ExceptionCheck()) {
                    return -1;
                }

                env->SetObjectArrayElement(fdArray, i, fdObject);

                if (env->ExceptionCheck()) {
                    return -1;
                }
            }

            env->SetObjectField(thisJ, field_inboundFileDescriptors, fdArray);

            if (env->ExceptionCheck()) {
                return -1;
            }
        }
    }

    return 0;
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
    struct msghdr msg;
    struct iovec iv;
    unsigned char *buf = (unsigned char *)buffer;
    // Enough buffer for a pile of fd's. We throw an exception if
    // this buffer is too small.
    struct cmsghdr cmsgbuf[2*sizeof(cmsghdr) + 0x100];

    memset(&msg, 0, sizeof(msg));
    memset(&iv, 0, sizeof(iv));

    iv.iov_base = buf;
    iv.iov_len = len;

    msg.msg_iov = &iv;
    msg.msg_iovlen = 1;
    msg.msg_control = cmsgbuf;
    msg.msg_controllen = sizeof(cmsgbuf);

    ret = TEMP_FAILURE_RETRY(recvmsg(fd, &msg, MSG_NOSIGNAL | MSG_CMSG_CLOEXEC));

    if (ret < 0 && errno == EPIPE) {
        // Treat this as an end of stream
        return 0;
    }

    if (ret < 0) {
        jniThrowIOException(env, errno);
        return -1;
    }

    if ((msg.msg_flags & (MSG_CTRUNC | MSG_OOB | MSG_ERRQUEUE)) != 0) {
        // To us, any of the above flags are a fatal error

        jniThrowException(env, "java/io/IOException",
                "Unexpected error or truncation during recvmsg()");

        return -1;
    }

    if (ret >= 0) {
        socket_process_cmsg(env, thisJ, &msg);
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
    ssize_t ret;
    struct msghdr msg;
    unsigned char *buffer = (unsigned char *)buf;
    memset(&msg, 0, sizeof(msg));

    jobjectArray outboundFds
            = (jobjectArray)env->GetObjectField(
                object, field_outboundFileDescriptors);

    if (env->ExceptionCheck()) {
        return -1;
    }

    struct cmsghdr *cmsg;
    int countFds = outboundFds == NULL ? 0 : env->GetArrayLength(outboundFds);
    int fds[countFds];
    char msgbuf[CMSG_SPACE(countFds)];

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

            fds[i] = jniGetFDFromFileDescriptor(env, fdObject);
            if (env->ExceptionCheck()) {
                return -1;
            }
        }

        // See "man cmsg" really
        msg.msg_control = msgbuf;
        msg.msg_controllen = sizeof msgbuf;
        cmsg = CMSG_FIRSTHDR(&msg);
        cmsg->cmsg_level = SOL_SOCKET;
        cmsg->cmsg_type = SCM_RIGHTS;
        cmsg->cmsg_len = CMSG_LEN(sizeof fds);
        memcpy(CMSG_DATA(cmsg), fds, sizeof fds);
    }

    // We only write our msg_control during the first write
    while (len > 0) {
        struct iovec iv;
        memset(&iv, 0, sizeof(iv));

        iv.iov_base = buffer;
        iv.iov_len = len;

        msg.msg_iov = &iv;
        msg.msg_iovlen = 1;

        do {
            ret = sendmsg(fd, &msg, MSG_NOSIGNAL);
        } while (ret < 0 && errno == EINTR);

        if (ret < 0) {
            jniThrowIOException(env, errno);
            return -1;
        }

        buffer += ret;
        len -= ret;

        // Wipes out any msg_control too
        memset(&msg, 0, sizeof(msg));
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
        jniThrowIOException(env, errno);
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
