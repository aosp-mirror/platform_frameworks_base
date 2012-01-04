/*
** Copyright 2006, The Android Open Source Project
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

#define LOG_TAG "BT HSHFP"

#include "android_bluetooth_common.h"
#include "android_runtime/AndroidRuntime.h"
#include "JNIHelp.h"
#include "jni.h"
#include "utils/Log.h"
#include "utils/misc.h"

#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <errno.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <sys/poll.h>

#ifdef HAVE_BLUETOOTH
#include <bluetooth/bluetooth.h>
#include <bluetooth/rfcomm.h>
#include <bluetooth/sco.h>
#endif

namespace android {

#ifdef HAVE_BLUETOOTH
static jfieldID field_mNativeData;
static jfieldID field_mAddress;
static jfieldID field_mRfcommChannel;
static jfieldID field_mTimeoutRemainingMs;

typedef struct {
    jstring address;
    const char *c_address;
    int rfcomm_channel;
    int last_read_err;
    int rfcomm_sock;
    int rfcomm_connected; // -1 in progress, 0 not connected, 1 connected
    int rfcomm_sock_flags;
} native_data_t;

static inline native_data_t * get_native_data(JNIEnv *env, jobject object) {
    return (native_data_t *)(env->GetIntField(object, field_mNativeData));
}

static const char CRLF[] = "\xd\xa";
static const int CRLF_LEN = 2;

static inline int write_error_check(int fd, const char* line, int len) {
    int ret;
    errno = 0;
    ret = write(fd, line, len);
    if (ret < 0) {
        LOGE("%s: write() failed: %s (%d)", __FUNCTION__, strerror(errno),
             errno);
        return -1;
    }
    if (ret != len) {
        LOGE("%s: write() only wrote %d of %d bytes", __FUNCTION__, ret, len);
        return -1;
    }
    return 0;
}

static int send_line(int fd, const char* line) {
    int nw;
    int len = strlen(line);
    int llen = len + CRLF_LEN * 2 + 1;
    char *buffer = (char *)calloc(llen, sizeof(char));

    snprintf(buffer, llen, "%s%s%s", CRLF, line, CRLF);

    if (write_error_check(fd, buffer, llen - 1)) {
        free(buffer);
        return -1;
    }
    free(buffer);
    return 0;
}

static void mask_eighth_bit(char *line)
{
   for (;;line++) {
     if (0 == *line) return;
     *line &= 0x7F;
   }
}

static const char* get_line(int fd, char *buf, int len, int timeout_ms,
                            int *err) {
    char *bufit=buf;
    int fd_flags = fcntl(fd, F_GETFL, 0);
    struct pollfd pfd;

again:
    *bufit = 0;
    pfd.fd = fd;
    pfd.events = POLLIN;
    *err = errno = 0;
    int ret = poll(&pfd, 1, timeout_ms);
    if (ret < 0) {
        LOGE("poll() error\n");
        *err = errno;
        return NULL;
    }
    if (ret == 0) {
        return NULL;
    }

    if (pfd.revents & (POLLHUP | POLLERR | POLLNVAL)) {
        LOGW("RFCOMM poll() returned  success (%d), "
             "but with an unexpected revents bitmask: %#x\n", ret, pfd.revents);
        errno = EIO;
        *err = errno;
        return NULL;
    }

    while ((int)(bufit - buf) < (len - 1))
    {
        errno = 0;
        int rc = read(fd, bufit, 1);

        if (!rc)
            break;

        if (rc < 0) {
            if (errno == EBUSY) {
                ALOGI("read() error %s (%d): repeating read()...",
                     strerror(errno), errno);
                goto again;
            }
            *err = errno;
            LOGE("read() error %s (%d)", strerror(errno), errno);
            return NULL;
        }


        if (*bufit=='\xd') {
            break;
        }

        if (*bufit=='\xa')
            bufit = buf;
        else
            bufit++;
    }

    *bufit = NULL;

    // According to ITU V.250 section 5.1, IA5 7 bit chars are used, 
    //   the eighth bit or higher bits are ignored if they exists
    // We mask out only eighth bit, no higher bit, since we do char
    // string here, not wide char.
    // We added this processing due to 2 real world problems.
    // 1 BMW 2005 E46 which sends binary junk
    // 2 Audi 2010 A3, dial command use 0xAD (soft-hyphen) as number 
    //   formater, which was rejected by the AT handler
    mask_eighth_bit(buf);

    return buf;
}
#endif

static void classInitNative(JNIEnv* env, jclass clazz) {
    ALOGV("%s", __FUNCTION__);
#ifdef HAVE_BLUETOOTH
    field_mNativeData = get_field(env, clazz, "mNativeData", "I");
    field_mAddress = get_field(env, clazz, "mAddress", "Ljava/lang/String;");
    field_mTimeoutRemainingMs = get_field(env, clazz, "mTimeoutRemainingMs", "I");
    field_mRfcommChannel = get_field(env, clazz, "mRfcommChannel", "I");
#endif
}

static void initializeNativeDataNative(JNIEnv* env, jobject object,
                                       jint socketFd) {
    ALOGV("%s", __FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = (native_data_t *)calloc(1, sizeof(native_data_t));
    if (NULL == nat) {
        LOGE("%s: out of memory!", __FUNCTION__);
        return;
    }

    env->SetIntField(object, field_mNativeData, (jint)nat);
    nat->address =
        (jstring)env->NewGlobalRef(env->GetObjectField(object,
                                                       field_mAddress));
    nat->c_address = env->GetStringUTFChars(nat->address, NULL);
    nat->rfcomm_channel = env->GetIntField(object, field_mRfcommChannel);
    nat->rfcomm_sock = socketFd;
    nat->rfcomm_connected = socketFd >= 0;
    if (nat->rfcomm_connected)
        ALOGI("%s: ALREADY CONNECTED!", __FUNCTION__);
#endif
}

static void cleanupNativeDataNative(JNIEnv* env, jobject object) {
    ALOGV("%s", __FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat =
        (native_data_t *)env->GetIntField(object, field_mNativeData);
    env->ReleaseStringUTFChars(nat->address, nat->c_address);
    env->DeleteGlobalRef(nat->address);
    if (nat)
        free(nat);
#endif
}

static jboolean connectNative(JNIEnv *env, jobject obj)
{
    ALOGV("%s", __FUNCTION__);
#ifdef HAVE_BLUETOOTH
    int lm;
    struct sockaddr_rc addr;
    native_data_t *nat = get_native_data(env, obj);

    nat->rfcomm_sock = socket(PF_BLUETOOTH, SOCK_STREAM, BTPROTO_RFCOMM);

    if (nat->rfcomm_sock < 0) {
        LOGE("%s: Could not create RFCOMM socket: %s\n", __FUNCTION__,
             strerror(errno));
        return JNI_FALSE;
    }

    if (debug_no_encrypt()) {
        lm = RFCOMM_LM_AUTH;
    } else {
        lm = RFCOMM_LM_AUTH | RFCOMM_LM_ENCRYPT;
    }

    if (lm && setsockopt(nat->rfcomm_sock, SOL_RFCOMM, RFCOMM_LM, &lm,
                sizeof(lm)) < 0) {
        LOGE("%s: Can't set RFCOMM link mode", __FUNCTION__);
        close(nat->rfcomm_sock);
        return JNI_FALSE;
    }

    memset(&addr, 0, sizeof(struct sockaddr_rc));
    get_bdaddr(nat->c_address, &addr.rc_bdaddr);
    addr.rc_channel = nat->rfcomm_channel;
    addr.rc_family = AF_BLUETOOTH;
    nat->rfcomm_connected = 0;
    while (nat->rfcomm_connected == 0) {
        if (connect(nat->rfcomm_sock, (struct sockaddr *)&addr,
                      sizeof(addr)) < 0) {
            if (errno == EINTR) continue;
            LOGE("%s: connect() failed: %s\n", __FUNCTION__, strerror(errno));
            close(nat->rfcomm_sock);
            nat->rfcomm_sock = -1;
            return JNI_FALSE;
        } else {
            nat->rfcomm_connected = 1;
        }
    }

    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

static jint connectAsyncNative(JNIEnv *env, jobject obj) {
    ALOGV("%s", __FUNCTION__);
#ifdef HAVE_BLUETOOTH
    struct sockaddr_rc addr;
    native_data_t *nat = get_native_data(env, obj);

    if (nat->rfcomm_connected) {
        ALOGV("RFCOMM socket is already connected or connection is in progress.");
        return 0;
    }

    if (nat->rfcomm_sock < 0) {
        int lm;

        nat->rfcomm_sock = socket(PF_BLUETOOTH, SOCK_STREAM, BTPROTO_RFCOMM);
        if (nat->rfcomm_sock < 0) {
            LOGE("%s: Could not create RFCOMM socket: %s\n", __FUNCTION__,
                 strerror(errno));
            return -1;
        }

        if (debug_no_encrypt()) {
            lm = RFCOMM_LM_AUTH;
        } else {
            lm = RFCOMM_LM_AUTH | RFCOMM_LM_ENCRYPT;
        }

        if (lm && setsockopt(nat->rfcomm_sock, SOL_RFCOMM, RFCOMM_LM, &lm,
                    sizeof(lm)) < 0) {
            LOGE("%s: Can't set RFCOMM link mode", __FUNCTION__);
            close(nat->rfcomm_sock);
            return -1;
        }
        ALOGI("Created RFCOMM socket fd %d.", nat->rfcomm_sock);
    }

    memset(&addr, 0, sizeof(struct sockaddr_rc));
    get_bdaddr(nat->c_address, &addr.rc_bdaddr);
    addr.rc_channel = nat->rfcomm_channel;
    addr.rc_family = AF_BLUETOOTH;
    if (nat->rfcomm_sock_flags >= 0) {
        nat->rfcomm_sock_flags = fcntl(nat->rfcomm_sock, F_GETFL, 0);
        if (fcntl(nat->rfcomm_sock,
                  F_SETFL, nat->rfcomm_sock_flags | O_NONBLOCK) >= 0) {
            int rc;
            nat->rfcomm_connected = 0;
            errno = 0;
            rc = connect(nat->rfcomm_sock,
                        (struct sockaddr *)&addr,
                         sizeof(addr));

            if (rc >= 0) {
                nat->rfcomm_connected = 1;
                ALOGI("async connect successful");
                return 0;
            }
            else if (rc < 0) {
                if (errno == EINPROGRESS || errno == EAGAIN)
                {
                    ALOGI("async connect is in progress (%s)",
                         strerror(errno));
                    nat->rfcomm_connected = -1;
                    return 0;
                }
                else
                {
                    LOGE("async connect error: %s (%d)", strerror(errno), errno);
                    close(nat->rfcomm_sock);
                    nat->rfcomm_sock = -1;
                    return -errno;
                }
            }
        } // fcntl(nat->rfcomm_sock ...)
    } // if (nat->rfcomm_sock_flags >= 0)
#endif
    return -1;
}

static jint waitForAsyncConnectNative(JNIEnv *env, jobject obj,
                                           jint timeout_ms) {
    ALOGV("%s", __FUNCTION__);
#ifdef HAVE_BLUETOOTH
    struct sockaddr_rc addr;
    native_data_t *nat = get_native_data(env, obj);

    env->SetIntField(obj, field_mTimeoutRemainingMs, timeout_ms);

    if (nat->rfcomm_connected > 0) {
        ALOGI("RFCOMM is already connected!");
        return 1;
    }

    if (nat->rfcomm_sock >= 0 && nat->rfcomm_connected == 0) {
        ALOGI("Re-opening RFCOMM socket.");
        close(nat->rfcomm_sock);
        nat->rfcomm_sock = -1;
    }
    int ret = connectAsyncNative(env, obj);

    if (ret < 0) {
        ALOGI("Failed to re-open RFCOMM socket!");
        return ret;
    }

    if (nat->rfcomm_sock >= 0) {
        /* Do an asynchronous select() */
        int n;
        fd_set rset, wset;
        struct timeval to;

        FD_ZERO(&rset);
        FD_ZERO(&wset);
        FD_SET(nat->rfcomm_sock, &rset);
        FD_SET(nat->rfcomm_sock, &wset);
        if (timeout_ms >= 0) {
            to.tv_sec = timeout_ms / 1000;
            to.tv_usec = 1000 * (timeout_ms % 1000);
        }
        n = select(nat->rfcomm_sock + 1,
                   &rset,
                   &wset,
                   NULL,
                   (timeout_ms < 0 ? NULL : &to));

        if (timeout_ms > 0) {
            jint remaining = to.tv_sec*1000 + to.tv_usec/1000;
            ALOGV("Remaining time %ldms", (long)remaining);
            env->SetIntField(obj, field_mTimeoutRemainingMs,
                             remaining);
        }

        if (n <= 0) {
            if (n < 0)  {
                LOGE("select() on RFCOMM socket: %s (%d)",
                     strerror(errno),
                     errno);
                return -errno;
            }
            return 0;
        }
        /* n must be equal to 1 and either rset or wset must have the
           file descriptor set. */
        ALOGV("select() returned %d.", n);
        if (FD_ISSET(nat->rfcomm_sock, &rset) ||
            FD_ISSET(nat->rfcomm_sock, &wset))
        {
            /* A trial async read() will tell us if everything is OK. */
            {
                char ch;
                errno = 0;
                int nr = read(nat->rfcomm_sock, &ch, 1);
                /* It should be that nr != 1 because we just opened a socket
                   and we haven't sent anything over it for the other side to
                   respond... but one can't be paranoid enough.
                */
                if (nr >= 0 || errno != EAGAIN) {
                    LOGE("RFCOMM async connect() error: %s (%d), nr = %d\n",
                         strerror(errno),
                         errno,
                         nr);
                    /* Clear the rfcomm_connected flag to cause this function
                       to re-create the socket and re-attempt the connect()
                       the next time it is called.
                    */
                    nat->rfcomm_connected = 0;
                    /* Restore the blocking properties of the socket. */
                    fcntl(nat->rfcomm_sock, F_SETFL, nat->rfcomm_sock_flags);
                    close(nat->rfcomm_sock);
                    nat->rfcomm_sock = -1;
                    return -errno;
                }
            }
            /* Restore the blocking properties of the socket. */
            fcntl(nat->rfcomm_sock, F_SETFL, nat->rfcomm_sock_flags);
            ALOGI("Successful RFCOMM socket connect.");
            nat->rfcomm_connected = 1;
            return 1;
        }
    }
    else LOGE("RFCOMM socket file descriptor %d is bad!",
              nat->rfcomm_sock);
#endif
    return -1;
}

static void disconnectNative(JNIEnv *env, jobject obj) {
    ALOGV("%s", __FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, obj);
    if (nat->rfcomm_sock >= 0) {
        close(nat->rfcomm_sock);
        nat->rfcomm_sock = -1;
        nat->rfcomm_connected = 0;
    }
#endif
}

static void pretty_log_urc(const char *urc) {
    size_t i;
    bool in_line_break = false;
    char *buf = (char *)calloc(strlen(urc) + 1, sizeof(char));

    strcpy(buf, urc);
    for (i = 0; i < strlen(buf); i++) {
        switch(buf[i]) {
        case '\r':
        case '\n':
            in_line_break = true;
            buf[i] = ' ';
            break;
        default:
            if (in_line_break) {
                in_line_break = false;
                buf[i-1] = '\n';
            }
        }
    }
    IF_ALOGV() ALOG(LOG_VERBOSE, "Bluetooth AT sent", "%s", buf);

    free(buf);
}

static jboolean sendURCNative(JNIEnv *env, jobject obj, jstring urc) {
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, obj);
    if (nat->rfcomm_connected) {
        const char *c_urc = env->GetStringUTFChars(urc, NULL);
        jboolean ret = send_line(nat->rfcomm_sock, c_urc) == 0 ? JNI_TRUE : JNI_FALSE;
        if (ret == JNI_TRUE) pretty_log_urc(c_urc);
        env->ReleaseStringUTFChars(urc, c_urc);
        return ret;
    }
#endif
    return JNI_FALSE;
}

static jstring readNative(JNIEnv *env, jobject obj, jint timeout_ms) {
#ifdef HAVE_BLUETOOTH
    {
        native_data_t *nat = get_native_data(env, obj);
        if (nat->rfcomm_connected) {
            char buf[256];
            const char *ret = get_line(nat->rfcomm_sock,
                                       buf, sizeof(buf),
                                       timeout_ms,
                                       &nat->last_read_err);
            return ret ? env->NewStringUTF(ret) : NULL;
        }
        return NULL;
    }
#else
    return NULL;
#endif
}

static jint getLastReadStatusNative(JNIEnv *env, jobject obj) {
#ifdef HAVE_BLUETOOTH
    {
        native_data_t *nat = get_native_data(env, obj);
        if (nat->rfcomm_connected)
            return (jint)nat->last_read_err;
        return 0;
    }
#else
    return 0;
#endif
}

static JNINativeMethod sMethods[] = {
     /* name, signature, funcPtr */
    {"classInitNative", "()V", (void*)classInitNative},
    {"initializeNativeDataNative", "(I)V", (void *)initializeNativeDataNative},
    {"cleanupNativeDataNative", "()V", (void *)cleanupNativeDataNative},
    {"connectNative", "()Z", (void *)connectNative},
    {"connectAsyncNative", "()I", (void *)connectAsyncNative},
    {"waitForAsyncConnectNative", "(I)I", (void *)waitForAsyncConnectNative},
    {"disconnectNative", "()V", (void *)disconnectNative},
    {"sendURCNative", "(Ljava/lang/String;)Z", (void *)sendURCNative},
    {"readNative", "(I)Ljava/lang/String;", (void *)readNative},
    {"getLastReadStatusNative", "()I", (void *)getLastReadStatusNative},
};

int register_android_bluetooth_HeadsetBase(JNIEnv *env) {
    return AndroidRuntime::registerNativeMethods(env,
            "android/bluetooth/HeadsetBase", sMethods, NELEM(sMethods));
}

} /* namespace android */
