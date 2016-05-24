/*
 * Copyright (C) 2012 The Android Open Source Project
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

/*
 * A service that exchanges time synchronization information between
 * a master that defines a timeline and clients that follow the timeline.
 */

#define LOG_TAG "common_time"
#include <utils/Log.h>

#include <arpa/inet.h>
#include <stdint.h>

#include "common_time_server_packets.h"

namespace android {

const uint32_t TimeServicePacketHeader::kMagic =
    (static_cast<uint32_t>('c') << 24) |
    (static_cast<uint32_t>('c') << 16) |
    (static_cast<uint32_t>('l') <<  8) |
     static_cast<uint32_t>('k');

const uint16_t TimeServicePacketHeader::kCurVersion = 1;

#define SERIALIZE_FIELD(field_name, type, converter)        \
    do {                                                    \
        if ((offset + sizeof(field_name)) > length)         \
            return -1;                                      \
        *((type*)(data + offset)) = converter(field_name);  \
        offset += sizeof(field_name);                       \
    } while (0)
#define SERIALIZE_INT16(field_name) SERIALIZE_FIELD(field_name, int16_t, htons)
#define SERIALIZE_INT32(field_name) SERIALIZE_FIELD(field_name, int32_t, htonl)
#define SERIALIZE_INT64(field_name) SERIALIZE_FIELD(field_name, int64_t, htonq)

#define DESERIALIZE_FIELD(field_name, type, converter)       \
    do {                                                     \
        if ((offset + sizeof(field_name)) > length)          \
            return -1;                                       \
        (field_name) = converter(*((type*)(data + offset))); \
        offset += sizeof(field_name);                        \
    } while (0)
#define DESERIALIZE_INT16(field_name) DESERIALIZE_FIELD(field_name, int16_t, ntohs)
#define DESERIALIZE_INT32(field_name) DESERIALIZE_FIELD(field_name, int32_t, ntohl)
#define DESERIALIZE_INT64(field_name) DESERIALIZE_FIELD(field_name, int64_t, ntohq)

#define kDevicePriorityShift 56
#define kDeviceIDMask ((static_cast<uint64_t>(1) << kDevicePriorityShift) - 1)

inline uint64_t packDeviceID(uint64_t devID, uint8_t prio) {
    return (devID & kDeviceIDMask) |
           (static_cast<uint64_t>(prio) << kDevicePriorityShift);
}

inline uint64_t unpackDeviceID(uint64_t packed) {
    return (packed & kDeviceIDMask);
}

inline uint8_t unpackDevicePriority(uint64_t packed) {
    return static_cast<uint8_t>(packed >> kDevicePriorityShift);
}

ssize_t TimeServicePacketHeader::serializeHeader(uint8_t* data,
                                                 uint32_t length) {
    ssize_t offset = 0;
    int16_t pktType = static_cast<int16_t>(packetType);
    SERIALIZE_INT32(magic);
    SERIALIZE_INT16(version);
    SERIALIZE_INT16(pktType);
    SERIALIZE_INT64(timelineID);
    SERIALIZE_INT64(syncGroupID);
    return offset;
}

ssize_t TimeServicePacketHeader::deserializeHeader(const uint8_t* data,
                                                   uint32_t length) {
    ssize_t offset = 0;
    int16_t tmp;
    DESERIALIZE_INT32(magic);
    DESERIALIZE_INT16(version);
    DESERIALIZE_INT16(tmp);
    DESERIALIZE_INT64(timelineID);
    DESERIALIZE_INT64(syncGroupID);
    packetType = static_cast<TimeServicePacketType>(tmp);
    return offset;
}

ssize_t TimeServicePacketHeader::serializePacket(uint8_t* data,
                                                 uint32_t length) {
    ssize_t ret, tmp;

    ret = serializeHeader(data, length);
    if (ret < 0)
        return ret;

    data += ret;
    length -= ret;

    switch (packetType) {
        case TIME_PACKET_WHO_IS_MASTER_REQUEST:
            tmp =((WhoIsMasterRequestPacket*)(this))->serializePacket(data,
                                                                      length);
            break;
        case TIME_PACKET_WHO_IS_MASTER_RESPONSE:
            tmp =((WhoIsMasterResponsePacket*)(this))->serializePacket(data,
                                                                       length);
            break;
        case TIME_PACKET_SYNC_REQUEST:
            tmp =((SyncRequestPacket*)(this))->serializePacket(data, length);
            break;
        case TIME_PACKET_SYNC_RESPONSE:
            tmp =((SyncResponsePacket*)(this))->serializePacket(data, length);
            break;
        case TIME_PACKET_MASTER_ANNOUNCEMENT:
            tmp =((MasterAnnouncementPacket*)(this))->serializePacket(data,
                                                                      length);
            break;
        default:
            return -1;
    }

    if (tmp < 0)
        return tmp;

    return ret + tmp;
}

ssize_t UniversalTimeServicePacket::deserializePacket(
        const uint8_t* data,
        uint32_t length,
        uint64_t expectedSyncGroupID) {
    ssize_t ret;
    TimeServicePacketHeader* header;
    if (length < 8)
        return -1;

    packetType = ntohs(*((uint16_t*)(data + 6)));
    switch (packetType) {
        case TIME_PACKET_WHO_IS_MASTER_REQUEST:
            ret = p.who_is_master_request.deserializePacket(data, length);
            header = &p.who_is_master_request;
            break;
        case TIME_PACKET_WHO_IS_MASTER_RESPONSE:
            ret = p.who_is_master_response.deserializePacket(data, length);
            header = &p.who_is_master_response;
            break;
        case TIME_PACKET_SYNC_REQUEST:
            ret = p.sync_request.deserializePacket(data, length);
            header = &p.sync_request;
            break;
        case TIME_PACKET_SYNC_RESPONSE:
            ret = p.sync_response.deserializePacket(data, length);
            header = &p.sync_response;
            break;
        case TIME_PACKET_MASTER_ANNOUNCEMENT:
            ret = p.master_announcement.deserializePacket(data, length);
            header = &p.master_announcement;
            break;
        default:
            return -1;
    }

    if ((ret >= 0) && !header->checkPacket(expectedSyncGroupID))
        ret = -1;

    return ret;
}

ssize_t WhoIsMasterRequestPacket::serializePacket(uint8_t* data,
                                                  uint32_t length) {
    ssize_t offset = serializeHeader(data, length);
    if (offset > 0) {
        uint64_t packed = packDeviceID(senderDeviceID, senderDevicePriority);
        SERIALIZE_INT64(packed);
    }
    return offset;
}

ssize_t WhoIsMasterRequestPacket::deserializePacket(const uint8_t* data,
                                                    uint32_t length) {
    ssize_t offset = deserializeHeader(data, length);
    if (offset > 0) {
        uint64_t packed;
        DESERIALIZE_INT64(packed);
        senderDeviceID       = unpackDeviceID(packed);
        senderDevicePriority = unpackDevicePriority(packed);
    }
    return offset;
}

ssize_t WhoIsMasterResponsePacket::serializePacket(uint8_t* data,
                                                   uint32_t length) {
    ssize_t offset = serializeHeader(data, length);
    if (offset > 0) {
        uint64_t packed = packDeviceID(deviceID, devicePriority);
        SERIALIZE_INT64(packed);
    }
    return offset;
}

ssize_t WhoIsMasterResponsePacket::deserializePacket(const uint8_t* data,
                                                     uint32_t length) {
    ssize_t offset = deserializeHeader(data, length);
    if (offset > 0) {
        uint64_t packed;
        DESERIALIZE_INT64(packed);
        deviceID       = unpackDeviceID(packed);
        devicePriority = unpackDevicePriority(packed);
    }
    return offset;
}

ssize_t SyncRequestPacket::serializePacket(uint8_t* data,
                                           uint32_t length) {
    ssize_t offset = serializeHeader(data, length);
    if (offset > 0) {
        SERIALIZE_INT64(clientTxLocalTime);
    }
    return offset;
}

ssize_t SyncRequestPacket::deserializePacket(const uint8_t* data,
                                             uint32_t length) {
    ssize_t offset = deserializeHeader(data, length);
    if (offset > 0) {
        DESERIALIZE_INT64(clientTxLocalTime);
    }
    return offset;
}

ssize_t SyncResponsePacket::serializePacket(uint8_t* data,
                                            uint32_t length) {
    ssize_t offset = serializeHeader(data, length);
    if (offset > 0) {
        SERIALIZE_INT64(clientTxLocalTime);
        SERIALIZE_INT64(masterRxCommonTime);
        SERIALIZE_INT64(masterTxCommonTime);
        SERIALIZE_INT32(nak);
    }
    return offset;
}

ssize_t SyncResponsePacket::deserializePacket(const uint8_t* data,
                                              uint32_t length) {
    ssize_t offset = deserializeHeader(data, length);
    if (offset > 0) {
        DESERIALIZE_INT64(clientTxLocalTime);
        DESERIALIZE_INT64(masterRxCommonTime);
        DESERIALIZE_INT64(masterTxCommonTime);
        DESERIALIZE_INT32(nak);
    }
    return offset;
}

ssize_t MasterAnnouncementPacket::serializePacket(uint8_t* data,
                                                  uint32_t length) {
    ssize_t offset = serializeHeader(data, length);
    if (offset > 0) {
        uint64_t packed = packDeviceID(deviceID, devicePriority);
        SERIALIZE_INT64(packed);
    }
    return offset;
}

ssize_t MasterAnnouncementPacket::deserializePacket(const uint8_t* data,
                                                    uint32_t length) {
    ssize_t offset = deserializeHeader(data, length);
    if (offset > 0) {
        uint64_t packed;
        DESERIALIZE_INT64(packed);
        deviceID       = unpackDeviceID(packed);
        devicePriority = unpackDevicePriority(packed);
    }
    return offset;
}

}  // namespace android

