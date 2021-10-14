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
#include <clocale>
#include <sstream>
#include <unordered_map>
#include <vector>

using namespace std;

/*
 * This is responsible for setting up the JNI environment for communication between
 * the Java and native parts of layoutlib, including registering native methods.
 * This is mostly achieved by copying the way it is done in the platform
 * (see AndroidRuntime.cpp).
 */

static JavaVM* javaVM;

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
extern int register_android_os_SystemClock(JNIEnv* env);
extern int register_android_os_SystemProperties(JNIEnv* env);
extern int register_android_os_Trace(JNIEnv* env);
extern int register_android_text_AndroidCharacter(JNIEnv* env);
extern int register_android_util_EventLog(JNIEnv* env);
extern int register_android_util_Log(JNIEnv* env);
extern int register_android_util_jar_StrictJarFile(JNIEnv* env);
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
        {"android.os.FileObserver", REG_JNI(register_android_os_FileObserver)},
        {"android.os.MessageQueue", REG_JNI(register_android_os_MessageQueue)},
#endif
        {"android.os.SystemClock", REG_JNI(register_android_os_SystemClock)},
        {"android.os.SystemProperties", REG_JNI(register_android_os_SystemProperties)},
        {"android.os.Trace", REG_JNI(register_android_os_Trace)},
        {"android.text.AndroidCharacter", REG_JNI(register_android_text_AndroidCharacter)},
        {"android.util.EventLog", REG_JNI(register_android_util_EventLog)},
        {"android.util.Log", REG_JNI(register_android_util_Log)},
        {"android.util.jar.StrictJarFile", REG_JNI(register_android_util_jar_StrictJarFile)},
        {"com.android.internal.util.VirtualRefBasePtr",
         REG_JNI(register_com_android_internal_util_VirtualRefBasePtr)},
};
// Vector to store the names of classes that need delegates of their native methods
static vector<string> classesToDelegate;

static int register_jni_procs(const std::unordered_map<std::string, RegJNIRec>& jniRegMap,
        const vector<string>& classesToRegister, JNIEnv* env) {

    for (const string& className : classesToRegister) {
        if (jniRegMap.at(className).mProc(env) < 0) {
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

} // namespace android

using namespace android;

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    javaVM = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    // Configuration is stored as java System properties.
    // Get a reference to System.getProperty
    jclass system = FindClassOrDie(env, "java/lang/System");
    jmethodID getPropertyMethod = GetStaticMethodIDOrDie(env, system, "getProperty",
                                                         "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");

    // Get the names of classes that have to delegate their native methods
    auto delegateNativesToNativesString =
            (jstring) env->CallStaticObjectMethod(system,
                    getPropertyMethod, env->NewStringUTF("delegate_natives_to_natives"),
                    env->NewStringUTF(""));
    classesToDelegate = parseCsv(env, delegateNativesToNativesString);

    // Get the names of classes that need to register their native methods
    auto nativesClassesJString =
            (jstring) env->CallStaticObjectMethod(system,
                                                  getPropertyMethod, env->NewStringUTF("native_classes"),
                                                  env->NewStringUTF(""));
    vector<string> classesToRegister = parseCsv(env, nativesClassesJString);

    if (register_jni_procs(gRegJNIMap, classesToRegister, env) < 0) {
        return JNI_ERR;
    }

    // Set the location of ICU data
    auto stringPath = (jstring) env->CallStaticObjectMethod(system,
        getPropertyMethod, env->NewStringUTF("icu.dir"),
        env->NewStringUTF(""));
    const char* path = env->GetStringUTFChars(stringPath, 0);
    u_setDataDirectory(path);
    env->ReleaseStringUTFChars(stringPath, path);

    // Use English locale for number format to ensure correct parsing of floats when using strtof
    setlocale(LC_NUMERIC, "en_US.UTF-8");

    return JNI_VERSION_1_6;
}

