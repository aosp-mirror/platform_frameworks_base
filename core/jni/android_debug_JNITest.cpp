/* //device/libs/android_runtime/android_debug_JNITest.cpp
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

#define LOG_TAG "DebugJNI"

#include "jni.h"
#include "nativehelper/JNIHelp.h"
#include "utils/Log.h"
#include "utils/misc.h"
//#include "android_runtime/AndroidRuntime.h"

namespace android {

/*
 * Implements:
 *  native int part1(int intArg, double doubleArg, String stringArg,
 *      int[] arrayArg)
 */
static jint android_debug_JNITest_part1(JNIEnv* env, jobject object,
    jint intArg, jdouble doubleArg, jstring stringArg, jobjectArray arrayArg)
{
    jclass clazz;
    jmethodID part2id;
    jsize arrayLen;
    jint arrayVal;
    int result = -2;

    LOGI("JNI test: in part1, intArg=%d, doubleArg=%.3f\n", intArg, doubleArg);

    /* find "int part2(double doubleArg, int fromArray, String stringArg)" */
    clazz = env->GetObjectClass(object);
    part2id = env->GetMethodID(clazz,
                "part2", "(DILjava/lang/String;)I");
    if (part2id == NULL) {
        LOGE("JNI test: unable to find part2\n");
        return -1;
    }

    /* get the length of the array */
    arrayLen = env->GetArrayLength(arrayArg);
    LOGI("  array size is %d\n", arrayLen);

    /*
     * Get the last element in the array.
     * Use the Get<type>ArrayElements functions instead if you need access
     * to multiple elements.
     */
    arrayVal = (int) env->GetObjectArrayElement(arrayArg, arrayLen-1);
    LOGI("  array val is %d\n", arrayVal);

    /* call this->part2 */
    result = env->CallIntMethod(object, part2id,
        doubleArg, arrayVal, stringArg);

    return result;
}

/*
 * Implements:
 *  private static native int part3(String stringArg);
 */
static jint android_debug_JNITest_part3(JNIEnv* env, jclass clazz,
    jstring stringArg)
{
    const char* utfChars;
    jboolean isCopy;

    LOGI("JNI test: in part3\n");

    utfChars = env->GetStringUTFChars(stringArg, &isCopy);

    LOGI("  String is '%s', isCopy=%d\n", (const char*) utfChars, isCopy);

    env->ReleaseStringUTFChars(stringArg, utfChars);

    return 2000;
}

/*
 * JNI registration.
 */
static JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "part1",      "(IDLjava/lang/String;[I)I",
            (void*) android_debug_JNITest_part1 },
    { "part3",      "(Ljava/lang/String;)I",
            (void*) android_debug_JNITest_part3 },
};
int register_android_debug_JNITest(JNIEnv* env)
{
    return jniRegisterNativeMethods(env, "android/debug/JNITest",
        gMethods, NELEM(gMethods));
}

#if 0
/* trampoline into C++ */
extern "C"
int register_android_debug_JNITest_C(JNIEnv* env)
{
    return android::register_android_debug_JNITest(env);
}
#endif

}; // namespace android

