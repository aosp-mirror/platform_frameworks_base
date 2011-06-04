/**
 * 
 * File Name:  omxVCM4P2_EncodeVLCZigzag_IntraDCVLC.c
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

OMXResult omxVCM4P2_EncodeVLCZigzag_IntraDCVLC(
     OMX_U8 **ppBitStream,
     OMX_INT *pBitOffset,
     const OMX_S16 *pQDctBlkCoef,
     OMX_U8 predDir,
     OMX_U8 pattern,
     OMX_INT shortVideoHeader,
     OMXVCM4P2VideoComponent videoComp
)
{
    OMX_S16 dcValue, powOfSize;
    OMX_U8  DCValueSize, start = 1;
    OMX_U16 absDCValue;

    /* Argument error checks */
    armRetArgErrIf(ppBitStream == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(*ppBitStream == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pBitOffset == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pQDctBlkCoef == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf((*pBitOffset < 0) || (*pBitOffset >7), OMX_Sts_BadArgErr);
	armRetArgErrIf((videoComp != OMX_VC_LUMINANCE) && (videoComp != OMX_VC_CHROMINANCE), OMX_Sts_BadArgErr);
	armRetArgErrIf((predDir != OMX_VC_NONE) && (predDir != OMX_VC_HORIZONTAL) && (predDir != OMX_VC_VERTICAL) , OMX_Sts_BadArgErr);
    
    if (pattern)
    {
        dcValue = pQDctBlkCoef[0];
        absDCValue = armAbs(dcValue);

        /* Find the size */
        DCValueSize = armLogSize (absDCValue);
        absDCValue = armAbs(dcValue);

        /* Insert the code into the bitstream */
        if (videoComp == OMX_VC_LUMINANCE)
        {

            armPackVLC32 (ppBitStream, pBitOffset,
                          armVCM4P2_aIntraDCLumaIndex[DCValueSize]);
        }
        else if (videoComp == OMX_VC_CHROMINANCE)
        {

            armPackVLC32 (ppBitStream, pBitOffset,
                          armVCM4P2_aIntraDCChromaIndex[DCValueSize]);
        }

        /* Additional code generation in case of negative
           dc value the additional */
        if (DCValueSize > 0)
        {
            if (dcValue < 0)
            {
                /* calulate 2 pow */
                powOfSize = (1 << DCValueSize);

                absDCValue =  absDCValue ^ (powOfSize - 1);
            }
            armPackBits(ppBitStream, pBitOffset, (OMX_U32)absDCValue, \
                        DCValueSize);

            if (DCValueSize > 8)
            {
                armPackBits(ppBitStream, pBitOffset, 1, 1);
            }
        }
    }

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
