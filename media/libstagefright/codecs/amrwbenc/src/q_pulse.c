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
*      File: q_pulse.c                                                 *
*                                                                      *
*      Description: Coding and decoding of algebraic codebook          *
*                                                                      *
************************************************************************/

#include <stdio.h>
#include "typedef.h"
#include "basic_op.h"
#include "q_pulse.h"

#define NB_POS 16                          /* pos in track, mask for sign bit */

Word32 quant_1p_N1(                        /* (o) return N+1 bits             */
		Word16 pos,                        /* (i) position of the pulse       */
		Word16 N)                          /* (i) number of bits for position */
{
	Word16 mask;
	Word32 index;

	mask = (1 << N) - 1;              /* mask = ((1<<N)-1); */
	/*-------------------------------------------------------*
	 * Quantization of 1 pulse with N+1 bits:                *
	 *-------------------------------------------------------*/
	index = L_deposit_l((Word16) (pos & mask));
	if ((pos & NB_POS) != 0)
	{
		index = vo_L_add(index, L_deposit_l(1 << N));   /* index += 1 << N; */
	}
	return (index);
}


Word32 quant_2p_2N1(                       /* (o) return (2*N)+1 bits         */
		Word16 pos1,                          /* (i) position of the pulse 1     */
		Word16 pos2,                          /* (i) position of the pulse 2     */
		Word16 N)                             /* (i) number of bits for position */
{
	Word16 mask, tmp;
	Word32 index;
	mask = (1 << N) - 1;              /* mask = ((1<<N)-1); */
	/*-------------------------------------------------------*
	 * Quantization of 2 pulses with 2*N+1 bits:             *
	 *-------------------------------------------------------*/
	if (((pos2 ^ pos1) & NB_POS) == 0)
	{
		/* sign of 1st pulse == sign of 2th pulse */
		if(pos1 <= pos2)          /* ((pos1 - pos2) <= 0) */
		{
			/* index = ((pos1 & mask) << N) + (pos2 & mask); */
			index = L_deposit_l(add1((((Word16) (pos1 & mask)) << N), ((Word16) (pos2 & mask))));
		} else
		{
			/* ((pos2 & mask) << N) + (pos1 & mask); */
			index = L_deposit_l(add1((((Word16) (pos2 & mask)) << N), ((Word16) (pos1 & mask))));
		}
		if ((pos1 & NB_POS) != 0)
		{
			tmp = (N << 1);
			index = vo_L_add(index, (1L << tmp));       /* index += 1 << (2*N); */
		}
	} else
	{
		/* sign of 1st pulse != sign of 2th pulse */
		if (vo_sub((Word16) (pos1 & mask), (Word16) (pos2 & mask)) <= 0)
		{
			/* index = ((pos2 & mask) << N) + (pos1 & mask); */
			index = L_deposit_l(add1((((Word16) (pos2 & mask)) << N), ((Word16) (pos1 & mask))));
			if ((pos2 & NB_POS) != 0)
			{
				tmp = (N << 1);           /* index += 1 << (2*N); */
				index = vo_L_add(index, (1L << tmp));
			}
		} else
		{
			/* index = ((pos1 & mask) << N) + (pos2 & mask);	 */
			index = L_deposit_l(add1((((Word16) (pos1 & mask)) << N), ((Word16) (pos2 & mask))));
			if ((pos1 & NB_POS) != 0)
			{
				tmp = (N << 1);
				index = vo_L_add(index, (1 << tmp));    /* index += 1 << (2*N); */
			}
		}
	}
	return (index);
}


Word32 quant_3p_3N1(                       /* (o) return (3*N)+1 bits         */
		Word16 pos1,                          /* (i) position of the pulse 1     */
		Word16 pos2,                          /* (i) position of the pulse 2     */
		Word16 pos3,                          /* (i) position of the pulse 3     */
		Word16 N)                             /* (i) number of bits for position */
{
	Word16 nb_pos;
	Word32 index;

	nb_pos =(1 <<(N - 1));            /* nb_pos = (1<<(N-1)); */
	/*-------------------------------------------------------*
	 * Quantization of 3 pulses with 3*N+1 bits:             *
	 *-------------------------------------------------------*/
	if (((pos1 ^ pos2) & nb_pos) == 0)
	{
		index = quant_2p_2N1(pos1, pos2, sub(N, 1));    /* index = quant_2p_2N1(pos1, pos2, (N-1)); */
		/* index += (pos1 & nb_pos) << N; */
		index = vo_L_add(index, (L_deposit_l((Word16) (pos1 & nb_pos)) << N));
		/* index += quant_1p_N1(pos3, N) << (2*N); */
		index = vo_L_add(index, (quant_1p_N1(pos3, N)<<(N << 1)));

	} else if (((pos1 ^ pos3) & nb_pos) == 0)
	{
		index = quant_2p_2N1(pos1, pos3, sub(N, 1));    /* index = quant_2p_2N1(pos1, pos3, (N-1)); */
		index = vo_L_add(index, (L_deposit_l((Word16) (pos1 & nb_pos)) << N));
		/* index += (pos1 & nb_pos) << N; */
		index = vo_L_add(index, (quant_1p_N1(pos2, N) << (N << 1)));
		/* index += quant_1p_N1(pos2, N) <<
		 * (2*N); */
	} else
	{
		index = quant_2p_2N1(pos2, pos3, (N - 1));    /* index = quant_2p_2N1(pos2, pos3, (N-1)); */
		/* index += (pos2 & nb_pos) << N;			 */
		index = vo_L_add(index, (L_deposit_l((Word16) (pos2 & nb_pos)) << N));
		/* index += quant_1p_N1(pos1, N) << (2*N);	 */
		index = vo_L_add(index, (quant_1p_N1(pos1, N) << (N << 1)));
	}
	return (index);
}


Word32 quant_4p_4N1(                       /* (o) return (4*N)+1 bits         */
		Word16 pos1,                          /* (i) position of the pulse 1     */
		Word16 pos2,                          /* (i) position of the pulse 2     */
		Word16 pos3,                          /* (i) position of the pulse 3     */
		Word16 pos4,                          /* (i) position of the pulse 4     */
		Word16 N)                             /* (i) number of bits for position */
{
	Word16 nb_pos;
	Word32 index;

	nb_pos = 1 << (N - 1);            /* nb_pos = (1<<(N-1));  */
	/*-------------------------------------------------------*
	 * Quantization of 4 pulses with 4*N+1 bits:             *
	 *-------------------------------------------------------*/
	if (((pos1 ^ pos2) & nb_pos) == 0)
	{
		index = quant_2p_2N1(pos1, pos2, sub(N, 1));    /* index = quant_2p_2N1(pos1, pos2, (N-1)); */
		/* index += (pos1 & nb_pos) << N;	 */
		index = vo_L_add(index, (L_deposit_l((Word16) (pos1 & nb_pos)) << N));
		/* index += quant_2p_2N1(pos3, pos4, N) << (2*N); */
		index = vo_L_add(index, (quant_2p_2N1(pos3, pos4, N) << (N << 1)));
	} else if (((pos1 ^ pos3) & nb_pos) == 0)
	{
		index = quant_2p_2N1(pos1, pos3, (N - 1));
		/* index += (pos1 & nb_pos) << N; */
		index = vo_L_add(index, (L_deposit_l((Word16) (pos1 & nb_pos)) << N));
		/* index += quant_2p_2N1(pos2, pos4, N) << (2*N); */
		index = vo_L_add(index, (quant_2p_2N1(pos2, pos4, N) << (N << 1)));
	} else
	{
		index = quant_2p_2N1(pos2, pos3, (N - 1));
		/* index += (pos2 & nb_pos) << N; */
		index = vo_L_add(index, (L_deposit_l((Word16) (pos2 & nb_pos)) << N));
		/* index += quant_2p_2N1(pos1, pos4, N) << (2*N); */
		index = vo_L_add(index, (quant_2p_2N1(pos1, pos4, N) << (N << 1)));
	}
	return (index);
}


Word32 quant_4p_4N(                        /* (o) return 4*N bits             */
		Word16 pos[],                         /* (i) position of the pulse 1..4  */
		Word16 N)                             /* (i) number of bits for position */
{
	Word16 nb_pos, mask, n_1, tmp;
	Word16 posA[4], posB[4];
	Word32 i, j, k, index;

	n_1 = (Word16) (N - 1);
	nb_pos = (1 << n_1);                  /* nb_pos = (1<<n_1); */
	mask = vo_sub((1 << N), 1);              /* mask = ((1<<N)-1); */

	i = 0;
	j = 0;
	for (k = 0; k < 4; k++)
	{
		if ((pos[k] & nb_pos) == 0)
		{
			posA[i++] = pos[k];
		} else
		{
			posB[j++] = pos[k];
		}
	}

	switch (i)
	{
		case 0:
			tmp = vo_sub((N << 2), 3);           /* index = 1 << ((4*N)-3); */
			index = (1L << tmp);
			/* index += quant_4p_4N1(posB[0], posB[1], posB[2], posB[3], n_1); */
			index = vo_L_add(index, quant_4p_4N1(posB[0], posB[1], posB[2], posB[3], n_1));
			break;
		case 1:
			/* index = quant_1p_N1(posA[0], n_1) << ((3*n_1)+1); */
			tmp = add1((Word16)((vo_L_mult(3, n_1) >> 1)), 1);
			index = L_shl(quant_1p_N1(posA[0], n_1), tmp);
			/* index += quant_3p_3N1(posB[0], posB[1], posB[2], n_1); */
			index = vo_L_add(index, quant_3p_3N1(posB[0], posB[1], posB[2], n_1));
			break;
		case 2:
			tmp = ((n_1 << 1) + 1);         /* index = quant_2p_2N1(posA[0], posA[1], n_1) << ((2*n_1)+1); */
			index = L_shl(quant_2p_2N1(posA[0], posA[1], n_1), tmp);
			/* index += quant_2p_2N1(posB[0], posB[1], n_1); */
			index = vo_L_add(index, quant_2p_2N1(posB[0], posB[1], n_1));
			break;
		case 3:
			/* index = quant_3p_3N1(posA[0], posA[1], posA[2], n_1) << N; */
			index = L_shl(quant_3p_3N1(posA[0], posA[1], posA[2], n_1), N);
			index = vo_L_add(index, quant_1p_N1(posB[0], n_1));        /* index += quant_1p_N1(posB[0], n_1); */
			break;
		case 4:
			index = quant_4p_4N1(posA[0], posA[1], posA[2], posA[3], n_1);
			break;
		default:
			index = 0;
			fprintf(stderr, "Error in function quant_4p_4N\n");
	}
	tmp = ((N << 2) - 2);               /* index += (i & 3) << ((4*N)-2); */
	index = vo_L_add(index, L_shl((L_deposit_l(i) & (3L)), tmp));

	return (index);
}



Word32 quant_5p_5N(                        /* (o) return 5*N bits             */
		Word16 pos[],                         /* (i) position of the pulse 1..5  */
		Word16 N)                             /* (i) number of bits for position */
{
	Word16 nb_pos, n_1, tmp;
	Word16 posA[5], posB[5];
	Word32 i, j, k, index, tmp2;

	n_1 = (Word16) (N - 1);
	nb_pos = (1 << n_1);                  /* nb_pos = (1<<n_1); */

	i = 0;
	j = 0;
	for (k = 0; k < 5; k++)
	{
		if ((pos[k] & nb_pos) == 0)
		{
			posA[i++] = pos[k];
		} else
		{
			posB[j++] = pos[k];
		}
	}

	switch (i)
	{
		case 0:
			tmp = vo_sub((Word16)((vo_L_mult(5, N) >> 1)), 1);        /* ((5*N)-1)) */
			index = L_shl(1L, tmp);   /* index = 1 << ((5*N)-1); */
			tmp = add1((N << 1), 1);  /* index += quant_3p_3N1(posB[0], posB[1], posB[2], n_1) << ((2*N)+1);*/
			tmp2 = L_shl(quant_3p_3N1(posB[0], posB[1], posB[2], n_1), tmp);
			index = vo_L_add(index, tmp2);
			index = vo_L_add(index, quant_2p_2N1(posB[3], posB[4], N));        /* index += quant_2p_2N1(posB[3], posB[4], N); */
			break;
		case 1:
			tmp = vo_sub((Word16)((vo_L_mult(5, N) >> 1)), 1);        /* index = 1 << ((5*N)-1); */
			index = L_shl(1L, tmp);
			tmp = add1((N << 1), 1);   /* index += quant_3p_3N1(posB[0], posB[1], posB[2], n_1) <<((2*N)+1);  */
			tmp2 = L_shl(quant_3p_3N1(posB[0], posB[1], posB[2], n_1), tmp);
			index = vo_L_add(index, tmp2);
			index = vo_L_add(index, quant_2p_2N1(posB[3], posA[0], N));        /* index += quant_2p_2N1(posB[3], posA[0], N); */
			break;
		case 2:
			tmp = vo_sub((Word16)((vo_L_mult(5, N) >> 1)), 1);        /* ((5*N)-1)) */
			index = L_shl(1L, tmp);            /* index = 1 << ((5*N)-1); */
			tmp = add1((N << 1), 1);           /* index += quant_3p_3N1(posB[0], posB[1], posB[2], n_1) << ((2*N)+1);  */
			tmp2 = L_shl(quant_3p_3N1(posB[0], posB[1], posB[2], n_1), tmp);
			index = vo_L_add(index, tmp2);
			index = vo_L_add(index, quant_2p_2N1(posA[0], posA[1], N));        /* index += quant_2p_2N1(posA[0], posA[1], N); */
			break;
		case 3:
			tmp = add1((N << 1), 1);           /* index = quant_3p_3N1(posA[0], posA[1], posA[2], n_1) << ((2*N)+1);  */
			index = L_shl(quant_3p_3N1(posA[0], posA[1], posA[2], n_1), tmp);
			index = vo_L_add(index, quant_2p_2N1(posB[0], posB[1], N));        /* index += quant_2p_2N1(posB[0], posB[1], N); */
			break;
		case 4:
			tmp = add1((N << 1), 1);           /* index = quant_3p_3N1(posA[0], posA[1], posA[2], n_1) << ((2*N)+1);  */
			index = L_shl(quant_3p_3N1(posA[0], posA[1], posA[2], n_1), tmp);
			index = vo_L_add(index, quant_2p_2N1(posA[3], posB[0], N));        /* index += quant_2p_2N1(posA[3], posB[0], N); */
			break;
		case 5:
			tmp = add1((N << 1), 1);           /* index = quant_3p_3N1(posA[0], posA[1], posA[2], n_1) << ((2*N)+1);  */
			index = L_shl(quant_3p_3N1(posA[0], posA[1], posA[2], n_1), tmp);
			index = vo_L_add(index, quant_2p_2N1(posA[3], posA[4], N));        /* index += quant_2p_2N1(posA[3], posA[4], N); */
			break;
		default:
			index = 0;
			fprintf(stderr, "Error in function quant_5p_5N\n");
	}

	return (index);
}


Word32 quant_6p_6N_2(                      /* (o) return (6*N)-2 bits         */
		Word16 pos[],                         /* (i) position of the pulse 1..6  */
		Word16 N)                             /* (i) number of bits for position */
{
	Word16 nb_pos, n_1;
	Word16 posA[6], posB[6];
	Word32 i, j, k, index;

	/* !!  N and n_1 are constants -> it doesn't need to be operated by Basic Operators */
	n_1 = (Word16) (N - 1);
	nb_pos = (1 << n_1);                  /* nb_pos = (1<<n_1); */

	i = 0;
	j = 0;
	for (k = 0; k < 6; k++)
	{
		if ((pos[k] & nb_pos) == 0)
		{
			posA[i++] = pos[k];
		} else
		{
			posB[j++] = pos[k];
		}
	}

	switch (i)
	{
		case 0:
			index = (1 << (Word16) (6 * N - 5));        /* index = 1 << ((6*N)-5); */
			index = vo_L_add(index, (quant_5p_5N(posB, n_1) << N)); /* index += quant_5p_5N(posB, n_1) << N; */
			index = vo_L_add(index, quant_1p_N1(posB[5], n_1));        /* index += quant_1p_N1(posB[5], n_1); */
			break;
		case 1:
			index = (1L << (Word16) (6 * N - 5));        /* index = 1 << ((6*N)-5); */
			index = vo_L_add(index, (quant_5p_5N(posB, n_1) << N)); /* index += quant_5p_5N(posB, n_1) << N; */
			index = vo_L_add(index, quant_1p_N1(posA[0], n_1));        /* index += quant_1p_N1(posA[0], n_1); */
			break;
		case 2:
			index = (1L << (Word16) (6 * N - 5));        /* index = 1 << ((6*N)-5); */
			/* index += quant_4p_4N(posB, n_1) << ((2*n_1)+1); */
			index = vo_L_add(index, (quant_4p_4N(posB, n_1) << (Word16) (2 * n_1 + 1)));
			index = vo_L_add(index, quant_2p_2N1(posA[0], posA[1], n_1));      /* index += quant_2p_2N1(posA[0], posA[1], n_1); */
			break;
		case 3:
			index = (quant_3p_3N1(posA[0], posA[1], posA[2], n_1) << (Word16) (3 * n_1 + 1));
			                                  /* index = quant_3p_3N1(posA[0], posA[1], posA[2], n_1) << ((3*n_1)+1); */
			index =vo_L_add(index, quant_3p_3N1(posB[0], posB[1], posB[2], n_1));
			                                 /* index += quant_3p_3N1(posB[0], posB[1], posB[2], n_1); */
			break;
		case 4:
			i = 2;
			index = (quant_4p_4N(posA, n_1) << (Word16) (2 * n_1 + 1));  /* index = quant_4p_4N(posA, n_1) << ((2*n_1)+1); */
			index = vo_L_add(index, quant_2p_2N1(posB[0], posB[1], n_1));      /* index += quant_2p_2N1(posB[0], posB[1], n_1); */
			break;
		case 5:
			i = 1;
			index = (quant_5p_5N(posA, n_1) << N);       /* index = quant_5p_5N(posA, n_1) << N; */
			index = vo_L_add(index, quant_1p_N1(posB[0], n_1));        /* index += quant_1p_N1(posB[0], n_1); */
			break;
		case 6:
			i = 0;
			index = (quant_5p_5N(posA, n_1) << N);       /* index = quant_5p_5N(posA, n_1) << N; */
			index = vo_L_add(index, quant_1p_N1(posA[5], n_1));        /* index += quant_1p_N1(posA[5], n_1); */
			break;
		default:
			index = 0;
			fprintf(stderr, "Error in function quant_6p_6N_2\n");
	}
	index = vo_L_add(index, ((L_deposit_l(i) & 3L) << (Word16) (6 * N - 4)));   /* index += (i & 3) << ((6*N)-4); */

	return (index);
}


