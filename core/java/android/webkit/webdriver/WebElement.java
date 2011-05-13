/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.webkit.webdriver;

import android.graphics.Point;
import android.view.KeyEvent;

import com.android.internal.R;

import java.util.List;
import java.util.Map;

/**
 * Represents an HTML element. Typically most interactions with a web page
 * will be performed through this class.
 *
 * @hide
 */
public class WebElement {
    private final String mId;
    private final WebDriver mDriver;

    private static final String LOCATOR_ID = "id";
    private static final String LOCATOR_LINK_TEXT = "linkText";
    private static final String LOCATOR_PARTIAL_LINK_TEXT = "partialLinkText";
    private static final String LOCATOR_NAME = "name";
    private static final String LOCATOR_CLASS_NAME = "className";
    private static final String LOCATOR_CSS = "css";
    private static final String LOCATOR_TAG_NAME = "tagName";
    private static final String LOCATOR_XPATH = "xpath";

    /**
     * Package constructor to prevent clients from creating a new WebElement
     * instance.
     *
     * <p> A WebElement represents an HTML element on the page.
     * The corresponding HTML element is stored in a JS cache in the page
     * that can be accessed through JavaScript using "bot.inject.cache".
     *
     * @param driver The WebDriver instance to use.
     * @param id The index of the HTML element in the JavaSctipt cache.
     * document.documentElement object.
     */
    /* package */ WebElement(final WebDriver driver, final String id) {
        this.mId = id;
        this.mDriver = driver;
    }

    /**
     * Finds the first {@link android.webkit.webdriver.WebElement} using the
     * given method.
     *
     * @param by The locating mechanism to use.
     * @return The first matching element on the current context.
     */
    public WebElement findElement(final By by) {
        return by.findElement(this);
    }

     /**
     * Finds all {@link android.webkit.webdriver.WebElement} within the page
     * using the given method.
     *
     * @param by The locating mechanism to use.
     * @return A list of all {@link android.webkit.webdriver.WebElement} found,
     * or an empty list if nothing matches.
     */
    public List<WebElement> findElements(final By by) {
        return by.findElements(this);
    }

    /**
     * Gets the visisble (i.e. not hidden by CSS) innerText of this element,
     * inlcuding sub-elements.
     *
     * @return the innerText of this element.
     * @throws {@link android.webkit.webdriver.WebElementStaleException} if this
     * element is stale, i.e. not on the current DOM.
     */
    public String getText() {
        String getText = mDriver.getResourceAsString(R.raw.get_text_android);
        return (String) executeAtom(getText, this);
    }

    /**
     * Gets the value of an HTML attribute for this element or the value of the
     * property with the same name if the attribute is not present. If neither
     * is set, null is returned.
     *
     * @param attribute the HTML attribute.
     * @return the value of that attribute or the value of the property with the
     * same name if the attribute is not set, or null if neither are set. For
     * boolean attribute values this will return the string "true" or "false".
     */
    public String getAttribute(String attribute) {
        String getAttribute = mDriver.getResourceAsString(
                R.raw.get_attribute_value_android);
        return (String) executeAtom(getAttribute, this, attribute);
    }

    /**
     * @return the tag name of this element.
     */
    public String getTagName() {
        return (String) mDriver.executeScript("return arguments[0].tagName;",
                this);
    }

    /**
     * @return true if this element is enabled, false otherwise.
     */
    public boolean isEnabled() {
        String isEnabled = mDriver.getResourceAsString(
                R.raw.is_enabled_android);
        return (Boolean) executeAtom(isEnabled, this);
    }

    /**
     * Determines whether this element is selected or not. This applies to input
     * elements such as checkboxes, options in a select, and radio buttons.
     *
     * @return True if this element is selected, false otherwise.
     */
    public boolean isSelected() {
        String isSelected = mDriver.getResourceAsString(
                R.raw.is_selected_android);
        return (Boolean) executeAtom(isSelected, this);
    }

    /**
     * Selects an element on the page. This works for selecting checkboxes,
     * options in a select, and radio buttons.
     */
    public void setSelected() {
        String setSelected = mDriver.getResourceAsString(
                R.raw.set_selected_android);
        executeAtom(setSelected, this);
    }

    /**
     * This toggles the checkboxe state from selected to not selected, or
     * from not selected to selected.
     *
     * @return True if the toggled element is selected, false otherwise.
     */
    public boolean toggle() {
        String toggle = mDriver.getResourceAsString(R.raw.toggle_android);
        return (Boolean) executeAtom(toggle, this);
    }

    /**
     * Sends the KeyEvents for the given sequence of characters to the
     * WebElement to simulate typing. The KeyEvents are generated using the
     * device's {@link android.view.KeyCharacterMap.VIRTUAL_KEYBOARD}.
     *
     * @param keys The keys to send to this WebElement
     */
    public void sendKeys(CharSequence... keys) {
        if (keys == null || keys.length == 0) {
            return;
        }
        click();
        mDriver.moveCursorToRightMostPosition(getAttribute("value"));
        mDriver.sendKeys(keys);
    }

    /**
     * Use this to send one of the key code constants defined in
     * {@link android.view.KeyEvent}
     *
     * @param keys
     */
    public void sendKeyCodes(int... keys) {
        if (keys == null || keys.length == 0) {
            return;
        }
        click();
        mDriver.moveCursorToRightMostPosition(getAttribute("value"));
        mDriver.sendKeyCodes(keys);
    }

    /**
     * Sends a touch event to the center coordinates of this WebElement.
     */
    public void click() {
        Point topLeft = getLocation();
        Point size = getSize();
        int jsX = topLeft.x + size.x/2;
        int jsY = topLeft.y + size.y/2;
        Point center = new Point(jsX, jsY);
        mDriver.sendTouchScreen(center);
    }

    /**
     * Submits the form containing this WebElement.
     */
    public void submit() {
        mDriver.resetPageLoadState();
        String submit = mDriver.getResourceAsString(R.raw.submit_android);
        executeAtom(submit, this);
        mDriver.waitForPageLoadIfNeeded();
    }

    /**
     * Clears the text value if this is a text entry element. Does nothing
     * otherwise.
     */
    public void clear() {
        String value = getAttribute("value");
        if (value == null || value.equals("")) {
            return;
        }
        int length = value.length();
        int[] keys = new int[length];
        for (int i = 0; i < length; i++) {
            keys[i] = KeyEvent.KEYCODE_DEL;
        }
        sendKeyCodes(keys);
    }

    /**
     * @return the value of the given CSS property if found, null otherwise.
     */
    public String getCssValue(String cssProperty) {
        String getCssProp = mDriver.getResourceAsString(
                R.raw.get_value_of_css_property_android);
        return (String) executeAtom(getCssProp, this, cssProperty);
    }

    /**
     * Gets the width and height of the rendered element.
     *
     * @return a {@link android.graphics.Point}, where Point.x represents the
     * width, and Point.y represents the height of the element.
     */
    public Point getSize() {
        String getSize = mDriver.getResourceAsString(R.raw.get_size_android);
        Map<String, Long> map = (Map<String, Long>) executeAtom(getSize, this);
        return new Point(map.get("width").intValue(),
                map.get("height").intValue());
    }

    /**
     * Gets the location of the top left corner of this element on the screen.
     * If the element is not visisble, this will scroll to get the element into
     * the visisble screen.
     *
     * @return a {@link android.graphics.Point} containing the x and y
     * coordinates of the top left corner of this element.
     */
    public Point getLocation() {
        String getLocation = mDriver.getResourceAsString(
                R.raw.get_top_left_coordinates_android);
        Map<String,Long> map = (Map<String, Long>)  executeAtom(getLocation,
                this);
        return new Point(map.get("x").intValue(), map.get("y").intValue());
    }

    /**
     * @return True if the WebElement is displayed on the screen,
     * false otherwise.
     */
    public boolean isDisplayed() {
        String isDisplayed = mDriver.getResourceAsString(
                R.raw.is_displayed_android);
        return (Boolean) executeAtom(isDisplayed, this);
    }

    /*package*/ String getId() {
        return mId;
    }

    /* package */ WebElement findElementById(final String locator) {
        return findElement(LOCATOR_ID, locator);
    }

    /* package */ WebElement findElementByLinkText(final String linkText) {
        return findElement(LOCATOR_LINK_TEXT, linkText);
    }

    /* package */ WebElement findElementByPartialLinkText(
            final String linkText) {
        return findElement(LOCATOR_PARTIAL_LINK_TEXT, linkText);
    }

    /* package */ WebElement findElementByName(final String name) {
        return findElement(LOCATOR_NAME, name);
    }

    /* package */ WebElement findElementByClassName(final String className) {
        return findElement(LOCATOR_CLASS_NAME, className);
    }

    /* package */ WebElement findElementByCss(final String css) {
        return findElement(LOCATOR_CSS, css);
    }

    /* package */ WebElement findElementByTagName(final String tagName) {
        return findElement(LOCATOR_TAG_NAME, tagName);
    }

    /* package */ WebElement findElementByXPath(final String xpath) {
        return findElement(LOCATOR_XPATH, xpath);
    }

        /* package */ List<WebElement> findElementsById(final String locator) {
        return findElements(LOCATOR_ID, locator);
    }

    /* package */ List<WebElement> findElementsByLinkText(final String linkText) {
        return findElements(LOCATOR_LINK_TEXT, linkText);
    }

    /* package */ List<WebElement> findElementsByPartialLinkText(
            final String linkText) {
        return findElements(LOCATOR_PARTIAL_LINK_TEXT, linkText);
    }

    /* package */ List<WebElement> findElementsByName(final String name) {
        return findElements(LOCATOR_NAME, name);
    }

    /* package */ List<WebElement> findElementsByClassName(final String className) {
        return findElements(LOCATOR_CLASS_NAME, className);
    }

    /* package */ List<WebElement> findElementsByCss(final String css) {
        return findElements(LOCATOR_CSS, css);
    }

    /* package */ List<WebElement> findElementsByTagName(final String tagName) {
        return findElements(LOCATOR_TAG_NAME, tagName);
    }

    /* package */ List<WebElement> findElementsByXPath(final String xpath) {
        return findElements(LOCATOR_XPATH, xpath);
    }

    private Object executeAtom(final String atom, final Object... args) {
        String scriptArgs = mDriver.convertToJsArgs(args);
        return mDriver.executeRawJavascript("(" +
                atom + ")(" + scriptArgs + ")");
    }

    private List<WebElement> findElements(String strategy, String locator) {
        String findElements = mDriver.getResourceAsString(
                R.raw.find_elements_android);
        if (mId.equals("")) {
            return (List<WebElement>) executeAtom(findElements,
                    strategy, locator);
        } else {
            return (List<WebElement>) executeAtom(findElements,
                    strategy, locator, this);
        }
    }

    private WebElement findElement(String strategy, String locator) {
        String findElement = mDriver.getResourceAsString(
                R.raw.find_element_android);
        WebElement el;
        if (mId.equals("")) {
            el = (WebElement) executeAtom(findElement,
                    strategy, locator);
        } else {
            el = (WebElement) executeAtom(findElement,
                    strategy, locator, this);
        }
        if (el == null) {
            throw new WebElementNotFoundException("Could not find element "
                    + "with " + strategy + ": " + locator);
        }
        return el;
    }
}
