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
#include <androidfw/ObbFile.h>

#include "jni.h"
#include "JNIHelp.h"
#include "utils/misc.h"
#include "android_runtime/AndroidRuntime.h"

#include "core_jni_helpers.h"

namespace android {

static struct {
    jclass clazz;

    jfieldID packageName;
    jfieldID version;
    jfieldID flags;
    jfieldID salt;
} gObbInfoClassInfo;

static void android_content_res_ObbScanner_getObbInfo(JNIEnv* env, jobject clazz, jstring file,
        jobject obbInfo)
{
    const char* filePath = env->GetStringUTFChars(file, NULL);

    sp<ObbFile> obb = new ObbFile();
    if (!obb->readFrom(filePath)) {
        env->ReleaseStringUTFChars(file, filePath);
        jniThrowException(env, "java/io/IOException", "Could not read OBB file");
        return;
    }

    env->ReleaseStringUTFChars(file, filePath);

    const char* packageNameStr = obb->getPackageName().string();

    jstring packageName = env->NewStringUTF(packageNameStr);
    if (packageName == NULL) {
        jniThrowException(env, "java/io/IOException", "Could not read OBB file");
        return;
    }

    env->SetObjectField(obbInfo, gObbInfoClassInfo.packageName, packageName);
    env->SetIntField(obbInfo, gObbInfoClassInfo.version, obb->getVersion());
    env->SetIntField(obbInfo, gObbInfoClassInfo.flags, obb->getFlags());

    size_t saltLen;
    const unsigned char* salt = obb->getSalt(&saltLen);
    if (saltLen > 0) {
        jbyteArray saltArray = env->NewByteArray(saltLen);
        env->SetByteArrayRegion(saltArray, 0, saltLen, (jbyte*)salt);
        env->SetObjectField(obbInfo, gObbInfoClassInfo.salt, saltArray);
    }
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "getObbInfo_native", "(Ljava/lang/String;Landroid/content/res/ObbInfo;)V",
            (void*) android_content_res_ObbScanner_getObbInfo },
};

int register_android_content_res_ObbScanner(JNIEnv* env)
{
    jclass clazz = FindClassOrDie(env, "android/content/res/ObbInfo");

    gObbInfoClassInfo.packageName = GetFieldIDOrDie(env, clazz, "packageName",
                                                    "Ljava/lang/String;");
    gObbInfoClassInfo.version = GetFieldIDOrDie(env, clazz, "version", "I");
    gObbInfoClassInfo.flags = GetFieldIDOrDie(env, clazz, "flags", "I");
    gObbInfoClassInfo.salt = GetFieldIDOrDie(env, clazz, "salt", "[B");

    return RegisterMethodsOrDie(env, "android/content/res/ObbScanner", gMethods, NELEM(gMethods));
}

}; // namespace android
