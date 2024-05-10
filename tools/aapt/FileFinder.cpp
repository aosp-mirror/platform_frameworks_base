//
// Copyright 2011 The Android Open Source Project
//

// File Finder implementation.
// Implementation for the functions declared and documented in FileFinder.h

#include <androidfw/PathUtils.h>
#include <utils/Vector.h>
#include <utils/String8.h>
#include <utils/KeyedVector.h>

#include <dirent.h>
#include <sys/stat.h>

#include "DirectoryWalker.h"
#include "FileFinder.h"

//#define DEBUG

using android::String8;

// Private function to check whether a file is a directory or not
bool isDirectory(const char* filename) {
    struct stat fileStat;
    if (stat(filename, &fileStat) == -1) {
        return false;
    }
    return(S_ISDIR(fileStat.st_mode));
}


// Private function to check whether a file is a regular file or not
bool isFile(const char* filename) {
    struct stat fileStat;
    if (stat(filename, &fileStat) == -1) {
        return false;
    }
    return(S_ISREG(fileStat.st_mode));
}

bool SystemFileFinder::findFiles(String8 basePath, Vector<String8>& extensions,
                                 KeyedVector<String8,time_t>& fileStore,
                                 DirectoryWalker* dw)
{
    // Scan the directory pointed to by basePath
    // check files and recurse into subdirectories.
    if (!dw->openDir(basePath)) {
        return false;
    }
    /*
     *  Go through all directory entries. Check each file using checkAndAddFile
     *  and recurse into sub-directories.
     */
    struct dirent* entry;
    while ((entry = dw->nextEntry()) != NULL) {
        String8 entryName(entry->d_name);
        if (entry->d_name[0] == '.') // Skip hidden files and directories
            continue;

        String8 fullPath = appendPathCopy(basePath, entryName);
        // If this entry is a directory we'll recurse into it
        if (isDirectory(fullPath.c_str()) ) {
            DirectoryWalker* copy = dw->clone();
            findFiles(fullPath, extensions, fileStore,copy);
            delete copy;
        }

        // If this entry is a file, we'll pass it over to checkAndAddFile
        if (isFile(fullPath.c_str()) ) {
            checkAndAddFile(fullPath,dw->entryStats(),extensions,fileStore);
        }
    }

    // Clean up
    dw->closeDir();

    return true;
}

void SystemFileFinder::checkAndAddFile(const String8& path, const struct stat* stats,
                                       Vector<String8>& extensions,
                                       KeyedVector<String8,time_t>& fileStore)
{
    // Loop over the extensions, checking for a match
    bool done = false;
    String8 ext(getPathExtension(path));
    ext.toLower();
    for (size_t i = 0; i < extensions.size() && !done; ++i) {
        String8 ext2 = getPathExtension(extensions[i]);
        ext2.toLower();
        // Compare the extensions. If a match is found, add to storage.
        if (ext == ext2) {
            done = true;
            fileStore.add(path,stats->st_mtime);
        }
    }
}

