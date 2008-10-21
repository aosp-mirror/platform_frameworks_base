/* libs/android_runtime/android/graphics/Rasterizer.cpp
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

// This file was generated from the C++ include file: SkRasterizer.h
// Any changes made to this file will be discarded by the build.
// To change this file, either edit the include, or device/tools/gluemaker/main.cpp, 
// or one of the auxilary file specifications in device/tools/gluemaker.

#include "jni.h"
#include "GraphicsJNI.h"
#include <android_runtime/AndroidRuntime.h>

#include "SkRasterizer.h"

namespace android {

class SkRasterizerGlue {
public:

    static void finalizer(JNIEnv* env, jobject clazz, SkRasterizer* obj) {
        obj->safeUnref();
    }
 
};

static JNINativeMethod methods[] = {
    {"finalizer", "(I)V", (void*) SkRasterizerGlue::finalizer}
};

int register_android_graphics_Rasterizer(JNIEnv* env) {
    int result = AndroidRuntime::registerNativeMethods(env, "android/graphics/Rasterizer", methods,
        sizeof(methods) / sizeof(methods[0]));
    return result;
}

}
