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

 Pathname: getgroup.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Modified from original shareware code

 Description:  Modified to pass variables by reference to eliminate use
               of global variables.

 Description: (1) Modified to bring code in-line with PV standards
              (2) Eliminated if(first_short) statement, move for-loop
                  inside if statement
              (3) Modified UChar -> Int on data types of group

 Description: Replace some instances of getbits to get9_n_lessbits
              when the number of bits read is 9 or less.

 Who:                       Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    pInputStream = pointer to structure that holds input bitstream
                   information. Type BITS

    group[]     = array that holds the index of the first window in each
                  group. Type Int

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    None

 Pointers and Buffers Modified:
    group   contains the index of first windows in each group

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function reads the window grouping information associated with an
 Individual Channel Stream (ICS). If the window sequence is
 EIGHT_SHORT_SEQUENCE, scalefactor grouping information is transmitted. If a
 set of short windows form a group then they share scalefactors, intensity
 positions and PNS information. The first short window is always a new group
 so no grouping bit is transmitted. Subsequent short windows are in the same
 group if the associated grouping bit is 1. A new group is started if the
 associated grouping bit is 0.
 The pointer pGroup points to an array that stores the first window index
 of next group. For example, if the window grouping is:

 window index:    |  0  |  1  |  2  |  3  |  4  |  5  |  6  |  7  |
 grouping    :    |<-   0   ->|  1  |<-    2        ->|<-   3   ->|

 Then:

    group[]  :    |     2     |  3  |        6        |     8     |

------------------------------------------------------------------------------
 REQUIREMENTS

 This function should replace the contents of the array pointed to by pGroup
 with the first window indexes of groups starting from the second group.

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
                    p16 (Table 4.4.6)
                    p55 (Recovering ics_info)

------------------------------------------------------------------------------
 PSEUDO-CODE

    IF (pFrameInfo->coef_per_win[0] > SN2)

        *pGroup++ = 1;
        *pGroup   = 1;

    ELSE

        FOR (win = 1; win < pFrameInfo->num_win; win++)

            IF (getbits(1,pInputStream) == 0)

                *pGroup++ = win;

            ENDIF

        ENDFOR (win)

        *pGroup = win;

    ENDIF(pFrameInfo)

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
#include    "huffman.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/
#define     SEVEN   7

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
void getgroup(
    Int         group[],
    BITS        *pInputStream)
{
    Int      win;
    Int     *pGroup;
    UInt     mask;
    UInt     groupBits;

    pGroup      = group;

    mask        = 0x40;

    /* only short-window sequences are grouped!
     * first short window is always a new group,
     * start reading bitstream from the second
     * window, a new group is indicated by an
     * "0" bit in the input stream
     */
    groupBits =
        get9_n_lessbits(
            SEVEN,
            pInputStream);

    for (win = 1; win < NUM_SHORT_WINDOWS; win++)
    {
        if ((groupBits & mask) == 0)
        {
            *pGroup++ = win;

        } /* if (groupBits) */

        mask >>= 1;

    } /* for (win) */

    *pGroup = win;

} /* getgroup */
