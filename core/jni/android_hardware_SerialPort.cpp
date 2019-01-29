/*
 * Copyright (C) 2011 The Android Open Source Project
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

#define LOG_TAG "SerialPortJNI"

#include "utils/Log.h"

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include "core_jni_helpers.h"

#include <stdio.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <termios.h>

using namespace android;

static jfieldID field_context;

static void
android_hardware_SerialPort_open(JNIEnv *env, jobject thiz, jobject fileDescriptor, jint speed)
{
    switch (speed) {
        case 50:
            speed = B50;
            break;
        case 75:
            speed = B75;
            break;
        case 110:
            speed = B110;
            break;
        case 134:
            speed = B134;
            break;
        case 150:
            speed = B150;
            break;
        case 200:
            speed = B200;
            break;
        case 300:
            speed = B300;
            break;
        case 600:
            speed = B600;
            break;
        case 1200:
            speed = B1200;
            break;
        case 1800:
            speed = B1800;
            break;
        case 2400:
            speed = B2400;
            break;
        case 4800:
            speed = B4800;
            break;
        case 9600:
            speed = B9600;
            break;
        case 19200:
            speed = B19200;
            break;
        case 38400:
            speed = B38400;
            break;
        case 57600:
            speed = B57600;
            break;
        case 115200:
            speed = B115200;
            break;
        case 230400:
            speed = B230400;
            break;
        case 460800:
            speed = B460800;
            break;
        case 500000:
            speed = B500000;
            break;
        case 576000:
            speed = B576000;
            break;
        case 921600:
            speed = B921600;
            break;
        case 1000000:
            speed = B1000000;
            break;
        case 1152000:
            speed = B1152000;
            break;
        case 1500000:
            speed = B1500000;
            break;
        case 2000000:
            speed = B2000000;
            break;
        case 2500000:
            speed = B2500000;
            break;
        case 3000000:
            speed = B3000000;
            break;
        case 3500000:
            speed = B3500000;
            break;
        case 4000000:
            speed = B4000000;
            break;
        default:
            jniThrowException(env, "java/lang/IllegalArgumentException",
                              "Unsupported serial port speed");
            return;
    }

    int fd = jniGetFDFromFileDescriptor(env, fileDescriptor);
    // duplicate the file descriptor, since ParcelFileDescriptor will eventually close its copy
    fd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    if (fd < 0) {
        jniThrowException(env, "java/io/IOException", "Could not open serial port");
        return;
    }
    env->SetIntField(thiz, field_context, fd);

    struct termios tio;
    if (tcgetattr(fd, &tio))
        memset(&tio, 0, sizeof(tio));

    tio.c_cflag =  speed | CS8 | CLOCAL | CREAD;
    // Disable output processing, including messing with end-of-line characters.
    tio.c_oflag &= ~OPOST;
    tio.c_iflag = IGNPAR;
    tio.c_lflag = 0; /* turn of CANON, ECHO*, etc */
    /* no timeout but request at least one character per read */
    tio.c_cc[VTIME] = 0;
    tio.c_cc[VMIN] = 1;
    tcsetattr(fd, TCSANOW, &tio);
    tcflush(fd, TCIFLUSH);
}

static void
android_hardware_SerialPort_close(JNIEnv *env, jobject thiz)
{
    int fd = env->GetIntField(thiz, field_context);
    close(fd);
    env->SetIntField(thiz, field_context, -1);
}

static jint
android_hardware_SerialPort_read_array(JNIEnv *env, jobject thiz, jbyteArray buffer, jint length)
{
    int fd = env->GetIntField(thiz, field_context);
    jbyte* buf = (jbyte *)malloc(length);
    if (!buf) {
        jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
        return -1;
    }

    int ret = read(fd, buf, length);
    if (ret > 0) {
        // copy data from native buffer to Java buffer
        env->SetByteArrayRegion(buffer, 0, ret, buf);
    }

    free(buf);
    if (ret < 0)
        jniThrowException(env, "java/io/IOException", NULL);
    return ret;
}

static jint
android_hardware_SerialPort_read_direct(JNIEnv *env, jobject thiz, jobject buffer, jint length)
{
    int fd = env->GetIntField(thiz, field_context);

    jbyte* buf = (jbyte *)env->GetDirectBufferAddress(buffer);
    if (!buf) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "ByteBuffer not direct");
        return -1;
    }

    int ret = read(fd, buf, length);
    if (ret < 0)
        jniThrowException(env, "java/io/IOException", NULL);
    return ret;
}

static void
android_hardware_SerialPort_write_array(JNIEnv *env, jobject thiz, jbyteArray buffer, jint length)
{
    int fd = env->GetIntField(thiz, field_context);
    jbyte* buf = (jbyte *)malloc(length);
    if (!buf) {
        jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
        return;
    }
    env->GetByteArrayRegion(buffer, 0, length, buf);

    jint ret = write(fd, buf, length);
    free(buf);
    if (ret < 0)
        jniThrowException(env, "java/io/IOException", NULL);
}

static void
android_hardware_SerialPort_write_direct(JNIEnv *env, jobject thiz, jobject buffer, jint length)
{
    int fd = env->GetIntField(thiz, field_context);

    jbyte* buf = (jbyte *)env->GetDirectBufferAddress(buffer);
    if (!buf) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "ByteBuffer not direct");
        return;
    }
    int ret = write(fd, buf, length);
    if (ret < 0)
        jniThrowException(env, "java/io/IOException", NULL);
}

static void
android_hardware_SerialPort_send_break(JNIEnv *env, jobject thiz)
{
    int fd = env->GetIntField(thiz, field_context);
    tcsendbreak(fd, 0);
}

static const JNINativeMethod method_table[] = {
    {"native_open",             "(Ljava/io/FileDescriptor;I)V",
                                        (void *)android_hardware_SerialPort_open},
    {"native_close",            "()V",  (void *)android_hardware_SerialPort_close},
    {"native_read_array",       "([BI)I",
                                        (void *)android_hardware_SerialPort_read_array},
    {"native_read_direct",      "(Ljava/nio/ByteBuffer;I)I",
                                        (void *)android_hardware_SerialPort_read_direct},
    {"native_write_array",      "([BI)V",
                                        (void *)android_hardware_SerialPort_write_array},
    {"native_write_direct",     "(Ljava/nio/ByteBuffer;I)V",
                                        (void *)android_hardware_SerialPort_write_direct},
    {"native_send_break",       "()V",  (void *)android_hardware_SerialPort_send_break},
};

int register_android_hardware_SerialPort(JNIEnv *env)
{
    jclass clazz = FindClassOrDie(env, "android/hardware/SerialPort");
    field_context = GetFieldIDOrDie(env, clazz, "mNativeContext", "I");

    return RegisterMethodsOrDie(env, "android/hardware/SerialPort",
            method_table, NELEM(method_table));
}
