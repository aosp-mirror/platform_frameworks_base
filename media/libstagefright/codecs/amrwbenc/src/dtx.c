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

/***********************************************************************
*       File: dtx.c                                                    *
*                                                                      *
*	    Description:DTX functions                                  *
*                                                                      *
************************************************************************/

#include <stdio.h>
#include <stdlib.h>
#include "typedef.h"
#include "basic_op.h"
#include "oper_32b.h"
#include "math_op.h"
#include "cnst.h"
#include "acelp.h"                         /* prototype of functions    */
#include "bits.h"
#include "dtx.h"
#include "log2.h"
#include "mem_align.h"

static void aver_isf_history(
		Word16 isf_old[],
		Word16 indices[],
		Word32 isf_aver[]
		);

static void find_frame_indices(
		Word16 isf_old_tx[],
		Word16 indices[],
		dtx_encState * st
		);

static Word16 dithering_control(
		dtx_encState * st
		);

/* excitation energy adjustment depending on speech coder mode used, Q7 */
static Word16 en_adjust[9] =
{
	230,                                   /* mode0 = 7k  :  -5.4dB  */
	179,                                   /* mode1 = 9k  :  -4.2dB  */
	141,                                   /* mode2 = 12k :  -3.3dB  */
	128,                                   /* mode3 = 14k :  -3.0dB  */
	122,                                   /* mode4 = 16k :  -2.85dB */
	115,                                   /* mode5 = 18k :  -2.7dB  */
	115,                                   /* mode6 = 20k :  -2.7dB  */
	115,                                   /* mode7 = 23k :  -2.7dB  */
	115                                    /* mode8 = 24k :  -2.7dB  */
};

/**************************************************************************
*
* Function    : dtx_enc_init
*
**************************************************************************/
Word16 dtx_enc_init(dtx_encState ** st, Word16 isf_init[], VO_MEM_OPERATOR *pMemOP)
{
	dtx_encState *s;

	if (st == (dtx_encState **) NULL)
	{
		fprintf(stderr, "dtx_enc_init: invalid parameter\n");
		return -1;
	}
	*st = NULL;

	/* allocate memory */
	if ((s = (dtx_encState *)mem_malloc(pMemOP, sizeof(dtx_encState), 32, VO_INDEX_ENC_AMRWB)) == NULL)
	{
		fprintf(stderr, "dtx_enc_init: can not malloc state structure\n");
		return -1;
	}
	dtx_enc_reset(s, isf_init);
	*st = s;
	return 0;
}

/**************************************************************************
*
* Function    : dtx_enc_reset
*
**************************************************************************/
Word16 dtx_enc_reset(dtx_encState * st, Word16 isf_init[])
{
	Word32 i;

	if (st == (dtx_encState *) NULL)
	{
		fprintf(stderr, "dtx_enc_reset: invalid parameter\n");
		return -1;
	}
	st->hist_ptr = 0;
	st->log_en_index = 0;

	/* Init isf_hist[] */
	for (i = 0; i < DTX_HIST_SIZE; i++)
	{
		Copy(isf_init, &st->isf_hist[i * M], M);
	}
	st->cng_seed = RANDOM_INITSEED;

	/* Reset energy history */
	Set_zero(st->log_en_hist, DTX_HIST_SIZE);

	st->dtxHangoverCount = DTX_HANG_CONST;
	st->decAnaElapsedCount = 32767;

	for (i = 0; i < 28; i++)
	{
		st->D[i] = 0;
	}

	for (i = 0; i < DTX_HIST_SIZE - 1; i++)
	{
		st->sumD[i] = 0;
	}

	return 1;
}

/**************************************************************************
*
* Function    : dtx_enc_exit
*
**************************************************************************/
void dtx_enc_exit(dtx_encState ** st, VO_MEM_OPERATOR *pMemOP)
{
	if (st == NULL || *st == NULL)
		return;
	/* deallocate memory */
	mem_free(pMemOP, *st, VO_INDEX_ENC_AMRWB);
	*st = NULL;
	return;
}


/**************************************************************************
*
* Function    : dtx_enc
*
**************************************************************************/
Word16 dtx_enc(
		dtx_encState * st,                    /* i/o : State struct                                         */
		Word16 isf[M],                        /* o   : CN ISF vector                                        */
		Word16 * exc2,                        /* o   : CN excitation                                        */
		Word16 ** prms
	      )
{
	Word32 i, j;
	Word16 indice[7];
	Word16 log_en, gain, level, exp, exp0, tmp;
	Word16 log_en_int_e, log_en_int_m;
	Word32 L_isf[M], ener32, level32;
	Word16 isf_order[3];
	Word16 CN_dith;

	/* VOX mode computation of SID parameters */
	log_en = 0;
	for (i = 0; i < M; i++)
	{
		L_isf[i] = 0;
	}
	/* average energy and isf */
	for (i = 0; i < DTX_HIST_SIZE; i++)
	{
		/* Division by DTX_HIST_SIZE = 8 has been done in dtx_buffer. log_en is in Q10 */
		log_en = add(log_en, st->log_en_hist[i]);

	}
	find_frame_indices(st->isf_hist, isf_order, st);
	aver_isf_history(st->isf_hist, isf_order, L_isf);

	for (j = 0; j < M; j++)
	{
		isf[j] = (Word16)(L_isf[j] >> 3);  /* divide by 8 */
	}

	/* quantize logarithmic energy to 6 bits (-6 : 66 dB) which corresponds to -2:22 in log2(E).  */
	/* st->log_en_index = (short)( (log_en + 2.0) * 2.625 ); */

	/* increase dynamics to 7 bits (Q8) */
	log_en = (log_en >> 2);

	/* Add 2 in Q8 = 512 to get log2(E) between 0:24 */
	log_en = add(log_en, 512);

	/* Multiply by 2.625 to get full 6 bit range. 2.625 = 21504 in Q13. The result is in Q6 */
	log_en = mult(log_en, 21504);

	/* Quantize Energy */
	st->log_en_index = shr(log_en, 6);

	if(st->log_en_index > 63)
	{
		st->log_en_index = 63;
	}
	if (st->log_en_index < 0)
	{
		st->log_en_index = 0;
	}
	/* Quantize ISFs */
	Qisf_ns(isf, isf, indice);


	Parm_serial(indice[0], 6, prms);
	Parm_serial(indice[1], 6, prms);
	Parm_serial(indice[2], 6, prms);
	Parm_serial(indice[3], 5, prms);
	Parm_serial(indice[4], 5, prms);

	Parm_serial((st->log_en_index), 6, prms);

	CN_dith = dithering_control(st);
	Parm_serial(CN_dith, 1, prms);

	/* level = (float)( pow( 2.0f, (float)st->log_en_index / 2.625 - 2.0 ) );    */
	/* log2(E) in Q9 (log2(E) lies in between -2:22) */
	log_en = shl(st->log_en_index, 15 - 6);

	/* Divide by 2.625; log_en will be between 0:24  */
	log_en = mult(log_en, 12483);
	/* the result corresponds to log2(gain) in Q10 */

	/* Find integer part  */
	log_en_int_e = (log_en >> 10);

	/* Find fractional part */
	log_en_int_m = (Word16) (log_en & 0x3ff);
	log_en_int_m = shl(log_en_int_m, 5);

	/* Subtract 2 from log_en in Q9, i.e divide the gain by 2 (energy by 4) */
	/* Add 16 in order to have the result of pow2 in Q16 */
	log_en_int_e = add(log_en_int_e, 16 - 1);

	level32 = Pow2(log_en_int_e, log_en_int_m); /* Q16 */
	exp0 = norm_l(level32);
	level32 = (level32 << exp0);        /* level in Q31 */
	exp0 = (15 - exp0);
	level = extract_h(level32);            /* level in Q15 */

	/* generate white noise vector */
	for (i = 0; i < L_FRAME; i++)
	{
		exc2[i] = (Random(&(st->cng_seed)) >> 4);
	}

	/* gain = level / sqrt(ener) * sqrt(L_FRAME) */

	/* energy of generated excitation */
	ener32 = Dot_product12(exc2, exc2, L_FRAME, &exp);

	Isqrt_n(&ener32, &exp);

	gain = extract_h(ener32);

	gain = mult(level, gain);              /* gain in Q15 */

	exp = add(exp0, exp);

	/* Multiply by sqrt(L_FRAME)=16, i.e. shift left by 4 */
	exp += 4;

	for (i = 0; i < L_FRAME; i++)
	{
		tmp = mult(exc2[i], gain);         /* Q0 * Q15 */
		exc2[i] = shl(tmp, exp);
	}

	return 0;
}

/**************************************************************************
*
* Function    : dtx_buffer Purpose     : handles the DTX buffer
*
**************************************************************************/
Word16 dtx_buffer(
		dtx_encState * st,                    /* i/o : State struct                    */
		Word16 isf_new[],                     /* i   : isf vector                      */
		Word32 enr,                           /* i   : residual energy (in L_FRAME)    */
		Word16 codec_mode
		)
{
	Word16 log_en;

	Word16 log_en_e;
	Word16 log_en_m;
	st->hist_ptr = add(st->hist_ptr, 1);
	if(st->hist_ptr == DTX_HIST_SIZE)
	{
		st->hist_ptr = 0;
	}
	/* copy lsp vector into buffer */
	Copy(isf_new, &st->isf_hist[st->hist_ptr * M], M);

	/* log_en = (float)log10(enr*0.0059322)/(float)log10(2.0f);  */
	Log2(enr, &log_en_e, &log_en_m);

	/* convert exponent and mantissa to Word16 Q7. Q7 is used to simplify averaging in dtx_enc */
	log_en = shl(log_en_e, 7);             /* Q7 */
	log_en = add(log_en, shr(log_en_m, 15 - 7));

	/* Find energy per sample by multiplying with 0.0059322, i.e subtract log2(1/0.0059322) = 7.39722 The
	 * constant 0.0059322 takes into account windowings and analysis length from autocorrelation
	 * computations; 7.39722 in Q7 = 947  */
	/* Subtract 3 dB = 0.99658 in log2(E) = 127 in Q7. */
	/* log_en = sub( log_en, 947 + en_adjust[codec_mode] ); */

	/* Find energy per sample (divide by L_FRAME=256), i.e subtract log2(256) = 8.0  (1024 in Q7) */
	/* Subtract 3 dB = 0.99658 in log2(E) = 127 in Q7. */

	log_en = sub(log_en, add(1024, en_adjust[codec_mode]));

	/* Insert into the buffer */
	st->log_en_hist[st->hist_ptr] = log_en;
	return 0;
}

/**************************************************************************
*
* Function    : tx_dtx_handler Purpose     : adds extra speech hangover
*                                            to analyze speech on
*                                            the decoding side.
**************************************************************************/
void tx_dtx_handler(dtx_encState * st,     /* i/o : State struct           */
		Word16 vad_flag,                      /* i   : vad decision           */
		Word16 * usedMode                     /* i/o : mode changed or not    */
		)
{

	/* this state machine is in synch with the GSMEFR txDtx machine      */
	st->decAnaElapsedCount = add(st->decAnaElapsedCount, 1);

	if (vad_flag != 0)
	{
		st->dtxHangoverCount = DTX_HANG_CONST;
	} else
	{                                      /* non-speech */
		if (st->dtxHangoverCount == 0)
		{                                  /* out of decoder analysis hangover  */
			st->decAnaElapsedCount = 0;
			*usedMode = MRDTX;
		} else
		{                                  /* in possible analysis hangover */
			st->dtxHangoverCount = sub(st->dtxHangoverCount, 1);

			/* decAnaElapsedCount + dtxHangoverCount < DTX_ELAPSED_FRAMES_THRESH */
			if (sub(add(st->decAnaElapsedCount, st->dtxHangoverCount),
						DTX_ELAPSED_FRAMES_THRESH) < 0)
			{
				*usedMode = MRDTX;
				/* if short time since decoder update, do not add extra HO */
			}
			/* else override VAD and stay in speech mode *usedMode and add extra hangover */
		}
	}

	return;
}



static void aver_isf_history(
		Word16 isf_old[],
		Word16 indices[],
		Word32 isf_aver[]
		)
{
	Word32 i, j, k;
	Word16 isf_tmp[2 * M];
	Word32 L_tmp;

	/* Memorize in isf_tmp[][] the ISF vectors to be replaced by */
	/* the median ISF vector prior to the averaging               */
	for (k = 0; k < 2; k++)
	{
		if ((indices[k] + 1) != 0)
		{
			for (i = 0; i < M; i++)
			{
				isf_tmp[k * M + i] = isf_old[indices[k] * M + i];
				isf_old[indices[k] * M + i] = isf_old[indices[2] * M + i];
			}
		}
	}

	/* Perform the ISF averaging */
	for (j = 0; j < M; j++)
	{
		L_tmp = 0;

		for (i = 0; i < DTX_HIST_SIZE; i++)
		{
			L_tmp = L_add(L_tmp, L_deposit_l(isf_old[i * M + j]));
		}
		isf_aver[j] = L_tmp;
	}

	/* Retrieve from isf_tmp[][] the ISF vectors saved prior to averaging */
	for (k = 0; k < 2; k++)
	{
		if ((indices[k] + 1) != 0)
		{
			for (i = 0; i < M; i++)
			{
				isf_old[indices[k] * M + i] = isf_tmp[k * M + i];
			}
		}
	}

	return;
}

static void find_frame_indices(
		Word16 isf_old_tx[],
		Word16 indices[],
		dtx_encState * st
		)
{
	Word32 L_tmp, summin, summax, summax2nd;
	Word16 i, j, tmp;
	Word16 ptr;

	/* Remove the effect of the oldest frame from the column */
	/* sum sumD[0..DTX_HIST_SIZE-1]. sumD[DTX_HIST_SIZE] is    */
	/* not updated since it will be removed later.           */

	tmp = DTX_HIST_SIZE_MIN_ONE;
	j = -1;
	for (i = 0; i < DTX_HIST_SIZE_MIN_ONE; i++)
	{
		j = add(j, tmp);
		st->sumD[i] = L_sub(st->sumD[i], st->D[j]);
		tmp = sub(tmp, 1);
	}

	/* Shift the column sum sumD. The element sumD[DTX_HIST_SIZE-1]    */
	/* corresponding to the oldest frame is removed. The sum of     */
	/* the distances between the latest isf and other isfs, */
	/* i.e. the element sumD[0], will be computed during this call. */
	/* Hence this element is initialized to zero.                   */

	for (i = DTX_HIST_SIZE_MIN_ONE; i > 0; i--)
	{
		st->sumD[i] = st->sumD[i - 1];
	}
	st->sumD[0] = 0;

	/* Remove the oldest frame from the distance matrix.           */
	/* Note that the distance matrix is replaced by a one-         */
	/* dimensional array to save static memory.                    */

	tmp = 0;
	for (i = 27; i >= 12; i = (Word16) (i - tmp))
	{
		tmp = add(tmp, 1);
		for (j = tmp; j > 0; j--)
		{
			st->D[i - j + 1] = st->D[i - j - tmp];
		}
	}

	/* Compute the first column of the distance matrix D            */
	/* (squared Euclidean distances from isf1[] to isf_old_tx[][]). */

	ptr = st->hist_ptr;
	for (i = 1; i < DTX_HIST_SIZE; i++)
	{
		/* Compute the distance between the latest isf and the other isfs. */
		ptr = sub(ptr, 1);
		if (ptr < 0)
		{
			ptr = DTX_HIST_SIZE_MIN_ONE;
		}
		L_tmp = 0;
		for (j = 0; j < M; j++)
		{
			tmp = sub(isf_old_tx[st->hist_ptr * M + j], isf_old_tx[ptr * M + j]);
			L_tmp = L_mac(L_tmp, tmp, tmp);
		}
		st->D[i - 1] = L_tmp;

		/* Update also the column sums. */
		st->sumD[0] = L_add(st->sumD[0], st->D[i - 1]);
		st->sumD[i] = L_add(st->sumD[i], st->D[i - 1]);
	}

	/* Find the minimum and maximum distances */
	summax = st->sumD[0];
	summin = st->sumD[0];
	indices[0] = 0;
	indices[2] = 0;
	for (i = 1; i < DTX_HIST_SIZE; i++)
	{
		if (L_sub(st->sumD[i], summax) > 0)
		{
			indices[0] = i;
			summax = st->sumD[i];
		}
		if (L_sub(st->sumD[i], summin) < 0)
		{
			indices[2] = i;
			summin = st->sumD[i];
		}
	}

	/* Find the second largest distance */
	summax2nd = -2147483647L;
	indices[1] = -1;
	for (i = 0; i < DTX_HIST_SIZE; i++)
	{
		if ((L_sub(st->sumD[i], summax2nd) > 0) && (sub(i, indices[0]) != 0))
		{
			indices[1] = i;
			summax2nd = st->sumD[i];
		}
	}

	for (i = 0; i < 3; i++)
	{
		indices[i] = sub(st->hist_ptr, indices[i]);
		if (indices[i] < 0)
		{
			indices[i] = add(indices[i], DTX_HIST_SIZE);
		}
	}

	/* If maximum distance/MED_THRESH is smaller than minimum distance */
	/* then the median ISF vector replacement is not performed         */
	tmp = norm_l(summax);
	summax = (summax << tmp);
	summin = (summin << tmp);
	L_tmp = L_mult(voround(summax), INV_MED_THRESH);
	if(L_tmp <= summin)
	{
		indices[0] = -1;
	}
	/* If second largest distance/MED_THRESH is smaller than     */
	/* minimum distance then the median ISF vector replacement is    */
	/* not performed                                                 */
	summax2nd = L_shl(summax2nd, tmp);
	L_tmp = L_mult(voround(summax2nd), INV_MED_THRESH);
	if(L_tmp <= summin)
	{
		indices[1] = -1;
	}
	return;
}

static Word16 dithering_control(
		dtx_encState * st
		)
{
	Word16 tmp, mean, CN_dith, gain_diff;
	Word32 i, ISF_diff;

	/* determine how stationary the spectrum of background noise is */
	ISF_diff = 0;
	for (i = 0; i < 8; i++)
	{
		ISF_diff = L_add(ISF_diff, st->sumD[i]);
	}
	if ((ISF_diff >> 26) > 0)
	{
		CN_dith = 1;
	} else
	{
		CN_dith = 0;
	}

	/* determine how stationary the energy of background noise is */
	mean = 0;
	for (i = 0; i < DTX_HIST_SIZE; i++)
	{
		mean = add(mean, st->log_en_hist[i]);
	}
	mean = (mean >> 3);
	gain_diff = 0;
	for (i = 0; i < DTX_HIST_SIZE; i++)
	{
		tmp = abs_s(sub(st->log_en_hist[i], mean));
		gain_diff = add(gain_diff, tmp);
	}
	if (gain_diff > GAIN_THR)
	{
		CN_dith = 1;
	}
	return CN_dith;
}
