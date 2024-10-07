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

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseLongArray;
import android.view.KeyEvent;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ToBooleanFunction;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Handles a mapping of two keys combination.
 */
public class KeyCombinationManager {
    private static final String TAG = "KeyCombinationManager";

    // Store the received down time of keycode.
    @GuardedBy("mLock")
    private final SparseLongArray mDownTimes = new SparseLongArray(2);
    private final ArrayList<TwoKeysCombinationRule> mRules = new ArrayList();

    // Selected rules according to current key down.
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final ArrayList<TwoKeysCombinationRule> mActiveRules = new ArrayList();
    // The rule has been triggered by current keys.
    @GuardedBy("mLock")
    private TwoKeysCombinationRule mTriggeredRule;
    private final Handler mHandler;

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
    public abstract static class TwoKeysCombinationRule {
        private int mKeyCode1;
        private int mKeyCode2;

        public TwoKeysCombinationRule(int keyCode1, int keyCode2) {
            mKeyCode1 = keyCode1;
            mKeyCode2 = keyCode2;
        }

        public boolean preCondition() {
            return true;
        }

        boolean shouldInterceptKey(int keyCode) {
            return (keyCode == mKeyCode1 || keyCode == mKeyCode2) && preCondition();
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

        // The excessive delay before it dispatching to client.
        public long getKeyInterceptDelayMs() {
            return COMBINE_KEY_DELAY_MILLIS;
        }

        public abstract void execute();
        public abstract void cancel();

        @Override
        public String toString() {
            return KeyEvent.keyCodeToString(mKeyCode1) + " + "
                    + KeyEvent.keyCodeToString(mKeyCode2);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o instanceof TwoKeysCombinationRule) {
                TwoKeysCombinationRule that = (TwoKeysCombinationRule) o;
                return (mKeyCode1 == that.mKeyCode1 && mKeyCode2 == that.mKeyCode2) || (
                        mKeyCode1 == that.mKeyCode2 && mKeyCode2 == that.mKeyCode1);
            }
            return false;
        }

        @Override
        public int hashCode() {
            int result = mKeyCode1;
            result = 31 * result + mKeyCode2;
            return result;
        }
    }

    public KeyCombinationManager(Handler handler) {
        mHandler = handler;
    }

    public void addRule(TwoKeysCombinationRule rule) {
        if (mRules.contains(rule)) {
            throw new IllegalArgumentException("Rule : " + rule + " already exists.");
        }
        mRules.add(rule);
    }

    public void removeRule(TwoKeysCombinationRule rule) {
        mRules.remove(rule);
    }

    /**
     * Check if the key event could be intercepted by combination key rule before it is dispatched
     * to a window.
     * Return true if any active rule could be triggered by the key event, otherwise false.
     */
    public boolean interceptKey(KeyEvent event, boolean interactive) {
        synchronized (mLock) {
            return interceptKeyLocked(event, interactive);
        }
    }

    private boolean interceptKeyLocked(KeyEvent event, boolean interactive) {
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
                    return false;
                } else if (count == 0) { // has some key down but no active rule exist.
                    return false;
                }
            }

            if (mDownTimes.get(keyCode) == 0) {
                mDownTimes.put(keyCode, eventTime);
            } else {
                // ignore old key, maybe a repeat key.
                return false;
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
                    return true;
                }

                // check if second key can trigger rule, or remove the non-match rule.
                forAllActiveRules((rule) -> {
                    if (!rule.shouldInterceptKeys(mDownTimes)) {
                        return false;
                    }
                    Log.v(TAG, "Performing combination rule : " + rule);
                    mHandler.post(rule::execute);
                    mTriggeredRule = rule;
                    return true;
                });
                mActiveRules.clear();
                if (mTriggeredRule != null) {
                    mActiveRules.add(mTriggeredRule);
                    return true;
                }
            }
        } else {
            mDownTimes.delete(keyCode);
            for (int index = count - 1; index >= 0; index--) {
                final TwoKeysCombinationRule rule = mActiveRules.get(index);
                if (rule.shouldInterceptKey(keyCode)) {
                    mHandler.post(rule::cancel);
                    mActiveRules.remove(index);
                }
            }
        }
        return false;
    }

    /**
     * Return the interceptTimeout to tell InputDispatcher when is ready to deliver to window.
     */
    public long getKeyInterceptTimeout(int keyCode) {
        synchronized (mLock) {
            if (mDownTimes.get(keyCode) == 0) {
                return 0;
            }
            long delayMs = 0;
            for (final TwoKeysCombinationRule rule : mActiveRules) {
                if (rule.shouldInterceptKey(keyCode)) {
                    delayMs = Math.max(delayMs, rule.getKeyInterceptDelayMs());
                }
            }
            // Make sure the delay is less than COMBINE_KEY_DELAY_MILLIS.
            delayMs = Math.min(delayMs, COMBINE_KEY_DELAY_MILLIS);
            return mDownTimes.get(keyCode) + delayMs;
        }
    }

    /**
     * True if the key event had been handled.
     */
    public boolean isKeyConsumed(KeyEvent event) {
        synchronized (mLock) {
            if ((event.getFlags() & KeyEvent.FLAG_FALLBACK) != 0) {
                return false;
            }
            return mTriggeredRule != null && mTriggeredRule.shouldInterceptKey(event.getKeyCode());
        }
    }

    /**
     * True if power key is the candidate.
     */
    public boolean isPowerKeyIntercepted() {
        synchronized (mLock) {
            if (forAllActiveRules((rule) -> rule.shouldInterceptKey(KEYCODE_POWER))) {
                // return false if only if power key pressed.
                return mDownTimes.size() > 1 || mDownTimes.get(KEYCODE_POWER) == 0;
            }
            return false;
        }
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

    public void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "KeyCombination rules:");
        forAllRules(mRules, (rule)-> {
            pw.println(prefix + "  " + rule);
        });
    }
}
