/*
 ** Copyright 2003-2010, VisualOn, Inc.
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */


/*--------------------------------------------------------------------------*
 *                         COD_MAIN.H                                       *
 *--------------------------------------------------------------------------*
 *       Static memory in the encoder				            *
 *--------------------------------------------------------------------------*/
#ifndef __COD_MAIN_H__
#define __COD_MAIN_H__

#include "cnst.h"                          /* coder constant parameters */

#include "wb_vad.h"
#include "dtx.h"
#include "stream.h"
#include "voAMRWB.h"

typedef struct
{
    Word16 mem_decim[2 * L_FILT16k];       /* speech decimated filter memory */
    Word16 mem_sig_in[6];                  /* hp50 filter memory */
    Word16 mem_preemph;                    /* speech preemph filter memory */
    Word16 old_speech[L_TOTAL - L_FRAME];  /* old speech vector at 12.8kHz */
    Word16 old_wsp[PIT_MAX / OPL_DECIM];   /* old decimated weighted speech vector */
    Word16 old_exc[PIT_MAX + L_INTERPOL];  /* old excitation vector */
    Word16 mem_levinson[M + 2];            /* levinson routine memory */
    Word16 ispold[M];                      /* old isp (immittance spectral pairs) */
    Word16 ispold_q[M];                    /* quantized old isp */
    Word16 past_isfq[M];                   /* past isf quantizer */
    Word16 mem_wsp;                        /* wsp vector memory */
    Word16 mem_decim2[3];                  /* wsp decimation filter memory */
    Word16 mem_w0;                         /* target vector memory */
    Word16 mem_syn[M];                     /* synthesis memory */
    Word16 tilt_code;                      /* tilt of code */
    Word16 old_wsp_max;                    /* old wsp maximum value */
    Word16 old_wsp_shift;                  /* old wsp shift */
    Word16 Q_old;                          /* old scaling factor */
    Word16 Q_max[2];                       /* old maximum scaling factor */
    Word16 gp_clip[2];                     /* gain of pitch clipping memory */
    Word16 qua_gain[4];                    /* gain quantizer memory */

    Word16 old_T0_med;
    Word16 ol_gain;
    Word16 ada_w;
    Word16 ol_wght_flg;
    Word16 old_ol_lag[5];
    Word16 hp_wsp_mem[9];
    Word16 old_hp_wsp[L_FRAME / OPL_DECIM + (PIT_MAX / OPL_DECIM)];
    VadVars *vadSt;
    dtx_encState *dtx_encSt;
    Word16 first_frame;
    Word16 isfold[M];                      /* old isf (frequency domain) */
    Word32 L_gc_thres;                     /* threshold for noise enhancer */
    Word16 mem_syn_hi[M];                  /* modified synthesis memory (MSB) */
    Word16 mem_syn_lo[M];                  /* modified synthesis memory (LSB) */
    Word16 mem_deemph;                     /* speech deemph filter memory */
    Word16 mem_sig_out[6];                 /* hp50 filter memory for synthesis */
    Word16 mem_hp400[6];                   /* hp400 filter memory for synthesis */
    Word16 mem_oversamp[2 * L_FILT];       /* synthesis oversampled filter memory */
    Word16 mem_syn_hf[M];                  /* HF synthesis memory */
    Word16 mem_hf[2 * L_FILT16k];          /* HF band-pass filter memory */
    Word16 mem_hf2[2 * L_FILT16k];         /* HF band-pass filter memory */
    Word16 seed2;                          /* random memory for HF generation */
    Word16 vad_hist;
    Word16 gain_alpha;
    /*  TX_State structure  */
	Word16 sid_update_counter;
    Word16 sid_handover_debt;
    Word16 prev_ft;
	Word16 allow_dtx;
	/*some input/output buffer parameters */
	unsigned char       *inputStream;
	int			        inputSize;
	VOAMRWBMODE  		mode;
	VOAMRWBFRAMETYPE	frameType;
	unsigned short      *outputStream;
	int			        outputSize;
	FrameStream         *stream;
	VO_MEM_OPERATOR     *pvoMemop;
	VO_MEM_OPERATOR     voMemoprator;
	VO_PTR              hCheck;
} Coder_State;

typedef void* HAMRENC;

#endif  //__COD_MAIN_H__


