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

#include <utility>
#include <optional>

#include "android-base/logging.h"
#include "android-base/parsebool.h"
#include "android-base/parseint.h"
#include "android-base/properties.h"
#include "utils/misc.h"
#include <utils/Log.h>
#include "jni.h"
#include "core_jni_helpers.h"
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>

#if defined(__BIONIC__)
# include <sys/system_properties.h>
#else
struct prop_info;
#endif

namespace android {
namespace {

using android::base::ParseBoolResult;

template<typename Functor>
void ReadProperty(const prop_info* prop, Functor&& functor)
{
#if defined(__BIONIC__)
    auto thunk = [](void* cookie,
                    const char* /*name*/,
                    const char* value,
                    uint32_t /*serial*/) {
        std::forward<Functor>(*static_cast<Functor*>(cookie))(value);
    };
    __system_property_read_callback(prop, thunk, &functor);
#else
    LOG(FATAL) << "fast property access supported only on device";
#endif
}

template<typename Functor>
void ReadProperty(JNIEnv* env, jstring keyJ, Functor&& functor)
{
    ScopedUtfChars key(env, keyJ);
    if (!key.c_str()) {
        return;
    }
#if defined(__BIONIC__)
    const prop_info* prop = __system_property_find(key.c_str());
    if (!prop) {
        return;
    }
    ReadProperty(prop, std::forward<Functor>(functor));
#else
    std::forward<Functor>(functor)(
        android::base::GetProperty(key.c_str(), "").c_str());
#endif
}

jstring SystemProperties_getSS(JNIEnv* env, jclass clazz, jstring keyJ,
                               jstring defJ)
{
    jstring ret = defJ;
    ReadProperty(env, keyJ, [&](const char* value) {
        if (value[0]) {
            ret = env->NewStringUTF(value);
        }
    });
    if (ret == nullptr && !env->ExceptionCheck()) {
      ret = env->NewStringUTF("");  // Legacy behavior
    }
    return ret;
}

template <typename T>
T SystemProperties_get_integral(JNIEnv *env, jclass, jstring keyJ,
                                       T defJ)
{
    T ret = defJ;
    ReadProperty(env, keyJ, [&](const char* value) {
        android::base::ParseInt<T>(value, &ret);
    });
    return ret;
}

static jboolean jbooleanFromParseBoolResult(ParseBoolResult parseResult, jboolean defJ) {
    jboolean ret;
    switch (parseResult) {
        case ParseBoolResult::kError:
            ret = defJ;
            break;
        case ParseBoolResult::kFalse:
            ret = JNI_FALSE;
            break;
        case ParseBoolResult::kTrue:
            ret = JNI_TRUE;
            break;
    }
    return ret;
}

jboolean SystemProperties_get_boolean(JNIEnv *env, jclass, jstring keyJ,
                                      jboolean defJ)
{
    ParseBoolResult parseResult = ParseBoolResult::kError;
    ReadProperty(env, keyJ, [&](const char* value) {
        parseResult = android::base::ParseBool(value);
    });
    return jbooleanFromParseBoolResult(parseResult, defJ);
}

jlong SystemProperties_find(JNIEnv* env, jclass, jstring keyJ)
{
#if defined(__BIONIC__)
    ScopedUtfChars key(env, keyJ);
    if (!key.c_str()) {
        return 0;
    }
    const prop_info* prop = __system_property_find(key.c_str());
    return reinterpret_cast<jlong>(prop);
#else
    LOG(FATAL) << "fast property access supported only on device";
    __builtin_unreachable();  // Silence warning
#endif
}

jstring SystemProperties_getH(JNIEnv* env, jclass clazz, jlong propJ)
{
    jstring ret;
    auto prop = reinterpret_cast<const prop_info*>(propJ);
    ReadProperty(prop, [&](const char* value) {
        ret = env->NewStringUTF(value);
    });
    return ret;
}

template <typename T>
T SystemProperties_get_integralH(CRITICAL_JNI_PARAMS_COMMA jlong propJ, T defJ)
{
    T ret = defJ;
    auto prop = reinterpret_cast<const prop_info*>(propJ);
    ReadProperty(prop, [&](const char* value) {
        android::base::ParseInt<T>(value, &ret);
    });
    return ret;
}

jboolean SystemProperties_get_booleanH(CRITICAL_JNI_PARAMS_COMMA jlong propJ, jboolean defJ)
{
    ParseBoolResult parseResult = ParseBoolResult::kError;
    auto prop = reinterpret_cast<const prop_info*>(propJ);
    ReadProperty(prop, [&](const char* value) {
        parseResult = android::base::ParseBool(value);
    });
    return jbooleanFromParseBoolResult(parseResult, defJ);
}

void SystemProperties_set(JNIEnv *env, jobject clazz, jstring keyJ,
                          jstring valJ)
{
    ScopedUtfChars key(env, keyJ);
    if (!key.c_str()) {
        return;
    }
    std::optional<ScopedUtfChars> value;
    if (valJ != nullptr) {
        value.emplace(env, valJ);
        if (!value->c_str()) {
            return;
        }
    }
    // Calling SystemProperties.set() with a null value is equivalent to an
    // empty string, but this is not true for the underlying libc function.
    const char* value_c_str = value ? value->c_str() : "";
    // Explicitly clear errno so we can recognize __system_property_set()
    // failures from failed system calls (as opposed to "init rejected your
    // request" failures).
    errno = 0;
    bool success;
#if defined(__BIONIC__)
    success = !__system_property_set(key.c_str(), value_c_str);
#else
    success = android::base::SetProperty(key.c_str(), value_c_str);
#endif
    if (!success) {
        if (errno != 0) {
            jniThrowExceptionFmt(env, "java/lang/RuntimeException",
                                 "failed to set system property \"%s\" to \"%s\": %m",
                                 key.c_str(), value_c_str);
        } else {
            // Must have made init unhappy, which will have logged something,
            // but there's no API to ask for more detail.
            jniThrowExceptionFmt(env, "java/lang/RuntimeException",
                                 "failed to set system property \"%s\" to \"%s\" (check logcat for reason)",
                                 key.c_str(), value_c_str);
        }
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
        { "native_get",
          "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
          (void*) SystemProperties_getSS },
        { "native_get_int", "(Ljava/lang/String;I)I",
          (void*) SystemProperties_get_integral<jint> },
        { "native_get_long", "(Ljava/lang/String;J)J",
          (void*) SystemProperties_get_integral<jlong> },
        { "native_get_boolean", "(Ljava/lang/String;Z)Z",
          (void*) SystemProperties_get_boolean },
        { "native_find",
          "(Ljava/lang/String;)J",
          (void*) SystemProperties_find },
        { "native_get",
          "(J)Ljava/lang/String;",
          (void*) SystemProperties_getH },
        { "native_get_int", "(JI)I",
          (void*) SystemProperties_get_integralH<jint> },
        { "native_get_long", "(JJ)J",
          (void*) SystemProperties_get_integralH<jlong> },
        { "native_get_boolean", "(JZ)Z",
          (void*) SystemProperties_get_booleanH },
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

}  // namespace android
