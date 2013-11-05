/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "GLRenderer"

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

#include <renderthread/RenderTask.h>
#include <renderthread/RenderThread.h>

namespace android {

#ifdef USE_OPENGL_RENDERER

namespace RT = android::uirenderer::renderthread;

static jmethodID gRunnableMethod;

class JavaTask : public RT::RenderTask {
public:
    JavaTask(JNIEnv* env, jobject jrunnable) {
        env->GetJavaVM(&mVm);
        mRunnable = env->NewGlobalRef(jrunnable);
    }

    virtual ~JavaTask() {
        env()->DeleteGlobalRef(mRunnable);
    }

    virtual void run() {
        env()->CallVoidMethod(mRunnable, gRunnableMethod);
    };

private:
    JNIEnv* env() {
        JNIEnv* env;
        if (mVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            return 0;
        }
        return env;
    }

    JavaVM* mVm;
    jobject mRunnable;
};

static void android_view_ThreadedRenderer_postToRenderThread(JNIEnv* env, jobject clazz,
        jobject jrunnable) {
    RT::RenderTask* task = new JavaTask(env, jrunnable);
    RT::RenderThread::getInstance().queue(task);
}

#endif

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/ThreadedRenderer";

static JNINativeMethod gMethods[] = {
#ifdef USE_OPENGL_RENDERER
    { "postToRenderThread", "(Ljava/lang/Runnable;)V",   (void*) android_view_ThreadedRenderer_postToRenderThread },
#endif
};

int register_android_view_ThreadedRenderer(JNIEnv* env) {
#ifdef USE_OPENGL_RENDERER
    jclass cls = env->FindClass("java/lang/Runnable");
    gRunnableMethod = env->GetMethodID(cls, "run", "()V");
    env->DeleteLocalRef(cls);
#endif
    return AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}

}; // namespace android
