/*
 * Copyright (C) 2013 The Android Open Source Project
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

#ifndef ANDROID_HWUI_PAIR_H
#define ANDROID_HWUI_PAIR_H

#include <utils/TypeHelpers.h>

namespace android {
namespace uirenderer {

template <typename F, typename S>
struct Pair {
    F first;
    S second;

    Pair() { }
    Pair(const Pair& o) : first(o.first), second(o.second) { }
    Pair(const F& f, const S& s) : first(f), second(s)  { }

    inline const F& getFirst() const {
        return first;
    }

    inline const S& getSecond() const {
        return second;
    }
};

}; // namespace uirenderer

template <typename F, typename S>
struct trait_trivial_ctor< uirenderer::Pair<F, S> >
{ enum { value = aggregate_traits<F, S>::has_trivial_ctor }; };
template <typename F, typename S>
struct trait_trivial_dtor< uirenderer::Pair<F, S> >
{ enum { value = aggregate_traits<F, S>::has_trivial_dtor }; };
template <typename F, typename S>
struct trait_trivial_copy< uirenderer::Pair<F, S> >
{ enum { value = aggregate_traits<F, S>::has_trivial_copy }; };
template <typename F, typename S>
struct trait_trivial_move< uirenderer::Pair<F, S> >
{ enum { value = aggregate_traits<F, S>::has_trivial_move }; };

}; // namespace android

#endif // ANDROID_HWUI_PAIR_H
