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

#ifndef ANDROID_TRAITS_H
#define ANDROID_TRAITS_H

// -----------------------------------------------------------------------
// Typelists

namespace android {

// end-of-list marker
class NullType {};

// type-list node
template <typename T, typename U>
struct TypeList {
    typedef T Head;
    typedef U Tail;
};

// helpers to build typelists
#define TYPELIST_1(T1) TypeList<T1, NullType>
#define TYPELIST_2(T1, T2) TypeList<T1, TYPELIST_1(T2)>
#define TYPELIST_3(T1, T2, T3) TypeList<T1, TYPELIST_2(T2, T3)>
#define TYPELIST_4(T1, T2, T3, T4) TypeList<T1, TYPELIST_3(T2, T3, T4)>

// typelists algorithms
namespace TL {
template <typename TList, typename T> struct IndexOf;

template <typename T>
struct IndexOf<NullType, T> {
    enum { value = -1 };
};

template <typename T, typename Tail>
struct IndexOf<TypeList<T, Tail>, T> {
    enum { value = 0 };
};

template <typename Head, typename Tail, typename T>
struct IndexOf<TypeList<Head, Tail>, T> {
private:
    enum { temp = IndexOf<Tail, T>::value };
public:
    enum { value = temp == -1 ? -1 : 1 + temp };
};

}; // namespace TL

// type selection based on a boolean
template <bool flag, typename T, typename U>
struct Select {
    typedef T Result;
};
template <typename T, typename U>
struct Select<false, T, U> {
    typedef U Result;
};

// -----------------------------------------------------------------------
// Type traits

template <typename T>
class TypeTraits {
    typedef TYPELIST_4(
            unsigned char, unsigned short,
            unsigned int, unsigned long int) UnsignedInts;

    typedef TYPELIST_4(
            signed char, signed short,
            signed int, signed long int) SignedInts;

    typedef TYPELIST_1(
            bool) OtherInts;

    typedef TYPELIST_3(
            float, double, long double) Floats;

    template<typename U> struct PointerTraits {
        enum { result = false };
        typedef NullType PointeeType;
    };
    template<typename U> struct PointerTraits<U*> {
        enum { result = true };
        typedef U PointeeType;
    };

public:
    enum { isStdUnsignedInt = TL::IndexOf<UnsignedInts, T>::value >= 0 };
    enum { isStdSignedInt   = TL::IndexOf<SignedInts,   T>::value >= 0 };
    enum { isStdIntegral    = TL::IndexOf<OtherInts,    T>::value >= 0 || isStdUnsignedInt || isStdSignedInt };
    enum { isStdFloat       = TL::IndexOf<Floats,       T>::value >= 0 };
    enum { isPointer        = PointerTraits<T>::result };
    enum { isStdArith       = isStdIntegral || isStdFloat };

    // best parameter type for given type
    typedef typename Select<isStdArith || isPointer, T, const T&>::Result ParameterType;
};

// -----------------------------------------------------------------------
}; // namespace android

#endif /* ANDROID_TRAITS_H */
