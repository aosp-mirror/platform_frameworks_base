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
/*

 Filename: ps_read_data.c


------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS



------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

        Decodes parametric stereo

------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

SC 29 Software Copyright Licencing Disclaimer:

This software module was originally developed by
  Coding Technologies

and edited by
  -

in the course of development of the ISO/IEC 13818-7 and ISO/IEC 14496-3
standards for reference purposes and its performance may not have been
optimized. This software module is an implementation of one or more tools as
specified by the ISO/IEC 13818-7 and ISO/IEC 14496-3 standards.
ISO/IEC gives users free license to this software module or modifications
thereof for use in products claiming conformance to audiovisual and
image-coding related ITU Recommendations and/or ISO/IEC International
Standards. ISO/IEC gives users the same free license to this software module or
modifications thereof for research purposes and further ISO/IEC standardisation.
Those intending to use this software module in products are advised that its
use may infringe existing patents. ISO/IEC have no liability for use of this
software module or modifications thereof. Copyright is not released for
products that do not conform to audiovisual and image-coding related ITU
Recommendations and/or ISO/IEC International Standards.
The original developer retains full right to modify and use the code for its
own purpose, assign or donate the code to a third party and to inhibit third
parties from using the code for products that do not conform to audiovisual and
image-coding related ITU Recommendations and/or ISO/IEC International Standards.
This copyright notice must be included in all copies or derivative works.
Copyright (c) ISO/IEC 2003.

------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#ifdef AAC_PLUS

#ifdef PARAMETRICSTEREO

#include    "pv_audio_type_defs.h"
#include    "buf_getbits.h"
#include    "s_bit_buffer.h"
#include    "s_huffman.h"
#include    "aac_mem_funcs.h"
#include    "s_ps_dec.h"
#include    "sbr_decode_huff_cw.h"
#include    "ps_decode_bs_utils.h"
#include    "ps_bstr_decoding.h"
#include    "ps_read_data.h"
/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; EXTERNAL FUNCTION REFERENCES
; Declare functions defined elsewhere and referenced in this module
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; EXTERNAL GLOBAL STORE/BUFFER/POINTER REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/

/* IID & ICC Huffman codebooks */
const Char aBookPsIidTimeDecode[28][2] =
{
    { -64,   1 },    { -65,   2 },    { -63,   3 },    { -66,   4 },
    { -62,   5 },    { -67,   6 },    { -61,   7 },    { -68,   8 },
    { -60,   9 },    { -69,  10 },    { -59,  11 },    { -70,  12 },
    { -58,  13 },    { -57,  14 },    { -71,  15 },    {  16,  17 },
    { -56, -72 },    {  18,  21 },    {  19,  20 },    { -55, -78 },
    { -77, -76 },    {  22,  25 },    {  23,  24 },    { -75, -74 },
    { -73, -54 },    {  26,  27 },    { -53, -52 },    { -51, -50 }
};

const Char aBookPsIidFreqDecode[28][2] =
{
    { -64,   1 },    {   2,   3 },    { -63, -65 },    {   4,   5 },
    { -62, -66 },    {   6,   7 },    { -61, -67 },    {   8,   9 },
    { -68, -60 },    { -59,  10 },    { -69,  11 },    { -58,  12 },
    { -70,  13 },    { -71,  14 },    { -57,  15 },    {  16,  17 },
    { -56, -72 },    {  18,  19 },    { -55, -54 },    {  20,  21 },
    { -73, -53 },    {  22,  24 },    { -74,  23 },    { -75, -78 },
    {  25,  26 },    { -77, -76 },    { -52,  27 },    { -51, -50 }
};

const Char aBookPsIccTimeDecode[14][2] =
{
    { -64,   1 },    { -63,   2 },    { -65,   3 },    { -62,   4 },
    { -66,   5 },    { -61,   6 },    { -67,   7 },    { -60,   8 },
    { -68,   9 },    { -59,  10 },    { -69,  11 },    { -58,  12 },
    { -70,  13 },    { -71, -57 }
};

const Char aBookPsIccFreqDecode[14][2] =
{
    { -64,   1 },    { -63,   2 },    { -65,   3 },    { -62,   4 },
    { -66,   5 },    { -61,   6 },    { -67,   7 },    { -60,   8 },
    { -59,   9 },    { -68,  10 },    { -58,  11 },    { -69,  12 },
    { -57,  13 },    { -70, -71 }
};

const Char aBookPsIidFineTimeDecode[60][2] =
{
    {   1, -64 },    { -63,   2 },    {   3, -65 },    {   4,  59 },
    {   5,   7 },    {   6, -67 },    { -68, -60 },    { -61,   8 },
    {   9,  11 },    { -59,  10 },    { -70, -58 },    {  12,  41 },
    {  13,  20 },    {  14, -71 },    { -55,  15 },    { -53,  16 },
    {  17, -77 },    {  18,  19 },    { -85, -84 },    { -46, -45 },
    { -57,  21 },    {  22,  40 },    {  23,  29 },    { -51,  24 },
    {  25,  26 },    { -83, -82 },    {  27,  28 },    { -90, -38 },
    { -92, -91 },    {  30,  37 },    {  31,  34 },    {  32,  33 },
    { -35, -34 },    { -37, -36 },    {  35,  36 },    { -94, -93 },
    { -89, -39 },    {  38, -79 },    {  39, -81 },    { -88, -40 },
    { -74, -54 },    {  42, -69 },    {  43,  44 },    { -72, -56 },
    {  45,  52 },    {  46,  50 },    {  47, -76 },    { -49,  48 },
    { -47,  49 },    { -87, -41 },    { -52,  51 },    { -78, -50 },
    {  53, -73 },    {  54, -75 },    {  55,  57 },    {  56, -80 },
    { -86, -42 },    { -48,  58 },    { -44, -43 },    { -66, -62 }
};

const Char aBookPsIidFineFreqDecode[60][2] =
{
    {   1, -64 },    {   2,   4 },    {   3, -65 },    { -66, -62 },
    { -63,   5 },    {   6,   7 },    { -67, -61 },    {   8,   9 },
    { -68, -60 },    {  10,  11 },    { -69, -59 },    {  12,  13 },
    { -70, -58 },    {  14,  18 },    { -57,  15 },    {  16, -72 },
    { -54,  17 },    { -75, -53 },    {  19,  37 },    { -56,  20 },
    {  21, -73 },    {  22,  29 },    {  23, -76 },    {  24, -78 },
    {  25,  28 },    {  26,  27 },    { -85, -43 },    { -83, -45 },
    { -81, -47 },    { -52,  30 },    { -50,  31 },    {  32, -79 },
    {  33,  34 },    { -82, -46 },    {  35,  36 },    { -90, -89 },
    { -92, -91 },    {  38, -71 },    { -55,  39 },    {  40, -74 },
    {  41,  50 },    {  42, -77 },    { -49,  43 },    {  44,  47 },
    {  45,  46 },    { -86, -42 },    { -88, -87 },    {  48,  49 },
    { -39, -38 },    { -41, -40 },    { -51,  51 },    {  52,  59 },
    {  53,  56 },    {  54,  55 },    { -35, -34 },    { -37, -36 },
    {  57,  58 },    { -94, -93 },    { -84, -44 },    { -80, -48 }
};






/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

Int32 ps_read_data(STRUCT_PS_DEC *ps_dec,
                   BIT_BUFFER * hBitBuf,
                   Int32 nBitsLeft)

{
    Int     gr;
    UInt32     env;
    UInt32     dtFlag;
    Int32     startbits;
    SbrHuffman CurrentTable;

    if (!ps_dec)
    {
        return 0;
    }

    startbits = GetNrBitsAvailable(hBitBuf);

    if (buf_get_1bit(hBitBuf))  /*  Enable Header */
    {
        ps_dec->bEnableIid = buf_get_1bit(hBitBuf);

        if (ps_dec->bEnableIid)
        {
            ps_dec->freqResIid = buf_getbits(hBitBuf, 3);

            if (ps_dec->freqResIid > 2)
            {
                ps_dec->bFineIidQ = 1;
                ps_dec->freqResIid -= 3;
            }
            else
            {
                ps_dec->bFineIidQ = 0;
            }
        }

        ps_dec->bEnableIcc = buf_get_1bit(hBitBuf);
        if (ps_dec->bEnableIcc)
        {
            ps_dec->freqResIcc = buf_getbits(hBitBuf, 3);

            if (ps_dec->freqResIcc > 2)
            {
                ps_dec->freqResIcc -= 3;
            }
        }
        ps_dec->bEnableExt = buf_get_1bit(hBitBuf);
    }

    ps_dec->bFrameClass = buf_get_1bit(hBitBuf);
    if (ps_dec->bFrameClass == 0)
    {
        ps_dec->noEnv = aFixNoEnvDecode[ buf_getbits(hBitBuf, 2)];
    }
    else
    {
        ps_dec->noEnv = 1 + buf_getbits(hBitBuf, 2);
        for (env = 1; env < ps_dec->noEnv + 1; env++)
        {
            ps_dec->aEnvStartStop[env] = (buf_getbits(hBitBuf, 5)) + 1;
        }
    }

    if ((ps_dec->freqResIid > 2) || (ps_dec->freqResIcc > 2))
    {

        ps_dec->bPsDataAvail = 0;

        nBitsLeft -= startbits - GetNrBitsAvailable(hBitBuf);
        while (nBitsLeft)
        {
            int i = nBitsLeft;
            if (i > 8)
            {
                i = 8;
            }
            buf_getbits(hBitBuf, i);
            nBitsLeft -= i;
        }
        return (startbits - GetNrBitsAvailable(hBitBuf));
    }

    if (ps_dec->bEnableIid)
    {
        for (env = 0; env < ps_dec->noEnv; env++)
        {
            dtFlag = buf_get_1bit(hBitBuf);

            if (!dtFlag)
            {
                if (ps_dec->bFineIidQ)
                {
                    CurrentTable = aBookPsIidFineFreqDecode;
                }
                else
                {
                    CurrentTable = aBookPsIidFreqDecode;
                }
            }
            else
            {
                if (ps_dec->bFineIidQ)
                {
                    CurrentTable = aBookPsIidFineTimeDecode;
                }
                else
                {
                    CurrentTable = aBookPsIidTimeDecode;
                }
            }

            for (gr = 0; gr < aNoIidBins[ps_dec->freqResIid]; gr++)
            {
                ps_dec->aaIidIndex[env][gr] = sbr_decode_huff_cw(CurrentTable, hBitBuf);
            }

            ps_dec->abIidDtFlag[env] = dtFlag;
        }
    }

    if (ps_dec->bEnableIcc)
    {
        for (env = 0; env < ps_dec->noEnv; env++)
        {
            dtFlag = buf_get_1bit(hBitBuf);
            if (!dtFlag)
            {
                CurrentTable = aBookPsIccFreqDecode;
            }
            else
            {
                CurrentTable = aBookPsIccTimeDecode;
            }
            for (gr = 0; gr < aNoIccBins[ps_dec->freqResIcc]; gr++)
            {
                ps_dec->aaIccIndex[env][gr] = sbr_decode_huff_cw(CurrentTable, hBitBuf);
            }

            ps_dec->abIccDtFlag[env] = dtFlag;
        }
    }

    if (ps_dec->bEnableExt)
    {

        int cnt;

        cnt = (int)buf_getbits(hBitBuf, 4);

        if (cnt == 15)
        {
            cnt += (int)buf_getbits(hBitBuf, 8);
        }

        hBitBuf->nrBitsRead += (cnt << 3);
    }

    ps_dec->bPsDataAvail = 1;

    return (startbits - GetNrBitsAvailable(hBitBuf));
}

#endif


#endif

