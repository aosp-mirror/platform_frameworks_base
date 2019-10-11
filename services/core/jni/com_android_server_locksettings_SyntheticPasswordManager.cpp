/*
 * Copyright (C) 2016 The Android Open Source Project
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

#define LOG_TAG "SyntheticPasswordManager"

#include <nativehelper/JNIHelp.h>
#include "jni.h"

#include <android_runtime/Log.h>
#include <utils/Timers.h>
#include <utils/misc.h>
#include <utils/String8.h>
#include <utils/Log.h>
#include <gatekeeper/password_handle.h>


namespace android {

static jlong android_server_SyntheticPasswordManager_nativeSidFromPasswordHandle(JNIEnv* env, jobject, jbyteArray handleArray) {

    jbyte* data = (jbyte*)env->GetPrimitiveArrayCritical(handleArray, NULL);

    if (data != NULL) {
        const gatekeeper::password_handle_t *handle =
                reinterpret_cast<const gatekeeper::password_handle_t *>(data);
        jlong sid = handle->user_id;
        env->ReleasePrimitiveArrayCritical(handleArray, data, JNI_ABORT);
        return sid;
    } else {
        return 0;
    }
}

static const JNINativeMethod sMethods[] = {
     /* name, signature, funcPtr */
    {"nativeSidFromPasswordHandle", "([B)J", (void*)android_server_SyntheticPasswordManager_nativeSidFromPasswordHandle},
};

int register_android_server_SyntheticPasswordManager(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/server/locksettings/SyntheticPasswordManager",
                                    sMethods, NELEM(sMethods));
}

} /* namespace android */
