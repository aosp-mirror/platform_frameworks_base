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
*       File: util.c                                                   *
*                                                                      *
*       Description: Reset and Copy buffer                             *
*                                                                      *
************************************************************************/

#include "typedef.h"
#include "basic_op.h"

/***********************************************************************
* Function:  Set_zero()                                             *
* Description: Set vector x[] to zero                               *
************************************************************************/

void Set_zero(
		Word16 x[],                           /* (o)    : vector to clear     */
		Word16 L                              /* (i)    : length of vector    */
	     )
{
	Word32 num = (Word32)L;
	do{
		*x++ = 0;
	}while(--num !=0);
}


/*********************************************************************
* Function: Copy()                                                   *
*                                                                    *
* Description: Copy vector x[] to y[]                                *
*********************************************************************/

void Copy(
		Word16 x[],                           /* (i)   : input vector   */
		Word16 y[],                           /* (o)   : output vector  */
		Word16 L                              /* (i)   : vector length  */
	 )
{
	Word32	temp1,temp2,num;
	if(L&1)
	{
		temp1 = *x++;
		*y++ = temp1;
	}
	num = (Word32)(L>>1);
	temp1 = *x++;
	temp2 = *x++;
	do{
		*y++ = temp1;
		*y++ = temp2;
		temp1 = *x++;
		temp2 = *x++;
	}while(--num!=0);
}



