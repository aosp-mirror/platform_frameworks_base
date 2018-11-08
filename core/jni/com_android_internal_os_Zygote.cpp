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

#include <functional>
#include <list>
#include <optional>
#include <sstream>
#include <string>

#include <android/fdsan.h>
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
#include <stats_event_list.h>
#include <processgroup/processgroup.h>

#include "core_jni_helpers.h"
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>
#include "fd_utils.h"

#include "nativebridge/native_bridge.h"

namespace {

using namespace std::placeholders;

using android::String8;
using android::base::StringAppendF;
using android::base::StringPrintf;
using android::base::WriteStringToFile;
using android::base::GetBoolProperty;

#define CREATE_ERROR(...) StringPrintf("%s:%d: ", __FILE__, __LINE__). \
                              append(StringPrintf(__VA_ARGS__))

static pid_t gSystemServerPid = 0;

static const char kIsolatedStorage[] = "persist.sys.isolated_storage";
static const char kZygoteClassName[] = "com/android/internal/os/Zygote";
static jclass gZygoteClass;
static jmethodID gCallPostForkSystemServerHooks;
static jmethodID gCallPostForkChildHooks;

static bool g_is_security_enforced = true;

// Must match values in com.android.internal.os.Zygote.
enum MountExternalKind {
  MOUNT_EXTERNAL_NONE = 0,
  MOUNT_EXTERNAL_DEFAULT = 1,
  MOUNT_EXTERNAL_READ = 2,
  MOUNT_EXTERNAL_WRITE = 3,
  MOUNT_EXTERNAL_FULL = 4,
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

static bool createPkgSandbox(uid_t uid, const std::string& package_name, std::string* error_msg) {
    // Create /mnt/user/0/package/<package-name>
    userid_t user_id = multiuser_get_user_id(uid);
    std::string pkg_sandbox_dir = StringPrintf("/mnt/user/%d", user_id);
    if (fs_prepare_dir(pkg_sandbox_dir.c_str(), 0751, AID_ROOT, AID_ROOT) != 0) {
        *error_msg = CREATE_ERROR("fs_prepare_dir failed on %s", pkg_sandbox_dir.c_str());
        return false;
    }
    StringAppendF(&pkg_sandbox_dir, "/package");
    if (fs_prepare_dir(pkg_sandbox_dir.c_str(), 0700, AID_ROOT, AID_ROOT) != 0) {
        *error_msg = CREATE_ERROR("fs_prepare_dir failed on %s", pkg_sandbox_dir.c_str());
        return false;
    }
    StringAppendF(&pkg_sandbox_dir, "/%s", package_name.c_str());
    if (fs_prepare_dir(pkg_sandbox_dir.c_str(), 0755, uid, uid) != 0) {
        *error_msg = CREATE_ERROR("fs_prepare_dir failed on %s", pkg_sandbox_dir.c_str());
        return false;
    }
    return true;
}

static bool mountPkgSpecificDir(const std::string& mntSourceRoot,
        const std::string& mntTargetRoot, const std::string& packageName,
        const char* dirName, std::string* error_msg) {
    std::string mntSourceDir = StringPrintf("%s/Android/%s/%s",
            mntSourceRoot.c_str(), dirName, packageName.c_str());
    std::string mntTargetDir = StringPrintf("%s/Android/%s/%s",
            mntTargetRoot.c_str(), dirName, packageName.c_str());
    if (TEMP_FAILURE_RETRY(mount(mntSourceDir.c_str(), mntTargetDir.c_str(),
            nullptr, MS_BIND | MS_REC, nullptr)) == -1) {
        *error_msg = CREATE_ERROR("Failed to mount %s to %s: %s",
                mntSourceDir.c_str(), mntTargetDir.c_str(), strerror(errno));
        return false;
    }
    if (TEMP_FAILURE_RETRY(mount(nullptr, mntTargetDir.c_str(),
            nullptr, MS_SLAVE | MS_REC, nullptr)) == -1) {
        *error_msg = CREATE_ERROR("Failed to set MS_SLAVE for %s", mntTargetDir.c_str());
        return false;
    }
    return true;
}

static bool preparePkgSpecificDirs(const std::vector<std::string>& packageNames,
        const std::vector<std::string>& volumeLabels, userid_t userId, std::string* error_msg) {
    for (auto& label : volumeLabels) {
        std::string mntSource = StringPrintf("/mnt/runtime/write/%s", label.c_str());
        std::string mntTarget = StringPrintf("/storage/%s", label.c_str());
        if (label == "emulated") {
            StringAppendF(&mntSource, "/%d", userId);
            StringAppendF(&mntTarget, "/%d", userId);
        }
        for (auto& package : packageNames) {
            mountPkgSpecificDir(mntSource, mntTarget, package, "data", error_msg);
            mountPkgSpecificDir(mntSource, mntTarget, package, "media", error_msg);
            mountPkgSpecificDir(mntSource, mntTarget, package, "obb", error_msg);
        }
    }
    return true;
}

// Create a private mount namespace and bind mount appropriate emulated
// storage for the given user.
static bool MountEmulatedStorage(uid_t uid, jint mount_mode,
        bool force_mount_namespace, std::string* error_msg, const std::string& package_name,
        const std::vector<std::string>& packages_for_uid,
        const std::vector<std::string>& visible_vol_ids) {
    // See storage config details at http://source.android.com/tech/storage/

    String8 storageSource;
    if (mount_mode == MOUNT_EXTERNAL_DEFAULT) {
        storageSource = "/mnt/runtime/default";
    } else if (mount_mode == MOUNT_EXTERNAL_READ) {
        storageSource = "/mnt/runtime/read";
    } else if (mount_mode == MOUNT_EXTERNAL_WRITE) {
        storageSource = "/mnt/runtime/write";
    } else if (mount_mode != MOUNT_EXTERNAL_FULL && !force_mount_namespace) {
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

    if (GetBoolProperty(kIsolatedStorage, false)) {
        if (mount_mode == MOUNT_EXTERNAL_FULL) {
            storageSource = "/mnt/runtime/write";
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
        } else {
            if (package_name.empty()) {
                return true;
            }
            userid_t user_id = multiuser_get_user_id(uid);
            std::string pkgSandboxDir = StringPrintf("/mnt/user/%d/package/%s",
                    user_id, package_name.c_str());
            struct stat sb;
            bool sandboxAlreadyCreated = true;
            if (TEMP_FAILURE_RETRY(lstat(pkgSandboxDir.c_str(), &sb)) == -1) {
                if (errno == ENOENT) {
                    ALOGD("Sandbox not yet created for %s", pkgSandboxDir.c_str());
                    sandboxAlreadyCreated = false;
                    if (!createPkgSandbox(uid, package_name, error_msg)) {
                        return false;
                    }
                } else {
                    ALOGE("Failed to lstat %s", pkgSandboxDir.c_str());
                    return false;
                }
            }
            if (TEMP_FAILURE_RETRY(mount(pkgSandboxDir.c_str(), "/storage",
                    nullptr, MS_BIND | MS_REC | MS_SLAVE, nullptr)) == -1) {
                *error_msg = CREATE_ERROR("Failed to mount %s to /storage: %s",
                        pkgSandboxDir.c_str(), strerror(errno));
                return false;
            }
            // If the sandbox was already created by vold, only then set up the bind mounts for
            // pkg specific directories. Otherwise, leave as is and bind mounts will be taken
            // care of by vold later.
            if (sandboxAlreadyCreated) {
                if (!preparePkgSpecificDirs(packages_for_uid, visible_vol_ids,
                        user_id, error_msg)) {
                    return false;
                }
            }
        }
    } else {
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
                                     jintArray managed_fds,
                                     std::vector<int>* fds,
                                     std::string* error_msg) {
  CHECK(fds != nullptr);
  if (managed_fds != nullptr) {
    ScopedIntArrayRO ar(env, managed_fds);
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

[[noreturn]]
static void ZygoteFailure(JNIEnv* env,
                          const char* process_name,
                          jstring managed_process_name,
                          const std::string& msg) {
  std::unique_ptr<ScopedUtfChars> scoped_managed_process_name_ptr = nullptr;
  if (managed_process_name != nullptr) {
    scoped_managed_process_name_ptr.reset(new ScopedUtfChars(env, managed_process_name));
    if (scoped_managed_process_name_ptr->c_str() != nullptr) {
      process_name = scoped_managed_process_name_ptr->c_str();
    }
  }

  const std::string& error_msg =
      (process_name == nullptr) ? msg : StringPrintf("(%s) %s", process_name, msg.c_str());

  env->FatalError(error_msg.c_str());
  __builtin_unreachable();
}

static std::optional<std::string> ExtractJString(JNIEnv* env,
                                                 const char* process_name,
                                                 jstring managed_process_name,
                                                 jstring managed_string) {
  if (managed_string == nullptr) {
    return std::optional<std::string>();
  } else {
    ScopedUtfChars scoped_string_chars(env, managed_string);

    if (scoped_string_chars.c_str() != nullptr) {
      return std::optional<std::string>(scoped_string_chars.c_str());
    } else {
      ZygoteFailure(env, process_name, managed_process_name, "Failed to extract JString.");
    }
  }
}

// Utility routine to fork a zygote.
static pid_t ForkCommon(JNIEnv* env, bool is_system_server,
                        jintArray managed_fds_to_close, jintArray managed_fds_to_ignore) {
  SetSignalHandlers();

  // Block SIGCHLD prior to fork.
  sigset_t sigchld;
  sigemptyset(&sigchld);
  sigaddset(&sigchld, SIGCHLD);

  // Curry a failure function.
  auto fail_fn = std::bind(ZygoteFailure, env, is_system_server ? "system_server" : "zygote",
                           nullptr, _1);

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
  stats_log_close();

  // If this is the first fork for this zygote, create the open FD table.  If
  // it isn't, we just need to check whether the list of open files has changed
  // (and it shouldn't in the normal case).
  std::string error_msg;
  std::vector<int> fds_to_ignore;
  if (!FillFileDescriptorVector(env, managed_fds_to_ignore, &fds_to_ignore, &error_msg)) {
    fail_fn(error_msg);
  }

  if (gOpenFdTable == nullptr) {
    gOpenFdTable = FileDescriptorTable::Create(fds_to_ignore, &error_msg);
    if (gOpenFdTable == nullptr) {
      fail_fn(error_msg);
    }
  } else if (!gOpenFdTable->Restat(fds_to_ignore, &error_msg)) {
    fail_fn(error_msg);
  }

  android_fdsan_error_level fdsan_error_level = android_fdsan_get_error_level();

  pid_t pid = fork();

  if (pid == 0) {
    // The child process.
    PreApplicationInit();

    // Clean up any descriptors which must be closed immediately
    if (!DetachDescriptors(env, managed_fds_to_close, &error_msg)) {
      fail_fn(error_msg);
    }

    // Re-open all remaining open file descriptors so that they aren't shared
    // with the zygote across a fork.
    if (!gOpenFdTable->ReopenOrDetach(&error_msg)) {
      fail_fn(error_msg);
    }

    // Turn fdsan back on.
    android_fdsan_set_error_level(fdsan_error_level);
  }

  // We blocked SIGCHLD prior to a fork, we unblock it here.
  if (sigprocmask(SIG_UNBLOCK, &sigchld, nullptr) == -1) {
    fail_fn(CREATE_ERROR("sigprocmask(SIG_SETMASK, { SIGCHLD }) failed: %s", strerror(errno)));
  }

  return pid;
}

// Utility routine to specialize a zygote child process.
static void SpecializeCommon(JNIEnv* env, uid_t uid, gid_t gid, jintArray gids,
                             jint runtime_flags, jobjectArray rlimits,
                             jlong permitted_capabilities, jlong effective_capabilities,
                             jint mount_external, jstring managed_se_info,
                             jstring managed_nice_name, bool is_system_server,
                             bool is_child_zygote, jstring managed_instruction_set,
                             jstring managed_app_data_dir, jstring managed_package_name,
                             jobjectArray managed_pacakges_for_uid,
                             jobjectArray managed_visible_vol_ids) {
  auto fail_fn = std::bind(ZygoteFailure, env, is_system_server ? "system_server" : "zygote",
                           managed_nice_name, _1);
  auto extract_fn = std::bind(ExtractJString, env, is_system_server ? "system_server" : "zygote",
                              managed_nice_name, _1);

  auto se_info = extract_fn(managed_se_info);
  auto nice_name = extract_fn(managed_nice_name);
  auto instruction_set = extract_fn(managed_instruction_set);
  auto app_data_dir = extract_fn(managed_app_data_dir);
  auto package_name = extract_fn(managed_package_name);

  std::string error_msg;

  // Keep capabilities across UID change, unless we're staying root.
  if (uid != 0) {
    if (!EnableKeepCapabilities(&error_msg)) {
      fail_fn(error_msg);
    }
  }

  if (!SetInheritable(permitted_capabilities, &error_msg)) {
    fail_fn(error_msg);
  }

  if (!DropCapabilitiesBoundingSet(&error_msg)) {
    fail_fn(error_msg);
  }

  bool use_native_bridge = !is_system_server &&
                           instruction_set.has_value() &&
                           android::NativeBridgeAvailable() &&
                           android::NeedsNativeBridge(instruction_set.value().c_str());

  if (use_native_bridge && !app_data_dir.has_value()) {
    // The app_data_dir variable should never be empty if we need to use a
    // native bridge.  In general, app_data_dir will never be empty for normal
    // applications.  It can only happen in special cases (for isolated
    // processes which are not associated with any app).  These are launched by
    // the framework and should not be emulated anyway.
    use_native_bridge = false;
    ALOGW("Native bridge will not be used because managed_app_data_dir == nullptr.");
  }

  if (!package_name.has_value()) {
    if (is_system_server) {
      package_name.emplace("android");
    } else {
      package_name.emplace("");
    }
  }

  std::vector<std::string> packages_for_uid;
  if (managed_pacakges_for_uid != nullptr) {
    jsize count = env->GetArrayLength(managed_pacakges_for_uid);
    for (jsize package_index = 0; package_index < count; ++package_index) {
      jstring managed_package_for_uid =
          (jstring) env->GetObjectArrayElement(managed_pacakges_for_uid, package_index);

      auto package_for_uid = extract_fn(managed_package_for_uid);
      if (LIKELY(package_for_uid.has_value())) {
        packages_for_uid.emplace_back(std::move(package_for_uid.value()));
      } else {
        fail_fn("Null string found in managed packages_for_uid.");
      }
    }
  }

  std::vector<std::string> visible_vol_ids;
  if (managed_visible_vol_ids != nullptr) {
    jsize count = env->GetArrayLength(managed_visible_vol_ids);
    for (jsize vol_id_index = 0; vol_id_index < count; ++vol_id_index) {
      jstring managed_visible_vol_id =
          (jstring) env->GetObjectArrayElement(managed_visible_vol_ids, vol_id_index);

      auto visible_vol_id = extract_fn(managed_visible_vol_id);
      if (LIKELY(visible_vol_id.has_value())) {
        visible_vol_ids.emplace_back(std::move(visible_vol_id.value()));
      } else {
        fail_fn("Null string found in managed visible_vol_ids.");
      }
    }
  }

  if (!MountEmulatedStorage(uid, mount_external, use_native_bridge, &error_msg,
                            package_name.value(), packages_for_uid, visible_vol_ids)) {
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
    if (rc == -EROFS) {
      ALOGW("createProcessGroup failed, kernel missing CONFIG_CGROUP_CPUACCT?");
    } else if (rc != 0) {
      ALOGE("createProcessGroup(%d, %d) failed: %s", uid, /* pid= */ 0, strerror(-rc));
    }
  }

  if (!SetGids(env, gids, &error_msg)) {
    fail_fn(error_msg);
  }

  if (!SetRLimits(env, rlimits, &error_msg)) {
    fail_fn(error_msg);
  }

  if (use_native_bridge) {
    // Due to the logic behind use_native_bridge we know that both app_data_dir
    // and instruction_set contain values.
    android::PreInitializeNativeBridge(app_data_dir.value().c_str(),
                                       instruction_set.value().c_str());
  }

  if (setresgid(gid, gid, gid) == -1) {
    fail_fn(CREATE_ERROR("setresgid(%d) failed: %s", gid, strerror(errno)));
  }

  // Must be called when the new process still has CAP_SYS_ADMIN, in this case,
  // before changing uid from 0, which clears capabilities.  The other
  // alternative is to call prctl(PR_SET_NO_NEW_PRIVS, 1) afterward, but that
  // breaks SELinux domain transition (see b/71859146).  As the result,
  // privileged syscalls used below still need to be accessible in app process.
  SetUpSeccompFilter(uid);

  if (setresuid(uid, uid, uid) == -1) {
    fail_fn(CREATE_ERROR("setresuid(%d) failed: %s", uid, strerror(errno)));
  }

  // The "dumpable" flag of a process, which controls core dump generation, is
  // overwritten by the value in /proc/sys/fs/suid_dumpable when the effective
  // user or group ID changes. See proc(5) for possible values. In most cases,
  // the value is 0, so core dumps are disabled for zygote children. However,
  // when running in a Chrome OS container, the value is already set to 2,
  // which allows the external crash reporter to collect all core dumps. Since
  // only system crashes are interested, core dump is disabled for app
  // processes. This also ensures compliance with CTS.
  int dumpable = prctl(PR_GET_DUMPABLE);
  if (dumpable == -1) {
    ALOGE("prctl(PR_GET_DUMPABLE) failed: %s", strerror(errno));
    RuntimeAbort(env, __LINE__, "prctl(PR_GET_DUMPABLE) failed");
  }

  if (dumpable == 2 && uid >= AID_APP) {
    if (prctl(PR_SET_DUMPABLE, 0, 0, 0, 0) == -1) {
      ALOGE("prctl(PR_SET_DUMPABLE, 0) failed: %s", strerror(errno));
      RuntimeAbort(env, __LINE__, "prctl(PR_SET_DUMPABLE, 0) failed");
    }
  }

  if (NeedsNoRandomizeWorkaround()) {
    // Work around ARM kernel ASLR lossage (http://b/5817320).
    int old_personality = personality(0xffffffff);
    int new_personality = personality(old_personality | ADDR_NO_RANDOMIZE);
    if (new_personality == -1) {
      ALOGW("personality(%d) failed: %s", new_personality, strerror(errno));
    }
  }

  if (!SetCapabilities(permitted_capabilities, effective_capabilities, permitted_capabilities,
                       &error_msg)) {
    fail_fn(error_msg);
  }

  if (!SetSchedulerPolicy(&error_msg)) {
    fail_fn(error_msg);
  }

  const char* se_info_ptr = se_info.has_value() ? se_info.value().c_str() : nullptr;
  const char* nice_name_ptr = nice_name.has_value() ? nice_name.value().c_str() : nullptr;

  if (selinux_android_setcontext(uid, is_system_server, se_info_ptr, nice_name_ptr) == -1) {
    fail_fn(CREATE_ERROR("selinux_android_setcontext(%d, %d, \"%s\", \"%s\") failed",
                         uid, is_system_server, se_info_ptr, nice_name_ptr));
  }

  // Make it easier to debug audit logs by setting the main thread's name to the
  // nice name rather than "app_process".
  if (nice_name.has_value()) {
    SetThreadName(nice_name.value().c_str());
  } else if (is_system_server) {
    SetThreadName("system_server");
  }

  // Unset the SIGCHLD handler, but keep ignoring SIGHUP (rationale in SetSignalHandlers).
  UnsetChldSignalHandler();

  if (is_system_server) {
    env->CallStaticVoidMethod(gZygoteClass, gCallPostForkSystemServerHooks);
    if (env->ExceptionCheck()) {
      fail_fn("Error calling post fork system server hooks.");
    }
    // TODO(oth): Remove hardcoded label here (b/117874058).
    static const char* kSystemServerLabel = "u:r:system_server:s0";
    if (selinux_android_setcon(kSystemServerLabel) != 0) {
      fail_fn(CREATE_ERROR("selinux_android_setcon(%s)", kSystemServerLabel));
    }
  }

  env->CallStaticVoidMethod(gZygoteClass, gCallPostForkChildHooks, runtime_flags,
                            is_system_server, is_child_zygote, managed_instruction_set);

  if (env->ExceptionCheck()) {
    fail_fn("Error calling post fork hooks.");
  }
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

    return capdata[0].effective | (static_cast<uint64_t>(capdata[1].effective) << 32);
}

static jlong CalculateCapabilities(JNIEnv* env, jint uid, jint gid, jintArray gids,
                                   bool is_child_zygote) {
  jlong capabilities = 0;

  /*
   *  Grant the following capabilities to the Bluetooth user:
   *    - CAP_WAKE_ALARM
   *    - CAP_NET_RAW
   *    - CAP_NET_BIND_SERVICE (for DHCP client functionality)
   *    - CAP_SYS_NICE (for setting RT priority for audio-related threads)
   */

  if (multiuser_get_app_id(uid) == AID_BLUETOOTH) {
    capabilities |= (1LL << CAP_WAKE_ALARM);
    capabilities |= (1LL << CAP_NET_RAW);
    capabilities |= (1LL << CAP_NET_BIND_SERVICE);
    capabilities |= (1LL << CAP_SYS_NICE);
  }

  /*
   * Grant CAP_BLOCK_SUSPEND to processes that belong to GID "wakelock"
   */

  bool gid_wakelock_found = false;
  if (gid == AID_WAKELOCK) {
    gid_wakelock_found = true;
  } else if (gids != nullptr) {
    jsize gids_num = env->GetArrayLength(gids);
    ScopedIntArrayRO native_gid_proxy(env, gids);

    if (native_gid_proxy.get() == nullptr) {
      RuntimeAbort(env, __LINE__, "Bad gids array");
    }

    for (int gid_index = gids_num; --gids_num >= 0;) {
      if (native_gid_proxy[gid_index] == AID_WAKELOCK) {
        gid_wakelock_found = true;
        break;
      }
    }
  }

  if (gid_wakelock_found) {
    capabilities |= (1LL << CAP_BLOCK_SUSPEND);
  }

  /*
   * Grant child Zygote processes the following capabilities:
   *   - CAP_SETUID (change UID of child processes)
   *   - CAP_SETGID (change GID of child processes)
   *   - CAP_SETPCAP (change capabilities of child processes)
   */

  if (is_child_zygote) {
    capabilities |= (1LL << CAP_SETUID);
    capabilities |= (1LL << CAP_SETGID);
    capabilities |= (1LL << CAP_SETPCAP);
  }

  /*
   * Containers run without some capabilities, so drop any caps that are not
   * available.
   */

  return capabilities & GetEffectiveCapabilityMask(env);
}
}  // anonymous namespace

namespace android {

static void com_android_internal_os_Zygote_nativeSecurityInit(JNIEnv*, jclass) {
  // security_getenforce is not allowed on app process. Initialize and cache
  // the value before zygote forks.
  g_is_security_enforced = security_getenforce();
}

static void com_android_internal_os_Zygote_nativePreApplicationInit(JNIEnv*, jclass) {
  PreApplicationInit();
}

static jint com_android_internal_os_Zygote_nativeForkAndSpecialize(
        JNIEnv* env, jclass, jint uid, jint gid, jintArray gids,
        jint runtime_flags, jobjectArray rlimits,
        jint mount_external, jstring se_info, jstring nice_name,
        jintArray fds_to_close, jintArray fds_to_ignore, jboolean is_child_zygote,
        jstring instruction_set, jstring app_data_dir, jstring package_name,
        jobjectArray packages_for_uid, jobjectArray visible_vol_ids) {
    jlong capabilities = CalculateCapabilities(env, uid, gid, gids, is_child_zygote);

    pid_t pid = ForkCommon(env, false, fds_to_close, fds_to_ignore);
    if (pid == 0) {
      SpecializeCommon(env, uid, gid, gids, runtime_flags, rlimits,
                       capabilities, capabilities,
                       mount_external, se_info, nice_name, false,
                       is_child_zygote == JNI_TRUE, instruction_set, app_data_dir,
                       package_name, packages_for_uid, visible_vol_ids);
    }
    return pid;
}

static jint com_android_internal_os_Zygote_nativeForkSystemServer(
        JNIEnv* env, jclass, uid_t uid, gid_t gid, jintArray gids,
        jint runtime_flags, jobjectArray rlimits, jlong permitted_capabilities,
        jlong effective_capabilities) {
  pid_t pid = ForkCommon(env, true,
                         /* managed_fds_to_close= */ nullptr,
                         /* managed_fds_to_ignore= */ nullptr);
  if (pid == 0) {
      SpecializeCommon(env, uid, gid, gids, runtime_flags, rlimits,
                       permitted_capabilities, effective_capabilities,
                       MOUNT_EXTERNAL_DEFAULT, nullptr, nullptr, true,
                       false, nullptr, nullptr, nullptr, nullptr, nullptr);
  } else if (pid > 0) {
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
        RuntimeAbort(env, __LINE__, "path_cstr == nullptr");
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
      "(II[II[[IILjava/lang/String;Ljava/lang/String;[I[IZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)I",
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
  gCallPostForkSystemServerHooks = GetStaticMethodIDOrDie(env, gZygoteClass,
                                                          "callPostForkSystemServerHooks",
                                                          "()V");
  gCallPostForkChildHooks = GetStaticMethodIDOrDie(env, gZygoteClass, "callPostForkChildHooks",
                                                   "(IZZLjava/lang/String;)V");

  return RegisterMethodsOrDie(env, "com/android/internal/os/Zygote", gMethods, NELEM(gMethods));
}
}  // namespace android
