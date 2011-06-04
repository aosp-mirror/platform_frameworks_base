/**
 * 
 * File Name:  armVCM4P2_PutVLCBits.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * 
 * Description:
 * Contains module for VLC put bits to bitstream 
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
 * Function: armVCM4P2_PutVLCBits
 *
 * Description:
 * Checks the type of Escape Mode and put encoded bits for 
 * quantized DCT coefficients.
 *
 * Remarks:
 *
 * Parameters:
 * [in]	 ppBitStream      pointer to the pointer to the current byte in
 *						  the bit stream
 * [in]	 pBitOffset       pointer to the bit position in the byte pointed
 *                        by *ppBitStream. Valid within 0 to 7
 * [in] shortVideoHeader binary flag indicating presence of short_video_header; escape modes 0-3 are used if shortVideoHeader==0,
 *                           and escape mode 4 is used when shortVideoHeader==1.
 * [in]  start            start indicates whether the encoding begins with 
 *                        0th element or 1st.
 * [in]  maxStoreRunL0    Max store possible (considering last and inter/intra)
 *                        for last = 0
 * [in]  maxStoreRunL1    Max store possible (considering last and inter/intra)
 *                        for last = 1
 * [in]  maxRunForMultipleEntriesL0 
 *                        The run value after which level 
 *                        will be equal to 1: 
 *                        (considering last and inter/intra status) for last = 0
 * [in]  maxRunForMultipleEntriesL1 
 *                        The run value after which level 
 *                        will be equal to 1: 
 *                        (considering last and inter/intra status) for last = 1
 * [in]  pRunIndexTableL0 Run Index table defined in 
 *                        armVCM4P2_Huff_Tables_VLC.c for last == 0
 * [in]  pVlcTableL0      VLC table for last == 0
 * [in]  pRunIndexTableL1 Run Index table defined in 
 *                        armVCM4P2_Huff_Tables_VLC.c for last == 1
 * [in]  pVlcTableL1      VLC table for last == 1
 * [in]  pLMAXTableL0     Level MAX table defined in 
 *                        armVCM4P2_Huff_Tables_VLC.c for last == 0
 * [in]  pLMAXTableL1     Level MAX table defined in 
 *                        armVCM4P2_Huff_Tables_VLC.c for last == 1
 * [in]  pRMAXTableL0     Run MAX table defined in 
 *                        armVCM4P2_Huff_Tables_VLC.c for last == 0
 * [in]  pRMAXTableL1     Run MAX table defined in 
 *                        armVCM4P2_Huff_Tables_VLC.c for last == 1
 * [out] pQDctBlkCoef     pointer to the quantized DCT coefficient
 * [out] ppBitStream      *ppBitStream is updated after the block is encoded
 *                        so that it points to the current byte in the bit
 *                        stream buffer.
 * [out] pBitOffset       *pBitOffset is updated so that it points to the
 *                        current bit position in the byte pointed by
 *                        *ppBitStream.
 *
 * Return Value:
 * Standard OMXResult result. See enumeration for possible result codes.
 *
 */


OMXResult armVCM4P2_PutVLCBits (
              OMX_U8 **ppBitStream,
              OMX_INT * pBitOffset,
              const OMX_S16 *pQDctBlkCoef,
              OMX_INT shortVideoHeader,
              OMX_U8 start,
              OMX_U8 maxStoreRunL0,
              OMX_U8 maxStoreRunL1,
              OMX_U8  maxRunForMultipleEntriesL0,
              OMX_U8  maxRunForMultipleEntriesL1,
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

    OMX_U32 storeRun = 0, run, storeRunPlus;
    OMX_U8  last = 0, first = 1, fMode;
    OMX_S16 level, storeLevel = 0, storeLevelPlus;
    OMX_INT i;
    
        /* RLE encoding and packing the bits into the streams */
        for (i = start, run=0; i < 64; i++)
        {
            level   = pQDctBlkCoef[pZigzagTable[i]];

            /* Counting the run */
            if (level == 0)
            {
                run++;
            }

            /* Found a non-zero coeff */
            else
            {
                if (first == 0)
                {
                    last = 0;
                    
                    /* Check for a valid entry in the VLC table */
                    storeLevelPlus = armSignCheck(storeLevel) * 
                      (armAbs(storeLevel) - pLMAXTableL0[storeRun]);
                    storeRunPlus = storeRun - 
                                  (pRMAXTableL0[armAbs(storeLevel) - 1] + 1);
                                                      
                    fMode = armVCM4P2_CheckVLCEscapeMode(
                                             storeRun,
                                             storeRunPlus,
                                             storeLevel,
                                             storeLevelPlus,
                                             maxStoreRunL0,
                                             maxRunForMultipleEntriesL0,
                                             shortVideoHeader,
                                             pRunIndexTableL0);
                    
                    armVCM4P2_FillVLCBuffer (
                                      ppBitStream, 
                                      pBitOffset,
                                      storeRun,
                                      storeLevel, 
									  storeRunPlus,
                                      storeLevelPlus, 
                                      fMode,
									  last,
                                      maxRunForMultipleEntriesL0, 
                                      pRunIndexTableL0,
                                      pVlcTableL0);                                                  
                }
                storeLevel = level;
                storeRun   = run;
                first = 0;
                run = 0;
            }

        } /* end of for loop for 64 elements */

        /* writing the last element */
        last = 1;
        
        /* Check for a valid entry in the VLC table */
        storeLevelPlus = armSignCheck(storeLevel) * 
                        (armAbs(storeLevel) - pLMAXTableL1[run]);
        storeRunPlus = storeRun - 
                      (pRMAXTableL1[armAbs(storeLevel) - 1] + 1);
        fMode = armVCM4P2_CheckVLCEscapeMode(
                                 storeRun,
                                 storeRunPlus,
                                 storeLevel,
                                 storeLevelPlus,
                                 maxStoreRunL1,
                                 maxRunForMultipleEntriesL1,
                                 shortVideoHeader,
                                 pRunIndexTableL1);
        
        armVCM4P2_FillVLCBuffer (
                          ppBitStream, 
                          pBitOffset,
                          storeRun,
                          storeLevel, 
						  storeRunPlus,
                          storeLevelPlus,
                          fMode,
						  last,
                          maxRunForMultipleEntriesL1,
                          pRunIndexTableL1,
                          pVlcTableL1);
	return OMX_Sts_NoErr;                          
}

/* End of File */
