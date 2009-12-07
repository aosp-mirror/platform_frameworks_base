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

   Filename: pvmp3_polyphase_filter_window.cpp

     Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY


 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS


Input
    int32 *synth_buffer,    synthesis input buffer
    int16 *outPcm,          generated output ( 32 values)
    int32 numChannels       number of channels
 Returns

    int16 *outPcm

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    apply polyphase filter window
    Input 32 subband samples
    Calculate 64 values
------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

 [1] ISO MPEG Audio Subgroup Software Simulation Group (1996)
     ISO 13818-3 MPEG-2 Audio Decoder - Lower Sampling Frequency Extension

------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
*/

#if ( !defined(PV_ARM_GCC_V5) && !defined(PV_ARM_GCC_V4) && !defined(PV_ARM_V5) && !defined(PV_ARM_V4) )
/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#include "pvmp3_polyphase_filter_window.h"
#include "pv_mp3dec_fxd_op.h"
#include "pvmp3_dec_defs.h"
#include "pvmp3_tables.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module1 specific macros here
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
; Variable declaration - defined here and used outside this module1
----------------------------------------------------------------------------*/
/*----------------------------------------------------------------------------
; EXTERNAL FUNCTION REFERENCES
; Declare functions defined elsewhere and referenced in this module_x
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL GLOBAL STORE/BUFFER/POINTER REFERENCES
; Declare variables used in this module_x but defined elsewhere
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

void pvmp3_polyphase_filter_window(int32 *synth_buffer,
                                   int16 *outPcm,
                                   int32 numChannels)
{
    int32 sum1;
    int32 sum2;
    const int32 *winPtr = pqmfSynthWin;
    int32 i;


    for (int16 j = 1; j < SUBBANDS_NUMBER / 2; j++)
    {
        sum1 = 0x00000020;
        sum2 = 0x00000020;


        for (i = (SUBBANDS_NUMBER >> 1);
                i < HAN_SIZE + (SUBBANDS_NUMBER >> 1);
                i += SUBBANDS_NUMBER << 4)
        {
            int32 *pt_1 = &synth_buffer[ i+j];
            int32 *pt_2 = &synth_buffer[ i-j];
            int32 temp1 = pt_1[ 0];
            int32 temp3 = pt_2[ SUBBANDS_NUMBER*15 ];
            int32 temp2 = pt_2[ SUBBANDS_NUMBER* 1 ];
            int32 temp4 = pt_1[ SUBBANDS_NUMBER*14 ];

            sum1  = fxp_mac32_Q32(sum1, temp1,  winPtr[ 0]);
            sum2  = fxp_mac32_Q32(sum2, temp3,  winPtr[ 0]);
            sum2  = fxp_mac32_Q32(sum2, temp1,  winPtr[ 1]);
            sum1  = fxp_msb32_Q32(sum1, temp3,  winPtr[ 1]);
            sum1  = fxp_mac32_Q32(sum1, temp2,  winPtr[ 2]);
            sum2  = fxp_msb32_Q32(sum2, temp4,  winPtr[ 2]);
            sum2  = fxp_mac32_Q32(sum2, temp2,  winPtr[ 3]);
            sum1  = fxp_mac32_Q32(sum1, temp4,  winPtr[ 3]);

            temp1 = pt_1[ SUBBANDS_NUMBER* 2];
            temp3 = pt_2[ SUBBANDS_NUMBER*13];
            temp2 = pt_2[ SUBBANDS_NUMBER* 3];
            temp4 = pt_1[ SUBBANDS_NUMBER*12];

            sum1  = fxp_mac32_Q32(sum1, temp1,  winPtr[ 4]);
            sum2  = fxp_mac32_Q32(sum2, temp3,  winPtr[ 4]);
            sum2  = fxp_mac32_Q32(sum2, temp1,  winPtr[ 5]);
            sum1  = fxp_msb32_Q32(sum1, temp3,  winPtr[ 5]);
            sum1  = fxp_mac32_Q32(sum1, temp2,  winPtr[ 6]);
            sum2  = fxp_msb32_Q32(sum2, temp4,  winPtr[ 6]);
            sum2  = fxp_mac32_Q32(sum2, temp2,  winPtr[ 7]);
            sum1  = fxp_mac32_Q32(sum1, temp4,  winPtr[ 7]);

            temp1 = pt_1[ SUBBANDS_NUMBER* 4 ];
            temp3 = pt_2[ SUBBANDS_NUMBER*11 ];
            temp2 = pt_2[ SUBBANDS_NUMBER* 5 ];
            temp4 = pt_1[ SUBBANDS_NUMBER*10 ];

            sum1  = fxp_mac32_Q32(sum1, temp1,  winPtr[ 8]);
            sum2  = fxp_mac32_Q32(sum2, temp3,  winPtr[ 8]);
            sum2  = fxp_mac32_Q32(sum2, temp1,  winPtr[ 9]);
            sum1  = fxp_msb32_Q32(sum1, temp3,  winPtr[ 9]);
            sum1  = fxp_mac32_Q32(sum1, temp2,  winPtr[10]);
            sum2  = fxp_msb32_Q32(sum2, temp4,  winPtr[10]);
            sum2  = fxp_mac32_Q32(sum2, temp2,  winPtr[11]);
            sum1  = fxp_mac32_Q32(sum1, temp4,  winPtr[11]);

            temp1 = pt_1[ SUBBANDS_NUMBER*6 ];
            temp3 = pt_2[ SUBBANDS_NUMBER*9 ];
            temp2 = pt_2[ SUBBANDS_NUMBER*7 ];
            temp4 = pt_1[ SUBBANDS_NUMBER*8 ];

            sum1  = fxp_mac32_Q32(sum1, temp1,  winPtr[12]);
            sum2  = fxp_mac32_Q32(sum2, temp3,  winPtr[12]);
            sum2  = fxp_mac32_Q32(sum2, temp1,  winPtr[13]);
            sum1  = fxp_msb32_Q32(sum1, temp3,  winPtr[13]);
            sum1  = fxp_mac32_Q32(sum1, temp2,  winPtr[14]);
            sum2  = fxp_msb32_Q32(sum2, temp4,  winPtr[14]);
            sum2  = fxp_mac32_Q32(sum2, temp2,  winPtr[15]);
            sum1  = fxp_mac32_Q32(sum1, temp4,  winPtr[15]);

            winPtr += 16;
        }



        int32 k = j << (numChannels - 1);
        outPcm[k] = saturate16(sum1 >> 6);
        outPcm[(numChannels<<5) - k] = saturate16(sum2 >> 6);
    }



    sum1 = 0x00000020;
    sum2 = 0x00000020;


    for (i = 16; i < HAN_SIZE + 16; i += (SUBBANDS_NUMBER << 2))
    {
        int32 *pt_synth = &synth_buffer[i];
        int32 temp1 = pt_synth[ 0                ];
        int32 temp2 = pt_synth[ SUBBANDS_NUMBER  ];
        int32 temp3 = pt_synth[ SUBBANDS_NUMBER/2];

        sum1 = fxp_mac32_Q32(sum1, temp1, winPtr[0]) ;
        sum1 = fxp_mac32_Q32(sum1, temp2, winPtr[1]) ;
        sum2 = fxp_mac32_Q32(sum2, temp3, winPtr[2]) ;

        temp1 = pt_synth[ SUBBANDS_NUMBER<<1 ];
        temp2 = pt_synth[ 3*SUBBANDS_NUMBER  ];
        temp3 = pt_synth[ SUBBANDS_NUMBER*5/2];

        sum1 = fxp_mac32_Q32(sum1, temp1, winPtr[3]) ;
        sum1 = fxp_mac32_Q32(sum1, temp2, winPtr[4]) ;
        sum2 = fxp_mac32_Q32(sum2, temp3, winPtr[5]) ;

        winPtr += 6;
    }


    outPcm[0] = saturate16(sum1 >> 6);
    outPcm[(SUBBANDS_NUMBER/2)<<(numChannels-1)] = saturate16(sum2 >> 6);


}

#endif // If not assembly

