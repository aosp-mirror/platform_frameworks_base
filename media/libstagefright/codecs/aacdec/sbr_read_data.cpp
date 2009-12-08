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

 Filename: sbr_read_data.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

    INPUT

    SBRDECODER self,
    SBRBITSTREAM * stream,
    float *timeData,
    int numChannels

    OUTPUT

    errorCode, noError if successful

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

        sbr decoder processing, set up SBR decoder phase 2 in case of
        different cotrol data

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


#include    "sbr_read_data.h"
#include    "s_bit_buffer.h"
#include    "buf_getbits.h"
#include    "sbr_get_sce.h"
#include    "sbr_get_cpe.h"
#include    "sbr_reset_dec.h"
#include    "sbr_get_header_data.h"
#include    "sbr_crc_check.h"
#include    "aac_mem_funcs.h"


#include    "init_sbr_dec.h"  /*  !!! */


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

SBR_ERROR sbr_read_data(SBRDECODER_DATA * self,
                        SBR_DEC * sbrDec,
                        SBRBITSTREAM *stream)
{
    SBR_ERROR sbr_err =  SBRDEC_OK;
    int32_t SbrFrameOK = 1;
    int32_t sbrCRCAlwaysOn = 0;

    UInt32 bs_header_flag = 0;

    SBR_HEADER_STATUS headerStatus = HEADER_OK;

    SBR_CHANNEL *SbrChannel = self->SbrChannel;

    int32_t zeropadding_bits;

    BIT_BUFFER bitBuf ;

    /*
     *  evaluate Bitstream
     */

    bitBuf.buffer_word    = 0;
    bitBuf.buffered_bits  = 0;
    bitBuf.nrBitsRead     = 0;

    bitBuf.char_ptr  =  stream->sbrElement[0].Data;
    bitBuf.bufferLen = (stream->sbrElement[0].Payload) << 3;


    /*
     *  we have to skip a nibble because the first element of Data only
     *  contains a nibble of data !
     */
    buf_getbits(&bitBuf, LEN_NIBBLE);

    if ((stream->sbrElement[0].ExtensionType == SBR_EXTENSION_CRC) ||
            sbrCRCAlwaysOn)
    {
        int32_t CRCLen = ((stream->sbrElement[0].Payload - 1) << 3) + 4 - SI_SBR_CRC_BITS;
        SbrFrameOK = sbr_crc_check(&bitBuf, CRCLen);
    }


    if (SbrFrameOK)
    {
        /*
         *  The sbr data seems ok, if the header flag is set we read the header
         *  and check if vital parameters have changed since the previous frame.
         *  If the syncState equals UPSAMPLING, the SBR Tool has not been
         *  initialised by SBR header data, and can only do upsampling
         */

        bs_header_flag = buf_getbits(&bitBuf, 1);  /* read Header flag */

        if (bs_header_flag)
        {
            /*
             *  If syncState == SBR_ACTIVE, it means that we've had a SBR header
             *  before, and we will compare with the previous header to see if a
             *  reset is required. If the syncState equals UPSAMPLING this means
             *  that the SBR-Tool so far is only initialised to do upsampling
             *  and hence we need to do a reset, and initialise the system
             *  according to the present header.
             */

            headerStatus = sbr_get_header_data(&(SbrChannel[0].frameData.sbr_header),
                                               &bitBuf,
                                               SbrChannel[0].syncState);
        }     /* if (bs_header_flag) */


        switch (stream->sbrElement[0].ElementID)
        {
            case SBR_ID_SCE :

                /* change of control data, reset decoder */
                if (headerStatus == HEADER_RESET)
                {
                    sbr_err = sbr_reset_dec(&(SbrChannel[0].frameData),
                                            sbrDec,
                                            self->SbrChannel[0].frameData.sbr_header.sampleRateMode);

                    if (sbr_err != SBRDEC_OK)
                    {
                        break;
                    }
                    /*
                     * At this point we have a header and the system has been reset,
                     * hence syncState from now on will be SBR_ACTIVE.
                     */
                    SbrChannel[0].syncState     = SBR_ACTIVE;
                }

                if ((SbrChannel[0].syncState == SBR_ACTIVE))
                {
                    sbr_err = sbr_get_sce(&(SbrChannel[0].frameData),
                                          &bitBuf
#ifdef PARAMETRICSTEREO
                                          , self->hParametricStereoDec
#endif
                                         );

                    if (sbr_err != SBRDEC_OK)
                    {
                        break;
                    }
                }

                break;

            case SBR_ID_CPE :

                if (bs_header_flag)
                {
                    pv_memcpy(&(SbrChannel[1].frameData.sbr_header),
                              &(SbrChannel[0].frameData.sbr_header),
                              sizeof(SBR_HEADER_DATA));
                }

                /* change of control data, reset decoder */
                if (headerStatus == HEADER_RESET)
                {
                    for (int32_t lr = 0 ; lr < 2 ; lr++)
                    {
                        sbr_err = sbr_reset_dec(&(SbrChannel[lr].frameData),
                                                sbrDec,
                                                self->SbrChannel[0].frameData.sbr_header.sampleRateMode);

                        if (sbr_err != SBRDEC_OK)
                        {
                            break;
                        }

                        SbrChannel[lr].syncState = SBR_ACTIVE;
                    }
                }

                if (SbrChannel[0].syncState == SBR_ACTIVE)
                {
                    sbr_err = sbr_get_cpe(&(SbrChannel[0].frameData),
                                          &(SbrChannel[1].frameData),
                                          &bitBuf);

                    if (sbr_err != SBRDEC_OK)
                    {
                        break;
                    }

                }
                break;

            default:
                sbr_err = SBRDEC_ILLEGAL_PLUS_ELE_ID;
                break;
        }

    }           /* if (SbrFrameOK) */

    /*
     *  Check that the bits read did not go beyond SBR frame boundaries
     */

    zeropadding_bits = (8 - (bitBuf.nrBitsRead & 0x7)) & 0x7;

    if ((bitBuf.nrBitsRead + zeropadding_bits)  > bitBuf.bufferLen)
    {
        sbr_err = SBRDEC_INVALID_BITSTREAM;
    }

    return sbr_err;
}


#endif

