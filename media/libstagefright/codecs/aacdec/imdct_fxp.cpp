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

 Pathname: imdct_fxp.c
 Funtions: imdct_fxp

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:

    data_quant    = Input vector, with quantized spectral lines:
                    type Int32

    freq_2_time_buffer =  Scratch memory used for in-place FFT calculation,
                    min size required 1024,
                    type Int32

    n            =  Length of input vector "data_quant". Currently 256 or 2048
                    type const Int

    Q_format     =  Q_format of the input vector "data_quant"
                    type Int

    max          =  Maximum value inside input vector "data_quant"
                    type Int32

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    shift = shift factor to reflect scaling introduced by IFFT and imdct_fxp,

 Pointers and Buffers Modified:
    Results are return in "Data_Int_precision"

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    The IMDCT is a linear orthogonal lapped transform, based on the idea of
    time domain aliasing cancellation (TDAC).
    IMDCT is critically sampled, which means that though it is 50% overlapped,
    a sequence data after IMDCT has the same number of coefficients as samples
    before the transform (after overlap-and-add). This means, that a single
    block of IMDCT data does not correspond to the original block on which the
    IMDCT was performed. When subsequent blocks of inverse transformed data
    are added (still using 50% overlap), the errors introduced by the
    transform cancels out.Thanks to the overlapping feature, the IMDCT is very
    useful for quantization. It effectively removes the otherwise easily
    detectable blocking artifact between transform blocks.

    N = twice the length of input vector X
    y = vector of length N, will hold fixed point IDCT
    p = 0:1:N-1

                    2   N/2-1
            y(p) = ---   SUM   X(m)*cos(pi/(2*N)*(2*p+1+N/2)*(2*m+1))
                    N    m=0

    The window that completes the TDAC is applied before calling this function.
    The IMDCT can be calculated using an IFFT, for this, the IMDCT need be
    rewritten as an odd-time odd-frequency discrete Fourier transform. Thus,
    the IMDCT can be calculated using only one n/4 point FFT and some pre and
    post-rotation of the sample points.


    where X(k) is the input with N frequency lines

    X(k) ----------------------------
                                     |
                                     |
                    Pre-rotation by exp(j(2pi/N)(k+1/8))
                                     |
                                     |
                              N/4- point IFFT
                                     |
                                     |
                    Post-rotation by exp(j(2pi/N)(n+1/8))
                                     |
                                     |
                                      ------------- x(n)  In the time domain


------------------------------------------------------------------------------
 REQUIREMENTS

    This function should provide a fixed point IMDCT with an average
    quantization error less than 1 % (variance and mean).

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

    FOR ( k=0; k< n/2; k +=2)

        Cx = - data_quant[k] + j data_quant[n/2-1 - k]

        freq_2_time_buffer = Cx * exp(j(2pi/n)(k+1/8))

    ENDFOR

    CALL IFFT( freq_2_time_buffer, n/4)

    MODIFYING( freq_2_time_buffer )

    RETURNING( shift )

    FOR ( k=0; k< n/4; k +=2)

        Cx = freq_2_time_buffer[ k] + j freq_2_time_buffer[ k+1]

        Cy = Cx * exp(j(2pi/n)(k+1/8))

        data_quant[3n/4-1 - k ] =   Real(Cy)
        data_quant[ n/4-1 - k ] = - Imag(Cy)
        data_quant[3n/4   + k ] =   Real(Cy)
        data_quant[ n/4   + k ] =   Imag(Cy)

    ENDFOR

    FOR ( k=n/4; k< n/2; k +=2)

        Cx = freq_2_time_buffer[ k] + j freq_2_time_buffer[ k+1]

        Cy = Cx * exp(j(2pi/n)(k+1/8))

        data_quant[3n/4-1 - k ] =   Real(Cy)
        data_quant[ n/4   + k ] = - Real(Cy)
        data_quant[5n/4   - k ] =   Imag(Cy)
        data_quant[ n/4   + k ] =   Imag(Cy)

    ENDFOR

    MODIFIED    data_quant[]

    RETURN      (exp - shift)

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
#include "imdct_fxp.h"


#include "mix_radix_fft.h"
#include "digit_reversal_tables.h"
#include "fft_rx4.h"
#include "inv_short_complex_rot.h"
#include "inv_long_complex_rot.h"
#include "pv_normalize.h"
#include "fxp_mul32.h"
#include "aac_mem_funcs.h"

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


Int imdct_fxp(Int32   data_quant[],
              Int32   freq_2_time_buffer[],
              const   Int     n,
              Int     Q_format,
              Int32   max)
{

    Int32     exp_jw;
    Int     shift = 0;

    const   Int32 *p_rotate;
    const   Int32 *p_rotate_2;

    Int32   *p_data_1;
    Int32   *p_data_2;

    Int32   temp_re32;
    Int32   temp_im32;

    Int     shift1 = 0;
    Int32   temp1;
    Int32   temp2;

    Int     k;
    Int     n_2   = n >> 1;
    Int     n_4   = n >> 2;



    if (max != 0)
    {

        switch (n)
        {
            case SHORT_WINDOW_TYPE:
                p_rotate = exp_rotation_N_256;
                shift = 21;           /* log2(n)-1 + 14 acomodates 2/N factor */
                break;

            case LONG_WINDOW_TYPE:
                p_rotate = exp_rotation_N_2048;
                shift = 24;           /* log2(n)-1 +14 acomodates 2/N factor */
                break;

            default:
                /*
                 * There is no defined behavior for a non supported frame
                 * size. By returning a fixed scaling factor, the input will
                 * scaled down and the will be heard as a low level noise
                 */
                return(ERROR_IN_FRAME_SIZE);

        }

        /*
         *   p_data_1                                        p_data_2
         *       |                                            |
         *       RIRIRIRIRIRIRIRIRIRIRIRIRIRIRI....RIRIRIRIRIRI
         *        |                                          |
         *
         */

        p_data_1 =  data_quant;             /* uses first  half of buffer */
        p_data_2 = &data_quant[n_2 - 1];    /* uses second half of buffer */

        p_rotate_2 = &p_rotate[n_4-1];

        shift1 = pv_normalize(max) - 1;     /* -1 to leave room for addition */
        Q_format -= (16 - shift1);
        max = 0;


        if (shift1 >= 0)
        {
            temp_re32 =   *(p_data_1++) << shift1;
            temp_im32 =   *(p_data_2--) << shift1;

            for (k = n_4 >> 1; k != 0; k--)
            {
                /*
                 *  Real and Imag parts have been swaped to use FFT as IFFT
                 */
                /*
                 * cos_n + j*sin_n == exp(j(2pi/N)(k+1/8))
                 */
                exp_jw = *p_rotate++;

                temp1      =  cmplx_mul32_by_16(temp_im32, -temp_re32, exp_jw);
                temp2      = -cmplx_mul32_by_16(temp_re32,  temp_im32, exp_jw);

                temp_im32 =   *(p_data_1--) << shift1;
                temp_re32 =   *(p_data_2--) << shift1;
                *(p_data_1++) = temp1;
                *(p_data_1++) = temp2;
                max         |= (temp1 >> 31) ^ temp1;
                max         |= (temp2 >> 31) ^ temp2;


                /*
                 *  Real and Imag parts have been swaped to use FFT as IFFT
                 */

                /*
                 * cos_n + j*sin_n == exp(j(2pi/N)(k+1/8))
                 */

                exp_jw = *p_rotate_2--;

                temp1      =  cmplx_mul32_by_16(temp_im32, -temp_re32, exp_jw);
                temp2      = -cmplx_mul32_by_16(temp_re32,  temp_im32, exp_jw);


                temp_re32 =   *(p_data_1++) << shift1;
                temp_im32 =   *(p_data_2--) << shift1;

                *(p_data_2 + 2) = temp1;
                *(p_data_2 + 3) = temp2;
                max         |= (temp1 >> 31) ^ temp1;
                max         |= (temp2 >> 31) ^ temp2;

            }
        }
        else
        {
            temp_re32 =   *(p_data_1++) >> 1;
            temp_im32 =   *(p_data_2--) >> 1;

            for (k = n_4 >> 1; k != 0; k--)
            {
                /*
                 *  Real and Imag parts have been swaped to use FFT as IFFT
                 */
                /*
                 * cos_n + j*sin_n == exp(j(2pi/N)(k+1/8))
                 */
                exp_jw = *p_rotate++;

                temp1      =  cmplx_mul32_by_16(temp_im32, -temp_re32, exp_jw);
                temp2      = -cmplx_mul32_by_16(temp_re32,  temp_im32, exp_jw);

                temp_im32 =   *(p_data_1--) >> 1;
                temp_re32 =   *(p_data_2--) >> 1;
                *(p_data_1++) = temp1;
                *(p_data_1++) = temp2;

                max         |= (temp1 >> 31) ^ temp1;
                max         |= (temp2 >> 31) ^ temp2;


                /*
                 *  Real and Imag parts have been swaped to use FFT as IFFT
                 */

                /*
                 * cos_n + j*sin_n == exp(j(2pi/N)(k+1/8))
                 */
                exp_jw = *p_rotate_2--;

                temp1      =  cmplx_mul32_by_16(temp_im32, -temp_re32, exp_jw);
                temp2      = -cmplx_mul32_by_16(temp_re32,  temp_im32, exp_jw);

                temp_re32 =   *(p_data_1++) >> 1;
                temp_im32 =   *(p_data_2--) >> 1;

                *(p_data_2 + 3) = temp2;
                *(p_data_2 + 2) = temp1;

                max         |= (temp1 >> 31) ^ temp1;
                max         |= (temp2 >> 31) ^ temp2;

            }
        }


        if (n != SHORT_WINDOW_TYPE)
        {

            shift -= mix_radix_fft(data_quant,
                                   &max);

            shift -= inv_long_complex_rot(data_quant,
                                          max);

        }
        else        /*  n_4 is 64 */
        {

            shift -= fft_rx4_short(data_quant,   &max);


            shift -= inv_short_complex_rot(data_quant,
                                           freq_2_time_buffer,
                                           max);

            pv_memcpy(data_quant,
                      freq_2_time_buffer,
                      SHORT_WINDOW*sizeof(*data_quant));
        }

    }
    else
    {
        Q_format = ALL_ZEROS_BUFFER;
    }

    return(shift + Q_format);

} /* imdct_fxp */
