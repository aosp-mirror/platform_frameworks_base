#include <unicode/putil.h>

#include <string>

#include "core_jni_helpers.h"
#include "jni.h"

static JavaVM* javaVM;

extern int register_android_graphics_Bitmap(JNIEnv*);
extern int register_android_graphics_BitmapFactory(JNIEnv*);
extern int register_android_graphics_CreateJavaOutputStreamAdaptor(JNIEnv* env);
extern int register_android_graphics_Graphics(JNIEnv* env);
extern int register_android_graphics_MaskFilter(JNIEnv* env);

namespace android {

extern int register_android_graphics_ColorFilter(JNIEnv* env);
extern int register_android_graphics_ColorSpace(JNIEnv* env);
extern int register_android_database_CursorWindow(JNIEnv* env);
extern int register_android_graphics_Matrix(JNIEnv* env);
extern int register_android_graphics_Paint(JNIEnv* env);
extern int register_android_graphics_Path(JNIEnv* env);
extern int register_android_graphics_PathMeasure(JNIEnv* env);
extern int register_android_database_SQLiteConnection(JNIEnv* env);

#define REG_JNI(name) \
    { name }
struct RegJNIRec {
    int (*mProc)(JNIEnv*);
};

static const RegJNIRec sqliteJNI[] = {
        REG_JNI(register_android_database_CursorWindow),
        REG_JNI(register_android_database_SQLiteConnection),
};

static const RegJNIRec graphicsJNI[] = {
        REG_JNI(register_android_graphics_Bitmap),
        REG_JNI(register_android_graphics_BitmapFactory),
        REG_JNI(register_android_graphics_ColorFilter),
        REG_JNI(register_android_graphics_ColorSpace),
        REG_JNI(register_android_graphics_CreateJavaOutputStreamAdaptor),
        REG_JNI(register_android_graphics_Graphics),
        REG_JNI(register_android_graphics_MaskFilter),
        REG_JNI(register_android_graphics_Matrix),
        REG_JNI(register_android_graphics_Paint),
        REG_JNI(register_android_graphics_Path),
        REG_JNI(register_android_graphics_PathMeasure),
};

JNIEnv* AndroidRuntime::getJNIEnv() {
    JNIEnv* env;
    if (javaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) return nullptr;
    return env;
}

JavaVM* AndroidRuntime::getJavaVM() {
    return javaVM;
}

int AndroidRuntime::registerNativeMethods(JNIEnv* env, const char* className,
                                          const JNINativeMethod* gMethods, int numMethods) {
    std::string fullClassName = std::string(className);
    std::string classNameString = fullClassName.substr(fullClassName.find_last_of("/"));
    std::string roboNativeBindingClass =
            "org/robolectric/nativeruntime" + classNameString + "Natives";
    jclass clazz = env->FindClass(roboNativeBindingClass.c_str());
    return env->RegisterNatives(clazz, gMethods, numMethods);
}

static int register_jni_procs(const RegJNIRec array[], size_t count, JNIEnv* env) {
    for (size_t i = 0; i < count; i++) {
        if (array[i].mProc(env) < 0) {
            return -1;
        }
    }
    return 0;
}

} // namespace android

using namespace android;

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    javaVM = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    if (register_jni_procs(sqliteJNI, NELEM(sqliteJNI), env) < 0) {
        return JNI_ERR;
    }
    jclass runtimeEnvironment = FindClassOrDie(env, "org/robolectric/RuntimeEnvironment");
    jmethodID getApiLevelMethod =
            GetStaticMethodIDOrDie(env, runtimeEnvironment, "getApiLevel", "()I");

    jint apiLevel = (jint)env->CallStaticIntMethod(runtimeEnvironment, getApiLevelMethod);

    // Native graphics currently supports SDK 26 and above
    if (apiLevel >= 26) {
        if (register_jni_procs(graphicsJNI, NELEM(graphicsJNI), env) < 0) {
            return JNI_ERR;
        }
    }

    // Configuration is stored as java System properties.
    // Get a reference to System.getProperty
    jclass system = FindClassOrDie(env, "java/lang/System");
    jmethodID getPropertyMethod =
            GetStaticMethodIDOrDie(env, system, "getProperty",
                                   "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");

    // Set the location of ICU data
    auto stringPath = (jstring)env->CallStaticObjectMethod(system, getPropertyMethod,
                                                           env->NewStringUTF("icu.dir"),
                                                           env->NewStringUTF(""));
    const char* path = env->GetStringUTFChars(stringPath, 0);
    u_setDataDirectory(path);
    env->ReleaseStringUTFChars(stringPath, path);

    return JNI_VERSION_1_6;
}
