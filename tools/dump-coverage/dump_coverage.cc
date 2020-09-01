// Copyright (C) 2018 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

#include <android-base/logging.h>
#include <jni.h>
#include <jvmti.h>
#include <string.h>

#include <fstream>

using std::get;
using std::tuple;

namespace dump_coverage {

#define CHECK_JVMTI(x) CHECK_EQ((x), JVMTI_ERROR_NONE)
#define CHECK_NOTNULL(x) CHECK((x) != nullptr)
#define CHECK_NO_EXCEPTION(env) CHECK(!(env)->ExceptionCheck());

static JavaVM* java_vm = nullptr;

// Get the current JNI environment.
static JNIEnv* GetJNIEnv() {
  JNIEnv* env = nullptr;
  CHECK_EQ(java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6),
           JNI_OK);
  return env;
}

// Get the JaCoCo Agent class and an instance of the class, given a JNI
// environment.
// Will crash if the Agent isn't found or if any Java Exception occurs.
static tuple<jclass, jobject> GetJavaAgent(JNIEnv* env) {
  jclass java_agent_class =
      env->FindClass("org/jacoco/agent/rt/internal/Agent");
  CHECK_NOTNULL(java_agent_class);

  jmethodID java_agent_get_instance =
      env->GetStaticMethodID(java_agent_class, "getInstance",
                             "()Lorg/jacoco/agent/rt/internal/Agent;");
  CHECK_NOTNULL(java_agent_get_instance);

  jobject java_agent_instance =
      env->CallStaticObjectMethod(java_agent_class, java_agent_get_instance);
  CHECK_NO_EXCEPTION(env);
  CHECK_NOTNULL(java_agent_instance);

  return tuple(java_agent_class, java_agent_instance);
}

// Runs equivalent of Agent.getInstance().getExecutionData(false) and returns
// the result.
// Will crash if the Agent isn't found or if any Java Exception occurs.
static jbyteArray GetExecutionData(JNIEnv* env) {
  auto java_agent = GetJavaAgent(env);
  jmethodID java_agent_get_execution_data =
      env->GetMethodID(get<0>(java_agent), "getExecutionData", "(Z)[B");
  CHECK_NO_EXCEPTION(env);
  CHECK_NOTNULL(java_agent_get_execution_data);

  jbyteArray java_result_array = (jbyteArray)env->CallObjectMethod(
      get<1>(java_agent), java_agent_get_execution_data, false);
  CHECK_NO_EXCEPTION(env);

  return java_result_array;
}

// Writes the execution data to a file.
//  data, length: represent the data, as a sequence of bytes.
//  filename: file to write coverage data to.
//  returns JNI_ERR if there is an error in writing the file, otherwise JNI_OK.
static jint WriteFile(const char* data, int length, const std::string& filename) {
  LOG(INFO) << "Writing file of length " << length << " to '" << filename
            << "'";
  std::ofstream file(filename, std::ios::binary);

  if (!file.is_open()) {
    LOG(ERROR) << "Could not open file: '" << filename << "'";
    return JNI_ERR;
  }
  file.write(data, length);
  file.close();

  if (!file) {
    LOG(ERROR) << "I/O error in reading file";
    return JNI_ERR;
  }

  LOG(INFO) << "Done writing file";
  return JNI_OK;
}

// Grabs execution data and writes it to a file.
//  filename: file to write coverage data to.
//  returns JNI_ERR if there is an error writing the file.
// Will crash if the Agent isn't found or if any Java Exception occurs.
static jint Dump(const std::string& filename) {
  LOG(INFO) << "Dumping file";

  JNIEnv* env = GetJNIEnv();
  jbyteArray java_result_array = GetExecutionData(env);
  CHECK_NOTNULL(java_result_array);

  jbyte* result_ptr = env->GetByteArrayElements(java_result_array, 0);
  CHECK_NOTNULL(result_ptr);

  int result_len = env->GetArrayLength(java_result_array);

  return WriteFile((const char*) result_ptr, result_len, filename);
}

// Resets execution data, performing the equivalent of
//  Agent.getInstance().reset();
//  args: should be empty.
//  returns JNI_ERR if the arguments are invalid.
// Will crash if the Agent isn't found or if any Java Exception occurs.
static jint Reset(const std::string& args) {
  if (args != "") {
    LOG(ERROR) << "reset takes no arguments, but received '" << args << "'";
    return JNI_ERR;
  }

  JNIEnv* env = GetJNIEnv();
  auto java_agent = GetJavaAgent(env);

  jmethodID java_agent_reset =
      env->GetMethodID(get<0>(java_agent), "reset", "()V");
  CHECK_NOTNULL(java_agent_reset);

  env->CallVoidMethod(get<1>(java_agent), java_agent_reset);
  CHECK_NO_EXCEPTION(env);
  return JNI_OK;
}

// Given a string of the form "<a>:<b>" returns (<a>, <b>).
// Given a string <a> that doesn't contain a colon, returns (<a>, "").
static tuple<std::string, std::string> SplitOnColon(const std::string& options) {
  size_t loc_delim = options.find(':');
  std::string command, args;

  if (loc_delim == std::string::npos) {
    command = options;
  } else {
    command = options.substr(0, loc_delim);
    args = options.substr(loc_delim + 1, options.length());
  }
  return tuple(command, args);
}

// Parses and executes a command specified by options of the form
// "<command>:<args>" where <command> is either "dump" or "reset".
static jint ParseOptionsAndExecuteCommand(const std::string& options) {
  auto split = SplitOnColon(options);
  auto command = get<0>(split), args = get<1>(split);

  LOG(INFO) << "command: '" << command << "' args: '" << args << "'";

  if (command == "dump") {
    return Dump(args);
  }

  if (command == "reset") {
    return Reset(args);
  }

  LOG(ERROR) << "Invalid command: expected 'dump' or 'reset' but was '"
             << command << "'";
  return JNI_ERR;
}

static jint AgentStart(JavaVM* vm, char* options) {
  android::base::InitLogging(/* argv= */ nullptr);
  java_vm = vm;

  return ParseOptionsAndExecuteCommand(options);
}

// Late attachment (e.g. 'am attach-agent').
extern "C" JNIEXPORT jint JNICALL
Agent_OnAttach(JavaVM* vm, char* options, void* reserved ATTRIBUTE_UNUSED) {
  return AgentStart(vm, options);
}

// Early attachment.
extern "C" JNIEXPORT jint JNICALL
Agent_OnLoad(JavaVM* jvm ATTRIBUTE_UNUSED, char* options ATTRIBUTE_UNUSED, void* reserved ATTRIBUTE_UNUSED) {
  LOG(ERROR)
    << "The dumpcoverage agent will not work on load,"
    << " as it does not have access to the runtime.";
  return JNI_ERR;
}

}  // namespace dump_coverage
