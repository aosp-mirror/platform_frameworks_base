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

import android.app.UiAutomation.OnAccessibilityEventListener;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;


/**
 * The QueryController main purpose is to translate a {@link UiSelector} selectors to
 * {@link AccessibilityNodeInfo}. This is all this controller does.
 */
class QueryController {

    private static final String LOG_TAG = QueryController.class.getSimpleName();

    private static final boolean DEBUG = Log.isLoggable(LOG_TAG, Log.DEBUG);
    private static final boolean VERBOSE = Log.isLoggable(LOG_TAG, Log.VERBOSE);

    private final UiAutomatorBridge mUiAutomatorBridge;

    private final Object mLock = new Object();

    private String mLastActivityName = null;

    // During a pattern selector search, the recursive pattern search
    // methods will track their counts and indexes here.
    private int mPatternCounter = 0;
    private int mPatternIndexer = 0;

    // These help show each selector's search context as it relates to the previous sub selector
    // matched. When a compound selector fails, it is hard to tell which part of it is failing.
    // Seeing how a selector is being parsed and which sub selector failed within a long list
    // of compound selectors is very helpful.
    private int mLogIndent = 0;
    private int mLogParentIndent = 0;

    private String mLastTraversedText = "";

    public QueryController(UiAutomatorBridge bridge) {
        mUiAutomatorBridge = bridge;
        bridge.setOnAccessibilityEventListener(new OnAccessibilityEventListener() {
            @Override
            public void onAccessibilityEvent(AccessibilityEvent event) {
                synchronized (mLock) {
                    switch(event.getEventType()) {
                        case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                            // don't trust event.getText(), check for nulls
                            if (event.getText() != null && event.getText().size() > 0) {
                                if(event.getText().get(0) != null)
                                    mLastActivityName = event.getText().get(0).toString();
                            }
                           break;
                        case AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY:
                            // don't trust event.getText(), check for nulls
                            if (event.getText() != null && event.getText().size() > 0)
                                if(event.getText().get(0) != null)
                                    mLastTraversedText = event.getText().get(0).toString();
                            if (DEBUG)
                                Log.d(LOG_TAG, "Last text selection reported: " +
                                        mLastTraversedText);
                            break;
                    }
                    mLock.notifyAll();
                }
            }
        });
    }

    /**
     * Returns the last text selection reported by accessibility
     * event TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY. One way to cause
     * this event is using a DPad arrows to focus on UI elements.
     */
    public String getLastTraversedText() {
        mUiAutomatorBridge.waitForIdle();
        synchronized (mLock) {
            if (mLastTraversedText.length() > 0) {
                return mLastTraversedText;
            }
        }
        return null;
    }

    /**
     * Clears the last text selection value saved from the TYPE_VIEW_TEXT_SELECTION_CHANGED
     * event
     */
    public void clearLastTraversedText() {
        mUiAutomatorBridge.waitForIdle();
        synchronized (mLock) {
            mLastTraversedText = "";
        }
    }

    private void initializeNewSearch() {
        mPatternCounter = 0;
        mPatternIndexer = 0;
        mLogIndent = 0;
        mLogParentIndent = 0;
    }

    /**
     * Counts the instances of the selector group. The selector must be in the following
     * format: [container_selector, PATTERN=[INSTANCE=x, PATTERN=[the_pattern]]
     * where the container_selector is used to find the containment region to search for patterns
     * and the INSTANCE=x is the instance of the_pattern to return.
     * @param selector
     * @return number of pattern matches. Returns 0 for all other cases.
     */
    public int getPatternCount(UiSelector selector) {
        findAccessibilityNodeInfo(selector, true /*counting*/);
        return mPatternCounter;
    }

    /**
     * Main search method for translating By selectors to AccessibilityInfoNodes
     * @param selector
     * @return AccessibilityNodeInfo
     */
    public AccessibilityNodeInfo findAccessibilityNodeInfo(UiSelector selector) {
        return findAccessibilityNodeInfo(selector, false);
    }

    protected AccessibilityNodeInfo findAccessibilityNodeInfo(UiSelector selector,
            boolean isCounting) {
        mUiAutomatorBridge.waitForIdle();
        initializeNewSearch();

        if (DEBUG)
            Log.d(LOG_TAG, "Searching: " + selector);

        synchronized (mLock) {
            AccessibilityNodeInfo rootNode = getRootNode();
            if (rootNode == null) {
                Log.e(LOG_TAG, "Cannot proceed when root node is null. Aborted search");
                return null;
            }

            // Copy so that we don't modify the original's sub selectors
            UiSelector uiSelector = new UiSelector(selector);
            return translateCompoundSelector(uiSelector, rootNode, isCounting);
        }
    }

    /**
     * Gets the root node from accessibility and if it fails to get one it will
     * retry every 250ms for up to 1000ms.
     * @return null if no root node is obtained
     */
    protected AccessibilityNodeInfo getRootNode() {
        final int maxRetry = 4;
        final long waitInterval = 250;
        AccessibilityNodeInfo rootNode = null;
        for(int x = 0; x < maxRetry; x++) {
            rootNode = mUiAutomatorBridge.getRootInActiveWindow();
            if (rootNode != null) {
                return rootNode;
            }
            if(x < maxRetry - 1) {
                Log.e(LOG_TAG, "Got null root node from accessibility - Retrying...");
                SystemClock.sleep(waitInterval);
            }
        }
        return rootNode;
    }

    /**
     * A compoundSelector encapsulate both Regular and Pattern selectors. The formats follows:
     * <p/>
     * regular_selector = By[attributes... CHILD=By[attributes... CHILD=By[....]]]
     * <br/>
     * pattern_selector = ...CONTAINER=By[..] PATTERN=By[instance=x PATTERN=[regular_selector]
     * <br/>
     * compound_selector = [regular_selector [pattern_selector]]
     * <p/>
     * regular_selectors are the most common form of selectors and the search for them
     * is straightforward. On the other hand pattern_selectors requires search to be
     * performed as in regular_selector but where regular_selector search returns immediately
     * upon a successful match, the search for pattern_selector continues until the
     * requested matched _instance_ of that pattern is matched.
     * <p/>
     * Counting UI objects requires using pattern_selectors. The counting search is the same
     * as a pattern_search however we're not looking to match an instance of the pattern but
     * rather continuously walking the accessibility node hierarchy while counting matched
     * patterns, until the end of the tree.
     * <p/>
     * If both present, order of parsing begins with CONTAINER followed by PATTERN then the
     * top most selector is processed as regular_selector within the context of the previous
     * CONTAINER and its PATTERN information. If neither is present then the top selector is
     * directly treated as regular_selector. So the presence of a CONTAINER and PATTERN within
     * a selector simply dictates that the selector matching will be constraint to the sub tree
     * node where the CONTAINER and its child PATTERN have identified.
     * @param selector
     * @param fromNode
     * @param isCounting
     * @return AccessibilityNodeInfo
     */
    private AccessibilityNodeInfo translateCompoundSelector(UiSelector selector,
            AccessibilityNodeInfo fromNode, boolean isCounting) {

        // Start translating compound selectors by translating the regular_selector first
        // The regular_selector is then used as a container for any optional pattern_selectors
        // that may or may not be specified.
        if(selector.hasContainerSelector())
            // nested pattern selectors
            if(selector.getContainerSelector().hasContainerSelector()) {
                fromNode = translateCompoundSelector(
                        selector.getContainerSelector(), fromNode, false);
                initializeNewSearch();
            } else
                fromNode = translateReqularSelector(selector.getContainerSelector(), fromNode);
        else
            fromNode = translateReqularSelector(selector, fromNode);

        if(fromNode == null) {
            if (DEBUG)
                Log.d(LOG_TAG, "Container selector not found: " + selector.dumpToString(false));
            return null;
        }

        if(selector.hasPatternSelector()) {
            fromNode = translatePatternSelector(selector.getPatternSelector(),
                    fromNode, isCounting);

            if (isCounting) {
                Log.i(LOG_TAG, String.format(
                        "Counted %d instances of: %s", mPatternCounter, selector));
                return null;
            } else {
                if(fromNode == null) {
                    if (DEBUG)
                        Log.d(LOG_TAG, "Pattern selector not found: " +
                                selector.dumpToString(false));
                    return null;
                }
            }
        }

        // translate any additions to the selector that may have been added by tests
        // with getChild(By selector) after a container and pattern selectors
        if(selector.hasContainerSelector() || selector.hasPatternSelector()) {
            if(selector.hasChildSelector() || selector.hasParentSelector())
                fromNode = translateReqularSelector(selector, fromNode);
        }

        if(fromNode == null) {
            if (DEBUG)
                Log.d(LOG_TAG, "Object Not Found for selector " + selector);
            return null;
        }
        Log.i(LOG_TAG, String.format("Matched selector: %s <<==>> [%s]", selector, fromNode));
        return fromNode;
    }

    /**
     * Used by the {@link #translateCompoundSelector(UiSelector, AccessibilityNodeInfo, boolean)}
     * to translate the regular_selector portion. It has the following format:
     * <p/>
     * regular_selector = By[attributes... CHILD=By[attributes... CHILD=By[....]]]<br/>
     * <p/>
     * regular_selectors are the most common form of selectors and the search for them
     * is straightforward. This method will only look for CHILD or PARENT sub selectors.
     * <p/>
     * @param selector
     * @param fromNode
     * @return AccessibilityNodeInfo if found else null
     */
    private AccessibilityNodeInfo translateReqularSelector(UiSelector selector,
            AccessibilityNodeInfo fromNode) {

        return findNodeRegularRecursive(selector, fromNode, 0);
    }

    private AccessibilityNodeInfo findNodeRegularRecursive(UiSelector subSelector,
            AccessibilityNodeInfo fromNode, int index) {

        if (subSelector.isMatchFor(fromNode, index)) {
            if (DEBUG) {
                Log.d(LOG_TAG, formatLog(String.format("%s",
                        subSelector.dumpToString(false))));
            }
            if(subSelector.isLeaf()) {
                return fromNode;
            }
            if(subSelector.hasChildSelector()) {
                mLogIndent++; // next selector
                subSelector = subSelector.getChildSelector();
                if(subSelector == null) {
                    Log.e(LOG_TAG, "Error: A child selector without content");
                    return null; // there is an implementation fault
                }
            } else if(subSelector.hasParentSelector()) {
                mLogIndent++; // next selector
                subSelector = subSelector.getParentSelector();
                if(subSelector == null) {
                    Log.e(LOG_TAG, "Error: A parent selector without content");
                    return null; // there is an implementation fault
                }
                // the selector requested we start at this level from
                // the parent node from the one we just matched
                fromNode = fromNode.getParent();
                if(fromNode == null)
                    return null;
            }
        }

        int childCount = fromNode.getChildCount();
        boolean hasNullChild = false;
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo childNode = fromNode.getChild(i);
            if (childNode == null) {
                Log.w(LOG_TAG, String.format(
                        "AccessibilityNodeInfo returned a null child (%d of %d)", i, childCount));
                if (!hasNullChild) {
                    Log.w(LOG_TAG, String.format("parent = %s", fromNode.toString()));
                }
                hasNullChild = true;
                continue;
            }
            if (!childNode.isVisibleToUser()) {
                if (VERBOSE)
                    Log.v(LOG_TAG,
                            String.format("Skipping invisible child: %s", childNode.toString()));
                continue;
            }
            AccessibilityNodeInfo retNode = findNodeRegularRecursive(subSelector, childNode, i);
            if (retNode != null) {
                return retNode;
            }
        }
        return null;
    }

    /**
     * Used by the {@link #translateCompoundSelector(UiSelector, AccessibilityNodeInfo, boolean)}
     * to translate the pattern_selector portion. It has the following format:
     * <p/>
     * pattern_selector = ... PATTERN=By[instance=x PATTERN=[regular_selector]]<br/>
     * <p/>
     * pattern_selectors requires search to be performed as regular_selector but where
     * regular_selector search returns immediately upon a successful match, the search for
     * pattern_selector continues until the requested matched instance of that pattern is
     * encountered.
     * <p/>
     * Counting UI objects requires using pattern_selectors. The counting search is the same
     * as a pattern_search however we're not looking to match an instance of the pattern but
     * rather continuously walking the accessibility node hierarchy while counting patterns
     * until the end of the tree.
     * @param subSelector
     * @param fromNode
     * @param isCounting
     * @return null of node is not found or if counting mode is true.
     * See {@link #translateCompoundSelector(UiSelector, AccessibilityNodeInfo, boolean)}
     */
    private AccessibilityNodeInfo translatePatternSelector(UiSelector subSelector,
            AccessibilityNodeInfo fromNode, boolean isCounting) {

        if(subSelector.hasPatternSelector()) {
            // Since pattern_selectors are also the type of selectors used when counting,
            // we check if this is a counting run or an indexing run
            if(isCounting)
                //since we're counting, we reset the indexer so to terminates the search when
                // the end of tree is reached. The count will be in mPatternCount
                mPatternIndexer = -1;
            else
                // terminates the search once we match the pattern's instance
                mPatternIndexer = subSelector.getInstance();

            // A pattern is wrapped in a PATTERN[instance=x PATTERN[the_pattern]]
            subSelector = subSelector.getPatternSelector();
            if(subSelector == null) {
                Log.e(LOG_TAG, "Pattern portion of the selector is null or not defined");
                return null; // there is an implementation fault
            }
            // save the current indent level as parent indent before pattern searches
            // begin under the current tree position.
            mLogParentIndent = ++mLogIndent;
            return findNodePatternRecursive(subSelector, fromNode, 0, subSelector);
        }

        Log.e(LOG_TAG, "Selector must have a pattern selector defined"); // implementation fault?
        return null;
    }

    private AccessibilityNodeInfo findNodePatternRecursive(
            UiSelector subSelector, AccessibilityNodeInfo fromNode, int index,
            UiSelector originalPattern) {

        if (subSelector.isMatchFor(fromNode, index)) {
            if(subSelector.isLeaf()) {
                if(mPatternIndexer == 0) {
                    if (DEBUG)
                        Log.d(LOG_TAG, formatLog(
                                String.format("%s", subSelector.dumpToString(false))));
                    return fromNode;
                } else {
                    if (DEBUG)
                        Log.d(LOG_TAG, formatLog(
                                String.format("%s", subSelector.dumpToString(false))));
                    mPatternCounter++; //count the pattern matched
                    mPatternIndexer--; //decrement until zero for the instance requested

                    // At a leaf selector within a group and still not instance matched
                    // then reset the  selector to continue search from current position
                    // in the accessibility tree for the next pattern match up until the
                    // pattern index hits 0.
                    subSelector = originalPattern;
                    // starting over with next pattern search so reset to parent level
                    mLogIndent = mLogParentIndent;
                }
            } else {
                if (DEBUG)
                    Log.d(LOG_TAG, formatLog(
                            String.format("%s", subSelector.dumpToString(false))));

                if(subSelector.hasChildSelector()) {
                    mLogIndent++; // next selector
                    subSelector = subSelector.getChildSelector();
                    if(subSelector == null) {
                        Log.e(LOG_TAG, "Error: A child selector without content");
                        return null;
                    }
                } else if(subSelector.hasParentSelector()) {
                    mLogIndent++; // next selector
                    subSelector = subSelector.getParentSelector();
                    if(subSelector == null) {
                        Log.e(LOG_TAG, "Error: A parent selector without content");
                        return null;
                    }
                    fromNode = fromNode.getParent();
                    if(fromNode == null)
                        return null;
                }
            }
        }

        int childCount = fromNode.getChildCount();
        boolean hasNullChild = false;
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo childNode = fromNode.getChild(i);
            if (childNode == null) {
                Log.w(LOG_TAG, String.format(
                        "AccessibilityNodeInfo returned a null child (%d of %d)", i, childCount));
                if (!hasNullChild) {
                    Log.w(LOG_TAG, String.format("parent = %s", fromNode.toString()));
                }
                hasNullChild = true;
                continue;
            }
            if (!childNode.isVisibleToUser()) {
                if (DEBUG)
                    Log.d(LOG_TAG,
                        String.format("Skipping invisible child: %s", childNode.toString()));
                continue;
            }
            AccessibilityNodeInfo retNode = findNodePatternRecursive(
                    subSelector, childNode, i, originalPattern);
            if (retNode != null) {
                return retNode;
            }
        }
        return null;
    }

    public AccessibilityNodeInfo getAccessibilityRootNode() {
        return mUiAutomatorBridge.getRootInActiveWindow();
    }

    /**
     * Last activity to report accessibility events.
     * @deprecated The results returned should be considered unreliable
     * @return String name of activity
     */
    @Deprecated
    public String getCurrentActivityName() {
        mUiAutomatorBridge.waitForIdle();
        synchronized (mLock) {
            return mLastActivityName;
        }
    }

    /**
     * Last package to report accessibility events
     * @return String name of package
     */
    public String getCurrentPackageName() {
        mUiAutomatorBridge.waitForIdle();
        AccessibilityNodeInfo rootNode = getRootNode();
        if (rootNode == null)
            return null;
        return rootNode.getPackageName() != null ? rootNode.getPackageName().toString() : null;
    }

    private String formatLog(String str) {
        StringBuilder l = new StringBuilder();
        for(int space = 0; space < mLogIndent; space++)
            l.append(". . ");
        if(mLogIndent > 0)
            l.append(String.format(". . [%d]: %s", mPatternCounter, str));
        else
            l.append(String.format(". . [%d]: %s", mPatternCounter, str));
        return l.toString();
    }
}
