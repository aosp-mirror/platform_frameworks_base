/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.webkit;

import android.provider.Settings;
import android.text.TextUtils;
import android.text.TextUtils.SimpleStringSplitter;
import android.util.Log;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.webkit.WebViewCore.EventHub;

import java.util.ArrayList;
import java.util.Stack;

/**
 * This class injects accessibility into WebViews with disabled JavaScript or
 * WebViews with enabled JavaScript but for which we have no accessibility
 * script to inject.
 * </p>
 * Note: To avoid changes in the framework upon changing the available
 *       navigation axis, or reordering the navigation axis, or changing
 *       the key bindings, or defining sequence of actions to be bound to
 *       a given key this class is navigation axis agnostic. It is only
 *       aware of one navigation axis which is in fact the default behavior
 *       of webViews while using the DPAD/TrackBall.
 * </p>
 * In general a key binding is a mapping from meta state + key code to
 * a sequence of actions. For more detail how to specify key bindings refer to
 * {@link android.provider.Settings.Secure#ACCESSIBILITY_WEB_CONTENT_KEY_BINDINGS}.
 * </p>
 * The possible actions are invocations to
 * {@link #setCurrentAxis(int, boolean, String)}, or
 * {@link #traverseCurrentAxis(int, boolean, String)}
 * {@link #traverseGivenAxis(int, int, boolean, String)}
 * {@link #prefromAxisTransition(int, int, boolean, String)}
 * referred via the values of:
 * {@link #ACTION_SET_CURRENT_AXIS},
 * {@link #ACTION_TRAVERSE_CURRENT_AXIS},
 * {@link #ACTION_TRAVERSE_GIVEN_AXIS},
 * {@link #ACTION_PERFORM_AXIS_TRANSITION},
 * respectively.
 * The arguments for the action invocation are specified as offset
 * hexademical pairs. Note the last argument of the invocation
 * should NOT be specified in the binding as it is provided by
 * this class. For details about the key binding implementation
 * refer to {@link AccessibilityWebContentKeyBinding}.
 */
class AccessibilityInjector {
    private static final String LOG_TAG = "AccessibilityInjector";

    private static final boolean DEBUG = true;

    private static final int ACTION_SET_CURRENT_AXIS = 0;
    private static final int ACTION_TRAVERSE_CURRENT_AXIS = 1;
    private static final int ACTION_TRAVERSE_GIVEN_AXIS = 2;
    private static final int ACTION_PERFORM_AXIS_TRANSITION = 3;

    // the default WebView behavior abstracted as a navigation axis
    private static final int NAVIGATION_AXIS_DEFAULT_WEB_VIEW_BEHAVIOR = 7;

    // these are the same for all instances so make them process wide
    private static SparseArray<AccessibilityWebContentKeyBinding> sBindings =
        new SparseArray<AccessibilityWebContentKeyBinding>();

    // handle to the WebView this injector is associated with.
    private final WebView mWebView;

    // events scheduled for sending as soon as we receive the selected text
    private final Stack<AccessibilityEvent> mScheduledEventStack = new Stack<AccessibilityEvent>();

    // the current traversal axis
    private int mCurrentAxis = 2; // sentence

    // we need to consume the up if we have handled the last down
    private boolean mLastDownEventHandled;

    // getting two empty selection strings in a row we let the WebView handle the event
    private boolean mIsLastSelectionStringNull;

    // keep track of last direction
    private int mLastDirection;

    /**
     * Creates a new injector associated with a given {@link WebView}.
     *
     * @param webView The associated WebView.
     */
    public AccessibilityInjector(WebView webView) {
        mWebView = webView;
        ensureWebContentKeyBindings();
    }

    /**
     * Processes a key down <code>event</code>.
     *
     * @return True if the event was processed.
     */
    public boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP) {
            return mLastDownEventHandled;
        }

        mLastDownEventHandled = false;

        int key = event.getMetaState() << AccessibilityWebContentKeyBinding.OFFSET_META_STATE |
            event.getKeyCode() << AccessibilityWebContentKeyBinding.OFFSET_KEY_CODE;

        AccessibilityWebContentKeyBinding binding = sBindings.get(key);
        if (binding == null) {
            return false;
        }

        for (int i = 0, count = binding.getActionCount(); i < count; i++) {
            int actionCode = binding.getActionCode(i);
            String contentDescription = Integer.toHexString(binding.getAction(i));
            switch (actionCode) {
                case ACTION_SET_CURRENT_AXIS:
                    int axis = binding.getFirstArgument(i);
                    boolean sendEvent = (binding.getSecondArgument(i) == 1);
                    setCurrentAxis(axis, sendEvent, contentDescription);
                    mLastDownEventHandled = true;
                    break;
                case ACTION_TRAVERSE_CURRENT_AXIS:
                    int direction = binding.getFirstArgument(i);
                    // on second null selection string in same direction => WebView handle the event
                    if (direction == mLastDirection && mIsLastSelectionStringNull) {
                        mLastDirection = direction;
                        mIsLastSelectionStringNull = false;
                        return false;
                    }
                    mLastDirection = direction;
                    sendEvent = (binding.getSecondArgument(i) == 1);
                    mLastDownEventHandled = traverseCurrentAxis(direction, sendEvent,
                            contentDescription);
                    break;
                case ACTION_TRAVERSE_GIVEN_AXIS:
                    direction = binding.getFirstArgument(i);
                    // on second null selection string in same direction => WebView handle the event
                    if (direction == mLastDirection && mIsLastSelectionStringNull) {
                        mLastDirection = direction;
                        mIsLastSelectionStringNull = false;
                        return false;
                    }
                    mLastDirection = direction;
                    axis =  binding.getSecondArgument(i);
                    sendEvent = (binding.getThirdArgument(i) == 1);
                    traverseGivenAxis(direction, axis, sendEvent, contentDescription);
                    mLastDownEventHandled = true;
                    break;
                case ACTION_PERFORM_AXIS_TRANSITION:
                    int fromAxis = binding.getFirstArgument(i);
                    int toAxis = binding.getSecondArgument(i);
                    sendEvent = (binding.getThirdArgument(i) == 1);
                    prefromAxisTransition(fromAxis, toAxis, sendEvent, contentDescription);
                    mLastDownEventHandled = true;
                    break;
                default:
                    Log.w(LOG_TAG, "Unknown action code: " + actionCode);
            }
        }

        return mLastDownEventHandled;
    }

    /**
     * Set the current navigation axis which will be used while
     * calling {@link #traverseCurrentAxis(int, boolean, String)}.
     *
     * @param axis The axis to set.
     * @param sendEvent Whether to send an accessibility event to
     *        announce the change.
     */
    private void setCurrentAxis(int axis, boolean sendEvent, String contentDescription) {
        mCurrentAxis = axis;
        if (sendEvent) {
            AccessibilityEvent event = getPartialyPopulatedAccessibilityEvent();
            event.getText().add(String.valueOf(axis));
            event.setContentDescription(contentDescription);
            sendAccessibilityEvent(event);
        }
    }

    /**
     * Performs conditional transition one axis to another.
     *
     * @param fromAxis The axis which must be the current for the transition to occur.
     * @param toAxis The axis to which to transition.
     * @param sendEvent Flag if to send an event to announce successful transition.
     * @param contentDescription A description of the performed action.
     */
    private void prefromAxisTransition(int fromAxis, int toAxis, boolean sendEvent,
            String contentDescription) {
        if (mCurrentAxis == fromAxis) {
            setCurrentAxis(toAxis, sendEvent, contentDescription);
        }
    }

    /**
     * Traverse the document along the current navigation axis.
     *
     * @param direction The direction of traversal.
     * @param sendEvent Whether to send an accessibility event to
     *        announce the change.
     * @param contentDescription A description of the performed action.
     * @see #setCurrentAxis(int, boolean, String)
     */
    private boolean traverseCurrentAxis(int direction, boolean sendEvent,
            String contentDescription) {
        return traverseGivenAxis(direction, mCurrentAxis, sendEvent, contentDescription);
    }

    /**
     * Traverse the document along the given navigation axis.
     *
     * @param direction The direction of traversal.
     * @param axis The axis along which to traverse.
     * @param sendEvent Whether to send an accessibility event to
     *        announce the change.
     * @param contentDescription A description of the performed action.
     */
    private boolean traverseGivenAxis(int direction, int axis, boolean sendEvent,
            String contentDescription) {
        // if the axis is the default let WebView handle the event
        if (axis == NAVIGATION_AXIS_DEFAULT_WEB_VIEW_BEHAVIOR) {
            return false;
        }
        WebViewCore webViewCore = mWebView.getWebViewCore();
        if (webViewCore != null) {
            AccessibilityEvent event = null;
            if (sendEvent) {
                event = getPartialyPopulatedAccessibilityEvent();
                // the text will be set upon receiving the selection string
                event.setContentDescription(contentDescription);
            }
            mScheduledEventStack.push(event);
            webViewCore.sendMessage(EventHub.MODIFY_SELECTION, direction, axis);
        }
        return true;
    }

    /**
     * Called when the <code>selectionString</code> has changed.
     */
    public void onSelectionStringChange(String selectionString) {
        mIsLastSelectionStringNull = (selectionString == null);
        AccessibilityEvent event = mScheduledEventStack.pop();
        if (event != null) {
            event.getText().add(selectionString);
            sendAccessibilityEvent(event);
        }
    }

    /**
     * Sends an {@link AccessibilityEvent}.
     *
     * @param event The event to send.
     */
    private void sendAccessibilityEvent(AccessibilityEvent event) {
        if (DEBUG) {
            Log.d(LOG_TAG, "Dispatching: " + event);
        }
        AccessibilityManager.getInstance(mWebView.getContext()).sendAccessibilityEvent(event);
    }

    /**
     * @return An accessibility event whose members are populated except its
     *         text and content description.
     */
    private AccessibilityEvent getPartialyPopulatedAccessibilityEvent() {
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SELECTED);
        event.setClassName(mWebView.getClass().getName());
        event.setPackageName(mWebView.getContext().getPackageName());
        event.setEnabled(mWebView.isEnabled());
        return event;
    }

    /**
     * Ensures that the Web content key bindings are loaded.
     */
    private void ensureWebContentKeyBindings() {
        if (sBindings.size() > 0) {
            return;
        }

        String webContentKeyBindingsString  = Settings.Secure.getString(
                mWebView.getContext().getContentResolver(),
                Settings.Secure.ACCESSIBILITY_WEB_CONTENT_KEY_BINDINGS);

        SimpleStringSplitter semiColonSplitter = new SimpleStringSplitter(';');
        semiColonSplitter.setString(webContentKeyBindingsString);

        ArrayList<AccessibilityWebContentKeyBinding> bindings =
            new ArrayList<AccessibilityWebContentKeyBinding>();

        while (semiColonSplitter.hasNext()) {
            String bindingString = semiColonSplitter.next();
            if (TextUtils.isEmpty(bindingString)) {
                Log.e(LOG_TAG, "Malformed Web content key binding: "
                        + webContentKeyBindingsString);
                continue;
            }
            String[] keyValueArray = bindingString.split("=");
            if (keyValueArray.length != 2) {
                Log.e(LOG_TAG, "Disregarding malformed Web content key binding: " +
                        bindingString);
                continue;
            }
            try {
                SimpleStringSplitter colonSplitter = new SimpleStringSplitter(':');//remove
                int key = Integer.decode(keyValueArray[0].trim());
                String[] actionStrings = keyValueArray[1].split(":");
                int[] actions = new int[actionStrings.length];
                for (int i = 0, count = actions.length; i < count; i++) {
                    actions[i] = Integer.decode(actionStrings[i].trim());
                }

                bindings.add(new AccessibilityWebContentKeyBinding(key, actions));
            } catch (NumberFormatException nfe) {
                Log.e(LOG_TAG, "Disregarding malformed key binding: " + bindingString);
            }
        }

        for (AccessibilityWebContentKeyBinding binding : bindings) {
            sBindings.put(binding.getKey(), binding);
        }
    }

    /**
     * Represents a web content key-binding.
     */
    private class AccessibilityWebContentKeyBinding {

        private static final int OFFSET_META_STATE = 0x00000010;

        private static final int MASK_META_STATE = 0xFFFF0000;

        private static final int OFFSET_KEY_CODE = 0x00000000;

        private static final int MASK_KEY_CODE = 0x0000FFFF;

        private static final int OFFSET_ACTION = 0x00000018;

        private static final int MASK_ACTION = 0xFF000000;

        private static final int OFFSET_FIRST_ARGUMENT = 0x00000010;

        private static final int MASK_FIRST_ARGUMENT = 0x00FF0000;

        private static final int OFFSET_SECOND_ARGUMENT = 0x00000008;

        private static final int MASK_SECOND_ARGUMENT = 0x0000FF00;

        private static final int OFFSET_THIRD_ARGUMENT = 0x00000000;

        private static final int MASK_THIRD_ARGUMENT = 0x000000FF;

        private int mKey;

        private int [] mActionSequence;

        /**
         * @return The binding key with key code and meta state.
         *
         * @see #MASK_KEY_CODE
         * @see #MASK_META_STATE
         * @see #OFFSET_KEY_CODE
         * @see #OFFSET_META_STATE
         */
        public int getKey() {
            return mKey;
        }

        /**
         * @return The key code of the binding key.
         */
        public int getKeyCode() {
            return (mKey & MASK_KEY_CODE) >> OFFSET_KEY_CODE;
        }

        /**
         * @return The meta state of the binding key.
         */
        public int getMetaState() {
            return (mKey & MASK_META_STATE) >> OFFSET_META_STATE;
        }

        /**
         * @return The number of actions in the key binding.
         */
        public int getActionCount() {
            return mActionSequence.length;
        }

        /**
         * @param index The action for a given action <code>index</code>.
         */
        public int getAction(int index) {
            return mActionSequence[index];
        }

        /**
         * @param index The action code for a given action <code>index</code>.
         */
        public int getActionCode(int index) {
            return (mActionSequence[index] & MASK_ACTION) >> OFFSET_ACTION;
        }

        /**
         * @param index The first argument for a given action <code>index</code>.
         */
        public int getFirstArgument(int index) {
            return (mActionSequence[index] & MASK_FIRST_ARGUMENT) >> OFFSET_FIRST_ARGUMENT;
        }

        /**
         * @param index The second argument for a given action <code>index</code>.
         */
        public int getSecondArgument(int index) {
            return (mActionSequence[index] & MASK_SECOND_ARGUMENT) >> OFFSET_SECOND_ARGUMENT;
        }

        /**
         * @param index The third argument for a given action <code>index</code>.
         */
        public int getThirdArgument(int index) {
            return (mActionSequence[index] & MASK_THIRD_ARGUMENT) >> OFFSET_THIRD_ARGUMENT;
        }

        /**
         * Creates a new instance.
         * @param key The key for the binding (key and meta state)
         * @param actionSequence The sequence of action for the binding.
         * @see #getKey()
         */
        public AccessibilityWebContentKeyBinding(int key, int[] actionSequence) {
            mKey = key;
            mActionSequence = actionSequence;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("key: ");
            builder.append(getKey());
            builder.append(", metaState: ");
            builder.append(getMetaState());
            builder.append(", keyCode: ");
            builder.append(getKeyCode());
            builder.append(", actions[");
            for (int i = 0, count = getActionCount(); i < count; i++) {
                builder.append("{actionCode");
                builder.append(i);
                builder.append(": ");
                builder.append(getActionCode(i));
                builder.append(", firstArgument: ");
                builder.append(getFirstArgument(i));
                builder.append(", secondArgument: ");
                builder.append(getSecondArgument(i));
                builder.append(", thirdArgument: ");
                builder.append(getThirdArgument(i));
                builder.append("}");
            }
            builder.append("]");
            return builder.toString();
        }
    }
}
