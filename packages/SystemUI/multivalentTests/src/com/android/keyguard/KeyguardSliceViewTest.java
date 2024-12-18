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
import android.testing.TestableLooper.RunWithLooper;
import android.view.LayoutInflater;

import androidx.slice.Slice;
import androidx.slice.SliceProvider;
import androidx.slice.SliceSpecs;
import androidx.slice.builders.ListBuilder;
import androidx.slice.widget.RowContent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.keyguard.KeyguardSliceProvider;
import com.android.systemui.res.R;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;

@SmallTest
@RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidJUnit4.class)
public class KeyguardSliceViewTest extends SysuiTestCase {
    private KeyguardSliceView mKeyguardSliceView;
    private Uri mSliceUri;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        LayoutInflater layoutInflater = LayoutInflater.from(getContext());
        mKeyguardSliceView = (KeyguardSliceView) layoutInflater
                .inflate(R.layout.keyguard_slice_view, null);
        mSliceUri = Uri.parse(KeyguardSliceProvider.KEYGUARD_SLICE_URI);
        SliceProvider.setSpecs(new HashSet<>(Collections.singletonList(SliceSpecs.LIST)));
    }

    @Test
    public void showSlice_notifiesListener() {
        ListBuilder builder = new ListBuilder(getContext(), mSliceUri, ListBuilder.INFINITY);
        builder.setHeader(new ListBuilder.HeaderBuilder().setTitle("header title!"));
        Slice slice = builder.build();
        RowContent rowContent = new RowContent(slice.getItemArray()[0], 0);

        AtomicBoolean notified = new AtomicBoolean();
        mKeyguardSliceView.setContentChangeListener(()-> notified.set(true));
        mKeyguardSliceView.showSlice(rowContent, Collections.EMPTY_LIST);
        Assert.assertTrue("Listener should be notified about slice changes.",
                notified.get());
    }

    @Test
    public void showSlice_emptySliceNotifiesListener() {
        AtomicBoolean notified = new AtomicBoolean();
        mKeyguardSliceView.setContentChangeListener(()-> notified.set(true));
        mKeyguardSliceView.showSlice(null, Collections.EMPTY_LIST);
        Assert.assertTrue("Listener should be notified about slice changes.",
                notified.get());
    }

    @Test
    public void hasHeader_readsSliceData() {
        ListBuilder builder = new ListBuilder(getContext(), mSliceUri, ListBuilder.INFINITY);
        mKeyguardSliceView.showSlice(null, Collections.EMPTY_LIST);
        Assert.assertFalse("View should not have a header", mKeyguardSliceView.hasHeader());

        builder.setHeader(new ListBuilder.HeaderBuilder().setTitle("header title!"));
        Slice slice = builder.build();
        RowContent rowContent = new RowContent(slice.getItemArray()[0], 0);
        mKeyguardSliceView.showSlice(rowContent, Collections.EMPTY_LIST);
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
