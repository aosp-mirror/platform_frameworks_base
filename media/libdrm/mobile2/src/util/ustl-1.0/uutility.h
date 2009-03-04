// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
/// \file uutility.h
///
/// \brief Utility templates.
///
/// Everything in here except min(), max(), distance(), and advance()
/// are uSTL extensions and are absent from other STL implementations.
///

#ifndef UUTILITY_H_6A58BD296269A82A4AAAA4FD19FDB3AC
#define UUTILITY_H_6A58BD296269A82A4AAAA4FD19FDB3AC

#include "uassert.h"
#include "utypes.h"

#if PLATFORM_ANDROID
#include <stdio.h>
#undef CPU_HAS_MMX
#endif

namespace ustl {

#ifdef __GNUC__
    /// Returns the number of elements in a static vector
    #define VectorSize(v)	(sizeof(v) / sizeof(*v))
#else
    // Old compilers will not be able to evaluate *v on an empty vector.
    // The tradeoff here is that VectorSize will not be able to measure arrays of local structs.
    #define VectorSize(v)	(sizeof(v) / ustl::size_of_elements(1, v))
#endif

/// Expands into a ptr,size expression for the given static vector; useful as link arguments.
#define VectorBlock(v)	(v)+0, VectorSize(v)	// +0 makes it work under gcc 2.95
/// Expands into a begin,end expression for the given static vector; useful for algorithm arguments.
#define VectorRange(v)	VectorBlock(v)+(v)

/// Returns the number of bits in the given type
#define BitsInType(t)	(sizeof(t) * CHAR_BIT)

/// Returns the mask of type \p t with the lowest \p n bits set.
#define BitMask(t,n)	(t(~t(0)) >> ((sizeof(t) * CHAR_BIT) - (n)))

/// Argument that is used only in debug builds (as in an assert)
#ifndef NDEBUG
    #define DebugArg(x)	x
#else
    #define DebugArg(x)
#endif

/// Shorthand for container iteration.
#define foreach(type,i,ctr)	for (type i = (ctr).begin(); i != (ctr).end(); ++ i)
/// Shorthand for container reverse iteration.
#define eachfor(type,i,ctr)	for (type i = (ctr).rbegin(); i != (ctr).rend(); ++ i)

/// Macro for passing template types as macro arguments.
/// \@{
#define TEMPLATE_FULL_DECL1(d1,t1)		template <d1 t1>
#define TEMPLATE_FULL_DECL2(d1,t1,d2,t2)	template <d1 t1, d2 t2>
#define TEMPLATE_FULL_DECL3(d1,t1,d2,t2,d3,t3)	template <d1 t1, d2 t2, d3 t3>
#define TEMPLATE_DECL1(t1)		TEMPLATE_FULL_DECL1(typename,t1)
#define TEMPLATE_DECL2(t1,t2)		TEMPLATE_FULL_DECL2(typename,t1,typename,t2)
#define TEMPLATE_DECL3(t1,t2,t3)	TEMPLATE_FULL_DECL3(typename,t1,typename,t2,typename,t3)
#define TEMPLATE_TYPE1(type,a1)		type<a1>
#define TEMPLATE_TYPE2(type,a1,a2)	type<a1,a2>
#define TEMPLATE_TYPE3(type,a1,a2,a3)	type<a1,a2,a3>
/// \@}

/// Returns the minimum of \p a and \p b
template <typename T1, typename T2>
inline const T1 min (const T1& a, const T2& b)
{
    return (a < b ? a : b);
}

/// Returns the maximum of \p a and \p b
template <typename T1, typename T2>
inline const T1 max (const T1& a, const T2& b)
{
    return (b < a ? a : b);
}

/// \brief Divides \p n1 by \p n2 and rounds the result up.
/// This is in contrast to regular division, which rounds down.
/// Negative numbers are rounded down because they are an unusual case, supporting
/// which would require a branch. Since this is frequently used in graphics, the
/// speed is important.
///
template <typename T1, typename T2>
inline T1 DivRU (T1 n1, T2 n2)
{
    return (n1 / n2 + (n1 % n2 > 0));
}

/// The alignment performed by default.
const size_t c_DefaultAlignment = __alignof__(void*);

/// \brief Rounds \p n up to be divisible by \p grain
template <typename T>
inline T Align (T n, size_t grain = c_DefaultAlignment)
{
    T a, r = n % grain;
    if (grain == 2) return (n + r);
    switch (grain) {
	case 4: case 8: case 16: a = (n & ~(grain - 1)) + grain; break;
	default:		 a = n + (grain - r);
    };
    return (r ? a : n);
}

/// Offsets an iterator
template <typename T>
inline T advance (T i, ssize_t offset)
{
    return (i + offset);
}

#ifndef DOXYGEN_SHOULD_SKIP_THIS
/// Offsets a void pointer
template <>
inline const void* advance (const void* p, ssize_t offset)
{
    assert (p || !offset);
    return (reinterpret_cast<const uint8_t*>(p) + offset);
}

/// Offsets a void pointer
template <>
inline void* advance (void* p, ssize_t offset)
{
    assert (p || !offset);
    return (reinterpret_cast<uint8_t*>(p) + offset);
}
#endif

/// Returns the difference \p p1 - \p p2
template <typename T1, typename T2>
inline ptrdiff_t distance (T1 i1, T2 i2)
{
    return (i2 - i1);
}

#ifndef DOXYGEN_SHOULD_SKIP_THIS
#define UNVOID_DISTANCE(T1const,T2const)				   \
template <> inline ptrdiff_t distance (T1const void* p1, T2const void* p2) \
{ return ((T2const uint8_t*)(p2) - (T1const uint8_t*)(p1)); }
UNVOID_DISTANCE(,)
UNVOID_DISTANCE(const,const)
UNVOID_DISTANCE(,const)
UNVOID_DISTANCE(const,)
#undef UNVOID_DISTANCE
#endif

/// \brief Returns the absolute value of \p v
/// Unlike the stdlib functions, this is inline and works with all types.
template <typename T>
inline T absv (T v)
{
    return (v < 0 ? -v : v);
}

/// \brief Returns -1 for negative values, 1 for positive, and 0 for 0
template <typename T>
inline T sign (T v)
{
    return ((0 < v) - (v < 0));
}

/// Returns the absolute value of the distance i1 and i2
template <typename T1, typename T2>
inline size_t abs_distance (T1 i1, T2 i2)
{
    return (absv (distance(i1, i2)));
}

/// Returns the size of \p n elements of size \p T
template <typename T>
inline size_t size_of_elements (size_t n, const T*)
{
    return (n * sizeof(T));
}

// Defined in byteswap.h, which is usually unusable.
#undef bswap_16
#undef bswap_32
#undef bswap_64

#if CPU_HAS_CMPXCHG8	// If it has that, it has bswap.
inline uint16_t bswap_16 (uint16_t v)	{ asm ("rorw $8, %w0" : "=r"(v) : "0"(v) : "cc"); return (v); }
inline uint32_t bswap_32 (uint32_t v)	{ asm ("bswap %0" : "=r"(v) : "0"(v)); return (v); }
#else
inline uint16_t bswap_16 (uint16_t v)	{ return (v << 8 | v >> 8); }
inline uint32_t bswap_32 (uint32_t v)	{ return (v << 24 | (v & 0xFF00) << 8 | (v >> 8) & 0xFF00 | v >> 24); }
#endif
#if HAVE_INT64_T
inline uint64_t bswap_64 (uint64_t v)	{ return ((uint64_t(bswap_32(v)) << 32) | bswap_32(v >> 32)); }
#endif

/// \brief Swaps the byteorder of \p v.
template <typename T>
inline T bswap (const T& v)
{
    switch (BitsInType(T)) {
	default:	return (v);
	case 16:	return (T (bswap_16 (uint16_t (v))));
	case 32:	return (T (bswap_32 (uint32_t (v))));
#if HAVE_INT64_T
	case 64:	return (T (bswap_64 (uint64_t (v))));
#endif
    };
}

#if USTL_BYTE_ORDER == USTL_BIG_ENDIAN
template <typename T> inline T le_to_native (const T& v) { return (bswap (v)); }
template <typename T> inline T be_to_native (const T& v) { return (v); }
template <typename T> inline T native_to_le (const T& v) { return (bswap (v)); }
template <typename T> inline T native_to_be (const T& v) { return (v); }
#elif USTL_BYTE_ORDER == USTL_LITTLE_ENDIAN
template <typename T> inline T le_to_native (const T& v) { return (v); }
template <typename T> inline T be_to_native (const T& v) { return (bswap (v)); }
template <typename T> inline T native_to_le (const T& v) { return (v); }
template <typename T> inline T native_to_be (const T& v) { return (bswap (v)); }
#endif // USTL_BYTE_ORDER

/// Deletes \p p and sets it to NULL
template <typename T>
inline void Delete (T*& p)
{
    delete p;
    p = NULL;
}

/// Deletes \p p as an array and sets it to NULL
template <typename T>
inline void DeleteVector (T*& p)
{
    delete [] p;
    p = NULL;
}

/// Template of making != from ! and ==
template <typename T>
inline bool operator!= (const T& x, const T& y)
{
    return (!(x == y));
}

/// Template of making > from <
template <typename T>
inline bool operator> (const T& x, const T& y)
{
    return (y < x);
}

/// Template of making <= from < and ==
template <typename T>
inline bool operator<= (const T& x, const T& y)
{
    return (!(y < x));
}

/// Template of making >= from < and ==
template <typename T>
inline bool operator>= (const T& x, const T& y)
{
    return (!(x < y));
}

/// Packs \p s multiple times into \p b. Useful for loop unrolling.
template <typename TSmall, typename TBig>
inline void pack_type (TSmall s, TBig& b)
{
    const size_t n = sizeof(TBig) / sizeof(TSmall);
    b = s;
    // Calls to min are here to avoid warnings for shifts bigger than the type. min will be gone when optimized.
    if (n < 2) return;
    b = (b << min (BitsInType(TSmall), BitsInType(TBig))) | b;
    if (n < 4) return;
    b = (b << min (BitsInType(TSmall) * 2, BitsInType(TBig))) | b;
    if (n < 8) return;
    b = (b << min (BitsInType(TSmall) * 4, BitsInType(TBig))) | b;
}

#if __GNUC__ >= 3
inline bool TestAndSet (int* pm) __attribute__((always_inline));
#endif
/// Sets the contents of \p pm to 1 and returns true if the previous value was 0.
inline bool TestAndSet (int* pm)
{
#if CPU_HAS_CMPXCHG8
    bool rv;
    int oldVal (1);
    asm volatile ( // cmpxchg compares to %eax and swaps if equal
	"cmpxchgl %3, %1\n\t"
	"sete %0"
	: "=a" (rv), "=m" (*pm), "=r" (oldVal)
	: "2" (oldVal), "a" (0)
	: "memory");
    return (rv);
#elif __i386__ || __x86_64__
    int oldVal (1);
    asm volatile ("xchgl %0, %1" : "=r"(oldVal), "=m"(*pm) : "0"(oldVal), "m"(*pm) : "memory");
    return (!oldVal);
#elif __sparc32__	// This has not been tested
    int rv;
    asm volatile ("ldstub %1, %0" : "=r"(rv), "=m"(*pm) : "m"(pm));
    return (!rv);
#else
    const int oldVal (*pm);
    *pm = 1;
    return (!oldVal);
#endif
}

/// \brief This template is to be used for dereferencing a type-punned pointer without a warning.
///
/// When casting a local variable to an unrelated type through a pointer (for
/// example, casting a float to a uint32_t without conversion), the resulting
/// memory location can be accessed through either pointer, which violates the
/// strict aliasing rule. While -fno-strict-aliasing option can be given to
/// the compiler, eliminating this warning, inefficient code may result in
/// some instances, because aliasing inhibits some optimizations. By using
/// this template, and by ensuring the memory is accessed in one way only,
/// efficient code can be produced without the warning. For gcc 4.1.0+.
///
template <typename DEST, typename SRC>
inline DEST noalias (DEST, SRC* s)
{
    union UPun { SRC s; DEST d; };
    return (((UPun*)(s))->d);
}

namespace simd {
    /// Call after you are done using SIMD algorithms for 64 bit tuples.
#if CPU_HAS_MMX
    inline void reset_mmx (void) __attribute__((always_inline));
    #define ALL_MMX_REGS_CHANGELIST "mm0","mm1","mm2","mm3","mm4","mm5","mm6","mm7","st","st(1)","st(2)","st(3)","st(4)","st(5)","st(6)","st(7)"
    #if CPU_HAS_3DNOW
	inline void reset_mmx (void) { asm ("femms":::ALL_MMX_REGS_CHANGELIST); }
    #else
	inline void reset_mmx (void) { asm ("emms":::ALL_MMX_REGS_CHANGELIST); }
    #endif
#else
    inline void reset_mmx (void) {}
#endif
} // namespace simd

/// \brief Type that is not size_t
///
/// Because size_t may be declared as unsigned long or unsigned int on
/// different machines, this macro is convenient when defining overloads
/// of size_t to use other types.
///
#if defined(SIZE_T_IS_LONG) && !defined(__ARM_EABI__)
     #define NOT_SIZE_T_I_OR_L	unsigned int
#else
    #define NOT_SIZE_T_I_OR_L	unsigned long
#endif

/// \brief Required when you want to overload size_t and a pointer.
///
/// The compiler will happily cast a number to a pointer and declare
/// that the overload is ambiguous unless you define overloads for all
/// possible integral types that a number may represent. This behaviour,
/// although braindead, is in the ANSI standard, and thus not a bug. If
/// you want to change the standard, the best solution is to disallow any
/// implicit casts to pointer from an integral type. Ironically, such an
/// implicit cast is already detected by gcc.
///
#if defined(USTL_ANDROID_X86)
#define OVERLOAD_POINTER_AND_SIZE_T_V2(name, arg1type)
#else
#define OVERLOAD_POINTER_AND_SIZE_T_V2(name, arg1type)						\
    inline void	name (arg1type a1, short a2)			{ name (a1, size_t(a2)); }	\
    inline void	name (arg1type a1, unsigned short a2)		{ name (a1, size_t(a2)); }	\
    inline void	name (arg1type a1, int a2)			{ name (a1, size_t(a2)); }	\
    inline void	name (arg1type a1, long a2)			{ name (a1, size_t(a2)); }	\
    inline void	name (arg1type a1, NOT_SIZE_T_I_OR_L a2)	{ name (a1, size_t(a2)); }
#endif
} // namespace ustl


#endif

