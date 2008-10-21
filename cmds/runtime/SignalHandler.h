//
// Copyright 2005 The Android Open Source Project
//
#ifndef ANDROID_SIGNAL_HANDLER_H
#define ANDROID_SIGNAL_HANDLER_H

#include <utils/KeyedVector.h>
#include <utils/threads.h>

#include <signal.h>

namespace android {

// ----------------------------------------------------------------------

enum {
    DEFAULT_PROCESS_TAG = 1
};

class SignalHandler
{
public:
    typedef void (*child_callback_t)(pid_t child, void* userData);

    /**
     * Set a handler for when a child process exits.  By calling
     * this, a waitpid() will be done when the child exits to remove
     * it from the zombie state.  You can also optionally specify a
     * handler to be called when the child exits.
     * 
     * If there is already a handler for this child process, it is
     * replaced by this new handler.  In this case the old handler's
     * function is not called.
     * 
     * @param childPid Process ID of child to watch.
     * @param childTag User-defined tag for this child.  Must be
     *                 greater than zero.
     * @param handler If non-NULL, this will be called when the
     *                child exits.  It may be called in either a
     *                separate signal handling thread, or
     *                immediately if the child has already exited.
     * @param userData Propageted as-is to handler.
     * 
     * @return status_t NO_ERROR if all is well.
     */
    static status_t             setChildHandler(pid_t childPid,
                                                int childTag = DEFAULT_PROCESS_TAG,
                                                child_callback_t handler = NULL,
                                                void* userData = NULL);

    /**
     * Kill all of the child processes for which we have a waiting
     * handler, whose tag is the given value.  If tag is 0, all
     * children are killed.
     * 
     * @param tag
     */
    static void                 killAllChildren(int tag = 0);

private:
                                SignalHandler();
                                ~SignalHandler();

    static SignalHandler*       getInstance();

    static void                 sigAction(int, siginfo_t*, void*);

    // --------------------------------------------------
    // Shared state...  all of this is protected by mLock.
    // --------------------------------------------------

    mutable Mutex                       mLock;

    struct ChildHandler
    {
        pid_t childPid;
        int tag;
        child_callback_t handler;
        void* userData;
    };
    KeyedVector<pid_t, ChildHandler>    mChildHandlers;

    // --------------------------------------------------
    // Commmand queue...  data is inserted by the signal
    // handler using atomic ops, and retrieved by the
    // signal processing thread.  Because these are touched
    // by the signal handler, no lock is used.
    // --------------------------------------------------

    enum {
        COMMAND_QUEUE_SIZE = 64
    };
    struct CommandEntry
    {
        int filled;
        int signum;
        siginfo_t info;
    };

    // The top of the queue.  This is incremented atomically by the
    // signal handler before placing a command in the queue.
    volatile int32_t                    mCommandTop;

    // The bottom of the queue.  Only modified by the processing
    // thread; the signal handler reads it only to determine if the
    // queue is full.
    int32_t                             mCommandBottom;

    // Incremented each time we receive a signal and don't have room
    // for it on the command queue.
    volatile int32_t                    mLostCommands;

    // The command processing thread.
    class ProcessThread;
    sp<Thread>                          mProcessThread;

    // Pipe used to tell command processing thread when new commands.
    // are available.  The thread blocks on the read end, the signal
    // handler writes when it enqueues new commands.
    int                                 mAvailMsg[2];

    // The commands.
    CommandEntry                        mCommands[COMMAND_QUEUE_SIZE];

    // --------------------------------------------------
    // Singleton.
    // --------------------------------------------------

    static Mutex                        mInstanceLock;
    static SignalHandler*               mInstance;
};

// ----------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_SIGNAL_HANDLER_H
