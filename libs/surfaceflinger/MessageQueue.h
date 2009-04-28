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


namespace android {

// ---------------------------------------------------------------------------

class MessageBase;

class MessageList 
{
    List< sp<MessageBase> > mList;
    typedef List< sp<MessageBase> > LIST;
public:
    typedef sp<MessageBase> value_type;
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
    
protected:
    virtual ~MessageBase() { }

private:
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

    // this is a work-around the multichar constant warning. A macro would
    // work too, but would pollute the namespace.
    template <int a, int b, int c, int d>
    struct WHAT {
        static const uint32_t Value = 
            (uint32_t(a&0xff)<<24)|(uint32_t(b&0xff)<<16)|
            (uint32_t(c&0xff)<<8)|uint32_t(d&0xff);
    };
    
    MessageQueue();
    ~MessageQueue();

    // pre-defined messages
    enum {
        INVALIDATE = WHAT<'_','p','d','t'>::Value
    };

    MessageList::value_type waitMessage(nsecs_t timeout = -1);
    
    status_t postMessage(const MessageList::value_type& message, 
            nsecs_t reltime=0, uint32_t flags = 0);
        
    status_t invalidate();
    
    void dump(const MessageList::value_type& message);

private:
    status_t queueMessage(const MessageList::value_type& message,
            nsecs_t reltime, uint32_t flags);
    void dumpLocked(const MessageList::value_type& message);
    
    Mutex           mLock;
    Condition       mCondition;
    MessageList     mMessages;
    bool            mInvalidate;
    MessageList::value_type mInvalidateMessage;
};

// ---------------------------------------------------------------------------

}; // namespace android

#endif /* ANDROID_MESSAGE_QUEUE_H */
