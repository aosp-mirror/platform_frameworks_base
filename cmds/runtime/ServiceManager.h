//
// Copyright 2005 The Android Open Source Project
//
#ifndef ANDROID_SERVICE_MANAGER_H
#define ANDROID_SERVICE_MANAGER_H

#include <binder/IServiceManager.h>
#include <utils/KeyedVector.h>
#include <utils/threads.h>

namespace android {

// ----------------------------------------------------------------------

class BServiceManager : public BnServiceManager
{
public:
                                BServiceManager();
    
    virtual sp<IBinder>         getService( const String16& name) const;
    virtual sp<IBinder>         checkService( const String16& name) const;
    virtual status_t            addService( const String16& name,
                                            const sp<IBinder>& service);
    virtual Vector<String16>    listServices();

    
private:
    mutable Mutex               mLock;
    mutable Condition           mChanged;
    sp<IPermissionController>   mPermissionController;
    KeyedVector<String16, sp<IBinder> > mServices;
};

// ----------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_SERVICE_MANAGER_H
