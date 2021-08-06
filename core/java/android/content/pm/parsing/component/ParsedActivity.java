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

package android.content.pm.parsing.component;

import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.pm.parsing.ParsingPackageImpl.sForInternedString;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_UNSPECIFIED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityTaskManager;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.android.internal.util.DataClass;
import com.android.internal.util.Parcelling.BuiltIn.ForInternedString;

/** @hide **/
public class ParsedActivity extends ParsedMainComponent {

    private int theme;
    private int uiOptions;

    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String targetActivity;

    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String parentActivityName;
    @Nullable
    private String taskAffinity;
    private int privateFlags;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String permission;

    private int launchMode;
    private int documentLaunchMode;
    private int maxRecents;
    private int configChanges;
    private int softInputMode;
    private int persistableMode;
    private int lockTaskLaunchMode;

    private int screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private int resizeMode = ActivityInfo.RESIZE_MODE_RESIZEABLE;

    @Nullable
    private Float maxAspectRatio;

    @Nullable
    private Float minAspectRatio;

    private boolean supportsSizeChanges;

    @Nullable
    private String requestedVrComponent;
    private int rotationAnimation = -1;
    private int colorMode;

    @Nullable
    private ActivityInfo.WindowLayout windowLayout;

    public ParsedActivity(ParsedActivity other) {
        super(other);
        this.theme = other.theme;
        this.uiOptions = other.uiOptions;
        this.targetActivity = other.targetActivity;
        this.parentActivityName = other.parentActivityName;
        this.taskAffinity = other.taskAffinity;
        this.privateFlags = other.privateFlags;
        this.permission = other.permission;
        this.launchMode = other.launchMode;
        this.documentLaunchMode = other.documentLaunchMode;
        this.maxRecents = other.maxRecents;
        this.configChanges = other.configChanges;
        this.softInputMode = other.softInputMode;
        this.persistableMode = other.persistableMode;
        this.lockTaskLaunchMode = other.lockTaskLaunchMode;
        this.screenOrientation = other.screenOrientation;
        this.resizeMode = other.resizeMode;
        this.maxAspectRatio = other.maxAspectRatio;
        this.minAspectRatio = other.minAspectRatio;
        this.supportsSizeChanges = other.supportsSizeChanges;
        this.requestedVrComponent = other.requestedVrComponent;
        this.rotationAnimation = other.rotationAnimation;
        this.colorMode = other.colorMode;
        this.windowLayout = other.windowLayout;
    }

    /**
     * Generate activity object that forwards user to App Details page automatically.
     * This activity should be invisible to user and user should not know or see it.
     */
    public static ParsedActivity makeAppDetailsActivity(String packageName, String processName,
            int uiOptions, String taskAffinity, boolean hardwareAccelerated) {
        ParsedActivity activity = new ParsedActivity();
        activity.setPackageName(packageName);
        activity.theme = android.R.style.Theme_NoDisplay;
        activity.exported = true;
        activity.setName(PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME);
        activity.setProcessName(processName);
        activity.uiOptions = uiOptions;
        activity.taskAffinity = taskAffinity;
        activity.launchMode = ActivityInfo.LAUNCH_MULTIPLE;
        activity.documentLaunchMode = ActivityInfo.DOCUMENT_LAUNCH_NONE;
        activity.maxRecents = ActivityTaskManager.getDefaultAppRecentsLimitStatic();
        activity.configChanges = ParsedActivityUtils.getActivityConfigChanges(0, 0);
        activity.softInputMode = 0;
        activity.persistableMode = ActivityInfo.PERSIST_NEVER;
        activity.screenOrientation = SCREEN_ORIENTATION_UNSPECIFIED;
        activity.resizeMode = RESIZE_MODE_FORCE_RESIZEABLE;
        activity.lockTaskLaunchMode = 0;
        activity.setDirectBootAware(false);
        activity.rotationAnimation = ROTATION_ANIMATION_UNSPECIFIED;
        activity.colorMode = ActivityInfo.COLOR_MODE_DEFAULT;
        if (hardwareAccelerated) {
            activity.setFlags(activity.getFlags() | ActivityInfo.FLAG_HARDWARE_ACCELERATED);
        }
        return activity;
    }

    static ParsedActivity makeAlias(String targetActivityName, ParsedActivity target) {
        ParsedActivity alias = new ParsedActivity();
        alias.setPackageName(target.getPackageName());
        alias.setTargetActivity(targetActivityName);
        alias.configChanges = target.configChanges;
        alias.flags = target.flags;
        alias.privateFlags = target.privateFlags;
        alias.icon = target.icon;
        alias.logo = target.logo;
        alias.banner = target.banner;
        alias.labelRes = target.labelRes;
        alias.nonLocalizedLabel = target.nonLocalizedLabel;
        alias.launchMode = target.launchMode;
        alias.lockTaskLaunchMode = target.lockTaskLaunchMode;
        alias.documentLaunchMode = target.documentLaunchMode;
        alias.descriptionRes = target.descriptionRes;
        alias.screenOrientation = target.screenOrientation;
        alias.taskAffinity = target.taskAffinity;
        alias.theme = target.theme;
        alias.softInputMode = target.softInputMode;
        alias.uiOptions = target.uiOptions;
        alias.parentActivityName = target.parentActivityName;
        alias.maxRecents = target.maxRecents;
        alias.windowLayout = target.windowLayout;
        alias.resizeMode = target.resizeMode;
        alias.maxAspectRatio = target.maxAspectRatio;
        alias.minAspectRatio = target.minAspectRatio;
        alias.supportsSizeChanges = target.supportsSizeChanges;
        alias.requestedVrComponent = target.requestedVrComponent;
        alias.directBootAware = target.directBootAware;
        alias.setProcessName(target.getProcessName());
        return alias;

        // Not all attributes from the target ParsedActivity are copied to the alias.
        // Careful when adding an attribute and determine whether or not it should be copied.
//        alias.enabled = target.enabled;
//        alias.exported = target.exported;
//        alias.permission = target.permission;
//        alias.splitName = target.splitName;
//        alias.persistableMode = target.persistableMode;
//        alias.rotationAnimation = target.rotationAnimation;
//        alias.colorMode = target.colorMode;
//        alias.intents.addAll(target.intents);
//        alias.order = target.order;
//        alias.metaData = target.metaData;
    }

    public boolean isSupportsSizeChanges() {
        return supportsSizeChanges;
    }

    public ParsedActivity setColorMode(int colorMode) {
        this.colorMode = colorMode;
        return this;
    }

    public ParsedActivity setConfigChanges(int configChanges) {
        this.configChanges = configChanges;
        return this;
    }

    public ParsedActivity setDocumentLaunchMode(int documentLaunchMode) {
        this.documentLaunchMode = documentLaunchMode;
        return this;
    }

    public ParsedActivity setLaunchMode(int launchMode) {
        this.launchMode = launchMode;
        return this;
    }

    public ParsedActivity setLockTaskLaunchMode(int lockTaskLaunchMode) {
        this.lockTaskLaunchMode = lockTaskLaunchMode;
        return this;
    }

    public ParsedActivity setMaxAspectRatio(int resizeMode, float maxAspectRatio) {
        if (resizeMode == ActivityInfo.RESIZE_MODE_RESIZEABLE
                || resizeMode == ActivityInfo.RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION) {
            // Resizeable activities can be put in any aspect ratio.
            return this;
        }

        if (maxAspectRatio < 1.0f && maxAspectRatio != 0) {
            // Ignore any value lesser than 1.0.
            return this;
        }

        this.maxAspectRatio = maxAspectRatio;
        return this;
    }

    public ParsedActivity setMaxAspectRatio(Float maxAspectRatio) {
        this.maxAspectRatio = maxAspectRatio;
        return this;
    }

    public ParsedActivity setMaxRecents(int maxRecents) {
        this.maxRecents = maxRecents;
        return this;
    }

    public ParsedActivity setMinAspectRatio(int resizeMode, float minAspectRatio) {
        if (resizeMode == RESIZE_MODE_RESIZEABLE
                || resizeMode == RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION) {
            // Resizeable activities can be put in any aspect ratio.
            return this;
        }

        if (minAspectRatio < 1.0f && minAspectRatio != 0) {
            // Ignore any value lesser than 1.0.
            return this;
        }

        this.minAspectRatio = minAspectRatio;
        return this;
    }

    public ParsedActivity setMinAspectRatio(Float minAspectRatio) {
        this.minAspectRatio = minAspectRatio;
        return this;
    }

    public ParsedActivity setParentActivityName(String parentActivityName) {
        this.parentActivityName = parentActivityName;
        return this;
    }

    public ParsedActivity setPersistableMode(int persistableMode) {
        this.persistableMode = persistableMode;
        return this;
    }

    public ParsedActivity setPrivateFlags(int privateFlags) {
        this.privateFlags = privateFlags;
        return this;
    }

    public ParsedActivity setRequestedVrComponent(String requestedVrComponent) {
        this.requestedVrComponent = requestedVrComponent;
        return this;
    }

    public ParsedActivity setRotationAnimation(int rotationAnimation) {
        this.rotationAnimation = rotationAnimation;
        return this;
    }

    public ParsedActivity setScreenOrientation(int screenOrientation) {
        this.screenOrientation = screenOrientation;
        return this;
    }

    public ParsedActivity setSoftInputMode(int softInputMode) {
        this.softInputMode = softInputMode;
        return this;
    }

    public ParsedActivity setSupportsSizeChanges(boolean supportsSizeChanges) {
        this.supportsSizeChanges = supportsSizeChanges;
        return this;
    }

    public ParsedActivity setResizeMode(int resizeMode) {
        this.resizeMode = resizeMode;
        return this;
    }

    public ParsedActivity setTargetActivity(String targetActivity) {
        this.targetActivity = TextUtils.safeIntern(targetActivity);
        return this;
    }

    public ParsedActivity setPermission(String permission) {
        // Empty string must be converted to null
        this.permission = TextUtils.isEmpty(permission) ? null : permission.intern();
        return this;
    }

    public ParsedActivity setTaskAffinity(String taskAffinity) {
        this.taskAffinity = taskAffinity;
        return this;
    }

    public ParsedActivity setTheme(int theme) {
        this.theme = theme;
        return this;
    }

    public ParsedActivity setUiOptions(int uiOptions) {
        this.uiOptions = uiOptions;
        return this;
    }

    public ParsedActivity setWindowLayout(ActivityInfo.WindowLayout windowLayout) {
        this.windowLayout = windowLayout;
        return this;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("Activity{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(' ');
        ComponentName.appendShortString(sb, getPackageName(), getName());
        sb.append('}');
        return sb.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(this.theme);
        dest.writeInt(this.uiOptions);
        dest.writeString(this.targetActivity);
        dest.writeString(this.parentActivityName);
        dest.writeString(this.taskAffinity);
        dest.writeInt(this.privateFlags);
        sForInternedString.parcel(this.permission, dest, flags);
        dest.writeInt(this.launchMode);
        dest.writeInt(this.documentLaunchMode);
        dest.writeInt(this.maxRecents);
        dest.writeInt(this.configChanges);
        dest.writeInt(this.softInputMode);
        dest.writeInt(this.persistableMode);
        dest.writeInt(this.lockTaskLaunchMode);
        dest.writeInt(this.screenOrientation);
        dest.writeInt(this.resizeMode);
        dest.writeValue(this.maxAspectRatio);
        dest.writeValue(this.minAspectRatio);
        dest.writeBoolean(this.supportsSizeChanges);
        dest.writeString(this.requestedVrComponent);
        dest.writeInt(this.rotationAnimation);
        dest.writeInt(this.colorMode);
        dest.writeBundle(this.metaData);

        if (windowLayout != null) {
            dest.writeInt(1);
            windowLayout.writeToParcel(dest);
        } else {
            dest.writeBoolean(false);
        }
    }

    public ParsedActivity() {
    }

    protected ParsedActivity(Parcel in) {
        super(in);
        this.theme = in.readInt();
        this.uiOptions = in.readInt();
        this.targetActivity = in.readString();
        this.parentActivityName = in.readString();
        this.taskAffinity = in.readString();
        this.privateFlags = in.readInt();
        this.permission = sForInternedString.unparcel(in);
        this.launchMode = in.readInt();
        this.documentLaunchMode = in.readInt();
        this.maxRecents = in.readInt();
        this.configChanges = in.readInt();
        this.softInputMode = in.readInt();
        this.persistableMode = in.readInt();
        this.lockTaskLaunchMode = in.readInt();
        this.screenOrientation = in.readInt();
        this.resizeMode = in.readInt();
        this.maxAspectRatio = (Float) in.readValue(Float.class.getClassLoader());
        this.minAspectRatio = (Float) in.readValue(Float.class.getClassLoader());
        this.supportsSizeChanges = in.readBoolean();
        this.requestedVrComponent = in.readString();
        this.rotationAnimation = in.readInt();
        this.colorMode = in.readInt();
        this.metaData = in.readBundle();
        if (in.readBoolean()) {
            windowLayout = new ActivityInfo.WindowLayout(in);
        }
    }

    @NonNull
    public static final Parcelable.Creator<ParsedActivity> CREATOR = new Creator<ParsedActivity>() {
        @Override
        public ParsedActivity createFromParcel(Parcel source) {
            return new ParsedActivity(source);
        }

        @Override
        public ParsedActivity[] newArray(int size) {
            return new ParsedActivity[size];
        }
    };

    public int getTheme() {
        return theme;
    }

    public int getUiOptions() {
        return uiOptions;
    }

    @Nullable
    public String getTargetActivity() {
        return targetActivity;
    }

    @Nullable
    public String getParentActivityName() {
        return parentActivityName;
    }

    @Nullable
    public String getTaskAffinity() {
        return taskAffinity;
    }

    public int getPrivateFlags() {
        return privateFlags;
    }

    @Nullable
    public String getPermission() {
        return permission;
    }

    public int getLaunchMode() {
        return launchMode;
    }

    public int getDocumentLaunchMode() {
        return documentLaunchMode;
    }

    public int getMaxRecents() {
        return maxRecents;
    }

    public int getConfigChanges() {
        return configChanges;
    }

    public int getSoftInputMode() {
        return softInputMode;
    }

    public int getPersistableMode() {
        return persistableMode;
    }

    public int getLockTaskLaunchMode() {
        return lockTaskLaunchMode;
    }

    public int getScreenOrientation() {
        return screenOrientation;
    }

    public int getResizeMode() {
        return resizeMode;
    }

    @Nullable
    public Float getMaxAspectRatio() {
        return maxAspectRatio;
    }

    @Nullable
    public Float getMinAspectRatio() {
        return minAspectRatio;
    }

    @Nullable
    public String getRequestedVrComponent() {
        return requestedVrComponent;
    }

    public int getRotationAnimation() {
        return rotationAnimation;
    }

    public int getColorMode() {
        return colorMode;
    }

    @Nullable
    public ActivityInfo.WindowLayout getWindowLayout() {
        return windowLayout;
    }
}
