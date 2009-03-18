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

// #define LOG_NDEBUG 0
#define LOG_TAG "libutils.threads"

#include <utils/threads.h>
#include <utils/Log.h>

#include <stdio.h>
#include <stdlib.h>
#include <memory.h>
#include <errno.h>
#include <assert.h>
#include <unistd.h>

#if defined(HAVE_PTHREADS)
# include <pthread.h>
# include <sched.h>
# include <sys/resource.h>
#elif defined(HAVE_WIN32_THREADS)
# include <windows.h>
# include <stdint.h>
# include <process.h>
# define HAVE_CREATETHREAD  // Cygwin, vs. HAVE__BEGINTHREADEX for MinGW
#endif

#if defined(HAVE_FUTEX)
#include <private/utils/futex_synchro.h>
#endif

#if defined(HAVE_PRCTL)
#include <sys/prctl.h>
#endif

/*
 * ===========================================================================
 *      Thread wrappers
 * ===========================================================================
 */

using namespace android;

// ----------------------------------------------------------------------------
#if defined(HAVE_PTHREADS)
#if 0
#pragma mark -
#pragma mark PTHREAD
#endif
// ----------------------------------------------------------------------------

/*
 * Create and run a new thead.
 *
 * We create it "detached", so it cleans up after itself.
 */

typedef void* (*android_pthread_entry)(void*);

struct thread_data_t {
    thread_func_t   entryFunction;
    void*           userData;
    int             priority;
    char *          threadName;

    // we use this trampoline when we need to set the priority with
    // nice/setpriority.
    static int trampoline(const thread_data_t* t) {
        thread_func_t f = t->entryFunction;
        void* u = t->userData;
        int prio = t->priority;
        char * name = t->threadName;
        delete t;
        setpriority(PRIO_PROCESS, 0, prio);
        if (name) {
#if defined(HAVE_PRCTL)
            // Mac OS doesn't have this, and we build libutil for the host too
            int hasAt = 0;
            int hasDot = 0;
            char *s = name;
            while (*s) {
                if (*s == '.') hasDot = 1;
                else if (*s == '@') hasAt = 1;
                s++;
            }
            int len = s - name;
            if (len < 15 || hasAt || !hasDot) {
                s = name;
            } else {
                s = name + len - 15;
            }
            prctl(PR_SET_NAME, (unsigned long) s, 0, 0, 0);
#endif
            free(name);
        }
        return f(u);
    }
};

int androidCreateRawThreadEtc(android_thread_func_t entryFunction,
                               void *userData,
                               const char* threadName,
                               int32_t threadPriority,
                               size_t threadStackSize,
                               android_thread_id_t *threadId)
{
    pthread_attr_t attr; 
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);

#ifdef HAVE_ANDROID_OS  /* valgrind is rejecting RT-priority create reqs */
    if (threadPriority != PRIORITY_DEFAULT || threadName != NULL) {
        // We could avoid the trampoline if there was a way to get to the
        // android_thread_id_t (pid) from pthread_t
        thread_data_t* t = new thread_data_t;
        t->priority = threadPriority;
        t->threadName = threadName ? strdup(threadName) : NULL;
        t->entryFunction = entryFunction;
        t->userData = userData;
        entryFunction = (android_thread_func_t)&thread_data_t::trampoline;
        userData = t;            
    }
#endif

    if (threadStackSize) {
        pthread_attr_setstacksize(&attr, threadStackSize);
    }
    
    errno = 0;
    pthread_t thread;
    int result = pthread_create(&thread, &attr,
                    (android_pthread_entry)entryFunction, userData);
    if (result != 0) {
        LOGE("androidCreateRawThreadEtc failed (entry=%p, res=%d, errno=%d)\n"
             "(android threadPriority=%d)",
            entryFunction, result, errno, threadPriority);
        return 0;
    }

    if (threadId != NULL) {
        *threadId = (android_thread_id_t)thread; // XXX: this is not portable
    }
    return 1;
}

android_thread_id_t androidGetThreadId()
{
    return (android_thread_id_t)pthread_self();
}

// ----------------------------------------------------------------------------
#elif defined(HAVE_WIN32_THREADS)
#if 0
#pragma mark -
#pragma mark WIN32_THREADS
#endif
// ----------------------------------------------------------------------------

/*
 * Trampoline to make us __stdcall-compliant.
 *
 * We're expected to delete "vDetails" when we're done.
 */
struct threadDetails {
    int (*func)(void*);
    void* arg;
};
static __stdcall unsigned int threadIntermediary(void* vDetails)
{
    struct threadDetails* pDetails = (struct threadDetails*) vDetails;
    int result;

    result = (*(pDetails->func))(pDetails->arg);

    delete pDetails;

    LOG(LOG_VERBOSE, "thread", "thread exiting\n");
    return (unsigned int) result;
}

/*
 * Create and run a new thread.
 */
static bool doCreateThread(android_thread_func_t fn, void* arg, android_thread_id_t *id)
{
    HANDLE hThread;
    struct threadDetails* pDetails = new threadDetails; // must be on heap
    unsigned int thrdaddr;

    pDetails->func = fn;
    pDetails->arg = arg;

#if defined(HAVE__BEGINTHREADEX)
    hThread = (HANDLE) _beginthreadex(NULL, 0, threadIntermediary, pDetails, 0,
                    &thrdaddr);
    if (hThread == 0)
#elif defined(HAVE_CREATETHREAD)
    hThread = CreateThread(NULL, 0,
                    (LPTHREAD_START_ROUTINE) threadIntermediary,
                    (void*) pDetails, 0, (DWORD*) &thrdaddr);
    if (hThread == NULL)
#endif
    {
        LOG(LOG_WARN, "thread", "WARNING: thread create failed\n");
        return false;
    }

#if defined(HAVE_CREATETHREAD)
    /* close the management handle */
    CloseHandle(hThread);
#endif

    if (id != NULL) {
      	*id = (android_thread_id_t)thrdaddr;
    }

    return true;
}

int androidCreateRawThreadEtc(android_thread_func_t fn,
                               void *userData,
                               const char* threadName,
                               int32_t threadPriority,
                               size_t threadStackSize,
                               android_thread_id_t *threadId)
{
    return doCreateThread(  fn, userData, threadId);
}

android_thread_id_t androidGetThreadId()
{
    return (android_thread_id_t)GetCurrentThreadId();
}

// ----------------------------------------------------------------------------
#else
#error "Threads not supported"
#endif

// ----------------------------------------------------------------------------

#if 0
#pragma mark -
#pragma mark Common Thread functions
#endif

int androidCreateThread(android_thread_func_t fn, void* arg)
{
    return createThreadEtc(fn, arg);
}

int androidCreateThreadGetID(android_thread_func_t fn, void *arg, android_thread_id_t *id)
{
    return createThreadEtc(fn, arg, "android:unnamed_thread",
                           PRIORITY_DEFAULT, 0, id);
}

static android_create_thread_fn gCreateThreadFn = androidCreateRawThreadEtc;

int androidCreateThreadEtc(android_thread_func_t entryFunction,
                            void *userData,
                            const char* threadName,
                            int32_t threadPriority,
                            size_t threadStackSize,
                            android_thread_id_t *threadId)
{
    return gCreateThreadFn(entryFunction, userData, threadName,
        threadPriority, threadStackSize, threadId);
}

void androidSetCreateThreadFunc(android_create_thread_fn func)
{
    gCreateThreadFn = func;
}

namespace android {

/*
 * ===========================================================================
 *      Mutex class
 * ===========================================================================
 */

#if 0
#pragma mark -
#pragma mark Mutex
#endif

#if defined(HAVE_PTHREADS) && !defined(HAVE_FUTEX)
/*
 * Simple pthread wrapper.
 */

Mutex::Mutex()
{
    _init();
}

Mutex::Mutex(const char* name)
{
    // XXX: name not used for now
    _init();
}

void Mutex::_init()
{
    pthread_mutex_t* pMutex = new pthread_mutex_t;
    pthread_mutex_init(pMutex, NULL);
    mState = pMutex;
}

Mutex::~Mutex()
{
    delete (pthread_mutex_t*) mState;
}

status_t Mutex::lock()
{
    int res;
    while ((res=pthread_mutex_lock((pthread_mutex_t*) mState)) == EINTR) ;
    return -res;
}

void Mutex::unlock()
{
    pthread_mutex_unlock((pthread_mutex_t*) mState);
}

status_t Mutex::tryLock()
{
    int res;
    while ((res=pthread_mutex_trylock((pthread_mutex_t*) mState)) == EINTR) ;
    return -res;
}

#elif defined(HAVE_FUTEX)
#if 0
#pragma mark -
#endif

#define STATE ((futex_mutex_t*) (&mState))

Mutex::Mutex()
{
    _init();
}

Mutex::Mutex(const char* name)
{
    _init();
}

void
Mutex::_init()
{
    futex_mutex_init(STATE);
}

Mutex::~Mutex()
{
}

status_t Mutex::lock()
{
    int res;
    while ((res=futex_mutex_lock(STATE, FUTEX_WAIT_INFINITE)) == EINTR) ;
    return -res;
}

void Mutex::unlock()
{
    futex_mutex_unlock(STATE);
}

status_t Mutex::tryLock()
{
    int res;
    while ((res=futex_mutex_trylock(STATE)) == EINTR) ;
    return -res;
}
#undef STATE

#elif defined(HAVE_WIN32_THREADS)
#if 0
#pragma mark -
#endif

Mutex::Mutex()
{
    HANDLE hMutex;

    assert(sizeof(hMutex) == sizeof(mState));

    hMutex = CreateMutex(NULL, FALSE, NULL);
    mState = (void*) hMutex;
}

Mutex::Mutex(const char* name)
{
    // XXX: name not used for now
    HANDLE hMutex;

    hMutex = CreateMutex(NULL, FALSE, NULL);
    mState = (void*) hMutex;
}

Mutex::~Mutex()
{
    CloseHandle((HANDLE) mState);
}

status_t Mutex::lock()
{
    DWORD dwWaitResult;
    dwWaitResult = WaitForSingleObject((HANDLE) mState, INFINITE);
    return dwWaitResult != WAIT_OBJECT_0 ? -1 : NO_ERROR;
}

void Mutex::unlock()
{
    if (!ReleaseMutex((HANDLE) mState))
        LOG(LOG_WARN, "thread", "WARNING: bad result from unlocking mutex\n");
}

status_t Mutex::tryLock()
{
    DWORD dwWaitResult;

    dwWaitResult = WaitForSingleObject((HANDLE) mState, 0);
    if (dwWaitResult != WAIT_OBJECT_0 && dwWaitResult != WAIT_TIMEOUT)
        LOG(LOG_WARN, "thread", "WARNING: bad result from try-locking mutex\n");
    return (dwWaitResult == WAIT_OBJECT_0) ? 0 : -1;
}

#else
#error "Somebody forgot to implement threads for this platform."
#endif


/*
 * ===========================================================================
 *      Condition class
 * ===========================================================================
 */

#if 0
#pragma mark -
#pragma mark Condition
#endif

#if defined(HAVE_PTHREADS) && !defined(HAVE_FUTEX)

/*
 * Constructor.  This is a simple pthread wrapper.
 */
Condition::Condition()
{
    pthread_cond_t* pCond = new pthread_cond_t;

    pthread_cond_init(pCond, NULL);
    mState = pCond;
}

/*
 * Destructor.
 */
Condition::~Condition()
{
    pthread_cond_destroy((pthread_cond_t*) mState);
    delete (pthread_cond_t*) mState;
}

/*
 * Wait on a condition variable.  Lock the mutex before calling.
 */

status_t Condition::wait(Mutex& mutex)
{
    assert(mutex.mState != NULL);

    int cc;
    while ((cc = pthread_cond_wait((pthread_cond_t*)mState,
                (pthread_mutex_t*) mutex.mState)) == EINTR) ;
    return -cc;
}

status_t Condition::wait(Mutex& mutex, nsecs_t abstime)
{
    assert(mutex.mState != NULL);

    struct timespec ts;
    ts.tv_sec = abstime/1000000000;
    ts.tv_nsec = abstime-(ts.tv_sec*1000000000);
    
    int cc;
    while ((cc = pthread_cond_timedwait((pthread_cond_t*)mState,
            (pthread_mutex_t*) mutex.mState, &ts)) == EINTR) ;
    return -cc;
}

status_t Condition::waitRelative(Mutex& mutex, nsecs_t reltime)
{
    return wait(mutex, systemTime()+reltime);
}

/*
 * Signal the condition variable, allowing one thread to continue.
 */
void Condition::signal()
{
    pthread_cond_signal((pthread_cond_t*) mState);
}

/*
 * Signal the condition variable, allowing all threads to continue.
 */
void Condition::broadcast()
{
    pthread_cond_broadcast((pthread_cond_t*) mState);
}

#elif defined(HAVE_FUTEX)
#if 0
#pragma mark -
#endif

#define STATE ((futex_cond_t*) (&mState))

/*
 * Constructor.  This is a simple pthread wrapper.
 */
Condition::Condition()
{
    futex_cond_init(STATE);
}

/*
 * Destructor.
 */
Condition::~Condition()
{
}

/*
 * Wait on a condition variable.  Lock the mutex before calling.
 */

status_t Condition::wait(Mutex& mutex)
{
    assert(mutex.mState != NULL);

    int res;
    while ((res = futex_cond_wait(STATE,
        (futex_mutex_t*)(&mutex.mState), FUTEX_WAIT_INFINITE)) == -EINTR) ;

    return -res;
}

status_t Condition::wait(Mutex& mutex, nsecs_t abstime)
{
    nsecs_t reltime = abstime - systemTime();
    if (reltime <= 0) return true;
    return waitRelative(mutex, reltime);
}

status_t Condition::waitRelative(Mutex& mutex, nsecs_t reltime)
{
    assert(mutex.mState != NULL);
    int res;
    unsigned msec = ns2ms(reltime);
    if(msec == 0)
        return true;
    // This code will not time out at the correct time if interrupted by signals
    while ((res = futex_cond_wait(STATE,
        (futex_mutex_t*)(&mutex.mState), msec)) == -EINTR) ;
    return res;
}

/*
 * Signal the condition variable, allowing one thread to continue.
 */
void Condition::signal()
{
    futex_cond_signal(STATE);
}

/*
 * Signal the condition variable, allowing all threads to continue.
 */
void Condition::broadcast()
{
    futex_cond_broadcast(STATE);
}

#undef STATE

#elif defined(HAVE_WIN32_THREADS)
#if 0
#pragma mark -
#endif

/*
 * Windows doesn't have a condition variable solution.  It's possible
 * to create one, but it's easy to get it wrong.  For a discussion, and
 * the origin of this implementation, see:
 *
 *  http://www.cs.wustl.edu/~schmidt/win32-cv-1.html
 *
 * The implementation shown on the page does NOT follow POSIX semantics.
 * As an optimization they require acquiring the external mutex before
 * calling signal() and broadcast(), whereas POSIX only requires grabbing
 * it before calling wait().  The implementation here has been un-optimized
 * to have the correct behavior.
 */
typedef struct WinCondition {
    // Number of waiting threads.
    int                 waitersCount;

    // Serialize access to waitersCount.
    CRITICAL_SECTION    waitersCountLock;

    // Semaphore used to queue up threads waiting for the condition to
    // become signaled.
    HANDLE              sema;

    // An auto-reset event used by the broadcast/signal thread to wait
    // for all the waiting thread(s) to wake up and be released from
    // the semaphore.
    HANDLE              waitersDone;

    // This mutex wouldn't be necessary if we required that the caller
    // lock the external mutex before calling signal() and broadcast().
    // I'm trying to mimic pthread semantics though.
    HANDLE              internalMutex;

    // Keeps track of whether we were broadcasting or signaling.  This
    // allows us to optimize the code if we're just signaling.
    bool                wasBroadcast;

    status_t wait(WinCondition* condState, HANDLE hMutex, nsecs_t* abstime)
    {
        // Increment the wait count, avoiding race conditions.
        EnterCriticalSection(&condState->waitersCountLock);
        condState->waitersCount++;
        //printf("+++ wait: incr waitersCount to %d (tid=%ld)\n",
        //    condState->waitersCount, getThreadId());
        LeaveCriticalSection(&condState->waitersCountLock);
    
        DWORD timeout = INFINITE;
        if (abstime) {
            nsecs_t reltime = *abstime - systemTime();
            if (reltime < 0)
                reltime = 0;
            timeout = reltime/1000000;
        }
        
        // Atomically release the external mutex and wait on the semaphore.
        DWORD res =
            SignalObjectAndWait(hMutex, condState->sema, timeout, FALSE);
    
        //printf("+++ wait: awake (tid=%ld)\n", getThreadId());
    
        // Reacquire lock to avoid race conditions.
        EnterCriticalSection(&condState->waitersCountLock);
    
        // No longer waiting.
        condState->waitersCount--;
    
        // Check to see if we're the last waiter after a broadcast.
        bool lastWaiter = (condState->wasBroadcast && condState->waitersCount == 0);
    
        //printf("+++ wait: lastWaiter=%d (wasBc=%d wc=%d)\n",
        //    lastWaiter, condState->wasBroadcast, condState->waitersCount);
    
        LeaveCriticalSection(&condState->waitersCountLock);
    
        // If we're the last waiter thread during this particular broadcast
        // then signal broadcast() that we're all awake.  It'll drop the
        // internal mutex.
        if (lastWaiter) {
            // Atomically signal the "waitersDone" event and wait until we
            // can acquire the internal mutex.  We want to do this in one step
            // because it ensures that everybody is in the mutex FIFO before
            // any thread has a chance to run.  Without it, another thread
            // could wake up, do work, and hop back in ahead of us.
            SignalObjectAndWait(condState->waitersDone, condState->internalMutex,
                INFINITE, FALSE);
        } else {
            // Grab the internal mutex.
            WaitForSingleObject(condState->internalMutex, INFINITE);
        }
    
        // Release the internal and grab the external.
        ReleaseMutex(condState->internalMutex);
        WaitForSingleObject(hMutex, INFINITE);
    
        return res == WAIT_OBJECT_0 ? NO_ERROR : -1;
    }
} WinCondition;

/*
 * Constructor.  Set up the WinCondition stuff.
 */
Condition::Condition()
{
    WinCondition* condState = new WinCondition;

    condState->waitersCount = 0;
    condState->wasBroadcast = false;
    // semaphore: no security, initial value of 0
    condState->sema = CreateSemaphore(NULL, 0, 0x7fffffff, NULL);
    InitializeCriticalSection(&condState->waitersCountLock);
    // auto-reset event, not signaled initially
    condState->waitersDone = CreateEvent(NULL, FALSE, FALSE, NULL);
    // used so we don't have to lock external mutex on signal/broadcast
    condState->internalMutex = CreateMutex(NULL, FALSE, NULL);

    mState = condState;
}

/*
 * Destructor.  Free Windows resources as well as our allocated storage.
 */
Condition::~Condition()
{
    WinCondition* condState = (WinCondition*) mState;
    if (condState != NULL) {
        CloseHandle(condState->sema);
        CloseHandle(condState->waitersDone);
        delete condState;
    }
}


status_t Condition::wait(Mutex& mutex)
{
    WinCondition* condState = (WinCondition*) mState;
    HANDLE hMutex = (HANDLE) mutex.mState;
    
    return ((WinCondition*)mState)->wait(condState, hMutex, NULL);
}

status_t Condition::wait(Mutex& mutex, nsecs_t abstime)
{
    WinCondition* condState = (WinCondition*) mState;
    HANDLE hMutex = (HANDLE) mutex.mState;

    return ((WinCondition*)mState)->wait(condState, hMutex, &abstime);
}

status_t Condition::waitRelative(Mutex& mutex, nsecs_t reltime)
{
    return wait(mutex, systemTime()+reltime);
}

/*
 * Signal the condition variable, allowing one thread to continue.
 */
void Condition::signal()
{
    WinCondition* condState = (WinCondition*) mState;

    // Lock the internal mutex.  This ensures that we don't clash with
    // broadcast().
    WaitForSingleObject(condState->internalMutex, INFINITE);

    EnterCriticalSection(&condState->waitersCountLock);
    bool haveWaiters = (condState->waitersCount > 0);
    LeaveCriticalSection(&condState->waitersCountLock);

    // If no waiters, then this is a no-op.  Otherwise, knock the semaphore
    // down a notch.
    if (haveWaiters)
        ReleaseSemaphore(condState->sema, 1, 0);

    // Release internal mutex.
    ReleaseMutex(condState->internalMutex);
}

/*
 * Signal the condition variable, allowing all threads to continue.
 *
 * First we have to wake up all threads waiting on the semaphore, then
 * we wait until all of the threads have actually been woken before
 * releasing the internal mutex.  This ensures that all threads are woken.
 */
void Condition::broadcast()
{
    WinCondition* condState = (WinCondition*) mState;

    // Lock the internal mutex.  This keeps the guys we're waking up
    // from getting too far.
    WaitForSingleObject(condState->internalMutex, INFINITE);

    EnterCriticalSection(&condState->waitersCountLock);
    bool haveWaiters = false;

    if (condState->waitersCount > 0) {
        haveWaiters = true;
        condState->wasBroadcast = true;
    }

    if (haveWaiters) {
        // Wake up all the waiters.
        ReleaseSemaphore(condState->sema, condState->waitersCount, 0);

        LeaveCriticalSection(&condState->waitersCountLock);

        // Wait for all awakened threads to acquire the counting semaphore.
        // The last guy who was waiting sets this.
        WaitForSingleObject(condState->waitersDone, INFINITE);

        // Reset wasBroadcast.  (No crit section needed because nobody
        // else can wake up to poke at it.)
        condState->wasBroadcast = 0;
    } else {
        // nothing to do
        LeaveCriticalSection(&condState->waitersCountLock);
    }

    // Release internal mutex.
    ReleaseMutex(condState->internalMutex);
}

#else
#error "condition variables not supported on this platform"
#endif

// ----------------------------------------------------------------------------

#if 0
#pragma mark -
#pragma mark Thread::Thread
#endif

/*
 * This is our thread object!
 */

Thread::Thread(bool canCallJava)
    :   mCanCallJava(canCallJava),
        mThread(thread_id_t(-1)),
        mLock("Thread::mLock"),
        mStatus(NO_ERROR),
        mExitPending(false), mRunning(false)
{
}

Thread::~Thread()
{
}

status_t Thread::readyToRun()
{
    return NO_ERROR;
}

status_t Thread::run(const char* name, int32_t priority, size_t stack)
{
    Mutex::Autolock _l(mLock);

    if (mRunning) {
        // thread already started
        return INVALID_OPERATION;
    }

    // reset status and exitPending to their default value, so we can
    // try again after an error happened (either below, or in readyToRun())
    mStatus = NO_ERROR;
    mExitPending = false;
    mThread = thread_id_t(-1);
    
    // hold a strong reference on ourself
    mHoldSelf = this;

    mRunning = true;

    bool res;
    if (mCanCallJava) {
        res = createThreadEtc(_threadLoop,
                this, name, priority, stack, &mThread);
    } else {
        res = androidCreateRawThreadEtc(_threadLoop,
                this, name, priority, stack, &mThread);
    }
    
    if (res == false) {
        mStatus = UNKNOWN_ERROR;   // something happened!
        mRunning = false;
        mThread = thread_id_t(-1);
        mHoldSelf.clear();  // "this" may have gone away after this.

        return UNKNOWN_ERROR;
    }
    
    // Do not refer to mStatus here: The thread is already running (may, in fact
    // already have exited with a valid mStatus result). The NO_ERROR indication
    // here merely indicates successfully starting the thread and does not
    // imply successful termination/execution.
    return NO_ERROR;
}

int Thread::_threadLoop(void* user)
{
    Thread* const self = static_cast<Thread*>(user);
    sp<Thread> strong(self->mHoldSelf);
    wp<Thread> weak(strong);
    self->mHoldSelf.clear();

    bool first = true;

    do {
        bool result;
        if (first) {
            first = false;
            self->mStatus = self->readyToRun();
            result = (self->mStatus == NO_ERROR);

            if (result && !self->mExitPending) {
                // Binder threads (and maybe others) rely on threadLoop
                // running at least once after a successful ::readyToRun()
                // (unless, of course, the thread has already been asked to exit
                // at that point).
                // This is because threads are essentially used like this:
                //   (new ThreadSubclass())->run();
                // The caller therefore does not retain a strong reference to
                // the thread and the thread would simply disappear after the
                // successful ::readyToRun() call instead of entering the
                // threadLoop at least once.
                result = self->threadLoop();
            }
        } else {
            result = self->threadLoop();
        }

        if (result == false || self->mExitPending) {
            self->mExitPending = true;
            self->mLock.lock();
            self->mRunning = false;
            self->mThreadExitedCondition.signal();
            self->mLock.unlock();
            break;
        }
        
        // Release our strong reference, to let a chance to the thread
        // to die a peaceful death.
        strong.clear();
        // And immediately, reacquire a strong reference for the next loop
        strong = weak.promote();
    } while(strong != 0);
    
    return 0;
}

void Thread::requestExit()
{
    mExitPending = true;
}

status_t Thread::requestExitAndWait()
{
    if (mThread == getThreadId()) {
        LOGW(
        "Thread (this=%p): don't call waitForExit() from this "
        "Thread object's thread. It's a guaranteed deadlock!",
        this);

        return WOULD_BLOCK;
    }
    
    requestExit();

    Mutex::Autolock _l(mLock);
    while (mRunning == true) {
        mThreadExitedCondition.wait(mLock);
    }
    mExitPending = false;

    return mStatus;
}

bool Thread::exitPending() const
{
    return mExitPending;
}



};  // namespace android
