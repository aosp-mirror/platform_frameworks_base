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

#define LOG_TAG "common_time"
#include <utils/Log.h>

#include "utils.h"

namespace android {

void Timeout::setTimeout(int msec) {
    if (msec < 0) {
        mSystemEndTime = 0;
        return;
    }

    mSystemEndTime = systemTime() + (static_cast<nsecs_t>(msec) * 1000000);
}

int Timeout::msecTillTimeout(nsecs_t nowTime) {
    if (!mSystemEndTime) {
        return -1;
    }

    if (mSystemEndTime < nowTime) {
        return 0;
    }

    nsecs_t delta = mSystemEndTime - nowTime;
    delta += 999999;
    delta /= 1000000;
    if (delta > 0x7FFFFFFF) {
        return 0x7FFFFFFF;
    }

    return static_cast<int>(delta);
}

LogRing::LogRing(const char* header, size_t entries)
    : mSize(entries)
    , mWr(0)
    , mIsFull(false)
    , mHeader(header) {
    mRingBuffer = new Entry[mSize];
    if (NULL == mRingBuffer)
        ALOGE("Failed to allocate log ring with %zu entries.", mSize);
}

LogRing::~LogRing() {
    if (NULL != mRingBuffer)
        delete[] mRingBuffer;
}

void LogRing::log(int prio, const char* tag, const char* fmt, ...) {
    va_list argp;
    va_start(argp, fmt);
    internalLog(prio, tag, fmt, argp);
    va_end(argp);
}

void LogRing::log(const char* fmt, ...) {
    va_list argp;
    va_start(argp, fmt);
    internalLog(0, NULL, fmt, argp);
    va_end(argp);
}

void LogRing::internalLog(int prio,
                          const char* tag,
                          const char* fmt,
                          va_list argp) {
    if (NULL != mRingBuffer) {
        Mutex::Autolock lock(&mLock);
        String8 s(String8::formatV(fmt, argp));
        Entry* last = NULL;

        if (mIsFull || mWr)
            last = &(mRingBuffer[(mWr + mSize - 1) % mSize]);


        if ((NULL != last) && !last->s.compare(s)) {
            gettimeofday(&(last->last_ts), NULL);
            ++last->count;
        } else {
            gettimeofday(&mRingBuffer[mWr].first_ts, NULL);
            mRingBuffer[mWr].last_ts = mRingBuffer[mWr].first_ts;
            mRingBuffer[mWr].count = 1;
            mRingBuffer[mWr].s.setTo(s);

            mWr = (mWr + 1) % mSize;
            if (!mWr)
                mIsFull = true;
        }
    }

    if (NULL != tag)
        LOG_PRI_VA(prio, tag, fmt, argp);
}

void LogRing::dumpLog(int fd) {
    if (NULL == mRingBuffer)
        return;

    Mutex::Autolock lock(&mLock);

    if (!mWr && !mIsFull)
        return;

    char buf[1024];
    int res;
    size_t start = mIsFull ? mWr : 0;
    size_t count = mIsFull ? mSize : mWr;
    static const char* kTimeFmt = "%a %b %d %Y %H:%M:%S";

    res = snprintf(buf, sizeof(buf), "\n%s\n", mHeader);
    if (res > 0)
        write(fd, buf, res);

    for (size_t i = 0; i < count; ++i) {
        struct tm t;
        char timebuf[64];
        char repbuf[96];
        size_t ndx = (start + i) % mSize;

        if (1 != mRingBuffer[ndx].count) {
            localtime_r(&mRingBuffer[ndx].last_ts.tv_sec, &t);
            strftime(timebuf, sizeof(timebuf), kTimeFmt, &t);
            snprintf(repbuf, sizeof(repbuf),
                    " (repeated %d times, last was %s.%03ld)",
                     mRingBuffer[ndx].count,
                     timebuf,
                     mRingBuffer[ndx].last_ts.tv_usec / 1000);
            repbuf[sizeof(repbuf) - 1] = 0;
        } else {
            repbuf[0] = 0;
        }

        localtime_r(&mRingBuffer[ndx].first_ts.tv_sec, &t);
        strftime(timebuf, sizeof(timebuf), kTimeFmt, &t);
        res = snprintf(buf, sizeof(buf), "[%2zu] %s.%03ld :: %s%s\n",
                       i, timebuf,
                       mRingBuffer[ndx].first_ts.tv_usec / 1000,
                       mRingBuffer[ndx].s.string(),
                       repbuf);

        if (res > 0)
            write(fd, buf, res);
    }
}

}  // namespace android
