// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// ustdxept.cc
//

#include "ustdxept.h"
#include "mistream.h"
#include "mostream.h"
#include "strmsize.h"
#include "uiosfunc.h"
#include "uspecial.h"

namespace ustl {

//----------------------------------------------------------------------

/// \p arg contains a description of the error.
error_message::error_message (const char* arg) throw()
: m_Arg ()
{
#if PLATFORM_ANDROID
    m_Arg = arg;
#else /* !PLATFORM_ANDROID */
    try { m_Arg = arg; } catch (...) {}
#endif
    set_format (xfmt_ErrorMessage);
}

/// Virtual destructor
error_message::~error_message (void) throw()
{
}

/// Returns a descriptive error message. fmt="%s: %s"
void error_message::info (string& msgbuf, const char* fmt) const throw()
{
    if (!fmt) fmt = "%s: %s";
#if PLATFORM_ANDROID
    msgbuf.format (fmt, what(), m_Arg.cdata());
#else /* !PLATFORM_ANDROID */
    try { msgbuf.format (fmt, what(), m_Arg.cdata()); } catch (...) {}
#endif
}

/// Reads the object from stream \p is.
void error_message::read (istream& is)
{
    exception::read (is);
    is >> m_Arg >> ios::align();
}

/// Writes the object to stream \p os.
void error_message::write (ostream& os) const
{
    exception::write (os);
    os << m_Arg << ios::align();
}

/// Returns the size of the written object.
size_t error_message::stream_size (void) const
{
    return (exception::stream_size() + Align (stream_size_of (m_Arg)));
}

//----------------------------------------------------------------------

} // namespace ustl


