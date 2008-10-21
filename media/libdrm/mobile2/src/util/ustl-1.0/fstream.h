// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// fstream.h
//

#ifndef FSTREAM_H_056E10F70EAD416443E3B36A2D6B5FA3
#define FSTREAM_H_056E10F70EAD416443E3B36A2D6B5FA3

#include "uios.h"
#include "ustring.h"

struct stat;

namespace ustl {

/// \class fstream fstream.h ustl.h
///
/// \brief Implements file operations.
///
/// This is not implemented as a stream, but rather as a base for one. You
/// should use ifstream or ofstream if you want flow operators. Otherwise
/// this only implements functions for binary i/o.
///
class fstream : public ios_base {
public:
			fstream (void);
    explicit		fstream (const char* filename, openmode mode = in | out);
    explicit		fstream (int nfd, const char* filename = string::empty_string);
		       ~fstream (void) throw();
    void		open (const char* filename, openmode mode, mode_t perms = 0644);
    void		attach (int nfd, const char* filename = string::empty_string);
    void		detach (void);
    void		close (void);
    void		sync (void);
    off_t		read (void* p, off_t n);
    off_t		readsome (void* p, off_t n);
    off_t		write (const void* p, off_t n);
    off_t		size (void) const;
    off_t		seek (off_t n, seekdir whence = beg);
    off_t		pos (void) const;
    void		stat (struct stat& rs) const;
    int			ioctl (const char* rname, int request, long argument = 0);
    inline int		ioctl (const char* rname, int request, int argument)	{ return (fstream::ioctl (rname, request, long(argument))); }
    inline int		ioctl (const char* rname, int request, void* argument)	{ return (fstream::ioctl (rname, request, intptr_t(argument))); }
    int			fcntl (const char* rname, int request, long argument = 0);
    inline int		fcntl (const char* rname, int request, int argument)	{ return (fstream::fcntl (rname, request, long(argument))); }
    inline int		fcntl (const char* rname, int request, void* argument)	{ return (fstream::fcntl (rname, request, intptr_t(argument))); }
    memlink		mmap (off_t n, off_t offset = 0);
    void		munmap (memlink& l);
    void		msync (memlink& l);
    void		set_nonblock (bool v = true);
    inline int		fd (void) const		{ return (m_fd); }
    inline bool		is_open (void) const	{ return (fd() >= 0); }
    inline off_t	tellg (void) const	{ return (pos()); }
    inline off_t	tellp (void) const	{ return (pos()); }
    inline void		seekg (off_t n, seekdir whence = beg)	{ seek (n, whence); }
    inline void		seekp (off_t n, seekdir whence = beg)	{ seek (n, whence); }
    inline void		flush (void)		{ sync(); }
    inline const string& name (void) const	{ return (m_Filename); }
private:
   DLL_LOCAL static int	om_to_flags (openmode m);
    DLL_LOCAL void	set_and_throw (iostate s, const char* op);
private:
    int			m_fd;		///< Currently open file descriptor.
    string		m_Filename;	///< Currently open filename.
};

/// Argument macro for fstream::ioctl. Use like fs.ioctl (IOCTLID (TCGETS), &ts).
#define IOCTLID(r)	"ioctl("#r")", r
#define FCNTLID(r)	"fcntl("#r")", r

}

#endif

