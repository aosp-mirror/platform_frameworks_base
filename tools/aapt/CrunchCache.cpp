//
// Copyright 2011 The Android Open Source Project
//
// Implementation file for CrunchCache
// This file defines functions laid out and documented in
// CrunchCache.h

#include <utils/Vector.h>
#include <utils/String8.h>

#include "DirectoryWalker.h"
#include "FileFinder.h"
#include "CacheUpdater.h"
#include "CrunchCache.h"

using namespace android;

CrunchCache::CrunchCache(String8 sourcePath, String8 destPath, FileFinder* ff)
    : mSourcePath(sourcePath), mDestPath(destPath), mSourceFiles(0), mDestFiles(0), mFileFinder(ff)
{
    // We initialize the default value to return to 0 so if a file doesn't exist
    // then all files are automatically "newer" than it.

    // Set file extensions to look for. Right now just pngs.
    mExtensions.push(String8(".png"));

    // Load files into our data members
    loadFiles();
}

size_t CrunchCache::crunch(CacheUpdater* cu, bool forceOverwrite)
{
    size_t numFilesUpdated = 0;

    // Iterate through the source files and compare to cache.
    // After processing a file, remove it from the source files and
    // from the dest files.
    // We're done when we're out of files in source.
    String8 relativePath;
    while (mSourceFiles.size() > 0) {
        // Get the full path to the source file, then convert to a c-string
        // and offset our beginning pointer to the length of the sourcePath
        // This efficiently strips the source directory prefix from our path.
        // Also, String8 doesn't have a substring method so this is what we've
        // got to work with.
        const char* rPathPtr = mSourceFiles.keyAt(0).string()+mSourcePath.length();
        // Strip leading slash if present
        int offset = 0;
        if (rPathPtr[0] == OS_PATH_SEPARATOR)
            offset = 1;
        relativePath = String8(rPathPtr + offset);

        if (forceOverwrite || needsUpdating(relativePath)) {
            cu->processImage(mSourcePath.appendPathCopy(relativePath),
                             mDestPath.appendPathCopy(relativePath));
            numFilesUpdated++;
            // crunchFile(relativePath);
        }
        // Delete this file from the source files and (if it exists) from the
        // dest files.
        mSourceFiles.removeItemsAt(0);
        mDestFiles.removeItem(mDestPath.appendPathCopy(relativePath));
    }

    // Iterate through what's left of destFiles and delete leftovers
    while (mDestFiles.size() > 0) {
        cu->deleteFile(mDestFiles.keyAt(0));
        mDestFiles.removeItemsAt(0);
    }

    // Update our knowledge of the files cache
    // both source and dest should be empty by now.
    loadFiles();

    return numFilesUpdated;
}

void CrunchCache::loadFiles()
{
    // Clear out our data structures to avoid putting in duplicates
    mSourceFiles.clear();
    mDestFiles.clear();

    // Make a directory walker that points to the system.
    DirectoryWalker* dw = new SystemDirectoryWalker();

    // Load files in the source directory
    mFileFinder->findFiles(mSourcePath, mExtensions, mSourceFiles,dw);

    // Load files in the destination directory
    mFileFinder->findFiles(mDestPath,mExtensions,mDestFiles,dw);

    delete dw;
}

bool CrunchCache::needsUpdating(String8 relativePath) const
{
    // Retrieve modification dates for this file entry under the source and
    // cache directory trees. The vectors will return a modification date of 0
    // if the file doesn't exist.
    time_t sourceDate = mSourceFiles.valueFor(mSourcePath.appendPathCopy(relativePath));
    time_t destDate = mDestFiles.valueFor(mDestPath.appendPathCopy(relativePath));
    return sourceDate > destDate;
}