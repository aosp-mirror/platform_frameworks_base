/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.graphics;

import static org.junit.Assert.assertEquals;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests of {@link ColorStateList}
 */

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ColorStateListTest {

    private Resources mResources;
    private int mFailureColor;

    @Before
    public void setUp() throws Exception {
        mResources = InstrumentationRegistry.getInstrumentation().getContext().getResources();
        mFailureColor = mResources.getColor(R.color.failColor);
    }

    @Test
    public void testStateIsInList() throws Exception {
        ColorStateList colorStateList = mResources.getColorStateList(R.color.color1);
        int[] focusedState = {android.R.attr.state_focused};
        int focusColor = colorStateList.getColorForState(focusedState, R.color.failColor);
        assertEquals(mResources.getColor(R.color.testcolor1), focusColor);
    }

    @Test
    public void testStateIsInList_proto() throws Exception {
        ColorStateList colorStateList = recreateFromProto(
                mResources.getColorStateList(R.color.color1));
        int[] focusedState = {android.R.attr.state_focused};
        int focusColor = colorStateList.getColorForState(focusedState, R.color.failColor);
        assertEquals(mResources.getColor(R.color.testcolor1), focusColor);
    }

    @Test
    public void testEmptyState() throws Exception {
        ColorStateList colorStateList = mResources.getColorStateList(R.color.color1);
        int[] emptyState = {};
        int defaultColor = colorStateList.getColorForState(emptyState, mFailureColor);
        assertEquals(mResources.getColor(R.color.testcolor2), defaultColor);
    }

    @Test
    public void testEmptyState_proto() throws Exception {
        ColorStateList colorStateList = recreateFromProto(
                mResources.getColorStateList(R.color.color1));
        int[] emptyState = {};
        int defaultColor = colorStateList.getColorForState(emptyState, mFailureColor);
        assertEquals(mResources.getColor(R.color.testcolor2), defaultColor);
    }

    @Test
    public void testGetColor() throws Exception {
        int defaultColor = mResources.getColor(R.color.color1);
        assertEquals(mResources.getColor(R.color.testcolor2), defaultColor);
    }

    @Test
    public void testGetColorWhenListHasNoDefault() throws Exception {
        int defaultColor = mResources.getColor(R.color.color_no_default);
        assertEquals(mResources.getColor(R.color.testcolor1), defaultColor);
    }

    @Test
    public void testLstar() throws Exception {
        var cl = ColorStateList.valueOf(mResources.getColor(R.color.testcolor2)).withLStar(50.0f);
        int defaultColor = mResources.getColor(R.color.color_with_lstar);
        assertEquals(cl.getDefaultColor(), defaultColor);
    }

    private ColorStateList recreateFromProto(ColorStateList colorStateList) throws Exception {
        ProtoOutputStream out = new ProtoOutputStream();
        colorStateList.writeToProto(out);
        ProtoInputStream in = new ProtoInputStream(out.getBytes());
        return ColorStateList.createFromProto(in);
    }
}
