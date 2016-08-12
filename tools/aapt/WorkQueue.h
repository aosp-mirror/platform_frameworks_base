/*]
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

#ifndef AAPT_WORK_QUEUE_H
#define AAPT_WORK_QUEUE_H

#include <utils/Errors.h>
#include <utils/Vector.h>
#include <utils/threads.h>

namespace android {

/*
 * A threaded work queue.
 *
 * This class is designed to make it easy to run a bunch of isolated work
 * units in parallel, using up to the specified number of threads.
 * To use it, write a loop to post work units to the work queue, then synchronize
 * on the queue at the end.
 */
class WorkQueue {
public:
    class WorkUnit {
    public:
        WorkUnit() { }
        virtual ~WorkUnit() { }

        /*
         * Runs the work unit.
         * If the result is 'true' then the work queue continues scheduling work as usual.
         * If the result is 'false' then the work queue is canceled.
         */
        virtual bool run() = 0;
    };

    /* Creates a work queue with the specified maximum number of work threads. */
    explicit WorkQueue(size_t maxThreads, bool canCallJava = true);

    /* Destroys the work queue.
     * Cancels pending work and waits for all remaining threads to complete.
     */
    ~WorkQueue();

    /* Posts a work unit to run later.
     * If the work queue has been canceled or is already finished, returns INVALID_OPERATION
     * and does not take ownership of the work unit (caller must destroy it itself).
     * Otherwise, returns OK and takes ownership of the work unit (the work queue will
     * destroy it automatically).
     *
     * For flow control, this method blocks when the size of the pending work queue is more
     * 'backlog' times the number of threads.  This condition reduces the rate of entry into
     * the pending work queue and prevents it from growing much more rapidly than the
     * work threads can actually handle.
     *
     * If 'backlog' is 0, then no throttle is applied.
     */
    status_t schedule(WorkUnit* workUnit, size_t backlog = 2);

    /* Cancels all pending work.
     * If the work queue is already finished, returns INVALID_OPERATION.
     * If the work queue is already canceled, returns OK and does nothing else.
     * Otherwise, returns OK, discards all pending work units and prevents additional
     * work units from being scheduled.
     *
     * Call finish() after cancel() to wait for all remaining work to complete.
     */
    status_t cancel();

    /* Waits for all work to complete.
     * If the work queue is already finished, returns INVALID_OPERATION.
     * Otherwise, waits for all work to complete and returns OK.
     */
    status_t finish();

private:
    class WorkThread : public Thread {
    public:
        WorkThread(WorkQueue* workQueue, bool canCallJava);
        virtual ~WorkThread();

    private:
        virtual bool threadLoop();

        WorkQueue* const mWorkQueue;
    };

    status_t cancelLocked();
    bool threadLoop(); // called from each work thread

    const size_t mMaxThreads;
    const bool mCanCallJava;

    Mutex mLock;
    Condition mWorkChangedCondition;
    Condition mWorkDequeuedCondition;

    bool mCanceled;
    bool mFinished;
    size_t mIdleThreads;
    Vector<sp<WorkThread> > mWorkThreads;
    Vector<WorkUnit*> mWorkUnits;
};

}; // namespace android

#endif // AAPT_WORK_QUEUE_H
