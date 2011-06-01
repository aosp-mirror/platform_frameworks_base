/**
 * 
 * File Name:  omxVCM4P2_EncodeVLCZigzag_IntraACVLC.c
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

OMXResult omxVCM4P2_EncodeVLCZigzag_IntraACVLC(
     OMX_U8 **ppBitStream,
     OMX_INT *pBitOffset,
     const OMX_S16 *pQDctBlkCoef,
     OMX_U8 predDir,
     OMX_U8 pattern,
     OMX_INT shortVideoHeader
)
{
    OMX_U8 start = 0;

    return armVCM4P2_EncodeVLCZigzag_Intra(
     ppBitStream,
     pBitOffset,
     pQDctBlkCoef,
     predDir,
     pattern,
     shortVideoHeader,
     start);
}

/* End of file */
