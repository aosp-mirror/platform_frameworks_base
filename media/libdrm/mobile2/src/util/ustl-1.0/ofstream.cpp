// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// ofstream.cc
//

#include "ofstream.h"
#include "ustring.h"
#include "uexception.h"
#include <unistd.h>
#include <errno.h>
#include <stdio.h>
#include <stdarg.h>

namespace ustl {

//----------------------------------------------------------------------

ifstream cin  (STDIN_FILENO);
ofstream cout (STDOUT_FILENO);
ofstream cerr (STDERR_FILENO);

//----------------------------------------------------------------------

/// Default constructor.
ofstream::ofstream (void)
: ostringstream (),
  m_File ()
{
    reserve (255);
}

/// Constructs a stream for writing to \p Fd.
ofstream::ofstream (int Fd)
: ostringstream (),
  m_File (Fd)
{
    clear (m_File.rdstate());
    reserve (255);
}

/// Constructs a stream for writing to \p filename.
ofstream::ofstream (const char* filename, openmode mode)
: ostringstream (),
  m_File (filename, mode)
{
    clear (m_File.rdstate());
}

/// Default destructor.
ofstream::~ofstream (void)
{
#if PLATFORM_ANDROID
    flush();
#else /* !PLATFORM_ANDROID */
    try { flush(); } catch (...) {}
#endif
}

/// Flushes the buffer to the file.
void ofstream::flush (void)
{
    while (pos() && overflow (remaining()));
    m_File.sync();
    clear (m_File.rdstate());
}

/// Seeks to \p p based on \p d.
void ofstream::seekp (off_t p, seekdir d)
{
    flush();
    m_File.seekp (p, d);
    clear (m_File.rdstate());
}

/// Called when more buffer space (\p n bytes) is needed.
ofstream::size_type ofstream::overflow (size_type n)
{
    if (eof() || (n > remaining() && n < capacity() - pos()))
	return (ostringstream::overflow (n));
    size_type bw = m_File.write (cdata(), pos());
    clear (m_File.rdstate());
    erase (begin(), bw);
    if (remaining() < n)
	ostringstream::overflow (n);
    return (remaining());
}

//----------------------------------------------------------------------

/// Constructs a stream to read from \p Fd.
ifstream::ifstream (int Fd)
: istringstream (),
  m_Buffer (255),
  m_File (Fd)
{
    link (m_Buffer.data(), 0U);
}

/// Constructs a stream to read from \p filename.
ifstream::ifstream (const char* filename, openmode mode)
: istringstream (),
  m_Buffer (255),
  m_File (filename, mode)
{
    clear (m_File.rdstate());
    link (m_Buffer.data(), 0U);
}

/// Reads at least \p n more bytes and returns available bytes.
ifstream::size_type ifstream::underflow (size_type n)
{
    if (eof())
	return (istringstream::underflow (n));

    const ssize_t freeSpace = m_Buffer.size() - pos();
    const ssize_t neededFreeSpace = max (n, m_Buffer.size() / 2);
    const size_t oughtToErase = Align (max (0, neededFreeSpace - freeSpace));
    const size_t nToErase = min (pos(), oughtToErase);
    m_Buffer.memlink::erase (m_Buffer.begin(), nToErase);
    const uoff_t oldPos (pos() - nToErase);

    size_type br = oldPos;
    if (m_Buffer.size() - br < n) {
	m_Buffer.resize (br + neededFreeSpace);
	link (m_Buffer.data(), 0U);
    }
    cout.flush();

    while (br - oldPos < n && m_File.good())
	br += m_File.readsome (m_Buffer.begin() + br, m_Buffer.size() - br);
    clear (m_File.rdstate());

    m_Buffer[br] = string::c_Terminator;
    link (m_Buffer.data(), br);
    seek (oldPos);
    return (remaining());
}

/// Flushes the input.
void ifstream::sync (void)
{
    istringstream::sync();
    underflow (0U);
    m_File.sync();
    clear (m_File.rdstate());
}

/// Seeks to \p p based on \p d.
void ifstream::seekg (off_t p, seekdir d)
{
    m_Buffer.clear();
    link (m_Buffer);
    m_File.seekg (p, d);
    clear (m_File.rdstate());
}

//----------------------------------------------------------------------

} // namespace ustl

