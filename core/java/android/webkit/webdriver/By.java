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

import java.util.List;

/**
 * Mechanism to locate elements within the DOM of the page.
 * @hide
 */
public abstract class By {
    public abstract WebElement findElement(WebElement element);
    public abstract List<WebElement> findElements(WebElement element);

    /**
     * Locates an element by its HTML id attribute.
     *
     * @param id The HTML id attribute to look for.
     * @return A By instance that locates elements by their HTML id attributes.
     */
    public static By id(final String id) {
        throwIfNull(id);
        return new By() {
            @Override
            public WebElement findElement(WebElement element) {
                return element.findElementById(id);
            }

            @Override
            public List<WebElement> findElements(WebElement element) {
                return element.findElementsById(id); // Yes, it happens a lot.
            }

            @Override
            public String toString() {
                return "By.id: " + id;
            }
        };
    }

    /**
     * Locates an element by the matching the exact text on the HTML link.
     *
     * @param linkText The exact text to match against.
     * @return A By instance that locates elements by the text displayed by
     * the link.
     */
    public static By linkText(final String linkText) {
        throwIfNull(linkText);
        return new By() {
            @Override
            public WebElement findElement(WebElement element) {
                return element.findElementByLinkText(linkText);
            }

            @Override
            public List<WebElement> findElements(WebElement element) {
                return element.findElementsByLinkText(linkText);
            }

            @Override
            public String toString() {
                return "By.linkText: " + linkText;
            }
        };
    }

    /**
     * Locates an element by matching partial part of the text displayed by an
     * HTML link.
     *
     * @param linkText The text that should be contained by the text displayed
     * on the link.
     * @return A By instance that locates elements that contain the given link
     * text.
     */
    public static By partialLinkText(final String linkText) {
        throwIfNull(linkText);
        return new By() {
            @Override
            public WebElement findElement(WebElement element) {
                return element.findElementByPartialLinkText(linkText);
            }

            @Override
            public List<WebElement> findElements(WebElement element) {
                return element.findElementsByPartialLinkText(linkText);
            }

            @Override
            public String toString() {
                return "By.partialLinkText: " + linkText;
            }
        };
    }

    /**
     * Locates an element by matching its HTML name attribute.
     *
     * @param name The value of the HTML name attribute.
     * @return A By instance that locates elements by the HTML name attribute.
     */
    public static By name(final String name) {
        throwIfNull(name);
        return new By() {
            @Override
            public WebElement findElement(WebElement element) {
                return element.findElementByName(name);
            }

            @Override
            public List<WebElement> findElements(WebElement element) {
                return element.findElementsByName(name);
            }

            @Override
            public String toString() {
                return "By.name: " + name;
            }
        };
    }

    /**
     * Locates an element by matching its class name.
     * @param className The class name
     * @return A By instance that locates elements by their class name attribute.
     */
    public static By className(final String className) {
        throwIfNull(className);
        return new By() {
            @Override
            public WebElement findElement(WebElement element) {
                return element.findElementByClassName(className);
            }

            @Override
            public List<WebElement> findElements(WebElement element) {
                return element.findElementsByClassName(className);
            }

            @Override
            public String toString() {
                return "By.className: " + className;
            }
        };
    }

    /**
     * Locates an element by matching its css property.
     *
     * @param css The css property.
     * @return A By instance that locates elements by their css property.
     */
    public static By css(final String css) {
        throwIfNull(css);
        return new By() {
            @Override
            public WebElement findElement(WebElement element) {
                return element.findElementByCss(css);
            }

            @Override
            public List<WebElement> findElements(WebElement element) {
                return element.findElementsByCss(css);
            }

            @Override
            public String toString() {
                return "By.css: " + css;
            }
        };
    }

    /**
     * Locates an element by matching its HTML tag name.
     *
     * @param tagName The HTML tag name to look for.
     * @return A By instance that locates elements using the name of the
     * HTML tag.
     */
    public static By tagName(final String tagName) {
        throwIfNull(tagName);
        return new By() {
            @Override
            public WebElement findElement(WebElement element) {
                return element.findElementByTagName(tagName);
            }

            @Override
            public List<WebElement> findElements(WebElement element) {
                return element.findElementsByTagName(tagName);
            }

            @Override
            public String toString() {
                return "By.tagName: " + tagName;
            }
        };
    }

    /**
     * Locates an element using an XPath expression.
     *
     * <p>When using XPath, be aware that this follows standard conventions: a
     * search prefixed with "//" will search the entire document, not just the
     * children of the current node. Use ".//" to limit your search to the
     * children of this {@link android.webkit.webdriver.WebElement}.
     *
     * @param xpath The XPath expression to use.
     * @return A By instance that locates elements using the given XPath.
     */
    public static By xpath(final String xpath) {
        throwIfNull(xpath);
        return new By() {
            @Override
            public WebElement findElement(WebElement element) {
                return element.findElementByXPath(xpath);
            }

            @Override
            public List<WebElement> findElements(WebElement element) {
                return element.findElementsByXPath(xpath);
            }

            @Override
            public String toString() {
                return "By.xpath: " + xpath;
            }
        };
    }

    private static void throwIfNull(String argument) {
        if (argument == null) {
            throw new IllegalArgumentException(
                    "Cannot find elements with null locator.");
        }
    }
}
