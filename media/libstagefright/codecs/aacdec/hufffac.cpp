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

 Pathname: ./src/hufffac.c
 Funtions:
    hufffac

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Modified from original shareware code

 Description:  Modified to pass variables by reference to eliminate use
               of global variables.

 Description: (1) Modified with new templates,
              (2) Modified variable names for clarity
              (3) adjusted variables of "for loop"
              (4) eliminated multiple returns, use return valid

 Description: (1) Change return logic: 0 if success, 1 if error
              (2) Define SectInfo structure to store section codebook index
                  and section boundary
              (3) Substitute "switch" with "if- else if"
              (4) move BITS *pInputStream to second pass-in parameter
              (5) pass in huffBookUsed[] to save stack size

 Description: (1) Remove pass in parameter Hcb pBook

 Description: Use binary tree search in decode_huff_cw_binary

 Description: Use decode_huff_scl function.

 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:

    *pFrameInfo     = pointer to structure that holds information
                      of each Frame. type FrameInfo

    *pInputStream   = pointer to input bitstream. type BITS

    *pGroup         = pointer to array that contains the index of the first
                      window in each group, type UChar

    nsect           = number of sections to be decoded. type Int

    *pSect          = pointer to structure array that contains the huffman
                      codebook index and section boundary for each section,
                      type SectInfo

    global_gain     = initial value for "DPCM encoded" scalefactors and noise
                      energy, type Int

    *pFactors       = pointer to array that stores the decoded scalefactors,
                      intensity position or noise energy, type Int

    huffBookUsed    = array that will hold the huffman codebook index for
                      each sfb, type Int

    *pBook          = pointer to structure that contains the huffman codebook
                      information, such as dimension, Largest Absolute Value
                      (LAV) of each huffman codebook, etc. type Hcb


 Local Stores/Buffers/Pointers Needed: None

 Global Stores/Buffers/Pointers Needed: None

 Outputs:
         0 if success
         1 if error

 Pointers and Buffers Modified:

        Int   *pFactors    contains the newly decoded scalefactors and/or
                             intensity position and/or noise energy level

 Local Stores Modified:

 Global Stores Modified:

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function first reads the Huffman codebook index of all sections within
 one Frame. Then, depending on the huffman codebook index of each section,
 the function decodes the scalefactors, and/or intensity positions
 (INTENSITY_HCB, INTENSITY_HCB2), and/or noise energy (NOISE_HCB)
 for every scalefactor band in each section.
 The function returns 0 upon successful decoding, returns 1 if error.

------------------------------------------------------------------------------
 REQUIREMENTS

 This function should replace the content of the array pFactors with the
 decoded scalefactors and/or intensity positions and/or noise energy

------------------------------------------------------------------------------
 REFERENCES

 (1) MPEG-2 NBC Audio Decoder
   "This software module was originally developed by AT&T, Dolby
   Laboratories, Fraunhofer Gesellschaft IIS in the course of development
   of the MPEG-2 NBC/MPEG-4 Audio standard ISO/IEC 13818-7, 14496-1,2 and
   3. This software module is an implementation of a part of one or more
   MPEG-2 NBC/MPEG-4 Audio tools as specified by the MPEG-2 NBC/MPEG-4
   Audio standard. ISO/IEC  gives users of the MPEG-2 NBC/MPEG-4 Audio
   standards free license to this software module or modifications thereof
   for use in hardware or software products claiming conformance to the
   MPEG-2 NBC/MPEG-4 Audio  standards. Those intending to use this software
   module in hardware or software products are advised that this use may
   infringe existing patents. The original developer of this software
   module and his/her company, the subsequent editors and their companies,
   and ISO/IEC have no liability for use of this software module or
   modifications thereof in an implementation. Copyright is not released
   for non MPEG-2 NBC/MPEG-4 Audio conforming products.The original
   developer retains full right to use the code for his/her own purpose,
   assign or donate the code to a third party and to inhibit third party
   from using the code for non MPEG-2 NBC/MPEG-4 Audio conforming products.
   This copyright notice must be included in all copies or derivative
   works."
   Copyright(c)1996.

 (2) ISO/IEC 14496-3: 1999(E)
     Subpart 4      p72-73  (scalefactors)
                    p76     (decoding)
                    p78     (Table 4.6.1, Table 4.6.2)
                    p93-94  (INTENSITY_HCB)
                    p123    (NOISE_HCB)

------------------------------------------------------------------------------
 PSEUDO-CODE

 status = SUCCESS;

 CALL pv_memset(pHuffBookUsed, ZERO_HCB, MAXBANDS*sizeof(*pHuffBookUsed));

 CALL pv_memset(pFactors, ZERO_HCB, MAXBANDS*sizeof(*pFactors));

 sect_start       = 0;

 FOR(sect_idx = nsect; sect_idx > 0; sect_idx--)
 {
     sect_cb  = pSect->sect_cb;
     sect_end = pSect->sect_end;
     pSect++;

     CALL pv_memset(
        &pHuffBookUsed[sect_start],
        sect_cb,
        (sect_end - sect_start));

 }
 ENDFOR

    fac       = global_gain;
    is_pos    = 0;
    noise_nrg = global_gain - NOISE_OFFSET;

    pTable    = pBook[BOOKSCL].pTable;
    group_win  = 0;
    group_end  = 0;

    WHILE((group_end < pFrameInfo->num_win)&&(status == SUCCESS))
    {
        nsfb_win  = pFrameInfo->sfb_per_win[group_end];
        group_end = *pGroup++;

        FOR(sfb = 0; sfb < nsfb_win; sfb++)
        {
            IF ((pHuffBookUsed[sfb] > 0)&&(pHuffBookUsed[sfb] < BOOKSCL))
            {
                cw_index = CALL decode_huff_cw_binary(pTable, pInputStream);

                fac      += cw_index - MIDFAC;

                IF((fac >= 2*TEXP) || (fac < 0))
                {
                    status = 1;
                }
                ELSE
                {
                    pFactors[sfb] = fac;
                }
                ENDIF (fac)

            }
            ELSE IF (pHuffBookUsed[sfb] == ZERO_HCB)
            {
                do nothing;
            }

            ELSE IF ((pHuffBookUsed[sfb] == INTENSITY_HCB)||
                     (pHuffBookUsed[sfb] == INTENSITY_HCB2))
            {
                cw_index = CALL decode_huff_cw_binary(pTable, pInputStream);

                is_pos        += cw_index - MIDFAC;
                pFactors[sfb] =  is_pos;
            }

            ELSE IF (pHuffBookUsed[sfb] == NOISE_HCB)
            {
                IF (noise_pcm_flag == TRUE)
                {
                    noise_pcm_flag = FALSE;
                    dpcm_noise_nrg = CALL getbits(
                                              NOISE_PCM_BITS,
                                              pInputStream);

                    dpcm_noise_nrg -= NOISE_PCM_OFFSET;
                }
                ELSE
                {
                    dpcm_noise_nrg = CALL decode_huff_cw_binary(
                                              pTable,
                                              pInputStream);

                    dpcm_noise_nrg -= MIDFAC;
                }
                ENDIF (noise_pcm_flag)

                noise_nrg       += dpcm_noise_nrg;
                pFactors[sfb]   =  noise_nrg;
            }

            ELSE IF (pHuffBookUsed[sfb] == BOOKSCL)
            {
                status = 1;
            }
            ENDIF (pHuffBookUsed[sfb])

        }
        ENDFOR (sfb)

        IF (pFrameInfo->islong == FALSE)
        {

            FOR(group_win++; group_win < group_end; group_win++)
            {
                FOR (sfb=0; sfb < nsfb_win; sfb++)
                {
                    pFactors[sfb + nsfb_win]  =  pFactors[sfb];
                }
                ENDFOR

                pFactors  +=  nsfb_win;
            }
            ENDFOR

        }
        ENDIF (pFrameInfo)

        pHuffBookUsed   += nsfb_win;
        pFactors        += nsfb_win;

    }
    ENDWHILE (group_end)

    return status;

------------------------------------------------------------------------------
 RESOURCES USED
   When the code is written for a specific target processor the
     the resources used should be documented below.

 STACK USAGE: [stack count for this module] + [variable to represent
          stack usage for each subroutine called]

     where: [stack usage variable] = stack usage for [subroutine
         name] (see [filename].ext)

 DATA MEMORY USED: x words

 PROGRAM MEMORY USED: x words

 CLOCK CYCLES: [cycle count equation for this module] + [variable
           used to represent cycle count for each subroutine
           called]

     where: [cycle count variable] = cycle count for [subroutine
        name] (see [filename].ext)

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include    "pv_audio_type_defs.h"
#include    "aac_mem_funcs.h"       /* pv_memset */
#include    "s_frameinfo.h"
#include    "s_bits.h"
#include    "s_sectinfo.h"
#include    "s_huffman.h"
#include    "ibstream.h"

#include    "hcbtables.h"
#include    "e_huffmanconst.h"
#include    "e_infoinitconst.h"
#include    "huffman.h"

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
Int hufffac(
    FrameInfo   *pFrameInfo,
    BITS        *pInputStream,
    Int         *pGroup,    /* may be changed to Int */
    Int         nsect,
    SectInfo    *pSect,     /* may be changed to Int */
    Int         global_gain,
    Int         *pFactors,
    Int         huffBookUsed[])
{
    Int     sect_idx;
    Int     group_end;  /* index of 1st window in next group */
    Int     group_win;  /* window index within group */
    Int     cw_index;   /* huff codeword index */
    Int     nsfb_win;   /* # of scfbands per window */
    Int     sfb;        /* scalefactor band index */
    Int     sect_cb;    /* huff codebook # for each section */
    Int     fac;        /* decoded scf */
    Int     is_pos;     /* intensity stereo position */
    Int     noise_pcm_flag = TRUE;  /* first PNS sfb */
    Int     dpcm_noise_nrg;     /* dpcm noise energy */
    Int     noise_nrg;      /* noise energy */
    Int     status = SUCCESS;  /* status of decoding */
    Int     *pHuffBookUsed = &huffBookUsed[0];


    pv_memset(pFactors,
              ZERO_HCB,
              MAXBANDS*sizeof(*pFactors));


    if (nsect)
    {
        /* read section length and codebook */

        if (nsect == 1) /* long window */
        {
            sect_cb  = pSect->sect_cb;  /* codebook for this section */

            /* all sfbs in one section share the same codebook */

            for (sfb = pSect->sect_end >> 2; sfb != 0; sfb--)
            {
                *(pHuffBookUsed++) = sect_cb;
                *(pHuffBookUsed++) = sect_cb;
                *(pHuffBookUsed++) = sect_cb;
                *(pHuffBookUsed++) = sect_cb;
            }
            for (sfb = pSect->sect_end & 3; sfb != 0; sfb--)
            {
                *(pHuffBookUsed++) = sect_cb;
            }

        }
        else            /* short */
        {
            Int sect_start = 0; /* start index of sfb for each section */
            for (sect_idx = nsect; sect_idx > 0; sect_idx--)
            {
                sect_cb  = pSect->sect_cb;  /* codebook for this section */

                /* all sfbs in one section share the same codebook */
                for (sfb = sect_start; sfb < pSect->sect_end; sfb++)
                {
                    pHuffBookUsed[sfb] = sect_cb;
                }

                pSect++;
                sect_start = sfb;

            } /* for (sect_idx) */
        }
    }
    else
    {
        /* clear array for the case of max_sfb == 0 */
        pv_memset(pHuffBookUsed,
                  ZERO_HCB,
                  MAXBANDS*sizeof(*pHuffBookUsed));
    }

    pHuffBookUsed = &huffBookUsed[0];

    /* scale factors and noise energy are dpcm relative to global gain
     * intensity positions are dpcm relative to zero
     */
    fac       = global_gain;
    is_pos    = 0;
    noise_nrg = global_gain - NOISE_OFFSET;

    /* get scale factors,
     * use reserved Table entry = 12, see reference (2) p78 Table 4.6.2
     */
    group_win  = 0;
    group_end  = 0;


    /* group by group decoding scalefactors and/or noise energy
     * and/or intensity position
     */
    while ((group_end < pFrameInfo->num_win) && (status == SUCCESS))
    {
        nsfb_win  = pFrameInfo->sfb_per_win[group_end];
        group_end = *pGroup++;  /* index of 1st window in next group */

        /* decode scf in first window of each group */

        for (sfb = 0; sfb < nsfb_win; sfb++)
        {

            switch (pHuffBookUsed[sfb])
            {
                case ZERO_HCB:
                    break;
                case INTENSITY_HCB:
                case INTENSITY_HCB2:
                    /* intensity books */
                    /* decode intensity position */
                    cw_index = decode_huff_scl(pInputStream);

                    is_pos        += cw_index - MIDFAC;
                    pFactors[sfb] =  is_pos;
                    break;
                case NOISE_HCB:
                    /* noise books */
                    /* decode noise energy */
                    if (noise_pcm_flag == TRUE)
                    {
                        noise_pcm_flag = FALSE;
                        dpcm_noise_nrg = get9_n_lessbits(NOISE_PCM_BITS,
                                                         pInputStream);

                        dpcm_noise_nrg -= NOISE_PCM_OFFSET;
                    }
                    else
                    {
                        dpcm_noise_nrg = decode_huff_scl(pInputStream);

                        dpcm_noise_nrg -= MIDFAC;
                    } /* if (noise_pcm_flag) */

                    noise_nrg       += dpcm_noise_nrg;
                    pFactors[sfb]   =  noise_nrg;
                    break;
                case BOOKSCL:
                    status = 1; /* invalid books */
                    sfb = nsfb_win;  /* force out */
                    break;
                default:
                    /* spectral books */
                    /* decode scale factors */
                    cw_index = decode_huff_scl(pInputStream);

                    fac      += cw_index - MIDFAC;   /* 1.5 dB */
                    if ((fac >= 2*TEXP) || (fac < 0))
                    {
                        status = 1;   /* error, MUST 0<=scf<256, Ref. p73 */
                    }
                    else
                    {
                        pFactors[sfb] = fac;  /* store scf */
                    } /* if (fac) */
            }

        } /* for (sfb=0), first window decode ends */

        /* expand scf to other windows in the same group */
        if (pFrameInfo->islong == FALSE)
        {

            for (group_win++; group_win < group_end; group_win++)
            {
                for (sfb = 0; sfb < nsfb_win; sfb++)
                {
                    pFactors[sfb + nsfb_win]  =  pFactors[sfb];
                }
                pFactors  +=  nsfb_win;
            }

        } /* if (pFrameInfo->islong), one group decode ends */


        /* points to next group */
        pHuffBookUsed   += nsfb_win;
        pFactors        += nsfb_win;

    } /* while (group_end), all groups decode end */

    return status;

} /* hufffac */

