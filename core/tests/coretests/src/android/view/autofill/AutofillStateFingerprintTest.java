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

package android.view.autofill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class AutofillStateFingerprintTest {

    private static final Context sContext = ApplicationProvider.getApplicationContext();

    private static final int MAGIC_AUTOFILL_NUMBER = 1000;

    private AutofillStateFingerprint mAutofillStateFingerprint =
            AutofillStateFingerprint.createInstance();

    @Test
    public void testSameFingerprintsForTextView() throws Exception {
        TextView tv = new TextView(sContext);
        tv.setHint("Password");
        tv.setSingleLine(true);
        tv.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tv.setImeOptions(EditorInfo.IME_FLAG_NAVIGATE_NEXT);
        fillViewProperties(tv);

        // Create a copy Text View, and compare both id's
        View tvCopy = copySelectiveViewAttributes(tv);
        assertIdsEqual(tv, tvCopy);
    }

    @Test
    public void testDifferentFingerprintsForTextViewWithDifferentHint() throws Exception {
        TextView tv = new TextView(sContext);
        tv.setHint("Password");
        tv.setSingleLine(true);
        tv.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tv.setImeOptions(EditorInfo.IME_FLAG_NAVIGATE_NEXT);
        fillViewProperties(tv);

        TextView tvCopy = (TextView) copySelectiveViewAttributes(tv);
        tvCopy.setHint("what a useless different hint");
        assertIdsNotEqual(tv, tvCopy);
    }

    @Test
    public void testSameFingerprintsForNonTextView() throws Exception {
        View v = new View(sContext);
        fillViewProperties(v);

        // Create a copy Text View, and compare both id's
        View copy = copySelectiveViewAttributes(v);
        assertIdsEqual(v, copy);
    }

    @Test
    public void testDifferentFingerprintsForNonTextViewWithDifferentVisibility() throws Exception {
        View v = new View(sContext);
        fillViewProperties(v);

        View copy = copySelectiveViewAttributes(v);
        copy.setVisibility(View.GONE);
        assertIdsNotEqual(v, copy);
    }

    private void assertIdsEqual(View v1, View v2) {
        assertEquals(mAutofillStateFingerprint.getEphemeralFingerprintId(v1, 0),
                mAutofillStateFingerprint.getEphemeralFingerprintId(v2, 0));
    }

    private void assertIdsNotEqual(View v1, View v2) {
        assertNotEquals(mAutofillStateFingerprint.getEphemeralFingerprintId(v1, 0),
                mAutofillStateFingerprint.getEphemeralFingerprintId(v2, 0));
    }

    private void fillViewProperties(View view) {
        // Fill in relevant view properties
        view.setContentDescription("ContentDesc");
        view.setTooltipText("TooltipText");
        view.setAutofillHints(new String[] {"password"});
        view.setVisibility(View.VISIBLE);
        view.setLeft(20);
        view.setRight(200);
        view.setTop(20);
        view.setBottom(200);
        view.setPadding(0, 1, 2, 3);
    }

    // Only copy interesting view attributes, particularly the view attributes that are critical
    // for calculating fingerprint. Keep Autofill Id different.
    private View copySelectiveViewAttributes(View view) {
        View copy;
        if (view instanceof TextView) {
            copy = new TextView(sContext);
            copySelectiveTextViewAttributes((TextView) view, (TextView) copy);
        } else {
            copy = new View(sContext) {
                public @AutofillType int getAutofillType() {
                    return view.getAutofillType();
                }
            };
        }
        // Copy over interested view properties.
        // Keep the order same as with the tested code for easier clarity.
        copy.setVisibility(view.getVisibility());
        copy.setAutofillHints(view.getAutofillHints());
        copy.setContentDescription(view.getContentDescription());
        copy.setTooltip(view.getTooltipText());

        copy.setRight(view.getRight());
        copy.setLeft(view.getLeft());
        copy.setTop(view.getTop());
        copy.setBottom(view.getBottom());
        copy.setPadding(view.getPaddingLeft(), view.getPaddingTop(),
                view.getPaddingRight(), view.getPaddingBottom());

        // DO not copy over autofill id
        AutofillId newId = new AutofillId(view.getAutofillId().getViewId() + MAGIC_AUTOFILL_NUMBER);
        copy.setAutofillId(newId);
        return copy;
    }

    private void copySelectiveTextViewAttributes(TextView fromView, TextView toView) {
        toView.setInputType(fromView.getInputType());
        toView.setHint(fromView.getHint());
        toView.setSingleLine(fromView.isSingleLine());
        toView.setImeOptions(fromView.getImeOptions());
    }
}
