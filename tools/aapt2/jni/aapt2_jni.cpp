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

#include "Diagnostics.h"
#include "cmd/Compile.h"
#include "cmd/Link.h"
#include "util/Util.h"

using android::StringPiece;

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

class JniDiagnostics : public aapt::IDiagnostics {
 public:
  JniDiagnostics(JNIEnv* env, jobject diagnostics_obj)
      : env_(env), diagnostics_obj_(diagnostics_obj) {
    mid_ = NULL;
  }

  void Log(Level level, aapt::DiagMessageActual& actual_msg) override {
    jint level_value;
    switch (level) {
      case Level::Error:
        level_value = 3;
        break;

      case Level::Warn:
        level_value = 2;
        break;

      case Level::Note:
        level_value = 1;
        break;
    }
    jstring message = env_->NewStringUTF(actual_msg.message.c_str());
    jstring path = env_->NewStringUTF(actual_msg.source.path.c_str());
    jlong line = -1;
    if (actual_msg.source.line) {
      line = actual_msg.source.line.value();
    }
    if (!mid_) {
      jclass diagnostics_cls = env_->GetObjectClass(diagnostics_obj_);
      mid_ = env_->GetMethodID(diagnostics_cls, "log", "(ILjava/lang/String;JLjava/lang/String;)V");
    }
    env_->CallVoidMethod(diagnostics_obj_, mid_, level_value, path, line, message);
  }

 private:
  JNIEnv* env_;
  jobject diagnostics_obj_;
  jmethodID mid_;
  DISALLOW_COPY_AND_ASSIGN(JniDiagnostics);
};

JNIEXPORT jint JNICALL Java_com_android_tools_aapt2_Aapt2Jni_nativeCompile(
    JNIEnv* env, jclass aapt_obj, jobject arguments_obj, jobject diagnostics_obj) {
  std::vector<ScopedUtfChars> compile_args_jni =
      list_to_utfchars(env, arguments_obj);
  std::vector<StringPiece> compile_args = extract_pieces(compile_args_jni);
  JniDiagnostics diagnostics(env, diagnostics_obj);
  return aapt::CompileCommand(&diagnostics).Execute(compile_args, &std::cerr);
}

JNIEXPORT jint JNICALL Java_com_android_tools_aapt2_Aapt2Jni_nativeLink(JNIEnv* env,
                                                                        jclass aapt_obj,
                                                                        jobject arguments_obj,
                                                                        jobject diagnostics_obj) {
  std::vector<ScopedUtfChars> link_args_jni =
      list_to_utfchars(env, arguments_obj);
  std::vector<StringPiece> link_args = extract_pieces(link_args_jni);
  JniDiagnostics diagnostics(env, diagnostics_obj);
  return aapt::LinkCommand(&diagnostics).Execute(link_args, &std::cerr);
}

JNIEXPORT void JNICALL Java_com_android_tools_aapt2_Aapt2Jni_ping(
        JNIEnv *env, jclass aapt_obj) {
  // This is just a dummy method to see if the library has been loaded.
}
