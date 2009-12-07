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

 Pathname: getmask.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Modified from original shareware code

 Description:  Modified to pass variables by reference to eliminate use
               of global variables.
               Replaced for-loop style memory initialization with memset()

 Description: (1) Modified to bring code in-line with PV standard
              (2) Removed multiple returns, Replaced multiple 'if's with
                  switch

 Description: (1) Modified per review comments
              (2) increment pointer pMask after memset

 Description: Make the maximum number of bits requested from getbits
              become a constant.

 Description: Typecast 1 to UInt32 for bitmask to avoid masking on a 16-bit
              platform

 Description: Replace some instances of getbits to get9_n_lessbits
              when the number of bits read is 9 or less.

 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
        pFrameInfo  = pointer to structure that holds information for current
                      frame, Type FrameInfo

        pInputStream= pointer to structure that holds input stream information
                      Type BITS

        pGroup      = pointer to array that holds the stop window index for
                      each group in current frame, Type Int

        max_sfb     = number of active sfbs for each window, Type Int

        mask[]      = array that holds the MS_mask information
                      Type Int

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    mask_present = 0    (no Mid/Side mixed)
                   2    (Mid/Side mixed present for entire frame)
                   1    (Mid/Side mixed information read from bitstream)
                   -1   (invalid mask_present read from bitstream)

 Pointers and Buffers Modified:
    pMask   contents replaced by MS information of each scalefactor band

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function reads the Mid/Side(MS) mask information from the input
 bitstream. If the mask_present field is equal to 2, the mask bits is set to
 1 for the entire frame. If mask_present has a value of 0, the function
 returns 0, If mask_present is set to 1, the Mid/Side(MS) information is
 read from the input stream. When mask_present is 3, an error code (-1) is
 generated.
 The Mid/Side(MS) information is later used for mixing the left and right
 channel sounds. Each scalefactor band has its own MS information.

 (ISO comments: read a synthesis mask,  read a synthesis mask uses
                EXTENDED_MS_MASK and grouped mask )

------------------------------------------------------------------------------
 REQUIREMENTS

 This function shall replace the contents of pMask with the MS information
 of each scalefactor band

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
    Subpart 4
                    p15     (Table 4.4.5    getmask)

------------------------------------------------------------------------------
 PSEUDO-CODE

    CALL getbits(LEN_MASK_PRES, pInputStream)
    MODIFYING (pInputStream)
    RETURNING (mask present information)
    mask_present = mask present information

    SWITCH (mask_present)

        CASE (0):
            BREAK;

        CASE (2):
            nwin = pFrameInfo->num_win;
            FOR(win = 0; win < nwin; win = *(pGroup++))

                FOR(sfb = pFrameInfo->sfb_per_win[win]; sfb > 0; sfb--)
                    *(pMask++) = 1;
                ENDFOR

            ENDFOR

            BREAK;

        CASE(1):

            nwin = pFrameInfo->num_win;

                nToDo = max_sfb;

                WHILE (nToDo > 0)
                    nCall = nToDo;

                    IF (nCall > MAX_GETBITS)
                    THEN
                        nCall = MAX_GETBITS;
                    ENDIF

                    tempMask =
                        getbits(
                            nCall,
                            pInputStream);

                    bitmask = 1 << (nCall - 1);
                    FOR (sfb = nCall; sfb > 0; sfb--)
                       *(pMask++) = (tempMask & bitmask) >> (sfb - 1);
                        bitmask >>= 1;
                    ENDFOR

                    nToDo -= nCall;
                END WHILE

                pv_memset(
                    pMask,
                    0,
                    (pFrameInfo->sfb_per_win[win]-max_sfb)*sizeof(*pMask));

            ENDFOR (win)

            BREAK

        DEFAULT:
            mask_present = -1

    ENDSWITCH

    RETURN  mask_present

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include    "pv_audio_type_defs.h"
#include    "huffman.h"
#include    "aac_mem_funcs.h"
#include    "e_maskstatus.h"

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
Int getmask(
    FrameInfo   *pFrameInfo,
    BITS        *pInputStream,
    Int         group[],
    Int         max_sfb,
    Int         mask[])
{

    Int     win; /* window index */
    Int     sfb;
    Int     mask_present;
    Int    *pMask;
    Int    *pGroup;
    Int     nwin;
    Int     nCall;
    Int     nToDo;
    UInt32  tempMask;
    UInt32  bitmask;

    pMask  = mask;
    pGroup = group;

    mask_present =
        get9_n_lessbits(
            LEN_MASK_PRES,
            pInputStream);

    switch (mask_present)
    {
        case(MASK_NOT_PRESENT):
            /* special EXTENDED_MS_MASK cases */
            /* no ms at all */
            break;

        case(MASK_ALL_FRAME):
            /* MS for whole spectrum on, mask bits set to 1 */
            nwin = pFrameInfo->num_win;
            for (win = 0; win < nwin; win = *(pGroup++))
            {
                for (sfb = pFrameInfo->sfb_per_win[win]; sfb > 0; sfb--)
                {
                    *(pMask++) = 1; /* cannot use memset for Int type */
                }

            }

            break;

        case(MASK_FROM_BITSTREAM):
            /* MS_mask_present==1, get mask information*/
            nwin = pFrameInfo->num_win;
            for (win = 0; win < nwin; win = *(pGroup++))
            {
                /*
                 * the following code is equivalent to
                 *
                 * for(sfb = max_sfb; sfb > 0; sfb--)
                 * {
                 *   *(pMask++) =
                 *       getbits(
                 *           LEN_MASK,
                 *           pInputStream);
                 * }
                 *
                 * in order to save the calls to getbits, the above
                 * for-loop is broken into two parts
                 */

                nToDo = max_sfb;

                while (nToDo > 0)
                {
                    nCall = nToDo;

                    if (nCall > MAX_GETBITS)
                    {
                        nCall = MAX_GETBITS;
                    }

                    tempMask =
                        getbits(
                            nCall,
                            pInputStream);

                    bitmask = (UInt32) 1 << (nCall - 1);
                    for (sfb = nCall; sfb > 0; sfb--)
                    {
                        *(pMask++) = (Int)((tempMask & bitmask) >> (sfb - 1));
                        bitmask >>= 1;
                    }

                    nToDo -= nCall;
                }

                /*
                 * set remaining sfbs to zero
                 * re-use nCall to save one variable on stack
                 */

                nCall = pFrameInfo->sfb_per_win[win] - max_sfb;


                if (nCall >= 0)
                {
                    pv_memset(pMask,
                              0,
                              nCall*sizeof(*pMask));

                    pMask += nCall;
                }
                else
                {
                    mask_present = MASK_ERROR;
                    break;
                }


            } /* for (win) */

            break;

        default:
            /* error */
            break;

    } /* switch (mask_present) */

    return mask_present;

} /* getmask */
