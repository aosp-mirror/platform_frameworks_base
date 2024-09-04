/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.accessibility.a11ychecker;

import android.accessibility.AccessibilityCheckClass;
import android.accessibility.AccessibilityCheckResultType;
import android.content.ComponentName;
import android.text.TextUtils;

public class AndroidAccessibilityCheckerResult implements Cloneable {
    // Package name of the app containing the checked View.
    private String mPackageName;
    // Version code of the app containing the checked View.
    private long mAppVersionCode;
    // The path of the View starting from the root element in the window. Each element is
    // represented by the View's resource id, when available, or the View's class name.
    private String mUiElementPath;
    // Class name of the activity containing the checked View.
    private String mActivityName;
    // Title of the window containing the checked View.
    private String mWindowTitle;
    // The component name of the app running the AccessibilityService which provided the a11y node.
    private String mSourceComponentName;
    // Version code of the app running the AccessibilityService that provided the a11y node.
    private long mSourceVersionCode;
    // Class Name of the AccessibilityCheck that produced the result.
    private AccessibilityCheckClass mResultCheckClass;
    // Result type of the AccessibilityCheckResult.
    private AccessibilityCheckResultType mResultType;
    // Result ID of the AccessibilityCheckResult.
    private int mResultId;

    static final class Builder {
        private final AndroidAccessibilityCheckerResult mInstance;

        Builder() {
            mInstance = new AndroidAccessibilityCheckerResult();
        }

        Builder(Builder otherBuilder) {
            mInstance = otherBuilder.mInstance.clone();
        }

        public Builder setPackageName(String packageName) {
            mInstance.mPackageName = packageName;
            return this;
        }

        public Builder setAppVersionCode(long versionCode) {
            mInstance.mAppVersionCode = versionCode;
            return this;
        }

        public Builder setUiElementPath(String uiElementPath) {
            mInstance.mUiElementPath = uiElementPath;
            return this;
        }

        public Builder setActivityName(String activityName) {
            mInstance.mActivityName = activityName;
            return this;
        }

        public Builder setWindowTitle(String windowTitle) {
            mInstance.mWindowTitle = windowTitle;
            return this;
        }

        public Builder setSourceComponentName(ComponentName componentName) {
            mInstance.mSourceComponentName = componentName.flattenToString();
            return this;
        }

        public Builder setSourceVersionCode(long versionCode) {
            mInstance.mSourceVersionCode = versionCode;
            return this;
        }

        public Builder setResultCheckClass(AccessibilityCheckClass checkClass) {
            mInstance.mResultCheckClass = checkClass;
            return this;
        }

        public Builder setResultType(AccessibilityCheckResultType resultType) {
            mInstance.mResultType = resultType;
            return this;
        }

        public Builder setResultId(int resultId) {
            mInstance.mResultId = resultId;
            return this;
        }

        public AndroidAccessibilityCheckerResult build() {
            // TODO: assert all fields are set, etc
            return mInstance;
        }
    }

    static Builder newBuilder() {
        return new Builder();
    }

    public String getPackageName() {
        return mPackageName;
    }

    public long getAppVersionCode() {
        return mAppVersionCode;
    }

    public String getUiElementPath() {
        return mUiElementPath;
    }

    public String getActivityName() {
        return mActivityName;
    }

    public String getWindowTitle() {
        return mWindowTitle;
    }

    public String getSourceComponentName() {
        return mSourceComponentName;
    }

    public long getSourceVersionCode() {
        return mSourceVersionCode;
    }

    public AccessibilityCheckClass getResultCheckClass() {
        return mResultCheckClass;
    }

    public AccessibilityCheckResultType getResultType() {
        return mResultType;
    }

    public int getResultId() {
        return mResultId;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof AndroidAccessibilityCheckerResult)) {
            return false;
        }
        AndroidAccessibilityCheckerResult otherResult = (AndroidAccessibilityCheckerResult) other;
        return mPackageName.equals(otherResult.mPackageName)
                && mAppVersionCode == otherResult.mAppVersionCode
                && mUiElementPath.equals(otherResult.mUiElementPath)
                && mActivityName.equals(otherResult.mActivityName)
                && mWindowTitle.equals(otherResult.mWindowTitle)
                && mSourceComponentName.equals(otherResult.mSourceComponentName)
                && mSourceVersionCode == otherResult.mSourceVersionCode
                && mResultCheckClass.equals(otherResult.mResultCheckClass)
                && mResultType.equals(otherResult.mResultType)
                && mResultId == otherResult.mResultId;
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    @Override
    public String toString() {
        return TextUtils.formatSimple("%s:%d:%s:%s:%s:%s:%d:%s:%s:%d", mPackageName,
                mAppVersionCode, mUiElementPath, mActivityName, mWindowTitle, mSourceComponentName,
                mSourceVersionCode, mResultCheckClass.name(), mResultType.name(), mResultId);
    }

    @Override
    public AndroidAccessibilityCheckerResult clone() {
        try {
            return (AndroidAccessibilityCheckerResult) super.clone();
        } catch (CloneNotSupportedException e) {
            return null;
        }
    }
}
