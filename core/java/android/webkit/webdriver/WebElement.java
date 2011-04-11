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

import com.android.internal.R;

import java.util.List;

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
        return (List<WebElement>) executeAtom(findElements,
                strategy, locator, this);
    }

    private WebElement findElement(String strategy, String locator) {
        String findElement = mDriver.getResourceAsString(
                R.raw.find_element_android);
        WebElement el = (WebElement) executeAtom(findElement,
                strategy, locator, this);
        if (el == null) {
            throw new WebElementNotFoundException("Could not find element "
                    + "with " + strategy + ": " + locator);
        }
        return el;
    }
}
