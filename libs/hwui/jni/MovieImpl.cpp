/*
 * Copyright 2011 Google Inc.
 *
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
#include "Movie.h"
#include "SkBitmap.h"
#include "SkStream.h"
#include "SkTypes.h"

// We should never see this in normal operation since our time values are
// 0-based. So we use it as a sentinel.
#define UNINITIALIZED_MSEC ((Movie::MSec)-1)

Movie::Movie()
{
    fInfo.fDuration = UNINITIALIZED_MSEC;  // uninitialized
    fCurrTime = UNINITIALIZED_MSEC; // uninitialized
    fNeedBitmap = true;
}

void Movie::ensureInfo()
{
    if (fInfo.fDuration == UNINITIALIZED_MSEC && !this->onGetInfo(&fInfo))
        memset(&fInfo, 0, sizeof(fInfo));   // failure
}

Movie::MSec Movie::duration()
{
    this->ensureInfo();
    return fInfo.fDuration;
}

int Movie::width()
{
    this->ensureInfo();
    return fInfo.fWidth;
}

int Movie::height()
{
    this->ensureInfo();
    return fInfo.fHeight;
}

int Movie::isOpaque()
{
    this->ensureInfo();
    return fInfo.fIsOpaque;
}

bool Movie::setTime(Movie::MSec time)
{
    Movie::MSec dur = this->duration();
    if (time > dur)
        time = dur;

    bool changed = false;
    if (time != fCurrTime)
    {
        fCurrTime = time;
        changed = this->onSetTime(time);
        fNeedBitmap |= changed;
    }
    return changed;
}

const SkBitmap& Movie::bitmap()
{
    if (fCurrTime == UNINITIALIZED_MSEC)    // uninitialized
        this->setTime(0);

    if (fNeedBitmap)
    {
        if (!this->onGetBitmap(&fBitmap))   // failure
            fBitmap.reset();
        fNeedBitmap = false;
    }
    return fBitmap;
}

////////////////////////////////////////////////////////////////////

Movie* Movie::DecodeMemory(const void* data, size_t length) {
    SkMemoryStream stream(data, length, false);
    return Movie::DecodeStream(&stream);
}

Movie* Movie::DecodeFile(const char path[]) {
    std::unique_ptr<SkStreamRewindable> stream = SkStream::MakeFromFile(path);
    return stream ? Movie::DecodeStream(stream.get()) : nullptr;
}
