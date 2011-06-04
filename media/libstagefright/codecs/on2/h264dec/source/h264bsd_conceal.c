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

/*------------------------------------------------------------------------------

    Table of contents

     1. Include headers
     2. External compiler flags
     3. Module defines
     4. Local function prototypes
     5. Functions
          h264bsdConceal
          ConcealMb
          Transform

------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    1. Include headers
------------------------------------------------------------------------------*/

#include "h264bsd_conceal.h"
#include "h264bsd_util.h"
#include "h264bsd_reconstruct.h"
#include "h264bsd_dpb.h"

/*------------------------------------------------------------------------------
    2. External compiler flags
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
    3. Module defines
------------------------------------------------------------------------------*/

/*lint -e702 disable lint warning on right shift of signed quantity */

/*------------------------------------------------------------------------------
    4. Local function prototypes
------------------------------------------------------------------------------*/

static u32 ConcealMb(mbStorage_t *pMb, image_t *currImage, u32 row, u32 col,
    u32 sliceType, u8 *data);

static void Transform(i32 *data);

/*------------------------------------------------------------------------------

    Function name: h264bsdConceal

        Functional description:
            Perform error concealment for a picture. Two types of concealment
            is performed based on sliceType:
                1) copy from previous picture for P-slices.
                2) concealment from neighbour pixels for I-slices

            I-type concealment is based on ideas presented by Jarno Tulkki.
            The concealment algorithm determines frequency domain coefficients
            from the neighbour pixels, applies integer transform (the same
            transform used in the residual processing) and uses the results as
            pixel values for concealed macroblocks. Transform produces 4x4
            array and one pixel value has to be used for 4x4 luma blocks and
            2x2 chroma blocks.

            Similar concealment is performed for whole picture (the choise
            of the type is based on last successfully decoded slice header of
            the picture but it is handled by the calling function). It is
            acknowledged that this may result in wrong type of concealment
            when a picture contains both types of slices. However,
            determination of slice type macroblock-by-macroblock cannot
            be done due to the fact that it is impossible to know to which
            slice each corrupted (not successfully decoded) macroblock
            belongs.

            The error concealment is started by searching the first propoerly
            decoded macroblock and concealing the row containing the macroblock
            in question. After that all macroblocks above the row in question
            are concealed. Finally concealment of rows below is performed.
            The order of concealment for 4x4 picture where macroblock 9 is the
            first properly decoded one is as follows (properly decoded
            macroblocks marked with 'x', numbers indicating the order of
            concealment):

               4  6  8 10
               3  5  7  9
               1  x  x  2
              11 12 13 14

            If all macroblocks of the picture are lost, the concealment is
            copy of previous picture for P-type and setting the image to
            constant gray (pixel value 128) for I-type.

            Concealment sets quantization parameter of the concealed
            macroblocks to value 40 and macroblock type to intra to enable
            deblocking filter to smooth the edges of the concealed areas.

        Inputs:
            pStorage        pointer to storage structure
            currImage       pointer to current image structure
            sliceType       type of the slice

        Outputs:
            currImage       concealed macroblocks will be written here

        Returns:
            HANTRO_OK

------------------------------------------------------------------------------*/

u32 h264bsdConceal(storage_t *pStorage, image_t *currImage, u32 sliceType)
{

/* Variables */

    u32 i, j;
    u32 row, col;
    u32 width, height;
    u8 *refData;
    mbStorage_t *mb;

/* Code */

    ASSERT(pStorage);
    ASSERT(currImage);

    DEBUG(("Concealing %s slice\n", IS_I_SLICE(sliceType) ?
            "intra" : "inter"));

    width = currImage->width;
    height = currImage->height;
    refData = NULL;
    /* use reference picture with smallest available index */
    if (IS_P_SLICE(sliceType) || (pStorage->intraConcealmentFlag != 0))
    {
        i = 0;
        do
        {
            refData = h264bsdGetRefPicData(pStorage->dpb, i);
            i++;
            if (i >= 16)
                break;
        } while (refData == NULL);
    }

    i = row = col = 0;
    /* find first properly decoded macroblock -> start point for concealment */
    while (i < pStorage->picSizeInMbs && !pStorage->mb[i].decoded)
    {
        i++;
        col++;
        if (col == width)
        {
            row++;
            col = 0;
        }
    }

    /* whole picture lost -> copy previous or set grey */
    if (i == pStorage->picSizeInMbs)
    {
        if ( (IS_I_SLICE(sliceType) && (pStorage->intraConcealmentFlag == 0)) ||
             refData == NULL)
            H264SwDecMemset(currImage->data, 128, width*height*384);
        else
            H264SwDecMemcpy(currImage->data, refData, width*height*384);

        pStorage->numConcealedMbs = pStorage->picSizeInMbs;

        /* no filtering if whole picture concealed */
        for (i = 0; i < pStorage->picSizeInMbs; i++)
            pStorage->mb[i].disableDeblockingFilterIdc = 1;

        return(HANTRO_OK);
    }

    /* start from the row containing the first correct macroblock, conceal the
     * row in question, all rows above that row and then continue downwards */
    mb = pStorage->mb + row * width;
    for (j = col; j--;)
    {
        ConcealMb(mb+j, currImage, row, j, sliceType, refData);
        mb[j].decoded = 1;
        pStorage->numConcealedMbs++;
    }
    for (j = col + 1; j < width; j++)
    {
        if (!mb[j].decoded)
        {
            ConcealMb(mb+j, currImage, row, j, sliceType, refData);
            mb[j].decoded = 1;
            pStorage->numConcealedMbs++;
        }
    }
    /* if previous row(s) could not be concealed -> conceal them now */
    if (row)
    {
        for (j = 0; j < width; j++)
        {
            i = row - 1;
            mb = pStorage->mb + i*width + j;
            do
            {
                ConcealMb(mb, currImage, i, j, sliceType, refData);
                mb->decoded = 1;
                pStorage->numConcealedMbs++;
                mb -= width;
            } while(i--);
        }
    }

    /* process rows below the one containing the first correct macroblock */
    for (i = row + 1; i < height; i++)
    {
        mb = pStorage->mb + i * width;

        for (j = 0; j < width; j++)
        {
            if (!mb[j].decoded)
            {
                ConcealMb(mb+j, currImage, i, j, sliceType, refData);
                mb[j].decoded = 1;
                pStorage->numConcealedMbs++;
            }
        }
    }

    return(HANTRO_OK);
}

/*------------------------------------------------------------------------------

    Function name: ConcealMb

        Functional description:
            Perform error concealment for one macroblock, location of the
            macroblock in the picture indicated by row and col

------------------------------------------------------------------------------*/

u32 ConcealMb(mbStorage_t *pMb, image_t *currImage, u32 row, u32 col,
    u32 sliceType, u8 *refData)
{

/* Variables */

    u32 i, j, comp;
    u32 hor, ver;
    u32 mbNum;
    u32 width, height;
    u8 *mbPos;
    u8 data[384];
    u8 *pData;
    i32 tmp;
    i32 firstPhase[16];
    i32 *pTmp;
    /* neighbours above, below, left and right */
    i32 a[4], b[4], l[4], r[4];
    u32 A, B, L, R;
#ifdef H264DEC_OMXDL
    u8 fillBuff[32*21 + 15 + 32];
    u8 *pFill;
#endif
/* Code */

    ASSERT(pMb);
    ASSERT(!pMb->decoded);
    ASSERT(currImage);
    ASSERT(col < currImage->width);
    ASSERT(row < currImage->height);

#ifdef H264DEC_OMXDL
    pFill = ALIGN(fillBuff, 16);
#endif
    width = currImage->width;
    height = currImage->height;
    mbNum = row * width + col;

    h264bsdSetCurrImageMbPointers(currImage, mbNum);

    mbPos = currImage->data + row * 16 * width * 16 + col * 16;
    A = B = L = R = HANTRO_FALSE;

    /* set qpY to 40 to enable some filtering in deblocking (stetson value) */
    pMb->qpY = 40;
    pMb->disableDeblockingFilterIdc = 0;
    /* mbType set to intra to perform filtering despite the values of other
     * boundary strength determination fields */
    pMb->mbType = I_4x4;
    pMb->filterOffsetA = 0;
    pMb->filterOffsetB = 0;
    pMb->chromaQpIndexOffset = 0;

    if (IS_I_SLICE(sliceType))
        H264SwDecMemset(data, 0, sizeof(data));
    else
    {
        mv_t mv = {0,0};
        image_t refImage;
        refImage.width = width;
        refImage.height = height;
        refImage.data = refData;
        if (refImage.data)
        {
#ifndef H264DEC_OMXDL
            h264bsdPredictSamples(data, &mv, &refImage, col*16, row*16,
                0, 0, 16, 16);
#else
            h264bsdPredictSamples(data, &mv, &refImage,
                    ((row*16) + ((col*16)<<16)),
                    0x00001010, pFill);
#endif
            h264bsdWriteMacroblock(currImage, data);

            return(HANTRO_OK);
        }
        else
            H264SwDecMemset(data, 0, sizeof(data));
    }

    H264SwDecMemset(firstPhase, 0, sizeof(firstPhase));

    /* counter for number of neighbours used */
    j = 0;
    hor = ver = 0;
    if (row && (pMb-width)->decoded)
    {
        A = HANTRO_TRUE;
        pData = mbPos - width*16;
        a[0] = *pData++; a[0] += *pData++; a[0] += *pData++; a[0] += *pData++;
        a[1] = *pData++; a[1] += *pData++; a[1] += *pData++; a[1] += *pData++;
        a[2] = *pData++; a[2] += *pData++; a[2] += *pData++; a[2] += *pData++;
        a[3] = *pData++; a[3] += *pData++; a[3] += *pData++; a[3] += *pData++;
        j++;
        hor++;
        firstPhase[0] += a[0] + a[1] + a[2] + a[3];
        firstPhase[1] += a[0] + a[1] - a[2] - a[3];
    }
    if ((row != height - 1) && (pMb+width)->decoded)
    {
        B = HANTRO_TRUE;
        pData = mbPos + 16*width*16;
        b[0] = *pData++; b[0] += *pData++; b[0] += *pData++; b[0] += *pData++;
        b[1] = *pData++; b[1] += *pData++; b[1] += *pData++; b[1] += *pData++;
        b[2] = *pData++; b[2] += *pData++; b[2] += *pData++; b[2] += *pData++;
        b[3] = *pData++; b[3] += *pData++; b[3] += *pData++; b[3] += *pData++;
        j++;
        hor++;
        firstPhase[0] += b[0] + b[1] + b[2] + b[3];
        firstPhase[1] += b[0] + b[1] - b[2] - b[3];
    }
    if (col && (pMb-1)->decoded)
    {
        L = HANTRO_TRUE;
        pData = mbPos - 1;
        l[0] = pData[0]; l[0] += pData[16*width];
        l[0] += pData[32*width]; l[0] += pData[48*width];
        pData += 64*width;
        l[1] = pData[0]; l[1] += pData[16*width];
        l[1] += pData[32*width]; l[1] += pData[48*width];
        pData += 64*width;
        l[2] = pData[0]; l[2] += pData[16*width];
        l[2] += pData[32*width]; l[2] += pData[48*width];
        pData += 64*width;
        l[3] = pData[0]; l[3] += pData[16*width];
        l[3] += pData[32*width]; l[3] += pData[48*width];
        j++;
        ver++;
        firstPhase[0] += l[0] + l[1] + l[2] + l[3];
        firstPhase[4] += l[0] + l[1] - l[2] - l[3];
    }
    if ((col != width - 1) && (pMb+1)->decoded)
    {
        R = HANTRO_TRUE;
        pData = mbPos + 16;
        r[0] = pData[0]; r[0] += pData[16*width];
        r[0] += pData[32*width]; r[0] += pData[48*width];
        pData += 64*width;
        r[1] = pData[0]; r[1] += pData[16*width];
        r[1] += pData[32*width]; r[1] += pData[48*width];
        pData += 64*width;
        r[2] = pData[0]; r[2] += pData[16*width];
        r[2] += pData[32*width]; r[2] += pData[48*width];
        pData += 64*width;
        r[3] = pData[0]; r[3] += pData[16*width];
        r[3] += pData[32*width]; r[3] += pData[48*width];
        j++;
        ver++;
        firstPhase[0] += r[0] + r[1] + r[2] + r[3];
        firstPhase[4] += r[0] + r[1] - r[2] - r[3];
    }

    /* at least one properly decoded neighbour available */
    ASSERT(j);

    /*lint -esym(644,l,r,a,b) variable initialized above */
    if (!hor && L && R)
        firstPhase[1] = (l[0]+l[1]+l[2]+l[3]-r[0]-r[1]-r[2]-r[3]) >> 5;
    else if (hor)
        firstPhase[1] >>= (3+hor);

    if (!ver && A && B)
        firstPhase[4] = (a[0]+a[1]+a[2]+a[3]-b[0]-b[1]-b[2]-b[3]) >> 5;
    else if (ver)
        firstPhase[4] >>= (3+ver);

    switch (j)
    {
        case 1:
            firstPhase[0] >>= 4;
            break;

        case 2:
            firstPhase[0] >>= 5;
            break;

        case 3:
            /* approximate (firstPhase[0]*4/3)>>6 */
            firstPhase[0] = (21 * firstPhase[0]) >> 10;
            break;

        default: /* 4 */
            firstPhase[0] >>= 6;
            break;

    }


    Transform(firstPhase);

    for (i = 0, pData = data, pTmp = firstPhase; i < 256;)
    {
        tmp = pTmp[(i & 0xF)>>2];
        /*lint -e734 CLIP1 macro results in value that fits into 8 bits */
        *pData++ = CLIP1(tmp);
        /*lint +e734 */

        i++;
        if (!(i & 0x3F))
            pTmp += 4;
    }

    /* chroma components */
    mbPos = currImage->data + width * height * 256 +
       row * 8 * width * 8 + col * 8;
    for (comp = 0; comp < 2; comp++)
    {

        H264SwDecMemset(firstPhase, 0, sizeof(firstPhase));

        /* counter for number of neighbours used */
        j = 0;
        hor = ver = 0;
        if (A)
        {
            pData = mbPos - width*8;
            a[0] = *pData++; a[0] += *pData++;
            a[1] = *pData++; a[1] += *pData++;
            a[2] = *pData++; a[2] += *pData++;
            a[3] = *pData++; a[3] += *pData++;
            j++;
            hor++;
            firstPhase[0] += a[0] + a[1] + a[2] + a[3];
            firstPhase[1] += a[0] + a[1] - a[2] - a[3];
        }
        if (B)
        {
            pData = mbPos + 8*width*8;
            b[0] = *pData++; b[0] += *pData++;
            b[1] = *pData++; b[1] += *pData++;
            b[2] = *pData++; b[2] += *pData++;
            b[3] = *pData++; b[3] += *pData++;
            j++;
            hor++;
            firstPhase[0] += b[0] + b[1] + b[2] + b[3];
            firstPhase[1] += b[0] + b[1] - b[2] - b[3];
        }
        if (L)
        {
            pData = mbPos - 1;
            l[0] = pData[0]; l[0] += pData[8*width];
            pData += 16*width;
            l[1] = pData[0]; l[1] += pData[8*width];
            pData += 16*width;
            l[2] = pData[0]; l[2] += pData[8*width];
            pData += 16*width;
            l[3] = pData[0]; l[3] += pData[8*width];
            j++;
            ver++;
            firstPhase[0] += l[0] + l[1] + l[2] + l[3];
            firstPhase[4] += l[0] + l[1] - l[2] - l[3];
        }
        if (R)
        {
            pData = mbPos + 8;
            r[0] = pData[0]; r[0] += pData[8*width];
            pData += 16*width;
            r[1] = pData[0]; r[1] += pData[8*width];
            pData += 16*width;
            r[2] = pData[0]; r[2] += pData[8*width];
            pData += 16*width;
            r[3] = pData[0]; r[3] += pData[8*width];
            j++;
            ver++;
            firstPhase[0] += r[0] + r[1] + r[2] + r[3];
            firstPhase[4] += r[0] + r[1] - r[2] - r[3];
        }
        if (!hor && L && R)
            firstPhase[1] = (l[0]+l[1]+l[2]+l[3]-r[0]-r[1]-r[2]-r[3]) >> 4;
        else if (hor)
            firstPhase[1] >>= (2+hor);

        if (!ver && A && B)
            firstPhase[4] = (a[0]+a[1]+a[2]+a[3]-b[0]-b[1]-b[2]-b[3]) >> 4;
        else if (ver)
            firstPhase[4] >>= (2+ver);

        switch (j)
        {
            case 1:
                firstPhase[0] >>= 3;
                break;

            case 2:
                firstPhase[0] >>= 4;
                break;

            case 3:
                /* approximate (firstPhase[0]*4/3)>>5 */
                firstPhase[0] = (21 * firstPhase[0]) >> 9;
                break;

            default: /* 4 */
                firstPhase[0] >>= 5;
                break;

        }

        Transform(firstPhase);

        pData = data + 256 + comp*64;
        for (i = 0, pTmp = firstPhase; i < 64;)
        {
            tmp = pTmp[(i & 0x7)>>1];
            /*lint -e734 CLIP1 macro results in value that fits into 8 bits */
            *pData++ = CLIP1(tmp);
            /*lint +e734 */

            i++;
            if (!(i & 0xF))
                pTmp += 4;
        }

        /* increment pointers for cr */
        mbPos += width * height * 64;
    }

    h264bsdWriteMacroblock(currImage, data);

    return(HANTRO_OK);

}


/*------------------------------------------------------------------------------

    Function name: Transform

        Functional description:
            Simplified transform, assuming that only dc component and lowest
            horizontal and lowest vertical component may be non-zero

------------------------------------------------------------------------------*/

void Transform(i32 *data)
{

    u32 col;
    i32 tmp0, tmp1;

    if (!data[1] && !data[4])
    {
        data[1]  = data[2]  = data[3]  = data[4]  = data[5]  =
        data[6]  = data[7]  = data[8]  = data[9]  = data[10] =
        data[11] = data[12] = data[13] = data[14] = data[15] = data[0];
        return;
    }
    /* first horizontal transform for rows 0 and 1 */
    tmp0 = data[0];
    tmp1 = data[1];
    data[0] = tmp0 + tmp1;
    data[1] = tmp0 + (tmp1>>1);
    data[2] = tmp0 - (tmp1>>1);
    data[3] = tmp0 - tmp1;

    tmp0 = data[4];
    data[5] = tmp0;
    data[6] = tmp0;
    data[7] = tmp0;

    /* then vertical transform */
    for (col = 4; col--; data++)
    {
        tmp0 = data[0];
        tmp1 = data[4];
        data[0] = tmp0 + tmp1;
        data[4] = tmp0 + (tmp1>>1);
        data[8] = tmp0 - (tmp1>>1);
        data[12] = tmp0 - tmp1;
    }

}
/*lint +e702 */

