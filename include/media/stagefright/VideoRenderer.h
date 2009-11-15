/*
 * Copyright (C) 2009 The Android Open Source Project
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

#ifndef VIDEO_RENDERER_H_

#define VIDEO_RENDERER_H_

#include <sys/types.h>

namespace android {

class VideoRenderer {
public:
    virtual ~VideoRenderer() {}

    virtual void render(
            const void *data, size_t size, void *platformPrivate) = 0;

protected:
    VideoRenderer() {}

    VideoRenderer(const VideoRenderer &);
    VideoRenderer &operator=(const VideoRenderer &);
};

}  // namespace android

#endif  // VIDEO_RENDERER_H_
