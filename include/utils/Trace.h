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

#ifndef ANDROID_TRACE_H
#define ANDROID_TRACE_H

#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <cutils/compiler.h>
#include <utils/threads.h>

// The ATRACE_TAG macro can be defined before including this header to trace
// using one of the tags defined below.  It must be defined to one of the
// following ATRACE_TAG_* macros.  The trace tag is used to filter tracing in
// userland to avoid some of the runtime cost of tracing when it is not desired.
//
// Defining ATRACE_TAG to be ATRACE_TAG_ALWAYS will result in the tracing always
// being enabled - this should ONLY be done for debug code, as userland tracing
// has a performance cost even when the trace is not being recorded.  Defining
// ATRACE_TAG to be ATRACE_TAG_NEVER or leaving ATRACE_TAG undefined will result
// in the tracing always being disabled.
#define ATRACE_TAG_NEVER    0           // The "never" tag is never enabled.
#define ATRACE_TAG_ALWAYS   (1<<0)      // The "always" tag is always enabled.
#define ATRACE_TAG_GRAPHICS (1<<1)
#define ATRACE_TAG_LAST     (1<<1)

#define ATRACE_TAG_INVALID (~((ATRACE_TAG_LAST - 1) | ATRACE_TAG_LAST))

#ifndef ATRACE_TAG
#define ATRACE_TAG ATRACE_TAG_NEVER
#elif ATRACE_TAG > ATRACE_TAG_LAST
#error ATRACE_TAG must be defined to be one of the tags defined in utils/Trace.h
#endif

// ATRACE_CALL traces the beginning and end of the current function.  To trace
// the correct start and end times this macro should be the first line of the
// function body.
#define ATRACE_CALL() android::ScopedTrace ___tracer(ATRACE_TAG, __FUNCTION__)

// ATRACE_INT traces a named integer value.  This can be used to track how the
// value changes over time in a trace.
#define ATRACE_INT(name, value) android::Tracer::traceCounter(ATRACE_TAG, name, value)

namespace android {

class Tracer {

public:

    static inline void traceCounter(uint64_t tag, const char* name,
            int32_t value) {
        if (!android_atomic_acquire_load(&sIsReady)) {
            init();
        }
        int traceFD = sTraceFD;
        if (CC_UNLIKELY(tagEnabled(tag) && traceFD != -1)) {
            char buf[1024];
            snprintf(buf, 1024, "C|%d|%s|%d", getpid(), name, value);
            write(traceFD, buf, strlen(buf));
        }
    }

    static inline void traceBegin(uint64_t tag, const char* name) {
        if (CC_UNLIKELY(!android_atomic_acquire_load(&sIsReady))) {
            init();
        }
        int traceFD = sTraceFD;
        if (CC_UNLIKELY(tagEnabled(tag) && (traceFD != -1))) {
            char buf[1024];
            size_t len = snprintf(buf, 1024, "B|%d|%s", getpid(), name);
            write(traceFD, buf, len);
        }
    }

   static inline void traceEnd(uint64_t tag) {
        if (CC_UNLIKELY(!android_atomic_acquire_load(&sIsReady))) {
            init();
        }
        int traceFD = sTraceFD;
        if (CC_UNLIKELY(tagEnabled(tag) && (traceFD != -1))) {
            char buf = 'E';
            write(traceFD, &buf, 1);
        }
    }

private:

    static inline bool tagEnabled(uint64_t tag) {
        return !(tag & ATRACE_TAG_INVALID) && (tag & sEnabledTags);
    }

    // init opens the trace marker file for writing and reads the
    // atrace.tags.enableflags system property.  It does this only the first
    // time it is run, using sMutex for synchronization.
    static void init();

    // sIsReady is a boolean value indicating whether a call to init() has
    // completed in this process.  It is initialized to 0 and set to 1 when the
    // first init() call completes.  It is set to 1 even if a failure occurred
    // in init (e.g. the trace marker file couldn't be opened).
    //
    // This should be checked by all tracing functions using an atomic acquire
    // load operation before calling init().  This check avoids the need to lock
    // a mutex each time a trace function gets called.
    static volatile int32_t sIsReady;

    // sTraceFD is the file descriptor used to write to the kernel's trace
    // buffer.  It is initialized to -1 and set to an open file descriptor in
    // init() while a lock on sMutex is held.
    //
    // This should only be used by a trace function after init() has
    // successfully completed.
    static int sTraceFD;

    // sEnabledTags is the set of tag bits for which tracing is currently
    // enabled.  It is initialized to 0 and set based on the
    // atrace.tags.enableflags system property in init() while a lock on sMutex
    // is held.
    //
    // This should only be used by a trace function after init() has
    // successfully completed.
    static uint64_t sEnabledTags;

    // sMutex is used to protect the execution of init().
    static Mutex sMutex;
};

class ScopedTrace {

public:
    inline ScopedTrace(uint64_t tag, const char* name) :
            mTag(tag) {
        Tracer::traceBegin(mTag, name);
    }

    inline ~ScopedTrace() {
        Tracer::traceEnd(mTag);
    }

private:

    uint64_t mTag;
};

}; // namespace android

#endif // ANDROID_TRACE_H
