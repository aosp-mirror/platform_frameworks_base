//
// Copyright 2011 The Android Open Source Project
//
// Abstraction of calls to system to make directories and delete files and
// wrapper to image processing.

#ifndef CACHE_UPDATER_H
#define CACHE_UPDATER_H

#include <androidfw/PathUtils.h>
#include <utils/String8.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <stdio.h>
#include "Images.h"
#ifdef _WIN32
#include <direct.h>
#endif

#include "Utils.h"

using namespace android;

/** CacheUpdater
 *  This is a pure virtual class that declares abstractions of functions useful
 *  for managing a cache files. This manager is set up to be used in a
 *  mirror cache where the source tree is duplicated and filled with processed
 *  images. This class is abstracted to allow for dependency injection during
 *  unit testing.
 *  Usage:
 *      To update/add a file to the cache, call processImage
 *      To remove a file from the cache, call deleteFile
 */
class CacheUpdater {
public:
    virtual ~CacheUpdater() {}

    // Make sure all the directories along this path exist
    virtual void ensureDirectoriesExist(String8 path) = 0;

    // Delete a file
    virtual void deleteFile(String8 path) = 0;

    // Process an image from source out to dest
    virtual void processImage(String8 source, String8 dest) = 0;
private:
};

/** SystemCacheUpdater
 * This is an implementation of the above virtual cache updater specification.
 * This implementations hits the filesystem to manage a cache and calls out to
 * the PNG crunching in images.h to process images out to its cache components.
 */
class SystemCacheUpdater : public CacheUpdater {
public:
    // Constructor to set bundle to pass to preProcessImage
    explicit SystemCacheUpdater (Bundle* b)
        : bundle(b) { };

    // Make sure all the directories along this path exist
    virtual void ensureDirectoriesExist(String8 path)
    {
        // Check to see if we're dealing with a fully qualified path
        String8 existsPath;
        String8 toCreate;
        String8 remains;
        struct stat s;

        // Check optomistically to see if all directories exist.
        // If something in the path doesn't exist, then walk the path backwards
        // and find the place to start creating directories forward.
        if (stat(path.c_str(),&s) == -1) {
            // Walk backwards to find place to start creating directories
            existsPath = path;
            do {
                // As we remove the end of existsPath add it to
                // the string of paths to create.
                toCreate = appendPathCopy(getPathLeaf(existsPath), toCreate);
                existsPath = getPathDir(existsPath);
            } while (stat(existsPath.c_str(),&s) == -1);

            // Walk forwards and build directories as we go
            do {
                // Advance to the next segment of the path
                appendPath(existsPath, walkPath(toCreate, &remains));
                toCreate = remains;
#ifdef _WIN32
                _mkdir(existsPath.c_str());
#else
                mkdir(existsPath.c_str(), S_IRUSR|S_IWUSR|S_IXUSR|S_IRGRP|S_IXGRP);
#endif
            } while (remains.length() > 0);
        } //if
    };

    // Delete a file
    virtual void deleteFile(String8 path)
    {
        if (remove(path.c_str()) != 0)
            fprintf(stderr,"ERROR DELETING %s\n",path.c_str());
    };

    // Process an image from source out to dest
    virtual void processImage(String8 source, String8 dest)
    {
        // Make sure we're trying to write to a directory that is extant
        ensureDirectoriesExist(getPathDir(dest));

        preProcessImageToCache(bundle, source, dest);
    };
private:
    Bundle* bundle;
};

#endif // CACHE_UPDATER_H
