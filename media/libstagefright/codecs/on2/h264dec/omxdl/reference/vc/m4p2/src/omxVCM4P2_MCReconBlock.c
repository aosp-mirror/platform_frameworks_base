/**
 * 
 * File Name:  omxVCM4P2_MCReconBlock.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * Description:
 * MPEG4 motion compensation prediction for an 8x8 block using 
 * interpolation
 * 
 */
 
#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"

/**
 * Function: armVCM4P2_HalfPelVer
 *
 * Description:
 * Performs half pel motion compensation for an 8x8 block using vertical 
 * interpolation described in ISO/IEC 14496-2, subclause 7.6.2.
 *
 * Remarks:
 *
 * Parameters:
 * [in] pSrc        pointer to the block in the reference plane.
 * [in] srcStep     distance between the start of consecutive lines
 *                  in the reference plane, in bytes; must be a multiple
 *                  of 8.
 * [in] rndVal      rounding control parameter: 0 - disabled; 1 - enabled.
 * [out] pDst       pointer to the linaer 8x8 destination buffer;
 *
 */
static OMXVoid armVCM4P2_HalfPelVer(
      const OMX_U8 *pSrc,
      OMX_INT srcStep, 
      OMX_U8 *pDst,
      OMX_INT rndVal)
{
  const OMX_U8 *pTempSrc1;
  const OMX_U8 *pTempSrc2;
  OMX_INT y, x;
  
  pTempSrc1 = pSrc;  
  pTempSrc2 = pSrc + srcStep;
  srcStep -= 8;
  for (y = 0; y < 8; y++)
  {
    for (x = 0; x < 8; x++)
    {
      *pDst++ = ((*pTempSrc1++ + *pTempSrc2++) + 1 - rndVal) >> 1;
    }
    pTempSrc1 += srcStep;
    pTempSrc2 += srcStep;
  }
}

/**
 * Function: armVCM4P2_HalfPelHor
 *
 * Description:
 * Performs half pel motion compensation for an 8x8 block using horizontal 
 * interpolation described in ISO/IEC 14496-2, subclause 7.6.2.
 *
 * Remarks:
 *
 * Parameters:
 * [in] pSrc        pointer to the block in the reference plane.
 * [in] srcStep     distance between the start of consecutive lines
 *                  in the reference plane, in bytes; must be a multiple
 *                  of 8.
 * [in] rndVal      rounding control parameter: 0 - disabled; 1 - enabled.
 * [out] pDst       pointer to the linaer 8x8 destination buffer;
 *
 */
static OMXVoid armVCM4P2_HalfPelHor(
      const OMX_U8 *pSrc,
      OMX_INT srcStep, 
      OMX_U8 *pDst,
      OMX_INT rndVal)
{
  const OMX_U8 *pTempSrc1;
  const OMX_U8 *pTempSrc2;
  OMX_INT y, x;
  
  pTempSrc1 = pSrc;
  pTempSrc2 = pTempSrc1 + 1;

  srcStep -= 8;
  for (y=0; y<8; y++)
  {
    for (x=0; x<8; x++)
    {
      *pDst++ = ((*pTempSrc1++ + *pTempSrc2++) + 1 - rndVal) >> 1;
    }
    pTempSrc1 += srcStep;
    pTempSrc2 += srcStep;
  }
}


/**
 * Function: armVCM4P2_HalfPelVerHor
 *
 * Description:
 * Performs half pel motion compensation for an 8x8 block using both 
 * horizontal and vertical interpolation described in ISO/IEC 14496-2,
 * subclause 7.6.2.
 *
 * Remarks:
 *
 * Parameters:
 * [in] pSrc        pointer to the block in the reference plane.
 * [in] srcStep     distance between the start of consecutive lines
 *                  in the reference plane, in bytes; must be a multiple
 *                  of 8.
 * [in] rndVal      rounding control parameter: 0 - disabled; 1 - enabled.
 * [out] pDst       pointer to the linaer 8x8 destination buffer;
 *
 */
static OMXVoid armVCM4P2_HalfPelVerHor(
      const OMX_U8 *pSrc,
      OMX_INT srcStep, 
      OMX_U8 *pDst,
      OMX_INT rndVal)
{
  const OMX_U8 *pTempSrc1;
  const OMX_U8 *pTempSrc2;
  const OMX_U8 *pTempSrc3;
  const OMX_U8 *pTempSrc4;
  OMX_INT y, x;

  pTempSrc1 = pSrc;
  pTempSrc2 = pSrc + srcStep;
  pTempSrc3 = pSrc + 1;
  pTempSrc4 = pSrc + srcStep + 1;

  srcStep -= 8;
  for (y=0; y<8; y++)
  {
    for (x=0; x<8; x++)
	{
	  *pDst++ = ((*pTempSrc1++ + *pTempSrc2++ + *pTempSrc3++ + *pTempSrc4++) + 
	                  2 - rndVal) >> 2;
	}
    pTempSrc1 += srcStep;
    pTempSrc2 += srcStep;
    pTempSrc3 += srcStep;
    pTempSrc4 += srcStep;
  }
}

/**
 * Function: armVCM4P2_MCReconBlock_NoRes
 *
 * Description:
 * Do motion compensation and copy the result to the current block.
 *
 * Remarks:
 *
 * Parameters:
 * [in] pSrc        pointer to the block in the reference plane.
 * [in] srcStep     distance between the start of consecutive lines
 *                  in the reference plane, in bytes; must be a multiple
 *                  of 8.
 * [in] dstStep     distance between the start of consecutive lines in the
 *                  destination plane, in bytes; must be a multiple of 8.
 * [in] predictType bilinear interpolation type, as defined in section 6.2.1.2.
 * [in] rndVal      rounding control parameter: 0 - disabled; 1 - enabled.
 * [out] pDst       pointer to the destination buffer; must be 8-byte aligned.
 *                  If prediction residuals are added then output intensities
 *                  are clipped to the range [0,255].
 *
 */
static OMXVoid armVCM4P2_MCReconBlock_NoRes(
      const OMX_U8 *pSrc, 
      OMX_INT srcStep,
      OMX_U8 *pDst,
      OMX_INT dstStep)
{
    OMX_U8 x,y,count,index;
    
    /* Copying the ref 8x8 blk to the curr blk */
    for (y = 0, count = 0, index = 0; y < 8; y++,index += (srcStep -8), count += (dstStep - 8))
    {
        for (x = 0; x < 8; x++, count++,index++)
        {
            pDst[count] = pSrc[index];
        }       
    }
}

/**
 * Function: armVCM4P2_MCReconBlock_Res
 *
 * Description:
 * Reconstructs INTER block by summing the motion compensation results
 * and the results of the inverse transformation (prediction residuals).
 * Output intensities are clipped to the range [0,255].
 *
 * Remarks:
 *
 * Parameters:
 * [in] pSrc        pointer to the block in the reference plane.
 * [in] pSrcResidue pointer to a buffer containing the 16-bit prediction
 *                  residuals. If the pointer is NULL,then no prediction
 *                  is done, only motion compensation, i.e., the block is
 *                  moved with interpolation.
 * [in] dstStep     distance between the start of consecutive lines in the
 *                  destination plane, in bytes; must be a multiple of 8.
 * [out] pDst       pointer to the destination buffer; must be 8-byte aligned.
 *                  If prediction residuals are added then output intensities
 *                  are clipped to the range [0,255].
 *
 */
static OMXVoid armVCM4P2_MCReconBlock_Res(
      const OMX_U8 *pSrc, 
      const OMX_S16 *pSrcResidue,
      OMX_U8 *pDst,
      OMX_INT dstStep)
{
      
  OMX_U8 x,y;
  OMX_INT temp;
  
  for(y = 0; y < 8; y++)
  {
    for(x = 0; x < 8; x++)
    {
      temp = pSrc[x] + pSrcResidue[x];         
      pDst[x] = armClip(0,255,temp);
    }
    pDst += dstStep;
    pSrc += 8;
    pSrcResidue += 8;
  }
}

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
OMXResult omxVCM4P2_MCReconBlock(
		const OMX_U8 *pSrc,
		OMX_INT srcStep,
		const OMX_S16 *pSrcResidue,
		OMX_U8 *pDst, 
		OMX_INT dstStep,
		OMX_INT predictType,
		OMX_INT rndVal)
{
    /* Definitions and Initializations*/
    OMX_U8 pTempDst[64];
    
    /* Argument error checks */
    armRetArgErrIf(pSrc == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pDst == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs8ByteAligned(pDst), OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs16ByteAligned(pSrcResidue), OMX_Sts_BadArgErr);
    armRetArgErrIf(((dstStep % 8) || (srcStep % 8)), OMX_Sts_BadArgErr);
    armRetArgErrIf(((predictType != OMX_VC_INTEGER_PIXEL) &&
                    (predictType != OMX_VC_HALF_PIXEL_X) &&
                    (predictType != OMX_VC_HALF_PIXEL_Y) &&
                    (predictType != OMX_VC_HALF_PIXEL_XY)
                   ),OMX_Sts_BadArgErr); 
    armRetArgErrIf(((rndVal != 0) && (rndVal != 1)),OMX_Sts_BadArgErr);
    
    switch(predictType)
    {
        case OMX_VC_INTEGER_PIXEL:
                                   armVCM4P2_MCReconBlock_NoRes(pSrc,
                                                                    srcStep,
                                                                    &(pTempDst[0]),
                                                                    8);
                                   break;
        case OMX_VC_HALF_PIXEL_X:
                                   armVCM4P2_HalfPelHor(pSrc,
                                                            srcStep,
                                                            &(pTempDst[0]),
                                                            rndVal);
                                   break;
        case OMX_VC_HALF_PIXEL_Y:
                                   armVCM4P2_HalfPelVer(pSrc,
                                                            srcStep,
                                                            &(pTempDst[0]),
                                                            rndVal);
                                   break;
        case OMX_VC_HALF_PIXEL_XY:
                                   armVCM4P2_HalfPelVerHor(pSrc,
                                                            srcStep,
                                                            &(pTempDst[0]),
                                                            rndVal);
                                   break;
    }
    
    if(pSrcResidue == NULL)
    {
      armVCM4P2_MCReconBlock_NoRes(&(pTempDst[0]),
                                         8,
                                         pDst,
                                         dstStep);    
    }
    else
    {
      armVCM4P2_MCReconBlock_Res(&(pTempDst[0]),
                                          pSrcResidue,
                                          pDst,
                                          dstStep);    
    }
    
    return OMX_Sts_NoErr;
}

