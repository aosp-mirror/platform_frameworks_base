/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "SkRegion.h"
#include "SkPath.h"
#include "GraphicsJNI.h"

#include <binder/Parcel.h>
#include "android_os_Parcel.h"
#include "android_util_Binder.h"

#include <jni.h>
#include <android_runtime/AndroidRuntime.h>

namespace android {

static jfieldID gRegion_nativeInstanceFieldID;

static inline jboolean boolTojboolean(bool value) {
    return value ? JNI_TRUE : JNI_FALSE;
}

static inline SkRegion* GetSkRegion(JNIEnv* env, jobject regionObject) {
    jlong regionHandle = env->GetLongField(regionObject, gRegion_nativeInstanceFieldID);
    SkRegion* region = reinterpret_cast<SkRegion*>(regionHandle);
    SkASSERT(region != NULL);
    return region;
}

static jlong Region_constructor(JNIEnv* env, jobject) {
    return reinterpret_cast<jlong>(new SkRegion);
}

static void Region_destructor(JNIEnv* env, jobject, jlong regionHandle) {
    SkRegion* region = reinterpret_cast<SkRegion*>(regionHandle);
    SkASSERT(region);
    delete region;
}

static void Region_setRegion(JNIEnv* env, jobject, jlong dstHandle, jlong srcHandle) {
    SkRegion* dst = reinterpret_cast<SkRegion*>(dstHandle);
    const SkRegion* src = reinterpret_cast<SkRegion*>(srcHandle);
    SkASSERT(dst && src);
    *dst = *src;
}

static jboolean Region_setRect(JNIEnv* env, jobject, jlong dstHandle, jint left, jint top, jint right, jint bottom) {
    SkRegion* dst = reinterpret_cast<SkRegion*>(dstHandle);
    bool result = dst->setRect(left, top, right, bottom);
    return boolTojboolean(result);
}

static jboolean Region_setPath(JNIEnv* env, jobject, jlong dstHandle,
                               jlong pathHandle, jlong clipHandle) {
    SkRegion*       dst  = reinterpret_cast<SkRegion*>(dstHandle);
    const SkPath*   path = reinterpret_cast<SkPath*>(pathHandle);
    const SkRegion* clip = reinterpret_cast<SkRegion*>(clipHandle);
    SkASSERT(dst && path && clip);
    bool result = dst->setPath(*path, *clip);
    return boolTojboolean(result);

}

static jboolean Region_getBounds(JNIEnv* env, jobject, jlong regionHandle, jobject rectBounds) {
    SkRegion* region = reinterpret_cast<SkRegion*>(regionHandle);
    GraphicsJNI::irect_to_jrect(region->getBounds(), env, rectBounds);
    bool result = !region->isEmpty();
    return boolTojboolean(result);
}

static jboolean Region_getBoundaryPath(JNIEnv* env, jobject, jlong regionHandle, jlong pathHandle) {
    const SkRegion* region = reinterpret_cast<SkRegion*>(regionHandle);
    SkPath*   path = reinterpret_cast<SkPath*>(pathHandle);
    bool result = region->getBoundaryPath(path);
    return boolTojboolean(result);
}

static jboolean Region_op0(JNIEnv* env, jobject, jlong dstHandle, jint left, jint top, jint right, jint bottom, jint op) {
    SkRegion* dst = reinterpret_cast<SkRegion*>(dstHandle);
    SkIRect ir;

    ir.set(left, top, right, bottom);
    bool result = dst->op(ir, (SkRegion::Op)op);
    return boolTojboolean(result);
}

static jboolean Region_op1(JNIEnv* env, jobject, jlong dstHandle, jobject rectObject, jlong regionHandle, jint op) {
    SkRegion* dst = reinterpret_cast<SkRegion*>(dstHandle);
    const SkRegion* region = reinterpret_cast<SkRegion*>(regionHandle);
    SkIRect    ir;
    GraphicsJNI::jrect_to_irect(env, rectObject, &ir);
    bool result = dst->op(ir, *region, (SkRegion::Op)op);
    return boolTojboolean(result);
}

static jboolean Region_op2(JNIEnv* env, jobject, jlong dstHandle, jlong region1Handle, jlong region2Handle, jint op) {
    SkRegion* dst = reinterpret_cast<SkRegion*>(dstHandle);
    const SkRegion* region1 = reinterpret_cast<SkRegion*>(region1Handle);
    const SkRegion* region2 = reinterpret_cast<SkRegion*>(region2Handle);
    bool result = dst->op(*region1, *region2, (SkRegion::Op)op);
    return boolTojboolean(result);
}

////////////////////////////////////  These are methods, not static

static jboolean Region_isEmpty(JNIEnv* env, jobject region) {
    bool result = GetSkRegion(env, region)->isEmpty();
    return boolTojboolean(result);
}

static jboolean Region_isRect(JNIEnv* env, jobject region) {
    bool result = GetSkRegion(env, region)->isRect();
    return boolTojboolean(result);
}

static jboolean Region_isComplex(JNIEnv* env, jobject region) {
    bool result = GetSkRegion(env, region)->isComplex();
    return boolTojboolean(result);
}

static jboolean Region_contains(JNIEnv* env, jobject region, jint x, jint y) {
    bool result = GetSkRegion(env, region)->contains(x, y);
    return boolTojboolean(result);
}

static jboolean Region_quickContains(JNIEnv* env, jobject region, jint left, jint top, jint right, jint bottom) {
    bool result = GetSkRegion(env, region)->quickContains(left, top, right, bottom);
    return boolTojboolean(result);
}

static jboolean Region_quickRejectIIII(JNIEnv* env, jobject region, jint left, jint top, jint right, jint bottom) {
    SkIRect ir;
    ir.set(left, top, right, bottom);
    bool result = GetSkRegion(env, region)->quickReject(ir);
    return boolTojboolean(result);
}

static jboolean Region_quickRejectRgn(JNIEnv* env, jobject region, jobject other) {
    bool result = GetSkRegion(env, region)->quickReject(*GetSkRegion(env, other));
    return boolTojboolean(result);
}

static void Region_translate(JNIEnv* env, jobject region, jint x, jint y, jobject dst) {
    SkRegion* rgn = GetSkRegion(env, region);
    if (dst)
        rgn->translate(x, y, GetSkRegion(env, dst));
    else
        rgn->translate(x, y);
}

// Scale the rectangle by given scale and set the reuslt to the dst.
static void scale_rect(SkIRect* dst, const SkIRect& src, float scale) {
   dst->fLeft = (int)::roundf(src.fLeft * scale);
   dst->fTop = (int)::roundf(src.fTop * scale);
   dst->fRight = (int)::roundf(src.fRight * scale);
   dst->fBottom = (int)::roundf(src.fBottom * scale);
}

// Scale the region by given scale and set the reuslt to the dst.
// dest and src can be the same region instance.
static void scale_rgn(SkRegion* dst, const SkRegion& src, float scale) {
   SkRegion tmp;
   SkRegion::Iterator iter(src);

   for (; !iter.done(); iter.next()) {
       SkIRect r;
       scale_rect(&r, iter.rect(), scale);
       tmp.op(r, SkRegion::kUnion_Op);
   }
   dst->swap(tmp);
}

static void Region_scale(JNIEnv* env, jobject region, jfloat scale, jobject dst) {
    SkRegion* rgn = GetSkRegion(env, region);
    if (dst)
        scale_rgn(GetSkRegion(env, dst), *rgn, scale);
    else
        scale_rgn(rgn, *rgn, scale);
}

static jstring Region_toString(JNIEnv* env, jobject clazz, jlong regionHandle) {
    SkRegion* region = reinterpret_cast<SkRegion*>(regionHandle);
    char* str = region->toString();
    if (str == NULL) {
        return NULL;
    }
    jstring result = env->NewStringUTF(str);
    free(str);
    return result;
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////

static jlong Region_createFromParcel(JNIEnv* env, jobject clazz, jobject parcel)
{
    if (parcel == NULL) {
        return NULL;
    }

    android::Parcel* p = android::parcelForJavaObject(env, parcel);

    const size_t size = p->readInt32();
    const void* regionData = p->readInplace(size);
    if (regionData == NULL) {
        return NULL;
    }
    SkRegion* region = new SkRegion;
    size_t actualSize = region->readFromMemory(regionData, size);

    if (size != actualSize) {
        delete region;
        return NULL;
    }

    return reinterpret_cast<jlong>(region);
}

static jboolean Region_writeToParcel(JNIEnv* env, jobject clazz, jlong regionHandle, jobject parcel)
{
    const SkRegion* region = reinterpret_cast<SkRegion*>(regionHandle);
    if (parcel == NULL) {
        return JNI_FALSE;
    }

    android::Parcel* p = android::parcelForJavaObject(env, parcel);

    size_t size = region->writeToMemory(NULL);
    p->writeInt32(size);
    region->writeToMemory(p->writeInplace(size));

    return JNI_TRUE;
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////

static jboolean Region_equals(JNIEnv* env, jobject clazz, jlong r1Handle, jlong r2Handle)
{
    const SkRegion *r1 = reinterpret_cast<SkRegion*>(r1Handle);
    const SkRegion *r2 = reinterpret_cast<SkRegion*>(r2Handle);
    return boolTojboolean(*r1 == *r2);
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////

struct RgnIterPair {
    SkRegion            fRgn;   // a copy of the caller's region
    SkRegion::Iterator  fIter;  // an iterator acting upon the copy (fRgn)

    RgnIterPair(const SkRegion& rgn) : fRgn(rgn) {
        // have our iterator reference our copy (fRgn), so we know it will be
        // unchanged for the lifetime of the iterator
        fIter.reset(fRgn);
    }
};

static jlong RegionIter_constructor(JNIEnv* env, jobject, jlong regionHandle)
{
    const SkRegion* region = reinterpret_cast<SkRegion*>(regionHandle);
    SkASSERT(region);
    return reinterpret_cast<jlong>(new RgnIterPair(*region));
}

static void RegionIter_destructor(JNIEnv* env, jobject, jlong pairHandle)
{
    RgnIterPair* pair = reinterpret_cast<RgnIterPair*>(pairHandle);
    SkASSERT(pair);
    delete pair;
}

static jboolean RegionIter_next(JNIEnv* env, jobject, jlong pairHandle, jobject rectObject)
{
    RgnIterPair* pair = reinterpret_cast<RgnIterPair*>(pairHandle);
    // the caller has checked that rectObject is not nul
    SkASSERT(pair);
    SkASSERT(rectObject);

    if (!pair->fIter.done()) {
        GraphicsJNI::irect_to_jrect(pair->fIter.rect(), env, rectObject);
        pair->fIter.next();
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////

static JNINativeMethod gRegionIterMethods[] = {
    { "nativeConstructor",  "(J)J",                         (void*)RegionIter_constructor   },
    { "nativeDestructor",   "(J)V",                         (void*)RegionIter_destructor    },
    { "nativeNext",         "(JLandroid/graphics/Rect;)Z",  (void*)RegionIter_next          }
};

static JNINativeMethod gRegionMethods[] = {
    // these are static methods
    { "nativeConstructor",      "()J",                              (void*)Region_constructor       },
    { "nativeDestructor",       "(J)V",                             (void*)Region_destructor        },
    { "nativeSetRegion",        "(JJ)V",                            (void*)Region_setRegion         },
    { "nativeSetRect",          "(JIIII)Z",                         (void*)Region_setRect           },
    { "nativeSetPath",          "(JJJ)Z",                           (void*)Region_setPath           },
    { "nativeGetBounds",        "(JLandroid/graphics/Rect;)Z",      (void*)Region_getBounds         },
    { "nativeGetBoundaryPath",  "(JJ)Z",                            (void*)Region_getBoundaryPath   },
    { "nativeOp",               "(JIIIII)Z",                        (void*)Region_op0               },
    { "nativeOp",               "(JLandroid/graphics/Rect;JI)Z",    (void*)Region_op1               },
    { "nativeOp",               "(JJJI)Z",                          (void*)Region_op2               },
    // these are methods that take the java region object
    { "isEmpty",                "()Z",                              (void*)Region_isEmpty           },
    { "isRect",                 "()Z",                              (void*)Region_isRect            },
    { "isComplex",              "()Z",                              (void*)Region_isComplex         },
    { "contains",               "(II)Z",                            (void*)Region_contains          },
    { "quickContains",          "(IIII)Z",                          (void*)Region_quickContains     },
    { "quickReject",            "(IIII)Z",                          (void*)Region_quickRejectIIII   },
    { "quickReject",            "(Landroid/graphics/Region;)Z",     (void*)Region_quickRejectRgn    },
    { "scale",                  "(FLandroid/graphics/Region;)V",    (void*)Region_scale             },
    { "translate",              "(IILandroid/graphics/Region;)V",   (void*)Region_translate         },
    { "nativeToString",         "(J)Ljava/lang/String;",            (void*)Region_toString          },
    // parceling methods
    { "nativeCreateFromParcel", "(Landroid/os/Parcel;)J",           (void*)Region_createFromParcel  },
    { "nativeWriteToParcel",    "(JLandroid/os/Parcel;)Z",          (void*)Region_writeToParcel     },
    { "nativeEquals",           "(JJ)Z",                            (void*)Region_equals            },
};

int register_android_graphics_Region(JNIEnv* env)
{
    jclass clazz = env->FindClass("android/graphics/Region");
    SkASSERT(clazz);

    gRegion_nativeInstanceFieldID = env->GetFieldID(clazz, "mNativeRegion", "J");
    SkASSERT(gRegion_nativeInstanceFieldID);

    int result = android::AndroidRuntime::registerNativeMethods(env, "android/graphics/Region",
                                                             gRegionMethods, SK_ARRAY_COUNT(gRegionMethods));
    if (result < 0)
        return result;

    return android::AndroidRuntime::registerNativeMethods(env, "android/graphics/RegionIterator",
                                                       gRegionIterMethods, SK_ARRAY_COUNT(gRegionIterMethods));
}

SkRegion* android_graphics_Region_getSkRegion(JNIEnv* env, jobject regionObj) {
    return GetSkRegion(env, regionObj);
}

} // namespace android
