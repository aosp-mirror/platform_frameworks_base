/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import java.util.ArrayList;

/**
 * An {@link ArrayList} with extended functionality to be used as the children data structure in
 * {@link WindowContainer}.
 */
class WindowList<E> extends ArrayList<E> {

    void addFirst(E e) {
        add(0, e);
    }

    E peekLast() {
        return size() > 0 ? get(size() - 1) : null;
    }

    E peekFirst() {
        return size() > 0 ? get(0) : null;
    }
}
