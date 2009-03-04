/* libs/android_runtime/android/graphics/ColorFilter.cpp
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

// This file was generated from the C++ include file: SkColorFilter.h
// Any changes made to this file will be discarded by the build.
// To change this file, either edit the include, or device/tools/gluemaker/main.cpp, 
// or one of the auxilary file specifications in device/tools/gluemaker.

#include "jni.h"
#include "GraphicsJNI.h"
#include <android_runtime/AndroidRuntime.h>

#include "SkDrawFilter.h"
#include "SkPaintFlagsDrawFilter.h"
#include "SkPaint.h"

namespace android {

class SkDrawFilterGlue {
public:

    static void finalizer(JNIEnv* env, jobject clazz, SkDrawFilter* obj) {
        obj->safeUnref();
    }

    static SkDrawFilter* CreatePaintFlagsDF(JNIEnv* env, jobject clazz,
                                           int clearFlags, int setFlags) {
        // trim off any out-of-range bits
        clearFlags &= SkPaint::kAllFlags;
        setFlags &= SkPaint::kAllFlags;

        if (clearFlags | setFlags) {
            return new SkPaintFlagsDrawFilter(clearFlags, setFlags);
        } else {
            return NULL;
        }
    }
};

static JNINativeMethod drawfilter_methods[] = {
    {"nativeDestructor", "(I)V", (void*) SkDrawFilterGlue::finalizer}
};

static JNINativeMethod paintflags_methods[] = {
    {"nativeConstructor","(II)I", (void*) SkDrawFilterGlue::CreatePaintFlagsDF}
};

#define REG(env, name, array)                                                                       \
    result = android::AndroidRuntime::registerNativeMethods(env, name, array, SK_ARRAY_COUNT(array));  \
    if (result < 0) return result


int register_android_graphics_DrawFilter(JNIEnv* env) {
    int result;
    
    REG(env, "android/graphics/DrawFilter", drawfilter_methods);
    REG(env, "android/graphics/PaintFlagsDrawFilter", paintflags_methods);
    
    return 0;
}

}
