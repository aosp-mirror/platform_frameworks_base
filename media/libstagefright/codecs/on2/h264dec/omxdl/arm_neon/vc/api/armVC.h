/**
 * 
 * File Name:  armVC.h
 * OpenMAX DL: v1.0.2
 * Revision:   12290
 * Date:       Wednesday, April 9, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * File: armVideo.h
 * Brief: Declares API's/Basic Data types used across the OpenMAX Video domain
 *
 */


#ifndef _armVideo_H_
#define _armVideo_H_

#include "omxVC.h"
#include "armCOMM_Bitstream.h"

/**
 * ARM specific state structure to hold Motion Estimation information.
 */
 
struct m4p2_MESpec
{
    OMXVCM4P2MEParams MEParams;
    OMXVCM4P2MEMode   MEMode;
};

struct m4p10_MESpec
{
    OMXVCM4P10MEParams MEParams;
    OMXVCM4P10MEMode   MEMode;
};

typedef struct m4p2_MESpec  ARMVCM4P2_MESpec;
typedef struct m4p10_MESpec ARMVCM4P10_MESpec;

/**
 * Function: armVCM4P2_CompareMV
 *
 * Description:
 * Performs comparision of motion vectors and SAD's to decide the
 * best MV and SAD
 *
 * Remarks:
 *
 * Parameters:
 * [in]     mvX     x coordinate of the candidate motion vector
 * [in]     mvY     y coordinate of the candidate motion vector
 * [in]     candSAD Candidate SAD
 * [in]     bestMVX x coordinate of the best motion vector
 * [in]     bestMVY y coordinate of the best motion vector
 * [in]     bestSAD best SAD
 *
 * Return Value:
 * OMX_INT -- 1 to indicate that the current sad is the best
 *            0 to indicate that it is NOT the best SAD
 */

OMX_INT armVCM4P2_CompareMV (
    OMX_S16 mvX,
    OMX_S16 mvY,
    OMX_INT candSAD,
    OMX_S16 bestMVX,
    OMX_S16 bestMVY,
    OMX_INT bestSAD);

/**
 * Function: armVCM4P2_ACDCPredict
 *
 * Description:
 * Performs adaptive DC/AC coefficient prediction for an intra block. Prior
 * to the function call, prediction direction (predDir) should be selected
 * as specified in subclause 7.4.3.1 of ISO/IEC 14496-2.
 *
 * Remarks:
 *
 * Parameters:
 * [in] pSrcDst     pointer to the coefficient buffer which contains
 *                          the quantized coefficient residuals (PQF) of the
 *                          current block
 * [in] pPredBufRow pointer to the coefficient row buffer
 * [in] pPredBufCol pointer to the coefficient column buffer
 * [in] curQP       quantization parameter of the current block. curQP
 *                          may equal to predQP especially when the current
 *                          block and the predictor block are in the same
 *                          macroblock.
 * [in] predQP      quantization parameter of the predictor block
 * [in] predDir     indicates the prediction direction which takes one
 *                          of the following values:
 *                          OMX_VIDEO_HORIZONTAL    predict horizontally
 *                          OMX_VIDEO_VERTICAL      predict vertically
 * [in] ACPredFlag  a flag indicating if AC prediction should be
 *                          performed. It is equal to ac_pred_flag in the bit
 *                          stream syntax of MPEG-4
 * [in] videoComp   video component type (luminance, chrominance or
 *                          alpha) of the current block
 * [in] flag        This flag defines the if one wants to use this functions to
 *                  calculate PQF (set 1, prediction) or QF (set 0, reconstruction)
 * [out]    pPreACPredict   pointer to the predicted coefficients buffer.
 *                          Filled ONLY if it is not NULL
 * [out]    pSrcDst     pointer to the coefficient buffer which contains
 *                          the quantized coefficients (QF) of the current
 *                          block
 * [out]    pPredBufRow pointer to the updated coefficient row buffer
 * [out]    pPredBufCol pointer to the updated coefficient column buffer
 * [out]    pSumErr     pointer to the updated sum of the difference
 *                      between predicted and unpredicted coefficients
 *                      If this is NULL, do not update
 *
 * Return Value:
 * Standard OMXResult result. See enumeration for possible result codes.
 *
 */

OMXResult armVCM4P2_ACDCPredict(
     OMX_S16 * pSrcDst,
     OMX_S16 * pPreACPredict,
     OMX_S16 * pPredBufRow,
     OMX_S16 * pPredBufCol,
     OMX_INT curQP,
     OMX_INT predQP,
     OMX_INT predDir,
     OMX_INT ACPredFlag,
     OMXVCM4P2VideoComponent  videoComp,
     OMX_U8 flag,
     OMX_INT *pSumErr
);

/**
 * Function: armVCM4P2_SetPredDir
 *
 * Description:
 * Performs detecting the prediction direction
 *
 * Remarks:
 *
 * Parameters:
 * [in] blockIndex  block index indicating the component type and
 *                          position as defined in subclause 6.1.3.8, of ISO/IEC
 *                          14496-2. Furthermore, indexes 6 to 9 indicate the
 *                          alpha blocks spatially corresponding to luminance
 *                          blocks 0 to 3 in the same macroblock.
 * [in] pCoefBufRow pointer to the coefficient row buffer
 * [in] pQpBuf      pointer to the quantization parameter buffer
 * [out]    predQP      quantization parameter of the predictor block
 * [out]    predDir     indicates the prediction direction which takes one
 *                          of the following values:
 *                          OMX_VIDEO_HORIZONTAL    predict horizontally
 *                          OMX_VIDEO_VERTICAL      predict vertically
 *
 * Return Value:
 * Standard OMXResult result. See enumeration for possible result codes.
 *
 */

OMXResult armVCM4P2_SetPredDir(
     OMX_INT blockIndex,
     OMX_S16 *pCoefBufRow,
     OMX_S16 *pCoefBufCol,
     OMX_INT *predDir,
     OMX_INT *predQP,
     const OMX_U8 *pQpBuf
);

/**
 * Function: armVCM4P2_EncodeVLCZigzag_Intra
 *
 * Description:
 * Performs zigzag scanning and VLC encoding for one intra block.
 *
 * Remarks:
 *
 * Parameters:
 * [in] ppBitStream     pointer to the pointer to the current byte in
 *                              the bit stream
 * [in] pBitOffset      pointer to the bit position in the byte pointed
 *                              by *ppBitStream. Valid within 0 to 7.
 * [in] pQDctBlkCoef    pointer to the quantized DCT coefficient
 * [in] predDir         AC prediction direction, which is used to decide
 *                              the zigzag scan pattern. This takes one of the
 *                              following values:
 *                              OMX_VIDEO_NONE          AC prediction not used.
 *                                                      Performs classical zigzag
 *                                                      scan.
 *                              OMX_VIDEO_HORIZONTAL    Horizontal prediction.
 *                                                      Performs alternate-vertical
 *                                                      zigzag scan.
 *                              OMX_VIDEO_VERTICAL      Vertical prediction.
 *                                                      Performs alternate-horizontal
 *                                                      zigzag scan.
 * [in] pattern         block pattern which is used to decide whether
 *                              this block is encoded
 * [in] start           start indicates whether the encoding begins with 0th element
 *                      or 1st.
 * [out]    ppBitStream     *ppBitStream is updated after the block is encoded,
 *                              so that it points to the current byte in the bit
 *                              stream buffer.
 * [out]    pBitOffset      *pBitOffset is updated so that it points to the
 *                              current bit position in the byte pointed by
 *                              *ppBitStream.
 *
 * Return Value:
 * Standard OMXResult result. See enumeration for possible result codes.
 *
 */

OMXResult armVCM4P2_EncodeVLCZigzag_Intra(
     OMX_U8 **ppBitStream,
     OMX_INT *pBitOffset,
     const OMX_S16 *pQDctBlkCoef,
     OMX_U8 predDir,
     OMX_U8 pattern,
     OMX_INT shortVideoHeader,
     OMX_U8 start
);

/**
 * Function: armVCM4P2_DecodeVLCZigzag_Intra
 *
 * Description:
 * Performs VLC decoding and inverse zigzag scan for one intra coded block.
 *
 * Remarks:
 *
 * Parameters:
 * [in] ppBitStream     pointer to the pointer to the current byte in
 *                              the bitstream buffer
 * [in] pBitOffset      pointer to the bit position in the byte pointed
 *                              to by *ppBitStream. *pBitOffset is valid within
 *                              [0-7].
 * [in] predDir         AC prediction direction which is used to decide
 *                              the zigzag scan pattern. It takes one of the
 *                              following values:
 *                              OMX_VIDEO_NONE  AC prediction not used;
 *                                              perform classical zigzag scan;
 *                              OMX_VIDEO_HORIZONTAL    Horizontal prediction;
 *                                                      perform alternate-vertical
 *                                                      zigzag scan;
 *                              OMX_VIDEO_VERTICAL      Vertical prediction;
 *                                                      thus perform
 *                                                      alternate-horizontal
 *                                                      zigzag scan.
 * [in] videoComp       video component type (luminance, chrominance or
 *                              alpha) of the current block
 * [in] shortVideoHeader binary flag indicating presence of short_video_header; escape modes 0-3 are used if shortVideoHeader==0,
 *                           and escape mode 4 is used when shortVideoHeader==1.
 * [in] start           start indicates whether the encoding begins with 0th element
 *                      or 1st.
 * [out]    ppBitStream     *ppBitStream is updated after the block is
 *                              decoded, so that it points to the current byte
 *                              in the bit stream buffer
 * [out]    pBitOffset      *pBitOffset is updated so that it points to the
 *                              current bit position in the byte pointed by
 *                              *ppBitStream
 * [out]    pDst            pointer to the coefficient buffer of current
 *                              block. Should be 32-bit aligned
 *
 * Return Value:
 * Standard OMXResult result. See enumeration for possible result codes.
 *
 */

OMXResult armVCM4P2_DecodeVLCZigzag_Intra(
     const OMX_U8 ** ppBitStream,
     OMX_INT * pBitOffset,
     OMX_S16 * pDst,
     OMX_U8 predDir,
     OMX_INT shortVideoHeader, 
     OMX_U8  start
);

/**
 * Function: armVCM4P2_FillVLDBuffer
 *
 * Description:
 * Performs filling of the coefficient buffer according to the run, level
 * and sign, also updates the index
 * 
 * Parameters:
 * [in]  storeRun        Stored Run value (count of zeros)   
 * [in]  storeLevel      Stored Level value (non-zero value)
 * [in]  sign            Flag indicating the sign of level
 * [in]  last            status of the last flag
 * [in]  pIndex          pointer to coefficient index in 8x8 matrix
 * [out] pIndex          pointer to updated coefficient index in 8x8 
 *                       matrix
 * [in]  pZigzagTable    pointer to the zigzag tables
 * [out] pDst            pointer to the coefficient buffer of current
 *                       block. Should be 32-bit aligned
 * Return Value:
 * Standard OMXResult result. See enumeration for possible result codes.
 *
 */

OMXResult armVCM4P2_FillVLDBuffer(
    OMX_U32 storeRun,
    OMX_S16 * pDst,
    OMX_S16 storeLevel,
    OMX_U8  sign,
    OMX_U8  last,
    OMX_U8  * index,
    const OMX_U8 * pZigzagTable
);

/**
 * Function: armVCM4P2_GetVLCBits
 *
 * Description:
 * Performs escape mode decision based on the run, run+, level, level+ and 
 * last combinations.
 *
 * Remarks:
 *
 * Parameters:
 * [in]	ppBitStream		pointer to the pointer to the current byte in
 *								the bit stream
 * [in]	pBitOffset		pointer to the bit position in the byte pointed
 *								by *ppBitStream. Valid within 0 to 7
 * [in] shortVideoHeader binary flag indicating presence of short_video_header; escape modes 0-3 are used if shortVideoHeader==0,
 *                           and escape mode 4 is used when shortVideoHeader==1.
 * [in] start           start indicates whether the encoding begins with 
 *                      0th element or 1st.
 * [in/out] pLast       pointer to last status flag
 * [in] runBeginSingleLevelEntriesL0      The run value from which level 
 *                                        will be equal to 1: last == 0
 * [in] IndexBeginSingleLevelEntriesL0    Array index in the VLC table 
 *                                        pointing to the  
 *                                        runBeginSingleLevelEntriesL0 
 * [in] runBeginSingleLevelEntriesL1      The run value from which level 
 *                                        will be equal to 1: last == 1
 * [in] IndexBeginSingleLevelEntriesL1    Array index in the VLC table 
 *                                        pointing to the  
 *                                        runBeginSingleLevelEntriesL0 
 * [in] pRunIndexTableL0    Run Index table defined in 
 *                          armVCM4P2_Huff_Tables_VLC.c for last == 0
 * [in] pVlcTableL0         VLC table for last == 0
 * [in] pRunIndexTableL1    Run Index table defined in 
 *                          armVCM4P2_Huff_Tables_VLC.c for last == 1
 * [in] pVlcTableL1         VLC table for last == 1
 * [in] pLMAXTableL0        Level MAX table defined in 
 *                          armVCM4P2_Huff_Tables_VLC.c for last == 0
 * [in] pLMAXTableL1        Level MAX table defined in 
 *                          armVCM4P2_Huff_Tables_VLC.c for last == 1
 * [in] pRMAXTableL0        Run MAX table defined in 
 *                          armVCM4P2_Huff_Tables_VLC.c for last == 0
 * [in] pRMAXTableL1        Run MAX table defined in 
 *                          armVCM4P2_Huff_Tables_VLC.c for last == 1
 * [out]pDst			    pointer to the coefficient buffer of current
 *							block. Should be 32-bit aligned
 *
 * Return Value:
 * Standard OMXResult result. See enumeration for possible result codes.
 *
 */

OMXResult armVCM4P2_GetVLCBits (
              const OMX_U8 **ppBitStream,
              OMX_INT * pBitOffset,
			  OMX_S16 * pDst,
			  OMX_INT shortVideoHeader,
			  OMX_U8    start,			  
			  OMX_U8  * pLast,
			  OMX_U8    runBeginSingleLevelEntriesL0,
			  OMX_U8    maxIndexForMultipleEntriesL0,
			  OMX_U8    maxRunForMultipleEntriesL1,
			  OMX_U8    maxIndexForMultipleEntriesL1,
              const OMX_U8  * pRunIndexTableL0,
              const ARM_VLC32 *pVlcTableL0,
			  const OMX_U8  * pRunIndexTableL1,
              const ARM_VLC32 *pVlcTableL1,
              const OMX_U8  * pLMAXTableL0,
              const OMX_U8  * pLMAXTableL1,
              const OMX_U8  * pRMAXTableL0,
              const OMX_U8  * pRMAXTableL1,
              const OMX_U8  * pZigzagTable
);

/**
 * Function: armVCM4P2_PutVLCBits
 *
 * Description:
 * Checks the type of Escape Mode and put encoded bits for 
 * quantized DCT coefficients.
 *
 * Remarks:
 *
 * Parameters:
 * [in]	 ppBitStream      pointer to the pointer to the current byte in
 *						  the bit stream
 * [in]	 pBitOffset       pointer to the bit position in the byte pointed
 *                        by *ppBitStream. Valid within 0 to 7
 * [in] shortVideoHeader binary flag indicating presence of short_video_header; escape modes 0-3 are used if shortVideoHeader==0,
 *                           and escape mode 4 is used when shortVideoHeader==1.
 * [in]  start            start indicates whether the encoding begins with 
 *                        0th element or 1st.
 * [in]  maxStoreRunL0    Max store possible (considering last and inter/intra)
 *                        for last = 0
 * [in]  maxStoreRunL1    Max store possible (considering last and inter/intra)
 *                        for last = 1
 * [in]  maxRunForMultipleEntriesL0 
 *                        The run value after which level 
 *                        will be equal to 1: 
 *                        (considering last and inter/intra status) for last = 0
 * [in]  maxRunForMultipleEntriesL1 
 *                        The run value after which level 
 *                        will be equal to 1: 
 *                        (considering last and inter/intra status) for last = 1
 * [in]  pRunIndexTableL0 Run Index table defined in 
 *                        armVCM4P2_Huff_Tables_VLC.c for last == 0
 * [in]  pVlcTableL0      VLC table for last == 0
 * [in]  pRunIndexTableL1 Run Index table defined in 
 *                        armVCM4P2_Huff_Tables_VLC.c for last == 1
 * [in]  pVlcTableL1      VLC table for last == 1
 * [in]  pLMAXTableL0     Level MAX table defined in 
 *                        armVCM4P2_Huff_Tables_VLC.c for last == 0
 * [in]  pLMAXTableL1     Level MAX table defined in 
 *                        armVCM4P2_Huff_Tables_VLC.c for last == 1
 * [in]  pRMAXTableL0     Run MAX table defined in 
 *                        armVCM4P2_Huff_Tables_VLC.c for last == 0
 * [in]  pRMAXTableL1     Run MAX table defined in 
 *                        armVCM4P2_Huff_Tables_VLC.c for last == 1
 * [out] pQDctBlkCoef     pointer to the quantized DCT coefficient
 * [out] ppBitStream      *ppBitStream is updated after the block is encoded
 *                        so that it points to the current byte in the bit
 *                        stream buffer.
 * [out] pBitOffset       *pBitOffset is updated so that it points to the
 *                        current bit position in the byte pointed by
 *                        *ppBitStream.
 *
 * Return Value:
 * Standard OMXResult result. See enumeration for possible result codes.
 *
 */


OMXResult armVCM4P2_PutVLCBits (
              OMX_U8 **ppBitStream,
              OMX_INT * pBitOffset,
              const OMX_S16 *pQDctBlkCoef,
              OMX_INT shortVideoHeader,
              OMX_U8 start,
              OMX_U8 maxStoreRunL0,
              OMX_U8 maxStoreRunL1,
              OMX_U8  maxRunForMultipleEntriesL0,
              OMX_U8  maxRunForMultipleEntriesL1,
              const OMX_U8  * pRunIndexTableL0,
              const ARM_VLC32 *pVlcTableL0,
			  const OMX_U8  * pRunIndexTableL1,
              const ARM_VLC32 *pVlcTableL1,
              const OMX_U8  * pLMAXTableL0,
              const OMX_U8  * pLMAXTableL1,
              const OMX_U8  * pRMAXTableL0,
              const OMX_U8  * pRMAXTableL1,
              const OMX_U8  * pZigzagTable
);
/**
 * Function: armVCM4P2_FillVLCBuffer
 *
 * Description:
 * Performs calculating the VLC bits depending on the escape type and insert 
 * the same in the bitstream
 *
 * Remarks:
 *
 * Parameters:
 * [in]	 ppBitStream		pointer to the pointer to the current byte in
 *	                        the bit stream
 * [in]	 pBitOffset         pointer to the bit position in the byte pointed
 *                          by *ppBitStream. Valid within 0 to 7
 * [in]  run                Run value (count of zeros) to be encoded  
 * [in]  level              Level value (non-zero value) to be encoded
 * [in]  runPlus            Calculated as runPlus = run - (RMAX + 1)  
 * [in]  levelPlus          Calculated as 
 *                          levelPlus = sign(level)*[abs(level) - LMAX]
 * [in]  fMode              Flag indicating the escape modes
 * [in]  last               status of the last flag
 * [in]  maxRunForMultipleEntries 
 *                          The run value after which level will be equal to 1: 
 *                          (considering last and inter/intra status)
 * [in]  pRunIndexTable     Run Index table defined in
 *                          armVCM4P2_Huff_tables_VLC.h
 * [in]  pVlcTable          VLC table defined in armVCM4P2_Huff_tables_VLC.h
 * [out] ppBitStream		*ppBitStream is updated after the block is encoded
 *                          so that it points to the current byte in the bit
 *                          stream buffer.
 * [out] pBitOffset         *pBitOffset is updated so that it points to the
 *                          current bit position in the byte pointed by
 *                          *ppBitStream.
 *
 * Return Value:
 * Standard OMXResult result. See enumeration for possible result codes.
 *
 */

OMXResult armVCM4P2_FillVLCBuffer (
              OMX_U8 **ppBitStream,
              OMX_INT * pBitOffset,
              OMX_U32 run,
              OMX_S16 level, 
			  OMX_U32 runPlus,
              OMX_S16 levelPlus, 
              OMX_U8  fMode,
			  OMX_U8  last,
              OMX_U8  maxRunForMultipleEntries, 
              const OMX_U8  *pRunIndexTable,
              const ARM_VLC32 *pVlcTable
);

/**
 * Function: armVCM4P2_CheckVLCEscapeMode
 *
 * Description:
 * Performs escape mode decision based on the run, run+, level, level+ and 
 * last combinations.
 *
 * Remarks:
 *
 * Parameters:
 * [in] run             Run value (count of zeros) to be encoded  
 * [in] level           Level value (non-zero value) to be encoded
 * [in] runPlus         Calculated as runPlus = run - (RMAX + 1)  
 * [in] levelPlus       Calculated as 
 *                      levelPlus = sign(level)*[abs(level) - LMAX]
 * [in] maxStoreRun     Max store possible (considering last and inter/intra)
 * [in] maxRunForMultipleEntries 
 *                      The run value after which level 
 *                      will be equal to 1: 
 *                      (considering last and inter/intra status)
 * [in] shortVideoHeader binary flag indicating presence of short_video_header; escape modes 0-3 are used if shortVideoHeader==0,
 *                           and escape mode 4 is used when shortVideoHeader==1.
 * [in] pRunIndexTable  Run Index table defined in 
 *                      armVCM4P2_Huff_Tables_VLC.c
 *                      (considering last and inter/intra status)
 *
 *                      
 * Return Value:
 * Returns an Escape mode which can take values from 0 to 3
 * 0 --> no escape mode, 1 --> escape type 1,
 * 1 --> escape type 2, 3 --> escape type 3, check section 7.4.1.3
 * in the MPEG ISO standard.
 *
 */

OMX_U8 armVCM4P2_CheckVLCEscapeMode(
     OMX_U32 run,
     OMX_U32 runPlus,
     OMX_S16 level,
     OMX_S16 levelPlus,
     OMX_U8  maxStoreRun,
     OMX_U8  maxRunForMultipleEntries,
     OMX_INT shortVideoHeader,
     const OMX_U8  *pRunIndexTable
);


/**
 * Function: armVCM4P2_BlockMatch_Integer
 *
 * Description:
 * Performs a 16x16 block search; estimates motion vector and associated minimum SAD.  
 * Both the input and output motion vectors are represented using half-pixel units, and 
 * therefore a shift left or right by 1 bit may be required, respectively, to match the 
 * input or output MVs with other functions that either generate output MVs or expect 
 * input MVs represented using integer pixel units. 
 *
 * Remarks:
 *
 * Parameters:
 * [in]	pSrcRefBuf		pointer to the reference Y plane; points to the reference MB that 
 *                    corresponds to the location of the current macroblock in the current 
 *                    plane.
 * [in]	refWidth		  width of the reference plane
 * [in]	pRefRect		  pointer to the valid rectangular in reference plane. Relative to image origin. 
 *                    It's not limited to the image boundary, but depended on the padding. For example, 
 *                    if you pad 4 pixels outside the image border, then the value for left border 
 *                    can be -4
 * [in]	pSrcCurrBuf		pointer to the current macroblock extracted from original plane (linear array, 
 *                    256 entries); must be aligned on an 8-byte boundary.
 * [in] pCurrPointPos	position of the current macroblock in the current plane
 * [in] pSrcPreMV		  pointer to predicted motion vector; NULL indicates no predicted MV
 * [in] pSrcPreSAD		pointer to SAD associated with the predicted MV (referenced by pSrcPreMV)
 * [in] searchRange		search range for 16X16 integer block,the units of it is full pixel,the search range 
 *                    is the same in all directions.It is in inclusive of the boundary and specified in 
 *                    terms of integer pixel units.
 * [in] pMESpec			  vendor-specific motion estimation specification structure; must have been allocated 
 *                    and then initialized using omxVCM4P2_MEInit prior to calling the block matching 
 *                    function.
 * [in] BlockSize     MacroBlock Size i.e either 16x16 or 8x8.
 * [out]	pDstMV			pointer to estimated MV
 * [out]	pDstSAD			pointer to minimum SAD
 *
 * Return Value:
 * OMX_Sts_NoErr 每 no error.
 * OMX_Sts_BadArgErr 每 bad arguments
 *
 */

OMXResult armVCM4P2_BlockMatch_Integer(
     const OMX_U8 *pSrcRefBuf,
     OMX_INT refWidth,
     const OMXRect *pRefRect,
     const OMX_U8 *pSrcCurrBuf,
     const OMXVCM4P2Coordinate *pCurrPointPos,
     const OMXVCMotionVector *pSrcPreMV,
     const OMX_INT *pSrcPreSAD,
     void *pMESpec,
     OMXVCMotionVector *pDstMV,
     OMX_INT *pDstSAD,
     OMX_U8 BlockSize
);

/**
 * Function: armVCM4P2_BlockMatch_Half
 *
 * Description:
 * Performs a 16x16 block match with half-pixel resolution.  Returns the estimated 
 * motion vector and associated minimum SAD.  This function estimates the half-pixel 
 * motion vector by interpolating the integer resolution motion vector referenced 
 * by the input parameter pSrcDstMV, i.e., the initial integer MV is generated 
 * externally.  The input parameters pSrcRefBuf and pSearchPointRefPos should be 
 * shifted by the winning MV of 16x16 integer search prior to calling BlockMatch_Half_16x16.  
 * The function BlockMatch_Integer_16x16 may be used for integer motion estimation.
 *
 * Remarks:
 *
 * Parameters:
 * [in]	pSrcRefBuf		pointer to the reference Y plane; points to the reference MB 
 *                    that corresponds to the location of the current macroblock in 
 *                    the	current plane.
 * [in]	refWidth		  width of the reference plane
 * [in]	pRefRect		  reference plane valid region rectangle
 * [in]	pSrcCurrBuf		pointer to the current macroblock extracted from original plane 
 *                    (linear array, 256 entries); must be aligned on an 8-byte boundary. 
 * [in]	pSearchPointRefPos	position of the starting point for half pixel search (specified 
 *                          in terms of integer pixel units) in the reference plane.
 * [in]	rndVal			  rounding control bit for half pixel motion estimation; 
 *                    0=rounding control disabled; 1=rounding control enabled
 * [in]	pSrcDstMV		pointer to the initial MV estimate; typically generated during a prior 
 *                  16X16 integer search and its unit is half pixel.
 * [in] BlockSize     MacroBlock Size i.e either 16x16 or 8x8.
 * [out]pSrcDstMV		pointer to estimated MV
 * [out]pDstSAD			pointer to minimum SAD
 *
 * Return Value:
 * OMX_Sts_NoErr 每 no error
 * OMX_Sts_BadArgErr 每 bad arguments
 *
 */

OMXResult armVCM4P2_BlockMatch_Half(
     const OMX_U8 *pSrcRefBuf,
     OMX_INT refWidth,
     const OMXRect *pRefRect,
     const OMX_U8 *pSrcCurrBuf,
     const OMXVCM4P2Coordinate *pSearchPointRefPos,
     OMX_INT rndVal,
     OMXVCMotionVector *pSrcDstMV,
     OMX_INT *pDstSAD,
     OMX_U8 BlockSize
);
/**
 * Function: armVCM4P2_PadMV
 *
 * Description:
 * Performs motion vector padding for a macroblock.
 *
 * Remarks:
 *
 * Parameters:
 * [in] pSrcDstMV       pointer to motion vector buffer of the current
 *                              macroblock
 * [in] pTransp         pointer to transparent status buffer of the
 *                              current macroblock
 * [out]    pSrcDstMV       pointer to motion vector buffer in which the
 *                              motion vectors have been padded
 * Return Value:
 * Standard OMXResult result. See enumeration for possible result codes.
 *
 */

OMXResult armVCM4P2_PadMV(
     OMXVCMotionVector * pSrcDstMV,
     OMX_U8 * pTransp
);

/* 
 * H.264 Specific Declarations 
 */
/* Defines */
#define ARM_M4P10_Q_OFFSET        (15)


/* Dequant tables */

extern const OMX_U8 armVCM4P10_PosToVCol4x4[16];
extern const OMX_U8 armVCM4P10_PosToVCol2x2[4];
extern const OMX_U8 armVCM4P10_VMatrix[6][3];
extern const OMX_U32 armVCM4P10_MFMatrix[6][3];


/*
 * Description:
 * This function perform the work required by the OpenMAX
 * DecodeCoeffsToPair function and DecodeChromaDCCoeffsToPair.
 * Since most of the code is common we share it here.
 *
 * Parameters:
 * [in]	ppBitStream		Double pointer to current byte in bit stream buffer
 * [in]	pOffset			Pointer to current bit position in the byte pointed
 *								to by *ppBitStream
 * [in]	sMaxNumCoeff	Maximum number of non-zero coefficients in current
 *								block (4,15 or 16)
 * [in]	nTable          Table number (0 to 4) according to the five columns
 *                      of Table 9-5 in the H.264 spec
 * [out]	ppBitStream		*ppBitStream is updated after each block is decoded
 * [out]	pOffset			*pOffset is updated after each block is decoded
 * [out]	pNumCoeff		Pointer to the number of nonzero coefficients in
 *								this block
 * [out]	ppPosCoefbuf	Double pointer to destination residual
 *								coefficient-position pair buffer
 * Return Value:
 * Standard omxError result. See enumeration for possible result codes.

 */

OMXResult armVCM4P10_DecodeCoeffsToPair(
     const OMX_U8** ppBitStream,
     OMX_S32* pOffset,
     OMX_U8* pNumCoeff,
     OMX_U8**ppPosCoefbuf,
     OMX_INT nTable,
     OMX_INT sMaxNumCoeff        
 );

/*
 * Description:
 * Perform DC style intra prediction, averaging upper and left block
 *
 * Parameters:
 * [in]	pSrcLeft		Pointer to the buffer of 16 left coefficients:
 *								p[x, y] (x = -1, y = 0..3)
 * [in]	pSrcAbove		Pointer to the buffer of 16 above coefficients:
 *								p[x,y] (x = 0..3, y = -1)
 * [in]	leftStep		Step of left coefficient buffer
 * [in]	dstStep			Step of the destination buffer
 * [in]	availability	Neighboring 16x16 MB availability flag
 * [out]	pDst			Pointer to the destination buffer
 *
 * Return Value:
 * None
 */

void armVCM4P10_PredictIntraDC4x4(
     const OMX_U8* pSrcLeft,
     const OMX_U8 *pSrcAbove,
     OMX_U8* pDst,
     OMX_INT leftStep,
     OMX_INT dstStep,
     OMX_S32 availability        
);

/*
 * Description
 * Unpack a 4x4 block of coefficient-residual pair values
 *
 * Parameters:
 * [in]	ppSrc	Double pointer to residual coefficient-position pair
 *						buffer output by CALVC decoding
 * [out]	ppSrc	*ppSrc is updated to the start of next non empty block
 * [out]	pDst	Pointer to unpacked 4x4 block
 */

void armVCM4P10_UnpackBlock4x4(
     const OMX_U8 **ppSrc,
     OMX_S16* pDst
);

/*
 * Description
 * Unpack a 2x2 block of coefficient-residual pair values
 *
 * Parameters:
 * [in]	ppSrc	Double pointer to residual coefficient-position pair
 *						buffer output by CALVC decoding
 * [out]	ppSrc	*ppSrc is updated to the start of next non empty block
 * [out]	pDst	Pointer to unpacked 4x4 block
 */

void armVCM4P10_UnpackBlock2x2(
     const OMX_U8 **ppSrc,
     OMX_S16* pDst
);

/*
 * Description
 * Deblock one boundary pixel
 *
 * Parameters:
 * [in]	pQ0         Pointer to pixel q0
 * [in] Step        Step between pixels q0 and q1
 * [in] tC0         Edge threshold value
 * [in] alpha       alpha threshold value
 * [in] beta        beta threshold value
 * [in] bS          deblocking strength
 * [in] ChromaFlag  True for chroma blocks
 * [out] pQ0        Deblocked pixels
 * 
 */

void armVCM4P10_DeBlockPixel(
    OMX_U8 *pQ0,    /* pointer to the pixel q0 */
    int Step,       /* step between pixels q0 and q1 */
    int tC0,        /* edge threshold value */
    int alpha,      /* alpha */
    int beta,       /* beta */
    int bS,         /* deblocking strength */
    int ChromaFlag
);

/**
 * Function: armVCM4P10_InterpolateHalfHor_Luma
 *
 * Description:
 * This function performs interpolation for horizontal 1/2-pel positions
 *
 * Remarks:
 *
 *	[in]	pSrc			Pointer to top-left corner of block used to interpolate 
 													in the reconstructed frame plane
 *	[in]	iSrcStep	Step of the source buffer.
 *	[in]	iDstStep	Step of the destination(interpolation) buffer.
 *	[in]	iWidth		Width of the current block
 *	[in]	iHeight		Height of the current block
 *	[out]	pDst	    Pointer to the interpolation buffer of the 1/2-pel 
 *
 * Return Value:
 * Standard OMXResult value.
 *
 */

OMXResult armVCM4P10_InterpolateHalfHor_Luma(
        const OMX_U8*		pSrc, 
		OMX_U32 	iSrcStep, 
		OMX_U8* 	pDst, 
		OMX_U32 	iDstStep, 
		OMX_U32 	iWidth, 
		OMX_U32 	iHeight
);

/**
 * Function: armVCM4P10_InterpolateHalfVer_Luma
 * 
 * Description:
 * This function performs interpolation for vertical 1/2-pel positions 
 * around a full-pel position.
 *
 * Remarks:
 *
 *	[in]	pSrc			Pointer to top-left corner of block used to interpolate 
 *												in the reconstructed frame plane
 *	[in]	iSrcStep	Step of the source buffer.
 *	[in]	iDstStep	Step of the destination(interpolation) buffer.
 *	[in]	iWidth		Width of the current block
 *	[in]	iHeight		Height of the current block
 *	[out]	pDst    	Pointer to the interpolation buffer of the 1/2-pel
 *
 * Return Value:
 * Standard OMXResult value.
 *
 */

OMXResult armVCM4P10_InterpolateHalfVer_Luma(	
	 const OMX_U8* 	pSrc, 
	 OMX_U32 	iSrcStep, 
 	 OMX_U8* 	pDst,
 	 OMX_U32 	iDstStep, 
 	 OMX_U32 	iWidth, 
 	 OMX_U32 	iHeight
);

/**
 * Function: armVCM4P10_InterpolateHalfDiag_Luma
 * 
 * Description:
 * This function performs interpolation for (1/2, 1/2)  positions 
 * around a full-pel position.
 *
 * Remarks:
 *
 *  [in]    pSrc        Pointer to top-left corner of block used to interpolate 
 *                      in the reconstructed frame plane
 *  [in]    iSrcStep    Step of the source buffer.
 *  [in]    iDstStep    Step of the destination(interpolation) buffer.
 *  [in]    iWidth      Width of the current block
 *  [in]    iHeight     Height of the current block
 *  [out]   pDst        Pointer to the interpolation buffer of the (1/2,1/2)-pel
 *
 * Return Value:
 * Standard OMXResult value.
 *
 */

OMXResult armVCM4P10_InterpolateHalfDiag_Luma(  
        const OMX_U8*     pSrc, 
        OMX_U32     iSrcStep, 
        OMX_U8*     pDst, 
        OMX_U32     iDstStep,
        OMX_U32     iWidth, 
        OMX_U32     iHeight
);

/*
 * Description:
 * Transform Residual 4x4 Coefficients
 *
 * Parameters:
 * [in]  pSrc		Source 4x4 block
 * [out] pDst		Destination 4x4 block
 *
 */

void armVCM4P10_TransformResidual4x4(OMX_S16* pDst, OMX_S16 *pSrc);

/*
 * Description:
 * Forward Transform Residual 4x4 Coefficients
 *
 * Parameters:
 * [in]  pSrc		Source 4x4 block
 * [out] pDst		Destination 4x4 block
 *
 */

void armVCM4P10_FwdTransformResidual4x4(OMX_S16* pDst, OMX_S16 *pSrc);

OMX_INT armVCM4P10_CompareMotionCostToMV (
    OMX_S16  mvX,
    OMX_S16  mvY,
    OMXVCMotionVector diffMV, 
    OMX_INT candSAD, 
    OMXVCMotionVector *bestMV, 
    OMX_U32 nLamda,
    OMX_S32 *pBestCost);

/**
 * Function: armVCCOMM_SAD
 *
 * Description:
 * This function calculate the SAD for NxM blocks.
 *
 * Remarks:
 *
 * [in]		pSrcOrg		Pointer to the original block
 * [in]		iStepOrg	Step of the original block buffer
 * [in]		pSrcRef		Pointer to the reference block
 * [in]		iStepRef	Step of the reference block buffer
 * [in]		iHeight		Height of the block
 * [in]		iWidth		Width of the block
 * [out]	pDstSAD		Pointer of result SAD
 *
 * Return Value:
 * Standard OMXResult value.
 *
 */
OMXResult armVCCOMM_SAD(	
	const OMX_U8* 	pSrcOrg,
	OMX_U32 	iStepOrg,
	const OMX_U8* 	pSrcRef,
	OMX_U32 	iStepRef,
	OMX_S32*	pDstSAD,
	OMX_U32		iHeight,
	OMX_U32		iWidth);

/**
 * Function: armVCCOMM_Average
 *
 * Description:
 * This function calculates the average of two blocks and stores the result.
 *
 * Remarks:
 *
 *	[in]	pPred0			Pointer to the top-left corner of reference block 0
 *	[in]	pPred1			Pointer to the top-left corner of reference block 1
 *	[in]	iPredStep0	    Step of reference block 0
 *	[in]	iPredStep1	    Step of reference block 1
 *	[in]	iDstStep 		Step of the destination buffer
 *	[in]	iWidth			Width of the blocks
 *	[in]	iHeight			Height of the blocks
 *	[out]	pDstPred		Pointer to the destination buffer
 *
 * Return Value:
 * Standard OMXResult value.
 *
 */
 OMXResult armVCCOMM_Average (
	 const OMX_U8* 	    pPred0,
	 const OMX_U8* 	    pPred1,	
	 OMX_U32		iPredStep0,
	 OMX_U32		iPredStep1,
	 OMX_U8*		pDstPred,
	 OMX_U32		iDstStep, 
	 OMX_U32		iWidth,
	 OMX_U32		iHeight
);

/**
 * Function: armVCM4P10_SADQuar
 *
 * Description:
 * This function calculates the SAD between one block (pSrc) and the 
 * average of the other two (pSrcRef0 and pSrcRef1)
 *
 * Remarks:
 *
 * [in]		pSrc				Pointer to the original block
 * [in]		pSrcRef0		Pointer to reference block 0
 * [in]		pSrcRef1		Pointer to reference block 1
 * [in]		iSrcStep 		Step of the original block buffer
 * [in]		iRefStep0		Step of reference block 0 
 * [in]		iRefStep1 	Step of reference block 1 
 * [in]		iHeight			Height of the block
 * [in]		iWidth			Width of the block
 * [out]	pDstSAD			Pointer of result SAD
 *
 * Return Value:
 * Standard OMXResult value.
 *
 */
OMXResult armVCM4P10_SADQuar(
	const OMX_U8* 	pSrc,
    const OMX_U8* 	pSrcRef0,
	const OMX_U8* 	pSrcRef1,	
    OMX_U32 	iSrcStep,
    OMX_U32		iRefStep0,
    OMX_U32		iRefStep1,
    OMX_U32*	pDstSAD,
    OMX_U32     iHeight,
    OMX_U32     iWidth
);

/**
 * Function: armVCM4P10_Interpolate_Chroma
 *
 * Description:
 * This function performs interpolation for chroma components.
 *
 * Remarks:
 *
 *  [in]    pSrc            Pointer to top-left corner of block used to 
 *                                              interpolate in the reconstructed frame plane
 *  [in]    iSrcStep    Step of the source buffer.
 *  [in]    iDstStep    Step of the destination(interpolation) buffer.
 *  [in]    iWidth      Width of the current block
 *  [in]    iHeight     Height of the current block
 *  [in]    dx              Fractional part of horizontal motion vector 
 *                                              component in 1/8 pixel unit (0~7) 
 *  [in]    dy              Fractional part of vertical motion vector 
 *                                              component in 1/8 pixel unit (0~7)
 *  [out]   pDst            Pointer to the interpolation buffer
 *
 * Return Value:
 * Standard OMXResult value.
 *
 */
 OMXResult armVCM4P10_Interpolate_Chroma(
        OMX_U8      *pSrc,
        OMX_U32     iSrcStep,
        OMX_U8      *pDst,
        OMX_U32     iDstStep,
        OMX_U32     iWidth,
        OMX_U32     iHeight,
        OMX_U32     dx,
        OMX_U32     dy
);

/**
 * Function: armVCM4P10_Interpolate_Luma
 *
 * Description:
 * This function performs interpolation for luma components.
 *
 * Remarks:
 *
 *  [in]    pSrc            Pointer to top-left corner of block used to 
 *                                              interpolate in the reconstructed frame plane
 *  [in]    iSrcStep    Step of the source buffer.
 *  [in]    iDstStep    Step of the destination(interpolation) buffer.
 *  [in]    iWidth      Width of the current block
 *  [in]    iHeight     Height of the current block
 *  [in]    dx              Fractional part of horizontal motion vector 
 *                                              component in 1/4 pixel unit (0~3) 
 *  [in]    dy              Fractional part of vertical motion vector 
 *                                              component in 1/4 pixel unit (0~3) 
 *  [out]   pDst            Pointer to the interpolation buffer
 *
 * Return Value:
 * Standard OMXResult value.
 *
 */

 OMXResult armVCM4P10_Interpolate_Luma(
     const OMX_U8     *pSrc,
     OMX_U32    iSrcStep,
     OMX_U8     *pDst,
     OMX_U32    iDstStep,
     OMX_U32    iWidth,
     OMX_U32    iHeight,
     OMX_U32    dx,
     OMX_U32    dy
);

/**
 * Function: omxVCH264_DequantTransformACFromPair_U8_S16_C1_DLx
 *
 * Description:
 * Reconstruct the 4x4 residual block from coefficient-position pair buffer,
 * perform dequantisation and integer inverse transformation for 4x4 block of
 * residuals and update the pair buffer pointer to next non-empty block.
 *
 * Remarks:
 *
 * Parameters:
 * [in]	ppSrc		Double pointer to residual coefficient-position
 *							pair buffer output by CALVC decoding
 * [in]	pDC			Pointer to the DC coefficient of this block, NULL
 *							if it doesn't exist
 * [in]	QP			Quantization parameter
 * [in] AC          Flag indicating if at least one non-zero coefficient exists
 * [out]	pDst		pointer to the reconstructed 4x4 block data
 *
 * Return Value:
 * Standard omxError result. See enumeration for possible result codes.
 *
 */

OMXResult armVCM4P10_DequantTransformACFromPair_U8_S16_C1_DLx(
     OMX_U8 **ppSrc,
     OMX_S16 *pDst,
     OMX_INT QP,
     OMX_S16* pDC,
     int AC
);

#endif  /*_armVideo_H_*/

/*End of File*/

