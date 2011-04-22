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
*      File: c4t64fx.c                                                 *
*                                                                      *
*	   Description:Performs algebraic codebook search for higher modes *
*                                                                      *
************************************************************************/

/************************************************************************
* Function: ACELP_4t64_fx()                                             *
*                                                                       *
* 20, 36, 44, 52, 64, 72, 88 bits algebraic codebook.                   *
* 4 tracks x 16 positions per track = 64 samples.                       *
*                                                                       *
* 20 bits --> 4 pulses in a frame of 64 samples.                        *
* 36 bits --> 8 pulses in a frame of 64 samples.                        *
* 44 bits --> 10 pulses in a frame of 64 samples.                       *
* 52 bits --> 12 pulses in a frame of 64 samples.                       *
* 64 bits --> 16 pulses in a frame of 64 samples.                       *
* 72 bits --> 18 pulses in a frame of 64 samples.                       *
* 88 bits --> 24 pulses in a frame of 64 samples.                       *
*                                                                       *
* All pulses can have two (2) possible amplitudes: +1 or -1.            *
* Each pulse can have sixteen (16) possible positions.                  *
*************************************************************************/

#include "typedef.h"
#include "basic_op.h"
#include "math_op.h"
#include "acelp.h"
#include "cnst.h"

#include "q_pulse.h"

static Word16 tipos[36] = {
	0, 1, 2, 3,                            /* starting point &ipos[0], 1st iter */
	1, 2, 3, 0,                            /* starting point &ipos[4], 2nd iter */
	2, 3, 0, 1,                            /* starting point &ipos[8], 3rd iter */
	3, 0, 1, 2,                            /* starting point &ipos[12], 4th iter */
	0, 1, 2, 3,
	1, 2, 3, 0,
	2, 3, 0, 1,
	3, 0, 1, 2,
	0, 1, 2, 3};                           /* end point for 24 pulses &ipos[35], 4th iter */

#define NB_PULSE_MAX  24

#define L_SUBFR   64
#define NB_TRACK  4
#define STEP      4
#define NB_POS    16
#define MSIZE     256
#define NB_MAX    8
#define NPMAXPT   ((NB_PULSE_MAX+NB_TRACK-1)/NB_TRACK)

/* Private functions */
void cor_h_vec_012(
		Word16 h[],                           /* (i) scaled impulse response                 */
		Word16 vec[],                         /* (i) scaled vector (/8) to correlate with h[] */
		Word16 track,                         /* (i) track to use                            */
		Word16 sign[],                        /* (i) sign vector                             */
		Word16 rrixix[][NB_POS],              /* (i) correlation of h[x] with h[x]      */
		Word16 cor_1[],                       /* (o) result of correlation (NB_POS elements) */
		Word16 cor_2[]                        /* (o) result of correlation (NB_POS elements) */
		);

void cor_h_vec_012_asm(
		Word16 h[],                           /* (i) scaled impulse response                 */
		Word16 vec[],                         /* (i) scaled vector (/8) to correlate with h[] */
		Word16 track,                         /* (i) track to use                            */
		Word16 sign[],                        /* (i) sign vector                             */
		Word16 rrixix[][NB_POS],              /* (i) correlation of h[x] with h[x]      */
		Word16 cor_1[],                       /* (o) result of correlation (NB_POS elements) */
		Word16 cor_2[]                        /* (o) result of correlation (NB_POS elements) */
		);

void cor_h_vec_30(
		Word16 h[],                           /* (i) scaled impulse response                 */
		Word16 vec[],                         /* (i) scaled vector (/8) to correlate with h[] */
		Word16 track,                         /* (i) track to use                            */
		Word16 sign[],                        /* (i) sign vector                             */
		Word16 rrixix[][NB_POS],              /* (i) correlation of h[x] with h[x]      */
		Word16 cor_1[],                       /* (o) result of correlation (NB_POS elements) */
		Word16 cor_2[]                        /* (o) result of correlation (NB_POS elements) */
		);

void search_ixiy(
		Word16 nb_pos_ix,                     /* (i) nb of pos for pulse 1 (1..8)       */
		Word16 track_x,                       /* (i) track of pulse 1                   */
		Word16 track_y,                       /* (i) track of pulse 2                   */
		Word16 * ps,                          /* (i/o) correlation of all fixed pulses  */
		Word16 * alp,                         /* (i/o) energy of all fixed pulses       */
		Word16 * ix,                          /* (o) position of pulse 1                */
		Word16 * iy,                          /* (o) position of pulse 2                */
		Word16 dn[],                          /* (i) corr. between target and h[]       */
		Word16 dn2[],                         /* (i) vector of selected positions       */
		Word16 cor_x[],                       /* (i) corr. of pulse 1 with fixed pulses */
		Word16 cor_y[],                       /* (i) corr. of pulse 2 with fixed pulses */
		Word16 rrixiy[][MSIZE]                /* (i) corr. of pulse 1 with pulse 2   */
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
		)
{
	Word32 i, j, k;
	Word16 st, ix, iy, pos, index, track, nb_pulse, nbiter, j_temp;
	Word16 psk, ps, alpk, alp, val, k_cn, k_dn, exp;
	Word16 *p0, *p1, *p2, *p3, *psign;
	Word16 *h, *h_inv, *ptr_h1, *ptr_h2, *ptr_hf, h_shift;
	Word32 s, cor, L_tmp, L_index;
	Word16 dn2[L_SUBFR], sign[L_SUBFR], vec[L_SUBFR];
	Word16 ind[NPMAXPT * NB_TRACK];
	Word16 codvec[NB_PULSE_MAX], nbpos[10];
	Word16 cor_x[NB_POS], cor_y[NB_POS], pos_max[NB_TRACK];
	Word16 h_buf[4 * L_SUBFR];
	Word16 rrixix[NB_TRACK][NB_POS], rrixiy[NB_TRACK][MSIZE];
	Word16 ipos[NB_PULSE_MAX];

	switch (nbbits)
	{
		case 20:                               /* 20 bits, 4 pulses, 4 tracks */
			nbiter = 4;                          /* 4x16x16=1024 loop */
			alp = 8192;                          /* alp = 2.0 (Q12) */
			nb_pulse = 4;                      
			nbpos[0] = 4;                      
			nbpos[1] = 8;                      
			break;
		case 36:                               /* 36 bits, 8 pulses, 4 tracks */
			nbiter = 4;                          /* 4x20x16=1280 loop */
			alp = 4096;                          /* alp = 1.0 (Q12) */
			nb_pulse = 8;                      
			nbpos[0] = 4;                      
			nbpos[1] = 8;                      
			nbpos[2] = 8;                      
			break;
		case 44:                               /* 44 bits, 10 pulses, 4 tracks */
			nbiter = 4;                          /* 4x26x16=1664 loop */
			alp = 4096;                          /* alp = 1.0 (Q12) */
			nb_pulse = 10;                     
			nbpos[0] = 4;                      
			nbpos[1] = 6;                      
			nbpos[2] = 8;                      
			nbpos[3] = 8;                      
			break;
		case 52:                               /* 52 bits, 12 pulses, 4 tracks */
			nbiter = 4;                          /* 4x26x16=1664 loop */
			alp = 4096;                          /* alp = 1.0 (Q12) */
			nb_pulse = 12;                     
			nbpos[0] = 4;                      
			nbpos[1] = 6;                      
			nbpos[2] = 8;                      
			nbpos[3] = 8;                      
			break;
		case 64:                               /* 64 bits, 16 pulses, 4 tracks */
			nbiter = 3;                          /* 3x36x16=1728 loop */
			alp = 3277;                          /* alp = 0.8 (Q12) */
			nb_pulse = 16;                     
			nbpos[0] = 4;                      
			nbpos[1] = 4;                      
			nbpos[2] = 6;                      
			nbpos[3] = 6;                      
			nbpos[4] = 8;                      
			nbpos[5] = 8;                      
			break;
		case 72:                               /* 72 bits, 18 pulses, 4 tracks */
			nbiter = 3;                          /* 3x35x16=1680 loop */
			alp = 3072;                          /* alp = 0.75 (Q12) */
			nb_pulse = 18;                     
			nbpos[0] = 2;                      
			nbpos[1] = 3;                      
			nbpos[2] = 4;                      
			nbpos[3] = 5;                      
			nbpos[4] = 6;                      
			nbpos[5] = 7;                      
			nbpos[6] = 8;                      
			break;
		case 88:                               /* 88 bits, 24 pulses, 4 tracks */
			if(ser_size > 462)
				nbiter = 1;
			else
				nbiter = 2;                    /* 2x53x16=1696 loop */

			alp = 2048;                          /* alp = 0.5 (Q12) */
			nb_pulse = 24;                     
			nbpos[0] = 2;                      
			nbpos[1] = 2;                      
			nbpos[2] = 3;                      
			nbpos[3] = 4;                      
			nbpos[4] = 5;                      
			nbpos[5] = 6;                      
			nbpos[6] = 7;                      
			nbpos[7] = 8;                      
			nbpos[8] = 8;                      
			nbpos[9] = 8;                      
			break;
		default:
			nbiter = 0;
			alp = 0;
			nb_pulse = 0;
	}

	for (i = 0; i < nb_pulse; i++)
	{
		codvec[i] = i;                     
	}

	/*----------------------------------------------------------------*
	 * Find sign for each pulse position.                             *
	 *----------------------------------------------------------------*/
	/* calculate energy for normalization of cn[] and dn[] */
	/* set k_cn = 32..32767 (ener_cn = 2^30..256-0) */
#ifdef ASM_OPT                  /* asm optimization branch */
	s = Dot_product12_asm(cn, cn, L_SUBFR, &exp);
#else
	s = Dot_product12(cn, cn, L_SUBFR, &exp);
#endif

	Isqrt_n(&s, &exp);
	s = L_shl(s, (exp + 5)); 
	k_cn = extract_h(L_add(s, 0x8000));

	/* set k_dn = 32..512 (ener_dn = 2^30..2^22) */
#ifdef ASM_OPT                      /* asm optimization branch */
	s = Dot_product12_asm(dn, dn, L_SUBFR, &exp);
#else
	s = Dot_product12(dn, dn, L_SUBFR, &exp);
#endif

	Isqrt_n(&s, &exp);
	k_dn = (L_shl(s, (exp + 5 + 3)) + 0x8000) >> 16;    /* k_dn = 256..4096 */
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
	for(i = 0; i < L_SUBFR; i++)
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
			dn2[i] = -ps;
		}
	}
	/*----------------------------------------------------------------*
	 * Select NB_MAX position per track according to max of dn2[].    *
	 *----------------------------------------------------------------*/
	pos = 0;
	for (i = 0; i < NB_TRACK; i++)
	{
		for (k = 0; k < NB_MAX; k++)
		{
			ps = -1;                       
			for (j = i; j < L_SUBFR; j += STEP)
			{
				if(dn2[j] > ps)
				{
					ps = dn2[j];          
					pos = j;               
				}
			}
			dn2[pos] = (k - NB_MAX);     /* dn2 < 0 when position is selected */
			if (k == 0)
			{
				pos_max[i] = pos;          
			}
		}
	}

	/*--------------------------------------------------------------*
	 * Scale h[] to avoid overflow and to get maximum of precision  *
	 * on correlation.                                              *
	 *                                                              *
	 * Maximum of h[] (h[0]) is fixed to 2048 (MAX16 / 16).         *
	 *  ==> This allow addition of 16 pulses without saturation.    *
	 *                                                              *
	 * Energy worst case (on resonant impulse response),            *
	 * - energy of h[] is approximately MAX/16.                     *
	 * - During search, the energy is divided by 8 to avoid         *
	 *   overflow on "alp". (energy of h[] = MAX/128).              *
	 *  ==> "alp" worst case detected is 22854 on sinusoidal wave.  *
	 *--------------------------------------------------------------*/

	/* impulse response buffer for fast computation */

	h = h_buf;                             
	h_inv = h_buf + (2 * L_SUBFR);   
	L_tmp = 0;
	for (i = 0; i < L_SUBFR; i++)
	{
		*h++ = 0;                          
		*h_inv++ = 0;   
		L_tmp += (H[i] * H[i]) << 1;
	}
	/* scale h[] down (/2) when energy of h[] is high with many pulses used */
	val = extract_h(L_tmp);
	h_shift = 0;                           

	if ((nb_pulse >= 12) && (val > 1024))
	{
		h_shift = 1;                       
	}
	p0 = H;
	p1 = h;
	p2 = h_inv;

	for (i = 0; i < L_SUBFR/4; i++)
	{
		*p1 = *p0++ >> h_shift;         
		*p2++ = -(*p1++);  
		*p1 = *p0++ >> h_shift;         
		*p2++ = -(*p1++); 
		*p1 = *p0++ >> h_shift;         
		*p2++ = -(*p1++); 
		*p1 = *p0++ >> h_shift;         
		*p2++ = -(*p1++); 
	}

	/*------------------------------------------------------------*
	 * Compute rrixix[][] needed for the codebook search.         *
	 * This algorithm compute impulse response energy of all      *
	 * positions (16) in each track (4).       Total = 4x16 = 64. *
	 *------------------------------------------------------------*/

	/* storage order --> i3i3, i2i2, i1i1, i0i0 */

	/* Init pointers to last position of rrixix[] */
	p0 = &rrixix[0][NB_POS - 1];           
	p1 = &rrixix[1][NB_POS - 1];           
	p2 = &rrixix[2][NB_POS - 1];           
	p3 = &rrixix[3][NB_POS - 1];           

	ptr_h1 = h;                            
	cor = 0x00008000L;                             /* for rounding */
	for (i = 0; i < NB_POS; i++)
	{
		cor += vo_L_mult((*ptr_h1), (*ptr_h1));
		ptr_h1++;
		*p3-- = extract_h(cor);            
		cor += vo_L_mult((*ptr_h1), (*ptr_h1));
		ptr_h1++;
		*p2-- = extract_h(cor);            
		cor += vo_L_mult((*ptr_h1), (*ptr_h1));
		ptr_h1++;
		*p1-- = extract_h(cor);            
		cor += vo_L_mult((*ptr_h1), (*ptr_h1));
		ptr_h1++;
		*p0-- = extract_h(cor);            
	}

	/*------------------------------------------------------------*
	 * Compute rrixiy[][] needed for the codebook search.         *
	 * This algorithm compute correlation between 2 pulses        *
	 * (2 impulses responses) in 4 possible adjacents tracks.     *
	 * (track 0-1, 1-2, 2-3 and 3-0).     Total = 4x16x16 = 1024. *
	 *------------------------------------------------------------*/

	/* storage order --> i2i3, i1i2, i0i1, i3i0 */

	pos = MSIZE - 1;                       
	ptr_hf = h + 1;                        

	for (k = 0; k < NB_POS; k++)
	{
		p3 = &rrixiy[2][pos];              
		p2 = &rrixiy[1][pos];              
		p1 = &rrixiy[0][pos];              
		p0 = &rrixiy[3][pos - NB_POS];     

		cor = 0x00008000L;                   /* for rounding */
		ptr_h1 = h;                        
		ptr_h2 = ptr_hf;                   

		for (i = k + 1; i < NB_POS; i++)
		{
			cor += vo_L_mult((*ptr_h1), (*ptr_h2));
			ptr_h1++;
			ptr_h2++;
			*p3 = extract_h(cor);          
			cor += vo_L_mult((*ptr_h1), (*ptr_h2));
			ptr_h1++;
			ptr_h2++;
			*p2 = extract_h(cor);          
			cor += vo_L_mult((*ptr_h1), (*ptr_h2));
			ptr_h1++;
			ptr_h2++;
			*p1 = extract_h(cor);          
			cor += vo_L_mult((*ptr_h1), (*ptr_h2));
			ptr_h1++;
			ptr_h2++;
			*p0 = extract_h(cor);         

			p3 -= (NB_POS + 1);
			p2 -= (NB_POS + 1);
			p1 -= (NB_POS + 1);
			p0 -= (NB_POS + 1);
		}
		cor += vo_L_mult((*ptr_h1), (*ptr_h2));
		ptr_h1++;
		ptr_h2++;
		*p3 = extract_h(cor);              
		cor += vo_L_mult((*ptr_h1), (*ptr_h2));
		ptr_h1++;
		ptr_h2++;
		*p2 = extract_h(cor);              
		cor += vo_L_mult((*ptr_h1), (*ptr_h2));
		ptr_h1++;
		ptr_h2++;
		*p1 = extract_h(cor);              

		pos -= NB_POS;
		ptr_hf += STEP;
	}

	/* storage order --> i3i0, i2i3, i1i2, i0i1 */

	pos = MSIZE - 1;                       
	ptr_hf = h + 3;                        

	for (k = 0; k < NB_POS; k++)
	{
		p3 = &rrixiy[3][pos];              
		p2 = &rrixiy[2][pos - 1];          
		p1 = &rrixiy[1][pos - 1];          
		p0 = &rrixiy[0][pos - 1];          

		cor = 0x00008000L;								/* for rounding */
		ptr_h1 = h;                        
		ptr_h2 = ptr_hf;                   

		for (i = k + 1; i < NB_POS; i++)
		{
			cor += vo_L_mult((*ptr_h1), (*ptr_h2));
			ptr_h1++;
			ptr_h2++;
			*p3 = extract_h(cor);          
			cor += vo_L_mult((*ptr_h1), (*ptr_h2));
			ptr_h1++;
			ptr_h2++;
			*p2 = extract_h(cor);          
			cor += vo_L_mult((*ptr_h1), (*ptr_h2));
			ptr_h1++;
			ptr_h2++;
			*p1 = extract_h(cor);          
			cor += vo_L_mult((*ptr_h1), (*ptr_h2));
			ptr_h1++;
			ptr_h2++;
			*p0 = extract_h(cor);          

			p3 -= (NB_POS + 1);
			p2 -= (NB_POS + 1);
			p1 -= (NB_POS + 1);
			p0 -= (NB_POS + 1);
		}
		cor += vo_L_mult((*ptr_h1), (*ptr_h2));
		ptr_h1++;
		ptr_h2++;
		*p3 = extract_h(cor);              

		pos--;
		ptr_hf += STEP;
	}

	/*------------------------------------------------------------*
	 * Modification of rrixiy[][] to take signs into account.     *
	 *------------------------------------------------------------*/

	p0 = &rrixiy[0][0];                    

	for (k = 0; k < NB_TRACK; k++)
	{
		j_temp = (k + 1)&0x03;
		for (i = k; i < L_SUBFR; i += STEP)
		{
			psign = sign;                  
			if (psign[i] < 0)
			{
				psign = vec;               
			}
			j = j_temp;
			for (; j < L_SUBFR; j += STEP)
			{
				*p0 = vo_mult(*p0, psign[j]);    
				p0++;
			}
		}
	}

	/*-------------------------------------------------------------------*
	 *                       Deep first search                           *
	 *-------------------------------------------------------------------*/

	psk = -1;                              
	alpk = 1;                              

	for (k = 0; k < nbiter; k++)
	{
		j_temp = k<<2;
		for (i = 0; i < nb_pulse; i++)
			ipos[i] = tipos[j_temp + i];

		if(nbbits == 20)
		{
			pos = 0;                       
			ps = 0;                        
			alp = 0;                       
			for (i = 0; i < L_SUBFR; i++)
			{
				vec[i] = 0;                
			}
		} else if ((nbbits == 36) || (nbbits == 44))
		{
			/* first stage: fix 2 pulses */
			pos = 2;

			ix = ind[0] = pos_max[ipos[0]];
			iy = ind[1] = pos_max[ipos[1]];
			ps = dn[ix] + dn[iy];
			i = ix >> 2;                /* ix / STEP */
			j = iy >> 2;                /* iy / STEP */
			s = rrixix[ipos[0]][i] << 13;
			s += rrixix[ipos[1]][j] << 13;
			i = (i << 4) + j;         /* (ix/STEP)*NB_POS + (iy/STEP) */
			s += rrixiy[ipos[0]][i] << 14;
			alp = (s + 0x8000) >> 16;
			if (sign[ix] < 0)
				p0 = h_inv - ix;
			else
				p0 = h - ix;
			if (sign[iy] < 0)
				p1 = h_inv - iy;
			else
				p1 = h - iy;

			for (i = 0; i < L_SUBFR; i++)
			{
				vec[i] = (*p0++) + (*p1++);
			}

			if(nbbits == 44)
			{
				ipos[8] = 0;               
				ipos[9] = 1;               
			}
		} else
		{
			/* first stage: fix 4 pulses */
			pos = 4;

			ix = ind[0] = pos_max[ipos[0]];  
			iy = ind[1] = pos_max[ipos[1]];  
			i = ind[2] = pos_max[ipos[2]];   
			j = ind[3] = pos_max[ipos[3]];   
			ps = add1(add1(add1(dn[ix], dn[iy]), dn[i]), dn[j]);

			if (sign[ix] < 0)
				p0 = h_inv - ix;
			else
				p0 = h - ix;

			if (sign[iy] < 0)
				p1 = h_inv - iy;
			else
				p1 = h - iy;

			if (sign[i] < 0)
				p2 = h_inv - i;
			else
				p2 = h - i;

			if (sign[j] < 0)
				p3 = h_inv - j;
			else
				p3 = h - j;

			L_tmp = 0L;
			for(i = 0; i < L_SUBFR; i++)
			{
				vec[i]  = add1(add1(add1(*p0++, *p1++), *p2++), *p3++);
				L_tmp  += (vec[i] * vec[i]) << 1;
			}

			alp = ((L_tmp >> 3) + 0x8000) >> 16;

			if(nbbits == 72)
			{
				ipos[16] = 0;              
				ipos[17] = 1;              
			}
		}

		/* other stages of 2 pulses */

		for (j = pos, st = 0; j < nb_pulse; j += 2, st++)
		{
			/*--------------------------------------------------*
			 * Calculate correlation of all possible positions  *
			 * of the next 2 pulses with previous fixed pulses. *
			 * Each pulse can have 16 possible positions.       *
			 *--------------------------------------------------*/
			if(ipos[j] == 3)
			{
				cor_h_vec_30(h, vec, ipos[j], sign, rrixix, cor_x, cor_y);
			}
			else
			{
#ifdef ASM_OPT                 /* asm optimization branch */
				cor_h_vec_012_asm(h, vec, ipos[j], sign, rrixix, cor_x, cor_y);
#else
				cor_h_vec_012(h, vec, ipos[j], sign, rrixix, cor_x, cor_y);
#endif
			}
			/*--------------------------------------------------*
			 * Find best positions of 2 pulses.                 *
			 *--------------------------------------------------*/
			search_ixiy(nbpos[st], ipos[j], ipos[j + 1], &ps, &alp,
					&ix, &iy, dn, dn2, cor_x, cor_y, rrixiy);

			ind[j] = ix;                   
			ind[j + 1] = iy;               

			if (sign[ix] < 0)
				p0 = h_inv - ix;
			else
				p0 = h - ix;
			if (sign[iy] < 0)
				p1 = h_inv - iy;
			else
				p1 = h - iy;

			for (i = 0; i < L_SUBFR; i+=4)
			{
				vec[i]   += add1((*p0++), (*p1++));       
				vec[i+1] += add1((*p0++), (*p1++));        
				vec[i+2] += add1((*p0++), (*p1++));        
				vec[i+3] += add1((*p0++), (*p1++));      
			}
		}
		/* memorise the best codevector */
		ps = vo_mult(ps, ps);
		s = vo_L_msu(vo_L_mult(alpk, ps), psk, alp);
		if (s > 0)
		{
			psk = ps;                      
			alpk = alp;                    
			for (i = 0; i < nb_pulse; i++)
			{
				codvec[i] = ind[i];        
			}
			for (i = 0; i < L_SUBFR; i++)
			{
				y[i] = vec[i];             
			}
		}
	}
	/*-------------------------------------------------------------------*
	 * Build the codeword, the filtered codeword and index of codevector.*
	 *-------------------------------------------------------------------*/
	for (i = 0; i < NPMAXPT * NB_TRACK; i++)
	{
		ind[i] = -1;                       
	}
	for (i = 0; i < L_SUBFR; i++)
	{
		code[i] = 0;                       
		y[i] = vo_shr_r(y[i], 3);               /* Q12 to Q9 */
	}
	val = (512 >> h_shift);               /* codeword in Q9 format */
	for (k = 0; k < nb_pulse; k++)
	{
		i = codvec[k];                       /* read pulse position */
		j = sign[i];                         /* read sign           */
		index = i >> 2;                 /* index = pos of pulse (0..15) */
		track = (Word16) (i & 0x03);         /* track = i % NB_TRACK (0..3)  */

		if (j > 0)
		{
			code[i] += val;   
			codvec[k] += 128;  
		} else
		{
			code[i] -= val;   
			index += NB_POS;    
		}

		i = (Word16)((vo_L_mult(track, NPMAXPT) >> 1));

		while (ind[i] >= 0)
		{
			i += 1;
		}
		ind[i] = index;                    
	}

	k = 0;                                 
	/* Build index of codevector */
	if(nbbits == 20)
	{
		for (track = 0; track < NB_TRACK; track++)
		{
			_index[track] = (Word16)(quant_1p_N1(ind[k], 4));
			k += NPMAXPT;
		}
	} else if(nbbits == 36)
	{
		for (track = 0; track < NB_TRACK; track++)
		{
			_index[track] = (Word16)(quant_2p_2N1(ind[k], ind[k + 1], 4));
			k += NPMAXPT;
		}
	} else if(nbbits == 44)
	{
		for (track = 0; track < NB_TRACK - 2; track++)
		{
			_index[track] = (Word16)(quant_3p_3N1(ind[k], ind[k + 1], ind[k + 2], 4));
			k += NPMAXPT;
		}
		for (track = 2; track < NB_TRACK; track++)
		{
			_index[track] = (Word16)(quant_2p_2N1(ind[k], ind[k + 1], 4));
			k += NPMAXPT;
		}
	} else if(nbbits == 52)
	{
		for (track = 0; track < NB_TRACK; track++)
		{
			_index[track] = (Word16)(quant_3p_3N1(ind[k], ind[k + 1], ind[k + 2], 4));
			k += NPMAXPT;
		}
	} else if(nbbits == 64)
	{
		for (track = 0; track < NB_TRACK; track++)
		{
			L_index = quant_4p_4N(&ind[k], 4);
			_index[track] = (Word16)((L_index >> 14) & 3);
			_index[track + NB_TRACK] = (Word16)(L_index & 0x3FFF);
			k += NPMAXPT;
		}
	} else if(nbbits == 72)
	{
		for (track = 0; track < NB_TRACK - 2; track++)
		{
			L_index = quant_5p_5N(&ind[k], 4);
			_index[track] = (Word16)((L_index >> 10) & 0x03FF);
			_index[track + NB_TRACK] = (Word16)(L_index & 0x03FF);
			k += NPMAXPT;
		}
		for (track = 2; track < NB_TRACK; track++)
		{
			L_index = quant_4p_4N(&ind[k], 4);
			_index[track] = (Word16)((L_index >> 14) & 3);
			_index[track + NB_TRACK] = (Word16)(L_index & 0x3FFF);
			k += NPMAXPT;
		}
	} else if(nbbits == 88)
	{
		for (track = 0; track < NB_TRACK; track++)
		{
			L_index = quant_6p_6N_2(&ind[k], 4);
			_index[track] = (Word16)((L_index >> 11) & 0x07FF);
			_index[track + NB_TRACK] = (Word16)(L_index & 0x07FF);
			k += NPMAXPT;
		}
	}
	return;
}


/*-------------------------------------------------------------------*
 * Function  cor_h_vec()                                             *
 * ~~~~~~~~~~~~~~~~~~~~~                                             *
 * Compute correlations of h[] with vec[] for the specified track.   *
 *-------------------------------------------------------------------*/
void cor_h_vec_30(
		Word16 h[],                           /* (i) scaled impulse response                 */
		Word16 vec[],                         /* (i) scaled vector (/8) to correlate with h[] */
		Word16 track,                         /* (i) track to use                            */
		Word16 sign[],                        /* (i) sign vector                             */
		Word16 rrixix[][NB_POS],              /* (i) correlation of h[x] with h[x]      */
		Word16 cor_1[],                       /* (o) result of correlation (NB_POS elements) */
		Word16 cor_2[]                        /* (o) result of correlation (NB_POS elements) */
		)
{
	Word32 i, j, pos, corr;
	Word16 *p0, *p1, *p2,*p3,*cor_x,*cor_y;
	Word32 L_sum1,L_sum2;
	cor_x = cor_1;
	cor_y = cor_2;
	p0 = rrixix[track];
	p3 = rrixix[0];
	pos = track;

	for (i = 0; i < NB_POS; i+=2)
	{
		L_sum1 = L_sum2 = 0L;
		p1 = h;
		p2 = &vec[pos];
		for (j=pos;j < L_SUBFR; j++)
		{
			L_sum1 += *p1 * *p2;		
			p2-=3;
			L_sum2 += *p1++ * *p2;		
			p2+=4;
		}
		p2-=3;
		L_sum2 += *p1++ * *p2++;	
		L_sum2 += *p1++ * *p2++;	
		L_sum2 += *p1++ * *p2++;	

		L_sum1 = (L_sum1 << 2);
		L_sum2 = (L_sum2 << 2);

		corr = vo_round(L_sum1);	
		*cor_x++ = vo_mult(corr, sign[pos]) + (*p0++);
		corr = vo_round(L_sum2);
		*cor_y++ = vo_mult(corr, sign[pos-3]) + (*p3++);
		pos += STEP;

		L_sum1 = L_sum2 = 0L;
		p1 = h;
		p2 = &vec[pos];
		for (j=pos;j < L_SUBFR; j++)
		{
			L_sum1 += *p1 * *p2;		
			p2-=3;
			L_sum2 += *p1++ * *p2;		
			p2+=4;
		}
		p2-=3;
		L_sum2 += *p1++ * *p2++;	
		L_sum2 += *p1++ * *p2++;	
		L_sum2 += *p1++ * *p2++;	

		L_sum1 = (L_sum1 << 2);
		L_sum2 = (L_sum2 << 2);

		corr = vo_round(L_sum1);	
		*cor_x++ = vo_mult(corr, sign[pos]) + (*p0++);
		corr = vo_round(L_sum2);
		*cor_y++ = vo_mult(corr, sign[pos-3]) + (*p3++);
		pos += STEP;
	}
	return;
}

void cor_h_vec_012(
		Word16 h[],                           /* (i) scaled impulse response                 */
		Word16 vec[],                         /* (i) scaled vector (/8) to correlate with h[] */
		Word16 track,                         /* (i) track to use                            */
		Word16 sign[],                        /* (i) sign vector                             */
		Word16 rrixix[][NB_POS],              /* (i) correlation of h[x] with h[x]      */
		Word16 cor_1[],                       /* (o) result of correlation (NB_POS elements) */
		Word16 cor_2[]                        /* (o) result of correlation (NB_POS elements) */
		)
{
	Word32 i, j, pos, corr;
	Word16 *p0, *p1, *p2,*p3,*cor_x,*cor_y;
	Word32 L_sum1,L_sum2;
	cor_x = cor_1;
	cor_y = cor_2;
	p0 = rrixix[track];
	p3 = rrixix[track+1];
	pos = track;

	for (i = 0; i < NB_POS; i+=2)
	{
		L_sum1 = L_sum2 = 0L;
		p1 = h;
		p2 = &vec[pos];
		for (j=62-pos ;j >= 0; j--)
		{
			L_sum1 += *p1 * *p2++;
			L_sum2 += *p1++ * *p2;
		}
		L_sum1 += *p1 * *p2;
		L_sum1 = (L_sum1 << 2);
		L_sum2 = (L_sum2 << 2);

		corr = (L_sum1 + 0x8000) >> 16;
		cor_x[i] = vo_mult(corr, sign[pos]) + (*p0++);
		corr = (L_sum2 + 0x8000) >> 16;
		cor_y[i] = vo_mult(corr, sign[pos + 1]) + (*p3++);
		pos += STEP;

		L_sum1 = L_sum2 = 0L;
		p1 = h;
		p2 = &vec[pos];
		for (j= 62-pos;j >= 0; j--)
		{
			L_sum1 += *p1 * *p2++;
			L_sum2 += *p1++ * *p2;
		}
		L_sum1 += *p1 * *p2;
		L_sum1 = (L_sum1 << 2);
		L_sum2 = (L_sum2 << 2);

		corr = (L_sum1 + 0x8000) >> 16;
		cor_x[i+1] = vo_mult(corr, sign[pos]) + (*p0++);
		corr = (L_sum2 + 0x8000) >> 16;
		cor_y[i+1] = vo_mult(corr, sign[pos + 1]) + (*p3++);
		pos += STEP;
	}
	return;
}

/*-------------------------------------------------------------------*
 * Function  search_ixiy()                                           *
 * ~~~~~~~~~~~~~~~~~~~~~~~                                           *
 * Find the best positions of 2 pulses in a subframe.                *
 *-------------------------------------------------------------------*/

void search_ixiy(
		Word16 nb_pos_ix,                     /* (i) nb of pos for pulse 1 (1..8)       */
		Word16 track_x,                       /* (i) track of pulse 1                   */
		Word16 track_y,                       /* (i) track of pulse 2                   */
		Word16 * ps,                          /* (i/o) correlation of all fixed pulses  */
		Word16 * alp,                         /* (i/o) energy of all fixed pulses       */
		Word16 * ix,                          /* (o) position of pulse 1                */
		Word16 * iy,                          /* (o) position of pulse 2                */
		Word16 dn[],                          /* (i) corr. between target and h[]       */
		Word16 dn2[],                         /* (i) vector of selected positions       */
		Word16 cor_x[],                       /* (i) corr. of pulse 1 with fixed pulses */
		Word16 cor_y[],                       /* (i) corr. of pulse 2 with fixed pulses */
		Word16 rrixiy[][MSIZE]                /* (i) corr. of pulse 1 with pulse 2   */
		)
{
	Word32 x, y, pos, thres_ix;
	Word16 ps1, ps2, sq, sqk;
	Word16 alp_16, alpk;
	Word16 *p0, *p1, *p2;
	Word32 s, alp0, alp1, alp2;

	p0 = cor_x;                            
	p1 = cor_y;                            
	p2 = rrixiy[track_x];                  

	thres_ix = nb_pos_ix - NB_MAX;

	alp0 = L_deposit_h(*alp);
	alp0 = (alp0 + 0x00008000L);       /* for rounding */

	sqk = -1;                              
	alpk = 1;                              

	for (x = track_x; x < L_SUBFR; x += STEP)
	{
		ps1 = *ps + dn[x];
		alp1 = alp0 + ((*p0++)<<13);

		if (dn2[x] < thres_ix)
		{
			pos = -1;
			for (y = track_y; y < L_SUBFR; y += STEP)
			{
				ps2 = add1(ps1, dn[y]);

				alp2 = alp1 + ((*p1++)<<13);
				alp2 = alp2 + ((*p2++)<<14);
				alp_16 = extract_h(alp2);
				sq = vo_mult(ps2, ps2);
				s = vo_L_mult(alpk, sq) - ((sqk * alp_16)<<1);

				if (s > 0)
				{
					sqk = sq;              
					alpk = alp_16;         
					pos = y;               
				}
			}
			p1 -= NB_POS;

			if (pos >= 0)
			{
				*ix = x;                   
				*iy = pos;                 
			}
		} else
		{
			p2 += NB_POS;
		}
	}

	*ps = add1(*ps, add1(dn[*ix], dn[*iy])); 
	*alp = alpk;                           

	return;
}




