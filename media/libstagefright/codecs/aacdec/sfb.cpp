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

 Pathname: ./src/sfb.c


------------------------------------------------------------------------------
 REVISION HISTORY

 Description: created to define the scalefactor bands for all sampling rates

 Description: Change short to Int16

 Description: Modified structure to avoid assigning addresses to constant
              tables. This solve linking problem when using the
              /ropi option (Read-only position independent) for some
              compilers
              - Eliminated redundant vector sfb_96_128.
              - Eliminated references to contant vector addresses in
                samp_rate_info

 Who:                              Date:
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:

 Local Stores/Buffers/Pointers Needed:

 Global Stores/Buffers/Pointers Needed:

 Outputs:

 Pointers and Buffers Modified:


 Local Stores Modified:

 Global Stores Modified:

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function defines the scalefactor bands for all sampling rates

------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

 (1) ISO/IEC 14496-3: 1999(E)
    Subpart 4       p66     (sfb tables)
                    p111    (4.6.10)
                    p200    (Annex 4.B.5)

------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
 RESOURCES USED
   When the code is written for a specific target processor the
     the resources used should be documented below.

 STACK USAGE: [stack count for this module] + [variable to represent
          stack usage for each subroutine called]

     where: [stack usage variable] = stack usage for [subroutine
         name] (see [filename].ext)

 DATA MEMORY USED: x words

 PROGRAM MEMORY USED: x words

 CLOCK CYCLES: [cycle count equation for this module] + [variable
           used to represent cycle count for each subroutine
           called]

     where: [cycle count variable] = cycle count for [subroutine
        name] (see [filename].ext)

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include    "pv_audio_type_defs.h"
#include    "sfb.h"

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
const Int16 sfb_96_1024[] =
{
    4, 8, 12, 16, 20, 24, 28,
    32, 36, 40, 44, 48, 52, 56,
    64, 72, 80, 88, 96, 108, 120,
    132, 144, 156, 172, 188, 212, 240,
    276, 320, 384, 448, 512, 576, 640,
    704, 768, 832, 896, 960, 1024
};         /* 41 scfbands */

const Int16 sfb_64_1024[] =
{
    4, 8, 12, 16, 20, 24, 28,
    32, 36, 40, 44, 48, 52, 56,
    64, 72, 80, 88, 100, 112, 124,
    140, 156, 172, 192, 216, 240, 268,
    304, 344, 384, 424, 464, 504, 544,
    584, 624, 664, 704, 744, 784, 824,
    864, 904, 944, 984, 1024
};               /* 41 scfbands 47 */

const Int16 sfb_64_128[] =
{
    4, 8, 12, 16, 20, 24, 32,
    40, 48, 64, 92, 128
};                   /* 12 scfbands */


const Int16 sfb_48_1024[] =
{
    4,  8,  12, 16, 20, 24, 28,
    32, 36, 40, 48, 56, 64, 72,
    80, 88, 96, 108,    120,    132,    144,
    160,    176,    196,    216,    240,    264,    292,
    320,    352,    384,    416,    448,    480,    512,
    544,    576,    608,    640,    672,    704,    736,
    768,    800,    832,    864,    896,    928,    1024
};
/* 49  scfbands*/

const Int16 sfb_48_128[] =
{
    4,  8,  12, 16, 20, 28, 36,
    44, 56, 68, 80, 96, 112, 128
};         /* 14 scfbands */

const Int16 sfb_32_1024[] =
{
    4,  8,  12, 16, 20, 24, 28,
    32, 36, 40, 48, 56, 64, 72,
    80, 88, 96, 108,    120,    132,    144,
    160,    176,    196,    216,    240,    264,    292,
    320,    352,    384,    416,    448,    480,    512,
    544,    576,    608,    640,    672,    704,    736,
    768,    800,    832,    864,    896,    928,    960,
    992,    1024
};                         /* 51 scfbands */

const Int16 sfb_24_1024[] =
{
    4, 8, 12, 16, 20, 24, 28,
    32, 36, 40, 44, 52, 60, 68,
    76, 84, 92, 100, 108, 116, 124,
    136, 148, 160, 172, 188, 204, 220,
    240, 260, 284, 308, 336, 364, 396,
    432, 468, 508, 552, 600, 652, 704,
    768, 832, 896, 960, 1024
};              /* 47 scfbands */

const Int16 sfb_24_128[] =
{
    4, 8, 12, 16, 20, 24, 28,
    36, 44, 52, 64, 76, 92, 108,
    128
};                                   /* 15 scfbands */

const Int16 sfb_16_1024[] =
{
    8, 16, 24, 32, 40, 48, 56,
    64, 72, 80, 88, 100, 112, 124,
    136, 148, 160, 172, 184, 196, 212,
    228, 244, 260, 280, 300, 320, 344,
    368, 396, 424, 456, 492, 532, 572,
    616, 664, 716, 772, 832, 896, 960,
    1024
};                                  /* 43 scfbands */

const Int16 sfb_16_128[] =
{
    4, 8, 12, 16, 20, 24, 28,
    32, 40, 48, 60, 72, 88, 108,
    128
};                                   /* 15 scfbands */

const Int16 sfb_8_1024[] =
{
    12, 24, 36, 48, 60, 72, 84,
    96, 108, 120, 132, 144, 156, 172,
    188, 204, 220, 236, 252, 268, 288,
    308, 328, 348, 372, 396, 420, 448,
    476, 508, 544, 580, 620, 664, 712,
    764, 820, 880, 944, 1024
};               /* 40 scfbands */

const Int16 sfb_8_128[] =
{
    4, 8, 12, 16, 20, 24, 28,
    36, 44, 52, 60, 72, 88, 108,
    128
};                                   /* 15 scfbands */

const SR_Info samp_rate_info[12] =
{
    /* sampling_frequency, #long sfb, #short sfb */
    /* samp_rate, nsfb1024, nsfb128 */
    {96000, 41, 12},       /* 96000 */
    {88200, 41, 12},       /* 88200 */
    {64000, 47, 12},       /* 64000 */
    {48000, 49, 14},       /* 48000 */
    {44100, 49, 14},       /* 44100 */
    {32000, 51, 14},       /* 32000 */
    {24000, 47, 15},       /* 24000 */
    {22050, 47, 15},       /* 22050 */
    {16000, 43, 15},       /* 16000 */
    {12000, 43, 15},       /* 12000 */
    {11025, 43, 15},       /* 11025 */
    { 8000, 40, 15},       /* 8000  */
};


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


