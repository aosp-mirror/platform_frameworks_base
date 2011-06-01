/* ----------------------------------------------------------------
 *
 * 
 * File Name:  omxVCM4P10_DecodeCoeffsToPairCAVLC.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * H.264 decode coefficients module
 * 
 */
 
#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"
#include "armCOMM.h"
#include "armVC.h"

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

OMXResult omxVCM4P10_DecodeCoeffsToPairCAVLC(
     const OMX_U8** ppBitStream,
     OMX_S32* pOffset,
     OMX_U8* pNumCoeff,
     OMX_U8**ppPosCoefbuf,
     OMX_INT sVLCSelect,
     OMX_INT sMaxNumCoeff        
 )
{
    int nTable;

    armRetArgErrIf(ppBitStream==NULL   , OMX_Sts_BadArgErr);
    armRetArgErrIf(*ppBitStream==NULL  , OMX_Sts_BadArgErr);
    armRetArgErrIf(pOffset==NULL       , OMX_Sts_BadArgErr);
    armRetArgErrIf(*pOffset<0          , OMX_Sts_BadArgErr);
    armRetArgErrIf(*pOffset>7          , OMX_Sts_BadArgErr);
    armRetArgErrIf(pNumCoeff==NULL     , OMX_Sts_BadArgErr);
    armRetArgErrIf(ppPosCoefbuf==NULL  , OMX_Sts_BadArgErr);
    armRetArgErrIf(*ppPosCoefbuf==NULL , OMX_Sts_BadArgErr);
    armRetArgErrIf(sVLCSelect<0        , OMX_Sts_BadArgErr);
    armRetArgErrIf(sMaxNumCoeff<15     , OMX_Sts_BadArgErr);
    armRetArgErrIf(sMaxNumCoeff>16     , OMX_Sts_BadArgErr);
    
    /* Find VLC table number */
    if (sVLCSelect<2)
    {
        nTable = 0;
    }
    else if (sVLCSelect<4)
    {
        nTable = 1;
    }
    else if (sVLCSelect<8)
    {
        nTable = 2;
    }
    else /* sVLCSelect >= 8 */
    {
        nTable = 3;
    }

    return armVCM4P10_DecodeCoeffsToPair(ppBitStream, pOffset, pNumCoeff,
                                         ppPosCoefbuf, nTable, sMaxNumCoeff);
}
