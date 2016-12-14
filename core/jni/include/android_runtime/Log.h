/*
 * Copyright (C) 2013 The Android Open Source Project
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

#ifndef _RUNTIME_ANDROID_LOG_H
#define _RUNTIME_ANDROID_LOG_H

// This relies on JNIHelp.h

/* Logging macros.
 *
 * Logs an exception.  If the exception is omitted or NULL, logs the current exception
 * from the JNI environment, if any.
 */
#define LOG_EX(env, priority, tag, ...) \
    jniLogException(env, ANDROID_##priority, tag, ##__VA_ARGS__)
#define LOGV_EX(env, ...) LOG_EX(env, LOG_VERBOSE, LOG_TAG, ##__VA_ARGS__)
#define LOGD_EX(env, ...) LOG_EX(env, LOG_DEBUG, LOG_TAG, ##__VA_ARGS__)
#define LOGI_EX(env, ...) LOG_EX(env, LOG_INFO, LOG_TAG, ##__VA_ARGS__)
#define LOGW_EX(env, ...) LOG_EX(env, LOG_WARN, LOG_TAG, ##__VA_ARGS__)
#define LOGE_EX(env, ...) LOG_EX(env, LOG_ERROR, LOG_TAG, ##__VA_ARGS__)

#endif // _RUNTIME_ANDROID_LOG_H
