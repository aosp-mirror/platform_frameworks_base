/**
 * 
 * File Name:  omxVCM4P2_DecodeVLCZigzag_IntraDCVLC.c
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
#include "armCOMM_Bitstream.h"
#include "armCOMM.h"
#include "armVCM4P2_Huff_Tables_VLC.h"
#include "armVCM4P2_ZigZag_Tables.h"




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

OMXResult omxVCM4P2_DecodeVLCZigzag_IntraDCVLC(
     const OMX_U8 ** ppBitStream,
     OMX_INT * pBitOffset,
     OMX_S16 * pDst,
     OMX_U8 predDir,
     OMX_INT shortVideoHeader,
     OMXVCM4P2VideoComponent videoComp
)
{
    /* Dummy initilaization to remove compilation error */
    OMX_S8  DCValueSize = 0;
    OMX_U16 powOfSize, fetchDCbits;
    OMX_U8 start = 1;

    /* Argument error checks */
    armRetArgErrIf(ppBitStream == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(*ppBitStream == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pBitOffset == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pDst == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs4ByteAligned(pDst), OMX_Sts_BadArgErr);
    armRetArgErrIf((*pBitOffset < 0) || (*pBitOffset > 7), OMX_Sts_BadArgErr);
    armRetArgErrIf((predDir > 2), OMX_Sts_BadArgErr);

    /* Insert the code into the bitstream */
    if (videoComp == OMX_VC_LUMINANCE)
    {
        DCValueSize = armUnPackVLC32(ppBitStream,
                            pBitOffset, armVCM4P2_aIntraDCLumaIndex);
    }
    else if (videoComp == OMX_VC_CHROMINANCE)
    {
        DCValueSize = armUnPackVLC32(ppBitStream,
                            pBitOffset, armVCM4P2_aIntraDCChromaIndex);
    }
    armRetDataErrIf(DCValueSize == -1, OMX_Sts_Err);
    armRetDataErrIf(DCValueSize > 12, OMX_Sts_Err);


    if (DCValueSize == 0)
    {
        pDst[0] = 0;
    }
    else
    {
        fetchDCbits = (OMX_U16) armGetBits(ppBitStream, pBitOffset, \
                                           DCValueSize);

        if ( (fetchDCbits >> (DCValueSize - 1)) == 0)
        {
            /* calulate pow */
            powOfSize = (1 << DCValueSize);

            pDst[0] =  (OMX_S16) (fetchDCbits ^ (powOfSize - 1));
            pDst[0] = -pDst[0];
        }
        else
        {
            pDst[0] = fetchDCbits;
        }

        if (DCValueSize > 8)
        {
            /* reading and checking the marker bit*/
            armRetDataErrIf (armGetBits(ppBitStream, pBitOffset, 1) == 0, \
                             OMX_Sts_Err);
        }
    }

    return armVCM4P2_DecodeVLCZigzag_Intra(
                ppBitStream,
                pBitOffset,
                pDst,
                predDir,
                shortVideoHeader,
                start);
}

/* End of file */

