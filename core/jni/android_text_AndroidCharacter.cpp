/* //device/libs/android_runtime/android_text_AndroidCharacter.cpp
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

#define LOG_TAG "AndroidUnicode"

#include <jni.h>
#include <android_runtime/AndroidRuntime.h>
#include "utils/misc.h"
#include "utils/AndroidUnicode.h"
#include "utils/Log.h"

namespace android {
    
static void jniThrowException(JNIEnv* env, const char* exc, const char* msg = NULL)
{
    jclass excClazz = env->FindClass(exc);
    LOG_ASSERT(excClazz, "Unable to find class %s", exc);

    env->ThrowNew(excClazz, msg);
}

static void getDirectionalities(JNIEnv* env, jobject obj, jcharArray srcArray, jbyteArray destArray, int count)
{
    jchar* src = env->GetCharArrayElements(srcArray, NULL);
    jbyte* dest = env->GetByteArrayElements(destArray, NULL);
    if (src == NULL || dest == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", NULL);
        goto DIRECTION_END;
    }

    if (env->GetArrayLength(srcArray) < count || env->GetArrayLength(destArray) < count) {
        jniThrowException(env, "java/lang/ArrayIndexOutOfBoundsException", NULL);
        goto DIRECTION_END;
    }

    for (int i = 0; i < count; i++) {
        if (src[i] >= 0xD800 && src[i] <= 0xDBFF &&
            i + 1 < count &&
            src[i + 1] >= 0xDC00 && src[i + 1] <= 0xDFFF) {
            int c = 0x00010000 + ((src[i] - 0xD800) << 10) +
                                 (src[i + 1] & 0x3FF);
            int dir = android::Unicode::getDirectionality(c);

            dest[i++] = dir;
            dest[i] = dir;
        } else {
            int c = src[i];
            int dir = android::Unicode::getDirectionality(c);

            dest[i] = dir;
        }
    }
    
DIRECTION_END:
    env->ReleaseCharArrayElements(srcArray, src, JNI_ABORT);
    env->ReleaseByteArrayElements(destArray, dest, JNI_ABORT);
}

static jboolean mirror(JNIEnv* env, jobject obj, jcharArray charArray, int start, int count)
{
    jchar* data = env->GetCharArrayElements(charArray, NULL);
    bool ret = false;

    if (data == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", NULL);
        goto MIRROR_END;
    }

    if (start > start + count || env->GetArrayLength(charArray) < count) {
        jniThrowException(env, "java/lang/ArrayIndexOutOfBoundsException", NULL);
        goto MIRROR_END;
    }

    for (int i = start; i < start + count; i++) {
        // XXX this thinks it knows that surrogates are never mirrored

        int c1 = data[i];
        int c2 = android::Unicode::toMirror(c1);

        if (c1 != c2) {
            data[i] = c2;
            ret = true;
        }
    }

MIRROR_END:
    env->ReleaseCharArrayElements(charArray, data, JNI_ABORT);
	return ret;
}

static jchar getMirror(JNIEnv* env, jobject obj, jchar c)
{   
    return android::Unicode::toMirror(c);
}

static JNINativeMethod gMethods[] = {
	{ "getDirectionalities", "([C[BI)V",
        (void*) getDirectionalities },
	{ "mirror", "([CII)Z",
        (void*) mirror },
	{ "getMirror", "(C)C",
        (void*) getMirror }
};

int register_android_text_AndroidCharacter(JNIEnv* env)
{
    jclass clazz = env->FindClass("android/text/AndroidCharacter");
    LOG_ASSERT(clazz, "Cannot find android/text/AndroidCharacter");
    
    return AndroidRuntime::registerNativeMethods(env, "android/text/AndroidCharacter",
            gMethods, NELEM(gMethods));
}

}
