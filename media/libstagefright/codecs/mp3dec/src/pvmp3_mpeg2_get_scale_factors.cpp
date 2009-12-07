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
------------------------------------------------------------------------------

   PacketVideo Corp.
   MP3 Decoder Library

   Filename: pvmp3_mpeg2_get_scale_factors.cpp

     Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY


 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

Input

    mp3ScaleFactors *scalefac,
    mp3SideInfo     *si,                    side information
    int32           gr,                     granule
    int32           ch,                     channel
    mp3Header       *info,                  mp3 header information
    uint32          *scalefac_IIP_buffer,   auxiliary scale data
    tbits           *pMainData               bit stream Data

 Returns

    III_scalefac_t  *scalefac,              scale factor


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    get scale factor for mpe2 layer III LSF extension

------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

 [1] ISO MPEG Audio Subgroup Software Simulation Group (1996)
     ISO 13818-3 MPEG-2 Audio Decoder - Lower Sampling Frequency Extension

------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#include "pvmp3_mpeg2_get_scale_factors.h"
#include "pvmp3_mpeg2_get_scale_data.h"


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

void pvmp3_mpeg2_get_scale_factors(mp3ScaleFactors *scalefac,
                                   mp3SideInfo     *si,
                                   int32           gr,
                                   int32           ch,
                                   mp3Header       *info,
                                   uint32          *scalefac_IIP_buffer,
                                   tmp3Bits        *pMainData)
{

    int32 sfb;
    int32 k = 0;
    int32 window;
    uint32 *scalefac_buffer     = &scalefac_IIP_buffer[56];

    granuleInfo *gr_info = &(si->ch[ch].gran[gr]);

    pvmp3_mpeg2_get_scale_data(si,
                               gr,
                               ch,
                               info,
                               (uint32 *)scalefac_buffer,
                               (uint32 *)scalefac_IIP_buffer,
                               pMainData);


    if (gr_info->window_switching_flag && (gr_info->block_type == 2))
    {
        if (gr_info->mixed_block_flag)
        {
            for (sfb = 0; sfb < 6; sfb++)
            {
                scalefac->l[sfb] = scalefac_buffer[sfb];
            }


            k = 6;
            for (sfb = 3; sfb < 12; sfb++)
            {
                for (window = 0; window < 3; window++)
                {
                    scalefac->s[window][sfb] = scalefac_buffer[k];
                    k++;
                }
            }


            /* adjust position of "illegal position" information in scalefac_IIP_buffer[] */
            /* in mixed blocks mode for short sfb, move them 3 places up. efs 3002-07-04  */
            for (sfb = 11; sfb >= 3; sfb--)
            {
                scalefac_IIP_buffer[3*sfb + 2] = scalefac_IIP_buffer[3*sfb - 1];
                scalefac_IIP_buffer[3*sfb + 1] = scalefac_IIP_buffer[3*sfb - 2];
                scalefac_IIP_buffer[3*sfb    ] = scalefac_IIP_buffer[3*sfb - 3];

            }
        }
        else
        {  /* SHORT*/
            for (sfb = 0; sfb < 12; sfb++)
            {
                for (window = 0; window < 3; window++)
                {
                    scalefac->s[window][sfb] = scalefac_buffer[k];
                    k++;
                }
            }
        }

        scalefac->s[0][12] = 0;
        scalefac->s[1][12] = 0;
        scalefac->s[2][12] = 0;

    }
    else
    {   /* LONG types 0,1,3 */
        for (sfb = 0; sfb < 21; sfb++)
        {
            scalefac->l[sfb] = scalefac_buffer[sfb];
        }
        scalefac->l[21] = 0;
        scalefac->l[22] = 0;

    }
}

