/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include <cstdint>
#include <core_jni_helpers.h>
#include <minikin/Hyphenator.h>
#include <nativehelper/ScopedUtfChars.h>

namespace android {

static jlong nBuildHyphenator(JNIEnv* env, jclass, jlong dataAddress, jstring lang,
        jint minPrefix, jint minSuffix) {
    const uint8_t* bytebuf = reinterpret_cast<const uint8_t*>(dataAddress);  // null allowed.
    ScopedUtfChars language(env, lang);
    minikin::Hyphenator* hyphenator = minikin::Hyphenator::loadBinary(
            bytebuf, minPrefix, minSuffix, language.c_str(), language.size());
    return reinterpret_cast<jlong>(hyphenator);
}

static const JNINativeMethod gMethods[] = {
    {"nBuildHyphenator", "(JLjava/lang/String;II)J", (void*) nBuildHyphenator},
};

int register_android_text_Hyphenator(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/text/Hyphenator", gMethods, NELEM(gMethods));
}

}  // namespace android
