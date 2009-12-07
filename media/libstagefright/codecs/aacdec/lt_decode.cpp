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

 Pathname: lt_decode.c


------------------------------------------------------------------------------

 REVISION HISTORY

 Description:  Modified from original shareware code

 Description:  Modified to pass variables by reference to eliminate use
               of global variables.

 Description:  First round of optimizations.

 Description:  pInputStream is now the 2nd parameter to this function.

 Description:  Changed to work with MT's new get_ics_info.c function, which
 only calls lt_decode if LTP is enabled.  This removes one grab from the
 bitstream and one "if" from this code.  Also, changed setting of weight.
 Now, rather than setting the actual weight, I only set the index into
 a table in this function.

 Description: Replace some instances of getbits to get9_n_lessbits
              when the number of bits read is 9 or less and get1bits
              when only 1 bit is read.

 Who:                                   Date:
 Description:
------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    win_type        Type of window (SHORT or LONG)
                    [WINDOW_TYPE]

    max_sfb         Maximum number of active scalefactor bands
                    [Int]

    pLt_pred        Pointer to structure containing information for
                    long-term prediction.
                    [LT_PRED_STATUS *]

    pInputStream    Pointer to structure containing bitstream
                    information.
                    [BITS *]

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    None

 Pointers and Buffers Modified:
    pLt_pred->weight_index - updated with index into weight table for LTP.

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function decodes the bitstream elements for long term prediction

------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

 (1) MPEG-2 NBC Audio Decoder
   "This software module was originally developed by Nokia
   in the course of development of the MPEG-2 AAC/MPEG-4 Audio standard
   ISO/IEC13818-7, 14496-1, 2 and 3.  This software module is an implementation
   of a part of one or more MPEG-2 AAC/MPEG-4 Audio tools as specified by the
   MPEG-2 aac/MPEG-4 Audio standard. ISO/IEC  gives users of the
   MPEG-2aac/MPEG-4 Audio standards free license to this software module or
   modifications thereof for use in hardware or software products claiming
   conformance to the MPEG-2 aac/MPEG-4 Audio  standards. Those intending to
   use this software module in hardware or software products are advised that
   this use may infringe existing patents. The original developer of this
   software module, the subsequent editors and their companies, and ISO/IEC
   have no liability for use of this software module or modifications thereof
   in an implementation. Copyright is not released for non MPEG-2 aac/MPEG-4
   Audio conforming products. The original developer retains full right to use
   the code for the developer's own purpose, assign or donate the code to a
   third party and to inhibit third party from using the code for non
   MPEG-2 aac/MPEG-4 Audio conforming products. This copyright notice
   must be included in all copies or derivative works."
   Copyright (c)1997.

------------------------------------------------------------------------------
 PSEUDO-CODE

    pDelay[0] = (Int) getbits(
                        LEN_LTP_LAG,
                        pInputStream);

    temp_reg  = (Int) getbits(
                        LEN_LTP_COEF,
                        pInputStream);

    pLt_pred->weight = codebook[temp_reg];

    last_band = max_sfb;

    IF (win_type != EIGHT_SHORT_SEQUENCE)

        IF (last_band > MAX_LT_PRED_LONG_SFB)

            last_band = MAX_LT_PRED_LONG_SFB;

        ENDIF

        FOR (m = last_band; m > 0; m--)

            *(pSfbPredictionUsed++) = (Int) getbits(
                                               LEN_LTP_LONG_USED,
                                               pInputStream);
        ENDFOR

        FOR (m = (max_sfb - last_band); m > 0; m--)

            *(pSfbPredictionUsed++) = 0;

        ENDFOR

    ELSE

        IF (last_band > MAX_LT_PRED_SHORT_SFB)

            last_band = MAX_LT_PRED_SHORT_SFB;

        ENDIF

        prev_subblock = pDelay[0];

        pWinPredictionUsed++;

        pTempPtr = &pSfbPredictionUsed[0];

        FOR (m = NUM_SHORT_WINDOWS; m > 0;)

            m--;
            temp_reg = (Int) getbits(
                                LEN_LTP_SHORT_USED,
                                pInputStream);

            *(pWinPredictionUsed++) = temp_reg;

            IF (temp_reg != FALSE)
            {
                *(pDelay++) = prev_subblock;

                FOR (k = last_band; k > 0; k--)
                {
                    *(pTempPtr++) = 1;
                }
                break;
            ELSE
            {
                pDelay++;
                pTempPtr += last_band;
            }

        ENDFOR (m = NUM_SHORT_WINDOWS; m > 0;)

        prev_subblock += LTP_LAG_OFFSET;

        FOR (; m > 0; m--)

            temp_reg = (Int) getbits (
                                LEN_LTP_SHORT_USED,
                                pInputStream);

            *(pWinPredictionUsed++) = temp_reg;

            IF (temp_reg != FALSE)

                temp_reg = (Int) getbits(
                                    LEN_LTP_SHORT_LAG_PRESENT,
                                    pInputStream);
                IF (temp_reg != 0)

                    temp_reg  = (Int) getbits(
                                         LEN_LTP_SHORT_LAG,
                                         pInputStream);

                    *(pDelay++) = prev_subblock - temp_reg;

                ELSE

                    *(pDelay++) = prev_subblock - LTP_LAG_OFFSET;

                ENDIF

                FOR (k = last_band; k > 0; k--)
                    *(pTempPtr++) = 1;
                ENDFOR

            ELSE

                pDelay++;
                pTempPtr += last_band;

            ENDIF

        ENDFOR (; m > 0; m--)

    ENDIF (win_type != EIGHT_SHORT_SEQUENCE)

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
#include "pv_audio_type_defs.h"
#include "lt_decode.h"
#include "ltp_common_internal.h"
#include "window_block_fxp.h"
#include "e_window_sequence.h"
#include "s_lt_pred_status.h"
#include "s_bits.h"
#include "ibstream.h"

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
void lt_decode(
    const WINDOW_SEQUENCE  win_type,
    BITS            *pInputStream,
    const Int              max_sfb,
    LT_PRED_STATUS  *pLt_pred)
{
    Int wnd_num;
    Int k;
    Int last_band;
    Int prev_subblock;
    Int prev_subblock_nonzero;
    Int temp_reg;

    Bool *pWinPredictionUsed = pLt_pred->win_prediction_used;
    Bool *pSfbPredictionUsed = pLt_pred->sfb_prediction_used;
    Int  *pTempPtr;
    Int  *pDelay = pLt_pred->delay;

    pDelay[0] = (Int) get17_n_lessbits(
                    LEN_LTP_LAG,  /* 11 bits */
                    pInputStream);

    pLt_pred->weight_index  = (Int) get9_n_lessbits(
                                  LEN_LTP_COEF, /*  3 bits */
                                  pInputStream);

    last_band = max_sfb;

    if (win_type != EIGHT_SHORT_SEQUENCE)
    {

        /* last_band = min(MAX_LT_PRED_LONG_SFB, max_sfb) MAX_SCFAC_BANDS */
        if (last_band > MAX_LT_PRED_LONG_SFB)
        {
            last_band = MAX_LT_PRED_LONG_SFB;
        }

        for (k = last_band; k > 0; k--)
        {
            *(pSfbPredictionUsed++) = (Int) get1bits(pInputStream);
        }

        /*
         * This is not a call to memset, because
         * (max_sfb - last_band) should typically be a small value.
         */
        for (k = (max_sfb - last_band); k > 0; k--)
        {
            *(pSfbPredictionUsed++) = FALSE;
        }
    }
    else /* (win_type == EIGHT_SHORT_SEQUENCE) */
    {
        /* last_band = min(MAX_LT_PRED_SHORT_SFB, max_sfb) */

        if (last_band > MAX_LT_PRED_SHORT_SFB)
        {
            last_band = MAX_LT_PRED_SHORT_SFB;
        }

        /*
         * The following two coding constructs are equivalent...
         *
         *  first_time == 1
         *  for (wnd_num=NUM_SHORT_WINDOWS; wnd_num > 0; wnd_num--)
         *  {
         *     if (condition)
         *     {
         *       if (first_time == 1)
         *       {
         *           CODE SECTION A
         *           first_time = 0;
         *       }
         *       else
         *       {
         *           CODE SECTION B
         *       }
         *     }
         *  }
         *
         * -----------------------------------EQUIVALENT TO------------
         *
         *  wnd_num=NUM_SHORT_WINDOWS;
         *
         *  do
         *  {
         *     wnd_num--;
         *     if (condition)
         *     {
         *         CODE SECTION A
         *         break;
         *     }
         *  } while( wnd_num > 0)
         *
         *  while (wnd_num > 0)
         *  {
         *     if (condition)
         *     {
         *         CODE SECTION B
         *     }
         *     wnd_num--;
         *  }
         *
         */

        prev_subblock = pDelay[0];

        pTempPtr = &pSfbPredictionUsed[0];

        wnd_num = NUM_SHORT_WINDOWS;

        prev_subblock_nonzero = prev_subblock;
        prev_subblock += LTP_LAG_OFFSET;

        do
        {
            /*
             * Place decrement of wnd_num here, to insure
             * that the decrement occurs before the
             * break out of the do-while loop.
             */
            wnd_num--;

            temp_reg = (Int) get1bits(pInputStream);

            *(pWinPredictionUsed++) = temp_reg;

            if (temp_reg != FALSE)
            {
                *(pDelay++) = prev_subblock_nonzero;

                for (k = last_band; k > 0; k--)
                {
                    *(pTempPtr++) = TRUE;
                }
                for (k = (max_sfb - last_band); k > 0; k--)
                {
                    *(pTempPtr++) = FALSE;
                }
                break;

            } /* if(pWinPredictionUsed) */
            else
            {
                pDelay++;
                pTempPtr += max_sfb;
            }

        }
        while (wnd_num > 0);

        /*
         * This while loop picks up where the previous one left off.
         * Notice that the code functions differently inside the loop
         */

        while (wnd_num > 0)
        {
            temp_reg = (Int) get1bits(pInputStream);

            *(pWinPredictionUsed++) = temp_reg;

            if (temp_reg != FALSE)
            {
                temp_reg = (Int) get1bits(pInputStream);
                if (temp_reg != 0)
                {
                    temp_reg  = (Int) get9_n_lessbits(
                                    LEN_LTP_SHORT_LAG,
                                    pInputStream);

                    *(pDelay++) = prev_subblock - temp_reg;
                }
                else
                {
                    *(pDelay++) = prev_subblock_nonzero;
                }
                for (k = last_band; k > 0; k--)
                {
                    *(pTempPtr++) = TRUE;
                }
                for (k = (max_sfb - last_band); k > 0; k--)
                {
                    *(pTempPtr++) = FALSE;
                }

            } /* if (temp_reg) */
            else
            {
                pDelay++;
                pTempPtr += max_sfb;
            }

            wnd_num--;

        } /* while(wnd_num) */

    } /* else (win_type == EIGHT_SHORT_SEQUENCE) */

} /* lt_decode */
