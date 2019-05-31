/*
 * Copyright (C) 2018 The Android Open Source Project
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
#include "core_jni_helpers.h"

#include <unicode/putil.h>
#include <vector>

using namespace std;

/*
 * This is responsible for setting up the JNI environment for communication between
 * the Java and native parts of layoutlib, including registering native methods.
 * This is mostly achieved by copying the way it is done in the platform
 * (see AndroidRuntime.cpp).
 */

static JavaVM* javaVM;

extern int register_android_graphics_Bitmap(JNIEnv*);
extern int register_android_graphics_BitmapFactory(JNIEnv*);
extern int register_android_graphics_ByteBufferStreamAdaptor(JNIEnv* env);
extern int register_android_graphics_CreateJavaOutputStreamAdaptor(JNIEnv* env);
extern int register_android_graphics_Graphics(JNIEnv* env);
extern int register_android_graphics_ImageDecoder(JNIEnv*);
extern int register_android_graphics_MaskFilter(JNIEnv* env);
extern int register_android_graphics_NinePatch(JNIEnv*);
extern int register_android_graphics_PathEffect(JNIEnv* env);
extern int register_android_graphics_Shader(JNIEnv* env);
extern int register_android_graphics_Typeface(JNIEnv* env);

namespace android {

extern int register_android_animation_PropertyValuesHolder(JNIEnv *env);
extern int register_android_graphics_Canvas(JNIEnv* env);
extern int register_android_graphics_ColorFilter(JNIEnv* env);
extern int register_android_graphics_ColorSpace(JNIEnv* env);
extern int register_android_graphics_DrawFilter(JNIEnv* env);
extern int register_android_graphics_FontFamily(JNIEnv* env);
extern int register_android_graphics_Matrix(JNIEnv* env);
extern int register_android_graphics_Paint(JNIEnv* env);
extern int register_android_graphics_Path(JNIEnv* env);
extern int register_android_graphics_PathMeasure(JNIEnv* env);
extern int register_android_graphics_Picture(JNIEnv* env);
extern int register_android_graphics_Region(JNIEnv* env);
extern int register_android_graphics_drawable_AnimatedVectorDrawable(JNIEnv* env);
extern int register_android_graphics_drawable_VectorDrawable(JNIEnv* env);
extern int register_android_graphics_fonts_Font(JNIEnv* env);
extern int register_android_graphics_fonts_FontFamily(JNIEnv* env);
extern int register_android_graphics_text_LineBreaker(JNIEnv* env);
extern int register_android_graphics_text_MeasuredText(JNIEnv* env);
extern int register_android_util_PathParser(JNIEnv* env);
extern int register_com_android_internal_util_VirtualRefBasePtr(JNIEnv *env);
extern int register_com_android_internal_view_animation_NativeInterpolatorFactoryHelper(JNIEnv *env);

#define REG_JNI(name)      { name }
struct RegJNIRec {
    int (*mProc)(JNIEnv*);
};

static const RegJNIRec gRegJNI[] = {
    REG_JNI(register_android_animation_PropertyValuesHolder),
    REG_JNI(register_android_graphics_Bitmap),
    REG_JNI(register_android_graphics_BitmapFactory),
    REG_JNI(register_android_graphics_ByteBufferStreamAdaptor),
    REG_JNI(register_android_graphics_Canvas),
    REG_JNI(register_android_graphics_ColorFilter),
    REG_JNI(register_android_graphics_ColorSpace),
    REG_JNI(register_android_graphics_CreateJavaOutputStreamAdaptor),
    REG_JNI(register_android_graphics_DrawFilter),
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
    REG_JNI(register_android_graphics_Picture),
    REG_JNI(register_android_graphics_Region),
    REG_JNI(register_android_graphics_Shader),
    REG_JNI(register_android_graphics_Typeface),
    REG_JNI(register_android_graphics_drawable_AnimatedVectorDrawable),
    REG_JNI(register_android_graphics_drawable_VectorDrawable),
    REG_JNI(register_android_graphics_fonts_Font),
    REG_JNI(register_android_graphics_fonts_FontFamily),
    REG_JNI(register_android_graphics_text_LineBreaker),
    REG_JNI(register_android_graphics_text_MeasuredText),
    REG_JNI(register_android_util_PathParser),
    REG_JNI(register_com_android_internal_util_VirtualRefBasePtr),
    REG_JNI(register_com_android_internal_view_animation_NativeInterpolatorFactoryHelper),
};

// Vector to store the names of classes that need delegates of their native methods
static vector<string> classesToDelegate;

static int register_jni_procs(const RegJNIRec array[], size_t count, JNIEnv* env) {
    for (size_t i = 0; i < count; i++) {
        if (array[i].mProc(env) < 0) {
            return -1;
        }
    }
    return 0;
}

int AndroidRuntime::registerNativeMethods(JNIEnv* env,
        const char* className, const JNINativeMethod* gMethods, int numMethods) {

    string classNameString = string(className);
    if (find(classesToDelegate.begin(), classesToDelegate.end(), classNameString)
            != classesToDelegate.end()) {
        // Register native methods to the delegate class <classNameString>_NativeDelegate
        // by adding _Original to the name of each method.
        replace(classNameString.begin(), classNameString.end(), '$', '_');
        string delegateClassName = classNameString + "_NativeDelegate";
        jclass clazz = env->FindClass(delegateClassName.c_str());
        JNINativeMethod gTypefaceDelegateMethods[numMethods];
        for (int i = 0; i < numMethods; i++) {
            JNINativeMethod gTypefaceMethod = gMethods[i];
            string newName = string(gTypefaceMethod.name) + "_Original";
            gTypefaceDelegateMethods[i].name = strdup(newName.c_str());
            gTypefaceDelegateMethods[i].signature = gTypefaceMethod.signature;
            gTypefaceDelegateMethods[i].fnPtr = gTypefaceMethod.fnPtr;
        }
        int result = env->RegisterNatives(clazz, gTypefaceDelegateMethods, numMethods);
        for (int i = 0; i < numMethods; i++) {
            free((char*)gTypefaceDelegateMethods[i].name);
        }
        return result;
    }

    jclass clazz = env->FindClass(className);

    return env->RegisterNatives(clazz, gMethods, numMethods);
}

JNIEnv* AndroidRuntime::getJNIEnv()
{
    JNIEnv* env;
    if (javaVM->GetEnv((void**) &env, JNI_VERSION_1_6) != JNI_OK)
        return nullptr;
    return env;
}

JavaVM* AndroidRuntime::getJavaVM() {
    return javaVM;
}

} // namespace android

using namespace android;

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    javaVM = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    // Get the names of classes that have to delegate their native methods
    jclass createInfo = FindClassOrDie(env, "com/android/tools/layoutlib/create/CreateInfo");
    jfieldID arrayId = GetStaticFieldIDOrDie(env, createInfo,
            "DELEGATE_CLASS_NATIVES_TO_NATIVES", "[Ljava/lang/String;");
    jobjectArray array = (jobjectArray) env->GetStaticObjectField(createInfo, arrayId);
    jsize size = env->GetArrayLength(array);

    for (int i=0; i < size; ++i) {
        jstring string = (jstring) env->GetObjectArrayElement(array, i);
        const char* charArray = env->GetStringUTFChars(string, 0);
        std::string className = std::string(charArray);
        std::replace(className.begin(), className.end(), '.', '/');
        classesToDelegate.push_back(className);
        env->ReleaseStringUTFChars(string, charArray);
    }

    if (register_jni_procs(gRegJNI, NELEM(gRegJNI), env) < 0) {
        return JNI_ERR;
    }

    // Set the location of ICU data
    jclass bridge = FindClassOrDie(env, "com/android/layoutlib/bridge/Bridge");
    jstring stringPath = (jstring) env->CallStaticObjectMethod(bridge,
            GetStaticMethodIDOrDie(env, bridge, "getIcuDataPath", "()Ljava/lang/String;"));
    const char* path = env->GetStringUTFChars(stringPath, 0);
    u_setDataDirectory(path);
    env->ReleaseStringUTFChars(stringPath, path);
    return JNI_VERSION_1_6;
}
