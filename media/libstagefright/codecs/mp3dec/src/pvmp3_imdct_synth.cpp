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

   Filename: pvmp3_imdct_synth.cpp

     Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY


 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Input
    int32 in[],         Pointer to spec values of current channel
    int32 overlap[],    Pointer to overlap values of current channel
    uint32 blk_type,    Block type
    int16 mx_band,      In case of mixed blocks, # of bands with long
                        blocks (2 or 4) else 0
    int32 *Scratch_mem
  Returns

    int32 in[],

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    The frequency lines are preprocessed by the "alias reduction" scheme
    and fed into the IMDCT matrix, each 18 into one transform block.
    The first half of the output values are added to the stored overlap
    values from the last block. These values are new output values and
    are input values for the polyphase filterbank. The second half of the
    output values is stored for overlap with the next data granule.
    The number of windowed samples is 12 for short blocks, and 36 for long
    blocks

Windowing

    Depending on window_switching_flag[gr][ch], block_type[gr][ch] and
    mixed_block_flag[gr][ch] different shapes of windows are used.
        normal window
        start window
        stop window
        short windows
            Each of the three short blocks is windowed separately.
            The windowed short blocks must be overlapped and concatenated.

Overlapping and adding with previous block

    The first half (18 values) of the current block (36 values) has to be
    overlapped with the second half of the previous block. The second half
    of the current block has to be stored for overlapping with the next block

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


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#include "pvmp3_imdct_synth.h"
#include "pv_mp3dec_fxd_op.h"
#include "pvmp3_dec_defs.h"
#include "pvmp3_mdct_18.h"
#include "pvmp3_mdct_6.h"
#include "mp3_mem_funcs.h"



/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/
#define LONG        0
#define START       1
#define SHORT       2
#define STOP        3

/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/
/*
 *   sin(pi/36*(k+0.5)),k=0..35
 */

const int32 normal_win[36] =
{
    Qfmt_31(0.08723877473068f),   Qfmt_31(0.26105238444010f),   Qfmt_31(0.43287922787620f),
    Qfmt_31(0.60141159900854f),   Qfmt_31(0.76536686473018f),   Qfmt_31(0.92349722647006f),
    Qfmt_31(0.53729960834682f),   Qfmt_31(0.60876142900872f),   Qfmt_31(0.67559020761566f),
    Qfmt_31(-0.73727733681012f),   Qfmt_31(-0.79335334029124f),   Qfmt_31(0.84339144581289f),
    Qfmt_31(0.88701083317822f),   Qfmt_31(0.92387953251129f),   Qfmt_31(-0.95371695074823f),
    Qfmt_31(-0.97629600711993f),   Qfmt_31(-0.99144486137381f),   Qfmt_31(-0.99904822158186f),
    Qfmt_31(0.99904822158186f),   Qfmt_31(0.99144486137381f),   Qfmt_31(0.97629600711993f),
    Qfmt_31(0.95371695074823f),   Qfmt_31(0.92387953251129f),   Qfmt_31(0.88701083317822f),
    Qfmt_31(0.84339144581289f),   Qfmt_31(0.79335334029124f),   Qfmt_31(0.73727733681012f),
    Qfmt_31(0.67559020761566f),   Qfmt_31(0.60876142900872f),   Qfmt_31(0.53729960834682f),
    Qfmt_31(0.46174861323503f),   Qfmt_31(0.38268343236509f),   Qfmt_31(0.30070579950427f),
    Qfmt_31(0.21643961393810f),   Qfmt_31(0.13052619222005f),   Qfmt_31(0.04361938736534f)
};


const int32 start_win[36] =
{
    /*   k=0..17  sin(pi/36*(k+0.5)),  */
    Qfmt_31(0.08723877473068f),   Qfmt_31(0.26105238444010f),   Qfmt_31(0.43287922787620f),
    Qfmt_31(0.60141159900854f),   Qfmt_31(0.76536686473018f),   Qfmt_31(0.92349722647006f),
    Qfmt_31(0.53729960834682f),   Qfmt_31(0.60876142900872f),   Qfmt_31(0.67559020761566f),
    Qfmt_31(-0.73727733681012f),   Qfmt_31(-0.79335334029124f),   Qfmt_31(0.84339144581289f),
    Qfmt_31(0.88701083317822f),   Qfmt_31(0.92387953251129f),   Qfmt_31(-0.95371695074823f),
    Qfmt_31(-0.97629600711993f),   Qfmt_31(-0.99144486137381f),   Qfmt_31(-0.99904822158186f),

    Qfmt_31(0.99999990000000f),   Qfmt_31(0.99999990000000f),   Qfmt_31(0.99999990000000f),
    Qfmt_31(0.99999990000000f),   Qfmt_31(0.99999990000000f),   Qfmt_31(0.99999990000000f),
    /*    k=24..29; sin(pi/12*(k-18+0.5)) */
    Qfmt_31(0.99144486137381f),   Qfmt_31(0.92387953251129f),   Qfmt_31(0.79335334029124f),
    Qfmt_31(0.60876142900872f),   Qfmt_31(0.38268343236509f),   Qfmt_31(0.13052619222005f),

    Qfmt_31(0.00000000000000f),   Qfmt_31(0.00000000000000f),   Qfmt_31(0.00000000000000f),
    Qfmt_31(0.00000000000000f),   Qfmt_31(0.00000000000000f),   Qfmt_31(0.00000000000000f)
};


const int32 stop_win[36] =
{
    Qfmt_31(0.00000000000000f),   Qfmt_31(0.00000000000000f),   Qfmt_31(0.00000000000000f),
    Qfmt_31(0.00000000000000f),   Qfmt_31(0.00000000000000f),   Qfmt_31(0.00000000000000f),
    /*    k=6..11; sin(pi/12*(k-6+0.5)) */
    Qfmt_31(0.13052619222005f),   Qfmt_31(0.38268343236509f),   Qfmt_31(0.60876142900872f),
    Qfmt_31(-0.79335334029124f),   Qfmt_31(-0.92387953251129f),   Qfmt_31(0.99144486137381f),

    Qfmt_31(0.99999990000000f),   Qfmt_31(0.99999990000000f),   Qfmt_31(-0.99999990000000f),
    Qfmt_31(-0.99999990000000f),   Qfmt_31(-0.99999990000000f),   Qfmt_31(-0.99999990000000f),
    /*   k=18..35  sin(pi/36*(k+0.5)),  */
    Qfmt_31(0.99904822158186f),   Qfmt_31(0.99144486137381f),   Qfmt_31(0.97629600711993f),
    Qfmt_31(0.95371695074823f),   Qfmt_31(0.92387953251129f),   Qfmt_31(0.88701083317822f),
    Qfmt_31(0.84339144581289f),   Qfmt_31(0.79335334029124f),   Qfmt_31(0.73727733681012f),
    Qfmt_31(0.67559020761566f),   Qfmt_31(0.60876142900872f),   Qfmt_31(0.53729960834682f),
    Qfmt_31(0.46174861323503f),   Qfmt_31(0.38268343236509f),   Qfmt_31(0.30070579950427f),
    Qfmt_31(0.21643961393810f),   Qfmt_31(0.13052619222005f),   Qfmt_31(0.04361938736534f)
};


const int32 short_win[12] =
{
    /*    k=0..11; sin(pi/12*(k+0.5)) */
    Qfmt_31(0.13052619222005f),   Qfmt_31(0.38268343236509f),   Qfmt_31(0.60876142900872f),
    Qfmt_31(0.79335334029124f),   Qfmt_31(0.92387953251129f),   Qfmt_31(0.99144486137381f),
    Qfmt_31(0.99144486137381f),   Qfmt_31(0.92387953251129f),   Qfmt_31(0.79335334029124f),
    Qfmt_31(0.60876142900872f),   Qfmt_31(0.38268343236509f),   Qfmt_31(0.13052619222005f),
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

void pvmp3_imdct_synth(int32  in[SUBBANDS_NUMBER*FILTERBANK_BANDS],
                       int32  overlap[SUBBANDS_NUMBER*FILTERBANK_BANDS],
                       uint32 blk_type,
                       int16  mx_band,
                       int32  used_freq_lines,
                       int32  *Scratch_mem)
{

    int32 band;
    int32 bands2process = used_freq_lines + 2;

    if (bands2process > SUBBANDS_NUMBER)
    {
        bands2process = SUBBANDS_NUMBER;  /* default */
    }


    /*
     *  in case of mx_poly_band> 0, do
     *  long transforms
     */


    for (band = 0; band < bands2process; band++)
    {
        uint32 current_blk_type = (band < mx_band) ? LONG : blk_type;

        int32 * out     = in      + (band * FILTERBANK_BANDS);
        int32 * history = overlap + (band * FILTERBANK_BANDS);

        switch (current_blk_type)
        {
            case LONG:

                pvmp3_mdct_18(out, history, normal_win);

                break;

            case START:

                pvmp3_mdct_18(out, history, start_win);

                break;

            case STOP:

                pvmp3_mdct_18(out, history, stop_win);

                break;

            case SHORT:
            {
                int32 *tmp_prev_ovr = &Scratch_mem[FILTERBANK_BANDS];
                int32 i;

                for (i = 0; i < 6; i++)
                {
                    Scratch_mem[i    ] = out[(i*3)];
                    Scratch_mem[6  +i] = out[(i*3) + 1];
                    Scratch_mem[12 +i] = out[(i*3) + 2];
                }

                pvmp3_mdct_6(&Scratch_mem[ 0], &tmp_prev_ovr[ 0]);
                pvmp3_mdct_6(&Scratch_mem[ 6], &tmp_prev_ovr[ 6]);
                pvmp3_mdct_6(&Scratch_mem[12], &tmp_prev_ovr[12]);

                for (i = 0; i < 6; i++)
                {
                    int32 temp  =  history[i];
                    /* next iteration overlap */
                    history[i]  =  fxp_mul32_Q32(tmp_prev_ovr[ 6+i] << 1, short_win[6+i]);
                    history[i] +=  fxp_mul32_Q32(Scratch_mem[12+i] << 1, short_win[  i]);
                    out[i]  =  temp;
                }

                for (i = 0; i < 6; i++)
                {
                    out[i+6]   =  fxp_mul32_Q32(Scratch_mem[i] << 1, short_win[i]);
                    out[i+6]  +=  history[i+6];
                    /* next iteration overlap */
                    history[i+6]  =  fxp_mul32_Q32(tmp_prev_ovr[12+i] << 1, short_win[6+i]);

                }
                for (i = 0; i < 6; i++)
                {
                    out[i+12]  =  fxp_mul32_Q32(tmp_prev_ovr[  i] << 1, short_win[6+i]);
                    out[i+12] +=  fxp_mul32_Q32(Scratch_mem[6+i] << 1, short_win[  i]);
                    out[i+12] +=  history[i+12];
                    history[12+i]  =  0;
                }
            }

            break;
        }

        /*
         *     Compensation for frequency inversion of polyphase filterbank
         *     every odd time sample of every odd odd subband is mulitplied by -1  before
         *     processing by the polyphase filter
         */

        if (band & 1)
        {
            for (int32 slot = 1; slot < FILTERBANK_BANDS; slot += 6)
            {
                int32 temp1 = out[slot  ];
                int32 temp2 = out[slot+2];
                int32 temp3 = out[slot+4];
                out[slot  ] = -temp1;
                out[slot+2] = -temp2;
                out[slot+4] = -temp3;
            }
        }
    }


    for (band = bands2process; band < SUBBANDS_NUMBER; band++)
    {
        int32 * out     = in      + (band * FILTERBANK_BANDS);
        int32 * history = overlap + (band * FILTERBANK_BANDS);
        int32 slot;

        if (band & 1)
        {
            for (slot = 0; slot < FILTERBANK_BANDS; slot += 6)
            {
                int32 temp1 =  history[slot  ];
                int32 temp2 =  history[slot+1];
                int32 temp3 =  history[slot+2];
                out[slot  ] =  temp1;
                out[slot+1] = -temp2;
                out[slot+2] =  temp3;

                temp1 =  history[slot+3];
                temp2 =  history[slot+4];
                temp3 =  history[slot+5];
                out[slot+3] = -temp1;
                out[slot+4] =  temp2;
                out[slot+5] = -temp3;
            }
        }
        else
        {
            for (slot = 0; slot < FILTERBANK_BANDS; slot += 3)
            {
                int32 temp1 =  history[slot  ];
                int32 temp2 =  history[slot+1];
                int32 temp3 =  history[slot+2];
                out[slot  ] =  temp1;
                out[slot+1] =  temp2;
                out[slot+2] =  temp3;
            }
        }

        pv_memset(history, 0, FILTERBANK_BANDS*sizeof(*overlap));
    }
}




