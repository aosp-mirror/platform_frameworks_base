// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// utypes.h
//
// Types used by this library.
//

#ifndef UTYPES_H_118BBB3B50B7DBF22F5460C52E515C83
#define UTYPES_H_118BBB3B50B7DBF22F5460C52E515C83

#include "config.h"
#ifndef STDC_HEADERS
    #error This library requires standard C and C++ headers to compile.
#endif
#ifndef STDUNIX_HEADERS
    #error This library compiles only on UNIX systems.
#endif
#define __STDC_LIMIT_MACROS	// For WCHAR_MIN and WCHAR_MAX in stdint.
#define __STDC_CONSTANT_MACROS	// For UINT??_C macros to avoid using L and UL suffixes on constants.
#ifdef HAVE_STDINT_H
    #include <stdint.h>
#elif HAVE_INTTYPES_H
    #include <inttypes.h>
#else
    #error Need standard integer types definitions, usually in stdint.h
#endif
#include <stddef.h>		// For ptrdiff_t, size_t
#include <limits.h>
#include <float.h>
#ifdef HAVE_SYS_TYPES_H
    #include <sys/types.h>
#endif
#ifndef SIZE_MAX
    #define SIZE_MAX		UINT_MAX
#endif
#if sun || __sun		// Solaris defines UINTPTR_MAX as empty.
    #undef UINTPTR_MAX
    #define UINTPTR_MAX		ULONG_MAX
#endif
#ifndef WCHAR_MAX
    #ifdef __WCHAR_MAX__
	#define WCHAR_MAX	__WCHAR_MAX__
    #else
	#define WCHAR_MAX	CHAR_MAX
    #endif
#endif
#ifdef HAVE_LONG_LONG
    #ifndef LLONG_MAX
	#define ULLONG_MAX	UINT64_C(0xFFFFFFFFFFFFFFFF)
	#define LLONG_MAX	INT64_C(0x7FFFFFFFFFFFFFFF)
	#define LLONG_MIN	ULLONG_MAX
    #endif
#endif
#if !PLATFORM_ANDROID
#ifndef BYTE_ORDER
    #define LITTLE_ENDIAN	USTL_LITTLE_ENDIAN
    #define BIG_ENDIAN		USTL_BIG_ENDIAN
    #define BYTE_ORDER		USTL_BYTE_ORDER
#endif
#endif

typedef size_t		uoff_t;		///< A type for storing offsets into blocks measured by size_t.
typedef uint32_t	hashvalue_t;	///< Value type returned by the hash functions.

#endif

