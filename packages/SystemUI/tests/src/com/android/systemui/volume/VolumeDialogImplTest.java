/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.volume;

import static android.media.AudioManager.RINGER_MODE_NORMAL;
import static android.media.AudioManager.RINGER_MODE_SILENT;
import static android.media.AudioManager.RINGER_MODE_VIBRATE;

import static com.android.systemui.Flags.FLAG_HAPTIC_VOLUME_SLIDER;
import static com.android.systemui.volume.Events.DISMISS_REASON_UNKNOWN;
import static com.android.systemui.volume.Events.SHOW_REASON_UNKNOWN;
import static com.android.systemui.volume.VolumeDialogControllerImpl.STREAMS;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNotSame;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assume.assumeNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.KeyguardManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.os.SystemClock;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.provider.Settings;
import android.testing.TestableLooper;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.ImageButton;
import android.widget.SeekBar;

import androidx.test.core.view.MotionEventBuilder;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.systemui.Prefs;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.AnimatorTestRule;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.media.dialog.MediaOutputDialogManager;
import com.android.systemui.plugins.VolumeDialogController;
import com.android.systemui.plugins.VolumeDialogController.State;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.DevicePostureController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.FakeConfigurationController;
import com.android.systemui.util.settings.FakeSettings;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.volume.domain.interactor.VolumePanelNavigationInteractor;
import com.android.systemui.volume.panel.shared.flag.VolumePanelFlag;
import com.android.systemui.volume.ui.binder.VolumeDialogMenuIconBinder;
import com.android.systemui.volume.ui.navigation.VolumeNavigator;

import dagger.Lazy;

import junit.framework.Assert;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.function.Predicate;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class VolumeDialogImplTest extends SysuiTestCase {
    VolumeDialogImpl mDialog;
    View mActiveRinger;
    View mDrawerContainer;
    View mDrawerVibrate;
    View mDrawerMute;
    View mDrawerNormal;
    ViewGroup mDialogRowsView;
    CaptionsToggleImageButton mODICaptionsIcon;
    private TestableLooper mTestableLooper;
    private ConfigurationController mConfigurationController;
    private int mOriginalOrientation;

    private static final String TAG = "VolumeDialogImplTest";

    @Mock
    VolumeDialogController mVolumeDialogController;
    @Mock
    KeyguardManager mKeyguard;
    @Mock
    AccessibilityManagerWrapper mAccessibilityMgr;
    @Mock
    DeviceProvisionedController mDeviceProvisionedController;
    @Mock
    MediaOutputDialogManager mMediaOutputDialogManager;
    @Mock
    InteractionJankMonitor mInteractionJankMonitor;
    @Mock
    private DumpManager mDumpManager;
    @Mock CsdWarningDialog mCsdWarningDialog;
    @Mock
    DevicePostureController mPostureController;
    @Mock
    private Lazy<SecureSettings> mLazySecureSettings;
    @Mock
    private VolumePanelNavigationInteractor mVolumePanelNavigationInteractor;
    @Mock
    private VolumeNavigator mVolumeNavigator;
    @Mock
    private VolumeDialogMenuIconBinder mVolumeDialogMenuIconBinder;
    @Mock
    private VolumePanelFlag mVolumePanelFlag;

    private final CsdWarningDialog.Factory mCsdWarningDialogFactory =
            new CsdWarningDialog.Factory() {
        @Override
        public CsdWarningDialog create(int warningType, Runnable onCleanup) {
            return mCsdWarningDialog;
        }
    };
    @Mock
    private VibratorHelper mVibratorHelper;

    private int mLongestHideShowAnimationDuration = 250;
    private FakeSettings mSecureSettings;

    @Rule
    public final AnimatorTestRule mAnimatorTestRule = new AnimatorTestRule(this);

   @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        getContext().addMockSystemService(KeyguardManager.class, mKeyguard);

        mTestableLooper = TestableLooper.get(this);
        allowTestableLooperAsMainThread();

        when(mPostureController.getDevicePosture())
                .thenReturn(DevicePostureController.DEVICE_POSTURE_CLOSED);

        int hideDialogDuration = mContext.getResources()
                .getInteger(R.integer.config_dialogHideAnimationDurationMs);
        int showDialogDuration = mContext.getResources()
                .getInteger(R.integer.config_dialogShowAnimationDurationMs);

        mLongestHideShowAnimationDuration = Math.max(hideDialogDuration, showDialogDuration);

        mOriginalOrientation = mContext.getResources().getConfiguration().orientation;

        mConfigurationController = new FakeConfigurationController();

        mSecureSettings = new FakeSettings();

        when(mLazySecureSettings.get()).thenReturn(mSecureSettings);

        when(mVibratorHelper.getPrimitiveDurations(anyInt())).thenReturn(new int[]{0});

        mDialog = new VolumeDialogImpl(
                getContext(),
                mVolumeDialogController,
                mAccessibilityMgr,
                mDeviceProvisionedController,
                mConfigurationController,
                mMediaOutputDialogManager,
                mInteractionJankMonitor,
                mVolumePanelNavigationInteractor,
                mVolumeNavigator,
                false,
                mCsdWarningDialogFactory,
                mPostureController,
                mTestableLooper.getLooper(),
                mVolumePanelFlag,
                mDumpManager,
                mLazySecureSettings,
                mVibratorHelper,
                mVolumeDialogMenuIconBinder,
                new FakeSystemClock());
        mDialog.init(0, null);
        State state = createShellState();
        mDialog.onStateChangedH(state);

        mActiveRinger = mDialog.getDialogView().findViewById(
                R.id.volume_new_ringer_active_icon_container);
        mDrawerContainer = mDialog.getDialogView().findViewById(R.id.volume_drawer_container);

        // Drawer is not always available, e.g. on TVs
        if (mDrawerContainer != null) {
            mDrawerVibrate = mDrawerContainer.findViewById(R.id.volume_drawer_vibrate);
            mDrawerMute = mDrawerContainer.findViewById(R.id.volume_drawer_mute);
            mDrawerNormal = mDrawerContainer.findViewById(R.id.volume_drawer_normal);
        }
        mODICaptionsIcon = mDialog.getDialogView().findViewById(R.id.odi_captions_icon);

        mDialogRowsView = mDialog.getDialogView().findViewById(R.id.volume_dialog_rows);

        Prefs.putInt(mContext,
                Prefs.Key.SEEN_RINGER_GUIDANCE_COUNT,
                VolumePrefs.SHOW_RINGER_TOAST_COUNT + 1);

        Prefs.putBoolean(mContext, Prefs.Key.HAS_SEEN_ODI_CAPTIONS_TOOLTIP, false);
    }

    private void assumeHasDrawer() {
        assumeNotNull("Layout does not contain drawer", mDrawerContainer);
    }

    private State createShellState() {
        State state = new VolumeDialogController.State();
        for (int i = AudioManager.STREAM_VOICE_CALL; i <= AudioManager.STREAM_ACCESSIBILITY; i++) {
            VolumeDialogController.StreamState ss = new VolumeDialogController.StreamState();
            ss.name = STREAMS.get(i);
            ss.level = 1;
            ss.levelMin = 0;
            ss.levelMax = 25;
            state.states.append(i, ss);
        }
        return state;
    }

    private void navigateViews(View view, Predicate<View> condition) {
        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                navigateViews(viewGroup.getChildAt(i), condition);
            }
        } else {
            String resourceName = null;
            try {
                resourceName = getContext().getResources().getResourceName(view.getId());
            } catch (Exception e) {}
            assertTrue("View " + resourceName != null ? resourceName : view.getId()
                    + " failed test", condition.test(view));
        }
    }

    @Test
    @DisableFlags(FLAG_HAPTIC_VOLUME_SLIDER)
    public void addSliderHaptics_withHapticsDisabled_doesNotDeliverOnProgressChangedHaptics() {
        // GIVEN that the slider haptics flag is disabled and we try to add haptics to volume rows
        mDialog.addSliderHapticsToRows();

        // WHEN haptics try to be delivered to a volume stream
        boolean canDeliverHaptics =
                mDialog.canDeliverProgressHapticsToStream(AudioSystem.STREAM_MUSIC, true, 50);

        // THEN the result is that haptics are not successfully delivered
        assertFalse(canDeliverHaptics);
    }

    @Test
    @EnableFlags(FLAG_HAPTIC_VOLUME_SLIDER)
    public void addSliderHaptics_withHapticsEnabled_canDeliverOnProgressChangedHaptics() {
        // GIVEN that the slider haptics flag is enabled and we try to add haptics to volume rows
        mDialog.addSliderHapticsToRows();

        // WHEN haptics try to be delivered to a volume stream
        boolean canDeliverHaptics =
                mDialog.canDeliverProgressHapticsToStream(AudioSystem.STREAM_MUSIC, true, 50);

        // THEN the result is that haptics are successfully delivered
        assertTrue(canDeliverHaptics);
    }

    @Test
    public void testComputeTimeout() {
        Mockito.reset(mAccessibilityMgr);
        mDialog.rescheduleTimeoutH();
        verify(mAccessibilityMgr).getRecommendedTimeoutMillis(
                VolumeDialogImpl.DIALOG_TIMEOUT_MILLIS,
                AccessibilityManager.FLAG_CONTENT_CONTROLS);
    }

    @Test
    public void testSetTimeoutValue_ComputeTimeout() {
        mSecureSettings.putInt(Settings.Secure.VOLUME_DIALOG_DISMISS_TIMEOUT, 7000);
        Mockito.reset(mAccessibilityMgr);
        mDialog.init(0, null);
        mDialog.rescheduleTimeoutH();
        verify(mAccessibilityMgr).getRecommendedTimeoutMillis(
                7000,
                AccessibilityManager.FLAG_CONTENT_CONTROLS);
    }

    @Test
    public void testComputeTimeout_tooltip() {
        Mockito.reset(mAccessibilityMgr);
        mDialog.showCaptionsTooltip();
        verify(mAccessibilityMgr).getRecommendedTimeoutMillis(
                VolumeDialogImpl.DIALOG_ODI_CAPTIONS_TOOLTIP_TIMEOUT_MILLIS,
                AccessibilityManager.FLAG_CONTENT_CONTROLS
                | AccessibilityManager.FLAG_CONTENT_TEXT);
    }

    @Test
    public void testComputeTimeout_withHovering() {
        Mockito.reset(mAccessibilityMgr);
        View dialog = mDialog.getDialogView();
        long uptimeMillis = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(uptimeMillis, uptimeMillis,
                MotionEvent.ACTION_HOVER_ENTER, 0, 0, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        dialog.dispatchGenericMotionEvent(event);
        event.recycle();
        verify(mAccessibilityMgr).getRecommendedTimeoutMillis(
                VolumeDialogImpl.DIALOG_HOVERING_TIMEOUT_MILLIS,
                AccessibilityManager.FLAG_CONTENT_CONTROLS);
    }

    @Test
    public void testComputeTimeout_withSafetyWarningOn() {
        Mockito.reset(mAccessibilityMgr);
        ArgumentCaptor<VolumeDialogController.Callbacks> controllerCallbackCapture =
                ArgumentCaptor.forClass(VolumeDialogController.Callbacks.class);
        verify(mVolumeDialogController).addCallback(controllerCallbackCapture.capture(), any());
        VolumeDialogController.Callbacks callbacks = controllerCallbackCapture.getValue();

        callbacks.onShowSafetyWarning(AudioManager.FLAG_SHOW_UI);
        verify(mAccessibilityMgr).getRecommendedTimeoutMillis(
                VolumeDialogImpl.DIALOG_SAFETYWARNING_TIMEOUT_MILLIS,
                AccessibilityManager.FLAG_CONTENT_TEXT
                        | AccessibilityManager.FLAG_CONTENT_CONTROLS);
    }

    @Test
    public void testComputeTimeout_standard() {
        Mockito.reset(mAccessibilityMgr);
        mDialog.tryToRemoveCaptionsTooltip();
        mDialog.rescheduleTimeoutH();
        verify(mAccessibilityMgr).getRecommendedTimeoutMillis(
                VolumeDialogImpl.DIALOG_TIMEOUT_MILLIS,
                AccessibilityManager.FLAG_CONTENT_CONTROLS);
    }

    @Test
    public void testVibrateOnRingerChangedToVibrate() {
        final State initialSilentState = new State();
        initialSilentState.ringerModeInternal = AudioManager.RINGER_MODE_SILENT;

        final State vibrateState = new State();
        vibrateState.ringerModeInternal = AudioManager.RINGER_MODE_VIBRATE;

        // change ringer to silent
        mDialog.onStateChangedH(initialSilentState);

        // expected: shouldn't call vibrate yet
        verify(mVolumeDialogController, never()).vibrate(any());

        // changed ringer to vibrate
        mDialog.onStateChangedH(vibrateState);

        // expected: vibrate device
        verify(mVolumeDialogController).vibrate(any());
    }

    @Test
    public void testNoVibrateOnRingerInitialization() {
        final State initialUnsetState = new State();
        initialUnsetState.ringerModeInternal = -1;

        // ringer not initialized yet:
        mDialog.onStateChangedH(initialUnsetState);

        final State vibrateState = new State();
        vibrateState.ringerModeInternal = AudioManager.RINGER_MODE_VIBRATE;

        // changed ringer to vibrate
        mDialog.onStateChangedH(vibrateState);

        // shouldn't call vibrate
        verify(mVolumeDialogController, never()).vibrate(any());
    }

    @Test
    public void testSelectVibrateFromDrawer() {
        assumeHasDrawer();

        final State initialUnsetState = new State();
        initialUnsetState.ringerModeInternal = AudioManager.RINGER_MODE_NORMAL;
        mDialog.onStateChangedH(initialUnsetState);

        mActiveRinger.performClick();
        mDrawerVibrate.performClick();

        // Make sure we've actually changed the ringer mode.
        verify(mVolumeDialogController, times(1)).setRingerMode(
                AudioManager.RINGER_MODE_VIBRATE, false);
    }

    @Test
    public void testSelectMuteFromDrawer() {
        assumeHasDrawer();

        final State initialUnsetState = new State();
        initialUnsetState.ringerModeInternal = AudioManager.RINGER_MODE_NORMAL;
        mDialog.onStateChangedH(initialUnsetState);

        mActiveRinger.performClick();
        mDrawerMute.performClick();

        // Make sure we've actually changed the ringer mode.
        verify(mVolumeDialogController, times(1)).setRingerMode(
                AudioManager.RINGER_MODE_SILENT, false);
    }

    @Test
    public void testSelectNormalFromDrawer() {
        assumeHasDrawer();

        final State initialUnsetState = new State();
        initialUnsetState.ringerModeInternal = AudioManager.RINGER_MODE_VIBRATE;
        mDialog.onStateChangedH(initialUnsetState);

        mActiveRinger.performClick();
        mDrawerNormal.performClick();

        // Make sure we've actually changed the ringer mode.
        verify(mVolumeDialogController, times(1)).setRingerMode(
                AudioManager.RINGER_MODE_NORMAL, false);
    }

    /**
     * Ideally we would look at the ringer ImageView and check its assigned drawable id, but that
     * API does not exist. So we do the next best thing; we check the cached icon id.
     */
    @Test
    public void notificationVolumeSeparated_theRingerIconChangesToSpeakerIcon() {
        // already separated. assert icon is new based on res id
        assertEquals(mDialog.mVolumeRingerIconDrawableId,
                R.drawable.ic_speaker_on);
        assertEquals(mDialog.mVolumeRingerMuteIconDrawableId,
                R.drawable.ic_speaker_mute);
    }

    @Test
    public void testDialogDismissAnimation_notifyVisibleIsNotCalledBeforeAnimation() {
        mDialog.dismissH(DISMISS_REASON_UNKNOWN);
        // notifyVisible(false) should not be called immediately but only after the dismiss
        // animation has ended.
        verify(mVolumeDialogController, times(0)).notifyVisible(false);
        mDialog.getDialogView().animate().cancel();
    }

    @Test
    public void showCsdWarning_dialogShown() {
        mDialog.showCsdWarningH(AudioManager.CSD_WARNING_DOSE_REACHED_1X,
                CsdWarningDialog.NO_ACTION_TIMEOUT_MS);

        verify(mCsdWarningDialog).show();
    }

    @Test
    public void ifPortraitHalfOpen_drawVerticallyTop() {
        mDialog.onPostureChanged(DevicePostureController.DEVICE_POSTURE_HALF_OPENED);
        mTestableLooper.processAllMessages(); // let dismiss() finish

        setOrientation(Configuration.ORIENTATION_PORTRAIT);

        // Call show() to trigger layout updates before verifying position
        mDialog.show(SHOW_REASON_UNKNOWN);
        mTestableLooper.processAllMessages(); // let show() finish before assessing its side-effect

        int gravity = mDialog.getWindowGravity();
        assertEquals(Gravity.TOP, gravity & Gravity.VERTICAL_GRAVITY_MASK);
    }

    @Test
    public void ifPortraitAndOpen_drawCenterVertically() {
        mDialog.onPostureChanged(DevicePostureController.DEVICE_POSTURE_OPENED);
        mTestableLooper.processAllMessages(); // let dismiss() finish

        setOrientation(Configuration.ORIENTATION_PORTRAIT);

        mDialog.show(SHOW_REASON_UNKNOWN);
        mTestableLooper.processAllMessages(); // let show() finish before assessing its side-effect

        int gravity = mDialog.getWindowGravity();
        assertEquals(Gravity.CENTER_VERTICAL, gravity & Gravity.VERTICAL_GRAVITY_MASK);
    }

    @Test
    public void ifLandscapeAndHalfOpen_drawCenterVertically() {
        mDialog.onPostureChanged(DevicePostureController.DEVICE_POSTURE_HALF_OPENED);
        mTestableLooper.processAllMessages(); // let dismiss() finish

        setOrientation(Configuration.ORIENTATION_LANDSCAPE);

        mDialog.show(SHOW_REASON_UNKNOWN);
        mTestableLooper.processAllMessages(); // let show() finish before assessing its side-effect

        int gravity = mDialog.getWindowGravity();
        assertEquals(Gravity.CENTER_VERTICAL, gravity & Gravity.VERTICAL_GRAVITY_MASK);
    }

    @Test
    public void dialogInit_addsPostureControllerCallback() {
        // init is already called in setup
        verify(mPostureController).addCallback(any());
    }

    @Test
    public void dialogDestroy_removesPostureControllerCallback() {
        verify(mPostureController, never()).removeCallback(any());
        mDialog.destroy();
        verify(mPostureController).removeCallback(any());
    }

    private void setOrientation(int orientation) {
        Configuration config = new Configuration();
        config.orientation = orientation;
        if (mConfigurationController != null) {
            mConfigurationController.onConfigurationChanged(config);
        }
    }

    private enum RingerDrawerState {INIT, OPEN, CLOSE}

    @Test
    public void ringerModeNormal_ringerContainerDescribesItsState() {
        assertRingerContainerDescribesItsState(RINGER_MODE_NORMAL, RingerDrawerState.INIT);
    }

    @Test
    public void ringerModeSilent_ringerContainerDescribesItsState() {
        assertRingerContainerDescribesItsState(RINGER_MODE_SILENT, RingerDrawerState.INIT);
    }

    @Test
    public void ringerModeVibrate_ringerContainerDescribesItsState() {
        assertRingerContainerDescribesItsState(RINGER_MODE_VIBRATE, RingerDrawerState.INIT);
    }

    @Test
    public void ringerModeNormal_openDrawer_ringerContainerDescribesItsState() {
        assertRingerContainerDescribesItsState(RINGER_MODE_NORMAL, RingerDrawerState.OPEN);
    }

    @Test
    public void ringerModeSilent_openDrawer_ringerContainerDescribesItsState() {
        assertRingerContainerDescribesItsState(RINGER_MODE_SILENT, RingerDrawerState.OPEN);
    }

    @Test
    public void ringerModeVibrate_openDrawer_ringerContainerDescribesItsState() {
        assertRingerContainerDescribesItsState(RINGER_MODE_VIBRATE, RingerDrawerState.OPEN);
    }

    @Test
    public void ringerModeNormal_closeDrawer_ringerContainerDescribesItsState() {
        assertRingerContainerDescribesItsState(RINGER_MODE_NORMAL, RingerDrawerState.CLOSE);
    }

    @Test
    public void ringerModeSilent_closeDrawer_ringerContainerDescribesItsState() {
        assertRingerContainerDescribesItsState(RINGER_MODE_SILENT, RingerDrawerState.CLOSE);
    }

    @Test
    public void ringerModeVibrate_closeDrawer_ringerContainerDescribesItsState() {
        assertRingerContainerDescribesItsState(RINGER_MODE_VIBRATE, RingerDrawerState.CLOSE);
    }

    @Test
    public void testOnCaptionEnabledStateChanged_checkBeforeSwitchTrue_setCaptionsEnabledState() {
        ArgumentCaptor<VolumeDialogController.Callbacks> controllerCallbackCapture =
                ArgumentCaptor.forClass(VolumeDialogController.Callbacks.class);
        verify(mVolumeDialogController).addCallback(controllerCallbackCapture.capture(), any());
        VolumeDialogController.Callbacks callbacks = controllerCallbackCapture.getValue();

        callbacks.onCaptionEnabledStateChanged(true, true);
        verify(mVolumeDialogController).setCaptionsEnabledState(eq(false));
    }

    @Test
    public void testOnCaptionEnabledStateChanged_checkBeforeSwitchFalse_getCaptionsEnabledTrue() {
        ArgumentCaptor<VolumeDialogController.Callbacks> controllerCallbackCapture =
                ArgumentCaptor.forClass(VolumeDialogController.Callbacks.class);
        verify(mVolumeDialogController).addCallback(controllerCallbackCapture.capture(), any());
        VolumeDialogController.Callbacks callbacks = controllerCallbackCapture.getValue();

        callbacks.onCaptionEnabledStateChanged(true, false);
        assertTrue(mODICaptionsIcon.getCaptionsEnabled());
    }

    /**
     * The content description should include ringer state, and the correct one.
     */
    private void assertRingerContainerDescribesItsState(int ringerMode,
            RingerDrawerState drawerState) {
        assumeHasDrawer();

        State state = createShellState();
        state.ringerModeInternal = ringerMode;
        mDialog.onStateChangedH(state);

        mDialog.show(SHOW_REASON_UNKNOWN);

        if (drawerState != RingerDrawerState.INIT) {
            // in both cases we first open the drawer
            mDialog.toggleRingerDrawer(true);

            if (drawerState == RingerDrawerState.CLOSE) {
                mDialog.toggleRingerDrawer(false);
            }
        }

        String ringerContainerDescription = mDialog.getSelectedRingerContainerDescription();
        assumeNotNull(ringerContainerDescription);

        String ringerDescription = mContext.getString(
                mDialog.getStringDescriptionResourceForRingerMode(ringerMode));

        if (drawerState == RingerDrawerState.OPEN) {
            assertEquals(ringerDescription, ringerContainerDescription);
        } else {
            assertNotSame(ringerDescription, ringerContainerDescription);
            assertTrue(ringerContainerDescription.startsWith(ringerDescription));
        }
    }

    /**
     * The click should be a single tap, thus we inject a down and an up event.
     */
    @Test
    public void clickCaptionsButton_logsUiEvent() {
        UiEventLoggerFake logger = new UiEventLoggerFake();
        Events.sUiEventLogger = logger;
        MotionEvent down = MotionEventBuilder.newBuilder()
                .setAction(MotionEvent.ACTION_DOWN).build();
        MotionEvent up = MotionEventBuilder.newBuilder()
                .setAction(MotionEvent.ACTION_UP).build();

        mODICaptionsIcon.onTouchEvent(down);
        mODICaptionsIcon.onTouchEvent(up);
        mTestableLooper.moveTimeForward(300); // to confirm it was only a single tap
        mTestableLooper.processAllMessages();

        boolean foundCaptionLog = false;
        for (UiEventLoggerFake.FakeUiEvent event : logger.getLogs()) {
            if (event.eventId
                    == Events.VolumeDialogEvent.VOLUME_DIALOG_ODI_CAPTIONS_CLICKED.getId()) {
                foundCaptionLog = true;
                break;
            }
        }
        Assert.assertTrue("Did not log the captions button click.", foundCaptionLog);
    }

    /**
     * Pressing the small x button at top right dismisses the captions tooltip.
     */
    @Test
    public void dismissCaptionsTooltip_logsUiEvent() {
        UiEventLoggerFake logger = new UiEventLoggerFake();
        Events.sUiEventLogger = logger;
        mDialog.showCaptionsTooltip();
        assumeNotNull(mDialog.mODICaptionsTooltipView);
        View dismissButton = mDialog.mODICaptionsTooltipView.findViewById(R.id.dismiss);

        dismissButton.performClick();

        boolean foundCaptionLog = false;
        for (UiEventLoggerFake.FakeUiEvent event : logger.getLogs()) {
            if (event.eventId
                    == Events.VolumeDialogEvent.VOLUME_DIALOG_ODI_CAPTIONS_TOOLTIP_CLICKED.getId()
            ) {
                foundCaptionLog = true;
                break;
            }
        }
        Assert.assertTrue("Did not log the captions tooltip dismiss button click.",
                foundCaptionLog);
    }

    @Test
    public void volumeSliderTracksTouch_logsStartAndStopTrackingUiEvents() {
        UiEventLoggerFake logger = new UiEventLoggerFake();
        Events.sUiEventLogger = logger;

        mDialog.show(SHOW_REASON_UNKNOWN);
        mTestableLooper.processAllMessages();

        MotionEvent down = MotionEventBuilder.newBuilder()
                .setAction(MotionEvent.ACTION_DOWN).build();
        MotionEvent up = MotionEventBuilder.newBuilder().setAction(MotionEvent.ACTION_UP).build();

        SeekBar slider =
                mDialogRowsView.getChildAt(0).findViewById(R.id.volume_row_slider);
        slider.onTouchEvent(down);
        slider.onTouchEvent(up);
        mTestableLooper.moveTimeForward(300);
        mTestableLooper.processAllMessages();

        boolean foundStartTrackingTouch = false;
        boolean foundStopTrackingTouch = false;
        for (UiEventLoggerFake.FakeUiEvent event : logger.getLogs()) {
            if (event.eventId
                    == Events.VolumeDialogEvent.VOLUME_DIALOG_SLIDER_STARTED_TRACKING_TOUCH.getId()
            ) {
                foundStartTrackingTouch = true;
            }
            if (event.eventId
                    == Events.VolumeDialogEvent.VOLUME_DIALOG_SLIDER_STOPPED_TRACKING_TOUCH.getId()
            ) {
                foundStopTrackingTouch = true;
            }
        }
        Assert.assertTrue("Did not log the event of start tracking touch.",
                foundStartTrackingTouch);
        Assert.assertTrue("Did not log the event of stop tracking touch.",
                foundStopTrackingTouch);
    }

    @Test
    public void turnOnDnD_volumeSliderIconChangesToDnd() {
        State state = createShellState();
        state.zenMode = Settings.Global.ZEN_MODE_NO_INTERRUPTIONS;

        mDialog.onStateChangedH(state);
        mTestableLooper.processAllMessages();

        boolean foundDnDIcon = findDndIconAmongVolumeRows();
        assertTrue(foundDnDIcon);
    }

    @Test
    public void turnOffDnD_volumeSliderIconIsNotDnd() {
        State state = createShellState();
        state.zenMode = Settings.Global.ZEN_MODE_OFF;

        mDialog.onStateChangedH(state);
        mTestableLooper.processAllMessages();

        boolean foundDnDIcon = findDndIconAmongVolumeRows();
        assertFalse(foundDnDIcon);
    }

    /**
     * @return true if at least one volume row has the DND icon
     */
    private boolean findDndIconAmongVolumeRows() {
        ViewGroup volumeDialogRows = mDialog.getDialogView().findViewById(R.id.volume_dialog_rows);
        assumeNotNull(volumeDialogRows);
        Drawable expected =  getContext().getDrawable(com.android.internal.R.drawable.ic_qs_dnd);
        boolean foundDnDIcon = false;
        final int rowCount = volumeDialogRows.getChildCount();
        // we don't make assumptions about the position of the dnd row
        for (int i = 0; i < rowCount && !foundDnDIcon; i++) {
            View volumeRow = volumeDialogRows.getChildAt(i);
            ImageButton rowIcon = volumeRow.findViewById(R.id.volume_row_icon);
            assertNotNull(rowIcon);

            // VolumeDialogImpl changes tint and alpha in a private method, so we clear those here.
            rowIcon.setImageTintList(null);
            rowIcon.setAlpha(0xFF);

            Drawable actual = rowIcon.getDrawable();
            foundDnDIcon |= areDrawablesEqual(expected, actual);
        }
        return foundDnDIcon;
    }

    private boolean areDrawablesEqual(Drawable drawable1, Drawable drawable2) {
        int size = 100;
        Bitmap bm1 = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Bitmap bm2 = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);

        Canvas canvas1 = new Canvas(bm1);
        Canvas canvas2 = new Canvas(bm2);

        drawable1.setBounds(0, 0, size, size);
        drawable2.setBounds(0, 0, size, size);

        drawable1.draw(canvas1);
        drawable2.draw(canvas2);

        boolean areBitmapsEqual = areBitmapsEqual(bm1, bm2);
        bm1.recycle();
        bm2.recycle();
        return areBitmapsEqual;
    }

    private boolean areBitmapsEqual(Bitmap a, Bitmap b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        int w = a.getWidth();
        int h = a.getHeight();
        int[] aPix = new int[w * h];
        int[] bPix = new int[w * h];
        a.getPixels(aPix, 0, w, 0, 0, w, h);
        b.getPixels(bPix, 0, w, 0, 0, w, h);
        return Arrays.equals(aPix, bPix);
    }

    @After
    public void teardown() {
        // Detailed logs to track down timeout issues in b/299491332
        Log.d(TAG, "teardown: entered");
        setOrientation(mOriginalOrientation);
        Log.d(TAG, "teardown: after setOrientation");
        // Unclear why we used to do this, and it seems to be a source of flakes
        // mAnimatorTestRule.advanceTimeBy(mLongestHideShowAnimationDuration);
        Log.d(TAG, "teardown: skipped advanceTimeBy");
        mTestableLooper.moveTimeForward(mLongestHideShowAnimationDuration);
        Log.d(TAG, "teardown: after moveTimeForward");
        mTestableLooper.processAllMessages();
        Log.d(TAG, "teardown: after processAllMessages");
        reset(mPostureController);
        Log.d(TAG, "teardown: after reset");
        cleanUp(mDialog);
        Log.d(TAG, "teardown: after cleanUp");
    }

    private void cleanUp(VolumeDialogImpl dialog) {
        if (dialog != null) {
            dialog.clearInternalHandlerAfterTest();
        }
    }

/*
    @Test
    public void testContentDescriptions() {
        mDialog.show(SHOW_REASON_UNKNOWN);
        ViewGroup dialog = mDialog.getDialogView();

        navigateViews(dialog, view -> {
            if (view instanceof ImageView) {
                return !TextUtils.isEmpty(view.getContentDescription());
            } else {
                return true;
            }
        });

        mDialog.dismiss(DISMISS_REASON_UNKNOWN);
    }

    @Test
    public void testNoDuplicationOfParentState() {
        mDialog.show(SHOW_REASON_UNKNOWN);
        ViewGroup dialog = mDialog.getDialogView();

        navigateViews(dialog, view -> !view.isDuplicateParentStateEnabled());

        mDialog.dismiss(DISMISS_REASON_UNKNOWN);
    }

    @Test
    public void testNoClickableViewGroups() {
        mDialog.show(SHOW_REASON_UNKNOWN);
        ViewGroup dialog = mDialog.getDialogView();

        navigateViews(dialog, view -> {
            if (view instanceof ViewGroup) {
                return !view.isClickable();
            } else {
                return true;
            }
        });

        mDialog.dismiss(DISMISS_REASON_UNKNOWN);
    }

    @Test
    public void testTristateToggle_withVibrator() {
        when(mController.hasVibrator()).thenReturn(true);

        State state = createShellState();
        state.ringerModeInternal = RINGER_MODE_NORMAL;
        mDialog.onStateChangedH(state);

        mDialog.show(SHOW_REASON_UNKNOWN);
        ViewGroup dialog = mDialog.getDialogView();

        // click once, verify updates to vibrate
        dialog.findViewById(R.id.ringer_icon).performClick();
        verify(mController, times(1)).setRingerMode(RINGER_MODE_VIBRATE, false);

        // fake the update back to the dialog with the new ringer mode
        state = createShellState();
        state.ringerModeInternal = RINGER_MODE_VIBRATE;
        mDialog.onStateChangedH(state);

        // click once, verify updates to silent
        dialog.findViewById(R.id.ringer_icon).performClick();
        verify(mController, times(1)).setRingerMode(RINGER_MODE_SILENT, false);
        verify(mController, times(1)).setStreamVolume(STREAM_RING, 0);

        // fake the update back to the dialog with the new ringer mode
        state = createShellState();
        state.states.get(STREAM_RING).level = 0;
        state.ringerModeInternal = RINGER_MODE_SILENT;
        mDialog.onStateChangedH(state);

        // click once, verify updates to normal
        dialog.findViewById(R.id.ringer_icon).performClick();
        verify(mController, times(1)).setRingerMode(RINGER_MODE_NORMAL, false);
        verify(mController, times(1)).setStreamVolume(STREAM_RING, 0);
    }

    @Test
    public void testTristateToggle_withoutVibrator() {
        when(mController.hasVibrator()).thenReturn(false);

        State state = createShellState();
        state.ringerModeInternal = RINGER_MODE_NORMAL;
        mDialog.onStateChangedH(state);

        mDialog.show(SHOW_REASON_UNKNOWN);
        ViewGroup dialog = mDialog.getDialogView();

        // click once, verify updates to silent
        dialog.findViewById(R.id.ringer_icon).performClick();
        verify(mController, times(1)).setRingerMode(RINGER_MODE_SILENT, false);
        verify(mController, times(1)).setStreamVolume(STREAM_RING, 0);

        // fake the update back to the dialog with the new ringer mode
        state = createShellState();
        state.states.get(STREAM_RING).level = 0;
        state.ringerModeInternal = RINGER_MODE_SILENT;
        mDialog.onStateChangedH(state);

        // click once, verify updates to normal
        dialog.findViewById(R.id.ringer_icon).performClick();
        verify(mController, times(1)).setRingerMode(RINGER_MODE_NORMAL, false);
        verify(mController, times(1)).setStreamVolume(STREAM_RING, 0);
    }
    */
}
