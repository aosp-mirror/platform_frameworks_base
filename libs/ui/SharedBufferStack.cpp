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

#include <private/ui/SharedBufferStack.h>

#include <ui/Rect.h>
#include <ui/Region.h>

#define DEBUG_ATOMICS 0

namespace android {
// ----------------------------------------------------------------------------

SharedClient::SharedClient()
    : lock(Mutex::SHARED)
{
}

SharedClient::~SharedClient() {
}


// these functions are used by the clients
status_t SharedClient::validate(size_t i) const {
    if (uint32_t(i) >= uint32_t(NUM_LAYERS_MAX))
        return BAD_INDEX;
    return surfaces[i].status;
}

uint32_t SharedClient::getIdentity(size_t token) const {
    return uint32_t(surfaces[token].identity);
}

// ----------------------------------------------------------------------------


SharedBufferStack::SharedBufferStack()
{
}

void SharedBufferStack::init(int32_t i)
{
    inUse = -1;
    status = NO_ERROR;
    identity = i;
}

status_t SharedBufferStack::setDirtyRegion(int buffer, const Region& dirty)
{
    if (uint32_t(buffer) >= NUM_BUFFER_MAX)
        return BAD_INDEX;

    // in the current implementation we only send a single rectangle
    const Rect bounds(dirty.getBounds());
    FlatRegion& reg(dirtyRegion[buffer]);
    reg.count = 1;
    reg.rects[0] = uint16_t(bounds.left);
    reg.rects[1] = uint16_t(bounds.top);
    reg.rects[2] = uint16_t(bounds.right);
    reg.rects[3] = uint16_t(bounds.bottom);
    return NO_ERROR;
}

Region SharedBufferStack::getDirtyRegion(int buffer) const
{
    Region res;
    if (uint32_t(buffer) >= NUM_BUFFER_MAX)
        return res;

    const FlatRegion& reg(dirtyRegion[buffer]);
    res.set(Rect(reg.rects[0], reg.rects[1], reg.rects[2], reg.rects[3]));
    return res;
}

// ----------------------------------------------------------------------------

SharedBufferBase::SharedBufferBase(SharedClient* sharedClient,
        int surface, int num)
    : mSharedClient(sharedClient), 
      mSharedStack(sharedClient->surfaces + surface),
      mNumBuffers(num)
{
}

SharedBufferBase::~SharedBufferBase()
{
}

uint32_t SharedBufferBase::getIdentity()
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
            "reallocMask=%08x, inUse=%2d, identity=%d, status=%d\n",
            prefix, stack.head, stack.available, stack.queued, 
            stack.reallocMask, stack.inUse, stack.identity, stack.status);
    result.append(buffer);
    return result;
}


// ============================================================================
// conditions and updates
// ============================================================================

SharedBufferClient::DequeueCondition::DequeueCondition(
        SharedBufferClient* sbc) : ConditionBase(sbc)  { 
}
bool SharedBufferClient::DequeueCondition::operator()() {
    return stack.available > 0;
}

SharedBufferClient::LockCondition::LockCondition(
        SharedBufferClient* sbc, int buf) : ConditionBase(sbc), buf(buf) { 
}
bool SharedBufferClient::LockCondition::operator()() {
    return (buf != stack.head || 
            (stack.queued > 0 && stack.inUse != buf));
}

SharedBufferServer::ReallocateCondition::ReallocateCondition(
        SharedBufferBase* sbb, int buf) : ConditionBase(sbb), buf(buf) { 
}
bool SharedBufferServer::ReallocateCondition::operator()() {
    // TODO: we should also check that buf has been dequeued
    return (buf != stack.head);
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
        LOGE("unlocking %d, but currently locked buffer is %d",
                lockedBuffer, stack.inUse);
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
    // head is only written in this function, which is single-thread.
    int32_t head = stack.head;

    // Preventively lock the current buffer before updating queued.
    android_atomic_write(head, &stack.inUse);

    // Decrement the number of queued buffers 
    int32_t queued;
    do {
        queued = stack.queued;
        if (queued == 0) {
            return NOT_ENOUGH_DATA;
        }
    } while (android_atomic_cmpxchg(queued, queued-1, &stack.queued));
    
    // update the head pointer
    head = ((head+1 >= numBuffers) ? 0 : head+1);

    // lock the buffer before advancing head, which automatically unlocks
    // the buffer we preventively locked upon entering this function
    android_atomic_write(head, &stack.inUse);

    // advance head
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
        int surface, int num)
    : SharedBufferBase(sharedClient, surface, num), tail(0)
{
}

ssize_t SharedBufferClient::dequeue()
{
    //LOGD("[%d] about to dequeue a buffer",
    //        mSharedStack->identity);
    DequeueCondition condition(this);
    status_t err = waitForCondition(condition);
    if (err != NO_ERROR)
        return ssize_t(err);


    SharedBufferStack& stack( *mSharedStack );
    // NOTE: 'stack.available' is part of the conditions, however
    // decrementing it, never changes any conditions, so we don't need
    // to do this as part of an update.
    if (android_atomic_dec(&stack.available) == 0) {
        LOGW("dequeue probably called from multiple threads!");
    }

    int dequeued = tail;
    tail = ((tail+1 >= mNumBuffers) ? 0 : tail+1);
    LOGD_IF(DEBUG_ATOMICS, "dequeued=%d, tail=%d, %s",
            dequeued, tail, dump("").string());
    return dequeued;
}

status_t SharedBufferClient::undoDequeue(int buf)
{
    UndoDequeueUpdate update(this);
    status_t err = updateCondition( update );
    return err;
}

status_t SharedBufferClient::lock(int buf)
{
    LockCondition condition(this, buf);
    status_t err = waitForCondition(condition);    
    return err;
}

status_t SharedBufferClient::queue(int buf)
{
    QueueUpdate update(this);
    status_t err = updateCondition( update );
    LOGD_IF(DEBUG_ATOMICS, "queued=%d, %s", buf, dump("").string());
    return err;
}

bool SharedBufferClient::needNewBuffer(int buffer) const
{
    SharedBufferStack& stack( *mSharedStack );
    const uint32_t mask = 1<<buffer;
    return (android_atomic_and(~mask, &stack.reallocMask) & mask) != 0;
}

status_t SharedBufferClient::setDirtyRegion(int buffer, const Region& reg)
{
    SharedBufferStack& stack( *mSharedStack );
    return stack.setDirtyRegion(buffer, reg);
}

// ----------------------------------------------------------------------------

SharedBufferServer::SharedBufferServer(SharedClient* sharedClient,
        int surface, int num, int32_t identity)
    : SharedBufferBase(sharedClient, surface, num)
{
    mSharedStack->init(identity);
    mSharedStack->head = num-1;
    mSharedStack->available = num;
    mSharedStack->queued = 0;
    mSharedStack->reallocMask = 0;
    memset(mSharedStack->dirtyRegion, 0, sizeof(mSharedStack->dirtyRegion));
}

ssize_t SharedBufferServer::retireAndLock()
{
    RetireUpdate update(this, mNumBuffers);
    ssize_t buf = updateCondition( update );
    LOGD_IF(DEBUG_ATOMICS, "retire=%d, %s", int(buf), dump("").string());
    return buf;
}

status_t SharedBufferServer::unlock(int buffer)
{
    UnlockUpdate update(this, buffer);
    status_t err = updateCondition( update );
    return err;
}

void SharedBufferServer::setStatus(status_t status)
{
    StatusUpdate update(this, status);
    updateCondition( update );
}

status_t SharedBufferServer::reallocate()
{
    SharedBufferStack& stack( *mSharedStack );
    uint32_t mask = (1<<mNumBuffers)-1;
    android_atomic_or(mask, &stack.reallocMask); 
    return NO_ERROR;
}

status_t SharedBufferServer::assertReallocate(int buffer)
{
    ReallocateCondition condition(this, buffer);
    status_t err = waitForCondition(condition);
    return err;
}

Region SharedBufferServer::getDirtyRegion(int buffer) const
{
    SharedBufferStack& stack( *mSharedStack );
    return stack.getDirtyRegion(buffer);
}

// ---------------------------------------------------------------------------
}; // namespace android
