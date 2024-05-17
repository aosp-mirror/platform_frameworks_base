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

extern int register_android_graphics_Bitmap(JNIEnv*);
extern int register_android_graphics_BitmapFactory(JNIEnv*);
extern int register_android_graphics_ByteBufferStreamAdaptor(JNIEnv* env);
extern int register_android_graphics_Camera(JNIEnv* env);
extern int register_android_graphics_CreateJavaOutputStreamAdaptor(JNIEnv* env);
extern int register_android_graphics_Graphics(JNIEnv* env);
extern int register_android_graphics_ImageDecoder(JNIEnv*);
extern int register_android_graphics_Interpolator(JNIEnv* env);
extern int register_android_graphics_MaskFilter(JNIEnv* env);
extern int register_android_graphics_NinePatch(JNIEnv*);
extern int register_android_graphics_PathEffect(JNIEnv* env);
extern int register_android_graphics_Shader(JNIEnv* env);
extern int register_android_graphics_RenderEffect(JNIEnv* env);
extern int register_android_graphics_Typeface(JNIEnv* env);
extern int register_android_graphics_YuvImage(JNIEnv* env);

namespace android {

extern int register_android_graphics_Canvas(JNIEnv* env);
extern int register_android_graphics_CanvasProperty(JNIEnv* env);
extern int register_android_graphics_Color(JNIEnv* env);
extern int register_android_graphics_ColorFilter(JNIEnv* env);
extern int register_android_graphics_ColorSpace(JNIEnv* env);
extern int register_android_graphics_DrawFilter(JNIEnv* env);
extern int register_android_graphics_FontFamily(JNIEnv* env);
extern int register_android_graphics_Matrix(JNIEnv* env);
extern int register_android_graphics_Paint(JNIEnv* env);
extern int register_android_graphics_Path(JNIEnv* env);
extern int register_android_graphics_PathIterator(JNIEnv* env);
extern int register_android_graphics_PathMeasure(JNIEnv* env);
extern int register_android_graphics_Picture(JNIEnv* env);
extern int register_android_graphics_Region(JNIEnv* env);
extern int register_android_graphics_animation_NativeInterpolatorFactory(JNIEnv* env);
extern int register_android_graphics_animation_RenderNodeAnimator(JNIEnv* env);
extern int register_android_graphics_drawable_AnimatedVectorDrawable(JNIEnv* env);
extern int register_android_graphics_drawable_VectorDrawable(JNIEnv* env);
extern int register_android_graphics_fonts_Font(JNIEnv* env);
extern int register_android_graphics_fonts_FontFamily(JNIEnv* env);
extern int register_android_graphics_text_LineBreaker(JNIEnv* env);
extern int register_android_graphics_text_MeasuredText(JNIEnv* env);
extern int register_android_graphics_text_TextShaper(JNIEnv* env);
extern int register_android_graphics_text_GraphemeBreak(JNIEnv* env);

extern int register_android_util_PathParser(JNIEnv* env);
extern int register_android_view_DisplayListCanvas(JNIEnv* env);
extern int register_android_view_RenderNode(JNIEnv* env);

#define REG_JNI(name)      { name }
struct RegJNIRec {
    int (*mProc)(JNIEnv*);
};

// Map of all possible class names to register to their corresponding JNI registration function pointer
// The actual list of registered classes will be determined at runtime via the 'native_classes' System property
static const std::unordered_map<std::string, RegJNIRec> gRegJNIMap = {
        {"android.graphics.Bitmap", REG_JNI(register_android_graphics_Bitmap)},
        {"android.graphics.BitmapFactory", REG_JNI(register_android_graphics_BitmapFactory)},
        {"android.graphics.ByteBufferStreamAdaptor",
         REG_JNI(register_android_graphics_ByteBufferStreamAdaptor)},
        {"android.graphics.Camera", REG_JNI(register_android_graphics_Camera)},
        {"android.graphics.Canvas", REG_JNI(register_android_graphics_Canvas)},
        {"android.graphics.CanvasProperty", REG_JNI(register_android_graphics_CanvasProperty)},
        {"android.graphics.Color", REG_JNI(register_android_graphics_Color)},
        {"android.graphics.ColorFilter", REG_JNI(register_android_graphics_ColorFilter)},
        {"android.graphics.ColorSpace", REG_JNI(register_android_graphics_ColorSpace)},
        {"android.graphics.CreateJavaOutputStreamAdaptor",
         REG_JNI(register_android_graphics_CreateJavaOutputStreamAdaptor)},
        {"android.graphics.DrawFilter", REG_JNI(register_android_graphics_DrawFilter)},
        {"android.graphics.FontFamily", REG_JNI(register_android_graphics_FontFamily)},
        {"android.graphics.Graphics", REG_JNI(register_android_graphics_Graphics)},
        {"android.graphics.ImageDecoder", REG_JNI(register_android_graphics_ImageDecoder)},
        {"android.graphics.Interpolator", REG_JNI(register_android_graphics_Interpolator)},
        {"android.graphics.MaskFilter", REG_JNI(register_android_graphics_MaskFilter)},
        {"android.graphics.Matrix", REG_JNI(register_android_graphics_Matrix)},
        {"android.graphics.NinePatch", REG_JNI(register_android_graphics_NinePatch)},
        {"android.graphics.Paint", REG_JNI(register_android_graphics_Paint)},
        {"android.graphics.Path", REG_JNI(register_android_graphics_Path)},
        {"android.graphics.PathEffect", REG_JNI(register_android_graphics_PathEffect)},
        {"android.graphics.PathIterator", REG_JNI(register_android_graphics_PathIterator)},
        {"android.graphics.PathMeasure", REG_JNI(register_android_graphics_PathMeasure)},
        {"android.graphics.Picture", REG_JNI(register_android_graphics_Picture)},
        {"android.graphics.RecordingCanvas", REG_JNI(register_android_view_DisplayListCanvas)},
        {"android.graphics.Region", REG_JNI(register_android_graphics_Region)},
        {"android.graphics.RenderNode", REG_JNI(register_android_view_RenderNode)},
        {"android.graphics.Shader", REG_JNI(register_android_graphics_Shader)},
        {"android.graphics.RenderEffect", REG_JNI(register_android_graphics_RenderEffect)},
        {"android.graphics.Typeface", REG_JNI(register_android_graphics_Typeface)},
        {"android.graphics.YuvImage", REG_JNI(register_android_graphics_YuvImage)},
        {"android.graphics.animation.NativeInterpolatorFactory",
         REG_JNI(register_android_graphics_animation_NativeInterpolatorFactory)},
        {"android.graphics.animation.RenderNodeAnimator",
         REG_JNI(register_android_graphics_animation_RenderNodeAnimator)},
        {"android.graphics.drawable.AnimatedVectorDrawable",
         REG_JNI(register_android_graphics_drawable_AnimatedVectorDrawable)},
        {"android.graphics.drawable.VectorDrawable",
         REG_JNI(register_android_graphics_drawable_VectorDrawable)},
        {"android.graphics.fonts.Font", REG_JNI(register_android_graphics_fonts_Font)},
        {"android.graphics.fonts.FontFamily", REG_JNI(register_android_graphics_fonts_FontFamily)},
        {"android.graphics.text.LineBreaker", REG_JNI(register_android_graphics_text_LineBreaker)},
        {"android.graphics.text.MeasuredText",
         REG_JNI(register_android_graphics_text_MeasuredText)},
        {"android.graphics.text.TextRunShaper", REG_JNI(register_android_graphics_text_TextShaper)},
        {"android.graphics.text.GraphemeBreak",
         REG_JNI(register_android_graphics_text_GraphemeBreak)},
        {"android.util.PathParser", REG_JNI(register_android_util_PathParser)},
};

static int register_jni_procs(const std::unordered_map<std::string, RegJNIRec>& jniRegMap,
        const vector<string>& classesToRegister, JNIEnv* env) {

    for (const string& className : classesToRegister) {
        if (jniRegMap.at(className).mProc(env) < 0) {
            return -1;
        }
    }
    return 0;
}

static vector<string> parseCsv(const string& csvString) {
    vector<string>   result;
    istringstream stream(csvString);
    string segment;
    while(getline(stream, segment, ','))
    {
        result.push_back(segment);
    }
    return result;
}

static vector<string> parseCsv(JNIEnv* env, jstring csvJString) {
    const char* charArray = env->GetStringUTFChars(csvJString, 0);
    string csvString(charArray);
    vector<string> result = parseCsv(csvString);
    env->ReleaseStringUTFChars(csvJString, charArray);
    return result;
}

} // namespace android

using namespace android;
using namespace android::uirenderer;

void init_android_graphics() {
    Properties::overrideRenderPipelineType(RenderPipelineType::SkiaCpu);
    SkGraphics::Init();
}

int register_android_graphics_classes(JNIEnv *env) {
    JavaVM* vm = nullptr;
    env->GetJavaVM(&vm);
    GraphicsJNI::setJavaVM(vm);

    // Configuration is stored as java System properties.
    // Get a reference to System.getProperty
    jclass system = FindClassOrDie(env, "java/lang/System");
    jmethodID getPropertyMethod = GetStaticMethodIDOrDie(env, system, "getProperty",
                                                         "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");

    // Get the names of classes that need to register their native methods
    auto nativesClassesJString = (jstring)env->CallStaticObjectMethod(
            system, getPropertyMethod, env->NewStringUTF("graphics_native_classes"),
            env->NewStringUTF(""));
    vector<string> classesToRegister = parseCsv(env, nativesClassesJString);

    if (register_jni_procs(gRegJNIMap, classesToRegister, env) < 0) {
        return JNI_ERR;
    }

    return 0;
}

void zygote_preload_graphics() { }
