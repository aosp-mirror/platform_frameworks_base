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

#ifndef ANDROID_SF_SHARED_BUFFER_STACK_H
#define ANDROID_SF_SHARED_BUFFER_STACK_H

#include <stdint.h>
#include <sys/types.h>

#include <cutils/compiler.h>

#include <utils/Debug.h>
#include <utils/threads.h>
#include <utils/String8.h>

#include <ui/Rect.h>

namespace android {
// ---------------------------------------------------------------------------

/*
 * These classes manage a stack of buffers in shared memory.
 * 
 * SharedClient: represents a client with several stacks
 * SharedBufferStack: represents a stack of buffers
 * SharedBufferClient: manipulates the SharedBufferStack from the client side 
 * SharedBufferServer: manipulates the SharedBufferStack from the server side 
 *
 * Buffers can be dequeued until there are none available, they can be locked
 * unless they are in use by the server, which is only the case for the last 
 * dequeue-able buffer. When these various conditions are not met, the caller
 * waits until the condition is met.
 *
 * 
 * CAVEATS:
 * 
 * In the current implementation there are several limitations:
 * - buffers must be locked in the same order they've been dequeued
 * - buffers must be enqueued in the same order they've been locked
 * - dequeue() is not reentrant
 * - no error checks are done on the condition above
 * 
 */

// When changing these values, the COMPILE_TIME_ASSERT at the end of this
// file need to be updated.
const unsigned int NUM_LAYERS_MAX  = 31;
const unsigned int NUM_BUFFER_MAX  = 16;
const unsigned int NUM_DISPLAY_MAX = 4;

// ----------------------------------------------------------------------------

class Region;
class SharedBufferStack;
class SharedClient;

// ----------------------------------------------------------------------------

// 4 * (11 + 7 + (1 + 2*NUM_RECT_MAX) * NUM_BUFFER_MAX) * NUM_LAYERS_MAX
// 4 * (11 + 7 + (1 + 2*6)*16) * 31
// 904 * 31
// = ~27 KiB (28024)

class SharedBufferStack
{
    friend class SharedClient;
    friend class SharedBufferBase;
    friend class SharedBufferClient;
    friend class SharedBufferServer;

public:
    struct FlatRegion { // 52 bytes = 4 * (1 + 2*N)
        static const unsigned int NUM_RECT_MAX = 6;
        uint32_t    count;
        uint16_t    rects[4*NUM_RECT_MAX];
    };
    
    struct Statistics { // 4 longs
        typedef int32_t usecs_t;
        usecs_t  totalTime;
        usecs_t  reserved[3];
    };
    
    SharedBufferStack();
    void init(int32_t identity);
    status_t setDirtyRegion(int buffer, const Region& reg);
    Region getDirtyRegion(int buffer) const;

    // these attributes are part of the conditions/updates
    volatile int32_t head;      // server's current front buffer
    volatile int32_t available; // number of dequeue-able buffers
    volatile int32_t queued;    // number of buffers waiting for post
    volatile int32_t inUse;     // buffer currently in use by SF
    volatile status_t status;   // surface's status code

    // not part of the conditions
    volatile int32_t reallocMask;

    int32_t     identity;       // surface's identity (const)
    int32_t     reserved32[6];
    Statistics  stats;
    int32_t     reserved;
    FlatRegion  dirtyRegion[NUM_BUFFER_MAX]; // 832 bytes
};

// ----------------------------------------------------------------------------

// 4 KB max
class SharedClient
{
public:
    SharedClient();
    ~SharedClient();

    status_t validate(size_t token) const;
    uint32_t getIdentity(size_t token) const;

private:
    friend class SharedBufferBase;
    friend class SharedBufferClient;
    friend class SharedBufferServer;

    // FIXME: this should be replaced by a lock-less primitive
    Mutex lock;
    Condition cv;
    SharedBufferStack surfaces[ NUM_LAYERS_MAX ];
};

// ============================================================================

class SharedBufferBase
{
public:
    SharedBufferBase(SharedClient* sharedClient, int surface, int num,
            int32_t identity);
    ~SharedBufferBase();
    uint32_t getIdentity();
    status_t getStatus() const;
    size_t getFrontBuffer() const;
    String8 dump(char const* prefix) const;

protected:
    SharedClient* const mSharedClient;
    SharedBufferStack* const mSharedStack;
    const int mNumBuffers;
    const int mIdentity;

    friend struct Update;
    friend struct QueueUpdate;

    struct ConditionBase {
        SharedBufferStack& stack;
        inline ConditionBase(SharedBufferBase* sbc) 
            : stack(*sbc->mSharedStack) { }
    };

    struct UpdateBase {
        SharedBufferStack& stack;
        inline UpdateBase(SharedBufferBase* sbb) 
            : stack(*sbb->mSharedStack) { }
    };

    template <typename T>
    status_t waitForCondition(T condition);

    template <typename T>
    status_t updateCondition(T update);
};

template <typename T>
status_t SharedBufferBase::waitForCondition(T condition) 
{
    const SharedBufferStack& stack( *mSharedStack );
    SharedClient& client( *mSharedClient );
    const nsecs_t TIMEOUT = s2ns(1);
    Mutex::Autolock _l(client.lock);
    while ((condition()==false) &&
            (stack.identity == mIdentity) &&
            (stack.status == NO_ERROR))
    {
        status_t err = client.cv.waitRelative(client.lock, TIMEOUT);
        
        // handle errors and timeouts
        if (CC_UNLIKELY(err != NO_ERROR)) {
            if (err == TIMED_OUT) {
                if (condition()) {
                    LOGE("waitForCondition(%s) timed out (identity=%d), "
                        "but condition is true! We recovered but it "
                        "shouldn't happen." , T::name(),
                        stack.identity);
                    break;
                } else {
                    LOGW("waitForCondition(%s) timed out "
                        "(identity=%d, status=%d). "
                        "CPU may be pegged. trying again.", T::name(),
                        stack.identity, stack.status);
                }
            } else {
                LOGE("waitForCondition(%s) error (%s) ",
                        T::name(), strerror(-err));
                return err;
            }
        }
    }
    return (stack.identity != mIdentity) ? status_t(BAD_INDEX) : stack.status;
}


template <typename T>
status_t SharedBufferBase::updateCondition(T update) {
    SharedClient& client( *mSharedClient );
    Mutex::Autolock _l(client.lock);
    ssize_t result = update();
    client.cv.broadcast();    
    return result;
}

// ----------------------------------------------------------------------------

class SharedBufferClient : public SharedBufferBase
{
public:
    SharedBufferClient(SharedClient* sharedClient, int surface, int num,
            int32_t identity);

    ssize_t dequeue();
    status_t undoDequeue(int buf);
    
    status_t lock(int buf);
    status_t queue(int buf);
    bool needNewBuffer(int buffer) const;
    status_t setDirtyRegion(int buffer, const Region& reg);
    
private:
    friend struct Condition;
    friend struct DequeueCondition;
    friend struct LockCondition;
    
    int32_t computeTail() const;

    struct QueueUpdate : public UpdateBase {
        inline QueueUpdate(SharedBufferBase* sbb);
        inline ssize_t operator()();
    };

    struct UndoDequeueUpdate : public UpdateBase {
        inline UndoDequeueUpdate(SharedBufferBase* sbb);
        inline ssize_t operator()();
    };

    // --

    struct DequeueCondition : public ConditionBase {
        inline DequeueCondition(SharedBufferClient* sbc);
        inline bool operator()();
        static inline const char* name() { return "DequeueCondition"; }
    };

    struct LockCondition : public ConditionBase {
        int buf;
        inline LockCondition(SharedBufferClient* sbc, int buf);
        inline bool operator()();
        static inline const char* name() { return "LockCondition"; }
    };

    int32_t tail;
    // statistics...
    nsecs_t mDequeueTime[NUM_BUFFER_MAX];
};

// ----------------------------------------------------------------------------

class SharedBufferServer : public SharedBufferBase
{
public:
    SharedBufferServer(SharedClient* sharedClient, int surface, int num,
            int32_t identity);

    ssize_t retireAndLock();
    status_t unlock(int buffer);
    void setStatus(status_t status);
    status_t reallocate();
    status_t assertReallocate(int buffer);
    int32_t getQueuedCount() const;
    
    Region getDirtyRegion(int buffer) const;

    SharedBufferStack::Statistics getStats() const;
    

private:
    struct UnlockUpdate : public UpdateBase {
        const int lockedBuffer;
        inline UnlockUpdate(SharedBufferBase* sbb, int lockedBuffer);
        inline ssize_t operator()();
    };

    struct RetireUpdate : public UpdateBase {
        const int numBuffers;
        inline RetireUpdate(SharedBufferBase* sbb, int numBuffers);
        inline ssize_t operator()();
    };

    struct StatusUpdate : public UpdateBase {
        const status_t status;
        inline StatusUpdate(SharedBufferBase* sbb, status_t status);
        inline ssize_t operator()();
    };

    struct ReallocateCondition : public ConditionBase {
        int buf;
        inline ReallocateCondition(SharedBufferBase* sbb, int buf);
        inline bool operator()();
        static inline const char* name() { return "ReallocateCondition"; }
    };
};

// ===========================================================================

struct display_cblk_t
{
    uint16_t    w;
    uint16_t    h;
    uint8_t     format;
    uint8_t     orientation;
    uint8_t     reserved[2];
    float       fps;
    float       density;
    float       xdpi;
    float       ydpi;
    uint32_t    pad[2];
};

struct surface_flinger_cblk_t   // 4KB max
{
    uint8_t         connected;
    uint8_t         reserved[3];
    uint32_t        pad[7];
    display_cblk_t  displays[NUM_DISPLAY_MAX];
};

// ---------------------------------------------------------------------------

COMPILE_TIME_ASSERT(sizeof(SharedClient) <= 32768)
COMPILE_TIME_ASSERT(sizeof(surface_flinger_cblk_t) <= 4096)

// ---------------------------------------------------------------------------
}; // namespace android

#endif /* ANDROID_SF_SHARED_BUFFER_STACK_H */
