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

 Filename: get_sbr_startfreq.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS



------------------------------------------------------------------------------
 FUNCTION DESCRIPTION


------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

SC 29 Software Copyright Licencing Disclaimer:

This software module was originally developed by
  Coding Technologies

and edited by
  -

in the course of development of the ISO/IEC 13818-7 and ISO/IEC 14496-3
standards for reference purposes and its performance may not have been
optimized. This software module is an implementation of one or more tools as
specified by the ISO/IEC 13818-7 and ISO/IEC 14496-3 standards.
ISO/IEC gives users free license to this software module or modifications
thereof for use in products claiming conformance to audiovisual and
image-coding related ITU Recommendations and/or ISO/IEC International
Standards. ISO/IEC gives users the same free license to this software module or
modifications thereof for research purposes and further ISO/IEC standardisation.
Those intending to use this software module in products are advised that its
use may infringe existing patents. ISO/IEC have no liability for use of this
software module or modifications thereof. Copyright is not released for
products that do not conform to audiovisual and image-coding related ITU
Recommendations and/or ISO/IEC International Standards.
The original developer retains full right to modify and use the code for its
own purpose, assign or donate the code to a third party and to inhibit third
parties from using the code for products that do not conform to audiovisual and
image-coding related ITU Recommendations and/or ISO/IEC International Standards.
This copyright notice must be included in all copies or derivative works.
Copyright (c) ISO/IEC 2002.

------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include    "get_sbr_startfreq.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/
const Int v_offset[7][16] =
{
    { -8, -7, -6, -5, -4, -3, -2, -1,  0,  1,  2,  3,  4,  5,  6,  7},
    { -5, -4, -3, -2, -1,  0,  1,  2,  3,  4,  5,  6,  7,  9, 11, 13},
    { -5, -3, -2, -1,  0,  1,  2,  3,  4,  5,  6,  7,  9, 11, 13, 16},
    { -6, -4, -2, -1,  0,  1,  2,  3,  4,  5,  6,  7,  9, 11, 13, 16},
    { -4, -2, -1,  0,  1,  2,  3,  4,  5,  6,  7,  9, 11, 13, 16, 20},
    { -2, -1,  0,  1,  2,  3,  4,  5,  6,  7,  9, 11, 13, 16, 20, 24},
    { 0,  1,  2,  3,  4,  5,  6,  7,  9, 11, 13, 16, 20, 24, 28, 33}
};

/*----------------------------------------------------------------------------
; EXTERNAL FUNCTION REFERENCES
; Declare functions defined elsewhere and referenced in this module
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL GLOBAL STORE/BUFFER/POINTER REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

Int get_sbr_startfreq(const Int32 fs,
                      const Int32 start_freq)
{
    Int k0_min = 0;
    Int32 index;


    switch (fs)
    {
        case 16000:
            index = 0;
            k0_min = 24;
            break;
        case 22050:
            index = 1;
            k0_min = 17;
            break;
        case 24000:
            index = 2;
            k0_min = 16;
            break;
        case 32000:
            index = 3;
            k0_min = 16;
            break;
        case 44100:
            index = 4;
            k0_min = 12;
            break;
        case 48000:
            index = 4;
            k0_min = 11;
            break;
        case 64000:
            index = 4;
            k0_min = 10;
            break;
        case 88200:
        case 96000:
            index = 5;
            k0_min = 7;
            break;

        default:
            index = 6;
    }
    return (k0_min + v_offset[index][start_freq]);

}



