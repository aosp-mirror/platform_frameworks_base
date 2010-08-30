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

#ifndef HARDWARE_API_H_

#define HARDWARE_API_H_

#include <media/stagefright/OMXPluginBase.h>
#include <media/stagefright/VideoRenderer.h>
#include <surfaceflinger/ISurface.h>
#include <utils/RefBase.h>

#include <OMX_Component.h>

namespace android {

// A pointer to this struct is passed to the OMX_SetParameter when the extension
// index for the 'OMX.google.android.index.enableAndroidNativeBuffers' extension
// is given.
//
// When Android native buffer use is disabled for a port (the default state),
// the OMX node should operate as normal, and expect UseBuffer calls to set its
// buffers.  This is the mode that will be used when CPU access to the buffer is
// required.
//
// When Android native buffer use has been enabled, the OMX node must support
// only color formats in the range [OMX_COLOR_FormatAndroidPrivateStart,
// OMX_COLOR_FormatAndroidPrivateEnd).  The node should then expect to receive
// UseAndroidNativeBuffer calls (via OMX_SetParameter) rather than UseBuffer
// calls.
struct EnableAndroidNativeBuffersParams {
    OMX_U32 portIndex;
    OMX_BOOL enable;
};

// Color formats in the range [OMX_COLOR_FormatAndroidPrivateStart,
// OMX_COLOR_FormatAndroidPrivateEnd) will be converted to a gralloc pixel
// format when used to allocate Android native buffers via gralloc.  The
// conversion is done by subtracting OMX_COLOR_FormatAndroidPrivateStart from
// the color format reported by the codec.
enum {
    OMX_COLOR_FormatAndroidPrivateStart = 0xA0000000,
    OMX_COLOR_FormatAndroidPrivateEnd = 0xB0000000,
};

// A pointer to this struct is passed to OMX_SetParameter when the extension
// index for the 'OMX.google.android.index.useAndroidNativeBuffer' extension is
// given.  This call will only be performed if a prior call was made with the
// 'OMX.google.android.index.enableAndroidNativeBuffers' extension index,
// enabling use of Android native buffers.
struct UseAndroidNativeBufferParams {
    OMX_BUFFERHEADERTYPE **bufferHeader;
    OMX_U32 portIndex;
    OMX_PTR appPrivate;
    const sp<android_native_buffer_t>& nativeBuffer;
};

}  // namespace android

extern android::VideoRenderer *createRenderer(
        const android::sp<android::ISurface> &surface,
        const char *componentName,
        OMX_COLOR_FORMATTYPE colorFormat,
        size_t displayWidth, size_t displayHeight,
        size_t decodedWidth, size_t decodedHeight);

extern android::OMXPluginBase *createOMXPlugin();

#endif  // HARDWARE_API_H_
