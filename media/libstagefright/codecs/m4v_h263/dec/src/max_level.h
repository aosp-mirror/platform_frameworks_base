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
*   Michael Wollborn (TUH / ACTS-MoMuSyS)
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
* Copyright (c) 1997
*
*****************************************************************************

This is a header file for "vlc_decode.c".  The table data actually resides
in "vlc_tab.c".


------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/

#ifndef max_level_H
#define max_level_H

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

    extern const int intra_max_level[2][NCOEFF_BLOCK];

    extern const int inter_max_level[2][NCOEFF_BLOCK];

    extern const int intra_max_run0[28];


    extern const int intra_max_run1[9];

    extern const int inter_max_run0[13];


    extern const int inter_max_run1[4];


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

#ifdef __cplusplus
}
#endif

#endif













