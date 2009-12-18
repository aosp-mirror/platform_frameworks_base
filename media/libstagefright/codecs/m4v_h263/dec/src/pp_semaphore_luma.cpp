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
    xpred = x-axis coordinate of the MB used for prediction (int)
    ypred = y-axis coordinate of the MB used for prediction (int)
    pp_dec_y = pointer to the post processing semaphore for current
           luminance frame (uint8)
    pstprcTypPrv = pointer the previous frame's post processing type
                   (uint8)
    ll = pointer to the buffer (int)
    mv_loc = flag indicating location of the motion compensated
         (x,y) position with respect to the luminance MB (int);
         0 -> inside MB, 1 -> outside MB
    dx = horizontal component of the motion vector (int)
    dy = vertical component of the motion vector (int)
    mvwidth = number of blocks per row (int)
    width = luminance VOP width in pixels (int)
    height = luminance VOP height in pixels (int)

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    msk_deblock = flag that indicates whether deblocking is to be
              performed (msk_deblock = 0) or not (msk_deblock =
              1) (uint8)

 Pointers and Buffers Modified:
    pp_dec_y contents are the updated semapohore propagation data

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This functions performs post processing semaphore propagation processing
 after luminance prediction.

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
    uint8 pp_semaphore_luma(
        int xpred,      /* i */
        int ypred,      /* i */
        uint8   *pp_dec_y,  /* i/o */
        uint8   *pstprcTypPrv,  /* i */
        int *ll,        /* i */
        int *mv_loc,    /* i/o */
        int dx,     /* i */
        int dy,     /* i */
        int mvwidth,    /* i */
        int width,      /* i */
        int height      /* i */
    )
    {
        /*----------------------------------------------------------------------------
        ; Define all local variables
        ----------------------------------------------------------------------------*/
        int kk, mmvy, mmvx, nmvx, nmvy;
        uint8   *pp_prev1, *pp_prev2, *pp_prev3, *pp_prev4;
        uint8   msk_deblock = 0;        /*  11/3/00 */

        /*----------------------------------------------------------------------------
        ; Function body here
        ----------------------------------------------------------------------------*/
        /* Interframe Processing - 1 MV per MB */

        /* check whether the MV points outside the frame */
        if (xpred >= 0 && xpred <= ((width << 1) - (2*MB_SIZE)) && ypred >= 0 &&
                ypred <= ((height << 1) - (2*MB_SIZE)))
        {   /*****************************/
            /* (x,y) is inside the frame */
            /*****************************/

            /*  10/24/2000 post_processing semaphore */
            /* generation */

            /*  10/23/2000 no boundary checking*/
            *mv_loc = 0;

            /* Calculate block x coordinate. Divide by 16 is for  */
            /* converting half-pixel resolution to block          */
            mmvx = xpred >> 4;

            /* Calculate block y coordinate. Divide by 16 is for */
            /* converting half-pixel resolution to block         */
            mmvy = ypred >> 4;

            /* Find post processing semaphore location for block */
            /* used for prediction, i.e.,                */
            /* pp_prev1 = &pstprcTypPrv[mmvy*mvwidth][mmvx]      */
            pp_prev1 = pstprcTypPrv + mmvx + mmvy * mvwidth;

            /* Check if MV is a multiple of 16 */
            if ((dx&0xF) != 0)
            {   /* dx is not a multiple of 16 */

                /* pp_prev2 is the block to the right of */
                /* pp_prev1 block            */
                pp_prev2 = pp_prev1 + 1;

                if ((dy&0xF) != 0)
                {   /* dy is not a multiple of 16 */

                    /* pp_prev3 is the block below */
                    /* pp_prev1 block          */
                    pp_prev3 = pp_prev1 + mvwidth;
                }
                else
                {   /* dy is a multiple of 16 */

                    pp_prev3 = pp_prev1;
                }

                /* pp_prev4 is the block to the right of */
                /* pp_prev3 block.           */
                pp_prev4 = pp_prev3 + 1;
            }
            else
            {   /* dx is a multiple of 16 */

                pp_prev2 = pp_prev1;

                if ((dy&0xF) != 0)
                {   /* dy is not a multiple of 16 */

                    /* pp_prev3 is the block below */
                    /* pp_prev1 block.         */
                    pp_prev3 = pp_prev1 + mvwidth;
                }
                else
                {   /* dy is a multiple of 16 */

                    pp_prev3 = pp_prev1;
                    msk_deblock = 0x3;
                }

                pp_prev4 = pp_prev3;
            }

            /* Perform post processing semaphore propagation for each */
            /* of the 4 blocks in a MB.               */
            for (kk = 0; kk < 4; kk++)
            {
                /* Deringing semaphore propagation */
                if ((*(pp_dec_y) & 4) == 0)
                {
                    *(pp_dec_y) |= ((*(pp_prev1) | *(pp_prev2) |
                                     *(pp_prev3) | *(pp_prev4)) & 0x4);
                }
                /* Deblocking semaphore propagation */
                /*  11/3/00, change the propagation for deblocking */
                if (msk_deblock == 0)
                {
                    *(pp_dec_y) = 0;
                }

                pp_dec_y += ll[kk];
                pp_prev1 += ll[kk];
                pp_prev2 += ll[kk];
                pp_prev3 += ll[kk];
                pp_prev4 += ll[kk];
            }

        }
        else
        {   /******************************/
            /* (x,y) is outside the frame */
            /******************************/

            /*  10/24/2000 post_processing semaphore */
            /* generation */

            /*  10/23/2000 boundary checking*/
            *mv_loc = 1;

            /* Perform post processing semaphore propagation for each */
            /* of the 4 blocks in a MB.               */
            for (kk = 0; kk < 4; kk++)
            {
                /* Calculate block x coordinate and round (?).  */
                /* Divide by 16 is for converting half-pixel    */
                /* resolution to block.             */
                mmvx = (xpred + ((kk & 1) << 3)) >> 4;
                nmvx = mmvx;

                /* Calculate block y coordinate and round (?).  */
                /* Divide by 16 is for converting half-pixel    */
                /* resolution to block.             */
                mmvy = (ypred + ((kk & 2) << 2)) >> 4;
                nmvy = mmvy;

                /* Perform boundary checking */
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
                else if (nmvy > (height >> 3) - 1)
                {
                    nmvy = (height >> 3) - 1;
                }

                /* Find post processing semaphore location for block */
                /* used for prediction, i.e.,                */
                /* pp_prev1 = &pstprcTypPrv[nmvy*mvwidth][nmvx]      */
                pp_prev1 = pstprcTypPrv + nmvx + nmvy * mvwidth;

                /* Check if x component of MV is a multiple of 16    */
                /* and check if block x coordinate is out of bounds  */
                if (((dx&0xF) != 0) && (mmvx + 1 < mvwidth - 1))
                {   /* dx is not a multiple of 16 and the block */
                    /* x coordinate is within the bounds        */

                    /* pp_prev2 is the block to the right of */
                    /* pp_prev1 block            */
                    pp_prev2 = pp_prev1 + 1;

                    /* Check if y component of MV is a multiple */
                    /* of 16 and check if block y coordinate is */
                    /* out of bounds                */
                    if (((dy&0xF) != 0) && (mmvy + 1 < (height >> 3) - 1))
                    {   /* dy is not a multiple of 16 and */
                        /* the block y coordinate is      */
                        /* within the bounds              */

                        /* pp_prev3 is the block below */
                        /* pp_prev1 block          */
                        pp_prev3 = pp_prev1 + mvwidth;

                        /* all prediction are from different blocks */
                        msk_deblock = 0x3;
                    }
                    else
                    {   /* dy is a multiple of 16 or the block */
                        /* y coordinate is out of bounds       */

                        pp_prev3 = pp_prev1;
                    }

                    /* pp_prev4 is the block to the right of */
                    /* pp_prev3 block.           */
                    pp_prev4 = pp_prev3 + 1;
                }
                else
                {   /* dx is a multiple of 16 or the block x */
                    /* coordinate is out of bounds           */

                    pp_prev2 = pp_prev1;

                    /* Check if y component of MV is a multiple */
                    /* of 16 and check if block y coordinate is */
                    /* out of bounds                */
                    if (((dy&0xF) != 0) && (mmvy + 1 < (height >> 3) - 1))
                    {   /* dy is not a multiple of 16 and */
                        /* the block y coordinate is      */
                        /* within the bounds              */

                        /* pp_prev3 is the block below */
                        /* pp_prev1 block.         */
                        pp_prev3 = pp_prev1 + mvwidth;
                    }
                    else
                    {   /* dy is a multiple of 16 or the block */
                        /* y coordinate is out of bounds       */

                        pp_prev3 = pp_prev1;
                    }

                    pp_prev4 = pp_prev3;
                }

                /* Deringing semaphore propagation */
                if ((*(pp_dec_y)&4) == 0)
                {
                    *(pp_dec_y) |= ((*(pp_prev1) |
                                     *(pp_prev2) | *(pp_prev3) |
                                     *(pp_prev4)) & 0x4);
                }
                /* Deblocking semaphore propagation */
                /*  11/3/00, change the propaga= */
                /* tion for deblocking */
                if (msk_deblock == 0)
                {
                    *(pp_dec_y) = 0;
                }

                pp_dec_y += ll[kk];
            }
        }

        /*----------------------------------------------------------------------------
        ; Return nothing or data or data pointer
        ----------------------------------------------------------------------------*/
        return (msk_deblock);
    }
#ifdef __cplusplus
}
#endif
#endif
