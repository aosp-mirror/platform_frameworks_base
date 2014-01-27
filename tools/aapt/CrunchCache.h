//
// Copyright 2011 The Android Open Source Project
//
// Cache manager for pre-processed PNG files.
// Contains code for managing which PNG files get processed
// at build time.
//

#ifndef CRUNCHCACHE_H
#define CRUNCHCACHE_H

#include <utils/KeyedVector.h>
#include <utils/String8.h>
#include "FileFinder.h"
#include "CacheUpdater.h"

using namespace android;

/** CrunchCache
 *  This class is a cache manager which can pre-process PNG files and store
 *  them in a mirror-cache. It's capable of doing incremental updates to its
 *  cache.
 *
 *  Usage:
 *      Create an instance initialized with the root of the source tree, the
 *      root location to store the cache files, and an instance of a file finder.
 *      Then update the cache by calling crunch.
 */
class CrunchCache {
public:
    // Constructor
    CrunchCache(String8 sourcePath, String8 destPath, FileFinder* ff);

    // Nobody should be calling the default constructor
    // So this space is intentionally left blank

    // Default Copy Constructor and Destructor are fine

    /** crunch is the workhorse of this class.
     * It goes through all the files found in the sourcePath and compares
     * them to the cached versions in the destPath. If the optional
     * argument forceOverwrite is set to true, then all source files are
     * re-crunched even if they have not been modified recently. Otherwise,
     * source files are only crunched when they needUpdating. Afterwards,
     * we delete any leftover files in the cache that are no longer present
     * in source.
     *
     * PRECONDITIONS:
     *      No setup besides construction is needed
     * POSTCONDITIONS:
     *      The cache is updated to fully reflect all changes in source.
     *      The function then returns the number of files changed in cache
     *      (counting deletions).
     */
    size_t crunch(CacheUpdater* cu, bool forceOverwrite=false);

private:
    /** loadFiles is a wrapper to the FileFinder that places matching
     * files into mSourceFiles and mDestFiles.
     *
     *  POSTCONDITIONS
     *      mDestFiles and mSourceFiles are refreshed to reflect the current
     *      state of the files in the source and dest directories.
     *      Any previous contents of mSourceFiles and mDestFiles are cleared.
     */
    void loadFiles();

    /** needsUpdating takes a file path
     * and returns true if the file represented by this path is newer in the
     * sourceFiles than in the cache (mDestFiles).
     *
     * PRECONDITIONS:
     *      mSourceFiles and mDestFiles must be initialized and filled.
     * POSTCONDITIONS:
     *      returns true if and only if source file's modification time
     *      is greater than the cached file's mod-time. Otherwise returns false.
     *
     * USAGE:
     *      Should be used something like the following:
     *      if (needsUpdating(filePath))
     *          // Recrunch sourceFile out to destFile.
     *
     */
    bool needsUpdating(String8 relativePath) const;

    // DATA MEMBERS ====================================================

    String8 mSourcePath;
    String8 mDestPath;

    Vector<String8> mExtensions;

    // Each vector of paths contains one entry per PNG file encountered.
    // Each entry consists of a path pointing to that PNG.
    DefaultKeyedVector<String8,time_t> mSourceFiles;
    DefaultKeyedVector<String8,time_t> mDestFiles;

    // Pointer to a FileFinder to use
    FileFinder* mFileFinder;
};

#endif // CRUNCHCACHE_H
