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
------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    pInputStream = pointer to a BITS structure that holds information
                   regarding the input stream.

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    None

 Pointers and Buffers Modified:
    pInputStream->usedBits is rounded up to a number that represents the next
    byte boundary.

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    Adquire Data Stream element (DSE) from raw bitstream
    At this time this function just drops the information.

------------------------------------------------------------------------------
 REQUIREMENTS

  This function shall not use global or static variables.

------------------------------------------------------------------------------
 REFERENCES

 (1) MPEG-2 NBC Audio Decoder
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

void byte_align(
    BITS  *pInputStream)

    MODIFYING(pInputStream->usedBits = pInputStream->usedBits +
                (pInputStream->usedBits + 7) % 8)

    RETURN(nothing)

------------------------------------------------------------------------------
 RESOURCES USED

 STACK USAGE:

     where:

 DATA MEMORY USED: x words

 PROGRAM MEMORY USED: x words

 CLOCK CYCLES:

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#include "pv_audio_type_defs.h"
#include "get_dse.h"
#include "ibstream.h"
#include "getbits.h"
#include "s_bits.h"


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

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

void get_dse(
    Char    *DataStreamBytes,
    BITS    *pInputStream)
{
    Int i;
    Int data_byte_align_flag;
    UInt count;
    Int esc_count;
    Char    *pDataStreamBytes;

    pDataStreamBytes = DataStreamBytes;

    /*
     *  Get element instance tag  ( 4 bits)
     *  ( max of 16 per raw data block)
     */
    get9_n_lessbits(LEN_TAG, pInputStream);

    /*
     *  get data_byte_align_flag ( 1 bit0 to see if byte alignment is
     *  performed within the DSE
     */
    data_byte_align_flag = get1bits(pInputStream);

    /*
     *  get count ( 8 bits)
     */
    count =  get9_n_lessbits(LEN_D_CNT, pInputStream);

    /*
     *  if count == 255, its value it is incremented  by a
     *  second 8 bit value, esc_count. This final value represents
     *  the number of bytes in the DSE
     */
    if (count == (1 << LEN_D_CNT) - 1)
    {
        esc_count = (Int)get9_n_lessbits(LEN_D_ESC, pInputStream);  /* 8 bits */
        count +=  esc_count;
    }

    /*
     *  Align if flag is set
     */
    if (data_byte_align_flag)
    {
        byte_align(pInputStream);
    }

    for (i = count; i != 0; i--)
    {
        *(pDataStreamBytes++) = (Char) get9_n_lessbits(
                                    LEN_BYTE,
                                    pInputStream);
    }

    return;

} /* end get_dse */

