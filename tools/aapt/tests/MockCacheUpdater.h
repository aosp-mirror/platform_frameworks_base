//
// Copyright 2011 The Android Open Source Project
//
#ifndef MOCKCACHEUPDATER_H
#define MOCKCACHEUPDATER_H

#include <utils/String8.h>
#include "CacheUpdater.h"

using namespace android;

class MockCacheUpdater : public CacheUpdater {
public:

    MockCacheUpdater()
        : deleteCount(0), processCount(0) { };

    // Make sure all the directories along this path exist
    virtual void ensureDirectoriesExist(String8 path)
    {
        // Nothing to do
    };

    // Delete a file
    virtual void deleteFile(String8 path) {
        deleteCount++;
    };

    // Process an image from source out to dest
    virtual void processImage(String8 source, String8 dest) {
        processCount++;
    };

    // DATA MEMBERS
    int deleteCount;
    int processCount;
private:
};

#endif // MOCKCACHEUPDATER_H