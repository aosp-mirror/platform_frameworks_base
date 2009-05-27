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
#endif

namespace android {

static jfieldID  field_mAuth;     /* read-only */
static jfieldID  field_mEncrypt;  /* read-only */
static jfieldID  field_mSocketData;
static jmethodID method_BluetoothSocket_ctor;
static jclass    class_BluetoothSocket;

static struct asocket *get_socketData(JNIEnv *env, jobject obj) {
    struct asocket *s =
            (struct asocket *) env->GetIntField(obj, field_mSocketData);
    if (!s)
        jniThrowException(env, "java/io/IOException", "null socketData");
    return s;
}

static void initSocketFromFdNative(JNIEnv *env, jobject obj, jint fd) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);

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
    LOGV(__FUNCTION__);

    int fd;
    int lm = 0;
    jboolean auth;
    jboolean encrypt;

    /*TODO: do not hardcode to rfcomm */
    fd = socket(PF_BLUETOOTH, SOCK_STREAM, BTPROTO_RFCOMM);
    if (fd < 0) {
        LOGV("socket() failed, throwing");
        jniThrowIOException(env, errno);
        return;
    }

    auth = env->GetBooleanField(obj, field_mAuth);
    encrypt = env->GetBooleanField(obj, field_mEncrypt);

    lm |= auth ? RFCOMM_LM_AUTH : 0;
    lm |= encrypt? RFCOMM_LM_ENCRYPT : 0;

    if (lm) {
        if (setsockopt(fd, SOL_RFCOMM, RFCOMM_LM, &lm, sizeof(lm))) {
            LOGV("setsockopt() failed, throwing");
            jniThrowIOException(env, errno);
            return;
        }
    }

    initSocketFromFdNative(env, obj, fd);
    return;
#endif
    jniThrowIOException(env, ENOSYS);
}

static void connectNative(JNIEnv *env, jobject obj, jstring address,
        jint port, jint timeout) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);

    int ret;
    struct sockaddr_rc addr;
    const char *c_address;
    struct asocket *s = get_socketData(env, obj);

    if (!s)
        return;

    addr.rc_family = AF_BLUETOOTH;
    addr.rc_channel = port;
    c_address = env->GetStringUTFChars(address, NULL);
    if (get_bdaddr((const char *)c_address, &addr.rc_bdaddr)) {
        env->ReleaseStringUTFChars(address, c_address);
        jniThrowIOException(env, EINVAL);
        return;
    }
    env->ReleaseStringUTFChars(address, c_address);

    ret = asocket_connect(s, (struct sockaddr *)&addr, sizeof(addr), timeout);

    if (ret)
        jniThrowIOException(env, errno);

    return;
#endif
    jniThrowIOException(env, ENOSYS);
}

static void bindListenNative(JNIEnv *env, jobject obj, jint port) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);

    struct sockaddr_rc addr;
    struct asocket *s = get_socketData(env, obj);

    if (!s)
        return;

    memset(&addr, 0, sizeof(struct sockaddr_rc));
    addr.rc_family = AF_BLUETOOTH;
    addr.rc_bdaddr = *BDADDR_ANY;
    addr.rc_channel = port;

    if (bind(s->fd, (struct sockaddr *)&addr, sizeof(addr))) {
        jniThrowIOException(env, errno);
        return;
    }

    if (listen(s->fd, 1)) {
        jniThrowIOException(env, errno);
        return;
    }

    return;
#endif
    jniThrowIOException(env, ENOSYS);
}

static jobject acceptNative(JNIEnv *env, jobject obj, int timeout) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);

    int fd;
    struct sockaddr_rc addr;
    int addrlen = sizeof(addr);
    jstring addr_jstr;
    char addr_cstr[BTADDR_SIZE];
    jboolean auth;
    jboolean encrypt;

    struct asocket *s = get_socketData(env, obj);

    if (!s)
        return NULL;

    fd = asocket_accept(s, (struct sockaddr *)&addr, &addrlen, timeout);

    if (fd < 0) {
        jniThrowIOException(env, errno);
        return NULL;
    }

    /* Connected - return new BluetoothSocket */
    auth = env->GetBooleanField(obj, field_mAuth);
    encrypt = env->GetBooleanField(obj, field_mEncrypt);
    get_bdaddr_as_string(&addr.rc_bdaddr, addr_cstr);
    addr_jstr = env->NewStringUTF(addr_cstr);
    return env->NewObject(class_BluetoothSocket, method_BluetoothSocket_ctor, fd,
            auth, encrypt, addr_jstr, -1);

#endif
    jniThrowIOException(env, ENOSYS);
    return NULL;
}

static jint availableNative(JNIEnv *env, jobject obj) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);

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

static jint readNative(JNIEnv *env, jobject obj) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);

    char buf;
    struct asocket *s = get_socketData(env, obj);

    if (!s)
        return -1;

    if (asocket_read(s, &buf, 1, -1) < 0) {
        jniThrowIOException(env, errno);
        return -1;
    }

    return (jint)buf;

#endif
    jniThrowIOException(env, ENOSYS);
    return -1;
}

static void writeNative(JNIEnv *env, jobject obj, jint data) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);

    const char buf = (char)data;
    struct asocket *s = get_socketData(env, obj);

    if (!s)
        return;

    if (asocket_write(s, &buf, 1, -1) < 0)
        jniThrowIOException(env, errno);

    return;
#endif
    jniThrowIOException(env, ENOSYS);
}

static void closeNative(JNIEnv *env, jobject obj) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    struct asocket *s = get_socketData(env, obj);

    if (!s)
        return;

    asocket_abort(s);
    return;
#endif
    jniThrowIOException(env, ENOSYS);
}

static void destroyNative(JNIEnv *env, jobject obj) {
#ifdef HAVE_BLUETOOTH
    LOGV(__FUNCTION__);
    struct asocket *s = get_socketData(env, obj);
    if (!s)
        return;

    asocket_destroy(s);
    return;
#endif
    jniThrowIOException(env, ENOSYS);
}

static JNINativeMethod sMethods[] = {
    {"initSocketNative", "()V",  (void*) initSocketNative},
    {"initSocketFromFdNative", "(I)V",  (void*) initSocketFromFdNative},
    {"connectNative", "(Ljava/lang/String;II)V", (void *) connectNative},
    {"bindListenNative", "(I)V", (void *) bindListenNative},
    {"acceptNative", "(I)Landroid/bluetooth/BluetoothSocket;", (void *) acceptNative},
    {"availableNative", "()I",    (void *) availableNative},
    {"readNative", "()I",    (void *) readNative},
    {"writeNative", "(I)V",    (void *) writeNative},
    {"closeNative", "()V",    (void *) closeNative},
    {"destroyNative", "()V",    (void *) destroyNative},
};

int register_android_bluetooth_BluetoothSocket(JNIEnv *env) {
    jclass clazz = env->FindClass("android/bluetooth/BluetoothSocket");
    if (clazz == NULL)
        return -1;
    class_BluetoothSocket = (jclass) env->NewGlobalRef(clazz);
    field_mAuth = env->GetFieldID(clazz, "mAuth", "Z");
    field_mEncrypt = env->GetFieldID(clazz, "mEncrypt", "Z");
    field_mSocketData = env->GetFieldID(clazz, "mSocketData", "I");
    method_BluetoothSocket_ctor = env->GetMethodID(clazz, "<init>", "(IZZLjava/lang/String;I)V");
    return AndroidRuntime::registerNativeMethods(env,
        "android/bluetooth/BluetoothSocket", sMethods, NELEM(sMethods));
}

} /* namespace android */

