/*
 * Copyright 2024 The Android Open Source Project
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
#define LOG_TAG "InputTransferToken"

#include <android/input_transfer_token_jni.h>
#include <android_runtime/android_window_InputTransferToken.h>
#include <gui/InputTransferToken.h>
#include <log/log_main.h>

using namespace android;

#define CHECK_NOT_NULL(name) \
    LOG_ALWAYS_FATAL_IF(name == nullptr, "nullptr passed as " #name " argument");

extern void InputTransferToken_acquire(InputTransferToken* inputTransferToken) {
    // incStrong/decStrong token must be the same, doesn't matter what it is
    inputTransferToken->incStrong((void*)InputTransferToken_acquire);
}

void InputTransferToken_release(InputTransferToken* inputTransferToken) {
    // incStrong/decStrong token must be the same, doesn't matter what it is
    inputTransferToken->decStrong((void*)InputTransferToken_acquire);
}

AInputTransferToken* AInputTransferToken_fromJava(JNIEnv* env, jobject inputTransferTokenObj) {
    CHECK_NOT_NULL(env);
    CHECK_NOT_NULL(inputTransferTokenObj);
    InputTransferToken* inputTransferToken =
            android_window_InputTransferToken_getNativeInputTransferToken(env,
                                                                          inputTransferTokenObj);
    CHECK_NOT_NULL(inputTransferToken);
    InputTransferToken_acquire(inputTransferToken);
    return reinterpret_cast<AInputTransferToken*>(inputTransferToken);
}

jobject AInputTransferToken_toJava(JNIEnv* _Nonnull env,
                                   const AInputTransferToken* aInputTransferToken) {
    CHECK_NOT_NULL(env);
    CHECK_NOT_NULL(aInputTransferToken);
    const InputTransferToken* inputTransferToken =
            reinterpret_cast<const InputTransferToken*>(aInputTransferToken);
    return android_window_InputTransferToken_getJavaInputTransferToken(env, *inputTransferToken);
}

void AInputTransferToken_release(AInputTransferToken* aInputTransferToken) {
    CHECK_NOT_NULL(aInputTransferToken);
    InputTransferToken* inputTransferToken =
            reinterpret_cast<InputTransferToken*>(aInputTransferToken);
    InputTransferToken_release(inputTransferToken);
}
