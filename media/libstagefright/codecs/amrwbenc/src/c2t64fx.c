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

/************************************************************************
*      File: c2t64fx.c                                                  *
*                                                                       *
*	   Description:Performs algebraic codebook search for 6.60kbits mode*
*                                                                       *
*************************************************************************/

#include "typedef.h"
#include "basic_op.h"
#include "math_op.h"
#include "acelp.h"
#include "cnst.h"

#define NB_TRACK  2
#define STEP      2
#define NB_POS    32
#define MSIZE     1024

/*************************************************************************
* Function:  ACELP_2t64_fx()                                             *
*                                                                        *
* 12 bits algebraic codebook.                                            *
* 2 tracks x 32 positions per track = 64 samples.                        *
*                                                                        *
* 12 bits --> 2 pulses in a frame of 64 samples.                         *
*                                                                        *
* All pulses can have two (2) possible amplitudes: +1 or -1.             *
* Each pulse can have 32 possible positions.                             *
**************************************************************************/

void ACELP_2t64_fx(
		Word16 dn[],                          /* (i) <12b : correlation between target x[] and H[]      */
		Word16 cn[],                          /* (i) <12b : residual after long term prediction         */
		Word16 H[],                           /* (i) Q12: impulse response of weighted synthesis filter */
		Word16 code[],                        /* (o) Q9 : algebraic (fixed) codebook excitation         */
		Word16 y[],                           /* (o) Q9 : filtered fixed codebook excitation            */
		Word16 * index                        /* (o) : index (12): 5+1+5+1 = 11 bits.                   */
		)
{
	Word32 i, j, k, i0, i1, ix, iy, pos, pos2;
	Word16 ps, psk, ps1, ps2, alpk, alp1, alp2, sq;
	Word16 alp, val, exp, k_cn, k_dn;
	Word16 *p0, *p1, *p2, *psign;
	Word16 *h, *h_inv, *ptr_h1, *ptr_h2, *ptr_hf;

	Word16 sign[L_SUBFR], vec[L_SUBFR], dn2[L_SUBFR];
	Word16 h_buf[4 * L_SUBFR] = {0};
	Word16 rrixix[NB_TRACK][NB_POS];
	Word16 rrixiy[MSIZE];
	Word32 s, cor;

	/*----------------------------------------------------------------*
	 * Find sign for each pulse position.                             *
	 *----------------------------------------------------------------*/
	alp = 8192;                              /* alp = 2.0 (Q12) */

	/* calculate energy for normalization of cn[] and dn[] */
	/* set k_cn = 32..32767 (ener_cn = 2^30..256-0) */
#ifdef ASM_OPT             /* asm optimization branch */
	s = Dot_product12_asm(cn, cn, L_SUBFR, &exp);
#else
	s = Dot_product12(cn, cn, L_SUBFR, &exp);
#endif

	Isqrt_n(&s, &exp);
	s = L_shl(s, add1(exp, 5));
	k_cn = vo_round(s);

	/* set k_dn = 32..512 (ener_dn = 2^30..2^22) */
#ifdef ASM_OPT                  /* asm optimization branch */
	s = Dot_product12_asm(dn, dn, L_SUBFR, &exp);
#else
	s = Dot_product12(dn, dn, L_SUBFR, &exp);
#endif

	Isqrt_n(&s, &exp);
	k_dn = vo_round(L_shl(s, (exp + 8)));    /* k_dn = 256..4096 */
	k_dn = vo_mult_r(alp, k_dn);              /* alp in Q12 */

	/* mix normalized cn[] and dn[] */
	p0 = cn;
	p1 = dn;
	p2 = dn2;

	for (i = 0; i < L_SUBFR/4; i++)
	{
		s = (k_cn* (*p0++))+(k_dn * (*p1++));
		*p2++ = s >> 7;
		s = (k_cn* (*p0++))+(k_dn * (*p1++));
		*p2++ = s >> 7;
		s = (k_cn* (*p0++))+(k_dn * (*p1++));
		*p2++ = s >> 7;
		s = (k_cn* (*p0++))+(k_dn * (*p1++));
		*p2++ = s >> 7;
	}

	/* set sign according to dn2[] = k_cn*cn[] + k_dn*dn[]    */
	for (i = 0; i < L_SUBFR; i ++)
	{
		val = dn[i];
		ps = dn2[i];
		if (ps >= 0)
		{
			sign[i] = 32767;             /* sign = +1 (Q12) */
			vec[i] = -32768;
		} else
		{
			sign[i] = -32768;            /* sign = -1 (Q12) */
			vec[i] = 32767;
			dn[i] = -val;
		}
	}
	/*------------------------------------------------------------*
	 * Compute h_inv[i].                                          *
	 *------------------------------------------------------------*/
	/* impulse response buffer for fast computation */
	h = h_buf + L_SUBFR;
	h_inv = h + (L_SUBFR<<1);

	for (i = 0; i < L_SUBFR; i++)
	{
		h[i] = H[i];
		h_inv[i] = vo_negate(h[i]);
	}

	/*------------------------------------------------------------*
	 * Compute rrixix[][] needed for the codebook search.         *
	 * Result is multiplied by 0.5                                *
	 *------------------------------------------------------------*/
	/* Init pointers to last position of rrixix[] */
	p0 = &rrixix[0][NB_POS - 1];
	p1 = &rrixix[1][NB_POS - 1];

	ptr_h1 = h;
	cor = 0x00010000L;                          /* for rounding */
	for (i = 0; i < NB_POS; i++)
	{
		cor += ((*ptr_h1) * (*ptr_h1) << 1);
		ptr_h1++;
		*p1-- = (extract_h(cor) >> 1);
		cor += ((*ptr_h1) * (*ptr_h1) << 1);
		ptr_h1++;
		*p0-- = (extract_h(cor) >> 1);
	}

	/*------------------------------------------------------------*
	 * Compute rrixiy[][] needed for the codebook search.         *
	 *------------------------------------------------------------*/
	pos = MSIZE - 1;
	pos2 = MSIZE - 2;
	ptr_hf = h + 1;

	for (k = 0; k < NB_POS; k++)
	{
		p1 = &rrixiy[pos];
		p0 = &rrixiy[pos2];
		cor = 0x00008000L;                        /* for rounding */
		ptr_h1 = h;
		ptr_h2 = ptr_hf;

		for (i = (k + 1); i < NB_POS; i++)
		{
			cor += ((*ptr_h1) * (*ptr_h2))<<1;
			ptr_h1++;
			ptr_h2++;
			*p1 = extract_h(cor);
			cor += ((*ptr_h1) * (*ptr_h2))<<1;
			ptr_h1++;
			ptr_h2++;
			*p0 = extract_h(cor);

			p1 -= (NB_POS + 1);
			p0 -= (NB_POS + 1);
		}
		cor += ((*ptr_h1) * (*ptr_h2))<<1;
		ptr_h1++;
		ptr_h2++;
		*p1 = extract_h(cor);

		pos -= NB_POS;
		pos2--;
		ptr_hf += STEP;
	}

	/*------------------------------------------------------------*
	 * Modification of rrixiy[][] to take signs into account.     *
	 *------------------------------------------------------------*/
	p0 = rrixiy;
	for (i = 0; i < L_SUBFR; i += STEP)
	{
		psign = sign;
		if (psign[i] < 0)
		{
			psign = vec;
		}
		for (j = 1; j < L_SUBFR; j += STEP)
		{
			*p0 = vo_mult(*p0, psign[j]);
			p0++;
		}
	}
	/*-------------------------------------------------------------------*
	 * search 2 pulses:                                                  *
	 * ~@~~~~~~~~~~~~~~                                                  *
	 * 32 pos x 32 pos = 1024 tests (all combinaisons is tested)         *
	 *-------------------------------------------------------------------*/
	p0 = rrixix[0];
	p1 = rrixix[1];
	p2 = rrixiy;

	psk = -1;
	alpk = 1;
	ix = 0;
	iy = 1;

	for (i0 = 0; i0 < L_SUBFR; i0 += STEP)
	{
		ps1 = dn[i0];
		alp1 = (*p0++);
		pos = -1;
		for (i1 = 1; i1 < L_SUBFR; i1 += STEP)
		{
			ps2 = add1(ps1, dn[i1]);
			alp2 = add1(alp1, add1(*p1++, *p2++));
			sq = vo_mult(ps2, ps2);
			s = vo_L_mult(alpk, sq) - ((psk * alp2)<<1);
			if (s > 0)
			{
				psk = sq;
				alpk = alp2;
				pos = i1;
			}
		}
		p1 -= NB_POS;
		if (pos >= 0)
		{
			ix = i0;
			iy = pos;
		}
	}
	/*-------------------------------------------------------------------*
	 * Build the codeword, the filtered codeword and index of codevector.*
	 *-------------------------------------------------------------------*/

	for (i = 0; i < L_SUBFR; i++)
	{
		code[i] = 0;
	}

	i0 = (ix >> 1);                       /* pos of pulse 1 (0..31) */
	i1 = (iy >> 1);                       /* pos of pulse 2 (0..31) */
	if (sign[ix] > 0)
	{
		code[ix] = 512;                     /* codeword in Q9 format */
		p0 = h - ix;
	} else
	{
		code[ix] = -512;
		i0 += NB_POS;
		p0 = h_inv - ix;
	}
	if (sign[iy] > 0)
	{
		code[iy] = 512;
		p1 = h - iy;
	} else
	{
		code[iy] = -512;
		i1 += NB_POS;
		p1 = h_inv - iy;
	}
	*index = add1((i0 << 6), i1);
	for (i = 0; i < L_SUBFR; i++)
	{
		y[i] = vo_shr_r(add1((*p0++), (*p1++)), 3);
	}
	return;
}



