/*
 * Copyright 2006 The Android Open Source Project
 *
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */

#include "Movie.h"
#include "SkStream.h"
#include "SkTRegistry.h"

typedef SkTRegistry<Movie*(*)(SkStreamRewindable*)> MovieReg;

Movie* Movie::DecodeStream(SkStreamRewindable* stream) {
    const MovieReg* curr = MovieReg::Head();
    while (curr) {
        Movie* movie = curr->factory()(stream);
        if (movie) {
            return movie;
        }
        // we must rewind only if we got nullptr, since we gave the stream to the
        // movie, who may have already started reading from it
        stream->rewind();
        curr = curr->next();
    }
    return nullptr;
}
