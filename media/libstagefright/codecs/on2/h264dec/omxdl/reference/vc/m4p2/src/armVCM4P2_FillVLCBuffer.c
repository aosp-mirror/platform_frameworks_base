/**
 * 
 * File Name:  armVCM4P2_FillVLCBuffer.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * 
 * Description:
 * Contains module for putting VLC bits
 *
 */ 
 
#include "omxtypes.h"
#include "armOMX.h"

#include "armVC.h"
#include "armCOMM.h"
#include "armCOMM_Bitstream.h"

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
 *                          armVCM4P2_Huff_Tables_VLC.h
 * [in]  pVlcTable          VLC table defined in armVCM4P2_Huff_Tables_VLC.h
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
)
{
    OMX_INT tempIndex;
	OMX_U32 tempRun = run, sign = 0;
    OMX_S16 tempLevel = level; 
    
    /* Escape sequence addition */
    if (fMode == 1)
    {
        armPackBits(ppBitStream, pBitOffset, 3, 7);
        armPackBits(ppBitStream, pBitOffset, 0, 1);
		tempLevel = levelPlus;

    }
    else if(fMode == 2)
    {
        armPackBits(ppBitStream, pBitOffset, 3, 7);
        armPackBits(ppBitStream, pBitOffset, 2, 2);
		tempRun = runPlus;
    }
    else if (fMode == 3)
    {
        armPackBits(ppBitStream, pBitOffset, 3, 7);
        armPackBits(ppBitStream, pBitOffset, 3, 2);
    }
    else if (fMode == 4)
    {
        armPackBits(ppBitStream, pBitOffset, 3, 7);
        armPackBits(ppBitStream, pBitOffset, (OMX_U32)last, 1);
		armPackBits(ppBitStream, pBitOffset, tempRun, 6);
		if((tempLevel != 0) && (tempLevel != -128))
		{
		    armPackBits(ppBitStream, pBitOffset,
			   (OMX_U32) tempLevel, 8);
		}
		return OMX_Sts_NoErr;		
    }
    
    if (tempLevel < 0)
    {
        sign = 1;
        tempLevel = armAbs(tempLevel);
    }
    /* Putting VLC bits in the stream */
	if (fMode < 3)
	{
		if (tempRun > maxRunForMultipleEntries)
		{
			tempIndex = pRunIndexTable [maxRunForMultipleEntries + 1] + 
						(tempRun - maxRunForMultipleEntries - 1);
		}
		else
		{
			tempIndex = pRunIndexTable [tempRun] + (tempLevel -1);
		}
    
		armPackVLC32 (ppBitStream, pBitOffset,
					  pVlcTable [tempIndex]);
		armPackBits(ppBitStream, pBitOffset, (OMX_U32)sign, 1);
	}
    else
	{
		if (sign)
		{
			tempLevel = -tempLevel;
		}
		tempRun  = run;
		armPackBits(ppBitStream, pBitOffset, (OMX_U32)last, 1);
		armPackBits(ppBitStream, pBitOffset, tempRun, 6);
		armPackBits(ppBitStream, pBitOffset, 1, 1);
		armPackBits(ppBitStream, pBitOffset,
			   (OMX_U32) tempLevel, 12);
		armPackBits(ppBitStream, pBitOffset, 1, 1);
	}
    return OMX_Sts_NoErr;
}

/*End of File*/

