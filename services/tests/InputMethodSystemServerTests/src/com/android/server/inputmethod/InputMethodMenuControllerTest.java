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

package com.android.server.inputmethod;

import static com.android.server.inputmethod.InputMethodMenuControllerNew.getMenuItems;
import static com.android.server.inputmethod.InputMethodMenuControllerNew.getSelectedIndex;
import static com.android.server.inputmethod.InputMethodSubtypeSwitchingControllerTest.addTestImeSubtypeListItems;
import static com.android.server.inputmethod.InputMethodUtils.NOT_A_SUBTYPE_INDEX;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.view.inputmethod.Flags;

import com.android.server.inputmethod.InputMethodMenuControllerNew.DividerItem;
import com.android.server.inputmethod.InputMethodMenuControllerNew.HeaderItem;
import com.android.server.inputmethod.InputMethodMenuControllerNew.SubtypeItem;
import com.android.server.inputmethod.InputMethodSubtypeSwitchingController.ImeSubtypeListItem;

import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

@RequiresFlagsEnabled(Flags.FLAG_IME_SWITCHER_REVAMP)
public class InputMethodMenuControllerTest {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    /** Verifies that getMenuItems maintains the same order and information from the given items. */
    @Test
    public void testGetMenuItems() {
        final var items = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(items, "LatinIme", "LatinIme",
                List.of("en", "fr"), true /* supportsSwitchingToNextInputMethod */);
        addTestImeSubtypeListItems(items, "SimpleIme", "SimpleIme",
                null, true /* supportsSwitchingToNextInputMethod */);

        final var menuItems = getMenuItems(items);

        int itemsIndex = 0;

        for (int i = 0; i < menuItems.size(); i++) {
            final var menuItem = menuItems.get(i);
            if (menuItem instanceof SubtypeItem subtypeItem) {
                final var item = items.get(itemsIndex);

                assertWithMessage("IME name does not match").that(subtypeItem.mImeName)
                        .isEqualTo(item.mImeName);
                assertWithMessage("Subtype name does not match").that(subtypeItem.mSubtypeName)
                        .isEqualTo(item.mSubtypeName);
                assertWithMessage("InputMethodInfo does not match").that(subtypeItem.mImi)
                        .isEqualTo(item.mImi);
                assertWithMessage("Subtype index does not match").that(subtypeItem.mSubtypeIndex)
                        .isEqualTo(item.mSubtypeIndex);

                itemsIndex++;
            }
        }

        assertWithMessage("Items list was not fully traversed").that(itemsIndex)
                .isEqualTo(items.size());
    }

    /**
     * Verifies that getMenuItems does not add a header or divider if all the items belong to
     * a single input method.
     */
    @Test
    public void testGetMenuItemsNoHeaderOrDividerForSingleInputMethod() {
        final var items = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(items, "LatinIme", "LatinIme",
                List.of("en", "fr"), true /* supportsSwitchingToNextInputMethod */);

        final var menuItems = getMenuItems(items);

        assertThat(menuItems.stream()
                .filter(item -> item instanceof HeaderItem || item instanceof DividerItem).toList())
                .isEmpty();
    }

    /**
     * Verifies that getMenuItems only adds headers for item groups with at least two items,
     * or with a single item with a subtype name.
     */
    @Test
    public void testGetMenuItemsHeaders() {
        final var items = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(items, "DefaultIme", "DefaultIme",
                null, true /* supportsSwitchingToNextInputMethod */);
        addTestImeSubtypeListItems(items, "LatinIme", "LatinIme",
                List.of("en", "fr"), true /* supportsSwitchingToNextInputMethod */);
        addTestImeSubtypeListItems(items, "ItalianIme", "ItalianIme",
                List.of("it"), true /* supportsSwitchingToNextInputMethod */);
        addTestImeSubtypeListItems(items, "SimpleIme", "SimpleIme",
                null, true /* supportsSwitchingToNextInputMethod */);

        final var menuItems = getMenuItems(items);

        assertWithMessage("Must have menu items").that(menuItems).isNotEmpty();

        final var headersAndDividers = menuItems.stream()
                .filter(item -> item instanceof HeaderItem || item instanceof DividerItem)
                .toList();

        assertWithMessage("Must have header and divider items").that(headersAndDividers).hasSize(5);

        assertWithMessage("First group has no header")
                .that(menuItems.getFirst()).isInstanceOf(SubtypeItem.class);
        assertWithMessage("Group with multiple items has divider")
                .that(headersAndDividers.get(0)).isInstanceOf(DividerItem.class);
        assertWithMessage("Group with multiple items has header")
                .that(headersAndDividers.get(1)).isInstanceOf(HeaderItem.class);
        assertWithMessage("Group with single item with subtype name has divider")
                .that(headersAndDividers.get(2)).isInstanceOf(DividerItem.class);
        assertWithMessage("Group with single item with subtype name has header")
                .that(headersAndDividers.get(3)).isInstanceOf(HeaderItem.class);
        assertWithMessage("Group with single item without subtype name has divider only")
                .that(headersAndDividers.get(4)).isInstanceOf(DividerItem.class);
    }

    /** Verifies that getMenuItems adds a divider before every header except the first one. */
    @Test
    public void testGetMenuItemsDivider() {
        final var items = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(items, "LatinIme", "LatinIme",
                List.of("en", "fr"), true /* supportsSwitchingToNextInputMethod */);
        addTestImeSubtypeListItems(items, "ItalianIme", "ItalianIme",
                List.of("it"), true /* supportsSwitchingToNextInputMethod */);
        addTestImeSubtypeListItems(items, "SimpleIme", "SimpleIme",
                null, true /* supportsSwitchingToNextInputMethod */);

        final var menuItems = getMenuItems(items);

        assertWithMessage("First item is a header")
                .that(menuItems.getFirst()).isInstanceOf(HeaderItem.class);
        assertWithMessage("Last item is a subtype")
                .that(menuItems.getLast()).isInstanceOf(SubtypeItem.class);

        for (int i = 0; i < menuItems.size(); i++) {
            final var item = menuItems.get(i);
            if (item instanceof HeaderItem && i > 0) {
                final var prevItem = menuItems.get(i - 1);
                assertWithMessage("The item before a header should be a divider")
                        .that(prevItem).isInstanceOf(DividerItem.class);
            } else if (item instanceof DividerItem && i < menuItems.size() - 1) {
                final var nextItem = menuItems.get(i + 1);
                assertWithMessage("The item after a divider should be a header or subtype")
                        .that(nextItem instanceof HeaderItem || nextItem instanceof SubtypeItem)
                        .isTrue();
            }
        }
    }

    /**
     * Verifies that getSelectedIndex returns the matching item when the selected subtype is given.
     */
    @Test
    public void testGetSelectedIndexWithSelectedSubtype() {
        final var items = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(items, "LatinIme", "LatinIme",
                List.of("en", "fr"), true /* supportsSwitchingToNextInputMethod */);
        addTestImeSubtypeListItems(items, "SimpleIme", "SimpleIme",
                List.of("it", "jp", "pt"),  true /* supportsSwitchingToNextInputMethod */);

        final var simpleImeId = items.get(2).mImi.getId();
        final var menuItems = getMenuItems(items);

        final int selectedIndex = getSelectedIndex(menuItems, simpleImeId, 1);
        // Two headers + one divider + three items
        assertThat(selectedIndex).isEqualTo(6);
    }

    /**
     * Verifies that getSelectedIndex returns the first item of the selected input method,
     * when no selected subtype is given.
     */
    @Test
    public void testGetSelectedIndexWithoutSelectedSubtype() {
        final var items = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(items, "LatinIme", "LatinIme",
                List.of("en", "fr"), true /* supportsSwitchingToNextInputMethod */);
        addTestImeSubtypeListItems(items, "SimpleIme", "SimpleIme",
                List.of("it", "jp", "pt"),  true /* supportsSwitchingToNextInputMethod */);

        final var simpleImeId = items.get(2).mImi.getId();
        final var menuItems = getMenuItems(items);

        final int selectedIndex = getSelectedIndex(menuItems, simpleImeId, NOT_A_SUBTYPE_INDEX);

        // Two headers + one divider + two items
        assertThat(selectedIndex).isEqualTo(5);
    }

    /**
     * Verifies that getSelectedIndex will return the item of the selected input method that has
     * no subtype, when this is the first one reached, regardless of the given selected subtype.
     */
    @Test
    public void getSelectedIndexNoSubtype() {
        final var items = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(items, "LatinIme", "LatinIme",
                List.of("en", "fr"), true /* supportsSwitchingToNextInputMethod */);
        addTestImeSubtypeListItems(items, "SimpleIme", "SimpleIme",
                null,  true /* supportsSwitchingToNextInputMethod */);

        final var simpleImeId = items.get(2).mImi.getId();
        final var menuItems = getMenuItems(items);

        final int selectedIndex = getSelectedIndex(menuItems, simpleImeId, 1);

        // One header + one divider + two items
        assertThat(selectedIndex).isEqualTo(4);
    }
}
