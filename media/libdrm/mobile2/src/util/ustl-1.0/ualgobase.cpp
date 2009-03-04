// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// ualgobase.cc
//
// Copy and fill optimizations are here.
//

#ifndef NDEBUG	// Optimized code here. asserts slow it down, and are checked elsewhere.
#define NDEBUG
#endif

#include "ualgo.h"

#undef CPU_HAS_MMX

namespace ustl {

// Generic version for implementing fill_nX_fast on non-i386 architectures.
template <typename T> inline void stosv (T*& p, size_t n, T v)
    { while (n--) *p++ = v; }

#if defined(__i386__) || defined(__x86_64__)

//----------------------------------------------------------------------
// Copy functions
//----------------------------------------------------------------------

#if __GNUC__ >= 3
static inline void movsb_dir_up (void) __attribute__((always_inline));
static inline void movsb_dir_down (void) __attribute__((always_inline));
static inline void movsb (const void*& src, size_t nBytes, void*& dest) __attribute__((always_inline));
static inline void movsd (const void*& src, size_t nWords, void*& dest) __attribute__((always_inline));
#endif

static inline void movsb_dir_up (void) { asm volatile ("cld"); }
static inline void movsb_dir_down (void) { asm volatile ("std"); }

static inline void movsb (const void*& src, size_t nBytes, void*& dest)
{
    asm volatile ("rep;\n\tmovsb"
	: "=&S"(src), "=&D"(dest), "=&c"(nBytes)
	: "0"(src), "1"(dest), "2"(nBytes)
	: "memory");
}

static inline void movsd (const void*& src, size_t nWords, void*& dest)
{
    asm volatile ("rep;\n\tmovsl"
	: "=&S"(src), "=&D"(dest), "=&c"(nWords)
	: "0"(src), "1"(dest), "2"(nWords)
	: "memory");
}

template <> inline void stosv (uint8_t*& p, size_t n, uint8_t v)
{ asm volatile ("rep;\n\tstosb" : "=&D"(p), "=c"(n) : "0"(p), "1"(n), "a"(v) : "memory"); }
template <> inline void stosv (uint16_t*& p, size_t n, uint16_t v)
{ asm volatile ("rep;\n\tstosw" : "=&D"(p), "=c"(n) : "0"(p), "1"(n), "a"(v) : "memory"); }
template <> inline void stosv (uint32_t*& p, size_t n, uint32_t v)
{ asm volatile ("rep;\n\tstosl" : "=&D"(p), "=c"(n) : "0"(p), "1"(n), "a"(v) : "memory"); }

#if CPU_HAS_MMX
#define MMX_ALIGN	16U	// Data must be aligned on this grain
#define MMX_BS		32U	// Assembly routines copy data this many bytes at a time.

static inline void simd_block_copy (const void* src, void* dest) __attribute__((always_inline));
static inline void simd_block_store (uint8_t* dest) __attribute__((always_inline));
static inline void simd_block_cleanup (void) __attribute__((always_inline));

static inline void simd_block_copy (const void* src, void* dest)
{
    const char* csrc ((const char*) src);
    char* cdest ((char*) dest);
    #if CPU_HAS_SSE
    asm (
	"movaps\t%2, %%xmm0	\n\t"
	"movaps\t%3, %%xmm1	\n\t"
	"movntps\t%%xmm0, %0	\n\t"
	"movntps\t%%xmm1, %1"
	: "=m"(cdest[0]), "=m"(cdest[16])
	: "m"(csrc[0]), "m"(csrc[16])
	: "xmm0", "xmm1");
    #else
    asm (
	"movq	%4, %%mm0	\n\t"
	"movq	%5, %%mm1	\n\t"
	"movq	%6, %%mm2	\n\t"
	"movq	%7, %%mm3	\n\t"
	"movq	%%mm0, %0	\n\t"
	"movq	%%mm1, %1	\n\t"
	"movq	%%mm2, %2	\n\t"
	"movq	%%mm3, %3"
	: "=m"(cdest[0]), "=m"(cdest[8]), "=m"(cdest[16]), "=m"(cdest[24])
	: "m"(csrc[0]), "m"(csrc[8]), "m"(csrc[16]), "m"(csrc[24])
	: "mm0", "mm1", "mm2", "mm3", "st", "st(1)", "st(2)", "st(3)");
    #endif
}

static inline void simd_block_store (uint8_t* dest)
{
    #if CPU_HAS_SSE
    asm volatile (
	"movntq %%mm0, %0\n\t"
	"movntq %%mm0, %1\n\t"
	"movntq %%mm0, %2\n\t"
	"movntq %%mm0, %3"
	: "=m"(dest[0]), "=m"(dest[8]), "=m"(dest[16]), "=m"(dest[24]));
    #else
    asm volatile (
	"movq %%mm0, %0	\n\t"
	"movq %%mm0, %1	\n\t"
	"movq %%mm0, %2	\n\t"
	"movq %%mm0, %3"
	: "=m"(dest[0]), "=m"(dest[8]), "=m"(dest[16]), "=m"(dest[24]));
    #endif
}

static inline void simd_block_cleanup (void)
{
    #if !CPU_HAS_SSE
	simd::reset_mmx();
    #endif
    asm volatile ("sfence");
}

/// The fastest optimized raw memory copy.
void copy_n_fast (const void* src, size_t nBytes, void* dest)
{
    movsb_dir_up();
    size_t nHeadBytes = Align(uintptr_t(src), MMX_ALIGN) - uintptr_t(src);
    nHeadBytes = min (nHeadBytes, nBytes);
    movsb (src, nHeadBytes, dest);
    nBytes -= nHeadBytes;
    if (!(uintptr_t(dest) % MMX_ALIGN)) {
	const size_t nMiddleBlocks = nBytes / MMX_BS;
	for (uoff_t i = 0; i < nMiddleBlocks; ++ i) {
	    prefetch (advance (src, 512), 0, 0);
	    simd_block_copy (src, dest);
	    src = advance (src, MMX_BS);
	    dest = advance (dest, MMX_BS);
	}
	simd_block_cleanup();
	nBytes %= MMX_BS;
    }
    movsb (src, nBytes, dest);
}
#endif // CPU_HAS_MMX

/// The fastest optimized backwards raw memory copy.
void copy_backward_fast (const void* first, const void* last, void* result)
{
    prefetch (first, 0, 0);
    prefetch (result, 1, 0);
    size_t nBytes (distance (first, last));
    movsb_dir_down();
    size_t nHeadBytes = uintptr_t(last) % 4;
    last = advance (last, -1);
    result = advance (result, -1);
    movsb (last, nHeadBytes, result);
    nBytes -= nHeadBytes;
    if (uintptr_t(result) % 4 == 3) {
	const size_t nMiddleBlocks = nBytes / 4;
	last = advance (last, -3);
	result = advance (result, -3);
	movsd (last, nMiddleBlocks, result);
	nBytes %= 4;
    }
    movsb (last, nBytes, result);
    movsb_dir_up();
}
#endif // __i386__

//----------------------------------------------------------------------
// Fill functions
//----------------------------------------------------------------------

#if CPU_HAS_MMX
template <typename T> inline void build_block (T) {}
template <> inline void build_block (uint8_t v)
{
    asm volatile (
	"movd %0, %%mm0\n\tpunpcklbw %%mm0, %%mm0\n\tpshufw $0, %%mm0, %%mm0"
	: : "g"(uint32_t(v)) : "mm0");
}
template <> inline void build_block (uint16_t v)
{
    asm volatile (
	"movd %0, %%mm0\n\tpshufw $0, %%mm0, %%mm0"
	: : "g"(uint32_t(v)) : "mm0");
}
template <> inline void build_block (uint32_t v)
{
    asm volatile (
	"movd %0, %%mm0\n\tpunpckldq %%mm0, %%mm0"
	: : "g"(uint32_t(v)) : "mm0");
}

static inline void simd_block_fill_loop (uint8_t*& dest, size_t count)
{
    prefetch (advance (dest, 512), 1, 0);
    for (uoff_t i = 0; i < count; ++ i, dest += MMX_BS)
	simd_block_store (dest);
    simd_block_cleanup();
    simd::reset_mmx();
}

template <typename T>
inline void fill_n_fast (T* dest, size_t count, T v)
{
    size_t nHead = Align(uintptr_t(dest), MMX_ALIGN) - uintptr_t(dest) / sizeof(T);
    nHead = min (nHead, count);
    stosv (dest, nHead, v);
    count -= nHead;
    build_block (v);
    simd_block_fill_loop ((uint8_t*&) dest, count * sizeof(T) / MMX_BS);
    count %= MMX_BS;
    stosv (dest, count, v);
}

void fill_n8_fast (uint8_t* dest, size_t count, uint8_t v)
    { fill_n_fast (dest, count, v); }
void fill_n16_fast (uint16_t* dest, size_t count, uint16_t v)
    { fill_n_fast (dest, count, v); }
void fill_n32_fast (uint32_t* dest, size_t count, uint32_t v)
    { fill_n_fast (dest, count, v); }
#else
void fill_n8_fast (uint8_t* dest, size_t count, uint8_t v) { memset (dest, v, count); }
void fill_n16_fast (uint16_t* dest, size_t count, uint16_t v) { stosv (dest, count, v); }
void fill_n32_fast (uint32_t* dest, size_t count, uint32_t v) { stosv (dest, count, v); }
#endif // CPU_HAS_MMX

/// Exchanges ranges [first, middle) and [middle, last)
void rotate_fast (void* first, void* middle, void* last)
{
#ifdef HAVE_ALLOCA_H
    const size_t half1 (distance (first, middle)), half2 (distance (middle, last));
    const size_t hmin (min (half1, half2));
  if (!hmin) {
	return;
  }
    void* buf = alloca (hmin);
    if (buf) {
	if (half2 < half1) {
	    copy_n_fast (middle, half2, buf);
	    copy_backward_fast (first, middle, last);
	    copy_n_fast (buf, half2, first);
	} else {
	    copy_n_fast (first, half1, buf);
	    copy_n_fast (middle, half2, first);
	    copy_n_fast (buf, half1, advance (first, half2));
	}
    } else
#else
    if (first == middle || middle == last) {
	return;
    }
#endif
    {
	char* f = (char*) first;
	char* m = (char*) middle;
	char* l = (char*) last;
	reverse (f, m);
	reverse (m, l);
	while (f != m && m != l)
	    iter_swap (f++, --l);
	reverse (f, (f == m ? l : m));
    }
}

#if __GNUC__ < 4
size_t popcount (uint32_t v)
{
    const uint32_t w = v - ((v >> 1) & 0x55555555); // Algorithm from AMD optimization guide
    const uint32_t x = (w & 0x33333333) + ((w >> 2) & 0x33333333);
    return (((x + (x >> 4) & 0x0F0F0F0F) * 0x01010101) >> 24);
}

#if HAVE_INT64_T
/// \brief Returns the number of 1s in \p v in binary.
size_t popcount (uint64_t v)
{
    v -= (v >> 1) & UINT64_C(0x5555555555555555);		// Algorithm from Wikipedia
    v = (v & UINT64_C(0x3333333333333333)) + ((v >> 2) & UINT64_C(0x3333333333333333));
    v = (v + (v >> 4)) & UINT64_C(0x0F0F0F0F0F0F0F0F);
    return ((v * UINT64_C(0x0101010101010101)) >> 56);
}
#endif	// HAVE_INT64_T
#endif	// !__GNUC__

} // namespace ustl

