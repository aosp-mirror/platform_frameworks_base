/**
 * File: omxVC.h
 * Brief: OpenMAX DL v1.0.2 - Video Coding library
 *
 * Copyright © 2005-2008 The Khronos Group Inc. All Rights Reserved. 
 *
 * These materials are protected by copyright laws and contain material 
 * proprietary to the Khronos Group, Inc.  You may use these materials 
 * for implementing Khronos specifications, without altering or removing 
 * any trademark, copyright or other notice from the specification.
 * 
 * Khronos Group makes no, and expressly disclaims any, representations 
 * or warranties, express or implied, regarding these materials, including, 
 * without limitation, any implied warranties of merchantability or fitness 
 * for a particular purpose or non-infringement of any intellectual property. 
 * Khronos Group makes no, and expressly disclaims any, warranties, express 
 * or implied, regarding the correctness, accuracy, completeness, timeliness, 
 * and reliability of these materials. 
 *
 * Under no circumstances will the Khronos Group, or any of its Promoters, 
 * Contributors or Members or their respective partners, officers, directors, 
 * employees, agents or representatives be liable for any damages, whether 
 * direct, indirect, special or consequential damages for lost revenues, 
 * lost profits, or otherwise, arising from or in connection with these 
 * materials.
 * 
 * Khronos and OpenMAX are trademarks of the Khronos Group Inc. 
 *
 */

/* *****************************************************************************************/

#ifndef _OMXVC_H_
#define _OMXVC_H_

#include "omxtypes.h"

#ifdef __cplusplus
extern "C" {
#endif


/* 6.1.1.1 Motion Vectors  */
/* In omxVC, motion vectors are represented as follows:  */

typedef struct {
    OMX_S16 dx;
    OMX_S16 dy;
} OMXVCMotionVector;



/**
 * Function:  omxVCCOMM_Average_8x   (6.1.3.1.1)
 *
 * Description:
 * This function calculates the average of two 8x4, 8x8, or 8x16 blocks.  The 
 * result is rounded according to (a+b+1)/2.  The block average function can 
 * be used in conjunction with half-pixel interpolation to obtain quarter 
 * pixel motion estimates, as described in [ISO14496-10], subclause 8.4.2.2.1. 
 *
 * Input Arguments:
 *   
 *   pPred0     - Pointer to the top-left corner of reference block 0 
 *   pPred1     - Pointer to the top-left corner of reference block 1 
 *   iPredStep0 - Step of reference block 0 
 *   iPredStep1 - Step of reference block 1 
 *   iDstStep   - Step of the destination buffer. 
 *   iHeight    - Height of the blocks 
 *
 * Output Arguments:
 *   
 *   pDstPred - Pointer to the destination buffer. 8-byte aligned. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned under any of the following 
 *              conditions: 
 *    -   one or more of the following pointers is NULL: pPred0, pPred1, or 
 *              pDstPred. 
 *    -   pDstPred is not aligned on an 8-byte boundary. 
 *    -   iPredStep0 <= 0 or iPredStep0 is not a multiple of 8. 
 *    -   iPredStep1 <= 0 or iPredStep1 is not a multiple of 8. 
 *    -   iDstStep   <= 0 or iDstStep is not a multiple of 8. 
 *    -   iHeight is not 4, 8, or 16. 
 *
 */
OMXResult omxVCCOMM_Average_8x (
    const OMX_U8 *pPred0,
    const OMX_U8 *pPred1,
    OMX_U32 iPredStep0,
    OMX_U32 iPredStep1,
    OMX_U8 *pDstPred,
    OMX_U32 iDstStep,
    OMX_U32 iHeight
);



/**
 * Function:  omxVCCOMM_Average_16x   (6.1.3.1.2)
 *
 * Description:
 * This function calculates the average of two 16x16 or 16x8 blocks.  The 
 * result is rounded according to (a+b+1)/2.  The block average function can 
 * be used in conjunction with half-pixel interpolation to obtain quarter 
 * pixel motion estimates, as described in [ISO14496-10], subclause 8.4.2.2.1. 
 *
 * Input Arguments:
 *   
 *   pPred0 - Pointer to the top-left corner of reference block 0 
 *   pPred1 - Pointer to the top-left corner of reference block 1 
 *   iPredStep0 - Step of reference block 0 
 *   iPredStep1 - Step of reference block 1 
 *   iDstStep - Step of the destination buffer 
 *   iHeight - Height of the blocks 
 *
 * Output Arguments:
 *   
 *   pDstPred - Pointer to the destination buffer. 16-byte aligned. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned under any of the following 
 *              conditions: 
 *    -   one or more of the following pointers is NULL: pPred0, pPred1, or 
 *              pDstPred. 
 *    -   pDstPred is not aligned on a 16-byte boundary. 
 *    -   iPredStep0 <= 0 or iPredStep0 is not a multiple of 16. 
 *    -   iPredStep1 <= 0 or iPredStep1 is not a multiple of 16. 
 *    -   iDstStep <= 0 or iDstStep is not a multiple of 16. 
 *    -   iHeight is not 8 or 16. 
 *
 */
OMXResult omxVCCOMM_Average_16x (
    const OMX_U8 *pPred0,
    const OMX_U8 *pPred1,
    OMX_U32 iPredStep0,
    OMX_U32 iPredStep1,
    OMX_U8 *pDstPred,
    OMX_U32 iDstStep,
    OMX_U32 iHeight
);



/**
 * Function:  omxVCCOMM_ExpandFrame_I   (6.1.3.2.1)
 *
 * Description:
 * This function expands a reconstructed frame in-place.  The unexpanded 
 * source frame should be stored in a plane buffer with sufficient space 
 * pre-allocated for edge expansion, and the input frame should be located in 
 * the plane buffer center.  This function executes the pixel expansion by 
 * replicating source frame edge pixel intensities in the empty pixel 
 * locations (expansion region) between the source frame edge and the plane 
 * buffer edge.  The width/height of the expansion regions on the 
 * horizontal/vertical edges is controlled by the parameter iExpandPels. 
 *
 * Input Arguments:
 *   
 *   pSrcDstPlane - pointer to the top-left corner of the frame to be 
 *            expanded; must be aligned on an 8-byte boundary. 
 *   iFrameWidth - frame width; must be a multiple of 8. 
 *   iFrameHeight -frame height; must be a multiple of 8. 
 *   iExpandPels - number of pixels to be expanded in the horizontal and 
 *            vertical directions; must be a multiple of 8. 
 *   iPlaneStep - distance, in bytes, between the start of consecutive lines 
 *            in the plane buffer; must be larger than or equal to 
 *            (iFrameWidth + 2 * iExpandPels). 
 *
 * Output Arguments:
 *   
 *   pSrcDstPlane -Pointer to the top-left corner of the frame (NOT the 
 *            top-left corner of the plane); must be aligned on an 8-byte 
 *            boundary. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned under any of the following 
 *              conditions: 
 *    -    pSrcDstPlane is NULL. 
 *    -    pSrcDstPlane is not aligned on an 8-byte boundary. 
 *    -    one of the following parameters is either equal to zero or is a 
 *              non-multiple of 8: iFrameHeight, iFrameWidth, iPlaneStep, or 
 *              iExpandPels. 
 *    -    iPlaneStep < (iFrameWidth + 2 * iExpandPels). 
 *
 */
OMXResult omxVCCOMM_ExpandFrame_I (
    OMX_U8 *pSrcDstPlane,
    OMX_U32 iFrameWidth,
    OMX_U32 iFrameHeight,
    OMX_U32 iExpandPels,
    OMX_U32 iPlaneStep
);



/**
 * Function:  omxVCCOMM_Copy8x8   (6.1.3.3.1)
 *
 * Description:
 * Copies the reference 8x8 block to the current block. 
 *
 * Input Arguments:
 *   
 *   pSrc - pointer to the reference block in the source frame; must be 
 *            aligned on an 8-byte boundary. 
 *   step - distance between the starts of consecutive lines in the reference 
 *            frame, in bytes; must be a multiple of 8 and must be larger than 
 *            or equal to 8. 
 *
 * Output Arguments:
 *   
 *   pDst - pointer to the destination block; must be aligned on an 8-byte 
 *            boundary. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned under any of the following 
 *              conditions: 
 *    -   one or more of the following pointers is NULL: pSrc, pDst 
 *    -   one or more of the following pointers is not aligned on an 8-byte 
 *              boundary: pSrc, pDst 
 *    -    step <8 or step is not a multiple of 8. 
 *
 */
OMXResult omxVCCOMM_Copy8x8 (
    const OMX_U8 *pSrc,
    OMX_U8 *pDst,
    OMX_INT step
);



/**
 * Function:  omxVCCOMM_Copy16x16   (6.1.3.3.2)
 *
 * Description:
 * Copies the reference 16x16 macroblock to the current macroblock. 
 *
 * Input Arguments:
 *   
 *   pSrc - pointer to the reference macroblock in the source frame; must be 
 *            aligned on a 16-byte boundary. 
 *   step - distance between the starts of consecutive lines in the reference 
 *            frame, in bytes; must be a multiple of 16 and must be larger 
 *            than or equal to 16. 
 *
 * Output Arguments:
 *   
 *   pDst - pointer to the destination macroblock; must be aligned on a 
 *            16-byte boundary. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned under any of the following 
 *              conditions: 
 *    -   one or more of the following pointers is NULL: pSrc, pDst 
 *    -   one or more of the following pointers is not aligned on a 16-byte 
 *              boundary: pSrc, pDst 
 *    -    step <16 or step is not a multiple of 16. 
 *
 */
OMXResult omxVCCOMM_Copy16x16 (
    const OMX_U8 *pSrc,
    OMX_U8 *pDst,
    OMX_INT step
);



/**
 * Function:  omxVCCOMM_ComputeTextureErrorBlock_SAD   (6.1.4.1.1)
 *
 * Description:
 * Computes texture error of the block; also returns SAD. 
 *
 * Input Arguments:
 *   
 *   pSrc - pointer to the source plane; must be aligned on an 8-byte 
 *            boundary. 
 *   srcStep - step of the source plane 
 *   pSrcRef - pointer to the reference buffer, an 8x8 block; must be aligned 
 *            on an 8-byte boundary. 
 *
 * Output Arguments:
 *   
 *   pDst - pointer to the destination buffer, an 8x8 block; must be aligned 
 *            on an 8-byte boundary. 
 *   pDstSAD - pointer to the Sum of Absolute Differences (SAD) value 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments 
 *    -    At least one of the following 
 *         pointers is NULL: pSrc, pSrcRef, pDst and pDstSAD. 
 *    -    pSrc is not 8-byte aligned. 
 *    -    SrcStep <= 0 or srcStep is not a multiple of 8. 
 *    -    pSrcRef is not 8-byte aligned. 
 *    -    pDst is not 8-byte aligned. 
 *
 */
OMXResult omxVCCOMM_ComputeTextureErrorBlock_SAD (
    const OMX_U8 *pSrc,
    OMX_INT srcStep,
    const OMX_U8 *pSrcRef,
    OMX_S16 *pDst,
    OMX_INT *pDstSAD
);



/**
 * Function:  omxVCCOMM_ComputeTextureErrorBlock   (6.1.4.1.2)
 *
 * Description:
 * Computes the texture error of the block. 
 *
 * Input Arguments:
 *   
 *   pSrc - pointer to the source plane. This should be aligned on an 8-byte 
 *            boundary. 
 *   srcStep - step of the source plane 
 *   pSrcRef - pointer to the reference buffer, an 8x8 block. This should be 
 *            aligned on an 8-byte boundary. 
 *
 * Output Arguments:
 *   
 *   pDst - pointer to the destination buffer, an 8x8 block. This should be 
 *            aligned on an 8-byte boundary. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments:
 *    -    At least one of the following pointers is NULL: 
 *         pSrc, pSrcRef, pDst. 
 *    -    pSrc is not 8-byte aligned. 
 *    -    SrcStep <= 0 or srcStep is not a multiple of 8. 
 *    -    pSrcRef is not 8-byte aligned. 
 *    -    pDst is not 8-byte aligned 
 *
 */
OMXResult omxVCCOMM_ComputeTextureErrorBlock (
    const OMX_U8 *pSrc,
    OMX_INT srcStep,
    const OMX_U8 *pSrcRef,
    OMX_S16 *pDst
);



/**
 * Function:  omxVCCOMM_LimitMVToRect   (6.1.4.1.3)
 *
 * Description:
 * Limits the motion vector associated with the current block/macroblock to 
 * prevent the motion compensated block/macroblock from moving outside a 
 * bounding rectangle as shown in Figure 6-1. 
 *
 * Input Arguments:
 *   
 *   pSrcMV - pointer to the motion vector associated with the current block 
 *            or macroblock 
 *   pRectVOPRef - pointer to the bounding rectangle 
 *   Xcoord, Ycoord  - coordinates of the current block or macroblock 
 *   size - size of the current block or macroblock; must be equal to 8 or 
 *            16. 
 *
 * Output Arguments:
 *   
 *   pDstMV - pointer to the limited motion vector 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments.  Returned if one or more of the 
 *              following conditions is true: 
 *    -    at least one of the following pointers is NULL: 
 *         pSrcMV, pDstMV, or pRectVOPRef. 
 *    -    size is not equal to either 8 or 16. 
 *    -    the width or height of the bounding rectangle is less than 
 *         twice the block size.
 */
OMXResult omxVCCOMM_LimitMVToRect (
    const OMXVCMotionVector *pSrcMV,
    OMXVCMotionVector *pDstMV,
    const OMXRect *pRectVOPRef,
    OMX_INT Xcoord,
    OMX_INT Ycoord,
    OMX_INT size
);



/**
 * Function:  omxVCCOMM_SAD_16x   (6.1.4.1.4)
 *
 * Description:
 * This function calculates the SAD for 16x16 and 16x8 blocks. 
 *
 * Input Arguments:
 *   
 *   pSrcOrg - Pointer to the original block; must be aligned on a 16-byte 
 *             boundary. 
 *   iStepOrg - Step of the original block buffer 
 *   pSrcRef  - Pointer to the reference block 
 *   iStepRef - Step of the reference block buffer 
 *   iHeight  - Height of the block 
 *
 * Output Arguments:
 *   
 *   pDstSAD - Pointer of result SAD 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments.  Returned if one or more of the 
 *              following conditions is true: 
 *    -    at least one of the following pointers is NULL: 
 *         pSrcOrg, pDstSAD, or pSrcRef 
 *    -    pSrcOrg is not 16-byte aligned. 
 *    -    iStepOrg  <= 0 or iStepOrg is not a multiple of 16 
 *    -    iStepRef <= 0 or iStepRef is not a multiple of 16 
 *    -    iHeight is not 8 or 16 
 *
 */
OMXResult omxVCCOMM_SAD_16x (
    const OMX_U8 *pSrcOrg,
    OMX_U32 iStepOrg,
    const OMX_U8 *pSrcRef,
    OMX_U32 iStepRef,
    OMX_S32 *pDstSAD,
    OMX_U32 iHeight
);



/**
 * Function:  omxVCCOMM_SAD_8x   (6.1.4.1.5)
 *
 * Description:
 * This function calculates the SAD for 8x16, 8x8, 8x4 blocks. 
 *
 * Input Arguments:
 *   
 *   pSrcOrg  - Pointer to the original block; must be aligned on a 8-byte 
 *              boundary. 
 *   iStepOrg - Step of the original block buffer 
 *   pSrcRef  - Pointer to the reference block 
 *   iStepRef - Step of the reference block buffer 
 *   iHeight  - Height of the block 
 *
 * Output Arguments:
 *   
 *   pDstSAD -Pointer of result SAD 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments.  Returned if one or more of the 
 *              following conditions is true: 
 *    -    at least one of the following pointers is NULL: 
 *         pSrcOrg, pDstSAD, or pSrcRef 
 *    -    pSrcOrg is not 8-byte aligned. 
 *    -    iStepOrg  <= 0 or iStepOrg is not a multiple of 8 
 *    -    iStepRef <= 0 or iStepRef is not a multiple of 8 
 *    -    iHeight is not 4, 8 or 16 
 *
 */
OMXResult omxVCCOMM_SAD_8x (
    const OMX_U8 *pSrcOrg,
    OMX_U32 iStepOrg,
    const OMX_U8 *pSrcRef,
    OMX_U32 iStepRef,
    OMX_S32*pDstSAD,
    OMX_U32 iHeight
);



/* 6.2.1.1 Direction  */
/* The direction enumerator is used with functions that perform AC/DC prediction and zig-zag scan.  */

enum {
    OMX_VC_NONE       = 0,
    OMX_VC_HORIZONTAL = 1,
    OMX_VC_VERTICAL   = 2 
};



/* 6.2.1.2 Bilinear Interpolation  */
/* The bilinear interpolation enumerator is used with motion estimation, motion compensation, and reconstruction functions.  */

enum {
    OMX_VC_INTEGER_PIXEL = 0, /* case a */
    OMX_VC_HALF_PIXEL_X  = 1, /* case b */
    OMX_VC_HALF_PIXEL_Y  = 2, /* case c */
    OMX_VC_HALF_PIXEL_XY = 3  /* case d */ 
};



/* 6.2.1.3 Neighboring Macroblock Availability  */
/* Neighboring macroblock availability is indicated using the following flags:   */

enum {
    OMX_VC_UPPER = 1,        /** above macroblock is available */
    OMX_VC_LEFT = 2,         /** left macroblock is available */
    OMX_VC_CENTER = 4,
    OMX_VC_RIGHT = 8,
    OMX_VC_LOWER = 16,
    OMX_VC_UPPER_LEFT = 32,  /** above-left macroblock is available */
    OMX_VC_UPPER_RIGHT = 64, /** above-right macroblock is available */
    OMX_VC_LOWER_LEFT = 128,
    OMX_VC_LOWER_RIGHT = 256 
};



/* 6.2.1.4 Video Components  */
/* A data type that enumerates video components is defined as follows:  */

typedef enum {
    OMX_VC_LUMINANCE,    /** Luminance component */
    OMX_VC_CHROMINANCE   /** chrominance component */ 
} OMXVCM4P2VideoComponent;



/* 6.2.1.5 MacroblockTypes  */
/* A data type that enumerates macroblock types is defined as follows:  */

typedef enum {
    OMX_VC_INTER     = 0, /** P picture or P-VOP */
    OMX_VC_INTER_Q   = 1, /** P picture or P-VOP */
    OMX_VC_INTER4V   = 2, /** P picture or P-VOP */
    OMX_VC_INTRA     = 3, /** I and P picture, I- and P-VOP */
    OMX_VC_INTRA_Q   = 4, /** I and P picture, I- and P-VOP */
    OMX_VC_INTER4V_Q = 5  /** P picture or P-VOP (H.263)*/
} OMXVCM4P2MacroblockType;



/* 6.2.1.6 Coordinates  */
/* Coordinates are represented as follows:  */

typedef struct {
    OMX_INT x;
    OMX_INT y;
} OMXVCM4P2Coordinate;



/* 6.2.1.7 Motion Estimation Algorithms  */
/* A data type that enumerates motion estimation search methods is defined as follows:  */

typedef enum {
    OMX_VC_M4P2_FAST_SEARCH = 0,  /** Fast motion search */
    OMX_VC_M4P2_FULL_SEARCH = 1   /** Full motion search */ 
} OMXVCM4P2MEMode;



/* 6.2.1.8 Motion Estimation Parameters  */
/* A data structure containing control parameters for 
 * motion estimation functions is defined as follows:  
 */

typedef struct {
    OMX_INT searchEnable8x8;     /** enables 8x8 search */
    OMX_INT halfPelSearchEnable; /** enables half-pel resolution */
    OMX_INT searchRange;         /** search range */
    OMX_INT rndVal;              /** rounding control; 0-disabled, 1-enabled*/
} OMXVCM4P2MEParams;



/* 6.2.1.9 Macroblock Information   */
/* A data structure containing macroblock parameters for 
 * motion estimation functions is defined as follows:  
 */

typedef struct {
    OMX_S32 sliceId;                 /* slice number */
    OMXVCM4P2MacroblockType mbType;  /* MB type: OMX_VC_INTRA, OMX_VC_INTER, or OMX_VC_INTER4 */
    OMX_S32 qp;                      /* quantization parameter*/
    OMX_U32 cbpy;                    /* CBP Luma */
    OMX_U32 cbpc;                    /* CBP Chroma */
    OMXVCMotionVector pMV0[2][2];    /* motion vector, represented using 1/2-pel units, 
                                      * pMV0[blocky][blockx] (blocky = 0~1, blockx =0~1) 
                                      */
    OMXVCMotionVector pMVPred[2][2]; /* motion vector prediction, represented using 1/2-pel units, 
                                      * pMVPred[blocky][blockx] (blocky = 0~1, blockx = 0~1) 
                                      */
    OMX_U8 pPredDir[2][2];           /* AC prediction direction: 
                                      *   OMX_VC_NONE, OMX_VC_VERTICAL, OMX_VC_HORIZONTAL 
                                      */
} OMXVCM4P2MBInfo, *OMXVCM4P2MBInfoPtr;



/**
 * Function:  omxVCM4P2_FindMVpred   (6.2.3.1.1)
 *
 * Description:
 * Predicts a motion vector for the current block using the procedure 
 * specified in [ISO14496-2], subclause 7.6.5.  The resulting predicted MV is 
 * returned in pDstMVPred. If the parameter pDstMVPredME if is not NULL then 
 * the set of three MV candidates used for prediction is also returned, 
 * otherwise pDstMVPredMEis NULL upon return. 
 *
 * Input Arguments:
 *   
 *   pSrcMVCurMB - pointer to the MV buffer associated with the current Y 
 *            macroblock; a value of NULL indicates unavailability. 
 *   pSrcCandMV1 - pointer to the MV buffer containing the 4 MVs associated 
 *            with the MB located to the left of the current MB; set to NULL 
 *            if there is no MB to the left. 
 *   pSrcCandMV2 - pointer to the MV buffer containing the 4 MVs associated 
 *            with the MB located above the current MB; set to NULL if there 
 *            is no MB located above the current MB. 
 *   pSrcCandMV3 - pointer to the MV buffer containing the 4 MVs associated 
 *            with the MB located to the right and above the current MB; set 
 *            to NULL if there is no MB located to the above-right. 
 *   iBlk - the index of block in the current macroblock 
 *   pDstMVPredME - MV candidate return buffer;  if set to NULL then 
 *            prediction candidate MVs are not returned and pDstMVPredME will 
 *            be NULL upon function return; if pDstMVPredME is non-NULL then it 
 *            must point to a buffer containing sufficient space for three 
 *            return MVs. 
 *
 * Output Arguments:
 *   
 *   pDstMVPred - pointer to the predicted motion vector 
 *   pDstMVPredME - if non-NULL upon input then pDstMVPredME  points upon 
 *            return to a buffer containing the three motion vector candidates 
 *            used for prediction as specified in [ISO14496-2], subclause 
 *            7.6.5, otherwise if NULL upon input then pDstMVPredME is NULL 
 *            upon output. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned under any of the following 
 *              conditions: 
 *    -    the pointer pDstMVPred is NULL 
 *    -    the parameter iBlk does not fall into the range 0 <= iBlk<=3 
 *
 */
OMXResult omxVCM4P2_FindMVpred (
    const OMXVCMotionVector *pSrcMVCurMB,
    const OMXVCMotionVector *pSrcCandMV1,
    const OMXVCMotionVector *pSrcCandMV2,
    const OMXVCMotionVector *pSrcCandMV3,
    OMXVCMotionVector *pDstMVPred,
    OMXVCMotionVector *pDstMVPredME,
    OMX_INT iBlk
);



/**
 * Function:  omxVCM4P2_IDCT8x8blk   (6.2.3.2.1)
 *
 * Description:
 * Computes a 2D inverse DCT for a single 8x8 block, as defined in 
 * [ISO14496-2]. 
 *
 * Input Arguments:
 *   
 *   pSrc - pointer to the start of the linearly arranged IDCT input buffer; 
 *            must be aligned on a 16-byte boundary.  According to 
 *            [ISO14496-2], the input coefficient values should lie within the 
 *            range [-2048, 2047]. 
 *
 * Output Arguments:
 *   
 *   pDst - pointer to the start of the linearly arranged IDCT output buffer; 
 *            must be aligned on a 16-byte boundary. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments:
 *    -    pSrc or pDst is NULL. 
 *    -    pSrc or pDst is not 16-byte aligned. 
 *
 */
OMXResult omxVCM4P2_IDCT8x8blk (
    const OMX_S16 *pSrc,
    OMX_S16 *pDst
);



/**
 * Function:  omxVCM4P2_MEGetBufSize   (6.2.4.1.1)
 *
 * Description:
 * Computes the size, in bytes, of the vendor-specific specification 
 * structure for the following motion estimation functions: 
 * BlockMatch_Integer_8x8, BlockMatch_Integer_16x16, and MotionEstimationMB. 
 *
 * Input Arguments:
 *   
 *   MEmode - motion estimation mode; available modes are defined by the 
 *            enumerated type OMXVCM4P2MEMode 
 *   pMEParams - motion estimation parameters 
 *
 * Output Arguments:
 *   
 *   pSize - pointer to the number of bytes required for the specification 
 *            structure 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - one or more of the following is true: 
 *    -    an invalid value was specified for the parameter MEmode 
 *    -    a negative or zero value was specified for the 
 *         parameter pMEParams->searchRange 
 *
 */
OMXResult omxVCM4P2_MEGetBufSize (
    OMXVCM4P2MEMode MEmode,
    const OMXVCM4P2MEParams *pMEParams,
    OMX_U32 *pSize
);



/**
 * Function:  omxVCM4P2_MEInit   (6.2.4.1.2)
 *
 * Description:
 * Initializes the vendor-specific specification structure required for the 
 * following motion estimation functions:  BlockMatch_Integer_8x8, 
 * BlockMatch_Integer_16x16, and MotionEstimationMB. Memory for the 
 * specification structure *pMESpec must be allocated prior to calling the 
 * function, and should be aligned on a 4-byte boundary.  Following 
 * initialization by this function, the vendor-specific structure *pMESpec 
 * should contain an implementation-specific representation of all motion 
 * estimation parameters received via the structure pMEParams, for example  
 * rndVal, searchRange, etc.  The number of bytes required for the 
 * specification structure can be determined using the function 
 * omxVCM4P2_MEGetBufSize. 
 *
 * Input Arguments:
 *   
 *   MEmode - motion estimation mode; available modes are defined by the 
 *            enumerated type OMXVCM4P2MEMode 
 *   pMEParams - motion estimation parameters 
 *   pMESpec - pointer to the uninitialized ME specification structure 
 *
 * Output Arguments:
 *   
 *   pMESpec - pointer to the initialized ME specification structure 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - one or more of the following is true: 
 *    -    an invalid value was specified for the parameter MEmode 
 *    -    a negative or zero value was specified for the 
 *         parameter pMEParams->searchRange 
 *
 */
OMXResult omxVCM4P2_MEInit (
    OMXVCM4P2MEMode MEmode,
    const OMXVCM4P2MEParams*pMEParams,
    void *pMESpec
);



/**
 * Function:  omxVCM4P2_BlockMatch_Integer_16x16   (6.2.4.2.1)
 *
 * Description:
 * Performs a 16x16 block search; estimates motion vector and associated 
 * minimum SAD. Both the input and output motion vectors are represented using 
 * half-pixel units, and therefore a shift left or right by 1 bit may be 
 * required, respectively, to match the input or output MVs with other 
 * functions that either generate output MVs or expect input MVs represented 
 * using integer pixel units. 
 *
 * Input Arguments:
 *   
 *   pSrcRefBuf - pointer to the reference Y plane; points to the reference 
 *            MB that corresponds to the location of the current macroblock in 
 *            the current plane. 
 *   refWidth - width of the reference plane 
 *   pRefRect - pointer to the valid reference plane rectangle; coordinates 
 *            are specified relative to the image origin.  Rectangle 
 *            boundaries may extend beyond image boundaries if the image has 
 *            been padded.  For example, if padding extends 4 pixels beyond 
 *            frame border, then the value for the left border could be set to 
 *            -4. 
 *   pSrcCurrBuf - pointer to the current block in the current macroblock 
 *            buffer extracted from the original plane (linear array, 256 
 *            entries); must be aligned on a 16-byte boundary.  The number of 
 *            bytes between lines (step) is 16. 
 *   pCurrPointPos - position of the current macroblock in the current plane 
 *   pSrcPreMV - pointer to predicted motion vector; NULL indicates no 
 *            predicted MV 
 *   pSrcPreSAD - pointer to SAD associated with the predicted MV (referenced 
 *            by pSrcPreMV); may be set to NULL if unavailable. 
 *   pMESpec - vendor-specific motion estimation specification structure; 
 *            must have been allocated and then initialized using 
 *            omxVCM4P2_MEInit prior to calling the block matching function. 
 *
 * Output Arguments:
 *   
 *   pDstMV - pointer to estimated MV 
 *   pDstSAD - pointer to minimum SAD 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments.  Returned if one of the following 
 *              conditions is true: 
 *    -    at least one of the following pointers is NULL: pSrcRefBuf, 
 *              pRefRect, pSrcCurrBuff, pCurrPointPos, pDstMV, pDstSAD or 
 *              pMESpec, or 
 *    -    pSrcCurrBuf is not 16-byte aligned 
 *
 */
OMXResult omxVCM4P2_BlockMatch_Integer_16x16 (
    const OMX_U8 *pSrcRefBuf,
    OMX_INT refWidth,
    const OMXRect *pRefRect,
    const OMX_U8 *pSrcCurrBuf,
    const OMXVCM4P2Coordinate *pCurrPointPos,
    const OMXVCMotionVector*pSrcPreMV,
    const OMX_INT *pSrcPreSAD,
    void *pMESpec,
    OMXVCMotionVector*pDstMV,
    OMX_INT *pDstSAD
);



/**
 * Function:  omxVCM4P2_BlockMatch_Integer_8x8   (6.2.4.2.2)
 *
 * Description:
 * Performs an 8x8 block search; estimates motion vector and associated 
 * minimum SAD.  Both the input and output motion vectors are represented 
 * using half-pixel units, and therefore a shift left or right by 1 bit may be 
 * required, respectively, to match the input or output MVs with other 
 * functions that either generate output MVs or expect input MVs represented 
 * using integer pixel units. 
 *
 * Input Arguments:
 *   
 *   pSrcRefBuf - pointer to the reference Y plane; points to the reference 
 *            block that corresponds to the location of the current 8x8 block 
 *            in the current plane. 
 *   refWidth - width of the reference plane 
 *   pRefRect - pointer to the valid reference plane rectangle; coordinates 
 *            are specified relative to the image origin.  Rectangle 
 *            boundaries may extend beyond image boundaries if the image has 
 *            been padded. 
 *   pSrcCurrBuf - pointer to the current block in the current macroblock 
 *            buffer extracted from the original plane (linear array, 128 
 *            entries); must be aligned on an 8-byte boundary.  The number of 
 *            bytes between lines (step) is 16 bytes. 
 *   pCurrPointPos - position of the current block in the current plane 
 *   pSrcPreMV - pointer to predicted motion vector; NULL indicates no 
 *            predicted MV 
 *   pSrcPreSAD - pointer to SAD associated with the predicted MV (referenced 
 *            by pSrcPreMV); may be set to NULL if unavailable. 
 *   pMESpec - vendor-specific motion estimation specification structure; 
 *            must have been allocated and then initialized using 
 *            omxVCM4P2_MEInit prior to calling the block matching function. 
 *
 * Output Arguments:
 *   
 *   pDstMV - pointer to estimated MV 
 *   pDstSAD - pointer to minimum SAD 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments.  Returned if one of the following 
 *              conditions is true: 
 *    -    at least one of the following pointers is NULL: pSrcRefBuf, 
 *              pRefRect, pSrcCurrBuff, pCurrPointPos, pDstMV, pDstSAD or 
 *              pMESpec, or 
 *    -    pSrcCurrBuf is not 8-byte aligned 
 *
 */
OMXResult omxVCM4P2_BlockMatch_Integer_8x8 (
    const OMX_U8 *pSrcRefBuf,
    OMX_INT refWidth,
    const OMXRect *pRefRect,
    const OMX_U8 *pSrcCurrBuf,
    const OMXVCM4P2Coordinate *pCurrPointPos,
    const OMXVCMotionVector *pSrcPreMV,
    const OMX_INT *pSrcPreSAD,
    void *pMESpec,
    OMXVCMotionVector *pDstMV,
    OMX_INT *pDstSAD
);



/**
 * Function:  omxVCM4P2_BlockMatch_Half_16x16   (6.2.4.2.3)
 *
 * Description:
 * Performs a 16x16 block match with half-pixel resolution.  Returns the 
 * estimated motion vector and associated minimum SAD.  This function 
 * estimates the half-pixel motion vector by interpolating the integer 
 * resolution motion vector referenced by the input parameter pSrcDstMV, i.e., 
 * the initial integer MV is generated externally.  The input parameters 
 * pSrcRefBuf and pSearchPointRefPos should be shifted by the winning MV of 
 * 16x16 integer search prior to calling BlockMatch_Half_16x16. The function 
 * BlockMatch_Integer_16x16 may be used for integer motion estimation. 
 *
 * Input Arguments:
 *   
 *   pSrcRefBuf - pointer to the reference Y plane; points to the reference 
 *            macroblock that corresponds to the location of the current 
 *            macroblock in the current plane. 
 *   refWidth - width of the reference plane 
 *   pRefRect - reference plane valid region rectangle 
 *   pSrcCurrBuf - pointer to the current block in the current macroblock 
 *            buffer extracted from the original plane (linear array, 256 
 *            entries); must be aligned on a 16-byte boundary.  The number of 
 *            bytes between lines (step) is 16. 
 *   pSearchPointRefPos - position of the starting point for half pixel 
 *            search (specified in terms of integer pixel units) in the 
 *            reference plane, i.e., the reference position pointed to by the 
 *            predicted motion vector. 
 *   rndVal - rounding control parameter: 0 - disabled; 1 - enabled. 
 *   pSrcDstMV - pointer to the initial MV estimate; typically generated 
 *            during a prior 16X16 integer search; specified in terms of 
 *            half-pixel units. 
 *
 * Output Arguments:
 *   
 *   pSrcDstMV - pointer to estimated MV 
 *   pDstSAD - pointer to minimum SAD 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments.  Returned if one of the following 
 *              conditions is true: 
 *    -    at least one of the following pointers is NULL: pSrcRefBuf, 
 *         pRefRect, pSrcCurrBuff, pSearchPointRefPos, pSrcDstMV.
 *    -    pSrcCurrBuf is not 16-byte aligned, or 
 *
 */
OMXResult omxVCM4P2_BlockMatch_Half_16x16 (
    const OMX_U8 *pSrcRefBuf,
    OMX_INT refWidth,
    const OMXRect *pRefRect,
    const OMX_U8 *pSrcCurrBuf,
    const OMXVCM4P2Coordinate *pSearchPointRefPos,
    OMX_INT rndVal,
    OMXVCMotionVector *pSrcDstMV,
    OMX_INT *pDstSAD
);



/**
 * Function:  omxVCM4P2_BlockMatch_Half_8x8   (6.2.4.2.4)
 *
 * Description:
 * Performs an 8x8 block match with half-pixel resolution. Returns the 
 * estimated motion vector and associated minimum SAD.  This function 
 * estimates the half-pixel motion vector by interpolating the integer 
 * resolution motion vector referenced by the input parameter pSrcDstMV, i.e., 
 * the initial integer MV is generated externally.  The input parameters 
 * pSrcRefBuf and pSearchPointRefPos should be shifted by the winning MV of 
 * 8x8 integer search prior to calling BlockMatch_Half_8x8. The function 
 * BlockMatch_Integer_8x8 may be used for integer motion estimation. 
 *
 * Input Arguments:
 *   
 *   pSrcRefBuf - pointer to the reference Y plane; points to the reference 
 *            block that corresponds to the location of the current 8x8 block 
 *            in the current plane. 
 *   refWidth - width of the reference plane 
 *   pRefRect - reference plane valid region rectangle 
 *   pSrcCurrBuf - pointer to the current block in the current macroblock 
 *            buffer extracted from the original plane (linear array, 128 
 *            entries); must be aligned on a 8-byte boundary.  The number of 
 *            bytes between lines (step) is 16. 
 *   pSearchPointRefPos - position of the starting point for half pixel 
 *            search (specified in terms of integer pixel units) in the 
 *            reference plane. 
 *   rndVal - rounding control parameter: 0 - disabled; 1 - enabled. 
 *   pSrcDstMV - pointer to the initial MV estimate; typically generated 
 *            during a prior 8x8 integer search, specified in terms of 
 *            half-pixel units. 
 *
 * Output Arguments:
 *   
 *   pSrcDstMV - pointer to estimated MV 
 *   pDstSAD - pointer to minimum SAD 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments.  Returned if one of the following 
 *              conditions is true: 
 *    -    at least one of the following pointers is NULL: 
 *         pSrcRefBuf, pRefRect, pSrcCurrBuff, pSearchPointRefPos, pSrcDstMV
 *    -    pSrcCurrBuf is not 8-byte aligned 
 *
 */
OMXResult omxVCM4P2_BlockMatch_Half_8x8 (
    const OMX_U8 *pSrcRefBuf,
    OMX_INT refWidth,
    const OMXRect *pRefRect,
    const OMX_U8 *pSrcCurrBuf,
    const OMXVCM4P2Coordinate *pSearchPointRefPos,
    OMX_INT rndVal,
    OMXVCMotionVector *pSrcDstMV,
    OMX_INT *pDstSAD
);



/**
 * Function:  omxVCM4P2_MotionEstimationMB   (6.2.4.3.1)
 *
 * Description:
 * Performs motion search for a 16x16 macroblock.  Selects best motion search 
 * strategy from among inter-1MV, inter-4MV, and intra modes.  Supports 
 * integer and half pixel resolution. 
 *
 * Input Arguments:
 *   
 *   pSrcCurrBuf - pointer to the top-left corner of the current MB in the 
 *            original picture plane; must be aligned on a 16-byte boundary.  
 *            The function does not expect source data outside the region 
 *            bounded by the MB to be available; for example it is not 
 *            necessary for the caller to guarantee the availability of 
 *            pSrcCurrBuf[-SrcCurrStep], i.e., the row of pixels above the MB 
 *            to be processed. 
 *   srcCurrStep - width of the original picture plane, in terms of full 
 *            pixels; must be a multiple of 16. 
 *   pSrcRefBuf - pointer to the reference Y plane; points to the reference 
 *            plane location corresponding to the location of the current 
 *            macroblock in the current plane; must be aligned on a 16-byte 
 *            boundary. 
 *   srcRefStep - width of the reference picture plane, in terms of full 
 *            pixels; must be a multiple of 16. 
 *   pRefRect - reference plane valid region rectangle, specified relative to 
 *            the image origin 
 *   pCurrPointPos - position of the current macroblock in the current plane 
 *   pMESpec - pointer to the vendor-specific motion estimation specification 
 *            structure; must be allocated and then initialized using 
 *            omxVCM4P2_MEInit prior to calling this function. 
 *   pMBInfo - array, of dimension four, containing pointers to information 
 *            associated with four nearby MBs: 
 *            -   pMBInfo[0] - pointer to left MB information 
 *            -   pMBInfo[1] - pointer to top MB information 
 *            -   pMBInfo[2] - pointer to top-left MB information 
 *            -   pMBInfo[3] - pointer to top-right MB information 
 *            Any pointer in the array may be set equal to NULL if the 
 *            corresponding MB doesn't exist.  For each MB, the following structure 
 *            members are used:    
 *            -   mbType - macroblock type, either OMX_VC_INTRA, OMX_VC_INTER, or 
 *                OMX_VC_INTER4V 
 *            -   pMV0[2][2] - estimated motion vectors; represented 
 *                in 1/2 pixel units 
 *            -   sliceID - number of the slice to which the MB belongs 
 *   pSrcDstMBCurr - pointer to information structure for the current MB.  
 *            The following entries should be set prior to calling the 
 *            function: sliceID - the number of the slice the to which the 
 *            current MB belongs.  The structure elements cbpy and cbpc are 
 *            ignored. 
 *
 * Output Arguments:
 *   
 *   pSrcDstMBCurr - pointer to updated information structure for the current 
 *            MB after MB-level motion estimation has been completed.  The 
 *            following structure members are updated by the ME function:   
 *              -  mbType - macroblock type: OMX_VC_INTRA, OMX_VC_INTER, or 
 *                 OMX_VC_INTER4V. 
 *              -  pMV0[2][2] - estimated motion vectors; represented in 
 *                 terms of 1/2 pel units. 
 *              -  pMVPred[2][2] - predicted motion vectors; represented 
 *                 in terms of 1/2 pel units. 
 *            The structure members cbpy and cbpc are not updated by the function. 
 *   pDstSAD - pointer to the minimum SAD for INTER1V, or sum of minimum SADs 
 *            for INTER4V 
 *   pDstBlockSAD - pointer to an array of SAD values for each of the four 
 *            8x8 luma blocks in the MB.  The block SADs are in scan order for 
 *            each MB. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments.  Returned if one or more of the 
 *              following conditions is true: 
 *    -    at least one of the following pointers is NULL: pSrcCurrBuf, 
 *              pSrcRefBuf, pRefRect, pCurrPointPos, pMBInter, pMBIntra, 
 *              pSrcDstMBCurr, or pDstSAD. 
 *
 */
OMXResult omxVCM4P2_MotionEstimationMB (
    const OMX_U8 *pSrcCurrBuf,
    OMX_S32 srcCurrStep,
    const OMX_U8 *pSrcRefBuf,
    OMX_S32 srcRefStep,
    const OMXRect*pRefRect,
    const OMXVCM4P2Coordinate *pCurrPointPos,
    void *pMESpec,
    const OMXVCM4P2MBInfoPtr *pMBInfo,
    OMXVCM4P2MBInfo *pSrcDstMBCurr,
    OMX_U16 *pDstSAD,
    OMX_U16 *pDstBlockSAD
);



/**
 * Function:  omxVCM4P2_DCT8x8blk   (6.2.4.4.1)
 *
 * Description:
 * Computes a 2D forward DCT for a single 8x8 block, as defined in 
 * [ISO14496-2]. 
 *
 * Input Arguments:
 *   
 *   pSrc - pointer to the start of the linearly arranged input buffer; must 
 *            be aligned on a 16-byte boundary.  Input values (pixel 
 *            intensities) are valid in the range [-255,255]. 
 *
 * Output Arguments:
 *   
 *   pDst - pointer to the start of the linearly arranged output buffer; must 
 *            be aligned on a 16-byte boundary. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments, returned if:
 *    -    pSrc or pDst is NULL. 
 *    -    pSrc or pDst is not 16-byte aligned. 
 *
 */
OMXResult omxVCM4P2_DCT8x8blk (
    const OMX_S16 *pSrc,
    OMX_S16 *pDst
);



/**
 * Function:  omxVCM4P2_QuantIntra_I   (6.2.4.4.2)
 *
 * Description:
 * Performs quantization on intra block coefficients. This function supports 
 * bits_per_pixel == 8. 
 *
 * Input Arguments:
 *   
 *   pSrcDst - pointer to the input intra block coefficients; must be aligned 
 *            on a 16-byte boundary. 
 *   QP - quantization parameter (quantizer_scale). 
 *   blockIndex - block index indicating the component type and position, 
 *            valid in the range 0 to 5, as defined in [ISO14496-2], subclause 
 *            6.1.3.8. 
 *   shortVideoHeader - binary flag indicating presence of 
 *            short_video_header; shortVideoHeader==1 selects linear intra DC 
 *            mode, and shortVideoHeader==0 selects non linear intra DC mode. 
 *
 * Output Arguments:
 *   
 *   pSrcDst - pointer to the output (quantized) interblock coefficients.  
 *            When shortVideoHeader==1, AC coefficients are saturated on the 
 *            interval [-127, 127], and DC coefficients are saturated on the 
 *            interval [1, 254].  When shortVideoHeader==0, AC coefficients 
 *            are saturated on the interval [-2047, 2047]. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments:
 *    -    pSrcDst is NULL. 
 *    -    blockIndex < 0 or blockIndex >= 10 
 *    -    QP <= 0 or QP >= 32. 
 *
 */
OMXResult omxVCM4P2_QuantIntra_I (
    OMX_S16 *pSrcDst,
    OMX_U8 QP,
    OMX_INT blockIndex,
    OMX_INT shortVideoHeader
);



/**
 * Function:  omxVCM4P2_QuantInter_I   (6.2.4.4.3)
 *
 * Description:
 * Performs quantization on an inter coefficient block; supports 
 * bits_per_pixel == 8. 
 *
 * Input Arguments:
 *   
 *   pSrcDst - pointer to the input inter block coefficients; must be aligned 
 *            on a 16-byte boundary. 
 *   QP - quantization parameter (quantizer_scale) 
 *   shortVideoHeader - binary flag indicating presence of short_video_header; 
 *            shortVideoHeader==1 selects linear intra DC mode, and 
 *            shortVideoHeader==0 selects non linear intra DC mode. 
 *
 * Output Arguments:
 *   
 *   pSrcDst - pointer to the output (quantized) interblock coefficients.  
 *            When shortVideoHeader==1, AC coefficients are saturated on the 
 *            interval [-127, 127], and DC coefficients are saturated on the 
 *            interval [1, 254].  When shortVideoHeader==0, AC coefficients 
 *            are saturated on the interval [-2047, 2047]. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments:
 *    -    pSrcDst is NULL. 
 *    -    QP <= 0 or QP >= 32. 
 *
 */
OMXResult omxVCM4P2_QuantInter_I (
    OMX_S16 *pSrcDst,
    OMX_U8 QP,
    OMX_INT shortVideoHeader
);



/**
 * Function:  omxVCM4P2_TransRecBlockCoef_intra   (6.2.4.4.4)
 *
 * Description:
 * Quantizes the DCT coefficients, implements intra block AC/DC coefficient 
 * prediction, and reconstructs the current intra block texture for prediction 
 * on the next frame.  Quantized row and column coefficients are returned in 
 * the updated coefficient buffers. 
 *
 * Input Arguments:
 *   
 *   pSrc - pointer to the pixels of current intra block; must be aligned on 
 *            an 8-byte boundary. 
 *   pPredBufRow - pointer to the coefficient row buffer containing 
 *            ((num_mb_per_row * 2 + 1) * 8) elements of type OMX_S16. 
 *            Coefficients are organized into blocks of eight as described 
 *            below (Internal Prediction Coefficient Update Procedures).  The 
 *            DC coefficient is first, and the remaining buffer locations 
 *            contain the quantized AC coefficients. Each group of eight row 
 *            buffer elements combined with one element eight elements ahead 
 *            contains the coefficient predictors of the neighboring block 
 *            that is spatially above or to the left of the block currently to 
 *            be decoded. A negative-valued DC coefficient indicates that this 
 *            neighboring block is not INTRA-coded or out of bounds, and 
 *            therefore the AC and DC coefficients are invalid.  Pointer must 
 *            be aligned on an 8-byte boundary. 
 *   pPredBufCol - pointer to the prediction coefficient column buffer 
 *            containing 16 elements of type OMX_S16. Coefficients are 
 *            organized as described in section 6.2.2.5.  Pointer must be 
 *            aligned on an 8-byte boundary. 
 *   pSumErr - pointer to a flag indicating whether or not AC prediction is 
 *            required; AC prediction is enabled if *pSumErr >=0, but the 
 *            value is not used for coefficient prediction, i.e., the sum of 
 *            absolute differences starts from 0 for each call to this 
 *            function.  Otherwise AC prediction is disabled if *pSumErr < 0 . 
 *   blockIndex - block index indicating the component type and position, as 
 *            defined in [ISO14496-2], subclause 6.1.3.8. 
 *   curQp - quantization parameter of the macroblock to which the current 
 *            block belongs 
 *   pQpBuf - pointer to a 2-element quantization parameter buffer; pQpBuf[0] 
 *            contains the quantization parameter associated with the 8x8 
 *            block left of the current block (QPa), and pQpBuf[1] contains 
 *            the quantization parameter associated with the 8x8 block above 
 *            the current block (QPc).  In the event that the corresponding 
 *            block is outside of the VOP bound, the Qp value will not affect 
 *            the intra prediction process, as described in [ISO14496-2], 
 *            sub-clause 7.4.3.3,  Adaptive AC Coefficient Prediction.  
 *   srcStep - width of the source buffer; must be a multiple of 8. 
 *   dstStep - width of the reconstructed destination buffer; must be a 
 *            multiple of 16. 
 *   shortVideoHeader - binary flag indicating presence of 
 *            short_video_header; shortVideoHeader==1 selects linear intra DC 
 *            mode, and shortVideoHeader==0 selects non linear intra DC mode. 
 *
 * Output Arguments:
 *   
 *   pDst - pointer to the quantized DCT coefficient buffer; pDst[0] contains 
 *            the predicted DC coefficient; the remaining entries contain the 
 *            quantized AC coefficients (without prediction).  The pointer 
 *            pDstmust be aligned on a 16-byte boundary. 
 *   pRec - pointer to the reconstructed texture; must be aligned on an 
 *            8-byte boundary. 
 *   pPredBufRow - pointer to the updated coefficient row buffer 
 *   pPredBufCol - pointer to the updated coefficient column buffer 
 *   pPreACPredict - if prediction is enabled, the parameter points to the 
 *            start of the buffer containing the coefficient differences for 
 *            VLC encoding. The entry pPreACPredict[0]indicates prediction 
 *            direction for the current block and takes one of the following 
 *            values: OMX_VC_NONE (prediction disabled), OMX_VC_HORIZONTAL, or 
 *            OMX_VC_VERTICAL.  The entries 
 *            pPreACPredict[1]-pPreACPredict[7]contain predicted AC 
 *            coefficients.  If prediction is disabled (*pSumErr<0) then the 
 *            contents of this buffer are undefined upon return from the 
 *            function 
 *   pSumErr - pointer to the value of the accumulated AC coefficient errors, 
 *            i.e., sum of the absolute differences between predicted and 
 *            unpredicted AC coefficients 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - Bad arguments:
 *    -    At least one of the following pointers is NULL: pSrc, pDst, pRec, 
 *         pCoefBufRow, pCoefBufCol, pQpBuf, pPreACPredict, pSumErr. 
 *    -    blockIndex < 0 or blockIndex >= 10; 
 *    -    curQP <= 0 or curQP >= 32. 
 *    -    srcStep, or dstStep <= 0 or not a multiple of 8. 
 *    -    pDst is not 16-byte aligned: . 
 *    -    At least one of the following pointers is not 8-byte aligned: 
 *         pSrc, pRec.  
 *
 *  Note: The coefficient buffers must be updated in accordance with the 
 *        update procedures defined in section in 6.2.2. 
 *
 */
OMXResult omxVCM4P2_TransRecBlockCoef_intra (
    const OMX_U8 *pSrc,
    OMX_S16 *pDst,
    OMX_U8 *pRec,
    OMX_S16 *pPredBufRow,
    OMX_S16 *pPredBufCol,
    OMX_S16 *pPreACPredict,
    OMX_INT *pSumErr,
    OMX_INT blockIndex,
    OMX_U8 curQp,
    const OMX_U8 *pQpBuf,
    OMX_INT srcStep,
    OMX_INT dstStep,
    OMX_INT shortVideoHeader
);



/**
 * Function:  omxVCM4P2_TransRecBlockCoef_inter   (6.2.4.4.5)
 *
 * Description:
 * Implements DCT, and quantizes the DCT coefficients of the inter block 
 * while reconstructing the texture residual. There is no boundary check for 
 * the bit stream buffer. 
 *
 * Input Arguments:
 *   
 *   pSrc -pointer to the residuals to be encoded; must be aligned on an 
 *            16-byte boundary. 
 *   QP - quantization parameter. 
 *   shortVideoHeader - binary flag indicating presence of short_video_header; 
 *                      shortVideoHeader==1 selects linear intra DC mode, and 
 *                      shortVideoHeader==0 selects non linear intra DC mode. 
 *
 * Output Arguments:
 *   
 *   pDst - pointer to the quantized DCT coefficients buffer; must be aligned 
 *            on a 16-byte boundary. 
 *   pRec - pointer to the reconstructed texture residuals; must be aligned 
 *            on a 16-byte boundary. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments:
 *    -    At least one of the following pointers is either NULL or 
 *         not 16-byte aligned: 
 *            - pSrc 
 *            - pDst
 *            - pRec
 *    -    QP <= 0 or QP >= 32. 
 *
 */
OMXResult omxVCM4P2_TransRecBlockCoef_inter (
    const OMX_S16 *pSrc,
    OMX_S16 *pDst,
    OMX_S16 *pRec,
    OMX_U8 QP,
    OMX_INT shortVideoHeader
);



/**
 * Function:  omxVCM4P2_EncodeVLCZigzag_IntraDCVLC   (6.2.4.5.2)
 *
 * Description:
 * Performs zigzag scan and VLC encoding of AC and DC coefficients for one 
 * intra block.  Two versions of the function (DCVLC and ACVLC) are provided 
 * in order to support the two different methods of processing DC 
 * coefficients, as described in [ISO14496-2], subclause 7.4.1.4, "Intra DC 
 * Coefficient Decoding for the Case of Switched VLC Encoding".  
 *
 * Input Arguments:
 *   
 *   ppBitStream - double pointer to the current byte in the bitstream 
 *   pBitOffset - pointer to the bit position in the byte pointed by 
 *            *ppBitStream. Valid within 0 to 7. 
 *   pQDctBlkCoef - pointer to the quantized DCT coefficient 
 *   predDir - AC prediction direction, which is used to decide the zigzag 
 *            scan pattern; takes one of the following values: 
 *            -  OMX_VC_NONE - AC prediction not used.  
 *                             Performs classical zigzag scan. 
 *            -  OMX_VC_HORIZONTAL - Horizontal prediction.  
 *                             Performs alternate-vertical zigzag scan. 
 *            -  OMX_VC_VERTICAL - Vertical prediction.  
 *                             Performs alternate-horizontal zigzag scan. 
 *   pattern - block pattern which is used to decide whether this block is 
 *            encoded 
 *   shortVideoHeader - binary flag indicating presence of 
 *            short_video_header; escape modes 0-3 are used if 
 *            shortVideoHeader==0, and escape mode 4 is used when 
 *            shortVideoHeader==1. 
 *   videoComp - video component type (luminance, chrominance) of the current 
 *            block 
 *
 * Output Arguments:
 *   
 *   ppBitStream - *ppBitStream is updated after the block is encoded, so 
 *            that it points to the current byte in the bit stream buffer. 
 *   pBitOffset - *pBitOffset is updated so that it points to the current bit 
 *            position in the byte pointed by *ppBitStream. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - Bad arguments:
 *    -    At least one of the following pointers is NULL: ppBitStream, 
 *              *ppBitStream, pBitOffset, pQDctBlkCoef. 
 *    -   *pBitOffset < 0, or *pBitOffset >7. 
 *    -    PredDir is not one of: OMX_VC_NONE, OMX_VC_HORIZONTAL, or 
 *         OMX_VC_VERTICAL. 
 *    -    VideoComp is not one component of enum OMXVCM4P2VideoComponent. 
 *
 */
OMXResult omxVCM4P2_EncodeVLCZigzag_IntraDCVLC (
    OMX_U8 **ppBitStream,
    OMX_INT *pBitOffset,
    const OMX_S16 *pQDctBlkCoef,
    OMX_U8 predDir,
    OMX_U8 pattern,
    OMX_INT shortVideoHeader,
    OMXVCM4P2VideoComponent videoComp
);



/**
 * Function:  omxVCM4P2_EncodeVLCZigzag_IntraACVLC   (6.2.4.5.2)
 *
 * Description:
 * Performs zigzag scan and VLC encoding of AC and DC coefficients for one 
 * intra block.  Two versions of the function (DCVLC and ACVLC) are provided 
 * in order to support the two different methods of processing DC 
 * coefficients, as described in [ISO14496-2], subclause 7.4.1.4,  Intra DC 
 * Coefficient Decoding for the Case of Switched VLC Encoding.  
 *
 * Input Arguments:
 *   
 *   ppBitStream - double pointer to the current byte in the bitstream 
 *   pBitOffset - pointer to the bit position in the byte pointed by 
 *            *ppBitStream. Valid within 0 to 7. 
 *   pQDctBlkCoef - pointer to the quantized DCT coefficient 
 *   predDir - AC prediction direction, which is used to decide the zigzag 
 *            scan pattern; takes one of the following values: 
 *            -  OMX_VC_NONE - AC prediction not used.  
 *                             Performs classical zigzag scan. 
 *            -  OMX_VC_HORIZONTAL - Horizontal prediction.  
 *                             Performs alternate-vertical zigzag scan. 
 *            -  OMX_VC_VERTICAL - Vertical prediction.  
 *                             Performs alternate-horizontal zigzag scan. 
 *   pattern - block pattern which is used to decide whether this block is 
 *            encoded 
 *   shortVideoHeader - binary flag indicating presence of 
 *            short_video_header; escape modes 0-3 are used if 
 *            shortVideoHeader==0, and escape mode 4 is used when 
 *            shortVideoHeader==1. 
 *
 * Output Arguments:
 *   
 *   ppBitStream - *ppBitStream is updated after the block is encoded, so 
 *            that it points to the current byte in the bit stream buffer. 
 *   pBitOffset - *pBitOffset is updated so that it points to the current bit 
 *            position in the byte pointed by *ppBitStream. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - Bad arguments:
 *    -    At least one of the following pointers is NULL: ppBitStream, 
 *              *ppBitStream, pBitOffset, pQDctBlkCoef. 
 *    -   *pBitOffset < 0, or *pBitOffset >7. 
 *    -    PredDir is not one of: OMX_VC_NONE, OMX_VC_HORIZONTAL, or 
 *         OMX_VC_VERTICAL. 
 *    -    VideoComp is not one component of enum OMXVCM4P2VideoComponent. 
 *
 */
OMXResult omxVCM4P2_EncodeVLCZigzag_IntraACVLC (
    OMX_U8 **ppBitStream,
    OMX_INT *pBitOffset,
    const OMX_S16 *pQDctBlkCoef,
    OMX_U8 predDir,
    OMX_U8 pattern,
    OMX_INT shortVideoHeader
);



/**
 * Function:  omxVCM4P2_EncodeVLCZigzag_Inter   (6.2.4.5.3)
 *
 * Description:
 * Performs classical zigzag scanning and VLC encoding for one inter block. 
 *
 * Input Arguments:
 *   
 *   ppBitStream - pointer to the pointer to the current byte in the bit 
 *            stream 
 *   pBitOffset - pointer to the bit position in the byte pointed by 
 *            *ppBitStream. Valid within 0 to 7 
 *   pQDctBlkCoef - pointer to the quantized DCT coefficient 
 *   pattern - block pattern which is used to decide whether this block is 
 *            encoded 
 *   shortVideoHeader - binary flag indicating presence of 
 *            short_video_header; escape modes 0-3 are used if 
 *            shortVideoHeader==0, and escape mode 4 is used when 
 *            shortVideoHeader==1. 
 *
 * Output Arguments:
 *   
 *   ppBitStream - *ppBitStream is updated after the block is encoded so that 
 *            it points to the current byte in the bit stream buffer. 
 *   pBitOffset - *pBitOffset is updated so that it points to the current bit 
 *            position in the byte pointed by *ppBitStream. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - Bad arguments 
 *    -    At least one of the pointers: is NULL: ppBitStream, *ppBitStream, 
 *              pBitOffset, pQDctBlkCoef 
 *    -   *pBitOffset < 0, or *pBitOffset >7. 
 *
 */
OMXResult omxVCM4P2_EncodeVLCZigzag_Inter (
    OMX_U8 **ppBitStream,
    OMX_INT *pBitOffset,
    const OMX_S16 *pQDctBlkCoef,
    OMX_U8 pattern,
    OMX_INT shortVideoHeader
);



/**
 * Function:  omxVCM4P2_EncodeMV   (6.2.4.5.4)
 *
 * Description:
 * Predicts a motion vector for the current macroblock, encodes the 
 * difference, and writes the output to the stream buffer. The input MVs 
 * pMVCurMB, pSrcMVLeftMB, pSrcMVUpperMB, and pSrcMVUpperRightMB should lie 
 * within the ranges associated with the input parameter fcodeForward, as 
 * described in [ISO14496-2], subclause 7.6.3.  This function provides a 
 * superset of the functionality associated with the function 
 * omxVCM4P2_FindMVpred. 
 *
 * Input Arguments:
 *   
 *   ppBitStream - double pointer to the current byte in the bitstream buffer 
 *   pBitOffset - index of the first free (next available) bit in the stream 
 *            buffer referenced by *ppBitStream, valid in the range 0 to 7. 
 *   pMVCurMB - pointer to the current macroblock motion vector; a value of 
 *            NULL indicates unavailability. 
 *   pSrcMVLeftMB - pointer to the source left macroblock motion vector; a 
 *            value of  NULLindicates unavailability. 
 *   pSrcMVUpperMB - pointer to source upper macroblock motion vector; a 
 *            value of NULL indicates unavailability. 
 *   pSrcMVUpperRightMB - pointer to source upper right MB motion vector; a 
 *            value of NULL indicates unavailability. 
 *   fcodeForward - an integer with values from 1 to 7; used in encoding 
 *            motion vectors related to search range, as described in 
 *            [ISO14496-2], subclause 7.6.3. 
 *   MBType - macro block type, valid in the range 0 to 5 
 *
 * Output Arguments:
 *   
 *   ppBitStream - updated pointer to the current byte in the bit stream 
 *            buffer 
 *   pBitOffset - updated index of the next available bit position in stream 
 *            buffer referenced by *ppBitStream 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments 
 *    -    At least one of the following pointers is NULL: ppBitStream, 
 *              *ppBitStream, pBitOffset, pMVCurMB 
 *    -    *pBitOffset < 0, or *pBitOffset >7. 
 *    -    fcodeForward <= 0, or fcodeForward > 7, or MBType < 0. 
 *
 */
OMXResult omxVCM4P2_EncodeMV (
    OMX_U8 **ppBitStream,
    OMX_INT *pBitOffset,
    const OMXVCMotionVector *pMVCurMB,
    const OMXVCMotionVector*pSrcMVLeftMB,
    const OMXVCMotionVector *pSrcMVUpperMB,
    const OMXVCMotionVector *pSrcMVUpperRightMB,
    OMX_INT fcodeForward,
    OMXVCM4P2MacroblockType MBType
);



/**
 * Function:  omxVCM4P2_DecodePadMV_PVOP   (6.2.5.1.1)
 *
 * Description:
 * Decodes and pads the four motion vectors associated with a non-intra P-VOP 
 * macroblock.  For macroblocks of type OMX_VC_INTER4V, the output MV is 
 * padded as specified in [ISO14496-2], subclause 7.6.1.6. Otherwise, for 
 * macroblocks of types other than OMX_VC_INTER4V, the decoded MV is copied to 
 * all four output MV buffer entries. 
 *
 * Input Arguments:
 *   
 *   ppBitStream - pointer to the pointer to the current byte in the bit 
 *            stream buffer 
 *   pBitOffset - pointer to the bit position in the byte pointed to by 
 *            *ppBitStream. *pBitOffset is valid within [0-7]. 
 *   pSrcMVLeftMB, pSrcMVUpperMB, and pSrcMVUpperRightMB - pointers to the 
 *            motion vector buffers of the macroblocks specially at the left, 
 *            upper, and upper-right side of the current macroblock, 
 *            respectively; a value of NULL indicates unavailability.  Note: 
 *            Any neighborhood macroblock outside the current VOP or video 
 *            packet or outside the current GOB (when short_video_header is 
 *             1 ) for which gob_header_empty is  0  is treated as 
 *            transparent, according to [ISO14496-2], subclause 7.6.5. 
 *   fcodeForward - a code equal to vop_fcode_forward in MPEG-4 bit stream 
 *            syntax 
 *   MBType - the type of the current macroblock. If MBType is not equal to 
 *            OMX_VC_INTER4V, the destination motion vector buffer is still 
 *            filled with the same decoded vector. 
 *
 * Output Arguments:
 *   
 *   ppBitStream - *ppBitStream is updated after the block is decoded, so 
 *            that it points to the current byte in the bit stream buffer 
 *   pBitOffset - *pBitOffset is updated so that it points to the current bit 
 *            position in the byte pointed by *ppBitStream 
 *   pDstMVCurMB - pointer to the motion vector buffer for the current 
 *            macroblock; contains four decoded motion vectors 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments:
 *    -    At least one of the following pointers is NULL: 
 *         ppBitStream, *ppBitStream, pBitOffset, pDstMVCurMB 
 *    -    *pBitOffset exceeds [0,7]
 *    -    fcodeForward exceeds (0,7]
 *    -    MBType less than zero
 *    -    motion vector buffer is not 4-byte aligned. 
 *    OMX_Sts_Err - status error 
 *
 */
OMXResult omxVCM4P2_DecodePadMV_PVOP (
    const OMX_U8 **ppBitStream,
    OMX_INT *pBitOffset,
    OMXVCMotionVector *pSrcMVLeftMB,
    OMXVCMotionVector*pSrcMVUpperMB,
    OMXVCMotionVector *pSrcMVUpperRightMB,
    OMXVCMotionVector*pDstMVCurMB,
    OMX_INT fcodeForward,
    OMXVCM4P2MacroblockType MBType
);



/**
 * Function:  omxVCM4P2_DecodeVLCZigzag_IntraDCVLC   (6.2.5.2.2)
 *
 * Description:
 * Performs VLC decoding and inverse zigzag scan of AC and DC coefficients 
 * for one intra block.  Two versions of the function (DCVLC and ACVLC) are 
 * provided in order to support the two different methods of processing DC 
 * coefficients, as described in [ISO14496-2], subclause 7.4.1.4,  Intra DC 
 * Coefficient Decoding for the Case of Switched VLC Encoding.  
 *
 * Input Arguments:
 *   
 *   ppBitStream - pointer to the pointer to the current byte in the 
 *            bitstream buffer 
 *   pBitOffset - pointer to the bit position in the current byte referenced 
 *            by *ppBitStream.  The parameter *pBitOffset is valid in the 
 *            range [0-7]. 
 *            Bit Position in one byte:  |Most      Least| 
 *                    *pBitOffset        |0 1 2 3 4 5 6 7| 
 *   predDir - AC prediction direction; used to select the zigzag scan 
 *            pattern; takes one of the following values: 
 *            -  OMX_VC_NONE - AC prediction not used; 
 *                             performs classical zigzag scan. 
 *            -  OMX_VC_HORIZONTAL - Horizontal prediction; 
 *                             performs alternate-vertical zigzag scan; 
 *            -  OMX_VC_VERTICAL - Vertical prediction; 
 *                             performs alternate-horizontal zigzag scan. 
 *   shortVideoHeader - binary flag indicating presence of 
 *            short_video_header; escape modes 0-3 are used if 
 *            shortVideoHeader==0, and escape mode 4 is used when 
 *            shortVideoHeader==1. 
 *   videoComp - video component type (luminance or chrominance) of the 
 *            current block 
 *
 * Output Arguments:
 *   
 *   ppBitStream - *ppBitStream is updated after the block is decoded such 
 *            that it points to the current byte in the bit stream buffer 
 *   pBitOffset - *pBitOffset is updated such that it points to the current 
 *            bit position in the byte pointed by *ppBitStream 
 *   pDst - pointer to the coefficient buffer of current block; must be 
 *            4-byte aligned. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments, if:
 *    -    At least one of the following pointers is NULL: 
 *         ppBitStream, *ppBitStream, pBitOffset, pDst
 *    -    *pBitOffset exceeds [0,7]
 *    -    preDir exceeds [0,2]
 *    -    pDst is not 4-byte aligned 
 *    OMX_Sts_Err - if:
 *    -    In DecodeVLCZigzag_IntraDCVLC, dc_size > 12 
 *    -    At least one of mark bits equals zero 
 *    -    Illegal stream encountered; code cannot be located in VLC table 
 *    -    Forbidden code encountered in the VLC FLC table. 
 *    -    The number of coefficients is greater than 64 
 *
 */
OMXResult omxVCM4P2_DecodeVLCZigzag_IntraDCVLC (
    const OMX_U8 **ppBitStream,
    OMX_INT *pBitOffset,
    OMX_S16 *pDst,
    OMX_U8 predDir,
    OMX_INT shortVideoHeader,
    OMXVCM4P2VideoComponent videoComp
);



/**
 * Function:  omxVCM4P2_DecodeVLCZigzag_IntraACVLC   (6.2.5.2.2)
 *
 * Description:
 * Performs VLC decoding and inverse zigzag scan of AC and DC coefficients 
 * for one intra block.  Two versions of the function (DCVLC and ACVLC) are 
 * provided in order to support the two different methods of processing DC 
 * coefficients, as described in [ISO14496-2], subclause 7.4.1.4,  Intra DC 
 * Coefficient Decoding for the Case of Switched VLC Encoding.  
 *
 * Input Arguments:
 *   
 *   ppBitStream - pointer to the pointer to the current byte in the 
 *            bitstream buffer 
 *   pBitOffset - pointer to the bit position in the current byte referenced 
 *            by *ppBitStream.  The parameter *pBitOffset is valid in the 
 *            range [0-7]. Bit Position in one byte:  |Most Least| *pBitOffset 
 *            |0 1 2 3 4 5 6 7| 
 *   predDir - AC prediction direction; used to select the zigzag scan 
 *            pattern; takes one of the following values: OMX_VC_NONE - AC 
 *            prediction not used; performs classical zigzag scan. 
 *            OMX_VC_HORIZONTAL - Horizontal prediction; performs 
 *            alternate-vertical zigzag scan; OMX_VC_VERTICAL - Vertical 
 *            prediction; performs alternate-horizontal zigzag scan. 
 *   shortVideoHeader - binary flag indicating presence of 
 *            short_video_header; escape modes 0-3 are used if 
 *            shortVideoHeader==0, and escape mode 4 is used when 
 *            shortVideoHeader==1. 
 *   videoComp - video component type (luminance or chrominance) of the 
 *            current block 
 *
 * Output Arguments:
 *   
 *   ppBitStream - *ppBitStream is updated after the block is decoded such 
 *            that it points to the current byte in the bit stream buffer 
 *   pBitOffset - *pBitOffset is updated such that it points to the current 
 *            bit position in the byte pointed by *ppBitStream 
 *   pDst - pointer to the coefficient buffer of current block; must be 
 *            4-byte aligned. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments At least one of the following 
 *              pointers is NULL: ppBitStream, *ppBitStream, pBitOffset, pDst, 
 *              or At least one of the following conditions is true: 
 *              *pBitOffset exceeds [0,7], preDir exceeds [0,2], or pDst is 
 *              not 4-byte aligned 
 *    OMX_Sts_Err In DecodeVLCZigzag_IntraDCVLC, dc_size > 12 At least one of 
 *              mark bits equals zero Illegal stream encountered; code cannot 
 *              be located in VLC table Forbidden code encountered in the VLC 
 *              FLC table The number of coefficients is greater than 64 
 *
 */
OMXResult omxVCM4P2_DecodeVLCZigzag_IntraACVLC (
    const OMX_U8 **ppBitStream,
    OMX_INT *pBitOffset,
    OMX_S16 *pDst,
    OMX_U8 predDir,
    OMX_INT shortVideoHeader
);



/**
 * Function:  omxVCM4P2_DecodeVLCZigzag_Inter   (6.2.5.2.3)
 *
 * Description:
 * Performs VLC decoding and inverse zigzag scan for one inter-coded block. 
 *
 * Input Arguments:
 *   
 *   ppBitStream - double pointer to the current byte in the stream buffer 
 *   pBitOffset - pointer to the next available bit in the current stream 
 *            byte referenced by *ppBitStream. The parameter *pBitOffset is 
 *            valid within the range [0-7]. 
 *   shortVideoHeader - binary flag indicating presence of 
 *            short_video_header; escape modes 0-3 are used if 
 *            shortVideoHeader==0, and escape mode 4 is used when 
 *            shortVideoHeader==1. 
 *
 * Output Arguments:
 *   
 *   ppBitStream - *ppBitStream is updated after the block is decoded such 
 *            that it points to the current byte in the stream buffer 
 *   pBitOffset - *pBitOffset is updated after decoding such that it points 
 *            to the next available bit in the stream byte referenced by 
 *            *ppBitStream 
 *   pDst - pointer to the coefficient buffer of current block; must be 
 *            4-byte aligned. 
 *
 * Return Value:
 *    
 *    OMX_Sts_BadArgErr - bad arguments:
 *    -    At least one of the following pointers is NULL: 
 *         ppBitStream, *ppBitStream, pBitOffset, pDst
 *    -    pDst is not 4-byte aligned
 *    -   *pBitOffset exceeds [0,7]
 *    OMX_Sts_Err - status error, if:
 *    -    At least one mark bit is equal to zero 
 *    -    Encountered an illegal stream code that cannot be found in the VLC table 
 *    -    Encountered an illegal code in the VLC FLC table 
 *    -    The number of coefficients is greater than 64 
 *
 */
OMXResult omxVCM4P2_DecodeVLCZigzag_Inter (
    const OMX_U8 **ppBitStream,
    OMX_INT *pBitOffset,
    OMX_S16 *pDst,
    OMX_INT shortVideoHeader
);



/**
 * Function:  omxVCM4P2_QuantInvIntra_I   (6.2.5.3.2)
 *
 * Description:
 * Performs the second inverse quantization mode on an intra/inter coded 
 * block. Supports bits_per_pixel = 8. The output coefficients are clipped to 
 * the range [-2048, 2047]. 
 *
 * Input Arguments:
 *   
 *   pSrcDst - pointer to the input (quantized) intra/inter block; must be 
 *            aligned on a 16-byte boundary. 
 *   QP - quantization parameter (quantizer_scale) 
 *   videoComp - video component type of the current block. Takes one of the 
 *            following flags: OMX_VC_LUMINANCE, OMX_VC_CHROMINANCE (intra 
 *            version only). 
 *   shortVideoHeader - binary flag indicating presence of short_video_header 
 *            (intra version only). 
 *
 * Output Arguments:
 *   
 *   pSrcDst - pointer to the output (dequantized) intra/inter block 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; one or more of the following is 
 *              true: 
 *    -    pSrcDst is NULL 
 *    -    QP <= 0 or QP >=31 
 *    -    videoComp is neither OMX_VC_LUMINANCE nor OMX_VC_CHROMINANCE. 
 *
 */
OMXResult omxVCM4P2_QuantInvIntra_I (
    OMX_S16 *pSrcDst,
    OMX_INT QP,
    OMXVCM4P2VideoComponent videoComp,
    OMX_INT shortVideoHeader
);



/**
 * Function:  omxVCM4P2_QuantInvInter_I   (6.2.5.3.2)
 *
 * Description:
 * Performs the second inverse quantization mode on an intra/inter coded 
 * block. Supports bits_per_pixel = 8. The output coefficients are clipped to 
 * the range [-2048, 2047]. 
 *
 * Input Arguments:
 *   
 *   pSrcDst - pointer to the input (quantized) intra/inter block; must be 
 *            aligned on a 16-byte boundary. 
 *   QP - quantization parameter (quantizer_scale) 
 *   videoComp - video component type of the current block. Takes one of the 
 *            following flags: OMX_VC_LUMINANCE, OMX_VC_CHROMINANCE (intra 
 *            version only). 
 *   shortVideoHeader - binary flag indicating presence of short_video_header 
 *            (intra version only). 
 *
 * Output Arguments:
 *   
 *   pSrcDst - pointer to the output (dequantized) intra/inter block 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; one or more of the following is 
 *              true: 
 *    -    pSrcDst is NULL 
 *    -    QP <= 0 or QP >=31 
 *    -    videoComp is neither OMX_VC_LUMINANCE nor OMX_VC_CHROMINANCE. 
 *
 */
OMXResult omxVCM4P2_QuantInvInter_I (
    OMX_S16 *pSrcDst,
    OMX_INT QP
);



/**
 * Function:  omxVCM4P2_DecodeBlockCoef_Intra   (6.2.5.4.1)
 *
 * Description:
 * Decodes the INTRA block coefficients. Inverse quantization, inversely 
 * zigzag positioning, and IDCT, with appropriate clipping on each step, are 
 * performed on the coefficients. The results are then placed in the output 
 * frame/plane on a pixel basis.  Note: This function will be used only when 
 * at least one non-zero AC coefficient of current block exists in the bit 
 * stream. The DC only condition will be handled in another function. 
 *
 *
 * Input Arguments:
 *   
 *   ppBitStream - pointer to the pointer to the current byte in the bit 
 *            stream buffer. There is no boundary check for the bit stream 
 *            buffer. 
 *   pBitOffset - pointer to the bit position in the byte pointed to by 
 *            *ppBitStream. *pBitOffset is valid within [0-7]. 
 *   step - width of the destination plane 
 *   pCoefBufRow - pointer to the coefficient row buffer; must be aligned on 
 *            an 8-byte boundary. 
 *   pCoefBufCol - pointer to the coefficient column buffer; must be aligned 
 *            on an 8-byte boundary. 
 *   curQP - quantization parameter of the macroblock which the current block 
 *            belongs to 
 *   pQPBuf - pointer to the quantization parameter buffer 
 *   blockIndex - block index indicating the component type and position as 
 *            defined in [ISO14496-2], subclause 6.1.3.8, Figure 6-5. 
 *   intraDCVLC - a code determined by intra_dc_vlc_thr and QP. This allows a 
 *            mechanism to switch between two VLC for coding of Intra DC 
 *            coefficients as per [ISO14496-2], Table 6-21. 
 *   ACPredFlag - a flag equal to ac_pred_flag (of luminance) indicating if 
 *            the ac coefficients of the first row or first column are 
 *            differentially coded for intra coded macroblock. 
 *   shortVideoHeader - binary flag indicating presence of 
 *            short_video_header; shortVideoHeader==1 selects linear intra DC 
 *            mode, and shortVideoHeader==0 selects non linear intra DC mode. 
 *
 * Output Arguments:
 *   
 *   ppBitStream - *ppBitStream is updated after the block is decoded, so 
 *            that it points to the current byte in the bit stream buffer 
 *   pBitOffset - *pBitOffset is updated so that it points to the current bit 
 *            position in the byte pointed by *ppBitStream 
 *   pDst - pointer to the block in the destination plane; must be aligned on 
 *            an 8-byte boundary. 
 *   pCoefBufRow - pointer to the updated coefficient row buffer. 
 *   pCoefBufCol - pointer to the updated coefficient column buffer  Note: 
 *            The coefficient buffers must be updated in accordance with the 
 *            update procedure defined in section 6.2.2. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments, if:
 *    -    At least one of the following pointers is NULL: 
 *         ppBitStream, *ppBitStream, pBitOffset, pCoefBufRow, pCoefBufCol, 
 *         pQPBuf, pDst. 
 *    -    *pBitOffset exceeds [0,7] 
 *    -    curQP exceeds (1, 31)
 *    -    blockIndex exceeds [0,5]
 *    -    step is not the multiple of 8
 *    -    a pointer alignment requirement was violated. 
 *    OMX_Sts_Err - status error. Refer to OMX_Sts_Err of DecodeVLCZigzag_Intra.  
 *
 */
OMXResult omxVCM4P2_DecodeBlockCoef_Intra (
    const OMX_U8 **ppBitStream,
    OMX_INT *pBitOffset,
    OMX_U8 *pDst,
    OMX_INT step,
    OMX_S16 *pCoefBufRow,
    OMX_S16 *pCoefBufCol,
    OMX_U8 curQP,
    const OMX_U8 *pQPBuf,
    OMX_INT blockIndex,
    OMX_INT intraDCVLC,
    OMX_INT ACPredFlag,
    OMX_INT shortVideoHeader
);



/**
 * Function:  omxVCM4P2_DecodeBlockCoef_Inter   (6.2.5.4.2)
 *
 * Description:
 * Decodes the INTER block coefficients. This function performs inverse 
 * quantization, inverse zigzag positioning, and IDCT (with appropriate 
 * clipping on each step) on the coefficients. The results (residuals) are 
 * placed in a contiguous array of 64 elements. For INTER block, the output 
 * buffer holds the residuals for further reconstruction. 
 *
 * Input Arguments:
 *   
 *   ppBitStream - pointer to the pointer to the current byte in the bit 
 *            stream buffer. There is no boundary check for the bit stream 
 *            buffer. 
 *   pBitOffset - pointer to the bit position in the byte pointed to by 
 *            *ppBitStream. *pBitOffset is valid within [0-7] 
 *   QP - quantization parameter 
 *   shortVideoHeader - binary flag indicating presence of 
 *            short_video_header; shortVideoHeader==1 selects linear intra DC 
 *            mode, and shortVideoHeader==0 selects non linear intra DC mode. 
 *
 * Output Arguments:
 *   
 *   ppBitStream - *ppBitStream is updated after the block is decoded, so 
 *            that it points to the current byte in the bit stream buffer 
 *   pBitOffset - *pBitOffset is updated so that it points to the current bit 
 *            position in the byte pointed by *ppBitStream 
 *   pDst - pointer to the decoded residual buffer (a contiguous array of 64 
 *            elements of OMX_S16 data type); must be aligned on a 16-byte 
 *            boundary. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments, if:
 *    -    At least one of the following pointers is Null: 
 *         ppBitStream, *ppBitStream, pBitOffset , pDst 
 *    -    *pBitOffset exceeds [0,7]
 *    -    QP <= 0. 
 *    -    pDst is not 16-byte aligned 
 *    OMX_Sts_Err - status error. Refer to OMX_Sts_Err of DecodeVLCZigzag_Inter . 
 *
 */
OMXResult omxVCM4P2_DecodeBlockCoef_Inter (
    const OMX_U8 **ppBitStream,
    OMX_INT *pBitOffset,
    OMX_S16 *pDst,
    OMX_INT QP,
    OMX_INT shortVideoHeader
);



/**
 * Function:  omxVCM4P2_PredictReconCoefIntra   (6.2.5.4.3)
 *
 * Description:
 * Performs adaptive DC/AC coefficient prediction for an intra block.  Prior 
 * to the function call, prediction direction (predDir) should be selected as 
 * specified in [ISO14496-2], subclause 7.4.3.1. 
 *
 * Input Arguments:
 *   
 *   pSrcDst - pointer to the coefficient buffer which contains the quantized 
 *            coefficient residuals (PQF) of the current block; must be 
 *            aligned on a 4-byte boundary.  The output coefficients are 
 *            saturated to the range [-2048, 2047]. 
 *   pPredBufRow - pointer to the coefficient row buffer; must be aligned on 
 *            a 4-byte boundary. 
 *   pPredBufCol - pointer to the coefficient column buffer; must be aligned 
 *            on a 4-byte boundary. 
 *   curQP - quantization parameter of the current block. curQP may equal to 
 *            predQP especially when the current block and the predictor block 
 *            are in the same macroblock. 
 *   predQP - quantization parameter of the predictor block 
 *   predDir - indicates the prediction direction which takes one of the 
 *            following values: OMX_VC_HORIZONTAL - predict horizontally 
 *            OMX_VC_VERTICAL - predict vertically 
 *   ACPredFlag - a flag indicating if AC prediction should be performed. It 
 *            is equal to ac_pred_flag in the bit stream syntax of MPEG-4 
 *   videoComp - video component type (luminance or chrominance) of the 
 *            current block 
 *
 * Output Arguments:
 *   
 *   pSrcDst - pointer to the coefficient buffer which contains the quantized 
 *            coefficients (QF) of the current block 
 *   pPredBufRow - pointer to the updated coefficient row buffer 
 *   pPredBufCol - pointer to the updated coefficient column buffer  Note: 
 *            Buffer update: Update the AC prediction buffer (both row and 
 *            column buffer). 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments, if:
 *        -    At least one of the pointers is NULL: 
 *              pSrcDst, pPredBufRow, or pPredBufCol. 
 *        -    curQP <= 0, 
 *        -    predQP <= 0, 
 *        -    curQP >31, 
 *        -    predQP > 31, 
 *        -    preDir exceeds [1,2]
 *        -    pSrcDst, pPredBufRow, or pPredBufCol is not 4-byte aligned. 
 *
 */
OMXResult omxVCM4P2_PredictReconCoefIntra (
    OMX_S16 *pSrcDst,
    OMX_S16 *pPredBufRow,
    OMX_S16 *pPredBufCol,
    OMX_INT curQP,
    OMX_INT predQP,
    OMX_INT predDir,
    OMX_INT ACPredFlag,
    OMXVCM4P2VideoComponent videoComp
);



/**
 * Function:  omxVCM4P2_MCReconBlock   (6.2.5.5.1)
 *
 * Description:
 * Performs motion compensation prediction for an 8x8 block using 
 * interpolation described in [ISO14496-2], subclause 7.6.2. 
 *
 * Input Arguments:
 *   
 *   pSrc - pointer to the block in the reference plane. 
 *   srcStep - distance between the start of consecutive lines in the 
 *            reference plane, in bytes; must be a multiple of 8. 
 *   dstStep - distance between the start of consecutive lines in the 
 *            destination plane, in bytes; must be a multiple of 8. 
 *   pSrcResidue - pointer to a buffer containing the 16-bit prediction 
 *            residuals; must be 16-byte aligned. If the pointer is NULL, then 
 *            no prediction is done, only motion compensation, i.e., the block 
 *            is moved with interpolation. 
 *   predictType - bilinear interpolation type, as defined in section 
 *            6.2.1.2. 
 *   rndVal - rounding control parameter: 0 - disabled; 1 - enabled. 
 *
 * Output Arguments:
 *   
 *   pDst - pointer to the destination buffer; must be 8-byte aligned.  If 
 *            prediction residuals are added then output intensities are 
 *            clipped to the range [0,255]. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned under any of the following 
 *              conditions: 
 *    -    pDst is not 8-byte aligned. 
 *    -    pSrcResidue is not 16-byte aligned. 
 *    -    one or more of the following pointers is NULL: pSrc or pDst. 
 *    -    either srcStep or dstStep is not a multiple of 8. 
 *    -    invalid type specified for the parameter predictType. 
 *    -    the parameter rndVal is not equal either to 0 or 1. 
 *
 */
OMXResult omxVCM4P2_MCReconBlock (
    const OMX_U8 *pSrc,
    OMX_INT srcStep,
    const OMX_S16 *pSrcResidue,
    OMX_U8 *pDst,
    OMX_INT dstStep,
    OMX_INT predictType,
    OMX_INT rndVal
);



/* 6.3.1.1 Intra 16x16 Prediction Modes  */
/* A data type that enumerates intra_16x16 macroblock prediction modes is defined as follows:  */

typedef enum {
    OMX_VC_16X16_VERT = 0,  /** Intra_16x16_Vertical */
    OMX_VC_16X16_HOR = 1,   /** Intra_16x16_Horizontal */
    OMX_VC_16X16_DC = 2,    /** Intra_16x16_DC */
    OMX_VC_16X16_PLANE = 3  /** Intra_16x16_Plane */ 
} OMXVCM4P10Intra16x16PredMode;



/* 6.3.1.2 Intra 4x4 Prediction Modes  */
/* A data type that enumerates intra_4x4 macroblock prediction modes is defined as follows:  */

typedef enum {
    OMX_VC_4X4_VERT = 0,     /** Intra_4x4_Vertical */
    OMX_VC_4X4_HOR = 1,      /** Intra_4x4_Horizontal */
    OMX_VC_4X4_DC = 2,       /** Intra_4x4_DC */
    OMX_VC_4X4_DIAG_DL = 3,  /** Intra_4x4_Diagonal_Down_Left */
    OMX_VC_4X4_DIAG_DR = 4,  /** Intra_4x4_Diagonal_Down_Right */
    OMX_VC_4X4_VR = 5,       /** Intra_4x4_Vertical_Right */
    OMX_VC_4X4_HD = 6,       /** Intra_4x4_Horizontal_Down */
    OMX_VC_4X4_VL = 7,       /** Intra_4x4_Vertical_Left */
    OMX_VC_4X4_HU = 8        /** Intra_4x4_Horizontal_Up */ 
} OMXVCM4P10Intra4x4PredMode;



/* 6.3.1.3 Chroma Prediction Modes  */
/* A data type that enumerates intra chroma prediction modes is defined as follows:  */

typedef enum {
    OMX_VC_CHROMA_DC = 0,    /** Intra_Chroma_DC */
    OMX_VC_CHROMA_HOR = 1,   /** Intra_Chroma_Horizontal */
    OMX_VC_CHROMA_VERT = 2,  /** Intra_Chroma_Vertical */
    OMX_VC_CHROMA_PLANE = 3  /** Intra_Chroma_Plane */ 
} OMXVCM4P10IntraChromaPredMode;



/* 6.3.1.4 Motion Estimation Modes  */
/* A data type that enumerates H.264 motion estimation modes is defined as follows:  */

typedef enum {
    OMX_VC_M4P10_FAST_SEARCH = 0, /** Fast motion search */
    OMX_VC_M4P10_FULL_SEARCH = 1  /** Full motion search */ 
} OMXVCM4P10MEMode;



/* 6.3.1.5 Macroblock Types  */
/* A data type that enumerates H.264 macroblock types is defined as follows:  */

typedef enum {
    OMX_VC_P_16x16  = 0, /* defined by [ISO14496-10] */
    OMX_VC_P_16x8  = 1,
    OMX_VC_P_8x16  = 2,
    OMX_VC_P_8x8  = 3,
    OMX_VC_PREF0_8x8  = 4,
    OMX_VC_INTER_SKIP  = 5,
    OMX_VC_INTRA_4x4  = 8,
    OMX_VC_INTRA_16x16  = 9,
    OMX_VC_INTRA_PCM = 10 
} OMXVCM4P10MacroblockType;



/* 6.3.1.6 Sub-Macroblock Types  */
/* A data type that enumerates H.264 sub-macroblock types is defined as follows:  */

typedef enum {
    OMX_VC_SUB_P_8x8 = 0, /* defined by [ISO14496-10] */
    OMX_VC_SUB_P_8x4 = 1,
    OMX_VC_SUB_P_4x8 = 2,
    OMX_VC_SUB_P_4x4 = 3 
} OMXVCM4P10SubMacroblockType;



/* 6.3.1.7 Variable Length Coding (VLC) Information  */

typedef struct {
    OMX_U8 uTrailing_Ones;      /* Trailing ones; 3 at most */
    OMX_U8 uTrailing_One_Signs; /* Trailing ones signal */
    OMX_U8 uNumCoeffs;          /* Total number of non-zero coefs, including trailing ones */
    OMX_U8 uTotalZeros;         /* Total number of zero coefs */
    OMX_S16 iLevels[16];        /* Levels of non-zero coefs, in reverse zig-zag order */
    OMX_U8 uRuns[16];           /* Runs for levels and trailing ones, in reverse zig-zag order */
} OMXVCM4P10VLCInfo;



/* 6.3.1.8 Macroblock Information  */

typedef struct {
    OMX_S32 sliceId;                          /* slice number */
    OMXVCM4P10MacroblockType mbType;          /* MB type */
    OMXVCM4P10SubMacroblockType subMBType[4]; /* sub-block type */
    OMX_S32 qpy;                              /* qp for luma */
    OMX_S32 qpc;                              /* qp for chroma */
    OMX_U32 cbpy;                             /* CBP Luma */
    OMX_U32 cbpc;                             /* CBP Chroma */
    OMXVCMotionVector pMV0[4][4]; /* motion vector, represented using 1/4-pel units, pMV0[blocky][blockx] (blocky = 0~3, blockx =0~3) */
    OMXVCMotionVector pMVPred[4][4]; /* motion vector prediction, Represented using 1/4-pel units, pMVPred[blocky][blockx] (blocky = 0~3, blockx = 0~3) */
    OMX_U8 pRefL0Idx[4];                      /* reference picture indices */
    OMXVCM4P10Intra16x16PredMode Intra16x16PredMode; /* best intra 16x16 prediction mode */
    OMXVCM4P10Intra4x4PredMode pIntra4x4PredMode[16]; /* best intra 4x4 prediction mode for each block, pMV0 indexed as above */
} OMXVCM4P10MBInfo, *OMXVCM4P10MBInfoPtr;



/* 6.3.1.9 Motion Estimation Parameters  */

typedef struct {
    OMX_S32 blockSplitEnable8x8; /* enables 16x8, 8x16, 8x8 */
    OMX_S32 blockSplitEnable4x4; /* enable splitting of 8x4, 4x8, 4x4 blocks */
    OMX_S32 halfSearchEnable;
    OMX_S32 quarterSearchEnable;
    OMX_S32 intraEnable4x4;      /* 1=enable, 0=disable */
    OMX_S32 searchRange16x16;    /* integer pixel units */
    OMX_S32 searchRange8x8;
    OMX_S32 searchRange4x4;
} OMXVCM4P10MEParams;



/**
 * Function:  omxVCM4P10_PredictIntra_4x4   (6.3.3.1.1)
 *
 * Description:
 * Perform Intra_4x4 prediction for luma samples. If the upper-right block is 
 * not available, then duplication work should be handled inside the function. 
 * Users need not define them outside. 
 *
 * Input Arguments:
 *   
 *   pSrcLeft -  Pointer to the buffer of 4 left pixels: 
 *                  p[x, y] (x = -1, y = 0..3) 
 *   pSrcAbove - Pointer to the buffer of 8 above pixels: 
 *                  p[x,y] (x = 0..7, y =-1); 
 *               must be aligned on a 4-byte boundary. 
 *   pSrcAboveLeft - Pointer to the above left pixels: p[x,y] (x = -1, y = -1) 
 *   leftStep - Step of left pixel buffer; must be a multiple of 4. 
 *   dstStep - Step of the destination buffer; must be a multiple of 4. 
 *   predMode - Intra_4x4 prediction mode. 
 *   availability - Neighboring 4x4 block availability flag, refer to 
 *             "Neighboring Macroblock Availability" . 
 *
 * Output Arguments:
 *   
 *   pDst - Pointer to the destination buffer; must be aligned on a 4-byte 
 *            boundary. 
 *
 * Return Value:
 *    If the function runs without error, it returns OMX_Sts_NoErr. 
 *    If one of the following cases occurs, the function returns 
 *              OMX_Sts_BadArgErr: 
 *    pDst is NULL. 
 *    dstStep < 4, or dstStep is not a multiple of 4. 
 *    leftStep is not a multiple of 4. 
 *    predMode is not in the valid range of enumeration 
 *              OMXVCM4P10Intra4x4PredMode. 
 *    predMode is OMX_VC_4x4_VERT, but availability doesn't set OMX_VC_UPPER 
 *              indicating p[x,-1] (x = 0..3) is not available. 
 *    predMode is OMX_VC_4x4_HOR, but availability doesn't set OMX_VC_LEFT 
 *              indicating p[-1,y] (y = 0..3) is not available. 
 *    predMode is OMX_VC_4x4_DIAG_DL, but availability doesn't set 
 *              OMX_VC_UPPER indicating p[x, -1] (x = 0..3) is not available. 
 *    predMode is OMX_VC_4x4_DIAG_DR, but availability doesn't set 
 *              OMX_VC_UPPER_LEFT or OMX_VC_UPPER or OMX_VC_LEFT indicating 
 *              p[x,-1] (x = 0..3), or p[-1,y] (y = 0..3) or p[-1,-1] is not 
 *              available. 
 *    predMode is OMX_VC_4x4_VR, but availability doesn't set 
 *              OMX_VC_UPPER_LEFT or OMX_VC_UPPER or OMX_VC_LEFT indicating 
 *              p[x,-1] (x = 0..3), or p[-1,y] (y = 0..3) or p[-1,-1] is not 
 *              available. 
 *    predMode is OMX_VC_4x4_HD, but availability doesn't set 
 *              OMX_VC_UPPER_LEFT or OMX_VC_UPPER or OMX_VC_LEFT indicating 
 *              p[x,-1] (x = 0..3), or p[-1,y] (y = 0..3) or p[-1,-1] is not 
 *              available. 
 *    predMode is OMX_VC_4x4_VL, but availability doesn't set OMX_VC_UPPER 
 *              indicating p[x,-1] (x = 0..3) is not available. 
 *    predMode is OMX_VC_4x4_HU, but availability doesn't set OMX_VC_LEFT 
 *              indicating p[-1,y] (y = 0..3) is not available. 
 *    availability sets OMX_VC_UPPER, but pSrcAbove is NULL. 
 *    availability sets OMX_VC_LEFT, but pSrcLeft is NULL. 
 *    availability sets OMX_VC_UPPER_LEFT, but pSrcAboveLeft is NULL. 
 *    either pSrcAbove or pDst is not aligned on a 4-byte boundary.  
 *
 * Note: 
 *     pSrcAbove, pSrcAbove, pSrcAboveLeft may be invalid pointers if 
 *     they are not used by intra prediction as implied in predMode. 
 *
 */
OMXResult omxVCM4P10_PredictIntra_4x4 (
    const OMX_U8 *pSrcLeft,
    const OMX_U8 *pSrcAbove,
    const OMX_U8 *pSrcAboveLeft,
    OMX_U8 *pDst,
    OMX_INT leftStep,
    OMX_INT dstStep,
    OMXVCM4P10Intra4x4PredMode predMode,
    OMX_S32 availability
);



/**
 * Function:  omxVCM4P10_PredictIntra_16x16   (6.3.3.1.2)
 *
 * Description:
 * Perform Intra_16x16 prediction for luma samples. If the upper-right block 
 * is not available, then duplication work should be handled inside the 
 * function. Users need not define them outside. 
 *
 * Input Arguments:
 *   
 *   pSrcLeft - Pointer to the buffer of 16 left pixels: p[x, y] (x = -1, y = 
 *            0..15) 
 *   pSrcAbove - Pointer to the buffer of 16 above pixels: p[x,y] (x = 0..15, 
 *            y= -1); must be aligned on a 16-byte boundary. 
 *   pSrcAboveLeft - Pointer to the above left pixels: p[x,y] (x = -1, y = -1) 
 *   leftStep - Step of left pixel buffer; must be a multiple of 16. 
 *   dstStep - Step of the destination buffer; must be a multiple of 16. 
 *   predMode - Intra_16x16 prediction mode, please refer to section 3.4.1. 
 *   availability - Neighboring 16x16 MB availability flag. Refer to 
 *                  section 3.4.4. 
 *
 * Output Arguments:
 *   
 *   pDst -Pointer to the destination buffer; must be aligned on a 16-byte 
 *            boundary. 
 *
 * Return Value:
 *    If the function runs without error, it returns OMX_Sts_NoErr. 
 *    If one of the following cases occurs, the function returns 
 *              OMX_Sts_BadArgErr: 
 *    pDst is NULL. 
 *    dstStep < 16. or dstStep is not a multiple of 16. 
 *    leftStep is not a multiple of 16. 
 *    predMode is not in the valid range of enumeration 
 *              OMXVCM4P10Intra16x16PredMode 
 *    predMode is OMX_VC_16X16_VERT, but availability doesn't set 
 *              OMX_VC_UPPER indicating p[x,-1] (x = 0..15) is not available. 
 *    predMode is OMX_VC_16X16_HOR, but availability doesn't set OMX_VC_LEFT 
 *              indicating p[-1,y] (y = 0..15) is not available. 
 *    predMode is OMX_VC_16X16_PLANE, but availability doesn't set 
 *              OMX_VC_UPPER_LEFT or OMX_VC_UPPER or OMX_VC_LEFT indicating 
 *              p[x,-1](x = 0..15), or p[-1,y] (y = 0..15), or p[-1,-1] is not 
 *              available. 
 *    availability sets OMX_VC_UPPER, but pSrcAbove is NULL. 
 *    availability sets OMX_VC_LEFT, but pSrcLeft is NULL. 
 *    availability sets OMX_VC_UPPER_LEFT, but pSrcAboveLeft is NULL. 
 *    either pSrcAbove or pDst is not aligned on a 16-byte boundary.  
 *
 * Note: 
 *     pSrcAbove, pSrcAbove, pSrcAboveLeft may be invalid pointers if 
 *     they are not used by intra prediction implied in predMode. 
 * Note: 
 *     OMX_VC_UPPER_RIGHT is not used in intra_16x16 luma prediction. 
 *
 */
OMXResult omxVCM4P10_PredictIntra_16x16 (
    const OMX_U8 *pSrcLeft,
    const OMX_U8 *pSrcAbove,
    const OMX_U8 *pSrcAboveLeft,
    OMX_U8 *pDst,
    OMX_INT leftStep,
    OMX_INT dstStep,
    OMXVCM4P10Intra16x16PredMode predMode,
    OMX_S32 availability
);



/**
 * Function:  omxVCM4P10_PredictIntraChroma_8x8   (6.3.3.1.3)
 *
 * Description:
 * Performs intra prediction for chroma samples. 
 *
 * Input Arguments:
 *   
 *   pSrcLeft - Pointer to the buffer of 8 left pixels: p[x, y] (x = -1, y= 
 *            0..7). 
 *   pSrcAbove - Pointer to the buffer of 8 above pixels: p[x,y] (x = 0..7, y 
 *            = -1); must be aligned on an 8-byte boundary. 
 *   pSrcAboveLeft - Pointer to the above left pixels: p[x,y] (x = -1, y = -1) 
 *   leftStep - Step of left pixel buffer; must be a multiple of 8. 
 *   dstStep - Step of the destination buffer; must be a multiple of 8. 
 *   predMode - Intra chroma prediction mode, please refer to section 3.4.3. 
 *   availability - Neighboring chroma block availability flag, please refer 
 *            to  "Neighboring Macroblock Availability". 
 *
 * Output Arguments:
 *   
 *   pDst - Pointer to the destination buffer; must be aligned on an 8-byte 
 *            boundary. 
 *
 * Return Value:
 *    If the function runs without error, it returns OMX_Sts_NoErr. 
 *    If any of the following cases occurs, the function returns 
 *              OMX_Sts_BadArgErr: 
 *    pDst is NULL. 
 *    dstStep < 8 or dstStep is not a multiple of 8. 
 *    leftStep is not a multiple of 8. 
 *    predMode is not in the valid range of enumeration 
 *              OMXVCM4P10IntraChromaPredMode. 
 *    predMode is OMX_VC_CHROMA_VERT, but availability doesn't set 
 *              OMX_VC_UPPER indicating p[x,-1] (x = 0..7) is not available. 
 *    predMode is OMX_VC_CHROMA_HOR, but availability doesn't set OMX_VC_LEFT 
 *              indicating p[-1,y] (y = 0..7) is not available. 
 *    predMode is OMX_VC_CHROMA_PLANE, but availability doesn't set 
 *              OMX_VC_UPPER_LEFT or OMX_VC_UPPER or OMX_VC_LEFT indicating 
 *              p[x,-1](x = 0..7), or p[-1,y] (y = 0..7), or p[-1,-1] is not 
 *              available. 
 *    availability sets OMX_VC_UPPER, but pSrcAbove is NULL. 
 *    availability sets OMX_VC_LEFT, but pSrcLeft is NULL. 
 *    availability sets OMX_VC_UPPER_LEFT, but pSrcAboveLeft is NULL. 
 *    either pSrcAbove or pDst is not aligned on a 8-byte boundary.  
 *
 *  Note: pSrcAbove, pSrcAbove, pSrcAboveLeft may be invalid pointer if 
 *  they are not used by intra prediction implied in predMode. 
 *
 *  Note: OMX_VC_UPPER_RIGHT is not used in intra chroma prediction. 
 *
 */
OMXResult omxVCM4P10_PredictIntraChroma_8x8 (
    const OMX_U8 *pSrcLeft,
    const OMX_U8 *pSrcAbove,
    const OMX_U8 *pSrcAboveLeft,
    OMX_U8 *pDst,
    OMX_INT leftStep,
    OMX_INT dstStep,
    OMXVCM4P10IntraChromaPredMode predMode,
    OMX_S32 availability
);



/**
 * Function:  omxVCM4P10_InterpolateLuma   (6.3.3.2.1)
 *
 * Description:
 * Performs quarter-pixel interpolation for inter luma MB. It is assumed that 
 * the frame is already padded when calling this function. 
 *
 * Input Arguments:
 *   
 *   pSrc - Pointer to the source reference frame buffer 
 *   srcStep - reference frame step, in bytes; must be a multiple of roi.width 
 *   dstStep - destination frame step, in bytes; must be a multiple of 
 *            roi.width 
 *   dx - Fractional part of horizontal motion vector component in 1/4 pixel 
 *            unit; valid in the range [0,3] 
 *   dy - Fractional part of vertical motion vector y component in 1/4 pixel 
 *            unit; valid in the range [0,3] 
 *   roi - Dimension of the interpolation region; the parameters roi.width and 
 *            roi.height must be equal to either 4, 8, or 16. 
 *
 * Output Arguments:
 *   
 *   pDst - Pointer to the destination frame buffer: 
 *          if roi.width==4,  4-byte alignment required 
 *          if roi.width==8,  8-byte alignment required 
 *          if roi.width==16, 16-byte alignment required 
 *
 * Return Value:
 *    If the function runs without error, it returns OMX_Sts_NoErr. 
 *    If one of the following cases occurs, the function returns 
 *              OMX_Sts_BadArgErr: 
 *    pSrc or pDst is NULL. 
 *    srcStep or dstStep < roi.width. 
 *    dx or dy is out of range [0,3]. 
 *    roi.width or roi.height is out of range {4, 8, 16}. 
 *    roi.width is equal to 4, but pDst is not 4 byte aligned. 
 *    roi.width is equal to 8 or 16, but pDst is not 8 byte aligned. 
 *    srcStep or dstStep is not a multiple of 8. 
 *
 */
OMXResult omxVCM4P10_InterpolateLuma (
    const OMX_U8 *pSrc,
    OMX_S32 srcStep,
    OMX_U8 *pDst,
    OMX_S32 dstStep,
    OMX_S32 dx,
    OMX_S32 dy,
    OMXSize roi
);



/**
 * Function:  omxVCM4P10_InterpolateChroma   (6.3.3.2.2)
 *
 * Description:
 * Performs 1/8-pixel interpolation for inter chroma MB. 
 *
 * Input Arguments:
 *   
 *   pSrc -Pointer to the source reference frame buffer 
 *   srcStep -Reference frame step in bytes 
 *   dstStep -Destination frame step in bytes; must be a multiple of 
 *            roi.width. 
 *   dx -Fractional part of horizontal motion vector component in 1/8 pixel 
 *            unit; valid in the range [0,7] 
 *   dy -Fractional part of vertical motion vector component in 1/8 pixel 
 *            unit; valid in the range [0,7] 
 *   roi -Dimension of the interpolation region; the parameters roi.width and 
 *            roi.height must be equal to either 2, 4, or 8. 
 *
 * Output Arguments:
 *   
 *   pDst -Pointer to the destination frame buffer:
 *         if roi.width==2,  2-byte alignment required 
 *         if roi.width==4,  4-byte alignment required 
 *         if roi.width==8, 8-byte alignment required 
 *
 * Return Value:
 *    If the function runs without error, it returns OMX_Sts_NoErr. 
 *    If one of the following cases occurs, the function returns 
 *              OMX_Sts_BadArgErr: 
 *    pSrc or pDst is NULL. 
 *    srcStep or dstStep < 8. 
 *    dx or dy is out of range [0-7]. 
 *    roi.width or roi.height is out of range {2,4,8}. 
 *    roi.width is equal to 2, but pDst is not 2-byte aligned. 
 *    roi.width is equal to 4, but pDst is not 4-byte aligned. 
 *    roi.width is equal to 8, but pDst is not 8 byte aligned. 
 *    srcStep or dstStep is not a multiple of 8. 
 *
 */
OMXResult omxVCM4P10_InterpolateChroma (
    const OMX_U8 *pSrc,
    OMX_S32 srcStep,
    OMX_U8 *pDst,
    OMX_S32 dstStep,
    OMX_S32 dx,
    OMX_S32 dy,
    OMXSize roi
);



/**
 * Function:  omxVCM4P10_FilterDeblockingLuma_VerEdge_I   (6.3.3.3.1)
 *
 * Description:
 * Performs in-place deblock filtering on four vertical edges of the luma 
 * macroblock (16x16). 
 *
 * Input Arguments:
 *   
 *   pSrcDst - Pointer to the input macroblock; must be 16-byte aligned. 
 *   srcdstStep -Step of the arrays; must be a multiple of 16. 
 *   pAlpha -Array of size 2 of alpha thresholds (the first item is the alpha 
 *            threshold for the external vertical edge, and the second item is 
 *            for the internal vertical edge); per [ISO14496-10] alpha values 
 *            must be in the range [0,255]. 
 *   pBeta -Array of size 2 of beta thresholds (the first item is the beta 
 *            threshold for the external vertical edge, and the second item is 
 *            for the internal vertical edge); per [ISO14496-10] beta values 
 *            must be in the range [0,18]. 
 *   pThresholds -Array of size 16 of Thresholds (TC0) (values for the left 
 *            edge of each 4x4 block, arranged in vertical block order); must 
 *            be aligned on a 4-byte boundary..  Per [ISO14496-10] values must 
 *            be in the range [0,25]. 
 *   pBS -Array of size 16 of BS parameters (arranged in vertical block 
 *            order); valid in the range [0,4] with the following 
 *            restrictions: i) pBS[i]== 4 may occur only for 0<=i<=3, ii) 
 *            pBS[i]== 4 if and only if pBS[i^3]== 4.  Must be 4-byte aligned. 
 *
 * Output Arguments:
 *   
 *   pSrcDst -Pointer to filtered output macroblock. 
 *
 * Return Value:
 *    If the function runs without error, it returns OMX_Sts_NoErr. 
 *    If one of the following cases occurs, the function returns 
 *              OMX_Sts_BadArgErr: 
 *    Either of the pointers in pSrcDst, pAlpha, pBeta, pThresholds, or pBS 
 *              is NULL. 
 *    Either pThresholds or pBS is not aligned on a 4-byte boundary. 
 *    pSrcDst is not 16-byte aligned. 
 *    srcdstStep is not a multiple of 16. 
 *    pAlpha[0] and/or pAlpha[1] is outside the range [0,255]. 
 *    pBeta[0] and/or pBeta[1] is outside the range [0,18]. 
 *    One or more entries in the table pThresholds[0..15]is outside of the 
 *              range [0,25]. 
 *    pBS is out of range, i.e., one of the following conditions is true: 
 *              pBS[i]<0, pBS[i]>4, pBS[i]==4 for i>=4, or (pBS[i]==4 && 
 *              pBS[i^3]!=4) for 0<=i<=3. 
 *
 */
OMXResult omxVCM4P10_FilterDeblockingLuma_VerEdge_I (
    OMX_U8 *pSrcDst,
    OMX_S32 srcdstStep,
    const OMX_U8 *pAlpha,
    const OMX_U8 *pBeta,
    const OMX_U8 *pThresholds,
    const OMX_U8 *pBS
);



/**
 * Function:  omxVCM4P10_FilterDeblockingLuma_HorEdge_I   (6.3.3.3.2)
 *
 * Description:
 * Performs in-place deblock filtering on four horizontal edges of the luma 
 * macroblock (16x16). 
 *
 * Input Arguments:
 *   
 *   pSrcDst - pointer to the input macroblock; must be 16-byte aligned. 
 *   srcdstStep - step of the arrays; must be a multiple of 16. 
 *   pAlpha - array of size 2 of alpha thresholds (the first item is the alpha 
 *            threshold for the external vertical edge, and the second item is 
 *            for the internal horizontal edge); per [ISO14496-10] alpha 
 *            values must be in the range [0,255]. 
 *   pBeta - array of size 2 of beta thresholds (the first item is the beta 
 *            threshold for the external horizontal edge, and the second item 
 *            is for the internal horizontal edge). Per [ISO14496-10] beta 
 *            values must be in the range [0,18]. 
 *   pThresholds - array of size 16 containing thresholds, TC0, for the top 
 *            horizontal edge of each 4x4 block, arranged in horizontal block 
 *            order; must be aligned on a 4-byte boundary.  Per [ISO14496 10] 
 *            values must be in the range [0,25]. 
 *   pBS - array of size 16 of BS parameters (arranged in horizontal block 
 *            order); valid in the range [0,4] with the following 
 *            restrictions: i) pBS[i]== 4 may occur only for 0<=i<=3, ii) 
 *            pBS[i]== 4 if and only if pBS[i^3]== 4.  Must be 4-byte aligned. 
 *
 * Output Arguments:
 *   
 *   pSrcDst -Pointer to filtered output macroblock. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr, if the function runs without error.
 * 
 *    OMX_Sts_BadArgErr, if one of the following cases occurs: 
 *    -    one or more of the following pointers is NULL: pSrcDst, pAlpha, 
 *              pBeta, pThresholds, or pBS. 
 *    -    either pThresholds or pBS is not aligned on a 4-byte boundary. 
 *    -    pSrcDst is not 16-byte aligned. 
 *    -    srcdstStep is not a multiple of 16. 
 *    -    pAlpha[0] and/or pAlpha[1] is outside the range [0,255]. 
 *    -    pBeta[0] and/or pBeta[1] is outside the range [0,18]. 
 *    -    One or more entries in the table pThresholds[0..15] is 
 *         outside of the range [0,25]. 
 *    -    pBS is out of range, i.e., one of the following conditions is true: 
 *              pBS[i]<0, pBS[i]>4, pBS[i]==4 for i>=4, or 
 *              (pBS[i]==4 && pBS[i^3]!=4) for 0<=i<=3. 
 *
 */
OMXResult omxVCM4P10_FilterDeblockingLuma_HorEdge_I (
    OMX_U8 *pSrcDst,
    OMX_S32 srcdstStep,
    const OMX_U8 *pAlpha,
    const OMX_U8 *pBeta,
    const OMX_U8 *pThresholds,
    const OMX_U8 *pBS
);



/**
 * Function:  omxVCM4P10_FilterDeblockingChroma_VerEdge_I   (6.3.3.3.3)
 *
 * Description:
 * Performs in-place deblock filtering on four vertical edges of the chroma 
 * macroblock (8x8). 
 *
 * Input Arguments:
 *   
 *   pSrcDst - Pointer to the input macroblock; must be 8-byte aligned. 
 *   srcdstStep - Step of the arrays; must be a multiple of 8. 
 *   pAlpha - Array of size 2 of alpha thresholds (the first item is alpha 
 *            threshold for external vertical edge, and the second item is for 
 *            internal vertical edge); per [ISO14496-10] alpha values must be 
 *            in the range [0,255]. 
 *   pBeta - Array of size 2 of beta thresholds (the first item is the beta 
 *            threshold for the external vertical edge, and the second item is 
 *            for the internal vertical edge); per [ISO14496-10] beta values 
 *            must be in the range [0,18]. 
 *   pThresholds - Array of size 8 containing thresholds, TC0, for the left 
 *            vertical edge of each 4x2 chroma block, arranged in vertical 
 *            block order; must be aligned on a 4-byte boundary.  Per 
 *            [ISO14496-10] values must be in the range [0,25]. 
 *   pBS - Array of size 16 of BS parameters (values for each 2x2 chroma 
 *            block, arranged in vertical block order). This parameter is the 
 *            same as the pBS parameter passed into FilterDeblockLuma_VerEdge; 
 *            valid in the range [0,4] with the following restrictions: i) 
 *            pBS[i]== 4 may occur only for 0<=i<=3, ii) pBS[i]== 4 if and 
 *            only if pBS[i^3]== 4.  Must be 4 byte aligned. 
 *
 * Output Arguments:
 *   
 *   pSrcDst -Pointer to filtered output macroblock. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr, if the function runs without error.
 * 
 *    OMX_Sts_BadArgErr - bad arguments: if one of the following cases occurs: 
 *    -    one or more of the following pointers is NULL: pSrcDst, pAlpha, 
 *              pBeta, pThresholds, or pBS. 
 *    -    pSrcDst is not 8-byte aligned. 
 *    -    srcdstStep is not a multiple of 8. 
 *    -    pThresholds is not 4-byte aligned. 
 *    -    pAlpha[0] and/or pAlpha[1] is outside the range [0,255]. 
 *    -    pBeta[0] and/or pBeta[1] is outside the range [0,18]. 
 *    -    One or more entries in the table pThresholds[0..7] is outside 
 *         of the range [0,25]. 
 *    -    pBS is out of range, i.e., one of the following conditions is true: 
 *         pBS[i]<0, pBS[i]>4, pBS[i]==4 for i>=4, or 
 *         (pBS[i]==4 && pBS[i^3]!=4) for 0<=i<=3. 
 *    -    pBS is not 4-byte aligned. 
 *
 */
OMXResult omxVCM4P10_FilterDeblockingChroma_VerEdge_I (
    OMX_U8 *pSrcDst,
    OMX_S32 srcdstStep,
    const OMX_U8 *pAlpha,
    const OMX_U8 *pBeta,
    const OMX_U8 *pThresholds,
    const OMX_U8 *pBS
);



/**
 * Function:  omxVCM4P10_FilterDeblockingChroma_HorEdge_I   (6.3.3.3.4)
 *
 * Description:
 * Performs in-place deblock filtering on the horizontal edges of the chroma 
 * macroblock (8x8). 
 *
 * Input Arguments:
 *   
 *   pSrcDst - pointer to the input macroblock; must be 8-byte aligned. 
 *   srcdstStep - array step; must be a multiple of 8. 
 *   pAlpha - array of size 2 containing alpha thresholds; the first element 
 *            contains the threshold for the external horizontal edge, and the 
 *            second element contains the threshold for internal horizontal 
 *            edge.  Per [ISO14496-10] alpha values must be in the range 
 *            [0,255]. 
 *   pBeta - array of size 2 containing beta thresholds; the first element 
 *            contains the threshold for the external horizontal edge, and the 
 *            second element contains the threshold for the internal 
 *            horizontal edge.  Per [ISO14496-10] beta values must be in the 
 *            range [0,18]. 
 *   pThresholds - array of size 8 containing thresholds, TC0, for the top 
 *            horizontal edge of each 2x4 chroma block, arranged in horizontal 
 *            block order; must be aligned on a 4-byte boundary.  Per 
 *            [ISO14496-10] values must be in the range [0,25]. 
 *   pBS - array of size 16 containing BS parameters for each 2x2 chroma 
 *            block, arranged in horizontal block order; valid in the range 
 *            [0,4] with the following restrictions: i) pBS[i]== 4 may occur 
 *            only for 0<=i<=3, ii) pBS[i]== 4 if and only if pBS[i^3]== 4. 
 *            Must be 4-byte aligned. 
 *
 * Output Arguments:
 *   
 *   pSrcDst -Pointer to filtered output macroblock. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr, if the function runs without error.
 * 
 *    OMX_Sts_BadArgErr, if one of the following cases occurs: 
 *    -    any of the following pointers is NULL: 
 *         pSrcDst, pAlpha, pBeta, pThresholds, or pBS. 
 *    -    pSrcDst is not 8-byte aligned. 
 *    -    srcdstStep is not a multiple of 8. 
 *    -    pThresholds is not 4-byte aligned. 
 *    -    pAlpha[0] and/or pAlpha[1] is outside the range [0,255]. 
 *    -    pBeta[0] and/or pBeta[1] is outside the range [0,18]. 
 *    -    One or more entries in the table pThresholds[0..7] is outside 
 *         of the range [0,25]. 
 *    -    pBS is out of range, i.e., one of the following conditions is true: 
 *              pBS[i]<0, pBS[i]>4, pBS[i]==4 for i>=4, or 
 *              (pBS[i]==4 && pBS[i^3]!=4) for 0<=i<=3.
 *    -    pBS is not 4-byte aligned. 
 *
 */
OMXResult omxVCM4P10_FilterDeblockingChroma_HorEdge_I (
    OMX_U8 *pSrcDst,
    OMX_S32 srcdstStep,
    const OMX_U8 *pAlpha,
    const OMX_U8 *pBeta,
    const OMX_U8 *pThresholds,
    const OMX_U8 *pBS
);



/**
 * Function:  omxVCM4P10_DeblockLuma_I   (6.3.3.3.5)
 *
 * Description:
 * This function performs in-place deblock filtering the horizontal and 
 * vertical edges of a luma macroblock (16x16). 
 *
 * Input Arguments:
 *   
 *   pSrcDst - pointer to the input macroblock; must be 16-byte aligned. 
 *   srcdstStep - image width; must be a multiple of 16. 
 *   pAlpha - pointer to a 2x2 table of alpha thresholds, organized as 
 *            follows: {external vertical edge, internal vertical edge, 
 *            external horizontal edge, internal horizontal edge }.  Per 
 *            [ISO14496-10] alpha values must be in the range [0,255]. 
 *   pBeta - pointer to a 2x2 table of beta thresholds, organized as follows: 
 *            {external vertical edge, internal vertical edge, external 
 *            horizontal edge, internal horizontal edge }.  Per [ISO14496-10] 
 *            beta values must be in the range [0,18]. 
 *   pThresholds - pointer to a 16x2 table of threshold (TC0), organized as 
 *            follows: {values for the left or above edge of each 4x4 block, 
 *            arranged in vertical block order and then in horizontal block 
 *            order}; must be aligned on a 4-byte boundary.  Per [ISO14496-10] 
 *            values must be in the range [0,25]. 
 *   pBS - pointer to a 16x2 table of BS parameters arranged in scan block 
 *            order for vertical edges and then horizontal edges; valid in the 
 *            range [0,4] with the following restrictions: i) pBS[i]== 4 may 
 *            occur only for 0<=i<=3, ii) pBS[i]== 4 if and only if pBS[i^3]== 
 *            4. Must be 4-byte aligned. 
 *
 * Output Arguments:
 *   
 *   pSrcDst - pointer to filtered output macroblock. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments 
 *    -     one or more of the following pointers is NULL: pSrcDst, pAlpha, 
 *              pBeta, pThresholds or pBS. 
 *    -    pSrcDst is not 16-byte aligned. 
 *    -    either pThresholds or pBS is not aligned on a 4-byte boundary. 
 *    -    one or more entries in the table pAlpha[0..3] is outside the range 
 *              [0,255]. 
 *    -    one or more entries in the table pBeta[0..3] is outside the range 
 *              [0,18]. 
 *    -    one or more entries in the table pThresholds[0..31]is outside of 
 *              the range [0,25]. 
 *    -    pBS is out of range, i.e., one of the following conditions is true: 
 *              pBS[i]<0, pBS[i]>4, pBS[i]==4 for i>=4, or 
 *             (pBS[i]==4 && pBS[i^3]!=4) for 0<=i<=3. 
 *    -    srcdstStep is not a multiple of 16. 
 *
 */
OMXResult omxVCM4P10_DeblockLuma_I (
    OMX_U8 *pSrcDst,
    OMX_S32 srcdstStep,
    const OMX_U8 *pAlpha,
    const OMX_U8 *pBeta,
    const OMX_U8 *pThresholds,
    const OMX_U8 *pBS
);



/**
 * Function:  omxVCM4P10_DeblockChroma_I   (6.3.3.3.6)
 *
 * Description:
 * Performs in-place deblocking filtering on all edges of the chroma 
 * macroblock (16x16). 
 *
 * Input Arguments:
 *   
 *   pSrcDst - pointer to the input macroblock; must be 8-byte aligned. 
 *   srcdstStep - step of the arrays; must be a multiple of 8. 
 *   pAlpha - pointer to a 2x2 array of alpha thresholds, organized as 
 *            follows: {external vertical edge, internal vertical edge, 
 *            external horizontal edge, internal horizontal edge }.  Per 
 *            [ISO14496-10] alpha values must be in the range [0,255]. 
 *   pBeta - pointer to a 2x2 array of Beta Thresholds, organized as follows: 
 *            { external vertical edge, internal vertical edge, external 
 *            horizontal edge, internal horizontal edge }.  Per [ISO14496-10] 
 *            beta values must be in the range [0,18]. 
 *   pThresholds - array of size 8x2 of Thresholds (TC0) (values for the left 
 *            or above edge of each 4x2 or 2x4 block, arranged in vertical 
 *            block order and then in horizontal block order); must be aligned 
 *            on a 4-byte boundary. Per [ISO14496-10] values must be in the 
 *            range [0,25]. 
 *   pBS - array of size 16x2 of BS parameters (arranged in scan block order 
 *            for vertical edges and then horizontal edges); valid in the 
 *            range [0,4] with the following restrictions: i) pBS[i]== 4 may 
 *            occur only for 0<=i<=3, ii) pBS[i]== 4 if and only if pBS[i^3]== 
 *            4.  Must be 4-byte aligned. 
 *
 * Output Arguments:
 *   
 *   pSrcDst - pointer to filtered output macroblock. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments 
 *    -   one or more of the following pointers is NULL: pSrcDst, pAlpha, 
 *              pBeta, pThresholds, or pBS. 
 *    -   pSrcDst is not 8-byte aligned. 
 *    -   either pThresholds or pBS is not 4-byte aligned. 
 *    -   one or more entries in the table pAlpha[0..3] is outside the range 
 *              [0,255]. 
 *    -   one or more entries in the table pBeta[0..3] is outside the range 
 *              [0,18]. 
 *    -   one or more entries in the table pThresholds[0..15]is outside of 
 *              the range [0,25]. 
 *    -   pBS is out of range, i.e., one of the following conditions is true: 
 *            pBS[i]<0, pBS[i]>4, pBS[i]==4  for i>=4, or 
 *            (pBS[i]==4 && pBS[i^3]!=4) for 0<=i<=3. 
 *    -   srcdstStep is not a multiple of 8. 
 *
 */
OMXResult omxVCM4P10_DeblockChroma_I (
    OMX_U8 *pSrcDst,
    OMX_S32 srcdstStep,
    const OMX_U8 *pAlpha,
    const OMX_U8 *pBeta,
    const OMX_U8 *pThresholds,
    const OMX_U8 *pBS
);



/**
 * Function:  omxVCM4P10_DecodeChromaDcCoeffsToPairCAVLC   (6.3.4.1.1)
 *
 * Description:
 * Performs CAVLC decoding and inverse raster scan for a 2x2 block of 
 * ChromaDCLevel.  The decoded coefficients in the packed position-coefficient 
 * buffer are stored in reverse zig-zag order, i.e., the first buffer element 
 * contains the last non-zero postion-coefficient pair of the block. Within 
 * each position-coefficient pair, the position entry indicates the 
 * raster-scan position of the coefficient, while the coefficient entry 
 * contains the coefficient value. 
 *
 * Input Arguments:
 *   
 *   ppBitStream - Double pointer to current byte in bit stream buffer 
 *   pOffset - Pointer to current bit position in the byte pointed to by 
 *            *ppBitStream; valid in the range [0,7]. 
 *
 * Output Arguments:
 *   
 *   ppBitStream - *ppBitStream is updated after each block is decoded 
 *   pOffset - *pOffset is updated after each block is decoded 
 *   pNumCoeff - Pointer to the number of nonzero coefficients in this block 
 *   ppPosCoefBuf - Double pointer to destination residual 
 *            coefficient-position pair buffer.  Buffer position 
 *            (*ppPosCoefBuf) is updated upon return, unless there are only 
 *            zero coefficients in the currently decoded block.  In this case 
 *            the caller is expected to bypass the transform/dequantization of 
 *            the empty blocks. 
 *
 * Return Value:
 *
 *    OMX_Sts_NoErr, if the function runs without error.
 * 
 *    OMX_Sts_BadArgErr - bad arguments: if one of the following cases occurs: 
 *    -    ppBitStream or pOffset is NULL. 
 *    -    ppPosCoefBuf or pNumCoeff is NULL. 
 *    OMX_Sts_Err - if one of the following is true: 
 *    -    an illegal code is encountered in the bitstream 
 *
 */
OMXResult omxVCM4P10_DecodeChromaDcCoeffsToPairCAVLC (
    const OMX_U8 **ppBitStream,
    OMX_S32*pOffset,
    OMX_U8 *pNumCoeff,
    OMX_U8 **ppPosCoefbuf
);



/**
 * Function:  omxVCM4P10_DecodeCoeffsToPairCAVLC   (6.3.4.1.2)
 *
 * Description:
 * Performs CAVLC decoding and inverse zigzag scan for 4x4 block of 
 * Intra16x16DCLevel, Intra16x16ACLevel, LumaLevel, and ChromaACLevel. Inverse 
 * field scan is not supported. The decoded coefficients in the packed 
 * position-coefficient buffer are stored in reverse zig-zag order, i.e., the 
 * first buffer element contains the last non-zero postion-coefficient pair of 
 * the block. Within each position-coefficient pair, the position entry 
 * indicates the raster-scan position of the coefficient, while the 
 * coefficient entry contains the coefficient value. 
 *
 * Input Arguments:
 *   
 *   ppBitStream -Double pointer to current byte in bit stream buffer 
 *   pOffset - Pointer to current bit position in the byte pointed to by 
 *            *ppBitStream; valid in the range [0,7]. 
 *   sMaxNumCoeff - Maximum the number of non-zero coefficients in current 
 *            block 
 *   sVLCSelect - VLC table selector, obtained from the number of non-zero 
 *            coefficients contained in the above and left 4x4 blocks.  It is 
 *            equivalent to the variable nC described in H.264 standard table 
 *            9 5, except its value can t be less than zero. 
 *
 * Output Arguments:
 *   
 *   ppBitStream - *ppBitStream is updated after each block is decoded.  
 *            Buffer position (*ppPosCoefBuf) is updated upon return, unless 
 *            there are only zero coefficients in the currently decoded block. 
 *             In this case the caller is expected to bypass the 
 *            transform/dequantization of the empty blocks. 
 *   pOffset - *pOffset is updated after each block is decoded 
 *   pNumCoeff - Pointer to the number of nonzero coefficients in this block 
 *   ppPosCoefBuf - Double pointer to destination residual 
 *            coefficient-position pair buffer 
 *
 * Return Value:
 *    OMX_Sts_NoErr, if the function runs without error.
 * 
 *    OMX_Sts_BadArgErr - bad arguments: if one of the following cases occurs: 
 *    -    ppBitStream or pOffset is NULL. 
 *    -    ppPosCoefBuf or pNumCoeff is NULL. 
 *    -    sMaxNumCoeff is not equal to either 15 or 16. 
 *    -    sVLCSelect is less than 0. 
 *
 *    OMX_Sts_Err - if one of the following is true: 
 *    -    an illegal code is encountered in the bitstream 
 *
 */
OMXResult omxVCM4P10_DecodeCoeffsToPairCAVLC (
    const OMX_U8 **ppBitStream,
    OMX_S32 *pOffset,
    OMX_U8 *pNumCoeff,
    OMX_U8 **ppPosCoefbuf,
    OMX_INT sVLCSelect,
    OMX_INT sMaxNumCoeff
);



/**
 * Function:  omxVCM4P10_TransformDequantLumaDCFromPair   (6.3.4.2.1)
 *
 * Description:
 * Reconstructs the 4x4 LumaDC block from the coefficient-position pair 
 * buffer, performs integer inverse, and dequantization for 4x4 LumaDC 
 * coefficients, and updates the pair buffer pointer to the next non-empty 
 * block. 
 *
 * Input Arguments:
 *   
 *   ppSrc - Double pointer to residual coefficient-position pair buffer 
 *            output by CALVC decoding 
 *   QP - Quantization parameter QpY 
 *
 * Output Arguments:
 *   
 *   ppSrc - *ppSrc is updated to the start of next non empty block 
 *   pDst - Pointer to the reconstructed 4x4 LumaDC coefficients buffer; must 
 *            be aligned on a 8-byte boundary. 
 *
 * Return Value:
 *    OMX_Sts_NoErr, if the function runs without error.
 *    OMX_Sts_BadArgErr - bad arguments: if one of the following cases occurs: 
 *    -    ppSrc or pDst is NULL. 
 *    -    pDst is not 8 byte aligned. 
 *    -    QP is not in the range of [0-51]. 
 *
 */
OMXResult omxVCM4P10_TransformDequantLumaDCFromPair (
    const OMX_U8 **ppSrc,
    OMX_S16 *pDst,
    OMX_INT QP
);



/**
 * Function:  omxVCM4P10_TransformDequantChromaDCFromPair   (6.3.4.2.2)
 *
 * Description:
 * Reconstruct the 2x2 ChromaDC block from coefficient-position pair buffer, 
 * perform integer inverse transformation, and dequantization for 2x2 chroma 
 * DC coefficients, and update the pair buffer pointer to next non-empty 
 * block. 
 *
 * Input Arguments:
 *   
 *   ppSrc - Double pointer to residual coefficient-position pair buffer 
 *            output by CALVC decoding 
 *   QP - Quantization parameter QpC 
 *
 * Output Arguments:
 *   
 *   ppSrc - *ppSrc is updated to the start of next non empty block 
 *   pDst - Pointer to the reconstructed 2x2 ChromaDC coefficients buffer; 
 *            must be aligned on a 4-byte boundary. 
 *
 * Return Value:
 *    OMX_Sts_NoErr, if the function runs without error.
 *    OMX_Sts_BadArgErr - bad arguments: if one of the following cases occurs: 
 *    -    ppSrc or pDst is NULL. 
 *    -    pDst is not 4-byte aligned. 
 *    -    QP is not in the range of [0-51]. 
 *
 */
OMXResult omxVCM4P10_TransformDequantChromaDCFromPair (
    const OMX_U8 **ppSrc,
    OMX_S16 *pDst,
    OMX_INT QP
);



/**
 * Function:  omxVCM4P10_DequantTransformResidualFromPairAndAdd   (6.3.4.2.3)
 *
 * Description:
 * Reconstruct the 4x4 residual block from coefficient-position pair buffer, 
 * perform dequantization and integer inverse transformation for 4x4 block of 
 * residuals with previous intra prediction or motion compensation data, and 
 * update the pair buffer pointer to next non-empty block. If pDC == NULL, 
 * there re 16 non-zero AC coefficients at most in the packed buffer starting 
 * from 4x4 block position 0; If pDC != NULL, there re 15 non-zero AC 
 * coefficients at most in the packet buffer starting from 4x4 block position 
 * 1. 
 *
 * Input Arguments:
 *   
 *   ppSrc - Double pointer to residual coefficient-position pair buffer 
 *            output by CALVC decoding 
 *   pPred - Pointer to the predicted 4x4 block; must be aligned on a 4-byte 
 *            boundary 
 *   predStep - Predicted frame step size in bytes; must be a multiple of 4 
 *   dstStep - Destination frame step in bytes; must be a multiple of 4 
 *   pDC - Pointer to the DC coefficient of this block, NULL if it doesn't 
 *            exist 
 *   QP - QP Quantization parameter.  It should be QpC in chroma 4x4 block 
 *            decoding, otherwise it should be QpY. 
 *   AC - Flag indicating if at least one non-zero AC coefficient exists 
 *
 * Output Arguments:
 *   
 *   pDst - pointer to the reconstructed 4x4 block data; must be aligned on a 
 *            4-byte boundary 
 *
 * Return Value:
 *    OMX_Sts_NoErr, if the function runs without error.
 *    OMX_Sts_BadArgErr - bad arguments: if one of the following cases occurs: 
 *    -    pPred or pDst is NULL. 
 *    -    pPred or pDst is not 4-byte aligned. 
 *    -    predStep or dstStep is not a multiple of 4. 
 *    -    AC !=0 and Qp is not in the range of [0-51] or ppSrc == NULL. 
 *    -    AC ==0 && pDC ==NULL. 
 *
 */
OMXResult omxVCM4P10_DequantTransformResidualFromPairAndAdd (
    const OMX_U8 **ppSrc,
    const OMX_U8 *pPred,
    const OMX_S16 *pDC,
    OMX_U8 *pDst,
    OMX_INT predStep,
    OMX_INT dstStep,
    OMX_INT QP,
    OMX_INT AC
);



/**
 * Function:  omxVCM4P10_MEGetBufSize   (6.3.5.1.1)
 *
 * Description:
 * Computes the size, in bytes, of the vendor-specific specification 
 * structure for the omxVCM4P10 motion estimation functions BlockMatch_Integer 
 * and MotionEstimationMB. 
 *
 * Input Arguments:
 *   
 *   MEmode - motion estimation mode; available modes are defined by the 
 *            enumerated type OMXVCM4P10MEMode 
 *   pMEParams -motion estimation parameters 
 *
 * Output Arguments:
 *   
 *   pSize - pointer to the number of bytes required for the motion 
 *            estimation specification structure 
 *
 * Return Value:
 *    OMX_Sts_NoErr, if the function runs without error.
 *    OMX_Sts_BadArgErr - bad arguments: if one of the following cases occurs: 
 *    -    pMEParams or pSize is NULL. 
 *    -    an invalid MEMode is specified. 
 *
 */
OMXResult omxVCM4P10_MEGetBufSize (
    OMXVCM4P10MEMode MEmode,
    const OMXVCM4P10MEParams *pMEParams,
    OMX_U32 *pSize
);



/**
 * Function:  omxVCM4P10_MEInit   (6.3.5.1.2)
 *
 * Description:
 * Initializes the vendor-specific specification structure required for the 
 * omxVCM4P10 motion estimation functions:  BlockMatch_Integer and 
 * MotionEstimationMB. Memory for the specification structure *pMESpec must be 
 * allocated prior to calling the function, and should be aligned on a 4-byte 
 * boundary.  The number of bytes required for the specification structure can 
 * be determined using the function omxVCM4P10_MEGetBufSize. Following 
 * initialization by this function, the vendor-specific structure *pMESpec 
 * should contain an implementation-specific representation of all motion 
 * estimation parameters received via the structure pMEParams, for example  
 * searchRange16x16, searchRange8x8, etc. 
 *
 * Input Arguments:
 *   
 *   MEmode - motion estimation mode; available modes are defined by the 
 *            enumerated type OMXVCM4P10MEMode 
 *   pMEParams - motion estimation parameters 
 *   pMESpec - pointer to the uninitialized ME specification structure 
 *
 * Output Arguments:
 *   
 *   pMESpec - pointer to the initialized ME specification structure 
 *
 * Return Value:
 *    OMX_Sts_NoErr, if the function runs without error.
 *    OMX_Sts_BadArgErr - bad arguments: if one of the following cases occurs: 
 *    -    pMEParams or pSize is NULL. 
 *    -    an invalid value was specified for the parameter MEmode 
 *    -    a negative or zero value was specified for one of the search ranges 
 *         (e.g.,  pMBParams >searchRange8x8, pMEParams->searchRange16x16, etc.) 
 *    -    either in isolation or in combination, one or more of the enables or 
 *         search ranges in the structure *pMEParams were configured such 
 *         that the requested behavior fails to comply with [ISO14496-10]. 
 *
 */
OMXResult omxVCM4P10_MEInit (
    OMXVCM4P10MEMode MEmode,
    const OMXVCM4P10MEParams *pMEParams,
    void *pMESpec
);



/**
 * Function:  omxVCM4P10_BlockMatch_Integer   (6.3.5.2.1)
 *
 * Description:
 * Performs integer block match.  Returns best MV and associated cost. 
 *
 * Input Arguments:
 *   
 *   pSrcOrgY - Pointer to the top-left corner of the current block:
 *            If iBlockWidth==4,  4-byte alignment required. 
 *            If iBlockWidth==8,  8-byte alignment required. 
 *            If iBlockWidth==16, 16-byte alignment required. 
 *   pSrcRefY - Pointer to the top-left corner of the co-located block in the 
 *            reference picture: 
 *            If iBlockWidth==4,  4-byte alignment required.  
 *            If iBlockWidth==8,  8-byte alignment required.  
 *            If iBlockWidth==16, 16-byte alignment required. 
 *   nSrcOrgStep - Stride of the original picture plane, expressed in terms 
 *            of integer pixels; must be a multiple of iBlockWidth. 
 *   nSrcRefStep - Stride of the reference picture plane, expressed in terms 
 *            of integer pixels 
 *   pRefRect - pointer to the valid reference rectangle inside the reference 
 *            picture plane 
 *   nCurrPointPos - position of the current block in the current plane 
 *   iBlockWidth - Width of the current block, expressed in terms of integer 
 *            pixels; must be equal to either 4, 8, or 16. 
 *   iBlockHeight - Height of the current block, expressed in terms of 
 *            integer pixels; must be equal to either 4, 8, or 16. 
 *   nLamda - Lamda factor; used to compute motion cost 
 *   pMVPred - Predicted MV; used to compute motion cost, expressed in terms 
 *            of 1/4-pel units 
 *   pMVCandidate - Candidate MV; used to initialize the motion search, 
 *            expressed in terms of integer pixels 
 *   pMESpec - pointer to the ME specification structure 
 *
 * Output Arguments:
 *   
 *   pDstBestMV - Best MV resulting from integer search, expressed in terms 
 *            of 1/4-pel units 
 *   pBestCost - Motion cost associated with the best MV; computed as 
 *            SAD+Lamda*BitsUsedByMV 
 *
 * Return Value:
 *    OMX_Sts_NoErr, if the function runs without error.
 *    OMX_Sts_BadArgErr - bad arguments: if one of the following cases occurs: 
 *    -    any of the following pointers are NULL:
 *         pSrcOrgY, pSrcRefY, pRefRect, pMVPred, pMVCandidate, or pMESpec. 
 *    -    Either iBlockWidth or iBlockHeight are values other than 4, 8, or 16. 
 *    -    Any alignment restrictions are violated 
 *
 */
OMXResult omxVCM4P10_BlockMatch_Integer (
    const OMX_U8 *pSrcOrgY,
    OMX_S32 nSrcOrgStep,
    const OMX_U8 *pSrcRefY,
    OMX_S32 nSrcRefStep,
    const OMXRect *pRefRect,
    const OMXVCM4P2Coordinate *pCurrPointPos,
    OMX_U8 iBlockWidth,
    OMX_U8 iBlockHeight,
    OMX_U32 nLamda,
    const OMXVCMotionVector *pMVPred,
    const OMXVCMotionVector *pMVCandidate,
    OMXVCMotionVector *pBestMV,
    OMX_S32 *pBestCost,
    void *pMESpec
);



/**
 * Function:  omxVCM4P10_BlockMatch_Half   (6.3.5.2.2)
 *
 * Description:
 * Performs a half-pel block match using results from a prior integer search. 
 *  Returns the best MV and associated cost.  This function estimates the 
 * half-pixel motion vector by interpolating the integer resolution motion 
 * vector referenced by the input parameter pSrcDstBestMV, i.e., the initial 
 * integer MV is generated externally.  The function 
 * omxVCM4P10_BlockMatch_Integer may be used for integer motion estimation. 
 *
 * Input Arguments:
 *   
 *   pSrcOrgY - Pointer to the current position in original picture plane:
 *              If iBlockWidth==4,  4-byte alignment required. 
 *              If iBlockWidth==8,  8-byte alignment required. 
 *              If iBlockWidth==16, 16-byte alignment required. 
 *   pSrcRefY - Pointer to the top-left corner of the co-located block in the 
 *            reference picture:  
 *              If iBlockWidth==4,  4-byte alignment required.  
 *              If iBlockWidth==8,  8-byte alignment required.  
 *              If iBlockWidth==16, 16-byte alignment required. 
 *   nSrcOrgStep - Stride of the original picture plane in terms of full 
 *            pixels; must be a multiple of iBlockWidth. 
 *   nSrcRefStep - Stride of the reference picture plane in terms of full 
 *            pixels 
 *   iBlockWidth - Width of the current block in terms of full pixels; must 
 *            be equal to either 4, 8, or 16. 
 *   iBlockHeight - Height of the current block in terms of full pixels; must 
 *            be equal to either 4, 8, or 16. 
 *   nLamda - Lamda factor, used to compute motion cost 
 *   pMVPred - Predicted MV, represented in terms of 1/4-pel units; used to 
 *            compute motion cost 
 *   pSrcDstBestMV - The best MV resulting from a prior integer search, 
 *            represented in terms of 1/4-pel units 
 *
 * Output Arguments:
 *   
 *   pSrcDstBestMV - Best MV resulting from the half-pel search, expressed in 
 *            terms of 1/4-pel units 
 *   pBestCost - Motion cost associated with the best MV; computed as 
 *            SAD+Lamda*BitsUsedByMV 
 *
 * Return Value:
 *    OMX_Sts_NoErr, if the function runs without error.
 *    OMX_Sts_BadArgErr - bad arguments: if one of the following cases occurs: 
 *    -    any of the following pointers is NULL: pSrcOrgY, pSrcRefY, 
 *              pSrcDstBestMV, pMVPred, pBestCost 
 *    -    iBlockWidth or iBlockHeight are equal to values other than 4, 8, or 16. 
 *    -    Any alignment restrictions are violated 
 *
 */
OMXResult omxVCM4P10_BlockMatch_Half (
    const OMX_U8 *pSrcOrgY,
    OMX_S32 nSrcOrgStep,
    const OMX_U8 *pSrcRefY,
    OMX_S32 nSrcRefStep,
    OMX_U8 iBlockWidth,
    OMX_U8 iBlockHeight,
    OMX_U32 nLamda,
    const OMXVCMotionVector *pMVPred,
    OMXVCMotionVector *pSrcDstBestMV,
    OMX_S32 *pBestCost
);



/**
 * Function:  omxVCM4P10_BlockMatch_Quarter   (6.3.5.2.3)
 *
 * Description:
 * Performs a quarter-pel block match using results from a prior half-pel 
 * search.  Returns the best MV and associated cost.  This function estimates 
 * the quarter-pixel motion vector by interpolating the half-pel resolution 
 * motion vector referenced by the input parameter pSrcDstBestMV, i.e., the 
 * initial half-pel MV is generated externally.  The function 
 * omxVCM4P10_BlockMatch_Half may be used for half-pel motion estimation. 
 *
 * Input Arguments:
 *   
 *   pSrcOrgY - Pointer to the current position in original picture plane:
 *            If iBlockWidth==4,  4-byte alignment required. 
 *            If iBlockWidth==8,  8-byte alignment required. 
 *            If iBlockWidth==16, 16-byte alignment required. 
 *   pSrcRefY - Pointer to the top-left corner of the co-located block in the 
 *            reference picture:
 *            If iBlockWidth==4,  4-byte alignment required.  
 *            If iBlockWidth==8,  8-byte alignment required.  
 *            If iBlockWidth==16, 16-byte alignment required. 
 *   nSrcOrgStep - Stride of the original picture plane in terms of full 
 *            pixels; must be a multiple of iBlockWidth. 
 *   nSrcRefStep - Stride of the reference picture plane in terms of full 
 *            pixels 
 *   iBlockWidth - Width of the current block in terms of full pixels; must 
 *            be equal to either 4, 8, or 16. 
 *   iBlockHeight - Height of the current block in terms of full pixels; must 
 *            be equal to either 4, 8, or 16. 
 *   nLamda - Lamda factor, used to compute motion cost 
 *   pMVPred - Predicted MV, represented in terms of 1/4-pel units; used to 
 *            compute motion cost 
 *   pSrcDstBestMV - The best MV resulting from a prior half-pel search, 
 *            represented in terms of 1/4 pel units 
 *
 * Output Arguments:
 *   
 *   pSrcDstBestMV - Best MV resulting from the quarter-pel search, expressed 
 *            in terms of 1/4-pel units 
 *   pBestCost - Motion cost associated with the best MV; computed as 
 *            SAD+Lamda*BitsUsedByMV 
 *
 * Return Value:
 *    OMX_Sts_NoErr, if the function runs without error.
 *    OMX_Sts_BadArgErr - bad arguments: if one of the following cases occurs: 
 *    -    One or more of the following pointers is NULL: 
 *         pSrcOrgY, pSrcRefY, pSrcDstBestMV, pMVPred, pBestCost 
 *    -    iBlockWidth or iBlockHeight are equal to values other than 4, 8, or 16. 
 *    -    Any alignment restrictions are violated 
 *
 */
OMXResult omxVCM4P10_BlockMatch_Quarter (
    const OMX_U8 *pSrcOrgY,
    OMX_S32 nSrcOrgStep,
    const OMX_U8 *pSrcRefY,
    OMX_S32 nSrcRefStep,
    OMX_U8 iBlockWidth,
    OMX_U8 iBlockHeight,
    OMX_U32 nLamda,
    const OMXVCMotionVector *pMVPred,
    OMXVCMotionVector *pSrcDstBestMV,
    OMX_S32 *pBestCost
);



/**
 * Function:  omxVCM4P10_MotionEstimationMB   (6.3.5.3.1)
 *
 * Description:
 * Performs MB-level motion estimation and selects best motion estimation 
 * strategy from the set of modes supported in baseline profile [ISO14496-10]. 
 *
 * Input Arguments:
 *   
 *   pSrcCurrBuf - Pointer to the current position in original picture plane; 
 *            16-byte alignment required 
 *   pSrcRefBufList - Pointer to an array with 16 entries.  Each entry points 
 *            to the top-left corner of the co-located MB in a reference 
 *            picture.  The array is filled from low-to-high with valid 
 *            reference frame pointers; the unused high entries should be set 
 *            to NULL.  Ordering of the reference frames should follow 
 *            [ISO14496-10] subclause 8.2.4  Decoding Process for Reference 
 *            Picture Lists.   The entries must be 16-byte aligned. 
 *   pSrcRecBuf - Pointer to the top-left corner of the co-located MB in the 
 *            reconstructed picture; must be 16-byte aligned. 
 *   SrcCurrStep - Width of the original picture plane in terms of full 
 *            pixels; must be a multiple of 16. 
 *   SrcRefStep - Width of the reference picture plane in terms of full 
 *            pixels; must be a multiple of 16. 
 *   SrcRecStep - Width of the reconstructed picture plane in terms of full 
 *            pixels; must be a multiple of 16. 
 *   pRefRect - Pointer to the valid reference rectangle; relative to the 
 *            image origin. 
 *   pCurrPointPos - Position of the current macroblock in the current plane. 
 *   Lambda - Lagrange factor for computing the cost function 
 *   pMESpec - Pointer to the motion estimation specification structure; must 
 *            have been allocated and initialized prior to calling this 
 *            function. 
 *   pMBInter - Array, of dimension four, containing pointers to information 
 *            associated with four adjacent type INTER MBs (Left, Top, 
 *            Top-Left, Top-Right). Any pointer in the array may be set equal 
 *            to NULL if the corresponding MB doesn t exist or is not of type 
 *            INTER. 
 *            -  pMBInter[0] - Pointer to left MB information 
 *            -  pMBInter[1] - Pointer to top MB information 
 *            -  pMBInter[2] - Pointer to top-left MB information 
 *            -  pMBInter[3] - Pointer to top-right MB information 
 *   pMBIntra - Array, of dimension four, containing pointers to information 
 *            associated with four adjacent type INTRA MBs (Left, Top, 
 *            Top-Left, Top-Right). Any pointer in the array may be set equal 
 *            to NULL if the corresponding MB doesn t exist or is not of type 
 *            INTRA. 
 *            -  pMBIntra[0] - Pointer to left MB information 
 *            -  pMBIntra[1] - Pointer to top MB information 
 *            -  pMBIntra[2] - Pointer to top-left MB information 
 *            -  pMBIntra[3] - Pointer to top-right MB information 
 *   pSrcDstMBCurr - Pointer to information structure for the current MB.  
 *            The following entries should be set prior to calling the 
 *            function:  sliceID - the number of the slice the to which the 
 *            current MB belongs. 
 *
 * Output Arguments:
 *   
 *   pDstCost - Pointer to the minimum motion cost for the current MB. 
 *   pDstBlockSAD - Pointer to the array of SADs for each of the sixteen luma 
 *            4x4 blocks in each MB.  The block SADs are in scan order for 
 *            each MB.  For implementations that cannot compute the SAD values 
 *            individually, the maximum possible value (0xffff) is returned 
 *            for each of the 16 block SAD entries. 
 *   pSrcDstMBCurr - Pointer to updated information structure for the current 
 *            MB after MB-level motion estimation has been completed.  The 
 *            following fields are updated by the ME function.   The following 
 *            parameter set quantifies the MB-level ME search results: 
 *            -  MbType 
 *            -  subMBType[4] 
 *            -  pMV0[4][4] 
 *            -  pMVPred[4][4] 
 *            -  pRefL0Idx[4] 
 *            -  Intra16x16PredMode 
 *            -  pIntra4x4PredMode[4][4] 
 *
 * Return Value:
 *    OMX_Sts_NoErr, if the function runs without error.
 *    OMX_Sts_BadArgErr - bad arguments: if one of the following cases occurs: 
 *    -   One or more of the following pointers is NULL: pSrcCurrBuf, 
 *           pSrcRefBufList, pSrcRecBuf, pRefRect, pCurrPointPos, pMESpec, 
 *           pMBInter, pMBIntra,pSrcDstMBCurr, pDstCost, pSrcRefBufList[0] 
 *    -    SrcRefStep, SrcRecStep are not multiples of 16 
 *    -    iBlockWidth or iBlockHeight are values other than 4, 8, or 16. 
 *    -    Any alignment restrictions are violated 
 *
 */
OMXResult omxVCM4P10_MotionEstimationMB (
    const OMX_U8 *pSrcCurrBuf,
    OMX_S32 SrcCurrStep,
    const OMX_U8 *pSrcRefBufList[15],
    OMX_S32 SrcRefStep,
    const OMX_U8 *pSrcRecBuf,
    OMX_S32 SrcRecStep,
    const OMXRect *pRefRect,
    const OMXVCM4P2Coordinate *pCurrPointPos,
    OMX_U32 Lambda,
    void *pMESpec,
    const OMXVCM4P10MBInfoPtr *pMBInter,
    const OMXVCM4P10MBInfoPtr *pMBIntra,
    OMXVCM4P10MBInfoPtr pSrcDstMBCurr,
    OMX_INT *pDstCost,
    OMX_U16 *pDstBlockSAD
);



/**
 * Function:  omxVCM4P10_SAD_4x   (6.3.5.4.1)
 *
 * Description:
 * This function calculates the SAD for 4x8 and 4x4 blocks. 
 *
 * Input Arguments:
 *   
 *   pSrcOrg -Pointer to the original block; must be aligned on a 4-byte 
 *            boundary. 
 *   iStepOrg -Step of the original block buffer; must be a multiple of 4. 
 *   pSrcRef -Pointer to the reference block 
 *   iStepRef -Step of the reference block buffer 
 *   iHeight -Height of the block; must be equal to either 4 or 8. 
 *
 * Output Arguments:
 *   
 *   pDstSAD -Pointer of result SAD 
 *
 * Return Value:
 *    OMX_Sts_NoErr, if the function runs without error.
 *    OMX_Sts_BadArgErr - bad arguments: if one of the following cases occurs: 
 *    -    One or more of the following pointers is NULL: 
 *         pSrcOrg, pSrcRef, or pDstSAD 
 *    -    iHeight is not equal to either 4 or 8. 
 *    -    iStepOrg is not a multiple of 4 
 *    -    Any alignment restrictions are violated 
 *
 */
OMXResult omxVCM4P10_SAD_4x (
    const OMX_U8 *pSrcOrg,
    OMX_U32 iStepOrg,
    const OMX_U8 *pSrcRef,
    OMX_U32 iStepRef,
    OMX_S32 *pDstSAD,
    OMX_U32 iHeight
);



/**
 * Function:  omxVCM4P10_SADQuar_4x   (6.3.5.4.2)
 *
 * Description:
 * This function calculates the SAD between one block (pSrc) and the average 
 * of the other two (pSrcRef0 and pSrcRef1) for 4x8 or 4x4 blocks.  Rounding 
 * is applied according to the convention (a+b+1)>>1. 
 *
 * Input Arguments:
 *   
 *   pSrc - Pointer to the original block; must be aligned on a 4-byte 
 *            boundary. 
 *   pSrcRef0 - Pointer to reference block 0 
 *   pSrcRef1 - Pointer to reference block 1 
 *   iSrcStep - Step of the original block buffer; must be a multiple of 4. 
 *   iRefStep0 - Step of reference block 0 
 *   iRefStep1 - Step of reference block 1 
 *   iHeight - Height of the block; must be equal to either 4 or 8. 
 *
 * Output Arguments:
 *   
 *   pDstSAD - Pointer of result SAD 
 *
 * Return Value:
 *    OMX_Sts_NoErr, if the function runs without error.
 *    OMX_Sts_BadArgErr - bad arguments: if one of the following cases occurs: 
 *    -    iHeight is not equal to either 4 or 8. 
 *    -    One or more of the following pointers is NULL: pSrc, pSrcRef0, 
 *              pSrcRef1, pDstSAD. 
 *    -    iSrcStep is not a multiple of 4 
 *    -    Any alignment restrictions are violated 
 *
 */
OMXResult omxVCM4P10_SADQuar_4x (
    const OMX_U8 *pSrc,
    const OMX_U8 *pSrcRef0,
    const OMX_U8 *pSrcRef1,
    OMX_U32 iSrcStep,
    OMX_U32 iRefStep0,
    OMX_U32 iRefStep1,
    OMX_U32 *pDstSAD,
    OMX_U32 iHeight
);



/**
 * Function:  omxVCM4P10_SADQuar_8x   (6.3.5.4.3)
 *
 * Description:
 * This function calculates the SAD between one block (pSrc) and the average 
 * of the other two (pSrcRef0 and pSrcRef1) for 8x16, 8x8, or 8x4 blocks.  
 * Rounding is applied according to the convention (a+b+1)>>1. 
 *
 * Input Arguments:
 *   
 *   pSrc - Pointer to the original block; must be aligned on an 8-byte 
 *            boundary. 
 *   pSrcRef0 - Pointer to reference block 0 
 *   pSrcRef1 - Pointer to reference block 1 
 *   iSrcStep - Step of the original block buffer; must be a multiple of 8. 
 *   iRefStep0 - Step of reference block 0 
 *   iRefStep1 - Step of reference block 1 
 *   iHeight - Height of the block; must be equal either 4, 8, or 16. 
 *
 * Output Arguments:
 *   
 *   pDstSAD - Pointer of result SAD 
 *
 * Return Value:
 *    OMX_Sts_NoErr, if the function runs without error.
 *    OMX_Sts_BadArgErr - bad arguments: if one of the following cases occurs: 
 *    -    iHeight is not equal to either 4, 8, or 16. 
 *    -    One or more of the following pointers is NULL: pSrc, pSrcRef0, 
 *              pSrcRef1, pDstSAD. 
 *    -    iSrcStep is not a multiple of 8 
 *    -    Any alignment restrictions are violated 
 *
 */
OMXResult omxVCM4P10_SADQuar_8x (
    const OMX_U8 *pSrc,
    const OMX_U8 *pSrcRef0,
    const OMX_U8 *pSrcRef1,
    OMX_U32 iSrcStep,
    OMX_U32 iRefStep0,
    OMX_U32 iRefStep1,
    OMX_U32 *pDstSAD,
    OMX_U32 iHeight
);



/**
 * Function:  omxVCM4P10_SADQuar_16x   (6.3.5.4.4)
 *
 * Description:
 * This function calculates the SAD between one block (pSrc) and the average 
 * of the other two (pSrcRef0 and pSrcRef1) for 16x16 or 16x8 blocks.  
 * Rounding is applied according to the convention (a+b+1)>>1. 
 *
 * Input Arguments:
 *   
 *   pSrc - Pointer to the original block; must be aligned on a 16-byte 
 *            boundary. 
 *   pSrcRef0 - Pointer to reference block 0 
 *   pSrcRef1 - Pointer to reference block 1 
 *   iSrcStep - Step of the original block buffer; must be a multiple of 16 
 *   iRefStep0 - Step of reference block 0 
 *   iRefStep1 - Step of reference block 1 
 *   iHeight - Height of the block; must be equal to either 8 or 16 
 *
 * Output Arguments:
 *   
 *   pDstSAD -Pointer of result SAD 
 *
 * Return Value:
 *    OMX_Sts_NoErr, if the function runs without error.
 *    OMX_Sts_BadArgErr - bad arguments: if one of the following cases occurs: 
 *    -    iHeight is not equal to either 8 or 16. 
 *    -    One or more of the following pointers is NULL: pSrc, pSrcRef0, 
 *              pSrcRef1, pDstSAD. 
 *    -    iSrcStep is not a multiple of 16 
 *    -    Any alignment restrictions are violated 
 *
 */
OMXResult omxVCM4P10_SADQuar_16x (
    const OMX_U8 *pSrc,
    const OMX_U8 *pSrcRef0,
    const OMX_U8 *pSrcRef1,
    OMX_U32 iSrcStep,
    OMX_U32 iRefStep0,
    OMX_U32 iRefStep1,
    OMX_U32 *pDstSAD,
    OMX_U32 iHeight
);



/**
 * Function:  omxVCM4P10_SATD_4x4   (6.3.5.4.5)
 *
 * Description:
 * This function calculates the sum of absolute transform differences (SATD) 
 * for a 4x4 block by applying a Hadamard transform to the difference block 
 * and then calculating the sum of absolute coefficient values. 
 *
 * Input Arguments:
 *   
 *   pSrcOrg - Pointer to the original block; must be aligned on a 4-byte 
 *            boundary 
 *   iStepOrg - Step of the original block buffer; must be a multiple of 4 
 *   pSrcRef - Pointer to the reference block; must be aligned on a 4-byte 
 *            boundary 
 *   iStepRef - Step of the reference block buffer; must be a multiple of 4 
 *
 * Output Arguments:
 *   
 *   pDstSAD - pointer to the resulting SAD 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned if any of the following 
 *              conditions are true: 
 *    -    at least one of the following pointers is NULL: 
 *         pSrcOrg, pSrcRef, or pDstSAD either pSrcOrg 
 *    -    pSrcRef is not aligned on a 4-byte boundary 
 *    -    iStepOrg <= 0 or iStepOrg is not a multiple of 4 
 *    -    iStepRef <= 0 or iStepRef is not a multiple of 4 
 *
 */
OMXResult omxVCM4P10_SATD_4x4 (
    const OMX_U8 *pSrcOrg,
    OMX_U32 iStepOrg,
    const OMX_U8 *pSrcRef,
    OMX_U32 iStepRef,
    OMX_U32 *pDstSAD
);



/**
 * Function:  omxVCM4P10_InterpolateHalfHor_Luma   (6.3.5.5.1)
 *
 * Description:
 * This function performs interpolation for two horizontal 1/2-pel positions 
 * (-1/2,0) and (1/2, 0) - around a full-pel position. 
 *
 * Input Arguments:
 *   
 *   pSrc - Pointer to the top-left corner of the block used to interpolate in 
 *            the reconstruction frame plane. 
 *   iSrcStep - Step of the source buffer. 
 *   iDstStep - Step of the destination(interpolation) buffer; must be a 
 *            multiple of iWidth. 
 *   iWidth - Width of the current block; must be equal to either 4, 8, or 16 
 *   iHeight - Height of the current block; must be equal to 4, 8, or 16 
 *
 * Output Arguments:
 *   
 *   pDstLeft -Pointer to the interpolation buffer of the left -pel position 
 *            (-1/2, 0) 
 *                 If iWidth==4,  4-byte alignment required. 
 *                 If iWidth==8,  8-byte alignment required. 
 *                 If iWidth==16, 16-byte alignment required. 
 *   pDstRight -Pointer to the interpolation buffer of the right -pel 
 *            position (1/2, 0) 
 *                 If iWidth==4,  4-byte alignment required. 
 *                 If iWidth==8,  8-byte alignment required. 
 *                 If iWidth==16, 16-byte alignment required. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned if any of the following 
 *              conditions are true: 
 *    -    at least one of the following pointers is NULL: 
 *             pSrc, pDstLeft, or pDstRight 
 *    -    iWidth or iHeight have values other than 4, 8, or 16 
 *    -    iWidth==4 but pDstLeft and/or pDstRight is/are not aligned on a 4-byte boundary 
 *    -    iWidth==8 but pDstLeft and/or pDstRight is/are not aligned on a 8-byte boundary 
 *    -    iWidth==16 but pDstLeft and/or pDstRight is/are not aligned on a 16-byte boundary 
 *    -    any alignment restrictions are violated 
 *
 */
OMXResult omxVCM4P10_InterpolateHalfHor_Luma (
    const OMX_U8 *pSrc,
    OMX_U32 iSrcStep,
    OMX_U8 *pDstLeft,
    OMX_U8 *pDstRight,
    OMX_U32 iDstStep,
    OMX_U32 iWidth,
    OMX_U32 iHeight
);



/**
 * Function:  omxVCM4P10_InterpolateHalfVer_Luma   (6.3.5.5.2)
 *
 * Description:
 * This function performs interpolation for two vertical 1/2-pel positions - 
 * (0, -1/2) and (0, 1/2) - around a full-pel position. 
 *
 * Input Arguments:
 *   
 *   pSrc - Pointer to top-left corner of block used to interpolate in the 
 *            reconstructed frame plane 
 *   iSrcStep - Step of the source buffer. 
 *   iDstStep - Step of the destination (interpolation) buffer; must be a 
 *            multiple of iWidth. 
 *   iWidth - Width of the current block; must be equal to either 4, 8, or 16 
 *   iHeight - Height of the current block; must be equal to either 4, 8, or 16 
 *
 * Output Arguments:
 *   
 *   pDstUp -Pointer to the interpolation buffer of the -pel position above 
 *            the current full-pel position (0, -1/2) 
 *                If iWidth==4, 4-byte alignment required. 
 *                If iWidth==8, 8-byte alignment required. 
 *                If iWidth==16, 16-byte alignment required. 
 *   pDstDown -Pointer to the interpolation buffer of the -pel position below 
 *            the current full-pel position (0, 1/2) 
 *                If iWidth==4, 4-byte alignment required. 
 *                If iWidth==8, 8-byte alignment required. 
 *                If iWidth==16, 16-byte alignment required. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned if any of the following 
 *              conditions are true: 
 *    -    at least one of the following pointers is NULL: 
 *            pSrc, pDstUp, or pDstDown 
 *    -    iWidth or iHeight have values other than 4, 8, or 16 
 *    -    iWidth==4 but pDstUp and/or pDstDown is/are not aligned on a 4-byte boundary 
 *    -    iWidth==8 but pDstUp and/or pDstDown is/are not aligned on a 8-byte boundary 
 *    -    iWidth==16 but pDstUp and/or pDstDown is/are not aligned on a 16-byte boundary 
 *
 */
OMXResult omxVCM4P10_InterpolateHalfVer_Luma (
    const OMX_U8 *pSrc,
    OMX_U32 iSrcStep,
    OMX_U8 *pDstUp,
    OMX_U8 *pDstDown,
    OMX_U32 iDstStep,
    OMX_U32 iWidth,
    OMX_U32 iHeight
);



/**
 * Function:  omxVCM4P10_Average_4x   (6.3.5.5.3)
 *
 * Description:
 * This function calculates the average of two 4x4, 4x8 blocks.  The result 
 * is rounded according to (a+b+1)/2. 
 *
 * Input Arguments:
 *   
 *   pPred0 - Pointer to the top-left corner of reference block 0 
 *   pPred1 - Pointer to the top-left corner of reference block 1 
 *   iPredStep0 - Step of reference block 0; must be a multiple of 4. 
 *   iPredStep1 - Step of reference block 1; must be a multiple of 4. 
 *   iDstStep - Step of the destination buffer; must be a multiple of 4. 
 *   iHeight - Height of the blocks; must be either 4 or 8. 
 *
 * Output Arguments:
 *   
 *   pDstPred - Pointer to the destination buffer. 4-byte alignment required. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned if any of the following 
 *              conditions are true: 
 *    -    at least one of the following pointers is NULL: 
 *           pPred0, pPred1, or pDstPred 
 *    -    pDstPred is not aligned on a 4-byte boundary 
 *    -    iPredStep0 <= 0 or iPredStep0 is not a multiple of 4 
 *    -    iPredStep1 <= 0 or iPredStep1 is not a multiple of 4 
 *    -    iDstStep <= 0 or iDstStep is not a multiple of 4 
 *    -    iHeight is not equal to either 4 or 8 
 *
 */
OMXResult omxVCM4P10_Average_4x (
    const OMX_U8 *pPred0,
    const OMX_U8 *pPred1,
    OMX_U32 iPredStep0,
    OMX_U32 iPredStep1,
    OMX_U8 *pDstPred,
    OMX_U32 iDstStep,
    OMX_U32 iHeight
);



/**
 * Function:  omxVCM4P10_TransformQuant_ChromaDC   (6.3.5.6.1)
 *
 * Description:
 * This function performs 2x2 Hadamard transform of chroma DC coefficients 
 * and then quantizes the coefficients. 
 *
 * Input Arguments:
 *   
 *   pSrcDst - Pointer to the 2x2 array of chroma DC coefficients.  8-byte 
 *            alignment required. 
 *   iQP - Quantization parameter; must be in the range [0,51]. 
 *   bIntra - Indicate whether this is an INTRA block. 1-INTRA, 0-INTER 
 *
 * Output Arguments:
 *   
 *   pSrcDst - Pointer to transformed and quantized coefficients.  8-byte 
 *            alignment required. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned if any of the following 
 *              conditions are true: 
 *    -    at least one of the following pointers is NULL: 
 *             pSrcDst 
 *    -    pSrcDst is not aligned on an 8-byte boundary 
 *
 */
OMXResult omxVCM4P10_TransformQuant_ChromaDC (
    OMX_S16 *pSrcDst,
    OMX_U32 iQP,
    OMX_U8 bIntra
);



/**
 * Function:  omxVCM4P10_TransformQuant_LumaDC   (6.3.5.6.2)
 *
 * Description:
 * This function performs a 4x4 Hadamard transform of luma DC coefficients 
 * and then quantizes the coefficients. 
 *
 * Input Arguments:
 *   
 *   pSrcDst - Pointer to the 4x4 array of luma DC coefficients.  16-byte 
 *            alignment required. 
 *   iQP - Quantization parameter; must be in the range [0,51]. 
 *
 * Output Arguments:
 *   
 *   pSrcDst - Pointer to transformed and quantized coefficients.  16-byte 
 *             alignment required. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned if any of the following 
 *              conditions are true: 
 *    -    at least one of the following pointers is NULL: pSrcDst 
 *    -    pSrcDst is not aligned on an 16-byte boundary 
 *
 */
OMXResult omxVCM4P10_TransformQuant_LumaDC (
    OMX_S16 *pSrcDst,
    OMX_U32 iQP
);



/**
 * Function:  omxVCM4P10_InvTransformDequant_LumaDC   (6.3.5.6.3)
 *
 * Description:
 * This function performs inverse 4x4 Hadamard transform and then dequantizes 
 * the coefficients. 
 *
 * Input Arguments:
 *   
 *   pSrc - Pointer to the 4x4 array of the 4x4 Hadamard-transformed and 
 *            quantized coefficients.  16 byte alignment required. 
 *   iQP - Quantization parameter; must be in the range [0,51]. 
 *
 * Output Arguments:
 *   
 *   pDst - Pointer to inverse-transformed and dequantized coefficients.  
 *            16-byte alignment required. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned if any of the following 
 *              conditions are true: 
 *    -    at least one of the following pointers is NULL: pSrc 
 *    -    pSrc or pDst is not aligned on a 16-byte boundary 
 *
 */
OMXResult omxVCM4P10_InvTransformDequant_LumaDC (
    const OMX_S16 *pSrc,
    OMX_S16 *pDst,
    OMX_U32 iQP
);



/**
 * Function:  omxVCM4P10_InvTransformDequant_ChromaDC   (6.3.5.6.4)
 *
 * Description:
 * This function performs inverse 2x2 Hadamard transform and then dequantizes 
 * the coefficients. 
 *
 * Input Arguments:
 *   
 *   pSrc - Pointer to the 2x2 array of the 2x2 Hadamard-transformed and 
 *            quantized coefficients.  8 byte alignment required. 
 *   iQP - Quantization parameter; must be in the range [0,51]. 
 *
 * Output Arguments:
 *   
 *   pDst - Pointer to inverse-transformed and dequantized coefficients.  
 *            8-byte alignment required. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned if any of the following 
 *              conditions are true: 
 *    -    at least one of the following pointers is NULL: pSrc 
 *    -    pSrc or pDst is not aligned on an 8-byte boundary 
 *
 */
OMXResult omxVCM4P10_InvTransformDequant_ChromaDC (
    const OMX_S16 *pSrc,
    OMX_S16 *pDst,
    OMX_U32 iQP
);



/**
 * Function:  omxVCM4P10_InvTransformResidualAndAdd   (6.3.5.7.1)
 *
 * Description:
 * This function performs inverse an 4x4 integer transformation to produce 
 * the difference signal and then adds the difference to the prediction to get 
 * the reconstructed signal. 
 *
 * Input Arguments:
 *   
 *   pSrcPred - Pointer to prediction signal.  4-byte alignment required. 
 *   pDequantCoeff - Pointer to the transformed coefficients.  8-byte 
 *            alignment required. 
 *   iSrcPredStep - Step of the prediction buffer; must be a multiple of 4. 
 *   iDstReconStep - Step of the destination reconstruction buffer; must be a 
 *            multiple of 4. 
 *   bAC - Indicate whether there is AC coefficients in the coefficients 
 *            matrix. 
 *
 * Output Arguments:
 *   
 *   pDstRecon -Pointer to the destination reconstruction buffer.  4-byte 
 *            alignment required. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned if any of the following 
 *              conditions are true: 
 *    -    at least one of the following pointers is NULL: 
 *            pSrcPred, pDequantCoeff, pDstRecon 
 *    -    pSrcPred is not aligned on a 4-byte boundary 
 *    -    iSrcPredStep or iDstReconStep is not a multiple of 4. 
 *    -    pDequantCoeff is not aligned on an 8-byte boundary 
 *
 */
OMXResult omxVCM4P10_InvTransformResidualAndAdd (
    const OMX_U8 *pSrcPred,
    const OMX_S16 *pDequantCoeff,
    OMX_U8 *pDstRecon,
    OMX_U32 iSrcPredStep,
    OMX_U32 iDstReconStep,
    OMX_U8 bAC
);



/**
 * Function:  omxVCM4P10_SubAndTransformQDQResidual   (6.3.5.8.1)
 *
 * Description:
 * This function subtracts the prediction signal from the original signal to 
 * produce the difference signal and then performs a 4x4 integer transform and 
 * quantization. The quantized transformed coefficients are stored as 
 * pDstQuantCoeff. This function can also output dequantized coefficients or 
 * unquantized DC coefficients optionally by setting the pointers 
 * pDstDeQuantCoeff, pDCCoeff. 
 *
 * Input Arguments:
 *   
 *   pSrcOrg - Pointer to original signal. 4-byte alignment required. 
 *   pSrcPred - Pointer to prediction signal. 4-byte alignment required. 
 *   iSrcOrgStep - Step of the original signal buffer; must be a multiple of 
 *            4. 
 *   iSrcPredStep - Step of the prediction signal buffer; must be a multiple 
 *            of 4. 
 *   pNumCoeff -Number of non-zero coefficients after quantization. If this 
 *            parameter is not required, it is set to NULL. 
 *   nThreshSAD - Zero-block early detection threshold. If this parameter is 
 *            not required, it is set to 0. 
 *   iQP - Quantization parameter; must be in the range [0,51]. 
 *   bIntra - Indicates whether this is an INTRA block, either 1-INTRA or 
 *            0-INTER 
 *
 * Output Arguments:
 *   
 *   pDstQuantCoeff - Pointer to the quantized transformed coefficients.  
 *            8-byte alignment required. 
 *   pDstDeQuantCoeff - Pointer to the dequantized transformed coefficients 
 *            if this parameter is not equal to NULL.  8-byte alignment 
 *            required. 
 *   pDCCoeff - Pointer to the unquantized DC coefficient if this parameter 
 *            is not equal to NULL. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned if any of the following 
 *              conditions are true: 
 *    -    at least one of the following pointers is NULL: 
 *            pSrcOrg, pSrcPred, pNumCoeff, pDstQuantCoeff, 
 *            pDstDeQuantCoeff, pDCCoeff 
 *    -    pSrcOrg is not aligned on a 4-byte boundary 
 *    -    pSrcPred is not aligned on a 4-byte boundary 
 *    -    iSrcOrgStep is not a multiple of 4 
 *    -    iSrcPredStep is not a multiple of 4 
 *    -    pDstQuantCoeff or pDstDeQuantCoeff is not aligned on an 8-byte boundary 
 *
 */
OMXResult omxVCM4P10_SubAndTransformQDQResidual (
    const OMX_U8 *pSrcOrg,
    const OMX_U8 *pSrcPred,
    OMX_U32 iSrcOrgStep,
    OMX_U32 iSrcPredStep,
    OMX_S16 *pDstQuantCoeff,
    OMX_S16 *pDstDeQuantCoeff,
    OMX_S16 *pDCCoeff,
    OMX_S8 *pNumCoeff,
    OMX_U32 nThreshSAD,
    OMX_U32 iQP,
    OMX_U8 bIntra
);



/**
 * Function:  omxVCM4P10_GetVLCInfo   (6.3.5.9.1)
 *
 * Description:
 * This function extracts run-length encoding (RLE) information from the 
 * coefficient matrix.  The results are returned in an OMXVCM4P10VLCInfo 
 * structure. 
 *
 * Input Arguments:
 *   
 *   pSrcCoeff - pointer to the transform coefficient matrix.  8-byte 
 *            alignment required. 
 *   pScanMatrix - pointer to the scan order definition matrix.  For a luma 
 *            block the scan matrix should follow [ISO14496-10] section 8.5.4, 
 *            and should contain the values 0, 1, 4, 8, 5, 2, 3, 6, 9, 12, 13, 
 *            10, 7, 11, 14, 15.  For a chroma block, the scan matrix should 
 *            contain the values 0, 1, 2, 3. 
 *   bAC - indicates presence of a DC coefficient; 0 = DC coefficient 
 *            present, 1= DC coefficient absent. 
 *   MaxNumCoef - specifies the number of coefficients contained in the 
 *            transform coefficient matrix, pSrcCoeff. The value should be 16 
 *            for blocks of type LUMADC, LUMAAC, LUMALEVEL, and CHROMAAC. The 
 *            value should be 4 for blocks of type CHROMADC. 
 *
 * Output Arguments:
 *   
 *   pDstVLCInfo - pointer to structure that stores information for 
 *            run-length coding. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned if any of the following 
 *              conditions are true: 
 *    -    at least one of the following pointers is NULL: 
 *            pSrcCoeff, pScanMatrix, pDstVLCInfo 
 *    -    pSrcCoeff is not aligned on an 8-byte boundary 
 *
 */
OMXResult omxVCM4P10_GetVLCInfo (
    const OMX_S16 *pSrcCoeff,
    const OMX_U8 *pScanMatrix,
    OMX_U8 bAC,
    OMX_U32 MaxNumCoef,
    OMXVCM4P10VLCInfo*pDstVLCInfo
);



#ifdef __cplusplus
}
#endif

#endif /** end of #define _OMXVC_H_ */

/** EOF */

