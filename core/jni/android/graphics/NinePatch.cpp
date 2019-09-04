/*
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

#define LOG_TAG "9patch"
#define LOG_NDEBUG 1

#include <androidfw/ResourceTypes.h>
#include <hwui/Canvas.h>
#include <hwui/Paint.h>
#include <utils/Log.h>

#include "SkCanvas.h"
#include "SkLatticeIter.h"
#include "SkRegion.h"
#include "GraphicsJNI.h"
#include "NinePatchPeeker.h"
#include "NinePatchUtils.h"

#include <nativehelper/JNIHelp.h>
#include "core_jni_helpers.h"

jclass      gInsetStruct_class;
jmethodID   gInsetStruct_constructorMethodID;

using namespace android;

/**
 * IMPORTANT NOTE: 9patch chunks can be manipuated either as an array of bytes
 * or as a Res_png_9patch instance. It is important to note that the size of the
 * array required to hold a 9patch chunk is greater than sizeof(Res_png_9patch).
 * The code below manipulates chunks as Res_png_9patch* types to draw and as
 * int8_t* to allocate and free the backing storage.
 */

class SkNinePatchGlue {
public:
    static jboolean isNinePatchChunk(JNIEnv* env, jobject, jbyteArray obj) {
        if (NULL == obj) {
            return JNI_FALSE;
        }
        if (env->GetArrayLength(obj) < (int)sizeof(Res_png_9patch)) {
            return JNI_FALSE;
        }
        const jbyte* array = env->GetByteArrayElements(obj, 0);
        if (array != NULL) {
            const Res_png_9patch* chunk = reinterpret_cast<const Res_png_9patch*>(array);
            int8_t wasDeserialized = chunk->wasDeserialized;
            env->ReleaseByteArrayElements(obj, const_cast<jbyte*>(array), JNI_ABORT);
            return (wasDeserialized != -1) ? JNI_TRUE : JNI_FALSE;
        }
        return JNI_FALSE;
    }

    static jlong validateNinePatchChunk(JNIEnv* env, jobject, jbyteArray obj) {
        size_t chunkSize = env->GetArrayLength(obj);
        if (chunkSize < (int) (sizeof(Res_png_9patch))) {
            jniThrowRuntimeException(env, "Array too small for chunk.");
            return NULL;
        }

        int8_t* storage = new int8_t[chunkSize];
        // This call copies the content of the jbyteArray
        env->GetByteArrayRegion(obj, 0, chunkSize, reinterpret_cast<jbyte*>(storage));
        // Deserialize in place, return the array we just allocated
        return reinterpret_cast<jlong>(Res_png_9patch::deserialize(storage));
    }

    static void finalize(JNIEnv* env, jobject, jlong patchHandle) {
        int8_t* patch = reinterpret_cast<int8_t*>(patchHandle);
        delete[] patch;
    }

    static jlong getTransparentRegion(JNIEnv* env, jobject, jlong bitmapPtr,
            jlong chunkHandle, jobject dstRect) {
        Res_png_9patch* chunk = reinterpret_cast<Res_png_9patch*>(chunkHandle);
        SkASSERT(chunk);

        SkBitmap bitmap;
        bitmap::toBitmap(bitmapPtr).getSkBitmap(&bitmap);
        SkRect dst;
        GraphicsJNI::jrect_to_rect(env, dstRect, &dst);

        SkCanvas::Lattice lattice;
        SkIRect src = SkIRect::MakeWH(bitmap.width(), bitmap.height());
        lattice.fBounds = &src;
        NinePatchUtils::SetLatticeDivs(&lattice, *chunk, bitmap.width(), bitmap.height());
        lattice.fRectTypes = nullptr;
        lattice.fColors = nullptr;

        SkRegion* region = nullptr;
        if (SkLatticeIter::Valid(bitmap.width(), bitmap.height(), lattice)) {
            SkLatticeIter iter(lattice, dst);
            if (iter.numRectsToDraw() == chunk->numColors) {
                SkRect dummy;
                SkRect iterDst;
                int index = 0;
                while (iter.next(&dummy, &iterDst)) {
                    if (0 == chunk->getColors()[index++] && !iterDst.isEmpty()) {
                        if (!region) {
                            region = new SkRegion();
                        }

                        region->op(iterDst.round(), SkRegion::kUnion_Op);
                    }
                }
            }
        }

        return reinterpret_cast<jlong>(region);
    }

};

jobject NinePatchPeeker::createNinePatchInsets(JNIEnv* env, float scale) const {
    if (!mHasInsets) {
        return nullptr;
    }

    return env->NewObject(gInsetStruct_class, gInsetStruct_constructorMethodID,
            mOpticalInsets[0], mOpticalInsets[1],
            mOpticalInsets[2], mOpticalInsets[3],
            mOutlineInsets[0], mOutlineInsets[1],
            mOutlineInsets[2], mOutlineInsets[3],
            mOutlineRadius, mOutlineAlpha, scale);
}

void NinePatchPeeker::getPadding(JNIEnv* env, jobject outPadding) const {
    if (mPatch) {
        GraphicsJNI::set_jrect(env, outPadding,
                mPatch->paddingLeft, mPatch->paddingTop,
                mPatch->paddingRight, mPatch->paddingBottom);

    } else {
        GraphicsJNI::set_jrect(env, outPadding, -1, -1, -1, -1);
    }
}

/////////////////////////////////////////////////////////////////////////////////////////

static const JNINativeMethod gNinePatchMethods[] = {
    { "isNinePatchChunk", "([B)Z", (void*) SkNinePatchGlue::isNinePatchChunk },
    { "validateNinePatchChunk", "([B)J",
            (void*) SkNinePatchGlue::validateNinePatchChunk },
    { "nativeFinalize", "(J)V", (void*) SkNinePatchGlue::finalize },
    { "nativeGetTransparentRegion", "(JJLandroid/graphics/Rect;)J",
            (void*) SkNinePatchGlue::getTransparentRegion }
};

int register_android_graphics_NinePatch(JNIEnv* env) {
    gInsetStruct_class = MakeGlobalRefOrDie(env, FindClassOrDie(env,
            "android/graphics/NinePatch$InsetStruct"));
    gInsetStruct_constructorMethodID = GetMethodIDOrDie(env, gInsetStruct_class, "<init>",
            "(IIIIIIIIFIF)V");
    return android::RegisterMethodsOrDie(env,
            "android/graphics/NinePatch", gNinePatchMethods, NELEM(gNinePatchMethods));
}
