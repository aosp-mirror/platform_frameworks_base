//
// Copyright 2011 The Android Open Source Project
//

#ifndef MOCKFILEFINDER_H
#define MOCKFILEFINDER_H

#include <utils/Vector.h>
#include <utils/KeyedVector.h>
#include <utils/String8.h>

#include "DirectoryWalker.h"

using namespace android;

class MockFileFinder : public FileFinder {
public:
    MockFileFinder (KeyedVector<String8, KeyedVector<String8,time_t> >& files)
        : mFiles(files)
    {
        // Nothing left to do
    };

    /**
     * findFiles implementation for the abstraction.
     * PRECONDITIONS:
     *  No checking is done, so there MUST be an entry in mFiles with
     *  path matching basePath.
     *
     * POSTCONDITIONS:
     *  fileStore is filled with a copy of the data in mFiles corresponding
     *  to the basePath.
     */

    virtual bool findFiles(String8 basePath, Vector<String8>& extensions,
                           KeyedVector<String8,time_t>& fileStore,
                           DirectoryWalker* dw)
    {
        const KeyedVector<String8,time_t>* payload(&mFiles.valueFor(basePath));
        // Since KeyedVector doesn't implement swap
        // (who doesn't use swap??) we loop and add one at a time.
        for (size_t i = 0; i < payload->size(); ++i) {
            fileStore.add(payload->keyAt(i),payload->valueAt(i));
        }
        return true;
    }

private:
    // Virtual mapping between "directories" and the "files" contained
    // in them
    KeyedVector<String8, KeyedVector<String8,time_t> > mFiles;
};


#endif // MOCKFILEFINDER_H