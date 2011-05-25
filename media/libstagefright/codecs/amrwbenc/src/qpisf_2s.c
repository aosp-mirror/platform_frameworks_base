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
*       File: apisf_2s.c                                               *
*                                                                      *
*       Description: Coding/Decodeing of ISF parameters with predication
*       The ISF vector is quantized using two-stage VQ with split-by-2 *
*       in 1st stage and split-by-5(or 3) in the second stage          *
*                                                                      *
************************************************************************/

#include "typedef.h"
#include "basic_op.h"
#include "cnst.h"
#include "acelp.h"
#include "qpisf_2s.tab"                    /* Codebooks of isfs */

#define MU         10923                   /* Prediction factor   (1.0/3.0) in Q15 */
#define N_SURV_MAX 4                       /* 4 survivors max */
#define ALPHA      29491                   /* 0. 9 in Q15     */
#define ONE_ALPHA (32768-ALPHA)            /* (1.0 - ALPHA) in Q15 */

/* private functions */
static void VQ_stage1(
		Word16 * x,                           /* input : ISF residual vector           */
		Word16 * dico,                        /* input : quantization codebook         */
		Word16 dim,                           /* input : dimention of vector           */
		Word16 dico_size,                     /* input : size of quantization codebook */
		Word16 * index,                       /* output: indices of survivors          */
		Word16 surv                           /* input : number of survivor            */
		);

/**************************************************************************
* Function:   Qpisf_2s_46B()                                              *
*                                                                         *
* Description: Quantization of isf parameters with prediction. (46 bits)  *
*                                                                         *
* The isf vector is quantized using two-stage VQ with split-by-2 in       *
*  1st stage and split-by-5 in the second stage.                          *
***************************************************************************/

void Qpisf_2s_46b(
		Word16 * isf1,                        /* (i) Q15 : ISF in the frequency domain (0..0.5) */
		Word16 * isf_q,                       /* (o) Q15 : quantized ISF               (0..0.5) */
		Word16 * past_isfq,                   /* (io)Q15 : past ISF quantizer                   */
		Word16 * indice,                      /* (o)     : quantization indices                 */
		Word16 nb_surv                        /* (i)     : number of survivor (1, 2, 3 or 4)    */
		)
{
	Word16 tmp_ind[5];
	Word16 surv1[N_SURV_MAX];              /* indices of survivors from 1st stage */
	Word32 i, k, temp, min_err, distance;
	Word16 isf[ORDER];
	Word16 isf_stage2[ORDER];

	for (i = 0; i < ORDER; i++)
	{
		isf[i] = vo_sub(isf1[i], mean_isf[i]);
		isf[i] = vo_sub(isf[i], vo_mult(MU, past_isfq[i])); 
	}

	VQ_stage1(&isf[0], dico1_isf, 9, SIZE_BK1, surv1, nb_surv);

	distance = MAX_32;          

	for (k = 0; k < nb_surv; k++)
	{
		for (i = 0; i < 9; i++)
		{
			isf_stage2[i] = vo_sub(isf[i], dico1_isf[i + surv1[k] * 9]); 
		}
		tmp_ind[0] = Sub_VQ(&isf_stage2[0], dico21_isf, 3, SIZE_BK21, &min_err); 
		temp = min_err;
		tmp_ind[1] = Sub_VQ(&isf_stage2[3], dico22_isf, 3, SIZE_BK22, &min_err); 
		temp = vo_L_add(temp, min_err);
		tmp_ind[2] = Sub_VQ(&isf_stage2[6], dico23_isf, 3, SIZE_BK23, &min_err);  
		temp = vo_L_add(temp, min_err);

		if(temp < distance)
		{
			distance = temp;               
			indice[0] = surv1[k];          
			for (i = 0; i < 3; i++)
			{
				indice[i + 2] = tmp_ind[i];
			}
		}
	}


	VQ_stage1(&isf[9], dico2_isf, 7, SIZE_BK2, surv1, nb_surv);

	distance = MAX_32;                   

	for (k = 0; k < nb_surv; k++)
	{
		for (i = 0; i < 7; i++)
		{
			isf_stage2[i] = vo_sub(isf[9 + i], dico2_isf[i + surv1[k] * 7]);       
		}

		tmp_ind[0] = Sub_VQ(&isf_stage2[0], dico24_isf, 3, SIZE_BK24, &min_err);
		temp = min_err; 
		tmp_ind[1] = Sub_VQ(&isf_stage2[3], dico25_isf, 4, SIZE_BK25, &min_err);
		temp = vo_L_add(temp, min_err);

		if(temp < distance)
		{
			distance = temp;               
			indice[1] = surv1[k];          
			for (i = 0; i < 2; i++)
			{
				indice[i + 5] = tmp_ind[i];
			}
		}
	}

	Dpisf_2s_46b(indice, isf_q, past_isfq, isf_q, isf_q, 0, 0);

	return;
}

/*****************************************************************************
* Function:   Qpisf_2s_36B()                                                 *
*                                                                            *
* Description: Quantization of isf parameters with prediction. (36 bits)     *
*                                                                            *
* The isf vector is quantized using two-stage VQ with split-by-2 in          *
*  1st stage and split-by-3 in the second stage.                             *
******************************************************************************/

void Qpisf_2s_36b(
		Word16 * isf1,                        /* (i) Q15 : ISF in the frequency domain (0..0.5) */
		Word16 * isf_q,                       /* (o) Q15 : quantized ISF               (0..0.5) */
		Word16 * past_isfq,                   /* (io)Q15 : past ISF quantizer                   */
		Word16 * indice,                      /* (o)     : quantization indices                 */
		Word16 nb_surv                        /* (i)     : number of survivor (1, 2, 3 or 4)    */
		)
{
	Word16 i, k, tmp_ind[5];
	Word16 surv1[N_SURV_MAX];              /* indices of survivors from 1st stage */
	Word32 temp, min_err, distance;
	Word16 isf[ORDER];
	Word16 isf_stage2[ORDER];

	for (i = 0; i < ORDER; i++)
	{
		isf[i] = vo_sub(isf1[i], mean_isf[i]);
		isf[i] = vo_sub(isf[i], vo_mult(MU, past_isfq[i]));
	}

	VQ_stage1(&isf[0], dico1_isf, 9, SIZE_BK1, surv1, nb_surv);

	distance = MAX_32;                  

	for (k = 0; k < nb_surv; k++)
	{
		for (i = 0; i < 9; i++)
		{
			isf_stage2[i] = vo_sub(isf[i], dico1_isf[i + surv1[k] * 9]); 
		}

		tmp_ind[0] = Sub_VQ(&isf_stage2[0], dico21_isf_36b, 5, SIZE_BK21_36b, &min_err);        
		temp = min_err;                  
		tmp_ind[1] = Sub_VQ(&isf_stage2[5], dico22_isf_36b, 4, SIZE_BK22_36b, &min_err);        
		temp = vo_L_add(temp, min_err);

		if(temp < distance)
		{
			distance = temp;               
			indice[0] = surv1[k];          
			for (i = 0; i < 2; i++)
			{
				indice[i + 2] = tmp_ind[i];
			}
		}
	}

	VQ_stage1(&isf[9], dico2_isf, 7, SIZE_BK2, surv1, nb_surv);
	distance = MAX_32;                    

	for (k = 0; k < nb_surv; k++)
	{
		for (i = 0; i < 7; i++)
		{
			isf_stage2[i] = vo_sub(isf[9 + i], dico2_isf[i + surv1[k] * 7]);     
		}

		tmp_ind[0] = Sub_VQ(&isf_stage2[0], dico23_isf_36b, 7, SIZE_BK23_36b, &min_err);  
		temp = min_err;                  

		if(temp < distance)
		{
			distance = temp;               
			indice[1] = surv1[k];          
			indice[4] = tmp_ind[0];        
		}
	}

	Dpisf_2s_36b(indice, isf_q, past_isfq, isf_q, isf_q, 0, 0);

	return;
}

/*********************************************************************
* Function: Dpisf_2s_46b()                                           *
*                                                                    *
* Description: Decoding of ISF parameters                            *
**********************************************************************/

void Dpisf_2s_46b(
		Word16 * indice,                      /* input:  quantization indices                       */
		Word16 * isf_q,                       /* output: quantized ISF in frequency domain (0..0.5) */
		Word16 * past_isfq,                   /* i/0   : past ISF quantizer                    */
		Word16 * isfold,                      /* input : past quantized ISF                    */
		Word16 * isf_buf,                     /* input : isf buffer                                                        */
		Word16 bfi,                           /* input : Bad frame indicator                   */
		Word16 enc_dec
		)
{
	Word16 ref_isf[M], tmp;
	Word32 i, j, L_tmp;

	if (bfi == 0)                          /* Good frame */
	{
		for (i = 0; i < 9; i++)
		{
			isf_q[i] = dico1_isf[indice[0] * 9 + i];    
		}
		for (i = 0; i < 7; i++)
		{
			isf_q[i + 9] = dico2_isf[indice[1] * 7 + i];       
		}

		for (i = 0; i < 3; i++)
		{
			isf_q[i] = add1(isf_q[i], dico21_isf[indice[2] * 3 + i]);   
			isf_q[i + 3] = add1(isf_q[i + 3], dico22_isf[indice[3] * 3 + i]);  
			isf_q[i + 6] = add1(isf_q[i + 6], dico23_isf[indice[4] * 3 + i]); 
			isf_q[i + 9] = add1(isf_q[i + 9], dico24_isf[indice[5] * 3 + i]); 
		}

		for (i = 0; i < 4; i++)
		{
			isf_q[i + 12] = add1(isf_q[i + 12], dico25_isf[indice[6] * 4 + i]);  
		}

		for (i = 0; i < ORDER; i++)
		{
			tmp = isf_q[i];               
			isf_q[i] = add1(tmp, mean_isf[i]);  
			isf_q[i] = add1(isf_q[i], vo_mult(MU, past_isfq[i]));
			past_isfq[i] = tmp;  
		}

		if (enc_dec)
		{
			for (i = 0; i < M; i++)
			{
				for (j = (L_MEANBUF - 1); j > 0; j--)
				{
					isf_buf[j * M + i] = isf_buf[(j - 1) * M + i]; 
				}
				isf_buf[i] = isf_q[i]; 
			}
		}
	} else
	{                                      /* bad frame */
		for (i = 0; i < M; i++)
		{
			L_tmp = mean_isf[i] << 14;
			for (j = 0; j < L_MEANBUF; j++)
			{
				L_tmp += (isf_buf[j * M + i] << 14);
			}
			ref_isf[i] = vo_round(L_tmp);
		}

		/* use the past ISFs slightly shifted towards their mean */
		for (i = 0; i < ORDER; i++)
		{
			isf_q[i] = add1(vo_mult(ALPHA, isfold[i]), vo_mult(ONE_ALPHA, ref_isf[i])); 
		}

		/* estimate past quantized residual to be used in next frame */
		for (i = 0; i < ORDER; i++)
		{
			tmp = add1(ref_isf[i], vo_mult(past_isfq[i], MU));      /* predicted ISF */
			past_isfq[i] = vo_sub(isf_q[i], tmp); 
			past_isfq[i] = (past_isfq[i] >> 1);        /* past_isfq[i] *= 0.5 */
		}
	}

	Reorder_isf(isf_q, ISF_GAP, ORDER);
	return;
}

/*********************************************************************
* Function:   Disf_2s_36b()                                          *
*                                                                    *
* Description: Decoding of ISF parameters                            *
*********************************************************************/

void Dpisf_2s_36b(
		Word16 * indice,                      /* input:  quantization indices                       */
		Word16 * isf_q,                       /* output: quantized ISF in frequency domain (0..0.5) */
		Word16 * past_isfq,                   /* i/0   : past ISF quantizer                    */
		Word16 * isfold,                      /* input : past quantized ISF                    */
		Word16 * isf_buf,                     /* input : isf buffer                                                        */
		Word16 bfi,                           /* input : Bad frame indicator                   */
		Word16 enc_dec
		)
{
	Word16 ref_isf[M], tmp;
	Word32 i, j, L_tmp;

	if (bfi == 0)                          /* Good frame */
	{
		for (i = 0; i < 9; i++)
		{
			isf_q[i] = dico1_isf[indice[0] * 9 + i];    
		}
		for (i = 0; i < 7; i++)
		{
			isf_q[i + 9] = dico2_isf[indice[1] * 7 + i];       
		}

		for (i = 0; i < 5; i++)
		{
			isf_q[i] = add1(isf_q[i], dico21_isf_36b[indice[2] * 5 + i]);       
		}
		for (i = 0; i < 4; i++)
		{
			isf_q[i + 5] = add1(isf_q[i + 5], dico22_isf_36b[indice[3] * 4 + i]);        
		}
		for (i = 0; i < 7; i++)
		{
			isf_q[i + 9] = add1(isf_q[i + 9], dico23_isf_36b[indice[4] * 7 + i]);       
		}

		for (i = 0; i < ORDER; i++)
		{
			tmp = isf_q[i];
			isf_q[i] = add1(tmp, mean_isf[i]);   
			isf_q[i] = add1(isf_q[i], vo_mult(MU, past_isfq[i]));   
			past_isfq[i] = tmp;           
		}


		if (enc_dec)
		{
			for (i = 0; i < M; i++)
			{
				for (j = (L_MEANBUF - 1); j > 0; j--)
				{
					isf_buf[j * M + i] = isf_buf[(j - 1) * M + i];      
				}
				isf_buf[i] = isf_q[i];    
			}
		}
	} else
	{                                      /* bad frame */
		for (i = 0; i < M; i++)
		{
			L_tmp = (mean_isf[i] << 14);
			for (j = 0; j < L_MEANBUF; j++)
			{
				L_tmp += (isf_buf[j * M + i] << 14);
			}
			ref_isf[i] = vo_round(L_tmp);    
		}

		/* use the past ISFs slightly shifted towards their mean */
		for (i = 0; i < ORDER; i++)
		{
			isf_q[i] = add1(vo_mult(ALPHA, isfold[i]), vo_mult(ONE_ALPHA, ref_isf[i]));        
		}

		/* estimate past quantized residual to be used in next frame */
		for (i = 0; i < ORDER; i++)
		{
			tmp = add1(ref_isf[i], vo_mult(past_isfq[i], MU));      /* predicted ISF */
			past_isfq[i] = vo_sub(isf_q[i], tmp);  
			past_isfq[i] = past_isfq[i] >> 1;         /* past_isfq[i] *= 0.5 */
		}
	}

	Reorder_isf(isf_q, ISF_GAP, ORDER);

	return;
}


/***************************************************************************
* Function:  Reorder_isf()                                                 *
*                                                                          *
* Description: To make sure that the  isfs are properly order and to       *
*              keep a certain minimum distance between consecutive isfs.   *
*--------------------------------------------------------------------------*
*    Argument         description                     in/out               *
*                                                                          *
*     isf[]           vector of isfs                    i/o                *
*     min_dist        minimum required distance         i                  *
*     n               LPC order                         i                  *
****************************************************************************/

void Reorder_isf(
		Word16 * isf,                         /* (i/o) Q15: ISF in the frequency domain (0..0.5) */
		Word16 min_dist,                      /* (i) Q15  : minimum distance to keep             */
		Word16 n                              /* (i)      : number of ISF                        */
		)
{
	Word32 i; 
	Word16 isf_min;

	isf_min = min_dist;                    
	for (i = 0; i < n - 1; i++)
	{
		if(isf[i] < isf_min)
		{
			isf[i] = isf_min;              
		}
		isf_min = (isf[i] + min_dist);
	}
	return;
}


Word16 Sub_VQ(                             /* output: return quantization index     */
		Word16 * x,                           /* input : ISF residual vector           */
		Word16 * dico,                        /* input : quantization codebook         */
		Word16 dim,                           /* input : dimention of vector           */
		Word16 dico_size,                     /* input : size of quantization codebook */
		Word32 * distance                     /* output: error of quantization         */
	     )
{
	Word16 temp, *p_dico;
	Word32 i, j, index;
	Word32 dist_min, dist;

	dist_min = MAX_32;                     
	p_dico = dico;                         

	index = 0;                             
	for (i = 0; i < dico_size; i++)
	{
		dist = 0;  

		for (j = 0; j < dim; j++)
		{
			temp = x[j] - (*p_dico++);
			dist += (temp * temp)<<1;
		}

		if(dist < dist_min)
		{
			dist_min = dist;               
			index = i;                     
		}
	}

	*distance = dist_min;                  

	/* Reading the selected vector */
	p_dico = &dico[index * dim];           
	for (j = 0; j < dim; j++)
	{
		x[j] = *p_dico++;                  
	}

	return index;
}


static void VQ_stage1(
		Word16 * x,                           /* input : ISF residual vector           */
		Word16 * dico,                        /* input : quantization codebook         */
		Word16 dim,                           /* input : dimention of vector           */
		Word16 dico_size,                     /* input : size of quantization codebook */
		Word16 * index,                       /* output: indices of survivors          */
		Word16 surv                           /* input : number of survivor            */
		)
{
	Word16 temp, *p_dico;
	Word32 i, j, k, l;
	Word32 dist_min[N_SURV_MAX], dist;

	dist_min[0] = MAX_32;
	dist_min[1] = MAX_32;
	dist_min[2] = MAX_32;
	dist_min[3] = MAX_32;
	index[0] = 0;
	index[1] = 1;
	index[2] = 2;
	index[3] = 3;

	p_dico = dico;                         

	for (i = 0; i < dico_size; i++)
	{
		dist = 0;                          
		for (j = 0; j < dim; j++)
		{
			temp = x[j] -  (*p_dico++);
			dist += (temp * temp)<<1;
		}

		for (k = 0; k < surv; k++)
		{
			if(dist < dist_min[k])
			{
				for (l = surv - 1; l > k; l--)
				{
					dist_min[l] = dist_min[l - 1];      
					index[l] = index[l - 1];    
				}
				dist_min[k] = dist;        
				index[k] = i;              
				break;
			}
		}
	}
	return;
}




