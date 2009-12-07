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

   Filename: pvmp3_mpeg2_get_scale_data.cpp

     Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY


 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

Input
    mp3SideInfo     *si,                    side information
    int32           gr,                     granule
    int32           ch,                     channel
    mp3Header       *info,                  mp3 header information
    uint32          *scalefac_buffer,
    uint32          *scalefac_IIP_buffer,
    tbits           *pMainData               bit stream Data

 Returns

    uint32          *scalefac_buffer,       acquired scale band data
    uint32          *scalefac_IIP_buffer,   auxiliary scale data


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    get scale data for mpeg2 layer III LSF extension

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

#include "pvmp3_mpeg2_get_scale_data.h"
#include "pvmp3_getbits.h"


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

const uint32 nr_of_sfb_block[6][3][4] =
{   {{ 6,  5, 5, 5}, {  9,  9,  9, 9}, { 6,  9,  9, 9}},
    {{ 6,  5, 7, 3}, {  9,  9, 12, 6}, { 6,  9, 12, 6}},
    {{11, 10, 0, 0}, { 18, 18,  0, 0}, {15, 18,  0, 0}},
    {{ 7,  7, 7, 0}, { 12, 12, 12, 0}, { 6, 15, 12, 0}},
    {{ 6,  6, 6, 3}, { 12,  9,  9, 6}, { 6, 12,  9, 6}},
    {{ 8,  8, 5, 0}, { 15, 12,  9, 0}, { 6, 18,  9, 0}}
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

void pvmp3_mpeg2_get_scale_data(mp3SideInfo *si,
                                int32        gr,
                                int32        ch,
                                mp3Header   *info,
                                uint32      *scalefac_buffer,
                                uint32      *scalefac_IIP_buffer,
                                tmp3Bits    *pMainData)
{
    int16 i;
    int16 j;
    int16 k;
    int16 blocktypenumber = 0;
    int16 blocknumber = 0;

    granuleInfo *gr_info = &(si->ch[ch].gran[gr]);
    uint32 scalefac_comp, int_scalefac_comp, new_slen[4];

    scalefac_comp =  gr_info->scalefac_compress;



    if ((((info->mode_ext &1)) && (ch == 1)))
    {
        /*   intensity_scale = scalefac_comp %2; */
        int_scalefac_comp = scalefac_comp >> 1;

        if (int_scalefac_comp  < 180)
        {
            new_slen[0] = int_scalefac_comp  / 36;
            new_slen[1] = (int_scalefac_comp % 36) / 6;
            new_slen[2] = int_scalefac_comp % 6;
            blocknumber = 3;
        }
        else if (int_scalefac_comp  < 244)
        {
            int_scalefac_comp -= 180;
            new_slen[0] = (int_scalefac_comp & 63) >> 4;
            new_slen[1] = (int_scalefac_comp & 15) >> 2;
            new_slen[2] =  int_scalefac_comp &  3;
            blocknumber = 4;
        }
        else if (int_scalefac_comp  <= 255)
        {
            int_scalefac_comp -= 244;
            new_slen[0] = (int_scalefac_comp) / 3;
            new_slen[1] = (int_scalefac_comp) % 3;
            new_slen[2] = 0;
            blocknumber = 5;
        }
        new_slen[3] = 0;
        si->ch[ch].gran[gr].preflag = 0;
    }
    else
    {
        if (scalefac_comp < 400)
        {
            new_slen[0] = (scalefac_comp >> 4) / 5;
            new_slen[1] = (scalefac_comp >> 4) % 5;
            new_slen[2] = (scalefac_comp & 15) >> 2 ;
            new_slen[3] = (scalefac_comp & 3);
            si->ch[ch].gran[gr].preflag = 0;

            blocknumber = 0;
        }
        else if (scalefac_comp  < 500)
        {
            scalefac_comp -= 400;
            new_slen[0] = (scalefac_comp >> 2) / 5;
            new_slen[1] = (scalefac_comp >> 2) % 5;
            new_slen[2] = scalefac_comp  & 3;
            new_slen[3] = 0;
            si->ch[ch].gran[gr].preflag = 0;
            blocknumber = 1;
        }
        else if (scalefac_comp  < 512)
        {
            scalefac_comp -= 500;
            new_slen[0] = scalefac_comp / 3;
            new_slen[1] = scalefac_comp % 3;
            new_slen[2] = 0 ;
            new_slen[3] = 0;
            si->ch[ch].gran[gr].preflag = 1;
            blocknumber = 2;
        }
    }

    if (gr_info->block_type == 2)
    {
        if (gr_info->mixed_block_flag)
        {
            blocktypenumber = 2;
        }
        else
        {
            blocktypenumber = 1;
        }
    }

    k = 0;
    for (i = 0; i < 4; i++)
    {
        if (new_slen[i])
        {
            for (j = 0; j < (int16)nr_of_sfb_block[blocknumber][blocktypenumber][i]; j++)
            {
                scalefac_buffer[k] =  getNbits(pMainData, new_slen[i]);
                scalefac_IIP_buffer[k] = (1L << new_slen[i]) - 1;
                k++;
            }
        }
        else
        {
            for (j = 0; j < (int16)nr_of_sfb_block[blocknumber][blocktypenumber][i]; j++)
            {
                scalefac_buffer[k]     = 0;
                scalefac_IIP_buffer[k] = 0;
                k++;
            }
        }
    }
}
