// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// ulimits.h
//

#ifndef ULIMITS_H_1C2192EA3821E0811BBAF86B0F048364
#define ULIMITS_H_1C2192EA3821E0811BBAF86B0F048364

#include "utypes.h"
#include <stdint.h>

namespace ustl {

// Android
#ifndef UINTPTR_MAX
#define UINTPTR_MAX	UINT32_MAX
#endif

#ifndef UINT32_MAX
#define UINT32_MAX    (0xffffffff)
#endif
	
/// \class numeric_limits ulimits.h ustl.h
/// \brief Defines numeric limits for a type.
///
template <typename T> 
struct numeric_limits {
    /// Returns the minimum value for type T.
    static inline T min (void)		{ return (T(0)); }
    /// Returns the minimum value for type T.
    static inline T max (void)		{ return (T(0)); }
    static const bool is_signed = false;	///< True if the type is signed.
    static const bool is_integer = false;	///< True if stores an exact value.
    static const bool is_integral = false;	///< True if fixed size and cast-copyable.
};

#ifndef DOXYGEN_SHOULD_SKIP_THIS

template <typename T>
struct numeric_limits<T*> {
    static inline T* min (void)	{ return (NULL); }
    static inline T* max (void)	{ return (UINTPTR_MAX); }
    static const bool is_signed = false;
    static const bool is_integer = true;
    static const bool is_integral = true;
};

#define _NUMERIC_LIMITS(type, minVal, maxVal, bSigned, bInteger, bIntegral)	\
template <>							\
struct numeric_limits<type> {					\
    static inline type min (void)	{ return (minVal); }	\
    static inline type max (void)	{ return (maxVal); }	\
    static const bool is_signed = bSigned;			\
    static const bool is_integer = bInteger;			\
    static const bool is_integral = bIntegral;			\
}

//--------------------------------------------------------------------------------------
//		type		min		max		signed	integer	integral
//--------------------------------------------------------------------------------------
_NUMERIC_LIMITS (bool,		false,		true,		false,	true,	true);
_NUMERIC_LIMITS (char,		SCHAR_MIN,	SCHAR_MAX,	true,	true,	true);
_NUMERIC_LIMITS (int,		INT_MIN,	INT_MAX,	true,	true,	true);
_NUMERIC_LIMITS (short,		SHRT_MIN,	SHRT_MAX,	true,	true,	true);
_NUMERIC_LIMITS (long,		LONG_MIN,	LONG_MAX,	true,	true,	true);
#if HAVE_THREE_CHAR_TYPES
_NUMERIC_LIMITS (signed char,	SCHAR_MIN,	SCHAR_MAX,	true,	true,	true);
#endif
_NUMERIC_LIMITS (unsigned char,	0,		UCHAR_MAX,	false,	true,	true);
_NUMERIC_LIMITS (unsigned int,	0,		UINT_MAX,	false,	true,	true);
_NUMERIC_LIMITS (unsigned short,0,		USHRT_MAX,	false,	true,	true);
_NUMERIC_LIMITS (unsigned long,	0,		ULONG_MAX,	false,	true,	true);
_NUMERIC_LIMITS (wchar_t,	0,		WCHAR_MAX,	false,	true,	true);
_NUMERIC_LIMITS (float,		FLT_MIN,	FLT_MAX,	true,	false,	true);
_NUMERIC_LIMITS (double,	DBL_MIN,	DBL_MAX,	true,	false,	true);
_NUMERIC_LIMITS (long double,	LDBL_MIN,	LDBL_MAX,	true,	false,	true);
#ifdef HAVE_LONG_LONG
_NUMERIC_LIMITS (long long,	LLONG_MIN,	LLONG_MAX,	true,	true,	true);
_NUMERIC_LIMITS (unsigned long long,	0,	ULLONG_MAX,	false,	true,	true);
#endif
//--------------------------------------------------------------------------------------

#endif // DOXYGEN_SHOULD_SKIP_THIS

/// Macro for defining numeric_limits specializations
#define NUMERIC_LIMITS(type, minVal, maxVal, bSigned, bInteger, bIntegral)	\
namespace ustl { _NUMERIC_LIMITS (type, minVal, maxVal, bSigned, bInteger, bIntegral); }

/// Returns the recommended stream alignment for type \p T. Override with ALIGNOF.
template <typename T>
inline size_t alignof (const T&)
{
    if (numeric_limits<T>::is_integral)
	return (__alignof__(T));
    return (4);
}

#define ALIGNOF(type,grain)	\
namespace ustl {		\
    template <> inline size_t alignof (const type&) { return (grain); } }

} // namespace ustl

#endif

