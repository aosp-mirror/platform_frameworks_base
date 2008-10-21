// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// ualgo.h
//
// Implementation of STL algorithms.
//
// The function prototypes are copied
// exactly from the SGI version of STL documentation along with comments about
// their use. The code is NOT the same, though the functionality usually is.
//

#ifndef UALGO_H_711AB4214D417A51166694D47A662D6E
#define UALGO_H_711AB4214D417A51166694D47A662D6E

#include "upair.h"
#include "ualgobase.h"
#include "ufunction.h"
#include "upredalgo.h"
#include "umemory.h"
#include <stdlib.h>	// for rand()

namespace ustl {

/// Swaps corresponding elements of [first, last) and [result,)
/// \ingroup SwapAlgorithms
///
template <typename ForwardIterator1, typename ForwardIterator2>
inline ForwardIterator2 swap_ranges (ForwardIterator1 first, ForwardIterator2 last, ForwardIterator2 result)
{
    for (; first != last; ++first, ++result)
	iter_swap (first, result);
    return (result);
}

/// Returns the first iterator i in the range [first, last) such that
/// *i == value. Returns last if no such iterator exists. 
/// \ingroup SearchingAlgorithms
///
template <typename InputIterator, typename EqualityComparable>
inline InputIterator find (InputIterator first, InputIterator last, const EqualityComparable& value)
{
    while (first != last && !(*first == value))
	++ first;
    return (first);
}

/// Returns the first iterator such that *i == *(i + 1)
/// \ingroup SearchingAlgorithms
///
template <typename ForwardIterator>
ForwardIterator adjacent_find (ForwardIterator first, ForwardIterator last)
{
    if (first != last)
	for (ForwardIterator prev = first; ++first != last; ++ prev)
	    if (*prev == *first)
		return (prev);
    return (last);
}

/// Returns the pointer to the first pair of unequal elements.
/// \ingroup SearchingAlgorithms
///
template <typename InputIterator>
pair<InputIterator,InputIterator>
mismatch (InputIterator first1, InputIterator last1, InputIterator first2)
{
    while (first1 != last1 && *first1 == *first2)
	++ first1, ++ first2;
    return (make_pair (first1, first2));
}

/// \brief Returns true if two ranges are equal.
/// This is an extension, present in uSTL and SGI STL.
/// \ingroup SearchingAlgorithms
///
template <typename InputIterator>
inline bool equal (InputIterator first1, InputIterator last1, InputIterator first2)
{
    return (mismatch (first1, last1, first2).first == last1);
}

/// Count finds the number of elements in [first, last) that are equal
/// to value. More precisely, the first version of count returns the
/// number of iterators i in [first, last) such that *i == value.
/// \ingroup SearchingAlgorithms
///
template <typename InputIterator, typename EqualityComparable>
inline size_t count (InputIterator first, InputIterator last, const EqualityComparable& value)
{
    size_t total = 0;
    for (; first != last; ++first)
	if (*first == value)
	    ++ total;
    return (total);
}

///
/// The first version of transform performs the operation op(*i) for each
/// iterator i in the range [first, last), and assigns the result of that
/// operation to *o, where o is the corresponding output iterator. That is,
/// for each n such that 0 <= n < last - first, it performs the assignment
/// *(result + n) = op(*(first + n)).
/// The return value is result + (last - first).
/// \ingroup MutatingAlgorithms
/// \ingroup PredicateAlgorithms
///
template <typename InputIterator, typename OutputIterator, typename UnaryFunction>
inline OutputIterator transform (InputIterator first, InputIterator last, OutputIterator result, UnaryFunction op)
{
    for (; first != last; ++result, ++first)
	*result = op (*first);
    return (result);
}

///
/// The second version of transform is very similar, except that it uses a
/// Binary Function instead of a Unary Function: it performs the operation
/// op(*i1, *i2) for each iterator i1 in the range [first1, last1) and assigns
/// the result to *o, where i2 is the corresponding iterator in the second
/// input range and where o is the corresponding output iterator. That is,
/// for each n such that 0 <= n < last1 - first1, it performs the assignment
/// *(result + n) = op(*(first1 + n), *(first2 + n).
/// The return value is result + (last1 - first1).
/// \ingroup MutatingAlgorithms
/// \ingroup PredicateAlgorithms
///
template <typename InputIterator1, typename InputIterator2, typename OutputIterator, typename BinaryFunction>
inline OutputIterator transform (InputIterator1 first1, InputIterator1 last1, InputIterator2 first2, OutputIterator result, BinaryFunction op)
{
    for (; first1 != last1; ++result, ++first1, ++first2)
	*result = op (*first1, *first2);
    return (result);
}

/// Replace replaces every element in the range [first, last) equal to
/// old_value with new_value. That is: for every iterator i,
/// if *i == old_value then it performs the assignment *i = new_value.
/// \ingroup MutatingAlgorithms
///
template <typename ForwardIterator, typename T>
inline void replace (ForwardIterator first, ForwardIterator last, const T& old_value, const T& new_value)
{
    for (; first != last; ++first)
	if (*first == old_value)
	    *first = new_value;
}

/// Replace_copy copies elements from the range [first, last) to the range
/// [result, result + (last-first)), except that any element equal to old_value
/// is not copied; new_value is copied instead. More precisely, for every
/// integer n such that 0 <= n < last-first, replace_copy performs the
/// assignment *(result+n) = new_value if *(first+n) == old_value, and
/// *(result+n) = *(first+n) otherwise.
/// \ingroup MutatingAlgorithms
///
template <typename InputIterator, typename OutputIterator, typename T>
inline OutputIterator replace_copy (InputIterator first, InputIterator last, OutputIterator result, const T& old_value, const T& new_value)
{
    for (; first != last; ++result, ++first)
        *result = (*first == old_value) ? new_value : *first;
}

/// Generate assigns the result of invoking gen, a function object that
/// takes no arguments, to each element in the range [first, last).
/// \ingroup GeneratorAlgorithms
/// \ingroup PredicateAlgorithms
///
template <typename ForwardIterator, typename Generator>
inline void generate (ForwardIterator first, ForwardIterator last, Generator gen)
{
    for (; first != last; ++first)
	*first = gen();
}

/// Generate_n assigns the result of invoking gen, a function object that
/// takes no arguments, to each element in the range [first, first+n).
/// The return value is first + n.
/// \ingroup GeneratorAlgorithms
/// \ingroup PredicateAlgorithms
///
template <typename OutputIterator, typename Generator>
inline OutputIterator generate_n (OutputIterator first, size_t n, Generator gen)
{
    for (uoff_t i = 0; i != n; ++i, ++first)
	*first = gen();
    return (first);
}

/// \brief Reverse reverses a range.
/// That is: for every i such that 0 <= i <= (last - first) / 2),
/// it exchanges *(first + i) and *(last - (i + 1)).
/// \ingroup MutatingAlgorithms
///
template <typename BidirectionalIterator>
inline void reverse (BidirectionalIterator first, BidirectionalIterator last)
{
    for (; distance (first, --last) > 0; ++first)
	iter_swap (first, last);
}

/// \brief Reverses [first,last) and writes it to \p output.
/// \ingroup MutatingAlgorithms
///
template <typename BidirectionalIterator, typename OutputIterator>
inline OutputIterator reverse_copy (BidirectionalIterator first, BidirectionalIterator last, OutputIterator result)
{
    for (; first != last; ++result)
	*result = *--last;
    return (result);
}

/// \brief Exchanges ranges [first, middle) and [middle, last)
/// \ingroup MutatingAlgorithms
///
template <typename ForwardIterator>
ForwardIterator rotate (ForwardIterator first, ForwardIterator middle, ForwardIterator last)
{
    if (first == middle || middle == last)
	return (first);
    reverse (first, middle);
    reverse (middle, last);
    for (;first != middle && middle != last; ++first)
	iter_swap (first, --last);
    reverse (first, (first == middle ? last : middle));
    return (first);
}

/// Specialization for pointers, which can be treated identically.
template <typename T>
inline T* rotate (T* first, T* middle, T* last)
{
    rotate_fast (first, middle, last);
    return (first);
}
 

/// \brief Exchanges ranges [first, middle) and [middle, last) into \p result.
/// \ingroup MutatingAlgorithms
///
template <typename ForwardIterator, typename OutputIterator>
inline OutputIterator rotate_copy (ForwardIterator first, ForwardIterator middle, ForwardIterator last, OutputIterator result)
{
    return (copy (first, middle, copy (middle, last, result)));
}

/// \brief Combines two sorted ranges.
/// \ingroup SortingAlgorithms
///
template <typename InputIterator1, typename InputIterator2, typename OutputIterator>
OutputIterator merge (InputIterator1 first1, InputIterator1 last1,
		      InputIterator2 first2, InputIterator2 last2, OutputIterator result)
{
    for (; first1 != last1 && first2 != last2; ++result) {
	if (*first1 < *first2)
	    *result = *first1++;
	else
	    *result = *first2++;
    }
    if (first1 < last1)
	return (copy (first1, last1, result));
    else
	return (copy (first2, last2, result));
}

/// Combines two sorted ranges from the same container.
/// \ingroup SortingAlgorithms
///
template <typename InputIterator>
void inplace_merge (InputIterator first, InputIterator middle, InputIterator last)
{
    for (; middle != last; ++first) {
	while (*first < *middle)
	    ++ first;
	reverse (first, middle);
	reverse (first, ++middle);
    }
}

/// Remove_copy copies elements that are not equal to value from the range
/// [first, last) to a range beginning at result. The return value is the
/// end of the resulting range. This operation is stable, meaning that the
/// relative order of the elements that are copied is the same as in the
/// range [first, last).
/// \ingroup MutatingAlgorithms
///
template <typename InputIterator, typename OutputIterator, typename T>
OutputIterator remove_copy (InputIterator first, InputIterator last, OutputIterator result, const T& value)
{
    for (; first != last; ++first) {
	if (!(*first == value)) {
	    *result = *first;
	    ++ result;
	}
    }
    return (result);
}

/// Remove_copy copies elements pointed to by iterators in [rfirst, rlast)
/// from the range [first, last) to a range beginning at result. The return
/// value is the end of the resulting range. This operation is stable, meaning
/// that the relative order of the elements that are copied is the same as in the
/// range [first, last). Range [rfirst, rlast) is assumed to be sorted.
/// This algorithm is a uSTL extension.
/// \ingroup MutatingAlgorithms
///
template <typename InputIterator, typename OutputIterator, typename RInputIterator>
OutputIterator remove_copy (InputIterator first, InputIterator last, OutputIterator result, RInputIterator rfirst, RInputIterator rlast)
{
    for (; first != last; ++first) {
	while (rfirst != rlast && *rfirst < first)
	    ++ rfirst;
	if (rfirst == rlast || first != *rfirst) {
	    *result = *first;
	    ++ result;
	}
    }
    return (result);
}

/// Remove removes from the range [first, last) all elements that are equal to
/// value. That is, remove returns an iterator new_last such that the range
/// [first, new_last) contains no elements equal to value. [1] The iterators
/// in the range [new_last, last) are all still dereferenceable, but the
/// elements that they point to are unspecified. Remove is stable, meaning
/// that the relative order of elements that are not equal to value is
/// unchanged.
/// \ingroup MutatingAlgorithms
///
template <typename ForwardIterator, typename T>
inline ForwardIterator remove (ForwardIterator first, ForwardIterator last, const T& value)
{
    return (remove_copy (first, last, first, value));
}

/// Unique_copy copies elements from the range [first, last) to a range
/// beginning with result, except that in a consecutive group of duplicate
/// elements only the first one is copied. The return value is the end of
/// the range to which the elements are copied. This behavior is similar
/// to the Unix filter uniq.
/// \ingroup MutatingAlgorithms
///
template <typename InputIterator, typename OutputIterator>
OutputIterator unique_copy (InputIterator first, InputIterator last, OutputIterator result)
{
    if (first != last) {
	*result = *first;
	while (++first != last)
	    if (!(*first == *result))
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
///
template <typename ForwardIterator>
inline ForwardIterator unique (ForwardIterator first, ForwardIterator last)
{
    return (unique_copy (first, last, first));
}

/// Returns the furthermost iterator i in [first, last) such that,
/// for every iterator j in [first, i), *j < value
/// Assumes the range is sorted.
/// \ingroup SearchingAlgorithms
///
template <typename ForwardIterator, typename LessThanComparable>
ForwardIterator lower_bound (ForwardIterator first, ForwardIterator last, const LessThanComparable& value)
{
    ForwardIterator mid;
    while (first != last) {
	mid = advance (first, distance (first,last) / 2);
	if (*mid < value)
	    first = mid + 1;
	else
	    last = mid;
    }
    return (first);
}

/// Performs a binary search inside the sorted range.
/// \ingroup SearchingAlgorithms
///
template <typename ForwardIterator, typename LessThanComparable>
inline ForwardIterator binary_search (ForwardIterator first, ForwardIterator last, const LessThanComparable& value)
{
    ForwardIterator found = lower_bound (first, last, value);
    return ((found == last || value < *found) ? last : found);
}

/// Returns the furthermost iterator i in [first,last) such that for
/// every iterator j in [first,i), value < *j is false.
/// \ingroup SearchingAlgorithms
///
template <typename ForwardIterator, typename LessThanComparable>
ForwardIterator upper_bound (ForwardIterator first, ForwardIterator last, const LessThanComparable& value)
{
    ForwardIterator mid;
    while (first != last) {
	mid = advance (first, distance (first,last) / 2);
	if (value < *mid)
	    last = mid;
	else
	    first = mid + 1;
    }
    return (last);
}

/// Returns pair<lower_bound,upper_bound>
/// \ingroup SearchingAlgorithms
///
template <typename ForwardIterator, typename LessThanComparable>
inline pair<ForwardIterator,ForwardIterator> equal_range (ForwardIterator first, ForwardIterator last, const LessThanComparable& value)
{
    pair<ForwardIterator,ForwardIterator> rv;
    rv.second = rv.first = lower_bound (first, last, value);
    while (rv.second != last && !(value < *(rv.second)))
	++ rv.second;
    return (rv);
}

/// Randomly permute the elements of the container.
/// \ingroup GeneratorAlgorithms
///
template <typename RandomAccessIterator>
void random_shuffle (RandomAccessIterator first, RandomAccessIterator last)
{
    for (; first != last; ++ first)
	iter_swap (first, first + (rand() % distance (first, last)));
}

/// \brief Generic compare function adaptor to pass to qsort
/// \ingroup FunctorObjects
template <typename ConstPointer, typename Compare>
int qsort_adapter (const void* p1, const void* p2)
{
    ConstPointer i1 = reinterpret_cast<ConstPointer>(p1);
    ConstPointer i2 = reinterpret_cast<ConstPointer>(p2);
    Compare comp;
    return (comp (*i1, *i2) ? -1 : (comp (*i2, *i1) ? 1 : 0));
}

/// Sorts the container
/// \ingroup SortingAlgorithms
/// \ingroup PredicateAlgorithms
///
template <typename RandomAccessIterator, typename Compare>
void sort (RandomAccessIterator first, RandomAccessIterator last, Compare)
{
    typedef typename iterator_traits<RandomAccessIterator>::value_type value_type;
    typedef typename iterator_traits<RandomAccessIterator>::const_pointer const_pointer;
    qsort (first, distance (first, last), sizeof(value_type),
	   &qsort_adapter<const_pointer, Compare>);
}

/// Sorts the container
/// \ingroup SortingAlgorithms
///
template <typename RandomAccessIterator>
inline void sort (RandomAccessIterator first, RandomAccessIterator last)
{
    typedef typename iterator_traits<RandomAccessIterator>::value_type value_type;
    sort (first, last, less<value_type>());
}

/// Sorts the container preserving order of equal elements.
/// \ingroup SortingAlgorithms
/// \ingroup PredicateAlgorithms
///
template <typename RandomAccessIterator, typename Compare>
void stable_sort (RandomAccessIterator first, RandomAccessIterator last, Compare comp)
{
    for (RandomAccessIterator j, i = first; ++i < last;) { // Insertion sort
	for (j = i; j-- > first && !comp(*j, *i););
	rotate (++j, i, i + 1);
    }
}

/// Sorts the container
/// \ingroup SortingAlgorithms
///
template <typename RandomAccessIterator>
inline void stable_sort (RandomAccessIterator first, RandomAccessIterator last)
{
    typedef typename iterator_traits<RandomAccessIterator>::value_type value_type;
    stable_sort (first, last, less<value_type>());
}

/// \brief Searches for the first subsequence [first2,last2) in [first1,last1)
/// \ingroup SearchingAlgorithms
template <typename ForwardIterator1, typename ForwardIterator2>
inline ForwardIterator1 search (ForwardIterator1 first1, ForwardIterator1 last1, ForwardIterator2 first2, ForwardIterator2 last2)
{
    typedef typename iterator_traits<ForwardIterator1>::value_type value_type;
    return (search (first1, last1, first2, last2, equal_to<value_type>()));
}

/// \brief Searches for the last subsequence [first2,last2) in [first1,last1)
/// \ingroup SearchingAlgorithms
template <typename ForwardIterator1, typename ForwardIterator2>
inline ForwardIterator1 find_end (ForwardIterator1 first1, ForwardIterator1 last1, ForwardIterator2 first2, ForwardIterator2 last2)
{
    typedef typename iterator_traits<ForwardIterator1>::value_type value_type;
    return (find_end (first1, last1, first2, last2, equal_to<value_type>()));
}

/// \brief Searches for the first occurence of \p count \p values in [first, last)
/// \ingroup SearchingAlgorithms
template <typename Iterator, typename T>
inline Iterator search_n (Iterator first, Iterator last, size_t count, const T& value)
{
    typedef typename iterator_traits<Iterator>::value_type value_type;
    return (search_n (first, last, count, value, equal_to<value_type>()));
}

/// \brief Searches [first1,last1) for the first occurrence of an element from [first2,last2)
/// \ingroup SearchingAlgorithms
template <typename InputIterator, typename ForwardIterator>
inline InputIterator find_first_of (InputIterator first1, InputIterator last1, ForwardIterator first2, ForwardIterator last2)
{
    typedef typename iterator_traits<InputIterator>::value_type value_type;
    return (find_first_of (first1, last1, first2, last2, equal_to<value_type>()));
}

/// \brief Returns true if [first2,last2) is a subset of [first1,last1)
/// \ingroup ConditionAlgorithms
/// \ingroup SetAlgorithms
template <typename InputIterator1, typename InputIterator2>
inline bool includes (InputIterator1 first1, InputIterator1 last1, InputIterator2 first2, InputIterator2 last2)
{
    typedef typename iterator_traits<InputIterator1>::value_type value_type;
    return (includes (first1, last1, first2, last2, less<value_type>()));
}

/// \brief Merges [first1,last1) with [first2,last2)
///
/// Result will contain every element that is in either set. If duplicate
/// elements are present, max(n,m) is placed in the result.
///
/// \ingroup SetAlgorithms
template <typename InputIterator1, typename InputIterator2, typename OutputIterator>
inline OutputIterator set_union (InputIterator1 first1, InputIterator1 last1, InputIterator2 first2, InputIterator2 last2, OutputIterator result)
{
    typedef typename iterator_traits<InputIterator1>::value_type value_type;
    return (set_union (first1, last1, first2, last2, result, less<value_type>()));
}

/// \brief Creates a set containing elements shared by the given ranges.
/// \ingroup SetAlgorithms
template <typename InputIterator1, typename InputIterator2, typename OutputIterator>
inline OutputIterator set_intersection (InputIterator1 first1, InputIterator1 last1, InputIterator2 first2, InputIterator2 last2, OutputIterator result)
{
    typedef typename iterator_traits<InputIterator1>::value_type value_type;
    return (set_intersection (first1, last1, first2, last2, result, less<value_type>()));
}

/// \brief Removes from [first1,last1) elements present in [first2,last2)
/// \ingroup SetAlgorithms
template <typename InputIterator1, typename InputIterator2, typename OutputIterator>
inline OutputIterator set_difference (InputIterator1 first1, InputIterator1 last1, InputIterator2 first2, InputIterator2 last2, OutputIterator result)
{
    typedef typename iterator_traits<InputIterator1>::value_type value_type;
    return (set_difference (first1, last1, first2, last2, result, less<value_type>()));
}

/// \brief Performs union of sets A-B and B-A.
/// \ingroup SetAlgorithms
template <typename InputIterator1, typename InputIterator2, typename OutputIterator>
inline OutputIterator set_symmetric_difference (InputIterator1 first1, InputIterator1 last1, InputIterator2 first2, InputIterator2 last2, OutputIterator result)
{
    typedef typename iterator_traits<InputIterator1>::value_type value_type;
    return (set_symmetric_difference (first1, last1, first2, last2, result, less<value_type>()));
}

/// \brief Returns true if the given range is sorted.
/// \ingroup ConditionAlgorithms
template <typename ForwardIterator>
inline bool is_sorted (ForwardIterator first, ForwardIterator last)
{
    typedef typename iterator_traits<ForwardIterator>::value_type value_type;
    return (is_sorted (first, last, less<value_type>()));
}

/// \brief Compares two given containers like strcmp compares strings.
/// \ingroup ConditionAlgorithms
template <typename InputIterator1, typename InputIterator2>
inline bool lexicographical_compare (InputIterator1 first1, InputIterator1 last1, InputIterator2 first2, InputIterator2 last2)
{
    typedef typename iterator_traits<InputIterator1>::value_type value_type;
    return (lexicographical_compare (first1, last1, first2, last2, less<value_type>()));
}

/// \brief Creates the next lexicographical permutation of [first,last).
/// Returns false if no further permutations can be created.
/// \ingroup GeneratorAlgorithms
template <typename BidirectionalIterator>
inline bool next_permutation (BidirectionalIterator first, BidirectionalIterator last)
{
    typedef typename iterator_traits<BidirectionalIterator>::value_type value_type;
    return (next_permutation (first, last, less<value_type>()));
}

/// \brief Creates the previous lexicographical permutation of [first,last).
/// Returns false if no further permutations can be created.
/// \ingroup GeneratorAlgorithms
template <typename BidirectionalIterator>
inline bool prev_permutation (BidirectionalIterator first, BidirectionalIterator last)
{
    typedef typename iterator_traits<BidirectionalIterator>::value_type value_type;
    return (prev_permutation (first, last, less<value_type>()));
}

/// \brief Returns iterator to the max element in [first,last)
/// \ingroup SearchingAlgorithms
template <typename ForwardIterator>
inline ForwardIterator max_element (ForwardIterator first, ForwardIterator last)
{
    typedef typename iterator_traits<ForwardIterator>::value_type value_type;
    return (max_element (first, last, less<value_type>()));
}

/// \brief Returns iterator to the min element in [first,last)
/// \ingroup SearchingAlgorithms
template <typename ForwardIterator>
inline ForwardIterator min_element (ForwardIterator first, ForwardIterator last)
{
    typedef typename iterator_traits<ForwardIterator>::value_type value_type;
    return (min_element (first, last, less<value_type>()));
}

/// \brief Makes [first,middle) a part of the sorted array.
/// Contents of [middle,last) is undefined. This implementation just calls stable_sort.
/// \ingroup SortingAlgorithms
template <typename RandomAccessIterator>
inline void partial_sort (RandomAccessIterator first, RandomAccessIterator middle, RandomAccessIterator last)
{
    typedef typename iterator_traits<RandomAccessIterator>::value_type value_type;
    partial_sort (first, middle, last, less<value_type>());
}

/// \brief Puts \p nth element into its sorted position.
/// In this implementation, the entire array is sorted. I can't think of any
/// use for it where the time gained would be useful.
/// \ingroup SortingAlgorithms
/// \ingroup SearchingAlgorithms
///
template <typename RandomAccessIterator>
inline void nth_element (RandomAccessIterator first, RandomAccessIterator nth, RandomAccessIterator last)
{
    partial_sort (first, nth, last);
}

/// \brief Like partial_sort, but outputs to [result_first,result_last)
/// \ingroup SortingAlgorithms
template <typename InputIterator, typename RandomAccessIterator>
inline RandomAccessIterator partial_sort_copy (InputIterator first, InputIterator last, RandomAccessIterator result_first, RandomAccessIterator result_last)
{
    typedef typename iterator_traits<InputIterator>::value_type value_type;
    return (partial_sort_copy (first, last, result_first, result_last, less<value_type>()));
}

} // namespace ustl

#endif

