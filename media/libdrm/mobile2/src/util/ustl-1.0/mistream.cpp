// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// mstream.cpp
//
//	Helper classes to read and write packed binary streams.
//

#include "mistream.h"
#include "sostream.h"
#include "ualgo.h"
#include "uassert.h"
#include "ustring.h"

#if PLATFORM_ANDROID
#include <stdio.h>
#endif

namespace ustl {

//--------------------------------------------------------------------

/// \brief Constructs a stream attached to nothing.
/// A stream attached to nothing is not usable. Call Link() functions
/// inherited from cmemlink to attach to some memory block.
///
istream::istream (void)
: cmemlink (),
  m_Pos (0)
{
}

/// Attaches the stream to a block at \p p of size \p n.
istream::istream (const void* p, size_type n)
: cmemlink (p, n),
  m_Pos (0)
{
}

/// Attaches to the block pointed to by \p source.
istream::istream (const cmemlink& source)
: cmemlink (source),
  m_Pos (0)
{
}

/// Attaches to the block pointed to by source of size source.pos()
istream::istream (const ostream& source)
: cmemlink (source.begin(), source.pos()),
  m_Pos (0)
{
}

/// Swaps contents with \p is
void istream::swap (istream& is)
{
    cmemlink::swap (is);
    ::ustl::swap (m_Pos, is.m_Pos);
}

/// Checks that \p n bytes are available in the stream, or else throws.
void istream::verify_remaining (const char* op, const char* type, size_t n) const
{
    if (remaining() < n)
#if PLATFORM_ANDROID
        printf("stream bounds exception\n");
#else
	throw stream_bounds_exception (op, type, pos(), n, remaining());
#endif
}

/// Reads \p n bytes into \p buffer.
void istream::read (void* buffer, size_type n)
{
#ifdef WANT_STREAM_BOUNDS_CHECKING
    verify_remaining ("read", "binary data", n);
#else
    assert (remaining() >= n && "Reading past end of buffer. Make sure you are reading the right format.");
#endif
    copy_n (ipos(), n, reinterpret_cast<value_type*>(buffer));
    m_Pos += n;
}

/// Reads a null-terminated string into \p str.
void istream::read_strz (string& str)
{
    const_iterator zp = find (ipos(), end(), string::c_Terminator);
    if (zp == end())
	zp = ipos();
    const size_type strl = distance (ipos(), zp);
    str.resize (strl);
    copy (ipos(), zp, str.begin());
    m_Pos += strl + 1;
}

/// Reads at most \p n bytes into \p s.
istream::size_type istream::readsome (void* s, size_type n)
{
    if (remaining() < n)
	underflow (n);
    const size_type ntr (min (n, remaining()));
    read (s, ntr);
    return (ntr);
}

/// Writes all unread bytes into \p os.
void istream::write (ostream& os) const
{
    os.write (ipos(), remaining());
}

/// Writes the object to stream \p os.
void istream::text_write (ostringstream& os) const
{
    os.write (ipos(), remaining());
}

/// Links to \p p of size \p n
void istream::unlink (void)
{
    cmemlink::unlink();
    m_Pos = 0;
}

//--------------------------------------------------------------------

/// \brief Constructs a stream attached to nothing.
/// A stream attached to nothing is not usable. Call Link() functions
/// inherited from memlink to attach to some memory block.
///
ostream::ostream (void)
: memlink (),
  m_Pos (0)
{
}

/// Attaches the stream to a block at \p p of size \p n.
ostream::ostream (void* p, size_type n)
: memlink (p, n),
  m_Pos (0)
{
}

/// Attaches to the block pointed to by \p source.
ostream::ostream (const memlink& source)
: memlink (source),
  m_Pos (0)
{
}

/// Links to \p p of size \p n
void ostream::unlink (void)
{
    memlink::unlink();
    m_Pos = 0;
}

/// Checks that \p n bytes are available in the stream, or else throws.
void ostream::verify_remaining (const char* op, const char* type, size_t n) const
{
    if (remaining() < n)
#if PLATFORM_ANDROID
        printf("stream bounds exception\n");
#else
	throw stream_bounds_exception (op, type, pos(), n, remaining());
#endif
}

/// Aligns the write pointer on \p grain. The skipped bytes are zeroed.
void ostream::align (size_type grain)
{
    const size_t r = pos() % grain;
    size_t nb = grain - r;
    if (!r) nb = 0;
#ifdef WANT_STREAM_BOUNDS_CHECKING
    verify_remaining ("align", "padding", nb);
#else
    assert (remaining() >= nb && "Buffer overrun. Check your stream size calculations.");
#endif
    fill_n (ipos(), nb, '\x0');
    m_Pos += nb;
}

/// Writes \p n bytes from \p buffer.
void ostream::write (const void* buffer, size_type n)
{
#ifdef WANT_STREAM_BOUNDS_CHECKING
    verify_remaining ("write", "binary data", n);
#else
    assert (remaining() >= n && "Buffer overrun. Check your stream size calculations.");
#endif
    copy_n (const_iterator(buffer), n, ipos());
    m_Pos += n;
}

/// Writes \p str as a null-terminated string.
void ostream::write_strz (const char* str)
{
    write (str, strlen(str));
    iwrite (string::c_Terminator);
}

/// Writes all available data from \p is.
void ostream::read (istream& is)
{
    is.write (*this);
    is.seek (is.size());
}

/// Writes all written data to \p os.
void ostream::text_write (ostringstream& os) const
{
    os.write (begin(), pos());
}

/// Inserts an empty area of \p size, at \p start.
void ostream::insert (iterator start, size_type s)
{
    memlink::insert (start, s);
    m_Pos += s;
}

/// Erases an area of \p size, at \p start.
void ostream::erase (iterator start, size_type s)
{
    m_Pos -= s;
    memlink::erase (start, s);
}

/// Swaps with \p os
void ostream::swap (ostream& os)
{
    memlink::swap (os);
    ::ustl::swap (m_Pos, os.m_Pos);
}

//--------------------------------------------------------------------

} // namespace ustl

