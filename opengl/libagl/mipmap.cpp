/* libs/opengles/mipmap.cpp
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

#include <stdio.h>
#include <stdlib.h>

#include "context.h"
#include "state.h"
#include "texture.h"
#include "TextureObjectManager.h"

namespace android {

// ----------------------------------------------------------------------------

status_t buildAPyramid(ogles_context_t* c, EGLTextureObject* tex)
{
    int level = 0;
    const GGLSurface* base = &tex->surface;    
    const GGLFormat& pixelFormat(c->rasterizer.formats[base->format]);

    int w = base->width;
    int h = base->height;
    if ((w&h) == 1)
        return NO_ERROR;

    w = (w>>1) ? : 1;
    h = (h>>1) ? : 1;

    while(true) {
        ++level;
        const int bpr = w * pixelFormat.size;
        if (tex->reallocate(level, w, h, w,
                base->format, base->compressedFormat, bpr) != NO_ERROR) {
            return NO_MEMORY;
        }
    
        int stride = w;
        int bs = base->stride;
        GGLSurface& cur = tex->editMip(level);

        if (base->format == GGL_PIXEL_FORMAT_RGB_565)
        {
            uint16_t const * src = (uint16_t const *)base->data;
            uint16_t* dst = (uint16_t*)cur.data;
            const uint32_t mask = 0x07E0F81F;
            for (int y=0 ; y<h ; y++) {
                size_t offset = (y*2) * bs;
                for (int x=0 ; x<w ; x++) {
                    uint32_t p00 = src[offset];
                    uint32_t p10 = src[offset+1];
                    uint32_t p01 = src[offset+bs];
                    uint32_t p11 = src[offset+bs+1];
                    p00 = (p00 | (p00 << 16)) & mask;
                    p01 = (p01 | (p01 << 16)) & mask;
                    p10 = (p10 | (p10 << 16)) & mask;
                    p11 = (p11 | (p11 << 16)) & mask;
                    uint32_t grb = ((p00 + p10 + p01 + p11) >> 2) & mask;
                    uint32_t rgb = (grb & 0xFFFF) | (grb >> 16);
                    dst[x + y*stride] = rgb;
                    offset += 2;
                }
            }
        }
        else if (base->format == GGL_PIXEL_FORMAT_RGBA_5551)
        {
            uint16_t const * src = (uint16_t const *)base->data;
            uint16_t* dst = (uint16_t*)cur.data;
            for (int y=0 ; y<h ; y++) {
                size_t offset = (y*2) * bs;
                for (int x=0 ; x<w ; x++) {
                    uint32_t p00 = src[offset];
                    uint32_t p10 = src[offset+1];
                    uint32_t p01 = src[offset+bs];
                    uint32_t p11 = src[offset+bs+1];
                    uint32_t r = ((p00>>11)+(p10>>11)+(p01>>11)+(p11>>11)+2)>>2;
                    uint32_t g = (((p00>>6)+(p10>>6)+(p01>>6)+(p11>>6)+2)>>2)&0x3F;
                    uint32_t b = ((p00&0x3E)+(p10&0x3E)+(p01&0x3E)+(p11&0x3E)+4)>>3;
                    uint32_t a = ((p00&1)+(p10&1)+(p01&1)+(p11&1)+2)>>2;
                    dst[x + y*stride] = (r<<11)|(g<<6)|(b<<1)|a;
                    offset += 2;
                }
            }
        }
        else if (base->format == GGL_PIXEL_FORMAT_RGBA_8888)
        {
            uint32_t const * src = (uint32_t const *)base->data;
            uint32_t* dst = (uint32_t*)cur.data;
            for (int y=0 ; y<h ; y++) {
                size_t offset = (y*2) * bs;
                for (int x=0 ; x<w ; x++) {
                    uint32_t p00 = src[offset];
                    uint32_t p10 = src[offset+1];
                    uint32_t p01 = src[offset+bs];
                    uint32_t p11 = src[offset+bs+1];
                    uint32_t rb00 = p00 & 0x00FF00FF;
                    uint32_t rb01 = p01 & 0x00FF00FF;
                    uint32_t rb10 = p10 & 0x00FF00FF;
                    uint32_t rb11 = p11 & 0x00FF00FF;
                    uint32_t ga00 = (p00 >> 8) & 0x00FF00FF;
                    uint32_t ga01 = (p01 >> 8) & 0x00FF00FF;
                    uint32_t ga10 = (p10 >> 8) & 0x00FF00FF;
                    uint32_t ga11 = (p11 >> 8) & 0x00FF00FF;
                    uint32_t rb = (rb00 + rb01 + rb10 + rb11)>>2;
                    uint32_t ga = (ga00 + ga01 + ga10 + ga11)>>2;
                    uint32_t rgba = (rb & 0x00FF00FF) | ((ga & 0x00FF00FF)<<8);
                    dst[x + y*stride] = rgba;
                    offset += 2;
                }
            }
        }
        else if ((base->format == GGL_PIXEL_FORMAT_RGB_888) ||
                 (base->format == GGL_PIXEL_FORMAT_LA_88) ||
                 (base->format == GGL_PIXEL_FORMAT_A_8) ||
                 (base->format == GGL_PIXEL_FORMAT_L_8))
        {
            int skip;
            switch (base->format) {
            case GGL_PIXEL_FORMAT_RGB_888:  skip = 3;   break;
            case GGL_PIXEL_FORMAT_LA_88:    skip = 2;   break;
            default:                        skip = 1;   break;
            }
            uint8_t const * src = (uint8_t const *)base->data;
            uint8_t* dst = (uint8_t*)cur.data;            
            bs *= skip;
            stride *= skip;
            for (int y=0 ; y<h ; y++) {
                size_t offset = (y*2) * bs;
                for (int x=0 ; x<w ; x++) {
                    for (int c=0 ; c<skip ; c++) {
                        uint32_t p00 = src[c+offset];
                        uint32_t p10 = src[c+offset+skip];
                        uint32_t p01 = src[c+offset+bs];
                        uint32_t p11 = src[c+offset+bs+skip];
                        dst[x + y*stride + c] = (p00 + p10 + p01 + p11) >> 2;
                    }
                    offset += 2*skip;
                }
            }
        }
        else if (base->format == GGL_PIXEL_FORMAT_RGBA_4444)
        {
            uint16_t const * src = (uint16_t const *)base->data;
            uint16_t* dst = (uint16_t*)cur.data;
            for (int y=0 ; y<h ; y++) {
                size_t offset = (y*2) * bs;
                for (int x=0 ; x<w ; x++) {
                    uint32_t p00 = src[offset];
                    uint32_t p10 = src[offset+1];
                    uint32_t p01 = src[offset+bs];
                    uint32_t p11 = src[offset+bs+1];
                    p00 = ((p00 << 12) & 0x0F0F0000) | (p00 & 0x0F0F);
                    p10 = ((p10 << 12) & 0x0F0F0000) | (p10 & 0x0F0F);
                    p01 = ((p01 << 12) & 0x0F0F0000) | (p01 & 0x0F0F);
                    p11 = ((p11 << 12) & 0x0F0F0000) | (p11 & 0x0F0F);
                    uint32_t rbga = (p00 + p10 + p01 + p11) >> 2;
                    uint32_t rgba = (rbga & 0x0F0F) | ((rbga>>12) & 0xF0F0);
                    dst[x + y*stride] = rgba;
                    offset += 2;
                }
            }
        } else {
            ALOGE("Unsupported format (%d)", base->format);
            return BAD_TYPE;
        }

        // exit condition: we just processed the 1x1 LODs
        if ((w&h) == 1)
            break;

        base = &cur;
        w = (w>>1) ? : 1;
        h = (h>>1) ? : 1;
    }
    return NO_ERROR;
}

}; // namespace android
