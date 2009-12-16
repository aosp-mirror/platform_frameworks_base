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
*     -------------------------------------------------------------------   *
*                    MPEG-4 Simple Profile Video Decoder                    *
*     -------------------------------------------------------------------   *
*
* This software module was originally developed by
*
*   Paulo Nunes (IST / ACTS-MoMuSyS)
*
* and edited by
*
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
*****************************************************************************
***********************************************************HeaderBegin*******
*
* File: vlc_dec_tab.h
*
* Author:   Paulo Nunes (IST) - Paulo.Nunes@it.ist.utl.pt
* Created:  1-Mar-96
*
* Description: This file contains the VLC tables for module which deals
*       with VLC decoding.
*
* Notes:    This file was created based on tmndecode
*       Written by Karl Olav Lillevold <kol@nta.no>,
*       1995 Telenor R&D.
*       Donated to the Momusys-project as background code by
*       Telenor.
*
*       based on mpeg2decode, (C) 1994, MPEG Software Simulation Group
*       and mpeg2play, (C) 1994 Stefan Eckart
*                         <stefan@lis.e-technik.tu-muenchen.de>
*
*
* Modified:  9-May-96 Paulo Nunes: Reformatted. New headers.
*       14-May-96 Paulo Nunes: Changed TMNMVtabs according to VM2.1.
*   04.11.96 Robert Danielsen: Added three new tables for coding
*           of Intra luminance coefficients (VM 4.0)
*      01.05.97 Luis Ducla-Soares: added VM7.0 Reversible VLC tables (RVLC).
*      13.05.97 Minhua Zhou: added VlC tables for CBPYtab2 CBPYtab3,
*   revised  CBPYtab
*
***********************************************************HeaderEnd*********

This module is a header file for "vlc_decode.c".  The table data actually
resides in "vlc_tab.c".


------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef vlc_dec_tab_H
#define vlc_dec_tab_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "mp4def.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here.
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; EXTERNAL VARIABLES REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/
#ifdef __cplusplus
extern "C"
{
#endif

    extern const VLCshorttab PV_TMNMVtab0[];

    extern const VLCshorttab PV_TMNMVtab1[];

    extern const VLCshorttab PV_TMNMVtab2[];

    extern const VLCshorttab PV_MCBPCtab[];

#ifdef PV_ANNEX_IJKT_SUPPORT
    extern const VLCshorttab PV_MCBPCtab1[];
#endif
    extern const VLCshorttab PV_MCBPCtabintra[];

    /* Table for separate mode MCBPC, for coding DQUANT-flag and CBPC */

    extern const VLCshorttab MCBPCtab_sep[32];

    extern const VLCshorttab PV_CBPYtab[48];

    extern const VLCshorttab CBPYtab2[16];

    extern const VLCshorttab CBPYtab3[64];

    extern const VLCtab2 PV_DCT3Dtab0[];


    extern const VLCtab2 PV_DCT3Dtab1[];


    extern const VLCtab2 PV_DCT3Dtab2[];

    /* New tables for Intra luminance blocks */

    extern const VLCtab2 PV_DCT3Dtab3[];

    extern const VLCtab2 PV_DCT3Dtab4[];

    extern const VLCtab2 PV_DCT3Dtab5[];
#ifdef PV_ANNEX_IJKT_SUPPORT
    /* Annex I tables */
    extern const VLCtab2 PV_DCT3Dtab6[];

    extern const VLCtab2 PV_DCT3Dtab7[];

    extern const VLCtab2 PV_DCT3Dtab8[];
#endif
    /* RVLC tables */
    extern const int ptrRvlcTab[];

    extern const VLCtab2 RvlcDCTtabIntra[];

    extern const VLCtab2 RvlcDCTtabInter[];

    /*----------------------------------------------------------------------------
    ; SIMPLE TYPEDEF'S
    ----------------------------------------------------------------------------*/


    /*----------------------------------------------------------------------------
    ; ENUMERATED TYPEDEF'S
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; STRUCTURES TYPEDEF'S
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; GLOBAL FUNCTION DEFINITIONS
    ; Function Prototype declaration
    ----------------------------------------------------------------------------*/


    /*----------------------------------------------------------------------------
    ; END
    ----------------------------------------------------------------------------*/
#endif

#ifdef __cplusplus
}
#endif








