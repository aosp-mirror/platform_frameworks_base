//
// Copyright 2005 The Android Open Source Project
//

#define LOG_TAG "SignalHandler"

#include "SignalHandler.h"

#include <utils/Atomic.h>
#include <utils/Debug.h>
#include <utils/Log.h>

#include <errno.h>
#include <sys/wait.h>
#include <unistd.h>

namespace android {

class SignalHandler::ProcessThread : public Thread
{
public:
    ProcessThread(SignalHandler& sh)
        : Thread(false)
        , mOwner(sh)
    {
    }

    virtual bool threadLoop()
    {
        char buffer[32];
        read(mOwner.mAvailMsg[0], buffer, sizeof(buffer));

        LOGV("Signal command processing thread woke up!");

        if (mOwner.mLostCommands) {
            LOGE("Lost %d signals!", mOwner.mLostCommands);
            mOwner.mLostCommands = 0;
        }

        int cur;
        while ((cur=mOwner.mCommandBottom) != mOwner.mCommandTop) {
            if (mOwner.mCommands[cur].filled == 0) {
                LOGV("Command at %d is not yet filled", cur);
                break;
            }

            LOGV("Processing command at %d, top is %d",
                 cur, mOwner.mCommandTop);
            processCommand(mOwner.mCommands[cur]);
            mOwner.mCommands[cur].filled = 0;

            int next = mOwner.mCommandBottom+1;
            if (next >= COMMAND_QUEUE_SIZE) {
                next = 0;
            }

            mOwner.mCommandBottom = next;
        }

        return true;
    }

    void processCommand(const CommandEntry& entry)
    {
        switch (entry.signum) {
        case SIGCHLD: {
            mOwner.mLock.lock();
            ssize_t i = mOwner.mChildHandlers.indexOfKey(entry.info.si_pid);
            ChildHandler ch;
            if (i >= 0) {
                ch = mOwner.mChildHandlers.valueAt(i);
                mOwner.mChildHandlers.removeItemsAt(i);
            }
            mOwner.mLock.unlock();

            LOGD("SIGCHLD: pid=%d, handle index=%d", entry.info.si_pid, i);

            if (i >= 0) {
                int res = waitpid(entry.info.si_pid, NULL, WNOHANG);
                LOGW_IF(res == 0,
                        "Received SIGCHLD, but pid %d is not yet stopped",
                        entry.info.si_pid);
                if (ch.handler) {
                    ch.handler(entry.info.si_pid, ch.userData);
                }
            } else {
                LOGW("Unhandled SIGCHLD for pid %d", entry.info.si_pid);
            }
        } break;
        }
    }

    SignalHandler& mOwner;
};


Mutex SignalHandler::mInstanceLock;
SignalHandler* SignalHandler::mInstance = NULL;

status_t SignalHandler::setChildHandler(pid_t childPid,
                                        int tag,
                                        child_callback_t handler,
                                        void* userData)
{
    SignalHandler* const self = getInstance();

    self->mLock.lock();

    // First make sure this child hasn't already exited.
    pid_t res = waitpid(childPid, NULL, WNOHANG);
    if (res != 0) {
        if (res < 0) {
            LOGW("setChildHandler waitpid of %d failed: %d (%s)",
                 childPid, res, strerror(errno));
        } else {
            LOGW("setChildHandler waitpid of %d said %d already dead",
                 childPid, res);
        }

        // Some kind of error...  just handle the exit now.
        self->mLock.unlock();

        if (handler) {
            handler(childPid, userData);
        }

        // Return an error code -- 0 means it already exited.
        return (status_t)res;
    }

    ChildHandler entry;
    entry.childPid = childPid;
    entry.tag = tag;
    entry.handler = handler;
    entry.userData = userData;

    // Note: this replaces an existing entry for this pid, if there already
    // is one.  This is the required behavior.
    LOGD("setChildHandler adding pid %d, tag %d, handler %p, data %p",
         childPid, tag, handler, userData);
    self->mChildHandlers.add(childPid, entry);

    self->mLock.unlock();

    return NO_ERROR;
}

void SignalHandler::killAllChildren(int tag)
{
    SignalHandler* const self = getInstance();

    AutoMutex _l (self->mLock);
    const size_t N = self->mChildHandlers.size();
    for (size_t i=0; i<N; i++) {
        const ChildHandler& ch(self->mChildHandlers.valueAt(i));
        if (tag == 0 || ch.tag == tag) {
            const pid_t pid = ch.childPid;
            LOGI("Killing child %d (tag %d)\n", pid, ch.tag);
            kill(pid, SIGKILL);
        }
    }
}

SignalHandler::SignalHandler()
    : mCommandTop(0)
    , mCommandBottom(0)
    , mLostCommands(0)
{
    memset(mCommands, 0, sizeof(mCommands));

    int res = pipe(mAvailMsg);
    LOGE_IF(res != 0, "Unable to create signal handler pipe: %s", strerror(errno));

    mProcessThread = new ProcessThread(*this);
    mProcessThread->run("SignalHandler", PRIORITY_HIGHEST);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = sigAction;
    sa.sa_flags = SA_NOCLDSTOP|SA_SIGINFO;
    sigaction(SIGCHLD, &sa, NULL);
}

SignalHandler::~SignalHandler()
{
}

SignalHandler* SignalHandler::getInstance()
{
    AutoMutex _l(mInstanceLock);
    if (mInstance == NULL) {
        mInstance = new SignalHandler();
    }
    return mInstance;
}

void SignalHandler::sigAction(int signum, siginfo_t* info, void*)
{
    static const char wakeupMsg[1] = { 0xff };

    // If our signal handler is being called, then we know we have
    // already initialized the SignalHandler class and thus mInstance
    // is valid.
    SignalHandler* const self = mInstance;

    // XXX This is not safe!
    #if 0
    LOGV("Signal %d: signo=%d, errno=%d, code=%d, pid=%d\n",
           signum,
           info->si_signo, info->si_errno, info->si_code,
           info->si_pid);
    #endif

    int32_t oldTop, newTop;

    // Find the next command slot...
    do {
        oldTop = self->mCommandTop;

        newTop = oldTop + 1;
        if (newTop >= COMMAND_QUEUE_SIZE) {
            newTop = 0;
        }

        if (newTop == self->mCommandBottom) {
            // The buffer is filled up!  Ouch!
            // XXX This is not safe!
            #if 0
            LOGE("Command buffer overflow!  newTop=%d\n", newTop);
            #endif
            android_atomic_add(1, &self->mLostCommands);
            write(self->mAvailMsg[1], wakeupMsg, sizeof(wakeupMsg));
            return;
        }
    } while(android_atomic_cmpxchg(oldTop, newTop, &(self->mCommandTop)));

    // Fill in the command data...
    self->mCommands[oldTop].signum = signum;
    self->mCommands[oldTop].info = *info;

    // And now make this command available.
    self->mCommands[oldTop].filled = 1;

    // Wake up the processing thread.
    write(self->mAvailMsg[1], wakeupMsg, sizeof(wakeupMsg));
}

}; // namespace android

