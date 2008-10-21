// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// ubitset.h
//
#ifndef UBITSET_H_7B6450EC1400CBA45DCE0127739F82EE
#define UBITSET_H_7B6450EC1400CBA45DCE0127739F82EE

#include "uassert.h"
#include "ustring.h"
#include "ufunction.h"

namespace ustl {

typedef uint32_t	bitset_value_type;

void convert_to_bitstring (const bitset_value_type* v, size_t n, string& buf);
void convert_from_bitstring (const string& buf, bitset_value_type* v, size_t n);

/// \class bitset ubitset.h ustl.h
/// \ingroup Sequences
///
/// \brief bitset is a fixed-size block of memory with addressable bits.
///
/// Normally used for state flags; allows setting and unsetting of individual
/// bits as well as bitwise operations on the entire set. The interface is
/// most like that of unsigned integers, and is intended to be used as such.
/// If you were using begin() and end() functions in STL's bitset, you would
/// not be able to do the same thing here, because those functions return
/// host type iterators, not bits.
///
template <size_t Size>
class bitset {
public:
    typedef bitset_value_type	value_type;
    typedef value_type*		pointer;
    typedef const value_type*	const_pointer;
    typedef pointer		iterator;
    typedef const_pointer	const_iterator;
    typedef size_t		difference_type;
    typedef size_t		size_type;
private:
    static const size_t s_WordBits	= BitsInType (value_type);
    static const size_t	s_nWords	= Size / s_WordBits + ((Size % s_WordBits) != 0);
    static const size_t	s_nBits		= s_nWords * s_WordBits;
private:
    inline value_type&		BitRef (uoff_t n)	{ assert (n < Size); return (m_Bits [n / s_WordBits]); }
    inline const value_type	BitRef (uoff_t n) const	{ assert (n < Size); return (m_Bits [n / s_WordBits]); }
    inline const value_type	Mask (uoff_t n) const	{ assert (n < Size); return (1 << (n % s_WordBits)); }
public:
    inline		bitset (value_type v = 0)	{ fill_n (m_Bits, s_nWords, 0); m_Bits[0] = v; }
    inline		bitset (const string& buf)	{ convert_from_bitstring (buf, m_Bits, s_nWords); }
    inline void		flip (uoff_t n)			{ BitRef(n) ^= Mask(n); }
    inline void		reset (void)			{ fill_n (m_Bits, s_nWords, 0); }
    inline void		clear (void)			{ fill_n (m_Bits, s_nWords, 0); }
    inline void		set (void)			{ fill_n (m_Bits, s_nWords, -1); }
    inline bitset	operator~ (void) const		{ bitset rv (*this); rv.flip(); return (rv); }
    inline size_type	size (void) const		{ return (Size); }
    inline size_type	capacity (void) const		{ return (s_nBits); }
    inline const bool	test (uoff_t n) const		{ return (BitRef(n) & Mask(n)); }
    inline const bool	operator[] (uoff_t n) const	{ return (test(n)); }
  inline const_iterator	begin (void) const		{ return (m_Bits); }
    inline iterator	begin (void)			{ return (m_Bits); }
  inline const_iterator	end (void) const		{ return (m_Bits + s_nWords); }
    inline iterator	end (void)			{ return (m_Bits + s_nWords); }
 			/// Returns the value_type with the equivalent bits. If size() > 1, you'll get only the first BitsInType(value_type) bits.
    inline const value_type	to_value (void) const		{ return (m_Bits[0]); }
    			/// Flips all the bits in the set.
    inline void		flip (void) { transform (begin(), end(), begin(), bitwise_not<value_type>()); }
			/// Sets or clears bit \p n.
    inline void		set (uoff_t n, bool val = true)
			{
			    value_type& br (BitRef (n));
			    const value_type mask (Mask (n));
			    const value_type bOn (br | mask), bOff (br & ~mask);
			    br = val ? bOn : bOff;
			}
			// Sets the value of the bitrange \p first through \p last to the equivalent number of bits from \p v.
    inline void		set (uoff_t first, uoff_t DebugArg(last), value_type v)
			{
#if !PLATFORM_ANDROID
			    assert (size_t (distance (first, last)) <= s_WordBits && "Bit ranges must be 32 bits or smaller");
			    assert (first / s_WordBits == last / s_WordBits && "Bit ranges can not cross dword (4 byte) boundary");
			    assert ((v & BitMask(value_type,distance(first,last))) == v && "The value is too large to fit in the given bit range");
#endif
			    BitRef(first) |= v << (first % s_WordBits);
			}
    			/// Clears the bit \p n.
    inline void		reset (uoff_t n)		{ set (n, false); }
			/// Returns a string with bits MSB "001101001..." LSB.
    inline string	to_string (void) const
			{
			    string rv (Size, '0');
			    convert_to_bitstring (m_Bits, s_nWords, rv);
			    return (rv);
			}
    inline value_type	at (uoff_t n) const		{ return (test(n)); }
			/// Returns the value in bits \p first through \p last.
    inline value_type	at (uoff_t first, uoff_t last) const
			{
			    assert (size_t (distance (first, last)) <= s_WordBits && "Bit ranges must be 32 bits or smaller");
			    assert (first / s_WordBits == last / s_WordBits && "Bit ranges can not cross dword (4 byte) boundary");
			    return ((BitRef(first) >> (first % s_WordBits)) & BitMask(value_type,distance(first, last)));
			}
    inline bool		any (void) const	{ value_type sum = 0; foreach (const_iterator, i, *this) sum |= *i; return (sum); }
    inline bool		none (void) const	{ return (!any()); }
    inline size_t	count (void) const	{ size_t sum = 0; foreach (const_iterator, i, *this) sum += popcount(*i); return (sum); }
    inline bool		operator== (const bitset<Size>& v) const
			    { return (s_nWords == 1 ? (m_Bits[0] == v.m_Bits[0]) : equal (begin(), end(), v.begin())); }
    inline const bitset	operator& (const bitset<Size>& v)
			    { bitset<Size> result; transform (begin(), end(), v.begin(), result.begin(), bitwise_and<value_type>()); return (result); }
    inline const bitset	operator| (const bitset<Size>& v)
			    { bitset<Size> result; transform (begin(), end(), v.begin(), result.begin(), bitwise_or<value_type>()); return (result); }
    inline const bitset	operator^ (const bitset<Size>& v)
			    { bitset<Size> result; transform (begin(), end(), v.begin(), result.begin(), bitwise_xor<value_type>()); return (result); }
   inline const bitset&	operator&= (const bitset<Size>& v)
			    { transform (begin(), end(), v.begin(), begin(), bitwise_and<value_type>()); return (*this); }
   inline const bitset&	operator|= (const bitset<Size>& v)
			    { transform (begin(), end(), v.begin(), begin(), bitwise_or<value_type>()); return (*this); }
   inline const bitset&	operator^= (const bitset<Size>& v)
			    { transform (begin(), end(), v.begin(), begin(), bitwise_xor<value_type>()); return (*this); }
private:
    value_type		m_Bits [s_nWords];
};

} // namespace ustl

#endif

