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

 Filename: get_sbr_stopfreq.c

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

     if(fs < 32000)
     {
         k1_min = (Int) ( ( (float) (6000 * 2 * 64) / fs ) + 0.5 );
     }
     else
     {
         if (fs < 64000)
         {
             k1_min = (Int) ( ( (float) (8000 * 2 * 64) / fs ) + 0.5 );
         }
         else
         {
             k1_min = (Int) ( ((float) (10000 * 2 * 64) / fs ) + 0.5);
         }
     }

     return((Int)( k1_min * pow( 64.0 / k1_min,(stop_freq)/13.0) + 0.5));

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#ifdef AAC_PLUS

#include    "get_sbr_stopfreq.h"
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

const UChar sbr_stopfreq_tbl[6][13] =
{

    { 21, 23, 25, 27, 29, 32, 35, 38, 41, 45, 49, 54, 59},  /* 48000  */
    { 23, 25, 27, 29, 31, 34, 37, 40, 43, 47, 51, 55, 59},  /* 44100  */
    { 32, 34, 36, 38, 40, 42, 44, 46, 49, 52, 55, 58, 61},  /* 32000  and 24000 */
    { 35, 36, 38, 40, 42, 44, 46, 48, 50, 52, 55, 58, 61},  /* 22050  */
    { 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 60, 62}   /* 16000  */

};

Int get_sbr_stopfreq(const Int32 fs,
                     const Int32 stop_freq)
{

    Int i;

    switch (fs)
    {
        case 48000:
            i = 0;
            break;

        case 32000:
        case 24000:
            i = 2;
            break;

        case 22050:
            i = 3;
            break;

        case 16000:
            i = 4;
            break;

        case 44100:
        default:
            i = 1;
            break;
    }

    return((Int)sbr_stopfreq_tbl[i][stop_freq]);

}



#endif
