/**
 * 
 * File Name:  armVCM4P2_Huff_Tables_VLC.h
 * OpenMAX DL: v1.0.2
 * Revision:   12290
 * Date:       Wednesday, April 9, 2008
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


extern const OMX_U16 armVCM4P2_IntraVlcL0L1[200];


extern const OMX_U16 armVCM4P2_InterVlcL0L1[200];

extern const OMX_U16 armVCM4P2_aIntraDCLumaChromaIndex[64];
//extern const OMX_U16 armVCM4P2_aIntraDCChromaIndex[32];
extern const OMX_U16 armVCM4P2_aVlcMVD[124];

extern const OMX_U8 armVCM4P2_InterL0L1LMAX[73];
extern const OMX_U8 armVCM4P2_InterL0L1RMAX[35];
extern const OMX_U8 armVCM4P2_IntraL0L1LMAX[53];
extern const OMX_U8 armVCM4P2_IntraL0L1RMAX[40]

#endif /* _OMXHUFFTAB_H_ */
