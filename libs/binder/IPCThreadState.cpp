/*
 * Copyright (C) 2005 The Android Open Source Project
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

#define LOG_TAG "IPCThreadState"

#include <binder/IPCThreadState.h>

#include <binder/Binder.h>
#include <binder/BpBinder.h>
#include <cutils/sched_policy.h>
#include <utils/Debug.h>
#include <utils/Log.h>
#include <utils/TextOutput.h>
#include <utils/threads.h>

#include <private/binder/binder_module.h>
#include <private/binder/Static.h>

#include <sys/ioctl.h>
#include <signal.h>
#include <errno.h>
#include <stdio.h>
#include <unistd.h>

#ifdef HAVE_PTHREADS
#include <pthread.h>
#include <sched.h>
#include <sys/resource.h>
#endif
#ifdef HAVE_WIN32_THREADS
#include <windows.h>
#endif


#if LOG_NDEBUG

#define IF_LOG_TRANSACTIONS() if (false)
#define IF_LOG_COMMANDS() if (false)
#define LOG_REMOTEREFS(...) 
#define IF_LOG_REMOTEREFS() if (false)
#define LOG_THREADPOOL(...) 
#define LOG_ONEWAY(...) 

#else

#define IF_LOG_TRANSACTIONS() IF_ALOG(LOG_VERBOSE, "transact")
#define IF_LOG_COMMANDS() IF_ALOG(LOG_VERBOSE, "ipc")
#define LOG_REMOTEREFS(...) ALOG(LOG_DEBUG, "remoterefs", __VA_ARGS__)
#define IF_LOG_REMOTEREFS() IF_ALOG(LOG_DEBUG, "remoterefs")
#define LOG_THREADPOOL(...) ALOG(LOG_DEBUG, "threadpool", __VA_ARGS__)
#define LOG_ONEWAY(...) ALOG(LOG_DEBUG, "ipc", __VA_ARGS__)

#endif

// ---------------------------------------------------------------------------

namespace android {

static const char* getReturnString(size_t idx);
static const char* getCommandString(size_t idx);
static const void* printReturnCommand(TextOutput& out, const void* _cmd);
static const void* printCommand(TextOutput& out, const void* _cmd);

// This will result in a missing symbol failure if the IF_LOG_COMMANDS()
// conditionals don't get stripped...  but that is probably what we want.
#if !LOG_NDEBUG
static const char *kReturnStrings[] = {
    "BR_ERROR",
    "BR_OK",
    "BR_TRANSACTION",
    "BR_REPLY",
    "BR_ACQUIRE_RESULT",
    "BR_DEAD_REPLY",
    "BR_TRANSACTION_COMPLETE",
    "BR_INCREFS",
    "BR_ACQUIRE",
    "BR_RELEASE",
    "BR_DECREFS",
    "BR_ATTEMPT_ACQUIRE",
    "BR_NOOP",
    "BR_SPAWN_LOOPER",
    "BR_FINISHED",
    "BR_DEAD_BINDER",
    "BR_CLEAR_DEATH_NOTIFICATION_DONE",
    "BR_FAILED_REPLY"
};

static const char *kCommandStrings[] = {
    "BC_TRANSACTION",
    "BC_REPLY",
    "BC_ACQUIRE_RESULT",
    "BC_FREE_BUFFER",
    "BC_INCREFS",
    "BC_ACQUIRE",
    "BC_RELEASE",
    "BC_DECREFS",
    "BC_INCREFS_DONE",
    "BC_ACQUIRE_DONE",
    "BC_ATTEMPT_ACQUIRE",
    "BC_REGISTER_LOOPER",
    "BC_ENTER_LOOPER",
    "BC_EXIT_LOOPER",
    "BC_REQUEST_DEATH_NOTIFICATION",
    "BC_CLEAR_DEATH_NOTIFICATION",
    "BC_DEAD_BINDER_DONE"
};

static const char* getReturnString(size_t idx)
{
    if (idx < sizeof(kReturnStrings) / sizeof(kReturnStrings[0]))
        return kReturnStrings[idx];
    else
        return "unknown";
}

static const char* getCommandString(size_t idx)
{
    if (idx < sizeof(kCommandStrings) / sizeof(kCommandStrings[0]))
        return kCommandStrings[idx];
    else
        return "unknown";
}

static const void* printBinderTransactionData(TextOutput& out, const void* data)
{
    const binder_transaction_data* btd =
        (const binder_transaction_data*)data;
    if (btd->target.handle < 1024) {
        /* want to print descriptors in decimal; guess based on value */
        out << "target.desc=" << btd->target.handle;
    } else {
        out << "target.ptr=" << btd->target.ptr;
    }
    out << " (cookie " << btd->cookie << ")" << endl
        << "code=" << TypeCode(btd->code) << ", flags=" << (void*)btd->flags << endl
        << "data=" << btd->data.ptr.buffer << " (" << (void*)btd->data_size
        << " bytes)" << endl
        << "offsets=" << btd->data.ptr.offsets << " (" << (void*)btd->offsets_size
        << " bytes)";
    return btd+1;
}

static const void* printReturnCommand(TextOutput& out, const void* _cmd)
{
    static const size_t N = sizeof(kReturnStrings)/sizeof(kReturnStrings[0]);
    const int32_t* cmd = (const int32_t*)_cmd;
    int32_t code = *cmd++;
    size_t cmdIndex = code & 0xff;
    if (code == (int32_t) BR_ERROR) {
        out << "BR_ERROR: " << (void*)(*cmd++) << endl;
        return cmd;
    } else if (cmdIndex >= N) {
        out << "Unknown reply: " << code << endl;
        return cmd;
    }
    out << kReturnStrings[cmdIndex];
    
    switch (code) {
        case BR_TRANSACTION:
        case BR_REPLY: {
            out << ": " << indent;
            cmd = (const int32_t *)printBinderTransactionData(out, cmd);
            out << dedent;
        } break;
        
        case BR_ACQUIRE_RESULT: {
            const int32_t res = *cmd++;
            out << ": " << res << (res ? " (SUCCESS)" : " (FAILURE)");
        } break;
        
        case BR_INCREFS:
        case BR_ACQUIRE:
        case BR_RELEASE:
        case BR_DECREFS: {
            const int32_t b = *cmd++;
            const int32_t c = *cmd++;
            out << ": target=" << (void*)b << " (cookie " << (void*)c << ")";
        } break;
    
        case BR_ATTEMPT_ACQUIRE: {
            const int32_t p = *cmd++;
            const int32_t b = *cmd++;
            const int32_t c = *cmd++;
            out << ": target=" << (void*)b << " (cookie " << (void*)c
                << "), pri=" << p;
        } break;

        case BR_DEAD_BINDER:
        case BR_CLEAR_DEATH_NOTIFICATION_DONE: {
            const int32_t c = *cmd++;
            out << ": death cookie " << (void*)c;
        } break;

        default:
            // no details to show for: BR_OK, BR_DEAD_REPLY,
            // BR_TRANSACTION_COMPLETE, BR_FINISHED
            break;
    }
    
    out << endl;
    return cmd;
}

static const void* printCommand(TextOutput& out, const void* _cmd)
{
    static const size_t N = sizeof(kCommandStrings)/sizeof(kCommandStrings[0]);
    const int32_t* cmd = (const int32_t*)_cmd;
    int32_t code = *cmd++;
    size_t cmdIndex = code & 0xff;

    if (cmdIndex >= N) {
        out << "Unknown command: " << code << endl;
        return cmd;
    }
    out << kCommandStrings[cmdIndex];

    switch (code) {
        case BC_TRANSACTION:
        case BC_REPLY: {
            out << ": " << indent;
            cmd = (const int32_t *)printBinderTransactionData(out, cmd);
            out << dedent;
        } break;
        
        case BC_ACQUIRE_RESULT: {
            const int32_t res = *cmd++;
            out << ": " << res << (res ? " (SUCCESS)" : " (FAILURE)");
        } break;
        
        case BC_FREE_BUFFER: {
            const int32_t buf = *cmd++;
            out << ": buffer=" << (void*)buf;
        } break;
        
        case BC_INCREFS:
        case BC_ACQUIRE:
        case BC_RELEASE:
        case BC_DECREFS: {
            const int32_t d = *cmd++;
            out << ": desc=" << d;
        } break;
    
        case BC_INCREFS_DONE:
        case BC_ACQUIRE_DONE: {
            const int32_t b = *cmd++;
            const int32_t c = *cmd++;
            out << ": target=" << (void*)b << " (cookie " << (void*)c << ")";
        } break;
        
        case BC_ATTEMPT_ACQUIRE: {
            const int32_t p = *cmd++;
            const int32_t d = *cmd++;
            out << ": desc=" << d << ", pri=" << p;
        } break;
        
        case BC_REQUEST_DEATH_NOTIFICATION:
        case BC_CLEAR_DEATH_NOTIFICATION: {
            const int32_t h = *cmd++;
            const int32_t c = *cmd++;
            out << ": handle=" << h << " (death cookie " << (void*)c << ")";
        } break;

        case BC_DEAD_BINDER_DONE: {
            const int32_t c = *cmd++;
            out << ": death cookie " << (void*)c;
        } break;

        default:
            // no details to show for: BC_REGISTER_LOOPER, BC_ENTER_LOOPER,
            // BC_EXIT_LOOPER
            break;
    }
    
    out << endl;
    return cmd;
}
#endif

static pthread_mutex_t gTLSMutex = PTHREAD_MUTEX_INITIALIZER;
static bool gHaveTLS = false;
static pthread_key_t gTLS = 0;
static bool gShutdown = false;
static bool gDisableBackgroundScheduling = false;

IPCThreadState* IPCThreadState::self()
{
    if (gHaveTLS) {
restart:
        const pthread_key_t k = gTLS;
        IPCThreadState* st = (IPCThreadState*)pthread_getspecific(k);
        if (st) return st;
        return new IPCThreadState;
    }
    
    if (gShutdown) return NULL;
    
    pthread_mutex_lock(&gTLSMutex);
    if (!gHaveTLS) {
        if (pthread_key_create(&gTLS, threadDestructor) != 0) {
            pthread_mutex_unlock(&gTLSMutex);
            return NULL;
        }
        gHaveTLS = true;
    }
    pthread_mutex_unlock(&gTLSMutex);
    goto restart;
}

IPCThreadState* IPCThreadState::selfOrNull()
{
    if (gHaveTLS) {
        const pthread_key_t k = gTLS;
        IPCThreadState* st = (IPCThreadState*)pthread_getspecific(k);
        return st;
    }
    return NULL;
}

void IPCThreadState::shutdown()
{
    gShutdown = true;
    
    if (gHaveTLS) {
        // XXX Need to wait for all thread pool threads to exit!
        IPCThreadState* st = (IPCThreadState*)pthread_getspecific(gTLS);
        if (st) {
            delete st;
            pthread_setspecific(gTLS, NULL);
        }
        gHaveTLS = false;
    }
}

void IPCThreadState::disableBackgroundScheduling(bool disable)
{
    gDisableBackgroundScheduling = disable;
}

sp<ProcessState> IPCThreadState::process()
{
    return mProcess;
}

status_t IPCThreadState::clearLastError()
{
    const status_t err = mLastError;
    mLastError = NO_ERROR;
    return err;
}

int IPCThreadState::getCallingPid()
{
    return mCallingPid;
}

int IPCThreadState::getCallingUid()
{
    return mCallingUid;
}

int64_t IPCThreadState::clearCallingIdentity()
{
    int64_t token = ((int64_t)mCallingUid<<32) | mCallingPid;
    clearCaller();
    return token;
}

void IPCThreadState::setStrictModePolicy(int32_t policy)
{
    mStrictModePolicy = policy;
}

int32_t IPCThreadState::getStrictModePolicy() const
{
    return mStrictModePolicy;
}

void IPCThreadState::setLastTransactionBinderFlags(int32_t flags)
{
    mLastTransactionBinderFlags = flags;
}

int32_t IPCThreadState::getLastTransactionBinderFlags() const
{
    return mLastTransactionBinderFlags;
}

void IPCThreadState::restoreCallingIdentity(int64_t token)
{
    mCallingUid = (int)(token>>32);
    mCallingPid = (int)token;
}

void IPCThreadState::clearCaller()
{
    mCallingPid = getpid();
    mCallingUid = getuid();
}

void IPCThreadState::flushCommands()
{
    if (mProcess->mDriverFD <= 0)
        return;
    talkWithDriver(false);
}

void IPCThreadState::joinThreadPool(bool isMain)
{
    LOG_THREADPOOL("**** THREAD %p (PID %d) IS JOINING THE THREAD POOL\n", (void*)pthread_self(), getpid());

    mOut.writeInt32(isMain ? BC_ENTER_LOOPER : BC_REGISTER_LOOPER);
    
    // This thread may have been spawned by a thread that was in the background
    // scheduling group, so first we will make sure it is in the default/foreground
    // one to avoid performing an initial transaction in the background.
    androidSetThreadSchedulingGroup(mMyThreadId, ANDROID_TGROUP_DEFAULT);
        
    status_t result;
    do {
        int32_t cmd;
        
        // When we've cleared the incoming command queue, process any pending derefs
        if (mIn.dataPosition() >= mIn.dataSize()) {
            size_t numPending = mPendingWeakDerefs.size();
            if (numPending > 0) {
                for (size_t i = 0; i < numPending; i++) {
                    RefBase::weakref_type* refs = mPendingWeakDerefs[i];
                    refs->decWeak(mProcess.get());
                }
                mPendingWeakDerefs.clear();
            }

            numPending = mPendingStrongDerefs.size();
            if (numPending > 0) {
                for (size_t i = 0; i < numPending; i++) {
                    BBinder* obj = mPendingStrongDerefs[i];
                    obj->decStrong(mProcess.get());
                }
                mPendingStrongDerefs.clear();
            }
        }

        // now get the next command to be processed, waiting if necessary
        result = talkWithDriver();
        if (result >= NO_ERROR) {
            size_t IN = mIn.dataAvail();
            if (IN < sizeof(int32_t)) continue;
            cmd = mIn.readInt32();
            IF_LOG_COMMANDS() {
                alog << "Processing top-level Command: "
                    << getReturnString(cmd) << endl;
            }


            result = executeCommand(cmd);
        }
        
        // After executing the command, ensure that the thread is returned to the
        // default cgroup before rejoining the pool.  The driver takes care of
        // restoring the priority, but doesn't do anything with cgroups so we
        // need to take care of that here in userspace.  Note that we do make
        // sure to go in the foreground after executing a transaction, but
        // there are other callbacks into user code that could have changed
        // our group so we want to make absolutely sure it is put back.
        androidSetThreadSchedulingGroup(mMyThreadId, ANDROID_TGROUP_DEFAULT);

        // Let this thread exit the thread pool if it is no longer
        // needed and it is not the main process thread.
        if(result == TIMED_OUT && !isMain) {
            break;
        }
    } while (result != -ECONNREFUSED && result != -EBADF);

    LOG_THREADPOOL("**** THREAD %p (PID %d) IS LEAVING THE THREAD POOL err=%p\n",
        (void*)pthread_self(), getpid(), (void*)result);
    
    mOut.writeInt32(BC_EXIT_LOOPER);
    talkWithDriver(false);
}

void IPCThreadState::stopProcess(bool immediate)
{
    //ALOGI("**** STOPPING PROCESS");
    flushCommands();
    int fd = mProcess->mDriverFD;
    mProcess->mDriverFD = -1;
    close(fd);
    //kill(getpid(), SIGKILL);
}

status_t IPCThreadState::transact(int32_t handle,
                                  uint32_t code, const Parcel& data,
                                  Parcel* reply, uint32_t flags)
{
    status_t err = data.errorCheck();

    flags |= TF_ACCEPT_FDS;

    IF_LOG_TRANSACTIONS() {
        TextOutput::Bundle _b(alog);
        alog << "BC_TRANSACTION thr " << (void*)pthread_self() << " / hand "
            << handle << " / code " << TypeCode(code) << ": "
            << indent << data << dedent << endl;
    }
    
    if (err == NO_ERROR) {
        LOG_ONEWAY(">>>> SEND from pid %d uid %d %s", getpid(), getuid(),
            (flags & TF_ONE_WAY) == 0 ? "READ REPLY" : "ONE WAY");
        err = writeTransactionData(BC_TRANSACTION, flags, handle, code, data, NULL);
    }
    
    if (err != NO_ERROR) {
        if (reply) reply->setError(err);
        return (mLastError = err);
    }
    
    if ((flags & TF_ONE_WAY) == 0) {
        #if 0
        if (code == 4) { // relayout
            ALOGI(">>>>>> CALLING transaction 4");
        } else {
            ALOGI(">>>>>> CALLING transaction %d", code);
        }
        #endif
        if (reply) {
            err = waitForResponse(reply);
        } else {
            Parcel fakeReply;
            err = waitForResponse(&fakeReply);
        }
        #if 0
        if (code == 4) { // relayout
            ALOGI("<<<<<< RETURNING transaction 4");
        } else {
            ALOGI("<<<<<< RETURNING transaction %d", code);
        }
        #endif
        
        IF_LOG_TRANSACTIONS() {
            TextOutput::Bundle _b(alog);
            alog << "BR_REPLY thr " << (void*)pthread_self() << " / hand "
                << handle << ": ";
            if (reply) alog << indent << *reply << dedent << endl;
            else alog << "(none requested)" << endl;
        }
    } else {
        err = waitForResponse(NULL, NULL);
    }
    
    return err;
}

void IPCThreadState::incStrongHandle(int32_t handle)
{
    LOG_REMOTEREFS("IPCThreadState::incStrongHandle(%d)\n", handle);
    mOut.writeInt32(BC_ACQUIRE);
    mOut.writeInt32(handle);
}

void IPCThreadState::decStrongHandle(int32_t handle)
{
    LOG_REMOTEREFS("IPCThreadState::decStrongHandle(%d)\n", handle);
    mOut.writeInt32(BC_RELEASE);
    mOut.writeInt32(handle);
}

void IPCThreadState::incWeakHandle(int32_t handle)
{
    LOG_REMOTEREFS("IPCThreadState::incWeakHandle(%d)\n", handle);
    mOut.writeInt32(BC_INCREFS);
    mOut.writeInt32(handle);
}

void IPCThreadState::decWeakHandle(int32_t handle)
{
    LOG_REMOTEREFS("IPCThreadState::decWeakHandle(%d)\n", handle);
    mOut.writeInt32(BC_DECREFS);
    mOut.writeInt32(handle);
}

status_t IPCThreadState::attemptIncStrongHandle(int32_t handle)
{
    LOG_REMOTEREFS("IPCThreadState::attemptIncStrongHandle(%d)\n", handle);
    mOut.writeInt32(BC_ATTEMPT_ACQUIRE);
    mOut.writeInt32(0); // xxx was thread priority
    mOut.writeInt32(handle);
    status_t result = UNKNOWN_ERROR;
    
    waitForResponse(NULL, &result);
    
#if LOG_REFCOUNTS
    printf("IPCThreadState::attemptIncStrongHandle(%ld) = %s\n",
        handle, result == NO_ERROR ? "SUCCESS" : "FAILURE");
#endif
    
    return result;
}

void IPCThreadState::expungeHandle(int32_t handle, IBinder* binder)
{
#if LOG_REFCOUNTS
    printf("IPCThreadState::expungeHandle(%ld)\n", handle);
#endif
    self()->mProcess->expungeHandle(handle, binder);
}

status_t IPCThreadState::requestDeathNotification(int32_t handle, BpBinder* proxy)
{
    mOut.writeInt32(BC_REQUEST_DEATH_NOTIFICATION);
    mOut.writeInt32((int32_t)handle);
    mOut.writeInt32((int32_t)proxy);
    return NO_ERROR;
}

status_t IPCThreadState::clearDeathNotification(int32_t handle, BpBinder* proxy)
{
    mOut.writeInt32(BC_CLEAR_DEATH_NOTIFICATION);
    mOut.writeInt32((int32_t)handle);
    mOut.writeInt32((int32_t)proxy);
    return NO_ERROR;
}

IPCThreadState::IPCThreadState()
    : mProcess(ProcessState::self()),
      mMyThreadId(androidGetTid()),
      mStrictModePolicy(0),
      mLastTransactionBinderFlags(0)
{
    pthread_setspecific(gTLS, this);
    clearCaller();
    mIn.setDataCapacity(256);
    mOut.setDataCapacity(256);
}

IPCThreadState::~IPCThreadState()
{
}

status_t IPCThreadState::sendReply(const Parcel& reply, uint32_t flags)
{
    status_t err;
    status_t statusBuffer;
    err = writeTransactionData(BC_REPLY, flags, -1, 0, reply, &statusBuffer);
    if (err < NO_ERROR) return err;
    
    return waitForResponse(NULL, NULL);
}

status_t IPCThreadState::waitForResponse(Parcel *reply, status_t *acquireResult)
{
    int32_t cmd;
    int32_t err;

    while (1) {
        if ((err=talkWithDriver()) < NO_ERROR) break;
        err = mIn.errorCheck();
        if (err < NO_ERROR) break;
        if (mIn.dataAvail() == 0) continue;
        
        cmd = mIn.readInt32();
        
        IF_LOG_COMMANDS() {
            alog << "Processing waitForResponse Command: "
                << getReturnString(cmd) << endl;
        }

        switch (cmd) {
        case BR_TRANSACTION_COMPLETE:
            if (!reply && !acquireResult) goto finish;
            break;
        
        case BR_DEAD_REPLY:
            err = DEAD_OBJECT;
            goto finish;

        case BR_FAILED_REPLY:
            err = FAILED_TRANSACTION;
            goto finish;
        
        case BR_ACQUIRE_RESULT:
            {
                ALOG_ASSERT(acquireResult != NULL, "Unexpected brACQUIRE_RESULT");
                const int32_t result = mIn.readInt32();
                if (!acquireResult) continue;
                *acquireResult = result ? NO_ERROR : INVALID_OPERATION;
            }
            goto finish;
        
        case BR_REPLY:
            {
                binder_transaction_data tr;
                err = mIn.read(&tr, sizeof(tr));
                ALOG_ASSERT(err == NO_ERROR, "Not enough command data for brREPLY");
                if (err != NO_ERROR) goto finish;

                if (reply) {
                    if ((tr.flags & TF_STATUS_CODE) == 0) {
                        reply->ipcSetDataReference(
                            reinterpret_cast<const uint8_t*>(tr.data.ptr.buffer),
                            tr.data_size,
                            reinterpret_cast<const size_t*>(tr.data.ptr.offsets),
                            tr.offsets_size/sizeof(size_t),
                            freeBuffer, this);
                    } else {
                        err = *static_cast<const status_t*>(tr.data.ptr.buffer);
                        freeBuffer(NULL,
                            reinterpret_cast<const uint8_t*>(tr.data.ptr.buffer),
                            tr.data_size,
                            reinterpret_cast<const size_t*>(tr.data.ptr.offsets),
                            tr.offsets_size/sizeof(size_t), this);
                    }
                } else {
                    freeBuffer(NULL,
                        reinterpret_cast<const uint8_t*>(tr.data.ptr.buffer),
                        tr.data_size,
                        reinterpret_cast<const size_t*>(tr.data.ptr.offsets),
                        tr.offsets_size/sizeof(size_t), this);
                    continue;
                }
            }
            goto finish;

        default:
            err = executeCommand(cmd);
            if (err != NO_ERROR) goto finish;
            break;
        }
    }

finish:
    if (err != NO_ERROR) {
        if (acquireResult) *acquireResult = err;
        if (reply) reply->setError(err);
        mLastError = err;
    }
    
    return err;
}

status_t IPCThreadState::talkWithDriver(bool doReceive)
{
    ALOG_ASSERT(mProcess->mDriverFD >= 0, "Binder driver is not opened");
    
    binder_write_read bwr;
    
    // Is the read buffer empty?
    const bool needRead = mIn.dataPosition() >= mIn.dataSize();
    
    // We don't want to write anything if we are still reading
    // from data left in the input buffer and the caller
    // has requested to read the next data.
    const size_t outAvail = (!doReceive || needRead) ? mOut.dataSize() : 0;
    
    bwr.write_size = outAvail;
    bwr.write_buffer = (long unsigned int)mOut.data();

    // This is what we'll read.
    if (doReceive && needRead) {
        bwr.read_size = mIn.dataCapacity();
        bwr.read_buffer = (long unsigned int)mIn.data();
    } else {
        bwr.read_size = 0;
        bwr.read_buffer = 0;
    }

    IF_LOG_COMMANDS() {
        TextOutput::Bundle _b(alog);
        if (outAvail != 0) {
            alog << "Sending commands to driver: " << indent;
            const void* cmds = (const void*)bwr.write_buffer;
            const void* end = ((const uint8_t*)cmds)+bwr.write_size;
            alog << HexDump(cmds, bwr.write_size) << endl;
            while (cmds < end) cmds = printCommand(alog, cmds);
            alog << dedent;
        }
        alog << "Size of receive buffer: " << bwr.read_size
            << ", needRead: " << needRead << ", doReceive: " << doReceive << endl;
    }
    
    // Return immediately if there is nothing to do.
    if ((bwr.write_size == 0) && (bwr.read_size == 0)) return NO_ERROR;

    bwr.write_consumed = 0;
    bwr.read_consumed = 0;
    status_t err;
    do {
        IF_LOG_COMMANDS() {
            alog << "About to read/write, write size = " << mOut.dataSize() << endl;
        }
#if defined(HAVE_ANDROID_OS)
        if (ioctl(mProcess->mDriverFD, BINDER_WRITE_READ, &bwr) >= 0)
            err = NO_ERROR;
        else
            err = -errno;
#else
        err = INVALID_OPERATION;
#endif
        IF_LOG_COMMANDS() {
            alog << "Finished read/write, write size = " << mOut.dataSize() << endl;
        }
    } while (err == -EINTR);

    IF_LOG_COMMANDS() {
        alog << "Our err: " << (void*)err << ", write consumed: "
            << bwr.write_consumed << " (of " << mOut.dataSize()
			<< "), read consumed: " << bwr.read_consumed << endl;
    }

    if (err >= NO_ERROR) {
        if (bwr.write_consumed > 0) {
            if (bwr.write_consumed < (ssize_t)mOut.dataSize())
                mOut.remove(0, bwr.write_consumed);
            else
                mOut.setDataSize(0);
        }
        if (bwr.read_consumed > 0) {
            mIn.setDataSize(bwr.read_consumed);
            mIn.setDataPosition(0);
        }
        IF_LOG_COMMANDS() {
            TextOutput::Bundle _b(alog);
            alog << "Remaining data size: " << mOut.dataSize() << endl;
            alog << "Received commands from driver: " << indent;
            const void* cmds = mIn.data();
            const void* end = mIn.data() + mIn.dataSize();
            alog << HexDump(cmds, mIn.dataSize()) << endl;
            while (cmds < end) cmds = printReturnCommand(alog, cmds);
            alog << dedent;
        }
        return NO_ERROR;
    }
    
    return err;
}

status_t IPCThreadState::writeTransactionData(int32_t cmd, uint32_t binderFlags,
    int32_t handle, uint32_t code, const Parcel& data, status_t* statusBuffer)
{
    binder_transaction_data tr;

    tr.target.handle = handle;
    tr.code = code;
    tr.flags = binderFlags;
    tr.cookie = 0;
    tr.sender_pid = 0;
    tr.sender_euid = 0;
    
    const status_t err = data.errorCheck();
    if (err == NO_ERROR) {
        tr.data_size = data.ipcDataSize();
        tr.data.ptr.buffer = data.ipcData();
        tr.offsets_size = data.ipcObjectsCount()*sizeof(size_t);
        tr.data.ptr.offsets = data.ipcObjects();
    } else if (statusBuffer) {
        tr.flags |= TF_STATUS_CODE;
        *statusBuffer = err;
        tr.data_size = sizeof(status_t);
        tr.data.ptr.buffer = statusBuffer;
        tr.offsets_size = 0;
        tr.data.ptr.offsets = NULL;
    } else {
        return (mLastError = err);
    }
    
    mOut.writeInt32(cmd);
    mOut.write(&tr, sizeof(tr));
    
    return NO_ERROR;
}

sp<BBinder> the_context_object;

void setTheContextObject(sp<BBinder> obj)
{
    the_context_object = obj;
}

status_t IPCThreadState::executeCommand(int32_t cmd)
{
    BBinder* obj;
    RefBase::weakref_type* refs;
    status_t result = NO_ERROR;
    
    switch (cmd) {
    case BR_ERROR:
        result = mIn.readInt32();
        break;
        
    case BR_OK:
        break;
        
    case BR_ACQUIRE:
        refs = (RefBase::weakref_type*)mIn.readInt32();
        obj = (BBinder*)mIn.readInt32();
        ALOG_ASSERT(refs->refBase() == obj,
                   "BR_ACQUIRE: object %p does not match cookie %p (expected %p)",
                   refs, obj, refs->refBase());
        obj->incStrong(mProcess.get());
        IF_LOG_REMOTEREFS() {
            LOG_REMOTEREFS("BR_ACQUIRE from driver on %p", obj);
            obj->printRefs();
        }
        mOut.writeInt32(BC_ACQUIRE_DONE);
        mOut.writeInt32((int32_t)refs);
        mOut.writeInt32((int32_t)obj);
        break;
        
    case BR_RELEASE:
        refs = (RefBase::weakref_type*)mIn.readInt32();
        obj = (BBinder*)mIn.readInt32();
        ALOG_ASSERT(refs->refBase() == obj,
                   "BR_RELEASE: object %p does not match cookie %p (expected %p)",
                   refs, obj, refs->refBase());
        IF_LOG_REMOTEREFS() {
            LOG_REMOTEREFS("BR_RELEASE from driver on %p", obj);
            obj->printRefs();
        }
        mPendingStrongDerefs.push(obj);
        break;
        
    case BR_INCREFS:
        refs = (RefBase::weakref_type*)mIn.readInt32();
        obj = (BBinder*)mIn.readInt32();
        refs->incWeak(mProcess.get());
        mOut.writeInt32(BC_INCREFS_DONE);
        mOut.writeInt32((int32_t)refs);
        mOut.writeInt32((int32_t)obj);
        break;
        
    case BR_DECREFS:
        refs = (RefBase::weakref_type*)mIn.readInt32();
        obj = (BBinder*)mIn.readInt32();
        // NOTE: This assertion is not valid, because the object may no
        // longer exist (thus the (BBinder*)cast above resulting in a different
        // memory address).
        //ALOG_ASSERT(refs->refBase() == obj,
        //           "BR_DECREFS: object %p does not match cookie %p (expected %p)",
        //           refs, obj, refs->refBase());
        mPendingWeakDerefs.push(refs);
        break;
        
    case BR_ATTEMPT_ACQUIRE:
        refs = (RefBase::weakref_type*)mIn.readInt32();
        obj = (BBinder*)mIn.readInt32();
         
        {
            const bool success = refs->attemptIncStrong(mProcess.get());
            ALOG_ASSERT(success && refs->refBase() == obj,
                       "BR_ATTEMPT_ACQUIRE: object %p does not match cookie %p (expected %p)",
                       refs, obj, refs->refBase());
            
            mOut.writeInt32(BC_ACQUIRE_RESULT);
            mOut.writeInt32((int32_t)success);
        }
        break;
    
    case BR_TRANSACTION:
        {
            binder_transaction_data tr;
            result = mIn.read(&tr, sizeof(tr));
            ALOG_ASSERT(result == NO_ERROR,
                "Not enough command data for brTRANSACTION");
            if (result != NO_ERROR) break;
            
            Parcel buffer;
            buffer.ipcSetDataReference(
                reinterpret_cast<const uint8_t*>(tr.data.ptr.buffer),
                tr.data_size,
                reinterpret_cast<const size_t*>(tr.data.ptr.offsets),
                tr.offsets_size/sizeof(size_t), freeBuffer, this);
            
            const pid_t origPid = mCallingPid;
            const uid_t origUid = mCallingUid;
            
            mCallingPid = tr.sender_pid;
            mCallingUid = tr.sender_euid;
            
            int curPrio = getpriority(PRIO_PROCESS, mMyThreadId);
            if (gDisableBackgroundScheduling) {
                if (curPrio > ANDROID_PRIORITY_NORMAL) {
                    // We have inherited a reduced priority from the caller, but do not
                    // want to run in that state in this process.  The driver set our
                    // priority already (though not our scheduling class), so bounce
                    // it back to the default before invoking the transaction.
                    setpriority(PRIO_PROCESS, mMyThreadId, ANDROID_PRIORITY_NORMAL);
                }
            } else {
                if (curPrio >= ANDROID_PRIORITY_BACKGROUND) {
                    // We want to use the inherited priority from the caller.
                    // Ensure this thread is in the background scheduling class,
                    // since the driver won't modify scheduling classes for us.
                    // The scheduling group is reset to default by the caller
                    // once this method returns after the transaction is complete.
                    androidSetThreadSchedulingGroup(mMyThreadId,
                                                    ANDROID_TGROUP_BG_NONINTERACT);
                }
            }

            //ALOGI(">>>> TRANSACT from pid %d uid %d\n", mCallingPid, mCallingUid);
            
            Parcel reply;
            IF_LOG_TRANSACTIONS() {
                TextOutput::Bundle _b(alog);
                alog << "BR_TRANSACTION thr " << (void*)pthread_self()
                    << " / obj " << tr.target.ptr << " / code "
                    << TypeCode(tr.code) << ": " << indent << buffer
                    << dedent << endl
                    << "Data addr = "
                    << reinterpret_cast<const uint8_t*>(tr.data.ptr.buffer)
                    << ", offsets addr="
                    << reinterpret_cast<const size_t*>(tr.data.ptr.offsets) << endl;
            }
            if (tr.target.ptr) {
                sp<BBinder> b((BBinder*)tr.cookie);
                const status_t error = b->transact(tr.code, buffer, &reply, tr.flags);
                if (error < NO_ERROR) reply.setError(error);

            } else {
                const status_t error = the_context_object->transact(tr.code, buffer, &reply, tr.flags);
                if (error < NO_ERROR) reply.setError(error);
            }
            
            //ALOGI("<<<< TRANSACT from pid %d restore pid %d uid %d\n",
            //     mCallingPid, origPid, origUid);
            
            if ((tr.flags & TF_ONE_WAY) == 0) {
                LOG_ONEWAY("Sending reply to %d!", mCallingPid);
                sendReply(reply, 0);
            } else {
                LOG_ONEWAY("NOT sending reply to %d!", mCallingPid);
            }
            
            mCallingPid = origPid;
            mCallingUid = origUid;

            IF_LOG_TRANSACTIONS() {
                TextOutput::Bundle _b(alog);
                alog << "BC_REPLY thr " << (void*)pthread_self() << " / obj "
                    << tr.target.ptr << ": " << indent << reply << dedent << endl;
            }
            
        }
        break;
    
    case BR_DEAD_BINDER:
        {
            BpBinder *proxy = (BpBinder*)mIn.readInt32();
            proxy->sendObituary();
            mOut.writeInt32(BC_DEAD_BINDER_DONE);
            mOut.writeInt32((int32_t)proxy);
        } break;
        
    case BR_CLEAR_DEATH_NOTIFICATION_DONE:
        {
            BpBinder *proxy = (BpBinder*)mIn.readInt32();
            proxy->getWeakRefs()->decWeak(proxy);
        } break;
        
    case BR_FINISHED:
        result = TIMED_OUT;
        break;
        
    case BR_NOOP:
        break;
        
    case BR_SPAWN_LOOPER:
        mProcess->spawnPooledThread(false);
        break;
        
    default:
        printf("*** BAD COMMAND %d received from Binder driver\n", cmd);
        result = UNKNOWN_ERROR;
        break;
    }

    if (result != NO_ERROR) {
        mLastError = result;
    }
    
    return result;
}

void IPCThreadState::threadDestructor(void *st)
{
	IPCThreadState* const self = static_cast<IPCThreadState*>(st);
	if (self) {
		self->flushCommands();
#if defined(HAVE_ANDROID_OS)
        ioctl(self->mProcess->mDriverFD, BINDER_THREAD_EXIT, 0);
#endif
		delete self;
	}
}


void IPCThreadState::freeBuffer(Parcel* parcel, const uint8_t* data, size_t dataSize,
                                const size_t* objects, size_t objectsSize,
                                void* cookie)
{
    //ALOGI("Freeing parcel %p", &parcel);
    IF_LOG_COMMANDS() {
        alog << "Writing BC_FREE_BUFFER for " << data << endl;
    }
    ALOG_ASSERT(data != NULL, "Called with NULL data");
    if (parcel != NULL) parcel->closeFileDescriptors();
    IPCThreadState* state = self();
    state->mOut.writeInt32(BC_FREE_BUFFER);
    state->mOut.writeInt32((int32_t)data);
}

}; // namespace android
