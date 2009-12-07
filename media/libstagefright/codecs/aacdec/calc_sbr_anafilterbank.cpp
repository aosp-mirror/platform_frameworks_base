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

 Filename: calc_sbr_anafilterbank.c


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


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#ifdef AAC_PLUS


#include    "calc_sbr_anafilterbank.h"
#include    "qmf_filterbank_coeff.h"
#include    "analysis_sub_band.h"

#include    "aac_mem_funcs.h"
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

void calc_sbr_anafilterbank_LC(Int32 * Sr,
                               Int16 * X,
                               Int32 scratch_mem[][64],
                               Int32 maxBand)
{

    Int i;
    Int32   *p_Y_1;
    Int32   *p_Y_2;

    Int16 * pt_X_1;
    Int16 * pt_X_2;
    Int32 realAccu1;
    Int32 realAccu2;

    Int32 tmp1;
    Int32 tmp2;


    const Int32 * pt_C;

    p_Y_1 = scratch_mem[0];


    p_Y_2 = p_Y_1 + 63;
    pt_C   = &sbrDecoderFilterbankCoefficients_an_filt_LC[0];

    pt_X_1 = X;


    realAccu1  =  fxp_mul32_by_16(Qfmt27(-0.51075594183097F),   pt_X_1[-192]);

    realAccu1  =  fxp_mac32_by_16(Qfmt27(-0.51075594183097F), -pt_X_1[-128], realAccu1);
    realAccu1  =  fxp_mac32_by_16(Qfmt27(-0.01876919066980F),  pt_X_1[-256], realAccu1);
    *(p_Y_1++) =  fxp_mac32_by_16(Qfmt27(-0.01876919066980F), -pt_X_1[ -64], realAccu1);


    /* create array Y */

    pt_X_1 = &X[-1];
    pt_X_2 = &X[-319];


    for (i = 15; i != 0; i--)
    {
        tmp1 = *(pt_X_1--);
        tmp2 = *(pt_X_2++);

        realAccu1  = fxp_mul32_by_16(*(pt_C), tmp1);
        realAccu2  = fxp_mul32_by_16(*(pt_C++), tmp2);
        tmp1 = pt_X_1[ -63];
        tmp2 = pt_X_2[ +63];
        realAccu1  = fxp_mac32_by_16(*(pt_C), tmp1, realAccu1);
        realAccu2  = fxp_mac32_by_16(*(pt_C++), tmp2, realAccu2);
        tmp1 = pt_X_1[ -127];
        tmp2 = pt_X_2[ +127];
        realAccu1  = fxp_mac32_by_16(*(pt_C), tmp1, realAccu1);
        realAccu2  = fxp_mac32_by_16(*(pt_C++), tmp2, realAccu2);
        tmp1 = pt_X_1[ -191];
        tmp2 = pt_X_2[ +191];
        realAccu1  = fxp_mac32_by_16(*(pt_C), tmp1, realAccu1);
        realAccu2  = fxp_mac32_by_16(*(pt_C++), tmp2, realAccu2);
        tmp1 = pt_X_1[ -255];
        tmp2 = pt_X_2[ +255];
        *(p_Y_1++) = fxp_mac32_by_16(*(pt_C), tmp1, realAccu1);
        *(p_Y_2--) = fxp_mac32_by_16(*(pt_C++), tmp2, realAccu2);

        tmp1 = *(pt_X_1--);
        tmp2 = *(pt_X_2++);
        realAccu1  = fxp_mul32_by_16(*(pt_C), tmp1);
        realAccu2  = fxp_mul32_by_16(*(pt_C++), tmp2);

        tmp1 = pt_X_1[ -63];
        tmp2 = pt_X_2[ +63];
        realAccu1  = fxp_mac32_by_16(*(pt_C), tmp1, realAccu1);
        realAccu2  = fxp_mac32_by_16(*(pt_C++), tmp2, realAccu2);
        tmp1 = pt_X_1[ -127];
        tmp2 = pt_X_2[ +127];
        realAccu1  = fxp_mac32_by_16(*(pt_C), tmp1, realAccu1);
        realAccu2  = fxp_mac32_by_16(*(pt_C++), tmp2, realAccu2);
        tmp1 = pt_X_1[ -191];
        tmp2 = pt_X_2[ +191];
        realAccu1  = fxp_mac32_by_16(*(pt_C), tmp1, realAccu1);
        realAccu2  = fxp_mac32_by_16(*(pt_C++), tmp2, realAccu2);
        tmp1 = pt_X_1[ -255];
        tmp2 = pt_X_2[ +255];
        *(p_Y_1++) = fxp_mac32_by_16(*(pt_C), tmp1, realAccu1);
        *(p_Y_2--) = fxp_mac32_by_16(*(pt_C++), tmp2, realAccu2);

    }


    tmp1 = *(pt_X_1--);
    tmp2 = *(pt_X_2++);
    realAccu1  = fxp_mul32_by_16(*(pt_C), tmp1);
    realAccu2  = fxp_mul32_by_16(*(pt_C++), tmp2);

    tmp1 = pt_X_1[ -63];
    tmp2 = pt_X_2[ +63];
    realAccu1  = fxp_mac32_by_16(*(pt_C), tmp1, realAccu1);
    realAccu2  = fxp_mac32_by_16(*(pt_C++), tmp2, realAccu2);
    tmp1 = pt_X_1[ -127];
    tmp2 = pt_X_2[ +127];
    realAccu1  = fxp_mac32_by_16(*(pt_C), tmp1, realAccu1);
    realAccu2  = fxp_mac32_by_16(*(pt_C++), tmp2, realAccu2);
    tmp1 = pt_X_1[ -191];
    tmp2 = pt_X_2[ +191];
    realAccu1  = fxp_mac32_by_16(*(pt_C), tmp1, realAccu1);
    realAccu2  = fxp_mac32_by_16(*(pt_C++), tmp2, realAccu2);
    tmp1 = pt_X_1[ -255];
    tmp2 = pt_X_2[ +255];
    *(p_Y_1++) = fxp_mac32_by_16(*(pt_C), tmp1, realAccu1);
    *(p_Y_2--) = fxp_mac32_by_16(*(pt_C++), tmp2, realAccu2);


    pt_X_1 = X;

    realAccu2  = fxp_mul32_by_16(Qfmt27(0.00370548843500F), X[ -32]);

    realAccu2  = fxp_mac32_by_16(Qfmt27(0.00370548843500F), pt_X_1[-288], realAccu2);
    realAccu2  = fxp_mac32_by_16(Qfmt27(0.09949460091720F), pt_X_1[ -96], realAccu2);
    realAccu2  = fxp_mac32_by_16(Qfmt27(0.09949460091720F), pt_X_1[-224], realAccu2);
    *(p_Y_1++) = fxp_mac32_by_16(Qfmt27(1.20736865027288F), pt_X_1[-160], realAccu2);


    analysis_sub_band_LC(scratch_mem[0],
                         Sr,
                         maxBand,
                         (Int32(*)[64])scratch_mem[1]);

}



#ifdef HQ_SBR

void calc_sbr_anafilterbank(Int32 * Sr,
                            Int32 * Si,
                            Int16 * X,
                            Int32 scratch_mem[][64],
                            Int32   maxBand)
{
    Int i;
    Int32   *p_Y_1;
    Int32   *p_Y_2;




    const Int32 * pt_C;
    Int16 * pt_X_1;
    Int16 * pt_X_2;
    Int32 realAccu1;
    Int32 realAccu2;

    Int32 tmp1;
    Int32 tmp2;


    p_Y_1 = scratch_mem[0];


    p_Y_2 = p_Y_1 + 63;
    pt_C   = &sbrDecoderFilterbankCoefficients_an_filt[0];

    realAccu1  =  fxp_mul32_by_16(Qfmt27(-0.36115899F),   X[-192]);


    realAccu1  =  fxp_mac32_by_16(Qfmt27(-0.36115899F),  -X[-128], realAccu1);
    realAccu1  =  fxp_mac32_by_16(Qfmt27(-0.013271822F),  X[-256], realAccu1);
    *(p_Y_1++) =  fxp_mac32_by_16(Qfmt27(-0.013271822F), -X[ -64], realAccu1);

    /* create array Y */

    pt_X_1 = &X[-1];
    pt_X_2 = &X[-319];


    for (i = 31; i != 0; i--)
    {
        tmp1 = *(pt_X_1--);
        tmp2 = *(pt_X_2++);
        realAccu1  = fxp_mul32_by_16(*(pt_C), tmp1);
        realAccu2  = fxp_mul32_by_16(*(pt_C++), tmp2);
        tmp1 = pt_X_1[ -63];
        tmp2 = pt_X_2[  63];
        realAccu1  = fxp_mac32_by_16(*(pt_C), tmp1, realAccu1);
        realAccu2  = fxp_mac32_by_16(*(pt_C++), tmp2, realAccu2);
        tmp1 = pt_X_1[ -127];
        tmp2 = pt_X_2[  127];
        realAccu1  = fxp_mac32_by_16(*(pt_C), tmp1, realAccu1);
        realAccu2  = fxp_mac32_by_16(*(pt_C++), tmp2, realAccu2);
        tmp1 = pt_X_1[ -191];
        tmp2 = pt_X_2[  191];
        realAccu1  = fxp_mac32_by_16(*(pt_C), tmp1, realAccu1);
        realAccu2  = fxp_mac32_by_16(*(pt_C++), tmp2, realAccu2);
        tmp1 = pt_X_1[ -255];
        tmp2 = pt_X_2[  255];
        *(p_Y_1++) = fxp_mac32_by_16(*(pt_C), tmp1, realAccu1);
        *(p_Y_2--) = fxp_mac32_by_16(*(pt_C++), tmp2, realAccu2);
    }


    realAccu2  = fxp_mul32_by_16(Qfmt27(0.002620176F), X[ -32]);
    realAccu2  = fxp_mac32_by_16(Qfmt27(0.002620176F), X[-288], realAccu2);
    realAccu2  = fxp_mac32_by_16(Qfmt27(0.070353307F), X[ -96], realAccu2);
    realAccu2  = fxp_mac32_by_16(Qfmt27(0.070353307F), X[-224], realAccu2);


    *(p_Y_1++) = fxp_mac32_by_16(Qfmt27(0.85373856F), (X[-160]), realAccu2);


    analysis_sub_band(scratch_mem[0],
                      Sr,
                      Si,
                      maxBand,
                      (Int32(*)[64])scratch_mem[1]);

}


#endif



#endif   /*  AAC_PLUS */

