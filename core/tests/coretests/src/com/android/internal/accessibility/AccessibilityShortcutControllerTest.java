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

package com.android.internal.accessibility;

import static android.provider.Settings.Secure.ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN;
import static android.provider.Settings.Secure.ACCESSIBILITY_SHORTCUT_ON_LOCK_SCREEN;
import static android.provider.Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE;
import static android.view.accessibility.AccessibilityManager.ACCESSIBILITY_SHORTCUT_KEY;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.fail;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.media.Ringtone;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.Vibrator;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.test.mock.MockContentResolver;
import android.text.TextUtils;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IAccessibilityManager;
import android.widget.Toast;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;
import com.android.internal.accessibility.AccessibilityShortcutController.FrameworkObjectProvider;
import com.android.internal.util.test.FakeSettingsProvider;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;


@RunWith(AndroidJUnit4.class)
public class AccessibilityShortcutControllerTest {
    private static final String SERVICE_NAME_STRING = "fake.package/fake.service.name";
    private static final String SERVICE_NAME_SUMMARY = "Summary";
    private static final long VIBRATOR_PATTERN_1 = 100L;
    private static final long VIBRATOR_PATTERN_2 = 150L;
    private static final int[] VIBRATOR_PATTERN_INT = {(int) VIBRATOR_PATTERN_1,
            (int) VIBRATOR_PATTERN_2};
    private static final long[] VIBRATOR_PATTERN_LONG = {VIBRATOR_PATTERN_1, VIBRATOR_PATTERN_2};

    // Convenience values for enabling/disabling to make code more readable
    private static final int ENABLED_EXCEPT_LOCK_SCREEN = 1;
    private static final int ENABLED_INCLUDING_LOCK_SCREEN = 2;

    private @Mock Context mContext;
    private @Mock FrameworkObjectProvider mFrameworkObjectProvider;
    private @Mock IAccessibilityManager mAccessibilityManagerService;
    private @Mock Handler mHandler;
    private @Mock AlertDialog.Builder mAlertDialogBuilder;
    private @Mock AlertDialog mAlertDialog;
    private @Mock AccessibilityServiceInfo mServiceInfo;
    private @Mock Resources mResources;
    private @Mock Toast mToast;
    private @Mock Vibrator mVibrator;
    private @Mock ApplicationInfo mApplicationInfo;
    private @Mock PackageManager mPackageManager;
    private @Mock TextToSpeech mTextToSpeech;
    private @Mock Voice mVoice;
    private @Mock Ringtone mRingtone;

    private MockContentResolver mContentResolver;
    private WindowManager.LayoutParams mLayoutParams = new WindowManager.LayoutParams();

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mVibrator.hasVibrator()).thenReturn(true);

        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getApplicationInfo()).thenReturn(mApplicationInfo);
        when(mContext.getSystemService(Context.VIBRATOR_SERVICE)).thenReturn(mVibrator);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);

        // We're not checking the text. Just prevent us crashing when getting text.
        when(mPackageManager.getText(any(), anyInt(), any())).thenReturn("text");

        mContentResolver = new MockContentResolver(mContext);
        mContentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        FakeSettingsProvider.clearSettingsProvider();
        when(mContext.getContentResolver()).thenReturn(mContentResolver);

        when(mAccessibilityManagerService.getInstalledAccessibilityServiceList(anyInt()))
                .thenReturn(Collections.singletonList(mServiceInfo));

        // Use the extra level of indirection in the object to mock framework objects
        AccessibilityManager accessibilityManager =
                new AccessibilityManager(mHandler, mAccessibilityManagerService, 0);
        when(mFrameworkObjectProvider.getAccessibilityManagerInstance(mContext))
                .thenReturn(accessibilityManager);
        when(mFrameworkObjectProvider.getAlertDialogBuilder(mContext))
                .thenReturn(mAlertDialogBuilder);
        when(mFrameworkObjectProvider.makeToastFromText(eq(mContext), anyObject(), anyInt()))
                .thenReturn(mToast);
        when(mFrameworkObjectProvider.getSystemUiContext()).thenReturn(mContext);
        when(mFrameworkObjectProvider.getTextToSpeech(eq(mContext), any()))
                .thenReturn(mTextToSpeech);
        when(mFrameworkObjectProvider.getRingtone(eq(mContext), any())).thenReturn(mRingtone);

        when(mResources.getString(anyInt())).thenReturn("Howdy %s");
        when(mResources.getString(R.string.config_defaultAccessibilityService)).thenReturn(null);
        when(mResources.getIntArray(anyInt())).thenReturn(VIBRATOR_PATTERN_INT);

        ResolveInfo resolveInfo = mock(ResolveInfo.class);
        resolveInfo.serviceInfo = mock(ServiceInfo.class);
        resolveInfo.serviceInfo.applicationInfo = mApplicationInfo;
        when(resolveInfo.loadLabel(anyObject())).thenReturn("Service name");
        when(mServiceInfo.getResolveInfo()).thenReturn(resolveInfo);
        when(mServiceInfo.getComponentName())
                .thenReturn(ComponentName.unflattenFromString(SERVICE_NAME_STRING));
        when(mServiceInfo.loadSummary(any())).thenReturn(SERVICE_NAME_SUMMARY);

        when(mAlertDialogBuilder.setTitle(anyInt())).thenReturn(mAlertDialogBuilder);
        when(mAlertDialogBuilder.setCancelable(anyBoolean())).thenReturn(mAlertDialogBuilder);
        when(mAlertDialogBuilder.setMessage(anyObject())).thenReturn(mAlertDialogBuilder);
        when(mAlertDialogBuilder.setPositiveButton(anyInt(), anyObject()))
                .thenReturn(mAlertDialogBuilder);
        when(mAlertDialogBuilder.setNegativeButton(anyInt(), anyObject()))
                .thenReturn(mAlertDialogBuilder);
        when(mAlertDialogBuilder.setOnCancelListener(anyObject())).thenReturn(mAlertDialogBuilder);
        when(mAlertDialogBuilder.create()).thenReturn(mAlertDialog);

        mLayoutParams.privateFlags = 0;
        when(mToast.getWindowParams()).thenReturn(mLayoutParams);

        Window window = mock(Window.class);
        // Initialize the mWindowAttributes field which was not properly initialized during mock
        // creation.
        try {
            Field field = Window.class.getDeclaredField("mWindowAttributes");
            field.setAccessible(true);
            field.set(window, new WindowManager.LayoutParams());
        } catch (Exception e) {
            throw new RuntimeException("Unable to set mWindowAttributes", e);
        }
        when(mAlertDialog.getWindow()).thenReturn(window);

        when(mTextToSpeech.getVoice()).thenReturn(mVoice);
    }

    @AfterClass
    public static void cleanUpSettingsProvider() {
        FakeSettingsProvider.clearSettingsProvider();
    }

    @Test
    public void testShortcutAvailable_enabledButNoServiceWhenCreated_shouldReturnFalse()
            throws Exception {
        configureNoShortcutService();
        configureShortcutEnabled(ENABLED_EXCEPT_LOCK_SCREEN);
        assertFalse(getController().isAccessibilityShortcutAvailable(false));
    }

    @Test
    public void testShortcutAvailable_enabledWithValidServiceWhenCreated_shouldReturnTrue()
            throws Exception {
        configureValidShortcutService();
        configureShortcutEnabled(ENABLED_EXCEPT_LOCK_SCREEN);
        assertTrue(getController().isAccessibilityShortcutAvailable(false));
    }

    @Test
    public void testShortcutAvailable_onLockScreenButDisabledThere_shouldReturnFalse()
            throws Exception {
        configureValidShortcutService();
        configureShortcutEnabled(ENABLED_EXCEPT_LOCK_SCREEN);
        assertFalse(getController().isAccessibilityShortcutAvailable(true));
    }

    @Test
    public void testShortcutAvailable_onLockScreenAndEnabledThere_shouldReturnTrue()
            throws Exception {
        configureValidShortcutService();
        configureShortcutEnabled(ENABLED_INCLUDING_LOCK_SCREEN);
        assertTrue(getController().isAccessibilityShortcutAvailable(true));
    }

    @Test
    public void testShortcutAvailable_onLockScreenAndLockScreenPreferenceUnset() throws Exception {
        // When the user hasn't specified a lock screen preference, we allow from the lock screen
        // as long as the user has agreed to enable the shortcut
        configureValidShortcutService();
        configureShortcutEnabled(ENABLED_INCLUDING_LOCK_SCREEN);
        Settings.Secure.putString(
                mContentResolver, ACCESSIBILITY_SHORTCUT_ON_LOCK_SCREEN, null);
        Settings.Secure.putInt(mContentResolver, ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, 0);
        assertFalse(getController().isAccessibilityShortcutAvailable(true));
        Settings.Secure.putInt(mContentResolver, ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, 1);
        assertTrue(getController().isAccessibilityShortcutAvailable(true));
    }

    @Test
    public void testShortcutAvailable_whenServiceIdBecomesNull_shouldReturnFalse()
            throws Exception {
        configureShortcutEnabled(ENABLED_EXCEPT_LOCK_SCREEN);
        configureValidShortcutService();
        AccessibilityShortcutController accessibilityShortcutController = getController();
        configureNoShortcutService();
        accessibilityShortcutController.onSettingsChanged();
        assertFalse(accessibilityShortcutController.isAccessibilityShortcutAvailable(false));
    }

    @Test
    public void testShortcutAvailable_whenServiceIdBecomesNonNull_shouldReturnTrue()
            throws Exception {
        configureShortcutEnabled(ENABLED_EXCEPT_LOCK_SCREEN);
        configureNoShortcutService();
        AccessibilityShortcutController accessibilityShortcutController = getController();
        configureValidShortcutService();
        accessibilityShortcutController.onSettingsChanged();
        assertTrue(accessibilityShortcutController.isAccessibilityShortcutAvailable(false));
    }

    @Test
    public void testShortcutAvailable_whenShortcutBecomesEnabled_shouldReturnTrue()
            throws Exception {
        configureValidShortcutService();
        AccessibilityShortcutController accessibilityShortcutController = getController();
        configureShortcutEnabled(ENABLED_EXCEPT_LOCK_SCREEN);
        accessibilityShortcutController.onSettingsChanged();
        assertTrue(accessibilityShortcutController.isAccessibilityShortcutAvailable(false));
    }

    @Test
    public void testShortcutAvailable_whenLockscreenBecomesDisabled_shouldReturnFalse()
            throws Exception {
        configureShortcutEnabled(ENABLED_INCLUDING_LOCK_SCREEN);
        configureValidShortcutService();
        AccessibilityShortcutController accessibilityShortcutController = getController();
        configureShortcutEnabled(ENABLED_EXCEPT_LOCK_SCREEN);
        accessibilityShortcutController.onSettingsChanged();
        assertFalse(accessibilityShortcutController.isAccessibilityShortcutAvailable(true));
    }

    @Test
    public void testShortcutAvailable_whenLockscreenBecomesEnabled_shouldReturnTrue()
            throws Exception {
        configureShortcutEnabled(ENABLED_EXCEPT_LOCK_SCREEN);
        configureValidShortcutService();
        AccessibilityShortcutController accessibilityShortcutController = getController();
        configureShortcutEnabled(ENABLED_INCLUDING_LOCK_SCREEN);
        accessibilityShortcutController.onSettingsChanged();
        assertTrue(accessibilityShortcutController.isAccessibilityShortcutAvailable(true));
    }

    @Test
    public void testOnAccessibilityShortcut_vibrates() {
        configureShortcutEnabled(ENABLED_EXCEPT_LOCK_SCREEN);
        AccessibilityShortcutController accessibilityShortcutController = getController();
        accessibilityShortcutController.performAccessibilityShortcut();
        verify(mVibrator).vibrate(aryEq(VIBRATOR_PATTERN_LONG), eq(-1), anyObject());
    }

    @Test
    public void testOnAccessibilityShortcut_firstTime_showsWarningDialog()
            throws Exception {
        configureShortcutEnabled(ENABLED_EXCEPT_LOCK_SCREEN);
        configureValidShortcutService();
        AccessibilityShortcutController accessibilityShortcutController = getController();
        Settings.Secure.putInt(mContentResolver, ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, 0);
        accessibilityShortcutController.performAccessibilityShortcut();

        assertEquals(1, Settings.Secure.getInt(
                mContentResolver, ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, 0));
        verify(mResources).getString(R.string.accessibility_shortcut_toogle_warning);
        verify(mAlertDialog).show();
        verify(mAccessibilityManagerService, atLeastOnce()).getInstalledAccessibilityServiceList(
                anyInt());
        verify(mAccessibilityManagerService, times(0)).performAccessibilityShortcut(null);
        verify(mFrameworkObjectProvider, times(0)).getTextToSpeech(any(), any());
    }

    @Test
    public void testOnAccessibilityShortcut_withDialogShowing_callsServer()
        throws Exception {
        configureShortcutEnabled(ENABLED_EXCEPT_LOCK_SCREEN);
        configureValidShortcutService();
        AccessibilityShortcutController accessibilityShortcutController = getController();
        Settings.Secure.putInt(mContentResolver, ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, 0);
        accessibilityShortcutController.performAccessibilityShortcut();
        accessibilityShortcutController.performAccessibilityShortcut();
        verify(mToast).show();
        // TODO(b/149408635): Reintroduce assertion
        // assertEquals(WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS,
        //        mLayoutParams.privateFlags
        //                & WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS);
        verify(mAccessibilityManagerService, times(1)).performAccessibilityShortcut(null);
    }

    @Test
    public void testOnAccessibilityShortcut_ifCanceledFirstTime_showsWarningDialog()
        throws Exception {
        configureShortcutEnabled(ENABLED_EXCEPT_LOCK_SCREEN);
        configureValidShortcutService();
        AccessibilityShortcutController accessibilityShortcutController = getController();
        Settings.Secure.putInt(mContentResolver, ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, 0);
        accessibilityShortcutController.performAccessibilityShortcut();
        ArgumentCaptor<AlertDialog.OnCancelListener> cancelListenerCaptor =
                ArgumentCaptor.forClass(AlertDialog.OnCancelListener.class);
        verify(mAlertDialogBuilder).setOnCancelListener(cancelListenerCaptor.capture());
        // Call the cancel callback
        cancelListenerCaptor.getValue().onCancel(null);

        accessibilityShortcutController.performAccessibilityShortcut();
        verify(mAlertDialog, times(2)).show();
    }

    @Test
    public void testClickingDisableButtonInDialog_shouldClearShortcutId() throws Exception {
        configureShortcutEnabled(ENABLED_EXCEPT_LOCK_SCREEN);
        configureValidShortcutService();
        Settings.Secure.putInt(mContentResolver, ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, 0);
        getController().performAccessibilityShortcut();

        ArgumentCaptor<DialogInterface.OnClickListener> captor =
                ArgumentCaptor.forClass(DialogInterface.OnClickListener.class);
        verify(mAlertDialogBuilder).setNegativeButton(eq(R.string.disable_accessibility_shortcut),
                captor.capture());
        // Call the button callback
        captor.getValue().onClick(null, 0);
        assertTrue(TextUtils.isEmpty(
                Settings.Secure.getString(mContentResolver, ACCESSIBILITY_SHORTCUT_TARGET_SERVICE)));
    }

    @Test
    public void testClickingLeaveOnButtonInDialog_shouldLeaveShortcutReady() throws Exception {
        configureShortcutEnabled(ENABLED_EXCEPT_LOCK_SCREEN);
        configureValidShortcutService();
        Settings.Secure.putInt(mContentResolver, ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, 0);
        getController().performAccessibilityShortcut();

        ArgumentCaptor<DialogInterface.OnClickListener> captor =
            ArgumentCaptor.forClass(DialogInterface.OnClickListener.class);
        verify(mAlertDialogBuilder).setPositiveButton(eq(R.string.leave_accessibility_shortcut_on),
            captor.capture());
        // Call the button callback, if one exists
        if (captor.getValue() != null) {
            captor.getValue().onClick(null, 0);
        }
        assertEquals(SERVICE_NAME_STRING,
                Settings.Secure.getString(mContentResolver, ACCESSIBILITY_SHORTCUT_TARGET_SERVICE));
        assertEquals(1, Settings.Secure.getInt(
            mContentResolver, ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN));
    }

    @Test
    public void testOnAccessibilityShortcut_afterDialogShown_shouldCallServer() throws Exception {
        configureShortcutEnabled(ENABLED_EXCEPT_LOCK_SCREEN);
        configureValidShortcutService();
        Settings.Secure.putInt(mContentResolver, ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, 1);
        getController().performAccessibilityShortcut();

        verifyZeroInteractions(mAlertDialogBuilder, mAlertDialog);
        verify(mToast).show();
        verify(mAccessibilityManagerService).performAccessibilityShortcut(null);
    }

    @Test
    public void getFrameworkFeatureMap_shouldBeNonNullAndUnmodifiable() {
        Map<ComponentName, AccessibilityShortcutController.ToggleableFrameworkFeatureInfo>
                frameworkFeatureMap =
                AccessibilityShortcutController.getFrameworkShortcutFeaturesMap();
        assertTrue("Framework features not supported", frameworkFeatureMap.size() > 0);

        try {
            frameworkFeatureMap.clear();
            fail("Framework feature map should be unmodifieable");
        } catch (UnsupportedOperationException e) {
            // Expected
        }
    }

    @Test
    public void testOnAccessibilityShortcut_forServiceWithNoSummary_doesNotCrash()
            throws Exception {
        configureShortcutEnabled(ENABLED_EXCEPT_LOCK_SCREEN);
        configureValidShortcutService();
        when(mServiceInfo.loadSummary(any())).thenReturn(null);
        Settings.Secure.putInt(mContentResolver, ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, 1);
        getController().performAccessibilityShortcut();
        verify(mAccessibilityManagerService).performAccessibilityShortcut(null);
    }

    @Test
    public void testOnAccessibilityShortcut_forFrameworkFeature_callsServiceWithNoToast()
            throws Exception {
        configureShortcutEnabled(ENABLED_EXCEPT_LOCK_SCREEN);
        configureFirstFrameworkFeature();
        Settings.Secure.putInt(mContentResolver, ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, 1);
        getController().performAccessibilityShortcut();

        verifyZeroInteractions(mToast);
        verify(mAccessibilityManagerService).performAccessibilityShortcut(null);
    }

    @Test
    public void testOnAccessibilityShortcut_sdkGreaterThanQ_reqA11yButton_callsServiceWithNoToast()
            throws Exception {
        configureShortcutEnabled(ENABLED_EXCEPT_LOCK_SCREEN);
        configureValidShortcutService();
        configureApplicationTargetSdkVersion(Build.VERSION_CODES.R);
        configureRequestAccessibilityButton();
        configureEnabledService();
        Settings.Secure.putInt(mContentResolver, ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, 1);
        getController().performAccessibilityShortcut();

        verifyZeroInteractions(mToast);
        verify(mAccessibilityManagerService).performAccessibilityShortcut(null);
    }

    @Test
    public void testOnAccessibilityShortcut_showsWarningDialog_shouldTtsSpokenPrompt()
            throws Exception {
        configureShortcutEnabled(ENABLED_EXCEPT_LOCK_SCREEN);
        configureValidShortcutService();
        configureTtsSpokenPromptEnabled();
        configureHandlerCallbackInvocation();
        AccessibilityShortcutController accessibilityShortcutController = getController();
        Settings.Secure.putInt(mContentResolver, ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, 0);
        accessibilityShortcutController.performAccessibilityShortcut();

        verify(mAlertDialog).show();
        ArgumentCaptor<TextToSpeech.OnInitListener> onInitCap = ArgumentCaptor.forClass(
                TextToSpeech.OnInitListener.class);
        verify(mFrameworkObjectProvider).getTextToSpeech(any(), onInitCap.capture());
        onInitCap.getValue().onInit(TextToSpeech.SUCCESS);
        verify(mTextToSpeech).speak(any(), eq(TextToSpeech.QUEUE_FLUSH), any(), any());
        ArgumentCaptor<DialogInterface.OnDismissListener> onDismissCap = ArgumentCaptor.forClass(
                DialogInterface.OnDismissListener.class);
        verify(mAlertDialog).setOnDismissListener(onDismissCap.capture());
        onDismissCap.getValue().onDismiss(mAlertDialog);
        verify(mTextToSpeech).shutdown();
        verify(mRingtone, times(0)).play();
    }

    @Test
    public void testOnAccessibilityShortcut_showsWarningDialog_ttsInitFail_noSpokenPrompt()
            throws Exception {
        configureShortcutEnabled(ENABLED_EXCEPT_LOCK_SCREEN);
        configureValidShortcutService();
        configureTtsSpokenPromptEnabled();
        configureHandlerCallbackInvocation();
        AccessibilityShortcutController accessibilityShortcutController = getController();
        Settings.Secure.putInt(mContentResolver, ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, 0);
        accessibilityShortcutController.performAccessibilityShortcut();

        verify(mAlertDialog).show();
        ArgumentCaptor<TextToSpeech.OnInitListener> onInitCap = ArgumentCaptor.forClass(
                TextToSpeech.OnInitListener.class);
        verify(mFrameworkObjectProvider).getTextToSpeech(any(), onInitCap.capture());
        onInitCap.getValue().onInit(TextToSpeech.ERROR);
        verify(mTextToSpeech, times(0)).speak(any(), anyInt(), any(), any());
        verify(mRingtone).play();
    }

    @Test
    public void testOnAccessibilityShortcut_showsWarningDialog_ttsLongTimeInit_retrySpoken()
            throws Exception {
        configureShortcutEnabled(ENABLED_EXCEPT_LOCK_SCREEN);
        configureValidShortcutService();
        configureTtsSpokenPromptEnabled();
        configureHandlerCallbackInvocation();
        AccessibilityShortcutController accessibilityShortcutController = getController();
        Settings.Secure.putInt(mContentResolver, ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, 0);
        Set<String> features = new HashSet<>();
        features.add(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
        doReturn(features, Collections.emptySet()).when(mVoice).getFeatures();
        doReturn(TextToSpeech.LANG_NOT_SUPPORTED, TextToSpeech.LANG_AVAILABLE)
                .when(mTextToSpeech).setLanguage(any());
        accessibilityShortcutController.performAccessibilityShortcut();

        verify(mAlertDialog).show();
        ArgumentCaptor<TextToSpeech.OnInitListener> onInitCap = ArgumentCaptor.forClass(
                TextToSpeech.OnInitListener.class);
        verify(mFrameworkObjectProvider).getTextToSpeech(any(), onInitCap.capture());
        onInitCap.getValue().onInit(TextToSpeech.SUCCESS);
        verify(mTextToSpeech).speak(any(), eq(TextToSpeech.QUEUE_FLUSH), any(), any());
        ArgumentCaptor<DialogInterface.OnDismissListener> onDismissCap = ArgumentCaptor.forClass(
                DialogInterface.OnDismissListener.class);
        verify(mAlertDialog).setOnDismissListener(onDismissCap.capture());
        onDismissCap.getValue().onDismiss(mAlertDialog);
        verify(mTextToSpeech).shutdown();
        verify(mRingtone, times(0)).play();
    }

    private void configureNoShortcutService() throws Exception {
        when(mAccessibilityManagerService
                .getAccessibilityShortcutTargets(ACCESSIBILITY_SHORTCUT_KEY))
                .thenReturn(Collections.emptyList());
        Settings.Secure.putString(mContentResolver, ACCESSIBILITY_SHORTCUT_TARGET_SERVICE, "");
    }

    private void configureValidShortcutService() throws Exception {
        when(mAccessibilityManagerService
                .getAccessibilityShortcutTargets(ACCESSIBILITY_SHORTCUT_KEY))
                .thenReturn(Collections.singletonList(SERVICE_NAME_STRING));
        Settings.Secure.putString(
                mContentResolver, ACCESSIBILITY_SHORTCUT_TARGET_SERVICE, SERVICE_NAME_STRING);
    }

    private void configureFirstFrameworkFeature() throws Exception {
        ComponentName featureComponentName =
                (ComponentName) AccessibilityShortcutController.getFrameworkShortcutFeaturesMap()
                        .keySet().toArray()[0];
        when(mAccessibilityManagerService
                .getAccessibilityShortcutTargets(ACCESSIBILITY_SHORTCUT_KEY))
                .thenReturn(Collections.singletonList(featureComponentName.flattenToString()));
        Settings.Secure.putString(mContentResolver, ACCESSIBILITY_SHORTCUT_TARGET_SERVICE,
                featureComponentName.flattenToString());
    }

    private void configureShortcutEnabled(int enabledValue) {
        final boolean lockscreen;

        switch (enabledValue) {
            case ENABLED_INCLUDING_LOCK_SCREEN:
                lockscreen = true;
                break;
            case ENABLED_EXCEPT_LOCK_SCREEN:
                lockscreen = false;
                break;
            default:
                throw new IllegalArgumentException();
        }

        Settings.Secure.putInt(
                mContentResolver, ACCESSIBILITY_SHORTCUT_ON_LOCK_SCREEN, lockscreen ? 1 : 0);
    }

    private void configureTtsSpokenPromptEnabled() {
        mServiceInfo.flags |= AccessibilityServiceInfo
                .FLAG_REQUEST_SHORTCUT_WARNING_DIALOG_SPOKEN_FEEDBACK;
    }

    private void configureRequestAccessibilityButton() {
        mServiceInfo.flags |= AccessibilityServiceInfo
                .FLAG_REQUEST_ACCESSIBILITY_BUTTON;
    }

    private void configureApplicationTargetSdkVersion(int versionCode) {
        mApplicationInfo.targetSdkVersion = versionCode;
    }

    private void configureHandlerCallbackInvocation() {
        doAnswer((InvocationOnMock invocation) -> {
            Message m = (Message) invocation.getArguments()[0];
            m.getCallback().run();
            return true;
        }).when(mHandler).sendMessageAtTime(any(), anyLong());
    }

    private void configureEnabledService() throws Exception {
        when(mAccessibilityManagerService.getEnabledAccessibilityServiceList(anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(mServiceInfo));
    }

    private AccessibilityShortcutController getController() {
        AccessibilityShortcutController accessibilityShortcutController =
                new AccessibilityShortcutController(mContext, mHandler, 0);
        accessibilityShortcutController.mFrameworkObjectProvider = mFrameworkObjectProvider;
        return accessibilityShortcutController;
    }
}
