// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// ustring.cpp
//
//	STL basic_string equivalent functionality.
//

#include "uassert.h"
#include "ustring.h"
#include "mistream.h"
#include "mostream.h"
#include "ualgo.h"
#include <stdio.h>	// for vsnprintf (in string::format)

#include "uassert.h"

namespace ustl {

//----------------------------------------------------------------------

const uoff_t string::npos;
const string::size_type string::size_Terminator;
const string::value_type string::c_Terminator;
const char string::empty_string[string::size_Terminator] = "";

typedef utf8in_iterator<string::const_iterator> utf8icstring_iterator;
typedef utf8in_iterator<string::iterator> utf8istring_iterator;
typedef utf8out_iterator<string::iterator> utf8ostring_iterator;

//----------------------------------------------------------------------

/// Creates an empty string.
string::string (void)
: memblock ()
{
    link (empty_string, 0U);
}

/// Assigns itself the value of string \p s
string::string (const string& s)
: memblock()
{
    if (s.is_linked())
	link (s.c_str(), s.size());
    else
	assign (s);
}

/// Links to \p s
string::string (const_pointer s)
: memblock ()
{
    if (!s)
	s = empty_string;
    link (s, strlen(s));
}

/// Creates a string of length \p n filled with character \p c.
string::string (size_type n, value_type c)
: memblock ()
{
    resize (n);
    fill_n (begin(), n, c);
}

/// Resize the string to \p n characters. New space contents is undefined.
void string::resize (size_type n)
{
    memblock::resize (n);
    at(n) = c_Terminator;
}

/// Assigns itself the value of string \p s
void string::assign (const_pointer s)
{
    if (!s)
	s = empty_string;
    assign (s, strlen (s));
}

/// Assigns itself the value of string \p s of length \p len.
void string::assign (const_pointer s, size_type len)
{
    while (len && s[len - 1] == c_Terminator)
	-- len;
    resize (len);
    copy (s, len);
}

/// Appends to itself the value of string \p s of length \p len.
void string::append (const_pointer s)
{
    if (!s)
	s = empty_string;
    append (s, strlen (s));
}

/// Appends to itself the value of string \p s of length \p len.
void string::append (const_pointer s, size_type len)
{
    while (len && s[len - 1] == c_Terminator)
	-- len;
    resize (size() + len);
    copy_n (s, len, end() - len);
}

/// Appends to itself \p n characters of value \p c.
void string::append (size_type n, value_type c)
{
    resize (size() + n);
    fill_n (end() - n, n, c);
}

/// Copies into itself at offset \p start, the value of string \p p of length \p n.
string::size_type string::copyto (pointer p, size_type n, const_iterator start) const
{
    assert (p && n);
    if (!start)
	start = begin();
    const size_type btc = min(n - size_Terminator, size());
    copy_n (start, btc, p);
    p[btc] = c_Terminator;
    return (btc + size_Terminator);
}

/// Returns comparison value regarding string \p s.
/// The return value is:
/// \li 1 if this string is greater (by value, not length) than string \p s
/// \li 0 if this string is equal to string \p s
/// \li -1 if this string is less than string \p s
///
/*static*/ int string::compare (const_iterator first1, const_iterator last1, const_iterator first2, const_iterator last2)
{
    assert (first1 <= last1 && (first2 <= last2 || !last2) && "Negative ranges result in memory allocation errors.");
    const size_type len1 = distance (first1, last1), len2 = distance (first2, last2);
    const int rvbylen = sign (int(len1 - len2));
    int rv = memcmp (first1, first2, min (len1, len2));
    return (rv ? rv : rvbylen);
}

/// Returns true if this string is equal to string \p s.
bool string::operator== (const_pointer s) const
{
    if (!s)
	s = empty_string;
    return (size() == strlen(s) && 0 == memcmp (c_str(), s, size()));
}

/// Returns the beginning of character \p i.
string::iterator string::utf8_iat (uoff_t i)
{
    utf8istring_iterator cfinder (begin());
    cfinder += i;
    return (cfinder.base());
}

/// Inserts wide character \p c at \p ip \p n times as a UTF-8 string.
///
/// \p ip is a character position, not a byte position, and must fall in
/// the 0 through length() range.
/// The first argument is not an iterator because it is rather difficult
/// to get one. You'd have to use ((utf8begin() + n).base()) as the first
/// argument, which is rather ugly. Besides, then this insert would be
/// ambiguous with the regular character insert.
///
void string::insert (const uoff_t ip, wchar_t c, size_type n)
{
    iterator ipp (utf8_iat (ip));
    ipp = iterator (memblock::insert (memblock::iterator(ipp), n * Utf8Bytes(c)));
    fill_n (utf8out (ipp), n, c);
    *end() = c_Terminator;
}

/// Inserts sequence of wide characters at \p ip.
void string::insert (const uoff_t ip, const wchar_t* first, const wchar_t* last, const size_type n)
{
    iterator ipp (utf8_iat (ip));
    size_type nti = distance (first, last), bti = 0;
    for (uoff_t i = 0; i < nti; ++ i)
	bti += Utf8Bytes(first[i]);
    ipp = iterator (memblock::insert (memblock::iterator(ipp), n * bti));
    utf8ostring_iterator uout (utf8out (ipp));
    for (uoff_t j = 0; j < n; ++ j)
	for (uoff_t k = 0; k < nti; ++ k, ++ uout)
	    *uout = first[k];
    *end() = c_Terminator;
}

/// Inserts character \p c into this string at \p start.
string::iterator string::insert (iterator start, const_reference c, size_type n)
{
    start = iterator (memblock::insert (memblock::iterator(start), n));
    fill_n (start, n, c);
    *end() = c_Terminator;
    return (start);
}

/// Inserts \p count instances of string \p s at offset \p start.
string::iterator string::insert (iterator start, const_pointer s, size_type n)
{
    if (!s)
	s = empty_string;
    return (insert (start, s, s + strlen(s), n));
}

/// Inserts [first,last] \p n times.
string::iterator string::insert (iterator start, const_pointer first, const_pointer last, size_type n)
{
    assert (first <= last);
    assert (begin() <= start && end() >= start);
    assert ((first < begin() || first >= end() || size() + abs_distance(first,last) < capacity()) && "Insertion of self with autoresize is not supported");
    start = iterator (memblock::insert (memblock::iterator(start), distance(first, last) * n));
    fill (memblock::iterator(start), first, distance(first, last), n);
    *end() = c_Terminator;
    return (start);
}

/// Erases \p size bytes at \p start.
string::iterator string::erase (iterator ep, size_type n)
{
    string::iterator rv = memblock::erase (memblock::iterator(ep), n);
    *end() = c_Terminator;
    return (rv);
}

/// Erases \p size characters at \p start.
/// \p start is a character position, not a byte position, and must be
/// in the 0..length() range.
///
void string::erase (uoff_t ep, size_type n)
{
    iterator first (utf8_iat(ep));
    size_t nbytes (utf8_iat(ep + n) - first);
    memblock::erase (first, nbytes);
    *end() = c_Terminator;
}

/// Replaces range [\p start, \p start + \p len] with string \p s.
void string::replace (iterator first, iterator last, const_pointer s)
{
    if (!s)
	s = empty_string;
    replace (first, last, s, s + strlen(s));
}

/// Replaces range [\p start, \p start + \p len] with \p count instances of string \p s.
void string::replace (iterator first, iterator last, const_pointer i1, const_pointer i2, size_type n)
{
    assert (first <= last);
    assert (n || distance(first, last));
    assert (first >= begin() && first <= end() && last >= first && last <= end());
    assert ((i1 < begin() || i1 >= end() || abs_distance(i1,i2) * n + size() < capacity()) && "Replacement by self can not autoresize");
    const size_type bte = distance(first, last), bti = distance(i1, i2) * n;
    if (bti < bte)
	first = iterator (memblock::erase (memblock::iterator(first), bte - bti));
    else if (bte < bti)
	first = iterator (memblock::insert (memblock::iterator(first), bti - bte));
    fill (memblock::iterator(first), i1, distance(i1, i2), n);
    *end() = c_Terminator;
}

/// Returns the offset of the first occurence of \p c after \p pos.
uoff_t string::find (const_reference c, uoff_t pos) const
{
    const_iterator found = ::ustl::find (iat(pos), end(), c);
    return (found < end() ? distance(begin(),found) : npos);
}

/// Returns the offset of the first occurence of substring \p s of length \p n after \p pos.
uoff_t string::find (const string& s, uoff_t pos) const
{
    if (s.empty() || s.size() > size() - pos)
	return (npos);
    const uoff_t endi = s.size() - 1;
    const_reference endchar = s[endi];
    uoff_t lastPos = endi;
    while (lastPos-- && s[lastPos] != endchar);
    const size_type skip = endi - lastPos;
    const_iterator i = iat(pos) + endi;
    for (; i < end() && (i = ::ustl::find (i, end(), endchar)) < end(); i += skip)
	if (memcmp (i - endi, s.c_str(), s.size()) == 0)
	    return (distance (begin(), i) - endi);
    return (npos);
}

/// Returns the offset of the last occurence of character \p c before \p pos.
uoff_t string::rfind (const_reference c, uoff_t pos) const
{
    for (int i = min(pos,size()-1); i >= 0; --i)
	if (at(i) == c)
	    return (i);
    return (npos);
}

/// Returns the offset of the last occurence of substring \p s of size \p n before \p pos.
uoff_t string::rfind (const string& s, uoff_t pos) const
{
    const_iterator d = iat(pos) - 1;
    const_iterator sp = begin() + s.size() - 1;
    const_iterator m = s.end() - 1;
    for (uoff_t i = 0; d > sp && i < s.size(); -- d)
	for (i = 0; i < s.size(); ++ i)
	    if (m[-i] != d[-i])
		break;
    return (d > sp ? distance (begin(), d + 2 - s.size()) : npos);
}

/// Returns the offset of the first occurence of one of characters in \p s of size \p n after \p pos.
uoff_t string::find_first_of (const string& s, uoff_t pos) const
{
    for (uoff_t i = min(pos,size()); i < size(); ++ i)
	if (s.find (at(i)) != npos)
	    return (i);
    return (npos);
}

/// Returns the offset of the first occurence of one of characters not in \p s of size \p n after \p pos.
uoff_t string::find_first_not_of (const string& s, uoff_t pos) const
{
    for (uoff_t i = min(pos,size()); i < size(); ++ i)
	if (s.find (at(i)) == npos)
	    return (i);
    return (npos);
}

/// Returns the offset of the last occurence of one of characters in \p s of size \p n before \p pos.
uoff_t string::find_last_of (const string& s, uoff_t pos) const
{
    for (int i = min(pos,size()-1); i >= 0; -- i)
	if (s.find (at(i)) != npos)
	    return (i);
    return (npos);
}

/// Returns the offset of the last occurence of one of characters not in \p s of size \p n before \p pos.
uoff_t string::find_last_not_of (const string& s, uoff_t pos) const
{
    for (int i = min(pos,size()-1); i >= 0; -- i)
	if (s.find (at(i)) == npos)
	    return (i);
    return (npos);
}

/// Equivalent to a vsprintf on the string.
int string::vformat (const char* fmt, va_list args)
{
#if HAVE_VA_COPY
    va_list args2;
#else
    #define args2 args
    #undef __va_copy
    #define __va_copy(x,y)
#endif
    size_t rv = size();
    do {
	reserve (rv);
	__va_copy (args2, args);
	rv = vsnprintf (data(), memblock::capacity(), fmt, args2);
	rv = min (rv, memblock::capacity());
    } while (rv > capacity());
    resize (min (rv, capacity()));
    return (rv);
}

/// Equivalent to a sprintf on the string.
int string::format (const char* fmt, ...)
{
    va_list args;
    va_start (args, fmt);
    const int rv = vformat (fmt, args);
    va_end (args);
    return (rv);
}

/// Returns the number of bytes required to write this object to a stream.
size_t string::stream_size (void) const
{
    return (Utf8Bytes(size()) + size());
}

/// Reads the object from stream \p os
void string::read (istream& is)
{
    char szbuf [8];
    is >> szbuf[0];
    size_t szsz (Utf8SequenceBytes (szbuf[0]) - 1), n = 0;
    is.verify_remaining ("read", "ustl::string", szsz);
    is.read (szbuf + 1, szsz);
    n = *utf8in(szbuf);
    is.verify_remaining ("read", "ustl::string", n);
    resize (n);
    is.read (data(), size());
}

/// Writes the object to stream \p os
void string::write (ostream& os) const
{
    const written_size_type sz (size());
    assert (sz == size() && "No support for writing strings larger than 4G");

    char szbuf [8];
    utf8out_iterator<char*> szout (szbuf);
    *szout = sz;
    size_t szsz = distance (szbuf, szout.base());

    os.verify_remaining ("write", "ustl::string", szsz + sz);
    os.write (szbuf, szsz);
    os.write (cdata(), sz);
}

/// Returns a hash value for [first, last)
/*static*/ hashvalue_t string::hash (const char* first, const char* last)
{
    hashvalue_t h = 0;
    // This has the bits flowing into each other from both sides of the number
    for (; first < last; ++ first)
	h = *first + ((h << 7) | (h >> BitsInType(hashvalue_t) - 7));
    return (h);
}

} // namespace ustl

