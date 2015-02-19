/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.uiautomator.core;

import android.util.SparseArray;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.regex.Pattern;

/**
 * Specifies the elements in the layout hierarchy for tests to target, filtered
 * by properties such as text value, content-description, class name, and state
 * information. You can also target an element by its location in a layout
 * hierarchy.
 * @since API Level 16
 */
public class UiSelector {
    static final int SELECTOR_NIL = 0;
    static final int SELECTOR_TEXT = 1;
    static final int SELECTOR_START_TEXT = 2;
    static final int SELECTOR_CONTAINS_TEXT = 3;
    static final int SELECTOR_CLASS = 4;
    static final int SELECTOR_DESCRIPTION = 5;
    static final int SELECTOR_START_DESCRIPTION = 6;
    static final int SELECTOR_CONTAINS_DESCRIPTION = 7;
    static final int SELECTOR_INDEX = 8;
    static final int SELECTOR_INSTANCE = 9;
    static final int SELECTOR_ENABLED = 10;
    static final int SELECTOR_FOCUSED = 11;
    static final int SELECTOR_FOCUSABLE = 12;
    static final int SELECTOR_SCROLLABLE = 13;
    static final int SELECTOR_CLICKABLE = 14;
    static final int SELECTOR_CHECKED = 15;
    static final int SELECTOR_SELECTED = 16;
    static final int SELECTOR_ID = 17;
    static final int SELECTOR_PACKAGE_NAME = 18;
    static final int SELECTOR_CHILD = 19;
    static final int SELECTOR_CONTAINER = 20;
    static final int SELECTOR_PATTERN = 21;
    static final int SELECTOR_PARENT = 22;
    static final int SELECTOR_COUNT = 23;
    static final int SELECTOR_LONG_CLICKABLE = 24;
    static final int SELECTOR_TEXT_REGEX = 25;
    static final int SELECTOR_CLASS_REGEX = 26;
    static final int SELECTOR_DESCRIPTION_REGEX = 27;
    static final int SELECTOR_PACKAGE_NAME_REGEX = 28;
    static final int SELECTOR_RESOURCE_ID = 29;
    static final int SELECTOR_CHECKABLE = 30;
    static final int SELECTOR_RESOURCE_ID_REGEX = 31;

    private SparseArray<Object> mSelectorAttributes = new SparseArray<Object>();

    /**
     * @since API Level 16
     */
    public UiSelector() {
    }

    UiSelector(UiSelector selector) {
        mSelectorAttributes = selector.cloneSelector().mSelectorAttributes;
    }

    /**
     * @since API Level 17
     */
    protected UiSelector cloneSelector() {
        UiSelector ret = new UiSelector();
        ret.mSelectorAttributes = mSelectorAttributes.clone();
        if (hasChildSelector())
            ret.mSelectorAttributes.put(SELECTOR_CHILD, new UiSelector(getChildSelector()));
        if (hasParentSelector())
            ret.mSelectorAttributes.put(SELECTOR_PARENT, new UiSelector(getParentSelector()));
        if (hasPatternSelector())
            ret.mSelectorAttributes.put(SELECTOR_PATTERN, new UiSelector(getPatternSelector()));
        return ret;
    }

    static UiSelector patternBuilder(UiSelector selector) {
        if (!selector.hasPatternSelector()) {
            return new UiSelector().patternSelector(selector);
        }
        return selector;
    }

    static UiSelector patternBuilder(UiSelector container, UiSelector pattern) {
        return new UiSelector(
                new UiSelector().containerSelector(container).patternSelector(pattern));
    }

    /**
     * Set the search criteria to match the visible text displayed
     * in a widget (for example, the text label to launch an app).
     *
     * The text for the element must match exactly with the string in your input
     * argument. Matching is case-sensitive.
     *
     * @param text Value to match
     * @return UiSelector with the specified search criteria
     * @since API Level 16
     */
    public UiSelector text(String text) {
        return buildSelector(SELECTOR_TEXT, text);
    }

    /**
     * Set the search criteria to match the visible text displayed in a layout
     * element, using a regular expression.
     *
     * The text in the widget must match exactly with the string in your
     * input argument.
     *
     * @param regex a regular expression
     * @return UiSelector with the specified search criteria
     * @since API Level 17
     */
    public UiSelector textMatches(String regex) {
        return buildSelector(SELECTOR_TEXT_REGEX, Pattern.compile(regex));
    }

    /**
     * Set the search criteria to match visible text in a widget that is
     * prefixed by the text parameter.
     *
     * The matching is case-insensitive.
     *
     * @param text Value to match
     * @return UiSelector with the specified search criteria
     * @since API Level 16
     */
    public UiSelector textStartsWith(String text) {
        return buildSelector(SELECTOR_START_TEXT, text);
    }

    /**
     * Set the search criteria to match the visible text in a widget
     * where the visible text must contain the string in your input argument.
     *
     * The matching is case-sensitive.
     *
     * @param text Value to match
     * @return UiSelector with the specified search criteria
     * @since API Level 16
     */
    public UiSelector textContains(String text) {
        return buildSelector(SELECTOR_CONTAINS_TEXT, text);
    }

    /**
     * Set the search criteria to match the class property
     * for a widget (for example, "android.widget.Button").
     *
     * @param className Value to match
     * @return UiSelector with the specified search criteria
     * @since API Level 16
     */
    public UiSelector className(String className) {
        return buildSelector(SELECTOR_CLASS, className);
    }

    /**
     * Set the search criteria to match the class property
     * for a widget, using a regular expression.
     *
     * @param regex a regular expression
     * @return UiSelector with the specified search criteria
     * @since API Level 17
     */
    public UiSelector classNameMatches(String regex) {
        return buildSelector(SELECTOR_CLASS_REGEX, Pattern.compile(regex));
    }

    /**
     * Set the search criteria to match the class property
     * for a widget (for example, "android.widget.Button").
     *
     * @param type type
     * @return UiSelector with the specified search criteria
     * @since API Level 17
     */
    public <T> UiSelector className(Class<T> type) {
        return buildSelector(SELECTOR_CLASS, type.getName());
    }

    /**
     * Set the search criteria to match the content-description
     * property for a widget.
     *
     * The content-description is typically used
     * by the Android Accessibility framework to
     * provide an audio prompt for the widget when
     * the widget is selected. The content-description
     * for the widget must match exactly
     * with the string in your input argument.
     *
     * Matching is case-sensitive.
     *
     * @param desc Value to match
     * @return UiSelector with the specified search criteria
     * @since API Level 16
     */
    public UiSelector description(String desc) {
        return buildSelector(SELECTOR_DESCRIPTION, desc);
    }

    /**
     * Set the search criteria to match the content-description
     * property for a widget.
     *
     * The content-description is typically used
     * by the Android Accessibility framework to
     * provide an audio prompt for the widget when
     * the widget is selected. The content-description
     * for the widget must match exactly
     * with the string in your input argument.
     *
     * @param regex a regular expression
     * @return UiSelector with the specified search criteria
     * @since API Level 17
     */
    public UiSelector descriptionMatches(String regex) {
        return buildSelector(SELECTOR_DESCRIPTION_REGEX, Pattern.compile(regex));
    }

    /**
     * Set the search criteria to match the content-description
     * property for a widget.
     *
     * The content-description is typically used
     * by the Android Accessibility framework to
     * provide an audio prompt for the widget when
     * the widget is selected. The content-description
     * for the widget must start
     * with the string in your input argument.
     *
     * Matching is case-insensitive.
     *
     * @param desc Value to match
     * @return UiSelector with the specified search criteria
     * @since API Level 16
     */
    public UiSelector descriptionStartsWith(String desc) {
        return buildSelector(SELECTOR_START_DESCRIPTION, desc);
    }

    /**
     * Set the search criteria to match the content-description
     * property for a widget.
     *
     * The content-description is typically used
     * by the Android Accessibility framework to
     * provide an audio prompt for the widget when
     * the widget is selected. The content-description
     * for the widget must contain
     * the string in your input argument.
     *
     * Matching is case-insensitive.
     *
     * @param desc Value to match
     * @return UiSelector with the specified search criteria
     * @since API Level 16
     */
    public UiSelector descriptionContains(String desc) {
        return buildSelector(SELECTOR_CONTAINS_DESCRIPTION, desc);
    }

    /**
     * Set the search criteria to match the given resource ID.
     *
     * @param id Value to match
     * @return UiSelector with the specified search criteria
     * @since API Level 18
     */
    public UiSelector resourceId(String id) {
        return buildSelector(SELECTOR_RESOURCE_ID, id);
    }

    /**
     * Set the search criteria to match the resource ID
     * of the widget, using a regular expression.
     *
     * @param regex a regular expression
     * @return UiSelector with the specified search criteria
     * @since API Level 18
     */
    public UiSelector resourceIdMatches(String regex) {
        return buildSelector(SELECTOR_RESOURCE_ID_REGEX, Pattern.compile(regex));
    }

    /**
     * Set the search criteria to match the widget by its node
     * index in the layout hierarchy.
     *
     * The index value must be 0 or greater.
     *
     * Using the index can be unreliable and should only
     * be used as a last resort for matching. Instead,
     * consider using the {@link #instance(int)} method.
     *
     * @param index Value to match
     * @return UiSelector with the specified search criteria
     * @since API Level 16
     */
    public UiSelector index(final int index) {
        return buildSelector(SELECTOR_INDEX, index);
    }

    /**
     * Set the search criteria to match the
     * widget by its instance number.
     *
     * The instance value must be 0 or greater, where
     * the first instance is 0.
     *
     * For example, to simulate a user click on
     * the third image that is enabled in a UI screen, you
     * could specify a a search criteria where the instance is
     * 2, the {@link #className(String)} matches the image
     * widget class, and {@link #enabled(boolean)} is true.
     * The code would look like this:
     * <code>
     * new UiSelector().className("android.widget.ImageView")
     *    .enabled(true).instance(2);
     * </code>
     *
     * @param instance Value to match
     * @return UiSelector with the specified search criteria
     * @since API Level 16
     */
    public UiSelector instance(final int instance) {
        return buildSelector(SELECTOR_INSTANCE, instance);
    }

    /**
     * Set the search criteria to match widgets that are enabled.
     *
     * Typically, using this search criteria alone is not useful.
     * You should also include additional criteria, such as text,
     * content-description, or the class name for a widget.
     *
     * If no other search criteria is specified, and there is more
     * than one matching widget, the first widget in the tree
     * is selected.
     *
     * @param val Value to match
     * @return UiSelector with the specified search criteria
     * @since API Level 16
     */
    public UiSelector enabled(boolean val) {
        return buildSelector(SELECTOR_ENABLED, val);
    }

    /**
     * Set the search criteria to match widgets that have focus.
     *
     * Typically, using this search criteria alone is not useful.
     * You should also include additional criteria, such as text,
     * content-description, or the class name for a widget.
     *
     * If no other search criteria is specified, and there is more
     * than one matching widget, the first widget in the tree
     * is selected.
     *
     * @param val Value to match
     * @return UiSelector with the specified search criteria
     * @since API Level 16
     */
    public UiSelector focused(boolean val) {
        return buildSelector(SELECTOR_FOCUSED, val);
    }

    /**
     * Set the search criteria to match widgets that are focusable.
     *
     * Typically, using this search criteria alone is not useful.
     * You should also include additional criteria, such as text,
     * content-description, or the class name for a widget.
     *
     * If no other search criteria is specified, and there is more
     * than one matching widget, the first widget in the tree
     * is selected.
     *
     * @param val Value to match
     * @return UiSelector with the specified search criteria
     * @since API Level 16
     */
    public UiSelector focusable(boolean val) {
        return buildSelector(SELECTOR_FOCUSABLE, val);
    }

    /**
     * Set the search criteria to match widgets that are scrollable.
     *
     * Typically, using this search criteria alone is not useful.
     * You should also include additional criteria, such as text,
     * content-description, or the class name for a widget.
     *
     * If no other search criteria is specified, and there is more
     * than one matching widget, the first widget in the tree
     * is selected.
     *
     * @param val Value to match
     * @return UiSelector with the specified search criteria
     * @since API Level 16
     */
    public UiSelector scrollable(boolean val) {
        return buildSelector(SELECTOR_SCROLLABLE, val);
    }

    /**
     * Set the search criteria to match widgets that
     * are currently selected.
     *
     * Typically, using this search criteria alone is not useful.
     * You should also include additional criteria, such as text,
     * content-description, or the class name for a widget.
     *
     * If no other search criteria is specified, and there is more
     * than one matching widget, the first widget in the tree
     * is selected.
     *
     * @param val Value to match
     * @return UiSelector with the specified search criteria
     * @since API Level 16
     */
    public UiSelector selected(boolean val) {
        return buildSelector(SELECTOR_SELECTED, val);
    }

    /**
     * Set the search criteria to match widgets that
     * are currently checked (usually for checkboxes).
     *
     * Typically, using this search criteria alone is not useful.
     * You should also include additional criteria, such as text,
     * content-description, or the class name for a widget.
     *
     * If no other search criteria is specified, and there is more
     * than one matching widget, the first widget in the tree
     * is selected.
     *
     * @param val Value to match
     * @return UiSelector with the specified search criteria
     * @since API Level 16
     */
    public UiSelector checked(boolean val) {
        return buildSelector(SELECTOR_CHECKED, val);
    }

    /**
     * Set the search criteria to match widgets that are clickable.
     *
     * Typically, using this search criteria alone is not useful.
     * You should also include additional criteria, such as text,
     * content-description, or the class name for a widget.
     *
     * If no other search criteria is specified, and there is more
     * than one matching widget, the first widget in the tree
     * is selected.
     *
     * @param val Value to match
     * @return UiSelector with the specified search criteria
     * @since API Level 16
     */
    public UiSelector clickable(boolean val) {
        return buildSelector(SELECTOR_CLICKABLE, val);
    }

    /**
     * Set the search criteria to match widgets that are checkable.
     *
     * Typically, using this search criteria alone is not useful.
     * You should also include additional criteria, such as text,
     * content-description, or the class name for a widget.
     *
     * If no other search criteria is specified, and there is more
     * than one matching widget, the first widget in the tree
     * is selected.
     *
     * @param val Value to match
     * @return UiSelector with the specified search criteria
     * @since API Level 18
     */
    public UiSelector checkable(boolean val) {
        return buildSelector(SELECTOR_CHECKABLE, val);
    }

    /**
     * Set the search criteria to match widgets that are long-clickable.
     *
     * Typically, using this search criteria alone is not useful.
     * You should also include additional criteria, such as text,
     * content-description, or the class name for a widget.
     *
     * If no other search criteria is specified, and there is more
     * than one matching widget, the first widget in the tree
     * is selected.
     *
     * @param val Value to match
     * @return UiSelector with the specified search criteria
     * @since API Level 17
     */
    public UiSelector longClickable(boolean val) {
        return buildSelector(SELECTOR_LONG_CLICKABLE, val);
    }

    /**
     * Adds a child UiSelector criteria to this selector.
     *
     * Use this selector to narrow the search scope to
     * child widgets under a specific parent widget.
     *
     * @param selector
     * @return UiSelector with this added search criterion
     * @since API Level 16
     */
    public UiSelector childSelector(UiSelector selector) {
        return buildSelector(SELECTOR_CHILD, selector);
    }

    private UiSelector patternSelector(UiSelector selector) {
        return buildSelector(SELECTOR_PATTERN, selector);
    }

    private UiSelector containerSelector(UiSelector selector) {
        return buildSelector(SELECTOR_CONTAINER, selector);
    }

    /**
     * Adds a child UiSelector criteria to this selector which is used to
     * start search from the parent widget.
     *
     * Use this selector to narrow the search scope to
     * sibling widgets as well all child widgets under a parent.
     *
     * @param selector
     * @return UiSelector with this added search criterion
     * @since API Level 16
     */
    public UiSelector fromParent(UiSelector selector) {
        return buildSelector(SELECTOR_PARENT, selector);
    }

    /**
     * Set the search criteria to match the package name
     * of the application that contains the widget.
     *
     * @param name Value to match
     * @return UiSelector with the specified search criteria
     * @since API Level 16
     */
    public UiSelector packageName(String name) {
        return buildSelector(SELECTOR_PACKAGE_NAME, name);
    }

    /**
     * Set the search criteria to match the package name
     * of the application that contains the widget.
     *
     * @param regex a regular expression
     * @return UiSelector with the specified search criteria
     * @since API Level 17
     */
    public UiSelector packageNameMatches(String regex) {
        return buildSelector(SELECTOR_PACKAGE_NAME_REGEX, Pattern.compile(regex));
    }

    /**
     * Building a UiSelector always returns a new UiSelector and never modifies the
     * existing UiSelector being used.
     */
    private UiSelector buildSelector(int selectorId, Object selectorValue) {
        UiSelector selector = new UiSelector(this);
        if (selectorId == SELECTOR_CHILD || selectorId == SELECTOR_PARENT)
            selector.getLastSubSelector().mSelectorAttributes.put(selectorId, selectorValue);
        else
            selector.mSelectorAttributes.put(selectorId, selectorValue);
        return selector;
    }

    /**
     * Selectors may have a hierarchy defined by specifying child nodes to be matched.
     * It is not necessary that every selector have more than one level. A selector
     * can also be a single level referencing only one node. In such cases the return
     * it null.
     *
     * @return a child selector if one exists. Else null if this selector does not
     * reference child node.
     */
    UiSelector getChildSelector() {
        UiSelector selector = (UiSelector)mSelectorAttributes.get(UiSelector.SELECTOR_CHILD, null);
        if (selector != null)
            return new UiSelector(selector);
        return null;
    }

    UiSelector getPatternSelector() {
        UiSelector selector =
                (UiSelector)mSelectorAttributes.get(UiSelector.SELECTOR_PATTERN, null);
        if (selector != null)
            return new UiSelector(selector);
        return null;
    }

    UiSelector getContainerSelector() {
        UiSelector selector =
                (UiSelector)mSelectorAttributes.get(UiSelector.SELECTOR_CONTAINER, null);
        if (selector != null)
            return new UiSelector(selector);
        return null;
    }

    UiSelector getParentSelector() {
        UiSelector selector =
                (UiSelector) mSelectorAttributes.get(UiSelector.SELECTOR_PARENT, null);
        if (selector != null)
            return new UiSelector(selector);
        return null;
    }

    int getInstance() {
        return getInt(UiSelector.SELECTOR_INSTANCE);
    }

    String getString(int criterion) {
        return (String) mSelectorAttributes.get(criterion, null);
    }

    boolean getBoolean(int criterion) {
        return (Boolean) mSelectorAttributes.get(criterion, false);
    }

    int getInt(int criterion) {
        return (Integer) mSelectorAttributes.get(criterion, 0);
    }

    Pattern getPattern(int criterion) {
        return (Pattern) mSelectorAttributes.get(criterion, null);
    }

    boolean isMatchFor(AccessibilityNodeInfo node, int index) {
        int size = mSelectorAttributes.size();
        for(int x = 0; x < size; x++) {
            CharSequence s = null;
            int criterion = mSelectorAttributes.keyAt(x);
            switch(criterion) {
            case UiSelector.SELECTOR_INDEX:
                if (index != this.getInt(criterion))
                    return false;
                break;
            case UiSelector.SELECTOR_CHECKED:
                if (node.isChecked() != getBoolean(criterion)) {
                    return false;
                }
                break;
            case UiSelector.SELECTOR_CLASS:
                s = node.getClassName();
                if (s == null || !s.toString().contentEquals(getString(criterion))) {
                    return false;
                }
                break;
            case UiSelector.SELECTOR_CLASS_REGEX:
                s = node.getClassName();
                if (s == null || !getPattern(criterion).matcher(s).matches()) {
                    return false;
                }
                break;
            case UiSelector.SELECTOR_CLICKABLE:
                if (node.isClickable() != getBoolean(criterion)) {
                    return false;
                }
                break;
            case UiSelector.SELECTOR_CHECKABLE:
                if (node.isCheckable() != getBoolean(criterion)) {
                    return false;
                }
                break;
            case UiSelector.SELECTOR_LONG_CLICKABLE:
                if (node.isLongClickable() != getBoolean(criterion)) {
                    return false;
                }
                break;
            case UiSelector.SELECTOR_CONTAINS_DESCRIPTION:
                s = node.getContentDescription();
                if (s == null || !s.toString().toLowerCase()
                        .contains(getString(criterion).toLowerCase())) {
                    return false;
                }
                break;
            case UiSelector.SELECTOR_START_DESCRIPTION:
                s = node.getContentDescription();
                if (s == null || !s.toString().toLowerCase()
                        .startsWith(getString(criterion).toLowerCase())) {
                    return false;
                }
                break;
            case UiSelector.SELECTOR_DESCRIPTION:
                s = node.getContentDescription();
                if (s == null || !s.toString().contentEquals(getString(criterion))) {
                    return false;
                }
                break;
            case UiSelector.SELECTOR_DESCRIPTION_REGEX:
                s = node.getContentDescription();
                if (s == null || !getPattern(criterion).matcher(s).matches()) {
                    return false;
                }
                break;
            case UiSelector.SELECTOR_CONTAINS_TEXT:
                s = node.getText();
                if (s == null || !s.toString().toLowerCase()
                        .contains(getString(criterion).toLowerCase())) {
                    return false;
                }
                break;
            case UiSelector.SELECTOR_START_TEXT:
                s = node.getText();
                if (s == null || !s.toString().toLowerCase()
                        .startsWith(getString(criterion).toLowerCase())) {
                    return false;
                }
                break;
            case UiSelector.SELECTOR_TEXT:
                s = node.getText();
                if (s == null || !s.toString().contentEquals(getString(criterion))) {
                    return false;
                }
                break;
            case UiSelector.SELECTOR_TEXT_REGEX:
                s = node.getText();
                if (s == null || !getPattern(criterion).matcher(s).matches()) {
                    return false;
                }
                break;
            case UiSelector.SELECTOR_ENABLED:
                if (node.isEnabled() != getBoolean(criterion)) {
                    return false;
                }
                break;
            case UiSelector.SELECTOR_FOCUSABLE:
                if (node.isFocusable() != getBoolean(criterion)) {
                    return false;
                }
                break;
            case UiSelector.SELECTOR_FOCUSED:
                if (node.isFocused() != getBoolean(criterion)) {
                    return false;
                }
                break;
            case UiSelector.SELECTOR_ID:
                break; //TODO: do we need this for AccessibilityNodeInfo.id?
            case UiSelector.SELECTOR_PACKAGE_NAME:
                s = node.getPackageName();
                if (s == null || !s.toString().contentEquals(getString(criterion))) {
                    return false;
                }
                break;
            case UiSelector.SELECTOR_PACKAGE_NAME_REGEX:
                s = node.getPackageName();
                if (s == null || !getPattern(criterion).matcher(s).matches()) {
                    return false;
                }
                break;
            case UiSelector.SELECTOR_SCROLLABLE:
                if (node.isScrollable() != getBoolean(criterion)) {
                    return false;
                }
                break;
            case UiSelector.SELECTOR_SELECTED:
                if (node.isSelected() != getBoolean(criterion)) {
                    return false;
                }
                break;
            case UiSelector.SELECTOR_RESOURCE_ID:
                s = node.getViewIdResourceName();
                if (s == null || !s.toString().contentEquals(getString(criterion))) {
                    return false;
                }
                break;
            case UiSelector.SELECTOR_RESOURCE_ID_REGEX:
                s = node.getViewIdResourceName();
                if (s == null || !getPattern(criterion).matcher(s).matches()) {
                    return false;
                }
                break;
            }
        }
        return matchOrUpdateInstance();
    }

    private boolean matchOrUpdateInstance() {
        int currentSelectorCounter = 0;
        int currentSelectorInstance = 0;

        // matched attributes - now check for matching instance number
        if (mSelectorAttributes.indexOfKey(UiSelector.SELECTOR_INSTANCE) >= 0) {
            currentSelectorInstance =
                    (Integer)mSelectorAttributes.get(UiSelector.SELECTOR_INSTANCE);
        }

        // instance is required. Add count if not already counting
        if (mSelectorAttributes.indexOfKey(UiSelector.SELECTOR_COUNT) >= 0) {
            currentSelectorCounter = (Integer)mSelectorAttributes.get(UiSelector.SELECTOR_COUNT);
        }

        // Verify
        if (currentSelectorInstance == currentSelectorCounter) {
            return true;
        }
        // Update count
        if (currentSelectorInstance > currentSelectorCounter) {
            mSelectorAttributes.put(UiSelector.SELECTOR_COUNT, ++currentSelectorCounter);
        }
        return false;
    }

    /**
     * Leaf selector indicates no more child or parent selectors
     * are declared in the this selector.
     * @return true if is leaf.
     */
    boolean isLeaf() {
        if (mSelectorAttributes.indexOfKey(UiSelector.SELECTOR_CHILD) < 0 &&
                mSelectorAttributes.indexOfKey(UiSelector.SELECTOR_PARENT) < 0) {
            return true;
        }
        return false;
    }

    boolean hasChildSelector() {
        if (mSelectorAttributes.indexOfKey(UiSelector.SELECTOR_CHILD) < 0) {
            return false;
        }
        return true;
    }

    boolean hasPatternSelector() {
        if (mSelectorAttributes.indexOfKey(UiSelector.SELECTOR_PATTERN) < 0) {
            return false;
        }
        return true;
    }

    boolean hasContainerSelector() {
        if (mSelectorAttributes.indexOfKey(UiSelector.SELECTOR_CONTAINER) < 0) {
            return false;
        }
        return true;
    }

    boolean hasParentSelector() {
        if (mSelectorAttributes.indexOfKey(UiSelector.SELECTOR_PARENT) < 0) {
            return false;
        }
        return true;
    }

    /**
     * Returns the deepest selector in the chain of possible sub selectors.
     * A chain of selector is created when either of {@link UiSelector#childSelector(UiSelector)}
     * or {@link UiSelector#fromParent(UiSelector)} are used once or more in the construction of
     * a selector.
     * @return last UiSelector in chain
     */
    private UiSelector getLastSubSelector() {
        if (mSelectorAttributes.indexOfKey(UiSelector.SELECTOR_CHILD) >= 0) {
            UiSelector child = (UiSelector)mSelectorAttributes.get(UiSelector.SELECTOR_CHILD);
            if (child.getLastSubSelector() == null) {
                return child;
            }
            return child.getLastSubSelector();
        } else if (mSelectorAttributes.indexOfKey(UiSelector.SELECTOR_PARENT) >= 0) {
            UiSelector parent = (UiSelector)mSelectorAttributes.get(UiSelector.SELECTOR_PARENT);
            if (parent.getLastSubSelector() == null) {
                return parent;
            }
            return parent.getLastSubSelector();
        }
        return this;
    }

    @Override
    public String toString() {
        return dumpToString(true);
    }

    String dumpToString(boolean all) {
        StringBuilder builder = new StringBuilder();
        builder.append(UiSelector.class.getSimpleName() + "[");
        final int criterionCount = mSelectorAttributes.size();
        for (int i = 0; i < criterionCount; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            final int criterion = mSelectorAttributes.keyAt(i);
            switch (criterion) {
            case SELECTOR_TEXT:
                builder.append("TEXT=").append(mSelectorAttributes.valueAt(i));
                break;
            case SELECTOR_TEXT_REGEX:
                builder.append("TEXT_REGEX=").append(mSelectorAttributes.valueAt(i));
                break;
            case SELECTOR_START_TEXT:
                builder.append("START_TEXT=").append(mSelectorAttributes.valueAt(i));
                break;
            case SELECTOR_CONTAINS_TEXT:
                builder.append("CONTAINS_TEXT=").append(mSelectorAttributes.valueAt(i));
                break;
            case SELECTOR_CLASS:
                builder.append("CLASS=").append(mSelectorAttributes.valueAt(i));
                break;
            case SELECTOR_CLASS_REGEX:
                builder.append("CLASS_REGEX=").append(mSelectorAttributes.valueAt(i));
                break;
            case SELECTOR_DESCRIPTION:
                builder.append("DESCRIPTION=").append(mSelectorAttributes.valueAt(i));
                break;
            case SELECTOR_DESCRIPTION_REGEX:
                builder.append("DESCRIPTION_REGEX=").append(mSelectorAttributes.valueAt(i));
                break;
            case SELECTOR_START_DESCRIPTION:
                builder.append("START_DESCRIPTION=").append(mSelectorAttributes.valueAt(i));
                break;
            case SELECTOR_CONTAINS_DESCRIPTION:
                builder.append("CONTAINS_DESCRIPTION=").append(mSelectorAttributes.valueAt(i));
                break;
            case SELECTOR_INDEX:
                builder.append("INDEX=").append(mSelectorAttributes.valueAt(i));
                break;
            case SELECTOR_INSTANCE:
                builder.append("INSTANCE=").append(mSelectorAttributes.valueAt(i));
                break;
            case SELECTOR_ENABLED:
                builder.append("ENABLED=").append(mSelectorAttributes.valueAt(i));
                break;
            case SELECTOR_FOCUSED:
                builder.append("FOCUSED=").append(mSelectorAttributes.valueAt(i));
                break;
            case SELECTOR_FOCUSABLE:
                builder.append("FOCUSABLE=").append(mSelectorAttributes.valueAt(i));
                break;
            case SELECTOR_SCROLLABLE:
                builder.append("SCROLLABLE=").append(mSelectorAttributes.valueAt(i));
                break;
            case SELECTOR_CLICKABLE:
                builder.append("CLICKABLE=").append(mSelectorAttributes.valueAt(i));
                break;
            case SELECTOR_CHECKABLE:
                builder.append("CHECKABLE=").append(mSelectorAttributes.valueAt(i));
                break;
            case SELECTOR_LONG_CLICKABLE:
                builder.append("LONG_CLICKABLE=").append(mSelectorAttributes.valueAt(i));
                break;
            case SELECTOR_CHECKED:
                builder.append("CHECKED=").append(mSelectorAttributes.valueAt(i));
                break;
            case SELECTOR_SELECTED:
                builder.append("SELECTED=").append(mSelectorAttributes.valueAt(i));
                break;
            case SELECTOR_ID:
                builder.append("ID=").append(mSelectorAttributes.valueAt(i));
                break;
            case SELECTOR_CHILD:
                if (all)
                    builder.append("CHILD=").append(mSelectorAttributes.valueAt(i));
                else
                    builder.append("CHILD[..]");
                break;
            case SELECTOR_PATTERN:
                if (all)
                    builder.append("PATTERN=").append(mSelectorAttributes.valueAt(i));
                else
                    builder.append("PATTERN[..]");
                break;
            case SELECTOR_CONTAINER:
                if (all)
                    builder.append("CONTAINER=").append(mSelectorAttributes.valueAt(i));
                else
                    builder.append("CONTAINER[..]");
                break;
            case SELECTOR_PARENT:
                if (all)
                    builder.append("PARENT=").append(mSelectorAttributes.valueAt(i));
                else
                    builder.append("PARENT[..]");
                break;
            case SELECTOR_COUNT:
                builder.append("COUNT=").append(mSelectorAttributes.valueAt(i));
                break;
            case SELECTOR_PACKAGE_NAME:
                builder.append("PACKAGE NAME=").append(mSelectorAttributes.valueAt(i));
                break;
            case SELECTOR_PACKAGE_NAME_REGEX:
                builder.append("PACKAGE_NAME_REGEX=").append(mSelectorAttributes.valueAt(i));
                break;
            case SELECTOR_RESOURCE_ID:
                builder.append("RESOURCE_ID=").append(mSelectorAttributes.valueAt(i));
                break;
            case SELECTOR_RESOURCE_ID_REGEX:
                builder.append("RESOURCE_ID_REGEX=").append(mSelectorAttributes.valueAt(i));
                break;
            default:
                builder.append("UNDEFINED="+criterion+" ").append(mSelectorAttributes.valueAt(i));
            }
        }
        builder.append("]");
        return builder.toString();
    }
}
