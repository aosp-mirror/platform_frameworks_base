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

 Pathname: ./src/get_ics_info.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Modified from original shareware code

 Description:  Modified to pass variables by reference to eliminate use
               of global variables.

 Description:  Clean up code.

 Description:  Fix comments before review, remove lpflag[]

 Description:  Update per review comments, and match ISO/IEC 14496-3

 Description:  Update per peer review comments.

 Description:  Remove "rollback" of used bits, since lt_decode is to change.

 Description: Replace some instances of getbits to get9_n_lessbits
              when the number of bits read is 9 or less and get1bits
              when only 1 bit is read.

 Who:                                   Date:
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    audioObjectType = MP4 Audio Object Type for the current song. Only if
                    this is set to LTP (MP4AUDIO_LTP) will long term
                    prediction bits be retrieved. Data type
                    tMP4AudioObjectType, which is an enumeration, which in
                    turn is an Int.

    pInputStream  = pointer to a BITS structure, used by the function getbits
                    to provide data. This is the second parameter to this
                    function to match its position in getbits().
                    Data type pointer to BITS structure

    common_window = field read in huffdecode, which tells whether information
                    is shared between the left and right channel. Long term
                    prediction (LTP) data is NOT shared even if its a common
                    window, so this flag is needed to see if another set of
                    LTP possibly needs to be read. If this flag is false,
                    pSecondLTPStatus is not touched, it could be NULL if
                    need be. Data type Bool, which is Int.

    pWindowSequence = pointer to where the the window type of the current
                    frame and channel should be placed, of data type
                    WINDOW_SEQUENCE, which is Int. It can take on one
                    of four values: ONLY_LONG_SEQUENCE, LONG_START_SEQUENCE,
                    EIGHT_SHORT_SEQUENCE, LONG_STOP_SEQUENCE,

    pWindowShape =  pointer to where the window shape for the current frame
                    and channel should be placed, of data type WINDOW_SHAPE,
                    which is Int. It can take on the one of these two values:
                    SINE_WINDOW, KAISER_BESSEL_WINDOW. It is used in the
                    "filterbank" section of decoding.

    group         = array that holds the index of the first window in each
                    group. Data type array of Int, eight elements.

    p_max_sfb     = pointer to where the maximum number of scale factor bands
                    for the current frame and channel will be placed. Data
                    type of pointer to Int.

    p_winmap      = array of pointers to all of the possible four window
                    configurations. This parameter did not need to be pointers,
                    and could be changed in the future. Data type array of pointers
                    to FrameInfo structures, length 4.

    pFirstLTPStatus = pointer to a structure where the first LTP
                    information will be stored. It would be confusing and wrong
                    to call this left LTP status since if common_window = FALSE,
                    this function will be called twice - once for the left, once
                    for the right. It could be done, but extra conditional code
                    would need to be done.
                    Data type pointer to LT_PRED_STATUS structure.

    pSecondLTPStatus = pointer to where the right channel of LTP
                    information will be stored only if common_window is non-zero.
                    Data type pointer to LT_PRED_STATUS structure.

 Local Stores/Buffers/Pointers Needed: None.

 Global Stores/Buffers/Pointers Needed: None.

 Outputs:
    status  = 0 implies no error occurred, non-zero otherwise.

 Pointers and Buffers Modified:
    pInputStream contents are modified in such a way that the number of bits
        read increases.
    pWindowSequence contents are updated with the current window for this
        frame and channel
    group[] contents will be modified to grouping information. See getgroup
        source code for a better description of what this is.
    p_max_sfb contents will be updated with the maximum scale factor bands
        for this frame and channel.
    pFirstLTPStatus contents may be updated if the stream has long term
        prediction information.
    pSecondLTPStatus contents may be updated if common_window != 0 and LTP data
        is present.


 Local Stores Modified: None

 Global Stores Modified: None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function retrieves the individual channel stream (ICS) information
 from the bitstream. The information read for the current
 frame and channel is:
 - window sequence
 - window shape for use in the filter bank
 - number of scale factor bands
 - long term predication (LTP) information
 - grouping information

 This function does NOT support MPEG2 style AAC Frequency Domain Predictor,
 not to be confused with LTP (Long Term Prediction). If such data is found
 to be on the file an error is generated.

------------------------------------------------------------------------------
 REQUIREMENTS

 This function is not to use static or global data.

------------------------------------------------------------------------------
 REFERENCES

  (1) ISO/IEC 14496-3:1999(E) Titled "Information technology - Coding
      of audio-visual objects Part 3: Audio Subpart 4:"
      Table 4.4.6 - Syntax of ics_info(), page 16.


 (2) MPEG-2 NBC Audio Decoder
   "This software module was originally developed by AT&T, Dolby
   Laboratories, Fraunhofer Gesellschaft IIS in the course of development
   of the MPEG-2 NBC/MPEG-4 Audio standard ISO/IEC 13818-7, 14496-1,2 and
   3. This software module is an implementation of a part of one or more
   MPEG-2 NBC/MPEG-4 Audio tools as specified by the MPEG-2 NBC/MPEG-4
   Audio standard. ISO/IEC  gives users of the MPEG-2 NBC/MPEG-4 Audio
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

    status = 0;
    first_ltp_data_present = FALSE;
    second_ltp_data_present = FALSE;


    CALL getbits(
        neededBits = LEN_ICS_RESERV + LEN_WIN_SEQ + LEN_WIN_SH,
        pInputStream = pInputStream)
    MODIFYING( pInputStream )
    RETURNING( temp = returnValue )

    windowSequence = (temp >> LEN_WIN_SH) & ((0x1<<LEN_WIN_SEQ)-1);

    *pWindowShape = (temp) & ((0x1<<LEN_WIN_SH)-1);

    IF (windowSequence == EIGHT_SHORT_SEQUENCE)
    THEN
        CALL getbits(
            neededBits = LEN_MAX_SFBS,
            pInputStream = pInputStream)
        MODIFYING(pInputStream)
        RETURNING(local_max_sfb = returnValue)

        CALL getgroup(
            group = group,
            pInputStream = pInputStream)
        MODIFYING(group)
        MODIFYING(pInputStream)
        RETURNING(nothing)


    ELSE

        group[0] = 1;

        CALL getbits(
            neededBits = LEN_MAX_SFBL + LEN_PREDICTOR_DATA_PRESENT,
            pInputStream = pInputStream)
        MODIFYING(pInputStream)
        RETURNING(temp = returnValue)

        predictor_data_present =
            (Bool) getbits(
                LEN_BOOLEAN,
                pInputStream);

        local_max_sfb = (Int)(temp >> LEN_PREDICTOR_DATA_PRESENT);

        predictor_data_present =
            (Bool) (temp & ((0x1 << LEN_PREDICTOR_DATA_PRESENT)-1));

        IF (local_max_sfb > allowed_max_sfb)
        THEN
            status = 1
        ELSEIF (audioObjectType == MP4AUDIO_LTP)
        THEN
            IF (predictor_data_present != FALSE)
            THEN
                CALL getbits(
                    neededBits = LEN_LTP_DATA_PRESENT,
                    pInputStream = pInputStream)
                MODIFYING(pInputStream)
                RETURNING(first_ltp_data_present = returnValue)

                IF (ltp_data_present != FALSE)
                THEN

                    CALL lt_decode(
                        win_type = windowSequence,
                        pInputStream  = pInputStream,
                        max_sfb = local_max_sfb,
                        pLt_pred = pFirstLTPStatus)
                    MODIFYING(pInputStream)
                    MODIFYING(pFirstLTPStatus)
                    RETURNING(nothing)

                ENDIF

                IF (common_window != FALSE)
                THEN
                    CALL getbits(
                        neededBits = LEN_LTP_DATA_PRESENT,
                        pInputStream = pInputStream)
                    MODIFYING(pInputStream)
                    RETURNING(second_ltp_data_present = returnValue)

                    IF (second_ltp_data_present != FALSE)
                    THEN

                        CALL lt_decode(
                            win_type = windowSequence,
                            pInputStream  = pInputStream,
                            max_sfb = local_max_sfb,
                            pLt_pred = pSecondLTPStatus)
                        MODIFYING(pInputStream)
                        MODIFYING(pSecondLTPStatus)
                        RETURNING(nothing)
                    ENDIF
                ENDIF
            ENDIF
        ELSE
            IF  (predictor_data_present != FALSE)
            THEN
                status = 1
            ENDIF
        END IF
    ENDIF

    pFirstLTPStatus->ltp_data_present = first_ltp_data_present;

    IF (common_window != FALSE)
    THEN
        pSecondLTPStatus->ltp_data_present = second_ltp_data_present;
    ENDIF

    pFrameInfo = p_winmap[*p_wnd];
    IF (local_max_sfb > pFrameInfo->sfb_per_frame)
    THEN
        status = 1;
    ENDIF

    *(p_max_sfb) = local_max_sfb;

    MODIFY(*(pWindowSequence))
    MODIFY(*(pWinShape))
    MODIFY(*(p_max_sfb))
    MODIFY(group[])
    MODIFY(*pInputStream)
    MODIFY(*pFirstLTPStatus)
    MODIFY(*pSecondLTPStatus)
    RETURN (status);



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

#include "e_rawbitstreamconst.h"
#include "e_tmp4audioobjecttype.h"

#include "s_bits.h"
#include "s_frameinfo.h"
#include "s_lt_pred_status.h"

#include "ibstream.h"
#include "lt_decode.h"
#include "ltp_common_internal.h" /* For LEN_LTP_DATA_PRESENT constant */

#include "get_ics_info.h"
#include "huffman.h"        /* For the declaration of getgroup */

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/

#define LEN_PREDICTOR_DATA_PRESENT (1)

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

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

Int get_ics_info(
    const tMP4AudioObjectType  audioObjectType,
    BITS                      *pInputStream,
    const Bool                 common_window,
    WINDOW_SEQUENCE           *pWindowSequence,
    WINDOW_SHAPE              *pWindowShape,
    Int                        group[],
    Int                       *p_max_sfb,
    FrameInfo                 *p_winmap[],
    LT_PRED_STATUS            *pFirstLTPStatus,
    LT_PRED_STATUS            *pSecondLTPStatus)
{
    WINDOW_SEQUENCE       windowSequence;
    UInt                  temp;
    Bool                  predictor_data_present;
    UInt                   local_max_sfb;
    UInt                   allowed_max_sfb;
    Int                   status = SUCCESS;
    Bool                  first_ltp_data_present = FALSE;
    Bool                  second_ltp_data_present = FALSE;

    /*
     * The following three calls to getbits have been replaced with one
     * call for speed:
     *
     *                  getbits(LEN_ICS_RESERV, pInputStream);
     * windowSequence = getbits(LEN_WIN_SEQ, pInputStream);
     * *pWindowShape  = getbits(LEN_WIN_SH, pInputStream);
     *
     */

    temp =
        get9_n_lessbits(
            LEN_ICS_RESERV + LEN_WIN_SEQ + LEN_WIN_SH,
            pInputStream);


    windowSequence = (WINDOW_SEQUENCE)((temp >> LEN_WIN_SH) & ((0x1 << LEN_WIN_SEQ) - 1));

    *pWindowShape = (WINDOW_SHAPE)((temp) & ((0x1 << LEN_WIN_SH) - 1));

    /*
     * This pointer should not be NULL as long as the initialization code
     * has been run, so the test for NULL has been removed.
     */
    allowed_max_sfb = p_winmap[windowSequence]->sfb_per_win[0];

    if (windowSequence == EIGHT_SHORT_SEQUENCE)
    {
        local_max_sfb =  get9_n_lessbits(LEN_MAX_SFBS,
                                         pInputStream);

        getgroup(
            group,
            pInputStream);

        if (local_max_sfb > allowed_max_sfb)
        {
            status = 1;  /* ERROR CODE - needs to be updated */
        }

    } /* end of TRUE of if (windowSequence == EIGHT_SHORT_SEQUENCE) */
    else
    {
        /* There is only one group for long windows. */
        group[0] = 1;

        /*
         * The window is long, get the maximum scale factor bands,
         * and get long term prediction info.
         *
         * Reference [1] states that the audioObjectType is first tested,
         * then the predictor_data_present is read on either branch of the
         * if (audioObjectType == MP4AUDIO_LTP). Instead, this code combines
         * the two calls on both branches into one before the
         * if, and then in turn combines with another call to getbits, all
         * in the name of speed.
         *
         * This would be the individual calls, without checking the number
         * of scale factor bands:
         *
         *   local_max_sfb =
         *      (Int) getbits(
         *          LEN_MAX_SFBL,
         *           pInputStream);
         *
         *  if (audioObjectType == MP4AUDIO_LTP)
         *  {
         *        predictor_data_present =
         *           (Bool) getbits(
         *              LEN_PREDICTOR_DATA_PRESENT,
         *              pInputStream);
         *
         *     .....   (read LTP data)
         *
         *    }
         *    else
         *    {
         *
         *        predictor_data_present =
         *           (Bool) getbits(
         *              LEN_PREDICTOR_DATA_PRESENT,
         *              pInputStream);
         *
         *     .....   (its an error for this library)
         *     }
         */
        temp =
            get9_n_lessbits(
                LEN_MAX_SFBL + LEN_PREDICTOR_DATA_PRESENT,
                pInputStream);

        local_max_sfb = (Int)(temp >> LEN_PREDICTOR_DATA_PRESENT);

        predictor_data_present =
            (Bool)(temp & ((0x1 << LEN_PREDICTOR_DATA_PRESENT) - 1));

        if (local_max_sfb > allowed_max_sfb)
        {
            status = 1;  /* ERROR CODE - needs to be updated */
        }
        else if (audioObjectType == MP4AUDIO_LTP)
        {
            /*
             * Note that the predictor data bit has already been
             * read.
             */

            /*
             * If the object type is LTP, the predictor data is
             * LTP. If the object type is not LTP, the predictor data
             * is so called "frequency predictor data", which is not
             * supported by this implementation. Refer to (1)
             */
            if (predictor_data_present != FALSE)
            {
                first_ltp_data_present =
                    (Bool) get1bits(/*                        LEN_LTP_DATA_PRESENT,*/
                        pInputStream);

                if (first_ltp_data_present != FALSE)
                {
                    lt_decode(
                        windowSequence,
                        pInputStream,
                        local_max_sfb,
                        pFirstLTPStatus);
                }
                if (common_window != FALSE)
                {
                    second_ltp_data_present =
                        (Bool) get1bits(/*                            LEN_LTP_DATA_PRESENT,*/
                            pInputStream);

                    if (second_ltp_data_present != FALSE)
                    {
                        lt_decode(
                            windowSequence,
                            pInputStream,
                            local_max_sfb,
                            pSecondLTPStatus);
                    }
                } /* if (common_window != FALSE) */

            } /* if (predictor_data_present != FALSE) */

        } /* else if (audioObjectType == MP4AUDIO_LTP) */
        else
        {
            /*
             * Note that the predictor data bit has already been
             * read.
             */

            /*
             * The object type is not LTP. If there is data, its
             * frequency predictor data, not supported by this
             * implementation.
             */
            if (predictor_data_present != FALSE)
            {
                status = 1; /* ERROR CODE UPDATE LATER */
            } /* if (predictor_data_present != FALSE) */

        } /* end of "else" clause of if (audioObjectType == MP4AUDIO_LTP) */

    } /*  if (windowSequence == EIGHT_SHORT_SEQUENCE) [FALSE branch] */


    /*
     * Save all local copies.
     */
    pFirstLTPStatus->ltp_data_present = first_ltp_data_present;
    if (common_window != FALSE)
    {
        pSecondLTPStatus->ltp_data_present = second_ltp_data_present;
    }

    *p_max_sfb = local_max_sfb;

    *pWindowSequence = windowSequence;

    return (status);

}  /* get_ics_info */

