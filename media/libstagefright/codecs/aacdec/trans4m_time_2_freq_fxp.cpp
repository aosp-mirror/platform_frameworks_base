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

  Pathname: trans4m_time_2_freq_fxp.c
  Function: trans4m_time_2_freq_fxp

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:
        Modified normalization, so it now happen per window basis, eliminated
        shifts left or rigth to accomodate TNS inverse filtering. The output
        is 32 bits but only the lowest 16 are being used.
        Modified fuction interface

 Description: Modified variable names with leading "p" for pointers

 Description:
        Modified call to mdct_fxp to reflect extended precision use. Added routine
        buffer_adaptation to extract 16 MSB and keep highest precision.
        Modify casting to ensure proper operations for different platforms

 Description:
        Added comments according to code review

 Description:
        Removed include file "buffer_normalization.h"

 Description:
        Eliminated buffer_adaptation() and embedded its functionality in other
        functions. Commented out the short window section given that this is
        not supported by the standards

 Description:
        Added shift down operation for case when the window was equal to one.
        This was not needed previuosly because buffer_adaptation() was doing
        it.

 Description: Created local version of vectors Long_Window_fxp and
              Short_Window_fxp. This solve linking problem when using the
              /ropi option (Read-only position independent) for some
              compilers.


 Who:                       Date:
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    Time2Freq_data    =  buffer with data in the time domain, it holds 2048
                         points of input time data
                         Output holds frequency (first 1024 points )
                         type Int32

    wnd_seq           =  window sequence
                         type WINDOW_SEQUENCE

    wnd_shape_prev_bk =  previous window shape type
                         type Int

    wnd_shape_this_bk =  current window shape type
                         type Int

    pQ_format          =  Holds the Q format of the data in, and data out
                         type Int *

    mem_4_in_place_FFT[] =  scratch memory for computing FFT, 1024 point
                         type Int32



 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    None

 Pointers and Buffers Modified:
    Frequency information (1024 pts.) is returned in Time2Freq_data
    pQ_format content spectral coefficients Q format

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

The time/frequency representation of the signal is mapped onto the frequency
domain by feeding it into the filterbank module. This module consists of
a modified discrete cosine transform (MDCT), (windowing and DCT).
In order to adapt the time/frequency resolution of the filterbank to the
 characteristics of the input signal, a block switching tool is also
adopted. N represents the window length, where N is a function of the
window_sequence. For each channel, the N time values are transformed into the
N/2 frequency domain values via the MDCT.

The adaptation of the time-frequency resolution of the filterbank to the
characteristics of the input signal is done by shifting between transforms
whose input lengths are either 2048 or 256 samples. By enabling the block
switching tool, the following transitions are meaningful:

from ONLY_LONG_SEQUENCE to   { LONG_START_SEQUENCE
                               ONLY_LONG_SEQUENCE

from LONG_START_SEQUENCE to  { LONG_STOP_SEQUENCE
                               EIGHT_SHORT_SEQUENCE

from LONG_STOP_SEQUENCE to   { LONG_START_SEQUENCE
                               ONLY_LONG_SEQUENCE

from EIGHT_SHORT_SEQUENCE to { LONG_STOP_SEQUENCE
                               EIGHT_SHORT_SEQUENCE

Window shape decisions are made by the encoder on a frame-by-frame-basis.
The window selected is applicable to the second half of the window function
only, since the first half is constrained to use the appropriate window
shape from the preceding frame.
The 2048 time-domain values x'(i)(n), (i window, n sample) to be windowed are
the last 1024 values of the previous window_sequence concatenated with 1024
values of the current block. The formula below shows this fact:

                     |  x(i-1)(n+1024)      for    0 < n < 1024
            x'(i)(n) {
                     |  x(i)(n)             for 1024 < n < 2048



Once the window shape is selected, the window_shape syntax element is
initialized. Together with the chosen window_sequence all information needed
for windowing exist.
With the window halves described below all window_sequences can be assembled.
For window_shape == 1, the window coefficients are given by the Kaiser -
Bessel derived (KBD) window.
Otherwise, for window_shape == 0, a sine window is employed.

The window length N can be 2048 or 256 for the KBD and the sine window.
All four window_sequences explained below have a total length of 2048
samples.
For all kinds of window_sequences the window_shape of the left half of
the first transform window is determined by the window shape of the previous
block.
------------------------------------------------------------------------------
 REQUIREMENTS

    This module shall implement a scheme to switch between window types and
    in turn perform time to frequency transformations


------------------------------------------------------------------------------
 REFERENCES

    [1] ISO 14496-3:1999, pag 111

------------------------------------------------------------------------------
 PSEUDO-CODE

    IF ( wnd_seq == EIGHT_SHORT_SEQUENCE)
    THEN


        FOR ( wnd=0; wnd<NUM_SHORT_WINDOWS; wnd++)

            time_info = &Time2Freq_data[ W_L_STOP_1 + wnd*SHORT_WINDOW]

            FOR( i=0; i<SHORT_BLOCK1; i++)
                aux_temp[i] = time_info[i]
            ENDFOR


            IF (wnd == 0)
            THEN
                pShort_Window_1 = &Short_Window[wnd_shape_prev_bk][0]
            ELSE
                pShort_Window_1 = &Short_Window[wnd_shape_this_bk][0]
            ENDIF


            pShort_Window_2   =
                    &Short_Window[wnd_shape->this_bk][SHORT_WINDOW_m_1]

            FOR( i=0, j=SHORT_WINDOW; i<SHORT_WINDOW; i++, j--)
                aux_temp[             i]  *= pShort_Window_1[i]
                aux_temp[SHORT_WINDOW+i]  *= pShort_Window_2[j]
            ENDFOR


            CALL MDCT( aux_temp, SHORT_BLOCK1)
            MODIFYING( aux_temp)

            FOR( i=0; i<SHORT_WINDOW; i++)
                Time2Freq_data[wnd*SHORT_WINDOW + i] = aux_temp[i];
            ENDFOR

        ENDFOR

    ELSE

        SWITCH ( wnd_seq)

            CASE ( ONLY_LONG_SEQUENCE)

                pLong_Window_1 = &Long_Window[wnd_shape_prev_bk][0]
                pLong_Window_2 =
                        &Long_Window[wnd_shape_this_bk][LONG_WINDOW_m_1]

                FOR (i=0; i<LONG_WINDOW; i++)
                    Time2Freq_data[            i] *= *pLong_Window_1++
                    Time2Freq_data[LONG_WINDOW+i] *= *pLong_Window_2--
                ENDFOR

                BREAK


            CASE ( LONG_START_SEQUENCE)

                pLong_Window_1 = &Long_Window[wnd_shape_prev_bk][0];

                FOR ( i=0; i<LONG_WINDOW; i++)
                    Time2Freq_data[ i] *= *pLong_Window_1++;
                ENDFOR


                pShort_Window_1   =
                        &Short_Window[wnd_shape->this_bk][SHORT_WINDOW_m_1];

                FOR ( i=0; i<SHORT_WINDOW; i++)
                    Time2Freq_data[W_L_START_1 + i] *= *pShort_Window_1--;
                ENDFOR


                FOR ( i=W_L_START_2; i<LONG_BLOCK1; i++)
                    Time2Freq_data[W_L_START_2 + i] = 0;
                ENDFOR

                BREAK


            CASE ( LONG_STOP_SEQUENCE )

                FOR ( i=0; i<W_L_STOP_1; i++)
                    Time2Freq_data[ i] = 0;
                ENDFOR


                pShort_Window_1   = &Short_Window[wnd_shape->prev_bk][0];

                FOR ( i=0; i<SHORT_WINDOW; i++)
                    Time2Freq_data[W_L_STOP_1+ i] *= *pShort_Window_1++;
                ENDFOR


                pLong_Window_1 =
                        &Long_Window[wnd_shape->this_bk][LONG_WINDOW_m_1];

                FOR ( i=0; i<LONG_WINDOW; i++)
                    Time2Freq_data[LONG_WINDOW + i]  *=  *pLong_Window_1--;
                ENDFOR

                BREAK


        }

        MDCT( Time2Freq_data, LONG_BLOCK1);
        MODIFYING( Time2Freq_data)

    ENDIF

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
#include "aac_mem_funcs.h"
#include "window_block_fxp.h"
#include "mdct_fxp.h"
#include "long_term_prediction.h"
#include    "fxp_mul32.h"


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
; LOCAL VARIABLE DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL FUNCTION REFERENCES
; Declare functions defined elsewhere and referenced in this module
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL VARIABLES REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL GLOBAL STORE/BUFFER/POINTER REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/
void trans4m_time_2_freq_fxp(
    Int32   Time2Freq_data[],       /* time data size 2048 */
    WINDOW_SEQUENCE wnd_seq,        /* window sequence */
    Int     wnd_shape_prev_bk,      /* window shape, current and previous  */
    Int     wnd_shape_this_bk,
    Int     *pQ_format,
    Int32   mem_4_in_place_FFT[])   /* scratch memory for computing FFT */
{

    Int  i;

    Int32   *pAux_temp_1;
    Int32   *pAux_temp_2;
    Int32   *pAux_temp;
//    Int32   temp;
    const   Int16 *pLong_Window_1;
    const   Int16 *pLong_Window_2;
    const   Int16 *pShort_Window_1;
    const   Int16 *pShort_Window_2;
    Int     shift = *pQ_format - 1;

    const Int16 * Long_Window_fxp[NUM_WINDOW_SHAPES];
    const Int16 * Short_Window_fxp[NUM_WINDOW_SHAPES];

    Long_Window_fxp[0] = Long_Window_sine_fxp;
    Long_Window_fxp[1] = Long_Window_KBD_fxp;
    Short_Window_fxp[0] = Short_Window_sine_fxp;
    Short_Window_fxp[1] = Short_Window_KBD_fxp;

    if (wnd_seq != EIGHT_SHORT_SEQUENCE)
    {

        pAux_temp = Time2Freq_data;

        *pQ_format = LTP_Q_FORMAT - *pQ_format;

        pAux_temp_1 = pAux_temp;

        switch (wnd_seq)
        {

            case LONG_START_SEQUENCE:

                pAux_temp_2 = &pAux_temp_1[HALF_LONG_WINDOW];

                pLong_Window_1 = &Long_Window_fxp[wnd_shape_prev_bk][0];
                pLong_Window_2 = &pLong_Window_1[ HALF_LONG_WINDOW];




                for (i = HALF_LONG_WINDOW; i > 0; i--)
                {

                    *pAux_temp_1 = fxp_mul32_by_16((*pAux_temp_1), *pLong_Window_1++) >> shift;
                    pAux_temp_1++;
                    *pAux_temp_2 = fxp_mul32_by_16((*pAux_temp_2), *pLong_Window_2++) >> shift;
                    pAux_temp_2++;

                }


                /* data unchanged from  LONG_WINDOW to W_L_START_1 */
                pAux_temp_1 = &pAux_temp[LONG_WINDOW];
                if (shift)
                {
                    for (i = (W_L_START_1 - LONG_WINDOW) >> 1; i != 0; i--)
                    {
                        *(pAux_temp_1++) >>= shift;
                        *(pAux_temp_1++) >>= shift;
                    }
                }


                pAux_temp_1 = &pAux_temp[W_L_START_1];
                pAux_temp_2 = &pAux_temp_1[HALF_SHORT_WINDOW];

                pShort_Window_1   =
                    &Short_Window_fxp[wnd_shape_this_bk][SHORT_WINDOW_m_1];

                pShort_Window_2   = pShort_Window_1 - HALF_SHORT_WINDOW;

                for (i = HALF_SHORT_WINDOW; i > 0; i--)
                {

                    *pAux_temp_1 = fxp_mul32_by_16((*pAux_temp_1), *pShort_Window_1--) >> shift;
                    pAux_temp_1++;
                    *pAux_temp_2 = fxp_mul32_by_16((*pAux_temp_2), *pShort_Window_2--) >> shift;
                    pAux_temp_2++;

                }

                pAux_temp_1 = &pAux_temp[W_L_START_2];

                pv_memset(
                    pAux_temp_1,
                    0,
                    (LONG_BLOCK1 - W_L_START_2)*sizeof(*pAux_temp_1));

                break;


            case LONG_STOP_SEQUENCE:

                pv_memset(
                    pAux_temp_1,
                    0,
                    (W_L_STOP_1)*sizeof(*pAux_temp_1));

                pShort_Window_1   = &Short_Window_fxp[wnd_shape_prev_bk][0];
                pShort_Window_2   = &pShort_Window_1[HALF_SHORT_WINDOW];

                pAux_temp_1      = &pAux_temp_1[W_L_STOP_1];
                pAux_temp_2      = pAux_temp_1 + HALF_SHORT_WINDOW;

                for (i = HALF_SHORT_WINDOW; i > 0; i--)
                {

                    *pAux_temp_1 = fxp_mul32_by_16((*pAux_temp_1), *pShort_Window_1++) >> shift;
                    pAux_temp_1++;
                    *pAux_temp_2 = fxp_mul32_by_16((*pAux_temp_2), *pShort_Window_2++) >> shift;
                    pAux_temp_2++;


                }

                /* data unchanged from  W_L_STOP_2 to LONG_WINDOW */
                pAux_temp_1 = &pAux_temp[W_L_STOP_2];

                if (shift)
                {
                    for (i = ((LONG_WINDOW - W_L_STOP_2) >> 1); i != 0; i--)
                    {
                        *(pAux_temp_1++) >>= shift;
                        *(pAux_temp_1++) >>= shift;
                    }
                }



                pAux_temp_1 = &pAux_temp[LONG_WINDOW];
                pAux_temp_2 =  pAux_temp_1 + HALF_LONG_WINDOW;

                pLong_Window_1 =
                    &Long_Window_fxp[wnd_shape_this_bk][LONG_WINDOW_m_1];


                pLong_Window_2   = &pLong_Window_1[-HALF_LONG_WINDOW];

                for (i = HALF_LONG_WINDOW; i > 0; i--)
                {
                    *pAux_temp_1 = fxp_mul32_by_16((*pAux_temp_1), *pLong_Window_1--) >> shift;
                    pAux_temp_1++;
                    *pAux_temp_2 = fxp_mul32_by_16((*pAux_temp_2), *pLong_Window_2--) >> shift;
                    pAux_temp_2++;

                }

                break;

            case ONLY_LONG_SEQUENCE:
            default:

                pAux_temp_2 = &pAux_temp[LONG_WINDOW];

                pLong_Window_1 = &Long_Window_fxp[wnd_shape_prev_bk][0];


                pLong_Window_2 =
                    &Long_Window_fxp[wnd_shape_this_bk][LONG_WINDOW_m_1];


                for (i = LONG_WINDOW; i > 0; i--)
                {

                    *pAux_temp_1 = fxp_mul32_by_16((*pAux_temp_1), *pLong_Window_1++) >> shift;
                    pAux_temp_1++;
                    *pAux_temp_2 = fxp_mul32_by_16((*pAux_temp_2), *pLong_Window_2--) >> shift;
                    pAux_temp_2++;
                }

                break;

        }   /* end switch ( wnd_seq)  */



        *pQ_format += mdct_fxp(
                          pAux_temp,
                          mem_4_in_place_FFT,
                          LONG_BLOCK1);


    }   /* end if( wnd_seq != EIGHT_SHORT_SEQUENCE) */



    /*****************************************/
    /* decoding process for short window */
    /*****************************************/

    /*
     * For short window the following code will be applied
     * in the future when short window is supported in the
     * standards
     */
    /*-------------------------------------------------------------------------

    *        pAux_temp = &mem_4_in_place_FFT[(2*SHORT_BLOCK1)];
    *
    *        for ( wnd=0; wnd<NUM_SHORT_WINDOWS; wnd++)
    *        {
    *
    *            pShort_Window_1 = &Short_Window_fxp[wnd_shape_this_bk][0];
    *
    *            if (wnd == 0)
    *            {
    *                pShort_Window_1 = &Short_Window_fxp[wnd_shape_prev_bk][0];
    *            }
    *
    *            pShort_Window_2   =
    *                    &Short_Window_fxp[wnd_shape_this_bk][SHORT_WINDOW_m_1];
    *
    *            pAux_temp_1 =  pAux_temp;
    *            pAux_temp_2 = pAux_temp_1 + SHORT_WINDOW;
    *
    *            Q_aux = 0;
    *
    *            buffer_adaptation (
    *                &Q_aux,
    *                &Time2Freq_data[ W_L_STOP_1 + wnd*SHORT_WINDOW],
    *                (void *) pAux_temp,
    *                SHORT_BLOCK1,
    *                USING_INT,
    *                16);
    *
    *
    *            for ( i=SHORT_WINDOW; i>0; i--)
    *            {
    *                temp           = (*pAux_temp_1) * *pShort_Window_1++;
    *                *pAux_temp_1++ = (temp + 0x08000L) >> 16;
    *
    *                temp           = (*pAux_temp_2) * *pShort_Window_2--;
    *                *pAux_temp_2++ = (temp + 0x08000L) >> 16;
    *
    *            }
    *
    *
    *            exp = mdct_fxp(
    *                pAux_temp,
    *                mem_4_in_place_FFT,
    *                SHORT_BLOCK1);
    *
    *
    *            exp += Q_aux;
    *
    *            pAux_temp_1  =  pAux_temp;
    *            pAux_temp_2  =  pAux_temp_1  +  HALF_SHORT_WINDOW;
    *            pTime_data_1 = &Time2Freq_data[wnd*SHORT_WINDOW];
    *            pTime_data_2 =  pTime_data_1 + HALF_SHORT_WINDOW;
    *
    *
    *            if (exp > 0)
    *            {
    *                for ( i=HALF_SHORT_WINDOW; i>0; i--)
    *                {
    *                    *pTime_data_1++ = (*pAux_temp_1++>>exp);
    *                    *pTime_data_2++ = (*pAux_temp_2++>>exp);
    *                }
    *            }
    *            else if (exp < 0)
    *            {
    *                exp = -exp;
    *                for ( i=HALF_SHORT_WINDOW; i>0; i--)
    *                {
    *                    *pTime_data_1++ = (*pAux_temp_1++<<exp);
    *                    *pTime_data_2++ = (*pAux_temp_2++<<exp);
    *                }
    *            }
    *            else
    *            {
    *                for ( i=HALF_SHORT_WINDOW; i>0; i--)
    *                {
    *                    *pTime_data_1++ = (*pAux_temp_1++);
    *                    *pTime_data_2++ = (*pAux_temp_2++);
    *                }
    *            }
    *
    *        }
    *
    *    }
    *
    *--------------------------------------------------------------------------*/

}   /* trans4m_time_2_freq_fxp */
