/* ------------------------------------------------------------------
 * Copyright (C) 1998-2009 PacketVideo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 * -------------------------------------------------------------------
 */
#include "avcdec_lib.h"
#include "avcdec_bitstream.h"

//#define PV_ARM_V5
#ifdef PV_ARM_V5
#define PV_CLZ(A,B) __asm{CLZ (A),(B)}  \
    A -= 16;
#else
#define PV_CLZ(A,B) while (((B) & 0x8000) == 0) {(B) <<=1; A++;}
#endif


#define PV_NO_CLZ

#ifndef PV_NO_CLZ
typedef struct tagVLCNumCoeffTrail
{
    int trailing;
    int total_coeff;
    int length;
} VLCNumCoeffTrail;

typedef struct tagShiftOffset
{
    int shift;
    int offset;
} ShiftOffset;

const VLCNumCoeffTrail NumCoeffTrailOnes[3][67] =
{
    {{0, 0, 1}, {1, 1, 2}, {2, 2, 3}, {1, 2, 6}, {0, 1, 6}, {3, 3, 5}, {3, 3, 5}, {3, 5, 7},
        {2, 3, 7}, {3, 4, 6}, {3, 4, 6}, {3, 6, 8}, {2, 4, 8}, {1, 3, 8}, {0, 2, 8}, {3, 7, 9},
        {2, 5, 9}, {1, 4, 9}, {0, 3, 9}, {3, 8, 10}, {2, 6, 10}, {1, 5, 10}, {0, 4, 10}, {3, 9, 11},
        {2, 7, 11}, {1, 6, 11}, {0, 5, 11}, {0, 8, 13}, {2, 9, 13}, {1, 8, 13}, {0, 7, 13}, {3, 10, 13},
        {2, 8, 13}, {1, 7, 13}, {0, 6, 13}, {3, 12, 14}, {2, 11, 14}, {1, 10, 14}, {0, 10, 14}, {3, 11, 14},
        {2, 10, 14}, {1, 9, 14}, {0, 9, 14}, {3, 14, 15}, {2, 13, 15}, {1, 12, 15}, {0, 12, 15}, {3, 13, 15},
        {2, 12, 15}, {1, 11, 15}, {0, 11, 15}, {3, 16, 16}, {2, 15, 16}, {1, 15, 16}, {0, 14, 16}, {3, 15, 16},
        {2, 14, 16}, {1, 14, 16}, {0, 13, 16}, {0, 16, 16}, {2, 16, 16}, {1, 16, 16}, {0, 15, 16}, {1, 13, 15},
        { -1, -1, -1}, { -1, -1, -1}, { -1, -1, -1}},

    {{1, 1, 2}, {0, 0, 2}, {3, 4, 4}, {3, 3, 4}, {2, 2, 3}, {2, 2, 3}, {3, 6, 6}, {2, 3, 6},
        {1, 3, 6}, {0, 1, 6}, {3, 5, 5}, {3, 5, 5}, {1, 2, 5}, {1, 2, 5}, {3, 7, 6}, {2, 4, 6},
        {1, 4, 6}, {0, 2, 6}, {3, 8, 7}, {2, 5, 7}, {1, 5, 7}, {0, 3, 7}, {0, 5, 8}, {2, 6, 8},
        {1, 6, 8}, {0, 4, 8}, {3, 9, 9}, {2, 7, 9}, {1, 7, 9}, {0, 6, 9}, {3, 11, 11}, {2, 9, 11},
        {1, 9, 11}, {0, 8, 11}, {3, 10, 11}, {2, 8, 11}, {1, 8, 11}, {0, 7, 11}, {0, 11, 12}, {2, 11, 12},
        {1, 11, 12}, {0, 10, 12}, {3, 12, 12}, {2, 10, 12}, {1, 10, 12}, {0, 9, 12}, {3, 14, 13}, {2, 13, 13},
        {1, 13, 13}, {0, 13, 13}, {3, 13, 13}, {2, 12, 13}, {1, 12, 13}, {0, 12, 13}, {1, 15, 14}, {0, 15, 14},
        {2, 15, 14}, {1, 14, 14}, {2, 14, 13}, {2, 14, 13}, {0, 14, 13}, {0, 14, 13}, {3, 16, 14}, {2, 16, 14},
        {1, 16, 14}, {0, 16, 14}, {3, 15, 13}},

    {{3, 7, 4}, {3, 6, 4}, {3, 5, 4}, {3, 4, 4}, {3, 3, 4}, {2, 2, 4}, {1, 1, 4}, {0, 0, 4},
        {1, 5, 5}, {2, 5, 5}, {1, 4, 5}, {2, 4, 5}, {1, 3, 5}, {3, 8, 5}, {2, 3, 5}, {1, 2, 5},
        {0, 3, 6}, {2, 7, 6}, {1, 7, 6}, {0, 2, 6}, {3, 9, 6}, {2, 6, 6}, {1, 6, 6}, {0, 1, 6},
        {0, 7, 7}, {0, 6, 7}, {2, 9, 7}, {0, 5, 7}, {3, 10, 7}, {2, 8, 7}, {1, 8, 7}, {0, 4, 7},
        {3, 12, 8}, {2, 11, 8}, {1, 10, 8}, {0, 9, 8}, {3, 11, 8}, {2, 10, 8}, {1, 9, 8}, {0, 8, 8},
        {0, 12, 9}, {2, 13, 9}, {1, 12, 9}, {0, 11, 9}, {3, 13, 9}, {2, 12, 9}, {1, 11, 9}, {0, 10, 9},
        {1, 15, 10}, {0, 14, 10}, {3, 14, 10}, {2, 14, 10}, {1, 14, 10}, {0, 13, 10}, {1, 13, 9}, {1, 13, 9},
        {1, 16, 10}, {0, 15, 10}, {3, 15, 10}, {2, 15, 10}, {3, 16, 10}, {2, 16, 10}, {0, 16, 10}, { -1, -1, -1},
        { -1, -1, -1}, { -1, -1, -1}, { -1, -1, -1}}
};


const ShiftOffset NumCoeffTrailOnes_indx[3][15] =
{
    {{15, -1}, {14, 0}, {13, 1}, {10, -1}, {9, 3}, {8, 7}, {7, 11}, {6, 15},
        {5, 19}, {3, 19}, {2, 27}, {1, 35}, {0, 43}, {0, 55}, {1, 62}},

    {{14, -2}, {12, -2}, {10, -2}, {10, 10}, {9, 14}, {8, 18}, {7, 22}, {5, 22},
        {4, 30}, {3, 38}, {2, 46}, {2, 58}, {3, 65}, {16, 0}, {16, 0}},

    {{12, -8}, {11, 0}, {10, 8}, {9, 16}, {8, 24}, {7, 32}, {6, 40}, {6, 52},
        {6, 58}, {6, 61}, {16, 0}, {16, 0}, {16, 0}, {16, 0}, {16, 0}}
};

const static int nC_table[8] = {0, 0, 1, 1, 2, 2, 2, 2};

#endif
/**
See algorithm in subclause 9.1, Table 9-1, Table 9-2. */
AVCDec_Status ue_v(AVCDecBitstream *bitstream, uint *codeNum)
{
    uint temp, tmp_cnt;
    int leading_zeros = 0;
    BitstreamShowBits(bitstream, 16, &temp);
    tmp_cnt = temp  | 0x1;

    PV_CLZ(leading_zeros, tmp_cnt)

    if (leading_zeros < 8)
    {
        *codeNum = (temp >> (15 - (leading_zeros << 1))) - 1;
        BitstreamFlushBits(bitstream, (leading_zeros << 1) + 1);
    }
    else
    {
        BitstreamReadBits(bitstream, (leading_zeros << 1) + 1, &temp);
        *codeNum = temp - 1;
    }

    return AVCDEC_SUCCESS;
}

/**
See subclause 9.1.1, Table 9-3 */
AVCDec_Status  se_v(AVCDecBitstream *bitstream, int *value)
{
    uint temp, tmp_cnt;
    int leading_zeros = 0;
    BitstreamShowBits(bitstream, 16, &temp);
    tmp_cnt = temp | 0x1;

    PV_CLZ(leading_zeros, tmp_cnt)

    if (leading_zeros < 8)
    {
        temp >>= (15 - (leading_zeros << 1));
        BitstreamFlushBits(bitstream, (leading_zeros << 1) + 1);
    }
    else
    {
        BitstreamReadBits(bitstream, (leading_zeros << 1) + 1, &temp);
    }

    *value = temp >> 1;

    if (temp & 0x01)                          // lsb is signed bit
        *value = -(*value);

//  leading_zeros = temp >> 1;
//  *value = leading_zeros - (leading_zeros*2*(temp&1));

    return AVCDEC_SUCCESS;
}

AVCDec_Status  se_v32bit(AVCDecBitstream *bitstream, int32 *value)
{
    int leadingZeros;
    uint32 infobits;
    uint32 codeNum;

    if (AVCDEC_SUCCESS != GetEGBitstring32bit(bitstream, &leadingZeros, &infobits))
        return AVCDEC_FAIL;

    codeNum = (1 << leadingZeros) - 1 + infobits;

    *value = (codeNum + 1) / 2;

    if ((codeNum & 0x01) == 0)                        // lsb is signed bit
        *value = -(*value);

    return AVCDEC_SUCCESS;
}


AVCDec_Status te_v(AVCDecBitstream *bitstream, uint *value, uint range)
{
    if (range > 1)
    {
        ue_v(bitstream, value);
    }
    else
    {
        BitstreamRead1Bit(bitstream, value);
        *value = 1 - (*value);
    }
    return AVCDEC_SUCCESS;
}



/* This function is only used for syntax with range from -2^31 to 2^31-1 */
/* only a few of them in the SPS and PPS */
AVCDec_Status GetEGBitstring32bit(AVCDecBitstream *bitstream, int *leadingZeros, uint32 *infobits)
{
    int bit_value;
    uint info_temp;

    *leadingZeros = 0;

    BitstreamRead1Bit(bitstream, (uint*)&bit_value);

    while (!bit_value)
    {
        (*leadingZeros)++;
        BitstreamRead1Bit(bitstream, (uint*)&bit_value);
    }

    if (*leadingZeros > 0)
    {
        if (sizeof(uint) == 4)  /* 32 bit machine */
        {
            BitstreamReadBits(bitstream, *leadingZeros, (uint*)&info_temp);
            *infobits = (uint32)info_temp;
        }
        else if (sizeof(uint) == 2) /* 16 bit machine */
        {
            *infobits = 0;
            if (*leadingZeros > 16)
            {
                BitstreamReadBits(bitstream, 16, (uint*)&info_temp);
                (*leadingZeros) -= 16;
                *infobits = ((uint32)info_temp) << (*leadingZeros);
            }

            BitstreamReadBits(bitstream, *leadingZeros, (uint*)&info_temp);
            *infobits |= (uint32)info_temp ;
        }
    }
    else
        *infobits = 0;

    return AVCDEC_SUCCESS;
}

/* see Table 9-4 assignment of codeNum to values of coded_block_pattern. */
const static uint8 MapCBP[48][2] =
{
    {47, 0}, {31, 16}, {15, 1}, { 0, 2}, {23, 4}, {27, 8}, {29, 32}, {30, 3}, { 7, 5}, {11, 10}, {13, 12}, {14, 15},
    {39, 47}, {43, 7}, {45, 11}, {46, 13}, {16, 14}, { 3, 6}, { 5, 9}, {10, 31}, {12, 35}, {19, 37}, {21, 42}, {26, 44},
    {28, 33}, {35, 34}, {37, 36}, {42, 40}, {44, 39}, { 1, 43}, { 2, 45}, { 4, 46}, { 8, 17}, {17, 18}, {18, 20}, {20, 24},
    {24, 19}, { 6, 21}, { 9, 26}, {22, 28}, {25, 23}, {32, 27}, {33, 29}, {34, 30}, {36, 22}, {40, 25}, {38, 38}, {41, 41},
};

AVCDec_Status DecodeCBP(AVCMacroblock *currMB, AVCDecBitstream *stream)
{
    uint codeNum;
    uint coded_block_pattern;

    ue_v(stream, &codeNum);

    if (codeNum > 47)
    {
        return AVCDEC_FAIL;
    }

    /* can get rid of the if _OPTIMIZE */
    if (currMB->mbMode == AVC_I4)
    {
        coded_block_pattern = MapCBP[codeNum][0];
    }
    else
    {
        coded_block_pattern = MapCBP[codeNum][1];
    }

//  currMB->cbpL = coded_block_pattern&0xF;  /* modulo 16 */
//  currMB->cbpC = coded_block_pattern>>4;   /* divide 16 */
    currMB->CBP = coded_block_pattern;

    return AVCDEC_SUCCESS;
}


/* TO BE OPTIMIZED !!!!! */
AVCDec_Status ce_TotalCoeffTrailingOnes(AVCDecBitstream *stream, int *TrailingOnes, int *TotalCoeff, int nC)
{
#ifdef PV_NO_CLZ
    const static uint8 TotCofNTrail1[75][3] = {{0, 0, 16}/*error */, {0, 0, 16}/*error */, {1, 13, 15}, {1, 13, 15}, {0, 16, 16}, {2, 16, 16}, {1, 16, 16}, {0, 15, 16},
        {3, 16, 16}, {2, 15, 16}, {1, 15, 16}, {0, 14, 16}, {3, 15, 16}, {2, 14, 16}, {1, 14, 16}, {0, 13, 16},
        {3, 14, 15}, {2, 13, 15}, {1, 12, 15}, {0, 12, 15}, {3, 13, 15}, {2, 12, 15}, {1, 11, 15}, {0, 11, 15},
        {3, 12, 14}, {2, 11, 14}, {1, 10, 14}, {0, 10, 14}, {3, 11, 14}, {2, 10, 14}, {1, 9, 14}, {0, 9, 14},
        {0, 8, 13}, {2, 9, 13}, {1, 8, 13}, {0, 7, 13}, {3, 10, 13}, {2, 8, 13}, {1, 7, 13}, {0, 6, 13},
        {3, 9, 11}, {2, 7, 11}, {1, 6, 11}, {0, 5, 11}, {3, 8, 10},
        {2, 6, 10}, {1, 5, 10}, {0, 4, 10}, {3, 7, 9}, {2, 5, 9}, {1, 4, 9}, {0, 3, 9}, {3, 6, 8},
        {2, 4, 8}, {1, 3, 8}, {0, 2, 8}, {3, 5, 7}, {2, 3, 7}, {3, 4, 6}, {3, 4, 6}, {1, 2, 6},
        {1, 2, 6}, {0, 1, 6}, {0, 1, 6}, {3, 3, 5}, {3, 3, 5}, {3, 3, 5}, {3, 3, 5}, {2, 2, 3},
        {1, 1, 2}, {1, 1, 2}, {0, 0, 1}, {0, 0, 1}, {0, 0, 1}, {0, 0, 1}
    };

    const static uint8 TotCofNTrail2[84][3] = {{0, 0, 14 /* error */}, {0, 0, 14/*error */}, {3, 15, 13}, {3, 15, 13}, {3, 16, 14}, {2, 16, 14}, {1, 16, 14}, {0, 16, 14},
        {1, 15, 14}, {0, 15, 14}, {2, 15, 14}, {1, 14, 14}, {2, 14, 13}, {2, 14, 13}, {0, 14, 13}, {0, 14, 13},
        {3, 14, 13}, {2, 13, 13}, {1, 13, 13}, {0, 13, 13}, {3, 13, 13}, {2, 12, 13}, {1, 12, 13}, {0, 12, 13},
        {0, 11, 12}, {2, 11, 12}, {1, 11, 12}, {0, 10, 12}, {3, 12, 12}, {2, 10, 12}, {1, 10, 12}, {0, 9, 12},
        {3, 11, 11}, {2, 9, 11}, {1, 9, 11}, {0, 8, 11}, {3, 10, 11}, {2, 8, 11}, {1, 8, 11}, {0, 7, 11},
        {3, 9, 9}, {2, 7, 9}, {1, 7, 9}, {0, 6, 9}, {0, 5, 8}, {0, 5, 8}, {2, 6, 8}, {2, 6, 8},
        {1, 6, 8}, {1, 6, 8}, {0, 4, 8}, {0, 4, 8}, {3, 8, 7}, {2, 5, 7}, {1, 5, 7}, {0, 3, 7},
        {3, 7, 6}, {3, 7, 6}, {2, 4, 6}, {2, 4, 6}, {1, 4, 6}, {1, 4, 6}, {0, 2, 6}, {0, 2, 6},
        {3, 6, 6}, {2, 3, 6}, {1, 3, 6}, {0, 1, 6}, {3, 5, 5}, {3, 5, 5}, {1, 2, 5}, {1, 2, 5},
        {3, 4, 4}, {3, 3, 4}, {2, 2, 3}, {2, 2, 3}, {1, 1, 2}, {1, 1, 2}, {1, 1, 2}, {1, 1, 2},
        {0, 0, 2}, {0, 0, 2}, {0, 0, 2}, {0, 0, 2}
    };

    const static uint8 TotCofNTrail3[64][3] = {{0, 0, 10/*error*/}, {0, 16, 10}, {3, 16, 10}, {2, 16, 10}, {1, 16, 10}, {0, 15, 10}, {3, 15, 10},
        {2, 15, 10}, {1, 15, 10}, {0, 14, 10}, {3, 14, 10}, {2, 14, 10}, {1, 14, 10}, {0, 13, 10}, {1, 13, 9},
        {1, 13, 9}, {0, 12, 9}, {2, 13, 9}, {1, 12, 9}, {0, 11, 9}, {3, 13, 9}, {2, 12, 9}, {1, 11, 9},
        {0, 10, 9}, {3, 12, 8}, {2, 11, 8}, {1, 10, 8}, {0, 9, 8}, {3, 11, 8}, {2, 10, 8}, {1, 9, 8},
        {0, 8, 8}, {0, 7, 7}, {0, 6, 7}, {2, 9, 7}, {0, 5, 7}, {3, 10, 7}, {2, 8, 7}, {1, 8, 7},
        {0, 4, 7}, {0, 3, 6}, {2, 7, 6}, {1, 7, 6}, {0, 2, 6}, {3, 9, 6}, {2, 6, 6}, {1, 6, 6},
        {0, 1, 6}, {1, 5, 5}, {2, 5, 5}, {1, 4, 5}, {2, 4, 5}, {1, 3, 5}, {3, 8, 5}, {2, 3, 5},
        {1, 2, 5}, {3, 7, 4}, {3, 6, 4}, {3, 5, 4}, {3, 4, 4}, {3, 3, 4}, {2, 2, 4}, {1, 1, 4},
        {0, 0, 4}
    };
#endif
    uint code;

#ifdef PV_NO_CLZ
    uint8 *pcode;
    if (nC < 2)
    {
        BitstreamShowBits(stream, 16, &code);

        if (code >= 8192)
        {
            pcode = (uint8*) & (TotCofNTrail1[(code>>13)+65+2][0]);
        }
        else if (code >= 2048)
        {
            pcode = (uint8*) & (TotCofNTrail1[(code>>9)+50+2][0]);
        }
        else if (code >= 1024)
        {
            pcode = (uint8*) & (TotCofNTrail1[(code>>8)+46+2][0]);
        }
        else if (code >= 512)
        {
            pcode = (uint8*) & (TotCofNTrail1[(code>>7)+42+2][0]);
        }
        else if (code >= 256)
        {
            pcode = (uint8*) & (TotCofNTrail1[(code>>6)+38+2][0]);
        }
        else if (code >= 128)
        {
            pcode = (uint8*) & (TotCofNTrail1[(code>>5)+34+2][0]);
        }
        else if (code >= 64)
        {
            pcode = (uint8*) & (TotCofNTrail1[(code>>3)+22+2][0]);
        }
        else if (code >= 32)
        {
            pcode = (uint8*) & (TotCofNTrail1[(code>>2)+14+2][0]);
        }
        else if (code >= 16)
        {
            pcode = (uint8*) & (TotCofNTrail1[(code>>1)+6+2][0]);
        }
        else
        {
            pcode = (uint8*) & (TotCofNTrail1[(code-2)+2][0]);
        }

        *TrailingOnes = pcode[0];
        *TotalCoeff = pcode[1];

        BitstreamFlushBits(stream, pcode[2]);
    }
    else if (nC < 4)
    {
        BitstreamShowBits(stream, 14, &code);

        if (code >= 4096)
        {
            pcode = (uint8*) & (TotCofNTrail2[(code>>10)+66+2][0]);
        }
        else if (code >= 2048)
        {
            pcode = (uint8*) & (TotCofNTrail2[(code>>8)+54+2][0]);
        }
        else if (code >= 512)
        {
            pcode = (uint8*) & (TotCofNTrail2[(code>>7)+46+2][0]);
        }
        else if (code >= 128)
        {
            pcode = (uint8*) & (TotCofNTrail2[(code>>5)+34+2][0]);
        }
        else if (code >= 64)
        {
            pcode = (uint8*) & (TotCofNTrail2[(code>>3)+22+2][0]);
        }
        else if (code >= 32)
        {
            pcode = (uint8*) & (TotCofNTrail2[(code>>2)+14+2][0]);
        }
        else if (code >= 16)
        {
            pcode = (uint8*) & (TotCofNTrail2[(code>>1)+6+2][0]);
        }
        else
        {
            pcode = (uint8*) & (TotCofNTrail2[code-2+2][0]);
        }
        *TrailingOnes = pcode[0];
        *TotalCoeff = pcode[1];

        BitstreamFlushBits(stream, pcode[2]);
    }
    else if (nC < 8)
    {
        BitstreamShowBits(stream, 10, &code);

        if (code >= 512)
        {
            pcode = (uint8*) & (TotCofNTrail3[(code>>6)+47+1][0]);
        }
        else if (code >= 256)
        {
            pcode = (uint8*) & (TotCofNTrail3[(code>>5)+39+1][0]);
        }
        else if (code >= 128)
        {
            pcode = (uint8*) & (TotCofNTrail3[(code>>4)+31+1][0]);
        }
        else if (code >= 64)
        {
            pcode = (uint8*) & (TotCofNTrail3[(code>>3)+23+1][0]);
        }
        else if (code >= 32)
        {
            pcode = (uint8*) & (TotCofNTrail3[(code>>2)+15+1][0]);
        }
        else if (code >= 16)
        {
            pcode = (uint8*) & (TotCofNTrail3[(code>>1)+7+1][0]);
        }
        else
        {
            pcode = (uint8*) & (TotCofNTrail3[code-1+1][0]);
        }
        *TrailingOnes = pcode[0];
        *TotalCoeff = pcode[1];

        BitstreamFlushBits(stream, pcode[2]);
    }
    else
    {
        /* read 6 bit FLC */
        BitstreamReadBits(stream, 6, &code);


        *TrailingOnes = code & 3;
        *TotalCoeff = (code >> 2) + 1;

        if (*TotalCoeff > 16)
        {
            *TotalCoeff = 16;  // _ERROR
        }

        if (code == 3)
        {
            *TrailingOnes = 0;
            (*TotalCoeff)--;
        }
    }
#else
    const VLCNumCoeffTrail *ptr;
    const ShiftOffset *ptr_indx;
    uint temp, leading_zeros = 0;

    if (nC < 8)
    {

        BitstreamShowBits(stream, 16, &code);
        temp = code | 1;

        PV_CLZ(leading_zeros, temp)

        temp = nC_table[nC];
        ptr_indx = &NumCoeffTrailOnes_indx[temp][leading_zeros];
        ptr = &NumCoeffTrailOnes[temp][(code >> ptr_indx->shift) + ptr_indx->offset];
        *TrailingOnes = ptr->trailing;
        *TotalCoeff = ptr->total_coeff;
        BitstreamFlushBits(stream, ptr->length);
    }
    else
    {
        /* read 6 bit FLC */
        BitstreamReadBits(stream, 6, &code);


        *TrailingOnes = code & 3;
        *TotalCoeff = (code >> 2) + 1;

        if (*TotalCoeff > 16)
        {
            *TotalCoeff = 16;  // _ERROR
        }

        if (code == 3)
        {
            *TrailingOnes = 0;
            (*TotalCoeff)--;
        }
    }
#endif
    return AVCDEC_SUCCESS;
}

/* TO BE OPTIMIZED !!!!! */
AVCDec_Status ce_TotalCoeffTrailingOnesChromaDC(AVCDecBitstream *stream, int *TrailingOnes, int *TotalCoeff)
{
    AVCDec_Status status;

    const static uint8 TotCofNTrail5[21][3] =
    {
        {3, 4, 7}, {3, 4, 7}, {2, 4, 8}, {1, 4, 8}, {2, 3, 7}, {2, 3, 7}, {1, 3, 7},
        {1, 3, 7}, {0, 4, 6}, {0, 3, 6}, {0, 2, 6}, {3, 3, 6}, {1, 2, 6}, {0, 1, 6},
        {2, 2, 3}, {0, 0, 2}, {0, 0, 2}, {1, 1, 1}, {1, 1, 1}, {1, 1, 1}, {1, 1, 1}
    };

    uint code;
    uint8 *pcode;

    status = BitstreamShowBits(stream, 8, &code);

    if (code >= 32)
    {
        pcode = (uint8*) & (TotCofNTrail5[(code>>5)+13][0]);
    }
    else if (code >= 8)
    {
        pcode = (uint8*) & (TotCofNTrail5[(code>>2)+6][0]);
    }
    else
    {
        pcode = (uint8*) & (TotCofNTrail5[code][0]);
    }

    *TrailingOnes = pcode[0];
    *TotalCoeff = pcode[1];

    BitstreamFlushBits(stream, pcode[2]);

    return status;
}

/* see Table 9-6 */
AVCDec_Status ce_LevelPrefix(AVCDecBitstream *stream, uint *code)
{
    uint temp;
    uint leading_zeros = 0;
    BitstreamShowBits(stream, 16, &temp);
    temp |= 1 ;

    PV_CLZ(leading_zeros, temp)

    BitstreamFlushBits(stream, leading_zeros + 1);
    *code = leading_zeros;
    return AVCDEC_SUCCESS;
}

/* see Table 9-7 and 9-8 */
AVCDec_Status ce_TotalZeros(AVCDecBitstream *stream, int *code, int TotalCoeff)
{
    const static uint8 TotZero1[28][2] = {{15, 9}, {14, 9}, {13, 9}, {12, 8},
        {12, 8}, {11, 8}, {11, 8}, {10, 7}, {9, 7}, {8, 6}, {8, 6}, {7, 6}, {7, 6}, {6, 5}, {6, 5},
        {6, 5}, {6, 5}, {5, 5}, {5, 5}, {5, 5}, {5, 5}, {4, 4}, {3, 4},
        {2, 3}, {2, 3}, {1, 3}, {1, 3}, {0, 1}
    };

    const static uint8 TotZero2n3[2][18][2] = {{{14, 6}, {13, 6}, {12, 6}, {11, 6},
            {10, 5}, {10, 5}, {9, 5}, {9, 5}, {8, 4}, {7, 4}, {6, 4}, {5, 4}, {4, 3}, {4, 3},
            {3, 3}, {2, 3}, {1, 3}, {0, 3}},

        /*const static uint8 TotZero3[18][2]=*/{{13, 6}, {11, 6}, {12, 5}, {12, 5}, {10, 5},
            {10, 5}, {9, 5}, {9, 5}, {8, 4}, {5, 4}, {4, 4}, {0, 4}, {7, 3}, {7, 3}, {6, 3}, {3, 3},
            {2, 3}, {1, 3}}
    };

    const static uint8 TotZero4[17][2] = {{12, 5}, {11, 5}, {10, 5}, {0, 5}, {9, 4},
        {9, 4}, {7, 4}, {7, 4}, {3, 4}, {3, 4}, {2, 4}, {2, 4}, {8, 3}, {6, 3}, {5, 3}, {4, 3}, {1, 3}
    };

    const static uint8 TotZero5[13][2] = {{11, 5}, {9, 5}, {10, 4}, {8, 4}, {2, 4},
        {1, 4}, {0, 4}, {7, 3}, {7, 3}, {6, 3}, {5, 3}, {4, 3}, {3, 3}
    };

    const static uint8 TotZero6to10[5][15][2] = {{{10, 6}, {0, 6}, {1, 5}, {1, 5}, {8, 4},
            {8, 4}, {8, 4}, {8, 4}, {9, 3}, {7, 3}, {6, 3}, {5, 3}, {4, 3}, {3, 3}, {2, 3}},

        /*const static uint8 TotZero7[15][2]=*/{{9, 6}, {0, 6}, {1, 5}, {1, 5}, {7, 4},
            {7, 4}, {7, 4}, {7, 4}, {8, 3}, {6, 3}, {4, 3}, {3, 3}, {2, 3}, {5, 2}, {5, 2}},

        /*const static uint8 TotZero8[15][2]=*/{{8, 6}, {0, 6}, {2, 5}, {2, 5}, {1, 4},
            {1, 4}, {1, 4}, {1, 4}, {7, 3}, {6, 3}, {3, 3}, {5, 2}, {5, 2}, {4, 2}, {4, 2}},

        /*const static uint8 TotZero9[15][2]=*/{{1, 6}, {0, 6}, {7, 5}, {7, 5}, {2, 4},
            {2, 4}, {2, 4}, {2, 4}, {5, 3}, {6, 2}, {6, 2}, {4, 2}, {4, 2}, {3, 2}, {3, 2}},

        /*const static uint8 TotZero10[11][2]=*/{{1, 5}, {0, 5}, {6, 4}, {6, 4}, {2, 3},
            {2, 3}, {2, 3}, {2, 3}, {5, 2}, {4, 2}, {3, 2}, {0, 0}, {0, 0}, {0, 0}, {0, 0}}
    };

    const static uint8 TotZero11[7][2] = {{0, 4}, {1, 4}, {2, 3}, {2, 3}, {3, 3}, {5, 3}, {4, 1}};

    const static uint8 TotZero12to15[4][5][2] =
    {
        {{3, 1}, {2, 2}, {4, 3}, {1, 4}, {0, 4}},
        {{2, 1}, {3, 2}, {1, 3}, {0, 3}, {0, 0}},
        {{2, 1}, {1, 2}, {0, 2}, {0, 0}, {0, 0}},
        {{1, 1}, {0, 1}, {0, 0}, {0, 0}, {0, 0}}
    };

    uint temp, mask;
    int indx;
    uint8 *pcode;

    if (TotalCoeff == 1)
    {
        BitstreamShowBits(stream, 9, &temp);

        if (temp >= 256)
        {
            pcode = (uint8*) & (TotZero1[27][0]);
        }
        else if (temp >= 64)
        {
            pcode = (uint8*) & (TotZero1[(temp>>5)+19][0]);
        }
        else if (temp >= 8)
        {
            pcode = (uint8*) & (TotZero1[(temp>>2)+5][0]);
        }
        else
        {
            pcode = (uint8*) & (TotZero1[temp-1][0]);
        }

    }
    else if (TotalCoeff == 2 || TotalCoeff == 3)
    {
        BitstreamShowBits(stream, 6, &temp);

        if (temp >= 32)
        {
            pcode = (uint8*) & (TotZero2n3[TotalCoeff-2][(temp>>3)+10][0]);
        }
        else if (temp >= 8)
        {
            pcode = (uint8*) & (TotZero2n3[TotalCoeff-2][(temp>>2)+6][0]);
        }
        else
        {
            pcode = (uint8*) & (TotZero2n3[TotalCoeff-2][temp][0]);
        }
    }
    else if (TotalCoeff == 4)
    {
        BitstreamShowBits(stream, 5, &temp);

        if (temp >= 12)
        {
            pcode = (uint8*) & (TotZero4[(temp>>2)+9][0]);
        }
        else
        {
            pcode = (uint8*) & (TotZero4[temp][0]);
        }
    }
    else if (TotalCoeff == 5)
    {
        BitstreamShowBits(stream, 5, &temp);

        if (temp >= 16)
        {
            pcode = (uint8*) & (TotZero5[(temp>>2)+5][0]);
        }
        else if (temp >= 2)
        {
            pcode = (uint8*) & (TotZero5[(temp>>1)+1][0]);
        }
        else
        {
            pcode = (uint8*) & (TotZero5[temp][0]);
        }
    }
    else if (TotalCoeff >= 6 && TotalCoeff <= 10)
    {
        if (TotalCoeff == 10)
        {
            BitstreamShowBits(stream, 5, &temp);
        }
        else
        {
            BitstreamShowBits(stream, 6, &temp);
        }


        if (temp >= 8)
        {
            pcode = (uint8*) & (TotZero6to10[TotalCoeff-6][(temp>>3)+7][0]);
        }
        else
        {
            pcode = (uint8*) & (TotZero6to10[TotalCoeff-6][temp][0]);
        }
    }
    else if (TotalCoeff == 11)
    {
        BitstreamShowBits(stream, 4, &temp);


        if (temp >= 8)
        {
            pcode = (uint8*) & (TotZero11[6][0]);
        }
        else if (temp >= 4)
        {
            pcode = (uint8*) & (TotZero11[(temp>>1)+2][0]);
        }
        else
        {
            pcode = (uint8*) & (TotZero11[temp][0]);
        }
    }
    else
    {
        BitstreamShowBits(stream, (16 - TotalCoeff), &temp);
        mask = 1 << (15 - TotalCoeff);
        indx = 0;
        while ((temp&mask) == 0 && indx < (16 - TotalCoeff)) /* search location of 1 bit */
        {
            mask >>= 1;
            indx++;
        }

        pcode = (uint8*) & (TotZero12to15[TotalCoeff-12][indx]);
    }

    *code = pcode[0];
    BitstreamFlushBits(stream, pcode[1]);

    return AVCDEC_SUCCESS;
}

/* see Table 9-9 */
AVCDec_Status ce_TotalZerosChromaDC(AVCDecBitstream *stream, int *code, int TotalCoeff)
{
    const static uint8 TotZeroChrom1to3[3][8][2] =
    {
        {{3, 3}, {2, 3}, {1, 2}, {1, 2}, {0, 1}, {0, 1}, {0, 1}, {0, 1}},
        {{2, 2}, {2, 2}, {1, 2}, {1, 2}, {0, 1}, {0, 1}, {0, 1}, {0, 1}},
        {{1, 1}, {1, 1}, {1, 1}, {1, 1}, {0, 1}, {0, 1}, {0, 1}, {0, 1}},
    };


    uint temp;
    uint8 *pcode;

    BitstreamShowBits(stream, 3, &temp);
    pcode = (uint8*) & (TotZeroChrom1to3[TotalCoeff-1][temp]);

    *code = pcode[0];

    BitstreamFlushBits(stream, pcode[1]);

    return AVCDEC_SUCCESS;
}

/* see Table 9-10 */
AVCDec_Status ce_RunBefore(AVCDecBitstream *stream, int *code, int zerosLeft)
{
    const static int codlen[6] = {1, 2, 2, 3, 3, 3}; /* num bits to read */
    const static uint8 RunBeforeTab[6][8][2] = {{{1, 1}, {0, 1}, {0, 0}, {0, 0}, {0, 0}, {0, 0}, {0, 0}, {0, 0}},
        /*const static int RunBefore2[4][2]=*/{{2, 2}, {1, 2}, {0, 1}, {0, 1}, {0, 0}, {0, 0}, {0, 0}, {0, 0}},
        /*const static int RunBefore3[4][2]=*/{{3, 2}, {2, 2}, {1, 2}, {0, 2}, {0, 0}, {0, 0}, {0, 0}, {0, 0}},
        /*const static int RunBefore4[7][2]=*/{{4, 3}, {3, 3}, {2, 2}, {2, 2}, {1, 2}, {1, 2}, {0, 2}, {0, 2}},
        /*const static int RunBefore5[7][2]=*/{{5, 3}, {4, 3}, {3, 3}, {2, 3}, {1, 2}, {1, 2}, {0, 2}, {0, 2}},
        /*const static int RunBefore6[7][2]=*/{{1, 3}, {2, 3}, {4, 3}, {3, 3}, {6, 3}, {5, 3}, {0, 2}, {0, 2}}
    };

    uint temp;
    uint8 *pcode;
    int indx;

    if (zerosLeft <= 6)
    {
        BitstreamShowBits(stream, codlen[zerosLeft-1], &temp);

        pcode = (uint8*) & (RunBeforeTab[zerosLeft-1][temp][0]);

        *code = pcode[0];

        BitstreamFlushBits(stream, pcode[1]);
    }
    else
    {
        BitstreamReadBits(stream, 3, &temp);
        if (temp)
        {
            *code = 7 - temp;
        }
        else
        {
            BitstreamShowBits(stream, 9, &temp);
            temp <<= 7;
            temp |= 1;
            indx = 0;
            PV_CLZ(indx, temp)
            *code = 7 + indx;
            BitstreamFlushBits(stream, indx + 1);
        }
    }


    return AVCDEC_SUCCESS;
}
