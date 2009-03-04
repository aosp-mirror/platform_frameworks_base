/*
 * Copyright (C) 2008 The Android Open Source Project
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

#include "jni.h"
#include "GraphicsJNI.h"
#include <android_runtime/AndroidRuntime.h>

#include "SkCanvas.h"
#include "SkPicture.h"
#include "SkTemplates.h"
#include "CreateJavaOutputStreamAdaptor.h"

namespace android {

class SkPictureGlue {
public:
    static SkPicture* newPicture(JNIEnv* env, jobject, const SkPicture* src) {
        if (src) {
            return new SkPicture(*src);
        } else {
            return new SkPicture;
        }
    }
    
    static SkPicture* deserialize(JNIEnv* env, jobject, jobject jstream,
                                  jbyteArray jstorage) {
        SkPicture* picture = NULL;
        SkStream* strm = CreateJavaInputStreamAdaptor(env, jstream, jstorage);
        if (strm) {
            picture = new SkPicture(strm);
            delete strm;
        }
        return picture;
    }
    
    static void killPicture(JNIEnv* env, jobject, SkPicture* picture) {
        SkASSERT(picture);
        delete picture;
    }
    
    static void draw(JNIEnv* env, jobject, SkCanvas* canvas,
                            SkPicture* picture) {
        SkASSERT(canvas);
        SkASSERT(picture);
        picture->draw(canvas);
    }
    
    static bool serialize(JNIEnv* env, jobject, SkPicture* picture,
                          jobject jstream, jbyteArray jstorage) {
        SkWStream* strm = CreateJavaOutputStreamAdaptor(env, jstream, jstorage);
        
        if (NULL != strm) {
            picture->serialize(strm);
            delete strm;
            return true;
        }
        return false;
    }
        
    static int getWidth(JNIEnv* env, jobject jpic) {
        NPE_CHECK_RETURN_ZERO(env, jpic);
        return GraphicsJNI::getNativePicture(env, jpic)->width();
    }
    
    static int getHeight(JNIEnv* env, jobject jpic) {
        NPE_CHECK_RETURN_ZERO(env, jpic);
        return GraphicsJNI::getNativePicture(env, jpic)->height();
    }
    
    static SkCanvas* beginRecording(JNIEnv* env, jobject, SkPicture* pict,
                                    int w, int h) {
        // beginRecording does not ref its return value, it just returns it.
        SkCanvas* canvas = pict->beginRecording(w, h);
        // the java side will wrap this guy in a Canvas.java, which will call
        // unref in its finalizer, so we have to ref it here, so that both that
        // Canvas.java and our picture can both be owners
        canvas->ref();
        return canvas;
    }
    
    static void endRecording(JNIEnv* env, jobject, SkPicture* pict) {
        pict->endRecording();
    }
};

static JNINativeMethod gPictureMethods[] = {
    {"getWidth", "()I", (void*) SkPictureGlue::getWidth},
    {"getHeight", "()I", (void*) SkPictureGlue::getHeight},
    {"nativeConstructor", "(I)I", (void*) SkPictureGlue::newPicture},
    {"nativeCreateFromStream", "(Ljava/io/InputStream;[B)I", (void*)SkPictureGlue::deserialize},
    {"nativeBeginRecording", "(III)I", (void*) SkPictureGlue::beginRecording},
    {"nativeEndRecording", "(I)V", (void*) SkPictureGlue::endRecording},
    {"nativeDraw", "(II)V", (void*) SkPictureGlue::draw},
    {"nativeWriteToStream", "(ILjava/io/OutputStream;[B)Z", (void*)SkPictureGlue::serialize},
    {"nativeDestructor","(I)V", (void*) SkPictureGlue::killPicture}
};

#include <android_runtime/AndroidRuntime.h>
    
#define REG(env, name, array) \
    result = android::AndroidRuntime::registerNativeMethods(env, name, array, \
    SK_ARRAY_COUNT(array));  \
    if (result < 0) return result
    
int register_android_graphics_Picture(JNIEnv* env) {
    int result;
    
    REG(env, "android/graphics/Picture", gPictureMethods);
    
    return result;
}
    
}


