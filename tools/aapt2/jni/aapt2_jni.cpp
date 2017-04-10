/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "com_android_tools_aapt2_Aapt2Jni.h"

#include <algorithm>
#include <memory>
#include <utility>
#include <vector>

#include "android-base/logging.h"
#include "ScopedUtfChars.h"

#include "util/Util.h"

using android::StringPiece;

namespace aapt {
extern int Compile(const std::vector<StringPiece> &args);
extern int Link(const std::vector<StringPiece> &args);
}

/*
 * Converts a java List<String> into C++ vector<ScopedUtfChars>.
 */
static std::vector<ScopedUtfChars> list_to_utfchars(JNIEnv *env, jobject obj) {
  std::vector<ScopedUtfChars> converted;

  // Call size() method on the list to know how many elements there are.
  jclass list_cls = env->GetObjectClass(obj);
  jmethodID size_method_id = env->GetMethodID(list_cls, "size", "()I");
  CHECK(size_method_id != 0);
  jint size = env->CallIntMethod(obj, size_method_id);
  CHECK(size >= 0);

  // Now, iterate all strings in the list
  // (note: generic erasure means get() return an Object)
  jmethodID get_method_id = env->GetMethodID(list_cls, "get", "(I)Ljava/lang/Object;");
  CHECK(get_method_id != 0);
  for (jint i = 0; i < size; i++) {
    // Call get(i) to get the string in the ith position.
    jobject string_obj_uncast = env->CallObjectMethod(obj, get_method_id, i);
    CHECK(string_obj_uncast != nullptr);
    jstring string_obj = static_cast<jstring>(string_obj_uncast);
    converted.push_back(ScopedUtfChars(env, string_obj));
  }

  return converted;
}

/*
 * Extracts all StringPiece from the ScopedUtfChars instances.
 *
 * The returned pieces can only be used while the original ones have not been
 * destroyed.
 */
static std::vector<StringPiece> extract_pieces(const std::vector<ScopedUtfChars> &strings) {
  std::vector<StringPiece> pieces;

  std::for_each(
      strings.begin(), strings.end(),
      [&pieces](const ScopedUtfChars &p) { pieces.push_back(p.c_str()); });

  return pieces;
}

JNIEXPORT jint JNICALL Java_com_android_tools_aapt2_Aapt2Jni_nativeCompile(
    JNIEnv *env, jclass aapt_obj, jobject arguments_obj) {
  std::vector<ScopedUtfChars> compile_args_jni =
      list_to_utfchars(env, arguments_obj);
  std::vector<StringPiece> compile_args = extract_pieces(compile_args_jni);
  return aapt::Compile(compile_args);
}

JNIEXPORT jint JNICALL Java_com_android_tools_aapt2_Aapt2Jni_nativeLink(
    JNIEnv *env, jclass aapt_obj, jobject arguments_obj) {
  std::vector<ScopedUtfChars> link_args_jni =
      list_to_utfchars(env, arguments_obj);
  std::vector<StringPiece> link_args = extract_pieces(link_args_jni);
  return aapt::Link(link_args);
}

JNIEXPORT void JNICALL Java_com_android_tools_aapt2_Aapt2Jni_ping(
        JNIEnv *env, jclass aapt_obj) {
  // This is just a dummy method to see if the library has been loaded.
}
