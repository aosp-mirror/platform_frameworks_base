// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// utuple.h
//

#ifndef UTUPLE_H_7324ADEC49B397CA74A56F6050FD5A6B
#define UTUPLE_H_7324ADEC49B397CA74A56F6050FD5A6B

#include "ualgo.h"

#if PLATFORM_ANDROID
#undef CPU_HAS_MMX
#endif

namespace ustl {

/// \class tuple utuple.h ustl.h
/// \ingroup Sequences
///
/// \brief A fixed-size array of \p N \p Ts.
///
template <size_t N, typename T>
class tuple {
public:
    typedef T						value_type;
    typedef size_t					size_type;
    typedef value_type*					pointer;
    typedef const value_type*				const_pointer;
    typedef value_type&					reference;
    typedef const value_type&				const_reference;
    typedef pointer					iterator;
    typedef const_pointer				const_iterator;
    typedef ::ustl::reverse_iterator<iterator>		reverse_iterator;
    typedef ::ustl::reverse_iterator<const_iterator>	const_reverse_iterator;
    typedef pair<iterator,iterator>			range_t;
    typedef pair<const_iterator,const_iterator>		const_range_t;
public:
    template <typename T2>
    inline			tuple (const tuple<N,T2>& t);
    inline			tuple (const tuple<N,T>& t);
    inline			tuple (const_pointer v);
    inline			tuple (void)			{ for (uoff_t i = 0; i < N; ++ i) m_v[i] = T(); }
    explicit inline		tuple (const_reference v0, const_reference v1 = T(), const_reference v2 = T(), const_reference v3 = T());
    inline iterator		begin (void)			{ return (m_v); }
    inline const_iterator	begin (void) const		{ return (m_v); }
    inline iterator		end (void)			{ return (begin() + N); }
    inline const_iterator	end (void) const		{ return (begin() + N); }
    inline size_type		size (void) const		{ return (N); }
    inline size_type		max_size (void) const		{ return (N); }
    inline bool			empty (void) const		{ return (N == 0); }
    inline const_reference	at (size_type i) const		{ return (m_v[i]); }
    inline reference		at (size_type i)		{ return (m_v[i]); }
    inline const_reference	operator[] (size_type i) const	{ return (m_v[i]); }
    inline reference		operator[] (size_type i)	{ return (m_v[i]); }
    template <typename T2>
    inline const tuple&		operator= (const tuple<N,T2>& src);
    inline const tuple&		operator= (const tuple<N,T>& src);
    inline const tuple&		operator+= (const_reference v)
				    { for (uoff_t i = 0; i < N; ++ i) m_v[i] += v; return (*this); }
    inline const tuple&		operator-= (const_reference v)
				    { for (uoff_t i = 0; i < N; ++ i) m_v[i] -= v; return (*this); }
    inline const tuple&		operator*= (const_reference v)
				    { for (uoff_t i = 0; i < N; ++ i) m_v[i] *= v; return (*this); }
    inline const tuple&		operator/= (const_reference v)
				    { for (uoff_t i = 0; i < N; ++ i) m_v[i] /= v; return (*this); }
    inline const tuple		operator+ (const_reference v) const
				    { tuple result; for (uoff_t i = 0; i < N; ++ i) result[i] = m_v[i] + v; return (result); }
    inline const tuple		operator- (const_reference v) const
				    { tuple result; for (uoff_t i = 0; i < N; ++ i) result[i] = m_v[i] - v; return (result); }
    inline const tuple		operator* (const_reference v) const
				    { tuple result; for (uoff_t i = 0; i < N; ++ i) result[i] = m_v[i] * v; return (result); }
    inline const tuple		operator/ (const_reference v) const
				    { tuple result; for (uoff_t i = 0; i < N; ++ i) result[i] = m_v[i] / v; return (result); }
    inline void			swap (tuple<N,T>& v)
				    { for (uoff_t i = 0; i < N; ++ i) ::ustl::swap (m_v[i], v.m_v[i]); }
private:
    T				m_v [N];
};

} // namespace ustl

#include "simd.h"

namespace ustl {

template <size_t N, typename T>
template <typename T2>
inline tuple<N,T>::tuple (const tuple<N,T2>& t)
{ simd::pconvert (t, *this, simd::fcast<T2,T>()); }

template <size_t N, typename T>
inline tuple<N,T>::tuple (const tuple<N,T>& t)
{ simd::passign (t, *this); }

template <size_t N, typename T>
inline tuple<N,T>::tuple (const_pointer v)
{ simd::ipassign (v, *this); }

template <size_t N, typename T>
inline tuple<N,T>::tuple (const_reference v0, const_reference v1, const_reference v2, const_reference v3)
{
    m_v[0] = v0;
    if (N > 1) m_v[1] = v1;
    if (N > 2) m_v[2] = v2;
    if (N > 3) m_v[3] = v3;
    if (N > 4) fill_n (m_v + 4, N - 4, T());
}

template <size_t N, typename T>
template <typename T2>
inline const tuple<N,T>& tuple<N,T>::operator= (const tuple<N,T2>& src)
{ simd::pconvert (src, *this, simd::fcast<T2,T>()); return (*this); }

template <size_t N, typename T>
inline const tuple<N,T>& tuple<N,T>::operator= (const tuple<N,T>& src)
{ simd::passign (src, *this); return (*this); }

template <size_t N, typename T1, typename T2>
inline bool operator== (const tuple<N,T1>& t1, const tuple<N,T2>& t2)
{
    for (uoff_t i = 0; i < N; ++ i)
	if (t1[i] != t2[i])
	    return (false);
    return (true);
}

template <size_t N, typename T1, typename T2>
inline bool operator< (const tuple<N,T1>& t1, const tuple<N,T2>& t2)
{
    for (uoff_t i = 0; i < N && t1[i] <= t2[i]; ++ i)
	if (t1[i] < t2[i])
	    return (true);
    return (false);
}

template <size_t N, typename T1, typename T2>
inline const tuple<N,T1>& operator+= (tuple<N,T1>& t1, const tuple<N,T2>& t2)
    { for (uoff_t i = 0; i < N; ++ i) t1[i] = T1(t1[i] + t2[i]); return (t1); }

template <size_t N, typename T1, typename T2>
inline const tuple<N,T1>& operator-= (tuple<N,T1>& t1, const tuple<N,T2>& t2)
    { for (uoff_t i = 0; i < N; ++ i) t1[i] = T1(t1[i] - t2[i]); return (t1); }

template <size_t N, typename T1, typename T2>
inline const tuple<N,T1>& operator*= (tuple<N,T1>& t1, const tuple<N,T2>& t2)
    { for (uoff_t i = 0; i < N; ++ i) t1[i] = T1(t1[i] * t2[i]); return (t1); }

template <size_t N, typename T1, typename T2>
inline const tuple<N,T1>& operator/= (tuple<N,T1>& t1, const tuple<N,T2>& t2)
    { for (uoff_t i = 0; i < N; ++ i) t1[i] = T1(t1[i] / t2[i]); return (t1); }

template <size_t N, typename T1, typename T2>
inline const tuple<N,T1> operator+ (const tuple<N,T1>& t1, const tuple<N,T2>& t2)
{
    tuple<N,T1> result;
    for (uoff_t i = 0; i < N; ++ i) result[i] = T1(t1[i] + t2[i]);
    return (result);
}

template <size_t N, typename T1, typename T2>
inline const tuple<N,T1> operator- (const tuple<N,T1>& t1, const tuple<N,T2>& t2)
{
    tuple<N,T1> result;
    for (uoff_t i = 0; i < N; ++ i) result[i] = T1(t1[i] - t2[i]);
    return (result);
}

template <size_t N, typename T1, typename T2>
inline const tuple<N,T1> operator* (const tuple<N,T1>& t1, const tuple<N,T2>& t2)
{
    tuple<N,T1> result;
    for (uoff_t i = 0; i < N; ++ i) result[i] = T1(t1[i] * t2[i]);
    return (result);
}

template <size_t N, typename T1, typename T2>
inline const tuple<N,T1> operator/ (const tuple<N,T1>& t1, const tuple<N,T2>& t2)
{
    tuple<N,T1> result;
    for (uoff_t i = 0; i < N; ++ i) result[i] = T1(t1[i] / t2[i]);
    return (result);
}

#if CPU_HAS_SSE
#define SSE_TUPLE_SPECS(n,type)		\
template <> inline tuple<n,type>::tuple (void)	\
{ asm ("xorps %%xmm0, %%xmm0\n\tmovups %%xmm0, %0"::"m"(m_v[0]):"xmm0","memory"); }	\
template<> inline void tuple<n,type>::swap (tuple<n,type>& v)	\
{ asm ("movups %0,%%xmm0\n\tmovups %1,%%xmm1\n\tmovups %%xmm0,%1\n\tmovups %%xmm1,%0"::"m"(m_v[0]),"m"(v.m_v[0]):"xmm0","xmm1","memory"); }
SSE_TUPLE_SPECS(4,float)
SSE_TUPLE_SPECS(4,int32_t)
SSE_TUPLE_SPECS(4,uint32_t)
#undef SSE_TUPLE_SPECS
#endif
#if CPU_HAS_MMX
#define MMX_TUPLE_SPECS(n,type)		\
template <> inline tuple<n,type>::tuple (void)	\
{ asm ("pxor %%mm0, %%mm0\n\tmovq %%mm0, %0"::"m"(m_v[0]):"mm0","memory"); simd::reset_mmx(); }	\
template<> inline void tuple<n,type>::swap (tuple<n,type>& v)	\
{ asm ("movq %0,%%mm0\n\tmovq %1,%%mm1\n\tmovq %%mm0,%1\n\tmovq %%mm1,%0"::"m"(m_v[0]),"m"(v.m_v[0]):"mm0","mm1","memory"); simd::reset_mmx(); }
MMX_TUPLE_SPECS(2,float)
MMX_TUPLE_SPECS(4,int16_t)
MMX_TUPLE_SPECS(4,uint16_t)
MMX_TUPLE_SPECS(2,int32_t)
MMX_TUPLE_SPECS(2,uint32_t)
MMX_TUPLE_SPECS(8,int8_t)
MMX_TUPLE_SPECS(8,uint8_t)
#undef MMX_TUPLE_SPECS
#endif

#define SIMD_TUPLE_PACKOP(N,T)	\
template <> inline const tuple<N,T>& operator+= (tuple<N,T>& t1, const tuple<N,T>& t2)	\
    { simd::padd (t2, t1); return (t1); }						\
template <> inline const tuple<N,T>& operator-= (tuple<N,T>& t1, const tuple<N,T>& t2)	\
    { simd::psub (t2, t1); return (t1); }						\
template <> inline const tuple<N,T>& operator*= (tuple<N,T>& t1, const tuple<N,T>& t2)	\
    { simd::pmul (t2, t1); return (t1); }						\
template <> inline const tuple<N,T>& operator/= (tuple<N,T>& t1, const tuple<N,T>& t2)	\
    { simd::pdiv (t2, t1); return (t1); }						\
template <> inline const tuple<N,T> operator+ (const tuple<N,T>& t1, const tuple<N,T>& t2) \
    { tuple<N,T> result (t1); simd::padd (t2, result); return (result); }		\
template <> inline const tuple<N,T> operator- (const tuple<N,T>& t1, const tuple<N,T>& t2) \
    { tuple<N,T> result (t1); simd::psub (t2, result); return (result); }		\
template <> inline const tuple<N,T> operator* (const tuple<N,T>& t1, const tuple<N,T>& t2) \
    { tuple<N,T> result (t1); simd::pmul (t2, result); return (result); }		\
template <> inline const tuple<N,T> operator/ (const tuple<N,T>& t1, const tuple<N,T>& t2) \
    { tuple<N,T> result (t1); simd::pdiv (t2, result); return (result); }
SIMD_TUPLE_PACKOP(4,float)
SIMD_TUPLE_PACKOP(2,float)
SIMD_TUPLE_PACKOP(2,double)
SIMD_TUPLE_PACKOP(4,int32_t)
SIMD_TUPLE_PACKOP(4,uint32_t)
SIMD_TUPLE_PACKOP(4,int16_t)
SIMD_TUPLE_PACKOP(4,uint16_t)
SIMD_TUPLE_PACKOP(2,int32_t)
SIMD_TUPLE_PACKOP(2,uint32_t)
SIMD_TUPLE_PACKOP(8,int8_t)
SIMD_TUPLE_PACKOP(8,uint8_t)
#undef SIMD_TUPLE_PACKOP

} // namespace ustl

#endif

