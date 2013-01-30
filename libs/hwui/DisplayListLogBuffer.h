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

#ifndef ANDROID_HWUI_DISPLAY_LIST_LOG_BUFFER_H
#define ANDROID_HWUI_DISPLAY_LIST_LOG_BUFFER_H

#include <utils/Singleton.h>

#include <stdio.h>

namespace android {
namespace uirenderer {

class DisplayListLogBuffer: public Singleton<DisplayListLogBuffer> {
    DisplayListLogBuffer();
    ~DisplayListLogBuffer();

    friend class Singleton<DisplayListLogBuffer>;

public:
    void writeCommand(int level, const char* label);
    void outputCommands(FILE *file);

    bool isEmpty() {
        return (mStart == mEnd);
    }

    struct OpLog {
        int level;
        const char* label;
    };

private:
    OpLog* mBufferFirst; // where the memory starts
    OpLog* mStart;       // where the current command stream starts
    OpLog* mEnd;         // where the current commands end
    OpLog* mBufferLast;  // where the buffer memory ends

};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_DISPLAY_LIST_LOG_BUFFER_H
