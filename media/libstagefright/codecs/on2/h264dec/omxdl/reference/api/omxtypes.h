/**
 * File: omxtypes.h
 * Brief: Defines basic Data types used in OpenMAX v1.0.2 header files.
 *
 * Copyright © 2005-2008 The Khronos Group Inc. All Rights Reserved. 
 *
 * These materials are protected by copyright laws and contain material 
 * proprietary to the Khronos Group, Inc.  You may use these materials 
 * for implementing Khronos specifications, without altering or removing 
 * any trademark, copyright or other notice from the specification.
 * 
 * Khronos Group makes no, and expressly disclaims any, representations 
 * or warranties, express or implied, regarding these materials, including, 
 * without limitation, any implied warranties of merchantability or fitness 
 * for a particular purpose or non-infringement of any intellectual property. 
 * Khronos Group makes no, and expressly disclaims any, warranties, express 
 * or implied, regarding the correctness, accuracy, completeness, timeliness, 
 * and reliability of these materials. 
 *
 * Under no circumstances will the Khronos Group, or any of its Promoters, 
 * Contributors or Members or their respective partners, officers, directors, 
 * employees, agents or representatives be liable for any damages, whether 
 * direct, indirect, special or consequential damages for lost revenues, 
 * lost profits, or otherwise, arising from or in connection with these 
 * materials.
 * 
 * Khronos and OpenMAX are trademarks of the Khronos Group Inc. 
 *
 */
  
#ifndef _OMXTYPES_H_
#define _OMXTYPES_H_

#include <limits.h> 

#define OMX_IN
#define OMX_OUT
#define OMX_INOUT


typedef enum {
    
    /* Mandatory return codes - use cases are explicitly described for each function */
    OMX_Sts_NoErr                    =  0,    /* No error, the function completed successfully */
    OMX_Sts_Err                      = -2,    /* Unknown/unspecified error */    
    OMX_Sts_InvalidBitstreamValErr   = -182,  /* Invalid value detected during bitstream processing */    
    OMX_Sts_MemAllocErr              = -9,    /* Not enough memory allocated for the operation */
    OMX_StsACAAC_GainCtrErr    	     = -159,  /* AAC: Unsupported gain control data detected */
    OMX_StsACAAC_PrgNumErr           = -167,  /* AAC: Invalid number of elements for one program   */
    OMX_StsACAAC_CoefValErr          = -163,  /* AAC: Invalid quantized coefficient value          */     
    OMX_StsACAAC_MaxSfbErr           = -162,  /* AAC: Invalid maxSfb value in relation to numSwb */    
	OMX_StsACAAC_PlsDataErr		     = -160,  /* AAC: pulse escape sequence data error */

    /* Optional return codes - use cases are explicitly described for each function*/
    OMX_Sts_BadArgErr                = -5,    /* Bad Arguments */

    OMX_StsACAAC_TnsNumFiltErr       = -157,  /* AAC: Invalid number of TNS filters  */
    OMX_StsACAAC_TnsLenErr           = -156,  /* AAC: Invalid TNS region length  */   
    OMX_StsACAAC_TnsOrderErr         = -155,  /* AAC: Invalid order of TNS filter  */                  
    OMX_StsACAAC_TnsCoefResErr       = -154,  /* AAC: Invalid bit-resolution for TNS filter coefficients  */
    OMX_StsACAAC_TnsCoefErr          = -153,  /* AAC: Invalid TNS filter coefficients  */                  
    OMX_StsACAAC_TnsDirectErr        = -152,  /* AAC: Invalid TNS filter direction  */  

    OMX_StsICJP_JPEGMarkerErr        = -183,  /* JPEG marker encountered within an entropy-coded block; */
                                              /* Huffman decoding operation terminated early.           */
    OMX_StsICJP_JPEGMarker           = -181,  /* JPEG marker encountered; Huffman decoding */
                                              /* operation terminated early.                         */
    OMX_StsIPPP_ContextMatchErr      = -17,   /* Context parameter doesn't match to the operation */

    OMX_StsSP_EvenMedianMaskSizeErr  = -180,  /* Even size of the Median Filter mask was replaced by the odd one */

    OMX_Sts_MaximumEnumeration       = INT_MAX  /*Placeholder, forces enum of size OMX_INT*/
    
 } OMXResult;          /** Return value or error value returned from a function. Identical to OMX_INT */

 
/* OMX_U8 */
#if UCHAR_MAX == 0xff
typedef unsigned char OMX_U8;
#elif USHRT_MAX == 0xff 
typedef unsigned short int OMX_U8; 
#else
#error OMX_U8 undefined
#endif 

 
/* OMX_S8 */
#if SCHAR_MAX == 0x7f 
typedef signed char OMX_S8;
#elif SHRT_MAX == 0x7f 
typedef signed short int OMX_S8; 
#else
#error OMX_S8 undefined
#endif
 
 
/* OMX_U16 */
#if USHRT_MAX == 0xffff
typedef unsigned short int OMX_U16;
#elif UINT_MAX == 0xffff
typedef unsigned int OMX_U16; 
#else
#error OMX_U16 undefined
#endif


/* OMX_S16 */
#if SHRT_MAX == 0x7fff 
typedef signed short int OMX_S16;
#elif INT_MAX == 0x7fff 
typedef signed int OMX_S16; 
#else
#error OMX_S16 undefined
#endif


/* OMX_U32 */
#if UINT_MAX == 0xffffffff
typedef unsigned int OMX_U32;
#elif LONG_MAX == 0xffffffff
typedef unsigned long int OMX_U32; 
#else
#error OMX_U32 undefined
#endif


/* OMX_S32 */
#if INT_MAX == 0x7fffffff
typedef signed int OMX_S32;
#elif LONG_MAX == 0x7fffffff
typedef long signed int OMX_S32; 
#else
#error OMX_S32 undefined
#endif


/* OMX_U64 & OMX_S64 */
#if defined( _WIN32 ) || defined ( _WIN64 )
    typedef __int64 OMX_S64; /** Signed 64-bit integer */
    typedef unsigned __int64 OMX_U64; /** Unsigned 64-bit integer */
    #define OMX_MIN_S64			(0x8000000000000000i64)
    #define OMX_MIN_U64			(0x0000000000000000i64)
    #define OMX_MAX_S64			(0x7FFFFFFFFFFFFFFFi64)
    #define OMX_MAX_U64			(0xFFFFFFFFFFFFFFFFi64)
#else
    typedef long long OMX_S64; /** Signed 64-bit integer */
    typedef unsigned long long OMX_U64; /** Unsigned 64-bit integer */
    #define OMX_MIN_S64			(0x8000000000000000LL)
    #define OMX_MIN_U64			(0x0000000000000000LL)
    #define OMX_MAX_S64			(0x7FFFFFFFFFFFFFFFLL)
    #define OMX_MAX_U64			(0xFFFFFFFFFFFFFFFFLL)
#endif


/* OMX_SC8 */
typedef struct
{
  OMX_S8 Re; /** Real part */
  OMX_S8 Im; /** Imaginary part */	
	
} OMX_SC8; /** Signed 8-bit complex number */


/* OMX_SC16 */
typedef struct
{
  OMX_S16 Re; /** Real part */
  OMX_S16 Im; /** Imaginary part */	
	
} OMX_SC16; /** Signed 16-bit complex number */


/* OMX_SC32 */
typedef struct
{
  OMX_S32 Re; /** Real part */
  OMX_S32 Im; /** Imaginary part */	
	
} OMX_SC32; /** Signed 32-bit complex number */


/* OMX_SC64 */
typedef struct
{
  OMX_S64 Re; /** Real part */
  OMX_S64 Im; /** Imaginary part */	
	
} OMX_SC64; /** Signed 64-bit complex number */


/* OMX_F32 */
typedef float OMX_F32; /** Single precision floating point,IEEE 754 */


/* OMX_F64 */
typedef double OMX_F64; /** Double precision floating point,IEEE 754 */


/* OMX_INT */
typedef int OMX_INT; /** signed integer corresponding to machine word length, has maximum signed value INT_MAX*/


#define OMX_MIN_S8  	   	(-128)
#define OMX_MIN_U8  		0
#define OMX_MIN_S16		 	(-32768)
#define OMX_MIN_U16			0
#define OMX_MIN_S32			(-2147483647-1)
#define OMX_MIN_U32			0

#define OMX_MAX_S8			(127)
#define OMX_MAX_U8			(255)
#define OMX_MAX_S16			(32767)
#define OMX_MAX_U16			(0xFFFF)
#define OMX_MAX_S32			(2147483647)
#define OMX_MAX_U32			(0xFFFFFFFF)

typedef void OMXVoid;

#ifndef NULL
#define NULL ((void*)0)
#endif

/** Defines the geometric position and size of a rectangle, 
  * where x,y defines the coordinates of the top left corner
  * of the rectangle, with dimensions width in the x-direction 
  * and height in the y-direction */
typedef struct {
	OMX_INT x;      /** x-coordinate of top left corner of rectangle */
	OMX_INT y;      /** y-coordinate of top left corner of rectangle */
	OMX_INT width;  /** Width in the x-direction. */
	OMX_INT height; /** Height in the y-direction. */
}OMXRect;


/** Defines the geometric position of a point, */
typedef struct 
{
 OMX_INT x; /** x-coordinate */
 OMX_INT y;	/** y-coordinate */
	
} OMXPoint;


/** Defines the dimensions of a rectangle, or region of interest in an image */
typedef struct 
{
 OMX_INT width;  /** Width of the rectangle, in the x-direction */
 OMX_INT height; /** Height of the rectangle, in the y-direction */
	
} OMXSize;

#endif /* _OMXTYPES_H_ */
