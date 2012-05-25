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
#include "utils.h"

namespace android {

const uint32_t AAH_RXPlayer::kGapRerequestTimeoutMsec = 75;
const uint32_t AAH_RXPlayer::kFastStartTimeoutMsec = 800;
const uint32_t AAH_RXPlayer::kRTPActivityTimeoutMsec = 10000;
const uint32_t AAH_RXPlayer::kSSCleanoutTimeoutMsec = 1000;
const uint32_t AAH_RXPlayer::kGrpMemberSlowReportIntervalMsec = 900;
const uint32_t AAH_RXPlayer::kGrpMemberFastReportIntervalMsec = 200;

static inline int16_t fetchInt16(uint8_t* data) {
    return static_cast<int16_t>(U16_AT(data));
}

static inline int32_t fetchInt32(uint8_t* data) {
    return static_cast<int32_t>(U32_AT(data));
}

static inline int64_t fetchInt64(uint8_t* data) {
    return static_cast<int64_t>(U64_AT(data));
}

status_t AAH_RXPlayer::startWorkThread() {
    status_t res;
    stopWorkThread();
    ss_cleanout_timeout_.setTimeout(kSSCleanoutTimeoutMsec);
    res = thread_wrapper_->run("TRX_Player", PRIORITY_AUDIO);

    if (res != OK) {
        LOGE("Failed to start work thread (res = %d)", res);
    }

    return res;
}

void AAH_RXPlayer::stopWorkThread() {
    thread_wrapper_->requestExit();  // set the exit pending flag
    signalEventFD(wakeup_work_thread_evt_fd_);

    status_t res;
    res = thread_wrapper_->requestExitAndWait(); // block until thread exit.
    if (res != OK) {
        LOGE("Failed to stop work thread (res = %d)", res);
    }

    clearEventFD(wakeup_work_thread_evt_fd_);
}

void AAH_RXPlayer::cleanupSocket() {
    if (sock_fd_ >= 0) {
        // If we are in unicast mode, send a pair of leave requests spaced by a
        // short delay.  We send a pair to increase the probability that at
        // least one gets through.  If both get dropped, the transmitter will
        // figure it out eventually via the timeout, but we'd rather not rely on
        // that if we can avoid it.
        if (!multicast_mode_) {
            sendUnicastGroupLeave();
            usleep(20000);  // 20mSec
            sendUnicastGroupLeave();
        }

        // If we had joined a multicast group, make sure we leave it properly
        // before closing our socket.
        if (multicast_joined_) {
            int res;
            struct ip_mreq mreq;
            mreq.imr_multiaddr = data_source_addr_.sin_addr;
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

    multicast_mode_ = false;
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

    setGapStatus(kGS_NoGap);
}

bool AAH_RXPlayer::setupSocket() {
    long flags;
    int  res, buf_size;
    socklen_t opt_size;
    uint32_t addr = ntohl(data_source_addr_.sin_addr.s_addr);
    uint16_t port = ntohs(data_source_addr_.sin_port);

    cleanupSocket();
    CHECK(sock_fd_ < 0);

    // Make sure we have a valid data source before proceeding.
    if (!data_source_set_) {
        LOGE("setupSocket called with no data source set.");
        goto bailout;
    }

    if ((addr == INADDR_ANY) || !port) {
        LOGE("setupSocket called with invalid data source (%d.%d.%d.%d:%hu)",
             IP_PRINTF_HELPER(addr), port);
        goto bailout;
    }

    // Check to see if we are in multicast RX mode or not.
    multicast_mode_ = isMulticastSockaddr(&data_source_addr_);

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

    // Bind to our port.  If we are in multicast mode, we need to bind to the
    // port on which the multicast traffic will be arriving.  If we are in
    // unicast mode, then just bind to an ephemeral port.
    struct sockaddr_in bind_addr;
    memset(&bind_addr, 0, sizeof(bind_addr));
    bind_addr.sin_family = AF_INET;
    bind_addr.sin_addr.s_addr = INADDR_ANY;
    bind_addr.sin_port = multicast_mode_ ? data_source_addr_.sin_port : 0;
    res = bind(sock_fd_,
               reinterpret_cast<const sockaddr*>(&bind_addr),
               sizeof(bind_addr));
    if (res < 0) {
        uint32_t a = ntohl(bind_addr.sin_addr.s_addr);
        uint16_t p = ntohs(bind_addr.sin_port);
        LOGE("Failed to bind socket (%d) to %d.%d.%d.%d:%hu. (errno %d)",
             sock_fd_, IP_PRINTF_HELPER(a), p, errno);

        goto bailout;
    }

    // Increase our socket buffer RX size
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
        LOGD("RX socket buffer size is now %d bytes",  buf_size);
    }

    // If we are in multicast mode, join our socket to the multicast group on
    // which we expect to receive traffic.
    if (multicast_mode_) {
        // Join the multicast group and we should be good to go.
        struct ip_mreq mreq;
        mreq.imr_multiaddr = data_source_addr_.sin_addr;
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

    // If we are not in multicast mode, send our first group membership report
    // right now.  Otherwise, make sure that the timeout has been canceled so we
    // don't accidentally end up sending reports when we should not.
    if (!multicast_mode_) {
        sendUnicastGroupJoin();
    } else {
        unicast_group_report_timeout_.setTimeout(-1);
    }

    while (!thread_wrapper_->exitPending()) {
        // Step 1: Wait until there is something to do.
        int timeout = -1;
        int tmp;

        // Time to report unicast group membership?
        if (!(tmp = unicast_group_report_timeout_.msecTillTimeout())) {
            sendUnicastGroupJoin();
            continue;
        }
        timeout = minTimeout(tmp, timeout);

        // Ring buffer timed out?
        if (!(tmp = ring_buffer_.computeInactivityTimeout())) {
            LOGW("RTP inactivity timeout reached, resetting pipeline.");
            resetPipeline();
            continue;
        }
        timeout = minTimeout(tmp, timeout);

        // Time to check for expired substreams?
        if (!(tmp = ss_cleanout_timeout_.msecTillTimeout())) {
            cleanoutExpiredSubstreams();
            continue;
        }
        timeout = minTimeout(tmp, timeout);

        // Finally, take the next retransmit request timeout into account and
        // proceed.
        tmp = next_retrans_req_timeout_.msecTillTimeout();
        timeout = minTimeout(tmp, timeout);

        if ((0 != timeout) && (!process_more_right_now)) {
            // Set up the events to wait on.  Start with the wakeup pipe.
            memset(&poll_fds, 0, sizeof(poll_fds));
            poll_fds[0].fd     = wakeup_work_thread_evt_fd_;
            poll_fds[0].events = POLLIN;

            // Add the RX socket.
            poll_fds[1].fd     = sock_fd_;
            poll_fds[1].events = POLLIN;

            // Wait for something interesting to happen.
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

        clearEventFD(wakeup_work_thread_evt_fd_);
        process_more_right_now = false;

        // Step 2: Do we have a change of audio parameters (volume/stream_type)
        // to apply to our current substreams?  If so, go ahead and take care of
        // it.
        if (audio_params_dirty_) {
            float latched_left_volume, latched_right_volume;
            int latched_stream_type;
            {  // explicit scope for autolock pattern
                AutoMutex api_lock(&audio_param_lock_);
                latched_left_volume  = audio_volume_left_;
                latched_right_volume = audio_volume_right_;
                latched_stream_type  = audio_stream_type_;
                audio_params_dirty_  = false;
            }

            for (size_t i = 0; i < substreams_.size(); ++i) {
                CHECK(substreams_.valueAt(i) != NULL);
                substreams_.valueAt(i)->setAudioSpecificParams(
                        latched_left_volume,
                        latched_right_volume,
                        latched_stream_type);
            }
        }

        // Step 3: Do we have data waiting in the socket?  If so, drain the
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
                             " %d.%d.%d.%d:%hu", IP_PRINTK_HELPER(a), p);

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

        // Step 4: Process any data we mave have accumulated in the ring buffer
        // so far.
        if (!thread_wrapper_->exitPending()) {
            processRingBuffer();
        }

        // Step 5: At this point in time, the ring buffer should either be
        // empty, or stalled in front of a gap caused by some dropped packets.
        // Check on the current gap situation and deal with it in an appropriate
        // fashion.  If processGaps returns true, it means that it has given up
        // on a gap and that we should try to process some more data
        // immediately.
        if (!thread_wrapper_->exitPending()) {
            process_more_right_now = processGaps();
        }

        // Step 6: Check for fatal errors.  If any of our substreams has
        // encountered a fatal, unrecoverable, error, then propagate the error
        // up to user level and shut down.
        for (size_t i = 0; i < substreams_.size(); ++i) {
            status_t status;
            CHECK(substreams_.valueAt(i) != NULL);

            status = substreams_.valueAt(i)->getStatus();
            if (OK != status) {
                LOGE("Substream index %d has encountered an unrecoverable"
                     " error (%d).  Signalling application level and shutting"
                     " down.", i, status);
                sendEvent(MEDIA_ERROR);
                goto bailout;
            }
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
    bool     ret_val = true;

    // Every packet should be either a C&C NAK packet, or a TRTP packet.  The
    // shortest possible packet is a group membership NAK, which is only 4 bytes
    // long.  If our RXed packet is not at least 4 bytes long, then this is junk
    // and should be tossed.
    if (amt < 4) {
        LOGV("Dropping packet, too short to contain any valid data (%u bytes)",
             static_cast<uint32_t>(amt));
        goto drop_packet;
    }

    // Check to see if this is a special C&C NAK packet.
    nak_magic = ntohl(*(reinterpret_cast<uint32_t*>(data)));

    switch (nak_magic) {
        case TRTPPacket::kCNC_NakRetryRequestID:
            ret_val = processRetransmitNAK(data, amt);
            goto drop_packet;

        case TRTPPacket::kCNC_NakJoinGroupID:
            LOGI("Received group join NAK; signalling error and shutting down");
            ret_val = false;
            goto drop_packet;
    }

    // Every non C&C packet starts with an RTP header which is at least 12 bytes
    // If there are fewer than 12 bytes here, this cannot be a proper RTP
    // packet.
    if (amt < 12) {
        LOGV("Dropping packet, too short to contain RTP header (%u bytes)",
             static_cast<uint32_t>(amt));
        goto drop_packet;
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
    return ret_val;
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

            // Is this a command packet?  If so, its not necessarily associated
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
    uint8_t trtp_flags   =  data[13] & 0xF;

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

    bool do_cleanup_pass = false;
    uint16_t command_id = U16_AT(data + offset);
    uint8_t  program_id = (U32_AT(data + 8) >> 5) & 0x1F;
    offset += 2;

    switch (command_id) {
        case TRTPControlPacket::kCommandNop:
            // Note: NOPs are frequently used to carry timestamp transformation
            // updates.  If there was a timestamp transform attached to this
            // payload, it was already taken care of by processRX.
            break;

        case TRTPControlPacket::kCommandEOS:
            // Flag the substreams which are a member of this program as having
            // hit EOS.  Once in the EOS state, it is not possible to get out.
            // It is possible to pause and unpause, but the only way out would
            // be to seek, or to stop completely.  Both of these operations
            // would involve a flush, which would destroy and (possibly)
            // recreate a new the substream, getting rid of the EOS flag in the
            // process.
            for (size_t i = 0; i < substreams_.size(); ++i) {
                const sp<Substream>& stream = substreams_.valueAt(i);
                if (stream->getProgramID() == program_id) {
                    stream->signalEOS();
                }
            }
            break;

        case TRTPControlPacket::kCommandFlush: {
            LOGI("Flushing program_id=%d", program_id);

            // Flag any programs with the given program ID for cleanup.
            for (size_t i = 0; i < substreams_.size(); ++i) {
                const sp<Substream>& stream = substreams_.valueAt(i);
                if (stream->getProgramID() == program_id) {
                    stream->clearInactivityTimeout();
                }
            }

            // Make sure we do our cleanup pass at the end of this.
            do_cleanup_pass = true;
        } break;

        case TRTPControlPacket::kCommandAPU: {
            // Active program update packet.  Go over all of our substreams and
            // either reset the inactivity timer for the substreams listed in
            // this update packet, or clear the inactivity timer for the
            // substreams not listed in this update packet.  A cleared
            // inactivity timer will flag a substream for deletion in the
            // cleanup pass at the end of this function.

            // The packet must contain at least the 1 byte numActivePrograms
            // field.
            if (amt < offset + 1) {
                return;
            }
            uint8_t numActivePrograms = data[offset++];

            // If the payload is not long enough to contain the list it promises
            // to have, just skip it.
            if (amt < (offset + numActivePrograms)) {
                return;
            }

            // Clear all inactivity timers.
            for (size_t i = 0; i < substreams_.size(); ++i) {
                const sp<Substream>& stream = substreams_.valueAt(i);
                stream->clearInactivityTimeout();
            }

            // Now go over the list of active programs and reset the inactivity
            // timers for those streams which are currently in the active
            // program update packet.
            for (uint8_t j = 0; j < numActivePrograms; ++j) {
                uint8_t pid = (data[offset + j] & 0x1F);
                for (size_t i = 0; i < substreams_.size(); ++i) {
                    const sp<Substream>& stream = substreams_.valueAt(i);
                    if (stream->getProgramID() == pid) {
                        stream->resetInactivityTimeout();
                    }
                }
            }

            // Make sure we do our cleanup pass at the end of this.
            do_cleanup_pass = true;
        } break;
    }

    if (do_cleanup_pass)
        cleanoutExpiredSubstreams();
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
           (0 == next_retrans_req_timeout_.msecTillTimeout())) {
            // If out current gap is the fast-start gap, don't bother to skip it
            // because substreams look like the are about to underflow.
            if ((kGS_FastStartGap != gap_status) ||
                (current_gap_.end_seq_ != gap.end_seq_)) {

                for (size_t i = 0; i < substreams_.size(); ++i) {
                    if (substreams_.valueAt(i)->isAboutToUnderflow()) {
                        LOGI("About to underflow, giving up on gap [%hu, %hu]",
                                gap.start_seq_, gap.end_seq_);
                        ring_buffer_.processNAK();
                        setGapStatus(kGS_NoGap);
                        return true;
                    }
                }
            }

            // Looks like no one is about to underflow.  Just go ahead and send
            // the request.
            send_retransmit_request = true;
        }
    } else {
        setGapStatus(kGS_NoGap);
    }

    if (send_retransmit_request) {
        // If we have been working on a fast start, and it is still not filled
        // in, even after the extended retransmit time out, give up and skip it.
        // The system should fall back into its normal slow-start behavior.
        if ((kGS_FastStartGap == current_gap_status_) &&
            (current_gap_.end_seq_ == gap.end_seq_)) {
            LOGV("Fast start is taking forever; giving up.");
            ring_buffer_.processNAK();
            setGapStatus(kGS_NoGap);
            return true;
        }

        // Send the request.
        RetransRequest req;
        uint32_t magic  = (kGS_FastStartGap == gap_status)
                        ? TRTPPacket::kCNC_FastStartRequestID
                        : TRTPPacket::kCNC_RetryRequestID;
        req.magic_      = htonl(magic);
        req.mcast_ip_   = data_source_addr_.sin_addr.s_addr;
        req.mcast_port_ = data_source_addr_.sin_port;
        req.start_seq_  = htons(gap.start_seq_);
        req.end_seq_    = htons(gap.end_seq_);

        {
            uint32_t a = ntohl(transmitter_addr_.sin_addr.s_addr);
            uint16_t p = ntohs(transmitter_addr_.sin_port);
            LOGV("Sending to transmitter %d.%d.%d.%d:%hu",
                 IP_PRINTF_HELPER(a), p);
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
        setGapStatus(gap_status);
    }

    return false;
}

bool AAH_RXPlayer::processRetransmitNAK(const uint8_t* data, size_t amt) {
    if (amt < static_cast<ssize_t>(sizeof(RetransRequest))) {
        LOGV("Dropping packet, too short to contain NAK payload (%u bytes)",
             static_cast<uint32_t>(amt));
        return true;
    }

    SeqNoGap gap;
    const RetransRequest* rtr = reinterpret_cast<const RetransRequest*>(data);
    gap.start_seq_ = ntohs(rtr->start_seq_);
    gap.end_seq_   = ntohs(rtr->end_seq_);

    LOGI("Process NAK for gap at [%hu, %hu]", gap.start_seq_, gap.end_seq_);
    ring_buffer_.processNAK(&gap);

    return true;
}

void AAH_RXPlayer::setGapStatus(GapStatus status) {
    current_gap_status_ = status;

    switch(current_gap_status_) {
        case kGS_NormalGap:
            next_retrans_req_timeout_.setTimeout(kGapRerequestTimeoutMsec);
            break;

        case kGS_FastStartGap:
            next_retrans_req_timeout_.setTimeout(kFastStartTimeoutMsec);
            break;

        case kGS_NoGap:
        default:
            next_retrans_req_timeout_.setTimeout(-1);
            break;
    }
}

void AAH_RXPlayer::cleanoutExpiredSubstreams() {
    static const size_t kMaxPerPass = 32;
    uint32_t to_remove[kMaxPerPass];
    size_t cnt, i;

    do {
        for (i = 0, cnt = 0;
            (i < substreams_.size()) && (cnt < kMaxPerPass);
            ++i) {
            const sp<Substream>& stream = substreams_.valueAt(i);
            if (stream->shouldExpire()) {
                to_remove[cnt++] = stream->getSSRC();
            }
        }

        for (i = 0; i < cnt; ++i) {
            LOGI("Purging substream with SSRC 0x%08x", to_remove[i]);
            substreams_.removeItem(to_remove[i]);
        }
    } while (cnt >= kMaxPerPass);

    ss_cleanout_timeout_.setTimeout(kSSCleanoutTimeoutMsec);
}

void AAH_RXPlayer::sendUnicastGroupJoin() {
    if (!multicast_mode_ && (sock_fd_ >= 0)) {
        uint32_t tag = htonl(TRTPPacket::kCNC_JoinGroupID);
        uint32_t a = ntohl(data_source_addr_.sin_addr.s_addr);
        uint16_t p = ntohs(data_source_addr_.sin_port);

        LOGV("Sending group join to transmitter %d.%d.%d.%d:%hu",
             IP_PRINTF_HELPER(a), p);

        int res = sendto(sock_fd_, &tag, sizeof(tag), 0,
                         reinterpret_cast<struct sockaddr*>(&data_source_addr_),
                         sizeof(data_source_addr_));
        if (res < 0) {
            LOGW("Error sending group join to transmitter %d.%d.%d.%d:%hu"
                 " (errno %d)", IP_PRINTF_HELPER(a), p, errno);
        }

        // Reset the membership report timeout.  Use our fast timeout until we
        // have heard back from our transmitter at least once.
        unicast_group_report_timeout_.setTimeout(transmitter_known_
                ?  kGrpMemberSlowReportIntervalMsec
                :  kGrpMemberFastReportIntervalMsec);
    } else {
        LOGE("Attempted to send unicast group membership report while"
             " multicast_mode = %s and sock_fd = %d",
             multicast_mode_ ? "true" : "false", sock_fd_);
        unicast_group_report_timeout_.setTimeout(-1);
    }
}

void AAH_RXPlayer::sendUnicastGroupLeave() {
    if (!multicast_mode_ && (sock_fd_ >= 0)) {
        uint32_t tag = htonl(TRTPPacket::kCNC_LeaveGroupID);
        uint32_t a = ntohl(data_source_addr_.sin_addr.s_addr);
        uint16_t p = ntohs(data_source_addr_.sin_port);

        LOGI("Sending group leave to transmitter %d.%d.%d.%d:%hu",
             IP_PRINTF_HELPER(a), p);

        int res = sendto(sock_fd_, &tag, sizeof(tag), 0,
                         reinterpret_cast<struct sockaddr*>(&data_source_addr_),
                         sizeof(data_source_addr_));
        if (res < 0) {
            LOGW("Error sending group leave to transmitter %d.%d.%d.%d:%hu"
                 " (errno %d)", IP_PRINTF_HELPER(a), p, errno);
        }
    }
}

}  // namespace android
