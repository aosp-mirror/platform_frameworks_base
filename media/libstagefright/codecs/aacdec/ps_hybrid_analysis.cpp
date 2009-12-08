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

 Filename: ps_hybrid_analysis.c


------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS



------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    Does Hybrid analysis:

        Get higher frequency resolution in the lower QMF subbands
        creating sub-subbands. This is done by low frequency filtering.
        Lower QMF subbands are further split in order to obtain a higher
        frequency resolution, enabling a proper stereo analysis and synthesis
        for the lower frequencies.
        Two hybrid are defined. Both filters have length 13 and a delay of 6.
        In this implementation, the symmetry of the filters helps to simplify
        the design.


   Increase Freq. Resolution
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



          subband k             QMF channel
             0   .................  0      -----------
             1   .................  0
             2   .................  0
             3   .................  0
             4   .................  0
             5   .................  0        Sub-QMF  ( Increase Freq. Resolution)
             6   .................  1
             7   .................  1
             8   .................  2
             9   .................  2
            10   .................  3      -----------
            11   .................  4
            12   .................  5
            13   .................  6
            14   .................  7
            15   .................  8         QMF
           16-17 .................  9-10
           18-20 ................. 11-13
           21-24 ................. 14-17
           25-29 ................. 18-22
           30-41 ................. 23-34
           42-70 ................. 35-63   -----------

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

#include    "s_hybrid.h"
#include    "aac_mem_funcs.h"
#include    "ps_channel_filtering.h"
#include    "ps_hybrid_analysis.h"

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

void ps_hybrid_analysis(const Int32 mQmfReal[][64],
                        const Int32 mQmfImag[][64],
                        Int32 *mHybridReal,
                        Int32 *mHybridImag,
                        HYBRID *pHybrid,
                        Int32 scratch_mem[],
                        Int32 i)

{

    Int32 band;
    HYBRID_RES hybridRes;
    Int32  chOffset = 0;

    Int32 *ptr_mHybrid_Re;
    Int32 *ptr_mHybrid_Im;

    Int32 *pt_mQmfBufferReal;
    Int32 *pt_mQmfBufferImag;

    pt_mQmfBufferReal = &scratch_mem[32 + i];

    for (band = 0; band < pHybrid->nQmfBands; band++)
    {
        pt_mQmfBufferImag = pt_mQmfBufferReal + 44;


        pt_mQmfBufferReal[HYBRID_FILTER_LENGTH_m_1] = mQmfReal[HYBRID_FILTER_DELAY][band];
        pt_mQmfBufferImag[HYBRID_FILTER_LENGTH_m_1] = mQmfImag[HYBRID_FILTER_DELAY][band];


        ptr_mHybrid_Re = &mHybridReal[ chOffset];
        ptr_mHybrid_Im = &mHybridImag[ chOffset];

        hybridRes = (HYBRID_RES)pHybrid->pResolution[band];
        switch (hybridRes)
        {
                /*
                 *  For QMF band = 1  and  2
                 */

            case HYBRID_2_REAL:

                two_ch_filtering(pt_mQmfBufferReal,
                                 pt_mQmfBufferImag,
                                 ptr_mHybrid_Re,
                                 ptr_mHybrid_Im);
                chOffset += 2;

                break;

                /*
                 *  For QMF band = 0
                 */

            case HYBRID_8_CPLX:

                eight_ch_filtering(pt_mQmfBufferReal,
                                   pt_mQmfBufferImag,
                                   pHybrid->mTempReal,
                                   pHybrid->mTempImag,
                                   scratch_mem);

                pv_memmove(ptr_mHybrid_Re, pHybrid->mTempReal, 4*sizeof(*pHybrid->mTempReal));

                ptr_mHybrid_Re += 2;

                *(ptr_mHybrid_Re++) +=  pHybrid->mTempReal[5];
                *(ptr_mHybrid_Re++) +=  pHybrid->mTempReal[4];
                *(ptr_mHybrid_Re++)  =  pHybrid->mTempReal[6];
                *(ptr_mHybrid_Re)  =  pHybrid->mTempReal[7];

                pv_memmove(ptr_mHybrid_Im, pHybrid->mTempImag, 4*sizeof(*pHybrid->mTempImag));
                ptr_mHybrid_Im += 2;

                *(ptr_mHybrid_Im++) +=  pHybrid->mTempImag[5];
                *(ptr_mHybrid_Im++) +=  pHybrid->mTempImag[4];
                *(ptr_mHybrid_Im++)  =  pHybrid->mTempImag[6];
                *(ptr_mHybrid_Im)  =  pHybrid->mTempImag[7];

                chOffset += 6;

                break;

            default:
                ;
        }

        pt_mQmfBufferReal = pt_mQmfBufferImag + 44;

    }


}
#endif


#endif

