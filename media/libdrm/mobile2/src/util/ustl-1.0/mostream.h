// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// mostream.h

#ifndef MOSTREAM_H_24A8C5397E0848216573E5670930FC9A
#define MOSTREAM_H_24A8C5397E0848216573E5670930FC9A

#include "memlink.h"
#include "uassert.h"
#include "uexception.h"
#include "utf8.h"
#include "uios.h"
#include <typeinfo>

namespace ustl {

class istream;
class string;

/// \class ostream mostream.h ustl.h
/// \ingroup BinaryStreams
///
/// \brief Helper class to write packed binary streams.
///
/// This class contains a set of functions to write integral types into an
/// unstructured memory block. Packing binary file data can be done this
/// way, for instance. aligning the data is your responsibility, and can
/// be accomplished by proper ordering of writes and by calling \ref ostream::align.
/// Unaligned access is usually slower by orders of magnitude and,
/// on some architectures, such as PowerPC, can cause your program to crash.
/// Therefore, all write functions have asserts to check alignment.
/// See \ref istream documentation for rules on designing your data format.
/// Overwriting the end of the stream will also cause a crash (an assert in
/// debug builds). Oh, and don't be intimidated by the size of the inlines
/// here. In the assembly code the compiler will usually chop everything down
/// to five instructions each.
///
/// Example code:
/// \code
///     memblock b;
///     ostream os (b);
///     os << boolVar << ios::talign<int>();
///     os << intVar << floatVar;
///     os.write (binaryData, binaryDataSize);
///     os.align();
///     b.resize (os.pos());
///	b.write_file ("test.file");
/// \endcode
///
class ostream : public memlink, public ios_base {
public:
			ostream (void);
			ostream (void* p, size_type n);
    explicit		ostream (const memlink& source);
    inline iterator	end (void)			{ return (memlink::end()); }
    inline const_iterator	end (void) const	{ return (memlink::end()); }
    inline void		seek (uoff_t newPos);
    inline void		iseek (const_iterator newPos);
    inline void		skip (size_type nBytes);
    inline uoff_t	pos (void) const	{ return (m_Pos); }
    inline iterator	ipos (void)		{ return (begin() + pos()); }
    inline const_iterator ipos (void) const	{ return (begin() + pos()); }
    inline size_type	remaining (void) const;
    inline bool		aligned (size_type grain = c_DefaultAlignment) const;
    void		verify_remaining (const char* op, const char* type, size_t n) const;
    inline size_type	align_size (size_type grain = c_DefaultAlignment) const;
    void		align (size_type grain = c_DefaultAlignment);
    void		write (const void* buffer, size_type size);
    inline void		write (const cmemlink& buf);
    void		write_strz (const char* str);
    void		read (istream& is);
    inline void		write (ostream& os) const	{ os.write (begin(), pos()); }
    void		text_write (ostringstream& os) const;
    inline size_t	stream_size (void) const	{ return (pos()); }
    void		insert (iterator start, size_type size);
    void		erase (iterator start, size_type size);
    void		swap (ostream& os);
    template <typename T>
    inline void		iwrite (const T& v);
    inline virtual size_type	overflow (size_type = 1){ return (remaining()); }
    virtual void	unlink (void);
    inline void		link (void* p, size_type n)	{ memlink::link (p, n); }
    inline void		link (memlink& l)		{ memlink::link (l.data(), l.writable_size()); }
    inline void		link (void* f, void* l)		{ memlink::link (f, l); }
			OVERLOAD_POINTER_AND_SIZE_T_V2(link, void*)
    inline void		relink (void* p, size_type n)	{ memlink::relink (p, n); m_Pos = 0; }
    inline void		relink (memlink& l)		{ relink (l.data(), l.writable_size()); }
    inline void		seekp (off_t p, seekdir d = beg);
    inline off_t	tellp (void) const		{ return (pos()); }
protected:
    inline void		SetPos (uoff_t newPos)		{ m_Pos = newPos; }
private:
    uoff_t		m_Pos;	///< Current write position.
};

//----------------------------------------------------------------------

/// \class ostream_iterator mostream.h ustl.h
/// \ingroup BinaryStreamIterators
///
/// \brief An iterator over an ostream to use with uSTL algorithms.
///
template <typename T, typename Stream = ostream>
class ostream_iterator {
public:
    typedef T			value_type;
    typedef ptrdiff_t		difference_type;
    typedef value_type*		pointer;
    typedef value_type&		reference;
    typedef ostream::size_type	size_type;
public:
    inline explicit		ostream_iterator (Stream& os)
				    : m_Os (os) {}
    inline			ostream_iterator (const ostream_iterator& iter)
				    : m_Os (iter.m_Os) {} 
    /// Writes \p v into the stream.
    inline ostream_iterator&	operator= (const T& v)
				    { m_Os << v; return (*this); }
    inline ostream_iterator&	operator* (void) { return (*this); }
    inline ostream_iterator&	operator++ (void) { return (*this); }
    inline ostream_iterator	operator++ (int) { return (*this); }
    inline ostream_iterator&	operator+= (size_type n) { m_Os.skip (n); return (*this); }
    inline bool			operator== (const ostream_iterator& i) const
				    { return (m_Os.pos() == i.m_Os.pos()); }
    inline bool			operator< (const ostream_iterator& i) const
				    { return (m_Os.pos() < i.m_Os.pos()); }
private:
    Stream&	m_Os;
};

//----------------------------------------------------------------------

typedef ostream_iterator<utf8subchar_t> ostream_iterator_for_utf8;
typedef utf8out_iterator<ostream_iterator_for_utf8> utf8ostream_iterator;

/// Returns a UTF-8 adaptor writing to \p os.
inline utf8ostream_iterator utf8out (ostream& os)
{
    ostream_iterator_for_utf8 si (os);
    return (utf8ostream_iterator (si));
}

//----------------------------------------------------------------------

/// Move the write pointer to \p newPos
inline void ostream::seek (uoff_t newPos)
{
#ifdef WANT_STREAM_BOUNDS_CHECKING
    if (newPos > size())
	throw stream_bounds_exception ("seekp", "byte", pos(), newPos - pos(), size());
#else
    assert (newPos <= size());
#endif
    SetPos (newPos);
}

/// Sets the current write position to \p newPos
inline void ostream::iseek (const_iterator newPos)
{
    seek (distance (begin(), const_cast<iterator>(newPos)));
}

/// Sets the current write position to \p p based on \p d.
inline void ostream::seekp (off_t p, seekdir d)
{
    switch (d) {
	case beg:	seek (p); break;
	case cur:	seek (pos() + p); break;
	case ios_base::end:	seek (size() - p); break;
    }
}

/// Skips \p nBytes without writing anything.
inline void ostream::skip (size_type nBytes)
{
    seek (pos() + nBytes);
}

/// Returns number of bytes remaining in the write buffer.
inline ostream::size_type ostream::remaining (void) const
{
    return (size() - pos());
}

/// Returns \c true if the write pointer is aligned on \p grain
inline bool ostream::aligned (size_type grain) const
{
    assert (uintptr_t(begin()) % grain == 0 && "Streams should be attached aligned at the maximum element grain to avoid bus errors.");
    return (pos() % grain == 0);
}

/// Returns the number of bytes to skip to be aligned on \p grain.
inline ostream::size_type ostream::align_size (size_type grain) const
{
    return (Align (pos(), grain) - pos());
}

/// Writes the contents of \p buf into the stream as a raw dump.
inline void ostream::write (const cmemlink& buf)
{
    write (buf.begin(), buf.size());
}

/// Writes type T into the stream via a direct pointer cast.
template <typename T>
inline void ostream::iwrite (const T& v)
{
    assert (aligned (alignof (v)));
#ifdef WANT_STREAM_BOUNDS_CHECKING
    verify_remaining ("write", typeid(v).name(), sizeof(T));
#else
    assert (remaining() >= sizeof(T));
#endif
    *reinterpret_cast<T*>(ipos()) = v;
    SetPos (pos() + sizeof(T));
}

#define OSTREAM_OPERATOR(type)	\
inline ostream&	operator<< (ostream& os, type v)	{ os.iwrite(v); return (os); }

template <typename T>
OSTREAM_OPERATOR(T*)
OSTREAM_OPERATOR(int8_t)
OSTREAM_OPERATOR(uint8_t)
OSTREAM_OPERATOR(int16_t)
OSTREAM_OPERATOR(uint16_t)
OSTREAM_OPERATOR(int32_t)
OSTREAM_OPERATOR(uint32_t)
OSTREAM_OPERATOR(float)
OSTREAM_OPERATOR(double)
OSTREAM_OPERATOR(wchar_t)
#if SIZE_OF_BOOL == SIZE_OF_CHAR
OSTREAM_OPERATOR(bool)
#else
inline ostream&	operator<< (ostream& os, bool v)
{ os.iwrite (uint8_t(v)); return (os); }
#endif
#if HAVE_THREE_CHAR_TYPES
OSTREAM_OPERATOR(char)
#endif
#if HAVE_INT64_T
OSTREAM_OPERATOR(int64_t)
OSTREAM_OPERATOR(uint64_t)
#endif
#if SIZE_OF_LONG == SIZE_OF_INT
OSTREAM_OPERATOR(long)
OSTREAM_OPERATOR(unsigned long)
#endif
#if HAVE_LONG_LONG && (!HAVE_INT64_T || SIZE_OF_LONG_LONG > 8)
OSTREAM_OPERATOR(long long)
OSTREAM_OPERATOR(unsigned long long)
#endif

} // namespace ustl

#endif

