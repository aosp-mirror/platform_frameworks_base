/*
 * Copyright (C) 2016 The Android Open Source Project
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
 *
 */

#ifndef AUDIOPLAY_H_
#define AUDIOPLAY_H_

#include <string.h>

namespace audioplay {

// Initializes the engine with an example of the type of WAV clip to play.
// All buffers passed to playClip are assumed to be in the same format.
bool create(const uint8_t* exampleClipBuf, int exampleClipBufSize);

// Plays a WAV contained in buf.
// Should not be called while a clip is still playing.
bool playClip(const uint8_t* buf, int size);
void setPlaying(bool isPlaying);
void destroy();

}

#endif // AUDIOPLAY_H_
