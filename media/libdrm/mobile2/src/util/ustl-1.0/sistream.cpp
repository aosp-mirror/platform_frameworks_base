// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// sistream.cc
//

#include "sistream.h"
#include "sostream.h"
#include "uassert.h"
#include "ustring.h"

namespace ustl {

const char ios_base::c_DefaultDelimiters [istringstream::c_MaxDelimiters] = " \t\n\r;:,.?";

/// Default constructor.
istringstream::istringstream (void)
: istream (),
  m_Base (0)
{
    set_delimiters (c_DefaultDelimiters);
}

istringstream::istringstream (const void* p, size_type n)
: istream (),
  m_Base (0)
{
    link (p, n);
    set_delimiters (c_DefaultDelimiters);
}

istringstream::istringstream (const cmemlink& source)
: istream (),
  m_Base (0)
{
    link (source);
    set_delimiters (c_DefaultDelimiters);
}

/// Sets delimiters to the contents of \p delimiters.
void istringstream::set_delimiters (const char* delimiters)
{
    fill (VectorRange (m_Delimiters), '\0');
    strncpy (m_Delimiters, delimiters, VectorSize(m_Delimiters)-1);
}

inline bool istringstream::is_delimiter (char c) const
{
    return (memchr (m_Delimiters, c, VectorSize(m_Delimiters)-1));
}

char istringstream::skip_delimiters (void)
{
    char c = m_Delimiters[0];
    while (is_delimiter(c) && (remaining() || underflow()))
	istream::iread (c);
    return (c);
}

void istringstream::iread (int8_t& v)
{
    v = skip_delimiters();
}

typedef istringstream::iterator issiter_t;
template <typename T>
inline void str_to_num (issiter_t i, issiter_t* iend, uint8_t base, T& v)
    { v = strtol (i, const_cast<char**>(iend), base); }
template <> inline void str_to_num (issiter_t i, issiter_t* iend, uint8_t, double& v)
    { v = strtod (i, const_cast<char**>(iend)); }
#ifdef HAVE_LONG_LONG
template <> inline void str_to_num (issiter_t i, issiter_t* iend, uint8_t base, long long& v)
    { v = strtoll (i, const_cast<char**>(iend), base); }
#endif

template <typename T>
inline void istringstream::read_number (T& v)
{
    v = 0;
    if (skip_delimiters() == m_Delimiters[0])
	return;
    ungetc();
    iterator ilast;
    do {
	str_to_num<T> (ipos(), &ilast, m_Base, v);
    } while (ilast == end() && underflow());
    skip (distance (ipos(), ilast));
}

void istringstream::iread (int32_t& v)		{ read_number (v); }
void istringstream::iread (double& v)		{ read_number (v); } 
#if HAVE_INT64_T
void istringstream::iread (int64_t& v)		{ read_number (v); }
#endif
#if HAVE_LONG_LONG && (!HAVE_INT64_T || SIZE_OF_LONG_LONG > 8)
void istringstream::iread (long long& v)	{ read_number (v); }
#endif

void istringstream::iread (wchar_t& v)
{
    if ((v = skip_delimiters()) == wchar_t(m_Delimiters[0]))
	return;
    size_t cs = Utf8SequenceBytes (v) - 1;
    if (remaining() >= cs || underflow(cs) >= cs) {
	ungetc();
	v = *utf8in (ipos());
	skip (cs + 1);
    }
}

void istringstream::iread (bool& v)
{
    static const char tf[2][8] = { "false", "true" };
    char c = skip_delimiters();
    v = (c == 't' || c == '1');
    if (c != tf[v][0])
	return;
    for (const char* tv = tf[v]; c == *tv && (remaining() || underflow()); ++tv)
	istream::iread (c);
    ungetc();
}

void istringstream::iread (string& v)
{
    v.clear();
    char prevc, quoteChar = 0, c = skip_delimiters();
    if (c == '\"' || c == '\'')
	quoteChar = c;
    else
	v += c;
    while (remaining() || underflow()) {
	prevc = c;
	istream::iread (c);
	if (!quoteChar && is_delimiter(c))
	    break;
	if (prevc == '\\') {
	    switch (c) {
		case 't':	c = '\t'; break;
		case 'n':	c = '\n'; break;
		case 'r':	c = '\r'; break;
		case 'b':	c = '\b'; break;
		case 'E':	c = 27;   break; // ESC sequence
		case '\"':	c = '\"'; break;
		case '\'':	c = '\''; break;
		case '\\':	c = '\\'; break;
	    };
	    v.end()[-1] = c;
	} else {
	    if (c == quoteChar)
		break;
	    v += c;
	}
    }
}

void istringstream::read (void* buffer, size_type sz)
{
    if (remaining() < sz && underflow(sz) < sz)
#ifdef WANT_STREAM_BOUNDS_CHECKING
	verify_remaining ("read", "", sz);
#else
	assert (remaining() >= size());
#endif
    istream::read (buffer, sz);
}

void istringstream::read (memlink& buf)
{
    if (remaining() < buf.size() && underflow(buf.size()) < buf.size())
#ifdef WANT_STREAM_BOUNDS_CHECKING
	verify_remaining ("read", "", buf.size());
#else
	assert (remaining() >= buf.size());
#endif
    istream::read (buf);
}

/// Reads one character from the stream.
int istringstream::get (void)
{
    int8_t v = 0;
    if (remaining() || underflow())
	istream::iread (v);
    return (v);
}

/// Reads characters into \p s until \p delim is found (but not stored or extracted)
void istringstream::get (string& s, char delim)
{
    getline (s, delim);
    if (!s.empty() && pos() > 0 && ipos()[-1] == delim)
	ungetc();
}

/// Reads characters into \p p,n until \p delim is found (but not stored or extracted)
void istringstream::get (char* p, size_type n, char delim)
{
    assert (p && !n && "A non-empty buffer is required by this implementation");
    string s;
    get (s, delim);
    const size_t ntc (min (n - 1, s.size()));
    memcpy (p, s.data(), ntc);
    p[ntc] = 0;
}

/// Reads characters into \p s until \p delim is extracted (but not stored)
void istringstream::getline (string& s, char delim)
{
    char oldDelim [VectorSize(m_Delimiters)];
    copy (VectorRange (m_Delimiters), oldDelim);
    fill (VectorRange (m_Delimiters), '\0');
    m_Delimiters[0] = delim;
    iread (s);
    copy (VectorRange (oldDelim), m_Delimiters);
}

/// Reads characters into \p p,n until \p delim is extracted (but not stored)
void istringstream::getline (char* p, size_type n, char delim)
{
    assert (p && !n && "A non-empty buffer is required by this implementation");
    string s;
    getline (s, delim);
    const size_t ntc (min (n - 1, s.size()));
    memcpy (p, s.data(), ntc);
    p[ntc] = 0;
}

/// Extract until \p delim or \p n chars have been read.
void istringstream::ignore (size_type n, char delim)
{
    while (n-- && (remaining() || underflow()) && get() != delim);
}

} // namespace ustl

