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
*      File: int_lpc.c                                                 *
*                                                                      *
*      Description:Interpolation of the LP parameters in 4 subframes.  *
*                                                                      *
************************************************************************/

#include "typedef.h"
#include "basic_op.h"
#include "cnst.h"
#include "acelp.h"

#define MP1 (M+1)


void Int_isp(
		Word16 isp_old[],                     /* input : isps from past frame              */
		Word16 isp_new[],                     /* input : isps from present frame           */
		Word16 frac[],                        /* input : fraction for 3 first subfr (Q15)  */
		Word16 Az[]                           /* output: LP coefficients in 4 subframes    */
	    )
{
	Word32 i, k; 
	Word16 fac_old, fac_new;
	Word16 isp[M];
	Word32 L_tmp;

	for (k = 0; k < 3; k++)
	{
		fac_new = frac[k];                
		fac_old = (32767 - fac_new) + 1;  /* 1.0 - fac_new */

		for (i = 0; i < M; i++)
		{
			L_tmp = (isp_old[i] * fac_old)<<1;
			L_tmp += (isp_new[i] * fac_new)<<1;
			isp[i] = (L_tmp + 0x8000)>>16;        
		}
		Isp_Az(isp, Az, M, 0);
		Az += MP1;
	}

	/* 4th subframe: isp_new (frac=1.0) */
	Isp_Az(isp_new, Az, M, 0);

	return;
}



