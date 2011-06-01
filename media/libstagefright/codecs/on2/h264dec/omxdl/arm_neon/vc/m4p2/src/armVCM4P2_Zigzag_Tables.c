/**
 * 
 * File Name:  armVCM4P2_Zigzag_Tables.c
 * OpenMAX DL: v1.0.2
 * Revision:   12290
 * Date:       Wednesday, April 9, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * File:        armVCM4P2_ZigZag_Tables.c
 * Description: Contains the zigzag tables
 *
 */

#include "omxtypes.h"

/* Contains Double the values in the reference Zigzag Table
 * Contains Classical,Vetical and Horizontal Zigzagscan tables in one array  
 */

const OMX_U8 armVCM4P2_aClassicalZigzagScan [192] = 
{
     0,  2,  16, 32,  18,  4,  6, 20,
    34, 48, 64, 50, 36, 22,  8,  10,
    24, 38, 52, 66, 80, 96, 82, 68,
    54, 40, 26,  12,  14, 28, 42, 56, 
    70, 84, 98, 112, 114, 100, 86, 72,
    58, 44, 30, 46, 60, 74, 88, 102,
    116, 118, 104, 90, 76, 62, 78, 92,
    106, 120, 122, 104, 94, 110, 124, 126,

	0,  16, 32, 48,  2,  18,  4, 20,
    34, 50, 64, 80, 96, 112, 114, 98,
    82, 66, 52, 36,  6, 22,  8, 24,
    38, 54, 68, 84, 100, 116, 70, 86,
    102, 118, 40, 56,  10, 26,  12, 28,
    42, 58, 72, 88, 104, 120, 74, 90, 
    106, 122, 44, 60,  14, 30, 46, 62,
    76, 92, 108, 124, 78, 94, 110, 126,

    0,  2,  4,  6,  16,  18, 32, 34,
    20, 22,  8,  10,  12,  14, 30, 28,
    26, 24, 38, 36, 48, 50, 64, 66,
    52, 54, 40, 42, 44, 46, 56, 58,
    60, 62, 68, 70, 80, 82, 96, 98,
    84, 86, 72, 74, 76, 78, 88, 90, 
    92, 94, 100, 102, 112, 114, 116, 118,
    104, 106, 108, 110, 120, 122, 124, 126


};





/* End of file */


