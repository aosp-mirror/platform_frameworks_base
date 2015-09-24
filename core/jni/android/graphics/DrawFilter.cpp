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

#include "core_jni_helpers.h"

#include "SkDrawFilter.h"
#include "SkPaintFlagsDrawFilter.h"
#include "SkPaint.h"

namespace android {

// Custom version of SkPaintFlagsDrawFilter that also calls setFilterQuality.
class CompatFlagsDrawFilter : public SkPaintFlagsDrawFilter {
public:
    CompatFlagsDrawFilter(uint32_t clearFlags, uint32_t setFlags,
            SkFilterQuality desiredQuality)
    : SkPaintFlagsDrawFilter(clearFlags, setFlags)
    , fDesiredQuality(desiredQuality) {
    }

    virtual bool filter(SkPaint* paint, Type type) {
        SkPaintFlagsDrawFilter::filter(paint, type);
        paint->setFilterQuality(fDesiredQuality);
        return true;
    }

private:
    const SkFilterQuality fDesiredQuality;
};

// Returns whether flags contains FILTER_BITMAP_FLAG. If flags does, remove it.
static inline bool hadFiltering(jint& flags) {
    // Equivalent to the Java Paint's FILTER_BITMAP_FLAG.
    static const uint32_t sFilterBitmapFlag = 0x02;

    const bool result = (flags & sFilterBitmapFlag) != 0;
    flags &= ~sFilterBitmapFlag;
    return result;
}

class SkDrawFilterGlue {
public:

    static void finalizer(JNIEnv* env, jobject clazz, jlong objHandle) {
        SkDrawFilter* obj = reinterpret_cast<SkDrawFilter*>(objHandle);
        SkSafeUnref(obj);
    }

    static jlong CreatePaintFlagsDF(JNIEnv* env, jobject clazz,
                                    jint clearFlags, jint setFlags) {
        if (clearFlags | setFlags) {
            // Mask both groups of flags to remove FILTER_BITMAP_FLAG, which no
            // longer has a Skia equivalent flag (instead it corresponds to
            // calling setFilterQuality), and keep track of which group(s), if
            // any, had the flag set.
            const bool turnFilteringOn = hadFiltering(setFlags);
            const bool turnFilteringOff = hadFiltering(clearFlags);

            SkDrawFilter* filter;
            if (turnFilteringOn) {
                // Turning filtering on overrides turning it off.
                filter = new CompatFlagsDrawFilter(clearFlags, setFlags,
                        kLow_SkFilterQuality);
            } else if (turnFilteringOff) {
                filter = new CompatFlagsDrawFilter(clearFlags, setFlags,
                        kNone_SkFilterQuality);
            } else {
                filter = new SkPaintFlagsDrawFilter(clearFlags, setFlags);
            }
            return reinterpret_cast<jlong>(filter);
        } else {
            return NULL;
        }
    }
};

static const JNINativeMethod drawfilter_methods[] = {
    {"nativeDestructor", "(J)V", (void*) SkDrawFilterGlue::finalizer}
};

static const JNINativeMethod paintflags_methods[] = {
    {"nativeConstructor","(II)J", (void*) SkDrawFilterGlue::CreatePaintFlagsDF}
};

int register_android_graphics_DrawFilter(JNIEnv* env) {
    int result = RegisterMethodsOrDie(env, "android/graphics/DrawFilter", drawfilter_methods,
                                      NELEM(drawfilter_methods));
    result |= RegisterMethodsOrDie(env, "android/graphics/PaintFlagsDrawFilter", paintflags_methods,
                                   NELEM(paintflags_methods));
    
    return 0;
}

}
