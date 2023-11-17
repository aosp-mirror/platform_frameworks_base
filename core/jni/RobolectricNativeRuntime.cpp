#include <unicode/putil.h>

#include <string>

#include <android/graphics/jni_runtime.h>
#include <sys/stat.h>
#include "core_jni_helpers.h"
#include "jni.h"
#include "unicode/locid.h"

static JavaVM* javaVM;

extern int register_libcore_util_NativeAllocationRegistry(JNIEnv* env);
extern int register_android_media_ImageReader(JNIEnv* env);

namespace android {

extern int register_android_animation_PropertyValuesHolder(JNIEnv* env);
extern int register_android_database_CursorWindow(JNIEnv* env);
extern int register_android_database_SQLiteConnection(JNIEnv* env);
extern int register_android_view_Surface(JNIEnv* env);
extern int register_com_android_internal_util_VirtualRefBasePtr(JNIEnv* env);

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
        REG_JNI(register_android_animation_PropertyValuesHolder),
        REG_JNI(register_android_media_ImageReader),
        REG_JNI(register_android_view_Surface),
        REG_JNI(register_com_android_internal_util_VirtualRefBasePtr),
        REG_JNI(register_libcore_util_NativeAllocationRegistry),
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
    // strip out inner class notation '$'
    classNameString.erase(std::remove(classNameString.begin(), classNameString.end(), '$'),
                          classNameString.end());
    std::string roboNativeBindingClass =
            "org/robolectric/nativeruntime" + classNameString + "Natives";
    jclass clazz = FindClassOrDie(env, roboNativeBindingClass.c_str());
    int res = env->RegisterNatives(clazz, gMethods, numMethods);
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");
    return res;
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

int fileExists(const char* filename) {
    struct stat buffer;
    return (stat(filename, &buffer) == 0);
}

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
        init_android_graphics();
        if (register_android_graphics_classes(env) < 0) {
            return JNI_ERR;
        }
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
    if (!fileExists(path)) {
        fprintf(stderr, "Invalid ICU dat file path '%s'\n", path);
        return JNI_ERR;
    }
    u_setDataDirectory(path);
    env->ReleaseStringUTFChars(stringPath, path);

    // Set the default locale, which is required for e.g. SQLite's 'COLLATE UNICODE'.
    auto stringLanguageTag =
            (jstring)env->CallStaticObjectMethod(system, getPropertyMethod,
                                                 env->NewStringUTF(
                                                         "robolectric.nativeruntime.languageTag"),
                                                 env->NewStringUTF(""));
    int languageTagLength = env->GetStringLength(stringLanguageTag);
    const char* languageTag = env->GetStringUTFChars(stringLanguageTag, 0);
    if (languageTagLength > 0) {
        UErrorCode status = U_ZERO_ERROR;
        icu::Locale locale = icu::Locale::forLanguageTag(languageTag, status);
        if (U_SUCCESS(status)) {
            icu::Locale::setDefault(locale, status);
        }
        if (U_FAILURE(status)) {
            fprintf(stderr, "Failed to set the ICU default locale to '%s' (error code %d)\n",
                    languageTag, status);
        }
    }
    env->ReleaseStringUTFChars(stringLanguageTag, languageTag);
    return JNI_VERSION_1_6;
}
