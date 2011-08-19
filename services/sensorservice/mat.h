/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef ANDROID_MAT_H
#define ANDROID_MAT_H

#include "vec.h"
#include "traits.h"

// -----------------------------------------------------------------------

namespace android {

template <typename TYPE, size_t C, size_t R>
class mat;

namespace helpers {

template <typename TYPE, size_t C, size_t R>
mat<TYPE, C, R>& doAssign(
        mat<TYPE, C, R>& lhs,
        typename TypeTraits<TYPE>::ParameterType rhs) {
    for (size_t i=0 ; i<C ; i++)
        for (size_t j=0 ; j<R ; j++)
            lhs[i][j] = (i==j) ? rhs : 0;
    return lhs;
}

template <typename TYPE, size_t C, size_t R, size_t D>
mat<TYPE, C, R> PURE doMul(
        const mat<TYPE, D, R>& lhs,
        const mat<TYPE, C, D>& rhs)
{
    mat<TYPE, C, R> res;
    for (size_t c=0 ; c<C ; c++) {
        for (size_t r=0 ; r<R ; r++) {
            TYPE v(0);
            for (size_t k=0 ; k<D ; k++) {
                v += lhs[k][r] * rhs[c][k];
            }
            res[c][r] = v;
        }
    }
    return res;
}

template <typename TYPE, size_t R, size_t D>
vec<TYPE, R> PURE doMul(
        const mat<TYPE, D, R>& lhs,
        const vec<TYPE, D>& rhs)
{
    vec<TYPE, R> res;
    for (size_t r=0 ; r<R ; r++) {
        TYPE v(0);
        for (size_t k=0 ; k<D ; k++) {
            v += lhs[k][r] * rhs[k];
        }
        res[r] = v;
    }
    return res;
}

template <typename TYPE, size_t C, size_t R>
mat<TYPE, C, R> PURE doMul(
        const vec<TYPE, R>& lhs,
        const mat<TYPE, C, 1>& rhs)
{
    mat<TYPE, C, R> res;
    for (size_t c=0 ; c<C ; c++) {
        for (size_t r=0 ; r<R ; r++) {
            res[c][r] = lhs[r] * rhs[c][0];
        }
    }
    return res;
}

template <typename TYPE, size_t C, size_t R>
mat<TYPE, C, R> PURE doMul(
        const mat<TYPE, C, R>& rhs,
        typename TypeTraits<TYPE>::ParameterType v)
{
    mat<TYPE, C, R> res;
    for (size_t c=0 ; c<C ; c++) {
        for (size_t r=0 ; r<R ; r++) {
            res[c][r] = rhs[c][r] * v;
        }
    }
    return res;
}

template <typename TYPE, size_t C, size_t R>
mat<TYPE, C, R> PURE doMul(
        typename TypeTraits<TYPE>::ParameterType v,
        const mat<TYPE, C, R>& rhs)
{
    mat<TYPE, C, R> res;
    for (size_t c=0 ; c<C ; c++) {
        for (size_t r=0 ; r<R ; r++) {
            res[c][r] = v * rhs[c][r];
        }
    }
    return res;
}


}; // namespace helpers

// -----------------------------------------------------------------------

template <typename TYPE, size_t C, size_t R>
class mat : public vec< vec<TYPE, R>, C > {
    typedef typename TypeTraits<TYPE>::ParameterType pTYPE;
    typedef vec< vec<TYPE, R>, C > base;
public:
    // STL-like interface.
    typedef TYPE value_type;
    typedef TYPE& reference;
    typedef TYPE const& const_reference;
    typedef size_t size_type;
    size_type size() const { return R*C; }
    enum { ROWS = R, COLS = C };


    // -----------------------------------------------------------------------
    // default constructors

    mat() { }
    mat(const mat& rhs)  : base(rhs) { }
    mat(const base& rhs) : base(rhs) { }

    // -----------------------------------------------------------------------
    // conversion constructors

    // sets the diagonal to the value, off-diagonal to zero
    mat(pTYPE rhs) {
        helpers::doAssign(*this, rhs);
    }

    // -----------------------------------------------------------------------
    // Assignment

    mat& operator=(const mat& rhs) {
        base::operator=(rhs);
        return *this;
    }

    mat& operator=(const base& rhs) {
        base::operator=(rhs);
        return *this;
    }

    mat& operator=(pTYPE rhs) {
        return helpers::doAssign(*this, rhs);
    }

    // -----------------------------------------------------------------------
    // non-member function declaration and definition

    friend inline mat PURE operator + (const mat& lhs, const mat& rhs) {
        return helpers::doAdd(
                static_cast<const base&>(lhs),
                static_cast<const base&>(rhs));
    }
    friend inline mat PURE operator - (const mat& lhs, const mat& rhs) {
        return helpers::doSub(
                static_cast<const base&>(lhs),
                static_cast<const base&>(rhs));
    }

    // matrix*matrix
    template <size_t D>
    friend mat PURE operator * (
            const mat<TYPE, D, R>& lhs,
            const mat<TYPE, C, D>& rhs) {
        return helpers::doMul(lhs, rhs);
    }

    // matrix*vector
    friend vec<TYPE, R> PURE operator * (
            const mat& lhs, const vec<TYPE, C>& rhs) {
        return helpers::doMul(lhs, rhs);
    }

    // vector*matrix
    friend mat PURE operator * (
            const vec<TYPE, R>& lhs, const mat<TYPE, C, 1>& rhs) {
        return helpers::doMul(lhs, rhs);
    }

    // matrix*scalar
    friend inline mat PURE operator * (const mat& lhs, pTYPE v) {
        return helpers::doMul(lhs, v);
    }

    // scalar*matrix
    friend inline mat PURE operator * (pTYPE v, const mat& rhs) {
        return helpers::doMul(v, rhs);
    }

    // -----------------------------------------------------------------------
    // streaming operator to set the columns of the matrix:
    // example:
    //    mat33_t m;
    //    m << v0 << v1 << v2;

    // column_builder<> stores the matrix and knows which column to set
    template<size_t PREV_COLUMN>
    struct column_builder {
        mat& matrix;
        column_builder(mat& matrix) : matrix(matrix) { }
    };

    // operator << is not a method of column_builder<> so we can
    // overload it for unauthorized values (partial specialization
    // not allowed in class-scope).
    // we just set the column and return the next column_builder<>
    template<size_t PREV_COLUMN>
    friend column_builder<PREV_COLUMN+1> operator << (
            const column_builder<PREV_COLUMN>& lhs,
            const vec<TYPE, R>& rhs) {
        lhs.matrix[PREV_COLUMN+1] = rhs;
        return column_builder<PREV_COLUMN+1>(lhs.matrix);
    }

    // we return void here so we get a compile-time error if the
    // user tries to set too many columns
    friend void operator << (
            const column_builder<C-2>& lhs,
            const vec<TYPE, R>& rhs) {
        lhs.matrix[C-1] = rhs;
    }

    // this is where the process starts. we set the first columns and
    // return the next column_builder<>
    column_builder<0> operator << (const vec<TYPE, R>& rhs) {
        (*this)[0] = rhs;
        return column_builder<0>(*this);
    }
};

// Specialize column matrix so they're exactly equivalent to a vector
template <typename TYPE, size_t R>
class mat<TYPE, 1, R> : public vec<TYPE, R> {
    typedef vec<TYPE, R> base;
public:
    // STL-like interface.
    typedef TYPE value_type;
    typedef TYPE& reference;
    typedef TYPE const& const_reference;
    typedef size_t size_type;
    size_type size() const { return R; }
    enum { ROWS = R, COLS = 1 };

    mat() { }
    mat(const base& rhs) : base(rhs) { }
    mat(const mat& rhs) : base(rhs) { }
    mat(const TYPE& rhs) { helpers::doAssign(*this, rhs); }
    mat& operator=(const mat& rhs) { base::operator=(rhs); return *this; }
    mat& operator=(const base& rhs) { base::operator=(rhs); return *this; }
    mat& operator=(const TYPE& rhs) { return helpers::doAssign(*this, rhs); }
    // we only have one column, so ignore the index
    const base& operator[](size_t) const { return *this; }
    base& operator[](size_t) { return *this; }
    void operator << (const vec<TYPE, R>& rhs) { base::operator[](0) = rhs; }
};

// -----------------------------------------------------------------------
// matrix functions

// transpose. this handles matrices of matrices
inline int     PURE transpose(int v)    { return v; }
inline float   PURE transpose(float v)  { return v; }
inline double  PURE transpose(double v) { return v; }

// Transpose a matrix
template <typename TYPE, size_t C, size_t R>
mat<TYPE, R, C> PURE transpose(const mat<TYPE, C, R>& m) {
    mat<TYPE, R, C> r;
    for (size_t i=0 ; i<R ; i++)
        for (size_t j=0 ; j<C ; j++)
            r[i][j] = transpose(m[j][i]);
    return r;
}

// Calculate the trace of a matrix
template <typename TYPE, size_t C> static TYPE trace(const mat<TYPE, C, C>& m) {
    TYPE t;
    for (size_t i=0 ; i<C ; i++)
        t += m[i][i];
    return t;
}

// Test positive-semidefiniteness of a matrix
template <typename TYPE, size_t C>
static bool isPositiveSemidefinite(const mat<TYPE, C, C>& m, TYPE tolerance) {
    for (size_t i=0 ; i<C ; i++)
        if (m[i][i] < 0)
            return false;

    for (size_t i=0 ; i<C ; i++)
      for (size_t j=i+1 ; j<C ; j++)
          if (fabs(m[i][j] - m[j][i]) > tolerance)
              return false;

    return true;
}

// Transpose a vector
template <
    template<typename T, size_t S> class VEC,
    typename TYPE,
    size_t SIZE
>
mat<TYPE, SIZE, 1> PURE transpose(const VEC<TYPE, SIZE>& v) {
    mat<TYPE, SIZE, 1> r;
    for (size_t i=0 ; i<SIZE ; i++)
        r[i][0] = transpose(v[i]);
    return r;
}

// -----------------------------------------------------------------------
// "dumb" matrix inversion
template<typename T, size_t N>
mat<T, N, N> PURE invert(const mat<T, N, N>& src) {
    T t;
    size_t swap;
    mat<T, N, N> tmp(src);
    mat<T, N, N> inverse(1);

    for (size_t i=0 ; i<N ; i++) {
        // look for largest element in column
        swap = i;
        for (size_t j=i+1 ; j<N ; j++) {
            if (fabs(tmp[j][i]) > fabs(tmp[i][i])) {
                swap = j;
            }
        }

        if (swap != i) {
            /* swap rows. */
            for (size_t k=0 ; k<N ; k++) {
                t = tmp[i][k];
                tmp[i][k] = tmp[swap][k];
                tmp[swap][k] = t;

                t = inverse[i][k];
                inverse[i][k] = inverse[swap][k];
                inverse[swap][k] = t;
            }
        }

        t = 1 / tmp[i][i];
        for (size_t k=0 ; k<N ; k++) {
            tmp[i][k] *= t;
            inverse[i][k] *= t;
        }
        for (size_t j=0 ; j<N ; j++) {
            if (j != i) {
                t = tmp[j][i];
                for (size_t k=0 ; k<N ; k++) {
                    tmp[j][k] -= tmp[i][k] * t;
                    inverse[j][k] -= inverse[i][k] * t;
                }
            }
        }
    }
    return inverse;
}

// -----------------------------------------------------------------------

typedef mat<float, 2, 2> mat22_t;
typedef mat<float, 3, 3> mat33_t;
typedef mat<float, 4, 4> mat44_t;

// -----------------------------------------------------------------------

}; // namespace android

#endif /* ANDROID_MAT_H */
