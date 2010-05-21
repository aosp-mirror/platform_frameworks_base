/*
 * Copyright (C) 2009 The Android Open Source Project
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

#ifndef ANDROID_MESSAGE_QUEUE_H
#define ANDROID_MESSAGE_QUEUE_H

#include <stdint.h>
#include <errno.h>
#include <sys/types.h>

#include <utils/threads.h>
#include <utils/Timers.h>
#include <utils/List.h>

#include "Barrier.h"

namespace android {

// ---------------------------------------------------------------------------

class MessageBase;

class MessageList 
{
    List< sp<MessageBase> > mList;
    typedef List< sp<MessageBase> > LIST;
public:
    inline LIST::iterator begin()                { return mList.begin(); }
    inline LIST::const_iterator begin() const    { return mList.begin(); }
    inline LIST::iterator end()                  { return mList.end(); }
    inline LIST::const_iterator end() const      { return mList.end(); }
    inline bool isEmpty() const { return mList.empty(); }
    void insert(const sp<MessageBase>& node);
    void remove(LIST::iterator pos);
};

// ============================================================================

class MessageBase : 
    public LightRefBase<MessageBase>
{
public:
    nsecs_t     when;
    uint32_t    what;
    int32_t     arg0;    

    MessageBase() : when(0), what(0), arg0(0) { }
    MessageBase(uint32_t what, int32_t arg0=0)
        : when(0), what(what), arg0(arg0) { }
    
    // return true if message has a handler
    virtual bool handler() { return false; }

    // waits for the handler to be processed
    void wait() const { barrier.wait(); }
    
    // releases all waiters. this is done automatically if
    // handler returns true
    void notify() const { barrier.open(); }

protected:
    virtual ~MessageBase() { }

private:
    mutable Barrier barrier;
    friend class LightRefBase<MessageBase>;
};

inline bool operator < (const MessageBase& lhs, const MessageBase& rhs) {
    return lhs.when < rhs.when;
}

// ---------------------------------------------------------------------------

class MessageQueue
{
    typedef List< sp<MessageBase> > LIST;
public:

    MessageQueue();
    ~MessageQueue();

    // pre-defined messages
    enum {
        INVALIDATE = '_upd'
    };

    sp<MessageBase> waitMessage(nsecs_t timeout = -1);
    
    status_t postMessage(const sp<MessageBase>& message,
            nsecs_t reltime=0, uint32_t flags = 0);

    status_t invalidate();
    
    void dump(const sp<MessageBase>& message);

private:
    status_t queueMessage(const sp<MessageBase>& message,
            nsecs_t reltime, uint32_t flags);
    void dumpLocked(const sp<MessageBase>& message);
    
    Mutex           mLock;
    Condition       mCondition;
    MessageList     mMessages;
    bool            mInvalidate;
    sp<MessageBase> mInvalidateMessage;
};

// ---------------------------------------------------------------------------

}; // namespace android

#endif /* ANDROID_MESSAGE_QUEUE_H */
