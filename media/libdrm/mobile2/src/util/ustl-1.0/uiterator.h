// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
/// \file uiterator.h
/// \brief Contains various iterator adapters.
//

#ifndef UITERATOR_H_5BCA176C7214A30F2069E2614D2DC226
#define UITERATOR_H_5BCA176C7214A30F2069E2614D2DC226

#include "uassert.h"
#include "utypes.h"

namespace ustl {

//----------------------------------------------------------------------

/// \struct iterator_traits uiterator.h ustl.h
/// \brief Contains the type traits of \p Iterator
///
template <typename Iterator>
struct iterator_traits {
    typedef typename Iterator::value_type        value_type;
    typedef typename Iterator::difference_type   difference_type;
    typedef typename Iterator::pointer           pointer;
    typedef typename Iterator::reference         reference;
};

#ifndef DOXYGEN_SHOULD_SKIP_THIS

template <typename T>
struct iterator_traits<T*> {
    typedef T		value_type;
    typedef ptrdiff_t	difference_type;
    typedef const T*	const_pointer;
    typedef T*		pointer;
    typedef T&		reference;
};

template <typename T>
struct iterator_traits<const T*> {
    typedef T		value_type;
    typedef ptrdiff_t	difference_type;
    typedef const T*	const_pointer;
    typedef const T*	pointer;
    typedef const T&	reference;
};

template <>
struct iterator_traits<void*> {
    typedef uint8_t	value_type;
    typedef ptrdiff_t	difference_type;
    typedef const void*	const_pointer;
    typedef void*	pointer;
    typedef value_type&	reference;
};

template <>
struct iterator_traits<const void*> {
    typedef uint8_t		value_type;
    typedef ptrdiff_t		difference_type;
    typedef const void*		const_pointer;
    typedef const void*		pointer;
    typedef const value_type&	reference;
};

#endif

//----------------------------------------------------------------------

/// \class reverse_iterator uiterator.h ustl.h
/// \ingroup IteratorAdaptors
/// \brief Wraps \p Iterator to behave in an exactly opposite manner.
///
template <class Iterator>
class reverse_iterator {
public:
    typedef typename iterator_traits<Iterator>::value_type	value_type;
    typedef typename iterator_traits<Iterator>::difference_type	difference_type;
    typedef typename iterator_traits<Iterator>::pointer		pointer;
    typedef typename iterator_traits<Iterator>::reference	reference;
public:
    				reverse_iterator (void) : m_i() {}
    explicit			reverse_iterator (Iterator iter) : m_i (iter) {}
    inline bool			operator== (const reverse_iterator& iter) const { return (m_i == iter.m_i); }
    inline bool			operator< (const reverse_iterator& iter) const { return (iter.m_i < m_i); }
    inline Iterator		base (void) const { return (m_i); }
    inline reference		operator* (void) const { Iterator prev (m_i); --prev; return (*prev); }
    inline pointer		operator-> (void) const { return (&(operator*())); }
    inline reverse_iterator&	operator++ (void) { -- m_i; return (*this); }
    inline reverse_iterator&	operator-- (void) { ++ m_i; return (*this); }
    inline reverse_iterator	operator++ (int) { reverse_iterator prev (*this); -- m_i; return (prev); }
    inline reverse_iterator	operator-- (int) { reverse_iterator prev (*this); ++ m_i; return (prev); }
    inline reverse_iterator&	operator+= (size_t n) { m_i -= n; return (*this); }
    inline reverse_iterator&	operator-= (size_t n) { m_i += n; return (*this); }
    inline reverse_iterator	operator+ (size_t n) const { return (reverse_iterator (m_i - n)); }
    inline reverse_iterator	operator- (size_t n) const { return (reverse_iterator (m_i + n)); }
    inline reference		operator[] (uoff_t n) const { return (*(*this + n)); }
    inline difference_type	operator- (const reverse_iterator& i) const { return (distance (m_i, i.m_i)); }
protected:
    Iterator		m_i;
};

//----------------------------------------------------------------------

/// \class insert_iterator uiterator.h ustl.h
/// \ingroup IteratorAdaptors
/// \brief Calls insert on bound container for each assignment.
///
template <class Container>
class insert_iterator {
public:
    typedef typename Container::value_type	value_type;
    typedef typename Container::difference_type	difference_type;
    typedef typename Container::pointer		pointer;
    typedef typename Container::reference	reference;
    typedef typename Container::iterator	iterator;
public:
    explicit			insert_iterator (Container& ctr, iterator ip) : m_rCtr (ctr), m_ip (ip) {}
    inline insert_iterator&	operator= (typename Container::const_reference v)
    				    { m_ip = m_rCtr.insert (m_ip, v); return (*this); }
    inline insert_iterator&	operator* (void)  { return (*this); }
    inline insert_iterator&	operator++ (void) { ++ m_ip; return (*this); }
    inline insert_iterator	operator++ (int)  { insert_iterator prev (*this); ++ m_ip; return (*this); }
protected:
    Container&			m_rCtr;
    iterator			m_ip;
};

/// Returns the insert_iterator for \p ctr.
template <class Container>
inline insert_iterator<Container> inserter (Container& ctr, typename Container::iterator ip)
{
    return (insert_iterator<Container> (ctr, ip));
}

//----------------------------------------------------------------------

/// \class back_insert_iterator uiterator.h ustl.h
/// \ingroup IteratorAdaptors
/// \brief Calls push_back on bound container for each assignment.
///
template <class Container>
class back_insert_iterator {
public:
    typedef typename Container::value_type	value_type;
    typedef typename Container::difference_type	difference_type;
    typedef typename Container::pointer		pointer;
    typedef typename Container::reference	reference;
public:
    explicit				back_insert_iterator (Container& ctr) : m_rCtr (ctr) {}
    inline back_insert_iterator&	operator= (typename Container::const_reference v)
    					    { m_rCtr.push_back (v); return (*this); }
    inline back_insert_iterator&	operator* (void)  { return (*this); }
    inline back_insert_iterator&	operator++ (void) { return (*this); }
    inline back_insert_iterator		operator++ (int)  { return (*this); }
protected:
    Container&		m_rCtr;
};

/// Returns the back_insert_iterator for \p ctr.
template <class Container>
inline back_insert_iterator<Container> back_inserter (Container& ctr)
{
    return (back_insert_iterator<Container> (ctr));
}

//----------------------------------------------------------------------

/// \class index_iterate uiterator.h ustl.h
/// \ingroup IteratorAdaptors
///
/// \brief Allows iteration through an index container.
///
/// Converts an iterator into a container of uoff_t indexes to an
/// iterator of iterators into another container.
///
template <typename RandomAccessIterator, typename IndexIterator>
class index_iterate {
public:
    typedef RandomAccessIterator	value_type;
    typedef ptrdiff_t			difference_type;
    typedef RandomAccessIterator*	pointer;
    typedef RandomAccessIterator	reference;
public:
    				index_iterate (void) : m_Base(), m_i() {}
				index_iterate (RandomAccessIterator ibase, IndexIterator iindex) : m_Base (ibase), m_i (iindex) {}
    inline bool			operator== (const index_iterate& i) const { return (m_i == i.m_i); }
    inline bool			operator< (const index_iterate& i) const { return (m_i < i.m_i); }
    inline bool			operator== (const RandomAccessIterator& i) const { return (m_Base == i); }
    inline bool			operator< (const RandomAccessIterator& i) const { return (m_Base < i); }
    inline IndexIterator	base (void) const { return (m_i); }
    inline reference		operator* (void) const { return (advance(m_Base, *m_i)); }
    inline pointer		operator-> (void) const { return (&(operator*())); }
    inline index_iterate&	operator++ (void) { ++ m_i; return (*this); }
    inline index_iterate&	operator-- (void) { -- m_i; return (*this); }
    inline index_iterate	operator++ (int) { index_iterate prev (*this); ++ m_i; return (prev); }
    inline index_iterate	operator-- (int) { index_iterate prev (*this); -- m_i; return (prev); }
    inline index_iterate&	operator+= (size_t n) { m_i += n; return (*this); }
    inline index_iterate&	operator-= (size_t n) { m_i -= n; return (*this); }
    inline index_iterate	operator+ (size_t n) const { return (index_iterate (m_Base, m_i + n)); }
    inline index_iterate	operator- (size_t n) const { return (index_iterate (m_Base, m_i - n)); }
    inline reference		operator[] (uoff_t n) const { return (*(*this + n)); }
    inline difference_type	operator- (const index_iterate& i) const { return (distance (m_i, i.m_i)); }
private:
    RandomAccessIterator	m_Base;
    IndexIterator		m_i;
};

/// Returns an index_iterate for \p ibase over \p iindex.
template <typename RandomAccessIterator, typename IndexIterator>
inline index_iterate<RandomAccessIterator, IndexIterator> index_iterator (RandomAccessIterator ibase, IndexIterator iindex)
{
    return (index_iterate<RandomAccessIterator, IndexIterator> (ibase, iindex));
}

/// Converts the indexes in \p xc to iterators in \p ic of base \p ibase.
template <typename IndexContainer, typename IteratorContainer>
inline void indexv_to_iteratorv (typename IteratorContainer::value_type ibase, const IndexContainer& xc, IteratorContainer& ic)
{
    ic.resize (xc.size());
    copy_n (index_iterator (ibase, xc.begin()), xc.size(), ic.begin());
}

//----------------------------------------------------------------------

/// Converts the given const_iterator into an iterator.
///
template <typename Container>
inline typename Container::iterator unconst (typename Container::const_iterator i, Container& ctr)
{
    const Container& cctr = ctr;
    return (ctr.begin() + (i - cctr.begin()));
}

#ifndef DOXYGEN_SHOULD_SKIP_THIS

#define IBYI(Iter1, Iter2, Ctr1, Ctr2)	\
template <typename Container1, typename Container2>	\
inline typename Container2::Iter2 ibyi (typename Container1::Iter1 idx, Ctr1& ctr1, Ctr2& ctr2)	\
{							\
    assert (ctr1.size() == ctr2.size());		\
    return (ctr2.begin() + (idx - ctr1.begin()));	\
}

IBYI(const_iterator, const_iterator, const Container1, const Container2)
IBYI(iterator, iterator, Container1, Container2)
IBYI(const_iterator, iterator, const Container1, Container2)
IBYI(iterator, const_iterator, Container1, const Container2)

#else // DOXYGEN

#error This declaration is for doxygen only; it is not compiled.

/// Converts a const_iterator in one container into a const_iterator in another container.
template <typename Container1, typename Container2>
inline typename Container2::iterator ibyi (typename Container1::iterator idx, Container1& ctr1, Container2& ctr2) {}

#endif // DOXYGEN

//----------------------------------------------------------------------

} // namespace ustl

#endif

