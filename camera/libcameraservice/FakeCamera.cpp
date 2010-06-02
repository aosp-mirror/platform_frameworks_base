/*
**
** Copyright 2008, The Android Open Source Project
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

#define LOG_TAG "FakeCamera"
#include <utils/Log.h>

#include <string.h>
#include <stdlib.h>
#include <utils/String8.h>

#include "FakeCamera.h"


namespace android {

// TODO: All this rgb to yuv should probably be in a util class.

// TODO: I think something is wrong in this class because the shadow is kBlue
// and the square color should alternate between kRed and kGreen. However on the
// emulator screen these are all shades of gray. Y seems ok but the U and V are
// probably not.

static int tables_initialized = 0;
uint8_t *gYTable, *gCbTable, *gCrTable;

static int
clamp(int  x)
{
    if (x > 255) return 255;
    if (x < 0)   return 0;
    return x;
}

/* the equation used by the video code to translate YUV to RGB looks like this
 *
 *    Y  = (Y0 - 16)*k0
 *    Cb = Cb0 - 128
 *    Cr = Cr0 - 128
 *
 *    G = ( Y - k1*Cr - k2*Cb )
 *    R = ( Y + k3*Cr )
 *    B = ( Y + k4*Cb )
 *
 */

static const double  k0 = 1.164;
static const double  k1 = 0.813;
static const double  k2 = 0.391;
static const double  k3 = 1.596;
static const double  k4 = 2.018;

/* let's try to extract the value of Y
 *
 *   G + k1/k3*R + k2/k4*B = Y*( 1 + k1/k3 + k2/k4 )
 *
 *   Y  = ( G + k1/k3*R + k2/k4*B ) / (1 + k1/k3 + k2/k4)
 *   Y0 = ( G0 + k1/k3*R0 + k2/k4*B0 ) / ((1 + k1/k3 + k2/k4)*k0) + 16
 *
 * let define:
 *   kYr = k1/k3
 *   kYb = k2/k4
 *   kYy = k0 * ( 1 + kYr + kYb )
 *
 * we have:
 *    Y  = ( G + kYr*R + kYb*B )
 *    Y0 = clamp[ Y/kYy + 16 ]
 */

static const double kYr = k1/k3;
static const double kYb = k2/k4;
static const double kYy = k0*( 1. + kYr + kYb );

static void
initYtab( void )
{
    const  int imax = (int)( (kYr + kYb)*(31 << 2) + (61 << 3) + 0.1 );
    int    i;

    gYTable = (uint8_t *)malloc(imax);

    for(i=0; i<imax; i++) {
        int  x = (int)(i/kYy + 16.5);
        if (x < 16) x = 16;
        else if (x > 235) x = 235;
        gYTable[i] = (uint8_t) x;
    }
}

/*
 *   the source is RGB565, so adjust for 8-bit range of input values:
 *
 *   G = (pixels >> 3) & 0xFC;
 *   R = (pixels >> 8) & 0xF8;
 *   B = (pixels & 0x1f) << 3;
 *
 *   R2 = (pixels >> 11)      R = R2*8
 *   B2 = (pixels & 0x1f)     B = B2*8
 *
 *   kYr*R = kYr2*R2 =>  kYr2 = kYr*8
 *   kYb*B = kYb2*B2 =>  kYb2 = kYb*8
 *
 *   we want to use integer multiplications:
 *
 *   SHIFT1 = 9
 *
 *   (ALPHA*R2) >> SHIFT1 == R*kYr  =>  ALPHA = kYr*8*(1 << SHIFT1)
 *
 *   ALPHA = kYr*(1 << (SHIFT1+3))
 *   BETA  = kYb*(1 << (SHIFT1+3))
 */

static const int  SHIFT1  = 9;
static const int  ALPHA   = (int)( kYr*(1 << (SHIFT1+3)) + 0.5 );
static const int  BETA    = (int)( kYb*(1 << (SHIFT1+3)) + 0.5 );

/*
 *  now let's try to get the values of Cb and Cr
 *
 *  R-B = (k3*Cr - k4*Cb)
 *
 *    k3*Cr = k4*Cb + (R-B)
 *    k4*Cb = k3*Cr - (R-B)
 *
 *  R-G = (k1+k3)*Cr + k2*Cb
 *      = (k1+k3)*Cr + k2/k4*(k3*Cr - (R-B)/k0)
 *      = (k1 + k3 + k2*k3/k4)*Cr - k2/k4*(R-B)
 *
 *  kRr*Cr = (R-G) + kYb*(R-B)
 *
 *  Cr  = ((R-G) + kYb*(R-B))/kRr
 *  Cr0 = clamp(Cr + 128)
 */

static const double  kRr = (k1 + k3 + k2*k3/k4);

static void
initCrtab( void )
{
    uint8_t *pTable;
    int i;

    gCrTable = (uint8_t *)malloc(768*2);

    pTable = gCrTable + 384;
    for(i=-384; i<384; i++)
        pTable[i] = (uint8_t) clamp( i/kRr + 128.5 );
}

/*
 *  B-G = (k2 + k4)*Cb + k1*Cr
 *      = (k2 + k4)*Cb + k1/k3*(k4*Cb + (R-B))
 *      = (k2 + k4 + k1*k4/k3)*Cb + k1/k3*(R-B)
 *
 *  kBb*Cb = (B-G) - kYr*(R-B)
 *
 *  Cb   = ((B-G) - kYr*(R-B))/kBb
 *  Cb0  = clamp(Cb + 128)
 *
 */

static const double  kBb = (k2 + k4 + k1*k4/k3);

static void
initCbtab( void )
{
    uint8_t *pTable;
    int i;

    gCbTable = (uint8_t *)malloc(768*2);

    pTable = gCbTable + 384;
    for(i=-384; i<384; i++)
        pTable[i] = (uint8_t) clamp( i/kBb + 128.5 );
}

/*
 *   SHIFT2 = 16
 *
 *   DELTA = kYb*(1 << SHIFT2)
 *   GAMMA = kYr*(1 << SHIFT2)
 */

static const int  SHIFT2 = 16;
static const int  DELTA  = kYb*(1 << SHIFT2);
static const int  GAMMA  = kYr*(1 << SHIFT2);

int32_t ccrgb16toyuv_wo_colorkey(uint8_t *rgb16, uint8_t *yuv420,
        uint32_t *param, uint8_t *table[])
{
    uint16_t *inputRGB = (uint16_t*)rgb16;
    uint8_t *outYUV = yuv420;
    int32_t width_dst = param[0];
    int32_t height_dst = param[1];
    int32_t pitch_dst = param[2];
    int32_t mheight_dst = param[3];
    int32_t pitch_src = param[4];
    uint8_t *y_tab = table[0];
    uint8_t *cb_tab = table[1];
    uint8_t *cr_tab = table[2];

    int32_t size16 = pitch_dst*mheight_dst;
    int32_t i,j,count;
    int32_t ilimit,jlimit;
    uint8_t *tempY,*tempU,*tempV;
    uint16_t pixels;
    int   tmp;
uint32_t temp;

    tempY = outYUV;
    tempU = outYUV + (height_dst * pitch_dst);
    tempV = tempU + 1;

    jlimit = height_dst;
    ilimit = width_dst;

    for(j=0; j<jlimit; j+=1)
    {
        for (i=0; i<ilimit; i+=2)
        {
            int32_t   G_ds = 0, B_ds = 0, R_ds = 0;
            uint8_t   y0, y1, u, v;

            pixels =  inputRGB[i];
            temp = (BETA*(pixels & 0x001F) + ALPHA*(pixels>>11) );
            y0   = y_tab[(temp>>SHIFT1) + ((pixels>>3) & 0x00FC)];

            G_ds    += (pixels>>1) & 0x03E0;
            B_ds    += (pixels<<5) & 0x03E0;
            R_ds    += (pixels>>6) & 0x03E0;

            pixels =  inputRGB[i+1];
            temp = (BETA*(pixels & 0x001F) + ALPHA*(pixels>>11) );
            y1   = y_tab[(temp>>SHIFT1) + ((pixels>>3) & 0x00FC)];

            G_ds    += (pixels>>1) & 0x03E0;
            B_ds    += (pixels<<5) & 0x03E0;
            R_ds    += (pixels>>6) & 0x03E0;

            R_ds >>= 1;
            B_ds >>= 1;
            G_ds >>= 1;

            tmp = R_ds - B_ds;

            u = cb_tab[(((B_ds-G_ds)<<SHIFT2) - GAMMA*tmp)>>(SHIFT2+2)];
            v = cr_tab[(((R_ds-G_ds)<<SHIFT2) + DELTA*tmp)>>(SHIFT2+2)];

            tempY[0] = y0;
            tempY[1] = y1;
            tempY += 2;

            if ((j&1) == 0) {
                tempU[0] = u;
                tempV[0] = v;
                tempU += 2;
                tempV += 2;
            }
        }

        inputRGB += pitch_src;
    }

    return 1;
}

#define min(a,b) ((a)<(b)?(a):(b))
#define max(a,b) ((a)>(b)?(a):(b))

static void convert_rgb16_to_yuv420(uint8_t *rgb, uint8_t *yuv, int width, int height)
{
    if (!tables_initialized) {
        initYtab();
        initCrtab();
        initCbtab();
        tables_initialized = 1;
    }

    uint32_t param[6];
    param[0] = (uint32_t) width;
    param[1] = (uint32_t) height;
    param[2] = (uint32_t) width;
    param[3] = (uint32_t) height;
    param[4] = (uint32_t) width;
    param[5] = (uint32_t) 0;

    uint8_t *table[3];
    table[0] = gYTable;
    table[1] = gCbTable + 384;
    table[2] = gCrTable + 384;

    ccrgb16toyuv_wo_colorkey(rgb, yuv, param, table);
}

const int FakeCamera::kRed;
const int FakeCamera::kGreen;
const int FakeCamera::kBlue;

FakeCamera::FakeCamera(int width, int height)
          : mTmpRgb16Buffer(0)
{
    setSize(width, height);
}

FakeCamera::~FakeCamera()
{
    delete[] mTmpRgb16Buffer;
}

void FakeCamera::setSize(int width, int height)
{
    mWidth = width;
    mHeight = height;
    mCounter = 0;
    mCheckX = 0;
    mCheckY = 0;

    // This will cause it to be reallocated on the next call
    // to getNextFrameAsYuv420().
    delete[] mTmpRgb16Buffer;
    mTmpRgb16Buffer = 0;
}

void FakeCamera::getNextFrameAsRgb565(uint16_t *buffer)
{
    int size = mWidth / 10;

    drawCheckerboard(buffer, size);

    int x = ((mCounter*3)&255);
    if(x>128) x = 255 - x;
    int y = ((mCounter*5)&255);
    if(y>128) y = 255 - y;

    drawSquare(buffer, x*size/32, y*size/32, (size*5)>>1, (mCounter&0x100)?kRed:kGreen, kBlue);

    mCounter++;
}

void FakeCamera::getNextFrameAsYuv420(uint8_t *buffer)
{
    if (mTmpRgb16Buffer == 0)
        mTmpRgb16Buffer = new uint16_t[mWidth * mHeight];

    getNextFrameAsRgb565(mTmpRgb16Buffer);
    convert_rgb16_to_yuv420((uint8_t*)mTmpRgb16Buffer, buffer, mWidth, mHeight);
}

void FakeCamera::drawSquare(uint16_t *dst, int x, int y, int size, int color, int shadow)
{
    int square_xstop, square_ystop, shadow_xstop, shadow_ystop;

    square_xstop = min(mWidth, x+size);
    square_ystop = min(mHeight, y+size);
    shadow_xstop = min(mWidth, x+size+(size/4));
    shadow_ystop = min(mHeight, y+size+(size/4));

    // Do the shadow.
    uint16_t *sh = &dst[(y+(size/4))*mWidth];
    for (int j = y + (size/4); j < shadow_ystop; j++) {
        for (int i = x + (size/4); i < shadow_xstop; i++) {
            sh[i] &= shadow;
        }
        sh += mWidth;
    }

    // Draw the square.
    uint16_t *sq = &dst[y*mWidth];
    for (int j = y; j < square_ystop; j++) {
        for (int i = x; i < square_xstop; i++) {
            sq[i] = color;
        }
        sq += mWidth;
    }
}

void FakeCamera::drawCheckerboard(uint16_t *dst, int size)
{
    bool black = true;

    if((mCheckX/size)&1)
        black = false;
    if((mCheckY/size)&1)
        black = !black;

    int county = mCheckY%size;
    int checkxremainder = mCheckX%size;

    for(int y=0;y<mHeight;y++) {
        int countx = checkxremainder;
        bool current = black;
        for(int x=0;x<mWidth;x++) {
            dst[y*mWidth+x] = current?0:0xffff;
            if(countx++ >= size) {
                countx=0;
                current = !current;
            }
        }
        if(county++ >= size) {
            county=0;
            black = !black;
        }
    }
    mCheckX += 3;
    mCheckY++;
}


void FakeCamera::dump(int fd) const
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    snprintf(buffer, 255, " width x height (%d x %d), counter (%d), check x-y coordinate(%d, %d)\n", mWidth, mHeight, mCounter, mCheckX, mCheckY);
    result.append(buffer);
    ::write(fd, result.string(), result.size());
}


}; // namespace android
