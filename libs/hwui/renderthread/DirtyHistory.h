/*
 * Copyright (C) 2015 The Android Open Source Project
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
#ifndef DIRTYHISTORY_H
#define DIRTYHISTORY_H

#include <Rect.h>

namespace android {
namespace uirenderer {
namespace renderthread {

#define DIRTY_HISTORY_SIZE 4

class DirtyHistory {
public:
    DirtyHistory();
    ~DirtyHistory() {}

    Rect get(int index);
    Rect unionWith(Rect rect, int count);
    void prepend(Rect rect);
    void clear();
private:
    Rect mHistory[DIRTY_HISTORY_SIZE];
    int mBack;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */

#endif /* DIRTYHISTORY_H */
