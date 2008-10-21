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

#include "jni.h"
#include "GraphicsJNI.h"
#include <android_runtime/AndroidRuntime.h>

#include "SkPathMeasure.h"

/*  We declare an explicit pair, so that we don't have to rely on the java
    client to be sure not to edit the path while we have an active measure
    object associated with it.
 
    This costs us the copy of the path, for the sake of not allowing a bad
    java client to randomly crash (since we can't detect the case where the
    native path has been modified).
 
    The C side does have this risk, but it chooses for speed over safety. If it
    later changes this, and is internally safe from changes to the path, then
    we can remove this explicit copy from our JNI code.
 
    Note that we do not have a reference on the java side to the java path.
    Were we to not need the native copy here, we would want to add a java
    reference, so that the java path would not get GD'd while the measure object
    was still alive.
*/
struct PathMeasurePair {
    PathMeasurePair() {}
    PathMeasurePair(const SkPath& path, bool forceClosed)
        : fPath(path), fMeasure(fPath, forceClosed) {}

    SkPath          fPath;      // copy of the user's path
    SkPathMeasure   fMeasure;   // this guy points to fPath
};

namespace android {
    
class SkPathMeasureGlue {
public:

    static PathMeasurePair* create(JNIEnv* env, jobject clazz, const SkPath* path, jboolean forceClosed) {
        return path ? new PathMeasurePair(*path, forceClosed) : new PathMeasurePair;
    }
 
    static void setPath(JNIEnv* env, jobject clazz, PathMeasurePair* pair, const SkPath* path, jboolean forceClosed) {
        if (NULL == path) {
            pair->fPath.reset();
        } else {
            pair->fPath = *path;
        }
        pair->fMeasure.setPath(&pair->fPath, forceClosed);
    }
 
    static jfloat getLength(JNIEnv* env, jobject clazz, PathMeasurePair* pair) {
        return SkScalarToFloat(pair->fMeasure.getLength());
    }
 
    static void convertTwoElemFloatArray(JNIEnv* env, jfloatArray array, const SkScalar src[2]) {
        AutoJavaFloatArray autoArray(env, array, 2);
        jfloat* ptr = autoArray.ptr();
        ptr[0] = SkScalarToFloat(src[0]);
        ptr[1] = SkScalarToFloat(src[1]);
    }

    static jboolean getPosTan(JNIEnv* env, jobject clazz, PathMeasurePair* pair, jfloat dist, jfloatArray pos, jfloatArray tan) {
        SkScalar    tmpPos[2], tmpTan[2];
        SkScalar*   posPtr = pos ? tmpPos : NULL;
        SkScalar*   tanPtr = tan ? tmpTan : NULL;
        
        if (!pair->fMeasure.getPosTan(SkFloatToScalar(dist), (SkPoint*)posPtr, (SkVector*)tanPtr)) {
            return false;
        }
    
        if (pos) {
            convertTwoElemFloatArray(env, pos, tmpPos);
        }
        if (tan) {
            convertTwoElemFloatArray(env, tan, tmpTan);
        }
        return true;
    }
 
    static jboolean getMatrix(JNIEnv* env, jobject clazz, PathMeasurePair* pair, jfloat dist,
                          SkMatrix* matrix, int flags) {
        return pair->fMeasure.getMatrix(SkFloatToScalar(dist), matrix, (SkPathMeasure::MatrixFlags)flags);
    }
 
    static jboolean getSegment(JNIEnv* env, jobject clazz, PathMeasurePair* pair, jfloat startF,
                               jfloat stopF, SkPath* dst, jboolean startWithMoveTo) {
        return pair->fMeasure.getSegment(SkFloatToScalar(startF), SkFloatToScalar(stopF), dst, startWithMoveTo);
    }
 
    static jboolean isClosed(JNIEnv* env, jobject clazz, PathMeasurePair* pair) {
        return pair->fMeasure.isClosed();
    }
 
    static jboolean nextContour(JNIEnv* env, jobject clazz, PathMeasurePair* pair) {
        return pair->fMeasure.nextContour();
    }
 
    static void destroy(JNIEnv* env, jobject clazz, PathMeasurePair* pair) {
        delete pair;
    } 
};

static JNINativeMethod methods[] = {
    {"native_create",       "(IZ)I",        (void*) SkPathMeasureGlue::create      },
    {"native_setPath",      "(IIZ)V",       (void*) SkPathMeasureGlue::setPath     },
    {"native_getLength",    "(I)F",         (void*) SkPathMeasureGlue::getLength   },
    {"native_getPosTan",    "(IF[F[F)Z",    (void*) SkPathMeasureGlue::getPosTan   },
    {"native_getMatrix",    "(IFII)Z",      (void*) SkPathMeasureGlue::getMatrix   },
    {"native_getSegment",   "(IFFIZ)Z",     (void*) SkPathMeasureGlue::getSegment  },
    {"native_isClosed",     "(I)Z",         (void*) SkPathMeasureGlue::isClosed    },
    {"native_nextContour",  "(I)Z",         (void*) SkPathMeasureGlue::nextContour },
    {"native_destroy",      "(I)V",         (void*) SkPathMeasureGlue::destroy     }
};

int register_android_graphics_PathMeasure(JNIEnv* env) {
    int result = AndroidRuntime::registerNativeMethods(env, "android/graphics/PathMeasure", methods,
        sizeof(methods) / sizeof(methods[0]));
    return result;
}

}
