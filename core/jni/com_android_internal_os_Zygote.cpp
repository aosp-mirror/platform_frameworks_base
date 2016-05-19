/*
 * Copyright (C) 2008 The Android Open Source Project
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

#define LOG_TAG "Zygote"

// sys/mount.h has to come before linux/fs.h due to redefinition of MS_RDONLY, MS_BIND, etc
#include <sys/mount.h>
#include <linux/fs.h>

#include <list>
#include <sstream>
#include <string>

#include <fcntl.h>
#include <grp.h>
#include <inttypes.h>
#include <mntent.h>
#include <paths.h>
#include <signal.h>
#include <stdlib.h>
#include <sys/capability.h>
#include <sys/personality.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cutils/fs.h>
#include <cutils/multiuser.h>
#include <cutils/sched_policy.h>
#include <private/android_filesystem_config.h>
#include <utils/String8.h>
#include <selinux/android.h>
#include <processgroup/processgroup.h>

#include "core_jni_helpers.h"
#include "JNIHelp.h"
#include "ScopedLocalRef.h"
#include "ScopedPrimitiveArray.h"
#include "ScopedUtfChars.h"
#include "fd_utils-inl.h"

#include "nativebridge/native_bridge.h"

namespace {

using android::String8;

static pid_t gSystemServerPid = 0;

static const char kZygoteClassName[] = "com/android/internal/os/Zygote";
static jclass gZygoteClass;
static jmethodID gCallPostForkChildHooks;

// Must match values in com.android.internal.os.Zygote.
enum MountExternalKind {
  MOUNT_EXTERNAL_NONE = 0,
  MOUNT_EXTERNAL_DEFAULT = 1,
  MOUNT_EXTERNAL_READ = 2,
  MOUNT_EXTERNAL_WRITE = 3,
};

static void RuntimeAbort(JNIEnv* env, int line, const char* msg) {
  std::ostringstream oss;
  oss << __FILE__ << ":" << line << ": " << msg;
  env->FatalError(oss.str().c_str());
}

// This signal handler is for zygote mode, since the zygote must reap its children
static void SigChldHandler(int /*signal_number*/) {
  pid_t pid;
  int status;

  // It's necessary to save and restore the errno during this function.
  // Since errno is stored per thread, changing it here modifies the errno
  // on the thread on which this signal handler executes. If a signal occurs
  // between a call and an errno check, it's possible to get the errno set
  // here.
  // See b/23572286 for extra information.
  int saved_errno = errno;

  while ((pid = waitpid(-1, &status, WNOHANG)) > 0) {
     // Log process-death status that we care about.  In general it is
     // not safe to call LOG(...) from a signal handler because of
     // possible reentrancy.  However, we know a priori that the
     // current implementation of LOG() is safe to call from a SIGCHLD
     // handler in the zygote process.  If the LOG() implementation
     // changes its locking strategy or its use of syscalls within the
     // lazy-init critical section, its use here may become unsafe.
    if (WIFEXITED(status)) {
      if (WEXITSTATUS(status)) {
        ALOGI("Process %d exited cleanly (%d)", pid, WEXITSTATUS(status));
      }
    } else if (WIFSIGNALED(status)) {
      if (WTERMSIG(status) != SIGKILL) {
        ALOGI("Process %d exited due to signal (%d)", pid, WTERMSIG(status));
      }
      if (WCOREDUMP(status)) {
        ALOGI("Process %d dumped core.", pid);
      }
    }

    // If the just-crashed process is the system_server, bring down zygote
    // so that it is restarted by init and system server will be restarted
    // from there.
    if (pid == gSystemServerPid) {
      ALOGE("Exit zygote because system server (%d) has terminated", pid);
      kill(getpid(), SIGKILL);
    }
  }

  // Note that we shouldn't consider ECHILD an error because
  // the secondary zygote might have no children left to wait for.
  if (pid < 0 && errno != ECHILD) {
    ALOGW("Zygote SIGCHLD error in waitpid: %s", strerror(errno));
  }

  errno = saved_errno;
}

// Configures the SIGCHLD handler for the zygote process. This is configured
// very late, because earlier in the runtime we may fork() and exec()
// other processes, and we want to waitpid() for those rather than
// have them be harvested immediately.
//
// This ends up being called repeatedly before each fork(), but there's
// no real harm in that.
static void SetSigChldHandler() {
  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_handler = SigChldHandler;

  int err = sigaction(SIGCHLD, &sa, NULL);
  if (err < 0) {
    ALOGW("Error setting SIGCHLD handler: %s", strerror(errno));
  }
}

// Resets nice priority for zygote process. Zygote priority can be set
// to high value during boot phase to speed it up. We want to ensure
// zygote is running at normal priority before childs are forked from it.
//
// This ends up being called repeatedly before each fork(), but there's
// no real harm in that.
static void ResetNicePriority(JNIEnv* env) {
  errno = 0;
  int prio = getpriority(PRIO_PROCESS, 0);
  if (prio == -1 && errno != 0) {
    ALOGW("getpriority failed: %s\n", strerror(errno));
  }
  if (prio != 0 && setpriority(PRIO_PROCESS, 0, 0) != 0) {
    ALOGE("setpriority(%d, 0, 0) failed: %s", PRIO_PROCESS, strerror(errno));
    RuntimeAbort(env, __LINE__, "setpriority failed");
  }
}

// Sets the SIGCHLD handler back to default behavior in zygote children.
static void UnsetSigChldHandler() {
  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_handler = SIG_DFL;

  int err = sigaction(SIGCHLD, &sa, NULL);
  if (err < 0) {
    ALOGW("Error unsetting SIGCHLD handler: %s", strerror(errno));
  }
}

// Calls POSIX setgroups() using the int[] object as an argument.
// A NULL argument is tolerated.
static void SetGids(JNIEnv* env, jintArray javaGids) {
  if (javaGids == NULL) {
    return;
  }

  ScopedIntArrayRO gids(env, javaGids);
  if (gids.get() == NULL) {
    RuntimeAbort(env, __LINE__, "Getting gids int array failed");
  }
  int rc = setgroups(gids.size(), reinterpret_cast<const gid_t*>(&gids[0]));
  if (rc == -1) {
    std::ostringstream oss;
    oss << "setgroups failed: " << strerror(errno) << ", gids.size=" << gids.size();
    RuntimeAbort(env, __LINE__, oss.str().c_str());
  }
}

// Sets the resource limits via setrlimit(2) for the values in the
// two-dimensional array of integers that's passed in. The second dimension
// contains a tuple of length 3: (resource, rlim_cur, rlim_max). NULL is
// treated as an empty array.
static void SetRLimits(JNIEnv* env, jobjectArray javaRlimits) {
  if (javaRlimits == NULL) {
    return;
  }

  rlimit rlim;
  memset(&rlim, 0, sizeof(rlim));

  for (int i = 0; i < env->GetArrayLength(javaRlimits); ++i) {
    ScopedLocalRef<jobject> javaRlimitObject(env, env->GetObjectArrayElement(javaRlimits, i));
    ScopedIntArrayRO javaRlimit(env, reinterpret_cast<jintArray>(javaRlimitObject.get()));
    if (javaRlimit.size() != 3) {
      RuntimeAbort(env, __LINE__, "rlimits array must have a second dimension of size 3");
    }

    rlim.rlim_cur = javaRlimit[1];
    rlim.rlim_max = javaRlimit[2];

    int rc = setrlimit(javaRlimit[0], &rlim);
    if (rc == -1) {
      ALOGE("setrlimit(%d, {%ld, %ld}) failed", javaRlimit[0], rlim.rlim_cur,
            rlim.rlim_max);
      RuntimeAbort(env, __LINE__, "setrlimit failed");
    }
  }
}

// The debug malloc library needs to know whether it's the zygote or a child.
extern "C" int gMallocLeakZygoteChild;

static void EnableKeepCapabilities(JNIEnv* env) {
  int rc = prctl(PR_SET_KEEPCAPS, 1, 0, 0, 0);
  if (rc == -1) {
    RuntimeAbort(env, __LINE__, "prctl(PR_SET_KEEPCAPS) failed");
  }
}

static void DropCapabilitiesBoundingSet(JNIEnv* env) {
  for (int i = 0; prctl(PR_CAPBSET_READ, i, 0, 0, 0) >= 0; i++) {
    int rc = prctl(PR_CAPBSET_DROP, i, 0, 0, 0);
    if (rc == -1) {
      if (errno == EINVAL) {
        ALOGE("prctl(PR_CAPBSET_DROP) failed with EINVAL. Please verify "
              "your kernel is compiled with file capabilities support");
      } else {
        RuntimeAbort(env, __LINE__, "prctl(PR_CAPBSET_DROP) failed");
      }
    }
  }
}

static void SetCapabilities(JNIEnv* env, int64_t permitted, int64_t effective) {
  __user_cap_header_struct capheader;
  memset(&capheader, 0, sizeof(capheader));
  capheader.version = _LINUX_CAPABILITY_VERSION_3;
  capheader.pid = 0;

  __user_cap_data_struct capdata[2];
  memset(&capdata, 0, sizeof(capdata));
  capdata[0].effective = effective;
  capdata[1].effective = effective >> 32;
  capdata[0].permitted = permitted;
  capdata[1].permitted = permitted >> 32;

  if (capset(&capheader, &capdata[0]) == -1) {
    ALOGE("capset(%" PRId64 ", %" PRId64 ") failed", permitted, effective);
    RuntimeAbort(env, __LINE__, "capset failed");
  }
}

static void SetSchedulerPolicy(JNIEnv* env) {
  errno = -set_sched_policy(0, SP_DEFAULT);
  if (errno != 0) {
    ALOGE("set_sched_policy(0, SP_DEFAULT) failed");
    RuntimeAbort(env, __LINE__, "set_sched_policy(0, SP_DEFAULT) failed");
  }
}

static int UnmountTree(const char* path) {
    size_t path_len = strlen(path);

    FILE* fp = setmntent("/proc/mounts", "r");
    if (fp == NULL) {
        ALOGE("Error opening /proc/mounts: %s", strerror(errno));
        return -errno;
    }

    // Some volumes can be stacked on each other, so force unmount in
    // reverse order to give us the best chance of success.
    std::list<std::string> toUnmount;
    mntent* mentry;
    while ((mentry = getmntent(fp)) != NULL) {
        if (strncmp(mentry->mnt_dir, path, path_len) == 0) {
            toUnmount.push_front(std::string(mentry->mnt_dir));
        }
    }
    endmntent(fp);

    for (auto path : toUnmount) {
        if (umount2(path.c_str(), MNT_DETACH)) {
            ALOGW("Failed to unmount %s: %s", path.c_str(), strerror(errno));
        }
    }
    return 0;
}

// Create a private mount namespace and bind mount appropriate emulated
// storage for the given user.
static bool MountEmulatedStorage(uid_t uid, jint mount_mode,
        bool force_mount_namespace) {
    // See storage config details at http://source.android.com/tech/storage/

    // Create a second private mount namespace for our process
    if (unshare(CLONE_NEWNS) == -1) {
        ALOGW("Failed to unshare(): %s", strerror(errno));
        return false;
    }

    String8 storageSource;
    if (mount_mode == MOUNT_EXTERNAL_DEFAULT) {
        storageSource = "/mnt/runtime/default";
    } else if (mount_mode == MOUNT_EXTERNAL_READ) {
        storageSource = "/mnt/runtime/read";
    } else if (mount_mode == MOUNT_EXTERNAL_WRITE) {
        storageSource = "/mnt/runtime/write";
    } else {
        // Sane default of no storage visible
        return true;
    }
    if (TEMP_FAILURE_RETRY(mount(storageSource.string(), "/storage",
            NULL, MS_BIND | MS_REC | MS_SLAVE, NULL)) == -1) {
        ALOGW("Failed to mount %s to /storage: %s", storageSource.string(), strerror(errno));
        return false;
    }

    // Mount user-specific symlink helper into place
    userid_t user_id = multiuser_get_user_id(uid);
    const String8 userSource(String8::format("/mnt/user/%d", user_id));
    if (fs_prepare_dir(userSource.string(), 0751, 0, 0) == -1) {
        return false;
    }
    if (TEMP_FAILURE_RETRY(mount(userSource.string(), "/storage/self",
            NULL, MS_BIND, NULL)) == -1) {
        ALOGW("Failed to mount %s to /storage/self: %s", userSource.string(), strerror(errno));
        return false;
    }

    return true;
}

static bool NeedsNoRandomizeWorkaround() {
#if !defined(__arm__)
    return false;
#else
    int major;
    int minor;
    struct utsname uts;
    if (uname(&uts) == -1) {
        return false;
    }

    if (sscanf(uts.release, "%d.%d", &major, &minor) != 2) {
        return false;
    }

    // Kernels before 3.4.* need the workaround.
    return (major < 3) || ((major == 3) && (minor < 4));
#endif
}

// Utility to close down the Zygote socket file descriptors while
// the child is still running as root with Zygote's privileges.  Each
// descriptor (if any) is closed via dup2(), replacing it with a valid
// (open) descriptor to /dev/null.

static void DetachDescriptors(JNIEnv* env, jintArray fdsToClose) {
  if (!fdsToClose) {
    return;
  }
  jsize count = env->GetArrayLength(fdsToClose);
  ScopedIntArrayRO ar(env, fdsToClose);
  if (ar.get() == NULL) {
      RuntimeAbort(env, __LINE__, "Bad fd array");
  }
  jsize i;
  int devnull;
  for (i = 0; i < count; i++) {
    devnull = open("/dev/null", O_RDWR);
    if (devnull < 0) {
      ALOGE("Failed to open /dev/null: %s", strerror(errno));
      RuntimeAbort(env, __LINE__, "Failed to open /dev/null");
      continue;
    }
    ALOGV("Switching descriptor %d to /dev/null: %s", ar[i], strerror(errno));
    if (dup2(devnull, ar[i]) < 0) {
      ALOGE("Failed dup2() on descriptor %d: %s", ar[i], strerror(errno));
      RuntimeAbort(env, __LINE__, "Failed dup2()");
    }
    close(devnull);
  }
}

void SetThreadName(const char* thread_name) {
  bool hasAt = false;
  bool hasDot = false;
  const char* s = thread_name;
  while (*s) {
    if (*s == '.') {
      hasDot = true;
    } else if (*s == '@') {
      hasAt = true;
    }
    s++;
  }
  const int len = s - thread_name;
  if (len < 15 || hasAt || !hasDot) {
    s = thread_name;
  } else {
    s = thread_name + len - 15;
  }
  // pthread_setname_np fails rather than truncating long strings.
  char buf[16];       // MAX_TASK_COMM_LEN=16 is hard-coded into bionic
  strlcpy(buf, s, sizeof(buf)-1);
  errno = pthread_setname_np(pthread_self(), buf);
  if (errno != 0) {
    ALOGW("Unable to set the name of current thread to '%s': %s", buf, strerror(errno));
  }
}

#ifdef ENABLE_SCHED_BOOST
static void SetForkLoad(bool boost) {
  // set scheduler knob to boost forked processes
  pid_t currentPid = getpid();
  // fits at most "/proc/XXXXXXX/sched_init_task_load\0"
  char schedPath[35];
  snprintf(schedPath, sizeof(schedPath), "/proc/%u/sched_init_task_load", currentPid);
  int schedBoostFile = open(schedPath, O_WRONLY);
  if (schedBoostFile < 0) {
    ALOGW("Unable to set zygote scheduler boost");
    return;
  }
  if (boost) {
    write(schedBoostFile, "100\0", 4);
  } else {
    write(schedBoostFile, "0\0", 2);
  }
  close(schedBoostFile);
}
#endif

// The list of open zygote file descriptors.
static FileDescriptorTable* gOpenFdTable = NULL;

// Utility routine to fork zygote and specialize the child process.
static pid_t ForkAndSpecializeCommon(JNIEnv* env, uid_t uid, gid_t gid, jintArray javaGids,
                                     jint debug_flags, jobjectArray javaRlimits,
                                     jlong permittedCapabilities, jlong effectiveCapabilities,
                                     jint mount_external,
                                     jstring java_se_info, jstring java_se_name,
                                     bool is_system_server, jintArray fdsToClose,
                                     jstring instructionSet, jstring dataDir) {
  SetSigChldHandler();

#ifdef ENABLE_SCHED_BOOST
  SetForkLoad(true);
#endif

  sigset_t sigchld;
  sigemptyset(&sigchld);
  sigaddset(&sigchld, SIGCHLD);

  // Temporarily block SIGCHLD during forks. The SIGCHLD handler might
  // log, which would result in the logging FDs we close being reopened.
  // This would cause failures because the FDs are not whitelisted.
  //
  // Note that the zygote process is single threaded at this point.
  if (sigprocmask(SIG_BLOCK, &sigchld, nullptr) == -1) {
    ALOGE("sigprocmask(SIG_SETMASK, { SIGCHLD }) failed: %s", strerror(errno));
    RuntimeAbort(env, __LINE__, "Call to sigprocmask(SIG_BLOCK, { SIGCHLD }) failed.");
  }

  // Close any logging related FDs before we start evaluating the list of
  // file descriptors.
  __android_log_close();

  // If this is the first fork for this zygote, create the open FD table.
  // If it isn't, we just need to check whether the list of open files has
  // changed (and it shouldn't in the normal case).
  if (gOpenFdTable == NULL) {
    gOpenFdTable = FileDescriptorTable::Create();
    if (gOpenFdTable == NULL) {
      RuntimeAbort(env, __LINE__, "Unable to construct file descriptor table.");
    }
  } else if (!gOpenFdTable->Restat()) {
    RuntimeAbort(env, __LINE__, "Unable to restat file descriptor table.");
  }

  ResetNicePriority(env);

  pid_t pid = fork();

  if (pid == 0) {
    // The child process.
    gMallocLeakZygoteChild = 1;

    // Clean up any descriptors which must be closed immediately
    DetachDescriptors(env, fdsToClose);

    // Re-open all remaining open file descriptors so that they aren't shared
    // with the zygote across a fork.
    if (!gOpenFdTable->ReopenOrDetach()) {
      RuntimeAbort(env, __LINE__, "Unable to reopen whitelisted descriptors.");
    }

    if (sigprocmask(SIG_UNBLOCK, &sigchld, nullptr) == -1) {
      ALOGE("sigprocmask(SIG_SETMASK, { SIGCHLD }) failed: %s", strerror(errno));
      RuntimeAbort(env, __LINE__, "Call to sigprocmask(SIG_UNBLOCK, { SIGCHLD }) failed.");
    }

    // Keep capabilities across UID change, unless we're staying root.
    if (uid != 0) {
      EnableKeepCapabilities(env);
    }

    DropCapabilitiesBoundingSet(env);

    bool use_native_bridge = !is_system_server && (instructionSet != NULL)
        && android::NativeBridgeAvailable();
    if (use_native_bridge) {
      ScopedUtfChars isa_string(env, instructionSet);
      use_native_bridge = android::NeedsNativeBridge(isa_string.c_str());
    }
    if (use_native_bridge && dataDir == NULL) {
      // dataDir should never be null if we need to use a native bridge.
      // In general, dataDir will never be null for normal applications. It can only happen in
      // special cases (for isolated processes which are not associated with any app). These are
      // launched by the framework and should not be emulated anyway.
      use_native_bridge = false;
      ALOGW("Native bridge will not be used because dataDir == NULL.");
    }

    if (!MountEmulatedStorage(uid, mount_external, use_native_bridge)) {
      ALOGW("Failed to mount emulated storage: %s", strerror(errno));
      if (errno == ENOTCONN || errno == EROFS) {
        // When device is actively encrypting, we get ENOTCONN here
        // since FUSE was mounted before the framework restarted.
        // When encrypted device is booting, we get EROFS since
        // FUSE hasn't been created yet by init.
        // In either case, continue without external storage.
      } else {
        RuntimeAbort(env, __LINE__, "Cannot continue without emulated storage");
      }
    }

    if (!is_system_server) {
        int rc = createProcessGroup(uid, getpid());
        if (rc != 0) {
            if (rc == -EROFS) {
                ALOGW("createProcessGroup failed, kernel missing CONFIG_CGROUP_CPUACCT?");
            } else {
                ALOGE("createProcessGroup(%d, %d) failed: %s", uid, pid, strerror(-rc));
            }
        }
    }

    SetGids(env, javaGids);

    SetRLimits(env, javaRlimits);

    if (use_native_bridge) {
      ScopedUtfChars isa_string(env, instructionSet);
      ScopedUtfChars data_dir(env, dataDir);
      android::PreInitializeNativeBridge(data_dir.c_str(), isa_string.c_str());
    }

    int rc = setresgid(gid, gid, gid);
    if (rc == -1) {
      ALOGE("setresgid(%d) failed: %s", gid, strerror(errno));
      RuntimeAbort(env, __LINE__, "setresgid failed");
    }

    rc = setresuid(uid, uid, uid);
    if (rc == -1) {
      ALOGE("setresuid(%d) failed: %s", uid, strerror(errno));
      RuntimeAbort(env, __LINE__, "setresuid failed");
    }

    if (NeedsNoRandomizeWorkaround()) {
        // Work around ARM kernel ASLR lossage (http://b/5817320).
        int old_personality = personality(0xffffffff);
        int new_personality = personality(old_personality | ADDR_NO_RANDOMIZE);
        if (new_personality == -1) {
            ALOGW("personality(%d) failed: %s", new_personality, strerror(errno));
        }
    }

    SetCapabilities(env, permittedCapabilities, effectiveCapabilities);

    SetSchedulerPolicy(env);

    const char* se_info_c_str = NULL;
    ScopedUtfChars* se_info = NULL;
    if (java_se_info != NULL) {
        se_info = new ScopedUtfChars(env, java_se_info);
        se_info_c_str = se_info->c_str();
        if (se_info_c_str == NULL) {
          RuntimeAbort(env, __LINE__, "se_info_c_str == NULL");
        }
    }
    const char* se_name_c_str = NULL;
    ScopedUtfChars* se_name = NULL;
    if (java_se_name != NULL) {
        se_name = new ScopedUtfChars(env, java_se_name);
        se_name_c_str = se_name->c_str();
        if (se_name_c_str == NULL) {
          RuntimeAbort(env, __LINE__, "se_name_c_str == NULL");
        }
    }
    rc = selinux_android_setcontext(uid, is_system_server, se_info_c_str, se_name_c_str);
    if (rc == -1) {
      ALOGE("selinux_android_setcontext(%d, %d, \"%s\", \"%s\") failed", uid,
            is_system_server, se_info_c_str, se_name_c_str);
      RuntimeAbort(env, __LINE__, "selinux_android_setcontext failed");
    }

    // Make it easier to debug audit logs by setting the main thread's name to the
    // nice name rather than "app_process".
    if (se_info_c_str == NULL && is_system_server) {
      se_name_c_str = "system_server";
    }
    if (se_info_c_str != NULL) {
      SetThreadName(se_name_c_str);
    }

    delete se_info;
    delete se_name;

    UnsetSigChldHandler();

    env->CallStaticVoidMethod(gZygoteClass, gCallPostForkChildHooks, debug_flags,
                              is_system_server, instructionSet);
    if (env->ExceptionCheck()) {
      RuntimeAbort(env, __LINE__, "Error calling post fork hooks.");
    }
  } else if (pid > 0) {
    // the parent process

#ifdef ENABLE_SCHED_BOOST
    // unset scheduler knob
    SetForkLoad(false);
#endif

    // We blocked SIGCHLD prior to a fork, we unblock it here.
    if (sigprocmask(SIG_UNBLOCK, &sigchld, nullptr) == -1) {
      ALOGE("sigprocmask(SIG_SETMASK, { SIGCHLD }) failed: %s", strerror(errno));
      RuntimeAbort(env, __LINE__, "Call to sigprocmask(SIG_UNBLOCK, { SIGCHLD }) failed.");
    }
  }
  return pid;
}
}  // anonymous namespace

namespace android {

static jint com_android_internal_os_Zygote_nativeForkAndSpecialize(
        JNIEnv* env, jclass, jint uid, jint gid, jintArray gids,
        jint debug_flags, jobjectArray rlimits,
        jint mount_external, jstring se_info, jstring se_name,
        jintArray fdsToClose, jstring instructionSet, jstring appDataDir) {
    jlong capabilities = 0;

    // Grant CAP_WAKE_ALARM to the Bluetooth process.
    // Additionally, allow bluetooth to open packet sockets so it can start the DHCP client.
    // TODO: consider making such functionality an RPC to netd.
    if (multiuser_get_app_id(uid) == AID_BLUETOOTH) {
      capabilities |= (1LL << CAP_WAKE_ALARM);
      capabilities |= (1LL << CAP_NET_RAW);
      capabilities |= (1LL << CAP_NET_BIND_SERVICE);
    }

    // Grant CAP_BLOCK_SUSPEND to processes that belong to GID "wakelock"
    bool gid_wakelock_found = false;
    if (gid == AID_WAKELOCK) {
      gid_wakelock_found = true;
    } else if (gids != NULL) {
      jsize gids_num = env->GetArrayLength(gids);
      ScopedIntArrayRO ar(env, gids);
      if (ar.get() == NULL) {
        RuntimeAbort(env, __LINE__, "Bad gids array");
      }
      for (int i = 0; i < gids_num; i++) {
        if (ar[i] == AID_WAKELOCK) {
          gid_wakelock_found = true;
          break;
        }
      }
    }
    if (gid_wakelock_found) {
      capabilities |= (1LL << CAP_BLOCK_SUSPEND);
    }

    return ForkAndSpecializeCommon(env, uid, gid, gids, debug_flags,
            rlimits, capabilities, capabilities, mount_external, se_info,
            se_name, false, fdsToClose, instructionSet, appDataDir);
}

static jint com_android_internal_os_Zygote_nativeForkSystemServer(
        JNIEnv* env, jclass, uid_t uid, gid_t gid, jintArray gids,
        jint debug_flags, jobjectArray rlimits, jlong permittedCapabilities,
        jlong effectiveCapabilities) {
  pid_t pid = ForkAndSpecializeCommon(env, uid, gid, gids,
                                      debug_flags, rlimits,
                                      permittedCapabilities, effectiveCapabilities,
                                      MOUNT_EXTERNAL_DEFAULT, NULL, NULL, true, NULL,
                                      NULL, NULL);
  if (pid > 0) {
      // The zygote process checks whether the child process has died or not.
      ALOGI("System server process %d has been created", pid);
      gSystemServerPid = pid;
      // There is a slight window that the system server process has crashed
      // but it went unnoticed because we haven't published its pid yet. So
      // we recheck here just to make sure that all is well.
      int status;
      if (waitpid(pid, &status, WNOHANG) == pid) {
          ALOGE("System server process %d has died. Restarting Zygote!", pid);
          RuntimeAbort(env, __LINE__, "System server process has died. Restarting Zygote!");
      }
  }
  return pid;
}

static void com_android_internal_os_Zygote_nativeUnmountStorageOnInit(JNIEnv* env, jclass) {
    // Zygote process unmount root storage space initially before every child processes are forked.
    // Every forked child processes (include SystemServer) only mount their own root storage space
    // And no need unmount storage operation in MountEmulatedStorage method.
    // Zygote process does not utilize root storage spaces and unshared its mount namespace from the ART.

    UnmountTree("/storage");
    return;
}

static const JNINativeMethod gMethods[] = {
    { "nativeForkAndSpecialize",
      "(II[II[[IILjava/lang/String;Ljava/lang/String;[ILjava/lang/String;Ljava/lang/String;)I",
      (void *) com_android_internal_os_Zygote_nativeForkAndSpecialize },
    { "nativeForkSystemServer", "(II[II[[IJJ)I",
      (void *) com_android_internal_os_Zygote_nativeForkSystemServer },
    { "nativeUnmountStorageOnInit", "()V",
      (void *) com_android_internal_os_Zygote_nativeUnmountStorageOnInit }
};

int register_com_android_internal_os_Zygote(JNIEnv* env) {
  gZygoteClass = MakeGlobalRefOrDie(env, FindClassOrDie(env, kZygoteClassName));
  gCallPostForkChildHooks = GetStaticMethodIDOrDie(env, gZygoteClass, "callPostForkChildHooks",
                                                   "(IZLjava/lang/String;)V");

  return RegisterMethodsOrDie(env, "com/android/internal/os/Zygote", gMethods, NELEM(gMethods));
}
}  // namespace android
