/*
 * Copyright 2010, The Android Open Source Project
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

#define LOG_TAG "ObbScanner"

#include <utils/Log.h>
#include <utils/String8.h>
#include <utils/ObbFile.h>

#include "jni.h"
#include "utils/misc.h"
#include "android_runtime/AndroidRuntime.h"

namespace android {

static struct {
    jclass clazz;

    jfieldID packageName;
    jfieldID version;
    jfieldID flags;
} gObbInfoClassInfo;

static jboolean android_content_res_ObbScanner_getObbInfo(JNIEnv* env, jobject clazz, jstring file,
        jobject obbInfo)
{
    const char* filePath = env->GetStringUTFChars(file, JNI_FALSE);

    sp<ObbFile> obb = new ObbFile();
    if (!obb->readFrom(filePath)) {
        env->ReleaseStringUTFChars(file, filePath);
        return JNI_FALSE;
    }

    env->ReleaseStringUTFChars(file, filePath);

    const char* packageNameStr = obb->getPackageName().string();

    jstring packageName = env->NewStringUTF(packageNameStr);
    if (packageName == NULL) {
        return JNI_FALSE;
    }

    env->SetObjectField(obbInfo, gObbInfoClassInfo.packageName, packageName);
    env->SetIntField(obbInfo, gObbInfoClassInfo.version, obb->getVersion());

    return JNI_TRUE;
}

/*
 * JNI registration.
 */
static JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "getObbInfo_native", "(Ljava/lang/String;Landroid/content/res/ObbInfo;)Z",
            (void*) android_content_res_ObbScanner_getObbInfo },
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className); \
        var = jclass(env->NewGlobalRef(var));

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find field " fieldName);

int register_android_content_res_ObbScanner(JNIEnv* env)
{
    FIND_CLASS(gObbInfoClassInfo.clazz, "android/content/res/ObbInfo");

    GET_FIELD_ID(gObbInfoClassInfo.packageName, gObbInfoClassInfo.clazz,
            "packageName", "Ljava/lang/String;");
    GET_FIELD_ID(gObbInfoClassInfo.version, gObbInfoClassInfo.clazz,
            "version", "I");
    GET_FIELD_ID(gObbInfoClassInfo.flags, gObbInfoClassInfo.clazz,
            "flags", "I");

    return AndroidRuntime::registerNativeMethods(env, "android/content/res/ObbScanner", gMethods,
            NELEM(gMethods));
}

}; // namespace android

