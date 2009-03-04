// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// ustack.h
//

#ifndef USTACK_H_5242F5635322B2EC44A9AEE73022C6E9
#define USTACK_H_5242F5635322B2EC44A9AEE73022C6E9

namespace ustl {

/// \class stack ustack.h ustl.h
/// \ingroup Sequences
///
/// \brief Stack adapter to uSTL containers.
///
template <typename Sequence>
class stack {
public:
    typedef typename Sequence::value_type	value_type;
    typedef typename Sequence::size_type	size_type;
    typedef typename Sequence::difference_type	difference_type;
    typedef typename Sequence::reference	reference;
    typedef typename Sequence::const_reference	const_reference;
    typedef typename Sequence::pointer		pointer;
public:
    inline			stack (void)			: m_Storage () { }
    explicit inline		stack (const Sequence& s)	: m_Storage (s) { }
    inline bool			empty (void) const		{ return (m_Storage.empty()); }
    inline size_type		size (void) const		{ return (m_Storage.size()); }
    inline reference		top (void)			{ return (m_Storage.back()); }
    inline const_reference	top (void) const		{ return (m_Storage.back()); }
    inline void			push (const value_type& v)	{ m_Storage.push_back (v); }
    inline void			pop (void)			{ m_Storage.pop_back(); }
    inline bool			operator== (const stack& s)	{ return (m_Storage == s.m_Storage); }
    inline bool			operator< (const stack& s)	{ return (m_Storage.size() < s.m_Storage.size()); }
private:
    Sequence			m_Storage;	///< Where the data actually is.
};

} // namespace ustl

#endif

