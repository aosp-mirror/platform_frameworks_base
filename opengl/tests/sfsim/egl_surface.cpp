/* 
 **
 ** Copyright 2008 The Android Open Source Project
 **
 ** Licensed under the Apache License Version 2.0(the "License"); 
 ** you may not use this file except in compliance with the License. 
 ** You may obtain a copy of the License at 
 **
 **     http://www.apache.org/licenses/LICENSE-2.0 
 **
 ** Unless required by applicable law or agreed to in writing software 
 ** distributed under the License is distributed on an "AS IS" BASIS 
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND either express or implied. 
 ** See the License for the specific language governing permissions and 
 ** limitations under the License.
 */

#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/mman.h>

#include <GLES/egl.h>

#include "egl_surface.h"

#define LOGI(x...) do { printf("INFO: " x); } while (0)
#define LOGW(x...) do { printf("WARN: " x); } while (0)
#define LOGE(x...) do { printf("ERR: " x); } while (0)

// ----------------------------------------------------------------------------

egl_native_window_t* android_createDisplaySurface()
{
    egl_native_window_t* s = new android::EGLDisplaySurface();
    s->memory_type = NATIVE_MEMORY_TYPE_GPU;
    return s;
}

#define LIKELY( exp )       (__builtin_expect( (exp) != 0, true  ))
#define UNLIKELY( exp )     (__builtin_expect( (exp) != 0, false ))

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

EGLDisplaySurface::EGLDisplaySurface()
    : EGLNativeSurface<EGLDisplaySurface>()
{
    egl_native_window_t::version = sizeof(egl_native_window_t);
    egl_native_window_t::ident = 0;
    egl_native_window_t::incRef = &EGLDisplaySurface::hook_incRef;
    egl_native_window_t::decRef = &EGLDisplaySurface::hook_decRef;
    egl_native_window_t::swapBuffers = &EGLDisplaySurface::hook_swapBuffers;
    egl_native_window_t::setSwapRectangle = &EGLDisplaySurface::hook_setSwapRectangle;
    egl_native_window_t::nextBuffer = &EGLDisplaySurface::hook_nextBuffer;
    egl_native_window_t::connect = 0;
    egl_native_window_t::disconnect = 0;

    mFb[0].data = 0;
    mFb[1].data = 0;
    egl_native_window_t::fd = mapFrameBuffer();
    if (egl_native_window_t::fd >= 0) {
        const float in2mm = 25.4f;
        float refreshRate = 1000000000000000LLU / (
                float( mInfo.upper_margin + mInfo.lower_margin + mInfo.yres ) 
                * ( mInfo.left_margin  + mInfo.right_margin + mInfo.xres )
                * mInfo.pixclock);

        const GGLSurface& buffer = mFb[1 - mIndex];
        egl_native_window_t::width  = buffer.width;
        egl_native_window_t::height = buffer.height;
        egl_native_window_t::stride = buffer.stride;
        egl_native_window_t::format = buffer.format;
        egl_native_window_t::base   = intptr_t(mFb[0].data);
        egl_native_window_t::offset =
            intptr_t(buffer.data) - egl_native_window_t::base;
        egl_native_window_t::flags  = 0;
        egl_native_window_t::xdpi = (mInfo.xres * in2mm) / mInfo.width;
        egl_native_window_t::ydpi = (mInfo.yres * in2mm) / mInfo.height;
        egl_native_window_t::fps  = refreshRate;
        egl_native_window_t::memory_type = NATIVE_MEMORY_TYPE_FB;
        // no error, set the magic word
        egl_native_window_t::magic = 0x600913;
    }
    mSwapCount = -1;
    mPageFlipCount = 0;
}

EGLDisplaySurface::~EGLDisplaySurface()
{
    magic = 0;
    close(egl_native_window_t::fd);
    munmap(mFb[0].data, mSize);
    if (!(mFlags & PAGE_FLIP))
        free((void*)mFb[1].data);
}

void EGLDisplaySurface::hook_incRef(NativeWindowType window) {
    EGLDisplaySurface* that = static_cast<EGLDisplaySurface*>(window);
    that->incStrong(that);
}
void EGLDisplaySurface::hook_decRef(NativeWindowType window) {
    EGLDisplaySurface* that = static_cast<EGLDisplaySurface*>(window);
    that->decStrong(that);
}
uint32_t EGLDisplaySurface::hook_swapBuffers(NativeWindowType window) {
    EGLDisplaySurface* that = static_cast<EGLDisplaySurface*>(window);
    return that->swapBuffers();
}
uint32_t EGLDisplaySurface::hook_nextBuffer(NativeWindowType window) {
    EGLDisplaySurface* that = static_cast<EGLDisplaySurface*>(window);
    return that->nextBuffer();
}
void EGLDisplaySurface::hook_setSwapRectangle(NativeWindowType window,
        int l, int t, int w, int h) {
    EGLDisplaySurface* that = static_cast<EGLDisplaySurface*>(window);
    that->setSwapRectangle(l, t, w, h);
}

void EGLDisplaySurface::setSwapRectangle(int l, int t, int w, int h)
{
    mInfo.reserved[0] = 0x54445055; // "UPDT";
    mInfo.reserved[1] = (uint16_t)l | ((uint32_t)t << 16);
    mInfo.reserved[2] = (uint16_t)(l+w) | ((uint32_t)(t+h) << 16);
}

uint32_t EGLDisplaySurface::swapBuffers()
{
    if (!(mFlags & PAGE_FLIP))
        return 0;

    // do the actual flip
    mIndex = 1 - mIndex;
    mInfo.activate = FB_ACTIVATE_VBL;
    mInfo.yoffset = mIndex ? mInfo.yres : 0;
    if (ioctl(egl_native_window_t::fd, FBIOPUT_VSCREENINFO, &mInfo) == -1) {
        LOGE("FBIOPUT_VSCREENINFO failed");
        return 0;
    }

    /*
     * this is a monstruous hack: Because the h/w accelerator is not able
     * to render directly into the framebuffer, we need to copy its
     * internal framebuffer out to the fb. the base address of the internal fb
     * is given in oem[0]. 
     * All this is needed only in standalone mode, in SurfaceFlinger mode
     * we control where the GPU renders.
     */
    if (egl_native_window_t::memory_type == NATIVE_MEMORY_TYPE_GPU && oem[0]) {
        // could use MDP here, but that's tricky because we need
        // /dev/pmem_gpu* filedescriptor
        const GGLSurface& buffer = mFb[mIndex];
        memcpy( buffer.data,
                (void*)(oem[0] + egl_native_window_t::offset),
                buffer.stride*buffer.height*2);
    }

    // update the address of the buffer to draw to next
    const GGLSurface& buffer = mFb[1 - mIndex];
    egl_native_window_t::offset =
        intptr_t(buffer.data) - egl_native_window_t::base;

    mPageFlipCount++;

    // We don't support screen-size changes for now
    return 0;
}

int32_t EGLDisplaySurface::getPageFlipCount() const
{
    return mPageFlipCount;
}

uint32_t EGLDisplaySurface::nextBuffer()
{
    // update the address of the buffer to draw to next
    const GGLSurface& buffer = mFb[mIndex];
    egl_native_window_t::offset =
        intptr_t(buffer.data) - egl_native_window_t::base;
    return 0;
}

int EGLDisplaySurface::mapFrameBuffer()
{
    char const * const device_template[] = {
            "/dev/graphics/fb%u",
            "/dev/fb%u",
            0 };
    int fd = -1;
    int i=0;
    char name[64];
    while ((fd==-1) && device_template[i]) {
        snprintf(name, 64, device_template[i], 0);
        fd = open(name, O_RDWR, 0);
        i++;
    }
    if (fd < 0)
        return -errno;

    struct fb_fix_screeninfo finfo;
    if (ioctl(fd, FBIOGET_FSCREENINFO, &finfo) == -1)
        return -errno;

    struct fb_var_screeninfo info;
    if (ioctl(fd, FBIOGET_VSCREENINFO, &info) == -1)
        return -errno;

    info.reserved[0] = 0;
    info.reserved[1] = 0;
    info.reserved[2] = 0;
    info.xoffset = 0;
    info.yoffset = 0;
    info.yres_virtual = info.yres * 2;
    info.bits_per_pixel = 16;
    info.activate = FB_ACTIVATE_NOW;

    uint32_t flags = PAGE_FLIP;
    if (ioctl(fd, FBIOPUT_VSCREENINFO, &info) == -1) {
        info.yres_virtual = info.yres;
        flags &= ~PAGE_FLIP;
        LOGW("FBIOPUT_VSCREENINFO failed, page flipping not supported");
    }

    if (info.yres_virtual < info.yres * 2) {
        info.yres_virtual = info.yres;
        flags &= ~PAGE_FLIP;
        LOGW("page flipping not supported (yres_virtual=%d, requested=%d)",
                info.yres_virtual, info.yres*2);
    }

    if (ioctl(fd, FBIOGET_VSCREENINFO, &info) == -1)
        return -errno;    

    int refreshRate = 1000000000000000LLU / 
    (
            uint64_t( info.upper_margin + info.lower_margin + info.yres ) 
            * ( info.left_margin  + info.right_margin + info.xres )
            * info.pixclock
    );

    if (refreshRate == 0) {
        // bleagh, bad info from the driver
        refreshRate = 60*1000;  // 60 Hz
    }

    if (int(info.width) <= 0 || int(info.height) <= 0) {
        // stupid driver, doesn't return that information
        // default to Sooner's screen size (160 dpi)
        info.width  = 51;
        info.height = 38;
    }

    float xdpi = (info.xres * 25.4f) / info.width; 
    float ydpi = (info.yres * 25.4f) / info.height;
    float fps  = refreshRate / 1000.0f; 

    LOGI(   "using (fd=%d)\n"
            "id           = %s\n"
            "xres         = %d px\n"
            "yres         = %d px\n"
            "xres_virtual = %d px\n"
            "yres_virtual = %d px\n"
            "bpp          = %d\n"
            "r            = %2u:%u\n"
            "g            = %2u:%u\n"
            "b            = %2u:%u\n",
            fd,
            finfo.id,
            info.xres,
            info.yres,
            info.xres_virtual,
            info.yres_virtual,
            info.bits_per_pixel,
            info.red.offset, info.red.length,
            info.green.offset, info.green.length,
            info.blue.offset, info.blue.length
    );

    LOGI(   "width        = %d mm (%f dpi)\n"
            "height       = %d mm (%f dpi)\n"
            "refresh rate = %.2f Hz\n",
            info.width,  xdpi,
            info.height, ydpi,
            fps
    );

    
    if (ioctl(fd, FBIOGET_FSCREENINFO, &finfo) == -1)
        return -errno;

    if (finfo.smem_len <= 0)
        return -errno;

    /*
     * Open and map the display.
     */

    void* buffer  = (uint16_t*) mmap(
            0, finfo.smem_len,
            PROT_READ | PROT_WRITE,
            MAP_SHARED,
            fd, 0);

    if (buffer == MAP_FAILED)
        return -errno;

    // at least for now, always clear the fb
    memset(buffer, 0, finfo.smem_len);

    uint8_t* offscreen[2];
    offscreen[0] = (uint8_t*)buffer;
    if (flags & PAGE_FLIP) {
        offscreen[1] = (uint8_t*)buffer + finfo.line_length*info.yres;
    } else {
        offscreen[1] = (uint8_t*)malloc(finfo.smem_len);
        if (offscreen[1] == 0) {
            munmap(buffer, finfo.smem_len);
            return -ENOMEM;
        }
    }

    mFlags = flags;
    mInfo = info;
    mFinfo = finfo;
    mSize = finfo.smem_len;
    mIndex = 0;
    for (int i=0 ; i<2 ; i++) {
        mFb[i].version = sizeof(GGLSurface);
        mFb[i].width   = info.xres;
        mFb[i].height  = info.yres;
        mFb[i].stride  = finfo.line_length / (info.bits_per_pixel >> 3);
        mFb[i].data    = (uint8_t*)(offscreen[i]);
        mFb[i].format  = NATIVE_PIXEL_FORMAT_RGB_565;
    }
    return fd;
}

// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------
