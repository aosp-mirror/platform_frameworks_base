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

#include "Picture.h"

#include "SkCanvas.h"
#include "SkStream.h"
#include "SkTemplates.h"
#include "CreateJavaOutputStreamAdaptor.h"

namespace android {

static jlong android_graphics_Picture_newPicture(JNIEnv* env, jobject, jlong srcHandle) {
    const Picture* src = reinterpret_cast<Picture*>(srcHandle);
    return reinterpret_cast<jlong>(new Picture(src));
}

static jlong android_graphics_Picture_deserialize(JNIEnv* env, jobject, jobject jstream,
                                                  jbyteArray jstorage) {
    Picture* picture = NULL;
    SkStream* strm = CreateJavaInputStreamAdaptor(env, jstream, jstorage);
    if (strm) {
        picture = Picture::CreateFromStream(strm);
        delete strm;
    }
    return reinterpret_cast<jlong>(picture);
}

static void android_graphics_Picture_killPicture(JNIEnv* env, jobject, jlong pictureHandle) {
    Picture* picture = reinterpret_cast<Picture*>(pictureHandle);
    SkASSERT(picture);
    delete picture;
}

static void android_graphics_Picture_draw(JNIEnv* env, jobject, jlong canvasHandle,
                                          jlong pictureHandle) {
    Canvas* canvas = reinterpret_cast<Canvas*>(canvasHandle);
    Picture* picture = reinterpret_cast<Picture*>(pictureHandle);
    SkASSERT(canvas);
    SkASSERT(picture);
    picture->draw(canvas);
}

static jboolean android_graphics_Picture_serialize(JNIEnv* env, jobject, jlong pictureHandle,
                                                   jobject jstream, jbyteArray jstorage) {
    Picture* picture = reinterpret_cast<Picture*>(pictureHandle);
    SkWStream* strm = CreateJavaOutputStreamAdaptor(env, jstream, jstorage);

    if (NULL != strm) {
        picture->serialize(strm);
        delete strm;
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static jint android_graphics_Picture_getWidth(JNIEnv* env, jobject, jlong pictureHandle) {
    Picture* pict = reinterpret_cast<Picture*>(pictureHandle);
    return static_cast<jint>(pict->width());
}

static jint android_graphics_Picture_getHeight(JNIEnv* env, jobject, jlong pictureHandle) {
    Picture* pict = reinterpret_cast<Picture*>(pictureHandle);
    return static_cast<jint>(pict->height());
}

static jlong android_graphics_Picture_beginRecording(JNIEnv* env, jobject, jlong pictHandle,
                                                     jint w, jint h) {
    Picture* pict = reinterpret_cast<Picture*>(pictHandle);
    Canvas* canvas = pict->beginRecording(w, h);
    return reinterpret_cast<jlong>(canvas);
}

static void android_graphics_Picture_endRecording(JNIEnv* env, jobject, jlong pictHandle) {
    Picture* pict = reinterpret_cast<Picture*>(pictHandle);
    pict->endRecording();
}

static JNINativeMethod gMethods[] = {
    {"nativeGetWidth", "(J)I", (void*) android_graphics_Picture_getWidth},
    {"nativeGetHeight", "(J)I", (void*) android_graphics_Picture_getHeight},
    {"nativeConstructor", "(J)J", (void*) android_graphics_Picture_newPicture},
    {"nativeCreateFromStream", "(Ljava/io/InputStream;[B)J", (void*)android_graphics_Picture_deserialize},
    {"nativeBeginRecording", "(JII)J", (void*) android_graphics_Picture_beginRecording},
    {"nativeEndRecording", "(J)V", (void*) android_graphics_Picture_endRecording},
    {"nativeDraw", "(JJ)V", (void*) android_graphics_Picture_draw},
    {"nativeWriteToStream", "(JLjava/io/OutputStream;[B)Z", (void*)android_graphics_Picture_serialize},
    {"nativeDestructor","(J)V", (void*) android_graphics_Picture_killPicture}
};

int register_android_graphics_Picture(JNIEnv* env) {
    return AndroidRuntime::registerNativeMethods(env, "android/graphics/Picture", gMethods, NELEM(gMethods));
}

}; // namespace android
