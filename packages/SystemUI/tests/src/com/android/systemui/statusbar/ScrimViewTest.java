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

package com.android.systemui.statusbar;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

import android.graphics.drawable.VectorDrawable;
import android.testing.AndroidTestingRunner;
import android.view.View;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.ScrimView;

import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static junit.framework.Assert.assertEquals;

@RunWith(AndroidTestingRunner.class)
public class ScrimViewTest extends SysuiTestCase {

    ScrimView mView;

    @Before
    public void setUp() {
        mView = new ScrimView(getContext());
        mView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        mView.layout(0, 0, 1920, 1080);
    }

    @Test
    public void testSetDrawable_UpdateDrawable() {
        Drawable drawable = new ColorDrawable(Color.GREEN);
        mView.setDrawable(drawable);
        assertEquals(drawable, mView.getDrawable());
    }

    @Test
    public void testSetViewAlpha_propagatesToDrawable() {
        float alpha = 0.5f;
        mView.setViewAlpha(alpha);
        assertEquals(mView.getViewAlpha(), alpha);
    }

    @Test
    public void testOnDraw_ExcludeRectDrawable() {
        mView.setExcludedArea(new Rect(10, 10, 20, 20));
        Canvas canvas = mock(Canvas.class);
        mView.onDraw(canvas);
        // One time for each rect side
        verify(canvas, times(4)).clipRect(anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    public void setTint_set() {
        int tint = Color.BLUE;
        mView.setTint(tint);
        assertEquals(mView.getTint(), tint);
    }
}
