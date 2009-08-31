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
#define LOG_TAG "OMXCodec"
#include <utils/Log.h>

#include <binder/IServiceManager.h>
#include <binder/MemoryDealer.h>
#include <binder/ProcessState.h>
#include <media/IMediaPlayerService.h>
#include <media/stagefright/ESDS.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaExtractor.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MmapSource.h>
#include <media/stagefright/OMXCodec.h>
#include <media/stagefright/Utils.h>
#include <utils/Vector.h>

#include <OMX_Audio.h>
#include <OMX_Component.h>

namespace android {

struct CodecInfo {
    const char *mime;
    const char *codec;
};

static const CodecInfo kDecoderInfo[] = {
    { "image/jpeg", "OMX.TI.JPEG.decode" },
    { "audio/mpeg", "OMX.TI.MP3.decode" },
    { "audio/mpeg", "OMX.PV.mp3dec" },
    { "audio/3gpp", "OMX.TI.AMR.decode" },
    { "audio/3gpp", "OMX.PV.amrdec" },
    { "audio/mp4a-latm", "OMX.TI.AAC.decode" },
    { "audio/mp4a-latm", "OMX.PV.aacdec" },
    { "video/mp4v-es", "OMX.qcom.video.decoder.mpeg4" },
    { "video/mp4v-es", "OMX.TI.Video.Decoder" },
    { "video/mp4v-es", "OMX.PV.mpeg4dec" },
    { "video/3gpp", "OMX.qcom.video.decoder.h263" },
    { "video/3gpp", "OMX.TI.Video.Decoder" },
    { "video/3gpp", "OMX.PV.h263dec" },
    { "video/avc", "OMX.qcom.video.decoder.avc" },
    { "video/avc", "OMX.TI.Video.Decoder" },
    { "video/avc", "OMX.PV.avcdec" },
};

static const CodecInfo kEncoderInfo[] = {
    { "audio/3gpp", "OMX.TI.AMR.encode" },
    { "audio/3gpp", "OMX.PV.amrencnb" },
    { "audio/mp4a-latm", "OMX.TI.AAC.encode" },
    { "audio/mp4a-latm", "OMX.PV.aacenc" },
    { "video/mp4v-es", "OMX.qcom.video.encoder.mpeg4" },
    { "video/mp4v-es", "OMX.TI.Video.encoder" },
    { "video/mp4v-es", "OMX.PV.mpeg4enc" },
    { "video/3gpp", "OMX.qcom.video.encoder.h263" },
    { "video/3gpp", "OMX.TI.Video.encoder" },
    { "video/3gpp", "OMX.PV.h263enc" },
    { "video/avc", "OMX.TI.Video.encoder" },
    { "video/avc", "OMX.PV.avcenc" },
};

struct OMXCodecObserver : public BnOMXObserver {
    OMXCodecObserver(const wp<OMXCodec> &target)
        : mTarget(target) {
    }

    // from IOMXObserver
    virtual void on_message(const omx_message &msg) {
        sp<OMXCodec> codec = mTarget.promote();

        if (codec.get() != NULL) {
            codec->on_message(msg);
        }
    }

protected:
    virtual ~OMXCodecObserver() {}

private:
    wp<OMXCodec> mTarget;

    OMXCodecObserver(const OMXCodecObserver &);
    OMXCodecObserver &operator=(const OMXCodecObserver &);
};

static const char *GetCodec(const CodecInfo *info, size_t numInfos,
                            const char *mime, int index) {
    CHECK(index >= 0);
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

enum {
    kAVCProfileBaseline      = 0x42,
    kAVCProfileMain          = 0x4d,
    kAVCProfileExtended      = 0x58,
    kAVCProfileHigh          = 0x64,
    kAVCProfileHigh10        = 0x6e,
    kAVCProfileHigh422       = 0x7a,
    kAVCProfileHigh444       = 0xf4,
    kAVCProfileCAVLC444Intra = 0x2c
};

static const char *AVCProfileToString(uint8_t profile) {
    switch (profile) {
        case kAVCProfileBaseline:
            return "Baseline";
        case kAVCProfileMain:
            return "Main";
        case kAVCProfileExtended:
            return "Extended";
        case kAVCProfileHigh:
            return "High";
        case kAVCProfileHigh10:
            return "High 10";
        case kAVCProfileHigh422:
            return "High 422";
        case kAVCProfileHigh444:
            return "High 444";
        case kAVCProfileCAVLC444Intra:
            return "CAVLC 444 Intra";
        default:   return "Unknown";
    }
}

// static
sp<OMXCodec> OMXCodec::Create(
        const sp<IOMX> &omx,
        const sp<MetaData> &meta, bool createEncoder,
        const sp<MediaSource> &source) {
    const char *mime;
    bool success = meta->findCString(kKeyMIMEType, &mime);
    CHECK(success);

    const char *componentName = NULL;
    IOMX::node_id node = 0;
    for (int index = 0;; ++index) {
        if (createEncoder) {
            componentName = GetCodec(
                    kEncoderInfo, sizeof(kEncoderInfo) / sizeof(kEncoderInfo[0]),
                    mime, index);
        } else {
            componentName = GetCodec(
                    kDecoderInfo, sizeof(kDecoderInfo) / sizeof(kDecoderInfo[0]),
                    mime, index);
        }

        if (!componentName) {
            return NULL;
        }

        LOGV("Attempting to allocate OMX node '%s'", componentName);

        status_t err = omx->allocate_node(componentName, &node);
        if (err == OK) {
            break;
        }
    }

    uint32_t quirks = 0;
    if (!strcmp(componentName, "OMX.PV.avcdec")) {
        quirks |= kWantsNALFragments;
    }
    if (!strcmp(componentName, "OMX.TI.MP3.decode")) {
        quirks |= kNeedsFlushBeforeDisable;
    }
    if (!strcmp(componentName, "OMX.TI.AAC.decode")) {
        quirks |= kNeedsFlushBeforeDisable;
        quirks |= kRequiresFlushCompleteEmulation;

        // The following is currently necessary for proper shutdown
        // behaviour, but NOT enabled by default in order to make the
        // bug reproducible...
        // quirks |= kRequiresFlushBeforeShutdown;
    }
    if (!strncmp(componentName, "OMX.qcom.video.encoder.", 23)) {
        quirks |= kRequiresLoadedToIdleAfterAllocation;
        quirks |= kRequiresAllocateBufferOnInputPorts;
    }

    sp<OMXCodec> codec = new OMXCodec(
            omx, node, quirks, createEncoder, mime, componentName,
            source);

    uint32_t type;
    const void *data;
    size_t size;
    if (meta->findData(kKeyESDS, &type, &data, &size)) {
        ESDS esds((const char *)data, size);
        CHECK_EQ(esds.InitCheck(), OK);

        const void *codec_specific_data;
        size_t codec_specific_data_size;
        esds.getCodecSpecificInfo(
                &codec_specific_data, &codec_specific_data_size);

        printf("found codec-specific data of size %d\n",
               codec_specific_data_size);

        codec->addCodecSpecificData(
                codec_specific_data, codec_specific_data_size);
    } else if (meta->findData(kKeyAVCC, &type, &data, &size)) {
        printf("found avcc of size %d\n", size);

        // Parse the AVCDecoderConfigurationRecord

        const uint8_t *ptr = (const uint8_t *)data;

        CHECK(size >= 7);
        CHECK_EQ(ptr[0], 1);  // configurationVersion == 1
        uint8_t profile = ptr[1];
        uint8_t level = ptr[3];

        CHECK((ptr[4] >> 2) == 0x3f);  // reserved

        size_t lengthSize = 1 + (ptr[4] & 3);

        // commented out check below as H264_QVGA_500_NO_AUDIO.3gp
        // violates it...
        // CHECK((ptr[5] >> 5) == 7);  // reserved

        size_t numSeqParameterSets = ptr[5] & 31;

        ptr += 6;
        size -= 6;

        for (size_t i = 0; i < numSeqParameterSets; ++i) {
            CHECK(size >= 2);
            size_t length = U16_AT(ptr);

            ptr += 2;
            size -= 2;

            CHECK(size >= length);

            codec->addCodecSpecificData(ptr, length);

            ptr += length;
            size -= length;
        }

        CHECK(size >= 1);
        size_t numPictureParameterSets = *ptr;
        ++ptr;
        --size;

        for (size_t i = 0; i < numPictureParameterSets; ++i) {
            CHECK(size >= 2);
            size_t length = U16_AT(ptr);

            ptr += 2;
            size -= 2;

            CHECK(size >= length);

            codec->addCodecSpecificData(ptr, length);

            ptr += length;
            size -= length;
        }

        LOGI("AVC profile = %d (%s), level = %d",
             (int)profile, AVCProfileToString(profile), (int)level / 10);

        if (!strcmp(componentName, "OMX.TI.Video.Decoder")
            && (profile != kAVCProfileBaseline || level > 39)) {
            // This stream exceeds the decoder's capabilities.

            LOGE("Profile and/or level exceed the decoder's capabilities.");
            return NULL;
        }
    }

    if (!strcasecmp("audio/3gpp", mime)) {
        codec->setAMRFormat();
    }
    if (!createEncoder && !strcasecmp("audio/mp4a-latm", mime)) {
        codec->setAACFormat();
    }
    if (!strncasecmp(mime, "video/", 6)) {
        int32_t width, height;
        bool success = meta->findInt32(kKeyWidth, &width);
        success = success && meta->findInt32(kKeyHeight, &height);
        CHECK(success);

        if (createEncoder) {
            codec->setVideoInputFormat(mime, width, height);
        } else {
            codec->setVideoOutputFormat(mime, width, height);
        }
    }
    if (!strcasecmp(mime, "image/jpeg")
        && !strcmp(componentName, "OMX.TI.JPEG.decode")) {
        OMX_COLOR_FORMATTYPE format =
            OMX_COLOR_Format32bitARGB8888;
            // OMX_COLOR_FormatYUV420PackedPlanar;
            // OMX_COLOR_FormatCbYCrY;
            // OMX_COLOR_FormatYUV411Planar;

        int32_t width, height;
        bool success = meta->findInt32(kKeyWidth, &width);
        success = success && meta->findInt32(kKeyHeight, &height);

        int32_t compressedSize;
        success = success && meta->findInt32(
                kKeyCompressedSize, &compressedSize);

        CHECK(success);
        CHECK(compressedSize > 0);

        codec->setImageOutputFormat(format, width, height);
        codec->setJPEGInputFormat(width, height, (OMX_U32)compressedSize);
    }

    codec->initOutputFormat(meta);

    return codec;
}

status_t OMXCodec::setVideoPortFormatType(
        OMX_U32 portIndex,
        OMX_VIDEO_CODINGTYPE compressionFormat,
        OMX_COLOR_FORMATTYPE colorFormat) {
    OMX_VIDEO_PARAM_PORTFORMATTYPE format;
    format.nSize = sizeof(format);
    format.nVersion.s.nVersionMajor = 1;
    format.nVersion.s.nVersionMinor = 1;
    format.nPortIndex = portIndex;
    format.nIndex = 0;
    bool found = false;

    OMX_U32 index = 0;
    for (;;) {
        format.nIndex = index;
        status_t err = mOMX->get_parameter(
                mNode, OMX_IndexParamVideoPortFormat,
                &format, sizeof(format));

        if (err != OK) {
            return err;
        }

        // The following assertion is violated by TI's video decoder.
        // CHECK_EQ(format.nIndex, index);

#if 1
        LOGI("portIndex: %ld, index: %ld, eCompressionFormat=%d eColorFormat=%d",
             portIndex,
             index, format.eCompressionFormat, format.eColorFormat);
#endif

        if (!strcmp("OMX.TI.Video.encoder", mComponentName)) {
            if (portIndex == kPortIndexInput
                    && colorFormat == format.eColorFormat) {
                // eCompressionFormat does not seem right.
                found = true;
                break;
            }
            if (portIndex == kPortIndexOutput
                    && compressionFormat == format.eCompressionFormat) {
                // eColorFormat does not seem right.
                found = true;
                break;
            }
        }

        if (format.eCompressionFormat == compressionFormat
            && format.eColorFormat == colorFormat) {
            found = true;
            break;
        }

        ++index;
    }

    if (!found) {
        return UNKNOWN_ERROR;
    }

    LOGI("found a match.");
    status_t err = mOMX->set_parameter(
            mNode, OMX_IndexParamVideoPortFormat,
            &format, sizeof(format));

    return err;
}

void OMXCodec::setVideoInputFormat(
        const char *mime, OMX_U32 width, OMX_U32 height) {
    LOGI("setVideoInputFormat width=%ld, height=%ld", width, height);

    OMX_VIDEO_CODINGTYPE compressionFormat = OMX_VIDEO_CodingUnused;
    if (!strcasecmp("video/avc", mime)) {
        compressionFormat = OMX_VIDEO_CodingAVC;
    } else if (!strcasecmp("video/mp4v-es", mime)) {
        compressionFormat = OMX_VIDEO_CodingMPEG4;
    } else if (!strcasecmp("video/3gpp", mime)) {
        compressionFormat = OMX_VIDEO_CodingH263;
    } else {
        LOGE("Not a supported video mime type: %s", mime);
        CHECK(!"Should not be here. Not a supported video mime type.");
    }

    OMX_COLOR_FORMATTYPE colorFormat =
        0 ? OMX_COLOR_FormatYCbYCr : OMX_COLOR_FormatCbYCrY;

    if (!strncmp("OMX.qcom.video.encoder.", mComponentName, 23)) {
        colorFormat = OMX_COLOR_FormatYUV420SemiPlanar;
    }

    setVideoPortFormatType(
            kPortIndexInput, OMX_VIDEO_CodingUnused,
            colorFormat);

    setVideoPortFormatType(
            kPortIndexOutput, compressionFormat, OMX_COLOR_FormatUnused);

    OMX_PARAM_PORTDEFINITIONTYPE def;
    OMX_VIDEO_PORTDEFINITIONTYPE *video_def = &def.format.video;

    def.nSize = sizeof(def);
    def.nVersion.s.nVersionMajor = 1;
    def.nVersion.s.nVersionMinor = 1;
    def.nPortIndex = kPortIndexOutput;

    status_t err = mOMX->get_parameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));

    CHECK_EQ(err, OK);
    CHECK_EQ(def.eDomain, OMX_PortDomainVideo);

    video_def->nFrameWidth = width;
    video_def->nFrameHeight = height;

    video_def->eCompressionFormat = compressionFormat;
    video_def->eColorFormat = OMX_COLOR_FormatUnused;

    err = mOMX->set_parameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));
    CHECK_EQ(err, OK);

    ////////////////////////////////////////////////////////////////////////////

    def.nSize = sizeof(def);
    def.nVersion.s.nVersionMajor = 1;
    def.nVersion.s.nVersionMinor = 1;
    def.nPortIndex = kPortIndexInput;

    err = mOMX->get_parameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));
    CHECK_EQ(err, OK);

    def.nBufferSize = (width * height * 2); // (width * height * 3) / 2;
    LOGI("setting nBufferSize = %ld", def.nBufferSize);

    CHECK_EQ(def.eDomain, OMX_PortDomainVideo);

    video_def->nFrameWidth = width;
    video_def->nFrameHeight = height;
    video_def->eCompressionFormat = OMX_VIDEO_CodingUnused;
    video_def->eColorFormat = colorFormat;

    err = mOMX->set_parameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));
    CHECK_EQ(err, OK);
}

void OMXCodec::setVideoOutputFormat(
        const char *mime, OMX_U32 width, OMX_U32 height) {
    LOGI("setVideoOutputFormat width=%ld, height=%ld", width, height);

    // Enabling this code appears to be the right thing(tm), but,...
    // the TI decoder then loses the ability to output YUV420 and only outputs
    // YCbYCr (16bit)

#if 1
    if (!strcmp("OMX.TI.Video.Decoder", mComponentName)) {
        OMX_PARAM_COMPONENTROLETYPE role;
        role.nSize = sizeof(role);
        role.nVersion.s.nVersionMajor = 1;
        role.nVersion.s.nVersionMinor = 1;

        if (!strcasecmp("video/avc", mime)) {
            strncpy((char *)role.cRole, "video_decoder.avc",
                    OMX_MAX_STRINGNAME_SIZE - 1);
        } else if (!strcasecmp("video/mp4v-es", mime)) {
            strncpy((char *)role.cRole, "video_decoder.mpeg4",
                    OMX_MAX_STRINGNAME_SIZE - 1);
        } else if (!strcasecmp("video/3gpp", mime)) {
            strncpy((char *)role.cRole, "video_decoder.h263",
                    OMX_MAX_STRINGNAME_SIZE - 1);
        }

        role.cRole[OMX_MAX_STRINGNAME_SIZE - 1] = '\0';

        status_t err = mOMX->set_parameter(
                mNode, OMX_IndexParamStandardComponentRole,
                &role, sizeof(role));
        CHECK_EQ(err, OK);
    }
#endif

    OMX_VIDEO_CODINGTYPE compressionFormat = OMX_VIDEO_CodingUnused;
    if (!strcasecmp("video/avc", mime)) {
        compressionFormat = OMX_VIDEO_CodingAVC;
    } else if (!strcasecmp("video/mp4v-es", mime)) {
        compressionFormat = OMX_VIDEO_CodingMPEG4;
    } else if (!strcasecmp("video/3gpp", mime)) {
        compressionFormat = OMX_VIDEO_CodingH263;
    } else {
        LOGE("Not a supported video mime type: %s", mime);
        CHECK(!"Should not be here. Not a supported video mime type.");
    }

    setVideoPortFormatType(
            kPortIndexInput, compressionFormat, OMX_COLOR_FormatUnused);

#if 1
    {
        OMX_VIDEO_PARAM_PORTFORMATTYPE format;
        format.nSize = sizeof(format);
        format.nVersion.s.nVersionMajor = 1;
        format.nVersion.s.nVersionMinor = 1;
        format.nPortIndex = kPortIndexOutput;
        format.nIndex = 0;

        status_t err = mOMX->get_parameter(
                mNode, OMX_IndexParamVideoPortFormat,
                &format, sizeof(format));
        CHECK_EQ(err, OK);
        CHECK_EQ(format.eCompressionFormat, OMX_VIDEO_CodingUnused);

        static const int OMX_QCOM_COLOR_FormatYVU420SemiPlanar = 0x7FA30C00;

        CHECK(format.eColorFormat == OMX_COLOR_FormatYUV420Planar
               || format.eColorFormat == OMX_COLOR_FormatYUV420SemiPlanar
               || format.eColorFormat == OMX_COLOR_FormatCbYCrY
               || format.eColorFormat == OMX_QCOM_COLOR_FormatYVU420SemiPlanar);

        err = mOMX->set_parameter(
                mNode, OMX_IndexParamVideoPortFormat,
                &format, sizeof(format));
        CHECK_EQ(err, OK);
    }
#endif

    OMX_PARAM_PORTDEFINITIONTYPE def;
    OMX_VIDEO_PORTDEFINITIONTYPE *video_def = &def.format.video;

    def.nSize = sizeof(def);
    def.nVersion.s.nVersionMajor = 1;
    def.nVersion.s.nVersionMinor = 1;
    def.nPortIndex = kPortIndexInput;

    status_t err = mOMX->get_parameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));

    CHECK_EQ(err, OK);

#if 1
    // XXX Need a (much) better heuristic to compute input buffer sizes.
    const size_t X = 64 * 1024;
    if (def.nBufferSize < X) {
        def.nBufferSize = X;
    }
#endif

    CHECK_EQ(def.eDomain, OMX_PortDomainVideo);

    video_def->nFrameWidth = width;
    video_def->nFrameHeight = height;

    video_def->eColorFormat = OMX_COLOR_FormatUnused;

    err = mOMX->set_parameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));
    CHECK_EQ(err, OK);

    ////////////////////////////////////////////////////////////////////////////

    def.nSize = sizeof(def);
    def.nVersion.s.nVersionMajor = 1;
    def.nVersion.s.nVersionMinor = 1;
    def.nPortIndex = kPortIndexOutput;

    err = mOMX->get_parameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));
    CHECK_EQ(err, OK);
    CHECK_EQ(def.eDomain, OMX_PortDomainVideo);

#if 0
    def.nBufferSize =
        (((width + 15) & -16) * ((height + 15) & -16) * 3) / 2;  // YUV420
#endif

    video_def->nFrameWidth = width;
    video_def->nFrameHeight = height;

    err = mOMX->set_parameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));
    CHECK_EQ(err, OK);
}


OMXCodec::OMXCodec(
        const sp<IOMX> &omx, IOMX::node_id node, uint32_t quirks,
        bool isEncoder,
        const char *mime,
        const char *componentName,
        const sp<MediaSource> &source)
    : mOMX(omx),
      mNode(node),
      mQuirks(quirks),
      mIsEncoder(isEncoder),
      mMIME(strdup(mime)),
      mComponentName(strdup(componentName)),
      mSource(source),
      mCodecSpecificDataIndex(0),
      mState(LOADED),
      mInitialBufferSubmit(true),
      mSignalledEOS(false),
      mNoMoreOutputData(false),
      mSeekTimeUs(-1) {
    mPortStatus[kPortIndexInput] = ENABLED;
    mPortStatus[kPortIndexOutput] = ENABLED;

    mObserver = new OMXCodecObserver(this);
    mOMX->observe_node(mNode, mObserver);
}

OMXCodec::~OMXCodec() {
    CHECK(mState == LOADED || mState == ERROR);

    status_t err = mOMX->observe_node(mNode, NULL);
    CHECK_EQ(err, OK);

    err = mOMX->free_node(mNode);
    CHECK_EQ(err, OK);

    mNode = NULL;
    setState(DEAD);

    clearCodecSpecificData();

    free(mComponentName);
    mComponentName = NULL;

    free(mMIME);
    mMIME = NULL;
}

status_t OMXCodec::init() {
    // mLock is held.

    CHECK_EQ(mState, LOADED);

    status_t err;
    if (!(mQuirks & kRequiresLoadedToIdleAfterAllocation)) {
        err = mOMX->send_command(mNode, OMX_CommandStateSet, OMX_StateIdle);
        CHECK_EQ(err, OK);

        setState(LOADED_TO_IDLE);
    }

    err = allocateBuffers();
    CHECK_EQ(err, OK);

    if (mQuirks & kRequiresLoadedToIdleAfterAllocation) {
        err = mOMX->send_command(mNode, OMX_CommandStateSet, OMX_StateIdle);
        CHECK_EQ(err, OK);

        setState(LOADED_TO_IDLE);
    }

    while (mState != EXECUTING && mState != ERROR) {
        mAsyncCompletion.wait(mLock);
    }

    return mState == ERROR ? UNKNOWN_ERROR : OK;
}

// static
bool OMXCodec::isIntermediateState(State state) {
    return state == LOADED_TO_IDLE
        || state == IDLE_TO_EXECUTING
        || state == EXECUTING_TO_IDLE
        || state == IDLE_TO_LOADED
        || state == RECONFIGURING;
}

status_t OMXCodec::allocateBuffers() {
    status_t err = allocateBuffersOnPort(kPortIndexInput);

    if (err != OK) {
        return err;
    }

    return allocateBuffersOnPort(kPortIndexOutput);
}

status_t OMXCodec::allocateBuffersOnPort(OMX_U32 portIndex) {
    OMX_PARAM_PORTDEFINITIONTYPE def;
    def.nSize = sizeof(def);
    def.nVersion.s.nVersionMajor = 1;
    def.nVersion.s.nVersionMinor = 1;
    def.nVersion.s.nRevision = 0;
    def.nVersion.s.nStep = 0;
    def.nPortIndex = portIndex;

    status_t err = mOMX->get_parameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));

    if (err != OK) {
        return err;
    }

    size_t totalSize = def.nBufferCountActual * def.nBufferSize;
    mDealer[portIndex] = new MemoryDealer(totalSize);

    for (OMX_U32 i = 0; i < def.nBufferCountActual; ++i) {
        sp<IMemory> mem = mDealer[portIndex]->allocate(def.nBufferSize);
        CHECK(mem.get() != NULL);

        IOMX::buffer_id buffer;
        if (portIndex == kPortIndexInput
                && (mQuirks & kRequiresAllocateBufferOnInputPorts)) {
            err = mOMX->allocate_buffer_with_backup(
                    mNode, portIndex, mem, &buffer);
        } else if (portIndex == kPortIndexOutput
                && (mQuirks & kRequiresAllocateBufferOnOutputPorts)) {
            err = mOMX->allocate_buffer(
                    mNode, portIndex, def.nBufferSize, &buffer);
        } else {
            err = mOMX->use_buffer(mNode, portIndex, mem, &buffer);
        }

        if (err != OK) {
            LOGE("allocate_buffer_with_backup failed");
            return err;
        }

        BufferInfo info;
        info.mBuffer = buffer;
        info.mOwnedByComponent = false;
        info.mMem = mem;
        info.mMediaBuffer = NULL;

        if (portIndex == kPortIndexOutput) {
            info.mMediaBuffer = new MediaBuffer(mem->pointer(), mem->size());
            info.mMediaBuffer->setObserver(this);
        }

        mPortBuffers[portIndex].push(info);

        LOGV("allocated buffer %p on %s port", buffer,
             portIndex == kPortIndexInput ? "input" : "output");
    }

    dumpPortStatus(portIndex);

    return OK;
}

void OMXCodec::on_message(const omx_message &msg) {
    Mutex::Autolock autoLock(mLock);

    switch (msg.type) {
        case omx_message::EVENT:
        {
            onEvent(
                 msg.u.event_data.event, msg.u.event_data.data1,
                 msg.u.event_data.data2);

            break;
        }

        case omx_message::EMPTY_BUFFER_DONE:
        {
            IOMX::buffer_id buffer = msg.u.extended_buffer_data.buffer;

            LOGV("EMPTY_BUFFER_DONE(buffer: %p)", buffer);

            Vector<BufferInfo> *buffers = &mPortBuffers[kPortIndexInput];
            size_t i = 0;
            while (i < buffers->size() && (*buffers)[i].mBuffer != buffer) {
                ++i;
            }

            CHECK(i < buffers->size());
            if (!(*buffers)[i].mOwnedByComponent) {
                LOGW("We already own input buffer %p, yet received "
                     "an EMPTY_BUFFER_DONE.", buffer);
            }

            buffers->editItemAt(i).mOwnedByComponent = false;

            if (mPortStatus[kPortIndexInput] == DISABLING) {
                LOGV("Port is disabled, freeing buffer %p", buffer);

                status_t err =
                    mOMX->free_buffer(mNode, kPortIndexInput, buffer);
                CHECK_EQ(err, OK);

                buffers->removeAt(i);
            } else if (mPortStatus[kPortIndexInput] != SHUTTING_DOWN) {
                CHECK_EQ(mPortStatus[kPortIndexInput], ENABLED);
                drainInputBuffer(&buffers->editItemAt(i));
            }

            break;
        }

        case omx_message::FILL_BUFFER_DONE:
        {
            IOMX::buffer_id buffer = msg.u.extended_buffer_data.buffer;
            OMX_U32 flags = msg.u.extended_buffer_data.flags;

            LOGV("FILL_BUFFER_DONE(buffer: %p, size: %ld, flags: 0x%08lx)",
                 buffer,
                 msg.u.extended_buffer_data.range_length,
                 flags);

            LOGV("FILL_BUFFER_DONE(timestamp: %lld us (%.2f secs))",
                 msg.u.extended_buffer_data.timestamp,
                 msg.u.extended_buffer_data.timestamp / 1E6);

            Vector<BufferInfo> *buffers = &mPortBuffers[kPortIndexOutput];
            size_t i = 0;
            while (i < buffers->size() && (*buffers)[i].mBuffer != buffer) {
                ++i;
            }

            CHECK(i < buffers->size());
            BufferInfo *info = &buffers->editItemAt(i);

            if (!info->mOwnedByComponent) {
                LOGW("We already own output buffer %p, yet received "
                     "a FILL_BUFFER_DONE.", buffer);
            }

            info->mOwnedByComponent = false;

            if (mPortStatus[kPortIndexOutput] == DISABLING) {
                LOGV("Port is disabled, freeing buffer %p", buffer);

                status_t err =
                    mOMX->free_buffer(mNode, kPortIndexOutput, buffer);
                CHECK_EQ(err, OK);

                buffers->removeAt(i);
            } else if (mPortStatus[kPortIndexOutput] == ENABLED
                       && (flags & OMX_BUFFERFLAG_EOS)) {
                LOGV("No more output data.");
                mNoMoreOutputData = true;
                mBufferFilled.signal();
            } else if (mPortStatus[kPortIndexOutput] != SHUTTING_DOWN) {
                CHECK_EQ(mPortStatus[kPortIndexOutput], ENABLED);

                MediaBuffer *buffer = info->mMediaBuffer;

                buffer->set_range(
                        msg.u.extended_buffer_data.range_offset,
                        msg.u.extended_buffer_data.range_length);

                buffer->meta_data()->clear();

                buffer->meta_data()->setInt32(
                        kKeyTimeUnits,
                        (msg.u.extended_buffer_data.timestamp + 500) / 1000);

                buffer->meta_data()->setInt32(
                        kKeyTimeScale, 1000);

                if (msg.u.extended_buffer_data.flags & OMX_BUFFERFLAG_SYNCFRAME) {
                    buffer->meta_data()->setInt32(kKeyIsSyncFrame, true);
                }

                buffer->meta_data()->setPointer(
                        kKeyPlatformPrivate,
                        msg.u.extended_buffer_data.platform_private);

                buffer->meta_data()->setPointer(
                        kKeyBufferID,
                        msg.u.extended_buffer_data.buffer);

                mFilledBuffers.push_back(i);
                mBufferFilled.signal();
            }

            break;
        }

        default:
        {
            CHECK(!"should not be here.");
            break;
        }
    }
}

void OMXCodec::onEvent(OMX_EVENTTYPE event, OMX_U32 data1, OMX_U32 data2) {
    switch (event) {
        case OMX_EventCmdComplete:
        {
            onCmdComplete((OMX_COMMANDTYPE)data1, data2);
            break;
        }

        case OMX_EventError:
        {
            LOGE("ERROR(%ld, %ld)", data1, data2);

            setState(ERROR);
            break;
        }

        case OMX_EventPortSettingsChanged:
        {
            onPortSettingsChanged(data1);
            break;
        }

        case OMX_EventBufferFlag:
        {
            LOGV("EVENT_BUFFER_FLAG(%ld)", data1);

            if (data1 == kPortIndexOutput) {
                mNoMoreOutputData = true;
            }
            break;
        }

        default:
        {
            LOGV("EVENT(%d, %ld, %ld)", event, data1, data2);
            break;
        }
    }
}

void OMXCodec::onCmdComplete(OMX_COMMANDTYPE cmd, OMX_U32 data) {
    switch (cmd) {
        case OMX_CommandStateSet:
        {
            onStateChange((OMX_STATETYPE)data);
            break;
        }

        case OMX_CommandPortDisable:
        {
            OMX_U32 portIndex = data;
            LOGV("PORT_DISABLED(%ld)", portIndex);

            CHECK(mState == EXECUTING || mState == RECONFIGURING);
            CHECK_EQ(mPortStatus[portIndex], DISABLING);
            CHECK_EQ(mPortBuffers[portIndex].size(), 0);

            mPortStatus[portIndex] = DISABLED;

            if (mState == RECONFIGURING) {
                CHECK_EQ(portIndex, kPortIndexOutput);

                enablePortAsync(portIndex);

                status_t err = allocateBuffersOnPort(portIndex);
                CHECK_EQ(err, OK);
            }
            break;
        }

        case OMX_CommandPortEnable:
        {
            OMX_U32 portIndex = data;
            LOGV("PORT_ENABLED(%ld)", portIndex);

            CHECK(mState == EXECUTING || mState == RECONFIGURING);
            CHECK_EQ(mPortStatus[portIndex], ENABLING);

            mPortStatus[portIndex] = ENABLED;

            if (mState == RECONFIGURING) {
                CHECK_EQ(portIndex, kPortIndexOutput);

                setState(EXECUTING);

                fillOutputBuffers();
            }
            break;
        }

        case OMX_CommandFlush:
        {
            OMX_U32 portIndex = data;

            LOGV("FLUSH_DONE(%ld)", portIndex);

            CHECK_EQ(mPortStatus[portIndex], SHUTTING_DOWN);
            mPortStatus[portIndex] = ENABLED;

            CHECK_EQ(countBuffersWeOwn(mPortBuffers[portIndex]),
                     mPortBuffers[portIndex].size());

            if (mState == RECONFIGURING) {
                CHECK_EQ(portIndex, kPortIndexOutput);

                disablePortAsync(portIndex);
            } else if (mState == EXECUTING_TO_IDLE) {
                if (mPortStatus[kPortIndexInput] == ENABLED
                    && mPortStatus[kPortIndexOutput] == ENABLED) {
                    LOGV("Finished flushing both ports, now completing "
                         "transition from EXECUTING to IDLE.");

                    mPortStatus[kPortIndexInput] = SHUTTING_DOWN;
                    mPortStatus[kPortIndexOutput] = SHUTTING_DOWN;

                    status_t err =
                        mOMX->send_command(mNode, OMX_CommandStateSet, OMX_StateIdle);
                    CHECK_EQ(err, OK);
                }
            } else {
                // We're flushing both ports in preparation for seeking.

                if (mPortStatus[kPortIndexInput] == ENABLED
                    && mPortStatus[kPortIndexOutput] == ENABLED) {
                    LOGV("Finished flushing both ports, now continuing from"
                         " seek-time.");

                    drainInputBuffers();
                    fillOutputBuffers();
                }
            }

            break;
        }

        default:
        {
            LOGV("CMD_COMPLETE(%d, %ld)", cmd, data);
            break;
        }
    }
}

void OMXCodec::onStateChange(OMX_STATETYPE newState) {
    switch (newState) {
        case OMX_StateIdle:
        {
            LOGV("Now Idle.");
            if (mState == LOADED_TO_IDLE) {
                status_t err = mOMX->send_command(
                        mNode, OMX_CommandStateSet, OMX_StateExecuting);

                CHECK_EQ(err, OK);

                setState(IDLE_TO_EXECUTING);
            } else {
                CHECK_EQ(mState, EXECUTING_TO_IDLE);

                CHECK_EQ(
                    countBuffersWeOwn(mPortBuffers[kPortIndexInput]),
                    mPortBuffers[kPortIndexInput].size());

                CHECK_EQ(
                    countBuffersWeOwn(mPortBuffers[kPortIndexOutput]),
                    mPortBuffers[kPortIndexOutput].size());

                status_t err = mOMX->send_command(
                        mNode, OMX_CommandStateSet, OMX_StateLoaded);

                CHECK_EQ(err, OK);

                err = freeBuffersOnPort(kPortIndexInput);
                CHECK_EQ(err, OK);

                err = freeBuffersOnPort(kPortIndexOutput);
                CHECK_EQ(err, OK);

                mPortStatus[kPortIndexInput] = ENABLED;
                mPortStatus[kPortIndexOutput] = ENABLED;

                setState(IDLE_TO_LOADED);
            }
            break;
        }

        case OMX_StateExecuting:
        {
            CHECK_EQ(mState, IDLE_TO_EXECUTING);

            LOGV("Now Executing.");

            setState(EXECUTING);

            // Buffers will be submitted to the component in the first
            // call to OMXCodec::read as mInitialBufferSubmit is true at
            // this point. This ensures that this on_message call returns,
            // releases the lock and ::init can notice the state change and
            // itself return.
            break;
        }

        case OMX_StateLoaded:
        {
            CHECK_EQ(mState, IDLE_TO_LOADED);

            LOGV("Now Loaded.");

            setState(LOADED);
            break;
        }

        default:
        {
            CHECK(!"should not be here.");
            break;
        }
    }
}

// static
size_t OMXCodec::countBuffersWeOwn(const Vector<BufferInfo> &buffers) {
    size_t n = 0;
    for (size_t i = 0; i < buffers.size(); ++i) {
        if (!buffers[i].mOwnedByComponent) {
            ++n;
        }
    }

    return n;
}

status_t OMXCodec::freeBuffersOnPort(
        OMX_U32 portIndex, bool onlyThoseWeOwn) {
    Vector<BufferInfo> *buffers = &mPortBuffers[portIndex];

    status_t stickyErr = OK;

    for (size_t i = buffers->size(); i-- > 0;) {
        BufferInfo *info = &buffers->editItemAt(i);

        if (onlyThoseWeOwn && info->mOwnedByComponent) {
            continue;
        }

        CHECK_EQ(info->mOwnedByComponent, false);

        status_t err =
            mOMX->free_buffer(mNode, portIndex, info->mBuffer);

        if (err != OK) {
            stickyErr = err;
        }

        if (info->mMediaBuffer != NULL) {
            info->mMediaBuffer->setObserver(NULL);

            // Make sure nobody but us owns this buffer at this point.
            CHECK_EQ(info->mMediaBuffer->refcount(), 0);

            info->mMediaBuffer->release();
        }

        buffers->removeAt(i);
    }

    CHECK(onlyThoseWeOwn || buffers->isEmpty());

    return stickyErr;
}

void OMXCodec::onPortSettingsChanged(OMX_U32 portIndex) {
    LOGV("PORT_SETTINGS_CHANGED(%ld)", portIndex);

    CHECK_EQ(mState, EXECUTING);
    CHECK_EQ(portIndex, kPortIndexOutput);
    setState(RECONFIGURING);

    if (mQuirks & kNeedsFlushBeforeDisable) {
        if (!flushPortAsync(portIndex)) {
            onCmdComplete(OMX_CommandFlush, portIndex);
        }
    } else {
        disablePortAsync(portIndex);
    }
}

bool OMXCodec::flushPortAsync(OMX_U32 portIndex) {
    CHECK(mState == EXECUTING || mState == RECONFIGURING
            || mState == EXECUTING_TO_IDLE);

    LOGV("flushPortAsync(%ld): we own %d out of %d buffers already.",
         portIndex, countBuffersWeOwn(mPortBuffers[portIndex]),
         mPortBuffers[portIndex].size());

    CHECK_EQ(mPortStatus[portIndex], ENABLED);
    mPortStatus[portIndex] = SHUTTING_DOWN;

    if ((mQuirks & kRequiresFlushCompleteEmulation)
        && countBuffersWeOwn(mPortBuffers[portIndex])
                == mPortBuffers[portIndex].size()) {
        // No flush is necessary and this component fails to send a
        // flush-complete event in this case.

        return false;
    }

    status_t err =
        mOMX->send_command(mNode, OMX_CommandFlush, portIndex);
    CHECK_EQ(err, OK);

    return true;
}

void OMXCodec::disablePortAsync(OMX_U32 portIndex) {
    CHECK(mState == EXECUTING || mState == RECONFIGURING);

    CHECK_EQ(mPortStatus[portIndex], ENABLED);
    mPortStatus[portIndex] = DISABLING;

    status_t err =
        mOMX->send_command(mNode, OMX_CommandPortDisable, portIndex);
    CHECK_EQ(err, OK);

    freeBuffersOnPort(portIndex, true);
}

void OMXCodec::enablePortAsync(OMX_U32 portIndex) {
    CHECK(mState == EXECUTING || mState == RECONFIGURING);

    CHECK_EQ(mPortStatus[portIndex], DISABLED);
    mPortStatus[portIndex] = ENABLING;

    status_t err =
        mOMX->send_command(mNode, OMX_CommandPortEnable, portIndex);
    CHECK_EQ(err, OK);
}

void OMXCodec::fillOutputBuffers() {
    CHECK_EQ(mState, EXECUTING);

    Vector<BufferInfo> *buffers = &mPortBuffers[kPortIndexOutput];
    for (size_t i = 0; i < buffers->size(); ++i) {
        fillOutputBuffer(&buffers->editItemAt(i));
    }
}

void OMXCodec::drainInputBuffers() {
    CHECK(mState == EXECUTING || mState == RECONFIGURING);

    Vector<BufferInfo> *buffers = &mPortBuffers[kPortIndexInput];
    for (size_t i = 0; i < buffers->size(); ++i) {
        drainInputBuffer(&buffers->editItemAt(i));
    }
}

void OMXCodec::drainInputBuffer(BufferInfo *info) {
    CHECK_EQ(info->mOwnedByComponent, false);

    if (mSignalledEOS) {
        return;
    }

    if (mCodecSpecificDataIndex < mCodecSpecificData.size()) {
        const CodecSpecificData *specific =
            mCodecSpecificData[mCodecSpecificDataIndex];

        size_t size = specific->mSize;

        if (!strcasecmp("video/avc", mMIME)
                && !(mQuirks & kWantsNALFragments)) {
            static const uint8_t kNALStartCode[4] =
                    { 0x00, 0x00, 0x00, 0x01 };

            CHECK(info->mMem->size() >= specific->mSize + 4);

            size += 4;

            memcpy(info->mMem->pointer(), kNALStartCode, 4);
            memcpy((uint8_t *)info->mMem->pointer() + 4,
                   specific->mData, specific->mSize);
        } else {
            CHECK(info->mMem->size() >= specific->mSize);
            memcpy(info->mMem->pointer(), specific->mData, specific->mSize);
        }

        mOMX->empty_buffer(
                mNode, info->mBuffer, 0, size,
                OMX_BUFFERFLAG_ENDOFFRAME | OMX_BUFFERFLAG_CODECCONFIG,
                0);

        info->mOwnedByComponent = true;

        ++mCodecSpecificDataIndex;
        return;
    }

    // We're going to temporarily give up the lock while reading data
    // from the source. A certain client unfortunately chose to have the
    // thread supplying input data and reading output data be the same...

    MediaBuffer *srcBuffer;
    status_t err;
    if (mSeekTimeUs >= 0) {
        MediaSource::ReadOptions options;
        options.setSeekTo(mSeekTimeUs);
        mSeekTimeUs = -1;

        mLock.unlock();
        err = mSource->read(&srcBuffer, &options);
    } else {
        mLock.unlock();
        err = mSource->read(&srcBuffer);
    }
    mLock.lock();

    OMX_U32 flags = OMX_BUFFERFLAG_ENDOFFRAME;
    OMX_TICKS timestamp = 0;
    size_t srcLength = 0;

    if (err != OK) {
        LOGV("signalling end of input stream.");
        flags |= OMX_BUFFERFLAG_EOS;

        mSignalledEOS = true;
    } else {
        srcLength = srcBuffer->range_length();

        if (info->mMem->size() < srcLength) {
            LOGE("info->mMem->size() = %d, srcLength = %d",
                 info->mMem->size(), srcLength);
        }
        CHECK(info->mMem->size() >= srcLength);
        memcpy(info->mMem->pointer(),
               (const uint8_t *)srcBuffer->data() + srcBuffer->range_offset(),
               srcLength);

        int32_t units, scale;
        if (srcBuffer->meta_data()->findInt32(kKeyTimeUnits, &units)
            && srcBuffer->meta_data()->findInt32(kKeyTimeScale, &scale)) {
            timestamp = ((OMX_TICKS)units * 1000000) / scale;

            LOGV("Calling empty_buffer on buffer %p (length %d)",
                 info->mBuffer, srcLength);
            LOGV("Calling empty_buffer with timestamp %lld us (%.2f secs)",
                 timestamp, timestamp / 1E6);
        }
    }

    mOMX->empty_buffer(
            mNode, info->mBuffer, 0, srcLength,
            flags, timestamp);

    info->mOwnedByComponent = true;

    if (srcBuffer != NULL) {
        srcBuffer->release();
        srcBuffer = NULL;
    }
}

void OMXCodec::fillOutputBuffer(BufferInfo *info) {
    CHECK_EQ(info->mOwnedByComponent, false);

    if (mNoMoreOutputData) {
        LOGV("There is no more output data available, not "
             "calling fillOutputBuffer");
        return;
    }

    LOGV("Calling fill_buffer on buffer %p", info->mBuffer);
    mOMX->fill_buffer(mNode, info->mBuffer);

    info->mOwnedByComponent = true;
}

void OMXCodec::drainInputBuffer(IOMX::buffer_id buffer) {
    Vector<BufferInfo> *buffers = &mPortBuffers[kPortIndexInput];
    for (size_t i = 0; i < buffers->size(); ++i) {
        if ((*buffers)[i].mBuffer == buffer) {
            drainInputBuffer(&buffers->editItemAt(i));
            return;
        }
    }

    CHECK(!"should not be here.");
}

void OMXCodec::fillOutputBuffer(IOMX::buffer_id buffer) {
    Vector<BufferInfo> *buffers = &mPortBuffers[kPortIndexOutput];
    for (size_t i = 0; i < buffers->size(); ++i) {
        if ((*buffers)[i].mBuffer == buffer) {
            fillOutputBuffer(&buffers->editItemAt(i));
            return;
        }
    }

    CHECK(!"should not be here.");
}

void OMXCodec::setState(State newState) {
    mState = newState;
    mAsyncCompletion.signal();

    // This may cause some spurious wakeups but is necessary to
    // unblock the reader if we enter ERROR state.
    mBufferFilled.signal();
}

void OMXCodec::setAMRFormat() {
    if (!mIsEncoder) {
        OMX_AUDIO_PARAM_AMRTYPE def;
        def.nSize = sizeof(def);
        def.nVersion.s.nVersionMajor = 1;
        def.nVersion.s.nVersionMinor = 1;
        def.nPortIndex = kPortIndexInput;

        status_t err =
            mOMX->get_parameter(mNode, OMX_IndexParamAudioAmr, &def, sizeof(def));

        CHECK_EQ(err, OK);

        def.eAMRFrameFormat = OMX_AUDIO_AMRFrameFormatFSF;
        def.eAMRBandMode = OMX_AUDIO_AMRBandModeNB0;

        err = mOMX->set_parameter(mNode, OMX_IndexParamAudioAmr, &def, sizeof(def));
        CHECK_EQ(err, OK);
    }

    ////////////////////////

    if (mIsEncoder) {
        sp<MetaData> format = mSource->getFormat();
        int32_t sampleRate;
        int32_t numChannels;
        CHECK(format->findInt32(kKeySampleRate, &sampleRate));
        CHECK(format->findInt32(kKeyChannelCount, &numChannels));

        OMX_AUDIO_PARAM_PCMMODETYPE pcmParams;
        pcmParams.nSize = sizeof(pcmParams);
        pcmParams.nVersion.s.nVersionMajor = 1;
        pcmParams.nVersion.s.nVersionMinor = 1;
        pcmParams.nPortIndex = kPortIndexInput;

        status_t err = mOMX->get_parameter(
                mNode, OMX_IndexParamAudioPcm, &pcmParams, sizeof(pcmParams));

        CHECK_EQ(err, OK);

        pcmParams.nChannels = numChannels;
        pcmParams.eNumData = OMX_NumericalDataSigned;
        pcmParams.bInterleaved = OMX_TRUE;
        pcmParams.nBitPerSample = 16;
        pcmParams.nSamplingRate = sampleRate;
        pcmParams.ePCMMode = OMX_AUDIO_PCMModeLinear;

        if (numChannels == 1) {
            pcmParams.eChannelMapping[0] = OMX_AUDIO_ChannelCF;
        } else {
            CHECK_EQ(numChannels, 2);

            pcmParams.eChannelMapping[0] = OMX_AUDIO_ChannelLF;
            pcmParams.eChannelMapping[1] = OMX_AUDIO_ChannelRF;
        }

        err = mOMX->set_parameter(
                mNode, OMX_IndexParamAudioPcm, &pcmParams, sizeof(pcmParams));

        CHECK_EQ(err, OK);
    }
}

void OMXCodec::setAACFormat() {
    OMX_AUDIO_PARAM_AACPROFILETYPE def;
    def.nSize = sizeof(def);
    def.nVersion.s.nVersionMajor = 1;
    def.nVersion.s.nVersionMinor = 1;
    def.nPortIndex = kPortIndexInput;

    status_t err =
        mOMX->get_parameter(mNode, OMX_IndexParamAudioAac, &def, sizeof(def));
    CHECK_EQ(err, OK);

    def.eAACStreamFormat = OMX_AUDIO_AACStreamFormatMP4ADTS;

    err = mOMX->set_parameter(mNode, OMX_IndexParamAudioAac, &def, sizeof(def));
    CHECK_EQ(err, OK);
}

void OMXCodec::setImageOutputFormat(
        OMX_COLOR_FORMATTYPE format, OMX_U32 width, OMX_U32 height) {
    LOGV("setImageOutputFormat(%ld, %ld)", width, height);

#if 0
    OMX_INDEXTYPE index;
    status_t err = mOMX->get_extension_index(
            mNode, "OMX.TI.JPEG.decode.Config.OutputColorFormat", &index);
    CHECK_EQ(err, OK);

    err = mOMX->set_config(mNode, index, &format, sizeof(format));
    CHECK_EQ(err, OK);
#endif

    OMX_PARAM_PORTDEFINITIONTYPE def;
    def.nSize = sizeof(def);
    def.nVersion.s.nVersionMajor = 1;
    def.nVersion.s.nVersionMinor = 1;
    def.nPortIndex = kPortIndexOutput;

    status_t err = mOMX->get_parameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));
    CHECK_EQ(err, OK);

    CHECK_EQ(def.eDomain, OMX_PortDomainImage);

    OMX_IMAGE_PORTDEFINITIONTYPE *imageDef = &def.format.image;

    CHECK_EQ(imageDef->eCompressionFormat, OMX_IMAGE_CodingUnused);
    imageDef->eColorFormat = format;
    imageDef->nFrameWidth = width;
    imageDef->nFrameHeight = height;

    switch (format) {
        case OMX_COLOR_FormatYUV420PackedPlanar:
        case OMX_COLOR_FormatYUV411Planar:
        {
            def.nBufferSize = (width * height * 3) / 2;
            break;
        }

        case OMX_COLOR_FormatCbYCrY:
        {
            def.nBufferSize = width * height * 2;
            break;
        }

        case OMX_COLOR_Format32bitARGB8888:
        {
            def.nBufferSize = width * height * 4;
            break;
        }

        default:
            CHECK(!"Should not be here. Unknown color format.");
            break;
    }

    def.nBufferCountActual = def.nBufferCountMin;

    err = mOMX->set_parameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));
    CHECK_EQ(err, OK);
}

void OMXCodec::setJPEGInputFormat(
        OMX_U32 width, OMX_U32 height, OMX_U32 compressedSize) {
    OMX_PARAM_PORTDEFINITIONTYPE def;
    def.nSize = sizeof(def);
    def.nVersion.s.nVersionMajor = 1;
    def.nVersion.s.nVersionMinor = 1;
    def.nPortIndex = kPortIndexInput;

    status_t err = mOMX->get_parameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));
    CHECK_EQ(err, OK);

    CHECK_EQ(def.eDomain, OMX_PortDomainImage);
    OMX_IMAGE_PORTDEFINITIONTYPE *imageDef = &def.format.image;

    CHECK_EQ(imageDef->eCompressionFormat, OMX_IMAGE_CodingJPEG);
    imageDef->nFrameWidth = width;
    imageDef->nFrameHeight = height;

    def.nBufferSize = compressedSize;
    def.nBufferCountActual = def.nBufferCountMin;

    err = mOMX->set_parameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));
    CHECK_EQ(err, OK);
}

void OMXCodec::addCodecSpecificData(const void *data, size_t size) {
    CodecSpecificData *specific =
        (CodecSpecificData *)malloc(sizeof(CodecSpecificData) + size - 1);

    specific->mSize = size;
    memcpy(specific->mData, data, size);

    mCodecSpecificData.push(specific);
}

void OMXCodec::clearCodecSpecificData() {
    for (size_t i = 0; i < mCodecSpecificData.size(); ++i) {
        free(mCodecSpecificData.editItemAt(i));
    }
    mCodecSpecificData.clear();
    mCodecSpecificDataIndex = 0;
}

status_t OMXCodec::start(MetaData *) {
    Mutex::Autolock autoLock(mLock);

    if (mState != LOADED) {
        return UNKNOWN_ERROR;
    }

    sp<MetaData> params = new MetaData;
    if (mQuirks & kWantsNALFragments) {
        params->setInt32(kKeyWantsNALFragments, true);
    }
    status_t err = mSource->start(params.get());

    if (err != OK) {
        return err;
    }

    mCodecSpecificDataIndex = 0;
    mInitialBufferSubmit = true;
    mSignalledEOS = false;
    mNoMoreOutputData = false;
    mSeekTimeUs = -1;
    mFilledBuffers.clear();

    return init();
}

status_t OMXCodec::stop() {
    LOGV("stop");

    Mutex::Autolock autoLock(mLock);

    while (isIntermediateState(mState)) {
        mAsyncCompletion.wait(mLock);
    }

    switch (mState) {
        case LOADED:
        case ERROR:
            break;

        case EXECUTING:
        {
            setState(EXECUTING_TO_IDLE);

            if (mQuirks & kRequiresFlushBeforeShutdown) {
                LOGV("This component requires a flush before transitioning "
                     "from EXECUTING to IDLE...");

                bool emulateInputFlushCompletion =
                    !flushPortAsync(kPortIndexInput);

                bool emulateOutputFlushCompletion =
                    !flushPortAsync(kPortIndexOutput);

                if (emulateInputFlushCompletion) {
                    onCmdComplete(OMX_CommandFlush, kPortIndexInput);
                }

                if (emulateOutputFlushCompletion) {
                    onCmdComplete(OMX_CommandFlush, kPortIndexOutput);
                }
            } else {
                mPortStatus[kPortIndexInput] = SHUTTING_DOWN;
                mPortStatus[kPortIndexOutput] = SHUTTING_DOWN;

                status_t err =
                    mOMX->send_command(mNode, OMX_CommandStateSet, OMX_StateIdle);
                CHECK_EQ(err, OK);
            }

            while (mState != LOADED && mState != ERROR) {
                mAsyncCompletion.wait(mLock);
            }

            break;
        }

        default:
        {
            CHECK(!"should not be here.");
            break;
        }
    }

    mSource->stop();

    return OK;
}

sp<MetaData> OMXCodec::getFormat() {
    return mOutputFormat;
}

status_t OMXCodec::read(
        MediaBuffer **buffer, const ReadOptions *options) {
    *buffer = NULL;

    Mutex::Autolock autoLock(mLock);

    if (mState != EXECUTING && mState != RECONFIGURING) {
        return UNKNOWN_ERROR;
    }

    if (mInitialBufferSubmit) {
        mInitialBufferSubmit = false;

        drainInputBuffers();

        if (mState == EXECUTING) {
            // Otherwise mState == RECONFIGURING and this code will trigger
            // after the output port is reenabled.
            fillOutputBuffers();
        }
    }

    int64_t seekTimeUs;
    if (options && options->getSeekTo(&seekTimeUs)) {
        LOGV("seeking to %lld us (%.2f secs)", seekTimeUs, seekTimeUs / 1E6);

        mSignalledEOS = false;
        mNoMoreOutputData = false;

        CHECK(seekTimeUs >= 0);
        mSeekTimeUs = seekTimeUs;

        mFilledBuffers.clear();

        CHECK_EQ(mState, EXECUTING);

        bool emulateInputFlushCompletion = !flushPortAsync(kPortIndexInput);
        bool emulateOutputFlushCompletion = !flushPortAsync(kPortIndexOutput);

        if (emulateInputFlushCompletion) {
            onCmdComplete(OMX_CommandFlush, kPortIndexInput);
        }

        if (emulateOutputFlushCompletion) {
            onCmdComplete(OMX_CommandFlush, kPortIndexOutput);
        }
    }

    while (mState != ERROR && !mNoMoreOutputData && mFilledBuffers.empty()) {
        mBufferFilled.wait(mLock);
    }

    if (mState == ERROR) {
        return UNKNOWN_ERROR;
    }

    if (mFilledBuffers.empty()) {
        return ERROR_END_OF_STREAM;
    }

    size_t index = *mFilledBuffers.begin();
    mFilledBuffers.erase(mFilledBuffers.begin());

    BufferInfo *info = &mPortBuffers[kPortIndexOutput].editItemAt(index);
    info->mMediaBuffer->add_ref();
    *buffer = info->mMediaBuffer;

    return OK;
}

void OMXCodec::signalBufferReturned(MediaBuffer *buffer) {
    Mutex::Autolock autoLock(mLock);

    Vector<BufferInfo> *buffers = &mPortBuffers[kPortIndexOutput];
    for (size_t i = 0; i < buffers->size(); ++i) {
        BufferInfo *info = &buffers->editItemAt(i);

        if (info->mMediaBuffer == buffer) {
            CHECK_EQ(mPortStatus[kPortIndexOutput], ENABLED);
            fillOutputBuffer(info);
            return;
        }
    }

    CHECK(!"should not be here.");
}

static const char *imageCompressionFormatString(OMX_IMAGE_CODINGTYPE type) {
    static const char *kNames[] = {
        "OMX_IMAGE_CodingUnused",
        "OMX_IMAGE_CodingAutoDetect",
        "OMX_IMAGE_CodingJPEG",
        "OMX_IMAGE_CodingJPEG2K",
        "OMX_IMAGE_CodingEXIF",
        "OMX_IMAGE_CodingTIFF",
        "OMX_IMAGE_CodingGIF",
        "OMX_IMAGE_CodingPNG",
        "OMX_IMAGE_CodingLZW",
        "OMX_IMAGE_CodingBMP",
    };

    size_t numNames = sizeof(kNames) / sizeof(kNames[0]);

    if (type < 0 || (size_t)type >= numNames) {
        return "UNKNOWN";
    } else {
        return kNames[type];
    }
}

static const char *colorFormatString(OMX_COLOR_FORMATTYPE type) {
    static const char *kNames[] = {
        "OMX_COLOR_FormatUnused",
        "OMX_COLOR_FormatMonochrome",
        "OMX_COLOR_Format8bitRGB332",
        "OMX_COLOR_Format12bitRGB444",
        "OMX_COLOR_Format16bitARGB4444",
        "OMX_COLOR_Format16bitARGB1555",
        "OMX_COLOR_Format16bitRGB565",
        "OMX_COLOR_Format16bitBGR565",
        "OMX_COLOR_Format18bitRGB666",
        "OMX_COLOR_Format18bitARGB1665",
        "OMX_COLOR_Format19bitARGB1666",
        "OMX_COLOR_Format24bitRGB888",
        "OMX_COLOR_Format24bitBGR888",
        "OMX_COLOR_Format24bitARGB1887",
        "OMX_COLOR_Format25bitARGB1888",
        "OMX_COLOR_Format32bitBGRA8888",
        "OMX_COLOR_Format32bitARGB8888",
        "OMX_COLOR_FormatYUV411Planar",
        "OMX_COLOR_FormatYUV411PackedPlanar",
        "OMX_COLOR_FormatYUV420Planar",
        "OMX_COLOR_FormatYUV420PackedPlanar",
        "OMX_COLOR_FormatYUV420SemiPlanar",
        "OMX_COLOR_FormatYUV422Planar",
        "OMX_COLOR_FormatYUV422PackedPlanar",
        "OMX_COLOR_FormatYUV422SemiPlanar",
        "OMX_COLOR_FormatYCbYCr",
        "OMX_COLOR_FormatYCrYCb",
        "OMX_COLOR_FormatCbYCrY",
        "OMX_COLOR_FormatCrYCbY",
        "OMX_COLOR_FormatYUV444Interleaved",
        "OMX_COLOR_FormatRawBayer8bit",
        "OMX_COLOR_FormatRawBayer10bit",
        "OMX_COLOR_FormatRawBayer8bitcompressed",
        "OMX_COLOR_FormatL2",
        "OMX_COLOR_FormatL4",
        "OMX_COLOR_FormatL8",
        "OMX_COLOR_FormatL16",
        "OMX_COLOR_FormatL24",
        "OMX_COLOR_FormatL32",
        "OMX_COLOR_FormatYUV420PackedSemiPlanar",
        "OMX_COLOR_FormatYUV422PackedSemiPlanar",
        "OMX_COLOR_Format18BitBGR666",
        "OMX_COLOR_Format24BitARGB6666",
        "OMX_COLOR_Format24BitABGR6666",
    };

    size_t numNames = sizeof(kNames) / sizeof(kNames[0]);

    static const int OMX_QCOM_COLOR_FormatYVU420SemiPlanar = 0x7FA30C00;

    if (type == OMX_QCOM_COLOR_FormatYVU420SemiPlanar) {
        return "OMX_QCOM_COLOR_FormatYVU420SemiPlanar";
    } else if (type < 0 || (size_t)type >= numNames) {
        return "UNKNOWN";
    } else {
        return kNames[type];
    }
}

static const char *videoCompressionFormatString(OMX_VIDEO_CODINGTYPE type) {
    static const char *kNames[] = {
        "OMX_VIDEO_CodingUnused",
        "OMX_VIDEO_CodingAutoDetect",
        "OMX_VIDEO_CodingMPEG2",
        "OMX_VIDEO_CodingH263",
        "OMX_VIDEO_CodingMPEG4",
        "OMX_VIDEO_CodingWMV",
        "OMX_VIDEO_CodingRV",
        "OMX_VIDEO_CodingAVC",
        "OMX_VIDEO_CodingMJPEG",
    };

    size_t numNames = sizeof(kNames) / sizeof(kNames[0]);

    if (type < 0 || (size_t)type >= numNames) {
        return "UNKNOWN";
    } else {
        return kNames[type];
    }
}

static const char *audioCodingTypeString(OMX_AUDIO_CODINGTYPE type) {
    static const char *kNames[] = {
        "OMX_AUDIO_CodingUnused",
        "OMX_AUDIO_CodingAutoDetect",
        "OMX_AUDIO_CodingPCM",
        "OMX_AUDIO_CodingADPCM",
        "OMX_AUDIO_CodingAMR",
        "OMX_AUDIO_CodingGSMFR",
        "OMX_AUDIO_CodingGSMEFR",
        "OMX_AUDIO_CodingGSMHR",
        "OMX_AUDIO_CodingPDCFR",
        "OMX_AUDIO_CodingPDCEFR",
        "OMX_AUDIO_CodingPDCHR",
        "OMX_AUDIO_CodingTDMAFR",
        "OMX_AUDIO_CodingTDMAEFR",
        "OMX_AUDIO_CodingQCELP8",
        "OMX_AUDIO_CodingQCELP13",
        "OMX_AUDIO_CodingEVRC",
        "OMX_AUDIO_CodingSMV",
        "OMX_AUDIO_CodingG711",
        "OMX_AUDIO_CodingG723",
        "OMX_AUDIO_CodingG726",
        "OMX_AUDIO_CodingG729",
        "OMX_AUDIO_CodingAAC",
        "OMX_AUDIO_CodingMP3",
        "OMX_AUDIO_CodingSBC",
        "OMX_AUDIO_CodingVORBIS",
        "OMX_AUDIO_CodingWMA",
        "OMX_AUDIO_CodingRA",
        "OMX_AUDIO_CodingMIDI",
    };

    size_t numNames = sizeof(kNames) / sizeof(kNames[0]);

    if (type < 0 || (size_t)type >= numNames) {
        return "UNKNOWN";
    } else {
        return kNames[type];
    }
}

static const char *audioPCMModeString(OMX_AUDIO_PCMMODETYPE type) {
    static const char *kNames[] = {
        "OMX_AUDIO_PCMModeLinear",
        "OMX_AUDIO_PCMModeALaw",
        "OMX_AUDIO_PCMModeMULaw",
    };

    size_t numNames = sizeof(kNames) / sizeof(kNames[0]);

    if (type < 0 || (size_t)type >= numNames) {
        return "UNKNOWN";
    } else {
        return kNames[type];
    }
}


void OMXCodec::dumpPortStatus(OMX_U32 portIndex) {
    OMX_PARAM_PORTDEFINITIONTYPE def;
    def.nSize = sizeof(def);
    def.nVersion.s.nVersionMajor = 1;
    def.nVersion.s.nVersionMinor = 1;
    def.nPortIndex = portIndex;

    status_t err = mOMX->get_parameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));
    CHECK_EQ(err, OK);

    printf("%s Port = {\n", portIndex == kPortIndexInput ? "Input" : "Output");

    CHECK((portIndex == kPortIndexInput && def.eDir == OMX_DirInput)
          || (portIndex == kPortIndexOutput && def.eDir == OMX_DirOutput));

    printf("  nBufferCountActual = %ld\n", def.nBufferCountActual);
    printf("  nBufferCountMin = %ld\n", def.nBufferCountMin);
    printf("  nBufferSize = %ld\n", def.nBufferSize);

    switch (def.eDomain) {
        case OMX_PortDomainImage:
        {
            const OMX_IMAGE_PORTDEFINITIONTYPE *imageDef = &def.format.image;

            printf("\n");
            printf("  // Image\n");
            printf("  nFrameWidth = %ld\n", imageDef->nFrameWidth);
            printf("  nFrameHeight = %ld\n", imageDef->nFrameHeight);
            printf("  nStride = %ld\n", imageDef->nStride);

            printf("  eCompressionFormat = %s\n",
                   imageCompressionFormatString(imageDef->eCompressionFormat));

            printf("  eColorFormat = %s\n",
                   colorFormatString(imageDef->eColorFormat));

            break;
        }

        case OMX_PortDomainVideo:
        {
            OMX_VIDEO_PORTDEFINITIONTYPE *videoDef = &def.format.video;

            printf("\n");
            printf("  // Video\n");
            printf("  nFrameWidth = %ld\n", videoDef->nFrameWidth);
            printf("  nFrameHeight = %ld\n", videoDef->nFrameHeight);
            printf("  nStride = %ld\n", videoDef->nStride);

            printf("  eCompressionFormat = %s\n",
                   videoCompressionFormatString(videoDef->eCompressionFormat));

            printf("  eColorFormat = %s\n",
                   colorFormatString(videoDef->eColorFormat));

            break;
        }

        case OMX_PortDomainAudio:
        {
            OMX_AUDIO_PORTDEFINITIONTYPE *audioDef = &def.format.audio;

            printf("\n");
            printf("  // Audio\n");
            printf("  eEncoding = %s\n",
                   audioCodingTypeString(audioDef->eEncoding));

            if (audioDef->eEncoding == OMX_AUDIO_CodingPCM) {
                OMX_AUDIO_PARAM_PCMMODETYPE params;
                params.nSize = sizeof(params);
                params.nVersion.s.nVersionMajor = 1;
                params.nVersion.s.nVersionMinor = 1;
                params.nPortIndex = portIndex;

                err = mOMX->get_parameter(
                        mNode, OMX_IndexParamAudioPcm, &params, sizeof(params));
                CHECK_EQ(err, OK);

                printf("  nSamplingRate = %ld\n", params.nSamplingRate);
                printf("  nChannels = %ld\n", params.nChannels);
                printf("  bInterleaved = %d\n", params.bInterleaved);
                printf("  nBitPerSample = %ld\n", params.nBitPerSample);

                printf("  eNumData = %s\n",
                       params.eNumData == OMX_NumericalDataSigned
                        ? "signed" : "unsigned");

                printf("  ePCMMode = %s\n", audioPCMModeString(params.ePCMMode));
            }

            break;
        }

        default:
        {
            printf("  // Unknown\n");
            break;
        }
    }

    printf("}\n");
}

void OMXCodec::initOutputFormat(const sp<MetaData> &inputFormat) {
    mOutputFormat = new MetaData;
    mOutputFormat->setCString(kKeyDecoderComponent, mComponentName);

    OMX_PARAM_PORTDEFINITIONTYPE def;
    def.nSize = sizeof(def);
    def.nVersion.s.nVersionMajor = 1;
    def.nVersion.s.nVersionMinor = 1;
    def.nPortIndex = kPortIndexOutput;

    status_t err = mOMX->get_parameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));
    CHECK_EQ(err, OK);

    switch (def.eDomain) {
        case OMX_PortDomainImage:
        {
            OMX_IMAGE_PORTDEFINITIONTYPE *imageDef = &def.format.image;
            CHECK_EQ(imageDef->eCompressionFormat, OMX_IMAGE_CodingUnused);

            mOutputFormat->setCString(kKeyMIMEType, "image/raw");
            mOutputFormat->setInt32(kKeyColorFormat, imageDef->eColorFormat);
            mOutputFormat->setInt32(kKeyWidth, imageDef->nFrameWidth);
            mOutputFormat->setInt32(kKeyHeight, imageDef->nFrameHeight);
            break;
        }

        case OMX_PortDomainAudio:
        {
            OMX_AUDIO_PORTDEFINITIONTYPE *audio_def = &def.format.audio;

            CHECK_EQ(audio_def->eEncoding, OMX_AUDIO_CodingPCM);

            OMX_AUDIO_PARAM_PCMMODETYPE params;
            params.nSize = sizeof(params);
            params.nVersion.s.nVersionMajor = 1;
            params.nVersion.s.nVersionMinor = 1;
            params.nPortIndex = kPortIndexOutput;

            err = mOMX->get_parameter(
                    mNode, OMX_IndexParamAudioPcm, &params, sizeof(params));
            CHECK_EQ(err, OK);

            CHECK_EQ(params.eNumData, OMX_NumericalDataSigned);
            CHECK_EQ(params.nBitPerSample, 16);
            CHECK_EQ(params.ePCMMode, OMX_AUDIO_PCMModeLinear);

            int32_t numChannels, sampleRate;
            inputFormat->findInt32(kKeyChannelCount, &numChannels);
            inputFormat->findInt32(kKeySampleRate, &sampleRate);

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
                CHECK(!"Unknown compression format.");
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
            CHECK(!"should not be here, neither audio nor video.");
            break;
        }
    }
}

}  // namespace android
