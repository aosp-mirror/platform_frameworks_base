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

   Filename: pvmp3_get_scale_factors.cpp

     Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY


 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Input
    mp3ScaleFactors *scalefac,
    mp3SideInfo *si,               side info
    int32 gr,                      granule
    int32 ch,                      channel
    tbits *pMainData               bit stream

  Returns

    mp3ScaleFactors *scalefac,   scale factors

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    get scale factors

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

#include "pvmp3_get_scale_factors.h"
#include "pvmp3_getbits.h"
#include "mp3_mem_funcs.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/
#define Qfmt_28(a)(int32(double(0x10000000)*a))

/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/
const int32 slen[2][16] =
{
    {0, 0, 0, 0, 3, 1, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4},
    {0, 1, 2, 3, 0, 1, 2, 3, 1, 2, 3, 1, 2, 3, 2, 3}
};

const struct
{
    int32 l[5];
    int32 s[3];
} sfbtable =
{
    {0, 6, 11, 16, 21},
    {0, 6, 12}
};

const int32 long_sfbtable[4] = { 6, 5, 5, 5};

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

void pvmp3_get_scale_factors(mp3ScaleFactors *scalefac,
                             mp3SideInfo    *si,
                             int32          gr,
                             int32          ch,
                             tmp3Bits       *pMainData)
{
    int32 sfb;
    int32 i;
    int32 window;
    granuleInfo *gr_info = &(si->ch[ch].gran[gr]);

    if (gr_info->window_switching_flag && (gr_info->block_type == 2))
    {
        if (gr_info->mixed_block_flag)
        { /* MIXED */
            for (sfb = 0; sfb < 8; sfb++)
            {
                scalefac->l[sfb] = getNbits(pMainData, slen[0][gr_info->scalefac_compress]);
            }

            for (sfb = 3; sfb < 6; sfb++)
            {
                for (window = 0; window < 3; window++)
                {
                    scalefac->s[window][sfb] = getNbits(pMainData, slen[0][gr_info->scalefac_compress]);
                }
            }
            for (sfb = 6; sfb < 12; sfb++)
            {
                for (window = 0; window < 3; window++)
                {
                    scalefac->s[window][sfb] = getNbits(pMainData, slen[1][gr_info->scalefac_compress]);
                }
            }
        }
        else
        {  /* SHORT*/
            for (i = 0; i < 2; i++)
            {
                for (sfb = sfbtable.s[i]; sfb < sfbtable.s[i+1]; sfb++)
                {
                    for (window = 0; window < 3; window++)
                    {
                        scalefac->s[window][sfb] = getNbits(pMainData, slen[i][gr_info->scalefac_compress]);
                    }
                }
            }
        }

        scalefac->s[0][12] = 0;    /* sfb = 12 win= 0 */
        scalefac->s[1][12] = 0;    /* sfb = 12 win= 1 */
        scalefac->s[2][12] = 0;    /* sfb = 12 win= 2 */
    }
    else
    {   /* LONG types 0,1,3 */

        int32 *ptr = &scalefac->l[0];

        for (i = 0; i < 4; i++)
        {
            int32 tmp4 = long_sfbtable[i];

            if ((si->ch[ch].scfsi[i] == 0) || (gr == 0))
            {
                int32 tmp1 = slen[(i>>1)][gr_info->scalefac_compress];

                if (tmp1)
                {
                    int32 tmp2 = tmp1 * tmp4;
                    uint32 tmp3 = getNbits(pMainData, tmp2);
                    tmp4 = 32 - tmp1;
                    for (; tmp2 > 0; tmp2 -= tmp1)
                    {
                        *(ptr++) = (tmp3 << (32 - tmp2)) >> tmp4;
                    }
                }
                else
                {
                    for (sfb = tmp4; sfb != 0; sfb--)
                    {
                        *(ptr++) = 0;
                    }

                }
            }
            else
            {
                ptr += tmp4;
            }
        }
        scalefac->l[21] = 0;
        scalefac->l[22] = 0;
    }
}

