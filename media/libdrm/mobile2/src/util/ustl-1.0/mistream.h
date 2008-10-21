// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// mistream.h
//
#ifndef MISTREAM_H_103AEF1F266C04AA1A817D38705983DA
#define MISTREAM_H_103AEF1F266C04AA1A817D38705983DA

#include "memlink.h"
#include "uexception.h"
#include "strmsize.h"
#include "uassert.h"
#include "utf8.h"
#include "uios.h"
#ifdef WANT_STREAM_BOUNDS_CHECKING
    #include <typeinfo>
#endif

namespace ustl {

class ostream;
class memlink;
class string;

/// \class istream mistream.h ustl.h
/// \ingroup BinaryStreams
///
/// \brief Helper class to read packed binary streams.
/// 
/// This class contains a set of functions to read integral types from an
/// unstructured memory block. Unpacking binary file data can be done this
/// way, for instance. aligning the data is your responsibility, and can
/// be accomplished by proper ordering of reads and by calling the align()
/// function. Unaligned access is usually slower by orders of magnitude and,
/// on some architectures, such as PowerPC, can cause your program to crash.
/// Therefore, all read functions have asserts to check alignment.
/// Overreading the end of the stream will also cause a crash (an assert in
/// debug builds). Oh, and don't be intimidated by the size of the inlines
/// here. In the assembly code the compiler will usually chop everything down
/// to five instructions each.
/// 
/// Alignment rules for your objects:
///	- Assume your writes start off 4-byte aligned.
///	- After completion, \ref istream::align the stream to at least 4.
///	- If data portability between 32bit and 64bit platforms is important
///	(it often is not, in config files and the like), ensure you are always
///	using fixed-size types and are aligning to a fixed grain. Avoid writing
///	8-byte types, and if you do, manually align before doing so.
///	- Non-default alignment is allowed if you plan to frequently write this
///	object in array form and alignment would be costly. For example, an
///	array of uint16_t-sized objects may leave the stream uint16_t aligned
///	as long as you know about it and will default-align the stream after
///	writing the array (note: \ref vector will already do this for you)
/// 
/// Example code:
/// \code
///	memblock b;
///	b.read_file ("test.file");
///	ostream is (b);
///	is >> boolVar >> ios::talign<int>();
///	is >> intVar >> floatVar;
///	is.read (binaryData, binaryDataSize);
///	is.align();
/// \endcode
///
class istream : public cmemlink, public ios_base {
public:
			istream (void);
			istream (const void* p, size_type n);
    explicit		istream (const cmemlink& source);
    explicit		istream (const ostream& source);
    inline iterator	end (void) const			{ return (cmemlink::end()); }
    inline void		link (const void* p, size_type n)	{ cmemlink::link (p, n); }
    inline void		link (const cmemlink& l)		{ cmemlink::link (l.cdata(), l.readable_size()); }
    inline void		link (const void* f, const void* l)	{ cmemlink::link (f, l); }
			OVERLOAD_POINTER_AND_SIZE_T_V2(link, const void*)
    inline void		relink (const void* p, size_type n)	{ cmemlink::relink (p, n); m_Pos = 0; }
    inline void		relink (const cmemlink& l)		{ relink (l.cdata(), l.readable_size()); }
    virtual void	unlink (void);
    inline virtual size_type	underflow (size_type = 1)	{ return (remaining()); }
    inline uoff_t	pos (void) const	{ return (m_Pos); }
    inline const_iterator ipos (void) const	{ return (begin() + pos()); }
    inline size_type	remaining (void) const	{ return (size() - pos()); }
    inline void		seek (uoff_t newPos);
    inline void		iseek (const_iterator newPos);
    inline void		skip (size_type nBytes);
    inline bool		aligned (size_type grain = c_DefaultAlignment) const;
    void		verify_remaining (const char* op, const char* type, size_t n) const;
    inline size_type	align_size (size_type grain = c_DefaultAlignment) const;
    inline void		align (size_type grain = c_DefaultAlignment);
    void		swap (istream& is);
    void		read (void* buffer, size_type size);
    inline void		read (memlink& buf)	{ read (buf.begin(), buf.writable_size()); }
    void		read_strz (string& str);
    size_type		readsome (void* s, size_type n);
    inline void		read (istream&)			{ }
    void		write (ostream& os) const;
    void		text_write (ostringstream& os) const;
    inline size_t	stream_size (void) const	{ return (remaining()); }
    template <typename T>
    inline void		iread (T& v);
    inline void		ungetc (void)		{ seek (pos() - 1); }
    inline off_t	tellg (void) const	{ return (pos()); }
    inline void		seekg (off_t p, seekdir d = beg);
private:
    uoff_t		m_Pos;		///< The current read position.
};

//----------------------------------------------------------------------

template <typename T, typename Stream>
inline size_t required_stream_size (T, const Stream&) { return (1); }
template <typename T>
inline size_t required_stream_size (T v, const istream&) { return (stream_size_of(v)); }

template <typename Stream>
inline bool stream_at_eof (const Stream& stm)	{ return (stm.eof()); }
template <>
inline bool stream_at_eof (const istream&)	{ return (false); }

/// \class istream_iterator
/// \ingroup BinaryStreamIterators
///
/// \brief An iterator over an istream to use with uSTL algorithms.
///
template <typename T, typename Stream = istream>
class istream_iterator {
public:
    typedef T			value_type;
    typedef ptrdiff_t		difference_type;
    typedef const value_type*	pointer;
    typedef const value_type&	reference;
    typedef size_t		size_type;
public:
				istream_iterator (void)		: m_pis (NULL), m_v() {}
    explicit			istream_iterator (Stream& is)	: m_pis (&is), m_v() { Read(); }
 				istream_iterator (const istream_iterator& i)	: m_pis (i.m_pis), m_v (i.m_v) {}
    /// Reads and returns the next value.
    inline const T&		operator* (void)	{ return (m_v); }
    inline istream_iterator&	operator++ (void)	{ Read(); return (*this); }
    inline istream_iterator&	operator-- (void)	{ m_pis->seek (m_pis->pos() - 2 * stream_size_of(m_v)); return (operator++()); }
    inline istream_iterator	operator++ (int)	{ istream_iterator old (*this); operator++(); return (old); }
    inline istream_iterator	operator-- (int)	{ istream_iterator old (*this); operator--(); return (old); }
    inline istream_iterator&	operator+= (size_type n)	{ while (n--) operator++(); return (*this); }
    inline istream_iterator&	operator-= (size_type n)	{ m_pis->seek (m_pis->pos() - (n + 1) * stream_size_of(m_v)); return (operator++()); }
    inline istream_iterator	operator- (size_type n) const			{ istream_iterator result (*this); return (result -= n); }
    inline difference_type	operator- (const istream_iterator& i) const	{ return (distance (i.m_pis->pos(), m_pis->pos()) / stream_size_of(m_v)); }
    inline bool			operator== (const istream_iterator& i) const	{ return ((!m_pis && !i.m_pis) || (m_pis && i.m_pis && m_pis->pos() == i.m_pis->pos())); }
    inline bool			operator< (const istream_iterator& i) const	{ return (!i.m_pis || (m_pis && m_pis->pos() < i.m_pis->pos())); }
private:
    void Read (void)
    {
	if (!m_pis)
	    return;
	const size_t rs (required_stream_size (m_v, *m_pis));
	if (m_pis->remaining() < rs && m_pis->underflow (rs) < rs) {
	    m_pis = NULL;
	    return;
	}
	*m_pis >> m_v;
	if (stream_at_eof (*m_pis))
	    m_pis = NULL;
    }
private:
    Stream*	m_pis;		///< The host stream.
    T		m_v;		///< Last read value; cached to be returnable as a const reference.
};

//----------------------------------------------------------------------

/// Sets the current read position to \p newPos
inline void istream::seek (uoff_t newPos)
{
#ifdef WANT_STREAM_BOUNDS_CHECKING
    if (newPos > size())
	throw stream_bounds_exception ("seekg", "byte", pos(), newPos - pos(), size());
#else
    assert (newPos <= size());
#endif
    m_Pos = newPos;
}

/// Sets the current read position to \p newPos
inline void istream::iseek (const_iterator newPos)
{
    seek (distance (begin(), newPos));
}

/// Sets the current write position to \p p based on \p d.
inline void istream::seekg (off_t p, seekdir d)
{
    switch (d) {
	case beg:	seek (p); break;
	case cur:	seek (pos() + p); break;
	case ios_base::end:	seek (size() - p); break;
    }
}

/// Skips \p nBytes without reading them.
inline void istream::skip (size_type nBytes)
{
    seek (pos() + nBytes);
}

/// Returns the number of bytes to skip to be aligned on \p grain.
inline istream::size_type istream::align_size (size_type grain) const
{
    return (Align (pos(), grain) - pos());
}

/// Returns \c true if the read position is aligned on \p grain
inline bool istream::aligned (size_type grain) const
{
    assert (uintptr_t(begin()) % grain == 0 && "Streams should be attached aligned at the maximum element grain to avoid bus errors.");
    return (pos() % grain == 0);
}

/// aligns the read position on \p grain
inline void istream::align (size_type grain)
{
    seek (Align (pos(), grain));
}

/// Reads type T from the stream via a direct pointer cast.
template <typename T>
inline void istream::iread (T& v)
{
    assert (aligned (alignof (T())));
#ifdef WANT_STREAM_BOUNDS_CHECKING
    verify_remaining ("read", typeid(v).name(), sizeof(T));
#else
    assert (remaining() >= sizeof(T));
#endif
    v = *reinterpret_cast<const T*>(ipos());
    m_Pos += sizeof(T);
}

#define ISTREAM_OPERATOR(type)	\
inline istream&	operator>> (istream& is, type& v)	{ is.iread(v); return (is); }

template <typename T>
ISTREAM_OPERATOR(T*)
ISTREAM_OPERATOR(int8_t)
ISTREAM_OPERATOR(uint8_t)
ISTREAM_OPERATOR(int16_t)
ISTREAM_OPERATOR(uint16_t)
ISTREAM_OPERATOR(int32_t)
ISTREAM_OPERATOR(uint32_t)
ISTREAM_OPERATOR(float)
ISTREAM_OPERATOR(double)
ISTREAM_OPERATOR(wchar_t)
#if SIZE_OF_BOOL == SIZE_OF_CHAR
ISTREAM_OPERATOR(bool)
#else
inline istream&	operator>> (istream& is, bool& v)
{ uint8_t v8; is.iread (v8); v = v8; return (is); }
#endif
#if HAVE_THREE_CHAR_TYPES
ISTREAM_OPERATOR(char)
#endif
#if HAVE_INT64_T
ISTREAM_OPERATOR(int64_t)
ISTREAM_OPERATOR(uint64_t)
#endif
#if SIZE_OF_LONG == SIZE_OF_INT
ISTREAM_OPERATOR(long)
ISTREAM_OPERATOR(unsigned long)
#endif
#if HAVE_LONG_LONG && (!HAVE_INT64_T || SIZE_OF_LONG_LONG > 8)
ISTREAM_OPERATOR(long long)
ISTREAM_OPERATOR(unsigned long long)
#endif

//----------------------------------------------------------------------

typedef istream_iterator<utf8subchar_t> istream_iterator_for_utf8;
typedef utf8in_iterator<istream_iterator_for_utf8> utf8istream_iterator;

/// Returns a UTF-8 adaptor reading from \p is.
inline utf8istream_iterator utf8in (istream& is)
{
    istream_iterator_for_utf8 si (is);
    return (utf8istream_iterator (si));
}

//----------------------------------------------------------------------

} // namespace ustl

#endif

