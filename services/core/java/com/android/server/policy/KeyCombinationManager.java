/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.server.policy;

import static android.view.KeyEvent.KEYCODE_POWER;

import android.os.SystemClock;
import android.util.SparseLongArray;
import android.view.KeyEvent;

import com.android.internal.util.ToBooleanFunction;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Handles a mapping of two keys combination.
 */
public class KeyCombinationManager {
    private static final String TAG = "KeyCombinationManager";

    // Store the received down time of keycode.
    private final SparseLongArray mDownTimes = new SparseLongArray(2);
    private final ArrayList<TwoKeysCombinationRule> mRules = new ArrayList();

    // Selected rules according to current key down.
    private final ArrayList<TwoKeysCombinationRule> mActiveRules = new ArrayList();
    // The rule has been triggered by current keys.
    private TwoKeysCombinationRule mTriggeredRule;

    // Keys in a key combination must be pressed within this interval of each other.
    private static final long COMBINE_KEY_DELAY_MILLIS = 150;

    /**
     *  Rule definition for two keys combination.
     *  E.g : define volume_down + power key.
     *  <pre class="prettyprint">
     *  TwoKeysCombinationRule rule =
     *      new TwoKeysCombinationRule(KEYCODE_VOLUME_DOWN, KEYCODE_POWER) {
     *           boolean preCondition() { // check if it needs to intercept key }
     *           void execute() { // trigger action }
     *           void cancel() { // cancel action }
     *       };
     *  </pre>
     */
    abstract static class TwoKeysCombinationRule {
        private int mKeyCode1;
        private int mKeyCode2;

        TwoKeysCombinationRule(int keyCode1, int keyCode2) {
            mKeyCode1 = keyCode1;
            mKeyCode2 = keyCode2;
        }

        boolean preCondition() {
            return true;
        }

        boolean shouldInterceptKey(int keyCode) {
            return preCondition() && (keyCode == mKeyCode1 || keyCode == mKeyCode2);
        }

        boolean shouldInterceptKeys(SparseLongArray downTimes) {
            final long now = SystemClock.uptimeMillis();
            if (downTimes.get(mKeyCode1) > 0
                    && downTimes.get(mKeyCode2) > 0
                    && now <= downTimes.get(mKeyCode1) + COMBINE_KEY_DELAY_MILLIS
                    && now <= downTimes.get(mKeyCode2) + COMBINE_KEY_DELAY_MILLIS) {
                return true;
            }
            return false;
        }

        abstract void execute();
        abstract void cancel();

        @Override
        public String toString() {
            return "KeyCode1 = " + KeyEvent.keyCodeToString(mKeyCode1)
                    + ", KeyCode2 = " +  KeyEvent.keyCodeToString(mKeyCode2);
        }
    }

    public KeyCombinationManager() {
    }

    void addRule(TwoKeysCombinationRule rule) {
        mRules.add(rule);
    }

    /**
     * Check if the key event could be triggered by combine key rule before dispatching to a window.
     */
    void interceptKey(KeyEvent event, boolean interactive) {
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        final int keyCode = event.getKeyCode();
        final int count = mActiveRules.size();
        final long eventTime = event.getEventTime();

        if (interactive && down) {
            if (mDownTimes.size() > 0) {
                if (count > 0
                        && eventTime > mDownTimes.valueAt(0) + COMBINE_KEY_DELAY_MILLIS) {
                    // exceed time from first key down.
                    forAllRules(mActiveRules, (rule)-> rule.cancel());
                    mActiveRules.clear();
                    return;
                } else if (count == 0) { // has some key down but no active rule exist.
                    return;
                }
            }

            if (mDownTimes.get(keyCode) == 0) {
                mDownTimes.put(keyCode, eventTime);
            } else {
                // ignore old key, maybe a repeat key.
                return;
            }

            if (mDownTimes.size() == 1) {
                mTriggeredRule = null;
                // check first key and pick active rules.
                forAllRules(mRules, (rule)-> {
                    if (rule.shouldInterceptKey(keyCode)) {
                        mActiveRules.add(rule);
                    }
                });
            } else {
                // Ignore if rule already triggered.
                if (mTriggeredRule != null) {
                    return;
                }

                // check if second key can trigger rule, or remove the non-match rule.
                forAllActiveRules((rule) -> {
                    if (!rule.shouldInterceptKeys(mDownTimes)) {
                        return false;
                    }
                    rule.execute();
                    mTriggeredRule = rule;
                    return true;
                });
                mActiveRules.clear();
                if (mTriggeredRule != null) {
                    mActiveRules.add(mTriggeredRule);
                }
            }
        } else {
            mDownTimes.delete(keyCode);
            for (int index = count - 1; index >= 0; index--) {
                final TwoKeysCombinationRule rule = mActiveRules.get(index);
                if (rule.shouldInterceptKey(keyCode)) {
                    rule.cancel();
                    mActiveRules.remove(index);
                }
            }
        }
    }

    /**
     * Return the interceptTimeout to tell InputDispatcher when is ready to deliver to window.
     */
    long getKeyInterceptTimeout(int keyCode) {
        if (forAllActiveRules((rule) -> rule.shouldInterceptKey(keyCode))) {
            return mDownTimes.get(keyCode) + COMBINE_KEY_DELAY_MILLIS;
        }
        return 0;
    }

    /**
     * True if the key event had been handled.
     */
    boolean isKeyConsumed(KeyEvent event) {
        if ((event.getFlags() & KeyEvent.FLAG_FALLBACK) != 0) {
            return false;
        }
        return mTriggeredRule != null && mTriggeredRule.shouldInterceptKey(event.getKeyCode());
    }

    /**
     * True if power key is the candidate.
     */
    boolean isPowerKeyIntercepted() {
        if (forAllActiveRules((rule) -> rule.shouldInterceptKey(KEYCODE_POWER))) {
            // return false if only if power key pressed.
            return mDownTimes.size() > 1 || mDownTimes.get(KEYCODE_POWER) == 0;
        }
        return false;
    }

    /**
     * Traverse each item of rules.
     */
    private void forAllRules(
            ArrayList<TwoKeysCombinationRule> rules, Consumer<TwoKeysCombinationRule> callback) {
        final int count = rules.size();
        for (int index = 0; index < count; index++) {
            final TwoKeysCombinationRule rule = rules.get(index);
            callback.accept(rule);
        }
    }

    /**
     * Traverse each item of active rules until some rule can be applied, otherwise return false.
     */
    private boolean forAllActiveRules(ToBooleanFunction<TwoKeysCombinationRule> callback) {
        final int count = mActiveRules.size();
        for (int index = 0; index < count; index++) {
            final TwoKeysCombinationRule rule = mActiveRules.get(index);
            if (callback.apply(rule)) {
                return true;
            }
        }
        return false;
    }
}
