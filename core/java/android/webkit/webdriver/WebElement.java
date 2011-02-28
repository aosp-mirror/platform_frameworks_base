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

/**
 * Represents an HTML element. Typically most interactions with a web page
 * will be performed through this class.
 *
 * @hide
 */
public class WebElement {
    private final String mId;
    private final WebDriver mDriver;

    /**
     * Package constructor to prevent clients from creating a new WebElement
     * instance.
     *
     * <p> A WebElement represents an HTML element on the page.
     * The corresponding HTML element is stored in a JS cache in the page
     * that can be accessed through JavaScript using "bot.inject.cache".
     *
     * @param driver The WebDriver instance to use.
     * @param id The index of the HTML element in the JavaSctipt cache. Pass
     * an empty String to indicate that this is the
     * document.documentElement object.
     */
    /* Package */ WebElement(final WebDriver driver, final String id) {
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
     * Gets the visisble (i.e. not hidden by CSS) innerText of this element,
     * inlcuding sub-elements.
     *
     * @return the innerText of this element.
     * @throws {@link android.webkit.webdriver.WebElementStaleException} if this
     * element is stale, i.e. not on the current DOM.
     */
    public String getText() {
        String getText = mDriver.getResourceAsString(R.raw.get_text_android);
        if (mId.equals("")) {
            return null;
        }
        return (String) executeAtom(getText, this);
    }

    /*package*/ String getId() {
        return mId;
    }

    /* package */ WebElement findElementById(final String locator) {
        return findElement("id", locator);
    }

    /* package */ WebElement findElementByLinkText(final String linkText) {
        return findElement("linkText", linkText);
    }

    /* package */ WebElement findElementByPartialLinkText(
            final String linkText) {
        return findElement("partialLinkText", linkText);
    }

    /* package */ WebElement findElementByName(final String name) {
        return findElement("name", name);
    }

    /* package */ WebElement findElementByClassName(final String className) {
        return findElement("className", className);
    }

    /* package */ WebElement findElementByCss(final String css) {
        return findElement("css", css);
    }

    /* package */ WebElement findElementByTagName(final String tagName) {
        return findElement("tagName", tagName);
    }

    /* package */ WebElement findElementByXPath(final String xpath) {
        return findElement("xpath", xpath);
    }

    private Object executeAtom(final String atom, final Object... args) {
        String scriptArgs = mDriver.convertToJsArgs(args);
        return mDriver.executeRawJavascript("(" +
                atom + ")(" + scriptArgs + ")");
    }

    private WebElement findElement(String strategy, String locator) {
        String findElement = mDriver.getResourceAsString(
                R.raw.find_element_android);
        WebElement el;
        if (mId.equals("")) {
            // Use default as root which is the document object
            el = (WebElement) executeAtom(findElement, strategy, locator);
        } else {
            // Use this as root
            el = (WebElement) executeAtom(findElement, strategy, locator, this);
        }
        if (el == null) {
            throw new WebElementNotFoundException("Could not find element "
                    + "with " + strategy + ": " + locator);
        }
        return el;
    }
}
