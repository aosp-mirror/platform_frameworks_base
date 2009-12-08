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

 Filename: sbr_aliasing_reduction.c

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



#include    "sbr_aliasing_reduction.h"
#include    "pv_sqrt.h"

#include    "aac_mem_funcs.h"

#include    "pv_div.h"
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

#define Q30fmt(x)   (Int32)(x*((Int32)1<<30) + (x>=0?0.5F:-0.5F))

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

#include "pv_normalize.h"
#include  "sbr_constants.h"

/*******************************************************************************
 Functionname:  sbr_aliasing_reduction
 *******************************************************************************
 Description:
 Arguments:

 Return:        none
*******************************************************************************/
void sbr_aliasing_reduction(Int32 *degreeAlias,
                            Int32  * nrg_gain_man,
                            Int32  * nrg_gain_exp,
                            Int32  * nrg_est_man,
                            Int32  * nrg_est_exp,
                            Int32  * dontUseTheseGainValues,
                            Int32    noSubbands,
                            Int32    lowSubband,
                            Int32  sqrt_cache[][4],
                            Int32 * groupVector)
{

    Int32 temp1;
    Int32 est_total;
    Int32 ref_total_man;
    Int32 ref_total_exp;
    Int32 tmp_q1;
    Int32 tmp_q2;
    Int32 tmp_q3;
    Int32 tmp_q4;
    Int32 bst_man;
    Int32 bst_exp;
    struct intg_div   quotient;
    struct intg_sqrt  root_sq;
    Int32 group;
    Int32 grouping = 0;
    Int32 index = 0;
    Int32 noGroups;
    Int32 k;


    /* Calculate grouping*/
    for (k = 0; k < noSubbands - 1; k++)
    {
        if (degreeAlias[k + lowSubband + 1] && dontUseTheseGainValues[k] == 0)
        {
            if (grouping == 0)
            {
                groupVector[index] = k + lowSubband;
                grouping = 1;
                index++;
            }
        }
        else
        {
            if (grouping)
            {
                groupVector[index] = k + lowSubband;

                if (! dontUseTheseGainValues[k])
                {
                    (groupVector[index])++;
                }
                grouping = 0;
                index++;
            }
        }
    }

    if (grouping)
    {
        groupVector[index] = noSubbands + lowSubband;
        index++;
    }
    noGroups = (index >> 1);



    /*Calculate new gain*/
    for (group = 0; group < noGroups; group ++)
    {

        int startGroup = groupVector[(group<<1)] - lowSubband;
        int stopGroup  = groupVector[(group<<1)+1] - lowSubband;


        est_total = 0;
        ref_total_man = 0;

        tmp_q1 = -100;
        tmp_q2 = -100;

        for (k = startGroup; k < stopGroup; k++)
        {
            if (tmp_q1 < nrg_est_exp[k])
            {
                tmp_q1 = nrg_est_exp[k];    /* max */
            }
            if (tmp_q2 < (nrg_est_exp[k] + (nrg_gain_exp[k] << 1)))
            {
                tmp_q2 = (nrg_est_exp[k] + (nrg_gain_exp[k] << 1));  /* max */
            }
        }


        k -= startGroup;        /*  number of element used in the addition */
        /* adjust Q format */
        tmp_q2 += 59 - pv_normalize(k);

        for (k = startGroup; k < stopGroup; k++)
        {
            /*
             *  est_total += nrg_est[k]
             *  ref_total += nrg_est[k]*nrg_gain[k]*nrg_gain[k
             */
            est_total += nrg_est_man[k] >> (tmp_q1 - nrg_est_exp[k]);

            if (tmp_q2 - (nrg_est_exp[k] + (nrg_gain_exp[k] << 1)) < 60)
            {
                nrg_gain_man[k] = fxp_mul32_Q28(nrg_gain_man[k], nrg_gain_man[k]);
                nrg_gain_exp[k] = (nrg_gain_exp[k] << 1) + 28;
                tmp_q3          = fxp_mul32_Q28(nrg_gain_man[k], nrg_est_man[k]);
                ref_total_man    += tmp_q3 >> (tmp_q2 - (nrg_est_exp[k] + nrg_gain_exp[k]));
            }
        }

        ref_total_exp = tmp_q2 + 28;

        pv_div(ref_total_man, est_total, &quotient);

        tmp_q2 += - tmp_q1 - quotient.shift_factor - 2;



        for (k = startGroup; k < stopGroup; k++)
        {
            Int32 alpha;
            temp1 = k + lowSubband;
            if (k < noSubbands - 1)
            {
                alpha = degreeAlias[temp1 + 1] > degreeAlias[temp1 ] ?
                        degreeAlias[temp1 + 1] : degreeAlias[temp1 ];
            }
            else
            {
                alpha = degreeAlias[temp1];
            }

            /*
             *  nrg_gain[k] = alpha*newGain + (1.0f-alpha)*nrg_gain[k]*nrg_gain[k];
             */

            tmp_q1 = tmp_q2 > nrg_gain_exp[k] ? tmp_q2 : nrg_gain_exp[k];
            tmp_q1++;

            tmp_q3 = fxp_mul32_Q30(alpha, quotient.quotient);
            tmp_q4 = fxp_mul32_Q30(Q30fmt(1.0f) - alpha, nrg_gain_man[k]);

            nrg_gain_man[k] = (tmp_q3 >> (tmp_q1 - tmp_q2)) +
                              (tmp_q4 >> (tmp_q1 - nrg_gain_exp[k]));

            nrg_gain_exp[k] = tmp_q1;
        }


        bst_exp = -100;

        for (k = startGroup; k < stopGroup; k++)
        {
            if (bst_exp < nrg_gain_exp[k] + nrg_est_exp[k])
            {
                bst_exp = nrg_gain_exp[k] + nrg_est_exp[k];    /* max */
            }
        }

        k -= startGroup;        /*  number of element used in the addition */

        while (k != 0)          /*  bit guard protection depends on log2(k)  */
        {
            k >>= 1;
            bst_exp++;           /*  add extra bit-overflow-guard */
        }

        bst_man = 0;

        for (k = startGroup; k < stopGroup; k++)
        {
            tmp_q2 =  fxp_mul32_Q28(nrg_gain_man[k], nrg_est_man[k]);
            bst_man +=  tmp_q2 >> (bst_exp - nrg_gain_exp[k] - nrg_est_exp[k]);
        }

        bst_exp += 28;  /* compensate for shift down */

        if (bst_man)
        {
            /*
             *  bst = ref_total / bst
             */

            pv_div(ref_total_man, bst_man, &quotient);
            bst_exp = ref_total_exp - bst_exp - quotient.shift_factor - 30;
            bst_man = quotient.quotient;      /*  Q30 */

            for (k = startGroup; k < stopGroup; k++)
            {
                tmp_q1 = fxp_mul32_Q30(bst_man, nrg_gain_man[k]);
                pv_sqrt(tmp_q1, (bst_exp + nrg_gain_exp[k] + 60), &root_sq, sqrt_cache[0]);
                nrg_gain_man[k] = root_sq.root;
                nrg_gain_exp[k] = root_sq.shift_factor;
            }
        }
        else
        {
            pv_memset((void *)&nrg_gain_man[startGroup],
                      0,
                      (stopGroup - startGroup)*sizeof(nrg_gain_man[0]));

            pv_memset((void *)&nrg_gain_exp[startGroup],
                      0,
                      (stopGroup - startGroup)*sizeof(nrg_gain_exp[0]));

        }

    }
}

#endif



