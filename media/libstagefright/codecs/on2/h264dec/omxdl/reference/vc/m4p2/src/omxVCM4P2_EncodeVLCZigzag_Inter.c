/**
 * 
 * File Name:  omxVCM4P2_EncodeVLCZigzag_Inter.c
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
 * for inter block.
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
OMXResult omxVCM4P2_EncodeVLCZigzag_Inter(
     OMX_U8 **ppBitStream,
     OMX_INT * pBitOffset,
     const OMX_S16 *pQDctBlkCoef,
     OMX_U8 pattern,
	 OMX_INT shortVideoHeader
)
{
    OMX_U8 start = 0;
    const OMX_U8  *pZigzagTable = armVCM4P2_aClassicalZigzagScan;

    /* Argument error checks */
    armRetArgErrIf(ppBitStream == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(*ppBitStream == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pBitOffset == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pQDctBlkCoef == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf((*pBitOffset < 0) || (*pBitOffset >7), OMX_Sts_BadArgErr);

    if (pattern)
    {
        armVCM4P2_PutVLCBits (
              ppBitStream,
              pBitOffset,
              pQDctBlkCoef,
              shortVideoHeader,
              start,
              26,
              40,
              10,
              1,
              armVCM4P2_InterL0RunIdx,
              armVCM4P2_InterVlcL0,
			  armVCM4P2_InterL1RunIdx,
              armVCM4P2_InterVlcL1,
              armVCM4P2_InterL0LMAX,
              armVCM4P2_InterL1LMAX,
              armVCM4P2_InterL0RMAX,
              armVCM4P2_InterL1RMAX,
              pZigzagTable
        );
    } /* Pattern check ends*/

    return OMX_Sts_NoErr;

}

/* End of file */
