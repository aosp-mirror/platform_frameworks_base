/* ------------------------------------------------------------------
 * Copyright (C) 1998-2009 PacketVideo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 * -------------------------------------------------------------------
 */
/*
*     -------------------------------------------------------------------       *
*                    MPEG-4 Simple Profile Video Decoder                        *
*     -------------------------------------------------------------------       *
*
* This software module was originally developed by
*
*   Paulo Nunes (IST / ACTS-MoMuSyS)
*   Robert Danielsen (Telenor / ACTS-MoMuSyS)
*
* in the course of development of the MPEG-4 Video (ISO/IEC 14496-2) standard.
* This software module is an implementation of a part of one or more MPEG-4
* Video (ISO/IEC 14496-2) tools as specified by the MPEG-4 Video (ISO/IEC
* 14496-2) standard.
*
* ISO/IEC gives users of the MPEG-4 Video (ISO/IEC 14496-2) standard free
* license to this software module or modifications thereof for use in hardware
* or software products claiming conformance to the MPEG-4 Video (ISO/IEC
* 14496-2) standard.
*
* Those intending to use this software module in hardware or software products
* are advised that its use may infringe existing patents. The original
* developer of this software module and his/her company, the subsequent
* editors and their companies, and ISO/IEC have no liability for use of this
* software module or modifications thereof in an implementation. Copyright is
* not released for non MPEG-4 Video (ISO/IEC 14496-2) Standard conforming
* products.
*
* ACTS-MoMuSys partners retain full right to use the code for his/her own
* purpose, assign or donate the code to a third party and to inhibit third
* parties from using the code for non MPEG-4 Video (ISO/IEC 14496-2) Standard
* conforming products. This copyright notice must be included in all copies or
* derivative works.
*
* Copyright (c) 1996
*
*****************************************************************************/

/***********************************************************HeaderBegin*******
*
* File: vlc_dec.c
*
* Author:   Paulo Nunes (IST) - Paulo.Nunes@lx.it.pt
* Created:  1-Mar-96
*
* Description: This file contains the VLC functions needed to decode a
*       bitstream.
*
* Notes:
*       The functions contained in this file were adapted from
*       tmndecode
*       Written by Karl Olav Lillevold <kol@nta.no>,
*       1995 Telenor R&D.
*       Donated to the Momusys-project as background code by
*       Telenor.
*
*       based on mpeg2decode, (C) 1994, MPEG Software Simulation Group
*       and mpeg2play, (C) 1994 Stefan Eckart
*                   <stefan@lis.e-technik.tu-muenchen.de>
*
*
* Modified: 9-May-96 Paulo Nunes: Reformatted. New headers.
*              17-Jan-97 Jan De Lameillieure (HHI) : corrected in
*              01.05.97 Luis Ducla-Soares: added RvlcDecTCOEF() to allow decoding
*                                          of Reversible VLCs.
*       09.03.98 Paulo Nunes: Cleaning.
*
***********************************************************HeaderEnd*********/

#include "mp4dec_lib.h"
#include "vlc_dec_tab.h"
#include "vlc_decode.h"
#include "bitstream.h"
#include "max_level.h"


/* ====================================================================== /
    Function : DecodeUserData()
    Date     : 04/10/2000
    History  :
    Modified : 04/16/2001 : removed status checking of PV_BitstreamFlushBits

        This is simply a realization of the user_data() function
        in the ISO/IEC 14496-2 manual.
/ ====================================================================== */
PV_STATUS DecodeUserData(BitstreamDecVideo *stream)
{
    PV_STATUS status;
    uint32 code;

    BitstreamReadBits32HC(stream);
    BitstreamShowBits32(stream, 24, &code);

    while (code != 1)
    {
        /* Discard user data for now.   04/05/2000 */
        BitstreamReadBits16(stream, 8);
        BitstreamShowBits32(stream, 24, &code);
        status = BitstreamCheckEndBuffer(stream);
        if (status == PV_END_OF_VOP) return status;    /*  03/19/2002 */
    }
    return PV_SUCCESS;
}



/***********************************************************CommentBegin******
*
*       3/10/00  : initial modification to the
*                new PV-Decoder Lib format.
*       3/29/00  : added return code check to some functions and
*                optimize the code.
*
***********************************************************CommentEnd********/
PV_STATUS PV_GetMBvectors(VideoDecData *video, uint mode)
{
    PV_STATUS status;
    BitstreamDecVideo *stream = video->bitstream;
    int  f_code_f = video->currVop->fcodeForward;
    int  vlc_code_mag;


    MOT *mot_x = video->motX;
    MOT *mot_y = video->motY;

    int k, offset;
    int x_pos = video->mbnum_col;
    int y_pos = video->mbnum_row;
    int doubleWidth = video->nMBPerRow << 1;
    int pos = (x_pos + y_pos * doubleWidth) << 1;
    MOT mvx = 0, mvy = 0;


    if (f_code_f == 1)
    {
#ifdef PV_ANNEX_IJKT_SUPPORT
        if (mode == MODE_INTER4V || mode == MODE_INTER4V_Q)
#else
        if (mode == MODE_INTER4V)
#endif
        {
            for (k = 0; k < 4; k++)
            {
                offset = (k & 1) + (k >> 1) * doubleWidth;
                mv_prediction(video, k, &mvx, &mvy);
                /* decode component x */
                status = PV_VlcDecMV(stream, &vlc_code_mag);
                if (status != PV_SUCCESS)
                {
                    return status;
                }

                mvx += (MOT)vlc_code_mag;
                mvx = (MOT)(((mvx + 32) & 0x3F) - 32);


                status = PV_VlcDecMV(stream, &vlc_code_mag);
                if (status != PV_SUCCESS)
                {
                    return status;
                }

                mvy += (MOT)vlc_code_mag;
                mvy = (MOT)(((mvy + 32) & 0x3F) - 32);

                mot_x[pos+offset] = (MOT) mvx;
                mot_y[pos+offset] = (MOT) mvy;
            }
        }
        else
        {
            mv_prediction(video, 0, &mvx, &mvy);
            /* For PVOPs, field  appears only in MODE_INTER & MODE_INTER_Q */
            status = PV_VlcDecMV(stream, &vlc_code_mag);
            if (status != PV_SUCCESS)
            {
                return status;
            }

            mvx += (MOT)vlc_code_mag;
            mvx = (MOT)(((mvx + 32) & 0x3F) - 32);


            status = PV_VlcDecMV(stream, &vlc_code_mag);
            if (status != PV_SUCCESS)
            {
                return status;
            }


            mvy += (MOT)vlc_code_mag;
            mvy = (MOT)(((mvy + 32) & 0x3F) - 32);


            mot_x[pos] = mot_x[pos+1] = (MOT) mvx;
            mot_y[pos] = mot_y[pos+1] = (MOT) mvy;
            pos += doubleWidth;
            mot_x[pos] = mot_x[pos+1] = (MOT) mvx;
            mot_y[pos] = mot_y[pos+1] = (MOT) mvy;
        }
    }
    else
    {
#ifdef PV_ANNEX_IJKT_SUPPORT
        if (mode == MODE_INTER4V || mode == MODE_INTER4V_Q)
#else
        if (mode == MODE_INTER4V)
#endif
        {
            for (k = 0; k < 4; k++)
            {
                offset = (k & 1) + (k >> 1) * doubleWidth;
                mv_prediction(video, k, &mvx, &mvy);
                status = PV_DecodeMBVec(stream, &mvx, &mvy, f_code_f);
                mot_x[pos+offset] = (MOT) mvx;
                mot_y[pos+offset] = (MOT) mvy;
                if (status != PV_SUCCESS)
                {
                    return status;
                }
            }
        }
        else
        {
            mv_prediction(video, 0, &mvx, &mvy);
            /* For PVOPs, field  appears only in MODE_INTER & MODE_INTER_Q */
            status = PV_DecodeMBVec(stream, &mvx, &mvy, f_code_f);
            mot_x[pos] = mot_x[pos+1] = (MOT) mvx;
            mot_y[pos] = mot_y[pos+1] = (MOT) mvy;
            pos += doubleWidth;
            mot_x[pos] = mot_x[pos+1] = (MOT) mvx;
            mot_y[pos] = mot_y[pos+1] = (MOT) mvy;
            if (status != PV_SUCCESS)
            {
                return status;
            }
        }
    }
    return PV_SUCCESS;
}


/***********************************************************CommentBegin******
*       3/10/00  : initial modification to the
*                new PV-Decoder Lib format.
*       3/29/00  : added return code check to some functions
*       5/10/00  : check whether the decoded vector is legal.
*       4/17/01  : use MOT type
***********************************************************CommentEnd********/
PV_STATUS PV_DecodeMBVec(BitstreamDecVideo *stream, MOT *mv_x, MOT *mv_y, int f_code_f)
{
    PV_STATUS status;
    int  vlc_code_magx, vlc_code_magy;
    int  residualx = 0, residualy = 0;

    /* decode component x */
    status = PV_VlcDecMV(stream, &vlc_code_magx);
    if (status != PV_SUCCESS)
    {
        return status;
    }

    if (vlc_code_magx)
    {
        residualx = (int) BitstreamReadBits16_INLINE(stream, (int)(f_code_f - 1));
    }


    /* decode component y */
    status = PV_VlcDecMV(stream, &vlc_code_magy);
    if (status != PV_SUCCESS)
    {
        return status;
    }

    if (vlc_code_magy)
    {
        residualy = (int) BitstreamReadBits16_INLINE(stream, (int)(f_code_f - 1));
    }


    if (PV_DeScaleMVD(f_code_f, residualx, vlc_code_magx, mv_x) != PV_SUCCESS)
    {
        return PV_FAIL;
    }

    if (PV_DeScaleMVD(f_code_f, residualy, vlc_code_magy, mv_y) != PV_SUCCESS)
    {
        return PV_FAIL;
    }

    return PV_SUCCESS;
}


/***********************************************************CommentBegin******
*       3/31/2000 : initial modification to the new PV-Decoder Lib format.
*       5/10/2000 : check to see if the decoded vector falls within
*                           the legal fcode range.
*
***********************************************************CommentEnd********/
PV_STATUS PV_DeScaleMVD(
    int  f_code,       /* <-- MV range in 1/2 units: 1=32,2=64,...,7=2048     */
    int  residual,     /* <-- part of the MV Diff. FLC coded                  */
    int  vlc_code_mag, /* <-- part of the MV Diff. VLC coded                  */
    MOT  *vector       /* --> Obtained MV component in 1/2 units              */
)
{
    int   half_range = (1 << (f_code + 4));
    int   mask = (half_range << 1) - 1;
    int   diff_vector;


    if (vlc_code_mag == 0)
    {
        diff_vector = vlc_code_mag;
    }
    else
    {
        diff_vector = ((PV_ABS(vlc_code_mag) - 1) << (f_code - 1)) + residual + 1;
        if (vlc_code_mag < 0)
        {
            diff_vector = -diff_vector;
        }
    }

    *vector += (MOT)(diff_vector);

    *vector = (MOT)((*vector + half_range) & mask) - half_range;

    return PV_SUCCESS;
}



void mv_prediction(
    VideoDecData *video,
    int block,
    MOT *mvx,
    MOT *mvy
)
{
    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    MOT *motxdata = video->motX;
    MOT *motydata = video->motY;
    int mbnum_col = video->mbnum_col;
    int mbnum_row = video->mbnum_row;
    uint8 *slice_nb = video->sliceNo;
    int nMBPerRow = video->nMBPerRow;
    int nMVPerRow = nMBPerRow << 1;
    int mbnum = video->mbnum;
    int p1x = 0, p2x = 0, p3x = 0;
    int p1y = 0, p2y = 0, p3y = 0;
    int rule1 = 0, rule2 = 0, rule3 = 0;
    int     indx;

    indx = ((mbnum_col << 1) + (block & 1)) + ((mbnum_row << 1)  + (block >> 1)) * nMVPerRow - 1; /* left block */

    if (block & 1)           /* block 1, 3 */
    {
        p1x = motxdata[indx];
        p1y = motydata[indx];
        rule1 = 1;
    }
    else                    /* block 0, 2 */
    {
        if (mbnum_col > 0 && slice_nb[mbnum] == slice_nb[mbnum-1])
        {
            p1x = motxdata[indx];
            p1y = motydata[indx];
            rule1 = 1;
        }
    }

    indx = indx + 1 - nMVPerRow; /* upper_block */
    if (block >> 1)
    {
        indx -= (block & 1);
        p2x = motxdata[indx];
        p2y = motydata[indx];
        p3x = motxdata[indx + 1];
        p3y = motydata[indx + 1];
        rule2 = rule3 = 1;
    }
    else
    {                           /* block 0,1 */
        if (mbnum_row)
        {
            if (slice_nb[mbnum] == slice_nb[mbnum-nMBPerRow])
            {
                p2x = motxdata[indx];
                p2y = motydata[indx];
                rule2 = 1;
            }
            if (mbnum_col < nMBPerRow - 1 && slice_nb[mbnum] == slice_nb[mbnum-nMBPerRow+1])
            {
                indx = indx + 2 - (block & 1);
                p3x = motxdata[indx];
                p3y = motydata[indx];
                rule3 = 1;
            }
        }
    }

    if (rule1 + rule2 + rule3 > 1)
    {
        *mvx = (MOT)PV_MEDIAN(p1x, p2x, p3x);
        *mvy = (MOT)PV_MEDIAN(p1y, p2y, p3y);
    }
    else if (rule1 + rule2 + rule3 == 1)
    {
        /* two of three are zero */
        *mvx = (MOT)(p1x + p2x + p3x);
        *mvy = (MOT)(p1y + p2y + p3y);
    }
    else
    {
        /* all MBs are outside the VOP */
        *mvx = *mvy = 0;
    }
    /*----------------------------------------------------------------------------
    ; Return nothing or data or data pointer
    ----------------------------------------------------------------------------*/
    return;
}

/***********************************************************CommentBegin******
*
*       3/30/2000 : initial modification to the new PV-Decoder Lib format.
*       4/16/2001 : removed checking of status for PV_BitstreamFlushBits
***********************************************************CommentEnd********/

PV_STATUS PV_VlcDecMV(BitstreamDecVideo *stream, int *mv)
{
    PV_STATUS status = PV_SUCCESS;
    uint code;

    BitstreamShow13Bits(stream, &code);

    if (code >> 12)
    {
        *mv = 0; /* Vector difference = 0 */
        PV_BitstreamFlushBits(stream, 1);
        return PV_SUCCESS;
    }

    if (code >= 512)
    {
        code = (code >> 8) - 2;
        PV_BitstreamFlushBits(stream, PV_TMNMVtab0[code].len + 1);
        *mv = PV_TMNMVtab0[code].val;
        return status;
    }

    if (code >= 128)
    {
        code = (code >> 2) - 32;
        PV_BitstreamFlushBits(stream, PV_TMNMVtab1[code].len + 1);
        *mv = PV_TMNMVtab1[code].val;
        return status;
    }

    if (code < 4)
    {
        *mv = -1;
        return PV_FAIL;
    }

    code -= 4;

    PV_BitstreamFlushBits(stream, PV_TMNMVtab2[code].len + 1);

    *mv = PV_TMNMVtab2[code].val;
    return status;
}


/***********************************************************CommentBegin******
*       3/30/2000 : initial modification to the new PV-Decoder Lib
*                           format and the change of error-handling method.
*       4/16/01   : removed status checking of PV_BitstreamFlushBits
***********************************************************CommentEnd********/

int PV_VlcDecMCBPC_com_intra(BitstreamDecVideo *stream)
{
    uint code;

    BitstreamShowBits16(stream, 9, &code);


    if (code < 8)
    {
        return VLC_CODE_ERROR;
    }

    code >>= 3;

    if (code >= 32)
    {
        PV_BitstreamFlushBits(stream, 1);
        return 3;
    }

    PV_BitstreamFlushBits(stream, PV_MCBPCtabintra[code].len);

    return PV_MCBPCtabintra[code].val;
}


/***********************************************************CommentBegin******
*
*       3/30/2000 : initial modification to the new PV-Decoder Lib
*                           format and the change of error-handling method.
*       4/16/2001 : removed checking of return status of PV_BitstreamFlushBits
***********************************************************CommentEnd********/

int PV_VlcDecMCBPC_com_inter(BitstreamDecVideo *stream)
{
    uint code;

    BitstreamShowBits16(stream, 9, &code);

    if (code == 0)
    {
        return VLC_CODE_ERROR;
    }
    else if (code >= 256)
    {
        PV_BitstreamFlushBits(stream, 1);
        return 0;
    }

    PV_BitstreamFlushBits(stream, PV_MCBPCtab[code].len);
    return PV_MCBPCtab[code].val;
}

#ifdef PV_ANNEX_IJKT_SUPPORT
int PV_VlcDecMCBPC_com_inter_H263(BitstreamDecVideo *stream)
{
    uint code;

    BitstreamShow13Bits(stream, &code);

    if (code == 0)
    {
        return VLC_CODE_ERROR;
    }
    else if (code >= 4096)
    {
        PV_BitstreamFlushBits(stream, 1);
        return 0;
    }
    if (code >= 16)
    {
        PV_BitstreamFlushBits(stream, PV_MCBPCtab[code >> 4].len);
        return PV_MCBPCtab[code >> 4].val;
    }
    else
    {
        PV_BitstreamFlushBits(stream, PV_MCBPCtab1[code - 8].len);
        return PV_MCBPCtab1[code - 8].val;
    }
}
#endif
/***********************************************************CommentBegin******
*       3/30/2000 : initial modification to the new PV-Decoder Lib
*                           format and the change of error-handling method.
*       4/16/2001 : removed status checking for PV_BitstreamFlushBits
***********************************************************CommentEnd********/

int PV_VlcDecCBPY(BitstreamDecVideo *stream, int intra)
{
    int CBPY = 0;
    uint code;

    BitstreamShowBits16(stream, 6, &code);


    if (code < 2)
    {
        return -1;
    }
    else if (code >= 48)
    {
        PV_BitstreamFlushBits(stream, 2);
        CBPY = 15;
    }
    else
    {
        PV_BitstreamFlushBits(stream, PV_CBPYtab[code].len);
        CBPY = PV_CBPYtab[code].val;
    }

    if (intra == 0) CBPY = 15 - CBPY;
    CBPY = CBPY & 15;
    return CBPY;
}


/***********************************************************CommentBegin******
*       3/31/2000 : initial modification to the new PV-Decoder Lib format.
*
*       8/23/2000 : optimize the function by removing unnecessary BitstreamShowBits()
*                       function calls.
*
*       9/6/2000 : change the API to check for end-of-buffer for proper
*                           termination of decoding process.
***********************************************************CommentEnd********/
PV_STATUS PV_VlcDecIntraDCPredSize(BitstreamDecVideo *stream, int compnum, uint *DC_size)
{
    PV_STATUS status = PV_FAIL;      /*  07/09/01 */
    uint  code;

    *DC_size = 0;
    if (compnum < 4)  /* luminance block */
    {

        BitstreamShowBits16(stream, 11, &code);

        if (code == 1)
        {
            *DC_size = 12;
            PV_BitstreamFlushBits(stream, 11);
            return PV_SUCCESS;
        }
        code >>= 1;
        if (code == 1)
        {
            *DC_size = 11;
            PV_BitstreamFlushBits(stream, 10);
            return PV_SUCCESS;
        }
        code >>= 1;
        if (code == 1)
        {
            *DC_size = 10;
            PV_BitstreamFlushBits(stream, 9);
            return PV_SUCCESS;
        }

        code >>= 1;
        if (code == 1)
        {
            *DC_size = 9;
            PV_BitstreamFlushBits(stream, 8);
            return PV_SUCCESS;
        }

        code >>= 1;
        if (code == 1)
        {
            *DC_size = 8;
            PV_BitstreamFlushBits(stream, 7);
            return PV_SUCCESS;
        }

        code >>= 1;
        if (code == 1)
        {
            *DC_size = 7;
            PV_BitstreamFlushBits(stream, 6);
            return PV_SUCCESS;
        }

        code >>= 1;
        if (code == 1)
        {
            *DC_size = 6;
            PV_BitstreamFlushBits(stream, 5);
            return PV_SUCCESS;
        }

        code >>= 1;
        if (code == 1)
        {
            *DC_size = 5;
            PV_BitstreamFlushBits(stream, 4);
            return PV_SUCCESS;
        }

        code >>= 1;
        if (code == 1)
        {
            *DC_size = 4;
            PV_BitstreamFlushBits(stream, 3);
            return PV_SUCCESS;
        }
        else if (code == 2)
        {
            *DC_size = 3;
            PV_BitstreamFlushBits(stream, 3);
            return PV_SUCCESS;
        }
        else if (code == 3)
        {
            *DC_size = 0;
            PV_BitstreamFlushBits(stream, 3);
            return PV_SUCCESS;
        }

        code >>= 1;
        if (code == 2)
        {
            *DC_size = 2;
            PV_BitstreamFlushBits(stream, 2);
            return PV_SUCCESS;
        }
        else if (code == 3)
        {
            *DC_size = 1;
            PV_BitstreamFlushBits(stream, 2);
            return PV_SUCCESS;
        }
    }
    else /* chrominance block */
    {

        BitstreamShow13Bits(stream, &code);
        code >>= 1;
        if (code == 1)
        {
            *DC_size = 12;
            PV_BitstreamFlushBits(stream, 12);
            return PV_SUCCESS;
        }

        code >>= 1;
        if (code == 1)
        {
            *DC_size = 11;
            PV_BitstreamFlushBits(stream, 11);
            return PV_SUCCESS;
        }

        code >>= 1;
        if (code == 1)
        {
            *DC_size = 10;
            PV_BitstreamFlushBits(stream, 10);
            return PV_SUCCESS;
        }

        code >>= 1;
        if (code == 1)
        {
            *DC_size = 9;
            PV_BitstreamFlushBits(stream, 9);
            return PV_SUCCESS;
        }

        code >>= 1;
        if (code == 1)
        {
            *DC_size = 8;
            PV_BitstreamFlushBits(stream, 8);
            return PV_SUCCESS;
        }

        code >>= 1;
        if (code == 1)
        {
            *DC_size = 7;
            PV_BitstreamFlushBits(stream, 7);
            return PV_SUCCESS;
        }

        code >>= 1;
        if (code == 1)
        {
            *DC_size = 6;
            PV_BitstreamFlushBits(stream, 6);
            return PV_SUCCESS;
        }

        code >>= 1;
        if (code == 1)
        {
            *DC_size = 5;
            PV_BitstreamFlushBits(stream, 5);
            return PV_SUCCESS;
        }

        code >>= 1;
        if (code == 1)
        {
            *DC_size = 4;
            PV_BitstreamFlushBits(stream, 4);
            return PV_SUCCESS;
        }

        code >>= 1;
        if (code == 1)
        {
            *DC_size = 3;
            PV_BitstreamFlushBits(stream, 3);
            return PV_SUCCESS;
        }

        code >>= 1;
        {
            *DC_size = (int)(3 - code);
            PV_BitstreamFlushBits(stream, 2);
            return PV_SUCCESS;
        }
    }

    return status;
}

/***********************************************************CommentBegin******
*
*
*       3/30/2000 : initial modification to the new PV-Decoder Lib
*                           format and the change of error-handling method.
*
***********************************************************CommentEnd********/



PV_STATUS VlcDecTCOEFIntra(BitstreamDecVideo *stream, Tcoef *pTcoef)
{
    uint code;
    const VLCtab2 *tab;

    BitstreamShow13Bits(stream, &code);

    /* 10/17/2000, perform a little bit better on ARM by putting the whole function in VlcDecTCOEFFIntra */
    /*  if(GetTcoeffIntra(code,pTcoef,&tab,stream)!=PV_SUCCESS) return status;*/
    if (code >= 1024)
    {
        tab = &PV_DCT3Dtab3[(code >> 6) - 16];
    }
    else
    {
        if (code >= 256)
        {
            tab = &PV_DCT3Dtab4[(code >> 3) - 32];
        }
        else
        {
            if (code >= 16)
            {
                tab = &PV_DCT3Dtab5[(code>>1) - 8];
            }
            else
            {
                return PV_FAIL;
            }
        }
    }

    PV_BitstreamFlushBits(stream, tab->len + 1);
    pTcoef->sign = (code >> (12 - tab->len)) & 1;
    pTcoef->run = (uint) tab->run; //(tab->val >> 8) & 255;
    pTcoef->level = (int) tab->level; //tab->val & 255;
    pTcoef->last = (uint) tab->last; //(tab->val >> 16) & 1;


    /* the following is modified for 3-mode escape -- boon */
    if (tab->level != 0xFF)
    {
        return PV_SUCCESS;
    }

    //if (((tab->run<<8)|(tab->level)|(tab->last<<16)) == VLC_ESCAPE_CODE)

    if (!pTcoef->sign)
    {
        /* first escape mode. level is offset */
        BitstreamShow13Bits(stream, &code);

        /* 10/17/2000, perform a little bit better on ARM by putting the whole function in VlcDecTCOEFFIntra */
        /*          if(GetTcoeffIntra(code,pTcoef,&tab,stream)!=PV_SUCCESS) return status;*/
        if (code >= 1024)
        {
            tab = &PV_DCT3Dtab3[(code >> 6) - 16];
        }
        else
        {
            if (code >= 256)
            {
                tab = &PV_DCT3Dtab4[(code >> 3) - 32];
            }
            else
            {
                if (code >= 16)
                {
                    tab = &PV_DCT3Dtab5[(code>>1) - 8];
                }
                else
                {
                    return PV_FAIL;
                }
            }
        }

        PV_BitstreamFlushBits(stream, tab->len + 1);

        /* sign bit */
        pTcoef->sign = (code >> (12 - tab->len)) & 1;
        pTcoef->run = (uint)tab->run; //(tab->val >> 8) & 255;
        pTcoef->level = (int)tab->level; //tab->val & 255;
        pTcoef->last = (uint)tab->last; //(tab->val >> 16) & 1;


        /* need to add back the max level */
        if ((pTcoef->last == 0 && pTcoef->run > 14) || (pTcoef->last == 1 && pTcoef->run > 20))
        {
            return PV_FAIL;
        }
        pTcoef->level = pTcoef->level + intra_max_level[pTcoef->last][pTcoef->run];


    }
    else
    {
        uint run_offset;
        run_offset = BitstreamRead1Bits_INLINE(stream);

        if (!run_offset)
        {
            /* second escape mode. run is offset */
            BitstreamShow13Bits(stream, &code);

            /* 10/17/2000, perform a little bit better on ARM by putting the whole function in VlcDecTCOEFFIntra */
            /*              if(GetTcoeffIntra(code,pTcoef,&tab,stream)!=PV_SUCCESS) return status;*/
            if (code >= 1024)
            {
                tab = &PV_DCT3Dtab3[(code >> 6) - 16];
            }
            else
            {
                if (code >= 256)
                {
                    tab = &PV_DCT3Dtab4[(code >> 3) - 32];
                }
                else
                {
                    if (code >= 16)
                    {
                        tab = &PV_DCT3Dtab5[(code>>1) - 8];
                    }
                    else
                    {
                        return PV_FAIL;
                    }
                }
            }

            PV_BitstreamFlushBits(stream, tab->len + 1);
            /* sign bit */
            pTcoef->sign = (code >> (12 - tab->len)) & 1;
            pTcoef->run = (uint)tab->run; //(tab->val >> 8) & 255;
            pTcoef->level = (int)tab->level; //tab->val & 255;
            pTcoef->last = (uint)tab->last; //(tab->val >> 16) & 1;



            /* need to add back the max run */
            if (pTcoef->last)
            {
                if (pTcoef->level > 8)
                {
                    return PV_FAIL;
                }
                pTcoef->run = pTcoef->run + intra_max_run1[pTcoef->level] + 1;
            }
            else
            {
                if (pTcoef->level > 27)
                {
                    return PV_FAIL;
                }
                pTcoef->run = pTcoef->run + intra_max_run0[pTcoef->level] + 1;
            }


        }
        else
        {

            code = BitstreamReadBits16_INLINE(stream, 8);
            pTcoef->last = code >> 7;
            pTcoef->run = (code >> 1) & 0x3F;
            pTcoef->level = (int)(BitstreamReadBits16_INLINE(stream, 13) >> 1);

            if (pTcoef->level >= 2048)
            {
                pTcoef->sign = 1;
                pTcoef->level = 4096 - pTcoef->level;
            }
            else
            {
                pTcoef->sign = 0;
            }
        } /* flc */
    }

    return PV_SUCCESS;

} /* VlcDecTCOEFIntra */

PV_STATUS VlcDecTCOEFInter(BitstreamDecVideo *stream, Tcoef *pTcoef)
{
    uint code;
    const VLCtab2 *tab;

    BitstreamShow13Bits(stream, &code);

    /* 10/17/2000, perform a little bit better on ARM by putting the whole function in VlcDecTCOEFFInter */
    /*  if(GetTcoeffInter(code,pTcoef,&tab,stream)!=PV_SUCCESS) return status;*/
    if (code >= 1024)
    {
        tab = &PV_DCT3Dtab0[(code >> 6) - 16];
    }
    else
    {
        if (code >= 256)
        {
            tab = &PV_DCT3Dtab1[(code >> 3) - 32];
        }
        else
        {
            if (code >= 16)
            {
                tab = &PV_DCT3Dtab2[(code>>1) - 8];
            }
            else
            {
                return PV_FAIL;
            }
        }
    }
    PV_BitstreamFlushBits(stream, tab->len + 1);
    pTcoef->sign = (code >> (12 - tab->len)) & 1;
    pTcoef->run = (uint)tab->run;     //(tab->val >> 4) & 255;
    pTcoef->level = (int)tab->level; //tab->val & 15;
    pTcoef->last = (uint)tab->last;   //(tab->val >> 12) & 1;

    /* the following is modified for 3-mode escape -- boon */
    if (tab->run != 0xBF)
    {
        return PV_SUCCESS;
    }
    //if (((tab->run<<4)|(tab->level)|(tab->last<<12)) == VLC_ESCAPE_CODE)


    if (!pTcoef->sign)
    {
        /* first escape mode. level is offset */
        BitstreamShow13Bits(stream, &code);

        /* 10/17/2000, perform a little bit better on ARM by putting the whole function in VlcDecTCOEFFInter */
        /*          if(GetTcoeffInter(code,pTcoef,&tab,stream)!=PV_SUCCESS) return status;*/
        if (code >= 1024)
        {
            tab = &PV_DCT3Dtab0[(code >> 6) - 16];
        }
        else
        {
            if (code >= 256)
            {
                tab = &PV_DCT3Dtab1[(code >> 3) - 32];
            }
            else
            {
                if (code >= 16)
                {
                    tab = &PV_DCT3Dtab2[(code>>1) - 8];
                }
                else
                {
                    return PV_FAIL;
                }
            }
        }
        PV_BitstreamFlushBits(stream, tab->len + 1);
        pTcoef->sign = (code >> (12 - tab->len)) & 1;
        pTcoef->run = (uint)tab->run;     //(tab->val >> 4) & 255;
        pTcoef->level = (int)tab->level; //tab->val & 15;
        pTcoef->last = (uint)tab->last;   //(tab->val >> 12) & 1;

        /* need to add back the max level */
        if ((pTcoef->last == 0 && pTcoef->run > 26) || (pTcoef->last == 1 && pTcoef->run > 40))
        {
            return PV_FAIL;
        }
        pTcoef->level = pTcoef->level + inter_max_level[pTcoef->last][pTcoef->run];
    }
    else
    {
        uint run_offset;
        run_offset = BitstreamRead1Bits_INLINE(stream);

        if (!run_offset)
        {
            /* second escape mode. run is offset */
            BitstreamShow13Bits(stream, &code);

            /* 10/17/2000, perform a little bit better on ARM by putting the whole function in VlcDecTCOEFFInter */
            /*if(GetTcoeffInter(code,pTcoef,&tab,stream)!=PV_SUCCESS) return status;*/
            if (code >= 1024)
            {
                tab = &PV_DCT3Dtab0[(code >> 6) - 16];
            }
            else
            {
                if (code >= 256)
                {
                    tab = &PV_DCT3Dtab1[(code >> 3) - 32];
                }
                else
                {
                    if (code >= 16)
                    {
                        tab = &PV_DCT3Dtab2[(code>>1) - 8];
                    }
                    else
                    {
                        return PV_FAIL;
                    }
                }
            }
            PV_BitstreamFlushBits(stream, tab->len + 1);
            pTcoef->sign = (code >> (12 - tab->len)) & 1;
            pTcoef->run = (uint)tab->run;     //(tab->val >> 4) & 255;
            pTcoef->level = (int)tab->level; //tab->val & 15;
            pTcoef->last = (uint)tab->last;   //(tab->val >> 12) & 1;

            /* need to add back the max run */
            if (pTcoef->last)
            {
                if (pTcoef->level > 3)
                {
                    return PV_FAIL;
                }
                pTcoef->run = pTcoef->run + inter_max_run1[pTcoef->level] + 1;
            }
            else
            {
                if (pTcoef->level > 12)
                {
                    return PV_FAIL;
                }
                pTcoef->run = pTcoef->run + inter_max_run0[pTcoef->level] + 1;
            }
        }
        else
        {

            code = BitstreamReadBits16_INLINE(stream, 8);
            pTcoef->last = code >> 7;
            pTcoef->run = (code >> 1) & 0x3F;
            pTcoef->level = (int)(BitstreamReadBits16_INLINE(stream, 13) >> 1);



            if (pTcoef->level >= 2048)
            {
                pTcoef->sign = 1;
                pTcoef->level = 4096 - pTcoef->level;
            }
            else
            {
                pTcoef->sign = 0;
            }
        } /* flc */
    }

    return PV_SUCCESS;

} /* VlcDecTCOEFInter */

/*=======================================================
    Function:   VlcDecTCOEFShortHeader()
    Date    :   04/27/99
    Purpose :   New function used in decoding of video planes
                with short header
    Modified:   05/23/2000
                for new decoder structure.
=========================================================*/
PV_STATUS VlcDecTCOEFShortHeader(BitstreamDecVideo *stream, Tcoef *pTcoef/*, int intra*/)
{
    uint code;
    const VLCtab2 *tab;

    BitstreamShow13Bits(stream, &code);

    /*intra = 0;*/

    if (code >= 1024) tab = &PV_DCT3Dtab0[(code >> 6) - 16];
    else
    {
        if (code >= 256) tab = &PV_DCT3Dtab1[(code >> 3) - 32];
        else
        {
            if (code >= 16) tab = &PV_DCT3Dtab2[(code>>1) - 8];
            else return PV_FAIL;
        }
    }

    PV_BitstreamFlushBits(stream, tab->len + 1);
    pTcoef->sign = (code >> (12 - tab->len)) & 1;
    pTcoef->run = (uint)tab->run;//(tab->val >> 4) & 255;
    pTcoef->level = (int)tab->level;//tab->val & 15;
    pTcoef->last = (uint)tab->last;//(tab->val >> 12) & 1;

    /* the following is modified for 3-mode escape -- boon */
    if (((tab->run << 4) | (tab->level) | (tab->last << 12)) != VLC_ESCAPE_CODE)    /* ESCAPE */
    {
        return PV_SUCCESS;
    }


    /* escape mode 4 - H.263 type */
    pTcoef->last = pTcoef->sign; /* Last */
    pTcoef->run = BitstreamReadBits16_INLINE(stream, 6); /* Run */
    pTcoef->level = (int) BitstreamReadBits16_INLINE(stream, 8); /* Level */

    if (pTcoef->level == 0 || pTcoef->level == 128)
    {
        return PV_FAIL;
    }

    if (pTcoef->level > 128)
    {
        pTcoef->sign = 1;
        pTcoef->level = 256 - pTcoef->level;
    }
    else
    {
        pTcoef->sign = 0;
    }



    return PV_SUCCESS;

}   /* VlcDecTCOEFShortHeader */

#ifdef PV_ANNEX_IJKT_SUPPORT
PV_STATUS VlcDecTCOEFShortHeader_AnnexI(BitstreamDecVideo *stream, Tcoef *pTcoef/*, int intra*/)
{
    uint code;
    const VLCtab2 *tab;

    BitstreamShow13Bits(stream, &code);

    /*intra = 0;*/

    if (code >= 1024) tab = &PV_DCT3Dtab6[(code >> 6) - 16];
    else
    {
        if (code >= 256) tab = &PV_DCT3Dtab7[(code >> 3) - 32];
        else
        {
            if (code >= 16) tab = &PV_DCT3Dtab8[(code>>1) - 8];
            else return PV_FAIL;
        }
    }

    PV_BitstreamFlushBits(stream, tab->len + 1);
    pTcoef->sign = (code >> (12 - tab->len)) & 1;
    pTcoef->run = (uint)tab->run;//(tab->val >> 4) & 255;
    pTcoef->level = (int)tab->level;//tab->val & 15;
    pTcoef->last = (uint)tab->last;//(tab->val >> 12) & 1;

    /* the following is modified for 3-mode escape -- boon */
    if (((tab->run << 6) | (tab->level) | (tab->last << 12)) != VLC_ESCAPE_CODE)    /* ESCAPE */
    {
        return PV_SUCCESS;
    }
    /* escape mode 4 - H.263 type */
    pTcoef->last = pTcoef->sign; /* Last */
    pTcoef->run = BitstreamReadBits16(stream, 6); /* Run */
    pTcoef->level = (int) BitstreamReadBits16(stream, 8); /* Level */

    if (pTcoef->level == 0 || pTcoef->level == 128)
    {
        return PV_FAIL;
    }


    if (pTcoef->level > 128)
    {
        pTcoef->sign = 1;
        pTcoef->level = 256 - pTcoef->level;
    }
    else pTcoef->sign = 0;



    return PV_SUCCESS;

}   /* VlcDecTCOEFShortHeader_AnnexI */

PV_STATUS VlcDecTCOEFShortHeader_AnnexT(BitstreamDecVideo *stream, Tcoef *pTcoef/*, int intra*/)
{
    uint code;
    const VLCtab2 *tab;

    BitstreamShow13Bits(stream, &code);

    /*intra = 0;*/

    if (code >= 1024) tab = &PV_DCT3Dtab0[(code >> 6) - 16];
    else
    {
        if (code >= 256) tab = &PV_DCT3Dtab1[(code >> 3) - 32];
        else
        {
            if (code >= 16) tab = &PV_DCT3Dtab2[(code>>1) - 8];
            else return PV_FAIL;
        }
    }

    PV_BitstreamFlushBits(stream, tab->len + 1);
    pTcoef->sign = (code >> (12 - tab->len)) & 1;
    pTcoef->run = (uint)tab->run;//(tab->val >> 4) & 255;
    pTcoef->level = (int)tab->level;//tab->val & 15;
    pTcoef->last = (uint)tab->last;//(tab->val >> 12) & 1;

    /* the following is modified for 3-mode escape --  */
    if (((tab->run << 4) | (tab->level) | (tab->last << 12)) != VLC_ESCAPE_CODE)    /* ESCAPE */
    {
        return PV_SUCCESS;
    }
    /* escape mode 4 - H.263 type */
    pTcoef->last = pTcoef->sign; /* Last */
    pTcoef->run = BitstreamReadBits16(stream, 6); /* Run */
    pTcoef->level = (int) BitstreamReadBits16(stream, 8); /* Level */

    if (pTcoef->level == 0)
    {
        return PV_FAIL;
    }

    if (pTcoef->level >= 128)
    {
        pTcoef->sign = 1;
        pTcoef->level = 256 - pTcoef->level;
    }
    else
    {
        pTcoef->sign = 0;
    }

    if (pTcoef->level == 128)
    {
        code = BitstreamReadBits16(stream, 11);        /* ANNEX_T */

        code = (code >> 6 & 0x1F) | (code << 5 & 0x7ff);
        if (code > 1024)
        {
            pTcoef->sign = 1;
            pTcoef->level = (2048 - code);
        }
        else
        {
            pTcoef->sign = 0;
            pTcoef->level = code;
        }
    }

    return PV_SUCCESS;

}   /* VlcDecTCOEFShortHeader */


PV_STATUS VlcDecTCOEFShortHeader_AnnexIT(BitstreamDecVideo *stream, Tcoef *pTcoef/*, int intra*/)
{
    uint code;
    const VLCtab2 *tab;

    BitstreamShow13Bits(stream, &code);

    /*intra = 0;*/

    if (code >= 1024) tab = &PV_DCT3Dtab6[(code >> 6) - 16];
    else
    {
        if (code >= 256) tab = &PV_DCT3Dtab7[(code >> 3) - 32];
        else
        {
            if (code >= 16) tab = &PV_DCT3Dtab8[(code>>1) - 8];
            else return PV_FAIL;
        }
    }

    PV_BitstreamFlushBits(stream, tab->len + 1);
    pTcoef->sign = (code >> (12 - tab->len)) & 1;
    pTcoef->run = (uint)tab->run;//(tab->val >> 4) & 255;
    pTcoef->level = (int)tab->level;//tab->val & 15;
    pTcoef->last = (uint)tab->last;//(tab->val >> 12) & 1;

    /* the following is modified for 3-mode escape --  */
    if (((tab->run << 6) | (tab->level) | (tab->last << 12)) != VLC_ESCAPE_CODE)    /* ESCAPE */
    {
        return PV_SUCCESS;
    }
    /* escape mode 4 - H.263 type */
    pTcoef->last = pTcoef->sign; /* Last */
    pTcoef->run = BitstreamReadBits16(stream, 6); /* Run */
    pTcoef->level = (int) BitstreamReadBits16(stream, 8); /* Level */

    if (pTcoef->level == 0)
    {
        return PV_FAIL;
    }

    if (pTcoef->level >= 128)
    {
        pTcoef->sign = 1;
        pTcoef->level = 256 - pTcoef->level;
    }
    else
    {
        pTcoef->sign = 0;
    }

    if (pTcoef->level == 128)
    {
        code = BitstreamReadBits16(stream, 11);        /* ANNEX_T */

        code = (code >> 6 & 0x1F) | (code << 5 & 0x7ff);
        if (code > 1024)
        {
            pTcoef->sign = 1;
            pTcoef->level = (2048 - code);
        }
        else
        {
            pTcoef->sign = 0;
            pTcoef->level = code;
        }
    }


    return PV_SUCCESS;

}   /* VlcDecTCOEFShortHeader_AnnexI */
#endif
/***********************************************************CommentBegin******
*       3/30/2000 : initial modification to the new PV-Decoder Lib
*                           format and the change of error-handling method.
*                           The coefficient is now returned thru a pre-
*                           initialized parameters for speedup.
*
***********************************************************CommentEnd********/


PV_STATUS RvlcDecTCOEFInter(BitstreamDecVideo *stream, Tcoef *pTcoef)
{
    uint code, mask;
    const VLCtab2 *tab2;
    int count, len, num[2] = {0, 0} /*  01/30/01 */;

    mask = 0x4000;      /* mask  100000000000000   */
    BitstreamShow15Bits(stream, &code);   /*  03/07/01 */

    len = 1;

    //  09/20/99 Escape mode
    /// Bitstream Exchange
    if (code < 2048)
    {
        PV_BitstreamFlushBits(stream, 5);
        pTcoef->last = BitstreamRead1Bits_INLINE(stream);
        pTcoef->run = BitstreamReadBits16_INLINE(stream, 6);
        //  09/20/99 New marker bit
        PV_BitstreamFlushBits(stream, 1);
        //  09/20/99 The length for LEVEL used to be 7 in the old version
        pTcoef->level = (int)(BitstreamReadBits16_INLINE(stream, 12) >> 1);
        //  09/20/99 Another new marker bit
//      PV_BitstreamFlushBitsCheck(stream, 1);
        pTcoef->sign = BitstreamReadBits16_INLINE(stream, 5) & 0x1;  /* fix   3/13/01  */
        return PV_SUCCESS;
    }

    if (code & mask)
    {
        count = 1;
        while (mask && count > 0)       /* fix  3/28/01  */
        {
            mask = mask >> 1;
            if (code & mask)
                count--;
            else
                num[0]++; /* number of zeros in the middle */
            len++;
        }
    }
    else
    {
        count = 2;
        while (mask && count > 0)           /* fix  3/28/01  */
        {
            mask = mask >> 1;
            if (!(code & mask))
                count--;
            else
                num[count-1]++; /* number of ones in the middle */
            len++;
        }
    }

    code = code & 0x7fff;
    code = code >> (15 - (len + 1));

    /*  1/30/01, add fast decoding algorithm here */
    /* code is in two forms : 0xxxx0xxx00 or 0xxx0xxx01
                         num[1] and num[0] x
                        or  : 1xxxxx10 or 1xxxxx11
                                num[0]  x      */

    /* len+1 is the length of the above */

    if (num[1] > 10 || num[0] > 11) /* invalid RVLC code */
        return PV_FAIL;

    if (code&(1 << len))
        tab2 = RvlcDCTtabInter + 146 + (num[0] << 1) + (code & 1);
    else
        tab2 = RvlcDCTtabInter + ptrRvlcTab[num[1]] + (num[0] << 1) + (code & 1);

    PV_BitstreamFlushBits(stream, (int) tab2->len);
    pTcoef->run = (uint)tab2->run;//(tab->val >> 8) & 255;
    pTcoef->level = (int)tab2->level;//tab->val & 255;
    pTcoef->last = (uint)tab2->last;//(tab->val >> 16) & 1;

    pTcoef->sign = BitstreamRead1Bits_INLINE(stream);
    return PV_SUCCESS;
}               /* RvlcDecTCOEFInter */

PV_STATUS RvlcDecTCOEFIntra(BitstreamDecVideo *stream, Tcoef *pTcoef)
{
    uint code, mask;
    const VLCtab2 *tab2;
    int count, len, num[2] = {0, 0} /*  01/30/01 */;

    mask = 0x4000;      /* mask  100000000000000   */
    BitstreamShow15Bits(stream, &code);

    len = 1;

    //  09/20/99 Escape mode
    /// Bitstream Exchange
    if (code < 2048)
    {
        PV_BitstreamFlushBits(stream, 5);
        pTcoef->last = BitstreamRead1Bits_INLINE(stream);
        pTcoef->run = BitstreamReadBits16_INLINE(stream, 6);
        //  09/20/99 New marker bit
        PV_BitstreamFlushBits(stream, 1);
        //  09/20/99 The length for LEVEL used to be 7 in the old version
        pTcoef->level = (int)(BitstreamReadBits16_INLINE(stream, 12) >> 1);
        //  09/20/99 Another new marker bit
//      PV_BitstreamFlushBitsCheck(stream, 1);
        pTcoef->sign = BitstreamReadBits16_INLINE(stream, 5) & 0x1; /* fix   03/13/01 */
        return PV_SUCCESS;
    }

    if (code & mask)
    {
        count = 1;
        while (mask && count > 0)                          /* fix  03/28/01 */
        {
            mask = mask >> 1;
            if (code & mask)
                count--;
            else
                num[0]++; /* number of zeros in the middle */
            len++;
        }
    }
    else
    {
        count = 2;
        while (mask && count > 0)              /* fix  03/28/01 */
        {
            mask = mask >> 1;
            if (!(code & mask))
                count--;
            else
                num[count-1]++; /* number of ones in the middle */
            len++;
        }
    }

    code = code & 0x7fff;
    code = code >> (15 - (len + 1));

    /*  1/30/01, add fast decoding algorithm here */
    /* code is in two forms : 0xxxx0xxx00 or 0xxx0xxx01
                         num[1] and num[0] x
                        or  : 1xxxxx10 or 1xxxxx11
                                num[0]  x      */

    /* len+1 is the length of the above */

    if (num[1] > 10 || num[0] > 11) /* invalid RVLC code */
        return PV_FAIL;

    if (code & (1 << len))
        tab2 = RvlcDCTtabIntra + 146 + (num[0] << 1) + (code & 1);
    else
        tab2 = RvlcDCTtabIntra + ptrRvlcTab[num[1]] + (num[0] << 1) + (code & 1);

    PV_BitstreamFlushBits(stream, (int) tab2->len);
    pTcoef->run = (uint)tab2->run;//(tab->val >> 8) & 255;
    pTcoef->level = (int)tab2->level;//tab->val & 255;
    pTcoef->last = (uint)tab2->last;//(tab->val >> 16) & 1;

    pTcoef->sign = BitstreamRead1Bits_INLINE(stream);
    return PV_SUCCESS;
}               /* RvlcDecTCOEFIntra */

