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
/*******************************************************************************
	File:		typedefs.h

	Content:	type defined or const defined

*******************************************************************************/

#ifndef typedefs_h
#define typedefs_h "$Id $"

#ifndef CHAR_BIT
#define CHAR_BIT      8         /* number of bits in a char */
#endif

#ifndef VOAAC_SHRT_MAX
#define VOAAC_SHRT_MAX    (32767)        /* maximum (signed) short value */
#endif

#ifndef VOAAC_SHRT_MIN
#define VOAAC_SHRT_MIN    (-32768)        /* minimum (signed) short value */
#endif

/* Define NULL pointer value */
#ifndef NULL
#ifdef __cplusplus
#define NULL    0
#else
#define NULL    ((void *)0)
#endif
#endif

#ifndef assert
#define assert(_Expression)     ((void)0)
#endif

#ifdef LINUX
#define __inline static __inline__
#endif

#define INT_BITS   32
/*
********************************************************************************
*                         DEFINITION OF CONSTANTS
********************************************************************************
*/
/*
 ********* define char type
 */
typedef char Char;

/*
 ********* define 8 bit signed/unsigned types & constants
 */
typedef signed char Word8;
typedef unsigned char UWord8;
/*
 ********* define 16 bit signed/unsigned types & constants
 */
typedef short Word16;
typedef unsigned short UWord16;

/*
 ********* define 32 bit signed/unsigned types & constants
 */
typedef long Word32;
typedef unsigned long UWord32;



#ifdef LINUX
typedef long long Word64;
typedef unsigned long long UWord64;
#else
typedef __int64 Word64;
typedef unsigned __int64 UWord64;
#endif

#ifndef min
#define min(a,b) ( a < b ? a : b)
#endif

#ifndef max
#define max(a,b) ( a > b ? a : b)
#endif

#ifdef ARM_INASM
#ifdef ARMV5_INASM
#define ARMV5E_INASM	1
#endif
#define ARMV4_INASM		1
#endif

#if ARMV4_INASM
	#define ARMV5TE_SAT           1
    #define ARMV5TE_ADD           1
    #define ARMV5TE_SUB           1
	#define ARMV5TE_SHL           1
    #define ARMV5TE_SHR           1
	#define ARMV5TE_L_SHL         1
    #define ARMV5TE_L_SHR         1
#endif//ARMV4
#if ARMV5E_INASM
    #define ARMV5TE_L_ADD         1
    #define ARMV5TE_L_SUB         1
    #define ARMV5TE_L_MULT        1
    #define ARMV5TE_L_MAC         1
    #define ARMV5TE_L_MSU         1


    #define ARMV5TE_DIV_S         1
    #define ARMV5TE_ROUND         1
    #define ARMV5TE_MULT          1

    #define ARMV5TE_NORM_S        1
    #define ARMV5TE_NORM_L        1
	#define ARMV5TE_L_MPY_LS	  1
#endif
#if ARMV6_INASM
    #undef  ARMV5TE_ADD
    #define ARMV5TE_ADD           0
    #undef  ARMV5TE_SUB
    #define ARMV5TE_SUB           0
    #define ARMV6_SAT             1
#endif

//basic operation functions optimization flags
#define SATRUATE_IS_INLINE              1   //define saturate as inline function
#define SHL_IS_INLINE                   1  //define shl as inline function
#define SHR_IS_INLINE                   1   //define shr as inline function
#define L_MULT_IS_INLINE                1   //define L_mult as inline function
#define L_MSU_IS_INLINE                 1   //define L_msu as inline function
#define L_SUB_IS_INLINE                 1   //define L_sub as inline function
#define L_SHL_IS_INLINE                 1   //define L_shl as inline function
#define L_SHR_IS_INLINE                 1   //define L_shr as inline function
#define ADD_IS_INLINE                   1   //define add as inline function //add, inline is the best
#define SUB_IS_INLINE                   1   //define sub as inline function //sub, inline is the best
#define DIV_S_IS_INLINE                 1   //define div_s as inline function
#define MULT_IS_INLINE                  1   //define mult as inline function
#define NORM_S_IS_INLINE                1   //define norm_s as inline function
#define NORM_L_IS_INLINE                1   //define norm_l as inline function
#define ROUND_IS_INLINE                 1   //define round as inline function
#define L_MAC_IS_INLINE                 1   //define L_mac as inline function
#define L_ADD_IS_INLINE                 1   //define L_add as inline function
#define EXTRACT_H_IS_INLINE             1   //define extract_h as inline function
#define EXTRACT_L_IS_INLINE             1   //define extract_l as inline function        //???
#define MULT_R_IS_INLINE                1   //define mult_r as inline function
#define SHR_R_IS_INLINE                 1   //define shr_r as inline function
#define MAC_R_IS_INLINE                 1   //define mac_r as inline function
#define MSU_R_IS_INLINE                 1   //define msu_r as inline function
#define L_SHR_R_IS_INLINE               1   //define L_shr_r as inline function

#define PREFIX				voAACEnc
#define LINK0(x, y, z)		LINK1(x,y,z)
#define LINK1(x,y,z)		x##y##z
#define ADD_PREFIX(func)	LINK0(PREFIX, _, func)

#define  L_Extract		ADD_PREFIX(L_Extract)
#define  L_Comp			ADD_PREFIX(L_Comp)
#define  Mpy_32			ADD_PREFIX(Mpy_32)
#define  Mpy_32_16		ADD_PREFIX(Mpy_32_16)
#define  Div_32			ADD_PREFIX(Div_32)
#define  iLog4			ADD_PREFIX(iLog4)
#define  rsqrt			ADD_PREFIX(rsqrt)
#define  pow2_xy		ADD_PREFIX(pow2_xy)
#define  L_mpy_ls		ADD_PREFIX(L_mpy_ls)
#define  L_mpy_wx		ADD_PREFIX(L_mpy_wx)

#define mem_malloc		ADD_PREFIX(mem_malloc)
#define mem_free		ADD_PREFIX(mem_free)

#endif
