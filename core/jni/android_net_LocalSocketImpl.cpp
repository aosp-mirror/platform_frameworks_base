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

#include "JNIHelp.h"
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

namespace android {

static jfieldID field_inboundFileDescriptors;
static jfieldID field_outboundFileDescriptors;
static jclass class_Credentials;
static jclass class_FileDescriptor;
static jmethodID method_CredentialsInit;

/*
 * private native FileDescriptor
 * create_native(boolean stream)
 *               throws IOException;
 */
static jobject
socket_create (JNIEnv *env, jobject object, jboolean stream)
{
    int ret;

    ret = socket(PF_LOCAL, stream ? SOCK_STREAM : SOCK_DGRAM, 0);

    if (ret < 0) {
        jniThrowIOException(env, errno);
        return NULL;
    }

    return jniCreateFileDescriptor(env,ret);
}

/* private native void connectLocal(FileDescriptor fd,
 * String name, int namespace) throws IOException
 */
static void
socket_connect_local(JNIEnv *env, jobject object,
                        jobject fileDescriptor, jstring name, jint namespaceId)
{
    int ret;
    const char *nameUtf8;
    int fd;

    nameUtf8 = env->GetStringUTFChars(name, NULL);

    fd = jniGetFDFromFileDescriptor(env, fileDescriptor);

    if (env->ExceptionOccurred() != NULL) {
        return;
    }

    ret = socket_local_client_connect(
                fd,
                nameUtf8,
                namespaceId,
                SOCK_STREAM);

    env->ReleaseStringUTFChars(name, nameUtf8);

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
    const char *nameUtf8;


    if (name == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", NULL);
    }

    fd = jniGetFDFromFileDescriptor(env, fileDescriptor);

    if (env->ExceptionOccurred() != NULL) {
        return;
    }

    nameUtf8 = env->GetStringUTFChars(name, NULL);

    ret = socket_local_server_bind(fd, nameUtf8, namespaceId);

    env->ReleaseStringUTFChars(name, nameUtf8);
 
    if (ret < 0) {
        jniThrowIOException(env, errno);
        return;
    }
}

/* private native void listen_native(int fd, int backlog) throws IOException; */
static void
socket_listen (JNIEnv *env, jobject object, jobject fileDescriptor, int backlog)
{
    int ret;
    int fd;

    fd = jniGetFDFromFileDescriptor(env, fileDescriptor);

    if (env->ExceptionOccurred() != NULL) {
        return;
    }

    ret = listen(fd, backlog);

    if (ret < 0) {
        jniThrowIOException(env, errno);
        return;
    }
}

/*    private native FileDescriptor
**    accept (FileDescriptor fd, LocalSocketImpl s)
**                                   throws IOException;
*/
static jobject
socket_accept (JNIEnv *env, jobject object, jobject fileDescriptor, jobject s)
{
    union {
        struct sockaddr address;
        struct sockaddr_un un_address;
    } sa;
    
    int ret;
    int retFD;
    int fd;
    socklen_t addrlen;

    if (s == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", NULL);
        return NULL;
    }

    fd = jniGetFDFromFileDescriptor(env, fileDescriptor);

    if (env->ExceptionOccurred() != NULL) {
        return NULL;
    }

    do {
        addrlen = sizeof(sa);
        ret = accept(fd, &(sa.address), &addrlen);
    } while (ret < 0 && errno == EINTR);

    if (ret < 0) {
        jniThrowIOException(env, errno);
        return NULL;
    }

    retFD = ret;

    return jniCreateFileDescriptor(env, retFD);
}

/* private native void shutdown(FileDescriptor fd, boolean shutdownInput) */

static void
socket_shutdown (JNIEnv *env, jobject object, jobject fileDescriptor,
                    jboolean shutdownInput)
{
    int ret;
    int fd;

    fd = jniGetFDFromFileDescriptor(env, fileDescriptor);

    if (env->ExceptionOccurred() != NULL) {
        return;
    }

    ret = shutdown(fd, shutdownInput ? SHUT_RD : SHUT_WR);

    if (ret < 0) {
        jniThrowIOException(env, errno);
        return;
    }
}

static bool
java_opt_to_real(int optID, int* opt, int* level)
{
    switch (optID)
    {
        case 4098:
            *opt = SO_RCVBUF;
            *level = SOL_SOCKET;
            return true;
        case 4097:
            *opt = SO_SNDBUF;
            *level = SOL_SOCKET;
            return true;
        case 4102:
            *opt = SO_SNDTIMEO;
            *level = SOL_SOCKET;
            return true;
        case 128:
            *opt = SO_LINGER;
            *level = SOL_SOCKET;
            return true;
        case 1:
            *opt = TCP_NODELAY;
            *level = IPPROTO_TCP;
            return true;
        case 4:
            *opt = SO_REUSEADDR;
            *level = SOL_SOCKET;
            return true;

    }
    return false;
}

static jint
socket_getOption(JNIEnv *env, jobject object, jobject fileDescriptor, int optID)
{
    int ret, value;
    int opt, level;
    int fd;

    socklen_t size = sizeof(int);

    if (!java_opt_to_real(optID, &opt, &level)) {
        jniThrowIOException(env, -1);
        return 0;
    }

    fd = jniGetFDFromFileDescriptor(env, fileDescriptor);

    if (env->ExceptionOccurred() != NULL) {
        return 0;
    }

    switch (opt)
    {
        case SO_LINGER:
        {
            struct linger lingr;
            size = sizeof(lingr);
            ret = getsockopt(fd, level, opt, &lingr, &size);
            if (!lingr.l_onoff) {
                value = -1;
            } else {
                value = lingr.l_linger;
            }
            break;
        }
        default:
            ret = getsockopt(fd, level, opt, &value, &size);
            break;
    }


    if (ret != 0) {
        jniThrowIOException(env, errno);
        return 0;
    }

    return value;
}

static void socket_setOption(
        JNIEnv *env, jobject object, jobject fileDescriptor, int optID,
        jint boolValue, jint intValue) {
    int ret;
    int optname;
    int level;
    int fd;

    if (!java_opt_to_real(optID, &optname, &level)) {
        jniThrowIOException(env, -1);
        return;
    }

    fd = jniGetFDFromFileDescriptor(env, fileDescriptor);

    if (env->ExceptionOccurred() != NULL) {
        return;
    }

    switch (optname) {
        case SO_LINGER: {
            /*
             * SO_LINGER is special because it needs to use a special
             * "linger" struct as well as use the incoming boolean
             * argument specially.
             */
            struct linger lingr;
            lingr.l_onoff = boolValue ? 1 : 0; // Force it to be 0 or 1.
            lingr.l_linger = intValue;
            ret = setsockopt(fd, level, optname, &lingr, sizeof(lingr));
            break;
        }
        case SO_SNDTIMEO: {
            /*
             * SO_TIMEOUT from the core library gets converted to
             * SO_SNDTIMEO, but the option is supposed to set both
             * send and receive timeouts. Note: The incoming timeout
             * value is in milliseconds.
             */
            struct timeval timeout;
            timeout.tv_sec = intValue / 1000;
            timeout.tv_usec = (intValue % 1000) * 1000;
            
            ret = setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO,
                    (void *)&timeout, sizeof(timeout));

            if (ret == 0) {
                ret = setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO,
                        (void *)&timeout, sizeof(timeout));
            }
            
            break;
        }
        default: {
            /*
             * In all other cases, the translated option level and
             * optname may be used directly for a call to setsockopt().
             */
            ret = setsockopt(fd, level, optname, &intValue, sizeof(intValue));
            break;
        }
    }

    if (ret != 0) {
        jniThrowIOException(env, errno);
        return;
    }
}

static jint socket_available (JNIEnv *env, jobject object, 
        jobject fileDescriptor)
{
    int fd;

    fd = jniGetFDFromFileDescriptor(env, fileDescriptor);

    if (env->ExceptionOccurred() != NULL) {
        return (jint)-1;
    }

#if 1
    int avail;
    int ret = ioctl(fd, FIONREAD, &avail);

    // If this were a non-socket fd, there would be other cases to worry
    // about...

    if (ret < 0) {
        jniThrowIOException(env, errno);
        return (jint) 0;
    }

    return (jint)avail;
#else
// there appears to be a bionic bug that prevents this version from working.
    
    ssize_t ret;
    struct msghdr msg;

    memset(&msg, 0, sizeof(msg));

    do {
        ret = recvmsg(fd, &msg, MSG_PEEK | MSG_DONTWAIT | MSG_NOSIGNAL);
    } while (ret < 0 && errno == EINTR);

    
    // MSG_PEEK returns 0 on EOF and EWOULDBLOCK on none available
    if (ret < 0 && errno == EWOULDBLOCK) {
        return 0;
    } if (ret < 0) {
        jniThrowIOException(env, errno);
        return -1;
    }

    return (jint)ret;
#endif
}

static void socket_close (JNIEnv *env, jobject object, jobject fileDescriptor)
{
    int fd;
    int err;

    if (fileDescriptor == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", NULL);
        return;
    }

    fd = jniGetFDFromFileDescriptor(env, fileDescriptor);

    if (env->ExceptionOccurred() != NULL) {
        return;
    }

    do {
        err = close(fd);
    } while (err < 0 && errno == EINTR);

    if (err < 0) {
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
            }

            fdArray = env->NewObjectArray(count, class_FileDescriptor, NULL);

            if (fdArray == NULL) {
                return -1;
            }

            for (int i = 0; i < count; i++) {
                jobject fdObject 
                        = jniCreateFileDescriptor(env, pDescriptors[i]);

                if (env->ExceptionOccurred() != NULL) {
                    return -1;
                }

                env->SetObjectArrayElement(fdArray, i, fdObject);

                if (env->ExceptionOccurred() != NULL) {
                    return -1;
                }
            }

            env->SetObjectField(thisJ, field_inboundFileDescriptors, fdArray);

            if (env->ExceptionOccurred() != NULL) {
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
    ssize_t bytesread = 0;
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

    do {
        ret = recvmsg(fd, &msg, MSG_NOSIGNAL);
    } while (ret < 0 && errno == EINTR);

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

    if (env->ExceptionOccurred() != NULL) {
        return -1;
    }

    struct cmsghdr *cmsg;
    int countFds = outboundFds == NULL ? 0 : env->GetArrayLength(outboundFds);
    int fds[countFds];
    char msgbuf[CMSG_SPACE(countFds)];

    // Add any pending outbound file descriptors to the message
    if (outboundFds != NULL) {

        if (env->ExceptionOccurred() != NULL) {
            return -1;
        }

        for (int i = 0; i < countFds; i++) {
            jobject fdObject = env->GetObjectArrayElement(outboundFds, i);
            if (env->ExceptionOccurred() != NULL) {
                return -1;
            }

            fds[i] = jniGetFDFromFileDescriptor(env, fdObject);
            if (env->ExceptionOccurred() != NULL) {
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
        jniThrowException(env, "java/lang/NullPointerException", NULL);
        return (jint)-1;
    }

    fd = jniGetFDFromFileDescriptor(env, fileDescriptor);

    if (env->ExceptionOccurred() != NULL) {
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
        jniThrowException(env, "java/lang/NullPointerException", NULL);
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

    if (env->ExceptionOccurred() != NULL) {
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
        jniThrowException(env, "java/lang/NullPointerException", NULL);
        return;
    }

    fd = jniGetFDFromFileDescriptor(env, fileDescriptor);

    if (env->ExceptionOccurred() != NULL) {
        return;
    }

    err = socket_write_all(env, object, fd, &b, 1);

    // A return of -1 above means an exception is pending
}

static void socket_writeba (JNIEnv *env, jobject object, 
        jbyteArray buffer, jint off, jint len, jobject fileDescriptor)
{
    int fd;
    int err;
    jbyte* byteBuffer;

    if (fileDescriptor == NULL || buffer == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", NULL);
        return;
    }

    if (off < 0 || len < 0 || (off + len) > env->GetArrayLength(buffer)) {
        jniThrowException(env, "java/lang/ArrayIndexOutOfBoundsException", NULL);
        return;
    }

    fd = jniGetFDFromFileDescriptor(env, fileDescriptor);

    if (env->ExceptionOccurred() != NULL) {
        return;
    }

    byteBuffer = env->GetByteArrayElements(buffer,NULL);

    if (NULL == byteBuffer) {
        // an exception will have been thrown
        return;
    }

    err = socket_write_all(env, object, fd, 
            byteBuffer + off, len);

    // A return of -1 above means an exception is pending

    env->ReleaseByteArrayElements(buffer, byteBuffer, JNI_ABORT);
}

static jobject socket_get_peer_credentials(JNIEnv *env, 
        jobject object, jobject fileDescriptor)
{
    int err;
    int fd;

    if (fileDescriptor == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", NULL);
        return NULL;
    }

    fd = jniGetFDFromFileDescriptor(env, fileDescriptor);

    if (env->ExceptionOccurred() != NULL) {
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

#if 0
//TODO change this to return an instance of LocalSocketAddress
static jobject socket_getSockName(JNIEnv *env, 
        jobject object, jobject fileDescriptor)
{
    int err;
    int fd;

    if (fileDescriptor == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", NULL);
        return NULL;
    }

    fd = jniGetFDFromFileDescriptor(env, fileDescriptor);

    if (env->ExceptionOccurred() != NULL) {
        return NULL;
    }

    union {
        struct sockaddr address;
        struct sockaddr_un un_address;
    } sa;

    memset(&sa, 0, sizeof(sa));

    socklen_t namelen = sizeof(sa);
    err = getsockname(fd, &(sa.address), &namelen);

    if (err < 0) {
        jniThrowIOException(env, errno);
        return NULL;
    }

    if (sa.address.sa_family != AF_UNIX) {
        // We think we're an impl only for AF_UNIX, so this should never happen.

        jniThrowIOException(env, EINVAL);
        return NULL;
    }

    if (sa.un_address.sun_path[0] == '\0') {
    } else {
    }




}
#endif

/*
 * JNI registration.
 */
static JNINativeMethod gMethods[] = {
     /* name, signature, funcPtr */
    {"getOption_native", "(Ljava/io/FileDescriptor;I)I", (void*)socket_getOption},
    {"setOption_native", "(Ljava/io/FileDescriptor;III)V", (void*)socket_setOption},
    {"create_native", "(Z)Ljava/io/FileDescriptor;", (void*)socket_create},
    {"connectLocal", "(Ljava/io/FileDescriptor;Ljava/lang/String;I)V",
                                                (void*)socket_connect_local},
    {"bindLocal", "(Ljava/io/FileDescriptor;Ljava/lang/String;I)V", (void*)socket_bind_local},
    {"listen_native", "(Ljava/io/FileDescriptor;I)V", (void*)socket_listen},
    {"accept", "(Ljava/io/FileDescriptor;Landroid/net/LocalSocketImpl;)Ljava/io/FileDescriptor;", (void*)socket_accept},
    {"shutdown", "(Ljava/io/FileDescriptor;Z)V", (void*)socket_shutdown},
    {"available_native", "(Ljava/io/FileDescriptor;)I", (void*) socket_available},
    {"close_native", "(Ljava/io/FileDescriptor;)V", (void*) socket_close},
    {"read_native", "(Ljava/io/FileDescriptor;)I", (void*) socket_read},
    {"readba_native", "([BIILjava/io/FileDescriptor;)I", (void*) socket_readba},
    {"writeba_native", "([BIILjava/io/FileDescriptor;)V", (void*) socket_writeba},
    {"write_native", "(ILjava/io/FileDescriptor;)V", (void*) socket_write},
    {"getPeerCredentials_native", 
            "(Ljava/io/FileDescriptor;)Landroid/net/Credentials;", 
            (void*) socket_get_peer_credentials}
    //,{"getSockName_native", "(Ljava/io/FileDescriptor;)Ljava/lang/String;", 
    //        (void *) socket_getSockName}

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
    LOGE("Error registering android.net.LocalSocketImpl");
    return -1;
}

};
