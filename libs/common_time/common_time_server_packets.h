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

#ifndef ANDROID_COMMON_TIME_SERVER_PACKETS_H
#define ANDROID_COMMON_TIME_SERVER_PACKETS_H

#include <stdint.h>
#include <common_time/ICommonClock.h>

namespace android {

/***** time sync protocol packets *****/

enum TimeServicePacketType {
    TIME_PACKET_WHO_IS_MASTER_REQUEST = 1,
    TIME_PACKET_WHO_IS_MASTER_RESPONSE,
    TIME_PACKET_SYNC_REQUEST,
    TIME_PACKET_SYNC_RESPONSE,
    TIME_PACKET_MASTER_ANNOUNCEMENT,
};

class TimeServicePacketHeader {
  public:
    friend class UniversalTimeServicePacket;
    // magic number identifying the protocol
    uint32_t magic;

    // protocol version of the packet
    uint16_t version;

    // type of the packet
    TimeServicePacketType packetType;

    // the timeline ID
    uint64_t timelineID;

    // synchronization group this packet belongs to (used to operate multiple
    // synchronization domains which all use the same master election endpoint)
    uint64_t syncGroupID;

    ssize_t serializePacket(uint8_t* data, uint32_t length);

  protected:
    void initHeader(TimeServicePacketType type,
                    const uint64_t tlID,
                    const uint64_t groupID) {
        magic              = kMagic;
        version            = kCurVersion;
        packetType         = type;
        timelineID         = tlID;
        syncGroupID        = groupID;
    }

    bool checkPacket(uint64_t expectedSyncGroupID) const {
        return ((magic       == kMagic) &&
                (version     == kCurVersion) &&
                (!expectedSyncGroupID || (syncGroupID == expectedSyncGroupID)));
    }

    ssize_t serializeHeader(uint8_t* data, uint32_t length);
    ssize_t deserializeHeader(const uint8_t* data, uint32_t length);

  private:
    static const uint32_t kMagic;
    static const uint16_t kCurVersion;
};

// packet querying for a suitable master
class WhoIsMasterRequestPacket : public TimeServicePacketHeader {
  public:
    uint64_t senderDeviceID;
    uint8_t senderDevicePriority;

    void initHeader(const uint64_t groupID) {
        TimeServicePacketHeader::initHeader(TIME_PACKET_WHO_IS_MASTER_REQUEST,
                                            ICommonClock::kInvalidTimelineID,
                                            groupID);
    }

    ssize_t serializePacket(uint8_t* data, uint32_t length);
    ssize_t deserializePacket(const uint8_t* data, uint32_t length);
};

// response to a WhoIsMaster request
class WhoIsMasterResponsePacket : public TimeServicePacketHeader {
  public:
    uint64_t deviceID;
    uint8_t devicePriority;

    void initHeader(const uint64_t tlID, const uint64_t groupID) {
        TimeServicePacketHeader::initHeader(TIME_PACKET_WHO_IS_MASTER_RESPONSE,
                                            tlID, groupID);
    }

    ssize_t serializePacket(uint8_t* data, uint32_t length);
    ssize_t deserializePacket(const uint8_t* data, uint32_t length);
};

// packet sent by a client requesting correspondence between local
// and common time
class SyncRequestPacket : public TimeServicePacketHeader {
  public:
    // local time when this request was transmitted
    int64_t clientTxLocalTime;

    void initHeader(const uint64_t tlID, const uint64_t groupID) {
        TimeServicePacketHeader::initHeader(TIME_PACKET_SYNC_REQUEST,
                                            tlID, groupID);
    }

    ssize_t serializePacket(uint8_t* data, uint32_t length);
    ssize_t deserializePacket(const uint8_t* data, uint32_t length);
};

// response to a sync request sent by the master
class SyncResponsePacket : public TimeServicePacketHeader {
  public:
    // local time when this request was transmitted by the client
    int64_t clientTxLocalTime;

    // common time when the master received the request
    int64_t masterRxCommonTime;

    // common time when the master transmitted the response
    int64_t masterTxCommonTime;

    // flag that is set if the recipient of the sync request is not acting
    // as a master for the requested timeline
    uint32_t nak;

    void initHeader(const uint64_t tlID, const uint64_t groupID) {
        TimeServicePacketHeader::initHeader(TIME_PACKET_SYNC_RESPONSE,
                                            tlID, groupID);
    }

    ssize_t serializePacket(uint8_t* data, uint32_t length);
    ssize_t deserializePacket(const uint8_t* data, uint32_t length);
};

// announcement of the master's presence
class MasterAnnouncementPacket : public TimeServicePacketHeader {
  public:
    // the master's device ID
    uint64_t deviceID;
    uint8_t devicePriority;

    void initHeader(const uint64_t tlID, const uint64_t groupID) {
        TimeServicePacketHeader::initHeader(TIME_PACKET_MASTER_ANNOUNCEMENT,
                                            tlID, groupID);
    }

    ssize_t serializePacket(uint8_t* data, uint32_t length);
    ssize_t deserializePacket(const uint8_t* data, uint32_t length);
};

class UniversalTimeServicePacket {
  public:
    uint16_t packetType;
    union {
        WhoIsMasterRequestPacket  who_is_master_request;
        WhoIsMasterResponsePacket who_is_master_response;
        SyncRequestPacket         sync_request;
        SyncResponsePacket        sync_response;
        MasterAnnouncementPacket  master_announcement;
    } p;

    ssize_t deserializePacket(const uint8_t* data,
                              uint32_t       length,
                              uint64_t       expectedSyncGroupID);
};

};  // namespace android

#endif  // ANDROID_COMMON_TIME_SERVER_PACKETS_H


