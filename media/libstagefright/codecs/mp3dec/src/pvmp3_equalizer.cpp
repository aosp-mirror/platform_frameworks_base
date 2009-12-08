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

   Filename: pvmp3_equalizer.cpp


     Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY


 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

  Input
    int32          *inData,           pointer to the spectrum frequency-line
    e_equalization equalizerType,     equalization mode
    int32          *pt_work_buff

  Output
    int32          *pt_work_buff      pointer to the equalized frequency-line

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    Equalizer
    Each subband sample is scaled according to a spectrum shape setting
    defined by "equalizerType"

------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#include "pvmp3_equalizer.h"
#include "pv_mp3dec_fxd_op.h"
#include "pvmp3_dec_defs.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/
#define LEVEL__0__dB  0.999999970f
#define LEVEL__1_5dB  0.841395142f
#define LEVEL__3__dB  0.707106781f
#define LEVEL__4_5dB  0.595662143f
#define LEVEL__6__dB  0.500000000f
#define LEVEL__7_5dB  0.421696503f
#define LEVEL__9__dB  0.353553393f
#define LEVEL_12__dB  0.250000000f
#define LEVEL_15__dB  0.176776695f
#define LEVEL_18__dB  0.125000000f
#define LEVEL_21__dB  0.088388347f
#define LEVEL_30__dB  0.031250000f
#define LEVEL_45__dB  0.005524271f
#define LEVEL_60__dB  0.000976562f

#define Qmf31( x)    (int32)(x*(float)0x7FFFFFFF)


/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/

const int32 equalizerTbl[8][SUBBANDS_NUMBER] =
{
    /*  FLAT */
    {
        Qmf31(LEVEL__0__dB),

        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),

        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),

        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),

        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB)
    },
    /*  BASS BOOST */
    {
        Qmf31(LEVEL__0__dB),

        Qmf31(LEVEL__1_5dB), Qmf31(LEVEL__3__dB),

        Qmf31(LEVEL__4_5dB), Qmf31(LEVEL__6__dB),
        Qmf31(LEVEL__6__dB), Qmf31(LEVEL__6__dB),

        Qmf31(LEVEL__6__dB), Qmf31(LEVEL__6__dB),
        Qmf31(LEVEL__6__dB), Qmf31(LEVEL__6__dB),
        Qmf31(LEVEL__6__dB), Qmf31(LEVEL__6__dB),
        Qmf31(LEVEL__6__dB), Qmf31(LEVEL__6__dB),

        Qmf31(LEVEL__6__dB), Qmf31(LEVEL__6__dB),
        Qmf31(LEVEL__6__dB), Qmf31(LEVEL__6__dB),
        Qmf31(LEVEL__6__dB), Qmf31(LEVEL__6__dB),
        Qmf31(LEVEL__6__dB), Qmf31(LEVEL__6__dB),
        Qmf31(LEVEL__6__dB), Qmf31(LEVEL__6__dB),
        Qmf31(LEVEL__6__dB), Qmf31(LEVEL__6__dB),
        Qmf31(LEVEL__6__dB), Qmf31(LEVEL__6__dB),
        Qmf31(LEVEL__6__dB), Qmf31(LEVEL__6__dB),
        Qmf31(LEVEL__6__dB)
    },
    /*  ROCK */
    {
        Qmf31(LEVEL__0__dB),

        Qmf31(LEVEL__1_5dB), Qmf31(LEVEL__3__dB),

        Qmf31(LEVEL__4_5dB), Qmf31(LEVEL__6__dB),
        Qmf31(LEVEL__6__dB), Qmf31(LEVEL__6__dB),

        Qmf31(LEVEL__3__dB), Qmf31(LEVEL__3__dB),
        Qmf31(LEVEL__3__dB), Qmf31(LEVEL__3__dB),
        Qmf31(LEVEL__3__dB), Qmf31(LEVEL__3__dB),
        Qmf31(LEVEL__3__dB), Qmf31(LEVEL__1_5dB),

        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB)
    },
    /*  POP */
    {
        Qmf31(LEVEL__6__dB),

        Qmf31(LEVEL__3__dB), Qmf31(LEVEL__3__dB),

        Qmf31(LEVEL__1_5dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),

        Qmf31(LEVEL__3__dB), Qmf31(LEVEL__3__dB),
        Qmf31(LEVEL__3__dB), Qmf31(LEVEL__3__dB),
        Qmf31(LEVEL__3__dB), Qmf31(LEVEL__3__dB),
        Qmf31(LEVEL__3__dB), Qmf31(LEVEL__3__dB),

        Qmf31(LEVEL__9__dB), Qmf31(LEVEL__9__dB),
        Qmf31(LEVEL__9__dB), Qmf31(LEVEL__9__dB),
        Qmf31(LEVEL__9__dB), Qmf31(LEVEL__9__dB),
        Qmf31(LEVEL__9__dB), Qmf31(LEVEL__9__dB),
        Qmf31(LEVEL__9__dB), Qmf31(LEVEL__9__dB),
        Qmf31(LEVEL__9__dB), Qmf31(LEVEL__9__dB),
        Qmf31(LEVEL__9__dB), Qmf31(LEVEL__9__dB),
        Qmf31(LEVEL__9__dB), Qmf31(LEVEL__9__dB),
        Qmf31(LEVEL__9__dB)
    },
    /*  JAZZ */
    {
        Qmf31(LEVEL__0__dB),

        Qmf31(LEVEL__6__dB), Qmf31(LEVEL__6__dB),

        Qmf31(LEVEL__9__dB), Qmf31(LEVEL__9__dB),
        Qmf31(LEVEL__9__dB), Qmf31(LEVEL__9__dB),

        Qmf31(LEVEL__3__dB), Qmf31(LEVEL__3__dB),
        Qmf31(LEVEL__3__dB), Qmf31(LEVEL__3__dB),
        Qmf31(LEVEL__3__dB), Qmf31(LEVEL__3__dB),
        Qmf31(LEVEL__3__dB), Qmf31(LEVEL__1_5dB),

        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB)
    },
    /*  CLASSICAL */
    {
        Qmf31(LEVEL__0__dB),

        Qmf31(LEVEL__9__dB), Qmf31(LEVEL__9__dB),

        Qmf31(LEVEL__9__dB), Qmf31(LEVEL__9__dB),
        Qmf31(LEVEL__9__dB), Qmf31(LEVEL__9__dB),

        Qmf31(LEVEL__3__dB), Qmf31(LEVEL__3__dB),
        Qmf31(LEVEL__3__dB), Qmf31(LEVEL__3__dB),
        Qmf31(LEVEL__3__dB), Qmf31(LEVEL__3__dB),
        Qmf31(LEVEL__3__dB), Qmf31(LEVEL__1_5dB),

        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB)
    },
    /*  TALK */
    {
        Qmf31(LEVEL__9__dB),

        Qmf31(LEVEL__6__dB), Qmf31(LEVEL__6__dB),

        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),

        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__1_5dB),

        Qmf31(LEVEL__3__dB), Qmf31(LEVEL__3__dB),
        Qmf31(LEVEL__3__dB), Qmf31(LEVEL__3__dB),
        Qmf31(LEVEL__3__dB), Qmf31(LEVEL__3__dB),
        Qmf31(LEVEL__3__dB), Qmf31(LEVEL__3__dB),
        Qmf31(LEVEL__3__dB), Qmf31(LEVEL__3__dB),
        Qmf31(LEVEL__3__dB), Qmf31(LEVEL__3__dB),
        Qmf31(LEVEL__3__dB), Qmf31(LEVEL__3__dB),
        Qmf31(LEVEL__3__dB), Qmf31(LEVEL__3__dB),
        Qmf31(LEVEL__3__dB)
    },
    /*  FLAT */
    {
        Qmf31(LEVEL__0__dB),

        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),

        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),

        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),

        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB), Qmf31(LEVEL__0__dB),
        Qmf31(LEVEL__0__dB)
    }
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

void pvmp3_equalizer(int32 *circ_buffer,
                     e_equalization equalizerType,
                     int32 *work_buff)
{

    if (equalizerType == flat)
    {
        for (int32 band = 0; band < FILTERBANK_BANDS; band += 2)
        {

            int32 *pt_work_buff = &work_buff[band];
            int32 *inData = &circ_buffer[544 - (band<<5)];

            int32 i;
            for (i = 0; i < SUBBANDS_NUMBER*FILTERBANK_BANDS; i += FILTERBANK_BANDS << 2)
            {
                int32 temp1 = (pt_work_buff[ i ]);
                int32 temp2 = (pt_work_buff[ i +   FILTERBANK_BANDS ]);
                int32 temp3 = (pt_work_buff[ i + 2*FILTERBANK_BANDS ]);
                int32 temp4 = (pt_work_buff[ i + 3*FILTERBANK_BANDS ]);
                *(inData++) = temp1;
                *(inData++) = temp2;
                *(inData++) = temp3;
                *(inData++) = temp4;
            }

            inData -= SUBBANDS_NUMBER << 1;
            pt_work_buff++;

            for (i = 0; i < SUBBANDS_NUMBER*FILTERBANK_BANDS; i += FILTERBANK_BANDS << 2)
            {
                int32 temp1 = (pt_work_buff[ i ]);
                int32 temp2 = (pt_work_buff[ i +   FILTERBANK_BANDS ]);
                int32 temp3 = (pt_work_buff[ i + 2*FILTERBANK_BANDS ]);
                int32 temp4 = (pt_work_buff[ i + 3*FILTERBANK_BANDS ]);
                *(inData++) = temp1;
                *(inData++) = temp2;
                *(inData++) = temp3;
                *(inData++) = temp4;
            }
        }
    }
    else
    {
        const int32 *pt_equalizer = equalizerTbl[equalizerType&7];


        for (int32 band = 0; band < FILTERBANK_BANDS; band += 3)
        {
            int32 *inData = &circ_buffer[544 - (band<<5)];

            int32 *pt_work_buff = &work_buff[band];
            int32 i;

            for (i = 0; i < SUBBANDS_NUMBER*FILTERBANK_BANDS; i += FILTERBANK_BANDS << 2)
            {
                int32 temp1 = (pt_work_buff[ i ]);
                int32 temp2 = (pt_work_buff[ i +   FILTERBANK_BANDS ]);
                int32 temp3 = (pt_work_buff[ i + 2*FILTERBANK_BANDS ]);
                int32 temp4 = (pt_work_buff[ i + 3*FILTERBANK_BANDS ]);
                *(inData++) = fxp_mul32_Q32(temp1 << 1, *(pt_equalizer++));
                *(inData++) = fxp_mul32_Q32(temp2 << 1, *(pt_equalizer++));
                *(inData++) = fxp_mul32_Q32(temp3 << 1, *(pt_equalizer++));
                *(inData++) = fxp_mul32_Q32(temp4 << 1, *(pt_equalizer++));
            }

            pt_equalizer -= SUBBANDS_NUMBER;

            inData -= SUBBANDS_NUMBER << 1;
            pt_work_buff++;

            for (i = 0; i < SUBBANDS_NUMBER*FILTERBANK_BANDS; i += FILTERBANK_BANDS << 2)
            {
                int32 temp1 = (pt_work_buff[ i ]);
                int32 temp2 = (pt_work_buff[ i +   FILTERBANK_BANDS ]);
                int32 temp3 = (pt_work_buff[ i + 2*FILTERBANK_BANDS ]);
                int32 temp4 = (pt_work_buff[ i + 3*FILTERBANK_BANDS ]);
                *(inData++) = fxp_mul32_Q32(temp1 << 1, *(pt_equalizer++));
                *(inData++) = fxp_mul32_Q32(temp2 << 1, *(pt_equalizer++));
                *(inData++) = fxp_mul32_Q32(temp3 << 1, *(pt_equalizer++));
                *(inData++) = fxp_mul32_Q32(temp4 << 1, *(pt_equalizer++));
            }
            pt_equalizer -= SUBBANDS_NUMBER;

        }
    }
}




