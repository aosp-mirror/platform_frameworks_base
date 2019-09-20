/* //device/libs/android_runtime/android_os_SystemProperties.cpp
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

#define LOG_TAG "SysPropJNI"

#include "android-base/logging.h"
#include "android-base/properties.h"
#include "utils/misc.h"
#include <utils/Log.h>
#include "jni.h"
#include "core_jni_helpers.h"
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>

namespace android
{

namespace {

template <typename T, typename Handler>
T ConvertKeyAndForward(JNIEnv *env, jstring keyJ, T defJ, Handler handler) {
    std::string key;
    {
        // Scope the String access. If the handler can throw an exception,
        // releasing the string characters late would trigger an abort.
        ScopedUtfChars key_utf(env, keyJ);
        if (key_utf.c_str() == nullptr) {
            return defJ;
        }
        key = key_utf.c_str();  // This will make a copy, but we can't avoid
                                // with the existing interface in
                                // android::base.
    }
    return handler(key, defJ);
}

jstring SystemProperties_getSS(JNIEnv *env, jclass clazz, jstring keyJ,
                               jstring defJ)
{
    // Using ConvertKeyAndForward is sub-optimal for copying the key string,
    // but improves reuse and reasoning over code.
    auto handler = [&](const std::string& key, jstring defJ) {
        std::string prop_val = android::base::GetProperty(key, "");
        if (!prop_val.empty()) {
            return env->NewStringUTF(prop_val.c_str());
        };
        if (defJ != nullptr) {
            return defJ;
        }
        // This function is specified to never return null (or have an
        // exception pending).
        return env->NewStringUTF("");
    };
    return ConvertKeyAndForward(env, keyJ, defJ, handler);
}

jstring SystemProperties_getS(JNIEnv *env, jclass clazz, jstring keyJ)
{
    return SystemProperties_getSS(env, clazz, keyJ, nullptr);
}

template <typename T>
T SystemProperties_get_integral(JNIEnv *env, jclass, jstring keyJ,
                                       T defJ)
{
    auto handler = [](const std::string& key, T defV) {
        return android::base::GetIntProperty<T>(key, defV);
    };
    return ConvertKeyAndForward(env, keyJ, defJ, handler);
}

jboolean SystemProperties_get_boolean(JNIEnv *env, jclass, jstring keyJ,
                                      jboolean defJ)
{
    auto handler = [](const std::string& key, jboolean defV) -> jboolean {
        bool result = android::base::GetBoolProperty(key, defV);
        return result ? JNI_TRUE : JNI_FALSE;
    };
    return ConvertKeyAndForward(env, keyJ, defJ, handler);
}

void SystemProperties_set(JNIEnv *env, jobject clazz, jstring keyJ,
                          jstring valJ)
{
    auto handler = [&](const std::string& key, bool) {
        std::string val;
        if (valJ != nullptr) {
            ScopedUtfChars key_utf(env, valJ);
            val = key_utf.c_str();
        }
        return android::base::SetProperty(key, val);
    };
    if (!ConvertKeyAndForward(env, keyJ, true, handler)) {
        // Must have been a failure in SetProperty.
        jniThrowException(env, "java/lang/RuntimeException",
                          "failed to set system property (check logcat for reason)");
    }
}

JavaVM* sVM = nullptr;
jclass sClazz = nullptr;
jmethodID sCallChangeCallbacks;

void do_report_sysprop_change() {
    //ALOGI("Java SystemProperties: VM=%p, Clazz=%p", sVM, sClazz);
    if (sVM != nullptr && sClazz != nullptr) {
        JNIEnv* env;
        if (sVM->GetEnv((void **)&env, JNI_VERSION_1_4) >= 0) {
            //ALOGI("Java SystemProperties: calling %p", sCallChangeCallbacks);
            env->CallStaticVoidMethod(sClazz, sCallChangeCallbacks);
            // There should not be any exceptions. But we must guarantee
            // there are none on return.
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                LOG(ERROR) << "Exception pending after sysprop_change!";
            }
        }
    }
}

void SystemProperties_add_change_callback(JNIEnv *env, jobject clazz)
{
    // This is called with the Java lock held.
    if (sVM == nullptr) {
        env->GetJavaVM(&sVM);
    }
    if (sClazz == nullptr) {
        sClazz = (jclass) env->NewGlobalRef(clazz);
        sCallChangeCallbacks = env->GetStaticMethodID(sClazz, "callChangeCallbacks", "()V");
        add_sysprop_change_callback(do_report_sysprop_change, -10000);
    }
}

void SystemProperties_report_sysprop_change(JNIEnv /**env*/, jobject /*clazz*/)
{
    report_sysprop_change();
}

}  // namespace

int register_android_os_SystemProperties(JNIEnv *env)
{
    const JNINativeMethod method_table[] = {
        { "native_get", "(Ljava/lang/String;)Ljava/lang/String;",
          (void*) SystemProperties_getS },
        { "native_get",
          "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
          (void*) SystemProperties_getSS },
        { "native_get_int", "(Ljava/lang/String;I)I",
          (void*) SystemProperties_get_integral<jint> },
        { "native_get_long", "(Ljava/lang/String;J)J",
          (void*) SystemProperties_get_integral<jlong> },
        { "native_get_boolean", "(Ljava/lang/String;Z)Z",
          (void*) SystemProperties_get_boolean },
        { "native_set", "(Ljava/lang/String;Ljava/lang/String;)V",
          (void*) SystemProperties_set },
        { "native_add_change_callback", "()V",
          (void*) SystemProperties_add_change_callback },
        { "native_report_sysprop_change", "()V",
          (void*) SystemProperties_report_sysprop_change },
    };
    return RegisterMethodsOrDie(env, "android/os/SystemProperties",
                                method_table, NELEM(method_table));
}

};
