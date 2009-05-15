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

#include <utils/backup_helpers.h>
#include <utils/ByteOrder.h>

#include <stdio.h>
#include <unistd.h>

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

#define APP_MAGIC_V1 0x31707041 // App1 (little endian)
#define ENTITY_MAGIC_V1 0x61746144 // Data (little endian)
#define FOOTER_MAGIC_V1 0x746f6f46 // Foot (little endian)

typedef struct {
    int type; // == APP_MAGIC_V1
    int packageLen; // length of the name of the package that follows, not including the null.
} app_header_v1;

typedef struct {
    int type; // ENTITY_MAGIC_V1
    int keyLen; // length of the key name, not including the null terminator
    int dataSize; // size of the data, not including the padding
} entity_header_v1;

typedef struct {
    int type; // FOOTER_MAGIC_V1
    int entityCount; // the number of entities that were written
} app_footer_v1;

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
BackupDataWriter::WriteAppHeader(const String8& packageName)
{
    if (m_status != NO_ERROR) {
        return m_status;
    }

    ssize_t amt;

    amt = write_padding_for(m_pos);
    if (amt != 0) {
        return amt;
    }

    app_header_v1 header;
    ssize_t nameLen;

    nameLen = packageName.length();

    header.type = tolel(APP_MAGIC_V1);
    header.packageLen = tolel(nameLen);

    amt = write(m_fd, &header, sizeof(app_header_v1));
    if (amt != sizeof(app_header_v1)) {
        m_status = errno;
        return m_status;
    }
    m_pos += amt;

    amt = write(m_fd, packageName.string(), nameLen+1);
    if (amt != nameLen+1) {
        m_status = errno;
        return m_status;
    }
    m_pos += amt;

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

    entity_header_v1 header;
    ssize_t keyLen;

    keyLen = key.length();

    header.type = tolel(ENTITY_MAGIC_V1);
    header.keyLen = tolel(keyLen);
    header.dataSize = tolel(dataSize);

    amt = write(m_fd, &header, sizeof(entity_header_v1));
    if (amt != sizeof(entity_header_v1)) {
        m_status = errno;
        return m_status;
    }
    m_pos += amt;

    amt = write(m_fd, key.string(), keyLen+1);
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

status_t
BackupDataWriter::WriteAppFooter()
{
    if (m_status != NO_ERROR) {
        return m_status;
    }

    ssize_t amt;

    amt = write_padding_for(m_pos);
    if (amt != 0) {
        return amt;
    }

    app_footer_v1 footer;
    ssize_t nameLen;

    footer.type = tolel(FOOTER_MAGIC_V1);
    footer.entityCount = tolel(m_entityCount);

    amt = write(m_fd, &footer, sizeof(app_footer_v1));
    if (amt != sizeof(app_footer_v1)) {
        m_status = errno;
        return m_status;
    }
    m_pos += amt;

    return NO_ERROR;
}

} // namespace android
