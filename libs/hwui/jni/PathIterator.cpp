/* libs/android_runtime/android/graphics/PathMeasure.cpp
**
** Copyright 2007, The Android Open Source Project
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

#include <log/log.h>

#include "GraphicsJNI.h"
#include "SkPath.h"
#include "SkPoint.h"

namespace android {

class SkPathIteratorGlue {
public:
    static void finalizer(SkPath::RawIter* obj) { delete obj; }

    static jlong getFinalizer(JNIEnv* env, jclass clazz) {
        return static_cast<jlong>(reinterpret_cast<uintptr_t>(&finalizer));
    }

    static jlong create(JNIEnv* env, jobject clazz, jlong pathHandle) {
        const SkPath* path = reinterpret_cast<SkPath*>(pathHandle);
        return reinterpret_cast<jlong>(new SkPath::RawIter(*path));
    }

    // ---------------- @CriticalNative -------------------------

    static jint peek(CRITICAL_JNI_PARAMS_COMMA jlong iteratorHandle) {
        SkPath::RawIter* iterator = reinterpret_cast<SkPath::RawIter*>(iteratorHandle);
        return iterator->peek();
    }

    static jint next(CRITICAL_JNI_PARAMS_COMMA jlong iteratorHandle, jlong pointsArray) {
        static_assert(SkPath::kMove_Verb == 0, "SkPath::Verb unexpected index");
        static_assert(SkPath::kLine_Verb == 1, "SkPath::Verb unexpected index");
        static_assert(SkPath::kQuad_Verb == 2, "SkPath::Verb unexpected index");
        static_assert(SkPath::kConic_Verb == 3, "SkPath::Verb unexpected index");
        static_assert(SkPath::kCubic_Verb == 4, "SkPath::Verb unexpected index");
        static_assert(SkPath::kClose_Verb == 5, "SkPath::Verb unexpected index");
        static_assert(SkPath::kDone_Verb == 6, "SkPath::Verb unexpected index");

        SkPath::RawIter* iterator = reinterpret_cast<SkPath::RawIter*>(iteratorHandle);
        float* points = reinterpret_cast<float*>(pointsArray);
        SkPath::Verb verb =
                static_cast<SkPath::Verb>(iterator->next(reinterpret_cast<SkPoint*>(points)));
        if (verb == SkPath::kConic_Verb) {
            float weight = iterator->conicWeight();
            points[6] = weight;
        }
        return static_cast<int>(verb);
    }
};

static const JNINativeMethod methods[] = {
        {"nCreate", "(J)J", (void*)SkPathIteratorGlue::create},
        {"nGetFinalizer", "()J", (void*)SkPathIteratorGlue::getFinalizer},

        // ------- @CriticalNative below here ------------------

        {"nPeek", "(J)I", (void*)SkPathIteratorGlue::peek},
        {"nNext", "(JJ)I", (void*)SkPathIteratorGlue::next},
};

int register_android_graphics_PathIterator(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/graphics/PathIterator", methods, NELEM(methods));
}

}  // namespace android
