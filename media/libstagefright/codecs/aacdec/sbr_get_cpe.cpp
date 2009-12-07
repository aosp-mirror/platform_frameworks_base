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

 Filename: sbr_get_cpe.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Arguments:     hFrameDataLeft  - handle to struct SBR_FRAME_DATA for first channel
                hFrameDataRight - handle to struct SBR_FRAME_DATA for first channel
                hBitBuf         - handle to struct BIT_BUF

 Return:        SbrFrameOK


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


#include    "sbr_get_cpe.h"
#include    "buf_getbits.h"
#include    "extractframeinfo.h"
#include    "sbr_get_dir_control_data.h"
#include    "sbr_get_envelope.h"
#include    "sbr_get_noise_floor_data.h"
#include    "sbr_get_additional_data.h"
#include    "sbr_extract_extended_data.h"
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


SBR_ERROR sbr_get_cpe(SBR_FRAME_DATA * hFrameDataLeft,
                      SBR_FRAME_DATA * hFrameDataRight,
                      BIT_BUFFER  * hBitBuf)
{
    Int32 i;
    Int32 bits;
    SBR_ERROR err =  SBRDEC_OK;

    /* reserved bits */
    bits = buf_getbits(hBitBuf, SI_SBR_RESERVED_PRESENT);

    if (bits)
    {
        buf_getbits(hBitBuf, SI_SBR_RESERVED_BITS_DATA);
        buf_getbits(hBitBuf, SI_SBR_RESERVED_BITS_DATA);
    }

    /* Read coupling flag */
    bits = buf_getbits(hBitBuf, SI_SBR_COUPLING_BITS);

    if (bits)
    {
        hFrameDataLeft->coupling = COUPLING_LEVEL;
        hFrameDataRight->coupling = COUPLING_BAL;
    }
    else
    {
        hFrameDataLeft->coupling = COUPLING_OFF;
        hFrameDataRight->coupling = COUPLING_OFF;
    }


    err = extractFrameInfo(hBitBuf, hFrameDataLeft);

    if (err != SBRDEC_OK)
    {
        return err;
    }

    if (hFrameDataLeft->coupling)
    {

        pv_memcpy(hFrameDataRight->frameInfo,
                  hFrameDataLeft->frameInfo,
                  LENGTH_FRAME_INFO * sizeof(Int32));

        hFrameDataRight->nNoiseFloorEnvelopes = hFrameDataLeft->nNoiseFloorEnvelopes;
        hFrameDataRight->frameClass = hFrameDataLeft->frameClass;


        sbr_get_dir_control_data(hFrameDataLeft, hBitBuf);
        sbr_get_dir_control_data(hFrameDataRight, hBitBuf);

        for (i = 0; i < hFrameDataLeft->nNfb; i++)
        {
            hFrameDataLeft->sbr_invf_mode_prev[i]  = hFrameDataLeft->sbr_invf_mode[i];
            hFrameDataRight->sbr_invf_mode_prev[i] = hFrameDataRight->sbr_invf_mode[i];

            hFrameDataLeft->sbr_invf_mode[i]  = (INVF_MODE) buf_getbits(hBitBuf, SI_SBR_INVF_MODE_BITS);
            hFrameDataRight->sbr_invf_mode[i] = hFrameDataLeft->sbr_invf_mode[i];
        }

        sbr_get_envelope(hFrameDataLeft, hBitBuf);
        sbr_get_noise_floor_data(hFrameDataLeft, hBitBuf);
        sbr_get_envelope(hFrameDataRight, hBitBuf);

    }
    else
    {
        err = extractFrameInfo(hBitBuf, hFrameDataRight);

        if (err != SBRDEC_OK)
        {
            return err;
        }


        sbr_get_dir_control_data(hFrameDataLeft,  hBitBuf);
        sbr_get_dir_control_data(hFrameDataRight, hBitBuf);

        for (i = 0; i <  hFrameDataLeft->nNfb; i++)
        {
            hFrameDataLeft->sbr_invf_mode_prev[i]  = hFrameDataLeft->sbr_invf_mode[i];
            hFrameDataLeft->sbr_invf_mode[i]  =
                (INVF_MODE) buf_getbits(hBitBuf, SI_SBR_INVF_MODE_BITS);
        }

        for (i = 0; i <  hFrameDataRight->nNfb; i++)
        {
            hFrameDataRight->sbr_invf_mode_prev[i] = hFrameDataRight->sbr_invf_mode[i];

            hFrameDataRight->sbr_invf_mode[i] =
                (INVF_MODE) buf_getbits(hBitBuf, SI_SBR_INVF_MODE_BITS);
        }
        sbr_get_envelope(hFrameDataLeft,  hBitBuf);
        sbr_get_envelope(hFrameDataRight, hBitBuf);

        sbr_get_noise_floor_data(hFrameDataLeft,  hBitBuf);

    }

    sbr_get_noise_floor_data(hFrameDataRight, hBitBuf);

    pv_memset((void *)hFrameDataLeft->addHarmonics,
              0,
              hFrameDataLeft->nSfb[HI]*sizeof(Int32));

    pv_memset((void *)hFrameDataRight->addHarmonics,
              0,
              hFrameDataRight->nSfb[HI]*sizeof(Int32));

    sbr_get_additional_data(hFrameDataLeft, hBitBuf);
    sbr_get_additional_data(hFrameDataRight, hBitBuf);

    sbr_extract_extended_data(hBitBuf
#ifdef PARAMETRICSTEREO
                              , NULL
#endif
                             );

    return SBRDEC_OK;

}

#endif


