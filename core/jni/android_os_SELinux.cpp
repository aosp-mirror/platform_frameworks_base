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
#include <utils/Log.h>

#include "JNIHelp.h"
#include "jni.h"
#include "android_runtime/AndroidRuntime.h"
#ifdef HAVE_SELINUX
#include "selinux/selinux.h"
#include "selinux/android.h"
#endif
#include <errno.h>

namespace android {

  static jboolean isSELinuxDisabled = true;

  static void throw_NullPointerException(JNIEnv *env, const char* msg) {
    jclass clazz;
    clazz = env->FindClass("java/lang/NullPointerException");
    env->ThrowNew(clazz, msg);
  }

  /*
   * Function: isSELinuxEnabled
   * Purpose:  checks whether SELinux is enabled/disbaled
   * Parameters: none
   * Return value : true (enabled) or false (disabled)
   * Exceptions: none
   */
  static jboolean isSELinuxEnabled(JNIEnv *env, jobject classz) {

    return !isSELinuxDisabled;
  }

  /*
   * Function: isSELinuxEnforced
   * Purpose: return the current SELinux enforce mode
   * Parameters: none
   * Return value: true (enforcing) or false (permissive)
   * Exceptions: none
   */
  static jboolean isSELinuxEnforced(JNIEnv *env, jobject clazz) {
#ifdef HAVE_SELINUX
    return (security_getenforce() == 1) ? true : false;
#else
    return false;
#endif
  }

  /*
   * Function: setSELinuxEnforce
   * Purpose: set the SE Linux enforcing mode
   * Parameters: true (enforcing) or false (permissive)
   * Return value: true (success) or false (fail)
   * Exceptions: none
   */
  static jboolean setSELinuxEnforce(JNIEnv *env, jobject clazz, jboolean value) {
#ifdef HAVE_SELINUX
    if (isSELinuxDisabled)
      return false;

    int enforce = (value) ? 1 : 0;

    return (security_setenforce(enforce) != -1) ? true : false;
#else
    return false;
#endif
  }

  /*
   * Function: getPeerCon
   * Purpose: retrieves security context of peer socket
   * Parameters:
   *        fileDescriptor: peer socket file as a FileDescriptor object
   * Returns: jstring representing the security_context of socket or NULL if error
   * Exceptions: NullPointerException if fileDescriptor object is NULL
   */
  static jstring getPeerCon(JNIEnv *env, jobject clazz, jobject fileDescriptor) {
#ifdef HAVE_SELINUX
    if (isSELinuxDisabled)
      return NULL;

    if (fileDescriptor == NULL) {
      throw_NullPointerException(env, "Trying to check security context of a null peer socket.");
      return NULL;
    }

    security_context_t context = NULL;
    jstring securityString = NULL;

    int fd = jniGetFDFromFileDescriptor(env, fileDescriptor);

    if (env->ExceptionOccurred() != NULL) {
      ALOGE("There was an issue with retrieving the file descriptor");
      goto bail;
    }

    if (getpeercon(fd, &context) == -1)
      goto bail;

    ALOGV("getPeerCon: Successfully retrived context of peer socket '%s'", context);

    securityString = env->NewStringUTF(context);

  bail:
    if (context != NULL)
      freecon(context);

    return securityString;
#else
    return NULL;
#endif
  }

  /*
   * Function: setFSCreateCon
   * Purpose: set security context used for creating a new file system object
   * Parameters:
   *       context: security_context_t representing the new context of a file system object,
   *                set to NULL to return to the default policy behavior
   * Returns: true on success, false on error
   * Exception: none
   */
  static jboolean setFSCreateCon(JNIEnv *env, jobject clazz, jstring context) {
#ifdef HAVE_SELINUX
    if (isSELinuxDisabled)
      return false;

    char * securityContext = NULL;
    const char *constant_securityContext = NULL;

    if (context != NULL) {
      constant_securityContext = env->GetStringUTFChars(context, NULL);

      // GetStringUTFChars returns const char * yet setfscreatecon needs char *
      securityContext = const_cast<char *>(constant_securityContext);
    }

    int ret;
    if ((ret = setfscreatecon(securityContext)) == -1)
      goto bail;

    ALOGV("setFSCreateCon: set new security context to '%s' ", context == NULL ? "default", context);

  bail:
    if (constant_securityContext != NULL)
      env->ReleaseStringUTFChars(context, constant_securityContext);

    return (ret == 0) ? true : false;
#else
    return false;
#endif
  }

  /*
   * Function: setFileCon
   * Purpose:  set the security context of a file object
   * Parameters:
   *       path: the location of the file system object
   *       con: the new security context of the file system object
   * Returns: true on success, false on error
   * Exception: NullPointerException is thrown if either path or context strign are NULL
   */
  static jboolean setFileCon(JNIEnv *env, jobject clazz, jstring path, jstring con) {
#ifdef HAVE_SELINUX
    if (isSELinuxDisabled)
      return false;

    if (path == NULL) {
      throw_NullPointerException(env, "Trying to change the security context of a NULL file object.");
      return false;
    }

    if (con == NULL) {
      throw_NullPointerException(env, "Trying to set the security context of a file object with NULL.");
      return false;
    }

    const char *objectPath = env->GetStringUTFChars(path, NULL);
    const char *constant_con = env->GetStringUTFChars(con, NULL);

    // GetStringUTFChars returns const char * yet setfilecon needs char *
    char *newCon = const_cast<char *>(constant_con);

    int ret;
    if ((ret = setfilecon(objectPath, newCon)) == -1)
      goto bail;

    ALOGV("setFileCon: Succesfully set security context '%s' for '%s'", newCon, objectPath);

  bail:
    env->ReleaseStringUTFChars(path, objectPath);
    env->ReleaseStringUTFChars(con, constant_con);
    return (ret == 0) ? true : false;
#else
    return false;
#endif
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
  static jstring getFileCon(JNIEnv *env, jobject clazz, jstring path) {
#ifdef HAVE_SELINUX
    if (isSELinuxDisabled)
      return NULL;

    if (path == NULL) {
      throw_NullPointerException(env, "Trying to check security context of a null path.");
      return NULL;
    }

    const char *objectPath = env->GetStringUTFChars(path, NULL);

    security_context_t context = NULL;
    jstring securityString = NULL;

    if (getfilecon(objectPath, &context) == -1)
      goto bail;

    ALOGV("getFileCon: Successfully retrived context '%s' for file '%s'", context, objectPath);

    securityString = env->NewStringUTF(context);

  bail:
    if (context != NULL)
      freecon(context);

    env->ReleaseStringUTFChars(path, objectPath);

    return securityString;
#else
    return NULL;
#endif
  }

  /*
   * Function: getCon
   * Purpose: Get the context of the current process.
   * Parameters: none
   * Returns: a jstring representing the security context of the process,
   *          the jstring may be NULL if there was an error
   * Exceptions: none
   */
  static jstring getCon(JNIEnv *env, jobject clazz) {
#ifdef HAVE_SELINUX
    if (isSELinuxDisabled)
      return NULL;

    security_context_t context = NULL;
    jstring securityString = NULL;

    if (getcon(&context) == -1)
      goto bail;

    ALOGV("getCon: Successfully retrieved context '%s'", context);

    securityString = env->NewStringUTF(context);

  bail:
    if (context != NULL)
      freecon(context);

    return securityString;
#else
    return NULL;
#endif
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
  static jstring getPidCon(JNIEnv *env, jobject clazz, jint pid) {
#ifdef HAVE_SELINUX
    if (isSELinuxDisabled)
      return NULL;

    security_context_t context = NULL;
    jstring securityString = NULL;

    pid_t checkPid = (pid_t)pid;

    if (getpidcon(checkPid, &context) == -1)
      goto bail;

    ALOGV("getPidCon: Successfully retrived context '%s' for pid '%d'", context, checkPid);

    securityString = env->NewStringUTF(context);

  bail:
    if (context != NULL)
      freecon(context);

    return securityString;
#else
    return NULL;
#endif
  }

  /*
   * Function: getBooleanNames
   * Purpose: Gets a list of the SELinux boolean names.
   * Parameters: None
   * Returns: an array of strings  containing the SELinux boolean names.
   *          returns NULL string on error
   * Exceptions: None
   */
  static jobjectArray getBooleanNames(JNIEnv *env, JNIEnv clazz) {
#ifdef HAVE_SELINUX
    if (isSELinuxDisabled)
      return NULL;

    char **list;
    int i, len, ret;
    jclass stringClass;
    jobjectArray stringArray = NULL;

    if (security_get_boolean_names(&list, &len) == -1)
      return NULL;

    stringClass = env->FindClass("java/lang/String");
    stringArray = env->NewObjectArray(len, stringClass, env->NewStringUTF(""));
    for (i = 0; i < len; i++) {
      jstring obj;
      obj = env->NewStringUTF(list[i]);
      env->SetObjectArrayElement(stringArray, i, obj);
      env->DeleteLocalRef(obj);
      free(list[i]);
    }
    free(list);

    return stringArray;
#else
    return NULL;
#endif
  }

  /*
   * Function: getBooleanValue
   * Purpose: Gets the value for the given SELinux boolean name.
   * Parameters:
   *            String: The name of the SELinux boolean.
   * Returns: a boolean: (true) boolean is set or (false) it is not.
   * Exceptions: None
   */
  static jboolean getBooleanValue(JNIEnv *env, jobject clazz, jstring name) {
#ifdef HAVE_SELINUX
    if (isSELinuxDisabled)
      return false;

    const char *boolean_name;
    int ret;

    if (name == NULL)
      return false;
    boolean_name = env->GetStringUTFChars(name, NULL);
    ret = security_get_boolean_active(boolean_name);
    env->ReleaseStringUTFChars(name, boolean_name);
    return (ret == 1) ? true : false;
#else
    return false;
#endif
  }

  /*
   * Function: setBooleanNames
   * Purpose: Sets the value for the given SELinux boolean name.
   * Parameters:
   *            String: The name of the SELinux boolean.
   *            Boolean: The new value of the SELinux boolean.
   * Returns: a boolean indicating whether or not the operation succeeded.
   * Exceptions: None
   */
  static jboolean setBooleanValue(JNIEnv *env, jobject clazz, jstring name, jboolean value) {
#ifdef HAVE_SELINUX
    if (isSELinuxDisabled)
      return false;

    const char *boolean_name = NULL;
    int ret;

    if (name == NULL)
      return false;
    boolean_name = env->GetStringUTFChars(name, NULL);
    ret = security_set_boolean(boolean_name, (value) ? 1 : 0);
    env->ReleaseStringUTFChars(name, boolean_name);
    if (ret)
      return false;

    if (security_commit_booleans() == -1)
      return false;

    return true;
#else
    return false;
#endif
  }

  /*
   * Function: checkSELinuxAccess
   * Purpose: Check permissions between two security contexts.
   * Parameters: scon: subject security context as a string
   *             tcon: object security context as a string
   *             tclass: object's security class name as a string
   *             perm: permission name as a string
   * Returns: boolean: (true) if permission was granted, (false) otherwise
   * Exceptions: None
   */
  static jboolean checkSELinuxAccess(JNIEnv *env, jobject clazz, jstring scon, jstring tcon, jstring tclass, jstring perm) {
#ifdef HAVE_SELINUX
    if (isSELinuxDisabled)
      return true;

    int accessGranted = -1;

    const char *const_scon, *const_tcon, *mytclass, *myperm;
    char *myscon, *mytcon;

    if (scon == NULL || tcon == NULL || tclass == NULL || perm == NULL)
      goto bail;

    const_scon = env->GetStringUTFChars(scon, NULL);
    const_tcon = env->GetStringUTFChars(tcon, NULL);
    mytclass   = env->GetStringUTFChars(tclass, NULL);
    myperm     = env->GetStringUTFChars(perm, NULL);

    // selinux_check_access needs char* for some
    myscon = const_cast<char *>(const_scon);
    mytcon = const_cast<char *>(const_tcon);

    accessGranted = selinux_check_access(myscon, mytcon, mytclass, myperm, NULL);

    ALOGV("selinux_check_access returned %d", accessGranted);

    env->ReleaseStringUTFChars(scon, const_scon);
    env->ReleaseStringUTFChars(tcon, const_tcon);
    env->ReleaseStringUTFChars(tclass, mytclass);
    env->ReleaseStringUTFChars(perm, myperm);

  bail:
    return (accessGranted == 0) ? true : false;

#else
    return true;
#endif
  }

  /*
   * Function: native_restorecon
   * Purpose: restore default SELinux security context
   * Parameters: pathname: the pathname for the file to be relabeled
   * Returns: boolean: (true) file label successfully restored, (false) otherwise
   * Exceptions: none
   */
  static jboolean native_restorecon(JNIEnv *env, jobject clazz, jstring pathname) {
#ifdef HAVE_SELINUX
    if (isSELinuxDisabled)
      return true;

    const char *file = const_cast<char *>(env->GetStringUTFChars(pathname, NULL));
    int ret = selinux_android_restorecon(file);
    env->ReleaseStringUTFChars(pathname, file);
    return (ret == 0);
#else
    return true;
#endif
  }

  /*
   * JNI registration.
   */
  static JNINativeMethod method_table[] = {

    /* name,                     signature,                    funcPtr */
    { "checkSELinuxAccess"       , "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z" , (void*)checkSELinuxAccess },
    { "getBooleanNames"          , "()[Ljava/lang/String;"                        , (void*)getBooleanNames  },
    { "getBooleanValue"          , "(Ljava/lang/String;)Z"                        , (void*)getBooleanValue  },
    { "getContext"               , "()Ljava/lang/String;"                         , (void*)getCon           },
    { "getFileContext"           , "(Ljava/lang/String;)Ljava/lang/String;"       , (void*)getFileCon       },
    { "getPeerContext"           , "(Ljava/io/FileDescriptor;)Ljava/lang/String;" , (void*)getPeerCon       },
    { "getPidContext"            , "(I)Ljava/lang/String;"                        , (void*)getPidCon        },
    { "isSELinuxEnforced"        , "()Z"                                          , (void*)isSELinuxEnforced},
    { "isSELinuxEnabled"         , "()Z"                                          , (void*)isSELinuxEnabled },
    { "native_restorecon"        , "(Ljava/lang/String;)Z"                        , (void*)native_restorecon},
    { "setBooleanValue"          , "(Ljava/lang/String;Z)Z"                       , (void*)setBooleanValue  },
    { "setFileContext"           , "(Ljava/lang/String;Ljava/lang/String;)Z"      , (void*)setFileCon       },
    { "setFSCreateContext"       , "(Ljava/lang/String;)Z"                        , (void*)setFSCreateCon   },
    { "setSELinuxEnforce"        , "(Z)Z"                                         , (void*)setSELinuxEnforce},
  };

  static int log_callback(int type, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    LOG_PRI_VA(ANDROID_LOG_ERROR, "SELinux", fmt, ap);
    va_end(ap);
    return 0;
  }

  int register_android_os_SELinux(JNIEnv *env) {
#ifdef HAVE_SELINUX
    union selinux_callback cb;
    cb.func_log = log_callback;
    selinux_set_callback(SELINUX_CB_LOG, cb);

    isSELinuxDisabled = (is_selinux_enabled() != 1) ? true : false;

#endif
    return AndroidRuntime::registerNativeMethods(
         env, "android/os/SELinux",
         method_table, NELEM(method_table));
  }
}
