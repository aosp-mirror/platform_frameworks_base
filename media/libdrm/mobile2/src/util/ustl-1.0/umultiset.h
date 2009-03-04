// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// umultiset.h
//

#ifndef UMULTISET_H_446AEDBB7F61C6994DC228C25D5FA3A1
#define UMULTISET_H_446AEDBB7F61C6994DC228C25D5FA3A1

#include "uassert.h"
#include "ualgo.h"
#include "uvector.h"

namespace ustl {

/// \class multiset umultiset.h ustl.h
/// \ingroup AssociativeContainers
///
/// \brief Multiple sorted container.
/// Unlike set, it may contain multiple copies of each element.
///
template <typename T>
class multiset : public vector<T> {
public:
    typedef const multiset<T>&				rcself_t;
    typedef vector<T>					base_class;
    typedef typename base_class::value_type		value_type;
    typedef typename base_class::size_type		size_type;
    typedef typename base_class::pointer		pointer;
    typedef typename base_class::const_pointer		const_pointer;
    typedef typename base_class::reference		reference;
    typedef typename base_class::const_reference	const_reference;
    typedef typename base_class::const_iterator		const_iterator;
    typedef typename base_class::iterator		iterator;
    typedef typename base_class::reverse_iterator	reverse_iterator;
    typedef typename base_class::const_reverse_iterator	const_reverse_iterator;
public:
    inline			multiset (void)		: vector<T> () {}
    explicit inline		multiset (size_type n)	: vector<T> (n) {}
    inline			multiset (rcself_t v)	: vector<T> (v) {} 
    inline			multiset (const_iterator i1, const_iterator i2) : vector<T> () { insert (i1, i2); }
    inline rcself_t		operator= (rcself_t v)	{ base_class::operator= (v); return (*this); }
    inline size_type		size (void) const	{ return (base_class::size()); }
    inline iterator		begin (void)		{ return (base_class::begin()); }
    inline const_iterator	begin (void) const	{ return (base_class::begin()); }
    inline iterator		end (void)		{ return (base_class::end()); }
    inline const_iterator	end (void) const	{ return (base_class::end()); }
    inline void			assign (const_iterator i1, const_iterator i2);
    size_type			count (const_reference v) const;
    inline void			push_back (const_reference v)	{ insert (v); }
    inline iterator		insert (const_reference v);
    void			insert (const_iterator i1, const_iterator i2);
    void			erase (const_reference v);
    inline iterator		erase (iterator ep)	{ return (base_class::erase (ep)); }
    inline iterator		erase (iterator ep1, iterator ep2) { return (base_class::erase (ep1, ep2)); }
    inline void			clear (void)		{ base_class::clear(); }
};

/// Copies contents of range [i1,i2)
template <typename T>
inline void multiset<T>::assign (const_iterator i1, const_iterator i2)
{
    base_class::clear();
    insert (i1, i2);
}

/// Returns the number of elements of value \p v.
template <typename T>
typename multiset<T>::size_type multiset<T>::count (const_reference v) const
{
    const pair<const_iterator,const_iterator> fr = equal_range (begin(), end(), v);
    return (distance (fr.first, fr.second));
}

/// Inserts \p v.
template <typename T>
inline typename multiset<T>::iterator multiset<T>::insert (const_reference v)
{
    iterator ip = upper_bound (begin(), end(), v);
    return (base_class::insert (ip, v));
}

/// Inserts all elements from range [i1,i2).
template <typename T>
void multiset<T>::insert (const_iterator i1, const_iterator i2)
{
    assert (i1 <= i2);
    reserve (size() + distance (i1, i2));
    for (; i1 < i2; ++i1)
	push_back (*i1);
}

/// Erases all elements with value \p v.
template <typename T>
void multiset<T>::erase (const_reference v)
{
    pair<iterator,iterator> epr = equal_range (begin(), end(), v);
    erase (epr.first, epr.second);
}

} // namespace ustl

#endif

