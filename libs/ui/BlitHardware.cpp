/*
 * Copyright (C) 2008 The Android Open Source Project
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

#define LOG_TAG "SurfaceFlinger"

#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>

#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/mman.h>

#include <cutils/log.h>

#include <utils/Errors.h>

#if HAVE_ANDROID_OS
#include <linux/fb.h>
#include <linux/msm_mdp.h>
#endif

#include <ui/BlitHardware.h>

/******************************************************************************/

namespace android {
class CopybitMSM7K : public copybit_t {
public:
    CopybitMSM7K();
    ~CopybitMSM7K();
    
    status_t getStatus() const {
        if (mFD<0) return mFD;
        return NO_ERROR;
    }

    status_t setParameter(int name, int value);

    status_t get(int name);

    status_t blit( 
            const copybit_image_t& dst,
            const copybit_image_t& src,
            copybit_region_t const* region);

    status_t stretch( 
            const copybit_image_t& dst,
            const copybit_image_t& src, 
            const copybit_rect_t& dst_rect,
            const copybit_rect_t& src_rect,
            copybit_region_t const* region);

#if HAVE_ANDROID_OS
private:
    static int copybit_set_parameter(copybit_t* handle, int name, int value);
    static int copybit_blit( copybit_t* handle, 
            copybit_image_t const* dst, copybit_image_t const* src,
            copybit_region_t const* region);
    static int copybit_stretch(copybit_t* handle, 
            copybit_image_t const* dst,  copybit_image_t const* src, 
            copybit_rect_t const* dst_rect, copybit_rect_t const* src_rect,
            copybit_region_t const* region);
    static int copybit_get(copybit_t* handle, int name);

    int getFormat(int format);
    void setImage(mdp_img* img, const copybit_image_t& rhs);
    void setRects(mdp_blit_req* req, const copybit_rect_t& dst,
            const copybit_rect_t& src, const copybit_rect_t& scissor);
    void setInfos(mdp_blit_req* req);
    static void intersect(copybit_rect_t* out, 
            const copybit_rect_t& lhs, const copybit_rect_t& rhs);
    status_t msm_copybit(void const* list);
#endif
    int mFD;
    uint8_t mAlpha;
    uint8_t mFlags;
};
}; // namespace android

using namespace android;

/******************************************************************************/

struct copybit_t* copybit_init()
{
    CopybitMSM7K* engine = new CopybitMSM7K();
    if (engine->getStatus() != NO_ERROR) {
        delete engine;
        engine = 0;
    }
    return (struct copybit_t*)engine;
        
}

int copybit_term(copybit_t* handle)
{
    delete static_cast<CopybitMSM7K*>(handle);
    return NO_ERROR;
}

namespace android {
/******************************************************************************/

static inline
int min(int a, int b) {
    return (a<b) ? a : b;
}

static inline
int max(int a, int b) {
    return (a>b) ? a : b;
}

static inline
void MULDIV(uint32_t& a, uint32_t& b, int mul, int div)
{
    if (mul != div) {
        a = (mul * a) / div;
        b = (mul * b) / div;
    }
}

//-----------------------------------------------------------------------------

#if HAVE_ANDROID_OS

int CopybitMSM7K::copybit_set_parameter(copybit_t* handle, int name, int value)
{
    return static_cast<CopybitMSM7K*>(handle)->setParameter(name, value);
}

int CopybitMSM7K::copybit_get(copybit_t* handle, int name)
{
    return static_cast<CopybitMSM7K*>(handle)->get(name);
}

int CopybitMSM7K::copybit_blit(
        copybit_t* handle, 
        copybit_image_t const* dst, 
        copybit_image_t const* src,
        struct copybit_region_t const* region)
{
    return static_cast<CopybitMSM7K*>(handle)->blit(*dst, *src, region);
}

int CopybitMSM7K::copybit_stretch(
        copybit_t* handle, 
        copybit_image_t const* dst, 
        copybit_image_t const* src, 
        copybit_rect_t const* dst_rect,
        copybit_rect_t const* src_rect,
        struct copybit_region_t const* region)
{
    return static_cast<CopybitMSM7K*>(handle)->stretch(
            *dst, *src, *dst_rect, *src_rect, region);
}

//-----------------------------------------------------------------------------

CopybitMSM7K::CopybitMSM7K()
    : mFD(-1), mAlpha(MDP_ALPHA_NOP), mFlags(0)
{
    int fd = open("/dev/graphics/fb0", O_RDWR, 0);
    if (fd > 0) {
        struct fb_fix_screeninfo finfo;
        if (ioctl(fd, FBIOGET_FSCREENINFO, &finfo) == 0) {
            if (!strcmp(finfo.id, "msmfb")) {
                mFD = fd;
                copybit_t::set_parameter = copybit_set_parameter;
                copybit_t::get = copybit_get;
                copybit_t::blit = copybit_blit;
                copybit_t::stretch = copybit_stretch;
            }
        }
    }
    if (fd<0 || mFD<0) {
        if (fd>0) { close(fd); }
        mFD = -errno;
    }
}

CopybitMSM7K::~CopybitMSM7K()
{
    if (mFD > 0){
        close(mFD);
    }
}

status_t CopybitMSM7K::setParameter(int name, int value)
{
    switch(name) {
    case COPYBIT_ROTATION_DEG:
        switch (value) {
        case 0:
            mFlags &= ~0x7;
            break;
        case 90:
            mFlags &= ~0x7;
            mFlags |= MDP_ROT_90;
            break;
        case 180:
            mFlags &= ~0x7;
            mFlags |= MDP_ROT_180;
            break;
        case 270:
            mFlags &= ~0x7;
            mFlags |= MDP_ROT_270;
            break;
        default:
            return BAD_VALUE;
        }
        break;
    case COPYBIT_PLANE_ALPHA:
        if (value < 0)      value = 0;
        if (value >= 256)   value = 255;
        mAlpha = value;
        break;
    case COPYBIT_DITHER:
        if (value == COPYBIT_ENABLE) {
            mFlags |= MDP_DITHER;
        } else if (value == COPYBIT_DISABLE) {
            mFlags &= ~MDP_DITHER;
        }
        break;
    case COPYBIT_TRANSFORM:
        mFlags &= ~0x7;
        mFlags |= value & 0x7;
        break;
    default:
        return BAD_VALUE;
    }
    return NO_ERROR;
}

status_t CopybitMSM7K::get(int name)
{
    switch(name) {
    case COPYBIT_MINIFICATION_LIMIT:
        return 4;
    case COPYBIT_MAGNIFICATION_LIMIT:
        return 4;
    case COPYBIT_SCALING_FRAC_BITS:
        return 32;
    case COPYBIT_ROTATION_STEP_DEG:
        return 90;
    }
    return BAD_VALUE;
}

status_t CopybitMSM7K::blit( 
        const copybit_image_t& dst,
        const copybit_image_t& src,
        copybit_region_t const* region)
{
    
    copybit_rect_t dr = { 0, 0, dst.w, dst.h };
    copybit_rect_t sr = { 0, 0, src.w, src.h };
    return CopybitMSM7K::stretch(dst, src, dr, sr, region);
}

status_t CopybitMSM7K::stretch( 
        const copybit_image_t& dst,
        const copybit_image_t& src, 
        const copybit_rect_t& dst_rect,
        const copybit_rect_t& src_rect,
        copybit_region_t const* region)
{
    struct {
        uint32_t count;
        struct mdp_blit_req req[12];
    } list;
    
    if (mAlpha<255) {
        switch (src.format) {
            // we dont' support plane alpha with RGBA formats
            case COPYBIT_RGBA_8888:
            case COPYBIT_RGBA_5551:
            case COPYBIT_RGBA_4444:
                return INVALID_OPERATION;
        }
    }
        
    const uint32_t maxCount = sizeof(list.req)/sizeof(list.req[0]);
    const copybit_rect_t bounds = { 0, 0, dst.w, dst.h };
    copybit_rect_t clip;
    list.count = 0;
    int err = 0;
    while (!err && region->next(region, &clip)) {
        intersect(&clip, bounds, clip);
        setInfos(&list.req[list.count]);
        setImage(&list.req[list.count].dst, dst);
        setImage(&list.req[list.count].src, src);
        setRects(&list.req[list.count], dst_rect, src_rect, clip);
        if (++list.count == maxCount) {
            err = msm_copybit(&list);
            list.count = 0;
        }
    }
    if (!err && list.count) {
        err = msm_copybit(&list);
    }
    return err;
}

status_t CopybitMSM7K::msm_copybit(void const* list)
{
    int err = ioctl(mFD, MSMFB_BLIT, static_cast<mdp_blit_req_list const*>(list));
    LOGE_IF(err<0, "copyBits failed (%s)", strerror(errno));
    if (err == 0)
        return NO_ERROR;
    return -errno;
}

int CopybitMSM7K::getFormat(int format)
{
    switch (format) {
    case COPYBIT_RGBA_8888:     return MDP_RGBA_8888;
    case COPYBIT_RGB_565:       return MDP_RGB_565;
    case COPYBIT_YCbCr_422_SP:  return MDP_Y_CBCR_H2V1;
    case COPYBIT_YCbCr_420_SP:  return MDP_Y_CBCR_H2V2;
    }
    return -1;
}

void CopybitMSM7K::setInfos(mdp_blit_req* req)
{
    req->alpha = mAlpha;
    req->transp_mask = MDP_TRANSP_NOP;
    req->flags = mFlags;
}

void CopybitMSM7K::setImage(mdp_img* img, const copybit_image_t& rhs)
{
    img->width      = rhs.w;
    img->height     = rhs.h;
    img->format     = getFormat(rhs.format);
    img->offset     = rhs.offset;
    img->memory_id  = rhs.fd;
}
    
void CopybitMSM7K::setRects(mdp_blit_req* e, 
        const copybit_rect_t& dst, const copybit_rect_t& src,
        const copybit_rect_t& scissor)
{
    copybit_rect_t clip;
    intersect(&clip, scissor, dst);

    e->dst_rect.x  = clip.l;
    e->dst_rect.y  = clip.t;
    e->dst_rect.w  = clip.r - clip.l;
    e->dst_rect.h  = clip.b - clip.t;

    uint32_t W, H;
    if (mFlags & COPYBIT_TRANSFORM_ROT_90) {
        e->src_rect.x  = (clip.t - dst.t) + src.t;
        e->src_rect.y  = (dst.r - clip.r) + src.l;
        e->src_rect.w  = (clip.b - clip.t);
        e->src_rect.h  = (clip.r - clip.l);
        W = dst.b - dst.t;
        H = dst.r - dst.l;
    } else {
        e->src_rect.x  = (clip.l - dst.l) + src.l;
        e->src_rect.y  = (clip.t - dst.t) + src.t;
        e->src_rect.w  = (clip.r - clip.l);
        e->src_rect.h  = (clip.b - clip.t);
        W = dst.r - dst.l;
        H = dst.b - dst.t;
    }
    MULDIV(e->src_rect.x, e->src_rect.w, src.r - src.l, W);
    MULDIV(e->src_rect.y, e->src_rect.h, src.b - src.t, H);
    if (mFlags & COPYBIT_TRANSFORM_FLIP_V) {
        e->src_rect.y = e->src.height - (e->src_rect.y + e->src_rect.h);
    }
    if (mFlags & COPYBIT_TRANSFORM_FLIP_H) {
        e->src_rect.x = e->src.width  - (e->src_rect.x + e->src_rect.w);
    }
}

void CopybitMSM7K::intersect(copybit_rect_t* out, 
        const copybit_rect_t& lhs, const copybit_rect_t& rhs)
{
    out->l = max(lhs.l, rhs.l);
    out->t = max(lhs.t, rhs.t);
    out->r = min(lhs.r, rhs.r);
    out->b = min(lhs.b, rhs.b);
}

/******************************************************************************/
#else // HAVE_ANDROID_OS

CopybitMSM7K::CopybitMSM7K()
    : mFD(-1)
{
}

CopybitMSM7K::~CopybitMSM7K()
{
}

status_t CopybitMSM7K::setParameter(int name, int value)
{
    return NO_INIT;
}

status_t CopybitMSM7K::get(int name)
{
    return BAD_VALUE;
}

status_t CopybitMSM7K::blit( 
        const copybit_image_t& dst,
        const copybit_image_t& src,
        copybit_region_t const* region)
{
    return NO_INIT;
}

status_t CopybitMSM7K::stretch( 
        const copybit_image_t& dst,
        const copybit_image_t& src, 
        const copybit_rect_t& dst_rect,
        const copybit_rect_t& src_rect,
        copybit_region_t const* region)
{
    return NO_INIT;
}

#endif // HAVE_ANDROID_OS

/******************************************************************************/
}; // namespace android
