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
 *                         ACELP.H                                          *
 *--------------------------------------------------------------------------*
 *       Function			 			             *
 *--------------------------------------------------------------------------*/
#ifndef __ACELP_H__
#define __ACELP_H__

#include "typedef.h"
#include "cod_main.h"

/*-----------------------------------------------------------------*
 *                        LPC prototypes                           *
 *-----------------------------------------------------------------*/

Word16 median5(Word16 x[]);

void Autocorr(
		Word16 x[],                           /* (i)    : Input signal                      */
		Word16 m,                             /* (i)    : LPC order                         */
		Word16 r_h[],                         /* (o)    : Autocorrelations  (msb)           */
		Word16 r_l[]                          /* (o)    : Autocorrelations  (lsb)           */
	     );

void Lag_window(
		Word16 r_h[],                         /* (i/o)   : Autocorrelations  (msb)          */
		Word16 r_l[]                          /* (i/o)   : Autocorrelations  (lsb)          */
	       );

void Init_Levinson(
		Word16 * mem                          /* output  :static memory (18 words) */
		);

void Levinson(
		Word16 Rh[],                          /* (i)     : Rh[M+1] Vector of autocorrelations (msb) */
		Word16 Rl[],                          /* (i)     : Rl[M+1] Vector of autocorrelations (lsb) */
		Word16 A[],                           /* (o) Q12 : A[M]    LPC coefficients  (m = 16)       */
		Word16 rc[],                          /* (o) Q15 : rc[M]   Reflection coefficients.         */
		Word16 * mem                          /* (i/o)   :static memory (18 words)                  */
	     );

void Az_isp(
		Word16 a[],                           /* (i) Q12 : predictor coefficients                 */
		Word16 isp[],                         /* (o) Q15 : Immittance spectral pairs              */
		Word16 old_isp[]                      /* (i)     : old isp[] (in case not found M roots)  */
	   );

void Isp_Az(
		Word16 isp[],                         /* (i) Q15 : Immittance spectral pairs            */
		Word16 a[],                           /* (o) Q12 : predictor coefficients (order = M)   */
		Word16 m,
		Word16 adaptive_scaling               /* (i) 0   : adaptive scaling disabled */
		/*     1   : adaptive scaling enabled  */
	   );

void Isp_isf(
		Word16 isp[],                         /* (i) Q15 : isp[m] (range: -1<=val<1)                */
		Word16 isf[],                         /* (o) Q15 : isf[m] normalized (range: 0.0<=val<=0.5) */
		Word16 m                              /* (i)     : LPC order                                */
	    );

void Isf_isp(
		Word16 isf[],                         /* (i) Q15 : isf[m] normalized (range: 0.0<=val<=0.5) */
		Word16 isp[],                         /* (o) Q15 : isp[m] (range: -1<=val<1)                */
		Word16 m                              /* (i)     : LPC order                                */
	    );

void Int_isp(
		Word16 isp_old[],                     /* input : isps from past frame              */
		Word16 isp_new[],                     /* input : isps from present frame           */
		Word16 frac[],                        /* input : fraction for 3 first subfr (Q15)  */
		Word16 Az[]                           /* output: LP coefficients in 4 subframes    */
	    );

void Weight_a(
		Word16 a[],                           /* (i) Q12 : a[m+1]  LPC coefficients             */
		Word16 ap[],                          /* (o) Q12 : Spectral expanded LPC coefficients   */
		Word16 gamma,                         /* (i) Q15 : Spectral expansion factor.           */
		Word16 m                              /* (i)     : LPC order.                           */
	     );


/*-----------------------------------------------------------------*
 *                        isf quantizers                           *
 *-----------------------------------------------------------------*/

void Qpisf_2s_46b(
		Word16 * isf1,                        /* (i) Q15 : ISF in the frequency domain (0..0.5) */
		Word16 * isf_q,                       /* (o) Q15 : quantized ISF               (0..0.5) */
		Word16 * past_isfq,                   /* (io)Q15 : past ISF quantizer                   */
		Word16 * indice,                      /* (o)     : quantization indices                 */
		Word16 nb_surv                        /* (i)     : number of survivor (1, 2, 3 or 4)    */
		);

void Qpisf_2s_36b(
		Word16 * isf1,                        /* (i) Q15 : ISF in the frequency domain (0..0.5) */
		Word16 * isf_q,                       /* (o) Q15 : quantized ISF               (0..0.5) */
		Word16 * past_isfq,                   /* (io)Q15 : past ISF quantizer                   */
		Word16 * indice,                      /* (o)     : quantization indices                 */
		Word16 nb_surv                        /* (i)     : number of survivor (1, 2, 3 or 4)    */
		);

void Dpisf_2s_46b(
		Word16 * indice,                      /* input:  quantization indices                       */
		Word16 * isf_q,                       /* output: quantized ISF in frequency domain (0..0.5) */
		Word16 * past_isfq,                   /* i/0   : past ISF quantizer                    */
		Word16 * isfold,                      /* input : past quantized ISF                    */
		Word16 * isf_buf,                     /* input : isf buffer                                                        */
		Word16 bfi,                           /* input : Bad frame indicator                   */
		Word16 enc_dec
		);

void Dpisf_2s_36b(
		Word16 * indice,                      /* input:  quantization indices                       */
		Word16 * isf_q,                       /* output: quantized ISF in frequency domain (0..0.5) */
		Word16 * past_isfq,                   /* i/0   : past ISF quantizer                    */
		Word16 * isfold,                      /* input : past quantized ISF                    */
		Word16 * isf_buf,                     /* input : isf buffer                                                        */
		Word16 bfi,                           /* input : Bad frame indicator                   */
		Word16 enc_dec
		);

void Qisf_ns(
		Word16 * isf1,                        /* input : ISF in the frequency domain (0..0.5) */
		Word16 * isf_q,                       /* output: quantized ISF                        */
		Word16 * indice                       /* output: quantization indices                 */
	    );

void Disf_ns(
		Word16 * indice,                      /* input:  quantization indices                  */
		Word16 * isf_q                        /* input : ISF in the frequency domain (0..0.5)  */
	    );

Word16 Sub_VQ(                             /* output: return quantization index     */
		Word16 * x,                           /* input : ISF residual vector           */
		Word16 * dico,                        /* input : quantization codebook         */
		Word16 dim,                           /* input : dimention of vector           */
		Word16 dico_size,                     /* input : size of quantization codebook */
		Word32 * distance                     /* output: error of quantization         */
	     );

void Reorder_isf(
		Word16 * isf,                         /* (i/o) Q15: ISF in the frequency domain (0..0.5) */
		Word16 min_dist,                      /* (i) Q15  : minimum distance to keep             */
		Word16 n                              /* (i)      : number of ISF                        */
		);

/*-----------------------------------------------------------------*
 *                       filter prototypes                         *
 *-----------------------------------------------------------------*/

void Init_Decim_12k8(
		Word16 mem[]                          /* output: memory (2*NB_COEF_DOWN) set to zeros */
		);
void Decim_12k8(
		Word16 sig16k[],                      /* input:  signal to downsampling  */
		Word16 lg,                            /* input:  length of input         */
		Word16 sig12k8[],                     /* output: decimated signal        */
		Word16 mem[]                          /* in/out: memory (2*NB_COEF_DOWN) */
	       );

void Init_HP50_12k8(Word16 mem[]);
void HP50_12k8(
		Word16 signal[],                      /* input/output signal */
		Word16 lg,                            /* lenght of signal    */
		Word16 mem[]                          /* filter memory [6]   */
	      );
void Init_HP400_12k8(Word16 mem[]);
void HP400_12k8(
		Word16 signal[],                      /* input/output signal */
		Word16 lg,                            /* lenght of signal    */
		Word16 mem[]                          /* filter memory [6]   */
	       );

void Init_Filt_6k_7k(Word16 mem[]);
void Filt_6k_7k(
		Word16 signal[],                      /* input:  signal                  */
		Word16 lg,                            /* input:  length of input         */
		Word16 mem[]                          /* in/out: memory (size=30)        */
	       );
void Filt_6k_7k_asm(
		Word16 signal[],                      /* input:  signal                  */
		Word16 lg,                            /* input:  length of input         */
		Word16 mem[]                          /* in/out: memory (size=30)        */
	       );

void LP_Decim2(
		Word16 x[],                           /* in/out: signal to process         */
		Word16 l,                             /* input : size of filtering         */
		Word16 mem[]                          /* in/out: memory (size=3)           */
	      );

void Preemph(
		Word16 x[],                           /* (i/o)   : input signal overwritten by the output */
		Word16 mu,                            /* (i) Q15 : preemphasis coefficient                */
		Word16 lg,                            /* (i)     : lenght of filtering                    */
		Word16 * mem                          /* (i/o)   : memory (x[-1])                         */
	    );
void Preemph2(
		Word16 x[],                           /* (i/o)   : input signal overwritten by the output */
		Word16 mu,                            /* (i) Q15 : preemphasis coefficient                */
		Word16 lg,                            /* (i)     : lenght of filtering                    */
		Word16 * mem                          /* (i/o)   : memory (x[-1])                         */
	     );
void Deemph(
		Word16 x[],                           /* (i/o)   : input signal overwritten by the output */
		Word16 mu,                            /* (i) Q15 : deemphasis factor                      */
		Word16 L,                             /* (i)     : vector size                            */
		Word16 * mem                          /* (i/o)   : memory (y[-1])                         */
	   );
void Deemph2(
		Word16 x[],                           /* (i/o)   : input signal overwritten by the output */
		Word16 mu,                            /* (i) Q15 : deemphasis factor                      */
		Word16 L,                             /* (i)     : vector size                            */
		Word16 * mem                          /* (i/o)   : memory (y[-1])                         */
	    );
void Deemph_32(
		Word16 x_hi[],                        /* (i)     : input signal (bit31..16) */
		Word16 x_lo[],                        /* (i)     : input signal (bit15..4)  */
		Word16 y[],                           /* (o)     : output signal (x16)      */
		Word16 mu,                            /* (i) Q15 : deemphasis factor        */
		Word16 L,                             /* (i)     : vector size              */
		Word16 * mem                          /* (i/o)   : memory (y[-1])           */
	      );

void Deemph_32_asm(
		Word16 x_hi[],                        /* (i)     : input signal (bit31..16) */
		Word16 x_lo[],                        /* (i)     : input signal (bit15..4)  */
		Word16 y[],                           /* (o)     : output signal (x16)      */
		Word16 * mem                          /* (i/o)   : memory (y[-1])           */
	      );

void Convolve(
		Word16 x[],                           /* (i)     : input vector                              */
		Word16 h[],                           /* (i) Q15    : impulse response                       */
		Word16 y[],                           /* (o) 12 bits: output vector                          */
		Word16 L                              /* (i)     : vector size                               */
	     );

void Convolve_asm(
		Word16 x[],                           /* (i)     : input vector                              */
		Word16 h[],                           /* (i) Q15    : impulse response                       */
		Word16 y[],                           /* (o) 12 bits: output vector                          */
		Word16 L                              /* (i)     : vector size                               */
	     );

void Residu(
		Word16 a[],                           /* (i) Q12 : prediction coefficients                     */
		Word16 x[],                           /* (i)     : speech (values x[-m..-1] are needed         */
		Word16 y[],                           /* (o)     : residual signal                             */
		Word16 lg                             /* (i)     : size of filtering                           */
		);

void Residu_opt(
		Word16 a[],                           /* (i) Q12 : prediction coefficients                     */
		Word16 x[],                           /* (i)     : speech (values x[-m..-1] are needed         */
		Word16 y[],                           /* (o)     : residual signal                             */
		Word16 lg                             /* (i)     : size of filtering                           */
		);

void Syn_filt(
	Word16 a[],                           /* (i) Q12 : a[m+1] prediction coefficients           */
	Word16 x[],                           /* (i)     : input signal                             */
	Word16 y[],                           /* (o)     : output signal                            */
	Word16 lg,                            /* (i)     : size of filtering                        */
	Word16 mem[],                         /* (i/o)   : memory associated with this filtering.   */
	Word16 update                         /* (i)     : 0=no update, 1=update of memory.         */
	);

void Syn_filt_asm(
	Word16 a[],                           /* (i) Q12 : a[m+1] prediction coefficients           */
	Word16 x[],                           /* (i)     : input signal                             */
	Word16 y[],                           /* (o)     : output signal                            */
	Word16 mem[]                          /* (i/o)   : memory associated with this filtering.   */
	);

void Syn_filt_32(
	Word16 a[],                           /* (i) Q12 : a[m+1] prediction coefficients */
	Word16 m,                             /* (i)     : order of LP filter             */
	Word16 exc[],                         /* (i) Qnew: excitation (exc[i] >> Qnew)    */
	Word16 Qnew,                          /* (i)     : exc scaling = 0(min) to 8(max) */
	Word16 sig_hi[],                      /* (o) /16 : synthesis high                 */
	Word16 sig_lo[],                      /* (o) /16 : synthesis low                  */
	Word16 lg                             /* (i)     : size of filtering              */
	);

void Syn_filt_32_asm(
	Word16 a[],                           /* (i) Q12 : a[m+1] prediction coefficients */
	Word16 m,                             /* (i)     : order of LP filter             */
	Word16 exc[],                         /* (i) Qnew: excitation (exc[i] >> Qnew)    */
	Word16 Qnew,                          /* (i)     : exc scaling = 0(min) to 8(max) */
	Word16 sig_hi[],                      /* (o) /16 : synthesis high                 */
	Word16 sig_lo[],                      /* (o) /16 : synthesis low                  */
	Word16 lg                             /* (i)     : size of filtering              */
	);
/*-----------------------------------------------------------------*
 *                       pitch prototypes                          *
 *-----------------------------------------------------------------*/

Word16 Pitch_ol(                           /* output: open loop pitch lag                        */
     Word16 signal[],                      /* input : signal used to compute the open loop pitch */
/* signal[-pit_max] to signal[-1] should be known */
     Word16 pit_min,                       /* input : minimum pitch lag                          */
     Word16 pit_max,                       /* input : maximum pitch lag                          */
     Word16 L_frame                        /* input : length of frame to compute pitch           */
);

Word16 Pitch_med_ol(                       /* output: open loop pitch lag                        */
     Word16 wsp[],                         /* input : signal used to compute the open loop pitch */
                                           /* wsp[-pit_max] to wsp[-1] should be known   */
     Coder_State *st,                      /* i/o : global codec structure */
     Word16 L_frame                        /* input : length of frame to compute pitch           */
);

Word16 Med_olag(                           /* output : median of  5 previous open-loop lags       */
     Word16 prev_ol_lag,                   /* input  : previous open-loop lag                     */
     Word16 old_ol_lag[5]
);

void Init_Hp_wsp(Word16 mem[]);
void scale_mem_Hp_wsp(Word16 mem[], Word16 exp);
void Hp_wsp(
     Word16 wsp[],                         /* i   : wsp[]  signal       */
     Word16 hp_wsp[],                      /* o   : hypass wsp[]        */
     Word16 lg,                            /* i   : lenght of signal    */
     Word16 mem[]                          /* i/o : filter memory [9]   */
);

Word16 Pitch_fr4(                          /* (o)     : pitch period.                         */
     Word16 exc[],                         /* (i)     : excitation buffer                     */
     Word16 xn[],                          /* (i)     : target vector                         */
     Word16 h[],                           /* (i) Q15 : impulse response of synth/wgt filters */
     Word16 t0_min,                        /* (i)     : minimum value in the searched range.  */
     Word16 t0_max,                        /* (i)     : maximum value in the searched range.  */
     Word16 * pit_frac,                    /* (o)     : chosen fraction (0, 1, 2 or 3).       */
     Word16 i_subfr,                       /* (i)     : indicator for first subframe.         */
     Word16 t0_fr2,                        /* (i)     : minimum value for resolution 1/2      */
     Word16 t0_fr1,                        /* (i)     : minimum value for resolution 1        */
     Word16 L_subfr                        /* (i)     : Length of subframe                    */
);
void Pred_lt4(
     Word16 exc[],                         /* in/out: excitation buffer */
     Word16 T0,                            /* input : integer pitch lag */
     Word16 frac,                          /* input : fraction of lag   */
     Word16 L_subfr                        /* input : subframe size     */
);

void pred_lt4_asm(
     Word16 exc[],                         /* in/out: excitation buffer */
     Word16 T0,                            /* input : integer pitch lag */
     Word16 frac,                          /* input : fraction of lag   */
     Word16 L_subfr                        /* input : subframe size     */
);

/*-----------------------------------------------------------------*
 *                       gain prototypes                           *
 *-----------------------------------------------------------------*/

Word16 G_pitch(                            /* (o) Q14 : Gain of pitch lag saturated to 1.2   */
     Word16 xn[],                          /* (i)     : Pitch target.                        */
     Word16 y1[],                          /* (i)     : filtered adaptive codebook.          */
     Word16 g_coeff[],                     /* : Correlations need for gain quantization. */
     Word16 L_subfr                        /* : Length of subframe.                  */
);
void Init_Q_gain2(
     Word16 * mem                          /* output  :static memory (2 words)      */
);
Word16 Q_gain2(                            /* Return index of quantization.        */
     Word16 xn[],                          /* (i) Q_xn:Target vector.               */
     Word16 y1[],                          /* (i) Q_xn:Adaptive codebook.           */
     Word16 Q_xn,                          /* (i)     :xn and y1 format             */
     Word16 y2[],                          /* (i) Q9  :Filtered innovative vector.  */
     Word16 code[],                        /* (i) Q9  :Innovative vector.           */
     Word16 g_coeff[],                     /* (i)     :Correlations <xn y1> <y1 y1> */
/* Compute in G_pitch().        */
     Word16 L_subfr,                       /* (i)     :Subframe lenght.             */
     Word16 nbits,                         /* (i)     : number of bits (6 or 7)     */
     Word16 * gain_pit,                    /* (i/o)Q14:Pitch gain.                  */
     Word32 * gain_cod,                    /* (o) Q16 :Code gain.                   */
     Word16 gp_clip,                       /* (i)     : Gp Clipping flag            */
     Word16 * mem                          /* (i/o)   :static memory (2 words)      */
);

void Init_D_gain2(
     Word16 * mem                          /* output  :static memory (4 words)      */
);
void D_gain2(
     Word16 index,                         /* (i)     :index of quantization.       */
     Word16 nbits,                         /* (i)     : number of bits (6 or 7)     */
     Word16 code[],                        /* (i) Q9  :Innovative vector.           */
     Word16 L_subfr,                       /* (i)     :Subframe lenght.             */
     Word16 * gain_pit,                    /* (o) Q14 :Pitch gain.                  */
     Word32 * gain_cod,                    /* (o) Q16 :Code gain.                   */
     Word16 bfi,                           /* (i)     :bad frame indicator          */
     Word16 prev_bfi,                      /* (i) : Previous BF indicator      */
     Word16 state,                         /* (i) : State of BFH               */
     Word16 unusable_frame,                /* (i) : UF indicator            */
     Word16 vad_hist,                      /* (i)         :number of non-speech frames  */
     Word16 * mem                          /* (i/o)   :static memory (4 words)      */
);

/*-----------------------------------------------------------------*
 *                       acelp prototypes                          *
 *-----------------------------------------------------------------*/

void cor_h_x(
     Word16 h[],                           /* (i) Q12 : impulse response of weighted synthesis filter */
     Word16 x[],                           /* (i) Q0  : target vector                                 */
     Word16 dn[]                           /* (o) <12bit : correlation between target and h[]         */
);
void ACELP_2t64_fx(
     Word16 dn[],                          /* (i) <12b : correlation between target x[] and H[]      */
     Word16 cn[],                          /* (i) <12b : residual after long term prediction         */
     Word16 H[],                           /* (i) Q12: impulse response of weighted synthesis filter */
     Word16 code[],                        /* (o) Q9 : algebraic (fixed) codebook excitation         */
     Word16 y[],                           /* (o) Q9 : filtered fixed codebook excitation            */
     Word16 * index                        /* (o) : index (12): 5+1+5+1 = 11 bits.                     */
);

void ACELP_4t64_fx(
     Word16 dn[],                          /* (i) <12b : correlation between target x[] and H[]      */
     Word16 cn[],                          /* (i) <12b : residual after long term prediction         */
     Word16 H[],                           /* (i) Q12: impulse response of weighted synthesis filter */
     Word16 code[],                        /* (o) Q9 : algebraic (fixed) codebook excitation         */
     Word16 y[],                           /* (o) Q9 : filtered fixed codebook excitation            */
     Word16 nbbits,                        /* (i) : 20, 36, 44, 52, 64, 72 or 88 bits                */
     Word16 ser_size,                      /* (i) : bit rate                                         */
     Word16 _index[]                       /* (o) : index (20): 5+5+5+5 = 20 bits.                   */
					   /* (o) : index (36): 9+9+9+9 = 36 bits.                   */
					   /* (o) : index (44): 13+9+13+9 = 44 bits.                 */
					   /* (o) : index (52): 13+13+13+13 = 52 bits.               */
					   /* (o) : index (64): 2+2+2+2+14+14+14+14 = 64 bits.       */
					   /* (o) : index (72): 10+2+10+2+10+14+10+14 = 72 bits.     */
					   /* (o) : index (88): 11+11+11+11+11+11+11+11 = 88 bits.   */
);

void Pit_shrp(
     Word16 * x,                           /* in/out: impulse response (or algebraic code) */
     Word16 pit_lag,                       /* input : pitch lag                            */
     Word16 sharp,                         /* input : pitch sharpening factor (Q15)        */
     Word16 L_subfr                        /* input : subframe size                        */
);


/*-----------------------------------------------------------------*
 *                        others prototypes                        *
 *-----------------------------------------------------------------*/

void Copy(
     Word16 x[],                           /* (i)   : input vector   */
     Word16 y[],                           /* (o)   : output vector  */
     Word16 L                              /* (i)   : vector length  */
);
void Set_zero(
     Word16 x[],                           /* (o)    : vector to clear     */
     Word16 L                              /* (i)    : length of vector    */
);
void Updt_tar(
     Word16 * x,                           /* (i) Q0  : old target (for pitch search)     */
     Word16 * x2,                          /* (o) Q0  : new target (for codebook search)  */
     Word16 * y,                           /* (i) Q0  : filtered adaptive codebook vector */
     Word16 gain,                          /* (i) Q14 : adaptive codebook gain            */
     Word16 L                              /* (i)     : subframe size                     */
);
Word16 voice_factor(                       /* (o) Q15 : factor (-1=unvoiced to 1=voiced) */
     Word16 exc[],                         /* (i) Q_exc: pitch excitation                */
     Word16 Q_exc,                         /* (i)     : exc format                       */
     Word16 gain_pit,                      /* (i) Q14 : gain of pitch                    */
     Word16 code[],                        /* (i) Q9  : Fixed codebook excitation        */
     Word16 gain_code,                     /* (i) Q0  : gain of code                     */
     Word16 L_subfr                        /* (i)     : subframe length                  */
);
void Scale_sig(
     Word16 x[],                           /* (i/o) : signal to scale               */
     Word16 lg,                            /* (i)   : size of x[]                   */
     Word16 exp                            /* (i)   : exponent: x = round(x << exp) */
);

void Scale_sig_opt(
     Word16 x[],                           /* (i/o) : signal to scale               */
     Word16 lg,                            /* (i)   : size of x[]                   */
     Word16 exp                            /* (i)   : exponent: x = round(x << exp) */
);

Word16 Random(Word16 * seed);

void Init_gp_clip(
     Word16 mem[]                          /* (o) : memory of gain of pitch clipping algorithm */
);
Word16 Gp_clip(
     Word16 mem[]                          /* (i/o) : memory of gain of pitch clipping algorithm */
);
void Gp_clip_test_isf(
     Word16 isf[],                         /* (i)   : isf values (in frequency domain)           */
     Word16 mem[]                          /* (i/o) : memory of gain of pitch clipping algorithm */
);
void Gp_clip_test_gain_pit(
     Word16 gain_pit,                      /* (i)   : gain of quantized pitch                    */
     Word16 mem[]                          /* (i/o) : memory of gain of pitch clipping algorithm */
);


#endif   //__ACELP_H__

