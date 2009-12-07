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

 Filename: dct64.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

    Int32 x             32-bit integer input length 64


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    Implement dct of lenght 64

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


#include "dct16.h"
#include "dct64.h"

#include "pv_audio_type_defs.h"
#include "synthesis_sub_band.h"

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

#define Qfmt(a)   (Int32)(a*((Int32)1<<26) + (a>=0?0.5F:-0.5F))
#define Qfmt31(a)   (Int32)(a*0x7FFFFFFF)

const Int32 CosTable_48[48] =
{
    Qfmt31(0.50015063602065F) ,  Qfmt31(0.50135845244641F) ,
    Qfmt31(0.50378872568104F) ,  Qfmt31(0.50747117207256F) ,
    Qfmt31(0.51245147940822F) ,  Qfmt31(0.51879271310533F) ,
    Qfmt31(0.52657731515427F) ,  Qfmt31(0.53590981690799F) ,
    Qfmt31(0.54692043798551F) ,  Qfmt31(0.55976981294708F) ,
    Qfmt31(0.57465518403266F) ,  Qfmt31(0.59181853585742F) ,
    Qfmt31(0.61155734788251F) ,  Qfmt31(0.63423893668840F) ,
    Qfmt31(0.66031980781371F) ,  Qfmt31(0.69037212820021F) ,
    Qfmt31(0.72512052237720F) ,  Qfmt31(0.76549416497309F) ,
    Qfmt31(0.81270209081449F) ,  Qfmt31(0.86834471522335F) ,
    Qfmt(0.93458359703641F) ,  Qfmt(1.01440826499705F) ,
    Qfmt(1.11207162057972F) ,  Qfmt(1.23383273797657F) ,
    Qfmt(1.38929395863283F) ,  Qfmt(1.59397228338563F) ,
    Qfmt(1.87467598000841F) ,  Qfmt(2.28205006800516F) ,
    Qfmt(2.92462842815822F) ,  Qfmt(4.08461107812925F) ,
    Qfmt(6.79675071167363F) ,  Qfmt(20.37387816723145F) , /* 32 */
    Qfmt(0.50060299823520F) ,  Qfmt(0.50547095989754F) ,
    Qfmt(0.51544730992262F) ,  Qfmt(0.53104259108978F) ,
    Qfmt(0.55310389603444F) ,  Qfmt(0.58293496820613F) ,
    Qfmt(0.62250412303566F) ,  Qfmt(0.67480834145501F) ,
    Qfmt(0.74453627100230F) ,  Qfmt(0.83934964541553F) ,
    Qfmt(0.97256823786196F) ,  Qfmt(1.16943993343288F) ,
    Qfmt(1.48416461631417F) ,  Qfmt(2.05778100995341F) ,
    Qfmt(3.40760841846872F) ,  Qfmt(10.19000812354803F)
};




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

/*----------------------------------------------------------------------------
; dct_64
----------------------------------------------------------------------------*/

void pv_split_LC(Int32 *vector,
                 Int32 *temp_o)
{

    Int32 i;
    Int32 *pt_vector     = &vector[0];
    Int32 *pt_vector_N_1 = &vector[31];
    const Int32 *pt_cosTerms = &CosTable_48[32];
    Int32 *pt_temp_o = temp_o;
    Int32 tmp1;
    Int32 tmp2;
    Int32 tmp3;


    tmp1 = *(pt_vector);
    tmp2 = *(pt_vector_N_1--);
    for (i = 16; i != 0; i--)
    {
        tmp3 = *(pt_cosTerms++);
        *(pt_vector++) =   tmp1  + tmp2;
        *(pt_temp_o++) = fxp_mul32_Q26((tmp1 - tmp2), tmp3);
        tmp1 = *(pt_vector);
        tmp2 = *(pt_vector_N_1--);
    }

}


#ifdef HQ_SBR


void dct_64(Int32 vec[], Int32 *scratch_mem)
{
    Int32 *temp_e1;
    Int32 *temp_o1;

    Int32 *pt_vec;

    Int   i;

    Int32 aux1;
    Int32 aux2;
    Int32 aux3;
    Int32 aux4;

    const Int32 *cosTerms = &CosTable_48[31];

    temp_o1 = &vec[32];
    temp_e1 = temp_o1 - 1;


    for (i = 6; i != 0; i--)
    {
        aux1 = *(temp_e1);
        aux2 = *(temp_o1);
        aux3 = *(cosTerms--);
        *(temp_e1--) =   aux1  + aux2;
        *(temp_o1++) = fxp_mul32_Q26((aux1 - aux2), aux3);
        aux1 = *(temp_e1);
        aux2 = *(temp_o1);
        aux3 = *(cosTerms--);
        *(temp_e1--) =   aux1  + aux2;
        *(temp_o1++) = fxp_mul32_Q26((aux1 - aux2), aux3);
    }


    for (i = 10; i != 0; i--)
    {
        aux1 = *(temp_e1);
        aux2 = *(temp_o1);
        aux3 = *(cosTerms--);
        *(temp_e1--) =   aux1  + aux2;
        *(temp_o1++) = fxp_mul32_Q31((aux1 - aux2), aux3) << 1;
        aux1 = *(temp_e1);
        aux2 = *(temp_o1);
        aux3 = *(cosTerms--);
        *(temp_e1--) =   aux1  + aux2;
        *(temp_o1++) = fxp_mul32_Q31((aux1 - aux2), aux3) << 1;
    }


    pv_split(&vec[16]);

    dct_16(&vec[16], 0);
    dct_16(vec,     1);      // Even terms

    pv_merge_in_place_N32(vec);

    pv_split_z(&vec[32]);

    dct_16(&vec[32], 1);     // Even terms
    dct_16(&vec[48], 0);

    pv_merge_in_place_N32(&vec[32]);



    aux1   = vec[32];
    aux3   = vec[33];
    aux4   = vec[ 1];  /* vec[ 1] */

    /* -----------------------------------*/
    aux1     = vec[32] + vec[33];
    vec[ 0] +=  aux1;
    vec[ 1] +=  aux1;

    aux1        = vec[34];
    aux2        = vec[ 2];   /* vec[ 2] */
    aux3        += aux1;
    vec[ 2] = aux4 + aux3;
    aux4        = vec[ 3];  /* vec[ 3] */
    vec[ 3] = aux2 + aux3;

    aux3        = vec[35];

    /* -----------------------------------*/
    aux1        += aux3;
    vec[32] = vec[ 4];
    vec[33] = vec[ 5];
    vec[ 4] = aux2 + aux1;
    vec[ 5] = aux4 + aux1;

    aux1        = vec[36];
    aux2        = vec[32];  /* vec[ 4] */
    aux3        += aux1;
    vec[34] = vec[ 6];
    vec[35] = vec[ 7];
    vec[ 6] = aux4 + aux3;
    vec[ 7] = aux2 + aux3;

    aux3        = vec[37];
    aux4        = vec[33];  /* vec[ 5] */

    /* -----------------------------------*/
    aux1        += aux3;
    vec[32] = vec[ 8];
    vec[33] = vec[ 9];
    vec[ 8] = aux2 + aux1;
    vec[ 9] = aux4 + aux1;

    aux1        = vec[38];
    aux2        = vec[34];  /* vec[ 6] */
    aux3        += aux1;
    vec[34] = vec[10];
    vec[10] = aux4 + aux3;
    aux4        = vec[35];  /* vec[ 7] */
    vec[35] = vec[11];
    vec[11] = aux2 + aux3;

    aux3        = vec[39];

    /* -----------------------------------*/
    aux1        += aux3;
    vec[36] = vec[12];
    vec[37] = vec[13];
    vec[12] = aux2 + aux1;
    vec[13] = aux4 + aux1;

    aux1        = vec[40];
    aux2        = vec[32];  /* vec[ 8] */
    aux3        += aux1;
    vec[32] = vec[14];
    vec[14] = aux4 + aux3;
    aux4        = vec[33];  /* vec[ 9] */
    vec[33] = vec[15];
    vec[15] = aux2 + aux3;

    aux3        = vec[41];

    /* -----------------------------------*/
    aux1        += aux3;
    vec[38] = vec[16];
    vec[39] = vec[17];
    vec[16] = aux2 + aux1;
    vec[17] = aux4 + aux1;

    aux1        = vec[42];
    aux2        = vec[34];  /* vec[10] */
    aux3        += aux1;
    vec[34] = vec[18];
    vec[18] = aux4 + aux3;
    aux4        = vec[35];  /* vec[11] */
    vec[35] = vec[19];
    vec[19] = aux2 + aux3;

    aux3        = vec[43];

    /* -----------------------------------*/
    aux1        += aux3;
    vec[40] = vec[20];
    vec[41] = vec[21];
    vec[20] = aux2 + aux1;
    vec[21] = aux4 + aux1;

    aux1        = vec[44];
    aux2        = vec[36];  /* vec[12] */
    aux3        += aux1;
    vec[42] = vec[22];
    vec[43] = vec[23];
    vec[22] = aux4 + aux3;
    vec[23] = aux2 + aux3;

    aux3        = vec[45];
    aux4        = vec[37];  /* vec[13] */

    /* -----------------------------------*/


    scratch_mem[0] = vec[24];
    scratch_mem[1] = vec[25];
    aux1        += aux3;
    vec[24] = aux2 + aux1;
    vec[25] = aux4 + aux1;

    aux1        = vec[46];
    aux2        = vec[32];  /* vec[14] */
    scratch_mem[2] = vec[26];
    scratch_mem[3] = vec[27];
    aux3        += aux1;
    vec[26] = aux4 + aux3;
    vec[27] = aux2 + aux3;

    aux3        = vec[47];
    aux4        = vec[33];  /* vec[15] */

    /* -----------------------------------*/
    scratch_mem[4] = vec[28];
    scratch_mem[5] = vec[29];
    aux1        += aux3;
    vec[28] = aux2 + aux1;
    vec[29] = aux4 + aux1;

    aux1        = vec[48];
    aux2        = vec[38];  /* vec[16] */
    scratch_mem[6] = vec[30];
    scratch_mem[7] = vec[31];
    aux3        += aux1;
    vec[30] = aux4 + aux3;
    vec[31] = aux2 + aux3;

    aux3        = vec[49];
    aux4        = vec[39];  /* vec[17] */

    /* -----------------------------------*/
    aux1        += aux3;
    vec[32] = aux2 + aux1;
    vec[33] = aux4 + aux1;

    aux1        = vec[50];
    aux2        = vec[34];  /* vec[18] */
    aux3        += aux1;
    vec[34] = aux4 + aux3;
    aux4        = vec[35];  /* vec[19] */
    vec[35] = aux2 + aux3;

    aux3        = vec[51];


    /* -----------------------------------*/
    aux1        += aux3;
    vec[36] = aux2 + aux1;
    vec[37] = aux4 + aux1;

    aux1        = vec[52];
    aux2        = vec[40];  /* vec[20] */
    aux3        += aux1;
    vec[38] = aux4 + aux3;
    vec[39] = aux2 + aux3;

    aux3        = vec[53];
    aux4        = vec[41];  /* vec[21] */

    /* -----------------------------------*/
    aux1        += aux3;
    vec[40] = aux2 + aux1;
    vec[41] = aux4 + aux1;

    aux1        = vec[54];
    aux2        = vec[42];  /* vec[22] */
    aux3        += aux1;
    vec[42] = aux4 + aux3;
    aux4        = vec[43];  /* vec[23] */
    vec[43] = aux2 + aux3;

    aux3        = vec[55];

    /* -----------------------------------*/

    pt_vec = &vec[44];
    temp_o1 = &vec[56];
    temp_e1 = &scratch_mem[0];

    for (i = 4; i != 0; i--)
    {
        aux1        += aux3;
        *(pt_vec++) = aux2 + aux1;
        *(pt_vec++) = aux4 + aux1;

        aux1        = *(temp_o1++);
        aux3        += aux1;
        aux2        = *(temp_e1++);
        *(pt_vec++) = aux4 + aux3;
        *(pt_vec++) = aux2 + aux3;

        aux3        = *(temp_o1++);
        aux4        = *(temp_e1++);
    }

    aux1       += aux3;
    vec[60] = aux2 + aux1;
    vec[61] = aux4 + aux1;
    vec[62] = aux4 + aux3;

}


#endif

/*----------------------------------------------------------------------------
; pv_split
----------------------------------------------------------------------------*/


void pv_split(Int32 *temp_o)
{

    Int32 i;
    const Int32 *pt_cosTerms = &CosTable_48[47];
    Int32 *pt_temp_o = temp_o;
    Int32 *pt_temp_e = pt_temp_o - 1;
    Int32 tmp1;
    Int32 tmp2;
    Int32 cosx;

    for (i = 8; i != 0; i--)
    {
        tmp2 = *(pt_temp_o);
        tmp1 = *(pt_temp_e);
        cosx = *(pt_cosTerms--);
        *(pt_temp_e--) =   tmp1  + tmp2;
        *(pt_temp_o++) = fxp_mul32_Q26((tmp1 - tmp2), cosx);
        tmp1 = *(pt_temp_e);
        tmp2 = *(pt_temp_o);
        cosx = *(pt_cosTerms--);
        *(pt_temp_e--) =   tmp1  + tmp2;
        *(pt_temp_o++) = fxp_mul32_Q26((tmp1 - tmp2), cosx);
    }
}



void pv_split_z(Int32 *vector)
{
    Int32 i;
    Int32 *pt_vector     = &vector[31];
    const Int32 *pt_cosTerms = &CosTable_48[32];
    Int32 *pt_temp_e = vector;
    Int32 tmp1;
    Int32 tmp2;
    Int32 cosx;

    for (i = 8; i != 0; i--)
    {
        tmp1 = *(pt_vector);
        tmp2 = *(pt_temp_e);
        cosx = *(pt_cosTerms++);
        *(pt_temp_e++) =   tmp1  + tmp2;
        *(pt_vector--) = fxp_mul32_Q26((tmp1 - tmp2), cosx);
        tmp2 = *(pt_temp_e);
        tmp1 = *(pt_vector);
        cosx = *(pt_cosTerms++);
        *(pt_temp_e++) =   tmp1  + tmp2;
        *(pt_vector--) = fxp_mul32_Q26((tmp1 - tmp2), cosx);
    }
}


void pv_merge_in_place_N32(Int32 vec[])
{

    Int32 temp[4];

    temp[0] = vec[14];
    vec[14] = vec[ 7];
    temp[1] = vec[12];
    vec[12] = vec[ 6];
    temp[2] = vec[10];
    vec[10] = vec[ 5];
    temp[3] = vec[ 8];
    vec[ 8] = vec[ 4];
    vec[ 6] = vec[ 3];
    vec[ 4] = vec[ 2];
    vec[ 2] = vec[ 1];

    vec[ 1] = vec[16] + vec[17];
    vec[16] = temp[3];
    vec[ 3] = vec[18] + vec[17];
    vec[ 5] = vec[19] + vec[18];
    vec[18] = vec[9];
    temp[3] = vec[11];

    vec[ 7] = vec[20] + vec[19];
    vec[ 9] = vec[21] + vec[20];
    vec[20] = temp[2];
    temp[2] = vec[13];
    vec[11] = vec[22] + vec[21];
    vec[13] = vec[23] + vec[22];
    vec[22] = temp[3];
    temp[3] = vec[15];
    vec[15] = vec[24] + vec[23];
    vec[17] = vec[25] + vec[24];
    vec[19] = vec[26] + vec[25];
    vec[21] = vec[27] + vec[26];
    vec[23] = vec[28] + vec[27];
    vec[25] = vec[29] + vec[28];
    vec[27] = vec[30] + vec[29];
    vec[29] = vec[30] + vec[31];
    vec[24] = temp[1];
    vec[26] = temp[2];
    vec[28] = temp[0];
    vec[30] = temp[3];
}

#endif


