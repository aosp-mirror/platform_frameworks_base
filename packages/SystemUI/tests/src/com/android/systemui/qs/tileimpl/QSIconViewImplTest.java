/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs.tileimpl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.drawable.Drawable;
import android.service.quicksettings.Tile;
import android.testing.AndroidTestingRunner;
import android.testing.UiThreadTest;
import android.widget.ImageView;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.qs.QSTile.Icon;
import com.android.systemui.plugins.qs.QSTile.State;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
@UiThreadTest
@SmallTest
public class QSIconViewImplTest extends SysuiTestCase {

    private QSIconViewImpl mIconView;
    private static int RES_ID = 1;

    @Before
    public void setup() {
        mIconView = new QSIconViewImpl(mContext);
    }

    @Test
    public void testNoFirstAnimation() {
        ImageView iv = mock(ImageView.class);
        State s = new State();
        when(iv.isShown()).thenReturn(true);

        // No current icon, only the static drawable should be used.
        s.icon = mock(Icon.class);
        when(iv.getDrawable()).thenReturn(null);
        mIconView.updateIcon(iv, s, true);
        verify(s.icon, never()).getDrawable(any());
        verify(s.icon).getInvisibleDrawable(any());

        // Has icon, should use the standard (animated) form.
        s.icon = mock(Icon.class);
        when(iv.getDrawable()).thenReturn(mock(Drawable.class));
        mIconView.updateIcon(iv, s, true);
        verify(s.icon).getDrawable(any());
        verify(s.icon, never()).getInvisibleDrawable(any());
    }

    @Test
    public void testNoFirstFade() {
        ImageView iv = mock(ImageView.class);
        State s = new State();
        s.state = Tile.STATE_ACTIVE;
        int desiredColor = mIconView.getColor(s.state);
        when(iv.isShown()).thenReturn(true);

        mIconView.setIcon(iv, s, true);
        verify(iv).setImageTintList(argThat(stateList -> stateList.getColors()[0] == desiredColor));
    }

    @Test
    public void testStateSetCorrectly_toString() {
        ImageView iv = mock(ImageView.class);
        State s = new State();
        s.state = Tile.STATE_ACTIVE;
        int desiredColor = mIconView.getColor(s.state);
        Icon i = mock(Icon.class);
        s.icon = i;
        when(i.toString()).thenReturn("MOCK ICON");
        mIconView.setIcon(iv, s, false);

        assertEquals("QSIconViewImpl[state=" + Tile.STATE_ACTIVE + ", tint=" + desiredColor
                + ", lastIcon=" + i.toString() + "]", mIconView.toString());
    }

    @Test
    public void testIconNotSet_toString() {
        assertFalse(mIconView.toString().contains("lastIcon"));
    }
}
