/*
 * Copyright (C) 2012 The Android Open Source Project
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

/** @file rs_sampler.rsh
 *  \brief Sampler routines
 *
 *
 */

#ifndef __RS_SAMPLER_RSH__
#define __RS_SAMPLER_RSH__

#if (defined(RS_VERSION) && (RS_VERSION >= 16))

/**
 * @hide
 * Get sampler minification value
 *
 * @param pr
 */
extern rs_sampler_value __attribute__((overloadable))
    rsSamplerGetMinification(rs_sampler s);

/**
 * @hide
 * Get sampler magnification value
 *
 * @param pr
 */
extern rs_sampler_value __attribute__((overloadable))
    rsSamplerGetMagnification(rs_sampler s);

/**
 * @hide
 * Get sampler wrap S value
 *
 * @param pr
 */
extern rs_sampler_value __attribute__((overloadable))
    rsSamplerGetWrapS(rs_sampler s);

/**
 * @hide
 * Get sampler wrap T value
 *
 * @param pr
 */
extern rs_sampler_value __attribute__((overloadable))
    rsSamplerGetWrapT(rs_sampler s);

/**
 * @hide
 * Get sampler anisotropy
 *
 * @param pr
 */
extern float __attribute__((overloadable))
    rsSamplerGetAnisotropy(rs_sampler s);

#endif // (defined(RS_VERSION) && (RS_VERSION >= 16))

#endif // __RS_SAMPLER_RSH__

