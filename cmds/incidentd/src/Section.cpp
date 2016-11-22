/*
 * Copyright (C) 2016 The Android Open Source Project
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

#define LOG_TAG "incidentd"

#include "Section.h"
#include "protobuf.h"

#include <binder/IServiceManager.h>
#include <mutex>

using namespace std;

const int64_t REMOTE_CALL_TIMEOUT_MS = 10 * 1000; // 10 seconds

// ================================================================================
Section::Section(int i)
    :id(i)
{
}

Section::~Section()
{
}

status_t
Section::WriteHeader(ReportRequestSet* requests, size_t size) const
{
    ssize_t amt;
    uint8_t buf[20];
    uint8_t* p = write_length_delimited_tag_header(buf, this->id, size);
    return requests->write(buf, p-buf);
}

// ================================================================================
struct WorkerThreadData : public virtual RefBase
{
    const WorkerThreadSection* section;
    int fds[2];

    // Lock protects these fields
    mutex lock;
    bool workerDone;
    status_t workerError;

    WorkerThreadData(const WorkerThreadSection* section);
    virtual ~WorkerThreadData();

    int readFd() { return fds[0]; }
    int writeFd() { return fds[1]; }
};

WorkerThreadData::WorkerThreadData(const WorkerThreadSection* sec)
    :section(sec),
     workerDone(false),
     workerError(NO_ERROR)
{
    fds[0] = -1;
    fds[1] = -1;
}

WorkerThreadData::~WorkerThreadData()
{
}

// ================================================================================
WorkerThreadSection::WorkerThreadSection(int id)
    :Section(id)
{
}

WorkerThreadSection::~WorkerThreadSection()
{
}

static void*
worker_thread_func(void* cookie)
{
    WorkerThreadData* data = (WorkerThreadData*)cookie;
    status_t err = data->section->BlockingCall(data->writeFd());

    {
        unique_lock<mutex> lock(data->lock);
        data->workerDone = true;
        data->workerError = err;
    }

    close(data->writeFd());
    data->decStrong(data->section);
    // data might be gone now. don't use it after this point in this thread.
    return NULL;
}

status_t
WorkerThreadSection::Execute(ReportRequestSet* requests) const
{
    status_t err = NO_ERROR;
    pthread_t thread;
    pthread_attr_t attr;
    bool timedOut = false;
    FdBuffer buffer;

    // Data shared between this thread and the worker thread.
    sp<WorkerThreadData> data = new WorkerThreadData(this);

    // Create the pipe
    err = pipe(data->fds);
    if (err != 0) {
        return -errno;
    }

    // The worker thread needs a reference and we can't let the count go to zero
    // if that thread is slow to start.
    data->incStrong(this);

    // Create the thread
    err = pthread_attr_init(&attr);
    if (err != 0) {
        return -err;
    }
    // TODO: Do we need to tweak thread priority?
    err = pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    if (err != 0) {
        pthread_attr_destroy(&attr);
        return -err;
    }
    err = pthread_create(&thread, &attr, worker_thread_func, (void*)data.get());
    if (err != 0) {
        pthread_attr_destroy(&attr);
        return -err;
    }
    pthread_attr_destroy(&attr);

    // Loop reading until either the timeout or the worker side is done (i.e. eof).
    err = buffer.read(data->readFd(), REMOTE_CALL_TIMEOUT_MS);
    if (err != NO_ERROR) {
        // TODO: Log this error into the incident report.
        ALOGW("WorkerThreadSection '%s' reader failed with error '%s'", this->name.string(),
                strerror(-err));
    }

    // Done with the read fd. The worker thread closes the write one so
    // we never race and get here first.
    close(data->readFd());

    // If the worker side is finished, then return its error (which may overwrite
    // our possible error -- but it's more interesting anyway).  If not, then we timed out.
    {
        unique_lock<mutex> lock(data->lock);
        if (!data->workerDone) {
            // We timed out
            timedOut = true;
        } else {
            if (data->workerError != NO_ERROR) {
                err = data->workerError;
                // TODO: Log this error into the incident report.
                ALOGW("WorkerThreadSection '%s' worker failed with error '%s'", this->name.string(),
                        strerror(-err));
            }
        }
    }

    if (timedOut || buffer.timedOut()) {
        ALOGW("WorkerThreadSection '%s' timed out", this->name.string());
        return NO_ERROR;
    }

    if (buffer.truncated()) {
        // TODO: Log this into the incident report.
    }

    // TODO: There was an error with the command or buffering. Report that.  For now
    // just exit with a log messasge.
    if (err != NO_ERROR) {
        ALOGW("WorkerThreadSection '%s' failed with error '%s'", this->name.string(),
                strerror(-err));
        return NO_ERROR;
    }

    // Write the data that was collected
    ALOGD("section '%s' wrote %zd bytes in %d ms", name.string(), buffer.size(),
            (int)buffer.durationMs());
    WriteHeader(requests, buffer.size());
    err = buffer.write(requests);
    if (err != NO_ERROR) {
        ALOGW("WorkerThreadSection '%s' failed writing: '%s'", this->name.string(), strerror(-err));
        return err;
    }

    return NO_ERROR;
}

// ================================================================================
CommandSection::CommandSection(int id, const char* first, ...)
    :Section(id)
{
    va_list args;
    int count = 0;

    va_start(args, first);
    while (va_arg(args, const char*) != NULL) {
        count++;
    }
    va_end(args);

    mCommand = (const char**)malloc(sizeof(const char*) * count);

    mCommand[0] = first;
    name = first;
    name += " ";
    va_start(args, first);
    for (int i=0; i<count; i++) {
        const char* arg = va_arg(args, const char*); 
        mCommand[i+1] = arg;
        if (arg != NULL) {
            name += va_arg(args, const char*);
            name += " ";
        }
    }
    va_end(args);
}

CommandSection::~CommandSection()
{
}

status_t
CommandSection::Execute(ReportRequestSet* /*requests*/) const
{
    return NO_ERROR;
}

// ================================================================================
DumpsysSection::DumpsysSection(int id, const char* service, ...)
    :WorkerThreadSection(id),
     mService(service)
{
    name = "dumpsys ";
    name += service;

    va_list args;
    va_start(args, service);
    while (true) {
        const char* arg = va_arg(args, const char*); 
        if (arg == NULL) {
            break;
        }
        mArgs.add(String16(arg));
        name += " ";
        name += arg;
    }
    va_end(args);
}

DumpsysSection::~DumpsysSection()
{
}

status_t
DumpsysSection::BlockingCall(int pipeWriteFd) const
{
    // checkService won't wait for the service to show up like getService will.
    sp<IBinder> service = defaultServiceManager()->checkService(mService);
    
    if (service == NULL) {
        // Returning an error interrupts the entire incident report, so just
        // log the failure.
        // TODO: have a meta record inside the report that would log this
        // failure inside the report, because the fact that we can't find
        // the service is good data in and of itself. This is running in
        // another thread so lock that carefully...
        ALOGW("DumpsysSection: Can't lookup service: %s", String8(mService).string());
        return NO_ERROR;
    }

    service->dump(pipeWriteFd, mArgs);

    return NO_ERROR;
}
