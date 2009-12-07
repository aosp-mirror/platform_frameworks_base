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

 Pathname: q_normalize.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:
 (1) Modify to include search over the scalefactor bands to insure
     that the data is using all 31 data-bits.

 Description:
 (1) Modify to remove search over the scalefactor bands to insure
     that the data is using all 31 data-bits.
     (Pushed out into separate function)
 (2) Change variable "k" to more descriptive "shift_amt"
 (3) Update pseudocode to reflect removed code.
 (4) Add PV Copyright notice.

 Description:
 (1) Modified to protect q-normalize from shifting by amounts >= 32.

 Description:
 (1) Delete local variable idx_count.

 Description:
 (1) Included search for max in each frame, modified interface.

 Description:
 (1) unrolled loop based on the fact that the size of each scale band
     is always an even number.

 Description:Check shift, if zero, do not shift.

 Description: Eliminated warning: non use variable "i" and memset function
    definition

 Who:                       Date:
 Description:
------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    qFormat[] = Array of qFormats, one per scalefactor band. [ Int ]

    pFrameInfo = Pointer to structure that holds information about each group.
                 (long block flag, number of windows, scalefactor bands, etc.)
                 [const FrameInfo]

    coef[]    = Array of the spectral coefficients for one channel. [ Int32 ]

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    min_q = The common q-format for the entire frame. [Int]

 Pointers and Buffers Modified:
    coef[]    = Array of spectral data, now normalized to one q-format.

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This module first scans every scalefactor band for the frame, insuring that
 at least one element in that scalefactor band is using all available bits.
 If not, the elements in the scalefactor band are shifted up to use all 31
 data bits.  The q-format is adjusted accordingly.

 This module then scans the q-formats for each scalefactor band.
 Upon finding the minimum q-format in the frame, the coefficients in each
 scalefactor band are normalized to the minimum q-format.
 The minimum q-format is then returned to the calling function, which is now
 the q-format for the entire frame.

------------------------------------------------------------------------------
 REQUIREMENTS

------------------------------------------------------------------------------
 REFERENCES

------------------------------------------------------------------------------
 PSEUDO-CODE

    nwin = pFrameInfo->num_win;

    pQformat   = &(qFormat[0]);
    pSfbPerWin = &(pFrameInfo->sfb_per_win[0]);
    pCoef      = &(coef[0]);

    FOR (win = nwin; win > 0; win--)

        nsfb = *(pSfbPerWin++);

        FOR (sfb = nsfb; sfb > 0; sfb--)

            IF ( *(pQformat) < min_q)
                min_q = *(pQformat);
            ENDIF

            pQformat++;

        ENDFOR

    ENDFOR

    pQformat   = &(qFormat[0]);
    pSfbPerWin = &(pFrameInfo->sfb_per_win[0]);
    pCoef      = &(coef[0]);

    FOR (win = 0; win < nwin; win++)

        stop_idx = 0;

        nsfb   = *(pSfbPerWin++);

        pWinSfbTop = &(pFrameInfo->win_sfb_top[win][0]);

        FOR (sfb = nsfb; sfb > 0; sfb--)

            sfbWidth  = *(pWinSfbTop++) - stop_idx;

            stop_idx += sfbWidth;

            k = *(pQformat++) - min_q;

            IF (k < 32)
            THEN
                FOR (; sfbWidth > 0; sfbWidth--)
                    *(pCoef++) >>= k;
                ENDFOR
            ELSE
                FOR (; sfbWidth > 0; sfbWidth--)
                    *(pCoef++) = 0;
                ENDFOR
            ENDIF

        ENDFOR

    ENDFOR

    return min_q;

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
#include "s_frameinfo.h"
#include "q_normalize.h"
#include "aac_mem_funcs.h"         /* For pv_memset                         */

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
Int q_normalize(
    Int        qFormat[],
    const FrameInfo *pFrameInfo,
    Int32      abs_max_per_window[],
    Int32      coef[])
{
    Int    sfb;
    Int    nsfb;
    Int    win;
    Int    nwin;
    Int    sfbWidth;

    Int    shift_amt;

    /* Initialize min_q to a very large value */
    Int    min_q = 1000;

    Int stop_idx  = 0;

    const Int   *pSfbPerWin;
    const Int16 *pWinSfbTop;

    Int   *pQformat;
    Int32 *pCoef;

    nwin = pFrameInfo->num_win;

    /* Find the minimum q format */
    pQformat   = &(qFormat[0]);
    pSfbPerWin = &(pFrameInfo->sfb_per_win[0]);

    for (win = nwin; win != 0; win--)
    {

        nsfb = *(pSfbPerWin++);

        if (nsfb < 0 || nsfb > MAXBANDS)
        {
            break;  /* avoid any processing on error condition */
        }

        for (sfb = nsfb; sfb != 0; sfb--)
        {
            Int qformat = *(pQformat++);
            if (qformat < min_q)
            {
                min_q = qformat;
            }
        }

    } /* for(win) */

    /* Normalize the coefs in each scalefactor band to one q-format */
    pQformat   = &(qFormat[0]);
    pSfbPerWin = &(pFrameInfo->sfb_per_win[0]);
    pCoef      = &(coef[0]);

    for (win = 0; win < nwin; win++)
    {

        Int32 max = 0;
        stop_idx = 0;

        nsfb   = *(pSfbPerWin++);

        if (nsfb < 0 || nsfb > MAXBANDS)
        {
            break;  /* avoid any processing on error condition */
        }

        pWinSfbTop = &(pFrameInfo->win_sfb_top[win][0]);

        for (sfb = nsfb; sfb != 0; sfb--)
        {
            Int tmp1, tmp2;
            tmp1 = *(pWinSfbTop++);
            tmp2 = *(pQformat++);
            sfbWidth  = tmp1 - stop_idx;

            if (sfbWidth < 2)
            {
                break;  /* will lead to error condition */
            }

            stop_idx += sfbWidth;

            shift_amt = tmp2 - min_q;

            if (shift_amt == 0)
            {
                Int32 tmp1, tmp2;
                tmp1 = *(pCoef++);
                tmp2 = *(pCoef++);
                /*
                 *  sfbWidth is always an even number
                 *  (check tables in pg.66 IS0 14496-3)
                 */
                for (Int i = (sfbWidth >> 1) - 1; i != 0; i--)
                {
                    max  |= (tmp1 >> 31) ^ tmp1;
                    max  |= (tmp2 >> 31) ^ tmp2;
                    tmp1 = *(pCoef++);
                    tmp2 = *(pCoef++);
                }
                max  |= (tmp1 >> 31) ^ tmp1;
                max  |= (tmp2 >> 31) ^ tmp2;

            }
            else
            {
                if (shift_amt < 31)
                {
                    Int32 tmp1, tmp2;
                    tmp1 = *(pCoef++) >> shift_amt;
                    tmp2 = *(pCoef--) >> shift_amt;
                    /*
                     *  sfbWidth is always an even number
                     *  (check tables in pg.66 IS0 14496-3)
                     */
                    for (Int i = (sfbWidth >> 1) - 1; i != 0; i--)
                    {
                        *(pCoef++)   = tmp1;
                        *(pCoef++)   = tmp2;

                        max  |= (tmp1 >> 31) ^ tmp1;
                        max  |= (tmp2 >> 31) ^ tmp2;
                        tmp1 = *(pCoef++) >> shift_amt;
                        tmp2 = *(pCoef--) >> shift_amt;

                    }
                    *(pCoef++)   = tmp1;
                    *(pCoef++)   = tmp2;
                    max  |= (tmp1 >> 31) ^ tmp1;
                    max  |= (tmp2 >> 31) ^ tmp2;

                }
                else
                {
                    pv_memset(pCoef, 0, sizeof(Int32)*sfbWidth);
                    pCoef += sfbWidth;
                }
            }

            abs_max_per_window[win] = max;

        }

    } /* for (win) */

    return min_q;

} /* normalize() */
