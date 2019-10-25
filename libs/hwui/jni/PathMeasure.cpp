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

#include "GraphicsJNI.h"

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

    static jlong create(JNIEnv* env, jobject clazz, jlong pathHandle,
                        jboolean forceClosedHandle) {
        const SkPath* path = reinterpret_cast<SkPath*>(pathHandle);
        bool forceClosed = (forceClosedHandle == JNI_TRUE);
        PathMeasurePair* pair;
        if(path)
            pair = new PathMeasurePair(*path, forceClosed);
        else
            pair = new PathMeasurePair;
        return reinterpret_cast<jlong>(pair);
    }

    static void setPath(JNIEnv* env, jobject clazz, jlong pairHandle,
                        jlong pathHandle, jboolean forceClosedHandle) {
        PathMeasurePair* pair = reinterpret_cast<PathMeasurePair*>(pairHandle);
        const SkPath* path = reinterpret_cast<SkPath*>(pathHandle);
        bool forceClosed = (forceClosedHandle == JNI_TRUE);

        if (NULL == path) {
            pair->fPath.reset();
        } else {
            pair->fPath = *path;
        }
        pair->fMeasure.setPath(&pair->fPath, forceClosed);
    }

    static jfloat getLength(JNIEnv* env, jobject clazz, jlong pairHandle) {
        PathMeasurePair* pair = reinterpret_cast<PathMeasurePair*>(pairHandle);
        return static_cast<jfloat>(SkScalarToFloat(pair->fMeasure.getLength()));
    }

    static void convertTwoElemFloatArray(JNIEnv* env, jfloatArray array, const SkScalar src[2]) {
        AutoJavaFloatArray autoArray(env, array, 2);
        jfloat* ptr = autoArray.ptr();
        ptr[0] = SkScalarToFloat(src[0]);
        ptr[1] = SkScalarToFloat(src[1]);
    }

    static jboolean getPosTan(JNIEnv* env, jobject clazz, jlong pairHandle, jfloat dist, jfloatArray pos, jfloatArray tan) {
        PathMeasurePair* pair = reinterpret_cast<PathMeasurePair*>(pairHandle);
        SkScalar    tmpPos[2], tmpTan[2];
        SkScalar*   posPtr = pos ? tmpPos : NULL;
        SkScalar*   tanPtr = tan ? tmpTan : NULL;
        
        if (!pair->fMeasure.getPosTan(dist, (SkPoint*)posPtr, (SkVector*)tanPtr)) {
            return JNI_FALSE;
        }
    
        if (pos) {
            convertTwoElemFloatArray(env, pos, tmpPos);
        }
        if (tan) {
            convertTwoElemFloatArray(env, tan, tmpTan);
        }
        return JNI_TRUE;
    }

    static jboolean getMatrix(JNIEnv* env, jobject clazz, jlong pairHandle, jfloat dist,
                          jlong matrixHandle, jint flags) {
        PathMeasurePair* pair = reinterpret_cast<PathMeasurePair*>(pairHandle);
        SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
        bool result = pair->fMeasure.getMatrix(dist, matrix, (SkPathMeasure::MatrixFlags)flags);
        return result ? JNI_TRUE : JNI_FALSE;
    }

    static jboolean getSegment(JNIEnv* env, jobject clazz, jlong pairHandle, jfloat startF,
                               jfloat stopF, jlong dstHandle, jboolean startWithMoveTo) {
        PathMeasurePair* pair = reinterpret_cast<PathMeasurePair*>(pairHandle);
        SkPath* dst = reinterpret_cast<SkPath*>(dstHandle);
        bool result = pair->fMeasure.getSegment(startF, stopF, dst, startWithMoveTo);
        return result ? JNI_TRUE : JNI_FALSE;
    }

    static jboolean isClosed(JNIEnv* env, jobject clazz, jlong pairHandle) {
        PathMeasurePair* pair = reinterpret_cast<PathMeasurePair*>(pairHandle);
        bool result = pair->fMeasure.isClosed();
        return result ? JNI_TRUE : JNI_FALSE;
    }

    static jboolean nextContour(JNIEnv* env, jobject clazz, jlong pairHandle) {
        PathMeasurePair* pair = reinterpret_cast<PathMeasurePair*>(pairHandle);
        bool result = pair->fMeasure.nextContour();
        return result ? JNI_TRUE : JNI_FALSE;
    }

    static void destroy(JNIEnv* env, jobject clazz, jlong pairHandle) {
        PathMeasurePair* pair = reinterpret_cast<PathMeasurePair*>(pairHandle);
        delete pair;
    } 
};

static const JNINativeMethod methods[] = {
    {"native_create",       "(JZ)J",        (void*) SkPathMeasureGlue::create      },
    {"native_setPath",      "(JJZ)V",       (void*) SkPathMeasureGlue::setPath     },
    {"native_getLength",    "(J)F",         (void*) SkPathMeasureGlue::getLength   },
    {"native_getPosTan",    "(JF[F[F)Z",    (void*) SkPathMeasureGlue::getPosTan   },
    {"native_getMatrix",    "(JFJI)Z",      (void*) SkPathMeasureGlue::getMatrix   },
    {"native_getSegment",   "(JFFJZ)Z",     (void*) SkPathMeasureGlue::getSegment  },
    {"native_isClosed",     "(J)Z",         (void*) SkPathMeasureGlue::isClosed    },
    {"native_nextContour",  "(J)Z",         (void*) SkPathMeasureGlue::nextContour },
    {"native_destroy",      "(J)V",         (void*) SkPathMeasureGlue::destroy     }
};

int register_android_graphics_PathMeasure(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/graphics/PathMeasure", methods, NELEM(methods));
}

}
