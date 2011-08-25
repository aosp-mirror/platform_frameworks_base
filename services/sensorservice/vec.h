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

#ifndef ANDROID_VEC_H
#define ANDROID_VEC_H

#include <math.h>

#include <stdint.h>
#include <stddef.h>

#include "traits.h"

// -----------------------------------------------------------------------

#define PURE __attribute__((pure))

namespace android {

// -----------------------------------------------------------------------
// non-inline helpers

template <typename TYPE, size_t SIZE>
class vec;

template <typename TYPE, size_t SIZE>
class vbase;

namespace helpers {

template <typename T> inline T min(T a, T b) { return a<b ? a : b; }
template <typename T> inline T max(T a, T b) { return a>b ? a : b; }

template < template<typename T, size_t S> class VEC,
    typename TYPE, size_t SIZE, size_t S>
vec<TYPE, SIZE>& doAssign(
        vec<TYPE, SIZE>& lhs, const VEC<TYPE, S>& rhs) {
    const size_t minSize = min(SIZE, S);
    const size_t maxSize = max(SIZE, S);
    for (size_t i=0 ; i<minSize ; i++)
        lhs[i] = rhs[i];
    for (size_t i=minSize ; i<maxSize ; i++)
        lhs[i] = 0;
    return lhs;
}


template <
    template<typename T, size_t S> class VLHS,
    template<typename T, size_t S> class VRHS,
    typename TYPE,
    size_t SIZE
>
VLHS<TYPE, SIZE> PURE doAdd(
        const VLHS<TYPE, SIZE>& lhs,
        const VRHS<TYPE, SIZE>& rhs) {
    VLHS<TYPE, SIZE> r;
    for (size_t i=0 ; i<SIZE ; i++)
        r[i] = lhs[i] + rhs[i];
    return r;
}

template <
    template<typename T, size_t S> class VLHS,
    template<typename T, size_t S> class VRHS,
    typename TYPE,
    size_t SIZE
>
VLHS<TYPE, SIZE> PURE doSub(
        const VLHS<TYPE, SIZE>& lhs,
        const VRHS<TYPE, SIZE>& rhs) {
    VLHS<TYPE, SIZE> r;
    for (size_t i=0 ; i<SIZE ; i++)
        r[i] = lhs[i] - rhs[i];
    return r;
}

template <
    template<typename T, size_t S> class VEC,
    typename TYPE,
    size_t SIZE
>
VEC<TYPE, SIZE> PURE doMulScalar(
        const VEC<TYPE, SIZE>& lhs,
        typename TypeTraits<TYPE>::ParameterType rhs) {
    VEC<TYPE, SIZE> r;
    for (size_t i=0 ; i<SIZE ; i++)
        r[i] = lhs[i] * rhs;
    return r;
}

template <
    template<typename T, size_t S> class VEC,
    typename TYPE,
    size_t SIZE
>
VEC<TYPE, SIZE> PURE doScalarMul(
        typename TypeTraits<TYPE>::ParameterType lhs,
        const VEC<TYPE, SIZE>& rhs) {
    VEC<TYPE, SIZE> r;
    for (size_t i=0 ; i<SIZE ; i++)
        r[i] = lhs * rhs[i];
    return r;
}

}; // namespace helpers

// -----------------------------------------------------------------------
// Below we define the mathematical operators for vectors.
// We use template template arguments so we can generically
// handle the case where the right-hand-size and left-hand-side are
// different vector types (but with same value_type and size).
// This is needed for performance when using ".xy{z}" element access
// on vec<>. Without this, an extra conversion to vec<> would be needed.
//
// example:
//      vec4_t a;
//      vec3_t b;
//      vec3_t c = a.xyz + b;
//
//  "a.xyz + b" is a mixed-operation between a vbase<> and a vec<>, requiring
//  a conversion of vbase<> to vec<>. The template gunk below avoids this,
// by allowing the addition on these different vector types directly
//

template <
    template<typename T, size_t S> class VLHS,
    template<typename T, size_t S> class VRHS,
    typename TYPE,
    size_t SIZE
>
inline VLHS<TYPE, SIZE> PURE operator + (
        const VLHS<TYPE, SIZE>& lhs,
        const VRHS<TYPE, SIZE>& rhs) {
    return helpers::doAdd(lhs, rhs);
}

template <
    template<typename T, size_t S> class VLHS,
    template<typename T, size_t S> class VRHS,
    typename TYPE,
    size_t SIZE
>
inline VLHS<TYPE, SIZE> PURE operator - (
        const VLHS<TYPE, SIZE>& lhs,
        const VRHS<TYPE, SIZE>& rhs) {
    return helpers::doSub(lhs, rhs);
}

template <
    template<typename T, size_t S> class VEC,
    typename TYPE,
    size_t SIZE
>
inline VEC<TYPE, SIZE> PURE operator * (
        const VEC<TYPE, SIZE>& lhs,
        typename TypeTraits<TYPE>::ParameterType rhs) {
    return helpers::doMulScalar(lhs, rhs);
}

template <
    template<typename T, size_t S> class VEC,
    typename TYPE,
    size_t SIZE
>
inline VEC<TYPE, SIZE> PURE operator * (
        typename TypeTraits<TYPE>::ParameterType lhs,
        const VEC<TYPE, SIZE>& rhs) {
    return helpers::doScalarMul(lhs, rhs);
}


template <
    template<typename T, size_t S> class VLHS,
    template<typename T, size_t S> class VRHS,
    typename TYPE,
    size_t SIZE
>
TYPE PURE dot_product(
        const VLHS<TYPE, SIZE>& lhs,
        const VRHS<TYPE, SIZE>& rhs) {
    TYPE r(0);
    for (size_t i=0 ; i<SIZE ; i++)
        r += lhs[i] * rhs[i];
    return r;
}

template <
    template<typename T, size_t S> class V,
    typename TYPE,
    size_t SIZE
>
TYPE PURE length(const V<TYPE, SIZE>& v) {
    return sqrt(dot_product(v, v));
}

template <
    template<typename T, size_t S> class V,
    typename TYPE,
    size_t SIZE
>
TYPE PURE length_squared(const V<TYPE, SIZE>& v) {
    return dot_product(v, v);
}

template <
    template<typename T, size_t S> class V,
    typename TYPE,
    size_t SIZE
>
V<TYPE, SIZE> PURE normalize(const V<TYPE, SIZE>& v) {
    return v * (1/length(v));
}

template <
    template<typename T, size_t S> class VLHS,
    template<typename T, size_t S> class VRHS,
    typename TYPE
>
VLHS<TYPE, 3> PURE cross_product(
        const VLHS<TYPE, 3>& u,
        const VRHS<TYPE, 3>& v) {
    VLHS<TYPE, 3> r;
    r.x = u.y*v.z - u.z*v.y;
    r.y = u.z*v.x - u.x*v.z;
    r.z = u.x*v.y - u.y*v.x;
    return r;
}


template <typename TYPE, size_t SIZE>
vec<TYPE, SIZE> PURE operator - (const vec<TYPE, SIZE>& lhs) {
    vec<TYPE, SIZE> r;
    for (size_t i=0 ; i<SIZE ; i++)
        r[i] = -lhs[i];
    return r;
}

// -----------------------------------------------------------------------

// This our basic vector type, it just implements the data storage
// and accessors.

template <typename TYPE, size_t SIZE>
struct vbase {
    TYPE v[SIZE];
    inline const TYPE& operator[](size_t i) const { return v[i]; }
    inline       TYPE& operator[](size_t i)       { return v[i]; }
};
template<> struct vbase<float, 2> {
    union {
        float v[2];
        struct { float x, y; };
        struct { float s, t; };
    };
    inline const float& operator[](size_t i) const { return v[i]; }
    inline       float& operator[](size_t i)       { return v[i]; }
};
template<> struct vbase<float, 3> {
    union {
        float v[3];
        struct { float x, y, z; };
        struct { float s, t, r; };
        vbase<float, 2> xy;
        vbase<float, 2> st;
    };
    inline const float& operator[](size_t i) const { return v[i]; }
    inline       float& operator[](size_t i)       { return v[i]; }
};
template<> struct vbase<float, 4> {
    union {
        float v[4];
        struct { float x, y, z, w; };
        struct { float s, t, r, q; };
        vbase<float, 3> xyz;
        vbase<float, 3> str;
        vbase<float, 2> xy;
        vbase<float, 2> st;
    };
    inline const float& operator[](size_t i) const { return v[i]; }
    inline       float& operator[](size_t i)       { return v[i]; }
};

// -----------------------------------------------------------------------

template <typename TYPE, size_t SIZE>
class vec : public vbase<TYPE, SIZE>
{
    typedef typename TypeTraits<TYPE>::ParameterType pTYPE;
    typedef vbase<TYPE, SIZE> base;

public:
    // STL-like interface.
    typedef TYPE value_type;
    typedef TYPE& reference;
    typedef TYPE const& const_reference;
    typedef size_t size_type;

    typedef TYPE* iterator;
    typedef TYPE const* const_iterator;
    iterator begin() { return base::v; }
    iterator end() { return base::v + SIZE; }
    const_iterator begin() const { return base::v; }
    const_iterator end() const { return base::v + SIZE; }
    size_type size() const { return SIZE; }

    // -----------------------------------------------------------------------
    // default constructors

    vec() { }
    vec(const vec& rhs)  : base(rhs) { }
    vec(const base& rhs) : base(rhs) { }

    // -----------------------------------------------------------------------
    // conversion constructors

    vec(pTYPE rhs) {
        for (size_t i=0 ; i<SIZE ; i++)
            base::operator[](i) = rhs;
    }

    template < template<typename T, size_t S> class VEC, size_t S>
    explicit vec(const VEC<TYPE, S>& rhs) {
        helpers::doAssign(*this, rhs);
    }

    explicit vec(TYPE const* array) {
        for (size_t i=0 ; i<SIZE ; i++)
            base::operator[](i) = array[i];
    }

    // -----------------------------------------------------------------------
    // Assignment

    vec& operator = (const vec& rhs) {
        base::operator=(rhs);
        return *this;
    }

    vec& operator = (const base& rhs) {
        base::operator=(rhs);
        return *this;
    }

    vec& operator = (pTYPE rhs) {
        for (size_t i=0 ; i<SIZE ; i++)
            base::operator[](i) = rhs;
        return *this;
    }

    template < template<typename T, size_t S> class VEC, size_t S>
    vec& operator = (const VEC<TYPE, S>& rhs) {
        return helpers::doAssign(*this, rhs);
    }

    // -----------------------------------------------------------------------
    // operation-assignment

    vec& operator += (const vec& rhs);
    vec& operator -= (const vec& rhs);
    vec& operator *= (pTYPE rhs);

    // -----------------------------------------------------------------------
    // non-member function declaration and definition
    // NOTE: we declare the non-member function as friend inside the class
    // so that they are known to the compiler when the class is instantiated.
    // This helps the compiler doing template argument deduction when the
    // passed types are not identical. Essentially this helps with
    // type conversion so that you can multiply a vec<float> by an scalar int
    // (for instance).

    friend inline vec PURE operator + (const vec& lhs, const vec& rhs) {
        return helpers::doAdd(lhs, rhs);
    }
    friend inline vec PURE operator - (const vec& lhs, const vec& rhs) {
        return helpers::doSub(lhs, rhs);
    }
    friend inline vec PURE operator * (const vec& lhs, pTYPE v) {
        return helpers::doMulScalar(lhs, v);
    }
    friend inline vec PURE operator * (pTYPE v, const vec& rhs) {
        return helpers::doScalarMul(v, rhs);
    }
    friend inline TYPE PURE dot_product(const vec& lhs, const vec& rhs) {
        return android::dot_product(lhs, rhs);
    }
};

// -----------------------------------------------------------------------

template <typename TYPE, size_t SIZE>
vec<TYPE, SIZE>& vec<TYPE, SIZE>::operator += (const vec<TYPE, SIZE>& rhs) {
    vec<TYPE, SIZE>& lhs(*this);
    for (size_t i=0 ; i<SIZE ; i++)
        lhs[i] += rhs[i];
    return lhs;
}

template <typename TYPE, size_t SIZE>
vec<TYPE, SIZE>& vec<TYPE, SIZE>::operator -= (const vec<TYPE, SIZE>& rhs) {
    vec<TYPE, SIZE>& lhs(*this);
    for (size_t i=0 ; i<SIZE ; i++)
        lhs[i] -= rhs[i];
    return lhs;
}

template <typename TYPE, size_t SIZE>
vec<TYPE, SIZE>& vec<TYPE, SIZE>::operator *= (vec<TYPE, SIZE>::pTYPE rhs) {
    vec<TYPE, SIZE>& lhs(*this);
    for (size_t i=0 ; i<SIZE ; i++)
        lhs[i] *= rhs;
    return lhs;
}

// -----------------------------------------------------------------------

typedef vec<float, 2> vec2_t;
typedef vec<float, 3> vec3_t;
typedef vec<float, 4> vec4_t;

// -----------------------------------------------------------------------

}; // namespace android

#endif /* ANDROID_VEC_H */
