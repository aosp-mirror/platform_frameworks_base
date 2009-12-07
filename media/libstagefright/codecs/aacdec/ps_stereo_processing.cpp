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

 Filename: ps_stereo_processing.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS



------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

        Stereo Process or reconstruction

           l_k(n) = H11(k,n)*s_k(n) + H21(k,n)*d_k(n)

           r_k(n) = H12(k,n)*s_k(n) + H22(k,n)*d_k(n)

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
#include    "ps_stereo_processing.h"
#include    "fxp_mul32.h"
#include    "ps_all_pass_filter_coeff.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/

#ifndef min
#define min(a, b) ((a) < (b) ? (a) : (b))
#endif

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

void ps_stereo_processing(STRUCT_PS_DEC  *pms,
                          Int32          *qmfLeftReal,
                          Int32          *qmfLeftImag,
                          Int32          *qmfRightReal,
                          Int32          *qmfRightImag)
{
    Int32     group;
    Int32     subband;
    Int32     maxSubband;
    Int32     usb;
    Char     index;


    Int32  *hybrLeftReal;
    Int32  *hybrLeftImag;
    Int32  *hybrRightReal;
    Int32  *hybrRightImag;
    Int32  *ptr_hybrLeftReal;
    Int32  *ptr_hybrLeftImag;
    Int32  *ptr_hybrRightReal;
    Int32  *ptr_hybrRightImag;


    Int16   h11;
    Int16   h12;
    Int16   h21;
    Int16   h22;

    Int32   temp1;
    Int32   temp2;
    Int32   temp3;

    usb = pms->usb;

    /*
     *   Complete Linear interpolation
     */

    hybrLeftReal  = pms->mHybridRealLeft;
    hybrLeftImag  = pms->mHybridImagLeft;
    hybrRightReal = pms->mHybridRealRight;
    hybrRightImag = pms->mHybridImagRight;

    for (group = 0; group < SUBQMF_GROUPS; group++)     /* SUBQMF_GROUPS == 10 */
    {

        temp1 = pms->deltaH11[group];
        temp2 = pms->deltaH12[group];

        pms->H11[group]  += temp1;
        h11  = (Int16)(pms->H11[group] >> 16);
        pms->H12[group]  += temp2;
        h12  = (Int16)(pms->H12[group] >> 16);

        temp1 = pms->deltaH21[group];
        temp2 = pms->deltaH22[group];

        pms->H21[group]  += temp1;
        h21  = (Int16)(pms->H21[group] >> 16);
        pms->H22[group]  += temp2;
        h22  = (Int16)(pms->H22[group] >> 16);

        index = groupBorders[group];

        /*
         *  Reconstruction of Stereo sub-band signal
         *
         *  l_k(n) = H11(k,n)*s_k(n) + H21(k,n)*d_k(n)
         *
         *  r_k(n) = H12(k,n)*s_k(n) + H22(k,n)*d_k(n)
         */
        ptr_hybrLeftReal  = &hybrLeftReal[  index];
        ptr_hybrRightReal = &hybrRightReal[ index];

        temp1 = *(ptr_hybrLeftReal) << 1;
        temp2 = *(ptr_hybrRightReal) << 1;

        temp3 = fxp_mul32_by_16(temp1, h11);
        *(ptr_hybrLeftReal)  = fxp_mac32_by_16(temp2, h21, temp3) << 1;

        temp3 = fxp_mul32_by_16(temp1, h12);
        *(ptr_hybrRightReal) = fxp_mac32_by_16(temp2, h22, temp3) << 1;


        ptr_hybrLeftImag  = &hybrLeftImag[  index];
        ptr_hybrRightImag = &hybrRightImag[ index];

        temp1 = *(ptr_hybrLeftImag) << 1;
        temp2 = *(ptr_hybrRightImag) << 1;

        temp3 = fxp_mul32_by_16(temp1, h11);
        *(ptr_hybrLeftImag)  = fxp_mac32_by_16(temp2, h21, temp3) << 1;

        temp3 = fxp_mul32_by_16(temp1, h12);
        *(ptr_hybrRightImag) = fxp_mac32_by_16(temp2, h22, temp3) << 1;


    } /* groups loop */

    temp1 = pms->deltaH11[SUBQMF_GROUPS];
    temp2 = pms->deltaH12[SUBQMF_GROUPS];

    pms->H11[SUBQMF_GROUPS]  += temp1;
    h11  = (Int16)(pms->H11[SUBQMF_GROUPS] >> 16);
    pms->H12[SUBQMF_GROUPS]  += temp2;
    h12  = (Int16)(pms->H12[SUBQMF_GROUPS] >> 16);

    temp1 = pms->deltaH21[SUBQMF_GROUPS];
    temp2 = pms->deltaH22[SUBQMF_GROUPS];

    pms->H21[SUBQMF_GROUPS]  += temp1;
    h21  = (Int16)(pms->H21[SUBQMF_GROUPS] >> 16);
    pms->H22[SUBQMF_GROUPS]  += temp2;
    h22  = (Int16)(pms->H22[SUBQMF_GROUPS] >> 16);


    ptr_hybrLeftReal  = &qmfLeftReal[  3];
    ptr_hybrRightReal = &qmfRightReal[ 3];

    /*
     *  Reconstruction of Stereo sub-band signal
     *
     *  l_k(n) = H11(k,n)*s_k(n) + H21(k,n)*d_k(n)
     *
     *  r_k(n) = H12(k,n)*s_k(n) + H22(k,n)*d_k(n)
     */
    temp1 = *(ptr_hybrLeftReal) << 1;
    temp2 = *(ptr_hybrRightReal) << 1;


    temp3 = fxp_mul32_by_16(temp1, h11);
    *(ptr_hybrLeftReal)  = fxp_mac32_by_16(temp2, h21, temp3) << 1;

    temp3 = fxp_mul32_by_16(temp1, h12);
    *(ptr_hybrRightReal)  = fxp_mac32_by_16(temp2, h22, temp3) << 1;

    ptr_hybrLeftImag  = &qmfLeftImag[  3];
    ptr_hybrRightImag = &qmfRightImag[ 3];


    temp1 = *(ptr_hybrLeftImag) << 1;
    temp2 = *(ptr_hybrRightImag) << 1;

    temp3 = fxp_mul32_by_16(temp1, h11);
    *(ptr_hybrLeftImag)  = fxp_mac32_by_16(temp2, h21, temp3) << 1;

    temp3 = fxp_mul32_by_16(temp1, h12);
    *(ptr_hybrRightImag)  = fxp_mac32_by_16(temp2, h22, temp3) << 1;


    for (group = SUBQMF_GROUPS + 1; group < NO_IID_GROUPS; group++)   /* 11 to NO_IID_GROUPS == 22 */
    {
        temp1 = pms->deltaH11[group];
        temp2 = pms->deltaH12[group];

        pms->H11[group]  += temp1;
        h11  = (Int16)(pms->H11[group] >> 16);
        pms->H12[group]  += temp2;
        h12  = (Int16)(pms->H12[group] >> 16);

        temp1 = pms->deltaH21[group];
        temp2 = pms->deltaH22[group];

        pms->H21[group]  += temp1;
        h21  = (Int16)(pms->H21[group] >> 16);
        pms->H22[group]  += temp2;
        h22  = (Int16)(pms->H22[group] >> 16);

        index = groupBorders[group];
        maxSubband = groupBorders[group + 1];
        maxSubband = min(usb, maxSubband);

        /*
         *  Reconstruction of Stereo sub-band signal
         *
         *  l_k(n) = H11(k,n)*s_k(n) + H21(k,n)*d_k(n)
         *
         *  r_k(n) = H12(k,n)*s_k(n) + H22(k,n)*d_k(n)
         */

        ptr_hybrLeftReal  = &qmfLeftReal[  index];
        ptr_hybrRightReal = &qmfRightReal[ index];

        for (subband = index; subband < maxSubband; subband++)
        {
            temp1 = *(ptr_hybrLeftReal) << 1;
            temp2 = *(ptr_hybrRightReal) << 1;
            temp3 = fxp_mul32_by_16(temp1, h11);
            *(ptr_hybrLeftReal++)   = fxp_mac32_by_16(temp2, h21, temp3) << 1;

            temp3 = fxp_mul32_by_16(temp1, h12);
            *(ptr_hybrRightReal++)  = fxp_mac32_by_16(temp2, h22, temp3) << 1;
        }

        ptr_hybrLeftImag  = &qmfLeftImag[  index];
        ptr_hybrRightImag = &qmfRightImag[ index];

        for (subband = index; subband < maxSubband; subband++)
        {
            temp1 = *(ptr_hybrLeftImag) << 1;
            temp2 = *(ptr_hybrRightImag) << 1;
            temp3 = fxp_mul32_by_16(temp1, h11);
            *(ptr_hybrLeftImag++)   = fxp_mac32_by_16(temp2, h21, temp3) << 1;

            temp3 = fxp_mul32_by_16(temp1, h12);
            *(ptr_hybrRightImag++)  = fxp_mac32_by_16(temp2, h22, temp3) << 1;

        } /* subband loop */

    } /* groups loop */

} /* END ps_stereo_processing */


#endif


#endif

