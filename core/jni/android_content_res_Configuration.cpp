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

#define LOG_TAG "Configuration"

#include <utils/Log.h>
#include "utils/misc.h"

#include "jni.h"
#include <android_runtime/android_content_res_Configuration.h>
#include "android_runtime/AndroidRuntime.h"

namespace android {

static struct {
    jfieldID mcc;
    jfieldID mnc;
    jfieldID locale;
    jfieldID screenLayout;
    jfieldID touchscreen;
    jfieldID keyboard;
    jfieldID keyboardHidden;
    jfieldID hardKeyboardHidden;
    jfieldID navigation;
    jfieldID navigationHidden;
    jfieldID orientation;
    jfieldID uiMode;
    jfieldID screenWidthDp;
    jfieldID screenHeightDp;
    jfieldID smallestScreenWidthDp;
} gConfigurationClassInfo;

void android_Configuration_getFromJava(
        JNIEnv* env, jobject clazz, struct AConfiguration* out) {
    out->mcc = env->GetIntField(clazz, gConfigurationClassInfo.mcc);
    out->mnc = env->GetIntField(clazz, gConfigurationClassInfo.mnc);
    out->screenLayout = env->GetIntField(clazz, gConfigurationClassInfo.screenLayout);
    out->touchscreen = env->GetIntField(clazz, gConfigurationClassInfo.touchscreen);
    out->keyboard = env->GetIntField(clazz, gConfigurationClassInfo.keyboard);
    out->navigation = env->GetIntField(clazz, gConfigurationClassInfo.navigation);

    out->inputFlags = env->GetIntField(clazz, gConfigurationClassInfo.keyboardHidden);
    int hardKeyboardHidden = env->GetIntField(clazz, gConfigurationClassInfo.hardKeyboardHidden);
    if (out->inputFlags == ACONFIGURATION_KEYSHIDDEN_NO
            && hardKeyboardHidden == 2) {
        out->inputFlags = ACONFIGURATION_KEYSHIDDEN_SOFT;
    }
    out->inputFlags |= env->GetIntField(clazz, gConfigurationClassInfo.navigationHidden)
            << ResTable_config::SHIFT_NAVHIDDEN;

    out->orientation = env->GetIntField(clazz, gConfigurationClassInfo.orientation);
    out->uiMode = env->GetIntField(clazz, gConfigurationClassInfo.uiMode);

    out->screenWidthDp = env->GetIntField(clazz, gConfigurationClassInfo.screenWidthDp);
    out->screenHeightDp = env->GetIntField(clazz, gConfigurationClassInfo.screenHeightDp);
    out->smallestScreenWidthDp = env->GetIntField(clazz,
            gConfigurationClassInfo.smallestScreenWidthDp);
}

/*
 * JNI registration.
 */
static JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    //{ "getObbInfo_native", "(Ljava/lang/String;Landroid/content/res/ObbInfo;)Z",
    //        (void*) android_content_res_ObbScanner_getObbInfo },
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find field " fieldName);

int register_android_content_res_Configuration(JNIEnv* env)
{
    jclass clazz;
    FIND_CLASS(clazz, "android/content/res/Configuration");

    GET_FIELD_ID(gConfigurationClassInfo.mcc, clazz,
            "mcc", "I");
    GET_FIELD_ID(gConfigurationClassInfo.mnc, clazz,
            "mnc", "I");
    GET_FIELD_ID(gConfigurationClassInfo.locale, clazz,
            "locale", "Ljava/util/Locale;");
    GET_FIELD_ID(gConfigurationClassInfo.screenLayout, clazz,
            "screenLayout", "I");
    GET_FIELD_ID(gConfigurationClassInfo.touchscreen, clazz,
            "touchscreen", "I");
    GET_FIELD_ID(gConfigurationClassInfo.keyboard, clazz,
            "keyboard", "I");
    GET_FIELD_ID(gConfigurationClassInfo.keyboardHidden, clazz,
            "keyboardHidden", "I");
    GET_FIELD_ID(gConfigurationClassInfo.hardKeyboardHidden, clazz,
            "hardKeyboardHidden", "I");
    GET_FIELD_ID(gConfigurationClassInfo.navigation, clazz,
            "navigation", "I");
    GET_FIELD_ID(gConfigurationClassInfo.navigationHidden, clazz,
            "navigationHidden", "I");
    GET_FIELD_ID(gConfigurationClassInfo.orientation, clazz,
            "orientation", "I");
    GET_FIELD_ID(gConfigurationClassInfo.uiMode, clazz,
            "uiMode", "I");
    GET_FIELD_ID(gConfigurationClassInfo.screenWidthDp, clazz,
            "screenWidthDp", "I");
    GET_FIELD_ID(gConfigurationClassInfo.screenHeightDp, clazz,
            "screenHeightDp", "I");
    GET_FIELD_ID(gConfigurationClassInfo.smallestScreenWidthDp, clazz,
            "smallestScreenWidthDp", "I");

    return AndroidRuntime::registerNativeMethods(env, "android/content/res/Configuration", gMethods,
            NELEM(gMethods));
}

}; // namespace android
