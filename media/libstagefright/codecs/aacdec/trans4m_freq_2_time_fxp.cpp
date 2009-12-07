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

  Pathname: trans4m_freq_2_time_fxp.c
  Function: trans4m_freq_2_time_fxp


------------------------------------------------------------------------------
 REVISION HISTORY

 Description:
    changed to decrement loop
    change wnd_shape from structure to passing parameters
    modified window tables from UInt to UInt16 to assure proper operation
    without dubious typecast
    changed logic to hit most common states first.
    modified Time_data from Int to Int32 to hold
    possible overflow before saturation process.

 Description:
    Increase processing on some loop by using more pointers
    changed interface to avoid passing a pointer for wnd_shape_prev_bk, this
    element is not change in this function because of this function use
    in the LTP module

 Description:
    Added rounding to multiplication

 Description:
    Update input description and eliminate unneeded comments

 Description:
    LONG_START_WINDOW was using SHORT_WINDOW instead of
    HALF_SHORT_WINDOW, causing a for loop to exceed its count

 Description:
    Modified structure of code so exp is not tested before it
    is initialized.  Also, new structure avoids double-testing
    of exp_freq = ALL_ZEROS_BUFFER.

 Description:
    The result of a shift is undefined if the right operand is greater than
    or equal to the number of bits in the left expression's type
    To avoid undefined shift by 32, a check of the shift has been
    added, so the function proceeds only when the exponent is less
    than 32. By design the shift up is related to the global gain,
    and controlled by the encoder, so saturation is not allowed.
    In both short and long window, processing is skip if an all zero
    input buffer or excessive down shift is detected.

 Description:
    Changes according to code review comments. Also, modified if-else
    structure so the imdct_fxp is not called with an all zero input buffer

 Description:
    Replaced function buffer_normalization by buffer_adaptation, to ease
    use of 16 bits. Function buffer_normalization becomes  obsolete.

 Description:
    Modified call to imdct_fxp to reflect extended precision use. Added
    routine buffer_adaptation to extract 16 MSB and keep highest.
    precision. Modify casting to ensure proper operations for different
    platforms

 Description:
    Eliminate double access to memory by loading data directly to the
    time array. Also reduced cycle count and added precision by combining
    downshifting in only one operation. Added adaptive rounding factor.
    Change exponent threshold when operations are waived. It is use to be 32
    but by combining downshifting, this new threshold is now 16. This may
    avoid unneeded calculations for extremely small numbers.

 Description:
    Per review comments:
        - Added comments to clarify buffer_adaptation function
        - Deleted  reference to include file "buffer_normalization.h"
        - Modified IF-ELSE so long_windows case is considered first
        - Eliminated extra IF when computing the rounding, so when exp ==0
          less cycles are used shifting than in an extra if-else
        - Corrected negative shift when computing rounding factor
        - Added condition when exp > 16 (for long windows)

 Description:
    Modified IF-ELSE structure so now ALL_ZEROS_BUFFER condition is share
    with exp > 16 condition. This avoid code duplication for both cases.

 Description:
        - Modified function interface to add output_buffer
        - Eliminated the 32 bit version of the current output, calculations
          are placed directly in output_buffer. In this way the buffer
          Time_data needs only to be 1024 Int32, instead of 2048 (per channel).
          Also, added the limit macro inside the function (this reduces access
          to memory).
        - Updated Pseudo - Code

 Description:
    Per review comments:
          Corrected line sizes and mispelling,  added comments and swap
          order or switch statement for ONLY_LONG_SEQUENCE.

 Description:
    Eliminated adaptive rounding due to potential saturation.

 Description:
    Eliminated use of buffer adaptation by shifting this functionality inside
    the imdct_fxp() routine. Also modified the call to imdct_fxp to accomodate
    new function interface.
    Modified macro limit() to save cycles when testing the most common case:
    no saturation.

 Description:
    Changed new function interface for imdct_fxp().

 Description:
    Replaced for-loop with memset and memcopy.

 Who:                         Date:
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    Frequency_data    =  vector with spectral information, size 2048
                         type Int32

    Time_data         =  buffer with data from previous Frequency to Time
                         conversion, used for overlap and add, size 1024
                         type Int32

    Output_buffer     =  place holder for current output,  size 1024
                         type Int16

    wnd_seq           =  window sequence
                         type WINDOW_SEQUENCE

    wnd_shape_prev_bk =  previous window shape type
                         type Int

    wnd_shape_this_bk =  current window shape type
                         type Int

    Q_format          =  Q format for the input frequency data
                         type Int

    freq_2_time_buffer[] =  scratch memory for computing FFT
                         type Int32


 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    None

 Pointers and Buffers Modified:
    Output_buffer
    Time_data
    Frequency_data
    pWnd_shape_prev_bk

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

The time/frequency representation of the signal is mapped onto the time
domain by feeding it into the filterbank module. This module consists of
an inverse modified discrete cosine transform (IMDCT), and a window and an
overlap-add function. In order to adapt the time/frequency resolution of the
filterbank to the characteristics of the input signal, a block switching tool
is also adopted. N represents the window length, where N is a function of the
window_sequence. For each channel, the N/2 time-frequency values are
transformed into the N time domain values via the IMDCT. After applying the
window function, for each channel, the first half of the sequence is added to
the second half of the previous block windowed sequence to reconstruct the
output samples for each channel outi,n.

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


Buffer Time_data data from previous Frequency to Time conversion, used
for overlap and add

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

In the case of EIGHT_SHORT_SEQUENCE the processing is done in-place and
in descendent order to avoid using extra memory.
The ordering is as follows:

                                            Pn: Previous data for window n
                                            Cn:  Current data for window n


                                                128 freq.
                                                 samples
                  FREQ                          ++++++
IN                         ===========================
                                                    \
                                                      \
                                                        ->  256 time
                                                             samples

                                                           P8    C8
           8                                            #######++++++
                                                    P7     C7
           7                                     #######++++++
           :                                :
           :                                :
                            P2    C2
           2             #######++++++
                     P1    C1
           1      #######++++++
                                                                          TIME
OUT          ==============================================================

------------------------------------------------------------------------------
 REQUIREMENTS

    This module shall implement a scheme to switch between window types

------------------------------------------------------------------------------
 REFERENCES

    [1] ISO 14496-3:1999, pag 111

------------------------------------------------------------------------------
 PSEUDO-CODE



    IF ( wnd_seq == EIGHT_SHORT_SEQUENCE)
    THEN

        FOR ( i=0; i<LONG_WINDOW; i++)
            Time_data[LONG_WINDOW + i] = 0;
        ENDFOR

        FOR ( wnd=NUM_SHORT_WINDOWS-1; wnd>=0; wnd--)

            pFreqInfo = &Frequency_data[ wnd*SHORT_WINDOW];

            CALL IMDCT( pFreqInfo, SHORT_BLOCK1);
            MODIFYING(pFreqInfo)


            IF (wnd == 0)
            THEN
                pShort_Window_1 = &Short_Window[wnd_shape_prev_bk][0];
            ELSE
                pShort_Window_1 = &Short_Window[wnd_shape_this_bk][0];
            ENDIF

            pShort_Window_2   =
                    &Short_Window[wnd_shape->this_bk][SHORT_WINDOW_m_1];

            FOR( i=0, j=SHORT_WINDOW; i<SHORT_WINDOW; i++, j--)
                pFreqInfo[             i]  *= pShort_Window_1[i];
                pFreqInfo[SHORT_WINDOW+i]  *= pShort_Window_2[j];
            ENDFOR


            FOR( i=0; i<SHORT_BLOCK1; i++)
                Time_data[W_L_STOP_1 + SHORT_WINDOW*wnd + i] += pFreqInfo[i];
            ENDFOR

        ENDFOR

        FOR ( i=0; i<LONG_WINDOW; i++)
            temp              = Time_data[i];
            Output_buffer[i]  = Time_data[i];
            Time_data[i]      = temp;
        ENDFOR
    ELSE

        CALL IMDCT( Frequency_data, LONG_BLOCK1)
            MODIFYING(Frequency_data)

        SWITCH ( wnd_seq)

            CASE ( ONLY_LONG_SEQUENCE)

                pLong_Window_1 = &Long_Window[wnd_shape_prev_bk][0];
                pLong_Window_2 =
                        &Long_Window[wnd_shape_this_bk][LONG_WINDOW_m_1];

                FOR (i=0; i<LONG_WINDOW; i++)
                    Frequency_data[            i] *= *pLong_Window_1++;
                    Frequency_data[LONG_WINDOW+i] *= *pLong_Window_2--;
                ENDFOR

                BREAK

            CASE ( LONG_START_SEQUENCE)

                pLong_Window_1 = &Long_Window[wnd_shape_prev_bk][0];

                FOR ( i=0; i<LONG_WINDOW; i++)
                    Frequency_data[ i] *= *pLong_Window_1++;
                ENDFOR

                pShort_Window_1   =
                        &Short_Window[wnd_shape_this_bk][SHORT_WINDOW_m_1];

                FOR ( i=0; i<SHORT_WINDOW; i++)
                    Frequency_data[W_L_START_1 + i] *= *pShort_Window_1--;
                ENDFOR

                FOR ( i=W_L_START_2; i<LONG_BLOCK1; i++)
                    Frequency_data[W_L_START_2 + i] = 0;
                ENDFOR

                BREAK


            CASE ( LONG_STOP_SEQUENCE )

                FOR ( i=0; i<W_L_STOP_1; i++)
                    Frequency_data[ i] = 0;
                ENDFOR

                pShort_Window_1   = &Short_Window[wnd_shape_prev_bk][0];

                FOR ( i=0; i<SHORT_WINDOW; i++)
                    Frequency_data[W_L_STOP_1+ i] *= *pShort_Window_1++;
                ENDFOR

                pLong_Window_1 =
                        &Long_Window[wnd_shape_this_bk][LONG_WINDOW_m_1];

                FOR ( i=0; i<LONG_WINDOW; i++)
                    Frequency_data[LONG_WINDOW + i]  *=  *pLong_Window_1--;
                ENDFOR

                BREAK

        }


        FOR ( i=0; i<LONG_WINDOW; i++)
            Output_buffer[i]  = Frequency_data[i]  + Time_data[i];
            Time_data[i] = Frequency_data[LONG_WINDOW+i];
        ENDFOR

    }

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
#include "imdct_fxp.h"

#include "fxp_mul32.h"


/*----------------------------------------------------------------------------
; MACROS
; limit(x) saturates any number that exceeds a 16-bit representation into a
; 16 bit number.
----------------------------------------------------------------------------*/

#define  ROUNDING_SCALED     (ROUNDING<<(16 - SCALING))


#if defined(PV_ARM_V5)


__inline Int16 sat(Int32 y)
{
    Int32 x;
    Int32 z;
    __asm
    {
        mov x, ROUNDING_SCALED
        mov y, y, lsl #(15-SCALING)
        qdadd z, x, y
        mov y, z, lsr #16
    }
    return((Int16)y);
}

#define  limiter( y, x)   y = sat(x);



#elif defined(PV_ARM_GCC_V5)


__inline Int16 sat(Int32 y)
{
    register Int32 x;
    register Int32 ra = (Int32)y;
    register Int32 z = ROUNDING_SCALED;


    asm volatile(
        "mov %0, %1, lsl #5\n\t"    // (15-SCALING) assembler does not take symbols
        "qdadd %0, %2, %0\n\t"
        "mov %0, %0, lsr #16"
    : "=&r*i"(x)
                : "r"(ra),
                "r"(z));

    return ((Int16)x);
}

#define  limiter( y, x)   y = sat(x);


#elif defined(PV_ARM_MSC_EVC_V5)


#define  limiter( y, x)       z = x<< (15-SCALING); \
                              y = _DAddSatInt( ROUNDING_SCALED, z)>>16;


#else

#define  limiter( y, x)   z = ((x + ROUNDING )>>SCALING); \
                          if ((z>>15) != (z>>31))         \
                          {                                    \
                              z = (z >> 31) ^ INT16_MAX;       \
                          } \
                          y = (Int16)(z);

#endif


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

#ifdef AAC_PLUS


void trans4m_freq_2_time_fxp_1(
    Int32               Frequency_data[],
    Int32               Time_data[],
    Int16               Output_buffer[],
    WINDOW_SEQUENCE     wnd_seq,
    Int                 wnd_shape_prev_bk,
    Int                 wnd_shape_this_bk,
    Int                 Q_format,
    Int32               abs_max_per_window[],
    Int32               freq_2_time_buffer[])

{
    Int exp;
    Int shift;

    Int  i;
    Int  wnd;
#if !(defined( PV_ARM_GCC_V5)||(PV_ARM_V5))
    Int32 z;
#endif

    Int16 *pFreqInfo;
    Int32 temp;
    Int32 test;

    Int16 *pFreq_2_Time_data_1;
    Int16 *pFreq_2_Time_data_2;

    const Int16 *pLong_Window_1;
    const Int16 *pLong_Window_2;
    const Int16 *pShort_Window_1;
    const Int16 *pShort_Window_2;

    Int32 *pOverlap_and_Add_Buffer_1;
    Int32 *pOverlap_and_Add_Buffer_2;

    Int16  *pOutput_buffer;
    Int16  *pOutput_buffer_2;

    const Int16 * Long_Window_fxp[NUM_WINDOW_SHAPES];
    const Int16 * Short_Window_fxp[NUM_WINDOW_SHAPES];

    Long_Window_fxp[0] = Long_Window_sine_fxp;
    Long_Window_fxp[1] = Long_Window_KBD_fxp;
    Short_Window_fxp[0] = Short_Window_sine_fxp;
    Short_Window_fxp[1] = Short_Window_KBD_fxp;


    if (wnd_seq != EIGHT_SHORT_SEQUENCE)
    {

        pFreqInfo = (Int16 *)Frequency_data;


        exp = imdct_fxp(
                  (Int32 *)pFreqInfo,
                  freq_2_time_buffer,
                  LONG_BLOCK1,
                  Q_format,
                  abs_max_per_window[0]);



        /*
         *  The C Programming Language, Second Edition, Kernighan & Ritchie,
         *  page 206.
         *  "The result [of a shift] is undefined if the right operand is
         *  negative, or greater than or equal to the number of bits in the
         *  left expression's type"
         *   => avoid shift by 32 or 16
         */

        if (exp < 16)
        {

            pFreq_2_Time_data_1 = pFreqInfo;

            switch (wnd_seq)
            {

                case ONLY_LONG_SEQUENCE:
                default:

                    pOutput_buffer = Output_buffer;

                    pOverlap_and_Add_Buffer_1 = Time_data;

                    {
                        const Int16 *pLong_Window_2 = &Long_Window_fxp[wnd_shape_this_bk][LONG_WINDOW_m_1];

                        Int32 * pFreq2T = (Int32 *)pFreqInfo;
                        Int32 * win = (Int32 *) & Long_Window_fxp[wnd_shape_prev_bk][0];
                        Int shift = exp + 15 - SCALING;


                        Int32 * pFreq2T_2 = &pFreq2T[HALF_LONG_WINDOW];


                        for (i = HALF_LONG_WINDOW; i != 0; i--)
                        {
                            Int16 win1, win2;
                            Int32  temp2, test2;

                            Int32  winx;

                            temp2 = *(pFreq2T++);
                            winx = *(win++);

                            test  = *(pOverlap_and_Add_Buffer_1++);
                            test2 = *(pOverlap_and_Add_Buffer_1--);
                            temp  =   fxp_mul_16_by_16bb(temp2, winx) >> shift;
                            temp2 =   fxp_mul_16_by_16tt(temp2, winx) >> shift;
                            limiter(*(pOutput_buffer++), (temp + test));
                            limiter(*(pOutput_buffer++), (temp2 + test2));

                            temp2 = *(pFreq2T_2++);

                            win1  = *(pLong_Window_2--);
                            win2  = *(pLong_Window_2--);
                            temp  = fxp_mul_16_by_16bb(temp2, win1) >> shift;
                            test2 = fxp_mul_16_by_16tb(temp2, win2) >> shift;
                            *(pOverlap_and_Add_Buffer_1++) = temp;
                            *(pOverlap_and_Add_Buffer_1++) = test2;

                        }
                    }

                    break;

                case LONG_START_SEQUENCE:


                    pFreq_2_Time_data_2 =
                        &pFreq_2_Time_data_1[ HALF_LONG_WINDOW];

                    pLong_Window_1 = &Long_Window_fxp[wnd_shape_prev_bk][0];
                    pLong_Window_2 = &pLong_Window_1[ HALF_LONG_WINDOW];

                    pOverlap_and_Add_Buffer_1 = &Time_data[0];
                    pOverlap_and_Add_Buffer_2 = &Time_data[HALF_LONG_WINDOW];

                    pOutput_buffer   = Output_buffer;
                    pOutput_buffer_2 = pOutput_buffer + HALF_LONG_WINDOW;


                    shift = exp + 15 - SCALING;

                    for (i = HALF_LONG_WINDOW; i != 0; i--)
                    {

                        Int16 win1, win2;
                        Int16  dat1, dat2;
                        Int32  test1, test2;

                        dat1   = *(pFreq_2_Time_data_1++);
                        win1   = *(pLong_Window_1++);
                        test1  = *(pOverlap_and_Add_Buffer_1++);

                        dat2  =  *(pFreq_2_Time_data_2++);
                        win2  = *(pLong_Window_2++);
                        test2 = *(pOverlap_and_Add_Buffer_2++);

                        limiter(*(pOutput_buffer++), (test1 + (fxp_mul_16_by_16(dat1, win1) >> shift)));

                        limiter(*(pOutput_buffer_2++), (test2 + (fxp_mul_16_by_16(dat2, win2) >> shift)));

                    }

                    /*
                     *  data unchanged from  LONG_WINDOW to W_L_START_1
                     *  only scaled accordingly
                     */

                    pOverlap_and_Add_Buffer_1 = &Time_data[0];
                    pFreq_2_Time_data_1       = &pFreqInfo[LONG_WINDOW];

                    exp -= SCALING;

                    if (exp >= 0)
                    {

                        for (i = (W_L_START_1 - LONG_WINDOW) >> 1; i != 0; i--)
                        {
                            *(pOverlap_and_Add_Buffer_1++) =
                                *(pFreq_2_Time_data_1++) >> exp;
                            *(pOverlap_and_Add_Buffer_1++) =
                                *(pFreq_2_Time_data_1++) >> exp;

                        }

                    }
                    else if (exp < 0)
                    {

                        Int shift = -exp;
                        for (i = (W_L_START_1 - LONG_WINDOW) >> 1; i != 0 ; i--)
                        {
                            Int32 temp2 = ((Int32) * (pFreq_2_Time_data_1++)) << shift;
                            *(pOverlap_and_Add_Buffer_1++) = temp2;
                            temp2 = ((Int32) * (pFreq_2_Time_data_1++)) << shift;
                            *(pOverlap_and_Add_Buffer_1++) = temp2;
                        }

                    }
                    else
                    {

                        for (i = (W_L_START_1 - LONG_WINDOW) >> 1; i != 0; i--)
                        {
                            *(pOverlap_and_Add_Buffer_1++) =
                                *(pFreq_2_Time_data_1++);
                            *(pOverlap_and_Add_Buffer_1++) =
                                *(pFreq_2_Time_data_1++);

                        }

                    }


                    pFreq_2_Time_data_1  = &pFreqInfo[W_L_START_1];
                    pFreq_2_Time_data_2  =
                        &pFreq_2_Time_data_1[HALF_SHORT_WINDOW];

                    pShort_Window_1   =
                        &Short_Window_fxp[wnd_shape_this_bk][SHORT_WINDOW_m_1];

                    pShort_Window_2   = pShort_Window_1 - HALF_SHORT_WINDOW;

                    pOverlap_and_Add_Buffer_2 = pOverlap_and_Add_Buffer_1 +
                                                HALF_SHORT_WINDOW;


                    for (i = HALF_SHORT_WINDOW; i != 0; i--)
                    {
                        Int16 win1, win2;
                        Int16  dat1, dat2;
                        Int32  temp2;
                        dat1  = (*pFreq_2_Time_data_1++);
                        dat2  = (*pFreq_2_Time_data_2++);
                        win1  = *(pShort_Window_1--);
                        win2  = *(pShort_Window_2--);

                        temp   =   fxp_mul_16_by_16(dat1, win1) >> shift;
                        *(pOverlap_and_Add_Buffer_1++) = temp;

                        temp2 =   fxp_mul_16_by_16(dat2, win2) >> shift;
                        *(pOverlap_and_Add_Buffer_2++) = temp2;


                    }


                    pOverlap_and_Add_Buffer_1 += HALF_SHORT_WINDOW;

                    pv_memset(
                        pOverlap_and_Add_Buffer_1,
                        0,
                        (LONG_BLOCK1 - W_L_START_2)
                        *sizeof(*pOverlap_and_Add_Buffer_1));


                    break;


                case LONG_STOP_SEQUENCE:

                    pOverlap_and_Add_Buffer_1 = &Time_data[ W_L_STOP_2];

                    pOutput_buffer         = &Output_buffer[W_L_STOP_2];

                    pFreq_2_Time_data_1      = &pFreqInfo[W_L_STOP_2];

                    exp -= SCALING; /*  !!!! */

                    if (exp > 0)
                    {
                        Int16 tmp1 = (*(pFreq_2_Time_data_1++) >> exp);
                        temp = *(pOverlap_and_Add_Buffer_1++);

                        for (i = (LONG_WINDOW - W_L_STOP_2); i != 0; i--)
                        {
                            limiter(*(pOutput_buffer++), (temp + tmp1));

                            tmp1 = *(pFreq_2_Time_data_1++) >> exp;
                            temp = *(pOverlap_and_Add_Buffer_1++);

                        }
                    }
                    else if (exp < 0)
                    {
                        shift = -exp;
                        Int32 temp1 = ((Int32) * (pFreq_2_Time_data_1++)) << shift;
                        temp = *(pOverlap_and_Add_Buffer_1++);

                        for (i = (LONG_WINDOW - W_L_STOP_2); i != 0; i--)
                        {
                            limiter(*(pOutput_buffer++), (temp + temp1));

                            temp1 = ((Int32) * (pFreq_2_Time_data_1++)) << shift;
                            temp = *(pOverlap_and_Add_Buffer_1++);

                        }
                    }
                    else
                    {
                        Int16 tmp1 = *(pFreq_2_Time_data_1++);
                        temp = *(pOverlap_and_Add_Buffer_1++);

                        for (i = (LONG_WINDOW - W_L_STOP_2); i != 0; i--)
                        {
                            limiter(*(pOutput_buffer++), (temp + tmp1));

                            tmp1 = *(pFreq_2_Time_data_1++);
                            temp = *(pOverlap_and_Add_Buffer_1++);

                        }
                    }


                    pShort_Window_1 = &Short_Window_fxp[wnd_shape_prev_bk][0];
                    pShort_Window_2 = &pShort_Window_1[HALF_SHORT_WINDOW];

                    pFreq_2_Time_data_1 = &pFreqInfo[W_L_STOP_1];
                    pFreq_2_Time_data_2 =
                        &pFreq_2_Time_data_1[HALF_SHORT_WINDOW];

                    pOverlap_and_Add_Buffer_1 = &Time_data[ W_L_STOP_1];
                    pOverlap_and_Add_Buffer_2 = pOverlap_and_Add_Buffer_1
                                                + HALF_SHORT_WINDOW;

                    pOutput_buffer   = &Output_buffer[W_L_STOP_1];
                    pOutput_buffer_2 = pOutput_buffer + HALF_SHORT_WINDOW;

                    exp += SCALING;  /* +8 back to what it was */

                    shift = exp + 15 - SCALING;


                    for (i = HALF_SHORT_WINDOW; i != 0; i--)
                    {
                        Int16 win1;
                        Int16  dat1;

                        dat1 = *(pFreq_2_Time_data_1++);
                        win1 = *(pShort_Window_1++);
                        temp = *(pOverlap_and_Add_Buffer_1++);

                        test  = fxp_mul_16_by_16(dat1, win1);

                        limiter(*(pOutput_buffer++), (temp + (test >> shift)));

                        dat1 = *(pFreq_2_Time_data_2++);
                        win1 = *(pShort_Window_2++);
                        temp = *(pOverlap_and_Add_Buffer_2++);
                        test =  fxp_mul_16_by_16(dat1, win1);
                        limiter(*(pOutput_buffer_2++), (temp + (test >> shift)));

                    }


                    pFreq_2_Time_data_2 = &pFreqInfo[LONG_WINDOW];

                    pOverlap_and_Add_Buffer_1 = Time_data;

                    pOutput_buffer = Output_buffer;

                    pLong_Window_2   =
                        &Long_Window_fxp[wnd_shape_this_bk][LONG_WINDOW_m_1];


                    /*
                     *  Copy previous time in current buffer, also copy overlap
                     *  and add buffer
                     */

                    for (i = W_L_STOP_1; i != 0; i--)
                    {
                        Int16 win1;
                        Int16 dat1;

                        win1 = *(pLong_Window_2--);
                        dat1 = *pFreq_2_Time_data_2++;

                        limiter(*(pOutput_buffer++), *(pOverlap_and_Add_Buffer_1));


                        temp = fxp_mul_16_by_16(dat1, win1) >> shift;
                        *(pOverlap_and_Add_Buffer_1++) = temp ;

                    }

                    for (i = (LONG_WINDOW - W_L_STOP_1); i != 0; i--)
                    {
                        temp = fxp_mul_16_by_16(*pFreq_2_Time_data_2++, *(pLong_Window_2--)) >> shift;
                        *(pOverlap_and_Add_Buffer_1++) = temp ;
                    }


                    break;



            } /* switch (wnd_seq) */

        }   /*  if (exp < 16)  */

        else
        {
            /* all zeros buffer or excessive down shift */

            /* Overlap and add, setup buffer for next iteration */
            pOverlap_and_Add_Buffer_1 = &Time_data[0];

            pOutput_buffer = Output_buffer;

            temp  = (*pOverlap_and_Add_Buffer_1++);

            for (i = LONG_WINDOW; i != 0; i--)
            {

                limiter(*(pOutput_buffer++), temp);

                temp = (*pOverlap_and_Add_Buffer_1++);

            }

            pv_memset(Time_data, 0, LONG_WINDOW*sizeof(Time_data[0]));


        }

    }
    else
    {

        Int32 *pScrath_mem;
        Int32 *pScrath_mem_entry;
        Int32  *pFrequency_data = Frequency_data;

        Int32 * pOverlap_and_Add_Buffer_1;
        Int32 * pOverlap_and_Add_Buffer_2;
        Int32 * pOverlap_and_Add_Buffer_1x;
        Int32 * pOverlap_and_Add_Buffer_2x;

        /*
         *  Frequency_data is 2*LONG_WINDOW length but only
         *  the first LONG_WINDOW elements are filled in,
         *  then the second part can be used as scratch mem,
         *  then grab data from one window at a time in
         *  reverse order.
         *  The upper LONG_WINDOW Int32 are used to hold the
         *  computed overlap and add, used in the next call to
         *  this function, and also as sctrach memory
         */

        /*
         *  Frequency_data usage for the case EIGHT_SHORT_SEQUENCE

          |<----- Input Freq. data ----->|< Overlap & Add ->| Unused |-Scratch-|
          |                              |  Store for next  |        |  memory |
          |                              |  call            |        |         |
          |                              |                  |        |         |
          |//////////////////////////////|\\\\\\\\\\\\\\\\\\|--------|+++++++++|
          |                              |                  |        |         |
          0                         LONG_WINDOW        LONG_WINDOW   |   2*LONG_WINDOW
                                                            +        |         |
                                                       W_L_STOP_2    |         |
                                                                     |<--   -->|
                                                                      SHORT_WINDOW +
                                                                    HALF_SHORT_WINDOW
          *
          */

        pOverlap_and_Add_Buffer_1  = &pFrequency_data[
                                         LONG_WINDOW + 3*SHORT_WINDOW + HALF_SHORT_WINDOW];

        /*
         *  Initialize to zero, only the firt short window used in overlap
         *  and add
         */
        pv_memset(
            pOverlap_and_Add_Buffer_1,
            0,
            SHORT_WINDOW*sizeof(*pOverlap_and_Add_Buffer_1));

        /*
         *  Showt windows are evaluated in decresing order. Windows from 7
         *  to 0 are break down in four cases: window numbers 7 to 5, 4, 3,
         *  and 2 to 0.
         *  The data from short windows 3 and 4 is situated at the boundary
         *  between the 'overlap and add' buffer and the output buffer.
         */
        for (wnd = NUM_SHORT_WINDOWS - 1; wnd >= NUM_SHORT_WINDOWS / 2 + 1; wnd--)
        {

            pFreqInfo = (Int16 *) & pFrequency_data[ wnd*SHORT_WINDOW];

            exp = imdct_fxp(
                      (Int32 *)pFreqInfo,
                      freq_2_time_buffer,
                      SHORT_BLOCK1,
                      Q_format,
                      abs_max_per_window[wnd]);

            pOverlap_and_Add_Buffer_1 =
                &pFrequency_data[ W_L_STOP_1 + SHORT_WINDOW*wnd];


            pOverlap_and_Add_Buffer_2 =
                pOverlap_and_Add_Buffer_1 + SHORT_WINDOW;

            /*
             *  If all element are zero or if the exponent is bigger than
             *  16 ( it becomes an undefined shift) ->  skip
             */

            if (exp < 16)
            {


                pFreq_2_Time_data_1 = &pFreqInfo[0];
                pFreq_2_Time_data_2 = &pFreqInfo[SHORT_WINDOW];


                /*
                 *  Each of the eight short blocks is windowed separately.
                 *  Window shape decisions are made on a frame-by-frame
                 *  basis.
                 */

                pShort_Window_1 = &Short_Window_fxp[wnd_shape_this_bk][0];

                pShort_Window_2   =
                    &Short_Window_fxp[wnd_shape_this_bk][SHORT_WINDOW_m_1];




                /*
                 * For short windows from 7 to 5
                 *                                      |   =========================
                 *                                      |   |     5     6     7
                 *               _--_  _--_  _--_  _--_ | _-|-_  _--_  _--_  _--_
                 *              /    \/    \/    \/    \|/  |  \/    \/    \/    \
                 *             /     /\    /\    /\    /|\  |  /\    /\    /\     \
                 *            /     /  \  /  \  /  \  / | \ | /  \  /  \  /  \     \
                 *           /     /    \/    \/    \/  |  \|/    \/    \     \     \
                 *      --------------------------------|---[///////////////////////]--------
                 *
                 */


                shift = exp + 15 - SCALING;


                for (i = SHORT_WINDOW; i != 0; i--)
                {
                    Int16 win1, win2;
                    Int16  dat1, dat2;

                    dat2 = *(pFreq_2_Time_data_2++);
                    win2 = *(pShort_Window_2--);
                    temp = *pOverlap_and_Add_Buffer_2;
                    dat1 = *(pFreq_2_Time_data_1++);
                    win1 = *(pShort_Window_1++);

                    *(pOverlap_and_Add_Buffer_2++) =  temp + (fxp_mul_16_by_16(dat2, win2) >> shift);

                    *(pOverlap_and_Add_Buffer_1++)  =  fxp_mul_16_by_16(dat1, win1) >> shift;

                }

            }   /* if (exp < 16) */
            else
            {
                pv_memset(
                    pOverlap_and_Add_Buffer_1,
                    0,
                    SHORT_WINDOW*sizeof(*pOverlap_and_Add_Buffer_1));
            }


        }/* for ( wnd=NUM_SHORT_WINDOWS-1; wnd>=NUM_SHORT_WINDOWS/2; wnd--) */


        wnd = NUM_SHORT_WINDOWS / 2;

        pFreqInfo = (Int16 *) & pFrequency_data[ wnd*SHORT_WINDOW];

        /*
         *  scratch memory is allocated in an unused part of memory
         */


        pScrath_mem = &pFrequency_data[ 2*LONG_WINDOW - HALF_SHORT_WINDOW];

        pOverlap_and_Add_Buffer_1 = &pFrequency_data[ LONG_WINDOW];

        pOverlap_and_Add_Buffer_2 = pOverlap_and_Add_Buffer_1
                                    + HALF_SHORT_WINDOW;


        exp = imdct_fxp(
                  (Int32 *)pFreqInfo,
                  freq_2_time_buffer,
                  SHORT_BLOCK1,
                  Q_format,
                  abs_max_per_window[wnd]);

        /*
         *  If all element are zero or if the exponent is bigger than
         *  16 ( it becomes an undefined shift) ->  skip
         */


        if (exp < 16)
        {

            pFreq_2_Time_data_1 = &pFreqInfo[0];
            pFreq_2_Time_data_2 = &pFreqInfo[SHORT_WINDOW];

            pShort_Window_1 = &Short_Window_fxp[wnd_shape_this_bk][0];

            pShort_Window_2 =
                &Short_Window_fxp[wnd_shape_this_bk][SHORT_WINDOW_m_1];


            /*
             * For short window 4
             *                                    ====|===========
             *                                        |   4
             *                                    |   |   |      |
             *                _--_  _--_  _--_  _-|-_ | _-|-_  _-|-_  _--_  _--_
             *               /    \/    \/    \/  |  \|/  |  \/  |  \/    \/    \
             *              /     /\    /\    /\  |  /|\  |  /\  |  /\    /\     \
             *             /     /  \  /  \  /  \ | / | \ | /  \ | /  \  /  \     \
             *            /     /    \/    \/    \|/  |  \|/    \|/    \/    \     \
             *      ------------------------------[\\\|\\\|//////]-------------------
             *           |                        | A | B |   C  |
             *           |
             *        W_L_STOP_1
             */

            shift = exp + 15 - SCALING;
            {
                Int16 win1;
                Int16  dat1;
                /* -------- segment A ---------------*/
                dat1 = *(pFreq_2_Time_data_1++);
                win1 = *(pShort_Window_1++);
                for (i = HALF_SHORT_WINDOW; i != 0; i--)
                {
                    *(pScrath_mem++)  =  fxp_mul_16_by_16(dat1, win1) >> (shift);
                    dat1 = *(pFreq_2_Time_data_1++);
                    win1 = *(pShort_Window_1++);
                }

                /* -------- segment B ---------------*/
                for (i = HALF_SHORT_WINDOW; i != 0; i--)
                {
                    *(pOverlap_and_Add_Buffer_1++)  =  fxp_mul_16_by_16(dat1, win1) >> shift;

                    dat1 = *(pFreq_2_Time_data_1++);
                    win1 = *(pShort_Window_1++);
                }

                /* -------- segment C ---------------*/
                temp = *pOverlap_and_Add_Buffer_2;
                dat1 = *(pFreq_2_Time_data_2++);
                win1 = *(pShort_Window_2--);

                for (i = SHORT_WINDOW; i != 0; i--)
                {
                    *(pOverlap_and_Add_Buffer_2++)  =  temp + (fxp_mul_16_by_16(dat1, win1) >> shift);

                    temp = *pOverlap_and_Add_Buffer_2;
                    dat1 = *(pFreq_2_Time_data_2++);
                    win1 = *(pShort_Window_2--);
                }
            }

        }   /* if (exp < 16) */
        else
        {
            pv_memset(
                pScrath_mem,
                0,
                HALF_SHORT_WINDOW*sizeof(*pScrath_mem));

            pv_memset(
                pOverlap_and_Add_Buffer_1,
                0,
                HALF_SHORT_WINDOW*sizeof(*pOverlap_and_Add_Buffer_1));
        }


        wnd = NUM_SHORT_WINDOWS / 2 - 1;

        pFreqInfo = (Int16 *) & pFrequency_data[ wnd*SHORT_WINDOW];

        pScrath_mem_entry =
            &pFrequency_data[2*LONG_WINDOW - HALF_SHORT_WINDOW - SHORT_WINDOW];
        pScrath_mem = pScrath_mem_entry;

        pOverlap_and_Add_Buffer_1 = &pFrequency_data[ LONG_WINDOW];

        /* point to end of buffer less HALF_SHORT_WINDOW */

        pOutput_buffer_2 = &Output_buffer[LONG_WINDOW - HALF_SHORT_WINDOW];
        pOutput_buffer   = pOutput_buffer_2;

        pOverlap_and_Add_Buffer_1x = &Time_data[W_L_STOP_1 + SHORT_WINDOW*(wnd+1)];  /* !!!! */

        exp = imdct_fxp(
                  (Int32 *)pFreqInfo,
                  freq_2_time_buffer,
                  SHORT_BLOCK1,
                  Q_format,
                  abs_max_per_window[wnd]);

        /*
         *  If all element are zero or if the exponent is bigger than
         *  16 ( it becomes an undefined shift) ->  skip
         */

        if (exp < 16)
        {

            pFreq_2_Time_data_1 = &pFreqInfo[0];
            pFreq_2_Time_data_2 = &pFreqInfo[SHORT_WINDOW];


            /*
             * For short window 3
             *                             ===========|====
             *                                    3   |
             *                             |      |   |   |
             *               _--_  _--_  _-|-_  _-|-_ | _-|-_  _--_  _--_  _--_
             *              /    \/    \/  |  \/  |  \|/  |  \/    \/    \/    \
             *             /     /\    /\  |  /\  |  /|\  |  /\    /\    /\     \
             *            /     /  \  /  \ | /  \ | / | \ | /  \  /  \  /  \     \
             *           /     /    \/    \|/    \|/  |  \|/    \/    \     \     \
             *     -----|------------------[\\\\\\|///|///]--------------------------
             *          |                  |   A  | B | C |
             *
             *      W_L_STOP_1
             */


            pShort_Window_1 = &Short_Window_fxp[wnd_shape_this_bk][0];

            pShort_Window_2 =
                &Short_Window_fxp[wnd_shape_this_bk][SHORT_WINDOW_m_1];

            shift = exp + 15 - SCALING;


            Int16 win1;
            Int16  dat1;
            /* -------- segment A ---------------*/
            dat1 = *(pFreq_2_Time_data_1++);
            win1 = *(pShort_Window_1++);
            for (i = SHORT_WINDOW; i != 0; i--)
            {
                *(pScrath_mem++)  =  fxp_mul_16_by_16(dat1, win1) >> shift;
                dat1 = *(pFreq_2_Time_data_1++);
                win1 = *(pShort_Window_1++);
            }

            dat1 = *(pFreq_2_Time_data_2++);
            win1 = *(pShort_Window_2--);

            /* -------- segment B ---------------*/
            for (i = HALF_SHORT_WINDOW; i != 0; i--)
            {
                test = fxp_mul_16_by_16(dat1, win1) >> shift;

                temp =  *(pScrath_mem++) + test;


                test = *(pOverlap_and_Add_Buffer_1x++);  /* !!!! */

                limiter(*(pOutput_buffer++), (temp + test));

                dat1 = *(pFreq_2_Time_data_2++);
                win1 = *(pShort_Window_2--);

            }

            /* -------- segment C ---------------*/
            for (i = HALF_SHORT_WINDOW; i != 0; i--)
            {
                temp = fxp_mul_16_by_16(dat1, win1) >> (shift);

                *(pOverlap_and_Add_Buffer_1++) += temp;

                dat1 = *(pFreq_2_Time_data_2++);
                win1 = *(pShort_Window_2--);
            }

        }   /* if (exp < 16) */
        else
        {

            pv_memset(
                pScrath_mem,
                0,
                SHORT_WINDOW*sizeof(*pScrath_mem));

            pScrath_mem += SHORT_WINDOW;

            temp = *(pScrath_mem++);
            for (i = HALF_SHORT_WINDOW; i != 0; i--)
            {
                limiter(*(pOutput_buffer++), temp);
                temp = *(pScrath_mem++);


            }
        }


        for (wnd = NUM_SHORT_WINDOWS / 2 - 2; wnd >= 0; wnd--)
        {


            pOutput_buffer_2 -= SHORT_WINDOW;
            pOutput_buffer = pOutput_buffer_2;

            /*
             * The same memory is used as scratch in every iteration
             */
            pScrath_mem = pScrath_mem_entry;

            pOverlap_and_Add_Buffer_2x =
                &Time_data[W_L_STOP_1 + SHORT_WINDOW*(wnd+1)];

            pFreqInfo = (Int16 *) & pFrequency_data[ wnd*SHORT_WINDOW];



            exp = imdct_fxp(
                      (Int32 *)pFreqInfo,
                      freq_2_time_buffer,
                      SHORT_BLOCK1,
                      Q_format,
                      abs_max_per_window[wnd]);

            /*
             *  If all element are zero or if the exponent is bigger than
             *  16 ( it becomes an undefined shift) ->  skip
             */

            if (exp < 16)
            {

                pFreq_2_Time_data_1 = &pFreqInfo[0];
                pFreq_2_Time_data_2 = &pFreqInfo[SHORT_WINDOW];


                /*
                 *  Each of the eight short blocks is windowed separately.
                 *  Window shape decisions are made on a frame-by-frame
                 *  basis.
                 */

                pShort_Window_1 = &Short_Window_fxp[wnd_shape_this_bk][0];

                if (wnd == 0)
                {
                    pShort_Window_1 =
                        &Short_Window_fxp[wnd_shape_prev_bk][0];
                }

                pShort_Window_2   =
                    &Short_Window_fxp[wnd_shape_this_bk][SHORT_WINDOW_m_1];


                /*
                 * For short windows from 2 to 0
                 *
                 *          =========================
                 *                                       |
                 *                0     1     2      |   |
                 *               _--_  _--_  _--_  _-|-_ | _--_  _--_  _--_  _--_
                 *              /    \/    \/    \/  |  \|/    \/    \/    \/    \
                 *             /     /\    /\    /\  |  /|\    /\    /\    /\     \
                 *            /     /  \  /  \  /  \ | / | \  /  \  /  \  /  \     \
                 *           /     /    \/    \/    \|/  |  \/    \/    \     \     \
                 *      ----[\\\\\\\\\\\\\\\\\\\\\\\\]---|-----------------------------
                 *          |
                 *
                 *      W_L_STOP_1
                 */

                shift = exp + 15 - SCALING;

                Int16 dat1 = *(pFreq_2_Time_data_2++);
                Int16 win1 = *(pShort_Window_2--);

                temp  =  *(pScrath_mem);
                for (i = SHORT_WINDOW; i != 0; i--)
                {
                    test  =  fxp_mul_16_by_16(dat1, win1) >> shift;

                    temp += test;

                    dat1 = *(pFreq_2_Time_data_1++);
                    win1 = *(pShort_Window_1++);

                    limiter(*(pOutput_buffer++), (temp + *(pOverlap_and_Add_Buffer_2x++)));


                    *(pScrath_mem++) = fxp_mul_16_by_16(dat1, win1) >> shift;
                    dat1 = *(pFreq_2_Time_data_2++);
                    win1 = *(pShort_Window_2--);
                    temp  =  *(pScrath_mem);

                }

            }   /* if (exp < 16) */
            else
            {
                test  = *(pScrath_mem);
                temp  = *(pOverlap_and_Add_Buffer_2x++);

                for (i = SHORT_WINDOW; i != 0; i--)
                {
                    limiter(*(pOutput_buffer++), (temp + test));

                    *(pScrath_mem++) = 0;
                    test  =  *(pScrath_mem);
                    temp  = *(pOverlap_and_Add_Buffer_2x++);

                }
            }

        }   /* for ( wnd=NUM_SHORT_WINDOWS/2-1; wnd>=0; wnd--) */

        pOverlap_and_Add_Buffer_2x =  &Time_data[W_L_STOP_1];

        pScrath_mem = pScrath_mem_entry;

        pOutput_buffer_2 -= SHORT_WINDOW;
        pOutput_buffer = pOutput_buffer_2;

        test  = *(pScrath_mem++);
        temp  = *(pOverlap_and_Add_Buffer_2x++);

        for (i = SHORT_WINDOW; i != 0; i--)
        {
            limiter(*(pOutput_buffer++), (temp + test));

            test  = *(pScrath_mem++);
            temp  = *(pOverlap_and_Add_Buffer_2x++);

        }

        pOverlap_and_Add_Buffer_1x = Time_data;

        pOutput_buffer = Output_buffer;


        temp = *(pOverlap_and_Add_Buffer_1x++);

        for (i = W_L_STOP_1; i != 0; i--)
        {
            limiter(*(pOutput_buffer++), temp);

            temp = *(pOverlap_and_Add_Buffer_1x++);
        }

        pOverlap_and_Add_Buffer_1x = &Time_data[0];

        pOverlap_and_Add_Buffer_2 = &pFrequency_data[LONG_WINDOW];

        /*
         *  update overlap and add buffer,
         *  so is ready for next iteration
         */

        for (int i = 0; i < W_L_STOP_2; i++)
        {
            temp = *(pOverlap_and_Add_Buffer_2++);
            *(pOverlap_and_Add_Buffer_1x++) = temp;
        }

        pv_memset(
            pOverlap_and_Add_Buffer_1x,
            0,
            W_L_STOP_1*sizeof(*pOverlap_and_Add_Buffer_1x));

    } /* if ( wnd_seq != EIGHT_SHORT_SEQUENCE) */

}

#endif
/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/



void trans4m_freq_2_time_fxp_2(
    Int32               Frequency_data[],
    Int32               Time_data[],
    WINDOW_SEQUENCE     wnd_seq,
    Int                 wnd_shape_prev_bk,
    Int                 wnd_shape_this_bk,
    Int                 Q_format,
    Int32               abs_max_per_window[],
    Int32               freq_2_time_buffer[],
    Int16               *Interleaved_output)

{

    Int exp;
    Int shift;

    Int  i;
    Int  wnd;
#if !(defined( PV_ARM_GCC_V5)||(PV_ARM_V5))
    Int32 z;
#endif
    Int16 *pFreqInfo;
    Int32 temp;
    Int32 test;

    Int16 *pFreq_2_Time_data_1;
    Int16 *pFreq_2_Time_data_2;

    const Int16 *pLong_Window_1;
    const Int16 *pLong_Window_2;
    const Int16 *pShort_Window_1;
    const Int16 *pShort_Window_2;

    Int32 *pOverlap_and_Add_Buffer_1;
    Int32 *pOverlap_and_Add_Buffer_2;

    Int16  *pInterleaved_output;
    Int16  *pInterleaved_output_2;


    const Int16 * Long_Window_fxp[NUM_WINDOW_SHAPES];
    const Int16 * Short_Window_fxp[NUM_WINDOW_SHAPES];

    Long_Window_fxp[0] = Long_Window_sine_fxp;
    Long_Window_fxp[1] = Long_Window_KBD_fxp;
    Short_Window_fxp[0] = Short_Window_sine_fxp;
    Short_Window_fxp[1] = Short_Window_KBD_fxp;

    if (wnd_seq != EIGHT_SHORT_SEQUENCE)
    {

        pFreqInfo = (Int16 *)Frequency_data;


        exp = imdct_fxp(
                  (Int32 *)pFreqInfo,
                  freq_2_time_buffer,
                  LONG_BLOCK1,
                  Q_format,
                  abs_max_per_window[0]);


        /*
         *  The C Programming Language, Second Edition, Kernighan & Ritchie,
         *  page 206.
         *  "The result [of a shift] is undefined if the right operand is
         *  negative, or greater than or equal to the number of bits in the
         *  left expression's type"
         *   => avoid shift by 32 or 16
         */

        if (exp < 16)
        {

            pFreq_2_Time_data_1 = pFreqInfo;


            switch (wnd_seq)
            {

                case ONLY_LONG_SEQUENCE:
                default:

                {
                    pOverlap_and_Add_Buffer_1 = Time_data;

                    pInterleaved_output = Interleaved_output;

                    {

                        const Int16 *pLong_Window_2 = &Long_Window_fxp[wnd_shape_this_bk][LONG_WINDOW_m_1];

                        Int32 * pFreq2T   = (Int32 *)pFreqInfo;
                        Int32 * pFreq2T_2 = &pFreq2T[HALF_LONG_WINDOW];
                        Int32 * win = (Int32 *) & Long_Window_fxp[wnd_shape_prev_bk][0];

                        Int shift = exp + 15 - SCALING;

                        for (i = HALF_LONG_WINDOW; i != 0; i--)
                        {
                            Int16 win1, win2;
                            Int32  temp2, test2;

                            Int32  winx;

                            temp2 = *(pFreq2T++);
                            winx = *(win++);

                            test  = *(pOverlap_and_Add_Buffer_1++);
                            test2 = *(pOverlap_and_Add_Buffer_1--);
                            temp  =   fxp_mul_16_by_16bb(temp2, winx) >> shift;
                            temp2 =   fxp_mul_16_by_16tt(temp2, winx) >> shift;

                            limiter(*(pInterleaved_output), (temp + test));

                            limiter(*(pInterleaved_output + 2), (temp2 + test2));
                            pInterleaved_output += 4;

                            temp2 = *(pFreq2T_2++);

                            win1  = *(pLong_Window_2--);
                            win2  = *(pLong_Window_2--);
                            temp  = fxp_mul_16_by_16bb(temp2, win1) >> shift;
                            test2 = fxp_mul_16_by_16tb(temp2, win2) >> shift;

                            *(pOverlap_and_Add_Buffer_1++) = temp;
                            *(pOverlap_and_Add_Buffer_1++) = test2;
                        }

                    }

                }

                break;

                case LONG_START_SEQUENCE:

                    pFreq_2_Time_data_2 =
                        &pFreq_2_Time_data_1[ HALF_LONG_WINDOW];

                    pLong_Window_1 = &Long_Window_fxp[wnd_shape_prev_bk][0];
                    pLong_Window_2 = &pLong_Window_1[ HALF_LONG_WINDOW];

                    pOverlap_and_Add_Buffer_1 = &Time_data[0];
                    pOverlap_and_Add_Buffer_2 = &Time_data[HALF_LONG_WINDOW];


                    pInterleaved_output   = Interleaved_output;
                    pInterleaved_output_2 = pInterleaved_output + (2 * HALF_LONG_WINDOW);


                    /*
                     *  process first  LONG_WINDOW elements
                     */

                    shift = exp + 15 - SCALING;

                    for (i = HALF_LONG_WINDOW; i != 0; i--)
                    {
                        Int16 win1, win2;
                        Int16  dat1, dat2;
                        Int32  test1, test2;

                        dat1   = *(pFreq_2_Time_data_1++);
                        win1   = *(pLong_Window_1++);
                        test1  = *(pOverlap_and_Add_Buffer_1++);

                        dat2  =  *(pFreq_2_Time_data_2++);
                        win2  = *(pLong_Window_2++);
                        test2 = *(pOverlap_and_Add_Buffer_2++);

                        limiter(*(pInterleaved_output), (test1 + (fxp_mul_16_by_16(dat1, win1) >> shift)));

                        pInterleaved_output   += 2;

                        limiter(*(pInterleaved_output_2), (test2 + (fxp_mul_16_by_16(dat2, win2) >> shift)));

                        pInterleaved_output_2    += 2;
                    }


                    /*
                     *  data unchanged from  LONG_WINDOW to W_L_START_1
                     *  only scaled accordingly
                     */

                    pOverlap_and_Add_Buffer_1 = &Time_data[0];
                    pFreq_2_Time_data_1       = &pFreqInfo[LONG_WINDOW];

                    exp -= SCALING;

                    if (exp >= 0)
                    {

                        for (i = (W_L_START_1 - LONG_WINDOW) >> 1; i != 0; i--)
                        {
                            *(pOverlap_and_Add_Buffer_1++) =
                                *(pFreq_2_Time_data_1++) >> exp;
                            *(pOverlap_and_Add_Buffer_1++) =
                                *(pFreq_2_Time_data_1++) >> exp;

                        }

                    }
                    else if (exp < 0)
                    {

                        Int shift = -exp;
                        for (i = (W_L_START_1 - LONG_WINDOW) >> 1; i != 0 ; i--)
                        {
                            Int32 temp2 = ((Int32) * (pFreq_2_Time_data_1++)) << shift;
                            *(pOverlap_and_Add_Buffer_1++) = temp2;
                            temp2 = ((Int32) * (pFreq_2_Time_data_1++)) << shift;
                            *(pOverlap_and_Add_Buffer_1++) = temp2;
                        }

                    }
                    else
                    {

                        for (i = (W_L_START_1 - LONG_WINDOW) >> 1; i != 0; i--)
                        {
                            *(pOverlap_and_Add_Buffer_1++) =
                                *(pFreq_2_Time_data_1++);
                            *(pOverlap_and_Add_Buffer_1++) =
                                *(pFreq_2_Time_data_1++);

                        }

                    }


                    pFreq_2_Time_data_1  = &pFreqInfo[W_L_START_1];
                    pFreq_2_Time_data_2  =
                        &pFreq_2_Time_data_1[HALF_SHORT_WINDOW];

                    pShort_Window_1   =
                        &Short_Window_fxp[wnd_shape_this_bk][SHORT_WINDOW_m_1];

                    pShort_Window_2   = pShort_Window_1 - HALF_SHORT_WINDOW;

                    pOverlap_and_Add_Buffer_2 = pOverlap_and_Add_Buffer_1 +
                                                HALF_SHORT_WINDOW;

                    {
                        Int16 win1, win2;
                        Int16  dat1, dat2;
                        Int32  temp2;

                        for (i = HALF_SHORT_WINDOW; i != 0; i--)
                        {

                            dat1  = (*pFreq_2_Time_data_1++);
                            dat2  = (*pFreq_2_Time_data_2++);
                            win1  = *(pShort_Window_1--);
                            win2  = *(pShort_Window_2--);

                            temp   =   fxp_mul_16_by_16(dat1, win1) >> shift;
                            *(pOverlap_and_Add_Buffer_1++) = temp;

                            temp2 =   fxp_mul_16_by_16(dat2, win2) >> shift;
                            *(pOverlap_and_Add_Buffer_2++) = temp2;

                        }
                    }

                    pOverlap_and_Add_Buffer_1 += HALF_SHORT_WINDOW;


                    pv_memset(
                        pOverlap_and_Add_Buffer_1,
                        0,
                        (LONG_BLOCK1 - W_L_START_2)
                        *sizeof(*pOverlap_and_Add_Buffer_1));


                    break;


                case LONG_STOP_SEQUENCE:

                    pOverlap_and_Add_Buffer_1 = &Time_data[ W_L_STOP_2];

                    pInterleaved_output    = &Interleaved_output[2*W_L_STOP_2];

                    pFreq_2_Time_data_1      = &pFreqInfo[W_L_STOP_2];

                    exp -= SCALING;


                    if (exp > 0)
                    {
                        Int16 tmp1 = (*(pFreq_2_Time_data_1++) >> exp);
                        temp = *(pOverlap_and_Add_Buffer_1++);

                        for (i = (LONG_WINDOW - W_L_STOP_2); i != 0; i--)
                        {
                            limiter(*(pInterleaved_output), (temp + tmp1));

                            pInterleaved_output += 2;
                            tmp1 = *(pFreq_2_Time_data_1++) >> exp;
                            temp = *(pOverlap_and_Add_Buffer_1++);
                        }
                    }
                    else if (exp < 0)
                    {
                        shift = -exp;

                        Int32 temp1 = ((Int32) * (pFreq_2_Time_data_1++)) << shift;
                        temp = *(pOverlap_and_Add_Buffer_1++);

                        for (i = (LONG_WINDOW - W_L_STOP_2); i != 0; i--)
                        {
                            limiter(*(pInterleaved_output), (temp + temp1));

                            pInterleaved_output += 2;
                            temp1 = ((Int32) * (pFreq_2_Time_data_1++)) << shift;
                            temp = *(pOverlap_and_Add_Buffer_1++);
                        }
                    }
                    else
                    {
                        Int16 tmp1 = *(pFreq_2_Time_data_1++);
                        temp = *(pOverlap_and_Add_Buffer_1++);
                        for (i = (LONG_WINDOW - W_L_STOP_2); i != 0; i--)
                        {
                            limiter(*(pInterleaved_output), (temp + tmp1));

                            pInterleaved_output += 2;
                            tmp1 = *(pFreq_2_Time_data_1++);
                            temp = *(pOverlap_and_Add_Buffer_1++);
                        }
                    }



                    pShort_Window_1 = &Short_Window_fxp[wnd_shape_prev_bk][0];
                    pShort_Window_2 = &pShort_Window_1[HALF_SHORT_WINDOW];

                    pFreq_2_Time_data_1 = &pFreqInfo[W_L_STOP_1];
                    pFreq_2_Time_data_2 =
                        &pFreq_2_Time_data_1[HALF_SHORT_WINDOW];

                    pOverlap_and_Add_Buffer_1 = &Time_data[ W_L_STOP_1];
                    pOverlap_and_Add_Buffer_2 = pOverlap_and_Add_Buffer_1
                                                + HALF_SHORT_WINDOW;


                    pInterleaved_output   = &Interleaved_output[2*W_L_STOP_1];
                    pInterleaved_output_2 = pInterleaved_output + (2 * HALF_SHORT_WINDOW);

                    exp += SCALING;  /* +8 back to what it was */
                    shift = exp + 15 - SCALING;


                    for (i = HALF_SHORT_WINDOW; i != 0; i--)
                    {

                        Int16 win1;
                        Int16 dat1;

                        dat1 = *(pFreq_2_Time_data_1++);
                        win1 = *(pShort_Window_1++);
                        temp = *(pOverlap_and_Add_Buffer_1++);

                        test  = fxp_mul_16_by_16(dat1, win1);

                        limiter(*(pInterleaved_output), (temp + (test >> shift)));

                        pInterleaved_output += 2;

                        dat1 = *(pFreq_2_Time_data_2++);
                        win1 = *(pShort_Window_2++);
                        temp = *(pOverlap_and_Add_Buffer_2++);
                        test =  fxp_mul_16_by_16(dat1, win1);

                        limiter(*(pInterleaved_output_2), (temp + (test >> shift)));

                        pInterleaved_output_2 += 2;

                    }



                    pFreq_2_Time_data_2 = &pFreqInfo[LONG_WINDOW];

                    pOverlap_and_Add_Buffer_1 = Time_data;


                    pInterleaved_output = Interleaved_output;

                    pLong_Window_2   =
                        &Long_Window_fxp[wnd_shape_this_bk][LONG_WINDOW_m_1];


                    /*
                     *  Copy previous time in current buffer, also copy overlap
                     *  and add buffer
                     */

                    for (i = W_L_STOP_1; i != 0; i--)
                    {

                        Int16 win1;
                        Int16 dat1;

                        win1 = *(pLong_Window_2--);
                        dat1 = *pFreq_2_Time_data_2++;

                        limiter(*(pInterleaved_output), *(pOverlap_and_Add_Buffer_1));

                        pInterleaved_output += 2;

                        temp = fxp_mul_16_by_16(dat1, win1) >> shift;
                        *(pOverlap_and_Add_Buffer_1++) = temp ;

                    }

                    for (i = (LONG_WINDOW - W_L_STOP_1); i != 0; i--)
                    {

                        temp = fxp_mul_16_by_16(*pFreq_2_Time_data_2++, *(pLong_Window_2--)) >> shift;
                        *(pOverlap_and_Add_Buffer_1++) = temp ;

                    }

                    break;



            } /* switch (wnd_seq) */

        }   /*  if (exp < 16)  */

        else
        {
            /* all zeros buffer or excessive down shift */

            /* Overlap and add, setup buffer for next iteration */
            pOverlap_and_Add_Buffer_1 = &Time_data[0];

            pInterleaved_output = Interleaved_output;


            temp  = (*pOverlap_and_Add_Buffer_1++);
            for (i = LONG_WINDOW; i != 0; i--)
            {

                limiter(*(pInterleaved_output), temp);

                pInterleaved_output += 2;
                temp  = (*pOverlap_and_Add_Buffer_1++);
            }
            pv_memset(Time_data, 0, LONG_WINDOW*sizeof(Time_data[0]));
        }

    }
    else
    {

        Int32 *pScrath_mem;
        Int32 *pScrath_mem_entry;
        Int32  *pFrequency_data = Frequency_data;

        Int32 * pOverlap_and_Add_Buffer_1;
        Int32 * pOverlap_and_Add_Buffer_2;
        Int32 * pOverlap_and_Add_Buffer_1x;
        Int32 * pOverlap_and_Add_Buffer_2x;


        /*
         *  Frequency_data is 2*LONG_WINDOW length but only
         *  the first LONG_WINDOW elements are filled in,
         *  then the second part can be used as scratch mem,
         *  then grab data from one window at a time in
         *  reverse order.
         *  The upper LONG_WINDOW Int32 are used to hold the
         *  computed overlap and add, used in the next call to
         *  this function, and also as sctrach memory
         */

        /*
         *  Frequency_data usage for the case EIGHT_SHORT_SEQUENCE

          |<----- Input Freq. data ----->|< Overlap & Add ->| Unused |-Scratch-|
          |                              |  Store for next  |        |  memory |
          |                              |  call            |        |         |
          |                              |                  |        |         |
          |//////////////////////////////|\\\\\\\\\\\\\\\\\\|--------|+++++++++|
          |                              |                  |        |         |
          0                         LONG_WINDOW        LONG_WINDOW   |   2*LONG_WINDOW
                                                            +        |         |
                                                       W_L_STOP_2    |         |
                                                                     |<--   -->|
                                                                      SHORT_WINDOW +
                                                                    HALF_SHORT_WINDOW
          *
          */

        pOverlap_and_Add_Buffer_1  = &pFrequency_data[
                                         LONG_WINDOW + 3*SHORT_WINDOW + HALF_SHORT_WINDOW];

        /*
         *  Initialize to zero, only the firt short window used in overlap
         *  and add
         */
        pv_memset(
            pOverlap_and_Add_Buffer_1,
            0,
            SHORT_WINDOW*sizeof(*pOverlap_and_Add_Buffer_1));

        /*
         *  Showt windows are evaluated in decresing order. Windows from 7
         *  to 0 are break down in four cases: window numbers 7 to 5, 4, 3,
         *  and 2 to 0.
         *  The data from short windows 3 and 4 is situated at the boundary
         *  between the 'overlap and add' buffer and the output buffer.
         */
        for (wnd = NUM_SHORT_WINDOWS - 1; wnd >= NUM_SHORT_WINDOWS / 2 + 1; wnd--)
        {

            pFreqInfo = (Int16 *) & pFrequency_data[ wnd*SHORT_WINDOW];

            exp = imdct_fxp(
                      (Int32 *)pFreqInfo,
                      freq_2_time_buffer,
                      SHORT_BLOCK1,
                      Q_format,
                      abs_max_per_window[wnd]);

            /*  W_L_STOP_1 == (LONG_WINDOW - SHORT_WINDOW)>>1 */
            pOverlap_and_Add_Buffer_1 =
                &pFrequency_data[ W_L_STOP_1 + SHORT_WINDOW*wnd];


            pOverlap_and_Add_Buffer_2 =
                pOverlap_and_Add_Buffer_1 + SHORT_WINDOW;

            /*
             *  If all element are zero or if the exponent is bigger than
             *  16 ( it becomes an undefined shift) ->  skip
             */

            if (exp < 16)
            {


                pFreq_2_Time_data_1 = &pFreqInfo[0];
                pFreq_2_Time_data_2 = &pFreqInfo[SHORT_WINDOW];


                /*
                 *  Each of the eight short blocks is windowed separately.
                 *  Window shape decisions are made on a frame-by-frame
                 *  basis.
                 */

                pShort_Window_1 = &Short_Window_fxp[wnd_shape_this_bk][0];

                pShort_Window_2   =
                    &Short_Window_fxp[wnd_shape_this_bk][SHORT_WINDOW_m_1];




                /*
                 * For short windows from 7 to 5
                 *                                      |   =========================
                 *                                      |   |     5     6     7
                 *               _--_  _--_  _--_  _--_ | _-|-_  _--_  _--_  _--_
                 *              /    \/    \/    \/    \|/  |  \/    \/    \/    \
                 *             /     /\    /\    /\    /|\  |  /\    /\    /\     \
                 *            /     /  \  /  \  /  \  / | \ | /  \  /  \  /  \     \
                 *           /     /    \/    \/    \/  |  \|/    \/    \     \     \
                 *      --------------------------------|---[///////////////////////]--------
                 *
                 */


                shift = exp + 15 - SCALING;


                for (i = SHORT_WINDOW; i != 0; i--)
                {
                    Int16 win1, win2;
                    Int16  dat1, dat2;

                    dat2 = *(pFreq_2_Time_data_2++);
                    win2 = *(pShort_Window_2--);
                    temp = *pOverlap_and_Add_Buffer_2;
                    dat1 = *(pFreq_2_Time_data_1++);
                    win1 = *(pShort_Window_1++);

                    *(pOverlap_and_Add_Buffer_2++) =  temp + (fxp_mul_16_by_16(dat2, win2) >> shift);

                    *(pOverlap_and_Add_Buffer_1++)  =  fxp_mul_16_by_16(dat1, win1) >> shift;

                }

            }   /* if (exp < 16) */
            else
            {
                pv_memset(
                    pOverlap_and_Add_Buffer_1,
                    0,
                    SHORT_WINDOW*sizeof(*pOverlap_and_Add_Buffer_1));
            }


        }/* for ( wnd=NUM_SHORT_WINDOWS-1; wnd>=NUM_SHORT_WINDOWS/2; wnd--) */


        wnd = NUM_SHORT_WINDOWS / 2;

        pFreqInfo = (Int16 *) & pFrequency_data[ wnd*SHORT_WINDOW];

        /*
         *  scratch memory is allocated in an unused part of memory
         */


        pScrath_mem = &pFrequency_data[ 2*LONG_WINDOW - HALF_SHORT_WINDOW];

        pOverlap_and_Add_Buffer_1 = &pFrequency_data[ LONG_WINDOW];

        pOverlap_and_Add_Buffer_2 = pOverlap_and_Add_Buffer_1
                                    + HALF_SHORT_WINDOW;


        exp = imdct_fxp(
                  (Int32 *)pFreqInfo,
                  freq_2_time_buffer,
                  SHORT_BLOCK1,
                  Q_format,
                  abs_max_per_window[wnd]);

        /*
         *  If all element are zero or if the exponent is bigger than
         *  16 ( it becomes an undefined shift) ->  skip
         */


        if (exp < 16)
        {

            pFreq_2_Time_data_1 = &pFreqInfo[0];
            pFreq_2_Time_data_2 = &pFreqInfo[SHORT_WINDOW];

            pShort_Window_1 = &Short_Window_fxp[wnd_shape_this_bk][0];

            pShort_Window_2 =
                &Short_Window_fxp[wnd_shape_this_bk][SHORT_WINDOW_m_1];


            /*
             * For short window 4
             *                                    ====|===========
             *                                        |   4
             *                                    |   |   |      |
             *                _--_  _--_  _--_  _-|-_ | _-|-_  _-|-_  _--_  _--_
             *               /    \/    \/    \/  |  \|/  |  \/  |  \/    \/    \
             *              /     /\    /\    /\  |  /|\  |  /\  |  /\    /\     \
             *             /     /  \  /  \  /  \ | / | \ | /  \ | /  \  /  \     \
             *            /     /    \/    \/    \|/  |  \|/    \|/    \/    \     \
             *      ------------------------------[\\\|\\\|//////]-------------------
             *           |                        | A | B |   C  |
             *           |
             *        W_L_STOP_1
             */

            shift = exp + 15 - SCALING;
            {
                Int16 win1;
                Int16  dat1;
                /* -------- segment A ---------------*/
                dat1 = *(pFreq_2_Time_data_1++);
                win1 = *(pShort_Window_1++);
                for (i = HALF_SHORT_WINDOW; i != 0; i--)
                {
                    *(pScrath_mem++)  =  fxp_mul_16_by_16(dat1, win1) >> shift;
                    dat1 = *(pFreq_2_Time_data_1++);
                    win1 = *(pShort_Window_1++);
                }

                /* -------- segment B ---------------*/
                for (i = HALF_SHORT_WINDOW; i != 0; i--)
                {
                    *(pOverlap_and_Add_Buffer_1++)  =  fxp_mul_16_by_16(dat1, win1) >> shift;

                    dat1 = *(pFreq_2_Time_data_1++);
                    win1 = *(pShort_Window_1++);
                }

                /* -------- segment C ---------------*/
                temp = *pOverlap_and_Add_Buffer_2;
                dat1 = *(pFreq_2_Time_data_2++);
                win1 = *(pShort_Window_2--);

                for (i = SHORT_WINDOW; i != 0; i--)
                {
                    *(pOverlap_and_Add_Buffer_2++)  =  temp + (fxp_mul_16_by_16(dat1, win1) >> shift);

                    temp = *pOverlap_and_Add_Buffer_2;
                    dat1 = *(pFreq_2_Time_data_2++);
                    win1 = *(pShort_Window_2--);
                }
            }

        }   /* if (exp < 16) */
        else
        {
            pv_memset(
                pScrath_mem,
                0,
                HALF_SHORT_WINDOW*sizeof(*pScrath_mem));

            pv_memset(
                pOverlap_and_Add_Buffer_1,
                0,
                HALF_SHORT_WINDOW*sizeof(*pOverlap_and_Add_Buffer_1));
        }


        wnd = NUM_SHORT_WINDOWS / 2 - 1;

        pFreqInfo = (Int16 *) & pFrequency_data[ wnd*SHORT_WINDOW];

        pScrath_mem_entry =
            &pFrequency_data[2*LONG_WINDOW - HALF_SHORT_WINDOW - SHORT_WINDOW];


        pScrath_mem = pScrath_mem_entry;

        pOverlap_and_Add_Buffer_1 = &pFrequency_data[ LONG_WINDOW];

        /* point to end of buffer less HALF_SHORT_WINDOW */

        pInterleaved_output_2 = &Interleaved_output[2*(LONG_WINDOW - HALF_SHORT_WINDOW)];
        pInterleaved_output = pInterleaved_output_2;

        pOverlap_and_Add_Buffer_1x = &Time_data[W_L_STOP_1 + SHORT_WINDOW*(wnd+1)];


        exp = imdct_fxp(
                  (Int32 *)pFreqInfo,
                  freq_2_time_buffer,
                  SHORT_BLOCK1,
                  Q_format,
                  abs_max_per_window[wnd]);

        /*
         *  If all element are zero or if the exponent is bigger than
         *  16 ( it becomes an undefined shift) ->  skip
         */

        if (exp < 16)
        {

            pFreq_2_Time_data_1 = &pFreqInfo[0];
            pFreq_2_Time_data_2 = &pFreqInfo[SHORT_WINDOW];


            /*
             * For short window 3
             *                             ===========|====
             *                                    3   |
             *                             |      |   |   |
             *               _--_  _--_  _-|-_  _-|-_ | _-|-_  _--_  _--_  _--_
             *              /    \/    \/  |  \/  |  \|/  |  \/    \/    \/    \
             *             /     /\    /\  |  /\  |  /|\  |  /\    /\    /\     \
             *            /     /  \  /  \ | /  \ | / | \ | /  \  /  \  /  \     \
             *           /     /    \/    \|/    \|/  |  \|/    \/    \     \     \
             *     -----|------------------[\\\\\\|///|///]--------------------------
             *          |                  |   A  | B | C |
             *
             *      W_L_STOP_1
             */


            pShort_Window_1 = &Short_Window_fxp[wnd_shape_this_bk][0];

            pShort_Window_2 =
                &Short_Window_fxp[wnd_shape_this_bk][SHORT_WINDOW_m_1];

            shift = exp + 15 - SCALING;

            Int16 win1;
            Int16  dat1;
            /* -------- segment A ---------------*/
            dat1 = *(pFreq_2_Time_data_1++);
            win1 = *(pShort_Window_1++);
            for (i = SHORT_WINDOW; i != 0; i--)
            {
                *(pScrath_mem++)  =  fxp_mul_16_by_16(dat1, win1) >> shift;
                dat1 = *(pFreq_2_Time_data_1++);
                win1 = *(pShort_Window_1++);
            }

            dat1 = *(pFreq_2_Time_data_2++);
            win1 = *(pShort_Window_2--);


            /* -------- segment B ---------------*/
            for (i = HALF_SHORT_WINDOW; i != 0; i--)
            {
                test = fxp_mul_16_by_16(dat1, win1) >> shift;

                temp =  *(pScrath_mem++) + test;

                test = *(pOverlap_and_Add_Buffer_1x++);
                limiter(*(pInterleaved_output), (temp + test));


                pInterleaved_output += 2;
                dat1 = *(pFreq_2_Time_data_2++);
                win1 = *(pShort_Window_2--);

            }

            /* -------- segment C ---------------*/
            for (i = HALF_SHORT_WINDOW; i != 0; i--)
            {

                temp = fxp_mul_16_by_16(dat1, win1) >> shift;

                *(pOverlap_and_Add_Buffer_1++) += temp;

                dat1 = *(pFreq_2_Time_data_2++);
                win1 = *(pShort_Window_2--);
            }


        }   /* if (exp < 16) */
        else
        {

            pv_memset(
                pScrath_mem,
                0,
                SHORT_WINDOW*sizeof(*pScrath_mem));

            pScrath_mem += SHORT_WINDOW;

            temp = *(pScrath_mem++);
            for (i = HALF_SHORT_WINDOW; i != 0; i--)
            {
                limiter(*(pInterleaved_output), (temp));

                pInterleaved_output += 2;
                temp = *(pScrath_mem++);

            }
        }


        for (wnd = NUM_SHORT_WINDOWS / 2 - 2; wnd >= 0; wnd--)
        {


            pInterleaved_output_2 -= (SHORT_WINDOW * 2);
            pInterleaved_output = pInterleaved_output_2;

            /*
             * The same memory is used as scratch in every iteration
             */
            pScrath_mem = pScrath_mem_entry;

            pOverlap_and_Add_Buffer_2x =
                &Time_data[W_L_STOP_1 + SHORT_WINDOW*(wnd+1)];

            pFreqInfo = (Int16 *) & pFrequency_data[ wnd*SHORT_WINDOW];



            exp = imdct_fxp(
                      (Int32 *)pFreqInfo,
                      freq_2_time_buffer,
                      SHORT_BLOCK1,
                      Q_format,
                      abs_max_per_window[wnd]);

            /*
             *  If all element are zero or if the exponent is bigger than
             *  16 ( it becomes an undefined shift) ->  skip
             */

            if (exp < 16)
            {

                pFreq_2_Time_data_1 = &pFreqInfo[0];
                pFreq_2_Time_data_2 = &pFreqInfo[SHORT_WINDOW];


                /*
                 *  Each of the eight short blocks is windowed separately.
                 *  Window shape decisions are made on a frame-by-frame
                 *  basis.
                 */

                pShort_Window_1 = &Short_Window_fxp[wnd_shape_this_bk][0];

                if (wnd == 0)
                {
                    pShort_Window_1 =
                        &Short_Window_fxp[wnd_shape_prev_bk][0];
                }

                pShort_Window_2   =
                    &Short_Window_fxp[wnd_shape_this_bk][SHORT_WINDOW_m_1];


                /*
                 * For short windows from 2 to 0
                 *
                 *          =========================
                 *                                       |
                 *                0     1     2      |   |
                 *               _--_  _--_  _--_  _-|-_ | _--_  _--_  _--_  _--_
                 *              /    \/    \/    \/  |  \|/    \/    \/    \/    \
                 *             /     /\    /\    /\  |  /|\    /\    /\    /\     \
                 *            /     /  \  /  \  /  \ | / | \  /  \  /  \  /  \     \
                 *           /     /    \/    \/    \|/  |  \/    \/    \     \     \
                 *      ----[\\\\\\\\\\\\\\\\\\\\\\\\]---|-----------------------------
                 *          |
                 *
                 *      W_L_STOP_1
                 */

                shift = exp + 15 - SCALING;

                Int16 dat1 = *(pFreq_2_Time_data_2++);
                Int16 win1 = *(pShort_Window_2--);

                temp  =  *(pScrath_mem);
                for (i = SHORT_WINDOW; i != 0; i--)
                {
                    test  =  fxp_mul_16_by_16(dat1, win1) >> shift;

                    temp += test;
                    dat1 = *(pFreq_2_Time_data_1++);
                    win1 = *(pShort_Window_1++);

                    limiter(*(pInterleaved_output), (temp + *(pOverlap_and_Add_Buffer_2x++)));

                    pInterleaved_output += 2;

                    *(pScrath_mem++) = fxp_mul_16_by_16(dat1, win1) >> shift;
                    dat1 = *(pFreq_2_Time_data_2++);
                    win1 = *(pShort_Window_2--);
                    temp  =  *(pScrath_mem);

                }

            }   /* if (exp < 16) */
            else
            {
                test  = *(pScrath_mem);
                temp  = *(pOverlap_and_Add_Buffer_2x++);

                for (i = SHORT_WINDOW; i != 0; i--)
                {
                    limiter(*(pInterleaved_output), (temp + test));

                    pInterleaved_output += 2;

                    *(pScrath_mem++) = 0;
                    test  =  *(pScrath_mem);
                    temp  = *(pOverlap_and_Add_Buffer_2x++);
                }
            }

        }   /* for ( wnd=NUM_SHORT_WINDOWS/2-1; wnd>=0; wnd--) */

        pOverlap_and_Add_Buffer_2x =  &Time_data[W_L_STOP_1];

        pScrath_mem = pScrath_mem_entry;

        pInterleaved_output_2 -= (SHORT_WINDOW * 2);
        pInterleaved_output    = pInterleaved_output_2;

        test  = *(pScrath_mem++);
        temp  = *(pOverlap_and_Add_Buffer_2x++);

        for (i = SHORT_WINDOW; i != 0; i--)
        {
            limiter(*(pInterleaved_output), (temp + test));

            pInterleaved_output += 2;
            test  = *(pScrath_mem++);
            temp  = *(pOverlap_and_Add_Buffer_2x++);

        }

        pOverlap_and_Add_Buffer_1x = Time_data;

        pInterleaved_output = Interleaved_output;


        temp = *(pOverlap_and_Add_Buffer_1x++);
        for (i = W_L_STOP_1; i != 0; i--)
        {
            limiter(*(pInterleaved_output), temp);

            pInterleaved_output += 2;
            temp = *(pOverlap_and_Add_Buffer_1x++);

        }

        pOverlap_and_Add_Buffer_1x = &Time_data[0];

        pOverlap_and_Add_Buffer_2 = &pFrequency_data[LONG_WINDOW];

        /*
         *  update overlap and add buffer,
         *  so is ready for next iteration
         */

        for (int i = 0; i < W_L_STOP_2; i++)
        {
            temp = *(pOverlap_and_Add_Buffer_2++);
            *(pOverlap_and_Add_Buffer_1x++) = temp;
        }

        pv_memset(
            pOverlap_and_Add_Buffer_1x,
            0,
            W_L_STOP_1*sizeof(*pOverlap_and_Add_Buffer_1x));

    } /* if ( wnd_seq != EIGHT_SHORT_SEQUENCE) */




}   /* trans4m_freq_2_time_fxp */




