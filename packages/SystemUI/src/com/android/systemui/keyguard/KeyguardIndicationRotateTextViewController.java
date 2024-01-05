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

import static com.android.systemui.flags.Flags.KEYGUARD_TALKBACK_FIX;

import android.annotation.Nullable;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.IntDef;
import androidx.annotation.VisibleForTesting;

import com.android.keyguard.logging.KeyguardLogger;
import com.android.systemui.Dumpable;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.phone.KeyguardIndicationTextView;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Animates through messages to show on the keyguard bottom area on the lock screen.
 * Utilizes a {@link KeyguardIndicationTextView} for animations. This class handles the rotating
 * nature of the messages including:
 *  - ensuring a message is shown for its minimum amount of time. Minimum time is determined by
 *  {@link KeyguardIndication#getMinVisibilityMillis()}
 *  - showing the next message after a default of 3.5 seconds before animating to the next
 *  - statically showing a single message if there is only one message to show
 *  - showing certain messages immediately, assuming te current message has been shown for
 *  at least {@link KeyguardIndication#getMinVisibilityMillis()}. For example, transient and
 *  biometric messages are meant to be shown immediately.
 *  - ending animations when dozing begins, and resuming when dozing ends. Rotating messages on
 *  AoD is undesirable since it wakes up the AP too often.
 */
public class KeyguardIndicationRotateTextViewController extends
        ViewController<KeyguardIndicationTextView> implements Dumpable {
    public static String TAG = "KgIndicationRotatingCtrl";
    private static final long DEFAULT_INDICATION_SHOW_LENGTH =
            KeyguardIndicationController.DEFAULT_HIDE_DELAY_MS
                    - KeyguardIndicationTextView.Y_IN_DURATION;
    public static final long IMPORTANT_MSG_MIN_DURATION =
            2000L + KeyguardIndicationTextView.Y_IN_DURATION;

    private final StatusBarStateController mStatusBarStateController;
    private final KeyguardLogger mLogger;
    private final float mMaxAlpha;
    private final ColorStateList mInitialTextColorState;

    // Stores @IndicationType => KeyguardIndication messages
    private final Map<Integer, KeyguardIndication> mIndicationMessages = new HashMap<>();

    // Executor that will show the next message after a delay
    private final DelayableExecutor mExecutor;
    private final FeatureFlags mFeatureFlags;

    @VisibleForTesting
    @Nullable ShowNextIndication mShowNextIndicationRunnable;

    // List of indication types to show. The next indication to show is always at index 0
    private final List<Integer> mIndicationQueue = new ArrayList<>();
    private @IndicationType int mCurrIndicationType = INDICATION_TYPE_NONE;
    private CharSequence mCurrMessage;
    private long mLastIndicationSwitch;

    private boolean mIsDozing;

    public KeyguardIndicationRotateTextViewController(
            KeyguardIndicationTextView view,
            @Main DelayableExecutor executor,
            StatusBarStateController statusBarStateController,
            KeyguardLogger logger,
            FeatureFlags flags
    ) {
        super(view);
        mMaxAlpha = view.getAlpha();
        mExecutor = executor;
        mInitialTextColorState = mView != null
                ? mView.getTextColors() : ColorStateList.valueOf(Color.WHITE);
        mStatusBarStateController = statusBarStateController;
        mLogger = logger;
        mFeatureFlags = flags;
        init();
    }

    @Override
    protected void onViewAttached() {
        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mView.setAlwaysAnnounceEnabled(mFeatureFlags.isEnabled(KEYGUARD_TALKBACK_FIX));
    }

    @Override
    protected void onViewDetached() {
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
        cancelScheduledIndication();
    }

    /** Destroy ViewController, removing any listeners. */
    public void destroy() {
        super.destroy();
        onViewDetached();
    }

    /**
     * Update the indication type with the given String.
     * @param type of indication
     * @param newIndication message to associate with this indication type
     * @param showAsap if true: shows this indication message as soon as possible. If false,
     *                   the text associated with this type is updated and will show when its turn
     *                   in the IndicationQueue comes around.
     */
    public void updateIndication(@IndicationType int type, KeyguardIndication newIndication,
            boolean showAsap) {
        if (type == INDICATION_TYPE_REVERSE_CHARGING) {
            // temporarily don't show here, instead use AmbientContainer b/181049781
            return;
        }
        long minShowDuration = getMinVisibilityMillis(mIndicationMessages.get(mCurrIndicationType));
        final boolean hasNewIndication = newIndication != null
                && !TextUtils.isEmpty(newIndication.getMessage());
        if (!hasNewIndication) {
            mIndicationMessages.remove(type);
            mIndicationQueue.removeIf(x -> x == type);
        } else {
            if (!mIndicationQueue.contains(type)) {
                mIndicationQueue.add(type);
            }

            mIndicationMessages.put(type, newIndication);
        }

        if (mIsDozing) {
            return;
        }

        long currTime = SystemClock.uptimeMillis();
        long timeSinceLastIndicationSwitch = currTime - mLastIndicationSwitch;
        boolean currMsgShownForMinTime = timeSinceLastIndicationSwitch >= minShowDuration;
        if (hasNewIndication) {
            if (mCurrIndicationType == INDICATION_TYPE_NONE || mCurrIndicationType == type) {
                showIndication(type);
            } else if (showAsap) {
                if (currMsgShownForMinTime) {
                    showIndication(type);
                } else {
                    mIndicationQueue.removeIf(x -> x == type);
                    mIndicationQueue.add(0 /* index */, type /* type */);
                    scheduleShowNextIndication(minShowDuration - timeSinceLastIndicationSwitch);
                }
            } else if (!isNextIndicationScheduled()) {
                long nextShowTime = Math.max(
                        getMinVisibilityMillis(mIndicationMessages.get(type)),
                        DEFAULT_INDICATION_SHOW_LENGTH);
                if (timeSinceLastIndicationSwitch >= nextShowTime) {
                    showIndication(type);
                } else {
                    scheduleShowNextIndication(
                            nextShowTime - timeSinceLastIndicationSwitch);
                }
            }
            return;
        }

        // current indication is updated to empty
        if (mCurrIndicationType == type
                && !hasNewIndication
                && showAsap) {
            if (currMsgShownForMinTime) {
                if (mShowNextIndicationRunnable != null) {
                    mShowNextIndicationRunnable.runImmediately();
                } else {
                    showIndication(INDICATION_TYPE_NONE);
                }
            } else {
                scheduleShowNextIndication(minShowDuration - timeSinceLastIndicationSwitch);
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
        updateIndication(INDICATION_TYPE_TRANSIENT,
                new KeyguardIndication.Builder()
                        .setMessage(newIndication)
                        .setMinVisibilityMillis(IMPORTANT_MSG_MIN_DURATION)
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
     * Clears all messages in the queue and sets the current message to an empty string.
     */
    public void clearMessages() {
        mCurrIndicationType = INDICATION_TYPE_NONE;
        mIndicationQueue.clear();
        mIndicationMessages.clear();
        mView.clearMessages();
    }

    /**
     * Immediately show the passed indication type and schedule the next indication to show.
     * Will re-add this indication to be re-shown after all other indications have been
     * rotated through.
     */
    private void showIndication(@IndicationType int type) {
        cancelScheduledIndication();

        final CharSequence previousMessage = mCurrMessage;
        final @IndicationType int previousIndicationType = mCurrIndicationType;
        mCurrIndicationType = type;
        mCurrMessage = mIndicationMessages.get(type) != null
                ? mIndicationMessages.get(type).getMessage()
                : null;

        mIndicationQueue.removeIf(x -> x == type);
        if (mCurrIndicationType != INDICATION_TYPE_NONE) {
            mIndicationQueue.add(type); // re-add to show later
        }

        mLastIndicationSwitch = SystemClock.uptimeMillis();
        if (!TextUtils.equals(previousMessage, mCurrMessage)
                || previousIndicationType != mCurrIndicationType) {
            mLogger.logKeyguardSwitchIndication(type,
                    mCurrMessage != null ? mCurrMessage.toString() : null);
            mView.switchIndication(mIndicationMessages.get(type));
        }

        // only schedule next indication if there's more than just this indication in the queue
        if (mCurrIndicationType != INDICATION_TYPE_NONE && mIndicationQueue.size() > 1) {
            scheduleShowNextIndication(Math.max(
                    getMinVisibilityMillis(mIndicationMessages.get(type)),
                    DEFAULT_INDICATION_SHOW_LENGTH));
        }
    }

    private long getMinVisibilityMillis(KeyguardIndication indication) {
        if (indication == null) {
            return 0;
        }

        if (indication.getMinVisibilityMillis() == null) {
            return 0;
        }

        return indication.getMinVisibilityMillis();
    }

    protected boolean isNextIndicationScheduled() {
        return mShowNextIndicationRunnable != null;
    }


    private void scheduleShowNextIndication(long msUntilShowNextMsg) {
        cancelScheduledIndication();
        mShowNextIndicationRunnable = new ShowNextIndication(msUntilShowNextMsg);
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
                        showIndication(mIndicationQueue.get(0));
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
                        ? INDICATION_TYPE_NONE : mIndicationQueue.get(0);
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
    public void dump(PrintWriter pw, String[] args) {
        pw.println("KeyguardIndicationRotatingTextViewController:");
        pw.println("    currentTextViewMessage=" + mView.getText());
        pw.println("    currentStoredMessage=" + mView.getMessage());
        pw.println("    dozing:" + mIsDozing);
        pw.println("    queue:" + mIndicationQueue);
        pw.println("    showNextIndicationRunnable:" + mShowNextIndicationRunnable);

        if (hasIndications()) {
            pw.println("    All messages:");
            for (int type : mIndicationMessages.keySet()) {
                pw.println("        type=" + type + " " + mIndicationMessages.get(type));
            }
        }
    }

    // only used locally to stop showing any messages & stop the rotating messages
    static final int INDICATION_TYPE_NONE = -1;

    public static final int INDICATION_TYPE_OWNER_INFO = 0;
    public static final int INDICATION_TYPE_DISCLOSURE = 1;
    public static final int INDICATION_TYPE_LOGOUT = 2;
    public static final int INDICATION_TYPE_BATTERY = 3;
    public static final int INDICATION_TYPE_ALIGNMENT = 4;
    public static final int INDICATION_TYPE_TRANSIENT = 5;
    public static final int INDICATION_TYPE_TRUST = 6;
    public static final int INDICATION_TYPE_PERSISTENT_UNLOCK_MESSAGE = 7;
    public static final int INDICATION_TYPE_USER_LOCKED = 8;
    public static final int INDICATION_TYPE_REVERSE_CHARGING = 10;
    public static final int INDICATION_TYPE_BIOMETRIC_MESSAGE = 11;
    public static final int INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP = 12;
    public static final int INDICATION_IS_DISMISSIBLE = 13;

    @IntDef({
            INDICATION_TYPE_NONE,
            INDICATION_TYPE_DISCLOSURE,
            INDICATION_TYPE_OWNER_INFO,
            INDICATION_TYPE_LOGOUT,
            INDICATION_TYPE_BATTERY,
            INDICATION_TYPE_ALIGNMENT,
            INDICATION_TYPE_TRANSIENT,
            INDICATION_TYPE_TRUST,
            INDICATION_TYPE_PERSISTENT_UNLOCK_MESSAGE,
            INDICATION_TYPE_USER_LOCKED,
            INDICATION_TYPE_REVERSE_CHARGING,
            INDICATION_TYPE_BIOMETRIC_MESSAGE,
            INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP,
            INDICATION_IS_DISMISSIBLE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface IndicationType{}

    /**
     * Get human-readable string representation of the indication type.
     */
    public static String indicationTypeToString(@IndicationType int type) {
        switch (type) {
            case INDICATION_TYPE_NONE:
                return "none";
            case INDICATION_TYPE_DISCLOSURE:
                return "disclosure";
            case INDICATION_TYPE_OWNER_INFO:
                return "owner_info";
            case INDICATION_TYPE_LOGOUT:
                return "logout";
            case INDICATION_TYPE_BATTERY:
                return "battery";
            case INDICATION_TYPE_ALIGNMENT:
                return "alignment";
            case INDICATION_TYPE_TRANSIENT:
                return "transient";
            case INDICATION_TYPE_TRUST:
                return "trust";
            case INDICATION_TYPE_PERSISTENT_UNLOCK_MESSAGE:
                return "persistent_unlock_message";
            case INDICATION_TYPE_USER_LOCKED:
                return "user_locked";
            case INDICATION_TYPE_REVERSE_CHARGING:
                return "reverse_charging";
            case INDICATION_TYPE_BIOMETRIC_MESSAGE:
                return "biometric_message";
            case INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP:
                return "biometric_message_followup";
            default:
                return "unknown[" + type + "]";
        }
    }
}
