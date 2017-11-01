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

#define LOG_TAG "DefContainer-JNI"

#include <nativehelper/JNIHelp.h>

#include <diskusage/dirsize.h>
#include <utils/Log.h>

#include <sys/types.h>
#include <sys/stat.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>

namespace android {

static jlong native_measureDirectory(JNIEnv* env, jobject /* clazz */, jstring directory) {
    jlong ret = 0L;

    const char* path = env->GetStringUTFChars(directory, NULL);
    if (path == NULL) {
        return ret;
    }

    int dirfd = open(path, O_DIRECTORY, O_RDONLY);
    if (dirfd < 0) {
        ALOGI("error opening: %s: %s", path, strerror(errno));
    } else {
        ret = calculate_dir_size(dirfd);
        close(dirfd);
    }

    env->ReleaseStringUTFChars(directory, path);

    return ret;
}

static const JNINativeMethod g_methods[] = {
    { "native_measureDirectory", "(Ljava/lang/String;)J", (void*)native_measureDirectory },
};

int register_com_android_defcontainer(JNIEnv *env) {
    if (jniRegisterNativeMethods(
            env, "com/android/defcontainer/MeasurementUtils", g_methods, NELEM(g_methods)) < 0) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}

} // namespace android

int JNI_OnLoad(JavaVM *jvm, void* /* reserved */) {
    JNIEnv *env;

    if (jvm->GetEnv((void**)&env, JNI_VERSION_1_6)) {
        return JNI_ERR;
    }

    return android::register_com_android_defcontainer(env);
}
