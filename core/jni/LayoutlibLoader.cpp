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

#include <android-base/logging.h>
#include <android-base/properties.h>
#include <android/graphics/jni_runtime.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/jni_macros.h>
#include <unicode/putil.h>
#include <unicode/udata.h>

#include <clocale>
#include <sstream>
#include <unordered_map>
#include <vector>

#include "android_view_InputDevice.h"
#include "core_jni_helpers.h"
#include "jni.h"
#ifdef _WIN32
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#endif

#include <iostream>

using namespace std;

/*
 * This is responsible for setting up the JNI environment for communication between
 * the Java and native parts of layoutlib, including registering native methods.
 * This is mostly achieved by copying the way it is done in the platform
 * (see AndroidRuntime.cpp).
 */

static JavaVM* javaVM;
static jclass bridge;
static jclass layoutLog;
static jmethodID getLogId;
static jmethodID logMethodId;

extern int register_android_os_Binder(JNIEnv* env);
extern int register_libcore_util_NativeAllocationRegistry_Delegate(JNIEnv* env);

typedef void (*FreeFunction)(void*);

static void NativeAllocationRegistry_Delegate_nativeApplyFreeFunction(JNIEnv*, jclass,
                                                                      jlong freeFunction,
                                                                      jlong ptr) {
    void* nativePtr = reinterpret_cast<void*>(static_cast<uintptr_t>(ptr));
    FreeFunction nativeFreeFunction =
            reinterpret_cast<FreeFunction>(static_cast<uintptr_t>(freeFunction));
    nativeFreeFunction(nativePtr);
}

static JNINativeMethod gMethods[] = {
        NATIVE_METHOD(NativeAllocationRegistry_Delegate, nativeApplyFreeFunction, "(JJ)V"),
};

int register_libcore_util_NativeAllocationRegistry_Delegate(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "libcore/util/NativeAllocationRegistry_Delegate", gMethods,
                                    NELEM(gMethods));
}

namespace android {

extern int register_android_animation_PropertyValuesHolder(JNIEnv *env);
extern int register_android_content_AssetManager(JNIEnv* env);
extern int register_android_content_StringBlock(JNIEnv* env);
extern int register_android_content_XmlBlock(JNIEnv* env);
extern int register_android_content_res_ApkAssets(JNIEnv* env);
extern int register_android_database_CursorWindow(JNIEnv* env);
extern int register_android_database_SQLiteConnection(JNIEnv* env);
extern int register_android_database_SQLiteGlobal(JNIEnv* env);
extern int register_android_database_SQLiteDebug(JNIEnv* env);
extern int register_android_os_FileObserver(JNIEnv* env);
extern int register_android_os_MessageQueue(JNIEnv* env);
extern int register_android_os_Parcel(JNIEnv* env);
extern int register_android_os_SystemClock(JNIEnv* env);
extern int register_android_os_SystemProperties(JNIEnv* env);
extern int register_android_os_Trace(JNIEnv* env);
extern int register_android_text_AndroidCharacter(JNIEnv* env);
extern int register_android_util_EventLog(JNIEnv* env);
extern int register_android_util_Log(JNIEnv* env);
extern int register_android_util_jar_StrictJarFile(JNIEnv* env);
extern int register_android_view_KeyCharacterMap(JNIEnv* env);
extern int register_android_view_KeyEvent(JNIEnv* env);
extern int register_android_view_InputDevice(JNIEnv* env);
extern int register_android_view_MotionEvent(JNIEnv* env);
extern int register_android_view_ThreadedRenderer(JNIEnv* env);
extern int register_android_graphics_HardwareBufferRenderer(JNIEnv* env);
extern int register_android_view_VelocityTracker(JNIEnv* env);
extern int register_com_android_internal_util_VirtualRefBasePtr(JNIEnv *env);

#define REG_JNI(name)      { name }
struct RegJNIRec {
    int (*mProc)(JNIEnv*);
};

// Map of all possible class names to register to their corresponding JNI registration function pointer
// The actual list of registered classes will be determined at runtime via the 'native_classes' System property
static const std::unordered_map<std::string, RegJNIRec> gRegJNIMap = {
        {"android.animation.PropertyValuesHolder",
         REG_JNI(register_android_animation_PropertyValuesHolder)},
#ifdef __linux__
        {"android.content.res.ApkAssets", REG_JNI(register_android_content_res_ApkAssets)},
        {"android.content.res.AssetManager", REG_JNI(register_android_content_AssetManager)},
        {"android.database.CursorWindow", REG_JNI(register_android_database_CursorWindow)},
        {"android.database.sqlite.SQLiteConnection",
         REG_JNI(register_android_database_SQLiteConnection)},
        {"android.database.sqlite.SQLiteGlobal", REG_JNI(register_android_database_SQLiteGlobal)},
        {"android.database.sqlite.SQLiteDebug", REG_JNI(register_android_database_SQLiteDebug)},
#endif
        {"android.content.res.StringBlock", REG_JNI(register_android_content_StringBlock)},
        {"android.content.res.XmlBlock", REG_JNI(register_android_content_XmlBlock)},
#ifdef __linux__
        {"android.os.Binder", REG_JNI(register_android_os_Binder)},
        {"android.os.FileObserver", REG_JNI(register_android_os_FileObserver)},
        {"android.os.MessageQueue", REG_JNI(register_android_os_MessageQueue)},
        {"android.os.Parcel", REG_JNI(register_android_os_Parcel)},
#endif
        {"android.os.SystemClock", REG_JNI(register_android_os_SystemClock)},
        {"android.os.SystemProperties", REG_JNI(register_android_os_SystemProperties)},
        {"android.os.Trace", REG_JNI(register_android_os_Trace)},
        {"android.text.AndroidCharacter", REG_JNI(register_android_text_AndroidCharacter)},
        {"android.util.EventLog", REG_JNI(register_android_util_EventLog)},
        {"android.util.Log", REG_JNI(register_android_util_Log)},
        {"android.util.jar.StrictJarFile", REG_JNI(register_android_util_jar_StrictJarFile)},
        {"android.view.KeyCharacterMap", REG_JNI(register_android_view_KeyCharacterMap)},
        {"android.view.KeyEvent", REG_JNI(register_android_view_KeyEvent)},
        {"android.view.InputDevice", REG_JNI(register_android_view_InputDevice)},
        {"android.view.MotionEvent", REG_JNI(register_android_view_MotionEvent)},
        {"android.view.VelocityTracker", REG_JNI(register_android_view_VelocityTracker)},
        {"com.android.internal.util.VirtualRefBasePtr",
         REG_JNI(register_com_android_internal_util_VirtualRefBasePtr)},
        {"libcore.util.NativeAllocationRegistry_Delegate",
         REG_JNI(register_libcore_util_NativeAllocationRegistry_Delegate)},
};

static int register_jni_procs(const std::unordered_map<std::string, RegJNIRec>& jniRegMap,
        const vector<string>& classesToRegister, JNIEnv* env) {

    for (const string& className : classesToRegister) {
        if (jniRegMap.at(className).mProc(env) < 0) {
            return -1;
        }
    }

    if (register_android_graphics_classes(env) < 0) {
        return -1;
    }

    return 0;
}

int AndroidRuntime::registerNativeMethods(JNIEnv* env,
        const char* className, const JNINativeMethod* gMethods, int numMethods) {
    return jniRegisterNativeMethods(env, className, gMethods, numMethods);
}

JNIEnv* AndroidRuntime::getJNIEnv() {
    JNIEnv* env;
    if (javaVM->GetEnv((void**) &env, JNI_VERSION_1_6) != JNI_OK)
        return nullptr;
    return env;
}

JavaVM* AndroidRuntime::getJavaVM() {
    return javaVM;
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

void LayoutlibLogger(base::LogId, base::LogSeverity severity, const char* tag, const char* file,
                     unsigned int line, const char* message) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jint logPrio = severity;
    jstring tagString = env->NewStringUTF(tag);
    jstring messageString = env->NewStringUTF(message);

    jobject bridgeLog = env->CallStaticObjectMethod(bridge, getLogId);

    env->CallVoidMethod(bridgeLog, logMethodId, logPrio, tagString, messageString);

    env->DeleteLocalRef(tagString);
    env->DeleteLocalRef(messageString);
    env->DeleteLocalRef(bridgeLog);
}

void LayoutlibAborter(const char* abort_message) {
    // Layoutlib should not call abort() as it would terminate Studio.
    // Throw an exception back to Java instead.
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jniThrowRuntimeException(env, "The Android framework has encountered a fatal error");
}

// This method has been copied/adapted from system/core/init/property_service.cpp
// If the ro.product.cpu.abilist* properties have not been explicitly
// set, derive them from ro.system.product.cpu.abilist* properties.
static void property_initialize_ro_cpu_abilist() {
    const std::string EMPTY = "";
    const char* kAbilistProp = "ro.product.cpu.abilist";
    const char* kAbilist32Prop = "ro.product.cpu.abilist32";
    const char* kAbilist64Prop = "ro.product.cpu.abilist64";

    // If the properties are defined explicitly, just use them.
    if (base::GetProperty(kAbilistProp, EMPTY) != EMPTY) {
        return;
    }

    std::string abilist32_prop_val;
    std::string abilist64_prop_val;
    const auto abilist32_prop = "ro.system.product.cpu.abilist32";
    const auto abilist64_prop = "ro.system.product.cpu.abilist64";
    abilist32_prop_val = base::GetProperty(abilist32_prop, EMPTY);
    abilist64_prop_val = base::GetProperty(abilist64_prop, EMPTY);

    // Merge ABI lists for ro.product.cpu.abilist
    auto abilist_prop_val = abilist64_prop_val;
    if (abilist32_prop_val != EMPTY) {
        if (abilist_prop_val != EMPTY) {
            abilist_prop_val += ",";
        }
        abilist_prop_val += abilist32_prop_val;
    }

    // Set these properties
    const std::pair<const char*, const std::string&> set_prop_list[] = {
            {kAbilistProp, abilist_prop_val},
            {kAbilist32Prop, abilist32_prop_val},
            {kAbilist64Prop, abilist64_prop_val},
    };
    for (const auto& [prop, prop_val] : set_prop_list) {
        base::SetProperty(prop, prop_val);
    }
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

static bool init_icu(const char* dataPath) {
    void* addr = mmapFile(dataPath);
    UErrorCode err = U_ZERO_ERROR;
    udata_setCommonData(addr, &err);
    if (err != U_ZERO_ERROR) {
        return false;
    }
    return true;
}

// Creates an array of InputDevice from key character map files
static void init_keyboard(JNIEnv* env, const vector<string>& keyboardPaths) {
    jclass inputDevice = FindClassOrDie(env, "android/view/InputDevice");
    jobjectArray inputDevicesArray =
            env->NewObjectArray(keyboardPaths.size(), inputDevice, nullptr);
    int keyboardId = 1;

    for (const string& path : keyboardPaths) {
        base::Result<std::shared_ptr<KeyCharacterMap>> charMap =
                KeyCharacterMap::load(path, KeyCharacterMap::Format::BASE);

        InputDeviceInfo info = InputDeviceInfo();
        info.initialize(keyboardId, 0, 0, InputDeviceIdentifier(),
                        "keyboard " + std::to_string(keyboardId), true, false, 0);
        info.setKeyboardType(AINPUT_KEYBOARD_TYPE_ALPHABETIC);
        info.setKeyCharacterMap(*charMap);

        jobject inputDeviceObj = android_view_InputDevice_create(env, info);
        if (inputDeviceObj) {
            env->SetObjectArrayElement(inputDevicesArray, keyboardId - 1, inputDeviceObj);
            env->DeleteLocalRef(inputDeviceObj);
        }
        keyboardId++;
    }

    if (bridge == nullptr) {
        bridge = FindClassOrDie(env, "com/android/layoutlib/bridge/Bridge");
        bridge = MakeGlobalRefOrDie(env, bridge);
    }
    jmethodID setInputManager = GetStaticMethodIDOrDie(env, bridge, "setInputManager",
                                                       "([Landroid/view/InputDevice;)V");
    env->CallStaticVoidMethod(bridge, setInputManager, inputDevicesArray);
    env->DeleteLocalRef(inputDevicesArray);
}

} // namespace android

using namespace android;

// Called right before aborting by LOG_ALWAYS_FATAL. Print the pending exception.
void abort_handler(const char* abort_message) {
    ALOGE("About to abort the process...");

    JNIEnv* env = NULL;
    if (javaVM->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        ALOGE("vm->GetEnv() failed");
        return;
    }
    if (env->ExceptionOccurred() != NULL) {
        ALOGE("Pending exception:");
        env->ExceptionDescribe();
    }
    ALOGE("Aborting because: %s", abort_message);
}

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    javaVM = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    __android_log_set_aborter(abort_handler);

    init_android_graphics();

    // Configuration is stored as java System properties.
    // Get a reference to System.getProperty
    jclass system = FindClassOrDie(env, "java/lang/System");
    jmethodID getPropertyMethod = GetStaticMethodIDOrDie(env, system, "getProperty",
                                                         "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");

    // Get the names of classes that need to register their native methods
    auto nativesClassesJString =
            (jstring)env->CallStaticObjectMethod(system, getPropertyMethod,
                                                 env->NewStringUTF("core_native_classes"),
                                                 env->NewStringUTF(""));
    vector<string> classesToRegister = parseCsv(env, nativesClassesJString);

    jstring registerProperty =
            (jstring)env->CallStaticObjectMethod(system, getPropertyMethod,
                                                 env->NewStringUTF(
                                                         "register_properties_during_load"),
                                                 env->NewStringUTF(""));
    const char* registerPropertyString = env->GetStringUTFChars(registerProperty, 0);
    if (strcmp(registerPropertyString, "true") == 0) {
        // Set the system properties first as they could be used in the static initialization of
        // other classes
        if (register_android_os_SystemProperties(env) < 0) {
            return JNI_ERR;
        }
        classesToRegister.erase(find(classesToRegister.begin(), classesToRegister.end(),
                                     "android.os.SystemProperties"));
        bridge = FindClassOrDie(env, "com/android/layoutlib/bridge/Bridge");
        bridge = MakeGlobalRefOrDie(env, bridge);
        jmethodID setSystemPropertiesMethod =
                GetStaticMethodIDOrDie(env, bridge, "setSystemProperties", "()V");
        env->CallStaticVoidMethod(bridge, setSystemPropertiesMethod);
        property_initialize_ro_cpu_abilist();
    }
    env->ReleaseStringUTFChars(registerProperty, registerPropertyString);

    if (register_jni_procs(gRegJNIMap, classesToRegister, env) < 0) {
        return JNI_ERR;
    }

    // Set the location of ICU data
    auto stringPath = (jstring)env->CallStaticObjectMethod(system, getPropertyMethod,
                                                           env->NewStringUTF("icu.data.path"),
                                                           env->NewStringUTF(""));
    const char* path = env->GetStringUTFChars(stringPath, 0);

    if (strcmp(path, "**n/a**") != 0) {
        bool icuInitialized = init_icu(path);
        if (!icuInitialized) {
            fprintf(stderr, "Failed to initialize ICU\n");
            return JNI_ERR;
        }
    } else {
        fprintf(stderr, "Skip initializing ICU\n");
    }
    env->ReleaseStringUTFChars(stringPath, path);

    jstring useJniProperty =
            (jstring)env->CallStaticObjectMethod(system, getPropertyMethod,
                                                 env->NewStringUTF("use_bridge_for_logging"),
                                                 env->NewStringUTF(""));
    const char* useJniString = env->GetStringUTFChars(useJniProperty, 0);
    if (strcmp(useJniString, "true") == 0) {
        layoutLog = FindClassOrDie(env, "com/android/ide/common/rendering/api/ILayoutLog");
        layoutLog = MakeGlobalRefOrDie(env, layoutLog);
        logMethodId = GetMethodIDOrDie(env, layoutLog, "logAndroidFramework",
                                       "(ILjava/lang/String;Ljava/lang/String;)V");
        if (bridge == nullptr) {
            bridge = FindClassOrDie(env, "com/android/layoutlib/bridge/Bridge");
            bridge = MakeGlobalRefOrDie(env, bridge);
        }
        getLogId = GetStaticMethodIDOrDie(env, bridge, "getLog",
                                          "()Lcom/android/ide/common/rendering/api/ILayoutLog;");
        android::base::SetLogger(LayoutlibLogger);
        android::base::SetAborter(LayoutlibAborter);
    } else {
        // initialize logging, so ANDROD_LOG_TAGS env variable is respected
        android::base::InitLogging(nullptr, android::base::StderrLogger);
    }
    env->ReleaseStringUTFChars(useJniProperty, useJniString);

    // Use English locale for number format to ensure correct parsing of floats when using strtof
    setlocale(LC_NUMERIC, "en_US.UTF-8");

    auto keyboardPathsJString =
            (jstring)env->CallStaticObjectMethod(system, getPropertyMethod,
                                                 env->NewStringUTF("keyboard_paths"),
                                                 env->NewStringUTF(""));
    const char* keyboardPathsString = env->GetStringUTFChars(keyboardPathsJString, 0);
    if (strcmp(keyboardPathsString, "**n/a**") != 0) {
        vector<string> keyboardPaths = parseCsv(env, keyboardPathsJString);
        init_keyboard(env, keyboardPaths);
    } else {
        fprintf(stderr, "Skip initializing keyboard\n");
    }
    env->ReleaseStringUTFChars(keyboardPathsJString, keyboardPathsString);

    return JNI_VERSION_1_6;
}

JNIEXPORT void JNI_OnUnload(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    env->DeleteGlobalRef(bridge);
    env->DeleteGlobalRef(layoutLog);
}
