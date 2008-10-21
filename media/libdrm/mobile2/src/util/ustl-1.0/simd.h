// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
/// \file simd.h
/// \brief SIMD-type algorithms, with hardware acceleration, if available.
///
/// All algorithms are container-based because iterator syntax is just too
/// damn verbose and because the specializations need to be able to tell
/// how many elements are in the container in order to choose proper SIMD
/// instruction set (i.e.: 4 floats select SSE, while 2 floats select 3dNow!)
/// Specializations are only for the tuple template because the container
/// must be of a fixed and compile-time-known size for the compiler to be
/// able to choose the specialization.
///

#ifndef SIMD_H_39BE2D970DF4BD00508CCFFB482496F9
#define SIMD_H_39BE2D970DF4BD00508CCFFB482496F9

#include "uassert.h"
#include "ulimits.h"
#if HAVE_MATH_H
    #include <math.h>
#endif

#if PLATFORM_ANDROID
#include <stdio.h>
#undef CPU_HAS_MMX
#endif

namespace ustl {
namespace simd {

//----------------------------------------------------------------------
// Generic algorithms
//----------------------------------------------------------------------

/// Applies \p op to each element in \p op1.
template <typename Ctr, typename UnaryOperation>
inline void packop (Ctr& op1, UnaryOperation op)
{
    foreach (typename Ctr::iterator, i, op1)
	op (*i);
}

/// Applies \p op to each element in \p op1 and \p op2 and stores in \p op2.
template <typename Ctr, typename BinaryOperation>
inline void packop (const Ctr& op1, Ctr& op2, BinaryOperation op)
{
    assert (op2.size() <= op1.size());
    typename Ctr::const_iterator i1 (op1.begin());
    typename Ctr::iterator i2 (op2.begin());
    for (; i2 != op2.end(); ++i1, ++i2)
	*i2 = op (*i2, *i1);
}

/// Applies \p op to corresponding elements in \p op1 and \p op2 and stores in \p result.
template <typename Ctr, typename BinaryOperation>
inline void packop (const Ctr& op1, const Ctr& op2, Ctr& result, BinaryOperation op)
{
    assert (op1.size() <= op2.size() && op1.size() <= result.size());
    passign (op1, result);
    packop (op2, result);
}

/// Copies \p op1 into \p result.
template <typename Ctr>
inline void passign (const Ctr& op1, Ctr& result)
{
    assert (op1.size() <= result.size());
    typename Ctr::iterator d (result.begin());
    foreach (typename Ctr::const_iterator, s, op1)
	*d++ = *s;
}

/// Copies \p result.size() elements from \p op1 to \p result.
template <typename Ctr>
inline void ipassign (typename Ctr::const_iterator op1, Ctr& result)
{
    foreach (typename Ctr::iterator, d, result)
	*d = *op1++;
}

template <typename Ctr1, typename Ctr2, typename ConvertFunction>
inline void pconvert (const Ctr1& op1, Ctr2& op2, ConvertFunction f)
{
    assert (op1.size() <= op2.size());
    typename Ctr1::const_iterator i1 (op1.begin());
    typename Ctr2::iterator i2 (op2.begin());
    for (; i1 != op1.end(); ++i1, ++i2)
	*i2 = f (*i1);
}

// Functionoids for SIMD operations, like saturation arithmetic, shifts, etc.
STD_BINARY_FUNCTOR (fpadds, T, ((b > numeric_limits<T>::max() - a) ? numeric_limits<T>::max() : a + b))
STD_BINARY_FUNCTOR (fpsubs, T, ((a < numeric_limits<T>::min() + b) ? numeric_limits<T>::min() : a - b))
STD_BINARY_FUNCTOR (fpshl,  T, (a << b))
STD_BINARY_FUNCTOR (fpshr,  T, (a >> b))
STD_BINARY_FUNCTOR (fpmin,  T, (min (a, b)))
STD_BINARY_FUNCTOR (fpmax,  T, (max (a, b)))
STD_BINARY_FUNCTOR (fpavg,  T, ((a + b + 1) / 2))
STD_CONVERSION_FUNCTOR (fcast, (D(a)))
#if HAVE_MATH_H
STD_UNARY_FUNCTOR (fpreciprocal,T, (1 / a))
STD_UNARY_FUNCTOR (fpsqrt,	T, (reset_mmx(), T (sqrt (a))))
STD_UNARY_FUNCTOR (fprecipsqrt,	T, (reset_mmx(), 1 / T(sqrt (a))))
STD_UNARY_FUNCTOR (fsin,	T, (reset_mmx(), T (sin (a))))
STD_UNARY_FUNCTOR (fcos,	T, (reset_mmx(), T (cos (a))))
STD_UNARY_FUNCTOR (ftan,	T, (reset_mmx(), T (tan (a))))
#if HAVE_RINTF
STD_CONVERSION_FUNCTOR (fround, (reset_mmx(), D(rintf(a))))
#else
STD_CONVERSION_FUNCTOR (fround, (reset_mmx(), D(rint(a))))
#endif
template <> inline int32_t fround<double,int32_t>::operator()(const double& a) const { reset_mmx(); return (int32_t(rint(a))); }
#endif
template <> inline float fpavg<float>::operator()(const float& a, const float& b) const { return ((a + b) / 2); }
template <> inline double fpavg<double>::operator()(const double& a, const double& b) const { return ((a + b) / 2); }

#define SIMD_PACKEDOP1(name, operation)		\
template <typename Ctr>				\
inline void name (Ctr& op1)			\
{						\
    typedef typename Ctr::value_type value_t;	\
    packop (op1, operation<value_t>());		\
}
#define SIMD_PACKEDOP2(name, operation)		\
template <typename Ctr>				\
inline void name (const Ctr& op1, Ctr& op2)	\
{						\
    typedef typename Ctr::value_type value_t;	\
    packop (op1, op2, operation<value_t>());	\
}
#define SIMD_PACKEDOP3(name, operation)			\
template <typename Ctr>					\
inline void name (const Ctr& op1, const Ctr& op2, Ctr& result)	\
{							\
    typedef typename Ctr::value_type value_t;		\
    packop (op1, op2, result, operation<value_t>());	\
}
#define SIMD_SINGLEOP1(name, operation)		\
template <typename T>				\
inline T name (T op)				\
{						\
    operation<T> obj;				\
    return (obj(op));				\
}
#define SIMD_CONVERTOP(name, operation)		\
template <typename Ctr1, typename Ctr2>		\
inline void name (const Ctr1& op1, Ctr2& op2)	\
{						\
    typedef typename Ctr1::value_type value1_t;	\
    typedef typename Ctr2::value_type value2_t;	\
    pconvert (op1, op2, operation<value1_t, value2_t>());\
}

SIMD_PACKEDOP2 (padd, plus)
SIMD_PACKEDOP2 (psub, minus)
SIMD_PACKEDOP2 (pmul, multiplies)
SIMD_PACKEDOP2 (pdiv, divides)
SIMD_PACKEDOP2 (pand, bitwise_and)
SIMD_PACKEDOP2 (por, bitwise_or)
SIMD_PACKEDOP2 (pxor, bitwise_xor)
SIMD_PACKEDOP2 (pshl, fpshl)
SIMD_PACKEDOP2 (pshr, fpshr)
SIMD_PACKEDOP2 (psubs, fpsubs)
SIMD_PACKEDOP2 (pmin, fpmin)
SIMD_PACKEDOP2 (pmax, fpmax)
SIMD_PACKEDOP2 (pavg, fpavg)

SIMD_PACKEDOP3 (padd, plus)
SIMD_PACKEDOP3 (psub, minus)
SIMD_PACKEDOP3 (pmul, multiplies)
SIMD_PACKEDOP3 (pdiv, divides)
SIMD_PACKEDOP3 (pand, bitwise_and)
SIMD_PACKEDOP3 (por, bitwise_or)
SIMD_PACKEDOP3 (pxor, bitwise_xor)
SIMD_PACKEDOP3 (pshl, fpshl)
SIMD_PACKEDOP3 (pshr, fpshr)
SIMD_PACKEDOP3 (padds, fpadds)
SIMD_PACKEDOP3 (psubs, fpsubs)
SIMD_PACKEDOP3 (pmin, fpmin)
SIMD_PACKEDOP3 (pmax, fpmax)
SIMD_PACKEDOP3 (pavg, fpavg)

#if HAVE_MATH_H
SIMD_PACKEDOP1 (precip, fpreciprocal)
SIMD_PACKEDOP1 (psqrt, fpsqrt)
SIMD_PACKEDOP1 (precipsqrt, fprecipsqrt)
SIMD_PACKEDOP1 (psin, fsin)
SIMD_PACKEDOP1 (pcos, fcos)
SIMD_PACKEDOP1 (ptan, ftan)

SIMD_SINGLEOP1 (srecip, fpreciprocal)
SIMD_SINGLEOP1 (ssqrt, fpsqrt)
SIMD_SINGLEOP1 (srecipsqrt, fprecipsqrt)
SIMD_SINGLEOP1 (ssin, fsin)
SIMD_SINGLEOP1 (scos, fcos)
SIMD_SINGLEOP1 (stan, ftan)

SIMD_CONVERTOP (pround, fround)

template <typename T> inline int32_t sround (T op) { fround<T,int32_t> obj; return (obj (op)); }
#endif

#undef SIMD_SINGLEOP1
#undef SIMD_PACKEDOP3
#undef SIMD_PACKEDOP2
#undef SIMD_PACKEDOP1

//----------------------------------------------------------------------
// Vector types to cast tuple data to
//----------------------------------------------------------------------

#if HAVE_VECTOR_EXTENSIONS && __GNUC__ >= 4
#define VECTOR_ATTRIBUTE(mode,vs)	__attribute__((vector_size(vs)))
#else
#define VECTOR_ATTRIBUTE(mode,vs)
#endif
typedef uint8_t v8qi_t VECTOR_ATTRIBUTE (V8QI,8);
typedef uint16_t v4hi_t VECTOR_ATTRIBUTE (V4HI,8);
typedef uint16_t v8hi_t VECTOR_ATTRIBUTE (V8HI,16);
typedef uint32_t v2si_t VECTOR_ATTRIBUTE (V2SI,8);
typedef uint32_t v4si_t VECTOR_ATTRIBUTE (V4SI,16);
#if HAVE_INT64_T
typedef uint64_t v1di_t VECTOR_ATTRIBUTE (V1DI,8);
#endif
typedef float v2sf_t VECTOR_ATTRIBUTE (V2SF,8);
typedef float v4sf_t VECTOR_ATTRIBUTE (V4SF,16);
typedef double v2df_t VECTOR_ATTRIBUTE (V2DF,16);
#undef VECTOR_ATTRIBUTE

//----------------------------------------------------------------------
// Hardware accelerated specializations
//----------------------------------------------------------------------

#define SIMD_PKOP2_SPEC(n, type, optype)	\
template <>					\
inline void packop (const tuple<n,type>& oin, tuple<n,type>& oout, optype<type>)
#define SIMD_PASSIGN_SPEC(n, type)		\
template <>					\
inline void passign (const tuple<n,type>& oin, tuple<n,type>& oout)
#define SIMD_IPASSIGN_SPEC(n, type)		\
template <>					\
inline void ipassign (tuple<n,type>::const_iterator oin, tuple<n,type>& oout)
#define SIMD_CONVERT_SPEC(n, type1, type2, optype)	\
template <>					\
inline void pconvert (const tuple<n,type1>& oin, tuple<n,type2>& oout, optype<type1,type2>)

#if CPU_HAS_MMX
#define STD_MMX_ARGS	"=m"(oout[0]) : "m"(oin[0]) : "mm0", "st", "memory"
#define DBL_MMX_ARGS	"=m"(oout[0]), "=m"(oout[2]) : "m"(oin[0]), "m"(oin[2]) : "mm0", "mm1", "st", "st(1)", "memory"
#define MMX_PKOP2_SPEC(n,type,optype,instruction)	\
SIMD_PKOP2_SPEC(n,type,optype)		\
{ asm ("movq %0, %%mm0\n\t" #instruction " %1, %%mm0\n\tmovq %%mm0, %0" : STD_MMX_ARGS); reset_mmx(); }
#define MMX_DBL_PKOP2_SPEC(n,type,optype,instruction)	\
SIMD_PKOP2_SPEC(n,type,optype)		\
{ asm ("movq %0, %%mm0\n\tmovq %1, %%mm1\n\t" #instruction " %2, %%mm0\n\t" #instruction " %3, %%mm1\n\tmovq %%mm0, %0\n\tmovq %%mm1, %1" : DBL_MMX_ARGS); reset_mmx(); }
#define MMX_PASSIGN_SPEC(n,type)	\
SIMD_PASSIGN_SPEC(n,type)		\
{ asm ("movq %1, %%mm0\n\tmovq %%mm0, %0" : STD_MMX_ARGS); reset_mmx(); }
#define MMX_DBL_PASSIGN_SPEC(n,type)	\
SIMD_PASSIGN_SPEC(n,type)		\
{ asm ("movq %2, %%mm0\n\tmovq %3, %%mm1\n\tmovq %%mm0, %0\n\tmovq %%mm1, %1" : DBL_MMX_ARGS); reset_mmx(); }
#define MMX_IPASSIGN_SPEC(n,type)	\
SIMD_IPASSIGN_SPEC(n,type)		\
{ asm ("movq %1, %%mm0\n\tmovq %%mm0, %0" : STD_MMX_ARGS); reset_mmx(); }
#define MMX_DBL_IPASSIGN_SPEC(n,type)	\
SIMD_IPASSIGN_SPEC(n,type)		\
{ asm ("movq %2, %%mm0\n\tmovq %3, %%mm1\n\tmovq %%mm0, %0\n\tmovq %%mm1, %1" : DBL_MMX_ARGS); reset_mmx(); }

MMX_PASSIGN_SPEC(8,uint8_t)
MMX_PKOP2_SPEC(8,uint8_t,plus,paddb)
MMX_PKOP2_SPEC(8,uint8_t,minus,psubb)
MMX_PKOP2_SPEC(8,uint8_t,bitwise_and,pand)
MMX_PKOP2_SPEC(8,uint8_t,bitwise_or,por)
MMX_PKOP2_SPEC(8,uint8_t,bitwise_xor,pxor)
MMX_PKOP2_SPEC(8,uint8_t,fpadds,paddusb)
MMX_PKOP2_SPEC(8,uint8_t,fpsubs,psubusb)

MMX_PASSIGN_SPEC(8,int8_t)
MMX_PKOP2_SPEC(8,int8_t,plus,paddb)
MMX_PKOP2_SPEC(8,int8_t,minus,psubb)
MMX_PKOP2_SPEC(8,int8_t,bitwise_and,pand)
MMX_PKOP2_SPEC(8,int8_t,bitwise_or,por)
MMX_PKOP2_SPEC(8,int8_t,bitwise_xor,pxor)
MMX_PKOP2_SPEC(8,int8_t,fpadds,paddsb)
MMX_PKOP2_SPEC(8,int8_t,fpsubs,psubsb)

MMX_PASSIGN_SPEC(4,uint16_t)
MMX_PKOP2_SPEC(4,uint16_t,plus,paddw)
MMX_PKOP2_SPEC(4,uint16_t,minus,psubw)
MMX_PKOP2_SPEC(4,uint16_t,bitwise_and,pand)
MMX_PKOP2_SPEC(4,uint16_t,bitwise_or,por)
MMX_PKOP2_SPEC(4,uint16_t,bitwise_xor,pxor)
/// \todo psllw does not work like other operations, it uses the first element for shift count.
//MMX_PKOP2_SPEC(4,uint16_t,fpshl,psllw)
//MMX_PKOP2_SPEC(4,uint16_t,fpshr,psrlw)
MMX_PKOP2_SPEC(4,uint16_t,fpadds,paddusw)
MMX_PKOP2_SPEC(4,uint16_t,fpsubs,psubusw)

MMX_PASSIGN_SPEC(4,int16_t)
MMX_PKOP2_SPEC(4,int16_t,plus,paddw)
MMX_PKOP2_SPEC(4,int16_t,minus,psubw)
MMX_PKOP2_SPEC(4,int16_t,bitwise_and,pand)
MMX_PKOP2_SPEC(4,int16_t,bitwise_or,por)
MMX_PKOP2_SPEC(4,int16_t,bitwise_xor,pxor)
//MMX_PKOP2_SPEC(4,int16_t,fpshl,psllw)
//MMX_PKOP2_SPEC(4,int16_t,fpshr,psrlw)
MMX_PKOP2_SPEC(4,int16_t,fpadds,paddsw)
MMX_PKOP2_SPEC(4,int16_t,fpsubs,psubsw)

MMX_PASSIGN_SPEC(2,uint32_t)
MMX_PKOP2_SPEC(2,uint32_t,plus,paddd)
MMX_PKOP2_SPEC(2,uint32_t,minus,psubd)
MMX_PKOP2_SPEC(2,uint32_t,bitwise_and,pand)
MMX_PKOP2_SPEC(2,uint32_t,bitwise_or,por)
MMX_PKOP2_SPEC(2,uint32_t,bitwise_xor,pxor)
//MMX_PKOP2_SPEC(2,uint32_t,fpshl,pslld)
//MMX_PKOP2_SPEC(2,uint32_t,fpshr,psrld)

MMX_PASSIGN_SPEC(2,int32_t)
MMX_PKOP2_SPEC(2,int32_t,plus,paddd)
MMX_PKOP2_SPEC(2,int32_t,minus,psubd)
MMX_PKOP2_SPEC(2,int32_t,bitwise_and,pand)
MMX_PKOP2_SPEC(2,int32_t,bitwise_or,por)
MMX_PKOP2_SPEC(2,int32_t,bitwise_xor,pxor)
//MMX_PKOP2_SPEC(2,int32_t,fpshl,pslld)
//MMX_PKOP2_SPEC(2,int32_t,fpshr,psrld)

MMX_DBL_PKOP2_SPEC(4,uint32_t,plus,paddd)
MMX_DBL_PKOP2_SPEC(4,uint32_t,minus,psubd)
MMX_DBL_PKOP2_SPEC(4,uint32_t,bitwise_and,pand)
MMX_DBL_PKOP2_SPEC(4,uint32_t,bitwise_or,por)
MMX_DBL_PKOP2_SPEC(4,uint32_t,bitwise_xor,pxor)
//MMX_DBL_PKOP2_SPEC(2,uint32_t,fpshl,pslld)
//MMX_DBL_PKOP2_SPEC(2,uint32_t,fpshr,psrld)

MMX_DBL_PKOP2_SPEC(4,int32_t,plus,paddd)
MMX_DBL_PKOP2_SPEC(4,int32_t,minus,psubd)
MMX_DBL_PKOP2_SPEC(4,int32_t,bitwise_and,pand)
MMX_DBL_PKOP2_SPEC(4,int32_t,bitwise_or,por)
MMX_DBL_PKOP2_SPEC(4,int32_t,bitwise_xor,pxor)
//MMX_DBL_PKOP2_SPEC(2,int32_t,fpshl,pslld)
//MMX_DBL_PKOP2_SPEC(2,int32_t,fpshr,psrld)

#if CPU_HAS_SSE || CPU_HAS_3DNOW
MMX_PKOP2_SPEC(8,uint8_t,fpavg,pavgb)
MMX_PKOP2_SPEC(8,int8_t,fpavg,pavgb)
MMX_PKOP2_SPEC(4,uint16_t,fpavg,pavgw)
MMX_PKOP2_SPEC(4,int16_t,fpavg,pavgw)
MMX_PKOP2_SPEC(8,uint8_t,fpmin,pminub)
MMX_PKOP2_SPEC(8,uint8_t,fpmax,pmaxub)
MMX_PKOP2_SPEC(4,int16_t,fpmax,pmaxsw)
MMX_PKOP2_SPEC(4,int16_t,fpmin,pminsw)
#endif // CPU_HAS_SSE || CPU_HAS_3DNOW

#if CPU_HAS_3DNOW
MMX_PASSIGN_SPEC(2,float)
MMX_PKOP2_SPEC(2,float,plus,pfadd)
MMX_PKOP2_SPEC(2,float,minus,pfsub)
MMX_PKOP2_SPEC(2,float,multiplies,pfmul)
MMX_PKOP2_SPEC(2,float,fpmin,pfmin)
MMX_PKOP2_SPEC(2,float,fpmax,pfmax)
#ifndef CPU_HAS_SSE
MMX_DBL_PKOP2_SPEC(4,float,plus,pfadd)
MMX_DBL_PKOP2_SPEC(4,float,minus,pfsub)
MMX_DBL_PKOP2_SPEC(4,float,multiplies,pfmul)
MMX_DBL_PKOP2_SPEC(4,float,fpmin,pfmin)
MMX_DBL_PKOP2_SPEC(4,float,fpmax,pfmax)
#endif
#endif // CPU_HAS_3DNOW

MMX_IPASSIGN_SPEC(8,uint8_t)
MMX_IPASSIGN_SPEC(4,uint16_t)
MMX_IPASSIGN_SPEC(2,uint32_t)
MMX_IPASSIGN_SPEC(2,float)

#ifndef CPU_HAS_SSE
MMX_DBL_PASSIGN_SPEC(4,float)
MMX_DBL_PASSIGN_SPEC(4,uint32_t)
MMX_DBL_PASSIGN_SPEC(4,int32_t)
MMX_DBL_IPASSIGN_SPEC(4,float)
MMX_DBL_IPASSIGN_SPEC(4,uint32_t)
MMX_DBL_IPASSIGN_SPEC(4,int32_t)
#endif

#undef MMX_IPASSIGN_SPEC
#undef MMX_PASSIGN_SPEC
#undef MMX_PKOP2_SPEC
#undef STD_MMX_ARGS
#endif // CPU_HAS_MMX

#if CPU_HAS_SSE
#define STD_SSE_ARGS	"=m"(oout[0]) : "m"(oin[0]) : "xmm0", "memory"
#define SSE_PKOP2_SPEC(n,type,optype,instruction)	\
SIMD_PKOP2_SPEC(n,type,optype)		\
{ asm ("movups %0, %%xmm0\n\tmovups %1, %%xmm1\n\t" #instruction " %%xmm1, %%xmm0\n\tmovups %%xmm0, %0" : STD_SSE_ARGS);}
#define SSE_PASSIGN_SPEC(n,type)			\
SIMD_PASSIGN_SPEC(n,type)		\
{ asm ("movups %1, %%xmm0\n\tmovups %%xmm0, %0" : STD_SSE_ARGS);}
#define SSE_IPASSIGN_SPEC(n,type)	\
SIMD_IPASSIGN_SPEC(n,type)		\
{ asm ("movups %1, %%xmm0\n\tmovups %%xmm0, %0" : STD_SSE_ARGS);}
SSE_PASSIGN_SPEC(4,float)
SSE_PASSIGN_SPEC(4,int32_t)
SSE_PASSIGN_SPEC(4,uint32_t)
SSE_PKOP2_SPEC(4,float,plus,addps)
SSE_PKOP2_SPEC(4,float,minus,subps)
SSE_PKOP2_SPEC(4,float,multiplies,mulps)
SSE_PKOP2_SPEC(4,float,divides,divps)
SSE_PKOP2_SPEC(4,float,bitwise_and,andps)
SSE_PKOP2_SPEC(4,float,bitwise_or,orps)
SSE_PKOP2_SPEC(4,float,bitwise_xor,xorps)
SSE_PKOP2_SPEC(4,float,fpmax,maxps)
SSE_PKOP2_SPEC(4,float,fpmin,minps)

SIMD_CONVERT_SPEC(4,float,int32_t,fround) {
    asm ("cvtps2pi %2, %%mm0\n\t"
	 "cvtps2pi %3, %%mm1\n\t"
	 "movq %%mm0, %0\n\t"
	 "movq %%mm1, %1"
	 : DBL_MMX_ARGS);
    reset_mmx();
}
SIMD_CONVERT_SPEC(4,int32_t,float,fround) {
    asm ("cvtpi2ps %2, %%xmm0\n\t"
	 "shufps $0x4E,%%xmm0,%%xmm0\n\t"
	 "cvtpi2ps %1, %%xmm0\n\t"
	 "movups %%xmm0, %0"
	 : "=m"(oout[0]) : "m"(oin[0]), "m"(oin[2]) : "xmm0", "memory");
}
template <> inline int32_t fround<float,int32_t>::operator()(const float& a) const {
    register int32_t rv;
    asm ("movss %1, %%xmm0\n\t"
	 "cvtss2si %%xmm0, %0"
	 : "=r"(rv) : "m"(a) : "xmm0" );
    return (rv);
}
template <> inline uint32_t fround<float,uint32_t>::operator()(const float& a) const {
    register uint32_t rv;
    asm ("movss %1, %%xmm0\n\t"
	 "cvtss2si %%xmm0, %0"
	 : "=r"(rv) : "m"(a) : "xmm0" );
    return (rv);
}

SSE_IPASSIGN_SPEC(4,float)
SSE_IPASSIGN_SPEC(4,int32_t)
SSE_IPASSIGN_SPEC(4,uint32_t)

#undef SSE_IPASSIGN_SPEC
#undef SSE_PASSIGN_SPEC
#undef SSE_PKOP2_SPEC
#undef STD_SSE_ARGS
#endif // CPU_HAS_SSE

#undef SIMD_PACKEDOP_SPEC

} // namespace simd
} // namespace ustl

#endif

