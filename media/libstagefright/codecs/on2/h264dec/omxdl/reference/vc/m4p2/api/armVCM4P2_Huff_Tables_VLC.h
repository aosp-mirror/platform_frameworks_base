/**
 * 
 * File Name:  armVCM4P2_Huff_Tables_VLC.h
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 *
 * File:        armVCM4P2_Huff_Tables.h
 * Description: Declares Tables used for Hufffman coding and decoding 
 *              in MP4P2 codec.
 *
 */
 
#ifndef _OMXHUFFTAB_H_
#define _OMXHUFFTAB_H_

extern const OMX_U8 armVCM4P2_IntraL0RunIdx[11];
extern const ARM_VLC32 armVCM4P2_IntraVlcL0[68];
extern const OMX_U8 armVCM4P2_IntraL1RunIdx[7];
extern const ARM_VLC32 armVCM4P2_IntraVlcL1[36];
extern const OMX_U8 armVCM4P2_IntraL0LMAX[15];
extern const OMX_U8 armVCM4P2_IntraL1LMAX[21];
extern const OMX_U8 armVCM4P2_IntraL0RMAX[27];
extern const OMX_U8 armVCM4P2_IntraL1RMAX[8];
extern const OMX_U8 armVCM4P2_InterL0RunIdx[12];
extern const ARM_VLC32 armVCM4P2_InterVlcL0[59];
extern const OMX_U8 armVCM4P2_InterL1RunIdx[3];
extern const ARM_VLC32 armVCM4P2_InterVlcL1[45];
extern const OMX_U8 armVCM4P2_InterL0LMAX[27];
extern const OMX_U8 armVCM4P2_InterL1LMAX[41];
extern const OMX_U8 armVCM4P2_InterL0RMAX[12];
extern const OMX_U8 armVCM4P2_InterL1RMAX[3];
extern const ARM_VLC32 armVCM4P2_aIntraDCLumaIndex[14];
extern const ARM_VLC32 armVCM4P2_aIntraDCChromaIndex[14];
extern const ARM_VLC32 armVCM4P2_aVlcMVD[66];

#endif /* _OMXHUFFTAB_H_ */
