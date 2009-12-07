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

 Pathname: long_term_prediction.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Made changes based on comments and experiment results.

 Description: Passed in buffer sizes based on review comments and prototype
              agreements.

 Description: 1. Passed in "weight_index" instead of "weight".
              2. Added weight table.

 Description: 1. Removed some passed in buffer size variables since they are
                 not used for long window.
              2. Modified comments format.

 Description:
    Modified casting to ensure proper operations for different platforms

 Description:
    Implemented circular buffer techniques, which save 4096 memmoves per
    frame.

 Description:
    Implemented some optimizations found during the code review of this
    module.  The optimizations related to the rules on the range of
    ltp_buffer_index and num_samples, which allows for a simpler
    code construct to be used in the processing of the predicted samples.

 Description:
    Add max calculation on the filter implementation, this to eliminate
    function buffer_adaptation() on the time to frequency transformation.
    Function interface changed. It now return the amount of shifting needed
    to garb only the top 16 MSB.

 Description:
     Replace clearing memory with for-loop with pvmemset function

 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    win_seq = type of window sequence (WINDOW_SEQUENCE).

    weight_index = index (Int) of LTP coefficient table for all windows in
                   current frame.

    delay = buffer (Int) containing delays for each window.

    buffer = history buffer (Int16) containing the reconstructed time domain
             signals of previous frames.

    buffer_offset = value (Int) that indicates the location of the first
                    element in the LTP circular buffer.  (Either 0 or 1024)

    time_quant    = filterbank buffer (Int32) This buffer is used by the
                    filterbank, but it's first 1024 elements are equivalent
                    to the last 1024 elements in the conventionally
                    implemented LTP buffer.  Using this buffer directly avoids
                    costly duplication of memory.

    predicted_samples = buffer (Int32) with length of 2048 to hold
                        predicted time domain signals.

    buffer_index = index into buffer where the first sample of data from
                   the frame (t-2) (two frames ago) resides.  (Int)

    frame_length = length of one frame, type of Int.

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    Amount of shifting needed to grab the top 16 MSB from teh predicted buffer

 Pointers and Buffers Modified:
    predicted_samples contents are the newly calculated predicted time
    domain signals

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 Long term prediction (LTP) is used to reduce the redundancy of a signal
 between successive coding frames. This function performs prediction by
 applying 1-tap IIR filtering to calculate the predicted time domain
 signals of current frame from previous reconstructed frames stored in
 time domain history buffer.

 The equation used for IIR filter is as following.

            y(n) = weight * x(n - delay)

    where   y(n) ----- predicted time domain signals
            x(n) ----- reconstructed time domain signals
            weight ----- LTP coefficient
            delay ----- optimal delay from 0 to 2047

------------------------------------------------------------------------------
 REQUIREMENTS

 None

------------------------------------------------------------------------------
 REFERENCES

 (1) ISO/IEC 14496-3:1999(E)
     Part 3: Audio
        Subpart 4.6.6   Long Term Prediction (LTP)

 (2) MPEG-2 NBC Audio Decoder
     "This software module was originally developed by Nokia in the course
     of development of the MPEG-2 AAC/MPEG-4 Audio standard ISO/IEC13818-7,
     14496-1, 2 and 3. This software module is an implementation of a part
     of one or more MPEG-2 AAC/MPEG-4 Audio tools as specified by the MPEG-2
     aac/MPEG-4 Audio standard. ISO/IEC  gives users of the MPEG-2aac/MPEG-4
     Audio standards free license to this software module or modifications
     thereof for use in hardware or software products claiming conformance
     to the MPEG-2 aac/MPEG-4 Audio  standards. Those intending to use this
     software module in hardware or software products are advised that this
     use may infringe existing patents. The original developer of this
     software module, the subsequent editors and their companies, and ISO/IEC
     have no liability for use of this software module or modifications
     thereof in an implementation. Copyright is not released for non MPEG-2
     aac/MPEG-4 Audio conforming products. The original developer retains
     full right to use the code for the developer's own purpose, assign or
     donate the code to a third party and to inhibit third party from using
     the code for non MPEG-2 aac/MPEG-4 Audio conforming products. This
     copyright notice must be included in all copies or derivative works.
     Copyright (c)1997.

------------------------------------------------------------------------------
 PSEUDO-CODE

    pPredicted_samples = &predicted_samples[0];

    weight = codebook[weight_index];

    IF (win_seq != EIGHT_SHORT_SEQUENCE)
    THEN

        block_length = frame_length << 1;

        lag = delay[0];

        j = block_length - lag;

        IF (lag < frame_length)
        THEN

            num_samples = frame_length + lag;

        ELSE

            num_samples = block_length;

        ENDIF

        pBuffer = &buffer[j];

        FOR (i = num_samples; i>0; i--)

            *pPredicted_samples = weight * (*pBuffer);
            pPredicted_samples = pPredicted_samples + 1;
            pBuffer = pBuffer + 1;

        ENDFOR

        FOR (i = block_length - num_samples; i>0; i--)

            *pPredicted_samples = 0;
            pPredicted_samples = pPredicted_samples + 1;

        ENDFOR

    ELSE

        FOR (wnd = 0; wnd < short_window_num; wnd++)

            IF (win_prediction_used[wnd] != FALSE)
            THEN

                delay[wnd] = delay[0] + ltp_short_lag[wnd];

                lag = delay[wnd];

                j = wnd*short_block_length - lag;

                IF (lag < short_frame_length)
                THEN

                    num_samples = short_frame_length + lag;

                ELSE

                    num_samples = short_block_length;

                ENDIF

                pBuffer = &buffer[j];

                FOR (i = num_samples; i>0; i--)

                    *pPredicted_samples = weight * (*pBuffer);
                    pPredicted_samples = pPredicted_samples + 1;
                    pBuffer = pBuffer + 1;

                ENDFOR

                FOR (i = short_block_length - num_samples; i>0; i--)

                    *pPredicted_samples = 0;
                    pPredicted_samples = pPredicted_samples + 1;

                ENDFOR

            ELSE

                CALL pv_memset(
                        pPredicted_samples,
                        0,
                        sizeof(*pPredicted_samples)*short_block_length);
                MODIFYING (predicted_samples[]);

                pPredicted_samples = pPredicted_samples + short_block_length;

            ENDIF [ IF (win_prediction_used[wnd] != FALSE) ]

        ENDFOR [ FOR (wnd=0; wnd<short_window_num; wnd++) ]

    ENDIF [ IF (win_seq != EIGHT_SHORT_SEQUENCE) ]

    RETURN

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
#include "e_window_sequence.h"
#include "ltp_common_internal.h"
#include "long_term_prediction.h"
#include "aac_mem_funcs.h"
#include "pv_normalize.h"
#include "window_block_fxp.h"


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
/* Purpose: Codebook for LTP weight coefficients. Stored in Q15 format */
const UInt codebook[CODESIZE] =
{
    18705,  /* 0 */
    22827,  /* 1 */
    26641,  /* 2 */
    29862,  /* 3 */
    32273,  /* 4 */
    34993,  /* 5 */
    39145,  /* 6 */
    44877   /* 7 */
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
Int long_term_prediction(
    WINDOW_SEQUENCE     win_seq,
    const Int           weight_index,
    const Int           delay[],
    const Int16         buffer[],
    const Int           buffer_offset,
    const Int32         time_quant[],
    Int32         predicted_samples[],    /* Q15 */
    const Int           frame_length)
{
    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    /*
     * Window index
     *
     * Int wnd;
     *
     * will be enabled when short window information is available.
     */

    /* Pointer to time domain history buffer */

    const Int16 *pBuffer;

    const Int32 *pTimeQuant = time_quant;

    /* Pointer to array containing predicted samples */
    Int32 *pPredicted_samples;

    Int32   test;
    Int32   datum;

    /* IIR coefficient with Q15 format */
    UInt    weight;

    /* Length of one block (two frames) */
    Int     block_length;

    Int     shift;
    Int     k;
    Int     ltp_buffer_index;
    Int     jump_point;
    Int     lag;
    Int     num_samples;

    Int32   max = 0;

    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/
    /* Initialize pointers */
    pPredicted_samples = &predicted_samples[0];

    weight = codebook[weight_index];

    /****************************************/
    /* LTP decoding process for long window */
    /****************************************/

    if (win_seq != EIGHT_SHORT_SEQUENCE)
    {
        /****************************************************/
        /* Prediction based on previous time domain signals */
        /****************************************************/
        block_length = frame_length << 1;

        /* Calculate time lag for 1-tap IIR filter */
        lag = delay[0];

        ltp_buffer_index = block_length - lag;

        /* Calculate number of samples used in IIR filter */
        if (lag < frame_length)
        {
            num_samples = frame_length + lag;
        }
        else
        {
            num_samples = block_length;
        }


        /*
         * Calculate the predicted time domain signals from the
         * reconstructed time domain signals of previous frames.
         */

        /* The data is stored in TWO buffers, either as...
         *
         *                                       [   t ==  0  ]
         *
         * [   t == -1   ][   t == -2   ]
         *
         * OR...
         *                                       [   t ==  0  ]
         *
         * [   t == -2   ][   t == -1   ]
         *
         *
         *
         * In the first case, all of the buffers are non-contiguous,
         * and each must be handled separately.  Code for this first case
         * will function correctly for both cases.
         *
         * In the second case, the buffers storing t == -2, and t == -1
         * data are contiguous, and an optimization could take advantage
         * of this, at the cost of an increase in code size for this function.
         */

        /* Decrement block_length by num_samples.  This is important
         * for the loop at the end of the "ACCESS DATA IN THE LTP BUFFERS"
         * section that sets all remaining samples in the block to zero.
         */

        block_length -= num_samples;






        /*
         ************************************ ACCESS DATA IN THE LTP BUFFERS
         */

        /*
         * This section of the code handles the t == -2
         * buffer, which corresponds to 0 <= ltp_buffer_index < 1024
         *
         * BUFFER t == -2
         *
         * [0][][][][][][][][][][][...][][][][][][][][][][][][1023]
         *
         */

        jump_point = (frame_length - ltp_buffer_index);

        if (jump_point > 0)
        {
            pBuffer = &(buffer[ltp_buffer_index + buffer_offset]);

            for (k = jump_point; k > 0; k--)
            {
                /* Q15 = Q15 * Q0 */
                test = (Int32) weight * (*(pBuffer++));
                *(pPredicted_samples++) =  test;
                max                   |= (test >> 31) ^ test;
            }

            num_samples -= jump_point;

            ltp_buffer_index += jump_point;
        }

        /*
         * This section of the code handles the t == -1
         * buffer, which corresponds to 1024 <= ltp_buffer_index < 2048
         *
         * BUFFER t == -1
         *
         * [1024][][][][][][][][][][][...][][][][][][][][][][][][2047]
         *
         */

        jump_point = 2 * frame_length - ltp_buffer_index;

        pBuffer = &(buffer[ltp_buffer_index - buffer_offset]);

        if (num_samples < jump_point)
        {
            jump_point = num_samples;
        }

        for (k = jump_point; k > 0; k--)
        {
            /* Q15 = Q15 * Q0 */
            test = (Int32) weight * (*(pBuffer++));
            *(pPredicted_samples++) =  test;
            max                   |= (test >> 31) ^ test;
        }

        num_samples -= jump_point;

        ltp_buffer_index += jump_point;

        /*
         * This section of the code handles the t == 0
         * buffer, which corresponds to 2048 <= ltp_buffer_index < 3072
         *
         * BUFFER t == 0
         *
         * [2048][][][][][][][][][][][...][][][][][][][][][][][][3071]
         *
         */
        for (k = num_samples; k > 0; k--)
        {

            datum = *(pTimeQuant++) >> SCALING;

            /*
             * Limit the values in the 32-bit filterbank's buffer to
             * 16-bit resolution.
             *
             * Value's greater than 32767 or less than -32768 are saturated
             * to 32767 and -32768, respectively.
             */

            test                    = (Int32)datum * weight;
            *(pPredicted_samples++) =  test;
            max                    |= (test >> 31) ^ test;

        }

        /* Set any remaining samples in the block to 0. */

        pv_memset(
            pPredicted_samples,
            0,
            block_length*sizeof(*pPredicted_samples));

    } /* if (win_seq != EIGHT_SHORT_SEQUENCE) */


    /*****************************************/
    /* LTP decoding process for short window */
    /*****************************************/

    /*
     * For short window LTP, since there is no "ltp_short_lag"
     * information being passed, the following code for short
     * window LTP will be applied in the future when those
     * information are available.
     */

    /*
     *----------------------------------------------------------------------------
     *  else
     *  {
     *      for (wnd = 0; wnd < short_window_num; wnd++)
     *      {
     *          if (win_prediction_used[wnd] != FALSE)
     *          {
     *              delay[wnd] = delay[0] + ltp_short_lag[wnd];
     *
     *              lag = delay[wnd];
     *
     *              j = wnd*short_block_length - lag;
     *
     *              if (lag < short_frame_length)
     *              {
     *                  num_samples = short_frame_length + lag;
     *              }
     *              else
     *              {
     *                  num_samples = short_block_length;
     *              }
     *
     *              pBuffer = &buffer[j];
     *
     *              for(i = num_samples; i>0; i--)
     *              {
     *                  *(pPredicted_samples++) = weight * (*(pBuffer++));
     *              }
     *
     *              for(i = short_block_length - num_samples; i>0; i--)
     *              {
     *                  *(pPredicted_samples++) = 0;
     *              }
     *          }
     *          else
     *          {
     *              pv_memset(
     *                  pPredicted_samples,
     *                  0,
     *                  sizeof(*pPredicted_samples)*short_block_length);
     *
     *              pPredicted_samples += short_block_length;
     *          }
     *      }
     *  }
     *----------------------------------------------------------------------------
     */

    shift = 16 - pv_normalize(max);

    if (shift < 0)
    {
        shift = 0;
    }

    /*----------------------------------------------------------------------------
    ; Return nothing or data or data pointer
    ----------------------------------------------------------------------------*/
    return (shift);
} /* long_term_prediction */




