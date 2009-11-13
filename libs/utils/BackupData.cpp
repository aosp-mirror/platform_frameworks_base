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

#define LOG_TAG "backup_data"

#include <utils/BackupHelpers.h>
#include <utils/ByteOrder.h>

#include <stdio.h>
#include <unistd.h>

#include <cutils/log.h>

namespace android {

/*
 * File Format (v1):
 *
 * All ints are stored little-endian.
 *
 *  - An app_header_v1 struct.
 *  - The name of the package, utf-8, null terminated, padded to 4-byte boundary.
 *  - A sequence of zero or more key/value paires (entities), each with
 *      - A entity_header_v1 struct
 *      - The key, utf-8, null terminated, padded to 4-byte boundary.
 *      - The value, padded to 4 byte boundary
 */

const static int ROUND_UP[4] = { 0, 3, 2, 1 };

static inline size_t
round_up(size_t n)
{
    return n + ROUND_UP[n % 4];
}

static inline size_t
padding_extra(size_t n)
{
    return ROUND_UP[n % 4];
}

BackupDataWriter::BackupDataWriter(int fd)
    :m_fd(fd),
     m_status(NO_ERROR),
     m_pos(0),
     m_entityCount(0)
{
}

BackupDataWriter::~BackupDataWriter()
{
}

// Pad out anything they've previously written to the next 4 byte boundary.
status_t
BackupDataWriter::write_padding_for(int n)
{
    ssize_t amt;
    ssize_t paddingSize;

    paddingSize = padding_extra(n);
    if (paddingSize > 0) {
        uint32_t padding = 0xbcbcbcbc;
        amt = write(m_fd, &padding, paddingSize);
        if (amt != paddingSize) {
            m_status = errno;
            return m_status;
        }
        m_pos += amt;
    }
    return NO_ERROR;
}

status_t
BackupDataWriter::WriteEntityHeader(const String8& key, size_t dataSize)
{
    if (m_status != NO_ERROR) {
        return m_status;
    }

    ssize_t amt;

    amt = write_padding_for(m_pos);
    if (amt != 0) {
        return amt;
    }

    String8 k;
    if (m_keyPrefix.length() > 0) {
        k = m_keyPrefix;
        k += ":";
        k += key;
    } else {
        k = key;
    }
    if (false) {
        LOGD("Writing entity: prefix='%s' key='%s' dataSize=%d", m_keyPrefix.string(), key.string(),
                dataSize);
    }

    entity_header_v1 header;
    ssize_t keyLen;

    keyLen = k.length();

    header.type = tolel(BACKUP_HEADER_ENTITY_V1);
    header.keyLen = tolel(keyLen);
    header.dataSize = tolel(dataSize);

    amt = write(m_fd, &header, sizeof(entity_header_v1));
    if (amt != sizeof(entity_header_v1)) {
        m_status = errno;
        return m_status;
    }
    m_pos += amt;

    amt = write(m_fd, k.string(), keyLen+1);
    if (amt != keyLen+1) {
        m_status = errno;
        return m_status;
    }
    m_pos += amt;

    amt = write_padding_for(keyLen+1);

    m_entityCount++;

    return amt;
}

status_t
BackupDataWriter::WriteEntityData(const void* data, size_t size)
{
    if (m_status != NO_ERROR) {
        return m_status;
    }

    // We don't write padding here, because they're allowed to call this several
    // times with smaller buffers.  We write it at the end of WriteEntityHeader
    // instead.
    ssize_t amt = write(m_fd, data, size);
    if (amt != (ssize_t)size) {
        m_status = errno;
        return m_status;
    }
    m_pos += amt;
    return NO_ERROR;
}

void
BackupDataWriter::SetKeyPrefix(const String8& keyPrefix)
{
    m_keyPrefix = keyPrefix;
}


BackupDataReader::BackupDataReader(int fd)
    :m_fd(fd),
     m_done(false),
     m_status(NO_ERROR),
     m_pos(0),
     m_entityCount(0)
{
    memset(&m_header, 0, sizeof(m_header));
}

BackupDataReader::~BackupDataReader()
{
}

status_t
BackupDataReader::Status()
{
    return m_status;
}

#define CHECK_SIZE(actual, expected) \
    do { \
        if ((actual) != (expected)) { \
            if ((actual) == 0) { \
                m_status = EIO; \
                m_done = true; \
            } else { \
                m_status = errno; \
                LOGD("CHECK_SIZE(a=%ld e=%ld) failed at line %d m_status='%s'", \
                    long(actual), long(expected), __LINE__, strerror(m_status)); \
            } \
            return m_status; \
        } \
    } while(0)
#define SKIP_PADDING() \
    do { \
        status_t err = skip_padding(); \
        if (err != NO_ERROR) { \
            LOGD("SKIP_PADDING FAILED at line %d", __LINE__); \
            m_status = err; \
            return err; \
        } \
    } while(0)

status_t
BackupDataReader::ReadNextHeader(bool* done, int* type)
{
    *done = m_done;
    if (m_status != NO_ERROR) {
        return m_status;
    }

    int amt;

    amt = skip_padding();
    if (amt == EIO) {
        *done = m_done = true;
        return NO_ERROR;
    }
    else if (amt != NO_ERROR) {
        return amt;
    }
    amt = read(m_fd, &m_header, sizeof(m_header));
    *done = m_done = (amt == 0);
    if (*done) {
        return NO_ERROR;
    }
    CHECK_SIZE(amt, sizeof(m_header));
    m_pos += sizeof(m_header);
    if (type) {
        *type = m_header.type;
    }

    // validate and fix up the fields.
    m_header.type = fromlel(m_header.type);
    switch (m_header.type)
    {
        case BACKUP_HEADER_ENTITY_V1:
        {
            m_header.entity.keyLen = fromlel(m_header.entity.keyLen);
            if (m_header.entity.keyLen <= 0) {
                LOGD("Entity header at %d has keyLen<=0: 0x%08x\n", (int)m_pos,
                        (int)m_header.entity.keyLen);
                m_status = EINVAL;
            }
            m_header.entity.dataSize = fromlel(m_header.entity.dataSize);
            m_entityCount++;

            // read the rest of the header (filename)
            size_t size = m_header.entity.keyLen;
            char* buf = m_key.lockBuffer(size);
            if (buf == NULL) {
                m_status = ENOMEM;
                return m_status;
            }
            int amt = read(m_fd, buf, size+1);
            CHECK_SIZE(amt, (int)size+1);
            m_key.unlockBuffer(size);
            m_pos += size+1;
            SKIP_PADDING();
            m_dataEndPos = m_pos + m_header.entity.dataSize;

            break;
        }
        default:
            LOGD("Chunk header at %d has invalid type: 0x%08x", (int)m_pos, (int)m_header.type);
            m_status = EINVAL;
    }
    
    return m_status;
}

bool
BackupDataReader::HasEntities()
{
    return m_status == NO_ERROR && m_header.type == BACKUP_HEADER_ENTITY_V1;
}

status_t
BackupDataReader::ReadEntityHeader(String8* key, size_t* dataSize)
{
    if (m_status != NO_ERROR) {
        return m_status;
    }
    if (m_header.type != BACKUP_HEADER_ENTITY_V1) {
        return EINVAL;
    }
    *key = m_key;
    *dataSize = m_header.entity.dataSize;
    return NO_ERROR;
}

status_t
BackupDataReader::SkipEntityData()
{
    if (m_status != NO_ERROR) {
        return m_status;
    }
    if (m_header.type != BACKUP_HEADER_ENTITY_V1) {
        return EINVAL;
    }
    if (m_header.entity.dataSize > 0) {
        int pos = lseek(m_fd, m_dataEndPos, SEEK_SET);
        if (pos == -1) {
            return errno;
        }
    }
    SKIP_PADDING();
    return NO_ERROR;
}

ssize_t
BackupDataReader::ReadEntityData(void* data, size_t size)
{
    if (m_status != NO_ERROR) {
        return -1;
    }
    int remaining = m_dataEndPos - m_pos;
    //LOGD("ReadEntityData size=%d m_pos=0x%x m_dataEndPos=0x%x remaining=%d\n",
    //        size, m_pos, m_dataEndPos, remaining);
    if (remaining <= 0) {
        return 0;
    }
    if (((int)size) > remaining) {
        size = remaining;
    }
    //LOGD("   reading %d bytes", size);
    int amt = read(m_fd, data, size);
    if (amt < 0) {
        m_status = errno;
        return -1;
    }
    if (amt == 0) {
        m_status = EIO;
        m_done = true;
    }
    m_pos += amt;
    return amt;
}

status_t
BackupDataReader::skip_padding()
{
    ssize_t amt;
    ssize_t paddingSize;

    paddingSize = padding_extra(m_pos);
    if (paddingSize > 0) {
        uint32_t padding;
        amt = read(m_fd, &padding, paddingSize);
        CHECK_SIZE(amt, paddingSize);
        m_pos += amt;
    }
    return NO_ERROR;
}


} // namespace android
