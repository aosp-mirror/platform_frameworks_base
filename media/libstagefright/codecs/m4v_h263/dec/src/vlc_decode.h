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
-------------------------------------------------------------------
                    MPEG-4 Simple Profile Video Decoder
-------------------------------------------------------------------
*
* This software module was originally developed by
*
*   Paulo Nunes (IST / ACTS-MoMuSyS)
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
* File: vlc_dec.h
*
* Author:   Paulo Nunes (IST) - Paulo.Nunes@lx.it.pt
* Created:
*
* Description: This is the header file for the "vlcdec" module.
*
* Notes:
*
* Modified: 9-May-96 Paulo Nunes: Reformatted. New headers.
*
* ================= PacketVideo Modification ================================
*
*       3/30/00  : initial modification to the
*                new PV-Decoder Lib format.
*
***********************************************************CommentEnd********/


#ifndef _VLCDECODE_H_
#define _VLCDECODE_H_

#include "mp4lib_int.h"

#define VLC_ERROR_DETECTED(x) ((x) < 0)
#define VLC_IO_ERROR    -1
#define VLC_CODE_ERROR  -2
#define VLC_MB_STUFFING -4
#define VLC_NO_LAST_BIT -5

#define VLC_ESCAPE_CODE  7167

#ifdef __cplusplus
extern "C"
{
#endif /* __cplusplus */

    PV_STATUS DecodeUserData(BitstreamDecVideo *stream);
    PV_STATUS PV_GetMBvectors(VideoDecData *, uint mode);
    PV_STATUS PV_DecodeMBVec(BitstreamDecVideo *stream, MOT *mv_x, MOT *mv_y, int f_code_f);
    PV_STATUS PV_DeScaleMVD(int f_code, int residual, int vlc_code_mag,  MOT *vector);

    PV_STATUS PV_VlcDecMV(BitstreamDecVideo *stream, int *mv);
    int PV_VlcDecMCBPC_com_intra(BitstreamDecVideo *stream);
    int PV_VlcDecMCBPC_com_inter(BitstreamDecVideo *stream);
#ifdef PV_ANNEX_IJKT_SUPPORT
    int PV_VlcDecMCBPC_com_inter_H263(BitstreamDecVideo *stream);
    PV_STATUS VlcDecTCOEFShortHeader_AnnexI(BitstreamDecVideo *stream, Tcoef *pTcoef);
    PV_STATUS VlcDecTCOEFShortHeader_AnnexT(BitstreamDecVideo *stream, Tcoef *pTcoef); /* ANNEX_T */
    PV_STATUS VlcDecTCOEFShortHeader_AnnexIT(BitstreamDecVideo *stream, Tcoef *pTcoef);
#endif
    int PV_VlcDecCBPY(BitstreamDecVideo *stream, int intra);

    PV_STATUS VlcDecTCOEFIntra(BitstreamDecVideo *stream, Tcoef *pTcoef);
    PV_STATUS VlcDecTCOEFInter(BitstreamDecVideo *stream, Tcoef *pTcoef);
    PV_STATUS VlcDecTCOEFShortHeader(BitstreamDecVideo *stream, Tcoef *pTcoef);
    PV_STATUS RvlcDecTCOEFIntra(BitstreamDecVideo *stream, Tcoef *pTcoef);
    PV_STATUS RvlcDecTCOEFInter(BitstreamDecVideo *stream, Tcoef *pTcoef);
    PV_STATUS PV_VlcDecIntraDCPredSize(BitstreamDecVideo *stream, int compnum, uint *DC_size);

#ifdef __cplusplus
}
#endif /* __cplusplus  */

#endif

