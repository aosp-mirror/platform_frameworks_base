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


namespace android {

// ---------------------------------------------------------------------------

template<typename NODE_PTR_TYPE>
class DoublyLinkedList
{
protected:
    typedef NODE_PTR_TYPE NODE_PTR;
    
    NODE_PTR  mFirst;
    NODE_PTR  mLast;

public:
    class Node {
        friend class DoublyLinkedList;
        mutable NODE_PTR next;
        mutable NODE_PTR prev;
    public:
        typedef NODE_PTR PTR;
        inline NODE_PTR getNext() const { return next; }
        inline NODE_PTR getPrev() const { return prev; }
        void detach() { 
            prev = 0;
            next = 0;
        }
    };

    DoublyLinkedList() : mFirst(0), mLast(0) { }
    ~DoublyLinkedList() { }
    
    bool        isEmpty() const { return mFirst == 0; }
    const NODE_PTR& head() const { return mFirst; }
    const NODE_PTR& tail() const { return mLast; }
    const NODE_PTR& head() { return mFirst; }
    const NODE_PTR& tail() { return mLast; }

    void insertAfter(const NODE_PTR& node, const NODE_PTR& newNode) {
        newNode->prev = node;
        newNode->next = node->next;
        if (node->next == 0) mLast = newNode;
        else                 node->next->prev = newNode;
        node->next = newNode;
    }

    void insertBefore(const NODE_PTR& node, const NODE_PTR& newNode) {
        newNode->prev = node->prev;
        newNode->next = node;
        if (node->prev == 0)   mFirst = newNode;
        else                   node->prev->next = newNode;
        node->prev = newNode;
    }

    void insertHead(const NODE_PTR& newNode) {
        if (mFirst == 0) {
            mFirst = mLast = newNode;
            newNode->prev = newNode->next = 0;
        } else {
            newNode->prev = 0;
            newNode->next = mFirst;
            mFirst->prev = newNode;
            mFirst = newNode;
        }
    }

    void insertTail(const NODE_PTR& newNode) {
        if (mLast == 0) {
            insertHead(newNode);
        } else {
            newNode->prev = mLast;
            newNode->next = 0;
            mLast->next = newNode;
            mLast = newNode;
        }
    }

    NODE_PTR remove(const NODE_PTR& node) {
        if (node->prev == 0)    mFirst = node->next;
        else                    node->prev->next = node->next;
        if (node->next == 0)    mLast = node->prev;
        else                    node->next->prev = node->prev;
        return node;
    }
};

// ---------------------------------------------------------------------------

template<typename NODE_PTR_TYPE>
class SortedList : protected DoublyLinkedList< NODE_PTR_TYPE > 
{
    typedef DoublyLinkedList< NODE_PTR_TYPE > forward;
public:
    typedef NODE_PTR_TYPE NODE_PTR;
    inline bool isEmpty() const { return forward::isEmpty(); }
    inline const NODE_PTR& head() const { return forward::head(); }
    inline const NODE_PTR& tail() const { return forward::tail(); }
    inline const NODE_PTR& head() { return forward::head(); }
    inline const NODE_PTR& tail() { return forward::tail(); }
    inline NODE_PTR remove(const NODE_PTR& node) { return forward::remove(node); }
    void insert(const NODE_PTR& node) {
        NODE_PTR l(head());
        while (l != 0) {
            if (*node < *l) {
                insertBefore(l, node);
                return;
            }
            l = l->getNext();
        }
        insertTail(node);
    }
};

// ============================================================================

class MessageBase : 
    public LightRefBase<MessageBase>, 
    public DoublyLinkedList< sp<MessageBase> >::Node
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

/*
 * MessageList is a sorted list of sp<MessageBase>
 */
    
typedef SortedList< MessageBase::Node::PTR > MessageList; 

// ---------------------------------------------------------------------------

class MessageQueue
{
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

    MessageList::NODE_PTR waitMessage(nsecs_t timeout = -1);
    
    status_t postMessage(const MessageList::NODE_PTR& message, 
            nsecs_t reltime=0, uint32_t flags = 0);
        
    status_t invalidate();
    
    void dump(const MessageList::NODE_PTR& message);

private:
    status_t queueMessage(const MessageList::NODE_PTR& message,
            nsecs_t reltime, uint32_t flags);
    void dumpLocked(const MessageList::NODE_PTR& message);
    
    Mutex           mLock;
    Condition       mCondition;
    MessageList     mMessages;
    bool            mInvalidate;
    MessageList::NODE_PTR mInvalidateMessage;
};

// ---------------------------------------------------------------------------

}; // namespace android

#endif /* ANDROID_MESSAGE_QUEUE_H */
