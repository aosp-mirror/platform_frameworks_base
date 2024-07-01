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

package android.hardware.biometrics;

import android.annotation.DrawableRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * Contains the information set/requested by the caller of the {@link BiometricPrompt}
 * @hide
 */
public class PromptInfo implements Parcelable {

    @DrawableRes private int mLogoRes;
    @Nullable private Bitmap mLogoBitmap;
    @Nullable private String mLogoDescription;
    @NonNull private CharSequence mTitle;
    private boolean mUseDefaultTitle;
    @Nullable private CharSequence mSubtitle;
    private boolean mUseDefaultSubtitle;
    @Nullable private CharSequence mDescription;
    @Nullable private PromptContentViewParcelable mContentView;
    @Nullable private CharSequence mDeviceCredentialTitle;
    @Nullable private CharSequence mDeviceCredentialSubtitle;
    @Nullable private CharSequence mDeviceCredentialDescription;
    @Nullable private CharSequence mNegativeButtonText;
    private boolean mConfirmationRequested = true; // default to true
    private boolean mDeviceCredentialAllowed;
    private @BiometricManager.Authenticators.Types int mAuthenticators;
    private boolean mDisallowBiometricsIfPolicyExists;
    private boolean mReceiveSystemEvents;
    @NonNull private List<Integer> mAllowedSensorIds = new ArrayList<>();
    private boolean mAllowBackgroundAuthentication;
    private boolean mIgnoreEnrollmentState;
    private boolean mIsForLegacyFingerprintManager = false;
    private boolean mShowEmergencyCallButton = false;
    private boolean mUseParentProfileForDeviceCredential = false;
    private ComponentName mComponentNameForConfirmDeviceCredentialActivity = null;

    public PromptInfo() {

    }

    PromptInfo(Parcel in) {
        mLogoRes = in.readInt();
        mLogoBitmap = in.readTypedObject(Bitmap.CREATOR);
        mLogoDescription = in.readString();
        mTitle = in.readCharSequence();
        mUseDefaultTitle = in.readBoolean();
        mSubtitle = in.readCharSequence();
        mUseDefaultSubtitle = in.readBoolean();
        mDescription = in.readCharSequence();
        mContentView = in.readParcelable(PromptContentViewParcelable.class.getClassLoader(),
                PromptContentViewParcelable.class);
        mDeviceCredentialTitle = in.readCharSequence();
        mDeviceCredentialSubtitle = in.readCharSequence();
        mDeviceCredentialDescription = in.readCharSequence();
        mNegativeButtonText = in.readCharSequence();
        mConfirmationRequested = in.readBoolean();
        mDeviceCredentialAllowed = in.readBoolean();
        mAuthenticators = in.readInt();
        mDisallowBiometricsIfPolicyExists = in.readBoolean();
        mReceiveSystemEvents = in.readBoolean();
        mAllowedSensorIds = in.readArrayList(Integer.class.getClassLoader(), java.lang.Integer.class);
        mAllowBackgroundAuthentication = in.readBoolean();
        mIgnoreEnrollmentState = in.readBoolean();
        mIsForLegacyFingerprintManager = in.readBoolean();
        mShowEmergencyCallButton = in.readBoolean();
        mUseParentProfileForDeviceCredential = in.readBoolean();
        mComponentNameForConfirmDeviceCredentialActivity = in.readParcelable(
                ComponentName.class.getClassLoader(), ComponentName.class);
    }

    public static final Creator<PromptInfo> CREATOR = new Creator<PromptInfo>() {
        @Override
        public PromptInfo createFromParcel(Parcel in) {
            return new PromptInfo(in);
        }

        @Override
        public PromptInfo[] newArray(int size) {
            return new PromptInfo[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mLogoRes);
        dest.writeTypedObject(mLogoBitmap, 0);
        dest.writeString(mLogoDescription);
        dest.writeCharSequence(mTitle);
        dest.writeBoolean(mUseDefaultTitle);
        dest.writeCharSequence(mSubtitle);
        dest.writeBoolean(mUseDefaultSubtitle);
        dest.writeCharSequence(mDescription);
        dest.writeParcelable(mContentView, 0);
        dest.writeCharSequence(mDeviceCredentialTitle);
        dest.writeCharSequence(mDeviceCredentialSubtitle);
        dest.writeCharSequence(mDeviceCredentialDescription);
        dest.writeCharSequence(mNegativeButtonText);
        dest.writeBoolean(mConfirmationRequested);
        dest.writeBoolean(mDeviceCredentialAllowed);
        dest.writeInt(mAuthenticators);
        dest.writeBoolean(mDisallowBiometricsIfPolicyExists);
        dest.writeBoolean(mReceiveSystemEvents);
        dest.writeList(mAllowedSensorIds);
        dest.writeBoolean(mAllowBackgroundAuthentication);
        dest.writeBoolean(mIgnoreEnrollmentState);
        dest.writeBoolean(mIsForLegacyFingerprintManager);
        dest.writeBoolean(mShowEmergencyCallButton);
        dest.writeBoolean(mUseParentProfileForDeviceCredential);
        dest.writeParcelable(mComponentNameForConfirmDeviceCredentialActivity, 0);
    }

    // LINT.IfChange
    public boolean requiresTestOrInternalPermission() {
        if (mIsForLegacyFingerprintManager
                && mAllowedSensorIds.size() == 1
                && !mAllowBackgroundAuthentication) {
            return false;
        } else if (!mAllowedSensorIds.isEmpty()) {
            return true;
        } else if (mAllowBackgroundAuthentication) {
            return true;
        } else if (mIsForLegacyFingerprintManager) {
            return true;
        } else if (mIgnoreEnrollmentState) {
            return true;
        } else if (mShowEmergencyCallButton) {
            return true;
        } else if (mComponentNameForConfirmDeviceCredentialActivity != null) {
            return true;
        }
        return false;
    }

    public boolean requiresInternalPermission() {
        if (mDisallowBiometricsIfPolicyExists) {
            return true;
        } else if (mUseDefaultTitle) {
            return true;
        } else if (mUseDefaultSubtitle) {
            return true;
        } else if (mDeviceCredentialTitle != null) {
            return true;
        } else if (mDeviceCredentialSubtitle != null) {
            return true;
        } else if (mDeviceCredentialDescription != null) {
            return true;
        } else if (mReceiveSystemEvents) {
            return true;
        }
        return false;
    }

    /**
     * Returns whether SET_BIOMETRIC_DIALOG_ADVANCED is contained.
     *
     * Currently, logo res, logo bitmap, logo description, PromptContentViewWithMoreOptions needs
     * this permission.
     */
    public boolean requiresAdvancedPermission() {
        if (mLogoRes != 0) {
            return true;
        } else if (mLogoBitmap != null) {
            return true;
        } else if (mLogoDescription != null) {
            return true;
        } else if (mContentView != null && isContentViewMoreOptionsButtonUsed()) {
            return true;
        } else if (Flags.mandatoryBiometrics()
                && (mAuthenticators & BiometricManager.Authenticators.MANDATORY_BIOMETRICS)
                != 0) {
            return true;
        }
        return false;
    }

    /**
     * Returns if parent profile needs to be used for device credential.
     */
    public boolean shouldUseParentProfileForDeviceCredential() {
        return mUseParentProfileForDeviceCredential;
    }

    /**
     * Returns if the PromptContentViewWithMoreOptionsButton is set.
     */
    public boolean isContentViewMoreOptionsButtonUsed() {
        return Flags.customBiometricPrompt() && mContentView != null
                && mContentView instanceof PromptContentViewWithMoreOptionsButton;
    }

    // LINT.ThenChange(frameworks/base/core/java/android/hardware/biometrics/BiometricPrompt.java)

    // Setters

    /**
     * Sets logo res and bitmap
     *
     * @param logoRes    The logo res set by the app; Or 0 if the app sets bitmap directly.
     * @param logoBitmap The bitmap from logoRes if the app sets logoRes; Or the bitmap set by the
     *                   app directly.
     */
    public void setLogo(@DrawableRes int logoRes, @NonNull Bitmap logoBitmap) {
        mLogoRes = logoRes;
        mLogoBitmap = logoBitmap;
    }

    public void setLogoDescription(@NonNull String logoDescription) {
        mLogoDescription = logoDescription;
    }

    public void setTitle(CharSequence title) {
        mTitle = title;
    }

    public void setUseDefaultTitle(boolean useDefaultTitle) {
        mUseDefaultTitle = useDefaultTitle;
    }

    public void setSubtitle(CharSequence subtitle) {
        mSubtitle = subtitle;
    }

    public void setUseDefaultSubtitle(boolean useDefaultSubtitle) {
        mUseDefaultSubtitle = useDefaultSubtitle;
    }

    public void setDescription(CharSequence description) {
        mDescription = description;
    }

    public void setContentView(PromptContentView view) {
        mContentView = (PromptContentViewParcelable) view;
    }

    public void setDeviceCredentialTitle(CharSequence deviceCredentialTitle) {
        mDeviceCredentialTitle = deviceCredentialTitle;
    }

    public void setDeviceCredentialSubtitle(CharSequence deviceCredentialSubtitle) {
        mDeviceCredentialSubtitle = deviceCredentialSubtitle;
    }

    public void setDeviceCredentialDescription(CharSequence deviceCredentialDescription) {
        mDeviceCredentialDescription = deviceCredentialDescription;
    }

    public void setNegativeButtonText(CharSequence negativeButtonText) {
        mNegativeButtonText = negativeButtonText;
    }

    public void setConfirmationRequested(boolean confirmationRequested) {
        mConfirmationRequested = confirmationRequested;
    }

    public void setDeviceCredentialAllowed(boolean deviceCredentialAllowed) {
        mDeviceCredentialAllowed = deviceCredentialAllowed;
    }

    public void setAuthenticators(int authenticators) {
        mAuthenticators = authenticators;
    }

    public void setDisallowBiometricsIfPolicyExists(boolean disallowBiometricsIfPolicyExists) {
        mDisallowBiometricsIfPolicyExists = disallowBiometricsIfPolicyExists;
    }

    public void setReceiveSystemEvents(boolean receiveSystemEvents) {
        mReceiveSystemEvents = receiveSystemEvents;
    }

    public void setAllowedSensorIds(@NonNull List<Integer> sensorIds) {
        mAllowedSensorIds.clear();
        mAllowedSensorIds.addAll(sensorIds);
    }

    public void setAllowBackgroundAuthentication(boolean allow) {
        mAllowBackgroundAuthentication = allow;
    }

    public void setIgnoreEnrollmentState(boolean ignoreEnrollmentState) {
        mIgnoreEnrollmentState = ignoreEnrollmentState;
    }

    public void setIsForLegacyFingerprintManager(int sensorId) {
        mIsForLegacyFingerprintManager = true;
        mAllowedSensorIds.clear();
        mAllowedSensorIds.add(sensorId);
    }

    public void setShowEmergencyCallButton(boolean showEmergencyCallButton) {
        mShowEmergencyCallButton = showEmergencyCallButton;
    }

    public void setComponentNameForConfirmDeviceCredentialActivity(
            ComponentName componentNameForConfirmDeviceCredentialActivity) {
        mComponentNameForConfirmDeviceCredentialActivity =
                componentNameForConfirmDeviceCredentialActivity;
    }

    public void setUseParentProfileForDeviceCredential(
            boolean useParentProfileForDeviceCredential) {
        mUseParentProfileForDeviceCredential = useParentProfileForDeviceCredential;
    }

    // Getters

    /**
     * Returns the logo bitmap either from logo resource or bitmap passed in from the app.
     */
    public Bitmap getLogo() {
        return mLogoBitmap;
    }

    /**
     * Returns the logo res set by the app.
     */
    @DrawableRes
    public int getLogoRes() {
        return mLogoRes;
    }

    /**
     * Returns the logo bitmap set by the app.
     */
    public Bitmap getLogoBitmap() {
        // If mLogoRes has been set, return null since mLogoBitmap is from the res, but not from
        // the app directly.
        return mLogoRes == 0 ? mLogoBitmap : null;
    }

    public String getLogoDescription() {
        return mLogoDescription;
    }

    public CharSequence getTitle() {
        return mTitle;
    }

    public boolean isUseDefaultTitle() {
        return mUseDefaultTitle;
    }

    public CharSequence getSubtitle() {
        return mSubtitle;
    }

    public boolean isUseDefaultSubtitle() {
        return mUseDefaultSubtitle;
    }

    public CharSequence getDescription() {
        return mDescription;
    }

    /**
     * Gets the content view for the prompt.
     *
     * @return The content view for the prompt, or null if the prompt has no content view.
     */
    public PromptContentView getContentView() {
        return mContentView;
    }

    public CharSequence getDeviceCredentialTitle() {
        return mDeviceCredentialTitle;
    }

    public CharSequence getDeviceCredentialSubtitle() {
        return mDeviceCredentialSubtitle;
    }

    public CharSequence getDeviceCredentialDescription() {
        return mDeviceCredentialDescription;
    }

    public CharSequence getNegativeButtonText() {
        return mNegativeButtonText;
    }

    public boolean isConfirmationRequested() {
        return mConfirmationRequested;
    }

    /**
     * This value is read once by {@link com.android.server.biometrics.BiometricService} and
     * combined into {@link #getAuthenticators()}.
     * @deprecated
     * @return
     */
    @Deprecated
    public boolean isDeviceCredentialAllowed() {
        return mDeviceCredentialAllowed;
    }

    public int getAuthenticators() {
        return mAuthenticators;
    }

    public boolean isDisallowBiometricsIfPolicyExists() {
        return mDisallowBiometricsIfPolicyExists;
    }

    public boolean isReceiveSystemEvents() {
        return mReceiveSystemEvents;
    }

    @NonNull
    public List<Integer> getAllowedSensorIds() {
        return mAllowedSensorIds;
    }

    public boolean isAllowBackgroundAuthentication() {
        return mAllowBackgroundAuthentication;
    }

    public boolean isIgnoreEnrollmentState() {
        return mIgnoreEnrollmentState;
    }

    public boolean isForLegacyFingerprintManager() {
        return mIsForLegacyFingerprintManager;
    }

    public boolean isShowEmergencyCallButton() {
        return mShowEmergencyCallButton;
    }

    public ComponentName getComponentNameForConfirmDeviceCredentialActivity() {
        return mComponentNameForConfirmDeviceCredentialActivity;
    }

}
