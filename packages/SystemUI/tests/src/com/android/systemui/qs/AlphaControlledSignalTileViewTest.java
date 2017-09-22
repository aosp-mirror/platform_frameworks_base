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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.test.suitebuilder.annotation.SmallTest;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.qs.AlphaControlledSignalTileView.AlphaControlledSlashDrawable;
import com.android.systemui.qs.AlphaControlledSignalTileView.AlphaControlledSlashImageView;
import org.junit.Test;

@SmallTest
public class AlphaControlledSignalTileViewTest extends SysuiTestCase {

    private AlphaControlledSignalTileView mTileView;

    @Test
    public void testTileView_createsAlphaControlledSlashImageView() {
        mTileView = new AlphaControlledSignalTileView(mContext);

        assertTrue(mTileView.createSlashImageView(mContext)
                instanceof AlphaControlledSlashImageView);
    }

    /// AlphaControlledSlashImageView tests
    @Test
    public void testSlashImageView_createsAlphaControlledSlashDrawable() {
        TestableSlashImageView iv = new TestableSlashImageView(mContext);

        iv.ensureSlashDrawable();
        assertTrue(iv.getSlashDrawable() instanceof AlphaControlledSlashDrawable);
    }

    /// AlphaControlledSlashDrawable tests
    @Test
    public void testSlashDrawable_doesNotSetTintList() {
        Drawable mockDrawable = mock(Drawable.class);
        AlphaControlledSlashDrawable drawable = new AlphaControlledSlashDrawable(mockDrawable);
        ColorStateList list = ColorStateList.valueOf(0xffffff);
        drawable.setTintList(list);
        verify(mockDrawable, never()).setTintList(any());
    }

    @Test
    public void testSlashDrawable_setsFinalTintList() {
        Drawable mockDrawable = mock(Drawable.class);
        AlphaControlledSlashDrawable drawable = new AlphaControlledSlashDrawable(mockDrawable);
        ColorStateList list = ColorStateList.valueOf(0xffffff);
        drawable.setFinalTintList(list);
        verify(mockDrawable, atLeastOnce()).setTintList(list);
    }

    // Expose getSlashDrawable
    private static class TestableSlashImageView extends AlphaControlledSlashImageView {
        TestableSlashImageView(Context c) {
            super(c);
        }

        private SlashDrawable getSlashDrawable() {
            return mSlash;
        }

        @Override
        protected void setSlash(SlashDrawable slash) {
            super.setSlash(slash);
        }
    }
}
