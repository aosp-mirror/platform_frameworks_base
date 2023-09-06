//
// Copyright 2011 The Android Open Source Project
//
// Defines an abstraction for opening a directory on the filesystem and
// iterating through it.

#ifndef DIRECTORYWALKER_H
#define DIRECTORYWALKER_H

#include <androidfw/PathUtils.h>
#include <dirent.h>
#include <sys/types.h>
#include <sys/param.h>
#include <sys/stat.h>
#include <unistd.h>
#include <utils/String8.h>

#include <stdio.h>

using namespace android;

// Directory Walker
// This is an abstraction for walking through a directory and getting files
// and descriptions.

class DirectoryWalker {
public:
    virtual ~DirectoryWalker() {};
    virtual bool openDir(String8 path) = 0;
    virtual bool openDir(const char* path) = 0;
    // Advance to next directory entry
    virtual struct dirent* nextEntry() = 0;
    // Get the stats for the current entry
    virtual struct stat*   entryStats() = 0;
    // Clean Up
    virtual void closeDir() = 0;
    // This class is able to replicate itself on the heap
    virtual DirectoryWalker* clone() = 0;

    // DATA MEMBERS
    // Current directory entry
    struct dirent mEntry;
    // Stats for that directory entry
    struct stat mStats;
    // Base path
    String8 mBasePath;
};

// System Directory Walker
// This is an implementation of the above abstraction that calls
// real system calls and is fully functional.
// functions are inlined since they're very short and simple

class SystemDirectoryWalker : public DirectoryWalker {

    // Default constructor, copy constructor, and destructor are fine
public:
    virtual bool openDir(String8 path) {
        mBasePath = path;
        dir = NULL;
        dir = opendir(mBasePath.c_str() );

        if (dir == NULL)
            return false;

        return true;
    };
    virtual bool openDir(const char* path) {
        String8 p(path);
        openDir(p);
        return true;
    };
    // Advance to next directory entry
    virtual struct dirent* nextEntry() {
        struct dirent* entryPtr = readdir(dir);
        if (entryPtr == NULL)
            return NULL;

        mEntry = *entryPtr;
        // Get stats
        String8 fullPath = appendPathCopy(mBasePath, mEntry.d_name);
        stat(fullPath.c_str(),&mStats);
        return &mEntry;
    };
    // Get the stats for the current entry
    virtual struct stat*   entryStats() {
        return &mStats;
    };
    virtual void closeDir() {
        closedir(dir);
    };
    virtual DirectoryWalker* clone() {
        return new SystemDirectoryWalker(*this);
    };
private:
    DIR* dir;
};

#endif // DIRECTORYWALKER_H
