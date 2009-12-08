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

   Filename: pvmp3_get_side_info.cpp

     Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY


 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

Input
    mp3SideInfo     *si,
    mp3Header       *info,             mp3 header information
    uint32          *crc               initialized crc value (if enabled)


 Returns

    mp3SideInfo     *si,               side information


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    acquires side information

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

#include "pvmp3_get_side_info.h"
#include "pvmp3_crc.h"


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

ERROR_CODE pvmp3_get_side_info(tmp3Bits    *inputStream,
                               mp3SideInfo *si,
                               mp3Header   *info,
                               uint32      *crc)
{
    int32 ch, gr;
    uint32 tmp;

    int stereo  = (info->mode == MPG_MD_MONO) ? 1 : 2;

    if (info->version_x == MPEG_1)
    {
        if (stereo == 1)
        {
            tmp = getbits_crc(inputStream, 14, crc, info->error_protection);
            si->main_data_begin = (tmp << 18) >> 23;    /* 9 */
            si->private_bits    = (tmp << 23) >> 27;    /* 5 */
        }
        else
        {
            tmp = getbits_crc(inputStream, 12, crc, info->error_protection);
            si->main_data_begin = (tmp << 20) >> 23;    /* 9 */
            si->private_bits    = (tmp << 23) >> 29;    /* 3 */

        }

        for (ch = 0; ch < stereo; ch++)
        {
            tmp = getbits_crc(inputStream, 4, crc, info->error_protection);
            si->ch[ch].scfsi[0] = (tmp << 28) >> 31;    /* 1 */
            si->ch[ch].scfsi[1] = (tmp << 29) >> 31;    /* 1 */
            si->ch[ch].scfsi[2] = (tmp << 30) >> 31;    /* 1 */
            si->ch[ch].scfsi[3] =  tmp & 1;         /* 1 */
        }

        for (gr = 0; gr < 2 ; gr++)
        {
            for (ch = 0; ch < stereo; ch++)
            {
                si->ch[ch].gran[gr].part2_3_length    = getbits_crc(inputStream, 12, crc, info->error_protection);
                tmp = getbits_crc(inputStream, 22, crc, info->error_protection);

                si->ch[ch].gran[gr].big_values            = (tmp << 10) >> 23;   /* 9 */
                si->ch[ch].gran[gr].global_gain           = ((tmp << 19) >> 24) - 210;   /* 8 */
                si->ch[ch].gran[gr].scalefac_compress     = (tmp << 27) >> 28;   /* 4 */
                si->ch[ch].gran[gr].window_switching_flag = tmp & 1;         /* 1 */

                if (si->ch[ch].gran[gr].window_switching_flag)
                {
                    tmp = getbits_crc(inputStream, 22, crc, info->error_protection);

                    si->ch[ch].gran[gr].block_type       = (tmp << 10) >> 30;   /* 2 */;
                    si->ch[ch].gran[gr].mixed_block_flag = (tmp << 12) >> 31;   /* 1 */;

                    si->ch[ch].gran[gr].table_select[0]  = (tmp << 13) >> 27;   /* 5 */;
                    si->ch[ch].gran[gr].table_select[1]  = (tmp << 18) >> 27;   /* 5 */;

                    si->ch[ch].gran[gr].subblock_gain[0] = (tmp << 23) >> 29;   /* 3 */;
                    si->ch[ch].gran[gr].subblock_gain[1] = (tmp << 26) >> 29;   /* 3 */;
                    si->ch[ch].gran[gr].subblock_gain[2] = (tmp << 29) >> 29;   /* 3 */;

                    /* Set region_count parameters since they are implicit in this case. */

                    if (si->ch[ch].gran[gr].block_type == 0)
                    {
                        return(SIDE_INFO_ERROR);
                    }
                    else if ((si->ch[ch].gran[gr].block_type       == 2)
                             && (si->ch[ch].gran[gr].mixed_block_flag == 0))
                    {
                        si->ch[ch].gran[gr].region0_count = 8; /* MI 9; */
                        si->ch[ch].gran[gr].region1_count = 12;
                    }
                    else
                    {
                        si->ch[ch].gran[gr].region0_count = 7; /* MI 8; */
                        si->ch[ch].gran[gr].region1_count = 13;
                    }
                }
                else
                {
                    tmp = getbits_crc(inputStream, 22, crc, info->error_protection);

                    si->ch[ch].gran[gr].table_select[0] = (tmp << 10) >> 27;   /* 5 */;
                    si->ch[ch].gran[gr].table_select[1] = (tmp << 15) >> 27;   /* 5 */;
                    si->ch[ch].gran[gr].table_select[2] = (tmp << 20) >> 27;   /* 5 */;

                    si->ch[ch].gran[gr].region0_count   = (tmp << 25) >> 28;   /* 4 */;
                    si->ch[ch].gran[gr].region1_count   = (tmp << 29) >> 29;   /* 3 */;

                    si->ch[ch].gran[gr].block_type      = 0;
                }

                tmp = getbits_crc(inputStream, 3, crc, info->error_protection);
                si->ch[ch].gran[gr].preflag            = (tmp << 29) >> 31;    /* 1 */
                si->ch[ch].gran[gr].scalefac_scale     = (tmp << 30) >> 31;    /* 1 */
                si->ch[ch].gran[gr].count1table_select =  tmp & 1;         /* 1 */
            }
        }
    }
    else /* Layer 3 LSF */
    {
        si->main_data_begin = getbits_crc(inputStream,      8, crc, info->error_protection);
        si->private_bits    = getbits_crc(inputStream, stereo, crc, info->error_protection);

        for (ch = 0; ch < stereo; ch++)
        {
            tmp = getbits_crc(inputStream, 21, crc, info->error_protection);
            si->ch[ch].gran[0].part2_3_length    = (tmp << 11) >> 20;  /* 12 */
            si->ch[ch].gran[0].big_values        = (tmp << 23) >> 23;  /*  9 */

            tmp = getbits_crc(inputStream, 18, crc, info->error_protection);
            si->ch[ch].gran[0].global_gain           = ((tmp << 14) >> 24) - 210;   /* 8 */
            si->ch[ch].gran[0].scalefac_compress     = (tmp << 22) >> 23;   /* 9 */
            si->ch[ch].gran[0].window_switching_flag = tmp & 1;         /* 1 */

            if (si->ch[ch].gran[0].window_switching_flag)
            {

                tmp = getbits_crc(inputStream, 22, crc, info->error_protection);

                si->ch[ch].gran[0].block_type       = (tmp << 10) >> 30;   /* 2 */;
                si->ch[ch].gran[0].mixed_block_flag = (tmp << 12) >> 31;   /* 1 */;

                si->ch[ch].gran[0].table_select[0]  = (tmp << 13) >> 27;   /* 5 */;
                si->ch[ch].gran[0].table_select[1]  = (tmp << 18) >> 27;   /* 5 */;

                si->ch[ch].gran[0].subblock_gain[0] = (tmp << 23) >> 29;   /* 3 */;
                si->ch[ch].gran[0].subblock_gain[1] = (tmp << 26) >> 29;   /* 3 */;
                si->ch[ch].gran[0].subblock_gain[2] = (tmp << 29) >> 29;   /* 3 */;

                /* Set region_count parameters since they are implicit in this case. */

                if (si->ch[ch].gran[0].block_type == 0)
                {
                    return(SIDE_INFO_ERROR);
                }
                else if ((si->ch[ch].gran[0].block_type       == 2)
                         && (si->ch[ch].gran[0].mixed_block_flag == 0))
                {
                    si->ch[ch].gran[0].region0_count = 8; /* MI 9; */
                    si->ch[ch].gran[0].region1_count = 12;
                }
                else
                {
                    si->ch[ch].gran[0].region0_count = 7; /* MI 8; */
                    si->ch[ch].gran[0].region1_count = 13;
                }
            }
            else
            {
                tmp = getbits_crc(inputStream, 22, crc, info->error_protection);

                si->ch[ch].gran[0].table_select[0] = (tmp << 10) >> 27;   /* 5 */;
                si->ch[ch].gran[0].table_select[1] = (tmp << 15) >> 27;   /* 5 */;
                si->ch[ch].gran[0].table_select[2] = (tmp << 20) >> 27;   /* 5 */;

                si->ch[ch].gran[0].region0_count   = (tmp << 25) >> 28;   /* 4 */;
                si->ch[ch].gran[0].region1_count   = (tmp << 29) >> 29;   /* 3 */;

                si->ch[ch].gran[0].block_type      = 0;
            }

            tmp = getbits_crc(inputStream, 2, crc, info->error_protection);
            si->ch[ch].gran[0].scalefac_scale     =  tmp >> 1;  /* 1 */
            si->ch[ch].gran[0].count1table_select =  tmp & 1;  /* 1 */

        }
    }
    return (NO_DECODING_ERROR);
}

