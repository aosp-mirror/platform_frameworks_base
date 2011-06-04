/**
 * 
 * File Name:  armVCM4P2_GetVLCBits.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * 
 * Description:
 * Contains module for VLC get bits from the stream 
 *
 */ 

#include "omxtypes.h"
#include "armOMX.h"

#include "armVC.h"
#include "armCOMM.h"
#include "armCOMM_Bitstream.h"
#include "armVCM4P2_ZigZag_Tables.h"
#include "armVCM4P2_Huff_Tables_VLC.h"

 
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
)
{
    OMX_U32 storeRun;
    OMX_U8  tabIndex, markerBit;
    OMX_S16 storeLevel;
    OMX_U16 unpackRetIndex;
	OMX_U8  i, fType, escape;	
	OMX_U8  sign = 0;
	
	/* Unpacking the bitstream and RLD */
    for (i = start; i < 64;)
    {
		escape = armLookAheadBits(ppBitStream, pBitOffset, 7);
		if (escape != 3)
		{	
			fType = 0; /* Not in escape mode */
		}
		else
		{
			armSkipBits (ppBitStream, pBitOffset, 7);
			if(shortVideoHeader)
			{
			  *pLast = armGetBits(ppBitStream, pBitOffset, 1);
			  storeRun = armGetBits(ppBitStream, pBitOffset, 6);
			  storeLevel = armGetBits(ppBitStream, pBitOffset, 8);
			  
			  /* Ref to Table B-18 (c) in MPEG4 Standard- FLC code for  */
			  /* LEVEL when short_video_header is 1, the storeLevel is  */
			  /* a signed value and the sign and the unsigned value for */
			  /* storeLevel need to be extracted and passed to arm      */
			  /* FillVLDBuffer function                                 */
			     
			  sign = (storeLevel & 0x80);
			  if(sign==0x80)
			  {
			  	storeLevel=(storeLevel^0xff)+1;			  
			  	sign=1;
			  	
			  }
			  
			  armRetDataErrIf( storeLevel == 0 || sign*storeLevel == 128 , OMX_Sts_Err); /* Invalid FLC */
			  armRetDataErrIf((i + storeRun) >= 64, OMX_Sts_Err);
			  armVCM4P2_FillVLDBuffer(
			    storeRun,
			    pDst,
			    storeLevel,
			    sign,
			    *pLast,
			    &i,
			    pZigzagTable);
			    return OMX_Sts_NoErr;
			    
			}
			if (armGetBits(ppBitStream, pBitOffset, 1))
			{
				if (armGetBits(ppBitStream, pBitOffset, 1))
				{
					fType = 3;
				}
				else
				{
					fType = 2;
				}
			}
			else
			{
				fType = 1;
			}
		}

	    if (fType < 3)
	    {
	        unpackRetIndex = armUnPackVLC32(ppBitStream, pBitOffset,
										pVlcTableL0);
			if (unpackRetIndex != ARM_NO_CODEBOOK_INDEX)
		    {
			    /* Decode run and level from the index */
			    /* last = 0 */
			    *pLast = 0;
			    if (unpackRetIndex > maxIndexForMultipleEntriesL0)
			    {
				    storeLevel = 1;
				    storeRun = (unpackRetIndex - maxIndexForMultipleEntriesL0) 
							+ runBeginSingleLevelEntriesL0;
			    }
			    else
			    {
				    tabIndex = 1;
				    while (pRunIndexTableL0[tabIndex] <= unpackRetIndex)
				    {
					    tabIndex++;
				    }
				    storeRun = tabIndex - 1;
				    storeLevel = unpackRetIndex - pRunIndexTableL0[tabIndex - 1] + 1;
			    }
			    sign = (OMX_U8) armGetBits(ppBitStream, pBitOffset, 1);
			
			    if (fType == 1)
			    {
				    storeLevel = (armAbs(storeLevel) + pLMAXTableL0[storeRun]);
			    }
			    else if (fType == 2)
			    {
				    storeRun = storeRun + pRMAXTableL0[storeLevel-1] + 1;
			    }
		    }
		    else
		    {
			    unpackRetIndex = armUnPackVLC32(ppBitStream, pBitOffset, 
											pVlcTableL1);

			    armRetDataErrIf(unpackRetIndex == ARM_NO_CODEBOOK_INDEX, OMX_Sts_Err);

			    /* Decode run and level from the index */
			    /* last = 1 */
			    *pLast = 1;
			    if (unpackRetIndex > maxIndexForMultipleEntriesL1)
			    {
				    storeLevel = 1;
				    storeRun = (unpackRetIndex - maxIndexForMultipleEntriesL1) 
							+ maxRunForMultipleEntriesL1;
		        }
		        else
			    {
				    tabIndex = 1;
				    while (pRunIndexTableL1[tabIndex] <= unpackRetIndex)
				    {
					    tabIndex++;
				    }
				    storeRun = tabIndex - 1;
				    storeLevel = unpackRetIndex - pRunIndexTableL1[tabIndex - 1] + 1;
			    }
			    sign = (OMX_U8) armGetBits(ppBitStream, pBitOffset, 1);

			    if (fType == 1)
			    {
			        storeLevel = (armAbs(storeLevel) + pLMAXTableL1[storeRun]);				
			    }
			    else if (fType == 2)
			    {
				    storeRun = storeRun + pRMAXTableL1[storeLevel-1] + 1;
			    }
		    }
            armRetDataErrIf((i + storeRun) >= 64, OMX_Sts_Err);
		    armVCM4P2_FillVLDBuffer(
			    storeRun,
			    pDst,
			    storeLevel,
			    sign,
			    *pLast,
			    &i,
			    pZigzagTable);		
	    }
	    else
	    {
		    *pLast = armGetBits(ppBitStream, pBitOffset, 1);
		    storeRun  = armGetBits(ppBitStream, pBitOffset, 6);
		    armRetDataErrIf((i + storeRun) >= 64, OMX_Sts_Err);
		    markerBit = armGetBits(ppBitStream, pBitOffset, 1);
		    armRetDataErrIf( markerBit == 0, OMX_Sts_Err);
		    storeLevel  = armGetBits(ppBitStream, pBitOffset, 12);
		    if (storeLevel & 0x800)
		    {
			    storeLevel -= 4096;
		    }			
		    armRetDataErrIf( storeLevel == 0 || storeLevel == -2048 , OMX_Sts_Err); /* Invalid FLC */
		    armGetBits(ppBitStream, pBitOffset, 1);
		    armVCM4P2_FillVLDBuffer(
			    storeRun,
			    pDst,
			    storeLevel,
			    0, /* Sign is not used, preprocessing done */
			    *pLast,
			    &i,
			    pZigzagTable);

	    }
    } /* End of forloop for i */
	return OMX_Sts_NoErr;
}

/* End of File */

