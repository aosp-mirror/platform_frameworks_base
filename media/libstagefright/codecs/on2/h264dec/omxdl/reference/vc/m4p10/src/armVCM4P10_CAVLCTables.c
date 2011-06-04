/* ----------------------------------------------------------------
 *
 * 
 * File Name:  armVCM4P10_CAVLCTables.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * 
 * CAVLC tables for H.264
 * 
 */
 
#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM_Bitstream.h"
#include "armVC.h"
#include "armVCM4P10_CAVLCTables.h"

/* Tables mapping a code to TrailingOnes and TotalCoeff */

const OMX_U8 armVCM4P10_CAVLCTrailingOnes[62] = {
 0,
 0, 1,
 0, 1, 2,
 0, 1, 2, 3,
 0, 1, 2, 3,
 0, 1, 2, 3,
 0, 1, 2, 3,
 0, 1, 2, 3,
 0, 1, 2, 3,
 0, 1, 2, 3,
 0, 1, 2, 3,
 0, 1, 2, 3,
 0, 1, 2, 3,
 0, 1, 2, 3,
 0, 1, 2, 3,
 0, 1, 2, 3,
 0, 1, 2, 3
};

const OMX_U8 armVCM4P10_CAVLCTotalCoeff[62] = {
 0,
 1, 1,
 2, 2, 2,
 3, 3, 3, 3,
 4, 4, 4, 4,
 5, 5, 5, 5,
 6, 6, 6, 6,
 7, 7, 7, 7,
 8, 8, 8, 8,
 9, 9, 9, 9,
 10, 10, 10, 10,
 11, 11, 11, 11,
 12, 12, 12, 12,
 13, 13, 13, 13,
 14, 14, 14, 14,
 15, 15, 15, 15,
 16, 16, 16, 16
};

static const ARM_VLC32 armVCM4P10_CAVLCCoeffToken0[63] = {
    {  1, 0x0001 },
    {  6, 0x0005 },
    {  2, 0x0001 },
    {  8, 0x0007 },
    {  6, 0x0004 },
    {  3, 0x0001 },
    {  9, 0x0007 },
    {  8, 0x0006 },
    {  7, 0x0005 },
    {  5, 0x0003 },
    { 10, 0x0007 },
    {  9, 0x0006 },
    {  8, 0x0005 },
    {  6, 0x0003 },
    { 11, 0x0007 },
    { 10, 0x0006 },
    {  9, 0x0005 },
    {  7, 0x0004 },
    { 13, 0x000f },
    { 11, 0x0006 },
    { 10, 0x0005 },
    {  8, 0x0004 },
    { 13, 0x000b },
    { 13, 0x000e },
    { 11, 0x0005 },
    {  9, 0x0004 },
    { 13, 0x0008 },
    { 13, 0x000a },
    { 13, 0x000d },
    { 10, 0x0004 },
    { 14, 0x000f },
    { 14, 0x000e },
    { 13, 0x0009 },
    { 11, 0x0004 },
    { 14, 0x000b },
    { 14, 0x000a },
    { 14, 0x000d },
    { 13, 0x000c },
    { 15, 0x000f },
    { 15, 0x000e },
    { 14, 0x0009 },
    { 14, 0x000c },
    { 15, 0x000b },
    { 15, 0x000a },
    { 15, 0x000d },
    { 14, 0x0008 },
    { 16, 0x000f },
    { 15, 0x0001 },
    { 15, 0x0009 },
    { 15, 0x000c },
    { 16, 0x000b },
    { 16, 0x000e },
    { 16, 0x000d },
    { 15, 0x0008 },
    { 16, 0x0007 },
    { 16, 0x000a },
    { 16, 0x0009 },
    { 16, 0x000c },
    { 16, 0x0004 },
    { 16, 0x0006 },
    { 16, 0x0005 },
    { 16, 0x0008 },
    {  0, 0x0000 }
};

static const ARM_VLC32 armVCM4P10_CAVLCCoeffToken1[63] = {
    {  2, 0x0003 },
    {  6, 0x000b },
    {  2, 0x0002 },
    {  6, 0x0007 },
    {  5, 0x0007 },
    {  3, 0x0003 },
    {  7, 0x0007 },
    {  6, 0x000a },
    {  6, 0x0009 },
    {  4, 0x0005 },
    {  8, 0x0007 },
    {  6, 0x0006 },
    {  6, 0x0005 },
    {  4, 0x0004 },
    {  8, 0x0004 },
    {  7, 0x0006 },
    {  7, 0x0005 },
    {  5, 0x0006 },
    {  9, 0x0007 },
    {  8, 0x0006 },
    {  8, 0x0005 },
    {  6, 0x0008 },
    { 11, 0x000f },
    {  9, 0x0006 },
    {  9, 0x0005 },
    {  6, 0x0004 },
    { 11, 0x000b },
    { 11, 0x000e },
    { 11, 0x000d },
    {  7, 0x0004 },
    { 12, 0x000f },
    { 11, 0x000a },
    { 11, 0x0009 },
    {  9, 0x0004 },
    { 12, 0x000b },
    { 12, 0x000e },
    { 12, 0x000d },
    { 11, 0x000c },
    { 12, 0x0008 },
    { 12, 0x000a },
    { 12, 0x0009 },
    { 11, 0x0008 },
    { 13, 0x000f },
    { 13, 0x000e },
    { 13, 0x000d },
    { 12, 0x000c },
    { 13, 0x000b },
    { 13, 0x000a },
    { 13, 0x0009 },
    { 13, 0x000c },
    { 13, 0x0007 },
    { 14, 0x000b },
    { 13, 0x0006 },
    { 13, 0x0008 },
    { 14, 0x0009 },
    { 14, 0x0008 },
    { 14, 0x000a },
    { 13, 0x0001 },
    { 14, 0x0007 },
    { 14, 0x0006 },
    { 14, 0x0005 },
    { 14, 0x0004 },
    {  0, 0x0000 }
};

static const ARM_VLC32 armVCM4P10_CAVLCCoeffToken2[63] = {
    {  4, 0x000f },
    {  6, 0x000f },
    {  4, 0x000e },
    {  6, 0x000b },
    {  5, 0x000f },
    {  4, 0x000d },
    {  6, 0x0008 },
    {  5, 0x000c },
    {  5, 0x000e },
    {  4, 0x000c },
    {  7, 0x000f },
    {  5, 0x000a },
    {  5, 0x000b },
    {  4, 0x000b },
    {  7, 0x000b },
    {  5, 0x0008 },
    {  5, 0x0009 },
    {  4, 0x000a },
    {  7, 0x0009 },
    {  6, 0x000e },
    {  6, 0x000d },
    {  4, 0x0009 },
    {  7, 0x0008 },
    {  6, 0x000a },
    {  6, 0x0009 },
    {  4, 0x0008 },
    {  8, 0x000f },
    {  7, 0x000e },
    {  7, 0x000d },
    {  5, 0x000d },
    {  8, 0x000b },
    {  8, 0x000e },
    {  7, 0x000a },
    {  6, 0x000c },
    {  9, 0x000f },
    {  8, 0x000a },
    {  8, 0x000d },
    {  7, 0x000c },
    {  9, 0x000b },
    {  9, 0x000e },
    {  8, 0x0009 },
    {  8, 0x000c },
    {  9, 0x0008 },
    {  9, 0x000a },
    {  9, 0x000d },
    {  8, 0x0008 },
    { 10, 0x000d },
    {  9, 0x0007 },
    {  9, 0x0009 },
    {  9, 0x000c },
    { 10, 0x0009 },
    { 10, 0x000c },
    { 10, 0x000b },
    { 10, 0x000a },
    { 10, 0x0005 },
    { 10, 0x0008 },
    { 10, 0x0007 },
    { 10, 0x0006 },
    { 10, 0x0001 },
    { 10, 0x0004 },
    { 10, 0x0003 },
    { 10, 0x0002 },
    {  0, 0x0000 }
};

static const ARM_VLC32 armVCM4P10_CAVLCCoeffToken3[63] = {
    {  6, 0x0003 },
    {  6, 0x0000 },
    {  6, 0x0001 },
    {  6, 0x0004 },
    {  6, 0x0005 },
    {  6, 0x0006 },
    {  6, 0x0008 },
    {  6, 0x0009 },
    {  6, 0x000a },
    {  6, 0x000b },
    {  6, 0x000c },
    {  6, 0x000d },
    {  6, 0x000e },
    {  6, 0x000f },
    {  6, 0x0010 },
    {  6, 0x0011 },
    {  6, 0x0012 },
    {  6, 0x0013 },
    {  6, 0x0014 },
    {  6, 0x0015 },
    {  6, 0x0016 },
    {  6, 0x0017 },
    {  6, 0x0018 },
    {  6, 0x0019 },
    {  6, 0x001a },
    {  6, 0x001b },
    {  6, 0x001c },
    {  6, 0x001d },
    {  6, 0x001e },
    {  6, 0x001f },
    {  6, 0x0020 },
    {  6, 0x0021 },
    {  6, 0x0022 },
    {  6, 0x0023 },
    {  6, 0x0024 },
    {  6, 0x0025 },
    {  6, 0x0026 },
    {  6, 0x0027 },
    {  6, 0x0028 },
    {  6, 0x0029 },
    {  6, 0x002a },
    {  6, 0x002b },
    {  6, 0x002c },
    {  6, 0x002d },
    {  6, 0x002e },
    {  6, 0x002f },
    {  6, 0x0030 },
    {  6, 0x0031 },
    {  6, 0x0032 },
    {  6, 0x0033 },
    {  6, 0x0034 },
    {  6, 0x0035 },
    {  6, 0x0036 },
    {  6, 0x0037 },
    {  6, 0x0038 },
    {  6, 0x0039 },
    {  6, 0x003a },
    {  6, 0x003b },
    {  6, 0x003c },
    {  6, 0x003d },
    {  6, 0x003e },
    {  6, 0x003f },
    {  0, 0x0000 }
};

static const ARM_VLC32 armVCM4P10_CAVLCCoeffToken4[15] = {
    {  2, 0x0001 },
    {  6, 0x0007 },
    {  1, 0x0001 },
    {  6, 0x0004 },
    {  6, 0x0006 },
    {  3, 0x0001 },
    {  6, 0x0003 },
    {  7, 0x0003 },
    {  7, 0x0002 },
    {  6, 0x0005 },
    {  6, 0x0002 },
    {  8, 0x0003 },
    {  8, 0x0002 },
    {  7, 0x0000 },
    {  0, 0x0000 }
};


const ARM_VLC32 *armVCM4P10_CAVLCCoeffTokenTables[5] = {
     armVCM4P10_CAVLCCoeffToken0, 
     armVCM4P10_CAVLCCoeffToken1,
     armVCM4P10_CAVLCCoeffToken2, 
     armVCM4P10_CAVLCCoeffToken3, 
     armVCM4P10_CAVLCCoeffToken4
};

/* Table for level_prefix */

const ARM_VLC32 armVCM4P10_CAVLCLevelPrefix[17] = {
    {  1, 1},
    {  2, 1},
    {  3, 1},
    {  4, 1},
    {  5, 1},
    {  6, 1},
    {  7, 1},
    {  8, 1},
    {  9, 1},
    { 10, 1},
    { 11, 1},
    { 12, 1},
    { 13, 1},
    { 14, 1},
    { 15, 1},
    { 16, 1},
    {  0, 0}
};

/* Tables for total_zeros */

static const ARM_VLC32 armVCM4P10_CAVLCTotalZeros1[17] = {
    {  1, 0x0001 },
    {  3, 0x0003 },
    {  3, 0x0002 },
    {  4, 0x0003 },
    {  4, 0x0002 },
    {  5, 0x0003 },
    {  5, 0x0002 },
    {  6, 0x0003 },
    {  6, 0x0002 },
    {  7, 0x0003 },
    {  7, 0x0002 },
    {  8, 0x0003 },
    {  8, 0x0002 },
    {  9, 0x0003 },
    {  9, 0x0002 },
    {  9, 0x0001 },
    {  0, 0x0000 }
};

static const ARM_VLC32 armVCM4P10_CAVLCTotalZeros2[16] = {
    {  3, 0x0007 },
    {  3, 0x0006 },
    {  3, 0x0005 },
    {  3, 0x0004 },
    {  3, 0x0003 },
    {  4, 0x0005 },
    {  4, 0x0004 },
    {  4, 0x0003 },
    {  4, 0x0002 },
    {  5, 0x0003 },
    {  5, 0x0002 },
    {  6, 0x0003 },
    {  6, 0x0002 },
    {  6, 0x0001 },
    {  6, 0x0000 },
    {  0, 0x0000 }
};

static const ARM_VLC32 armVCM4P10_CAVLCTotalZeros3[15] = {
    {  4, 0x0005 },
    {  3, 0x0007 },
    {  3, 0x0006 },
    {  3, 0x0005 },
    {  4, 0x0004 },
    {  4, 0x0003 },
    {  3, 0x0004 },
    {  3, 0x0003 },
    {  4, 0x0002 },
    {  5, 0x0003 },
    {  5, 0x0002 },
    {  6, 0x0001 },
    {  5, 0x0001 },
    {  6, 0x0000 },
    {  0, 0x0000 }
};

static const ARM_VLC32 armVCM4P10_CAVLCTotalZeros4[14] = {
    {  5, 0x0003 },
    {  3, 0x0007 },
    {  4, 0x0005 },
    {  4, 0x0004 },
    {  3, 0x0006 },
    {  3, 0x0005 },
    {  3, 0x0004 },
    {  4, 0x0003 },
    {  3, 0x0003 },
    {  4, 0x0002 },
    {  5, 0x0002 },
    {  5, 0x0001 },
    {  5, 0x0000 },
    {  0, 0x0000 }
};

static const ARM_VLC32 armVCM4P10_CAVLCTotalZeros5[13] = {
    {  4, 0x0005 },
    {  4, 0x0004 },
    {  4, 0x0003 },
    {  3, 0x0007 },
    {  3, 0x0006 },
    {  3, 0x0005 },
    {  3, 0x0004 },
    {  3, 0x0003 },
    {  4, 0x0002 },
    {  5, 0x0001 },
    {  4, 0x0001 },
    {  5, 0x0000 },
    {  0, 0x0000 }
};

static const ARM_VLC32 armVCM4P10_CAVLCTotalZeros6[12] = {
    {  6, 0x0001 },
    {  5, 0x0001 },
    {  3, 0x0007 },
    {  3, 0x0006 },
    {  3, 0x0005 },
    {  3, 0x0004 },
    {  3, 0x0003 },
    {  3, 0x0002 },
    {  4, 0x0001 },
    {  3, 0x0001 },
    {  6, 0x0000 },
    {  0, 0x0000 }
};

static const ARM_VLC32 armVCM4P10_CAVLCTotalZeros7[11] = {
    {  6, 0x0001 },
    {  5, 0x0001 },
    {  3, 0x0005 },
    {  3, 0x0004 },
    {  3, 0x0003 },
    {  2, 0x0003 },
    {  3, 0x0002 },
    {  4, 0x0001 },
    {  3, 0x0001 },
    {  6, 0x0000 },
    {  0, 0x0000 }
};

static const ARM_VLC32 armVCM4P10_CAVLCTotalZeros8[10] = {
    {  6, 0x0001 },
    {  4, 0x0001 },
    {  5, 0x0001 },
    {  3, 0x0003 },
    {  2, 0x0003 },
    {  2, 0x0002 },
    {  3, 0x0002 },
    {  3, 0x0001 },
    {  6, 0x0000 },
    {  0, 0x0000 }
};

static const ARM_VLC32 armVCM4P10_CAVLCTotalZeros9[9] = {
    {  6, 0x0001 },
    {  6, 0x0000 },
    {  4, 0x0001 },
    {  2, 0x0003 },
    {  2, 0x0002 },
    {  3, 0x0001 },
    {  2, 0x0001 },
    {  5, 0x0001 },
    {  0, 0x0000 }
};

static const ARM_VLC32 armVCM4P10_CAVLCTotalZeros10[8] = {
    {  5, 0x0001 },
    {  5, 0x0000 },
    {  3, 0x0001 },
    {  2, 0x0003 },
    {  2, 0x0002 },
    {  2, 0x0001 },
    {  4, 0x0001 },
    {  0, 0x0000 }
};

static const ARM_VLC32 armVCM4P10_CAVLCTotalZeros11[7] = {
    {  4, 0x0000 },
    {  4, 0x0001 },
    {  3, 0x0001 },
    {  3, 0x0002 },
    {  1, 0x0001 },
    {  3, 0x0003 },
    {  0, 0x0000 }
};

static const ARM_VLC32 armVCM4P10_CAVLCTotalZeros12[6] = {
    {  4, 0x0000 },
    {  4, 0x0001 },
    {  2, 0x0001 },
    {  1, 0x0001 },
    {  3, 0x0001 },
    {  0, 0x0000 }
};

static const ARM_VLC32 armVCM4P10_CAVLCTotalZeros13[5] = {
    {  3, 0x0000 },
    {  3, 0x0001 },
    {  1, 0x0001 },
    {  2, 0x0001 },
    {  0, 0x0000 }
};

static const ARM_VLC32 armVCM4P10_CAVLCTotalZeros14[4] = {
    {  2, 0x0000 },
    {  2, 0x0001 },
    {  1, 0x0001 },
    {  0, 0x0000 }
};

static const ARM_VLC32 armVCM4P10_CAVLCTotalZeros15[3] = {
    {  1, 0x0000 },
    {  1, 0x0001 },
    {  0, 0x0000 }
};

const ARM_VLC32 *armVCM4P10_CAVLCTotalZeroTables[15] = {
     armVCM4P10_CAVLCTotalZeros1, 
     armVCM4P10_CAVLCTotalZeros2,
     armVCM4P10_CAVLCTotalZeros3, 
     armVCM4P10_CAVLCTotalZeros4, 
     armVCM4P10_CAVLCTotalZeros5, 
     armVCM4P10_CAVLCTotalZeros6, 
     armVCM4P10_CAVLCTotalZeros7, 
     armVCM4P10_CAVLCTotalZeros8, 
     armVCM4P10_CAVLCTotalZeros9, 
     armVCM4P10_CAVLCTotalZeros10, 
     armVCM4P10_CAVLCTotalZeros11, 
     armVCM4P10_CAVLCTotalZeros12, 
     armVCM4P10_CAVLCTotalZeros13, 
     armVCM4P10_CAVLCTotalZeros14, 
     armVCM4P10_CAVLCTotalZeros15 
};

static const ARM_VLC32 armVCM4P10_CAVLCTotalZeros2x2_1[5] = {
    {  1, 1 },
    {  2, 1 },
    {  3, 1 },
    {  3, 0 },
    {  0, 0 }
};

static const ARM_VLC32 armVCM4P10_CAVLCTotalZeros2x2_2[4] = {
    {  1, 1 },
    {  2, 1 },
    {  2, 0 },
    {  0, 0 }
};

static const ARM_VLC32 armVCM4P10_CAVLCTotalZeros2x2_3[3] = {
    {  1, 1 },
    {  1, 0 },
    {  0, 0 }
};

const ARM_VLC32 *armVCM4P10_CAVLCTotalZeros2x2Tables[3] = {
     armVCM4P10_CAVLCTotalZeros2x2_1, 
     armVCM4P10_CAVLCTotalZeros2x2_2, 
     armVCM4P10_CAVLCTotalZeros2x2_3
};


/* Tables for run_before */

static const ARM_VLC32 armVCM4P10_CAVLCRunBefore1[3] = {
    {  1, 0x0001 },
    {  1, 0x0000 },
    {  0, 0x0000 }
};

static const ARM_VLC32 armVCM4P10_CAVLCRunBefore2[4] = {
    {  1, 0x0001 },
    {  2, 0x0001 },
    {  2, 0x0000 },
    {  0, 0x0000 }
};

static const ARM_VLC32 armVCM4P10_CAVLCRunBefore3[5] = {
    {  2, 0x0003 },
    {  2, 0x0002 },
    {  2, 0x0001 },
    {  2, 0x0000 },
    {  0, 0x0000 }
};

static const ARM_VLC32 armVCM4P10_CAVLCRunBefore4[6] = {
    {  2, 0x0003 },
    {  2, 0x0002 },
    {  2, 0x0001 },
    {  3, 0x0001 },
    {  3, 0x0000 },
    {  0, 0x0000 }
};

static const ARM_VLC32 armVCM4P10_CAVLCRunBefore5[7] = {
    {  2, 0x0003 },
    {  2, 0x0002 },
    {  3, 0x0003 },
    {  3, 0x0002 },
    {  3, 0x0001 },
    {  3, 0x0000 },
    {  0, 0x0000 }
};

static const ARM_VLC32 armVCM4P10_CAVLCRunBefore6[8] = {
    {  2, 0x0003 },
    {  3, 0x0000 },
    {  3, 0x0001 },
    {  3, 0x0003 },
    {  3, 0x0002 },
    {  3, 0x0005 },
    {  3, 0x0004 },
    {  0, 0x0000 }
};

static const ARM_VLC32 armVCM4P10_CAVLCRunBefore7[16] = {
    {  3, 0x0007 },
    {  3, 0x0006 },
    {  3, 0x0005 },
    {  3, 0x0004 },
    {  3, 0x0003 },
    {  3, 0x0002 },
    {  3, 0x0001 },
    {  4, 0x0001 },
    {  5, 0x0001 },
    {  6, 0x0001 },
    {  7, 0x0001 },
    {  8, 0x0001 },
    {  9, 0x0001 },
    { 10, 0x0001 },
    { 11, 0x0001 },
    {  0, 0x0000 }
};

const ARM_VLC32 *armVCM4P10_CAVLCRunBeforeTables[7] = {
     armVCM4P10_CAVLCRunBefore1, 
     armVCM4P10_CAVLCRunBefore2, 
     armVCM4P10_CAVLCRunBefore3, 
     armVCM4P10_CAVLCRunBefore4, 
     armVCM4P10_CAVLCRunBefore5, 
     armVCM4P10_CAVLCRunBefore6, 
     armVCM4P10_CAVLCRunBefore7
};
