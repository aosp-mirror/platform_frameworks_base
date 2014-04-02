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

#include "android_runtime/AndroidRuntime.h"

// sys/mount.h has to come before linux/fs.h due to redefinition of MS_RDONLY, MS_BIND, etc
#include <sys/mount.h>
#include <linux/fs.h>

#include <grp.h>
#include <paths.h>
#include <signal.h>
#include <stdlib.h>
#include <sys/resource.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>
#include <fcntl.h>

#include "cutils/fs.h"
#include "cutils/multiuser.h"
#include "cutils/sched_policy.h"
#include "utils/String8.h"
#include "JNIHelp.h"
#include "ScopedLocalRef.h"
#include "ScopedPrimitiveArray.h"
#include "ScopedUtfChars.h"

#if defined(HAVE_PRCTL)
#include <sys/prctl.h>
#endif

#include <selinux/android.h>

#if defined(__linux__)
#include <sys/personality.h>
#include <sys/utsname.h>
#if defined(HAVE_ANDROID_OS)
#include <sys/capability.h>
#endif
#endif

namespace {

using android::String8;

static pid_t gSystemServerPid = 0;

static const char kZygoteClassName[] = "com/android/internal/os/Zygote";
static jclass gZygoteClass;
static jmethodID gCallPostForkChildHooks;

// Must match values in com.android.internal.os.Zygote.
enum MountExternalKind {
  MOUNT_EXTERNAL_NONE = 0,
  MOUNT_EXTERNAL_SINGLEUSER = 1,
  MOUNT_EXTERNAL_MULTIUSER = 2,
  MOUNT_EXTERNAL_MULTIUSER_ALL = 3,
};

static void RuntimeAbort(JNIEnv* env) {
  env->FatalError("RuntimeAbort");
}

// This signal handler is for zygote mode, since the zygote must reap its children
static void SigChldHandler(int /*signal_number*/) {
  pid_t pid;
  int status;

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
      } else if (false) {
        ALOGI("Process %d exited cleanly (%d)", pid, WEXITSTATUS(status));
      }
    } else if (WIFSIGNALED(status)) {
      if (WTERMSIG(status) != SIGKILL) {
        ALOGI("Process %d exited cleanly (%d)", pid, WTERMSIG(status));
      } else if (false) {
        ALOGI("Process %d exited cleanly (%d)", pid, WTERMSIG(status));
      }
#ifdef WCOREDUMP
      if (WCOREDUMP(status)) {
        ALOGI("Process %d dumped core.", pid);
      }
#endif /* ifdef WCOREDUMP */
    }

    // If the just-crashed process is the system_server, bring down zygote
    // so that it is restarted by init and system server will be restarted
    // from there.
    if (pid == gSystemServerPid) {
      ALOGE("Exit zygote because system server (%d) has terminated");
      kill(getpid(), SIGKILL);
    }
  }

  if (pid < 0) {
    ALOGW("Zygote SIGCHLD error in waitpid: %d", errno);
  }
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
    ALOGW("Error setting SIGCHLD handler: %d", errno);
  }
}

// Sets the SIGCHLD handler back to default behavior in zygote children.
static void UnsetSigChldHandler() {
  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_handler = SIG_DFL;

  int err = sigaction(SIGCHLD, &sa, NULL);
  if (err < 0) {
    ALOGW("Error unsetting SIGCHLD handler: %d", errno);
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
      RuntimeAbort(env);
  }
  int rc = setgroups(gids.size(), reinterpret_cast<const gid_t*>(&gids[0]));
  if (rc == -1) {
    ALOGE("setgroups failed");
    RuntimeAbort(env);
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
      ALOGE("rlimits array must have a second dimension of size 3");
      RuntimeAbort(env);
    }

    rlim.rlim_cur = javaRlimit[1];
    rlim.rlim_max = javaRlimit[2];

    int rc = setrlimit(javaRlimit[0], &rlim);
    if (rc == -1) {
      ALOGE("setrlimit(%d, {%d, %d}) failed", javaRlimit[0], rlim.rlim_cur, rlim.rlim_max);
      RuntimeAbort(env);
    }
  }
}

#if defined(HAVE_ANDROID_OS)

// The debug malloc library needs to know whether it's the zygote or a child.
extern "C" int gMallocLeakZygoteChild;

static void EnableKeepCapabilities(JNIEnv* env) {
  int rc = prctl(PR_SET_KEEPCAPS, 1, 0, 0, 0);
  if (rc == -1) {
    ALOGE("prctl(PR_SET_KEEPCAPS) failed");
    RuntimeAbort(env);
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
        ALOGE("prctl(PR_CAPBSET_DROP) failed");
        RuntimeAbort(env);
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
    ALOGE("capset(%lld, %lld) failed", permitted, effective);
    RuntimeAbort(env);
  }
}

static void SetSchedulerPolicy(JNIEnv* env) {
  errno = -set_sched_policy(0, SP_DEFAULT);
  if (errno != 0) {
    ALOGE("set_sched_policy(0, SP_DEFAULT) failed");
    RuntimeAbort(env);
  }
}

#else

static int gMallocLeakZygoteChild = 0;

static void EnableKeepCapabilities(JNIEnv*) {}
static void DropCapabilitiesBoundingSet(JNIEnv*) {}
static void SetCapabilities(JNIEnv*, int64_t, int64_t) {}
static void SetSchedulerPolicy(JNIEnv*) {}

#endif

// Create a private mount namespace and bind mount appropriate emulated
// storage for the given user.
static bool MountEmulatedStorage(uid_t uid, jint mount_mode) {
  if (mount_mode == MOUNT_EXTERNAL_NONE) {
    return true;
  }

  // See storage config details at http://source.android.com/tech/storage/
  userid_t user_id = multiuser_get_user_id(uid);

  // Create a second private mount namespace for our process
  if (unshare(CLONE_NEWNS) == -1) {
      ALOGW("Failed to unshare(): %d", errno);
      return false;
  }

  // Create bind mounts to expose external storage
  if (mount_mode == MOUNT_EXTERNAL_MULTIUSER || mount_mode == MOUNT_EXTERNAL_MULTIUSER_ALL) {
    // These paths must already be created by init.rc
    const char* source = getenv("EMULATED_STORAGE_SOURCE");
    const char* target = getenv("EMULATED_STORAGE_TARGET");
    const char* legacy = getenv("EXTERNAL_STORAGE");
    if (source == NULL || target == NULL || legacy == NULL) {
      ALOGW("Storage environment undefined; unable to provide external storage");
      return false;
    }

    // Prepare source paths

    // /mnt/shell/emulated/0
    const String8 source_user(String8::format("%s/%d", source, user_id));
    // /storage/emulated/0
    const String8 target_user(String8::format("%s/%d", target, user_id));

    if (fs_prepare_dir(source_user.string(), 0000, 0, 0) == -1
        || fs_prepare_dir(target_user.string(), 0000, 0, 0) == -1) {
      return false;
    }

    if (mount_mode == MOUNT_EXTERNAL_MULTIUSER_ALL) {
      // Mount entire external storage tree for all users
      if (TEMP_FAILURE_RETRY(mount(source, target, NULL, MS_BIND, NULL)) == -1) {
        ALOGW("Failed to mount %s to %s :%d", source, target, errno);
        return false;
      }
    } else {
      // Only mount user-specific external storage
      if (TEMP_FAILURE_RETRY(
              mount(source_user.string(), target_user.string(), NULL, MS_BIND, NULL)) == -1) {
        ALOGW("Failed to mount %s to %s: %d", source_user.string(), target_user.string(), errno);
        return false;
      }
    }

    if (fs_prepare_dir(legacy, 0000, 0, 0) == -1) {
        return false;
    }

    // Finally, mount user-specific path into place for legacy users
    if (TEMP_FAILURE_RETRY(
            mount(target_user.string(), legacy, NULL, MS_BIND | MS_REC, NULL)) == -1) {
      ALOGW("Failed to mount %s to %s: %d", target_user.string(), legacy, errno);
      return false;
    }
  } else {
    ALOGW("Mount mode %d unsupported", mount_mode);
    return false;
  }

  return true;
}

#if defined(__linux__)
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
#endif

// Utility to close down the Zygote socket file descriptors while
// the child is still running as root with Zygote's privileges.  Each
// descriptor (if any) is closed via dup2(), replacing it with a valid
// (open) descriptor to /dev/null.

static void DetachDescriptors(JNIEnv* env, jintArray fdsToClose) {
  if (!fdsToClose) {
    return;
  }
  jsize count = env->GetArrayLength(fdsToClose);
  jint *ar = env->GetIntArrayElements(fdsToClose, 0);
  if (!ar) {
      ALOGE("Bad fd array");
      RuntimeAbort(env);
  }
  jsize i;
  int devnull;
  for (i = 0; i < count; i++) {
    devnull = open("/dev/null", O_RDWR);
    if (devnull < 0) {
      ALOGE("Failed to open /dev/null");
      RuntimeAbort(env);
      continue;
    }
    ALOGV("Switching descriptor %d to /dev/null: %d", ar[i], errno);
    if (dup2(devnull, ar[i]) < 0) {
      ALOGE("Failed dup2() on descriptor %d", ar[i]);
      RuntimeAbort(env);
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
    ALOGW("Unable to set the name of current thread to '%s'", buf);
  }
}

// Utility routine to fork zygote and specialize the child process.
static pid_t ForkAndSpecializeCommon(JNIEnv* env, uid_t uid, gid_t gid, jintArray javaGids,
                                     jint debug_flags, jobjectArray javaRlimits,
                                     jlong permittedCapabilities, jlong effectiveCapabilities,
                                     jint mount_external,
                                     jstring java_se_info, jstring java_se_name,
                                     bool is_system_server, jintArray fdsToClose) {
  SetSigChldHandler();

  pid_t pid = fork();

  if (pid == 0) {
    // The child process.
    gMallocLeakZygoteChild = 1;

    // Clean up any descriptors which must be closed immediately
    DetachDescriptors(env, fdsToClose);

    // Keep capabilities across UID change, unless we're staying root.
    if (uid != 0) {
      EnableKeepCapabilities(env);
    }

    DropCapabilitiesBoundingSet(env);

    if (!MountEmulatedStorage(uid, mount_external)) {
      ALOGW("Failed to mount emulated storage: %d", errno);
      if (errno == ENOTCONN || errno == EROFS) {
        // When device is actively encrypting, we get ENOTCONN here
        // since FUSE was mounted before the framework restarted.
        // When encrypted device is booting, we get EROFS since
        // FUSE hasn't been created yet by init.
        // In either case, continue without external storage.
      } else {
        ALOGE("Cannot continue without emulated storage");
        RuntimeAbort(env);
      }
    }

    SetGids(env, javaGids);

    SetRLimits(env, javaRlimits);

    int rc = setresgid(gid, gid, gid);
    if (rc == -1) {
      ALOGE("setresgid(%d) failed", gid);
      RuntimeAbort(env);
    }

    rc = setresuid(uid, uid, uid);
    if (rc == -1) {
      ALOGE("setresuid(%d) failed", uid);
      RuntimeAbort(env);
    }

#if defined(__linux__)
    if (NeedsNoRandomizeWorkaround()) {
        // Work around ARM kernel ASLR lossage (http://b/5817320).
        int old_personality = personality(0xffffffff);
        int new_personality = personality(old_personality | ADDR_NO_RANDOMIZE);
        if (new_personality == -1) {
            ALOGW("personality(%d) failed", new_personality);
        }
    }
#endif

    SetCapabilities(env, permittedCapabilities, effectiveCapabilities);

    SetSchedulerPolicy(env);

#if defined(HAVE_ANDROID_OS)
    {  // NOLINT(whitespace/braces)
      const char* se_info_c_str = NULL;
      ScopedUtfChars* se_info = NULL;
      if (java_se_info != NULL) {
          se_info = new ScopedUtfChars(env, java_se_info);
          se_info_c_str = se_info->c_str();
          if (se_info_c_str == NULL) {
            ALOGE("se_info_c_str == NULL");
            RuntimeAbort(env);
          }
      }
      const char* se_name_c_str = NULL;
      ScopedUtfChars* se_name = NULL;
      if (java_se_name != NULL) {
          se_name = new ScopedUtfChars(env, java_se_name);
          se_name_c_str = se_name->c_str();
          if (se_name_c_str == NULL) {
            ALOGE("se_name_c_str == NULL");
            RuntimeAbort(env);
          }
      }
      rc = selinux_android_setcontext(uid, is_system_server, se_info_c_str, se_name_c_str);
      if (rc == -1) {
        ALOGE("selinux_android_setcontext(%d, %d, \"%s\", \"%s\") failed", uid,
              is_system_server, se_info_c_str, se_name_c_str);
        RuntimeAbort(env);
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
    }
#else
    UNUSED(is_system_server);
    UNUSED(java_se_info);
    UNUSED(java_se_name);
#endif

    UnsetSigChldHandler();

    env->CallStaticVoidMethod(gZygoteClass, gCallPostForkChildHooks, debug_flags);
    if (env->ExceptionCheck()) {
      ALOGE("Error calling post fork hooks.");
      RuntimeAbort(env);
    }
  } else if (pid > 0) {
    // the parent process
  }
  return pid;
}
}  // anonymous namespace

namespace android {

static jint com_android_internal_os_Zygote_nativeForkAndSpecialize(
        JNIEnv* env, jclass, jint uid, jint gid, jintArray gids,
        jint debug_flags, jobjectArray rlimits,
        jint mount_external, jstring se_info, jstring se_name,
        jintArray fdsToClose) {
    return ForkAndSpecializeCommon(env, uid, gid, gids, debug_flags,
            rlimits, 0, 0, mount_external, se_info, se_name, false, fdsToClose);
}

static jint com_android_internal_os_Zygote_nativeForkSystemServer(
        JNIEnv* env, jclass, uid_t uid, gid_t gid, jintArray gids,
        jint debug_flags, jobjectArray rlimits, jlong permittedCapabilities,
        jlong effectiveCapabilities) {
  pid_t pid = ForkAndSpecializeCommon(env, uid, gid, gids,
                                      debug_flags, rlimits,
                                      permittedCapabilities, effectiveCapabilities,
                                      MOUNT_EXTERNAL_NONE, NULL, NULL, true, NULL);
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
          RuntimeAbort(env);
      }
  }
  return pid;
}

static JNINativeMethod gMethods[] = {
    { "nativeForkAndSpecialize", "(II[II[[IILjava/lang/String;Ljava/lang/String;[I)I",
      (void *) com_android_internal_os_Zygote_nativeForkAndSpecialize },
    { "nativeForkSystemServer", "(II[II[[IJJ)I",
      (void *) com_android_internal_os_Zygote_nativeForkSystemServer }
};

int register_com_android_internal_os_Zygote(JNIEnv* env) {
  gZygoteClass = (jclass) env->NewGlobalRef(env->FindClass(kZygoteClassName));
  if (gZygoteClass == NULL) {
    RuntimeAbort(env);
  }
  gCallPostForkChildHooks = env->GetStaticMethodID(gZygoteClass, "callPostForkChildHooks", "(I)V");

  return AndroidRuntime::registerNativeMethods(env, "com/android/internal/os/Zygote",
      gMethods, NELEM(gMethods));
}
}  // namespace android

