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

//#define LOG_NDEBUG 0
#define LOG_TAG "OMXDecoder"
#include <utils/Log.h>

#undef NDEBUG
#include <assert.h>

#include <OMX_Component.h>

#include <media/stagefright/ESDS.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/OMXDecoder.h>

namespace android {

class OMXMediaBuffer : public MediaBuffer {
public:
    OMXMediaBuffer(IOMX::buffer_id buffer_id, const sp<IMemory> &mem)
        : MediaBuffer(mem->pointer(),
                      mem->size()),
          mBufferID(buffer_id),
          mMem(mem) {
    }

    IOMX::buffer_id buffer_id() const { return mBufferID; }

private:
    IOMX::buffer_id mBufferID;
    sp<IMemory> mMem;

    OMXMediaBuffer(const OMXMediaBuffer &);
    OMXMediaBuffer &operator=(const OMXMediaBuffer &);
};

struct CodecInfo {
    const char *mime;
    const char *codec;
};

static const CodecInfo kDecoderInfo[] = {
    { "audio/mpeg", "OMX.PV.mp3dec" },
    { "audio/3gpp", "OMX.PV.amrdec" },
    { "audio/mp4a-latm", "OMX.PV.aacdec" },
    { "video/mp4v-es", "OMX.qcom.video.decoder.mpeg4" },
    { "video/mp4v-es", "OMX.PV.mpeg4dec" },
    { "video/3gpp", "OMX.qcom.video.decoder.h263" },
    { "video/3gpp", "OMX.PV.h263dec" },
    { "video/avc", "OMX.qcom.video.decoder.avc" },
    { "video/avc", "OMX.PV.avcdec" },
};

static const CodecInfo kEncoderInfo[] = {
    { "audio/3gpp", "OMX.PV.amrencnb" },
    { "audio/mp4a-latm", "OMX.PV.aacenc" },
    { "video/mp4v-es", "OMX.qcom.video.encoder.mpeg4" },
    { "video/mp4v-es", "OMX.PV.mpeg4enc" },
    { "video/3gpp", "OMX.qcom.video.encoder.h263" },
    { "video/3gpp", "OMX.PV.h263enc" },
    { "video/avc", "OMX.PV.avcenc" },
};

static const char *GetCodec(const CodecInfo *info, size_t numInfos,
                            const char *mime, int index) {
    assert(index >= 0);
    for(size_t i = 0; i < numInfos; ++i) {
        if (!strcasecmp(mime, info[i].mime)) {
            if (index == 0) {
                return info[i].codec;
            }

            --index;
        }
    }

    return NULL;
}

// static
OMXDecoder *OMXDecoder::Create(OMXClient *client, const sp<MetaData> &meta) {
    const char *mime;
    bool success = meta->findCString(kKeyMIMEType, &mime);
    assert(success);

    sp<IOMX> omx = client->interface();

    const char *codec = NULL;
    IOMX::node_id node = 0;
    for (int index = 0;; ++index) {
        codec = GetCodec(
                kDecoderInfo, sizeof(kDecoderInfo) / sizeof(kDecoderInfo[0]),
                mime, index);

        if (!codec) {
            return NULL;
        }

        LOGI("Attempting to allocate OMX node '%s'", codec);

        status_t err = omx->allocate_node(codec, &node);
        if (err == OK) {
            break;
        }
    }

    OMXDecoder *decoder = new OMXDecoder(client, node, mime, codec);

    uint32_t type;
    const void *data;
    size_t size;
    if (meta->findData(kKeyESDS, &type, &data, &size)) {
        ESDS esds((const char *)data, size);
        assert(esds.InitCheck() == OK);
        
        const void *codec_specific_data;
        size_t codec_specific_data_size;
        esds.getCodecSpecificInfo(
                &codec_specific_data, &codec_specific_data_size);

        printf("found codec specific data of size %d\n",
               codec_specific_data_size);

        decoder->addCodecSpecificData(
                codec_specific_data, codec_specific_data_size);
    } else if (meta->findData(kKeyAVCC, &type, &data, &size)) {
        printf("found avcc of size %d\n", size);

        const uint8_t *ptr = (const uint8_t *)data + 6;
        size -= 6;
        while (size >= 2) {
            size_t length = ptr[0] << 8 | ptr[1];

            ptr += 2;
            size -= 2;

            // printf("length = %d, size = %d\n", length, size);

            assert(size >= length);

            decoder->addCodecSpecificData(ptr, length);

            ptr += length;
            size -= length;

            if (size <= 1) {
                break;
            }

            ptr++;  // XXX skip trailing 0x01 byte???
            --size;
        }
    }

    return decoder;
}

// static
OMXDecoder *OMXDecoder::CreateEncoder(
        OMXClient *client, const sp<MetaData> &meta) {
    const char *mime;
    bool success = meta->findCString(kKeyMIMEType, &mime);
    assert(success);

    sp<IOMX> omx = client->interface();

    const char *codec = NULL;
    IOMX::node_id node = 0;
    for (int index = 0;; ++index) {
        codec = GetCodec(
                kEncoderInfo, sizeof(kEncoderInfo) / sizeof(kEncoderInfo[0]),
                mime, index);

        if (!codec) {
            return NULL;
        }

        LOGI("Attempting to allocate OMX node '%s'", codec);

        status_t err = omx->allocate_node(codec, &node);
        if (err == OK) {
            break;
        }
    }

    OMXDecoder *encoder = new OMXDecoder(client, node, mime, codec);

    return encoder;
}

OMXDecoder::OMXDecoder(OMXClient *client, IOMX::node_id node,
                       const char *mime, const char *codec)
    : mClient(client),
      mOMX(mClient->interface()),
      mNode(node),
      mComponentName(strdup(codec)),
      mIsMP3(!strcasecmp(mime, "audio/mpeg")),
      mSource(NULL),
      mCodecSpecificDataIterator(mCodecSpecificData.begin()),
      mState(OMX_StateLoaded),
      mPortStatusMask(kPortStatusActive << 2 | kPortStatusActive),
      mShutdownInitiated(false),
      mDealer(new MemoryDealer(3 * 1024 * 1024)),
      mSeeking(false),
      mStarted(false),
      mErrorCondition(OK),
      mReachedEndOfInput(false) {
    mClient->registerObserver(mNode, this);

    mBuffers.push();  // input buffers
    mBuffers.push();  // output buffers
}

OMXDecoder::~OMXDecoder() {
    if (mStarted) {
        stop();
    }

    for (List<CodecSpecificData>::iterator it = mCodecSpecificData.begin();
         it != mCodecSpecificData.end(); ++it) {
        free((*it).data);
    }
    mCodecSpecificData.clear();

    mClient->unregisterObserver(mNode);

    status_t err = mOMX->free_node(mNode);
    assert(err == OK);
    mNode = 0;

    free(mComponentName);
    mComponentName = NULL;
}

void OMXDecoder::setSource(MediaSource *source) {
    Mutex::Autolock autoLock(mLock);

    assert(mSource == NULL);

    mSource = source;
    setup();
}

status_t OMXDecoder::start(MetaData *) {
    assert(!mStarted);

    // mDealer->dump("Decoder Dealer");

    sp<MetaData> params = new MetaData;
    if (!strcmp(mComponentName, "OMX.qcom.video.decoder.avc")) {
        params->setInt32(kKeyNeedsNALFraming, true);
    }

    status_t err = mSource->start(params.get());

    if (err != OK) {
        return err;
    }

    postStart();

    mStarted = true;

    return OK;
}

status_t OMXDecoder::stop() {
    assert(mStarted);

    LOGI("Initiating OMX Node shutdown, busy polling.");
    initiateShutdown();

    // Important: initiateShutdown must be called first, _then_ release
    // buffers we're holding onto.
    while (!mOutputBuffers.empty()) {
        MediaBuffer *buffer = *mOutputBuffers.begin();
        mOutputBuffers.erase(mOutputBuffers.begin());

        LOGV("releasing buffer %p.", buffer->data());

        buffer->release();
        buffer = NULL;
    }

    int attempt = 1;
    while (mState != OMX_StateLoaded && attempt < 10) {
        usleep(100000);

        ++attempt;
    }

    if (mState != OMX_StateLoaded) {
        LOGE("!!! OMX Node '%s' did NOT shutdown cleanly !!!", mComponentName);
    } else {
        LOGI("OMX Node '%s' has shutdown cleanly.", mComponentName);
    }

    mSource->stop();

    mCodecSpecificDataIterator = mCodecSpecificData.begin();
    mShutdownInitiated = false;
    mSeeking = false;
    mStarted = false;
    mErrorCondition = OK;
    mReachedEndOfInput = false;

    return OK;
}

sp<MetaData> OMXDecoder::getFormat() {
    return mOutputFormat;
}

status_t OMXDecoder::read(
        MediaBuffer **out, const ReadOptions *options) {
    assert(mStarted);

    *out = NULL;

    Mutex::Autolock autoLock(mLock);

    if (mErrorCondition != OK && mErrorCondition != ERROR_END_OF_STREAM) {
        // Errors are sticky.
        return mErrorCondition;
    }

    int64_t seekTimeUs;
    if (options && options->getSeekTo(&seekTimeUs)) {
        LOGI("[%s] seeking to %lld us", mComponentName, seekTimeUs);

        mErrorCondition = OK;
        mReachedEndOfInput = false;

        setPortStatus(kPortIndexInput, kPortStatusFlushing);
        setPortStatus(kPortIndexOutput, kPortStatusFlushing);

        mSeeking = true;
        mSeekTimeUs = seekTimeUs;

        while (!mOutputBuffers.empty()) {
            OMXMediaBuffer *buffer =
                static_cast<OMXMediaBuffer *>(*mOutputBuffers.begin());

            // We could have used buffer->release() instead, but we're
            // holding the lock and signalBufferReturned attempts to acquire
            // the lock.
            buffer->claim();
            mBuffers.editItemAt(
                    kPortIndexOutput).push_back(buffer->buffer_id());
            buffer = NULL;

            mOutputBuffers.erase(mOutputBuffers.begin());
        }

        status_t err = mOMX->send_command(mNode, OMX_CommandFlush, -1);
        assert(err == OK);

        // Once flushing is completed buffers will again be scheduled to be
        // filled/emptied.
    }

    while (mErrorCondition == OK && mOutputBuffers.empty()) {
        mOutputBufferAvailable.wait(mLock);
    }

    if (!mOutputBuffers.empty()) {
        MediaBuffer *buffer = *mOutputBuffers.begin();
        mOutputBuffers.erase(mOutputBuffers.begin());

        *out = buffer;

        return OK;
    } else {
        assert(mErrorCondition != OK);
        return mErrorCondition;
    }
}

void OMXDecoder::addCodecSpecificData(const void *data, size_t size) {
    CodecSpecificData specific;
    specific.data = malloc(size);
    memcpy(specific.data, data, size);
    specific.size = size;

    mCodecSpecificData.push_back(specific);
    mCodecSpecificDataIterator = mCodecSpecificData.begin();
}

void OMXDecoder::onOMXMessage(const omx_message &msg) {
    Mutex::Autolock autoLock(mLock);

    switch (msg.type) {
        case omx_message::START:
        {
            onStart();
            break;
        }

        case omx_message::EVENT:
        {
            onEvent(msg.u.event_data.event, msg.u.event_data.data1,
                    msg.u.event_data.data2);
            break;
        }

        case omx_message::EMPTY_BUFFER_DONE:
        {
            onEmptyBufferDone(msg.u.buffer_data.buffer);
            break;
        }

        case omx_message::FILL_BUFFER_DONE:
        case omx_message::INITIAL_FILL_BUFFER:
        {
            onFillBufferDone(msg);
            break;
        }

        default:
            LOGE("received unknown omx_message type %d", msg.type);
            break;
    }
}

void OMXDecoder::setAMRFormat() {
    OMX_AUDIO_PARAM_AMRTYPE def;
    def.nSize = sizeof(def);
    def.nVersion.s.nVersionMajor = 1;
    def.nVersion.s.nVersionMinor = 1;
    def.nPortIndex = kPortIndexInput;

    status_t err =
        mOMX->get_parameter(mNode, OMX_IndexParamAudioAmr, &def, sizeof(def));

    assert(err == NO_ERROR);

    def.eAMRFrameFormat = OMX_AUDIO_AMRFrameFormatFSF;
    def.eAMRBandMode = OMX_AUDIO_AMRBandModeNB0;

    err = mOMX->set_parameter(mNode, OMX_IndexParamAudioAmr, &def, sizeof(def));
    assert(err == NO_ERROR);
}

void OMXDecoder::setAACFormat() {
    OMX_AUDIO_PARAM_AACPROFILETYPE def;
    def.nSize = sizeof(def);
    def.nVersion.s.nVersionMajor = 1;
    def.nVersion.s.nVersionMinor = 1;
    def.nPortIndex = kPortIndexInput;

    status_t err =
        mOMX->get_parameter(mNode, OMX_IndexParamAudioAac, &def, sizeof(def));
    assert(err == NO_ERROR);

    def.eAACStreamFormat = OMX_AUDIO_AACStreamFormatMP4ADTS;

    err = mOMX->set_parameter(mNode, OMX_IndexParamAudioAac, &def, sizeof(def));
    assert(err == NO_ERROR);
}

void OMXDecoder::setVideoOutputFormat(OMX_U32 width, OMX_U32 height) {
    LOGI("setVideoOutputFormat width=%ld, height=%ld", width, height);

    OMX_PARAM_PORTDEFINITIONTYPE def;
    OMX_VIDEO_PORTDEFINITIONTYPE *video_def = &def.format.video;

    bool is_encoder = strstr(mComponentName, ".encoder.") != NULL;  // XXX

    def.nSize = sizeof(def);
    def.nVersion.s.nVersionMajor = 1;
    def.nVersion.s.nVersionMinor = 1;
    def.nPortIndex = is_encoder ? kPortIndexOutput : kPortIndexInput;

    status_t err = mOMX->get_parameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));

    assert(err == NO_ERROR);

#if 1
    // XXX Need a (much) better heuristic to compute input buffer sizes.
    const size_t X = 64 * 1024;
    if (def.nBufferSize < X) {
        def.nBufferSize = X;
    }
#endif

    assert(def.eDomain == OMX_PortDomainVideo);
    
    video_def->nFrameWidth = width;
    video_def->nFrameHeight = height;
    // video_def.eCompressionFormat = OMX_VIDEO_CodingAVC;
    video_def->eColorFormat = OMX_COLOR_FormatUnused;

    err = mOMX->set_parameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));
    assert(err == NO_ERROR);

    ////////////////////////////////////////////////////////////////////////////

    def.nSize = sizeof(def);
    def.nVersion.s.nVersionMajor = 1;
    def.nVersion.s.nVersionMinor = 1;
    def.nPortIndex = is_encoder ? kPortIndexInput : kPortIndexOutput;

    err = mOMX->get_parameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));
    assert(err == NO_ERROR);

    assert(def.eDomain == OMX_PortDomainVideo);
    
    def.nBufferSize =
        (((width + 15) & -16) * ((height + 15) & -16) * 3) / 2;  // YUV420

    video_def->nFrameWidth = width;
    video_def->nFrameHeight = height;
    video_def->nStride = width;
    // video_def->nSliceHeight = height;
    video_def->eCompressionFormat = OMX_VIDEO_CodingUnused;
//    video_def->eColorFormat = OMX_COLOR_FormatYUV420Planar;

    err = mOMX->set_parameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));
    assert(err == NO_ERROR);
}

void OMXDecoder::setup() {
    const sp<MetaData> &meta = mSource->getFormat();

    const char *mime;
    bool success = meta->findCString(kKeyMIMEType, &mime);
    assert(success);

    if (!strcasecmp(mime, "audio/3gpp")) {
        setAMRFormat();
    } else if (!strcasecmp(mime, "audio/mp4a-latm")) {
        setAACFormat();
    } else if (!strncasecmp(mime, "video/", 6)) {
        int32_t width, height;
        bool success = meta->findInt32(kKeyWidth, &width);
        success = success && meta->findInt32(kKeyHeight, &height);
        assert(success);

        setVideoOutputFormat(width, height);
    }

    // dumpPortDefinition(0);
    // dumpPortDefinition(1);

    mOutputFormat = new MetaData;
    mOutputFormat->setCString(kKeyDecoderComponent, mComponentName);

    OMX_PARAM_PORTDEFINITIONTYPE def;
    def.nSize = sizeof(def);
    def.nVersion.s.nVersionMajor = 1;
    def.nVersion.s.nVersionMinor = 1;
    def.nPortIndex = kPortIndexOutput;

    status_t err = mOMX->get_parameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));
    assert(err == NO_ERROR);

    switch (def.eDomain) {
        case OMX_PortDomainAudio:
        {
            OMX_AUDIO_PORTDEFINITIONTYPE *audio_def = &def.format.audio;

            assert(audio_def->eEncoding == OMX_AUDIO_CodingPCM);

            OMX_AUDIO_PARAM_PCMMODETYPE params;
            params.nSize = sizeof(params);
            params.nVersion.s.nVersionMajor = 1;
            params.nVersion.s.nVersionMinor = 1;
            params.nPortIndex = kPortIndexOutput;

            err = mOMX->get_parameter(
                    mNode, OMX_IndexParamAudioPcm, &params, sizeof(params));
            assert(err == OK);

            assert(params.eNumData == OMX_NumericalDataSigned);
            assert(params.nBitPerSample == 16);
            assert(params.ePCMMode == OMX_AUDIO_PCMModeLinear);

            int32_t numChannels, sampleRate;
            meta->findInt32(kKeyChannelCount, &numChannels);
            meta->findInt32(kKeySampleRate, &sampleRate);

            mOutputFormat->setCString(kKeyMIMEType, "audio/raw");
            mOutputFormat->setInt32(kKeyChannelCount, numChannels);
            mOutputFormat->setInt32(kKeySampleRate, sampleRate);
            break;
        }

        case OMX_PortDomainVideo:
        {
            OMX_VIDEO_PORTDEFINITIONTYPE *video_def = &def.format.video;

            if (video_def->eCompressionFormat == OMX_VIDEO_CodingUnused) {
                mOutputFormat->setCString(kKeyMIMEType, "video/raw");
            } else if (video_def->eCompressionFormat == OMX_VIDEO_CodingMPEG4) {
                mOutputFormat->setCString(kKeyMIMEType, "video/mp4v-es");
            } else if (video_def->eCompressionFormat == OMX_VIDEO_CodingH263) {
                mOutputFormat->setCString(kKeyMIMEType, "video/3gpp");
            } else if (video_def->eCompressionFormat == OMX_VIDEO_CodingAVC) {
                mOutputFormat->setCString(kKeyMIMEType, "video/avc");
            } else {
                assert(!"Unknown compression format.");
            }

            if (!strcmp(mComponentName, "OMX.PV.avcdec")) {
                // This component appears to be lying to me.
                mOutputFormat->setInt32(
                        kKeyWidth, (video_def->nFrameWidth + 15) & -16);
                mOutputFormat->setInt32(
                        kKeyHeight, (video_def->nFrameHeight + 15) & -16);
            } else {
                mOutputFormat->setInt32(kKeyWidth, video_def->nFrameWidth);
                mOutputFormat->setInt32(kKeyHeight, video_def->nFrameHeight);
            }

            mOutputFormat->setInt32(kKeyColorFormat, video_def->eColorFormat);
            break;
        }

        default:
        {
            assert(!"should not be here, neither audio nor video.");
            break;
        }
    }
}

void OMXDecoder::onStart() {
    bool needs_qcom_hack =
        !strncmp(mComponentName, "OMX.qcom.video.", 15);

    if (!needs_qcom_hack) {
        status_t err =
            mOMX->send_command(mNode, OMX_CommandStateSet, OMX_StateIdle);
        assert(err == NO_ERROR);
    }

    allocateBuffers(kPortIndexInput);
    allocateBuffers(kPortIndexOutput);

    if (needs_qcom_hack) {
        // XXX this should happen before AllocateBuffers, but qcom's
        // h264 vdec disagrees.
        status_t err =
            mOMX->send_command(mNode, OMX_CommandStateSet, OMX_StateIdle);
        assert(err == NO_ERROR);
    }
}

void OMXDecoder::allocateBuffers(OMX_U32 port_index) {
    assert(mBuffers[port_index].empty());

    OMX_U32 num_buffers;
    OMX_U32 buffer_size;

    OMX_PARAM_PORTDEFINITIONTYPE def;
    def.nSize = sizeof(def);
    def.nVersion.s.nVersionMajor = 1;
    def.nVersion.s.nVersionMinor = 1;
    def.nVersion.s.nRevision = 0;
    def.nVersion.s.nStep = 0;
    def.nPortIndex = port_index;

    status_t err = mOMX->get_parameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));
    assert(err == NO_ERROR);

    num_buffers = def.nBufferCountActual;
    buffer_size = def.nBufferSize;

    LOGV("[%s] port %ld: allocating %ld buffers of size %ld each\n",
           mComponentName, port_index, num_buffers, buffer_size);

    for (OMX_U32 i = 0; i < num_buffers; ++i) {
        sp<IMemory> mem = mDealer->allocate(buffer_size);
        assert(mem.get() != NULL);

        IOMX::buffer_id buffer;
        status_t err;

        if (port_index == kPortIndexInput
            && !strncmp(mComponentName, "OMX.qcom.video.encoder.", 23)) {
            // qcom's H.263 encoder appears to want to allocate its own input
            // buffers.
            err = mOMX->allocate_buffer_with_backup(mNode, port_index, mem, &buffer);
            if (err != OK) {
                LOGE("[%s] allocate_buffer_with_backup failed with error %d",
                     mComponentName, err);
            }
        } else if (port_index == kPortIndexOutput
            && !strncmp(mComponentName, "OMX.qcom.video.decoder.", 23)) {
#if 1
            err = mOMX->allocate_buffer_with_backup(mNode, port_index, mem, &buffer);
#else
            // XXX This is fine as long as we are either running the player
            // inside the media server process or we are using the
            // QComHardwareRenderer to output the frames.
            err = mOMX->allocate_buffer(mNode, port_index, buffer_size, &buffer);
#endif
            if (err != OK) {
                LOGE("[%s] allocate_buffer_with_backup failed with error %d",
                     mComponentName, err);
            }
        } else {
            err = mOMX->use_buffer(mNode, port_index, mem, &buffer);
            if (err != OK) {
                LOGE("[%s] use_buffer failed with error %d",
                     mComponentName, err);
            }
        }
        assert(err == OK);

        LOGV("allocated %s buffer %p.",
             port_index == kPortIndexInput ? "INPUT" : "OUTPUT",
             buffer);

        mBuffers.editItemAt(port_index).push_back(buffer);
        mBufferMap.add(buffer, mem);

        if (port_index == kPortIndexOutput) {
            OMXMediaBuffer *media_buffer = new OMXMediaBuffer(buffer, mem);
            media_buffer->setObserver(this);

            mMediaBufferMap.add(buffer, media_buffer);
        }
    }

    LOGV("allocate %s buffers done.",
         port_index == kPortIndexInput ? "INPUT" : "OUTPUT");
}

void OMXDecoder::onEvent(
        OMX_EVENTTYPE event, OMX_U32 data1, OMX_U32 data2) {
    LOGV("[%s] onEvent event=%d, data1=%ld, data2=%ld",
         mComponentName, event, data1, data2);

    switch (event) {
        case OMX_EventCmdComplete: {
            onEventCmdComplete(
                    static_cast<OMX_COMMANDTYPE>(data1), data2);

            break;
        }

        case OMX_EventPortSettingsChanged: {
            onEventPortSettingsChanged(data1);
            break;
        }

        case OMX_EventBufferFlag: {
            // initiateShutdown();
            break;
        }

        default:
            break;
    }
}

void OMXDecoder::onEventCmdComplete(OMX_COMMANDTYPE type, OMX_U32 data) {
    switch (type) {
        case OMX_CommandStateSet: {
            OMX_STATETYPE state = static_cast<OMX_STATETYPE>(data);
            onStateChanged(state);
            break;
        }

        case OMX_CommandPortDisable: {
            OMX_U32 port_index = data;
            assert(getPortStatus(port_index) == kPortStatusDisabled);

            status_t err =
                mOMX->send_command(mNode, OMX_CommandPortEnable, port_index);

            allocateBuffers(port_index);

            break;
        }

        case OMX_CommandPortEnable: {
            OMX_U32 port_index = data;
            assert(getPortStatus(port_index) ==kPortStatusDisabled);
            setPortStatus(port_index, kPortStatusActive);

            assert(port_index == kPortIndexOutput);

            BufferList *obuffers = &mBuffers.editItemAt(kPortIndexOutput);
            while (!obuffers->empty()) {
                IOMX::buffer_id buffer = *obuffers->begin();
                obuffers->erase(obuffers->begin());

                status_t err = mClient->fillBuffer(mNode, buffer);
                assert(err == NO_ERROR);
            }

            break;
        }

        case OMX_CommandFlush: {
            OMX_U32 port_index = data;
            LOGV("Port %ld flush complete.", port_index);
            assert(getPortStatus(port_index) == kPortStatusFlushing);

            setPortStatus(port_index, kPortStatusActive);
            BufferList *buffers = &mBuffers.editItemAt(port_index);
            while (!buffers->empty()) {
                IOMX::buffer_id buffer = *buffers->begin();
                buffers->erase(buffers->begin());

                if (port_index == kPortIndexInput) {
                    postEmptyBufferDone(buffer);
                } else {
                    postInitialFillBuffer(buffer);
                }
            }
            break;
        }

        default:
            break;
    }
}

void OMXDecoder::onEventPortSettingsChanged(OMX_U32 port_index) {
    assert(getPortStatus(port_index) == kPortStatusActive);
    setPortStatus(port_index, kPortStatusDisabled);

    status_t err =
        mOMX->send_command(mNode, OMX_CommandPortDisable, port_index);
    assert(err == NO_ERROR);
}

void OMXDecoder::onStateChanged(OMX_STATETYPE to) {
    if (mState == OMX_StateLoaded) {
        assert(to == OMX_StateIdle);

        mState = to;

        status_t err =
            mOMX->send_command(mNode, OMX_CommandStateSet, OMX_StateExecuting);
        assert(err == NO_ERROR);
    } else if (mState == OMX_StateIdle) {
        if (to == OMX_StateExecuting) {
            mState = to;

            BufferList *ibuffers = &mBuffers.editItemAt(kPortIndexInput);
            while (!ibuffers->empty()) {
                IOMX::buffer_id buffer = *ibuffers->begin();
                ibuffers->erase(ibuffers->begin());

                postEmptyBufferDone(buffer);
            }

            BufferList *obuffers = &mBuffers.editItemAt(kPortIndexOutput);
            while (!obuffers->empty()) {
                IOMX::buffer_id buffer = *obuffers->begin();
                obuffers->erase(obuffers->begin());

                postInitialFillBuffer(buffer);
            }
        } else {
            assert(to == OMX_StateLoaded);

            mState = to;

            setPortStatus(kPortIndexInput, kPortStatusActive);
            setPortStatus(kPortIndexOutput, kPortStatusActive);
        }
    } else if (mState == OMX_StateExecuting) {
        assert(to == OMX_StateIdle);

        mState = to;

        LOGV("Executing->Idle complete, initiating Idle->Loaded");
        status_t err =
            mClient->send_command(mNode, OMX_CommandStateSet, OMX_StateLoaded);
        assert(err == NO_ERROR);

        BufferList *ibuffers = &mBuffers.editItemAt(kPortIndexInput);
        for (BufferList::iterator it = ibuffers->begin();
             it != ibuffers->end(); ++it) {
            freeInputBuffer(*it);
        }
        ibuffers->clear();

        BufferList *obuffers = &mBuffers.editItemAt(kPortIndexOutput);
        for (BufferList::iterator it = obuffers->begin();
             it != obuffers->end(); ++it) {
            freeOutputBuffer(*it);
        }
        obuffers->clear();
    }
}

void OMXDecoder::initiateShutdown() {
    Mutex::Autolock autoLock(mLock);

    if (mShutdownInitiated) {
        return;
    }
    
    if (mState == OMX_StateLoaded) {
        return;
    }

    assert(mState == OMX_StateExecuting);

    mShutdownInitiated = true;

    status_t err =
        mClient->send_command(mNode, OMX_CommandStateSet, OMX_StateIdle);
    assert(err == NO_ERROR);

    setPortStatus(kPortIndexInput, kPortStatusShutdown);
    setPortStatus(kPortIndexOutput, kPortStatusShutdown);
}

void OMXDecoder::setPortStatus(OMX_U32 port_index, PortStatus status) {
    int shift = 2 * port_index;

    mPortStatusMask &= ~(3 << shift);
    mPortStatusMask |= status << shift;
}

OMXDecoder::PortStatus OMXDecoder::getPortStatus(
        OMX_U32 port_index) const {
    int shift = 2 * port_index;

    return static_cast<PortStatus>((mPortStatusMask >> shift) & 3);
}

void OMXDecoder::onEmptyBufferDone(IOMX::buffer_id buffer) {
    LOGV("[%s] onEmptyBufferDone (%p)", mComponentName, buffer);

    status_t err;
    switch (getPortStatus(kPortIndexInput)) {
        case kPortStatusDisabled:
            freeInputBuffer(buffer);
            err = NO_ERROR;
            break;

        case kPortStatusShutdown:
            LOGV("We're shutting down, enqueue INPUT buffer %p.", buffer);
            mBuffers.editItemAt(kPortIndexInput).push_back(buffer);
            err = NO_ERROR;
            break;

        case kPortStatusFlushing:
            LOGV("We're currently flushing, enqueue INPUT buffer %p.", buffer);
            mBuffers.editItemAt(kPortIndexInput).push_back(buffer);
            err = NO_ERROR;
            break;

        default:
            onRealEmptyBufferDone(buffer);
            err = NO_ERROR;
            break;
    }
    assert(err == NO_ERROR);
}

void OMXDecoder::onFillBufferDone(const omx_message &msg) {
    IOMX::buffer_id buffer = msg.u.extended_buffer_data.buffer;

    LOGV("[%s] onFillBufferDone (%p)", mComponentName, buffer);

    status_t err;
    switch (getPortStatus(kPortIndexOutput)) {
        case kPortStatusDisabled:
            freeOutputBuffer(buffer);
            err = NO_ERROR;
            break;
        case kPortStatusShutdown:
            LOGV("We're shutting down, enqueue OUTPUT buffer %p.", buffer);
            mBuffers.editItemAt(kPortIndexOutput).push_back(buffer);
            err = NO_ERROR;
            break;

        case kPortStatusFlushing:
            LOGV("We're currently flushing, enqueue OUTPUT buffer %p.", buffer);
            mBuffers.editItemAt(kPortIndexOutput).push_back(buffer);
            err = NO_ERROR;
            break;

        default:
        {
            if (msg.type == omx_message::INITIAL_FILL_BUFFER) {
                err = mClient->fillBuffer(mNode, buffer);
            } else {
                LOGV("[%s] Filled OUTPUT buffer %p, flags=0x%08lx.",
                     mComponentName, buffer, msg.u.extended_buffer_data.flags);

                onRealFillBufferDone(msg);
                err = NO_ERROR;
            }
            break;
        }
    }
    assert(err == NO_ERROR);
}

void OMXDecoder::onRealEmptyBufferDone(IOMX::buffer_id buffer) {
    if (mReachedEndOfInput) {
        // We already sent the EOS notification.

        mBuffers.editItemAt(kPortIndexInput).push_back(buffer);
        return;
    }

    const sp<IMemory> mem = mBufferMap.valueFor(buffer);
    assert(mem.get() != NULL);

    static const uint8_t kNALStartCode[4] = { 0x00, 0x00, 0x00, 0x01 };

    if (mCodecSpecificDataIterator != mCodecSpecificData.end()) {
        List<CodecSpecificData>::iterator it = mCodecSpecificDataIterator;

        size_t range_length = 0;

        if (!strcmp(mComponentName, "OMX.qcom.video.decoder.avc")) {
            assert((*mCodecSpecificDataIterator).size + 4 <= mem->size());

            memcpy(mem->pointer(), kNALStartCode, 4);

            memcpy((uint8_t *)mem->pointer() + 4, (*it).data, (*it).size);
            range_length = (*it).size + 4;
        } else {
            assert((*mCodecSpecificDataIterator).size <= mem->size());

            memcpy((uint8_t *)mem->pointer(), (*it).data, (*it).size);
            range_length = (*it).size;
        }

        ++mCodecSpecificDataIterator;

        status_t err = mClient->emptyBuffer(
                mNode, buffer, 0, range_length, 
                OMX_BUFFERFLAG_ENDOFFRAME | OMX_BUFFERFLAG_CODECCONFIG,
                0);

        assert(err == NO_ERROR);

        return;
    }

    LOGV("[%s] waiting for input data", mComponentName);

    MediaBuffer *input_buffer;
    for (;;) {
        status_t err;

        if (mSeeking) {
            MediaSource::ReadOptions options;
            options.setSeekTo(mSeekTimeUs);

            mSeeking = false;

            err = mSource->read(&input_buffer, &options);
        } else {
            err = mSource->read(&input_buffer);
        }
        assert((err == OK && input_buffer != NULL)
               || (err != OK && input_buffer == NULL));

        if (err == ERROR_END_OF_STREAM) {
            LOGE("[%s] Reached end of stream.", mComponentName);
            mReachedEndOfInput = true;
        } else {
            LOGV("[%s] got input data", mComponentName);
        }

        if (err != OK) {
            status_t err2 = mClient->emptyBuffer(
                    mNode, buffer, 0, 0, OMX_BUFFERFLAG_EOS, 0);

            assert(err2 == NO_ERROR);
            return;
        }

        if (mSeeking) {
            input_buffer->release();
            input_buffer = NULL;

            continue;
        }

        break;
    }

    const uint8_t *src_data =
        (const uint8_t *)input_buffer->data() + input_buffer->range_offset();

    size_t src_length = input_buffer->range_length();
    if (src_length == 195840) {
        // When feeding the output of the AVC decoder into the H263 encoder,
        // buffer sizes mismatch if width % 16 != 0 || height % 16 != 0.
        src_length = 194400;  // XXX HACK
    } else if (src_length == 115200) {
        src_length = 114240;  // XXX HACK
    }

    if (src_length > mem->size()) {
        LOGE("src_length=%d > mem->size() = %d\n",
             src_length, mem->size());
    }

    assert(src_length <= mem->size());
    memcpy(mem->pointer(), src_data, src_length);

    OMX_U32 flags = 0;
    if (!mIsMP3) {
        // Only mp3 audio data may be streamed, all other data is assumed
        // to be fed into the decoder at frame boundaries.
        flags |= OMX_BUFFERFLAG_ENDOFFRAME;
    }

    int32_t units, scale;
    bool success =
        input_buffer->meta_data()->findInt32(kKeyTimeUnits, &units);

    success = success &&
        input_buffer->meta_data()->findInt32(kKeyTimeScale, &scale);

    OMX_TICKS timestamp = 0;

    if (success) {
        // XXX units should be microseconds but PV treats them as milliseconds.
        timestamp = ((OMX_S64)units * 1000) / scale;
    }

    input_buffer->release();
    input_buffer = NULL;

    LOGV("[%s] Calling EmptyBuffer on buffer %p",
         mComponentName, buffer);

    status_t err2 = mClient->emptyBuffer(
            mNode, buffer, 0, src_length, flags, timestamp);
    assert(err2 == OK);
}

void OMXDecoder::onRealFillBufferDone(const omx_message &msg) {
    OMXMediaBuffer *media_buffer =
        mMediaBufferMap.valueFor(msg.u.extended_buffer_data.buffer);

    media_buffer->set_range(
            msg.u.extended_buffer_data.range_offset,
            msg.u.extended_buffer_data.range_length);

    media_buffer->add_ref();

    media_buffer->meta_data()->clear();

    media_buffer->meta_data()->setInt32(
            kKeyTimeUnits, msg.u.extended_buffer_data.timestamp);
    media_buffer->meta_data()->setInt32(kKeyTimeScale, 1000);

    if (msg.u.extended_buffer_data.flags & OMX_BUFFERFLAG_SYNCFRAME) {
        media_buffer->meta_data()->setInt32(kKeyIsSyncFrame, true);
    }

    media_buffer->meta_data()->setPointer(
            kKeyPlatformPrivate,
            msg.u.extended_buffer_data.platform_private);

    if (msg.u.extended_buffer_data.flags & OMX_BUFFERFLAG_EOS) {
        mErrorCondition = ERROR_END_OF_STREAM;
    }

    mOutputBuffers.push_back(media_buffer);
    mOutputBufferAvailable.signal();
}

void OMXDecoder::signalBufferReturned(MediaBuffer *_buffer) {
    Mutex::Autolock autoLock(mLock);

    OMXMediaBuffer *media_buffer = static_cast<OMXMediaBuffer *>(_buffer);

    IOMX::buffer_id buffer = media_buffer->buffer_id();

    PortStatus outputStatus = getPortStatus(kPortIndexOutput);
    if (outputStatus == kPortStatusShutdown
            || outputStatus == kPortStatusFlushing) {
        mBuffers.editItemAt(kPortIndexOutput).push_back(buffer);
    } else {
        LOGV("[%s] Calling FillBuffer on buffer %p.", mComponentName, buffer);

        status_t err = mClient->fillBuffer(mNode, buffer);
        assert(err == NO_ERROR);
    }
}

void OMXDecoder::freeInputBuffer(IOMX::buffer_id buffer) {
    LOGV("freeInputBuffer %p", buffer);

    status_t err = mOMX->free_buffer(mNode, kPortIndexInput, buffer);
    assert(err == NO_ERROR);
    mBufferMap.removeItem(buffer);

    LOGV("freeInputBuffer %p done", buffer);
}

void OMXDecoder::freeOutputBuffer(IOMX::buffer_id buffer) {
    LOGV("freeOutputBuffer %p", buffer);

    status_t err = mOMX->free_buffer(mNode, kPortIndexOutput, buffer);
    assert(err == NO_ERROR);
    mBufferMap.removeItem(buffer);

    ssize_t index = mMediaBufferMap.indexOfKey(buffer);
    assert(index >= 0);
    MediaBuffer *mbuffer = mMediaBufferMap.editValueAt(index);
    mMediaBufferMap.removeItemsAt(index);
    mbuffer->setObserver(NULL);
    mbuffer->release();
    mbuffer = NULL;

    LOGV("freeOutputBuffer %p done", buffer);
}

void OMXDecoder::dumpPortDefinition(OMX_U32 port_index) {
    OMX_PARAM_PORTDEFINITIONTYPE def;
    def.nSize = sizeof(def);
    def.nVersion.s.nVersionMajor = 1;
    def.nVersion.s.nVersionMinor = 1;
    def.nPortIndex = port_index;

    status_t err = mOMX->get_parameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));
    assert(err == NO_ERROR);

    LOGI("DumpPortDefinition on port %ld", port_index);
    LOGI("nBufferCountActual = %ld, nBufferCountMin = %ld, nBufferSize = %ld",
         def.nBufferCountActual, def.nBufferCountMin, def.nBufferSize);
    switch (def.eDomain) {
        case OMX_PortDomainAudio:
        {
            LOGI("eDomain = AUDIO");

            if (port_index == kPortIndexOutput) {
                OMX_AUDIO_PORTDEFINITIONTYPE *audio_def = &def.format.audio;
                assert(audio_def->eEncoding == OMX_AUDIO_CodingPCM);

                OMX_AUDIO_PARAM_PCMMODETYPE params;
                params.nSize = sizeof(params);
                params.nVersion.s.nVersionMajor = 1;
                params.nVersion.s.nVersionMinor = 1;
                params.nPortIndex = port_index;

                err = mOMX->get_parameter(
                        mNode, OMX_IndexParamAudioPcm, &params, sizeof(params));
                assert(err == OK);

                assert(params.nChannels == 1 || params.bInterleaved);
                assert(params.eNumData == OMX_NumericalDataSigned);
                assert(params.nBitPerSample == 16);
                assert(params.ePCMMode == OMX_AUDIO_PCMModeLinear);

                LOGI("nChannels = %ld, nSamplingRate = %ld",
                     params.nChannels, params.nSamplingRate);
            }

            break;
        }

        case OMX_PortDomainVideo:
        {
            LOGI("eDomain = VIDEO");

            OMX_VIDEO_PORTDEFINITIONTYPE *video_def = &def.format.video;
            LOGI("nFrameWidth = %ld, nFrameHeight = %ld, nStride = %ld, "
                 "nSliceHeight = %ld",
                 video_def->nFrameWidth, video_def->nFrameHeight,
                 video_def->nStride, video_def->nSliceHeight);
            LOGI("nBitrate = %ld, xFrameRate = %.2f",
                 video_def->nBitrate, video_def->xFramerate / 65536.0f);
            LOGI("eCompressionFormat = %d, eColorFormat = %d",
                 video_def->eCompressionFormat, video_def->eColorFormat);

            break;
        }

        default:
            LOGI("eDomain = UNKNOWN");
            break;
    }
}

void OMXDecoder::postStart() {
    omx_message msg;
    msg.type = omx_message::START;
    postMessage(msg);
}

void OMXDecoder::postEmptyBufferDone(IOMX::buffer_id buffer) {
    omx_message msg;
    msg.type = omx_message::EMPTY_BUFFER_DONE;
    msg.u.buffer_data.node = mNode;
    msg.u.buffer_data.buffer = buffer;
    postMessage(msg);
}

void OMXDecoder::postInitialFillBuffer(IOMX::buffer_id buffer) {
    omx_message msg;
    msg.type = omx_message::INITIAL_FILL_BUFFER;
    msg.u.buffer_data.node = mNode;
    msg.u.buffer_data.buffer = buffer;
    postMessage(msg);
}

}  // namespace android
