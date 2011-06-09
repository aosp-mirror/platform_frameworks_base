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

/*
*
*      File             : typedefs.h
*      Description      : Definition of platform independent data
*                         types and constants
*
*
*      The following platform independent data types and corresponding
*      preprocessor (#define) constants are defined:
*
*        defined type  meaning           corresponding constants
*        ----------------------------------------------------------
*        Char          character         (none)
*        Bool          boolean           true, false
*        Word8         8-bit signed      minWord8,   maxWord8
*        UWord8        8-bit unsigned    minUWord8,  maxUWord8
*        Word16        16-bit signed     minWord16,  maxWord16
*        UWord16       16-bit unsigned   minUWord16, maxUWord16
*        Word32        32-bit signed     minWord32,  maxWord32
*        UWord32       32-bit unsigned   minUWord32, maxUWord32
*        Float         floating point    minFloat,   maxFloat
*
*
*      The following compile switches are #defined:
*
*        PLATFORM      string indicating platform progam is compiled on
*                      possible values: "OSF", "PC", "SUN"
*
*        OSF           only defined if the current platform is an Alpha
*        PC            only defined if the current platform is a PC
*        SUN           only defined if the current platform is a Sun
*        
*        LSBFIRST      is defined if the byte order on this platform is
*                      "least significant byte first" -> defined on DEC Alpha
*                      and PC, undefined on Sun
*
********************************************************************************
*/

#ifndef __TYPEDEFS_H__
#define __TYPEDEFS_H__

/*
********************************************************************************
*                         INCLUDE FILES
********************************************************************************
*/
#include <float.h>
#include <limits.h>



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
#if SCHAR_MAX == 127
typedef signed char Word8;
#define minWord8  SCHAR_MIN
#define maxWord8  SCHAR_MAX

typedef unsigned char UWord8;
#define minUWord8 0
#define maxUWord8 UCHAR_MAX
#else
#error cannot find 8-bit type
#endif


/*
 ********* define 16 bit signed/unsigned types & constants
 */
#if INT_MAX == 32767
typedef int Word16;
#define minWord16     INT_MIN
#define maxWord16     INT_MAX
typedef unsigned int UWord16;
#define minUWord16    0
#define maxUWord16    UINT_MAX
#elif SHRT_MAX == 32767
typedef short Word16;
#define minWord16     SHRT_MIN
#define maxWord16     SHRT_MAX
typedef unsigned short UWord16;
#define minUWord16    0
#define maxUWord16    USHRT_MAX
#else
#error cannot find 16-bit type
#endif


/*
 ********* define 32 bit signed/unsigned types & constants
 */
#if INT_MAX == 2147483647
typedef int Word32;
#define minWord32     INT_MIN
#define maxWord32     INT_MAX
typedef unsigned int UWord32;
#define minUWord32    0
#define maxUWord32    UINT_MAX
#elif LONG_MAX == 2147483647
typedef long Word32;
#define minWord32     LONG_MIN
#define maxWord32     LONG_MAX
typedef unsigned long UWord32;
#define minUWord32    0
#define maxUWord32    ULONG_MAX
#else
#error cannot find 32-bit type
#endif

/*
 ********* define floating point type & constants
 */
/* use "#if 0" below if Float should be double;
   use "#if 1" below if Float should be float
 */
#if 0
typedef float Float;
#define maxFloat      FLT_MAX
#define minFloat      FLT_MIN
#else
typedef double Float;
#define maxFloat      DBL_MAX
#define minFloat      DBL_MIN
#endif

/*
 ********* define complex type
 */
typedef struct {
  Float r;  /* real      part */
  Float i;  /* imaginary part */
} CPX;

/*
 ********* define boolean type
 */
typedef int Bool;
#define false 0
#define true 1

/* ******Avoid function multiple definition****** */
#define     Autocorr         voAWB_Autocorr
#define     Convolve         voAWB_Convolve
#define     cor_h_x          voAWB_cor_h_x
#define     dtx_enc_init     voAWB_dtx_enc_init
#define     dtx_enc_reset    voAWB_dtx_enc_reset
#define     dtx_enc_exit     voAWB_dtx_enc_exit
#define     dtx_enc          voAWB_dtx_enc
#define     dtx_buffer       voAWB_dtx_buffer
#define     tx_dtx_handler   voAWB_tx_dtx_handler
#define     G_pitch          voAWB_G_pitch
#define     Isp_Az           voAWB_Isp_Az
#define     Lag_window       voAWB_Lag_window
#define     Log2_norm        voAWB_Log2_norm
#define     Log2             voAWB_Log2
#define     Pow2             voAWB_Pow2
#define     L_Comp           voAWB_L_Comp
#define     Mpy_32           voAWB_Mpy_32
#define     Mpy_32_16        voAWB_Mpy_32_16
#define     Div_32           voAWB_Div_32
#define     Pit_shrp         voAWB_Pit_shrp
#define     Qisf_ns          voAWB_Qisf_ns
#define     Disf_ns          voAWB_Disf_ns
#define     Residu           voAWB_Residu
#define     Syn_filt         voAWB_Syn_filt
#define     Set_zero         voAWB_Set_zero
#define     Copy             voAWB_Copy
#define     voice_factor     voAWB_voice_factor
#define     Syn_filt_32      voAWB_Syn_filt_32
#define     Isf_isp          voAWB_Isf_isp
#define     Levinson         voAWB_Levinson
#define     median5          voAWB_median5           
#define     Pred_lt4         voAWB_Pred_lt4
#define     Reorder_isf      voAWB_Reorder_isf
#define     Dpisf_2s_36b     voAWB_Dpisf_2s_36b
#define     Dpisf_2s_46b     voAWB_Dpisf_2s_46b
#define     Dot_product12    voAWB_Dot_product12
#define     mem_malloc       voAWB_mem_malloc
#define     mem_free         voAWB_mem_free
/******************************************************/

#endif  //#define __TYPEDEFS_H__

