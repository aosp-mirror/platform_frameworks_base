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
 * limitations under the License.
 */

package com.android.systemui.qs;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.qs.QSTile.SlashState;
import com.android.systemui.qs.tileimpl.SlashImageView;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class SlashImageViewTest extends SysuiTestCase {
    private TestableSlashImageView mSlashView;

    @Test
    public void testSetNonNullSlashStateCreatesSlashDrawable() {
        SlashState mockState = mock(SlashState.class);
        Drawable mockDrawable = mock(Drawable.class);
        mSlashView = new TestableSlashImageView(mContext);
        assertTrue(mSlashView.getSlashDrawable() == null);

        mSlashView.setState(mockState, mockDrawable);

        assertTrue(mSlashView.getSlashDrawable() != null);
    }

    @Test
    public void testSetNullSlashStateRemovesSlashDrawable() {
        SlashState mockState = mock(SlashState.class);
        Drawable mockDrawable = mock(Drawable.class);
        mSlashView = new TestableSlashImageView(mContext);
        mSlashView.setState(mockState, mockDrawable);

        assertTrue(mSlashView.getSlashDrawable() != null);

        mSlashView.setState(null, mockDrawable);

        assertTrue(mSlashView.getSlashDrawable() == null);
    }

    @Test
    public void testSetNullDrawableRemovesSlashDrawable() {
        SlashState mockState = mock(SlashState.class);
        Drawable mockDrawable = mock(Drawable.class);

        mSlashView = new TestableSlashImageView(mContext);
        mSlashView.setImageDrawable(mockDrawable);
        mSlashView.setState(mockState, mockDrawable);
        mSlashView.setImageDrawable(null);

        assertTrue(mSlashView.getSlashDrawable() == null);
    }

    @Test
    public void testSetImageDrawableUsesDrawableLevel() {
        SlashImageView iv = new SlashImageView(mContext);
        Drawable mockDrawable = mock(Drawable.class);
        when(mockDrawable.getLevel()).thenReturn(2);

        iv.setImageDrawable(mockDrawable);

        // Make sure setting the drawable didn't reset its level to 0
        verify(mockDrawable).setLevel(eq(2));
    }

    // Expose getSlashDrawable
    private static class TestableSlashImageView extends SlashImageView {
        TestableSlashImageView(Context c) {
            super(c);
        }

        private SlashDrawable getSlashDrawable() {
            return mSlash;
        }
    }
}
