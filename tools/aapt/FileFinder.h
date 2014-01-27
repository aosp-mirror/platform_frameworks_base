//
// Copyright 2011 The Android Open Source Project
//

// File Finder.
// This is a collection of useful functions for finding paths and modification
// times of files that match an extension pattern in a directory tree.
// and finding files in it.

#ifndef FILEFINDER_H
#define FILEFINDER_H

#include <utils/Vector.h>
#include <utils/KeyedVector.h>
#include <utils/String8.h>

#include "DirectoryWalker.h"

using namespace android;

// Abstraction to allow for dependency injection. See MockFileFinder.h
// for the testing implementation.
class FileFinder {
public:
    virtual bool findFiles(String8 basePath, Vector<String8>& extensions,
                           KeyedVector<String8,time_t>& fileStore,
                           DirectoryWalker* dw) = 0;

    virtual ~FileFinder() {};
};

class SystemFileFinder : public FileFinder {
public:

    /* findFiles takes a path, a Vector of extensions, and a destination KeyedVector
     *           and places path/modification date key/values pointing to
     *          all files with matching extensions found into the KeyedVector
     * PRECONDITIONS
     *     path is a valid system path
     *     extensions should include leading "."
     *                This is not necessary, but the comparison directly
     *                compares the end of the path string so if the "."
     *                is excluded there is a small chance you could have
     *                a false positive match. (For example: extension "png"
     *                would match a file called "blahblahpng")
     *
     * POSTCONDITIONS
     *     fileStore contains (in no guaranteed order) paths to all
     *                matching files encountered in subdirectories of path
     *                as keys in the KeyedVector. Each key has the modification time
     *                of the file as its value.
     *
     * Calls checkAndAddFile on each file encountered in the directory tree
     * Recursively descends into subdirectories.
     */
    virtual bool findFiles(String8 basePath, Vector<String8>& extensions,
                           KeyedVector<String8,time_t>& fileStore,
                           DirectoryWalker* dw);

private:
    /**
     * checkAndAddFile looks at a single file path and stat combo
     * to determine whether it is a matching file (by looking at
     * the extension)
     *
     * PRECONDITIONS
     *    no setup is needed
     *
     * POSTCONDITIONS
     *    If the given file has a matching extension then a new entry
     *    is added to the KeyedVector with the path as the key and the modification
     *    time as the value.
     *
     */
    static void checkAndAddFile(String8 path, const struct stat* stats,
                                Vector<String8>& extensions,
                                KeyedVector<String8,time_t>& fileStore);

};
#endif // FILEFINDER_H
