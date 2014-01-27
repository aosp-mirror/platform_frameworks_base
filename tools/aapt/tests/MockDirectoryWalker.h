//
// Copyright 2011 The Android Open Source Project
//
#ifndef MOCKDIRECTORYWALKER_H
#define MOCKDIRECTORYWALKER_H

#include <utils/Vector.h>
#include <utils/String8.h>
#include <utility>
#include "DirectoryWalker.h"

using namespace android;
using std::pair;

// String8 Directory Walker
// This is an implementation of the Directory Walker abstraction that is built
// for testing.
// Instead of system calls it queries a private data structure for the directory
// entries. It takes a path and a map of filenames and their modification times.
// functions are inlined since they are short and simple

class StringDirectoryWalker : public DirectoryWalker {
public:
    StringDirectoryWalker(String8& path, Vector< pair<String8,time_t> >& data)
        :  mPos(0), mBasePath(path), mData(data) {
        //fprintf(stdout,"StringDW built to mimic %s with %d files\n",
        //       mBasePath.string());
    };
    // Default copy constructor, and destructor are fine

    virtual bool openDir(String8 path) {
        // If the user is trying to query the "directory" that this
        // walker was initialized with, then return success. Else fail.
        return path == mBasePath;
    };
    virtual bool openDir(const char* path) {
        String8 p(path);
        openDir(p);
        return true;
    };
    // Advance to next entry in the Vector
    virtual struct dirent* nextEntry() {
        // Advance position and check to see if we're done
        if (mPos >= mData.size())
            return NULL;

        // Place data in the entry descriptor. This class only returns files.
        mEntry.d_type = DT_REG;
        mEntry.d_ino = mPos;
        // Copy chars from the string name to the entry name
        size_t i = 0;
        for (i; i < mData[mPos].first.size(); ++i)
            mEntry.d_name[i] = mData[mPos].first[i];
        mEntry.d_name[i] = '\0';

        // Place data in stats
        mStats.st_ino = mPos;
        mStats.st_mtime = mData[mPos].second;

        // Get ready to move to the next entry
        mPos++;

        return &mEntry;
    };
    // Get the stats for the current entry
    virtual struct stat*   entryStats() {
        return &mStats;
    };
    // Nothing to do in clean up
    virtual void closeDir() {
        // Nothing to do
    };
    virtual DirectoryWalker* clone() {
        return new StringDirectoryWalker(*this);
    };
private:
    // Current position in the Vector
    size_t mPos;
    // Base path
    String8 mBasePath;
    // Data to simulate a directory full of files.
    Vector< pair<String8,time_t> > mData;
};

#endif // MOCKDIRECTORYWALKER_H