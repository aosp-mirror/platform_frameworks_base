/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.internal.accessibility;

import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;

import static com.android.internal.util.ArrayUtils.convertToLongArray;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.util.function.pooled.PooledLambda;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * Class to help manage the accessibility shortcut
 */
public class AccessibilityShortcutController {
    private static final String TAG = "AccessibilityShortcutController";

    // Dummy component names for framework features
    public static final ComponentName COLOR_INVERSION_COMPONENT_NAME =
            new ComponentName("com.android.server.accessibility", "ColorInversion");
    public static final ComponentName DALTONIZER_COMPONENT_NAME =
            new ComponentName("com.android.server.accessibility", "Daltonizer");

    private static final AudioAttributes VIBRATION_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .build();
    private static Map<ComponentName, ToggleableFrameworkFeatureInfo> sFrameworkShortcutFeaturesMap;

    private final Context mContext;
    private final Handler mHandler;
    private AlertDialog mAlertDialog;
    private boolean mIsShortcutEnabled;
    private boolean mEnabledOnLockScreen;
    private int mUserId;

    // Visible for testing
    public FrameworkObjectProvider mFrameworkObjectProvider = new FrameworkObjectProvider();

    /**
     * Get the component name string for the service or feature currently assigned to the
     * accessiblity shortcut
     *
     * @param context A valid context
     * @param userId The user ID of interest
     * @return The flattened component name string of the service selected by the user, or the
     *         string for the default service if the user has not made a selection
     */
    public static String getTargetServiceComponentNameString(
            Context context, int userId) {
        final String currentShortcutServiceId = Settings.Secure.getStringForUser(
                context.getContentResolver(), Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE,
                userId);
        if (currentShortcutServiceId != null) {
            return currentShortcutServiceId;
        }
        return context.getString(R.string.config_defaultAccessibilityService);
    }

    /**
     * @return An immutable map from dummy component names to feature info for toggling a framework
     *         feature
     */
    public static Map<ComponentName, ToggleableFrameworkFeatureInfo>
        getFrameworkShortcutFeaturesMap() {
        if (sFrameworkShortcutFeaturesMap == null) {
            Map<ComponentName, ToggleableFrameworkFeatureInfo> featuresMap = new ArrayMap<>(2);
            featuresMap.put(COLOR_INVERSION_COMPONENT_NAME,
                    new ToggleableFrameworkFeatureInfo(
                            Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED,
                            "1" /* Value to enable */, "0" /* Value to disable */,
                            R.string.color_inversion_feature_name));
            featuresMap.put(DALTONIZER_COMPONENT_NAME,
                    new ToggleableFrameworkFeatureInfo(
                            Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED,
                            "1" /* Value to enable */, "0" /* Value to disable */,
                            R.string.color_correction_feature_name));
            sFrameworkShortcutFeaturesMap = Collections.unmodifiableMap(featuresMap);
        }
        return sFrameworkShortcutFeaturesMap;
    }

    public AccessibilityShortcutController(Context context, Handler handler, int initialUserId) {
        mContext = context;
        mHandler = handler;
        mUserId = initialUserId;

        // Keep track of state of shortcut settings
        final ContentObserver co = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange, Uri uri, int userId) {
                if (userId == mUserId) {
                    onSettingsChanged();
                }
            }
        };
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE),
                false, co, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_SHORTCUT_ENABLED),
                false, co, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_SHORTCUT_ON_LOCK_SCREEN),
                false, co, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN),
                false, co, UserHandle.USER_ALL);
        setCurrentUser(mUserId);
    }

    public void setCurrentUser(int currentUserId) {
        mUserId = currentUserId;
        onSettingsChanged();
    }

    /**
     * Check if the shortcut is available.
     *
     * @param onLockScreen Whether or not the phone is currently locked.
     *
     * @return {@code true} if the shortcut is available
     */
    public boolean isAccessibilityShortcutAvailable(boolean phoneLocked) {
        return mIsShortcutEnabled && (!phoneLocked || mEnabledOnLockScreen);
    }

    public void onSettingsChanged() {
        final boolean haveValidService =
                !TextUtils.isEmpty(getTargetServiceComponentNameString(mContext, mUserId));
        final ContentResolver cr = mContext.getContentResolver();
        final boolean enabled = Settings.Secure.getIntForUser(
                cr, Settings.Secure.ACCESSIBILITY_SHORTCUT_ENABLED, 1, mUserId) == 1;
        // Enable the shortcut from the lockscreen by default if the dialog has been shown
        final int dialogAlreadyShown = Settings.Secure.getIntForUser(
                cr, Settings.Secure.ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, 0, mUserId);
        mEnabledOnLockScreen = Settings.Secure.getIntForUser(
                cr, Settings.Secure.ACCESSIBILITY_SHORTCUT_ON_LOCK_SCREEN,
                dialogAlreadyShown, mUserId) == 1;
        mIsShortcutEnabled = enabled && haveValidService;
    }

    /**
     * Called when the accessibility shortcut is activated
     */
    public void performAccessibilityShortcut() {
        Slog.d(TAG, "Accessibility shortcut activated");
        final ContentResolver cr = mContext.getContentResolver();
        final int userId = ActivityManager.getCurrentUser();
        final int dialogAlreadyShown = Settings.Secure.getIntForUser(
                cr, Settings.Secure.ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, 0, userId);
        // Play a notification vibration
        Vibrator vibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        if ((vibrator != null) && vibrator.hasVibrator()) {
            // Don't check if haptics are disabled, as we need to alert the user that their
            // way of interacting with the phone may change if they activate the shortcut
            long[] vibePattern = convertToLongArray(
                    mContext.getResources().getIntArray(R.array.config_longPressVibePattern));
            vibrator.vibrate(vibePattern, -1, VIBRATION_ATTRIBUTES);
        }


        if (dialogAlreadyShown == 0) {
            // The first time, we show a warning rather than toggle the service to give the user a
            // chance to turn off this feature before stuff gets enabled.
            mAlertDialog = createShortcutWarningDialog(userId);
            if (mAlertDialog == null) {
                return;
            }
            if (!performTtsPrompt(mAlertDialog)) {
                playNotificationTone();
            }
            Window w = mAlertDialog.getWindow();
            WindowManager.LayoutParams attr = w.getAttributes();
            attr.type = TYPE_KEYGUARD_DIALOG;
            w.setAttributes(attr);
            mAlertDialog.show();
            Settings.Secure.putIntForUser(
                    cr, Settings.Secure.ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, 1, userId);
        } else {
            playNotificationTone();
            if (mAlertDialog != null) {
                mAlertDialog.dismiss();
                mAlertDialog = null;
            }

            // Show a toast alerting the user to what's happening
            final String serviceName = getShortcutFeatureDescription(false /* no summary */);
            if (serviceName == null) {
                Slog.e(TAG, "Accessibility shortcut set to invalid service");
                return;
            }
            // For accessibility services, show a toast explaining what we're doing.
            final AccessibilityServiceInfo serviceInfo = getInfoForTargetService();
            if (serviceInfo != null) {
                String toastMessageFormatString = mContext.getString(isServiceEnabled(serviceInfo)
                        ? R.string.accessibility_shortcut_disabling_service
                        : R.string.accessibility_shortcut_enabling_service);
                String toastMessage = String.format(toastMessageFormatString, serviceName);
                Toast warningToast = mFrameworkObjectProvider.makeToastFromText(
                        mContext, toastMessage, Toast.LENGTH_LONG);
                warningToast.getWindowParams().privateFlags |=
                        WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
                warningToast.show();
            }

            mFrameworkObjectProvider.getAccessibilityManagerInstance(mContext)
                    .performAccessibilityShortcut();
        }
    }

    private AlertDialog createShortcutWarningDialog(int userId) {
        final String serviceDescription = getShortcutFeatureDescription(true /* Include summary */);

        if (serviceDescription == null) {
            return null;
        }

        final String warningMessage = String.format(
                mContext.getString(R.string.accessibility_shortcut_toogle_warning),
                serviceDescription);
        final AlertDialog alertDialog = mFrameworkObjectProvider.getAlertDialogBuilder(
                // Use SystemUI context so we pick up any theme set in a vendor overlay
                mFrameworkObjectProvider.getSystemUiContext())
                .setTitle(R.string.accessibility_shortcut_warning_dialog_title)
                .setMessage(warningMessage)
                .setCancelable(false)
                .setPositiveButton(R.string.leave_accessibility_shortcut_on, null)
                .setNegativeButton(R.string.disable_accessibility_shortcut,
                        (DialogInterface d, int which) -> {
                            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                                    Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE, "",
                                    userId);
                        })
                .setOnCancelListener((DialogInterface d) -> {
                    // If canceled, treat as if the dialog has never been shown
                    Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, 0, userId);
                })
                .create();
        return alertDialog;
    }

    private AccessibilityServiceInfo getInfoForTargetService() {
        final String currentShortcutServiceString = getTargetServiceComponentNameString(
                mContext, UserHandle.USER_CURRENT);
        if (currentShortcutServiceString == null) {
            return null;
        }
        AccessibilityManager accessibilityManager =
                mFrameworkObjectProvider.getAccessibilityManagerInstance(mContext);
        return accessibilityManager.getInstalledServiceInfoWithComponentName(
                        ComponentName.unflattenFromString(currentShortcutServiceString));
    }

    private String getShortcutFeatureDescription(boolean includeSummary) {
        final String currentShortcutServiceString = getTargetServiceComponentNameString(
                mContext, UserHandle.USER_CURRENT);
        if (currentShortcutServiceString == null) {
            return null;
        }
        final ComponentName targetComponentName =
                ComponentName.unflattenFromString(currentShortcutServiceString);
        final ToggleableFrameworkFeatureInfo frameworkFeatureInfo =
                getFrameworkShortcutFeaturesMap().get(targetComponentName);
        if (frameworkFeatureInfo != null) {
            return frameworkFeatureInfo.getLabel(mContext);
        }
        final AccessibilityServiceInfo serviceInfo = mFrameworkObjectProvider
                .getAccessibilityManagerInstance(mContext).getInstalledServiceInfoWithComponentName(
                        targetComponentName);
        if (serviceInfo == null) {
            return null;
        }
        final PackageManager pm = mContext.getPackageManager();
        String label = serviceInfo.getResolveInfo().loadLabel(pm).toString();
        CharSequence summary = serviceInfo.loadSummary(pm);
        if (!includeSummary || TextUtils.isEmpty(summary)) {
            return label;
        }
        return String.format("%s\n%s", label, summary);
    }

    private boolean isServiceEnabled(AccessibilityServiceInfo serviceInfo) {
        AccessibilityManager accessibilityManager =
                mFrameworkObjectProvider.getAccessibilityManagerInstance(mContext);
        return accessibilityManager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK).contains(serviceInfo);
    }

    private boolean hasFeatureLeanback() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    private void playNotificationTone() {
        // Use USAGE_ASSISTANCE_ACCESSIBILITY for TVs to ensure that TVs play the ringtone as they
        // have less ways of providing feedback like vibration.
        final int audioAttributesUsage = hasFeatureLeanback()
                ? AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
                : AudioAttributes.USAGE_NOTIFICATION_EVENT;

        // Play a notification tone
        final Ringtone tone = mFrameworkObjectProvider.getRingtone(mContext,
                Settings.System.DEFAULT_NOTIFICATION_URI);
        if (tone != null) {
            tone.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(audioAttributesUsage)
                    .build());
            tone.play();
        }
    }

    private boolean performTtsPrompt(AlertDialog alertDialog) {
        final String serviceName = getShortcutFeatureDescription(false /* no summary */);
        final AccessibilityServiceInfo serviceInfo = getInfoForTargetService();
        if (TextUtils.isEmpty(serviceName) || serviceInfo == null) {
            return false;
        }
        if ((serviceInfo.flags & AccessibilityServiceInfo
                .FLAG_REQUEST_SHORTCUT_WARNING_DIALOG_SPOKEN_FEEDBACK) == 0) {
            return false;
        }
        final TtsPrompt tts = new TtsPrompt(serviceName);
        alertDialog.setOnDismissListener(dialog -> tts.dismiss());
        return true;
    }

    /**
     * Class to wrap TextToSpeech for shortcut dialog spoken feedback.
     */
    private class TtsPrompt implements TextToSpeech.OnInitListener {
        private final CharSequence mText;
        private boolean mDismiss;
        private TextToSpeech mTts;

        TtsPrompt(String serviceName) {
            mText = mContext.getString(R.string.accessibility_shortcut_spoken_feedback,
                    serviceName);
            mTts = mFrameworkObjectProvider.getTextToSpeech(mContext, this);
        }

        /**
         * Releases the resources used by the TextToSpeech, when dialog dismiss.
         */
        public void dismiss() {
            mDismiss = true;
            mHandler.sendMessage(PooledLambda.obtainMessage(TextToSpeech::shutdown, mTts));
        }

        @Override
        public void onInit(int status) {
            if (status != TextToSpeech.SUCCESS) {
                Slog.d(TAG, "Tts init fail, status=" + Integer.toString(status));
                playNotificationTone();
                return;
            }
            mHandler.sendMessage(PooledLambda.obtainMessage(TtsPrompt::play, this));
        }

        private void play() {
            if (mDismiss) {
                return;
            }
            int status = TextToSpeech.ERROR;
            if (setLanguage(Locale.getDefault())) {
                status = mTts.speak(mText, TextToSpeech.QUEUE_FLUSH, null, null);
            }
            if (status != TextToSpeech.SUCCESS) {
                Slog.d(TAG, "Tts play fail");
                playNotificationTone();
            }
        }

        /**
         * @return false if tts language is not available
         */
        private boolean setLanguage(final Locale locale) {
            int status = mTts.isLanguageAvailable(locale);
            if (status == TextToSpeech.LANG_MISSING_DATA
                    || status == TextToSpeech.LANG_NOT_SUPPORTED) {
                return false;
            }
            mTts.setLanguage(locale);
            Voice voice = mTts.getVoice();
            if (voice == null || (voice.getFeatures() != null && voice.getFeatures()
                    .contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))) {
                return false;
            }
            return true;
        }
    }

    /**
     * Immutable class to hold info about framework features that can be controlled by shortcut
     */
    public static class ToggleableFrameworkFeatureInfo {
        private final String mSettingKey;
        private final String mSettingOnValue;
        private final String mSettingOffValue;
        private final int mLabelStringResourceId;
        // These go to the settings wrapper
        private int mIconDrawableId;

        ToggleableFrameworkFeatureInfo(String settingKey, String settingOnValue,
                String settingOffValue, int labelStringResourceId) {
            mSettingKey = settingKey;
            mSettingOnValue = settingOnValue;
            mSettingOffValue = settingOffValue;
            mLabelStringResourceId = labelStringResourceId;
        }

        /**
         * @return The settings key to toggle between two values
         */
        public String getSettingKey() {
            return mSettingKey;
        }

        /**
         * @return The value to write to settings to turn the feature on
         */
        public String getSettingOnValue() {
            return mSettingOnValue;
        }

        /**
         * @return The value to write to settings to turn the feature off
         */
        public String getSettingOffValue() {
            return mSettingOffValue;
        }

        public String getLabel(Context context) {
            return context.getString(mLabelStringResourceId);
        }
    }

    // Class to allow mocking of static framework calls
    public static class FrameworkObjectProvider {
        public AccessibilityManager getAccessibilityManagerInstance(Context context) {
            return AccessibilityManager.getInstance(context);
        }

        public AlertDialog.Builder getAlertDialogBuilder(Context context) {
            return new AlertDialog.Builder(context);
        }

        public Toast makeToastFromText(Context context, CharSequence charSequence, int duration) {
            return Toast.makeText(context, charSequence, duration);
        }

        public Context getSystemUiContext() {
            return ActivityThread.currentActivityThread().getSystemUiContext();
        }

        /**
         * @param ctx A context for TextToSpeech
         * @param listener TextToSpeech initialization callback
         * @return TextToSpeech instance
         */
        public TextToSpeech getTextToSpeech(Context ctx, TextToSpeech.OnInitListener listener) {
            return new TextToSpeech(ctx, listener);
        }

        /**
         * @param ctx context for ringtone
         * @param uri ringtone uri
         * @return Ringtone instance
         */
        public Ringtone getRingtone(Context ctx, Uri uri) {
            return RingtoneManager.getRingtone(ctx, uri);
        }
    }
}
