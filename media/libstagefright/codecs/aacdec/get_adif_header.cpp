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

 Pathname: get_adif_header.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Modified from original shareware code

 Description:  Modified to pass variables by reference to eliminate use
               of global variables.

 Description:  Change to PV template, remove default config parameter,
               move some functionality into get_prog_config().

 Description: Update per code review
              1) Add parameter pScratchPCE
              2) Change way ADIF_ID is read in.
              3) Fix comments
              4) ADD a test for status != SUCCESS in loop.

 Description: The ADIF_Header has now been delegated to the "scratch memory"
 union.  This change inside s_tDec_Int_File.h had to be reflected here also.

 Description: Updated the SW template to include the full pathname to the
 source file and a slightly modified copyright header.

 Who:                                   Date:
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    pVars        = pointer to the structure that contains the current state
                   of this instance of the library, of data type pointer to
                   tDec_Int_File

    pScratchPCE  = pointer to a ProgConfig structure used as scratch in the
                   the function get_prog_config. of data type pointer to
                   ProgConfig

 Local Stores/Buffers/Pointers Needed: None

 Global Stores/Buffers/Pointers Needed: None

 Outputs:
    The function returns 0 if no error occurred, non-zero otherwise.

 Pointers and Buffers Modified:
    pVars->adif_header contents are updated with the some of the ADIF header
           contents
    pVars->tempProgConfig contents are overwritten with last PCE found,
           which is most likely the first one found.
    pVars->prog_config contents are updated with the first PCE found.
    pVars->inputStream contents are modify in such a way that the
           stream is moved further along in the buffer.
    pVars->SFBWidth128 contents may be updated.
    pVars->winSeqInfo  contents may be updated.
    pScratchPCE        contents may be updated.

 Local Stores Modified: None

 Global Stores Modified: None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function reads in the ADIF Header found at the front of ADIF streams.
 If the header is not found an error is returned. An ADIF header can contain
 from zero to sixteen program configuration elements (PCE). This function, and
 the rest of the library, saves and uses the first PCE found.
------------------------------------------------------------------------------
 REQUIREMENTS

 Function shall not use static or global variables.

------------------------------------------------------------------------------
 REFERENCES

 (1) ISO/IEC 13818-7:1997 Titled "Information technology - Generic coding
   of moving pictures and associated audio information - Part 7: Advanced
   Audio Coding (AAC)", Table 6.21 - Syntax of program_config_element(),
   page 16, and section 8.5 "Program Config Element (PCE)", page 30.

 (2) MPEG-2 NBC Audio Decoder
   "This software module was originally developed by AT&T, Dolby
   Laboratories, Fraunhofer Gesellschaft IIS in the course of development
   of the MPEG-2 NBC/MPEG-4 Audio standard ISO/IEC 13818-7, 14496-1,2 and
   3. This software module is an implementation of a part of one or more
   MPEG-2 NBC/MPEG-4 Audio tools as specified by the MPEG-2 NBC/MPEG-4
   Audio standard. ISO/IEC gives users of the MPEG-2 NBC/MPEG-4 Audio
   standards free license to this software module or modifications thereof
   for use in hardware or software products claiming conformance to the
   MPEG-2 NBC/MPEG-4 Audio  standards. Those intending to use this software
   module in hardware or software products are advised that this use may
   infringe existing patents. The original developer of this software
   module and his/her company, the subsequent editors and their companies,
   and ISO/IEC have no liability for use of this software module or
   modifications thereof in an implementation. Copyright is not released
   for non MPEG-2 NBC/MPEG-4 Audio conforming products.The original
   developer retains full right to use the code for his/her own purpose,
   assign or donate the code to a third party and to inhibit third party
   from using the code for non MPEG-2 NBC/MPEG-4 Audio conforming products.
   This copyright notice must be included in all copies or derivative
   works."
   Copyright(c)1996.

------------------------------------------------------------------------------
 PSEUDO-CODE

     CALL getbits(
        neededBits = 2 * LEN_BYTE,
        pInputStream = pInputStream)
     MODIFYING( pInputStream )
     RETURNING( theIDFromFile )

     CALL getbits(
        neededBits = 2 * LEN_BYTE,
        pInputStream = pInputStream)
     MODIFYING( pInputStream )
     RETURNING( temp )

    theIDFromFile = (theIDFromFile << (2*LEN_BYTE)) | temp;

    IF (theIDFromFile != ADIF_ID)
    THEN

        pInputStream->usedBits -= (4 * LEN_BYTE);

        status = -1;
    ELSE
        CALL getbits(
            neededBits = LEN_COPYRT_PRES,
            pInputStream = pInputStream)
        MODIFYING( pInputStream )
        RETURNING( temp )

        IF (temp != FALSE) THEN
            FOR (i = LEN_COPYRT_ID; i > 0; i--)
               CALL getbits(
                   neededBits = LEN_BYTE,
                   pInputStream = pInputStream)
               MODIFYING( pInputStream )

            END FOR
        END IF

        CALL getbits(
            neededBits = LEN_ORIG + LEN_HOME,
            pInputStream = pInputStream)
        MODIFYING( pInputStream )

        CALL getbits(
            neededBits = LEN_BS_TYPE,
            pInputStream = pInputStream)
        MODIFYING( pInputStream )
        RETURNING( bitStreamType )

        CALL getbits(
            neededBits = LEN_BIT_RATE,
            pInputStream = pInputStream)
        MODIFYING( pInputStream )
        RETURNING( pHeader->bitrate )

        CALL getbits(
            neededBits = LEN_NUM_PCE,
            pInputStream = pInputStream)
        MODIFYING( pInputStream )
        RETURNING( numConfigElementsMinus1 )

        FOR (  i = numConfigElementsMinus1;
              (i >= 0) && (status == SUCCESS);
               i--)

            IF (bitStreamType == CONSTANT_RATE_BITSTREAM) THEN
               CALL getbits(
                   neededBits = LEN_ADIF_BF,
                   pInputStream = pInputStream)
               MODIFYING( pInputStream )
            END IF

            CALL get_prog_config(
                pVars = pVars)
            MODIFYING( pVars->prog_config )
            RETURNING( status )

        END FOR
    END IF

    RETURN (status)

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
#include "pv_audio_type_defs.h"
#include "e_adif_const.h"

#include "s_progconfig.h"
#include "s_adif_header.h"
#include "s_bits.h"
#include "s_mc_info.h"
#include "s_frameinfo.h"
#include "s_tdec_int_file.h"

#include "get_prog_config.h"
#include "ibstream.h"

#include "get_adif_header.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/

/*
 * This constant is simply the characters 'A' 'D' 'I' 'F' compressed into
 * a UInt32. Any possible endian problems that exist must be solved by
 * the function that fills the buffer and getbits(), or this constant and
 * the rest of the bit stream will not work.
 */
#define ADIF_ID (0x41444946)

/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL VARIABLE DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL FUNCTION REFERENCES
; Declare functions defined elsewhere and referenced in this module
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL VARIABLES REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/

Int get_adif_header(
    tDec_Int_File *pVars,
    ProgConfig    *pScratchPCE)
{
    Int          i;
    UInt32       temp;
    Int          numConfigElementsMinus1;
    Int          bitStreamType;
    UInt32       theIDFromFile;

    BITS        *pInputStream = &pVars->inputStream;
    ADIF_Header *pHeader = &pVars->scratch.adif_header;
    Int          status  = SUCCESS;

    /*
     * The ADIF_ID field is 32 bits long, one more than what getbits() can
     * do, so read the field in two parts. There is no point in saving the
     * string - it either matches or it does not. If it matches, it must
     * have been 'ADIF'
     */

    theIDFromFile = get17_n_lessbits((2 * LEN_BYTE), pInputStream);

    temp          = get17_n_lessbits((2 * LEN_BYTE), pInputStream);

    theIDFromFile = (theIDFromFile << (2 * LEN_BYTE)) | temp;


    if (theIDFromFile != ADIF_ID)
    {
        /*
         * Rewind the bit stream pointer so a search for ADTS header
         * can start at the beginning.
         */

        pInputStream->usedBits -= (4 * LEN_BYTE);

        /*
         * The constant in the next line needs to be updated when
         * error handling method is determined.
         */
        status = -1;
    }
    else
    {
        /*
         * To save space, the unused fields are read in, but not saved.
         */

        /* copyright string */
        temp =
            get1bits(/*                LEN_COPYRT_PRES,*/
                pInputStream);

        if (temp != FALSE)
        {
            /*
             * Read in and ignore the copyright string. If restoring
             * watch out for count down loop.
             */

            for (i = LEN_COPYRT_ID; i > 0; i--)
            {
                get9_n_lessbits(LEN_BYTE,
                                pInputStream);
            } /* end for */

            /*
             * Make sure to terminate the string with '\0' if restoring
             * the the copyright string.
             */

        } /* end if */

        /* Combine the original/copy and fields into one call */
        get9_n_lessbits(
            LEN_ORIG + LEN_HOME,
            pInputStream);

        bitStreamType =
            get1bits(/*                LEN_BS_TYPE,*/
                pInputStream);

        pHeader->bitrate =
            getbits(
                LEN_BIT_RATE,
                pInputStream);

        /*
         * Read in all the Program Configuration Elements.
         * For this library, only one of the up to 16 possible PCE's will be
         * saved. Since each PCE must be read, a temporary PCE structure is
         * used, and if that PCE is the one to use, it is copied into the
         * single PCE. This is done inside of get_prog_config()
         */

        numConfigElementsMinus1 =  get9_n_lessbits(LEN_NUM_PCE,
                                   pInputStream);

        for (i = numConfigElementsMinus1;
                (i >= 0) && (status == SUCCESS);
                i--)
        {
            /*
             * For ADIF contant bit rate streams, the _encoder_ buffer
             * fullness is transmitted. This version of an AAC decoder has
             * no use for this variable; yet it must be read in to move
             * the bitstream pointers.
             */

            if (bitStreamType == CONSTANT_RATE_BITSTREAM)
            {
                getbits(
                    LEN_ADIF_BF,
                    pInputStream);
            } /* end if */

            pVars->adif_test = 1;
            /* Get one program configuration element */
            status =
                get_prog_config(
                    pVars,
                    pScratchPCE);

#ifdef AAC_PLUS

            /*
             *  For implicit signalling, no hint that sbr or ps is used, so we need to
             *  check the sampling frequency of the aac content, if lesser or equal to
             *  24 KHz, by defualt upsample, otherwise, do nothing
             */
            if ((pVars->prog_config.sampling_rate_idx >= 6) && (pVars->aacPlusEnabled == true) &&
                    pVars->mc_info.audioObjectType == MP4AUDIO_AAC_LC)
            {
                pVars->mc_info.upsamplingFactor = 2;
                pVars->prog_config.sampling_rate_idx -= 3;
                pVars->mc_info.sbrPresentFlag = 1;
                pVars->sbrDecoderData.SbrChannel[0].syncState = UPSAMPLING;
                pVars->sbrDecoderData.SbrChannel[1].syncState = UPSAMPLING;
            }
#endif



        } /* end for */


    } /* end 'else' of --> if (theIDFromFile != ADIF_ID) */

    return status;

} /* end get_adif_header */
