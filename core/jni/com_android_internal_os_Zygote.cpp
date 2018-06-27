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
#include <malloc.h>
#include <mntent.h>
#include <paths.h>
#include <signal.h>
#include <stdlib.h>
#include <sys/capability.h>
#include <sys/cdefs.h>
#include <sys/personality.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <unistd.h>

#include "android-base/logging.h"
#include <android-base/properties.h>
#include <android-base/file.h>
#include <android-base/stringprintf.h>
#include <cutils/fs.h>
#include <cutils/multiuser.h>
#include <cutils/sched_policy.h>
#include <private/android_filesystem_config.h>
#include <utils/String8.h>
#include <selinux/android.h>
#include <seccomp_policy.h>
#include <processgroup/processgroup.h>

#include "core_jni_helpers.h"
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>
#include "fd_utils.h"

#include "nativebridge/native_bridge.h"

namespace {

using android::String8;
using android::base::StringPrintf;
using android::base::WriteStringToFile;
using android::base::GetBoolProperty;

#define CREATE_ERROR(...) StringPrintf("%s:%d: ", __FILE__, __LINE__). \
                              append(StringPrintf(__VA_ARGS__))

static pid_t gSystemServerPid = 0;

static const char kZygoteClassName[] = "com/android/internal/os/Zygote";
static jclass gZygoteClass;
static jmethodID gCallPostForkChildHooks;

static bool g_is_security_enforced = true;

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
      ALOGI("Process %d exited cleanly (%d)", pid, WEXITSTATUS(status));
    } else if (WIFSIGNALED(status)) {
      ALOGI("Process %d exited due to signal (%d)", pid, WTERMSIG(status));
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

// Configures the SIGCHLD/SIGHUP handlers for the zygote process. This is
// configured very late, because earlier in the runtime we may fork() and
// exec() other processes, and we want to waitpid() for those rather than
// have them be harvested immediately.
//
// Ignore SIGHUP because all processes forked by the zygote are in the same
// process group as the zygote and we don't want to be notified if we become
// an orphaned group and have one or more stopped processes. This is not a
// theoretical concern :
// - we can become an orphaned group if one of our direct descendants forks
//   and is subsequently killed before its children.
// - crash_dump routinely STOPs the process it's tracing.
//
// See issues b/71965619 and b/25567761 for further details.
//
// This ends up being called repeatedly before each fork(), but there's
// no real harm in that.
static void SetSignalHandlers() {
  struct sigaction sig_chld = {};
  sig_chld.sa_handler = SigChldHandler;

  if (sigaction(SIGCHLD, &sig_chld, NULL) < 0) {
    ALOGW("Error setting SIGCHLD handler: %s", strerror(errno));
  }

  struct sigaction sig_hup = {};
  sig_hup.sa_handler = SIG_IGN;
  if (sigaction(SIGHUP, &sig_hup, NULL) < 0) {
    ALOGW("Error setting SIGHUP handler: %s", strerror(errno));
  }
}

// Sets the SIGCHLD handler back to default behavior in zygote children.
static void UnsetChldSignalHandler() {
  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_handler = SIG_DFL;

  if (sigaction(SIGCHLD, &sa, NULL) < 0) {
    ALOGW("Error unsetting SIGCHLD handler: %s", strerror(errno));
  }
}

// Calls POSIX setgroups() using the int[] object as an argument.
// A NULL argument is tolerated.
static bool SetGids(JNIEnv* env, jintArray javaGids, std::string* error_msg) {
  if (javaGids == NULL) {
    return true;
  }

  ScopedIntArrayRO gids(env, javaGids);
  if (gids.get() == NULL) {
    *error_msg = CREATE_ERROR("Getting gids int array failed");
    return false;
  }
  int rc = setgroups(gids.size(), reinterpret_cast<const gid_t*>(&gids[0]));
  if (rc == -1) {
    *error_msg = CREATE_ERROR("setgroups failed: %s, gids.size=%zu", strerror(errno), gids.size());
    return false;
  }

  return true;
}

// Sets the resource limits via setrlimit(2) for the values in the
// two-dimensional array of integers that's passed in. The second dimension
// contains a tuple of length 3: (resource, rlim_cur, rlim_max). NULL is
// treated as an empty array.
static bool SetRLimits(JNIEnv* env, jobjectArray javaRlimits, std::string* error_msg) {
  if (javaRlimits == NULL) {
    return true;
  }

  rlimit rlim;
  memset(&rlim, 0, sizeof(rlim));

  for (int i = 0; i < env->GetArrayLength(javaRlimits); ++i) {
    ScopedLocalRef<jobject> javaRlimitObject(env, env->GetObjectArrayElement(javaRlimits, i));
    ScopedIntArrayRO javaRlimit(env, reinterpret_cast<jintArray>(javaRlimitObject.get()));
    if (javaRlimit.size() != 3) {
      *error_msg = CREATE_ERROR("rlimits array must have a second dimension of size 3");
      return false;
    }

    rlim.rlim_cur = javaRlimit[1];
    rlim.rlim_max = javaRlimit[2];

    int rc = setrlimit(javaRlimit[0], &rlim);
    if (rc == -1) {
      *error_msg = CREATE_ERROR("setrlimit(%d, {%ld, %ld}) failed", javaRlimit[0], rlim.rlim_cur,
            rlim.rlim_max);
      return false;
    }
  }

  return true;
}

// The debug malloc library needs to know whether it's the zygote or a child.
extern "C" int gMallocLeakZygoteChild;

static void PreApplicationInit() {
  // The child process sets this to indicate it's not the zygote.
  gMallocLeakZygoteChild = 1;

  // Set the jemalloc decay time to 1.
  mallopt(M_DECAY_TIME, 1);
}

static void SetUpSeccompFilter(uid_t uid) {
  if (!g_is_security_enforced) {
    ALOGI("seccomp disabled by setenforce 0");
    return;
  }

  // Apply system or app filter based on uid.
  if (uid >= AID_APP_START) {
    set_app_seccomp_filter();
  } else {
    set_system_seccomp_filter();
  }
}

static bool EnableKeepCapabilities(std::string* error_msg) {
  int rc = prctl(PR_SET_KEEPCAPS, 1, 0, 0, 0);
  if (rc == -1) {
    *error_msg = CREATE_ERROR("prctl(PR_SET_KEEPCAPS) failed: %s", strerror(errno));
    return false;
  }
  return true;
}

static bool DropCapabilitiesBoundingSet(std::string* error_msg) {
  for (int i = 0; prctl(PR_CAPBSET_READ, i, 0, 0, 0) >= 0; i++) {
    int rc = prctl(PR_CAPBSET_DROP, i, 0, 0, 0);
    if (rc == -1) {
      if (errno == EINVAL) {
        ALOGE("prctl(PR_CAPBSET_DROP) failed with EINVAL. Please verify "
              "your kernel is compiled with file capabilities support");
      } else {
        *error_msg = CREATE_ERROR("prctl(PR_CAPBSET_DROP, %d) failed: %s", i, strerror(errno));
        return false;
      }
    }
  }
  return true;
}

static bool SetInheritable(uint64_t inheritable, std::string* error_msg) {
  __user_cap_header_struct capheader;
  memset(&capheader, 0, sizeof(capheader));
  capheader.version = _LINUX_CAPABILITY_VERSION_3;
  capheader.pid = 0;

  __user_cap_data_struct capdata[2];
  if (capget(&capheader, &capdata[0]) == -1) {
    *error_msg = CREATE_ERROR("capget failed: %s", strerror(errno));
    return false;
  }

  capdata[0].inheritable = inheritable;
  capdata[1].inheritable = inheritable >> 32;

  if (capset(&capheader, &capdata[0]) == -1) {
    *error_msg = CREATE_ERROR("capset(inh=%" PRIx64 ") failed: %s", inheritable, strerror(errno));
    return false;
  }

  return true;
}

static bool SetCapabilities(uint64_t permitted, uint64_t effective, uint64_t inheritable,
                            std::string* error_msg) {
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
  capdata[0].inheritable = inheritable;
  capdata[1].inheritable = inheritable >> 32;

  if (capset(&capheader, &capdata[0]) == -1) {
    *error_msg = CREATE_ERROR("capset(perm=%" PRIx64 ", eff=%" PRIx64 ", inh=%" PRIx64 ") "
                              "failed: %s", permitted, effective, inheritable, strerror(errno));
    return false;
  }
  return true;
}

static bool SetSchedulerPolicy(std::string* error_msg) {
  errno = -set_sched_policy(0, SP_DEFAULT);
  if (errno != 0) {
    *error_msg = CREATE_ERROR("set_sched_policy(0, SP_DEFAULT) failed: %s", strerror(errno));
    return false;
  }
  return true;
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
        bool force_mount_namespace, std::string* error_msg) {
    // See storage config details at http://source.android.com/tech/storage/

    String8 storageSource;
    if (mount_mode == MOUNT_EXTERNAL_DEFAULT) {
        storageSource = "/mnt/runtime/default";
    } else if (mount_mode == MOUNT_EXTERNAL_READ) {
        storageSource = "/mnt/runtime/read";
    } else if (mount_mode == MOUNT_EXTERNAL_WRITE) {
        storageSource = "/mnt/runtime/write";
    } else if (!force_mount_namespace) {
        // Sane default of no storage visible
        return true;
    }

    // Create a second private mount namespace for our process
    if (unshare(CLONE_NEWNS) == -1) {
        *error_msg = CREATE_ERROR("Failed to unshare(): %s", strerror(errno));
        return false;
    }

    // Handle force_mount_namespace with MOUNT_EXTERNAL_NONE.
    if (mount_mode == MOUNT_EXTERNAL_NONE) {
        return true;
    }

    if (TEMP_FAILURE_RETRY(mount(storageSource.string(), "/storage",
            NULL, MS_BIND | MS_REC | MS_SLAVE, NULL)) == -1) {
        *error_msg = CREATE_ERROR("Failed to mount %s to /storage: %s",
                                  storageSource.string(),
                                  strerror(errno));
        return false;
    }

    // Mount user-specific symlink helper into place
    userid_t user_id = multiuser_get_user_id(uid);
    const String8 userSource(String8::format("/mnt/user/%d", user_id));
    if (fs_prepare_dir(userSource.string(), 0751, 0, 0) == -1) {
        *error_msg = CREATE_ERROR("fs_prepare_dir failed on %s", userSource.string());
        return false;
    }
    if (TEMP_FAILURE_RETRY(mount(userSource.string(), "/storage/self",
            NULL, MS_BIND, NULL)) == -1) {
        *error_msg = CREATE_ERROR("Failed to mount %s to /storage/self: %s",
                                  userSource.string(),
                                  strerror(errno));
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

static bool DetachDescriptors(JNIEnv* env, jintArray fdsToClose, std::string* error_msg) {
  if (!fdsToClose) {
    return true;
  }
  jsize count = env->GetArrayLength(fdsToClose);
  ScopedIntArrayRO ar(env, fdsToClose);
  if (ar.get() == NULL) {
    *error_msg = "Bad fd array";
    return false;
  }
  jsize i;
  int devnull;
  for (i = 0; i < count; i++) {
    devnull = open("/dev/null", O_RDWR);
    if (devnull < 0) {
      *error_msg = std::string("Failed to open /dev/null: ").append(strerror(errno));
      return false;
    }
    ALOGV("Switching descriptor %d to /dev/null: %s", ar[i], strerror(errno));
    if (dup2(devnull, ar[i]) < 0) {
      *error_msg = StringPrintf("Failed dup2() on descriptor %d: %s", ar[i], strerror(errno));
      return false;
    }
    close(devnull);
  }
  return true;
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
  // Update base::logging default tag.
  android::base::SetDefaultTag(buf);
}

// The list of open zygote file descriptors.
static FileDescriptorTable* gOpenFdTable = NULL;

static bool FillFileDescriptorVector(JNIEnv* env,
                                     jintArray java_fds,
                                     std::vector<int>* fds,
                                     std::string* error_msg) {
  CHECK(fds != nullptr);
  if (java_fds != nullptr) {
    ScopedIntArrayRO ar(env, java_fds);
    if (ar.get() == nullptr) {
      *error_msg = "Bad fd array";
      return false;
    }
    fds->reserve(ar.size());
    for (size_t i = 0; i < ar.size(); ++i) {
      fds->push_back(ar[i]);
    }
  }
  return true;
}

// Utility routine to fork zygote and specialize the child process.
static pid_t ForkAndSpecializeCommon(JNIEnv* env, uid_t uid, gid_t gid, jintArray javaGids,
                                     jint runtime_flags, jobjectArray javaRlimits,
                                     jlong permittedCapabilities, jlong effectiveCapabilities,
                                     jint mount_external,
                                     jstring java_se_info, jstring java_se_name,
                                     bool is_system_server, jintArray fdsToClose,
                                     jintArray fdsToIgnore, bool is_child_zygote,
                                     jstring instructionSet, jstring dataDir) {
  SetSignalHandlers();

  sigset_t sigchld;
  sigemptyset(&sigchld);
  sigaddset(&sigchld, SIGCHLD);

  auto fail_fn = [env, java_se_name, is_system_server](const std::string& msg)
      __attribute__ ((noreturn)) {
    const char* se_name_c_str = nullptr;
    std::unique_ptr<ScopedUtfChars> se_name;
    if (java_se_name != nullptr) {
      se_name.reset(new ScopedUtfChars(env, java_se_name));
      se_name_c_str = se_name->c_str();
    }
    if (se_name_c_str == nullptr && is_system_server) {
      se_name_c_str = "system_server";
    }
    const std::string& error_msg = (se_name_c_str == nullptr)
        ? msg
        : StringPrintf("(%s) %s", se_name_c_str, msg.c_str());
    env->FatalError(error_msg.c_str());
    __builtin_unreachable();
  };

  // Temporarily block SIGCHLD during forks. The SIGCHLD handler might
  // log, which would result in the logging FDs we close being reopened.
  // This would cause failures because the FDs are not whitelisted.
  //
  // Note that the zygote process is single threaded at this point.
  if (sigprocmask(SIG_BLOCK, &sigchld, nullptr) == -1) {
    fail_fn(CREATE_ERROR("sigprocmask(SIG_SETMASK, { SIGCHLD }) failed: %s", strerror(errno)));
  }

  // Close any logging related FDs before we start evaluating the list of
  // file descriptors.
  __android_log_close();

  std::string error_msg;

  // If this is the first fork for this zygote, create the open FD table.
  // If it isn't, we just need to check whether the list of open files has
  // changed (and it shouldn't in the normal case).
  std::vector<int> fds_to_ignore;
  if (!FillFileDescriptorVector(env, fdsToIgnore, &fds_to_ignore, &error_msg)) {
    fail_fn(error_msg);
  }
  if (gOpenFdTable == NULL) {
    gOpenFdTable = FileDescriptorTable::Create(fds_to_ignore, &error_msg);
    if (gOpenFdTable == NULL) {
      fail_fn(error_msg);
    }
  } else if (!gOpenFdTable->Restat(fds_to_ignore, &error_msg)) {
    fail_fn(error_msg);
  }

  pid_t pid = fork();

  if (pid == 0) {
    PreApplicationInit();

    // Clean up any descriptors which must be closed immediately
    if (!DetachDescriptors(env, fdsToClose, &error_msg)) {
      fail_fn(error_msg);
    }

    // Re-open all remaining open file descriptors so that they aren't shared
    // with the zygote across a fork.
    if (!gOpenFdTable->ReopenOrDetach(&error_msg)) {
      fail_fn(error_msg);
    }

    if (sigprocmask(SIG_UNBLOCK, &sigchld, nullptr) == -1) {
      fail_fn(CREATE_ERROR("sigprocmask(SIG_SETMASK, { SIGCHLD }) failed: %s", strerror(errno)));
    }

    // Keep capabilities across UID change, unless we're staying root.
    if (uid != 0) {
      if (!EnableKeepCapabilities(&error_msg)) {
        fail_fn(error_msg);
      }
    }

    if (!SetInheritable(permittedCapabilities, &error_msg)) {
      fail_fn(error_msg);
    }
    if (!DropCapabilitiesBoundingSet(&error_msg)) {
      fail_fn(error_msg);
    }

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

    if (!MountEmulatedStorage(uid, mount_external, use_native_bridge, &error_msg)) {
      ALOGW("Failed to mount emulated storage: %s (%s)", error_msg.c_str(), strerror(errno));
      if (errno == ENOTCONN || errno == EROFS) {
        // When device is actively encrypting, we get ENOTCONN here
        // since FUSE was mounted before the framework restarted.
        // When encrypted device is booting, we get EROFS since
        // FUSE hasn't been created yet by init.
        // In either case, continue without external storage.
      } else {
        fail_fn(error_msg);
      }
    }

    // If this zygote isn't root, it won't be able to create a process group,
    // since the directory is owned by root.
    if (!is_system_server && getuid() == 0) {
        int rc = createProcessGroup(uid, getpid());
        if (rc != 0) {
            if (rc == -EROFS) {
                ALOGW("createProcessGroup failed, kernel missing CONFIG_CGROUP_CPUACCT?");
            } else {
                ALOGE("createProcessGroup(%d, %d) failed: %s", uid, pid, strerror(-rc));
            }
        }
    }

    std::string error_msg;
    if (!SetGids(env, javaGids, &error_msg)) {
      fail_fn(error_msg);
    }

    if (!SetRLimits(env, javaRlimits, &error_msg)) {
      fail_fn(error_msg);
    }

    if (use_native_bridge) {
      ScopedUtfChars isa_string(env, instructionSet);
      ScopedUtfChars data_dir(env, dataDir);
      android::PreInitializeNativeBridge(data_dir.c_str(), isa_string.c_str());
    }

    int rc = setresgid(gid, gid, gid);
    if (rc == -1) {
      fail_fn(CREATE_ERROR("setresgid(%d) failed: %s", gid, strerror(errno)));
    }

    // Must be called when the new process still has CAP_SYS_ADMIN, in this case, before changing
    // uid from 0, which clears capabilities.  The other alternative is to call
    // prctl(PR_SET_NO_NEW_PRIVS, 1) afterward, but that breaks SELinux domain transition (see
    // b/71859146).  As the result, privileged syscalls used below still need to be accessible in
    // app process.
    SetUpSeccompFilter(uid);

    rc = setresuid(uid, uid, uid);
    if (rc == -1) {
      fail_fn(CREATE_ERROR("setresuid(%d) failed: %s", uid, strerror(errno)));
    }

    if (NeedsNoRandomizeWorkaround()) {
        // Work around ARM kernel ASLR lossage (http://b/5817320).
        int old_personality = personality(0xffffffff);
        int new_personality = personality(old_personality | ADDR_NO_RANDOMIZE);
        if (new_personality == -1) {
            ALOGW("personality(%d) failed: %s", new_personality, strerror(errno));
        }
    }

    if (!SetCapabilities(permittedCapabilities, effectiveCapabilities, permittedCapabilities,
                         &error_msg)) {
      fail_fn(error_msg);
    }

    if (!SetSchedulerPolicy(&error_msg)) {
      fail_fn(error_msg);
    }

    const char* se_info_c_str = NULL;
    ScopedUtfChars* se_info = NULL;
    if (java_se_info != NULL) {
        se_info = new ScopedUtfChars(env, java_se_info);
        se_info_c_str = se_info->c_str();
        if (se_info_c_str == NULL) {
          fail_fn("se_info_c_str == NULL");
        }
    }
    const char* se_name_c_str = NULL;
    ScopedUtfChars* se_name = NULL;
    if (java_se_name != NULL) {
        se_name = new ScopedUtfChars(env, java_se_name);
        se_name_c_str = se_name->c_str();
        if (se_name_c_str == NULL) {
          fail_fn("se_name_c_str == NULL");
        }
    }
    rc = selinux_android_setcontext(uid, is_system_server, se_info_c_str, se_name_c_str);
    if (rc == -1) {
      fail_fn(CREATE_ERROR("selinux_android_setcontext(%d, %d, \"%s\", \"%s\") failed", uid,
            is_system_server, se_info_c_str, se_name_c_str));
    }

    // Make it easier to debug audit logs by setting the main thread's name to the
    // nice name rather than "app_process".
    if (se_name_c_str == NULL && is_system_server) {
      se_name_c_str = "system_server";
    }
    if (se_name_c_str != NULL) {
      SetThreadName(se_name_c_str);
    }

    delete se_info;
    delete se_name;

    // Unset the SIGCHLD handler, but keep ignoring SIGHUP (rationale in SetSignalHandlers).
    UnsetChldSignalHandler();

    env->CallStaticVoidMethod(gZygoteClass, gCallPostForkChildHooks, runtime_flags,
                              is_system_server, is_child_zygote, instructionSet);
    if (env->ExceptionCheck()) {
      fail_fn("Error calling post fork hooks.");
    }
  } else if (pid > 0) {
    // the parent process

    // We blocked SIGCHLD prior to a fork, we unblock it here.
    if (sigprocmask(SIG_UNBLOCK, &sigchld, nullptr) == -1) {
      fail_fn(CREATE_ERROR("sigprocmask(SIG_SETMASK, { SIGCHLD }) failed: %s", strerror(errno)));
    }
  }
  return pid;
}

static uint64_t GetEffectiveCapabilityMask(JNIEnv* env) {
    __user_cap_header_struct capheader;
    memset(&capheader, 0, sizeof(capheader));
    capheader.version = _LINUX_CAPABILITY_VERSION_3;
    capheader.pid = 0;

    __user_cap_data_struct capdata[2];
    if (capget(&capheader, &capdata[0]) == -1) {
        ALOGE("capget failed: %s", strerror(errno));
        RuntimeAbort(env, __LINE__, "capget failed");
    }

    return capdata[0].effective |
           (static_cast<uint64_t>(capdata[1].effective) << 32);
}
}  // anonymous namespace

namespace android {

static void com_android_internal_os_Zygote_nativeSecurityInit(JNIEnv*, jclass) {
  // security_getenforce is not allowed on app process. Initialize and cache the value before
  // zygote forks.
  g_is_security_enforced = security_getenforce();
}

static void com_android_internal_os_Zygote_nativePreApplicationInit(JNIEnv*, jclass) {
  PreApplicationInit();
}

static jint com_android_internal_os_Zygote_nativeForkAndSpecialize(
        JNIEnv* env, jclass, jint uid, jint gid, jintArray gids,
        jint runtime_flags, jobjectArray rlimits,
        jint mount_external, jstring se_info, jstring se_name,
        jintArray fdsToClose, jintArray fdsToIgnore, jboolean is_child_zygote,
        jstring instructionSet, jstring appDataDir) {
    jlong capabilities = 0;

    // Grant CAP_WAKE_ALARM to the Bluetooth process.
    // Additionally, allow bluetooth to open packet sockets so it can start the DHCP client.
    // Grant CAP_SYS_NICE to allow Bluetooth to set RT priority for
    // audio-related threads.
    // TODO: consider making such functionality an RPC to netd.
    if (multiuser_get_app_id(uid) == AID_BLUETOOTH) {
      capabilities |= (1LL << CAP_WAKE_ALARM);
      capabilities |= (1LL << CAP_NET_RAW);
      capabilities |= (1LL << CAP_NET_BIND_SERVICE);
      capabilities |= (1LL << CAP_SYS_NICE);
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

    // If forking a child zygote process, that zygote will need to be able to change
    // the UID and GID of processes it forks, as well as drop those capabilities.
    if (is_child_zygote) {
      capabilities |= (1LL << CAP_SETUID);
      capabilities |= (1LL << CAP_SETGID);
      capabilities |= (1LL << CAP_SETPCAP);
    }

    // Containers run without some capabilities, so drop any caps that are not
    // available.
    capabilities &= GetEffectiveCapabilityMask(env);

    return ForkAndSpecializeCommon(env, uid, gid, gids, runtime_flags,
            rlimits, capabilities, capabilities, mount_external, se_info,
            se_name, false, fdsToClose, fdsToIgnore, is_child_zygote == JNI_TRUE,
            instructionSet, appDataDir);
}

static jint com_android_internal_os_Zygote_nativeForkSystemServer(
        JNIEnv* env, jclass, uid_t uid, gid_t gid, jintArray gids,
        jint runtime_flags, jobjectArray rlimits, jlong permittedCapabilities,
        jlong effectiveCapabilities) {
  pid_t pid = ForkAndSpecializeCommon(env, uid, gid, gids,
                                      runtime_flags, rlimits,
                                      permittedCapabilities, effectiveCapabilities,
                                      MOUNT_EXTERNAL_DEFAULT, NULL, NULL, true, NULL,
                                      NULL, false, NULL, NULL);
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

      bool low_ram_device = GetBoolProperty("ro.config.low_ram", false);
      bool per_app_memcg = GetBoolProperty("ro.config.per_app_memcg", low_ram_device);
      if (per_app_memcg) {
          // Assign system_server to the correct memory cgroup.
          // Not all devices mount /dev/memcg so check for the file first
          // to avoid unnecessarily printing errors and denials in the logs.
          if (!access("/dev/memcg/system/tasks", F_OK) &&
                !WriteStringToFile(StringPrintf("%d", pid), "/dev/memcg/system/tasks")) {
              ALOGE("couldn't write %d to /dev/memcg/system/tasks", pid);
          }
      }
  }
  return pid;
}

static void com_android_internal_os_Zygote_nativeAllowFileAcrossFork(
        JNIEnv* env, jclass, jstring path) {
    ScopedUtfChars path_native(env, path);
    const char* path_cstr = path_native.c_str();
    if (!path_cstr) {
        RuntimeAbort(env, __LINE__, "path_cstr == NULL");
    }
    FileDescriptorWhitelist::Get()->Allow(path_cstr);
}

static void com_android_internal_os_Zygote_nativeUnmountStorageOnInit(JNIEnv* env, jclass) {
    // Zygote process unmount root storage space initially before every child processes are forked.
    // Every forked child processes (include SystemServer) only mount their own root storage space
    // and no need unmount storage operation in MountEmulatedStorage method.
    // Zygote process does not utilize root storage spaces and unshares its mount namespace below.

    // See storage config details at http://source.android.com/tech/storage/
    // Create private mount namespace shared by all children
    if (unshare(CLONE_NEWNS) == -1) {
        RuntimeAbort(env, __LINE__, "Failed to unshare()");
        return;
    }

    // Mark rootfs as being a slave so that changes from default
    // namespace only flow into our children.
    if (mount("rootfs", "/", nullptr, (MS_SLAVE | MS_REC), nullptr) == -1) {
        RuntimeAbort(env, __LINE__, "Failed to mount() rootfs as MS_SLAVE");
        return;
    }

    // Create a staging tmpfs that is shared by our children; they will
    // bind mount storage into their respective private namespaces, which
    // are isolated from each other.
    const char* target_base = getenv("EMULATED_STORAGE_TARGET");
    if (target_base != nullptr) {
#define STRINGIFY_UID(x) __STRING(x)
        if (mount("tmpfs", target_base, "tmpfs", MS_NOSUID | MS_NODEV,
                  "uid=0,gid=" STRINGIFY_UID(AID_SDCARD_R) ",mode=0751") == -1) {
            ALOGE("Failed to mount tmpfs to %s", target_base);
            RuntimeAbort(env, __LINE__, "Failed to mount tmpfs");
            return;
        }
#undef STRINGIFY_UID
    }

    UnmountTree("/storage");
}

static const JNINativeMethod gMethods[] = {
    { "nativeSecurityInit", "()V",
      (void *) com_android_internal_os_Zygote_nativeSecurityInit },
    { "nativeForkAndSpecialize",
      "(II[II[[IILjava/lang/String;Ljava/lang/String;[I[IZLjava/lang/String;Ljava/lang/String;)I",
      (void *) com_android_internal_os_Zygote_nativeForkAndSpecialize },
    { "nativeForkSystemServer", "(II[II[[IJJ)I",
      (void *) com_android_internal_os_Zygote_nativeForkSystemServer },
    { "nativeAllowFileAcrossFork", "(Ljava/lang/String;)V",
      (void *) com_android_internal_os_Zygote_nativeAllowFileAcrossFork },
    { "nativeUnmountStorageOnInit", "()V",
      (void *) com_android_internal_os_Zygote_nativeUnmountStorageOnInit },
    { "nativePreApplicationInit", "()V",
      (void *) com_android_internal_os_Zygote_nativePreApplicationInit }
};

int register_com_android_internal_os_Zygote(JNIEnv* env) {
  gZygoteClass = MakeGlobalRefOrDie(env, FindClassOrDie(env, kZygoteClassName));
  gCallPostForkChildHooks = GetStaticMethodIDOrDie(env, gZygoteClass, "callPostForkChildHooks",
                                                   "(IZZLjava/lang/String;)V");

  return RegisterMethodsOrDie(env, "com/android/internal/os/Zygote", gMethods, NELEM(gMethods));
}
}  // namespace android
