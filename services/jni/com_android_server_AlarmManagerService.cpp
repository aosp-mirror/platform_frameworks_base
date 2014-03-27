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
#include <linux/android_alarm.h>
#include <linux/rtc.h>

namespace android {

static const size_t N_ANDROID_TIMERFDS = ANDROID_ALARM_TYPE_COUNT + 1;
static const clockid_t android_alarm_to_clockid[N_ANDROID_TIMERFDS] = {
    CLOCK_REALTIME_ALARM,
    CLOCK_REALTIME,
    CLOCK_BOOTTIME_ALARM,
    CLOCK_BOOTTIME,
    CLOCK_MONOTONIC,
    CLOCK_REALTIME,
};
/* to match the legacy alarm driver implementation, we need an extra
   CLOCK_REALTIME fd which exists specifically to be canceled on RTC changes */

class AlarmImpl
{
public:
    AlarmImpl(int *fds, size_t n_fds);
    virtual ~AlarmImpl();

    virtual int set(int type, struct timespec *ts) = 0;
    virtual int setTime(struct timeval *tv) = 0;
    virtual int waitForAlarm() = 0;

protected:
    int *fds;
    size_t n_fds;
};

class AlarmImplAlarmDriver : public AlarmImpl
{
public:
    AlarmImplAlarmDriver(int fd) : AlarmImpl(&fd, 1) { }

    int set(int type, struct timespec *ts);
    int setTime(struct timeval *tv);
    int waitForAlarm();
};

class AlarmImplTimerFd : public AlarmImpl
{
public:
    AlarmImplTimerFd(int fds[N_ANDROID_TIMERFDS], int epollfd) :
        AlarmImpl(fds, N_ANDROID_TIMERFDS), epollfd(epollfd) { }
    ~AlarmImplTimerFd();

    int set(int type, struct timespec *ts);
    int setTime(struct timeval *tv);
    int waitForAlarm();

private:
    int epollfd;
};

AlarmImpl::AlarmImpl(int *fds_, size_t n_fds) : fds(new int[n_fds]),
        n_fds(n_fds)
{
    memcpy(fds, fds_, n_fds * sizeof(fds[0]));
}

AlarmImpl::~AlarmImpl()
{
    for (size_t i = 0; i < n_fds; i++) {
        close(fds[i]);
    }
    delete [] fds;
}

int AlarmImplAlarmDriver::set(int type, struct timespec *ts)
{
    return ioctl(fds[0], ANDROID_ALARM_SET(type), ts);
}

int AlarmImplAlarmDriver::setTime(struct timeval *tv)
{
    struct timespec ts;
    int res;

    ts.tv_sec = tv->tv_sec;
    ts.tv_nsec = tv->tv_usec * 1000;
    res = ioctl(fds[0], ANDROID_ALARM_SET_RTC, &ts);
    if (res < 0)
        ALOGV("ANDROID_ALARM_SET_RTC ioctl failed: %s\n", strerror(errno));
    return res;
}

int AlarmImplAlarmDriver::waitForAlarm()
{
    return ioctl(fds[0], ANDROID_ALARM_WAIT);
}

AlarmImplTimerFd::~AlarmImplTimerFd()
{
    for (size_t i = 0; i < N_ANDROID_TIMERFDS; i++) {
        epoll_ctl(epollfd, EPOLL_CTL_DEL, fds[i], NULL);
    }
    close(epollfd);
}

int AlarmImplTimerFd::set(int type, struct timespec *ts)
{
    if (type > ANDROID_ALARM_TYPE_COUNT) {
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

int AlarmImplTimerFd::setTime(struct timeval *tv)
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

    fd = open("/dev/rtc0", O_RDWR);
    if (fd < 0) {
        ALOGV("Unable to open RTC driver: %s\n", strerror(errno));
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

int AlarmImplTimerFd::waitForAlarm()
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

static jlong init_alarm_driver()
{
    int fd = open("/dev/alarm", O_RDWR);
    if (fd < 0) {
        ALOGV("opening alarm driver failed: %s", strerror(errno));
        return 0;
    }

    AlarmImpl *ret = new AlarmImplAlarmDriver(fd);
    return reinterpret_cast<jlong>(ret);
}

static jlong init_timerfd()
{
    int epollfd;
    int fds[N_ANDROID_TIMERFDS];

    epollfd = epoll_create(N_ANDROID_TIMERFDS);
    if (epollfd < 0) {
        ALOGV("epoll_create(%u) failed: %s", N_ANDROID_TIMERFDS,
                strerror(errno));
        return 0;
    }

    for (size_t i = 0; i < N_ANDROID_TIMERFDS; i++) {
        fds[i] = timerfd_create(android_alarm_to_clockid[i], 0);
        if (fds[i] < 0) {
            ALOGV("timerfd_create(%u) failed: %s",  android_alarm_to_clockid[i],
                    strerror(errno));
            close(epollfd);
            for (size_t j = 0; j < i; j++) {
                close(fds[j]);
            }
            return 0;
        }
    }

    AlarmImpl *ret = new AlarmImplTimerFd(fds, epollfd);

    for (size_t i = 0; i < N_ANDROID_TIMERFDS; i++) {
        epoll_event event;
        event.events = EPOLLIN | EPOLLWAKEUP;
        event.data.u32 = i;

        int err = epoll_ctl(epollfd, EPOLL_CTL_ADD, fds[i], &event);
        if (err < 0) {
            ALOGV("epoll_ctl(EPOLL_CTL_ADD) failed: %s", strerror(errno));
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
        ALOGV("timerfd_settime() failed: %s", strerror(errno));
        delete ret;
        return 0;
    }

    return reinterpret_cast<jlong>(ret);
}

static jlong android_server_AlarmManagerService_init(JNIEnv*, jobject)
{
    jlong ret = init_alarm_driver();
    if (ret) {
        return ret;
    }

    return init_timerfd();
}

static void android_server_AlarmManagerService_close(JNIEnv*, jobject, jlong nativeData)
{
    AlarmImpl *impl = reinterpret_cast<AlarmImpl *>(nativeData);
    delete impl;
}

static void android_server_AlarmManagerService_set(JNIEnv*, jobject, jlong nativeData, jint type, jlong seconds, jlong nanoseconds)
{
    AlarmImpl *impl = reinterpret_cast<AlarmImpl *>(nativeData);
    struct timespec ts;
    ts.tv_sec = seconds;
    ts.tv_nsec = nanoseconds;

    int result = impl->set(type, &ts);
    if (result < 0)
    {
        ALOGE("Unable to set alarm to %lld.%09lld: %s\n",
              static_cast<long long>(seconds),
              static_cast<long long>(nanoseconds), strerror(errno));
    }
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

static JNINativeMethod sMethods[] = {
     /* name, signature, funcPtr */
    {"init", "()J", (void*)android_server_AlarmManagerService_init},
    {"close", "(J)V", (void*)android_server_AlarmManagerService_close},
    {"set", "(JIJJ)V", (void*)android_server_AlarmManagerService_set},
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
