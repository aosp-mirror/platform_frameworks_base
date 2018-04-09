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
17#define LOG_TAG "ProcessState"
18
19#include <cutils/process_name.h>
20
21#include <binder/ProcessState.h>
22
23#include <utils/Atomic.h>
24#include <binder/BpBinder.h>
25#include <binder/IPCThreadState.h>
26#include <utils/Log.h>
27#include <utils/String8.h>
28#include <binder/IServiceManager.h>
29#include <utils/String8.h>
30#include <utils/threads.h>
31
32#include <private/binder/binder_module.h>
33#include <private/binder/Static.h>
34
35#include <errno.h>
36#include <fcntl.h>
37#include <stdio.h>
38#include <stdlib.h>
39#include <unistd.h>
40#include <sys/ioctl.h>
41#include <sys/mman.h>
42#include <sys/stat.h>
43#include <sys/types.h>
44
45#define BINDER_VM_SIZE ((1*1024*1024) - (4096 *2))
46#define DEFAULT_MAX_BINDER_THREADS 15
47
48// -------------------------------------------------------------------------
49
50namespace android {
51
52class PoolThread : public Thread
53{
54public:
55    PoolThread(bool isMain)
56        : mIsMain(isMain)
57    {
58    }
59
60protected:
61    virtual bool threadLoop()
62    {
63        IPCThreadState::self()->joinThreadPool(mIsMain);
64        return false;
65    }
66
67    const bool mIsMain;
68};
69
70sp<ProcessState> ProcessState::self()
71{
72    Mutex::Autolock _l(gProcessMutex);
73    if (gProcess != NULL) {
74        return gProcess;
75    }
76    gProcess = new ProcessState;
77    return gProcess;
78}
79
80void ProcessState::setContextObject(const sp<IBinder>& object)
81{
82    setContextObject(object, String16("default"));
83}
84
85sp<IBinder> ProcessState::getContextObject(const sp<IBinder>& /*caller*/)
86{
87    return getStrongProxyForHandle(0);
88}
89
90void ProcessState::setContextObject(const sp<IBinder>& object, const String16& name)
91{
92    AutoMutex _l(mLock);
93    mContexts.add(name, object);
94}
95
96sp<IBinder> ProcessState::getContextObject(const String16& name, const sp<IBinder>& caller)
97{
98    mLock.lock();
99    sp<IBinder> object(
100        mContexts.indexOfKey(name) >= 0 ? mContexts.valueFor(name) : NULL);
101    mLock.unlock();
102
103    //printf("Getting context object %s for %p\n", String8(name).string(), caller.get());
104
105    if (object != NULL) return object;
106
107    // Don't attempt to retrieve contexts if we manage them
108    if (mManagesContexts) {
109        ALOGE("getContextObject(%s) failed, but we manage the contexts!\n",
110            String8(name).string());
111        return NULL;
112    }
113
114    IPCThreadState* ipc = IPCThreadState::self();
115    {
116        Parcel data, reply;
117        // no interface token on this magic transaction
118        data.writeString16(name);
119        data.writeStrongBinder(caller);
120        status_t result = ipc->transact(0 /*magic*/, 0, data, &reply, 0);
121        if (result == NO_ERROR) {
122            object = reply.readStrongBinder();
123        }
124    }
125
126    ipc->flushCommands();
127
128    if (object != NULL) setContextObject(object, name);
129    return object;
130}
131
132void ProcessState::startThreadPool()
133{
134    AutoMutex _l(mLock);
135    if (!mThreadPoolStarted) {
136        mThreadPoolStarted = true;
137        spawnPooledThread(true);
138    }
139}
140
141bool ProcessState::isContextManager(void) const
142{
143    return mManagesContexts;
144}
145
146bool ProcessState::becomeContextManager(context_check_func checkFunc, void* userData)
147{
148    if (!mManagesContexts) {
149        AutoMutex _l(mLock);
150        mBinderContextCheckFunc = checkFunc;
151        mBinderContextUserData = userData;
152
153        int dummy = 0;
154        status_t result = ioctl(mDriverFD, BINDER_SET_CONTEXT_MGR, &dummy);
155        if (result == 0) {
156            mManagesContexts = true;
157        } else if (result == -1) {
158            mBinderContextCheckFunc = NULL;
159            mBinderContextUserData = NULL;
160            ALOGE("Binder ioctl to become context manager failed: %s\n", strerror(errno));
161        }
162    }
163    return mManagesContexts;
164}
165
166ProcessState::handle_entry* ProcessState::lookupHandleLocked(int32_t handle)
167{
168    const size_t N=mHandleToObject.size();
169    if (N <= (size_t)handle) {
170        handle_entry e;
171        e.binder = NULL;
172        e.refs = NULL;
173        status_t err = mHandleToObject.insertAt(e, N, handle+1-N);
174        if (err < NO_ERROR) return NULL;
175    }
176    return &mHandleToObject.editItemAt(handle);
177}
178
179sp<IBinder> ProcessState::getStrongProxyForHandle(int32_t handle)
180{
181    sp<IBinder> result;
182
183    AutoMutex _l(mLock);
184
185    handle_entry* e = lookupHandleLocked(handle);
186
187    if (e != NULL) {
188        // We need to create a new BpBinder if there isn't currently one, OR we
189        // are unable to acquire a weak reference on this current one.  See comment
190        // in getWeakProxyForHandle() for more info about this.
191        IBinder* b = e->binder;
192        if (b == NULL || !e->refs->attemptIncWeak(this)) {
193            if (handle == 0) {
194                // Special case for context manager...
195                // The context manager is the only object for which we create
196                // a BpBinder proxy without already holding a reference.
197                // Perform a dummy transaction to ensure the context manager
198                // is registered before we create the first local reference
199                // to it (which will occur when creating the BpBinder).
200                // If a local reference is created for the BpBinder when the
201                // context manager is not present, the driver will fail to
202                // provide a reference to the context manager, but the
203                // driver API does not return status.
204                //
205                // Note that this is not race-free if the context manager
206                // dies while this code runs.
207                //
208                // TODO: add a driver API to wait for context manager, or
209                // stop special casing handle 0 for context manager and add
210                // a driver API to get a handle to the context manager with
211                // proper reference counting.
212
213                Parcel data;
214                status_t status = IPCThreadState::self()->transact(
215                        0, IBinder::PING_TRANSACTION, data, NULL, 0);
216                if (status == DEAD_OBJECT)
217                   return NULL;
218            }
219
220            b = new BpBinder(handle);
221            e->binder = b;
222            if (b) e->refs = b->getWeakRefs();
223            result = b;
224        } else {
225            // This little bit of nastyness is to allow us to add a primary
226            // reference to the remote proxy when this team doesn't have one
227            // but another team is sending the handle to us.
228            result.force_set(b);
229            e->refs->decWeak(this);
230        }
231    }
232
233    return result;
234}
235
236wp<IBinder> ProcessState::getWeakProxyForHandle(int32_t handle)
237{
238    wp<IBinder> result;
239
240    AutoMutex _l(mLock);
241
242    handle_entry* e = lookupHandleLocked(handle);
243
244    if (e != NULL) {
245        // We need to create a new BpBinder if there isn't currently one, OR we
246        // are unable to acquire a weak reference on this current one.  The
247        // attemptIncWeak() is safe because we know the BpBinder destructor will always
248        // call expungeHandle(), which acquires the same lock we are holding now.
249        // We need to do this because there is a race condition between someone
250        // releasing a reference on this BpBinder, and a new reference on its handle
251        // arriving from the driver.
252        IBinder* b = e->binder;
253        if (b == NULL || !e->refs->attemptIncWeak(this)) {
254            b = new BpBinder(handle);
255            result = b;
256            e->binder = b;
257            if (b) e->refs = b->getWeakRefs();
258        } else {
259            result = b;
260            e->refs->decWeak(this);
261        }
262    }
263
264    return result;
265}
266
267void ProcessState::expungeHandle(int32_t handle, IBinder* binder)
268{
269    AutoMutex _l(mLock);
270
271    handle_entry* e = lookupHandleLocked(handle);
272
273    // This handle may have already been replaced with a new BpBinder
274    // (if someone failed the AttemptIncWeak() above); we don't want
275    // to overwrite it.
276    if (e && e->binder == binder) e->binder = NULL;
277}
278
279String8 ProcessState::makeBinderThreadName() {
280    int32_t s = android_atomic_add(1, &mThreadPoolSeq);
281    pid_t pid = getpid();
282    String8 name;
283    name.appendFormat("Binder:%d_%X", pid, s);
284    return name;
285}
286
287void ProcessState::spawnPooledThread(bool isMain)
288{
289    if (mThreadPoolStarted) {
290        String8 name = makeBinderThreadName();
291        ALOGV("Spawning new pooled thread, name=%s\n", name.string());
292        sp<Thread> t = new PoolThread(isMain);
293        t->run(name.string());
294    }
295}
296
297status_t ProcessState::setThreadPoolMaxThreadCount(size_t maxThreads) {
298    status_t result = NO_ERROR;
299    if (ioctl(mDriverFD, BINDER_SET_MAX_THREADS, &maxThreads) != -1) {
300        mMaxThreads = maxThreads;
301    } else {
302        result = -errno;
303        ALOGE("Binder ioctl to set max threads failed: %s", strerror(-result));
304    }
305    return result;
306}
307
308void ProcessState::giveThreadPoolName() {
309    androidSetThreadName( makeBinderThreadName().string() );
310}
311
312static int open_driver()
313{
314    int fd = open("/dev/binder", O_RDWR | O_CLOEXEC);
315    if (fd >= 0) {
316        int vers = 0;
317        status_t result = ioctl(fd, BINDER_VERSION, &vers);
318        if (result == -1) {
319            ALOGE("Binder ioctl to obtain version failed: %s", strerror(errno));
320            close(fd);
321            fd = -1;
322        }
323        if (result != 0 || vers != BINDER_CURRENT_PROTOCOL_VERSION) {
324            ALOGE("Binder driver protocol does not match user space protocol!");
325            close(fd);
326            fd = -1;
327        }
328        size_t maxThreads = DEFAULT_MAX_BINDER_THREADS;
329        result = ioctl(fd, BINDER_SET_MAX_THREADS, &maxThreads);
330        if (result == -1) {
331            ALOGE("Binder ioctl to set max threads failed: %s", strerror(errno));
332        }
333    } else {
334        ALOGW("Opening '/dev/binder' failed: %s\n", strerror(errno));
335    }
336    return fd;
337}
338
339ProcessState::ProcessState()
340    : mDriverFD(open_driver())
341    , mVMStart(MAP_FAILED)
342    , mThreadCountLock(PTHREAD_MUTEX_INITIALIZER)
343    , mThreadCountDecrement(PTHREAD_COND_INITIALIZER)
344    , mExecutingThreadsCount(0)
345    , mMaxThreads(DEFAULT_MAX_BINDER_THREADS)
346    , mStarvationStartTimeMs(0)
347    , mManagesContexts(false)
348    , mBinderContextCheckFunc(NULL)
349    , mBinderContextUserData(NULL)
350    , mThreadPoolStarted(false)
351    , mThreadPoolSeq(1)
352{
353    if (mDriverFD >= 0) {
354        // mmap the binder, providing a chunk of virtual address space to receive transactions.
355        mVMStart = mmap(0, BINDER_VM_SIZE, PROT_READ, MAP_PRIVATE | MAP_NORESERVE, mDriverFD, 0);
356        if (mVMStart == MAP_FAILED) {
357            // *sigh*
358            ALOGE("Using /dev/binder failed: unable to mmap transaction memory.\n");
359            close(mDriverFD);
360            mDriverFD = -1;
361        }
362    }
363
364    LOG_ALWAYS_FATAL_IF(mDriverFD < 0, "Binder driver could not be opened.  Terminating.");
365}
366
367ProcessState::~ProcessState()
368{
369}
370
371}; // namespace android