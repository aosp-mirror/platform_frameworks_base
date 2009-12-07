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

  Filename: calc_sbr_synfilterbank.c


------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS



------------------------------------------------------------------------------
 FUNCTION DESCRIPTION


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
Copyright (c) ISO/IEC 2002.

------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
*/

#ifdef AAC_PLUS

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include    "calc_sbr_synfilterbank.h"
#include    "qmf_filterbank_coeff.h"
#include    "synthesis_sub_band.h"
#include    "fxp_mul32.h"
#include    "aac_mem_funcs.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/


#if defined (PV_ARM_V5)


__inline Int16 sat(Int32 y)
{
    Int32 x;
    __asm
    {
        qdadd y, y, y
        mov y, y, asr #16
    }

    return((Int16)y);
}

#define saturate2( a, b, c, d)      *c = sat( a);   \
                                    *d = sat( b);   \
                                    c += 2;         \
                                    d -= 2;


#elif defined (PV_ARM_V4)


__inline Int16 sat(Int32 y)
{
    Int32 x;
    Int32 z = 31; /* rvct compiler problem */
    __asm
    {
        sub y, y, y, asr 2
        mov y, y, asr N
        mov x, y, asr #15
        teq x, y, asr z
        eorne  y, INT16_MAX, y, asr #31
    }

    return((Int16)y);
}

#define saturate2( a, b, c, d)      *c = sat( a);   \
                                    *d = sat( b);   \
                                    c += 2;         \
                                    d -= 2;

#elif defined(PV_ARM_GCC_V5)

__inline Int16 sat(Int32 y)
{
    register Int32 x;
    register Int32 ra = y;


    asm volatile(
        "qdadd %0, %1, %1\n\t"
        "mov %0, %0, asr #16"
    : "=&r*i"(x)
                : "r"(ra));

    return ((Int16)x);
}


#define saturate2( a, b, c, d)      *c = sat( a);   \
                                    *d = sat( b);   \
                                    c += 2;         \
                                    d -= 2;


#elif defined(PV_ARM_MSC_EVC_V5)

#include "armintr.h"

#define saturate2( a, b, c, d)      *c = _DAddSatInt( a, a)>>16;   \
                                    *d = _DAddSatInt( b, b)>>16;   \
                                    c += 2;         \
                                    d -= 2;

#else


#define   saturate2( a, b, c, d)    a -= (a>>2);                             \
                                    a  = (a>>N);                     \
                                    if((a>>15) != (a>>31))                   \
                                    {                                        \
                                        a = ((a >> 31) ^ INT16_MAX); \
                                    }                                        \
                                    *c = (Int16)a;                           \
                                    c += 2;                                  \
                                    b -= (b>>2);                             \
                                    b =  (b>>N);                      \
                                    if((b>>15) != (b>>31))                   \
                                    {                                        \
                                        b = ((b >> 31) ^ INT16_MAX); \
                                    }                                        \
                                    *d = (Int16)b;                           \
                                    d -= 2;


#endif

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

void calc_sbr_synfilterbank_LC(Int32 * Sr,
                               Int16 * timeSig,
                               Int16   V[1280],
                               bool bDownSampleSBR)
{
    Int32 i;

    Int32   realAccu1;
    Int32   realAccu2;
    const Int32 *pt_C2;

    Int16 *pt_V1;
    Int16 *pt_V2;


    Int16 *pt_timeSig;

    Int16 *pt_timeSig_2;
    Int32  test1;
    Int16  tmp1;
    Int16  tmp2;

    /* shift filterstates */

    Int32 * pt_Sr = Sr;


    if (bDownSampleSBR == false)
    {

        synthesis_sub_band_LC(pt_Sr, V);

        /* content of V[] is at most 16 bits */

        pt_timeSig   = &timeSig[0];
        pt_timeSig_2 = &timeSig[64];


        tmp1 = V[ 704];
        tmp2 = V[ 768];
        realAccu1 =  fxp_mac_16_by_16(tmp1, Qfmt(0.853738560F), ROUND_SYNFIL);
        realAccu1 =  fxp_mac_16_by_16(tmp2, Qfmt(-0.361158990F), realAccu1);
        tmp1 = -V[ 512];
        tmp2 =  V[ 960];
        realAccu1 =  fxp_mac_16_by_16(tmp1, Qfmt(-0.361158990F), realAccu1);
        realAccu1 =  fxp_mac_16_by_16(tmp2, Qfmt(0.070353307F), realAccu1);
        tmp1 =  V[ 448];
        tmp2 =  V[1024];
        realAccu1 =  fxp_mac_16_by_16(tmp1, Qfmt(0.070353307F), realAccu1);
        realAccu1 =  fxp_mac_16_by_16(tmp2, Qfmt(-0.013271822F), realAccu1);
        tmp1 =  -V[ 256];
        tmp2 =   V[ 192];
        realAccu1 =  fxp_mac_16_by_16(tmp1, Qfmt(-0.013271822F), realAccu1);
        realAccu1 =  fxp_mac_16_by_16(tmp2, Qfmt(0.002620176F), realAccu1);
        realAccu1 =  fxp_mac_16_by_16(V[1216], Qfmt(0.002620176F), realAccu1);

        tmp1 = V[  32];
        tmp2 = V[1248];
        realAccu2 =  fxp_mac_16_by_16(tmp1, Qfmt(-0.000665042F), ROUND_SYNFIL);
        realAccu2 =  fxp_mac_16_by_16(tmp2, Qfmt(-0.000665042F), realAccu2);
        tmp1 = V[ 224];
        tmp2 = V[1056];
        realAccu2 =  fxp_mac_16_by_16(tmp1, Qfmt(0.005271576F), realAccu2);
        realAccu2 =  fxp_mac_16_by_16(tmp2, Qfmt(0.005271576F), realAccu2);
        tmp1 = V[ 992];
        tmp2 = V[ 288];
        realAccu2 =  fxp_mac_16_by_16(tmp1, Qfmt(0.058591568F), realAccu2);
        realAccu2 =  fxp_mac_16_by_16(tmp2, Qfmt(0.058591568F), realAccu2);
        tmp1 = V[ 480];
        tmp2 = V[ 800];
        realAccu2 =  fxp_mac_16_by_16(tmp1, Qfmt(-0.058370533F), realAccu2);
        realAccu2 =  fxp_mac_16_by_16(tmp2, Qfmt(-0.058370533F), realAccu2);
        tmp1 = V[ 736];
        tmp2 = V[ 544];
        realAccu2 =  fxp_mac_16_by_16(tmp1, Qfmt(0.702238872F), realAccu2);
        realAccu2 =  fxp_mac_16_by_16(tmp2, Qfmt(0.702238872F), realAccu2);



        saturate2(realAccu1, realAccu2, pt_timeSig, pt_timeSig_2);

        pt_timeSig_2 = &timeSig[126];

        pt_V1 = &V[1];
        pt_V2 = &V[1279];

        pt_C2 = &sbrDecoderFilterbankCoefficients[0];

        for (i = 31; i != 0; i--)
        {
            test1 = *(pt_C2++);
            tmp1 = *(pt_V1++);
            tmp2 = *(pt_V2--);
            realAccu1 =  fxp_mac_16_by_16_bt(tmp1 , test1, ROUND_SYNFIL);
            realAccu2 =  fxp_mac_16_by_16_bt(tmp2 , test1, ROUND_SYNFIL);
            tmp1 = pt_V1[  191];
            tmp2 = pt_V2[ -191];
            realAccu1 =  fxp_mac_16_by_16_bb(tmp1, test1, realAccu1);
            realAccu2 =  fxp_mac_16_by_16_bb(tmp2, test1, realAccu2);

            test1 = *(pt_C2++);
            tmp1 = pt_V1[  255];
            tmp2 = pt_V2[ -255];
            realAccu1 =  fxp_mac_16_by_16_bt(tmp1 , test1, realAccu1);
            realAccu2 =  fxp_mac_16_by_16_bt(tmp2 , test1, realAccu2);
            tmp1 = pt_V1[  447];
            tmp2 = pt_V2[ -447];
            realAccu1 =  fxp_mac_16_by_16_bb(tmp1, test1, realAccu1);
            realAccu2 =  fxp_mac_16_by_16_bb(tmp2, test1, realAccu2);

            test1 = *(pt_C2++);
            tmp1 = pt_V1[  511];
            tmp2 = pt_V2[ -511];
            realAccu1 =  fxp_mac_16_by_16_bt(tmp1 , test1, realAccu1);
            realAccu2 =  fxp_mac_16_by_16_bt(tmp2 , test1, realAccu2);
            tmp1 = pt_V1[  703];
            tmp2 = pt_V2[ -703];
            realAccu1 =  fxp_mac_16_by_16_bb(tmp1, test1, realAccu1);
            realAccu2 =  fxp_mac_16_by_16_bb(tmp2, test1, realAccu2);

            test1 = *(pt_C2++);
            tmp1 = pt_V1[  767];
            tmp2 = pt_V2[ -767];
            realAccu1 =  fxp_mac_16_by_16_bt(tmp1 , test1, realAccu1);
            realAccu2 =  fxp_mac_16_by_16_bt(tmp2 , test1, realAccu2);
            tmp1 = pt_V1[  959];
            tmp2 = pt_V2[ -959];
            realAccu1 =  fxp_mac_16_by_16_bb(tmp1, test1, realAccu1);
            realAccu2 =  fxp_mac_16_by_16_bb(tmp2, test1, realAccu2);

            test1 = *(pt_C2++);
            tmp1 = pt_V1[  1023];
            tmp2 = pt_V2[ -1023];
            realAccu1 =  fxp_mac_16_by_16_bt(tmp1 , test1, realAccu1);
            realAccu2 =  fxp_mac_16_by_16_bt(tmp2 , test1, realAccu2);
            tmp1 = pt_V1[  1215];
            tmp2 = pt_V2[ -1215];
            realAccu1 =  fxp_mac_16_by_16_bb(tmp1, test1, realAccu1);
            realAccu2 =  fxp_mac_16_by_16_bb(tmp2, test1, realAccu2);

            saturate2(realAccu1, realAccu2, pt_timeSig, pt_timeSig_2);

        }
    }
    else
    {

        synthesis_sub_band_LC_down_sampled(Sr, V);

        /*
         *    window signal
         *    calculate output samples
         */


        pt_V1 = &V[0];
        pt_V2 = &V[96];


        Int32 * pt_out = Sr;

        for (i = 0; i < 8; i++)
        {
            *(pt_out++) = 0;
            *(pt_out++) = 0;
            *(pt_out++) = 0;
            *(pt_out++) = 0;
        }

        const Int32* pt_C1 = &sbrDecoderFilterbankCoefficients_down_smpl[0];
        pt_C2 = &sbrDecoderFilterbankCoefficients_down_smpl[16];

        for (int k = 0; k < 5; k++)
        {
            pt_out -= 32;
            for (i = 0; i < 16; i++)
            {
                realAccu1   = fxp_mul_16_by_16bt(*(pt_V1++), *(pt_C1));
                realAccu2   = fxp_mul_16_by_16bb(*(pt_V1++), *(pt_C1++));
                realAccu1   = fxp_mac_16_by_16_bt(*(pt_V2++), *(pt_C2), realAccu1);
                realAccu2   = fxp_mac_16_by_16_bb(*(pt_V2++), *(pt_C2++), realAccu2);
                *(pt_out++) += realAccu1 >> 5;
                *(pt_out++) += realAccu2 >> 5;

            }
            pt_V1 += 96;
            pt_V2 += 96;
            pt_C1 += 16;
            pt_C2 += 16;
        }
        pt_out -= 32;

        for (i = 0; i < 32; i++)
        {
            timeSig[2*i] = (Int16)((*(pt_out++) + 512) >> 10);
        }

    }

}



#ifdef HQ_SBR

void calc_sbr_synfilterbank(Int32 * Sr,
                            Int32 * Si,
                            Int16 * timeSig,
                            Int16   V[1280],
                            bool bDownSampleSBR)
{
    Int32 i;

    const Int32 *pt_C2;

    Int32   realAccu1;
    Int32   realAccu2;

    Int16 *pt_V1;
    Int16 *pt_V2;

    Int16 *pt_timeSig;

    Int16 *pt_timeSig_2;
    Int32  test1;
    Int16  tmp1;
    Int16  tmp2;


    if (bDownSampleSBR == false)
    {
        synthesis_sub_band(Sr, Si, V);

        /* content of V[] is at most 16 bits */
        pt_timeSig   = &timeSig[0];
        pt_timeSig_2 = &timeSig[64];

        tmp1 = V[ 704];
        tmp2 = V[ 768];
        realAccu1 =  fxp_mac_16_by_16(tmp1, Qfmt(0.853738560F), ROUND_SYNFIL);
        realAccu1 =  fxp_mac_16_by_16(tmp2, Qfmt(-0.361158990F), realAccu1);
        tmp1 = -V[ 512];
        tmp2 =  V[ 960];
        realAccu1 =  fxp_mac_16_by_16(tmp1, Qfmt(-0.361158990F), realAccu1);
        realAccu1 =  fxp_mac_16_by_16(tmp2, Qfmt(0.070353307F), realAccu1);
        tmp1 =  V[ 448];
        tmp2 =  V[1024];
        realAccu1 =  fxp_mac_16_by_16(tmp1, Qfmt(0.070353307F), realAccu1);
        realAccu1 =  fxp_mac_16_by_16(tmp2, Qfmt(-0.013271822F), realAccu1);
        tmp1 =  -V[ 256];
        tmp2 =   V[ 192];
        realAccu1 =  fxp_mac_16_by_16(tmp1, Qfmt(-0.013271822F), realAccu1);
        realAccu1 =  fxp_mac_16_by_16(tmp2, Qfmt(0.002620176F), realAccu1);
        realAccu1 =  fxp_mac_16_by_16(V[1216], Qfmt(0.002620176F), realAccu1);

        tmp1 = V[  32];
        tmp2 = V[1248];
        realAccu2 =  fxp_mac_16_by_16(tmp1, Qfmt(-0.000665042F), ROUND_SYNFIL);
        realAccu2 =  fxp_mac_16_by_16(tmp2, Qfmt(-0.000665042F), realAccu2);
        tmp1 = V[ 224];
        tmp2 = V[1056];
        realAccu2 =  fxp_mac_16_by_16(tmp1, Qfmt(0.005271576F), realAccu2);
        realAccu2 =  fxp_mac_16_by_16(tmp2, Qfmt(0.005271576F), realAccu2);
        tmp1 = V[ 992];
        tmp2 = V[ 288];
        realAccu2 =  fxp_mac_16_by_16(tmp1, Qfmt(0.058591568F), realAccu2);
        realAccu2 =  fxp_mac_16_by_16(tmp2, Qfmt(0.058591568F), realAccu2);
        tmp1 = V[ 480];
        tmp2 = V[ 800];
        realAccu2 =  fxp_mac_16_by_16(tmp1, Qfmt(-0.058370533F), realAccu2);
        realAccu2 =  fxp_mac_16_by_16(tmp2, Qfmt(-0.058370533F), realAccu2);
        tmp1 = V[ 736];
        tmp2 = V[ 544];
        realAccu2 =  fxp_mac_16_by_16(tmp1, Qfmt(0.702238872F), realAccu2);
        realAccu2 =  fxp_mac_16_by_16(tmp2, Qfmt(0.702238872F), realAccu2);


        saturate2(realAccu1, realAccu2, pt_timeSig, pt_timeSig_2);

        pt_timeSig_2 = &timeSig[126];

        pt_V1 = &V[1];
        pt_V2 = &V[1279];

        pt_C2 = &sbrDecoderFilterbankCoefficients[0];

        for (i = 31; i != 0; i--)
        {
            test1 = *(pt_C2++);
            tmp1 = *(pt_V1++);
            tmp2 = *(pt_V2--);
            realAccu1 =  fxp_mac_16_by_16_bt(tmp1 , test1, ROUND_SYNFIL);
            realAccu2 =  fxp_mac_16_by_16_bt(tmp2 , test1, ROUND_SYNFIL);
            tmp1 = pt_V1[  191];
            tmp2 = pt_V2[ -191];
            realAccu1 =  fxp_mac_16_by_16_bb(tmp1, test1, realAccu1);
            realAccu2 =  fxp_mac_16_by_16_bb(tmp2, test1, realAccu2);

            test1 = *(pt_C2++);
            tmp1 = pt_V1[  255];
            tmp2 = pt_V2[ -255];
            realAccu1 =  fxp_mac_16_by_16_bt(tmp1 , test1, realAccu1);
            realAccu2 =  fxp_mac_16_by_16_bt(tmp2 , test1, realAccu2);
            tmp1 = pt_V1[  447];
            tmp2 = pt_V2[ -447];
            realAccu1 =  fxp_mac_16_by_16_bb(tmp1, test1, realAccu1);
            realAccu2 =  fxp_mac_16_by_16_bb(tmp2, test1, realAccu2);

            test1 = *(pt_C2++);
            tmp1 = pt_V1[  511];
            tmp2 = pt_V2[ -511];
            realAccu1 =  fxp_mac_16_by_16_bt(tmp1 , test1, realAccu1);
            realAccu2 =  fxp_mac_16_by_16_bt(tmp2 , test1, realAccu2);
            tmp1 = pt_V1[  703];
            tmp2 = pt_V2[ -703];
            realAccu1 =  fxp_mac_16_by_16_bb(tmp1, test1, realAccu1);
            realAccu2 =  fxp_mac_16_by_16_bb(tmp2, test1, realAccu2);

            test1 = *(pt_C2++);
            tmp1 = pt_V1[  767];
            tmp2 = pt_V2[ -767];
            realAccu1 =  fxp_mac_16_by_16_bt(tmp1 , test1, realAccu1);
            realAccu2 =  fxp_mac_16_by_16_bt(tmp2 , test1, realAccu2);
            tmp1 = pt_V1[  959];
            tmp2 = pt_V2[ -959];
            realAccu1 =  fxp_mac_16_by_16_bb(tmp1, test1, realAccu1);
            realAccu2 =  fxp_mac_16_by_16_bb(tmp2, test1, realAccu2);

            test1 = *(pt_C2++);
            tmp1 = pt_V1[  1023];
            tmp2 = pt_V2[ -1023];
            realAccu1 =  fxp_mac_16_by_16_bt(tmp1 , test1, realAccu1);
            realAccu2 =  fxp_mac_16_by_16_bt(tmp2 , test1, realAccu2);
            tmp1 = pt_V1[  1215];
            tmp2 = pt_V2[ -1215];
            realAccu1 =  fxp_mac_16_by_16_bb(tmp1, test1, realAccu1);
            realAccu2 =  fxp_mac_16_by_16_bb(tmp2, test1, realAccu2);

            saturate2(realAccu1, realAccu2, pt_timeSig, pt_timeSig_2);
        }

    }
    else
    {

        synthesis_sub_band_down_sampled(Sr,  Si,  V);


        Int32 * pt_out = Sr;

        for (i = 0; i < 8; i++)
        {
            *(pt_out++) = 0;
            *(pt_out++) = 0;
            *(pt_out++) = 0;
            *(pt_out++) = 0;
        }


        /*
         *    window signal
         *    calculate output samples
         */

        pt_V1 = &V[0];
        pt_V2 = &V[96];


        const Int32* pt_C1 = &sbrDecoderFilterbankCoefficients_down_smpl[0];
        pt_C2 = &sbrDecoderFilterbankCoefficients_down_smpl[16];

        for (Int k = 0; k < 5; k++)
        {
            pt_out -= 32;
            for (i = 0; i < 16; i++)
            {
                realAccu1   = fxp_mul_16_by_16bt(*(pt_V1++), *(pt_C1));
                realAccu2   = fxp_mul_16_by_16bb(*(pt_V1++), *(pt_C1++));
                realAccu1   = fxp_mac_16_by_16_bt(*(pt_V2++), *(pt_C2), realAccu1);
                realAccu2   = fxp_mac_16_by_16_bb(*(pt_V2++), *(pt_C2++), realAccu2);
                *(pt_out++) += realAccu1 >> 5;
                *(pt_out++) += realAccu2 >> 5;
            }
            pt_V1 += 96;
            pt_V2 += 96;
            pt_C1 += 16;
            pt_C2 += 16;
        }
        pt_out -= 32;

        for (i = 0; i < 32; i++)
        {
            timeSig[2*i] = (Int16)((*(pt_out++) + 512) >> 10);
        }

    }
}


#endif      /* --- HQ_SBR --- */


#endif      /* --- AAC_PLUS --- */



