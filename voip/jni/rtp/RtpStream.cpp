/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <errno.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <netinet/in.h>

#define LOG_TAG "RtpStream"
#include <utils/Log.h>

#include "jni.h"
#include "JNIHelp.h"

extern int parse(JNIEnv *env, jstring jAddress, int port, sockaddr_storage *ss);

namespace {

jfieldID gNative;

jint create(JNIEnv *env, jobject thiz, jstring jAddress)
{
    env->SetIntField(thiz, gNative, -1);

    sockaddr_storage ss;
    if (parse(env, jAddress, 0, &ss) < 0) {
        // Exception already thrown.
        return -1;
    }

    int socket = ::socket(ss.ss_family, SOCK_DGRAM, 0);
    socklen_t len = sizeof(ss);
    if (socket == -1 || bind(socket, (sockaddr *)&ss, sizeof(ss)) != 0 ||
        getsockname(socket, (sockaddr *)&ss, &len) != 0) {
        jniThrowException(env, "java/net/SocketException", strerror(errno));
        ::close(socket);
        return -1;
    }

    uint16_t *p = (ss.ss_family == AF_INET) ?
        &((sockaddr_in *)&ss)->sin_port : &((sockaddr_in6 *)&ss)->sin6_port;
    uint16_t port = ntohs(*p);
    if ((port & 1) == 0) {
        env->SetIntField(thiz, gNative, socket);
        return port;
    }
    ::close(socket);

    socket = ::socket(ss.ss_family, SOCK_DGRAM, 0);
    if (socket != -1) {
        uint16_t delta = port << 1;
        ++port;

        for (int i = 0; i < 1000; ++i) {
            do {
                port += delta;
            } while (port < 1024);
            *p = htons(port);

            if (bind(socket, (sockaddr *)&ss, sizeof(ss)) == 0) {
                env->SetIntField(thiz, gNative, socket);
                return port;
            }
        }
    }

    jniThrowException(env, "java/net/SocketException", strerror(errno));
    ::close(socket);
    return -1;
}

jint dup(JNIEnv *env, jobject thiz)
{
    int socket = ::dup(env->GetIntField(thiz, gNative));
    if (socket == -1) {
        jniThrowException(env, "java/lang/IllegalStateException", strerror(errno));
    }
    return socket;
}

void close(JNIEnv *env, jobject thiz)
{
    int socket = env->GetIntField(thiz, gNative);
    ::close(socket);
    env->SetIntField(thiz, gNative, -1);
}

JNINativeMethod gMethods[] = {
    {"create", "(Ljava/lang/String;)I", (void *)create},
    {"dup", "()I", (void *)dup},
    {"close", "()V", (void *)close},
};

} // namespace

int registerRtpStream(JNIEnv *env)
{
    jclass clazz;
    if ((clazz = env->FindClass("android/net/rtp/RtpStream")) == NULL ||
        (gNative = env->GetFieldID(clazz, "mNative", "I")) == NULL ||
        env->RegisterNatives(clazz, gMethods, NELEM(gMethods)) < 0) {
        ALOGE("JNI registration failed");
        return -1;
    }
    return 0;
}
