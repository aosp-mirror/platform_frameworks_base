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

 Pathname: mdct_fxp.c
 Funtions: fft_rx2

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    data_quant  = Input vector, with quantized Q15 spectral lines:
                  type Int32

    Q_FFTarray  = Scratch memory used for in-place IFFT calculation,
                  min size required 1024, type Int32

    n           = Length of input vector "data_quant". Currently 256 or 2048.
                  type const Int

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    shift = shift factor to reflect scaling introduced by FFT and mdct_fxp,

 Pointers and Buffers Modified:
    calculation are done in-place and returned in "data_quant"

 Local Stores Modified:
     None

 Global Stores Modified:
     None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    The MDCT is a linear orthogonal lapped transform, based on the idea of
    time domain aliasing cancellation (TDAC).
    MDCT is critically sampled, which means that though it is 50% overlapped,
    a sequence data after MDCT has the same number of coefficients as samples
    before the transform (after overlap-and-add). This means, that a single
    block of MDCT data does not correspond to the original block on which the
    MDCT was performed. When subsequent blocks of data are added (still using
    50% overlap), the errors introduced by the transform cancels out.
    Thanks to the overlapping feature, the MDCT is very useful for
    quantization. It effectively removes the otherwise easily detectable
    blocking artifact between transform blocks.
    N = length of input vector X
    X = vector of length N/2, will hold fixed point DCT
    k = 0:1:N-1

                        N-1
            X(m) =  2   SUM   x(k)*cos(pi/(2*N)*(2*k+1+N/2)*(2*m+1))
                        k=0


    The window that completes the TDAC is applied before calling this function.
    The MDCT can be calculated using an FFT, for this, the MDCT needs to be
    rewritten as an odd-time odd-frequency discrete Fourier transform. Thus,
    the MDCT can be calculated using only one n/4 point FFT and some pre and
    post-rotation of the sample points.

    Computation of the MDCT implies computing

        x  = ( y   - y        ) + j( y       +  y       )
         n      2n    N/2-1-2n        N-1-2n     N/2+2n

    using the Fast discrete cosine transform as described in [2]

    where x(n) is an input with N points

    x(n) ----------------------------
                                     |
                                     |
                    Pre-rotation by exp(j(2pi/N)(n+1/8))
                                     |
                                     |
                              N/4- point FFT
                                     |
                                     |
                    Post-rotation by exp(j(2pi/N)(k+1/8))
                                     |
                                     |
                                      ------------- DCT

    By considering the N/2 overlap, a relation between successive input blocks
    is found:

        x   (2n) = x (N/2 + 2n)
         m+1        m
------------------------------------------------------------------------------
 REQUIREMENTS

    This function should provide a fixed point MDCT with an average
    quantization error less than 1 %.

------------------------------------------------------------------------------
 REFERENCES

    [1] Analysis/Synthesis Filter Bank design based on time domain
        aliasing cancellation
        Jhon Princen, et. al.
        IEEE Transactions on ASSP, vol ASSP-34, No. 5 October 1986
        Pg 1153 - 1161

    [2] Regular FFT-related transform kernels for DCT/DST based
        polyphase filterbanks
        Rolf Gluth
        Proc. ICASSP 1991, pg. 2205 - 2208

------------------------------------------------------------------------------
  PSEUDO-CODE

  Cx, Cy are complex number


    exp = log2(n)-1

    FOR ( k=0; k< n/4; k +=2)

        Cx =   (data_quant[3n/4 + k] + data_quant[3n/4 - 1 - k]) +
             j (data_quant[ n/4 + k] - data_quant[ n/4 - 1 - k])

        Q_FFTarray = Cx * exp(-j(2pi/n)(k+1/8))

    ENDFOR

    FOR ( k=n/4; k< n/2; k +=2)

        Cx =   (data_quant[3n/4 - 1 - k] + data_quant[ - n/4 + k]) +
             j (data_quant[5n/4 - 1 - k] - data_quant[   n/4 + k])

        Q_FFTarray = Cx * exp(-j(2pi/n)(k+1/8))

    ENDFOR

    CALL FFT( Q_FFTarray, n/4)

    MODIFYING( Q_FFTarray )

    RETURNING( shift )

    FOR ( k=0; k< n/2; k +=2)

        Cx = Q_FFTarray[ k] + j Q_FFTarray[ k+1]

        Cy = 2 * Cx * exp(-j(2pi/n)(k+1/8))

        data_quant[           k ] = - Real(Cy)
        data_quant[ n/2 - 1 - k ] =   Imag(Cy)
        data_quant[ n/2     + k ] = - Imag(Cy)
        data_quant[ n       - k ] =   Real(Cy)

    ENDFOR

    MODIFIED    data_quant[]

    RETURN      (-shift-1)

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
#include "mdct_fxp.h"
#include "fft_rx4.h"
#include "mix_radix_fft.h"
#include "fwd_long_complex_rot.h"
#include "fwd_short_complex_rot.h"


/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/
#define ERROR_IN_FRAME_SIZE 10


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


Int mdct_fxp(
    Int32   data_quant[],
    Int32   Q_FFTarray[],
    Int     n)
{

    Int32   temp_re;
    Int32   temp_im;

    Int32   temp_re_32;
    Int32   temp_im_32;

    Int16     cos_n;
    Int16     sin_n;
    Int32     exp_jw;
    Int     shift;


    const Int32 *p_rotate;


    Int32   *p_data_1;
    Int32   *p_data_2;
    Int32   *p_data_3;
    Int32   *p_data_4;

    Int32 *p_Q_FFTarray;

    Int32   max1;

    Int k;
    Int n_2   = n >> 1;
    Int n_4   = n >> 2;
    Int n_8   = n >> 3;
    Int n_3_4 = 3 * n_4;

    switch (n)
    {
        case SHORT_WINDOW_TYPE:
            p_rotate = (Int32 *)exp_rotation_N_256;
            break;

        case LONG_WINDOW_TYPE:
            p_rotate = (Int32 *)exp_rotation_N_2048;
            break;

        default:
            /*
             *  There is no defined behavior for a non supported frame
             *  size. By returning a fixed scaling factor, the input will
             *  scaled down and this will be heard as a low level noise
             */
            return(ERROR_IN_FRAME_SIZE);

    }

    /*--- Reordering and Pre-rotation by exp(-j(2pi/N)(r+1/8))   */
    p_data_1 = &data_quant[n_3_4];
    p_data_2 = &data_quant[n_3_4 - 1];
    p_data_3 = &data_quant[n_4];
    p_data_4 = &data_quant[n_4 - 1];

    p_Q_FFTarray = Q_FFTarray;

    max1 = 0;

    for (k = n_8; k > 0; k--)
    {
        /*
         *  scale down to ensure numbers are Q15
         *  temp_re and temp_im are 32-bit but
         *  only the lower 16 bits are used
         */

        temp_re = (*(p_data_1++) + *(p_data_2--)) >> 1;
        temp_im = (*(p_data_3++) - *(p_data_4--)) >> 1;


        /*
         * cos_n + j*sin_n == exp(j(2pi/N)(k+1/8))
         */

        exp_jw = *p_rotate++;

        cos_n = (Int16)(exp_jw >> 16);
        sin_n = (Int16)(exp_jw & 0xFFFF);

        temp_re_32 = temp_re * cos_n + temp_im * sin_n;
        temp_im_32 = temp_im * cos_n - temp_re * sin_n;
        *(p_Q_FFTarray++) = temp_re_32;
        *(p_Q_FFTarray++) = temp_im_32;
        max1         |= (temp_re_32 >> 31) ^ temp_re_32;
        max1         |= (temp_im_32 >> 31) ^ temp_im_32;


        p_data_1++;
        p_data_2--;
        p_data_4--;
        p_data_3++;
    }


    p_data_1 = &data_quant[n - 1];
    p_data_2 = &data_quant[n_2 - 1];
    p_data_3 = &data_quant[n_2];
    p_data_4 =  data_quant;

    for (k = n_8; k > 0; k--)
    {
        /*
         *  scale down to ensure numbers are Q15
         */
        temp_re = (*(p_data_2--) - *(p_data_4++)) >> 1;
        temp_im = (*(p_data_1--) + *(p_data_3++)) >> 1;

        p_data_2--;
        p_data_1--;
        p_data_4++;
        p_data_3++;

        /*
         * cos_n + j*sin_n == exp(j(2pi/N)(k+1/8))
         */

        exp_jw = *p_rotate++;

        cos_n = (Int16)(exp_jw >> 16);
        sin_n = (Int16)(exp_jw & 0xFFFF);

        temp_re_32 = temp_re * cos_n + temp_im * sin_n;
        temp_im_32 = temp_im * cos_n - temp_re * sin_n;

        *(p_Q_FFTarray++) = temp_re_32;
        *(p_Q_FFTarray++) = temp_im_32;
        max1         |= (temp_re_32 >> 31) ^ temp_re_32;
        max1         |= (temp_im_32 >> 31) ^ temp_im_32;


    } /* for(k) */



    p_Q_FFTarray = Q_FFTarray;

    if (max1)
    {

        if (n != SHORT_WINDOW_TYPE)
        {

            shift = mix_radix_fft(
                        Q_FFTarray,
                        &max1);

            shift += fwd_long_complex_rot(
                         Q_FFTarray,
                         data_quant,
                         max1);

        }
        else        /*  n_4 is 64 */
        {

            shift = fft_rx4_short(
                        Q_FFTarray,
                        &max1);

            shift += fwd_short_complex_rot(
                         Q_FFTarray,
                         data_quant,
                         max1);
        }

    }
    else
    {
        shift = -31;
    }

    /*
     *  returns shift introduced by FFT and mdct_fxp, 12 accounts for
     *  regular downshift (14) and MDCT scale factor (-2)
     *  number are returned as 16 bits
     */
    return (12 - shift);

} /* mdct_fxp */

