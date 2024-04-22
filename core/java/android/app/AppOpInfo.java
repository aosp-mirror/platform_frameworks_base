/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.app;

import static android.app.AppOpsManager.OP_NONE;

import android.annotation.NonNull;

import java.util.Objects;

/**
 * Information about a particular app op.
 */
class AppOpInfo {

    /**
     * A unique constant identifying this app op.
     */
    public final int code;

    /**
     * This maps each operation to the operation that serves as the
     * switch to determine whether it is allowed.  Generally this is
     * a 1:1 mapping, but for some things (like location) that have
     * multiple low-level operations being tracked that should be
     * presented to the user as one switch then this can be used to
     * make them all controlled by the same single operation.
     */
    public final int switchCode;

    /**
     * This maps each operation to the public string constant for it.
     */
    public final String name;

    /**
     * This provides a simple name for each operation to be used
     * in debug output.
     */
    public final String simpleName;

    /**
     * This optionally maps a permission to an operation.  If there
     * is no permission associated with an operation, it is null.
     */
    public final String permission;

    /**
     * Specifies whether an Op should be restricted by a user restriction.
     * Each Op should be filled with a restriction string from UserManager or
     * null to specify it is not affected by any user restriction.
     */
    public final String restriction;

    /**
     * In which cases should an app be allowed to bypass the
     * {@link AppOpsManager#setUserRestriction user restriction} for a certain app-op.
     */
    public final AppOpsManager.RestrictionBypass allowSystemRestrictionBypass;

    /**
     * This specifies the default mode for each operation.
     */
    public final int defaultMode;

    /**
     * This specifies whether each option is allowed to be reset
     * when resetting all app preferences.  Disable reset for
     * app ops that are under strong control of some part of the
     * system (such as OP_WRITE_SMS, which should be allowed only
     * for whichever app is selected as the current SMS app).
     */
    public final boolean disableReset;

    /**
     * This specifies whether each option is only allowed to be read
     * by apps with privileged appops permission.
     */
    public final boolean restrictRead;

    /**
     * Whether to collect noteOp instances, and send them to callbacks.
     */
    public final boolean forceCollectNotes;

    AppOpInfo(int code,
            int switchCode,
            @NonNull String name,
            @NonNull String simpleName,
            String permission,
            String restriction,
            AppOpsManager.RestrictionBypass allowSystemRestrictionBypass,
            int defaultMode,
            boolean disableReset,
            boolean restrictRead,
            boolean forceCollectNotes) {
        if (code < OP_NONE) throw new IllegalArgumentException();
        if (switchCode < OP_NONE) throw new IllegalArgumentException();
        Objects.requireNonNull(name);
        Objects.requireNonNull(simpleName);
        this.code = code;
        this.switchCode = switchCode;
        this.name = name;
        this.simpleName = simpleName;
        this.permission = permission;
        this.restriction = restriction;
        this.allowSystemRestrictionBypass = allowSystemRestrictionBypass;
        this.defaultMode = defaultMode;
        this.disableReset = disableReset;
        this.restrictRead = restrictRead;
        this.forceCollectNotes = forceCollectNotes;
    }

    static class Builder {
        private int mCode;
        private int mSwitchCode;
        private String mName;
        private String mSimpleName;
        private String mPermission = null;
        private String mRestriction = null;
        private AppOpsManager.RestrictionBypass mAllowSystemRestrictionBypass = null;
        private int mDefaultMode = AppOpsManager.MODE_DEFAULT;
        private boolean mDisableReset = false;
        private boolean mRestrictRead = false;
        private boolean mForceCollectNotes = false;

        Builder(int code, @NonNull String name, @NonNull String simpleName) {
            if (code < OP_NONE) throw new IllegalArgumentException();
            Objects.requireNonNull(name);
            Objects.requireNonNull(simpleName);
            this.mCode = code;
            this.mSwitchCode = code;
            this.mName = name;
            this.mSimpleName = simpleName;
        }

        public Builder setCode(int value) {
            this.mCode = value;
            return this;
        }

        public Builder setSwitchCode(int value) {
            this.mSwitchCode = value;
            return this;
        }

        public Builder setName(String value) {
            this.mName = value;
            return this;
        }

        public Builder setSimpleName(String value) {
            this.mSimpleName = value;
            return this;
        }

        public Builder setPermission(String value) {
            this.mPermission = value;
            return this;
        }

        public Builder setRestriction(String value) {
            this.mRestriction = value;
            return this;
        }

        public Builder setAllowSystemRestrictionBypass(
                AppOpsManager.RestrictionBypass value) {
            this.mAllowSystemRestrictionBypass = value;
            return this;
        }

        public Builder setDefaultMode(int value) {
            this.mDefaultMode = value;
            return this;
        }

        public Builder setDisableReset(boolean value) {
            this.mDisableReset = value;
            return this;
        }

        public Builder setRestrictRead(boolean value) {
            this.mRestrictRead = value;
            return this;
        }

        public Builder setForceCollectNotes(boolean value) {
            this.mForceCollectNotes = value;
            return this;
        }

        public AppOpInfo build() {
            return new AppOpInfo(mCode, mSwitchCode, mName, mSimpleName, mPermission, mRestriction,
                mAllowSystemRestrictionBypass, mDefaultMode, mDisableReset, mRestrictRead,
                    mForceCollectNotes);
        }
    }
}
