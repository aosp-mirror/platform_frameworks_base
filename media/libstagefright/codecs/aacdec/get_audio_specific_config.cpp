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

 Pathname: ./src/get_audio_specific_config.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Modified per review comments

 Description: Modified per second review comments
              (1) change audioObjectType to Int
              (2) do not set pVars->prog_config.profile
              (3) clean up status flag, default to SUCCESS
              (4) fix multiple lines comments

 Description: Change getbits.h to ibstream.h

 Description: Modified per review comments
              (1) updated revision history
              (2) declare audioObjectType as enum type

 Description: Replace some instances of getbits to get9_n_lessbits
              when the number of bits read is 9 or less.

 Description: Added support for backward and non-backward (explicit)
              mode for Parametric Stereo (PS) used in enhanced AAC+

 Who:                              Date:
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    pVars = pointer to the structure that holds all information for
            this instance of the library. pVars->prog_config is directly
            used, and pVars->mc_info, pVars->prog_config,
            pVars->pWinSeqInfo, pVars->SFBWidth128 are needed indirectly
            for calling set_mc_info. Data type pointer to tDec_Int_File

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    status = 0 if successfully decoded AudioSpecificConfig
             1 if un-supported config is used for this release

 Pointers and Buffers Modified:
    pVars->prog_config contents are updated with the information read in.
    pVars->mc_info contents are updated with channel information.
    pVars->pWinSeqInfo contents are updated with window information.
    pVars->SFBWidth128 contents are updated with scale factor band width data.

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function reads the bitstream for the structure "AudioSpecificConfig",
 and sets the decoder configuration that is needed by the decoder to be able
 to decode the media properly.

------------------------------------------------------------------------------
 REQUIREMENTS

 This function shall not use global variables

------------------------------------------------------------------------------
 REFERENCES

 (1) ISO/IEC 14496-3: 1999(E)
 Part 3
 Subpart 1  p18     1.6   Interface to MPEG-4 Systems
 Subpart 4  p13     4.4.1 GA Specific Configuration
 Amendment  p10     6.2.1 AudioSpecificInfo
 Amendment  p78     8.2   Decoder configuration (GASpecificConfig)

 (2) AAC DecoderSpecificInfo Information
   PacketVideo descriptions - San Diego

------------------------------------------------------------------------------
 PSEUDO-CODE

    status = SUCCESS;

    pInputStream = &(pVars->inputStream);

    temp = CALL getbits(
                    neededBits = LEN_OBJ_TYPE + LEN_SAMP_RATE_IDX,
                    pInputStream = pInputStream)
           MODIFYING (pInputStream)
           RETURNING (temp)

    audioObjectType = (temp & 0x1f0) >> 4;

    pVars->prog_config.profile = audioObjectType;

    pVars->prog_config.sampling_rate_idx = temp & 0xf;

    IF (pVars->prog_config.sampling_rate_idx == 0xf)
    THEN
        sampling_rate = CALL getbits(
                            neededBits = LEN_SAMP_RATE,
                            pInputStream = pInputStream);
                        MODIFYING (pInputStream)
                        RETURNING (sampling_rate)
    ENDIF

    channel_config = CALL getbits(
                            neededBits = LEN_CHAN_CONFIG,
                            pInputStream = pInputStream);
                        MODIFYING (pInputStream)
                        RETURNING (channel_config)

    IF (channel_config > 2)
    THEN
        status = 1;
    ENDIF

    IF (((audioObjectType == MP4AUDIO_AAC_MAIN)     OR
        (audioObjectType == MP4AUDIO_AAC_LC)        OR
        (audioObjectType == MP4AUDIO_AAC_SSR)       OR
        (audioObjectType == MP4AUDIO_LTP)           OR
        (audioObjectType == MP4AUDIO_AAC_SCALABLE)  OR
        (audioObjectType == MP4AUDIO_TWINVQ)) AND (status == -1))
    THEN
        status = CALL get_GA_specific_config(
                            pVars = pVars,
                            channel_config = channel_config,
                            audioObjectType = audioObjectType,
                            pInputStream = pInputStream);
                      MODIFYING (pVars->mc_info,channel_config,pInputStream)
                      RETURNING (status)

    ENDIF

    IF (audioObjectType == MP4AUDIO_CELP)
    THEN
        status = 1;
    ENDIF

    IF (audioObjectType == MP4AUDIO_HVXC)
    THEN
        status = 1;
    ENDIF

    IF (audioObjectType == MP4AUDIO_TTSI)
    THEN
        status = 1;
    ENDIF

    IF ((audioObjectType == 13) OR (audioObjectType == 14) OR
        (audioObjectType == 15) OR (audioObjectType == 16))
    THEN
        status = 1;
    ENDIF

    IF (((audioObjectType == MP4AUDIO_ER_AAC_LC)       OR
         (audioObjectType == MP4AUDIO_ER_AAC_LTP)      OR
         (audioObjectType == MP4AUDIO_ER_AAC_SCALABLE) OR
         (audioObjectType == MP4AUDIO_ER_TWINVQ)       OR
         (audioObjectType == MP4AUDIO_ER_BSAC)         OR
         (audioObjectType == MP4AUDIO_ER_AAC_LD)) AND (status == -1))
    THEN
        status = 1;
    ENDIF

    IF (audioObjectType == MP4AUDIO_ER_CELP)
    THEN
        status = 1;
    ENDIF

    IF (audioObjectType == MP4AUDIO_ER_HVXC)
    THEN
        status = 1;
    ENDIF

    IF ((audioObjectType == MP4AUDIO_ER_HILN) OR
        (audioObjectType == MP4AUDIO_PARAMETRIC))
    THEN
        status = 1;
    ENDIF

    IF ((audioObjectType == MP4AUDIO_ER_AAC_LC)       OR
        (audioObjectType == MP4AUDIO_ER_AAC_LTP)      OR
        (audioObjectType == MP4AUDIO_ER_AAC_SCALABLE) OR
        (audioObjectType == MP4AUDIO_ER_TWINVQ)       OR
        (audioObjectType == MP4AUDIO_ER_BSAC)         OR
        (audioObjectType == MP4AUDIO_ER_AAC_LD)       OR
        (audioObjectType == MP4AUDIO_ER_CELP)         OR
        (audioObjectType == MP4AUDIO_ER_HVXC)         OR
        (audioObjectType == MP4AUDIO_ER_HILN)         OR
        (audioObjectType == MP4AUDIO_PARAMETRIC))
    THEN
        epConfig = CALL getbits(
                            neededBits = LEN_EP_CONFIG,
                            pInputStream = pInputStream);
                      MODIFYING (pInputStream)
                      RETURNING (epConfig)

        IF (epConfig == 2)
        THEN
            status = 1;
        ENDIF

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
#include    "get_audio_specific_config.h"
#include    "get_ga_specific_config.h"
#include    "ibstream.h"
#include    "sfb.h"                   /* Where samp_rate_info[] is declared */

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
Int get_audio_specific_config(tDec_Int_File   * const pVars)
{

    UInt    temp;
    tMP4AudioObjectType     audioObjectType;
    //UInt32  sampling_rate;
    UInt    channel_config;
    UInt    syncExtensionType;
    UInt    extensionAudioObjectType = 0;
    UInt    extensionSamplingFrequencyIndex = 0;
    BITS   *pInputStream;
    Int     status;

    status = SUCCESS;

    pInputStream = &(pVars->inputStream);

    pVars->mc_info.upsamplingFactor = 1;   /*  default to regular AAC */

    temp =  get9_n_lessbits(LEN_OBJ_TYPE + LEN_SAMP_RATE_IDX,
                            pInputStream);

    /*
     * The following code can directly set the values of elements in
     * MC_Info, rather than first setting the values in pVars->prog_config
     * and then copy these values to MC_Info by calling set_mc_info.
     * In order to keep consistent with get_prog_config (ADIF) and
     * get_adts_header (ADTS), the code here is still copying
     * the info, and set the pVars->current_program = 0
     */

    /* AudioObjectType */
    audioObjectType = (tMP4AudioObjectType)((temp & 0x1f0) >> 4);

    pVars->mc_info.ExtendedAudioObjectType =  audioObjectType;   /* default */
    /* saving an audioObjectType into a profile field */
    /* pVars->prog_config.profile = audioObjectType; */

    /* sampling rate index */
    pVars->prog_config.sampling_rate_idx = temp & 0xf;

    if (pVars->prog_config.sampling_rate_idx > 0xb)
    {
        /*
         *  Only support 12 sampling frequencies from array samp_rate_info ( see sfb.cpp)
         *  7350 Hz (index 0xc) is not supported, the other indexes are reserved or escape
         */
        if (pVars->prog_config.sampling_rate_idx == 0xf) /* escape sequence */
        {
            /*
             * sampling rate not listed in Table 1.6.2,
             * this release does not support this
             */
            /*sampling_rate =  getbits( LEN_SAMP_RATE,
                                      pInputStream);*/
            getbits(LEN_SAMP_RATE, pInputStream); /* future use */
        }

        status = 1;
    }

    channel_config =  get9_n_lessbits(LEN_CHAN_CONFIG,
                                      pInputStream);

    if ((channel_config > 2) && (!pVars->aacConfigUtilityEnabled))
    {
        /*
         * AAC lib does not support more than two channels
         * signal error when in decoder mode
         * do not test when in utility mode
         */
        status = 1;

    }

    if (audioObjectType == MP4AUDIO_SBR || audioObjectType == MP4AUDIO_PS)
    {
        /* to disable explicit backward compatiblity check */
        pVars->mc_info.ExtendedAudioObjectType = MP4AUDIO_SBR;
        pVars->mc_info.sbrPresentFlag = 1;

        if (audioObjectType == MP4AUDIO_PS)
        {
            pVars->mc_info.psPresentFlag = 1;
            pVars->mc_info.ExtendedAudioObjectType = MP4AUDIO_PS;
        }

        extensionSamplingFrequencyIndex = /* extensionSamplingFrequencyIndex */
            get9_n_lessbits(LEN_SAMP_RATE_IDX,
                            pInputStream);
        if (extensionSamplingFrequencyIndex == 0x0f)
        {
            /*
             * sampling rate not listed in Table 1.6.2,
             * this release does not support this
                */
            /*sampling_rate = getbits( LEN_SAMP_RATE,
                                     pInputStream);*/
            getbits(LEN_SAMP_RATE, pInputStream);
        }

        audioObjectType = (tMP4AudioObjectType) get9_n_lessbits(LEN_OBJ_TYPE ,
                          pInputStream);
    }


    if ((/*(audioObjectType == MP4AUDIO_AAC_MAIN)     ||*/
                (audioObjectType == MP4AUDIO_AAC_LC)        ||
                /*(audioObjectType == MP4AUDIO_AAC_SSR)       ||*/
                (audioObjectType == MP4AUDIO_LTP)           /*||*/
                /*(audioObjectType == MP4AUDIO_AAC_SCALABLE)  ||*/
                /*(audioObjectType == MP4AUDIO_TWINVQ)*/) && (status == SUCCESS))
    {
        status = get_GA_specific_config(pVars,
                                        pInputStream,
                                        channel_config,
                                        audioObjectType);

        /*
         *  verify that Program config returned a supported audio object type
         */

        if ((pVars->mc_info.audioObjectType != MP4AUDIO_AAC_LC) &&
                (pVars->mc_info.audioObjectType != MP4AUDIO_LTP))
        {
            return 1;   /* status != SUCCESS invalid aot */
        }
    }
    else
    {
        return 1;   /* status != SUCCESS invalid aot or invalid parameter */
    }

    /*
     *  SBR tool explicit signaling ( backward compatible )
     */
    if (extensionAudioObjectType != MP4AUDIO_SBR)
    {
        syncExtensionType = (UInt)get17_n_lessbits(LEN_SYNC_EXTENSION_TYPE,
                            pInputStream);

        if (syncExtensionType == 0x2b7)
        {
            extensionAudioObjectType = get9_n_lessbits( /* extensionAudioObjectType */
                                           LEN_OBJ_TYPE,
                                           pInputStream);

            if (extensionAudioObjectType == MP4AUDIO_SBR)
            {
                pVars->mc_info.sbrPresentFlag = get1bits(pInputStream);  /* sbrPresentFlag */
                if (pVars->mc_info.sbrPresentFlag == 1)
                {
                    extensionSamplingFrequencyIndex =
                        get9_n_lessbits( /* extensionSamplingFrequencyIndex */
                            LEN_SAMP_RATE_IDX,
                            pInputStream);
                    if (pVars->aacPlusEnabled == true)
                    {
#ifdef AAC_PLUS
                        pVars->mc_info.upsamplingFactor = (samp_rate_info[extensionSamplingFrequencyIndex].samp_rate >> 1) ==
                                                          samp_rate_info[pVars->prog_config.sampling_rate_idx].samp_rate ? 2 : 1;

                        if ((Int)extensionSamplingFrequencyIndex == pVars->prog_config.sampling_rate_idx)
                        {
                            /*
                             *  Disable SBR decoding for any sbr-downsampled file whose SF is >= 24 KHz
                             */
                            if (pVars->prog_config.sampling_rate_idx < 6)
                            {
                                pVars->aacPlusEnabled = false;
                            }

                            pVars->mc_info.bDownSampledSbr = true;
                        }
                        pVars->prog_config.sampling_rate_idx = extensionSamplingFrequencyIndex;

#endif
                    }

                    if (extensionSamplingFrequencyIndex == 0x0f)
                    {
                        /*
                         * sampling rate not listed in Table 1.6.2,
                         * this release does not support this
                         */
                        /*sampling_rate = getbits( LEN_SAMP_RATE,
                                                 pInputStream);*/
                        getbits(LEN_SAMP_RATE, pInputStream);
                    }
                    /* syncExtensionType */
                    syncExtensionType = (UInt)get17_n_lessbits(LEN_SYNC_EXTENSION_TYPE,
                                        pInputStream);
                    if (syncExtensionType == 0x548)
                    {
                        pVars->mc_info.psPresentFlag = get1bits(pInputStream);  /* psPresentFlag */
                        if (pVars->mc_info.psPresentFlag)
                        {
                            extensionAudioObjectType = MP4AUDIO_PS;
                        }
                    }
                    else
                    {
                        /*
                        * Rewind bitstream pointer so that the syncExtensionType reading has no
                        * effect when decoding raw bitstream
                            */
                        pVars->inputStream.usedBits -= LEN_SYNC_EXTENSION_TYPE;
                    }

                    pVars->mc_info.ExtendedAudioObjectType = (eMP4AudioObjectType)extensionAudioObjectType;
                }
            }
        }
        else if (!status)
        {
            /*
             * Rewind bitstream pointer so that the syncExtensionType reading has no
             * effect when decoding raw bitstream
             */
            pVars->inputStream.usedBits -= LEN_SYNC_EXTENSION_TYPE;

#ifdef AAC_PLUS

            /*
             *  For implicit signalling, no hint that sbr or ps is used, so we need to
             *  check the sampling frequency of the aac content, if lesser or equal to
             *  24 KHz, by defualt upsample, otherwise, do nothing
             */
            if ((pVars->prog_config.sampling_rate_idx >= 6) && (pVars->aacPlusEnabled == true) &&
                    audioObjectType == MP4AUDIO_AAC_LC)
            {
                pVars->mc_info.upsamplingFactor = 2;
                pVars->prog_config.sampling_rate_idx -= 3;
                pVars->mc_info.sbrPresentFlag = 1;
                pVars->sbrDecoderData.SbrChannel[0].syncState = SBR_NOT_INITIALIZED;
                pVars->sbrDecoderData.SbrChannel[1].syncState = SBR_NOT_INITIALIZED;

            }
#endif

        }
    }
    else    /*  MP4AUDIO_SBR was detected  */
    {
        /*
         *  Set the real output frequency use by the SBR tool, define tentative upsample ratio
         */
        if (pVars->aacPlusEnabled == true)
        {
#ifdef AAC_PLUS
            pVars->mc_info.upsamplingFactor = (samp_rate_info[extensionSamplingFrequencyIndex].samp_rate >> 1) ==
                                              samp_rate_info[pVars->prog_config.sampling_rate_idx].samp_rate ? 2 : 1;

            if ((Int)extensionSamplingFrequencyIndex == pVars->prog_config.sampling_rate_idx)
            {
                /*
                 *  Disable SBR decoding for any sbr-downsampled file whose SF is >= 24 KHz
                 */
                if (pVars->prog_config.sampling_rate_idx < 6)
                {
                    pVars->aacPlusEnabled = false;
                }
                pVars->mc_info.bDownSampledSbr = true;
            }
            pVars->prog_config.sampling_rate_idx = extensionSamplingFrequencyIndex;



#endif




        }

    }  /*  if ( extensionAudioObjectType != MP4AUDIO_SBR ) */

    /*
     * The following object types are not supported in this release,
     * however, keep these interfaces for future implementation
     */

    /*
     *if (audioObjectType == MP4AUDIO_CELP)
     *{
     *    status = 1;
     *}
     */

    /*
     *if (audioObjectType == MP4AUDIO_HVXC)
     *{
     *    status = 1;
     *}
     */

    /*
     *if (audioObjectType == MP4AUDIO_TTSI)
     *{
     *    status = 1;
     *}
     */

    /*
     *if ((audioObjectType == 13) || (audioObjectType == 14) ||
     *   (audioObjectType == 15) || (audioObjectType == 16))
     *{
     *    status = 1;
     *}
     */

    /* The following objects are Amendment 1 objects */
    /*
     *if (((audioObjectType == MP4AUDIO_ER_AAC_LC)       ||
     *    (audioObjectType == MP4AUDIO_ER_AAC_LTP)      ||
     *    (audioObjectType == MP4AUDIO_ER_AAC_SCALABLE) ||
     *    (audioObjectType == MP4AUDIO_ER_TWINVQ)       ||
     *    (audioObjectType == MP4AUDIO_ER_BSAC)         ||
     *    (audioObjectType == MP4AUDIO_ER_AAC_LD)) && (status == -1))
     *{
     */
    /*
     * should call get_GA_specific_config
     * for this release, do not support Error Resilience
     * temporary solution is set status flag and exit decoding
     */
    /*    status = 1;
    *}
    */

    /*
     *if (audioObjectType == MP4AUDIO_ER_CELP)
     * {
     *    status = 1;
     *}
     */

    /*
     *if (audioObjectType == MP4AUDIO_ER_HVXC)
     *{
     *    status = 1;
     *}
     */

    /*
     *if ((audioObjectType == MP4AUDIO_ER_HILN) ||
     *    (audioObjectType == MP4AUDIO_PARAMETRIC))
     *{
     *    status = 1;
     *}
     */

    /*
     *if ((audioObjectType == MP4AUDIO_ER_AAC_LC)       ||
     *    (audioObjectType == MP4AUDIO_ER_AAC_LTP)      ||
     *    (audioObjectType == MP4AUDIO_ER_AAC_SCALABLE) ||
     *    (audioObjectType == MP4AUDIO_ER_TWINVQ)       ||
     *    (audioObjectType == MP4AUDIO_ER_BSAC)         ||
     *    (audioObjectType == MP4AUDIO_ER_AAC_LD)       ||
     *    (audioObjectType == MP4AUDIO_ER_CELP)         ||
     *    (audioObjectType == MP4AUDIO_ER_HVXC)         ||
     *    (audioObjectType == MP4AUDIO_ER_HILN)         ||
     *    (audioObjectType == MP4AUDIO_PARAMETRIC))
     *{
     */
    /* error protection config */
    /*
     *     epConfig =
     *       getbits(
     *           LEN_EP_CONFIG,
     *           pInputStream);
     *
     *   if (epConfig == 2)
     *   {
     */
    /* should call ErrorProtectionSpecificConfig() */
    /*
     *       status = 1;
     *   }
     *
     *}
     */

    return status;

}
