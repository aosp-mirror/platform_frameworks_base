/**
 * 
 * File Name:  armVCM4P2_EncodeVLCZigzag_intra.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * 
 * Description:
 * Contains modules for zigzag scanning and VLC encoding
 * for intra block.
 *
 */
 
#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armVC.h"
#include "armCOMM_Bitstream.h"
#include "armCOMM.h"
#include "armVCM4P2_Huff_Tables_VLC.h"
#include "armVCM4P2_ZigZag_Tables.h"



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
 *                              OMX_VC_NONE          AC prediction not used.
 *                                                      Performs classical zigzag
 *                                                      scan.
 *                              OMX_VC_HORIZONTAL    Horizontal prediction.
 *                                                      Performs alternate-vertical
 *                                                      zigzag scan.
 *                              OMX_VC_VERTICAL      Vertical prediction.
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
)
{
    const OMX_U8  *pZigzagTable = armVCM4P2_aClassicalZigzagScan;
    OMXResult errorCode;
    
    /* Argument error checks */
    armRetArgErrIf(ppBitStream == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(*ppBitStream == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pBitOffset == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pQDctBlkCoef == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf((*pBitOffset < 0) || (*pBitOffset >7), OMX_Sts_BadArgErr);
    armRetArgErrIf(start > 1, OMX_Sts_BadArgErr);
    armRetArgErrIf(predDir > 2, OMX_Sts_BadArgErr);

    if (pattern)
    {
        switch (predDir)
        {
            case OMX_VC_NONE:
            {
                pZigzagTable = armVCM4P2_aClassicalZigzagScan;
                break;
            }

            case OMX_VC_HORIZONTAL:
            {
                pZigzagTable = armVCM4P2_aVerticalZigzagScan;
                break;
            }

            case OMX_VC_VERTICAL:
            {
                pZigzagTable = armVCM4P2_aHorizontalZigzagScan;
                break;
            }
        }
        
        errorCode = armVCM4P2_PutVLCBits (
              ppBitStream,
              pBitOffset,
              pQDctBlkCoef,
              shortVideoHeader,
              start,
              14,
              20,
              9,
              6,
              armVCM4P2_IntraL0RunIdx,
              armVCM4P2_IntraVlcL0,
			  armVCM4P2_IntraL1RunIdx,
              armVCM4P2_IntraVlcL1,
              armVCM4P2_IntraL0LMAX,
              armVCM4P2_IntraL1LMAX,
              armVCM4P2_IntraL0RMAX,
              armVCM4P2_IntraL1RMAX,
              pZigzagTable
        );
        armRetDataErrIf((errorCode != OMX_Sts_NoErr), errorCode);
        
    } /* Pattern check ends*/

    return (OMX_Sts_NoErr);

}

/* End of file */
