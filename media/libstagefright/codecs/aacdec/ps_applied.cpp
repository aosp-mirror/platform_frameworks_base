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

 Filename: ps_applied.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS



------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

        Applies Parametric Stereo Tool to a QMF-analized mono signal
        providing a stereo image as output


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
#include    "aac_mem_funcs.h"
#include    "ps_stereo_processing.h"
#include    "ps_decorrelate.h"
#include    "ps_hybrid_synthesis.h"
#include    "ps_hybrid_analysis.h"
#include    "ps_applied.h"

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

void ps_applied(STRUCT_PS_DEC *h_ps_dec,
                Int32 rIntBufferLeft[][64],
                Int32 iIntBufferLeft[][64],
                Int32 *rIntBufferRight,
                Int32 *iIntBufferRight,
                Int32 scratch_mem[],
                Int32 band)

{

    /*
     *  Get higher frequency resolution in the lower QMF subbands
     *  creating sub-subbands
     */
    ps_hybrid_analysis(rIntBufferLeft,
                       iIntBufferLeft,
                       h_ps_dec->mHybridRealLeft,
                       h_ps_dec->mHybridImagLeft,
                       h_ps_dec->hHybrid,
                       scratch_mem,
                       band);

    /*
     *  By means of delaying and all-pass filtering, sub-subbands of
     *  left ch. are decorrelate to creates right ch. sub-subbands
     */

    ps_decorrelate(h_ps_dec,
                   *rIntBufferLeft,
                   *iIntBufferLeft,
                   rIntBufferRight,
                   iIntBufferRight,
                   scratch_mem);

    /*
     *  sub-subbands of left and right ch. are processed according to
     *  stereo clues.
     */

    ps_stereo_processing(h_ps_dec,
                         *rIntBufferLeft,
                         *iIntBufferLeft,
                         rIntBufferRight,
                         iIntBufferRight);

    /*
     *  Reconstruct stereo signals
     */

    ps_hybrid_synthesis((const Int32*)h_ps_dec->mHybridRealLeft,
                        (const Int32*)h_ps_dec->mHybridImagLeft,
                        *rIntBufferLeft,
                        *iIntBufferLeft,
                        h_ps_dec->hHybrid);

    ps_hybrid_synthesis((const Int32*)h_ps_dec->mHybridRealRight,
                        (const Int32*)h_ps_dec->mHybridImagRight,
                        rIntBufferRight,
                        iIntBufferRight,
                        h_ps_dec->hHybrid);

}/* END ps_applied */
#endif


#endif

