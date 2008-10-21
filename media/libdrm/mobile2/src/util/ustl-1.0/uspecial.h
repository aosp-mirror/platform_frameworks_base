// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// uspecial.h
//
// Template specializations for uSTL classes.
//

#ifndef USPECIAL_H_947ADYOU0ARE3YOU2REALLY8ARE44CE0
#define USPECIAL_H_947ADYOU0ARE3YOU2REALLY8ARE44CE0

#include "uassert.h"
#include "uvector.h"
#include "ustring.h"
#include "uset.h"
#include "umultiset.h"
#include "ubitset.h"
#include "ulaalgo.h"
#include "uctralgo.h"
#include "ufunction.h"
#include "uctrstrm.h"
#include "sistream.h"
#include <ctype.h>

namespace ustl {

//----------------------------------------------------------------------
// Alogrithm specializations not in use by the library code.
//----------------------------------------------------------------------

template <> inline void swap (cmemlink& a, cmemlink& b)			{ a.swap (b); }
template <> inline void swap (memlink& a, memlink& b)			{ a.swap (b); }
template <> inline void swap (memblock& a, memblock& b)			{ a.swap (b); }
template <> inline void swap (string& a, string& b)			{ a.swap (b); }
#define TEMPLATE_SWAP_PSPEC(type, template_decl)	\
template_decl inline void swap (type& a, type& b) { a.swap (b); }
TEMPLATE_SWAP_PSPEC (TEMPLATE_TYPE1 (vector,T),		TEMPLATE_DECL1 (T))
TEMPLATE_SWAP_PSPEC (TEMPLATE_TYPE1 (set,T),		TEMPLATE_DECL1 (T))
TEMPLATE_SWAP_PSPEC (TEMPLATE_TYPE1 (multiset,T),	TEMPLATE_DECL1 (T))
TEMPLATE_SWAP_PSPEC (TEMPLATE_TYPE2 (tuple,N,T),	TEMPLATE_FULL_DECL2 (size_t,N,typename,T))

//----------------------------------------------------------------------
// Streamable definitions. Not used in the library and require streams.
//----------------------------------------------------------------------

//----{ pair }----------------------------------------------------------

/// \brief Reads pair \p p from stream \p is.
template <typename T1, typename T2>
istream& operator>> (istream& is, pair<T1,T2>& p)
{
    is >> p.first;
    is.align (alignof(T2()));
    is >> p.second;
    is.align (alignof(T1()));
    return (is);
}

/// Writes pair \p p to stream \p os.
template <typename T1, typename T2>
ostream& operator<< (ostream& os, const pair<T1,T2>& p)
{
    os << p.first;
    os.align (alignof(T2()));
    os << p.second;
    os.align (alignof(T1()));
    return (os);
}

/// Writes pair \p p to stream \p os.
template <typename T1, typename T2>
ostringstream& operator<< (ostringstream& os, const pair<T1,T2>& p)
{
    os << '(' << p.first << ',' << p.second << ')';
    return (os);
}

/// Returns the written size of the object.
template <typename T1, typename T2>
inline size_t stream_size_of (const pair<T1,T2>& v)
{
    return (Align (stream_size_of(v.first), alignof(T2())) +
	    Align (stream_size_of(v.second), alignof(T1())));
}

/// \brief Takes a pair and returns pair.first
/// This is an extension, available in uSTL and the SGI STL.
template <typename Pair> struct select1st : public unary_function<Pair,typename Pair::first_type> {
    typedef typename Pair::first_type result_type;
    inline const result_type&	operator()(const Pair& a) const { return (a.first); }
    inline result_type&		operator()(Pair& a) const { return (a.first); }
};

/// \brief Takes a pair and returns pair.second
/// This is an extension, available in uSTL and the SGI STL.
template <typename Pair> struct select2nd : public unary_function<Pair,typename Pair::second_type> {
    typedef typename Pair::second_type result_type;
    inline const result_type&	operator()(const Pair& a) const { return (a.second); }
    inline result_type&		operator()(Pair& a) const { return (a.second); }
};

/// \brief Converts a const_iterator pair into an iterator pair
/// Useful for converting pair ranges returned by equal_range, for instance.
/// This is an extension, available in uSTL.
template <typename Container>
inline pair<typename Container::iterator, typename Container::iterator>
unconst (const pair<typename Container::const_iterator, typename Container::const_iterator>& i, Container& ctr)
{
    assert (i.first >= ctr.begin() && i.first <= ctr.end() && "unconst algorithm must be given iterators from the argument container");
    pair<typename Container::iterator, typename Container::iterator> result;
    result.first = ctr.begin() + (i.first - ctr.begin());
    result.second = ctr.begin() + (i.second - ctr.begin());
    return (result);
}

//----{ vector }--------------------------------------------------------

STD_TEMPLATE_CTR_STREAMABLE (TEMPLATE_TYPE1 (vector,T), TEMPLATE_DECL1 (T))

template <typename T>
inline size_t alignof (const vector<T>&)
{
    typedef typename vector<T>::written_size_type written_size_type;
    return (alignof (written_size_type()));
}

//----{ bitset }--------------------------------------------------------

/// Reads bitset \p v from stream \p is.
template <size_t Size>
inline istream& operator>> (istream& is, bitset<Size>& v)
{
    return (nr_container_read (is, v));
}

/// Writes bitset \p v into stream \p os.
template <size_t Size>
inline ostream& operator<< (ostream& os, const bitset<Size>& v)
{
    return (nr_container_write (os, v));
}

/// Writes bitset \p v into stream \p os.
template <size_t Size>
inline ostringstream& operator<< (ostringstream& os, const bitset<Size>& v)
{
    return (os << v.to_string());
}

/// Writes bitset \p v into stream \p os.
template <size_t Size>
istringstream& operator>> (istringstream& is, bitset<Size>& v)
{
    char c;
    for (int i = Size; --i >= 0 && (is >> c).good();)
	v.set (i, c == '1');
    return (is);
}

/// Returns the number of bytes necessary to write this object to a stream
template <size_t Size>
inline size_t stream_size_of (const bitset<Size>& v)
{
    return (v.capacity() / CHAR_BIT);
}

//----{ tuple }---------------------------------------------------------

STD_TEMPLATE_NR_CTR_STREAMABLE (
    TEMPLATE_TYPE2 (tuple,N,T),
    TEMPLATE_FULL_DECL2 (size_t,N,typename,T)
)

template <size_t N, typename T>
struct numeric_limits<tuple<N,T> > {
    typedef numeric_limits<T> value_limits;
    static inline tuple<N,T> min (void)	{ tuple<N,T> v; fill (v, value_limits::min()); return (v); }
    static inline tuple<N,T> max (void)	{ tuple<N,T> v; fill (v, value_limits::max()); return (v); }
    static const bool is_signed = value_limits::is_signed;
    static const bool is_integer = value_limits::is_integer;
    static const bool is_integral = value_limits::is_integral;
};

template <size_t N, typename T>
inline size_t alignof (const tuple<N,T>&) { return (alignof (T())); }

template <typename T, typename IntT>
inline ostringstream& chartype_text_write (ostringstream& os, const T& v)
{
    if (isprint(v))
	os << '\'' << v << '\'';
    else
	os << (IntT)(v);
    return (os);
}

template <>
inline ostringstream& container_element_text_write (ostringstream& os, const uint8_t& v)
{ return (chartype_text_write<uint8_t, unsigned int> (os, v)); }
template <>
inline ostringstream& container_element_text_write (ostringstream& os, const int8_t& v)
{ return (chartype_text_write<int8_t, int> (os, v)); }

//----{ matrix }--------------------------------------------------------

/// Writes tuple \p v into stream \p os.
template <size_t NX, size_t NY, typename T>
ostringstream& operator<< (ostringstream& os, const matrix<NX,NY,T>& v)
{
    os << '(';
    for (uoff_t row = 0; row < NY; ++ row) {
	os << '(';
        for (uoff_t column = 0; column < NX; ++ column) {
	    os << v[row][column];
	    if (column < NX - 1)
		os << ',';
	}
	os << ')';
    }
    os << ')';
    return (os);
}

//----------------------------------------------------------------------

#ifndef DOXYGEN_SHOULD_SKIP_THIS
#ifndef WITHOUT_LIBSTDCPP

/// \todo Need a better solution to getting the hash value.
inline hashvalue_t hash_value (const string::const_pointer& v)
{
    string::const_pointer first (v), last (v + strlen(v));
    hashvalue_t h = 0;
    // This has the bits flowing into each other from both sides of the number
    for (; first < last; ++ first)
	h = *first + ((h << 7) | (h >> BitsInType(hashvalue_t) - 7));
    return (h);
}

#endif
#endif

//----------------------------------------------------------------------

} // namespace ustl

// This is here because there really is no other place to put it.
#if SIZE_OF_BOOL != SIZE_OF_CHAR
// bool is a big type on some machines (like DEC Alpha), so it's written as a byte.
ALIGNOF(bool, sizeof(uint8_t))
#endif
STD_STREAMABLE(cmemlink)
STD_STREAMABLE(istream)
STD_STREAMABLE(ostream)
STD_STREAMABLE(string)
STD_STREAMABLE(exception)
STD_STREAMABLE(CBacktrace)
TEXT_STREAMABLE(cmemlink)
TEXT_STREAMABLE(istream)
TEXT_STREAMABLE(ostream)
TEXT_STREAMABLE(exception)
TEXT_STREAMABLE(CBacktrace)

#endif

