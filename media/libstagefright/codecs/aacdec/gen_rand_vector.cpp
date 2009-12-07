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
 REVISION HISTORY

 Description:  Modified from original shareware code

 Description:  Modified to remove instances of pow() and sqrt(), and
 optimized for inclusion in fixed-point version of decoder.

 Description:  Modified to include comments/optimizations from code review.
 Also, declared appropriate variables as type "const"

 Description:  Adopted strategy of "one q-format per sfb" strategy, which
 eliminated the array q-format from this function.  The q-format the
 random vector is stored in is now returned from the function.

 Description:  Completely redesigned the routine to allow a simplified
        calculation of the adjusted noise, by eliminating the dependency
        on the band_length. Added polynomial approximation for the
        function 1/sqrt(power). Updated comments and pseudo-code

 Description:  Modified function description, pseudocode, etc.

 Description:
    Modified casting to ensure proper operations for different platforms

 Description:
    Eliminiated access to memory for noise seed. Now a local variable is
    used. Also unrolled loops to speed up code.

 Description:
    Modified pointer decrement to a pointer increment, to ensure proper
    compiler behavior

 Description:
------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:    random_array[] = Array for storage of the power-scaled
                             random values of length "band_length"
            Int32

            band_length    = Length of random_array[]
            const Int

            pSeed          = seed for random number generator
            Int32*

            power_scale    = scale factor for this particular band
            const Int


 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:   Function returns the q-format the random vector is stored in.

 Pointers and Buffers Modified:
            random_array[] = filled with random numbers scaled
            to the correct power as defined by the input value power_scale.

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function generates a vector of uniformly distributed random numbers for
 the PNS block.  The random numbers are each scaled by a scale_factor,
 defined in Ref(2) as

 2^(scale_factor/4)
 ------------------
  sqrt(N*MEAN_NRG)

 where N == band_length, and MEAN_NRG is defined as...

         N-1
         ___
     1   \
    ---   >    x(i)^2
     N   /__
         i=0

 And x is the unscaled vector from the random number generator.

 This function takes advantage of the fact that the portion of the
 scale_factor that is divisible by 4 can be simply accounted for by varying
 the q-format.

 The scaling of the random numbers is thus broken into the
 equivalent equation below.

 2^(scale_factor%4)   2^(floor(scale_factor/4))
 ------------------ *
  sqrt(N*MEAN_NRG)


 2^(scale_factor%4) is stored in a simple 4-element table.
 2^(floor(scale_factor/4) is accounted for by adjusting the q-format.
 sqrt(N*MEAN_NRG) is calculated and implemented via a polynomial approximation.
------------------------------------------------------------------------------
 REQUIREMENTS

 This function shall produce uniformly distributed random 32-bit integers,
 with signed random values of average energy equal to the results of the ISO
 code's multiplying factor discussed in the FUNCTION DESCRIPTION section.

 Please see Ref (2) for a detailed description of the requirements.
------------------------------------------------------------------------------
 REFERENCES

 (1) Numerical Recipes in C     Second Edition
        William H. Press        Saul A. Teukolsky
        William T. Vetterling   Brian P. Flannery
        Page 284

 (2) ISO/IEC 14496-3:1999(E)
     Part 3
        Subpart 4.6.12 (Perceptual Noise Substitution)

 (3) MPEG-2 NBC Audio Decoder
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
   developer retains full right to use the code for his/her  own purpose,
   assign or donate the code to a third party and to inhibit third party
   from using the code for non MPEG-2 NBC/MPEG-4 Audio conforming products.
   This copyright notice must be included in all copies or derivative
   works."
   Copyright(c)1996.

------------------------------------------------------------------------------
 PSEUDO-CODE

    power_adj = scale_mod_4[power_scale & 3];

    power = 0;

    FOR (k=band_length; k > 0; k--)

        *(pSeed) = *(pSeed) * 1664525L;
        *(pSeed) = *(pSeed) + 1013904223L;

        temp = (Int)(*(pSeed) >> 16);

        power = power + ((temp*temp) >> 6);

        *(pArray) = (Int32)temp;

        pArray = pArray + 1;

    ENDFOR

    k = 0;
    q_adjust = 30;

    IF (power)
    THEN

        WHILE ( power > 32767)

            power = power >> 1;
            k = k + 1;

        ENDWHILE

        k = k - 13;

        IF (k < 0)
        THEN
            k = -k;
            IF ( k & 1 )
            THEN
                power_adj = (power_adj*SQRT_OF_2)>>14;
            ENDIF
            q_adjust = q_adjust - ( k >> 1);

        ELSE IF (k > 0)
        THEN
            IF ( k & 1  )
            THEN
                power_adj = (power_adj*INV_SQRT_OF_2)>>14;
            ENDIF
            q_adjust = q_adjust + ( k >> 1);
        ENDIF

        pInvSqrtCoeff = inv_sqrt_coeff;

        inv_sqrt_power  = (*(pInvSqrtCoeff)* power) >>15;

        pInvSqrtCoeff = pInvSqrtCoeff + 1;

        inv_sqrt_power = inv_sqrt_power + *(pInvSqrtCoeff);

        pInvSqrtCoeff = pInvSqrtCoeff + 1;

        FOR ( k=INV_SQRT_POLY_ORDER - 1; k>0; k--)

            inv_sqrt_power  =  ( inv_sqrt_power * power)>>15;

            inv_sqrt_power = inv_sqrt_power + *(pInvSqrtCoeff);

            pInvSqrtCoeff = pInvSqrtCoeff + 1;

        ENDFOR

        inv_sqrt_power = (inv_sqrt_power*power_adj)>>13;

        FOR (k=band_length; k > 0; k--)

            pArray = pArray - 1;

            *(pArray) = *(pArray)*inv_sqrt_power;

        ENDFOR

    ENDIF

    q_adjust = q_adjust - (power_scale >> 2);

    return q_adjust;

------------------------------------------------------------------------------
 RESOURCES USED
   When the code is written for a specific target processor
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
#include    "gen_rand_vector.h"
#include    "window_block_fxp.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/
#define     SQRT_OF_2       23170       /*    sqrt(2) in Q14  */
#define     INV_SQRT_OF_2   11585       /*  1/sqrt(2) in Q14  */
#define     INV_SQRT_POLY_ORDER     4

/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/



/*
 *  2^([0:3]/4) = 1.0000    1.1892    1.4142    1.6818
 */
const UInt scale_mod_4[4] = { 16384, 19484, 23170, 27554};

/*
 *  polynomial approx. in Q12 (type Int)
 */

const Int  inv_sqrt_coeff[INV_SQRT_POLY_ORDER+1] =
    { 4680, -17935, 27697, -22326, 11980};


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

Int gen_rand_vector(
    Int32     random_array[],
    const Int band_length,
    Int32*   pSeed,
    const Int power_scale)
{

    Int      k;
    UInt     power_adj;
    Int      q_adjust = 30;

    Int32    temp;
    Int32    seed;
    Int32    power;

    Int32*   pArray = &random_array[0];

    Int32    inv_sqrt_power;
    const Int  *pInvSqrtCoeff;

    /*
     *  The out of the random number generator is scaled is such a way
     *  that is independent of the band length.
     *  The output is computed as:
     *
     *                  x(i)
     *  output = ------------------ * 2^(power_scale%4) 2^(floor(power_scale/4))
     *                   bl
     *           sqrt(  SUM x(i)^2 )
     *                   0
     *
     *  bl == band length
     */


    /*
     *  get 2^(power_scale%4)
     */


    power = 0;

    seed = *pSeed;

    /*
     *  band_length is always an even number (check tables in pg.66 IS0 14496-3)
     */
    if (band_length < 0 || band_length > LONG_WINDOW)
    {
        return  q_adjust;     /*  avoid any processing on error condition */
    }

    for (k = (band_length >> 1); k != 0; k--)
    {
        /*------------------------------------------------
           Numerical Recipes in C
                    Page 284
        ------------------------------------------------*/
        seed *= 1664525L;
        seed += 1013904223L;

        temp =  seed >> 16;

        seed *= 1664525L;
        seed += 1013904223L;

        /* shift by 6 make room for band length accumulation  */
        power  += ((temp * temp) >> 6);
        *pArray++ = temp;

        temp    = seed >> 16;
        power  += ((temp * temp) >> 6);
        *pArray++ = temp;

    } /* END for (k=half_band_length; k > 0; k--) */


    *pSeed = seed;

    /*
     *  If the distribution is uniform, the power is expected to use between
     *  28 and 27 bits, by shifting down by 13 bits the power will be a
     *  Q15 number.
     *  For different band lengths, the power uses between 20 and 29 bits
     */


    k = 0;

    if (power)
    {
        /*
         *    approximation requires power  between 0.5 < power < 1 in Q15.
         */

        while (power > 32767)
        {
            power >>= 1;
            k++;
        }

        /*
         *  expected power bit usage == 27 bits
         */

        k -= 13;

        power_adj = scale_mod_4[power_scale & 3];

        if (k < 0)
        {
            k = -k;
            if (k & 1)
            {                               /* multiply by sqrt(2)  */
                power_adj = (UInt)(((UInt32) power_adj * SQRT_OF_2) >> 14);
            }
            q_adjust -= (k >> 1);    /* adjust Q instead of shifting up */
        }
        else if (k > 0)
        {
            if (k & 1)
            {                               /* multiply by 1/sqrt(2)  */
                power_adj = (UInt)(((UInt32) power_adj * INV_SQRT_OF_2) >> 14);
            }
            q_adjust += (k >> 1);   /* adjust Q instead of shifting down */
        }

        /*
         *    Compute 1/sqrt(power), where 0.5 < power < 1.0 is approximated
         *    using a polynomial order INV_SQRT_POLY_ORDER
         */

        pInvSqrtCoeff = inv_sqrt_coeff;

        inv_sqrt_power  = (*(pInvSqrtCoeff++) * power) >> 15;
        inv_sqrt_power += *(pInvSqrtCoeff++);
        inv_sqrt_power  = (inv_sqrt_power * power) >> 15;
        inv_sqrt_power += *(pInvSqrtCoeff++);
        inv_sqrt_power  = (inv_sqrt_power * power) >> 15;
        inv_sqrt_power += *(pInvSqrtCoeff++);
        inv_sqrt_power  = (inv_sqrt_power * power) >> 15;
        inv_sqrt_power += *(pInvSqrtCoeff);

        inv_sqrt_power  = (inv_sqrt_power * power_adj) >> 13;

        pArray = &random_array[0];

        for (k = (band_length >> 1); k != 0; k--)
        {
            temp        = *(pArray) * inv_sqrt_power;
            *(pArray++) = temp;
            temp        = *(pArray) * inv_sqrt_power;
            *(pArray++) = temp;
        } /* END for (k=half_band_length; k > 0; k--) */

    }   /* if(power) */

    /*
     *      Adjust Q with the value corresponding to 2^(floor(power_scale/4))
     */

    q_adjust  -= (power_scale >> 2);

    return (q_adjust);

} /* gen_rand_vector */
