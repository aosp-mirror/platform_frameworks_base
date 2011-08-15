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

#include "aah_rx_player.h"

namespace android {

AAH_RXPlayer::RXRingBuffer::RXRingBuffer(uint32_t capacity) {
    capacity_ = capacity;
    rd_ = wr_ = 0;
    ring_ = new PacketBuffer*[capacity];
    memset(ring_, 0, sizeof(PacketBuffer*) * capacity);
    reset();
}

AAH_RXPlayer::RXRingBuffer::~RXRingBuffer() {
    reset();
    delete[] ring_;
}

void AAH_RXPlayer::RXRingBuffer::reset() {
    AutoMutex lock(&lock_);

    if (NULL != ring_) {
        while (rd_ != wr_) {
            CHECK(rd_ < capacity_);
            if (NULL != ring_[rd_]) {
                PacketBuffer::destroy(ring_[rd_]);
                ring_[rd_] = NULL;
            }
            rd_ = (rd_ + 1) % capacity_;
        }
    }

    rd_ = wr_ = 0;
    rd_seq_known_ = false;
    waiting_for_fast_start_ = true;
    fetched_first_packet_ = false;
    rtp_activity_timeout_valid_ = false;
}

bool AAH_RXPlayer::RXRingBuffer::pushBuffer(PacketBuffer* buf,
                                                uint16_t seq) {
    AutoMutex lock(&lock_);
    CHECK(NULL != ring_);
    CHECK(NULL != buf);

    rtp_activity_timeout_valid_ = true;
    rtp_activity_timeout_ = monotonicUSecNow() + kRTPActivityTimeoutUSec;

    // If the ring buffer is totally reset (we have never received a single
    // payload) then we don't know the rd sequence number and this should be
    // simple.  We just store the payload, advance the wr pointer and record the
    // initial sequence number.
    if (!rd_seq_known_) {
        CHECK(rd_ == wr_);
        CHECK(NULL == ring_[wr_]);
        CHECK(wr_ < capacity_);

        ring_[wr_] = buf;
        wr_ = (wr_ + 1) % capacity_;
        rd_seq_ = seq;
        rd_seq_known_ = true;
        return true;
    }

    // Compute the seqence number of this payload and of the write pointer,
    // normalized around the read pointer.  IOW - transform the payload seq no
    // and the wr pointer seq no into a space where the rd pointer seq no is
    // zero.  This will define 4 cases we can consider...
    //
    // 1) norm_seq == norm_wr_seq
    //    This payload is contiguous with the last.  All is good.
    //
    // 2)  ((norm_seq <  norm_wr_seq) && (norm_seq >= norm_rd_seq)
    // aka ((norm_seq <  norm_wr_seq) && (norm_seq >= 0)
    //    This payload is in the past, in the unprocessed region of the ring
    //    buffer.  It is probably a retransmit intended to fill in a dropped
    //    payload; it may be a duplicate.
    //
    // 3) ((norm_seq - norm_wr_seq) & 0x8000) != 0
    //    This payload is in the past compared to the write pointer (or so very
    //    far in the future that it has wrapped the seq no space), but not in
    //    the unprocessed region of the ring buffer.  This could be a duplicate
    //    retransmit; we just drop these payloads unless we are waiting for our
    //    first fast start packet.  If we are waiting for fast start, than this
    //    packet is probably the first packet of the fast start retransmission.
    //    If it will fit in the buffer, back up the read pointer to its position
    //    and clear the fast start flag, otherwise just drop it.
    //
    // 4) ((norm_seq - norm_wr_seq) & 0x8000) == 0
    //    This payload which is ahead of the next write pointer.  This indicates
    //    that we have missed some payloads and need to request a retransmit.
    //    If norm_seq >= (capacity - 1), then the gap is so large that it would
    //    overflow the ring buffer and we should probably start to panic.

    uint16_t norm_wr_seq = ((wr_ + capacity_ - rd_) % capacity_);
    uint16_t norm_seq    = seq - rd_seq_;

    // Check for overflow first.
    if ((!(norm_seq & 0x8000)) && (norm_seq >= (capacity_ - 1))) {
        LOGW("Ring buffer overflow; cap = %u, [rd, wr] = [%hu, %hu], seq = %hu",
             capacity_, rd_seq_, norm_wr_seq + rd_seq_, seq);
        PacketBuffer::destroy(buf);
        return false;
    }

    // Check for case #1
    if (norm_seq == norm_wr_seq) {
        CHECK(wr_ < capacity_);
        CHECK(NULL == ring_[wr_]);

        ring_[wr_] = buf;
        wr_ = (wr_ + 1) % capacity_;

        CHECK(wr_ != rd_);
        return true;
    }

    // Check case #2
    uint32_t ring_pos = (rd_ + norm_seq) % capacity_;
    if ((norm_seq < norm_wr_seq) && (!(norm_seq & 0x8000))) {
        // Do we already have a payload for this slot?  If so, then this looks
        // like a duplicate retransmit.  Just ignore it.
        if (NULL != ring_[ring_pos]) {
            LOGD("RXed duplicate retransmit, seq = %hu", seq);
            PacketBuffer::destroy(buf);
        } else {
            // Looks like we were missing this payload.  Go ahead and store it.
            ring_[ring_pos] = buf;
        }

        return true;
    }

    // Check case #3
    if ((norm_seq - norm_wr_seq) & 0x8000) {
        if (!waiting_for_fast_start_) {
            LOGD("RXed duplicate retransmit from before rd pointer, seq = %hu",
                 seq);
            PacketBuffer::destroy(buf);
        } else {
            // Looks like a fast start fill-in.  Go ahead and store it, assuming
            // that we can fit it in the buffer.
            uint32_t implied_ring_size = static_cast<uint32_t>(norm_wr_seq)
                                       + (rd_seq_ - seq);

            if (implied_ring_size >= (capacity_ - 1)) {
                LOGD("RXed what looks like a fast start packet (seq = %hu),"
                     " but packet is too far in the past to fit into the ring"
                     "  buffer.  Dropping.", seq);
                PacketBuffer::destroy(buf);
            } else {
                ring_pos = (rd_ + capacity_ + seq - rd_seq_) % capacity_;
                rd_seq_ = seq;
                rd_ = ring_pos;
                waiting_for_fast_start_ = false;

                CHECK(ring_pos < capacity_);
                CHECK(NULL == ring_[ring_pos]);
                ring_[ring_pos] = buf;
            }

        }
        return true;
    }

    // Must be in case #4 with no overflow.  This packet fits in the current
    // ring buffer, but is discontiuguous.  Advance the write pointer leaving a
    // gap behind.
    uint32_t gap_len = (ring_pos + capacity_ - wr_) % capacity_;
    LOGD("Drop detected; %u packets, seq_range [%hu, %hu]",
         gap_len,
         rd_seq_ + norm_wr_seq,
         rd_seq_ + norm_wr_seq + gap_len - 1);

    CHECK(NULL == ring_[ring_pos]);
    ring_[ring_pos] = buf;
    wr_ = (ring_pos + 1) % capacity_;
    CHECK(wr_ != rd_);

    return true;
}

AAH_RXPlayer::PacketBuffer*
AAH_RXPlayer::RXRingBuffer::fetchBuffer(bool* is_discon) {
    AutoMutex lock(&lock_);
    CHECK(NULL != ring_);
    CHECK(NULL != is_discon);

    // If the read seqence number is not known, then this ring buffer has not
    // received a packet since being reset and there cannot be any packets to
    // return.  If we are still waiting for the first fast start packet to show
    // up, we don't want to let any buffer be consumed yet because we expect to
    // see a packet before the initial read sequence number show up shortly.
    if (!rd_seq_known_ || waiting_for_fast_start_) {
        *is_discon = false;
        return NULL;
    }

    PacketBuffer* ret = NULL;
    *is_discon = !fetched_first_packet_;

    while ((rd_ != wr_) && (NULL == ret)) {
        CHECK(rd_ < capacity_);

        // If we hit a gap, stall and do not advance the read pointer.  Let the
        // higher level code deal with requesting retries and/or deciding to
        // skip the current gap.
        ret = ring_[rd_];
        if (NULL == ret) {
            break;
        }

        ring_[rd_] = NULL;
        rd_ = (rd_ + 1) % capacity_;
        ++rd_seq_;
    }

    if (NULL != ret) {
        fetched_first_packet_ = true;
    }

    return ret;
}

AAH_RXPlayer::GapStatus
AAH_RXPlayer::RXRingBuffer::fetchCurrentGap(SeqNoGap* gap) {
    AutoMutex lock(&lock_);
    CHECK(NULL != ring_);
    CHECK(NULL != gap);

    // If the read seqence number is not known, then this ring buffer has not
    // received a packet since being reset and there cannot be any gaps.
    if (!rd_seq_known_) {
        return kGS_NoGap;
    }

    // If we are waiting for fast start, then the current gap is a fast start
    // gap and it includes all packets before the read sequence number.
    if (waiting_for_fast_start_) {
        gap->start_seq_ =
        gap->end_seq_   = rd_seq_ - 1;
        return kGS_FastStartGap;
    }

    // If rd == wr, then the buffer is empty and there cannot be any gaps.
    if (rd_ == wr_) {
        return kGS_NoGap;
    }

    // If rd_ is currently pointing at an unprocessed packet, then there is no
    // current gap.
    CHECK(rd_ < capacity_);
    if (NULL != ring_[rd_]) {
        return kGS_NoGap;
    }

    // Looks like there must be a gap here.  The start of the gap is the current
    // rd sequence number, all we need to do now is determine its length in
    // order to compute the end sequence number.
    gap->start_seq_ = rd_seq_;
    uint16_t end = rd_seq_;
    uint32_t tmp = (rd_ + 1) % capacity_;
    while ((tmp != wr_) && (NULL == ring_[tmp])) {
        ++end;
        tmp = (tmp + 1) % capacity_;
    }
    gap->end_seq_ = end;

    return kGS_NormalGap;
}

void AAH_RXPlayer::RXRingBuffer::processNAK(SeqNoGap* nak) {
    AutoMutex lock(&lock_);
    CHECK(NULL != ring_);

    // If we were waiting for our first fast start fill-in packet, and we
    // received a NAK, then apparantly we are not getting our fast start.  Just
    // clear the waiting flag and go back to normal behavior.
    if (waiting_for_fast_start_) {
        waiting_for_fast_start_ = false;
    }

    // If we have not received a packet since last reset, or there is no data in
    // the ring, then there is nothing to skip.
    if ((!rd_seq_known_) || (rd_ == wr_)) {
        return;
    }

    // If rd_ is currently pointing at an unprocessed packet, then there is no
    // gap to skip.
    CHECK(rd_ < capacity_);
    if (NULL != ring_[rd_]) {
        return;
    }

    // Looks like there must be a gap here.  Advance rd until we have passed
    // over the portion of it indicated by nak (or all of the gap if nak is
    // NULL).  Then reset fetched_first_packet_ so that the next read will show
    // up as being discontiguous.
    uint16_t seq_after_gap = (NULL == nak) ? 0 : nak->end_seq_ + 1;
    while ((rd_ != wr_) &&
           (NULL == ring_[rd_]) &&
          ((NULL == nak) || (seq_after_gap != rd_seq_))) {
        rd_ = (rd_ + 1) % capacity_;
        ++rd_seq_;
    }
    fetched_first_packet_ = false;
}

int AAH_RXPlayer::RXRingBuffer::computeInactivityTimeout() {
    AutoMutex lock(&lock_);

    if (!rtp_activity_timeout_valid_) {
        return -1;
    }

    uint64_t now = monotonicUSecNow();
    if (rtp_activity_timeout_ <= now) {
        return 0;
    }

    return (rtp_activity_timeout_ - now) / 1000;
}

AAH_RXPlayer::PacketBuffer*
AAH_RXPlayer::PacketBuffer::allocate(ssize_t length) {
    if (length <= 0) {
        return NULL;
    }

    uint32_t alloc_len = sizeof(PacketBuffer) + length;
    PacketBuffer* ret = reinterpret_cast<PacketBuffer*>(
                        new uint8_t[alloc_len]);

    if (NULL != ret) {
        ret->length_ = length;
    }

    return ret;
}

void AAH_RXPlayer::PacketBuffer::destroy(PacketBuffer* pb) {
    uint8_t* kill_me = reinterpret_cast<uint8_t*>(pb);
    delete[] kill_me;
}

}  // namespace android
