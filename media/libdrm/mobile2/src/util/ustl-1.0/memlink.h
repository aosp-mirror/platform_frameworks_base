// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// memlink.h

#ifndef MEMLINK_H_798D25827C8E322D2D7E734B169FF5FC
#define MEMLINK_H_798D25827C8E322D2D7E734B169FF5FC

#include "cmemlink.h"
#include "ualgo.h"
#include "uassert.h"

namespace ustl {

/// \class memlink memlink.h ustl.h
/// \ingroup MemoryManagement
///
/// \brief Wrapper for pointer to block with size.
///
/// Use this class the way you would a pointer to an allocated unstructured block.
/// The pointer and block size are available through member functions and cast operator.
///
/// Example usage:
/// \code
///     void* p = malloc (46721);
///     memlink a, b;
///     a.link (p, 46721);
///     assert (a.size() == 46721));
///     b = a;
///     assert (b.size() == 46721));
///     assert (b.begin() + 34 == a.begin + 34);
///     assert (0 == memcmp (a, b, 12));
///     a.fill (673, b, 42, 67);
///     b.erase (87, 12);
/// \endcode
///
class memlink : public cmemlink {
public:
    typedef value_type*			pointer;
    typedef cmemlink::pointer		const_pointer;
    typedef cmemlink::const_iterator	const_iterator;
    typedef pointer			iterator;
    typedef const memlink&		rcself_t;
public:
    inline		memlink (void)				: cmemlink() {}
    inline		memlink (void* p, size_type n)		: cmemlink (p, n) {}
    inline		memlink (const void* p, size_type n)	: cmemlink (p, n) {}
    inline		memlink (rcself_t l)			: cmemlink (l) {}
    inline explicit	memlink (const cmemlink& l)		: cmemlink (l) {}
    inline pointer	data (void)				{ return (const_cast<pointer>(cdata())); }
    inline iterator	begin (void)				{ return (iterator (data())); }
    inline iterator	iat (size_type i)			{ assert (i <= size()); return (begin() + i); }
    inline iterator	end (void)				{ return (iat (size())); }
    inline const_iterator	begin (void) const		{ return (cmemlink::begin()); }
    inline const_iterator	end (void) const		{ return (cmemlink::end()); }
    inline const_iterator	iat (size_type i) const		{ return (cmemlink::iat (i)); }
    size_type		writable_size (void) const		{ return (size()); }
    inline rcself_t	operator= (const cmemlink& l)		{ cmemlink::operator= (l); return (*this); }
    inline rcself_t	operator= (rcself_t l)			{ cmemlink::operator= (l); return (*this); }
    inline void		link (const void* p, size_type n)	{ cmemlink::link (p, n); }
    inline void		link (void* p, size_type n)		{ cmemlink::link (p, n); }
    inline void		link (const cmemlink& l)		{ cmemlink::link (l); }
    inline void		link (memlink& l)			{ cmemlink::link (l); }
			OVERLOAD_POINTER_AND_SIZE_T_V2(link, void*)
			OVERLOAD_POINTER_AND_SIZE_T_V2(link, const void*)
    inline void		link (const void* first, const void* last)	{ link (first, distance (first, last)); }
    inline void		link (void* first, void* last)		{ link (first, distance (first, last)); }
    inline void		relink (const void* p, size_type n)	{ cmemlink::relink (p, n); }
    inline void		relink (void* p, size_type n)		{ cmemlink::relink (p, n); }
    inline void		copy (const cmemlink& l)		{ copy (begin(), l.cdata(), l.size()); }
    inline void		copy (const void* p, size_type n)	{ copy (begin(), p, n); }
    void		copy (iterator offset, const void* p, size_type n);
    inline void		swap (memlink& l)			{ cmemlink::swap (l); }
    void		fill (iterator start, const void* p, size_type elsize, size_type elCount = 1);
    inline void		insert (iterator start, size_type size);
    inline void		erase (iterator start, size_type size);
    void		read (istream& is);
};

/// Shifts the data in the linked block from \p start to \p start + \p n.
/// The contents of the uncovered bytes is undefined.
inline void memlink::insert (iterator start, size_type n)
{
    assert (data() || !n);
    assert (cmemlink::begin() || !n);
    assert (start >= begin() && start + n <= end());
    rotate (start, end() - n, end());
}

/// Shifts the data in the linked block from \p start + \p n to \p start.
/// The contents of the uncovered bytes is undefined.
inline void memlink::erase (iterator start, size_type n)
{
    assert (data() || !n);
    assert (cmemlink::begin() || !n);
    assert (start >= begin() && start + n <= end());
    rotate (start, start + n, end());
}

/// Reads object \p l from stream \p is
inline istream& operator>> (istream& is, memlink& l)
{
    l.read (is);
    return (is);
}

/// Use with memlink-derived classes to allocate and link to stack space.
#define alloca_link(m,n)	(m).link (alloca (n), (n))

} // namespace ustl

#endif

