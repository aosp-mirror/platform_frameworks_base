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
#include "mp4def.h"
#include "mp4enc_lib.h"
#include "mp4lib_int.h"
#include "m4venc_oscl.h"

/* 3/29/01 fast half-pel search based on neighboring guess */
/* value ranging from 0 to 4, high complexity (more accurate) to
   low complexity (less accurate) */
#define HP_DISTANCE_TH      2  /* half-pel distance threshold */

#define PREF_16_VEC 129     /* 1MV bias versus 4MVs*/

#ifdef __cplusplus
extern "C"
{
#endif
    void GenerateSearchRegion(UChar *searchPadding, UChar *ref, Int width, Int height,
    Int ilow, Int ihigh, Int jlow, Int jhigh);

    void InterpDiag(UChar *prev, Int lx, UChar *pred_block);
    void InterpHorz(UChar *prev, Int lx, UChar *pred_block);
    void InterpVert(UChar *prev, Int lx, UChar *pred_block);
#ifdef __cplusplus
}
#endif


const static Int distance_tab[9][9] =   /* [hp_guess][k] */
{
    {0, 1, 1, 1, 1, 1, 1, 1, 1},
    {1, 0, 1, 2, 3, 4, 3, 2, 1},
    {1, 0, 0, 0, 1, 2, 3, 2, 1},
    {1, 2, 1, 0, 1, 2, 3, 4, 3},
    {1, 2, 1, 0, 0, 0, 1, 2, 3},
    {1, 4, 3, 2, 1, 0, 1, 2, 3},
    {1, 2, 3, 2, 1, 0, 0, 0, 1},
    {1, 2, 3, 4, 3, 2, 1, 0, 1},
    {1, 0, 1, 2, 3, 2, 1, 0, 0}
};


/*=====================================================================
    Function:   FindHalfPelMB
    Date:       10/7/2000
    Purpose:    Find half pel resolution MV surrounding the full-pel MV
=====================================================================*/

void FindHalfPelMB(VideoEncData *video, UChar *cur, MOT *mot, UChar *ncand,
                   Int xpos, Int ypos, Int *xhmin, Int *yhmin, Int hp_guess)
{
//  hp_mem = ULong *vertArray; /* 20x17 */
//           ULong *horzArray; /* 20x16 */
//           ULong *diagArray; /* 20x17 */
    Int dmin, d;

    Int xh, yh;
    Int k, kmin = 0;
    Int imin, jmin, ilow, jlow;
    Int h263_mode = video->encParams->H263_Enabled; /*  3/29/01 */
    Int in_range[9] = {0, 1, 1, 1, 1, 1, 1, 1, 1}; /*  3/29/01 */
    Int range = video->encParams->SearchRange;
    Int lx = video->currVop->pitch;
    Int width = video->currVop->width; /*  padding */
    Int height = video->vol[video->currLayer]->height;
    Int(**SAD_MB_HalfPel)(UChar*, UChar*, Int, void*) =
        video->functionPointer->SAD_MB_HalfPel;
    void *extra_info = video->sad_extra_info;

    Int next_hp_pos[9][2] = {{0, 0}, {2, 0}, {1, 1}, {0, 2}, { -1, 1}, { -2, 0}, { -1, -1}, {0, -2}, {0, -1}};
    Int next_ncand[9] = {0, 1 , lx, lx, 0, -1, -1, -lx, -lx};

    cur = video->currYMB;

    /**************** check range ***************************/
    /*  3/29/01 */
    imin = xpos + (mot[0].x >> 1);
    jmin = ypos + (mot[0].y >> 1);
    ilow = xpos - range;
    jlow = ypos - range;

    if (!h263_mode)
    {
        if (imin <= -15 || imin == ilow)
            in_range[1] = in_range[7] = in_range[8] = 0;
        else if (imin >= width - 1)
            in_range[3] = in_range[4] = in_range[5] = 0;
        if (jmin <= -15 || jmin == jlow)
            in_range[1] = in_range[2] = in_range[3] = 0;
        else if (jmin >= height - 1)
            in_range[5] = in_range[6] = in_range[7] = 0;
    }
    else
    {
        if (imin <= 0 || imin == ilow)
            in_range[1] = in_range[7] = in_range[8] = 0;
        else if (imin >= width - 16)
            in_range[3] = in_range[4] = in_range[5] = 0;
        if (jmin <= 0 || jmin == jlow)
            in_range[1] = in_range[2] = in_range[3] = 0;
        else if (jmin >= height - 16)
            in_range[5] = in_range[6] = in_range[7] = 0;
    }

    xhmin[0] = 0;
    yhmin[0] = 0;
    dmin = mot[0].sad;

    xh = 0;
    yh = -1;
    ncand -= lx; /* initial position */

    for (k = 2; k <= 8; k += 2)
    {
        if (distance_tab[hp_guess][k] < HP_DISTANCE_TH)
        {
            if (in_range[k])
            {
                d = (*(SAD_MB_HalfPel[((yh&1)<<1)+(xh&1)]))(ncand, cur, (dmin << 16) | lx, extra_info);

                if (d < dmin)
                {
                    dmin = d;
                    xhmin[0] = xh;
                    yhmin[0] = yh;
                    kmin = k;
                }
                else if (d == dmin &&
                         PV_ABS(mot[0].x + xh) + PV_ABS(mot[0].y + yh) < PV_ABS(mot[0].x + xhmin[0]) + PV_ABS(mot[0].y + yhmin[0]))
                {
                    xhmin[0] = xh;
                    yhmin[0] = yh;
                    kmin = k;
                }

            }
        }
        xh += next_hp_pos[k][0];
        yh += next_hp_pos[k][1];
        ncand += next_ncand[k];

        if (k == 8)
        {
            if (xhmin[0] != 0 || yhmin[0] != 0)
            {
                k = -1;
                hp_guess = kmin;
            }
        }
    }

    mot[0].sad = dmin;
    mot[0].x += xhmin[0];
    mot[0].y += yhmin[0];

    return ;
}

#ifndef NO_INTER4V
/*=====================================================================
    Function:   FindHalfPelBlk
    Date:       10/7/2000
    Purpose:    Find half pel resolution MV surrounding the full-pel MV
                And decide between 1MV or 4MV mode
=====================================================================*/
///// THIS FUNCTION IS NOT WORKING!!! NEED TO BE RIVISITED

Int FindHalfPelBlk(VideoEncData *video, UChar *cur, MOT *mot, Int sad16, UChar *ncand8[],
                   UChar *mode, Int xpos, Int ypos, Int *xhmin, Int *yhmin, UChar *hp_mem)
{
    Int k, comp;
    Int xh, yh;//, xhmin, yhmin;
    Int imin, jmin, ilow, jlow;
    Int height;
    UChar *cand, *cur8;
    UChar *hmem;//[17*17]; /* half-pel memory */
    Int d, dmin, sad8;
    Int lx = video->currVop->pitch;
    Int width = video->currVop->width; /* , padding */
    Int(*SAD_Blk_HalfPel)(UChar*, UChar*, Int, Int, Int, Int, Int, void*) = video->functionPointer->SAD_Blk_HalfPel;
    void *extra_info = video->sad_extra_info;
    Int in_range[8]; /*  3/29/01 */
    Int range = video->encParams->SearchRange;
    Int swidth;
    Int next_hp_pos[8][2] = {{1, 0}, {1, 0}, {0, 1}, {0, 1}, { -1, 0}, { -1, 0}, {0, -1}, {0, -1}};

    height = video->vol[video->currLayer]->height;

    hmem = hp_mem;
    sad8 = 0;
    for (comp = 0; comp < 4; comp++)
    {
#ifdef _SAD_STAT
        num_HP_Blk++;
#endif
        /**************** check range ***************************/
        /*  3/29/01 */
        M4VENC_MEMSET(in_range, 1, sizeof(Int) << 3);
        imin = xpos + ((comp & 1) << 3) + (mot[comp+1].x >> 1);
        jmin = ypos + ((comp & 2) << 2) + (mot[comp+1].y >> 1);
        ilow = xpos + ((comp & 1) << 3) - range;
        jlow = ypos + ((comp & 2) << 2) - range;

        if (imin <= -15 || imin == ilow)
            in_range[0] = in_range[6] = in_range[7] = 0;
        else if (imin >= width - 1)
            in_range[2] = in_range[3] = in_range[4] = 0;

        if (jmin <= -15 || jmin == jlow)
            in_range[0] = in_range[1] = in_range[2] = 0;
        else if (jmin >= height - 1)
            in_range[4] = in_range[5] = in_range[6] = 0;

        /**************** half-pel search ***********************/
        cur8 = cur + ((comp & 1) << 3) + ((comp & 2) << 2) * width ;

        /* generate half-pel search region */
        {
            cand = ncand8[comp+1];
            swidth = lx;
        }

        xhmin[comp+1] = 0;
        yhmin[comp+1] = 0;
        dmin = mot[comp+1].sad;

        xh = -1;
        yh = -1;
        for (k = 0; k < 8; k++)
        {
            if (in_range[k])
            {
                d = (*SAD_Blk_HalfPel)(cand, cur8, dmin, lx, swidth, xh, yh, extra_info);

                if (d < dmin)
                {
                    dmin = d;
                    xhmin[comp+1] = xh;
                    yhmin[comp+1] = yh;
                }
            }
            xh += next_hp_pos[k][0];
            yh += next_hp_pos[k][1];
        }
        /********************************************/
        mot[comp+1].x += xhmin[comp+1];
        mot[comp+1].y += yhmin[comp+1];
        mot[comp+1].sad = dmin;
        sad8 += dmin;

        if (sad8 >= sad16 - PREF_16_VEC)
        {
            *mode = MODE_INTER;
            for (k = 1; k <= 4; k++)
            {
                mot[k].sad = (mot[0].sad + 2) >> 2;
                mot[k].x = mot[0].x;
                mot[k].y = mot[0].y;
            }
            return sad8;
        }

        hmem += (10 * 10);
    }

    *mode = MODE_INTER4V;

    return sad8;
}
#endif /* NO_INTER4V */

