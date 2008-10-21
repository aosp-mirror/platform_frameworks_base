// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// \file uctralgo.h
//
// \brief Implementation of STL algorithms with container shortcuts.
//
// The function prototypes are copied
// exactly from the SGI version of STL documentation along with comments about
// their use. The code is NOT the same, though the functionality usually is.
//

#ifndef UCTRALGO_H_0D1AEDFA74B09791489FE25B1EC644B0
#define UCTRALGO_H_0D1AEDFA74B09791489FE25B1EC644B0

#include "uassert.h"

namespace ustl {

/// Copy copies elements from the range [first, last) to the range
/// [result, result + (last - first)). That is, it performs the assignments
/// *result = *first, *(result + 1) = *(first + 1), and so on. [1] Generally,
/// for every integer n from 0 to last - first, copy performs the assignment
/// *(result + n) = *(first + n). Assignments are performed in forward order,
/// i.e. in order of increasing n. 
/// \ingroup MutatingAlgorithms
///
template <typename Container, typename OutputIterator>
inline OutputIterator copy (const Container& ctr, OutputIterator result)
{
    return (copy (ctr.begin(), ctr.end(), result));
}

/// Copy_if copies elements from the range [first, last) to the range
/// [result, result + (last - first)) if pred(*i) returns true.
/// \ingroup MutatingAlgorithms
///
template <typename Container, typename OutputIterator, typename Predicate>
inline OutputIterator copy_if (Container& ctr, OutputIterator result, Predicate pred)
{
    return (copy_if (ctr.begin(), ctr.end(), result, pred));
}

/// For_each applies the function object f to each element in the range
/// [first, last); f's return value, if any, is ignored. Applications are
/// performed in forward order, i.e. from first to last. For_each returns
/// the function object after it has been applied to each element.
/// \ingroup MutatingAlgorithms
///
template <typename Container, typename UnaryFunction>
inline UnaryFunction for_each (Container& ctr, UnaryFunction f)
{
    return (for_each (ctr.begin(), ctr.end(), f));
}

/// For_each applies the function object f to each element in the range
/// [first, last); f's return value, if any, is ignored. Applications are
/// performed in forward order, i.e. from first to last. For_each returns
/// the function object after it has been applied to each element.
/// \ingroup MutatingAlgorithms
///
template <typename Container, typename UnaryFunction>
inline UnaryFunction for_each (const Container& ctr, UnaryFunction f)
{
    return (for_each (ctr.begin(), ctr.end(), f));
}

/// Returns the first iterator i in the range [first, last) such that
/// *i == value. Returns last if no such iterator exists. 
/// \ingroup SearchingAlgorithms
///
template <typename Container, typename EqualityComparable>
inline typename Container::const_iterator find (const Container& ctr, const EqualityComparable& value)
{
    return (find (ctr.begin(), ctr.end(), value));
}
template <typename Container, typename EqualityComparable>
inline typename Container::iterator find (Container& ctr, const EqualityComparable& value)
{
    return (find (ctr.begin(), ctr.end(), value));
}

/// Returns the first iterator i in the range [first, last) such that
/// pred(*i) is true. Returns last if no such iterator exists.
/// \ingroup SearchingAlgorithms
///
template <typename Container, typename Predicate>
inline typename Container::const_iterator find_if (const Container& ctr, Predicate pred)
{
    return (find_if (ctr.begin(), ctr.end(), pred));
}
template <typename Container, typename Predicate>
inline typename Container::iterator find_if (Container& ctr, Predicate pred)
{
    return (find_if (ctr.begin(), ctr.end(), pred));
}

/// Count finds the number of elements in [first, last) that are equal
/// to value. More precisely, the first version of count returns the
/// number of iterators i in [first, last) such that *i == value.
/// \ingroup ConditionAlgorithms
///
template <typename Container, typename EqualityComparable>
inline size_t count (const Container& ctr, const EqualityComparable& value)
{
    return (count (ctr.begin(), ctr.end(), value));
}

/// Count_if finds the number of elements in [first, last) that satisfy the
/// predicate pred. More precisely, the first version of count_if returns the
/// number of iterators i in [first, last) such that pred(*i) is true.
/// \ingroup ConditionAlgorithms
///
template <typename Container, typename Predicate>
inline size_t count_if (const Container& ctr, Predicate pred)
{
    return (count_if (ctr.begin(), ctr.end(), pred));
}

/// The first version of transform performs the operation op(*i) for each
/// iterator i in the range [first, last), and assigns the result of that
/// operation to *o, where o is the corresponding output iterator. That is,
/// for each n such that 0 <= n < last - first, it performs the assignment
/// *(result + n) = op(*(first + n)).
/// The return value is result + (last - first).
/// \ingroup MutatingAlgorithms
///
template <typename Container, typename UnaryFunction>
inline void transform (Container& ctr, UnaryFunction op)
{
    transform (ctr.begin(), ctr.end(), ctr.begin(), op);
}

/// The first version of transform performs the operation op(*i) for each
/// iterator i in the range [first, last), and assigns the result of that
/// operation to *o, where o is the corresponding output iterator. That is,
/// for each n such that 0 <= n < last - first, it performs the assignment
/// *(result + n) = op(*(first + n)).
/// The return value is result + (last - first).
/// \ingroup MutatingAlgorithms
///
template <typename Container, typename OutputIterator, typename UnaryFunction>
inline OutputIterator transform (Container& ctr, OutputIterator result, UnaryFunction op)
{
    return (transform (ctr.begin(), ctr.end(), result, op));
}

/// The second version of transform is very similar, except that it uses a
/// Binary Function instead of a Unary Function: it performs the operation
/// op(*i1, *i2) for each iterator i1 in the range [first1, last1) and assigns
/// the result to *o, where i2 is the corresponding iterator in the second
/// input range and where o is the corresponding output iterator. That is,
/// for each n such that 0 <= n < last1 - first1, it performs the assignment
/// *(result + n) = op(*(first1 + n), *(first2 + n).
/// The return value is result + (last1 - first1).
/// \ingroup MutatingAlgorithms
///
template <typename Container, typename InputIterator, typename OutputIterator, typename BinaryFunction>
inline OutputIterator transform (Container& ctr, InputIterator first, OutputIterator result, BinaryFunction op)
{
    return (transform (ctr.begin(), ctr.end(), first, result, op));
}

/// Replace replaces every element in the range [first, last) equal to
/// old_value with new_value. That is: for every iterator i,
/// if *i == old_value then it performs the assignment *i = new_value.
/// \ingroup MutatingAlgorithms
///
template <typename Container, typename T>
inline void replace (Container& ctr, const T& old_value, const T& new_value)
{
    replace (ctr.begin(), ctr.end(), old_value, new_value);
}

/// Replace_if replaces every element in the range [first, last) for which
/// pred returns true with new_value. That is: for every iterator i, if
/// pred(*i) is true then it performs the assignment *i = new_value.
/// \ingroup MutatingAlgorithms
///
template <typename Container, typename Predicate, typename T>
inline void replace_if (Container& ctr, Predicate pred, const T& new_value)
{
    replace_if (ctr.begin(), ctr.end(), pred, new_value);
}

/// Replace_copy copies elements from the range [first, last) to the range
/// [result, result + (last-first)), except that any element equal to old_value
/// is not copied; new_value is copied instead. More precisely, for every
/// integer n such that 0 <= n < last-first, replace_copy performs the
/// assignment *(result+n) = new_value if *(first+n) == old_value, and
/// *(result+n) = *(first+n) otherwise.
/// \ingroup MutatingAlgorithms
///
template <typename Container, typename OutputIterator, typename T>
inline OutputIterator replace_copy (const Container& ctr, OutputIterator result, const T& old_value, const T& new_value)
{
    return (replace_copy (ctr.begin(), ctr.end(), result, old_value, new_value));
}

/// Replace_copy_if copies elements from the range [first, last) to the range
/// [result, result + (last-first)), except that any element for which pred is
/// true is not copied; new_value is copied instead. More precisely, for every
/// integer n such that 0 <= n < last-first, replace_copy_if performs the
/// assignment *(result+n) = new_value if pred(*(first+n)),
/// and *(result+n) = *(first+n) otherwise.
/// \ingroup MutatingAlgorithms
///
template <typename Container, typename OutputIterator, typename Predicate, typename T>
inline OutputIterator replace_copy_if (const Container& ctr, OutputIterator result, Predicate pred, const T& new_value) 
{
    return (replace_copy_if (ctr.begin(), ctr.end(), result, pred, new_value));
}

/// Fill assigns the value value to every element in the range [first, last).
/// That is, for every iterator i in [first, last),
/// it performs the assignment *i = value.
/// \ingroup GeneratorAlgorithms
///
template <typename Container, typename T>
inline void fill (Container& ctr, const T& value)
{
    fill (ctr.begin(), ctr.end(), value);
}

/// Generate assigns the result of invoking gen, a function object that
/// takes no arguments, to each element in the range [first, last).
/// \ingroup GeneratorAlgorithms
///
template <typename Container, typename Generator>
inline void generate (Container& ctr, Generator gen)
{
    generate (ctr.begin(), ctr.end(), gen);
}

/// Randomly permute the elements of the container.
/// \ingroup GeneratorAlgorithms
///
template <typename Container>
inline void random_shuffle (Container& ctr)
{
    random_shuffle (ctr.begin(), ctr.end());
}

/// Remove_copy copies elements that are not equal to value from the range
/// [first, last) to a range beginning at result. The return value is the
/// end of the resulting range. This operation is stable, meaning that the
/// relative order of the elements that are copied is the same as in the
/// range [first, last).
/// \ingroup MutatingAlgorithms
///
template <typename Container, typename OutputIterator, typename T>
inline OutputIterator remove_copy (const Container& ctr, OutputIterator result, const T& value)
{
    return (remove_copy (ctr.begin(), ctr.end(), result, value));
}

/// Remove_copy_if copies elements from the range [first, last) to a range
/// beginning at result, except that elements for which pred is true are not
/// copied. The return value is the end of the resulting range. This operation
/// is stable, meaning that the relative order of the elements that are copied
/// is the same as in the range [first, last).
/// \ingroup MutatingAlgorithms
///
template <typename Container, typename OutputIterator, typename Predicate>
inline OutputIterator remove_copy_if (const Container& ctr, OutputIterator result, Predicate pred)
{
    return (remove_copy_if (ctr.begin(), ctr.end(), result, pred));
}

/// Remove removes from the range [first, last) all elements that are equal to
/// value. That is, remove returns an iterator new_last such that the range
/// [first, new_last) contains no elements equal to value. Remove is stable,
/// meaning that the relative order of elements that are not equal to value is
/// unchanged.
/// \ingroup MutatingAlgorithms
///
template <typename Container, typename T>
inline void remove (Container& ctr, const T& value)
{
    ctr.erase (remove_copy (ctr.begin(), ctr.end(), ctr.begin(), value), ctr.end());
}

/// Remove removes from the range [first, last) all elements that have an iterator
/// in range [rfirst, rlast). The range is assumed to be sorted. That is, remove
/// returns an iterator new_last such that the range [first, new_last) contains
/// no elements whose iterators are in [rfirst, rlast). Remove is stable,
/// meaning that the relative order of elements that are not equal to value is
/// unchanged. This version of the algorithm is a uSTL extension.
/// \ingroup MutatingAlgorithms
///
template <typename Container, typename ForwardIterator>
inline void remove (Container& ctr, ForwardIterator rfirst, ForwardIterator rlast)
{
    ctr.erase (remove_copy (ctr.begin(), ctr.end(), ctr.begin(), rfirst, rlast), ctr.end());
}

/// Remove_if removes from the range [first, last) every element x such that
/// pred(x) is true. That is, remove_if returns an iterator new_last such that
/// the range [first, new_last) contains no elements for which pred is true.
/// The iterators in the range [new_last, last) are all still dereferenceable,
/// but the elements that they point to are unspecified. Remove_if is stable,
/// meaning that the relative order of elements that are not removed is
/// unchanged.
/// \ingroup MutatingAlgorithms
///
template <typename Container, typename Predicate>
inline void remove_if (Container& ctr, Predicate pred)
{
    ctr.erase (remove_copy_if (ctr.begin(), ctr.end(), ctr.begin(), pred), ctr.end());
}

/// Unique_copy copies elements from the range [first, last) to a range
/// beginning with result, except that in a consecutive group of duplicate
/// elements only the first one is copied. The return value is the end of
/// the range to which the elements are copied. This behavior is similar
/// to the Unix filter uniq.
/// \ingroup MutatingAlgorithms
///
template <typename Container, typename OutputIterator>
inline OutputIterator unique_copy (const Container& ctr, OutputIterator result)
{
    return (unique_copy (ctr.begin(), ctr.end(), result));
}

/// Every time a consecutive group of duplicate elements appears in the range
/// [first, last), the algorithm unique removes all but the first element.
/// That is, unique returns an iterator new_last such that the range [first,
/// new_last) contains no two consecutive elements that are duplicates.
/// The iterators in the range [new_last, last) are all still dereferenceable,
/// but the elements that they point to are unspecified. Unique is stable,
/// meaning that the relative order of elements that are not removed is
/// unchanged.
/// \ingroup MutatingAlgorithms
///
template <typename Container>
inline void unique (Container& ctr)
{
    ctr.erase (unique_copy (ctr.begin(), ctr.end(), ctr.begin()), ctr.end());
}

/// Every time a consecutive group of duplicate elements appears in the range
/// [first, last), the algorithm unique removes all but the first element.
/// That is, unique returns an iterator new_last such that the range [first,
/// new_last) contains no two consecutive elements that are duplicates.
/// The iterators in the range [new_last, last) are all still dereferenceable,
/// but the elements that they point to are unspecified. Unique is stable,
/// meaning that the relative order of elements that are not removed is
/// unchanged.
/// \ingroup MutatingAlgorithms
///
template <typename Container, typename BinaryPredicate>
inline void unique (Container& ctr, BinaryPredicate binary_pred)
{
    ctr.erase (unique_copy (ctr.begin(), ctr.end(), ctr.begin(), binary_pred), ctr.end());
}

/// Reverse reverses a range.
/// That is: for every i such that 0 <= i <= (last - first) / 2),
/// it exchanges *(first + i) and *(last - (i + 1)).
/// \ingroup MutatingAlgorithms
///
template <typename Container>
inline void reverse (Container& ctr)
{
    reverse (ctr.begin(), ctr.end());
}

/// Exchanges ranges [first, middle) and [middle, last)
/// \ingroup MutatingAlgorithms
///
template <typename Container>
inline void rotate (Container& ctr, off_t offset)
{
    assert (size_t(offset > 0 ? offset : -offset) < ctr.size());
    if (offset > 0)
	rotate (ctr.begin(), ctr.end() - offset, ctr.end());
    else
	rotate (ctr.begin(), ctr.begin() - offset, ctr.end());
}

/// Returns the furthermost iterator i in [first, last) such that,
/// for every iterator j in [first, i), *j < value
/// Assumes the range is sorted.
/// \ingroup SearchingAlgorithms
///
template <typename Container, typename LessThanComparable>
inline typename Container::const_iterator lower_bound (const Container& ctr, const LessThanComparable& value)
{
    return (lower_bound (ctr.begin(), ctr.end(), value));
}
template <typename Container, typename LessThanComparable>
inline typename Container::iterator lower_bound (Container& ctr, const LessThanComparable& value)
{
    return (lower_bound (ctr.begin(), ctr.end(), value));
}

/// Returns the furthermost iterator i in [first,last) such that for
/// every iterator j in [first,i), value < *j is false.
/// \ingroup SearchingAlgorithms
///
template <typename Container, typename LessThanComparable>
inline typename Container::const_iterator upper_bound (const Container& ctr, const LessThanComparable& value)
{
    return (upper_bound (ctr.begin(), ctr.end(), value));
}
template <typename Container, typename LessThanComparable>
inline typename Container::iterator upper_bound (Container& ctr, const LessThanComparable& value)
{
    return (upper_bound (ctr.begin(), ctr.end(), value));
}

/// Performs a binary search for \p value.
/// Assumes the range is sorted.
/// \ingroup SearchingAlgorithms
///
template <typename Container>
inline typename Container::const_iterator binary_search (const Container& ctr, const typename Container::value_type& value)
{
    return (binary_search (ctr.begin(), ctr.end(), value));
}
template <typename Container>
inline typename Container::iterator binary_search (Container& ctr, const typename Container::value_type& value)
{
    return (binary_search (ctr.begin(), ctr.end(), value));
}

/// Returns pair<lower_bound,upper_bound>
/// \ingroup SearchingAlgorithms
///
template <typename Container, typename LessThanComparable>
inline pair<typename Container::const_iterator,typename Container::const_iterator> equal_range (const Container& ctr, const LessThanComparable& value)
{
    return (equal_range (ctr.begin(), ctr.end(), value));
}
template <typename Container, typename LessThanComparable>
inline pair<typename Container::iterator,typename Container::iterator> equal_range (Container& ctr, const LessThanComparable& value)
{
    return (equal_range (ctr.begin(), ctr.end(), value));
}

/// Sorts the container
/// \ingroup SortingAlgorithms
///
template <typename Container>
inline void sort (Container& ctr)
{
    sort (ctr.begin(), ctr.end());
}

/// Sorts the container
/// \ingroup SortingAlgorithms
///
template <typename Container, typename Compare>
inline void sort (Container& ctr, Compare comp)
{
    sort (ctr.begin(), ctr.end(), comp);
}

/// Sorts the container
/// \ingroup SortingAlgorithms
///
template <typename Container>
inline void stable_sort (Container& ctr)
{
    stable_sort (ctr.begin(), ctr.end());
}

/// Sorts the container
/// \ingroup SortingAlgorithms
///
template <typename Container, typename Compare>
inline void stable_sort (Container& ctr, Compare comp)
{
    stable_sort (ctr.begin(), ctr.end(), comp);
}

} // namespace ustl

#endif

