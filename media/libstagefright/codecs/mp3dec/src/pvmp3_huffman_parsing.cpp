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

   Filename: pvmp3_huffman_decoding.cpp

 Funtions:
    pvmp3_huffman_quad_decoding
    pvmp3_huffman_pair_decoding
    pvmp3_huffman_pair_decoding_linbits

     Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY


 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    int32 is[],
    granuleInfo  *grInfo,    information for the given channel and granule
    tmp3dec_file   *pVars,   decoder state structure
    int32 part2_start,       index to beginning of part 2 data
    mp3Header *info          mp3 header info

 Outputs:
    int32 is[],              uncompressed data

  Return:
     non zero frequency lines

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

   These functions are used to decode huffman codewords from the input
   bitstream using combined binary search and look-up table approach.

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
#include "pv_mp3_huffman.h"
#include "s_mp3bits.h"
#include "mp3_mem_funcs.h"
#include "pvmp3_tables.h"


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

int32 pvmp3_huffman_parsing(int32 is[SUBBANDS_NUMBER*FILTERBANK_BANDS],
                            granuleInfo *grInfo,
                            tmp3dec_file   *pVars,
                            int32 part2_start,
                            mp3Header *info)


{
    int32 i;
    int32 region1Start;
    int32 region2Start;
    int32 sfreq;
    uint32 grBits;
    void(*pt_huff)(struct huffcodetab *, int32 *, tmp3Bits *);
    struct huffcodetab *h;

    tmp3Bits *pMainData = &pVars->mainDataStream;


    /*int32 bt = (*si).ch[ch].gr[gr].window_switching_flag && ((*si).ch[ch].gr[gr].block_type == 2);*/

    sfreq = info->sampling_frequency + info->version_x + (info->version_x << 1);

    /* Find region boundary for short block case. */


    if ((grInfo->window_switching_flag) && (grInfo->block_type == 2))
    {
        if (info->version_x == MPEG_1)
        {
            /* Region2. */
            region1Start = 12;
        }
        else
        {
            /* Region2. */
            i = grInfo->region0_count + 1;
            region1Start = mp3_sfBandIndex[sfreq].s[i/3];
        }

        region1Start += region1Start << 1;
        region2Start = 576; /* No Region2 for short block case. */
    }
    else
    {          /* Find region boundary for long block case. */
        i = grInfo->region0_count + 1;
        region1Start = mp3_sfBandIndex[sfreq].l[i];
        region2Start = mp3_sfBandIndex[sfreq].l[i + grInfo->region1_count + 1];
    }

    /* Read bigvalues area. */


    if (grInfo->big_values > (FILTERBANK_BANDS*SUBBANDS_NUMBER >> 1))
    {
        grInfo->big_values = (FILTERBANK_BANDS * SUBBANDS_NUMBER >> 1);
    }

    if ((grInfo->big_values << 1) > (uint32)region2Start)
    {
        h = &(pVars->ht[grInfo->table_select[0]]);
        if (h->linbits)
        {
            pt_huff = pvmp3_huffman_pair_decoding_linbits;
        }
        else
        {
            pt_huff = pvmp3_huffman_pair_decoding;
        }

        for (i = 0; i < region1Start; i += 2)
        {
            (*pt_huff)(h, &is[i], pMainData);
        }

        h = &(pVars->ht[grInfo->table_select[1]]);
        if (h->linbits)
        {
            pt_huff = pvmp3_huffman_pair_decoding_linbits;
        }
        else
        {
            pt_huff = pvmp3_huffman_pair_decoding;
        }

        for (; i < region2Start; i += 2)
        {
            (*pt_huff)(h, &is[i], pMainData);
        }

        h = &(pVars->ht[grInfo->table_select[2]]);
        if (h->linbits)
        {
            pt_huff = pvmp3_huffman_pair_decoding_linbits;
        }
        else
        {
            pt_huff = pvmp3_huffman_pair_decoding;
        }

        for (; (uint32)i < (grInfo->big_values << 1); i += 2)
        {
            (*pt_huff)(h, &is[i], pMainData);
        }
    }
    else if ((grInfo->big_values << 1) > (uint32)region1Start)
    {
        h = &(pVars->ht[grInfo->table_select[0]]);
        if (h->linbits)
        {
            pt_huff = pvmp3_huffman_pair_decoding_linbits;
        }
        else
        {
            pt_huff = pvmp3_huffman_pair_decoding;
        }
        for (i = 0; i < region1Start; i += 2)
        {
            (*pt_huff)(h, &is[i], pMainData);
        }

        h = &(pVars->ht[grInfo->table_select[1]]);
        if (h->linbits)
        {
            pt_huff = pvmp3_huffman_pair_decoding_linbits;
        }
        else
        {
            pt_huff = pvmp3_huffman_pair_decoding;
        }
        for (; (uint32)i < (grInfo->big_values << 1); i += 2)
        {
            (*pt_huff)(h, &is[i], pMainData);
        }
    }
    else
    {
        h = &(pVars->ht[grInfo->table_select[0]]);
        if (h->linbits)
        {
            pt_huff = pvmp3_huffman_pair_decoding_linbits;
        }
        else
        {
            pt_huff = pvmp3_huffman_pair_decoding;
        }

        for (i = 0; (uint32)i < (grInfo->big_values << 1); i += 2)
        {
            (*pt_huff)(h, &is[i], pMainData);
        }
    }



    /* Read count1 area. */
    h = &(pVars->ht[grInfo->count1table_select+32]);

    grBits     = part2_start + grInfo->part2_3_length;

    while ((pMainData->usedBits < grBits) &&
            (i < FILTERBANK_BANDS*SUBBANDS_NUMBER - 4))
    {
        pvmp3_huffman_quad_decoding(h, &is[i], pMainData);
        i += 4;
    }

    if ((pMainData->usedBits < grBits) &&
            (i < FILTERBANK_BANDS*SUBBANDS_NUMBER))
    {
        pvmp3_huffman_quad_decoding(h, &is[i], pMainData);
        i += 4;

        if ((i - 2) >= FILTERBANK_BANDS*SUBBANDS_NUMBER)
        {
            i -= 2;
            is[i] = 0;
            is[(i+1)] = 0;
        }
    }

    if (pMainData->usedBits > grBits)
    {
        i -= 4;

        if (i < 0 || i > FILTERBANK_BANDS*SUBBANDS_NUMBER - 4)
        {
            /* illegal parameters may cause invalid access, set i to 0 */
            i = 0;
        }

        is[i] = 0;
        is[(i+1)] = 0;
        is[(i+2)] = 0;
        is[(i+3)] = 0;

    }

    pMainData->usedBits = grBits;

    return (i);

}

