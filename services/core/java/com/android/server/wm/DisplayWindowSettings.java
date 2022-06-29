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

package com.android.server.wm;

import static android.view.WindowManager.DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
import static android.view.WindowManager.DISPLAY_IME_POLICY_LOCAL;
import static android.view.WindowManager.REMOVE_CONTENT_MODE_DESTROY;
import static android.view.WindowManager.REMOVE_CONTENT_MODE_MOVE_TO_PRIMARY;
import static android.view.WindowManager.REMOVE_CONTENT_MODE_UNDEFINED;

import static com.android.server.wm.DisplayContent.FORCE_SCALING_MODE_AUTO;
import static com.android.server.wm.DisplayContent.FORCE_SCALING_MODE_DISABLED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.WindowConfiguration;
import android.provider.Settings;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.IWindowManager;
import android.view.Surface;
import android.view.WindowManager.DisplayImePolicy;

import com.android.server.policy.WindowManagerPolicy;
import com.android.server.wm.DisplayContent.ForceScalingMode;

import java.util.Objects;

/**
 * Current persistent settings about a display. Provides policies for display settings and
 * delegates the persistence and lookup of settings values to the supplied {@link SettingsProvider}.
 */
class DisplayWindowSettings {
    private final WindowManagerService mService;
    private final SettingsProvider mSettingsProvider;

    DisplayWindowSettings(WindowManagerService service, SettingsProvider settingsProvider) {
        mService = service;
        mSettingsProvider = settingsProvider;
    }

    void setUserRotation(DisplayContent displayContent, int rotationMode, int rotation) {
        final DisplayInfo displayInfo = displayContent.getDisplayInfo();
        final SettingsProvider.SettingsEntry overrideSettings =
                mSettingsProvider.getOverrideSettings(displayInfo);
        overrideSettings.mUserRotationMode = rotationMode;
        overrideSettings.mUserRotation = rotation;
        mSettingsProvider.updateOverrideSettings(displayInfo, overrideSettings);
    }

    void setForcedSize(DisplayContent displayContent, int width, int height) {
        if (displayContent.isDefaultDisplay) {
            final String sizeString = (width == 0 || height == 0) ? "" : (width + "," + height);
            Settings.Global.putString(mService.mContext.getContentResolver(),
                    Settings.Global.DISPLAY_SIZE_FORCED, sizeString);
        }

        final DisplayInfo displayInfo = displayContent.getDisplayInfo();
        final SettingsProvider.SettingsEntry overrideSettings =
                mSettingsProvider.getOverrideSettings(displayInfo);
        overrideSettings.mForcedWidth = width;
        overrideSettings.mForcedHeight = height;
        mSettingsProvider.updateOverrideSettings(displayInfo, overrideSettings);
    }

    void setForcedDensity(DisplayContent displayContent, int density, int userId) {
        if (displayContent.isDefaultDisplay) {
            final String densityString = density == 0 ? "" : Integer.toString(density);
            Settings.Secure.putStringForUser(mService.mContext.getContentResolver(),
                    Settings.Secure.DISPLAY_DENSITY_FORCED, densityString, userId);
        }

        final DisplayInfo displayInfo = displayContent.getDisplayInfo();
        final SettingsProvider.SettingsEntry overrideSettings =
                mSettingsProvider.getOverrideSettings(displayInfo);
        overrideSettings.mForcedDensity = density;
        mSettingsProvider.updateOverrideSettings(displayInfo, overrideSettings);
    }

    void setForcedScalingMode(DisplayContent displayContent, @ForceScalingMode int mode) {
        if (displayContent.isDefaultDisplay) {
            Settings.Global.putInt(mService.mContext.getContentResolver(),
                    Settings.Global.DISPLAY_SCALING_FORCE, mode);
        }

        final DisplayInfo displayInfo = displayContent.getDisplayInfo();
        final SettingsProvider.SettingsEntry overrideSettings =
                mSettingsProvider.getOverrideSettings(displayInfo);
        overrideSettings.mForcedScalingMode = mode;
        mSettingsProvider.updateOverrideSettings(displayInfo, overrideSettings);
    }

    void setFixedToUserRotation(DisplayContent displayContent, int fixedToUserRotation) {
        final DisplayInfo displayInfo = displayContent.getDisplayInfo();
        final SettingsProvider.SettingsEntry overrideSettings =
                mSettingsProvider.getOverrideSettings(displayInfo);
        overrideSettings.mFixedToUserRotation = fixedToUserRotation;
        mSettingsProvider.updateOverrideSettings(displayInfo, overrideSettings);
    }

    void setIgnoreOrientationRequest(
            DisplayContent displayContent, boolean ignoreOrientationRequest) {
        final DisplayInfo displayInfo = displayContent.getDisplayInfo();
        final SettingsProvider.SettingsEntry overrideSettings =
                mSettingsProvider.getOverrideSettings(displayInfo);
        overrideSettings.mIgnoreOrientationRequest = ignoreOrientationRequest;
        mSettingsProvider.updateOverrideSettings(displayInfo, overrideSettings);
    }

    private int getWindowingModeLocked(SettingsProvider.SettingsEntry settings, DisplayContent dc) {
        int windowingMode = settings.mWindowingMode;
        // This display used to be in freeform, but we don't support freeform anymore, so fall
        // back to fullscreen.
        if (windowingMode == WindowConfiguration.WINDOWING_MODE_FREEFORM
                && !mService.mAtmService.mSupportsFreeformWindowManagement) {
            return WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
        }
        // No record is present so use default windowing mode policy.
        if (windowingMode == WindowConfiguration.WINDOWING_MODE_UNDEFINED) {
            windowingMode = mService.mAtmService.mSupportsFreeformWindowManagement
                    && (mService.mIsPc || dc.forceDesktopMode())
                    ? WindowConfiguration.WINDOWING_MODE_FREEFORM
                    : WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
        }
        return windowingMode;
    }

    int getWindowingModeLocked(DisplayContent dc) {
        final DisplayInfo displayInfo = dc.getDisplayInfo();
        final SettingsProvider.SettingsEntry settings = mSettingsProvider.getSettings(displayInfo);
        return getWindowingModeLocked(settings, dc);
    }

    void setWindowingModeLocked(DisplayContent dc, int mode) {
        final DisplayInfo displayInfo = dc.getDisplayInfo();
        final SettingsProvider.SettingsEntry overrideSettings =
                mSettingsProvider.getOverrideSettings(displayInfo);
        overrideSettings.mWindowingMode = mode;
        dc.setWindowingMode(mode);
        mSettingsProvider.updateOverrideSettings(displayInfo, overrideSettings);
    }

    int getRemoveContentModeLocked(DisplayContent dc) {
        final DisplayInfo displayInfo = dc.getDisplayInfo();
        final SettingsProvider.SettingsEntry settings = mSettingsProvider.getSettings(displayInfo);
        if (settings.mRemoveContentMode == REMOVE_CONTENT_MODE_UNDEFINED) {
            if (dc.isPrivate()) {
                // For private displays by default content is destroyed on removal.
                return REMOVE_CONTENT_MODE_DESTROY;
            }
            // For other displays by default content is moved to primary on removal.
            return REMOVE_CONTENT_MODE_MOVE_TO_PRIMARY;
        }
        return settings.mRemoveContentMode;
    }

    void setRemoveContentModeLocked(DisplayContent dc, int mode) {
        final DisplayInfo displayInfo = dc.getDisplayInfo();
        final SettingsProvider.SettingsEntry overrideSettings =
                mSettingsProvider.getOverrideSettings(displayInfo);
        overrideSettings.mRemoveContentMode = mode;
        mSettingsProvider.updateOverrideSettings(displayInfo, overrideSettings);
    }

    boolean shouldShowWithInsecureKeyguardLocked(DisplayContent dc) {
        final DisplayInfo displayInfo = dc.getDisplayInfo();
        final SettingsProvider.SettingsEntry settings = mSettingsProvider.getSettings(displayInfo);
        return settings.mShouldShowWithInsecureKeyguard != null
                ? settings.mShouldShowWithInsecureKeyguard : false;
    }

    void setShouldShowWithInsecureKeyguardLocked(DisplayContent dc, boolean shouldShow) {
        if (!dc.isPrivate() && shouldShow) {
            throw new IllegalArgumentException("Public display can't be allowed to show content"
                    + " when locked");
        }

        final DisplayInfo displayInfo = dc.getDisplayInfo();
        final SettingsProvider.SettingsEntry overrideSettings =
                mSettingsProvider.getOverrideSettings(displayInfo);
        overrideSettings.mShouldShowWithInsecureKeyguard = shouldShow;
        mSettingsProvider.updateOverrideSettings(displayInfo, overrideSettings);
    }

    void setDontMoveToTop(DisplayContent dc, boolean dontMoveToTop) {
        DisplayInfo displayInfo = dc.getDisplayInfo();
        SettingsProvider.SettingsEntry overrideSettings =
                mSettingsProvider.getSettings(displayInfo);
        overrideSettings.mDontMoveToTop = dontMoveToTop;
        mSettingsProvider.updateOverrideSettings(displayInfo, overrideSettings);
    }

    boolean shouldShowSystemDecorsLocked(DisplayContent dc) {
        if (dc.getDisplayId() == Display.DEFAULT_DISPLAY) {
            // Default display should show system decors.
            return true;
        }

        final DisplayInfo displayInfo = dc.getDisplayInfo();
        final SettingsProvider.SettingsEntry settings = mSettingsProvider.getSettings(displayInfo);
        return settings.mShouldShowSystemDecors != null ? settings.mShouldShowSystemDecors : false;
    }

    void setShouldShowSystemDecorsLocked(DisplayContent dc, boolean shouldShow) {
        final DisplayInfo displayInfo = dc.getDisplayInfo();
        final SettingsProvider.SettingsEntry overrideSettings =
                mSettingsProvider.getOverrideSettings(displayInfo);
        overrideSettings.mShouldShowSystemDecors = shouldShow;
        mSettingsProvider.updateOverrideSettings(displayInfo, overrideSettings);
    }

    @DisplayImePolicy int getImePolicyLocked(DisplayContent dc) {
        if (dc.getDisplayId() == Display.DEFAULT_DISPLAY) {
            // Default display should show IME.
            return DISPLAY_IME_POLICY_LOCAL;
        }

        final DisplayInfo displayInfo = dc.getDisplayInfo();
        final SettingsProvider.SettingsEntry settings = mSettingsProvider.getSettings(displayInfo);
        return settings.mImePolicy != null ? settings.mImePolicy
                : DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
    }

    void setDisplayImePolicy(DisplayContent dc, @DisplayImePolicy int imePolicy) {
        final DisplayInfo displayInfo = dc.getDisplayInfo();
        final SettingsProvider.SettingsEntry overrideSettings =
                mSettingsProvider.getOverrideSettings(displayInfo);
        overrideSettings.mImePolicy = imePolicy;
        mSettingsProvider.updateOverrideSettings(displayInfo, overrideSettings);
    }

    void applySettingsToDisplayLocked(DisplayContent dc) {
        applySettingsToDisplayLocked(dc, /* includeRotationSettings */ true);
    }

    void applySettingsToDisplayLocked(DisplayContent dc, boolean includeRotationSettings) {
        final DisplayInfo displayInfo = dc.getDisplayInfo();
        final SettingsProvider.SettingsEntry settings = mSettingsProvider.getSettings(displayInfo);

        // Setting windowing mode first, because it may override overscan values later.
        final int windowingMode = getWindowingModeLocked(settings, dc);
        dc.setWindowingMode(windowingMode);

        final int userRotationMode = settings.mUserRotationMode != null
                ? settings.mUserRotationMode : WindowManagerPolicy.USER_ROTATION_FREE;
        final int userRotation = settings.mUserRotation != null
                ? settings.mUserRotation : Surface.ROTATION_0;
        final int mFixedToUserRotation = settings.mFixedToUserRotation != null
                ? settings.mFixedToUserRotation : IWindowManager.FIXED_TO_USER_ROTATION_DEFAULT;
        dc.getDisplayRotation().restoreSettings(userRotationMode, userRotation,
                mFixedToUserRotation);

        final boolean hasDensityOverride = settings.mForcedDensity != 0;
        final boolean hasSizeOverride = settings.mForcedWidth != 0 && settings.mForcedHeight != 0;
        dc.mIsDensityForced = hasDensityOverride;
        dc.mIsSizeForced = hasSizeOverride;

        final boolean ignoreDisplayCutout = settings.mIgnoreDisplayCutout != null
                ? settings.mIgnoreDisplayCutout : false;
        dc.mIgnoreDisplayCutout = ignoreDisplayCutout;

        final int width = hasSizeOverride ? settings.mForcedWidth : dc.mInitialDisplayWidth;
        final int height = hasSizeOverride ? settings.mForcedHeight : dc.mInitialDisplayHeight;
        final int density = hasDensityOverride ? settings.mForcedDensity
                : dc.mInitialDisplayDensity;
        dc.updateBaseDisplayMetrics(width, height, density, dc.mBaseDisplayPhysicalXDpi,
                dc.mBaseDisplayPhysicalYDpi);

        final int forcedScalingMode = settings.mForcedScalingMode != null
                ? settings.mForcedScalingMode : FORCE_SCALING_MODE_AUTO;
        dc.mDisplayScalingDisabled = forcedScalingMode == FORCE_SCALING_MODE_DISABLED;

        boolean dontMoveToTop = settings.mDontMoveToTop != null
                ? settings.mDontMoveToTop : false;
        dc.mDontMoveToTop = dontMoveToTop;

        if (includeRotationSettings) applyRotationSettingsToDisplayLocked(dc);
    }

    void applyRotationSettingsToDisplayLocked(DisplayContent dc) {
        final DisplayInfo displayInfo = dc.getDisplayInfo();
        final SettingsProvider.SettingsEntry settings = mSettingsProvider.getSettings(displayInfo);

        final boolean ignoreOrientationRequest = settings.mIgnoreOrientationRequest != null
                ? settings.mIgnoreOrientationRequest : false;
        dc.setIgnoreOrientationRequest(ignoreOrientationRequest);

        dc.getDisplayRotation().resetAllowAllRotations();
    }

    /**
     * Updates settings for the given display after system features are loaded into window manager
     * service, e.g. if this device is PC and if this device supports freeform.
     *
     * @param dc the given display.
     * @return {@code true} if any settings for this display has changed; {@code false} if nothing
     * changed.
     */
    boolean updateSettingsForDisplay(DisplayContent dc) {
        if (dc.getWindowingMode() != getWindowingModeLocked(dc)) {
            // For the time being the only thing that may change is windowing mode, so just update
            // that.
            dc.setWindowingMode(getWindowingModeLocked(dc));
            return true;
        }
        return false;
    }

    /**
     * Provides the functionality to lookup the {@link SettingsEntry settings} for a given
     * {@link DisplayInfo}.
     * <p>
     * NOTE: All interactions with implementations of this provider <b>must</b> be thread-safe
     * externally.
     */
    interface SettingsProvider {
        /**
         * Returns the {@link SettingsEntry} for a given {@link DisplayInfo}. The values for the
         * returned settings are guaranteed to match those previously set with
         * {@link #updateOverrideSettings(DisplayInfo, SettingsEntry)} with all other values left
         * to the implementation to determine.
         */
        @NonNull
        SettingsEntry getSettings(@NonNull DisplayInfo info);

        /**
         * Returns the existing override settings for the given {@link DisplayInfo}. All calls to
         * {@link #getSettings(DisplayInfo)} for the provided {@code info} are required to have
         * their values overridden with all set values from the returned {@link SettingsEntry}.
         *
         * @see #getSettings(DisplayInfo)
         * @see #updateOverrideSettings(DisplayInfo, SettingsEntry)
         */
        @NonNull
        SettingsEntry getOverrideSettings(@NonNull DisplayInfo info);

        /**
         * Updates the override settings for a given {@link DisplayInfo}. All subsequent calls to
         * {@link #getSettings(DisplayInfo)} for the provided {@link DisplayInfo} are required to
         * have their values match all set values in {@code overrides}.
         *
         * @see #getSettings(DisplayInfo)
         */
        void updateOverrideSettings(@NonNull DisplayInfo info, @NonNull SettingsEntry overrides);

        /**
         * Settings for a display.
         */
        class SettingsEntry {
            int mWindowingMode = WindowConfiguration.WINDOWING_MODE_UNDEFINED;
            @Nullable
            Integer mUserRotationMode;
            @Nullable
            Integer mUserRotation;
            int mForcedWidth;
            int mForcedHeight;
            int mForcedDensity;
            @Nullable
            Integer mForcedScalingMode;
            int mRemoveContentMode = REMOVE_CONTENT_MODE_UNDEFINED;
            @Nullable
            Boolean mShouldShowWithInsecureKeyguard;
            @Nullable
            Boolean mShouldShowSystemDecors;
            @Nullable
            Integer mImePolicy;
            @Nullable
            Integer mFixedToUserRotation;
            @Nullable
            Boolean mIgnoreOrientationRequest;
            @Nullable
            Boolean mIgnoreDisplayCutout;
            @Nullable
            Boolean mDontMoveToTop;

            SettingsEntry() {}

            SettingsEntry(SettingsEntry copyFrom) {
                setTo(copyFrom);
            }

            /**
             * Copies all fields from {@code delta} into this {@link SettingsEntry} object, keeping
             * track of whether a change has occurred.
             *
             * @return {@code true} if this settings have changed as a result of the copy,
             *         {@code false} otherwise.
             *
             * @see #updateFrom(SettingsEntry)
             */
            boolean setTo(@NonNull SettingsEntry other) {
                boolean changed = false;
                if (other.mWindowingMode != mWindowingMode) {
                    mWindowingMode = other.mWindowingMode;
                    changed = true;
                }
                if (!Objects.equals(other.mUserRotationMode, mUserRotationMode)) {
                    mUserRotationMode = other.mUserRotationMode;
                    changed = true;
                }
                if (!Objects.equals(other.mUserRotation, mUserRotation)) {
                    mUserRotation = other.mUserRotation;
                    changed = true;
                }
                if (other.mForcedWidth != mForcedWidth) {
                    mForcedWidth = other.mForcedWidth;
                    changed = true;
                }
                if (other.mForcedHeight != mForcedHeight) {
                    mForcedHeight = other.mForcedHeight;
                    changed = true;
                }
                if (other.mForcedDensity != mForcedDensity) {
                    mForcedDensity = other.mForcedDensity;
                    changed = true;
                }
                if (!Objects.equals(other.mForcedScalingMode, mForcedScalingMode)) {
                    mForcedScalingMode = other.mForcedScalingMode;
                    changed = true;
                }
                if (other.mRemoveContentMode != mRemoveContentMode) {
                    mRemoveContentMode = other.mRemoveContentMode;
                    changed = true;
                }
                if (other.mShouldShowWithInsecureKeyguard != mShouldShowWithInsecureKeyguard) {
                    mShouldShowWithInsecureKeyguard = other.mShouldShowWithInsecureKeyguard;
                    changed = true;
                }
                if (other.mShouldShowSystemDecors != mShouldShowSystemDecors) {
                    mShouldShowSystemDecors = other.mShouldShowSystemDecors;
                    changed = true;
                }
                if (!Objects.equals(other.mImePolicy, mImePolicy)) {
                    mImePolicy = other.mImePolicy;
                    changed = true;
                }
                if (!Objects.equals(other.mFixedToUserRotation, mFixedToUserRotation)) {
                    mFixedToUserRotation = other.mFixedToUserRotation;
                    changed = true;
                }
                if (other.mIgnoreOrientationRequest != mIgnoreOrientationRequest) {
                    mIgnoreOrientationRequest = other.mIgnoreOrientationRequest;
                    changed = true;
                }
                if (other.mIgnoreDisplayCutout != mIgnoreDisplayCutout) {
                    mIgnoreDisplayCutout = other.mIgnoreDisplayCutout;
                    changed = true;
                }
                if (other.mDontMoveToTop != mDontMoveToTop) {
                    mDontMoveToTop = other.mDontMoveToTop;
                    changed = true;
                }
                return changed;
            }

            /**
             * Copies the fields from {@code delta} into this {@link SettingsEntry} object, keeping
             * track of whether a change has occurred. Any undefined fields in {@code delta} are
             * ignored and not copied into the current {@link SettingsEntry}.
             *
             * @return {@code true} if this settings have changed as a result of the copy,
             *         {@code false} otherwise.
             *
             * @see #setTo(SettingsEntry)
             */
            boolean updateFrom(@NonNull SettingsEntry delta) {
                boolean changed = false;
                if (delta.mWindowingMode != WindowConfiguration.WINDOWING_MODE_UNDEFINED
                        && delta.mWindowingMode != mWindowingMode) {
                    mWindowingMode = delta.mWindowingMode;
                    changed = true;
                }
                if (delta.mUserRotationMode != null
                        && !Objects.equals(delta.mUserRotationMode, mUserRotationMode)) {
                    mUserRotationMode = delta.mUserRotationMode;
                    changed = true;
                }
                if (delta.mUserRotation != null
                        && !Objects.equals(delta.mUserRotation, mUserRotation)) {
                    mUserRotation = delta.mUserRotation;
                    changed = true;
                }
                if (delta.mForcedWidth != 0 && delta.mForcedWidth != mForcedWidth) {
                    mForcedWidth = delta.mForcedWidth;
                    changed = true;
                }
                if (delta.mForcedHeight != 0 && delta.mForcedHeight != mForcedHeight) {
                    mForcedHeight = delta.mForcedHeight;
                    changed = true;
                }
                if (delta.mForcedDensity != 0 && delta.mForcedDensity != mForcedDensity) {
                    mForcedDensity = delta.mForcedDensity;
                    changed = true;
                }
                if (delta.mForcedScalingMode != null
                        && !Objects.equals(delta.mForcedScalingMode, mForcedScalingMode)) {
                    mForcedScalingMode = delta.mForcedScalingMode;
                    changed = true;
                }
                if (delta.mRemoveContentMode != REMOVE_CONTENT_MODE_UNDEFINED
                        && delta.mRemoveContentMode != mRemoveContentMode) {
                    mRemoveContentMode = delta.mRemoveContentMode;
                    changed = true;
                }
                if (delta.mShouldShowWithInsecureKeyguard != null
                        && delta.mShouldShowWithInsecureKeyguard
                        != mShouldShowWithInsecureKeyguard) {
                    mShouldShowWithInsecureKeyguard = delta.mShouldShowWithInsecureKeyguard;
                    changed = true;
                }
                if (delta.mShouldShowSystemDecors != null
                        && delta.mShouldShowSystemDecors != mShouldShowSystemDecors) {
                    mShouldShowSystemDecors = delta.mShouldShowSystemDecors;
                    changed = true;
                }
                if (delta.mImePolicy != null
                        && !Objects.equals(delta.mImePolicy, mImePolicy)) {
                    mImePolicy = delta.mImePolicy;
                    changed = true;
                }
                if (delta.mFixedToUserRotation != null
                        && !Objects.equals(delta.mFixedToUserRotation, mFixedToUserRotation)) {
                    mFixedToUserRotation = delta.mFixedToUserRotation;
                    changed = true;
                }
                if (delta.mIgnoreOrientationRequest != null
                        && delta.mIgnoreOrientationRequest != mIgnoreOrientationRequest) {
                    mIgnoreOrientationRequest = delta.mIgnoreOrientationRequest;
                    changed = true;
                }
                if (delta.mIgnoreDisplayCutout != null
                        && delta.mIgnoreDisplayCutout != mIgnoreDisplayCutout) {
                    mIgnoreDisplayCutout = delta.mIgnoreDisplayCutout;
                    changed = true;
                }
                if (delta.mDontMoveToTop != null
                        && delta.mDontMoveToTop != mDontMoveToTop) {
                    mDontMoveToTop = delta.mDontMoveToTop;
                    changed = true;
                }
                return changed;
            }

            /** @return {@code true} if all values are unset. */
            boolean isEmpty() {
                return mWindowingMode == WindowConfiguration.WINDOWING_MODE_UNDEFINED
                        && mUserRotationMode == null
                        && mUserRotation == null
                        && mForcedWidth == 0 && mForcedHeight == 0 && mForcedDensity == 0
                        && mForcedScalingMode == null
                        && mRemoveContentMode == REMOVE_CONTENT_MODE_UNDEFINED
                        && mShouldShowWithInsecureKeyguard == null
                        && mShouldShowSystemDecors == null
                        && mImePolicy == null
                        && mFixedToUserRotation == null
                        && mIgnoreOrientationRequest == null
                        && mIgnoreDisplayCutout == null
                        && mDontMoveToTop == null;
            }

            @Override
            public boolean equals(@Nullable Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                SettingsEntry that = (SettingsEntry) o;
                return mWindowingMode == that.mWindowingMode
                        && mForcedWidth == that.mForcedWidth
                        && mForcedHeight == that.mForcedHeight
                        && mForcedDensity == that.mForcedDensity
                        && mRemoveContentMode == that.mRemoveContentMode
                        && Objects.equals(mUserRotationMode, that.mUserRotationMode)
                        && Objects.equals(mUserRotation, that.mUserRotation)
                        && Objects.equals(mForcedScalingMode, that.mForcedScalingMode)
                        && Objects.equals(mShouldShowWithInsecureKeyguard,
                                that.mShouldShowWithInsecureKeyguard)
                        && Objects.equals(mShouldShowSystemDecors, that.mShouldShowSystemDecors)
                        && Objects.equals(mImePolicy, that.mImePolicy)
                        && Objects.equals(mFixedToUserRotation, that.mFixedToUserRotation)
                        && Objects.equals(mIgnoreOrientationRequest, that.mIgnoreOrientationRequest)
                        && Objects.equals(mIgnoreDisplayCutout, that.mIgnoreDisplayCutout)
                        && Objects.equals(mDontMoveToTop, that.mDontMoveToTop);
            }

            @Override
            public int hashCode() {
                return Objects.hash(mWindowingMode, mUserRotationMode, mUserRotation, mForcedWidth,
                        mForcedHeight, mForcedDensity, mForcedScalingMode, mRemoveContentMode,
                        mShouldShowWithInsecureKeyguard, mShouldShowSystemDecors, mImePolicy,
                        mFixedToUserRotation, mIgnoreOrientationRequest, mIgnoreDisplayCutout,
                        mDontMoveToTop);
            }

            @Override
            public String toString() {
                return "SettingsEntry{"
                        + "mWindowingMode=" + mWindowingMode
                        + ", mUserRotationMode=" + mUserRotationMode
                        + ", mUserRotation=" + mUserRotation
                        + ", mForcedWidth=" + mForcedWidth
                        + ", mForcedHeight=" + mForcedHeight
                        + ", mForcedDensity=" + mForcedDensity
                        + ", mForcedScalingMode=" + mForcedScalingMode
                        + ", mRemoveContentMode=" + mRemoveContentMode
                        + ", mShouldShowWithInsecureKeyguard=" + mShouldShowWithInsecureKeyguard
                        + ", mShouldShowSystemDecors=" + mShouldShowSystemDecors
                        + ", mShouldShowIme=" + mImePolicy
                        + ", mFixedToUserRotation=" + mFixedToUserRotation
                        + ", mIgnoreOrientationRequest=" + mIgnoreOrientationRequest
                        + ", mIgnoreDisplayCutout=" + mIgnoreDisplayCutout
                        + ", mDontMoveToTop=" + mDontMoveToTop
                        + '}';
            }
        }
    }
}
