/*
 * Copyright (C) 2011 The Android Open Source Project
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

#define LOG_TAG "LibAAH_RTP"
//#define LOG_NDEBUG 0
#include <utils/Log.h>

#include <fcntl.h>
#include <poll.h>
#include <sys/socket.h>
#include <time.h>
#include <utils/misc.h>

#include <media/stagefright/Utils.h>

#include "aah_rx_player.h"
#include "aah_tx_packet.h"

namespace android {

const uint32_t AAH_RXPlayer::kRetransRequestMagic =
    FOURCC('T','r','e','q');
const uint32_t AAH_RXPlayer::kRetransNAKMagic =
    FOURCC('T','n','a','k');
const uint32_t AAH_RXPlayer::kFastStartRequestMagic =
    FOURCC('T','f','s','t');
const uint32_t AAH_RXPlayer::kGapRerequestTimeoutUSec = 75000;
const uint32_t AAH_RXPlayer::kFastStartTimeoutUSec = 800000;
const uint32_t AAH_RXPlayer::kRTPActivityTimeoutUSec = 10000000;

static inline int16_t fetchInt16(uint8_t* data) {
    return static_cast<int16_t>(U16_AT(data));
}

static inline int32_t fetchInt32(uint8_t* data) {
    return static_cast<int32_t>(U32_AT(data));
}

static inline int64_t fetchInt64(uint8_t* data) {
    return static_cast<int64_t>(U64_AT(data));
}

uint64_t AAH_RXPlayer::monotonicUSecNow() {
    struct timespec now;
    int res = clock_gettime(CLOCK_MONOTONIC, &now);
    CHECK(res >= 0);

    uint64_t ret = static_cast<uint64_t>(now.tv_sec) * 1000000;
    ret += now.tv_nsec / 1000;

    return ret;
}

status_t AAH_RXPlayer::startWorkThread() {
    status_t res;
    stopWorkThread();
    res = thread_wrapper_->run("TRX_Player", PRIORITY_AUDIO);

    if (res != OK) {
        LOGE("Failed to start work thread (res = %d)", res);
    }

    return res;
}

void AAH_RXPlayer::stopWorkThread() {
    thread_wrapper_->requestExit();  // set the exit pending flag
    wakeup_work_thread_evt_.setEvent();

    status_t res;
    res = thread_wrapper_->requestExitAndWait(); // block until thread exit.
    if (res != OK) {
        LOGE("Failed to stop work thread (res = %d)", res);
    }

    wakeup_work_thread_evt_.clearPendingEvents();
}

void AAH_RXPlayer::cleanupSocket() {
    if (sock_fd_ >= 0) {
        if (multicast_joined_) {
            int res;
            struct ip_mreq mreq;
            mreq.imr_multiaddr = listen_addr_.sin_addr;
            mreq.imr_interface.s_addr = htonl(INADDR_ANY);
            res = setsockopt(sock_fd_,
                             IPPROTO_IP,
                             IP_DROP_MEMBERSHIP,
                             &mreq, sizeof(mreq));
            if (res < 0) {
                LOGW("Failed to leave multicast group. (%d, %d)", res, errno);
            }
            multicast_joined_ = false;
        }

        close(sock_fd_);
        sock_fd_ = -1;
    }

    resetPipeline();
}

void AAH_RXPlayer::resetPipeline() {
    ring_buffer_.reset();

    // Explicitly shudown all of the active substreams, then call clear out the
    // collection.  Failure to clear out a substream can result in its decoder
    // holding a reference to itself and therefor not going away when the
    // collection is cleared.
    for (size_t i = 0; i < substreams_.size(); ++i)
        substreams_.valueAt(i)->shutdown();

    substreams_.clear();

    current_gap_status_ = kGS_NoGap;
}

bool AAH_RXPlayer::setupSocket() {
    long flags;
    int  res, buf_size;
    socklen_t opt_size;

    cleanupSocket();
    CHECK(sock_fd_ < 0);

    // Make the socket
    sock_fd_ = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock_fd_ < 0) {
        LOGE("Failed to create listen socket (errno %d)", errno);
        goto bailout;
    }

    // Set non-blocking operation
    flags = fcntl(sock_fd_, F_GETFL);
    res   = fcntl(sock_fd_, F_SETFL, flags | O_NONBLOCK);
    if (res < 0) {
        LOGE("Failed to set socket (%d) to non-blocking mode (errno %d)",
             sock_fd_, errno);
        goto bailout;
    }

    // Bind to our port
    struct sockaddr_in bind_addr;
    memset(&bind_addr, 0, sizeof(bind_addr));
    bind_addr.sin_family = AF_INET;
    bind_addr.sin_addr.s_addr = INADDR_ANY;
    bind_addr.sin_port = listen_addr_.sin_port;
    res = bind(sock_fd_,
               reinterpret_cast<const sockaddr*>(&bind_addr),
               sizeof(bind_addr));
    if (res < 0) {
        uint32_t a = ntohl(bind_addr.sin_addr.s_addr);
        uint16_t p = ntohs(bind_addr.sin_port);
        LOGE("Failed to bind socket (%d) to %d.%d.%d.%d:%hd. (errno %d)",
             sock_fd_,
             (a >> 24) & 0xFF,
             (a >> 16) & 0xFF,
             (a >>  8) & 0xFF,
             (a      ) & 0xFF,
             p,
             errno);

        goto bailout;
    }

    buf_size = 1 << 16;   // 64k
    res = setsockopt(sock_fd_,
                     SOL_SOCKET, SO_RCVBUF,
                     &buf_size, sizeof(buf_size));
    if (res < 0) {
        LOGW("Failed to increase socket buffer size to %d.  (errno %d)",
             buf_size, errno);
    }

    buf_size = 0;
    opt_size = sizeof(buf_size);
    res = getsockopt(sock_fd_,
                     SOL_SOCKET, SO_RCVBUF,
                     &buf_size, &opt_size);
    if (res < 0) {
        LOGW("Failed to increase socket buffer size to %d.  (errno %d)",
             buf_size, errno);
    } else {
        LOGI("RX socket buffer size is now %d bytes",  buf_size);
    }

    if (listen_addr_.sin_addr.s_addr) {
        // Join the multicast group and we should be good to go.
        struct ip_mreq mreq;
        mreq.imr_multiaddr = listen_addr_.sin_addr;
        mreq.imr_interface.s_addr = htonl(INADDR_ANY);
        res = setsockopt(sock_fd_,
                         IPPROTO_IP,
                         IP_ADD_MEMBERSHIP,
                         &mreq, sizeof(mreq));
        if (res < 0) {
            LOGE("Failed to join multicast group. (errno %d)", errno);
            goto bailout;
        }
        multicast_joined_ = true;
    }

    return true;

bailout:
    cleanupSocket();
    return false;
}

bool AAH_RXPlayer::threadLoop() {
    struct pollfd poll_fds[2];
    bool process_more_right_now = false;

    if (!setupSocket()) {
        sendEvent(MEDIA_ERROR);
        goto bailout;
    }

    while (!thread_wrapper_->exitPending()) {
        // Step 1: Wait until there is something to do.
        int gap_timeout = computeNextGapRetransmitTimeout();
        int ring_timeout = ring_buffer_.computeInactivityTimeout();
        int timeout = -1;

        if (!ring_timeout) {
            LOGW("RTP inactivity timeout reached, resetting pipeline.");
            resetPipeline();
            timeout = gap_timeout;
        } else {
            if (gap_timeout < 0) {
                timeout = ring_timeout;
            } else if (ring_timeout < 0) {
                timeout = gap_timeout;
            } else {
                timeout = (gap_timeout < ring_timeout) ? gap_timeout
                                                       : ring_timeout;
            }
        }

        if ((0 != timeout) && (!process_more_right_now)) {
            // Set up the events to wait on.  Start with the wakeup pipe.
            memset(&poll_fds, 0, sizeof(poll_fds));
            poll_fds[0].fd     = wakeup_work_thread_evt_.getWakeupHandle();
            poll_fds[0].events = POLLIN;

            // Add the RX socket.
            poll_fds[1].fd     = sock_fd_;
            poll_fds[1].events = POLLIN;

            // Wait for something interesing to happen.
            int poll_res = poll(poll_fds, NELEM(poll_fds), timeout);
            if (poll_res < 0) {
                LOGE("Fatal error (%d,%d) while waiting on events",
                     poll_res, errno);
                sendEvent(MEDIA_ERROR);
                goto bailout;
            }
        }

        if (thread_wrapper_->exitPending()) {
            break;
        }

        wakeup_work_thread_evt_.clearPendingEvents();
        process_more_right_now = false;

        // Step 2: Do we have data waiting in the socket?  If so, drain the
        // socket moving valid RTP information into the ring buffer to be
        // processed.
        if (poll_fds[1].revents) {
            struct sockaddr_in from;
            socklen_t from_len;

            ssize_t res = 0;
            while (!thread_wrapper_->exitPending()) {
                // Check the size of any pending packet.
                res = recv(sock_fd_, NULL, 0, MSG_PEEK | MSG_TRUNC);

                // Error?
                if (res < 0) {
                    // If the error is anything other than would block,
                    // something has gone very wrong.
                    if ((errno != EAGAIN) && (errno != EWOULDBLOCK)) {
                        LOGE("Fatal socket error during recvfrom (%d, %d)",
                             (int)res, errno);
                        goto bailout;
                    }

                    // Socket is out of data, just break out of processing and
                    // wait for more.
                    break;
                }

                // Allocate a payload.
                PacketBuffer* pb = PacketBuffer::allocate(res);
                if (NULL == pb) {
                    LOGE("Fatal error, failed to allocate packet buffer of"
                         " length %u", static_cast<uint32_t>(res));
                    goto bailout;
                }

                // Fetch the data.
                from_len = sizeof(from);
                res = recvfrom(sock_fd_, pb->data_, pb->length_, 0,
                               reinterpret_cast<struct sockaddr*>(&from),
                               &from_len);
                if (res != pb->length_) {
                    LOGE("Fatal error, fetched packet length (%d) does not"
                         " match peeked packet length (%u).  This should never"
                         " happen.  (errno = %d)",
                         static_cast<int>(res),
                         static_cast<uint32_t>(pb->length_),
                         errno);
                }

                bool drop_packet = false;
                if (transmitter_known_) {
                    if (from.sin_addr.s_addr !=
                        transmitter_addr_.sin_addr.s_addr) {
                        uint32_t a = ntohl(from.sin_addr.s_addr);
                        uint16_t p = ntohs(from.sin_port);
                        LOGV("Dropping packet from unknown transmitter"
                             " %u.%u.%u.%u:%hu",
                             ((a >> 24) & 0xFF),
                             ((a >> 16) & 0xFF),
                             ((a >>  8) & 0xFF),
                             ( a        & 0xFF),
                             p);

                        drop_packet = true;
                    } else {
                        transmitter_addr_.sin_port = from.sin_port;
                    }
                } else {
                    memcpy(&transmitter_addr_, &from, sizeof(from));
                    transmitter_known_ = true;
                }

                if (!drop_packet) {
                    bool serious_error = !processRX(pb);

                    if (serious_error) {
                        // Something went "seriously wrong".  Currently, the
                        // only trigger for this should be a ring buffer
                        // overflow.  The current failsafe behavior for when
                        // something goes seriously wrong is to just reset the
                        // pipeline.  The system should behave as if this
                        // AAH_RXPlayer was just set up for the first time.
                        LOGE("Something just went seriously wrong with the"
                             " pipeline.  Resetting.");
                        resetPipeline();
                    }
                } else {
                    PacketBuffer::destroy(pb);
                }
            }
        }

        // Step 3: Process any data we mave have accumulated in the ring buffer
        // so far.
        if (!thread_wrapper_->exitPending()) {
            processRingBuffer();
        }

        // Step 4: At this point in time, the ring buffer should either be
        // empty, or stalled in front of a gap caused by some dropped packets.
        // Check on the current gap situation and deal with it in an appropriate
        // fashion.  If processGaps returns true, it means that it has given up
        // on a gap and that we should try to process some more data
        // immediately.
        if (!thread_wrapper_->exitPending()) {
            process_more_right_now = processGaps();
        }
    }

bailout:
    cleanupSocket();
    return false;
}

bool AAH_RXPlayer::processRX(PacketBuffer* pb) {
    CHECK(NULL != pb);

    uint8_t* data = pb->data_;
    ssize_t  amt  = pb->length_;
    uint32_t nak_magic;
    uint16_t seq_no;
    uint32_t epoch;

    // Every packet either starts with an RTP header which is at least 12 bytes
    // long or is a retry NAK which is 14 bytes long.  If there are fewer than
    // 12 bytes here, this cannot be a proper RTP packet.
    if (amt < 12) {
        LOGV("Dropping packet, too short to contain RTP header (%u bytes)",
             static_cast<uint32_t>(amt));
        goto drop_packet;
    }

    // Check to see if this is the special case of a NAK packet.
    nak_magic = ntohl(*(reinterpret_cast<uint32_t*>(data)));
    if (nak_magic == kRetransNAKMagic) {
        // Looks like a NAK packet; make sure its long enough.

        if (amt < static_cast<ssize_t>(sizeof(RetransRequest))) {
            LOGV("Dropping packet, too short to contain NAK payload (%u bytes)",
                 static_cast<uint32_t>(amt));
            goto drop_packet;
        }

        SeqNoGap gap;
        RetransRequest* rtr = reinterpret_cast<RetransRequest*>(data);
        gap.start_seq_ = ntohs(rtr->start_seq_);
        gap.end_seq_   = ntohs(rtr->end_seq_);

        LOGV("Process NAK for gap at [%hu, %hu]", gap.start_seq_, gap.end_seq_);
        ring_buffer_.processNAK(&gap);

        return true;
    }

    // According to the TRTP spec, version should be 2, padding should be 0,
    // extension should be 0 and CSRCCnt should be 0.  If any of these tests
    // fail, we chuck the packet.
    if (data[0] != 0x80) {
        LOGV("Dropping packet, bad V/P/X/CSRCCnt field (0x%02x)",
             data[0]);
        goto drop_packet;
    }

    // Check the payload type.  For TRTP, it should always be 100.
    if ((data[1] & 0x7F) != 100) {
        LOGV("Dropping packet, bad payload type. (%u)",
             data[1] & 0x7F);
        goto drop_packet;
    }

    // Check whether the transmitter has begun a new epoch.
    epoch = (U32_AT(data + 8) >> 10) & 0x3FFFFF;
    if (current_epoch_known_) {
        if (epoch != current_epoch_) {
            LOGV("%s: new epoch %u", __PRETTY_FUNCTION__, epoch);
            current_epoch_ = epoch;
            resetPipeline();
        }
    } else {
        current_epoch_ = epoch;
        current_epoch_known_ = true;
    }

    // Extract the sequence number and hand the packet off to the ring buffer
    // for dropped packet detection and later processing.
    seq_no = U16_AT(data + 2);
    return ring_buffer_.pushBuffer(pb, seq_no);

drop_packet:
    PacketBuffer::destroy(pb);
    return true;
}

void AAH_RXPlayer::processRingBuffer() {
    PacketBuffer* pb;
    bool is_discon;
    sp<Substream> substream;
    LinearTransform trans;
    bool foundTrans = false;

    while (NULL != (pb = ring_buffer_.fetchBuffer(&is_discon))) {
        if (is_discon) {
            // Abort all partially assembled payloads.
            for (size_t i = 0; i < substreams_.size(); ++i) {
                CHECK(substreams_.valueAt(i) != NULL);
                substreams_.valueAt(i)->cleanupBufferInProgress();
            }
        }

        uint8_t* data = pb->data_;
        ssize_t  amt  = pb->length_;

        // Should not have any non-RTP packets in the ring buffer.  RTP packets
        // must be at least 12 bytes long.
        CHECK(amt >= 12);

        // Extract the marker bit and the SSRC field.
        bool     marker = (data[1] & 0x80) != 0;
        uint32_t ssrc   = U32_AT(data + 8);

        // Is this the start of a new TRTP payload?  If so, the marker bit
        // should be set and there are some things we should be checking for.
        if (marker) {
            // TRTP headers need to have at least a byte for version, a byte for
            // payload type and flags, and 4 bytes for length.
            if (amt < 18) {
                LOGV("Dropping packet, too short to contain TRTP header"
                     " (%u bytes)", static_cast<uint32_t>(amt));
                goto process_next_packet;
            }

            // Check the TRTP version and extract the payload type/flags.
            uint8_t trtp_version =  data[12];
            uint8_t payload_type = (data[13] >> 4) & 0xF;
            uint8_t trtp_flags   =  data[13]       & 0xF;

            if (1 != trtp_version) {
                LOGV("Dropping packet, bad trtp version %hhu", trtp_version);
                goto process_next_packet;
            }

            // Is there a timestamp transformation present on this packet?  If
            // so, extract it and pass it to the appropriate substreams.
            if (trtp_flags & 0x02) {
                ssize_t offset = 18 + ((trtp_flags & 0x01) ? 4 : 0);
                if (amt < (offset + 24)) {
                    LOGV("Dropping packet, too short to contain TRTP Timestamp"
                         " Transformation (%u bytes)",
                         static_cast<uint32_t>(amt));
                    goto process_next_packet;
                }

                trans.a_zero = fetchInt64(data + offset);
                trans.b_zero = fetchInt64(data + offset + 16);
                trans.a_to_b_numer = static_cast<int32_t>(
                        fetchInt32 (data + offset + 8));
                trans.a_to_b_denom = U32_AT(data + offset + 12);
                foundTrans = true;

                uint32_t program_id = (ssrc >> 5) & 0x1F;
                for (size_t i = 0; i < substreams_.size(); ++i) {
                    sp<Substream> iter = substreams_.valueAt(i);
                    CHECK(iter != NULL);

                    if (iter->getProgramID() == program_id) {
                        iter->processTSTransform(trans);
                    }
                }
            }

            // Is this a command packet?  If so, its not necessarily associate
            // with one particular substream.  Just give it to the command
            // packet handler and then move on.
            if (4 == payload_type) {
                processCommandPacket(pb);
                goto process_next_packet;
            }
        }

        // If we got to here, then we are a normal packet.  Find (or allocate)
        // the substream we belong to and send the packet off to be processed.
        substream = substreams_.valueFor(ssrc);
        if (substream == NULL) {
            substream = new Substream(ssrc, omx_);
            if (substream == NULL) {
                LOGE("Failed to allocate substream for SSRC 0x%08x", ssrc);
                goto process_next_packet;
            }
            substreams_.add(ssrc, substream);

            if (foundTrans) {
                substream->processTSTransform(trans);
            }
        }

        CHECK(substream != NULL);

        if (marker) {
            // Start of a new TRTP payload for this substream.  Extract the
            // lower 32 bits of the timestamp and hand the buffer to the
            // substream for processing.
            uint32_t ts_lower = U32_AT(data + 4);
            substream->processPayloadStart(data + 12, amt - 12, ts_lower);
        } else {
            // Continuation of an existing TRTP payload.  Just hand it off to
            // the substream for processing.
            substream->processPayloadCont(data + 12, amt - 12);
        }

process_next_packet:
        PacketBuffer::destroy(pb);
    }  // end of main processing while loop.
}

void AAH_RXPlayer::processCommandPacket(PacketBuffer* pb) {
    CHECK(NULL != pb);

    uint8_t* data = pb->data_;
    ssize_t  amt  = pb->length_;

    // verify that this packet meets the minimum length of a command packet
    if (amt < 20) {
        return;
    }

    uint8_t trtp_version =  data[12];
    uint8_t trtp_flags   =  data[13]       & 0xF;

    if (1 != trtp_version) {
        LOGV("Dropping packet, bad trtp version %hhu", trtp_version);
        return;
    }

    // calculate the start of the command payload
    ssize_t offset = 18;
    if (trtp_flags & 0x01) {
        // timestamp is present (4 bytes)
        offset += 4;
    }
    if (trtp_flags & 0x02) {
        // transform is present (24 bytes)
        offset += 24;
    }

    // the packet must contain 2 bytes of command payload beyond the TRTP header
    if (amt < offset + 2) {
        return;
    }

    uint16_t command_id = U16_AT(data + offset);

    switch (command_id) {
        case TRTPControlPacket::kCommandNop:
            break;

        case TRTPControlPacket::kCommandEOS:
        case TRTPControlPacket::kCommandFlush: {
            uint16_t program_id = (U32_AT(data + 8) >> 5) & 0x1F;
            LOGI("*** %s flushing program_id=%d",
                 __PRETTY_FUNCTION__, program_id);

            Vector<uint32_t> substreams_to_remove;
            for (size_t i = 0; i < substreams_.size(); ++i) {
                sp<Substream> iter = substreams_.valueAt(i);
                if (iter->getProgramID() == program_id) {
                    iter->shutdown();
                    substreams_to_remove.add(iter->getSSRC());
                }
            }

            for (size_t i = 0; i < substreams_to_remove.size(); ++i) {
                substreams_.removeItem(substreams_to_remove[i]);
            }
        } break;
    }
}

bool AAH_RXPlayer::processGaps() {
    // Deal with the current gap situation.  Specifically...
    //
    // 1) If a new gap has shown up, send a retransmit request to the
    //    transmitter.
    // 2) If a gap we were working on has had a packet in the middle or at
    //    the end filled in, send another retransmit request for the begining
    //    portion of the gap.  TRTP was designed for LANs where packet
    //    re-ordering is very unlikely; so see the middle or end of a gap
    //    filled in before the begining is an almost certain indication that
    //    a retransmission packet was also dropped.
    // 3) If we have been working on a gap for a while and it still has not
    //    been filled in, send another retransmit request.
    // 4) If the are no more gaps in the ring, clear the current_gap_status_
    //    flag to indicate that all is well again.

    // Start by fetching the active gap status.
    SeqNoGap gap;
    bool send_retransmit_request = false;
    bool ret_val = false;
    GapStatus gap_status;
    if (kGS_NoGap != (gap_status = ring_buffer_.fetchCurrentGap(&gap))) {
        // Note: checking for a change in the end sequence number should cover
        // moving on to an entirely new gap for case #1 as well as resending the
        // begining of a gap range for case #2.
        send_retransmit_request = (kGS_NoGap == current_gap_status_) ||
                                  (current_gap_.end_seq_ != gap.end_seq_);

        // If this is the same gap we have been working on, and it has timed
        // out, then check to see if our substreams are about to underflow.  If
        // so, instead of sending another retransmit request, just give up on
        // this gap and move on.
        if (!send_retransmit_request &&
           (kGS_NoGap != current_gap_status_) &&
           (0 == computeNextGapRetransmitTimeout())) {

            // If out current gap is the fast-start gap, don't bother to skip it
            // because substreams look like the are about to underflow.
            if ((kGS_FastStartGap != gap_status) ||
                (current_gap_.end_seq_ != gap.end_seq_)) {
                for (size_t i = 0; i < substreams_.size(); ++i) {
                    if (substreams_.valueAt(i)->isAboutToUnderflow()) {
                        LOGV("About to underflow, giving up on gap [%hu, %hu]",
                                gap.start_seq_, gap.end_seq_);
                        ring_buffer_.processNAK();
                        current_gap_status_ = kGS_NoGap;
                        return true;
                    }
                }
            }

            // Looks like no one is about to underflow.  Just go ahead and send
            // the request.
            send_retransmit_request = true;
        }
    } else {
        current_gap_status_ = kGS_NoGap;
    }

    if (send_retransmit_request) {
        // If we have been working on a fast start, and it is still not filled
        // in, even after the extended retransmit time out, give up and skip it.
        // The system should fall back into its normal slow-start behavior.
        if ((kGS_FastStartGap == current_gap_status_) &&
            (current_gap_.end_seq_ == gap.end_seq_)) {
            LOGV("Fast start is taking forever; giving up.");
            ring_buffer_.processNAK();
            current_gap_status_ = kGS_NoGap;
            return true;
        }

        // Send the request.
        RetransRequest req;
        uint32_t magic  = (kGS_FastStartGap == gap_status)
                        ? kFastStartRequestMagic
                        : kRetransRequestMagic;
        req.magic_      = htonl(magic);
        req.mcast_ip_   = listen_addr_.sin_addr.s_addr;
        req.mcast_port_ = listen_addr_.sin_port;
        req.start_seq_  = htons(gap.start_seq_);
        req.end_seq_    = htons(gap.end_seq_);

        {
            uint32_t a = ntohl(transmitter_addr_.sin_addr.s_addr);
            uint16_t p = ntohs(transmitter_addr_.sin_port);
            LOGV("Sending to transmitter %u.%u.%u.%u:%hu",
                    ((a >> 24) & 0xFF),
                    ((a >> 16) & 0xFF),
                    ((a >>  8) & 0xFF),
                    ( a        & 0xFF),
                    p);
        }

        int res = sendto(sock_fd_, &req, sizeof(req), 0,
                         reinterpret_cast<struct sockaddr*>(&transmitter_addr_),
                         sizeof(transmitter_addr_));
        if (res < 0) {
            LOGE("Error when sending retransmit request (%d)", errno);
        } else {
            LOGV("%s request for range [%hu, %hu] sent",
                 (kGS_FastStartGap == gap_status) ? "Fast Start" : "Retransmit",
                 gap.start_seq_, gap.end_seq_);
        }

        // Update the current gap info.
        current_gap_ = gap;
        current_gap_status_ = gap_status;
        next_retrans_req_time_ = monotonicUSecNow() +
                               ((kGS_FastStartGap == current_gap_status_)
                                ? kFastStartTimeoutUSec
                                : kGapRerequestTimeoutUSec);
    }

    return false;
}

// Compute when its time to send the next gap retransmission in milliseconds.
// Returns < 0 for an infinite timeout (no gap) and 0 if its time to retransmit
// right now.
int AAH_RXPlayer::computeNextGapRetransmitTimeout() {
    if (kGS_NoGap == current_gap_status_) {
        return -1;
    }

    int64_t timeout_delta = next_retrans_req_time_ - monotonicUSecNow();

    timeout_delta /= 1000;
    if (timeout_delta <= 0) {
        return 0;
    }

    return static_cast<uint32_t>(timeout_delta);
}

}  // namespace android
