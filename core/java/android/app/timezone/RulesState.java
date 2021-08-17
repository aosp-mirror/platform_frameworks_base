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

package android.app.timezone;

import static android.app.timezone.Utils.validateConditionalNull;
import static android.app.timezone.Utils.validateNotNull;
import static android.app.timezone.Utils.validateRulesVersion;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Description of the state of time zone rules on a device.
 *
 * <p>The following properties are included:
 * <dl>
 *     <dt>baseRulesVersion</dt>
 *     <dd>the IANA rules version that shipped with the OS. Always present. e.g. "2017a".</dd>
 *     <dt>distroFormatVersionSupported</dt>
 *     <dd>the distro format version supported by this device. Always present.</dd>
 *     <dt>operationInProgress</dt>
 *     <dd>{@code true} if there is an install / uninstall operation currently happening.</dd>
 *     <dt>stagedOperationType</dt>
 *     <dd>one of {@link #STAGED_OPERATION_UNKNOWN}, {@link #STAGED_OPERATION_NONE},
 *     {@link #STAGED_OPERATION_UNINSTALL} and {@link #STAGED_OPERATION_INSTALL} indicating whether
 *     there is a currently staged time zone distro operation. {@link #STAGED_OPERATION_UNKNOWN} is
 *     used when {@link #isOperationInProgress()} is {@code true}. Staged operations currently
 *     require a reboot to become active.</dd>
 *     <dt>stagedDistroRulesVersion</dt>
 *     <dd>[present if distroStagedState == STAGED_STATE_INSTALL], the rules version of the distro
 *     currently staged for installation.</dd>
 *     <dt>distroStatus</dt>
 *     <dd>{@link #DISTRO_STATUS_INSTALLED} if there is a time zone distro installed and active,
 *     {@link #DISTRO_STATUS_NONE} if there is no active installed distro.
 *     {@link #DISTRO_STATUS_UNKNOWN} is used when {@link #isOperationInProgress()} is {@code true}.
 *     </dd>
 *     <dt>installedDistroRulesVersion</dt>
 *     <dd>[present if distroStatus == {@link #DISTRO_STATUS_INSTALLED}], the rules version of the
 *     installed and active distro.</dd>
 * </dl>
 *
 * @hide
 */
public final class RulesState implements Parcelable {

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "STAGED_OPERATION_" }, value = {
            STAGED_OPERATION_UNKNOWN,
            STAGED_OPERATION_NONE,
            STAGED_OPERATION_UNINSTALL,
            STAGED_OPERATION_INSTALL
    })
    private @interface StagedOperationType {}

    /** Staged state could not be determined. */
    public static final int STAGED_OPERATION_UNKNOWN = 0;
    /** Nothing is staged. */
    public static final int STAGED_OPERATION_NONE = 1;
    /** An uninstall is staged. */
    public static final int STAGED_OPERATION_UNINSTALL = 2;
    /** An install is staged. */
    public static final int STAGED_OPERATION_INSTALL = 3;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "DISTRO_STATUS_" }, value = {
            DISTRO_STATUS_UNKNOWN,
            DISTRO_STATUS_NONE,
            DISTRO_STATUS_INSTALLED
    })
    private @interface DistroStatus {}

    /** The current distro status could not be determined. */
    public static final int DISTRO_STATUS_UNKNOWN = 0;
    /** There is no active installed time zone distro. */
    public static final int DISTRO_STATUS_NONE = 1;
    /** The is an active, installed time zone distro. */
    public static final int DISTRO_STATUS_INSTALLED = 2;

    private static final byte BYTE_FALSE = 0;
    private static final byte BYTE_TRUE = 1;

    private final String mBaseRulesVersion;
    private final DistroFormatVersion mDistroFormatVersionSupported;
    private final boolean mOperationInProgress;
    @StagedOperationType private final int mStagedOperationType;
    @Nullable private final DistroRulesVersion mStagedDistroRulesVersion;
    @DistroStatus private final int mDistroStatus;
    @Nullable private final DistroRulesVersion mInstalledDistroRulesVersion;

    public RulesState(String baseRulesVersion, DistroFormatVersion distroFormatVersionSupported,
            boolean operationInProgress,
            @StagedOperationType int stagedOperationType,
            @Nullable DistroRulesVersion stagedDistroRulesVersion,
            @DistroStatus int distroStatus,
            @Nullable DistroRulesVersion installedDistroRulesVersion) {
        this.mBaseRulesVersion = validateRulesVersion("baseRulesVersion", baseRulesVersion);
        this.mDistroFormatVersionSupported =
                validateNotNull("distroFormatVersionSupported", distroFormatVersionSupported);
        this.mOperationInProgress = operationInProgress;

        if (operationInProgress && stagedOperationType != STAGED_OPERATION_UNKNOWN) {
            throw new IllegalArgumentException(
                    "stagedOperationType != STAGED_OPERATION_UNKNOWN");
        }
        this.mStagedOperationType = validateStagedOperation(stagedOperationType);
        this.mStagedDistroRulesVersion = validateConditionalNull(
                mStagedOperationType == STAGED_OPERATION_INSTALL /* requireNotNull */,
                "stagedDistroRulesVersion", stagedDistroRulesVersion);

        this.mDistroStatus = validateDistroStatus(distroStatus);
        this.mInstalledDistroRulesVersion = validateConditionalNull(
                mDistroStatus == DISTRO_STATUS_INSTALLED/* requireNotNull */,
                "installedDistroRulesVersion", installedDistroRulesVersion);
    }

    public String getBaseRulesVersion() {
        return mBaseRulesVersion;
    }

    public boolean isOperationInProgress() {
        return mOperationInProgress;
    }

    public @StagedOperationType int getStagedOperationType() {
        return mStagedOperationType;
    }

    /**
     * Returns the staged rules version when {@link #getStagedOperationType()} is
     * {@link #STAGED_OPERATION_INSTALL}.
     */
    public @Nullable DistroRulesVersion getStagedDistroRulesVersion() {
        return mStagedDistroRulesVersion;
    }

    public @DistroStatus int getDistroStatus() {
        return mDistroStatus;
    }

    /**
     * Returns the installed rules version when {@link #getDistroStatus()} is
     * {@link #DISTRO_STATUS_INSTALLED}.
     */
    public @Nullable DistroRulesVersion getInstalledDistroRulesVersion() {
        return mInstalledDistroRulesVersion;
    }

    /**
     * Returns true if a distro in the specified format is supported on this device.
     */
    public boolean isDistroFormatVersionSupported(DistroFormatVersion distroFormatVersion) {
        return mDistroFormatVersionSupported.supports(distroFormatVersion);
    }

    /**
     * Returns true if the base data files contain IANA rules data that are newer than the
     * distro IANA rules version supplied, i.e. true when the version specified would be "worse"
     * than the one that is in the base data. Returns false if the base version is the
     * same or older, i.e. false when the version specified would be "better" than the one that is
     * in the base set.
     */
    public boolean isBaseVersionNewerThan(DistroRulesVersion distroRulesVersion) {
        return mBaseRulesVersion.compareTo(distroRulesVersion.getRulesVersion()) > 0;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<RulesState> CREATOR =
            new Parcelable.Creator<RulesState>() {
        public RulesState createFromParcel(Parcel in) {
            return RulesState.createFromParcel(in);
        }

        public RulesState[] newArray(int size) {
            return new RulesState[size];
        }
    };

    private static RulesState createFromParcel(Parcel in) {
        String baseRulesVersion = in.readString();
        DistroFormatVersion distroFormatVersionSupported = in.readParcelable(null);
        boolean operationInProgress = in.readByte() == BYTE_TRUE;
        int distroStagedState = in.readByte();
        DistroRulesVersion stagedDistroRulesVersion = in.readParcelable(null);
        int installedDistroStatus = in.readByte();
        DistroRulesVersion installedDistroRulesVersion = in.readParcelable(null);
        return new RulesState(baseRulesVersion, distroFormatVersionSupported, operationInProgress,
                distroStagedState, stagedDistroRulesVersion,
                installedDistroStatus, installedDistroRulesVersion);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mBaseRulesVersion);
        out.writeParcelable(mDistroFormatVersionSupported, 0);
        out.writeByte(mOperationInProgress ? BYTE_TRUE : BYTE_FALSE);
        out.writeByte((byte) mStagedOperationType);
        out.writeParcelable(mStagedDistroRulesVersion, 0);
        out.writeByte((byte) mDistroStatus);
        out.writeParcelable(mInstalledDistroRulesVersion, 0);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        RulesState that = (RulesState) o;

        if (mOperationInProgress != that.mOperationInProgress) {
            return false;
        }
        if (mStagedOperationType != that.mStagedOperationType) {
            return false;
        }
        if (mDistroStatus != that.mDistroStatus) {
            return false;
        }
        if (!mBaseRulesVersion.equals(that.mBaseRulesVersion)) {
            return false;
        }
        if (!mDistroFormatVersionSupported.equals(that.mDistroFormatVersionSupported)) {
            return false;
        }
        if (mStagedDistroRulesVersion != null ? !mStagedDistroRulesVersion
                .equals(that.mStagedDistroRulesVersion) : that.mStagedDistroRulesVersion != null) {
            return false;
        }
        return mInstalledDistroRulesVersion != null ? mInstalledDistroRulesVersion
                .equals(that.mInstalledDistroRulesVersion)
                : that.mInstalledDistroRulesVersion == null;
    }

    @Override
    public int hashCode() {
        int result = mBaseRulesVersion.hashCode();
        result = 31 * result + mDistroFormatVersionSupported.hashCode();
        result = 31 * result + (mOperationInProgress ? 1 : 0);
        result = 31 * result + mStagedOperationType;
        result = 31 * result + (mStagedDistroRulesVersion != null ? mStagedDistroRulesVersion
                .hashCode()
                : 0);
        result = 31 * result + mDistroStatus;
        result = 31 * result + (mInstalledDistroRulesVersion != null ? mInstalledDistroRulesVersion
                .hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "RulesState{"
                + "mBaseRulesVersion='" + mBaseRulesVersion + '\''
                + ", mDistroFormatVersionSupported=" + mDistroFormatVersionSupported
                + ", mOperationInProgress=" + mOperationInProgress
                + ", mStagedOperationType=" + mStagedOperationType
                + ", mStagedDistroRulesVersion=" + mStagedDistroRulesVersion
                + ", mDistroStatus=" + mDistroStatus
                + ", mInstalledDistroRulesVersion=" + mInstalledDistroRulesVersion
                + '}';
    }

    private static int validateStagedOperation(int stagedOperationType) {
        if (stagedOperationType < STAGED_OPERATION_UNKNOWN
                || stagedOperationType > STAGED_OPERATION_INSTALL) {
            throw new IllegalArgumentException("Unknown operation type=" + stagedOperationType);
        }
        return stagedOperationType;
    }

    private static int validateDistroStatus(int distroStatus) {
        if (distroStatus < DISTRO_STATUS_UNKNOWN || distroStatus > DISTRO_STATUS_INSTALLED) {
            throw new IllegalArgumentException("Unknown distro status=" + distroStatus);
        }
        return distroStatus;
    }
}
