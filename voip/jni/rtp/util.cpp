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
#include <string.h>
#include <arpa/inet.h>
#include <netinet/in.h>

#include "jni.h"
#include "JNIHelp.h"

int parse(JNIEnv *env, jstring jAddress, int port, sockaddr_storage *ss)
{
    if (!jAddress) {
        jniThrowNullPointerException(env, "address");
        return -1;
    }
    if (port < 0 || port > 65535) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "port");
        return -1;
    }
    const char *address = env->GetStringUTFChars(jAddress, NULL);
    if (!address) {
        // Exception already thrown.
        return -1;
    }
    memset(ss, 0, sizeof(*ss));

    sockaddr_in *sin = (sockaddr_in *)ss;
    if (inet_pton(AF_INET, address, &(sin->sin_addr)) > 0) {
        sin->sin_family = AF_INET;
        sin->sin_port = htons(port);
        env->ReleaseStringUTFChars(jAddress, address);
        return 0;
    }

    sockaddr_in6 *sin6 = (sockaddr_in6 *)ss;
    if (inet_pton(AF_INET6, address, &(sin6->sin6_addr)) > 0) {
        sin6->sin6_family = AF_INET6;
        sin6->sin6_port = htons(port);
        env->ReleaseStringUTFChars(jAddress, address);
        return 0;
    }

    env->ReleaseStringUTFChars(jAddress, address);
    jniThrowException(env, "java/lang/IllegalArgumentException", "address");
    return -1;
}
