/*
 * Copyright (C) 2008 The Android Open Source Project
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

#include <utils/TimerProbe.h>
 
#if ENABLE_TIMER_PROBE

#ifdef LOG_TAG
#undef LOG_TAG
#endif
#define LOG_TAG "time"

namespace android {

Vector<TimerProbe::Bucket> TimerProbe::gBuckets;
TimerProbe* TimerProbe::gExecuteChain;
int TimerProbe::gIndent;
timespec TimerProbe::gRealBase;

TimerProbe::TimerProbe(const char tag[], int* slot) : mTag(tag)
{
    mNext = gExecuteChain;
    gExecuteChain = this;
    mIndent = gIndent;
    gIndent += 1;
    if (mIndent > 0) {
        if (*slot == 0) {
            int count = gBuckets.add();
            *slot = count;
            Bucket& bucket = gBuckets.editItemAt(count);
            memset(&bucket, 0, sizeof(Bucket));
            bucket.mTag = tag;
            bucket.mSlotPtr = slot;
            bucket.mIndent = mIndent;
        }
        mBucket = *slot;
    }
    clock_gettime(CLOCK_REALTIME, &mRealStart);
    if (gRealBase.tv_sec == 0)
        gRealBase = mRealStart;
    clock_gettime(CLOCK_PROCESS_CPUTIME_ID, &mPStart);
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &mTStart);
}

void TimerProbe::end()
{
    timespec realEnd, pEnd, tEnd;
    clock_gettime(CLOCK_REALTIME, &realEnd);
    clock_gettime(CLOCK_PROCESS_CPUTIME_ID, &pEnd);
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &tEnd);
    print(realEnd, pEnd, tEnd);
    mTag = NULL;
}

TimerProbe::~TimerProbe()
{
    if (mTag != NULL)
        end();
    gExecuteChain = mNext;
    gIndent--;
}


uint32_t TimerProbe::ElapsedTime(const timespec& start, const timespec& end)
{
    int sec = end.tv_sec - start.tv_sec;
    int nsec = end.tv_nsec - start.tv_nsec;
    if (nsec < 0) {
        sec--;
        nsec += 1000000000;
    }
    return sec * 1000000 + nsec / 1000;
}

void TimerProbe::print(const timespec& r, const timespec& p,
        const timespec& t) const
{
    uint32_t es = ElapsedTime(gRealBase, mRealStart);
    uint32_t er = ElapsedTime(mRealStart, r);
    uint32_t ep = ElapsedTime(mPStart, p);
    uint32_t et = ElapsedTime(mTStart, t);
    if (mIndent > 0) {
        Bucket& bucket = gBuckets.editItemAt(mBucket);
        if (bucket.mStart == 0)
            bucket.mStart = es;
        bucket.mReal += er;
        bucket.mProcess += ep;
        bucket.mThread += et;
        bucket.mCount++;
        return;
    }
    int index = 0;
    int buckets = gBuckets.size();
    int count = 1;
    const char* tag = mTag;
    int indent = mIndent;
    do {
        LOGD("%-30.30s: (%3d) %-5.*s time=%-10.3f real=%7dus process=%7dus (%3d%%) thread=%7dus (%3d%%)\n", 
            tag, count, indent > 5 ? 5 : indent, "+++++", es / 1000000.0,
            er, ep, ep * 100 / er, et, et * 100 / er);
        if (index >= buckets)
            break;
        Bucket& bucket = gBuckets.editItemAt(index);
        count = bucket.mCount;
        es = bucket.mStart;
        er = bucket.mReal;
        ep = bucket.mProcess;
        et = bucket.mThread;
        tag = bucket.mTag;
        indent = bucket.mIndent;
        *bucket.mSlotPtr = 0;
    } while (++index); // always true
    gBuckets.clear();
}

}; // namespace android

#endif
