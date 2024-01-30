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

package com.android.internal.pm.pkg.component;

import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_UNSPECIFIED;

import static com.android.internal.pm.parsing.pkg.PackageImpl.sForInternedString;
import static com.android.internal.pm.parsing.pkg.PackageImpl.sForStringSet;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityTaskManager;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.ArraySet;

import com.android.internal.util.DataClass;
import com.android.internal.util.Parcelling.BuiltIn.ForInternedString;
import com.android.internal.pm.pkg.parsing.ParsingUtils;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * @hide
 **/
@DataClass(genGetters = true, genSetters = true, genBuilder = false, genParcelable = false)
public class ParsedActivityImpl extends ParsedMainComponentImpl implements ParsedActivity,
        Parcelable {

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
    @Nullable
    private Set<String> mKnownActivityEmbeddingCerts;

    private int launchMode;
    private int documentLaunchMode;
    private int maxRecents;
    private int configChanges;
    private int softInputMode;
    private int persistableMode;
    private int lockTaskLaunchMode;

    private int screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private int resizeMode = ActivityInfo.RESIZE_MODE_RESIZEABLE;

    private float maxAspectRatio = ParsingUtils.NOT_SET;
    private float minAspectRatio = ParsingUtils.NOT_SET;

    private boolean supportsSizeChanges;

    @Nullable
    private String requestedVrComponent;
    private int rotationAnimation = -1;
    private int colorMode;

    @Nullable
    private ActivityInfo.WindowLayout windowLayout;

    @Nullable
    private String mRequiredDisplayCategory;

    public ParsedActivityImpl(ParsedActivityImpl other) {
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
        this.mKnownActivityEmbeddingCerts = other.mKnownActivityEmbeddingCerts;
        this.mRequiredDisplayCategory = other.mRequiredDisplayCategory;
    }

    /**
     * Generate activity object that forwards user to App Details page automatically. This activity
     * should be invisible to user and user should not know or see it.
     */
    @NonNull
    public static ParsedActivityImpl makeAppDetailsActivity(String packageName, String processName,
            int uiOptions, String taskAffinity, boolean hardwareAccelerated) {
        ParsedActivityImpl activity = new ParsedActivityImpl();
        activity.setPackageName(packageName);
        activity.theme = android.R.style.Theme_NoDisplay;
        activity.setExported(true);
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

    @NonNull
    static ParsedActivityImpl makeAlias(String targetActivityName, ParsedActivity target) {
        ParsedActivityImpl alias = new ParsedActivityImpl();
        alias.setPackageName(target.getPackageName());
        alias.setTargetActivity(targetActivityName);
        alias.configChanges = target.getConfigChanges();
        alias.setFlags(target.getFlags());
        alias.privateFlags = target.getPrivateFlags();
        alias.setIcon(target.getIcon());
        alias.setLogo(target.getLogo());
        alias.setBanner(target.getBanner());
        alias.setLabelRes(target.getLabelRes());
        alias.setNonLocalizedLabel(target.getNonLocalizedLabel());
        alias.launchMode = target.getLaunchMode();
        alias.lockTaskLaunchMode = target.getLockTaskLaunchMode();
        alias.documentLaunchMode = target.getDocumentLaunchMode();
        alias.setDescriptionRes(target.getDescriptionRes());
        alias.screenOrientation = target.getScreenOrientation();
        alias.taskAffinity = target.getTaskAffinity();
        alias.theme = target.getTheme();
        alias.softInputMode = target.getSoftInputMode();
        alias.uiOptions = target.getUiOptions();
        alias.parentActivityName = target.getParentActivityName();
        alias.maxRecents = target.getMaxRecents();
        alias.windowLayout = target.getWindowLayout();
        alias.resizeMode = target.getResizeMode();
        alias.maxAspectRatio = target.getMaxAspectRatio();
        alias.minAspectRatio = target.getMinAspectRatio();
        alias.supportsSizeChanges = target.isSupportsSizeChanges();
        alias.requestedVrComponent = target.getRequestedVrComponent();
        alias.setDirectBootAware(target.isDirectBootAware());
        alias.setProcessName(target.getProcessName());
        alias.setRequiredDisplayCategory(target.getRequiredDisplayCategory());
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

    public ParsedActivityImpl setMaxAspectRatio(int resizeMode, float maxAspectRatio) {
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

    public ParsedActivityImpl setMinAspectRatio(int resizeMode, float minAspectRatio) {
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

    public ParsedActivityImpl setTargetActivity(String targetActivity) {
        this.targetActivity = TextUtils.safeIntern(targetActivity);
        return this;
    }

    public ParsedActivityImpl setPermission(String permission) {
        // Empty string must be converted to null
        this.permission = TextUtils.isEmpty(permission) ? null : permission.intern();
        return this;
    }

    @NonNull
    @Override
    public Set<String> getKnownActivityEmbeddingCerts() {
        return mKnownActivityEmbeddingCerts == null ? Collections.emptySet()
                : mKnownActivityEmbeddingCerts;
    }

    /**
     * Sets the trusted host certificates of apps that are allowed to embed this activity.
     */
    public void setKnownActivityEmbeddingCerts(@NonNull Set<String> knownActivityEmbeddingCerts) {
        // Convert the provided digest to upper case for consistent Set membership
        // checks when verifying the signing certificate digests of requesting apps.
        this.mKnownActivityEmbeddingCerts = new ArraySet<>();
        for (String knownCert : knownActivityEmbeddingCerts) {
            this.mKnownActivityEmbeddingCerts.add(knownCert.toUpperCase(Locale.US));
        }
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
        dest.writeBundle(this.getMetaData());

        if (windowLayout != null) {
            dest.writeInt(1);
            windowLayout.writeToParcel(dest);
        } else {
            dest.writeBoolean(false);
        }
        sForStringSet.parcel(this.mKnownActivityEmbeddingCerts, dest, flags);
        dest.writeString8(this.mRequiredDisplayCategory);
    }

    public ParsedActivityImpl() {
    }

    protected ParsedActivityImpl(Parcel in) {
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
        this.setMetaData(in.readBundle());
        if (in.readBoolean()) {
            windowLayout = new ActivityInfo.WindowLayout(in);
        }
        this.mKnownActivityEmbeddingCerts = sForStringSet.unparcel(in);
        this.mRequiredDisplayCategory = in.readString8();
    }

    @NonNull
    public static final Parcelable.Creator<ParsedActivityImpl> CREATOR =
            new Parcelable.Creator<ParsedActivityImpl>() {
        @Override
        public ParsedActivityImpl createFromParcel(Parcel source) {
            return new ParsedActivityImpl(source);
        }

        @Override
        public ParsedActivityImpl[] newArray(int size) {
            return new ParsedActivityImpl[size];
        }
    };



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/com/android/internal/pm/pkg/component/ParsedActivityImpl.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @DataClass.Generated.Member
    public ParsedActivityImpl(
            int theme,
            int uiOptions,
            @Nullable String targetActivity,
            @Nullable String parentActivityName,
            @Nullable String taskAffinity,
            int privateFlags,
            @Nullable String permission,
            @Nullable Set<String> knownActivityEmbeddingCerts,
            int launchMode,
            int documentLaunchMode,
            int maxRecents,
            int configChanges,
            int softInputMode,
            int persistableMode,
            int lockTaskLaunchMode,
            int screenOrientation,
            int resizeMode,
            float maxAspectRatio,
            float minAspectRatio,
            boolean supportsSizeChanges,
            @Nullable String requestedVrComponent,
            int rotationAnimation,
            int colorMode,
            @Nullable ActivityInfo.WindowLayout windowLayout,
            @Nullable String requiredDisplayCategory) {
        this.theme = theme;
        this.uiOptions = uiOptions;
        this.targetActivity = targetActivity;
        this.parentActivityName = parentActivityName;
        this.taskAffinity = taskAffinity;
        this.privateFlags = privateFlags;
        this.permission = permission;
        this.mKnownActivityEmbeddingCerts = knownActivityEmbeddingCerts;
        this.launchMode = launchMode;
        this.documentLaunchMode = documentLaunchMode;
        this.maxRecents = maxRecents;
        this.configChanges = configChanges;
        this.softInputMode = softInputMode;
        this.persistableMode = persistableMode;
        this.lockTaskLaunchMode = lockTaskLaunchMode;
        this.screenOrientation = screenOrientation;
        this.resizeMode = resizeMode;
        this.maxAspectRatio = maxAspectRatio;
        this.minAspectRatio = minAspectRatio;
        this.supportsSizeChanges = supportsSizeChanges;
        this.requestedVrComponent = requestedVrComponent;
        this.rotationAnimation = rotationAnimation;
        this.colorMode = colorMode;
        this.windowLayout = windowLayout;
        this.mRequiredDisplayCategory = requiredDisplayCategory;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public int getTheme() {
        return theme;
    }

    @DataClass.Generated.Member
    public int getUiOptions() {
        return uiOptions;
    }

    @DataClass.Generated.Member
    public @Nullable String getTargetActivity() {
        return targetActivity;
    }

    @DataClass.Generated.Member
    public @Nullable String getParentActivityName() {
        return parentActivityName;
    }

    @DataClass.Generated.Member
    public @Nullable String getTaskAffinity() {
        return taskAffinity;
    }

    @DataClass.Generated.Member
    public int getPrivateFlags() {
        return privateFlags;
    }

    @DataClass.Generated.Member
    public @Nullable String getPermission() {
        return permission;
    }

    @DataClass.Generated.Member
    public int getLaunchMode() {
        return launchMode;
    }

    @DataClass.Generated.Member
    public int getDocumentLaunchMode() {
        return documentLaunchMode;
    }

    @DataClass.Generated.Member
    public int getMaxRecents() {
        return maxRecents;
    }

    @DataClass.Generated.Member
    public int getConfigChanges() {
        return configChanges;
    }

    @DataClass.Generated.Member
    public int getSoftInputMode() {
        return softInputMode;
    }

    @DataClass.Generated.Member
    public int getPersistableMode() {
        return persistableMode;
    }

    @DataClass.Generated.Member
    public int getLockTaskLaunchMode() {
        return lockTaskLaunchMode;
    }

    @DataClass.Generated.Member
    public int getScreenOrientation() {
        return screenOrientation;
    }

    @DataClass.Generated.Member
    public int getResizeMode() {
        return resizeMode;
    }

    @DataClass.Generated.Member
    public float getMaxAspectRatio() {
        return maxAspectRatio;
    }

    @DataClass.Generated.Member
    public float getMinAspectRatio() {
        return minAspectRatio;
    }

    @DataClass.Generated.Member
    public boolean isSupportsSizeChanges() {
        return supportsSizeChanges;
    }

    @DataClass.Generated.Member
    public @Nullable String getRequestedVrComponent() {
        return requestedVrComponent;
    }

    @DataClass.Generated.Member
    public int getRotationAnimation() {
        return rotationAnimation;
    }

    @DataClass.Generated.Member
    public int getColorMode() {
        return colorMode;
    }

    @DataClass.Generated.Member
    public @Nullable ActivityInfo.WindowLayout getWindowLayout() {
        return windowLayout;
    }

    @DataClass.Generated.Member
    public @Nullable String getRequiredDisplayCategory() {
        return mRequiredDisplayCategory;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedActivityImpl setTheme( int value) {
        theme = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedActivityImpl setUiOptions( int value) {
        uiOptions = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedActivityImpl setParentActivityName(@NonNull String value) {
        parentActivityName = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedActivityImpl setTaskAffinity(@NonNull String value) {
        taskAffinity = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedActivityImpl setPrivateFlags( int value) {
        privateFlags = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedActivityImpl setLaunchMode( int value) {
        launchMode = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedActivityImpl setDocumentLaunchMode( int value) {
        documentLaunchMode = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedActivityImpl setMaxRecents( int value) {
        maxRecents = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedActivityImpl setConfigChanges( int value) {
        configChanges = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedActivityImpl setSoftInputMode( int value) {
        softInputMode = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedActivityImpl setPersistableMode( int value) {
        persistableMode = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedActivityImpl setLockTaskLaunchMode( int value) {
        lockTaskLaunchMode = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedActivityImpl setScreenOrientation( int value) {
        screenOrientation = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedActivityImpl setResizeMode( int value) {
        resizeMode = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedActivityImpl setMaxAspectRatio( float value) {
        maxAspectRatio = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedActivityImpl setMinAspectRatio( float value) {
        minAspectRatio = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedActivityImpl setSupportsSizeChanges( boolean value) {
        supportsSizeChanges = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedActivityImpl setRequestedVrComponent(@NonNull String value) {
        requestedVrComponent = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedActivityImpl setRotationAnimation( int value) {
        rotationAnimation = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedActivityImpl setColorMode( int value) {
        colorMode = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedActivityImpl setWindowLayout(@NonNull ActivityInfo.WindowLayout value) {
        windowLayout = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedActivityImpl setRequiredDisplayCategory(@NonNull String value) {
        mRequiredDisplayCategory = value;
        return this;
    }

    @DataClass.Generated(
            time = 1701338377709L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/com/android/internal/pm/pkg/component/ParsedActivityImpl.java",
            inputSignatures = "private  int theme\nprivate  int uiOptions\nprivate @android.annotation.Nullable @com.android.internal.util.DataClass.ParcelWith(com.android.internal.util.Parcelling.BuiltIn.ForInternedString.class) java.lang.String targetActivity\nprivate @android.annotation.Nullable @com.android.internal.util.DataClass.ParcelWith(com.android.internal.util.Parcelling.BuiltIn.ForInternedString.class) java.lang.String parentActivityName\nprivate @android.annotation.Nullable java.lang.String taskAffinity\nprivate  int privateFlags\nprivate @android.annotation.Nullable @com.android.internal.util.DataClass.ParcelWith(com.android.internal.util.Parcelling.BuiltIn.ForInternedString.class) java.lang.String permission\nprivate @android.annotation.Nullable java.util.Set<java.lang.String> mKnownActivityEmbeddingCerts\nprivate  int launchMode\nprivate  int documentLaunchMode\nprivate  int maxRecents\nprivate  int configChanges\nprivate  int softInputMode\nprivate  int persistableMode\nprivate  int lockTaskLaunchMode\nprivate  int screenOrientation\nprivate  int resizeMode\nprivate  float maxAspectRatio\nprivate  float minAspectRatio\nprivate  boolean supportsSizeChanges\nprivate @android.annotation.Nullable java.lang.String requestedVrComponent\nprivate  int rotationAnimation\nprivate  int colorMode\nprivate @android.annotation.Nullable android.content.pm.ActivityInfo.WindowLayout windowLayout\nprivate @android.annotation.Nullable java.lang.String mRequiredDisplayCategory\npublic static final @android.annotation.NonNull android.os.Parcelable.Creator<com.android.internal.pm.pkg.component.ParsedActivityImpl> CREATOR\npublic static @android.annotation.NonNull com.android.internal.pm.pkg.component.ParsedActivityImpl makeAppDetailsActivity(java.lang.String,java.lang.String,int,java.lang.String,boolean)\nstatic @android.annotation.NonNull com.android.internal.pm.pkg.component.ParsedActivityImpl makeAlias(java.lang.String,com.android.internal.pm.pkg.component.ParsedActivity)\npublic  com.android.internal.pm.pkg.component.ParsedActivityImpl setMaxAspectRatio(int,float)\npublic  com.android.internal.pm.pkg.component.ParsedActivityImpl setMinAspectRatio(int,float)\npublic  com.android.internal.pm.pkg.component.ParsedActivityImpl setTargetActivity(java.lang.String)\npublic  com.android.internal.pm.pkg.component.ParsedActivityImpl setPermission(java.lang.String)\npublic @android.annotation.NonNull @java.lang.Override java.util.Set<java.lang.String> getKnownActivityEmbeddingCerts()\npublic  void setKnownActivityEmbeddingCerts(java.util.Set<java.lang.String>)\npublic  java.lang.String toString()\npublic @java.lang.Override int describeContents()\npublic @java.lang.Override void writeToParcel(android.os.Parcel,int)\nclass ParsedActivityImpl extends com.android.internal.pm.pkg.component.ParsedMainComponentImpl implements [com.android.internal.pm.pkg.component.ParsedActivity, android.os.Parcelable]\n@com.android.internal.util.DataClass(genGetters=true, genSetters=true, genBuilder=false, genParcelable=false)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
