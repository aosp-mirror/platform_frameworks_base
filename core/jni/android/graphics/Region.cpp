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

static inline SkRegion* GetSkRegion(JNIEnv* env, jobject regionObject) {
    SkRegion* rgn = (SkRegion*)env->GetIntField(regionObject, gRegion_nativeInstanceFieldID);
    SkASSERT(rgn != NULL);
    return rgn;
}

static SkRegion* Region_constructor(JNIEnv* env, jobject) {
    return new SkRegion;
}

static void Region_destructor(JNIEnv* env, jobject, SkRegion* region) {
    SkASSERT(region);
    delete region;
}

static void Region_setRegion(JNIEnv* env, jobject, SkRegion* dst, const SkRegion* src) {
    SkASSERT(dst && src);
    *dst = *src;
}

static jboolean Region_setRect(JNIEnv* env, jobject, SkRegion* dst, int left, int top, int right, int bottom) {
    return dst->setRect(left, top, right, bottom);
}

static jboolean Region_setPath(JNIEnv* env, jobject, SkRegion* dst,
                               const SkPath* path, const SkRegion* clip) {
    SkASSERT(dst && path && clip);
    return dst->setPath(*path, *clip);
}

static jboolean Region_getBounds(JNIEnv* env, jobject, SkRegion* region, jobject rectBounds) {
    GraphicsJNI::irect_to_jrect(region->getBounds(), env, rectBounds);
    return !region->isEmpty();
}

static jboolean Region_getBoundaryPath(JNIEnv* env, jobject, const SkRegion* region, SkPath* path) {
    return region->getBoundaryPath(path);
}

static jboolean Region_op0(JNIEnv* env, jobject, SkRegion* dst, int left, int top, int right, int bottom, int op) {
    SkIRect ir;

    ir.set(left, top, right, bottom);
    return dst->op(ir, (SkRegion::Op)op);
}

static jboolean Region_op1(JNIEnv* env, jobject, SkRegion* dst, jobject rectObject, const SkRegion* region, int op) {
    SkIRect    ir;
    GraphicsJNI::jrect_to_irect(env, rectObject, &ir);
    return dst->op(ir, *region, (SkRegion::Op)op);
}

static jboolean Region_op2(JNIEnv* env, jobject, SkRegion* dst, const SkRegion* region1, const SkRegion* region2, int op) {
    return dst->op(*region1, *region2, (SkRegion::Op)op);
}

////////////////////////////////////  These are methods, not static

static jboolean Region_isEmpty(JNIEnv* env, jobject region) {
    return GetSkRegion(env, region)->isEmpty();
}

static jboolean Region_isRect(JNIEnv* env, jobject region) {
    return GetSkRegion(env, region)->isRect();
}

static jboolean Region_isComplex(JNIEnv* env, jobject region) {
    return GetSkRegion(env, region)->isComplex();
}

static jboolean Region_contains(JNIEnv* env, jobject region, int x, int y) {
    return GetSkRegion(env, region)->contains(x, y);
}

static jboolean Region_quickContains(JNIEnv* env, jobject region, int left, int top, int right, int bottom) {
    return GetSkRegion(env, region)->quickContains(left, top, right, bottom);
}

static jboolean Region_quickRejectIIII(JNIEnv* env, jobject region, int left, int top, int right, int bottom) {
    SkIRect ir;
    ir.set(left, top, right, bottom);
    return GetSkRegion(env, region)->quickReject(ir);
}

static jboolean Region_quickRejectRgn(JNIEnv* env, jobject region, jobject other) {
    return GetSkRegion(env, region)->quickReject(*GetSkRegion(env, other));
}

static void Region_translate(JNIEnv* env, jobject region, int x, int y, jobject dst) {
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

static jstring Region_toString(JNIEnv* env, jobject clazz, SkRegion* region) {
    char* str = region->toString();
    if (str == NULL) {
        return NULL;
    }
    jstring result = env->NewStringUTF(str);
    free(str);
    return result;
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////

static SkRegion* Region_createFromParcel(JNIEnv* env, jobject clazz, jobject parcel)
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
    region->readFromMemory(regionData, size);

    return region;
}

static jboolean Region_writeToParcel(JNIEnv* env, jobject clazz, const SkRegion* region, jobject parcel)
{
    if (parcel == NULL) {
        return false;
    }

    android::Parcel* p = android::parcelForJavaObject(env, parcel);

    size_t size = region->writeToMemory(NULL);
    p->writeInt32(size);
    region->writeToMemory(p->writeInplace(size));

    return true;
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////

static jboolean Region_equals(JNIEnv* env, jobject clazz, const SkRegion *r1, const SkRegion* r2)
{
  return (jboolean) (*r1 == *r2);
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

static RgnIterPair* RegionIter_constructor(JNIEnv* env, jobject, const SkRegion* region)
{
    SkASSERT(region);
    return new RgnIterPair(*region);
}

static void RegionIter_destructor(JNIEnv* env, jobject, RgnIterPair* pair)
{
    SkASSERT(pair);
    delete pair;
}

static jboolean RegionIter_next(JNIEnv* env, jobject, RgnIterPair* pair, jobject rectObject)
{
    // the caller has checked that rectObject is not nul
    SkASSERT(pair);
    SkASSERT(rectObject);

    if (!pair->fIter.done()) {
        GraphicsJNI::irect_to_jrect(pair->fIter.rect(), env, rectObject);
        pair->fIter.next();
        return true;
    }
    return false;
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////

static JNINativeMethod gRegionIterMethods[] = {
    { "nativeConstructor",  "(I)I",                         (void*)RegionIter_constructor   },
    { "nativeDestructor",   "(I)V",                         (void*)RegionIter_destructor    },
    { "nativeNext",         "(ILandroid/graphics/Rect;)Z",  (void*)RegionIter_next          }
};

static JNINativeMethod gRegionMethods[] = {
    // these are static methods
    { "nativeConstructor",      "()I",                              (void*)Region_constructor       },
    { "nativeDestructor",       "(I)V",                             (void*)Region_destructor        },
    { "nativeSetRegion",        "(II)V",                            (void*)Region_setRegion         },
    { "nativeSetRect",          "(IIIII)Z",                         (void*)Region_setRect           },
    { "nativeSetPath",          "(III)Z",                           (void*)Region_setPath           },
    { "nativeGetBounds",        "(ILandroid/graphics/Rect;)Z",      (void*)Region_getBounds         },
    { "nativeGetBoundaryPath",  "(II)Z",                            (void*)Region_getBoundaryPath   },
    { "nativeOp",               "(IIIIII)Z",                        (void*)Region_op0               },
    { "nativeOp",               "(ILandroid/graphics/Rect;II)Z",    (void*)Region_op1               },
    { "nativeOp",               "(IIII)Z",                          (void*)Region_op2               },
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
    { "nativeToString",         "(I)Ljava/lang/String;",            (void*)Region_toString          },
    // parceling methods
    { "nativeCreateFromParcel", "(Landroid/os/Parcel;)I",           (void*)Region_createFromParcel  },
    { "nativeWriteToParcel",    "(ILandroid/os/Parcel;)Z",          (void*)Region_writeToParcel     },
    { "nativeEquals",           "(II)Z",                            (void*)Region_equals            },
};

int register_android_graphics_Region(JNIEnv* env)
{
    jclass clazz = env->FindClass("android/graphics/Region");
    SkASSERT(clazz);

    gRegion_nativeInstanceFieldID = env->GetFieldID(clazz, "mNativeRegion", "I");
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
