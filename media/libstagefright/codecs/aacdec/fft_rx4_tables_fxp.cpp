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

 Pathname: ./src/fft_rx4_tables_fxp.c
 Funtions:

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Reduce the accuracy of w_256rx4 and w_512rx2 to Q10 format.
            Try to to pack sin and cos into one 32-bit number to reduce the
            memory access, but doesn't help in speed, so commented out for now.

 Description:
        (1) Reduced precision of w_64rx4 from Q15 to Q12.
        (2) Increased precision of w_512rx2 from Q10 to Q13, Both changes
            increase overall decoder precision

 Description:
        (1) per code review comment, added description for table generation
        (2) modified definition of w_64rx4 from Int to Int16


 Who:                           Date:
 Description:

  ----------------------------------------------------------------------------
 MODULE DESCRIPTION

  Table generation

 n = 256  or  64;
 M = precision; 2^10, 2^12, 2^13

 for j=1; j<log4(n); j *= 4

    for i=0; i<n/4; i +=j

        phi_1 = 2*pi*i/n;
        phi_2 = 4*pi*i/n;
        phi_3 = 6*pi*i/n;
        M*[cos(phi_1) sin(phi_1) cos(phi_2) sin(phi_2) cos(phi_3) sin(phi_4)];

    end

 end

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#include "fft_rx4.h"

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
; LOCAL VARIABLE DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; EXTERNAL FUNCTION REFERENCES
; Declare functions defined elsewhere and referenced in this module
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL VARIABLES REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/

/*
------------------------------------------------------------------------------
 Forward FFT radix-4 tables
------------------------------------------------------------------------------
*/


const Int32 W_64rx4[60] =            /* 2 Q15  */
{

    0x7F610C8C,  0x7D8918F9,  0x7A7C2528,
    0x7D8918F9,  0x764130FB,  0x6A6D471C,
    0x7A7C2528,  0x6A6D471C,  0x513362F1,
    0x764130FB,  0x5A825A82,  0x30FB7641,
    0x70E23C56,  0x471C6A6D,  0x0C8C7F61,
    0x6A6D471C,  0x30FB7641,  0xE7057D89,
    0x62F15133,  0x18F97D89,  0xC3A870E2,
    0x5A825A82,  0x00007FFF,  0xA57C5A82,
    0x513362F1,  0xE7057D89,  0x8F1C3C56,
    0x471C6A6D,  0xCF037641,  0x827518F9,
    0x3C5670E2,  0xB8E26A6D,  0x809DF372,
    0x30FB7641,  0xA57C5A82,  0x89BDCF03,
    0x25287A7C,  0x9591471C,  0x9D0DAECB,
    0x18F97D89,  0x89BD30FB,  0xB8E29591,
    0x0C8C7F61,  0x827518F9,  0xDAD68582,
    0x764130FB,  0x5A825A82,  0x30FB7641,
    0x5A825A82,  0x00007FFF,  0xA57C5A82,
    0x30FB7641,  0xA57C5A82,  0x89BDCF03,
};



const Int32 W_256rx4[495] =            /* 2 Q15  */
{

    0x7FF50324,  0x7FD80648,  0x7FA6096A,
    0x7FD80648,  0x7F610C8C,  0x7E9C12C8,
    0x7FA6096A,  0x7E9C12C8,  0x7CE31C0B,
    0x7F610C8C,  0x7D8918F9,  0x7A7C2528,
    0x7F090FAB,  0x7C291F1A,  0x776B2E11,
    0x7E9C12C8,  0x7A7C2528,  0x73B536BA,
    0x7E1D15E2,  0x78842B1F,  0x6F5E3F17,
    0x7D8918F9,  0x764130FB,  0x6A6D471C,
    0x7CE31C0B,  0x73B536BA,  0x64E84EBF,
    0x7C291F1A,  0x70E23C56,  0x5ED755F5,
    0x7B5C2223,  0x6DC941CE,  0x58425CB3,
    0x7A7C2528,  0x6A6D471C,  0x513362F1,
    0x79892826,  0x66CF4C3F,  0x49B468A6,
    0x78842B1F,  0x62F15133,  0x41CE6DC9,
    0x776B2E11,  0x5ED755F5,  0x398C7254,
    0x764130FB,  0x5A825A82,  0x30FB7641,
    0x750433DF,  0x55F55ED7,  0x28267989,
    0x73B536BA,  0x513362F1,  0x1F1A7C29,
    0x7254398C,  0x4C3F66CF,  0x15E27E1D,
    0x70E23C56,  0x471C6A6D,  0x0C8C7F61,
    0x6F5E3F17,  0x41CE6DC9,  0x03247FF5,
    0x6DC941CE,  0x3C5670E2,  0xF9B67FD8,
    0x6C23447A,  0x36BA73B5,  0xF0537F09,
    0x6A6D471C,  0x30FB7641,  0xE7057D89,
    0x68A649B4,  0x2B1F7884,  0xDDDB7B5C,
    0x66CF4C3F,  0x25287A7C,  0xD4DF7884,
    0x64E84EBF,  0x1F1A7C29,  0xCC1F7504,
    0x62F15133,  0x18F97D89,  0xC3A870E2,
    0x60EB539B,  0x12C87E9C,  0xBB846C23,
    0x5ED755F5,  0x0C8C7F61,  0xB3BF66CF,
    0x5CB35842,  0x06487FD8,  0xAC6360EB,
    0x5A825A82,  0x00007FFF,  0xA57C5A82,
    0x58425CB3,  0xF9B67FD8,  0x9F13539B,
    0x55F55ED7,  0xF3727F61,  0x992F4C3F,
    0x539B60EB,  0xED367E9C,  0x93DB447A,
    0x513362F1,  0xE7057D89,  0x8F1C3C56,
    0x4EBF64E8,  0xE0E47C29,  0x8AFA33DF,
    0x4C3F66CF,  0xDAD67A7C,  0x877A2B1F,
    0x49B468A6,  0xD4DF7884,  0x84A22223,
    0x471C6A6D,  0xCF037641,  0x827518F9,
    0x447A6C23,  0xC94473B5,  0x80F50FAB,
    0x41CE6DC9,  0xC3A870E2,  0x80260648,
    0x3F176F5E,  0xBE306DC9,  0x8009FCDA,
    0x3C5670E2,  0xB8E26A6D,  0x809DF372,
    0x398C7254,  0xB3BF66CF,  0x81E1EA1C,
    0x36BA73B5,  0xAECB62F1,  0x83D5E0E4,
    0x33DF7504,  0xAA095ED7,  0x8675D7D8,
    0x30FB7641,  0xA57C5A82,  0x89BDCF03,
    0x2E11776B,  0xA12755F5,  0x8DAAC672,
    0x2B1F7884,  0x9D0D5133,  0x9235BE30,
    0x28267989,  0x992F4C3F,  0x9758B64A,
    0x25287A7C,  0x9591471C,  0x9D0DAECB,
    0x22237B5C,  0x923541CE,  0xA34BA7BC,
    0x1F1A7C29,  0x8F1C3C56,  0xAA09A127,
    0x1C0B7CE3,  0x8C4936BA,  0xB13F9B16,
    0x18F97D89,  0x89BD30FB,  0xB8E29591,
    0x15E27E1D,  0x877A2B1F,  0xC0E790A0,
    0x12C87E9C,  0x85822528,  0xC9448C49,
    0x0FAB7F09,  0x83D51F1A,  0xD1ED8893,
    0x0C8C7F61,  0x827518F9,  0xDAD68582,
    0x096A7FA6,  0x816212C8,  0xE3F3831B,
    0x06487FD8,  0x809D0C8C,  0xED368162,
    0x03247FF5,  0x80260648,  0xF6948058,
    0x7F610C8C,  0x7D8918F9,  0x7A7C2528,
    0x7D8918F9,  0x764130FB,  0x6A6D471C,
    0x7A7C2528,  0x6A6D471C,  0x513362F1,
    0x764130FB,  0x5A825A82,  0x30FB7641,
    0x70E23C56,  0x471C6A6D,  0x0C8C7F61,
    0x6A6D471C,  0x30FB7641,  0xE7057D89,
    0x62F15133,  0x18F97D89,  0xC3A870E2,
    0x5A825A82,  0x00007FFF,  0xA57C5A82,
    0x513362F1,  0xE7057D89,  0x8F1C3C56,
    0x471C6A6D,  0xCF037641,  0x827518F9,
    0x3C5670E2,  0xB8E26A6D,  0x809DF372,
    0x30FB7641,  0xA57C5A82,  0x89BDCF03,
    0x25287A7C,  0x9591471C,  0x9D0DAECB,
    0x18F97D89,  0x89BD30FB,  0xB8E29591,
    0x0C8C7F61,  0x827518F9,  0xDAD68582,
    0x764130FB,  0x5A825A82,  0x30FB7641,
    0x5A825A82,  0x00007FFF,  0xA57C5A82,
    0x30FB7641,  0xA57C5A82,  0x89BDCF03
};



/*
------------------------------------------------------------------------------
 Forward FFT radix-2 table
------------------------------------------------------------------------------
*/


const Int32 w_512rx2[127] =
{
    /* Q15  */
    0x7FFE0192, 0x7FF60324, 0x7FEA04B6,
    0x7FD90648,  0x7FC207D9, 0x7FA7096B, 0x7F870AFB,
    0x7F620C8C,  0x7F380E1C, 0x7F0A0FAB, 0x7ED6113A,
    0x7E9D12C8,  0x7E601455, 0x7E1E15E2, 0x7DD6176E,
    0x7D8A18F9,  0x7D3A1A83, 0x7CE41C0C, 0x7C891D93,
    0x7C2A1F1A,  0x7BC6209F, 0x7B5D2224, 0x7AEF23A7,
    0x7A7D2528,  0x7A0626A8, 0x798A2827, 0x790A29A4,
    0x78852B1F,  0x77FB2C99, 0x776C2E11, 0x76D92F87,
    0x764230FC,  0x75A6326E, 0x750533DF, 0x7460354E,
    0x73B636BA,  0x73083825, 0x7255398D, 0x719E3AF3,
    0x70E33C57,  0x70233DB8, 0x6F5F3F17, 0x6E974074,
    0x6DCA41CE,  0x6CF94326, 0x6C24447B, 0x6B4B45CD,
    0x6A6E471D,  0x698C486A, 0x68A749B4, 0x67BD4AFB,
    0x66D04C40,  0x65DE4D81, 0x64E94EC0, 0x63EF4FFB,
    0x62F25134,  0x61F15269, 0x60EC539B, 0x5FE454CA,
    0x5ED755F6,  0x5DC8571E, 0x5CB45843, 0x5B9D5964,
    0x5A825A82,  0x59645B9D, 0x58435CB4, 0x571E5DC8,
    0x55F65ED7,  0x54CA5FE4, 0x539B60EC, 0x526961F1,
    0x513462F2,  0x4FFB63EF, 0x4EC064E9, 0x4D8165DE,
    0x4C4066D0,  0x4AFB67BD, 0x49B468A7, 0x486A698C,
    0x471D6A6E,  0x45CD6B4B, 0x447B6C24, 0x43266CF9,
    0x41CE6DCA,  0x40746E97, 0x3F176F5F, 0x3DB87023,
    0x3C5770E3,  0x3AF3719E, 0x398D7255, 0x38257308,
    0x36BA73B6,  0x354E7460, 0x33DF7505, 0x326E75A6,
    0x30FC7642,  0x2F8776D9, 0x2E11776C, 0x2C9977FB,
    0x2B1F7885,  0x29A4790A, 0x2827798A, 0x26A87A06,
    0x25287A7D,  0x23A77AEF, 0x22247B5D, 0x209F7BC6,
    0x1F1A7C2A,  0x1D937C89, 0x1C0C7CE4, 0x1A837D3A,
    0x18F97D8A,  0x176E7DD6, 0x15E27E1E, 0x14557E60,
    0x12C87E9D,  0x113A7ED6, 0x0FAB7F0A, 0x0E1C7F38,
    0x0C8C7F62,  0x0AFB7F87, 0x096B7FA7, 0x07D97FC2,
    0x06487FD9,  0x04B67FEA, 0x03247FF6, 0x01927FFE
};

