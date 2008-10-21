// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// umultimap.h
//

#ifndef UMULTIMAP_H_45743F516E02A87A3FCEA5024052A6F5
#define UMULTIMAP_H_45743F516E02A87A3FCEA5024052A6F5

#include "uassert.h"
#include "ufunction.h"
#include "uvector.h"

namespace ustl {

/// \class multimap umultimap.h ustl.h
/// \ingroup AssociativeContainers
///
/// \brief A sorted associative container that may container multiple entries for each key.
///
template <typename K, typename V>
class multimap : public vector<pair<K,V> > {
public:
    typedef K						key_type;
    typedef V						data_type;
    typedef const K&					const_key_ref;
    typedef const V&					const_data_ref;
    typedef const multimap<K,V>&			rcself_t;
    typedef vector<pair<K,V> >				base_class;
    typedef typename base_class::value_type	value_type;
    typedef typename base_class::size_type	size_type;
    typedef typename base_class::pointer	pointer;
    typedef typename base_class::const_pointer	const_pointer;
    typedef typename base_class::reference	reference;
    typedef typename base_class::const_reference	const_reference;
    typedef typename base_class::const_iterator		const_iterator;
    typedef typename base_class::iterator		iterator;
    typedef typename base_class::reverse_iterator	reverse_iterator;
    typedef typename base_class::const_reverse_iterator	const_reverse_iterator;
    typedef pair<const_iterator,const_iterator>		const_range_t;
    typedef pair<iterator,iterator>			range_t;
public:
    inline			multimap (void)		: vector<pair<K,V> > () {} 
    explicit inline		multimap (size_type n)	: vector<pair<K,V> > (n) {} 
    inline			multimap (rcself_t v)	: vector<pair<K,V> > (v) {} 
    inline			multimap (const_iterator i1, const_iterator i2)	: vector<pair<K,V> > () { insert (i1, i2); } 
    inline rcself_t		operator= (rcself_t v)	{ base_class::operator= (v); return (*this); }
    inline size_type		size (void) const	{ return (base_class::size()); }
    inline iterator		begin (void)		{ return (base_class::begin()); }
    inline const_iterator	begin (void) const	{ return (base_class::begin()); }
    inline iterator		end (void)		{ return (base_class::end()); }
    inline const_iterator	end (void) const	{ return (base_class::end()); }
    inline void			assign (const_iterator i1, const_iterator i2) { clear(); insert (i1, i2); }
    inline size_type		count (const_key_ref k) const		{ return (upper_bound(k) - lower_bound(k)); }
    inline void			push_back (const_reference v)		{ insert (v); }
    inline const_range_t	equal_range (const_key_ref k) const	{ return (make_pair (lower_bound(k), upper_bound(k))); }
    inline range_t		equal_range (const_key_ref k)		{ return (make_pair (const_cast<iterator>(lower_bound(k)), const_cast<iterator>(upper_bound(k)))); }
    const_iterator		lower_bound (const_key_ref k) const;
    const_iterator		upper_bound (const_key_ref k) const;
    inline iterator		insert (const_reference v);
    void			insert (const_iterator i1, const_iterator i2);
    inline void			erase (const_key_ref k)			{ erase (const_cast<iterator>(lower_bound(k)), const_cast<iterator>(upper_bound(k))); }
    inline iterator		erase (iterator ep)			{ return (base_class::erase (ep)); } 
    inline iterator		erase (iterator ep1, iterator ep2)	{ return (base_class::erase (ep1, ep2)); } 
    inline void			clear (void)				{ base_class::clear(); }
};

/// Returns an iterator to the first element with key value \p k.
template <typename K, typename V>
typename multimap<K,V>::const_iterator multimap<K,V>::lower_bound (const_key_ref k) const
{
    const_iterator first (begin()), last (end());
    while (first != last) {
	const_iterator mid = advance (first, distance (first,last) / 2);
	if (mid->first < k)
	    first = advance (mid, 1);
	else
	    last = mid;
    }
    return (first);
}

/// Returns an iterator to the first element with key value \p k.
template <typename K, typename V>
typename multimap<K,V>::const_iterator multimap<K,V>::upper_bound (const_key_ref k) const
{
    const_iterator first (begin()), last (end());
    while (first != last) {
	const_iterator mid = advance (first, distance (first,last) / 2);
	if (k < mid->first)
	    last = mid;
	else
	    first = advance (mid, 1);
    }
    return (last);
}

/// Inserts the pair into the container.
template <typename K, typename V>
inline typename multimap<K,V>::iterator multimap<K,V>::insert (const_reference v)
{
    iterator ip = const_cast<iterator> (upper_bound (v.first));
    return (base_class::insert (ip, v));
}

/// Inserts elements from range [i1,i2) into the container.
template <typename K, typename V>
void multimap<K,V>::insert (const_iterator i1, const_iterator i2)
{
    assert (i1 <= i2);
    reserve (size() + distance (i1, i2));
    for (; i1 != i2; ++i1)
	insert (*i1);
}

} // namespace ustl

#endif

