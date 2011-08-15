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

#ifndef __AAH_RX_PLAYER_H__
#define __AAH_RX_PLAYER_H__

#include <media/MediaPlayerInterface.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/OMXClient.h>
#include <netinet/in.h>
#include <utils/KeyedVector.h>
#include <utils/LinearTransform.h>
#include <utils/threads.h>

#include "aah_decoder_pump.h"
#include "pipe_event.h"

namespace android {

class AAH_RXPlayer : public MediaPlayerInterface {
  public:
    AAH_RXPlayer();

    virtual status_t    initCheck();
    virtual status_t    setDataSource(const char *url,
                                      const KeyedVector<String8, String8>*
                                      headers);
    virtual status_t    setDataSource(int fd, int64_t offset, int64_t length);
    virtual status_t    setVideoSurface(const sp<Surface>& surface);
    virtual status_t    setVideoSurfaceTexture(const sp<ISurfaceTexture>&
                                               surfaceTexture);
    virtual status_t    prepare();
    virtual status_t    prepareAsync();
    virtual status_t    start();
    virtual status_t    stop();
    virtual status_t    pause();
    virtual bool        isPlaying();
    virtual status_t    seekTo(int msec);
    virtual status_t    getCurrentPosition(int *msec);
    virtual status_t    getDuration(int *msec);
    virtual status_t    reset();
    virtual status_t    setLooping(int loop);
    virtual player_type playerType();
    virtual status_t    setParameter(int key, const Parcel &request);
    virtual status_t    getParameter(int key, Parcel *reply);
    virtual status_t    invoke(const Parcel& request, Parcel *reply);

  protected:
    virtual ~AAH_RXPlayer();

  private:
    class ThreadWrapper : public Thread {
      public:
        friend class AAH_RXPlayer;
        explicit ThreadWrapper(AAH_RXPlayer& player)
            : Thread(false /* canCallJava */ )
            , player_(player) { }

        virtual bool threadLoop() { return player_.threadLoop(); }

      private:
        AAH_RXPlayer& player_;

        DISALLOW_EVIL_CONSTRUCTORS(ThreadWrapper);
    };

#pragma pack(push, 1)
    struct PacketBuffer {
        ssize_t length_;
        uint8_t data_[0];

        // TODO : consider changing this to be some form of ring buffer or free
        // pool system instead of just using the heap in order to avoid heap
        // fragmentation.
        static PacketBuffer* allocate(ssize_t length);
        static void destroy(PacketBuffer* pb);

      private:
        // Force people to use allocate/destroy instead of new/delete.
        PacketBuffer() { }
        ~PacketBuffer() { }
    };

    struct RetransRequest {
        uint32_t magic_;
        uint32_t mcast_ip_;
        uint16_t mcast_port_;
        uint16_t start_seq_;
        uint16_t end_seq_;
    };
#pragma pack(pop)

    enum GapStatus {
        kGS_NoGap = 0,
        kGS_NormalGap,
        kGS_FastStartGap,
    };

    struct SeqNoGap {
        uint16_t start_seq_;
        uint16_t end_seq_;
    };

    class RXRingBuffer {
      public:
        explicit RXRingBuffer(uint32_t capacity);
        ~RXRingBuffer();

        bool initCheck() const { return (ring_ != NULL); }
        void reset();

        // Push a packet buffer with a given sequence number into the ring
        // buffer.  pushBuffer will always consume the buffer pushed to it,
        // either destroying it because it was a duplicate or overflow, or
        // holding on to it in the ring.  Callers should not hold any references
        // to PacketBuffers after they have been pushed to the ring.  Returns
        // false in the case of a serious error (such as ring overflow).
        // Callers should consider resetting the pipeline entirely in the event
        // of a serious error.
        bool pushBuffer(PacketBuffer* buf, uint16_t seq);

        // Fetch the next buffer in the RTP sequence.  Returns NULL if there is
        // no buffer to fetch.  If a non-NULL PacketBuffer is returned,
        // is_discon will be set to indicate whether or not this PacketBuffer is
        // discontiuous with any previously returned packet buffers.  Packet
        // buffers returned by fetchBuffer are the caller's responsibility; they
        // must be certain to destroy the buffers when they are done.
        PacketBuffer* fetchBuffer(bool* is_discon);

        // Returns true and fills out the gap structure if the read pointer of
        // the ring buffer is currently pointing to a gap which would stall a
        // fetchBuffer operation.  Returns false if the read pointer is not
        // pointing to a gap in the sequence currently.
        GapStatus fetchCurrentGap(SeqNoGap* gap);

        // Causes the read pointer to skip over any portion of a gap indicated
        // by nak.  If nak is NULL, any gap currently blocking the read pointer
        // will be completely skipped.  If any portion of a gap is skipped, the
        // next successful read from fetch buffer will indicate a discontinuity.
        void processNAK(SeqNoGap* nak = NULL);

        // Compute the number of milliseconds until the inactivity timer for
        // this RTP stream.  Returns -1 if there is no active timeout, or 0 if
        // the system has already timed out.
        int computeInactivityTimeout();

      private:
        Mutex          lock_;
        PacketBuffer** ring_;
        uint32_t       capacity_;
        uint32_t       rd_;
        uint32_t       wr_;

        uint16_t       rd_seq_;
        bool           rd_seq_known_;
        bool           waiting_for_fast_start_;
        bool           fetched_first_packet_;

        uint64_t       rtp_activity_timeout_;
        bool           rtp_activity_timeout_valid_;

        DISALLOW_EVIL_CONSTRUCTORS(RXRingBuffer);
    };

    class Substream : public virtual RefBase {
      public:
        Substream(uint32_t ssrc, OMXClient& omx);

        void cleanupBufferInProgress();
        void shutdown();
        void processPayloadStart(uint8_t* buf,
                                 uint32_t amt,
                                 int32_t ts_lower);
        void processPayloadCont (uint8_t* buf,
                                 uint32_t amt);
        void processCompletedBuffer();
        void processTSTransform(const LinearTransform& trans);

        bool     isAboutToUnderflow();
        uint32_t getSSRC()      const { return ssrc_; }
        uint16_t getProgramID() const { return (ssrc_ >> 5) & 0x1F; }

      protected:
        virtual ~Substream() {
            shutdown();
        }

      private:
        void                cleanupDecoder();
        bool                setupSubstreamType(uint8_t substream_type,
                                               uint8_t codec_type);

        uint32_t            ssrc_;
        bool                waiting_for_rap_;

        bool                substream_details_known_;
        uint8_t             substream_type_;
        uint8_t             codec_type_;
        sp<MetaData>        substream_meta_;

        MediaBuffer*        buffer_in_progress_;
        uint32_t            expected_buffer_size_;
        uint32_t            buffer_filled_;

        sp<AAH_DecoderPump> decoder_;

        static int64_t      kAboutToUnderflowThreshold;

        DISALLOW_EVIL_CONSTRUCTORS(Substream);
    };

    typedef DefaultKeyedVector< uint32_t, sp<Substream> > SubstreamVec;

    status_t            startWorkThread();
    void                stopWorkThread();
    virtual bool        threadLoop();
    bool                setupSocket();
    void                cleanupSocket();
    void                resetPipeline();
    void                reset_l();
    bool                processRX(PacketBuffer* pb);
    void                processRingBuffer();
    void                processCommandPacket(PacketBuffer* pb);
    bool                processGaps();
    int                 computeNextGapRetransmitTimeout();
    void                fetchAudioFlinger();

    PipeEvent           wakeup_work_thread_evt_;
    sp<ThreadWrapper>   thread_wrapper_;
    Mutex               api_lock_;
    bool                is_playing_;
    bool                data_source_set_;

    struct sockaddr_in  listen_addr_;
    int                 sock_fd_;
    bool                multicast_joined_;

    struct sockaddr_in  transmitter_addr_;
    bool                transmitter_known_;

    uint32_t            current_epoch_;
    bool                current_epoch_known_;

    SeqNoGap            current_gap_;
    GapStatus           current_gap_status_;
    uint64_t            next_retrans_req_time_;

    RXRingBuffer        ring_buffer_;
    SubstreamVec        substreams_;
    OMXClient           omx_;

    // Connection to audio flinger used to hack a path to setMasterVolume.
    sp<IAudioFlinger>   audio_flinger_;

    static const uint32_t kRTPRingBufferSize;
    static const uint32_t kRetransRequestMagic;
    static const uint32_t kFastStartRequestMagic;
    static const uint32_t kRetransNAKMagic;
    static const uint32_t kGapRerequestTimeoutUSec;
    static const uint32_t kFastStartTimeoutUSec;
    static const uint32_t kRTPActivityTimeoutUSec;

    static const uint32_t INVOKE_GET_MASTER_VOLUME = 3;
    static const uint32_t INVOKE_SET_MASTER_VOLUME = 4;

    static uint64_t monotonicUSecNow();

    DISALLOW_EVIL_CONSTRUCTORS(AAH_RXPlayer);
};

}  // namespace android

#endif  // __AAH_RX_PLAYER_H__
