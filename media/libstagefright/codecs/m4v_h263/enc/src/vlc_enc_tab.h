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
/******************************************************************************
 *
 * This software module was originally developed by
 *
 * Robert Danielsen (Telenor / ACTS-MoMuSys).
 *
 * and edited by
 *
 * Minhua Zhou (HHI / ACTS-MoMuSys).
 * Luis Ducla-Soares (IST / ACTS-MoMuSys).
 *
 * in the course of development of the MPEG-4 Video (ISO/IEC 14496-2) standard.
 * This software module is an implementation of a part of one or more MPEG-4
 * Video (ISO/IEC 14496-2) tools as specified by the MPEG-4 Video (ISO/IEC
 * 14496-2) standard.
 *
 * ISO/IEC gives users of the MPEG-4 Video (ISO/IEC 14496-2) standard free
 * license to this software module or modifications thereof for use in hardware
 * or software products claiming conformance to the MPEG-4 Video (ISO/IEC
 * 14496-2) standard.
 *
 * Those intending to use this software module in hardware or software products
 * are advised that its use may infringe existing patents. The original
 * developer of this software module and his/her company, the subsequent
 * editors and their companies, and ISO/IEC have no liability for use of this
 * software module or modifications thereof in an implementation. Copyright is
 * not released for non MPEG-4 Video (ISO/IEC 14496-2) standard conforming
 * products.
 *
 * ACTS-MoMuSys partners retain full right to use the code for his/her own
 * purpose, assign or donate the code to a third party and to inhibit third
 * parties from using the code for non MPEG-4 Video (ISO/IEC 14496-2) standard
 * conforming products. This copyright notice must be included in all copies or
 * derivative works.
 *
 * Copyright (c) 1997
 *
 *****************************************************************************/


/***********************************************************HeaderBegin*******
 *
 * File:    vlc.h
 *
 * Author:  Robert Danielsen
 * Created: 07.06.96
 *
 * Description: vlc tables for encoder
 *
 * Notes:   Idea taken from MPEG-2 software simulation group
 *
 * Modified:
 *  28.10.96 Robert Danielsen: Added tables for Intra luminance
 *          coefficients
 *      01.05.97 Luis Ducla-Soares: added VM7.0 Reversible VLC tables (RVLC).
 *      13.05.97 Minhua Zhou: added cbpy_tab3,cbpy_tab2
 *
 ***********************************************************HeaderEnd*********/

/************************    INCLUDE FILES    ********************************/

#ifndef _VLC_ENC_TAB_H_
#define _VLC_ENC_TAB_H_


#include "mp4def.h"
/* type definitions for variable length code table entries */



static const Int intra_max_level[2][64] =
{
    {27, 10,  5,  4,  3,  3,  3,  3,
        2,  2,  1,  1,  1,  1,  1,  0,
        0,  0,  0,  0,  0,  0,  0,  0,
        0,  0,  0,  0,  0,  0,  0,  0,
        0,  0,  0,  0,  0,  0,  0,  0,
        0,  0,  0,  0,  0,  0,  0,  0,
        0,  0,  0,  0,  0,  0,  0,  0,
        0,  0,  0,  0,  0,  0,  0,  0,
    },

    {8,  3,  2,  2,  2,  2,  2,  1,
     1,  1,  1,  1,  1,  1,  1,  1,
     1,  1,  1,  1,  1,  0,  0,  0,
     0,  0,  0,  0,  0,  0,  0,  0,
     0,  0,  0,  0,  0,  0,  0,  0,
     0,  0,  0,  0,  0,  0,  0,  0,
     0,  0,  0,  0,  0,  0,  0,  0,
     0,  0,  0,  0,  0,  0,  0,  0
    }
};


static const Int inter_max_level[2][64] =
{
    {12,  6,  4,  3,  3,  3,  3,  2,
        2,  2,  2,  1,  1,  1,  1,  1,
        1,  1,  1,  1,  1,  1,  1,  1,
        1,  1,  1,  0,  0,  0,  0,  0,
        0,  0,  0,  0,  0,  0,  0,  0,
        0,  0,  0,  0,  0,  0,  0,  0,
        0,  0,  0,  0,  0,  0,  0,  0,
        0,  0,  0,  0,  0,  0,  0,  0},

    {3,  2,  1,  1,  1,  1,  1,  1,
     1,  1,  1,  1,  1,  1,  1,  1,
     1,  1,  1,  1,  1,  1,  1,  1,
     1,  1,  1,  1,  1,  1,  1,  1,
     1,  1,  1,  1,  1,  1,  1,  1,
     1,  0,  0,  0,  0,  0,  0,  0,
     0,  0,  0,  0,  0,  0,  0,  0,
     0,  0,  0,  0,  0,  0,  0,  0}
};


static const Int intra_max_run0[28] = { 999, 14,  9,  7,  3,  2,  1,
                                        1,  1,  1,  1,  0,  0,  0,
                                        0,  0,  0,  0,  0,  0,  0,
                                        0,  0,  0,  0,  0,  0,  0
                                      };


static const Int intra_max_run1[9] = { 999, 20,  6,
                                       1,  0,  0,
                                       0,  0,  0
                                     };

static const Int inter_max_run0[13] = { 999,
                                        26, 10,  6,  2,  1,  1,
                                        0,  0,  0,  0,  0,  0
                                      };


static const Int inter_max_run1[4] = { 999, 40,  1,  0 };



/* DC prediction sizes */

static const VLCtable DCtab_lum[13] =
{
    {3, 3}, {3, 2}, {2, 2}, {2, 3}, {1, 3}, {1, 4}, {1, 5}, {1, 6}, {1, 7},
    {1, 8}, {1, 9}, {1, 10}, {1, 11}
};

static const VLCtable DCtab_chrom[13] =
{
    {3, 2}, {2, 2}, {1, 2}, {1, 3}, {1, 4}, {1, 5}, {1, 6}, {1, 7}, {1, 8},
    {1, 9}, {1, 10}, {1, 11}, {1, 12}
};

/* Motion vectors */

static const VLCtable mvtab[33] =
{
    {1, 1}, {1, 2}, {1, 3}, {1, 4}, {3, 6}, {5, 7}, {4, 7}, {3, 7},
    {11, 9}, {10, 9}, {9, 9}, {17, 10}, {16, 10}, {15, 10}, {14, 10}, {13, 10},
    {12, 10}, {11, 10}, {10, 10}, {9, 10}, {8, 10}, {7, 10}, {6, 10}, {5, 10},
    {4, 10}, {7, 11}, {6, 11}, {5, 11}, {4, 11}, {3, 11}, {2, 11}, {3, 12},
    {2, 12}
};


/* MCBPC Indexing by cbpc in first two bits, mode in last two.
 CBPC as in table 4/H.263, MB type (mode): 3 = 01, 4 = 10.
 Example: cbpc = 01 and mode = 4 gives index = 0110 = 6. */

static const VLCtable mcbpc_intra_tab[15] =
{
    {0x01, 9}, {0x01, 1}, {0x01, 4}, {0x00, 0},
    {0x00, 0}, {0x01, 3}, {0x01, 6}, {0x00, 0},
    {0x00, 0}, {0x02, 3}, {0x02, 6}, {0x00, 0},
    {0x00, 0}, {0x03, 3}, {0x03, 6}
};


/* MCBPC inter.
   Addressing: 5 bit ccmmm (cc = CBPC, mmm = mode (1-4 binary)) */

static const VLCtable mcbpc_inter_tab[29] =
{
    {1, 1}, {3, 3}, {2, 3}, {3, 5}, {4, 6}, {1, 9}, {0, 0}, {0, 0},
    {3, 4}, {7, 7}, {5, 7}, {4, 8}, {4, 9}, {0, 0}, {0, 0}, {0, 0},
    {2, 4}, {6, 7}, {4, 7}, {3, 8}, {3, 9}, {0, 0}, {0, 0}, {0, 0},
    {5, 6}, {5, 9}, {5, 8}, {3, 7}, {2, 9}
};



/* CBPY. Straightforward indexing */

static const VLCtable cbpy_tab[16] =
{
    {3, 4}, {5, 5}, {4, 5}, {9, 4}, {3, 5}, {7, 4}, {2, 6}, {11, 4},
    {2, 5}, {3, 6}, {5, 4}, {10, 4}, {4, 4}, {8, 4}, {6, 4}, {3, 2}
};

static const VLCtable cbpy_tab3[8] =
{
    {3, 3}, {1, 6}, {1, 5}, {2, 3}, {2, 5}, {3, 5}, {1, 3}, {1, 1}
};
static const VLCtable cbpy_tab2[4] =
{
    {1, 4}, {1, 3}, {1, 2}, {1, 1}
};

/* DCT coefficients. Four tables, two for last = 0, two for last = 1.
   the sign bit must be added afterwards. */

/* first part of coeffs for last = 0. Indexed by [run][level-1] */

static const VLCtable coeff_tab0[2][12] =
{
    /* run = 0 */
    {
        {0x02, 2}, {0x0f, 4}, {0x15, 6}, {0x17, 7},
        {0x1f, 8}, {0x25, 9}, {0x24, 9}, {0x21, 10},
        {0x20, 10}, {0x07, 11}, {0x06, 11}, {0x20, 11}
    },
    /* run = 1 */
    {
        {0x06, 3}, {0x14, 6}, {0x1e, 8}, {0x0f, 10},
        {0x21, 11}, {0x50, 12}, {0x00, 0}, {0x00, 0},
        {0x00, 0}, {0x00, 0}, {0x00, 0}, {0x00, 0}
    }
};

/* rest of coeffs for last = 0. indexing by [run-2][level-1] */

static const VLCtable coeff_tab1[25][4] =
{
    /* run = 2 */
    {
        {0x0e, 4}, {0x1d, 8}, {0x0e, 10}, {0x51, 12}
    },
    /* run = 3 */
    {
        {0x0d, 5}, {0x23, 9}, {0x0d, 10}, {0x00, 0}
    },
    /* run = 4-26 */
    {
        {0x0c, 5}, {0x22, 9}, {0x52, 12}, {0x00, 0}
    },
    {
        {0x0b, 5}, {0x0c, 10}, {0x53, 12}, {0x00, 0}
    },
    {
        {0x13, 6}, {0x0b, 10}, {0x54, 12}, {0x00, 0}
    },
    {
        {0x12, 6}, {0x0a, 10}, {0x00, 0}, {0x00, 0}
    },
    {
        {0x11, 6}, {0x09, 10}, {0x00, 0}, {0x00, 0}
    },
    {
        {0x10, 6}, {0x08, 10}, {0x00, 0}, {0x00, 0}
    },
    {
        {0x16, 7}, {0x55, 12}, {0x00, 0}, {0x00, 0}
    },
    {
        {0x15, 7}, {0x00, 0}, {0x00, 0}, {0x00, 0}
    },
    {
        {0x14, 7}, {0x00, 0}, {0x00, 0}, {0x00, 0}
    },
    {
        {0x1c, 8}, {0x00, 0}, {0x00, 0}, {0x00, 0}
    },
    {
        {0x1b, 8}, {0x00, 0}, {0x00, 0}, {0x00, 0}
    },
    {
        {0x21, 9}, {0x00, 0}, {0x00, 0}, {0x00, 0}
    },
    {
        {0x20, 9}, {0x00, 0}, {0x00, 0}, {0x00, 0}
    },
    {
        {0x1f, 9}, {0x00, 0}, {0x00, 0}, {0x00, 0}
    },
    {
        {0x1e, 9}, {0x00, 0}, {0x00, 0}, {0x00, 0}
    },
    {
        {0x1d, 9}, {0x00, 0}, {0x00, 0}, {0x00, 0}
    },
    {
        {0x1c, 9}, {0x00, 0}, {0x00, 0}, {0x00, 0}
    },
    {
        {0x1b, 9}, {0x00, 0}, {0x00, 0}, {0x00, 0}
    },
    {
        {0x1a, 9}, {0x00, 0}, {0x00, 0}, {0x00, 0}
    },
    {
        {0x22, 11}, {0x00, 0}, {0x00, 0}, {0x00, 0}
    },
    {
        {0x23, 11}, {0x00, 0}, {0x00, 0}, {0x00, 0}
    },
    {
        {0x56, 12}, {0x00, 0}, {0x00, 0}, {0x00, 0}
    },
    {
        {0x57, 12}, {0x00, 0}, {0x00, 0}, {0x00, 0}
    }
};

/* first coeffs of last = 1. indexing by [run][level-1] */

static const VLCtable coeff_tab2[2][3] =
{
    /* run = 0 */
    {
        {0x07, 4}, {0x19, 9}, {0x05, 11}
    },
    /* run = 1 */
    {
        {0x0f, 6}, {0x04, 11}, {0x00, 0}
    }
};

/* rest of coeffs for last = 1. indexing by [run-2] */

static const VLCtable coeff_tab3[40] =
{
    {0x0e, 6}, {0x0d, 6}, {0x0c, 6},
    {0x13, 7}, {0x12, 7}, {0x11, 7}, {0x10, 7},
    {0x1a, 8}, {0x19, 8}, {0x18, 8}, {0x17, 8},
    {0x16, 8}, {0x15, 8}, {0x14, 8}, {0x13, 8},
    {0x18, 9}, {0x17, 9}, {0x16, 9}, {0x15, 9},
    {0x14, 9}, {0x13, 9}, {0x12, 9}, {0x11, 9},
    {0x07, 10}, {0x06, 10}, {0x05, 10}, {0x04, 10},
    {0x24, 11}, {0x25, 11}, {0x26, 11}, {0x27, 11},
    {0x58, 12}, {0x59, 12}, {0x5a, 12}, {0x5b, 12},
    {0x5c, 12}, {0x5d, 12}, {0x5e, 12}, {0x5f, 12},
    {0x00, 0}
};

/* New tables for Intra luminance coefficients. Same codewords,
   different meaning */

/* Coeffs for last = 0, run = 0. Indexed by [level-1] */

static const VLCtable coeff_tab4[27] =
{
    /* run = 0 */
    {0x02, 2}, {0x06, 3}, {0x0f, 4}, {0x0d, 5},
    {0x0c, 5}, {0x15, 6}, {0x13, 6}, {0x12, 6},
    {0x17, 7}, {0x1f, 8}, {0x1e, 8}, {0x1d, 8},
    {0x25, 9}, {0x24, 9}, {0x23, 9}, {0x21, 9},
    {0x21, 10}, {0x20, 10}, {0x0f, 10}, {0x0e, 10},
    {0x07, 11}, {0x06, 11}, {0x20, 11}, {0x21, 11},
    {0x50, 12}, {0x51, 12}, {0x52, 12}
};

/* Coeffs for last = 0, run = 1. Indexed by [level-1] */

static const VLCtable coeff_tab5[10] =
{
    {0x0e, 4}, {0x14, 6}, {0x16, 7}, {0x1c, 8},
    {0x20, 9}, {0x1f, 9}, {0x0d, 10}, {0x22, 11},
    {0x53, 12}, {0x55, 12}
};

/* Coeffs for last = 0, run = 2 -> 9. Indexed by [run-2][level-1] */

static const VLCtable coeff_tab6[8][5] =
{
    /* run = 2 */
    {
        {0x0b, 5}, {0x15, 7}, {0x1e, 9}, {0x0c, 10},
        {0x56, 12}
    },
    /* run = 3 */
    {
        {0x11, 6}, {0x1b, 8}, {0x1d, 9}, {0x0b, 10},
        {0x00, 0}
    },
    /* run = 4 */
    {
        {0x10, 6}, {0x22, 9}, {0x0a, 10}, {0x00, 0},
        {0x00, 0}
    },
    /* run = 5 */
    {
        {0x0d, 6}, {0x1c, 9}, {0x08, 10}, {0x00, 0},
        {0x00, 0}
    },
    /* run = 6 */
    {
        {0x12, 7}, {0x1b, 9}, {0x54, 12}, {0x00, 0},
        {0x00, 0}
    },
    /* run = 7 */
    {
        {0x14, 7}, {0x1a, 9}, {0x57, 12}, {0x00, 0},
        {0x00, 0}
    },
    /* run = 8 */
    {
        {0x19, 8}, {0x09, 10}, {0x00, 0}, {0x00, 0},
        {0x00, 0}
    },
    /* run = 9 */
    {
        {0x18, 8}, {0x23, 11}, {0x00, 0}, {0x00, 0},
        {0x00, 0}
    }
};

/* Coeffs for last = 0, run = 10 -> 14. Indexed by [run-10] */

static const VLCtable coeff_tab7[5] =
{
    {0x17, 8}, {0x19, 9}, {0x18, 9}, {0x07, 10},
    {0x58, 12}
};

/* Coeffs for last = 1, run = 0. Indexed by [level-1] */

static const VLCtable coeff_tab8[8] =
{
    {0x07, 4}, {0x0c, 6}, {0x16, 8}, {0x17, 9},
    {0x06, 10}, {0x05, 11}, {0x04, 11}, {0x59, 12}
};

/* Coeffs for last = 1, run = 1 -> 6. Indexed by [run-1][level-1] */

static const VLCtable coeff_tab9[6][3] =
{
    /* run = 1 */
    {
        {0x0f, 6}, {0x16, 9}, {0x05, 10}
    },
    /* run = 2 */
    {
        {0x0e, 6}, {0x04, 10}, {0x00, 0}
    },
    /* run = 3 */
    {
        {0x11, 7}, {0x24, 11}, {0x00, 0}
    },
    /* run = 4 */
    {
        {0x10, 7}, {0x25, 11}, {0x00, 0}
    },
    /* run = 5 */
    {
        {0x13, 7}, {0x5a, 12}, {0x00, 0}
    },
    /* run = 6 */
    {
        {0x15, 8}, {0x5b, 12}, {0x00, 0}
    }
};

/* Coeffs for last = 1, run = 7 -> 20. Indexed by [run-7] */

static const VLCtable coeff_tab10[14] =
{
    {0x14, 8}, {0x13, 8}, {0x1a, 8}, {0x15, 9},
    {0x14, 9}, {0x13, 9}, {0x12, 9}, {0x11, 9},
    {0x26, 11}, {0x27, 11}, {0x5c, 12}, {0x5d, 12},
    {0x5e, 12}, {0x5f, 12}
};


#ifndef NO_RVLC
/* RVLC tables */
/* DCT coefficients. Four tables, two for last = 0, two for last = 1.
   the sign bit must be added afterwards. */

/* DCT  coeffs (intra) for last = 0.  */

/* Indexed by [level-1] */

static const VLCtable coeff_RVLCtab1[27] =
{
    /* run = 0 */
    {     0x6,  3},
    {     0x7,  3},
    {     0xa,  4},
    {     0x9,  5},
    {    0x14,  6},
    {    0x15,  6},
    {    0x34,  7},
    {    0x74,  8},
    {    0x75,  8},
    {    0xdd,  9},
    {    0xec,  9},
    {   0x1ec, 10},
    {   0x1ed, 10},
    {   0x1f4, 10},
    {   0x3ec, 11},
    {   0x3ed, 11},
    {   0x3f4, 11},
    {   0x77d, 12},
    {   0x7bc, 12},
    {   0xfbd, 13},
    {   0xfdc, 13},
    {   0x7bd, 12},
    {   0xfdd, 13},
    {  0x1fbd, 14},
    {  0x1fdc, 14},
    {  0x1fdd, 14},
    {  0x1ffc, 15}
};


/* Indexed by [level-1] */

static const VLCtable coeff_RVLCtab2[13] =
{
    /* run = 1 */
    {     0x1,  4},
    {     0x8,  5},
    {    0x2d,  7},
    {    0x6c,  8},
    {    0x6d,  8},
    {    0xdc,  9},
    {   0x1dd, 10},
    {   0x3dc, 11},
    {   0x3dd, 11},
    {   0x77c, 12},
    {   0xfbc, 13},
    {  0x1f7d, 14},
    {  0x1fbc, 14}
};


/* Indexed by [level-1] */

static const VLCtable coeff_RVLCtab3[11] =
{
    /* run = 2 */

    {     0x4,  5},
    {    0x2c,  7},
    {    0xbc,  9},
    {   0x1dc, 10},
    {   0x3bc, 11},
    {   0x3bd, 11},
    {   0xefd, 13},
    {   0xf7c, 13},
    {   0xf7d, 13},
    {  0x1efd, 14},
    {  0x1f7c, 14}
};


/* Indexed by [level-1] */

static const VLCtable coeff_RVLCtab4[9] =
{
    /* run = 3 */
    {     0x5,  5},
    {    0x5c,  8},
    {    0xbd,  9},
    {   0x37d, 11},
    {   0x6fc, 12},
    {   0xefc, 13},
    {  0x1dfd, 14},
    {  0x1efc, 14},
    {  0x1ffd, 15}
};


/* Indexed by [run-4][level-1] */

static const VLCtable coeff_RVLCtab5[2][6] =
{
    /* run = 4 */
    {
        {     0xc,  6},
        {    0x5d,  8},
        {   0x1bd, 10},
        {   0x3fd, 12},
        {   0x6fd, 12},
        {  0x1bfd, 14}
    },
    /* run = 5 */
    {
        {     0xd,  6},
        {    0x7d,  9},
        {   0x2fc, 11},
        {   0x5fc, 12},
        {  0x1bfc, 14},
        {  0x1dfc, 14}
    }
};


/* Indexed by [run-6][level-1]       */

static const VLCtable coeff_RVLCtab6[2][5] =
{

    /* run = 6 */
    {
        {    0x1c,  7},
        {   0x17c, 10},
        {   0x2fd, 11},
        {   0x5fd, 12},
        {  0x2ffc, 15}
    },
    /* run = 7 */
    {
        {    0x1d,  7},
        {   0x17d, 10},
        {   0x37c, 11},
        {   0xdfd, 13},
        {  0x2ffd, 15}
    }

};
/* Indexed by [run-8][level-1] */

static const VLCtable coeff_RVLCtab7[2][4] =
{
    /* run = 8 */
    {
        {    0x3c,  8},
        {   0x1bc, 10},
        {   0xbfd, 13},
        {  0x17fd, 14}
    },
    /* run = 9 */
    {
        {    0x3d,  8},
        {   0x1fd, 11},
        {   0xdfc, 13},
        {  0x37fc, 15},
    }
};



/* Indexed by [run-10][level-1] */

static const VLCtable coeff_RVLCtab8[3][2] =
{
    /* run = 10 */
    {
        {    0x7c,  9},
        {   0x3fc, 12}
    },
    /* run = 11 */
    {
        {    0xfc, 10},
        {   0xbfc, 13}
    },
    /* run = 12 */
    {
        {    0xfd, 10},
        {  0x37fd, 15}
    }
};


/* Indexed by [level-1] */

static const VLCtable coeff_RVLCtab9[7] =
{
    /* run = 13 -> 19 */
    {   0x1fc, 11},
    {   0x7fc, 13},
    {   0x7fd, 13},
    {   0xffc, 14},
    {   0xffd, 14},
    {  0x17fc, 14},
    {  0x3bfc, 15}
};



/* first coeffs of last = 1. indexing by [run][level-1] */

static const VLCtable coeff_RVLCtab10[2][5] =
{
    /* run = 0 */
    {
        {     0xb,  4},
        {    0x78,  8},
        {   0x3f5, 11},
        {   0xfec, 13},
        {  0x1fec, 14}
    },
    /* run = 1 */
    {
        {    0x12,  5},
        {    0xed,  9},
        {   0x7dc, 12},
        {  0x1fed, 14},
        {  0x3bfd, 15}
    }

};

static const VLCtable coeff_RVLCtab11[3] =
{
    /* run = 2 */
    {    0x13,  5},
    {   0x3f8, 11},
    {  0x3dfc, 15}

};

static const VLCtable coeff_RVLCtab12[11][2] =
{
    /* run = 3 */
    {
        {    0x18,  6},
        {   0x7dd, 12}
    },
    /* run = 4 */
    {
        {    0x19,  6},
        {   0x7ec, 12}
    },
    /* run = 5 */
    {
        {    0x22,  6},
        {   0xfed, 13}
    },
    /* run = 6 */
    {
        {    0x23,  6},
        {   0xff4, 13}
    },
    /* run = 7 */
    {
        {    0x35,  7},
        {   0xff5, 13}
    },
    /* run = 8 */
    {
        {    0x38,  7},
        {   0xff8, 13}
    },
    /* run = 9 */
    {
        {    0x39,  7},
        {   0xff9, 13}
    },
    /* run = 10 */
    {
        {    0x42,  7},
        {  0x1ff4, 14}
    },
    /* run = 11 */
    {
        {    0x43,  7},
        {  0x1ff5, 14}
    },
    /* run = 12 */
    {
        {    0x79,  8},
        {  0x1ff8, 14}
    },
    /* run = 13 */
    {
        {    0x82,  8},
        {  0x3dfd, 15}
    }

};

static const VLCtable coeff_RVLCtab13[32] =
{
    /* run = 14 -> 44 */
    {    0x83,  8},
    {    0xf4,  9},
    {    0xf5,  9},
    {    0xf8,  9},
    {    0xf9,  9},
    {   0x102,  9},
    {   0x103,  9},
    {   0x1f5, 10},
    {   0x1f8, 10},
    {   0x1f9, 10},
    {   0x202, 10},
    {   0x203, 10},
    {   0x3f9, 11},
    {   0x402, 11},
    {   0x403, 11},
    {   0x7ed, 12},
    {   0x7f4, 12},
    {   0x7f5, 12},
    {   0x7f8, 12},
    {   0x7f9, 12},
    {   0x802, 12},
    {   0x803, 12},
    {  0x1002, 13},
    {  0x1003, 13},
    {  0x1ff9, 14},
    {  0x2002, 14},
    {  0x2003, 14},
    {  0x3efc, 15},
    {  0x3efd, 15},
    {  0x3f7c, 15},
    {  0x3f7d, 15}
};



/* Coeffs for last = 0, run = 0. Indexed by [level-1] */

static const VLCtable coeff_RVLCtab14[19] =
{
    /* run = 0 */
    {     0x6,  3},
    {     0x1,  4},
    {     0x4,  5},
    {    0x1c,  7},
    {    0x3c,  8},
    {    0x3d,  8},
    {    0x7c,  9},
    {    0xfc, 10},
    {    0xfd, 10},
    {   0x1fc, 11},
    {   0x1fd, 11},
    {   0x3fc, 12},
    {   0x7fc, 13},
    {   0x7fd, 13},
    {   0xbfc, 13},
    {   0xbfd, 13},
    {   0xffc, 14},
    {   0xffd, 14},
    {  0x1ffc, 15}
};

static const VLCtable coeff_RVLCtab15[10] =
{
    /* run = 1 */
    {     0x7,  3},
    {     0xc,  6},
    {    0x5c,  8},
    {    0x7d,  9},
    {   0x17c, 10},
    {   0x2fc, 11},
    {   0x3fd, 12},
    {   0xdfc, 13},
    {  0x17fc, 14},
    {  0x17fd, 14}
};

static const VLCtable coeff_RVLCtab16[2][7] =
{
    /* run = 2 */
    {
        {     0xa,  4},
        {    0x1d,  7},
        {    0xbc,  9},
        {   0x2fd, 11},
        {   0x5fc, 12},
        {  0x1bfc, 14},
        {  0x1bfd, 14}
    },
    /* run = 3 */
    {
        {     0x5,  5},
        {    0x5d,  8},
        {   0x17d, 10},
        {   0x5fd, 12},
        {   0xdfd, 13},
        {  0x1dfc, 14},
        {  0x1ffd, 15}
    }
};

static const VLCtable coeff_RVLCtab17[5] =
{
    /* run = 4 */
    {     0x8,  5},
    {    0x6c,  8},
    {   0x37c, 11},
    {   0xefc, 13},
    {  0x2ffc, 15}
};

static const VLCtable coeff_RVLCtab18[3][4] =
{
    /* run = 5 */
    {
        {     0x9,  5},
        {    0xbd,  9},
        {   0x37d, 11},
        {   0xefd, 13}
    },
    /* run = 6 */
    {
        {     0xd,  6},
        {   0x1bc, 10},
        {   0x6fc, 12},
        {  0x1dfd, 14}
    },
    /* run = 7 */
    {
        {    0x14,  6},
        {   0x1bd, 10},
        {   0x6fd, 12},
        {  0x2ffd, 15}
    }
};

static const VLCtable coeff_RVLCtab19[2][3] =
{
    /* run = 8 */
    {
        {    0x15,  6},
        {   0x1dc, 10},
        {   0xf7c, 13}
    },
    /* run = 9 */
    {
        {    0x2c,  7},
        {   0x1dd, 10},
        {  0x1efc, 14}
    }
};

static const VLCtable coeff_RVLCtab20[8][2] =
{
    /* run = 10 */
    {
        {    0x2d,  7},
        {   0x3bc, 11}
    },
    /* run = 11 */
    {
        {    0x34,  7},
        {   0x77c, 12}
    },
    /* run = 12 */
    {
        {    0x6d,  8},
        {   0xf7d, 13}
    },
    /* run = 13 */
    {
        {    0x74,  8},
        {  0x1efd, 14}
    },
    /* run = 14 */
    {
        {    0x75,  8},
        {  0x1f7c, 14}
    },
    /* run = 15 */
    {
        {    0xdc,  9},
        {  0x1f7d, 14}
    },
    /* run = 16 */
    {
        {    0xdd,  9},
        {  0x1fbc, 14}
    },
    /* run = 17 */
    {
        {    0xec,  9},
        {  0x37fc, 15}
    }
};

static const VLCtable coeff_RVLCtab21[21] =
{
    /* run = 18 -> 38 */
    {   0x1ec, 10},
    {   0x1ed, 10},
    {   0x1f4, 10},
    {   0x3bd, 11},
    {   0x3dc, 11},
    {   0x3dd, 11},
    {   0x3ec, 11},
    {   0x3ed, 11},
    {   0x3f4, 11},
    {   0x77d, 12},
    {   0x7bc, 12},
    {   0x7bd, 12},
    {   0xfbc, 13},
    {   0xfbd, 13},
    {   0xfdc, 13},
    {   0xfdd, 13},
    {  0x1fbd, 14},
    {  0x1fdc, 14},
    {  0x1fdd, 14},
    {  0x37fd, 15},
    {  0x3bfc, 15}
};


/* first coeffs of last = 1. indexing by [run][level-1] */

static const VLCtable coeff_RVLCtab22[2][5] =
{
    /* run = 0 */
    {
        {     0xb,  4},
        {    0x78,  8},
        {   0x3f5, 11},
        {   0xfec, 13},
        {  0x1fec, 14}
    },
    /* run = 1 */
    {
        {    0x12,  5},
        {    0xed,  9},
        {   0x7dc, 12},
        {  0x1fed, 14},
        {  0x3bfd, 15}
    }

};

static const VLCtable coeff_RVLCtab23[3] =
{
    /* run = 2 */
    {    0x13,  5},
    {   0x3f8, 11},
    {  0x3dfc, 15}

};

static const VLCtable coeff_RVLCtab24[11][2] =
{
    /* run = 3 */
    {
        {    0x18,  6},
        {   0x7dd, 12}
    },
    /* run = 4 */
    {
        {    0x19,  6},
        {   0x7ec, 12}
    },
    /* run = 5 */
    {
        {    0x22,  6},
        {   0xfed, 13}
    },
    /* run = 6 */
    {
        {    0x23,  6},
        {   0xff4, 13}
    },
    /* run = 7 */
    {
        {    0x35,  7},
        {   0xff5, 13}
    },
    /* run = 8 */
    {
        {    0x38,  7},
        {   0xff8, 13}
    },
    /* run = 9 */
    {
        {    0x39,  7},
        {   0xff9, 13}
    },
    /* run = 10 */
    {
        {    0x42,  7},
        {  0x1ff4, 14}
    },
    /* run = 11 */
    {
        {    0x43,  7},
        {  0x1ff5, 14}
    },
    /* run = 12 */
    {
        {    0x79,  8},
        {  0x1ff8, 14}
    },
    /* run = 13 */
    {
        {    0x82,  8},
        {  0x3dfd, 15}
    }

};

static const VLCtable coeff_RVLCtab25[32] =
{
    /* run = 14 -> 44 */
    {    0x83,  8},
    {    0xf4,  9},
    {    0xf5,  9},
    {    0xf8,  9},
    {    0xf9,  9},
    {   0x102,  9},
    {   0x103,  9},
    {   0x1f5, 10},
    {   0x1f8, 10},
    {   0x1f9, 10},
    {   0x202, 10},
    {   0x203, 10},
    {   0x3f9, 11},
    {   0x402, 11},
    {   0x403, 11},
    {   0x7ed, 12},
    {   0x7f4, 12},
    {   0x7f5, 12},
    {   0x7f8, 12},
    {   0x7f9, 12},
    {   0x802, 12},
    {   0x803, 12},
    {  0x1002, 13},
    {  0x1003, 13},
    {  0x1ff9, 14},
    {  0x2002, 14},
    {  0x2003, 14},
    {  0x3efc, 15},
    {  0x3efd, 15},
    {  0x3f7c, 15},
    {  0x3f7d, 15}
};

#endif /* NO_RVLC */

#endif /* _VLC_ENC_TAB_H_ */

