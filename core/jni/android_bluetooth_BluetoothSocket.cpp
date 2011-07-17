/*
 * Copyright 2009, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "BluetoothSocket.cpp"

#include "android_bluetooth_common.h"
#include "android_bluetooth_c.h"
#include "android_runtime/AndroidRuntime.h"
#include "JNIHelp.h"
#include "utils/Log.h"
#include "cutils/abort_socket.h"

#include <stdlib.h>
#include <errno.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/ioctl.h>

#ifdef HAVE_BLUETOOTH
#include <bluetooth/bluetooth.h>
#include <bluetooth/rfcomm.h>
#include <bluetooth/l2cap.h>
#include <bluetooth/sco.h>
#endif

#define TYPE_AS_STR(t) \
    ((t) == TYPE_RFCOMM ? "RFCOMM" : ((t) == TYPE_SCO ? "SCO" : "L2CAP"))

namespace android {

static jfieldID  field_mAuth;     /* read-only */
static jfieldID  field_mEncrypt;  /* read-only */
static jfieldID  field_mType;     /* read-only */
static jfieldID  field_mAddress;  /* read-only */
static jfieldID  field_mPort;     /* read-only */
static jfieldID  field_mSocketData;
static jmethodID method_BluetoothSocket_ctor;
static jclass    class_BluetoothSocket;

/* Keep TYPE_RFCOMM etc in sync with BluetoothSocket.java */
static const int TYPE_RFCOMM = 1;
static const int TYPE_SCO = 2;
static const int TYPE_L2CAP = 3;  // TODO: Test l2cap code paths

static const int RFCOMM_SO_SNDBUF = 70 * 1024;  // 70 KB send buffer

static struct asocket *get_socketData(JNIEnv *env, jobject obj) {
    struct asocket *s =
            (struct asocket *) env->GetIntField(obj, field_mSocketData);
    if (!s)
        jniThrowException(env, "java/io/IOException", "null socketData");
    return s;
}

static void initSocketFromFdNative(JNIEnv *env, jobject obj, jint fd) {
#ifdef HAVE_BLUETOOTH
    LOGV("%s", __FUNCTION__);

    struct asocket *s = asocket_init(fd);

    if (!s) {
        LOGV("asocket_init() failed, throwing");
        jniThrowIOException(env, errno);
        return;
    }

    env->SetIntField(obj, field_mSocketData, (jint)s);

    return;
#endif
    jniThrowIOException(env, ENOSYS);
}

static void initSocketNative(JNIEnv *env, jobject obj) {
#ifdef HAVE_BLUETOOTH
    LOGV("%s", __FUNCTION__);

    int fd;
    int lm = 0;
    int sndbuf;
    jboolean auth;
    jboolean encrypt;
    jint type;

    type = env->GetIntField(obj, field_mType);

    switch (type) {
    case TYPE_RFCOMM:
        fd = socket(PF_BLUETOOTH, SOCK_STREAM, BTPROTO_RFCOMM);
        break;
    case TYPE_SCO:
        fd = socket(PF_BLUETOOTH, SOCK_SEQPACKET, BTPROTO_SCO);
        break;
    case TYPE_L2CAP:
        fd = socket(PF_BLUETOOTH, SOCK_SEQPACKET, BTPROTO_L2CAP);
        break;
    default:
        jniThrowIOException(env, ENOSYS);
        return;
    }

    if (fd < 0) {
        LOGV("socket() failed, throwing");
        jniThrowIOException(env, errno);
        return;
    }

    auth = env->GetBooleanField(obj, field_mAuth);
    encrypt = env->GetBooleanField(obj, field_mEncrypt);

    /* kernel does not yet support LM for SCO */
    switch (type) {
    case TYPE_RFCOMM:
        lm |= auth ? RFCOMM_LM_AUTH : 0;
        lm |= encrypt ? RFCOMM_LM_ENCRYPT : 0;
        lm |= (auth && encrypt) ? RFCOMM_LM_SECURE : 0;
        break;
    case TYPE_L2CAP:
        lm |= auth ? L2CAP_LM_AUTH : 0;
        lm |= encrypt ? L2CAP_LM_ENCRYPT : 0;
        lm |= (auth && encrypt) ? L2CAP_LM_SECURE : 0;
        break;
    }

    if (lm) {
        if (setsockopt(fd, SOL_RFCOMM, RFCOMM_LM, &lm, sizeof(lm))) {
            LOGV("setsockopt(RFCOMM_LM) failed, throwing");
            jniThrowIOException(env, errno);
            return;
        }
    }

    if (type == TYPE_RFCOMM) {
        sndbuf = RFCOMM_SO_SNDBUF;
        if (setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof(sndbuf))) {
            LOGV("setsockopt(SO_SNDBUF) failed, throwing");
            jniThrowIOException(env, errno);
            return;
        }
    }

    LOGV("...fd %d created (%s, lm = %x)", fd, TYPE_AS_STR(type), lm);

    initSocketFromFdNative(env, obj, fd);
    return;
#endif
    jniThrowIOException(env, ENOSYS);
}

static void connectNative(JNIEnv *env, jobject obj) {
#ifdef HAVE_BLUETOOTH
    LOGV("%s", __FUNCTION__);

    int ret;
    jint type;
    const char *c_address;
    jstring address;
    bdaddr_t bdaddress;
    socklen_t addr_sz;
    struct sockaddr *addr;
    struct asocket *s = get_socketData(env, obj);

    if (!s)
        return;

    type = env->GetIntField(obj, field_mType);

    /* parse address into bdaddress */
    address = (jstring) env->GetObjectField(obj, field_mAddress);
    c_address = env->GetStringUTFChars(address, NULL);
    if (get_bdaddr(c_address, &bdaddress)) {
        env->ReleaseStringUTFChars(address, c_address);
        jniThrowIOException(env, EINVAL);
        return;
    }
    env->ReleaseStringUTFChars(address, c_address);

    switch (type) {
    case TYPE_RFCOMM:
        struct sockaddr_rc addr_rc;
        addr = (struct sockaddr *)&addr_rc;
        addr_sz = sizeof(addr_rc);

        memset(addr, 0, addr_sz);
        addr_rc.rc_family = AF_BLUETOOTH;
        addr_rc.rc_channel = env->GetIntField(obj, field_mPort);
        memcpy(&addr_rc.rc_bdaddr, &bdaddress, sizeof(bdaddr_t));

        break;
    case TYPE_SCO:
        struct sockaddr_sco addr_sco;
        addr = (struct sockaddr *)&addr_sco;
        addr_sz = sizeof(addr_sco);

        memset(addr, 0, addr_sz);
        addr_sco.sco_family = AF_BLUETOOTH;
        memcpy(&addr_sco.sco_bdaddr, &bdaddress, sizeof(bdaddr_t));

        break;
    case TYPE_L2CAP:
        struct sockaddr_l2 addr_l2;
        addr = (struct sockaddr *)&addr_l2;
        addr_sz = sizeof(addr_l2);

        memset(addr, 0, addr_sz);
        addr_l2.l2_family = AF_BLUETOOTH;
        addr_l2.l2_psm = env->GetIntField(obj, field_mPort);
        memcpy(&addr_l2.l2_bdaddr, &bdaddress, sizeof(bdaddr_t));

        break;
    default:
        jniThrowIOException(env, ENOSYS);
        return;
    }

    ret = asocket_connect(s, addr, addr_sz, -1);
    LOGV("...connect(%d, %s) = %d (errno %d)",
            s->fd, TYPE_AS_STR(type), ret, errno);

    if (ret)
        jniThrowIOException(env, errno);

    return;
#endif
    jniThrowIOException(env, ENOSYS);
}

/* Returns errno instead of throwing, so java can check errno */
static int bindListenNative(JNIEnv *env, jobject obj) {
#ifdef HAVE_BLUETOOTH
    LOGV("%s", __FUNCTION__);

    jint type;
    socklen_t addr_sz;
    struct sockaddr *addr;
    bdaddr_t bdaddr = android_bluetooth_bdaddr_any();
    struct asocket *s = get_socketData(env, obj);

    if (!s)
        return EINVAL;

    type = env->GetIntField(obj, field_mType);

    switch (type) {
    case TYPE_RFCOMM:
        struct sockaddr_rc addr_rc;
        addr = (struct sockaddr *)&addr_rc;
        addr_sz = sizeof(addr_rc);

        memset(addr, 0, addr_sz);
        addr_rc.rc_family = AF_BLUETOOTH;
        addr_rc.rc_channel = env->GetIntField(obj, field_mPort);
        memcpy(&addr_rc.rc_bdaddr, &bdaddr, sizeof(bdaddr_t));
        break;
    case TYPE_SCO:
        struct sockaddr_sco addr_sco;
        addr = (struct sockaddr *)&addr_sco;
        addr_sz = sizeof(addr_sco);

        memset(addr, 0, addr_sz);
        addr_sco.sco_family = AF_BLUETOOTH;
        memcpy(&addr_sco.sco_bdaddr, &bdaddr, sizeof(bdaddr_t));
        break;
    case TYPE_L2CAP:
        struct sockaddr_l2 addr_l2;
        addr = (struct sockaddr *)&addr_l2;
        addr_sz = sizeof(addr_l2);

        memset(addr, 0, addr_sz);
        addr_l2.l2_family = AF_BLUETOOTH;
        addr_l2.l2_psm = env->GetIntField(obj, field_mPort);
        memcpy(&addr_l2.l2_bdaddr, &bdaddr, sizeof(bdaddr_t));
        break;
    default:
        return ENOSYS;
    }

    if (bind(s->fd, addr, addr_sz)) {
        LOGV("...bind(%d) gave errno %d", s->fd, errno);
        return errno;
    }

    if (listen(s->fd, 1)) {
        LOGV("...listen(%d) gave errno %d", s->fd, errno);
        return errno;
    }

    LOGV("...bindListenNative(%d) success", s->fd);

    return 0;

#endif
    return ENOSYS;
}

static jobject acceptNative(JNIEnv *env, jobject obj, int timeout) {
#ifdef HAVE_BLUETOOTH
    LOGV("%s", __FUNCTION__);

    int fd;
    jint type;
    struct sockaddr *addr;
    socklen_t addr_sz;
    jstring addr_jstr;
    char addr_cstr[BTADDR_SIZE];
    bdaddr_t *bdaddr;
    jboolean auth;
    jboolean encrypt;

    struct asocket *s = get_socketData(env, obj);

    if (!s)
        return NULL;

    type = env->GetIntField(obj, field_mType);

    switch (type) {
    case TYPE_RFCOMM:
        struct sockaddr_rc addr_rc;
        addr = (struct sockaddr *)&addr_rc;
        addr_sz = sizeof(addr_rc);
        bdaddr = &addr_rc.rc_bdaddr;
        memset(addr, 0, addr_sz);
        break;
    case TYPE_SCO:
        struct sockaddr_sco addr_sco;
        addr = (struct sockaddr *)&addr_sco;
        addr_sz = sizeof(addr_sco);
        bdaddr = &addr_sco.sco_bdaddr;
        memset(addr, 0, addr_sz);
        break;
    case TYPE_L2CAP:
        struct sockaddr_l2 addr_l2;
        addr = (struct sockaddr *)&addr_l2;
        addr_sz = sizeof(addr_l2);
        bdaddr = &addr_l2.l2_bdaddr;
        memset(addr, 0, addr_sz);
        break;
    default:
        jniThrowIOException(env, ENOSYS);
        return NULL;
    }

    fd = asocket_accept(s, addr, &addr_sz, timeout);

    LOGV("...accept(%d, %s) = %d (errno %d)",
            s->fd, TYPE_AS_STR(type), fd, errno);

    if (fd < 0) {
        jniThrowIOException(env, errno);
        return NULL;
    }

    /* Connected - return new BluetoothSocket */
    auth = env->GetBooleanField(obj, field_mAuth);
    encrypt = env->GetBooleanField(obj, field_mEncrypt);

    get_bdaddr_as_string(bdaddr, addr_cstr);

    addr_jstr = env->NewStringUTF(addr_cstr);
    return env->NewObject(class_BluetoothSocket, method_BluetoothSocket_ctor,
            type, fd, auth, encrypt, addr_jstr, -1);

#endif
    jniThrowIOException(env, ENOSYS);
    return NULL;
}

static jint availableNative(JNIEnv *env, jobject obj) {
#ifdef HAVE_BLUETOOTH
    LOGV("%s", __FUNCTION__);

    int available;
    struct asocket *s = get_socketData(env, obj);

    if (!s)
        return -1;

    if (ioctl(s->fd, FIONREAD, &available) < 0) {
        jniThrowIOException(env, errno);
        return -1;
    }

    return available;

#endif
    jniThrowIOException(env, ENOSYS);
    return -1;
}

static jint readNative(JNIEnv *env, jobject obj, jbyteArray jb, jint offset,
        jint length) {
#ifdef HAVE_BLUETOOTH
    LOGV("%s", __FUNCTION__);

    int ret;
    jbyte *b;
    int sz;
    struct asocket *s = get_socketData(env, obj);

    if (!s)
        return -1;
    if (jb == NULL) {
        jniThrowIOException(env, EINVAL);
        return -1;
    }
    sz = env->GetArrayLength(jb);
    if (offset < 0 || length < 0 || offset + length > sz) {
        jniThrowIOException(env, EINVAL);
        return -1;
    }

    b = env->GetByteArrayElements(jb, NULL);
    if (b == NULL) {
        jniThrowIOException(env, EINVAL);
        return -1;
    }

    ret = asocket_read(s, &b[offset], length, -1);
    if (ret < 0) {
        jniThrowIOException(env, errno);
        env->ReleaseByteArrayElements(jb, b, JNI_ABORT);
        return -1;
    }

    env->ReleaseByteArrayElements(jb, b, 0);
    return (jint)ret;

#endif
    jniThrowIOException(env, ENOSYS);
    return -1;
}

static jint writeNative(JNIEnv *env, jobject obj, jbyteArray jb, jint offset,
        jint length) {
#ifdef HAVE_BLUETOOTH
    LOGV("%s", __FUNCTION__);

    int ret, total;
    jbyte *b;
    int sz;
    struct asocket *s = get_socketData(env, obj);

    if (!s)
        return -1;
    if (jb == NULL) {
        jniThrowIOException(env, EINVAL);
        return -1;
    }
    sz = env->GetArrayLength(jb);
    if (offset < 0 || length < 0 || offset + length > sz) {
        jniThrowIOException(env, EINVAL);
        return -1;
    }

    b = env->GetByteArrayElements(jb, NULL);
    if (b == NULL) {
        jniThrowIOException(env, EINVAL);
        return -1;
    }

    total = 0;
    while (length > 0) {
        ret = asocket_write(s, &b[offset], length, -1);
        if (ret < 0) {
            jniThrowIOException(env, errno);
            env->ReleaseByteArrayElements(jb, b, JNI_ABORT);
            return -1;
        }
        offset += ret;
        total += ret;
        length -= ret;
    }

    env->ReleaseByteArrayElements(jb, b, JNI_ABORT);  // no need to commit
    return (jint)total;

#endif
    jniThrowIOException(env, ENOSYS);
    return -1;
}

static void abortNative(JNIEnv *env, jobject obj) {
#ifdef HAVE_BLUETOOTH
    LOGV("%s", __FUNCTION__);
    struct asocket *s = get_socketData(env, obj);

    if (!s)
        return;

    asocket_abort(s);

    LOGV("...asocket_abort(%d) complete", s->fd);
    return;
#endif
    jniThrowIOException(env, ENOSYS);
}

static void destroyNative(JNIEnv *env, jobject obj) {
#ifdef HAVE_BLUETOOTH
    LOGV("%s", __FUNCTION__);
    struct asocket *s = get_socketData(env, obj);
    int fd = s->fd;

    if (!s)
        return;

    asocket_destroy(s);

    LOGV("...asocket_destroy(%d) complete", fd);
    return;
#endif
    jniThrowIOException(env, ENOSYS);
}

static void throwErrnoNative(JNIEnv *env, jobject obj, jint err) {
    jniThrowIOException(env, err);
}

static JNINativeMethod sMethods[] = {
    {"initSocketNative", "()V",  (void*) initSocketNative},
    {"initSocketFromFdNative", "(I)V",  (void*) initSocketFromFdNative},
    {"connectNative", "()V", (void *) connectNative},
    {"bindListenNative", "()I", (void *) bindListenNative},
    {"acceptNative", "(I)Landroid/bluetooth/BluetoothSocket;", (void *) acceptNative},
    {"availableNative", "()I",    (void *) availableNative},
    {"readNative", "([BII)I",    (void *) readNative},
    {"writeNative", "([BII)I",    (void *) writeNative},
    {"abortNative", "()V",    (void *) abortNative},
    {"destroyNative", "()V",    (void *) destroyNative},
    {"throwErrnoNative", "(I)V",    (void *) throwErrnoNative},
};

int register_android_bluetooth_BluetoothSocket(JNIEnv *env) {
    jclass clazz = env->FindClass("android/bluetooth/BluetoothSocket");
    if (clazz == NULL)
        return -1;
    class_BluetoothSocket = (jclass) env->NewGlobalRef(clazz);
    field_mType = env->GetFieldID(clazz, "mType", "I");
    field_mAddress = env->GetFieldID(clazz, "mAddress", "Ljava/lang/String;");
    field_mPort = env->GetFieldID(clazz, "mPort", "I");
    field_mAuth = env->GetFieldID(clazz, "mAuth", "Z");
    field_mEncrypt = env->GetFieldID(clazz, "mEncrypt", "Z");
    field_mSocketData = env->GetFieldID(clazz, "mSocketData", "I");
    method_BluetoothSocket_ctor = env->GetMethodID(clazz, "<init>", "(IIZZLjava/lang/String;I)V");
    return AndroidRuntime::registerNativeMethods(env,
        "android/bluetooth/BluetoothSocket", sMethods, NELEM(sMethods));
}

} /* namespace android */

