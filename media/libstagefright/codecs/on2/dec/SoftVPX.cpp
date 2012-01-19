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

//#define LOG_NDEBUG 0
#define LOG_TAG "SoftVPX"
#include <utils/Log.h>

#include "SoftVPX.h"

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/MediaDefs.h>

#include "vpx/vpx_decoder.h"
#include "vpx/vpx_codec.h"
#include "vpx/vp8dx.h"

namespace android {

template<class T>
static void InitOMXParams(T *params) {
    params->nSize = sizeof(T);
    params->nVersion.s.nVersionMajor = 1;
    params->nVersion.s.nVersionMinor = 0;
    params->nVersion.s.nRevision = 0;
    params->nVersion.s.nStep = 0;
}

SoftVPX::SoftVPX(
        const char *name,
        const OMX_CALLBACKTYPE *callbacks,
        OMX_PTR appData,
        OMX_COMPONENTTYPE **component)
    : SimpleSoftOMXComponent(name, callbacks, appData, component),
      mCtx(NULL),
      mWidth(320),
      mHeight(240),
      mOutputPortSettingsChange(NONE) {
    initPorts();
    CHECK_EQ(initDecoder(), (status_t)OK);
}

SoftVPX::~SoftVPX() {
    vpx_codec_destroy((vpx_codec_ctx_t *)mCtx);
    delete (vpx_codec_ctx_t *)mCtx;
    mCtx = NULL;
}

void SoftVPX::initPorts() {
    OMX_PARAM_PORTDEFINITIONTYPE def;
    InitOMXParams(&def);

    def.nPortIndex = 0;
    def.eDir = OMX_DirInput;
    def.nBufferCountMin = kNumBuffers;
    def.nBufferCountActual = def.nBufferCountMin;
    def.nBufferSize = 256 * 1024;
    def.bEnabled = OMX_TRUE;
    def.bPopulated = OMX_FALSE;
    def.eDomain = OMX_PortDomainVideo;
    def.bBuffersContiguous = OMX_FALSE;
    def.nBufferAlignment = 1;

    def.format.video.cMIMEType = const_cast<char *>(MEDIA_MIMETYPE_VIDEO_VPX);
    def.format.video.pNativeRender = NULL;
    def.format.video.nFrameWidth = mWidth;
    def.format.video.nFrameHeight = mHeight;
    def.format.video.nStride = def.format.video.nFrameWidth;
    def.format.video.nSliceHeight = def.format.video.nFrameHeight;
    def.format.video.nBitrate = 0;
    def.format.video.xFramerate = 0;
    def.format.video.bFlagErrorConcealment = OMX_FALSE;
    def.format.video.eCompressionFormat = OMX_VIDEO_CodingVPX;
    def.format.video.eColorFormat = OMX_COLOR_FormatUnused;
    def.format.video.pNativeWindow = NULL;

    addPort(def);

    def.nPortIndex = 1;
    def.eDir = OMX_DirOutput;
    def.nBufferCountMin = kNumBuffers;
    def.nBufferCountActual = def.nBufferCountMin;
    def.bEnabled = OMX_TRUE;
    def.bPopulated = OMX_FALSE;
    def.eDomain = OMX_PortDomainVideo;
    def.bBuffersContiguous = OMX_FALSE;
    def.nBufferAlignment = 2;

    def.format.video.cMIMEType = const_cast<char *>(MEDIA_MIMETYPE_VIDEO_RAW);
    def.format.video.pNativeRender = NULL;
    def.format.video.nFrameWidth = mWidth;
    def.format.video.nFrameHeight = mHeight;
    def.format.video.nStride = def.format.video.nFrameWidth;
    def.format.video.nSliceHeight = def.format.video.nFrameHeight;
    def.format.video.nBitrate = 0;
    def.format.video.xFramerate = 0;
    def.format.video.bFlagErrorConcealment = OMX_FALSE;
    def.format.video.eCompressionFormat = OMX_VIDEO_CodingUnused;
    def.format.video.eColorFormat = OMX_COLOR_FormatYUV420Planar;
    def.format.video.pNativeWindow = NULL;

    def.nBufferSize =
        (def.format.video.nFrameWidth * def.format.video.nFrameHeight * 3) / 2;

    addPort(def);
}

static int GetCPUCoreCount() {
    int cpuCoreCount = 1;
#if defined(_SC_NPROCESSORS_ONLN)
    cpuCoreCount = sysconf(_SC_NPROCESSORS_ONLN);
#else
    // _SC_NPROC_ONLN must be defined...
    cpuCoreCount = sysconf(_SC_NPROC_ONLN);
#endif
    CHECK(cpuCoreCount >= 1);
    ALOGV("Number of CPU cores: %d", cpuCoreCount);
    return cpuCoreCount;
}

status_t SoftVPX::initDecoder() {
    mCtx = new vpx_codec_ctx_t;
    vpx_codec_err_t vpx_err;
    vpx_codec_dec_cfg_t cfg;
    memset(&cfg, 0, sizeof(vpx_codec_dec_cfg_t));
    cfg.threads = GetCPUCoreCount();
    if ((vpx_err = vpx_codec_dec_init(
                (vpx_codec_ctx_t *)mCtx, &vpx_codec_vp8_dx_algo, &cfg, 0))) {
        ALOGE("on2 decoder failed to initialize. (%d)", vpx_err);
        return UNKNOWN_ERROR;
    }

    return OK;
}

OMX_ERRORTYPE SoftVPX::internalGetParameter(
        OMX_INDEXTYPE index, OMX_PTR params) {
    switch (index) {
        case OMX_IndexParamVideoPortFormat:
        {
            OMX_VIDEO_PARAM_PORTFORMATTYPE *formatParams =
                (OMX_VIDEO_PARAM_PORTFORMATTYPE *)params;

            if (formatParams->nPortIndex > 1) {
                return OMX_ErrorUndefined;
            }

            if (formatParams->nIndex != 0) {
                return OMX_ErrorNoMore;
            }

            if (formatParams->nPortIndex == 0) {
                formatParams->eCompressionFormat = OMX_VIDEO_CodingVPX;
                formatParams->eColorFormat = OMX_COLOR_FormatUnused;
                formatParams->xFramerate = 0;
            } else {
                CHECK_EQ(formatParams->nPortIndex, 1u);

                formatParams->eCompressionFormat = OMX_VIDEO_CodingUnused;
                formatParams->eColorFormat = OMX_COLOR_FormatYUV420Planar;
                formatParams->xFramerate = 0;
            }

            return OMX_ErrorNone;
        }

        default:
            return SimpleSoftOMXComponent::internalGetParameter(index, params);
    }
}

OMX_ERRORTYPE SoftVPX::internalSetParameter(
        OMX_INDEXTYPE index, const OMX_PTR params) {
    switch (index) {
        case OMX_IndexParamStandardComponentRole:
        {
            const OMX_PARAM_COMPONENTROLETYPE *roleParams =
                (const OMX_PARAM_COMPONENTROLETYPE *)params;

            if (strncmp((const char *)roleParams->cRole,
                        "video_decoder.vpx",
                        OMX_MAX_STRINGNAME_SIZE - 1)) {
                return OMX_ErrorUndefined;
            }

            return OMX_ErrorNone;
        }

        case OMX_IndexParamVideoPortFormat:
        {
            OMX_VIDEO_PARAM_PORTFORMATTYPE *formatParams =
                (OMX_VIDEO_PARAM_PORTFORMATTYPE *)params;

            if (formatParams->nPortIndex > 1) {
                return OMX_ErrorUndefined;
            }

            if (formatParams->nIndex != 0) {
                return OMX_ErrorNoMore;
            }

            return OMX_ErrorNone;
        }

        default:
            return SimpleSoftOMXComponent::internalSetParameter(index, params);
    }
}

void SoftVPX::onQueueFilled(OMX_U32 portIndex) {
    if (mOutputPortSettingsChange != NONE) {
        return;
    }

    List<BufferInfo *> &inQueue = getPortQueue(0);
    List<BufferInfo *> &outQueue = getPortQueue(1);

    while (!inQueue.empty() && !outQueue.empty()) {
        BufferInfo *inInfo = *inQueue.begin();
        OMX_BUFFERHEADERTYPE *inHeader = inInfo->mHeader;

        BufferInfo *outInfo = *outQueue.begin();
        OMX_BUFFERHEADERTYPE *outHeader = outInfo->mHeader;

        if (inHeader->nFlags & OMX_BUFFERFLAG_EOS) {
            inQueue.erase(inQueue.begin());
            inInfo->mOwnedByUs = false;
            notifyEmptyBufferDone(inHeader);

            outHeader->nFilledLen = 0;
            outHeader->nFlags = OMX_BUFFERFLAG_EOS;

            outQueue.erase(outQueue.begin());
            outInfo->mOwnedByUs = false;
            notifyFillBufferDone(outHeader);
            return;
        }

        if (vpx_codec_decode(
                    (vpx_codec_ctx_t *)mCtx,
                    inHeader->pBuffer + inHeader->nOffset,
                    inHeader->nFilledLen,
                    NULL,
                    0)) {
            ALOGE("on2 decoder failed to decode frame.");

            notify(OMX_EventError, OMX_ErrorUndefined, 0, NULL);
            return;
        }

        vpx_codec_iter_t iter = NULL;
        vpx_image_t *img = vpx_codec_get_frame((vpx_codec_ctx_t *)mCtx, &iter);

        if (img != NULL) {
            CHECK_EQ(img->fmt, IMG_FMT_I420);

            int32_t width = img->d_w;
            int32_t height = img->d_h;

            if (width != mWidth || height != mHeight) {
                mWidth = width;
                mHeight = height;

                updatePortDefinitions();

                notify(OMX_EventPortSettingsChanged, 1, 0, NULL);
                mOutputPortSettingsChange = AWAITING_DISABLED;
                return;
            }

            outHeader->nOffset = 0;
            outHeader->nFilledLen = (width * height * 3) / 2;
            outHeader->nFlags = 0;
            outHeader->nTimeStamp = inHeader->nTimeStamp;

            const uint8_t *srcLine = (const uint8_t *)img->planes[PLANE_Y];
            uint8_t *dst = outHeader->pBuffer;
            for (size_t i = 0; i < img->d_h; ++i) {
                memcpy(dst, srcLine, img->d_w);

                srcLine += img->stride[PLANE_Y];
                dst += img->d_w;
            }

            srcLine = (const uint8_t *)img->planes[PLANE_U];
            for (size_t i = 0; i < img->d_h / 2; ++i) {
                memcpy(dst, srcLine, img->d_w / 2);

                srcLine += img->stride[PLANE_U];
                dst += img->d_w / 2;
            }

            srcLine = (const uint8_t *)img->planes[PLANE_V];
            for (size_t i = 0; i < img->d_h / 2; ++i) {
                memcpy(dst, srcLine, img->d_w / 2);

                srcLine += img->stride[PLANE_V];
                dst += img->d_w / 2;
            }

            outInfo->mOwnedByUs = false;
            outQueue.erase(outQueue.begin());
            outInfo = NULL;
            notifyFillBufferDone(outHeader);
            outHeader = NULL;
        }

        inInfo->mOwnedByUs = false;
        inQueue.erase(inQueue.begin());
        inInfo = NULL;
        notifyEmptyBufferDone(inHeader);
        inHeader = NULL;
    }
}

void SoftVPX::onPortFlushCompleted(OMX_U32 portIndex) {
}

void SoftVPX::onPortEnableCompleted(OMX_U32 portIndex, bool enabled) {
    if (portIndex != 1) {
        return;
    }

    switch (mOutputPortSettingsChange) {
        case NONE:
            break;

        case AWAITING_DISABLED:
        {
            CHECK(!enabled);
            mOutputPortSettingsChange = AWAITING_ENABLED;
            break;
        }

        default:
        {
            CHECK_EQ((int)mOutputPortSettingsChange, (int)AWAITING_ENABLED);
            CHECK(enabled);
            mOutputPortSettingsChange = NONE;
            break;
        }
    }
}

void SoftVPX::updatePortDefinitions() {
    OMX_PARAM_PORTDEFINITIONTYPE *def = &editPortInfo(0)->mDef;
    def->format.video.nFrameWidth = mWidth;
    def->format.video.nFrameHeight = mHeight;
    def->format.video.nStride = def->format.video.nFrameWidth;
    def->format.video.nSliceHeight = def->format.video.nFrameHeight;

    def = &editPortInfo(1)->mDef;
    def->format.video.nFrameWidth = mWidth;
    def->format.video.nFrameHeight = mHeight;
    def->format.video.nStride = def->format.video.nFrameWidth;
    def->format.video.nSliceHeight = def->format.video.nFrameHeight;

    def->nBufferSize =
        (def->format.video.nFrameWidth
            * def->format.video.nFrameHeight * 3) / 2;
}

}  // namespace android

android::SoftOMXComponent *createSoftOMXComponent(
        const char *name, const OMX_CALLBACKTYPE *callbacks,
        OMX_PTR appData, OMX_COMPONENTTYPE **component) {
    return new android::SoftVPX(name, callbacks, appData, component);
}

