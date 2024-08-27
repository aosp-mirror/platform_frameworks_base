/*
 * Copyright 2017 The Android Open Source Project
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

package android.view.accessibility;

import static junit.framework.Assert.assertEquals;

import static org.hamcrest.Matchers.emptyCollectionOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import android.os.Parcel;
import android.util.ArraySet;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import com.android.internal.util.CollectionUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class AccessibilityNodeInfoTest {
    // The number of fields tested in the corresponding CTS AccessibilityNodeInfoTest:
    // See fullyPopulateAccessibilityNodeInfo, assertEqualsAccessibilityNodeInfo,
    // and assertAccessibilityNodeInfoCleared in that class.
    private static final int NUM_MARSHALLED_PROPERTIES = 44;

    /**
     * The number of properties that are purposely not marshalled
     * mOriginalText - Used when resolving clickable spans; intentionally not parceled
     * mSealed - Unparceling sets the sealed bit
     * mConnectionId - Set after unparceling. Actually is parceled, but probably shouldn't be.
     */
    private static final int NUM_NONMARSHALLED_PROPERTIES = 3;

    // The number of flags held in boolean properties. Their values should also be double-checked
    // in the methods above.
    private static final int NUM_BOOLEAN_PROPERTIES = 27;

    @Test
    public void testStandardActions_serializationFlagIsValid() {
        AccessibilityAction brokenStandardAction = CollectionUtils.find(
                new ArrayList<>(AccessibilityAction.sStandardActions),
                action -> Long.bitCount(action.mSerializationFlag) != 1);
        if (brokenStandardAction != null) {
            String message = "Invalid serialization flag(0x"
                    + Long.toHexString(brokenStandardAction.mSerializationFlag)
                    + ") in " + brokenStandardAction;
            if (brokenStandardAction.mSerializationFlag == 0L) {
                message += "\nThis is likely due to an overflow";
            }
            fail(message);
        }

        brokenStandardAction = CollectionUtils.find(
                new ArrayList<>(AccessibilityAction.sStandardActions),
                action -> Integer.bitCount(action.getId()) == 1
                        && action.getId() <= AccessibilityNodeInfo.LAST_LEGACY_STANDARD_ACTION
                        && action.getId() != action.mSerializationFlag);
        if (brokenStandardAction != null) {
            fail("Serialization flag(0x"
                    + Long.toHexString(brokenStandardAction.mSerializationFlag)
                    + ") is different from legacy action id(0x"
                    + Integer.toHexString(brokenStandardAction.getId())
                    + ") in " + brokenStandardAction);
        }
    }

    @Test
    public void testStandardActions_idsAreUnique() {
        ArraySet<AccessibilityAction> actions = AccessibilityAction.sStandardActions;
        for (int i = 0; i < actions.size(); i++) {
            for (int j = 0; j < i; j++) {
                int id = actions.valueAt(i).getId();
                if (id == actions.valueAt(j).getId()) {
                    fail("Id 0x" + Integer.toHexString(id)
                            + " is duplicated for standard actions #" + i + " and #" + j);
                }
            }
        }
    }

    @Test
    public void testStandardActions_allComeThroughParceling() {
        for (AccessibilityAction action : AccessibilityAction.sStandardActions) {
            final AccessibilityNodeInfo nodeWithAction = AccessibilityNodeInfo.obtain();
            nodeWithAction.addAction(action);
            assertThat(nodeWithAction.getActionList(), hasItem(action));
            final Parcel parcel = Parcel.obtain();
            nodeWithAction.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            final AccessibilityNodeInfo unparceledNode =
                    AccessibilityNodeInfo.CREATOR.createFromParcel(parcel);
            assertThat(unparceledNode.getActionList(), hasItem(action));
        }
    }

    @Test
    public void testEmptyListOfActions_parcelsCorrectly() {
        // Also set text, as if there's nothing else in the parcel it can unparcel even with
        // a bug present.
        final String text = "text";
        final AccessibilityNodeInfo nodeWithEmptyActionList = AccessibilityNodeInfo.obtain();
        nodeWithEmptyActionList.addAction(AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);
        nodeWithEmptyActionList.removeAction(AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);
        nodeWithEmptyActionList.setText(text);
        final Parcel parcel = Parcel.obtain();
        nodeWithEmptyActionList.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        final AccessibilityNodeInfo unparceledNode =
                AccessibilityNodeInfo.CREATOR.createFromParcel(parcel);
        assertThat(unparceledNode.getActionList(), emptyCollectionOf(AccessibilityAction.class));
        assertThat(unparceledNode.getText(), equalTo(text));
    }

    @Test
    public void dontForgetToUpdateCtsParcelingTestWhenYouAddNewFields() {
        AccessibilityEventTest.assertNoNewNonStaticFieldsAdded(AccessibilityNodeInfo.class,
                NUM_MARSHALLED_PROPERTIES + NUM_NONMARSHALLED_PROPERTIES);
    }

    @Test
    public void updateCtsToTestNewBooleanProperties() {
        int booleanPropertiesCount = 0;

        for (Field field : AccessibilityNodeInfo.class.getDeclaredFields()) {
            if (((field.getModifiers() & Modifier.STATIC) != 0)
                    && (field.getName().contains("BOOLEAN_PROPERTY"))) {
                booleanPropertiesCount++;
            }
        }

        assertEquals("New boolean properties. Make sure you're testing them in CTS",
                NUM_BOOLEAN_PROPERTIES, booleanPropertiesCount);
    }
}
