/*
 * Copyright (C) 2015 The Android Open Source Project
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

#define LOG_TAG "ActivityManagerService"
//#define LOG_NDEBUG 0

#include <android_runtime/AndroidRuntime.h>
#include <jni.h>

#include <ScopedLocalRef.h>
#include <ScopedPrimitiveArray.h>

#include <cutils/log.h>
#include <utils/misc.h>
#include <utils/Log.h>

#include <stdio.h>
#include <errno.h>
#include <fcntl.h>
#include <semaphore.h>
#include <stddef.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

namespace android
{

    // migrate from foreground to foreground_boost
    static jint migrateToBoost(JNIEnv *env, jobject _this)
    {
#ifdef USE_SCHED_BOOST
        // File descriptors open to /dev/cpuset/../tasks, setup by initialize, or -1 on error
        FILE* fg_cpuset_file = NULL;
        int   boost_cpuset_fd = 0;
        if (!access("/dev/cpuset/tasks", F_OK)) {
            fg_cpuset_file = fopen("/dev/cpuset/foreground/tasks", "r+");
            if (ferror(fg_cpuset_file)) {
                return 0;
            }
            boost_cpuset_fd = open("/dev/cpuset/foreground/boost/tasks", O_WRONLY);
            if (boost_cpuset_fd < 0) {
                fclose(fg_cpuset_file);
                return 0;
            }

        }
        if (!fg_cpuset_file || !boost_cpuset_fd) {
            fclose(fg_cpuset_file);
            close(boost_cpuset_fd);
            return 0;
        }
        char buf[17];
        while (fgets(buf, 16, fg_cpuset_file)) {
            int i = 0;
            for (; i < 16; i++) {
                if (buf[i] == '\n') {
                    buf[i] = 0;
                    break;
                }
            }
            if (write(boost_cpuset_fd, buf, i) < 0) {
                // ignore error
            }
            if (feof(fg_cpuset_file))
                break;
        }
        fclose(fg_cpuset_file);
        close(boost_cpuset_fd);
#endif
        return 0;
    }

    // migrate from foreground_boost to foreground
    static jint migrateFromBoost(JNIEnv *env, jobject _this)
    {
#ifdef USE_SCHED_BOOST
        // File descriptors open to /dev/cpuset/../tasks, setup by initialize, or -1 on error
        int   fg_cpuset_fd = 0;
        FILE* boost_cpuset_file = NULL;
        if (!access("/dev/cpuset/tasks", F_OK)) {
            boost_cpuset_file = fopen("/dev/cpuset/foreground/boost/tasks", "r+");
            if (ferror(boost_cpuset_file)) {
                return 0;
            }
            fg_cpuset_fd = open("/dev/cpuset/foreground/tasks", O_WRONLY);
            if (fg_cpuset_fd < 0) {
                fclose(boost_cpuset_file);
                return 0;
            }

        }
        if (!boost_cpuset_file || !fg_cpuset_fd) {
            fclose(boost_cpuset_file);
            close(fg_cpuset_fd);
            return 0;
        }
        char buf[17];
        while (fgets(buf, 16, boost_cpuset_file)) {
            //ALOGE("Appending FD %s to fg", buf);
            int i = 0;
            for (; i < 16; i++) {
                if (buf[i] == '\n') {
                    buf[i] = 0;
                    break;
                }
            }
            if (write(fg_cpuset_fd, buf, i) < 0) {
                //ALOGE("Appending FD %s to fg ERROR", buf);
                // handle error?
            }
            if (feof(boost_cpuset_file))
                break;
        }

        close(fg_cpuset_fd);
        fclose(boost_cpuset_file);

#endif
        return 0;

    }


    static JNINativeMethod method_table[] = {
        { "nativeMigrateToBoost",   "()I", (void*)migrateToBoost },
        { "nativeMigrateFromBoost", "()I", (void*)migrateFromBoost },
    };

    int register_android_server_ActivityManagerService(JNIEnv *env)
    {
        return jniRegisterNativeMethods(env, "com/android/server/am/ActivityManagerService",
                                        method_table, NELEM(method_table));
    }

}
