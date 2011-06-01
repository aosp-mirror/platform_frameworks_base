/**                                                                            x
 * 
 * File Name:  omxVCM4P10_MotionEstimationMB.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * Description:
 * This function perform MB level motion estimation
 * 
 */

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"
#include "armVC.h"

#define  ARM_VCM4P10_MAX_FRAMES     (15)
#define  ARM_VCM4P10_MAX_4x4_SAD		(0xffff)
#define  ARM_VCM4P10_MAX_MODE_VALUE     (0xffffffff)
#define  ARM_VCM4P10_MAX_MODES          (16)
#define  ARM_VCM4P10_MB_BLOCK_SIZE      (16)
#define  ARM_VCM4P10_MEDIAN(a,b,c)      (a>b?a>c?b>c?b:c:a:b>c?a>c?a:c:b)
#define  ARM_VCM4P10_SHIFT_QP           (12)

#define  ARM_VCM4P10_MVPRED_MEDIAN      (0)
#define  ARM_VCM4P10_MVPRED_L           (1)
#define  ARM_VCM4P10_MVPRED_U           (2)
#define  ARM_VCM4P10_MVPRED_UR          (3)

#define ARM_VCM4P10_MB_BLOCK_SIZE       (16)
#define ARM_VCM4P10_BLOCK_SIZE          (4)
#define ARM_VCM4P10_MAX_COST            (1 << 30)
#define  ARM_VCM4P10_INVALID_BLOCK      (-2)


/**
 * Function: armVCM4P10_CalculateBlockSAD
 *
 * Description:
 *    Calculate SAD value for the selected MB encoding mode and update 
 * pDstBlockSAD parameter. These SAD values are calculated 4x4 blocks at
 * a time and in the scan order.
 *
 * Remarks:
 *
 * Parameters:
 * [in] pSrcMBInfo    - 
 * [in] pSrcCurrBuf   - 
 * [in] SrcCurrStep   - 
 * [in] pSrcRefBufList- 
 * [in] SrcRefStep    - 
 * [in] pSrcRecBuf    - 
 * [in] SrcRecStep    - 
 * [in] pRefRect      - 
 * [in] pCurrPointPos - 
 * [in] Lambda        - 
 * [in] pMESpec       - 
 * [in] pMBInter      - 
 * [in] pMBIntra      - 
 * [out] pDstBlockSAD - pointer to 16 element array for SAD corresponding to 4x4 blocks
 * Return Value:
 * None
 *
 */

static OMXResult armVCM4P10_CalculateBlockSAD(
	OMXVCM4P10MBInfo *pSrcMBInfo, 
    const OMX_U8 *pSrcCurrBuf,                                  
	OMX_S32 SrcCurrStep, 
	const OMX_U8 *pSrcRefBufList[ARM_VCM4P10_MAX_FRAMES],
	OMX_S32 SrcRefStep,
	const OMX_U8 *pSrcRecBuf, 
	OMX_S32 SrcRecStep,
	const OMXRect *pRefRect,
	const OMXVCM4P2Coordinate *pCurrPointPos,
	const OMXVCM4P10MBInfoPtr *pMBInter, 
	const OMXVCM4P10MBInfoPtr *pMBIntra,
	OMX_U16 *pDstBlockSAD)
{
	OMX_INT		InvalidSAD = 0;
	OMX_INT		i;

	OMX_U8		Buffer [16*16 + 15];
	OMX_U8		*pTempDstBuf;
	OMX_S32		TempDstStep;
	OMX_U8		*pTempRefBuf;
	OMX_S32		TempRefStep; 

	/* Temporary buffer to store the predicted mb coefficients */
	pTempDstBuf = armAlignTo16Bytes(Buffer);
	TempDstStep = 16;

	/* Update pDstBlockSAD if MB is a valid type */
	if (pSrcMBInfo)
	{
	    OMX_U32     Width=0, Height=0, MaxXPart, MaxYPart,MaxSubXPart,MaxSubYPart;
	    
		/* Depending on type of MB, do prediction and fill temp buffer */
		switch (pSrcMBInfo->mbType)
		{
		case OMX_VC_P_16x16:
				Width = 16;
				Height = 16;
				break;
		case OMX_VC_P_16x8:
				Width = 16;
				Height = 8;
				break;
		case OMX_VC_P_8x16:
				Width = 8;
				Height = 16;
				break;
		case OMX_VC_P_8x8:
				Width = 8;
				Height = 8;
				break;
		case OMX_VC_INTRA_4x4:
			{
				/* Create predicted MB Intra4x4 mode */
				OMX_S32     PredIntra4x4Mode [5][9];
				OMX_S32		x, y, Block8x8, Block4x4, BlockX, BlockY;
				OMX_U8      pSrcYBuff [(16*3)*(16*2)];
				OMX_U8		*pSrcY;
				OMX_S32     StepSrcY;
				OMX_S32		availability;

				for (y = 0; y < 5; y++)
				{
					for (x = 0; x < 9; x++)
					{
						/* 
						 * Initialize with value of ARM_VCM4P10_INVALID_BLOCK, to mean this 
						 * 4x4 block is not available 
						 */
						PredIntra4x4Mode [y][x] = ARM_VCM4P10_INVALID_BLOCK;
					}
				}

				/* Replace ARM_VCM4P10_INVALID_BLOCK value with available MBs values*/
				for (x = 0; x < 4; x++)
				{
					/* Store values of b0, b1, b2, b3 */
					if (pMBIntra[1] != NULL)
					{
						PredIntra4x4Mode [0][x + 1] = 
							pMBIntra[1]->pIntra4x4PredMode[3*4 + x];            
					}
			        
					/* Store values of d0, d1, d2, d3 */
					if (pMBIntra[3] != NULL)
					{
						PredIntra4x4Mode [0][x + 5] = 
							pMBIntra[3]->pIntra4x4PredMode[3*4 + x];
					}
				}
		    
				/* Store values of c3 */
				if (pMBIntra[2] != NULL)
				{
					PredIntra4x4Mode [0][0] = pMBIntra[2]->pIntra4x4PredMode[15];
				}
		    
				for (y = 0; y < 4; y++)
				{
					/* Store values of a0, a1, a2, a3 */
					if (pMBIntra[0] != NULL)
					{
						PredIntra4x4Mode [y + 1][0] = 
							pMBIntra[0]->pIntra4x4PredMode[y*4 + 3];
					}
				}
		        
				/*
				 * Update neighbouring Pred mode array which will be used for
				 * prediction of Intra4x4 modes.
				 */
			    
				pSrcY = pSrcYBuff;
				StepSrcY = 16 * 3;
				for (y = 0; y < (16 * 2); y++)
				{
					for (x = 0; x < (16 * 3); x++)
					{
						pSrcY [StepSrcY * y + x] = 
							pSrcRecBuf [SrcRecStep * (y - 16) + x - 16];
					}
				}

		    
				/* for each 8x8 block */
				for (Block8x8 = 0; Block8x8 < 4; Block8x8++)
				{
					/* for each 4x4 block inside 8x8 block */
					for (Block4x4 = 0; Block4x4 < 4; Block4x4++)
					{
						/* Get block cordinates from 8x8 block index and 4x4 block index */
						BlockX = ((Block8x8 & 1) << 1) + (Block4x4 & 1);
						BlockY = ((Block8x8 >> 1) << 1) + (Block4x4 >> 1);
					    
						/* Add offset to point to start of current MB in the array pIntra4x4PredMode */
						x = BlockX + 1;
						y = BlockY + 1;

						availability = 0;

						/* Check for availability of LEFT Block */
						if (PredIntra4x4Mode [y][x - 1] != ARM_VCM4P10_INVALID_BLOCK)
						{
							availability |= OMX_VC_LEFT;        
						}

						/* Check for availability of UPPER Block */
						if (PredIntra4x4Mode [y - 1][x] != ARM_VCM4P10_INVALID_BLOCK)
						{
							availability |= OMX_VC_UPPER;        
						}

						/* Check for availability of UPPER LEFT Block */
						if (PredIntra4x4Mode [y - 1][x - 1] != ARM_VCM4P10_INVALID_BLOCK)
						{
							availability |= OMX_VC_UPPER_LEFT;        
						}
						
						PredIntra4x4Mode [y][x] = pSrcMBInfo->pIntra4x4PredMode[BlockY*4+BlockX];
						x = BlockX * 4;
						y = BlockY * 4;

						pSrcY = pSrcYBuff + 16 * StepSrcY + 16 + y * StepSrcY + x;

						omxVCM4P10_PredictIntra_4x4(
							 pSrcY - 1,
							 pSrcY - StepSrcY,
							 pSrcY - StepSrcY - 1,
							 pTempDstBuf + x + y * TempDstStep,
							 StepSrcY,
							 TempDstStep,
							 pSrcMBInfo->pIntra4x4PredMode[BlockY*4+BlockX],
							 availability);

						for (BlockY=0;BlockY<4;BlockY++)
						{
							for(BlockX=0;BlockX<4;BlockX++)
							{
								pSrcY [BlockY * StepSrcY + BlockX] = 
									(OMX_U8)(*(pTempDstBuf + x + y * TempDstStep + BlockY * TempDstStep + BlockX));
							}
						}

					}
				}
				break;
			}
		case OMX_VC_INTRA_16x16:
			{
				OMX_U32     MBPosX = pCurrPointPos->x >> 4;        
				OMX_U32     MBPosY = pCurrPointPos->y >> 4;        
				OMX_U32		availability = 0;

				/* Check for availability of LEFT MB */
				if ((MBPosX != 0) && (pMBIntra [0] != 0 || pMBInter [0] != 0))
				{
					availability |= OMX_VC_LEFT;        
				}

				/* Check for availability of UP MB */
				if ((MBPosY != 0) && (pMBIntra [1] != 0 || pMBInter [1] != 0))
				{
					availability |= OMX_VC_UPPER;        
				}

				/* Check for availability of UP-LEFT MB */
				if ((MBPosX > 0) && (MBPosY > 0) && 
					(pMBIntra [2] != 0 || pMBInter [2] != 0))
				{
					availability |= OMX_VC_UPPER_LEFT;        
				}

				omxVCM4P10_PredictIntra_16x16(
						pSrcRecBuf - 1, 
						pSrcRecBuf - SrcRecStep, 
						pSrcRecBuf - SrcRecStep - 1, 
						pTempDstBuf, 
						SrcRecStep, 
						TempDstStep, 
						pSrcMBInfo->Intra16x16PredMode, 
						availability);
				
				break;
			}

		case OMX_VC_INTER_SKIP:
		case OMX_VC_PREF0_8x8:
		case OMX_VC_INTRA_PCM:
		default:
			/* These cases will update pDstBlockSAD with MAX value */
			InvalidSAD = 1;
			break;
		}

		/* INTER MB */
		if ((pSrcMBInfo->mbType == OMX_VC_P_16x16) ||
			(pSrcMBInfo->mbType == OMX_VC_P_8x16) ||
			(pSrcMBInfo->mbType == OMX_VC_P_16x8) ||
			(pSrcMBInfo->mbType == OMX_VC_P_8x8))
		{
        	const OMX_U8		*pTempSrcBuf;
        	OMX_S32		TempSrcStep;
        	OMX_S32		mvx,mvy;
        	OMX_U32		PartX, PartY, SubPartX, SubPartY;
        	
			TempSrcStep = SrcRefStep;

			MaxXPart = 16/Width;
			MaxYPart = 16/Height;


			for (PartY = 0; PartY < MaxYPart; PartY++)
			{
				for (PartX = 0; PartX < MaxXPart; PartX++)
				{

					pTempSrcBuf = pSrcRefBufList[pSrcMBInfo->pRefL0Idx[PartY * 2 + PartX]];

					if (MaxXPart == 2 && MaxYPart == 2)
					{
        				switch (pSrcMBInfo->subMBType[PartY*2+PartX])
        				{
        				    case OMX_VC_SUB_P_8x8:
								Width = 8;
								Height = 8;
            				    break;
        				    case OMX_VC_SUB_P_8x4:
								Width = 8;
								Height = 4;
            				    break;
        				    case OMX_VC_SUB_P_4x8:
								Width = 4;
								Height = 8;
            				    break;
        				    case OMX_VC_SUB_P_4x4:
								Width = 4;
								Height = 4;
            				    break;
        				    default:
								/* Default */
								Width = 4;
								Height = 4;
        				    break;
        				}
					
    				    MaxSubXPart = 8/Width;
    				    MaxSubYPart = 8/Height;

						for (SubPartY = 0; SubPartY < MaxSubYPart; SubPartY++)
						{
							for (SubPartX = 0; SubPartX < MaxSubXPart; SubPartX++)
							{
								mvx = pSrcMBInfo->pMV0 [2*PartY + SubPartY][2*PartX + SubPartX].dx;
								mvy = pSrcMBInfo->pMV0 [2*PartY + SubPartY][2*PartX + SubPartX].dy;
								armVCM4P10_Interpolate_Luma(
									pTempSrcBuf + (8*PartX + 4*SubPartX + (mvx/4)) + (8*PartY + 4*SubPartY + (mvy/4)) * TempSrcStep,
									TempSrcStep,
									pTempDstBuf + (8*PartX + 4*SubPartX) + (8*PartY + 4*SubPartY) * TempDstStep,
									TempDstStep,
									Width,
									Height,
									mvx & 3,
									mvy & 3
									);
							}
						}
					}
					else
					{

						mvx = pSrcMBInfo->pMV0 [2*PartY][2*PartX].dx;
						mvy = pSrcMBInfo->pMV0 [2*PartY][2*PartX].dy;
						armVCM4P10_Interpolate_Luma(
							pTempSrcBuf + (8*PartX + (mvx/4)) + (8*PartY + (mvy/4)) * TempSrcStep,
							TempSrcStep,
							pTempDstBuf + (8*PartX) + (8*PartY) * TempDstStep,
							TempDstStep,
							Width,
							Height,
							mvx & 3,
							mvy & 3
							);

					}
				}
			}
		}
	}
	else
	{
		InvalidSAD = 1;
	}

	/* Calculate SAD from predicted buffer */
	if (!InvalidSAD)
	{
	    OMX_U32     x8x8, y8x8, x4x4, y4x4, Block8x8, Block4x4;
	    OMX_S32     SAD;
	    
		pTempRefBuf = pTempDstBuf;
		TempRefStep = 16;

		/* SAD for each 4x4 block in scan order */
		for (Block8x8 = 0; Block8x8 < 4; Block8x8++)
		{
			x8x8 = 8*(Block8x8 & 1);
			y8x8 = 8*(Block8x8 >> 1);
			for (Block4x4 = 0; Block4x4 < 4; Block4x4++)
			{
				x4x4 = 4*(Block4x4 & 1);
				y4x4 = 4*(Block4x4 >> 1);

				armVCCOMM_SAD(	
					pSrcCurrBuf + (x8x8 + x4x4) + (y8x8 + y4x4) * SrcCurrStep, 
					SrcCurrStep,
					pTempRefBuf + (x8x8 + x4x4) + (y8x8 + y4x4) * TempRefStep,
					TempRefStep,
    				&SAD,
    				4, /* Height */
    				4); /* Width */
                *(pDstBlockSAD + 4 * Block8x8 + Block4x4) = (SAD < 0x7fff) ? (OMX_U16) SAD : ARM_VCM4P10_MAX_MODE_VALUE;   			    
 			}
		}
	}
	else
	{
		/* Fill SADs with max values and return*/
		for (i = 0; i < 16; i++)
		{
			pDstBlockSAD [i] = ARM_VCM4P10_MAX_4x4_SAD;
		}
	}
	return OMX_Sts_NoErr;
}



/**
 * Function: armVCM4P10_Mode4x4Decision
 *
 * Description:
 *    Intra 4x4 Mode decision by calculating cost for all possible modes and
 * choosing the best mode
 *
 * Remarks:
 *
 * Parameters:
 * [in] pSrcCurrBuf    - Pointer to the start of current Macroblock
 * [in] SrcCurrStep - Step size of the pointer pSrcCurrBuf
 * [in/out] pSrcDstMBCurr - Pointer to the OMXVCM4P10MBInfo which will be updated for
 *                    field pIntra4x4PredMode of the current block.
 * [in] Block8x8    - Index 8x8 block in which current 4x4 block belongs
 * [in] Block4x4    - Index of current 4x4 block
 * [in/out] pPredIntra4x4SrcY - Pointer to current block location in buffer 
 *                    with reconstructed values. This will be modified by this
 *                    function with best mode predicted values 
 * [in] StepPredIntra4x4SrcY  - Step size of the pointer pPredIntra4x4SrcY
 * [in] pIntra4x4PredMode     - Array of Intra 4x4 prediction mode for the MB.
 *                              Current MB modes starts at [1,1].
 * [in] pBestCost   - Cost for the Best Intra 4x4 mode
 * Return Value:
 * None
 *
 */
static OMXVoid armVCM4P10_Mode4x4Decision (
    const OMX_U8* pSrcCurrBuf,   
    OMX_S32 SrcCurrStep,
    OMXVCM4P10MBInfo *pSrcDstMBCurr,
    OMX_S32 Block8x8,
    OMX_S32 Block4x4,
    OMX_U8  *pPredIntra4x4SrcY,
    OMX_S32 StepPredIntra4x4SrcY,
    OMX_S32 pIntra4x4PredMode [][9],
    OMX_S32 *pBestCost
)
{
    OMX_S32     i, j, x, y, BlockX, BlockY, mode;
    OMX_S32     Cost, BestCost;
    OMX_U8      *pSrcY;
    OMX_S32     StepSrcY;
    OMX_S32     availability = 0;
    OMX_U8      pPredBlock [4*4];
    OMXResult   Ret = OMX_Sts_Err;

    /* Get block cordinates from 8x8 block index and 4x4 block index */
    BlockX = ((Block8x8 & 1) << 1) + (Block4x4 & 1);
    BlockY = ((Block8x8 >> 1) << 1) + (Block4x4 >> 1);
    
    /* Add offset to point to start of current MB in the array pIntra4x4PredMode */
    x = BlockX + 1;
    y = BlockY + 1;

    /* Check for availability of LEFT Block */
    if (pIntra4x4PredMode [y][x - 1] != ARM_VCM4P10_INVALID_BLOCK)
    {
        availability |= OMX_VC_LEFT;        
    }

    /* Check for availability of UPPER Block */
    if (pIntra4x4PredMode [y - 1][x] != ARM_VCM4P10_INVALID_BLOCK)
    {
        availability |= OMX_VC_UPPER;        
    }

    /* Check for availability of UPPER LEFT Block */
    if (pIntra4x4PredMode [y - 1][x - 1] != ARM_VCM4P10_INVALID_BLOCK)
    {
        availability |= OMX_VC_UPPER_LEFT;        
    }

    pSrcY = pPredIntra4x4SrcY + 
            StepPredIntra4x4SrcY * (BlockY << 2) + 
            (BlockX << 2);
            
    StepSrcY = StepPredIntra4x4SrcY;
              
    x = BlockX * 4;
    y = BlockY * 4;

    Cost = BestCost = ARM_VCM4P10_MAX_COST;
    
    /* Go through each mode for minim cost */
    for (mode = 0; mode < 9; mode++)
    {
        Ret = omxVCM4P10_PredictIntra_4x4(
             pSrcY - 1,
             pSrcY - StepSrcY,
             pSrcY - StepSrcY - 1,
             pPredBlock,
             StepSrcY,
             4,
             (OMXVCM4P10Intra4x4PredMode) mode,
             availability);
             
        if (Ret == OMX_Sts_NoErr)
        {            
            armVCCOMM_SAD(    
                pSrcCurrBuf + (y * SrcCurrStep) + x,
                SrcCurrStep,
                pPredBlock,
                4,
                &Cost,
                4,
                4);
            
            if (Cost < BestCost)
            {
                BestCost = Cost;
                
                pIntra4x4PredMode [BlockY + 1][BlockX + 1] = 
                    (OMXVCM4P10Intra4x4PredMode) mode;                
                pSrcDstMBCurr->pIntra4x4PredMode [BlockY * 4 + BlockX] = 
                    (OMXVCM4P10Intra4x4PredMode) mode;

                for (j = 0; j < 4; j++)
                {
                    for (i = 0; i < 4; i++)
                    {
                        pSrcY [StepSrcY * j + i] = pPredBlock [4 * j + i];
                    }
                }
            }
        }
    }

    *pBestCost = BestCost;
    return;
}

/**
 * Function: armVCM4P10_SetMotionVectorPredictor
 *
 * Description:
 *    This function will do the MV Prediction for Inter MBs
 *
 * Parameters:
 * [in] BlockStartX - Start X index in integer pels in current Block
 * [in] BlockStartY - Start Y index in integer pels in current Block
 * [in] BlockSizeX  - Width of current block
 * [in] BlockSizeY  - Height of current block
 * [in] RefFrame    - Index of the reference frame for prediction
 * [in] pRefFrArr   - Pointer to Ref array storing neighbouring MVs for MV prediction
 * [in] pMVArr      - Pointer to MV array storing neighbouring MVs for MV prediction
 * [out] pMVPred    - Pointer to predicted MVs
 * Remarks:
 *
 * Return Value:
 * None
 *
 */
static OMXVoid armVCM4P10_SetMotionVectorPredictor(
    OMX_U32 BlockStartX, 
    OMX_U32 BlockStartY,
    OMX_U32 BlockSizex,
    OMX_U32 BlockSizey,
    OMX_S32 RefFrame,
    OMX_S32 pRefFrArr[][6], 
    OMXVCMotionVector pMVArr[][12],
    OMXVCMotionVector *pMVPred
)
{
    OMX_S32     RFrameL;       /* Left */
    OMX_S32     RFrameU;       /* Up */
    OMX_S32     RFrameUR;      /* Up-Right */

    OMX_S32     BlockX, BlockY, BlockXFr, BlockYFr, MVPredType;
    OMX_S32     BlockXPlusOff, BlockXPlusOffFr, BlockXMin1Fr, BlockYMin1Fr;
    
    BlockX = 4 + (BlockStartX >> 2);
    BlockY = 4 + (BlockStartY >> 2); 
    BlockXPlusOff = BlockX + (BlockSizex >> 2);
    
    BlockXFr = BlockX >> 1;  
    BlockYFr = BlockY >> 1;  
    BlockXMin1Fr = (BlockX - 1) >> 1;  
    BlockYMin1Fr = (BlockY - 1) >> 1;  
    BlockXPlusOffFr = BlockXPlusOff >> 1;
    
    MVPredType = ARM_VCM4P10_MVPRED_MEDIAN;

    RFrameL = pRefFrArr [BlockYFr][BlockXMin1Fr];
    RFrameU = pRefFrArr [BlockYMin1Fr][BlockXFr];
    RFrameUR = pRefFrArr [BlockYMin1Fr][BlockXPlusOffFr];

    if (RFrameUR == ARM_VCM4P10_INVALID_BLOCK)
    {
        RFrameUR = pRefFrArr [BlockYMin1Fr][BlockXMin1Fr];
    }

    /* 
     * Prediction if only one of the neighbors uses the reference frame
     * we are checking
     */
  
    if (RFrameL == RefFrame && RFrameU != RefFrame && RFrameUR != RefFrame)
    {
        MVPredType = ARM_VCM4P10_MVPRED_L;
    }
    else if(RFrameL != RefFrame && RFrameU == RefFrame && RFrameUR != RefFrame)
    {
        MVPredType = ARM_VCM4P10_MVPRED_U;
    }
    else if(RFrameL != RefFrame && RFrameU != RefFrame && RFrameUR == RefFrame)
    {
        MVPredType = ARM_VCM4P10_MVPRED_UR;
    }

    /* Directional predictions  */
    else if(BlockSizex == 8 && BlockSizey == 16)
    {
        if(BlockStartX == 0)
        {
            if(RFrameL == RefFrame)
            {
                MVPredType = ARM_VCM4P10_MVPRED_L;
            }
        }
        else
        {
            if (RFrameUR == RefFrame)
            {
                MVPredType = ARM_VCM4P10_MVPRED_UR;
            }
        }
    }
    else if(BlockSizex == 16 && BlockSizey == 8)
    {
        if(BlockStartY == 0)
        {
            if(RFrameU == RefFrame)
            {
                MVPredType = ARM_VCM4P10_MVPRED_U;
            }
        }
        else
        {
            if(RFrameL == RefFrame)
            {
                MVPredType = ARM_VCM4P10_MVPRED_L;
            }
        }
    }

    switch (MVPredType)
    {
    case ARM_VCM4P10_MVPRED_MEDIAN:
        if (!(pRefFrArr [BlockYMin1Fr][BlockXMin1Fr] == ARM_VCM4P10_INVALID_BLOCK || 
              pRefFrArr [BlockYMin1Fr][BlockXFr] == ARM_VCM4P10_INVALID_BLOCK || 
              pRefFrArr [BlockYMin1Fr][BlockXPlusOffFr] == ARM_VCM4P10_INVALID_BLOCK))
        {
            pMVPred->dx = pMVArr [BlockY][BlockX - 1].dx;
            pMVPred->dy = pMVArr [BlockY][BlockX - 1].dy;
        }
        else
        {
            pMVPred->dx = 
                ARM_VCM4P10_MEDIAN(pMVArr [BlockY][BlockX - 1].dx, 
                pMVArr [BlockY - 1][BlockX].dx, 
                pMVArr [BlockY - 1][BlockXPlusOff].dx);
            pMVPred->dy = 
                ARM_VCM4P10_MEDIAN(pMVArr [BlockY][BlockX - 1].dy, 
                pMVArr [BlockY - 1][BlockX].dy, 
                pMVArr [BlockY - 1][BlockXPlusOff].dy);
        }
        break;
      
    case ARM_VCM4P10_MVPRED_L:
        pMVPred->dx = pMVArr [BlockY][BlockX - 1].dx;
        pMVPred->dy = pMVArr [BlockY][BlockX - 1].dy;
        break;
    case ARM_VCM4P10_MVPRED_U:
        pMVPred->dx = pMVArr [BlockY - 1][BlockX].dx;
        pMVPred->dy = pMVArr [BlockY - 1][BlockX].dy;
        break;
    case ARM_VCM4P10_MVPRED_UR:
        if (pRefFrArr [BlockYMin1Fr][BlockXPlusOffFr] != ARM_VCM4P10_INVALID_BLOCK)
        {
            pMVPred->dx = pMVArr [BlockY - 1][BlockXPlusOff].dx;
            pMVPred->dy = pMVArr [BlockY - 1][BlockXPlusOff].dy;
        }
        else
        {
            pMVPred->dx = pMVArr [BlockY - 1][BlockX - 1].dx;
            pMVPred->dy = pMVArr [BlockY - 1][BlockX - 1].dy;
        }
        break;
    default:
        break;
    }
    
    return;
}

/**
 * Function: armVCM4P10_BlockMotionSearch
 *
 * Description:
 *    Gets best MV for the current block
 *
 * Parameters:
 * [in] pSrcCurrBuf    - Pointer to the start of luma component of current Macroblock 
 * [in] SrcCurrStep - Step size for the pointer pSrcCurrBuf 
 * [in] pSrcRefY    - Pointer to the start of luma component of co-located reference MB
 * [in] nSrcRefStep - Step size for the pointer pSrcRefY 
 * [in] pRefRect   Pointer to the valid reference rectangle; relative to the image origin.
 * [in] pCurrPointPos   Position of the current macroblock in the current plane.
 * [in] pMESpec     - Motion estimation structure
 * [in] pMBInter    - Array, of dimension four, containing pointers to information associated with four
 *                    adjacent type INTER MBs (Left, Top, Top-Left, Top-Right). 
 * [in] nLamda      - For calculating the cost
 * [out] pBestCost  - Minimum cost for encoding current block 
 * [out] pBestMV    - MV corresponding to best cost
 * [in] BlockStartX - Block start X index in integer pels
 * [in] BlockStartY - Block start Y index in integer pels
 * [in] BlockSizeX  - Width of current block
 * [in] BlockSizeY  - Height of current block
 * [in] RefFrame    - Index of the reference frame for prediction
 * [in] pRefFrArr   - Pointer to reference frame array storing neighbouring MVs for prediction
 * [in] pMVArr      - Pointer to MV array storing neighbouring MVs for MV prediction
 * [in] pMVPred     - Pointer to MV predicted from neighbour MVs
 * Remarks:
 *
 * Return Value:
 * OMXResult
 *
 */
static OMXResult armVCM4P10_BlockMotionSearch(
    const OMX_U8* pSrcCurrBuf, 
    OMX_S32 SrcCurrStep, 
    const OMX_U8* pSrcRefY, 
    OMX_S32 nSrcRefStep, 
	const OMXRect *pRefRect,
	const OMXVCM4P2Coordinate *pCurrPointPos,
    void* pMESpec, 

    OMX_S32 nLamda,
    OMX_S32* pBestCost, 
    OMXVCMotionVector *pBestMV,
    
    OMX_U32 BlockStartX, 
    OMX_U32 BlockStartY,
    OMX_U32 BlockSizeX,
    OMX_U32 BlockSizeY,
    OMX_S32 RefFrame,
    OMX_S32 pRefFrArr [][6], 
    OMXVCMotionVector pMVArr [][12],
    OMXVCMotionVector *pMVPred
   )
{

    OMXVCMotionVector   MVCalculated, MVCandidate;
    OMX_S32             Cost;
    OMXResult           RetValue;
    OMXVCM4P10MEParams  *pMEParams;    
	OMXVCM4P2Coordinate CurrBlockPos;

    /* Get Predicted Motion Vectors */
    armVCM4P10_SetMotionVectorPredictor (
        BlockStartX, 
        BlockStartY,
        BlockSizeX, 
        BlockSizeY,
        RefFrame,
        pRefFrArr,   
        pMVArr,      
        pMVPred);

    /* Initialize candidate MV */
    MVCandidate.dx = 0;
    MVCandidate.dy = 0;
    
    CurrBlockPos.x = pCurrPointPos->x + BlockStartX;
    CurrBlockPos.y = pCurrPointPos->y + BlockStartY;

    /* Block Match Integer */
    RetValue = omxVCM4P10_BlockMatch_Integer (
        pSrcCurrBuf, 
        SrcCurrStep, 
        pSrcRefY, 
        nSrcRefStep, 
        pRefRect, 
        &CurrBlockPos, 
        BlockSizeX, 
        BlockSizeY, 
        nLamda, 
        pMVPred, 
        &MVCandidate, 
        &MVCalculated, 
        &Cost, 
        pMESpec);
    
    /* updated BestMV*/
    /**pBestCost = Cost;
    pBestMV->dx = MVCalculated.dx;
    pBestMV->dy = MVCalculated.dy;*/

    pMEParams = (OMXVCM4P10MEParams *) pMESpec;
    
    /* Block Match Half pel */
    if (pMEParams->halfSearchEnable)
    {
        RetValue = omxVCM4P10_BlockMatch_Half(
            pSrcCurrBuf, 
            SrcCurrStep, 
            pSrcRefY, 
            nSrcRefStep, 
            BlockSizeX, 
            BlockSizeY, 
            nLamda, 
            pMVPred, 
            &MVCalculated,        /* input/output*/
            &Cost);
    }

    /* Block Match Quarter pel */
    if (pMEParams->quarterSearchEnable)
    {
        RetValue = omxVCM4P10_BlockMatch_Quarter(
            pSrcCurrBuf, 
            SrcCurrStep, 
            pSrcRefY, 
            nSrcRefStep, 
            BlockSizeX, 
            BlockSizeY, 
            nLamda, 
            pMVPred, 
            &MVCalculated, 
            &Cost);
    }

    /* updated Best Cost and Best MV */
    *pBestCost = Cost;
    pBestMV->dx = MVCalculated.dx;
    pBestMV->dy = MVCalculated.dy;

    /*
     * Skip MB cost calculations of 16x16 inter mode
     */
    return RetValue;
}

/**
 * Function: armVCM4P10_PartitionME
 *
 * Description:
 *    Gets best cost for the current partition
 *
 * Parameters:
 * [in] pSrcCurrBuf    - Pointer to the start of luma component of current Macroblock 
 * [in] SrcCurrStep - Step size for the pointer pSrcCurrBuf 
 * [in] pSrcRefBufList    - Pointer to List of ref buffer of co-located reference MB
 * [in] nSrcRefStep - Step size for the pointer pSrcRefY 
 * [in] pRefRect   Pointer to the valid reference rectangle; relative to the image origin.
 * [in] pCurrPointPos   Position of the current macroblock in the current plane.
 * [in] pMESpec     - Motion estimation structure
 * [in] PartWidth   - Width of current partition
 * [in] PartHeight  - Height of current partition
 * [in] BlockWidth  - Width of current block
 * [in] BlockHeight - Height of current block
 * [in] PartStartX  - Partition start X index in integer pels
 * [in] PartStartY  - Partition start Y index in integer pels
 * [in] pMVArr      - Pointer to MV array storing neighbouring MVs for MV prediction
 * [in] pRefFrArr   - Pointer to reference frame array storing neighbouring MVs for prediction
 * [in] Lambda      - For calculating the cost
 * [out] pCost      - Pointer to cost for Inter MB
 *
 * Return Value:
 * OMXResult
 *
 */
static OMXResult armVCM4P10_PartitionME (
    const OMX_U8* pSrcCurrBuf,   
    OMX_S32 SrcCurrStep,
	const OMX_U8 *pSrcRefBufList[ARM_VCM4P10_MAX_FRAMES],
	OMX_S32 SrcRefStep,
	const OMXRect *pRefRect,
	const OMXVCM4P2Coordinate *pCurrPointPos,
    void* pMESpec, 

    OMX_S32 PartWidth,
    OMX_S32 PartHeight,
    OMX_S32 BlockWidth,
    OMX_S32 BlockHeight,
    OMX_S32 PartStartX, 
    OMX_S32 PartStartY,

    OMXVCMotionVector pMVArr [][12],
    OMX_S32 pRefFrArr [][6],
    OMXVCMotionVector pMVPredArr [][4],

    OMX_S32 Lambda,
    OMX_S32 *pCost
)
{
    OMX_U32     x, y, i, j, ref, OffX, OffY, OffSrc, OffRef;
    OMX_S32     BlockCost, PartitionCost, BestCost;
    OMX_S32     BestRefFrame=0;
    OMXVCMotionVector   BestMV [4][4];
    OMXVCMotionVector   BestMVPred [4][4];
    OMXVCMotionVector   MVPred;
    OMXVCMotionVector   DstMV;

    BestCost = ARM_VCM4P10_MAX_COST;
    
    for (ref = 0; ref < ARM_VCM4P10_MAX_FRAMES; ref++)
    {
        if (pSrcRefBufList [ref] == NULL)
        {
        	/* No reference frame, continue */
        	continue;
        }

        PartitionCost = 0;
        
        for (y = 0; y < PartHeight; y += BlockHeight)
        {
            for (x = 0; x < PartWidth; x += BlockWidth)
            {
            	OffSrc = SrcCurrStep * (PartStartY + y) + PartStartX + x;
            	OffRef = SrcRefStep * (PartStartY + y) + PartStartX + x;
                armVCM4P10_BlockMotionSearch (
                    pSrcCurrBuf + OffSrc, 
                    SrcCurrStep, 
                    pSrcRefBufList [ref] + OffRef, 
                    SrcRefStep, 
                    pRefRect,
                    pCurrPointPos,
                    pMESpec, 

                    Lambda,
                    &BlockCost, 
                    &DstMV,
                    
                    x + PartStartX, 
                    y + PartStartY,
                    BlockWidth,
                    BlockHeight,
                    ref,
                    pRefFrArr, 
                    pMVArr,
                    &MVPred);

                PartitionCost += BlockCost;
				
				OffX = (PartStartX + x) >> 2;
				OffY = (PartStartY + y) >> 2;
				
	            for (j = 0; j < (BlockHeight >> 2); j++)
	            {
	                for (i = 0; i < (BlockWidth >> 2); i++)
	                {
	                    pMVArr [4 + OffY + j][4 + OffX + i].dx = DstMV.dx;
	                    pMVArr [4 + OffY + j][4 + OffX + i].dy = DstMV.dy;
	                    pMVPredArr [OffY + j][OffX + i].dx = MVPred.dx;
	                    pMVPredArr [OffY + j][OffX + i].dy = MVPred.dy;
	                }
	            }

				pRefFrArr [2 + (OffY >> 1)][2 + (OffX >> 1)] = ref;
	            for (j = 0; j < (BlockHeight >> 3); j++)
	            {
	                for (i = 0; i < (BlockWidth >> 3); i++)
	                {
			            pRefFrArr [2 + (OffY >> 1) + j][2 + (OffX >> 1) + i] = ref;
	                }
	            }

            }
        }

		/*
		 * If PartitionCost is less for this reference frame, motion vectors needs to be backedup
		 */
        if (PartitionCost <= BestCost)
        {
            BestCost = PartitionCost;            
            BestRefFrame = ref;
            
            for (y = 0; y < (PartHeight/BlockHeight); y++)
            {
                for (x = 0; x < (PartWidth/BlockWidth); x++)
                {
					OffX = (PartStartX + x * BlockWidth) >> 2;
					OffY = (PartStartY + y * BlockHeight) >> 2;
				
                    BestMV[y][x].dx = pMVArr [4 + OffY][4 + OffX].dx;
                    BestMV[y][x].dy = pMVArr [4 + OffY][4 + OffX].dy;
                    BestMVPred[y][x].dx = pMVPredArr [OffY][OffX].dx;
                    BestMVPred[y][x].dy = pMVPredArr [OffY][OffX].dy;
                }
            }
        }

    }

	/*
	 * Copy back best reference frame, motion vectors and cost.
	 */
    for (y = 0; y < (PartHeight/BlockHeight); y++)
    {
        for (x = 0; x < (PartWidth/BlockWidth); x++)
        {
			OffX = (PartStartX + x * BlockWidth) >> 2;
			OffY = (PartStartY + y * BlockHeight) >> 2;            
            
            for (j = 0; j < (BlockHeight >> 2); j++)
            {
                for (i = 0; i < (BlockWidth >> 2); i++)
                {
                    pMVArr [4 + OffY + j][4 + OffX + i].dx = BestMV[y][x].dx;
                    pMVArr [4 + OffY + j][4 + OffX + i].dy = BestMV[y][x].dy;
                    pMVPredArr [OffY + j][OffX + i].dx = BestMVPred[y][x].dx;
                    pMVPredArr [OffY + j][OffX + i].dy = BestMVPred[y][x].dy;
                }
            }
            
            for (j = 0; j < (BlockHeight >> 3); j++)
            {
                for (i = 0; i < (BlockWidth >> 3); i++)
                {
		            pRefFrArr [2 + (OffY >> 1) + j][2 + (OffX >> 1) + i] = BestRefFrame;
                }
            }
        }
    }

	*pCost = BestCost;
    return OMX_Sts_NoErr;

}

/**
 * Function: armVCM4P10_Intra16x16Estimation
 *
 * Description:
 * Performs MB-level motion estimation for INTER MB type and selects best motion estimation strategy from 
 * the set of modes supported in baseline profile ISO/IEC 14496-10.
 *
 * Remarks:
 *
 * Parameters:
 * [in] pSrcCurrBuf    - Pointer to the start of luma component of current Macroblock 
 * [in] SrcCurrStep - Step size for the pointer pSrcCurrBuf 
 * [in] pSrcRecBuf    - Pointer to the start of luma component of co-located reconstructed MB 
 * [in] SrcRecStep - Step size for the pointer pSrcRecBuf 
 * [in] nMBPosX     - Position of MB in the frame w.r.t X axis
 * [in] nMBPosY     - Position of MB in the frame w.r.t Y axis
 * [in] pMBInter    - Array, of dimension four, containing pointers to information associated with four
 *                    adjacent type INTER MBs (Left, Top, Top-Left, Top-Right). 
 * [in] pMBIntra    - Array, of dimension four, containing pointers to information associated with four
 *                    adjacent type INTRA MBs (Left, Top, Top-Left, Top-Right). 
 * [in/out] pSrcDstMBCurr - Pointer to information structure for the current MB.  Following member should be set 
 *                    before calling this function
 * [in] Lambda      - For calculating the cost
 * [out] pCost      - Pointer to cost for Intra16x16
 * Return Value:
 * OMX_Sts_NoErr - No Error
 * OMX_Sts_BadArgErr - Bad arguments:
 *
 */

static OMXResult armVCM4P10_Intra16x16Estimation(
    const OMX_U8* pSrcCurrBuf,   
    OMX_S32 SrcCurrStep,
    const OMX_U8* pSrcRecBuf,   
    OMX_S32 SrcRecStep,
	const OMXVCM4P2Coordinate *pCurrPointPos,
    const OMXVCM4P10MBInfoPtr *pMBInter,
    const OMXVCM4P10MBInfoPtr *pMBIntra,
    OMXVCM4P10MBInfo *pSrcDstMBCurr,
    OMX_U32 *pCost)
{
    OMX_U8      PredBuf [16*16 + 16];
    OMX_U8      *pPred;
    OMX_S32     mode;
    OMX_S32     Cost;
    OMX_S32     availability = 0;
    OMXResult   Ret;
    OMXVCM4P10Intra16x16PredMode    IntraMode16x16 [4] = 
        {OMX_VC_16X16_VERT, OMX_VC_16X16_HOR, 
        OMX_VC_16X16_DC, OMX_VC_16X16_PLANE};        
    OMX_U32     MBPosX = pCurrPointPos->x >> 4;        
    OMX_U32     MBPosY = pCurrPointPos->y >> 4;        

	pPred = armAlignTo16Bytes(PredBuf);
    
	/* Check for availability of LEFT MB */
    if ((MBPosX != 0) && (pMBIntra [0] != 0 || pMBInter [0] != 0))
    {
        availability |= OMX_VC_LEFT;        
    }

    /* Check for availability of UP MB */
    if ((MBPosY != 0) && (pMBIntra [1] != 0 || pMBInter [1] != 0))
    {
        availability |= OMX_VC_UPPER;        
    }

    /* Check for availability of UP-LEFT MB */
    if ((MBPosX > 0) && (MBPosY > 0) && 
        (pMBIntra [2] != 0 || pMBInter [2] != 0))
    {
        availability |= OMX_VC_UPPER_LEFT;        
    }

    *pCost = ARM_VCM4P10_MAX_COST;
    for (mode = 0; mode < 4; mode++)
    {
        Ret = omxVCM4P10_PredictIntra_16x16(
                pSrcRecBuf - 1, 
                pSrcRecBuf - SrcRecStep, 
                pSrcRecBuf - SrcRecStep - 1, 
                pPred, 
                SrcRecStep, 
                16, 
                IntraMode16x16 [mode], 
                availability);
        if (Ret == OMX_Sts_NoErr)                         
        {
            armVCCOMM_SAD(    
                pSrcCurrBuf,
                SrcCurrStep,
                pPred,
                16,
                &Cost,
                16,
                16);
            if (Cost < *pCost)
            {
                *pCost = Cost;
                pSrcDstMBCurr->Intra16x16PredMode = IntraMode16x16 [mode];
            }
            
        }
        
    }

    return OMX_Sts_NoErr;
}

/**
 * Function: armVCM4P10_Intra4x4Estimation
 *
 * Description:
 * Performs MB-level motion estimation for Intra 4x4 MB type and selects
 * the best set of modes supported in baseline profile.
 *
 * Parameters:
 * [in] pSrcCurrBuf    - Pointer to the start of luma component of current Macroblock 
 * [in] SrcCurrStep - Step size for the pointer pSrcCurrBuf 
 * [in] pSrcRecBuf    - Pointer to the start of luma component of co-located reconstructed MB 
 * [in] SrcRecStep - Step size for the pointer pSrcRecBuf 
 * [in] nMBPosX     - Position of MB in the frame w.r.t X axis
 * [in] nMBPosY     - Position of MB in the frame w.r.t Y axis
 * [in] pMBIntra    - Array, of dimension four, containing pointers to information associated with four
 *                    adjacent type INTRA MBs (Left, Top, Top-Left, Top-Right). 
 * [in/out] pSrcDstMBCurr - Pointer to information structure for the current MB.  Following member should be set 
 *                    before calling this function
 * [in] Lambda      - For calculating the cost
 * [out] pCost      - Pointer to cost for Intra4x4
 * Return Value:
 * OMX_Sts_NoErr - No Error
 * OMX_Sts_BadArgErr - Bad arguments:
 *
 */

static OMXResult armVCM4P10_Intra4x4Estimation(
    const OMX_U8* pSrcCurrBuf,   
    OMX_S32 SrcCurrStep,
    const OMX_U8* pSrcRecBuf,   
    OMX_S32 SrcRecStep,
    const OMXVCM4P10MBInfoPtr *pMBIntra,
    OMXVCM4P10MBInfo *pSrcDstMBCurr,
    OMX_U32 *pCost)
{
    OMX_S32     x, y, Block4x4, Block8x8;
    OMX_S32     Cost;

    /*
     * PredIntra4x4Mode will store prediction modes of 4x4 blocks. 
     * Modes for current MB starts at index [1][1].   
     * Modes of nighbouring MB's will be as shown below
     * A value of ARM_VCM4P10_INVALID_BLOCK for any block in this array means 
     * that block is not available for prediction.
     *
     * c3 b0 b1 b2 b3 d0 d1 d2 d3
     * a0 xx xx xx xx -  -  -  -
     * a1 xx xx xx xx -  -  -  -
     * a2 xx xx xx xx -  -  -  -
     * a3 xx xx xx xx -  -  -  -
     *
     */
    OMX_S32     PredIntra4x4Mode [5][9];

    /*
     * pSrcY stores re-construsted source array of size 3MB X 2MB as below
     *
     * MB11 MB12 MB13 
     * MB21 MB22 MB23
     *
     * This array will be used for local reconstruction of 4x4 blocks 
     * with best prediction mode within an MB
     */    
    OMX_U8      pSrcY [(16*3)*(16*2)];
    OMX_S32     StepSrcY;
    
    /* init */
    *pCost = 0;

    for (y = 0; y < 5; y++)
    {
        for (x = 0; x < 9; x++)
        {
            /* 
             * Initialize with value of ARM_VCM4P10_INVALID_BLOCK, to mean this 
             * 4x4 block is not available 
             */
            PredIntra4x4Mode [y][x] = ARM_VCM4P10_INVALID_BLOCK;
        }
    }

    /* Replace ARM_VCM4P10_INVALID_BLOCK value with available MBs values*/
    for (x = 0; x < 4; x++)
    {
        /* Store values of b0, b1, b2, b3 */
        if (pMBIntra[1] != NULL)
        {
            PredIntra4x4Mode [0][x + 1] = 
                pMBIntra[1]->pIntra4x4PredMode[3*4 + x];            
        }
        
        /* Store values of d0, d1, d2, d3 */
        if (pMBIntra[3] != NULL)
        {
            PredIntra4x4Mode [0][x + 5] = 
                pMBIntra[3]->pIntra4x4PredMode[3*4 + x];
        }
    }
    
    /* Store values of c3 */
    if (pMBIntra[2] != NULL)
    {
        PredIntra4x4Mode [0][0] = pMBIntra[2]->pIntra4x4PredMode[15];
    }
    
    for (y = 0; y < 4; y++)
    {
        /* Store values of a0, a1, a2, a3 */
        if (pMBIntra[0] != NULL)
        {
            PredIntra4x4Mode [y + 1][0] = 
                pMBIntra[0]->pIntra4x4PredMode[y*4 + 3];
        }
    }
        
    /*
     * Update neighbouring Pred mode array which will be used for
     * prediction of Intra4x4 modes.
     */
    
    StepSrcY = 16 * 3;
    for (y = 0; y < (16 * 2); y++)
    {
        for (x = 0; x < (16 * 3); x++)
        {
            pSrcY [StepSrcY * y + x] = 
                pSrcRecBuf [SrcRecStep * (y - 16) + x - 16];
        }
    }
    
    /* for each 8x8 block */
    for (Block8x8 = 0; Block8x8 < 4; Block8x8++)
    {
        /* for each 4x4 block inside 8x8 block */
        for (Block4x4 = 0; Block4x4 < 4; Block4x4++)
        {
            armVCM4P10_Mode4x4Decision (
                pSrcCurrBuf,   
                SrcCurrStep,
                pSrcDstMBCurr,
                Block8x8, 
                Block4x4,
                pSrcY + 16 * StepSrcY + 16,
                StepSrcY,
                PredIntra4x4Mode, 
                &Cost);

            *pCost += Cost;
        }
    }
    return OMX_Sts_NoErr;
}

/**
 * Function: armVCM4P10_InterMEMB
 *
 * Description:
 * Performs MB-level motion estimation for INTER MB type and selects best motion estimation strategy from 
 * the set of modes supported in baseline profile ISO/IEC 14496-10.
 *
 * Remarks:
 *
 * Parameters:
 * [in] pSrcCurrBuf    - Pointer to the start of luma component of current Macroblock 
 * [in] SrcCurrStep - Step size for the pointer pSrcCurrBuf 
 * [in] pSrcRefBufList    - Pointer to the start of luma component of co-located reference MB
 * [in] SrcRefStep - Step size for the pointer pSrcRefY 
 * [in] pRefRect   Pointer to the valid reference rectangle; relative to the image origin.
 * [in] pCurrPointPos   Position of the current macroblock in the current plane.
 * [in] pMESpec     - Motion estimation structure
 * [in] pMBInter    - Array, of dimension four, containing pointers to information associated with four
 *                    adjacent type INTER MBs (Left, Top, Top-Left, Top-Right). 
 * [in/out] pSrcDstMBCurr - Pointer to information structure for the current MB.  Following member should be set 
 *                    before calling this function
 * [in] Lambda      - For calculating the cost
 * [out] pDstCost      - Pointer to cost for Inter MB
 * Return Value:
 * OMX_Sts_NoErr - No Error
 * OMX_Sts_BadArgErr - Bad arguments:
 *
 */

static OMXResult armVCM4P10_InterMEMB(
    const OMX_U8 *pSrcCurrBuf, 
	OMX_S32 SrcCurrStep, 
	const OMX_U8 *pSrcRefBufList[ARM_VCM4P10_MAX_FRAMES],
	OMX_S32 SrcRefStep,
	const OMXRect *pRefRect,
	const OMXVCM4P2Coordinate *pCurrPointPos,
	OMX_U32 Lambda,
	void *pMESpec,
	const OMXVCM4P10MBInfoPtr *pMBInter, 
    OMXVCM4P10MBInfoPtr pSrcDstMBCurr,
	OMX_U32 *pDstCost)
{
    OMX_S32     i, j, x, y, mode;
    OMX_U32     Block8x8, XPerMB, YPerMB, Block2x, Block2y;
    OMX_S32     PartStartX = 0, PartStartY = 0;
    OMX_S32     PartWidth = 8, PartHeight = 8, BlockWidth = 4, BlockHeight = 4;
    const OMX_U32     BlkSz [4][2] = {{4,4}, {4,8}, {8,4}};
    const OMX_U32     PartSz [4][2] = {{8,8}, {8,16}, {16,8}, {16,16}};
    const OMXVCM4P10SubMacroblockType     
                ModeSubMBType4x4 [] = {OMX_VC_SUB_P_4x4, OMX_VC_SUB_P_4x8, 
                              OMX_VC_SUB_P_8x4, OMX_VC_SUB_P_8x8};
    const OMXVCM4P10MacroblockType
                ModeMBType [] = {OMX_VC_P_8x8, OMX_VC_P_8x16, OMX_VC_P_16x8, OMX_VC_P_16x16};
    
    OMXVCM4P10MEParams  *pMBOptions;
    /*
     * RefFrArr and  MVArr will be used for temporary storage of Reference frame index and MVs
     * It will store RefIndex and MVs of 6 MBs as shown below
     * 
     *     |------|------|------|
     *     |Tp-Lt |Top   |Tp-R  |
     *     | MB   | MB   | MB   |
     *     |------|------|------|
     *     |Left  | Curr |      |
     *     | MB   | MB   |      |
     *     |------|------|------|
     */
    OMX_S32     RefFrArr [4][6]; 
    OMXVCMotionVector MVArr [8][12];
    OMXVCMotionVector MVPredArr [4][4];
    
    /*
     * IndexToLoc will translate pMBInter index into spacial arrangement of MBs
     */
    OMX_S32     IndexToLoc [] = {2,1,3,0};
    OMX_U32     part, MaxPart;
    OMX_S32     Cost, MotionCost8x8 [4], MBCost, BestCost;

    /*
     * Update neighbouring MV array and Ref frame array which will be used for
     * prediction of MVs and Ref frames.
     */

    /* Set cost to a high value */
    Cost = BestCost = ARM_VCM4P10_MAX_COST;
    
    for (y = 0; y < 8; y++)
    {
        for (x = 0; x < 12; x++)
        {
            i = 3 * (y >> 2) + (x >> 2);
            if ((y < 4 || x < 4) && (pMBInter[IndexToLoc[i]] != NULL))
            {
                MVArr [y][x].dx = 
                    pMBInter[IndexToLoc[i]]->pMV0[y % 4][x % 4].dx;
                MVArr [y][x].dy = 
                    pMBInter[IndexToLoc[i]]->pMV0[y % 4][x % 4].dy;
            }
            else
            {
                MVArr [y][x].dx = 0;
                MVArr [y][x].dy = 0;
            }
        }
    }    

    for (y = 0; y < 4; y++)
    {
        for (x = 0; x < 6; x++)
        {
            i = 3 * (y >> 1) + (x >> 1);
            if ((y < 2 || x < 2) && (pMBInter[IndexToLoc[i]] != NULL))
            {
                RefFrArr [y][x] = 
                    pMBInter[IndexToLoc[i]]->pRefL0Idx [(y % 2) * 2 + (x % 2)];
            }
            else
            {
                RefFrArr [y][x] = ARM_VCM4P10_INVALID_BLOCK;
            }
        }
    }    

    for (y = 0; y < 4; y++)
    {
        for (x = 0; x < 4; x++)
        {
            MVPredArr [y][x].dx = 0;
            MVPredArr [y][x].dy = 0;
        }
    }
    /*
     * Motion Estimation for 8x8 MB Partition 
     */

    for (i = 0; i < 4; i++)
    {
        MotionCost8x8 [i] = 0;
    }        
    
    pMBOptions = (OMXVCM4P10MEParams *) pMESpec;
    
    if (pMBOptions->blockSplitEnable8x8 == 1 && 
        pMBOptions->blockSplitEnable4x4 == 1)
    {
        pSrcDstMBCurr->mbType = OMX_VC_P_8x8;

        PartWidth = PartSz [0][0];
        PartHeight = PartSz [0][1];
        
        /* For each 8x8 partitions */
        for (Block8x8 = 0; Block8x8 < 4; Block8x8++)
        {
            PartStartX = (Block8x8 % 2) << 3;
            PartStartY = (Block8x8 / 2) << 3;

            Block2x = (Block8x8 & 1) << 1;
            Block2y = (Block8x8 >> 1) << 1;
            
            BestCost = ARM_VCM4P10_MAX_COST;
            for (mode = 0; mode < 3; mode++)
            {
                BlockWidth = BlkSz [mode][0];
                BlockHeight = BlkSz [mode][1];

                armVCM4P10_PartitionME (
                    pSrcCurrBuf,   
                    SrcCurrStep,
                    pSrcRefBufList,   
                    SrcRefStep,
                    pRefRect,
                    pCurrPointPos,
                    pMESpec,

                    PartWidth,
                    PartHeight,
                    BlockWidth,
                    BlockHeight,
                    PartStartX,
                    PartStartY,

                    MVArr,
                    RefFrArr,
                    MVPredArr,

                    Lambda,
                    &Cost);
                    
                if (Cost <= BestCost)
                {
                    /* Update cost */
                    BestCost = Cost;
                    
                    /* Update MBCurr struct */
                    pSrcDstMBCurr->subMBType [Block8x8] = ModeSubMBType4x4 [mode];
                    
                    pSrcDstMBCurr->pRefL0Idx [Block8x8] = RefFrArr [2 + (PartStartY >> 3)][2 + (PartStartX >> 3)];

                    /* Update pMV0 and pMVPred of MBCurr struct */
                    for (j = 0; j < 2; j++)
                    {
                        for (i = 0; i < 2; i++)
                        {
                            pSrcDstMBCurr->pMV0 [Block2y + j][Block2x + i].dx =
                                MVArr [4 + Block2y + j][4 + Block2x + i].dx;
                            pSrcDstMBCurr->pMV0 [Block2y + j][Block2x + i].dy =
                                MVArr [4 + Block2y + j][4 + Block2x + i].dy;
                            
                            pSrcDstMBCurr->pMVPred [Block2y + j][Block2x + i].dx =
                                MVPredArr [Block2y + j][Block2x + i].dx;
                            pSrcDstMBCurr->pMVPred [Block2y + j][Block2x + i].dy =
                                MVPredArr [Block2y + j][Block2x + i].dy;
                        }
                    }
                }
            }
            
            /* Update cost */
            MotionCost8x8 [Block8x8] = BestCost;
        }
        
        /* Cost for mbType OMX_VC_P_8x8 */
        BestCost = 0;
        for (i = 0; i < 4; i++)
        {
            BestCost += MotionCost8x8 [i];
        }
    }
    else
    {
        /* Set sub MB type to 8x8 */
        for (i = 0; i < 4; i++)
        {
            pSrcDstMBCurr->subMBType [i] = OMX_VC_SUB_P_8x8;
        }
    }

    /*
     * Motion Estimation for 8x8, 8x16, 16x8 and 16x16 MB Partition
     * If pMBOptions->b8x8BlockSplitEnable is 0, do only 16x16 ME (mode 3)
     */
    for (mode = (pMBOptions->blockSplitEnable8x8 == 1 ? 0 : 3); mode < 4; mode++)
    {
        BlockWidth = PartWidth = PartSz [mode][0];
        BlockHeight = PartHeight = PartSz [mode][1];
        
        XPerMB = 16 / PartWidth;
        YPerMB = 16 / PartHeight;
        MaxPart = XPerMB * YPerMB;
        
        MBCost = 0;
        
        /* part size 4, 2, 2 and 1 corresponding to 8x8, 8x16, 16x8 and 16x16 MB */
        for (part = 0; part < MaxPart; part++)
        {
        	PartStartX = (part % XPerMB) * PartWidth;
        	PartStartY = (part / XPerMB) * PartHeight;
        	
            armVCM4P10_PartitionME (
                pSrcCurrBuf,
                SrcCurrStep,
                pSrcRefBufList,   
                SrcRefStep,
                pRefRect,
                pCurrPointPos,
                pMESpec,

                PartWidth,
                PartHeight,
                BlockWidth,
                BlockHeight,
                PartStartX,
                PartStartY,

                MVArr,
                RefFrArr,
                MVPredArr,

                Lambda,
                &Cost);
                
                MBCost += Cost;
        }

        if (MBCost <= BestCost)
        {
            /* Update cost */
            BestCost = MBCost;
            
            /* Update mbType of MBCurr struct */
            pSrcDstMBCurr->mbType = ModeMBType [mode];
            
            /* Update pMV0 and pMVPred of MBCurr struct */
            for (j = 0; j < 4; j++)
            {
                for (i = 0; i < 4; i++)
                {
                    pSrcDstMBCurr->pMV0 [j][i].dx = MVArr [4+j][4+i].dx;
                    pSrcDstMBCurr->pMV0 [j][i].dy = MVArr [4+j][4+i].dy;
                    pSrcDstMBCurr->pMVPred [j][i].dx = MVPredArr [j][i].dx;
                    pSrcDstMBCurr->pMVPred [j][i].dy = MVPredArr [j][i].dy;
                }
            }
            for (j = 0; j < 2; j++)
            {
                for (i = 0; i < 2; i++)
                {
                    pSrcDstMBCurr->pRefL0Idx [j*2+i] = RefFrArr [2+j][2+i];
                }
            }
        }

    }

    /* Update Best Cost */
    *pDstCost = BestCost;
    
    return OMX_Sts_NoErr;
}

/**
 * Function:  omxVCM4P10_MotionEstimationMB   (6.3.5.3.1)
 *
 * Description:
 * Performs MB-level motion estimation and selects best motion estimation 
 * strategy from the set of modes supported in baseline profile [ISO14496-10]. 
 *
 * Input Arguments:
 *   
 *   pSrcCurrBuf - Pointer to the current position in original picture plane; 
 *            16-byte alignment required 
 *   pSrcRefBufList - Pointer to an array with 16 entries.  Each entry points 
 *            to the top-left corner of the co-located MB in a reference 
 *            picture.  The array is filled from low-to-high with valid 
 *            reference frame pointers; the unused high entries should be set 
 *            to NULL.  Ordering of the reference frames should follow 
 *            [ISO14496-10] subclause 8.2.4  Decoding Process for Reference 
 *            Picture Lists.   The entries must be 16-byte aligned. 
 *   pSrcRecBuf - Pointer to the top-left corner of the co-located MB in the 
 *            reconstructed picture; must be 16-byte aligned. 
 *   SrcCurrStep - Width of the original picture plane in terms of full 
 *            pixels; must be a multiple of 16. 
 *   SrcRefStep - Width of the reference picture plane in terms of full 
 *            pixels; must be a multiple of 16. 
 *   SrcRecStep - Width of the reconstructed picture plane in terms of full 
 *            pixels; must be a multiple of 16. 
 *   pRefRect - Pointer to the valid reference rectangle; relative to the 
 *            image origin. 
 *   pCurrPointPos - Position of the current macroblock in the current plane. 
 *   Lambda - Lagrange factor for computing the cost function 
 *   pMESpec - Pointer to the motion estimation specification structure; must 
 *            have been allocated and initialized prior to calling this 
 *            function. 
 *   pMBInter - Array, of dimension four, containing pointers to information 
 *            associated with four adjacent type INTER MBs (Left, Top, 
 *            Top-Left, Top-Right). Any pointer in the array may be set equal 
 *            to NULL if the corresponding MB doesn t exist or is not of type 
 *            INTER. pMBInter[0] - Pointer to left MB information pMBInter[1] 
 *            - Pointer to top MB information pMBInter[2] - Pointer to 
 *            top-left MB information pMBInter[3] - Pointer to top-right MB 
 *            information 
 *   pMBIntra - Array, of dimension four, containing pointers to information 
 *            associated with four adjacent type INTRA MBs (Left, Top, 
 *            Top-Left, Top-Right). Any pointer in the array may be set equal 
 *            to NULL if the corresponding MB doesn t exist or is not of type 
 *            INTRA. pMBIntra[0] - Pointer to left MB information pMBIntra[1] 
 *            - Pointer to top MB information pMBIntra[2] - Pointer to 
 *            top-left MB information pMBIntra[3] - Pointer to top-right MB 
 *            information 
 *   pSrcDstMBCurr - Pointer to information structure for the current MB.  
 *            The following entries should be set prior to calling the 
 *            function:  sliceID - the number of the slice the to which the 
 *            current MB belongs. 
 *
 * Output Arguments:
 *   
 *   pDstCost - Pointer to the minimum motion cost for the current MB. 
 *   pDstBlockSAD - Pointer to the array of SADs for each of the sixteen luma 
 *            4x4 blocks in each MB.  The block SADs are in scan order for 
 *            each MB.  For implementations that cannot compute the SAD values 
 *            individually, the maximum possible value (0xffff) is returned 
 *            for each of the 16 block SAD entries. 
 *   pSrcDstMBCurr - Pointer to updated information structure for the current 
 *            MB after MB-level motion estimation has been completed.  The 
 *            following fields are updated by the ME function.   The following 
 *            parameter set quantifies the MB-level ME search results: MbType 
 *            subMBType[4] pMV0[4][4] pMVPred[4][4] pRefL0Idx[4] 
 *            Intra16x16PredMode pIntra4x4PredMode[4][4] 
 *
 * Return Value:
 *    OMX_Sts_NoErr, if the function runs without error.
 *    OMX_Sts_BadArgErr - bad arguments: if one of the following cases occurs: 
 *    -   One of more of the following pointers is NULL: pSrcCurrBuf, 
 *           pSrcRefBufList, pSrcRecBuf, pRefRect, pCurrPointPos, pMESpec, 
 *           pMBInter, pMBIntra,pSrcDstMBCurr, pDstCost, pSrcRefBufList[0] 
 *    -    SrcRefStep, SrcRecStep are not multiples of 16 
 *    -    iBlockWidth or iBlockHeight are values other than 4, 8, or 16. 
 *    -    Any alignment restrictions are violated 
 *
 */
 
OMXResult omxVCM4P10_MotionEstimationMB(                    
    const OMX_U8 *pSrcCurrBuf,                                  
	OMX_S32 SrcCurrStep, 
	const OMX_U8 *pSrcRefBufList[ARM_VCM4P10_MAX_FRAMES],
	OMX_S32 SrcRefStep,
	const OMX_U8 *pSrcRecBuf, 
	OMX_S32 SrcRecStep,
	const OMXRect *pRefRect,
	const OMXVCM4P2Coordinate *pCurrPointPos,
	OMX_U32 Lambda,
	void *pMESpec,
	const OMXVCM4P10MBInfoPtr *pMBInter, 
	const OMXVCM4P10MBInfoPtr *pMBIntra,
    OMXVCM4P10MBInfo *pSrcDstMBCurr,
	OMX_INT *pDstCost,
    OMX_U16 *pDstBlockSAD)
{
    OMX_U32     Cost, i, IntraFlag = 1;
    OMXVCM4P10MEParams  *pMEParams; 

    /* check for argument error */
    armRetArgErrIf(pSrcCurrBuf == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pSrcRefBufList == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pSrcRecBuf == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pRefRect == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pCurrPointPos == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pMESpec == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pMBInter == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pMBIntra == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pSrcDstMBCurr == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pDstCost == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(SrcRefStep <= 0 || SrcRefStep & 15, OMX_Sts_BadArgErr)
    armRetArgErrIf(SrcRecStep <= 0 || SrcRecStep & 15, OMX_Sts_BadArgErr)
    armRetArgErrIf(SrcCurrStep <= 0 || SrcCurrStep & 15, OMX_Sts_BadArgErr)
    
    armRetArgErrIf(armNot16ByteAligned(pSrcCurrBuf), OMX_Sts_BadArgErr)    
    armRetArgErrIf(armNot16ByteAligned(pSrcRecBuf), OMX_Sts_BadArgErr)

    for (i = 0; i < ARM_VCM4P10_MAX_FRAMES; i++)
    {
        armRetArgErrIf(pSrcRefBufList [i] != NULL &&
            armNot16ByteAligned(pSrcRefBufList [i]), OMX_Sts_BadArgErr)
            
        /* Check if current MB needs INTER cost calculations */
        if (pSrcRefBufList [i] != NULL && IntraFlag == 1)
        {
            IntraFlag = 0;
        }
    }

    *pDstCost = ARM_VCM4P10_MAX_COST;
    /*
     * Inter cost calculations 
     */

     /* check this MB can be Inter */
    if (IntraFlag != 1)
    {
         armVCM4P10_InterMEMB(
             pSrcCurrBuf,   
             SrcCurrStep,
             pSrcRefBufList,   
             SrcRefStep,
             pRefRect,    
             pCurrPointPos,
             Lambda,
             pMESpec,
             pMBInter,
             pSrcDstMBCurr,
             &Cost
             );
        
        *pDstCost = Cost;
    }     

    pMEParams = (OMXVCM4P10MEParams *)pMESpec;
    
    if (pMEParams->intraEnable4x4 == 1)
    {
        /*
         * Intra 4x4 cost calculations
         */
        armVCM4P10_Intra4x4Estimation(
            pSrcCurrBuf,   
            SrcCurrStep,
            pSrcRecBuf,   
            SrcRecStep,
            pMBIntra,
            pSrcDstMBCurr,
            &Cost
            );

        if (Cost <= *pDstCost)
        {
            *pDstCost = Cost;
            pSrcDstMBCurr->mbType = OMX_VC_INTRA_4x4;

        }
        
    }

    /*
     * Cost for Intra 16x16 mode
     */

    armVCM4P10_Intra16x16Estimation(
        pSrcCurrBuf,   
        SrcCurrStep,
        pSrcRecBuf,   
        SrcRecStep,
        pCurrPointPos,
        pMBInter,
        pMBIntra,
        pSrcDstMBCurr,
        &Cost
        );

    if (Cost <= *pDstCost)
    {
        *pDstCost = Cost;
        pSrcDstMBCurr->mbType = OMX_VC_INTRA_16x16;
    }

    /*
     * Update pDstBlockSAD to max value
     */
	armVCM4P10_CalculateBlockSAD(	pSrcDstMBCurr, 
        pSrcCurrBuf,                                  
    	SrcCurrStep, 
    	pSrcRefBufList,
    	SrcRefStep,
    	pSrcRecBuf, 
    	SrcRecStep,
    	pRefRect,
    	pCurrPointPos,
    	pMBInter, 
    	pMBIntra,
    	pDstBlockSAD);


	return OMX_Sts_NoErr;
}


/*****************************************************************************
 *                              END OF FILE
 *****************************************************************************/
