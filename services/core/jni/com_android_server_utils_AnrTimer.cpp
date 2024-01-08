/*
 * Copyright (C) 2023 The Android Open Source Project
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

#include <time.h>
#include <pthread.h>
#include <sys/timerfd.h>
#include <inttypes.h>

#include <algorithm>
#include <list>
#include <memory>
#include <set>
#include <string>
#include <vector>

#define LOG_TAG "AnrTimerService"

#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include "android_runtime/AndroidRuntime.h"
#include "core_jni_helpers.h"

#include <utils/Mutex.h>
#include <utils/Timers.h>

#include <utils/Log.h>
#include <utils/Timers.h>
#include <android-base/logging.h>
#include <android-base/stringprintf.h>
#include <android-base/unique_fd.h>

using ::android::base::StringPrintf;


// Native support is unavailable on WIN32 platforms.  This macro preemptively disables it.
#ifdef _WIN32
#define NATIVE_SUPPORT 0
#else
#define NATIVE_SUPPORT 1
#endif

namespace android {

// using namespace android;

// Almost nothing in this module needs to be in the android namespace.
namespace {

// If not on a Posix system, create stub timerfd methods.  These are defined to allow
// compilation.  They are not functional.  Also, they do not leak outside this compilation unit.
#ifdef _WIN32
int timer_create() {
  return -1;
}
int timer_settime(int, int, void const *, void *) {
  return -1;
}
#else
int timer_create() {
  return timerfd_create(CLOCK_MONOTONIC, TFD_CLOEXEC);
}
int timer_settime(int fd, int flags, const struct itimerspec *new_value,
                  struct itimerspec *_Nullable old_value) {
  return timerfd_settime(fd, flags, new_value, old_value);
}
#endif

// A local debug flag that gates a set of log messages for debug only.  This is normally const
// false so the debug statements are not included in the image.  The flag can be set true in a
// unit test image to debug test failures.
const bool DEBUG = false;

// Return the current time in nanoseconds.  This time is relative to system boot.
nsecs_t now() {
    return systemTime(SYSTEM_TIME_MONOTONIC);
}

/**
 * This class encapsulates the anr timer service.  The service manages a list of individual
 * timers.  A timer is either Running or Expired.  Once started, a timer may be canceled or
 * accepted.  Both actions collect statistics about the timer and then delete it.  An expired
 * timer may also be discarded, which deletes the timer without collecting any statistics.
 *
 * All public methods in this class are thread-safe.
 */
class AnrTimerService {
  private:
    class ProcessStats;
    class Timer;

  public:

    // The class that actually runs the clock.
    class Ticker;

    // A timer is identified by a timer_id_t.  Timer IDs are unique in the moment.
    using timer_id_t = uint32_t;

    // A manifest constant.  No timer is ever created with this ID.
    static const timer_id_t NOTIMER = 0;

    // A notifier is called with a timer ID, the timer's tag, and the client's cookie.  The pid
    // and uid that were originally assigned to the timer are passed as well.
    using notifier_t = bool (*)(timer_id_t, int pid, int uid, void* cookie, jweak object);

    enum Status {
        Invalid,
        Running,
        Expired,
        Canceled
    };

    /**
     * Create a timer service.  The service is initialized with a name used for logging.  The
     * constructor is also given the notifier callback, and two cookies for the callback: the
     * traditional void* and an int.
     */
    AnrTimerService(char const* label, notifier_t notifier, void* cookie, jweak jtimer, Ticker*);

    // Delete the service and clean up memory.
    ~AnrTimerService();

    // Start a timer and return the associated timer ID.  It does not matter if the same pid/uid
    // are already in the running list.  Once start() is called, one of cancel(), accept(), or
    // discard() must be called to clean up the internal data structures.
    timer_id_t start(int pid, int uid, nsecs_t timeout, bool extend);

    // Cancel a timer and remove it from all lists.  This is called when the event being timed
    // has occurred.  If the timer was Running, the function returns true.  The other
    // possibilities are that the timer was Expired or non-existent; in both cases, the function
    // returns false.
    bool cancel(timer_id_t timerId);

    // Accept a timer and remove it from all lists.  This is called when the upper layers accept
    // that a timer has expired.  If the timer was Expired, the function returns true.  The
    // other possibilities are tha the timer was Running or non-existing; in both cases, the
    // function returns false.
    bool accept(timer_id_t timerId);

    // Discard a timer without collecting any statistics.  This is called when the upper layers
    // recognize that a timer expired but decide the expiration is not significant.  If the
    // timer was Expired, the function returns true.  The other possibilities are tha the timer
    // was Running or non-existing; in both cases, the function returns false.
    bool discard(timer_id_t timerId);

    // A timer has expired.
    void expire(timer_id_t);

    // Dump a small amount of state to the log file.
    void dump(bool verbose) const;

    // Return the Java object associated with this instance.
    jweak jtimer() const {
        return notifierObject_;
    }

  private:
    // The service cannot be copied.
    AnrTimerService(AnrTimerService const &) = delete;

    // Insert a timer into the running list.  The lock must be held by the caller.
    void insert(const Timer&);

    // Remove a timer from the lists and return it. The lock must be held by the caller.
    Timer remove(timer_id_t timerId);

    // Return a string representation of a status value.
    static char const *statusString(Status);

    // The name of this service, for logging.
    std::string const label_;

    // The callback that is invoked when a timer expires.
    notifier_t const notifier_;

    // The two cookies passed to the notifier.
    void* notifierCookie_;
    jweak notifierObject_;

    // The global lock
    mutable Mutex lock_;

    // The list of all timers that are still running.  This is sorted by ID for fast lookup.
    std::set<Timer> running_;

    // The maximum number of active timers.
    size_t maxActive_;

    // Simple counters
    struct Counters {
        // The number of timers started, canceled, accepted, discarded, and expired.
        size_t started;
        size_t canceled;
        size_t accepted;
        size_t discarded;
        size_t expired;

        // The number of times there were zero active timers.
        size_t drained;

        // The number of times a protocol error was seen.
        size_t error;
    };

    Counters counters_;

    // The clock used by this AnrTimerService.
    Ticker *ticker_;
};

class AnrTimerService::ProcessStats {
  public:
    nsecs_t cpu_time;
    nsecs_t cpu_delay;

    ProcessStats() :
            cpu_time(0),
            cpu_delay(0) {
    }

    // Collect all statistics for a process.  Return true if the fill succeeded and false if it
    // did not.  If there is any problem, the statistics are zeroed.
    bool fill(int pid) {
        cpu_time = 0;
        cpu_delay = 0;

        char path[PATH_MAX];
        snprintf(path, sizeof(path), "/proc/%u/schedstat", pid);
        ::android::base::unique_fd fd(open(path, O_RDONLY | O_CLOEXEC));
        if (!fd.ok()) {
            return false;
        }
        char buffer[128];
        ssize_t len = read(fd, buffer, sizeof(buffer));
        if (len <= 0) {
            return false;
        }
        if (len >= sizeof(buffer)) {
            ALOGE("proc file too big: %s", path);
            return false;
        }
        buffer[len] = 0;
        unsigned long t1;
        unsigned long t2;
        if (sscanf(buffer, "%lu %lu", &t1, &t2) != 2) {
            return false;
        }
        cpu_time = t1;
        cpu_delay = t2;
        return true;
    }
};

class AnrTimerService::Timer {
  public:
    // A unique ID assigned when the Timer is created.
    timer_id_t const id;

    // The creation parameters.  The timeout is the original, relative timeout.
    int const pid;
    int const uid;
    nsecs_t const timeout;
    bool const extend;

    // The state of this timer.
    Status status;

    // The scheduled timeout.  This is an absolute time.  It may be extended.
    nsecs_t scheduled;

    // True if this timer has been extended.
    bool extended;

    // Bookkeeping for extensions.  The initial state of the process.  This is collected only if
    // the timer is extensible.
    ProcessStats initial;

    // The default constructor is used to create timers that are Invalid, representing the "not
    // found" condition when a collection is searched.
    Timer() :
            id(NOTIMER),
            pid(0),
            uid(0),
            timeout(0),
            extend(false),
            status(Invalid),
            scheduled(0),
            extended(false) {
    }

    // This constructor creates a timer with the specified id.  This can be used as the argument
    // to find().
    Timer(timer_id_t id) :
            id(id),
            pid(0),
            uid(0),
            timeout(0),
            extend(false),
            status(Invalid),
            scheduled(0),
            extended(false) {
    }

    // Create a new timer.  This starts the timer.
    Timer(int pid, int uid, nsecs_t timeout, bool extend) :
            id(nextId()),
            pid(pid),
            uid(uid),
            timeout(timeout),
            extend(extend),
            status(Running),
            scheduled(now() + timeout),
            extended(false) {
        if (extend && pid != 0) {
            initial.fill(pid);
        }
    }

    // Cancel a timer.  Return the headroom (which may be negative).  This does not, as yet,
    // account for extensions.
    void cancel() {
        ALOGW_IF(DEBUG && status != Running, "cancel %s", toString().c_str());
        status = Canceled;
    }

    // Expire a timer. Return true if the timer is expired and false otherwise.  The function
    // returns false if the timer is eligible for extension.  If the function returns false, the
    // scheduled time is updated.
    bool expire() {
        ALOGI_IF(DEBUG, "expire %s", toString().c_str());
        nsecs_t extension = 0;
        if (extend && !extended) {
            // Only one extension is permitted.
            extended = true;
            ProcessStats current;
            current.fill(pid);
            extension = current.cpu_delay - initial.cpu_delay;
            if (extension < 0) extension = 0;
            if (extension > timeout) extension = timeout;
        }
        if (extension == 0) {
            status = Expired;
        } else {
            scheduled += extension;
        }
        return status == Expired;
    }

    // Accept a timeout.
    void accept() {
    }

    // Discard a timeout.
    void discard() {
    }

    // Timers are sorted by id, which is unique.  This provides fast lookups.
    bool operator<(Timer const &r) const {
        return id < r.id;
    }

    bool operator==(timer_id_t r) const {
        return id == r;
    }

    std::string toString() const {
        return StringPrintf("timer id=%d pid=%d status=%s", id, pid, statusString(status));
    }

    std::string toString(nsecs_t now) const {
        uint32_t ms = nanoseconds_to_milliseconds(now - scheduled);
        return StringPrintf("timer id=%d pid=%d status=%s scheduled=%ums",
                            id, pid, statusString(status), -ms);
    }

    static int maxId() {
        return idGen;
    }

  private:
    // Get the next free ID.  NOTIMER is never returned.
    static timer_id_t nextId() {
        timer_id_t id = idGen.fetch_add(1);
        while (id == NOTIMER) {
            id = idGen.fetch_add(1);
        }
        return id;
    }

    // IDs start at 1.  A zero ID is invalid.
    static std::atomic<timer_id_t> idGen;
};

// IDs start at 1.
std::atomic<AnrTimerService::timer_id_t> AnrTimerService::Timer::idGen(1);

/**
 * Manage a set of timers and notify clients when there is a timeout.
 */
class AnrTimerService::Ticker {
  private:
    struct Entry {
        const nsecs_t scheduled;
        const timer_id_t id;
        AnrTimerService* const service;

        Entry(nsecs_t scheduled, timer_id_t id, AnrTimerService* service) :
                scheduled(scheduled), id(id), service(service) {};

        bool operator<(const Entry &r) const {
            return scheduled == r.scheduled ? id < r.id : scheduled < r.scheduled;
        }
    };

  public:

    // Construct the ticker.  This creates the timerfd file descriptor and starts the monitor
    // thread.  The monitor thread is given a unique name.
    Ticker() {
        timerFd_ = timer_create();
        if (timerFd_ < 0) {
            ALOGE("failed to create timerFd: %s", strerror(errno));
            return;
        }

        if (pthread_create(&watcher_, 0, run, this) != 0) {
            ALOGE("failed to start thread: %s", strerror(errno));
            watcher_ = 0;
            ::close(timerFd_);
            return;
        }

        // 16 is a magic number from the kernel.  Thread names may not be longer than this many
        // bytes, including the terminating null.  The snprintf() method will truncate properly.
        char name[16];
        snprintf(name, sizeof(name), "AnrTimerService");
        pthread_setname_np(watcher_, name);

        ready_ = true;
    }

    ~Ticker() {
        // Closing the file descriptor will close the monitor process, if any.
        if (timerFd_ >= 0) ::close(timerFd_);
        timerFd_ = -1;
        watcher_ = 0;
    }

    // Insert a timer.  Unless canceled, the timer will expire at the scheduled time.  If it
    // expires, the service will be notified with the id.
    void insert(nsecs_t scheduled, timer_id_t id, AnrTimerService *service) {
        Entry e(scheduled, id, service);
        AutoMutex _l(lock_);
        timer_id_t front = headTimerId();
        running_.insert(e);
        if (front != headTimerId()) restartLocked();
        maxRunning_ = std::max(maxRunning_, running_.size());
    }

    // Remove a timer.  The timer is identified by its scheduled timeout and id.  Technically,
    // the id is sufficient (because timer IDs are unique) but using the timeout is more
    // efficient.
    void remove(nsecs_t scheduled, timer_id_t id) {
        Entry key(scheduled, id, 0);
        AutoMutex _l(lock_);
        timer_id_t front = headTimerId();
        auto found = running_.find(key);
        if (found != running_.end()) running_.erase(found);
        if (front != headTimerId()) restartLocked();
    }

    // Remove every timer associated with the service.
    void remove(AnrTimerService const* service) {
        AutoMutex _l(lock_);
        timer_id_t front = headTimerId();
        for (auto i = running_.begin(); i != running_.end(); ) {
            if (i->service == service) {
                i = running_.erase(i);
            } else {
                i++;
            }
        }
        if (front != headTimerId()) restartLocked();
    }

    // Return the number of timers still running.
    size_t running() const {
        AutoMutex _l(lock_);
        return running_.size();
    }

    // Return the high-water mark of timers running.
    size_t maxRunning() const {
        AutoMutex _l(lock_);
        return maxRunning_;
    }

  private:

    // Return the head of the running list.  The lock must be held by the caller.
    timer_id_t headTimerId() {
        return running_.empty() ? NOTIMER : running_.cbegin()->id;
    }

    // A simple wrapper that meets the requirements of pthread_create.
    static void* run(void* arg) {
        reinterpret_cast<Ticker*>(arg)->monitor();
        ALOGI("monitor exited");
        return 0;
    }

    // Loop (almost) forever.  Whenever the timerfd expires, expire as many entries as
    // possible.  The loop terminates when the read fails; this generally indicates that the
    // file descriptor has been closed and the thread can exit.
    void monitor() {
        uint64_t token = 0;
        while (read(timerFd_, &token, sizeof(token)) == sizeof(token)) {
            // Move expired timers into the local ready list.  This is done inside
            // the lock.  Then, outside the lock, expire them.
            nsecs_t current = now();
            std::vector<Entry> ready;
            {
                AutoMutex _l(lock_);
                while (!running_.empty()) {
                    Entry timer = *(running_.begin());
                    if (timer.scheduled <= current) {
                        ready.push_back(timer);
                        running_.erase(running_.cbegin());
                    } else {
                        break;
                    }
                }
                restartLocked();
            }
            // Call the notifiers outside the lock.  Calling the notifiers with the lock held
            // can lead to deadlock, if the Java-side handler also takes a lock.  Note that the
            // timerfd is already running.
            for (auto i = ready.begin(); i != ready.end(); i++) {
                Entry e = *i;
                e.service->expire(e.id);
            }
        }
    }

    // Restart the ticker.  The caller must be holding the lock.  This method updates the
    // timerFd_ to expire at the time of the first Entry in the running list.  This method does
    // not check to see if the currently programmed expiration time is different from the
    // scheduled expiration time of the first entry.
    void restartLocked() {
        if (!running_.empty()) {
            Entry const x = *(running_.cbegin());
            nsecs_t delay = x.scheduled - now();
            // Force a minimum timeout of 10ns.
            if (delay < 10) delay = 10;
            time_t sec = nanoseconds_to_seconds(delay);
            time_t ns = delay - seconds_to_nanoseconds(sec);
            struct itimerspec setting = {
                .it_interval = { 0, 0 },
                .it_value = { sec, ns },
            };
            timer_settime(timerFd_, 0, &setting, nullptr);
            restarted_++;
            ALOGI_IF(DEBUG, "restarted timerfd for %ld.%09ld", sec, ns);
        } else {
            const struct itimerspec setting = {
                .it_interval = { 0, 0 },
                .it_value = { 0, 0 },
            };
            timer_settime(timerFd_, 0, &setting, nullptr);
            drained_++;
            ALOGI_IF(DEBUG, "drained timer list");
        }
    }

    // The usual lock.
    mutable Mutex lock_;

    // True if the object was initialized properly.  Android does not support throwing C++
    // exceptions, so clients should check this flag after constructing the object.  This is
    // effectively const after the instance has been created.
    bool ready_ = false;

    // The file descriptor of the timer.
    int timerFd_ = -1;

    // The thread that monitors the timer.
    pthread_t watcher_ = 0;

    // The number of times the timer was restarted.
    size_t restarted_ = 0;

    // The number of times the timer list was exhausted.
    size_t drained_ = 0;

    // The highwater mark of timers that are running.
    size_t maxRunning_ = 0;

    // The list of timers that are scheduled.  This set is sorted by timeout and then by timer
    // ID.  A set is sufficient (as opposed to a multiset) because timer IDs are unique.
    std::set<Entry> running_;
};


AnrTimerService::AnrTimerService(char const* label,
            notifier_t notifier, void* cookie, jweak jtimer, Ticker* ticker) :
        label_(label),
        notifier_(notifier),
        notifierCookie_(cookie),
        notifierObject_(jtimer),
        ticker_(ticker) {

    // Zero the statistics
    maxActive_ = 0;
    memset(&counters_, 0, sizeof(counters_));

    ALOGI_IF(DEBUG, "initialized %s", label);
}

AnrTimerService::~AnrTimerService() {
    AutoMutex _l(lock_);
    ticker_->remove(this);
}

char const *AnrTimerService::statusString(Status s) {
    switch (s) {
        case Invalid: return "invalid";
        case Running: return "running";
        case Expired: return "expired";
        case Canceled: return "canceled";
    }
    return "unknown";
}

AnrTimerService::timer_id_t AnrTimerService::start(int pid, int uid,
        nsecs_t timeout, bool extend) {
    ALOGI_IF(DEBUG, "starting");
    AutoMutex _l(lock_);
    Timer t(pid, uid, timeout, extend);
    insert(t);
    counters_.started++;

    ALOGI_IF(DEBUG, "started timer %u timeout=%zu", t.id, static_cast<size_t>(timeout));
    return t.id;
}

bool AnrTimerService::cancel(timer_id_t timerId) {
    ALOGI_IF(DEBUG, "canceling %u", timerId);
    if (timerId == NOTIMER) return false;
    AutoMutex _l(lock_);
    Timer timer = remove(timerId);

    bool result = timer.status == Running;
    if (timer.status != Invalid) {
        timer.cancel();
    } else {
        counters_.error++;
    }
    counters_.canceled++;
    ALOGI_IF(DEBUG, "canceled timer %u", timerId);
    return result;
}

bool AnrTimerService::accept(timer_id_t timerId) {
    ALOGI_IF(DEBUG, "accepting %u", timerId);
    if (timerId == NOTIMER) return false;
    AutoMutex _l(lock_);
    Timer timer = remove(timerId);

    bool result = timer.status == Expired;
    if (timer.status == Expired) {
        timer.accept();
    } else {
        counters_.error++;
    }
    counters_.accepted++;
    ALOGI_IF(DEBUG, "accepted timer %u", timerId);
    return result;
}

bool AnrTimerService::discard(timer_id_t timerId) {
    ALOGI_IF(DEBUG, "discarding %u", timerId);
    if (timerId == NOTIMER) return false;
    AutoMutex _l(lock_);
    Timer timer = remove(timerId);

    bool result = timer.status == Expired;
    if (timer.status == Expired) {
        timer.discard();
    } else {
        counters_.error++;
    }
    counters_.discarded++;
    ALOGI_IF(DEBUG, "discarded timer %u", timerId);
    return result;
}

// Hold the lock in order to manage the running list.
// the listener.
void AnrTimerService::expire(timer_id_t timerId) {
    ALOGI_IF(DEBUG, "expiring %u", timerId);
    // Save the timer attributes for the notification
    int pid = 0;
    int uid = 0;
    bool expired = false;
    {
        AutoMutex _l(lock_);
        Timer t = remove(timerId);
        expired = t.expire();
        if (t.status == Invalid) {
            ALOGW_IF(DEBUG, "error: expired invalid timer %u", timerId);
            return;
        } else {
            // The timer is either Running (because it was extended) or expired (and is awaiting an
            // accept or discard).
            insert(t);
        }
    }

    // Deliver the notification outside of the lock.
    if (expired) {
        if (!notifier_(timerId, pid, uid, notifierCookie_, notifierObject_)) {
            AutoMutex _l(lock_);
            // Notification failed, which means the listener will never call accept() or
            // discard().  Do not reinsert the timer.
            remove(timerId);
        }
    }
    ALOGI_IF(DEBUG, "expired timer %u", timerId);
}

void AnrTimerService::insert(const Timer& t) {
    running_.insert(t);
    if (t.status == Running) {
        // Only forward running timers to the ticker.  Expired timers are handled separately.
        ticker_->insert(t.scheduled, t.id, this);
        maxActive_ = std::max(maxActive_, running_.size());
    }
}

AnrTimerService::Timer AnrTimerService::remove(timer_id_t timerId) {
    Timer key(timerId);
    auto found = running_.find(key);
    if (found != running_.end()) {
        Timer result = *found;
        running_.erase(found);
        ticker_->remove(result.scheduled, result.id);
        return result;
    }
    return Timer();
}

void AnrTimerService::dump(bool verbose) const {
    AutoMutex _l(lock_);
    ALOGI("timer %s ops started=%zu canceled=%zu accepted=%zu discarded=%zu expired=%zu",
          label_.c_str(),
          counters_.started, counters_.canceled, counters_.accepted,
          counters_.discarded, counters_.expired);
    ALOGI("timer %s stats max-active=%zu/%zu running=%zu/%zu errors=%zu",
          label_.c_str(),
          maxActive_, ticker_->maxRunning(), running_.size(), ticker_->running(),
          counters_.error);

    if (verbose) {
        nsecs_t time = now();
        for (auto i = running_.begin(); i != running_.end(); i++) {
            Timer t = *i;
            ALOGI("   running %s", t.toString(time).c_str());
        }
    }
}

/**
 * True if the native methods are supported in this process.  Native methods are supported only
 * if the initialization succeeds.
 */
bool nativeSupportEnabled = false;

/**
 * Singleton/globals for the anr timer.  Among other things, this includes a Ticker* and a use
 * count.  The JNI layer creates a single Ticker for all operational AnrTimers.  The Ticker is
 * created when the first AnrTimer is created, and is deleted when the last AnrTimer is closed.
 */
static Mutex gAnrLock;
struct AnrArgs {
    jclass clazz = NULL;
    jmethodID func = NULL;
    JavaVM* vm = NULL;
    AnrTimerService::Ticker* ticker = nullptr;
    int tickerUseCount = 0;;
};
static AnrArgs gAnrArgs;

// The cookie is the address of the AnrArgs object to which the notification should be sent.
static bool anrNotify(AnrTimerService::timer_id_t timerId, int pid, int uid,
                      void* cookie, jweak jtimer) {
    AutoMutex _l(gAnrLock);
    AnrArgs* target = reinterpret_cast<AnrArgs* >(cookie);
    JNIEnv *env;
    if (target->vm->AttachCurrentThread(&env, 0) != JNI_OK) {
        ALOGE("failed to attach thread to JavaVM");
        return false;
    }
    jboolean r = false;
    jobject timer = env->NewGlobalRef(jtimer);
    if (timer != nullptr) {
        r = env->CallBooleanMethod(timer, target->func, timerId, pid, uid);
        env->DeleteGlobalRef(timer);
    }
    target->vm->DetachCurrentThread();
    return r;
}

jboolean anrTimerSupported(JNIEnv* env, jclass) {
    return nativeSupportEnabled;
}

jlong anrTimerCreate(JNIEnv* env, jobject jtimer, jstring jname) {
    if (!nativeSupportEnabled) return 0;
    AutoMutex _l(gAnrLock);
    if (!gAnrArgs.ticker) {
        gAnrArgs.ticker = new AnrTimerService::Ticker();
    }
    gAnrArgs.tickerUseCount++;

    ScopedUtfChars name(env, jname);
    jobject timer = env->NewWeakGlobalRef(jtimer);
    AnrTimerService* service =
            new AnrTimerService(name.c_str(), anrNotify, &gAnrArgs, timer, gAnrArgs.ticker);
    return reinterpret_cast<jlong>(service);
}

AnrTimerService *toService(jlong pointer) {
    return reinterpret_cast<AnrTimerService*>(pointer);
}

jint anrTimerClose(JNIEnv* env, jclass, jlong ptr) {
    if (!nativeSupportEnabled) return -1;
    if (ptr == 0) return -1;
    AutoMutex _l(gAnrLock);
    AnrTimerService *s = toService(ptr);
    env->DeleteWeakGlobalRef(s->jtimer());
    delete s;
    if (--gAnrArgs.tickerUseCount <= 0) {
        delete gAnrArgs.ticker;
        gAnrArgs.ticker = nullptr;
    }
    return 0;
}

jint anrTimerStart(JNIEnv* env, jclass, jlong ptr,
        jint pid, jint uid, jlong timeout, jboolean extend) {
    if (!nativeSupportEnabled) return 0;
    // On the Java side, timeouts are expressed in milliseconds and must be converted to
    // nanoseconds before being passed to the library code.
    return toService(ptr)->start(pid, uid, milliseconds_to_nanoseconds(timeout), extend);
}

jboolean anrTimerCancel(JNIEnv* env, jclass, jlong ptr, jint timerId) {
    if (!nativeSupportEnabled) return false;
    return toService(ptr)->cancel(timerId);
}

jboolean anrTimerAccept(JNIEnv* env, jclass, jlong ptr, jint timerId) {
    if (!nativeSupportEnabled) return false;
    return toService(ptr)->accept(timerId);
}

jboolean anrTimerDiscard(JNIEnv* env, jclass, jlong ptr, jint timerId) {
    if (!nativeSupportEnabled) return false;
    return toService(ptr)->discard(timerId);
}

jint anrTimerDump(JNIEnv *env, jclass, jlong ptr, jboolean verbose) {
    if (!nativeSupportEnabled) return -1;
    toService(ptr)->dump(verbose);
    return 0;
}

static const JNINativeMethod methods[] = {
    {"nativeAnrTimerSupported", "()Z",  (void*) anrTimerSupported},
    {"nativeAnrTimerCreate", "(Ljava/lang/String;)J", (void*) anrTimerCreate},
    {"nativeAnrTimerClose", "(J)I",     (void*) anrTimerClose},
    {"nativeAnrTimerStart", "(JIIJZ)I", (void*) anrTimerStart},
    {"nativeAnrTimerCancel", "(JI)Z",   (void*) anrTimerCancel},
    {"nativeAnrTimerAccept", "(JI)Z",   (void*) anrTimerAccept},
    {"nativeAnrTimerDiscard", "(JI)Z",  (void*) anrTimerDiscard},
    {"nativeAnrTimerDump", "(JZ)V",     (void*) anrTimerDump},
};

} // anonymous namespace

int register_android_server_utils_AnrTimer(JNIEnv* env)
{
    static const char *className = "com/android/server/utils/AnrTimer";
    jniRegisterNativeMethods(env, className, methods, NELEM(methods));

    jclass service = FindClassOrDie(env, className);
    gAnrArgs.clazz = MakeGlobalRefOrDie(env, service);
    gAnrArgs.func = env->GetMethodID(gAnrArgs.clazz, "expire", "(III)Z");
    env->GetJavaVM(&gAnrArgs.vm);

    nativeSupportEnabled = NATIVE_SUPPORT;

    return 0;
}

} // namespace android
