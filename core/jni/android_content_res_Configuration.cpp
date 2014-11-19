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

#include "core_jni_helpers.h"

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

int register_android_content_res_Configuration(JNIEnv* env)
{
    jclass clazz = FindClassOrDie(env, "android/content/res/Configuration");

    gConfigurationClassInfo.mcc = GetFieldIDOrDie(env, clazz, "mcc", "I");
    gConfigurationClassInfo.mnc = GetFieldIDOrDie(env, clazz, "mnc", "I");
    gConfigurationClassInfo.locale = GetFieldIDOrDie(env, clazz, "locale", "Ljava/util/Locale;");
    gConfigurationClassInfo.screenLayout = GetFieldIDOrDie(env, clazz, "screenLayout", "I");
    gConfigurationClassInfo.touchscreen = GetFieldIDOrDie(env, clazz, "touchscreen", "I");
    gConfigurationClassInfo.keyboard = GetFieldIDOrDie(env, clazz, "keyboard", "I");
    gConfigurationClassInfo.keyboardHidden = GetFieldIDOrDie(env, clazz, "keyboardHidden", "I");
    gConfigurationClassInfo.hardKeyboardHidden = GetFieldIDOrDie(env, clazz, "hardKeyboardHidden",
                                                                 "I");
    gConfigurationClassInfo.navigation = GetFieldIDOrDie(env, clazz, "navigation", "I");
    gConfigurationClassInfo.navigationHidden = GetFieldIDOrDie(env, clazz, "navigationHidden", "I");
    gConfigurationClassInfo.orientation = GetFieldIDOrDie(env, clazz, "orientation", "I");
    gConfigurationClassInfo.uiMode = GetFieldIDOrDie(env, clazz, "uiMode", "I");
    gConfigurationClassInfo.screenWidthDp = GetFieldIDOrDie(env, clazz, "screenWidthDp", "I");
    gConfigurationClassInfo.screenHeightDp = GetFieldIDOrDie(env, clazz, "screenHeightDp", "I");
    gConfigurationClassInfo.smallestScreenWidthDp = GetFieldIDOrDie(env, clazz,
                                                                    "smallestScreenWidthDp", "I");

    return 0;
}

}; // namespace android
