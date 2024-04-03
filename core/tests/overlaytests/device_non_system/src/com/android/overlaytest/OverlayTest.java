/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.overlaytest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.test.InstrumentationRegistry;

import com.android.overlaytest.non_system.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * This test class is to verify overlay behavior for non-system apps.
 */
@RunWith(JUnit4.class)
public class OverlayTest {
    @Test
    public void testStringOverlay() throws Throwable {
        final LayoutInflater inflater = LayoutInflater.from(InstrumentationRegistry.getContext());
        final View layout = inflater.inflate(R.layout.layout, null);
        TextView tv = layout.findViewById(R.id.text_view_id);
        assertNotNull(tv);
        assertEquals("Overlaid", tv.getText().toString());
    }
}
