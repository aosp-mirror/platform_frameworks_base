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


#ifndef ANDROID_TYPEFACE_IMPL_H
#define ANDROID_TYPEFACE_IMPL_H

#include <androidfw/AssetManager.h>

#ifdef USE_MINIKIN
#include <minikin/FontCollection.h>
#endif

namespace android {

#ifdef USE_MINIKIN
struct TypefaceImpl {
    FontCollection *fFontCollection;
    FontStyle fStyle;
};

// Note: it would be cleaner if the following functions were member
// functions (static or otherwise) of the TypefaceImpl class. However,
// that can't be easily accommodated in the case where TypefaceImpl
// is just a pointer to SkTypeface, in the non-USE_MINIKIN case.
// TODO: when #ifdef USE_MINIKIN is removed, move to member functions.

TypefaceImpl* TypefaceImpl_resolveDefault(TypefaceImpl* src);
#else
typedef SkTypeface TypefaceImpl;
#endif

TypefaceImpl* TypefaceImpl_createFromTypeface(TypefaceImpl* src, SkTypeface::Style style);

TypefaceImpl* TypefaceImpl_createFromName(const char* name, SkTypeface::Style style);

TypefaceImpl* TypefaceImpl_createFromFile(const char* filename);

TypefaceImpl* TypefaceImpl_createFromAsset(Asset* asset);

void TypefaceImpl_unref(TypefaceImpl* face);

int TypefaceImpl_getStyle(TypefaceImpl* face);

}

#endif  // ANDROID_TYPEFACE_IMPL_H