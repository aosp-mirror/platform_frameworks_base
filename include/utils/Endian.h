/*
 * Copyright (C) 2005 The Android Open Source Project
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

//
// Android endian-ness defines.
//
#ifndef _LIBS_UTILS_ENDIAN_H
#define _LIBS_UTILS_ENDIAN_H

#if defined(HAVE_ENDIAN_H)

#include <endian.h>

#else /*not HAVE_ENDIAN_H*/

#define __BIG_ENDIAN 0x1000
#define __LITTLE_ENDIAN 0x0001

#if defined(HAVE_LITTLE_ENDIAN)
# define __BYTE_ORDER __LITTLE_ENDIAN
#else
# define __BYTE_ORDER __BIG_ENDIAN
#endif

#endif /*not HAVE_ENDIAN_H*/

#endif /*_LIBS_UTILS_ENDIAN_H*/
