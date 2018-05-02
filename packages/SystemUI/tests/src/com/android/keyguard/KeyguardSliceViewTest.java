/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.keyguard;

import android.graphics.Color;
import android.net.Uri;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.LayoutInflater;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.keyguard.KeyguardSliceProvider;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;

import androidx.slice.SliceProvider;
import androidx.slice.SliceSpecs;
import androidx.slice.builders.ListBuilder;

@SmallTest
@RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidTestingRunner.class)
public class KeyguardSliceViewTest extends SysuiTestCase {
    private KeyguardSliceView mKeyguardSliceView;
    private Uri mSliceUri;

    @Before
    public void setUp() throws Exception {
        mKeyguardSliceView = (KeyguardSliceView) LayoutInflater.from(getContext())
                .inflate(R.layout.keyguard_status_area, null);
        mSliceUri = Uri.parse(KeyguardSliceProvider.KEYGUARD_SLICE_URI);
        SliceProvider.setSpecs(new HashSet<>(Collections.singletonList(SliceSpecs.LIST)));
    }

    @Test
    public void showSlice_notifiesListener() {
        ListBuilder builder = new ListBuilder(getContext(), mSliceUri);
        AtomicBoolean notified = new AtomicBoolean();
        mKeyguardSliceView.setContentChangeListener((hasHeader)-> {
            notified.set(true);
        });
        mKeyguardSliceView.onChanged(builder.build());
        Assert.assertTrue("Listener should be notified about slice changes.",
                notified.get());
    }

    @Test
    public void showSlice_emptySliceNotifiesListener() {
        AtomicBoolean notified = new AtomicBoolean();
        mKeyguardSliceView.setContentChangeListener((hasHeader)-> {
            notified.set(true);
        });
        mKeyguardSliceView.onChanged(null);
        Assert.assertTrue("Listener should be notified about slice changes.",
                notified.get());
    }

    @Test
    public void hasHeader_readsSliceData() {
        ListBuilder builder = new ListBuilder(getContext(), mSliceUri);
        mKeyguardSliceView.onChanged(builder.build());
        Assert.assertFalse("View should not have a header", mKeyguardSliceView.hasHeader());

        builder.setHeader((ListBuilder.HeaderBuilder headerBuilder) -> {
            headerBuilder.setTitle("header title!");
        });
        mKeyguardSliceView.onChanged(builder.build());
        Assert.assertTrue("View should have a header", mKeyguardSliceView.hasHeader());
    }

    @Test
    public void getTextColor_whiteTextWhenAOD() {
        // Set text color to red since the default is white and test would always pass
        mKeyguardSliceView.setTextColor(Color.RED);
        mKeyguardSliceView.setDarkAmount(0);
        Assert.assertEquals("Should be using regular text color", Color.RED,
                mKeyguardSliceView.getTextColor());
        mKeyguardSliceView.setDarkAmount(1);
        Assert.assertEquals("Should be using AOD text color", Color.WHITE,
                mKeyguardSliceView.getTextColor());
    }
}