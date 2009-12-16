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
    q_block = pointer to buffer of inverse quantized DCT coefficients of type
              int for intra-VOP mode or buffer of residual data of type int
              for inter-VOP mode

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    postmode = post processing semaphore with the vertical deblocking,
               horizontal deblocking, and deringing bits set up accordingly

 Pointers and Buffers Modified:
    None

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function sets up the postmode semaphore based on the contents of the
 buffer pointed to by q_block. The function starts out with the assumption
 that all entries of q_block, except for the first entry (q_block[0]), are
 zero. This case can induce horizontal and vertical blocking artifacts,
 therefore, both horizontal and vertical deblocking bits are enabled.

 The following conditions are tested when setting up the horizontal/vertical
 deblocking and deringing bits:
 1. When only the elements of the top row of the B_SIZE x B_SIZE block
    (q_block[n], n = 0,..., B_SIZE-1) are non-zero, vertical blocking artifacts
    may result, therefore, only the vertical deblocking bit is enabled.
    Otherwise, the vertical deblocking bit is disabled.
 2. When only the elements of the far left column of the B_SIZE x B_SIZE block
    (q_block[n*B_SIZE], n = 0, ..., B_SIZE-1) are non-zero, horizontal blocking
    artifacts may result, therefore, only the horizontal deblocking bit is
    enabled. Otherwise, the horizontal deblocking bit is disabled.
 3. If any non-zero elements exist in positions other than q_block[0],
    q_block[1], or q_block[B_SIZE], the deringing bit is enabled. Otherwise,
    it is disabled.

 The 3 least significant bits of postmode defines vertical or horizontal
 deblocking and deringing.

 The valid values are shown below:
 -------------------------------------------------------
 |           Type                 | Enabled | Disabled |
 -------------------------------------------------------
 | Vertical Deblocking (Bit #0)   |    1    |     0    |
 -------------------------------------------------------
 | Horizontal Deblocking (Bit #1) |    1    |     0    |
 -------------------------------------------------------
 | Deringing (Bit #2)             |    1    |     0    |
 -------------------------------------------------------

*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include    "mp4dec_lib.h"
#include    "mp4def.h"
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
int PostProcSemaphore(
    int16 *q_block)
{
    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    int i, j;

    /* Set default value to vertical and horizontal deblocking enabled */
    /* Initial assumption is that only q_block[0] element is non-zero, */
    /* therefore, vertical and horizontal deblocking bits are set to 1 */
    int postmode = 0x3;

    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/
    /* Vertical deblocking bit is enabled when only the entire top row of   */
    /* the B_SIZE x B_SIZE block, i.e., q_block[n], n = 0,..., B_SIZE-1,    */
    /* are non-zero. Since initial assumption is that all elements, except  */
    /* q_block[0], is zero, we need to check the remaining elements in the  */
    /* top row to  determine if all or some are non-zero.                   */
    if (q_block[1] != 0)
    {
        /* At this point, q_block[0] and q_block[1] are non-zero, while */
        /* q_block[n], n = 2,..., B_SIZE-1, are zero. Therefore, we     */
        /* need to disable vertical deblocking                          */
        postmode &= 0xE;
    }

    for (i = 2; i < B_SIZE; i++)
    {
        if (q_block[i])
        {
            /* Check if q_block[n], n = 2,..., B_SIZE-1, are non-zero.*/
            /* If any of them turn out to be non-zero, we need to     */
            /* disable vertical deblocking.                           */
            postmode &= 0xE;

            /* Deringing is enabled if any nonzero elements exist in */
            /* positions other than q_block[0], q_block[1] or        */
            /* q_block[B_SIZE].                                      */
            postmode |= 0x4;

            break;
        }
    }

    /* Horizontal deblocking bit is enabled when only the entire far */
    /* left column, i.e., q_block[n*B_SIZE], n = 0, ..., B_SIZE-1,   */
    /* are non-zero. Since initial assumption is that all elements,  */
    /* except q_block[0], is zero, we need to check the remaining    */
    /* elements in the far left column to determine if all or some   */
    /* are non-zero.                                                 */
    if (q_block[B_SIZE])
    {
        /* At this point, only q_block[0] and q_block[B_SIZE] are non-zero, */
        /* while q_block[n*B_SIZE], n = 2, 3,..., B_SIZE-1, are zero.       */
        /* Therefore, we need to disable horizontal deblocking.             */
        postmode &= 0xD;
    }

    for (i = 16; i < NCOEFF_BLOCK; i += B_SIZE)
    {
        if (q_block[i])
        {
            /* Check if q_block[n], n = 2*B_SIZE,...,(B_SIZE-1)*B_SIZE,  */
            /* are non-zero. If any of them turn out to be non-zero,     */
            /* we need to disable horizontal deblocking.                 */
            postmode &= 0xD;

            /* Deringing is enabled if any nonzero elements exist in */
            /* positions other than q_block[0], q_block[1] or        */
            /* q_block[B_SIZE].                                      */
            postmode |= 0x4;

            break;
        }
    }

    /* At this point, only the first row and far left column elements */
    /* have been tested. If deringing bit is still not set at this    */
    /* point, check the rest of q_block to determine if the elements  */
    /* are non-zero. If all elements, besides q_block[0], q_block[1], */
    /* or q_block[B_SIZE] are non-zero, deringing bit must be set     */
    if ((postmode & 0x4) == 0)
    {
        for (i = 1; i < B_SIZE; i++)
        {
            for (j = 1; j < B_SIZE; j++)
            {
                if (q_block[(i<<3)+j])
                {
                    /* At this point, q_block[0] and another q_block */
                    /* element are non-zero, therefore, we need to   */
                    /* disable vertical and horizontal deblocking    */
                    postmode &= 0xC;

                    /* Deringing is enabled if any nonzero elements exist in */
                    /* positions other than q_block[0], q_block[1] or        */
                    /* q_block[B_SIZE].                                      */
                    postmode |= 0x4;

                    /* Set outer FOR loop count to B_SIZE to get out of */
                    /* outer FOR loop                                   */
                    i = B_SIZE;

                    /* Get out of inner FOR loop */
                    break;
                }
            }
        }
    }

    /*----------------------------------------------------------------------------
    ; Return nothing or data or data pointer
    ----------------------------------------------------------------------------*/
    return (postmode);
}

#endif
