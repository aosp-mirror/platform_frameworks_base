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
    video = pointer to structure of type VideoDecData

 Local Stores/Buffers/Pointers Needed:
    roundtab16 = rounding table

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    None

 Pointers and Buffers Modified:
    video->currVop->yChan contents are the newly calculated luminance
      data
    video->currVop->uChan contents are the newly calculated chrominance
      b data
    video->currVop->vChan contents are the newly calculated chrominance
      r data
    video->pstprcTypCur contents are the updated semaphore propagation
      values

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function performs high level motion compensation on the luminance and
 chrominance data. It sets up all the parameters required by the functions
 that perform luminance and chrominance prediction and it initializes the
 pointer to the post processing semaphores of a given block. It also checks
 the motion compensation mode in order to determine which luminance or
 chrominance prediction functions to call and determines how the post
 processing semaphores are updated.

*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "mp4dec_lib.h"
#include "motion_comp.h"
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
/* 09/29/2000 bring this from mp4def.h */
// const static int roundtab4[] = {0,1,1,1};
// const static int roundtab8[] = {0,0,1,1,1,1,1,2};
/*** 10/30 for TPS */
// const static int roundtab12[] = {0,0,0,1,1,1,1,1,1,1,2,2};
/* 10/30 for TPS ***/
const static int roundtab16[] = {0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2};


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

/** modified 3 August 2005 to do prediction and put the results in
video->mblock->pred_block, no adding with residue */

void  MBMotionComp(
    VideoDecData *video,
    int CBP
)
{

    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    /* Previous Video Object Plane */
    Vop *prev = video->prevVop;

    /* Current Macroblock (MB) in the VOP */
    int mbnum = video->mbnum;

    /* Number of MB per data row */
    int MB_in_width = video->nMBPerRow;
    int ypos, xpos;
    PIXEL *c_comp, *c_prev;
    PIXEL *cu_comp, *cu_prev;
    PIXEL *cv_comp, *cv_prev;
    int height, width, pred_width;
    int imv, mvwidth;
    int32 offset;
    uint8 mode;
    uint8 *pred_block, *pred;

    /* Motion vector (dx,dy) in half-pel resolution */
    int dx, dy;

    MOT px[4], py[4];
    int xpred, ypred;
    int xsum;
    int round1;
#ifdef PV_POSTPROC_ON // 2/14/2001      
    /* Total number of pixels in the VOL */
    int32 size = (int32) video->nTotalMB << 8;
    uint8 *pp_dec_y, *pp_dec_u;
    int ll[4];
    int tmp = 0;
    uint8 msk_deblock = 0;
#endif
    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/
    /* Set rounding type */
    /* change from array to single 09/29/2000 */
    round1 = (int)(1 - video->currVop->roundingType);

    /* width of luminance data in pixels (y axis) */
    width = video->width;

    /* heigth of luminance data in pixels (x axis) */
    height = video->height;

    /* number of blocks per row */
    mvwidth = MB_in_width << 1;

    /* starting y position in current MB; origin of MB */
    ypos = video->mbnum_row << 4 ;
    /* starting x position in current MB; origin of MB */
    xpos = video->mbnum_col << 4 ;

    /* offset to (x,y) position in current luminance MB */
    /* in pixel resolution                              */
    /* ypos*width -> row, +x -> column */
    offset = (int32)ypos * width + xpos;

    /* get mode for current MB */
    mode = video->headerInfo.Mode[mbnum];

    /* block index */
    /* imv = (xpos/8) + ((ypos/8) * mvwidth) */
    imv = (offset >> 6) - (xpos >> 6) + (xpos >> 3);
    if (mode & INTER_1VMASK)
    {
        dx = px[0] = px[1] = px[2] = px[3] = video->motX[imv];
        dy = py[0] = py[1] = py[2] = py[3] = video->motY[imv];
        if ((dx & 3) == 0)
        {
            dx = dx >> 1;
        }
        else
        {
            /* x component of MV is or'ed for rounding (?) */
            dx = (dx >> 1) | 1;
        }

        /* y component of motion vector; divide by 2 for to */
        /* convert to full-pel resolution.                  */
        if ((dy & 3) == 0)
        {
            dy = dy >> 1;
        }
        else
        {
            /* y component of MV is or'ed for rounding (?) */
            dy = (dy >> 1) | 1;
        }
    }
    else
    {
        px[0] = video->motX[imv];
        px[1] = video->motX[imv+1];
        px[2] = video->motX[imv+mvwidth];
        px[3] = video->motX[imv+mvwidth+1];
        xsum = px[0] + px[1] + px[2] + px[3];
        dx = PV_SIGN(xsum) * (roundtab16[(PV_ABS(xsum)) & 0xF] +
                              (((PV_ABS(xsum)) >> 4) << 1));
        py[0] = video->motY[imv];
        py[1] = video->motY[imv+1];
        py[2] = video->motY[imv+mvwidth];
        py[3] = video->motY[imv+mvwidth+1];
        xsum = py[0] + py[1] + py[2] + py[3];
        dy = PV_SIGN(xsum) * (roundtab16[(PV_ABS(xsum)) & 0xF] +
                              (((PV_ABS(xsum)) >> 4) << 1));
    }

    /* Pointer to previous luminance frame */
    c_prev  = prev->yChan;

    pred_block = video->mblock->pred_block;

    /* some blocks have no residue or INTER4V */
    /*if (mode == MODE_INTER4V)   05/08/15 */
    /* Motion Compensation for an 8x8 block within a MB */
    /* (4 MV per MB) */



    /* Call function that performs luminance prediction */
    /*      luminance_pred_mode_inter4v(xpos, ypos, px, py, c_prev,
                    video->mblock->pred_block, width, height,
                    round1, mvwidth, &xsum, &ysum);*/
    c_comp = video->currVop->yChan + offset;


    xpred = (int)((xpos << 1) + px[0]);
    ypred = (int)((ypos << 1) + py[0]);

    if ((CBP >> 5)&1)
    {
        pred = pred_block;
        pred_width = 16;
    }
    else
    {
        pred = c_comp;
        pred_width = width;
    }

    /* check whether the MV points outside the frame */
    if (xpred >= 0 && xpred <= ((width << 1) - (2*B_SIZE)) &&
            ypred >= 0 && ypred <= ((height << 1) - (2*B_SIZE)))
    {   /*****************************/
        /* (x,y) is inside the frame */
        /*****************************/
        ;
        GetPredAdvBTable[ypred&1][xpred&1](c_prev + (xpred >> 1) + ((ypred >> 1)*width),
                                           pred, width, (pred_width << 1) | round1);
    }
    else
    {   /******************************/
        /* (x,y) is outside the frame */
        /******************************/
        GetPredOutside(xpred, ypred, c_prev,
                       pred, width, height, round1, pred_width);
    }


    /* Compute prediction values over current luminance MB */
    /* (blocks 1); add motion vector prior to input;       */
    /* add 8 to x_pos to advance to next block         */
    xpred = (int)(((xpos + B_SIZE) << 1) + px[1]);
    ypred = (int)((ypos << 1) + py[1]);

    if ((CBP >> 4)&1)
    {
        pred = pred_block + 8;
        pred_width = 16;
    }
    else
    {
        pred = c_comp + 8;
        pred_width = width;
    }

    /* check whether the MV points outside the frame */
    if (xpred >= 0 && xpred <= ((width << 1) - (2*B_SIZE)) &&
            ypred >= 0 && ypred <= ((height << 1) - (2*B_SIZE)))
    {   /*****************************/
        /* (x,y) is inside the frame */
        /*****************************/
        GetPredAdvBTable[ypred&1][xpred&1](c_prev + (xpred >> 1) + ((ypred >> 1)*width),
                                           pred, width, (pred_width << 1) | round1);
    }
    else
    {   /******************************/
        /* (x,y) is outside the frame */
        /******************************/
        GetPredOutside(xpred, ypred, c_prev,
                       pred, width, height, round1, pred_width);
    }



    /* Compute prediction values over current luminance MB */
    /* (blocks 2); add motion vector prior to input        */
    /* add 8 to y_pos to advance to block on next row      */
    xpred = (int)((xpos << 1) + px[2]);
    ypred = (int)(((ypos + B_SIZE) << 1) + py[2]);

    if ((CBP >> 3)&1)
    {
        pred = pred_block + 128;
        pred_width = 16;
    }
    else
    {
        pred = c_comp + (width << 3);
        pred_width = width;
    }

    /* check whether the MV points outside the frame */
    if (xpred >= 0 && xpred <= ((width << 1) - (2*B_SIZE)) &&
            ypred >= 0 && ypred <= ((height << 1) - (2*B_SIZE)))
    {   /*****************************/
        /* (x,y) is inside the frame */
        /*****************************/
        GetPredAdvBTable[ypred&1][xpred&1](c_prev + (xpred >> 1) + ((ypred >> 1)*width),
                                           pred, width, (pred_width << 1) | round1);
    }
    else
    {   /******************************/
        /* (x,y) is outside the frame */
        /******************************/
        GetPredOutside(xpred, ypred, c_prev,
                       pred, width, height, round1, pred_width);
    }



    /* Compute prediction values over current luminance MB */
    /* (blocks 3); add motion vector prior to input;       */
    /* add 8 to x_pos and y_pos to advance to next block   */
    /* on next row                         */
    xpred = (int)(((xpos + B_SIZE) << 1) + px[3]);
    ypred = (int)(((ypos + B_SIZE) << 1) + py[3]);

    if ((CBP >> 2)&1)
    {
        pred = pred_block + 136;
        pred_width = 16;
    }
    else
    {
        pred = c_comp + (width << 3) + 8;
        pred_width = width;
    }

    /* check whether the MV points outside the frame */
    if (xpred >= 0 && xpred <= ((width << 1) - (2*B_SIZE)) &&
            ypred >= 0 && ypred <= ((height << 1) - (2*B_SIZE)))
    {   /*****************************/
        /* (x,y) is inside the frame */
        /*****************************/
        GetPredAdvBTable[ypred&1][xpred&1](c_prev + (xpred >> 1) + ((ypred >> 1)*width),
                                           pred, width, (pred_width << 1) | round1);
    }
    else
    {   /******************************/
        /* (x,y) is outside the frame */
        /******************************/
        GetPredOutside(xpred, ypred, c_prev,
                       pred, width, height, round1, pred_width);
    }
    /* Call function to set de-blocking and de-ringing */
    /*   semaphores for luminance                      */

#ifdef PV_POSTPROC_ON
    if (video->postFilterType != PV_NO_POST_PROC)
    {
        if (mode&INTER_1VMASK)
        {
            pp_dec_y = video->pstprcTypCur + imv;
            ll[0] = 1;
            ll[1] = mvwidth - 1;
            ll[2] = 1;
            ll[3] = -mvwidth - 1;
            msk_deblock = pp_semaphore_luma(xpred, ypred, pp_dec_y,
                                            video->pstprcTypPrv, ll, &tmp, px[0], py[0], mvwidth,
                                            width, height);

            pp_dec_u = video->pstprcTypCur + (size >> 6) +
                       ((imv + (xpos >> 3)) >> 2);

            pp_semaphore_chroma_inter(xpred, ypred, pp_dec_u,
                                      video->pstprcTypPrv, dx, dy, mvwidth, height, size,
                                      tmp, msk_deblock);
        }
        else
        {
            /* Post-processing mode (MBM_INTER8) */
            /* deblocking and deringing) */
            pp_dec_y = video->pstprcTypCur + imv;
            *pp_dec_y = 4;
            *(pp_dec_y + 1) = 4;
            *(pp_dec_y + mvwidth) = 4;
            *(pp_dec_y + mvwidth + 1) = 4;
            pp_dec_u = video->pstprcTypCur + (size >> 6) +
                       ((imv + (xpos >> 3)) >> 2);
            *pp_dec_u = 4;
            pp_dec_u[size>>8] = 4;
        }
    }
#endif


    /* xpred and ypred calculation for Chrominance is */
    /* in full-pel resolution.                        */

    /* Chrominance */
    /* width of chrominance data in pixels (y axis) */
    width >>= 1;

    /* heigth of chrominance data in pixels (x axis) */
    height >>= 1;

    /* Pointer to previous chrominance b frame */
    cu_prev = prev->uChan;

    /* Pointer to previous chrominance r frame */
    cv_prev = prev->vChan;

    /* x position in prediction data offset by motion vector */
    /* xpred calculation for Chrominance is in full-pel      */
    /* resolution.                                           */
    xpred = xpos + dx;

    /* y position in prediction data offset by motion vector */
    /* ypred calculation for Chrominance is in full-pel      */
    /* resolution.                                           */
    ypred = ypos + dy;

    cu_comp = video->currVop->uChan + (offset >> 2) + (xpos >> 2);
    cv_comp = video->currVop->vChan + (offset >> 2) + (xpos >> 2);

    /* Call function that performs chrominance prediction */
    /*      chrominance_pred(xpred, ypred, cu_prev, cv_prev,
            pred_block, width_uv, height_uv,
            round1);*/
    if (xpred >= 0 && xpred <= ((width << 1) - (2*B_SIZE)) && ypred >= 0 &&
            ypred <= ((height << 1) - (2*B_SIZE)))
    {
        /*****************************/
        /* (x,y) is inside the frame */
        /*****************************/
        if ((CBP >> 1)&1)
        {
            pred = pred_block + 256;
            pred_width = 16;
        }
        else
        {
            pred = cu_comp;
            pred_width = width;
        }

        /* Compute prediction for Chrominance b (block[4]) */
        GetPredAdvBTable[ypred&1][xpred&1](cu_prev + (xpred >> 1) + ((ypred >> 1)*width),
                                           pred, width, (pred_width << 1) | round1);

        if (CBP&1)
        {
            pred = pred_block + 264;
            pred_width = 16;
        }
        else
        {
            pred = cv_comp;
            pred_width = width;
        }
        /* Compute prediction for Chrominance r (block[5]) */
        GetPredAdvBTable[ypred&1][xpred&1](cv_prev + (xpred >> 1) + ((ypred >> 1)*width),
                                           pred, width, (pred_width << 1) | round1);

        return ;
    }
    else
    {
        /******************************/
        /* (x,y) is outside the frame */
        /******************************/
        if ((CBP >> 1)&1)
        {
            pred = pred_block + 256;
            pred_width = 16;
        }
        else
        {
            pred = cu_comp;
            pred_width = width;
        }

        /* Compute prediction for Chrominance b (block[4]) */
        GetPredOutside(xpred, ypred,    cu_prev,
                       pred, width, height, round1, pred_width);

        if (CBP&1)
        {
            pred = pred_block + 264;
            pred_width = 16;
        }
        else
        {
            pred = cv_comp;
            pred_width = width;
        }

        /* Compute prediction for Chrominance r (block[5]) */
        GetPredOutside(xpred, ypred,    cv_prev,
                       pred, width, height, round1, pred_width);

        return ;
    }

}

/*** special function for skipped macroblock,  Aug 15, 2005 */
void  SkippedMBMotionComp(
    VideoDecData *video
)
{
    Vop *prev = video->prevVop;
    Vop *comp;
    int ypos, xpos;
    PIXEL *c_comp, *c_prev;
    PIXEL *cu_comp, *cu_prev;
    PIXEL *cv_comp, *cv_prev;
    int width, width_uv;
    int32 offset;
#ifdef PV_POSTPROC_ON // 2/14/2001      
    int imv;
    int32 size = (int32) video->nTotalMB << 8;
    uint8 *pp_dec_y, *pp_dec_u;
    uint8 *pp_prev1;
    int mvwidth = video->nMBPerRow << 1;
#endif

    width = video->width;
    width_uv  = width >> 1;
    ypos = video->mbnum_row << 4 ;
    xpos = video->mbnum_col << 4 ;
    offset = (int32)ypos * width + xpos;


    /* zero motion compensation for previous frame */
    /*mby*width + mbx;*/
    c_prev  = prev->yChan + offset;
    /*by*width_uv + bx;*/
    cu_prev = prev->uChan + (offset >> 2) + (xpos >> 2);
    /*by*width_uv + bx;*/
    cv_prev = prev->vChan + (offset >> 2) + (xpos >> 2);

    comp = video->currVop;

    c_comp  = comp->yChan + offset;
    cu_comp = comp->uChan + (offset >> 2) + (xpos >> 2);
    cv_comp = comp->vChan + (offset >> 2) + (xpos >> 2);


    /* Copy previous reconstructed frame into the current frame */
    PutSKIPPED_MB(c_comp,  c_prev, width);
    PutSKIPPED_B(cu_comp, cu_prev, width_uv);
    PutSKIPPED_B(cv_comp, cv_prev, width_uv);

    /*  10/24/2000 post_processing semaphore generation */
#ifdef PV_POSTPROC_ON // 2/14/2001
    if (video->postFilterType != PV_NO_POST_PROC)
    {
        imv = (offset >> 6) - (xpos >> 6) + (xpos >> 3);
        /* Post-processing mode (copy previous MB) */
        pp_prev1 = video->pstprcTypPrv + imv;
        pp_dec_y = video->pstprcTypCur + imv;
        *pp_dec_y = *pp_prev1;
        *(pp_dec_y + 1) = *(pp_prev1 + 1);
        *(pp_dec_y + mvwidth) = *(pp_prev1 + mvwidth);
        *(pp_dec_y + mvwidth + 1) = *(pp_prev1 + mvwidth + 1);

        /* chrominance */
        /*4*MB_in_width*MB_in_height*/
        pp_prev1 = video->pstprcTypPrv + (size >> 6) +
                   ((imv + (xpos >> 3)) >> 2);
        pp_dec_u = video->pstprcTypCur + (size >> 6) +
                   ((imv + (xpos >> 3)) >> 2);
        *pp_dec_u = *pp_prev1;
        pp_dec_u[size>>8] = pp_prev1[size>>8];
    }
#endif
    /*----------------------------------------------------------------------------
    ; Return nothing or data or data pointer
    ----------------------------------------------------------------------------*/

    return;
}
