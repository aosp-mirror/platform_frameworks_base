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

 Pathname: ./src/esc_iquant_scaling.c
 Funtions:  esc_iquant_scaling

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Modified from esc_iquant_fxp.c code

 Description:  Eliminated unused variables to avoid warnings, changed header

 Who:                                   Date:
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    quantSpec[] = array of quantized compressed spectral coefficients, of
                  data type Int and length sfbWidth.

    sfbWidth    = number of array elements in quantSpec and the output array
                  coef, data type Int.

    coef[]      = output array of uncompressed coefficients, stored in a
                  variable Q format, depending on the maximum value found
                  for the group, array of Int32, length sfbWdith to be
                  overwritten.

    QFormat     = the output Q format for the array coef[].


    scale       = scaling factor after separating power of 2 factor out from
                  0.25*(sfb_scale - 100), i.e., 0.25*sfb_scale.

    maxInput    = maximum absolute value of quantSpec.

 Local Stores/Buffers/Pointers Needed: None.

 Global Stores/Buffers/Pointers Needed:
    inverseQuantTable = lookup table of const integer values to the one third
                power stored in Q27 format, in file iquant_table.c, const
                array of UInt32, of size 1025.

 Outputs: None

 Pointers and Buffers Modified:
    coef[] contents are overwritten with the uncompressed values from
    quantSpec[]




 Local Stores Modified: None.

 Global Stores Modified: None.

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function performs the inverse quantization of the spectral coeficients
 read from huffman decoding. It takes each input array value to the four
 thirds power, then scales it according to the scaling factor input argument
 ,and stores the result in the output array in a variable Q format
 depending upon the maximum input value found.

------------------------------------------------------------------------------
 REQUIREMENTS

 This function shall not have static or global variables.

------------------------------------------------------------------------------
 REFERENCES

 (1) ISO/IEC 13818-7:1997 Titled "Information technology - Generic coding
   of moving pictures and associated audio information - Part 7: Advanced
   Audio Coding (AAC)", Section 10.3, "Decoding process", page 43.

 (2) MPEG-2 NBC Audio Decoder
   "This software module was originally developed by AT&T, Dolby
   Laboratories, Fraunhofer Gesellschaft IIS in the course of development
   of the MPEG-2 NBC/MPEG-4 Audio standard ISO/IEC 13818-7, 14496-1,2 and
   3. This software module is an implementation of a part of one or more
   MPEG-2 NBC/MPEG-4 Audio tools as specified by the MPEG-2 NBC/MPEG-4
   Audio standard. ISO/IEC gives users of the MPEG-2 NBC/MPEG-4 Audio
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

------------------------------------------------------------------------------
 PSEUDO-CODE

    maxInput = 0;

    FOR (i = sfbWidth - 1; i >= 0; i--)
        x = quantSpec[i];

        IF ( x >= 0)
            absX = x;
        ELSE
            absX = -x;
        ENDIF

        coef[i] = absX;

        IF (absX > maxInput)
            maxInput = absX;
        ENDIF
    ENDFOR

    IF (maxInput == 0)
        *pQFormat = QTABLE;
    ELSE
        temp = inverseQuantTable[(maxInput >> ORDER) + 1];

        temp += ((1 << (QTABLE))-1);

        temp >>= (QTABLE-1);

        temp *= maxInput;

        binaryDigits = 0;
        WHILE( temp != 0)
            temp >>= 1;
            binaryDigits++;
        WEND

        IF (binaryDigits < (SIGNED32BITS - QTABLE))
            binaryDigits = SIGNED32BITS - QTABLE;
        ENDIF

        *pQFormat = SIGNED32BITS - binaryDigits;
        shift = QTABLE - *pQFormat;

        IF (maxInput < TABLESIZE)
            FOR (i = sfbWidth - 1; i >= 0; i--)
                x = quantSpec[i];

                absX = coef[i];

                tmp_coef = x * (inverseQuantTable[absX] >> shift);

                b_low  = (tmp_coef & 0xFFFF);
                b_high = (tmp_coef >> 16);

                mult_low  = ( (UInt32) b_low * scale );
                mult_high = ( (Int32) b_high * scale );

                mult_low >>= 16;

                coef[i]  = (Int32) (mult_high + mult_low);

            ENDFOR
        ELSE
            FOR (i = sfbWidth; i >= 0 ; i--)
                x    = quantSpec[i];
                absX = coef[i];

                IF (absX < TABLESIZE)
                    tmp_coef = x * (inverseQuantTable[absX] >> shift);
                ELSE
                    index = absX >> ORDER;
                    w1 = inverseQuantTable[index];

                    approxOneThird = (w1 * FACTOR) >> shift;


                    x1 = index * SPACING;
                    w2 = inverseQuantTable[index+1];

                    deltaOneThird = (w2 - w1) * (absX - x1);

                    deltaOneThird >>= (shift + ORDER - 1);

                    tmp_coef = x * (approxOneThird + deltaOneThird);

                ENDIF

                b_low  = (mult_high & 0xFFFF);
                b_high = (mult_high >> 16);

                mult_low  = ( (UInt32) b_low * scale );
                mult_high = ( (Int32) b_high * scale );

                mult_low >>= 16;

                coef[i]  = (Int32) (mult_high + mult_low);

            ENDFOR
        ENDIF
    ENDIF

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
#include "iquant_table.h"
#include "esc_iquant_scaling.h"
#include "aac_mem_funcs.h"         /* For pv_memset                         */

#include "fxp_mul32.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/
/*
 * Read further on what order is.
 * Note: If ORDER is not a multiple of 3, FACTOR is not an integer.
 * Note: Portions of this function assume ORDER is 3, and so does the table
 *       in iquant_table.c
 */
#define ORDER        (3)
/*
 * For input values > TABLESIZE, multiply by FACTOR to get x ^ (1/3)
 * FACTOR = 2 ^ (ORDER/3)
 */
#define FACTOR       (2)

/*
 * This is one more than the range of expected inputs.
 */
#define INPUTRANGE   (8192)

/*
 * SPACING is 2 ^ ORDER, and is the spacing between points when in the
 * interpolation range.
 */
#define SPACING      (1<<ORDER)

/*
 * The actual table size is one more than TABLESIZE, to allow for
 * interpolation for numbers near 8191
 */
#define TABLESIZE    (INPUTRANGE/SPACING)

/*
 * Format the table is stored in.
 */
#define QTABLE       (27)

/*
 * Number of bits for data in a signed 32 bit integer.
 */
#define SIGNED32BITS  (31)

/*
 * Round up value for intermediate values obtained from the table
 */
#define ROUND_UP (( ((UInt32) 1) << (QTABLE) )-1)

#define     MASK_LOW16  0xffff
#define     UPPER16     16

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

/*
 * Processing in this function is performed in these steps:
 *
 * 1) Find the overall Q format for the entire group of inputs. This consists
 *    of:
 *    a) Finding the maximum input
 *    b) estimate the maximum output
 *    c) Using the table, get max ^ (4/3), taking into account the table is
 *       in q format.
 * 2) For each array element, see if the value is directly inside the table.
 *    a) If yes, just multiply by table value by itself, then shift as
 *       appropriate.
 *    b) If no, get an approximation (described below) for x ^ (1/3) by linearly
 *       interpolating using lower values in the table, then multiply by a
 *       correction factor, then multiply by x (see below).
 *
 * It more accurate to interpolate x ^ (1/3) then x ^ (4/3), so that is stored
 * in the lookup table. For values not in the table, interpolation is used:
 *
 *  We want y = x ^ (4/3) = x * (x ^ (1/3))
 *
 *  Let     x = w * (2 ^ m)  where m is a constant, = ORDER
 *
 *  then     x ^ (1/3) = w ^ (1/3) * (2 ^ (m/3))
 *
 *  w is most likely not an integer, so an interpolation with floor(w) and
 *  ceil(w) can be performed to approximate w ^ (1/3) by getting values out of
 *  the table. Then to get x ^ (1/3), multiply by FACTOR. If m = 0, 3, 6,
 *  then FACTOR is a simple power of 2, so a shift can do the job.
 *
 *  The actual code employs some more tricks to speed things up, and because
 *  the table is stored in Q format.
 *
 *  Rather than saving the sign of each input, the unsigned value of
 *  abs(x) ^ (1/3) is multiplied by the signed input value.
 */



#if ( defined(_ARM) || defined(_ARM_V4))

/*
 *  Absolute value for 16 bit-numbers
 */
__inline Int32 abs2(Int32 x)
{
    Int32 z;
    /*
        z = x - (x<0);
        x = z ^ sign(z)
     */
    __asm
    {
        sub  z, x, x, lsr #31
        eor  x, z, z, asr #31
    }
    return (x);
}


#define pv_abs(x)   abs2(x)


#elif (defined(PV_ARM_GCC_V5)||defined(PV_ARM_GCC_V4))

/*
 *  Absolute value for 16 bit-numbers
 */
__inline Int32 abs2(Int32 x)
{
    register Int32 z;
    register Int32 y;
    register Int32 ra = x;
    asm volatile(
        "sub  %0, %2, %2, lsr #31\n\t"
        "eor  %1, %0, %0, asr #31"
    : "=&r*i"(z),
        "=&r*i"(y)
                : "r"(ra));

    return (y);
}

#define pv_abs(x)   abs2(x)


#else

#define pv_abs(x)   ((x) > 0)? (x) : (-x)

#endif





void esc_iquant_scaling(
    const Int16     quantSpec[],
    Int32         coef[],
    const Int     sfbWidth,
    Int const      QFormat,
    UInt16        scale,
    Int           maxInput)
{
    Int    i;
    Int    x;
    Int    y;
    Int    index;
    Int    shift;
    UInt   absX;
    UInt32 w1, w2;
    UInt32 deltaOneThird;
    UInt32 x1;
    UInt32 approxOneThird;
    Int32   mult_high;


#if ( defined(_ARM) || defined(_ARM_V4))

    {
        Int32   *temp;
        Int32   R12, R11, R10, R9;

        deltaOneThird = sizeof(Int32) * sfbWidth;
        temp = coef;

        // from standard library call for __rt_memset
        __asm
        {
            MOV     R12, #0x0
            MOV     R11, #0x0
            MOV     R10, #0x0
            MOV     R9, #0x0
            SUBS    deltaOneThird, deltaOneThird, #0x20
loop:
            STMCSIA temp!, {R12, R11, R10, R9}
            STMCSIA temp!, {R12, R11, R10, R9}
            SUBCSS  deltaOneThird, deltaOneThird, #0x20
            BCS     loop

            MOVS    deltaOneThird, deltaOneThird, LSL #28
            STMCSIA temp!, {R12, R11, R10, R9}
            STMMIIA temp!, {R12, R11}
        }
    }

#else
    pv_memset(coef, 0, sizeof(Int32) * sfbWidth);
#endif

    if (maxInput > 0)
    {

        shift = QTABLE - QFormat;

        if (scale != 0)
        {
            if (maxInput < TABLESIZE)
            {

                for (i = sfbWidth - 1; i >= 0; i -= 4)
                {
                    x = quantSpec[i];
                    y = quantSpec[i-1];
                    if (x)
                    {
                        absX = pv_abs(x);
                        mult_high = (x * (inverseQuantTable[absX] >> shift));
                        coef[i] = fxp_mul32_by_16(mult_high, scale) << 1;
                    }

                    if (y)
                    {
                        absX = pv_abs(y);
                        mult_high = y * (inverseQuantTable[absX] >> shift);
                        coef[i-1] = fxp_mul32_by_16(mult_high, scale) << 1;
                    }

                    x = quantSpec[i-2];
                    y = quantSpec[i-3];
                    if (x)
                    {
                        absX = pv_abs(x);
                        mult_high = x * (inverseQuantTable[absX] >> shift);
                        coef[i-2] = fxp_mul32_by_16(mult_high, scale) << 1;
                    }

                    if (y)
                    {
                        absX = pv_abs(y);
                        mult_high = y * (inverseQuantTable[absX] >> shift);
                        coef[i-3] = fxp_mul32_by_16(mult_high, scale) << 1;
                    }
                } /* end for (i = sfbWidth - 1; i >= 0; i--) */

            } /* end if (maxInput < TABLESIZE)*/

            else /* maxInput >= TABLESIZE) */
            {
                for (i = sfbWidth - 1; i >= 0; i -= 4)
                {
                    x    = quantSpec[i];
                    if (x)
                    {
                        absX = pv_abs(x);
                        if (absX < TABLESIZE)
                        {
                            mult_high = x * (inverseQuantTable[absX] >> shift);
                            coef[i] = fxp_mul32_by_16(mult_high, scale) << 1;

                        }
                        else
                        {
                            index = absX >> ORDER;
                            w1 = inverseQuantTable[index];
                            w2 = inverseQuantTable[index+1];
                            approxOneThird = (w1 * FACTOR) >> shift;
                            x1 = index << ORDER;
                            deltaOneThird = (w2 - w1) * (absX - x1);
                            deltaOneThird >>= (shift + 2);
                            mult_high = x * (approxOneThird + deltaOneThird);
                            coef[i] = fxp_mul32_by_16(mult_high, scale) << 1;

                        }
                    } /* if(x) */


                    x    = quantSpec[i-1];
                    if (x)
                    {
                        absX = pv_abs(x);
                        if (absX < TABLESIZE)
                        {
                            mult_high = (x * (inverseQuantTable[absX] >> shift));
                            coef[i-1] = fxp_mul32_by_16(mult_high, scale) << 1;

                        }
                        else
                        {
                            index = absX >> ORDER;
                            w1 = inverseQuantTable[index];
                            w2 = inverseQuantTable[index+1];
                            approxOneThird = (w1 * FACTOR) >> shift;
                            x1 = index << ORDER;
                            deltaOneThird = (w2 - w1) * (absX - x1);
                            deltaOneThird >>= (shift + 2);
                            mult_high = x * (approxOneThird + deltaOneThird);
                            coef[i-1] = fxp_mul32_by_16(mult_high, scale) << 1;
                        }
                    } /* if(x) */

                    x    = quantSpec[i-2];
                    if (x)
                    {
                        absX = pv_abs(x);
                        if (absX < TABLESIZE)
                        {
                            mult_high = x * (inverseQuantTable[absX] >> shift);
                            coef[i-2] = fxp_mul32_by_16(mult_high, scale) << 1;
                        }
                        else
                        {
                            index = absX >> ORDER;
                            w1 = inverseQuantTable[index];
                            w2 = inverseQuantTable[index+1];
                            approxOneThird = (w1 * FACTOR) >> shift;
                            x1 = index << ORDER;
                            deltaOneThird = (w2 - w1) * (absX - x1);
                            deltaOneThird >>= (shift + 2);
                            mult_high = x * (approxOneThird + deltaOneThird);
                            coef[i-2] = fxp_mul32_by_16(mult_high, scale) << 1;
                        }
                    } /* if(x) */

                    x    = quantSpec[i-3];
                    if (x)
                    {
                        absX = pv_abs(x);
                        if (absX < TABLESIZE)
                        {
                            mult_high = x * (inverseQuantTable[absX] >> shift);
                            coef[i-3] = fxp_mul32_by_16(mult_high, scale) << 1;

                        }
                        else
                        {
                            index = absX >> ORDER;
                            w1 = inverseQuantTable[index];
                            w2 = inverseQuantTable[index+1];
                            approxOneThird = (w1 * FACTOR) >> shift;
                            x1 = index << ORDER;
                            deltaOneThird = (w2 - w1) * (absX - x1);
                            deltaOneThird >>= (shift + 2);
                            mult_high = x * (approxOneThird + deltaOneThird);
                            coef[i-3] = fxp_mul32_by_16(mult_high, scale) << 1;

                        }
                    } /* if(x) */

                }  /* end for (i = sfbWidth - 1; i >= 0; i--) */
            } /* end else for if (maxInput < TABLESIZE)*/
        }
        else /* scale == 0 */
        {
            if (maxInput < TABLESIZE)
            {
                for (i = sfbWidth - 1; i >= 0; i -= 4)
                {
                    x = quantSpec[i];
                    y = quantSpec[i-1];
                    if (x)
                    {
                        absX = pv_abs(x);
                        mult_high = x * (inverseQuantTable[absX] >> shift);
                        coef[i] = mult_high >> 1;
                    }

                    if (y)
                    {
                        absX = pv_abs(y);
                        mult_high = y * (inverseQuantTable[absX] >> shift);
                        coef[i-1] = mult_high >> 1;
                    }

                    x = quantSpec[i-2];
                    y = quantSpec[i-3];
                    if (x)
                    {
                        absX = pv_abs(x);
                        mult_high = x * (inverseQuantTable[absX] >> shift);
                        coef[i-2] = mult_high >> 1;
                    }

                    if (y)
                    {
                        absX = pv_abs(y);
                        mult_high = y * (inverseQuantTable[absX] >> shift);
                        coef[i-3] = mult_high >> 1;
                    }
                }

            } /* end if (maxInput < TABLESIZE)*/

            else /* maxInput >= TABLESIZE) */
            {
                for (i = sfbWidth - 1; i >= 0; i -= 4)
                {
                    x    = quantSpec[i];
                    if (x)
                    {
                        absX = pv_abs(x);
                        if (absX < TABLESIZE)
                        {
                            mult_high = x * (inverseQuantTable[absX] >> shift);
                            coef[i] = (mult_high >> 1);
                        } /* end if (absX < TABLESIZE) */
                        else
                        {
                            index = absX >> ORDER;
                            w1 = inverseQuantTable[index];
                            w2 = inverseQuantTable[index+1];
                            approxOneThird = (w1 * FACTOR) >> shift;
                            x1 = index << ORDER;
                            deltaOneThird = (w2 - w1) * (absX - x1);
                            deltaOneThird >>= (shift + 2);
                            mult_high = x * (approxOneThird + deltaOneThird);
                            coef[i] = (mult_high >> 1);
                        }
                    } /* if(x) */

                    x    = quantSpec[i-1];
                    if (x)
                    {
                        absX = pv_abs(x);
                        if (absX < TABLESIZE)
                        {
                            mult_high = x * (inverseQuantTable[absX] >> shift);
                            coef[i-1] = (mult_high >> 1);
                        } /* end if (absX < TABLESIZE) */
                        else
                        {
                            index = absX >> ORDER;
                            w1 = inverseQuantTable[index];
                            w2 = inverseQuantTable[index+1];
                            approxOneThird = (w1 * FACTOR) >> shift;
                            x1 = index << ORDER;
                            deltaOneThird = (w2 - w1) * (absX - x1);
                            deltaOneThird >>= (shift + 2);
                            mult_high = x * (approxOneThird + deltaOneThird);
                            coef[i-1] = (mult_high >> 1);
                        }
                    } /* if(x) */

                    x    = quantSpec[i-2];
                    if (x)
                    {
                        absX = pv_abs(x);
                        if (absX < TABLESIZE)
                        {
                            mult_high = x * (inverseQuantTable[absX] >> shift);
                            coef[i-2] = (mult_high >> 1);
                        } /* end if (absX < TABLESIZE) */
                        else
                        {
                            index = absX >> ORDER;
                            w1 = inverseQuantTable[index];
                            w2 = inverseQuantTable[index+1];
                            approxOneThird = (w1 * FACTOR) >> shift;
                            x1 = index << ORDER;
                            deltaOneThird = (w2 - w1) * (absX - x1);
                            deltaOneThird >>= (shift + 2);
                            mult_high = x * (approxOneThird + deltaOneThird);
                            coef[i-2] = (mult_high >> 1);
                        }
                    } /* if(x) */

                    x    = quantSpec[i-3];
                    if (x)
                    {
                        absX = pv_abs(x);
                        if (absX < TABLESIZE)
                        {
                            mult_high = x * (inverseQuantTable[absX] >> shift);
                            coef[i-3] = (mult_high >> 1);
                        } /* end if (absX < TABLESIZE) */
                        else
                        {
                            index = absX >> ORDER;
                            w1 = inverseQuantTable[index];
                            w2 = inverseQuantTable[index+1];
                            approxOneThird = (w1 * FACTOR) >> shift;
                            x1 = index << ORDER;
                            deltaOneThird = (w2 - w1) * (absX - x1);
                            deltaOneThird >>= (shift + 2);
                            mult_high = x * (approxOneThird + deltaOneThird);
                            coef[i-3] = (mult_high >> 1);
                        }

                    } /* if(x) */

                }  /* end for (i = sfbWidth - 1; i >= 0; i--) */

            } /* end else for if (maxInput < TABLESIZE)*/

        } /* end else for if(scale!=0) */

    }  /* end else for if(maxInput == 0) */

} /* end esc_iquant_fxp */


