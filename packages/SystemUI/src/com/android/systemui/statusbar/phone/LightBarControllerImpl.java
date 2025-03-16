/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;

import static com.android.systemui.shared.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT_TRANSPARENT;
import static com.android.systemui.shared.statusbar.phone.BarTransitions.MODE_TRANSPARENT;

import android.content.Context;
import android.graphics.Rect;
import android.util.Log;
import android.view.Display;
import android.view.InsetsFlags;
import android.view.ViewDebug;
import android.view.WindowInsetsController.Appearance;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.internal.view.AppearanceRegion;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.statusbar.data.model.StatusBarAppearance;
import com.android.systemui.statusbar.data.repository.DarkIconDispatcherStore;
import com.android.systemui.statusbar.data.repository.StatusBarModePerDisplayRepository;
import com.android.systemui.statusbar.data.repository.StatusBarModeRepositoryStore;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.util.Compile;
import com.android.systemui.util.kotlin.JavaAdapterKt;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

import kotlin.coroutines.CoroutineContext;

import kotlinx.coroutines.CoroutineScope;

import java.io.PrintWriter;
import java.util.ArrayList;

import javax.inject.Inject;

/**
 * Controls how light status bar flag applies to the icons.
 */
public class LightBarControllerImpl implements
        BatteryController.BatteryStateChangeCallback, LightBarController {

    private static final String TAG = "LightBarController";
    private static final boolean DEBUG_NAVBAR = Compile.IS_DEBUG;
    private static final boolean DEBUG_LOGS = Compile.IS_DEBUG && Log.isLoggable(TAG, Log.DEBUG);

    private static final float NAV_BAR_INVERSION_SCRIM_ALPHA_THRESHOLD = 0.1f;

    private final int mDisplayId;
    private final CoroutineScope mCoroutineScope;
    private final SysuiDarkIconDispatcher mStatusBarIconController;
    private final BatteryController mBatteryController;
    private final NavigationModeController mNavModeController;
    private final DumpManager mDumpManager;
    private final StatusBarModePerDisplayRepository mStatusBarModeRepository;
    private final CoroutineContext mMainContext;
    private final BiometricUnlockController mBiometricUnlockController;

    private LightBarTransitionsController mNavigationBarController;
    private @Appearance int mAppearance;
    private AppearanceRegion[] mAppearanceRegions = new AppearanceRegion[0];
    private int mStatusBarMode;
    private BoundsPair mStatusBarBounds = new BoundsPair(new Rect(), new Rect());
    private int mNavigationBarMode;
    private int mNavigationMode;

    /**
     * Whether the navigation bar should be light factoring in already how much alpha the scrim has.
     * "Light" refers to the background color of the navigation bar, so when this is true,
     * it's referring to a state where the navigation bar icons are tinted dark.
     */
    private boolean mNavigationLight;

    /**
     * Whether the flags indicate that a light navigation bar is requested.
     * "Light" refers to the background color of the navigation bar, so when this is true,
     * it's referring to a state where the navigation bar icons would be tinted dark.
     * This doesn't factor in the scrim alpha yet.
     */
    private boolean mHasLightNavigationBar;

    /**
     * {@code true} if {@link #mHasLightNavigationBar} should be ignored and forcefully make
     * {@link #mNavigationLight} {@code false}.
     */
    private boolean mForceDarkForScrim;
    /**
     * {@code true} if {@link #mHasLightNavigationBar} should be ignored and forcefully make
     * {@link #mNavigationLight} {@code true}.
     */
    private boolean mForceLightForScrim;

    private boolean mQsCustomizing;
    private boolean mQsExpanded;
    private boolean mBouncerVisible;
    private boolean mGlobalActionsVisible;

    private boolean mDirectReplying;
    private boolean mNavbarColorManagedByIme;

    private boolean mIsCustomizingForBackNav;

    private String mLastSetScrimStateLog;
    private String mLastNavigationBarAppearanceChangedLog;
    private StringBuilder mLogStringBuilder = null;

    private final String mDumpableName;

    private final NavigationModeController.ModeChangedListener mNavigationModeListener =
            (mode) -> mNavigationMode = mode;

    @AssistedInject
    public LightBarControllerImpl(
            @Assisted int displayId,
            @Assisted CoroutineScope coroutineScope,
            @Assisted DarkIconDispatcher darkIconDispatcher,
            BatteryController batteryController,
            NavigationModeController navModeController,
            @Assisted StatusBarModePerDisplayRepository statusBarModeRepository,
            DumpManager dumpManager,
            @Main CoroutineContext mainContext,
            BiometricUnlockController biometricUnlockController) {
        mDisplayId = displayId;
        mCoroutineScope = coroutineScope;
        mStatusBarIconController = (SysuiDarkIconDispatcher) darkIconDispatcher;
        mBatteryController = batteryController;
        mNavModeController = navModeController;
        mDumpManager = dumpManager;
        mStatusBarModeRepository = statusBarModeRepository;
        mMainContext = mainContext;
        mBiometricUnlockController = biometricUnlockController;
        String dumpableNameSuffix =
                displayId == Display.DEFAULT_DISPLAY ? "" : String.valueOf(displayId);
        mDumpableName = getClass().getSimpleName() + dumpableNameSuffix;
    }

    @Override
    public void start() {
        if (mDisplayId == Display.DEFAULT_DISPLAY) {
            // Can only register on default display, because NavigationBar creates its own instance
            // as well as PerDisplayStore.
            // TODO: b/380394368 - make sure there is only one instance per display.
            mDumpManager.registerCriticalDumpable(mDumpableName, this);
        }
        mBatteryController.addCallback(this);
        mNavigationMode = mNavModeController.addListener(mNavigationModeListener);
        JavaAdapterKt.collectFlow(
                mCoroutineScope,
                mMainContext,
                mStatusBarModeRepository.getStatusBarAppearance(),
                this::onStatusBarAppearanceChanged);
    }

    @Override
    public void stop() {
        mDumpManager.unregisterDumpable(mDumpableName);
        mBatteryController.removeCallback(this);
        mNavModeController.removeListener(mNavigationModeListener);
    }

    @Override
    public void setNavigationBar(LightBarTransitionsController navigationBar) {
        mNavigationBarController = navigationBar;
        updateNavigation();
    }

    private void onStatusBarAppearanceChanged(@Nullable StatusBarAppearance params) {
        if (params == null) {
            return;
        }
        int newStatusBarMode = params.getMode().toTransitionModeInt();
        boolean sbModeChanged = mStatusBarMode != newStatusBarMode;
        mStatusBarMode = newStatusBarMode;

        boolean sbBoundsChanged = !mStatusBarBounds.equals(params.getBounds());
        mStatusBarBounds = params.getBounds();

        onStatusBarAppearanceChanged(
                params.getAppearanceRegions().toArray(new AppearanceRegion[0]),
                sbModeChanged,
                sbBoundsChanged,
                params.getNavbarColorManagedByIme());
    }

    private void onStatusBarAppearanceChanged(
            AppearanceRegion[] appearanceRegions,
            boolean sbModeChanged,
            boolean sbBoundsChanged,
            boolean navbarColorManagedByIme) {
        final int numStacks = appearanceRegions.length;
        boolean stackAppearancesChanged = mAppearanceRegions.length != numStacks;
        for (int i = 0; i < numStacks && !stackAppearancesChanged; i++) {
            stackAppearancesChanged |= !appearanceRegions[i].equals(mAppearanceRegions[i]);
        }

        if (stackAppearancesChanged
                || sbModeChanged
                // Be sure to re-draw when the status bar bounds have changed because the status bar
                // icons may have moved to be part of a different appearance region. See b/301605450
                || sbBoundsChanged
                || mIsCustomizingForBackNav) {
            mAppearanceRegions = appearanceRegions;
            updateStatus(mAppearanceRegions);
            mIsCustomizingForBackNav = false;
        }
        mNavbarColorManagedByIme = navbarColorManagedByIme;
    }

    @Override
    public void onNavigationBarAppearanceChanged(@Appearance int appearance, boolean nbModeChanged,
            int navigationBarMode, boolean navbarColorManagedByIme) {
        int diff = appearance ^ mAppearance;
        if ((diff & APPEARANCE_LIGHT_NAVIGATION_BARS) != 0 || nbModeChanged) {
            final boolean last = mNavigationLight;
            mHasLightNavigationBar = isLight(appearance, navigationBarMode,
                    APPEARANCE_LIGHT_NAVIGATION_BARS);
            final boolean ignoreScrimForce = mDirectReplying && mNavbarColorManagedByIme;
            final boolean darkForScrim = mForceDarkForScrim && !ignoreScrimForce;
            final boolean lightForScrim = mForceLightForScrim && !ignoreScrimForce;
            final boolean darkForQs = (mQsCustomizing || mQsExpanded) && !mBouncerVisible;
            final boolean darkForTop = darkForQs || mGlobalActionsVisible;
            mNavigationLight =
                    ((mHasLightNavigationBar && !darkForScrim) || lightForScrim) && !darkForTop;
            if (DEBUG_NAVBAR) {
                mLastNavigationBarAppearanceChangedLog = getLogStringBuilder()
                        .append("onNavigationBarAppearanceChanged()")
                        .append(" appearance=").append(appearance)
                        .append(" nbModeChanged=").append(nbModeChanged)
                        .append(" navigationBarMode=").append(navigationBarMode)
                        .append(" navbarColorManagedByIme=").append(navbarColorManagedByIme)
                        .append(" mHasLightNavigationBar=").append(mHasLightNavigationBar)
                        .append(" ignoreScrimForce=").append(ignoreScrimForce)
                        .append(" darkForScrim=").append(darkForScrim)
                        .append(" lightForScrim=").append(lightForScrim)
                        .append(" darkForQs=").append(darkForQs)
                        .append(" darkForTop=").append(darkForTop)
                        .append(" mNavigationLight=").append(mNavigationLight)
                        .append(" last=").append(last)
                        .append(" timestamp=").append(System.currentTimeMillis())
                        .toString();
                if (DEBUG_LOGS) Log.d(TAG, mLastNavigationBarAppearanceChangedLog);
            }
            if (mNavigationLight != last) {
                updateNavigation();
            }
        }
        mAppearance = appearance;
        mNavigationBarMode = navigationBarMode;
        mNavbarColorManagedByIme = navbarColorManagedByIme;
    }

    @Override
    public void onNavigationBarModeChanged(int newBarMode) {
        mHasLightNavigationBar = isLight(mAppearance, newBarMode, APPEARANCE_LIGHT_NAVIGATION_BARS);
    }

    private void reevaluate() {
        onStatusBarAppearanceChanged(
                mAppearanceRegions,
                /* sbModeChanged= */ true,
                /* sbBoundsChanged= */ true,
                mNavbarColorManagedByIme);
        onNavigationBarAppearanceChanged(mAppearance, true /* nbModeChanged */,
                mNavigationBarMode, mNavbarColorManagedByIme);
    }

    @Override
    public void setQsCustomizing(boolean customizing) {
        if (mQsCustomizing == customizing) return;
        mQsCustomizing = customizing;
        reevaluate();
    }

    @Override
    public void setQsExpanded(boolean expanded) {
        if (mQsExpanded == expanded) return;
        mQsExpanded = expanded;
        reevaluate();
    }

    @Override
    public void setGlobalActionsVisible(boolean visible) {
        if (mGlobalActionsVisible == visible) return;
        mGlobalActionsVisible = visible;
        reevaluate();
    }

    @Override
    public void customizeStatusBarAppearance(AppearanceRegion appearance) {
        if (appearance != null) {
            final ArrayList<AppearanceRegion> appearancesList = new ArrayList<>();
            appearancesList.add(appearance);
            for (int i = 0; i < mAppearanceRegions.length; i++) {
                final AppearanceRegion ar = mAppearanceRegions[i];
                if (appearance.getBounds().contains(ar.getBounds())) {
                    continue;
                }
                appearancesList.add(ar);
            }

            final AppearanceRegion[] newAppearances = new AppearanceRegion[appearancesList.size()];
            updateStatus(appearancesList.toArray(newAppearances));
            mIsCustomizingForBackNav = true;
        } else {
            mIsCustomizingForBackNav = false;
            updateStatus(mAppearanceRegions);
        }
    }

    @Override
    public void setDirectReplying(boolean directReplying) {
        if (mDirectReplying == directReplying) return;
        mDirectReplying = directReplying;
        reevaluate();
    }

    @Override
    public void setScrimState(ScrimState scrimState, float scrimBehindAlpha,
            GradientColors scrimInFrontColor) {
        boolean bouncerVisibleLast = mBouncerVisible;
        boolean forceDarkForScrimLast = mForceDarkForScrim;
        boolean forceLightForScrimLast = mForceLightForScrim;
        mBouncerVisible =
                scrimState == ScrimState.BOUNCER || scrimState == ScrimState.BOUNCER_SCRIMMED;
        final boolean forceForScrim = mBouncerVisible
                || scrimBehindAlpha >= NAV_BAR_INVERSION_SCRIM_ALPHA_THRESHOLD;
        final boolean scrimColorIsLight = scrimInFrontColor.supportsDarkText();

        mForceDarkForScrim = forceForScrim && !scrimColorIsLight;
        mForceLightForScrim = forceForScrim && scrimColorIsLight;
        if (mBouncerVisible != bouncerVisibleLast) {
            reevaluate();
        } else if (mHasLightNavigationBar) {
            if (mForceDarkForScrim != forceDarkForScrimLast) reevaluate();
        } else {
            if (mForceLightForScrim != forceLightForScrimLast) reevaluate();
        }
        if (DEBUG_NAVBAR) {
            mLastSetScrimStateLog = getLogStringBuilder()
                    .append("setScrimState()")
                    .append(" scrimState=").append(scrimState)
                    .append(" scrimBehindAlpha=").append(scrimBehindAlpha)
                    .append(" scrimInFrontColor=").append(scrimInFrontColor)
                    .append(" forceForScrim=").append(forceForScrim)
                    .append(" scrimColorIsLight=").append(scrimColorIsLight)
                    .append(" mHasLightNavigationBar=").append(mHasLightNavigationBar)
                    .append(" mBouncerVisible=").append(mBouncerVisible)
                    .append(" mForceDarkForScrim=").append(mForceDarkForScrim)
                    .append(" mForceLightForScrim=").append(mForceLightForScrim)
                    .append(" timestamp=").append(System.currentTimeMillis())
                    .toString();
            if (DEBUG_LOGS) Log.d(TAG, mLastSetScrimStateLog);
        }
    }

    @NonNull
    private StringBuilder getLogStringBuilder() {
        if (mLogStringBuilder == null) {
            mLogStringBuilder = new StringBuilder();
        }
        mLogStringBuilder.setLength(0);
        return mLogStringBuilder;
    }

    private static boolean isLight(int appearance, int barMode, int flag) {
        final boolean isTransparentBar = (barMode == MODE_TRANSPARENT
                || barMode == MODE_LIGHTS_OUT_TRANSPARENT);
        final boolean light = (appearance & flag) != 0;
        return isTransparentBar && light;
    }

    private boolean animateChange() {
        int unlockMode = mBiometricUnlockController.getMode();
        return unlockMode != BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING
                && unlockMode != BiometricUnlockController.MODE_WAKE_AND_UNLOCK;
    }

    private void updateStatus(AppearanceRegion[] appearanceRegions) {
        final int numStacks = appearanceRegions.length;
        final ArrayList<Rect> lightBarBounds = new ArrayList<>();

        for (int i = 0; i < numStacks; i++) {
            final AppearanceRegion ar = appearanceRegions[i];
            if (isLight(ar.getAppearance(), mStatusBarMode, APPEARANCE_LIGHT_STATUS_BARS)) {
                lightBarBounds.add(ar.getBounds());
            }
        }

        if (lightBarBounds.isEmpty()) {
            // If no one is light, all icons become white.
            mStatusBarIconController
                    .getTransitionsController()
                    .setIconsDark(false, animateChange());
        } else if (lightBarBounds.size() == numStacks) {
            // If all stacks are light, all icons get dark.
            mStatusBarIconController.setIconsDarkArea(null);
            mStatusBarIconController.getTransitionsController().setIconsDark(true, animateChange());
        } else {
            // Not the same for every stack, magic!
            mStatusBarIconController.setIconsDarkArea(lightBarBounds);
            mStatusBarIconController.getTransitionsController().setIconsDark(true, animateChange());
        }
    }

    private void updateNavigation() {
        if (mNavigationBarController != null
                && mNavigationBarController.supportsIconTintForNavMode(mNavigationMode)) {
            mNavigationBarController.setIconsDark(mNavigationLight, animateChange());
        }
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        reevaluate();
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println("LightBarController: ");
        pw.print(" mAppearance="); pw.println(ViewDebug.flagsToString(
                InsetsFlags.class, "appearance", mAppearance));
        final int numStacks = mAppearanceRegions.length;
        for (int i = 0; i < numStacks; i++) {
            final boolean isLight = isLight(mAppearanceRegions[i].getAppearance(), mStatusBarMode,
                    APPEARANCE_LIGHT_STATUS_BARS);
            pw.print(" stack #"); pw.print(i); pw.print(": ");
            pw.print(mAppearanceRegions[i].toString()); pw.print(" isLight="); pw.println(isLight);
        }

        pw.print(" mNavigationLight="); pw.println(mNavigationLight);
        pw.print(" mHasLightNavigationBar="); pw.println(mHasLightNavigationBar);
        pw.println();
        pw.print(" mStatusBarMode="); pw.print(mStatusBarMode);
        pw.print(" mNavigationBarMode="); pw.println(mNavigationBarMode);
        pw.println();
        pw.print(" mForceDarkForScrim="); pw.println(mForceDarkForScrim);
        pw.print(" mForceLightForScrim="); pw.println(mForceLightForScrim);
        pw.println();
        pw.print(" mQsCustomizing="); pw.println(mQsCustomizing);
        pw.print(" mQsExpanded="); pw.println(mQsExpanded);
        pw.print(" mBouncerVisible="); pw.println(mBouncerVisible);
        pw.print(" mGlobalActionsVisible="); pw.println(mGlobalActionsVisible);
        pw.print(" mDirectReplying="); pw.println(mDirectReplying);
        pw.print(" mNavbarColorManagedByIme="); pw.println(mNavbarColorManagedByIme);
        pw.println();
        pw.println(" Recent Calculation Logs:");
        pw.print("   "); pw.println(mLastSetScrimStateLog);
        pw.print("   "); pw.println(mLastNavigationBarAppearanceChangedLog);

        pw.println();

        LightBarTransitionsController transitionsController =
                mStatusBarIconController.getTransitionsController();
        if (transitionsController != null) {
            pw.println(" StatusBarTransitionsController:");
            transitionsController.dump(pw, args);
            pw.println();
        }

        if (mNavigationBarController != null) {
            pw.println(" NavigationBarTransitionsController:");
            mNavigationBarController.dump(pw, args);
            pw.println();
        }
    }

    /** Injectable factory for creating a {@link LightBarControllerImpl}. */
    @AssistedFactory
    @FunctionalInterface
    public interface Factory {

        /** Creates a {@link LightBarControllerImpl}. */
        LightBarControllerImpl create(
                int displayId,
                CoroutineScope coroutineScope,
                DarkIconDispatcher darkIconDispatcher,
                StatusBarModePerDisplayRepository statusBarModePerDisplayRepository);
    }

    public static class LegacyFactory implements LightBarController.Factory {

        private final Factory mFactory;
        private final CoroutineScope mApplicationScope;
        private final DarkIconDispatcherStore mDarkIconDispatcherStore;
        private final StatusBarModeRepositoryStore mStatusBarModeRepositoryStore;

        @Inject
        public LegacyFactory(
                LightBarControllerImpl.Factory factory,
                @Application CoroutineScope applicationScope,
                DarkIconDispatcherStore darkIconDispatcherStore,
                StatusBarModeRepositoryStore statusBarModeRepositoryStore) {
            mFactory = factory;
            mApplicationScope = applicationScope;
            mDarkIconDispatcherStore = darkIconDispatcherStore;
            mStatusBarModeRepositoryStore = statusBarModeRepositoryStore;
        }

        @NonNull
        @Override
        public LightBarController create(@NonNull Context context) {
            // TODO: b/380394368 - Make sure correct per display instances are used.
            LightBarControllerImpl lightBarController = mFactory.create(
                    context.getDisplayId(),
                    mApplicationScope,
                    mDarkIconDispatcherStore.getDefaultDisplay(),
                    mStatusBarModeRepositoryStore.getDefaultDisplay()
            );
            // Calling start() manually to keep the legacy behavior. Before, LightBarControllerImpl
            // was doing work in the constructor, which moved to start().
            lightBarController.start();
            return lightBarController;
        }
    }
}
