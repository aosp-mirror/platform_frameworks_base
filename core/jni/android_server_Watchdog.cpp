/*
 ** Copyright 2010, The Android Open Source Project
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

#define LOG_TAG "Watchdog_N"
#include <utils/Log.h>

#include <sys/types.h>
#include <fcntl.h>
#include <dirent.h>
#include <string.h>
#include <errno.h>

#include "jni.h"
#include "JNIHelp.h"
#include <android_runtime/AndroidRuntime.h>

static void dumpOneStack(int tid, int outFd) {
    char buf[64];

    snprintf(buf, sizeof(buf), "/proc/%d/stack", tid);
    int stackFd = open(buf, O_RDONLY);
    if (stackFd >= 0) {
        // header for readability
        strncat(buf, ":\n", sizeof(buf) - strlen(buf) - 1);
        write(outFd, buf, strlen(buf));

        // copy the stack dump text
        int nBytes;
        while ((nBytes = read(stackFd, buf, sizeof(buf))) > 0) {
            write(outFd, buf, nBytes);
        }

        // footer and done
        write(outFd, "\n", 1);
        close(stackFd);
    } else {
        LOGE("Unable to open stack of tid %d : %d (%s)", tid, errno, strerror(errno));
    }
}

static void dumpKernelStacks(JNIEnv* env, jobject clazz, jstring pathStr) {
    char buf[128];
    DIR* taskdir;

    LOGI("dumpKernelStacks");
    if (!pathStr) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Null path");
        return;
    }

    const char *path = env->GetStringUTFChars(pathStr, NULL);

    int outFd = open(path, O_WRONLY | O_APPEND | O_CREAT);
    if (outFd < 0) {
        LOGE("Unable to open stack dump file: %d (%s)", errno, strerror(errno));
        goto done;
    }

    snprintf(buf, sizeof(buf), "\n----- begin pid %d kernel stacks -----\n", getpid());
    write(outFd, buf, strlen(buf));

    // look up the list of all threads in this process
    snprintf(buf, sizeof(buf), "/proc/%d/task", getpid());
    taskdir = opendir(buf);
    if (taskdir != NULL) {
        struct dirent * ent;
        while ((ent = readdir(taskdir)) != NULL) {
            int tid = atoi(ent->d_name);
            if (tid > 0 && tid <= 65535) {
                // dump each stack trace
                dumpOneStack(tid, outFd);
            }
        }
        closedir(taskdir);
    }

    snprintf(buf, sizeof(buf), "----- end pid %d kernel stacks -----\n", getpid());
    write(outFd, buf, strlen(buf));

    close(outFd);
done:
    env->ReleaseStringUTFChars(pathStr, path);
}

// ----------------------------------------

namespace android {

static const JNINativeMethod g_methods[] = {
    { "native_dumpKernelStacks", "(Ljava/lang/String;)V", (void*)dumpKernelStacks },
};

int register_android_server_Watchdog(JNIEnv* env) {
    return AndroidRuntime::registerNativeMethods(env, "com/android/server/Watchdog",
                                                 g_methods, NELEM(g_methods));
}

}
