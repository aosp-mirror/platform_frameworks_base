// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// uexception.cc
//

#include "uassert.h"
#include "uexception.h"
#include "ustring.h"
#include "mistream.h"
#include "sostream.h"
#include "strmsize.h"
#include "uspecial.h"
#include <errno.h>
#if __GNUC__ >= 3 && !PLATFORM_ANDROID
    #include <cxxabi.h>
#endif

namespace ustl {

//----------------------------------------------------------------------

/// \brief Returns a descriptive error message. fmt="%s"
/// Overloads of this functions must set NULL as the default fmt
/// argument and handle that case to provide a default format string
/// in case the user does not have a localized one. The format
/// string should be shown in the documentation to not require
/// translators to look through code. Also, this function must
/// not throw anything, so you must wrap memory allocation routines
/// (like string::format, for instance) in a try{}catch(...){} block.
///
void exception::info (string& msgbuf, const char*) const throw()
{
#if PLATFORM_ANDROID
    msgbuf.format ("%s", what());
#else /* !PLATFORM_ANDROID */
    try { msgbuf.format ("%s", what()); } catch (...) { /* Ignore all exceptions */ }
#endif
}

/// Reads the exception from stream \p is.
void exception::read (istream& is)
{
    uint32_t stmSize;
    xfmt_t fmt;
    is >> fmt >> stmSize >> m_Backtrace;
    assert (fmt == m_Format && "The saved exception is of a different type.");
    assert (stmSize - exception::stream_size() <= is.remaining() && "The saved exception data is corrupt.");
    m_Format = fmt;
}

/// Writes the exception into stream \p os as an IFF chunk.
void exception::write (ostream& os) const
{
    os << m_Format << uint32_t(stream_size()) << m_Backtrace;
}

/// Writes the exception as text into stream \p os.
void exception::text_write (ostringstream& os) const
{
#if !PLATFORM_ANDROID
    try {
#endif
	string buf;
	info (buf);
	os << buf;
#if !PLATFORM_ANDROID
    } catch (...) {}
#endif
}

//----------------------------------------------------------------------

/// Initializes the empty object. \p nBytes is the size of the attempted allocation.
bad_alloc::bad_alloc (size_t nBytes) throw()
: ustl::exception(),
  m_nBytesRequested (nBytes)
{
    set_format (xfmt_BadAlloc);
}

/// Returns a descriptive error message. fmt="failed to allocate %d bytes"
void bad_alloc::info (string& msgbuf, const char* fmt) const throw()
{
    if (!fmt) fmt = "failed to allocate %d bytes";
#if PLATFORM_ANDROID
    msgbuf.format (fmt, m_nBytesRequested);
#else /* !PLATFORM_ANDROID */
    try { msgbuf.format (fmt, m_nBytesRequested); } catch (...) {}
#endif
}

/// Reads the exception from stream \p is.
void bad_alloc::read (istream& is)
{
    ustl::exception::read (is);
    is >> m_nBytesRequested;
}

/// Writes the exception into stream \p os.
void bad_alloc::write (ostream& os) const
{
    ustl::exception::write (os);
    os << m_nBytesRequested;
}

/// Returns the size of the written exception.
size_t bad_alloc::stream_size (void) const
{
    return (ustl::exception::stream_size() + stream_size_of(m_nBytesRequested));
}

//----------------------------------------------------------------------

/// Initializes the empty object. \p operation is the function that returned the error code.
libc_exception::libc_exception (const char* operation) throw()
: exception(),
  m_Errno (errno),
  m_Operation (operation)
{
    set_format (xfmt_LibcException);
}

/// Copies object \p v.
libc_exception::libc_exception (const libc_exception& v) throw()
: exception (v),
  m_Errno (v.m_Errno),
  m_Operation (v.m_Operation)
{
}

/// Copies object \p v.
const libc_exception& libc_exception::operator= (const libc_exception& v)
{
    m_Errno = v.m_Errno;
    m_Operation = v.m_Operation;
    return (*this);
}

/// Returns a descriptive error message. fmt="%s: %m"
void libc_exception::info (string& msgbuf, const char* fmt) const throw()
{
    if (!fmt) fmt = "%s: %m";
#if PLATFORM_ANDROID
    msgbuf.format (fmt, m_Operation, m_Errno, m_Errno);
#else /* !PLATFORM_ANDROID */
    try { msgbuf.format (fmt, m_Operation, m_Errno, m_Errno); } catch (...) {}
#endif
}

/// Reads the exception from stream \p is.
void libc_exception::read (istream& is)
{
    exception::read (is);
    is >> m_Errno >> m_Operation;
}

/// Writes the exception into stream \p os.
void libc_exception::write (ostream& os) const
{
    exception::write (os);
    os << m_Errno << m_Operation;
}

/// Returns the size of the written exception.
size_t libc_exception::stream_size (void) const
{
    return (exception::stream_size() +
	    stream_size_of(m_Errno) +
	    stream_size_of(m_Operation));
}

//----------------------------------------------------------------------

/// Initializes the empty object. \p operation is the function that returned the error code.
file_exception::file_exception (const char* operation, const char* filename) throw()
: libc_exception (operation)
{
    memset (m_Filename, 0, VectorSize(m_Filename));
    set_format (xfmt_FileException);
    if (filename) {
	strncpy (m_Filename, filename, VectorSize(m_Filename));
	m_Filename [VectorSize(m_Filename) - 1] = 0;
    }
}

/// Returns a descriptive error message. fmt="%s %s: %m"
void file_exception::info (string& msgbuf, const char* fmt) const throw()
{
    if (!fmt) fmt = "%s %s: %m";
#if PLATFORM_ANDROID
    msgbuf.format (fmt, m_Operation, m_Filename, m_Errno, m_Errno);
#else /* !PLATFORM_ANDROID */
    try { msgbuf.format (fmt, m_Operation, m_Filename, m_Errno, m_Errno); } catch (...) {}
#endif
}

/// Reads the exception from stream \p is.
void file_exception::read (istream& is)
{
    libc_exception::read (is);
    string filename;
    is >> filename;
    is.align (8);
    filename.copyto (filename, VectorSize(m_Filename));
}

/// Writes the exception into stream \p os.
void file_exception::write (ostream& os) const
{
    libc_exception::write (os);
    os << string (m_Filename);
    os.align (8);
}

/// Returns the size of the written exception.
size_t file_exception::stream_size (void) const
{
    return (libc_exception::stream_size() +
	    Align (stream_size_of (string (m_Filename)), 8));
}

//----------------------------------------------------------------------

/// \brief Uses C++ ABI call, if available to demangle the contents of \p buf.
///
/// The result is written to \p buf, with the maximum size of \p bufSize, and
/// is zero-terminated. The return value is \p buf.
///
const char* demangle_type_name (char* buf, size_t bufSize, size_t* pdmSize)
{
    size_t bl = strlen (buf);
#if __GNUC__ >= 3 && !PLATFORM_ANDROID
    char dmname [256];
    size_t sz = VectorSize(dmname);
    int bFailed;
    abi::__cxa_demangle (buf, dmname, &sz, &bFailed);
    if (!bFailed) {
	bl = min (strlen (dmname), bufSize - 1);
	memcpy (buf, dmname, bl);
	buf[bl] = 0;
    }
#else
    bl = min (bl, bufSize);
#endif
    if (pdmSize)
	*pdmSize = bl;
    return (buf);
}

//----------------------------------------------------------------------

/// Initializes the empty object. \p operation is the function that returned the error code.
stream_bounds_exception::stream_bounds_exception (const char* operation, const char* type, uoff_t offset, size_t expected, size_t remaining) throw()
: libc_exception (operation),
  m_TypeName (type),
  m_Offset (offset),
  m_Expected (expected),
  m_Remaining (remaining)
{
    set_format (xfmt_StreamBoundsException);
}

/// Returns a descriptive error message. fmt="%s stream %s: @%u: expected %u, available %u";
void stream_bounds_exception::info (string& msgbuf, const char* fmt) const throw()
{
    char typeName [256];
    strncpy (typeName, m_TypeName, VectorSize(typeName));
    typeName[VectorSize(typeName)-1] = 0;
    if (!fmt) fmt = "%s stream %s: @0x%X: need %u bytes, have %u";
#if PLATFORM_ANDROID
    msgbuf.format (fmt, demangle_type_name (VectorBlock(typeName)), m_Operation, m_Offset, m_Expected, m_Remaining);
#else /* !PLATFORM_ANDROID */
    try { msgbuf.format (fmt, demangle_type_name (VectorBlock(typeName)), m_Operation, m_Offset, m_Expected, m_Remaining); } catch (...) {}
#endif
}

/// Reads the exception from stream \p is.
void stream_bounds_exception::read (istream& is)
{
    libc_exception::read (is);
    is >> m_TypeName >> m_Offset >> m_Expected >> m_Remaining;
}

/// Writes the exception into stream \p os.
void stream_bounds_exception::write (ostream& os) const
{
    libc_exception::write (os);
    os << m_TypeName << m_Offset << m_Expected << m_Remaining;
}

/// Returns the size of the written exception.
size_t stream_bounds_exception::stream_size (void) const
{
    return (libc_exception::stream_size() +
	    stream_size_of(m_TypeName) +
	    stream_size_of(m_Offset) +
	    stream_size_of(m_Expected) +
	    stream_size_of(m_Remaining));
}

} // namespace ustl

