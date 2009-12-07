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

 Pathname: get_GA_specific_config.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Modified per review comments

 Description: Change getbits.h to ibstream.h

 Description: (1) use enum type for audioObjectType (2) update revision history

 Description: Updated the SW template to include the full pathname to the
 source file and a slightly modified copyright header.

 Description: Updated to use scratch memory for the temporary prog config.

 Description: Replace some instances of getbits to get1bits
              when only 1 bit is read.

 Who:                               Date:
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
        pVars   = pointer to the structure that holds all information for
                  this instance of the library. pVars->prog_config
                  pVars->mc_info, pVars->pWinSeqInfo, pVars->SFBWidth128
                  are needed for calling set_mc_info.
                  Data type pointer to tDec_Int_File

        channel_config = variable that indicates the channel configuration
                         information, in this decoder library, only values
                         0, 1, and 2 are allowed.
                         Data type UInt

        audioObjectType = variable that indicates the Audio Object Type.
                          Data type UInt.

        pInputStream = pointer to a BITS structure that holds information
                       regarding the input stream.

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    status = 0 if success
             1 otherwise

 Pointers and Buffers Modified:
    pVars->mc_info contents are updated with channel information.
    if infoinit is called within set_mc_info, then
    pVars->pWinSeqInfo contents are updated with window information.
    pVars->SFBWidth128 contents are updated with scale factor band width data.

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function takes the sampling_rate_idx, channel_config, and
 audioObjectType from AudioSpecificConfig() and set the decoder configuration
 necessary for the decoder to decode properly.
 It also reads the bitstream for frame length, scalable bitstream information
 and extension information to General Audio defined in MPEG-4 phase 1

------------------------------------------------------------------------------
 REQUIREMENTS

  This function shall not use global variables

------------------------------------------------------------------------------
 REFERENCES

 (1) ISO/IEC 14496-3: 1999(E)
 Part 3
 Subpart 1  p18     1.6 Interface to MPEG-4 Systems
 Subpart 4  p13     4.4.1 GA Specific Configuration
 Amendment  p10     6.2.1 AudioSpecificInfo
 Amendment  p78     8.2 Decoder configuration (GASpecificConfig)

 (2) AAC DecoderSpecificInfo Information
   PacketVideo descriptions - San Diego

------------------------------------------------------------------------------
 PSEUDO-CODE

    frameLenFlag = CALL getbits(
                            neededBits = LEN_FRAME_LEN_FLAG,
                            pInputStream = pInputStream);
                        MODIFYING (pInputStream)
                        RETURNING (frameLenFlag)

    dependsOnCoreCoder = CALL getbits(
                               neededBits = LEN_DEPEND_ON_CORE,
                               pInputStream = pInputStream);
                        MODIFYING (pInputStream)
                        RETURNING (dependsOnCoreCoder)

    IF (dependsOnCoreCoder != FALSE)
    THEN
        coreCoderDelay = CALL getbits(
                                neededBits = LEN_CORE_DELAY,
                                pInputStream = pInputStream);
                            MODIFYING (pInputStream)
                            RETURNING (coreCoderDelay)
    ENDIF

    extFlag = CALL getbits(
                      neededBits = LEN_EXT_FLAG,
                      pInputStream = pInputStream);
                   MODIFYING (pInputStream)
                   RETURNING (extFlag)

    IF (channel_config == 0)
    THEN
        status = CALL get_prog_config(
                        pVars = pVars,
                        pScratchPCE = &pVars->scratch_prog_config);
                   MODIFYING (pVars, pScratchPCE)
                   RETURNING (status)

    ELSE
        channel_config--;
        pVars->prog_config.front.ele_is_cpe[0] = channel_config;
        pVars->prog_config.front.ele_tag[0] = 0;

        status = CALL set_mc_info(
                        pMC_Info =  &(pVars->mc_info),
                        audioObjectType = audioObjectType,
                        sampling_rate_idx = pVars->prog_config.sampling_rate_idx,
                        tag = pVars->prog_config.front.ele_tag[0],
                        is_cpe = pVars->prog_config.front.ele_is_cpe[0],
                        pWinSeqInfo = pVars->pWinSeqInfo,
                        sfbwidth128 = pVars->SFBWidth128);
                    MODIFYING (pMC_Info, pWinSeqInfo, sfbwidth128)
                    RETURNING (SUCCESS)
    ENDIF

    IF ((audioObjectType == MP4AUDIO_AAC_SCALABLE) OR
        (audioObjectType == MP4AUDIO_ER_AAC_SCALABLE))
    THEN
        layer_num = CALL getbits(
                            neededBits = LEN_LAYER_NUM,
                            pInputStream = pInputStream);
                        MODIFYING (pInputStream)
                        RETURNING (layer_num)

        status = 1;
    ENDIF

    IF (extFlag != FALSE)
    THEN
         IF (audioObjectType == MP4AUDIO_ER_BSAC)
         THEN
              numOfSubFrame = CALL getbits(
                                     neededBits = LEN_SUB_FRAME,
                                     pInputStream = pInputStream);
                                MODIFYING (pInputStream)
                                RETURNING (numOfSubFrame)

              layer_len = CALL getbits(
                                neededBits = LEN_LAYER_LEN,
                                pInputStream = pInputStream);
                               MODIFYING (pInputStream)
                               RETURNING (layer_len)

         ENDIF

         IF (((audioObjectType > 16) AND (audioObjectType < 22)) OR
             (audioObjectType == 23))
         THEN
             aacSectionDataResilienceFlag =
                            CALL getbits(
                                    neededBits = LEN_SECT_RES_FLAG,
                                    pInputStream = pInputStream);
                                MODIFYING (pInputStream)
                                RETURNING (aacSectionDataResilienceFlag)

             aacScalefactorDataResilienceFlag =
                            CALL getbits(
                                    neededBits = LEN_SFB_RES_FLAG,
                                    pInputStream = pInputStream);
                                MODIFYING (pInputStream)
                                RETURNING (aacScalefactorDataResilienceFlag)

             aacSpectralDataResilienceFlag =
                            CALL getbits(
                                    neededBits = LEN_SPEC_RES_FLAG,
                                    pInputStream = pInputStream);
                                MODIFYING (pInputStream)
                                RETURNING (aacSpectralDataResilienceFlag)
         ENDIF

        status = 1;

    ENDIF

    RETURN status;

------------------------------------------------------------------------------
 RESOURCES USED
   When the code is written for a specific target processor the
     the resources used should be documented below.

 STACK USAGE: [stack count for this module] + [variable to represent
          stack usage for each subroutine called]

     where: [stack usage variable] = stack usage for [subroutine
         name] (see [filename].ext)

 DATA MEMORY USED: x words

 PROGRAM MEMORY USED: x words

 CLOCK CYCLES: [cycle count equation for this module] + [variable
           used to represent cycle count for each subroutine
           called]

     where: [cycle count variable] = cycle count for [subroutine
        name] (see [filename].ext)

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include    "pv_audio_type_defs.h"
#include    "e_mp4ff_const.h"
#include    "e_tmp4audioobjecttype.h"
#include    "s_tdec_int_file.h"
#include    "get_ga_specific_config.h"
#include    "set_mc_info.h"
#include    "get_prog_config.h"
#include    "ibstream.h"

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
Int get_GA_specific_config(
    tDec_Int_File * const pVars,
    BITS    *pInputStream,
    UInt     channel_config,
    const tMP4AudioObjectType audioObjectType
)
{

    Int status = SUCCESS;
    UInt dependsOnCoreCoder;
    /* Int coreCoderDelay; */
    UInt extFlag;

    /* These variables are left for future implementation */
    /* UInt layer_num; */
    /* UInt numOfSubFrame; */
    /* UInt layer_len; */
    /* UInt aacSectionDataResilienceFlag; */
    /* UInt aacScalefactorDataResilienceFlag; */
    /* UInt aacSpectralDataResilienceFlag; */
    Int  extFlag3;

    /*
     * frame length flag == 0, 1024 samples/frame
     * frame length flag == 1,  960 samples/frame
     */
    get1bits(/*            LEN_FRAME_LEN_FLAG,*/
        pInputStream);

    /*
     * dependsOnCoreCoder == 1, core coder has different sampling rate
     * in a scalable bitstream
     */
    dependsOnCoreCoder =
        get1bits(/*            LEN_DEPEND_ON_CORE,*/
            pInputStream);

    if (dependsOnCoreCoder != FALSE)
    {
        /*coreCoderDelay =
         *    getbits(
         *        LEN_CORE_DELAY,
         *        pInputStream);
         */

        status = 1; /* do not support scalable coding in this release */
    }

    /*
     * extension flag indicates if Amendment 1 objects are used or not
     * extension flag == 0 objects = 1, 2, 3, 4, 6, 7
     * extension flag == 1 objects = 17, 19, 20, 21, 22, 23
     */
    extFlag = get1bits(pInputStream);       /*  LEN_EXT_FLAG,*/


    /* Force checks for implicit channel configuration */
    pVars->mc_info.implicit_channeling = 1;

    if (status == SUCCESS)
    {

        if (channel_config == 0)
        {
            status = get_prog_config(pVars,
                                     &pVars->scratch.scratch_prog_config);

            if (status != SUCCESS)
            {
                pVars->prog_config.front.ele_is_cpe[0] = 0; /* default to mono  */
                pVars->mc_info.nch = 1;
                pVars->prog_config.front.ele_tag[0] = 0;

                status = SUCCESS;
            }
        }
        else
        {
            /*
             * dummy tag = 0 and
             * set up decoding configurations
             */
            channel_config--;
            pVars->prog_config.front.ele_is_cpe[0] = channel_config;
            pVars->prog_config.front.ele_tag[0] = 0;

            status =
                set_mc_info(
                    &(pVars->mc_info),
                    audioObjectType, /* previously profile */
                    pVars->prog_config.sampling_rate_idx,
                    pVars->prog_config.front.ele_tag[0],
                    pVars->prog_config.front.ele_is_cpe[0],
                    pVars->winmap, /*pVars->pWinSeqInfo,*/
                    pVars->SFBWidth128);

        } /* if (channel_config) */

    } /* if(status) */

    /*
     * This layer_num is not found in ISO/IEC specs,
     * but it is defined in San Diego spec for scalable bitstream
     */
    if ((audioObjectType == MP4AUDIO_AAC_SCALABLE) ||
            (audioObjectType == MP4AUDIO_ER_AAC_SCALABLE))
    {
        /*layer_num =
         *    getbits(
         *        LEN_LAYER_NUM,
         *        pInputStream);
         */

        status = 1; /* for this release only */
    }

    if (extFlag)
    {
        /*
         * currently do not implement these functionalities
         * defined in Amendment 1
         * keep it here for future release
         */
        if (audioObjectType == MP4AUDIO_ER_BSAC)
        {
            status = 1;     /* NOT SUPPORTED */
            /*
            numOfSubFrame = getbits( LEN_SUB_FRAME, pInputStream);

            layer_len = getbits( LEN_LAYER_LEN, pInputStream);
            */
        }

        /*
         * The following code is equivalent to
         * if ((audioObjectType == 17) || (audioObjectType == 18) ||
         *     (audioObjectType == 19) || (audioObjectType == 20) ||
         *     (audioObjectType == 21) || (audioObjectType == 23))
         */

        if (((audioObjectType > 16) && (audioObjectType < 22)) ||
                (audioObjectType == 23))
        {
            status = 1;     /* NOT SUPPORTED */
            /*
            aacSectionDataResilienceFlag = getbits( LEN_SECT_RES_FLAG,
                                                    pInputStream);

            aacScalefactorDataResilienceFlag = getbits( LEN_SCF_RES_FLAG,
                                                        pInputStream);

            aacSpectralDataResilienceFlag = getbits( LEN_SPEC_RES_FLAG,
                                                     pInputStream);
            */
        }
        /*
         * this flag is tbd in version 3 of ISO/IEC spec
         * if the encoder generates this bit, then it has to be read
         * current adif2mp4ff does not write this bit. If this bit is to
         * be read, it can be done by the following code:
         */

        extFlag3 = get1bits(pInputStream);       /*  LEN_EXT_FLAG3 */

        if (extFlag3)
        {
            status = 1;     /* NOT SUPPORTED */
        }

    }

    return status;
}
