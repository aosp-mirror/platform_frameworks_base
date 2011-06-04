/* ----------------------------------------------------------------
 *
 * 
 * File Name:  armVCM4P10_DecodeCoeffsToPair.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * 
 * H.264 decode coefficients module
 * 
 */
 
#ifdef DEBUG_ARMVCM4P10_DECODECOEFFSTOPAIR
#undef DEBUG_ON
#define DEBUG_ON
#endif
 
#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"
#include "armCOMM_Bitstream.h"
#include "armVCM4P10_CAVLCTables.h"

/* 4x4 DeZigZag table */

static const OMX_U8 armVCM4P10_ZigZag[16] =
{
    0, 1, 4, 8, 5, 2, 3, 6, 9, 12, 13, 10, 7, 11, 14, 15
};

/*
 * Description:
 * This function perform the work required by the OpenMAX
 * DecodeCoeffsToPair function and DecodeChromaDCCoeffsToPair.
 * Since most of the code is common we share it here.
 *
 * Parameters:
 * [in]	ppBitStream		Double pointer to current byte in bit stream buffer
 * [in]	pOffset			Pointer to current bit position in the byte pointed
 *								to by *ppBitStream
 * [in]	sMaxNumCoeff	Maximum number of non-zero coefficients in current
 *								block (4,15 or 16)
 * [in]	nTable          Table number (0 to 4) according to the five columns
 *                      of Table 9-5 in the H.264 spec
 * [out]	ppBitStream		*ppBitStream is updated after each block is decoded
 * [out]	pOffset			*pOffset is updated after each block is decoded
 * [out]	pNumCoeff		Pointer to the number of nonzero coefficients in
 *								this block
 * [out]	ppPosCoefbuf	Double pointer to destination residual
 *								coefficient-position pair buffer
 * Return Value:
 * Standard omxError result. See enumeration for possible result codes.

 */

OMXResult armVCM4P10_DecodeCoeffsToPair(
     const OMX_U8** ppBitStream,
     OMX_S32* pOffset,
     OMX_U8* pNumCoeff,
     OMX_U8  **ppPosCoefbuf,
     OMX_INT nTable,
     OMX_INT sMaxNumCoeff        
 )
{
    int CoeffToken, TotalCoeff, TrailingOnes;
    int Level, LevelCode, LevelPrefix, LevelSuffix, LevelSuffixSize;
    int SuffixLength, Run, ZerosLeft,CoeffNum;
    int i, Flags;
    OMX_U8 *pPosCoefbuf = *ppPosCoefbuf;
    OMX_S16 pLevel[16];
    OMX_U8  pRun[16];

    CoeffToken = armUnPackVLC32(ppBitStream, pOffset, armVCM4P10_CAVLCCoeffTokenTables[nTable]);
    armRetDataErrIf(CoeffToken == ARM_NO_CODEBOOK_INDEX, OMX_Sts_Err);

    TrailingOnes = armVCM4P10_CAVLCTrailingOnes[CoeffToken];
    TotalCoeff   = armVCM4P10_CAVLCTotalCoeff[CoeffToken];
    *pNumCoeff   = (OMX_U8)TotalCoeff;

    DEBUG_PRINTF_2("TotalCoeff = %d, TrailingOnes = %d\n", TotalCoeff, TrailingOnes);

    if (TotalCoeff == 0)
    {
        /* Nothing to do */
        return OMX_Sts_NoErr;
    }

    /* Decode trailing ones */
    for (i=TotalCoeff-1; i>=TotalCoeff-TrailingOnes; i--)
    {
        if (armGetBits(ppBitStream, pOffset, 1))
        {
            Level = -1;
        }
        else
        {
            Level = +1;
        }
        pLevel[i] = (OMX_S16)Level;

        DEBUG_PRINTF_2("Level[%d] = %d\n", i, pLevel[i]);
    }

    /* Decode (non zero) level values */
    SuffixLength = 0;
    if (TotalCoeff>10 && TrailingOnes<3)
    {
        SuffixLength=1;
    }
    for ( ; i>=0; i--)
    {
        LevelPrefix = armUnPackVLC32(ppBitStream, pOffset, armVCM4P10_CAVLCLevelPrefix);
        armRetDataErrIf(LevelPrefix == ARM_NO_CODEBOOK_INDEX, OMX_Sts_Err);

        LevelSuffixSize = SuffixLength;
        if (LevelPrefix==14 && SuffixLength==0)
        {
            LevelSuffixSize = 4;
        }
        if (LevelPrefix==15)
        {
            LevelSuffixSize = 12;
        }
        
        LevelSuffix = 0;
        if (LevelSuffixSize > 0)
        {
            LevelSuffix = armGetBits(ppBitStream, pOffset, LevelSuffixSize);
        }

        LevelCode = (LevelPrefix << SuffixLength) + LevelSuffix;


        if (LevelPrefix==15 && SuffixLength==0)
        {
            LevelCode += 15;
        }

        /* LevelCode = 2*(magnitude-1) + sign */

        if (i==TotalCoeff-1-TrailingOnes && TrailingOnes<3)
        {
            /* Level magnitude can't be 1 */
            LevelCode += 2;
        }
        if (LevelCode & 1)
        {
            /* 2a+1 maps to -a-1 */
            Level = (-LevelCode-1)>>1;
        }
        else
        {
            /* 2a+0 maps to +a+1 */
            Level = (LevelCode+2)>>1;
        }
        pLevel[i] = (OMX_S16)Level;

        DEBUG_PRINTF_2("Level[%d] = %d\n", i, pLevel[i]);

        if (SuffixLength==0)
        {
            SuffixLength=1;
        }
        if ( ((LevelCode>>1)+1)>(3<<(SuffixLength-1)) && SuffixLength<6 )
        {
            SuffixLength++;
        }
    }

    /* Decode run values */
    ZerosLeft = 0;
    if (TotalCoeff < sMaxNumCoeff)
    {
        /* Decode TotalZeros VLC */
        if (sMaxNumCoeff==4)
        {
            ZerosLeft = armUnPackVLC32(ppBitStream, pOffset, armVCM4P10_CAVLCTotalZeros2x2Tables[TotalCoeff-1]);
            armRetDataErrIf(ZerosLeft ==ARM_NO_CODEBOOK_INDEX , OMX_Sts_Err);
        }
        else
        {
            ZerosLeft = armUnPackVLC32(ppBitStream, pOffset, armVCM4P10_CAVLCTotalZeroTables[TotalCoeff-1]);
             armRetDataErrIf(ZerosLeft ==ARM_NO_CODEBOOK_INDEX , OMX_Sts_Err);
	    }
    }

    DEBUG_PRINTF_1("TotalZeros = %d\n", ZerosLeft);

	CoeffNum=ZerosLeft+TotalCoeff-1;

    for (i=TotalCoeff-1; i>0; i--)
    {
        Run = 0;
        if (ZerosLeft > 0)
        {
            int Table = ZerosLeft;
            if (Table > 6)
            {
                Table = 7;
            }
            Run = armUnPackVLC32(ppBitStream, pOffset, armVCM4P10_CAVLCRunBeforeTables[Table-1]);
            armRetDataErrIf(Run == ARM_NO_CODEBOOK_INDEX, OMX_Sts_Err);
        }
        pRun[i] = (OMX_U8)Run;

        DEBUG_PRINTF_2("Run[%d] = %d\n", i, pRun[i]);

        ZerosLeft -= Run;
    }
    pRun[0] = (OMX_U8)ZerosLeft;

    DEBUG_PRINTF_1("Run[0] = %d\n", pRun[i]);


    /* Fill in coefficients */
	    
    if (sMaxNumCoeff==15)
    {
        CoeffNum++; /* Skip the DC position */
    }
	
	/*for (i=0;i<TotalCoeff;i++)
		CoeffNum += pRun[i]+1;*/
    
	for (i=(TotalCoeff-1); i>=0; i--)
    {
        /*CoeffNum += pRun[i]+1;*/
        Level     = pLevel[i];

        DEBUG_PRINTF_2("Coef[%d] = %d\n", CoeffNum, Level);

        Flags = CoeffNum;
		CoeffNum -= (pRun[i]+1);
        if (sMaxNumCoeff>4)
        {
            /* Perform 4x4 DeZigZag */
            Flags = armVCM4P10_ZigZag[Flags];
        }
        if (i==0)
        {   
            /* End of block flag */
            Flags += 0x20;
        }
        if (Level<-128 || Level>127)
        {
            /* Overflow flag */
            Flags += 0x10;
        }
        
        *pPosCoefbuf++ = (OMX_U8)(Flags);
        *pPosCoefbuf++ = (OMX_U8)(Level & 0xFF);
        if (Flags & 0x10)
        {
            *pPosCoefbuf++ = (OMX_U8)(Level>>8);
        }
    }

    *ppPosCoefbuf = pPosCoefbuf;

    return OMX_Sts_NoErr;
}
