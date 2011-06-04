 /**
 * 
 * File Name:  armVCM4P2_Huff_Tables_VLC.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * File:        armVCM4P2_Huff_Tables_VLC.c
 * Description: Contains all the Huffman tables used in MPEG4 codec
 *
 */

#include "omxtypes.h"
#include "armOMX.h"

#include "armCOMM_Bitstream.h"

/* 
*  For Intra
*  last = 0 
*/
const OMX_U8 armVCM4P2_IntraL0RunIdx[11] = 
{ 
    0, 27, 37, 42, 46, 49, 52, 
    55, 58, 60, 62
};

/* Entry defined for all values 
*  for run = 0 to 14
*  Note: the last entry is to terminate while decoding
*/
const ARM_VLC32 armVCM4P2_IntraVlcL0[68] = 
{
        {2,    2},
        {3,    6},
        {4,    15},
        {5,    13},
        {5,    12},
        {6,    21},
        {6,    19},
        {6,    18},
        {7,    23},
        {8,    31},
        {8,    30},
        {8,    29},
        {9,    37},
        {9,    36},
        {9,    35},
        {9,    33},
        {10,   33},
        {10,   32},
        {10,   15},
        {10,   14},
        {11,    7},
        {11,    6},
        {11,   32},
        {11,   33},
        {12,   80},
        {12,   81},
        {12,   82},
        {4,    14},
        {6,    20},
        {7,    22},
        {8,    28},
        {9,    32},
        {9,    31},
        {10,   13},
        {11,   34},
        {12,   83},
        {12,   85},
        {5,    11},
        {7,    21},
        {9,    30},
        {10,   12},
        {12,   86},
        {6,    17},
        {8,    27},
        {9,    29},
        {10,   11},
        {6,    16},
        {9,    34},
        {10,   10},
        {6,    13},
        {9,    28},
        {10,    8},
        {7,    18},
        {9,    27},
        {12,   84},
        {7,    20},
        {9,    26},
        {12,   87},
        {8,    25},
        {10,    9},
        {8,    24},
        {11,   35},
        {8,    23},
        {9,    25},
        {9,    24},
        {10,    7},
        {12,   88},
        {0,     0}
};

/* 
*  For Intra
*  last = 1 
*/

const OMX_U8 armVCM4P2_IntraL1RunIdx[8] = 
{
    0,  8, 11, 13, 15, 17, 19, 21
};

/* Entry defined for all values 
*  for run = 0 to 20
*  *  Note: the last entry is to terminate while decoding
*/
const ARM_VLC32 armVCM4P2_IntraVlcL1[36] = 
{
        {4,     7},
        {6,    12},
        {8,    22},
        {9,    23},
        {10,    6},
        {11,    5},
        {11,    4},
        {12,   89},
        {6,    15},
        {9,    22},
        {10,    5},
        {6,    14},
        {10,    4},
        {7,    17},
        {11,   36},
        {7,    16},
        {11,   37},
        {7,    19},
        {12,   90},
        {8,    21},
        {12,   91},
        {8,    20},
        {8,    19},
        {8,    26},
        {9,    21},
        {9,    20},
        {9,    19},
        {9,    18},
        {9,    17},
        {11,   38},
        {11,   39},
        {12,   92},
        {12,   93},
        {12,   94},
        {12,   95},  
        {0,     0}
};

/* LMAX table for Intra (Last == 0)*/
const OMX_U8 armVCM4P2_IntraL0LMAX[15] = 
{
   27, 10,  5,  4,  3,  3,  3,  
    3,  2,  2,  1,  1,  1,  1,  1
};

/* LMAX table for Intra (Last == 1)*/
const OMX_U8 armVCM4P2_IntraL1LMAX[21] = 
{
    8,  3,  2,  2,  2,  2,  2,  1, 
	1,  1,  1,  1,  1,  1,  1,  1,
	1,  1,  1,  1,  1
};

/* RMAX table for Intra (Last == 0)
   Level - 1 Indexed 
*/
const OMX_U8 armVCM4P2_IntraL0RMAX[27] =
{
   14,  9,  7,  3,  2,  1,	1,  
    1,  1,  1,  0,  0,  0, 	0,  
    0,  0,  0,  0,  0,  0,  0,  
    0,  0,  0,  0,  0,  0
};

/* RMAX table for Intra (Last == 1)
   Level - 1 Indexed 
*/
const OMX_U8 armVCM4P2_IntraL1RMAX[8] =
{
   20,  6,  1,  0,  0,  0,  0,  0
};

/* 
*  For Inter
*  last = 0 
*/
const OMX_U8 armVCM4P2_InterL0RunIdx[12] = 
{ 
     0,  12,  18,  22,  25,  28,  
    31,  34,  36,  38,  40,  42
};

/* Entry defined for all values 
*  for run = 0 to 26
*  Note: the last entry is to terminate while decoding
*/
const ARM_VLC32 armVCM4P2_InterVlcL0[59] = 
{
        {2,     2},
        {4,    15},
        {6,    21},
        {7,    23},
        {8,    31},
        {9,    37},
        {9,    36},
        {10,   33},
        {10,   32},
        {11,    7},
        {11,    6},
        {11,   32},
        {3,     6},
        {6,    20},
        {8,    30},
        {10,   15},
        {11,   33},
        {12,   80},
        {4,    14},
        {8,    29},
        {10,   14},
        {12,   81},
        {5,    13},
        {9,    35},
        {10,   13},
        {5,    12},
        {9,    34},
        {12,   82},
        {5,    11},
        {10,   12},
        {12,   83},
        {6,    19},
        {10,   11},
        {12,   84},
        {6,    18},
        {10,   10},
        {6,    17},
        {10,    9},
        {6,    16},
        {10,    8},
        {7,    22},
        {12,   85},
        {7,    21},
        {7,    20},
        {8,    28},
        {8,    27},
        {9,    33},
        {9,    32},
        {9,    31},
        {9,    30},
        {9,    29},
        {9,    28},
        {9,    27},
        {9,    26},
        {11,   34},
        {11,   35},
        {12,   86},
        {12,   87},
        {0,     0}
};
 

/* 
*  For Intra
*  last = 1 
*/

const OMX_U8 armVCM4P2_InterL1RunIdx[3] = 
{
    0, 3, 5
};

/* Entry defined for all values 
*  for run = 0 to 40
*  Note: the last entry is to terminate while decoding
*/
const ARM_VLC32 armVCM4P2_InterVlcL1[45] = 
{
        {4,     7},
        {9,    25},
        {11,    5},
        {6,    15},
        {11,    4},
        {6,    14},
        {6,    13},
        {6,    12},
        {7,    19},
        {7,    18},
        {7,    17},
        {7,    16},
        {8,    26},
        {8,    25},
        {8,    24},
        {8,    23},
        {8,    22},
        {8,    21},
        {8,    20},
        {8,    19},
        {9,    24},
        {9,    23},
        {9,    22},
        {9,    21},
        {9,    20},
        {9,    19},
        {9,    18},
        {9,    17},
        {10,    7},
        {10,    6},
        {10,    5},
        {10,    4},
        {11,   36},
        {11,   37},
        {11,   38},
        {11,   39},
        {12,   88},
        {12,   89},
        {12,   90},
        {12,   91},
        {12,   92},
        {12,   93},
        {12,   94},
        {12,   95},
        { 0,    0}
};

/* LMAX table for Intra (Last == 0)*/
const OMX_U8 armVCM4P2_InterL0LMAX[27] = 
{
   12,  6,  4,  3,  3,  3,  3,  2, 
    2,  2,  2,  1,  1,  1,  1,  1,
    1,  1,  1,  1,  1,  1,  1,  1,
    1,  1,  1,
};

/* LMAX table for Intra (Last == 1)*/
const OMX_U8 armVCM4P2_InterL1LMAX[41] = 
{
    3,  2,  1,  1,  1,  1,  1,  1, 
	1,  1,  1,  1,  1,  1,  1,  1,
	1,  1,  1,  1,  1,  1,  1,  1,
	1,  1,  1,  1,  1,  1,  1,  1,
	1,  1,  1,  1,  1,  1,  1,  1,
	1,  
};

/* RMAX table for Intra (Last == 0)
   Level - 1 Indexed 
*/
const OMX_U8 armVCM4P2_InterL0RMAX[12] = 
{
   26, 10,  6,  2,  1,  1,   
    0,  0,  0,  0,  0,  0
};

/* RMAX table for Intra (Last == 1)
   Level - 1 Indexed 
*/
const OMX_U8 armVCM4P2_InterL1RMAX[3] = 
{
   40,  1,  0
};

/* 
*  For Intra - Luminance
*/

const ARM_VLC32 armVCM4P2_aIntraDCLumaIndex[14] = 
{
        {3,     3},
        {2,     3},
        {2,     2},
        {3,     2},
        {3,     1},
        {4,     1},
        {5,     1},
        {6,     1},
        {7,     1},
        {8,     1},
        {9,     1},
        {10,    1},
        {11,    1},
        {0,     0}
};

/* 
*  For Intra - Chrominance
*/
 
const ARM_VLC32 armVCM4P2_aIntraDCChromaIndex[14] = 
{
        {2,     3},
        {2,     2},
        {2,     1},
        {3,     1},
        {4,     1},
        {5,     1},
        {6,     1},
        {7,     1},
        {8,     1},
        {9,     1},
        {10,    1},
        {11,    1},
        {12,    1},
        {0,     0}
};

/* 
 *  Motion vector decoding table
 */
 
const ARM_VLC32 armVCM4P2_aVlcMVD[66] =
{
        {13,     5},
        {13,     7},
        {12,     5},
        {12,     7},
        {12,     9},
        {12,    11},
        {12,    13},
        {12,    15},
        {11,     9},
        {11,    11},
        {11,    13},
        {11,    15},
        {11,    17},
        {11,    19},
        {11,    21},
        {11,    23},
        {11,    25},
        {11,    27},
        {11,    29},
        {11,    31},
        {11,    33},
        {11,    35},
        {10,    19},
        {10,    21},
        {10,    23},
        {8,      7},
        {8,      9},
        {8,     11},
        {7,      7},
        {5,      3},
        {4,      3},
        {3,      3},
        {1,      1},
        {3,      2},
        {4,      2},
        {5,      2},
        {7,      6},
        {8,     10},
        {8,      8},
        {8,      6},
        {10,    22},
        {10,    20},
        {10,    18},
        {11,    34},
        {11,    32},
        {11,    30},
        {11,    28},
        {11,    26},
        {11,    24},
        {11,    22},
        {11,    20},
        {11,    18},
        {11,    16},
        {11,    14},
        {11,    12},
        {11,    10},
        {11,     8},
        {12,    14},
        {12,    12},
        {12,    10},
        {12,     8},
        {12,     6},
        {12,     4},
        {13,     6},
        {13,     4},
        { 0,     0}
};

/* End of file */



