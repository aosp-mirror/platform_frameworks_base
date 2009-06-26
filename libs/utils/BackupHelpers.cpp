/*
 * Copyright (C) 2009 The Android Open Source Project
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

#define LOG_TAG "file_backup_helper"

#include <utils/BackupHelpers.h>

#include <utils/KeyedVector.h>
#include <utils/ByteOrder.h>
#include <utils/String8.h>

#include <errno.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/stat.h>
#include <sys/time.h>  // for utimes
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <utime.h>
#include <fcntl.h>
#include <zlib.h>

#include <cutils/log.h>

namespace android {

#define MAGIC0 0x70616e53 // Snap
#define MAGIC1 0x656c6946 // File

/*
 * File entity data format (v1):
 *
 *   - 4-byte version number of the metadata, little endian (0x00000001 for v1)
 *   - 12 bytes of metadata
 *   - the file data itself
 *
 * i.e. a 16-byte metadata header followed by the raw file data.  If the
 * restore code does not recognize the metadata version, it can still
 * interpret the file data itself correctly.
 *
 * file_metadata_v1:
 *
 *   - 4 byte version number === 0x00000001 (little endian)
 *   - 4-byte access mode (little-endian)
 *   - undefined (8 bytes)
 */

struct file_metadata_v1 {
    int version;
    int mode;
    int undefined_1;
    int undefined_2;
};

const static int CURRENT_METADATA_VERSION = 1;

#if 1
#define LOGP(f, x...)
#else
#if TEST_BACKUP_HELPERS
#define LOGP(f, x...) printf(f "\n", x)
#else
#define LOGP(x...) LOGD(x)
#endif
#endif

const static int ROUND_UP[4] = { 0, 3, 2, 1 };

static inline int
round_up(int n)
{
    return n + ROUND_UP[n % 4];
}

static int
read_snapshot_file(int fd, KeyedVector<String8,FileState>* snapshot)
{
    int bytesRead = 0;
    int amt;
    SnapshotHeader header;

    amt = read(fd, &header, sizeof(header));
    if (amt != sizeof(header)) {
        return errno;
    }
    bytesRead += amt;

    if (header.magic0 != MAGIC0 || header.magic1 != MAGIC1) {
        LOGW("read_snapshot_file header.magic0=0x%08x magic1=0x%08x", header.magic0, header.magic1);
        return 1;
    }

    for (int i=0; i<header.fileCount; i++) {
        FileState file;
        char filenameBuf[128];

        amt = read(fd, &file, sizeof(FileState));
        if (amt != sizeof(FileState)) {
            LOGW("read_snapshot_file FileState truncated/error with read at %d bytes\n", bytesRead);
            return 1;
        }
        bytesRead += amt;

        // filename is not NULL terminated, but it is padded
        int nameBufSize = round_up(file.nameLen);
        char* filename = nameBufSize <= (int)sizeof(filenameBuf)
                ? filenameBuf
                : (char*)malloc(nameBufSize);
        amt = read(fd, filename, nameBufSize);
        if (amt == nameBufSize) {
            snapshot->add(String8(filename, file.nameLen), file);
        }
        bytesRead += amt;
        if (filename != filenameBuf) {
            free(filename);
        }
        if (amt != nameBufSize) {
            LOGW("read_snapshot_file filename truncated/error with read at %d bytes\n", bytesRead);
            return 1;
        }
    }

    if (header.totalSize != bytesRead) {
        LOGW("read_snapshot_file length mismatch: header.totalSize=%d bytesRead=%d\n",
                header.totalSize, bytesRead);
        return 1;
    }

    return 0;
}

static int
write_snapshot_file(int fd, const KeyedVector<String8,FileRec>& snapshot)
{
    int fileCount = 0;
    int bytesWritten = sizeof(SnapshotHeader);
    // preflight size
    const int N = snapshot.size();
    for (int i=0; i<N; i++) {
        const FileRec& g = snapshot.valueAt(i);
        if (!g.deleted) {
            const String8& name = snapshot.keyAt(i);
            bytesWritten += sizeof(FileState) + round_up(name.length());
            fileCount++;
        }
    }

    LOGP("write_snapshot_file fd=%d\n", fd);

    int amt;
    SnapshotHeader header = { MAGIC0, fileCount, MAGIC1, bytesWritten };

    amt = write(fd, &header, sizeof(header));
    if (amt != sizeof(header)) {
        LOGW("write_snapshot_file error writing header %s", strerror(errno));
        return errno;
    }

    for (int i=0; i<N; i++) {
        FileRec r = snapshot.valueAt(i);
        if (!r.deleted) {
            const String8& name = snapshot.keyAt(i);
            int nameLen = r.s.nameLen = name.length();

            amt = write(fd, &r.s, sizeof(FileState));
            if (amt != sizeof(FileState)) {
                LOGW("write_snapshot_file error writing header %s", strerror(errno));
                return 1;
            }

            // filename is not NULL terminated, but it is padded
            amt = write(fd, name.string(), nameLen);
            if (amt != nameLen) {
                LOGW("write_snapshot_file error writing filename %s", strerror(errno));
                return 1;
            }
            int paddingLen = ROUND_UP[nameLen % 4];
            if (paddingLen != 0) {
                int padding = 0xabababab;
                amt = write(fd, &padding, paddingLen);
                if (amt != paddingLen) {
                    LOGW("write_snapshot_file error writing %d bytes of filename padding %s",
                            paddingLen, strerror(errno));
                    return 1;
                }
            }
        }
    }

    return 0;
}

static int
write_delete_file(BackupDataWriter* dataStream, const String8& key)
{
    LOGP("write_delete_file %s\n", key.string());
    return dataStream->WriteEntityHeader(key, -1);
}

static int
write_update_file(BackupDataWriter* dataStream, int fd, int mode, const String8& key,
        char const* realFilename)
{
    LOGP("write_update_file %s (%s) : mode 0%o\n", realFilename, key.string(), mode);

    const int bufsize = 4*1024;
    int err;
    int amt;
    int fileSize;
    int bytesLeft;
    file_metadata_v1 metadata;

    char* buf = (char*)malloc(bufsize);
    int crc = crc32(0L, Z_NULL, 0);


    fileSize = lseek(fd, 0, SEEK_END);
    lseek(fd, 0, SEEK_SET);

    if (sizeof(metadata) != 16) {
        LOGE("ERROR: metadata block is the wrong size!");
    }

    bytesLeft = fileSize + sizeof(metadata);
    err = dataStream->WriteEntityHeader(key, bytesLeft);
    if (err != 0) {
        free(buf);
        return err;
    }

    // store the file metadata first
    metadata.version = tolel(CURRENT_METADATA_VERSION);
    metadata.mode = tolel(mode);
    metadata.undefined_1 = metadata.undefined_2 = 0;
    err = dataStream->WriteEntityData(&metadata, sizeof(metadata));
    if (err != 0) {
        free(buf);
        return err;
    }
    bytesLeft -= sizeof(metadata); // bytesLeft should == fileSize now

    // now store the file content
    while ((amt = read(fd, buf, bufsize)) != 0 && bytesLeft > 0) {
        bytesLeft -= amt;
        if (bytesLeft < 0) {
            amt += bytesLeft; // Plus a negative is minus.  Don't write more than we promised.
        }
        err = dataStream->WriteEntityData(buf, amt);
        if (err != 0) {
            free(buf);
            return err;
        }
    }
    if (bytesLeft != 0) {
        if (bytesLeft > 0) {
            // Pad out the space we promised in the buffer.  We can't corrupt the buffer,
            // even though the data we're sending is probably bad.
            memset(buf, 0, bufsize);
            while (bytesLeft > 0) {
                amt = bytesLeft < bufsize ? bytesLeft : bufsize;
                bytesLeft -= amt;
                err = dataStream->WriteEntityData(buf, amt);
                if (err != 0) {
                    free(buf);
                    return err;
                }
            }
        }
        LOGE("write_update_file size mismatch for %s. expected=%d actual=%d."
                " You aren't doing proper locking!", realFilename, fileSize, fileSize-bytesLeft);
    }

    free(buf);
    return NO_ERROR;
}

static int
write_update_file(BackupDataWriter* dataStream, const String8& key, char const* realFilename)
{
    int err;
    struct stat st;

    err = stat(realFilename, &st);
    if (err < 0) {
        return errno;
    }

    int fd = open(realFilename, O_RDONLY);
    if (fd == -1) {
        return errno;
    }

    err = write_update_file(dataStream, fd, st.st_mode, key, realFilename);
    close(fd);
    return err;
}

static int
compute_crc32(int fd)
{
    const int bufsize = 4*1024;
    int amt;

    char* buf = (char*)malloc(bufsize);
    int crc = crc32(0L, Z_NULL, 0);

    lseek(fd, 0, SEEK_SET);

    while ((amt = read(fd, buf, bufsize)) != 0) {
        crc = crc32(crc, (Bytef*)buf, amt);
    }

    free(buf);
    return crc;
}

int
back_up_files(int oldSnapshotFD, BackupDataWriter* dataStream, int newSnapshotFD,
        char const* const* files, char const* const* keys, int fileCount)
{
    int err;
    KeyedVector<String8,FileState> oldSnapshot;
    KeyedVector<String8,FileRec> newSnapshot;

    if (oldSnapshotFD != -1) {
        err = read_snapshot_file(oldSnapshotFD, &oldSnapshot);
        if (err != 0) {
            // On an error, treat this as a full backup.
            oldSnapshot.clear();
        }
    }

    for (int i=0; i<fileCount; i++) {
        String8 key(keys[i]);
        FileRec r;
        char const* file = files[i];
        r.file = file;
        struct stat st;

        err = stat(file, &st);
        if (err != 0) {
            r.deleted = true;
        } else {
            r.deleted = false;
            r.s.modTime_sec = st.st_mtime;
            r.s.modTime_nsec = 0; // workaround sim breakage
            //r.s.modTime_nsec = st.st_mtime_nsec;
            r.s.mode = st.st_mode;
            r.s.size = st.st_size;
            // we compute the crc32 later down below, when we already have the file open.

            if (newSnapshot.indexOfKey(key) >= 0) {
                LOGP("back_up_files key already in use '%s'", key.string());
                return -1;
            }
        }
        newSnapshot.add(key, r);
    }

    int n = 0;
    int N = oldSnapshot.size();
    int m = 0;

    while (n<N && m<fileCount) {
        const String8& p = oldSnapshot.keyAt(n);
        const String8& q = newSnapshot.keyAt(m);
        FileRec& g = newSnapshot.editValueAt(m);
        int cmp = p.compare(q);
        if (g.deleted || cmp < 0) {
            // file removed
            LOGP("file removed: %s", p.string());
            g.deleted = true; // They didn't mention the file, but we noticed that it's gone.
            dataStream->WriteEntityHeader(p, -1);
            n++;
        }
        else if (cmp > 0) {
            // file added
            LOGP("file added: %s", g.file.string());
            write_update_file(dataStream, q, g.file.string());
            m++;
        }
        else {
            // both files exist, check them
            const FileState& f = oldSnapshot.valueAt(n);

            int fd = open(g.file.string(), O_RDONLY);
            if (fd < 0) {
                // We can't open the file.  Don't report it as a delete either.  Let the
                // server keep the old version.  Maybe they'll be able to deal with it
                // on restore.
                LOGP("Unable to open file %s - skipping", g.file.string());
            } else {
                g.s.crc32 = compute_crc32(fd);

                LOGP("%s", q.string());
                LOGP("  new: modTime=%d,%d mode=%04o size=%-3d crc32=0x%08x",
                        f.modTime_sec, f.modTime_nsec, f.mode, f.size, f.crc32);
                LOGP("  old: modTime=%d,%d mode=%04o size=%-3d crc32=0x%08x",
                        g.s.modTime_sec, g.s.modTime_nsec, g.s.mode, g.s.size, g.s.crc32);
                if (f.modTime_sec != g.s.modTime_sec || f.modTime_nsec != g.s.modTime_nsec
                        || f.mode != g.s.mode || f.size != g.s.size || f.crc32 != g.s.crc32) {
                    write_update_file(dataStream, fd, g.s.mode, p, g.file.string());
                }

                close(fd);
            }
            n++;
            m++;
        }
    }

    // these were deleted
    while (n<N) {
        dataStream->WriteEntityHeader(oldSnapshot.keyAt(n), -1);
        n++;
    }

    // these were added
    while (m<fileCount) {
        const String8& q = newSnapshot.keyAt(m);
        FileRec& g = newSnapshot.editValueAt(m);
        write_update_file(dataStream, q, g.file.string());
        m++;
    }

    err = write_snapshot_file(newSnapshotFD, newSnapshot);

    return 0;
}

#define RESTORE_BUF_SIZE (8*1024)

RestoreHelperBase::RestoreHelperBase()
{
    m_buf = malloc(RESTORE_BUF_SIZE);
    m_loggedUnknownMetadata = false;
}

RestoreHelperBase::~RestoreHelperBase()
{
    free(m_buf);
}

status_t
RestoreHelperBase::WriteFile(const String8& filename, BackupDataReader* in)
{
    ssize_t err;
    size_t dataSize;
    String8 key;
    int fd;
    void* buf = m_buf;
    ssize_t amt;
    int mode;
    int crc;
    struct stat st;
    FileRec r;

    err = in->ReadEntityHeader(&key, &dataSize);
    if (err != NO_ERROR) {
        return err;
    }

    // Get the metadata block off the head of the file entity and use that to
    // set up the output file
    file_metadata_v1 metadata;
    amt = in->ReadEntityData(&metadata, sizeof(metadata));
    if (amt != sizeof(metadata)) {
        LOGW("Could not read metadata for %s -- %ld / %s", filename.string(),
                (long)amt, strerror(errno));
        return EIO;
    }
    metadata.version = fromlel(metadata.version);
    metadata.mode = fromlel(metadata.mode);
    if (metadata.version > CURRENT_METADATA_VERSION) {
        if (!m_loggedUnknownMetadata) {
            m_loggedUnknownMetadata = true;
            LOGW("Restoring file with unsupported metadata version %d (currently %d)",
                    metadata.version, CURRENT_METADATA_VERSION);
        }
    }
    mode = metadata.mode;

    // Write the file and compute the crc
    crc = crc32(0L, Z_NULL, 0);
    fd = open(filename.string(), O_CREAT|O_RDWR|O_TRUNC, mode);
    if (fd == -1) {
        LOGW("Could not open file %s -- %s", filename.string(), strerror(errno));
        return errno;
    }
    
    while ((amt = in->ReadEntityData(buf, RESTORE_BUF_SIZE)) > 0) {
        err = write(fd, buf, amt);
        if (err != amt) {
            close(fd);
            LOGW("Error '%s' writing '%s'", strerror(errno), filename.string());
            return errno;
        }
        crc = crc32(crc, (Bytef*)buf, amt);
    }

    close(fd);

    // Record for the snapshot
    err = stat(filename.string(), &st);
    if (err != 0) {
        LOGW("Error stating file that we just created %s", filename.string());
        return errno;
    }

    r.file = filename;
    r.deleted = false;
    r.s.modTime_sec = st.st_mtime;
    r.s.modTime_nsec = 0; // workaround sim breakage
    //r.s.modTime_nsec = st.st_mtime_nsec;
    r.s.mode = st.st_mode;
    r.s.size = st.st_size;
    r.s.crc32 = crc;

    m_files.add(key, r);

    return NO_ERROR;
}

status_t
RestoreHelperBase::WriteSnapshot(int fd)
{
    return write_snapshot_file(fd, m_files);;
}

#if TEST_BACKUP_HELPERS

#define SCRATCH_DIR "/data/backup_helper_test/"

static int
write_text_file(const char* path, const char* data)
{
    int amt;
    int fd;
    int len;

    fd = creat(path, 0666);
    if (fd == -1) {
        fprintf(stderr, "creat %s failed\n", path);
        return errno;
    }

    len = strlen(data);
    amt = write(fd, data, len);
    if (amt != len) {
        fprintf(stderr, "error (%s) writing to file %s\n", strerror(errno), path);
        return errno;
    }

    close(fd);

    return 0;
}

static int
compare_file(const char* path, const unsigned char* data, int len)
{
    int fd;
    int amt;

    fd = open(path, O_RDONLY);
    if (fd == -1) {
        fprintf(stderr, "compare_file error (%s) opening %s\n", strerror(errno), path);
        return errno;
    }

    unsigned char* contents = (unsigned char*)malloc(len);
    if (contents == NULL) {
        fprintf(stderr, "malloc(%d) failed\n", len);
        return ENOMEM;
    }

    bool sizesMatch = true;
    amt = lseek(fd, 0, SEEK_END);
    if (amt != len) {
        fprintf(stderr, "compare_file file length should be %d, was %d\n", len, amt);
        sizesMatch = false;
    }
    lseek(fd, 0, SEEK_SET);

    int readLen = amt < len ? amt : len;
    amt = read(fd, contents, readLen);
    if (amt != readLen) {
        fprintf(stderr, "compare_file read expected %d bytes but got %d\n", len, amt);
    }

    bool contentsMatch = true;
    for (int i=0; i<readLen; i++) {
        if (data[i] != contents[i]) {
            if (contentsMatch) {
                fprintf(stderr, "compare_file contents are different: (index, expected, actual)\n");
                contentsMatch = false;
            }
            fprintf(stderr, "  [%-2d] %02x %02x\n", i, data[i], contents[i]);
        }
    }

    free(contents);
    return contentsMatch && sizesMatch ? 0 : 1;
}

int
backup_helper_test_empty()
{
    int err;
    int fd;
    KeyedVector<String8,FileRec> snapshot;
    const char* filename = SCRATCH_DIR "backup_helper_test_empty.snap";

    system("rm -r " SCRATCH_DIR);
    mkdir(SCRATCH_DIR, 0777);

    // write
    fd = creat(filename, 0666);
    if (fd == -1) {
        fprintf(stderr, "error creating %s\n", filename);
        return 1;
    }

    err = write_snapshot_file(fd, snapshot);

    close(fd);

    if (err != 0) {
        fprintf(stderr, "write_snapshot_file reported error %d (%s)\n", err, strerror(err));
        return err;
    }

    static const unsigned char correct_data[] = {
        0x53, 0x6e, 0x61, 0x70,  0x00, 0x00, 0x00, 0x00,
        0x46, 0x69, 0x6c, 0x65,  0x10, 0x00, 0x00, 0x00
    };

    err = compare_file(filename, correct_data, sizeof(correct_data));
    if (err != 0) {
        return err;
    }

    // read
    fd = open(filename, O_RDONLY);
    if (fd == -1) {
        fprintf(stderr, "error opening for read %s\n", filename);
        return 1;
    }

    KeyedVector<String8,FileState> readSnapshot;
    err = read_snapshot_file(fd, &readSnapshot);
    if (err != 0) {
        fprintf(stderr, "read_snapshot_file failed %d\n", err);
        return err;
    }

    if (readSnapshot.size() != 0) {
        fprintf(stderr, "readSnapshot should be length 0\n");
        return 1;
    }

    return 0;
}

int
backup_helper_test_four()
{
    int err;
    int fd;
    KeyedVector<String8,FileRec> snapshot;
    const char* filename = SCRATCH_DIR "backup_helper_test_four.snap";

    system("rm -r " SCRATCH_DIR);
    mkdir(SCRATCH_DIR, 0777);

    // write
    fd = creat(filename, 0666);
    if (fd == -1) {
        fprintf(stderr, "error opening %s\n", filename);
        return 1;
    }

    String8 filenames[4];
    FileState states[4];
    FileRec r;
    r.deleted = false;

    states[0].modTime_sec = 0xfedcba98;
    states[0].modTime_nsec = 0xdeadbeef;
    states[0].mode = 0777; // decimal 511, hex 0x000001ff
    states[0].size = 0xababbcbc;
    states[0].crc32 = 0x12345678;
    states[0].nameLen = -12;
    r.s = states[0];
    filenames[0] = String8("bytes_of_padding");
    snapshot.add(filenames[0], r);

    states[1].modTime_sec = 0x93400031;
    states[1].modTime_nsec = 0xdeadbeef;
    states[1].mode = 0666; // decimal 438, hex 0x000001b6
    states[1].size = 0x88557766;
    states[1].crc32 = 0x22334422;
    states[1].nameLen = -1;
    r.s = states[1];
    filenames[1] = String8("bytes_of_padding3");
    snapshot.add(filenames[1], r);

    states[2].modTime_sec = 0x33221144;
    states[2].modTime_nsec = 0xdeadbeef;
    states[2].mode = 0744; // decimal 484, hex 0x000001e4
    states[2].size = 0x11223344;
    states[2].crc32 = 0x01122334;
    states[2].nameLen = 0;
    r.s = states[2];
    filenames[2] = String8("bytes_of_padding_2");
    snapshot.add(filenames[2], r);

    states[3].modTime_sec = 0x33221144;
    states[3].modTime_nsec = 0xdeadbeef;
    states[3].mode = 0755; // decimal 493, hex 0x000001ed
    states[3].size = 0x11223344;
    states[3].crc32 = 0x01122334;
    states[3].nameLen = 0;
    r.s = states[3];
    filenames[3] = String8("bytes_of_padding__1");
    snapshot.add(filenames[3], r);

    err = write_snapshot_file(fd, snapshot);

    close(fd);

    if (err != 0) {
        fprintf(stderr, "write_snapshot_file reported error %d (%s)\n", err, strerror(err));
        return err;
    }

    static const unsigned char correct_data[] = {
        // header
        0x53, 0x6e, 0x61, 0x70,  0x04, 0x00, 0x00, 0x00,
        0x46, 0x69, 0x6c, 0x65,  0xbc, 0x00, 0x00, 0x00,

        // bytes_of_padding
        0x98, 0xba, 0xdc, 0xfe,  0xef, 0xbe, 0xad, 0xde,
        0xff, 0x01, 0x00, 0x00,  0xbc, 0xbc, 0xab, 0xab,
        0x78, 0x56, 0x34, 0x12,  0x10, 0x00, 0x00, 0x00,
        0x62, 0x79, 0x74, 0x65,  0x73, 0x5f, 0x6f, 0x66,
        0x5f, 0x70, 0x61, 0x64,  0x64, 0x69, 0x6e, 0x67,

        // bytes_of_padding3
        0x31, 0x00, 0x40, 0x93,  0xef, 0xbe, 0xad, 0xde,
        0xb6, 0x01, 0x00, 0x00,  0x66, 0x77, 0x55, 0x88,
        0x22, 0x44, 0x33, 0x22,  0x11, 0x00, 0x00, 0x00,
        0x62, 0x79, 0x74, 0x65,  0x73, 0x5f, 0x6f, 0x66,
        0x5f, 0x70, 0x61, 0x64,  0x64, 0x69, 0x6e, 0x67,
        0x33, 0xab, 0xab, 0xab,

        // bytes of padding2
        0x44, 0x11, 0x22, 0x33,  0xef, 0xbe, 0xad, 0xde,
        0xe4, 0x01, 0x00, 0x00,  0x44, 0x33, 0x22, 0x11,
        0x34, 0x23, 0x12, 0x01,  0x12, 0x00, 0x00, 0x00,
        0x62, 0x79, 0x74, 0x65,  0x73, 0x5f, 0x6f, 0x66,
        0x5f, 0x70, 0x61, 0x64,  0x64, 0x69, 0x6e, 0x67,
        0x5f, 0x32, 0xab, 0xab,

        // bytes of padding3
        0x44, 0x11, 0x22, 0x33,  0xef, 0xbe, 0xad, 0xde,
        0xed, 0x01, 0x00, 0x00,  0x44, 0x33, 0x22, 0x11,
        0x34, 0x23, 0x12, 0x01,  0x13, 0x00, 0x00, 0x00,
        0x62, 0x79, 0x74, 0x65,  0x73, 0x5f, 0x6f, 0x66,
        0x5f, 0x70, 0x61, 0x64,  0x64, 0x69, 0x6e, 0x67,
        0x5f, 0x5f, 0x31, 0xab
    };

    err = compare_file(filename, correct_data, sizeof(correct_data));
    if (err != 0) {
        return err;
    }

    // read
    fd = open(filename, O_RDONLY);
    if (fd == -1) {
        fprintf(stderr, "error opening for read %s\n", filename);
        return 1;
    }


    KeyedVector<String8,FileState> readSnapshot;
    err = read_snapshot_file(fd, &readSnapshot);
    if (err != 0) {
        fprintf(stderr, "read_snapshot_file failed %d\n", err);
        return err;
    }

    if (readSnapshot.size() != 4) {
        fprintf(stderr, "readSnapshot should be length 4 is %d\n", readSnapshot.size());
        return 1;
    }

    bool matched = true;
    for (size_t i=0; i<readSnapshot.size(); i++) {
        const String8& name = readSnapshot.keyAt(i);
        const FileState state = readSnapshot.valueAt(i);

        if (name != filenames[i] || states[i].modTime_sec != state.modTime_sec
                || states[i].modTime_nsec != state.modTime_nsec || states[i].mode != state.mode
                || states[i].size != state.size || states[i].crc32 != states[i].crc32) {
            fprintf(stderr, "state %d expected={%d/%d, 0x%08x, %04o, 0x%08x, %3d} '%s'\n"
                            "          actual={%d/%d, 0x%08x, %04o, 0x%08x, %3d} '%s'\n", i,
                    states[i].modTime_sec, states[i].modTime_nsec, states[i].mode, states[i].size,
                    states[i].crc32, name.length(), filenames[i].string(),
                    state.modTime_sec, state.modTime_nsec, state.mode, state.size, state.crc32,
                    state.nameLen, name.string());
            matched = false;
        }
    }

    return matched ? 0 : 1;
}

// hexdump -v -e '"    " 8/1 " 0x%02x," "\n"' data_writer.data
const unsigned char DATA_GOLDEN_FILE[] = {
     0x44, 0x61, 0x74, 0x61, 0x0b, 0x00, 0x00, 0x00,
     0x0c, 0x00, 0x00, 0x00, 0x6e, 0x6f, 0x5f, 0x70,
     0x61, 0x64, 0x64, 0x69, 0x6e, 0x67, 0x5f, 0x00,
     0x6e, 0x6f, 0x5f, 0x70, 0x61, 0x64, 0x64, 0x69,
     0x6e, 0x67, 0x5f, 0x00, 0x44, 0x61, 0x74, 0x61,
     0x0c, 0x00, 0x00, 0x00, 0x0d, 0x00, 0x00, 0x00,
     0x70, 0x61, 0x64, 0x64, 0x65, 0x64, 0x5f, 0x74,
     0x6f, 0x5f, 0x5f, 0x33, 0x00, 0xbc, 0xbc, 0xbc,
     0x70, 0x61, 0x64, 0x64, 0x65, 0x64, 0x5f, 0x74,
     0x6f, 0x5f, 0x5f, 0x33, 0x00, 0xbc, 0xbc, 0xbc,
     0x44, 0x61, 0x74, 0x61, 0x0d, 0x00, 0x00, 0x00,
     0x0e, 0x00, 0x00, 0x00, 0x70, 0x61, 0x64, 0x64,
     0x65, 0x64, 0x5f, 0x74, 0x6f, 0x5f, 0x32, 0x5f,
     0x5f, 0x00, 0xbc, 0xbc, 0x70, 0x61, 0x64, 0x64,
     0x65, 0x64, 0x5f, 0x74, 0x6f, 0x5f, 0x32, 0x5f,
     0x5f, 0x00, 0xbc, 0xbc, 0x44, 0x61, 0x74, 0x61,
     0x0a, 0x00, 0x00, 0x00, 0x0b, 0x00, 0x00, 0x00,
     0x70, 0x61, 0x64, 0x64, 0x65, 0x64, 0x5f, 0x74,
     0x6f, 0x31, 0x00, 0xbc, 0x70, 0x61, 0x64, 0x64,
     0x65, 0x64, 0x5f, 0x74, 0x6f, 0x31, 0x00

};
const int DATA_GOLDEN_FILE_SIZE = sizeof(DATA_GOLDEN_FILE);

static int
test_write_header_and_entity(BackupDataWriter& writer, const char* str)
{
    int err;
    String8 text(str);

    err = writer.WriteEntityHeader(text, text.length()+1);
    if (err != 0) {
        fprintf(stderr, "WriteEntityHeader failed with %s\n", strerror(err));
        return err;
    }

    err = writer.WriteEntityData(text.string(), text.length()+1);
    if (err != 0) {
        fprintf(stderr, "write failed for data '%s'\n", text.string());
        return errno;
    }

    return err;
}

int
backup_helper_test_data_writer()
{
    int err;
    int fd;
    const char* filename = SCRATCH_DIR "data_writer.data";

    system("rm -r " SCRATCH_DIR);
    mkdir(SCRATCH_DIR, 0777);
    mkdir(SCRATCH_DIR "data", 0777);

    fd = creat(filename, 0666);
    if (fd == -1) {
        fprintf(stderr, "error creating: %s\n", strerror(errno));
        return errno;
    }

    BackupDataWriter writer(fd);

    err = 0;
    err |= test_write_header_and_entity(writer, "no_padding_");
    err |= test_write_header_and_entity(writer, "padded_to__3");
    err |= test_write_header_and_entity(writer, "padded_to_2__");
    err |= test_write_header_and_entity(writer, "padded_to1");

    close(fd);

    err = compare_file(filename, DATA_GOLDEN_FILE, DATA_GOLDEN_FILE_SIZE);
    if (err != 0) {
        return err;
    }

    return err;
}

int
test_read_header_and_entity(BackupDataReader& reader, const char* str)
{
    int err;
    int bufSize = strlen(str)+1;
    char* buf = (char*)malloc(bufSize);
    String8 string;
    int cookie = 0x11111111;
    size_t actualSize;
    bool done;
    int type;
    ssize_t nRead;

    // printf("\n\n---------- test_read_header_and_entity -- %s\n\n", str);

    err = reader.ReadNextHeader(&done, &type);
    if (done) {
        fprintf(stderr, "should not be done yet\n");
        goto finished;
    }
    if (err != 0) {
        fprintf(stderr, "ReadNextHeader (for app header) failed with %s\n", strerror(err));
        goto finished;
    }
    if (type != BACKUP_HEADER_ENTITY_V1) {
        err = EINVAL;
        fprintf(stderr, "type=0x%08x expected 0x%08x\n", type, BACKUP_HEADER_ENTITY_V1);
    }

    err = reader.ReadEntityHeader(&string, &actualSize);
    if (err != 0) {
        fprintf(stderr, "ReadEntityHeader failed with %s\n", strerror(err));
        goto finished;
    }
    if (string != str) {
        fprintf(stderr, "ReadEntityHeader expected key '%s' got '%s'\n", str, string.string());
        err = EINVAL;
        goto finished;
    }
    if ((int)actualSize != bufSize) {
        fprintf(stderr, "ReadEntityHeader expected dataSize 0x%08x got 0x%08x\n", bufSize,
                actualSize);
        err = EINVAL;
        goto finished;
    }

    nRead = reader.ReadEntityData(buf, bufSize);
    if (nRead < 0) {
        err = reader.Status();
        fprintf(stderr, "ReadEntityData failed with %s\n", strerror(err));
        goto finished;
    }

    if (0 != memcmp(buf, str, bufSize)) {
        fprintf(stderr, "ReadEntityData expected '%s' but got something starting with "
                "%02x %02x %02x %02x  '%c%c%c%c'\n", str, buf[0], buf[1], buf[2], buf[3],
                buf[0], buf[1], buf[2], buf[3]);
        err = EINVAL;
        goto finished;
    }

    // The next read will confirm whether it got the right amount of data.

finished:
    if (err != NO_ERROR) {
        fprintf(stderr, "test_read_header_and_entity failed with %s\n", strerror(err));
    }
    free(buf);
    return err;
}

int
backup_helper_test_data_reader()
{
    int err;
    int fd;
    const char* filename = SCRATCH_DIR "data_reader.data";

    system("rm -r " SCRATCH_DIR);
    mkdir(SCRATCH_DIR, 0777);
    mkdir(SCRATCH_DIR "data", 0777);

    fd = creat(filename, 0666);
    if (fd == -1) {
        fprintf(stderr, "error creating: %s\n", strerror(errno));
        return errno;
    }

    err = write(fd, DATA_GOLDEN_FILE, DATA_GOLDEN_FILE_SIZE);
    if (err != DATA_GOLDEN_FILE_SIZE) {
        fprintf(stderr, "Error \"%s\" writing golden file %s\n", strerror(errno), filename);
        return errno;
    }

    close(fd);

    fd = open(filename, O_RDONLY);
    if (fd == -1) {
        fprintf(stderr, "Error \"%s\" opening golden file %s for read\n", strerror(errno),
                filename);
        return errno;
    }

    {
        BackupDataReader reader(fd);

        err = 0;

        if (err == NO_ERROR) {
            err = test_read_header_and_entity(reader, "no_padding_");
        }

        if (err == NO_ERROR) {
            err = test_read_header_and_entity(reader, "padded_to__3");
        }

        if (err == NO_ERROR) {
            err = test_read_header_and_entity(reader, "padded_to_2__");
        }

        if (err == NO_ERROR) {
            err = test_read_header_and_entity(reader, "padded_to1");
        }
    }

    close(fd);

    return err;
}

static int
get_mod_time(const char* filename, struct timeval times[2])
{
    int err;
    struct stat64 st;
    err = stat64(filename, &st);
    if (err != 0) {
        fprintf(stderr, "stat '%s' failed: %s\n", filename, strerror(errno));
        return errno;
    }
    times[0].tv_sec = st.st_atime;
    times[1].tv_sec = st.st_mtime;

    // If st_atime is a macro then struct stat64 uses struct timespec
    // to store the access and modif time values and typically
    // st_*time_nsec is not defined. In glibc, this is controlled by
    // __USE_MISC.
#ifdef __USE_MISC
#if !defined(st_atime) || defined(st_atime_nsec)
#error "Check if this __USE_MISC conditional is still needed."
#endif
    times[0].tv_usec = st.st_atim.tv_nsec / 1000;
    times[1].tv_usec = st.st_mtim.tv_nsec / 1000;
#else
    times[0].tv_usec = st.st_atime_nsec / 1000;
    times[1].tv_usec = st.st_mtime_nsec / 1000;
#endif

    return 0;
}

int
backup_helper_test_files()
{
    int err;
    int oldSnapshotFD;
    int dataStreamFD;
    int newSnapshotFD;

    system("rm -r " SCRATCH_DIR);
    mkdir(SCRATCH_DIR, 0777);
    mkdir(SCRATCH_DIR "data", 0777);

    write_text_file(SCRATCH_DIR "data/b", "b\nbb\n");
    write_text_file(SCRATCH_DIR "data/c", "c\ncc\n");
    write_text_file(SCRATCH_DIR "data/d", "d\ndd\n");
    write_text_file(SCRATCH_DIR "data/e", "e\nee\n");
    write_text_file(SCRATCH_DIR "data/f", "f\nff\n");
    write_text_file(SCRATCH_DIR "data/h", "h\nhh\n");

    char const* files_before[] = {
        SCRATCH_DIR "data/b",
        SCRATCH_DIR "data/c",
        SCRATCH_DIR "data/d",
        SCRATCH_DIR "data/e",
        SCRATCH_DIR "data/f"
    };

    char const* keys_before[] = {
        "data/b",
        "data/c",
        "data/d",
        "data/e",
        "data/f"
    };

    dataStreamFD = creat(SCRATCH_DIR "1.data", 0666);
    if (dataStreamFD == -1) {
        fprintf(stderr, "error creating: %s\n", strerror(errno));
        return errno;
    }

    newSnapshotFD = creat(SCRATCH_DIR "before.snap", 0666);
    if (newSnapshotFD == -1) {
        fprintf(stderr, "error creating: %s\n", strerror(errno));
        return errno;
    }

    {
        BackupDataWriter dataStream(dataStreamFD);

        err = back_up_files(-1, &dataStream, newSnapshotFD, files_before, keys_before, 5);
        if (err != 0) {
            return err;
        }
    }

    close(dataStreamFD);
    close(newSnapshotFD);

    sleep(3);

    struct timeval d_times[2];
    struct timeval e_times[2];

    err = get_mod_time(SCRATCH_DIR "data/d", d_times);
    err |= get_mod_time(SCRATCH_DIR "data/e", e_times);
    if (err != 0) {
        return err;
    }

    write_text_file(SCRATCH_DIR "data/a", "a\naa\n");
    unlink(SCRATCH_DIR "data/c");
    write_text_file(SCRATCH_DIR "data/c", "c\ncc\n");
    write_text_file(SCRATCH_DIR "data/d", "dd\ndd\n");
    utimes(SCRATCH_DIR "data/d", d_times);
    write_text_file(SCRATCH_DIR "data/e", "z\nzz\n");
    utimes(SCRATCH_DIR "data/e", e_times);
    write_text_file(SCRATCH_DIR "data/g", "g\ngg\n");
    unlink(SCRATCH_DIR "data/f");

    char const* files_after[] = {
        SCRATCH_DIR "data/a", // added
        SCRATCH_DIR "data/b", // same
        SCRATCH_DIR "data/c", // different mod time
        SCRATCH_DIR "data/d", // different size (same mod time)
        SCRATCH_DIR "data/e", // different contents (same mod time, same size)
        SCRATCH_DIR "data/g"  // added
    };

    char const* keys_after[] = {
        "data/a", // added
        "data/b", // same
        "data/c", // different mod time
        "data/d", // different size (same mod time)
        "data/e", // different contents (same mod time, same size)
        "data/g"  // added
    };

    oldSnapshotFD = open(SCRATCH_DIR "before.snap", O_RDONLY);
    if (oldSnapshotFD == -1) {
        fprintf(stderr, "error opening: %s\n", strerror(errno));
        return errno;
    }

    dataStreamFD = creat(SCRATCH_DIR "2.data", 0666);
    if (dataStreamFD == -1) {
        fprintf(stderr, "error creating: %s\n", strerror(errno));
        return errno;
    }

    newSnapshotFD = creat(SCRATCH_DIR "after.snap", 0666);
    if (newSnapshotFD == -1) {
        fprintf(stderr, "error creating: %s\n", strerror(errno));
        return errno;
    }

    {
        BackupDataWriter dataStream(dataStreamFD);

        err = back_up_files(oldSnapshotFD, &dataStream, newSnapshotFD, files_after, keys_after, 6);
        if (err != 0) {
            return err;
        }
}

    close(oldSnapshotFD);
    close(dataStreamFD);
    close(newSnapshotFD);

    return 0;
}

int
backup_helper_test_null_base()
{
    int err;
    int oldSnapshotFD;
    int dataStreamFD;
    int newSnapshotFD;

    system("rm -r " SCRATCH_DIR);
    mkdir(SCRATCH_DIR, 0777);
    mkdir(SCRATCH_DIR "data", 0777);

    write_text_file(SCRATCH_DIR "data/a", "a\naa\n");

    char const* files[] = {
        SCRATCH_DIR "data/a",
    };

    char const* keys[] = {
        "a",
    };

    dataStreamFD = creat(SCRATCH_DIR "null_base.data", 0666);
    if (dataStreamFD == -1) {
        fprintf(stderr, "error creating: %s\n", strerror(errno));
        return errno;
    }

    newSnapshotFD = creat(SCRATCH_DIR "null_base.snap", 0666);
    if (newSnapshotFD == -1) {
        fprintf(stderr, "error creating: %s\n", strerror(errno));
        return errno;
    }

    {
        BackupDataWriter dataStream(dataStreamFD);

        err = back_up_files(-1, &dataStream, newSnapshotFD, files, keys, 1);
        if (err != 0) {
            return err;
        }
    }

    close(dataStreamFD);
    close(newSnapshotFD);

    return 0;
}

int
backup_helper_test_missing_file()
{
    int err;
    int oldSnapshotFD;
    int dataStreamFD;
    int newSnapshotFD;

    system("rm -r " SCRATCH_DIR);
    mkdir(SCRATCH_DIR, 0777);
    mkdir(SCRATCH_DIR "data", 0777);

    write_text_file(SCRATCH_DIR "data/b", "b\nbb\n");

    char const* files[] = {
        SCRATCH_DIR "data/a",
        SCRATCH_DIR "data/b",
        SCRATCH_DIR "data/c",
    };

    char const* keys[] = {
        "a",
        "b",
        "c",
    };

    dataStreamFD = creat(SCRATCH_DIR "null_base.data", 0666);
    if (dataStreamFD == -1) {
        fprintf(stderr, "error creating: %s\n", strerror(errno));
        return errno;
    }

    newSnapshotFD = creat(SCRATCH_DIR "null_base.snap", 0666);
    if (newSnapshotFD == -1) {
        fprintf(stderr, "error creating: %s\n", strerror(errno));
        return errno;
    }

    {
        BackupDataWriter dataStream(dataStreamFD);

        err = back_up_files(-1, &dataStream, newSnapshotFD, files, keys, 1);
        if (err != 0) {
            return err;
        }
    }

    close(dataStreamFD);
    close(newSnapshotFD);

    return 0;
}


#endif // TEST_BACKUP_HELPERS

}
