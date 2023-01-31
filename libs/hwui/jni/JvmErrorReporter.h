/*
 * Copyright (C) 2022 The Android Open Source Project
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
#ifndef JVMERRORREPORTER_H
#define JVMERRORREPORTER_H

#include <TreeInfo.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>

#include "GraphicsJNI.h"

namespace android {
namespace uirenderer {

class JvmErrorReporter : public android::uirenderer::ErrorHandler {
public:
    JvmErrorReporter(JNIEnv* env) { env->GetJavaVM(&mVm); }

    virtual void onError(const std::string& message) override {
        JNIEnv* env = GraphicsJNI::getJNIEnv();
        jniThrowException(env, "java/lang/IllegalStateException", message.c_str());
    }

private:
    JavaVM* mVm;
};

}  // namespace uirenderer
}  // namespace android

#endif  // JVMERRORREPORTER_H
