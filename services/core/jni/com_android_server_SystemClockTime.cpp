/*
 * Copyright (C) 2022 The Android Open Source Project
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

#define LOG_TAG "SystemClockTime"

#include <android-base/file.h>
#include <android-base/unique_fd.h>
#include <linux/rtc.h>
#include <nativehelper/JNIHelp.h>
#include <utils/Log.h>
#include <utils/String8.h>

#include "jni.h"

namespace android {

class SystemClockImpl {
public:
    SystemClockImpl(const std::string &rtc_dev) : rtc_dev{rtc_dev} {}

    int setTime(struct timeval *tv);

private:
    std::string rtc_dev;
};

int SystemClockImpl::setTime(struct timeval *tv) {
    if (settimeofday(tv, NULL) == -1) {
        ALOGV("settimeofday() failed: %s", strerror(errno));
        return -1;
    }

    android::base::unique_fd fd{open(rtc_dev.c_str(), O_RDWR)};
    if (!fd.ok()) {
        ALOGE("Unable to open %s: %s", rtc_dev.c_str(), strerror(errno));
        return -1;
    }

    struct tm tm;
    if (!gmtime_r(&tv->tv_sec, &tm)) {
        ALOGV("gmtime_r() failed: %s", strerror(errno));
        return -1;
    }

    struct rtc_time rtc = {};
    rtc.tm_sec = tm.tm_sec;
    rtc.tm_min = tm.tm_min;
    rtc.tm_hour = tm.tm_hour;
    rtc.tm_mday = tm.tm_mday;
    rtc.tm_mon = tm.tm_mon;
    rtc.tm_year = tm.tm_year;
    rtc.tm_wday = tm.tm_wday;
    rtc.tm_yday = tm.tm_yday;
    rtc.tm_isdst = tm.tm_isdst;
    if (ioctl(fd, RTC_SET_TIME, &rtc) == -1) {
        ALOGV("RTC_SET_TIME ioctl failed: %s", strerror(errno));
        return -1;
    }

    return 0;
}

static jlong com_android_server_SystemClockTime_init(JNIEnv *, jobject) {
    // Find the wall clock RTC. We expect this always to be /dev/rtc0, but
    // check the /dev/rtc symlink first so that legacy devices that don't use
    // rtc0 can add a symlink rather than need to carry a local patch to this
    // code.
    //
    // TODO: if you're reading this in a world where all devices are using the
    // GKI, you can remove the readlink and just assume /dev/rtc0.
    std::string dev_rtc;
    if (!android::base::Readlink("/dev/rtc", &dev_rtc)) {
        dev_rtc = "/dev/rtc0";
    }

    std::unique_ptr<SystemClockImpl> system_clock{new SystemClockImpl(dev_rtc)};
    return reinterpret_cast<jlong>(system_clock.release());
}

static jint com_android_server_SystemClockTime_setTime(JNIEnv *, jobject, jlong nativeData,
                                                       jlong millis) {
    SystemClockImpl *impl = reinterpret_cast<SystemClockImpl *>(nativeData);

    if (millis <= 0 || millis / 1000LL >= std::numeric_limits<time_t>::max()) {
        return -1;
    }

    struct timeval tv;
    tv.tv_sec = (millis / 1000LL);
    tv.tv_usec = ((millis % 1000LL) * 1000LL);

    ALOGD("Setting time of day to sec=%ld", tv.tv_sec);

    int ret = impl->setTime(&tv);
    if (ret < 0) {
        ALOGW("Unable to set rtc to %ld: %s", tv.tv_sec, strerror(errno));
        ret = -1;
    }
    return ret;
}

static const JNINativeMethod sMethods[] = {
        /* name, signature, funcPtr */
        {"init", "()J", (void *)com_android_server_SystemClockTime_init},
        {"setTime", "(JJ)I", (void *)com_android_server_SystemClockTime_setTime},
};

int register_com_android_server_SystemClockTime(JNIEnv *env) {
    return jniRegisterNativeMethods(env, "com/android/server/SystemClockTime", sMethods,
                                    NELEM(sMethods));
}

} /* namespace android */
