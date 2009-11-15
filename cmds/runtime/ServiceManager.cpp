//
// Copyright 2005 The Android Open Source Project
//

#define LOG_TAG "ServiceManager"

#include "ServiceManager.h"
#include "SignalHandler.h"

#include <utils/Debug.h>
#include <utils/Log.h>
#include <binder/Parcel.h>
#include <utils/String8.h>
#include <binder/ProcessState.h>

#include <private/utils/Static.h>

#include <ctype.h>
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/stat.h>

namespace android {

BServiceManager::BServiceManager()
{
}

sp<IBinder> BServiceManager::getService(const String16& name) const
{
    AutoMutex _l(mLock);
    ssize_t i = mServices.indexOfKey(name);
    LOGV("ServiceManager: getService(%s) -> %d\n", String8(name).string(), i);
    if (i >= 0) return mServices.valueAt(i);
    return NULL;
}

sp<IBinder> BServiceManager::checkService(const String16& name) const
{
    AutoMutex _l(mLock);
    ssize_t i = mServices.indexOfKey(name);
    LOGV("ServiceManager: getService(%s) -> %d\n", String8(name).string(), i);
    if (i >= 0) return mServices.valueAt(i);
    return NULL;
}

status_t BServiceManager::addService(const String16& name, const sp<IBinder>& service)
{
    AutoMutex _l(mLock);
    LOGI("ServiceManager: addService(%s, %p)\n", String8(name).string(), service.get());
    const ssize_t res = mServices.add(name, service);
    if (res >= NO_ERROR) {
        mChanged.broadcast();
        return NO_ERROR;
    }
    return res;
}

Vector<String16> BServiceManager::listServices()
{
    Vector<String16> res;

    AutoMutex _l(mLock);
    const size_t N = mServices.size();
    for (size_t i=0; i<N; i++) {
        res.add(mServices.keyAt(i));
    }

    return res;
}

}; // namespace android
