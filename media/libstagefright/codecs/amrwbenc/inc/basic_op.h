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


#ifndef __BASIC_OP_H__
#define __BASIC_OP_H__

#include <stdio.h>
#include <stdlib.h>
#include "typedef.h"

#define MAX_32 (Word32)0x7fffffffL
#define MIN_32 (Word32)0x80000000L

#define MAX_16 (Word16)+32767	/* 0x7fff */
#define MIN_16 (Word16)-32768	/* 0x8000 */


#define  static_vo  static __inline

#define saturate(L_var1) (((L_var1) > 0X00007fffL) ? (MAX_16): (((L_var1) < (Word32) 0xffff8000L) ? (MIN_16): ((L_var1) & 0xffff)))

#define abs_s(x)       ((Word16)(((x) != MIN_16) ? (((x) >= 0) ? (x) : (-(x))) : MAX_16))  /* Short abs,           1   */
#define L_deposit_h(x) (((Word32)(x)) << 16)                                               /* 16 bit var1 -> MSB,     2 */
#define L_deposit_l(x) ((Word32)(x))                                                       /* 16 bit var1 -> LSB,     2 */
#define L_abs(x) (((x) != MIN_32) ? (((x) >= 0) ? (x) : (-(x))) : MAX_32)                  /* Long abs,              3*/
#define negate(var1) ((Word16)(((var1) == MIN_16) ? MAX_16 : (-(var1))))                   /* Short negate,        1*/
#define L_negate(L_var1) (((L_var1) == (MIN_32)) ? (MAX_32) : (-(L_var1)))                 /* Long negate,     2*/


#define extract_h(a)			((Word16)(a >> 16))
#define extract_l(x)            	(Word16)((x))
#define add1(a,b)			(a + b)
#define vo_L_msu(a,b,c)			( a - (( b * c ) << 1) )
#define vo_mult32(a, b)         ((a) * (b))
#define vo_mult(a,b)			(( a * b ) >> 15 )
#define	vo_L_mult(a,b)	    		(((a) * (b)) << 1)
#define vo_shr_r(var1, var2)   		((var1+((Word16)(1L<<(var2-1))))>>var2)
#define vo_sub(a,b)			(a - b)
#define vo_L_deposit_h(a)		((Word32)((a) << 16))
#define vo_round(a)			((a + 0x00008000) >> 16)
#define vo_extract_l(a)			((Word16)(a))
#define vo_L_add(a,b)			(a + b)
#define vo_L_sub(a,b)			(a - b)
#define vo_mult_r(a,b)			((( a * b ) + 0x4000 ) >> 15 )
#define vo_negate(a)		        (-a)
#define vo_L_shr_r(L_var1, var2)        ((L_var1+((Word32)(1L<<(var2-1))))>>var2)


/*___________________________________________________________________________
|                                                                           |
|   Prototypes for basic arithmetic operators                               |
|___________________________________________________________________________|
*/
static_vo Word16 add (Word16 var1, Word16 var2);				/* Short add,1 */
static_vo Word16 sub (Word16 var1, Word16 var2);				/* Short sub,1 */
static_vo Word16 shl (Word16 var1, Word16 var2);                                /* Short shift left,    1   */
static_vo Word16 shr (Word16 var1, Word16 var2);                                /* Short shift right,   1   */
static_vo Word16 mult (Word16 var1, Word16 var2);                               /* Short mult,          1   */
static_vo Word32 L_mult (Word16 var1, Word16 var2);                             /* Long mult,           1   */
static_vo Word16 voround (Word32 L_var1);                                       /* Round,               1   */
static_vo Word32 L_mac (Word32 L_var3, Word16 var1, Word16 var2);            	/* Mac,  1  */
static_vo Word32 L_msu (Word32 L_var3, Word16 var1, Word16 var2);   		/* Msu,  1  */
static_vo Word32 L_add (Word32 L_var1, Word32 L_var2);   		 	/* Long add,        2 */
static_vo Word32 L_sub (Word32 L_var1, Word32 L_var2);   			/* Long sub,        2 */
static_vo Word16 mult_r (Word16 var1, Word16 var2);      		 	/* Mult with round, 2 */
static_vo Word32 L_shl2(Word32 L_var1, Word16 var2);             		/* var2 > 0*/
static_vo Word32 L_shl (Word32 L_var1, Word16 var2);    	 	 	/* Long shift left, 2 */
static_vo Word32 L_shr (Word32 L_var1, Word16 var2);    	 	 	/* Long shift right, 2*/
static_vo Word32 L_shr_r (Word32 L_var1, Word16 var2); 				/* Long shift right with round,  3   */
static_vo Word16 norm_s (Word16 var1);             				/* Short norm,           15  */
static_vo Word16 div_s (Word16 var1, Word16 var2); 				/* Short division,       18  */
static_vo Word16 norm_l (Word32 L_var1);           				/* Long norm,            30  */

/*___________________________________________________________________________
|                                                                           |
|   Functions                                                               |
|___________________________________________________________________________|
*/
/*___________________________________________________________________________
|                                                                           |
|   Function Name : add                                                     |
|                                                                           |
|   Purpose :                                                               |
|                                                                           |
|    Performs the addition (var1+var2) with overflow control and saturation;|
|    the 16 bit result is set at +32767 when overflow occurs or at -32768   |
|    when underflow occurs.                                                 |
|                                                                           |
|   Complexity weight : 1                                                   |
|                                                                           |
|   Inputs :                                                                |
|                                                                           |
|    var1                                                                   |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var1 <= 0x0000 7fff.                   |
|                                                                           |
|    var2                                                                   |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var1 <= 0x0000 7fff.                   |
|                                                                           |
|   Outputs :                                                               |
|                                                                           |
|    none                                                                   |
|                                                                           |
|   Return Value :                                                          |
|                                                                           |
|    var_out                                                                |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var_out <= 0x0000 7fff.                |
|___________________________________________________________________________|
*/
static_vo Word16 add (Word16 var1, Word16 var2)
{
	Word16 var_out;
	Word32 L_sum;
	L_sum = (Word32) var1 + var2;
	var_out = saturate (L_sum);
	return (var_out);
}

/*___________________________________________________________________________
|                                                                           |
|   Function Name : sub                                                     |
|                                                                           |
|   Purpose :                                                               |
|                                                                           |
|    Performs the subtraction (var1+var2) with overflow control and satu-   |
|    ration; the 16 bit result is set at +32767 when overflow occurs or at  |
|    -32768 when underflow occurs.                                          |
|                                                                           |
|   Complexity weight : 1                                                   |
|                                                                           |
|   Inputs :                                                                |
|                                                                           |
|    var1                                                                   |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var1 <= 0x0000 7fff.                   |
|                                                                           |
|    var2                                                                   |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var1 <= 0x0000 7fff.                   |
|                                                                           |
|   Outputs :                                                               |
|                                                                           |
|    none                                                                   |
|                                                                           |
|   Return Value :                                                          |
|                                                                           |
|    var_out                                                                |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var_out <= 0x0000 7fff.                |
|___________________________________________________________________________|
*/

static_vo Word16 sub (Word16 var1, Word16 var2)
{
	Word16 var_out;
	Word32 L_diff;
	L_diff = (Word32) var1 - var2;
	var_out = saturate (L_diff);
	return (var_out);
}

/*___________________________________________________________________________
|                                                                           |
|   Function Name : shl                                                     |
|                                                                           |
|   Purpose :                                                               |
|                                                                           |
|   Arithmetically shift the 16 bit input var1 left var2 positions.Zero fill|
|   the var2 LSB of the result. If var2 is negative, arithmetically shift   |
|   var1 right by -var2 with sign extension. Saturate the result in case of |
|   underflows or overflows.                                                |
|                                                                           |
|   Complexity weight : 1                                                   |
|                                                                           |
|   Inputs :                                                                |
|                                                                           |
|    var1                                                                   |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var1 <= 0x0000 7fff.                   |
|                                                                           |
|    var2                                                                   |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var1 <= 0x0000 7fff.                   |
|                                                                           |
|   Outputs :                                                               |
|                                                                           |
|    none                                                                   |
|                                                                           |
|   Return Value :                                                          |
|                                                                           |
|    var_out                                                                |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var_out <= 0x0000 7fff.                |
|___________________________________________________________________________|
*/

static_vo Word16 shl (Word16 var1, Word16 var2)
{
	Word16 var_out;
	Word32 result;
	if (var2 < 0)
	{
		if (var2 < -16)
			var2 = -16;
		var_out = var1 >> ((Word16)-var2);
	}
	else
	{
		result = (Word32) var1 *((Word32) 1 << var2);
		if ((var2 > 15 && var1 != 0) || (result != (Word32) ((Word16) result)))
		{
			var_out = (Word16)((var1 > 0) ? MAX_16 : MIN_16);
		}
		else
		{
			var_out = extract_l (result);
		}
	}
	return (var_out);
}

/*___________________________________________________________________________
|                                                                           |
|   Function Name : shr                                                     |
|                                                                           |
|   Purpose :                                                               |
|                                                                           |
|   Arithmetically shift the 16 bit input var1 right var2 positions with    |
|   sign extension. If var2 is negative, arithmetically shift var1 left by  |
|   -var2 with sign extension. Saturate the result in case of underflows or |
|   overflows.                                                              |
|                                                                           |
|   Complexity weight : 1                                                   |
|                                                                           |
|   Inputs :                                                                |
|                                                                           |
|    var1                                                                   |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var1 <= 0x0000 7fff.                   |
|                                                                           |
|    var2                                                                   |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var1 <= 0x0000 7fff.                   |
|                                                                           |
|   Outputs :                                                               |
|                                                                           |
|    none                                                                   |
|                                                                           |
|   Return Value :                                                          |
|                                                                           |
|    var_out                                                                |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var_out <= 0x0000 7fff.                |
|___________________________________________________________________________|
*/

static_vo Word16 shr (Word16 var1, Word16 var2)
{
	Word16 var_out;
	if (var2 < 0)
	{
		if (var2 < -16)
			var2 = -16;
		var_out = shl(var1, (Word16)-var2);
	}
	else
	{
		if (var2 >= 15)
		{
			var_out = (Word16)((var1 < 0) ? -1 : 0);
		}
		else
		{
			if (var1 < 0)
			{
				var_out = (Word16)(~((~var1) >> var2));
			}
			else
			{
				var_out = (Word16)(var1 >> var2);
			}
		}
	}
	return (var_out);
}

/*___________________________________________________________________________
|                                                                           |
|   Function Name : mult                                                    |
|                                                                           |
|   Purpose :                                                               |
|                                                                           |
|    Performs the multiplication of var1 by var2 and gives a 16 bit result  |
|    which is scaled i.e.:                                                  |
|             mult(var1,var2) = extract_l(L_shr((var1 times var2),15)) and  |
|             mult(-32768,-32768) = 32767.                                  |
|                                                                           |
|   Complexity weight : 1                                                   |
|                                                                           |
|   Inputs :                                                                |
|                                                                           |
|    var1                                                                   |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var1 <= 0x0000 7fff.                   |
|                                                                           |
|    var2                                                                   |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var1 <= 0x0000 7fff.                   |
|                                                                           |
|   Outputs :                                                               |
|                                                                           |
|    none                                                                   |
|                                                                           |
|   Return Value :                                                          |
|                                                                           |
|    var_out                                                                |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var_out <= 0x0000 7fff.                |
|___________________________________________________________________________|
*/

static_vo Word16 mult (Word16 var1, Word16 var2)
{
	Word16 var_out;
	Word32 L_product;
	L_product = (Word32) var1 *(Word32) var2;
	L_product = (L_product & (Word32) 0xffff8000L) >> 15;
	if (L_product & (Word32) 0x00010000L)
		L_product = L_product | (Word32) 0xffff0000L;
	var_out = saturate (L_product);
	return (var_out);
}

/*___________________________________________________________________________
|                                                                           |
|   Function Name : L_mult                                                  |
|                                                                           |
|   Purpose :                                                               |
|                                                                           |
|   L_mult is the 32 bit result of the multiplication of var1 times var2    |
|   with one shift left i.e.:                                               |
|        L_mult(var1,var2) = L_shl((var1 times var2),1) and                   |
|        L_mult(-32768,-32768) = 2147483647.                                |
|                                                                           |
|   Complexity weight : 1                                                   |
|                                                                           |
|   Inputs :                                                                |
|                                                                           |
|    var1                                                                   |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var1 <= 0x0000 7fff.                   |
|                                                                           |
|    var2                                                                   |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var1 <= 0x0000 7fff.                   |
|                                                                           |
|   Outputs :                                                               |
|                                                                           |
|    none                                                                   |
|                                                                           |
|   Return Value :                                                          |
|                                                                           |
|    L_var_out                                                              |
|             32 bit long signed integer (Word32) whose value falls in the  |
|             range : 0x8000 0000 <= L_var_out <= 0x7fff ffff.              |
|___________________________________________________________________________|
*/

static_vo Word32 L_mult (Word16 var1, Word16 var2)
{
	Word32 L_var_out;
	L_var_out = (Word32) var1 *(Word32) var2;
	if (L_var_out != (Word32) 0x40000000L)
	{
		L_var_out *= 2;
	}
	else
	{
		L_var_out = MAX_32;
	}
	return (L_var_out);
}

/*___________________________________________________________________________
|                                                                           |
|   Function Name : round                                                   |
|                                                                           |
|   Purpose :                                                               |
|                                                                           |
|   Round the lower 16 bits of the 32 bit input number into the MS 16 bits  |
|   with saturation. Shift the resulting bits right by 16 and return the 16 |
|   bit number:                                                             |
|               round(L_var1) = extract_h(L_add(L_var1,32768))              |
|                                                                           |
|   Complexity weight : 1                                                   |
|                                                                           |
|   Inputs :                                                                |
|                                                                           |
|    L_var1                                                                 |
|             32 bit long signed integer (Word32 ) whose value falls in the |
|             range : 0x8000 0000 <= L_var1 <= 0x7fff ffff.                 |
|                                                                           |
|   Outputs :                                                               |
|                                                                           |
|    none                                                                   |
|                                                                           |
|   Return Value :                                                          |
|                                                                           |
|    var_out                                                                |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var_out <= 0x0000 7fff.                |
|___________________________________________________________________________|
*/

static_vo Word16 voround (Word32 L_var1)
{
	Word16 var_out;
	Word32 L_rounded;
	L_rounded = L_add (L_var1, (Word32) 0x00008000L);
	var_out = extract_h (L_rounded);
	return (var_out);
}

/*___________________________________________________________________________
|                                                                           |
|   Function Name : L_mac                                                   |
|                                                                           |
|   Purpose :                                                               |
|                                                                           |
|   Multiply var1 by var2 and shift the result left by 1. Add the 32 bit    |
|   result to L_var3 with saturation, return a 32 bit result:               |
|        L_mac(L_var3,var1,var2) = L_add(L_var3,L_mult(var1,var2)).         |
|                                                                           |
|   Complexity weight : 1                                                   |
|                                                                           |
|   Inputs :                                                                |
|                                                                           |
|    L_var3   32 bit long signed integer (Word32) whose value falls in the  |
|             range : 0x8000 0000 <= L_var3 <= 0x7fff ffff.                 |
|                                                                           |
|    var1                                                                   |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var1 <= 0x0000 7fff.                   |
|                                                                           |
|    var2                                                                   |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var1 <= 0x0000 7fff.                   |
|                                                                           |
|   Outputs :                                                               |
|                                                                           |
|    none                                                                   |
|                                                                           |
|   Return Value :                                                          |
|                                                                           |
|    L_var_out                                                              |
|             32 bit long signed integer (Word32) whose value falls in the  |
|             range : 0x8000 0000 <= L_var_out <= 0x7fff ffff.              |
|___________________________________________________________________________|
*/

static_vo Word32 L_mac (Word32 L_var3, Word16 var1, Word16 var2)
{
	Word32 L_var_out;
	Word32 L_product;
	L_product = ((var1 * var2) << 1);
	L_var_out = L_add (L_var3, L_product);
	return (L_var_out);
}

/*___________________________________________________________________________
|                                                                           |
|   Function Name : L_msu                                                   |
|                                                                           |
|   Purpose :                                                               |
|                                                                           |
|   Multiply var1 by var2 and shift the result left by 1. Subtract the 32   |
|   bit result to L_var3 with saturation, return a 32 bit result:           |
|        L_msu(L_var3,var1,var2) = L_sub(L_var3,L_mult(var1,var2)).         |
|                                                                           |
|   Complexity weight : 1                                                   |
|                                                                           |
|   Inputs :                                                                |
|                                                                           |
|    L_var3   32 bit long signed integer (Word32) whose value falls in the  |
|             range : 0x8000 0000 <= L_var3 <= 0x7fff ffff.                 |
|                                                                           |
|    var1                                                                   |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var1 <= 0x0000 7fff.                   |
|                                                                           |
|    var2                                                                   |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var1 <= 0x0000 7fff.                   |
|                                                                           |
|   Outputs :                                                               |
|                                                                           |
|    none                                                                   |
|                                                                           |
|   Return Value :                                                          |
|                                                                           |
|    L_var_out                                                              |
|             32 bit long signed integer (Word32) whose value falls in the  |
|             range : 0x8000 0000 <= L_var_out <= 0x7fff ffff.              |
|___________________________________________________________________________|
*/

static_vo Word32 L_msu (Word32 L_var3, Word16 var1, Word16 var2)
{
	Word32 L_var_out;
	Word32 L_product;
	L_product = (var1 * var2)<<1;
	L_var_out = L_sub (L_var3, L_product);
	return (L_var_out);
}

/*___________________________________________________________________________
|                                                                           |
|   Function Name : L_add                                                   |
|                                                                           |
|   Purpose :                                                               |
|                                                                           |
|   32 bits addition of the two 32 bits variables (L_var1+L_var2) with      |
|   overflow control and saturation; the result is set at +2147483647 when  |
|   overflow occurs or at -2147483648 when underflow occurs.                |
|                                                                           |
|   Complexity weight : 2                                                   |
|                                                                           |
|   Inputs :                                                                |
|                                                                           |
|    L_var1   32 bit long signed integer (Word32) whose value falls in the  |
|             range : 0x8000 0000 <= L_var3 <= 0x7fff ffff.                 |
|                                                                           |
|    L_var2   32 bit long signed integer (Word32) whose value falls in the  |
|             range : 0x8000 0000 <= L_var3 <= 0x7fff ffff.                 |
|                                                                           |
|   Outputs :                                                               |
|                                                                           |
|    none                                                                   |
|                                                                           |
|   Return Value :                                                          |
|                                                                           |
|    L_var_out                                                              |
|             32 bit long signed integer (Word32) whose value falls in the  |
|             range : 0x8000 0000 <= L_var_out <= 0x7fff ffff.              |
|___________________________________________________________________________|
*/

static_vo Word32 L_add (Word32 L_var1, Word32 L_var2)
{
	Word32 L_var_out;
	L_var_out = L_var1 + L_var2;
	if (((L_var1 ^ L_var2) & MIN_32) == 0)
	{
		if ((L_var_out ^ L_var1) & MIN_32)
		{
			L_var_out = (L_var1 < 0) ? MIN_32 : MAX_32;
		}
	}
	return (L_var_out);
}

/*___________________________________________________________________________
|                                                                           |
|   Function Name : L_sub                                                   |
|                                                                           |
|   Purpose :                                                               |
|                                                                           |
|   32 bits subtraction of the two 32 bits variables (L_var1-L_var2) with   |
|   overflow control and saturation; the result is set at +2147483647 when  |
|   overflow occurs or at -2147483648 when underflow occurs.                |
|                                                                           |
|   Complexity weight : 2                                                   |
|                                                                           |
|   Inputs :                                                                |
|                                                                           |
|    L_var1   32 bit long signed integer (Word32) whose value falls in the  |
|             range : 0x8000 0000 <= L_var3 <= 0x7fff ffff.                 |
|                                                                           |
|    L_var2   32 bit long signed integer (Word32) whose value falls in the  |
|             range : 0x8000 0000 <= L_var3 <= 0x7fff ffff.                 |
|                                                                           |
|   Outputs :                                                               |
|                                                                           |
|    none                                                                   |
|                                                                           |
|   Return Value :                                                          |
|                                                                           |
|    L_var_out                                                              |
|             32 bit long signed integer (Word32) whose value falls in the  |
|             range : 0x8000 0000 <= L_var_out <= 0x7fff ffff.              |
|___________________________________________________________________________|
*/

static_vo Word32 L_sub (Word32 L_var1, Word32 L_var2)
{
	Word32 L_var_out;
	L_var_out = L_var1 - L_var2;
	if (((L_var1 ^ L_var2) & MIN_32) != 0)
	{
		if ((L_var_out ^ L_var1) & MIN_32)
		{
			L_var_out = (L_var1 < 0L) ? MIN_32 : MAX_32;
		}
	}
	return (L_var_out);
}


/*___________________________________________________________________________
|                                                                           |
|   Function Name : mult_r                                                  |
|                                                                           |
|   Purpose :                                                               |
|                                                                           |
|   Same as mult with rounding, i.e.:                                       |
|     mult_r(var1,var2) = extract_l(L_shr(((var1 * var2) + 16384),15)) and  |
|     mult_r(-32768,-32768) = 32767.                                        |
|                                                                           |
|   Complexity weight : 2                                                   |
|                                                                           |
|   Inputs :                                                                |
|                                                                           |
|    var1                                                                   |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var1 <= 0x0000 7fff.                   |
|                                                                           |
|    var2                                                                   |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var1 <= 0x0000 7fff.                   |
|                                                                           |
|   Outputs :                                                               |
|                                                                           |
|    none                                                                   |
|                                                                           |
|   Return Value :                                                          |
|                                                                           |
|    var_out                                                                |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var_out <= 0x0000 7fff.                |
|___________________________________________________________________________|
*/

static_vo Word16 mult_r (Word16 var1, Word16 var2)
{
	Word16 var_out;
	Word32 L_product_arr;
	L_product_arr = (Word32) var1 *(Word32) var2;       /* product */
	L_product_arr += (Word32) 0x00004000L;      /* round */
	L_product_arr &= (Word32) 0xffff8000L;
	L_product_arr >>= 15;       /* shift */
	if (L_product_arr & (Word32) 0x00010000L)   /* sign extend when necessary */
	{
		L_product_arr |= (Word32) 0xffff0000L;
	}
	var_out = saturate (L_product_arr);
	return (var_out);
}

/*___________________________________________________________________________
|                                                                           |
|   Function Name : L_shl                                                   |
|                                                                           |
|   Purpose :                                                               |
|                                                                           |
|   Arithmetically shift the 32 bit input L_var1 left var2 positions. Zero  |
|   fill the var2 LSB of the result. If var2 is negative, arithmetically    |
|   shift L_var1 right by -var2 with sign extension. Saturate the result in |
|   case of underflows or overflows.                                        |
|                                                                           |
|   Complexity weight : 2                                                   |
|                                                                           |
|   Inputs :                                                                |
|                                                                           |
|    L_var1   32 bit long signed integer (Word32) whose value falls in the  |
|             range : 0x8000 0000 <= L_var3 <= 0x7fff ffff.                 |
|                                                                           |
|    var2                                                                   |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var1 <= 0x0000 7fff.                   |
|                                                                           |
|   Outputs :                                                               |
|                                                                           |
|    none                                                                   |
|                                                                           |
|   Return Value :                                                          |
|                                                                           |
|    L_var_out                                                              |
|             32 bit long signed integer (Word32) whose value falls in the  |
|             range : 0x8000 0000 <= L_var_out <= 0x7fff ffff.              |
|___________________________________________________________________________|
*/

static_vo Word32 L_shl (Word32 L_var1, Word16 var2)
{
	Word32 L_var_out = 0L;
	if (var2 <= 0)
	{
		if (var2 < -32)
			var2 = -32;
		L_var_out = (L_var1 >> (Word16)-var2);
	}
	else
	{
		for (; var2 > 0; var2--)
		{
			if (L_var1 > (Word32) 0X3fffffffL)
			{
				L_var_out = MAX_32;
				break;
			}
			else
			{
				if (L_var1 < (Word32) 0xc0000000L)
				{
					//Overflow = 1;
					L_var_out = MIN_32;
					break;
				}
			}
			L_var1 *= 2;
			L_var_out = L_var1;
		}
	}
	return (L_var_out);
}

static_vo Word32 L_shl2(Word32 L_var1, Word16 var2)
{
	Word32 L_var_out = 0L;

	for (; var2 > 0; var2--)
	{
		if (L_var1 > (Word32) 0X3fffffffL)
		{
			L_var_out = MAX_32;
			break;
		}
		else
		{
			if (L_var1 < (Word32) 0xc0000000L)
			{
				L_var_out = MIN_32;
				break;
			}
		}
		L_var1 <<=1 ;
		L_var_out = L_var1;
	}
	return (L_var_out);
}

/*___________________________________________________________________________
|                                                                           |
|   Function Name : L_shr                                                   |
|                                                                           |
|   Purpose :                                                               |
|                                                                           |
|   Arithmetically shift the 32 bit input L_var1 right var2 positions with  |
|   sign extension. If var2 is negative, arithmetically shift L_var1 left   |
|   by -var2 and zero fill the -var2 LSB of the result. Saturate the result |
|   in case of underflows or overflows.                                     |
|                                                                           |
|   Complexity weight : 2                                                   |
|                                                                           |
|   Inputs :                                                                |
|                                                                           |
|    L_var1   32 bit long signed integer (Word32) whose value falls in the  |
|             range : 0x8000 0000 <= L_var3 <= 0x7fff ffff.                 |
|                                                                           |
|    var2                                                                   |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var1 <= 0x0000 7fff.                   |
|                                                                           |
|   Outputs :                                                               |
|                                                                           |
|    none                                                                   |
|                                                                           |
|   Return Value :                                                          |
|                                                                           |
|    L_var_out                                                              |
|             32 bit long signed integer (Word32) whose value falls in the  |
|             range : 0x8000 0000 <= L_var_out <= 0x7fff ffff.              |
|___________________________________________________________________________|
*/

static_vo Word32 L_shr (Word32 L_var1, Word16 var2)
{
	Word32 L_var_out;
	if (var2 < 0)
	{
		if (var2 < -32)
			var2 = -32;
		L_var_out = L_shl2(L_var1, (Word16)-var2);
	}
	else
	{
		if (var2 >= 31)
		{
			L_var_out = (L_var1 < 0L) ? -1 : 0;
		}
		else
		{
			if (L_var1 < 0)
			{
				L_var_out = ~((~L_var1) >> var2);
			}
			else
			{
				L_var_out = L_var1 >> var2;
			}
		}
	}
	return (L_var_out);
}

/*___________________________________________________________________________
|                                                                           |
|   Function Name : L_shr_r                                                 |
|                                                                           |
|   Purpose :                                                               |
|                                                                           |
|   Same as L_shr(L_var1,var2) but with rounding. Saturate the result in    |
|   case of underflows or overflows :                                       |
|    - If var2 is greater than zero :                                       |
|          if (L_sub(L_shl(L_shr(L_var1,var2),1),L_shr(L_var1,sub(var2,1))))|
|          is equal to zero                                                 |
|                     then                                                  |
|                     L_shr_r(L_var1,var2) = L_shr(L_var1,var2)             |
|                     else                                                  |
|                     L_shr_r(L_var1,var2) = L_add(L_shr(L_var1,var2),1)    |
|    - If var2 is less than or equal to zero :                              |
|                     L_shr_r(L_var1,var2) = L_shr(L_var1,var2).            |
|                                                                           |
|   Complexity weight : 3                                                   |
|                                                                           |
|   Inputs :                                                                |
|                                                                           |
|    L_var1                                                                 |
|             32 bit long signed integer (Word32) whose value falls in the  |
|             range : 0x8000 0000 <= var1 <= 0x7fff ffff.                   |
|                                                                           |
|    var2                                                                   |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var1 <= 0x0000 7fff.                   |
|                                                                           |
|   Outputs :                                                               |
|                                                                           |
|    none                                                                   |
|                                                                           |
|   Return Value :                                                          |
|                                                                           |
|    L_var_out                                                              |
|             32 bit long signed integer (Word32) whose value falls in the  |
|             range : 0x8000 0000 <= var_out <= 0x7fff ffff.                |
|___________________________________________________________________________|
*/

static_vo Word32 L_shr_r (Word32 L_var1, Word16 var2)
{
	Word32 L_var_out;
	if (var2 > 31)
	{
		L_var_out = 0;
	}
	else
	{
		L_var_out = L_shr (L_var1, var2);
		if (var2 > 0)
		{
			if ((L_var1 & ((Word32) 1 << (var2 - 1))) != 0)
			{
				L_var_out++;
			}
		}
	}
	return (L_var_out);
}

/*___________________________________________________________________________
|                                                                           |
|   Function Name : norm_s                                                  |
|                                                                           |
|   Purpose :                                                               |
|                                                                           |
|   Produces the number of left shift needed to normalize the 16 bit varia- |
|   ble var1 for positive values on the interval with minimum of 16384 and  |
|   maximum of 32767, and for negative values on the interval with minimum  |
|   of -32768 and maximum of -16384; in order to normalize the result, the  |
|   following operation must be done :                                      |
|                    norm_var1 = shl(var1,norm_s(var1)).                    |
|                                                                           |
|   Complexity weight : 15                                                  |
|                                                                           |
|   Inputs :                                                                |
|                                                                           |
|    var1                                                                   |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0xffff 8000 <= var1 <= 0x0000 7fff.                   |
|                                                                           |
|   Outputs :                                                               |
|                                                                           |
|    none                                                                   |
|                                                                           |
|   Return Value :                                                          |
|                                                                           |
|    var_out                                                                |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0x0000 0000 <= var_out <= 0x0000 000f.                |
|___________________________________________________________________________|
*/

static_vo Word16 norm_s (Word16 var1)
{
	Word16 var_out = 0;
	if (var1 == 0)
	{
		var_out = 0;
	}
	else
	{
		if (var1 == -1)
		{
			var_out = 15;
		}
		else
		{
			if (var1 < 0)
			{
				var1 = (Word16)~var1;
			}
			for (var_out = 0; var1 < 0x4000; var_out++)
			{
				var1 <<= 1;
			}
		}
	}
	return (var_out);
}

/*___________________________________________________________________________
|                                                                           |
|   Function Name : div_s                                                   |
|                                                                           |
|   Purpose :                                                               |
|                                                                           |
|   Produces a result which is the fractional integer division of var1  by  |
|   var2; var1 and var2 must be positive and var2 must be greater or equal  |
|   to var1; the result is positive (leading bit equal to 0) and truncated  |
|   to 16 bits.                                                             |
|   If var1 = var2 then div(var1,var2) = 32767.                             |
|                                                                           |
|   Complexity weight : 18                                                  |
|                                                                           |
|   Inputs :                                                                |
|                                                                           |
|    var1                                                                   |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0x0000 0000 <= var1 <= var2 and var2 != 0.            |
|                                                                           |
|    var2                                                                   |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : var1 <= var2 <= 0x0000 7fff and var2 != 0.            |
|                                                                           |
|   Outputs :                                                               |
|                                                                           |
|    none                                                                   |
|                                                                           |
|   Return Value :                                                          |
|                                                                           |
|    var_out                                                                |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0x0000 0000 <= var_out <= 0x0000 7fff.                |
|             It's a Q15 value (point between b15 and b14).                 |
|___________________________________________________________________________|
*/

static_vo Word16 div_s (Word16 var1, Word16 var2)
{
	Word16 var_out = 0;
	Word16 iteration;
	Word32 L_num;
	Word32 L_denom;
	if ((var1 < 0) || (var2 < 0))
	{
		var_out = MAX_16;
		return var_out;
	}
	if (var2 == 0)
	{
		var_out = MAX_16;
		return var_out;
	}
	if (var1 == 0)
	{
		var_out = 0;
	}
	else
	{
		if (var1 == var2)
		{
			var_out = MAX_16;
		}
		else
		{
			L_num = L_deposit_l (var1);
			L_denom = L_deposit_l(var2);
			for (iteration = 0; iteration < 15; iteration++)
			{
				var_out <<= 1;
				L_num <<= 1;
				if (L_num >= L_denom)
				{
					L_num -= L_denom;
					var_out += 1;
				}
			}
		}
	}
	return (var_out);
}

/*___________________________________________________________________________
|                                                                           |
|   Function Name : norm_l                                                  |
|                                                                           |
|   Purpose :                                                               |
|                                                                           |
|   Produces the number of left shifts needed to normalize the 32 bit varia-|
|   ble L_var1 for positive values on the interval with minimum of          |
|   1073741824 and maximum of 2147483647, and for negative values on the in-|
|   terval with minimum of -2147483648 and maximum of -1073741824; in order |
|   to normalize the result, the following operation must be done :         |
|                   norm_L_var1 = L_shl(L_var1,norm_l(L_var1)).             |
|                                                                           |
|   Complexity weight : 30                                                  |
|                                                                           |
|   Inputs :                                                                |
|                                                                           |
|    L_var1                                                                 |
|             32 bit long signed integer (Word32) whose value falls in the  |
|             range : 0x8000 0000 <= var1 <= 0x7fff ffff.                   |
|                                                                           |
|   Outputs :                                                               |
|                                                                           |
|    none                                                                   |
|                                                                           |
|   Return Value :                                                          |
|                                                                           |
|    var_out                                                                |
|             16 bit short signed integer (Word16) whose value falls in the |
|             range : 0x0000 0000 <= var_out <= 0x0000 001f.                |
|___________________________________________________________________________|
*/

static_vo Word16 norm_l (Word32 L_var1)
{
	Word16 var_out = 0;
	if (L_var1 != 0)
	{
		var_out = 31;
		if (L_var1 != (Word32) 0xffffffffL)
		{
			L_var1 ^= (L_var1 >>31);
			for (var_out = 0; L_var1 < (Word32) 0x40000000L; var_out++)
			{
				L_var1 <<= 1;
			}
		}
	}
	return (var_out);
}

#endif //__BASIC_OP_H__

