/*
 * Copyright (C) 2013 The Android Open Source Project
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

/**
 * Allows you to set key parameters for running uiautomator tests. The new
 * settings take effect immediately and can be changed any time during a test run.
 *
 * To modify parameters using Configurator, first obtain an instance by calling
 * {@link #getInstance()}. As a best practice, make sure you always save
 * the original value of any parameter that you are modifying. After running your
 * tests with the modified parameters, make sure to also restore
 * the original parameter values, otherwise this will impact other tests cases.
 * @since API Level 18
 */
public final class Configurator {
    private long mWaitForIdleTimeout = 10 * 1000;
    private long mWaitForSelector = 10 * 1000;
    private long mWaitForActionAcknowledgment = 3 * 1000;

    // The events for a scroll typically complete even before touchUp occurs.
    // This short timeout to make sure we get the very last in cases where the above isn't true.
    private long mScrollEventWaitTimeout = 200; // ms

    // Default is inject as fast as we can
    private long mKeyInjectionDelay = 0; // ms

    // reference to self
    private static Configurator sConfigurator;

    private Configurator() {
        /* hide constructor */
    }

    /**
     * Retrieves a singleton instance of Configurator.
     *
     * @return Configurator instance
     * @since API Level 18
     */
    public static Configurator getInstance() {
        if (sConfigurator == null) {
            sConfigurator = new Configurator();
        }
        return sConfigurator;
    }

    /**
     * Sets the timeout for waiting for the user interface to go into an idle
     * state before starting a uiautomator action.
     *
     * By default, all core uiautomator objects except {@link UiDevice} will perform
     * this wait before starting to search for the widget specified by the
     * object's {@link UiSelector}. Once the idle state is detected or the
     * timeout elapses (whichever occurs first), the object will start to wait
     * for the selector to find a match.
     * See {@link #setWaitForSelectorTimeout(long)}
     *
     * @param timeout Timeout value in milliseconds
     * @return self
     * @since API Level 18
     */
    public Configurator setWaitForIdleTimeout(long timeout) {
        mWaitForIdleTimeout = timeout;
        return this;
    }

    /**
     * Gets the current timeout used for waiting for the user interface to go
     * into an idle state.
     *
     * By default, all core uiautomator objects except {@link UiDevice} will perform
     * this wait before starting to search for the widget specified by the
     * object's {@link UiSelector}. Once the idle state is detected or the
     * timeout elapses (whichever occurs first), the object will start to wait
     * for the selector to find a match.
     * See {@link #setWaitForSelectorTimeout(long)}
     *
     * @return Current timeout value in milliseconds
     * @since API Level 18
     */
    public long getWaitForIdleTimeout() {
        return mWaitForIdleTimeout;
    }

    /**
     * Sets the timeout for waiting for a widget to become visible in the user
     * interface so that it can be matched by a selector.
     *
     * Because user interface content is dynamic, sometimes a widget may not
     * be visible immediately and won't be detected by a selector. This timeout
     * allows the uiautomator framework to wait for a match to be found, up until
     * the timeout elapses.
     *
     * @param timeout Timeout value in milliseconds.
     * @return self
     * @since API Level 18
     */
    public Configurator setWaitForSelectorTimeout(long timeout) {
        mWaitForSelector = timeout;
        return this;
    }

    /**
     * Gets the current timeout for waiting for a widget to become visible in
     * the user interface so that it can be matched by a selector.
     *
     * Because user interface content is dynamic, sometimes a widget may not
     * be visible immediately and won't be detected by a selector. This timeout
     * allows the uiautomator framework to wait for a match to be found, up until
     * the timeout elapses.
     *
     * @return Current timeout value in milliseconds
     * @since API Level 18
     */
    public long getWaitForSelectorTimeout() {
        return mWaitForSelector;
    }

    /**
     * Sets the timeout for waiting for an acknowledgement of an
     * uiautomtor scroll swipe action.
     *
     * The acknowledgment is an <a href="http://developer.android.com/reference/android/view/accessibility/AccessibilityEvent.html">AccessibilityEvent</a>,
     * corresponding to the scroll action, that lets the framework determine if
     * the scroll action was successful. Generally, this timeout should not be modified.
     * See {@link UiScrollable}
     *
     * @param timeout Timeout value in milliseconds
     * @return self
     * @since API Level 18
     */
    public Configurator setScrollAcknowledgmentTimeout(long timeout) {
        mScrollEventWaitTimeout = timeout;
        return this;
    }

    /**
     * Gets the timeout for waiting for an acknowledgement of an
     * uiautomtor scroll swipe action.
     *
     * The acknowledgment is an <a href="http://developer.android.com/reference/android/view/accessibility/AccessibilityEvent.html">AccessibilityEvent</a>,
     * corresponding to the scroll action, that lets the framework determine if
     * the scroll action was successful. Generally, this timeout should not be modified.
     * See {@link UiScrollable}
     *
     * @return current timeout in milliseconds
     * @since API Level 18
     */
    public long getScrollAcknowledgmentTimeout() {
        return mScrollEventWaitTimeout;
    }

    /**
     * Sets the timeout for waiting for an acknowledgment of generic uiautomator
     * actions, such as clicks, text setting, and menu presses.
     *
     * The acknowledgment is an <a href="http://developer.android.com/reference/android/view/accessibility/AccessibilityEvent.html">AccessibilityEvent</a>,
     * corresponding to an action, that lets the framework determine if the
     * action was successful. Generally, this timeout should not be modified.
     * See {@link UiObject}
     *
     * @param timeout Timeout value in milliseconds
     * @return self
     * @since API Level 18
     */
    public Configurator setActionAcknowledgmentTimeout(long timeout) {
        mWaitForActionAcknowledgment = timeout;
        return this;
    }

    /**
     * Gets the current timeout for waiting for an acknowledgment of generic
     * uiautomator actions, such as clicks, text setting, and menu presses.
     *
     * The acknowledgment is an <a href="http://developer.android.com/reference/android/view/accessibility/AccessibilityEvent.html">AccessibilityEvent</a>,
     * corresponding to an action, that lets the framework determine if the
     * action was successful. Generally, this timeout should not be modified.
     * See {@link UiObject}
     *
     * @return current timeout in milliseconds
     * @since API Level 18
     */
    public long getActionAcknowledgmentTimeout() {
        return mWaitForActionAcknowledgment;
    }

    /**
     * Sets a delay between key presses when injecting text input.
     * See {@link UiObject#setText(String)}
     *
     * @param delay Delay value in milliseconds
     * @return self
     * @since API Level 18
     */
    public Configurator setKeyInjectionDelay(long delay) {
        mKeyInjectionDelay = delay;
        return this;
    }

    /**
     * Gets the current delay between key presses when injecting text input.
     * See {@link UiObject#setText(String)}
     *
     * @return current delay in milliseconds
     * @since API Level 18
     */
    public long getKeyInjectionDelay() {
        return mKeyInjectionDelay;
    }
}
