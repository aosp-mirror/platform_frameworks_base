// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// cmemlink.h
//

#ifndef CMEMLINK_H_7CFAB32C5C6732ED29B34EF00EA40A12
#define CMEMLINK_H_7CFAB32C5C6732ED29B34EF00EA40A12

#include "ualgobase.h"

/// The ustl namespace contains all ustl classes and algorithms.
namespace ustl {

class istream;
class ostream;
class ostringstream;

/// \class cmemlink cmemlink.h ustl.h
/// \ingroup MemoryManagement
///
/// \brief A read-only pointer to a sized block of memory.
///
/// Use this class the way you would a const pointer to an allocated unstructured block.
/// The pointer and block size are available through member functions and cast operator.
///
/// Example usage:
///
/// \code
///     void* p = malloc (46721);
///     cmemlink a, b;
///     a.link (p, 46721);
///     assert (a.size() == 46721));
///     b = a;
///     assert (b.size() == 46721));
///     assert (b.DataAt(34) == a.DataAt(34));
///     assert (0 == memcmp (a, b, 12));
/// \endcode
///
class cmemlink {
public:
    typedef char		value_type;
    typedef const value_type*	pointer;
    typedef const value_type*	const_pointer;
    typedef value_type		reference;
    typedef value_type		const_reference;
    typedef size_t		size_type;
    typedef uint32_t		written_size_type;
    typedef ptrdiff_t		difference_type;
    typedef const_pointer	const_iterator;
    typedef const_iterator	iterator;
    typedef const cmemlink&	rcself_t;
public:
    inline		cmemlink (void)				: m_Data (NULL), m_Size (0) { }
    inline		cmemlink (const void* p, size_type n)	: m_Data (const_pointer(p)), m_Size (n) { assert (p || !n); }
    inline		cmemlink (const cmemlink& l)		: m_Data (l.m_Data), m_Size (l.m_Size) {}
    inline virtual     ~cmemlink (void)				{}
    void		link (const void* p, size_type n);
			OVERLOAD_POINTER_AND_SIZE_T_V2(link, const void*)
    inline void		link (const cmemlink& l)	{ link (l.begin(), l.size()); }
    inline void		link (const void* first, const void* last)	{ link (first, distance (first, last)); }
    inline void		relink (const void* p, size_type n);
    inline virtual void	unlink (void)			{ m_Data = NULL; m_Size = 0; }
    inline rcself_t	operator= (const cmemlink& l)	{ link (l); return (*this); }
    bool		operator== (const cmemlink& l) const;
    void		swap (cmemlink& l);
    inline size_type	size (void) const		{ return (m_Size); }
    inline size_type	max_size (void) const		{ return (size()); }
    inline size_type	readable_size (void) const	{ return (size()); }
    inline bool		empty (void) const		{ return (!size()); }
   inline const_pointer	cdata (void) const		{ return (m_Data); }
    inline iterator	begin (void) const		{ return (iterator (cdata())); }
    inline iterator	iat (size_type i) const		{ assert (i <= size()); return (begin() + i); }
    inline iterator	end (void) const		{ return (iat (size())); }
    inline void		resize (size_type n)		{ m_Size = n; }
    inline void		read (istream&)			{ assert (!"ustl::cmemlink is a read-only object."); }
    void		write (ostream& os) const;
    size_type		stream_size (void) const;
    void		text_write (ostringstream& os) const;
    void		write_file (const char* filename, int mode = 0644) const;
private:
    const_pointer	m_Data;		///< Pointer to the data block (const)
    size_type		m_Size;		///< size of the data block
};

/// A fast alternative to link which can be used when relinking to the same block (i.e. when it is resized)
inline void cmemlink::relink (const void* p, size_type n)
{
    m_Data = reinterpret_cast<const_pointer>(p);
    m_Size = n;
}

/// Use with cmemlink-derived classes to link to a static array
#define static_link(v)	link (VectorBlock(v))

} // namespace ustl

#endif

