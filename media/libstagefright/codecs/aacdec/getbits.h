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

 Pathname: getbits.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Update comments for the structure

 Description: Move structur to another file

 Who:                                            Date: MM/DD/YYYY
 Description:


------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 Header file for the function getbits().

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef GETBITS_H
#define GETBITS_H

#ifdef __cplusplus
extern "C"
{
#endif

    /*----------------------------------------------------------------------------
    ; INCLUDES
    ----------------------------------------------------------------------------*/
#include "pv_audio_type_defs.h"
#include "ibstream.h"

    /*----------------------------------------------------------------------------
    ; MACROS
    ; Define module specific macros here
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; DEFINES
    ; Include all pre-processor statements here.
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; EXTERNAL VARIABLES REFERENCES
    ; Declare variables used in this module but defined elsewhere
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; SIMPLE TYPEDEF'S
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; ENUMERATED TYPEDEF'S
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; STRUCTURES TYPEDEF'S
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; GLOBAL FUNCTION DEFINITIONS
    ; Function Prototype declaration
    ----------------------------------------------------------------------------*/

#define INBUF_ARRAY_INDEX_SHIFT  (3)
#define INBUF_BIT_WIDTH         (1<<(INBUF_ARRAY_INDEX_SHIFT))
#define INBUF_BIT_MODULO_MASK   ((INBUF_BIT_WIDTH)-1)

#define MAX_GETBITS             (25)

#define  CHECK_INPUT_BUFFER_LIMITS  1

    __inline UInt32 getbits(
        const UInt  neededBits,
        BITS       *pInputStream)
    {
        UInt32   returnValue = 0;
        UInt     offset;
        UInt     bitIndex;
        UChar    *pElem;        /* Needs to be same type as pInput->pBuffer */

        offset = (pInputStream->usedBits) >> INBUF_ARRAY_INDEX_SHIFT;

        pElem = pInputStream->pBuffer + offset;

#if CHECK_INPUT_BUFFER_LIMITS

        offset =  pInputStream->inputBufferCurrentLength - offset;
        /*  check if access to input buffer does not go beyond boundaries */
        if (offset > 3)
        {
            returnValue = (((UInt32) * (pElem)) << 24) |
                          (((UInt32) * (pElem + 1)) << 16) |
                          (((UInt32) * (pElem + 2)) << 8) |
                          ((UInt32) * (pElem + 3));
        }
        else  /*  then access only available bytes  */
        {
            /*  Access to the bitstream beyond frame boundaries are not allowed,
             *  Here, only what was available before the end of the frame will
             *  be processed. Non-accessible bytes will be filled in with zeros.
             *  Zero values guarantees that the data structures are filled in with values
             *  that eventually will signal an error (like invalid parameters) or that allow
             *  completion of the parsing routine.
             *  Overrun is detected on file pvmp4audiodecodeframe.cpp.
             */
            switch (offset)
            {
                case 3:
                    returnValue  = (((UInt32) * (pElem + 2)) << 8);
                case 2:
                    returnValue |= (((UInt32) * (pElem + 1)) << 16);
                case 1:
                    returnValue |= (((UInt32) * (pElem)) << 24);
                default:
                    break;
            }
        }


#else

        returnValue = (((UInt32) * (pElem)) << 24) |
                      (((UInt32) * (pElem + 1)) << 16) |
                      (((UInt32) * (pElem + 2)) << 8) |
                      ((UInt32) * (pElem + 3));
#endif

        /* Remove extra high bits by shifting up */
        bitIndex = (UInt)((pInputStream->usedBits) & INBUF_BIT_MODULO_MASK);

        /* This line is faster way to mask off the high bits. */
        returnValue = returnValue << (bitIndex);

        /* Move the field down. */
        returnValue = returnValue >> (32 - neededBits);

        pInputStream->usedBits += neededBits;

        return (returnValue);

    }



    __inline UInt get1bits(
        BITS       *pInputStream)
    {
        UInt     returnValue;
        UInt     offset;
        UInt     bitIndex;
        UChar    *pElem;        /* Needs to be same type as pInput->pBuffer */

        offset = (pInputStream->usedBits) >> INBUF_ARRAY_INDEX_SHIFT;

        pElem = pInputStream->pBuffer + offset;

#if CHECK_INPUT_BUFFER_LIMITS
        returnValue = (offset < pInputStream->inputBufferCurrentLength) ? ((UInt) * (pElem)) : 0;
#else
        returnValue = ((UInt32) * (pElem));
#endif


        /* Remove extra high bits by shifting up */
        bitIndex = (UInt)((pInputStream->usedBits++) & INBUF_BIT_MODULO_MASK);

        /* This line is faster way to mask off the high bits. */
        returnValue = 0xFF & (returnValue << (bitIndex));

        /* Move the field down. */

        return ((UInt)(returnValue >> 7));

    }



    __inline UInt get9_n_lessbits(
        const UInt  neededBits,
        BITS       *pInputStream)

    {
        UInt     returnValue;
        UInt     offset;
        UInt     bitIndex;
        UChar    *pElem;        /* Needs to be same type as pInput->pBuffer */

        offset = (pInputStream->usedBits) >> INBUF_ARRAY_INDEX_SHIFT;

        pElem = pInputStream->pBuffer + offset;

#if CHECK_INPUT_BUFFER_LIMITS


        offset =  pInputStream->inputBufferCurrentLength - offset;
        /*  check if access to input buffer does not go beyond boundaries */
        if (offset > 1)
        {
            returnValue = (((UInt32) * (pElem)) << 8) |
                          ((UInt32) * (pElem + 1));
        }
        else  /*  then access only available bytes  */
        {
            /*  Access to the bitstream beyond frame boundaries are not allowed,
             *  Here, only what was available before the end of the frame will
             *  be processed. Non-accessible bytes will be filled in with zeros.
             *  Zero values guarantees that the data structures are filled in with values
             *  that eventually will signal an error (like invalid parameters) or that allow
             *  completion of the parsing routine.
             *  Overrun is detected on file pvmp4audiodecodeframe.cpp
             */
            switch (offset)
            {
                case 1:
                    returnValue  = (((UInt32) * (pElem)) << 8);
                    break;
                default:
                    returnValue = 0;
                    break;
            }
        }


#else
        returnValue = (((UInt32) * (pElem)) << 8) |
                      ((UInt32) * (pElem + 1)) ;
#endif

        /* Remove extra high bits by shifting up */
        bitIndex = (UInt)((pInputStream->usedBits) & INBUF_BIT_MODULO_MASK);

        pInputStream->usedBits += neededBits;

        /* This line is faster way to mask off the high bits. */
        returnValue = 0xFFFF & (returnValue << (bitIndex));

        /* Move the field down. */

        return (UInt)(returnValue >> (16 - neededBits));

    }

    __inline UInt32 get17_n_lessbits(
        const UInt  neededBits,
        BITS       *pInputStream)
    {
        UInt32   returnValue;
        UInt     offset;
        UInt     bitIndex;
        UChar    *pElem;        /* Needs to be same type as pInput->pBuffer */

        offset = (pInputStream->usedBits) >> INBUF_ARRAY_INDEX_SHIFT;

        pElem = pInputStream->pBuffer + offset;

#if CHECK_INPUT_BUFFER_LIMITS

        offset =  pInputStream->inputBufferCurrentLength - offset;
        /*  check if access to input buffer does not go beyond boundaries */

        if (offset > 2)
        {
            returnValue = (((UInt32) * (pElem)) << 16) |
                          (((UInt32) * (pElem + 1)) << 8) |
                          ((UInt32)  * (pElem + 2));
        }
        else   /*  then access only available bytes  */
        {
            /*  Access to the bitstream beyond frame boundaries are not allowed,
             *  Here, only what was available before the end of the frame will
             *  be processed. Non-accessible bytes will be filled in with zeros.
             *  Zero values guarantees that the data structures are filled in with values
             *  that eventually will signal an error (like invalid parameters) or that allow
             *  completion of the parsing routine.
             *  Overrun is detected on file pvmp4audiodecodeframe.cpp
             */
            returnValue = 0;
            switch (offset)
            {
                case 2:
                    returnValue  = (((UInt32) * (pElem + 1)) << 8);
                case 1:
                    returnValue |= (((UInt32) * (pElem)) << 16);
                default:
                    break;
            }
        }

#else

        returnValue = (((UInt32) * (pElem)) << 16) |
                      (((UInt32) * (pElem + 1)) << 8) |
                      ((UInt32)  * (pElem + 2));
#endif

        /* Remove extra high bits by shifting up */
        bitIndex = (UInt)((pInputStream->usedBits) & INBUF_BIT_MODULO_MASK);

        /* This line is faster way to mask off the high bits. */
        returnValue = 0xFFFFFF & (returnValue << (bitIndex));

        /* Move the field down. */
        returnValue = returnValue >> (24 - neededBits);

        pInputStream->usedBits += neededBits;

        return (returnValue);

    }

#ifdef __cplusplus
}
#endif

/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/
#endif /* GETBITS_H*/


