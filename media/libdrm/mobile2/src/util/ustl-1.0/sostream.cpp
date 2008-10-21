// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// sostream.h
//

#include "mistream.h"	// for istream_iterator, referenced in utf8.h
#include "sostream.h"
#include "uassert.h"
#include "ulimits.h"
#include "ustring.h"
#include <stdio.h>

namespace ustl {

/// Creates an output string stream linked to the given memory area.
ostringstream::ostringstream (void* p, size_t n)
: ostream (),
  m_Buffer (),
  m_Flags (0),
  m_Width (0),
  m_Base (10),
  m_Precision (2)
{
    link (p, n);
}

/// Creates an output string stream, initializing the buffer with v.
ostringstream::ostringstream (const string& v)
: ostream (),
  m_Buffer (v),
  m_Flags (0),
  m_Width (0),
  m_Base (10),
  m_Precision (2)
{
    ostream::link (m_Buffer);
}

/// Copies \p s to the internal buffer.
void ostringstream::str (const string& s)
{
    m_Buffer = s;
    ostream::link (m_Buffer);
    SetPos (m_Buffer.size());
}

/// Writes a single character into the stream.
void ostringstream::iwrite (uint8_t v)
{
    if (remaining() >= 1 || overflow() >= 1)
	ostream::iwrite (v);
}

/// Writes \p buf of size \p bufSize through the internal buffer.
void ostringstream::write_buffer (const char* buf, size_type bufSize)
{
    size_type btw = 0, written = 0;
    while ((written += btw) < bufSize && (remaining() || overflow(bufSize - written)))
	write (buf + written, btw = min (remaining(), bufSize - written));
}

/// Simple decimal encoding of \p n into \p fmt.
inline char* ostringstream::encode_dec (char* fmt, uint32_t n) const
{
    do {
	*fmt++ = '0' + n % 10;
    } while (n /= 10);
    return (fmt);
}

/// Generates a sprintf format string for the given type.
void ostringstream::fmtstring (char* fmt, const char* typestr, bool bInteger) const
{
    *fmt++ = '%';
    if (m_Width)
	fmt = encode_dec (fmt, m_Width);
    if (m_Flags & left)
	*fmt++ = '-';
    if (!bInteger) {
	*fmt++ = '.';
	fmt = encode_dec (fmt, m_Precision);
    }
    while (*typestr)
	*fmt++ = *typestr++;
    if (bInteger) {
	if (m_Base == 16)
	    fmt[-1] = 'X';
	else if (m_Base == 8)
	    fmt[-1] = 'o';
    } else {
	if (m_Flags & scientific)
	    fmt[-1] = 'E';
    }
    *fmt = 0;
}

/// Writes \p v into the stream as utf8
void ostringstream::iwrite (wchar_t v)
{
    char buffer [8];
    *utf8out(buffer) = v;
    write_buffer (buffer, Utf8Bytes(v));
}

/// Writes value \p v into the stream as text.
void ostringstream::iwrite (bool v)
{
    static const char tf[2][8] = { "false", "true" };
    write_buffer (tf[v], 5 - v);
}

/// Equivalent to a vsprintf on the string.
int ostringstream::vformat (const char* fmt, va_list args)
{
#if HAVE_VA_COPY
    va_list args2;
#else
    #define args2 args
    #undef __va_copy
    #define __va_copy(x,y)
#endif
    size_t rv, space;
    do {
	space = remaining();
	__va_copy (args2, args);
	rv = vsnprintf (ipos(), space, fmt, args2);
	if (ssize_t(rv) < 0)
	    rv = space;
    } while (rv >= space && rv < overflow(rv + 1));
    SetPos (pos() + min (rv, space));
    return (rv);
}

/// Equivalent to a sprintf on the string.
int ostringstream::format (const char* fmt, ...)
{
    va_list args;
    va_start (args, fmt);
    const int rv = vformat (fmt, args);
    va_end (args);
    return (rv);
}

/// Links to string \p l as resizable.
void ostringstream::link (void* p, size_t n)
{
    assert ((p || !n) && "The output string buffer must not be read-only");
    ostream::link (p, n);
    m_Buffer.link (p, n);
}

/// Writes the contents of \p buffer of \p size into the stream.
void ostringstream::write (const void* buffer, size_type sz)
{
    if (remaining() < sz && overflow(sz) < sz)
	return;
    ostream::write (buffer, sz);
}

/// Writes the contents of \p buf into the stream.
void ostringstream::write (const cmemlink& buf)
{
    if (remaining() < buf.size() && overflow(buf.size()) < buf.size())
	return;
    ostream::write (buf);
}

/// Flushes the internal buffer by truncating it at the current position.
void ostringstream::flush (void)
{
    m_Buffer.resize (pos());
}

/// Attempts to create more output space. Returns remaining().
ostringstream::size_type ostringstream::overflow (size_type n)
{
    if (n > remaining()) {
	const uoff_t oldPos (pos());
	m_Buffer.reserve (oldPos + n, false);
	m_Buffer.resize (oldPos + n);
	ostream::link (m_Buffer);
	SetPos (oldPos);
    }
    verify_remaining ("write", "text", n);
    return (remaining());
}

} // namespace ustl


