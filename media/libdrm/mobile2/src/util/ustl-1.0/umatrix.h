// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// umatrix.h
//

#ifndef UMATRIX_H_740EBFEF554E833645E0FD72419A8185
#define UMATRIX_H_740EBFEF554E833645E0FD72419A8185

#include "utuple.h"

namespace ustl {

/// \class matrix umatrix.h ustl.h
/// \ingroup Sequences
///
/// \brief A two-dimensional array of NX*NY elements of type T.
///
template <size_t NX, size_t NY, typename T>
class matrix : public tuple<NX*NY,T> {
public:
    typedef tuple<NX,T>					row_type;
    typedef tuple<NY,T>					column_type;
    typedef tuple<NX*NY,T>				tuple_type;
    typedef typename tuple_type::value_type		value_type;
    typedef typename tuple_type::size_type		size_type;
    typedef typename tuple_type::pointer		pointer;
    typedef typename tuple_type::const_pointer		const_pointer;
    typedef typename tuple_type::reference		reference;
    typedef typename tuple_type::const_reference	const_reference;
    typedef typename tuple_type::iterator		iterator;
    typedef typename tuple_type::const_iterator		const_iterator;
    typedef typename tuple_type::range_t		range_t;
    typedef typename tuple_type::const_range_t		const_range_t;
    typedef typename tuple_type::reverse_iterator	reverse_iterator;
    typedef typename tuple_type::const_reverse_iterator	const_reverse_iterator;
public:
    inline			matrix (void)			{ fill_n (matrix::begin(), NX*NY, T()); }
    inline size_type		columns (void) const		{ return (NX); }
    inline size_type		rows (void) const		{ return (NY); }
    inline const_iterator	at (size_type i) const		{ return (matrix::begin() + i * NX); }
    inline iterator		at (size_type i)		{ return (matrix::begin() + i * NX); }
    inline const_iterator	operator[] (size_type i) const	{ return (at (i)); }
    inline iterator		operator[] (size_type i)	{ return (at (i)); }
    inline row_type		row (size_type r) const		{ return (row_type (at (r))); }
    inline column_type		column (size_type c) const;
    template <typename T2>
    inline const matrix&	operator= (const matrix<NX,NY,T2>& src)	{ tuple_type::operator= (src); return (*this); }
    inline const matrix&	operator= (const matrix<NX,NY,T>& src)	{ tuple_type::operator= (src); return (*this); }
    inline const matrix&	operator+= (const_reference v)		{ tuple_type::operator+= (v); return (*this); }
    inline const matrix&	operator-= (const_reference v)		{ tuple_type::operator-= (v); return (*this); }
    inline const matrix&	operator*= (const_reference v)		{ tuple_type::operator*= (v); return (*this); }
    inline const matrix&	operator/= (const_reference v)		{ tuple_type::operator/= (v); return (*this); }
    inline const matrix		operator+ (const_reference v) const
				    { matrix result (*this); result += v; return (result); }
    inline const matrix		operator- (const_reference v) const
				    { matrix result (*this); result -= v; return (result); }
    inline const matrix		operator* (const_reference v) const
				    { matrix result (*this); result *= v; return (result); }
    inline const matrix		operator/ (const_reference v) const
				    { matrix result (*this); result /= v; return (result); }
};

template <size_t NX, size_t NY, typename T>
inline typename matrix<NX,NY,T>::column_type matrix<NX,NY,T>::column (size_type c) const
{
    column_type result;
    const_iterator src (matrix::begin() + c);
    iterator dest (result.begin());
    for (uoff_t i = 0; i < NY; ++ i, ++ dest, src += NX)
	*dest = *src;
    return (result);
}

} // namespace ustl

#endif

