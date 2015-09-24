/* //device/libs/android_runtime/android_text_AndroidBidi.cpp
**
** Copyright 2010, The Android Open Source Project
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

#include "JNIHelp.h"
#include "core_jni_helpers.h"
#include "utils/misc.h"
#include "utils/Log.h"
#include "unicode/ubidi.h"

namespace android {

static jint runBidi(JNIEnv* env, jobject obj, jint dir, jcharArray chsArray,
                    jbyteArray infoArray, jint n, jboolean haveInfo)
{
    // Parameters are checked on java side
    // Failures from GetXXXArrayElements indicate a serious out-of-memory condition
    // that we don't bother to report, we're probably dead anyway.
    jint result = 0;
    jchar* chs = env->GetCharArrayElements(chsArray, NULL);
    if (chs != NULL) {
        jbyte* info = env->GetByteArrayElements(infoArray, NULL);
        if (info != NULL) {
            UErrorCode status = U_ZERO_ERROR;
            UBiDi* bidi = ubidi_openSized(n, 0, &status);
            ubidi_setPara(bidi, chs, n, dir, NULL, &status);
            if (U_SUCCESS(status)) {
                for (int i = 0; i < n; ++i) {
                  info[i] = ubidi_getLevelAt(bidi, i);
                }
                result = ubidi_getParaLevel(bidi);
            } else {
                jniThrowException(env, "java/lang/RuntimeException", NULL);
            }
            ubidi_close(bidi);

            env->ReleaseByteArrayElements(infoArray, info, 0);
        }
        env->ReleaseCharArrayElements(chsArray, chs, JNI_ABORT);
    }
    return result;
}

static const JNINativeMethod gMethods[] = {
        { "runBidi", "(I[C[BIZ)I", (void*) runBidi }
};

int register_android_text_AndroidBidi(JNIEnv* env)
{
    return RegisterMethodsOrDie(env, "android/text/AndroidBidi", gMethods, NELEM(gMethods));
}

}
