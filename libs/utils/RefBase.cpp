/*
 * Copyright (C) 2005 The Android Open Source Project
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

#define LOG_TAG "RefBase"

#include <utils/RefBase.h>

#include <utils/Atomic.h>
#include <utils/CallStack.h>
#include <utils/Log.h>
#include <utils/threads.h>
#include <utils/TextOutput.h>

#include <stdlib.h>
#include <stdio.h>
#include <typeinfo>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

// compile with refcounting debugging enabled
#define DEBUG_REFS                      0
#define DEBUG_REFS_FATAL_SANITY_CHECKS  0
#define DEBUG_REFS_ENABLED_BY_DEFAULT   1
#define DEBUG_REFS_CALLSTACK_ENABLED    1

// log all reference counting operations
#define PRINT_REFS                      0

// ---------------------------------------------------------------------------

namespace android {

#define INITIAL_STRONG_VALUE (1<<28)

// ---------------------------------------------------------------------------

class RefBase::weakref_impl : public RefBase::weakref_type
{
public:
    volatile int32_t    mStrong;
    volatile int32_t    mWeak;
    RefBase* const      mBase;
    volatile int32_t    mFlags;

#if !DEBUG_REFS

    weakref_impl(RefBase* base)
        : mStrong(INITIAL_STRONG_VALUE)
        , mWeak(0)
        , mBase(base)
        , mFlags(0)
    {
    }

    void addStrongRef(const void* /*id*/) { }
    void removeStrongRef(const void* /*id*/) { }
    void renameStrongRefId(const void* /*old_id*/, const void* /*new_id*/) { }
    void addWeakRef(const void* /*id*/) { }
    void removeWeakRef(const void* /*id*/) { }
    void renameWeakRefId(const void* /*old_id*/, const void* /*new_id*/) { }
    void printRefs() const { }
    void trackMe(bool, bool) { }

#else

    weakref_impl(RefBase* base)
        : mStrong(INITIAL_STRONG_VALUE)
        , mWeak(0)
        , mBase(base)
        , mFlags(0)
        , mStrongRefs(NULL)
        , mWeakRefs(NULL)
        , mTrackEnabled(!!DEBUG_REFS_ENABLED_BY_DEFAULT)
        , mRetain(false)
    {
    }
    
    ~weakref_impl()
    {
        bool dumpStack = false;
        if (!mRetain && mStrongRefs != NULL) {
            dumpStack = true;
#if DEBUG_REFS_FATAL_SANITY_CHECKS
            LOG_ALWAYS_FATAL("Strong references remain!");
#else
            ALOGE("Strong references remain:");
#endif
            ref_entry* refs = mStrongRefs;
            while (refs) {
                char inc = refs->ref >= 0 ? '+' : '-';
                ALOGD("\t%c ID %p (ref %d):", inc, refs->id, refs->ref);
#if DEBUG_REFS_CALLSTACK_ENABLED
                refs->stack.dump();
#endif
                refs = refs->next;
            }
        }

        if (!mRetain && mWeakRefs != NULL) {
            dumpStack = true;
#if DEBUG_REFS_FATAL_SANITY_CHECKS
            LOG_ALWAYS_FATAL("Weak references remain:");
#else
            ALOGE("Weak references remain!");
#endif
            ref_entry* refs = mWeakRefs;
            while (refs) {
                char inc = refs->ref >= 0 ? '+' : '-';
                ALOGD("\t%c ID %p (ref %d):", inc, refs->id, refs->ref);
#if DEBUG_REFS_CALLSTACK_ENABLED
                refs->stack.dump();
#endif
                refs = refs->next;
            }
        }
        if (dumpStack) {
            ALOGE("above errors at:");
            CallStack stack;
            stack.update();
            stack.dump();
        }
    }

    void addStrongRef(const void* id) {
        //ALOGD_IF(mTrackEnabled,
        //        "addStrongRef: RefBase=%p, id=%p", mBase, id);
        addRef(&mStrongRefs, id, mStrong);
    }

    void removeStrongRef(const void* id) {
        //ALOGD_IF(mTrackEnabled,
        //        "removeStrongRef: RefBase=%p, id=%p", mBase, id);
        if (!mRetain) {
            removeRef(&mStrongRefs, id);
        } else {
            addRef(&mStrongRefs, id, -mStrong);
        }
    }

    void renameStrongRefId(const void* old_id, const void* new_id) {
        //ALOGD_IF(mTrackEnabled,
        //        "renameStrongRefId: RefBase=%p, oid=%p, nid=%p",
        //        mBase, old_id, new_id);
        renameRefsId(mStrongRefs, old_id, new_id);
    }

    void addWeakRef(const void* id) {
        addRef(&mWeakRefs, id, mWeak);
    }

    void removeWeakRef(const void* id) {
        if (!mRetain) {
            removeRef(&mWeakRefs, id);
        } else {
            addRef(&mWeakRefs, id, -mWeak);
        }
    }

    void renameWeakRefId(const void* old_id, const void* new_id) {
        renameRefsId(mWeakRefs, old_id, new_id);
    }

    void trackMe(bool track, bool retain)
    { 
        mTrackEnabled = track;
        mRetain = retain;
    }

    void printRefs() const
    {
        String8 text;

        {
            Mutex::Autolock _l(mMutex);
            char buf[128];
            sprintf(buf, "Strong references on RefBase %p (weakref_type %p):\n", mBase, this);
            text.append(buf);
            printRefsLocked(&text, mStrongRefs);
            sprintf(buf, "Weak references on RefBase %p (weakref_type %p):\n", mBase, this);
            text.append(buf);
            printRefsLocked(&text, mWeakRefs);
        }

        {
            char name[100];
            snprintf(name, 100, "/data/%p.stack", this);
            int rc = open(name, O_RDWR | O_CREAT | O_APPEND);
            if (rc >= 0) {
                write(rc, text.string(), text.length());
                close(rc);
                ALOGD("STACK TRACE for %p saved in %s", this, name);
            }
            else ALOGE("FAILED TO PRINT STACK TRACE for %p in %s: %s", this,
                      name, strerror(errno));
        }
    }

private:
    struct ref_entry
    {
        ref_entry* next;
        const void* id;
#if DEBUG_REFS_CALLSTACK_ENABLED
        CallStack stack;
#endif
        int32_t ref;
    };

    void addRef(ref_entry** refs, const void* id, int32_t mRef)
    {
        if (mTrackEnabled) {
            AutoMutex _l(mMutex);

            ref_entry* ref = new ref_entry;
            // Reference count at the time of the snapshot, but before the
            // update.  Positive value means we increment, negative--we
            // decrement the reference count.
            ref->ref = mRef;
            ref->id = id;
#if DEBUG_REFS_CALLSTACK_ENABLED
            ref->stack.update(2);
#endif
            ref->next = *refs;
            *refs = ref;
        }
    }

    void removeRef(ref_entry** refs, const void* id)
    {
        if (mTrackEnabled) {
            AutoMutex _l(mMutex);
            
            ref_entry* const head = *refs;
            ref_entry* ref = head;
            while (ref != NULL) {
                if (ref->id == id) {
                    *refs = ref->next;
                    delete ref;
                    return;
                }
                refs = &ref->next;
                ref = *refs;
            }

#if DEBUG_REFS_FATAL_SANITY_CHECKS
            LOG_ALWAYS_FATAL("RefBase: removing id %p on RefBase %p"
                    "(weakref_type %p) that doesn't exist!",
                    id, mBase, this);
#endif

            ALOGE("RefBase: removing id %p on RefBase %p"
                    "(weakref_type %p) that doesn't exist!",
                    id, mBase, this);

            ref = head;
            while (ref) {
                char inc = ref->ref >= 0 ? '+' : '-';
                ALOGD("\t%c ID %p (ref %d):", inc, ref->id, ref->ref);
                ref = ref->next;
            }

            CallStack stack;
            stack.update();
            stack.dump();
        }
    }

    void renameRefsId(ref_entry* r, const void* old_id, const void* new_id)
    {
        if (mTrackEnabled) {
            AutoMutex _l(mMutex);
            ref_entry* ref = r;
            while (ref != NULL) {
                if (ref->id == old_id) {
                    ref->id = new_id;
                }
                ref = ref->next;
            }
        }
    }

    void printRefsLocked(String8* out, const ref_entry* refs) const
    {
        char buf[128];
        while (refs) {
            char inc = refs->ref >= 0 ? '+' : '-';
            sprintf(buf, "\t%c ID %p (ref %d):\n", 
                    inc, refs->id, refs->ref);
            out->append(buf);
#if DEBUG_REFS_CALLSTACK_ENABLED
            out->append(refs->stack.toString("\t\t"));
#else
            out->append("\t\t(call stacks disabled)");
#endif
            refs = refs->next;
        }
    }

    mutable Mutex mMutex;
    ref_entry* mStrongRefs;
    ref_entry* mWeakRefs;

    bool mTrackEnabled;
    // Collect stack traces on addref and removeref, instead of deleting the stack references
    // on removeref that match the address ones.
    bool mRetain;

#endif
};

// ---------------------------------------------------------------------------

void RefBase::incStrong(const void* id) const
{
    weakref_impl* const refs = mRefs;
    refs->incWeak(id);
    
    refs->addStrongRef(id);
    const int32_t c = android_atomic_inc(&refs->mStrong);
    LOG_ASSERT(c > 0, "incStrong() called on %p after last strong ref", refs);
#if PRINT_REFS
    ALOGD("incStrong of %p from %p: cnt=%d\n", this, id, c);
#endif
    if (c != INITIAL_STRONG_VALUE)  {
        return;
    }

    android_atomic_add(-INITIAL_STRONG_VALUE, &refs->mStrong);
    refs->mBase->onFirstRef();
}

void RefBase::decStrong(const void* id) const
{
    weakref_impl* const refs = mRefs;
    refs->removeStrongRef(id);
    const int32_t c = android_atomic_dec(&refs->mStrong);
#if PRINT_REFS
    ALOGD("decStrong of %p from %p: cnt=%d\n", this, id, c);
#endif
    LOG_ASSERT(c >= 1, "decStrong() called on %p too many times", refs);
    if (c == 1) {
        refs->mBase->onLastStrongRef(id);
        if ((refs->mFlags&OBJECT_LIFETIME_MASK) == OBJECT_LIFETIME_STRONG) {
            delete this;
        }
    }
    refs->decWeak(id);
}

void RefBase::forceIncStrong(const void* id) const
{
    weakref_impl* const refs = mRefs;
    refs->incWeak(id);
    
    refs->addStrongRef(id);
    const int32_t c = android_atomic_inc(&refs->mStrong);
    LOG_ASSERT(c >= 0, "forceIncStrong called on %p after ref count underflow",
               refs);
#if PRINT_REFS
    ALOGD("forceIncStrong of %p from %p: cnt=%d\n", this, id, c);
#endif

    switch (c) {
    case INITIAL_STRONG_VALUE:
        android_atomic_add(-INITIAL_STRONG_VALUE, &refs->mStrong);
        // fall through...
    case 0:
        refs->mBase->onFirstRef();
    }
}

int32_t RefBase::getStrongCount() const
{
    return mRefs->mStrong;
}

RefBase* RefBase::weakref_type::refBase() const
{
    return static_cast<const weakref_impl*>(this)->mBase;
}

void RefBase::weakref_type::incWeak(const void* id)
{
    weakref_impl* const impl = static_cast<weakref_impl*>(this);
    impl->addWeakRef(id);
    const int32_t c = android_atomic_inc(&impl->mWeak);
    LOG_ASSERT(c >= 0, "incWeak called on %p after last weak ref", this);
}


void RefBase::weakref_type::decWeak(const void* id)
{
    weakref_impl* const impl = static_cast<weakref_impl*>(this);
    impl->removeWeakRef(id);
    const int32_t c = android_atomic_dec(&impl->mWeak);
    LOG_ASSERT(c >= 1, "decWeak called on %p too many times", this);
    if (c != 1) return;

    if ((impl->mFlags&OBJECT_LIFETIME_WEAK) == OBJECT_LIFETIME_STRONG) {
        // This is the regular lifetime case. The object is destroyed
        // when the last strong reference goes away. Since weakref_impl
        // outlive the object, it is not destroyed in the dtor, and
        // we'll have to do it here.
        if (impl->mStrong == INITIAL_STRONG_VALUE) {
            // Special case: we never had a strong reference, so we need to
            // destroy the object now.
            delete impl->mBase;
        } else {
            // ALOGV("Freeing refs %p of old RefBase %p\n", this, impl->mBase);
            delete impl;
        }
    } else {
        // less common case: lifetime is OBJECT_LIFETIME_{WEAK|FOREVER}
        impl->mBase->onLastWeakRef(id);
        if ((impl->mFlags&OBJECT_LIFETIME_MASK) == OBJECT_LIFETIME_WEAK) {
            // this is the OBJECT_LIFETIME_WEAK case. The last weak-reference
            // is gone, we can destroy the object.
            delete impl->mBase;
        }
    }
}

bool RefBase::weakref_type::attemptIncStrong(const void* id)
{
    incWeak(id);
    
    weakref_impl* const impl = static_cast<weakref_impl*>(this);
    
    int32_t curCount = impl->mStrong;
    LOG_ASSERT(curCount >= 0, "attemptIncStrong called on %p after underflow",
               this);
    while (curCount > 0 && curCount != INITIAL_STRONG_VALUE) {
        if (android_atomic_cmpxchg(curCount, curCount+1, &impl->mStrong) == 0) {
            break;
        }
        curCount = impl->mStrong;
    }
    
    if (curCount <= 0 || curCount == INITIAL_STRONG_VALUE) {
        bool allow;
        if (curCount == INITIAL_STRONG_VALUE) {
            // Attempting to acquire first strong reference...  this is allowed
            // if the object does NOT have a longer lifetime (meaning the
            // implementation doesn't need to see this), or if the implementation
            // allows it to happen.
            allow = (impl->mFlags&OBJECT_LIFETIME_WEAK) != OBJECT_LIFETIME_WEAK
                  || impl->mBase->onIncStrongAttempted(FIRST_INC_STRONG, id);
        } else {
            // Attempting to revive the object...  this is allowed
            // if the object DOES have a longer lifetime (so we can safely
            // call the object with only a weak ref) and the implementation
            // allows it to happen.
            allow = (impl->mFlags&OBJECT_LIFETIME_WEAK) == OBJECT_LIFETIME_WEAK
                  && impl->mBase->onIncStrongAttempted(FIRST_INC_STRONG, id);
        }
        if (!allow) {
            decWeak(id);
            return false;
        }
        curCount = android_atomic_inc(&impl->mStrong);

        // If the strong reference count has already been incremented by
        // someone else, the implementor of onIncStrongAttempted() is holding
        // an unneeded reference.  So call onLastStrongRef() here to remove it.
        // (No, this is not pretty.)  Note that we MUST NOT do this if we
        // are in fact acquiring the first reference.
        if (curCount > 0 && curCount < INITIAL_STRONG_VALUE) {
            impl->mBase->onLastStrongRef(id);
        }
    }
    
    impl->addStrongRef(id);

#if PRINT_REFS
    ALOGD("attemptIncStrong of %p from %p: cnt=%d\n", this, id, curCount);
#endif

    if (curCount == INITIAL_STRONG_VALUE) {
        android_atomic_add(-INITIAL_STRONG_VALUE, &impl->mStrong);
        impl->mBase->onFirstRef();
    }
    
    return true;
}

bool RefBase::weakref_type::attemptIncWeak(const void* id)
{
    weakref_impl* const impl = static_cast<weakref_impl*>(this);

    int32_t curCount = impl->mWeak;
    LOG_ASSERT(curCount >= 0, "attemptIncWeak called on %p after underflow",
               this);
    while (curCount > 0) {
        if (android_atomic_cmpxchg(curCount, curCount+1, &impl->mWeak) == 0) {
            break;
        }
        curCount = impl->mWeak;
    }

    if (curCount > 0) {
        impl->addWeakRef(id);
    }

    return curCount > 0;
}

int32_t RefBase::weakref_type::getWeakCount() const
{
    return static_cast<const weakref_impl*>(this)->mWeak;
}

void RefBase::weakref_type::printRefs() const
{
    static_cast<const weakref_impl*>(this)->printRefs();
}

void RefBase::weakref_type::trackMe(bool enable, bool retain)
{
    static_cast<weakref_impl*>(this)->trackMe(enable, retain);
}

RefBase::weakref_type* RefBase::createWeak(const void* id) const
{
    mRefs->incWeak(id);
    return mRefs;
}

RefBase::weakref_type* RefBase::getWeakRefs() const
{
    return mRefs;
}

RefBase::RefBase()
    : mRefs(new weakref_impl(this))
{
}

RefBase::~RefBase()
{
    if (mRefs->mStrong == INITIAL_STRONG_VALUE) {
        // we never acquired a strong (and/or weak) reference on this object.
        delete mRefs;
    } else {
        // life-time of this object is extended to WEAK or FOREVER, in
        // which case weakref_impl doesn't out-live the object and we
        // can free it now.
        if ((mRefs->mFlags & OBJECT_LIFETIME_MASK) != OBJECT_LIFETIME_STRONG) {
            // It's possible that the weak count is not 0 if the object
            // re-acquired a weak reference in its destructor
            if (mRefs->mWeak == 0) {
                delete mRefs;
            }
        }
    }
    // for debugging purposes, clear this.
    const_cast<weakref_impl*&>(mRefs) = NULL;
}

void RefBase::extendObjectLifetime(int32_t mode)
{
    android_atomic_or(mode, &mRefs->mFlags);
}

void RefBase::onFirstRef()
{
}

void RefBase::onLastStrongRef(const void* /*id*/)
{
}

bool RefBase::onIncStrongAttempted(uint32_t flags, const void* id)
{
    return (flags&FIRST_INC_STRONG) ? true : false;
}

void RefBase::onLastWeakRef(const void* /*id*/)
{
}

// ---------------------------------------------------------------------------

void RefBase::moveReferences(void* dst, void const* src, size_t n,
        const ReferenceConverterBase& caster)
{
#if DEBUG_REFS
    const size_t itemSize = caster.getReferenceTypeSize();
    for (size_t i=0 ; i<n ; i++) {
        void*       d = reinterpret_cast<void      *>(intptr_t(dst) + i*itemSize);
        void const* s = reinterpret_cast<void const*>(intptr_t(src) + i*itemSize);
        RefBase* ref(reinterpret_cast<RefBase*>(caster.getReferenceBase(d)));
        ref->mRefs->renameStrongRefId(s, d);
        ref->mRefs->renameWeakRefId(s, d);
    }
#endif
}

// ---------------------------------------------------------------------------

TextOutput& printStrongPointer(TextOutput& to, const void* val)
{
    to << "sp<>(" << val << ")";
    return to;
}

TextOutput& printWeakPointer(TextOutput& to, const void* val)
{
    to << "wp<>(" << val << ")";
    return to;
}


}; // namespace android
