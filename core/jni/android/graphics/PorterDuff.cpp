/* libs/android_runtime/android/graphics/PorterDuff.cpp
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

// This file was generated from the C++ include file: SkPorterDuff.h
// Any changes made to this file will be discarded by the build.
// To change this file, either edit the include, or device/tools/gluemaker/main.cpp, 
// or one of the auxilary file specifications in device/tools/gluemaker.

#include "jni.h"
#include "GraphicsJNI.h"
#include <android_runtime/AndroidRuntime.h>

#include "SkPorterDuff.h"

namespace android {

class SkPorterDuffGlue {
public:

    static jlong CreateXfermode(JNIEnv* env, jobject, jint modeHandle) {
        SkPorterDuff::Mode mode = static_cast<SkPorterDuff::Mode>(modeHandle);
        return reinterpret_cast<jlong>(SkPorterDuff::CreateXfermode(mode));
    }
 
};

static JNINativeMethod methods[] = {
    {"nativeCreateXfermode","(I)J", (void*) SkPorterDuffGlue::CreateXfermode},
};

int register_android_graphics_PorterDuff(JNIEnv* env) {
    int result = AndroidRuntime::registerNativeMethods(env,
                                "android/graphics/PorterDuffXfermode", methods,
                                        sizeof(methods) / sizeof(methods[0]));
    return result;
}

}
