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

#define LOG_TAG "bluetooth_RfcommSocket.cpp"

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
static jfieldID field_mTimeoutRemainingMs;
static jfieldID field_mAcceptTimeoutRemainingMs;
static jfieldID field_mAddress;
static jfieldID field_mPort;

typedef struct {
    jstring address;
    const char *c_address;
    int rfcomm_channel;
    int last_read_err;
    int rfcomm_sock;
    // < 0 -- in progress, 
    //   0 -- not connected
    // > 0 connected
    //     1 input is open
    //     2 output is open
    //     3 both input and output are open
    int rfcomm_connected; 
    int rfcomm_sock_flags;
} native_data_t;

static inline native_data_t * get_native_data(JNIEnv *env, jobject object) {
    return (native_data_t *)(env->GetIntField(object, field_mNativeData));
}

static inline void init_socket_info(
    JNIEnv *env, jobject object,
    native_data_t *nat,
    jstring address,
    jint rfcomm_channel) {
    nat->address = (jstring)env->NewGlobalRef(address);
    nat->c_address = env->GetStringUTFChars(nat->address, NULL);
    nat->rfcomm_channel = (int)rfcomm_channel;
}

static inline void cleanup_socket_info(JNIEnv *env, native_data_t *nat) {
    if (nat->c_address != NULL) {
        env->ReleaseStringUTFChars(nat->address, nat->c_address);
        env->DeleteGlobalRef(nat->address);
        nat->c_address = NULL;
    }
}
#endif

static void classInitNative(JNIEnv* env, jclass clazz) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    field_mNativeData = get_field(env, clazz, "mNativeData", "I");
    field_mTimeoutRemainingMs = get_field(env, clazz, "mTimeoutRemainingMs", "I");
    field_mAcceptTimeoutRemainingMs = get_field(env, clazz, "mAcceptTimeoutRemainingMs", "I");
    field_mAddress = get_field(env, clazz, "mAddress", "Ljava/lang/String;");
    field_mPort = get_field(env, clazz, "mPort", "I");
#endif
}

static void initializeNativeDataNative(JNIEnv* env, jobject object) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH

    native_data_t *nat = (native_data_t *)calloc(1, sizeof(native_data_t));
    if (nat == NULL) {
        LOGE("%s: out of memory!", __FUNCTION__);
        return;
    }

    env->SetIntField(object, field_mNativeData, (jint)nat);
    nat->rfcomm_sock = -1;
    nat->rfcomm_connected = 0;
#endif
}

static void cleanupNativeDataNative(JNIEnv* env, jobject object) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, object);
    if (nat) {
        free(nat);
    }
#endif
}

static jobject createNative(JNIEnv *env, jobject obj) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    int lm;
    native_data_t *nat = get_native_data(env, obj);
    nat->rfcomm_sock = socket(PF_BLUETOOTH, SOCK_STREAM, BTPROTO_RFCOMM);

    if (nat->rfcomm_sock < 0) {
        LOGE("%s: Could not create RFCOMM socket: %s\n", __FUNCTION__,
             strerror(errno));
        return NULL;
    }
        
    lm = RFCOMM_LM_AUTH | RFCOMM_LM_ENCRYPT;

    if (lm && setsockopt(nat->rfcomm_sock, SOL_RFCOMM, RFCOMM_LM, &lm,
                sizeof(lm)) < 0) {
        LOGE("%s: Can't set RFCOMM link mode", __FUNCTION__);
        close(nat->rfcomm_sock);
        return NULL;
    }

    return jniCreateFileDescriptor(env, nat->rfcomm_sock);
#else
    return NULL;
#endif
}

static void destroyNative(JNIEnv *env, jobject obj) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, obj);
    cleanup_socket_info(env, nat);
    if (nat->rfcomm_sock >= 0) {
        close(nat->rfcomm_sock);
        nat->rfcomm_sock = -1;
    }
#endif
}


static jboolean connectNative(JNIEnv *env, jobject obj,
                              jstring address, jint port) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, obj);

    if (nat->rfcomm_sock >= 0) {
        if (nat->rfcomm_connected) {
            LOGI("RFCOMM socket: %s.",
                 (nat->rfcomm_connected > 0) ? "already connected" : "connection is in progress");
            return JNI_TRUE;
        }

        init_socket_info(env, obj, nat, address, port);

        struct sockaddr_rc addr;
        memset(&addr, 0, sizeof(struct sockaddr_rc));
        get_bdaddr(nat->c_address, &addr.rc_bdaddr);
        addr.rc_channel = nat->rfcomm_channel;
        addr.rc_family = AF_BLUETOOTH;
        nat->rfcomm_connected = 0;

        while (nat->rfcomm_connected == 0) {
            if (connect(nat->rfcomm_sock, (struct sockaddr *)&addr,
                    sizeof(addr)) < 0) {
                if (errno == EINTR) continue;
                LOGE("connect error: %s (%d)\n", strerror(errno), errno);
                break;
            } else {
                nat->rfcomm_connected = 3; // input and output
            }
        }
    } else {
        LOGE("%s: socket(RFCOMM) error: socket not created", __FUNCTION__);
    }

    if (nat->rfcomm_connected > 0) {
        env->SetIntField(obj, field_mPort, port);
        return JNI_TRUE;
    }
#endif
    return JNI_FALSE;
}

static jboolean connectAsyncNative(JNIEnv *env, jobject obj,
                                   jstring address, jint port) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, obj);

    if (nat->rfcomm_sock < 0) {
        LOGE("%s: socket(RFCOMM) error: socket not created", __FUNCTION__);
        return JNI_FALSE;
    }

    if (nat->rfcomm_connected) {
        LOGI("RFCOMM socket: %s.",
             (nat->rfcomm_connected > 0) ?
             "already connected" : "connection is in progress");
        return JNI_TRUE;
    }

    init_socket_info(env, obj, nat, address, port);

    struct sockaddr_rc addr;
    memset(&addr, 0, sizeof(struct sockaddr_rc));
    get_bdaddr(nat->c_address, &addr.rc_bdaddr);
    addr.rc_channel = nat->rfcomm_channel;
    addr.rc_family = AF_BLUETOOTH;

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
            nat->rfcomm_connected = 3;
            LOGI("RFCOMM async connect immediately successful");
            env->SetIntField(obj, field_mPort, port);
            return JNI_TRUE;
        }
        else if (rc < 0) {
            if (errno == EINPROGRESS || errno == EAGAIN)
                {
                    LOGI("RFCOMM async connect is in progress (%s)",
                         strerror(errno));
                    nat->rfcomm_connected = -1;
                    env->SetIntField(obj, field_mPort, port);
                    return JNI_TRUE;
                }
            else
                {
                    LOGE("RFCOMM async connect error (%d): %s (%d)",
                         nat->rfcomm_sock, strerror(errno), errno);
                    return JNI_FALSE;
                }
        }
    } // fcntl(nat->rfcomm_sock ...)
#endif
    return JNI_FALSE;
}

static jboolean interruptAsyncConnectNative(JNIEnv *env, jobject obj) {
    //WRITEME
    return JNI_TRUE;
}

static jint waitForAsyncConnectNative(JNIEnv *env, jobject obj,
                                      jint timeout_ms) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    struct sockaddr_rc addr;
    native_data_t *nat = get_native_data(env, obj);

    env->SetIntField(obj, field_mTimeoutRemainingMs, timeout_ms);

    if (nat->rfcomm_sock < 0) {
        LOGE("%s: socket(RFCOMM) error: socket not created", __FUNCTION__);
        return -1;
    }

    if (nat->rfcomm_connected > 0) {
        LOGI("%s: RFCOMM is already connected!", __FUNCTION__);
        return 1;
    }

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
        LOGI("Remaining time %ldms", (long)remaining);
        env->SetIntField(obj, field_mTimeoutRemainingMs,
                         remaining);
    }

    if (n <= 0) {
        if (n < 0)  {
            LOGE("select() on RFCOMM socket: %s (%d)",
                 strerror(errno),
                 errno);
            return -1;
        }
        return 0;
    }
    /* n must be equal to 1 and either rset or wset must have the
       file descriptor set. */
    LOGI("select() returned %d.", n);
    if (FD_ISSET(nat->rfcomm_sock, &rset) ||
        FD_ISSET(nat->rfcomm_sock, &wset)) {
        /* A trial async read() will tell us if everything is OK. */
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
            return -1;
        }
        /* Restore the blocking properties of the socket. */
        fcntl(nat->rfcomm_sock, F_SETFL, nat->rfcomm_sock_flags);
        LOGI("Successful RFCOMM socket connect.");
        nat->rfcomm_connected = 3; // input and output
        return 1;
    }
#endif
    return -1;
}

static jboolean shutdownNative(JNIEnv *env, jobject obj,
                jboolean shutdownInput) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    /* NOTE: If you change the bcode to modify nat, make sure you 
       add synchronize(this) to the method calling this native
       method. 
    */
    native_data_t *nat = get_native_data(env, obj);
    if (nat->rfcomm_sock < 0) {
        LOGE("socket(RFCOMM) error: socket not created");
        return JNI_FALSE;
    }
    int rc = shutdown(nat->rfcomm_sock, 
            shutdownInput ? SHUT_RD : SHUT_WR);
    if (!rc) {
        nat->rfcomm_connected &= 
            shutdownInput ? ~1 : ~2;
        return JNI_TRUE;
    }
#endif
    return JNI_FALSE;
}

static jint isConnectedNative(JNIEnv *env, jobject obj) {
    LOGI(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    const native_data_t *nat = get_native_data(env, obj);
    return nat->rfcomm_connected;
#endif
    return 0;
}

//@@@@@@@@@ bind to device???
static jboolean bindNative(JNIEnv *env, jobject obj, jstring device,
                           jint port) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH

    /* NOTE: If you change the code to modify nat, make sure you
       add synchronize(this) to the method calling this native
       method.
    */
    const native_data_t *nat = get_native_data(env, obj);
    if (nat->rfcomm_sock < 0) {
        LOGE("socket(RFCOMM) error: socket not created");
        return JNI_FALSE;
    }

    struct sockaddr_rc laddr;
    int lm;

    lm = 0;
/*
    lm |= RFCOMM_LM_MASTER;
    lm |= RFCOMM_LM_AUTH;
    lm |= RFCOMM_LM_ENCRYPT;
    lm |= RFCOMM_LM_SECURE;
*/

    if (lm && setsockopt(nat->rfcomm_sock, SOL_RFCOMM, RFCOMM_LM, &lm, sizeof(lm)) < 0) {
        LOGE("Can't set RFCOMM link mode");
        return JNI_FALSE;
    }

    laddr.rc_family = AF_BLUETOOTH;
    bacpy(&laddr.rc_bdaddr, BDADDR_ANY);
    laddr.rc_channel = port;

    if (bind(nat->rfcomm_sock, (struct sockaddr *)&laddr, sizeof(laddr)) < 0) {
        LOGE("Can't bind RFCOMM socket");
        return JNI_FALSE;
    }

    env->SetIntField(obj, field_mPort, port);

    return JNI_TRUE;
#endif
    return JNI_FALSE;
}

static jboolean listenNative(JNIEnv *env, jobject obj, jint backlog) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    /* NOTE: If you change the code to modify nat, make sure you
       add synchronize(this) to the method calling this native
       method.
    */
    const native_data_t *nat = get_native_data(env, obj);
    if (nat->rfcomm_sock < 0) {
        LOGE("socket(RFCOMM) error: socket not created");
        return JNI_FALSE;
    }
    return listen(nat->rfcomm_sock, backlog) < 0 ? JNI_FALSE : JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

static int set_nb(int sk, bool nb) {
    int flags = fcntl(sk, F_GETFL);
    if (flags < 0) {
        LOGE("Can't get socket flags with fcntl(): %s (%d)",
             strerror(errno), errno);
        close(sk);
        return -1;
    }
    flags &= ~O_NONBLOCK;
    if (nb) flags |= O_NONBLOCK;
    int status = fcntl(sk, F_SETFL, flags);
    if (status < 0) {
        LOGE("Can't set socket to nonblocking mode with fcntl(): %s (%d)",
             strerror(errno), errno);
        close(sk);
        return -1;
    }
    return 0;
}

// Note: the code should check at a higher level to see whether
// listen() has been called.
#ifdef HAVE_BLUETOOTH
static int do_accept(JNIEnv* env, jobject object, int sock,
                     jobject newsock,
                     jfieldID out_address,
                     bool must_succeed) {

    if (must_succeed && set_nb(sock, true) < 0)
        return -1;

    struct sockaddr_rc raddr;
    int alen = sizeof(raddr);
    int nsk = accept(sock, (struct sockaddr *) &raddr, &alen);
    if (nsk < 0) {
        LOGE("Error on accept from socket fd %d: %s (%d).",
             sock,
             strerror(errno),
             errno);
        if (must_succeed) set_nb(sock, false);
        return -1;
    }

    char addr[BTADDR_SIZE];
    get_bdaddr_as_string(&raddr.rc_bdaddr, addr);
    env->SetObjectField(newsock, out_address, env->NewStringUTF(addr));

    LOGI("Successful accept() on AG socket %d: new socket %d, address %s, RFCOMM channel %d",
         sock,
         nsk,
         addr,
         raddr.rc_channel);
    if (must_succeed) set_nb(sock, false);
    return nsk;
}
#endif /*HAVE_BLUETOOTH*/

static jobject acceptNative(JNIEnv *env, jobject obj,
                            jobject newsock, jint timeoutMs) {
    LOGV(__FUNCTION__);
#ifdef HAVE_BLUETOOTH
    native_data_t *nat = get_native_data(env, obj);
    if (nat->rfcomm_sock < 0) {
        LOGE("socket(RFCOMM) error: socket not created");
        return JNI_FALSE;
    }

    if (newsock == NULL) {
        LOGE("%s: newsock = NULL\n", __FUNCTION__);
        return JNI_FALSE;
    }

    int nsk = -1;
    if (timeoutMs < 0) {
        /* block until accept() succeeds */
        nsk = do_accept(env, obj, nat->rfcomm_sock,
                        newsock, field_mAddress, false);
        if (nsk < 0) {
            return NULL;
        }
    }
    else {
        /* wait with a timeout */

        struct pollfd fds;
        fds.fd = nat->rfcomm_sock;
        fds.events = POLLIN | POLLPRI | POLLOUT | POLLERR;

        env->SetIntField(obj, field_mAcceptTimeoutRemainingMs, 0);
        int n = poll(&fds, 1, timeoutMs);
        if (n <= 0) {
            if (n < 0)  {
                LOGE("listening poll() on RFCOMM socket: %s (%d)",
                     strerror(errno),
                     errno);
                env->SetIntField(obj, field_mAcceptTimeoutRemainingMs, timeoutMs);
            }
            else {
                LOGI("listening poll() on RFCOMM socket timed out");
            }
            return NULL;
        }

        LOGI("listening poll() on RFCOMM socket returned %d", n);
        if (fds.fd == nat->rfcomm_sock) {
            if (fds.revents & (POLLIN | POLLPRI | POLLOUT)) {
                LOGI("Accepting connection.\n");
                nsk = do_accept(env, obj, nat->rfcomm_sock,
                                newsock, field_mAddress, true);
                if (nsk < 0) {
                    return NULL;
                }
            }
        }
    }

    LOGI("Connection accepted, new socket fd = %d.", nsk);
    native_data_t *newnat = get_native_data(env, newsock);
    newnat->rfcomm_sock = nsk;
    newnat->rfcomm_connected = 3;
    return jniCreateFileDescriptor(env, nsk);
#else
    return NULL;
#endif
}

static JNINativeMethod sMethods[] = {
     /* name, signature, funcPtr */
    {"classInitNative", "()V", (void*)classInitNative},
    {"initializeNativeDataNative", "()V", (void *)initializeNativeDataNative},
    {"cleanupNativeDataNative", "()V", (void *)cleanupNativeDataNative},

    {"createNative", "()Ljava/io/FileDescriptor;", (void *)createNative},
    {"destroyNative", "()V", (void *)destroyNative},
    {"connectNative", "(Ljava/lang/String;I)Z", (void *)connectNative},
    {"connectAsyncNative", "(Ljava/lang/String;I)Z", (void *)connectAsyncNative},
    {"interruptAsyncConnectNative", "()Z", (void *)interruptAsyncConnectNative},
    {"waitForAsyncConnectNative", "(I)I", (void *)waitForAsyncConnectNative},
    {"shutdownNative", "(Z)Z", (void *)shutdownNative},
    {"isConnectedNative", "()I", (void *)isConnectedNative},

    {"bindNative", "(Ljava/lang/String;I)Z", (void*)bindNative},
    {"listenNative", "(I)Z", (void*)listenNative},
    {"acceptNative", "(Landroid/bluetooth/RfcommSocket;I)Ljava/io/FileDescriptor;", (void*)acceptNative},
};

int register_android_bluetooth_RfcommSocket(JNIEnv *env) {
    return AndroidRuntime::registerNativeMethods(env,
        "android/bluetooth/RfcommSocket", sMethods, NELEM(sMethods));
}

} /* namespace android */
