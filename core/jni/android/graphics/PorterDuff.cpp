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
#include "core_jni_helpers.h"

#include "SkXfermode.h"

namespace android {

class SkPorterDuffGlue {
public:

    static jlong CreateXfermode(JNIEnv* env, jobject, jint modeHandle) {
        // validate that the Java enum values match our expectations
        SK_COMPILE_ASSERT(0  == SkXfermode::kClear_Mode,    xfermode_mismatch);
        SK_COMPILE_ASSERT(1  == SkXfermode::kSrc_Mode,      xfermode_mismatch);
        SK_COMPILE_ASSERT(2  == SkXfermode::kDst_Mode,      xfermode_mismatch);
        SK_COMPILE_ASSERT(3  == SkXfermode::kSrcOver_Mode,  xfermode_mismatch);
        SK_COMPILE_ASSERT(4  == SkXfermode::kDstOver_Mode,  xfermode_mismatch);
        SK_COMPILE_ASSERT(5  == SkXfermode::kSrcIn_Mode,    xfermode_mismatch);
        SK_COMPILE_ASSERT(6  == SkXfermode::kDstIn_Mode,    xfermode_mismatch);
        SK_COMPILE_ASSERT(7  == SkXfermode::kSrcOut_Mode,   xfermode_mismatch);
        SK_COMPILE_ASSERT(8  == SkXfermode::kDstOut_Mode,   xfermode_mismatch);
        SK_COMPILE_ASSERT(9  == SkXfermode::kSrcATop_Mode,  xfermode_mismatch);
        SK_COMPILE_ASSERT(10 == SkXfermode::kDstATop_Mode,  xfermode_mismatch);
        SK_COMPILE_ASSERT(11 == SkXfermode::kXor_Mode,      xfermode_mismatch);
        SK_COMPILE_ASSERT(16 == SkXfermode::kDarken_Mode,   xfermode_mismatch);
        SK_COMPILE_ASSERT(17 == SkXfermode::kLighten_Mode,  xfermode_mismatch);
        SK_COMPILE_ASSERT(13 == SkXfermode::kModulate_Mode, xfermode_mismatch);
        SK_COMPILE_ASSERT(14 == SkXfermode::kScreen_Mode,   xfermode_mismatch);
        SK_COMPILE_ASSERT(12 == SkXfermode::kPlus_Mode,     xfermode_mismatch);
        SK_COMPILE_ASSERT(15 == SkXfermode::kOverlay_Mode,  xfermode_mismatch);
        
        SkXfermode::Mode mode = static_cast<SkXfermode::Mode>(modeHandle);
        return reinterpret_cast<jlong>(SkXfermode::Create(mode));
    }
 
};

static JNINativeMethod methods[] = {
    {"nativeCreateXfermode","(I)J", (void*) SkPorterDuffGlue::CreateXfermode},
};

int register_android_graphics_PorterDuff(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/graphics/PorterDuffXfermode", methods, NELEM(methods));
}

}
