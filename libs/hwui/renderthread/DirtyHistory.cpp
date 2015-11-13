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
#include "DirtyHistory.h"

namespace android {
namespace uirenderer {
namespace renderthread {

DirtyHistory::DirtyHistory()
        : mBack(DIRTY_HISTORY_SIZE - 1) {
    clear();
}

void DirtyHistory::clear()
{
    for (int i = 0; i < DIRTY_HISTORY_SIZE; i++) {
        mHistory[i].clear();
    }
}

Rect DirtyHistory::get(int index) {
    if (index >= DIRTY_HISTORY_SIZE || index < 0)
        return Rect();
    return mHistory[(1 + mBack + index) % DIRTY_HISTORY_SIZE];
}

Rect DirtyHistory::unionWith(Rect rect, int count) {
    if (rect.isEmpty() || count > DIRTY_HISTORY_SIZE || count < 0)
        return Rect();

    for (int i = 0; i < count; i++) {
        Rect ith = get(i);
        if (ith.isEmpty())
            return Rect();

        // rect union
        rect.left = fminf(rect.left, ith.left);
        rect.top = fminf(rect.top, ith.top);
        rect.right = fmaxf(rect.right, ith.right);
        rect.bottom = fmaxf(rect.bottom, ith.bottom);
    }
    return rect;
}

void DirtyHistory::prepend(Rect rect) {
    if (rect.isEmpty()) {
        mHistory[mBack].clear();
    } else {
        mHistory[mBack].set(rect);
    }
    mBack = (mBack + DIRTY_HISTORY_SIZE - 1) % DIRTY_HISTORY_SIZE;
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
