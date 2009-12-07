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
------------------------------------------------------------------------------

   PacketVideo Corp.
   MP3 Decoder Library

   Filename: pvmp3_decode_huff_cw.cpp

 Funtions:
    pvmp3_decode_huff_cw_tab0
    pvmp3_decode_huff_cw_tab1
    pvmp3_decode_huff_cw_tab2
    pvmp3_decode_huff_cw_tab3
    pvmp3_decode_huff_cw_tab5
    pvmp3_decode_huff_cw_tab6
    pvmp3_decode_huff_cw_tab7
    pvmp3_decode_huff_cw_tab8
    pvmp3_decode_huff_cw_tab9
    pvmp3_decode_huff_cw_tab10
    pvmp3_decode_huff_cw_tab11
    pvmp3_decode_huff_cw_tab12
    pvmp3_decode_huff_cw_tab13
    pvmp3_decode_huff_cw_tab15
    pvmp3_decode_huff_cw_tab16
    pvmp3_decode_huff_cw_tab24
    pvmp3_decode_huff_cw_tab32
    pvmp3_decode_huff_cw_tab33

     Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY


 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    BITS          *pMainData = pointer to input mp3 Main data bit stream


 Outputs:
    cw = bit field extracted from a leaf entry of packed mp3 Huffman Tables


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

   These functions are used to decode huffman codewords from the input
   bitstream using combined binary search and look-up table approach.

------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES
 [1] ISO MPEG Audio Subgroup Software Simulation Group (1996)
     ISO 13818-3 MPEG-2 Audio Decoder - Lower Sampling Frequency Extension

 [2] Introduction to Algorithms,
     Thomas H. Cormen, Charles E. Leiserson, Ronald L. Rivest.
     The MIT press, 1990

 [3] "Selecting an Optimal Huffman Decoder for AAC",
     Vladimir Z. Mesarovic, et al.
     AES 111th Convention, September 21-24, 2001, New York, USA

------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "pv_mp3dec_fxd_op.h"
#include "pvmp3_tables.h"
#include "pvmp3_getbits.h"
#include "pvmp3_decode_huff_cw.h"

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

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

uint16 pvmp3_decode_huff_cw_tab0(tmp3Bits *pMainData)
{
    OSCL_UNUSED_ARG(pMainData);
    return(0);

}

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/
uint16 pvmp3_decode_huff_cw_tab1(tmp3Bits *pMainData)
{
    uint32 tmp;
    uint16 cw;

    tmp = getUpTo9bits(pMainData, 3);    /*  hufftable1  */

    cw = *(huffTable_1 + tmp);
    pMainData->usedBits -= (3 - (cw & 0xFF));
    return(cw >> 8);

}


/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/
uint16 pvmp3_decode_huff_cw_tab2(tmp3Bits *pMainData)
{
    uint32 tmp;
    uint16 cw;

    tmp = getUpTo9bits(pMainData, 6);    /*  huffTable_2,3  */

    if (tmp >> 3)
    {
        tmp = (tmp >> 3) - 1;
    }
    else
    {
        tmp = tmp + 7;
    }

    cw = *(huffTable_2 + tmp);
    pMainData->usedBits -= (6 - (cw & 0xFF));

    return(cw >> 8);
}


/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/
uint16 pvmp3_decode_huff_cw_tab3(tmp3Bits *pMainData)
{
    uint32 tmp;
    uint16 cw;

    tmp = getUpTo9bits(pMainData, 6);    /*  huffTable_2,3  */

    if (tmp >> 3)
    {
        tmp = (tmp >> 3) - 1;
    }
    else
    {
        tmp = tmp + 7;
    }

    cw = *(huffTable_3 + tmp);
    pMainData->usedBits -= (6 - (cw & 0xFF));

    return(cw >> 8);
}


/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/
uint16 pvmp3_decode_huff_cw_tab5(tmp3Bits *pMainData)
{
    uint32 tmp;
    uint16 cw;

    tmp = getUpTo9bits(pMainData, 8);    /*  huffTable_5  */

    if ((tmp >> 5))
    {
        tmp = (tmp >> 5) - 1;
    }
    else if ((tmp >> 1) >= 2)
    {
        tmp = (tmp >> 1) - 2 + 7;
    }
    else
    {
        tmp = (tmp & 3) + 21;
    }

    cw = *(huffTable_5 + tmp);
    pMainData->usedBits -= (8 - (cw & 0xFF));

    return(cw >> 8);
}

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/
uint16 pvmp3_decode_huff_cw_tab6(tmp3Bits *pMainData)
{
    uint32 tmp;
    uint16 cw;

    tmp = getUpTo9bits(pMainData, 7);    /*  huffTable_6  */
    if ((tmp >> 3) >= 3)
    {
        tmp = (tmp >> 3) - 3;
    }
    else if (tmp >> 1)
    {
        tmp = (tmp >> 1) - 1 + 13;
    }
    else
    {
        tmp = tmp + 24;
    }

    cw = *(huffTable_6 + tmp);
    pMainData->usedBits -= (7 - (cw & 0xFF));

    return(cw >> 8);
}


/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/
uint16 pvmp3_decode_huff_cw_tab7(tmp3Bits *pMainData)
{
    uint32 tmp;
    uint16 cw;

    tmp = getUpTo17bits(pMainData, 10);    /*  huffTable_7  */
    if ((tmp >> 7) >= 2)
    {
        tmp = (tmp >> 7) - 2;
    }
    else if ((tmp >> 4) >= 7)
    {
        tmp = (tmp >> 4) - 7 + 6;
    }
    else if ((tmp >> 1) >=  2)
    {
        tmp = (tmp >> 1) - 2 + 15;
    }
    else
    {
        tmp = (tmp & 3) + 69;
    }

    cw = *(huffTable_7 + tmp);
    pMainData->usedBits -= (10 - (cw & 0xFF));

    return(cw >> 8);
}


/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/
uint16 pvmp3_decode_huff_cw_tab8(tmp3Bits *pMainData)
{
    uint32 tmp;
    uint16 cw;

    tmp = getUpTo17bits(pMainData, 11);    /*  huffTable_8  */
    if ((tmp >> 7) >= 2)
    {
        tmp = (tmp >> 7) - 2;
    }
    else if ((tmp >> 5) >= 5)
    {
        tmp = (tmp >> 5) - 5 + 14;
    }
    else if ((tmp >> 2) >= 3)
    {
        tmp = (tmp >> 2) - 3 + 17;
    }
    else
    {
        tmp = (tmp) + 54;
    }

    cw = *(huffTable_8 + tmp);
    pMainData->usedBits -= (11 - (cw & 0xFF));

    return(cw >> 8);
}


/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/
uint16 pvmp3_decode_huff_cw_tab9(tmp3Bits *pMainData)
{
    uint32 tmp;
    uint16 cw;

    tmp = getUpTo9bits(pMainData, 9);    /*  huffTable_9  */
    if ((tmp >> 5) >= 5)
    {
        tmp = (tmp >> 5) - 5;
    }
    else if ((tmp >> 3) >= 6)
    {
        tmp = (tmp >> 3) - 6 + 11;
    }
    else if ((tmp >> 1) >= 4)
    {
        tmp = (tmp >> 1) - 4 + 25;
    }
    else
    {
        tmp = tmp + 45;
    }

    cw = *(huffTable_9 + tmp);
    pMainData->usedBits -= (9 - (cw & 0xFF));

    return(cw >> 8);
}


/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/
uint16 pvmp3_decode_huff_cw_tab10(tmp3Bits *pMainData)
{
    uint32 tmp;
    uint16 cw;

    tmp = getUpTo17bits(pMainData, 11);    /*  huffTable_10  */
    if (tmp >> 10)
    {
        tmp = (tmp >> 10) - 1;
    }
    else if ((tmp >> 7) >= 3)
    {
        tmp = (tmp >> 7) - 3 + 1;
    }
    else if ((tmp >> 5) >= 8)
    {
        tmp = (tmp >> 5) - 8 + 6;
    }
    else if ((tmp >> 3) >= 18)
    {
        tmp = (tmp >> 3) - 18 + 10;
    }
    else if ((tmp >> 2) >= 24)
    {
        tmp = (tmp >> 2) - 24 + 24;
    }
    else if ((tmp >> 1) >= 12)
    {
        tmp = (tmp >> 1) - 12 + 36;
    }
    else
    {
        tmp = (tmp) + 72;
    }

    cw = *(huffTable_10 + tmp);
    pMainData->usedBits -= (11 - (cw & 0xFF));

    return(cw >> 8);
}


/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/
uint16 pvmp3_decode_huff_cw_tab11(tmp3Bits *pMainData)
{
    uint32 tmp;
    uint16 cw;

    tmp = getUpTo17bits(pMainData, 11);    /*  huffTable_11  */
    if ((tmp >> 8) >= 3)
    {
        tmp = (tmp >> 8) - 3;
    }
    else if ((tmp >> 6) >= 7)
    {
        tmp = (tmp >> 6) - 7 + 5;
    }
    else if ((tmp >> 3) >= 32)
    {
        tmp = (tmp >> 3) - 32 + 10;
    }
    else if ((tmp >> 2) >= 10)
    {
        tmp = (tmp >> 2) - 10 + 34;
    }
    else if ((tmp >> 1) >= 8)
    {
        tmp = (tmp >> 1) - 8 + 88;
    }
    else
    {
        tmp = (tmp & 0xFF) + 100;
    }
    cw = *(huffTable_11 + tmp);
    pMainData->usedBits -= (11 - (cw & 0xFF));

    return(cw >> 8);
}


/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/
uint16 pvmp3_decode_huff_cw_tab12(tmp3Bits *pMainData)
{
    uint32 tmp;
    uint16 cw;

    tmp = getUpTo17bits(pMainData, 10);    /*  huffTable_12  */
    if ((tmp >> 7) >= 5)
    {
        tmp = (tmp >> 7) - 5;
    }
    else if ((tmp >> 5) >= 12)
    {
        tmp = (tmp >> 5) - 12 + 3;
    }
    else if ((tmp >> 4) >= 17)
    {
        tmp = (tmp >> 4) - 17 + 11;
    }
    else if ((tmp >> 2) >= 32)
    {
        tmp = (tmp >> 2) - 32 + 18;
    }
    else if ((tmp >> 1) >= 16)
    {
        tmp = (tmp >> 1) - 16 + 54;
    }
    else
    {
        tmp = (tmp & 0x1F) + 102;

    }
    cw = *(huffTable_12 + tmp);
    pMainData->usedBits -= (10 - (cw & 0xFF));

    return(cw >> 8);
}


/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/
uint16 pvmp3_decode_huff_cw_tab13(tmp3Bits *pMainData)
{
    uint32 tmp;
    uint16 cw;

    tmp = getNbits(pMainData, 19);    /*  huffTable_13  */
    if (tmp >> 18)
    {
        tmp = 0;
    }
    else if ((tmp >> 15) >= 4)
    {
        tmp = (tmp >> 15) - 4 + 1;
    }
    else if ((tmp >> 11) >= 32)
    {
        tmp = (tmp >> 11) - 32 + 5;
    }
    else if ((tmp >> 9) >= 64)
    {
        tmp = (tmp >> 9) - 64 + 37;
    }
    else if ((tmp >> 8) >= 64)
    {
        tmp = (tmp >> 8) - 64 + 101;
    }
    else if ((tmp >> 7) >= 64)
    {
        tmp = (tmp >> 7) - 64 + 165;
    }
    else if ((tmp >> 6) >= 32)
    {
        tmp = (tmp >> 6) - 32 + 229;
    }
    else if ((tmp >> 5) >= 32)
    {
        tmp = (tmp >> 5) - 32 + 325;
    }
    else if ((tmp >> 4) >= 32)
    {
        tmp = (tmp >> 4) - 32 + 357;
    }
    else if ((tmp >> 3) >= 32)
    {
        tmp = (tmp >> 3) - 32 + 389;
    }
    else if ((tmp >> 2) >= 2)
    {
        tmp = (tmp >> 2) - 2 + 421;
    }
    else
    {
        tmp = (tmp & 0x7) + 483;
    }

    cw = *(huffTable_13 + tmp);
    pMainData->usedBits -= (19 - (cw & 0xFF));

    return(cw >> 8);
}


/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/
uint16 pvmp3_decode_huff_cw_tab15(tmp3Bits *pMainData)
{
    uint32 tmp;
    uint16 cw;

    tmp = getUpTo17bits(pMainData, 13);    /*  huffTable_15  */
    if ((tmp >> 9) >= 10)
    {
        tmp = (tmp >> 9) - 10;
    }
    else if ((tmp >> 6) >= 39)
    {
        tmp = (tmp >> 6) - 39 + 6;
    }
    else if ((tmp >> 4) >= 62)
    {
        tmp = (tmp >> 4) - 62 + 47;
    }
    else if ((tmp >> 3) >= 60)
    {
        tmp = (tmp >> 3) - 60 + 141;
    }
    else if ((tmp >> 2) >= 64)
    {
        tmp = (tmp >> 2) - 64 + 205;
    }
    else if ((tmp >> 1) >= 32)
    {
        tmp = (tmp >> 1) - 32 + 261;
    }
    else
    {
        tmp = (tmp & 0x3f) + 357;
    }

    cw = *(huffTable_15 + tmp);
    pMainData->usedBits -= (13 - (cw & 0xFF));

    return(cw >> 8);
}


/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/
uint16 pvmp3_decode_huff_cw_tab16(tmp3Bits *pMainData)
{
    uint32 tmp;
    uint16 cw;

    tmp = getUpTo17bits(pMainData, 17);    /*  huffTable_16  */
    if (tmp >> 16)
    {
        tmp = 0;
    }
    else if ((tmp >> 13) >= 4)
    {
        tmp = (tmp >> 13) - 4 + 1;
    }
    else if ((tmp >> 9) >= 38)
    {
        tmp = (tmp >> 9) - 38 + 5;
    }
    else if ((tmp >> 7) >= 94)
    {
        tmp = (tmp >> 7) - 94 + 31;
    }
    else if ((tmp >> 5) >= 214)
    {
        tmp = (tmp >> 5) - 214 + 89;
    }
    else if ((tmp >> 3) >= 704)
    {
        if ((tmp >> 4) >= 384)
        {
            tmp = (tmp >> 4) - 384 + 315;
        }
        else
        {
            tmp = (tmp >> 3) - 704 + 251;
        }
    }
    else if ((tmp >> 8) >= 14)
    {
        tmp = (tmp >> 8) - 14 + 359;
    }
    else if ((tmp) >= 3456)
    {
        if ((tmp >> 2) >= 868)
        {
            tmp = (tmp >> 2) - 868 + 383;
        }
        else
        {
            tmp = (tmp) - 3456 + 367;
        }
    }
    else
    {
        tmp = ((tmp >> 6) & 0x3f) + 411;
    }

    cw = *(huffTable_16 + tmp);
    pMainData->usedBits -= (17 - (cw & 0xFF));

    return(cw >> 8);
}



/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/
uint16 pvmp3_decode_huff_cw_tab24(tmp3Bits *pMainData)
{
    uint32 tmp;
    uint16 cw;

    tmp = getUpTo17bits(pMainData, 12);    /*  huffTable_24  */
    if ((tmp >> 6) >= 41)
    {
        tmp = (tmp >> 6) - 41;
    }
    else if ((tmp >> 3) >= 218)
    {
        tmp = (tmp >> 3) - 218 + 23;
    }
    else if ((tmp >> 2) >= 336)
    {
        tmp = (tmp >> 2) - 336 + 133;
    }
    else if ((tmp >> 1) >= 520)
    {
        tmp = (tmp >> 1) - 520 + 233;
    }
    else if ((tmp) >= 1024)
    {
        tmp = (tmp) - 1024 + 385;
    }
    else if ((tmp >> 1) >= 352)
    {
        if ((tmp >> 8) == 3)
        {
            tmp = (tmp >> 8) - 3 + 433;
        }
        else
        {
            tmp = (tmp >> 1) - 352 + 401;
        }
    }
    else
    {
        tmp = ((tmp >> 4) & 0x3f) + 434;
    }

    cw = *(huffTable_24 + tmp);
    pMainData->usedBits -= (12 - (cw & 0xFF));

    return(cw >> 8);
}


/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/
uint16 pvmp3_decode_huff_cw_tab32(tmp3Bits *pMainData)
{
    uint32 tmp = getUpTo9bits(pMainData, 6);    /*  huffTable_32  */
    if ((tmp >> 5))
    {
        pMainData->usedBits -= 5;
        return(0);
    }
    else
    {
        uint16 cw = *(huffTable_32 + (tmp & 0x1f));
        pMainData->usedBits -= (6 - (cw & 0xFF));

        return(cw >> 8);
    }

}


uint16 pvmp3_decode_huff_cw_tab33(tmp3Bits *pMainData)
{

    uint16 tmp = getUpTo9bits(pMainData, 4);    /*  huffTable_33  */

    return((0x0f - tmp));
}

