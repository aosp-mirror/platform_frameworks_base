/*
 * Copyright (C) 2008 The Android Open Source Project
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

#ifndef _COM_ANDROID_INTERNAL_OS_ZYGOTE_H
#define _COM_ANDROID_INTERNAL_OS_ZYGOTE_H

#define LOG_TAG "Zygote"
#define ATRACE_TAG ATRACE_TAG_DALVIK

/* Functions in the callchain during the fork shall not be protected with
   Armv8.3-A Pointer Authentication, otherwise child will not be able to return. */
#ifdef __ARM_FEATURE_PAC_DEFAULT
#ifdef __ARM_FEATURE_BTI_DEFAULT
#define NO_PAC_FUNC __attribute__((target("branch-protection=bti")))
#else
#define NO_PAC_FUNC __attribute__((target("branch-protection=none")))
#endif /* __ARM_FEATURE_BTI_DEFAULT */
#else /* !__ARM_FEATURE_PAC_DEFAULT */
#define NO_PAC_FUNC
#endif /* __ARM_FEATURE_PAC_DEFAULT */

#include <jni.h>
#include <vector>
#include <android-base/stringprintf.h>

#define CREATE_ERROR(...) StringPrintf("%s:%d: ", __FILE__, __LINE__). \
                              append(StringPrintf(__VA_ARGS__))

namespace android {
namespace zygote {

NO_PAC_FUNC
pid_t ForkCommon(JNIEnv* env,bool is_system_server,
                 const std::vector<int>& fds_to_close,
                 const std::vector<int>& fds_to_ignore,
                 bool is_priority_fork,
                 bool purge = true);

/**
 * Fork a process. The pipe fds are used for usap communication, or -1 in
 * other cases. Session_socket_fds are FDs used for zygote communication that must be dealt
 * with hygienically, but are not otherwise used here. Args_known indicates that the process
 * will be immediately specialized with arguments that are already known, so no usap
 * communication is required. Is_priority_fork should be true if this is on the app startup
 * critical path. Purge specifies that unused pages should be purged before the fork.
 */
NO_PAC_FUNC
int forkApp(JNIEnv* env,
            int read_pipe_fd,
            int write_pipe_fd,
            const std::vector<int>& session_socket_fds,
            bool args_known,
            bool is_priority_fork,
            bool purge);

[[noreturn]]
void ZygoteFailure(JNIEnv* env,
                   const char* process_name,
                   jstring managed_process_name,
                   const std::string& msg);

}  // namespace zygote
}  // namespace android

#endif // _COM_ANDROID_INTERNAL_OS_ZYGOTE_
