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
    xpred = x-axis coordinate of the block used for prediction (int)
    ypred = y-axis coordinate of the block used for prediction (int)
    pp_dec_u = pointer to the post processing semaphore for chrominance
               (uint8)
    pstprcTypPrv = pointer the previous frame's post processing type
                   (uint8)
    dx = horizontal component of the motion vector (int)
    dy = vertical component of the motion vector (int)
    mvwidth = number of blocks per row in the luminance VOP (int)
    height = luminance VOP height in pixels (int)
    size = total number of pixel in the current luminance VOP (int)
    mv_loc = flag indicating location of the motion compensated
         (x,y) position with respect to the luminance MB (int);
         0 -> inside MB, 1 -> outside MB
    msk_deblock = flag indicating whether to perform deblocking
              (msk_deblock = 0) or not (msk_deblock = 1) (uint8)

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    None

 Pointers and Buffers Modified:
    pp_dec_u contents are the updated semaphore propagation data

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This functions performs post processing semaphore propagation processing
 after chrominance prediction in interframe processing mode.

*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include    "mp4dec_api.h"
#include    "mp4def.h"

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
#ifdef __cplusplus
extern "C"
{
#endif
    /*----------------------------------------------------------------------------
    ; FUNCTION CODE
    ----------------------------------------------------------------------------*/
    void pp_semaphore_chroma_inter(
        int xpred,      /* i */
        int ypred,      /* i */
        uint8   *pp_dec_u,  /* i/o */
        uint8   *pstprcTypPrv,  /* i */
        int dx,     /* i */
        int dy,     /* i */
        int mvwidth,    /* i */
        int height,     /* i */
        int32   size,       /* i */
        int mv_loc,     /* i */
        uint8   msk_deblock /* i */
    )
    {
        /*----------------------------------------------------------------------------
        ; Define all local variables
        ----------------------------------------------------------------------------*/
        int mmvy, mmvx, nmvy, nmvx;
        uint8 *pp_prev1, *pp_prev2, *pp_prev3, *pp_prev4;

        /*----------------------------------------------------------------------------
        ; Function body here
        ----------------------------------------------------------------------------*/

        /* 09/28/2000, modify semaphore propagation to */
        /* accommodate smart indexing */
        mmvx = xpred >> 4;  /* block x coor */
        nmvx = mmvx;

        mmvy = ypred >> 4;  /* block y coor */
        nmvy = mmvy;

        /* Check if MV is outside the frame */
        if (mv_loc == 1)
        {
            /* Perform boundary check */
            if (nmvx < 0)
            {
                nmvx = 0;
            }
            else if (nmvx > mvwidth - 1)
            {
                nmvx = mvwidth - 1;
            }

            if (nmvy < 0)
            {
                nmvy = 0;
            }
            else if (nmvy > (height >> 4) - 1)
            {
                nmvy = (height >> 4) - 1;
            }
        }

        /* Calculate pointer to first chrominance b semaphores in       */
        /* pstprcTypPrv, i.e., first chrominance b semaphore is in      */
        /* (pstprcTypPrv + (size>>6)).                  */
        /* Since total number of chrominance blocks per row in a VOP    */
        /* is half of the total number of luminance blocks per row in a */
        /* VOP, we use (mvwidth >> 1) when calculating the row offset.  */
        pp_prev1 = pstprcTypPrv + (size >> 6) + nmvx + nmvy * (mvwidth >> 1) ;

        /* Check if MV is a multiple of 16 */
        /*  1/5/01, make sure it doesn't go out of bound */
        if (((dy&0xF) != 0) && (mmvy + 1 < (height >> 4) - 1))
        {   /* dy is not a multiple of 16 */

            /* pp_prev3 is the block below pp_prev1 block */
            pp_prev3 = pp_prev1 + (mvwidth >> 1);
        }
        else
        {   /* dy is a multiple of 16 */
            pp_prev3 = pp_prev1;
        }

        /*  1/5/01, make sure it doesn't go out of bound */
        if (((dx&0xF) != 0) && (mmvx + 1 < (mvwidth >> 1) - 1))
        {   /* dx is not a multiple of 16 */

            /* pp_prev2 is the block to the right of pp_prev1 block */
            pp_prev2 = pp_prev1 + 1;

            /* pp_prev4 is the block to the right of the block */
            /* below pp_prev1 block                */
            pp_prev4 = pp_prev3 + 1;
        }
        else
        {   /* dx is a multiple of 16 */

            pp_prev2 = pp_prev1;
            pp_prev4 = pp_prev3;
        }

        /* Advance offset to location of first Chrominance R semaphore in */
        /* pstprcTypPrv. Since the number of pixels in a Chrominance VOP  */
        /* is (number of pixels in Luminance VOP/4), and there are 64     */
        /* pixels in an 8x8 Chrominance block, the offset can be      */
        /* calculated as:                         */
        /*  mv_loc = (number of pixels in Luminance VOP/(4*64))   */
        /*         = size/256 = size>>8               */
        mv_loc = (size >> 8);

        /*  11/3/00, change the propagation for deblocking */
        if (msk_deblock == 0)
        {

            /* Deblocking semaphore propagation for Chrominance */
            /* b semaphores                     */
            *(pp_dec_u) = 0;

            /* Advance offset to point to Chrominance r semaphores */
            pp_dec_u += mv_loc;

            /* Deblocking semaphore propagation for Chrominance */
            /* r semaphores                     */
            *(pp_dec_u) = 0;
        }
        else
        {
            /* Deringing semaphore propagation for Chrominance B block */
            if ((*(pp_dec_u)&4) == 0)
            {
                *(pp_dec_u) |= ((*(pp_prev1) | *(pp_prev2) |
                                 *(pp_prev3) | *(pp_prev4)) & 0x4);
            }

            /* Advance offset to point to Chrominance r semaphores */
            pp_dec_u += mv_loc;
            pp_prev1 += mv_loc;
            pp_prev2 += mv_loc;
            pp_prev3 += mv_loc;
            pp_prev4 += mv_loc;

            /* Deringing semaphore propagation for Chrominance R */
            if ((*(pp_dec_u)&4) == 0)
            {
                *(pp_dec_u) |= ((*(pp_prev1) | *(pp_prev2) |
                                 *(pp_prev3) | *(pp_prev4)) & 0x4);
            }
        }

        /*----------------------------------------------------------------------------
        ; Return nothing or data or data pointer
        ----------------------------------------------------------------------------*/
        return;
    }
#ifdef __cplusplus
}
#endif

#endif
