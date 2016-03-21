/* libs/android_runtime/android/graphics/Rasterizer.cpp
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

// This file was generated from the C++ include file: SkRasterizer.h
// Any changes made to this file will be discarded by the build.
// To change this file, either edit the include, or device/tools/gluemaker/main.cpp, 
// or one of the auxilary file specifications in device/tools/gluemaker.

#include "jni.h"
#include "GraphicsJNI.h"
#include "SkLayerRasterizer.h"
#include "core_jni_helpers.h"

#include <hwui/Paint.h>

// Rasterizer.java holds a pointer (jlong) to this guy
class NativeRasterizer {
public:
    NativeRasterizer() {}
    virtual ~NativeRasterizer() {}

    // Can return NULL, or a ref to the skia rasterizer.
    virtual SkRasterizer* refRasterizer() { return NULL; }
};

class NativeLayerRasterizer : public NativeRasterizer {
public:
    SkLayerRasterizer::Builder fBuilder;

    virtual SkRasterizer* refRasterizer() {
        return fBuilder.snapshotRasterizer();
    }
};

SkRasterizer* GraphicsJNI::refNativeRasterizer(jlong rasterizerHandle) {
    NativeRasterizer* nr = reinterpret_cast<NativeRasterizer*>(rasterizerHandle);
    return nr ? nr->refRasterizer() : NULL;
}

///////////////////////////////////////////////////////////////////////////////

namespace android {

class SkRasterizerGlue {
public:
    static void finalizer(JNIEnv* env, jobject clazz, jlong objHandle) {
        delete reinterpret_cast<NativeRasterizer *>(objHandle);
    }
};

static const JNINativeMethod gRasterizerMethods[] = {
    {"finalizer", "(J)V", (void*) SkRasterizerGlue::finalizer}
};

int register_android_graphics_Rasterizer(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/graphics/Rasterizer", gRasterizerMethods,
                                NELEM(gRasterizerMethods));
}

class SkLayerRasterizerGlue {
public:
    static jlong create(JNIEnv* env, jobject) {
        return reinterpret_cast<jlong>(new NativeLayerRasterizer);
    }

    static void addLayer(JNIEnv* env, jobject, jlong layerHandle, jlong paintHandle, jfloat dx, jfloat dy) {
        NativeLayerRasterizer* nr = reinterpret_cast<NativeLayerRasterizer *>(layerHandle);
        const Paint* paint = reinterpret_cast<Paint *>(paintHandle);
        SkASSERT(nr);
        SkASSERT(paint);
        nr->fBuilder.addLayer(*paint, dx, dy);
    }
};

static const JNINativeMethod gLayerRasterizerMethods[] = {
    { "nativeConstructor",  "()J",      (void*)SkLayerRasterizerGlue::create    },
    { "nativeAddLayer",     "(JJFF)V",  (void*)SkLayerRasterizerGlue::addLayer  }
};

int register_android_graphics_LayerRasterizer(JNIEnv* env)
{
    return RegisterMethodsOrDie(env, "android/graphics/LayerRasterizer",
                                gLayerRasterizerMethods, NELEM(gLayerRasterizerMethods));
}

}

