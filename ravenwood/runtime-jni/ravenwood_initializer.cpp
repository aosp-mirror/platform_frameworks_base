/*
 * Copyright (C) 2024 The Android Open Source Project
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

/*
 * This file is compiled into a single SO file, which we load at the very first.
 * We can do process-wide initialization here.
 * Please be aware that all symbols defined in this SO file will be reloaded
 * as `RTLD_GLOBAL`, so make sure all functions are static except those we EXPLICITLY
 * want to expose and override globally.
 */

#include <dlfcn.h>
#include <fcntl.h>

#include <set>
#include <fstream>
#include <iostream>
#include <string>
#include <cstdlib>

#include "jni_helper.h"

// Implement a rudimentary system properties data store

#define PROP_VALUE_MAX 92

namespace {

struct prop_info {
    std::string key;
    mutable std::string value;
    mutable uint32_t serial;

    prop_info(const char* key, const char* value) : key(key), value(value), serial(0) {}
};

struct prop_info_cmp {
    using is_transparent = void;
    bool operator()(const prop_info& lhs, const prop_info& rhs) {
        return lhs.key < rhs.key;
    }
    bool operator()(std::string_view lhs, const prop_info& rhs) {
        return lhs < rhs.key;
    }
    bool operator()(const prop_info& lhs, std::string_view rhs) {
        return lhs.key < rhs;
    }
};

} // namespace

static auto& g_properties_lock = *new std::mutex;
static auto& g_properties = *new std::set<prop_info, prop_info_cmp>;

static bool property_set(const char* key, const char* value) {
    if (key == nullptr || *key == '\0') return false;
    if (value == nullptr) value = "";
    bool read_only = !strncmp(key, "ro.", 3);
    if (!read_only && strlen(value) >= PROP_VALUE_MAX) return false;

    std::lock_guard lock(g_properties_lock);
    auto [it, success] = g_properties.emplace(key, value);
    if (read_only) return success;
    if (!success) {
        it->value = value;
        ++it->serial;
    }
    return true;
}

template <typename Func>
static void property_get(const char* key, Func callback) {
    std::lock_guard lock(g_properties_lock);
    auto it = g_properties.find(key);
    if (it != g_properties.end()) {
        callback(*it);
    }
}

// Redefine the __system_property_XXX functions here so we can perform
// logging and access checks for all sysprops in native code.

static void check_system_property_access(const char* key, bool write);

extern "C" {

int __system_property_set(const char* key, const char* value) {
    check_system_property_access(key, true);
    return property_set(key, value) ? 0 : -1;
}

int __system_property_get(const char* key, char* value) {
    check_system_property_access(key, false);
    *value = '\0';
    property_get(key, [&](const prop_info& info) {
        snprintf(value, PROP_VALUE_MAX, "%s", info.value.c_str());
    });
    return strlen(value);
}

const prop_info* __system_property_find(const char* key) {
    check_system_property_access(key, false);
    const prop_info* pi = nullptr;
    property_get(key, [&](const prop_info& info) { pi = &info; });
    return pi;
}

void __system_property_read_callback(const prop_info* pi,
                                     void (*callback)(void*, const char*, const char*, uint32_t),
                                     void* cookie) {
    std::lock_guard lock(g_properties_lock);
    callback(cookie, pi->key.c_str(), pi->value.c_str(), pi->serial);
}

} // extern "C"

// ---- JNI ----

static JavaVM* gVM = nullptr;
static jclass gRunnerState = nullptr;
static jmethodID gCheckSystemPropertyAccess;

static void reloadNativeLibrary(JNIEnv* env, jclass, jstring javaPath) {
    ScopedUtfChars path(env, javaPath);
    // Force reload ourselves as global
    dlopen(path.c_str(), RTLD_LAZY | RTLD_GLOBAL | RTLD_NOLOAD);
}

// Call back into Java code to check property access
static void check_system_property_access(const char* key, bool write) {
    if (gVM != nullptr && gRunnerState != nullptr) {
        JNIEnv* env;
        if (gVM->GetEnv((void**)&env, JNI_VERSION_1_4) >= 0) {
            ALOGV("%s access to system property '%s'", write ? "Write" : "Read", key);
            env->CallStaticVoidMethod(gRunnerState, gCheckSystemPropertyAccess,
                                      env->NewStringUTF(key), write ? JNI_TRUE : JNI_FALSE);
            return;
        }
    }
    // Not on JVM thread, abort
    LOG_ALWAYS_FATAL("Access to system property '%s' on non-JVM threads is not allowed.", key);
}

static jstring getSystemProperty(JNIEnv* env, jclass, jstring javaKey) {
    ScopedUtfChars key(env, javaKey);
    jstring value = nullptr;
    property_get(key.c_str(),
                 [&](const prop_info& info) { value = env->NewStringUTF(info.value.c_str()); });
    return value;
}

static jboolean setSystemProperty(JNIEnv* env, jclass, jstring javaKey, jstring javaValue) {
    ScopedUtfChars key(env, javaKey);
    ScopedUtfChars value(env, javaValue);
    return property_set(key.c_str(), value.c_str()) ? JNI_TRUE : JNI_FALSE;
}

static jboolean removeSystemProperty(JNIEnv* env, jclass, jstring javaKey) {
    std::lock_guard lock(g_properties_lock);

    if (javaKey == nullptr) {
        g_properties.clear();
        return JNI_TRUE;
    } else {
        ScopedUtfChars key(env, javaKey);
        auto it = g_properties.find(key);
        if (it != g_properties.end()) {
            g_properties.erase(it);
            return JNI_TRUE;
        } else {
            return JNI_FALSE;
        }
    }
}

// Find the PPID of child_pid using /proc/N/stat. The 4th field is the PPID.
// Also returns child_pid's process name (2nd field).
static pid_t getppid_of(pid_t child_pid, std::string& out_process_name) {
    if (child_pid < 0) {
        return -1;
    }
    std::string stat_file = "/proc/" + std::to_string(child_pid) + "/stat";
    std::ifstream stat_stream(stat_file);
    if (!stat_stream.is_open()) {
        ALOGW("Unable to open '%s': %s", stat_file.c_str(), strerror(errno));
        return -1;
    }

    std::string field;
    int field_count = 0;
    while (std::getline(stat_stream, field, ' ')) {
        if (++field_count == 4) {
            return atoi(field.c_str());
        }
        if (field_count == 2) {
            out_process_name = field;
        }
    }
    ALOGW("Unexpected format in '%s'", stat_file.c_str());
    return -1;
}

// Find atest's PID. Climb up the process tree, and find "atest-py3".
static pid_t find_atest_pid() {
    auto ret = getpid(); // self (isolation runner process)

    while (ret != -1) {
        std::string proc;
        ret = getppid_of(ret, proc);
        if (proc == "(atest-py3)") {
            return ret;
        }
    }

    return ret;
}

// If $RAVENWOOD_LOG_OUT is set, redirect stdout/err to this file.
// Originally it was added to allow to monitor log in realtime, with
// RAVENWOOD_LOG_OUT=$(tty) atest...
//
// As a special case, if $RAVENWOOD_LOG_OUT is set to "-", we try to find
// atest's process and send the output to its stdout. It's sort of hacky, but
// this allows shell redirection to work on Ravenwood output too,
// so e.g. `atest ... |tee atest.log` would work on Ravenwood's output.
// (which wouldn't work with `RAVENWOOD_LOG_OUT=$(tty)`).
//
// Otherwise -- if $RAVENWOOD_LOG_OUT isn't set -- atest/tradefed just writes
// the test's output to its own log file.
static void maybeRedirectLog() {
    auto ravenwoodLogOut = getenv("RAVENWOOD_LOG_OUT");
    if (ravenwoodLogOut == NULL || *ravenwoodLogOut == '\0') {
        return;
    }
    std::string path;
    if (strcmp("-", ravenwoodLogOut) == 0) {
        pid_t ppid = find_atest_pid();
        if (ppid < 0) {
            ALOGI("RAVENWOOD_LOG_OUT set to '-', but unable to find atest's PID");
            return;
        }
        path = std::format("/proc/{}/fd/1", ppid);
    } else {
        path = ravenwoodLogOut;
    }
    ALOGI("RAVENWOOD_LOG_OUT set. Redirecting output to '%s'", path.c_str());

    // Redirect stdin / stdout to /dev/tty.
    int ttyFd = open(path.c_str(), O_WRONLY | O_APPEND);
    if (ttyFd == -1) {
        ALOGW("$RAVENWOOD_LOG_OUT is set, but failed to open '%s': %s ", path.c_str(),
                strerror(errno));
        return;
    }
    dup2(ttyFd, 1);
    dup2(ttyFd, 2);
}

static const JNINativeMethod sMethods[] = {
        {"reloadNativeLibrary", "(Ljava/lang/String;)V", (void*)reloadNativeLibrary},
        {"getSystemProperty", "(Ljava/lang/String;)Ljava/lang/String;", (void*)getSystemProperty},
        {"setSystemProperty", "(Ljava/lang/String;Ljava/lang/String;)Z", (void*)setSystemProperty},
        {"removeSystemProperty", "(Ljava/lang/String;)Z", (void*)removeSystemProperty},
};

extern "C" jint JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    ALOGV("%s: JNI_OnLoad", __FILE__);

    maybeRedirectLog();

    JNIEnv* env = GetJNIEnvOrDie(vm);
    gVM = vm;

    // Fetch several references for future use
    gRunnerState = FindGlobalClassOrDie(env, kRunnerState);
    gCheckSystemPropertyAccess =
            GetStaticMethodIDOrDie(env, gRunnerState, "checkSystemPropertyAccess",
                                   "(Ljava/lang/String;Z)V");

    // Expose raw property methods as JNI methods
    jint res = jniRegisterNativeMethods(env, kRuntimeNative, sMethods, NELEM(sMethods));
    if (res < 0) return -1;

    return JNI_VERSION_1_4;
}
