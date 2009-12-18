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

 Description: Separated modules into one function per file and put into
    new template.

 Description: Optimizing C code and adding comments.  Also changing variable
    names to make them more meaningful.

 Who:                   Date:
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:

    Rec_Y = pointer to 0th position in buffer containing luminance values
        of type uint8.
    y_start = value of y coordinate of type int that specifies the first
        row of pixels to be used in the filter algorithm.
    x_start = value of x coordinate of type int that specifies the first
        column of pixels to be used in the filter algorithm.
    y_blk_start = value of the y coordinate of type int that specifies the
        row of pixels which contains the start of a block. The row
        specified by y_blk_start+BLK_SIZE is the last row of pixels
        that are used in the filter algorithm.
    x_blk_start = value of the x coordinate of type int that specifies the
        column of pixels which contains the start of a block.  The
        column specified by x_blk_start+BLK_SIZE is the last column of
        pixels that are used in the filter algorithm.
    thr = value of type int that is compared to the elements in Rec_Y to
        determine if a particular value in Rec_Y will be modified by
        the filter or not
    width = value of type int that specifies the width of the display
        in pixels (or pels, equivalently).
    max_diff = value of type int that specifies the value that may be added
        or subtracted from the pixel in Rec_Y that is being filtered
        if the filter algorithm decides to change that particular
        pixel's luminance value.


 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    None

 Pointers and Buffers Modified:
    Buffer pointed to by Rec_Y is modified with the filtered
    luminance values.

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function implements a motion compensated noise filter using adaptive
 weighted averaging of luminance values.  *Rec_Y contains the luminance values
 that are being filtered.

 The picture below depicts a 3x3 group of pixel luminance values.  The "u", "c",
 and "l" stand for "upper", "center" and "lower", respectively.  The location
 of pelc0 is specified by x_start and y_start in the 1-D array "Rec_Y" as
 follows (assuming x_start=0):

 location of pelc0 = [(y_start+1) * width] + x_start

 Moving up or down 1 row (moving from pelu2 to pelc2, for example) is done by
 incrementing or decrementing "width" elements within Rec_Y.

 The coordinates of the upper left hand corner of a block (not the group of
 9 pixels depicted in the figure below) is specified by
 (y_blk_start, x_blk_start).  The width and height of the block is BLKSIZE.
 (y_start,x_start) may be specified independently of (y_blk_start, x_blk_start).

    (y_start,x_start)
 -----------|--------------------------
    |   |   |   |   |
    |   X   | pelu1 | pelu2 |
    | pelu0 |   |   |
    |   |   |   |
 --------------------------------------
    |   |   |   |
    | pelc0 | pelc1 | pelc2 |
    |   |   |   |
    |   |   |   |
 --------------------------------------
    |   |   |   |
    | pell0 | pell1 | pell2 |
    |   |   |   |
    |   |   |   |
 --------------------------------------

 The filtering of the luminance values is achieved by comparing the 9
 luminance values to a threshold value ("thr") and then changing the
 luminance value of pelc1 if all of the values are above or all of the values
 are below the threshold.  The amount that the luminance value is changed
 depends on a weighted sum of the 9 luminance values. The position of Pelc1
 is then advanced to the right by one (as well as all of the surrounding pixels)
 and the same calculation is performed again for the luminance value of the new
 Pelc1. This continues row-wise until pixels in the last row of the block are
 filtered.


------------------------------------------------------------------------------
 REQUIREMENTS

 None.

------------------------------------------------------------------------------
 REFERENCES

 ..\corelibs\decoder\common\src\post_proc.c

------------------------------------------------------------------------------
 PSEUDO-CODE

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
#include    "mp4dec_lib.h"
#include    "post_proc.h"
#include    "mp4def.h"

#define OSCL_DISABLE_WARNING_CONV_POSSIBLE_LOSS_OF_DATA

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
void AdaptiveSmooth_NoMMX(
    uint8 *Rec_Y,       /* i/o  */
    int y_start,        /* i    */
    int x_start,        /* i    */
    int y_blk_start,    /* i    */
    int x_blk_start,    /* i    */
    int thr,        /* i    */
    int width,      /* i    */
    int max_diff        /* i    */
)
{

    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    int  sign_v[15];
    int sum_v[15];
    int *sum_V_ptr;
    int *sign_V_ptr;
    uint8 pelu;
    uint8 pelc;
    uint8 pell;
    uint8 *pelp;
    uint8 oldrow[15];
    int  sum;
    int sum1;
    uint8 *Rec_Y_ptr;
    int32  addr_v;
    int row_cntr;
    int col_cntr;

    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/
    /*  first row
    */
    addr_v = (int32)(y_start + 1) * width;  /* y coord of 1st element in the row  /
                     /containing pelc pixel /     */
    Rec_Y_ptr = &Rec_Y[addr_v + x_start];  /* initializing pointer to
                           /  pelc0 position  */
    sum_V_ptr = &sum_v[0];  /* initializing pointer to 0th element of array
                /   that will contain weighted sums of pixel
                /   luminance values */
    sign_V_ptr = &sign_v[0];  /*  initializing pointer to 0th element of
                  /   array that will contain sums that indicate
                  /    how many of the 9 pixels are above or below
                  /    the threshold value (thr)    */
    pelp = &oldrow[0];  /* initializing pointer to the 0th element of array
                /    that will contain current values of pelc that
                /   are saved and used as values of pelu when the
                /   next row of pixels are filtered */

    pelu = *(Rec_Y_ptr - width);  /* assigning value of pelu0 to pelu  */
    *pelp++ = pelc = *Rec_Y_ptr; /* assigning value of pelc0 to pelc and
                     /  storing this value in pelp which
                     /   will be used as value of pelu0 when
                     /  next row is filtered */
    pell = *(Rec_Y_ptr + width);  /* assigning value of pell0 to pell */
    Rec_Y_ptr++; /* advancing pointer from pelc0 to pelc1 */
    *sum_V_ptr++ = pelu + (pelc << 1) + pell;  /* weighted sum of pelu0,
                         /  pelc0 and pell0  */
    /* sum of 0's and 1's (0 if pixel value is below thr, 1 if value
    /is above thr)  */
    *sign_V_ptr++ = INDEX(pelu, thr) + INDEX(pelc, thr) + INDEX(pell, thr);


    pelu = *(Rec_Y_ptr - width);  /* assigning value of pelu1 to pelu */
    *pelp++ = pelc = *Rec_Y_ptr; /* assigning value of pelc1 to pelc and
                     /  storing this value in pelp which
                     /  will be used as the value of pelu1 when
                     /  next row is filtered */
    pell = *(Rec_Y_ptr + width);  /* assigning value of pell1 to pell */
    Rec_Y_ptr++;  /* advancing pointer from pelc1 to pelc2 */
    *sum_V_ptr++ = pelu + (pelc << 1) + pell; /* weighted sum of pelu1,
                        / pelc1 and pell1  */
    /* sum of 0's and 1's (0 if pixel value is below thr, 1 if value
    /is above thr)  */
    *sign_V_ptr++ = INDEX(pelu, thr) + INDEX(pelc, thr) + INDEX(pell, thr);

    /* The loop below performs the filtering for the first row of
    /   pixels in the region.  It steps across the remaining pixels in
    /   the row and alters the luminance value of pelc1 if necessary,
    /   depending on the luminance values of the adjacent pixels*/

    for (col_cntr = (x_blk_start + BLKSIZE - 1) - x_start; col_cntr > 0; col_cntr--)
    {
        pelu = *(Rec_Y_ptr - width);  /* assigning value of pelu2 to
                        /   pelu */
        *pelp++ = pelc = *Rec_Y_ptr; /* assigning value of pelc2 to pelc
                         / and storing this value in pelp
                         / which will be used   as value of pelu2
                         / when next row is filtered */
        pell = *(Rec_Y_ptr + width); /* assigning value of pell2 to pell */

        /* weighted sum of pelu1, pelc1 and pell1  */
        *sum_V_ptr = pelu + (pelc << 1) + pell;
        /* sum of 0's and 1's (0 if pixel value is below thr,
        /1 if value is above thr)  */
        *sign_V_ptr = INDEX(pelu, thr) + INDEX(pelc, thr) +
                      INDEX(pell, thr);
        /* the value of sum1 indicates how many of the 9 pixels'
        /luminance values are above or equal to thr */
        sum1 = *(sign_V_ptr - 2) + *(sign_V_ptr - 1) + *sign_V_ptr;

        /* alter the luminance value of pelc1 if all 9 luminance values
        /are above or equal to thr or if all 9 values are below thr */
        if (sum1 == 0 || sum1 == 9)
        {
            /* sum is a weighted average of the 9 pixel luminance
            /values   */
            sum = (*(sum_V_ptr - 2) + (*(sum_V_ptr - 1) << 1) +
                   *sum_V_ptr + 8) >> 4;

            Rec_Y_ptr--;  /* move pointer back to pelc1  */
            /* If luminance value of pelc1 is larger than
            / sum by more than max_diff, then subract max_diff
            / from luminance value of pelc1*/
            if ((int)(*Rec_Y_ptr - sum) > max_diff)
            {
                sum = *Rec_Y_ptr - max_diff;
            }
            /* If luminance value of pelc1 is smaller than
            / sum by more than max_diff, then add max_diff
            / to luminance value of pelc1*/
            else if ((int)(*Rec_Y_ptr - sum) < -max_diff)
            {
                sum = *Rec_Y_ptr + max_diff;
            }
            *Rec_Y_ptr++ = sum; /* assign value of sum to pelc1
                         and advance pointer to pelc2 */
        }
        Rec_Y_ptr++; /* advance pointer to new value of pelc2
                 /   old pelc2 is now treated as pelc1*/
        sum_V_ptr++; /* pointer is advanced so next weighted sum may
                 /  be saved */
        sign_V_ptr++; /* pointer is advanced so next sum of 0's and
                  / 1's may be saved  */
    }

    /* The nested loops below perform the filtering for the remaining rows */

    addr_v = (y_start + 2) * width;  /* advance addr_v to the next row
                     /   (corresponding to pell0)*/
    /* The outer loop steps throught the rows.   */
    for (row_cntr = (y_blk_start + BLKSIZE) - (y_start + 2); row_cntr > 0; row_cntr--)
    {
        Rec_Y_ptr = &Rec_Y[addr_v + x_start]; /* advance pointer to
            /the old pell0, which has become the new pelc0 */
        addr_v += width;  /* move addr_v down 1 row */
        sum_V_ptr = &sum_v[0];  /* re-initializing pointer */
        sign_V_ptr = &sign_v[0];  /* re-initilaizing pointer */
        pelp = &oldrow[0]; /* re-initializing pointer */

        pelu = *pelp; /* setting pelu0 to old value of pelc0 */
        *pelp++ = pelc = *Rec_Y_ptr;
        pell = *(Rec_Y_ptr + width);
        Rec_Y_ptr++;
        *sum_V_ptr++ = pelu + (pelc << 1) + pell;
        *sign_V_ptr++ = INDEX(pelu, thr) + INDEX(pelc, thr) +
                        INDEX(pell, thr);

        pelu = *pelp; /* setting pelu1 to old value of pelc1 */
        *pelp++ = pelc = *Rec_Y_ptr;
        pell = *(Rec_Y_ptr + width);
        Rec_Y_ptr++;
        *sum_V_ptr++ = pelu + (pelc << 1) + pell;
        *sign_V_ptr++ = INDEX(pelu, thr) + INDEX(pelc, thr) +
                        INDEX(pell, thr);
        /* The inner loop steps through the columns */
        for (col_cntr = (x_blk_start + BLKSIZE - 1) - x_start; col_cntr > 0; col_cntr--)
        {
            pelu = *pelp; /* setting pelu2 to old value of pelc2 */
            *pelp++ = pelc = *Rec_Y_ptr;
            pell = *(Rec_Y_ptr + width);

            *sum_V_ptr = pelu + (pelc << 1) + pell;
            *sign_V_ptr = INDEX(pelu, thr) + INDEX(pelc, thr) +
                          INDEX(pell, thr);

            sum1 = *(sign_V_ptr - 2) + *(sign_V_ptr - 1) + *sign_V_ptr;
            /* the "if" statement below is the same as the one in
            / the first loop */
            if (sum1 == 0 || sum1 == 9)
            {
                sum = (*(sum_V_ptr - 2) + (*(sum_V_ptr - 1) << 1) +
                       *sum_V_ptr + 8) >> 4;

                Rec_Y_ptr--;
                if ((int)(*Rec_Y_ptr - sum) > max_diff)
                {
                    sum = *Rec_Y_ptr - max_diff;
                }
                else if ((int)(*Rec_Y_ptr - sum) < -max_diff)
                {
                    sum = *Rec_Y_ptr + max_diff;
                }
                *Rec_Y_ptr++ = (uint8) sum;
            }
            Rec_Y_ptr++;
            sum_V_ptr++;
            sign_V_ptr++;
        }
    }

    /*----------------------------------------------------------------------------
    ; Return nothing or data or data pointer
    ----------------------------------------------------------------------------*/
    return;
}
#endif
