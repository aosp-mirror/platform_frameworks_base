// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// uqueue.h
//

#ifndef UQUEUE_H_27F01FDB0D59B75277E0E5C41ABC6B5B
#define UQUEUE_H_27F01FDB0D59B75277E0E5C41ABC6B5B

namespace ustl {

/// \class queue uqueue.h ustl.h
/// \ingroup Sequences
///
/// \brief Queue adapter to uSTL containers.
///
/// The most efficient way to use this implementation is to fill the queue
/// and the completely empty it before filling again.
///
template <typename Sequence>
class queue {
public:
    typedef typename Sequence::value_type	value_type;
    typedef typename Sequence::size_type	size_type;
    typedef typename Sequence::difference_type	difference_type;
    typedef typename Sequence::reference	reference;
    typedef typename Sequence::const_reference	const_reference;
    typedef typename Sequence::pointer		pointer;
public:
    inline			queue (void)			: m_Storage (), m_Front (0) { }
    explicit inline		queue (const Sequence& s)	: m_Storage (s), m_Front (0) { }
    inline size_type		size (void) const		{ return (m_Storage.size() - m_Front); }
    inline bool			empty (void) const		{ return (!size()); }
    inline reference		front (void)			{ return (m_Storage [m_Front]); }
    inline const_reference	front (void) const		{ return (m_Storage [m_Front]); }
    inline reference		back (void)			{ return (m_Storage.back()); }
    inline const_reference	back (void) const		{ return (m_Storage.back()); }
    inline void			push (const value_type& v);
    inline void			pop (void);
    inline bool			operator== (const queue& s)	{ return (m_Storage == s.m_Storage && m_Front == s.m_Front); }
    inline bool			operator< (const queue& s)	{ return (size() < s.size()); }
private:
    Sequence			m_Storage;	///< Where the data actually is.
    size_type			m_Front;	///< Index of the element returned by next pop.
};

/// Pushes \p v on the queue.
template <class Sequence>
inline void queue<Sequence>::push (const value_type& v)
{
    if (m_Front) {
	m_Storage.erase (m_Storage.begin(), m_Front);
	m_Front = 0;
    }
    m_Storage.push_back (v);
}

/// Pops the topmost element from the queue.
template <class Sequence>
inline void queue<Sequence>::pop (void)
{
    if (++m_Front >= m_Storage.size())
	m_Storage.resize (m_Front = 0);
}

} // namespace ustl

#endif

