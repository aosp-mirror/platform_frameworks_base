/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include "android/graphics/jni_runtime.h"

#include <android/log.h>
#include <nativehelper/JNIHelp.h>
#include <sys/cdefs.h>

#include <EGL/egl.h>
#include <GraphicsJNI.h>
#include <Properties.h>
#include <SkGraphics.h>

#undef LOG_TAG
#define LOG_TAG "AndroidGraphicsJNI"

extern int register_android_graphics_Bitmap(JNIEnv*);
extern int register_android_graphics_BitmapFactory(JNIEnv*);
extern int register_android_graphics_BitmapRegionDecoder(JNIEnv*);
extern int register_android_graphics_ByteBufferStreamAdaptor(JNIEnv* env);
extern int register_android_graphics_Camera(JNIEnv* env);
extern int register_android_graphics_CreateJavaOutputStreamAdaptor(JNIEnv* env);
extern int register_android_graphics_Graphics(JNIEnv* env);
extern int register_android_graphics_ImageDecoder(JNIEnv*);
extern int register_android_graphics_drawable_AnimatedImageDrawable(JNIEnv*);
extern int register_android_graphics_Interpolator(JNIEnv* env);
extern int register_android_graphics_MaskFilter(JNIEnv* env);
extern int register_android_graphics_Movie(JNIEnv* env);
extern int register_android_graphics_NinePatch(JNIEnv*);
extern int register_android_graphics_PathEffect(JNIEnv* env);
extern int register_android_graphics_Shader(JNIEnv* env);
extern int register_android_graphics_Typeface(JNIEnv* env);
extern int register_android_graphics_YuvImage(JNIEnv* env);

namespace android {

extern int register_android_graphics_Canvas(JNIEnv* env);
extern int register_android_graphics_CanvasProperty(JNIEnv* env);
extern int register_android_graphics_ColorFilter(JNIEnv* env);
extern int register_android_graphics_ColorSpace(JNIEnv* env);
extern int register_android_graphics_DrawFilter(JNIEnv* env);
extern int register_android_graphics_FontFamily(JNIEnv* env);
extern int register_android_graphics_HardwareRendererObserver(JNIEnv* env);
extern int register_android_graphics_Matrix(JNIEnv* env);
extern int register_android_graphics_Paint(JNIEnv* env);
extern int register_android_graphics_Path(JNIEnv* env);
extern int register_android_graphics_PathMeasure(JNIEnv* env);
extern int register_android_graphics_Picture(JNIEnv*);
extern int register_android_graphics_Region(JNIEnv* env);
extern int register_android_graphics_animation_NativeInterpolatorFactory(JNIEnv* env);
extern int register_android_graphics_animation_RenderNodeAnimator(JNIEnv* env);
extern int register_android_graphics_drawable_AnimatedVectorDrawable(JNIEnv* env);
extern int register_android_graphics_drawable_VectorDrawable(JNIEnv* env);
extern int register_android_graphics_fonts_Font(JNIEnv* env);
extern int register_android_graphics_fonts_FontFamily(JNIEnv* env);
extern int register_android_graphics_pdf_PdfDocument(JNIEnv* env);
extern int register_android_graphics_pdf_PdfEditor(JNIEnv* env);
extern int register_android_graphics_pdf_PdfRenderer(JNIEnv* env);
extern int register_android_graphics_text_MeasuredText(JNIEnv* env);
extern int register_android_graphics_text_LineBreaker(JNIEnv *env);

extern int register_android_util_PathParser(JNIEnv* env);
extern int register_android_view_DisplayListCanvas(JNIEnv* env);
extern int register_android_view_RenderNode(JNIEnv* env);
extern int register_android_view_TextureLayer(JNIEnv* env);
extern int register_android_view_ThreadedRenderer(JNIEnv* env);

#ifdef NDEBUG
    #define REG_JNI(name)      { name }
    struct RegJNIRec {
        int (*mProc)(JNIEnv*);
    };
#else
    #define REG_JNI(name)      { name, #name }
    struct RegJNIRec {
        int (*mProc)(JNIEnv*);
        const char* mName;
    };
#endif

static const RegJNIRec gRegJNI[] = {
    REG_JNI(register_android_graphics_Canvas),
    // This needs to be before register_android_graphics_Graphics, or the latter
    // will not be able to find the jmethodID for ColorSpace.get().
    REG_JNI(register_android_graphics_ColorSpace),
    REG_JNI(register_android_graphics_Graphics),
    REG_JNI(register_android_graphics_Bitmap),
    REG_JNI(register_android_graphics_BitmapFactory),
    REG_JNI(register_android_graphics_BitmapRegionDecoder),
    REG_JNI(register_android_graphics_ByteBufferStreamAdaptor),
    REG_JNI(register_android_graphics_Camera),
    REG_JNI(register_android_graphics_CreateJavaOutputStreamAdaptor),
    REG_JNI(register_android_graphics_CanvasProperty),
    REG_JNI(register_android_graphics_ColorFilter),
    REG_JNI(register_android_graphics_DrawFilter),
    REG_JNI(register_android_graphics_FontFamily),
    REG_JNI(register_android_graphics_HardwareRendererObserver),
    REG_JNI(register_android_graphics_ImageDecoder),
    REG_JNI(register_android_graphics_drawable_AnimatedImageDrawable),
    REG_JNI(register_android_graphics_Interpolator),
    REG_JNI(register_android_graphics_MaskFilter),
    REG_JNI(register_android_graphics_Matrix),
    REG_JNI(register_android_graphics_Movie),
    REG_JNI(register_android_graphics_NinePatch),
    REG_JNI(register_android_graphics_Paint),
    REG_JNI(register_android_graphics_Path),
    REG_JNI(register_android_graphics_PathMeasure),
    REG_JNI(register_android_graphics_PathEffect),
    REG_JNI(register_android_graphics_Picture),
    REG_JNI(register_android_graphics_Region),
    REG_JNI(register_android_graphics_Shader),
    REG_JNI(register_android_graphics_Typeface),
    REG_JNI(register_android_graphics_YuvImage),
    REG_JNI(register_android_graphics_animation_NativeInterpolatorFactory),
    REG_JNI(register_android_graphics_animation_RenderNodeAnimator),
    REG_JNI(register_android_graphics_drawable_AnimatedVectorDrawable),
    REG_JNI(register_android_graphics_drawable_VectorDrawable),
    REG_JNI(register_android_graphics_fonts_Font),
    REG_JNI(register_android_graphics_fonts_FontFamily),
    REG_JNI(register_android_graphics_pdf_PdfDocument),
    REG_JNI(register_android_graphics_pdf_PdfEditor),
    REG_JNI(register_android_graphics_pdf_PdfRenderer),
    REG_JNI(register_android_graphics_text_MeasuredText),
    REG_JNI(register_android_graphics_text_LineBreaker),

    REG_JNI(register_android_util_PathParser),
    REG_JNI(register_android_view_RenderNode),
    REG_JNI(register_android_view_DisplayListCanvas),
    REG_JNI(register_android_view_TextureLayer),
    REG_JNI(register_android_view_ThreadedRenderer),
};

} // namespace android

void init_android_graphics() {
    SkGraphics::Init();
}

int register_android_graphics_classes(JNIEnv *env) {
    JavaVM* vm = nullptr;
    env->GetJavaVM(&vm);
    GraphicsJNI::setJavaVM(vm);

    for (size_t i = 0; i < NELEM(android::gRegJNI); i++) {
        if (android::gRegJNI[i].mProc(env) < 0) {
#ifndef NDEBUG
            __android_log_print(ANDROID_LOG_FATAL, LOG_TAG, "JNI Error!!! %s failed to load\n",
                                android::gRegJNI[i].mName);
#endif
            return -1;
        }
    }
    return 0;
}

using android::uirenderer::Properties;
using android::uirenderer::RenderPipelineType;

void zygote_preload_graphics() {
    if (Properties::peekRenderPipelineType() == RenderPipelineType::SkiaGL) {
        eglGetDisplay(EGL_DEFAULT_DISPLAY);
    }
}