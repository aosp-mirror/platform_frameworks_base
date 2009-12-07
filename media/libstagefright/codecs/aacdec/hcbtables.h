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

 Pathname: hcbtables.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: (1) Add declaration of binary tree tables
              (2) #if optimized Linear Search Huffman decoding

 Description: Modified per review comments
              (1) delete #if optimized Linear Search Huffman decoding
              (2) modified copyright header

 Description: (1) Add declaration different huffman tables

 Who:                              Date:
 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 Declare the structure array for Huffman Codebooks information.

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef _HCBTABLES_H
#define _HCBTABLES_H

#ifdef __cplusplus
extern "C"
{
#endif

    /*----------------------------------------------------------------------------
    ; INCLUDES
    ----------------------------------------------------------------------------*/
#include    "s_hcb.h"

    /*----------------------------------------------------------------------------
    ; MACROS
    ; Define module specific macros here
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; DEFINES
    ; Include all pre-processor statements here.
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; EXTERNAL VARIABLES REFERENCES
    ; Declare variables used in this module but defined elsewhere
    ----------------------------------------------------------------------------*/

    /* ISO: Hcb book[NSPECBOOKS + 2]; */

    extern const Hcb hcbbook_binary[13];
    extern const Int32 huff_tab1[88];
    extern const Int32 huff_tab2[90];
    extern const Int32 huff_tab3[151];
    extern const Int32 huff_tab4[119];
    extern const Int32 huff_tab5[110];
    extern const Int32 huff_tab6[113];
    extern const Int32 huff_tab7[107];
    extern const Int32 huff_tab8[90];
    extern const Int32 huff_tab9[204];
    extern const Int32 huff_tab10[186];
    extern const Int32 huff_tab11[301];
    extern const UInt32 huff_tab_scl[188];

    /*----------------------------------------------------------------------------
    ; SIMPLE TYPEDEF'S
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; ENUMERATED TYPEDEF'S
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; STRUCTURES TYPEDEF'S
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; GLOBAL FUNCTION DEFINITIONS
    ; Function Prototype declaration
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; END
    ----------------------------------------------------------------------------*/
#ifdef __cplusplus
}
#endif

#endif


