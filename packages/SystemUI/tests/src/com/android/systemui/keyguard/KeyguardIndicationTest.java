/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.keyguard;

import static android.graphics.Color.WHITE;

import static org.junit.Assert.assertEquals;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;
import android.testing.AndroidTestingRunner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
@SmallTest
public class KeyguardIndicationTest extends SysuiTestCase {

    @Test(expected = IllegalStateException.class)
    public void testCannotCreateIndicationWithoutMessageOrIcon() {
        new KeyguardIndication.Builder()
                .setTextColor(ColorStateList.valueOf(WHITE))
                .build();
    }

    @Test(expected = IllegalStateException.class)
    public void testCannotCreateIndicationWithoutColor() {
        new KeyguardIndication.Builder()
                .setMessage("message")
                .build();
    }

    @Test(expected = IllegalStateException.class)
    public void testCannotCreateIndicationWithEmptyMessage() {
        new KeyguardIndication.Builder()
                .setMessage("")
                .setTextColor(ColorStateList.valueOf(WHITE))
                .build();
    }

    @Test
    public void testCreateIndicationWithMessage() {
        final String text = "regular indication";
        final KeyguardIndication indication = new KeyguardIndication.Builder()
                .setMessage(text)
                .setTextColor(ColorStateList.valueOf(WHITE))
                .build();
        assertEquals(text, indication.getMessage());
    }

    @Test
    public void testCreateIndicationWithIcon() {
        final KeyguardIndication indication = new KeyguardIndication.Builder()
                .setIcon(mDrawable)
                .setTextColor(ColorStateList.valueOf(WHITE))
                .build();
        assertEquals(mDrawable, indication.getIcon());
    }

    @Test
    public void testCreateIndicationWithMessageAndIcon() {
        final String text = "indication with msg and icon";
        final KeyguardIndication indication = new KeyguardIndication.Builder()
                .setMessage(text)
                .setIcon(mDrawable)
                .setTextColor(ColorStateList.valueOf(WHITE))
                .build();
        assertEquals(text, indication.getMessage());
        assertEquals(mDrawable, indication.getIcon());
    }

    final Drawable mDrawable = new Drawable() {
        @Override
        public void draw(@NonNull Canvas canvas) { }

        @Override
        public void setAlpha(int alpha) { }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) { }

        @Override
        public int getOpacity() {
            return 0;
        }
    };
}
