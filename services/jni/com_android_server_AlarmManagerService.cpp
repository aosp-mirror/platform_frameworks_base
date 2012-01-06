/* //device/libs/android_runtime/android_server_AlarmManagerService.cpp
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

#define LOG_TAG "AlarmManagerService"

#include "JNIHelp.h"
#include "jni.h"
#include <utils/Log.h>
#include <utils/misc.h>

#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <stdlib.h>
#include <errno.h>
#include <unistd.h>
#include <linux/ioctl.h>
#include <linux/android_alarm.h>

namespace android {

static jint android_server_AlarmManagerService_setKernelTimezone(JNIEnv* env, jobject obj, jint fd, jint minswest)
{
    struct timezone tz;

    tz.tz_minuteswest = minswest;
    tz.tz_dsttime = 0;

    int result = settimeofday(NULL, &tz);
    if (result < 0) {
        ALOGE("Unable to set kernel timezone to %d: %s\n", minswest, strerror(errno));
        return -1;
    } else {
        ALOGD("Kernel timezone updated to %d minutes west of GMT\n", minswest);
    }

    return 0;
}

static jint android_server_AlarmManagerService_init(JNIEnv* env, jobject obj)
{
    return open("/dev/alarm", O_RDWR);
}

static void android_server_AlarmManagerService_close(JNIEnv* env, jobject obj, jint fd)
{
	close(fd);
}

static void android_server_AlarmManagerService_set(JNIEnv* env, jobject obj, jint fd, jint type, jlong seconds, jlong nanoseconds)
{
    struct timespec ts;
    ts.tv_sec = seconds;
    ts.tv_nsec = nanoseconds;

	int result = ioctl(fd, ANDROID_ALARM_SET(type), &ts);
	if (result < 0)
	{
        ALOGE("Unable to set alarm to %lld.%09lld: %s\n", seconds, nanoseconds, strerror(errno));
    }
}

static jint android_server_AlarmManagerService_waitForAlarm(JNIEnv* env, jobject obj, jint fd)
{
	int result = 0;

	do
	{
		result = ioctl(fd, ANDROID_ALARM_WAIT);
	} while (result < 0 && errno == EINTR);

	if (result < 0)
	{
        ALOGE("Unable to wait on alarm: %s\n", strerror(errno));
        return 0;
    }

    return result;
}

static JNINativeMethod sMethods[] = {
     /* name, signature, funcPtr */
	{"init", "()I", (void*)android_server_AlarmManagerService_init},
	{"close", "(I)V", (void*)android_server_AlarmManagerService_close},
	{"set", "(IIJJ)V", (void*)android_server_AlarmManagerService_set},
    {"waitForAlarm", "(I)I", (void*)android_server_AlarmManagerService_waitForAlarm},
    {"setKernelTimezone", "(II)I", (void*)android_server_AlarmManagerService_setKernelTimezone},
};

int register_android_server_AlarmManagerService(JNIEnv* env)
{
    return jniRegisterNativeMethods(env, "com/android/server/AlarmManagerService",
                                    sMethods, NELEM(sMethods));
}

} /* namespace android */
