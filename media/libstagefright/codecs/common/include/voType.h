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
	File:		voType.h

	Content:	data type definition

*******************************************************************************/
#ifndef __voType_H__
#define __voType_H__

#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */

#ifdef _WIN32
#	define VO_API __cdecl
#	define VO_CBI __stdcall
#else
#	define VO_API
#	define VO_CBI
#endif //_WIN32

/** VO_IN is used to identify inputs to an VO function.  This designation
    will also be used in the case of a pointer that points to a parameter
    that is used as an output. */
#ifndef VO_IN
#define VO_IN
#endif

/** VO_OUT is used to identify outputs from an VO function.  This
    designation will also be used in the case of a pointer that points
    to a parameter that is used as an input. */
#ifndef VO_OUT
#define VO_OUT
#endif

/** VO_INOUT is used to identify parameters that may be either inputs or
    outputs from an VO function at the same time.  This designation will
    also be used in the case of a pointer that  points to a parameter that
    is used both as an input and an output. */
#ifndef VO_INOUT
#define VO_INOUT
#endif

#define VO_MAX_ENUM_VALUE	0X7FFFFFFF

/** VO_VOID */
typedef void VO_VOID;

/** VO_U8 is an 8 bit unsigned quantity that is byte aligned */
typedef unsigned char VO_U8;

/** VO_BYTE is an 8 bit unsigned quantity that is byte aligned */
typedef unsigned char VO_BYTE;

/** VO_S8 is an 8 bit signed quantity that is byte aligned */
typedef signed char VO_S8;

/** VO_CHAR is an 8 bit signed quantity that is byte aligned */
typedef char VO_CHAR;

/** VO_U16 is a 16 bit unsigned quantity that is 16 bit word aligned */
typedef unsigned short VO_U16;

/** VO_WCHAR is a 16 bit unsigned quantity that is 16 bit word aligned */
#if defined _WIN32
typedef unsigned short VO_WCHAR;
typedef unsigned short* VO_PWCHAR;
#elif defined LINUX
typedef unsigned char VO_WCHAR;
typedef unsigned char* VO_PWCHAR;
#endif

/** VO_S16 is a 16 bit signed quantity that is 16 bit word aligned */
typedef signed short VO_S16;

/** VO_U32 is a 32 bit unsigned quantity that is 32 bit word aligned */
typedef unsigned long VO_U32;

/** VO_S32 is a 32 bit signed quantity that is 32 bit word aligned */
typedef signed long VO_S32;

/* Users with compilers that cannot accept the "long long" designation should
   define the VO_SKIP64BIT macro.  It should be noted that this may cause
   some components to fail to compile if the component was written to require
   64 bit integral types.  However, these components would NOT compile anyway
   since the compiler does not support the way the component was written.
*/
#ifndef VO_SKIP64BIT
#ifdef _MSC_VER
/** VO_U64 is a 64 bit unsigned quantity that is 64 bit word aligned */
typedef unsigned __int64  VO_U64;
/** VO_S64 is a 64 bit signed quantity that is 64 bit word aligned */
typedef signed   __int64  VO_S64;
#else // WIN32
/** VO_U64 is a 64 bit unsigned quantity that is 64 bit word aligned */
typedef unsigned long long VO_U64;
/** VO_S64 is a 64 bit signed quantity that is 64 bit word aligned */
typedef signed long long VO_S64;
#endif // WIN32
#endif // VO_SKIP64BIT

/** The VO_BOOL type is intended to be used to represent a true or a false
    value when passing parameters to and from the VO core and components.  The
    VO_BOOL is a 32 bit quantity and is aligned on a 32 bit word boundary.
 */
typedef enum VO_BOOL {
    VO_FALSE = 0,
    VO_TRUE = !VO_FALSE,
	VO_BOOL_MAX = VO_MAX_ENUM_VALUE
} VO_BOOL;

/** The VO_PTR type is intended to be used to pass pointers between the VO
    applications and the VO Core and components.  This is a 32 bit pointer and
    is aligned on a 32 bit boundary.
 */
typedef void* VO_PTR;

/** The VO_HANDLE type is intended to be used to pass pointers between the VO
    applications and the VO Core and components.  This is a 32 bit pointer and
    is aligned on a 32 bit boundary.
 */
typedef void* VO_HANDLE;

/** The VO_STRING type is intended to be used to pass "C" type strings between
    the application and the core and component.  The VO_STRING type is a 32
    bit pointer to a zero terminated string.  The  pointer is word aligned and
    the string is byte aligned.
 */
typedef char* VO_PCHAR;

/** The VO_PBYTE type is intended to be used to pass arrays of bytes such as
    buffers between the application and the component and core.  The VO_PBYTE
    type is a 32 bit pointer to a zero terminated string.  The  pointer is word
    aligned and the string is byte aligned.
 */
typedef unsigned char* VO_PBYTE;

/** The VO_PTCHAR type is intended to be used to pass arrays of wchar such as
    unicode char between the application and the component and core.  The VO_PTCHAR
    type is a 32 bit pointer to a zero terminated string.  The  pointer is word
    aligned and the string is byte aligned.
 */
/*
#if !defined LINUX
typedef unsigned short* VO_PTCHAR;
typedef unsigned short* VO_TCHAR;
#else
typedef char* VO_PTCHAR;
typedef char VO_TCHAR;
#endif
*/

#ifndef NULL
#ifdef __cplusplus
#define NULL    0
#else
#define NULL    ((void *)0)
#endif
#endif

/**
 * Input stream format, Frame or Stream..
 */
typedef enum {
    VO_INPUT_FRAME	= 1,	/*!< Input contains completely frame(s) data. */
    VO_INPUT_STREAM,		/*!< Input is stream data. */
	VO_INPUT_STREAM_MAX = VO_MAX_ENUM_VALUE
} VO_INPUT_TYPE;


/**
 * General data buffer, used as input or output.
 */
typedef struct {
	VO_PBYTE	Buffer;		/*!< Buffer pointer */
	VO_U32		Length;		/*!< Buffer size in byte */
	VO_S64		Time;		/*!< The time of the buffer */
} VO_CODECBUFFER;


/**
 * The init memdata flag.
 */
typedef enum{
	VO_IMF_USERMEMOPERATOR		=0,	/*!< memData is  the pointer of memoperator function*/
	VO_IMF_PREALLOCATEDBUFFER	=1,	/*!< memData is  preallocated memory*/
	VO_IMF_MAX = VO_MAX_ENUM_VALUE
}VO_INIT_MEM_FlAG;


/**
 * The init memory structure..
 */
typedef struct{
	VO_INIT_MEM_FlAG			memflag;	/*!<memory flag  */
	VO_PTR						memData;	/*!<a pointer to VO_MEM_OPERATOR or a preallocated buffer  */
	VO_U32						reserved1;	/*!<reserved  */
	VO_U32						reserved2;	/*!<reserved */
}VO_CODEC_INIT_USERDATA;


#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif // __voType_H__
