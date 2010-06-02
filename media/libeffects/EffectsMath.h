/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef ANDROID_EFFECTSMATH_H_
#define ANDROID_EFFECTSMATH_H_

#include <stdint.h>

#if __cplusplus
extern "C" {
#endif

/** coefs for pan, generates sin, cos */
#define COEFF_PAN_G2    -27146    /* -0.82842712474619 = 2 - 4/sqrt(2) */
#define COEFF_PAN_G0    23170     /* 0.707106781186547 = 1/sqrt(2) */

/*
coefficients for approximating
2^x = gn2toX0 + gn2toX1*x + gn2toX2*x^2 + gn2toX3*x^3
where x is a int.frac number representing number of octaves.
Actually, we approximate only the 2^(frac) using the power series
and implement the 2^(int) as a shift, so that
2^x == 2^(int.frac) == 2^(int) * 2^(fract)
    == (gn2toX0 + gn2toX1*x + gn2toX2*x^2 + gn2toX3*x^3) << (int)

The gn2toX.. were generated using a best fit for a 3rd
order polynomial, instead of taking the coefficients from
a truncated Taylor (or Maclaurin?) series.
*/

#define GN2_TO_X0    32768    /*    1                    */
#define GN2_TO_X1    22833    /*    0.696807861328125    */
#define GN2_TO_X2    7344    /*    0.22412109375        */
#define GN2_TO_X3    2588    /*    0.0789794921875        */

/*----------------------------------------------------------------------------
 * Fixed Point Math
 *----------------------------------------------------------------------------
 * These macros are used for fixed point multiplies. If the processor
 * supports fixed point multiplies, replace these macros with inline
 * assembly code to improve performance.
 *----------------------------------------------------------------------------
*/

/* Fixed point multiply 0.15 x 0.15 = 0.15 returned as 32-bits */
#define FMUL_15x15(a,b) \
    /*lint -e(704) <avoid multiply for performance>*/ \
    (((int32_t)(a) * (int32_t)(b)) >> 15)

/* Fixed point multiply 0.7 x 0.7 = 0.15 returned as 32-bits */
#define FMUL_7x7(a,b) \
    /*lint -e(704) <avoid multiply for performance>*/ \
    (((int32_t)(a) * (int32_t)(b) ) << 1)

/* Fixed point multiply 0.8 x 0.8 = 0.15 returned as 32-bits */
#define FMUL_8x8(a,b) \
    /*lint -e(704) <avoid multiply for performance>*/ \
    (((int32_t)(a) * (int32_t)(b) ) >> 1)

/* Fixed point multiply 0.8 x 1.15 = 0.15 returned as 32-bits */
#define FMUL_8x15(a,b) \
    /*lint -e(704) <avoid divide for performance>*/ \
    (((int32_t)((a) << 7) * (int32_t)(b)) >> 15)

/* macros for fractional phase accumulator */
/*
Note: changed the _U32 to _I32 on 03/14/02. This should not
affect the phase calculations, and should allow us to reuse these
macros for other audio sample related math.
*/
#define HARDWARE_BIT_WIDTH        32

#define NUM_PHASE_INT_BITS        1
#define NUM_PHASE_FRAC_BITS       15

#define PHASE_FRAC_MASK           (uint32_t) ((0x1L << NUM_PHASE_FRAC_BITS) -1)

#define GET_PHASE_INT_PART(x)     (uint32_t)((uint32_t)(x) >> NUM_PHASE_FRAC_BITS)
#define GET_PHASE_FRAC_PART(x)    (uint32_t)((uint32_t)(x) & PHASE_FRAC_MASK)

#define DEFAULT_PHASE_FRAC        0
#define DEFAULT_PHASE_INT         0

/*
Linear interpolation calculates:
output = (1-frac) * sample[n] + (frac) * sample[n+1]

where conceptually    0 <= frac < 1

For a fixed point implementation, frac is actually an integer value
with an implied binary point one position to the left. The value of
one (unity) is given by PHASE_ONE
one half and one quarter are useful for 4-point linear interp.
*/
#define PHASE_ONE                (int32_t) (0x1L << NUM_PHASE_FRAC_BITS)

/*
 Multiply the signed audio sample by the unsigned fraction.
-  a is the signed audio sample
-  b is the unsigned fraction (cast to signed int as long as coef
    uses (n-1) or less bits, where n == hardware bit width)
*/
#define MULT_AUDIO_COEF(audio,coef)         /*lint -e704 <avoid divide for performance>*/ \
            (int32_t)(                                    \
            (                                            \
                ((int32_t)(audio)) * ((int32_t)(coef))    \
            )                                            \
            >> NUM_PHASE_FRAC_BITS                        \
                                        )                \
                                        /* lint +704 <restore checking>*/

/* wet / dry calculation macros */
#define NUM_WET_DRY_FRAC_BITS       7    // 15
#define NUM_WET_DRY_INT_BITS        9    // 1

/* define a 1.0 */
#define WET_DRY_ONE                 (int32_t) ((0x1L << NUM_WET_DRY_FRAC_BITS))
#define WET_DRY_MINUS_ONE           (int32_t) (~WET_DRY_ONE)
#define WET_DRY_FULL_SCALE          (int32_t) (WET_DRY_ONE - 1)

#define MULT_AUDIO_WET_DRY_COEF(audio,coef) /*lint -e(702) <avoid divide for performance>*/ \
            (int32_t)(                                        \
            (                                                \
                ((int32_t)(audio)) * ((int32_t)(coef))        \
            )                                                \
            >> NUM_WET_DRY_FRAC_BITS                        \
                                                     )

/* Envelope 1 (EG1) calculation macros */
#define    NUM_EG1_INT_BITS          1
#define NUM_EG1_FRAC_BITS            15

/* the max positive gain used in the synth for EG1 */
/* SYNTH_FULL_SCALE_EG1_GAIN must match the value in the dls2eas
converter, otherwise, the values we read from the .eas file are bogus. */
#define SYNTH_FULL_SCALE_EG1_GAIN    (int32_t) ((0x1L << NUM_EG1_FRAC_BITS) -1)

/* define a 1.0 */
#define EG1_ONE                      (int32_t) ((0x1L << NUM_EG1_FRAC_BITS))
#define EG1_MINUS_ONE                (int32_t) (~SYNTH_FULL_SCALE_EG1_GAIN)

#define EG1_HALF                     (int32_t) (EG1_ONE/2)
#define EG1_MINUS_HALF               (int32_t) (EG1_MINUS_ONE/2)

/*
We implement the EG1 using a linear gain value, which means that the
attack segment is handled by incrementing (adding) the linear gain.
However, EG1 treats the Decay, Sustain, and Release differently than
the Attack portion. For Decay, Sustain, and Release, the gain is
linear on dB scale, which is equivalent to exponential damping on
a linear scale. Because we use a linear gain for EG1, we implement
the Decay and Release as multiplication (instead of incrementing
as we did for the attack segment).
Therefore, we need the following macro to implement the multiplication
(i.e., exponential damping) during the Decay and Release segments of
the EG1
*/
#define MULT_EG1_EG1(gain,damping)        /*lint -e(704) <avoid divide for performance>*/ \
            (int32_t)(                                        \
            (                                                \
                ((int32_t)(gain)) * ((int32_t)(damping))    \
            )                                                \
            >> NUM_EG1_FRAC_BITS                            \
                                        )

// Use the following macro specifically for the filter, when multiplying
// the b1 coefficient. The 0 <= |b1| < 2, which therefore might overflow
// in certain conditions because we store b1 as a 1.15 value.
// Instead, we could store b1 as b1p (b1' == b1 "prime") where
// b1p == b1/2, thus ensuring no potential overflow for b1p because
// 0 <= |b1p| < 1
// However, during the filter calculation, we must account for the fact
// that we are using b1p instead of b1, and thereby multiply by
// an extra factor of 2. Rather than multiply by an extra factor of 2,
// we can instead shift the result right by one less, hence the
// modified shift right value of (NUM_EG1_FRAC_BITS -1)
#define MULT_EG1_EG1_X2(gain,damping)         /*lint -e(702) <avoid divide for performance>*/ \
            (int32_t)(                                        \
            (                                                \
                ((int32_t)(gain)) * ((int32_t)(damping))    \
            )                                                \
            >> (NUM_EG1_FRAC_BITS -1)                        \
                                        )

#define SATURATE_EG1(x)        /*lint -e{734} saturation operation */                \
    ((int32_t)(x) > SYNTH_FULL_SCALE_EG1_GAIN)    ? (SYNTH_FULL_SCALE_EG1_GAIN) :    \
    ((int32_t)(x) < EG1_MINUS_ONE)                ? (EG1_MINUS_ONE) :    (x);


/* use "digital cents" == "dents" instead of cents */
/* we coudl re-use the phase frac macros, but if we do,
we must change the phase macros to cast to _I32 instead of _U32,
because using a _U32 cast causes problems when shifting the exponent
for the 2^x calculation, because right shift a negative values MUST
be sign extended, or else the 2^x calculation is wrong */

/* use "digital cents" == "dents" instead of cents */
#define NUM_DENTS_FRAC_BITS        12
#define NUM_DENTS_INT_BITS         (HARDWARE_BIT_WIDTH - NUM_DENTS_FRAC_BITS)

#define DENTS_FRAC_MASK            (int32_t) ((0x1L << NUM_DENTS_FRAC_BITS) -1)

#define GET_DENTS_INT_PART(x)        /*lint -e(704) <avoid divide for performance>*/    \
                            (int32_t)((int32_t)(x) >> NUM_DENTS_FRAC_BITS)

#define GET_DENTS_FRAC_PART(x)     (int32_t)((int32_t)(x) & DENTS_FRAC_MASK)

#define DENTS_ONE                  (int32_t) (0x1L << NUM_DENTS_FRAC_BITS)

/* use CENTS_TO_DENTS to convert a value in cents to dents */
#define CENTS_TO_DENTS (int32_t) (DENTS_ONE * (0x1L << NUM_EG1_FRAC_BITS) / 1200L)                            \


/*
For gain, the LFO generates a value that modulates in terms
of dB. However, we use a linear gain value, so we must convert
the LFO value in dB to a linear gain. Normally, we would use
linear gain = 10^x, where x = LFO value in dB / 20.
Instead, we implement 10^x using our 2^x approximation.
because

  10^x = 2^(log2(10^x)) = 2^(x * log2(10))

so we need to multiply by log2(10) which is just a constant.
Ah, but just wait -- our 2^x actually doesn't exactly implement
2^x, but it actually assumes that the input is in cents, and within
the 2^x approximation converts its input from cents to octaves
by dividing its input by 1200.

So, in order to convert the LFO gain value in dB to something
that our existing 2^x approximation can use, multiply the LFO gain
by log2(10) * 1200 / 20

The divide by 20 helps convert dB to linear gain, and we might
as well incorporate that operation into this conversion.
Of course, we need to keep some fractional bits, so multiply
the constant by NUM_EG1_FRAC_BITS
*/

/* use LFO_GAIN_TO_CENTS to convert the LFO gain value to cents */
#if 0
#define    DOUBLE_LOG2_10    (double) (3.32192809488736)    /* log2(10) */

#define    DOUBLE_LFO_GAIN_TO_CENTS    (double)                \
    (                                                        \
                (DOUBLE_LOG2_10) *                            \
                1200.0    /                                    \
                20.0                                        \
    )

#define    LFO_GAIN_TO_CENTS    (int32_t)                        \
    (                                                        \
                DOUBLE_LFO_GAIN_TO_CENTS *                    \
                (0x1L << NUM_EG1_FRAC_BITS)                    \
    )
#endif

#define LFO_GAIN_TO_CENTS (int32_t) (1671981156L >> (23 - NUM_EG1_FRAC_BITS))


#define MULT_DENTS_COEF(dents,coef)     /*lint -e704 <avoid divide for performance>*/    \
            (int32_t)(                                    \
            (                                            \
                ((int32_t)(dents)) * ((int32_t)(coef))    \
            )                                            \
            >> NUM_DENTS_FRAC_BITS                        \
                                        )                \
                                        /* lint +e704 <restore checking>*/


/* we use 16-bits in the PC per audio sample */
#define BITS_PER_AUDIO_SAMPLE    16

/* we define 1 as 1.0 - 1 LSbit */
#define DISTORTION_ONE           (int32_t)((0x1L << (BITS_PER_AUDIO_SAMPLE-1)) -1)
#define DISTORTION_MINUS_ONE     (int32_t)(~DISTORTION_ONE)

/* drive coef is given as int.frac */
#define NUM_DRIVE_COEF_INT_BITS  1
#define NUM_DRIVE_COEF_FRAC_BITS 4

#define MULT_AUDIO_DRIVE(audio,drive)         /*lint -e(702) <avoid divide for performance>*/ \
            (int32_t)    (                                \
            (                                            \
                ((int32_t)(audio)) * ((int32_t)(drive))    \
            )                                            \
            >> NUM_DRIVE_COEF_FRAC_BITS                    \
                                                )

#define MULT_AUDIO_AUDIO(audio1,audio2)         /*lint -e(702) <avoid divide for performance>*/ \
            (int32_t)    (                                    \
            (                                                \
                ((int32_t)(audio1)) * ((int32_t)(audio2))    \
            )                                                \
            >> (BITS_PER_AUDIO_SAMPLE-1)                    \
                                                    )

#define SATURATE(x)                                                            \
    ((((int32_t)(x)) > DISTORTION_ONE)        ? (DISTORTION_ONE) :            \
    (((int32_t)(x)) < DISTORTION_MINUS_ONE)    ? (DISTORTION_MINUS_ONE) :    ((int32_t)(x)));


/*----------------------------------------------------------------------------
 * Effects_log2()
 *----------------------------------------------------------------------------
 * Purpose:
 * Fixed-point log2 function.
 *
 * Inputs:
 * Input is interpreted as an integer (should not be 0).
 *
 * Outputs:
 * Output is in 15-bit precision.
 *
 * Side Effects:
 *
 *----------------------------------------------------------------------------
*/
int32_t Effects_log2(uint32_t x);

/*----------------------------------------------------------------------------
 * Effects_exp2()
 *----------------------------------------------------------------------------
 * Purpose:
 * Fixed-point radix-2 exponent.
 *
 * Inputs:
 * Input is in 15-bit precision. Must be non-negative and less than 32.
 *
 * Outputs:
 * Output is an integer.
 *
 * Side Effects:
 *
 *----------------------------------------------------------------------------
*/
uint32_t Effects_exp2(int32_t x);

/*----------------------------------------------------------------------------
 * Effects_MillibelsToLinear16()
 *----------------------------------------------------------------------------
 * Purpose:
 * Transform gain in millibels to linear gain multiplier:
 *
 * mB = 2000*log(lin/32767)
 *    => lin = 2^((mB+2000*log(32767))/2000*log(2))
 *    => lin = Effects_exp2(((mB + K1) << 15) / K2)
 * with:
 *    K1 = 2000*log(32767) and K2 = 2000*log(2)
 *
 * Inputs:
 * nGain - log scale value in millibels.
 *
 * Outputs:
 * Returns a 16-bit linear value approximately equal to 2^(nGain/1024)
 *
 * Side Effects:
 *
 *----------------------------------------------------------------------------
*/
#define MB_TO_LIN_K1 9031
#define MB_TO_LIN_K2 602
int16_t Effects_MillibelsToLinear16 (int32_t nGain);

/*----------------------------------------------------------------------------
 * Effects_Linear16ToMillibels()
 *----------------------------------------------------------------------------
 * Purpose:
 * Transform linear gain multiplier to millibels
 *  mB = 2000*log(lin/32767)
 *     = 2000*log(2)*log2(lin)-2000*log(32767)
 *    => mB = K1*Effects_log2(lin) + K2
 * with:
 *    K1 = 2000*log(2) and K2 = -2000*log(32767)
 *
 * Inputs:
 * nGain - linear multiplier ranging form 0 to 32767 (corresponding to [0 1] gain range).
 *
 * Outputs:
 * Returns a 16-bit log value expressed in milllibels.
 *
 * Side Effects:
 *
 *----------------------------------------------------------------------------
*/
int16_t Effects_Linear16ToMillibels (int32_t nGain);

/*----------------------------------------------------------------------------
 * Effects_Sqrt()
 *----------------------------------------------------------------------------
 * Purpose:
 * Returns the square root of the argument given.
 *
 * Inputs:
 * in - positive number in the range 0 - 2^28
 *
 * Outputs:
 * Returned value: square root of in.
 *
 * Side Effects:
 *
 *----------------------------------------------------------------------------
*/
int32_t Effects_Sqrt(int32_t in);

#if __cplusplus
}  // extern "C"
#endif

#endif /*ANDROID_EFFECTSMATH_H_*/

