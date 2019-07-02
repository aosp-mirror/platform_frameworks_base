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

#include "jni.h"
#include "GraphicsJNI.h"
#include <android_runtime/AndroidRuntime.h>

#include "core_jni_helpers.h"

#include "hwui/Paint.h"
#include "hwui/PaintFilter.h"
#include "SkPaint.h"

namespace android {

class PaintFlagsFilter : public PaintFilter {
public:
    PaintFlagsFilter(uint32_t clearFlags, uint32_t setFlags) {
        fClearFlags = static_cast<uint16_t>(clearFlags);
        fSetFlags = static_cast<uint16_t>(setFlags);
    }
    void filter(SkPaint* paint) override {
        uint32_t flags = Paint::GetSkPaintJavaFlags(*paint);
        Paint::SetSkPaintJavaFlags(paint, (flags & ~fClearFlags) | fSetFlags);
    }
    void filterFullPaint(Paint* paint) override {
        paint->setJavaFlags((paint->getJavaFlags() & ~fClearFlags) | fSetFlags);
    }

private:
    uint16_t fClearFlags;
    uint16_t fSetFlags;
};

class PaintFilterGlue {
public:

    static void finalizer(JNIEnv* env, jobject clazz, jlong objHandle) {
        PaintFilter* obj = reinterpret_cast<PaintFilter*>(objHandle);
        SkSafeUnref(obj);
    }

    static jlong CreatePaintFlagsFilter(JNIEnv* env, jobject clazz,
                                        jint clearFlags, jint setFlags) {
        PaintFilter* filter = nullptr;
        if (clearFlags | setFlags) {
            filter = new PaintFlagsFilter(clearFlags, setFlags);
        }
        return reinterpret_cast<jlong>(filter);
    }
};

static const JNINativeMethod drawfilter_methods[] = {
    {"nativeDestructor", "(J)V", (void*) PaintFilterGlue::finalizer}
};

static const JNINativeMethod paintflags_methods[] = {
    {"nativeConstructor","(II)J", (void*) PaintFilterGlue::CreatePaintFlagsFilter}
};

int register_android_graphics_DrawFilter(JNIEnv* env) {
    int result = RegisterMethodsOrDie(env, "android/graphics/DrawFilter", drawfilter_methods,
                                      NELEM(drawfilter_methods));
    result |= RegisterMethodsOrDie(env, "android/graphics/PaintFlagsDrawFilter", paintflags_methods,
                                   NELEM(paintflags_methods));

    return 0;
}

}
