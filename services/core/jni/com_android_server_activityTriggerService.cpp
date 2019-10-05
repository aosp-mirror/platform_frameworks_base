/*
* Copyright (c) 2019, The Linux Foundation. All rights reserved.
*
* Redistribution and use in source and binary forms, with or without
* modification, are permitted provided that the following conditions are
* met:
*     * Redistributions of source code must retain the above copyright
*       notice, this list of conditions and the following disclaimer.
*     * Redistributions in binary form must reproduce the above
*       copyright notice, this list of conditions and the following
*       disclaimer in the documentation and/or other materials provided
*       with the distribution.
*     * Neither the name of The Linux Foundation nor the names of its
*       contributors may be used to endorse or promote products derived
*       from this software without specific prior written permission.
*
* THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
* WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
* MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
* ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
* BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
* CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
* SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
* BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
* WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
* OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
* IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include "android_runtime/AndroidRuntime.h"
#include <utils/Log.h>
#include <dlfcn.h>
#include <limits.h>

namespace android {

//structure which has a handle for dynamically loading the trigger handler lib.

typedef struct dlLibHandler {
    void *handle;
    void (*set_info)(const char*, const char*, int *);
    const char *dlname;
}dlLibhandler;

//initialize the handler
static dlLibHandler handler = {
    NULL, NULL, "libtrigger-handler.so"
};

static void trigger_handler_lib_init() {
    bool dlError = false;
    handler.handle = dlopen(handler.dlname, RTLD_NOW | RTLD_LOCAL);
    /*no need to proceed if the lib isn't available*/
    if(handler.handle == NULL) {
        ALOGE("Activity trigger handling disabled.");
        return;
    }
    *(void **) (&handler.set_info) = dlsym(handler.handle, "set_info");
    if(handler.set_info == NULL) {
        dlError = true;
    }
    if(dlError) {
        handler.set_info = NULL;
        if(handler.handle) dlclose(handler.handle);
        handler.handle = NULL;
    }
}

static void notifyAction_native (JNIEnv* env, jobject /*jclazz*/,jstring pkgName, jlong vCode, jstring /*procName*/, jint pid_in, jint flag) {
   int pid = (int)pid_in;
   std::string versionCode = std::to_string((long)vCode) + std::to_string((int)flag);
   const char* version = versionCode.c_str();

   if(pkgName && handler.set_info) {
     const char *package = env->GetStringUTFChars(pkgName, NULL);
       if(package) {
           (*handler.set_info)(package,version,&pid);
           env->ReleaseStringUTFChars(pkgName, package);
       }
   }
}

static JNINativeMethod method_list[] = {
   { "notifyAction_native", "(Ljava/lang/String;JLjava/lang/String;II)V", (void*)notifyAction_native },
};

int register_android_server_ActivityTriggerService(JNIEnv *env) {
   //load and link to the handler library
   trigger_handler_lib_init();
   return jniRegisterNativeMethods(env, "com/android/server/ActivityTriggerService",
          method_list, NELEM(method_list));
}
};
