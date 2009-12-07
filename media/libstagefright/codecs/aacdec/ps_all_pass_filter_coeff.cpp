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

 Filename: ps_all_pass_filter_coeff.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS



------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

  Decorrelation is achieved by means of all-pass filtering


     _______                                              ________
    |       |                                  _______   |        |
  ->|Hybrid | LF ----                         |       |->| Hybrid |-->
    | Anal. |        |                        |       |  | Synth  |   QMF -> L
     -------         o----------------------->|       |   --------    Synth
QMF                  |                s_k(n)  |Stereo |-------------->
Anal.              -------------------------->|       |
     _______       | |                        |       |   ________
    |       | HF --o |   -----------          |Process|  |        |
  ->| Delay |      |  ->|           |-------->|       |->| Hybrid |-->
     -------       |    |decorrelate| d_k(n)  |       |  | Synth  |   QMF -> R
                   ---->|           |-------->|       |   --------    Synth
                         -----------          |_______|-------------->


------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

SC 29 Software Copyright Licencing Disclaimer:

This software module was originally developed by
  Coding Technologies

and edited by
  -

in the course of development of the ISO/IEC 13818-7 and ISO/IEC 14496-3
standards for reference purposes and its performance may not have been
optimized. This software module is an implementation of one or more tools as
specified by the ISO/IEC 13818-7 and ISO/IEC 14496-3 standards.
ISO/IEC gives users free license to this software module or modifications
thereof for use in products claiming conformance to audiovisual and
image-coding related ITU Recommendations and/or ISO/IEC International
Standards. ISO/IEC gives users the same free license to this software module or
modifications thereof for research purposes and further ISO/IEC standardisation.
Those intending to use this software module in products are advised that its
use may infringe existing patents. ISO/IEC have no liability for use of this
software module or modifications thereof. Copyright is not released for
products that do not conform to audiovisual and image-coding related ITU
Recommendations and/or ISO/IEC International Standards.
The original developer retains full right to modify and use the code for its
own purpose, assign or donate the code to a third party and to inhibit third
parties from using the code for products that do not conform to audiovisual and
image-coding related ITU Recommendations and/or ISO/IEC International Standards.
This copyright notice must be included in all copies or derivative works.
Copyright (c) ISO/IEC 2003.

------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#ifdef AAC_PLUS

#ifdef PARAMETRICSTEREO

#include    "pv_audio_type_defs.h"
#include    "s_ps_dec.h"
#include    "ps_all_pass_filter_coeff.h"
#include    "ps_all_pass_fract_delay_filter.h"

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


const Int16 aRevLinkDecaySerCoeff[NO_ALLPASS_CHANNELS][NO_SERIAL_ALLPASS_LINKS] =
{


    { Qfmt15(0.74915491616071f),  Qfmt15(0.64942584030892f),  Qfmt15(0.56297290849050f) },
    { Qfmt15(0.71658296328416f),  Qfmt15(0.62118993420853f),  Qfmt15(0.53849582551265f) },
    { Qfmt15(0.68401101040761f),  Qfmt15(0.59295402810815f),  Qfmt15(0.51401874253480f) },
    { Qfmt15(0.65143905753106f),  Qfmt15(0.56471812200776f),  Qfmt15(0.97908331911390f) },      /*  3  */
    { Qfmt15(0.61886710465450f),  Qfmt15(0.53648221590737f),  Qfmt15(0.93012915315822f) },
    { Qfmt15(0.58629515177795f),  Qfmt15(0.50824630980698f),  Qfmt15(0.88117498720252f) },
    { Qfmt15(0.55372319890140f),  Qfmt15(0.48001040370660f),  Qfmt15(0.83222082124682f) },
    { Qfmt15(0.52115124602484f),  Qfmt15(0.45177449760621f),  Qfmt15(0.78326665529112f) },
    { Qfmt15(0.48857929314829f),  Qfmt15(0.42353859150582f),  Qfmt15(0.73431248933542f) },
    { Qfmt15(0.45600734027174f),  Qfmt15(0.39530268540543f),  Qfmt15(0.68535832337974f) },
    { Qfmt15(0.42343538739519f),  Qfmt15(0.36706677930504f),  Qfmt15(0.63640415742404f) },
    { Qfmt15(0.39086343451863f),  Qfmt15(0.33883087320466f),  Qfmt15(0.58744999146834f) },
    { Qfmt15(0.35829148164208f),  Qfmt15(0.31059496710427f),  Qfmt15(0.53849582551265f) },
    { Qfmt15(0.32571952876553f),  Qfmt15(0.28235906100388f),  Qfmt15(0.48954165955695f) },
    { Qfmt15(0.29314757588898f),  Qfmt15(0.25412315490349f),  Qfmt15(0.44058749360126f) },
    { Qfmt15(0.26057562301242f),  Qfmt15(0.22588724880310f),  Qfmt15(0.39163332764556f) },
    { Qfmt15(0.22800367013587f),  Qfmt15(0.19765134270272f),  Qfmt15(0.34267916168986f) },
    { Qfmt15(0.19543171725932f),  Qfmt15(0.16941543660233f),  Qfmt15(0.29372499573418f) },
    { Qfmt15(0.16285976438276f),  Qfmt15(0.14117953050194f),  Qfmt15(0.24477082977848f) },
    { Qfmt15(0.13028781150621f),  Qfmt15(0.11294362440155f),  Qfmt15(0.19581666382278f) },
    { Qfmt15(0.09771585862966f),  Qfmt15(0.08470771830116f),  Qfmt15(0.14686249786708f) },
    { Qfmt15(0.06514390575311f),  Qfmt15(0.05647181220078f),  Qfmt15(0.09790833191140f) },
    { Qfmt15(0.03257195287655f),  Qfmt15(0.02823590610039f),  Qfmt15(0.04895416595570f) }

};





const Char groupBorders[NO_IID_GROUPS + 1] =
{
    4,  5,  0,  1,  2,  3,  7,  6,   8,  9,  3,  4,
    5,  6,  7,  8,  9,  11, 14, 18, 23, 35, 64
};

const Char bins2groupMap[NO_IID_GROUPS] =
{
    1, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19
};




/*
 *  q_phi = 0.39
 *
 *  cos(pi*([3:22]+0.5)*q_phi)
 */


/*
 *  sin(-pi*([3:22]+0.5)*q_phi)
 */


const Int32 aFractDelayPhaseFactor[NO_QMF_ALLPASS_CHANNELS] =
{
    0xCB5474A9,  0x5BEC5914, 0x72F3C7B0, 0xF1F480C6, 0x8389E21E,
    0xB9BA6AFC,  0x4CDB665C, 0x7A57DA5D, 0x06088024, 0x89BECF04,
    0xA9DB5EAC,  0x3BE5711F, 0x7EB9EDF7, 0x19F582A9, 0x92DDBD1F,
    0x9C1B5008,  0x29767919, 0x7FFC0203, 0x2D3F8843, 0x9EABACDF
};

/*
 *  q(m) = { 0.43, 0.75, 0.347 }
 *
 *  cos(pi*([3:22]+0.5)*q(m))        cos(pi*([3:22]+0.5)'*q)
 *
 *  sin(-pi*([3:22]+0.5)*q(m))
 *
 */


const Int32 aaFractDelayPhaseFactorSerQmf[NO_QMF_ALLPASS_CHANNELS][3] =
{
    { 0x02037FFC,  0xCF0489BE, 0x9BFB4FE0 },
    { 0x7D5719F5,  0xCF047642, 0x18947D9E },
    { 0x34AD8B57,  0x7642CF04, 0x7ABF244A },
    { 0x99A4B325,  0x89BECF04, 0x58EFA3F1 },
    { 0x9EAB5321,  0x30FC7642, 0xD77E8694 },
    { 0x3BE5711F,  0x30FC89BE, 0x819CEBC7 },
    { 0x7B77DE39,  0x89BE30FC, 0xB3A166B8 },
    { 0xF9F88024,  0x764230FC, 0x37C57336 },
    { 0x81E8E9FE,  0xCF0489BE, 0x7FF103D2 },
    { 0xCF047642,  0xCF047642, 0x3E8B9052 },
    { 0x68B9499A,  0x7642CF04, 0xB9E594E8 },
    { 0x5EACA9DB,  0x89BECF04, 0x80A00CA5 },
    { 0xC09590D1,  0x30FC7642, 0xD05276CA },
    { 0x85A925A3,  0x30FC89BE, 0x53486134 },
    { 0x0A0B7F9B,  0x89BE30FC, 0x7CB2E319 },
    { 0x7EB91209,  0x764230FC, 0x20078412 },
    { 0x2D3F8843,  0xCF0489BE, 0xA0ECAA4D },
    { 0x9504B9BA,  0xCF047642, 0x880D2CAE },
    { 0xA4145914,  0x7642CF04, 0xF0287F04 },
    { 0x42E16D23,  0x89BECF04, 0x694C48C7 }
};

/*
 *  Fractional delay vector
 *
 *  phi_fract(k) = exp(-j*pi*q_phi*f_center(k))       0<= k <= SUBQMF_GROUPS
 *
 *  q_phi = 0.39
 *  f_center(k) frequency vector
 *
 *
 *  f_center(k) = {0.5/4,  1.5/4,  2.5/4,  3.5/4,
 *                -1.5/4, -0.5/4,
 *                 3.5/2,  2.5/2,  4.5/2,  5.5/2};
 */



const Int32 aFractDelayPhaseFactorSubQmf[SUBQMF_GROUPS] =
{
    0x7E80EC79,  0x72BAC73D, 0x5C45A749, 0x3D398F97, 0x72BA38C3,
    0x7E801387,  0xBA919478, 0x05068019, 0x895DCFF2, 0x834E1CE7,
};





/*
 *  Fractional delay length matrix
 *
 *  Q_fract(k,m) = exp(-j*pi*q(m)*f_center(k))
 *
 *  q(m) = { 0.43, 0.75, 0.347 }
 *  f_center(k) frequency vector
 *
 *
 *  f_center(k) = { 0.5/4,  1.5/4,  2.5/4,  3.5/4,
 *                 -1.5/4, -0.5/4,
 *                  3.5/2,  2.5/2,  4.5/2,  5.5/2};
 */

const Int32 aaFractDelayPhaseFactorSerSubQmf[SUBQMF_GROUPS][3] =
{

    { 0x7E2EEA7D,  0x7A7DDAD8, 0x7ED0EE9D },
    { 0x6FEDC1E5,  0x51349D0E, 0x7574CD1E },
    { 0x5506A052,  0x0C8C809E, 0x636CAF62 },
    { 0x3085898D,  0xC3A98F1D, 0x4A0D9799 },
    { 0x6FED3E1B,  0x513462F2, 0x757432E2 },
    { 0x7E2E1583,  0x7A7D2528, 0x7ED01163 },
    { 0xA4C8A634,  0xB8E36A6E, 0xD5AF8732 },
    { 0xF0F580E3,  0x8276E707, 0x1A7382C3 },
    { 0x80ABF2F4,  0x471D6A6E, 0x9D2FAEA4 },
    { 0x9478456F,  0x7D8AE707, 0x8152EDAB }
};


/*----------------------------------------------------------------------------
; EXTERNAL GLOBAL STORE/BUFFER/POINTER REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

#endif


#endif

