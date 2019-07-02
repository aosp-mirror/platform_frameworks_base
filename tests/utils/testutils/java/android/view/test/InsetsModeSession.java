/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.view.test;

import android.view.ViewRootImpl;

/**
 * Session to set insets mode for {@link ViewRootImpl#sNewInsetsMode}.
 */
public class InsetsModeSession implements AutoCloseable {

    private int mOldMode;

    public InsetsModeSession(int flag) {
        mOldMode = ViewRootImpl.sNewInsetsMode;
        ViewRootImpl.sNewInsetsMode = flag;
    }

    @Override
    public void close() throws Exception {
        ViewRootImpl.sNewInsetsMode = mOldMode;
    }
}
