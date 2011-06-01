 /**
 * 
 * File Name:  armVCM4P2_Zigzag_Tables.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
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

const OMX_U8 armVCM4P2_aClassicalZigzagScan [64] = 
{
     0,  1,  8, 16,  9,  2,  3, 10,
    17, 24, 32, 25, 18, 11,  4,  5,
    12, 19, 26, 33, 40, 48, 41, 34,
    27, 20, 13,  6,  7, 14, 21, 28, 
    35, 42, 49, 56, 57, 50, 43, 36,
    29, 22, 15, 23, 30, 37, 44, 51,
    58, 59, 52, 45, 38, 31, 39, 46,
    53, 60, 61, 54, 47, 55, 62, 63
};

const OMX_U8 armVCM4P2_aHorizontalZigzagScan [64] = 
{
     0,  1,  2,  3,  8,  9, 16, 17,
    10, 11,  4,  5,  6,  7, 15, 14,
    13, 12, 19, 18, 24, 25, 32, 33,
    26, 27, 20, 21, 22, 23, 28, 29,
    30, 31, 34, 35, 40, 41, 48, 49,
    42, 43, 36, 37, 38, 39, 44, 45, 
    46, 47, 50, 51, 56, 57, 58, 59,
    52, 53, 54, 55, 60, 61, 62, 63
};

const OMX_U8 armVCM4P2_aVerticalZigzagScan [64] = 
{
     0,  8, 16, 24,  1,  9,  2, 10,
     17, 25, 32, 40, 48, 56, 57, 49,
     41, 33, 26, 18,  3, 11,  4, 12,
     19, 27, 34, 42, 50, 58, 35, 43,
     51, 59, 20, 28,  5, 13,  6, 14,
     21, 29, 36, 44, 52, 60, 37, 45, 
     53, 61, 22, 30,  7, 15, 23, 31,
     38, 46, 54, 62, 39, 47, 55, 63
};


/* End of file */


