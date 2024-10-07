/*
 * Copyright (C) 2012 The Android Open Source Project
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

#define LOG_TAG "SELinuxJNI"

#include <errno.h>
#include <fcntl.h>

#include <utils/Log.h>

#include <nativehelper/JNIPlatformHelp.h>
#include "jni.h"
#include "core_jni_helpers.h"
#include "selinux/selinux.h"
#include "selinux/android.h"
#include <memory>
#include <atomic>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedUtfChars.h>

namespace android {
namespace {
std::atomic<selabel_handle*> sehandle{nullptr};

selabel_handle* GetSELabelHandle() {
    selabel_handle* h = sehandle.load();
    if (h != nullptr) {
        return h;
    }

    h = selinux_android_file_context_handle();
    selabel_handle* expected = nullptr;
    if (!sehandle.compare_exchange_strong(expected, h)) {
        selabel_close(h);
        return sehandle.load();
    }
    return h;
}

}

struct SecurityContext_Delete {
    void operator()(char* p) const {
        freecon(p);
    }
};
typedef std::unique_ptr<char[], SecurityContext_Delete> Unique_SecurityContext;

static jboolean isSELinuxDisabled = true;

/*
 * Function: isSELinuxEnabled
 * Purpose:  checks whether SELinux is enabled/disbaled
 * Parameters: none
 * Return value : true (enabled) or false (disabled)
 * Exceptions: none
 */
static jboolean isSELinuxEnabled(JNIEnv *env, jobject) {
    return !isSELinuxDisabled;
}

/*
 * Function: isSELinuxEnforced
 * Purpose: return the current SELinux enforce mode
 * Parameters: none
 * Return value: true (enforcing) or false (permissive)
 * Exceptions: none
 */
static jboolean isSELinuxEnforced(JNIEnv *env, jobject) {
    return (security_getenforce() == 1) ? true : false;
}

static jstring fileSelabelLookup(JNIEnv* env, jobject, jstring pathStr) {
    if (isSELinuxDisabled) {
        ALOGE("fileSelabelLookup => SELinux is disabled");
        return NULL;
    }

    if (pathStr == NULL) {
      ALOGE("fileSelabelLookup => got null path.");
      jniThrowNullPointerException(
          env, "Trying to get security context of a null path.");
      return NULL;
    }

    ScopedUtfChars path(env, pathStr);
    const char* path_c_str = path.c_str();
    if (path_c_str == NULL) {
        ALOGE("fileSelabelLookup => Got null path");
        jniThrowNullPointerException(
            env, "Trying to get security context of a null path.");
        return NULL;
    }

    auto* selabel_handle = GetSELabelHandle();
    if (selabel_handle == NULL) {
        ALOGE("fileSelabelLookup => Failed to get SEHandle");
        return NULL;
    }

    char* tmp = NULL;
    if (selabel_lookup(selabel_handle, &tmp, path_c_str, S_IFREG) != 0) {
      ALOGE("fileSelabelLookup => selabel_lookup for %s failed: %d", path_c_str, errno);
      return NULL;
    }

    Unique_SecurityContext context(tmp);
    return env->NewStringUTF(context.get());
}

static jstring getFdConInner(JNIEnv *env, jobject fileDescriptor, bool isSocket) {
    if (isSELinuxDisabled) {
        return NULL;
    }

    if (fileDescriptor == NULL) {
        jniThrowNullPointerException(env,
                "Trying to check security context of a null FileDescriptor.");
        return NULL;
    }

    int fd = jniGetFDFromFileDescriptor(env, fileDescriptor);
    if (env->ExceptionCheck()) {
        ALOGE("getFdCon => getFD for %p failed", fileDescriptor);
        return NULL;
    }

    char* tmp = NULL;
    int ret;
    if (isSocket) {
        ret = getpeercon(fd, &tmp);
    } else{
        ret = fgetfilecon(fd, &tmp);
    }
    Unique_SecurityContext context(tmp);

    ScopedLocalRef<jstring> contextStr(env, NULL);
    if (ret != -1) {
        contextStr.reset(env->NewStringUTF(context.get()));
    }

    ALOGV("getFdCon(%d) => %s", fd, context.get());
    return contextStr.release();
}

/*
 * Function: getPeerCon
 * Purpose: retrieves security context of peer socket
 * Parameters:
 *        fileDescriptor: peer socket file as a FileDescriptor object
 * Returns: jstring representing the security_context of socket or NULL if error
 * Exceptions: NullPointerException if fileDescriptor object is NULL
 */
static jstring getPeerCon(JNIEnv *env, jobject, jobject fileDescriptor) {
    return getFdConInner(env, fileDescriptor, true);
}

/*
 * Function: getFdCon
 * Purpose: retrieves security context of a file descriptor.
 * Parameters:
 *        fileDescriptor: a FileDescriptor object
 * Returns: jstring representing the security_context of socket or NULL if error
 * Exceptions: NullPointerException if fileDescriptor object is NULL
 */
static jstring getFdCon(JNIEnv *env, jobject, jobject fileDescriptor) {
    return getFdConInner(env, fileDescriptor, false);
}

/*
 * Function: setFSCreateCon
 * Purpose: set security context used for creating a new file system object
 * Parameters:
 *       context: char* representing the new context of a file system object,
 *                set to NULL to return to the default policy behavior
 * Returns: true on success, false on error
 * Exception: none
 */
static jboolean setFSCreateCon(JNIEnv *env, jobject, jstring contextStr) {
    if (isSELinuxDisabled) {
        return false;
    }

    std::unique_ptr<ScopedUtfChars> context;
    const char* context_c_str = NULL;
    if (contextStr != NULL) {
        context.reset(new ScopedUtfChars(env, contextStr));
        context_c_str = context->c_str();
        if (context_c_str == NULL) {
            return false;
        }
    }

    int ret = setfscreatecon(const_cast<char *>(context_c_str));

    ALOGV("setFSCreateCon(%s) => %d", context_c_str, ret);

    return (ret == 0) ? true : false;
}

/*
 * Function: setFileCon
 * Purpose:  set the security context of a file object
 * Parameters:
 *       path: the location of the file system object
 *       context: the new security context of the file system object
 * Returns: true on success, false on error
 * Exception: NullPointerException is thrown if either path or context strign are NULL
 */
static jboolean setFileCon(JNIEnv *env, jobject, jstring pathStr, jstring contextStr) {
    if (isSELinuxDisabled) {
        return false;
    }

    ScopedUtfChars path(env, pathStr);
    if (path.c_str() == NULL) {
        return false;
    }

    ScopedUtfChars context(env, contextStr);
    if (context.c_str() == NULL) {
        return false;
    }

    // GetStringUTFChars returns const char * yet setfilecon needs char *
    char *tmp = const_cast<char *>(context.c_str());
    int ret = setfilecon(path.c_str(), tmp);

    if (ret == 0) {
        ALOGV("setFileCon(%s, %s) => %d", path.c_str(), context.c_str(), ret);
        return true;
    }
    ALOGE("setFileCon(%s, %s) => %d, err: %s", path.c_str(), context.c_str(), ret, strerror(errno));
    return false;
}

/*
 * Function: getFileCon
 * Purpose: retrieves the context associated with the given path in the file system
 * Parameters:
 *        path: given path in the file system
 * Returns:
 *        string representing the security context string of the file object
 *        the string may be NULL if an error occured
 * Exceptions: NullPointerException if the path object is null
 */
static jstring getFileCon(JNIEnv *env, jobject, jstring pathStr) {
    if (isSELinuxDisabled) {
        return NULL;
    }

    ScopedUtfChars path(env, pathStr);
    if (path.c_str() == NULL) {
        return NULL;
    }

    char* tmp = NULL;
    int ret = getfilecon(path.c_str(), &tmp);
    Unique_SecurityContext context(tmp);

    ScopedLocalRef<jstring> securityString(env, NULL);
    if (ret != -1) {
        securityString.reset(env->NewStringUTF(context.get()));
    }

    ALOGV("getFileCon(%s) => %s", path.c_str(), context.get());
    return securityString.release();
}

/*
 * Function: getCon
 * Purpose: Get the context of the current process.
 * Parameters: none
 * Returns: a jstring representing the security context of the process,
 *          the jstring may be NULL if there was an error
 * Exceptions: none
 */
static jstring getCon(JNIEnv *env, jobject) {
    if (isSELinuxDisabled) {
        return NULL;
    }

    char* tmp = NULL;
    int ret = getcon(&tmp);
    Unique_SecurityContext context(tmp);

    ScopedLocalRef<jstring> securityString(env, NULL);
    if (ret != -1) {
        securityString.reset(env->NewStringUTF(context.get()));
    }

    ALOGV("getCon() => %s", context.get());
    return securityString.release();
}

/*
 * Function: getPidCon
 * Purpose: Get the context of a process identified by its pid
 * Parameters:
 *            pid: a jint representing the process
 * Returns: a jstring representing the security context of the pid,
 *          the jstring may be NULL if there was an error
 * Exceptions: none
 */
static jstring getPidCon(JNIEnv *env, jobject, jint pid) {
    if (isSELinuxDisabled) {
        return NULL;
    }

    char* tmp = NULL;
    int ret = getpidcon(static_cast<pid_t>(pid), &tmp);
    Unique_SecurityContext context(tmp);

    ScopedLocalRef<jstring> securityString(env, NULL);
    if (ret != -1) {
        securityString.reset(env->NewStringUTF(context.get()));
    }

    ALOGV("getPidCon(%d) => %s", pid, context.get());
    return securityString.release();
}

/*
 * Function: checkSELinuxAccess
 * Purpose: Check permissions between two security contexts.
 * Parameters: subjectContextStr: subject security context as a string
 *             objectContextStr: object security context as a string
 *             objectClassStr: object's security class name as a string
 *             permissionStr: permission name as a string
 * Returns: boolean: (true) if permission was granted, (false) otherwise
 * Exceptions: None
 */
static jboolean checkSELinuxAccess(JNIEnv *env, jobject, jstring subjectContextStr,
        jstring objectContextStr, jstring objectClassStr, jstring permissionStr) {
    if (isSELinuxDisabled) {
        return true;
    }

    ScopedUtfChars subjectContext(env, subjectContextStr);
    if (subjectContext.c_str() == NULL) {
        return false;
    }

    ScopedUtfChars objectContext(env, objectContextStr);
    if (objectContext.c_str() == NULL) {
        return false;
    }

    ScopedUtfChars objectClass(env, objectClassStr);
    if (objectClass.c_str() == NULL) {
        return false;
    }

    ScopedUtfChars permission(env, permissionStr);
    if (permission.c_str() == NULL) {
        return false;
    }

    char *tmp1 = const_cast<char *>(subjectContext.c_str());
    char *tmp2 = const_cast<char *>(objectContext.c_str());
    int accessGranted = selinux_check_access(tmp1, tmp2, objectClass.c_str(), permission.c_str(),
            NULL);

    ALOGV("checkSELinuxAccess(%s, %s, %s, %s) => %d", subjectContext.c_str(), objectContext.c_str(),
            objectClass.c_str(), permission.c_str(), accessGranted);

    return (accessGranted == 0) ? true : false;
}

/*
 * Function: native_restorecon
 * Purpose: restore default SELinux security context
 * Parameters: pathname: the pathname for the file to be relabeled
 * Returns: boolean: (true) file label successfully restored, (false) otherwise
 * Exceptions: none
 */
static jboolean native_restorecon(JNIEnv *env, jobject, jstring pathnameStr, jint flags) {
    if (isSELinuxDisabled) {
        return true;
    }

    ScopedUtfChars pathname(env, pathnameStr);
    if (pathname.c_str() == NULL) {
        ALOGV("restorecon(%p) => threw exception", pathnameStr);
        return false;
    }

    int ret = selinux_android_restorecon(pathname.c_str(), flags);
    ALOGV("restorecon(%s) => %d", pathname.c_str(), ret);
    return (ret == 0);
}

/*
 * JNI registration.
 */
static const JNINativeMethod method_table[] = {
    /* name,                     signature,                    funcPtr */
    { "checkSELinuxAccess"       , "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z" , (void*)checkSELinuxAccess },
    { "getContext"               , "()Ljava/lang/String;"                         , (void*)getCon           },
    { "getFileContext"           , "(Ljava/lang/String;)Ljava/lang/String;"       , (void*)getFileCon       },
    { "getPeerContext"           , "(Ljava/io/FileDescriptor;)Ljava/lang/String;" , (void*)getPeerCon       },
    { "getFileContext"           , "(Ljava/io/FileDescriptor;)Ljava/lang/String;" , (void*)getFdCon         },
    { "getPidContext"            , "(I)Ljava/lang/String;"                        , (void*)getPidCon        },
    { "isSELinuxEnforced"        , "()Z"                                          , (void*)isSELinuxEnforced},
    { "isSELinuxEnabled"         , "()Z"                                          , (void*)isSELinuxEnabled },
    { "native_restorecon"        , "(Ljava/lang/String;I)Z"                       , (void*)native_restorecon},
    { "setFileContext"           , "(Ljava/lang/String;Ljava/lang/String;)Z"      , (void*)setFileCon       },
    { "setFSCreateContext"       , "(Ljava/lang/String;)Z"                        , (void*)setFSCreateCon   },
    { "fileSelabelLookup"        , "(Ljava/lang/String;)Ljava/lang/String;"       , (void*)fileSelabelLookup},
};

static int log_callback(int type, const char *fmt, ...) {
    va_list ap;
    int priority;

    switch (type) {
    case SELINUX_WARNING:
        priority = ANDROID_LOG_WARN;
        break;
    case SELINUX_INFO:
        priority = ANDROID_LOG_INFO;
        break;
    default:
        priority = ANDROID_LOG_ERROR;
        break;
    }
    va_start(ap, fmt);
    LOG_PRI_VA(priority, "SELinux", fmt, ap);
    va_end(ap);
    return 0;
}

int register_android_os_SELinux(JNIEnv *env) {
    union selinux_callback cb;
    cb.func_log = log_callback;
    selinux_set_callback(SELINUX_CB_LOG, cb);

    isSELinuxDisabled = (is_selinux_enabled() != 1) ? true : false;

    return RegisterMethodsOrDie(env, "android/os/SELinux", method_table, NELEM(method_table));
}

}
