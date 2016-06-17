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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.annotation.StringRes;
import android.view.MenuItem;

import org.mockito.Mockito;

/**
*
* Test copy of {@link android.view.MenuItem}.
*
* We use abstract so we don't have to implement all the necessary methods from the interface,
* and we use Mockito to just mock out the methods we need.
* To get an instance, use {@link #create(int)}.
*/

public abstract class TestMenuItem implements MenuItem {

    boolean enabled;
    boolean visible;
    @StringRes int title;

    public static TestMenuItem create(int id) {
        final TestMenuItem mockMenuItem = Mockito.mock(TestMenuItem.class,
                Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS));

        return mockMenuItem;
    }

    @Override
    public TestMenuItem setTitle(@StringRes int title) {
        this.title = title;
        return this;
    }

    @Override
    public MenuItem setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    @Override
    public MenuItem setVisible(boolean visible) {
        this.visible = visible;
        return this;
    }

    @Override
    public boolean isVisible() {
        return this.visible;
    }

    @Override
    public boolean isEnabled() {
        return this.enabled;
    }

    public void assertEnabled() {
        assertTrue(this.enabled);
    }

    public void assertDisabled() {
        assertFalse(this.enabled);
    }

    public void assertVisible() {
        assertTrue(this.visible);
    }

    public void assertInvisible() {
        assertFalse(this.visible);
    }

    public void assertTitle(@StringRes int title) {
        assertTrue(this.title == title);
    }
}
