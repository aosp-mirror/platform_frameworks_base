// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// ualgo.h
//
// Implementation of STL algorithms with custom predicates.
//
// The function prototypes are copied
// exactly from the SGI version of STL documentation along with comments about
// their use. The code is NOT the same, though the functionality usually is.
//

#ifndef UPREDALGO_H_2CB058AE0807A01A2F6A51BA5D5820A5
#define UPREDALGO_H_2CB058AE0807A01A2F6A51BA5D5820A5

namespace ustl {

/// Copy_if copies elements from the range [first, last) to the range
/// [result, result + (last - first)) if pred(*i) returns true.
/// \ingroup MutatingAlgorithms
/// \ingroup PredicateAlgorithms
///
template <typename InputIterator, typename OutputIterator, typename Predicate>
inline OutputIterator copy_if (InputIterator first, InputIterator last, OutputIterator result, Predicate pred)
{
    for (; first != last; ++first) {
	if (pred(*first)) {
	    *result = *first;
	    ++ result;
	}
    }
    return (result);
}

/// Returns the first iterator i in the range [first, last) such that
/// pred(*i) is true. Returns last if no such iterator exists.
/// \ingroup SearchingAlgorithms
/// \ingroup PredicateAlgorithms
///
template <typename InputIterator, typename Predicate>
inline InputIterator find_if (InputIterator first, InputIterator last, Predicate pred)
{
    while (first != last && !pred (*first))
	++ first;
    return (first);
}

/// Returns the first iterator such that p(*i, *(i + 1)) == true.
/// \ingroup SearchingAlgorithms
/// \ingroup PredicateAlgorithms
///
template <typename ForwardIterator, typename BinaryPredicate>
inline ForwardIterator adjacent_find (ForwardIterator first, ForwardIterator last, BinaryPredicate p)
{
    if (first != last)
	for (ForwardIterator prev = first; ++first != last; ++ prev)
	    if (p (*prev, *first))
		return (prev);
    return (last);
}

/// Returns the pointer to the first pair of unequal elements.
/// \ingroup SearchingAlgorithms
/// \ingroup PredicateAlgorithms
///
template <typename InputIterator, typename BinaryPredicate>
inline pair<InputIterator,InputIterator>
mismatch (InputIterator first1, InputIterator last1, InputIterator first2, BinaryPredicate comp)
{
    while (first1 != last1 && comp(*first1, *first2))
	++ first1, ++ first2;
    return (make_pair (first1, first2));
}

/// Returns true if two ranges are equal.
/// This is an extension, present in uSTL and SGI STL.
/// \ingroup ConditionAlgorithms
/// \ingroup PredicateAlgorithms
///
template <typename InputIterator, typename BinaryPredicate>
inline bool equal (InputIterator first1, InputIterator last1, InputIterator first2, BinaryPredicate comp)
{
    return (mismatch (first1, last1, first2, comp).first == last1);
}

/// Count_if finds the number of elements in [first, last) that satisfy the
/// predicate pred. More precisely, the first version of count_if returns the
/// number of iterators i in [first, last) such that pred(*i) is true.
/// \ingroup ConditionAlgorithms
/// \ingroup PredicateAlgorithms
///
template <typename InputIterator, typename Predicate>
inline size_t count_if (InputIterator first, InputIterator last, Predicate pred)
{
    size_t total = 0;
    for (; first != last; ++first)
	if (pred (*first))
	    ++ total;
    return (total);
}

/// Replace_if replaces every element in the range [first, last) for which
/// pred returns true with new_value. That is: for every iterator i, if
/// pred(*i) is true then it performs the assignment *i = new_value.
/// \ingroup MutatingAlgorithms
/// \ingroup PredicateAlgorithms
///
template <typename ForwardIterator, typename Predicate, typename T>
inline void replace_if (ForwardIterator first, ForwardIterator last, Predicate pred, const T& new_value)
{
    for (; first != last; ++first)
	if (pred (*first))
	    *first = new_value;
}

/// Replace_copy_if copies elements from the range [first, last) to the range
/// [result, result + (last-first)), except that any element for which pred is
/// true is not copied; new_value is copied instead. More precisely, for every
/// integer n such that 0 <= n < last-first, replace_copy_if performs the
/// assignment *(result+n) = new_value if pred(*(first+n)),
/// and *(result+n) = *(first+n) otherwise.
/// \ingroup MutatingAlgorithms
/// \ingroup PredicateAlgorithms
///
template <typename InputIterator, typename OutputIterator, typename Predicate, typename T>
inline OutputIterator replace_copy_if (InputIterator first, InputIterator last, OutputIterator result, Predicate pred, const T& new_value) 
{
    for (; first != last; ++result, ++first)
        *result = pred(*first) ? new_value : *first;
}

/// Remove_copy_if copies elements from the range [first, last) to a range
/// beginning at result, except that elements for which pred is true are not
/// copied. The return value is the end of the resulting range. This operation
/// is stable, meaning that the relative order of the elements that are copied
/// is the same as in the range [first, last).
/// \ingroup MutatingAlgorithms
/// \ingroup PredicateAlgorithms
///
template <typename InputIterator, typename OutputIterator, typename Predicate>
inline OutputIterator remove_copy_if (InputIterator first, InputIterator last, OutputIterator result, Predicate pred)
{
    for (; first != last; ++first)
	if (pred (*first))
	    *result++ = *first;
    return (result);
}

/// Remove_if removes from the range [first, last) every element x such that
/// pred(x) is true. That is, remove_if returns an iterator new_last such that
/// the range [first, new_last) contains no elements for which pred is true.
/// The iterators in the range [new_last, last) are all still dereferenceable,
/// but the elements that they point to are unspecified. Remove_if is stable,
/// meaning that the relative order of elements that are not removed is
/// unchanged.
/// \ingroup MutatingAlgorithms
/// \ingroup PredicateAlgorithms
///
template <typename ForwardIterator, typename Predicate>
inline ForwardIterator remove_if (ForwardIterator first, ForwardIterator last, Predicate pred)
{
    return (remove_copy_if (first, last, first, pred));
}

/// The reason there are two different versions of unique_copy is that there
/// are two different definitions of what it means for a consecutive group of
/// elements to be duplicates. In the first version, the test is simple
/// equality: the elements in a range [f, l) are duplicates if, for every
/// iterator i in the range, either i == f or else *i == *(i-1). In the second,
/// the test is an arbitrary Binary Predicate binary_pred: the elements in
/// [f, l) are duplicates if, for every iterator i in the range, either
/// i == f or else binary_pred(*i, *(i-1)) is true.
/// \ingroup MutatingAlgorithms
/// \ingroup PredicateAlgorithms
///
template <typename InputIterator, typename OutputIterator, typename BinaryPredicate>
OutputIterator unique_copy (InputIterator first, InputIterator last, OutputIterator result, BinaryPredicate binary_pred)
{
    if (first != last) {
	*result = *first;
	while (++first != last)
	    if (!binary_pred (*first, *result))
		*++result = *first;
	++ result;
    }
    return (result);
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
/// \ingroup PredicateAlgorithms
///
template <typename ForwardIterator, typename BinaryPredicate>
inline ForwardIterator unique (ForwardIterator first, ForwardIterator last, BinaryPredicate binary_pred)
{
    return (unique_copy (first, last, first, binary_pred));
}

/// Returns the furthermost iterator i in [first, last) such that,
/// for every iterator j in [first, i), comp(*j, value) is true.
/// Assumes the range is sorted.
/// \ingroup SearchingAlgorithms
/// \ingroup PredicateAlgorithms
///
template <typename ForwardIterator, typename T, typename StrictWeakOrdering>
ForwardIterator lower_bound (ForwardIterator first, ForwardIterator last, const T& value, StrictWeakOrdering comp)
{
    ForwardIterator mid;
    while (first != last) {
	mid = advance (first, distance (first,last) / 2);
	if (comp (*mid, value))
	    first = mid + 1;
	else
	    last = mid;
    }
    return (first);
}

/// Performs a binary search inside the sorted range.
/// \ingroup SearchingAlgorithms
/// \ingroup PredicateAlgorithms
///
template <typename ForwardIterator, typename T, typename StrictWeakOrdering>
inline ForwardIterator binary_search (ForwardIterator first, ForwardIterator last, const T& value, StrictWeakOrdering comp)
{
    ForwardIterator found = lower_bound (first, last, value, comp);
    return ((found == last || comp(value, *found)) ? last : found);
}

/// Returns the furthermost iterator i in [first,last) such that for
/// every iterator j in [first,i), comp(value,*j) is false.
/// \ingroup SearchingAlgorithms
/// \ingroup PredicateAlgorithms
///
template <typename ForwardIterator, typename T, typename StrictWeakOrdering>
ForwardIterator upper_bound (ForwardIterator first, ForwardIterator last, const T& value, StrictWeakOrdering comp)
{
    ForwardIterator mid;
    while (first != last) {
	mid = advance (first, distance (first,last) / 2);
	if (comp (value, *mid))
	    last = mid;
	else
	    first = mid + 1;
    }
    return (last);
}

/// Returns pair<lower_bound,upper_bound>
/// \ingroup SearchingAlgorithms
/// \ingroup PredicateAlgorithms
///
template <typename ForwardIterator, typename T, typename StrictWeakOrdering>
inline pair<ForwardIterator,ForwardIterator> equal_range (ForwardIterator first, ForwardIterator last, const T& value, StrictWeakOrdering comp)
{
    pair<ForwardIterator,ForwardIterator> rv;
    rv.second = rv.first = lower_bound (first, last, value, comp);
    while (rv.second != last && !comp(value, *(rv.second)))
	++ rv.second;
    return (rv);
}

/// \brief Puts \p nth element into its sorted position.
/// In this implementation, the entire array is sorted. The performance difference is
/// so small and the function use is so rare, there is no need to have code for it.
/// \ingroup SortingAlgorithms
/// \ingroup SearchingAlgorithms
/// \ingroup PredicateAlgorithms
///
template <typename RandomAccessIterator, typename Compare>
inline void nth_element (RandomAccessIterator first, RandomAccessIterator, RandomAccessIterator last, Compare comp)
{
    sort (first, last, comp);
}

/// \brief Searches for the first subsequence [first2,last2) in [first1,last1)
/// \ingroup SearchingAlgorithms
/// \ingroup PredicateAlgorithms
template <typename ForwardIterator1, typename ForwardIterator2, typename BinaryPredicate>
ForwardIterator1 search (ForwardIterator1 first1, ForwardIterator1 last1, ForwardIterator2 first2, ForwardIterator2 last2, BinaryPredicate comp)
{
    const ForwardIterator1 slast = last1 - distance(first2, last2) + 1;
    for (; first1 < slast; ++first1) {
	ForwardIterator2 i = first2;
	ForwardIterator1 j = first1;
	for (; i != last2 && comp(*j, *i); ++i, ++j);
	if (i == last2)
	    return (first1);
    }
    return (last1);
}

/// \brief Searches for the last subsequence [first2,last2) in [first1,last1)
/// \ingroup SearchingAlgorithms
/// \ingroup PredicateAlgorithms
template <typename ForwardIterator1, typename ForwardIterator2, typename BinaryPredicate>
ForwardIterator1 find_end (ForwardIterator1 first1, ForwardIterator1 last1, ForwardIterator2 first2, ForwardIterator2 last2, BinaryPredicate comp)
{
    ForwardIterator1 s = last1 - distance(first2, last2);
    for (; first1 < s; --s) {
	ForwardIterator2 i = first2, j = s;
	for (; i != last2 && comp(*j, *i); ++i, ++j);
	if (i == last2)
	    return (s);
    }
    return (last1);
}

/// \brief Searches for the first occurence of \p count \p values in [first, last)
/// \ingroup SearchingAlgorithms
/// \ingroup PredicateAlgorithms
template <typename Iterator, typename T, typename BinaryPredicate>
Iterator search_n (Iterator first, Iterator last, size_t count, const T& value, BinaryPredicate comp)
{
    size_t n = 0;
    for (; first != last; ++first) {
	if (!comp (*first, value))
	    n = 0;
	else if (++n == count)
	    return (first - --n);
    }
    return (last);
}

/// \brief Searches [first1,last1) for the first occurrence of an element from [first2,last2)
/// \ingroup SearchingAlgorithms
/// \ingroup PredicateAlgorithms
template <typename InputIterator, typename ForwardIterator, typename BinaryPredicate>
InputIterator find_first_of (InputIterator first1, InputIterator last1, ForwardIterator first2, ForwardIterator last2, BinaryPredicate comp)
{
    for (; first1 != last1; ++first1)
	for (ForwardIterator i = first2; i != last2; ++i)
	    if (comp (*first1, *i))
		return (first1);
    return (first1);
}

/// \brief Returns true if [first2,last2) is a subset of [first1,last1)
/// \ingroup ConditionAlgorithms
/// \ingroup SetAlgorithms
/// \ingroup PredicateAlgorithms
template <typename InputIterator1, typename InputIterator2, typename StrictWeakOrdering>
bool includes (InputIterator1 first1, InputIterator1 last1, InputIterator2 first2, InputIterator2 last2, StrictWeakOrdering comp)
{
    for (; (first1 != last1) & (first2 != last2); ++first1) {
	if (comp (*first2, *first1))
	    return (false);
	first2 += !comp (*first1, *first2);
    }
    return (first2 == last2);
}

/// \brief Merges [first1,last1) with [first2,last2)
///
/// Result will contain every element that is in either set. If duplicate
/// elements are present, max(n,m) is placed in the result.
///
/// \ingroup SetAlgorithms
/// \ingroup PredicateAlgorithms
template <typename InputIterator1, typename InputIterator2, typename OutputIterator, typename StrictWeakOrdering>
OutputIterator set_union (InputIterator1 first1, InputIterator1 last1, InputIterator2 first2, InputIterator2 last2, OutputIterator result, StrictWeakOrdering comp)
{
    for (; (first1 != last1) & (first2 != last2); ++result) {
	if (comp (*first2, *first1))
	    *result = *first2++;
	else {
	    first2 += !comp (*first1, *first2);
	    *result = *first1++;
	}
    }
    return (copy (first2, last2, copy (first1, last1, result)));
}

/// \brief Creates a set containing elements shared by the given ranges.
/// \ingroup SetAlgorithms
/// \ingroup PredicateAlgorithms
template <typename InputIterator1, typename InputIterator2, typename OutputIterator, typename StrictWeakOrdering>
OutputIterator set_intersection (InputIterator1 first1, InputIterator1 last1, InputIterator2 first2, InputIterator2 last2, OutputIterator result, StrictWeakOrdering comp)
{
    while ((first1 != last1) & (first2 != last2)) {
	bool b1ge2 = !comp (*first1, *first2), b2ge1 = !comp (*first2, *first1);
	if (b1ge2 & b2ge1)
	    *result++ = *first1;
	first1 += b2ge1;
	first2 += b1ge2;
    }
    return (result);
}

/// \brief Removes from [first1,last1) elements present in [first2,last2)
/// \ingroup SetAlgorithms
/// \ingroup PredicateAlgorithms
template <typename InputIterator1, typename InputIterator2, typename OutputIterator, typename StrictWeakOrdering>
OutputIterator set_difference (InputIterator1 first1, InputIterator1 last1, InputIterator2 first2, InputIterator2 last2, OutputIterator result, StrictWeakOrdering comp)
{
    while ((first1 != last1) & (first2 != last2)) {
	bool b1ge2 = !comp (*first1, *first2), b2ge1 = !comp (*first2, *first1);
	if (!b1ge2)
	    *result++ = *first1;
	first1 += b2ge1;
	first2 += b1ge2;
    }
    return (copy (first1, last1, result));
}

/// \brief Performs union of sets A-B and B-A.
/// \ingroup SetAlgorithms
/// \ingroup PredicateAlgorithms
template <typename InputIterator1, typename InputIterator2, typename OutputIterator, typename StrictWeakOrdering>
OutputIterator set_symmetric_difference (InputIterator1 first1, InputIterator1 last1, InputIterator2 first2, InputIterator2 last2, OutputIterator result, StrictWeakOrdering comp)
{
    while ((first1 != last1) & (first2 != last2)) {
	bool b1l2 = comp (*first1, *first2), b2l1 = comp (*first2, *first1);
	if (b1l2)
	    *result++ = *first1;
	else if (b2l1)
	    *result++ = *first2;
	first1 += !b2l1;
	first2 += !b1l2;
    }
    return (copy (first2, last2, copy (first1, last1, result)));
}

/// \brief Returns true if the given range is sorted.
/// \ingroup ConditionAlgorithms
/// \ingroup PredicateAlgorithms
template <typename ForwardIterator, typename StrictWeakOrdering>
bool is_sorted (ForwardIterator first, ForwardIterator last, StrictWeakOrdering comp)
{
    for (ForwardIterator i = first; ++i < last; ++first)
	if (comp (*i, *first))
	    return (false);
    return (true);
}

/// \brief Compares two given containers like strcmp compares strings.
/// \ingroup ConditionAlgorithms
/// \ingroup PredicateAlgorithms
template <typename InputIterator1, typename InputIterator2, typename BinaryPredicate>
bool lexicographical_compare (InputIterator1 first1, InputIterator1 last1, InputIterator2 first2, InputIterator2 last2, BinaryPredicate comp)
{
    for (; (first1 != last1) & (first2 != last2); ++first1, ++first2) {
	if (comp (*first1, *first2))
	    return (true);
	if (comp (*first2, *first1))
	    return (false);
    }
    return ((first1 == last1) & (first2 != last2));
}

/// \brief Creates the next lexicographical permutation of [first,last).
/// Returns false if no further permutations can be created.
/// \ingroup GeneratorAlgorithms
/// \ingroup PredicateAlgorithms
template <typename BidirectionalIterator, typename StrictWeakOrdering>
bool next_permutation (BidirectionalIterator first, BidirectionalIterator last, StrictWeakOrdering comp)
{
    if (distance (first, last) < 2)
	return (false);
    BidirectionalIterator i = last;
    for (--i; i != first; ) {
	--i;
	if (comp (i[0], i[1])) {
	    BidirectionalIterator j = last;
	    while (!comp (*i, *--j));
	    iter_swap (i, j);
	    reverse (i + 1, last);
	    return (true);
	}
    }
    reverse (first, last);
    return (false);
}

/// \brief Creates the previous lexicographical permutation of [first,last).
/// Returns false if no further permutations can be created.
/// \ingroup GeneratorAlgorithms
/// \ingroup PredicateAlgorithms
template <typename BidirectionalIterator, typename StrictWeakOrdering>
bool prev_permutation (BidirectionalIterator first, BidirectionalIterator last, StrictWeakOrdering comp)
{
    if (distance (first, last) < 2)
	return (false);
    BidirectionalIterator i = last;
    for (--i; i != first; ) {
	--i;
	if (comp(i[1], i[0])) {
	    BidirectionalIterator j = last;
	    while (!comp (*--j, *i));
	    iter_swap (i, j);
	    reverse (i + 1, last);
	    return (true);
	}
    }
    reverse (first, last);
    return (false);
}

/// \brief Returns iterator to the max element in [first,last)
/// \ingroup SearchingAlgorithms
/// \ingroup PredicateAlgorithms
template <typename ForwardIterator, typename BinaryPredicate>
inline ForwardIterator max_element (ForwardIterator first, ForwardIterator last, BinaryPredicate comp)
{
    ForwardIterator result = first;
    for (; first != last; ++first)
	if (comp (*result, *first))
	    result = first;
    return (result);
}

/// \brief Returns iterator to the min element in [first,last)
/// \ingroup SearchingAlgorithms
/// \ingroup PredicateAlgorithms
template <typename ForwardIterator, typename BinaryPredicate>
inline ForwardIterator min_element (ForwardIterator first, ForwardIterator last, BinaryPredicate comp)
{
    ForwardIterator result = first;
    for (; first != last; ++first)
	if (comp (*first, *result))
	    result = first;
    return (result);
}

/// \brief Makes [first,middle) a part of the sorted array.
/// Contents of [middle,last) is undefined. This implementation just calls stable_sort.
/// \ingroup SortingAlgorithms
/// \ingroup PredicateAlgorithms
template <typename RandomAccessIterator, typename StrictWeakOrdering>
inline void partial_sort (RandomAccessIterator first, RandomAccessIterator, RandomAccessIterator last, StrictWeakOrdering comp)
{
    stable_sort (first, last, comp);
}

/// \brief Like partial_sort, but outputs to [result_first,result_last)
/// \ingroup SortingAlgorithms
/// \ingroup PredicateAlgorithms
template <typename InputIterator, typename RandomAccessIterator, typename StrictWeakOrdering>
RandomAccessIterator partial_sort_copy (InputIterator first, InputIterator last, RandomAccessIterator result_first, RandomAccessIterator result_last, StrictWeakOrdering comp)
{
    RandomAccessIterator rend = result_first;
    for (; first != last; ++first) {
	RandomAccessIterator i = result_first;
	for (; i != rend && comp (*i, *first); ++i);
	if (i == result_last)
	    continue;
	rend += (rend < result_last);
	copy_backward (i, rend - 1, rend);
	*i = *first;
    }
    return (rend);
}

/// \brief Like \ref partition, but preserves equal element order.
/// \ingroup SortingAlgorithms
/// \ingroup PredicateAlgorithms
template <typename ForwardIterator, typename Predicate>
ForwardIterator stable_partition (ForwardIterator first, ForwardIterator last, Predicate pred)
{
    if (first == last)
	return (first);
    ForwardIterator l, r, m = advance (first, distance (first, last) / 2);
    if (first == m)
	return (pred(*first) ? last : first);
    l = stable_partition (first, m, pred);
    r = stable_partition (m, last, pred);
    rotate (l, m, r);
    return (advance (l, distance (m, r)));
}

/// \brief Splits [first,last) in two by \p pred.
///
/// Creates two ranges [first,middle) and [middle,last), where every element
/// in the former is less than every element in the latter.
/// The return value is middle.
///
/// \ingroup SortingAlgorithms
/// \ingroup PredicateAlgorithms
template <typename ForwardIterator, typename Predicate>
inline ForwardIterator partition (ForwardIterator first, ForwardIterator last, Predicate pred)
{
    return (stable_partition (first, last, pred));
}

} // namespace ustl

#endif

