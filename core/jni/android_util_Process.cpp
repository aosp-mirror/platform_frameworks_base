/* //device/libs/android_runtime/android_util_Process.cpp
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#define LOG_TAG "Process"

// To make sure cpu_set_t is included from sched.h
#define _GNU_SOURCE 1
#include <utils/Log.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <utils/String8.h>
#include <utils/Vector.h>
#include <meminfo/procmeminfo.h>
#include <meminfo/sysmeminfo.h>
#include <processgroup/processgroup.h>
#include <processgroup/sched_policy.h>
#include <android-base/unique_fd.h>

#include <algorithm>
#include <array>
#include <limits>
#include <memory>
#include <string>
#include <vector>

#include "core_jni_helpers.h"

#include "android_util_Binder.h"
#include <nativehelper/JNIHelp.h>
#include "android_os_Debug.h"

#include <dirent.h>
#include <fcntl.h>
#include <grp.h>
#include <inttypes.h>
#include <pwd.h>
#include <signal.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/errno.h>
#include <sys/pidfd.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/sysinfo.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#define GUARD_THREAD_PRIORITY 0

using namespace android;

static constexpr bool kDebugPolicy = false;
static constexpr bool kDebugProc = false;

// Stack reservation for reading small proc files.  Most callers of
// readProcFile() are reading files under this threshold, e.g.,
// /proc/pid/stat.  /proc/pid/time_in_state ends up being about 520
// bytes, so use 1024 for the stack to provide a bit of slack.
static constexpr ssize_t kProcReadStackBufferSize = 1024;

// The other files we read from proc tend to be a bit larger (e.g.,
// /proc/stat is about 3kB), so once we exhaust the stack buffer,
// retry with a relatively large heap-allocated buffer.  We double
// this size and retry until the whole file fits.
static constexpr ssize_t kProcReadMinHeapBufferSize = 4096;

#if GUARD_THREAD_PRIORITY
Mutex gKeyCreateMutex;
static pthread_key_t gBgKey = -1;
#endif

// For both of these, err should be in the errno range (positive), not a status_t (negative)
static void signalExceptionForError(JNIEnv* env, int err, int tid) {
    switch (err) {
        case EINVAL:
            jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                                 "Invalid argument: %d", tid);
            break;
        case ESRCH:
            jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                                 "Given thread %d does not exist", tid);
            break;
        case EPERM:
            jniThrowExceptionFmt(env, "java/lang/SecurityException",
                                 "No permission to modify given thread %d", tid);
            break;
        default:
            jniThrowException(env, "java/lang/RuntimeException", "Unknown error");
            break;
    }
}

static void signalExceptionForPriorityError(JNIEnv* env, int err, int tid) {
    switch (err) {
        case EACCES:
            jniThrowExceptionFmt(env, "java/lang/SecurityException",
                                 "No permission to set the priority of %d", tid);
            break;
        default:
            signalExceptionForError(env, err, tid);
            break;
    }

}

static void signalExceptionForGroupError(JNIEnv* env, int err, int tid) {
    switch (err) {
        case EACCES:
            jniThrowExceptionFmt(env, "java/lang/SecurityException",
                                 "No permission to set the group of %d", tid);
            break;
        default:
            signalExceptionForError(env, err, tid);
            break;
    }
}

jint android_os_Process_getUidForName(JNIEnv* env, jobject clazz, jstring name)
{
    if (name == NULL) {
        jniThrowNullPointerException(env, NULL);
        return -1;
    }

    const jchar* str16 = env->GetStringCritical(name, 0);
    String8 name8;
    if (str16) {
        name8 = String8(reinterpret_cast<const char16_t*>(str16),
                        env->GetStringLength(name));
        env->ReleaseStringCritical(name, str16);
    }

    const size_t N = name8.size();
    if (N > 0) {
        const char* str = name8.string();
        for (size_t i=0; i<N; i++) {
            if (str[i] < '0' || str[i] > '9') {
                struct passwd* pwd = getpwnam(str);
                if (pwd == NULL) {
                    return -1;
                }
                return pwd->pw_uid;
            }
        }
        return atoi(str);
    }
    return -1;
}

jint android_os_Process_getGidForName(JNIEnv* env, jobject clazz, jstring name)
{
    if (name == NULL) {
        jniThrowNullPointerException(env, NULL);
        return -1;
    }

    const jchar* str16 = env->GetStringCritical(name, 0);
    String8 name8;
    if (str16) {
        name8 = String8(reinterpret_cast<const char16_t*>(str16),
                        env->GetStringLength(name));
        env->ReleaseStringCritical(name, str16);
    }

    const size_t N = name8.size();
    if (N > 0) {
        const char* str = name8.string();
        for (size_t i=0; i<N; i++) {
            if (str[i] < '0' || str[i] > '9') {
                struct group* grp = getgrnam(str);
                if (grp == NULL) {
                    return -1;
                }
                return grp->gr_gid;
            }
        }
        return atoi(str);
    }
    return -1;
}

static bool verifyGroup(JNIEnv* env, int grp)
{
    if (grp < SP_DEFAULT || grp  >= SP_CNT) {
        signalExceptionForError(env, EINVAL, grp);
        return false;
    }
    return true;
}

void android_os_Process_setThreadGroup(JNIEnv* env, jobject clazz, int tid, jint grp)
{
    ALOGV("%s tid=%d grp=%" PRId32, __func__, tid, grp);
    if (!verifyGroup(env, grp)) {
        return;
    }

    int res = SetTaskProfiles(tid, {get_sched_policy_profile_name((SchedPolicy)grp)}, true) ? 0 : -1;

    if (res != NO_ERROR) {
        signalExceptionForGroupError(env, -res, tid);
    }
}

void android_os_Process_setThreadGroupAndCpuset(JNIEnv* env, jobject clazz, int tid, jint grp)
{
    ALOGV("%s tid=%d grp=%" PRId32, __func__, tid, grp);
    if (!verifyGroup(env, grp)) {
        return;
    }

    int res = SetTaskProfiles(tid, {get_cpuset_policy_profile_name((SchedPolicy)grp)}, true) ? 0 : -1;

    if (res != NO_ERROR) {
        signalExceptionForGroupError(env, -res, tid);
    }
}

void android_os_Process_setProcessGroup(JNIEnv* env, jobject clazz, int pid, jint grp)
{
    ALOGV("%s pid=%d grp=%" PRId32, __func__, pid, grp);
    DIR *d;
    char proc_path[255];
    struct dirent *de;

    if (!verifyGroup(env, grp)) {
        return;
    }

    if (grp == SP_FOREGROUND) {
        signalExceptionForGroupError(env, EINVAL, pid);
        return;
    }

    if (grp < 0) {
        grp = SP_FOREGROUND;
    }

    if (kDebugPolicy) {
        char cmdline[32];
        int fd;

        strcpy(cmdline, "unknown");

        sprintf(proc_path, "/proc/%d/cmdline", pid);
        fd = open(proc_path, O_RDONLY | O_CLOEXEC);
        if (fd >= 0) {
            int rc = read(fd, cmdline, sizeof(cmdline)-1);
            cmdline[rc] = 0;
            close(fd);
        }

        if (grp == SP_BACKGROUND) {
            ALOGD("setProcessGroup: vvv pid %d (%s)", pid, cmdline);
        } else {
            ALOGD("setProcessGroup: ^^^ pid %d (%s)", pid, cmdline);
        }
    }

    sprintf(proc_path, "/proc/%d/task", pid);
    if (!(d = opendir(proc_path))) {
        // If the process exited on us, don't generate an exception
        if (errno != ENOENT)
            signalExceptionForGroupError(env, errno, pid);
        return;
    }

    while ((de = readdir(d))) {
        int t_pid;
        int t_pri;
        std::string taskprofile;

        if (de->d_name[0] == '.')
            continue;
        t_pid = atoi(de->d_name);

        if (!t_pid) {
            ALOGE("Error getting pid for '%s'\n", de->d_name);
            continue;
        }

        t_pri = getpriority(PRIO_PROCESS, t_pid);

        if (t_pri <= ANDROID_PRIORITY_AUDIO) {
            int scheduler = sched_getscheduler(t_pid) & ~SCHED_RESET_ON_FORK;
            if ((scheduler == SCHED_FIFO) || (scheduler == SCHED_RR)) {
                // This task wants to stay in its current audio group so it can keep its budget
                // don't update its cpuset or cgroup
                continue;
            }
        }

        errno = 0;
        // grp == SP_BACKGROUND. Set background cpuset policy profile for all threads.
        if (grp == SP_BACKGROUND) {
            if (!SetTaskProfiles(t_pid, {"CPUSET_SP_BACKGROUND"}, true)) {
                signalExceptionForGroupError(env, errno ? errno : EPERM, t_pid);
                break;
            }
            continue;
        }

        // grp != SP_BACKGROUND. Only change the cpuset cgroup for low priority thread, so it could
        // preserve it sched policy profile setting.
        if (t_pri >= ANDROID_PRIORITY_BACKGROUND) {
            switch (grp) {
                case SP_SYSTEM:
                    taskprofile = "ServiceCapacityLow";
                    break;
                case SP_RESTRICTED:
                    taskprofile = "ServiceCapacityRestricted";
                    break;
                case SP_FOREGROUND:
                case SP_AUDIO_APP:
                case SP_AUDIO_SYS:
                    taskprofile = "ProcessCapacityHigh";
                    break;
                case SP_TOP_APP:
                    taskprofile = "ProcessCapacityMax";
                    break;
                default:
                    taskprofile = "ProcessCapacityNormal";
                    break;
            }
            if (!SetTaskProfiles(t_pid, {taskprofile}, true)) {
                signalExceptionForGroupError(env, errno ? errno : EPERM, t_pid);
                break;
            }
        // Change the cpuset policy profile for non-low priority thread according to the grp
        } else {
            if (!SetTaskProfiles(t_pid, {get_cpuset_policy_profile_name((SchedPolicy)grp)}, true)) {
                signalExceptionForGroupError(env, errno ? errno : EPERM, t_pid);
                break;
            }
        }
    }
    closedir(d);
}

void android_os_Process_setProcessFrozen(
        JNIEnv *env, jobject clazz, jint pid, jint uid, jboolean freeze)
{
    bool success = true;

    if (freeze) {
        success = SetProcessProfiles(uid, pid, {"Frozen"});
    } else {
        success = SetProcessProfiles(uid, pid, {"Unfrozen"});
    }

    if (!success) {
        signalExceptionForGroupError(env, EINVAL, pid);
    }
}

jint android_os_Process_getProcessGroup(JNIEnv* env, jobject clazz, jint pid)
{
    SchedPolicy sp;
    if (get_sched_policy(pid, &sp) != 0) {
        signalExceptionForGroupError(env, errno, pid);
    }
    return (int) sp;
}

jint android_os_Process_createProcessGroup(JNIEnv* env, jobject clazz, jint uid, jint pid) {
    return createProcessGroup(uid, pid);
}

/** Sample CPUset list format:
 *  0-3,4,6-8
 */
static void parse_cpuset_cpus(char *cpus, cpu_set_t *cpu_set) {
    unsigned int start, end, matched, i;
    char *cpu_range = strtok(cpus, ",");
    while (cpu_range != NULL) {
        start = end = 0;
        matched = sscanf(cpu_range, "%u-%u", &start, &end);
        cpu_range = strtok(NULL, ",");
        if (start >= CPU_SETSIZE) {
            ALOGE("parse_cpuset_cpus: ignoring CPU number larger than %d.", CPU_SETSIZE);
            continue;
        } else if (end >= CPU_SETSIZE) {
            ALOGE("parse_cpuset_cpus: ignoring CPU numbers larger than %d.", CPU_SETSIZE);
            end = CPU_SETSIZE - 1;
        }
        if (matched == 1) {
            CPU_SET(start, cpu_set);
        } else if (matched == 2) {
            for (i = start; i <= end; i++) {
                CPU_SET(i, cpu_set);
            }
        } else {
            ALOGE("Failed to match cpus");
        }
    }
    return;
}

/**
 * Stores the CPUs assigned to the cpuset corresponding to the
 * SchedPolicy in the passed in cpu_set.
 */
static void get_cpuset_cores_for_policy(SchedPolicy policy, cpu_set_t *cpu_set)
{
    FILE *file;
    std::string filename;

    CPU_ZERO(cpu_set);

    switch (policy) {
        case SP_BACKGROUND:
            if (!CgroupGetAttributePath("LowCapacityCPUs", &filename)) {
                return;
            }
            break;
        case SP_FOREGROUND:
        case SP_AUDIO_APP:
        case SP_AUDIO_SYS:
        case SP_RT_APP:
            if (!CgroupGetAttributePath("HighCapacityCPUs", &filename)) {
                return;
            }
            break;
        case SP_TOP_APP:
            if (!CgroupGetAttributePath("MaxCapacityCPUs", &filename)) {
                return;
            }
            break;
        default:
            return;
    }

    file = fopen(filename.c_str(), "re");
    if (file != NULL) {
        // Parse cpus string
        char *line = NULL;
        size_t len = 0;
        ssize_t num_read = getline(&line, &len, file);
        fclose (file);
        if (num_read > 0) {
            parse_cpuset_cpus(line, cpu_set);
        } else {
            ALOGE("Failed to read %s", filename.c_str());
        }
        free(line);
    }
    return;
}


/**
 * Determine CPU cores exclusively assigned to the
 * cpuset corresponding to the SchedPolicy and store
 * them in the passed in cpu_set_t
 */
void get_exclusive_cpuset_cores(SchedPolicy policy, cpu_set_t *cpu_set) {
    if (cpusets_enabled()) {
        int i;
        cpu_set_t tmp_set;
        get_cpuset_cores_for_policy(policy, cpu_set);
        for (i = 0; i < SP_CNT; i++) {
            if ((SchedPolicy) i == policy) continue;
            get_cpuset_cores_for_policy((SchedPolicy)i, &tmp_set);
            // First get cores exclusive to one set or the other
            CPU_XOR(&tmp_set, cpu_set, &tmp_set);
            // Then get the ones only in cpu_set
            CPU_AND(cpu_set, cpu_set, &tmp_set);
        }
    } else {
        CPU_ZERO(cpu_set);
    }
    return;
}

jintArray android_os_Process_getExclusiveCores(JNIEnv* env, jobject clazz) {
    SchedPolicy sp;
    cpu_set_t cpu_set;
    jintArray cpus;
    int pid = getpid();
    if (get_sched_policy(pid, &sp) != 0) {
        signalExceptionForGroupError(env, errno, pid);
        return NULL;
    }
    get_exclusive_cpuset_cores(sp, &cpu_set);
    int num_cpus = CPU_COUNT(&cpu_set);
    cpus = env->NewIntArray(num_cpus);
    if (cpus == NULL) {
        jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
        return NULL;
    }

    jint* cpu_elements = env->GetIntArrayElements(cpus, 0);
    int count = 0;
    for (int i = 0; i < CPU_SETSIZE && count < num_cpus; i++) {
        if (CPU_ISSET(i, &cpu_set)) {
            cpu_elements[count++] = i;
        }
    }

    env->ReleaseIntArrayElements(cpus, cpu_elements, 0);
    return cpus;
}

static void android_os_Process_setCanSelfBackground(JNIEnv* env, jobject clazz, jboolean bgOk) {
    // Establishes the calling thread as illegal to put into the background.
    // Typically used only for the system process's main looper.
#if GUARD_THREAD_PRIORITY
    ALOGV("Process.setCanSelfBackground(%d) : tid=%d", bgOk, gettid());
    {
        Mutex::Autolock _l(gKeyCreateMutex);
        if (gBgKey == -1) {
            pthread_key_create(&gBgKey, NULL);
        }
    }

    // inverted:  not-okay, we set a sentinel value
    pthread_setspecific(gBgKey, (void*)(bgOk ? 0 : 0xbaad));
#endif
}

jint android_os_Process_getThreadScheduler(JNIEnv* env, jclass clazz,
                                              jint tid)
{
    int policy = 0;
// linux has sched_getscheduler(), others don't.
#if defined(__linux__)
    errno = 0;
    policy = sched_getscheduler(tid);
    if (errno != 0) {
        signalExceptionForPriorityError(env, errno, tid);
    }
#else
    signalExceptionForPriorityError(env, ENOSYS, tid);
#endif
    return policy;
}

void android_os_Process_setThreadScheduler(JNIEnv* env, jclass clazz,
                                              jint tid, jint policy, jint pri)
{
// linux has sched_setscheduler(), others don't.
#if defined(__linux__)
    struct sched_param param;
    param.sched_priority = pri;
    int rc = sched_setscheduler(tid, policy, &param);
    if (rc) {
        signalExceptionForPriorityError(env, errno, tid);
    }
#else
    signalExceptionForPriorityError(env, ENOSYS, tid);
#endif
}

void android_os_Process_setThreadPriority(JNIEnv* env, jobject clazz,
                                              jint pid, jint pri)
{
#if GUARD_THREAD_PRIORITY
    // if we're putting the current thread into the background, check the TLS
    // to make sure this thread isn't guarded.  If it is, raise an exception.
    if (pri >= ANDROID_PRIORITY_BACKGROUND) {
        if (pid == gettid()) {
            void* bgOk = pthread_getspecific(gBgKey);
            if (bgOk == ((void*)0xbaad)) {
                ALOGE("Thread marked fg-only put self in background!");
                jniThrowException(env, "java/lang/SecurityException", "May not put this thread into background");
                return;
            }
        }
    }
#endif

    int rc = androidSetThreadPriority(pid, pri);
    if (rc != 0) {
        if (rc == INVALID_OPERATION) {
            signalExceptionForPriorityError(env, errno, pid);
        } else {
            signalExceptionForGroupError(env, errno, pid);
        }
    }

    //ALOGI("Setting priority of %" PRId32 ": %" PRId32 ", getpriority returns %d\n",
    //     pid, pri, getpriority(PRIO_PROCESS, pid));
}

void android_os_Process_setCallingThreadPriority(JNIEnv* env, jobject clazz,
                                                        jint pri)
{
    android_os_Process_setThreadPriority(env, clazz, gettid(), pri);
}

jint android_os_Process_getThreadPriority(JNIEnv* env, jobject clazz,
                                              jint pid)
{
    errno = 0;
    jint pri = getpriority(PRIO_PROCESS, pid);
    if (errno != 0) {
        signalExceptionForPriorityError(env, errno, pid);
    }
    //ALOGI("Returning priority of %" PRId32 ": %" PRId32 "\n", pid, pri);
    return pri;
}

jboolean android_os_Process_setSwappiness(JNIEnv *env, jobject clazz,
                                          jint pid, jboolean is_increased)
{
    char text[64];

    if (is_increased) {
        strcpy(text, "/sys/fs/cgroup/memory/sw/tasks");
    } else {
        strcpy(text, "/sys/fs/cgroup/memory/tasks");
    }

    struct stat st;
    if (stat(text, &st) || !S_ISREG(st.st_mode)) {
        return false;
    }

    int fd = open(text, O_WRONLY | O_CLOEXEC);
    if (fd >= 0) {
        sprintf(text, "%" PRId32, pid);
        write(fd, text, strlen(text));
        close(fd);
    }

    return true;
}

void android_os_Process_setArgV0(JNIEnv* env, jobject clazz, jstring name)
{
    if (name == NULL) {
        jniThrowNullPointerException(env, NULL);
        return;
    }

    const jchar* str = env->GetStringCritical(name, 0);
    String8 name8;
    if (str) {
        name8 = String8(reinterpret_cast<const char16_t*>(str),
                        env->GetStringLength(name));
        env->ReleaseStringCritical(name, str);
    }

    if (!name8.isEmpty()) {
        AndroidRuntime::getRuntime()->setArgv0(name8.string(), true /* setProcName */);
    }
}

jint android_os_Process_setUid(JNIEnv* env, jobject clazz, jint uid)
{
    return setuid(uid) == 0 ? 0 : errno;
}

jint android_os_Process_setGid(JNIEnv* env, jobject clazz, jint uid)
{
    return setgid(uid) == 0 ? 0 : errno;
}

static int pid_compare(const void* v1, const void* v2)
{
    //ALOGI("Compare %" PRId32 " vs %" PRId32 "\n", *((const jint*)v1), *((const jint*)v2));
    return *((const jint*)v1) - *((const jint*)v2);
}

static jlong android_os_Process_getFreeMemory(JNIEnv* env, jobject clazz)
{
    std::array<std::string_view, 2> memFreeTags = {
        ::android::meminfo::SysMemInfo::kMemFree,
        ::android::meminfo::SysMemInfo::kMemCached,
    };
    std::vector<uint64_t> mem(memFreeTags.size());
    ::android::meminfo::SysMemInfo smi;

    if (!smi.ReadMemInfo(memFreeTags.size(),
                         memFreeTags.data(),
                         mem.data())) {
        jniThrowRuntimeException(env, "SysMemInfo read failed to get Free Memory");
        return -1L;
    }

    jlong sum = 0;
    std::for_each(mem.begin(), mem.end(), [&](uint64_t val) { sum += val; });
    return sum * 1024;
}

static jlong android_os_Process_getTotalMemory(JNIEnv* env, jobject clazz)
{
    struct sysinfo si;
    if (sysinfo(&si) == -1) {
        ALOGE("sysinfo failed: %s", strerror(errno));
        return -1;
    }

    return static_cast<jlong>(si.totalram) * si.mem_unit;
}

/*
 * The outFields array is initialized to -1 to allow the caller to identify
 * when the status file (and therefore the process) they specified is invalid.
 * This array should not be overwritten or cleared before we know that the
 * status file can be read.
 */
void android_os_Process_readProcLines(JNIEnv* env, jobject clazz, jstring fileStr,
                                      jobjectArray reqFields, jlongArray outFields)
{
    //ALOGI("getMemInfo: %p %p", reqFields, outFields);

    if (fileStr == NULL || reqFields == NULL || outFields == NULL) {
        jniThrowNullPointerException(env, NULL);
        return;
    }

    const char* file8 = env->GetStringUTFChars(fileStr, NULL);
    if (file8 == NULL) {
        return;
    }
    String8 file(file8);
    env->ReleaseStringUTFChars(fileStr, file8);

    jsize count = env->GetArrayLength(reqFields);
    if (count > env->GetArrayLength(outFields)) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Array lengths differ");
        return;
    }

    Vector<String8> fields;
    int i;

    for (i=0; i<count; i++) {
        jobject obj = env->GetObjectArrayElement(reqFields, i);
        if (obj != NULL) {
            const char* str8 = env->GetStringUTFChars((jstring)obj, NULL);
            //ALOGI("String at %d: %p = %s", i, obj, str8);
            if (str8 == NULL) {
                jniThrowNullPointerException(env, "Element in reqFields");
                return;
            }
            fields.add(String8(str8));
            env->ReleaseStringUTFChars((jstring)obj, str8);
        } else {
            jniThrowNullPointerException(env, "Element in reqFields");
            return;
        }
    }

    jlong* sizesArray = env->GetLongArrayElements(outFields, 0);
    if (sizesArray == NULL) {
        return;
    }

    int fd = open(file.string(), O_RDONLY | O_CLOEXEC);

    if (fd >= 0) {
        //ALOGI("Clearing %" PRId32 " sizes", count);
        for (i=0; i<count; i++) {
            sizesArray[i] = 0;
        }

        const size_t BUFFER_SIZE = 4096;
        char* buffer = (char*)malloc(BUFFER_SIZE);
        int len = read(fd, buffer, BUFFER_SIZE-1);
        close(fd);

        if (len < 0) {
            ALOGW("Unable to read %s", file.string());
            len = 0;
        }
        buffer[len] = 0;

        int foundCount = 0;

        char* p = buffer;
        while (*p && foundCount < count) {
            bool skipToEol = true;
            //ALOGI("Parsing at: %s", p);
            for (i=0; i<count; i++) {
                const String8& field = fields[i];
                if (strncmp(p, field.string(), field.length()) == 0) {
                    p += field.length();
                    while (*p == ' ' || *p == '\t') p++;
                    char* num = p;
                    while (*p >= '0' && *p <= '9') p++;
                    skipToEol = *p != '\n';
                    if (*p != 0) {
                        *p = 0;
                        p++;
                    }
                    char* end;
                    sizesArray[i] = strtoll(num, &end, 10);
                    //ALOGI("Field %s = %" PRId64, field.string(), sizesArray[i]);
                    foundCount++;
                    break;
                }
            }
            if (skipToEol) {
                while (*p && *p != '\n') {
                    p++;
                }
                if (*p == '\n') {
                    p++;
                }
            }
        }

        free(buffer);
    } else {
        ALOGW("Unable to open %s", file.string());
    }

    //ALOGI("Done!");
    env->ReleaseLongArrayElements(outFields, sizesArray, 0);
}

jintArray android_os_Process_getPids(JNIEnv* env, jobject clazz,
                                     jstring file, jintArray lastArray)
{
    if (file == NULL) {
        jniThrowNullPointerException(env, NULL);
        return NULL;
    }

    const char* file8 = env->GetStringUTFChars(file, NULL);
    if (file8 == NULL) {
        jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
        return NULL;
    }

    DIR* dirp = opendir(file8);

    env->ReleaseStringUTFChars(file, file8);

    if(dirp == NULL) {
        return NULL;
    }

    jsize curCount = 0;
    jint* curData = NULL;
    if (lastArray != NULL) {
        curCount = env->GetArrayLength(lastArray);
        curData = env->GetIntArrayElements(lastArray, 0);
    }

    jint curPos = 0;

    struct dirent* entry;
    while ((entry=readdir(dirp)) != NULL) {
        const char* p = entry->d_name;
        while (*p) {
            if (*p < '0' || *p > '9') break;
            p++;
        }
        if (*p != 0) continue;

        char* end;
        int pid = strtol(entry->d_name, &end, 10);
        //ALOGI("File %s pid=%d\n", entry->d_name, pid);
        if (curPos >= curCount) {
            jsize newCount = (curCount == 0) ? 10 : (curCount*2);
            jintArray newArray = env->NewIntArray(newCount);
            if (newArray == NULL) {
                closedir(dirp);
                jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
                return NULL;
            }
            jint* newData = env->GetIntArrayElements(newArray, 0);
            if (curData != NULL) {
                memcpy(newData, curData, sizeof(jint)*curCount);
                env->ReleaseIntArrayElements(lastArray, curData, 0);
            }
            lastArray = newArray;
            curCount = newCount;
            curData = newData;
        }

        curData[curPos] = pid;
        curPos++;
    }

    closedir(dirp);

    if (curData != NULL && curPos > 0) {
        qsort(curData, curPos, sizeof(jint), pid_compare);
    }

    while (curPos < curCount) {
        curData[curPos] = -1;
        curPos++;
    }

    if (curData != NULL) {
        env->ReleaseIntArrayElements(lastArray, curData, 0);
    }

    return lastArray;
}

enum {
    PROC_TERM_MASK = 0xff,
    PROC_ZERO_TERM = 0,
    PROC_SPACE_TERM = ' ',
    PROC_COMBINE = 0x100,
    PROC_PARENS = 0x200,
    PROC_QUOTES = 0x400,
    PROC_CHAR = 0x800,
    PROC_OUT_STRING = 0x1000,
    PROC_OUT_LONG = 0x2000,
    PROC_OUT_FLOAT = 0x4000,
};

jboolean android_os_Process_parseProcLineArray(JNIEnv* env, jobject clazz,
        char* buffer, jint startIndex, jint endIndex, jintArray format,
        jobjectArray outStrings, jlongArray outLongs, jfloatArray outFloats)
{

    const jsize NF = env->GetArrayLength(format);
    const jsize NS = outStrings ? env->GetArrayLength(outStrings) : 0;
    const jsize NL = outLongs ? env->GetArrayLength(outLongs) : 0;
    const jsize NR = outFloats ? env->GetArrayLength(outFloats) : 0;

    jint* formatData = env->GetIntArrayElements(format, 0);
    jlong* longsData = outLongs ?
        env->GetLongArrayElements(outLongs, 0) : NULL;
    jfloat* floatsData = outFloats ?
        env->GetFloatArrayElements(outFloats, 0) : NULL;
    if (formatData == NULL || (NL > 0 && longsData == NULL)
            || (NR > 0 && floatsData == NULL)) {
        if (formatData != NULL) {
            env->ReleaseIntArrayElements(format, formatData, 0);
        }
        if (longsData != NULL) {
            env->ReleaseLongArrayElements(outLongs, longsData, 0);
        }
        if (floatsData != NULL) {
            env->ReleaseFloatArrayElements(outFloats, floatsData, 0);
        }
        jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
        return JNI_FALSE;
    }

    jsize i = startIndex;
    jsize di = 0;

    jboolean res = JNI_TRUE;

    for (jsize fi=0; fi<NF; fi++) {
        jint mode = formatData[fi];
        if ((mode&PROC_PARENS) != 0) {
            i++;
        } else if ((mode&PROC_QUOTES) != 0) {
            if (buffer[i] == '"') {
                i++;
            } else {
                mode &= ~PROC_QUOTES;
            }
        }
        const char term = (char)(mode&PROC_TERM_MASK);
        const jsize start = i;
        if (i >= endIndex) {
            if (kDebugProc) {
                ALOGW("Ran off end of data @%d", i);
            }
            res = JNI_FALSE;
            break;
        }

        jsize end = -1;
        if ((mode&PROC_PARENS) != 0) {
            while (i < endIndex && buffer[i] != ')') {
                i++;
            }
            end = i;
            i++;
        } else if ((mode&PROC_QUOTES) != 0) {
            while (buffer[i] != '"' && i < endIndex) {
                i++;
            }
            end = i;
            i++;
        }
        while (i < endIndex && buffer[i] != term) {
            i++;
        }
        if (end < 0) {
            end = i;
        }

        if (i < endIndex) {
            i++;
            if ((mode&PROC_COMBINE) != 0) {
                while (i < endIndex && buffer[i] == term) {
                    i++;
                }
            }
        }

        //ALOGI("Field %" PRId32 ": %" PRId32 "-%" PRId32 " dest=%" PRId32 " mode=0x%" PRIx32 "\n", i, start, end, di, mode);

        if ((mode&(PROC_OUT_FLOAT|PROC_OUT_LONG|PROC_OUT_STRING)) != 0) {
            char c = buffer[end];
            buffer[end] = 0;
            if ((mode&PROC_OUT_FLOAT) != 0 && di < NR) {
                char* end;
                floatsData[di] = strtof(buffer+start, &end);
            }
            if ((mode&PROC_OUT_LONG) != 0 && di < NL) {
                if ((mode&PROC_CHAR) != 0) {
                    // Caller wants single first character returned as one long.
                    longsData[di] = buffer[start];
                } else {
                    char* end;
                    longsData[di] = strtoll(buffer+start, &end, 10);
                }
            }
            if ((mode&PROC_OUT_STRING) != 0 && di < NS) {
                jstring str = env->NewStringUTF(buffer+start);
                env->SetObjectArrayElement(outStrings, di, str);
            }
            buffer[end] = c;
            di++;
        }
    }

    env->ReleaseIntArrayElements(format, formatData, 0);
    if (longsData != NULL) {
        env->ReleaseLongArrayElements(outLongs, longsData, 0);
    }
    if (floatsData != NULL) {
        env->ReleaseFloatArrayElements(outFloats, floatsData, 0);
    }

    return res;
}

jboolean android_os_Process_parseProcLine(JNIEnv* env, jobject clazz,
        jbyteArray buffer, jint startIndex, jint endIndex, jintArray format,
        jobjectArray outStrings, jlongArray outLongs, jfloatArray outFloats)
{
        jbyte* bufferArray = env->GetByteArrayElements(buffer, NULL);

        jboolean result = android_os_Process_parseProcLineArray(env, clazz,
                (char*) bufferArray, startIndex, endIndex, format, outStrings,
                outLongs, outFloats);

        env->ReleaseByteArrayElements(buffer, bufferArray, 0);

        return result;
}

jboolean android_os_Process_readProcFile(JNIEnv* env, jobject clazz,
        jstring file, jintArray format, jobjectArray outStrings,
        jlongArray outLongs, jfloatArray outFloats)
{
    if (file == NULL || format == NULL) {
        jniThrowNullPointerException(env, NULL);
        return JNI_FALSE;
    }

    const char* file8 = env->GetStringUTFChars(file, NULL);
    if (file8 == NULL) {
        jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
        return JNI_FALSE;
    }

    ::android::base::unique_fd fd(open(file8, O_RDONLY | O_CLOEXEC));
    if (!fd.ok()) {
        if (kDebugProc) {
            ALOGW("Unable to open process file: %s\n", file8);
        }
        env->ReleaseStringUTFChars(file, file8);
        return JNI_FALSE;
    }
    env->ReleaseStringUTFChars(file, file8);

    // Most proc files we read are small, so we only go through the
    // loop once and use the stack buffer.  We allocate a buffer big
    // enough for the whole file.

    char readBufferStack[kProcReadStackBufferSize];
    std::unique_ptr<char[]> readBufferHeap;
    char* readBuffer = &readBufferStack[0];
    ssize_t readBufferSize = kProcReadStackBufferSize;
    ssize_t numberBytesRead;
    for (;;) {
        // By using pread, we can avoid an lseek to rewind the FD
        // before retry, saving a system call.
        numberBytesRead = pread(fd, readBuffer, readBufferSize, 0);
        if (numberBytesRead < 0 && errno == EINTR) {
            continue;
        }
        if (numberBytesRead < 0) {
            if (kDebugProc) {
                ALOGW("Unable to open process file: %s fd=%d\n", file8, fd.get());
            }
            return JNI_FALSE;
        }
        if (numberBytesRead < readBufferSize) {
            break;
        }
        if (readBufferSize > std::numeric_limits<ssize_t>::max() / 2) {
            if (kDebugProc) {
                ALOGW("Proc file too big: %s fd=%d\n", file8, fd.get());
            }
            return JNI_FALSE;
        }
        readBufferSize = std::max(readBufferSize * 2,
                                  kProcReadMinHeapBufferSize);
        readBufferHeap.reset();  // Free address space before getting more.
        readBufferHeap = std::make_unique<char[]>(readBufferSize);
        if (!readBufferHeap) {
            jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
            return JNI_FALSE;
        }
        readBuffer = readBufferHeap.get();
    }

    // parseProcLineArray below modifies the buffer while parsing!
    return android_os_Process_parseProcLineArray(
        env, clazz, readBuffer, 0, numberBytesRead,
        format, outStrings, outLongs, outFloats);
}

void android_os_Process_setApplicationObject(JNIEnv* env, jobject clazz,
                                             jobject binderObject)
{
    if (binderObject == NULL) {
        jniThrowNullPointerException(env, NULL);
        return;
    }

    sp<IBinder> binder = ibinderForJavaObject(env, binderObject);
}

void android_os_Process_sendSignal(JNIEnv* env, jobject clazz, jint pid, jint sig)
{
    if (pid > 0) {
        ALOGI("Sending signal. PID: %" PRId32 " SIG: %" PRId32, pid, sig);
        kill(pid, sig);
    }
}

void android_os_Process_sendSignalQuiet(JNIEnv* env, jobject clazz, jint pid, jint sig)
{
    if (pid > 0) {
        kill(pid, sig);
    }
}

static jlong android_os_Process_getElapsedCpuTime(JNIEnv* env, jobject clazz)
{
    struct timespec ts;

    int res = clock_gettime(CLOCK_PROCESS_CPUTIME_ID, &ts);

    if (res != 0) {
        return (jlong) 0;
    }

    nsecs_t when = seconds_to_nanoseconds(ts.tv_sec) + ts.tv_nsec;
    return (jlong) nanoseconds_to_milliseconds(when);
}

static jlong android_os_Process_getPss(JNIEnv* env, jobject clazz, jint pid)
{
    ::android::meminfo::ProcMemInfo proc_mem(pid);
    uint64_t pss;
    if (!proc_mem.SmapsOrRollupPss(&pss)) {
        return (jlong) -1;
    }

    // Return the Pss value in bytes, not kilobytes
    return pss * 1024;
}

static jlongArray android_os_Process_getRss(JNIEnv* env, jobject clazz, jint pid)
{
    // total, file, anon, swap
    jlong rss[4] = {0, 0, 0, 0};
    std::string status_path =
            android::base::StringPrintf("/proc/%d/status", pid);
    UniqueFile file = MakeUniqueFile(status_path.c_str(), "re");

    char line[256];
    while (file != nullptr && fgets(line, sizeof(line), file.get())) {
        jlong v;
        if ( sscanf(line, "VmRSS: %" SCNd64 " kB", &v) == 1) {
            rss[0] = v;
        } else if ( sscanf(line, "RssFile: %" SCNd64 " kB", &v) == 1) {
            rss[1] = v;
        } else if ( sscanf(line, "RssAnon: %" SCNd64 " kB", &v) == 1) {
            rss[2] = v;
        } else if ( sscanf(line, "VmSwap: %" SCNd64 " kB", &v) == 1) {
            rss[3] = v;
        }
    }

    jlongArray rssArray = env->NewLongArray(4);
    if (rssArray == NULL) {
        jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
        return NULL;
    }

    env->SetLongArrayRegion(rssArray, 0, 4, rss);

    return rssArray;
}

jintArray android_os_Process_getPidsForCommands(JNIEnv* env, jobject clazz,
        jobjectArray commandNames)
{
    if (commandNames == NULL) {
        jniThrowNullPointerException(env, NULL);
        return NULL;
    }

    Vector<String8> commands;

    jsize count = env->GetArrayLength(commandNames);

    for (int i=0; i<count; i++) {
        jobject obj = env->GetObjectArrayElement(commandNames, i);
        if (obj != NULL) {
            const char* str8 = env->GetStringUTFChars((jstring)obj, NULL);
            if (str8 == NULL) {
                jniThrowNullPointerException(env, "Element in commandNames");
                return NULL;
            }
            commands.add(String8(str8));
            env->ReleaseStringUTFChars((jstring)obj, str8);
        } else {
            jniThrowNullPointerException(env, "Element in commandNames");
            return NULL;
        }
    }

    Vector<jint> pids;

    DIR *proc = opendir("/proc");
    if (proc == NULL) {
        fprintf(stderr, "/proc: %s\n", strerror(errno));
        return NULL;
    }

    struct dirent *d;
    while ((d = readdir(proc))) {
        int pid = atoi(d->d_name);
        if (pid <= 0) continue;

        char path[PATH_MAX];
        char data[PATH_MAX];
        snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);

        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) {
            continue;
        }
        const int len = read(fd, data, sizeof(data)-1);
        close(fd);

        if (len < 0) {
            continue;
        }
        data[len] = 0;

        for (int i=0; i<len; i++) {
            if (data[i] == ' ') {
                data[i] = 0;
                break;
            }
        }

        for (size_t i=0; i<commands.size(); i++) {
            if (commands[i] == data) {
                pids.add(pid);
                break;
            }
        }
    }

    closedir(proc);

    jintArray pidArray = env->NewIntArray(pids.size());
    if (pidArray == NULL) {
        jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
        return NULL;
    }

    if (pids.size() > 0) {
        env->SetIntArrayRegion(pidArray, 0, pids.size(), pids.array());
    }

    return pidArray;
}

jint android_os_Process_killProcessGroup(JNIEnv* env, jobject clazz, jint uid, jint pid)
{
    return killProcessGroup(uid, pid, SIGKILL);
}

void android_os_Process_removeAllProcessGroups(JNIEnv* env, jobject clazz)
{
    return removeAllProcessGroups();
}

static jint android_os_Process_nativePidFdOpen(JNIEnv* env, jobject, jint pid, jint flags) {
    int fd = pidfd_open(pid, flags);
    if (fd < 0) {
        jniThrowErrnoException(env, "nativePidFdOpen", errno);
        return -1;
    }
    return fd;
}

static const JNINativeMethod methods[] = {
        {"getUidForName", "(Ljava/lang/String;)I", (void*)android_os_Process_getUidForName},
        {"getGidForName", "(Ljava/lang/String;)I", (void*)android_os_Process_getGidForName},
        {"setThreadPriority", "(II)V", (void*)android_os_Process_setThreadPriority},
        {"setThreadScheduler", "(III)V", (void*)android_os_Process_setThreadScheduler},
        {"setCanSelfBackground", "(Z)V", (void*)android_os_Process_setCanSelfBackground},
        {"setThreadPriority", "(I)V", (void*)android_os_Process_setCallingThreadPriority},
        {"getThreadPriority", "(I)I", (void*)android_os_Process_getThreadPriority},
        {"getThreadScheduler", "(I)I", (void*)android_os_Process_getThreadScheduler},
        {"setThreadGroup", "(II)V", (void*)android_os_Process_setThreadGroup},
        {"setThreadGroupAndCpuset", "(II)V", (void*)android_os_Process_setThreadGroupAndCpuset},
        {"setProcessGroup", "(II)V", (void*)android_os_Process_setProcessGroup},
        {"getProcessGroup", "(I)I", (void*)android_os_Process_getProcessGroup},
        {"createProcessGroup", "(II)I", (void*)android_os_Process_createProcessGroup},
        {"getExclusiveCores", "()[I", (void*)android_os_Process_getExclusiveCores},
        {"setSwappiness", "(IZ)Z", (void*)android_os_Process_setSwappiness},
        {"setArgV0", "(Ljava/lang/String;)V", (void*)android_os_Process_setArgV0},
        {"setUid", "(I)I", (void*)android_os_Process_setUid},
        {"setGid", "(I)I", (void*)android_os_Process_setGid},
        {"sendSignal", "(II)V", (void*)android_os_Process_sendSignal},
        {"sendSignalQuiet", "(II)V", (void*)android_os_Process_sendSignalQuiet},
        {"setProcessFrozen", "(IIZ)V", (void*)android_os_Process_setProcessFrozen},
        {"getFreeMemory", "()J", (void*)android_os_Process_getFreeMemory},
        {"getTotalMemory", "()J", (void*)android_os_Process_getTotalMemory},
        {"readProcLines", "(Ljava/lang/String;[Ljava/lang/String;[J)V",
         (void*)android_os_Process_readProcLines},
        {"getPids", "(Ljava/lang/String;[I)[I", (void*)android_os_Process_getPids},
        {"readProcFile", "(Ljava/lang/String;[I[Ljava/lang/String;[J[F)Z",
         (void*)android_os_Process_readProcFile},
        {"parseProcLine", "([BII[I[Ljava/lang/String;[J[F)Z",
         (void*)android_os_Process_parseProcLine},
        {"getElapsedCpuTime", "()J", (void*)android_os_Process_getElapsedCpuTime},
        {"getPss", "(I)J", (void*)android_os_Process_getPss},
        {"getRss", "(I)[J", (void*)android_os_Process_getRss},
        {"getPidsForCommands", "([Ljava/lang/String;)[I",
         (void*)android_os_Process_getPidsForCommands},
        //{"setApplicationObject", "(Landroid/os/IBinder;)V",
        //(void*)android_os_Process_setApplicationObject},
        {"killProcessGroup", "(II)I", (void*)android_os_Process_killProcessGroup},
        {"removeAllProcessGroups", "()V", (void*)android_os_Process_removeAllProcessGroups},
        {"nativePidFdOpen", "(II)I", (void*)android_os_Process_nativePidFdOpen},
};

int register_android_os_Process(JNIEnv* env)
{
    return RegisterMethodsOrDie(env, "android/os/Process", methods, NELEM(methods));
}
