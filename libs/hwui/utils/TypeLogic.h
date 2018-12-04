/*
 * Copyright (C) 2018 The Android Open Source Project
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

#pragma once

#include <type_traits>

namespace android::uirenderer {

template <typename D, typename S> struct copy_const {
    using type = std::conditional_t<std::is_const<S>::value, std::add_const_t<D>, D>;
};
template <typename D, typename S> using copy_const_t = typename copy_const<D, S>::type;

template <typename D, typename S> struct copy_volatile {
    using type = std::conditional_t<std::is_volatile<S>::value, std::add_volatile_t<D>, D>;
};
template <typename D, typename S> using copy_volatile_t = typename copy_volatile<D, S>::type;

template <typename D, typename S> struct copy_cv {
    using type = copy_volatile_t<copy_const_t<D, S>, S>;
};

template <typename D, typename S> using same_cv = copy_cv<std::remove_cv_t<D>, S>;
template <typename D, typename S> using same_cv_t = typename same_cv<D, S>::type;

} // namespace android::uirenderer
