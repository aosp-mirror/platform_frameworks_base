/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "MtpMediaScanner"

#include "MtpDebug.h"
#include "MtpDatabase.h"
#include "MtpMediaScanner.h"
#include "mtp.h"

#include <sys/types.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <unistd.h>
#include <dirent.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>
#include <limits.h>

#include <media/mediascanner.h>
#include <media/stagefright/StagefrightMediaScanner.h>

namespace android {

class MtpMediaScannerClient : public MediaScannerClient
{
public:
    MtpMediaScannerClient()
    {
        reset();
    }

    virtual ~MtpMediaScannerClient()
    {
    }

    // returns true if it succeeded, false if an exception occured in the Java code
    virtual bool scanFile(const char* path, long long lastModified, long long fileSize)
    {
        LOGV("scanFile %s", path);
        return true;
    }

    // returns true if it succeeded, false if an exception occured in the Java code
    virtual bool handleStringTag(const char* name, const char* value)
    {
        int temp;

        if (!strcmp(name, "title")) {
            mTitle = value;
            mHasTitle = true;
        } else if  (!strcmp(name, "artist")) {
            mArtist = value;
            mHasArtist = true;
        } else if  (!strcmp(name, "album")) {
            mAlbum = value;
            mHasAlbum = true;
        } else if  (!strcmp(name, "albumartist")) {
            mAlbumArtist = value;
            mHasAlbumArtist = true;
        } else if  (!strcmp(name, "genre")) {
            // FIXME - handle numeric values here
            mGenre = value;
            mHasGenre = true;
        } else if  (!strcmp(name, "composer")) {
            mComposer = value;
            mHasComposer = true;
        } else if  (!strcmp(name, "tracknumber")) {
            if (sscanf(value, "%d", &temp) == 1)
                mTrack = temp;
        } else if  (!strcmp(name, "discnumber")) {
            // currently unused
        } else if  (!strcmp(name, "year") || !strcmp(name, "date")) {
            if (sscanf(value, "%d", &temp) == 1)
                mYear = temp;
        } else if  (!strcmp(name, "duration")) {
            if (sscanf(value, "%d", &temp) == 1)
                mDuration = temp;
        } else {
            LOGV("handleStringTag %s : %s", name, value);
        }
        return true;
    }

    // returns true if it succeeded, false if an exception occured in the Java code
    virtual bool setMimeType(const char* mimeType)
    {
        mMimeType = mimeType;
        mHasMimeType = true;
        return true;
    }

    // returns true if it succeeded, false if an exception occured in the Java code
    virtual bool addNoMediaFolder(const char* path)
    {
        LOGV("addNoMediaFolder %s", path);
        return true;
    }

    void reset()
    {
        mHasTitle = false;
        mHasArtist = false;
        mHasAlbum = false;
        mHasAlbumArtist = false;
        mHasGenre = false;
        mHasComposer = false;
        mHasMimeType = false;
        mTrack = mYear = mDuration = 0;
    }

    inline const char* getTitle() const { return mHasTitle ? (const char *)mTitle : NULL; }
    inline const char* getArtist() const { return mHasArtist ? (const char *)mArtist : NULL; }
    inline const char* getAlbum() const { return mHasAlbum ? (const char *)mAlbum : NULL; }
    inline const char* getAlbumArtist() const { return mHasAlbumArtist ? (const char *)mAlbumArtist : NULL; }
    inline const char* getGenre() const { return mHasGenre ? (const char *)mGenre : NULL; }
    inline const char* getComposer() const { return mHasComposer ? (const char *)mComposer : NULL; }
    inline const char* getMimeType() const { return mHasMimeType ? (const char *)mMimeType : NULL; }
    inline int getTrack() const { return mTrack; }
    inline int getYear() const { return mYear; }
    inline int getDuration() const { return mDuration; }

private:
    MtpString   mTitle;
    MtpString   mArtist;
    MtpString   mAlbum;
    MtpString   mAlbumArtist;
    MtpString   mGenre;
    MtpString   mComposer;
    MtpString   mMimeType;

    bool        mHasTitle;
    bool        mHasArtist;
    bool        mHasAlbum;
    bool        mHasAlbumArtist;
    bool        mHasGenre;
    bool        mHasComposer;
    bool        mHasMimeType;

    int         mTrack;
    int         mYear;
    int         mDuration;
};


MtpMediaScanner::MtpMediaScanner(MtpStorageID id, const char* filePath, MtpDatabase* db)
    :   mStorageID(id),
        mFilePath(filePath),
        mDatabase(db),
        mMediaScanner(NULL),
        mMediaScannerClient(NULL),
        mFileList(NULL),
        mFileCount(0)
{
    mMediaScanner = new StagefrightMediaScanner;
    mMediaScannerClient = new MtpMediaScannerClient;
}

MtpMediaScanner::~MtpMediaScanner() {
}

bool MtpMediaScanner::scanFiles() {
    mDatabase->beginTransaction();
    mFileCount = 0;
    mFileList = mDatabase->getFileList(mFileCount);

    int ret = scanDirectory(mFilePath, MTP_PARENT_ROOT);

    for (int i = 0; i < mFileCount; i++) {
        MtpObjectHandle test = mFileList[i];
        if (! (test & kObjectHandleMarkBit)) {
            LOGV("delete missing file %08X", test);
            mDatabase->deleteFile(test);
        }
    }

    delete[] mFileList;
    mFileCount = 0;
    mDatabase->commitTransaction();
    return (ret == 0);
}


static const struct MediaFileTypeEntry
{
    const char*     extension;
    MtpObjectFormat format;
    uint32_t        table;
} sFileTypes[] =
{
    { "MP3",    MTP_FORMAT_MP3,             kObjectHandleTableAudio     },
    { "M4A",    MTP_FORMAT_UNDEFINED_AUDIO, kObjectHandleTableAudio     },
    { "WAV",    MTP_FORMAT_WAV,             kObjectHandleTableAudio     },
    { "AMR",    MTP_FORMAT_UNDEFINED_AUDIO, kObjectHandleTableAudio     },
    { "AWB",    MTP_FORMAT_UNDEFINED_AUDIO, kObjectHandleTableAudio     },
    { "WMA",    MTP_FORMAT_WMA,             kObjectHandleTableAudio     },
    { "OGG",    MTP_FORMAT_OGG,             kObjectHandleTableAudio     },
    { "OGA",    MTP_FORMAT_UNDEFINED_AUDIO, kObjectHandleTableAudio     },
    { "AAC",    MTP_FORMAT_AAC,             kObjectHandleTableAudio     },
    { "MID",    MTP_FORMAT_UNDEFINED_AUDIO, kObjectHandleTableAudio     },
    { "MIDI",   MTP_FORMAT_UNDEFINED_AUDIO, kObjectHandleTableAudio     },
    { "XMF",    MTP_FORMAT_UNDEFINED_AUDIO, kObjectHandleTableAudio     },
    { "RTTTL",  MTP_FORMAT_UNDEFINED_AUDIO, kObjectHandleTableAudio     },
    { "SMF",    MTP_FORMAT_UNDEFINED_AUDIO, kObjectHandleTableAudio     },
    { "IMY",    MTP_FORMAT_UNDEFINED_AUDIO, kObjectHandleTableAudio     },
    { "RTX",    MTP_FORMAT_UNDEFINED_AUDIO, kObjectHandleTableAudio     },
    { "OTA",    MTP_FORMAT_UNDEFINED_AUDIO, kObjectHandleTableAudio     },
    { "MPEG",   MTP_FORMAT_UNDEFINED_VIDEO, kObjectHandleTableVideo     },
    { "MP4",    MTP_FORMAT_UNDEFINED_VIDEO, kObjectHandleTableVideo     },
    { "M4V",    MTP_FORMAT_UNDEFINED_VIDEO, kObjectHandleTableVideo     },
    { "3GP",    MTP_FORMAT_UNDEFINED_VIDEO, kObjectHandleTableVideo     },
    { "3GPP",   MTP_FORMAT_UNDEFINED_VIDEO, kObjectHandleTableVideo     },
    { "3G2",    MTP_FORMAT_UNDEFINED_VIDEO, kObjectHandleTableVideo     },
    { "3GPP2",  MTP_FORMAT_UNDEFINED_VIDEO, kObjectHandleTableVideo     },
    { "WMV",    MTP_FORMAT_UNDEFINED_VIDEO, kObjectHandleTableVideo     },
    { "ASF",    MTP_FORMAT_UNDEFINED_VIDEO, kObjectHandleTableVideo     },
    { "JPG",    MTP_FORMAT_EXIF_JPEG,       kObjectHandleTableImage     },
    { "JPEG",   MTP_FORMAT_EXIF_JPEG,       kObjectHandleTableImage     },
    { "GIF",    MTP_FORMAT_GIF,             kObjectHandleTableImage     },
    { "PNG",    MTP_FORMAT_PNG,             kObjectHandleTableImage     },
    { "BMP",    MTP_FORMAT_BMP,             kObjectHandleTableImage     },
    { "WBMP",   MTP_FORMAT_BMP,             kObjectHandleTableImage     },
    { "M3U",    MTP_FORMAT_M3U_PLAYLIST,    kObjectHandleTablePlaylist  },
    { "PLS",    MTP_FORMAT_PLS_PLAYLIST,    kObjectHandleTablePlaylist  },
    { "WPL",    MTP_FORMAT_WPL_PLAYLIST,    kObjectHandleTablePlaylist  },
};

MtpObjectFormat MtpMediaScanner::getFileFormat(const char* path, uint32_t& table)
{
    const char* extension = strrchr(path, '.');
    if (!extension)
        return MTP_FORMAT_UNDEFINED;
    extension++; // skip the dot

    for (unsigned i = 0; i < sizeof(sFileTypes) / sizeof(sFileTypes[0]); i++) {
        if (!strcasecmp(extension, sFileTypes[i].extension)) {
            table = sFileTypes[i].table;
            return sFileTypes[i].format;
        }
    }
    table = kObjectHandleTableFile;
    return MTP_FORMAT_UNDEFINED;
}

int MtpMediaScanner::scanDirectory(const char* path, MtpObjectHandle parent)
{
    char buffer[PATH_MAX];
    struct dirent* entry;

    unsigned length = strlen(path);
    if (length > sizeof(buffer) + 2) {
        LOGE("path too long: %s", path);
    }

    DIR* dir = opendir(path);
    if (!dir) {
        LOGE("opendir %s failed, errno: %d", path, errno);
        return -1;
    }

    strncpy(buffer, path, sizeof(buffer));
    char* fileStart = buffer + length;
    // make sure we have a trailing slash
    if (fileStart[-1] != '/') {
        *(fileStart++) = '/';
    }
    int fileNameLength = sizeof(buffer) + fileStart - buffer;

    while ((entry = readdir(dir))) {
        const char* name = entry->d_name;

        // ignore "." and "..", as well as any files or directories staring with dot
        if (name[0] == '.') {
            continue;
        }
        if (strlen(name) + 1 > fileNameLength) {
            LOGE("path too long for %s", name);
            continue;
        }
        strcpy(fileStart, name);

        struct stat statbuf;
        memset(&statbuf, 0, sizeof(statbuf));
        stat(buffer, &statbuf);

        if (S_ISDIR(statbuf.st_mode)) {
            MtpObjectHandle handle = mDatabase->getObjectHandle(buffer);
            if (handle) {
                markFile(handle);
            } else {
                handle = mDatabase->addFile(buffer, MTP_FORMAT_ASSOCIATION,
                        parent, mStorageID, 0, statbuf.st_mtime);
            }
            scanDirectory(buffer, handle);
        } else if (S_ISREG(statbuf.st_mode)) {
            scanFile(buffer, parent, statbuf);
        }
    }

    closedir(dir);
    return 0;
}

void MtpMediaScanner::scanFile(const char* path, MtpObjectHandle parent, struct stat& statbuf) {
    uint32_t table;
    MtpObjectFormat format = getFileFormat(path, table);
    // don't scan unknown file types
    if (format == MTP_FORMAT_UNDEFINED)
        return;
    MtpObjectHandle handle = mDatabase->getObjectHandle(path);
    // fixme - rescan if mod date changed
    if (handle) {
        markFile(handle);
    } else {
        mDatabase->beginTransaction();
        handle = mDatabase->addFile(path, format, parent, mStorageID,
                statbuf.st_size, statbuf.st_mtime);
        if (handle <= 0) {
            LOGE("addFile failed in MtpMediaScanner::scanFile()");
            mDatabase->rollbackTransaction();
            return;
        }

        if (table == kObjectHandleTableAudio) {
            mMediaScannerClient->reset();
            mMediaScanner->processFile(path, NULL, *mMediaScannerClient);
            handle = mDatabase->addAudioFile(handle,
                    mMediaScannerClient->getTitle(),
                    mMediaScannerClient->getArtist(),
                    mMediaScannerClient->getAlbum(),
                    mMediaScannerClient->getAlbumArtist(),
                    mMediaScannerClient->getGenre(),
                    mMediaScannerClient->getComposer(),
                    mMediaScannerClient->getMimeType(),
                    mMediaScannerClient->getTrack(),
                    mMediaScannerClient->getYear(),
                    mMediaScannerClient->getDuration());
        }
        mDatabase->commitTransaction();
    }
}

void MtpMediaScanner::markFile(MtpObjectHandle handle) {
    if (mFileList) {
        handle &= kObjectHandleIndexMask;
        // binary search for the file in mFileList
        int low = 0;
        int high = mFileCount;
        int index;

        while (low < high) {
            index = (low + high) >> 1;
            MtpObjectHandle test = (mFileList[index] & kObjectHandleIndexMask);
            if (handle < test)
                high = index;       // item is less than index
            else if (handle > test)
                low = index + 1;    // item is greater than index
            else {
                mFileList[index] |= kObjectHandleMarkBit;
                return;
            }
        }
        LOGE("file %d not found in mFileList", handle);
    }
}

}  // namespace android
