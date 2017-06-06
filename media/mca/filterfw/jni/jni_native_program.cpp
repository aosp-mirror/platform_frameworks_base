/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include <string>
#include <vector>

#include "jni/jni_native_program.h"
#include "jni/jni_util.h"

#include "native/base/logging.h"
#include "native/core/native_frame.h"
#include "native/core/native_program.h"

using android::filterfw::NativeFrame;
using android::filterfw::NativeProgram;

jboolean Java_android_filterfw_core_NativeProgram_allocate(JNIEnv* env, jobject thiz) {
  std::unique_ptr<NativeProgram> program(new NativeProgram());
  return ToJBool(WrapOwnedObjectInJava(std::move(program), env, thiz, true));
}

jboolean Java_android_filterfw_core_NativeProgram_deallocate(JNIEnv* env, jobject thiz) {
  return ToJBool(DeleteNativeObject<NativeProgram>(env, thiz));
}

jboolean Java_android_filterfw_core_NativeProgram_nativeInit(JNIEnv* env, jobject thiz) {
  NativeProgram* program = ConvertFromJava<NativeProgram>(env, thiz);
  return ToJBool(program && program->CallInit());
}

jboolean Java_android_filterfw_core_NativeProgram_openNativeLibrary(JNIEnv* env,
                                                                    jobject thiz,
                                                                    jstring lib_name) {
  NativeProgram* program = ConvertFromJava<NativeProgram>(env, thiz);
  return ToJBool(program && lib_name && program->OpenLibrary(ToCppString(env, lib_name)));
}

jboolean Java_android_filterfw_core_NativeProgram_bindInitFunction(JNIEnv* env,
                                                                   jobject thiz,
                                                                   jstring func_name) {
  NativeProgram* program = ConvertFromJava<NativeProgram>(env, thiz);
  return ToJBool(program && func_name && program->BindInitFunction(ToCppString(env, func_name)));
}

jboolean Java_android_filterfw_core_NativeProgram_bindSetValueFunction(JNIEnv* env,
                                                                       jobject thiz,
                                                                       jstring func_name) {
  NativeProgram* program = ConvertFromJava<NativeProgram>(env, thiz);
  return ToJBool(program &&
                 func_name &&
                 program->BindSetValueFunction(ToCppString(env, func_name)));
}

jboolean Java_android_filterfw_core_NativeProgram_bindGetValueFunction(JNIEnv* env,
                                                                       jobject thiz,
                                                                       jstring func_name) {
  NativeProgram* program = ConvertFromJava<NativeProgram>(env, thiz);
  return ToJBool(program &&
                 func_name &&
                 program->BindGetValueFunction(ToCppString(env, func_name)));
}

jboolean Java_android_filterfw_core_NativeProgram_bindProcessFunction(JNIEnv* env,
                                                                      jobject thiz,
                                                                      jstring func_name) {
  NativeProgram* program = ConvertFromJava<NativeProgram>(env, thiz);
  return ToJBool(program && func_name && program->BindProcessFunction(ToCppString(env, func_name)));
}

jboolean Java_android_filterfw_core_NativeProgram_bindResetFunction(JNIEnv* env,
                                                                    jobject thiz,
                                                                    jstring func_name) {
  NativeProgram* program = ConvertFromJava<NativeProgram>(env, thiz);
  return ToJBool(program &&
                 func_name &&
                 program->BindResetFunction(ToCppString(env, func_name)));
}

jboolean Java_android_filterfw_core_NativeProgram_bindTeardownFunction(JNIEnv* env,
                                                                       jobject thiz,
                                                                       jstring func_name) {
  NativeProgram* program = ConvertFromJava<NativeProgram>(env, thiz);
  return ToJBool(program &&
                 func_name &&
                 program->BindTeardownFunction(ToCppString(env, func_name)));
}

jboolean Java_android_filterfw_core_NativeProgram_callNativeInit(JNIEnv* env, jobject thiz) {
  NativeProgram* program = ConvertFromJava<NativeProgram>(env, thiz);
  return ToJBool(program && program->CallInit());
}

jboolean Java_android_filterfw_core_NativeProgram_callNativeSetValue(JNIEnv* env,
                                                                     jobject thiz,
                                                                     jstring key,
                                                                     jstring value) {
  if (!value) {
    ALOGE("Native Program: Attempting to set null value for key %s!",
         ToCppString(env, key).c_str());
  }
  NativeProgram* program = ConvertFromJava<NativeProgram>(env, thiz);
  const std::string c_value = ToCppString(env, value);
  const std::string c_key = ToCppString(env, key);
  return ToJBool(program && program->CallSetValue(c_key, c_value));
}

jstring Java_android_filterfw_core_NativeProgram_callNativeGetValue(JNIEnv* env,
                                                                    jobject thiz,
                                                                    jstring key) {
  NativeProgram* program = ConvertFromJava<NativeProgram>(env, thiz);
  const std::string c_key = ToCppString(env, key);
  if (program) {
    return ToJString(env, program->CallGetValue(c_key));
  }
  return JNI_FALSE;
}

jboolean Java_android_filterfw_core_NativeProgram_callNativeProcess(JNIEnv* env,
                                                                    jobject thiz,
                                                                    jobjectArray inputs,
                                                                    jobject output) {
  NativeProgram* program = ConvertFromJava<NativeProgram>(env, thiz);

  // Sanity checks
  if (!program || !inputs) {
    return JNI_FALSE;
  }

  // Get the input buffers
  const int input_count = env->GetArrayLength(inputs);
  std::vector<const char*> input_buffers(input_count, NULL);
  std::vector<int> input_sizes(input_count, 0);
  for (int i = 0 ; i < input_count; ++i) {
    const char* input_data = NULL;
    int input_size = 0;
    jobject input = env->GetObjectArrayElement(inputs, i);
    if (input) {
        NativeFrame* native_frame = ConvertFromJava<NativeFrame>(env, input);
        if (!native_frame) {
          ALOGE("NativeProgram: Could not grab NativeFrame input %d!", i);
          return JNI_FALSE;
        }
        input_data = reinterpret_cast<const char*>(native_frame->Data());
        input_size = native_frame->Size();
    }
    input_buffers[i] = input_data;
    input_sizes[i] = input_size;
  }

  // Get the output buffer
  char* output_data = NULL;
  int output_size = 0;
  if (output) {
    NativeFrame* output_frame = ConvertFromJava<NativeFrame>(env, output);
    if (!output_frame) {
      ALOGE("NativeProgram: Could not grab NativeFrame output!");
      return JNI_FALSE;
    }
    output_data = reinterpret_cast<char*>(output_frame->MutableData());
    output_size = output_frame->Size();
  }

  // Process the frames!
  return ToJBool(program->CallProcess(input_buffers, input_sizes, output_data, output_size));
}

jboolean Java_android_filterfw_core_NativeProgram_callNativeReset(JNIEnv* env, jobject thiz) {
  NativeProgram* program = ConvertFromJava<NativeProgram>(env, thiz);
  return ToJBool(program && program->CallReset());
}

jboolean Java_android_filterfw_core_NativeProgram_callNativeTeardown(JNIEnv* env, jobject thiz) {
  NativeProgram* program = ConvertFromJava<NativeProgram>(env, thiz);
  return ToJBool(program && program->CallTeardown());
}
