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

#define LOG_TAG "BluetoothAudioGateway.cpp"

#include "android_bluetooth_common.h"
#include "android_bluetooth_c.h"
#include "android_runtime/AndroidRuntime.h"
#include "JNIHelp.h"
#include "jni.h"
#include "utils/Log.h"
#include "utils/misc.h"

#define USE_ACCEPT_DIRECTLY (0)
#define USE_SELECT (0) /* 1 for select(), 0 for poll(); used only when
                          USE_ACCEPT_DIRECTLY == 0 */

#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <errno.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/types.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/uio.h>
#include <ctype.h>

#if USE_SELECT
#include <sys/select.h>
#else
#include <sys/poll.h>
#endif

#ifdef HAVE_BLUETOOTH
#include <bluetooth/bluetooth.h>
#include <bluetooth/rfcomm.h>
#include <bluetooth/sco.h>
#endif

namespace android {

#ifdef HAVE_BLUETOOTH
static jfieldID field_mNativeData;
    /* in */
static jfieldID field_mHandsfreeAgRfcommChannel;
static jfieldID field_mHeadsetAgRfcommChannel;
    /* out */
static jfieldID field_mTimeoutRemainingMs; /* out */

static jfieldID field_mConnectingHeadsetAddress;
static jfieldID field_mConnectingHeadsetRfcommChannel; /* -1 when not connected */
static jfieldID field_mConnectingHeadsetSocketFd;

static jfieldID field_mConnectingHandsfreeAddress;
static jfieldID field_mConnectingHandsfreeRfcommChannel; /* -1 when not connected */
static jfieldID field_mConnectingHandsfreeSocketFd;


typedef struct {
    int hcidev;
    int hf_ag_rfcomm_channel;
    int hs_ag_rfcomm_channel;
    int hf_ag_rfcomm_sock;
    int hs_ag_rfcomm_sock;
} native_data_t;

static inline native_data_t * get_native_data(JNIEnv *env, jobject object) {
    return (native_data_t *)(env->GetIntField(object,
                                                 field_mNativeData));
}

static int setup_listening_socket(int dev, int channel);
#endif

static void classInitNative(JNIEnv* env, jclass clazz) {
    ALOGV("%s", __FUNCTION__);
#ifdef HAVE_BLUETOOTH

    /* in */
    field_mNativeData = get_field(env, clazz, "mNativeData", "I");
    field_mHandsfreeAgRfcommChannel =
        get_field(env, clazz, "mHandsfreeAgRfcommChannel", "I");
    field_mHeadsetAgRfcommChannel =
        get_field(env, clazz, "mHeadsetAgRfcommChannel", "I");

    /* out */
    field_mConnectingHeadsetAddress =
        get_field(env, clazz,
                  "mConnectingHeadsetAddress", "Ljava/lang/String;");
    field_mConnectingHeadsetRfcommChannel =
        get_field(env, clazz, "mConnectingHeadsetRfcommChannel", "I");
    field_mConnectingHeadsetSocketFd =
        get_field(env, clazz, "mConnectingHeadsetSocketFd", "I");

    field_mConnectingHandsfreeAddress =
        get_field(env, clazz,
                  "mConnectingHandsfreeAddress", "Ljava/lang/String;");
    field_mConnectingHandsfreeRfcommChannel =
        get_field(env, clazz, "mConnectingHandsfreeRfcommChannel", "I");
    field_mConnectingHandsfreeSocketFd =
        get_field(env, clazz, "mConnectingHandsfreeSocketFd", "I");

    field_mTimeoutRemainingMs =
        get_field(env, clazz, "mTimeoutRemainingMs", "I");
#endif
}

static void initializeNativeDataNative(JNIEnv* env, jobject object) {
    ALOGV("%s", __FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = (native_data_t *)calloc(1, sizeof(native_data_t));
    if (NULL == nat) {
        ALOGE("%s: out of memory!", __FUNCTION__);
        return;
    }

    nat->hcidev = BLUETOOTH_ADAPTER_HCI_NUM;

    env->SetIntField(object, field_mNativeData, (jint)nat);
    nat->hf_ag_rfcomm_channel =
        env->GetIntField(object, field_mHandsfreeAgRfcommChannel);
    nat->hs_ag_rfcomm_channel =
        env->GetIntField(object, field_mHeadsetAgRfcommChannel);
    ALOGV("HF RFCOMM channel = %d.", nat->hf_ag_rfcomm_channel);
    ALOGV("HS RFCOMM channel = %d.", nat->hs_ag_rfcomm_channel);

    /* Set the default values of these to -1. */
    env->SetIntField(object, field_mConnectingHeadsetRfcommChannel, -1);
    env->SetIntField(object, field_mConnectingHandsfreeRfcommChannel, -1);

    nat->hf_ag_rfcomm_sock = -1;
    nat->hs_ag_rfcomm_sock = -1;
#endif
}

static void cleanupNativeDataNative(JNIEnv* env, jobject object) {
    ALOGV("%s", __FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        free(nat);
    }
#endif
}

#ifdef HAVE_BLUETOOTH

#if USE_ACCEPT_DIRECTLY==0
static int set_nb(int sk, bool nb) {
    int flags = fcntl(sk, F_GETFL);
    if (flags < 0) {
        ALOGE("Can't get socket flags with fcntl(): %s (%d)",
             strerror(errno), errno);
        close(sk);
        return -1;
    }
    flags &= ~O_NONBLOCK;
    if (nb) flags |= O_NONBLOCK;
    int status = fcntl(sk, F_SETFL, flags);
    if (status < 0) {
        ALOGE("Can't set socket to nonblocking mode with fcntl(): %s (%d)",
             strerror(errno), errno);
        close(sk);
        return -1;
    }
    return 0;
}
#endif /*USE_ACCEPT_DIRECTLY==0*/

static int do_accept(JNIEnv* env, jobject object, int ag_fd,
                     jfieldID out_fd,
                     jfieldID out_address,
                     jfieldID out_channel) {

#if USE_ACCEPT_DIRECTLY==0
    if (set_nb(ag_fd, true) < 0)
        return -1;
#endif

    struct sockaddr_rc raddr;
    int alen = sizeof(raddr);
    int nsk = accept(ag_fd, (struct sockaddr *) &raddr, &alen);
    if (nsk < 0) {
        ALOGE("Error on accept from socket fd %d: %s (%d).",
             ag_fd,
             strerror(errno),
             errno);
#if USE_ACCEPT_DIRECTLY==0
        set_nb(ag_fd, false);
#endif
        return -1;
    }

    env->SetIntField(object, out_fd, nsk);
    env->SetIntField(object, out_channel, raddr.rc_channel);

    char addr[BTADDR_SIZE];
    get_bdaddr_as_string(&raddr.rc_bdaddr, addr);
    env->SetObjectField(object, out_address, env->NewStringUTF(addr));

    ALOGI("Successful accept() on AG socket %d: new socket %d, address %s, RFCOMM channel %d",
         ag_fd,
         nsk,
         addr,
         raddr.rc_channel);
#if USE_ACCEPT_DIRECTLY==0
    set_nb(ag_fd, false);
#endif
    return 0;
}

#if USE_SELECT
static inline int on_accept_set_fields(JNIEnv* env, jobject object,
                                       fd_set *rset, int ag_fd,
                                       jfieldID out_fd,
                                       jfieldID out_address,
                                       jfieldID out_channel) {

    env->SetIntField(object, out_channel, -1);

    if (ag_fd >= 0 && FD_ISSET(ag_fd, &rset)) {
        return do_accept(env, object, ag_fd,
                         out_fd, out_address, out_channel);
    }
    else {
        ALOGI("fd = %d, FD_ISSET() = %d",
             ag_fd,
             FD_ISSET(ag_fd, &rset));
        if (ag_fd >= 0 && !FD_ISSET(ag_fd, &rset)) {
            ALOGE("WTF???");
            return -1;
        }
    }

    return 0;
}
#endif
#endif /* HAVE_BLUETOOTH */

static jboolean waitForHandsfreeConnectNative(JNIEnv* env, jobject object,
                                              jint timeout_ms) {
//    ALOGV("%s", __FUNCTION__);
#ifdef HAVE_BLUETOOTH

    env->SetIntField(object, field_mTimeoutRemainingMs, timeout_ms);

    int n = 0;
    native_data_t *nat = get_native_data(env, object);
#if USE_ACCEPT_DIRECTLY
    if (nat->hf_ag_rfcomm_channel > 0) {
        ALOGI("Setting HF AG server socket to RFCOMM port %d!",
             nat->hf_ag_rfcomm_channel);
        struct timeval tv;
        int len = sizeof(tv);
        if (getsockopt(nat->hf_ag_rfcomm_channel,
                       SOL_SOCKET, SO_RCVTIMEO, &tv, &len) < 0) {
            ALOGE("getsockopt(%d, SOL_SOCKET, SO_RCVTIMEO): %s (%d)",
                 nat->hf_ag_rfcomm_channel,
                 strerror(errno),
                 errno);
            return JNI_FALSE;
        }
        ALOGI("Current HF AG server socket RCVTIMEO is (%d(s), %d(us))!",
             (int)tv.tv_sec, (int)tv.tv_usec);
        if (timeout_ms >= 0) {
            tv.tv_sec = timeout_ms / 1000;
            tv.tv_usec = 1000 * (timeout_ms % 1000);
            if (setsockopt(nat->hf_ag_rfcomm_channel,
                           SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv)) < 0) {
                ALOGE("setsockopt(%d, SOL_SOCKET, SO_RCVTIMEO): %s (%d)",
                     nat->hf_ag_rfcomm_channel,
                     strerror(errno),
                     errno);
                return JNI_FALSE;
            }
            ALOGI("Changed HF AG server socket RCVTIMEO to (%d(s), %d(us))!",
                 (int)tv.tv_sec, (int)tv.tv_usec);
        }

        if (!do_accept(env, object, nat->hf_ag_rfcomm_sock,
                       field_mConnectingHandsfreeSocketFd,
                       field_mConnectingHandsfreeAddress,
                       field_mConnectingHandsfreeRfcommChannel))
        {
            env->SetIntField(object, field_mTimeoutRemainingMs, 0);
            return JNI_TRUE;
        }
        return JNI_FALSE;
    }
#else
#if USE_SELECT
    fd_set rset;
    FD_ZERO(&rset);
    int cnt = 0;
    if (nat->hf_ag_rfcomm_channel > 0) {
        ALOGI("Setting HF AG server socket to RFCOMM port %d!",
             nat->hf_ag_rfcomm_channel);
        cnt++;
        FD_SET(nat->hf_ag_rfcomm_sock, &rset);
    }
    if (nat->hs_ag_rfcomm_channel > 0) {
        ALOGI("Setting HS AG server socket to RFCOMM port %d!",
             nat->hs_ag_rfcomm_channel);
        cnt++;
        FD_SET(nat->hs_ag_rfcomm_sock, &rset);
    }
    if (cnt == 0) {
        ALOGE("Neither HF nor HS listening sockets are open!");
        return JNI_FALSE;
    }

    struct timeval to;
    if (timeout_ms >= 0) {
        to.tv_sec = timeout_ms / 1000;
        to.tv_usec = 1000 * (timeout_ms % 1000);
    }
    n = select(MAX(nat->hf_ag_rfcomm_sock,
                       nat->hs_ag_rfcomm_sock) + 1,
                   &rset,
                   NULL,
                   NULL,
                   (timeout_ms < 0 ? NULL : &to));
    if (timeout_ms > 0) {
        jint remaining = to.tv_sec*1000 + to.tv_usec/1000;
        ALOGI("Remaining time %ldms", (long)remaining);
        env->SetIntField(object, field_mTimeoutRemainingMs,
                         remaining);
    }

    ALOGI("listening select() returned %d", n);

    if (n <= 0) {
        if (n < 0)  {
            ALOGE("listening select() on RFCOMM sockets: %s (%d)",
                 strerror(errno),
                 errno);
        }
        return JNI_FALSE;
    }

    n = on_accept_set_fields(env, object,
                             &rset, nat->hf_ag_rfcomm_sock,
                             field_mConnectingHandsfreeSocketFd,
                             field_mConnectingHandsfreeAddress,
                             field_mConnectingHandsfreeRfcommChannel);

    n += on_accept_set_fields(env, object,
                              &rset, nat->hs_ag_rfcomm_sock,
                              field_mConnectingHeadsetSocketFd,
                              field_mConnectingHeadsetAddress,
                              field_mConnectingHeadsetRfcommChannel);

    return !n ? JNI_TRUE : JNI_FALSE;
#else
    struct pollfd fds[2];
    int cnt = 0;
    if (nat->hf_ag_rfcomm_channel > 0) {
//        ALOGI("Setting HF AG server socket %d to RFCOMM port %d!",
//             nat->hf_ag_rfcomm_sock,
//             nat->hf_ag_rfcomm_channel);
        fds[cnt].fd = nat->hf_ag_rfcomm_sock;
        fds[cnt].events = POLLIN | POLLPRI | POLLOUT | POLLERR;
        cnt++;
    }
    if (nat->hs_ag_rfcomm_channel > 0) {
//        ALOGI("Setting HS AG server socket %d to RFCOMM port %d!",
//             nat->hs_ag_rfcomm_sock,
//             nat->hs_ag_rfcomm_channel);
        fds[cnt].fd = nat->hs_ag_rfcomm_sock;
        fds[cnt].events = POLLIN | POLLPRI | POLLOUT | POLLERR;
        cnt++;
    }
    if (cnt == 0) {
        ALOGE("Neither HF nor HS listening sockets are open!");
        return JNI_FALSE;
    }
    n = poll(fds, cnt, timeout_ms);
    if (n <= 0) {
        if (n < 0)  {
            ALOGE("listening poll() on RFCOMM sockets: %s (%d)",
                 strerror(errno),
                 errno);
        }
        else {
            env->SetIntField(object, field_mTimeoutRemainingMs, 0);
//            ALOGI("listening poll() on RFCOMM socket timed out");
        }
        return JNI_FALSE;
    }

    //ALOGI("listening poll() on RFCOMM socket returned %d", n);
    int err = 0;
    for (cnt = 0; cnt < (int)(sizeof(fds)/sizeof(fds[0])); cnt++) {
        //ALOGI("Poll on fd %d revent = %d.", fds[cnt].fd, fds[cnt].revents);
        if (fds[cnt].fd == nat->hf_ag_rfcomm_sock) {
            if (fds[cnt].revents & (POLLIN | POLLPRI | POLLOUT)) {
                ALOGI("Accepting HF connection.\n");
                err += do_accept(env, object, fds[cnt].fd,
                               field_mConnectingHandsfreeSocketFd,
                               field_mConnectingHandsfreeAddress,
                               field_mConnectingHandsfreeRfcommChannel);
                n--;
            }
        }
        else if (fds[cnt].fd == nat->hs_ag_rfcomm_sock) {
            if (fds[cnt].revents & (POLLIN | POLLPRI | POLLOUT)) {
                ALOGI("Accepting HS connection.\n");
                err += do_accept(env, object, fds[cnt].fd,
                               field_mConnectingHeadsetSocketFd,
                               field_mConnectingHeadsetAddress,
                               field_mConnectingHeadsetRfcommChannel);
                n--;
            }
        }
    } /* for */

    if (n != 0) {
        ALOGI("Bogus poll(): %d fake pollfd entrie(s)!", n);
        return JNI_FALSE;
    }

    return !err ? JNI_TRUE : JNI_FALSE;
#endif /* USE_SELECT */
#endif /* USE_ACCEPT_DIRECTLY */
#else
    return JNI_FALSE;
#endif /* HAVE_BLUETOOTH */
}

static jboolean setUpListeningSocketsNative(JNIEnv* env, jobject object) {
    ALOGV("%s", __FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, object);

    nat->hf_ag_rfcomm_sock =
        setup_listening_socket(nat->hcidev, nat->hf_ag_rfcomm_channel);
    if (nat->hf_ag_rfcomm_sock < 0)
        return JNI_FALSE;

    nat->hs_ag_rfcomm_sock =
        setup_listening_socket(nat->hcidev, nat->hs_ag_rfcomm_channel);
    if (nat->hs_ag_rfcomm_sock < 0) {
        close(nat->hf_ag_rfcomm_sock);
        nat->hf_ag_rfcomm_sock = -1;
        return JNI_FALSE;
    }

    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif /* HAVE_BLUETOOTH */
}

#ifdef HAVE_BLUETOOTH
static int setup_listening_socket(int dev, int channel) {
    struct sockaddr_rc laddr;
    int sk, lm;

    sk = socket(AF_BLUETOOTH, SOCK_STREAM, BTPROTO_RFCOMM);
    if (sk < 0) {
        ALOGE("Can't create RFCOMM socket");
        return -1;
    }

    if (debug_no_encrypt()) {
        lm = RFCOMM_LM_AUTH;
    } else {
        lm = RFCOMM_LM_AUTH | RFCOMM_LM_ENCRYPT;
    }

    if (lm && setsockopt(sk, SOL_RFCOMM, RFCOMM_LM, &lm, sizeof(lm)) < 0) {
        ALOGE("Can't set RFCOMM link mode");
        close(sk);
        return -1;
    }

    laddr.rc_family = AF_BLUETOOTH;
    bdaddr_t any = android_bluetooth_bdaddr_any();
    memcpy(&laddr.rc_bdaddr, &any, sizeof(bdaddr_t));
    laddr.rc_channel = channel;

    if (bind(sk, (struct sockaddr *)&laddr, sizeof(laddr)) < 0) {
        ALOGE("Can't bind RFCOMM socket");
        close(sk);
        return -1;
    }

    listen(sk, 10);
    return sk;
}
#endif /* HAVE_BLUETOOTH */

/*
    private native void tearDownListeningSocketsNative();
*/
static void tearDownListeningSocketsNative(JNIEnv *env, jobject object) {
    ALOGV("%s", __FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, object);

    if (nat->hf_ag_rfcomm_sock > 0) {
        if (close(nat->hf_ag_rfcomm_sock) < 0) {
            ALOGE("Could not close HF server socket: %s (%d)\n",
                 strerror(errno), errno);
        }
        nat->hf_ag_rfcomm_sock = -1;
    }
    if (nat->hs_ag_rfcomm_sock > 0) {
        if (close(nat->hs_ag_rfcomm_sock) < 0) {
            ALOGE("Could not close HS server socket: %s (%d)\n",
                 strerror(errno), errno);
        }
        nat->hs_ag_rfcomm_sock = -1;
    }
#endif /* HAVE_BLUETOOTH */
}

static JNINativeMethod sMethods[] = {
     /* name, signature, funcPtr */

    {"classInitNative", "()V", (void*)classInitNative},
    {"initializeNativeDataNative", "()V", (void *)initializeNativeDataNative},
    {"cleanupNativeDataNative", "()V", (void *)cleanupNativeDataNative},

    {"setUpListeningSocketsNative", "()Z", (void *)setUpListeningSocketsNative},
    {"tearDownListeningSocketsNative", "()V", (void *)tearDownListeningSocketsNative},
    {"waitForHandsfreeConnectNative", "(I)Z", (void *)waitForHandsfreeConnectNative},
};

int register_android_bluetooth_BluetoothAudioGateway(JNIEnv *env) {
    return AndroidRuntime::registerNativeMethods(env,
            "android/bluetooth/BluetoothAudioGateway", sMethods,
            NELEM(sMethods));
}

} /* namespace android */
