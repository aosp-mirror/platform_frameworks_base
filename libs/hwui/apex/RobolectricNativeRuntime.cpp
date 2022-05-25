/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include <GraphicsJNI.h>
#include <SkGraphics.h>

#include <unordered_map>
#include <vector>

#include "Properties.h"
#include "android/graphics/jni_runtime.h"
#include "graphics_jni_helpers.h"

using namespace std;

int register_android_graphics_Bitmap(JNIEnv*);
int register_android_graphics_BitmapFactory(JNIEnv*);
int register_android_graphics_ByteBufferStreamAdaptor(JNIEnv* env);
int register_android_graphics_CreateJavaOutputStreamAdaptor(JNIEnv* env);
int register_android_graphics_Graphics(JNIEnv* env);
int register_android_graphics_ImageDecoder(JNIEnv*);
int register_android_graphics_MaskFilter(JNIEnv* env);
int register_android_graphics_NinePatch(JNIEnv*);
int register_android_graphics_PathEffect(JNIEnv* env);
int register_android_graphics_Shader(JNIEnv* env);
int register_android_graphics_Typeface(JNIEnv* env);
int register_android_graphics_RenderEffect(JNIEnv* env);

namespace android {

int register_android_graphics_Canvas(JNIEnv* env);
int register_android_graphics_ColorFilter(JNIEnv* env);
int register_android_graphics_ColorSpace(JNIEnv* env);
int register_android_graphics_FontFamily(JNIEnv* env);
int register_android_graphics_Matrix(JNIEnv* env);
int register_android_graphics_Paint(JNIEnv* env);
int register_android_graphics_Path(JNIEnv* env);
int register_android_graphics_PathMeasure(JNIEnv* env);
int register_android_graphics_Region(JNIEnv* env);
int register_android_graphics_drawable_AnimatedVectorDrawable(JNIEnv* env);
int register_android_graphics_drawable_VectorDrawable(JNIEnv* env);
int register_android_graphics_fonts_Font(JNIEnv* env);
int register_android_graphics_fonts_FontFamily(JNIEnv* env);
int register_android_graphics_text_LineBreaker(JNIEnv* env);
int register_android_graphics_text_MeasuredText(JNIEnv* env);
int register_android_util_PathParser(JNIEnv* env);
int register_android_view_DisplayListCanvas(JNIEnv* env);
int register_android_view_RenderNode(JNIEnv* env);

#define REG_JNI(name) \
    { name }
struct RegJNIRec {
    int (*mProc)(JNIEnv*);
};

static const RegJNIRec graphicsJNI[] = {
        REG_JNI(register_android_graphics_Bitmap),
        REG_JNI(register_android_graphics_BitmapFactory),
        REG_JNI(register_android_graphics_ByteBufferStreamAdaptor),
        REG_JNI(register_android_graphics_Canvas),
        REG_JNI(register_android_graphics_ColorFilter),
        REG_JNI(register_android_graphics_ColorSpace),
        REG_JNI(register_android_graphics_CreateJavaOutputStreamAdaptor),
        REG_JNI(register_android_graphics_FontFamily),
        REG_JNI(register_android_graphics_Graphics),
        REG_JNI(register_android_graphics_ImageDecoder),
        REG_JNI(register_android_graphics_MaskFilter),
        REG_JNI(register_android_graphics_Matrix),
        REG_JNI(register_android_graphics_NinePatch),
        REG_JNI(register_android_graphics_Paint),
        REG_JNI(register_android_graphics_Path),
        REG_JNI(register_android_graphics_PathEffect),
        REG_JNI(register_android_graphics_PathMeasure),
        REG_JNI(register_android_graphics_Region),
        REG_JNI(register_android_graphics_drawable_AnimatedVectorDrawable),
        REG_JNI(register_android_graphics_drawable_VectorDrawable),
        REG_JNI(register_android_graphics_RenderEffect),
        REG_JNI(register_android_graphics_Shader),
        REG_JNI(register_android_graphics_Typeface),
        REG_JNI(register_android_graphics_fonts_Font),
        REG_JNI(register_android_graphics_fonts_FontFamily),
        REG_JNI(register_android_graphics_text_LineBreaker),
        REG_JNI(register_android_graphics_text_MeasuredText),
        REG_JNI(register_android_util_PathParser),
        REG_JNI(register_android_view_DisplayListCanvas),
        REG_JNI(register_android_view_RenderNode),
};

static int register_jni_procs(const RegJNIRec array[], size_t count, JNIEnv* env) {
    for (size_t i = 0; i < count; i++) {
        if (array[i].mProc(env) < 0) {
            return -1;
        }
    }
    return 0;
}
}  // namespace android

using namespace android;

void init_android_graphics() {
    SkGraphics::Init();
}

int register_android_graphics_classes(JNIEnv* env) {
    JavaVM* vm = nullptr;
    env->GetJavaVM(&vm);
    GraphicsJNI::setJavaVM(vm);

    if (register_jni_procs(graphicsJNI, NELEM(graphicsJNI), env) < 0) {
        return JNI_ERR;
    }

    return 0;
}

void zygote_preload_graphics() {}
