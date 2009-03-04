/*
 * Copyright (C) 2007 The Android Open Source Project
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

#include <objmng/drm_file.h>

#include <unistd.h>
#include <sys/param.h>
#include <sys/stat.h>
#include <stdio.h>
#include <fcntl.h>
#include <dirent.h>
#include <errno.h>
#include <string.h>

/**
 * Fails on zaurus?
 #define DEVICE_FILESYSTEM
*/
#define DEFAULT_TOTAL_SPACE (4L * 1024L * 1024L) /* 4 Meg. */

#ifndef DEVICE_FILESYSTEM
/* Store the total space on FS VM can use. */
static int32_t totalSpace;
/* how many remain space can VM use. */
static int32_t availableSize;
#endif

extern char* getStorageRoot(void);

static char tmpPathBuf1[MAX_FILENAME_LEN];
static char tmpPathBuf2[MAX_FILENAME_LEN];

static int32_t
convertFilename(const uint16_t *strData, int32_t strLength, char *buffer);

static int calcDirSize(char *path, int len, uint8_t includeSubdirs);

#ifndef DEVICE_FILESYSTEM
static void initFsVariables(void);
#endif

/**
 * Convert a Java string into a nul terminated ascii string to pass to posix
 * @param strData    first character of name
 * @param strLength  number of characters in name
 * @param buffer Buffer to store terminated string in (at least MAXPATHLEN)
 * @return Length of filename in characters (excl. nul), or -1 on failure.
 */
static int32_t
convertFilename(const uint16_t *strData, int32_t strLength, char *buffer)
{
    int idx;

    if (strLength >= (MAXPATHLEN-1))
    {
        Trace("convertFilename '%.*S' too long", strLength, strData);
        return -1;
    }

    for (idx = 0; idx < strLength; ++idx)
        *buffer++ = (char)*strData++;

    *buffer = 0;
    return strLength;
}


/**
 * Perform a stat() call on the given filename.
 * Helper for getFileLength and exists
 * @param name unicode name
 * @param nameLen number of unicode characters in name
 * @param sbuf stat buffer
 * @return TRUE on success, FALSE on failure
 */
static int32_t
getFileStat(const uint16_t *name, int32_t nameLen, struct stat *sbuf)
{
    Trace("getFileStat: %.*S", nameLen, name);

    if (convertFilename(name, nameLen, tmpPathBuf1) <= 0)
    {
        Trace("getFileStat: bad filename");
    }
    else if (stat(tmpPathBuf1, sbuf) != 0)
    {
        Trace("getFileStat %s: stat() errno=%d", tmpPathBuf1, errno);
    }
    else /* Successful */
    {
        return TRUE;
    }

    return FALSE;
}

#ifndef DEVICE_FILESYSTEM
/**
 * initial the variables like totalSpace, availableSize...
 */
static void initFsVariables(void)
{
    totalSpace = DEFAULT_TOTAL_SPACE;

    availableSize = totalSpace;
}
#endif /* DEVICE_FILESYSTEM */

/**
 * calculate the size of everything inside path pointed directory
 * this function will use path pointed buffer to store some extra info
 * so param len is needed.
 * @param path    the directory path need to calculate
 * @param len   length of the path buffer, not the path string length
 * @param includeSubdirs  also calculate all the subdirs in path holds?
 * @return the calculated size, DRM_FILE_FAILURE on failure.
 */
static int calcDirSize(char *path, int len, uint8_t includeSubdirs)
{
    struct dirent *ent;
    struct stat stat_buf;

    DIR *dir = NULL;
    int size = 0;
    int exists = -1;
    int dirPathLen = strlen(path);

    /* Ensure space for wildcard */
    if((dirPathLen + 2) >= MAXPATHLEN || (dirPathLen + 2) >= len)
    {
        return DRM_FILE_FAILURE;
    }

    if(path[dirPathLen - 1] != '/')
    {
        path[dirPathLen++] = '/';
        path[dirPathLen] = '\0';
    }

    dir = opendir(path);
    if (dir == NULL)
    {
        return DRM_FILE_FAILURE;
    }

    while ((ent = readdir(dir)) != NULL )
    {
        if (strcmp(ent->d_name, ".") == 0 ||
                strcmp(ent->d_name, "..") == 0)
        {
            continue;
        }

        path[dirPathLen] = '\0';
        if ((int)(strlen(ent->d_name) + dirPathLen + 1) < len)
        {
            strcat(path, ent->d_name);
        }
        else
        {
            continue;
        }

        exists = stat(path, &stat_buf);
        if (exists != -1)
        {
            /* exclude the storage occupied by directory itself */
            if (stat_buf.st_mode & S_IFDIR)
            {
                if(includeSubdirs)
                {
                    /* calculate the size recursively */
                    int ret;
                    ret = calcDirSize(path, len, includeSubdirs);
                    /* ignore failure in subdirs */
                    if( DRM_FILE_FAILURE != ret )
                    {
                        size += ret;
                    }
                }
            }
            else
            {
                size += stat_buf.st_size;
            }
        }
    }

    closedir(dir);
    return size;
}

/* see drm_file.h */
int32_t DRM_file_startup(void)
{
    Trace("DRM_file_startup");

#ifndef DEVICE_FILESYSTEM
    availableSize = -1;

    initFsVariables();
#endif

    return DRM_FILE_SUCCESS;    /* Nothing to do */
}

/* see drm_file.h */
int32_t
DRM_file_listOpen(const uint16_t *prefix,
                    int32_t prefixLen,
                    int32_t* session,
                    int32_t* iteration)
{
    Trace("DRM_file_listOpen: %.*S", prefixLen, prefix);

    if (convertFilename(prefix, prefixLen, tmpPathBuf1) <= 0)
    {
        Trace("DRM_file_listOpen: bad filename");
    }
    else
    {
        DIR *dir;

        /* find the last /, and store the offset to the leaf prefix in
         * *iteration
         */

        char *sep = strrchr(tmpPathBuf1, '/');
        /* Root "/" is a leaf */
        if (sep == NULL || ((sep != NULL) && (sep == tmpPathBuf1)))
        {
            *iteration = prefixLen;

#ifdef TRACE_ON
            sep = " <empty>"; /* trace will show sep+1 */
#endif
        }
        else
        {
            *iteration = sep - tmpPathBuf1 + 1;
            *sep = 0;
        }

        dir = opendir(tmpPathBuf1);

        if (dir == NULL)
        {
            Trace("DRM_file_listOpen: opendir %s: errno=%d", tmpPathBuf1, errno);
        }
        else
        {
            Trace("DRM_file_listOpen: dir %s, filter %s", tmpPathBuf1, sep+1);
            *session = (int32_t)dir;
            return DRM_FILE_SUCCESS;
        }
    }

    return DRM_FILE_FAILURE;
}

/* see drm_file.h */
int32_t
DRM_file_listNextEntry(const uint16_t *prefix, int32_t prefixLen,
                       uint16_t* entry, int32_t entrySize,
                       int32_t *session, int32_t* iteration)
{
    struct dirent *ent;

    /* We stored the offset of the leaf part of the prefix (if any)
     * in *iteration
     */
    const uint16_t* strData   = prefix + *iteration;
    int32_t   strLength = prefixLen - *iteration;

    /* entrySize is bytes for some reason. Convert to ucs chars */
    entrySize /= 2;

    /* Now we want to filter for files which start with the (possibly empty)
     * sequence at strData. We have to return fully-qualified filenames,
     * which means *iteration characters from prefix, plus the
     * leaf name.
     */

    while ( (ent = readdir((DIR *)*session)) != NULL)
    {
        int len = strlen(ent->d_name);

        if ( (len + *iteration) > entrySize)
        {
            Trace("DRM_file_listNextEntry: %s too long", ent->d_name);
        }
        else if (strcmp(ent->d_name, ".") != 0 &&
                 strcmp(ent->d_name, "..") != 0)
        {
            int idx;
            struct stat sinfo;

            /* check against the filter */

            for (idx = 0; idx < strLength; ++idx)
            {
                if (ent->d_name[idx] != strData[idx])
                    goto next_name;
            }

            Trace("DRM_file_listNextEntry: matched %s", ent->d_name);

            /* Now generate the fully-qualified name */

            for (idx = 0; idx < *iteration; ++idx)
                entry[idx] = prefix[idx];

            for (idx = 0; idx < len; ++idx)
                entry[*iteration + idx] = (unsigned char)ent->d_name[idx];

            /*add "/" at the end of a DIR file entry*/
            if (getFileStat(entry, idx + *iteration, &sinfo)){
                if (S_ISDIR(sinfo.st_mode) &&
                        (idx + 1 + *iteration) < entrySize) {
                    entry[*iteration + idx] = '/';
                    ++idx;
                }
            }
            else
            {
                Trace("DRM_file_listNextEntry: stat FAILURE on %.*S",
                      idx + *iteration, entry);
            }
            Trace("DRM_file_listNextEntry: got %.*S", idx + *iteration, entry);

            return idx + *iteration;
        }

    next_name:
        Trace("DRM_file_listNextEntry: rejected %s", ent->d_name);
    }

    Trace("DRM_file_listNextEntry: end of list");
    return 0;
}

/* see drm_file.h */
int32_t
DRM_file_listClose(int32_t session, int32_t iteration)
{
    closedir( (DIR *)session);
    return DRM_FILE_SUCCESS;
}

/* see drm_file.h */
int32_t
DRM_file_getFileLength(const uint16_t *name, int32_t nameLen)
{
    struct stat sbuf;

    if (getFileStat(name, nameLen, &sbuf))
    {
        if (sbuf.st_size >= INT32_MAX)
        {
            Trace("DRM_file_getFileLength: file too big");
        }
        else /* Successful */
        {
            Trace("DRM_file_getFileLength: %.*S -> %d",
                                         nameLen, name, (int32_t)sbuf.st_size);
            return (int32_t)sbuf.st_size;
        }
    }

    return DRM_FILE_FAILURE;
}

/* see drm_file.h */
int32_t
DRM_file_delete(const uint16_t *name, int32_t nameLen)
{
    Trace("DRM_file_delete: %.*S", nameLen, name);

    if (convertFilename(name, nameLen, tmpPathBuf1) <= 0)
    {
        Trace("DRM_file_delete: bad filename");
        return DRM_FILE_FAILURE;
    }
    else
    {
       struct stat sinfo;
       if (stat(tmpPathBuf1, &sinfo) != 0){
           Trace("DRM_file_delete: stat failed, errno=%d", errno);
           return DRM_FILE_FAILURE;
       }
#ifndef DEVICE_FILESYSTEM
       if (S_ISDIR(sinfo.st_mode)){
            /* it's a dir */
            if (rmdir(tmpPathBuf1) != 0){
                Trace("DRM_file_delete: dir remove failed, errno=%d", errno);
                return DRM_FILE_FAILURE;
            }
            else
            {
                return DRM_FILE_SUCCESS;
            }
        }
#endif
        /* it's a file */
        if (unlink(tmpPathBuf1) != 0)
        {
            Trace("DRM_file_delete: file remove failed, errno=%d", errno);
            return DRM_FILE_FAILURE;
        }
        else
        {
#ifndef DEVICE_FILESYSTEM
            availableSize += sinfo.st_size;
#endif
            return DRM_FILE_SUCCESS;
        }
    }
    return DRM_FILE_FAILURE;
}

/* see drm_file.h */
int32_t
DRM_file_rename(const uint16_t *oldName, int32_t oldNameLen,
                const uint16_t *newName, int32_t newNameLen)
{
    Trace("DRM_file_rename %.*S -> %.*S",
                                    oldNameLen, oldName, newNameLen, newName);
    if (DRM_file_exists(newName, newNameLen) != DRM_FILE_FAILURE)
    {
        Trace("DRM_file_rename: filename:%s exist",newName);
        return DRM_FILE_FAILURE;
    }

    if (convertFilename(oldName, oldNameLen, tmpPathBuf1) <= 0 ||
        convertFilename(newName, newNameLen, tmpPathBuf2) <= 0)
    {
        Trace("DRM_file_rename: bad filename");
    }
    else if (rename(tmpPathBuf1, tmpPathBuf2) != 0)
    {
         Trace("DRM_file_rename: failed errno=%d", errno);
    }
    else /* Success */
    {
        return DRM_FILE_SUCCESS;
    }

    return DRM_FILE_FAILURE;
}

/* see drm_file.h */
int32_t
DRM_file_exists(const uint16_t *name, int32_t nameLen)
{
    struct stat sbuf;

    Trace("DRM_file_exists: %.*S", nameLen, name);

    /*remove trailing "/" separators, except the first "/" standing for root*/
    while ((nameLen > 1) && (name[nameLen -1] == '/'))
       --nameLen;

    if (getFileStat(name, nameLen, &sbuf))
    {
        Trace("DRM_file_exists: stat returns mode 0x%x", sbuf.st_mode);

        if (S_ISDIR(sbuf.st_mode))
            return DRM_FILE_ISDIR;
        if (S_ISREG(sbuf.st_mode))
            return DRM_FILE_ISREG;
    }

    return DRM_FILE_FAILURE;
}

/* see drm_file.h */
int32_t
DRM_file_open(const uint16_t *name, int32_t nameLen, int32_t mode,
                      int32_t* handle)
{
    int res;

#if DRM_FILE_MODE_READ != 1 || DRM_FILE_MODE_WRITE != 2
#error constants changed
#endif

    /* Convert DRM file modes to posix modes */
    static const int modes[4] =
    { 0,
      O_RDONLY,
      O_WRONLY | O_CREAT,
      O_RDWR | O_CREAT
    };

    Trace("DRM_file_open %.*S mode 0x%x", nameLen, name, mode);

    assert((mode & ~(DRM_FILE_MODE_READ|DRM_FILE_MODE_WRITE)) == 0);

    if (convertFilename(name, nameLen, tmpPathBuf1) <= 0)
    {
        Trace("DRM_file_open: bad filename");
        return DRM_FILE_FAILURE;
    }

    if ((res = open(tmpPathBuf1, modes[mode], 0777)) == -1)
    {
        Trace("DRM_file_open: open failed errno=%d", errno);
        return DRM_FILE_FAILURE;
    }

    Trace("DRM_file_open: open '%s; returned %d", tmpPathBuf1, res);
    *handle = res;

    return DRM_FILE_SUCCESS;
}

/* see drm_file.h */
int32_t
DRM_file_read(int32_t handle, uint8_t* dst, int32_t length)
{
    int n;

    assert(length > 0);

    /* TODO: Make dst a void *? */

    n = read((int)handle, dst, (size_t)length);
    if (n > 0)
    {
        Trace("DRM_file_read handle=%d read %d bytes", handle, n);
        return n;
    }
    else if (n == 0)
    {
        Trace("DRM_file_read read EOF: handle=%d", handle);
        return DRM_FILE_EOF;
    }
    else
    {
        Trace("DRM_file_read failed handle=%d, errno=%d", handle, errno);
        return DRM_FILE_FAILURE;
    }
}

/* see drm_file.h */
int32_t
DRM_file_write(int32_t handle, const uint8_t* src, int32_t length)
{
    /* TODO: Make dst a void *? */
    int n;
#ifndef DEVICE_FILESYSTEM
    int delta;
    off_t prevPos;
    struct stat sbuf;
    int prevFileSize;
#endif

    assert(length >= 0);

#ifndef DEVICE_FILESYSTEM
    if ( -1 == fstat((int)handle, &sbuf) )
    {
        Trace("DRM_file_write: fstat error %d", errno);
        return DRM_FILE_FAILURE;
    }
    prevFileSize = (int)(sbuf.st_size);
    prevPos = lseek( (int)handle, 0, SEEK_CUR);
    if ( (off_t)-1 == prevPos )
    {
        Trace("DRM_file_write: get current pos error %d", errno);
        return DRM_FILE_FAILURE;
    }
    delta = (int)prevPos + length - prevFileSize;
    if (delta > availableSize)
    {
        Trace("DRM_file_write: not enough size!");
        return DRM_FILE_FAILURE;
    }
#endif
    n = write((int)handle, src, (size_t)length);
    if (n < 0)
    {
        Trace("DRM_file_write failed errno=%d", errno);
        return DRM_FILE_FAILURE;
    }
#ifndef DEVICE_FILESYSTEM
    delta = prevPos + n - prevFileSize;

    if ( delta > 0 )
    {
        availableSize -= delta;
    }
#endif
    Trace("DRM_file_write handle=%d wrote %d/%d bytes", handle, n, length);

    return n;
}

/* see drm_file.h */
int32_t DRM_file_close(int32_t handle)
{
    if (close((int)handle) == 0)
    {
        Trace("DRM_file_close handle=%d success", handle);
        return DRM_FILE_SUCCESS;
    }

    Trace("DRM_file_close handle=%d failed", handle);
    return DRM_FILE_FAILURE;
}

/* see drm_file.h */
int32_t
DRM_file_setPosition(int32_t handle, int32_t value)
{
#ifndef DEVICE_FILESYSTEM
    struct stat sbuf;
#endif
    off_t newPos;

    if (value < 0)
    {
        Trace("DRM_file_setPosition: handle=%d negative value (%d)",
            handle, value);
        return DRM_FILE_FAILURE;
    }

#ifndef DEVICE_FILESYSTEM
    if ( fstat((int)handle, &sbuf) == -1 )
    {
        Trace("DRM_file_setPosition: fstat fail errno=%d", errno);
        return DRM_FILE_FAILURE;
    }

    if ( ((off_t)value > sbuf.st_size) &&
         (availableSize < (value - (int)(sbuf.st_size))) )
    {
        Trace("DRM_file_setPosition: not enough space");
        return DRM_FILE_FAILURE;
    }
#endif

    newPos = lseek( (int)handle, (off_t)value, SEEK_SET);
    if ( newPos == (off_t)-1 )
    {
        Trace("DRM_file_setPosition: seek failed: errno=%d", errno);
    }
    else
    {
#ifndef DEVICE_FILESYSTEM
        if ( newPos > sbuf.st_size )
        {
            availableSize -= (int)(newPos - sbuf.st_size);
        }
#endif
        return DRM_FILE_SUCCESS;
    }

    return DRM_FILE_FAILURE;
}

/* see drm_file.h */
int32_t
DRM_file_mkdir(const uint16_t* name, int32_t nameChars)
{
    Trace("DRM_file_mkdir started!..");

    if (convertFilename(name, nameChars, tmpPathBuf1) <= 0)
    {
        Trace("DRM_file_mkdir: bad filename");
        return DRM_FILE_FAILURE;
    }

    if (mkdir(tmpPathBuf1,0777) != 0)
    {
        Trace("DRM_file_mkdir failed!errno=%d",errno);
        return DRM_FILE_FAILURE;
    }

    return DRM_FILE_SUCCESS;
}
