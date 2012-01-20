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

#include <binder/Binder.h>

#include <utils/Atomic.h>
#include <binder/BpBinder.h>
#include <binder/IInterface.h>
#include <binder/Parcel.h>

#include <stdio.h>

namespace android {

// ---------------------------------------------------------------------------

IBinder::IBinder()
    : RefBase()
{
}

IBinder::~IBinder()
{
}

// ---------------------------------------------------------------------------

sp<IInterface>  IBinder::queryLocalInterface(const String16& descriptor)
{
    return NULL;
}

BBinder* IBinder::localBinder()
{
    return NULL;
}

BpBinder* IBinder::remoteBinder()
{
    return NULL;
}

bool IBinder::checkSubclass(const void* /*subclassID*/) const
{
    return false;
}

// ---------------------------------------------------------------------------

class BBinder::Extras
{
public:
    Mutex mLock;
    BpBinder::ObjectManager mObjects;
};

// ---------------------------------------------------------------------------

BBinder::BBinder()
    : mExtras(NULL)
{
}

bool BBinder::isBinderAlive() const
{
    return true;
}

status_t BBinder::pingBinder()
{
    return NO_ERROR;
}

const String16& BBinder::getInterfaceDescriptor() const
{
    // This is a local static rather than a global static,
    // to avoid static initializer ordering issues.
    static String16 sEmptyDescriptor;
    ALOGW("reached BBinder::getInterfaceDescriptor (this=%p)", this);
    return sEmptyDescriptor;
}

status_t BBinder::transact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    data.setDataPosition(0);

    status_t err = NO_ERROR;
    switch (code) {
        case PING_TRANSACTION:
            reply->writeInt32(pingBinder());
            break;
        default:
            err = onTransact(code, data, reply, flags);
            break;
    }

    if (reply != NULL) {
        reply->setDataPosition(0);
    }

    return err;
}

status_t BBinder::linkToDeath(
    const sp<DeathRecipient>& recipient, void* cookie, uint32_t flags)
{
    return INVALID_OPERATION;
}

status_t BBinder::unlinkToDeath(
    const wp<DeathRecipient>& recipient, void* cookie, uint32_t flags,
    wp<DeathRecipient>* outRecipient)
{
    return INVALID_OPERATION;
}

status_t BBinder::dump(int fd, const Vector<String16>& args)
{
    return NO_ERROR;
}

void BBinder::attachObject(
    const void* objectID, void* object, void* cleanupCookie,
    object_cleanup_func func)
{
    Extras* e = mExtras;

    if (!e) {
        e = new Extras;
        if (android_atomic_cmpxchg(0, reinterpret_cast<int32_t>(e),
                reinterpret_cast<volatile int32_t*>(&mExtras)) != 0) {
            delete e;
            e = mExtras;
        }
        if (e == 0) return; // out of memory
    }

    AutoMutex _l(e->mLock);
    e->mObjects.attach(objectID, object, cleanupCookie, func);
}

void* BBinder::findObject(const void* objectID) const
{
    Extras* e = mExtras;
    if (!e) return NULL;

    AutoMutex _l(e->mLock);
    return e->mObjects.find(objectID);
}

void BBinder::detachObject(const void* objectID)
{
    Extras* e = mExtras;
    if (!e) return;

    AutoMutex _l(e->mLock);
    e->mObjects.detach(objectID);
}

BBinder* BBinder::localBinder()
{
    return this;
}

BBinder::~BBinder()
{
    if (mExtras) delete mExtras;
}


status_t BBinder::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch (code) {
        case INTERFACE_TRANSACTION:
            reply->writeString16(getInterfaceDescriptor());
            return NO_ERROR;

        case DUMP_TRANSACTION: {
            int fd = data.readFileDescriptor();
            int argc = data.readInt32();
            Vector<String16> args;
            for (int i = 0; i < argc && data.dataAvail() > 0; i++) {
               args.add(data.readString16());
            }
            return dump(fd, args);
        }
        default:
            return UNKNOWN_TRANSACTION;
    }
}

// ---------------------------------------------------------------------------

enum {
    // This is used to transfer ownership of the remote binder from
    // the BpRefBase object holding it (when it is constructed), to the
    // owner of the BpRefBase object when it first acquires that BpRefBase.
    kRemoteAcquired = 0x00000001
};

BpRefBase::BpRefBase(const sp<IBinder>& o)
    : mRemote(o.get()), mRefs(NULL), mState(0)
{
    extendObjectLifetime(OBJECT_LIFETIME_WEAK);

    if (mRemote) {
        mRemote->incStrong(this);           // Removed on first IncStrong().
        mRefs = mRemote->createWeak(this);  // Held for our entire lifetime.
    }
}

BpRefBase::~BpRefBase()
{
    if (mRemote) {
        if (!(mState&kRemoteAcquired)) {
            mRemote->decStrong(this);
        }
        mRefs->decWeak(this);
    }
}

void BpRefBase::onFirstRef()
{
    android_atomic_or(kRemoteAcquired, &mState);
}

void BpRefBase::onLastStrongRef(const void* id)
{
    if (mRemote) {
        mRemote->decStrong(this);
    }
}

bool BpRefBase::onIncStrongAttempted(uint32_t flags, const void* id)
{
    return mRemote ? mRefs->attemptIncStrong(this) : false;
}

// ---------------------------------------------------------------------------

}; // namespace android
