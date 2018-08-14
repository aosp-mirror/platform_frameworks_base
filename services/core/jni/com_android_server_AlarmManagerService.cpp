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

#include <nativehelper/JNIHelp.h>
#include "jni.h"
#include <utils/Log.h>
#include <utils/misc.h>
#include <utils/String8.h>

#include <dirent.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/timerfd.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <stdlib.h>
#include <errno.h>
#include <unistd.h>
#include <linux/ioctl.h>
#include <linux/rtc.h>

#include <array>
#include <memory>

namespace android {

static constexpr int ANDROID_ALARM_TIME_CHANGE_MASK = 1 << 16;

/**
 * The AlarmManager alarm constants:
 *
 *   RTC_WAKEUP
 *   RTC
 *   REALTIME_WAKEUP
 *   REALTIME
 *   SYSTEMTIME (only defined in old alarm driver header, possibly unused?)
 *
 * We also need an extra CLOCK_REALTIME fd which exists specifically to be
 * canceled on RTC changes.
 */
static const size_t ANDROID_ALARM_TYPE_COUNT = 5;
static const size_t N_ANDROID_TIMERFDS = ANDROID_ALARM_TYPE_COUNT + 1;
static const clockid_t android_alarm_to_clockid[N_ANDROID_TIMERFDS] = {
    CLOCK_REALTIME_ALARM,
    CLOCK_REALTIME,
    CLOCK_BOOTTIME_ALARM,
    CLOCK_BOOTTIME,
    CLOCK_MONOTONIC,
    CLOCK_REALTIME,
};

typedef std::array<int, N_ANDROID_TIMERFDS> TimerFds;

class AlarmImpl
{
public:
    AlarmImpl(const TimerFds &fds, int epollfd, int rtc_id) :
        fds{fds}, epollfd{epollfd}, rtc_id{rtc_id} { }
    ~AlarmImpl();

    int set(int type, struct timespec *ts);
    int setTime(struct timeval *tv);
    int waitForAlarm();

private:
    const TimerFds fds;
    const int epollfd;
    const int rtc_id;
};

AlarmImpl::~AlarmImpl()
{
    for (auto fd : fds) {
        epoll_ctl(epollfd, EPOLL_CTL_DEL, fd, nullptr);
        close(fd);
    }

    close(epollfd);
}

int AlarmImpl::set(int type, struct timespec *ts)
{
    if (static_cast<size_t>(type) > ANDROID_ALARM_TYPE_COUNT) {
        errno = EINVAL;
        return -1;
    }

    if (!ts->tv_nsec && !ts->tv_sec) {
        ts->tv_nsec = 1;
    }
    /* timerfd interprets 0 = disarm, so replace with a practically
       equivalent deadline of 1 ns */

    struct itimerspec spec;
    memset(&spec, 0, sizeof(spec));
    memcpy(&spec.it_value, ts, sizeof(spec.it_value));

    return timerfd_settime(fds[type], TFD_TIMER_ABSTIME, &spec, NULL);
}

int AlarmImpl::setTime(struct timeval *tv)
{
    struct rtc_time rtc;
    struct tm tm, *gmtime_res;
    int fd;
    int res;

    res = settimeofday(tv, NULL);
    if (res < 0) {
        ALOGV("settimeofday() failed: %s\n", strerror(errno));
        return -1;
    }

    if (rtc_id < 0) {
        ALOGV("Not setting RTC because wall clock RTC was not found");
        errno = ENODEV;
        return -1;
    }

    android::String8 rtc_dev = String8::format("/dev/rtc%d", rtc_id);
    fd = open(rtc_dev.string(), O_RDWR);
    if (fd < 0) {
        ALOGV("Unable to open %s: %s\n", rtc_dev.string(), strerror(errno));
        return res;
    }

    gmtime_res = gmtime_r(&tv->tv_sec, &tm);
    if (!gmtime_res) {
        ALOGV("gmtime_r() failed: %s\n", strerror(errno));
        res = -1;
        goto done;
    }

    memset(&rtc, 0, sizeof(rtc));
    rtc.tm_sec = tm.tm_sec;
    rtc.tm_min = tm.tm_min;
    rtc.tm_hour = tm.tm_hour;
    rtc.tm_mday = tm.tm_mday;
    rtc.tm_mon = tm.tm_mon;
    rtc.tm_year = tm.tm_year;
    rtc.tm_wday = tm.tm_wday;
    rtc.tm_yday = tm.tm_yday;
    rtc.tm_isdst = tm.tm_isdst;
    res = ioctl(fd, RTC_SET_TIME, &rtc);
    if (res < 0)
        ALOGV("RTC_SET_TIME ioctl failed: %s\n", strerror(errno));
done:
    close(fd);
    return res;
}

int AlarmImpl::waitForAlarm()
{
    epoll_event events[N_ANDROID_TIMERFDS];

    int nevents = epoll_wait(epollfd, events, N_ANDROID_TIMERFDS, -1);
    if (nevents < 0) {
        return nevents;
    }

    int result = 0;
    for (int i = 0; i < nevents; i++) {
        uint32_t alarm_idx = events[i].data.u32;
        uint64_t unused;
        ssize_t err = read(fds[alarm_idx], &unused, sizeof(unused));
        if (err < 0) {
            if (alarm_idx == ANDROID_ALARM_TYPE_COUNT && errno == ECANCELED) {
                result |= ANDROID_ALARM_TIME_CHANGE_MASK;
            } else {
                return err;
            }
        } else {
            result |= (1 << alarm_idx);
        }
    }

    return result;
}

static jint android_server_AlarmManagerService_setKernelTime(JNIEnv*, jobject, jlong nativeData, jlong millis)
{
    AlarmImpl *impl = reinterpret_cast<AlarmImpl *>(nativeData);
    struct timeval tv;
    int ret;

    if (millis <= 0 || millis / 1000LL >= INT_MAX) {
        return -1;
    }

    tv.tv_sec = (time_t) (millis / 1000LL);
    tv.tv_usec = (suseconds_t) ((millis % 1000LL) * 1000LL);

    ALOGD("Setting time of day to sec=%d\n", (int) tv.tv_sec);

    ret = impl->setTime(&tv);

    if(ret < 0) {
        ALOGW("Unable to set rtc to %ld: %s\n", tv.tv_sec, strerror(errno));
        ret = -1;
    }
    return ret;
}

static jint android_server_AlarmManagerService_setKernelTimezone(JNIEnv*, jobject, jlong, jint minswest)
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

static const char rtc_sysfs[] = "/sys/class/rtc";

static bool rtc_is_hctosys(unsigned int rtc_id)
{
    android::String8 hctosys_path = String8::format("%s/rtc%u/hctosys",
            rtc_sysfs, rtc_id);
    FILE *file = fopen(hctosys_path.string(), "re");
    if (!file) {
        ALOGE("failed to open %s: %s", hctosys_path.string(), strerror(errno));
        return false;
    }

    unsigned int hctosys;
    bool ret = false;
    int err = fscanf(file, "%u", &hctosys);
    if (err == EOF)
        ALOGE("failed to read from %s: %s", hctosys_path.string(),
                strerror(errno));
    else if (err == 0)
        ALOGE("%s did not have expected contents", hctosys_path.string());
    else
        ret = hctosys;

    fclose(file);
    return ret;
}

static int wall_clock_rtc()
{
    std::unique_ptr<DIR, int(*)(DIR*)> dir(opendir(rtc_sysfs), closedir);
    if (!dir.get()) {
        ALOGE("failed to open %s: %s", rtc_sysfs, strerror(errno));
        return -1;
    }

    struct dirent *dirent;
    while (errno = 0, dirent = readdir(dir.get())) {
        unsigned int rtc_id;
        int matched = sscanf(dirent->d_name, "rtc%u", &rtc_id);

        if (matched < 0)
            break;
        else if (matched != 1)
            continue;

        if (rtc_is_hctosys(rtc_id)) {
            ALOGV("found wall clock RTC %u", rtc_id);
            return rtc_id;
        }
    }

    if (errno == 0)
        ALOGW("no wall clock RTC found");
    else
        ALOGE("failed to enumerate RTCs: %s", strerror(errno));

    return -1;
}

static void log_timerfd_create_error(clockid_t id)
{
    if (errno == EINVAL) {
        switch (id) {
        case CLOCK_REALTIME_ALARM:
        case CLOCK_BOOTTIME_ALARM:
            ALOGE("kernel missing required commits:");
            ALOGE("https://git.kernel.org/cgit/linux/kernel/git/torvalds/linux.git/commit/?id=6cffe00f7d4e24679eae6b7aae4caaf915288256");
            ALOGE("https://git.kernel.org/cgit/linux/kernel/git/torvalds/linux.git/commit/?id=11ffa9d6065f344a9bd769a2452f26f2f671e5f8");
            LOG_ALWAYS_FATAL("kernel does not support timerfd_create() with alarm timers");
            break;

        case CLOCK_BOOTTIME:
            ALOGE("kernel missing required commit:");
            ALOGE("https://git.kernel.org/cgit/linux/kernel/git/torvalds/linux.git/commit/?id=4a2378a943f09907fb1ae35c15de917f60289c14");
            LOG_ALWAYS_FATAL("kernel does not support timerfd_create(CLOCK_BOOTTIME)");
            break;

        default:
            break;
        }
    }

    ALOGE("timerfd_create(%u) failed: %s", id, strerror(errno));
}

static jlong android_server_AlarmManagerService_init(JNIEnv*, jobject)
{
    int epollfd;
    TimerFds fds;

    epollfd = epoll_create(fds.size());
    if (epollfd < 0) {
        ALOGE("epoll_create(%zu) failed: %s", fds.size(),
                strerror(errno));
        return 0;
    }

    for (size_t i = 0; i < fds.size(); i++) {
        fds[i] = timerfd_create(android_alarm_to_clockid[i], 0);
        if (fds[i] < 0) {
            log_timerfd_create_error(android_alarm_to_clockid[i]);
            close(epollfd);
            for (size_t j = 0; j < i; j++) {
                close(fds[j]);
            }
            return 0;
        }
    }

    AlarmImpl *ret = new AlarmImpl(fds, epollfd, wall_clock_rtc());

    for (size_t i = 0; i < fds.size(); i++) {
        epoll_event event;
        event.events = EPOLLIN | EPOLLWAKEUP;
        event.data.u32 = i;

        int err = epoll_ctl(epollfd, EPOLL_CTL_ADD, fds[i], &event);
        if (err < 0) {
            ALOGE("epoll_ctl(EPOLL_CTL_ADD) failed: %s", strerror(errno));
            delete ret;
            return 0;
        }
    }

    struct itimerspec spec;
    memset(&spec, 0, sizeof(spec));
    /* 0 = disarmed; the timerfd doesn't need to be armed to get
       RTC change notifications, just set up as cancelable */

    int err = timerfd_settime(fds[ANDROID_ALARM_TYPE_COUNT],
            TFD_TIMER_ABSTIME | TFD_TIMER_CANCEL_ON_SET, &spec, NULL);
    if (err < 0) {
        ALOGE("timerfd_settime() failed: %s", strerror(errno));
        delete ret;
        return 0;
    }

    return reinterpret_cast<jlong>(ret);
}

static void android_server_AlarmManagerService_close(JNIEnv*, jobject, jlong nativeData)
{
    AlarmImpl *impl = reinterpret_cast<AlarmImpl *>(nativeData);
    delete impl;
}

static jint android_server_AlarmManagerService_set(JNIEnv*, jobject, jlong nativeData, jint type, jlong seconds, jlong nanoseconds)
{
    AlarmImpl *impl = reinterpret_cast<AlarmImpl *>(nativeData);
    struct timespec ts;
    ts.tv_sec = seconds;
    ts.tv_nsec = nanoseconds;

    const int result = impl->set(type, &ts);
    if (result < 0)
    {
        ALOGE("Unable to set alarm to %lld.%09lld: %s\n",
              static_cast<long long>(seconds),
              static_cast<long long>(nanoseconds), strerror(errno));
    }
    return result >= 0 ? 0 : errno;
}

static jint android_server_AlarmManagerService_waitForAlarm(JNIEnv*, jobject, jlong nativeData)
{
    AlarmImpl *impl = reinterpret_cast<AlarmImpl *>(nativeData);
    int result = 0;

    do
    {
        result = impl->waitForAlarm();
    } while (result < 0 && errno == EINTR);

    if (result < 0)
    {
        ALOGE("Unable to wait on alarm: %s\n", strerror(errno));
        return 0;
    }

    return result;
}

static const JNINativeMethod sMethods[] = {
     /* name, signature, funcPtr */
    {"init", "()J", (void*)android_server_AlarmManagerService_init},
    {"close", "(J)V", (void*)android_server_AlarmManagerService_close},
    {"set", "(JIJJ)I", (void*)android_server_AlarmManagerService_set},
    {"waitForAlarm", "(J)I", (void*)android_server_AlarmManagerService_waitForAlarm},
    {"setKernelTime", "(JJ)I", (void*)android_server_AlarmManagerService_setKernelTime},
    {"setKernelTimezone", "(JI)I", (void*)android_server_AlarmManagerService_setKernelTimezone},
};

int register_android_server_AlarmManagerService(JNIEnv* env)
{
    return jniRegisterNativeMethods(env, "com/android/server/AlarmManagerService",
                                    sMethods, NELEM(sMethods));
}

} /* namespace android */
