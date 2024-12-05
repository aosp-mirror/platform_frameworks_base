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

package com.android.systemui.clipboardoverlay;

import static android.content.ClipDescription.CLASSIFICATION_COMPLETE;

import static com.android.systemui.Flags.clipboardNoninteractiveOnLockscreen;
import static com.android.systemui.Flags.clipboardOverlayMultiuser;
import static com.android.systemui.Flags.overrideSuppressOverlayCondition;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_ENTERED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_UPDATED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_TOAST_SHOWN;

import static com.google.android.setupcompat.util.WizardManagerHelper.SETTINGS_SECURE_USER_SETUP_COMPLETE;

import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.user.utils.UserScopedService;

import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * ClipboardListener brings up a clipboard overlay when something is copied to the clipboard.
 */
@SysUISingleton
public class ClipboardListener implements
        CoreStartable, ClipboardManager.OnPrimaryClipChangedListener {
    private static final String TAG = "ClipboardListener";

    @VisibleForTesting
    static final String SHELL_PACKAGE = "com.android.shell";
    @VisibleForTesting
    static final String EXTRA_SUPPRESS_OVERLAY =
            "com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY";

    private final Context mContext;
    private final Provider<ClipboardOverlayController> mOverlayProvider;
    private final ClipboardToast mClipboardToast;
    private final UserScopedService<ClipboardManager> mClipboardManagerProvider;
    private final UserScopedService<KeyguardManager> mKeyguardManagerProvider;
    private final UiEventLogger mUiEventLogger;
    private final ClipboardOverlaySuppressionController mClipboardOverlaySuppressionController;
    private ClipboardOverlay mClipboardOverlay;
    private ClipboardManager mClipboardManagerForUser;
    private KeyguardManager mKeyguardManagerForUser;

    private final UserTracker mUserTracker;
    private final Executor mMainExecutor;

    private final UserTracker.Callback mCallback = new UserTracker.Callback() {
        @Override
        public void onUserChanged(int newUser, @NonNull Context userContext) {
            UserTracker.Callback.super.onUserChanged(newUser, userContext);
            mClipboardManagerForUser.removePrimaryClipChangedListener(ClipboardListener.this);
            setUser(mUserTracker.getUserHandle());
            mClipboardManagerForUser.addPrimaryClipChangedListener(ClipboardListener.this);
        }
    };

    @Inject
    public ClipboardListener(Context context,
            Provider<ClipboardOverlayController> clipboardOverlayControllerProvider,
            ClipboardToast clipboardToast,
            UserTracker userTracker,
            UserScopedService<ClipboardManager> clipboardManager,
            UserScopedService<KeyguardManager> keyguardManager,
            UiEventLogger uiEventLogger,
            @Main Executor mainExecutor,
            ClipboardOverlaySuppressionController clipboardOverlaySuppressionController) {
        mContext = context;
        mOverlayProvider = clipboardOverlayControllerProvider;
        mClipboardToast = clipboardToast;
        mClipboardManagerProvider = clipboardManager;
        mKeyguardManagerProvider = keyguardManager;
        mUiEventLogger = uiEventLogger;
        mClipboardOverlaySuppressionController = clipboardOverlaySuppressionController;

        mMainExecutor = mainExecutor;
        mUserTracker = userTracker;
        setUser(mUserTracker.getUserHandle());
    }

    private void setUser(UserHandle user) {
        mClipboardManagerForUser = mClipboardManagerProvider.forUser(user);
        mKeyguardManagerForUser = mKeyguardManagerProvider.forUser(user);
    }

    @Override
    public void start() {
        if (clipboardOverlayMultiuser()) {
            mUserTracker.addCallback(mCallback, mMainExecutor);
        }
        mClipboardManagerForUser.addPrimaryClipChangedListener(this);
    }

    @Override
    public void onPrimaryClipChanged() {
        if (!mClipboardManagerForUser.hasPrimaryClip()) {
            return;
        }

        String clipSource = mClipboardManagerForUser.getPrimaryClipSource();
        ClipData clipData = mClipboardManagerForUser.getPrimaryClip();

        if (overrideSuppressOverlayCondition()) {
            if (mClipboardOverlaySuppressionController.shouldSuppressOverlay(clipData, clipSource,
                    Build.IS_EMULATOR)) {
                Log.i(TAG, "Clipboard overlay suppressed.");
                return;
            }
        } else {
            if (shouldSuppressOverlay(clipData, clipSource, Build.IS_EMULATOR)) {
                Log.i(TAG, "Clipboard overlay suppressed.");
                return;
            }
        }

        // user should not access intents before setup or while device is locked
        if ((clipboardNoninteractiveOnLockscreen() && mKeyguardManagerForUser.isDeviceLocked())
                || !isUserSetupComplete()
                || clipData == null // shouldn't happen, but just in case
                || clipData.getItemCount() == 0) {
            if (shouldShowToast(clipData)) {
                mUiEventLogger.log(CLIPBOARD_TOAST_SHOWN, 0, clipSource);
                mClipboardToast.showCopiedToast();
            }
            return;
        }

        if (mClipboardOverlay == null) {
            mClipboardOverlay = mOverlayProvider.get();
            mUiEventLogger.log(CLIPBOARD_OVERLAY_ENTERED, 0, clipSource);
        } else {
            mUiEventLogger.log(CLIPBOARD_OVERLAY_UPDATED, 0, clipSource);
        }
        mClipboardOverlay.setClipData(clipData, clipSource);
        mClipboardOverlay.setOnSessionCompleteListener(() -> {
            // Session is complete, free memory until it's needed again.
            mClipboardOverlay = null;
        });
    }

    // The overlay is suppressed if EXTRA_SUPPRESS_OVERLAY is true and the device is an emulator or
    // the source package is SHELL_PACKAGE. This is meant to suppress the overlay when the emulator
    // or a mirrored device is syncing the clipboard.
    @VisibleForTesting
    static boolean shouldSuppressOverlay(ClipData clipData, String clipSource,
            boolean isEmulator) {
        if (!(isEmulator || SHELL_PACKAGE.equals(clipSource))) {
            return false;
        }
        if (clipData == null || clipData.getDescription().getExtras() == null) {
            return false;
        }
        return clipData.getDescription().getExtras().getBoolean(EXTRA_SUPPRESS_OVERLAY, false);
    }

    boolean shouldShowToast(ClipData clipData) {
        if (clipData == null) {
            return false;
        } else if (clipData.getDescription().getClassificationStatus() == CLASSIFICATION_COMPLETE) {
            // only show for classification complete if we aren't already showing a toast, to ignore
            // the duplicate ClipData with classification
            return !mClipboardToast.isShowing();
        }
        return true;
    }

    private boolean isUserSetupComplete() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                SETTINGS_SECURE_USER_SETUP_COMPLETE, 0) == 1;
    }

    interface ClipboardOverlay {
        void setClipData(ClipData clipData, String clipSource);

        void setOnSessionCompleteListener(Runnable runnable);
    }
}
