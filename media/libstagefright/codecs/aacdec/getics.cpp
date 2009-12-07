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

 Pathname: getics.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Modified from original shareware code

 Description:  Modified to pass variables by reference to eliminate use
               of global variables

 Description: Remove pass-in parameter global_gain, define it on stack.

 Description: (1) Modified to bring in-line with PV standards
              (2) Modified pass in parameters
              (3) Removed multiple returns, removed some if branch
              (4) Replace for loop with pv_memset

 Description: Remove prstflag, fix copyright.

 Description: Fix pseudo-code

 Description: Remove lpflag from get_ics_info

 Description: (1) Removed widx, therefore, pChVarsWin is eliminated from
                  pass in parameter

 Description: merged the above changes from Michael and Wen

 Description: Removed initialization of "pTnsFrameInfo->num_subblocks" since
 this element was removed from that structure, as a part of
 rearchitecting the TNS routines to use memory more efficiently.

 Description:
 (1) Added #include of "e_HuffmanConst.h"
     Previously, this function was relying on another include file
     to include "e_HuffmanConst.h"

 (2) Updated the copyright header.

 (3) Added #include of <stdlib.h> for NULL macro definition.

 Description:
 (1) Removed the first parameter to getics.c  This extra
     FrameInfo was not needed, the contents of winmap can be used.
 (2) Removed the memcpy of the data from winmap to the temporary
     FrameInfo.

 Description: Replace some instances of getbits to get1bits
              when only 1 bit is read.

 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    pInputStream    =   pointer to structure that holds input stream,
                        Type BITS

    common_window   =   flag that indicates whether left and right channel
                        share the same window sequence & shape, Type Int

    pVars           =   pointer to structure that holds decoder information
                        Type tDec_Int_File

    pChVarsCh       =   pointer to structure that holds channel related
                        decoding information, Type tDec_Int_Chan

    group[]         =   pointer to array that contains window grouping
                        information of current frame, Type UChar

    pMax_sfb        =   pointer to variable that stores maximum active
                        scalefactor bands of current frame, Type UChar

    pCodebookMap    =   pointer to array that holds the indexes of all
                        Huffman codebooks used for current frame, ordered
                        from section 0 to last section. Type UChar

    pTnsFrameInfo   =   pointer to structure that holds TNS information.
                        Type TNS_frame_info

    pWinMap         =   array of pointers which points to structures that
                        hold information of long and short window sequences
                        Type FrameInfo

    pPulseInfo       =   pointer to structure that holds pulse data decoding
                        information, Type Nec_info

    sect[]          =   array of structures that hold section codebook and
                        section length in current frame, Type SectInfo

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    status = 0  if success
             1  otherwise

 Pointers and Buffers Modified:
    pCodebookMap    contents are replaced by the indexes of all the huffman
                    codebooks used for current frame

    pWinMap         For short windows, the contents of frame_sfb_top are
                    modified by calc_gsfb_table, with the top coefficient
                    index of each scalefactor band.

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function decodes individual channel stream by calling other Huffman
 decoding functions.

------------------------------------------------------------------------------
 REQUIREMENTS

 This function replaces the contents of pCodebookMap with the decoded
 codebook indexes. By calling hufffac, it decodes scale factor data. Call
 huffspec_fxp to decode spectral coefficients of current frame.

------------------------------------------------------------------------------
 REFERENCES

 (1) MPEG-2 NBC Audio Decoder
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

 (2) ISO/IEC 14496-3: 1999(E)
    Subpart 4           p24 (Table 4.4.24)
                        p54 (4.5.2.3.2)

------------------------------------------------------------------------------
 PSEUDO-CODE

    pGroup = group;

    global_gain = CALL getbits(
                            neededBits   = LEN_SCL_PCM,
                            pInputStream = pInputStream)
                        MODIFYING(pInputStream)
                        ReTURNING(global_gain)

    IF (common_window == FALSE)
    THEN
        status = CALL get_ics_info(
                        pVars->mc_info.audioObjectType,
                        pInputStream,
                        common_window,
                       &pChVars->wnd,
                       &pChVars->wnd_shape_this_bk,
                        group,
                        pMax_sfb,
                        pWinMap,
                        &pChVars->lt_status,
                        NULL)
                    MODIFYING(pInputStream,pChVars,group,max_sfb,lt_status)
                    RETURNING(status)
    ENDIF

    memcpy(pFrameInfo, pWinMap[pChVars->wnd], sizeof(FrameInfo))

    IF (*pMax_sfb > 0)
    THEN

        i      = 0;
        totSfb = 0;

        DO

            totSfb++;

        WHILE( *pGroup++ < pFrameInfo->num_win);

        totSfb  *=  pFrameInfo->sfb_per_win[0];

        nsect = CALL huffcb(
                        sect,
                        pInputStream,
                        pFrameInfo->sectbits,
                        totSfb,
                        pFrameInfo->sfb_per_win[0],
                       *pMax_sfb)
                    MODIFYING(sect,pInputStream,sectbits)
                    RETURNING(nsect)

        IF (nsect == 0)
        THEN
            status = 1

        ENDIF

        sectStart = 0;
        FOR (i = 0; i < nsect; i++)

            cb  = sect[i].sect_cb;
            sectWidth =  sect[i].sect_end - sectStart;
            sectStart += sectWidth;

            WHILE (sectWidth > 0)

                *pCodebookMap++ = cb
                 sectWidth--
            ENDWHILE

        ENDFOR (i)

    ELSE

        memset(pCodebookMap,ZERO_HCB,MAXBANDS*sizeof(*pCodebookMap));

    ENDIF (*pMax_sfb)

    IF (pFrameInfo->islong == FALSE)
    THEN
        CALL calc_gsfb_table(
                pFramInfo = pFrameInfo,
                group[]   = group)
              MODIFYING(pFrameInfo->frame_sfb_top)
              RETURNING(void)
    ENDIF

    IF (status == SUCCESS)
    THEN
        status = CALL hufffac(
                        pFrameInfo,
                        pInputStream,
                        group,
                        nsect,
                        sect,
                        global_gain,
                        pChVars->factors,
                        pVars->huffBookUsed)
                    MODIFYING(pInputStream,factors)
                    RETURNING(status)

    ENDIF (status)

    IF (status == SUCCESS)
    THEN
        present = CALL getbits(
                        neededBits   = LEN_PULSE_PRES,
                        pInputStream = pInputStream)
                    MODIFYING(pInputStream)
                    RETURNING(present)

        pPulseInfo->pulse_data_present = present;

        IF (present != FALSE)
        THEN
            IF (pFrameInfo->islong == 1)
            THEN
                CALL get_pulse_data(
                          pPulseInfo = pPulseInfo,
                          pInputStream = pInputStream)
                    MODIFYING(pInputStream,pPulseInfo)
                    RETURNING(void)

            ELSE

                status = 1;

            ENDIF (pFrameInfo)
        ENDIF (present)

    ENDIF (status)

    IF (status == SUCCESS)
    THEN
        present = CALL getbits(
                        neededBits = LEN_TNS_PRES,
                        pInputStream = pInputStream)
                    MODIFYING(pInputStream)
                    RETURNING(present)

        pTnsFrameInfo->tns_data_present = present;

        IF (present != FALSE)
        THEN
            CALL get_tns(
                    pFrameInfo = pFrameInfo,
                    pTnsFrameInfo = pTnsFrameInfo,
                    pInputStream = pInputStream)
                MODIFYING(pInputStream, pTnsFrameInfo)
                RETURNING(void)
        ELSE

            FOR (i = pTnsFrameInfo->n_subblocks - 1; i >= 0 ; i--)

                pTnsFrameInfo->info[i].n_filt = 0;
            ENDFOR

        ENDIF(present)

    ENDIF (status)

    IF (status == SUCCESS)
    THEN
        present = CALL getbits(
                        neededBits = LEN_GAIN_PRES,
                        pInputStream = pInputStream)
                MODIFYING(pInputStream)
                RETURNING(present)

        IF (present != FALSE)
        THEN
            status = 1;
        ENDIF
    ENDIF (status)

    IF (status == SUCCESS)
    THEN
        status = CALL huffspec_fxp(
                        pFrameInfo,
                        pInputStream,
                        nsect,
                        sect,
                        pChVars->factors,
                        pChVars->fxpCoef,
                        pVars->quantSpec,
                        pVars->tmp_spec,
                        pWinMap[ONLY_LONG_WINDOW],
                        pPulseInfo,
                        pChVars->qFormat)
                MODIFYING(pInputStream,fxpCoef,quantSpec,tmp_spec,qFormat)
                RETURNING(status)
    ENDIF

    RETURN status

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include    "pv_audio_type_defs.h"
#include    "e_huffmanconst.h"
#include    "huffman.h"
#include    "aac_mem_funcs.h"
#include    "get_tns.h"

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
Int getics(
    BITS            *pInputStream,
    Int             common_window,
    tDec_Int_File   *pVars,
    tDec_Int_Chan   *pChVars,
    Int             group[],
    Int             *pMax_sfb,
    Int             *pCodebookMap,
    TNS_frame_info  *pTnsFrameInfo,
    FrameInfo       **pWinMap,
    PulseInfo       *pPulseInfo,
    SectInfo        sect[])
{
    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    Int     status = SUCCESS;

    Int     nsect = 0;
    Int     i;
    Int     cb;
    Int     sectWidth;
    Int     sectStart;
    Int     totSfb;
    Int     *pGroup;

    FrameInfo *pFrameInfo;

    Int     global_gain; /* originally passed in from huffdecode */
    Bool    present;

    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/
    pGroup = group;

    /* read global gain from Input bitstream */
    global_gain =
        get9_n_lessbits(
            LEN_SCL_PCM,
            pInputStream);

    if (common_window == FALSE)
    {
        status = get_ics_info(
                     pVars->mc_info.audioObjectType,
                     pInputStream,
                     common_window,
                     &pChVars->wnd,
                     &pChVars->wnd_shape_this_bk,
                     group,
                     pMax_sfb,
                     pWinMap,
                     &pChVars->pShareWfxpCoef->lt_status,
                     NULL);
    }

    pFrameInfo = pWinMap[pChVars->wnd];

    /* First, calculate total number of scalefactor bands
     * for this grouping. Then, decode section data
     */
    if (*pMax_sfb > 0)
    {

        /* calculate total number of sfb */
        i      = 0;
        totSfb = 0;

        do
        {
            totSfb++;

        }
        while (*pGroup++ < pFrameInfo->num_win);

        totSfb  *=  pFrameInfo->sfb_per_win[0];

        /* decode section data */
        nsect =
            huffcb(
                sect,
                pInputStream,
                pFrameInfo->sectbits,
                totSfb,
                pFrameInfo->sfb_per_win[0],
                *pMax_sfb);

        if (nsect == 0)
        {
            status = 1;     /* decode section data error */

        }/* if (nsect) */

        /* generate "linear" description from section info
         * stored as codebook for each scalefactor band and group
         * when nsect == 0, for-loop does not execute
         */
        sectStart = 0;
        for (i = 0; i < nsect; i++)
        {
            cb  = sect[i].sect_cb;
            sectWidth =  sect[i].sect_end - sectStart;
            sectStart += sectWidth;

            while (sectWidth > 0)
            {
                *pCodebookMap++ = cb;   /* cannot use memset for Int */
                sectWidth--;
            }

        } /* for (i) */

    }
    else
    {
        /* set all sections with ZERO_HCB */
        pv_memset(
            pCodebookMap,
            ZERO_HCB,
            MAXBANDS*sizeof(*pCodebookMap));
        /*
                for (i=MAXBANDS; i>0; i--)
                {
                    *(pCodebookMap++) = ZERO_HCB;
                }
        */

    } /* if (*pMax_sfb) */

    /* calculate band offsets
     * (because of grouping and interleaving this cannot be
     * a constant: store it in pFrameInfo->frame_sfb_top)
     */
    if (pFrameInfo->islong == FALSE)
    {
        calc_gsfb_table(
            pFrameInfo,
            group);
    }

    /* decode scale factor data */
    if (status == SUCCESS)
    {
        status =
            hufffac(
                pFrameInfo,
                pInputStream,
                group,
                nsect,
                sect,
                global_gain,
                pChVars->pShareWfxpCoef->factors,
                pVars->scratch.huffbook_used);

    } /* if (status) */

    /* noiseless coding */
    if (status == SUCCESS)
    {
        present =
            get1bits(pInputStream);

        pPulseInfo->pulse_data_present = present;

        if (present != FALSE)
        {
            if (pFrameInfo->islong == 1)
            {
                status = get_pulse_data(
                             pPulseInfo,
                             pInputStream);
            }
            else
            {
                /* CommonExit(1,"Pulse data not allowed for short blocks"); */
                status = 1;

            } /* if (pFrameInfo) */
        } /* if (present) */

    } /* if (status) */


    /* decode tns data */
    if (status == SUCCESS)
    {
        present =
            get1bits(pInputStream);

        pTnsFrameInfo->tns_data_present = present;

        if (present != FALSE)
        {
            get_tns(
                pChVars->pShareWfxpCoef->max_sfb,
                pInputStream,
                pChVars->wnd,
                pFrameInfo,
                &pVars->mc_info,
                pTnsFrameInfo,
                pVars->scratch.tns_decode_coef);
        }
        else
        {
            for (i = pFrameInfo->num_win - 1; i >= 0 ; i--)
            {
                pTnsFrameInfo->n_filt[i] = 0;
            }

        } /* if(present) */

    } /* if (status) */

    /* gain control */
    if (status == SUCCESS)
    {
        present =
            get1bits(pInputStream);

        if (present != FALSE)
        {
            /* CommonExit(1, "Gain control not implemented"); */
            status = 1;
        }
    } /* if (status) */

    if (status == SUCCESS)
    {
        status =
            huffspec_fxp(
                pFrameInfo,
                pInputStream,
                nsect,
                sect,
                pChVars->pShareWfxpCoef->factors,
                pChVars->fxpCoef,
                pVars->share.a.quantSpec,
                pVars->scratch.tmp_spec,
                pWinMap[ONLY_LONG_WINDOW],
                pPulseInfo,
                pChVars->pShareWfxpCoef->qFormat);
    }

    /*----------------------------------------------------------------------------
    ; Return status
    ----------------------------------------------------------------------------*/

    return status;

} /* getics */
