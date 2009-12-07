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

 Filename: calc_auto_corr.c


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


#include    "calc_auto_corr.h"
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
#include    "fxp_mul32.h"
#include    "pv_normalize.h"

#define N   2

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



void calc_auto_corr_LC(struct ACORR_COEFS *ac,
                       Int32  realBuf[][32],
                       Int32  bd,
                       Int32  len)
{
    Int32 j;
    Int32 temp1;
    Int32 temp3;
    Int32 temp5;

    int64_t temp_r01r;
    int64_t temp_r02r;
    int64_t temp_r11r;
    int64_t temp_r12r;
    int64_t temp_r22r;
    int64_t max = 0;


    temp1 = (realBuf[ 0][bd]) >> N;
    temp3 = (realBuf[-1][bd]) >> N;
    temp5 = (realBuf[-2][bd]) >> N;


    temp_r11r = fxp_mac64_Q31(0, temp3, temp3);   /* [j-1]*[j-1]  */
    temp_r12r = fxp_mac64_Q31(0, temp3, temp5);   /* [j-1]*[j-2]  */
    temp_r22r = fxp_mac64_Q31(0, temp5, temp5);   /* [j-2]*[j-2]  */

    temp_r01r = 0;
    temp_r02r = 0;

    for (j = 1; j < len; j++)
    {
        temp_r01r = fxp_mac64_Q31(temp_r01r, temp1, temp3);    /* [j  ]*[j-1]  */
        temp_r02r = fxp_mac64_Q31(temp_r02r, temp1, temp5);    /* [j  ]*[j-2]  */
        temp_r11r = fxp_mac64_Q31(temp_r11r, temp1, temp1);    /* [j-1]*[j-1]  */

        temp5 = temp3;
        temp3 = temp1;
        temp1 = (realBuf[j][bd]) >> N;
    }


    temp_r22r += temp_r11r;
    temp_r12r += temp_r01r;          /* [j-1]*[j-2]  */

    temp_r22r  = fxp_mac64_Q31(temp_r22r, -temp3, temp3);

    temp_r01r = fxp_mac64_Q31(temp_r01r, temp1, temp3);
    temp_r02r = fxp_mac64_Q31(temp_r02r, temp1, temp5);

    max  |= temp_r01r ^(temp_r01r >> 63);
    max  |= temp_r02r ^(temp_r02r >> 63);
    max  |= temp_r11r;
    max  |= temp_r12r ^(temp_r12r >> 63);
    max  |= temp_r22r;

    if (max)
    {
        temp1 = (UInt32)(max >> 32);
        if (temp1)
        {
            temp3 = 33 - pv_normalize(temp1);
            ac->r01r = (Int32)(temp_r01r >> temp3);
            ac->r02r = (Int32)(temp_r02r >> temp3);
            ac->r11r = (Int32)(temp_r11r >> temp3);
            ac->r12r = (Int32)(temp_r12r >> temp3);
            ac->r22r = (Int32)(temp_r22r >> temp3);

        }
        else
        {
            temp3 = pv_normalize(((UInt32)max) >> 1) - 2;

            if (temp3 > 0)
            {
                ac->r01r = (Int32)(temp_r01r << temp3);
                ac->r02r = (Int32)(temp_r02r << temp3);
                ac->r11r = (Int32)(temp_r11r << temp3);
                ac->r12r = (Int32)(temp_r12r << temp3);
                ac->r22r = (Int32)(temp_r22r << temp3);
            }
            else
            {
                temp3 = -temp3;
                ac->r01r = (Int32)(temp_r01r >> temp3);
                ac->r02r = (Int32)(temp_r02r >> temp3);
                ac->r11r = (Int32)(temp_r11r >> temp3);
                ac->r12r = (Int32)(temp_r12r >> temp3);
                ac->r22r = (Int32)(temp_r22r >> temp3);
            }

        }

        /*
         *  ac->det = ac->r11r*ac->r22r - rel*(ac->r12r*ac->r12r);
         */
        /* 1/(1 + 1e-6) == 1 - 1e-6 */
        /* 2^-20 == 1e-6 */
        ac->det  = fxp_mul32_Q30(ac->r12r, ac->r12r);

        ac->det -= ac->det >> 20;

        ac->det  = fxp_mul32_Q30(ac->r11r, ac->r22r) - ac->det;
    }
    else
    {
        pv_memset((void *)ac, 0, sizeof(struct ACORR_COEFS));
    }


}


#ifdef HQ_SBR


void calc_auto_corr(struct ACORR_COEFS *ac,
                    Int32  realBuf[][32],
                    Int32  imagBuf[][32],
                    Int32  bd,
                    Int32  len)
{


    Int32 j;
    Int32 temp1;
    Int32 temp2;
    Int32 temp3;
    Int32 temp4;
    Int32 temp5;
    Int32 temp6;

    int64_t accu1 = 0;
    int64_t accu2 = 0;
    int64_t accu3 = 0;
    int64_t accu4 = 0;
    int64_t accu5 = 0;


    int64_t temp_r12r;
    int64_t temp_r12i;
    int64_t temp_r22r;
    int64_t max = 0;


    temp1 = realBuf[0  ][bd] >> N;
    temp2 = imagBuf[0  ][bd] >> N;
    temp3 = realBuf[0-1][bd] >> N;
    temp4 = imagBuf[0-1][bd] >> N;
    temp5 = realBuf[0-2][bd] >> N;
    temp6 = imagBuf[0-2][bd] >> N;

    temp_r22r =  fxp_mac64_Q31(0, temp5, temp5);
    temp_r22r =  fxp_mac64_Q31(temp_r22r, temp6, temp6);
    temp_r12r =  fxp_mac64_Q31(0, temp3, temp5);
    temp_r12r =  fxp_mac64_Q31(temp_r12r, temp4, temp6);
    temp_r12i = -fxp_mac64_Q31(0, temp3, temp6);
    temp_r12i =  fxp_mac64_Q31(temp_r12i, temp4, temp5);

    for (j = 1; j < len; j++)
    {
        accu1  = fxp_mac64_Q31(accu1, temp3, temp3);
        accu1  = fxp_mac64_Q31(accu1, temp4, temp4);
        accu2  = fxp_mac64_Q31(accu2, temp1, temp3);
        accu2  = fxp_mac64_Q31(accu2, temp2, temp4);
        accu3  = fxp_mac64_Q31(accu3, temp2, temp3);
        accu3  = fxp_mac64_Q31(accu3, -temp1, temp4);
        accu4  = fxp_mac64_Q31(accu4, temp1, temp5);
        accu4  = fxp_mac64_Q31(accu4, temp2, temp6);
        accu5  = fxp_mac64_Q31(accu5, temp2, temp5);
        accu5  = fxp_mac64_Q31(accu5, -temp1, temp6);

        temp5 = temp3;
        temp6 = temp4;
        temp3 = temp1;
        temp4 = temp2;
        temp1 = realBuf[j][bd] >> N;
        temp2 = imagBuf[j][bd] >> N;
    }


    temp_r22r += accu1;
    temp_r12r += accu2;
    temp_r12i += accu3;


    accu1  = fxp_mac64_Q31(accu1, temp3, temp3);
    accu1  = fxp_mac64_Q31(accu1, temp4, temp4);
    accu2  = fxp_mac64_Q31(accu2, temp1, temp3);
    accu2  = fxp_mac64_Q31(accu2, temp2, temp4);
    accu3  = fxp_mac64_Q31(accu3, temp2, temp3);
    accu3  = fxp_mac64_Q31(accu3, -temp1, temp4);
    accu4  = fxp_mac64_Q31(accu4, temp1, temp5);
    accu4  = fxp_mac64_Q31(accu4, temp2, temp6);
    accu5  = fxp_mac64_Q31(accu5, temp2, temp5);
    accu5  = fxp_mac64_Q31(accu5, -temp1, temp6);


    max  |= accu5 ^(accu5 >> 63);
    max  |= accu4 ^(accu4 >> 63);
    max  |= accu3 ^(accu3 >> 63);
    max  |= accu2 ^(accu2 >> 63);
    max  |= accu1;
    max  |= temp_r12r ^(temp_r12r >> 63);
    max  |= temp_r12i ^(temp_r12i >> 63);
    max  |= temp_r22r;

    if (max)
    {

        temp1 = (UInt32)(max >> 32);
        if (temp1)
        {
            temp1 = 34 - pv_normalize(temp1);
            ac->r11r = (Int32)(accu1 >> temp1);
            ac->r01r = (Int32)(accu2 >> temp1);
            ac->r01i = (Int32)(accu3 >> temp1);
            ac->r02r = (Int32)(accu4 >> temp1);
            ac->r02i = (Int32)(accu5 >> temp1);
            ac->r12r = (Int32)(temp_r12r >> temp1);
            ac->r12i = (Int32)(temp_r12i >> temp1);
            ac->r22r = (Int32)(temp_r22r >> temp1);
        }
        else
        {
            temp1 = pv_normalize(((UInt32)max) >> 1) - 3;

            if (temp1 > 0)
            {
                ac->r11r = (Int32)(accu1 << temp1);
                ac->r01r = (Int32)(accu2 << temp1);
                ac->r01i = (Int32)(accu3 << temp1);
                ac->r02r = (Int32)(accu4 << temp1);
                ac->r02i = (Int32)(accu5 << temp1);
                ac->r12r = (Int32)(temp_r12r << temp1);
                ac->r12i = (Int32)(temp_r12i << temp1);
                ac->r22r = (Int32)(temp_r22r << temp1);
            }
            else
            {
                temp1 = -temp1;
                ac->r11r = (Int32)(accu1 >> temp1);
                ac->r01r = (Int32)(accu2 >> temp1);
                ac->r01i = (Int32)(accu3 >> temp1);
                ac->r02r = (Int32)(accu4 >> temp1);
                ac->r02i = (Int32)(accu5 >> temp1);
                ac->r12r = (Int32)(temp_r12r >> temp1);
                ac->r12i = (Int32)(temp_r12i >> temp1);
                ac->r22r = (Int32)(temp_r22r >> temp1);
            }

        }

        /*
         *  ac->det = ac->r11r*ac->r22r - rel*(ac->r12r*ac->r12r);
         */
        /* 1/(1 + 1e-6) == 1 - 1e-6 */
        /* 2^-20 == 1e-6 */

        ac->det =   fxp_mul32_Q29(ac->r12i, ac->r12i);
        ac->det =   fxp_mac32_Q29(ac->r12r, ac->r12r, ac->det);

        ac->det -= ac->det >> 20;

        ac->det =  -fxp_msu32_Q29(ac->r11r, ac->r22r, ac->det);

    }
    else
    {
        pv_memset((void *)ac, 0, sizeof(struct ACORR_COEFS));
    }

}

#endif





#endif


