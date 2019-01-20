/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.view.contentcapture;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.content.Context;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.Parcel;
import android.view.View;
import android.view.ViewStructure.HtmlInfo;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.view.contentcapture.ViewNode.ViewStructureImpl;
import android.widget.FrameLayout;

import androidx.test.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Locale;

/**
 * Unit test for {@link ViewNode}.
 *
 * <p>To run it: {@code atest FrameworksCoreTests:android.view.contentcapture.ViewNodeTest}
 */
@RunWith(MockitoJUnitRunner.class)
public class ViewNodeTest {

    private final Context mContext = InstrumentationRegistry.getTargetContext();

    @Mock
    private HtmlInfo mHtmlInfoMock;

    @Test
    public void testAutofillIdMethods_orphanView() {
        View view = new View(mContext);
        AutofillId initialId = new AutofillId(42);
        view.setAutofillId(initialId);

        ViewStructureImpl structure = new ViewStructureImpl(view);
        ViewNode node = structure.getNode();

        assertThat(node.getAutofillId()).isEqualTo(initialId);
        assertThat(node.getParentAutofillId()).isNull();

        AutofillId newId = new AutofillId(108);
        structure.setAutofillId(newId);
        assertThat(node.getAutofillId()).isEqualTo(newId);
        assertThat(node.getParentAutofillId()).isNull();

        structure.setAutofillId(new AutofillId(66), 6);
        assertThat(node.getAutofillId()).isEqualTo(new AutofillId(66, 6));
        assertThat(node.getParentAutofillId()).isEqualTo(new AutofillId(66));
    }

    @Test
    public void testAutofillIdMethods_parentedView() {
        FrameLayout parent = new FrameLayout(mContext);
        AutofillId initialParentId = new AutofillId(48);
        parent.setAutofillId(initialParentId);

        View child = new View(mContext);
        AutofillId initialChildId = new AutofillId(42);
        child.setAutofillId(initialChildId);

        parent.addView(child);

        ViewStructureImpl structure = new ViewStructureImpl(child);
        ViewNode node = structure.getNode();

        assertThat(node.getAutofillId()).isEqualTo(initialChildId);
        assertThat(node.getParentAutofillId()).isEqualTo(initialParentId);

        AutofillId newChildId = new AutofillId(108);
        structure.setAutofillId(newChildId);
        assertThat(node.getAutofillId()).isEqualTo(newChildId);
        assertThat(node.getParentAutofillId()).isEqualTo(initialParentId);

        AutofillId newParentId = new AutofillId(15162342);
        parent.setAutofillId(newParentId);
        assertThat(node.getAutofillId()).isEqualTo(newChildId);
        assertThat(node.getParentAutofillId()).isEqualTo(initialParentId);

        structure.setAutofillId(new AutofillId(66), 6);
        assertThat(node.getAutofillId()).isEqualTo(new AutofillId(66, 6));
        assertThat(node.getParentAutofillId()).isEqualTo(new AutofillId(66));
    }

    @Test
    public void testAutofillIdMethods_explicitIdsConstructor() {
        AutofillId initialParentId = new AutofillId(42);
        ViewStructureImpl structure = new ViewStructureImpl(initialParentId, 108, 666);
        ViewNode node = structure.getNode();

        assertThat(node.getAutofillId()).isEqualTo(new AutofillId(initialParentId, 108, 666));
        assertThat(node.getParentAutofillId()).isEqualTo(initialParentId);

        AutofillId newChildId = new AutofillId(108);
        structure.setAutofillId(newChildId);
        assertThat(node.getAutofillId()).isEqualTo(newChildId);
        assertThat(node.getParentAutofillId()).isEqualTo(initialParentId);

        structure.setAutofillId(new AutofillId(66), 6);
        assertThat(node.getAutofillId()).isEqualTo(new AutofillId(66, 6));
        assertThat(node.getParentAutofillId()).isEqualTo(new AutofillId(66));
    }

    @Test
    public void testInvalidSetters() {
        View view = new View(mContext);
        AutofillId initialId = new AutofillId(42);
        view.setAutofillId(initialId);

        ViewStructureImpl structure = new ViewStructureImpl(view);
        ViewNode node = structure.getNode();
        assertThat(node.getAutofillId()).isEqualTo(initialId); // sanity check

        assertThrows(NullPointerException.class, () -> structure.setAutofillId(null));
        assertThat(node.getAutofillId()).isEqualTo(initialId); // invariant

        assertThrows(NullPointerException.class, () -> structure.setAutofillId(null, 666));
        assertThat(node.getAutofillId()).isEqualTo(initialId); // invariant

        assertThrows(NullPointerException.class, () -> structure.setTextIdEntry(null));
        assertThat(node.getTextIdEntry()).isNull();
    }

    @Test
    public void testUnsupportedProperties() {
        View view = new View(mContext);

        ViewStructureImpl structure = new ViewStructureImpl(view);
        ViewNode node = structure.getNode();

        structure.setChildCount(1);
        assertThat(node.getChildCount()).isEqualTo(0);

        structure.addChildCount(1);
        assertThat(node.getChildCount()).isEqualTo(0);

        assertThat(structure.newChild(0)).isNull();
        assertThat(node.getChildCount()).isEqualTo(0);

        assertThat(structure.asyncNewChild(0)).isNull();
        assertThat(node.getChildCount()).isEqualTo(0);

        structure.asyncCommit();
        assertThat(node.getChildCount()).isEqualTo(0);

        structure.setWebDomain("Y U NO SET?");
        assertThat(node.getWebDomain()).isNull();

        assertThat(structure.newHtmlInfoBuilder("WHATEVER")).isNull();

        structure.setHtmlInfo(mHtmlInfoMock);
        assertThat(node.getHtmlInfo()).isNull();

        structure.setDataIsSensitive(true);

        assertThat(structure.getTempRect()).isNull();

        // Graphic properties
        structure.setElevation(6.66f);
        assertThat(node.getElevation()).isWithin(1.0e-10f).of(0f);
        structure.setAlpha(66.6f);
        assertThat(node.getAlpha()).isWithin(1.0e-10f).of(1.0f);
        structure.setTransformation(Matrix.IDENTITY_MATRIX);
        assertThat(node.getTransformation()).isNull();
    }

    @Test
    public void testValidProperties_directly() {
        ViewStructureImpl structure = newSimpleStructure();
        assertSimpleStructure(structure);
        assertSimpleNode(structure.getNode());
    }

    @Test
    public void testValidProperties_throughParcel() {
        ViewStructureImpl structure = newSimpleStructure();
        final ViewNode node = structure.getNode();
        assertSimpleNode(node); // sanity check

        final ViewNode clone = cloneThroughParcel(node);
        assertSimpleNode(clone);
    }

    @Test
    public void testComplexText_directly() {
        ViewStructureImpl structure = newStructureWithComplexText();
        assertStructureWithComplexText(structure);
        assertNodeWithComplexText(structure.getNode());
    }

    @Test
    public void testComplexText_throughParcel() {
        ViewStructureImpl structure = newStructureWithComplexText();
        final ViewNode node = structure.getNode();
        assertNodeWithComplexText(node); // sanity check

        ViewNode clone = cloneThroughParcel(node);
        assertNodeWithComplexText(clone);
    }

    @Test
    public void testVisibility() {
        // Visibility is a special case becase it use flag masks, so we want to make sure it works
        // fine
        View view = new View(mContext);
        ViewStructureImpl structure = new ViewStructureImpl(view);
        ViewNode node = structure.getNode();

        structure.setVisibility(View.VISIBLE);
        assertThat(node.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(cloneThroughParcel(node).getVisibility()).isEqualTo(View.VISIBLE);

        structure.setVisibility(View.GONE);
        assertThat(node.getVisibility()).isEqualTo(View.GONE);
        assertThat(cloneThroughParcel(node).getVisibility()).isEqualTo(View.GONE);

        structure.setVisibility(View.VISIBLE);
        assertThat(node.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(cloneThroughParcel(node).getVisibility()).isEqualTo(View.VISIBLE);

        structure.setVisibility(View.INVISIBLE);
        assertThat(node.getVisibility()).isEqualTo(View.INVISIBLE);
        assertThat(cloneThroughParcel(node).getVisibility()).isEqualTo(View.INVISIBLE);

        structure.setVisibility(View.INVISIBLE | View.GONE);
        assertThat(node.getVisibility()).isEqualTo(View.INVISIBLE | View.GONE);
        assertThat(cloneThroughParcel(node).getVisibility()).isEqualTo(View.INVISIBLE | View.GONE);


        final int invalidValue = Math.max(Math.max(View.VISIBLE, View.INVISIBLE), View.GONE) * 2;
        structure.setVisibility(View.VISIBLE);
        structure.setVisibility(invalidValue); // should be ignored
        assertThat(node.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(cloneThroughParcel(node).getVisibility()).isEqualTo(View.VISIBLE);

        structure.setVisibility(View.GONE | invalidValue);
        assertThat(node.getVisibility()).isEqualTo(View.GONE);
        assertThat(cloneThroughParcel(node).getVisibility()).isEqualTo(View.GONE);
    }

    /**
     * Creates a {@link ViewStructureImpl} that can be asserted through
     * {@link #assertSimpleNode(ViewNode)}.
     */
    private ViewStructureImpl newSimpleStructure() {
        View view = new View(mContext);
        view.setAutofillId(new AutofillId(42));

        ViewStructureImpl structure = new ViewStructureImpl(view);

        // Basic properties
        structure.setText("Text is set!");
        structure.setClassName("Classy!");
        structure.setContentDescription("Described I am!");
        structure.setVisibility(View.INVISIBLE);

        // Autofill properties
        structure.setAutofillType(View.AUTOFILL_TYPE_TEXT);
        structure.setAutofillHints(new String[] { "Auto", "Man" });
        structure.setAutofillOptions(new String[] { "Maybe" });
        structure.setAutofillValue(AutofillValue.forText("Malkovich"));

        // Extra text properties
        structure.setMinTextEms(6);
        structure.setMaxTextLength(66);
        structure.setMaxTextEms(666);
        structure.setInputType(42);
        structure.setTextIdEntry("TEXT, Y U NO ENTRY?");
        structure.setLocaleList(new LocaleList(Locale.US, Locale.ENGLISH));

        // Resource id
        structure.setId(16, "package.name", "type.name", "entry.name");

        // Dimensions
        structure.setDimens(4, 8, 15, 16, 23, 42);

        // Boolean properties
        structure.setAssistBlocked(true);
        structure.setEnabled(true);
        structure.setClickable(true);
        structure.setLongClickable(true);
        structure.setContextClickable(true);
        structure.setFocusable(true);
        structure.setFocused(true);
        structure.setAccessibilityFocused(true);
        structure.setChecked(true);
        structure.setActivated(true);
        structure.setOpaque(true);

        // Bundle
        assertThat(structure.hasExtras()).isFalse();
        final Bundle bundle = structure.getExtras();
        assertThat(bundle).isNotNull();
        bundle.putString("Marlon", "Bundle");
        assertThat(structure.hasExtras()).isTrue();
        return structure;
    }

    /**
     * Asserts the properties of a {@link ViewNode} that was created by
     * {@link #newSimpleStructure()}.
     */
    private void assertSimpleNode(ViewNode node) {

        // Basic properties
        assertThat(node.getAutofillId()).isEqualTo(new AutofillId(42));
        assertThat(node.getParentAutofillId()).isNull();
        assertThat(node.getText()).isEqualTo("Text is set!");
        assertThat(node.getClassName()).isEqualTo("Classy!");
        assertThat(node.getContentDescription().toString()).isEqualTo("Described I am!");
        assertThat(node.getVisibility()).isEqualTo(View.INVISIBLE);

        // Autofill properties
        assertThat(node.getAutofillType()).isEqualTo(View.AUTOFILL_TYPE_TEXT);
        assertThat(node.getAutofillHints()).asList().containsExactly("Auto", "Man").inOrder();
        assertThat(node.getAutofillOptions()).asList().containsExactly("Maybe").inOrder();
        assertThat(node.getAutofillValue().getTextValue()).isEqualTo("Malkovich");

        // Extra text properties
        assertThat(node.getMinTextEms()).isEqualTo(6);
        assertThat(node.getMaxTextLength()).isEqualTo(66);
        assertThat(node.getMaxTextEms()).isEqualTo(666);
        assertThat(node.getInputType()).isEqualTo(42);
        assertThat(node.getTextIdEntry()).isEqualTo("TEXT, Y U NO ENTRY?");
        assertThat(node.getLocaleList()).isEqualTo(new LocaleList(Locale.US, Locale.ENGLISH));

        // Resource id
        assertThat(node.getId()).isEqualTo(16);
        assertThat(node.getIdPackage()).isEqualTo("package.name");
        assertThat(node.getIdType()).isEqualTo("type.name");
        assertThat(node.getIdEntry()).isEqualTo("entry.name");

        // Dimensions
        assertThat(node.getLeft()).isEqualTo(4);
        assertThat(node.getTop()).isEqualTo(8);
        assertThat(node.getScrollX()).isEqualTo(15);
        assertThat(node.getScrollY()).isEqualTo(16);
        assertThat(node.getWidth()).isEqualTo(23);
        assertThat(node.getHeight()).isEqualTo(42);

        // Boolean properties
        assertThat(node.isAssistBlocked()).isTrue();
        assertThat(node.isEnabled()).isTrue();
        assertThat(node.isClickable()).isTrue();
        assertThat(node.isLongClickable()).isTrue();
        assertThat(node.isContextClickable()).isTrue();
        assertThat(node.isFocusable()).isTrue();
        assertThat(node.isFocused()).isTrue();
        assertThat(node.isAccessibilityFocused()).isTrue();
        assertThat(node.isChecked()).isTrue();
        assertThat(node.isActivated()).isTrue();
        assertThat(node.isOpaque()).isTrue();

        // Bundle
        final Bundle bundle = node.getExtras();
        assertThat(bundle).isNotNull();
        assertThat(bundle.size()).isEqualTo(1);
        assertThat(bundle.getString("Marlon")).isEqualTo("Bundle");
    }

    /**
     * Asserts the properties of a {@link ViewStructureImpl} that was created by
     * {@link #newSimpleStructure()}.
     */
    private void assertSimpleStructure(ViewStructureImpl structure) {
        assertThat(structure.getAutofillId()).isEqualTo(new AutofillId(42));
        assertThat(structure.getText()).isEqualTo("Text is set!");

        // Bundle
        final Bundle bundle = structure.getExtras();
        assertThat(bundle.size()).isEqualTo(1);
        assertThat(bundle.getString("Marlon")).isEqualTo("Bundle");
    }

    /**
     * Creates a {@link ViewStructureImpl} with "complex" text properties (such as selection); it
     * can be asserted through {@link #assertNodeWithComplexText(ViewNode)}.
     */
    private ViewStructureImpl newStructureWithComplexText() {
        View view = new View(mContext);
        ViewStructureImpl structure = new ViewStructureImpl(view);
        structure.setText("IGNORE ME!");
        structure.setText("Now we're talking!", 4, 8);
        structure.setHint("Soylent Green is SPOILER ALERT");
        structure.setTextStyle(15.0f, 16, 23, 42);
        structure.setTextLines(new int[] {4,  8, 15} , new int[] {16, 23, 42});
        return structure;
    }

    /**
     * Asserts the properties of a {@link ViewNode} that was created by
     * {@link #newStructureWithComplexText()}.
     */
    private void assertNodeWithComplexText(ViewNode node) {
        assertThat(node.getText()).isEqualTo("Now we're talking!");
        assertThat(node.getTextSelectionStart()).isEqualTo(4);
        assertThat(node.getTextSelectionEnd()).isEqualTo(8);
        assertThat(node.getHint()).isEqualTo("Soylent Green is SPOILER ALERT");
        assertThat(node.getTextSize()).isWithin(1.0e-10f).of(15.0f);
        assertThat(node.getTextColor()).isEqualTo(16);
        assertThat(node.getTextBackgroundColor()).isEqualTo(23);
        assertThat(node.getTextStyle()).isEqualTo(42);
        assertThat(node.getTextLineCharOffsets()).asList().containsExactly(4, 8, 15).inOrder();
        assertThat(node.getTextLineBaselines()).asList().containsExactly(16, 23, 42).inOrder();
    }

    /**
     * Asserts the properties of a {@link ViewStructureImpl} that was created by
     * {@link #newStructureWithComplexText()}.
     */
    private void assertStructureWithComplexText(ViewStructureImpl structure) {
        assertThat(structure.getText()).isEqualTo("Now we're talking!");
        assertThat(structure.getTextSelectionStart()).isEqualTo(4);
        assertThat(structure.getTextSelectionEnd()).isEqualTo(8);
        assertThat(structure.getHint()).isEqualTo("Soylent Green is SPOILER ALERT");
    }

    private ViewNode cloneThroughParcel(ViewNode node) {
        Parcel parcel = Parcel.obtain();

        try {
            // Write to parcel
            parcel.setDataPosition(0); // Sanity / paranoid check
            ViewNode.writeToParcel(parcel, node, 0);

            // Read from parcel
            parcel.setDataPosition(0);
            ViewNode clone = ViewNode.readFromParcel(parcel);
            assertThat(clone).isNotNull();
            return clone;
        } finally {
            parcel.recycle();
        }
    }
}
