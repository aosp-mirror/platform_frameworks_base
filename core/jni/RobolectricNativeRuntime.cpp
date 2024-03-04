#include <unicode/putil.h>

#include <string>
#include <vector>

#include <android/graphics/jni_runtime.h>
#include <sys/stat.h>
#include <unicode/putil.h>
#include <unicode/udata.h>
#include "core_jni_helpers.h"
#include "jni.h"
#include "unicode/locid.h"

#ifdef _WIN32
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#endif

static JavaVM* javaVM;

extern int register_libcore_util_NativeAllocationRegistry(JNIEnv* env);
extern int register_android_media_ImageReader(JNIEnv* env);

namespace android {

extern int register_android_animation_PropertyValuesHolder(JNIEnv* env);
#ifndef _WIN32
extern int register_android_database_CursorWindow(JNIEnv* env);
extern int register_android_database_SQLiteConnection(JNIEnv* env);
#endif
extern int register_android_view_Surface(JNIEnv* env);
extern int register_com_android_internal_util_VirtualRefBasePtr(JNIEnv* env);

#define REG_JNI(name) \
    { name }
struct RegJNIRec {
    int (*mProc)(JNIEnv*);
};

static const RegJNIRec sqliteJNI[] = {
#ifndef _WIN32
        REG_JNI(register_android_database_CursorWindow),
        REG_JNI(register_android_database_SQLiteConnection),
#endif
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

static void* mmapFile(const char* dataFilePath) {
#ifdef _WIN32
    // Windows needs file path in wide chars to handle unicode file paths
    int size = MultiByteToWideChar(CP_UTF8, 0, dataFilePath, -1, NULL, 0);
    std::vector<wchar_t> wideDataFilePath(size);
    MultiByteToWideChar(CP_UTF8, 0, dataFilePath, -1, wideDataFilePath.data(), size);
    HANDLE file =
            CreateFileW(wideDataFilePath.data(), GENERIC_READ, FILE_SHARE_READ, nullptr,
                        OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL | FILE_FLAG_RANDOM_ACCESS, nullptr);
    if ((HANDLE)INVALID_HANDLE_VALUE == file) {
        return nullptr;
    }
    struct CloseHandleWrapper {
        void operator()(HANDLE h) { CloseHandle(h); }
    };
    std::unique_ptr<void, CloseHandleWrapper> mmapHandle(
            CreateFileMapping(file, nullptr, PAGE_READONLY, 0, 0, nullptr));
    if (!mmapHandle) {
        return nullptr;
    }
    return MapViewOfFile(mmapHandle.get(), FILE_MAP_READ, 0, 0, 0);
#else
    int fd = open(dataFilePath, O_RDONLY);
    if (fd == -1) {
        return nullptr;
    }
    struct stat sb;
    if (fstat(fd, &sb) == -1) {
        close(fd);
        return nullptr;
    }
    void* addr = mmap(NULL, sb.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    if (addr == MAP_FAILED) {
        close(fd);
        return nullptr;
    }
    close(fd);
    return addr;
#endif
}

static bool init_icu(const char* dataPath, const char* defaultLocaleLanguageTag) {
    void* addr = mmapFile(dataPath);
    UErrorCode err = U_ZERO_ERROR;
    udata_setCommonData(addr, &err);
    if (err != U_ZERO_ERROR) {
        return false;
    }
    if (defaultLocaleLanguageTag != nullptr && defaultLocaleLanguageTag[0] != '\0') {
        UErrorCode status = U_ZERO_ERROR;
        icu::Locale locale = icu::Locale::forLanguageTag(defaultLocaleLanguageTag, status);
        if (U_SUCCESS(status)) {
            icu::Locale::setDefault(locale, status);
        }
        if (U_FAILURE(status)) {
            fprintf(stderr, "Failed to set the ICU default locale to '%s' (error code %d)\n",
                    defaultLocaleLanguageTag, status);
        }
    }
    return true;
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

    // Get the path to the icu dat file
    auto stringPath = (jstring)env->CallStaticObjectMethod(system, getPropertyMethod,
                                                           env->NewStringUTF("icu.data.path"),
                                                           env->NewStringUTF(""));
    const char* icuPath = env->GetStringUTFChars(stringPath, 0);
    if (!fileExists(icuPath)) {
        fprintf(stderr, "Invalid ICU dat file path '%s'\n", icuPath);
        return JNI_ERR;
    }

    // Get the default language tag
    auto stringLanguageTag =
            (jstring)env->CallStaticObjectMethod(system, getPropertyMethod,
                                                 env->NewStringUTF("icu.locale.default"),
                                                 env->NewStringUTF(""));
    const char* languageTag = env->GetStringUTFChars(stringLanguageTag, 0);

    bool icuInitialized = init_icu(icuPath, languageTag);
    if (!icuInitialized) {
        fprintf(stderr, "Failed to initialize ICU\n");
        return JNI_ERR;
    }
    env->ReleaseStringUTFChars(stringPath, icuPath);
    env->ReleaseStringUTFChars(stringLanguageTag, languageTag);
    return JNI_VERSION_1_6;
}
