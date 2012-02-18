/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//
// Access a chunk of the asset hierarchy as if it were a single directory.
//
#ifndef __LIBS_ASSETDIR_H
#define __LIBS_ASSETDIR_H

#include <utils/String8.h>
#include <utils/Vector.h>
#include <utils/SortedVector.h>
#include <utils/misc.h>
#include <sys/types.h>

namespace android {

/*
 * This provides vector-style access to a directory.  We do this rather
 * than modeling opendir/readdir access because it's simpler and the
 * nature of the operation requires us to have all data on hand anyway.
 *
 * The list of files will be sorted in ascending order by ASCII value.
 *
 * The contents are populated by our friend, the AssetManager.
 */
class AssetDir {
public:
    AssetDir(void)
        : mFileInfo(NULL)
        {}
    virtual ~AssetDir(void) {
        delete mFileInfo;
    }

    /*
     * Vector-style access.
     */
    size_t getFileCount(void) { return mFileInfo->size(); }
    const String8& getFileName(int idx) {
        return mFileInfo->itemAt(idx).getFileName();
    }
    const String8& getSourceName(int idx) {
        return mFileInfo->itemAt(idx).getSourceName();
    }

    /*
     * Get the type of a file (usually regular or directory).
     */
    FileType getFileType(int idx) {
        return mFileInfo->itemAt(idx).getFileType();
    }

private:
    /* these operations are not implemented */
    AssetDir(const AssetDir& src);
    const AssetDir& operator=(const AssetDir& src);

    friend class AssetManager;

    /*
     * This holds information about files in the asset hierarchy.
     */
    class FileInfo {
    public:
        FileInfo(void) {}
        FileInfo(const String8& path)      // useful for e.g. svect.indexOf
            : mFileName(path), mFileType(kFileTypeUnknown)
            {}
        ~FileInfo(void) {}
        FileInfo(const FileInfo& src) {
            copyMembers(src);
        }
        const FileInfo& operator= (const FileInfo& src) {
            if (this != &src)
                copyMembers(src);
            return *this;
        }

        void copyMembers(const FileInfo& src) {
            mFileName = src.mFileName;
            mFileType = src.mFileType;
            mSourceName = src.mSourceName;
        }

        /* need this for SortedVector; must compare only on file name */
        bool operator< (const FileInfo& rhs) const {
            return mFileName < rhs.mFileName;
        }

        /* used by AssetManager */
        bool operator== (const FileInfo& rhs) const {
            return mFileName == rhs.mFileName;
        }

        void set(const String8& path, FileType type) {
            mFileName = path;
            mFileType = type;
        }

        const String8& getFileName(void) const { return mFileName; }
        void setFileName(const String8& path) { mFileName = path; }

        FileType getFileType(void) const { return mFileType; }
        void setFileType(FileType type) { mFileType = type; }

        const String8& getSourceName(void) const { return mSourceName; }
        void setSourceName(const String8& path) { mSourceName = path; }

        /*
         * Handy utility for finding an entry in a sorted vector of FileInfo.
         * Returns the index of the matching entry, or -1 if none found.
         */
        static int findEntry(const SortedVector<FileInfo>* pVector,
            const String8& fileName);

    private:
        String8    mFileName;      // filename only
        FileType    mFileType;      // regular, directory, etc

        String8    mSourceName;    // currently debug-only
    };

    /* AssetManager uses this to initialize us */
    void setFileList(SortedVector<FileInfo>* list) { mFileInfo = list; }

    SortedVector<FileInfo>* mFileInfo;
};

}; // namespace android

#endif // __LIBS_ASSETDIR_H
