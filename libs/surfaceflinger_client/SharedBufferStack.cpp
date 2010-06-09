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

#define LOG_TAG "SharedBufferStack"

#include <stdint.h>
#include <sys/types.h>

#include <utils/Debug.h>
#include <utils/Log.h>
#include <utils/threads.h>

#include <private/surfaceflinger/SharedBufferStack.h>

#include <ui/Rect.h>
#include <ui/Region.h>

#define DEBUG_ATOMICS 0

namespace android {
// ----------------------------------------------------------------------------

SharedClient::SharedClient()
    : lock(Mutex::SHARED), cv(Condition::SHARED)
{
}

SharedClient::~SharedClient() {
}


// these functions are used by the clients
status_t SharedClient::validate(size_t i) const {
    if (uint32_t(i) >= uint32_t(SharedBufferStack::NUM_LAYERS_MAX))
        return BAD_INDEX;
    return surfaces[i].status;
}

// ----------------------------------------------------------------------------


SharedBufferStack::SharedBufferStack()
{
}

void SharedBufferStack::init(int32_t i)
{
    inUse = -2;
    status = NO_ERROR;
    identity = i;
}

status_t SharedBufferStack::setCrop(int buffer, const Rect& crop)
{
    if (uint32_t(buffer) >= NUM_BUFFER_MAX)
        return BAD_INDEX;

    buffers[buffer].crop.l = uint16_t(crop.left);
    buffers[buffer].crop.t = uint16_t(crop.top);
    buffers[buffer].crop.r = uint16_t(crop.right);
    buffers[buffer].crop.b = uint16_t(crop.bottom);
    return NO_ERROR;
}

status_t SharedBufferStack::setDirtyRegion(int buffer, const Region& dirty)
{
    if (uint32_t(buffer) >= NUM_BUFFER_MAX)
        return BAD_INDEX;

    FlatRegion& reg(buffers[buffer].dirtyRegion);
    if (dirty.isEmpty()) {
        reg.count = 0;
        return NO_ERROR;
    }

    size_t count;
    Rect const* r = dirty.getArray(&count);
    if (count > FlatRegion::NUM_RECT_MAX) {
        const Rect bounds(dirty.getBounds());
        reg.count = 1;
        reg.rects[0].l = uint16_t(bounds.left);
        reg.rects[0].t = uint16_t(bounds.top);
        reg.rects[0].r = uint16_t(bounds.right);
        reg.rects[0].b = uint16_t(bounds.bottom);
    } else {
        reg.count = count;
        for (size_t i=0 ; i<count ; i++) {
            reg.rects[i].l = uint16_t(r[i].left);
            reg.rects[i].t = uint16_t(r[i].top);
            reg.rects[i].r = uint16_t(r[i].right);
            reg.rects[i].b = uint16_t(r[i].bottom);
        }
    }
    return NO_ERROR;
}

Region SharedBufferStack::getDirtyRegion(int buffer) const
{
    Region res;
    if (uint32_t(buffer) >= NUM_BUFFER_MAX)
        return res;

    const FlatRegion& reg(buffers[buffer].dirtyRegion);
    if (reg.count > FlatRegion::NUM_RECT_MAX)
        return res;

    if (reg.count == 1) {
        const Rect r(
                reg.rects[0].l,
                reg.rects[0].t,
                reg.rects[0].r,
                reg.rects[0].b);
        res.set(r);
    } else {
        for (size_t i=0 ; i<reg.count ; i++) {
            const Rect r(
                    reg.rects[i].l,
                    reg.rects[i].t,
                    reg.rects[i].r,
                    reg.rects[i].b);
            res.orSelf(r);
        }
    }
    return res;
}

// ----------------------------------------------------------------------------

SharedBufferBase::SharedBufferBase(SharedClient* sharedClient,
        int surface, int32_t identity)
    : mSharedClient(sharedClient), 
      mSharedStack(sharedClient->surfaces + surface),
      mIdentity(identity)
{
}

SharedBufferBase::~SharedBufferBase()
{
}

status_t SharedBufferBase::getStatus() const
{
    SharedBufferStack& stack( *mSharedStack );
    return stack.status;
}

int32_t SharedBufferBase::getIdentity() const
{
    SharedBufferStack& stack( *mSharedStack );
    return stack.identity;
}

size_t SharedBufferBase::getFrontBuffer() const
{
    SharedBufferStack& stack( *mSharedStack );
    return size_t( stack.head );
}

String8 SharedBufferBase::dump(char const* prefix) const
{
    const size_t SIZE = 1024;
    char buffer[SIZE];
    String8 result;
    SharedBufferStack& stack( *mSharedStack );
    snprintf(buffer, SIZE, 
            "%s[ head=%2d, available=%2d, queued=%2d ] "
            "reallocMask=%08x, inUse=%2d, identity=%d, status=%d",
            prefix, stack.head, stack.available, stack.queued,
            stack.reallocMask, stack.inUse, stack.identity, stack.status);
    result.append(buffer);
    result.append("\n");
    return result;
}

status_t SharedBufferBase::waitForCondition(const ConditionBase& condition)
{
    const SharedBufferStack& stack( *mSharedStack );
    SharedClient& client( *mSharedClient );
    const nsecs_t TIMEOUT = s2ns(1);
    const int identity = mIdentity;

    Mutex::Autolock _l(client.lock);
    while ((condition()==false) &&
            (stack.identity == identity) &&
            (stack.status == NO_ERROR))
    {
        status_t err = client.cv.waitRelative(client.lock, TIMEOUT);
        // handle errors and timeouts
        if (CC_UNLIKELY(err != NO_ERROR)) {
            if (err == TIMED_OUT) {
                if (condition()) {
                    LOGE("waitForCondition(%s) timed out (identity=%d), "
                        "but condition is true! We recovered but it "
                        "shouldn't happen." , condition.name(), stack.identity);
                    break;
                } else {
                    LOGW("waitForCondition(%s) timed out "
                        "(identity=%d, status=%d). "
                        "CPU may be pegged. trying again.", condition.name(),
                        stack.identity, stack.status);
                }
            } else {
                LOGE("waitForCondition(%s) error (%s) ",
                        condition.name(), strerror(-err));
                return err;
            }
        }
    }
    return (stack.identity != mIdentity) ? status_t(BAD_INDEX) : stack.status;
}
// ============================================================================
// conditions and updates
// ============================================================================

SharedBufferClient::DequeueCondition::DequeueCondition(
        SharedBufferClient* sbc) : ConditionBase(sbc)  { 
}
bool SharedBufferClient::DequeueCondition::operator()() const {
    return stack.available > 0;
}

SharedBufferClient::LockCondition::LockCondition(
        SharedBufferClient* sbc, int buf) : ConditionBase(sbc), buf(buf) { 
}
bool SharedBufferClient::LockCondition::operator()() const {
    // NOTE: if stack.head is messed up, we could crash the client
    // or cause some drawing artifacts. This is okay, as long as it is
    // limited to the client.
    return (buf != stack.index[stack.head] ||
            (stack.queued > 0 && stack.inUse != buf));
}

SharedBufferServer::ReallocateCondition::ReallocateCondition(
        SharedBufferBase* sbb, int buf) : ConditionBase(sbb), buf(buf) { 
}
bool SharedBufferServer::ReallocateCondition::operator()() const {
    int32_t head = stack.head;
    if (uint32_t(head) >= SharedBufferStack::NUM_BUFFER_MAX) {
        // if stack.head is messed up, we cannot allow the server to
        // crash (since stack.head is mapped on the client side)
        stack.status = BAD_VALUE;
        return false;
    }
    // TODO: we should also check that buf has been dequeued
    return (buf != stack.index[head]);
}

// ----------------------------------------------------------------------------

SharedBufferClient::QueueUpdate::QueueUpdate(SharedBufferBase* sbb)
    : UpdateBase(sbb) {    
}
ssize_t SharedBufferClient::QueueUpdate::operator()() {
    android_atomic_inc(&stack.queued);
    return NO_ERROR;
}

SharedBufferClient::UndoDequeueUpdate::UndoDequeueUpdate(SharedBufferBase* sbb)
    : UpdateBase(sbb) {    
}
ssize_t SharedBufferClient::UndoDequeueUpdate::operator()() {
    android_atomic_inc(&stack.available);
    return NO_ERROR;
}

SharedBufferServer::UnlockUpdate::UnlockUpdate(
        SharedBufferBase* sbb, int lockedBuffer)
    : UpdateBase(sbb), lockedBuffer(lockedBuffer) {
}
ssize_t SharedBufferServer::UnlockUpdate::operator()() {
    if (stack.inUse != lockedBuffer) {
        LOGE("unlocking %d, but currently locked buffer is %d "
             "(identity=%d, token=%d)",
                lockedBuffer, stack.inUse,
                stack.identity, stack.token);
        return BAD_VALUE;
    }
    android_atomic_write(-1, &stack.inUse);
    return NO_ERROR;
}

SharedBufferServer::RetireUpdate::RetireUpdate(
        SharedBufferBase* sbb, int numBuffers)
    : UpdateBase(sbb), numBuffers(numBuffers) {
}
ssize_t SharedBufferServer::RetireUpdate::operator()() {
    int32_t head = stack.head;
    if (uint32_t(head) >= SharedBufferStack::NUM_BUFFER_MAX)
        return BAD_VALUE;

    // Preventively lock the current buffer before updating queued.
    android_atomic_write(stack.index[head], &stack.inUse);

    // Decrement the number of queued buffers 
    int32_t queued;
    do {
        queued = stack.queued;
        if (queued == 0) {
            return NOT_ENOUGH_DATA;
        }
    } while (android_atomic_cmpxchg(queued, queued-1, &stack.queued));
    
    // lock the buffer before advancing head, which automatically unlocks
    // the buffer we preventively locked upon entering this function

    head = (head + 1) % numBuffers;
    android_atomic_write(stack.index[head], &stack.inUse);

    // head is only modified here, so we don't need to use cmpxchg
    android_atomic_write(head, &stack.head);

    // now that head has moved, we can increment the number of available buffers
    android_atomic_inc(&stack.available);
    return head;
}

SharedBufferServer::StatusUpdate::StatusUpdate(
        SharedBufferBase* sbb, status_t status)
    : UpdateBase(sbb), status(status) {
}

ssize_t SharedBufferServer::StatusUpdate::operator()() {
    android_atomic_write(status, &stack.status);
    return NO_ERROR;
}

// ============================================================================

SharedBufferClient::SharedBufferClient(SharedClient* sharedClient,
        int surface, int num, int32_t identity)
    : SharedBufferBase(sharedClient, surface, identity),
      mNumBuffers(num), tail(0), undoDequeueTail(0)
{
    SharedBufferStack& stack( *mSharedStack );
    tail = computeTail();
    queued_head = stack.head;
}

int32_t SharedBufferClient::computeTail() const
{
    SharedBufferStack& stack( *mSharedStack );
    return (mNumBuffers + stack.head - stack.available + 1) % mNumBuffers;
}

ssize_t SharedBufferClient::dequeue()
{
    SharedBufferStack& stack( *mSharedStack );

    if (stack.head == tail && stack.available == mNumBuffers) {
        LOGW("dequeue: tail=%d, head=%d, avail=%d, queued=%d",
                tail, stack.head, stack.available, stack.queued);
    }

    RWLock::AutoRLock _rd(mLock);

    const nsecs_t dequeueTime = systemTime(SYSTEM_TIME_THREAD);

    //LOGD("[%d] about to dequeue a buffer",
    //        mSharedStack->identity);
    DequeueCondition condition(this);
    status_t err = waitForCondition(condition);
    if (err != NO_ERROR)
        return ssize_t(err);

    // NOTE: 'stack.available' is part of the conditions, however
    // decrementing it, never changes any conditions, so we don't need
    // to do this as part of an update.
    if (android_atomic_dec(&stack.available) == 0) {
        LOGW("dequeue probably called from multiple threads!");
    }

    undoDequeueTail = tail;
    int dequeued = stack.index[tail];
    tail = ((tail+1 >= mNumBuffers) ? 0 : tail+1);
    LOGD_IF(DEBUG_ATOMICS, "dequeued=%d, tail++=%d, %s",
            dequeued, tail, dump("").string());

    mDequeueTime[dequeued] = dequeueTime; 

    return dequeued;
}

status_t SharedBufferClient::undoDequeue(int buf)
{
    RWLock::AutoRLock _rd(mLock);

    // TODO: we can only undo the previous dequeue, we should
    // enforce that in the api
    UndoDequeueUpdate update(this);
    status_t err = updateCondition( update );
    if (err == NO_ERROR) {
        tail = undoDequeueTail;
    }
    return err;
}

status_t SharedBufferClient::lock(int buf)
{
    RWLock::AutoRLock _rd(mLock);

    SharedBufferStack& stack( *mSharedStack );
    LockCondition condition(this, buf);
    status_t err = waitForCondition(condition);
    return err;
}

status_t SharedBufferClient::queue(int buf)
{
    RWLock::AutoRLock _rd(mLock);

    SharedBufferStack& stack( *mSharedStack );

    queued_head = (queued_head + 1) % mNumBuffers;
    stack.index[queued_head] = buf;

    QueueUpdate update(this);
    status_t err = updateCondition( update );
    LOGD_IF(DEBUG_ATOMICS, "queued=%d, %s", buf, dump("").string());

    const nsecs_t now = systemTime(SYSTEM_TIME_THREAD);
    stack.stats.totalTime = ns2us(now - mDequeueTime[buf]);
    return err;
}

bool SharedBufferClient::needNewBuffer(int buf) const
{
    SharedBufferStack& stack( *mSharedStack );
    const uint32_t mask = 1<<(31-buf);
    return (android_atomic_and(~mask, &stack.reallocMask) & mask) != 0;
}

status_t SharedBufferClient::setCrop(int buf, const Rect& crop)
{
    SharedBufferStack& stack( *mSharedStack );
    return stack.setCrop(buf, crop);
}

status_t SharedBufferClient::setDirtyRegion(int buf, const Region& reg)
{
    SharedBufferStack& stack( *mSharedStack );
    return stack.setDirtyRegion(buf, reg);
}

status_t SharedBufferClient::setBufferCount(
        int bufferCount, const SetBufferCountCallback& ipc)
{
    SharedBufferStack& stack( *mSharedStack );
    if (uint32_t(bufferCount) >= SharedBufferStack::NUM_BUFFER_MAX)
        return BAD_VALUE;

    if (uint32_t(bufferCount) < SharedBufferStack::NUM_BUFFER_MIN)
        return BAD_VALUE;

    RWLock::AutoWLock _wr(mLock);

    status_t err = ipc(bufferCount);
    if (err == NO_ERROR) {
        mNumBuffers = bufferCount;
        queued_head = (stack.head + stack.queued) % mNumBuffers;
    }
    return err;
}

// ----------------------------------------------------------------------------

SharedBufferServer::SharedBufferServer(SharedClient* sharedClient,
        int surface, int num, int32_t identity)
    : SharedBufferBase(sharedClient, surface, identity),
      mNumBuffers(num)
{
    mSharedStack->init(identity);
    mSharedStack->token = surface;
    mSharedStack->head = num-1;
    mSharedStack->available = num;
    mSharedStack->queued = 0;
    mSharedStack->reallocMask = 0;
    memset(mSharedStack->buffers, 0, sizeof(mSharedStack->buffers));
    for (int i=0 ; i<num ; i++) {
        mBufferList.add(i);
        mSharedStack->index[i] = i;
    }
}

SharedBufferServer::~SharedBufferServer()
{
}

ssize_t SharedBufferServer::retireAndLock()
{
    RWLock::AutoRLock _l(mLock);

    RetireUpdate update(this, mNumBuffers);
    ssize_t buf = updateCondition( update );
    if (buf >= 0) {
        if (uint32_t(buf) >= SharedBufferStack::NUM_BUFFER_MAX)
            return BAD_VALUE;
        SharedBufferStack& stack( *mSharedStack );
        buf = stack.index[buf];
        LOGD_IF(DEBUG_ATOMICS && buf>=0, "retire=%d, %s",
                int(buf), dump("").string());
    }
    return buf;
}

status_t SharedBufferServer::unlock(int buf)
{
    UnlockUpdate update(this, buf);
    status_t err = updateCondition( update );
    return err;
}

void SharedBufferServer::setStatus(status_t status)
{
    if (status < NO_ERROR) {
        StatusUpdate update(this, status);
        updateCondition( update );
    }
}

status_t SharedBufferServer::reallocateAll()
{
    RWLock::AutoRLock _l(mLock);

    SharedBufferStack& stack( *mSharedStack );
    uint32_t mask = mBufferList.getMask();
    android_atomic_or(mask, &stack.reallocMask);
    return NO_ERROR;
}

status_t SharedBufferServer::reallocateAllExcept(int buffer)
{
    RWLock::AutoRLock _l(mLock);

    SharedBufferStack& stack( *mSharedStack );
    BufferList temp(mBufferList);
    temp.remove(buffer);
    uint32_t mask = temp.getMask();
    android_atomic_or(mask, &stack.reallocMask);
    return NO_ERROR;
}

int32_t SharedBufferServer::getQueuedCount() const
{
    SharedBufferStack& stack( *mSharedStack );
    return stack.queued;
}

status_t SharedBufferServer::assertReallocate(int buf)
{
    /*
     * NOTE: it's safe to hold mLock for read while waiting for
     * the ReallocateCondition because that condition is not updated
     * by the thread that holds mLock for write.
     */
    RWLock::AutoRLock _l(mLock);

    // TODO: need to validate "buf"
    ReallocateCondition condition(this, buf);
    status_t err = waitForCondition(condition);
    return err;
}

Region SharedBufferServer::getDirtyRegion(int buf) const
{
    SharedBufferStack& stack( *mSharedStack );
    return stack.getDirtyRegion(buf);
}

/*
 * NOTE: this is not thread-safe on the server-side, meaning
 * 'head' cannot move during this operation. The client-side
 * can safely operate an usual.
 *
 */
status_t SharedBufferServer::resize(int newNumBuffers)
{
    if (uint32_t(newNumBuffers) >= SharedBufferStack::NUM_BUFFER_MAX)
        return BAD_VALUE;

    RWLock::AutoWLock _l(mLock);

    // for now we're not supporting shrinking
    const int numBuffers = mNumBuffers;
    if (newNumBuffers < numBuffers)
        return BAD_VALUE;

    SharedBufferStack& stack( *mSharedStack );
    const int extra = newNumBuffers - numBuffers;

    // read the head, make sure it's valid
    int32_t head = stack.head;
    if (uint32_t(head) >= SharedBufferStack::NUM_BUFFER_MAX)
        return BAD_VALUE;

    int base = numBuffers;
    int32_t avail = stack.available;
    int tail = head - avail + 1;

    if (tail >= 0) {
        int8_t* const index = const_cast<int8_t*>(stack.index);
        const int nb = numBuffers - head;
        memmove(&index[head + extra], &index[head], nb);
        base = head;
        // move head 'extra' ahead, this doesn't impact stack.index[head];
        stack.head = head + extra;
    }
    stack.available += extra;

    // fill the new free space with unused buffers
    BufferList::const_iterator curr(mBufferList.free_begin());
    for (int i=0 ; i<extra ; i++) {
        stack.index[base+i] = *curr;
        mBufferList.add(*curr);
        ++curr;
    }

    mNumBuffers = newNumBuffers;
    return NO_ERROR;
}

SharedBufferStack::Statistics SharedBufferServer::getStats() const
{
    SharedBufferStack& stack( *mSharedStack );
    return stack.stats;
}

// ---------------------------------------------------------------------------
status_t SharedBufferServer::BufferList::add(int value)
{
    if (uint32_t(value) >= mCapacity)
        return BAD_VALUE;
    uint32_t mask = 1<<(31-value);
    if (mList & mask)
        return ALREADY_EXISTS;
    mList |= mask;
    return NO_ERROR;
}

status_t SharedBufferServer::BufferList::remove(int value)
{
    if (uint32_t(value) >= mCapacity)
        return BAD_VALUE;
    uint32_t mask = 1<<(31-value);
    if (!(mList & mask))
        return NAME_NOT_FOUND;
    mList &= ~mask;
    return NO_ERROR;
}


// ---------------------------------------------------------------------------
}; // namespace android
