/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.keyguard;

import android.annotation.Nullable;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.text.TextUtils;

import androidx.annotation.IntDef;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.phone.KeyguardIndicationTextView;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Rotates through messages to show on the keyguard bottom area on the lock screen
 * NOTE: This controller should not be used on AoD to avoid waking up the AP too often.
 */
public class KeyguardIndicationRotateTextViewController extends
        ViewController<KeyguardIndicationTextView> implements Dumpable {
    public static String TAG = "KgIndicationRotatingCtrl";
    private static final long DEFAULT_INDICATION_SHOW_LENGTH = 3500; // milliseconds

    private final StatusBarStateController mStatusBarStateController;
    private final float mMaxAlpha;
    private final ColorStateList mInitialTextColorState;

    // Stores @IndicationType => KeyguardIndication messages
    private final Map<Integer, KeyguardIndication> mIndicationMessages = new HashMap<>();

    // Executor that will show the next message after a delay
    private final DelayableExecutor mExecutor;
    @Nullable private ShowNextIndication mShowNextIndicationRunnable;

    // List of indication types to show. The next indication to show is always at index 0
    private final List<Integer> mIndicationQueue = new LinkedList<>();
    private @IndicationType int mCurrIndicationType = INDICATION_TYPE_NONE;

    private boolean mIsDozing;

    public KeyguardIndicationRotateTextViewController(
            KeyguardIndicationTextView view,
            @Main DelayableExecutor executor,
            StatusBarStateController statusBarStateController
    ) {
        super(view);
        mMaxAlpha = view.getAlpha();
        mExecutor = executor;
        mInitialTextColorState = mView != null
                ? mView.getTextColors() : ColorStateList.valueOf(Color.WHITE);
        mStatusBarStateController = statusBarStateController;
        init();
    }

    @Override
    protected void onViewAttached() {
        mStatusBarStateController.addCallback(mStatusBarStateListener);
    }

    @Override
    protected void onViewDetached() {
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
        cancelScheduledIndication();
    }

    /**
     * Update the indication type with the given String.
     * @param type of indication
     * @param newIndication message to associate with this indication type
     * @param showImmediately if true: shows this indication message immediately. Else, the text
     *                        associated with this type is updated and will show when its turn in
     *                        the IndicationQueue comes around.
     */
    public void updateIndication(@IndicationType int type, KeyguardIndication newIndication,
            boolean updateImmediately) {
        if (type == INDICATION_TYPE_NOW_PLAYING
                || type == INDICATION_TYPE_REVERSE_CHARGING) {
            // temporarily don't show here, instead use AmbientContainer b/181049781
            return;
        }
        final boolean hasPreviousIndication = mIndicationMessages.get(type) != null;
        final boolean hasNewIndication = newIndication != null;
        if (!hasNewIndication) {
            mIndicationMessages.remove(type);
            mIndicationQueue.removeIf(x -> x == type);
        } else {
            if (!hasPreviousIndication) {
                mIndicationQueue.add(type);
            }

            mIndicationMessages.put(type, newIndication);
        }

        if (mIsDozing) {
            return;
        }

        final boolean showNow = updateImmediately
                || mCurrIndicationType == INDICATION_TYPE_NONE
                || mCurrIndicationType == type;
        if (hasNewIndication) {
            if (showNow) {
                showIndication(type);
            } else if (!isNextIndicationScheduled()) {
                scheduleShowNextIndication();
            }
            return;
        }

        if (mCurrIndicationType == type
                && !hasNewIndication
                && updateImmediately) {
            if (mShowNextIndicationRunnable != null) {
                mShowNextIndicationRunnable.runImmediately();
            } else {
                showIndication(INDICATION_TYPE_NONE);
            }
        }
    }

    /**
     * Stop showing the following indication type.
     *
     * If the current indication is of this type, immediately stops showing the message.
     */
    public void hideIndication(@IndicationType int type) {
        if (!mIndicationMessages.containsKey(type)
                || TextUtils.isEmpty(mIndicationMessages.get(type).getMessage())) {
            return;
        }
        updateIndication(type, null, true);
    }

    /**
     * Show a transient message.
     * Transient messages:
     * - show immediately
     * - will continue to be in the rotation of messages shown until hideTransient is called.
     */
    public void showTransient(CharSequence newIndication) {
        final long inAnimationDuration = 600L; // see KeyguardIndicationTextView.getYInDuration
        updateIndication(INDICATION_TYPE_TRANSIENT,
                new KeyguardIndication.Builder()
                        .setMessage(newIndication)
                        .setMinVisibilityMillis(2000L + inAnimationDuration)
                        .setTextColor(mInitialTextColorState)
                        .build(),
                /* showImmediately */true);
    }

    /**
     * Hide a transient message immediately.
     */
    public void hideTransient() {
        hideIndication(INDICATION_TYPE_TRANSIENT);
    }

    /**
     * @return true if there are available indications to show
     */
    public boolean hasIndications() {
        return mIndicationMessages.keySet().size() > 0;
    }

    /**
     * Immediately show the passed indication type and schedule the next indication to show.
     * Will re-add this indication to be re-shown after all other indications have been
     * rotated through.
     */
    private void showIndication(@IndicationType int type) {
        cancelScheduledIndication();

        mCurrIndicationType = type;
        mIndicationQueue.removeIf(x -> x == type);
        if (mCurrIndicationType != INDICATION_TYPE_NONE) {
            mIndicationQueue.add(type); // re-add to show later
        }

        mView.switchIndication(mIndicationMessages.get(type));

        // only schedule next indication if there's more than just this indication in the queue
        if (mCurrIndicationType != INDICATION_TYPE_NONE && mIndicationQueue.size() > 1) {
            scheduleShowNextIndication();
        }
    }

    protected boolean isNextIndicationScheduled() {
        return mShowNextIndicationRunnable != null;
    }

    private void scheduleShowNextIndication() {
        cancelScheduledIndication();
        mShowNextIndicationRunnable = new ShowNextIndication(DEFAULT_INDICATION_SHOW_LENGTH);
    }

    private void cancelScheduledIndication() {
        if (mShowNextIndicationRunnable != null) {
            mShowNextIndicationRunnable.cancelDelayedExecution();
            mShowNextIndicationRunnable = null;
        }
    }

    private StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onDozeAmountChanged(float linear, float eased) {
                    mView.setAlpha((1 - linear) * mMaxAlpha);
                }

                @Override
                public void onDozingChanged(boolean isDozing) {
                    if (isDozing == mIsDozing) return;
                    mIsDozing = isDozing;
                    if (mIsDozing) {
                        showIndication(INDICATION_TYPE_NONE);
                    } else if (mIndicationQueue.size() > 0) {
                        showIndication(mIndicationQueue.remove(0));
                    }
                }
            };

    /**
     * Shows the next indication in the IndicationQueue after an optional delay.
     * This wrapper has the ability to cancel itself (remove runnable from DelayableExecutor) or
     * immediately run itself (which also removes itself from the DelayableExecutor).
     */
    class ShowNextIndication {
        private final Runnable mShowIndicationRunnable;
        private Runnable mCancelDelayedRunnable;

        ShowNextIndication(long delay) {
            mShowIndicationRunnable = () -> {
                int type = mIndicationQueue.size() == 0
                        ? INDICATION_TYPE_NONE : mIndicationQueue.remove(0);
                showIndication(type);
            };
            mCancelDelayedRunnable = mExecutor.executeDelayed(mShowIndicationRunnable, delay);
        }

        public void runImmediately() {
            cancelDelayedExecution();
            mShowIndicationRunnable.run();
        }

        public void cancelDelayedExecution() {
            if (mCancelDelayedRunnable != null) {
                mCancelDelayedRunnable.run();
                mCancelDelayedRunnable = null;
            }
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("KeyguardIndicationRotatingTextViewController:");
        pw.println("    currentMessage=" + mView.getText());
        pw.println("    dozing:" + mIsDozing);
        pw.println("    queue:" + mIndicationQueue.toString());
        pw.println("    showNextIndicationRunnable:" + mShowNextIndicationRunnable);

        if (hasIndications()) {
            pw.println("    All messages:");
            for (int type : mIndicationMessages.keySet()) {
                pw.println("        type=" + type + " " + mIndicationMessages.get(type));
            }
        }
    }

    static final int INDICATION_TYPE_NONE = -1;
    public static final int INDICATION_TYPE_OWNER_INFO = 0;
    public static final int INDICATION_TYPE_DISCLOSURE = 1;
    public static final int INDICATION_TYPE_LOGOUT = 2;
    public static final int INDICATION_TYPE_BATTERY = 3;
    public static final int INDICATION_TYPE_ALIGNMENT = 4;
    public static final int INDICATION_TYPE_TRANSIENT = 5;
    public static final int INDICATION_TYPE_TRUST = 6;
    public static final int INDICATION_TYPE_RESTING = 7;
    public static final int INDICATION_TYPE_USER_LOCKED = 8;
    public static final int INDICATION_TYPE_NOW_PLAYING = 9;
    public static final int INDICATION_TYPE_REVERSE_CHARGING = 10;

    @IntDef({
            INDICATION_TYPE_NONE,
            INDICATION_TYPE_DISCLOSURE,
            INDICATION_TYPE_OWNER_INFO,
            INDICATION_TYPE_LOGOUT,
            INDICATION_TYPE_BATTERY,
            INDICATION_TYPE_ALIGNMENT,
            INDICATION_TYPE_TRANSIENT,
            INDICATION_TYPE_TRUST,
            INDICATION_TYPE_RESTING,
            INDICATION_TYPE_USER_LOCKED,
            INDICATION_TYPE_NOW_PLAYING,
            INDICATION_TYPE_REVERSE_CHARGING,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface IndicationType{}
}
