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
#include "avcenc_lib.h"

/**
See algorithm in subclause 9.1, Table 9-1, Table 9-2. */
AVCEnc_Status ue_v(AVCEncBitstream *bitstream, uint codeNum)
{
    if (AVCENC_SUCCESS != SetEGBitstring(bitstream, codeNum))
        return AVCENC_FAIL;

    return AVCENC_SUCCESS;
}

/**
See subclause 9.1.1, Table 9-3 */
AVCEnc_Status  se_v(AVCEncBitstream *bitstream, int value)
{
    uint codeNum;
    AVCEnc_Status status;

    if (value <= 0)
    {
        codeNum = -value * 2;
    }
    else
    {
        codeNum = value * 2 - 1;
    }

    status = ue_v(bitstream, codeNum);

    return status;
}

AVCEnc_Status te_v(AVCEncBitstream *bitstream, uint value, uint range)
{
    AVCEnc_Status status;

    if (range > 1)
    {
        return ue_v(bitstream, value);
    }
    else
    {
        status = BitstreamWrite1Bit(bitstream, 1 - value);
        return status;
    }
}

/**
See subclause 9.1, Table 9-1, 9-2. */
// compute leadingZeros and inforbits
//codeNum = (1<<leadingZeros)-1+infobits;
AVCEnc_Status SetEGBitstring(AVCEncBitstream *bitstream, uint codeNum)
{
    AVCEnc_Status status;
    int leadingZeros;
    int infobits;

    if (!codeNum)
    {
        status = BitstreamWrite1Bit(bitstream, 1);
        return status;
    }

    /* calculate leadingZeros and infobits */
    leadingZeros = 1;
    while ((uint)(1 << leadingZeros) < codeNum + 2)
    {
        leadingZeros++;
    }
    leadingZeros--;
    infobits = codeNum - (1 << leadingZeros) + 1;

    status = BitstreamWriteBits(bitstream, leadingZeros, 0);
    infobits |= (1 << leadingZeros);
    status = BitstreamWriteBits(bitstream, leadingZeros + 1, infobits);
    return status;
}

/* see Table 9-4 assignment of codeNum to values of coded_block_pattern. */
const static uint8 MapCBP2code[48][2] =
{
    {3, 0}, {29, 2}, {30, 3}, {17, 7}, {31, 4}, {18, 8}, {37, 17}, {8, 13}, {32, 5}, {38, 18}, {19, 9}, {9, 14},
    {20, 10}, {10, 15}, {11, 16}, {2, 11}, {16, 1}, {33, 32}, {34, 33}, {21, 36}, {35, 34}, {22, 37}, {39, 44}, {4, 40},
    {36, 35}, {40, 45}, {23, 38}, {5, 41}, {24, 39}, {6, 42}, {7, 43}, {1, 19}, {41, 6}, {42, 24}, {43, 25}, {25, 20},
    {44, 26}, {26, 21}, {46, 46}, {12, 28}, {45, 27}, {47, 47}, {27, 22}, {13, 29}, {28, 23}, {14, 30}, {15, 31}, {0, 12}
};

AVCEnc_Status EncodeCBP(AVCMacroblock *currMB, AVCEncBitstream *stream)
{
    AVCEnc_Status status;
    uint codeNum;

    if (currMB->mbMode == AVC_I4)
    {
        codeNum = MapCBP2code[currMB->CBP][0];
    }
    else
    {
        codeNum = MapCBP2code[currMB->CBP][1];
    }

    status = ue_v(stream, codeNum);

    return status;
}

AVCEnc_Status ce_TotalCoeffTrailingOnes(AVCEncBitstream *stream, int TrailingOnes, int TotalCoeff, int nC)
{
    const static uint8 totCoeffTrailOne[3][4][17][2] =
    {
        {   // 0702
            {{1, 1}, {6, 5}, {8, 7}, {9, 7}, {10, 7}, {11, 7}, {13, 15}, {13, 11}, {13, 8}, {14, 15}, {14, 11}, {15, 15}, {15, 11}, {16, 15}, {16, 11}, {16, 7}, {16, 4}},
            {{0, 0}, {2, 1}, {6, 4}, {8, 6}, {9, 6}, {10, 6}, {11, 6}, {13, 14}, {13, 10}, {14, 14}, {14, 10}, {15, 14}, {15, 10}, {15, 1}, {16, 14}, {16, 10}, {16, 6}},
            {{0, 0}, {0, 0}, {3, 1}, {7, 5}, {8, 5}, {9, 5}, {10, 5}, {11, 5}, {13, 13}, {13, 9}, {14, 13}, {14, 9}, {15, 13}, {15, 9}, {16, 13}, {16, 9}, {16, 5}},
            {{0, 0}, {0, 0}, {0, 0}, {5, 3}, {6, 3}, {7, 4}, {8, 4}, {9, 4}, {10, 4}, {11, 4}, {13, 12}, {14, 12}, {14, 8}, {15, 12}, {15, 8}, {16, 12}, {16, 8}},
        },
        {
            {{2, 3}, {6, 11}, {6, 7}, {7, 7}, {8, 7}, {8, 4}, {9, 7}, {11, 15}, {11, 11}, {12, 15}, {12, 11}, {12, 8}, {13, 15}, {13, 11}, {13, 7}, {14, 9}, {14, 7}},
            {{0, 0}, {2, 2}, {5, 7}, {6, 10}, {6, 6}, {7, 6}, {8, 6}, {9, 6}, {11, 14}, {11, 10}, {12, 14}, {12, 10}, {13, 14}, {13, 10}, {14, 11}, {14, 8}, {14, 6}},
            {{0, 0}, {0, 0}, {3, 3}, {6, 9}, {6, 5}, {7, 5}, {8, 5}, {9, 5}, {11, 13}, {11, 9}, {12, 13}, {12, 9}, {13, 13}, {13, 9}, {13, 6}, {14, 10}, {14, 5}},
            {{0, 0}, {0, 0}, {0, 0}, {4, 5}, {4, 4}, {5, 6}, {6, 8}, {6, 4}, {7, 4}, {9, 4}, {11, 12}, {11, 8}, {12, 12}, {13, 12}, {13, 8}, {13, 1}, {14, 4}},
        },
        {
            {{4, 15}, {6, 15}, {6, 11}, {6, 8}, {7, 15}, {7, 11}, {7, 9}, {7, 8}, {8, 15}, {8, 11}, {9, 15}, {9, 11}, {9, 8}, {10, 13}, {10, 9}, {10, 5}, {10, 1}},
            {{0, 0}, {4, 14}, {5, 15}, {5, 12}, {5, 10}, {5, 8}, {6, 14}, {6, 10}, {7, 14}, {8, 14}, {8, 10}, {9, 14}, {9, 10}, {9, 7}, {10, 12}, {10, 8}, {10, 4}},
            {{0, 0}, {0, 0}, {4, 13}, {5, 14}, {5, 11}, {5, 9}, {6, 13}, {6, 9}, {7, 13}, {7, 10}, {8, 13}, {8, 9}, {9, 13}, {9, 9}, {10, 11}, {10, 7}, {10, 3}},
            {{0, 0}, {0, 0}, {0, 0}, {4, 12}, {4, 11}, {4, 10}, {4, 9}, {4, 8}, {5, 13}, {6, 12}, {7, 12}, {8, 12}, {8, 8}, {9, 12}, {10, 10}, {10, 6}, {10, 2}}
        }
    };


    AVCEnc_Status status = AVCENC_SUCCESS;
    uint code, len;
    int vlcnum;

    if (TrailingOnes > 3)
    {
        return AVCENC_TRAILINGONES_FAIL;
    }

    if (nC >= 8)
    {
        if (TotalCoeff)
        {
            code = ((TotalCoeff - 1) << 2) | (TrailingOnes);
        }
        else
        {
            code = 3;
        }
        status = BitstreamWriteBits(stream, 6, code);
    }
    else
    {
        if (nC < 2)
        {
            vlcnum = 0;
        }
        else if (nC < 4)
        {
            vlcnum = 1;
        }
        else
        {
            vlcnum = 2;
        }

        len = totCoeffTrailOne[vlcnum][TrailingOnes][TotalCoeff][0];
        code = totCoeffTrailOne[vlcnum][TrailingOnes][TotalCoeff][1];
        status = BitstreamWriteBits(stream, len, code);
    }

    return status;
}

AVCEnc_Status ce_TotalCoeffTrailingOnesChromaDC(AVCEncBitstream *stream, int TrailingOnes, int TotalCoeff)
{
    const static uint8 totCoeffTrailOneChrom[4][5][2] =
    {
        { {2, 1}, {6, 7}, {6, 4}, {6, 3}, {6, 2}},
        { {0, 0}, {1, 1}, {6, 6}, {7, 3}, {8, 3}},
        { {0, 0}, {0, 0}, {3, 1}, {7, 2}, {8, 2}},
        { {0, 0}, {0, 0}, {0, 0}, {6, 5}, {7, 0}},
    };

    AVCEnc_Status status = AVCENC_SUCCESS;
    uint code, len;

    len = totCoeffTrailOneChrom[TrailingOnes][TotalCoeff][0];
    code = totCoeffTrailOneChrom[TrailingOnes][TotalCoeff][1];
    status = BitstreamWriteBits(stream, len, code);

    return status;
}

/* see Table 9-7 and 9-8 */
AVCEnc_Status ce_TotalZeros(AVCEncBitstream *stream, int total_zeros, int TotalCoeff)
{
    const static uint8 lenTotalZeros[15][16] =
    {
        { 1, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 9},
        { 3, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 6, 6, 6, 6},
        { 4, 3, 3, 3, 4, 4, 3, 3, 4, 5, 5, 6, 5, 6},
        { 5, 3, 4, 4, 3, 3, 3, 4, 3, 4, 5, 5, 5},
        { 4, 4, 4, 3, 3, 3, 3, 3, 4, 5, 4, 5},
        { 6, 5, 3, 3, 3, 3, 3, 3, 4, 3, 6},
        { 6, 5, 3, 3, 3, 2, 3, 4, 3, 6},
        { 6, 4, 5, 3, 2, 2, 3, 3, 6},
        { 6, 6, 4, 2, 2, 3, 2, 5},
        { 5, 5, 3, 2, 2, 2, 4},
        { 4, 4, 3, 3, 1, 3},
        { 4, 4, 2, 1, 3},
        { 3, 3, 1, 2},
        { 2, 2, 1},
        { 1, 1},
    };

    const static uint8 codTotalZeros[15][16] =
    {
        {1, 3, 2, 3, 2, 3, 2, 3, 2, 3, 2, 3, 2, 3, 2, 1},
        {7, 6, 5, 4, 3, 5, 4, 3, 2, 3, 2, 3, 2, 1, 0},
        {5, 7, 6, 5, 4, 3, 4, 3, 2, 3, 2, 1, 1, 0},
        {3, 7, 5, 4, 6, 5, 4, 3, 3, 2, 2, 1, 0},
        {5, 4, 3, 7, 6, 5, 4, 3, 2, 1, 1, 0},
        {1, 1, 7, 6, 5, 4, 3, 2, 1, 1, 0},
        {1, 1, 5, 4, 3, 3, 2, 1, 1, 0},
        {1, 1, 1, 3, 3, 2, 2, 1, 0},
        {1, 0, 1, 3, 2, 1, 1, 1, },
        {1, 0, 1, 3, 2, 1, 1, },
        {0, 1, 1, 2, 1, 3},
        {0, 1, 1, 1, 1},
        {0, 1, 1, 1},
        {0, 1, 1},
        {0, 1},
    };
    int len, code;
    AVCEnc_Status status;

    len = lenTotalZeros[TotalCoeff-1][total_zeros];
    code = codTotalZeros[TotalCoeff-1][total_zeros];

    status = BitstreamWriteBits(stream, len, code);

    return status;
}

/* see Table 9-9 */
AVCEnc_Status ce_TotalZerosChromaDC(AVCEncBitstream *stream, int total_zeros, int TotalCoeff)
{
    const static uint8 lenTotalZerosChromaDC[3][4] =
    {
        { 1, 2, 3, 3, },
        { 1, 2, 2, 0, },
        { 1, 1, 0, 0, },
    };

    const static uint8 codTotalZerosChromaDC[3][4] =
    {
        { 1, 1, 1, 0, },
        { 1, 1, 0, 0, },
        { 1, 0, 0, 0, },
    };

    int len, code;
    AVCEnc_Status status;

    len = lenTotalZerosChromaDC[TotalCoeff-1][total_zeros];
    code = codTotalZerosChromaDC[TotalCoeff-1][total_zeros];

    status = BitstreamWriteBits(stream, len, code);

    return status;
}

/* see Table 9-10 */
AVCEnc_Status ce_RunBefore(AVCEncBitstream *stream, int run_before, int zerosLeft)
{
    const static uint8 lenRunBefore[7][16] =
    {
        {1, 1},
        {1, 2, 2},
        {2, 2, 2, 2},
        {2, 2, 2, 3, 3},
        {2, 2, 3, 3, 3, 3},
        {2, 3, 3, 3, 3, 3, 3},
        {3, 3, 3, 3, 3, 3, 3, 4, 5, 6, 7, 8, 9, 10, 11},
    };

    const static uint8 codRunBefore[7][16] =
    {
        {1, 0},
        {1, 1, 0},
        {3, 2, 1, 0},
        {3, 2, 1, 1, 0},
        {3, 2, 3, 2, 1, 0},
        {3, 0, 1, 3, 2, 5, 4},
        {7, 6, 5, 4, 3, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1},
    };

    int len, code;
    AVCEnc_Status status;

    if (zerosLeft <= 6)
    {
        len = lenRunBefore[zerosLeft-1][run_before];
        code = codRunBefore[zerosLeft-1][run_before];
    }
    else
    {
        len = lenRunBefore[6][run_before];
        code = codRunBefore[6][run_before];
    }

    status = BitstreamWriteBits(stream, len, code);


    return status;
}
