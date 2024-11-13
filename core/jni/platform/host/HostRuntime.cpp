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
#include <android_runtime/AndroidRuntime.h>
#include <jni_wrappers.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/jni_macros.h>
#include <unicode/putil.h>
#include <unicode/udata.h>

#include <clocale>
#include <sstream>
#include <unordered_map>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#endif

using namespace std;

/*
 * This is responsible for setting up the JNI environment for communication between
 * the Java and native parts of layoutlib, including registering native methods.
 * This is mostly achieved by copying the way it is done in the platform
 * (see AndroidRuntime.cpp).
 */

extern int register_android_os_Binder(JNIEnv* env);
extern int register_libcore_util_NativeAllocationRegistry(JNIEnv* env);

typedef void (*FreeFunction)(void*);

static void NativeAllocationRegistry_applyFreeFunction(JNIEnv*, jclass, jlong freeFunction,
                                                       jlong ptr) {
    void* nativePtr = reinterpret_cast<void*>(static_cast<uintptr_t>(ptr));
    FreeFunction nativeFreeFunction =
            reinterpret_cast<FreeFunction>(static_cast<uintptr_t>(freeFunction));
    nativeFreeFunction(nativePtr);
}

static JNINativeMethod gMethods[] = {
        NATIVE_METHOD(NativeAllocationRegistry, applyFreeFunction, "(JJ)V"),
};

int register_libcore_util_NativeAllocationRegistry(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "libcore/util/NativeAllocationRegistry", gMethods,
                                    NELEM(gMethods));
}

namespace android {

extern int register_android_animation_PropertyValuesHolder(JNIEnv* env);
extern int register_android_content_AssetManager(JNIEnv* env);
extern int register_android_content_StringBlock(JNIEnv* env);
extern int register_android_content_XmlBlock(JNIEnv* env);
extern int register_android_content_res_ApkAssets(JNIEnv* env);
extern int register_android_database_CursorWindow(JNIEnv* env);
extern int register_android_database_SQLiteConnection(JNIEnv* env);
extern int register_android_database_SQLiteGlobal(JNIEnv* env);
extern int register_android_database_SQLiteDebug(JNIEnv* env);
extern int register_android_database_SQLiteRawStatement(JNIEnv* env);
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
extern int register_android_view_Surface(JNIEnv* env);
extern int register_android_view_ThreadedRenderer(JNIEnv* env);
extern int register_android_graphics_HardwareBufferRenderer(JNIEnv* env);
extern int register_android_view_VelocityTracker(JNIEnv* env);
extern int register_com_android_internal_util_VirtualRefBasePtr(JNIEnv* env);

#define REG_JNI(name) \
    { name }
struct RegJNIRec {
    int (*mProc)(JNIEnv*);
};

// Map of all possible class names to register to their corresponding JNI registration function
// pointer The actual list of registered classes will be determined at runtime via the
// 'native_classes' System property
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
        {"android.database.sqlite.SQLiteRawStatement",
         REG_JNI(register_android_database_SQLiteRawStatement)},
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
        {"android.view.Surface", REG_JNI(register_android_view_Surface)},
        {"android.view.VelocityTracker", REG_JNI(register_android_view_VelocityTracker)},
        {"com.android.internal.util.VirtualRefBasePtr",
         REG_JNI(register_com_android_internal_util_VirtualRefBasePtr)},
        {"libcore.util.NativeAllocationRegistry",
         REG_JNI(register_libcore_util_NativeAllocationRegistry)},
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
    vector<string> result;
    istringstream stream(csvString);
    string segment;
    while (getline(stream, segment, ',')) {
        result.push_back(segment);
    }
    return result;
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
        void operator()(HANDLE h) {
            CloseHandle(h);
        }
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

// Loads the ICU data file from the location specified in the system property ro.icu.data.path
static void loadIcuData() {
    string icuPath = base::GetProperty("ro.icu.data.path", "");
    if (!icuPath.empty()) {
        // Set the location of ICU data
        void* addr = mmapFile(icuPath.c_str());
        UErrorCode err = U_ZERO_ERROR;
        udata_setCommonData(addr, &err);
        if (err != U_ZERO_ERROR) {
            ALOGE("Unable to load ICU data\n");
        }
    }
}

static int register_android_core_classes(JNIEnv* env) {
    jclass system = FindClassOrDie(env, "java/lang/System");
    jmethodID getPropertyMethod =
            GetStaticMethodIDOrDie(env, system, "getProperty",
                                   "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");

    // Get the names of classes that need to register their native methods
    auto nativesClassesJString =
            (jstring)env->CallStaticObjectMethod(system, getPropertyMethod,
                                                 env->NewStringUTF("core_native_classes"),
                                                 env->NewStringUTF(""));
    const char* nativesClassesArray = env->GetStringUTFChars(nativesClassesJString, nullptr);
    string nativesClassesString(nativesClassesArray);
    vector<string> classesToRegister = parseCsv(nativesClassesString);
    env->ReleaseStringUTFChars(nativesClassesJString, nativesClassesArray);

    if (register_jni_procs(gRegJNIMap, classesToRegister, env) < 0) {
        return JNI_ERR;
    }

    return 0;
}

// Called right before aborting by LOG_ALWAYS_FATAL. Print the pending exception.
void abort_handler(const char* abort_message) {
    ALOGE("About to abort the process...");

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    if (env == nullptr) {
        ALOGE("vm->GetEnv() failed");
        return;
    }
    if (env->ExceptionOccurred() != NULL) {
        ALOGE("Pending exception:");
        env->ExceptionDescribe();
    }
    ALOGE("Aborting because: %s", abort_message);
}

// ------------------ Host implementation of AndroidRuntime ------------------

/*static*/ JavaVM* AndroidRuntime::mJavaVM;

/*static*/ int AndroidRuntime::registerNativeMethods(JNIEnv* env, const char* className,
                                                     const JNINativeMethod* gMethods,
                                                     int numMethods) {
    return jniRegisterNativeMethods(env, className, gMethods, numMethods);
}

/*static*/ JNIEnv* AndroidRuntime::getJNIEnv() {
    JNIEnv* env;
    JavaVM* vm = AndroidRuntime::getJavaVM();
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return nullptr;
    }
    return env;
}

/*static*/ JavaVM* AndroidRuntime::getJavaVM() {
    return mJavaVM;
}

/*static*/ int AndroidRuntime::startReg(JNIEnv* env) {
    if (register_android_core_classes(env) < 0) {
        return JNI_ERR;
    }
    if (register_android_graphics_classes(env) < 0) {
        return JNI_ERR;
    }
    return 0;
}

void AndroidRuntime::onVmCreated(JNIEnv* env) {
    env->GetJavaVM(&mJavaVM);
}

void AndroidRuntime::onStarted() {
    property_initialize_ro_cpu_abilist();
    loadIcuData();

    // Use English locale for number format to ensure correct parsing of floats when using strtof
    setlocale(LC_NUMERIC, "en_US.UTF-8");
}

void AndroidRuntime::start(const char* className, const Vector<String8>& options, bool zygote) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    // Register native functions.
    if (startReg(env) < 0) {
        ALOGE("Unable to register all android native methods\n");
    }
    onStarted();
}

AndroidRuntime::AndroidRuntime(char* argBlockStart, const size_t argBlockLength)
      : mExitWithoutCleanup(false), mArgBlockStart(argBlockStart), mArgBlockLength(argBlockLength) {
    init_android_graphics();
}

AndroidRuntime::~AndroidRuntime() {}

// Version of AndroidRuntime to run on host
class HostRuntime : public AndroidRuntime {
public:
    HostRuntime() : AndroidRuntime(nullptr, 0) {}

    void onVmCreated(JNIEnv* env) override {
        AndroidRuntime::onVmCreated(env);
        // initialize logging, so ANDROD_LOG_TAGS env variable is respected
        android::base::InitLogging(nullptr, android::base::StderrLogger, abort_handler);
    }

    void onStarted() override {
        AndroidRuntime::onStarted();
    }
};

} // namespace android

#ifndef _WIN32
using namespace android;

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    Vector<String8> args;
    HostRuntime runtime;

    runtime.onVmCreated(env);
    runtime.start("HostRuntime", args, false);

    return JNI_VERSION_1_6;
}
#endif
