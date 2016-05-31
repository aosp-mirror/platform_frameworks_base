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
 */

package com.android.documentsui.testing;

import android.os.Bundle;

import com.android.documentsui.SearchViewManager;

/**
 * Test copy of {@link com.android.documentsui.SearchViewManager}
 *
 * Specficially used to test whether {@link #showMenu(boolean)}
 * and {@link #updateMenu()} are called.
 */
public class TestSearchViewManager extends SearchViewManager {

    boolean updateMenuCalled;
    boolean showMenuCalled;

    public TestSearchViewManager(SearchManagerListener listener, Bundle savedState) {
        super(listener, savedState);
    }

    public TestSearchViewManager() {
        super(null, null);
    }

    @Override
    protected void showMenu(boolean visible) {
        showMenuCalled = true;
    }

    @Override
    public void updateMenu() {
        updateMenuCalled = true;
    }

    public boolean showMenuCalled() {
        return showMenuCalled;
    }

    public boolean updateMenuCalled() {
        return updateMenuCalled;
    }
}
