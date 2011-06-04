/**
 * 
 * File Name:  omxVCM4P2_DecodeVLCZigzag_IntraACVLC.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * Description: 
 * Contains modules for zigzag scanning and VLC decoding
 * for intra block.
 *
 */

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armVC.h"
#include "armCOMM.h"



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


OMXResult omxVCM4P2_DecodeVLCZigzag_IntraACVLC(
     const OMX_U8 ** ppBitStream,
     OMX_INT * pBitOffset,
     OMX_S16 * pDst,
     OMX_U8 predDir,
     OMX_INT shortVideoHeader
)
{
    OMX_U8 start = 0;

    return armVCM4P2_DecodeVLCZigzag_Intra(
     ppBitStream,
     pBitOffset,
     pDst,
     predDir,
     shortVideoHeader,
     start);
}

/* End of file */

