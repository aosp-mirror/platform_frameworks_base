/* ----------------------------------------------------------------
 * 
 * 
 * File Name:  armVCM4P10_CAVLCTables.h
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * ----------------------------------------------------------------
 * File:     armVCM4P10_CAVLCTables.h
 * ----------------------------------------------------------------
 * 
 * Header file for ARM implementation of OpenMAX VCM4P10
 * 
 */
 
#ifndef ARMVCM4P10_CAVLCTABLES_H
#define ARMVCM4P10_CAVLCTABLES_H
  
/* CAVLC tables */

extern const OMX_U8 armVCM4P10_CAVLCTrailingOnes[62];
extern const OMX_U8 armVCM4P10_CAVLCTotalCoeff[62];
extern const ARM_VLC32 *armVCM4P10_CAVLCCoeffTokenTables[5];
extern const ARM_VLC32 armVCM4P10_CAVLCLevelPrefix[17];
extern const ARM_VLC32 *armVCM4P10_CAVLCTotalZeroTables[15];
extern const ARM_VLC32 *armVCM4P10_CAVLCTotalZeros2x2Tables[3];
extern const ARM_VLC32 *armVCM4P10_CAVLCRunBeforeTables[7];

#endif
