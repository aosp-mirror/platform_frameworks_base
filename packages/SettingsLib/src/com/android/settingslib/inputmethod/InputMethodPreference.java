/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.inputmethod;

import static com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.Preference.OnPreferenceClickListener;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.R;
import com.android.settingslib.RestrictedLockUtilsInternal;
import com.android.settingslib.RestrictedSwitchPreference;

import java.text.Collator;
import java.util.List;

/**
 * Input method preference.
 *
 * This preference represents an IME. It is used for two purposes. 1) An instance with a switch
 * is used to enable or disable the IME. 2) An instance without a switch is used to invoke the
 * setting activity of the IME.
 */
public class InputMethodPreference extends RestrictedSwitchPreference implements OnPreferenceClickListener,
        OnPreferenceChangeListener {
    private static final String TAG = InputMethodPreference.class.getSimpleName();
    private static final String EMPTY_TEXT = "";
    private static final int NO_WIDGET = 0;

    public interface OnSavePreferenceListener {
        /**
         * Called when this preference needs to be saved its state.
         *
         * Note that this preference is non-persistent and needs explicitly to be saved its state.
         * Because changing one IME state may change other IMEs' state, this is a place to update
         * other IMEs' state as well.
         *
         * @param pref This preference.
         */
        void onSaveInputMethodPreference(InputMethodPreference pref);
    }

    private final InputMethodInfo mImi;
    private final boolean mHasPriorityInSorting;
    private final OnSavePreferenceListener mOnSaveListener;
    private final InputMethodSettingValuesWrapper mInputMethodSettingValues;
    private final boolean mIsAllowedByOrganization;

    private AlertDialog mDialog = null;

    /**
     * A preference entry of an input method.
     *
     * @param context The Context this is associated with.
     * @param imi The {@link InputMethodInfo} of this preference.
     * @param isImeEnabler true if this preference is the IME enabler that has enable/disable
     *     switches for all available IMEs, not the list of enabled IMEs.
     * @param isAllowedByOrganization false if the IME has been disabled by a device or profile
     *     owner.
     * @param onSaveListener The listener called when this preference has been changed and needs
     *     to save the state to shared preference.
     */
    public InputMethodPreference(final Context context, final InputMethodInfo imi,
            final boolean isImeEnabler, final boolean isAllowedByOrganization,
            final OnSavePreferenceListener onSaveListener) {
        this(context, imi, imi.loadLabel(context.getPackageManager()), isAllowedByOrganization,
                onSaveListener);
        if (!isImeEnabler) {
            // Remove switch widget.
            setWidgetLayoutResource(NO_WIDGET);
        }
    }

    @VisibleForTesting
    InputMethodPreference(final Context context, final InputMethodInfo imi,
            final CharSequence title, final boolean isAllowedByOrganization,
            final OnSavePreferenceListener onSaveListener) {
        super(context);
        setPersistent(false);
        mImi = imi;
        mIsAllowedByOrganization = isAllowedByOrganization;
        mOnSaveListener = onSaveListener;
        // Disable on/off switch texts.
        setSwitchTextOn(EMPTY_TEXT);
        setSwitchTextOff(EMPTY_TEXT);
        setKey(imi.getId());
        setTitle(title);
        final String settingsActivity = imi.getSettingsActivity();
        if (TextUtils.isEmpty(settingsActivity)) {
            setIntent(null);
        } else {
            // Set an intent to invoke settings activity of an input method.
            final Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setClassName(imi.getPackageName(), settingsActivity);
            setIntent(intent);
        }
        mInputMethodSettingValues = InputMethodSettingValuesWrapper.getInstance(context);
        mHasPriorityInSorting = imi.isSystem()
                && InputMethodAndSubtypeUtil.isValidNonAuxAsciiCapableIme(imi);
        setOnPreferenceClickListener(this);
        setOnPreferenceChangeListener(this);
    }

    public InputMethodInfo getInputMethodInfo() {
        return mImi;
    }

    private boolean isImeEnabler() {
        // If this {@link SwitchPreference} doesn't have a widget layout, we explicitly hide the
        // switch widget at constructor.
        return getWidgetLayoutResource() != NO_WIDGET;
    }

    @Override
    public boolean onPreferenceChange(final Preference preference, final Object newValue) {
        // Always returns false to prevent default behavior.
        // See {@link TwoStatePreference#onClick()}.
        if (!isImeEnabler()) {
            // Prevent disabling an IME because this preference is for invoking a settings activity.
            return false;
        }
        if (isChecked()) {
            // Disable this IME.
            setCheckedInternal(false);
            return false;
        }
        if (mImi.isSystem()) {
            // Enable a system IME. No need to show a security warning dialog,
            // but we might need to prompt if it's not Direct Boot aware.
            // TV doesn't doesn't need to worry about this, but other platforms should show
            // a warning.
            if (mImi.getServiceInfo().directBootAware || isTv()) {
                setCheckedInternal(true);
            } else if (!isTv()){
                showDirectBootWarnDialog();
            }
        } else {
            // Once security is confirmed, we might prompt if the IME isn't
            // Direct Boot aware.
            showSecurityWarnDialog();
        }
        return false;
    }

    @Override
    public boolean onPreferenceClick(final Preference preference) {
        // Always returns true to prevent invoking an intent without catching exceptions.
        // See {@link Preference#performClick(PreferenceScreen)}/
        if (isImeEnabler()) {
            // Prevent invoking a settings activity because this preference is for enabling and
            // disabling an input method.
            return true;
        }
        final Context context = getContext();
        try {
            final Intent intent = getIntent();
            if (intent != null) {
                // Invoke a settings activity of an input method.
                context.startActivity(intent);
            }
        } catch (final ActivityNotFoundException e) {
            Log.d(TAG, "IME's Settings Activity Not Found", e);
            final String message = context.getString(
                    R.string.failed_to_open_app_settings_toast,
                    mImi.loadLabel(context.getPackageManager()));
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        }
        return true;
    }

    public void updatePreferenceViews() {
        final boolean isAlwaysChecked = mInputMethodSettingValues.isAlwaysCheckedIme(mImi);
        // When this preference has a switch and an input method should be always enabled,
        // this preference should be disabled to prevent accidentally disabling an input method.
        // This preference should also be disabled in case the admin does not allow this input
        // method.
        if (isAlwaysChecked && isImeEnabler()) {
            setDisabledByAdmin(null);
            setEnabled(false);
        } else if (!mIsAllowedByOrganization) {
            EnforcedAdmin admin =
                    RestrictedLockUtilsInternal.checkIfInputMethodDisallowed(getContext(),
                            mImi.getPackageName(), UserHandle.myUserId());
            setDisabledByAdmin(admin);
        } else {
            setEnabled(true);
        }
        setChecked(mInputMethodSettingValues.isEnabledImi(mImi));
        if (!isDisabledByAdmin()) {
            setSummary(getSummaryString());
        }
    }

    private InputMethodManager getInputMethodManager() {
        return (InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    }

    private String getSummaryString() {
        final InputMethodManager imm = getInputMethodManager();
        final List<InputMethodSubtype> subtypes = imm.getEnabledInputMethodSubtypeList(mImi, true);
        return InputMethodAndSubtypeUtil.getSubtypeLocaleNameListAsSentence(
                subtypes, getContext(), mImi);
    }

    private void setCheckedInternal(boolean checked) {
        super.setChecked(checked);
        mOnSaveListener.onSaveInputMethodPreference(InputMethodPreference.this);
        notifyChanged();
    }

    private void showSecurityWarnDialog() {
        if (mDialog != null && mDialog.isShowing()) {
            mDialog.dismiss();
        }
        final Context context = getContext();
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setCancelable(true /* cancelable */);
        builder.setTitle(android.R.string.dialog_alert_title);
        final CharSequence label = mImi.getServiceInfo().applicationInfo.loadLabel(
                context.getPackageManager());
        builder.setMessage(context.getString(R.string.ime_security_warning, label));
        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            // The user confirmed to enable a 3rd party IME, but we might
            // need to prompt if it's not Direct Boot aware.
            // TV doesn't doesn't need to worry about this, but other platforms should show
            // a warning.
            if (mImi.getServiceInfo().directBootAware || isTv()) {
                setCheckedInternal(true);
            } else {
                showDirectBootWarnDialog();
            }
        });
        builder.setNegativeButton(android.R.string.cancel, (dialog, which) -> {
            // The user canceled to enable a 3rd party IME.
            setCheckedInternal(false);
        });
        builder.setOnCancelListener((dialog) -> {
            // The user canceled to enable a 3rd party IME.
            setCheckedInternal(false);
        });
        mDialog = builder.create();
        mDialog.show();
    }

    private boolean isTv() {
        return (getContext().getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    private void showDirectBootWarnDialog() {
        if (mDialog != null && mDialog.isShowing()) {
            mDialog.dismiss();
        }
        final Context context = getContext();
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setCancelable(true /* cancelable */);
        builder.setMessage(context.getText(R.string.direct_boot_unaware_dialog_message));
        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> setCheckedInternal(true));
        builder.setNegativeButton(android.R.string.cancel,
                (dialog, which) -> setCheckedInternal(false));
        mDialog = builder.create();
        mDialog.show();
    }

    public int compareTo(final InputMethodPreference rhs, final Collator collator) {
        if (this == rhs) {
            return 0;
        }
        if (mHasPriorityInSorting != rhs.mHasPriorityInSorting) {
            // Prefer always checked system IMEs
            return mHasPriorityInSorting ? -1 : 1;
        }
        final CharSequence title = getTitle();
        final CharSequence rhsTitle = rhs.getTitle();
        final boolean emptyTitle = TextUtils.isEmpty(title);
        final boolean rhsEmptyTitle = TextUtils.isEmpty(rhsTitle);
        if (!emptyTitle && !rhsEmptyTitle) {
            return collator.compare(title.toString(), rhsTitle.toString());
        }
        // For historical reasons, an empty text needs to be put at the first.
        return (emptyTitle ? -1 : 0) - (rhsEmptyTitle ? -1 : 0);
    }
}
