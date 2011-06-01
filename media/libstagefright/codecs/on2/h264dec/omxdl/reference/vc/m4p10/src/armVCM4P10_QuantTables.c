/* ----------------------------------------------------------------
 *
 * 
 * File Name:  armVCM4P10_QuantTables.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * H.264 inverse quantize tables
 * 
 */
 
#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armVC.h"

const OMX_U32 armVCM4P10_MFMatrix[6][3] =
{
    {13107, 5243, 8066},
    {11916, 4660, 7490},
    {10082, 4194, 6554},
    { 9362, 3647, 5825},
    { 8192, 3355, 5243},
    { 7282, 2893, 4559}
}; 
