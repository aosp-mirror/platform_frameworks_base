//
// Copyright 2011 The Android Open Source Project
//

// File Finder implementation.
// Implementation for the functions declared and documented in FileFinder.h

#include <utils/Vector.h>
#include <utils/String8.h>
#include <utils/KeyedVector.h>


#include <iostream>

#include "DirectoryWalker.h"
#include "FileFinder.h"

//#define DEBUG

using android::String8;
using std::cout;
using std::endl;

bool SystemFileFinder::findFiles(String8 basePath, Vector<String8>& extensions,
                                 KeyedVector<String8,time_t>& fileStore,
                                 DirectoryWalker* dw)
{
    // Scan the directory pointed to by basePath
    // check files and recurse into subdirectories.
    if (!dw->openDir(basePath)) {
        return false;
    }
#ifdef DEBUG
    cout << "FileFinder looking in " << basePath << endl;
#endif // DEBUG
    /*
     *  Go through all directory entries. Check each file using checkAndAddFile
     *  and recurse into sub-directories.
     */
    struct dirent* entry;
    while ((entry = dw->nextEntry()) != NULL) {
        String8 entryName(entry->d_name);
        if (entry->d_name[0] == '.') // Skip hidden files and directories
            continue;

        String8 fullPath = basePath.appendPathCopy(entryName);
        // If this entry is a directory we'll recurse into it
        if (entry->d_type == DT_DIR) {
            DirectoryWalker* copy = dw->clone();
            findFiles(fullPath, extensions, fileStore,copy);
            delete copy;
        }

        // If this entry is a file, we'll pass it over to checkAndAddFile
        if (entry->d_type == DT_REG) {
            checkAndAddFile(fullPath,dw->entryStats(),extensions,fileStore);
        }
    }

    // Clean up
    dw->closeDir();

    return true;
}

void SystemFileFinder::checkAndAddFile(String8 path, const struct stat* stats,
                                       Vector<String8>& extensions,
                                       KeyedVector<String8,time_t>& fileStore)
{
#ifdef DEBUG
    cout << "Checking file " << path << "...";
#endif // DEBUG
    // Loop over the extensions, checking for a match
    bool done = false;
    String8 ext(path.getPathExtension());
    ext.toLower();
    for (size_t i = 0; i < extensions.size() && !done; ++i) {
        String8 ext2 = extensions[i].getPathExtension();
        ext2.toLower();
        // Compare the extensions. If a match is found, add to storage.
        if (ext == ext2) {
#ifdef DEBUG
            cout << "Match";
#endif // DEBUG
            done = true;
            fileStore.add(path,stats->st_mtime);
        }
    }
#ifdef DEBUG
    cout << endl;
#endif //DEBUG
}