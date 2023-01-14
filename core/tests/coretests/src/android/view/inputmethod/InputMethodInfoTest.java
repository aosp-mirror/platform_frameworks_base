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

package android.view.inputmethod;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import android.annotation.XmlRes;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.Parcel;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.coretests.R;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class InputMethodInfoTest {

    @Test
    public void testEqualsAndHashCode() throws Exception {
        final InputMethodInfo imi = buildInputMethodForTest(R.xml.ime_meta);
        final InputMethodInfo clone = cloneViaParcel(imi);

        assertThat(clone.equals(imi), is(true));
        assertThat(clone.hashCode(), equalTo(imi.hashCode()));
    }

    @Test
    public void testBooleanAttributes_DefaultValues() throws Exception {
        final InputMethodInfo imi = buildInputMethodForTest(R.xml.ime_meta);

        assertThat(imi.supportsSwitchingToNextInputMethod(), is(false));
        assertThat(imi.isInlineSuggestionsEnabled(), is(false));
        assertThat(imi.supportsInlineSuggestionsWithTouchExploration(), is(false));

        final InputMethodInfo clone = cloneViaParcel(imi);

        assertThat(clone.supportsSwitchingToNextInputMethod(), is(false));
        assertThat(imi.isInlineSuggestionsEnabled(), is(false));
        assertThat(imi.supportsInlineSuggestionsWithTouchExploration(), is(false));
    }

    @Test
    public void testSupportsSwitchingToNextInputMethod() throws Exception {
        final InputMethodInfo imi = buildInputMethodForTest(R.xml.ime_meta_sw_next);

        assertThat(imi.supportsSwitchingToNextInputMethod(), is(true));

        final InputMethodInfo clone = cloneViaParcel(imi);

        assertThat(clone.supportsSwitchingToNextInputMethod(), is(true));
    }

    @Test
    public void testInlineSuggestionsEnabled() throws Exception {
        final InputMethodInfo imi = buildInputMethodForTest(R.xml.ime_meta_inline_suggestions);

        assertThat(imi.isInlineSuggestionsEnabled(), is(true));

        final InputMethodInfo clone = cloneViaParcel(imi);

        assertThat(clone.isInlineSuggestionsEnabled(), is(true));
    }

    @Test
    public void testInlineSuggestionsEnabledWithTouchExploration() throws Exception {
        final InputMethodInfo imi =
                buildInputMethodForTest(R.xml.ime_meta_inline_suggestions_with_touch_exploration);

        assertThat(imi.supportsInlineSuggestionsWithTouchExploration(), is(true));

        final InputMethodInfo clone = cloneViaParcel(imi);

        assertThat(clone.supportsInlineSuggestionsWithTouchExploration(), is(true));
    }

    @Test
    public void testIsVrOnly() throws Exception {
        final InputMethodInfo imi = buildInputMethodForTest(R.xml.ime_meta_vr_only);

        assertThat(imi.isVrOnly(), is(true));

        final InputMethodInfo clone = cloneViaParcel(imi);

        assertThat(clone.isVrOnly(), is(true));
    }

    private InputMethodInfo buildInputMethodForTest(final @XmlRes int metaDataRes)
            throws Exception {
        final Context context = InstrumentationRegistry.getContext();
        final ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.applicationInfo = context.getApplicationInfo();
        serviceInfo.packageName = context.getPackageName();
        serviceInfo.name = "DummyImeForTest";
        serviceInfo.metaData = new Bundle();
        serviceInfo.metaData.putInt(InputMethod.SERVICE_META_DATA, metaDataRes);
        final ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.serviceInfo = serviceInfo;
        return new InputMethodInfo(context, resolveInfo, null /* additionalSubtypesMap */);
    }

    private InputMethodInfo cloneViaParcel(final InputMethodInfo original) {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return InputMethodInfo.CREATOR.createFromParcel(parcel);
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
    }
}
