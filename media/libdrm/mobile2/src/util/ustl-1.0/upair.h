// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
/// \file upair.h
/// \brief Pair-related functionality.

#ifndef UPAIR_H_7DC08F1B7FECF8AE6856D84C3B617A75
#define UPAIR_H_7DC08F1B7FECF8AE6856D84C3B617A75

#include "utypes.h"

namespace ustl {

/// \class pair upair.h ustl.h
/// \ingroup AssociativeContainers
///
/// \brief Container for two values.
///
template <typename T1, typename T2>
class pair {
public:
    typedef T1		first_type;
    typedef T2		second_type;
public:
    /// Default constructor.
    inline pair (void)				: first (T1()), second (T2()) {}
    /// Initializes members with \p a, and \p b.
    inline pair (const T1& a, const T2& b)	: first (a), second (b) {}
    inline pair&	operator= (const pair<T1, T2>& p2) { first = p2.first; second = p2.second; return (*this); }
    template <typename T3, typename T4>
    inline pair&	operator= (const pair<T3, T4>& p2) { first = p2.first; second = p2.second; return (*this); }
public:
    first_type		first;
    second_type		second;
};

/// Compares both values of \p p1 to those of \p p2.
template <typename T1, typename T2>
inline bool operator== (const pair<T1,T2>& p1, const pair<T1,T2>& p2)
{
    return (p1.first == p2.first && p1.second == p2.second);
}

/// Compares both values of \p p1 to those of \p p2.
template <typename T1, typename T2>
bool operator< (const pair<T1,T2>& p1, const pair<T1,T2>& p2)
{
    return (p1.first < p2.first || (p1.first == p2.first && p1.second < p2.second));
}

/// Returns a pair object with (a,b)
template <typename T1, typename T2>
inline pair<T1,T2> make_pair (const T1& a, const T2& b)
{
    return (pair<T1,T2> (a, b));
}

} // namespace ustl

#endif

