/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.content.om;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Immutable overlay information about a package. All PackageInfos that
 * represent an overlay package will have a corresponding OverlayInfo.
 *
 * @hide
 */
@SystemApi
public final class OverlayInfo implements Parcelable {

    /** @hide */
    @IntDef(prefix = "STATE_", value = {
            STATE_UNKNOWN,
            STATE_MISSING_TARGET,
            STATE_NO_IDMAP,
            STATE_DISABLED,
            STATE_ENABLED,
            STATE_ENABLED_STATIC,
            STATE_TARGET_UPGRADING,
            STATE_OVERLAY_UPGRADING,
    })
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {}

    /**
     * An internal state used as the initial state of an overlay. OverlayInfo
     * objects exposed outside the {@link
     * com.android.server.om.OverlayManagerService} should never have this
     * state.
     *
     * @hide
     */
    public static final int STATE_UNKNOWN = -1;

    /**
     * The target package of the overlay is not installed. The overlay cannot be enabled.
     *
     * @hide
     */
    public static final int STATE_MISSING_TARGET = 0;

    /**
     * Creation of idmap file failed (e.g. no matching resources). The overlay
     * cannot be enabled.
     *
     * @hide
     */
    public static final int STATE_NO_IDMAP = 1;

    /**
     * The overlay is currently disabled. It can be enabled.
     *
     * @see IOverlayManager#setEnabled
     * @hide
     */
    public static final int STATE_DISABLED = 2;

    /**
     * The overlay is currently enabled. It can be disabled.
     *
     * @see IOverlayManager#setEnabled
     * @hide
     */
    public static final int STATE_ENABLED = 3;

    /**
     * The target package is currently being upgraded; the state will change
     * once the package installation has finished.
     * @hide
     */
    public static final int STATE_TARGET_UPGRADING = 4;

    /**
     * The overlay package is currently being upgraded; the state will change
     * once the package installation has finished.
     * @hide
     */
    public static final int STATE_OVERLAY_UPGRADING = 5;

    /**
     * The overlay package is currently enabled because it is marked as
     * 'static'. It cannot be disabled but will change state if for instance
     * its target is uninstalled.
     * @hide
     */
    public static final int STATE_ENABLED_STATIC = 6;

    /**
     * Overlay category: theme.
     * <p>
     * Change how Android (including the status bar, dialogs, ...) looks.
     *
     * @hide
     */
    public static final String CATEGORY_THEME = "android.theme";

    /**
     * Package name of the overlay package
     *
     * @hide
     */
    @SystemApi
    public final String packageName;

    /**
     * Package name of the target package
     *
     * @hide
     */
    @SystemApi
    public final String targetPackageName;

    /**
     * Category of the overlay package
     *
     * @hide
     */
    @SystemApi
    public final String category;

    /**
     * Full path to the base APK for this overlay package
     * @hide
     */
    public final String baseCodePath;

    /**
     * The state of this OverlayInfo as defined by the STATE_* constants in this class.
     * @hide
     */
    public final @State int state;

    /**
     * User handle for which this overlay applies
     * @hide
     */
    @SystemApi
    public final int userId;

    /**
     * Priority as read from the manifest. Used if isStatic is true. Not
     * intended to be exposed to 3rd party.
     *
     * @hide
     */
    public final int priority;

    /**
     * isStatic as read from the manifest. If true, the overlay is
     * unconditionally loaded and cannot be unloaded. Not intended to be
     * exposed to 3rd party.
     *
     * @hide
     */
    public final boolean isStatic;

    /**
     * Create a new OverlayInfo based on source with an updated state.
     *
     * @param source the source OverlayInfo to base the new instance on
     * @param state the new state for the source OverlayInfo
     *
     * @hide
     */
    public OverlayInfo(@NonNull OverlayInfo source, @State int state) {
        this(source.packageName, source.targetPackageName, source.category, source.baseCodePath,
                state, source.userId, source.priority, source.isStatic);
    }

    /** @hide */
    public OverlayInfo(@NonNull String packageName, @NonNull String targetPackageName,
            @NonNull String category, @NonNull String baseCodePath, int state, int userId,
            int priority, boolean isStatic) {
        this.packageName = packageName;
        this.targetPackageName = targetPackageName;
        this.category = category;
        this.baseCodePath = baseCodePath;
        this.state = state;
        this.userId = userId;
        this.priority = priority;
        this.isStatic = isStatic;
        ensureValidState();
    }

    /** @hide */
    public OverlayInfo(Parcel source) {
        packageName = source.readString();
        targetPackageName = source.readString();
        category = source.readString();
        baseCodePath = source.readString();
        state = source.readInt();
        userId = source.readInt();
        priority = source.readInt();
        isStatic = source.readBoolean();
        ensureValidState();
    }

    private void ensureValidState() {
        if (packageName == null) {
            throw new IllegalArgumentException("packageName must not be null");
        }
        if (targetPackageName == null) {
            throw new IllegalArgumentException("targetPackageName must not be null");
        }
        if (baseCodePath == null) {
            throw new IllegalArgumentException("baseCodePath must not be null");
        }
        switch (state) {
            case STATE_UNKNOWN:
            case STATE_MISSING_TARGET:
            case STATE_NO_IDMAP:
            case STATE_DISABLED:
            case STATE_ENABLED:
            case STATE_ENABLED_STATIC:
            case STATE_TARGET_UPGRADING:
            case STATE_OVERLAY_UPGRADING:
                break;
            default:
                throw new IllegalArgumentException("State " + state + " is not a valid state");
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(packageName);
        dest.writeString(targetPackageName);
        dest.writeString(category);
        dest.writeString(baseCodePath);
        dest.writeInt(state);
        dest.writeInt(userId);
        dest.writeInt(priority);
        dest.writeBoolean(isStatic);
    }

    public static final Parcelable.Creator<OverlayInfo> CREATOR =
            new Parcelable.Creator<OverlayInfo>() {
        @Override
        public OverlayInfo createFromParcel(Parcel source) {
            return new OverlayInfo(source);
        }

        @Override
        public OverlayInfo[] newArray(int size) {
            return new OverlayInfo[size];
        }
    };

    /**
     * Return true if this overlay is enabled, i.e. should be used to overlay
     * the resources in the target package.
     *
     * Disabled overlay packages are installed but are currently not in use.
     *
     * @return true if the overlay is enabled, else false.
     * @hide
     */
    @SystemApi
    public boolean isEnabled() {
        switch (state) {
            case STATE_ENABLED:
            case STATE_ENABLED_STATIC:
                return true;
            default:
                return false;
        }
    }

    /**
     * Translate a state to a human readable string. Only intended for
     * debugging purposes.
     *
     * @return a human readable String representing the state.
     * @hide
     */
    public static String stateToString(@State int state) {
        switch (state) {
            case STATE_UNKNOWN:
                return "STATE_UNKNOWN";
            case STATE_MISSING_TARGET:
                return "STATE_MISSING_TARGET";
            case STATE_NO_IDMAP:
                return "STATE_NO_IDMAP";
            case STATE_DISABLED:
                return "STATE_DISABLED";
            case STATE_ENABLED:
                return "STATE_ENABLED";
            case STATE_ENABLED_STATIC:
                return "STATE_ENABLED_STATIC";
            case STATE_TARGET_UPGRADING:
                return "STATE_TARGET_UPGRADING";
            case STATE_OVERLAY_UPGRADING:
                return "STATE_OVERLAY_UPGRADING";
            default:
                return "<unknown state>";
        }
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + userId;
        result = prime * result + state;
        result = prime * result + ((packageName == null) ? 0 : packageName.hashCode());
        result = prime * result + ((targetPackageName == null) ? 0 : targetPackageName.hashCode());
        result = prime * result + ((category == null) ? 0 : category.hashCode());
        result = prime * result + ((baseCodePath == null) ? 0 : baseCodePath.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        OverlayInfo other = (OverlayInfo) obj;
        if (userId != other.userId) {
            return false;
        }
        if (state != other.state) {
            return false;
        }
        if (!packageName.equals(other.packageName)) {
            return false;
        }
        if (!targetPackageName.equals(other.targetPackageName)) {
            return false;
        }
        if (!category.equals(other.category)) {
            return false;
        }
        if (!baseCodePath.equals(other.baseCodePath)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "OverlayInfo { overlay=" + packageName + ", target=" + targetPackageName + ", state="
                + state + " (" + stateToString(state) + "), userId=" + userId + " }";
    }
}
