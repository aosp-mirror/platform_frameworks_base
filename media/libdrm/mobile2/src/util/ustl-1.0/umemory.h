// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// umemory.h
//

#ifndef UMEMORY_H_4AB5B0DB5BF09140541409CC47BCD17A
#define UMEMORY_H_4AB5B0DB5BF09140541409CC47BCD17A

#include "unew.h"
#ifdef HAVE_ALLOCA_H
    #include <alloca.h>
#else
    #include <stdlib.h>
#endif
#include "upair.h"
#include "uiterator.h"
#include "ulimits.h"

namespace ustl {

/// \class auto_ptr umemory.h ustl.h
/// \ingroup MemoryManagement
///
/// \brief A smart pointer.
///
/// Calls delete in the destructor; assignment transfers ownership.
/// This class does not work with void pointers due to the absence
/// of the required dereference operator.
///
template <typename T>
class auto_ptr {
public:
    typedef T		value_type;
    typedef T*		pointer;
    typedef T&		reference;
public:
    /// Takes ownership of \p p.
    inline explicit	auto_ptr (pointer p = NULL)	: m_p (p) {}
    /// Takes ownership of pointer in \p p. \p p relinquishes ownership.
    inline		auto_ptr (auto_ptr<T>& p)	: m_p (p.release()) {}
    /// Deletes the owned pointer.
    inline	       ~auto_ptr (void)			{ delete m_p; }
    /// Returns the pointer without relinquishing ownership.
    inline pointer	get (void) const		{ return (m_p); }
    /// Returns the pointer and gives up ownership.
    inline pointer	release (void)			{ pointer rv (m_p); m_p = NULL; return (rv); }
    /// Deletes the pointer and sets it equal to \p p.
    inline void		reset (pointer p)		{ if (p != m_p) { delete m_p; m_p = p; } }
    /// Takes ownership of \p p.
    inline auto_ptr<T>&	operator= (pointer p)		{ reset (p); return (*this); }
    /// Takes ownership of pointer in \p p. \p p relinquishes ownership.
    inline auto_ptr<T>&	operator= (auto_ptr<T>& p)	{ reset (p.release()); return (*this); }
    inline reference	operator* (void) const		{ return (*m_p); }
    inline pointer	operator-> (void) const		{ return (m_p); }
    inline bool		operator== (const pointer p) const	{ return (m_p == p); }
    inline bool		operator== (const auto_ptr<T>& p) const	{ return (m_p == p.m_p); }
    inline bool		operator< (const auto_ptr<T>& p) const	{ return (p.m_p < m_p); }
private:
    pointer		m_p;
};

/// Calls the placement new on \p p.
/// \ingroup RawStorageAlgorithms
///
template <typename T>
inline void construct (T* p)
{
    new (p) T;
}

/// Calls the placement new on \p p.
/// \ingroup RawStorageAlgorithms
///
template <typename ForwardIterator>
inline void construct (ForwardIterator first, ForwardIterator last)
{
    typedef typename iterator_traits<ForwardIterator>::value_type value_type;
    if (!numeric_limits<value_type>::is_integral) {
	while (first < last) {
	    construct (&*first);
	    ++ first;
	}
    }
}

/// Calls the placement new on \p p.
/// \ingroup RawStorageAlgorithms
///
template <typename T>
inline void construct (T* p, const T& value)
{
    new (p) T (value);
}

/// Calls the destructor of \p p without calling delete.
/// \ingroup RawStorageAlgorithms
///
template <typename T>
inline void destroy (T* p) throw()
{
    p->~T();
}

/// Calls the destructor on elements in range [first, last) without calling delete.
/// \ingroup RawStorageAlgorithms
///
template <typename ForwardIterator>
inline void destroy (ForwardIterator first, ForwardIterator last) throw()
{
    typedef typename iterator_traits<ForwardIterator>::value_type value_type;
    if (!numeric_limits<value_type>::is_integral)
	for (; first < last; ++ first)
	    destroy (&*first);
}

/// Casts \p p to the type of the second pointer argument.
template <typename T> inline T* cast_to_type (void* p, const T*) { return ((T*) p); }

/// \brief Creates a temporary buffer pair from \p p and \p n
/// This is intended to be used with alloca to create temporary buffers.
/// The size in the returned pair is set to 0 if the allocation is unsuccessful.
/// \ingroup RawStorageAlgorithms
///
template <typename T>
inline pair<T*, ptrdiff_t> make_temporary_buffer (void* p, size_t n, const T* ptype)
{
    return (make_pair (cast_to_type(p,ptype), ptrdiff_t(p ? n : 0)));
}

#ifdef HAVE_ALLOCA_H
    /// \brief Allocates a temporary buffer, if possible.
    /// \ingroup RawStorageAlgorithms
    #define get_temporary_buffer(size, ptype)	make_temporary_buffer (alloca(size_of_elements(size, ptype)), size, ptype)
    #define return_temporary_buffer(p)
#else
    #define get_temporary_buffer(size, ptype)	make_temporary_buffer (malloc(size_of_elements(size, ptype)), size, ptype)
    #define return_temporary_buffer(p)		if (p) free (p), p = NULL
#endif

/// Copies [first, last) into result by calling copy constructors in result.
/// \ingroup RawStorageAlgorithms
///
template <typename InputIterator, typename ForwardIterator>
ForwardIterator uninitialized_copy (InputIterator first, InputIterator last, ForwardIterator result)
{
    while (first < last) {
	construct (&*result, *first);
	++ result;
	++ first;
    }
    return (result);
}

/// Copies [first, first + n) into result by calling copy constructors in result.
/// \ingroup RawStorageAlgorithms
///
template <typename InputIterator, typename ForwardIterator>
ForwardIterator uninitialized_copy_n (InputIterator first, size_t n, ForwardIterator result)
{
    while (n--) {
	construct (&*result, *first);
	++ result;
	++ first;
    }
    return (result);
}

/// Calls construct on all elements in [first, last) with value \p v.
/// \ingroup RawStorageAlgorithms
///
template <typename ForwardIterator, typename T>
void uninitialized_fill (ForwardIterator first, ForwardIterator last, const T& v)
{
    while (first < last) {
	construct (&*first, v);
	++ first;
    }
}

/// Calls construct on all elements in [first, first + n) with value \p v.
/// \ingroup RawStorageAlgorithms
///
template <typename ForwardIterator, typename T>
ForwardIterator uninitialized_fill_n (ForwardIterator first, size_t n, const T& v)
{
    while (n--) {
	construct (&*first, v);
	++ first;
    }
    return (first);
}
    
} // namespace ustl

#endif

