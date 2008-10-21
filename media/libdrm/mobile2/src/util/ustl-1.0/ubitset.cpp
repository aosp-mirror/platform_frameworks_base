// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// ubitset.cc
//

#include "ubitset.h"

namespace ustl {

/// Copies bits from \p v of size \p n into \p buf as MSB "1011001..." LSB
/// If \p buf is too small, MSB bits will be truncated.
void convert_to_bitstring (const bitset_value_type* v, size_t n, string& buf)
{
    string::iterator stri = buf.end();
    for (size_t i = 0; i < n && stri > buf.begin(); ++ i)
	for (bitset_value_type b = 1; b && stri > buf.begin(); b <<= 1)
	    *--stri = (v[i] & b) ? '1' : '0';
}

/// Copies bits from \p buf as MSB "1011001..." LSB into \p v of size \p n.
void convert_from_bitstring (const string& buf, bitset_value_type* v, size_t n)
{
    string::const_iterator stri = buf.end();
    for (size_t i = 0; i < n; ++ i) {
	for (bitset_value_type b = 1; b; b <<= 1) {
	    if (stri == buf.begin() || *--stri == '0')
		v[i] &= ~b;
	    else
		v[i] |= b;
	}
    }
}

} // namespace ustl

