// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// unumeric.h
//
//	This file contains numeric algorithm templates.
//

#ifndef UNUMERIC_H_6C99D6F6363832C644A6FFF336E84E18
#define UNUMERIC_H_6C99D6F6363832C644A6FFF336E84E18

namespace ustl {

/// Returns the sum of all elements in [first, last) added to \p init.
/// \ingroup NumericAlgorithms
///
template <typename InputIterator, typename T>
inline T accumulate (InputIterator first, InputIterator last, T init)
{
    while (first < last)
	init += *first++;
    return (init);
}

/// Returns the sum of all elements in [first, last) via \p op, added to \p init.
/// \ingroup NumericAlgorithms
///
template <typename InputIterator, typename T, typename BinaryFunction>
inline T accumulate (InputIterator first, InputIterator last, T init, BinaryFunction binary_op)
{
    while (first < last)
	init = binary_op (init, *first++);
    return (init);
}

/// Assigns range [value, value + (last - first)) to [first, last)
/// \ingroup NumericAlgorithms
///
template <typename ForwardIterator, typename T>
inline void iota (ForwardIterator first, ForwardIterator last, T value)
{
    while (first < last)
	*first++ = value++;
}

/// Returns the sum of products of respective elements in the given ranges.
/// \ingroup NumericAlgorithms
///
template <typename InputIterator1, typename InputIterator2, typename T>
inline T inner_product (InputIterator1 first1, InputIterator1 last1, InputIterator2 first2, T init)
{
    while (first1 < last1)
	init += *first1++ * *first2++;
    return (init);
}

/// Returns the sum of products of respective elements in the given ranges.
/// \ingroup NumericAlgorithms
///
template <typename InputIterator1, typename InputIterator2, typename T,
    	  typename BinaryOperation1, typename BinaryOperation2>
inline T inner_product
(InputIterator1 first1, InputIterator1 last1, InputIterator2 first2, T init,
 BinaryOperation1 sumOp, BinaryOperation2 productOp)
{
    while (first1 < last1)
	init = sumOp (init, productOp (*first1++, *first2++));
    return (init);
}

/// Writes result such that result[i] = sum (first...first+i)
/// \ingroup NumericAlgorithms
///
template <typename InputIterator, typename OutputIterator>
inline OutputIterator partial_sum (InputIterator first, InputIterator last, OutputIterator result)
{
    if (first < last)
	*result = *first++;
    while (first < last)
	*++result = *first++ + *result;
    return (result);
}

/// Writes result such that result[i] = sumOp (first...first+i)
/// \ingroup NumericAlgorithms
///
template <typename InputIterator, typename OutputIterator, typename BinaryOperation>
inline OutputIterator partial_sum (InputIterator first, InputIterator last, OutputIterator result, BinaryOperation sumOp)
{
    if (first < last)
	*result = *first++;
    while (first < last)
	*++result = sumOp (*first++, *result);
    return (result);
}

/// Writes result such that result[i] = first[i] - first[i - 1]
/// \ingroup NumericAlgorithms
///
template <typename InputIterator, typename OutputIterator>
inline OutputIterator adjacent_difference (InputIterator first, InputIterator last, OutputIterator result)
{
    if (first < last)
	*result++ = *first++;
    while (first < last)
	*result++ = *first - *(first - 1);
    return (result);
}

/// Writes result such that result[i] = differenceOp (first[i], first[i - 1])
/// \ingroup NumericAlgorithms
///
template <typename InputIterator, typename OutputIterator, typename BinaryOperation>
inline OutputIterator adjacent_difference (InputIterator first, InputIterator last, OutputIterator result, BinaryOperation differenceOp)
{
    if (first < last)
	*result++ = *first++;
    while (first < last)
	*result++ = differenceOp (*first, *(first - 1));
    return (result);
}

/// \brief Returns x^n.
/// Donald Knuth's Russian Peasant algorithm.
/// \ingroup NumericAlgorithms
///
template <typename T>
inline T power (T x, unsigned n)
{
    T result (n % 2 ? x : 1);
    while (n /= 2) {
	x *= x;
	if (n % 2)
	    result *= x;
    }
    return (result);
}

/// \brief Returns x^n, using \p op instead of multiplication.
/// Donald Knuth's Russian Peasant algorithm.
/// \ingroup NumericAlgorithms
///
template <typename T, typename BinaryOperation>
inline T power (T x, unsigned n, BinaryOperation op)
{
    T result (n % 2 ? x : 1);
    while (n /= 2) {
	x = op (x, x);
	if (n % 2)
	    result = op (result, x);
    }
    return (result);
}

} // namespace ustl

#endif

