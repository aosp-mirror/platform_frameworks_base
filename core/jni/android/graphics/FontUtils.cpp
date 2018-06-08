/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "FontUtils.h"

#include <nativehelper/JNIHelp.h>
#include <core_jni_helpers.h>

namespace android {
namespace {

static struct {
    jmethodID mGet;
    jmethodID mSize;
} gListClassInfo;

static struct {
    jfieldID mTag;
    jfieldID mStyleValue;
} gAxisClassInfo;

}  // namespace

jint ListHelper::size() const {
    return mEnv->CallIntMethod(mList, gListClassInfo.mSize);
}

jobject ListHelper::get(jint index) const {
    return mEnv->CallObjectMethod(mList, gListClassInfo.mGet, index);
}

jint AxisHelper::getTag() const {
    return mEnv->GetIntField(mAxis, gAxisClassInfo.mTag);
}

jfloat AxisHelper::getStyleValue() const {
    return mEnv->GetFloatField(mAxis, gAxisClassInfo.mStyleValue);
}

void init_FontUtils(JNIEnv* env) {
    jclass listClass = FindClassOrDie(env, "java/util/List");
    gListClassInfo.mGet = GetMethodIDOrDie(env, listClass, "get", "(I)Ljava/lang/Object;");
    gListClassInfo.mSize = GetMethodIDOrDie(env, listClass, "size", "()I");

    jclass axisClass = FindClassOrDie(env, "android/graphics/fonts/FontVariationAxis");
    gAxisClassInfo.mTag = GetFieldIDOrDie(env, axisClass, "mTag", "I");
    gAxisClassInfo.mStyleValue = GetFieldIDOrDie(env, axisClass, "mStyleValue", "F");
}

}  // namespace android
