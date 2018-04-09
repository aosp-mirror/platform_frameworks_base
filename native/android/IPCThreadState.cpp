/*
2 * Copyright (C) 2005 The Android Open Source Project
3 *
4 * Licensed under the Apache License, Version 2.0 (the "License");
5 * you may not use this file except in compliance with the License.
6 * You may obtain a copy of the License at
7 *
8 *      http://www.apache.org/licenses/LICENSE-2.0
9 *
10 * Unless required by applicable law or agreed to in writing, software
11 * distributed under the License is distributed on an "AS IS" BASIS,
12 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
13 * See the License for the specific language governing permissions and
14 * limitations under the License.
15 */
16
17#define LOG_TAG "IPCThreadState"
18
19#include <binder/IPCThreadState.h>
20
21#include <binder/Binder.h>
22#include <binder/BpBinder.h>
23#include <binder/TextOutput.h>
24
25#include <cutils/sched_policy.h>
26#include <utils/Log.h>
27#include <utils/SystemClock.h>
28#include <utils/threads.h>
29
30#include <private/binder/binder_module.h>
31#include <private/binder/Static.h>
32
33#include <errno.h>
34#include <inttypes.h>
35#include <pthread.h>
36#include <sched.h>
37#include <signal.h>
38#include <stdio.h>
39#include <sys/ioctl.h>
40#include <sys/resource.h>
41#include <unistd.h>
42
43#if LOG_NDEBUG
44
45#define IF_LOG_TRANSACTIONS() if (false)
46#define IF_LOG_COMMANDS() if (false)
47#define LOG_REMOTEREFS(...)
48#define IF_LOG_REMOTEREFS() if (false)
49#define LOG_THREADPOOL(...)
50#define LOG_ONEWAY(...)
51
52#else
53
54#define IF_LOG_TRANSACTIONS() IF_ALOG(LOG_VERBOSE, "transact")
55#define IF_LOG_COMMANDS() IF_ALOG(LOG_VERBOSE, "ipc")
56#define LOG_REMOTEREFS(...) ALOG(LOG_DEBUG, "remoterefs", __VA_ARGS__)
57#define IF_LOG_REMOTEREFS() IF_ALOG(LOG_DEBUG, "remoterefs")
58#define LOG_THREADPOOL(...) ALOG(LOG_DEBUG, "threadpool", __VA_ARGS__)
59#define LOG_ONEWAY(...) ALOG(LOG_DEBUG, "ipc", __VA_ARGS__)
60
61#endif
62
63// ---------------------------------------------------------------------------
64
65namespace android {
66
67static const char* getReturnString(size_t idx);
68static const void* printReturnCommand(TextOutput& out, const void* _cmd);
69static const void* printCommand(TextOutput& out, const void* _cmd);
70
71// Static const and functions will be optimized out if not used,
72// when LOG_NDEBUG and references in IF_LOG_COMMANDS() are optimized out.
73static const char *kReturnStrings[] = {
74    "BR_ERROR",
75    "BR_OK",
76    "BR_TRANSACTION",
77    "BR_REPLY",
78    "BR_ACQUIRE_RESULT",
79    "BR_DEAD_REPLY",
80    "BR_TRANSACTION_COMPLETE",
81    "BR_INCREFS",
82    "BR_ACQUIRE",
83    "BR_RELEASE",
84    "BR_DECREFS",
85    "BR_ATTEMPT_ACQUIRE",
86    "BR_NOOP",
87    "BR_SPAWN_LOOPER",
88    "BR_FINISHED",
89    "BR_DEAD_BINDER",
90    "BR_CLEAR_DEATH_NOTIFICATION_DONE",
91    "BR_FAILED_REPLY"
92};
93
94static const char *kCommandStrings[] = {
95    "BC_TRANSACTION",
96    "BC_REPLY",
97    "BC_ACQUIRE_RESULT",
98    "BC_FREE_BUFFER",
99    "BC_INCREFS",
100    "BC_ACQUIRE",
101    "BC_RELEASE",
102    "BC_DECREFS",
103    "BC_INCREFS_DONE",
104    "BC_ACQUIRE_DONE",
105    "BC_ATTEMPT_ACQUIRE",
106    "BC_REGISTER_LOOPER",
107    "BC_ENTER_LOOPER",
108    "BC_EXIT_LOOPER",
109    "BC_REQUEST_DEATH_NOTIFICATION",
110    "BC_CLEAR_DEATH_NOTIFICATION",
111    "BC_DEAD_BINDER_DONE"
112};
113
114static const char* getReturnString(size_t idx)
115{
116    if (idx < sizeof(kReturnStrings) / sizeof(kReturnStrings[0]))
117        return kReturnStrings[idx];
118    else
119        return "unknown";
120}
121
122static const void* printBinderTransactionData(TextOutput& out, const void* data)
123{
124    const binder_transaction_data* btd =
125        (const binder_transaction_data*)data;
126    if (btd->target.handle < 1024) {
127        /* want to print descriptors in decimal; guess based on value */
128        out << "target.desc=" << btd->target.handle;
129    } else {
130        out << "target.ptr=" << btd->target.ptr;
131    }
132    out << " (cookie " << btd->cookie << ")" << endl
133        << "code=" << TypeCode(btd->code) << ", flags=" << (void*)(long)btd->flags << endl
134        << "data=" << btd->data.ptr.buffer << " (" << (void*)btd->data_size
135        << " bytes)" << endl
136        << "offsets=" << btd->data.ptr.offsets << " (" << (void*)btd->offsets_size
137        << " bytes)";
138    return btd+1;
139}
140
141static const void* printReturnCommand(TextOutput& out, const void* _cmd)
142{
143    static const size_t N = sizeof(kReturnStrings)/sizeof(kReturnStrings[0]);
144    const int32_t* cmd = (const int32_t*)_cmd;
145    uint32_t code = (uint32_t)*cmd++;
146    size_t cmdIndex = code & 0xff;
147    if (code == BR_ERROR) {
148        out << "BR_ERROR: " << (void*)(long)(*cmd++) << endl;
149        return cmd;
150    } else if (cmdIndex >= N) {
151        out << "Unknown reply: " << code << endl;
152        return cmd;
153    }
154    out << kReturnStrings[cmdIndex];
155
156    switch (code) {
157        case BR_TRANSACTION:
158        case BR_REPLY: {
159            out << ": " << indent;
160            cmd = (const int32_t *)printBinderTransactionData(out, cmd);
161            out << dedent;
162        } break;
163
164        case BR_ACQUIRE_RESULT: {
165            const int32_t res = *cmd++;
166            out << ": " << res << (res ? " (SUCCESS)" : " (FAILURE)");
167        } break;
168
169        case BR_INCREFS:
170        case BR_ACQUIRE:
171        case BR_RELEASE:
172        case BR_DECREFS: {
173            const int32_t b = *cmd++;
174            const int32_t c = *cmd++;
175            out << ": target=" << (void*)(long)b << " (cookie " << (void*)(long)c << ")";
176        } break;
177
178        case BR_ATTEMPT_ACQUIRE: {
179            const int32_t p = *cmd++;
180            const int32_t b = *cmd++;
181            const int32_t c = *cmd++;
182            out << ": target=" << (void*)(long)b << " (cookie " << (void*)(long)c
183                << "), pri=" << p;
184        } break;
185
186        case BR_DEAD_BINDER:
187        case BR_CLEAR_DEATH_NOTIFICATION_DONE: {
188            const int32_t c = *cmd++;
189            out << ": death cookie " << (void*)(long)c;
190        } break;
191
192        default:
193            // no details to show for: BR_OK, BR_DEAD_REPLY,
194            // BR_TRANSACTION_COMPLETE, BR_FINISHED
195            break;
196    }
197
198    out << endl;
199    return cmd;
200}
201
202static const void* printCommand(TextOutput& out, const void* _cmd)
203{
204    static const size_t N = sizeof(kCommandStrings)/sizeof(kCommandStrings[0]);
205    const int32_t* cmd = (const int32_t*)_cmd;
206    uint32_t code = (uint32_t)*cmd++;
207    size_t cmdIndex = code & 0xff;
208
209    if (cmdIndex >= N) {
210        out << "Unknown command: " << code << endl;
211        return cmd;
212    }
213    out << kCommandStrings[cmdIndex];
214
215    switch (code) {
216        case BC_TRANSACTION:
217        case BC_REPLY: {
218            out << ": " << indent;
219            cmd = (const int32_t *)printBinderTransactionData(out, cmd);
220            out << dedent;
221        } break;
222
223        case BC_ACQUIRE_RESULT: {
224            const int32_t res = *cmd++;
225            out << ": " << res << (res ? " (SUCCESS)" : " (FAILURE)");
226        } break;
227
228        case BC_FREE_BUFFER: {
229            const int32_t buf = *cmd++;
230            out << ": buffer=" << (void*)(long)buf;
231        } break;
232
233        case BC_INCREFS:
234        case BC_ACQUIRE:
235        case BC_RELEASE:
236        case BC_DECREFS: {
237            const int32_t d = *cmd++;
238            out << ": desc=" << d;
239        } break;
240
241        case BC_INCREFS_DONE:
242        case BC_ACQUIRE_DONE: {
243            const int32_t b = *cmd++;
244            const int32_t c = *cmd++;
245            out << ": target=" << (void*)(long)b << " (cookie " << (void*)(long)c << ")";
246        } break;
247
248        case BC_ATTEMPT_ACQUIRE: {
249            const int32_t p = *cmd++;
250            const int32_t d = *cmd++;
251            out << ": desc=" << d << ", pri=" << p;
252        } break;
253
254        case BC_REQUEST_DEATH_NOTIFICATION:
255        case BC_CLEAR_DEATH_NOTIFICATION: {
256            const int32_t h = *cmd++;
257            const int32_t c = *cmd++;
258            out << ": handle=" << h << " (death cookie " << (void*)(long)c << ")";
259        } break;
260
261        case BC_DEAD_BINDER_DONE: {
262            const int32_t c = *cmd++;
263            out << ": death cookie " << (void*)(long)c;
264        } break;
265
266        default:
267            // no details to show for: BC_REGISTER_LOOPER, BC_ENTER_LOOPER,
268            // BC_EXIT_LOOPER
269            break;
270    }
271
272    out << endl;
273    return cmd;
274}
275
276static pthread_mutex_t gTLSMutex = PTHREAD_MUTEX_INITIALIZER;
277static bool gHaveTLS = false;
278static pthread_key_t gTLS = 0;
279static bool gShutdown = false;
280static bool gDisableBackgroundScheduling = false;
281
282IPCThreadState* IPCThreadState::self()
283{
284    if (gHaveTLS) {
285restart:
286        const pthread_key_t k = gTLS;
287        IPCThreadState* st = (IPCThreadState*)pthread_getspecific(k);
288        if (st) return st;
289        return new IPCThreadState;
290    }
291
292    if (gShutdown) {
293        ALOGW("Calling IPCThreadState::self() during shutdown is dangerous, expect a crash.\n");
294        return NULL;
295    }
296
297    pthread_mutex_lock(&gTLSMutex);
298    if (!gHaveTLS) {
299        int key_create_value = pthread_key_create(&gTLS, threadDestructor);
300        if (key_create_value != 0) {
301            pthread_mutex_unlock(&gTLSMutex);
302            ALOGW("IPCThreadState::self() unable to create TLS key, expect a crash: %s\n",
303                    strerror(key_create_value));
304            return NULL;
305        }
306        gHaveTLS = true;
307    }
308    pthread_mutex_unlock(&gTLSMutex);
309    goto restart;
310}
311
312IPCThreadState* IPCThreadState::selfOrNull()
313{
314    if (gHaveTLS) {
315        const pthread_key_t k = gTLS;
316        IPCThreadState* st = (IPCThreadState*)pthread_getspecific(k);
317        return st;
318    }
319    return NULL;
320}
321
322void IPCThreadState::shutdown()
323{
324    gShutdown = true;
325
326    if (gHaveTLS) {
327        // XXX Need to wait for all thread pool threads to exit!
328        IPCThreadState* st = (IPCThreadState*)pthread_getspecific(gTLS);
329        if (st) {
330            delete st;
331            pthread_setspecific(gTLS, NULL);
332        }
333        gHaveTLS = false;
334    }
335}
336
337void IPCThreadState::disableBackgroundScheduling(bool disable)
338{
339    gDisableBackgroundScheduling = disable;
340}
341
342sp<ProcessState> IPCThreadState::process()
343{
344    return mProcess;
345}
346
347status_t IPCThreadState::clearLastError()
348{
349    const status_t err = mLastError;
350    mLastError = NO_ERROR;
351    return err;
352}
353
354pid_t IPCThreadState::getCallingPid() const
355{
356    return mCallingPid;
357}
358
359uid_t IPCThreadState::getCallingUid() const
360{
361    return mCallingUid;
362}
363
364int64_t IPCThreadState::clearCallingIdentity()
365{
366    int64_t token = ((int64_t)mCallingUid<<32) | mCallingPid;
367    clearCaller();
368    return token;
369}
370
371void IPCThreadState::setStrictModePolicy(int32_t policy)
372{
373    mStrictModePolicy = policy;
374}
375
376int32_t IPCThreadState::getStrictModePolicy() const
377{
378    return mStrictModePolicy;
379}
380
381void IPCThreadState::setLastTransactionBinderFlags(int32_t flags)
382{
383    mLastTransactionBinderFlags = flags;
384}
385
386int32_t IPCThreadState::getLastTransactionBinderFlags() const
387{
388    return mLastTransactionBinderFlags;
389}
390
391void IPCThreadState::restoreCallingIdentity(int64_t token)
392{
393    mCallingUid = (int)(token>>32);
394    mCallingPid = (int)token;
395}
396
397void IPCThreadState::clearCaller()
398{
399    mCallingPid = getpid();
400    mCallingUid = getuid();
401}
402
403void IPCThreadState::flushCommands()
404{
405    if (mProcess->mDriverFD <= 0)
406        return;
407    talkWithDriver(false);
408}
409
410void IPCThreadState::blockUntilThreadAvailable()
411{
412    pthread_mutex_lock(&mProcess->mThreadCountLock);
413    while (mProcess->mExecutingThreadsCount >= mProcess->mMaxThreads) {
414        ALOGW("Waiting for thread to be free. mExecutingThreadsCount=%lu mMaxThreads=%lu\n",
415                static_cast<unsigned long>(mProcess->mExecutingThreadsCount),
416                static_cast<unsigned long>(mProcess->mMaxThreads));
417        pthread_cond_wait(&mProcess->mThreadCountDecrement, &mProcess->mThreadCountLock);
418    }
419    pthread_mutex_unlock(&mProcess->mThreadCountLock);
420}
421
422status_t IPCThreadState::getAndExecuteCommand()
423{
424    status_t result;
425    int32_t cmd;
426
427    result = talkWithDriver();
428    if (result >= NO_ERROR) {
429        size_t IN = mIn.dataAvail();
430        if (IN < sizeof(int32_t)) return result;
431        cmd = mIn.readInt32();
432        IF_LOG_COMMANDS() {
433            alog << "Processing top-level Command: "
434                 << getReturnString(cmd) << endl;
435        }
436
437        pthread_mutex_lock(&mProcess->mThreadCountLock);
438        mProcess->mExecutingThreadsCount++;
439        if (mProcess->mExecutingThreadsCount >= mProcess->mMaxThreads &&
440                mProcess->mStarvationStartTimeMs == 0) {
441            mProcess->mStarvationStartTimeMs = uptimeMillis();
442        }
443        pthread_mutex_unlock(&mProcess->mThreadCountLock);
444
445        result = executeCommand(cmd);
446
447        pthread_mutex_lock(&mProcess->mThreadCountLock);
448        mProcess->mExecutingThreadsCount--;
449        if (mProcess->mExecutingThreadsCount < mProcess->mMaxThreads &&
450                mProcess->mStarvationStartTimeMs != 0) {
451            int64_t starvationTimeMs = uptimeMillis() - mProcess->mStarvationStartTimeMs;
452            if (starvationTimeMs > 100) {
453                ALOGE("binder thread pool (%zu threads) starved for %" PRId64 " ms",
454                      mProcess->mMaxThreads, starvationTimeMs);
455            }
456            mProcess->mStarvationStartTimeMs = 0;
457        }
458        pthread_cond_broadcast(&mProcess->mThreadCountDecrement);
459        pthread_mutex_unlock(&mProcess->mThreadCountLock);
460
461        // After executing the command, ensure that the thread is returned to the
462        // foreground cgroup before rejoining the pool.  The driver takes care of
463        // restoring the priority, but doesn't do anything with cgroups so we
464        // need to take care of that here in userspace.  Note that we do make
465        // sure to go in the foreground after executing a transaction, but
466        // there are other callbacks into user code that could have changed
467        // our group so we want to make absolutely sure it is put back.
468        set_sched_policy(mMyThreadId, SP_FOREGROUND);
469    }
470
471    return result;
472}
473
474// When we've cleared the incoming command queue, process any pending derefs
475void IPCThreadState::processPendingDerefs()
476{
477    if (mIn.dataPosition() >= mIn.dataSize()) {
478        size_t numPending = mPendingWeakDerefs.size();
479        if (numPending > 0) {
480            for (size_t i = 0; i < numPending; i++) {
481                RefBase::weakref_type* refs = mPendingWeakDerefs[i];
482                refs->decWeak(mProcess.get());
483            }
484            mPendingWeakDerefs.clear();
485        }
486
487        numPending = mPendingStrongDerefs.size();
488        if (numPending > 0) {
489            for (size_t i = 0; i < numPending; i++) {
490                BBinder* obj = mPendingStrongDerefs[i];
491                obj->decStrong(mProcess.get());
492            }
493            mPendingStrongDerefs.clear();
494        }
495    }
496}
497
498void IPCThreadState::joinThreadPool(bool isMain)
499{
500    LOG_THREADPOOL("**** THREAD %p (PID %d) IS JOINING THE THREAD POOL\n", (void*)pthread_self(), getpid());
501
502    mOut.writeInt32(isMain ? BC_ENTER_LOOPER : BC_REGISTER_LOOPER);
503
504    // This thread may have been spawned by a thread that was in the background
505    // scheduling group, so first we will make sure it is in the foreground
506    // one to avoid performing an initial transaction in the background.
507    set_sched_policy(mMyThreadId, SP_FOREGROUND);
508
509    status_t result;
510    do {
511        processPendingDerefs();
512        // now get the next command to be processed, waiting if necessary
513        result = getAndExecuteCommand();
514
515        if (result < NO_ERROR && result != TIMED_OUT && result != -ECONNREFUSED && result != -EBADF) {
516            ALOGE("getAndExecuteCommand(fd=%d) returned unexpected error %d, aborting",
517                  mProcess->mDriverFD, result);
518            abort();
519        }
520
521        // Let this thread exit the thread pool if it is no longer
522        // needed and it is not the main process thread.
523        if(result == TIMED_OUT && !isMain) {
524            break;
525        }
526    } while (result != -ECONNREFUSED && result != -EBADF);
527
528    LOG_THREADPOOL("**** THREAD %p (PID %d) IS LEAVING THE THREAD POOL err=%p\n",
529        (void*)pthread_self(), getpid(), (void*)result);
530
531    mOut.writeInt32(BC_EXIT_LOOPER);
532    talkWithDriver(false);
533}
534
535int IPCThreadState::setupPolling(int* fd)
536{
537    if (mProcess->mDriverFD <= 0) {
538        return -EBADF;
539    }
540
541    mOut.writeInt32(BC_ENTER_LOOPER);
542    *fd = mProcess->mDriverFD;
543    return 0;
544}
545
546status_t IPCThreadState::handlePolledCommands()
547{
548    status_t result;
549
550    do {
551        result = getAndExecuteCommand();
552    } while (mIn.dataPosition() < mIn.dataSize());
553
554    processPendingDerefs();
555    flushCommands();
556    return result;
557}
558
559void IPCThreadState::stopProcess(bool /*immediate*/)
560{
561    //ALOGI("**** STOPPING PROCESS");
562    flushCommands();
563    int fd = mProcess->mDriverFD;
564    mProcess->mDriverFD = -1;
565    close(fd);
566    //kill(getpid(), SIGKILL);
567}
568
569status_t IPCThreadState::transact(int32_t handle,
570                                  uint32_t code, const Parcel& data,
571                                  Parcel* reply, uint32_t flags)
572{
573    status_t err = data.errorCheck();
574
575    flags |= TF_ACCEPT_FDS;
576
577    IF_LOG_TRANSACTIONS() {
578        TextOutput::Bundle _b(alog);
579        alog << "BC_TRANSACTION thr " << (void*)pthread_self() << " / hand "
580            << handle << " / code " << TypeCode(code) << ": "
581            << indent << data << dedent << endl;
582    }
583
584    if (err == NO_ERROR) {
585        LOG_ONEWAY(">>>> SEND from pid %d uid %d %s", getpid(), getuid(),
586            (flags & TF_ONE_WAY) == 0 ? "READ REPLY" : "ONE WAY");
587        err = writeTransactionData(BC_TRANSACTION, flags, handle, code, data, NULL);
588    }
589
590    if (err != NO_ERROR) {
591        if (reply) reply->setError(err);
592        return (mLastError = err);
593    }
594
595    if ((flags & TF_ONE_WAY) == 0) {
596        #if 0
597        if (code == 4) { // relayout
598            ALOGI(">>>>>> CALLING transaction 4");
599        } else {
600            ALOGI(">>>>>> CALLING transaction %d", code);
601        }
602        #endif
603        if (reply) {
604            err = waitForResponse(reply);
605        } else {
606            Parcel fakeReply;
607            err = waitForResponse(&fakeReply);
608        }
609        #if 0
610        if (code == 4) { // relayout
611            ALOGI("<<<<<< RETURNING transaction 4");
612        } else {
613            ALOGI("<<<<<< RETURNING transaction %d", code);
614        }
615        #endif
616
617        IF_LOG_TRANSACTIONS() {
618            TextOutput::Bundle _b(alog);
619            alog << "BR_REPLY thr " << (void*)pthread_self() << " / hand "
620                << handle << ": ";
621            if (reply) alog << indent << *reply << dedent << endl;
622            else alog << "(none requested)" << endl;
623        }
624    } else {
625        err = waitForResponse(NULL, NULL);
626    }
627
628    return err;
629}
630
631void IPCThreadState::incStrongHandle(int32_t handle)
632{
633    LOG_REMOTEREFS("IPCThreadState::incStrongHandle(%d)\n", handle);
634    mOut.writeInt32(BC_ACQUIRE);
635    mOut.writeInt32(handle);
636}
637
638void IPCThreadState::decStrongHandle(int32_t handle)
639{
640    LOG_REMOTEREFS("IPCThreadState::decStrongHandle(%d)\n", handle);
641    mOut.writeInt32(BC_RELEASE);
642    mOut.writeInt32(handle);
643}
644
645void IPCThreadState::incWeakHandle(int32_t handle)
646{
647    LOG_REMOTEREFS("IPCThreadState::incWeakHandle(%d)\n", handle);
648    mOut.writeInt32(BC_INCREFS);
649    mOut.writeInt32(handle);
650}
651
652void IPCThreadState::decWeakHandle(int32_t handle)
653{
654    LOG_REMOTEREFS("IPCThreadState::decWeakHandle(%d)\n", handle);
655    mOut.writeInt32(BC_DECREFS);
656    mOut.writeInt32(handle);
657}
658
659status_t IPCThreadState::attemptIncStrongHandle(int32_t handle)
660{
661#if HAS_BC_ATTEMPT_ACQUIRE
662    LOG_REMOTEREFS("IPCThreadState::attemptIncStrongHandle(%d)\n", handle);
663    mOut.writeInt32(BC_ATTEMPT_ACQUIRE);
664    mOut.writeInt32(0); // xxx was thread priority
665    mOut.writeInt32(handle);
666    status_t result = UNKNOWN_ERROR;
667
668    waitForResponse(NULL, &result);
669
670#if LOG_REFCOUNTS
671    printf("IPCThreadState::attemptIncStrongHandle(%ld) = %s\n",
672        handle, result == NO_ERROR ? "SUCCESS" : "FAILURE");
673#endif
674
675    return result;
676#else
677    (void)handle;
678    ALOGE("%s(%d): Not supported\n", __func__, handle);
679    return INVALID_OPERATION;
680#endif
681}
682
683void IPCThreadState::expungeHandle(int32_t handle, IBinder* binder)
684{
685#if LOG_REFCOUNTS
686    printf("IPCThreadState::expungeHandle(%ld)\n", handle);
687#endif
688    self()->mProcess->expungeHandle(handle, binder);
689}
690
691status_t IPCThreadState::requestDeathNotification(int32_t handle, BpBinder* proxy)
692{
693    mOut.writeInt32(BC_REQUEST_DEATH_NOTIFICATION);
694    mOut.writeInt32((int32_t)handle);
695    mOut.writePointer((uintptr_t)proxy);
696    return NO_ERROR;
697}
698
699status_t IPCThreadState::clearDeathNotification(int32_t handle, BpBinder* proxy)
700{
701    mOut.writeInt32(BC_CLEAR_DEATH_NOTIFICATION);
702    mOut.writeInt32((int32_t)handle);
703    mOut.writePointer((uintptr_t)proxy);
704    return NO_ERROR;
705}
706
707IPCThreadState::IPCThreadState()
708    : mProcess(ProcessState::self()),
709      mMyThreadId(gettid()),
710      mStrictModePolicy(0),
711      mLastTransactionBinderFlags(0)
712{
713    pthread_setspecific(gTLS, this);
714    clearCaller();
715    mIn.setDataCapacity(256);
716    mOut.setDataCapacity(256);
717}
718
719IPCThreadState::~IPCThreadState()
720{
721}
722
723status_t IPCThreadState::sendReply(const Parcel& reply, uint32_t flags)
724{
725    status_t err;
726    status_t statusBuffer;
727    err = writeTransactionData(BC_REPLY, flags, -1, 0, reply, &statusBuffer);
728    if (err < NO_ERROR) return err;
729
730    return waitForResponse(NULL, NULL);
731}
732
733status_t IPCThreadState::waitForResponse(Parcel *reply, status_t *acquireResult)
734{
735    uint32_t cmd;
736    int32_t err;
737
738    while (1) {
739        if ((err=talkWithDriver()) < NO_ERROR) break;
740        err = mIn.errorCheck();
741        if (err < NO_ERROR) break;
742        if (mIn.dataAvail() == 0) continue;
743
744        cmd = (uint32_t)mIn.readInt32();
745
746        IF_LOG_COMMANDS() {
747            alog << "Processing waitForResponse Command: "
748                << getReturnString(cmd) << endl;
749        }
750
751        switch (cmd) {
752        case BR_TRANSACTION_COMPLETE:
753            if (!reply && !acquireResult) goto finish;
754            break;
755
756        case BR_DEAD_REPLY:
757            err = DEAD_OBJECT;
758            goto finish;
759
760        case BR_FAILED_REPLY:
761            err = FAILED_TRANSACTION;
762            goto finish;
763
764        case BR_ACQUIRE_RESULT:
765            {
766                ALOG_ASSERT(acquireResult != NULL, "Unexpected brACQUIRE_RESULT");
767                const int32_t result = mIn.readInt32();
768                if (!acquireResult) continue;
769                *acquireResult = result ? NO_ERROR : INVALID_OPERATION;
770            }
771            goto finish;
772
773        case BR_REPLY:
774            {
775                binder_transaction_data tr;
776                err = mIn.read(&tr, sizeof(tr));
777                ALOG_ASSERT(err == NO_ERROR, "Not enough command data for brREPLY");
778                if (err != NO_ERROR) goto finish;
779
780                if (reply) {
781                    if ((tr.flags & TF_STATUS_CODE) == 0) {
782                        reply->ipcSetDataReference(
783                            reinterpret_cast<const uint8_t*>(tr.data.ptr.buffer),
784                            tr.data_size,
785                            reinterpret_cast<const binder_size_t*>(tr.data.ptr.offsets),
786                            tr.offsets_size/sizeof(binder_size_t),
787                            freeBuffer, this);
788                    } else {
789                        err = *reinterpret_cast<const status_t*>(tr.data.ptr.buffer);
790                        freeBuffer(NULL,
791                            reinterpret_cast<const uint8_t*>(tr.data.ptr.buffer),
792                            tr.data_size,
793                            reinterpret_cast<const binder_size_t*>(tr.data.ptr.offsets),
794                            tr.offsets_size/sizeof(binder_size_t), this);
795                    }
796                } else {
797                    freeBuffer(NULL,
798                        reinterpret_cast<const uint8_t*>(tr.data.ptr.buffer),
799                        tr.data_size,
800                        reinterpret_cast<const binder_size_t*>(tr.data.ptr.offsets),
801                        tr.offsets_size/sizeof(binder_size_t), this);
802                    continue;
803                }
804            }
805            goto finish;
806
807        default:
808            err = executeCommand(cmd);
809            if (err != NO_ERROR) goto finish;
810            break;
811        }
812    }
813
814finish:
815    if (err != NO_ERROR) {
816        if (acquireResult) *acquireResult = err;
817        if (reply) reply->setError(err);
818        mLastError = err;
819    }
820
821    return err;
822}
823
824status_t IPCThreadState::talkWithDriver(bool doReceive)
825{
826    if (mProcess->mDriverFD <= 0) {
827        return -EBADF;
828    }
829
830    binder_write_read bwr;
831
832    // Is the read buffer empty?
833    const bool needRead = mIn.dataPosition() >= mIn.dataSize();
834
835    // We don't want to write anything if we are still reading
836    // from data left in the input buffer and the caller
837    // has requested to read the next data.
838    const size_t outAvail = (!doReceive || needRead) ? mOut.dataSize() : 0;
839
840    bwr.write_size = outAvail;
841    bwr.write_buffer = (uintptr_t)mOut.data();
842
843    // This is what we'll read.
844    if (doReceive && needRead) {
845        bwr.read_size = mIn.dataCapacity();
846        bwr.read_buffer = (uintptr_t)mIn.data();
847    } else {
848        bwr.read_size = 0;
849        bwr.read_buffer = 0;
850    }
851
852    IF_LOG_COMMANDS() {
853        TextOutput::Bundle _b(alog);
854        if (outAvail != 0) {
855            alog << "Sending commands to driver: " << indent;
856            const void* cmds = (const void*)bwr.write_buffer;
857            const void* end = ((const uint8_t*)cmds)+bwr.write_size;
858            alog << HexDump(cmds, bwr.write_size) << endl;
859            while (cmds < end) cmds = printCommand(alog, cmds);
860            alog << dedent;
861        }
862        alog << "Size of receive buffer: " << bwr.read_size
863            << ", needRead: " << needRead << ", doReceive: " << doReceive << endl;
864    }
865
866    // Return immediately if there is nothing to do.
867    if ((bwr.write_size == 0) && (bwr.read_size == 0)) return NO_ERROR;
868
869    bwr.write_consumed = 0;
870    bwr.read_consumed = 0;
871    status_t err;
872    do {
873        IF_LOG_COMMANDS() {
874            alog << "About to read/write, write size = " << mOut.dataSize() << endl;
875        }
876#if defined(__ANDROID__)
877        if (ioctl(mProcess->mDriverFD, BINDER_WRITE_READ, &bwr) >= 0)
878            err = NO_ERROR;
879        else
880            err = -errno;
881#else
882        err = INVALID_OPERATION;
883#endif
884        if (mProcess->mDriverFD <= 0) {
885            err = -EBADF;
886        }
887        IF_LOG_COMMANDS() {
888            alog << "Finished read/write, write size = " << mOut.dataSize() << endl;
889        }
890    } while (err == -EINTR);
891
892    IF_LOG_COMMANDS() {
893        alog << "Our err: " << (void*)(intptr_t)err << ", write consumed: "
894            << bwr.write_consumed << " (of " << mOut.dataSize()
895                        << "), read consumed: " << bwr.read_consumed << endl;
896    }
897
898    if (err >= NO_ERROR) {
899        if (bwr.write_consumed > 0) {
900            if (bwr.write_consumed < mOut.dataSize())
901                mOut.remove(0, bwr.write_consumed);
902            else
903                mOut.setDataSize(0);
904        }
905        if (bwr.read_consumed > 0) {
906            mIn.setDataSize(bwr.read_consumed);
907            mIn.setDataPosition(0);
908        }
909        IF_LOG_COMMANDS() {
910            TextOutput::Bundle _b(alog);
911            alog << "Remaining data size: " << mOut.dataSize() << endl;
912            alog << "Received commands from driver: " << indent;
913            const void* cmds = mIn.data();
914            const void* end = mIn.data() + mIn.dataSize();
915            alog << HexDump(cmds, mIn.dataSize()) << endl;
916            while (cmds < end) cmds = printReturnCommand(alog, cmds);
917            alog << dedent;
918        }
919        return NO_ERROR;
920    }
921
922    return err;
923}
924
925status_t IPCThreadState::writeTransactionData(int32_t cmd, uint32_t binderFlags,
926    int32_t handle, uint32_t code, const Parcel& data, status_t* statusBuffer)
927{
928    binder_transaction_data tr;
929
930    tr.target.ptr = 0; /* Don't pass uninitialized stack data to a remote process */
931    tr.target.handle = handle;
932    tr.code = code;
933    tr.flags = binderFlags;
934    tr.cookie = 0;
935    tr.sender_pid = 0;
936    tr.sender_euid = 0;
937
938    const status_t err = data.errorCheck();
939    if (err == NO_ERROR) {
940        tr.data_size = data.ipcDataSize();
941        tr.data.ptr.buffer = data.ipcData();
942        tr.offsets_size = data.ipcObjectsCount()*sizeof(binder_size_t);
943        tr.data.ptr.offsets = data.ipcObjects();
944    } else if (statusBuffer) {
945        tr.flags |= TF_STATUS_CODE;
946        *statusBuffer = err;
947        tr.data_size = sizeof(status_t);
948        tr.data.ptr.buffer = reinterpret_cast<uintptr_t>(statusBuffer);
949        tr.offsets_size = 0;
950        tr.data.ptr.offsets = 0;
951    } else {
952        return (mLastError = err);
953    }
954
955    mOut.writeInt32(cmd);
956    mOut.write(&tr, sizeof(tr));
957
958    return NO_ERROR;
959}
960
961sp<BBinder> the_context_object;
962
963void setTheContextObject(sp<BBinder> obj)
964{
965    the_context_object = obj;
966}
967
968status_t IPCThreadState::executeCommand(int32_t cmd)
969{
970    BBinder* obj;
971    RefBase::weakref_type* refs;
972    status_t result = NO_ERROR;
973
974    switch ((uint32_t)cmd) {
975    case BR_ERROR:
976        result = mIn.readInt32();
977        break;
978
979    case BR_OK:
980        break;
981
982    case BR_ACQUIRE:
983        refs = (RefBase::weakref_type*)mIn.readPointer();
984        obj = (BBinder*)mIn.readPointer();
985        ALOG_ASSERT(refs->refBase() == obj,
986                   "BR_ACQUIRE: object %p does not match cookie %p (expected %p)",
987                   refs, obj, refs->refBase());
988        obj->incStrong(mProcess.get());
989        IF_LOG_REMOTEREFS() {
990            LOG_REMOTEREFS("BR_ACQUIRE from driver on %p", obj);
991            obj->printRefs();
992        }
993        mOut.writeInt32(BC_ACQUIRE_DONE);
994        mOut.writePointer((uintptr_t)refs);
995        mOut.writePointer((uintptr_t)obj);
996        break;
997
998    case BR_RELEASE:
999        refs = (RefBase::weakref_type*)mIn.readPointer();
1000        obj = (BBinder*)mIn.readPointer();
1001        ALOG_ASSERT(refs->refBase() == obj,
1002                   "BR_RELEASE: object %p does not match cookie %p (expected %p)",
1003                   refs, obj, refs->refBase());
1004        IF_LOG_REMOTEREFS() {
1005            LOG_REMOTEREFS("BR_RELEASE from driver on %p", obj);
1006            obj->printRefs();
1007        }
1008        mPendingStrongDerefs.push(obj);
1009        break;
1010
1011    case BR_INCREFS:
1012        refs = (RefBase::weakref_type*)mIn.readPointer();
1013        obj = (BBinder*)mIn.readPointer();
1014        refs->incWeak(mProcess.get());
1015        mOut.writeInt32(BC_INCREFS_DONE);
1016        mOut.writePointer((uintptr_t)refs);
1017        mOut.writePointer((uintptr_t)obj);
1018        break;
1019
1020    case BR_DECREFS:
1021        refs = (RefBase::weakref_type*)mIn.readPointer();
1022        obj = (BBinder*)mIn.readPointer();
1023        // NOTE: This assertion is not valid, because the object may no
1024        // longer exist (thus the (BBinder*)cast above resulting in a different
1025        // memory address).
1026        //ALOG_ASSERT(refs->refBase() == obj,
1027        //           "BR_DECREFS: object %p does not match cookie %p (expected %p)",
1028        //           refs, obj, refs->refBase());
1029        mPendingWeakDerefs.push(refs);
1030        break;
1031
1032    case BR_ATTEMPT_ACQUIRE:
1033        refs = (RefBase::weakref_type*)mIn.readPointer();
1034        obj = (BBinder*)mIn.readPointer();
1035
1036        {
1037            const bool success = refs->attemptIncStrong(mProcess.get());
1038            ALOG_ASSERT(success && refs->refBase() == obj,
1039                       "BR_ATTEMPT_ACQUIRE: object %p does not match cookie %p (expected %p)",
1040                       refs, obj, refs->refBase());
1041
1042            mOut.writeInt32(BC_ACQUIRE_RESULT);
1043            mOut.writeInt32((int32_t)success);
1044        }
1045        break;
1046
1047    case BR_TRANSACTION:
1048        {
1049            binder_transaction_data tr;
1050            result = mIn.read(&tr, sizeof(tr));
1051            ALOG_ASSERT(result == NO_ERROR,
1052                "Not enough command data for brTRANSACTION");
1053            if (result != NO_ERROR) break;
1054
1055            Parcel buffer;
1056            buffer.ipcSetDataReference(
1057                reinterpret_cast<const uint8_t*>(tr.data.ptr.buffer),
1058                tr.data_size,
1059                reinterpret_cast<const binder_size_t*>(tr.data.ptr.offsets),
1060                tr.offsets_size/sizeof(binder_size_t), freeBuffer, this);
1061
1062            const pid_t origPid = mCallingPid;
1063            const uid_t origUid = mCallingUid;
1064            const int32_t origStrictModePolicy = mStrictModePolicy;
1065            const int32_t origTransactionBinderFlags = mLastTransactionBinderFlags;
1066
1067            mCallingPid = tr.sender_pid;
1068            mCallingUid = tr.sender_euid;
1069            mLastTransactionBinderFlags = tr.flags;
1070
1071            int curPrio = getpriority(PRIO_PROCESS, mMyThreadId);
1072            if (gDisableBackgroundScheduling) {
1073                if (curPrio > ANDROID_PRIORITY_NORMAL) {
1074                    // We have inherited a reduced priority from the caller, but do not
1075                    // want to run in that state in this process.  The driver set our
1076                    // priority already (though not our scheduling class), so bounce
1077                    // it back to the default before invoking the transaction.
1078                    setpriority(PRIO_PROCESS, mMyThreadId, ANDROID_PRIORITY_NORMAL);
1079                }
1080            } else {
1081                if (curPrio >= ANDROID_PRIORITY_BACKGROUND) {
1082                    // We want to use the inherited priority from the caller.
1083                    // Ensure this thread is in the background scheduling class,
1084                    // since the driver won't modify scheduling classes for us.
1085                    // The scheduling group is reset to default by the caller
1086                    // once this method returns after the transaction is complete.
1087                    set_sched_policy(mMyThreadId, SP_BACKGROUND);
1088                }
1089            }
1090
1091            //ALOGI(">>>> TRANSACT from pid %d uid %d\n", mCallingPid, mCallingUid);
1092
1093            Parcel reply;
1094            status_t error;
1095            IF_LOG_TRANSACTIONS() {
1096                TextOutput::Bundle _b(alog);
1097                alog << "BR_TRANSACTION thr " << (void*)pthread_self()
1098                    << " / obj " << tr.target.ptr << " / code "
1099                    << TypeCode(tr.code) << ": " << indent << buffer
1100                    << dedent << endl
1101                    << "Data addr = "
1102                    << reinterpret_cast<const uint8_t*>(tr.data.ptr.buffer)
1103                    << ", offsets addr="
1104                    << reinterpret_cast<const size_t*>(tr.data.ptr.offsets) << endl;
1105            }
1106            if (tr.target.ptr) {
1107                // We only have a weak reference on the target object, so we must first try to
1108                // safely acquire a strong reference before doing anything else with it.
1109                if (reinterpret_cast<RefBase::weakref_type*>(
1110                        tr.target.ptr)->attemptIncStrong(this)) {
1111                    error = reinterpret_cast<BBinder*>(tr.cookie)->transact(tr.code, buffer,
1112                            &reply, tr.flags);
1113                    reinterpret_cast<BBinder*>(tr.cookie)->decStrong(this);
1114                } else {
1115                    error = UNKNOWN_TRANSACTION;
1116                }
1117
1118            } else {
1119                error = the_context_object->transact(tr.code, buffer, &reply, tr.flags);
1120            }
1121
1122            //ALOGI("<<<< TRANSACT from pid %d restore pid %d uid %d\n",
1123            //     mCallingPid, origPid, origUid);
1124
1125            if ((tr.flags & TF_ONE_WAY) == 0) {
1126                LOG_ONEWAY("Sending reply to %d!", mCallingPid);
1127                if (error < NO_ERROR) reply.setError(error);
1128                sendReply(reply, 0);
1129            } else {
1130                LOG_ONEWAY("NOT sending reply to %d!", mCallingPid);
1131            }
1132
1133            mCallingPid = origPid;
1134            mCallingUid = origUid;
1135            mStrictModePolicy = origStrictModePolicy;
1136            mLastTransactionBinderFlags = origTransactionBinderFlags;
1137
1138            IF_LOG_TRANSACTIONS() {
1139                TextOutput::Bundle _b(alog);
1140                alog << "BC_REPLY thr " << (void*)pthread_self() << " / obj "
1141                    << tr.target.ptr << ": " << indent << reply << dedent << endl;
1142            }
1143
1144        }
1145        break;
1146
1147    case BR_DEAD_BINDER:
1148        {
1149            BpBinder *proxy = (BpBinder*)mIn.readPointer();
1150            proxy->sendObituary();
1151            mOut.writeInt32(BC_DEAD_BINDER_DONE);
1152            mOut.writePointer((uintptr_t)proxy);
1153        } break;
1154
1155    case BR_CLEAR_DEATH_NOTIFICATION_DONE:
1156        {
1157            BpBinder *proxy = (BpBinder*)mIn.readPointer();
1158            proxy->getWeakRefs()->decWeak(proxy);
1159        } break;
1160
1161    case BR_FINISHED:
1162        result = TIMED_OUT;
1163        break;
1164
1165    case BR_NOOP:
1166        break;
1167
1168    case BR_SPAWN_LOOPER:
1169        mProcess->spawnPooledThread(false);
1170        break;
1171
1172    default:
1173        printf("*** BAD COMMAND %d received from Binder driver\n", cmd);
1174        result = UNKNOWN_ERROR;
1175        break;
1176    }
1177
1178    if (result != NO_ERROR) {
1179        mLastError = result;
1180    }
1181
1182    return result;
1183}
1184
1185void IPCThreadState::threadDestructor(void *st)
1186{
1187        IPCThreadState* const self = static_cast<IPCThreadState*>(st);
1188        if (self) {
1189                self->flushCommands();
1190#if defined(__ANDROID__)
1191        if (self->mProcess->mDriverFD > 0) {
1192            ioctl(self->mProcess->mDriverFD, BINDER_THREAD_EXIT, 0);
1193        }
1194#endif
1195                delete self;
1196        }
1197}
1198
1199
1200void IPCThreadState::freeBuffer(Parcel* parcel, const uint8_t* data,
1201                                size_t /*dataSize*/,
1202                                const binder_size_t* /*objects*/,
1203                                size_t /*objectsSize*/, void* /*cookie*/)
1204{
1205    //ALOGI("Freeing parcel %p", &parcel);
1206    IF_LOG_COMMANDS() {
1207        alog << "Writing BC_FREE_BUFFER for " << data << endl;
1208    }
1209    ALOG_ASSERT(data != NULL, "Called with NULL data");
1210    if (parcel != NULL) parcel->closeFileDescriptors();
1211    IPCThreadState* state = self();
1212    state->mOut.writeInt32(BC_FREE_BUFFER);
1213    state->mOut.writePointer((uintptr_t)data);
1214}
1215
1216}; // namespace android