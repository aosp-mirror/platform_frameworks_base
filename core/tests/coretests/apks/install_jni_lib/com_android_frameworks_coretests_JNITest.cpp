/*
 * Copyright (C) 2012 The Android Open Source Project
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

#include "nativehelper/JNIHelp.h"

namespace android {

static jint checkFunction(JNIEnv*, jclass) {
    return 1;
}

static JNINativeMethod sMethods[] = {
    /* name, signature, funcPtr */
    { "checkFunction", "()I", (void*) checkFunction },
};

int register_com_android_framework_coretests_JNITests(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/framework/coretests/JNITests", sMethods,
            NELEM(sMethods));
}

}

/*
 * JNI Initialization
 */
jint JNI_OnLoad(JavaVM *jvm, void *reserved) {
    JNIEnv *e;
    int status;

    // Check JNI version
    if (jvm->GetEnv((void **) &e, JNI_VERSION_1_6)) {
        return JNI_ERR;
    }

    if ((status = android::register_com_android_framework_coretests_JNITests(e)) < 0) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}
