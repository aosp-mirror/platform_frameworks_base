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
    input_ptr = pointer to the buffer containing values of type UChar
            in a 2D block of data.
    min_ptr = pointer to the minimum value of type Int to be found in a
          square block of size BLKSIZE contained in 2D block of data.
    max_ptr = pointer to the maximum value of type Int to be found in a
          square block of size BLKSIZE contained in 2D block of data.
    incr = value of type Int representing the width of 2D block of data.

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    None

 Pointers and Buffers Modified:
    min_ptr points to the found minimum value in the square block of
    size BLKSIZE contained in 2D block of data.

    max_ptr points to the found maximum value in the square block of
    size BLKSIZE contained in 2D block of data.

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function finds the maximum and the minimum values in a square block of
 data of size BLKSIZE * BLKSIZE. The data is contained in the buffer which
 represents a 2D block of data that is larger than BLKSIZE * BLKSIZE.
 This is illustrated below.

    mem loc x + 00h -> o o o o o o o o o o o o o o o o
    mem loc x + 10h -> o o o o o X X X X X X X X o o o
    mem loc x + 20h -> o o o o o X X X X X X X X o o o
    mem loc x + 30h -> o o o o o X X X X X X X X o o o
    mem loc x + 40h -> o o o o o X X X X X X X X o o o
    mem loc x + 50h -> o o o o o X X X X X X X X o o o
    mem loc x + 60h -> o o o o o X X X X X X X X o o o
    mem loc x + 70h -> o o o o o X X X X X X X X o o o
    mem loc x + 80h -> o o o o o X X X X X X X X o o o
    mem loc x + 90h -> o o o o o o o o o o o o o o o o
    mem loc x + A0h -> o o o o o o o o o o o o o o o o
    mem loc x + B0h -> o o o o o o o o o o o o o o o o

For illustration purposes, the diagram assumes that BLKSIZE is equal to 8
but this is not a requirement. In this diagram, the buffer starts at
location x but the input pointer, input_ptr, passed into this function
would be the first row of data to be searched which is at x + 15h. The
value of incr passed onto this function represents the amount the input_ptr
needs to be incremented to point to the next row of data.

This function compares each value in a row to the current maximum and
minimum. After each row, input_ptr is incremented to point to the next row.
This is repeated until all rows have been processed. When the search is
complete the location pointed to by min_ptr contains the minimum value
found and the location pointed to by max_ptr contains the maximum value found.

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include    "mp4dec_lib.h"
#include    "post_proc.h"

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

#ifdef PV_POSTPROC_ON
/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/
void  FindMaxMin(
    uint8 *input_ptr,
    int *min_ptr,
    int *max_ptr,
    int incr)
{
    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    register    uint    i, j;
    register    int min, max;

    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/
    max = min = *input_ptr;
    /*  incr = incr - BLKSIZE; */   /*  09/06/2001, already passed in as width - BLKSIZE */

    for (i = BLKSIZE; i > 0; i--)
    {
        for (j = BLKSIZE; j > 0; j--)
        {
            if (*input_ptr > max)
            {
                max = *input_ptr;
            }
            else if (*input_ptr < min)
            {
                min = *input_ptr;
            }
            input_ptr += 1;
        }

        /* set pointer to the beginning of the next row*/
        input_ptr += incr;
    }

    *max_ptr = max;
    *min_ptr = min;
    /*----------------------------------------------------------------------------
    ; Return nothing or data or data pointer
    ----------------------------------------------------------------------------*/
    return;
}
#endif
