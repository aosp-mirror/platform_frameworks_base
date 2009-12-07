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

 Filename: synthesis_sub_band.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

    Int32 vec[],            Input vector, 32-bit
    const Int32 *cosTerms,  Cosine Terms
    Int32 *scratch_mem      Scratch memory


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    Implement root squared of a number

------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#ifdef AAC_PLUS


#include "pv_audio_type_defs.h"
#include "fxp_mul32.h"
#include "dct64.h"
#include "synthesis_sub_band.h"
#include "mdst.h"
#include "dct16.h"


/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/

#define Qfmt_30(x)   (Int32)(x*((Int32)(1<<30)) + (x>=0?0.5F:-0.5F))
#define Qfmt_25(x)   (Int32)(x*((Int32)(1<<25))*(1.5625F) + (x>=0?0.5F:-0.5F))

#define SCALE_DOWN_LP   Qfmt_30(0.075000F)  /* 3/40 */
#define SCALE_DOWN_HQ     Qfmt_30(0.009375F*0.64F)  /* 3/40 * 1/8 */

/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/

const Int32 CosTable_64[64] =
{
    Qfmt_25(0.50003765191555F),   Qfmt_25(40.74468810335183F),   Qfmt_25(0.50033903744282F),   Qfmt_25(13.58429025728446F),
    Qfmt_25(0.50094271763809F),   Qfmt_25(8.15384860246681F),   Qfmt_25(0.50185051748424F),   Qfmt_25(5.82768837784465F),
    Qfmt_25(0.50306519130137F),   Qfmt_25(4.53629093696936F),   Qfmt_25(0.50459044322165F),   Qfmt_25(3.71524273832697F),
    Qfmt_25(0.50643095492855F),   Qfmt_25(3.14746219178191F),   Qfmt_25(0.50859242104981F),   Qfmt_25(2.73164502877394F),
    Qfmt_25(0.51108159270668F),   Qfmt_25(2.41416000025008F),   Qfmt_25(0.51390632984754F),   Qfmt_25(2.16395781875198F),
    Qfmt_25(0.51707566313349F),   Qfmt_25(1.96181784857117F),   Qfmt_25(0.52059986630189F),   Qfmt_25(1.79520521907789F),
    Qfmt_25(0.52449054011472F),   Qfmt_25(1.65559652426412F),   Qfmt_25(0.52876070920749F),   Qfmt_25(1.53699410085250F),
    Qfmt_25(0.53342493339713F),   Qfmt_25(1.43505508844143F),   Qfmt_25(0.53849943529198F),   Qfmt_25(1.34655762820629F),
    Qfmt_25(0.54400224638178F),   Qfmt_25(1.26906117169912F),   Qfmt_25(0.54995337418324F),   Qfmt_25(1.20068325572942F),
    Qfmt_25(0.55637499348989F),   Qfmt_25(1.13994867510150F),   Qfmt_25(0.56329166534170F),   Qfmt_25(1.08568506425801F),
    Qfmt_25(0.57073058801215F),   Qfmt_25(1.03694904091039F),   Qfmt_25(0.57872188513482F),   Qfmt_25(0.99297296126755F),
    Qfmt_25(0.58729893709379F),   Qfmt_25(0.95312587439212F),   Qfmt_25(0.59649876302446F),   Qfmt_25(0.91688444618465F),
    Qfmt_25(0.60636246227215F),   Qfmt_25(0.88381100455962F),   Qfmt_25(0.61693572600507F),   Qfmt_25(0.85353675100661F),
    Qfmt_25(0.62826943197077F),   Qfmt_25(0.82574877386279F),   Qfmt_25(0.64042033824166F),   Qfmt_25(0.80017989562169F),
    Qfmt_25(0.65345189537513F),   Qfmt_25(0.77660065823396F),   Qfmt_25(0.66743520092634F),   Qfmt_25(0.75481293911653F),
    Qfmt_25(0.68245012597642F),   Qfmt_25(0.73464482364786F),   Qfmt_25(0.69858665064723F),   Qfmt_25(0.71594645497057F),
};


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



void synthesis_sub_band_LC(Int32 Sr[], Int16 data[])
{

    Int32 *temp_o1 = (Int32 *) & data[0];

    Int   i;
    Int32 *pt_temp_e;
    Int32 *pt_temp_o = temp_o1;
    Int32 *pt_temp_x = &Sr[63];
    Int32 temp1;
    Int32 temp2;
    Int32 temp3;
    Int32 temp11;

    Int16 *pt_data_1;
    Int16 *pt_data_2;

    Int32 *pt_Sr_1 = Sr;
    Int16 tmp1;
    Int16 tmp2;
    Int16 tmp11;
    Int16 tmp22;
    const Int32 *pt_cosTerms = CosTable_48;


    temp2 = *(pt_temp_x--);
    for (i = 20; i != 0; i--)
    {
        temp1 = *(pt_Sr_1);
        temp3 = *(pt_cosTerms++);
        *(pt_Sr_1++) =   temp1  + temp2;
        *(pt_temp_o++) = fxp_mul32_Q31((temp1 - temp2), temp3) << 1;
        temp2 = *(pt_temp_x--);
    }

    for (i = 12; i != 0; i--)
    {
        temp1 = *(pt_Sr_1);
        temp3 = *(pt_cosTerms++);
        *(pt_Sr_1++) =   temp1  + temp2;
        *(pt_temp_o++) = fxp_mul32_Q26((temp1 - temp2), temp3);
        temp2 = *(pt_temp_x--);
    }


    pv_split_LC(temp_o1, &Sr[32]);

    dct_16(temp_o1, 1);     // Even terms
    dct_16(&Sr[32], 1);     // Odd  terms

    /* merge */


    pt_Sr_1 = &temp_o1[31];
    pt_temp_e   =  &temp_o1[15];
    pt_temp_o   =  &Sr[47];

    temp1 = *(pt_temp_o--);
    *(pt_Sr_1--) = temp1;
    for (i = 5; i != 0; i--)
    {
        temp2 = *(pt_temp_o--);
        *(pt_Sr_1--) = *(pt_temp_e--);
        *(pt_Sr_1--) = temp1 + temp2;
        temp3 = *(pt_temp_o--);
        *(pt_Sr_1--) = *(pt_temp_e--);
        *(pt_Sr_1--) = temp2 + temp3;
        temp1 = *(pt_temp_o--);
        *(pt_Sr_1--) = *(pt_temp_e--);
        *(pt_Sr_1--) = temp1 + temp3;
    }


    pv_split_LC(Sr, &Sr[32]);

    dct_16(Sr, 1);     // Even terms
    dct_16(&Sr[32], 1);     // Odd  terms


    pt_temp_x   =  &temp_o1[31];
    pt_temp_e   =  &Sr[15];
    pt_temp_o   =  &Sr[47];

    pt_data_1 = &data[95];

    temp2  = *(pt_temp_x--);
    temp11 = *(pt_temp_x--);
    temp1  = *(pt_temp_o--);

    *(pt_data_1--) = (Int16) fxp_mul32_Q31(temp2, SCALE_DOWN_LP);
    *(pt_data_1--) = (Int16) fxp_mul32_Q31(temp1, SCALE_DOWN_LP);

    for (i = 5; i != 0; i--)
    {
        *(pt_data_1--) = (Int16) fxp_mul32_Q31((temp11 + temp2), SCALE_DOWN_LP);
        temp3         = *(pt_temp_x--);
        *(pt_data_1--) = (Int16) fxp_mul32_Q31(*(pt_temp_e--), SCALE_DOWN_LP);
        temp2          = *(pt_temp_o--);
        *(pt_data_1--) = (Int16) fxp_mul32_Q31((temp11 + temp3), SCALE_DOWN_LP);
        temp11         = *(pt_temp_x--);
        *(pt_data_1--) = (Int16) fxp_mul32_Q31((temp1 + temp2), SCALE_DOWN_LP);


        *(pt_data_1--) = (Int16) fxp_mul32_Q31((temp11 + temp3), SCALE_DOWN_LP);
        temp1         = *(pt_temp_x--);
        *(pt_data_1--) = (Int16) fxp_mul32_Q31(*(pt_temp_e--), SCALE_DOWN_LP);
        temp3          = *(pt_temp_o--);
        *(pt_data_1--) = (Int16) fxp_mul32_Q31((temp11 + temp1), SCALE_DOWN_LP);
        temp11         = *(pt_temp_x--);
        *(pt_data_1--) = (Int16) fxp_mul32_Q31((temp2 + temp3), SCALE_DOWN_LP);


        *(pt_data_1--) = (Int16) fxp_mul32_Q31((temp11 + temp1), SCALE_DOWN_LP);
        temp2         = *(pt_temp_x--);
        *(pt_data_1--) = (Int16) fxp_mul32_Q31(*(pt_temp_e--), SCALE_DOWN_LP);
        temp1          = *(pt_temp_o--);
        *(pt_data_1--) = (Int16) fxp_mul32_Q31((temp11 + temp2), SCALE_DOWN_LP);
        temp11         = *(pt_temp_x--);
        *(pt_data_1--) = (Int16) fxp_mul32_Q31((temp1 + temp3), SCALE_DOWN_LP);
    }

    *(pt_data_1--) = (Int16) fxp_mul32_Q31((temp11 + temp2), SCALE_DOWN_LP);
    *(pt_data_1--) = (Int16) fxp_mul32_Q31(*(pt_temp_e), SCALE_DOWN_LP);

    /* ---- merge ends---- */


    pt_data_1 = &data[95];
    pt_data_2 = &data[96];

    *(pt_data_2++) =   0;
    tmp1  =  *(pt_data_1--);
    tmp2  =  *(pt_data_1--);
    tmp11 =  *(pt_data_1--);
    tmp22 =  *(pt_data_1--);

    for (i = 7; i != 0; i--)
    {
        *(pt_data_2++) = (-tmp1);
        *(pt_data_2++) = (-tmp2);
        *(pt_data_2++) = (-tmp11);
        *(pt_data_2++) = (-tmp22);
        tmp1  =  *(pt_data_1--);
        tmp2  =  *(pt_data_1--);
        tmp11 =  *(pt_data_1--);
        tmp22 =  *(pt_data_1--);
    }


    *(pt_data_2++) = (-tmp1);
    *(pt_data_2++) = (-tmp2);
    *(pt_data_2++) = (-tmp11);

    pt_data_2 = &data[0];

    *(pt_data_2++) =  tmp22;
    tmp1  =  *(pt_data_1--);
    tmp2  =  *(pt_data_1--);
    tmp11 =  *(pt_data_1--);
    tmp22 =  *(pt_data_1--);

    for (i = 7; i != 0; i--)
    {
        *(pt_data_2++) =  tmp1;
        *(pt_data_2++) =  tmp2;
        *(pt_data_2++) =  tmp11;
        *(pt_data_2++) =  tmp22;
        tmp1  =  *(pt_data_1--);
        tmp2  =  *(pt_data_1--);
        tmp11 =  *(pt_data_1--);
        tmp22 =  *(pt_data_1--);
    }

    *(pt_data_2++) =  tmp1;
    *(pt_data_2++) =  tmp2;
    *(pt_data_2++) =  tmp11;
    *(pt_data_2)   =  tmp22;

}


void synthesis_sub_band_LC_down_sampled(Int32 Sr[], Int16 data[])
{

    Int i ;
    Int16 *pt_data_1;

    pt_data_1 = &data[0];

    dct_32(Sr);

    for (i = 0; i < 16; i++)
    {
        pt_data_1[   i] = (Int16)(Sr[16-i] >> 5);
        pt_data_1[16+i] = (Int16)(Sr[i] >> 5);
        pt_data_1[32+i] = (Int16)(Sr[16+i] >> 5);
    }
    for (i = 0; i < 15; i++)
    {
        pt_data_1[49+i] = (Int16)(-Sr[31-i] >> 5);
    }
    pt_data_1[48] = 0;
}


#ifdef HQ_SBR

void synthesis_sub_band(Int32 Sr[], Int32 Si[], Int16 data[])
{


    Int32 i ;
    Int16 *pt_data_1;
    Int16 *pt_data_2;
    Int32 *pt_Sr_1;
    Int32 *pt_Sr_2;
    Int32 *pt_Si_1;
    Int32 *pt_Si_2;

    Int32 tmp1;
    Int32 tmp2;
    Int32 tmp3;
    Int32 tmp4;

    Int32 cosx;
    const Int32 *pt_CosTable = CosTable_64;


    pt_Sr_1 = &Sr[0];
    pt_Sr_2 = &Sr[63];

    pt_Si_1 = &Si[0];
    pt_Si_2 = &Si[63];


    tmp3 = *pt_Sr_1;

    for (i = 32; i != 0; i--)
    {
        tmp4 = *pt_Si_2;
        cosx = *(pt_CosTable++);
        *(pt_Sr_1++) = fxp_mul32_Q31(tmp3, cosx);
        tmp3 = *pt_Si_1;
        *(pt_Si_1++) = fxp_mul32_Q31(tmp4, cosx);
        tmp4 = *pt_Sr_2;
        cosx = *(pt_CosTable++);
        *(pt_Si_2--) = fxp_mul32_Q31(tmp3, cosx);
        *(pt_Sr_2--) = fxp_mul32_Q31(tmp4, cosx);
        tmp3 = *pt_Sr_1;
    }


    dct_64(Sr, (Int32 *)data);
    dct_64(Si, (Int32 *)data);


    pt_data_1 = &data[0];
    pt_data_2 = &data[127];

    pt_Sr_1 = &Sr[0];
    pt_Si_1 = &Si[0];

    tmp1 = *(pt_Sr_1++);
    tmp3 = *(pt_Sr_1++);
    tmp2 = *(pt_Si_1++);
    tmp4 = *(pt_Si_1++);

    for (i = 32; i != 0; i--)
    {
        *(pt_data_1++) = (Int16) fxp_mul32_Q31((tmp2 - tmp1), SCALE_DOWN_HQ);
        *(pt_data_1++) = (Int16) fxp_mul32_Q31(-(tmp3 + tmp4), SCALE_DOWN_HQ);
        *(pt_data_2--) = (Int16) fxp_mul32_Q31((tmp1 + tmp2), SCALE_DOWN_HQ);
        *(pt_data_2--) = (Int16) fxp_mul32_Q31((tmp3 - tmp4), SCALE_DOWN_HQ);

        tmp1 = *(pt_Sr_1++);
        tmp3 = *(pt_Sr_1++);
        tmp2 = *(pt_Si_1++);
        tmp4 = *(pt_Si_1++);
    }

}


const Int32 exp_m0_25_phi[32] =
{

    0x7FFEFE6E,  0x7FEAFB4A, 0x7FC2F827, 0x7F87F505,
    0x7F38F1E4,  0x7ED6EEC6, 0x7E60EBAB, 0x7DD6E892,
    0x7D3AE57D,  0x7C89E26D, 0x7BC6DF61, 0x7AEFDC59,
    0x7A06D958,  0x790AD65C, 0x77FBD367, 0x76D9D079,
    0x75A6CD92,  0x7460CAB2, 0x7308C7DB, 0x719EC50D,
    0x7023C248,  0x6E97BF8C, 0x6CF9BCDA, 0x6B4BBA33,
    0x698CB796,  0x67BDB505, 0x65DEB27F, 0x63EFB005,
    0x61F1AD97,  0x5FE4AB36, 0x5DC8A8E2, 0x5B9DA69C
};

void synthesis_sub_band_down_sampled(Int32 Sr[], Int32 Si[], Int16 data[])
{

    Int16 k;
    Int16 *pt_data_1;
    Int32 exp_m0_25;
    const Int32 *pt_exp = exp_m0_25_phi;

    Int32 *XX = Sr;
    Int32 *YY = (Int32 *)data;
    Int32 tmp1;
    Int32 tmp2;

    for (k = 0; k < 32; k++)
    {
        exp_m0_25 = *(pt_exp++);
        tmp1 = Sr[k];
        tmp2 = Si[k];
        XX[k]    = cmplx_mul32_by_16(-tmp1,  tmp2, exp_m0_25);
        YY[31-k] = cmplx_mul32_by_16(tmp2,  tmp1, exp_m0_25);
    }

    mdct_32(XX);
    mdct_32(YY);

    for (k = 0; k < 32; k++)
    {
        Si[k] = YY[k];
    }

    pt_data_1 = data;

    for (k = 0; k < 16; k++)
    {
        *(pt_data_1++)  = (Int16)((XX[2*k  ] + Si[2*k  ]) >> 14);
        *(pt_data_1++)  = (Int16)((XX[2*k+1] - Si[2*k+1]) >> 14);
    }

    for (k = 15; k > -1; k--)
    {
        *(pt_data_1++)  = (Int16)(-(XX[2*k+1] + Si[2*k+1]) >> 14);
        *(pt_data_1++)  = (Int16)(-(XX[2*k  ] - Si[2*k  ]) >> 14);
    }

}


#endif      /* HQ_SBR */

#endif      /*  AAC_PLUS */


