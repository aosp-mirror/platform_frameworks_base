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

 Pathname: get_prog_config.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Modified from original shareware code

 Description:  Modified to pass variables by reference to eliminate use
               of global variables.

 Description:  Move functionality from get_adif_header for when to change
               the current program configuration, add a temporary config
               to read into, clean up code, change function prototype.

 Description:  Clean up

 Description:  Update per review comments

 Description:  Fix double 'could'

 Description:  change enter_mc_info to set_mc_info

 Description:  update comments

 Description: Replace some instances of getbits to get9_n_lessbits
              when the number of bits read is 9 or less and get1bits
              when only 1 bit is read.

 Who:                                   Date:
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    pVars      = pointer to the structure that holds all information for
                 this instance of the library. pVars->prog_config is directly
                 used, and pVars->mc_info, pVars->prog_config, pVars->winmap,
                 pVars->SFBWidth128 are needed indirectly for calling
                 set_mc_info. Data type  pointer to tDec_Int_File structure.

    pScratchPCE = pointer to a temporary ProgConfig structure to be used
                  to read in the program configuration element.

 Local Stores/Buffers/Pointers Needed: None

 Global Stores/Buffers/Pointers Needed: None

 Outputs:
    status     = zero if no error was found, non-zero otherwise.

 Pointers and Buffers Modified:
    pVars->prog_config contents are updated with the PCE read in.
    pVars->mc_info contents are updated with channel information.
    pVars->winmap contents are updated with window information.
    pVars->SFBWidth128 contents are updated with scale factor band width data.

 Local Stores Modified: None

 Global Stores Modified: None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function reads from the input stream to memory for a temporary
 program configuration element (PCE). If the PCE read is the first
 encountered it is saved. Or, if the tag of the PCE read matches the tag of
 the first PCE encounted, it is saved as well. This is a mechanism for
 changing the sampling rate.

------------------------------------------------------------------------------
 REQUIREMENTS

 This function shall not use static or global variables.

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

    status          = SUCCESS;
    pInputStream   = &(pVars->inputStream);


    CALL getbits(
        neededBits = LEN_TAG,
        pInputStream = pInputStream )
    MODIFYING( pInputStream )
    RETURNING( tag = returnValue )

    CALL getbits(
        neededBits = LEN_PROFILE,
        pInputStream = pInputStream )
    MODIFYING( pInputStream )
    RETURNING( pScratchPCE->profile = returnValue )

    CALL getbits(
        neededBits = LEN_PROFILE,
        pInputStream = pInputStream )
    MODIFYING( pInputStream )
    RETURNING( pScratchPCE->sampling_rate_idx = returnValue )

    CALL getbits(
        neededBits = LEN_NUM_ELE,
        pInputStream = pInputStream )
    MODIFYING( pInputStream )
    RETURNING( temp = returnValue )

    pScratchPCE->front.num_ele = temp;

    CALL getbits(
        neededBits = LEN_NUM_ELE,
        pInputStream = pInputStream )
    MODIFYING( pInputStream )
    RETURNING( temp = returnValue )

    pScratchPCE->side.num_ele = temp;

    CALL getbits(
        neededBits = LEN_NUM_ELE,
        pInputStream = pInputStream )
    MODIFYING( pInputStream )
    RETURNING( temp = returnValue )

    pScratchPCE->back.num_ele = temp;

    CALL getbits(
        neededBits = LEN_NUM_LFE,
        pInputStream = pInputStream )
    MODIFYING( pInputStream )
    RETURNING( temp = returnValue )

    pScratchPCE->lfe.num_ele = temp;

    CALL getbits(
        neededBits = LEN_NUM_DAT,
        pInputStream = pInputStream )
    MODIFYING( pInputStream )
    RETURNING( temp = returnValue )

    pScratchPCE->data.num_ele = temp;

    CALL getbits(
        neededBits = LEN_NUM_CCE,
        pInputStream = pInputStream )
    MODIFYING( pInputStream )
    RETURNING( temp = returnValue )

    pScratchPCE->coupling.num_ele = temp;

    CALL getbits(
        neededBits = LEN_MIX_PRES,
        pInputStream = pInputStream )
    MODIFYING( pInputStream )
    RETURNING( flag = returnValue )

    pScratchPCE->mono_mix.present = flag;

    IF (flag != FALSE)
    THEN
        CALL getbits(
            neededBits = LEN_TAG,
            pInputStream = pInputStream )
        MODIFYING( pInputStream )
        RETURNING( temp = returnValue )

        pScratchPCE->mono_mix.ele_tag = temp;

    ENDIF

    CALL getbits(
        neededBits = LEN_MIX_PRES,
        pInputStream = pInputStream )
    MODIFYING( pInputStream )
    RETURNING( flag = returnValue )

    pScratchPCE->stereo_mix.present = flag;

    IF (flag != FALSE)
    THEN

        CALL getbits(
            neededBits = LEN_TAG,
            pInputStream = pInputStream )
        MODIFYING( pInputStream )
        RETURNING( temp = returnValue )

        pScratchPCE->stereo_mix.ele_tag = temp;

    ENDIF

    CALL getbits(
        neededBits = LEN_MIX_PRES,
        pInputStream = pInputStream )
    MODIFYING( pInputStream )
    RETURNING( flag = returnValue )

    flag =
        getbits(
            LEN_MIX_PRES,
            pInputStream);

    pScratchPCE->matrix_mix.present = flag;

    IF (flag != FALSE)
    THEN
        CALL getbits(
            neededBits = LEN_MMIX_IDX,
            pInputStream = pInputStream )
        MODIFYING( pInputStream )
        RETURNING( temp = returnValue )

        pScratchPCE->matrix_mix.ele_tag = temp;

        CALL getbits(
            neededBits = LEN_PSUR_ENAB,
            pInputStream = pInputStream )
        MODIFYING( pInputStream )
        RETURNING( temp = returnValue )

        pScratchPCE->matrix_mix.pseudo_enab = temp;

    ENDIF


    CALL get_ele_list(
        pElementList = &pScratchPCE->front,
        pInputStream = pInputStream,
        enableCPE    = TRUE )
    MODIFYING( pInputStream )
    MODIFYING( pScratchPCE->front )
    RETURNING( nothing )

    CALL get_ele_list(
        pElementList = &pScratchPCE->side,
        pInputStream = pInputStream,
        enableCPE    = TRUE )
    MODIFYING( pInputStream )
    MODIFYING( pScratchPCE->side )
    RETURNING( nothing )

    CALL get_ele_list(
        pElementList = &pScratchPCE->back,
        pInputStream = pInputStream,
        enableCPE    = TRUE )
    MODIFYING( pInputStream )
    MODIFYING( pScratchPCE->back )
    RETURNING( nothing )

    CALL get_ele_list(
        pElementList = &pScratchPCE->lfe,
        pInputStream = pInputStream,
        enableCPE    = FALSE )
    MODIFYING( pInputStream )
    MODIFYING( pScratchPCE->lfe )
    RETURNING( nothing )

    CALL get_ele_list(
        pElementList = &pScratchPCE->data,
        pInputStream = pInputStream,
        enableCPE    = FALSE )
    MODIFYING( pInputStream )
    MODIFYING( pScratchPCE->data )
    RETURNING( nothing )

    CALL get_ele_list(
        pElementList = &pScratchPCE->coupling,
        pInputStream = pInputStream,
        enableCPE    = TRUE )
    MODIFYING( pInputStream )
    MODIFYING( pScratchPCE->coupling )
    RETURNING( nothing )


    CALL byte_align(
        pInputStream = pInputStream )
    MODIFYING( pInputStream )
    RETURNING( nothing )

    CALL getbits(
        neededBits = LEN_COMMENT_BYTES,
        pInputStream = pInputStream )
    MODIFYING( pInputStream )
    RETURNING( numChars = returnValue )

    FOR (i = numChars; i > 0; i--)

        CALL getbits(
            neededBits = LEN_COMMENT_BYTES,
            pInputStream = pInputStream )
        MODIFYING( pInputStream )
        RETURNING( nothing )

    ENDFOR

    IF (pVars->current_program < 0)
    THEN
        pVars->current_program = tag;
    ENDIF


    IF (tag == pVars->current_program)
    THEN

        CALL pv_memcpy(
            to = &pVars->prog_config,
            from = pScratchPCE,
            n = sizeof(ProgConfig))
        MODIFYING( pVars->prog_config )
        RETURNING( nothing )

        CALL set_mc_info(
            pMC_Info = &pVars->mc_info,
            objectType = pVars->prog_config.profile + 1,
            samplin_rate_idx = pVars->prog_config.sampling_rate_idx,
            tag = pVars->prog_config.front.ele_tag[0],
            is_cpe = pVars->prog_config.front.ele_is_cpe[0],
            pWinSeqInfo = pVars->winmap,
            pSfbwidth128 = pVars->SFBWidth128)
        MODIFYING( pVars->mc_info )
        MODIFYING( pVars->winmap )
        MODIFYING( pVars->SFBWidth128 )
        RETURN( status = return_value )

    ENDIF

    MODIFY( pVars->mc_info )
    MODIFY( pVars->winmap )
    MODIFY( pVars->SFBWidth128 )
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
#include "s_bits.h"
#include "s_elelist.h"
#include "s_tdec_int_file.h"
#include "s_tdec_int_chan.h"
#include "e_progconfigconst.h"
#include "ibstream.h"
#include "get_ele_list.h"
#include "aac_mem_funcs.h"
#include "set_mc_info.h"
#include "get_prog_config.h"

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

Int get_prog_config(
    tDec_Int_File *pVars,
    ProgConfig    *pScratchPCE)
{
    Int    i;
    UInt    tag;
    Int    numChars;
    UInt    temp;
    Bool   flag;
    Int    status          = SUCCESS;
    BITS  *pInputStream   = &(pVars->inputStream);


    /*
     * The tag is used at the very end to see if this PCE is
     * the one to be used. Otherwise it does not need to be saved for the
     * the simple configurations to be used in this version of an AAC
     * decoder.
     *
     * All of the bits of this PCE must be read even if this PCE will not
     * be used. They are read into a temporary PCE, then later it is decided
     * whether to keep this PCE.
     *
     * To allow quick removal of the fields from the ProgConfig structure
     * that will probably not be used at a later date,
     * while still advancing the bitstream pointer,the return value of
     * getbits is saved into a temporary variable, then transfered to
     * the structure item.
     */
    tag =
        get9_n_lessbits(
            LEN_TAG,
            pInputStream);

    pScratchPCE->profile =
        get9_n_lessbits(
            LEN_PROFILE,
            pInputStream);

    pScratchPCE->sampling_rate_idx =
        get9_n_lessbits(
            LEN_SAMP_IDX,
            pInputStream);

    if (!pVars->adif_test && pScratchPCE->sampling_rate_idx != pVars->prog_config.sampling_rate_idx)
    {
        /* rewind the pointer as implicit channel configuration maybe the case */
        pInputStream->usedBits -= (LEN_TAG + LEN_PROFILE + LEN_SAMP_IDX);

        return (1); /*  mismatch cannot happen */
    }


    /*
     * Retrieve the number of element lists for each of
     * front, side, back, lfe, data, and coupling.
     *
     * For two-channel stereo or mono, only the data in the front needs
     * to be saved. However, ALL fields need to be skipped over in some
     * fashion. Also, the number of elements needs to be temporarily saved
     * to call get_ele_list(). If that function was changed to pass in
     * the number of points to be read, the memory set aside inside the
     * ProgConfig structure could be removed.
     */

    /*
     * The next six function calls could be combined into one, then use
     * shifts and masks to retrieve the individual fields.
     */
    temp =
        get9_n_lessbits(
            LEN_NUM_ELE,
            pInputStream);

    pScratchPCE->front.num_ele = temp;

    /* Needed only to read in the element list. */
    temp =
        get9_n_lessbits(
            LEN_NUM_ELE,
            pInputStream);

    pScratchPCE->side.num_ele = temp;

    /* Needed only to read in the element list. */
    temp =
        get9_n_lessbits(
            LEN_NUM_ELE,
            pInputStream);

    pScratchPCE->back.num_ele = temp;

    /* Needed only to read in the element list. */
    temp =
        get9_n_lessbits(
            LEN_NUM_LFE,
            pInputStream);

    pScratchPCE->lfe.num_ele = temp;

    /* Needed only to read in the element list. */
    temp =
        get9_n_lessbits(
            LEN_NUM_DAT,
            pInputStream);
    pScratchPCE->data.num_ele = temp;

    /* Needed only to read in the element list. */
    temp =
        get9_n_lessbits(
            LEN_NUM_CCE,
            pInputStream);

    pScratchPCE->coupling.num_ele = temp;

    /*
     * Read in mix down data.
     *
     * Whether these fields can be removed and have proper operation
     * will be determined at a later date.
     */

    /* Read presence of mono_mix */
    flag =
        get1bits(/*            LEN_MIX_PRES,*/
            pInputStream);

    pScratchPCE->mono_mix.present = flag;

    if (flag != FALSE)
    {
        temp =
            get9_n_lessbits(
                LEN_TAG,
                pInputStream);

        pScratchPCE->mono_mix.ele_tag = temp;

    } /* end if (flag != FALSE) */

    /* Read presence of stereo mix */
    flag =
        get1bits(/*            LEN_MIX_PRES,*/
            pInputStream);

    pScratchPCE->stereo_mix.present = flag;

    if (flag != FALSE)
    {
        temp =
            get9_n_lessbits(
                LEN_TAG,
                pInputStream);

        pScratchPCE->stereo_mix.ele_tag = temp;

    } /* end if (flag != FALSE) */

    /* Read presence of matrix mix */
    flag =
        get1bits(/*            LEN_MIX_PRES,*/
            pInputStream);

    pScratchPCE->matrix_mix.present = flag;

    if (flag != FALSE)
    {
        temp =
            get9_n_lessbits(
                LEN_MMIX_IDX,
                pInputStream);

        pScratchPCE->matrix_mix.ele_tag = temp;

        temp =
            get1bits(/*                LEN_PSUR_ENAB,*/
                pInputStream);

        pScratchPCE->matrix_mix.pseudo_enab = temp;

    } /* end if (flag != FALSE) */

    /*
     * Get each of the element lists. Only the front information will be
     * used for the PV decoder, but the usedBits field of pInputStream must
     * be advanced appropriately.
     *
     * This could be optimized by advancing the bit stream for the
     * elements that do not need to be read.
     */
    get_ele_list(
        &pScratchPCE->front,
        pInputStream,
        TRUE);

    get_ele_list(
        &pScratchPCE->side,
        pInputStream,
        TRUE);

    get_ele_list(
        &pScratchPCE->back,
        pInputStream,
        TRUE);

    get_ele_list(
        &pScratchPCE->lfe,
        pInputStream,
        FALSE);

    get_ele_list(
        &pScratchPCE->data,
        pInputStream,
        FALSE);

    get_ele_list(
        &pScratchPCE->coupling,
        pInputStream,
        TRUE);

    /*
     * The standard requests a byte alignment before reading in the
     * comment. This can be done because LEN_COMMENT_BYTES == 8.
     */
    byte_align(pInputStream);

    numChars =
        get9_n_lessbits(
            LEN_COMMENT_BYTES, pInputStream);

    /*
     * Ignore the comment - it requires 65 bytes to store (or worse on DSP).
     * If this field is restored, make sure to append a trailing '\0'
     */
    for (i = numChars; i > 0; i--)
    {
        pScratchPCE->comments[i] = (Char) get9_n_lessbits(LEN_BYTE,
                                   pInputStream);

    } /* end for */

    if (pVars->current_program < 0)
    {
        /*
         * If this is the first PCE, it becomes the current, regardless of
         * its tag number.
         */
        pVars->current_program = tag;

    } /* end if (pVars->current_program < 0) */


    if (tag == (UInt)pVars->current_program)
    {
        /*
         * This branch is reached under two conditions:
         * 1) This is the first PCE found, it was selected in the above if
         *    block. In all encoders found thus far, the tag value has been
         *    zero.
         * 2) A PCE has been sent by the encoder with a tag that matches the
         *    the first one sent. It will then be re-read. No encoder found
         *    thus far re-sends a PCE, when looking at ADIF files.
         *
         * Regardless, the temporary PCE will now be copied into the
         * the one official program configuration.
         */
        pv_memcpy(
            &pVars->prog_config,
            pScratchPCE,
            sizeof(ProgConfig));

        /* enter configuration into MC_Info structure */
        status =
            set_mc_info(
                &pVars->mc_info,
                (tMP4AudioObjectType)(pVars->prog_config.profile + 1),
                pVars->prog_config.sampling_rate_idx,
                pVars->prog_config.front.ele_tag[0],
                pVars->prog_config.front.ele_is_cpe[0],
                pVars->winmap,
                pVars->SFBWidth128);

    } /* end if (tag == pVars->current_program) */

    return (status);
}

