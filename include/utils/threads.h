/*
 * Copyright (C) 2007 The Android Open Source Project
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

#ifndef _LIBS_UTILS_THREADS_H
#define _LIBS_UTILS_THREADS_H

#include <stdint.h>
#include <sys/types.h>
#include <time.h>

// ------------------------------------------------------------------
// C API

#ifdef __cplusplus
extern "C" {
#endif

typedef void* android_thread_id_t;

typedef int (*android_thread_func_t)(void*);

enum {
    /*
     * ***********************************************
     * ** Keep in sync with android.os.Process.java **
     * ***********************************************
     * 
     * This maps directly to the "nice" priorites we use in Android.
     * A thread priority should be chosen inverse-proportinally to
     * the amount of work the thread is expected to do. The more work
     * a thread will do, the less favorable priority it should get so that 
     * it doesn't starve the system. Threads not behaving properly might
     * be "punished" by the kernel.
     * Use the levels below when appropriate. Intermediate values are
     * acceptable, preferably use the {MORE|LESS}_FAVORABLE constants below.
     */
    ANDROID_PRIORITY_LOWEST         =  19,

    /* use for background tasks */
    ANDROID_PRIORITY_BACKGROUND     =  10,
    
    /* most threads run at normal priority */
    ANDROID_PRIORITY_NORMAL         =   0,
    
    /* threads currently running a UI that the user is interacting with */
    ANDROID_PRIORITY_FOREGROUND     =  -2,

    /* the main UI thread has a slightly more favorable priority */
    ANDROID_PRIORITY_DISPLAY        =  -4,
    
    /* ui service treads might want to run at a urgent display (uncommon) */
    ANDROID_PRIORITY_URGENT_DISPLAY =  -8,
    
    /* all normal audio threads */
    ANDROID_PRIORITY_AUDIO          = -16,
    
    /* service audio threads (uncommon) */
    ANDROID_PRIORITY_URGENT_AUDIO   = -19,

    /* should never be used in practice. regular process might not 
     * be allowed to use this level */
    ANDROID_PRIORITY_HIGHEST        = -20,

    ANDROID_PRIORITY_DEFAULT        = ANDROID_PRIORITY_NORMAL,
    ANDROID_PRIORITY_MORE_FAVORABLE = -1,
    ANDROID_PRIORITY_LESS_FAVORABLE = +1,
};

// Create and run a new thread.
extern int androidCreateThread(android_thread_func_t, void *);

// Create thread with lots of parameters
extern int androidCreateThreadEtc(android_thread_func_t entryFunction,
                                  void *userData,
                                  const char* threadName,
                                  int32_t threadPriority,
                                  size_t threadStackSize,
                                  android_thread_id_t *threadId);

// Get some sort of unique identifier for the current thread.
extern android_thread_id_t androidGetThreadId();

// Low-level thread creation -- never creates threads that can
// interact with the Java VM.
extern int androidCreateRawThreadEtc(android_thread_func_t entryFunction,
                                     void *userData,
                                     const char* threadName,
                                     int32_t threadPriority,
                                     size_t threadStackSize,
                                     android_thread_id_t *threadId);

// Used by the Java Runtime to control how threads are created, so that
// they can be proper and lovely Java threads.
typedef int (*android_create_thread_fn)(android_thread_func_t entryFunction,
                                        void *userData,
                                        const char* threadName,
                                        int32_t threadPriority,
                                        size_t threadStackSize,
                                        android_thread_id_t *threadId);

extern void androidSetCreateThreadFunc(android_create_thread_fn func);

#ifdef __cplusplus
}
#endif

// ------------------------------------------------------------------
// C++ API

#ifdef __cplusplus

#include <utils/Errors.h>
#include <utils/RefBase.h>
#include <utils/Timers.h>

namespace android {

typedef android_thread_id_t thread_id_t;

typedef android_thread_func_t thread_func_t;

enum {
    PRIORITY_LOWEST         = ANDROID_PRIORITY_LOWEST,
    PRIORITY_BACKGROUND     = ANDROID_PRIORITY_BACKGROUND,
    PRIORITY_NORMAL         = ANDROID_PRIORITY_NORMAL,
    PRIORITY_FOREGROUND     = ANDROID_PRIORITY_FOREGROUND,
    PRIORITY_DISPLAY        = ANDROID_PRIORITY_DISPLAY,
    PRIORITY_URGENT_DISPLAY = ANDROID_PRIORITY_URGENT_DISPLAY,
    PRIORITY_AUDIO          = ANDROID_PRIORITY_AUDIO,
    PRIORITY_URGENT_AUDIO   = ANDROID_PRIORITY_URGENT_AUDIO,
    PRIORITY_HIGHEST        = ANDROID_PRIORITY_HIGHEST,
    PRIORITY_DEFAULT        = ANDROID_PRIORITY_DEFAULT,
    PRIORITY_MORE_FAVORABLE = ANDROID_PRIORITY_MORE_FAVORABLE,
    PRIORITY_LESS_FAVORABLE = ANDROID_PRIORITY_LESS_FAVORABLE,
};

// Create and run a new thread.
inline bool createThread(thread_func_t f, void *a) {
    return androidCreateThread(f, a) ? true : false;
}

// Create thread with lots of parameters
inline bool createThreadEtc(thread_func_t entryFunction,
                            void *userData,
                            const char* threadName = "android:unnamed_thread",
                            int32_t threadPriority = PRIORITY_DEFAULT,
                            size_t threadStackSize = 0,
                            thread_id_t *threadId = 0)
{
    return androidCreateThreadEtc(entryFunction, userData, threadName,
        threadPriority, threadStackSize, threadId) ? true : false;
}

// Get some sort of unique identifier for the current thread.
inline thread_id_t getThreadId() {
    return androidGetThreadId();
}

/*
 * Simple mutex class.  The implementation is system-dependent.
 *
 * The mutex must be unlocked by the thread that locked it.  They are not
 * recursive, i.e. the same thread can't lock it multiple times.
 */
class Mutex {
public:
                Mutex();
                Mutex(const char* name);
                ~Mutex();

    // lock or unlock the mutex
    status_t    lock();
    void        unlock();

    // lock if possible; returns 0 on success, error otherwise
    status_t    tryLock();

    // Manages the mutex automatically. It'll be locked when Autolock is
    // constructed and released when Autolock goes out of scope.
    class Autolock {
    public:
        inline Autolock(Mutex& mutex) : mpMutex(&mutex) { mutex.lock(); }
        inline Autolock(Mutex* mutex) : mpMutex(mutex) { mutex->lock(); }
        inline ~Autolock() { mpMutex->unlock(); }
    private:
        Mutex*  mpMutex;
    };

private:
    friend class Condition;
    
    // A mutex cannot be copied
                Mutex(const Mutex&);
    Mutex&      operator = (const Mutex&);
    void        _init();
    
    void*   mState;
};

/*
 * Automatic mutex.  Declare one of these at the top of a function.
 * When the function returns, it will go out of scope, and release the
 * mutex.
 */
 
typedef Mutex::Autolock AutoMutex;


/*
 * Condition variable class.  The implementation is system-dependent.
 *
 * Condition variables are paired up with mutexes.  Lock the mutex,
 * call wait(), then either re-wait() if things aren't quite what you want,
 * or unlock the mutex and continue.  All threads calling wait() must
 * use the same mutex for a given Condition.
 */
class Condition {
public:
    Condition();
    ~Condition();
    // Wait on the condition variable.  Lock the mutex before calling.
    status_t wait(Mutex& mutex);
    // Wait on the condition variable until the given time.  Lock the mutex
    // before calling.
    status_t wait(Mutex& mutex, nsecs_t abstime);
    // same with relative timeout
    status_t waitRelative(Mutex& mutex, nsecs_t reltime);
    // Signal the condition variable, allowing one thread to continue.
    void signal();
    // Signal the condition variable, allowing all threads to continue.
    void broadcast();

private:
    void*   mState;
};


/*
 * Read/write lock.  The resource can have multiple readers or one writer,
 * but can't be read and written at the same time.
 *
 * The same thread should not call a lock function while it already has
 * a lock.  (Should be okay for multiple readers.)
 */
class ReadWriteLock {
public:
    ReadWriteLock()
        : mNumReaders(0), mNumWriters(0)
        {}
    ~ReadWriteLock() {}

    void lockForRead();
    bool tryLockForRead();
    void unlockForRead();

    void lockForWrite();
    bool tryLockForWrite();
    void unlockForWrite();

private:
    int         mNumReaders;
    int         mNumWriters;

    Mutex       mLock;
    Condition   mReadWaiter;
    Condition   mWriteWaiter;
#if defined(PRINT_RENDER_TIMES)
    DurationTimer mDebugTimer;
#endif
};


/*
 * This is our spiffy thread object!
 */

class Thread : virtual public RefBase
{
public:
    // Create a Thread object, but doesn't create or start the associated
    // thread. See the run() method.
                        Thread(bool canCallJava = true);
    virtual             ~Thread();

    // Start the thread in threadLoop() which needs to be implemented.
    virtual status_t    run(    const char* name = 0,
                                int32_t priority = PRIORITY_DEFAULT,
                                size_t stack = 0);
    
    // Ask this object's thread to exit. This function is asynchronous, when the
    // function returns the thread might still be running. Of course, this
    // function can be called from a different thread.
    virtual void        requestExit();

    // Good place to do one-time initializations
    virtual status_t    readyToRun();
    
    // Call requestExit() and wait until this object's thread exits.
    // BE VERY CAREFUL of deadlocks. In particular, it would be silly to call
    // this function from this object's thread. Will return WOULD_BLOCK in
    // that case.
            status_t    requestExitAndWait();

protected:
    // exitPending() returns true if requestExit() has been called.
            bool        exitPending() const;
    
private:
    // Derived class must implemtent threadLoop(). The thread starts its life
    // here. There are two ways of using the Thread object:
    // 1) loop: if threadLoop() returns true, it will be called again if
    //          requestExit() wasn't called.
    // 2) once: if threadLoop() returns false, the thread will exit upon return.
    virtual bool        threadLoop() = 0;

private:
    Thread& operator=(const Thread&);
    static  int             _threadLoop(void* user);
    const   bool            mCanCallJava;
            thread_id_t     mThread;
            Mutex           mLock;
            Condition       mThreadExitedCondition;
            status_t        mStatus;
    volatile bool           mExitPending;
    volatile bool           mRunning;
            sp<Thread>      mHoldSelf;
};


}; // namespace android

#endif  // __cplusplus

#endif // _LIBS_UTILS_THREADS_H
