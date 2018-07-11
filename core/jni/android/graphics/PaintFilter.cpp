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

#include "hwui/PaintFilter.h"
#include "SkPaint.h"

namespace android {

class PaintFlagsFilter : public PaintFilter {
public:
    PaintFlagsFilter(uint32_t clearFlags, uint32_t setFlags) {
        fClearFlags = static_cast<uint16_t>(clearFlags & SkPaint::kAllFlags);
        fSetFlags = static_cast<uint16_t>(setFlags & SkPaint::kAllFlags);
    }
    void filter(SkPaint* paint) override {
        paint->setFlags((paint->getFlags() & ~fClearFlags) | fSetFlags);
    }

private:
    uint16_t fClearFlags;
    uint16_t fSetFlags;
};

// Custom version of PaintFlagsDrawFilter that also calls setFilterQuality.
class CompatPaintFlagsFilter : public PaintFlagsFilter {
public:
    CompatPaintFlagsFilter(uint32_t clearFlags, uint32_t setFlags, SkFilterQuality desiredQuality)
    : PaintFlagsFilter(clearFlags, setFlags)
    , fDesiredQuality(desiredQuality) {
    }

    virtual void filter(SkPaint* paint) {
        PaintFlagsFilter::filter(paint);
        paint->setFilterQuality(fDesiredQuality);
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

class PaintFilterGlue {
public:

    static void finalizer(JNIEnv* env, jobject clazz, jlong objHandle) {
        PaintFilter* obj = reinterpret_cast<PaintFilter*>(objHandle);
        SkSafeUnref(obj);
    }

    static jlong CreatePaintFlagsFilter(JNIEnv* env, jobject clazz,
                                        jint clearFlags, jint setFlags) {
        if (clearFlags | setFlags) {
            // Mask both groups of flags to remove FILTER_BITMAP_FLAG, which no
            // longer has a Skia equivalent flag (instead it corresponds to
            // calling setFilterQuality), and keep track of which group(s), if
            // any, had the flag set.
            const bool turnFilteringOn = hadFiltering(setFlags);
            const bool turnFilteringOff = hadFiltering(clearFlags);

            PaintFilter* filter;
            if (turnFilteringOn) {
                // Turning filtering on overrides turning it off.
                filter = new CompatPaintFlagsFilter(clearFlags, setFlags,
                        kLow_SkFilterQuality);
            } else if (turnFilteringOff) {
                filter = new CompatPaintFlagsFilter(clearFlags, setFlags,
                        kNone_SkFilterQuality);
            } else {
                filter = new PaintFlagsFilter(clearFlags, setFlags);
            }
            return reinterpret_cast<jlong>(filter);
        } else {
            return NULL;
        }
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
