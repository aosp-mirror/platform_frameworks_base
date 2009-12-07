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

 Pathname: ./src/decode_huff_cw_binary.c
 Funtions:
    decode_huff_cw_tab1
    decode_huff_cw_tab2
    decode_huff_cw_tab3
    decode_huff_cw_tab4
    decode_huff_cw_tab5
    decode_huff_cw_tab6
    decode_huff_cw_tab7
    decode_huff_cw_tab8
    decode_huff_cw_tab9
    decode_huff_cw_tab10
    decode_huff_cw_tab11
    decode_huff_cw_scl


------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Updated per review comments
              (1) make cw sgined and change "if(cw&0x80000000)" to if(cw<0)
              (2)

 Description: Create specific functions for different huffman tables.


 Description: Added ( Int16) castings to eliminate several compiler warnings


 Description: Modified huffman tables to allocate int32 variables instead of
              int16, which lead to data missaligned for some compiler.
              Eliminated casting and unused variables


 Who:                                   Date:
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    BITS          *pInputStream = pointer to input bit stream

 Local Stores/Buffers/Pointers Needed:


 Global Stores/Buffers/Pointers Needed:


 Outputs:
    idx = bit field extracted from a leaf entry of packed Huffman Tables

 Pointers and Buffers Modified:

 Local Stores Modified:

 Global Stores Modified:


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

   These functions are used to decode huffman codewords from the input
   bitstream using combined binary search and look-up table approach.

   First the codewords are grouped and the input symbol is determined
   which group it belongs. Then within that group, a look-up table is
   used to determine which codeword the symbol is.
   The table is created by ordering the codeword in the table according to their
   normalized shifted binary value, i.e., all the codewords are left
   shifted to meet the maximum codelength. Example, max codelength is
   10, the codeword with lenth 3 will left shift by 7.
   The binary values of after the shift are sorted.
   Then the sorted table is divided into several partition.
   At the VLC decoding period, input is read in at max codelenght.
   The partition is decided using if-else logic.
   Inside each partition, a look-up table is used to map the input value
   to a correct symbol. Table entries can appear to be repeated according
   to the humming distance between adjacent codewords.

------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

 (1) MPEG-2 NBC Audio Decoder
   "This software module was originally developed by AT&T, Dolby
   Laboratories, Fraunhofer Gesellschaft IIS in the course of development
   of the MPEG-2 NBC/MPEG-4 Audio standard ISO/IEC 13818-7, 14496-1,2 and
   3. This software module is an implementation of a part of one or more
   MPEG-2 NBC/MPEG-4 Audio tools as specified by the MPEG-2 NBC/MPEG-4
   Audio standard. ISO/IEC  gives users of the MPEG-2 NBC/MPEG-4 Audio
   standards free license to this software module or modifications thereof
   for use in hardware or software products claiming conformance to the
   MPEG-2 NBC/MPEG-4 Audio  standards. Those intending to use this software
   module in hardware or software products are advised that this use may
   infringe existing patents. The original developer of this software
   module and his/her company, the subsequent editors and their companies,
   and ISO/IEC have no liability for use of this software module or
   modifications thereof in an implementation. Copyright is not released
   for non MPEG-2 NBC/MPEG-4 Audio conforming products.The original
   developer retains full right to use the code for his/her own purpose,
   assign or donate the code to a third party and to inhibit third party
   from using the code for non MPEG-2 NBC/MPEG-4 Audio conforming products.
   This copyright notice must be included in all copies or derivative
   works."
   Copyright(c)1996.

 (2) Introduction to Algorithms,
     Thomas H. Cormen, Charles E. Leiserson, Ronald L. Rivest.
     The MIT press, 1990

 (3) "Selecting an Optimal Huffman Decoder for AAC",
     Vladimir Z. Mesarovic, et al.
     AES 111th Convention, September 21-24, 2001, New York, USA

------------------------------------------------------------------------------
 PSEUDO-CODE


------------------------------------------------------------------------------
 RESOURCES USED
   When the code is written for a specific target processor the
     the resources used should be documented below.

 STACK USAGE:

 DATA MEMORY USED: x words

 PROGRAM MEMORY USED: x words

 CLOCK CYCLES:


------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include    "pv_audio_type_defs.h"
#include    "huffman.h"


/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/
#define    MAX_CW_LEN  (19)
#define    MASK_IDX    (0x1FF)
#define    MASK_RIGHT  (0xFE00)

#define    UPPER16      (16)
#define    MASK_LOW16   (0xFFFF)
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

Int decode_huff_cw_tab1(
    BITS          *pInputStream)
{
    Int32  tab;
    Int32  cw;

    cw  = get17_n_lessbits(
              11,
              pInputStream);
    if ((cw >> 10) == 0)
    {
        pInputStream->usedBits -= (11 - 1);
        return 40; /* idx is 40 */
    }
    else if ((cw >> 6) <= 23)
    {
        tab = (cw >> 6) - 16;
    }
    else if ((cw >> 4) <= 119)
    {
        tab = (cw >> 4) - 96 + 8;
    }
    else if ((cw >> 2) <= 503)
    {
        tab = (cw >> 2) - 480 + 32;
    }
    else
    {
        tab = cw - 2016 + 56;
    }

    tab = *(huff_tab1 + tab);

    pInputStream->usedBits -= (11 - (tab & MASK_LOW16));
    return ((Int)(tab >> UPPER16));
}


Int decode_huff_cw_tab2(
    BITS          *pInputStream)
{
    Int32  tab;
    Int32  cw;

    cw  = get9_n_lessbits(
              9,
              pInputStream);
    if ((cw >> 6) == 0)
    {
        pInputStream->usedBits -= (9 - 3); /* used 3 bits */
        return 40; /* idx is 40 */
    }
    else if ((cw >> 3) <= 49)
    {
        tab = (cw >> 3) - 8;
    }
    else if ((cw >> 2) <= 114)
    {
        tab = (cw >> 2) - 100 + 42;
    }
    else if ((cw >> 1) <= 248)
    {
        tab = (cw >> 1) - 230 + 57;
    }
    else
    {
        tab = cw - 498 + 76;
    }

    tab = *(huff_tab2 + tab);

    pInputStream->usedBits -= (9 - (tab & MASK_LOW16));
    return ((Int)(tab >> UPPER16));
}


Int decode_huff_cw_tab3(
    BITS          *pInputStream)
{
    Int32  tab;
    Int32  cw;

    cw  = get17_n_lessbits(
              16,
              pInputStream);
    if ((cw >> 15) == 0)
    {
        pInputStream->usedBits -= (16 - 1); /* used 1 bits */
        return 0; /* idx is 0 */
    }
    else if ((cw >> 10) <= 57)
    {
        tab = (cw >> 10) - 32;
    }
    else if ((cw >> 7) <= 500)
    {
        tab = (cw >> 7) - 464 + 26;
    }
    else if ((cw >> 6) <= 1016)
    {
        tab = (cw >> 6) - 1002 + 63;
    }
    else if ((cw >> 4) <= 4092)
    {
        tab = (cw >> 4) - 4068 + 78;
    }
    else
    {
        tab = cw - 65488 + 103;
    }

    tab = *(huff_tab3 + tab);

    pInputStream->usedBits -= (16 - (tab & MASK_LOW16));
    return ((Int)(tab >> UPPER16));
}


Int decode_huff_cw_tab4(
    BITS          *pInputStream)
{
    Int32  tab;
    Int32  cw;

    cw  = get17_n_lessbits(
              12,
              pInputStream);

    if ((cw >> 7) <= 25)
    {
        tab = (cw >> 7);
    }
    else if ((cw >> 4) <= 246)
    {
        tab = (cw >> 4) - 208 + 26;
    }
    else if ((cw >> 2) <= 1017)
    {
        tab = (cw >> 2) - 988 + 65;
    }
    else
    {
        tab = cw - 4072 + 95;
    }

    tab = *(huff_tab4 + tab);

    pInputStream->usedBits -= (12 - (tab & MASK_LOW16));
    return ((Int)(tab >> UPPER16));
}



Int decode_huff_cw_tab5(
    BITS          *pInputStream)
{
    Int32  tab;
    Int32  cw;

    cw  = get17_n_lessbits(
              13,
              pInputStream);

    if ((cw >> 12) == 0)
    {
        pInputStream->usedBits -= (13 - 1); /* used 1 bits */
        return 40; /* idx is 40 */
    }
    else if ((cw >> 8) <= 27)
    {
        tab = (cw >> 8) - 16;
    }
    else if ((cw >> 5) <= 243)
    {
        tab = (cw >> 5) - 224 + 12;
    }
    else if ((cw >> 3) <= 1011)
    {
        tab = (cw >> 3) - 976 + 32;
    }
    else if ((cw >> 2) <= 2041)
    {
        tab = (cw >> 2) - 2024 + 68;
    }
    else
    {
        tab = cw - 8168 + 86;
    }

    tab = *(huff_tab5 + tab);

    pInputStream->usedBits -= (13 - (tab & MASK_LOW16));
    return ((Int)(tab >> UPPER16));
}



Int decode_huff_cw_tab6(
    BITS          *pInputStream)
{
    Int32  tab;
    Int32  cw;

    cw  = get17_n_lessbits(
              11,
              pInputStream);

    if ((cw >> 7) <= 8)
    {
        tab = (cw >> 7);
    }
    else if ((cw >> 4) <= 116)
    {
        tab = (cw >> 4) - 72 + 9;
    }
    else if ((cw >> 2) <= 506)
    {
        tab = (cw >> 2) - 468 + 54;
    }
    else
    {
        tab = cw - 2028 + 93;
    }

    tab = *(huff_tab6 + tab);

    pInputStream->usedBits -= (11 - (tab & MASK_LOW16));
    return ((Int)(tab >> UPPER16));
}



Int decode_huff_cw_tab7(
    BITS          *pInputStream)
{
    Int32  tab;
    Int32  cw;

    cw  = get17_n_lessbits(
              12,
              pInputStream);

    if ((cw >> 11) == 0)
    {
        pInputStream->usedBits -= (12 - 1); /* used 1 bits */
        return 0; /* idx is 0 */
    }
    else if ((cw >> 6) <= 55)
    {
        tab = (cw >> 6) - 32;
    }
    else if ((cw >> 4) <= 243)
    {
        tab = (cw >> 4) - 224 + 24;
    }
    else if ((cw >> 2) <= 1018)
    {
        tab = (cw >> 2) - 976 + 44;
    }
    else
    {
        tab = cw - 4076 + 87;
    }

    tab = *(huff_tab7 + tab);

    pInputStream->usedBits -= (12 - (tab & MASK_LOW16));
    return ((Int)(tab >> UPPER16));
}



Int decode_huff_cw_tab8(
    BITS          *pInputStream)
{
    Int32  tab;
    Int32  cw;

    cw  = get17_n_lessbits(
              10,
              pInputStream);

    if ((cw >> 5) <= 20)
    {
        tab = (cw >> 5);
    }
    else if ((cw >> 3) <= 117)
    {
        tab = (cw >> 3) - 84 + 21;
    }
    else if ((cw >> 2) <= 250)
    {
        tab = (cw >> 2) - 236 + 55;
    }
    else
    {
        tab = cw - 1004 + 70;
    }

    tab = *(huff_tab8 + tab);

    pInputStream->usedBits -= (10 - (tab & MASK_LOW16));
    return ((Int)(tab >> UPPER16));
}


Int decode_huff_cw_tab9(
    BITS          *pInputStream)
{
    Int32  tab;
    Int32  cw;

    cw  = get17_n_lessbits(
              15,
              pInputStream);

    if ((cw >> 11) <= 12)
    {
        tab = (cw >> 11);
    }
    else if ((cw >> 8) <= 114)
    {
        tab = (cw >> 8) - 104 + 13;
    }
    else if ((cw >> 6) <= 486)
    {
        tab = (cw >> 6) - 460 + 24;
    }
    else if ((cw >> 5) <= 993)
    {
        tab = (cw >> 5) - 974 + 51;
    }
    else if ((cw >> 4) <= 2018)
    {
        tab = (cw >> 4) - 1988 + 71;
    }
    else if ((cw >> 3) <= 4075)
    {
        tab = (cw >> 3) - 4038 + 102;
    }
    else if ((cw >> 2) <= 8183)
    {
        tab = (cw >> 2) - 8152 + 140;
    }
    else
    {
        tab = cw - 32736 + 172;
    }

    tab = *(huff_tab9 + tab);

    pInputStream->usedBits -= (15 - (tab & MASK_LOW16));
    return ((Int)(tab >> UPPER16));
}


Int decode_huff_cw_tab10(
    BITS          *pInputStream)
{
    Int32  tab;
    Int32  cw;

    cw  = get17_n_lessbits(
              12,
              pInputStream);

    if ((cw >> 6) <= 41)
    {
        tab = (cw >> 6);
    }
    else if ((cw >> 5) <= 100)
    {
        tab = (cw >> 5) - 84 + 42;
    }
    else if ((cw >> 4) <= 226)
    {
        tab = (cw >> 4) - 202 + 59;
    }
    else if ((cw >> 3) <= 484)
    {
        tab = (cw >> 3) - 454 + 84;
    }
    else if ((cw >> 2) <= 1010)
    {
        tab = (cw >> 2) - 970 + 115;
    }
    else if ((cw >> 1) <= 2043)
    {
        tab = (cw >> 1) - 2022 + 156;
    }
    else
    {
        tab = cw - 4088 + 178;
    }

    tab = *(huff_tab10 + tab);

    pInputStream->usedBits -= (12 - (tab & MASK_LOW16));
    return ((Int)(tab >> UPPER16));
}


Int decode_huff_cw_tab11(
    BITS          *pInputStream)
{
    Int32  tab;
    Int32  cw;

    cw  = get17_n_lessbits(
              12,
              pInputStream);

    if ((cw >> 6) <= 26)
    {
        tab = (cw >> 6);
    }
    else if ((cw >> 5) <= 69)
    {
        tab = (cw >> 5) - 54 + 27;
    }
    else if ((cw >> 4) <= 198)
    {
        tab = (cw >> 4) - 140 + 43;
    }
    else if ((cw >> 3) <= 452)
    {
        tab = (cw >> 3) - 398 + 102;
    }
    else if ((cw >> 2) <= 1000)
    {
        tab = (cw >> 2) - 906 + 157;
    }
    else if ((cw >> 1) <= 2044)
    {
        tab = (cw >> 1) - 2002 + 252;
    }
    else
    {
        tab = cw - 4090 + 295;
    }

    tab = *(huff_tab11 + tab);

    pInputStream->usedBits -= (12 - (tab & MASK_LOW16));
    return ((Int)(tab >> UPPER16));
}


Int decode_huff_scl(
    BITS          *pInputStream)
{
    Int32  tab;
    Int32  cw;

    cw  = getbits(
              19,
              pInputStream);

    if ((cw >> 18) == 0)
    {
        pInputStream->usedBits -= (19 - 1); /* used 1 bits */
        return 60; /* idx is 60 */
    }
    else if ((cw >> 13) <= 59)
    {
        tab = (cw >> 13) - 32;
    }
    else if ((cw >> 10) <= 505)
    {
        tab = (cw >> 10) - 480 + 28;
    }
    else if ((cw >> 7) <= 4089)
    {
        tab = (cw >> 7) - 4048 + 54;
    }
    else if ((cw >> 5) <= 16377)
    {
        tab = (cw >> 5) - 16360 + 96;
    }
    else if ((cw >> 3) <= 65526)
    {
        tab = (cw >> 3) - 65512 + 114;
    }
    else if ((cw >> 1) <= 262120)
    {
        tab = (cw >> 1) - 262108 + 129;
    }
    else
    {
        tab = cw - 524242 + 142;
    }

    tab = *(huff_tab_scl + tab);

    pInputStream->usedBits -= (19 - (tab & MASK_LOW16));
    return ((Int)(tab >> UPPER16));
}

