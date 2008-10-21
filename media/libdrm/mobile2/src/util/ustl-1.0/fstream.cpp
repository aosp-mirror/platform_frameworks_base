// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// file.cc
//

#include "fstream.h"
#include "uassert.h"
#include "uexception.h"
#include "uutility.h"

#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <sys/ioctl.h>

#if PLATFORM_ANDROID
#include <stdio.h>
#endif

namespace ustl {

/// Default constructor.
fstream::fstream (void)
: ios_base (),
  m_fd (-1),
  m_Filename ()
{
}

/// Opens \p filename in \p mode.
fstream::fstream (const char* filename, openmode mode)
: ios_base (),
  m_fd (-1),
  m_Filename ()
{
    open (filename, mode);
}

/// Attaches to \p nfd of \p filename.
fstream::fstream (int nfd, const char* filename)
: ios_base (),
  m_fd (-1),
  m_Filename ()
{
    attach (nfd, filename);
}

/// Destructor. Closes if still open, but without throwing.
fstream::~fstream (void) throw()
{
    clear (goodbit);
    exceptions (goodbit);
    close();
    assert (!(rdstate() & badbit) && "close failed in the destructor! This may lead to loss of user data. Please call close() manually and either enable exceptions or check the badbit.");
}

/// Sets state \p s and throws depending on the exception setting.
void fstream::set_and_throw (iostate s, const char* op)
{
    if (ios_base::set_and_throw (s))
#if PLATFORM_ANDROID
        printf("file_exception\n");
#else /* !PLATFORM_ANDROID */
	throw file_exception (op, name());
#endif
}

/// Attaches to the given \p nfd.
void fstream::attach (int nfd, const char* filename)
{
    assert (filename && "Don't do that");
    clear (goodbit);
    if (nfd < 0 && ios_base::set_and_throw (badbit))
#if PLATFORM_ANDROID
        printf("file exception\n");
#else /* !PLATFORM_ANDROID */
	throw file_exception ("open", filename);
#endif
    close();
    m_fd = nfd;
    m_Filename = filename;
}

/// Detaches from the current fd.
void fstream::detach (void)
{
    m_fd = -1;
    m_Filename.clear();
}

/// Converts openmode bits into libc open flags.
/*static*/ int fstream::om_to_flags (openmode m)
{
    static const int s_OMFlags [nombits] = {
	0,		// in
	O_CREAT,	// out
	O_APPEND,	// app
	O_APPEND,	// ate
	0,		// binary
	O_TRUNC,	// trunc
	O_NONBLOCK,	// nonblock
	0,		// nocreate
	O_NOCTTY	// noctty
    };
    int flags = (m - 1) & O_ACCMODE;	// in and out
    for (uoff_t i = 0; i < VectorSize(s_OMFlags); ++ i)
	if (m & (1 << i))
	    flags |= s_OMFlags[i];
    if (m & nocreate)
	flags &= ~O_CREAT;
    return (flags);
}

/// \brief Opens \p filename in the given mode.
/// \warning The string at \p filename must exist until the object is closed.
void fstream::open (const char* filename, openmode mode, mode_t perms)
{
    int nfd = ::open (filename, om_to_flags(mode), perms);
    attach (nfd, filename);
}

/// Closes the file and throws on error.
void fstream::close (void)
{
    if (m_fd >= 0 && ::close(m_fd))
	set_and_throw (badbit | failbit, "close");
    detach();
}

/// Moves the current file position to \p n.
off_t fstream::seek (off_t n, seekdir whence)
{
    off_t p = lseek (m_fd, n, whence);
    if (p < 0)
	set_and_throw (failbit, "seek");
    return (p);
}

/// Returns the current file position.
off_t fstream::pos (void) const
{
    return (lseek (m_fd, 0, SEEK_CUR));
}

/// Reads \p n bytes into \p p.
off_t fstream::read (void* p, off_t n)
{
    off_t br (0);
    while (br < n && good())
	br += readsome (advance (p, br), n - br);
    return (br);
}

/// Reads at most \p n bytes into \p p, stopping when it feels like it.
off_t fstream::readsome (void* p, off_t n)
{
    ssize_t brn;
    do { brn = ::read (m_fd, p, n); } while (brn < 0 && errno == EINTR);
    if (brn > 0)
	return (brn);
    if (brn < 0 && errno != EAGAIN)
	set_and_throw (failbit, "read");
    if (!brn && ios_base::set_and_throw (eofbit | failbit))
#if PLATFORM_ANDROID
        printf("stream_bounds_exception\n");
#else /* !PLATFORM_ANDROID */
        throw stream_bounds_exception ("read", name(), pos(), n, 0);
#endif
    return (0);
}

/// Writes \p n bytes from \p p.
off_t fstream::write (const void* p, off_t n)
{
    off_t btw (n);
    while (btw) {
	const off_t bw (n - btw);
	ssize_t bwn = ::write (m_fd, advance(p,bw), btw);
	if (bwn > 0)
	    btw -= bwn;
	else if (!bwn) {
	    if (ios_base::set_and_throw (eofbit | failbit))
#if PLATFORM_ANDROID
	      printf("stream_bounds_exception\n");
#else /* !PLATFORM_ANDROID */
	    throw stream_bounds_exception ("write", name(), pos() - bw, n, bw);
#endif
	    break;
	} else if (errno != EINTR) {
	    if (errno != EAGAIN)
		set_and_throw (failbit, "write");
	    break;
	}
    }
    return (n - btw);
}

/// Returns the file size.
off_t fstream::size (void) const
{
    struct stat st;
    st.st_size = 0;
    stat (st);
    return (st.st_size);
}

/// Synchronizes the file's data and status with the disk.
void fstream::sync (void)
{
    if (fsync (m_fd))
	set_and_throw (failbit, "sync");
}

/// Get the stat structure.
void fstream::stat (struct stat& rs) const
{
    if (fstat (m_fd, &rs))
#if PLATFORM_ANDROID
        printf("file_exception\n");
#else
	throw file_exception ("stat", name());
#endif
}

/// Calls the given ioctl. Use IOCTLID macro to pass in both \p name and \p request.
int fstream::ioctl (const char* rname, int request, long argument)
{
    int rv = ::ioctl (m_fd, request, argument);
    if (rv < 0)
	set_and_throw (failbit, rname);
    return (rv);
}

/// Calls the given fcntl. Use FCNTLID macro to pass in both \p name and \p request.
int fstream::fcntl (const char* rname, int request, long argument)
{
    int rv = ::fcntl (m_fd, request, argument);
    if (rv < 0)
	set_and_throw (failbit, rname);
    return (rv);
}

/// Memory-maps the file and returns a link to it.
memlink fstream::mmap (off_t n, off_t offset)
{
    void* result = ::mmap (NULL, n, PROT_READ | PROT_WRITE, MAP_SHARED, m_fd, offset);
    if (result == MAP_FAILED)
	set_and_throw (failbit, "mmap");
    return (memlink (result, n));
}

/// Unmaps a memory-mapped area.
void fstream::munmap (memlink& l)
{
    if (::munmap (l.data(), l.size()))
	set_and_throw (failbit, "munmap");
    l.unlink();
}

/// Synchronizes a memory-mapped area.
void fstream::msync (memlink& l)
{
    if (::msync (l.data(), l.size(), MS_ASYNC | MS_INVALIDATE))
	set_and_throw (failbit, "msync");
}

void fstream::set_nonblock (bool v)
{
    int curf = fcntl (FCNTLID (F_GETFL));
    if (curf < 0) return;
    if (v) curf |=  O_NONBLOCK;
    else   curf &= ~O_NONBLOCK;
    fcntl (FCNTLID (F_SETFL), curf);
}

} // namespace ustl

