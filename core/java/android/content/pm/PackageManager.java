/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.content.pm;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.CheckResult;
import android.annotation.DrawableRes;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.LongDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.StringRes;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UserIdInt;
import android.annotation.XmlRes;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.AppDetailsActivity;
import android.app.PackageDeleteObserver;
import android.app.PackageInstallObserver;
import android.app.PropertyInvalidatedCache;
import android.app.admin.DevicePolicyManager;
import android.app.usage.StorageStatsManager;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.dex.ArtManager;
import android.content.pm.pkg.FrameworkPackageUserState;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.incremental.IncrementalManager;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.permission.PermissionManager;
import android.telephony.TelephonyManager;
import android.telephony.UiccCardInfo;
import android.telephony.gba.GbaService;
import android.telephony.ims.ImsService;
import android.telephony.ims.ProvisioningManager;
import android.telephony.ims.RcsUceAdapter;
import android.telephony.ims.SipDelegateManager;
import android.util.AndroidException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DataClass;

import dalvik.system.VMRuntime;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Class for retrieving various kinds of information related to the application
 * packages that are currently installed on the device.
 *
 * You can find this class through {@link Context#getPackageManager}.
 *
 * <p class="note"><strong>Note: </strong>If your app targets Android 11 (API level 30) or
 * higher, the methods in this class each return a filtered list of apps. Learn more about how to
 * <a href="/training/basics/intents/package-visibility">manage package visibility</a>.
 * </p>
 */
public abstract class PackageManager {
    private static final String TAG = "PackageManager";

    /** {@hide} */
    public static final boolean APPLY_DEFAULT_TO_DEVICE_PROTECTED_STORAGE = true;

    /** {@hide} */
    public static final boolean ENABLE_SHARED_UID_MIGRATION = true;

    /**
     * This exception is thrown when a given package, application, or component
     * name cannot be found.
     */
    public static class NameNotFoundException extends AndroidException {
        public NameNotFoundException() {
        }

        public NameNotFoundException(String name) {
            super(name);
        }
    }

    /**
     * &lt;application&gt; level {@link android.content.pm.PackageManager.Property} tag specifying
     * the XML resource ID containing an application's media capabilities XML file
     *
     * For example:
     * &lt;application&gt;
     *   &lt;property android:name="android.media.PROPERTY_MEDIA_CAPABILITIES"
     *     android:resource="@xml/media_capabilities"&gt;
     * &lt;application&gt;
     */
    public static final String PROPERTY_MEDIA_CAPABILITIES =
            "android.media.PROPERTY_MEDIA_CAPABILITIES";

    /**
     * Application level property that an app can specify to opt-out from having private data
     * directories both on the internal and external storages.
     *
     * <p>Changing the value of this property during app update is not supported, and such updates
     * will be rejected.
     *
     * <p>This should only be set by platform apps that know what they are doing.
     *
     * @hide
     */
    public static final String PROPERTY_NO_APP_DATA_STORAGE =
            "android.internal.PROPERTY_NO_APP_DATA_STORAGE";

    /**
     * A property value set within the manifest.
     * <p>
     * The value of a property will only have a single type, as defined by
     * the property itself.
     */
    public static final class Property implements Parcelable {
        private static final int TYPE_BOOLEAN = 1;
        private static final int TYPE_FLOAT = 2;
        private static final int TYPE_INTEGER = 3;
        private static final int TYPE_RESOURCE = 4;
        private static final int TYPE_STRING = 5;
        private final String mName;
        private final int mType;
        private final String mClassName;
        private final String mPackageName;
        private boolean mBooleanValue;
        private float mFloatValue;
        private int mIntegerValue;
        private String mStringValue;

        /** @hide */
        @VisibleForTesting
        public Property(@NonNull String name, int type,
                @NonNull String packageName, @Nullable String className) {
            assert name != null;
            assert type >= TYPE_BOOLEAN && type <= TYPE_STRING;
            assert packageName != null;
            this.mName = name;
            this.mType = type;
            this.mPackageName = packageName;
            this.mClassName = className;
        }
        /** @hide */
        public Property(@NonNull String name, boolean value,
                String packageName, String className) {
            this(name, TYPE_BOOLEAN, packageName, className);
            mBooleanValue = value;
        }
        /** @hide */
        public Property(@NonNull String name, float value,
                String packageName, String className) {
            this(name, TYPE_FLOAT, packageName, className);
            mFloatValue = value;
        }
        /** @hide */
        public Property(@NonNull String name, int value, boolean isResource,
                String packageName, String className) {
            this(name, isResource ? TYPE_RESOURCE : TYPE_INTEGER, packageName, className);
            mIntegerValue = value;
        }
        /** @hide */
        public Property(@NonNull String name, String value,
                String packageName, String className) {
            this(name, TYPE_STRING, packageName, className);
            mStringValue = value;
        }

        /** @hide */
        @VisibleForTesting
        public int getType() {
            return mType;
        }

        /**
         * Returns the name of the property.
         */
        @NonNull public String getName() {
            return mName;
        }

        /**
         * Returns the name of the package where this this property was defined.
         */
        @NonNull public String getPackageName() {
            return mPackageName;
        }

        /**
         * Returns the classname of the component where this property was defined.
         * <p>If the property was defined within and &lt;application&gt; tag, retutrns
         * {@code null}
         */
        @Nullable public String getClassName() {
            return mClassName;
        }

        /**
         * Returns the boolean value set for the property.
         * <p>If the property is not of a boolean type, returns {@code false}.
         */
        public boolean getBoolean() {
            return mBooleanValue;
        }

        /**
         * Returns {@code true} if the property is a boolean type. Otherwise {@code false}.
         */
        public boolean isBoolean() {
            return mType == TYPE_BOOLEAN;
        }

        /**
         * Returns the float value set for the property.
         * <p>If the property is not of a float type, returns {@code 0.0}.
         */
        public float getFloat() {
            return mFloatValue;
        }

        /**
         * Returns {@code true} if the property is a float type. Otherwise {@code false}.
         */
        public boolean isFloat() {
            return mType == TYPE_FLOAT;
        }

        /**
         * Returns the integer value set for the property.
         * <p>If the property is not of an integer type, returns {@code 0}.
         */
        public int getInteger() {
            return mType == TYPE_INTEGER ? mIntegerValue : 0;
        }

        /**
         * Returns {@code true} if the property is an integer type. Otherwise {@code false}.
         */
        public boolean isInteger() {
            return mType == TYPE_INTEGER;
        }

        /**
         * Returns the a resource id set for the property.
         * <p>If the property is not of a resource id type, returns {@code 0}.
         */
        public int getResourceId() {
            return mType == TYPE_RESOURCE ? mIntegerValue : 0;
        }

        /**
         * Returns {@code true} if the property is a resource id type. Otherwise {@code false}.
         */
        public boolean isResourceId() {
            return mType == TYPE_RESOURCE;
        }

        /**
         * Returns the a String value set for the property.
         * <p>If the property is not a String type, returns {@code null}.
         */
        @Nullable public String getString() {
            return mStringValue;
        }

        /**
         * Returns {@code true} if the property is a String type. Otherwise {@code false}.
         */
        public boolean isString() {
            return mType == TYPE_STRING;
        }

        /**
         * Adds a mapping from the given key to this property's value in the provided
         * {@link android.os.Bundle}. If the provided {@link android.os.Bundle} is
         * {@code null}, creates a new {@link android.os.Bundle}.
         * @hide
         */
        public Bundle toBundle(Bundle outBundle) {
            final Bundle b = outBundle == null || outBundle == Bundle.EMPTY
                    ? new Bundle() : outBundle;
            if (mType == TYPE_BOOLEAN) {
                b.putBoolean(mName, mBooleanValue);
            } else if (mType == TYPE_FLOAT) {
                b.putFloat(mName, mFloatValue);
            } else if (mType == TYPE_INTEGER) {
                b.putInt(mName, mIntegerValue);
            } else if (mType == TYPE_RESOURCE) {
                b.putInt(mName, mIntegerValue);
            } else if (mType == TYPE_STRING) {
                b.putString(mName, mStringValue);
            }
            return b;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeString(mName);
            dest.writeInt(mType);
            dest.writeString(mPackageName);
            dest.writeString(mClassName);
            if (mType == TYPE_BOOLEAN) {
                dest.writeBoolean(mBooleanValue);
            } else if (mType == TYPE_FLOAT) {
                dest.writeFloat(mFloatValue);
            } else if (mType == TYPE_INTEGER) {
                dest.writeInt(mIntegerValue);
            } else if (mType == TYPE_RESOURCE) {
                dest.writeInt(mIntegerValue);
            } else if (mType == TYPE_STRING) {
                dest.writeString(mStringValue);
            }
        }

        @NonNull
        public static final Creator<Property> CREATOR = new Creator<Property>() {
            @Override
            public Property createFromParcel(@NonNull Parcel source) {
                final String name = source.readString();
                final int type = source.readInt();
                final String packageName = source.readString();
                final String className = source.readString();
                if (type == TYPE_BOOLEAN) {
                    return new Property(name, source.readBoolean(), packageName, className);
                } else if (type == TYPE_FLOAT) {
                    return new Property(name, source.readFloat(), packageName, className);
                } else if (type == TYPE_INTEGER) {
                    return new Property(name, source.readInt(), false, packageName, className);
                } else if (type == TYPE_RESOURCE) {
                    return new Property(name, source.readInt(), true, packageName, className);
                } else if (type == TYPE_STRING) {
                    return new Property(name, source.readString(), packageName, className);
                }
                return null;
            }

            @Override
            public Property[] newArray(int size) {
                return new Property[size];
            }
        };
    }

    /**
     * The class containing the enabled setting of a package component.
     * <p>
     * This is used by the {@link #setComponentEnabledSettings(List)} to support the batch updates
     * of the enabled settings of components.
     *
     * @see #setComponentEnabledSettings(List)
     */
    @DataClass(genConstructor = false)
    public static final class ComponentEnabledSetting implements Parcelable {
        /**
         * The package name of the application to enable the setting.
         */
        private final @Nullable String mPackageName;

        /**
         * The component name of the application to enable the setting.
         */
        private final @Nullable ComponentName mComponentName;

        /**
         * The new enabled state
         */
        private final @EnabledState int mEnabledState;

        /**
         * The optional behavior flag
         */
        private final @EnabledFlags int mEnabledFlags;

        /**
         * Create an instance of the ComponentEnabledSetting for the component level's enabled
         * setting update.
         *
         * @param componentName The component name to update the enabled setting.
         * @param newState The new enabled state.
         * @param flags The optional behavior flags.
         */
        public ComponentEnabledSetting(@NonNull ComponentName componentName,
                @EnabledState int newState, @EnabledFlags int flags) {
            Objects.nonNull(componentName);
            mPackageName = null;
            mComponentName = componentName;
            mEnabledState = newState;
            mEnabledFlags = flags;
        }

        /**
         * Create an instance of the ComponentEnabledSetting for the application level's enabled
         * setting update.
         *
         * @param packageName The package name to update the enabled setting.
         * @param newState The new enabled state.
         * @param flags The optional behavior flags.
         * @hide
         */
        public ComponentEnabledSetting(@NonNull String packageName,
                @EnabledState int newState, @EnabledFlags int flags) {
            Objects.nonNull(packageName);
            mPackageName = packageName;
            mComponentName = null;
            mEnabledState = newState;
            mEnabledFlags = flags;
        }

        /**
         * Returns the package name of the setting.
         *
         * @return the package name.
         * @hide
         */
        public @NonNull String getPackageName() {
            if (isComponent()) {
                return mComponentName.getPackageName();
            }
            return mPackageName;
        }

        /**
         * Returns the component class name of the setting.
         *
         * @return the class name.
         * @hide
         */
        public @Nullable String getClassName() {
            if (isComponent()) {
                return mComponentName.getClassName();
            }
            return null;
        }

        /**
         * Whether or not this is for the component level's enabled setting update.
         *
         * @return {@code true} if it's the component level enabled setting update.
         * @hide
         */
        public boolean isComponent() {
            return mComponentName != null;
        }



        // Code below generated by codegen v1.0.23.
        //
        // DO NOT MODIFY!
        // CHECKSTYLE:OFF Generated code
        //
        // To regenerate run:
        // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/content/pm/PackageManager.java
        //
        // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
        //   Settings > Editor > Code Style > Formatter Control
        //@formatter:off


        /**
         * The component name of the application to enable the setting.
         */
        @DataClass.Generated.Member
        public @Nullable ComponentName getComponentName() {
            return mComponentName;
        }

        /**
         * The new enabled state
         */
        @DataClass.Generated.Member
        public @EnabledState int getEnabledState() {
            return mEnabledState;
        }

        /**
         * The optional behavior flag
         */
        @DataClass.Generated.Member
        public @EnabledFlags int getEnabledFlags() {
            return mEnabledFlags;
        }

        @Override
        @DataClass.Generated.Member
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            // You can override field parcelling by defining methods like:
            // void parcelFieldName(Parcel dest, int flags) { ... }

            byte flg = 0;
            if (mPackageName != null) flg |= 0x1;
            if (mComponentName != null) flg |= 0x2;
            dest.writeByte(flg);
            if (mPackageName != null) dest.writeString(mPackageName);
            if (mComponentName != null) dest.writeTypedObject(mComponentName, flags);
            dest.writeInt(mEnabledState);
            dest.writeInt(mEnabledFlags);
        }

        @Override
        @DataClass.Generated.Member
        public int describeContents() { return 0; }

        /** @hide */
        @SuppressWarnings({"unchecked", "RedundantCast"})
        @DataClass.Generated.Member
        /* package-private */ ComponentEnabledSetting(@NonNull Parcel in) {
            // You can override field unparcelling by defining methods like:
            // static FieldType unparcelFieldName(Parcel in) { ... }

            byte flg = in.readByte();
            String packageName = (flg & 0x1) == 0 ? null : in.readString();
            ComponentName componentName = (flg & 0x2) == 0 ? null : (ComponentName) in.readTypedObject(ComponentName.CREATOR);
            int enabledState = in.readInt();
            int enabledFlags = in.readInt();

            this.mPackageName = packageName;
            this.mComponentName = componentName;
            this.mEnabledState = enabledState;
            com.android.internal.util.AnnotationValidations.validate(
                    EnabledState.class, null, mEnabledState);
            this.mEnabledFlags = enabledFlags;
            com.android.internal.util.AnnotationValidations.validate(
                    EnabledFlags.class, null, mEnabledFlags);

            // onConstructed(); // You can define this method to get a callback
        }

        @DataClass.Generated.Member
        public static final @NonNull Parcelable.Creator<ComponentEnabledSetting> CREATOR
                = new Parcelable.Creator<ComponentEnabledSetting>() {
            @Override
            public ComponentEnabledSetting[] newArray(int size) {
                return new ComponentEnabledSetting[size];
            }

            @Override
            public ComponentEnabledSetting createFromParcel(@NonNull Parcel in) {
                return new ComponentEnabledSetting(in);
            }
        };

        @DataClass.Generated(
                time = 1628668290863L,
                codegenVersion = "1.0.23",
                sourceFile = "frameworks/base/core/java/android/content/pm/PackageManager.java",
                inputSignatures = "private final @android.annotation.Nullable java.lang.String mPackageName\nprivate final @android.annotation.Nullable android.content.ComponentName mComponentName\nprivate final @android.content.pm.PackageManager.EnabledState int mEnabledState\nprivate final @android.content.pm.PackageManager.EnabledFlags int mEnabledFlags\npublic @android.annotation.NonNull java.lang.String getPackageName()\npublic @android.annotation.Nullable java.lang.String getClassName()\npublic  boolean isComponent()\nclass ComponentEnabledSetting extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genConstructor=false)")
        @Deprecated
        private void __metadata() {}


        //@formatter:on
        // End of generated code

    }

    /**
     * Listener for changes in permissions granted to a UID.
     *
     * @hide
     */
    @SystemApi
    public interface OnPermissionsChangedListener {

        /**
         * Called when the permissions for a UID change.
         * @param uid The UID with a change.
         */
        public void onPermissionsChanged(int uid);
    }

    /** @hide */
    public static final int TYPE_UNKNOWN = 0;
    /** @hide */
    public static final int TYPE_ACTIVITY = 1;
    /** @hide */
    public static final int TYPE_RECEIVER = 2;
    /** @hide */
    public static final int TYPE_SERVICE = 3;
    /** @hide */
    public static final int TYPE_PROVIDER = 4;
    /** @hide */
    public static final int TYPE_APPLICATION = 5;
    /** @hide */
    @IntDef(prefix = { "TYPE_" }, value = {
            TYPE_UNKNOWN,
            TYPE_ACTIVITY,
            TYPE_RECEIVER,
            TYPE_SERVICE,
            TYPE_PROVIDER,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ComponentType {}

    /** @hide */
    @IntDef(prefix = { "TYPE_" }, value = {
            TYPE_UNKNOWN,
            TYPE_ACTIVITY,
            TYPE_RECEIVER,
            TYPE_SERVICE,
            TYPE_PROVIDER,
            TYPE_APPLICATION,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PropertyLocation {}

    /**
     * As a guiding principle:
     * <p>
     * {@code GET_} flags are used to request additional data that may have been
     * elided to save wire space.
     * <p>
     * {@code MATCH_} flags are used to include components or packages that
     * would have otherwise been omitted from a result set by current system
     * state.
     */

    /** @hide */
    @LongDef(flag = true, prefix = { "GET_", "MATCH_" }, value = {
            GET_ACTIVITIES,
            GET_CONFIGURATIONS,
            GET_GIDS,
            GET_INSTRUMENTATION,
            GET_INTENT_FILTERS,
            GET_META_DATA,
            GET_PERMISSIONS,
            GET_PROVIDERS,
            GET_RECEIVERS,
            GET_SERVICES,
            GET_SHARED_LIBRARY_FILES,
            GET_SIGNATURES,
            GET_SIGNING_CERTIFICATES,
            GET_URI_PERMISSION_PATTERNS,
            MATCH_UNINSTALLED_PACKAGES,
            MATCH_DISABLED_COMPONENTS,
            MATCH_DISABLED_UNTIL_USED_COMPONENTS,
            MATCH_SYSTEM_ONLY,
            MATCH_FACTORY_ONLY,
            MATCH_DEBUG_TRIAGED_MISSING,
            MATCH_INSTANT,
            MATCH_APEX,
            GET_DISABLED_COMPONENTS,
            GET_DISABLED_UNTIL_USED_COMPONENTS,
            GET_UNINSTALLED_PACKAGES,
            MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS,
            GET_ATTRIBUTIONS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PackageInfoFlagsBits {}

    /** @hide */
    @LongDef(flag = true, prefix = { "GET_", "MATCH_" }, value = {
            GET_META_DATA,
            GET_SHARED_LIBRARY_FILES,
            MATCH_UNINSTALLED_PACKAGES,
            MATCH_SYSTEM_ONLY,
            MATCH_DEBUG_TRIAGED_MISSING,
            MATCH_DISABLED_COMPONENTS,
            MATCH_DISABLED_UNTIL_USED_COMPONENTS,
            MATCH_INSTANT,
            MATCH_STATIC_SHARED_AND_SDK_LIBRARIES,
            GET_DISABLED_UNTIL_USED_COMPONENTS,
            GET_UNINSTALLED_PACKAGES,
            MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS,
            MATCH_APEX,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ApplicationInfoFlagsBits {}

    /** @hide */
    @LongDef(flag = true, prefix = { "GET_", "MATCH_" }, value = {
            GET_META_DATA,
            GET_SHARED_LIBRARY_FILES,
            MATCH_ALL,
            MATCH_DEBUG_TRIAGED_MISSING,
            MATCH_DEFAULT_ONLY,
            MATCH_DISABLED_COMPONENTS,
            MATCH_DISABLED_UNTIL_USED_COMPONENTS,
            MATCH_DIRECT_BOOT_AUTO,
            MATCH_DIRECT_BOOT_AWARE,
            MATCH_DIRECT_BOOT_UNAWARE,
            MATCH_SYSTEM_ONLY,
            MATCH_UNINSTALLED_PACKAGES,
            MATCH_INSTANT,
            MATCH_STATIC_SHARED_AND_SDK_LIBRARIES,
            GET_DISABLED_COMPONENTS,
            GET_DISABLED_UNTIL_USED_COMPONENTS,
            GET_UNINSTALLED_PACKAGES,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ComponentInfoFlagsBits {}

    /** @hide */
    @LongDef(flag = true, prefix = { "GET_", "MATCH_" }, value = {
            GET_META_DATA,
            GET_RESOLVED_FILTER,
            GET_SHARED_LIBRARY_FILES,
            MATCH_ALL,
            MATCH_DEBUG_TRIAGED_MISSING,
            MATCH_DISABLED_COMPONENTS,
            MATCH_DISABLED_UNTIL_USED_COMPONENTS,
            MATCH_DEFAULT_ONLY,
            MATCH_DIRECT_BOOT_AUTO,
            MATCH_DIRECT_BOOT_AWARE,
            MATCH_DIRECT_BOOT_UNAWARE,
            MATCH_SYSTEM_ONLY,
            MATCH_UNINSTALLED_PACKAGES,
            MATCH_INSTANT,
            GET_DISABLED_COMPONENTS,
            GET_DISABLED_UNTIL_USED_COMPONENTS,
            GET_UNINSTALLED_PACKAGES,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ResolveInfoFlagsBits {}

    /** @hide */
    @IntDef(flag = true, prefix = { "GET_", "MATCH_" }, value = {
            MATCH_ALL,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface InstalledModulesFlags {}

    /** @hide */
    @IntDef(flag = true, prefix = { "GET_", "MATCH_" }, value = {
            GET_META_DATA,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PermissionInfoFlags {}

    /** @hide */
    @IntDef(flag = true, prefix = { "GET_", "MATCH_" }, value = {
            GET_META_DATA,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PermissionGroupInfoFlags {}

    /** @hide */
    @IntDef(flag = true, prefix = { "GET_", "MATCH_" }, value = {
            GET_META_DATA,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface InstrumentationInfoFlags {}

    /**
     * {@link PackageInfo} flag: return information about
     * activities in the package in {@link PackageInfo#activities}.
     */
    public static final int GET_ACTIVITIES              = 0x00000001;

    /**
     * {@link PackageInfo} flag: return information about
     * intent receivers in the package in
     * {@link PackageInfo#receivers}.
     */
    public static final int GET_RECEIVERS               = 0x00000002;

    /**
     * {@link PackageInfo} flag: return information about
     * services in the package in {@link PackageInfo#services}.
     */
    public static final int GET_SERVICES                = 0x00000004;

    /**
     * {@link PackageInfo} flag: return information about
     * content providers in the package in
     * {@link PackageInfo#providers}.
     */
    public static final int GET_PROVIDERS               = 0x00000008;

    /**
     * {@link PackageInfo} flag: return information about
     * instrumentation in the package in
     * {@link PackageInfo#instrumentation}.
     */
    public static final int GET_INSTRUMENTATION         = 0x00000010;

    /**
     * {@link PackageInfo} flag: return information about the
     * intent filters supported by the activity.
     *
     * @deprecated The platform does not support getting {@link IntentFilter}s for the package.
     */
    @Deprecated
    public static final int GET_INTENT_FILTERS          = 0x00000020;

    /**
     * {@link PackageInfo} flag: return information about the
     * signatures included in the package.
     *
     * @deprecated use {@code GET_SIGNING_CERTIFICATES} instead
     */
    @Deprecated
    public static final int GET_SIGNATURES          = 0x00000040;

    /**
     * {@link ResolveInfo} flag: return the IntentFilter that
     * was matched for a particular ResolveInfo in
     * {@link ResolveInfo#filter}.
     */
    public static final int GET_RESOLVED_FILTER         = 0x00000040;

    /**
     * {@link ComponentInfo} flag: return the {@link ComponentInfo#metaData}
     * data {@link android.os.Bundle}s that are associated with a component.
     * This applies for any API returning a ComponentInfo subclass.
     */
    public static final int GET_META_DATA               = 0x00000080;

    /**
     * {@link PackageInfo} flag: return the
     * {@link PackageInfo#gids group ids} that are associated with an
     * application.
     * This applies for any API returning a PackageInfo class, either
     * directly or nested inside of another.
     */
    public static final int GET_GIDS                    = 0x00000100;

    /**
     * @deprecated replaced with {@link #MATCH_DISABLED_COMPONENTS}
     */
    @Deprecated
    public static final int GET_DISABLED_COMPONENTS = 0x00000200;

    /**
     * {@link PackageInfo} flag: include disabled components in the returned info.
     */
    public static final int MATCH_DISABLED_COMPONENTS = 0x00000200;

    /**
     * {@link ApplicationInfo} flag: return the
     * {@link ApplicationInfo#sharedLibraryFiles paths to the shared libraries}
     * that are associated with an application.
     * This applies for any API returning an ApplicationInfo class, either
     * directly or nested inside of another.
     */
    public static final int GET_SHARED_LIBRARY_FILES    = 0x00000400;

    /**
     * {@link ProviderInfo} flag: return the
     * {@link ProviderInfo#uriPermissionPatterns URI permission patterns}
     * that are associated with a content provider.
     * This applies for any API returning a ProviderInfo class, either
     * directly or nested inside of another.
     */
    public static final int GET_URI_PERMISSION_PATTERNS  = 0x00000800;
    /**
     * {@link PackageInfo} flag: return information about
     * permissions in the package in
     * {@link PackageInfo#permissions}.
     */
    public static final int GET_PERMISSIONS               = 0x00001000;

    /**
     * @deprecated replaced with {@link #MATCH_UNINSTALLED_PACKAGES}
     */
    @Deprecated
    public static final int GET_UNINSTALLED_PACKAGES = 0x00002000;

    /**
     * Flag parameter to retrieve some information about all applications (even
     * uninstalled ones) which have data directories. This state could have
     * resulted if applications have been deleted with flag
     * {@code DELETE_KEEP_DATA} with a possibility of being replaced or
     * reinstalled in future.
     * <p>
     * Note: this flag may cause less information about currently installed
     * applications to be returned.
     * <p>
     * Note: use of this flag requires the android.permission.QUERY_ALL_PACKAGES
     * permission to see uninstalled packages.
     */
    public static final int MATCH_UNINSTALLED_PACKAGES = 0x00002000;

    /**
     * {@link PackageInfo} flag: return information about
     * hardware preferences in
     * {@link PackageInfo#configPreferences PackageInfo.configPreferences},
     * and requested features in {@link PackageInfo#reqFeatures} and
     * {@link PackageInfo#featureGroups}.
     */
    public static final int GET_CONFIGURATIONS = 0x00004000;

    /**
     * @deprecated replaced with {@link #MATCH_DISABLED_UNTIL_USED_COMPONENTS}.
     */
    @Deprecated
    public static final int GET_DISABLED_UNTIL_USED_COMPONENTS = 0x00008000;

    /**
     * {@link PackageInfo} flag: include disabled components which are in
     * that state only because of {@link #COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED}
     * in the returned info.  Note that if you set this flag, applications
     * that are in this disabled state will be reported as enabled.
     */
    public static final int MATCH_DISABLED_UNTIL_USED_COMPONENTS = 0x00008000;

    /**
     * Resolution and querying flag: if set, only filters that support the
     * {@link android.content.Intent#CATEGORY_DEFAULT} will be considered for
     * matching.  This is a synonym for including the CATEGORY_DEFAULT in your
     * supplied Intent.
     */
    public static final int MATCH_DEFAULT_ONLY  = 0x00010000;

    /**
     * Querying flag: if set and if the platform is doing any filtering of the
     * results, then the filtering will not happen. This is a synonym for saying
     * that all results should be returned.
     * <p>
     * <em>This flag should be used with extreme care.</em>
     */
    public static final int MATCH_ALL = 0x00020000;

    /**
     * Querying flag: match components which are direct boot <em>unaware</em> in
     * the returned info, regardless of the current user state.
     * <p>
     * When neither {@link #MATCH_DIRECT_BOOT_AWARE} nor
     * {@link #MATCH_DIRECT_BOOT_UNAWARE} are specified, the default behavior is
     * to match only runnable components based on the user state. For example,
     * when a user is started but credentials have not been presented yet, the
     * user is running "locked" and only {@link #MATCH_DIRECT_BOOT_AWARE}
     * components are returned. Once the user credentials have been presented,
     * the user is running "unlocked" and both {@link #MATCH_DIRECT_BOOT_AWARE}
     * and {@link #MATCH_DIRECT_BOOT_UNAWARE} components are returned.
     *
     * @see UserManager#isUserUnlocked()
     */
    public static final int MATCH_DIRECT_BOOT_UNAWARE = 0x00040000;

    /**
     * Querying flag: match components which are direct boot <em>aware</em> in
     * the returned info, regardless of the current user state.
     * <p>
     * When neither {@link #MATCH_DIRECT_BOOT_AWARE} nor
     * {@link #MATCH_DIRECT_BOOT_UNAWARE} are specified, the default behavior is
     * to match only runnable components based on the user state. For example,
     * when a user is started but credentials have not been presented yet, the
     * user is running "locked" and only {@link #MATCH_DIRECT_BOOT_AWARE}
     * components are returned. Once the user credentials have been presented,
     * the user is running "unlocked" and both {@link #MATCH_DIRECT_BOOT_AWARE}
     * and {@link #MATCH_DIRECT_BOOT_UNAWARE} components are returned.
     *
     * @see UserManager#isUserUnlocked()
     */
    public static final int MATCH_DIRECT_BOOT_AWARE = 0x00080000;

    /**
     * Querying flag: include only components from applications that are marked
     * with {@link ApplicationInfo#FLAG_SYSTEM}.
     */
    public static final int MATCH_SYSTEM_ONLY = 0x00100000;

    /**
     * Internal {@link PackageInfo} flag: include only components on the system image.
     * This will not return information on any unbundled update to system components.
     * @hide
     */
    @SystemApi
    public static final int MATCH_FACTORY_ONLY = 0x00200000;

    /**
     * Allows querying of packages installed for any user, not just the specific one. This flag
     * is only meant for use by apps that have INTERACT_ACROSS_USERS permission.
     * @hide
     */
    @SystemApi
    public static final int MATCH_ANY_USER = 0x00400000;

    /**
     * Combination of MATCH_ANY_USER and MATCH_UNINSTALLED_PACKAGES to mean any known
     * package.
     * @hide
     */
    @TestApi
    public static final int MATCH_KNOWN_PACKAGES = MATCH_UNINSTALLED_PACKAGES | MATCH_ANY_USER;

    /**
     * Internal {@link PackageInfo} flag: include components that are part of an
     * instant app. By default, instant app components are not matched.
     * @hide
     */
    @SystemApi
    public static final int MATCH_INSTANT = 0x00800000;

    /**
     * Internal {@link PackageInfo} flag: include only components that are exposed to
     * instant apps. Matched components may have been either explicitly or implicitly
     * exposed.
     * @hide
     */
    public static final int MATCH_VISIBLE_TO_INSTANT_APP_ONLY = 0x01000000;

    /**
     * Internal {@link PackageInfo} flag: include only components that have been
     * explicitly exposed to instant apps.
     * @hide
     */
    public static final int MATCH_EXPLICITLY_VISIBLE_ONLY = 0x02000000;

    /**
     * Internal {@link PackageInfo} flag: include static shared and SDK libraries.
     * Apps that depend on static shared/SDK libs can always access the version
     * of the lib they depend on. System/shell/root can access all shared
     * libs regardless of dependency but need to explicitly ask for them
     * via this flag.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int MATCH_STATIC_SHARED_AND_SDK_LIBRARIES = 0x04000000;

    /**
     * {@link PackageInfo} flag: return the signing certificates associated with
     * this package.  Each entry is a signing certificate that the package
     * has proven it is authorized to use, usually a past signing certificate from
     * which it has rotated.
     */
    public static final int GET_SIGNING_CERTIFICATES = 0x08000000;

    /**
     * Querying flag: automatically match components based on their Direct Boot
     * awareness and the current user state.
     * <p>
     * Since the default behavior is to automatically apply the current user
     * state, this is effectively a sentinel value that doesn't change the
     * output of any queries based on its presence or absence.
     * <p>
     * Instead, this value can be useful in conjunction with
     * {@link android.os.StrictMode.VmPolicy.Builder#detectImplicitDirectBoot()}
     * to detect when a caller is relying on implicit automatic matching,
     * instead of confirming the explicit behavior they want, using a
     * combination of these flags:
     * <ul>
     * <li>{@link #MATCH_DIRECT_BOOT_AWARE}
     * <li>{@link #MATCH_DIRECT_BOOT_UNAWARE}
     * <li>{@link #MATCH_DIRECT_BOOT_AUTO}
     * </ul>
     */
    public static final int MATCH_DIRECT_BOOT_AUTO = 0x10000000;

    /**
     * {@link PackageInfo} flag: return all attributions declared in the package manifest
     */
    public static final int GET_ATTRIBUTIONS = 0x80000000;

    /** @hide */
    @Deprecated
    public static final int MATCH_DEBUG_TRIAGED_MISSING = MATCH_DIRECT_BOOT_AUTO;

    /**
     * {@link PackageInfo} flag: include system apps that are in the uninstalled state and have
     * been set to be hidden until installed via {@link #setSystemAppState}.
     * @hide
     */
    @SystemApi
    public static final int MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS =  0x20000000;

    /**
     * {@link PackageInfo} flag: include APEX packages that are currently
     * installed. In APEX terminology, this corresponds to packages that are
     * currently active, i.e. mounted and available to other processes of the OS.
     * In particular, this flag alone will not match APEX files that are staged
     * for activation at next reboot.
     */
    public static final int MATCH_APEX = 0x40000000;

    /**
     * Flag for {@link #addCrossProfileIntentFilter}: if this flag is set: when
     * resolving an intent that matches the {@code CrossProfileIntentFilter},
     * the current profile will be skipped. Only activities in the target user
     * can respond to the intent.
     *
     * @hide
     */
    public static final int SKIP_CURRENT_PROFILE = 0x00000002;

    /**
     * Flag for {@link #addCrossProfileIntentFilter}: if this flag is set:
     * activities in the other profiles can respond to the intent only if no activity with
     * non-negative priority in current profile can respond to the intent.
     * @hide
     */
    public static final int ONLY_IF_NO_MATCH_FOUND = 0x00000004;

    /** @hide */
    @IntDef(flag = true, prefix = { "MODULE_" }, value = {
            MODULE_APEX_NAME,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ModuleInfoFlags {}

    /**
     * Flag for {@link #getModuleInfo}: allow ModuleInfo to be retrieved using the apex module
     * name, rather than the package name.
     *
     * @hide
     */
    @SystemApi
    public static final int MODULE_APEX_NAME = 0x00000001;

    /** @hide */
    @IntDef(prefix = { "PERMISSION_" }, value = {
            PERMISSION_GRANTED,
            PERMISSION_DENIED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PermissionResult {}

    /**
     * Permission check result: this is returned by {@link #checkPermission}
     * if the permission has been granted to the given package.
     */
    public static final int PERMISSION_GRANTED = 0;

    /**
     * Permission check result: this is returned by {@link #checkPermission}
     * if the permission has not been granted to the given package.
     */
    public static final int PERMISSION_DENIED = -1;

    /** @hide */
    @IntDef(prefix = { "SIGNATURE_" }, value = {
            SIGNATURE_MATCH,
            SIGNATURE_NEITHER_SIGNED,
            SIGNATURE_FIRST_NOT_SIGNED,
            SIGNATURE_SECOND_NOT_SIGNED,
            SIGNATURE_NO_MATCH,
            SIGNATURE_UNKNOWN_PACKAGE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SignatureResult {}

    /**
     * Signature check result: this is returned by {@link #checkSignatures}
     * if all signatures on the two packages match.
     */
    public static final int SIGNATURE_MATCH = 0;

    /**
     * Signature check result: this is returned by {@link #checkSignatures}
     * if neither of the two packages is signed.
     */
    public static final int SIGNATURE_NEITHER_SIGNED = 1;

    /**
     * Signature check result: this is returned by {@link #checkSignatures}
     * if the first package is not signed but the second is.
     */
    public static final int SIGNATURE_FIRST_NOT_SIGNED = -1;

    /**
     * Signature check result: this is returned by {@link #checkSignatures}
     * if the second package is not signed but the first is.
     */
    public static final int SIGNATURE_SECOND_NOT_SIGNED = -2;

    /**
     * Signature check result: this is returned by {@link #checkSignatures}
     * if not all signatures on both packages match.
     */
    public static final int SIGNATURE_NO_MATCH = -3;

    /**
     * Signature check result: this is returned by {@link #checkSignatures}
     * if either of the packages are not valid.
     */
    public static final int SIGNATURE_UNKNOWN_PACKAGE = -4;

    /** @hide */
    @IntDef(prefix = { "COMPONENT_ENABLED_STATE_" }, value = {
            COMPONENT_ENABLED_STATE_DEFAULT,
            COMPONENT_ENABLED_STATE_ENABLED,
            COMPONENT_ENABLED_STATE_DISABLED,
            COMPONENT_ENABLED_STATE_DISABLED_USER,
            COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EnabledState {}

    /**
     * Flag for {@link #setApplicationEnabledSetting(String, int, int)} and
     * {@link #setComponentEnabledSetting(ComponentName, int, int)}: This
     * component or application is in its default enabled state (as specified in
     * its manifest).
     * <p>
     * Explicitly setting the component state to this value restores it's
     * enabled state to whatever is set in the manifest.
     */
    public static final int COMPONENT_ENABLED_STATE_DEFAULT = 0;

    /**
     * Flag for {@link #setApplicationEnabledSetting(String, int, int)}
     * and {@link #setComponentEnabledSetting(ComponentName, int, int)}: This
     * component or application has been explictily enabled, regardless of
     * what it has specified in its manifest.
     */
    public static final int COMPONENT_ENABLED_STATE_ENABLED = 1;

    /**
     * Flag for {@link #setApplicationEnabledSetting(String, int, int)}
     * and {@link #setComponentEnabledSetting(ComponentName, int, int)}: This
     * component or application has been explicitly disabled, regardless of
     * what it has specified in its manifest.
     */
    public static final int COMPONENT_ENABLED_STATE_DISABLED = 2;

    /**
     * Flag for {@link #setApplicationEnabledSetting(String, int, int)} only: The
     * user has explicitly disabled the application, regardless of what it has
     * specified in its manifest.  Because this is due to the user's request,
     * they may re-enable it if desired through the appropriate system UI.  This
     * option currently <strong>cannot</strong> be used with
     * {@link #setComponentEnabledSetting(ComponentName, int, int)}.
     */
    public static final int COMPONENT_ENABLED_STATE_DISABLED_USER = 3;

    /**
     * Flag for {@link #setApplicationEnabledSetting(String, int, int)} only: This
     * application should be considered, until the point where the user actually
     * wants to use it.  This means that it will not normally show up to the user
     * (such as in the launcher), but various parts of the user interface can
     * use {@link #GET_DISABLED_UNTIL_USED_COMPONENTS} to still see it and allow
     * the user to select it (as for example an IME, device admin, etc).  Such code,
     * once the user has selected the app, should at that point also make it enabled.
     * This option currently <strong>can not</strong> be used with
     * {@link #setComponentEnabledSetting(ComponentName, int, int)}.
     */
    public static final int COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED = 4;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "ROLLBACK_DATA_POLICY_" }, value = {
            ROLLBACK_DATA_POLICY_RESTORE,
            ROLLBACK_DATA_POLICY_WIPE,
            ROLLBACK_DATA_POLICY_RETAIN
    })
    public @interface RollbackDataPolicy {}

    /**
     * User data will be backed up during install and restored during rollback.
     *
     * @hide
     */
    @SystemApi
    public static final int ROLLBACK_DATA_POLICY_RESTORE = 0;

    /**
     * User data won't be backed up during install but will be wiped out during rollback.
     *
     * @hide
     */
    @SystemApi
    public static final int ROLLBACK_DATA_POLICY_WIPE = 1;

    /**
     * User data won't be backed up during install and will remain unchanged during rollback.
     *
     * @hide
     */
    @SystemApi
    public static final int ROLLBACK_DATA_POLICY_RETAIN = 2;

    /** @hide */
    @IntDef(flag = true, prefix = { "INSTALL_" }, value = {
            INSTALL_REPLACE_EXISTING,
            INSTALL_ALLOW_TEST,
            INSTALL_INTERNAL,
            INSTALL_FROM_ADB,
            INSTALL_ALL_USERS,
            INSTALL_REQUEST_DOWNGRADE,
            INSTALL_GRANT_RUNTIME_PERMISSIONS,
            INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS,
            INSTALL_FORCE_VOLUME_UUID,
            INSTALL_FORCE_PERMISSION_PROMPT,
            INSTALL_INSTANT_APP,
            INSTALL_DONT_KILL_APP,
            INSTALL_FULL_APP,
            INSTALL_ALLOCATE_AGGRESSIVE,
            INSTALL_VIRTUAL_PRELOAD,
            INSTALL_APEX,
            INSTALL_ENABLE_ROLLBACK,
            INSTALL_ALLOW_DOWNGRADE,
            INSTALL_STAGED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface InstallFlags {}

    /**
     * Flag parameter for {@link #installPackage} to indicate that you want to
     * replace an already installed package, if one exists.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static final int INSTALL_REPLACE_EXISTING = 0x00000002;

    /**
     * Flag parameter for {@link #installPackage} to indicate that you want to
     * allow test packages (those that have set android:testOnly in their
     * manifest) to be installed.
     * @hide
     */
    public static final int INSTALL_ALLOW_TEST = 0x00000004;

    /**
     * Flag parameter for {@link #installPackage} to indicate that this package
     * must be installed to internal storage.
     *
     * @hide
     */
    public static final int INSTALL_INTERNAL = 0x00000010;

    /**
     * Flag parameter for {@link #installPackage} to indicate that this install
     * was initiated via ADB.
     *
     * @hide
     */
    public static final int INSTALL_FROM_ADB = 0x00000020;

    /**
     * Flag parameter for {@link #installPackage} to indicate that this install
     * should immediately be visible to all users.
     *
     * @hide
     */
    public static final int INSTALL_ALL_USERS = 0x00000040;

    /**
     * Flag parameter for {@link #installPackage} to indicate that an upgrade to a lower version
     * of a package than currently installed has been requested.
     *
     * <p>Note that this flag doesn't guarantee that downgrade will be performed. That decision
     * depends
     * on whenever:
     * <ul>
     * <li>An app is debuggable.
     * <li>Or a build is debuggable.
     * <li>Or {@link #INSTALL_ALLOW_DOWNGRADE} is set.
     * </ul>
     *
     * @hide
     */
    public static final int INSTALL_REQUEST_DOWNGRADE = 0x00000080;

    /**
     * Flag parameter for {@link #installPackage} to indicate that all runtime
     * permissions should be granted to the package. If {@link #INSTALL_ALL_USERS}
     * is set the runtime permissions will be granted to all users, otherwise
     * only to the owner.
     *
     * @hide
     */
    public static final int INSTALL_GRANT_RUNTIME_PERMISSIONS = 0x00000100;

    /**
     * Flag parameter for {@link #installPackage} to indicate that all restricted
     * permissions should be whitelisted. If {@link #INSTALL_ALL_USERS}
     * is set the restricted permissions will be whitelisted for all users, otherwise
     * only to the owner.
     *
     * <p>
     * <strong>Note: </strong>In retrospect it would have been preferred to use
     * more inclusive terminology when naming this API. Similar APIs added will
     * refrain from using the term "whitelist".
     * </p>
     *
     * @hide
     */
    public static final int INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS = 0x00400000;

    /** {@hide} */
    public static final int INSTALL_FORCE_VOLUME_UUID = 0x00000200;

    /**
     * Flag parameter for {@link #installPackage} to indicate that we always want to force
     * the prompt for permission approval. This overrides any special behaviour for internal
     * components.
     *
     * @hide
     */
    public static final int INSTALL_FORCE_PERMISSION_PROMPT = 0x00000400;

    /**
     * Flag parameter for {@link #installPackage} to indicate that this package is
     * to be installed as a lightweight "ephemeral" app.
     *
     * @hide
     */
    public static final int INSTALL_INSTANT_APP = 0x00000800;

    /**
     * Flag parameter for {@link #installPackage} to indicate that this package contains
     * a feature split to an existing application and the existing application should not
     * be killed during the installation process.
     *
     * @hide
     */
    public static final int INSTALL_DONT_KILL_APP = 0x00001000;

    /**
     * Flag parameter for {@link #installPackage} to indicate that this package is
     * to be installed as a heavy weight app. This is fundamentally the opposite of
     * {@link #INSTALL_INSTANT_APP}.
     *
     * @hide
     */
    public static final int INSTALL_FULL_APP = 0x00004000;

    /**
     * Flag parameter for {@link #installPackage} to indicate that this package
     * is critical to system health or security, meaning the system should use
     * {@link StorageManager#FLAG_ALLOCATE_AGGRESSIVE} internally.
     *
     * @hide
     */
    public static final int INSTALL_ALLOCATE_AGGRESSIVE = 0x00008000;

    /**
     * Flag parameter for {@link #installPackage} to indicate that this package
     * is a virtual preload.
     *
     * @hide
     */
    public static final int INSTALL_VIRTUAL_PRELOAD = 0x00010000;

    /**
     * Flag parameter for {@link #installPackage} to indicate that this package
     * is an APEX package
     *
     * @hide
     */
    public static final int INSTALL_APEX = 0x00020000;

    /**
     * Flag parameter for {@link #installPackage} to indicate that rollback
     * should be enabled for this install.
     *
     * @hide
     */
    public static final int INSTALL_ENABLE_ROLLBACK = 0x00040000;

    /**
     * Flag parameter for {@link #installPackage} to indicate that package verification should be
     * disabled for this package.
     *
     * @hide
     */
    public static final int INSTALL_DISABLE_VERIFICATION = 0x00080000;

    /**
     * Flag parameter for {@link #installPackage} to indicate that
     * {@link #INSTALL_REQUEST_DOWNGRADE} should be allowed.
     *
     * @hide
     */
    public static final int INSTALL_ALLOW_DOWNGRADE = 0x00100000;

    /**
     * Flag parameter for {@link #installPackage} to indicate that this package
     * is being installed as part of a staged install.
     *
     * @hide
     */
    public static final int INSTALL_STAGED = 0x00200000;

    /**
     * Flag parameter for {@link #installPackage} to indicate that check whether given APEX can be
     * updated should be disabled for this install.
     * @hide
     */
    public static final int INSTALL_DISABLE_ALLOWED_APEX_UPDATE_CHECK = 0x00400000;

    /** @hide */
    @IntDef(flag = true, value = {
            DONT_KILL_APP,
            SYNCHRONOUS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EnabledFlags {}

    /**
     * Flag parameter for
     * {@link #setComponentEnabledSetting(android.content.ComponentName, int, int)} to indicate
     * that you don't want to kill the app containing the component.  Be careful when you set this
     * since changing component states can make the containing application's behavior unpredictable.
     */
    public static final int DONT_KILL_APP = 0x00000001;

    /**
     * Flag parameter for
     * {@link #setComponentEnabledSetting(android.content.ComponentName, int, int)} to indicate
     * that the given user's package restrictions state will be serialised to disk after the
     * component state has been updated. Note that this is synchronous disk access, so calls using
     * this flag should be run on a background thread.
     */
    public static final int SYNCHRONOUS = 0x00000002;

    /** @hide */
    @IntDef(prefix = { "INSTALL_REASON_" }, value = {
            INSTALL_REASON_UNKNOWN,
            INSTALL_REASON_POLICY,
            INSTALL_REASON_DEVICE_RESTORE,
            INSTALL_REASON_DEVICE_SETUP,
            INSTALL_REASON_USER,
            INSTALL_REASON_ROLLBACK
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface InstallReason {}

    /**
     * Code indicating that the reason for installing this package is unknown.
     */
    public static final int INSTALL_REASON_UNKNOWN = 0;

    /**
     * Code indicating that this package was installed due to enterprise policy.
     */
    public static final int INSTALL_REASON_POLICY = 1;

    /**
     * Code indicating that this package was installed as part of restoring from another device.
     */
    public static final int INSTALL_REASON_DEVICE_RESTORE = 2;

    /**
     * Code indicating that this package was installed as part of device setup.
     */
    public static final int INSTALL_REASON_DEVICE_SETUP = 3;

    /**
     * Code indicating that the package installation was initiated by the user.
     */
    public static final int INSTALL_REASON_USER = 4;

    /**
     * Code indicating that the package installation was a rollback initiated by RollbackManager.
     *
     * @hide
     */
    public static final int INSTALL_REASON_ROLLBACK = 5;

    /** @hide */
    @IntDef(prefix = { "INSTALL_SCENARIO_" }, value = {
            INSTALL_SCENARIO_DEFAULT,
            INSTALL_SCENARIO_FAST,
            INSTALL_SCENARIO_BULK,
            INSTALL_SCENARIO_BULK_SECONDARY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface InstallScenario {}

    /**
     * A value to indicate the lack of CUJ information, disabling all installation scenario logic.
     */
    public static final int INSTALL_SCENARIO_DEFAULT = 0;

    /**
     * Installation scenario providing the fastest “install button to launch" experience possible.
     */
    public static final int INSTALL_SCENARIO_FAST = 1;

    /**
     * Installation scenario indicating a bulk operation with the desired result of a fully
     * optimized application.  If the system is busy or resources are scarce the system will
     * perform less work to avoid impacting system health.
     *
     * Examples of bulk installation scenarios might include device restore, background updates of
     * multiple applications, or user-triggered updates for all applications.
     *
     * The decision to use BULK or BULK_SECONDARY should be based on the desired user experience.
     * BULK_SECONDARY operations may take less time to complete but, when they do, will produce
     * less optimized applications.  The device state (e.g. memory usage or battery status) should
     * not be considered when making this decision as those factors are taken into account by the
     * Package Manager when acting on the installation scenario.
     */
    public static final int INSTALL_SCENARIO_BULK = 2;

    /**
     * Installation scenario indicating a bulk operation that prioritizes minimal system health
     * impact over application optimization.  The application may undergo additional optimization
     * if the system is idle and system resources are abundant.  The more elements of a bulk
     * operation that are marked BULK_SECONDARY, the faster the entire bulk operation will be.
     *
     * See the comments for INSTALL_SCENARIO_BULK for more information.
     */
    public static final int INSTALL_SCENARIO_BULK_SECONDARY = 3;

    /** @hide */
    @IntDef(prefix = { "UNINSTALL_REASON_" }, value = {
            UNINSTALL_REASON_UNKNOWN,
            UNINSTALL_REASON_USER_TYPE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UninstallReason {}

    /**
     * Code indicating that the reason for uninstalling this package is unknown.
     * @hide
     */
    public static final int UNINSTALL_REASON_UNKNOWN = 0;

    /**
     * Code indicating that this package was uninstalled due to the type of user.
     * See UserSystemPackageInstaller
     * @hide
     */
    public static final int UNINSTALL_REASON_USER_TYPE = 1;

    /**
     * @hide
     */
    public static final int INSTALL_UNKNOWN = 0;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * on success.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_SUCCEEDED = 1;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the package is already installed.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_ALREADY_EXISTS = -1;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the package archive file is invalid.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_INVALID_APK = -2;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the URI passed in is invalid.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_INVALID_URI = -3;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the package manager service found that the device didn't have enough storage space to
     * install the app.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_INSUFFICIENT_STORAGE = -4;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if a package is already installed with the same name.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_DUPLICATE_PACKAGE = -5;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the requested shared user does not exist.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_NO_SHARED_USER = -6;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if a previously installed package of the same name has a different signature than the new
     * package (and the old package's data was not removed).
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_UPDATE_INCOMPATIBLE = -7;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package is requested a shared user which is already installed on the device and
     * does not have matching signature.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_SHARED_USER_INCOMPATIBLE = -8;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package uses a shared library that is not available.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_MISSING_SHARED_LIBRARY = -9;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * when the package being replaced is a system app and the caller didn't provide the
     * {@link #DELETE_SYSTEM_APP} flag.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_REPLACE_COULDNT_DELETE = -10;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package failed while optimizing and validating its dex files, either because there
     * was not enough storage or the validation failed.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_DEXOPT = -11;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package failed because the current SDK version is older than that required by the
     * package.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_OLDER_SDK = -12;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package failed because it contains a content provider with the same authority as a
     * provider already installed in the system.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_CONFLICTING_PROVIDER = -13;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package failed because the current SDK version is newer than that required by the
     * package.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_NEWER_SDK = -14;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package failed because it has specified that it is a test-only package and the
     * caller has not supplied the {@link #INSTALL_ALLOW_TEST} flag.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_TEST_ONLY = -15;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the package being installed contains native code, but none that is compatible with the
     * device's CPU_ABI.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_CPU_ABI_INCOMPATIBLE = -16;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package uses a feature that is not available.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_MISSING_FEATURE = -17;

    // ------ Errors related to sdcard
    /**
     * Installation return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if a secure container mount point couldn't be
     * accessed on external media.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_CONTAINER_ERROR = -18;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package couldn't be installed in the specified install location.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_INVALID_INSTALL_LOCATION = -19;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package couldn't be installed in the specified install location because the media
     * is not available.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_MEDIA_UNAVAILABLE = -20;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package couldn't be installed because the verification timed out.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_VERIFICATION_TIMEOUT = -21;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package couldn't be installed because the verification did not succeed.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_VERIFICATION_FAILURE = -22;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the package changed from what the calling program expected.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_PACKAGE_CHANGED = -23;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package is assigned a different UID than it previously held.
     *
     * @hide
     */
    public static final int INSTALL_FAILED_UID_CHANGED = -24;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package has an older version code than the currently installed package.
     *
     * @hide
     */
    public static final int INSTALL_FAILED_VERSION_DOWNGRADE = -25;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the old package has target SDK high enough to support runtime permission and the new
     * package has target SDK low enough to not support runtime permissions.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_PERMISSION_MODEL_DOWNGRADE = -26;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package attempts to downgrade the target sandbox version of the app.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_SANDBOX_VERSION_DOWNGRADE = -27;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package requires at least one split and it was not provided.
     *
     * @hide
     */
    public static final int INSTALL_FAILED_MISSING_SPLIT = -28;

    /**
     * Installation parse return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the parser was given a path that is not a
     * file, or does not end with the expected '.apk' extension.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_NOT_APK = -100;

    /**
     * Installation parse return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the parser was unable to retrieve the
     * AndroidManifest.xml file.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_BAD_MANIFEST = -101;

    /**
     * Installation parse return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the parser encountered an unexpected
     * exception.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION = -102;

    /**
     * Installation parse return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the parser did not find any certificates in
     * the .apk.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_NO_CERTIFICATES = -103;

    /**
     * Installation parse return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the parser found inconsistent certificates on
     * the files in the .apk.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES = -104;

    /**
     * Installation parse return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the parser encountered a
     * CertificateEncodingException in one of the files in the .apk.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING = -105;

    /**
     * Installation parse return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the parser encountered a bad or missing
     * package name in the manifest.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME = -106;

    /**
     * Installation parse return code: tthis is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the parser encountered a bad shared user id
     * name in the manifest.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID = -107;

    /**
     * Installation parse return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the parser encountered some structural
     * problem in the manifest.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_MANIFEST_MALFORMED = -108;

    /**
     * Installation parse return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the parser did not find any actionable tags
     * (instrumentation or application) in the manifest.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_MANIFEST_EMPTY = -109;

    /**
     * Installation failed return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the system failed to install the package
     * because of system issues.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_INTERNAL_ERROR = -110;

    /**
     * Installation failed return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the system failed to install the package
     * because the user is restricted from installing apps.
     *
     * @hide
     */
    public static final int INSTALL_FAILED_USER_RESTRICTED = -111;

    /**
     * Installation failed return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the system failed to install the package
     * because it is attempting to define a permission that is already defined by some existing
     * package.
     * <p>
     * The package name of the app which has already defined the permission is passed to a
     * {@link PackageInstallObserver}, if any, as the {@link #EXTRA_FAILURE_EXISTING_PACKAGE} string
     * extra; and the name of the permission being redefined is passed in the
     * {@link #EXTRA_FAILURE_EXISTING_PERMISSION} string extra.
     *
     * @hide
     */
    public static final int INSTALL_FAILED_DUPLICATE_PERMISSION = -112;

    /**
     * Installation failed return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the system failed to install the package
     * because its packaged native code did not match any of the ABIs supported by the system.
     *
     * @hide
     */
    public static final int INSTALL_FAILED_NO_MATCHING_ABIS = -113;

    /**
     * Internal return code for NativeLibraryHelper methods to indicate that the package
     * being processed did not contain any native code. This is placed here only so that
     * it can belong to the same value space as the other install failure codes.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static final int NO_NATIVE_LIBRARIES = -114;

    /** {@hide} */
    public static final int INSTALL_FAILED_ABORTED = -115;

    /**
     * Installation failed return code: install type is incompatible with some other
     * installation flags supplied for the operation; or other circumstances such as trying
     * to upgrade a system app via an Incremental or instant app install.
     * @hide
     */
    public static final int INSTALL_FAILED_SESSION_INVALID = -116;

    /**
     * Installation parse return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the dex metadata file is invalid or
     * if there was no matching apk file for a dex metadata file.
     *
     * @hide
     */
    public static final int INSTALL_FAILED_BAD_DEX_METADATA = -117;

    /**
     * Installation parse return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if there is any signature problem.
     *
     * @hide
     */
    public static final int INSTALL_FAILED_BAD_SIGNATURE = -118;

    /**
     * Installation failed return code: a new staged session was attempted to be committed while
     * there is already one in-progress or new session has package that is already staged.
     *
     * @hide
     */
    public static final int INSTALL_FAILED_OTHER_STAGED_SESSION_IN_PROGRESS = -119;

    /**
     * Installation failed return code: one of the child sessions does not match the parent session
     * in respect to staged or rollback enabled parameters.
     *
     * @hide
     */
    public static final int INSTALL_FAILED_MULTIPACKAGE_INCONSISTENCY = -120;

    /**
     * Installation failed return code: the required installed version code
     * does not match the currently installed package version code.
     *
     * @hide
     */
    public static final int INSTALL_FAILED_WRONG_INSTALLED_VERSION = -121;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package failed because it contains a request to use a process that was not
     * explicitly defined as part of its &lt;processes&gt; tag.
     *
     * @hide
     */
    public static final int INSTALL_FAILED_PROCESS_NOT_DEFINED = -122;

    /**
     * Installation parse return code: system is in a minimal boot state, and the parser only
     * allows the package with {@code coreApp} manifest attribute to be a valid application.
     *
     * @hide
     */
    public static final int INSTALL_PARSE_FAILED_ONLY_COREAPP_ALLOWED = -123;

    /**
     * Installation failed return code: the {@code resources.arsc} of one of the APKs being
     * installed is compressed or not aligned on a 4-byte boundary. Resource tables that cannot be
     * memory mapped exert excess memory pressure on the system and drastically slow down
     * construction of {@link Resources} objects.
     *
     * @hide
     */
    public static final int INSTALL_PARSE_FAILED_RESOURCES_ARSC_COMPRESSED = -124;

    /**
     * Installation failed return code: the package was skipped and should be ignored.
     *
     * The reason for the skip is undefined.
     * @hide
     */
    public static final int INSTALL_PARSE_FAILED_SKIPPED = -125;

    /**
     * Installation failed return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the system failed to install the package
     * because it is attempting to define a permission group that is already defined by some
     * existing package.
     *
     * @hide
     */
    public static final int INSTALL_FAILED_DUPLICATE_PERMISSION_GROUP = -126;

    /**
     * Installation failed return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the system failed to install the package
     * because it is attempting to define a permission in a group that does not exists or that is
     * defined by an packages with an incompatible certificate.
     *
     * @hide
     */
    public static final int INSTALL_FAILED_BAD_PERMISSION_GROUP = -127;

    /**
     * Installation failed return code: an error occurred during the activation phase of this
     * session.
     *
     * @hide
     */
    public static final int INSTALL_ACTIVATION_FAILED = -128;

    /** @hide */
    @IntDef(flag = true, prefix = { "DELETE_" }, value = {
            DELETE_KEEP_DATA,
            DELETE_ALL_USERS,
            DELETE_SYSTEM_APP,
            DELETE_DONT_KILL_APP,
            DELETE_CHATTY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DeleteFlags {}

    /**
     * Flag parameter for {@link #deletePackage} to indicate that you don't want to delete the
     * package's data directory.
     *
     * @hide
     */
    public static final int DELETE_KEEP_DATA = 0x00000001;

    /**
     * Flag parameter for {@link #deletePackage} to indicate that you want the
     * package deleted for all users.
     *
     * @hide
     */
    public static final int DELETE_ALL_USERS = 0x00000002;

    /**
     * Flag parameter for {@link #deletePackage} to indicate that, if you are calling
     * uninstall on a system that has been updated, then don't do the normal process
     * of uninstalling the update and rolling back to the older system version (which
     * needs to happen for all users); instead, just mark the app as uninstalled for
     * the current user.
     *
     * @hide
     */
    public static final int DELETE_SYSTEM_APP = 0x00000004;

    /**
     * Flag parameter for {@link #deletePackage} to indicate that, if you are calling
     * uninstall on a package that is replaced to provide new feature splits, the
     * existing application should not be killed during the removal process.
     *
     * @hide
     */
    public static final int DELETE_DONT_KILL_APP = 0x00000008;

    /**
     * Flag parameter for {@link #deletePackage} to indicate that package deletion
     * should be chatty.
     *
     * @hide
     */
    public static final int DELETE_CHATTY = 0x80000000;

    /**
     * Return code for when package deletion succeeds. This is passed to the
     * {@link IPackageDeleteObserver} if the system succeeded in deleting the
     * package.
     *
     * @hide
     */
    public static final int DELETE_SUCCEEDED = 1;

    /**
     * Deletion failed return code: this is passed to the
     * {@link IPackageDeleteObserver} if the system failed to delete the package
     * for an unspecified reason.
     *
     * @hide
     */
    public static final int DELETE_FAILED_INTERNAL_ERROR = -1;

    /**
     * Deletion failed return code: this is passed to the
     * {@link IPackageDeleteObserver} if the system failed to delete the package
     * because it is the active DevicePolicy manager.
     *
     * @hide
     */
    public static final int DELETE_FAILED_DEVICE_POLICY_MANAGER = -2;

    /**
     * Deletion failed return code: this is passed to the
     * {@link IPackageDeleteObserver} if the system failed to delete the package
     * since the user is restricted.
     *
     * @hide
     */
    public static final int DELETE_FAILED_USER_RESTRICTED = -3;

    /**
     * Deletion failed return code: this is passed to the
     * {@link IPackageDeleteObserver} if the system failed to delete the package
     * because a profile or device owner has marked the package as
     * uninstallable.
     *
     * @hide
     */
    public static final int DELETE_FAILED_OWNER_BLOCKED = -4;

    /** {@hide} */
    public static final int DELETE_FAILED_ABORTED = -5;

    /**
     * Deletion failed return code: this is passed to the
     * {@link IPackageDeleteObserver} if the system failed to delete the package
     * because the packge is a shared library used by other installed packages.
     * {@hide} */
    public static final int DELETE_FAILED_USED_SHARED_LIBRARY = -6;

    /**
     * Deletion failed return code: this is passed to the
     * {@link IPackageDeleteObserver} if the system failed to delete the package
     * because there is an app pinned.
     *
     * @hide
     */
    public static final int DELETE_FAILED_APP_PINNED = -7;

    /**
     * Return code that is passed to the {@link IPackageMoveObserver} when the
     * package has been successfully moved by the system.
     *
     * @hide
     */
    public static final int MOVE_SUCCEEDED = -100;

    /**
     * Error code that is passed to the {@link IPackageMoveObserver} when the
     * package hasn't been successfully moved by the system because of
     * insufficient memory on specified media.
     *
     * @hide
     */
    public static final int MOVE_FAILED_INSUFFICIENT_STORAGE = -1;

    /**
     * Error code that is passed to the {@link IPackageMoveObserver} if the
     * specified package doesn't exist.
     *
     * @hide
     */
    public static final int MOVE_FAILED_DOESNT_EXIST = -2;

    /**
     * Error code that is passed to the {@link IPackageMoveObserver} if the
     * specified package cannot be moved since its a system package.
     *
     * @hide
     */
    public static final int MOVE_FAILED_SYSTEM_PACKAGE = -3;

    /**
     * Error code that is passed to the {@link IPackageMoveObserver} if the
     * specified package cannot be moved to the specified location.
     *
     * @hide
     */
    public static final int MOVE_FAILED_INVALID_LOCATION = -5;

    /**
     * Error code that is passed to the {@link IPackageMoveObserver} if the
     * specified package cannot be moved to the specified location.
     *
     * @hide
     */
    public static final int MOVE_FAILED_INTERNAL_ERROR = -6;

    /**
     * Error code that is passed to the {@link IPackageMoveObserver} if the
     * specified package already has an operation pending in the queue.
     *
     * @hide
     */
    public static final int MOVE_FAILED_OPERATION_PENDING = -7;

    /**
     * Error code that is passed to the {@link IPackageMoveObserver} if the
     * specified package cannot be moved since it contains a device admin.
     *
     * @hide
     */
    public static final int MOVE_FAILED_DEVICE_ADMIN = -8;

    /**
     * Error code that is passed to the {@link IPackageMoveObserver} if system does not allow
     * non-system apps to be moved to internal storage.
     *
     * @hide
     */
    public static final int MOVE_FAILED_3RD_PARTY_NOT_ALLOWED_ON_INTERNAL = -9;

    /** @hide */
    public static final int MOVE_FAILED_LOCKED_USER = -10;

    /**
     * Flag parameter for {@link #movePackage} to indicate that
     * the package should be moved to internal storage if its
     * been installed on external media.
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int MOVE_INTERNAL = 0x00000001;

    /**
     * Flag parameter for {@link #movePackage} to indicate that
     * the package should be moved to external media.
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int MOVE_EXTERNAL_MEDIA = 0x00000002;

    /** {@hide} */
    public static final String EXTRA_MOVE_ID = "android.content.pm.extra.MOVE_ID";

    /**
     * Usable by the required verifier as the {@code verificationCode} argument
     * for {@link PackageManager#verifyPendingInstall} to indicate that it will
     * allow the installation to proceed without any of the optional verifiers
     * needing to vote.
     *
     * @hide
     */
    public static final int VERIFICATION_ALLOW_WITHOUT_SUFFICIENT = 2;

    /**
     * Used as the {@code verificationCode} argument for
     * {@link PackageManager#verifyPendingInstall} to indicate that the calling
     * package verifier allows the installation to proceed.
     */
    public static final int VERIFICATION_ALLOW = 1;

    /**
     * Used as the {@code verificationCode} argument for
     * {@link PackageManager#verifyPendingInstall} to indicate the calling
     * package verifier does not vote to allow the installation to proceed.
     */
    public static final int VERIFICATION_REJECT = -1;

    /**
     * Used as the {@code verificationCode} argument for
     * {@link PackageManager#verifyIntentFilter} to indicate that the calling
     * IntentFilter Verifier confirms that the IntentFilter is verified.
     *
     * @deprecated Use {@link DomainVerificationManager} APIs.
     * @hide
     */
    @Deprecated
    @SystemApi
    public static final int INTENT_FILTER_VERIFICATION_SUCCESS = 1;

    /**
     * Used as the {@code verificationCode} argument for
     * {@link PackageManager#verifyIntentFilter} to indicate that the calling
     * IntentFilter Verifier confirms that the IntentFilter is NOT verified.
     *
     * @deprecated Use {@link DomainVerificationManager} APIs.
     * @hide
     */
    @Deprecated
    @SystemApi
    public static final int INTENT_FILTER_VERIFICATION_FAILURE = -1;

    /**
     * Internal status code to indicate that an IntentFilter verification result is not specified.
     *
     * @deprecated Use {@link DomainVerificationManager} APIs.
     * @hide
     */
    @Deprecated
    @SystemApi
    public static final int INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED = 0;

    /**
     * Used as the {@code status} argument for
     * {@link #updateIntentVerificationStatusAsUser} to indicate that the User
     * will always be prompted the Intent Disambiguation Dialog if there are two
     * or more Intent resolved for the IntentFilter's domain(s).
     *
     * @deprecated Use {@link DomainVerificationManager} APIs.
     * @hide
     */
    @Deprecated
    @SystemApi
    public static final int INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK = 1;

    /**
     * Used as the {@code status} argument for
     * {@link #updateIntentVerificationStatusAsUser} to indicate that the User
     * will never be prompted the Intent Disambiguation Dialog if there are two
     * or more resolution of the Intent. The default App for the domain(s)
     * specified in the IntentFilter will also ALWAYS be used.
     *
     * @deprecated Use {@link DomainVerificationManager} APIs.
     * @hide
     */
    @Deprecated
    @SystemApi
    public static final int INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS = 2;

    /**
     * Used as the {@code status} argument for
     * {@link #updateIntentVerificationStatusAsUser} to indicate that the User
     * may be prompted the Intent Disambiguation Dialog if there are two or more
     * Intent resolved. The default App for the domain(s) specified in the
     * IntentFilter will also NEVER be presented to the User.
     *
     * @deprecated Use {@link DomainVerificationManager} APIs.
     * @hide
     */
    @Deprecated
    @SystemApi
    public static final int INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER = 3;

    /**
     * Used as the {@code status} argument for
     * {@link #updateIntentVerificationStatusAsUser} to indicate that this app
     * should always be considered as an ambiguous candidate for handling the
     * matching Intent even if there are other candidate apps in the "always"
     * state. Put another way: if there are any 'always ask' apps in a set of
     * more than one candidate app, then a disambiguation is *always* presented
     * even if there is another candidate app with the 'always' state.
     *
     * @deprecated Use {@link DomainVerificationManager} APIs.
     * @hide
     */
    @Deprecated
    @SystemApi
    public static final int INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS_ASK = 4;

    /**
     * Can be used as the {@code millisecondsToDelay} argument for
     * {@link PackageManager#extendVerificationTimeout}. This is the
     * maximum time {@code PackageManager} waits for the verification
     * agent to return (in milliseconds).
     */
    public static final long MAXIMUM_VERIFICATION_TIMEOUT = 60*60*1000;

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: The device's
     * audio pipeline is low-latency, more suitable for audio applications sensitive to delays or
     * lag in sound input or output.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_AUDIO_LOW_LATENCY = "android.hardware.audio.low_latency";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes at least one form of audio
     * output, as defined in the Android Compatibility Definition Document (CDD)
     * <a href="https://source.android.com/compatibility/android-cdd#7_8_audio">section 7.8 Audio</a>.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_AUDIO_OUTPUT = "android.hardware.audio.output";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device has professional audio level of functionality and performance.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_AUDIO_PRO = "android.hardware.audio.pro";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device is capable of communicating with
     * other devices via Bluetooth.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_BLUETOOTH = "android.hardware.bluetooth";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device is capable of communicating with
     * other devices via Bluetooth Low Energy radio.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_BLUETOOTH_LE = "android.hardware.bluetooth_le";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device has a camera facing away
     * from the screen.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CAMERA = "android.hardware.camera";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device's camera supports auto-focus.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CAMERA_AUTOFOCUS = "android.hardware.camera.autofocus";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device has at least one camera pointing in
     * some direction, or can support an external camera being connected to it.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CAMERA_ANY = "android.hardware.camera.any";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device can support having an external camera connected to it.
     * The external camera may not always be connected or available to applications to use.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CAMERA_EXTERNAL = "android.hardware.camera.external";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device's camera supports flash.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CAMERA_FLASH = "android.hardware.camera.flash";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device has a front facing camera.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CAMERA_FRONT = "android.hardware.camera.front";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: At least one
     * of the cameras on the device supports the
     * {@link android.hardware.camera2.CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL full hardware}
     * capability level.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CAMERA_LEVEL_FULL = "android.hardware.camera.level.full";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: At least one
     * of the cameras on the device supports the
     * {@link android.hardware.camera2.CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR manual sensor}
     * capability level.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CAMERA_CAPABILITY_MANUAL_SENSOR =
            "android.hardware.camera.capability.manual_sensor";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: At least one
     * of the cameras on the device supports the
     * {@link android.hardware.camera2.CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING manual post-processing}
     * capability level.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CAMERA_CAPABILITY_MANUAL_POST_PROCESSING =
            "android.hardware.camera.capability.manual_post_processing";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: At least one
     * of the cameras on the device supports the
     * {@link android.hardware.camera2.CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_RAW RAW}
     * capability level.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CAMERA_CAPABILITY_RAW =
            "android.hardware.camera.capability.raw";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: At least one
     * of the cameras on the device supports the
     * {@link android.hardware.camera2.CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING
     * MOTION_TRACKING} capability level.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CAMERA_AR =
            "android.hardware.camera.ar";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device's main front and back cameras can stream
     * concurrently as described in {@link
     * android.hardware.camera2.CameraManager#getConcurrentCameraIds()}.
     * </p>
     * <p>While {@link android.hardware.camera2.CameraManager#getConcurrentCameraIds()} and
     * associated APIs are only available on API level 30 or newer, this feature flag may be
     * advertised by devices on API levels below 30. If present on such a device, the same
     * guarantees hold: The main front and main back camera can be used at the same time, with
     * guaranteed stream configurations as defined in the table for concurrent streaming at
     * {@link android.hardware.camera2.CameraDevice#createCaptureSession(android.hardware.camera2.params.SessionConfiguration)}.
     * </p>
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CAMERA_CONCURRENT = "android.hardware.camera.concurrent";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device is capable of communicating with
     * consumer IR devices.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CONSUMER_IR = "android.hardware.consumerir";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports a Context Hub, used to expose the
     * functionalities in {@link android.hardware.location.ContextHubManager}.
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CONTEXT_HUB = "android.hardware.context_hub";

    /** {@hide} */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CTS = "android.software.cts";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: The device
     * is opted-in to render the application using Automotive App Host
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CAR_TEMPLATES_HOST =
            "android.software.car.templates_host";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: This device is capable of launching apps in automotive display
     * compatibility mode.
     * @hide
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CAR_DISPLAY_COMPATIBILITY =
            "android.software.car.display_compatibility";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature(String, int)}: If this feature is supported, the device supports
     * {@link android.security.identity.IdentityCredentialStore} implemented in secure hardware
     * at the given feature version.
     *
     * <p>Known feature versions include:
     * <ul>
     * <li><code>202009</code>: corresponds to the features included in the Identity Credential
     * API shipped in Android 11.
     * <li><code>202101</code>: corresponds to the features included in the Identity Credential
     * API shipped in Android 12.
     * <li><code>202201</code>: corresponds to the features included in the Identity Credential
     * API shipped in Android 13.
     * </ul>
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_IDENTITY_CREDENTIAL_HARDWARE =
            "android.hardware.identity_credential";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature(String, int)}: If this feature is supported, the device supports
     * {@link android.security.identity.IdentityCredentialStore} implemented in secure hardware
     * with direct access at the given feature version.
     * See {@link #FEATURE_IDENTITY_CREDENTIAL_HARDWARE} for known feature versions.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_IDENTITY_CREDENTIAL_HARDWARE_DIRECT_ACCESS =
            "android.hardware.identity_credential_direct_access";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports one or more methods of
     * reporting current location.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_LOCATION = "android.hardware.location";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device has a Global Positioning System
     * receiver and can report precise location.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_LOCATION_GPS = "android.hardware.location.gps";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device can report location with coarse
     * accuracy using a network-based geolocation system.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_LOCATION_NETWORK = "android.hardware.location.network";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports FeliCa communication, which is based on
     * ISO/IEC 18092 and JIS X 6319-4.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_FELICA = "android.hardware.felica";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device's
     * {@link ActivityManager#isLowRamDevice() ActivityManager.isLowRamDevice()} method returns
     * true.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_RAM_LOW = "android.hardware.ram.low";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device's
     * {@link ActivityManager#isLowRamDevice() ActivityManager.isLowRamDevice()} method returns
     * false.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_RAM_NORMAL = "android.hardware.ram.normal";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device can record audio via a
     * microphone.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_MICROPHONE = "android.hardware.microphone";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device can communicate using Near-Field
     * Communications (NFC).
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_NFC = "android.hardware.nfc";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports host-
     * based NFC card emulation.
     *
     * TODO remove when depending apps have moved to new constant.
     * @hide
     * @deprecated
     */
    @Deprecated
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_NFC_HCE = "android.hardware.nfc.hce";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports host-
     * based NFC card emulation.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_NFC_HOST_CARD_EMULATION = "android.hardware.nfc.hce";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports host-
     * based NFC-F card emulation.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_NFC_HOST_CARD_EMULATION_NFCF = "android.hardware.nfc.hcef";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports uicc-
     * based NFC card emulation.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_NFC_OFF_HOST_CARD_EMULATION_UICC =
                                                                       "android.hardware.nfc.uicc";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports eSE-
     * based NFC card emulation.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_NFC_OFF_HOST_CARD_EMULATION_ESE = "android.hardware.nfc.ese";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The Beam API is enabled on the device.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_NFC_BEAM = "android.sofware.nfc.beam";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports any
     * one of the {@link #FEATURE_NFC}, {@link #FEATURE_NFC_HOST_CARD_EMULATION},
     * or {@link #FEATURE_NFC_HOST_CARD_EMULATION_NFCF} features.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_NFC_ANY = "android.hardware.nfc.any";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports Open Mobile API capable UICC-based secure
     * elements.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SE_OMAPI_UICC = "android.hardware.se.omapi.uicc";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports Open Mobile API capable eSE-based secure
     * elements.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SE_OMAPI_ESE = "android.hardware.se.omapi.ese";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports Open Mobile API capable SD-based secure
     * elements.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SE_OMAPI_SD = "android.hardware.se.omapi.sd";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: The device is
     * compatible with Android’s security model.
     *
     * <p>See sections 2 and 9 in the
     * <a href="https://source.android.com/compatibility/android-cdd">Android CDD</a> for more
     * details.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SECURITY_MODEL_COMPATIBLE =
            "android.hardware.security.model.compatible";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports the OpenGL ES
     * <a href="http://www.khronos.org/registry/gles/extensions/ANDROID/ANDROID_extension_pack_es31a.txt">
     * Android Extension Pack</a>.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_OPENGLES_EXTENSION_PACK = "android.hardware.opengles.aep";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature(String, int)}: If this feature is supported, the Vulkan
     * implementation on this device is hardware accelerated, and the Vulkan native API will
     * enumerate at least one {@code VkPhysicalDevice}, and the feature version will indicate what
     * level of optional hardware features limits it supports.
     * <p>
     * Level 0 includes the base Vulkan requirements as well as:
     * <ul><li>{@code VkPhysicalDeviceFeatures::textureCompressionETC2}</li></ul>
     * <p>
     * Level 1 additionally includes:
     * <ul>
     * <li>{@code VkPhysicalDeviceFeatures::fullDrawIndexUint32}</li>
     * <li>{@code VkPhysicalDeviceFeatures::imageCubeArray}</li>
     * <li>{@code VkPhysicalDeviceFeatures::independentBlend}</li>
     * <li>{@code VkPhysicalDeviceFeatures::geometryShader}</li>
     * <li>{@code VkPhysicalDeviceFeatures::tessellationShader}</li>
     * <li>{@code VkPhysicalDeviceFeatures::sampleRateShading}</li>
     * <li>{@code VkPhysicalDeviceFeatures::textureCompressionASTC_LDR}</li>
     * <li>{@code VkPhysicalDeviceFeatures::fragmentStoresAndAtomics}</li>
     * <li>{@code VkPhysicalDeviceFeatures::shaderImageGatherExtended}</li>
     * <li>{@code VkPhysicalDeviceFeatures::shaderUniformBufferArrayDynamicIndexing}</li>
     * <li>{@code VkPhysicalDeviceFeatures::shaderSampledImageArrayDynamicIndexing}</li>
     * </ul>
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_VULKAN_HARDWARE_LEVEL = "android.hardware.vulkan.level";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature(String, int)}: If this feature is supported, the Vulkan
     * implementation on this device is hardware accelerated, and the Vulkan native API will
     * enumerate at least one {@code VkPhysicalDevice}, and the feature version will indicate what
     * level of optional compute features that device supports beyond the Vulkan 1.0 requirements.
     * <p>
     * Compute level 0 indicates:
     * <ul>
     * <li>The {@code VK_KHR_variable_pointers} extension and
     *     {@code VkPhysicalDeviceVariablePointerFeaturesKHR::variablePointers} feature are
           supported.</li>
     * <li>{@code VkPhysicalDeviceLimits::maxPerStageDescriptorStorageBuffers} is at least 16.</li>
     * </ul>
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_VULKAN_HARDWARE_COMPUTE = "android.hardware.vulkan.compute";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature(String, int)}: If this feature is supported, the Vulkan
     * implementation on this device is hardware accelerated, and the feature version will indicate
     * the highest {@code VkPhysicalDeviceProperties::apiVersion} supported by the physical devices
     * that support the hardware level indicated by {@link #FEATURE_VULKAN_HARDWARE_LEVEL}. The
     * feature version uses the same encoding as Vulkan version numbers:
     * <ul>
     * <li>Major version number in bits 31-22</li>
     * <li>Minor version number in bits 21-12</li>
     * <li>Patch version number in bits 11-0</li>
     * </ul>
     * A version of 1.1.0 or higher also indicates:
     * <ul>
     * <li>The {@code VK_ANDROID_external_memory_android_hardware_buffer} extension is
     *     supported.</li>
     * <li>{@code SYNC_FD} external semaphore and fence handles are supported.</li>
     * <li>{@code VkPhysicalDeviceSamplerYcbcrConversionFeatures::samplerYcbcrConversion} is
     *     supported.</li>
     * </ul>
     * A subset of devices that support Vulkan 1.1 do so via software emulation. For more
     * information, see
     * <a href="{@docRoot}ndk/guides/graphics/design-notes">Vulkan Design Guidelines</a>.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_VULKAN_HARDWARE_VERSION = "android.hardware.vulkan.version";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature(String, int)}: If this feature is supported, the feature version
     * specifies a date such that the device is known to pass the Vulkan dEQP test suite associated
     * with that date.  The date is encoded as follows:
     * <ul>
     * <li>Year in bits 31-16</li>
     * <li>Month in bits 15-8</li>
     * <li>Day in bits 7-0</li>
     * </ul>
     * <p>
     * Example: 2019-03-01 is encoded as 0x07E30301, and would indicate that the device passes the
     * Vulkan dEQP test suite version that was current on 2019-03-01.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_VULKAN_DEQP_LEVEL = "android.software.vulkan.deqp.level";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature(String, int)}: If this feature is supported, the feature version
     * specifies a date such that the device is known to pass the OpenGLES dEQP test suite
     * associated with that date.  The date is encoded as follows:
     * <ul>
     * <li>Year in bits 31-16</li>
     * <li>Month in bits 15-8</li>
     * <li>Day in bits 7-0</li>
     * </ul>
     * <p>
     * Example: 2021-03-01 is encoded as 0x07E50301, and would indicate that the device passes the
     * OpenGL ES dEQP test suite version that was current on 2021-03-01.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_OPENGLES_DEQP_LEVEL = "android.software.opengles.deqp.level";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes broadcast radio tuner.
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_BROADCAST_RADIO = "android.hardware.broadcastradio";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device has a secure implementation of keyguard, meaning the
     * device supports PIN, pattern and password as defined in Android CDD
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SECURE_LOCK_SCREEN = "android.software.secure_lock_screen";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes an accelerometer.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_ACCELEROMETER = "android.hardware.sensor.accelerometer";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes a barometer (air
     * pressure sensor.)
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_BAROMETER = "android.hardware.sensor.barometer";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes a magnetometer (compass).
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_COMPASS = "android.hardware.sensor.compass";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes a gyroscope.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_GYROSCOPE = "android.hardware.sensor.gyroscope";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes a limited axes accelerometer.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_ACCELEROMETER_LIMITED_AXES =
                "android.hardware.sensor.accelerometer_limited_axes";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes a limited axes gyroscope.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_GYROSCOPE_LIMITED_AXES =
                "android.hardware.sensor.gyroscope_limited_axes";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes an uncalibrated limited axes accelerometer.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_ACCELEROMETER_LIMITED_AXES_UNCALIBRATED =
                "android.hardware.sensor.accelerometer_limited_axes_uncalibrated";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes an uncalibrated limited axes gyroscope.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_GYROSCOPE_LIMITED_AXES_UNCALIBRATED =
                "android.hardware.sensor.gyroscope_limited_axes_uncalibrated";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes a light sensor.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_LIGHT = "android.hardware.sensor.light";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes a proximity sensor.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_PROXIMITY = "android.hardware.sensor.proximity";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes a hardware step counter.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_STEP_COUNTER = "android.hardware.sensor.stepcounter";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes a hardware step detector.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_STEP_DETECTOR = "android.hardware.sensor.stepdetector";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes a heart rate monitor.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_HEART_RATE = "android.hardware.sensor.heartrate";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The heart rate sensor on this device is an Electrocardiogram.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_HEART_RATE_ECG =
            "android.hardware.sensor.heartrate.ecg";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes a relative humidity sensor.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_RELATIVE_HUMIDITY =
            "android.hardware.sensor.relative_humidity";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes an ambient temperature sensor.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_AMBIENT_TEMPERATURE =
            "android.hardware.sensor.ambient_temperature";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes a hinge angle sensor.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_HINGE_ANGLE = "android.hardware.sensor.hinge_angle";

     /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes a heading sensor.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_HEADING = "android.hardware.sensor.heading";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports exposing head tracker sensors from peripheral
     * devices via the dynamic sensors API.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_DYNAMIC_HEAD_TRACKER = "android.hardware.sensor.dynamic.head_tracker";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports high fidelity sensor processing
     * capabilities.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_HIFI_SENSORS =
            "android.hardware.sensor.hifi_sensors";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports a hardware mechanism for invoking an assist gesture.
     * @see android.provider.Settings.Secure#ASSIST_GESTURE_ENABLED
     * @hide
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_ASSIST_GESTURE = "android.hardware.sensor.assist";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device has a telephony radio with data
     * communication support.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TELEPHONY = "android.hardware.telephony";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device has a CDMA telephony stack.
     *
     * <p>This feature should only be defined if {@link #FEATURE_TELEPHONY} has been defined.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TELEPHONY_CDMA = "android.hardware.telephony.cdma";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device has a GSM telephony stack.
     *
     * <p>This feature should only be defined if {@link #FEATURE_TELEPHONY} has been defined.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TELEPHONY_GSM = "android.hardware.telephony.gsm";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports telephony carrier restriction mechanism.
     *
     * <p>Devices declaring this feature must have an implementation of the
     * {@link android.telephony.TelephonyManager#getAllowedCarriers} and
     * {@link android.telephony.TelephonyManager#setAllowedCarriers}.
     *
     * This feature should only be defined if {@link #FEATURE_TELEPHONY_SUBSCRIPTION}
     * has been defined.
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TELEPHONY_CARRIERLOCK =
            "android.hardware.telephony.carrierlock";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: The device
     * supports embedded subscriptions on eUICCs.
     *
     * This feature should only be defined if {@link #FEATURE_TELEPHONY_SUBSCRIPTION}
     * has been defined.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TELEPHONY_EUICC = "android.hardware.telephony.euicc";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: The device
     * supports multiple enabled profiles on eUICCs.
     *
     * <p>Devices declaring this feature must have an implementation of the
     *  {@link UiccCardInfo#getPorts},
     *  {@link UiccCardInfo#isMultipleEnabledProfilesSupported} and
     *  {@link android.telephony.euicc.EuiccManager#switchToSubscription (with portIndex)}.
     *
     * This feature should only be defined if {@link #FEATURE_TELEPHONY_EUICC} have been defined.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TELEPHONY_EUICC_MEP = "android.hardware.telephony.euicc.mep";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: The device
     * supports cell-broadcast reception using the MBMS APIs.
     *
     * <p>This feature should only be defined if both {@link #FEATURE_TELEPHONY_SUBSCRIPTION}
     * and {@link #FEATURE_TELEPHONY_RADIO_ACCESS} have been defined.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TELEPHONY_MBMS = "android.hardware.telephony.mbms";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: The device
     * supports attaching to IMS implementations using the ImsService API in telephony.
     *
     * <p>This feature should only be defined if {@link #FEATURE_TELEPHONY_DATA} has been defined.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TELEPHONY_IMS = "android.hardware.telephony.ims";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: The device
     * supports a single IMS registration as defined by carrier networks in the IMS service
     * implementation using the {@link ImsService} API, {@link GbaService} API, and IRadio 1.6 HAL.
     * <p>
     * When set, the device must fully support the following APIs for an application to implement
     * IMS single registration:
     * <ul>
     * <li> Updating RCS provisioning status using the {@link ProvisioningManager} API to supply an
     * RCC.14 defined XML and notify IMS applications of Auto Configuration Server (ACS) or
     * proprietary server provisioning updates.</li>
     * <li>Opening a delegate in the device IMS service to forward SIP traffic to the carrier's
     * network using the {@link SipDelegateManager} API</li>
     * <li>Listening to EPS dedicated bearer establishment via the
     * {@link ConnectivityManager#registerQosCallback}
     * API to indicate to the application when to start/stop media traffic.</li>
     * <li>Implementing Generic Bootstrapping Architecture (GBA) and providing the associated
     * authentication keys to applications
     * requesting this information via the {@link TelephonyManager#bootstrapAuthenticationRequest}
     * API</li>
     * <li>Implementing RCS User Capability Exchange using the {@link RcsUceAdapter} API</li>
     * </ul>
     * <p>
     * This feature should only be defined if {@link #FEATURE_TELEPHONY_IMS} is also defined.
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TELEPHONY_IMS_SINGLE_REGISTRATION =
            "android.hardware.telephony.ims.singlereg";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports Telecom Service APIs.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TELECOM = "android.software.telecom";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports Telephony APIs for calling service.
     *
     * <p>This feature should only be defined if {@link #FEATURE_TELEPHONY_RADIO_ACCESS},
     * {@link #FEATURE_TELEPHONY_SUBSCRIPTION}, and {@link #FEATURE_TELECOM} have been defined.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TELEPHONY_CALLING = "android.hardware.telephony.calling";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports Telephony APIs for data service.
     *
     * <p>This feature should only be defined if both {@link #FEATURE_TELEPHONY_SUBSCRIPTION}
     * and {@link #FEATURE_TELEPHONY_RADIO_ACCESS} have been defined.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TELEPHONY_DATA = "android.hardware.telephony.data";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports Telephony APIs for SMS and MMS.
     *
     * <p>This feature should only be defined if both {@link #FEATURE_TELEPHONY_SUBSCRIPTION}
     * and {@link #FEATURE_TELEPHONY_RADIO_ACCESS} have been defined.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TELEPHONY_MESSAGING = "android.hardware.telephony.messaging";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports Telephony APIs for the radio access.
     *
     * <p>This feature should only be defined if {@link #FEATURE_TELEPHONY} has been defined.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TELEPHONY_RADIO_ACCESS =
            "android.hardware.telephony.radio.access";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports Telephony APIs for the subscription.
     *
     * <p>This feature should only be defined if {@link #FEATURE_TELEPHONY} has been defined.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TELEPHONY_SUBSCRIPTION =
            "android.hardware.telephony.subscription";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device is capable of communicating with
     * other devices via ultra wideband.
     * @hide
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_UWB = "android.hardware.uwb";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports connecting to USB devices
     * as the USB host.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_USB_HOST = "android.hardware.usb.host";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports connecting to USB accessories.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_USB_ACCESSORY = "android.hardware.usb.accessory";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The SIP API is enabled on the device.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SIP = "android.software.sip";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports SIP-based VOIP.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SIP_VOIP = "android.software.sip.voip";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The Connection Service API is enabled on the device.
     * @deprecated use {@link #FEATURE_TELECOM} instead.
     */
    @Deprecated
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CONNECTION_SERVICE = "android.software.connectionservice";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device's display has a touch screen.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TOUCHSCREEN = "android.hardware.touchscreen";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device's touch screen supports
     * multitouch sufficient for basic two-finger gesture detection.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TOUCHSCREEN_MULTITOUCH = "android.hardware.touchscreen.multitouch";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device's touch screen is capable of
     * tracking two or more fingers fully independently.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT = "android.hardware.touchscreen.multitouch.distinct";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device's touch screen is capable of
     * tracking a full hand of fingers fully independently -- that is, 5 or
     * more simultaneous independent pointers.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TOUCHSCREEN_MULTITOUCH_JAZZHAND = "android.hardware.touchscreen.multitouch.jazzhand";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device does not have a touch screen, but
     * does support touch emulation for basic events. For instance, the
     * device might use a mouse or remote control to drive a cursor, and
     * emulate basic touch pointer events like down, up, drag, etc. All
     * devices that support android.hardware.touchscreen or a sub-feature are
     * presumed to also support faketouch.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_FAKETOUCH = "android.hardware.faketouch";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device does not have a touch screen, but
     * does support touch emulation for basic events that supports distinct
     * tracking of two or more fingers.  This is an extension of
     * {@link #FEATURE_FAKETOUCH} for input devices with this capability.  Note
     * that unlike a distinct multitouch screen as defined by
     * {@link #FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT}, these kinds of input
     * devices will not actually provide full two-finger gestures since the
     * input is being transformed to cursor movement on the screen.  That is,
     * single finger gestures will move a cursor; two-finger swipes will
     * result in single-finger touch events; other two-finger gestures will
     * result in the corresponding two-finger touch event.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_FAKETOUCH_MULTITOUCH_DISTINCT = "android.hardware.faketouch.multitouch.distinct";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device does not have a touch screen, but
     * does support touch emulation for basic events that supports tracking
     * a hand of fingers (5 or more fingers) fully independently.
     * This is an extension of
     * {@link #FEATURE_FAKETOUCH} for input devices with this capability.  Note
     * that unlike a multitouch screen as defined by
     * {@link #FEATURE_TOUCHSCREEN_MULTITOUCH_JAZZHAND}, not all two finger
     * gestures can be detected due to the limitations described for
     * {@link #FEATURE_FAKETOUCH_MULTITOUCH_DISTINCT}.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_FAKETOUCH_MULTITOUCH_JAZZHAND = "android.hardware.faketouch.multitouch.jazzhand";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device has biometric hardware to detect a fingerprint.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_FINGERPRINT = "android.hardware.fingerprint";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device has biometric hardware to perform face authentication.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_FACE = "android.hardware.biometrics.face";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device has biometric hardware to perform iris authentication.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_IRIS = "android.hardware.biometrics.iris";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports portrait orientation
     * screens.  For backwards compatibility, you can assume that if neither
     * this nor {@link #FEATURE_SCREEN_LANDSCAPE} is set then the device supports
     * both portrait and landscape.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SCREEN_PORTRAIT = "android.hardware.screen.portrait";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports landscape orientation
     * screens.  For backwards compatibility, you can assume that if neither
     * this nor {@link #FEATURE_SCREEN_PORTRAIT} is set then the device supports
     * both portrait and landscape.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SCREEN_LANDSCAPE = "android.hardware.screen.landscape";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports live wallpapers.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_LIVE_WALLPAPER = "android.software.live_wallpaper";
    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports app widgets.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_APP_WIDGETS = "android.software.app_widgets";
    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports the
     * {@link android.R.attr#cantSaveState} API.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CANT_SAVE_STATE = "android.software.cant_save_state";

    /**
     * @hide
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports
     * {@link android.service.games.GameService}.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.FEATURE)
    @SystemApi
    public static final String FEATURE_GAME_SERVICE = "android.software.game_service";

    /**
     * @hide
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports
     * {@link android.service.voice.VoiceInteractionService} and
     * {@link android.app.VoiceInteractor}.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_VOICE_RECOGNIZERS = "android.software.voice_recognizers";


    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports a home screen that is replaceable
     * by third party applications.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_HOME_SCREEN = "android.software.home_screen";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports adding new input methods implemented
     * with the {@link android.inputmethodservice.InputMethodService} API.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_INPUT_METHODS = "android.software.input_methods";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports device policy enforcement via device admins.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_DEVICE_ADMIN = "android.software.device_admin";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports leanback UI. This is
     * typically used in a living room television experience, but is a software
     * feature unlike {@link #FEATURE_TELEVISION}. Devices running with this
     * feature will use resources associated with the "television" UI mode.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_LEANBACK = "android.software.leanback";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports only leanback UI. Only
     * applications designed for this experience should be run, though this is
     * not enforced by the system.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_LEANBACK_ONLY = "android.software.leanback_only";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports live TV and can display
     * contents from TV inputs implemented with the
     * {@link android.media.tv.TvInputService} API.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_LIVE_TV = "android.software.live_tv";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports WiFi (802.11) networking.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_WIFI = "android.hardware.wifi";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports Wi-Fi Direct networking.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_WIFI_DIRECT = "android.hardware.wifi.direct";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports Wi-Fi Aware.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_WIFI_AWARE = "android.hardware.wifi.aware";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports Wi-Fi Passpoint and all
     * Passpoint related APIs in {@link WifiManager} are supported. Refer to
     * {@link WifiManager#addOrUpdatePasspointConfiguration} for more info.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_WIFI_PASSPOINT = "android.hardware.wifi.passpoint";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports Wi-Fi RTT (IEEE 802.11mc).
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_WIFI_RTT = "android.hardware.wifi.rtt";


    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports LoWPAN networking.
     * @hide
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_LOWPAN = "android.hardware.lowpan";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: This is a device dedicated to showing UI
     * on a vehicle headunit. A headunit here is defined to be inside a
     * vehicle that may or may not be moving. A headunit uses either a
     * primary display in the center console and/or additional displays in
     * the instrument cluster or elsewhere in the vehicle. Headunit display(s)
     * have limited size and resolution. The user will likely be focused on
     * driving so limiting driver distraction is a primary concern. User input
     * can be a variety of hard buttons, touch, rotary controllers and even mouse-
     * like interfaces.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_AUTOMOTIVE = "android.hardware.type.automotive";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: This is a device dedicated to showing UI
     * on a television.  Television here is defined to be a typical living
     * room television experience: displayed on a big screen, where the user
     * is sitting far away from it, and the dominant form of input will be
     * something like a DPAD, not through touch or mouse.
     * @deprecated use {@link #FEATURE_LEANBACK} instead.
     */
    @Deprecated
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TELEVISION = "android.hardware.type.television";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: This is a device dedicated to showing UI
     * on a watch. A watch here is defined to be a device worn on the body, perhaps on
     * the wrist. The user is very close when interacting with the device.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_WATCH = "android.hardware.type.watch";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: This is a device for IoT and may not have an UI. An embedded
     * device is defined as a full stack Android device with or without a display and no
     * user-installable apps.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_EMBEDDED = "android.hardware.type.embedded";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: This is a device dedicated to be primarily used
     * with keyboard, mouse or touchpad. This includes traditional desktop
     * computers, laptops and variants such as convertibles or detachables.
     * Due to the larger screen, the device will most likely use the
     * {@link #FEATURE_FREEFORM_WINDOW_MANAGEMENT} feature as well.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_PC = "android.hardware.type.pc";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports printing.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_PRINTING = "android.software.print";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports {@link android.companion.CompanionDeviceManager#associate associating}
     * with devices via {@link android.companion.CompanionDeviceManager}.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_COMPANION_DEVICE_SETUP
            = "android.software.companion_device_setup";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device can perform backup and restore operations on installed applications.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_BACKUP = "android.software.backup";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports freeform window management.
     * Windows have title bars and can be moved and resized.
     */
    // If this feature is present, you also need to set
    // com.android.internal.R.config_freeformWindowManagement to true in your configuration overlay.
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_FREEFORM_WINDOW_MANAGEMENT
            = "android.software.freeform_window_management";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports picture-in-picture multi-window mode.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_PICTURE_IN_PICTURE = "android.software.picture_in_picture";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports expanded picture-in-picture multi-window mode.
     *
     * @see android.app.PictureInPictureParams.Builder#setExpandedAspectRatio
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_EXPANDED_PICTURE_IN_PICTURE
            = "android.software.expanded_picture_in_picture";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports running activities on secondary displays.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS
            = "android.software.activities_on_secondary_displays";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports creating secondary users and managed profiles via
     * {@link DevicePolicyManager}.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_MANAGED_USERS = "android.software.managed_users";

    /**
     * @hide
     * TODO: Remove after dependencies updated b/17392243
     */
    public static final String FEATURE_MANAGED_PROFILES = "android.software.managed_users";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports verified boot.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_VERIFIED_BOOT = "android.software.verified_boot";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports secure removal of users. When a user is deleted the data associated
     * with that user is securely deleted and no longer available.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SECURELY_REMOVES_USERS
            = "android.software.securely_removes_users";

    /** {@hide} */
    @TestApi
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_FILE_BASED_ENCRYPTION
            = "android.software.file_based_encryption";

    /** {@hide} */
    @TestApi
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_ADOPTABLE_STORAGE
            = "android.software.adoptable_storage";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device has a full implementation of the android.webkit.* APIs. Devices
     * lacking this feature will not have a functioning WebView implementation.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_WEBVIEW = "android.software.webview";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: This device supports ethernet.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_ETHERNET = "android.hardware.ethernet";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: This device supports HDMI-CEC.
     * @hide
     */
    @TestApi
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_HDMI_CEC = "android.hardware.hdmi.cec";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device has all of the inputs necessary to be considered a compatible game controller, or
     * includes a compatible game controller in the box.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_GAMEPAD = "android.hardware.gamepad";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device has a full implementation of the android.media.midi.* APIs.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_MIDI = "android.software.midi";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device implements an optimized mode for virtual reality (VR) applications that handles
     * stereoscopic rendering of notifications, and disables most monocular system UI components
     * while a VR application has user focus.
     * Devices declaring this feature must include an application implementing a
     * {@link android.service.vr.VrListenerService} that can be targeted by VR applications via
     * {@link android.app.Activity#setVrModeEnabled}.
     * @deprecated use {@link #FEATURE_VR_MODE_HIGH_PERFORMANCE} instead.
     */
    @Deprecated
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_VR_MODE = "android.software.vr.mode";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device implements an optimized mode for virtual reality (VR) applications that handles
     * stereoscopic rendering of notifications, disables most monocular system UI components
     * while a VR application has user focus and meets extra CDD requirements to provide a
     * high-quality VR experience.
     * Devices declaring this feature must include an application implementing a
     * {@link android.service.vr.VrListenerService} that can be targeted by VR applications via
     * {@link android.app.Activity#setVrModeEnabled}.
     * and must meet CDD requirements to provide a high-quality VR experience.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_VR_MODE_HIGH_PERFORMANCE
            = "android.hardware.vr.high_performance";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports autofill of user credentials, addresses, credit cards, etc
     * via integration with {@link android.service.autofill.AutofillService autofill
     * providers}.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_AUTOFILL = "android.software.autofill";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device implements headtracking suitable for a VR device.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_VR_HEADTRACKING = "android.hardware.vr.headtracking";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature(String, int)}: If this feature is supported, the device implements
     * the Android Keystore backed by an isolated execution environment. The version indicates
     * which features are implemented in the isolated execution environment:
     * <ul>
     * <li>100: Hardware support for ECDH (see {@link javax.crypto.KeyAgreement}) and support
     * for app-generated attestation keys (see {@link
     * android.security.keystore.KeyGenParameterSpec.Builder#setAttestKeyAlias(String)}).
     * <li>41: Hardware enforcement of device-unlocked keys (see {@link
     * android.security.keystore.KeyGenParameterSpec.Builder#setUnlockedDeviceRequired(boolean)}).
     * <li>40: Support for wrapped key import (see {@link
     * android.security.keystore.WrappedKeyEntry}), optional support for ID attestation (see {@link
     * android.security.keystore.KeyGenParameterSpec.Builder#setDevicePropertiesAttestationIncluded(boolean)}),
     * attestation (see {@link
     * android.security.keystore.KeyGenParameterSpec.Builder#setAttestationChallenge(byte[])}),
     * AES, HMAC, ECDSA and RSA support where the secret or private key never leaves secure
     * hardware, and support for requiring user authentication before a key can be used.
     * </ul>
     * This feature version is guaranteed to be set for all devices launching with Android 12 and
     * may be set on devices launching with an earlier version. If the feature version is set, it
     * will at least have the value 40. If it's not set the device may have a version of
     * hardware-backed keystore but it may not support all features listed above.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_HARDWARE_KEYSTORE = "android.hardware.hardware_keystore";

    /**
     * Feature for {@link #getSystemAvailableFeatures}, {@link #hasSystemFeature(String)}, and
     * {@link #hasSystemFeature(String, int)}: If this feature is supported, the device implements
     * the Android Keystore backed by a dedicated secure processor referred to as
     * <a href="https://source.android.com/security/best-practices/hardware#strongbox-keymaster">
     * StrongBox</a>. If this feature has a version, the version number indicates which features are
     * implemented in StrongBox:
     * <ul>
     * <li>100: Hardware support for ECDH (see {@link javax.crypto.KeyAgreement}) and support
     * for app-generated attestation keys (see {@link
     * android.security.keystore.KeyGenParameterSpec.Builder#setAttestKeyAlias(String)}).
     * <li>41: Hardware enforcement of device-unlocked keys (see {@link
     * android.security.keystore.KeyGenParameterSpec.Builder#setUnlockedDeviceRequired(boolean)}).
     * <li>40: Support for wrapped key import (see {@link
     * android.security.keystore.WrappedKeyEntry}), optional support for ID attestation (see {@link
     * android.security.keystore.KeyGenParameterSpec.Builder#setDevicePropertiesAttestationIncluded(boolean)}),
     * attestation (see {@link
     * android.security.keystore.KeyGenParameterSpec.Builder#setAttestationChallenge(byte[])}),
     * AES, HMAC, ECDSA and RSA support where the secret or private key never leaves secure
     * hardware, and support for requiring user authentication before a key can be used.
     * </ul>
     * If a device has StrongBox, this feature version number is guaranteed to be set for all
     * devices launching with Android 12 and may be set on devices launching with an earlier
     * version. If the feature version is set, it will at least have the value 40. If it's not
     * set the device may have StrongBox but it may not support all features listed above.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_STRONGBOX_KEYSTORE =
            "android.hardware.strongbox_keystore";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device does not have slices implementation.
     * @hide
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SLICES_DISABLED = "android.software.slices_disabled";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports device-unique Keystore attestations.  Only available on devices that
     * also support {@link #FEATURE_STRONGBOX_KEYSTORE}, and can only be used by device owner
     * apps (see {@link android.app.admin.DevicePolicyManager#generateKeyPair}).
     * @hide
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_DEVICE_UNIQUE_ATTESTATION =
            "android.hardware.device_unique_attestation";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device has a Keymaster implementation that supports Device ID attestation.
     *
     * @see DevicePolicyManager#isDeviceIdAttestationSupported
     * @hide
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_DEVICE_ID_ATTESTATION =
            "android.software.device_id_attestation";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: The device has
     * the requisite kernel support for multinetworking-capable IPsec tunnels.
     *
     * <p>This feature implies that the device supports XFRM Interfaces (CONFIG_XFRM_INTERFACE), or
     * VTIs with kernel patches allowing updates of output/set mark via UPDSA.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_IPSEC_TUNNELS = "android.software.ipsec_tunnels";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports a system interface for the user to select
     * and bind device control services provided by applications.
     *
     * @see android.service.controls.ControlsProviderService
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CONTROLS = "android.software.controls";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: The device has
     * the requisite hardware support to support reboot escrow of synthetic password for updates.
     *
     * <p>This feature implies that the device has the RebootEscrow HAL implementation.
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_REBOOT_ESCROW = "android.hardware.reboot_escrow";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: The device has
     * the requisite kernel support to support incremental delivery aka Incremental FileSystem.
     *
     * feature not present - IncFs is not present on the device.
     * 1 - IncFs v1, core features, no PerUid support. Optional in R.
     * 2 - IncFs v2, PerUid support, fs-verity support. Required in S.
     *
     * @see IncrementalManager#isFeatureEnabled
     * @see IncrementalManager#getVersion()
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_INCREMENTAL_DELIVERY =
            "android.software.incremental_delivery";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: The device
     * has the requisite kernel support for the EROFS filesystem present in 4.19 kernels as a
     * staging driver, which lacks 0padding and big pcluster support.
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_EROFS_LEGACY = "android.software.erofs_legacy";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: The device
     * has the requisite kernel support for the EROFS filesystem present in 5.10 kernels, which
     * has 0padding, big pcluster, and chunked index support.
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_EROFS = "android.software.erofs";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device has tuner hardware to support tuner operations.
     *
     * <p>This feature implies that the device has the tuner HAL implementation.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TUNER = "android.hardware.tv.tuner";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: The device has
     * the necessary changes to support app enumeration.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_APP_ENUMERATION = "android.software.app_enumeration";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: The device has
     * a Keystore implementation that can only enforce limited use key in hardware with max usage
     * count equals to 1.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_KEYSTORE_SINGLE_USE_KEY =
            "android.hardware.keystore.single_use_key";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: The device has
     * a Keystore implementation that can enforce limited use key in hardware with any max usage
     * count (including count equals to 1).
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_KEYSTORE_LIMITED_USE_KEY =
            "android.hardware.keystore.limited_use_key";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: The device has
     * a Keystore implementation that can create application-specific attestation keys.
     * See {@link android.security.keystore.KeyGenParameterSpec.Builder#setAttestKeyAlias}.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_KEYSTORE_APP_ATTEST_KEY =
            "android.hardware.keystore.app_attest_key";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: The device
     * is opted-in to receive per-app compatibility overrides that are applied in
     * {@link com.android.server.compat.overrides.AppCompatOverridesService}.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_APP_COMPAT_OVERRIDES =
            "android.software.app_compat_overrides";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: The device
     * supports communal mode,
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.FEATURE)
    @TestApi
    public static final String FEATURE_COMMUNAL_MODE = "android.software.communal_mode";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: The device
     * supports dream overlay feature, which is an informational layer shown on top of dreams.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_DREAM_OVERLAY = "android.software.dream_overlay";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: The device
     * supports window magnification.
     *
     * @see android.accessibilityservice.MagnificationConfig#MAGNIFICATION_MODE_WINDOW
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_WINDOW_MAGNIFICATION =
            "android.software.window_magnification";

    /** @hide */
    public static final boolean APP_ENUMERATION_ENABLED_BY_DEFAULT = true;

    /**
     * Extra field name for the URI to a verification file. Passed to a package
     * verifier.
     *
     * @hide
     */
    public static final String EXTRA_VERIFICATION_URI = "android.content.pm.extra.VERIFICATION_URI";

    /**
     * Extra field name for the ID of a package pending verification. Passed to
     * a package verifier and is used to call back to
     * {@link PackageManager#verifyPendingInstall(int, int)}
     */
    public static final String EXTRA_VERIFICATION_ID = "android.content.pm.extra.VERIFICATION_ID";

    /**
     * Extra field name for the package identifier which is trying to install
     * the package.
     *
     * @hide
     */
    public static final String EXTRA_VERIFICATION_INSTALLER_PACKAGE
            = "android.content.pm.extra.VERIFICATION_INSTALLER_PACKAGE";

    /**
     * Extra field name for the requested install flags for a package pending
     * verification. Passed to a package verifier.
     *
     * @hide
     */
    public static final String EXTRA_VERIFICATION_INSTALL_FLAGS
            = "android.content.pm.extra.VERIFICATION_INSTALL_FLAGS";

    /**
     * Extra field name for the uid of who is requesting to install
     * the package.
     *
     * @hide
     */
    public static final String EXTRA_VERIFICATION_INSTALLER_UID
            = "android.content.pm.extra.VERIFICATION_INSTALLER_UID";

    /**
     * Extra field name for the package name of a package pending verification.
     *
     * @hide
     */
    public static final String EXTRA_VERIFICATION_PACKAGE_NAME
            = "android.content.pm.extra.VERIFICATION_PACKAGE_NAME";
    /**
     * Extra field name for the result of a verification, either
     * {@link #VERIFICATION_ALLOW}, or {@link #VERIFICATION_REJECT}.
     * Passed to package verifiers after a package is verified.
     */
    public static final String EXTRA_VERIFICATION_RESULT
            = "android.content.pm.extra.VERIFICATION_RESULT";

    /**
     * Extra field name for the version code of a package pending verification.
     * @deprecated Use {@link #EXTRA_VERIFICATION_LONG_VERSION_CODE} instead.
     * @hide
     */
    @Deprecated
    public static final String EXTRA_VERIFICATION_VERSION_CODE
            = "android.content.pm.extra.VERIFICATION_VERSION_CODE";

    /**
     * Extra field name for the long version code of a package pending verification.
     *
     * @hide
     */
    public static final String EXTRA_VERIFICATION_LONG_VERSION_CODE =
            "android.content.pm.extra.VERIFICATION_LONG_VERSION_CODE";

    /**
     * Extra field name for the Merkle tree root hash of a package.
     * <p>Passed to a package verifier both prior to verification and as a result
     * of verification.
     * <p>The value of the extra is a specially formatted list:
     * {@code filename1:HASH_1;filename2:HASH_2;...;filenameN:HASH_N}
     * <p>The extra must include an entry for every APK within an installation. If
     * a hash is not physically present, a hash value of {@code 0} will be used.
     * <p>The root hash is generated using SHA-256, no salt with a 4096 byte block
     * size. See the description of the
     * <a href="https://www.kernel.org/doc/html/latest/filesystems/fsverity.html#merkle-tree">fs-verity merkle-tree</a>
     * for more details.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final String EXTRA_VERIFICATION_ROOT_HASH =
            "android.content.pm.extra.VERIFICATION_ROOT_HASH";

    /**
     * Extra field name for the ID of a intent filter pending verification.
     * Passed to an intent filter verifier and is used to call back to
     * {@link #verifyIntentFilter}
     *
     * @deprecated Use DomainVerificationManager APIs.
     * @hide
     */
    @Deprecated
    public static final String EXTRA_INTENT_FILTER_VERIFICATION_ID
            = "android.content.pm.extra.INTENT_FILTER_VERIFICATION_ID";

    /**
     * Extra field name for the scheme used for an intent filter pending verification. Passed to
     * an intent filter verifier and is used to construct the URI to verify against.
     *
     * Usually this is "https"
     *
     * @deprecated Use DomainVerificationManager APIs.
     * @hide
     */
    @Deprecated
    public static final String EXTRA_INTENT_FILTER_VERIFICATION_URI_SCHEME
            = "android.content.pm.extra.INTENT_FILTER_VERIFICATION_URI_SCHEME";

    /**
     * Extra field name for the host names to be used for an intent filter pending verification.
     * Passed to an intent filter verifier and is used to construct the URI to verify the
     * intent filter.
     *
     * This is a space delimited list of hosts.
     *
     * @deprecated Use DomainVerificationManager APIs.
     * @hide
     */
    @Deprecated
    public static final String EXTRA_INTENT_FILTER_VERIFICATION_HOSTS
            = "android.content.pm.extra.INTENT_FILTER_VERIFICATION_HOSTS";

    /**
     * Extra field name for the package name to be used for an intent filter pending verification.
     * Passed to an intent filter verifier and is used to check the verification responses coming
     * from the hosts. Each host response will need to include the package name of APK containing
     * the intent filter.
     *
     * @deprecated Use DomainVerificationManager APIs.
     * @hide
     */
    @Deprecated
    public static final String EXTRA_INTENT_FILTER_VERIFICATION_PACKAGE_NAME
            = "android.content.pm.extra.INTENT_FILTER_VERIFICATION_PACKAGE_NAME";

    /**
     * The action used to request that the user approve a permission request
     * from the application.
     *
     * @hide
     */
    @SystemApi
    public static final String ACTION_REQUEST_PERMISSIONS =
            "android.content.pm.action.REQUEST_PERMISSIONS";

    /**
     * The action used to request that the user approve a permission request
     * from the application. Sent from an application other than the one whose permissions
     * will be granted. Can only be used by the system server.
     *
     * @hide
     */
    @SystemApi
    public static final String ACTION_REQUEST_PERMISSIONS_FOR_OTHER =
            "android.content.pm.action.REQUEST_PERMISSIONS_FOR_OTHER";

    /**
     * The names of the requested permissions.
     * <p>
     * <strong>Type:</strong> String[]
     * </p>
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_REQUEST_PERMISSIONS_NAMES =
            "android.content.pm.extra.REQUEST_PERMISSIONS_NAMES";

    /**
     * The results from the permissions request.
     * <p>
     * <strong>Type:</strong> int[] of #PermissionResult
     * </p>
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_REQUEST_PERMISSIONS_RESULTS
            = "android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS";

    /**
     * Indicates that the package requesting permissions has legacy access for some permissions,
     * or had it, but it was recently revoked. These request dialogs may show different text,
     * indicating that the app is requesting continued access to a permission. Will be cleared
     * from any permission request intent, if set by a non-system server app.
     * <p>
     * <strong>Type:</strong> String[]
     * </p>
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_REQUEST_PERMISSIONS_LEGACY_ACCESS_PERMISSION_NAMES
            = "android.content.pm.extra.REQUEST_PERMISSIONS_LEGACY_ACCESS_PERMISSION_NAMES";

    /**
     * String extra for {@link PackageInstallObserver} in the 'extras' Bundle in case of
     * {@link #INSTALL_FAILED_DUPLICATE_PERMISSION}.  This extra names the package which provides
     * the existing definition for the permission.
     * @hide
     */
    public static final String EXTRA_FAILURE_EXISTING_PACKAGE
            = "android.content.pm.extra.FAILURE_EXISTING_PACKAGE";

    /**
     * String extra for {@link PackageInstallObserver} in the 'extras' Bundle in case of
     * {@link #INSTALL_FAILED_DUPLICATE_PERMISSION}.  This extra names the permission that is
     * being redundantly defined by the package being installed.
     * @hide
     */
    public static final String EXTRA_FAILURE_EXISTING_PERMISSION
            = "android.content.pm.extra.FAILURE_EXISTING_PERMISSION";

   /**
    * Permission flag: The permission is set in its current state
    * by the user and apps can still request it at runtime.
    *
    * @hide
    */
    @SystemApi
    public static final int FLAG_PERMISSION_USER_SET = 1 << 0;

    /**
     * Permission flag: The permission is set in its current state
     * by the user and it is fixed, i.e. apps can no longer request
     * this permission.
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_PERMISSION_USER_FIXED =  1 << 1;

    /**
     * Permission flag: The permission is set in its current state
     * by device policy and neither apps nor the user can change
     * its state.
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_PERMISSION_POLICY_FIXED =  1 << 2;

    /**
     * Permission flag: The permission is set in a granted state but
     * access to resources it guards is restricted by other means to
     * enable revoking a permission on legacy apps that do not support
     * runtime permissions. If this permission is upgraded to runtime
     * because the app was updated to support runtime permissions, the
     * the permission will be revoked in the upgrade process.
     *
     * @deprecated Renamed to {@link #FLAG_PERMISSION_REVOKED_COMPAT}.
     *
     * @hide
     */
    @Deprecated
    @SystemApi
    public static final int FLAG_PERMISSION_REVOKE_ON_UPGRADE =  1 << 3;

    /**
     * Permission flag: The permission is set in its current state
     * because the app is a component that is a part of the system.
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_PERMISSION_SYSTEM_FIXED =  1 << 4;

    /**
     * Permission flag: The permission is granted by default because it
     * enables app functionality that is expected to work out-of-the-box
     * for providing a smooth user experience. For example, the phone app
     * is expected to have the phone permission.
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_PERMISSION_GRANTED_BY_DEFAULT =  1 << 5;

    /**
     * Permission flag: If app targetSDK < M, then the permission has to be reviewed before any of
     * the app components can run. If app targetSDK >= M, then the system might need to show a
     * request dialog for this permission on behalf of an app.
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_PERMISSION_REVIEW_REQUIRED =  1 << 6;

    /**
     * Permission flag: The permission has not been explicitly requested by
     * the app but has been added automatically by the system. Revoke once
     * the app does explicitly request it.
     *
     * @hide
     */
    @TestApi
    @SystemApi
    public static final int FLAG_PERMISSION_REVOKE_WHEN_REQUESTED =  1 << 7;

    /**
     * Permission flag: The permission's usage should be made highly visible to the user
     * when granted.
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_PERMISSION_USER_SENSITIVE_WHEN_GRANTED =  1 << 8;

    /**
     * Permission flag: The permission's usage should be made highly visible to the user
     * when denied.
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_PERMISSION_USER_SENSITIVE_WHEN_DENIED =  1 << 9;

    /**
     * Permission flag: The permission is restricted but the app is exempt
     * from the restriction and is allowed to hold this permission in its
     * full form and the exemption is provided by the installer on record.
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT =  1 << 11;

    /**
     * Permission flag: The permission is restricted but the app is exempt
     * from the restriction and is allowed to hold this permission in its
     * full form and the exemption is provided by the system due to its
     * permission policy.
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT =  1 << 12;

    /**
     * Permission flag: The permission is restricted but the app is exempt
     * from the restriction and is allowed to hold this permission and the
     * exemption is provided by the system when upgrading from an OS version
     * where the permission was not restricted to an OS version where the
     * permission is restricted.
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT =  1 << 13;


    /**
     * Permission flag: The permission is disabled but may be granted. If
     * disabled the data protected by the permission should be protected
     * by a no-op (empty list, default error, etc) instead of crashing the
     * client.
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_PERMISSION_APPLY_RESTRICTION =  1 << 14;

    /**
     * Permission flag: The permission is granted because the application holds a role.
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_PERMISSION_GRANTED_BY_ROLE =  1 << 15;

    /**
     * Permission flag: The permission should have been revoked but is kept granted for
     * compatibility. The data protected by the permission should be protected by a no-op (empty
     * list, default error, etc) instead of crashing the client. The permission will be revoked if
     * the app is upgraded to supports it.
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_PERMISSION_REVOKED_COMPAT =  FLAG_PERMISSION_REVOKE_ON_UPGRADE;

    /**
     * Permission flag: The permission is one-time and should be revoked automatically on app
     * inactivity
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_PERMISSION_ONE_TIME = 1 << 16;

    /**
     * Permission flag: Whether permission was revoked by auto-revoke.
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_PERMISSION_AUTO_REVOKED = 1 << 17;

    /**
     * Permission flag: This location permission is selected as the level of granularity of
     * location accuracy.
     * Example: If this flag is set for ACCESS_FINE_LOCATION, FINE location is the selected location
     *          accuracy for location permissions.
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_PERMISSION_SELECTED_LOCATION_ACCURACY =  1 << 19;

    /**
     * Permission flags: Reserved for use by the permission controller. The platform and any
     * packages besides the permission controller should not assume any definition about these
     * flags.
     * @hide
     */
    @SystemApi
    public static final int FLAGS_PERMISSION_RESERVED_PERMISSION_CONTROLLER = 1 << 28 | 1 << 29
            | 1 << 30 | 1 << 31;

    /**
     * Permission flags: Bitwise or of all permission flags allowing an
     * exemption for a restricted permission.
     * @hide
     */
    public static final int FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT =
            FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT
                    | FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT
                    | FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT;

    /**
     * Mask for all permission flags.
     *
     * @hide
     *
     * @deprecated Don't use - does not capture all flags.
     */
    @Deprecated
    @SystemApi
    public static final int MASK_PERMISSION_FLAGS = 0xFF;

    /**
     * Mask for all permission flags.
     *
     * @hide
     */
    public static final int MASK_PERMISSION_FLAGS_ALL = FLAG_PERMISSION_USER_SET
            | FLAG_PERMISSION_USER_FIXED
            | FLAG_PERMISSION_POLICY_FIXED
            | FLAG_PERMISSION_REVOKE_ON_UPGRADE
            | FLAG_PERMISSION_SYSTEM_FIXED
            | FLAG_PERMISSION_GRANTED_BY_DEFAULT
            | FLAG_PERMISSION_REVIEW_REQUIRED
            | FLAG_PERMISSION_REVOKE_WHEN_REQUESTED
            | FLAG_PERMISSION_USER_SENSITIVE_WHEN_GRANTED
            | FLAG_PERMISSION_USER_SENSITIVE_WHEN_DENIED
            | FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT
            | FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT
            | FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT
            | FLAG_PERMISSION_APPLY_RESTRICTION
            | FLAG_PERMISSION_GRANTED_BY_ROLE
            | FLAG_PERMISSION_REVOKED_COMPAT
            | FLAG_PERMISSION_ONE_TIME
            | FLAG_PERMISSION_AUTO_REVOKED;

    /**
     * Injected activity in app that forwards user to setting activity of that app.
     *
     * @hide
     */
    public static final String APP_DETAILS_ACTIVITY_CLASS_NAME = AppDetailsActivity.class.getName();

    /**
     * Permission whitelist flag: permissions whitelisted by the system.
     * Permissions can also be whitelisted by the installer, on upgrade, or on
     * role grant.
     *
     * <p>
     * <strong>Note: </strong>In retrospect it would have been preferred to use
     * more inclusive terminology when naming this API. Similar APIs added will
     * refrain from using the term "whitelist".
     * </p>
     */
    public static final int FLAG_PERMISSION_WHITELIST_SYSTEM = 1 << 0;

    /**
     * Permission whitelist flag: permissions whitelisted by the installer.
     * Permissions can also be whitelisted by the system, on upgrade, or on role
     * grant.
     *
     * <p>
     * <strong>Note: </strong>In retrospect it would have been preferred to use
     * more inclusive terminology when naming this API. Similar APIs added will
     * refrain from using the term "whitelist".
     * </p>
     */
    public static final int FLAG_PERMISSION_WHITELIST_INSTALLER = 1 << 1;

    /**
     * Permission whitelist flag: permissions whitelisted by the system
     * when upgrading from an OS version where the permission was not
     * restricted to an OS version where the permission is restricted.
     * Permissions can also be whitelisted by the installer, the system, or on
     * role grant.
     *
     * <p>
     * <strong>Note: </strong>In retrospect it would have been preferred to use
     * more inclusive terminology when naming this API. Similar APIs added will
     * refrain from using the term "whitelist".
     * </p>
     */
    public static final int FLAG_PERMISSION_WHITELIST_UPGRADE = 1 << 2;

    /** @hide */
    @IntDef(flag = true, prefix = {"FLAG_PERMISSION_WHITELIST_"}, value = {
            FLAG_PERMISSION_WHITELIST_SYSTEM,
            FLAG_PERMISSION_WHITELIST_INSTALLER,
            FLAG_PERMISSION_WHITELIST_UPGRADE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PermissionWhitelistFlags {}

    /**
     * This is a library that contains components apps can invoke. For
     * example, a services for apps to bind to, or standard chooser UI,
     * etc. This library is versioned and backwards compatible. Clients
     * should check its version via {@link android.ext.services.Version
     * #getVersionCode()} and avoid calling APIs added in later versions.
     * <p>
     * This shared library no longer exists since Android R.
     *
     * @see #getServicesSystemSharedLibraryPackageName()
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @TestApi
    public static final String SYSTEM_SHARED_LIBRARY_SERVICES = "android.ext.services";

    /**
     * This is a library that contains components apps can dynamically
     * load. For example, new widgets, helper classes, etc. This library
     * is versioned and backwards compatible. Clients should check its
     * version via {@link android.ext.shared.Version#getVersionCode()}
     * and avoid calling APIs added in later versions.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @TestApi
    public static final String SYSTEM_SHARED_LIBRARY_SHARED = "android.ext.shared";

    /** @hide */
    @IntDef({
            NOTIFY_PACKAGE_USE_ACTIVITY,
            NOTIFY_PACKAGE_USE_SERVICE,
            NOTIFY_PACKAGE_USE_FOREGROUND_SERVICE,
            NOTIFY_PACKAGE_USE_BROADCAST_RECEIVER,
            NOTIFY_PACKAGE_USE_CONTENT_PROVIDER,
            NOTIFY_PACKAGE_USE_BACKUP,
            NOTIFY_PACKAGE_USE_CROSS_PACKAGE,
            NOTIFY_PACKAGE_USE_INSTRUMENTATION,
    })
    public @interface NotifyReason {
    }

    /**
     * Used when starting a process for an Activity.
     *
     * @hide
     */
    public static final int NOTIFY_PACKAGE_USE_ACTIVITY = 0;

    /**
     * Used when starting a process for a Service.
     *
     * @hide
     */
    public static final int NOTIFY_PACKAGE_USE_SERVICE = 1;

    /**
     * Used when moving a Service to the foreground.
     *
     * @hide
     */
    public static final int NOTIFY_PACKAGE_USE_FOREGROUND_SERVICE = 2;

    /**
     * Used when starting a process for a BroadcastReceiver.
     *
     * @hide
     */
    public static final int NOTIFY_PACKAGE_USE_BROADCAST_RECEIVER = 3;

    /**
     * Used when starting a process for a ContentProvider.
     *
     * @hide
     */
    public static final int NOTIFY_PACKAGE_USE_CONTENT_PROVIDER = 4;

    /**
     * Used when starting a process for a BroadcastReceiver.
     *
     * @hide
     */
    public static final int NOTIFY_PACKAGE_USE_BACKUP = 5;

    /**
     * Used with Context.getClassLoader() across Android packages.
     *
     * @hide
     */
    public static final int NOTIFY_PACKAGE_USE_CROSS_PACKAGE = 6;

    /**
     * Used when starting a package within a process for Instrumentation.
     *
     * @hide
     */
    public static final int NOTIFY_PACKAGE_USE_INSTRUMENTATION = 7;

    /**
     * Total number of usage reasons.
     *
     * @hide
     */
    public static final int NOTIFY_PACKAGE_USE_REASONS_COUNT = 8;

    /**
     * Constant for specifying the highest installed package version code.
     */
    public static final int VERSION_CODE_HIGHEST = -1;

    /**
     * Apps targeting Android R and above will need to declare the packages and intents they intend
     * to use to get details about other apps on a device. Such declarations must be made via the
     * {@code <queries>} tag in the manifest.
     *
     * @hide
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.R)
    public static final long FILTER_APPLICATION_QUERY = 135549675L;

    /** {@hide} */
    @IntDef(prefix = {"SYSTEM_APP_STATE_"}, value = {
            SYSTEM_APP_STATE_HIDDEN_UNTIL_INSTALLED_HIDDEN,
            SYSTEM_APP_STATE_HIDDEN_UNTIL_INSTALLED_VISIBLE,
            SYSTEM_APP_STATE_INSTALLED,
            SYSTEM_APP_STATE_UNINSTALLED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SystemAppState {}

    /**
     * Constant for use with {@link #setSystemAppState} to mark a system app as hidden until
     * installation.
     * @hide
     */
    @SystemApi
    public static final int SYSTEM_APP_STATE_HIDDEN_UNTIL_INSTALLED_HIDDEN = 0;

    /**
     * Constant for use with {@link #setSystemAppState} to mark a system app as not hidden until
     * installation.
     * @hide
     */
    @SystemApi
    public static final int SYSTEM_APP_STATE_HIDDEN_UNTIL_INSTALLED_VISIBLE = 1;

    /**
     * Constant for use with {@link #setSystemAppState} to change a system app's state to installed.
     * @hide
     */
    @SystemApi
    public static final int SYSTEM_APP_STATE_INSTALLED = 2;

    /**
     * Constant for use with {@link #setSystemAppState} to change a system app's state to
     * uninstalled.
     * @hide
     */
    @SystemApi
    public static final int SYSTEM_APP_STATE_UNINSTALLED = 3;

    /**
     * A manifest property to control app's participation in {@code adb backup}. Should only
     * be used by system / privileged apps.
     *
     * @hide
     */
    public static final String PROPERTY_ALLOW_ADB_BACKUP = "android.backup.ALLOW_ADB_BACKUP";

    /**
     * Flags class that wraps around the bitmask flags used in methods that retrieve package or
     * application info.
     * @hide
     */
    public static class Flags {
        final long mValue;
        protected Flags(long value) {
            mValue = value;
        }
        public long getValue() {
            return mValue;
        }
    }

    /**
     * Specific flags used for retrieving package info. Example:
     * {@code PackageManager.getPackageInfo(packageName, PackageInfoFlags.of(0)}
     */
    public final static class PackageInfoFlags extends Flags {
        private PackageInfoFlags(@PackageInfoFlagsBits long value) {
            super(value);
        }
        @NonNull
        public static PackageInfoFlags of(@PackageInfoFlagsBits long value) {
            return new PackageInfoFlags(value);
        }
    }

    /**
     * Specific flags used for retrieving application info.
     */
    public final static class ApplicationInfoFlags extends Flags {
        private ApplicationInfoFlags(@ApplicationInfoFlagsBits long value) {
            super(value);
        }
        @NonNull
        public static ApplicationInfoFlags of(@ApplicationInfoFlagsBits long value) {
            return new ApplicationInfoFlags(value);
        }
    }

    /**
     * Specific flags used for retrieving component info.
     */
    public final static class ComponentInfoFlags extends Flags {
        private ComponentInfoFlags(@ComponentInfoFlagsBits long value) {
            super(value);
        }
        @NonNull
        public static ComponentInfoFlags of(@ComponentInfoFlagsBits long value) {
            return new ComponentInfoFlags(value);
        }
    }

    /**
     * Specific flags used for retrieving resolve info.
     */
    public final static class ResolveInfoFlags extends Flags {
        private ResolveInfoFlags(@ResolveInfoFlagsBits long value) {
            super(value);
        }
        @NonNull
        public static ResolveInfoFlags of(@ResolveInfoFlagsBits long value) {
            return new ResolveInfoFlags(value);
        }
    }

    /** {@hide} */
    public int getUserId() {
        return UserHandle.myUserId();
    }

    /**
     * @deprecated Do not instantiate or subclass - obtain an instance from
     * {@link Context#getPackageManager}
     */
    @Deprecated
    public PackageManager() {}

    /**
     * Retrieve overall information about an application package that is
     * installed on the system.
     *
     * @param packageName The full name (i.e. com.google.apps.contacts) of the
     *            desired package.
     * @param flags Additional option flags to modify the data returned.
     * @return A PackageInfo object containing information about the package. If
     *         flag {@code MATCH_UNINSTALLED_PACKAGES} is set and if the package
     *         is not found in the list of installed applications, the package
     *         information is retrieved from the list of uninstalled
     *         applications (which includes installed applications as well as
     *         applications with data directory i.e. applications which had been
     *         deleted with {@code DELETE_KEEP_DATA} flag set).
     * @throws NameNotFoundException if no such package is available to the
     *             caller.
     * @deprecated Use {@link #getPackageInfo(String, PackageInfoFlags)} instead.
     */
    @Deprecated
    public abstract PackageInfo getPackageInfo(@NonNull String packageName, int flags)
            throws NameNotFoundException;

    /**
     * See {@link #getPackageInfo(String, int)}
     */
    @NonNull
    public PackageInfo getPackageInfo(@NonNull String packageName, @NonNull PackageInfoFlags flags)
            throws NameNotFoundException {
        throw new UnsupportedOperationException(
                "getPackageInfo not implemented in subclass");
    }

    /**
     * Retrieve overall information about an application package that is
     * installed on the system. This method can be used for retrieving
     * information about packages for which multiple versions can be installed
     * at the time. Currently only packages hosting static shared libraries can
     * have multiple installed versions. The method can also be used to get info
     * for a package that has a single version installed by passing
     * {@link #VERSION_CODE_HIGHEST} in the {@link VersionedPackage}
     * constructor.
     *
     * @param versionedPackage The versioned package for which to query.
     * @param flags Additional option flags to modify the data returned.
     * @return A PackageInfo object containing information about the package. If
     *         flag {@code MATCH_UNINSTALLED_PACKAGES} is set and if the package
     *         is not found in the list of installed applications, the package
     *         information is retrieved from the list of uninstalled
     *         applications (which includes installed applications as well as
     *         applications with data directory i.e. applications which had been
     *         deleted with {@code DELETE_KEEP_DATA} flag set).
     * @throws NameNotFoundException if no such package is available to the
     *             caller.
     * @deprecated Use {@link #getPackageInfo(VersionedPackage, PackageInfoFlags)} instead.
     */
    @Deprecated
    public abstract PackageInfo getPackageInfo(@NonNull VersionedPackage versionedPackage,
            int flags) throws NameNotFoundException;

    /**
     * See {@link #getPackageInfo(VersionedPackage, int)}
     */
    @NonNull
    public PackageInfo getPackageInfo(@NonNull VersionedPackage versionedPackage,
            @NonNull PackageInfoFlags flags) throws NameNotFoundException {
        throw new UnsupportedOperationException(
                "getPackageInfo not implemented in subclass");
    }

    /**
     * Retrieve overall information about an application package that is
     * installed on the system.
     *
     * @param packageName The full name (i.e. com.google.apps.contacts) of the
     *            desired package.
     * @param flags Additional option flags to modify the data returned.
     * @param userId The user id.
     * @return A PackageInfo object containing information about the package. If
     *         flag {@code MATCH_UNINSTALLED_PACKAGES} is set and if the package
     *         is not found in the list of installed applications, the package
     *         information is retrieved from the list of uninstalled
     *         applications (which includes installed applications as well as
     *         applications with data directory i.e. applications which had been
     *         deleted with {@code DELETE_KEEP_DATA} flag set).
     * @throws NameNotFoundException if no such package is available to the
     *             caller.
     * @deprecated Use {@link #getPackageInfoAsUser(String, PackageInfoFlags, int)} instead.
     * @hide
     */
    @Deprecated
    @SuppressWarnings("HiddenAbstractMethod")
    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS)
    @UnsupportedAppUsage
    public abstract PackageInfo getPackageInfoAsUser(@NonNull String packageName,
            int flags, @UserIdInt int userId) throws NameNotFoundException;

    /**
     * See {@link #getPackageInfoAsUser(String, int, int)}
     * @hide
     */
    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS)
    @NonNull
    public PackageInfo getPackageInfoAsUser(@NonNull String packageName,
            @NonNull PackageInfoFlags flags, @UserIdInt int userId) throws NameNotFoundException {
        throw new UnsupportedOperationException(
                "getPackageInfoAsUser not implemented in subclass");
    }

    /**
     * Map from the current package names in use on the device to whatever
     * the current canonical name of that package is.
     * @param packageNames Array of current names to be mapped.
     * @return Returns an array of the same size as the original, containing
     * the canonical name for each package.
     */
    public abstract String[] currentToCanonicalPackageNames(@NonNull String[] packageNames);

    /**
     * Map from a packages canonical name to the current name in use on the device.
     * @param packageNames Array of new names to be mapped.
     * @return Returns an array of the same size as the original, containing
     * the current name for each package.
     */
    public abstract String[] canonicalToCurrentPackageNames(@NonNull String[] packageNames);

    /**
     * Returns a "good" intent to launch a front-door activity in a package.
     * This is used, for example, to implement an "open" button when browsing
     * through packages.  The current implementation looks first for a main
     * activity in the category {@link Intent#CATEGORY_INFO}, and next for a
     * main activity in the category {@link Intent#CATEGORY_LAUNCHER}. Returns
     * <code>null</code> if neither are found.
     *
     * <p>Consider using {@link #getLaunchIntentSenderForPackage(String)} if
     * the caller is not allowed to query for the <code>packageName</code>.
     *
     * @param packageName The name of the package to inspect.
     *
     * @return A fully-qualified {@link Intent} that can be used to launch the
     * main activity in the package. Returns <code>null</code> if the package
     * does not contain such an activity, or if <em>packageName</em> is not
     * recognized.
     *
     * @see #getLaunchIntentSenderForPackage(String)
     */
    public abstract @Nullable Intent getLaunchIntentForPackage(@NonNull String packageName);

    /**
     * Return a "good" intent to launch a front-door Leanback activity in a
     * package, for use for example to implement an "open" button when browsing
     * through packages. The current implementation will look for a main
     * activity in the category {@link Intent#CATEGORY_LEANBACK_LAUNCHER}, or
     * return null if no main leanback activities are found.
     *
     * @param packageName The name of the package to inspect.
     * @return Returns either a fully-qualified Intent that can be used to launch
     *         the main Leanback activity in the package, or null if the package
     *         does not contain such an activity.
     */
    public abstract @Nullable Intent getLeanbackLaunchIntentForPackage(@NonNull String packageName);

    /**
     * Return a "good" intent to launch a front-door Car activity in a
     * package, for use for example to implement an "open" button when browsing
     * through packages. The current implementation will look for a main
     * activity in the category {@link Intent#CATEGORY_CAR_LAUNCHER}, or
     * return null if no main car activities are found.
     *
     * @param packageName The name of the package to inspect.
     * @return Returns either a fully-qualified Intent that can be used to launch
     *         the main Car activity in the package, or null if the package
     *         does not contain such an activity.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    public abstract @Nullable Intent getCarLaunchIntentForPackage(@NonNull String packageName);

    /**
     * Returns an {@link IntentSender} that can be used to launch a front-door activity in a
     * package. This is used, for example, to implement an "open" button when browsing through
     * packages. The current implementation is the same with
     * {@link #getLaunchIntentForPackage(String)}. Instead of returning the {@link Intent}, it
     * returns the {@link IntentSender} which is not restricted by the package visibility.
     *
     * <p>The caller can invoke
     * {@link IntentSender#sendIntent(Context, int, Intent, IntentSender.OnFinished, Handler)}
     * to launch the activity. An {@link IntentSender.SendIntentException} is thrown if the
     * package does not contain such an activity, or if <em>packageName</em> is not recognized.
     *
     * @param packageName The name of the package to inspect.
     * @return Returns a {@link IntentSender} to launch the activity.
     *
     * @see #getLaunchIntentForPackage(String)
     */
    public @NonNull IntentSender getLaunchIntentSenderForPackage(@NonNull String packageName) {
        throw new UnsupportedOperationException("getLaunchIntentSenderForPackage not implemented"
                + "in subclass");
    }

    /**
     * Return an array of all of the POSIX secondary group IDs that have been
     * assigned to the given package.
     * <p>
     * Note that the same package may have different GIDs under different
     * {@link UserHandle} on the same device.
     *
     * @param packageName The full name (i.e. com.google.apps.contacts) of the
     *            desired package.
     * @return Returns an int array of the assigned GIDs, or null if there are
     *         none.
     * @throws NameNotFoundException if no such package is available to the
     *             caller.
     */
    public abstract int[] getPackageGids(@NonNull String packageName)
            throws NameNotFoundException;

    /**
     * Return an array of all of the POSIX secondary group IDs that have been
     * assigned to the given package.
     * <p>
     * Note that the same package may have different GIDs under different
     * {@link UserHandle} on the same device.
     *
     * @param packageName The full name (i.e. com.google.apps.contacts) of the
     *            desired package.
     * @return Returns an int array of the assigned gids, or null if there are
     *         none.
     * @throws NameNotFoundException if no such package is available to the
     *             caller.
     * @deprecated Use {@link #getPackageGids(String, PackageInfoFlags)} instead.
     */
    @Deprecated
    public abstract int[] getPackageGids(@NonNull String packageName, int flags)
            throws NameNotFoundException;

    /**
     * See {@link #getPackageGids(String, int)}.
     */
    @Nullable
    public int[] getPackageGids(@NonNull String packageName, @NonNull PackageInfoFlags flags)
            throws NameNotFoundException {
        throw new UnsupportedOperationException(
                "getPackageGids not implemented in subclass");
    }

    /**
     * Return the UID associated with the given package name.
     * <p>
     * Note that the same package will have different UIDs under different
     * {@link UserHandle} on the same device.
     *
     * @param packageName The full name (i.e. com.google.apps.contacts) of the
     *            desired package.
     * @return Returns an integer UID who owns the given package name.
     * @throws NameNotFoundException if no such package is available to the
     *             caller.
     * @deprecated Use {@link #getPackageUid(String, PackageInfoFlags)} instead.
     */
    @Deprecated
    public abstract int getPackageUid(@NonNull String packageName, int flags)
            throws NameNotFoundException;

    /**
     * See {@link #getPackageUid(String, int)}.
     */
    public int getPackageUid(@NonNull String packageName, @NonNull PackageInfoFlags flags)
            throws NameNotFoundException {
        throw new UnsupportedOperationException(
                "getPackageUid not implemented in subclass");
    }

    /**
     * Return the UID associated with the given package name.
     * <p>
     * Note that the same package will have different UIDs under different
     * {@link UserHandle} on the same device.
     *
     * @param packageName The full name (i.e. com.google.apps.contacts) of the
     *            desired package.
     * @param userId The user handle identifier to look up the package under.
     * @return Returns an integer UID who owns the given package name.
     * @throws NameNotFoundException if no such package is available to the
     *             caller.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    public abstract int getPackageUidAsUser(@NonNull String packageName, @UserIdInt int userId)
            throws NameNotFoundException;

    /**
     * Return the UID associated with the given package name.
     * <p>
     * Note that the same package will have different UIDs under different
     * {@link UserHandle} on the same device.
     *
     * @param packageName The full name (i.e. com.google.apps.contacts) of the
     *            desired package.
     * @param userId The user handle identifier to look up the package under.
     * @return Returns an integer UID who owns the given package name.
     * @throws NameNotFoundException if no such package is available to the
     *             caller.
     * @deprecated Use {@link #getPackageUidAsUser(String, PackageInfoFlags, int)} instead.
     * @hide
     */
    @Deprecated
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    public abstract int getPackageUidAsUser(@NonNull String packageName,
            int flags, @UserIdInt int userId) throws NameNotFoundException;

    /**
     * See {@link #getPackageUidAsUser(String, int, int)}.
     * @hide
     */
    public int getPackageUidAsUser(@NonNull String packageName, @NonNull PackageInfoFlags flags,
            @UserIdInt int userId) throws NameNotFoundException {
        throw new UnsupportedOperationException(
                "getPackageUidAsUser not implemented in subclass");
    }

    /**
     * Retrieve all of the information we know about a particular permission.
     *
     * @param permName The fully qualified name (i.e. com.google.permission.LOGIN)
     *            of the permission you are interested in.
     * @param flags Additional option flags to modify the data returned.
     * @return Returns a {@link PermissionInfo} containing information about the
     *         permission.
     * @throws NameNotFoundException if a package with the given name cannot be
     *             found on the system.
     */
    //@Deprecated
    public abstract PermissionInfo getPermissionInfo(@NonNull String permName,
            @PermissionInfoFlags int flags) throws NameNotFoundException;

    /**
     * Query for all of the permissions associated with a particular group.
     *
     * @param permissionGroup The fully qualified name (i.e. com.google.permission.LOGIN)
     *            of the permission group you are interested in. Use {@code null} to
     *            find all of the permissions not associated with a group.
     * @param flags Additional option flags to modify the data returned.
     * @return Returns a list of {@link PermissionInfo} containing information
     *         about all of the permissions in the given group.
     * @throws NameNotFoundException if a group with the given name cannot be
     *             found on the system.
     */
    //@Deprecated
    @NonNull
    public abstract List<PermissionInfo> queryPermissionsByGroup(@Nullable String permissionGroup,
            @PermissionInfoFlags int flags) throws NameNotFoundException;

    /**
     * Returns true if some permissions are individually controlled.
     *
     * <p>The user usually grants and revokes permission-groups. If this option is set some
     * dangerous system permissions can be revoked/granted by the user separately from their group.
     *
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    public abstract boolean arePermissionsIndividuallyControlled();

    /**
     * Returns true if wireless consent mode is enabled
     *
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    public abstract boolean isWirelessConsentModeEnabled();

    /**
     * Retrieve all of the information we know about a particular group of
     * permissions.
     *
     * @param groupName The fully qualified name (i.e.
     *            com.google.permission_group.APPS) of the permission you are
     *            interested in.
     * @param flags Additional option flags to modify the data returned.
     * @return Returns a {@link PermissionGroupInfo} containing information
     *         about the permission.
     * @throws NameNotFoundException if a package with the given name cannot be
     *             found on the system.
     */
    //@Deprecated
    @NonNull
    public abstract PermissionGroupInfo getPermissionGroupInfo(@NonNull String groupName,
            @PermissionGroupInfoFlags int flags) throws NameNotFoundException;

    /**
     * Retrieve all of the known permission groups in the system.
     *
     * @param flags Additional option flags to modify the data returned.
     * @return Returns a list of {@link PermissionGroupInfo} containing
     *         information about all of the known permission groups.
     */
    //@Deprecated
    @NonNull
    public abstract List<PermissionGroupInfo> getAllPermissionGroups(
            @PermissionGroupInfoFlags int flags);

    /**
     * Get the platform-defined permissions which belong to a particular permission group.
     *
     * @param permissionGroupName the permission group whose permissions are desired
     * @param executor the {@link Executor} on which to invoke the callback
     * @param callback the callback which will receive a list of the platform-defined permissions in
     *                 the group, or empty if the group is not a valid platform-defined permission
     *                 group, or there was an exception
     */
    public void getPlatformPermissionsForGroup(@NonNull String permissionGroupName,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<List<String>> callback) {}

    /**
     * Get the platform-defined permission group of a particular permission, if the permission is a
     * platform-defined permission.
     *
     * @param permissionName the permission whose group is desired
     * @param executor the {@link Executor} on which to invoke the callback
     * @param callback the callback which will receive the name of the permission group this
     *                 permission belongs to, or {@code null} if it has no group, is not a
     *                 platform-defined permission, or there was an exception
     */
    public void getGroupOfPlatformPermission(@NonNull String permissionName,
            @NonNull @CallbackExecutor Executor executor, @NonNull Consumer<String> callback) {}

    /**
     * Retrieve all of the information we know about a particular
     * package/application.
     *
     * @param packageName The full name (i.e. com.google.apps.contacts) of an
     *            application.
     * @param flags Additional option flags to modify the data returned.
     * @return An {@link ApplicationInfo} containing information about the
     *         package. If flag {@code MATCH_UNINSTALLED_PACKAGES} is set and if
     *         the package is not found in the list of installed applications,
     *         the application information is retrieved from the list of
     *         uninstalled applications (which includes installed applications
     *         as well as applications with data directory i.e. applications
     *         which had been deleted with {@code DELETE_KEEP_DATA} flag set).
     * @throws NameNotFoundException if a package with the given name cannot be
     *             found on the system.
     * @deprecated Use {@link #getApplicationInfo(String, ApplicationInfoFlags)} instead.
     */
    @NonNull
    @Deprecated
    public abstract ApplicationInfo getApplicationInfo(@NonNull String packageName,
            int flags) throws NameNotFoundException;

    /**
     * See {@link #getApplicationInfo(String, int)}.
     */
    @NonNull
    public ApplicationInfo getApplicationInfo(@NonNull String packageName,
            @NonNull ApplicationInfoFlags flags) throws NameNotFoundException {
        throw new UnsupportedOperationException(
                "getApplicationInfo not implemented in subclass");
    }

    /**
     * @deprecated Use {@link #getApplicationInfoAsUser(String, ApplicationInfoFlags, int)} instead.
     * {@hide}
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @NonNull
    @UnsupportedAppUsage
    @Deprecated
    public abstract ApplicationInfo getApplicationInfoAsUser(@NonNull String packageName,
            int flags, @UserIdInt int userId) throws NameNotFoundException;

    /** {@hide} */
    @NonNull
    public ApplicationInfo getApplicationInfoAsUser(@NonNull String packageName,
            @NonNull ApplicationInfoFlags flags, @UserIdInt int userId)
            throws NameNotFoundException {
        throw new UnsupportedOperationException(
                "getApplicationInfoAsUser not implemented in subclass");
    }

    /**
     * Retrieve all of the information we know about a particular
     * package/application, for a specific user.
     *
     * @param packageName The full name (i.e. com.google.apps.contacts) of an
     *            application.
     * @param flags Additional option flags to modify the data returned.
     * @return An {@link ApplicationInfo} containing information about the
     *         package. If flag {@code MATCH_UNINSTALLED_PACKAGES} is set and if
     *         the package is not found in the list of installed applications,
     *         the application information is retrieved from the list of
     *         uninstalled applications (which includes installed applications
     *         as well as applications with data directory i.e. applications
     *         which had been deleted with {@code DELETE_KEEP_DATA} flag set).
     * @throws NameNotFoundException if a package with the given name cannot be
     *             found on the system.
     * @deprecated Use {@link #getApplicationInfoAsUser(String, ApplicationInfoFlags, UserHandle)}
     * instead.
     * @hide
     */
    @NonNull
    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS)
    @SystemApi
    @Deprecated
    public ApplicationInfo getApplicationInfoAsUser(@NonNull String packageName,
            int flags, @NonNull UserHandle user)
            throws NameNotFoundException {
        return getApplicationInfoAsUser(packageName, flags, user.getIdentifier());
    }

    /**
     * See {@link #getApplicationInfoAsUser(String, int, UserHandle)}.
     * @hide
     */
    @NonNull
    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS)
    @SystemApi
    public ApplicationInfo getApplicationInfoAsUser(@NonNull String packageName,
            @NonNull ApplicationInfoFlags flags, @NonNull UserHandle user)
            throws NameNotFoundException {
        return getApplicationInfoAsUser(packageName, flags, user.getIdentifier());
    }

    /**
     * @return The target SDK version for the given package name.
     * @throws NameNotFoundException if a package with the given name cannot be found on the system.
     */
    @IntRange(from = 0)
    public int getTargetSdkVersion(@NonNull String packageName) throws NameNotFoundException {
        throw new UnsupportedOperationException();
    }

    /**
     * Retrieve all of the information we know about a particular activity
     * class.
     *
     * @param component The full component name (i.e.
     *            com.google.apps.contacts/com.google.apps.contacts.
     *            ContactsList) of an Activity class.
     * @param flags Additional option flags to modify the data returned.
     * @return An {@link ActivityInfo} containing information about the
     *         activity.
     * @throws NameNotFoundException if a package with the given name cannot be
     *             found on the system.
     * @deprecated Use {@link #getActivityInfo(ComponentName, ComponentInfoFlags)} instead.
     */
    @Deprecated
    @NonNull
    public abstract ActivityInfo getActivityInfo(@NonNull ComponentName component,
            int flags) throws NameNotFoundException;

    /**
     * See {@link #getActivityInfo(ComponentName, int)}.
     */
    @NonNull
    public ActivityInfo getActivityInfo(@NonNull ComponentName component,
            @NonNull ComponentInfoFlags flags) throws NameNotFoundException {
        throw new UnsupportedOperationException(
                "getActivityInfo not implemented in subclass");
    }

    /**
     * Retrieve all of the information we know about a particular receiver
     * class.
     *
     * @param component The full component name (i.e.
     *            com.google.apps.calendar/com.google.apps.calendar.
     *            CalendarAlarm) of a Receiver class.
     * @param flags Additional option flags to modify the data returned.
     * @return An {@link ActivityInfo} containing information about the
     *         receiver.
     * @throws NameNotFoundException if a package with the given name cannot be
     *             found on the system.
     * @deprecated Use {@link #getReceiverInfo(ComponentName, ComponentInfoFlags)} instead.
     */
    @Deprecated
    @NonNull
    public abstract ActivityInfo getReceiverInfo(@NonNull ComponentName component,
            int flags) throws NameNotFoundException;

    /**
     * See {@link #getReceiverInfo(ComponentName, int)}.
     */
    @NonNull
    public ActivityInfo getReceiverInfo(@NonNull ComponentName component,
            @NonNull ComponentInfoFlags flags) throws NameNotFoundException {
        throw new UnsupportedOperationException(
                "getReceiverInfo not implemented in subclass");
    }

    /**
     * Retrieve all of the information we know about a particular service class.
     *
     * @param component The full component name (i.e.
     *            com.google.apps.media/com.google.apps.media.
     *            BackgroundPlayback) of a Service class.
     * @param flags Additional option flags to modify the data returned.
     * @return A {@link ServiceInfo} object containing information about the
     *         service.
     * @throws NameNotFoundException if the component cannot be found on the system.
     * @deprecated Use {@link #getServiceInfo(ComponentName, ComponentInfoFlags)} instead.
     */
    @Deprecated
    @NonNull
    public abstract ServiceInfo getServiceInfo(@NonNull ComponentName component,
            int flags) throws NameNotFoundException;

    /**
     * See {@link #getServiceInfo(ComponentName, int)}.
     */
    @NonNull
    public ServiceInfo getServiceInfo(@NonNull ComponentName component,
            @NonNull ComponentInfoFlags flags) throws NameNotFoundException {
        throw new UnsupportedOperationException(
                "getServiceInfo not implemented in subclass");
    }

    /**
     * Retrieve all of the information we know about a particular content
     * provider class.
     *
     * @param component The full component name (i.e.
     *            com.google.providers.media/com.google.providers.media.
     *            MediaProvider) of a ContentProvider class.
     * @param flags Additional option flags to modify the data returned.
     * @return A {@link ProviderInfo} object containing information about the
     *         provider.
     * @throws NameNotFoundException if a package with the given name cannot be
     *             found on the system.
     * @deprecated Use {@link #getProviderInfo(ComponentName, ComponentInfoFlags)} instead.
     */
    @Deprecated
    @NonNull
    public abstract ProviderInfo getProviderInfo(@NonNull ComponentName component,
            int flags) throws NameNotFoundException;

    /**
     * See {@link #getProviderInfo(ComponentName, int)}.
     */
    @NonNull
    public ProviderInfo getProviderInfo(@NonNull ComponentName component,
            @NonNull ComponentInfoFlags flags) throws NameNotFoundException {
        throw new UnsupportedOperationException(
                "getProviderInfo not implemented in subclass");
    }

    /**
     * Retrieve information for a particular module.
     *
     * @param packageName The name of the module.
     * @param flags Additional option flags to modify the data returned.
     * @return A {@link ModuleInfo} object containing information about the
     *         module.
     * @throws NameNotFoundException if a module with the given name cannot be
     *             found on the system.
     */
    @NonNull
    public ModuleInfo getModuleInfo(@NonNull String packageName, @ModuleInfoFlags int flags)
            throws NameNotFoundException {
        throw new UnsupportedOperationException(
                "getModuleInfo not implemented in subclass");
    }

    /**
     * Return a List of all modules that are installed.
     *
     * @param flags Additional option flags to modify the data returned.
     * @return A {@link List} of {@link ModuleInfo} objects, one for each installed
     *         module, containing information about the module. In the unlikely case
     *         there are no installed modules, an empty list is returned.
     */
    @NonNull
    public List<ModuleInfo> getInstalledModules(@InstalledModulesFlags int flags) {
        throw new UnsupportedOperationException(
                "getInstalledModules not implemented in subclass");
    }

    /**
     * Return a List of all packages that are installed for the current user.
     *
     * @param flags Additional option flags to modify the data returned.
     * @return A List of PackageInfo objects, one for each installed package,
     *         containing information about the package. In the unlikely case
     *         there are no installed packages, an empty list is returned. If
     *         flag {@code MATCH_UNINSTALLED_PACKAGES} is set, the package
     *         information is retrieved from the list of uninstalled
     *         applications (which includes installed applications as well as
     *         applications with data directory i.e. applications which had been
     *         deleted with {@code DELETE_KEEP_DATA} flag set).
     * @deprecated Use {@link #getInstalledPackages(PackageInfoFlags)} instead.
     */
    @Deprecated
    @NonNull
    public abstract List<PackageInfo> getInstalledPackages(int flags);

    /**
     * See {@link #getInstalledPackages(int)}.
     * @param flags
     */
    @NonNull
    public List<PackageInfo> getInstalledPackages(@NonNull PackageInfoFlags flags) {
        throw new UnsupportedOperationException(
                "getInstalledPackages not implemented in subclass");
    }

    /**
     * Return a List of all installed packages that are currently holding any of
     * the given permissions.
     *
     * @param flags Additional option flags to modify the data returned.
     * @return A List of PackageInfo objects, one for each installed package
     *         that holds any of the permissions that were provided, containing
     *         information about the package. If no installed packages hold any
     *         of the permissions, an empty list is returned. If flag
     *         {@code MATCH_UNINSTALLED_PACKAGES} is set, the package
     *         information is retrieved from the list of uninstalled
     *         applications (which includes installed applications as well as
     *         applications with data directory i.e. applications which had been
     *         deleted with {@code DELETE_KEEP_DATA} flag set).
     * @deprecated Use {@link #getPackagesHoldingPermissions(String[], PackageInfoFlags)} instead.
     */
    @Deprecated
    @NonNull
    public abstract List<PackageInfo> getPackagesHoldingPermissions(
            @NonNull String[] permissions, int flags);

    /**
     * See {@link #getPackagesHoldingPermissions(String[], int)}.
     */
    @NonNull
    public List<PackageInfo> getPackagesHoldingPermissions(
            @NonNull String[] permissions, @NonNull PackageInfoFlags flags) {
        throw new UnsupportedOperationException(
                "getPackagesHoldingPermissions not implemented in subclass");
    }

    /**
     * Return a List of all packages that are installed on the device, for a
     * specific user.
     *
     * @param flags Additional option flags to modify the data returned.
     * @param userId The user for whom the installed packages are to be listed
     * @return A List of PackageInfo objects, one for each installed package,
     *         containing information about the package. In the unlikely case
     *         there are no installed packages, an empty list is returned. If
     *         flag {@code MATCH_UNINSTALLED_PACKAGES} is set, the package
     *         information is retrieved from the list of uninstalled
     *         applications (which includes installed applications as well as
     *         applications with data directory i.e. applications which had been
     *         deleted with {@code DELETE_KEEP_DATA} flag set).
     * @deprecated Use {@link #getInstalledPackagesAsUser(PackageInfoFlags, int)} instead.
     * @hide
     */
    @Deprecated
    @SuppressWarnings("HiddenAbstractMethod")
    @NonNull
    @SystemApi
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
    public abstract List<PackageInfo> getInstalledPackagesAsUser(int flags,
            @UserIdInt int userId);

    /**
     * See {@link #getInstalledPackagesAsUser(int, int)}.
     * @hide
     */
    @NonNull
    @SystemApi
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
    public List<PackageInfo> getInstalledPackagesAsUser(@NonNull PackageInfoFlags flags,
            @UserIdInt int userId) {
        throw new UnsupportedOperationException(
                "getApplicationInfoAsUser not implemented in subclass");
    }

    /**
     * Check whether a particular package has been granted a particular
     * permission.
     *
     * @param permName The name of the permission you are checking for.
     * @param packageName The name of the package you are checking against.
     *
     * @return If the package has the permission, PERMISSION_GRANTED is
     * returned.  If it does not have the permission, PERMISSION_DENIED
     * is returned.
     *
     * @see #PERMISSION_GRANTED
     * @see #PERMISSION_DENIED
     */
    @CheckResult
    @PermissionResult
    public abstract int checkPermission(@NonNull String permName,
            @NonNull String packageName);

    /**
     * Checks whether a particular permissions has been revoked for a
     * package by policy. Typically the device owner or the profile owner
     * may apply such a policy. The user cannot grant policy revoked
     * permissions, hence the only way for an app to get such a permission
     * is by a policy change.
     *
     * @param permName The name of the permission you are checking for.
     * @param packageName The name of the package you are checking against.
     *
     * @return Whether the permission is restricted by policy.
     */
    @CheckResult
    //@Deprecated
    public abstract boolean isPermissionRevokedByPolicy(@NonNull String permName,
            @NonNull String packageName);

    /**
     * Gets the package name of the component controlling runtime permissions.
     *
     * @return the package name of the component controlling runtime permissions
     *
     * @hide
     */
    @NonNull
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    @UnsupportedAppUsage
    public String getPermissionControllerPackageName() {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Returns the package name of the component implementing sdk sandbox service.
     *
     * @return the package name of the component implementing sdk sandbox service
     *
     * @hide
     */
    @NonNull
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    public String getSdkSandboxPackageName() {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Add a new dynamic permission to the system.  For this to work, your
     * package must have defined a permission tree through the
     * {@link android.R.styleable#AndroidManifestPermissionTree
     * &lt;permission-tree&gt;} tag in its manifest.  A package can only add
     * permissions to trees that were defined by either its own package or
     * another with the same user id; a permission is in a tree if it
     * matches the name of the permission tree + ".": for example,
     * "com.foo.bar" is a member of the permission tree "com.foo".
     *
     * <p>It is good to make your permission tree name descriptive, because you
     * are taking possession of that entire set of permission names.  Thus, it
     * must be under a domain you control, with a suffix that will not match
     * any normal permissions that may be declared in any applications that
     * are part of that domain.
     *
     * <p>New permissions must be added before
     * any .apks are installed that use those permissions.  Permissions you
     * add through this method are remembered across reboots of the device.
     * If the given permission already exists, the info you supply here
     * will be used to update it.
     *
     * @param info Description of the permission to be added.
     *
     * @return Returns true if a new permission was created, false if an
     * existing one was updated.
     *
     * @throws SecurityException if you are not allowed to add the
     * given permission name.
     *
     * @see #removePermission(String)
     */
    //@Deprecated
    public abstract boolean addPermission(@NonNull PermissionInfo info);

    /**
     * Like {@link #addPermission(PermissionInfo)} but asynchronously
     * persists the package manager state after returning from the call,
     * allowing it to return quicker and batch a series of adds at the
     * expense of no guarantee the added permission will be retained if
     * the device is rebooted before it is written.
     */
    //@Deprecated
    public abstract boolean addPermissionAsync(@NonNull PermissionInfo info);

    /**
     * Removes a permission that was previously added with
     * {@link #addPermission(PermissionInfo)}.  The same ownership rules apply
     * -- you are only allowed to remove permissions that you are allowed
     * to add.
     *
     * @param permName The name of the permission to remove.
     *
     * @throws SecurityException if you are not allowed to remove the
     * given permission name.
     *
     * @see #addPermission(PermissionInfo)
     */
    //@Deprecated
    public abstract void removePermission(@NonNull String permName);

    /**
     * Permission flags set when granting or revoking a permission.
     *
     * @hide
     */
    @SystemApi
    @IntDef(prefix = { "FLAG_PERMISSION_" }, value = {
            FLAG_PERMISSION_USER_SET,
            FLAG_PERMISSION_USER_FIXED,
            FLAG_PERMISSION_POLICY_FIXED,
            FLAG_PERMISSION_REVOKE_ON_UPGRADE,
            FLAG_PERMISSION_SYSTEM_FIXED,
            FLAG_PERMISSION_GRANTED_BY_DEFAULT,
            FLAG_PERMISSION_USER_SENSITIVE_WHEN_GRANTED,
            FLAG_PERMISSION_USER_SENSITIVE_WHEN_DENIED,
            /*
            FLAG_PERMISSION_REVOKE_WHEN_REQUESED
            */
            FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT,
            FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT,
            FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT,
            FLAG_PERMISSION_APPLY_RESTRICTION,
            FLAG_PERMISSION_GRANTED_BY_ROLE,
            FLAG_PERMISSION_REVOKED_COMPAT,
            FLAG_PERMISSION_ONE_TIME,
            FLAG_PERMISSION_AUTO_REVOKED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PermissionFlags {}

    /**
     * Grant a runtime permission to an application which the application does not
     * already have. The permission must have been requested by the application.
     * If the application is not allowed to hold the permission, a {@link
     * java.lang.SecurityException} is thrown. If the package or permission is
     * invalid, a {@link java.lang.IllegalArgumentException} is thrown.
     * <p>
     * <strong>Note: </strong>Using this API requires holding
     * android.permission.GRANT_RUNTIME_PERMISSIONS and if the user id is
     * not the current user android.permission.INTERACT_ACROSS_USERS_FULL.
     * </p>
     *
     * @param packageName The package to which to grant the permission.
     * @param permName The permission name to grant.
     * @param user The user for which to grant the permission.
     *
     * @see #revokeRuntimePermission(String, String, android.os.UserHandle)
     *
     * @hide
     */
    //@Deprecated
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    @RequiresPermission(android.Manifest.permission.GRANT_RUNTIME_PERMISSIONS)
    public abstract void grantRuntimePermission(@NonNull String packageName,
            @NonNull String permName, @NonNull UserHandle user);

    /**
     * Revoke a runtime permission that was previously granted by {@link
     * #grantRuntimePermission(String, String, android.os.UserHandle)}. The
     * permission must have been requested by and granted to the application.
     * If the application is not allowed to hold the permission, a {@link
     * java.lang.SecurityException} is thrown. If the package or permission is
     * invalid, a {@link java.lang.IllegalArgumentException} is thrown.
     * <p>
     * <strong>Note: </strong>Using this API requires holding
     * android.permission.REVOKE_RUNTIME_PERMISSIONS and if the user id is
     * not the current user android.permission.INTERACT_ACROSS_USERS_FULL.
     * </p>
     *
     * @param packageName The package from which to revoke the permission.
     * @param permName The permission name to revoke.
     * @param user The user for which to revoke the permission.
     *
     * @see #grantRuntimePermission(String, String, android.os.UserHandle)
     *
     * @hide
     */
    //@Deprecated
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    @RequiresPermission(android.Manifest.permission.REVOKE_RUNTIME_PERMISSIONS)
    public abstract void revokeRuntimePermission(@NonNull String packageName,
            @NonNull String permName, @NonNull UserHandle user);

    /**
     * Revoke a runtime permission that was previously granted by {@link
     * #grantRuntimePermission(String, String, android.os.UserHandle)}. The
     * permission must have been requested by and granted to the application.
     * If the application is not allowed to hold the permission, a {@link
     * java.lang.SecurityException} is thrown. If the package or permission is
     * invalid, a {@link java.lang.IllegalArgumentException} is thrown.
     * <p>
     * <strong>Note: </strong>Using this API requires holding
     * android.permission.REVOKE_RUNTIME_PERMISSIONS and if the user id is
     * not the current user android.permission.INTERACT_ACROSS_USERS_FULL.
     * </p>
     *
     * @param packageName The package from which to revoke the permission.
     * @param permName The permission name to revoke.
     * @param user The user for which to revoke the permission.
     * @param reason The reason for the revoke.
     *
     * @see #grantRuntimePermission(String, String, android.os.UserHandle)
     *
     * @hide
     */
    //@Deprecated
    @SystemApi
    @RequiresPermission(android.Manifest.permission.REVOKE_RUNTIME_PERMISSIONS)
    public void revokeRuntimePermission(@NonNull String packageName,
            @NonNull String permName, @NonNull UserHandle user, @NonNull String reason) {
        revokeRuntimePermission(packageName, permName, user);
    }

    /**
     * Gets the state flags associated with a permission.
     *
     * @param permName The permission for which to get the flags.
     * @param packageName The package name for which to get the flags.
     * @param user The user for which to get permission flags.
     * @return The permission flags.
     *
     * @hide
     */
    //@Deprecated
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
            android.Manifest.permission.REVOKE_RUNTIME_PERMISSIONS,
            android.Manifest.permission.GET_RUNTIME_PERMISSIONS
    })
    @PermissionFlags
    public abstract int getPermissionFlags(@NonNull String permName,
            @NonNull String packageName, @NonNull UserHandle user);

    /**
     * Updates the flags associated with a permission by replacing the flags in
     * the specified mask with the provided flag values.
     *
     * @param permName The permission for which to update the flags.
     * @param packageName The package name for which to update the flags.
     * @param flagMask The flags which to replace.
     * @param flagValues The flags with which to replace.
     * @param user The user for which to update the permission flags.
     *
     * @hide
     */
    //@Deprecated
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
            android.Manifest.permission.REVOKE_RUNTIME_PERMISSIONS
    })
    public abstract void updatePermissionFlags(@NonNull String permName,
            @NonNull String packageName, @PermissionFlags int flagMask,
            @PermissionFlags int flagValues, @NonNull UserHandle user);

    /**
     * Gets the restricted permissions that have been whitelisted and the app
     * is allowed to have them granted in their full form.
     *
     * <p> Permissions can be hard restricted which means that the app cannot hold
     * them or soft restricted where the app can hold the permission but in a weaker
     * form. Whether a permission is {@link PermissionInfo#FLAG_HARD_RESTRICTED hard
     * restricted} or {@link PermissionInfo#FLAG_SOFT_RESTRICTED soft restricted}
     * depends on the permission declaration. Whitelisting a hard restricted permission
     * allows for the to hold that permission and whitelisting a soft restricted
     * permission allows the app to hold the permission in its full, unrestricted form.
     *
     * <p><ol>There are four allowlists:
     *
     * <li>one for cases where the system permission policy whitelists a permission
     * This list corresponds to the{@link #FLAG_PERMISSION_WHITELIST_SYSTEM} flag.
     * Can only be accessed by pre-installed holders of a dedicated permission.
     *
     * <li>one for cases where the system whitelists the permission when upgrading
     * from an OS version in which the permission was not restricted to an OS version
     * in which the permission is restricted. This list corresponds to the {@link
     * #FLAG_PERMISSION_WHITELIST_UPGRADE} flag. Can be accessed by pre-installed
     * holders of a dedicated permission or the installer on record.
     *
     * <li>one for cases where the installer of the package whitelists a permission.
     * This list corresponds to the {@link #FLAG_PERMISSION_WHITELIST_INSTALLER} flag.
     * Can be accessed by pre-installed holders of a dedicated permission or the
     * installer on record.
     * </ol>
     *
     * <p>
     * <strong>Note: </strong>In retrospect it would have been preferred to use
     * more inclusive terminology when naming this API. Similar APIs added will
     * refrain from using the term "whitelist".
     * </p>
     *
     * @param packageName The app for which to get whitelisted permissions.
     * @param whitelistFlag The flag to determine which whitelist to query. Only one flag
     * can be passed.s
     * @return The whitelisted permissions that are on any of the whitelists you query for.
     *
     * @see #addWhitelistedRestrictedPermission(String, String, int)
     * @see #removeWhitelistedRestrictedPermission(String, String, int)
     * @see #FLAG_PERMISSION_WHITELIST_SYSTEM
     * @see #FLAG_PERMISSION_WHITELIST_UPGRADE
     * @see #FLAG_PERMISSION_WHITELIST_INSTALLER
     *
     * @throws SecurityException if you try to access a whitelist that you have no access to.
     */
    //@Deprecated
    @RequiresPermission(value = Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS,
            conditional = true)
    public @NonNull Set<String> getWhitelistedRestrictedPermissions(
            @NonNull String packageName, @PermissionWhitelistFlags int whitelistFlag) {
        return Collections.emptySet();
    }

    /**
     * Adds a whitelisted restricted permission for an app.
     *
     * <p> Permissions can be hard restricted which means that the app cannot hold
     * them or soft restricted where the app can hold the permission but in a weaker
     * form. Whether a permission is {@link PermissionInfo#FLAG_HARD_RESTRICTED hard
     * restricted} or {@link PermissionInfo#FLAG_SOFT_RESTRICTED soft restricted}
     * depends on the permission declaration. Whitelisting a hard restricted permission
     * allows for the to hold that permission and whitelisting a soft restricted
     * permission allows the app to hold the permission in its full, unrestricted form.
     *
     * <p><ol>There are four whitelists:
     *
     * <li>one for cases where the system permission policy whitelists a permission
     * This list corresponds to the {@link #FLAG_PERMISSION_WHITELIST_SYSTEM} flag.
     * Can only be modified by pre-installed holders of a dedicated permission.
     *
     * <li>one for cases where the system whitelists the permission when upgrading
     * from an OS version in which the permission was not restricted to an OS version
     * in which the permission is restricted. This list corresponds to the {@link
     * #FLAG_PERMISSION_WHITELIST_UPGRADE} flag. Can be modified by pre-installed
     * holders of a dedicated permission. The installer on record can only remove
     * permissions from this whitelist.
     *
     * <li>one for cases where the installer of the package whitelists a permission.
     * This list corresponds to the {@link #FLAG_PERMISSION_WHITELIST_INSTALLER} flag.
     * Can be modified by pre-installed holders of a dedicated permission or the installer
     * on record.
     * </ol>
     *
     * <p>You need to specify the whitelists for which to set the whitelisted permissions
     * which will clear the previous whitelisted permissions and replace them with the
     * provided ones.
     *
     * <p>
     * <strong>Note: </strong>In retrospect it would have been preferred to use
     * more inclusive terminology when naming this API. Similar APIs added will
     * refrain from using the term "whitelist".
     * </p>
     *
     * @param packageName The app for which to get whitelisted permissions.
     * @param permName The whitelisted permission to add.
     * @param whitelistFlags The whitelists to which to add. Passing multiple flags
     * updates all specified whitelists.
     * @return Whether the permission was added to the whitelist.
     *
     * @see #getWhitelistedRestrictedPermissions(String, int)
     * @see #removeWhitelistedRestrictedPermission(String, String, int)
     * @see #FLAG_PERMISSION_WHITELIST_SYSTEM
     * @see #FLAG_PERMISSION_WHITELIST_UPGRADE
     * @see #FLAG_PERMISSION_WHITELIST_INSTALLER
     *
     * @throws SecurityException if you try to modify a whitelist that you have no access to.
     */
    //@Deprecated
    @RequiresPermission(value = Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS,
            conditional = true)
    public boolean addWhitelistedRestrictedPermission(@NonNull String packageName,
            @NonNull String permName, @PermissionWhitelistFlags int whitelistFlags) {
        return false;
    }

    /**
     * Removes a whitelisted restricted permission for an app.
     *
     * <p> Permissions can be hard restricted which means that the app cannot hold
     * them or soft restricted where the app can hold the permission but in a weaker
     * form. Whether a permission is {@link PermissionInfo#FLAG_HARD_RESTRICTED hard
     * restricted} or {@link PermissionInfo#FLAG_SOFT_RESTRICTED soft restricted}
     * depends on the permission declaration. Whitelisting a hard restricted permission
     * allows for the to hold that permission and whitelisting a soft restricted
     * permission allows the app to hold the permission in its full, unrestricted form.
     *
     * <p><ol>There are four whitelists:
     *
     * <li>one for cases where the system permission policy whitelists a permission
     * This list corresponds to the {@link #FLAG_PERMISSION_WHITELIST_SYSTEM} flag.
     * Can only be modified by pre-installed holders of a dedicated permission.
     *
     * <li>one for cases where the system whitelists the permission when upgrading
     * from an OS version in which the permission was not restricted to an OS version
     * in which the permission is restricted. This list corresponds to the {@link
     * #FLAG_PERMISSION_WHITELIST_UPGRADE} flag. Can be modified by pre-installed
     * holders of a dedicated permission. The installer on record can only remove
     * permissions from this whitelist.
     *
     * <li>one for cases where the installer of the package whitelists a permission.
     * This list corresponds to the {@link #FLAG_PERMISSION_WHITELIST_INSTALLER} flag.
     * Can be modified by pre-installed holders of a dedicated permission or the installer
     * on record.
     *
     * <li>one for cases where the system exempts the permission when upgrading
     * from an OS version in which the permission was not restricted to an OS version
     * in which the permission is restricted. This list corresponds to the {@link
     * #FLAG_PERMISSION_WHITELIST_UPGRADE} flag. Can be modified by pre-installed
     * holders of a dedicated permission. The installer on record can only remove
     * permissions from this allowlist.
     * </ol>
     *
     * <p>You need to specify the whitelists for which to set the whitelisted permissions
     * which will clear the previous whitelisted permissions and replace them with the
     * provided ones.
     *
     * <p>
     * <strong>Note: </strong>In retrospect it would have been preferred to use
     * more inclusive terminology when naming this API. Similar APIs added will
     * refrain from using the term "whitelist".
     * </p>
     *
     * @param packageName The app for which to get whitelisted permissions.
     * @param permName The whitelisted permission to remove.
     * @param whitelistFlags The whitelists from which to remove. Passing multiple flags
     * updates all specified whitelists.
     * @return Whether the permission was removed from the whitelist.
     *
     * @see #getWhitelistedRestrictedPermissions(String, int)
     * @see #addWhitelistedRestrictedPermission(String, String, int)
     * @see #FLAG_PERMISSION_WHITELIST_SYSTEM
     * @see #FLAG_PERMISSION_WHITELIST_UPGRADE
     * @see #FLAG_PERMISSION_WHITELIST_INSTALLER
     *
     * @throws SecurityException if you try to modify a whitelist that you have no access to.
     */
    //@Deprecated
    @RequiresPermission(value = Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS,
        conditional = true)
    public boolean removeWhitelistedRestrictedPermission(@NonNull String packageName,
            @NonNull String permName, @PermissionWhitelistFlags int whitelistFlags) {
        return false;
    }

    /**
     * Marks an application exempt from having its permissions be automatically revoked when
     * the app is unused for an extended period of time.
     *
     * Only the installer on record that installed the given package is allowed to call this.
     *
     * Packages start in whitelisted state, and it is the installer's responsibility to
     * un-whitelist the packages it installs, unless auto-revoking permissions from that package
     * would cause breakages beyond having to re-request the permission(s).
     *
     * <p>
     * <strong>Note: </strong>In retrospect it would have been preferred to use
     * more inclusive terminology when naming this API. Similar APIs added will
     * refrain from using the term "whitelist".
     * </p>
     *
     * @param packageName The app for which to set exemption.
     * @param whitelisted Whether the app should be whitelisted.
     *
     * @return whether any change took effect.
     *
     * @see #isAutoRevokeWhitelisted
     *
     * @throws SecurityException if you you have no access to modify this.
     */
    //@Deprecated
    @RequiresPermission(value = Manifest.permission.WHITELIST_AUTO_REVOKE_PERMISSIONS,
            conditional = true)
    public boolean setAutoRevokeWhitelisted(@NonNull String packageName, boolean whitelisted) {
        return false;
    }

    /**
     * Checks whether an application is exempt from having its permissions be automatically revoked
     * when the app is unused for an extended period of time.
     *
     * Only the installer on record that installed the given package, or a holder of
     * {@code WHITELIST_AUTO_REVOKE_PERMISSIONS} is allowed to call this.
     *
     * <p>
     * <strong>Note: </strong>In retrospect it would have been preferred to use
     * more inclusive terminology when naming this API. Similar APIs added will
     * refrain from using the term "whitelist".
     * </p>
     *
     * @param packageName The app for which to set exemption.
     *
     * @return Whether the app is whitelisted.
     *
     * @see #setAutoRevokeWhitelisted
     *
     * @throws SecurityException if you you have no access to this.
     */
    //@Deprecated
    @RequiresPermission(value = Manifest.permission.WHITELIST_AUTO_REVOKE_PERMISSIONS,
            conditional = true)
    public boolean isAutoRevokeWhitelisted(@NonNull String packageName) {
        return false;
    }


    /**
     * Gets whether you should show UI with rationale for requesting a permission.
     * You should do this only if you do not have the permission and the context in
     * which the permission is requested does not clearly communicate to the user
     * what would be the benefit from grating this permission.
     *
     * @param permName A permission your app wants to request.
     * @return Whether you can show permission rationale UI.
     *
     * @hide
     */
    //@Deprecated
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    public abstract boolean shouldShowRequestPermissionRationale(@NonNull String permName);

    /**
     * Gets the localized label that corresponds to the option in settings for granting
     * background access.
     *
     * <p>The intended use is for apps to reference this label in its instruction for users to grant
     * a background permission.
     *
     * @return the localized label that corresponds to the settings option for granting
     * background access
     */
    @NonNull
    public CharSequence getBackgroundPermissionOptionLabel() {
        return "";
    }

    /**
     * Returns an {@link android.content.Intent} suitable for passing to
     * {@link android.app.Activity#startActivityForResult(android.content.Intent, int)}
     * which prompts the user to grant permissions to this application.
     *
     * @throws NullPointerException if {@code permissions} is {@code null} or empty.
     *
     * @hide
     */
    @NonNull
    @UnsupportedAppUsage
    public Intent buildRequestPermissionsIntent(@NonNull String[] permissions) {
        if (ArrayUtils.isEmpty(permissions)) {
           throw new IllegalArgumentException("permission cannot be null or empty");
        }
        Intent intent = new Intent(ACTION_REQUEST_PERMISSIONS);
        intent.putExtra(EXTRA_REQUEST_PERMISSIONS_NAMES, permissions);
        intent.setPackage(getPermissionControllerPackageName());
        return intent;
    }

    /**
     * Compare the signatures of two packages to determine if the same
     * signature appears in both of them.  If they do contain the same
     * signature, then they are allowed special privileges when working
     * with each other: they can share the same user-id, run instrumentation
     * against each other, etc.
     *
     * @param packageName1 First package name whose signature will be compared.
     * @param packageName2 Second package name whose signature will be compared.
     *
     * @return Returns an integer indicating whether all signatures on the
     * two packages match. The value is >= 0 ({@link #SIGNATURE_MATCH}) if
     * all signatures match or < 0 if there is not a match ({@link
     * #SIGNATURE_NO_MATCH} or {@link #SIGNATURE_UNKNOWN_PACKAGE}).
     *
     * @see #checkSignatures(int, int)
     */
    @CheckResult
    @SignatureResult
    public abstract int checkSignatures(@NonNull String packageName1,
            @NonNull String packageName2);

    /**
     * Like {@link #checkSignatures(String, String)}, but takes UIDs of
     * the two packages to be checked.  This can be useful, for example,
     * when doing the check in an IPC, where the UID is the only identity
     * available.  It is functionally identical to determining the package
     * associated with the UIDs and checking their signatures.
     *
     * @param uid1 First UID whose signature will be compared.
     * @param uid2 Second UID whose signature will be compared.
     *
     * @return Returns an integer indicating whether all signatures on the
     * two packages match. The value is >= 0 ({@link #SIGNATURE_MATCH}) if
     * all signatures match or < 0 if there is not a match ({@link
     * #SIGNATURE_NO_MATCH} or {@link #SIGNATURE_UNKNOWN_PACKAGE}).
     *
     * @see #checkSignatures(String, String)
     */
    @CheckResult
    public abstract @SignatureResult int checkSignatures(int uid1, int uid2);

    /**
     * Retrieve the names of all packages that are associated with a particular
     * user id.  In most cases, this will be a single package name, the package
     * that has been assigned that user id.  Where there are multiple packages
     * sharing the same user id through the "sharedUserId" mechanism, all
     * packages with that id will be returned.
     *
     * @param uid The user id for which you would like to retrieve the
     * associated packages.
     *
     * @return Returns an array of one or more packages assigned to the user
     * id, or null if there are no known packages with the given id.
     */
    public abstract @Nullable String[] getPackagesForUid(int uid);

    /**
     * Retrieve the official name associated with a uid. This name is
     * guaranteed to never change, though it is possible for the underlying
     * uid to be changed.  That is, if you are storing information about
     * uids in persistent storage, you should use the string returned
     * by this function instead of the raw uid.
     *
     * @param uid The uid for which you would like to retrieve a name.
     * @return Returns a unique name for the given uid, or null if the
     * uid is not currently assigned.
     */
    public abstract @Nullable String getNameForUid(int uid);

    /**
     * Retrieves the official names associated with each given uid.
     * @see #getNameForUid(int)
     *
     * @hide
     */
    @SuppressWarnings({"HiddenAbstractMethod", "NullableCollection"})
    @TestApi
    public abstract @Nullable String[] getNamesForUids(int[] uids);

    /**
     * Return the user id associated with a shared user name. Multiple
     * applications can specify a shared user name in their manifest and thus
     * end up using a common uid. This might be used for new applications
     * that use an existing shared user name and need to know the uid of the
     * shared user.
     *
     * @param sharedUserName The shared user name whose uid is to be retrieved.
     * @return Returns the UID associated with the shared user.
     * @throws NameNotFoundException if a package with the given name cannot be
     *             found on the system.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    public abstract int getUidForSharedUser(@NonNull String sharedUserName)
            throws NameNotFoundException;

    /**
     * Return a List of all application packages that are installed for the
     * current user. If flag GET_UNINSTALLED_PACKAGES has been set, a list of all
     * applications including those deleted with {@code DELETE_KEEP_DATA}
     * (partially installed apps with data directory) will be returned.
     *
     * @param flags Additional option flags to modify the data returned.
     * @return A List of ApplicationInfo objects, one for each installed
     *         application. In the unlikely case there are no installed
     *         packages, an empty list is returned. If flag
     *         {@code MATCH_UNINSTALLED_PACKAGES} is set, the application
     *         information is retrieved from the list of uninstalled
     *         applications (which includes installed applications as well as
     *         applications with data directory i.e. applications which had been
     *         deleted with {@code DELETE_KEEP_DATA} flag set).
     * @deprecated  Use {@link #getInstalledApplications(ApplicationInfoFlags)} instead.
     */
    @NonNull
    @Deprecated
    public abstract List<ApplicationInfo> getInstalledApplications(int flags);

    /**
     * See {@link #getInstalledApplications(int)}
     * @param flags
     */
    @NonNull
    public List<ApplicationInfo> getInstalledApplications(@NonNull ApplicationInfoFlags flags) {
        throw new UnsupportedOperationException(
                "getInstalledApplications not implemented in subclass");
    }
    /**
     * Return a List of all application packages that are installed on the
     * device, for a specific user. If flag GET_UNINSTALLED_PACKAGES has been
     * set, a list of all applications including those deleted with
     * {@code DELETE_KEEP_DATA} (partially installed apps with data directory)
     * will be returned.
     *
     * @param flags Additional option flags to modify the data returned.
     * @param userId The user for whom the installed applications are to be
     *            listed
     * @return A List of ApplicationInfo objects, one for each installed
     *         application. In the unlikely case there are no installed
     *         packages, an empty list is returned. If flag
     *         {@code MATCH_UNINSTALLED_PACKAGES} is set, the application
     *         information is retrieved from the list of uninstalled
     *         applications (which includes installed applications as well as
     *         applications with data directory i.e. applications which had been
     *         deleted with {@code DELETE_KEEP_DATA} flag set).
     * @deprecated  Use {@link #getInstalledApplicationsAsUser(ApplicationInfoFlags, int)} instead.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @NonNull
    @TestApi
    @Deprecated
    public abstract List<ApplicationInfo> getInstalledApplicationsAsUser(
            int flags, @UserIdInt int userId);

    /**
     * See {@link #getInstalledApplicationsAsUser(int, int}.
     * @hide
     */
    @NonNull
    @TestApi
    public List<ApplicationInfo> getInstalledApplicationsAsUser(
            @NonNull ApplicationInfoFlags flags, @UserIdInt int userId) {
        throw new UnsupportedOperationException(
                "getInstalledApplicationsAsUser not implemented in subclass");
    }

    /**
     * Gets the instant applications the user recently used.
     *
     * @return The instant app list.
     *
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    @RequiresPermission(Manifest.permission.ACCESS_INSTANT_APPS)
    public abstract @NonNull List<InstantAppInfo> getInstantApps();

    /**
     * Gets the icon for an instant application.
     *
     * @param packageName The app package name.
     *
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    @RequiresPermission(Manifest.permission.ACCESS_INSTANT_APPS)
    public abstract @Nullable Drawable getInstantAppIcon(String packageName);

    /**
     * Gets whether this application is an instant app.
     *
     * @return Whether caller is an instant app.
     *
     * @see #isInstantApp(String)
     * @see #updateInstantAppCookie(byte[])
     * @see #getInstantAppCookie()
     * @see #getInstantAppCookieMaxBytes()
     */
    public abstract boolean isInstantApp();

    /**
     * Gets whether the given package is an instant app.
     *
     * @param packageName The package to check
     * @return Whether the given package is an instant app.
     *
     * @see #isInstantApp()
     * @see #updateInstantAppCookie(byte[])
     * @see #getInstantAppCookie()
     * @see #getInstantAppCookieMaxBytes()
     * @see #clearInstantAppCookie()
     */
    public abstract boolean isInstantApp(@NonNull String packageName);

    /**
     * Gets the maximum size in bytes of the cookie data an instant app
     * can store on the device.
     *
     * @return The max cookie size in bytes.
     *
     * @see #isInstantApp()
     * @see #isInstantApp(String)
     * @see #updateInstantAppCookie(byte[])
     * @see #getInstantAppCookie()
     * @see #clearInstantAppCookie()
     */
    public abstract int getInstantAppCookieMaxBytes();

    /**
     * deprecated
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    public abstract int getInstantAppCookieMaxSize();

    /**
     * Gets the instant application cookie for this app. Non
     * instant apps and apps that were instant but were upgraded
     * to normal apps can still access this API. For instant apps
     * this cookie is cached for some time after uninstall while for
     * normal apps the cookie is deleted after the app is uninstalled.
     * The cookie is always present while the app is installed.
     *
     * @return The cookie.
     *
     * @see #isInstantApp()
     * @see #isInstantApp(String)
     * @see #updateInstantAppCookie(byte[])
     * @see #getInstantAppCookieMaxBytes()
     * @see #clearInstantAppCookie()
     */
    public abstract @NonNull byte[] getInstantAppCookie();

    /**
     * Clears the instant application cookie for the calling app.
     *
     * @see #isInstantApp()
     * @see #isInstantApp(String)
     * @see #getInstantAppCookieMaxBytes()
     * @see #getInstantAppCookie()
     * @see #clearInstantAppCookie()
     */
    public abstract void clearInstantAppCookie();

    /**
     * Updates the instant application cookie for the calling app. Non
     * instant apps and apps that were instant but were upgraded
     * to normal apps can still access this API. For instant apps
     * this cookie is cached for some time after uninstall while for
     * normal apps the cookie is deleted after the app is uninstalled.
     * The cookie is always present while the app is installed. The
     * cookie size is limited by {@link #getInstantAppCookieMaxBytes()}.
     * Passing <code>null</code> or an empty array clears the cookie.
     * </p>
     *
     * @param cookie The cookie data.
     *
     * @see #isInstantApp()
     * @see #isInstantApp(String)
     * @see #getInstantAppCookieMaxBytes()
     * @see #getInstantAppCookie()
     * @see #clearInstantAppCookie()
     *
     * @throws IllegalArgumentException if the array exceeds max cookie size.
     */
    public abstract void updateInstantAppCookie(@Nullable byte[] cookie);

    /**
     * @removed
     */
    @SuppressWarnings("HiddenAbstractMethod")
    public abstract boolean setInstantAppCookie(@Nullable byte[] cookie);

    /**
     * Get a list of shared libraries that are available on the
     * system.
     *
     * @return An array of shared library names that are
     * available on the system, or null if none are installed.
     *
     */
    @Nullable
    public abstract String[] getSystemSharedLibraryNames();

    /**
     * Get a list of shared libraries on the device.
     *
     * @param flags To filter the libraries to return.
     * @return The shared library list.
     *
     * @see #MATCH_UNINSTALLED_PACKAGES
     * @deprecated Use {@link #getSharedLibraries(PackageInfoFlags)} instead.
     */
    @Deprecated
    public abstract @NonNull List<SharedLibraryInfo> getSharedLibraries(int flags);

    /**
     * See {@link #getSharedLibraries(int)}.
     * @param flags
     */
    public @NonNull List<SharedLibraryInfo> getSharedLibraries(@NonNull PackageInfoFlags flags) {
        throw new UnsupportedOperationException(
                "getSharedLibraries() not implemented in subclass");
    }

    /**
     * Get a list of shared libraries on the device.
     *
     * @param flags To filter the libraries to return.
     * @param userId The user to query for.
     * @return The shared library list.
     *
     * @see #MATCH_FACTORY_ONLY
     * @see #MATCH_KNOWN_PACKAGES
     * @see #MATCH_ANY_USER
     * @see #MATCH_UNINSTALLED_PACKAGES
     *
     * @hide
     * @deprecated Use {@link #getSharedLibrariesAsUser(PackageInfoFlags, int)} instead.
     */
    @Deprecated
    @SuppressWarnings("HiddenAbstractMethod")
    public abstract @NonNull List<SharedLibraryInfo> getSharedLibrariesAsUser(int flags,
            @UserIdInt int userId);

    /**
     * See {@link #getSharedLibrariesAsUser(int, int)}.
     * @hide
     */
    public @NonNull List<SharedLibraryInfo> getSharedLibrariesAsUser(
            @NonNull PackageInfoFlags flags, @UserIdInt int userId) {
        throw new UnsupportedOperationException(
                "getSharedLibrariesAsUser() not implemented in subclass");
    }

    /**
     * Get the list of shared libraries declared by a package.
     *
     * @param packageName the package name to query
     * @param flags the flags to filter packages
     * @return the shared library list
     *
     * @hide
     * @deprecated Use {@link #getDeclaredSharedLibraries(String, PackageInfoFlags)} instead.
     */
    @Deprecated
    @SuppressWarnings("HiddenAbstractMethod")
    @NonNull
    @RequiresPermission(Manifest.permission.ACCESS_SHARED_LIBRARIES)
    @SystemApi
    public List<SharedLibraryInfo> getDeclaredSharedLibraries(@NonNull String packageName,
            int flags) {
        throw new UnsupportedOperationException(
                "getDeclaredSharedLibraries() not implemented in subclass");
    }

    /**
     * See {@link #getDeclaredSharedLibraries(String, int)}.
     * @hide
     */
    @NonNull
    @RequiresPermission(Manifest.permission.ACCESS_SHARED_LIBRARIES)
    @SystemApi
    public List<SharedLibraryInfo> getDeclaredSharedLibraries(@NonNull String packageName,
            @NonNull PackageInfoFlags flags) {
        throw new UnsupportedOperationException(
                "getDeclaredSharedLibraries() not implemented in subclass");
    }

    /**
     * Get the name of the package hosting the services shared library.
     * <p>
     * Note that this package is no longer a shared library since Android R. It is now a package
     * that hosts for a bunch of updatable services that the system binds to.
     *
     * @return The library host package.
     *
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    @TestApi
    public abstract @NonNull String getServicesSystemSharedLibraryPackageName();

    /**
     * Get the name of the package hosting the shared components shared library.
     *
     * @return The library host package.
     *
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    @TestApi
    public abstract @NonNull String getSharedSystemSharedLibraryPackageName();

    /**
     * Returns the names of the packages that have been changed
     * [eg. added, removed or updated] since the given sequence
     * number.
     * <p>If no packages have been changed, returns <code>null</code>.
     * <p>The sequence number starts at <code>0</code> and is
     * reset every boot.
     * @param sequenceNumber The first sequence number for which to retrieve package changes.
     * @see android.provider.Settings.Global#BOOT_COUNT
     */
    public abstract @Nullable ChangedPackages getChangedPackages(
            @IntRange(from=0) int sequenceNumber);

    /**
     * Get a list of features that are available on the
     * system.
     *
     * @return An array of FeatureInfo classes describing the features
     * that are available on the system, or null if there are none(!!).
     */
    @NonNull
    public abstract FeatureInfo[] getSystemAvailableFeatures();

    /**
     * Check whether the given feature name is one of the available features as
     * returned by {@link #getSystemAvailableFeatures()}. This tests for the
     * presence of <em>any</em> version of the given feature name; use
     * {@link #hasSystemFeature(String, int)} to check for a minimum version.
     *
     * @return Returns true if the devices supports the feature, else false.
     */
    public abstract boolean hasSystemFeature(@NonNull String featureName);

    /**
     * Check whether the given feature name and version is one of the available
     * features as returned by {@link #getSystemAvailableFeatures()}. Since
     * features are defined to always be backwards compatible, this returns true
     * if the available feature version is greater than or equal to the
     * requested version.
     *
     * @return Returns true if the devices supports the feature, else false.
     */
    public abstract boolean hasSystemFeature(@NonNull String featureName, int version);

    /**
     * Determine the best action to perform for a given Intent. This is how
     * {@link Intent#resolveActivity} finds an activity if a class has not been
     * explicitly specified.
     * <p>
     * <em>Note:</em> if using an implicit Intent (without an explicit
     * ComponentName specified), be sure to consider whether to set the
     * {@link #MATCH_DEFAULT_ONLY} only flag. You need to do so to resolve the
     * activity in the same way that
     * {@link android.content.Context#startActivity(Intent)} and
     * {@link android.content.Intent#resolveActivity(PackageManager)
     * Intent.resolveActivity(PackageManager)} do.
     * </p>
     *
     * @param intent An intent containing all of the desired specification
     *            (action, data, type, category, and/or component).
     * @param flags Additional option flags to modify the data returned. The
     *            most important is {@link #MATCH_DEFAULT_ONLY}, to limit the
     *            resolution to only those activities that support the
     *            {@link android.content.Intent#CATEGORY_DEFAULT}.
     * @return Returns a ResolveInfo object containing the final activity intent
     *         that was determined to be the best action. Returns null if no
     *         matching activity was found. If multiple matching activities are
     *         found and there is no default set, returns a ResolveInfo object
     *         containing something else, such as the activity resolver.
     * @deprecated Use {@link #resolveActivity(Intent, ResolveInfoFlags)} instead.
     */
    @Deprecated
    @Nullable
    public abstract ResolveInfo resolveActivity(@NonNull Intent intent, int flags);

    /**
     * See {@link #resolveActivity(Intent, int)}.
     */
    @Nullable
    public ResolveInfo resolveActivity(@NonNull Intent intent, @NonNull ResolveInfoFlags flags) {
        throw new UnsupportedOperationException(
                "resolveActivity not implemented in subclass");
    }

    /**
     * Determine the best action to perform for a given Intent for a given user.
     * This is how {@link Intent#resolveActivity} finds an activity if a class
     * has not been explicitly specified.
     * <p>
     * <em>Note:</em> if using an implicit Intent (without an explicit
     * ComponentName specified), be sure to consider whether to set the
     * {@link #MATCH_DEFAULT_ONLY} only flag. You need to do so to resolve the
     * activity in the same way that
     * {@link android.content.Context#startActivity(Intent)} and
     * {@link android.content.Intent#resolveActivity(PackageManager)
     * Intent.resolveActivity(PackageManager)} do.
     * </p>
     *
     * @param intent An intent containing all of the desired specification
     *            (action, data, type, category, and/or component).
     * @param flags Additional option flags to modify the data returned. The
     *            most important is {@link #MATCH_DEFAULT_ONLY}, to limit the
     *            resolution to only those activities that support the
     *            {@link android.content.Intent#CATEGORY_DEFAULT}.
     * @param userId The user id.
     * @return Returns a ResolveInfo object containing the final activity intent
     *         that was determined to be the best action. Returns null if no
     *         matching activity was found. If multiple matching activities are
     *         found and there is no default set, returns a ResolveInfo object
     *         containing something else, such as the activity resolver.
     * @hide
     * @deprecated Use {@link #resolveActivityAsUser(Intent, ResolveInfoFlags, int)} instead.
     */
    @Deprecated
    @SuppressWarnings("HiddenAbstractMethod")
    @Nullable
    @UnsupportedAppUsage
    public abstract ResolveInfo resolveActivityAsUser(@NonNull Intent intent,
            int flags, @UserIdInt int userId);

    /**
     * See {@link #resolveActivityAsUser(Intent, int, int)}.
     * @hide
     */
    @Nullable
    public ResolveInfo resolveActivityAsUser(@NonNull Intent intent,
            @NonNull ResolveInfoFlags flags, @UserIdInt int userId) {
        throw new UnsupportedOperationException(
                "resolveActivityAsUser not implemented in subclass");
    }

    /**
     * Retrieve all activities that can be performed for the given intent.
     *
     * @param intent The desired intent as per resolveActivity().
     * @param flags Additional option flags to modify the data returned. The
     *            most important is {@link #MATCH_DEFAULT_ONLY}, to limit the
     *            resolution to only those activities that support the
     *            {@link android.content.Intent#CATEGORY_DEFAULT}. Or, set
     *            {@link #MATCH_ALL} to prevent any filtering of the results.
     * @return Returns a List of ResolveInfo objects containing one entry for
     *         each matching activity, ordered from best to worst. In other
     *         words, the first item is what would be returned by
     *         {@link #resolveActivity}. If there are no matching activities, an
     *         empty list is returned.
     * @deprecated Use {@link #queryIntentActivities(Intent, ResolveInfoFlags)} instead.
     */
    @Deprecated
    @NonNull
    public abstract List<ResolveInfo> queryIntentActivities(@NonNull Intent intent, int flags);

    /**
     * See {@link #queryIntentActivities(Intent, int)}.
     */
    @NonNull
    public List<ResolveInfo> queryIntentActivities(@NonNull Intent intent,
            @NonNull ResolveInfoFlags flags) {
        throw new UnsupportedOperationException(
                "queryIntentActivities not implemented in subclass");
    }

    /**
     * Retrieve all activities that can be performed for the given intent, for a
     * specific user.
     *
     * @param intent The desired intent as per resolveActivity().
     * @param flags Additional option flags to modify the data returned. The
     *            most important is {@link #MATCH_DEFAULT_ONLY}, to limit the
     *            resolution to only those activities that support the
     *            {@link android.content.Intent#CATEGORY_DEFAULT}. Or, set
     *            {@link #MATCH_ALL} to prevent any filtering of the results.
     * @return Returns a List of ResolveInfo objects containing one entry for
     *         each matching activity, ordered from best to worst. In other
     *         words, the first item is what would be returned by
     *         {@link #resolveActivity}. If there are no matching activities, an
     *         empty list is returned.
     * @hide
     * @deprecated Use {@link #queryIntentActivitiesAsUser(Intent, ResolveInfoFlags, int)} instead.
     */
    @Deprecated
    @SuppressWarnings("HiddenAbstractMethod")
    @NonNull
    @UnsupportedAppUsage
    public abstract List<ResolveInfo> queryIntentActivitiesAsUser(@NonNull Intent intent,
            int flags, @UserIdInt int userId);

    /**
     * See {@link #queryIntentActivitiesAsUser(Intent, int, int)}.
     * @hide
     */
    @NonNull
    public List<ResolveInfo> queryIntentActivitiesAsUser(@NonNull Intent intent,
            @NonNull ResolveInfoFlags flags, @UserIdInt int userId) {
        throw new UnsupportedOperationException(
                "queryIntentActivitiesAsUser not implemented in subclass");
    }

    /**
     * Retrieve all activities that can be performed for the given intent, for a
     * specific user.
     *
     * @param intent The desired intent as per resolveActivity().
     * @param flags Additional option flags to modify the data returned. The
     *            most important is {@link #MATCH_DEFAULT_ONLY}, to limit the
     *            resolution to only those activities that support the
     *            {@link android.content.Intent#CATEGORY_DEFAULT}. Or, set
     *            {@link #MATCH_ALL} to prevent any filtering of the results.
     * @param user The user being queried.
     * @return Returns a List of ResolveInfo objects containing one entry for
     *         each matching activity, ordered from best to worst. In other
     *         words, the first item is what would be returned by
     *         {@link #resolveActivity}. If there are no matching activities, an
     *         empty list is returned.
     * @hide
     * @deprecated Use {@link #queryIntentActivitiesAsUser(Intent, ResolveInfoFlags, UserHandle)}
     * instead.
     */
    @Deprecated
    @SuppressWarnings("HiddenAbstractMethod")
    @NonNull
    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS)
    @SystemApi
    public List<ResolveInfo> queryIntentActivitiesAsUser(@NonNull Intent intent,
            int flags, @NonNull UserHandle user) {
        return queryIntentActivitiesAsUser(intent, flags, user.getIdentifier());
    }

    /**
     * See {@link #queryIntentActivitiesAsUser(Intent, int, UserHandle)}.
     * @hide
     */
    @NonNull
    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS)
    @SystemApi
    public List<ResolveInfo> queryIntentActivitiesAsUser(@NonNull Intent intent,
            @NonNull ResolveInfoFlags flags, @NonNull UserHandle user) {
        return queryIntentActivitiesAsUser(intent, flags, user.getIdentifier());
    }

    /**
     * Retrieve a set of activities that should be presented to the user as
     * similar options. This is like {@link #queryIntentActivities}, except it
     * also allows you to supply a list of more explicit Intents that you would
     * like to resolve to particular options, and takes care of returning the
     * final ResolveInfo list in a reasonable order, with no duplicates, based
     * on those inputs.
     *
     * @param caller The class name of the activity that is making the request.
     *            This activity will never appear in the output list. Can be
     *            null.
     * @param specifics An array of Intents that should be resolved to the first
     *            specific results. Can be null.
     * @param intent The desired intent as per resolveActivity().
     * @param flags Additional option flags to modify the data returned. The
     *            most important is {@link #MATCH_DEFAULT_ONLY}, to limit the
     *            resolution to only those activities that support the
     *            {@link android.content.Intent#CATEGORY_DEFAULT}.
     * @return Returns a List of ResolveInfo objects containing one entry for
     *         each matching activity. The list is ordered first by all of the
     *         intents resolved in <var>specifics</var> and then any additional
     *         activities that can handle <var>intent</var> but did not get
     *         included by one of the <var>specifics</var> intents. If there are
     *         no matching activities, an empty list is returned.
     * @deprecated Use {@link #queryIntentActivityOptions(ComponentName, List, Intent,
     * ResolveInfoFlags)} instead.
     */
    @Deprecated
    @NonNull
    public abstract List<ResolveInfo> queryIntentActivityOptions(@Nullable ComponentName caller,
            @Nullable Intent[] specifics, @NonNull Intent intent, int flags);

    /**
     * See {@link #queryIntentActivityOptions(ComponentName, Intent[], Intent, int)}.
     */
    @NonNull
    public List<ResolveInfo> queryIntentActivityOptions(@Nullable ComponentName caller,
            @Nullable List<Intent> specifics, @NonNull Intent intent,
            @NonNull ResolveInfoFlags flags) {
        throw new UnsupportedOperationException(
                "queryIntentActivityOptions not implemented in subclass");
    }

    /**
     * Retrieve all receivers that can handle a broadcast of the given intent.
     *
     * @param intent The desired intent as per resolveActivity().
     * @param flags Additional option flags to modify the data returned.
     * @return Returns a List of ResolveInfo objects containing one entry for
     *         each matching receiver, ordered from best to worst. If there are
     *         no matching receivers, an empty list or null is returned.
     * @deprecated Use {@link #queryBroadcastReceivers(Intent, ResolveInfoFlags)} instead.
     */
    @Deprecated
    @NonNull
    public abstract List<ResolveInfo> queryBroadcastReceivers(@NonNull Intent intent, int flags);

    /**
     * See {@link #queryBroadcastReceivers(Intent, int)}.
     */
    @NonNull
    public List<ResolveInfo> queryBroadcastReceivers(@NonNull Intent intent,
            @NonNull ResolveInfoFlags flags) {
        throw new UnsupportedOperationException(
                "queryBroadcastReceivers not implemented in subclass");
    }

    /**
     * Retrieve all receivers that can handle a broadcast of the given intent,
     * for a specific user.
     *
     * @param intent The desired intent as per resolveActivity().
     * @param flags Additional option flags to modify the data returned.
     * @param userHandle UserHandle of the user being queried.
     * @return Returns a List of ResolveInfo objects containing one entry for
     *         each matching receiver, ordered from best to worst. If there are
     *         no matching receivers, an empty list or null is returned.
     * @hide
     * @deprecated Use {@link #queryBroadcastReceiversAsUser(Intent, ResolveInfoFlags, UserHandle)}
     * instead.
     */
    @Deprecated
    @SuppressWarnings("HiddenAbstractMethod")
    @NonNull
    @SystemApi
    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS)
    public List<ResolveInfo> queryBroadcastReceiversAsUser(@NonNull Intent intent,
            int flags, UserHandle userHandle) {
        return queryBroadcastReceiversAsUser(intent, flags, userHandle.getIdentifier());
    }

    /**
     * See {@link #queryBroadcastReceiversAsUser(Intent, int, UserHandle)}.
     * @hide
     */
    @NonNull
    @SystemApi
    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS)
    public List<ResolveInfo> queryBroadcastReceiversAsUser(@NonNull Intent intent,
            @NonNull ResolveInfoFlags flags, @NonNull UserHandle userHandle) {
        return queryBroadcastReceiversAsUser(intent, flags, userHandle.getIdentifier());
    }

    /**
     * @hide
     * @deprecated Use {@link #queryBroadcastReceiversAsUser(Intent, ResolveInfoFlags, int)}
     * instead.
     */
    @Deprecated
    @SuppressWarnings("HiddenAbstractMethod")
    @NonNull
    @UnsupportedAppUsage
    public abstract List<ResolveInfo> queryBroadcastReceiversAsUser(@NonNull Intent intent,
            int flags, @UserIdInt int userId);

    /**
     * See {@link #queryBroadcastReceiversAsUser(Intent, int, int)}.
     * @hide
     */
    @NonNull
    public List<ResolveInfo> queryBroadcastReceiversAsUser(@NonNull Intent intent,
            @NonNull ResolveInfoFlags flags, @UserIdInt int userId) {
        throw new UnsupportedOperationException(
                "queryBroadcastReceiversAsUser not implemented in subclass");
    }


    /** @deprecated @hide */
    @NonNull
    @Deprecated
    @UnsupportedAppUsage
    public List<ResolveInfo> queryBroadcastReceivers(@NonNull Intent intent,
            int flags, @UserIdInt int userId) {
        final String msg = "Shame on you for calling the hidden API "
                + "queryBroadcastReceivers(). Shame!";
        if (VMRuntime.getRuntime().getTargetSdkVersion() >= Build.VERSION_CODES.O) {
            throw new UnsupportedOperationException(msg);
        } else {
            Log.d(TAG, msg);
            return queryBroadcastReceiversAsUser(intent, flags, userId);
        }
    }

    /**
     * Determine the best service to handle for a given Intent.
     *
     * @param intent An intent containing all of the desired specification
     *            (action, data, type, category, and/or component).
     * @param flags Additional option flags to modify the data returned.
     * @return Returns a ResolveInfo object containing the final service intent
     *         that was determined to be the best action. Returns null if no
     *         matching service was found.
     * @deprecated Use {@link #resolveService(Intent, ResolveInfoFlags)} instead.
     */
    @Deprecated
    @Nullable
    public abstract ResolveInfo resolveService(@NonNull Intent intent, int flags);

    /**
     * See {@link #resolveService(Intent, int)}.
     */
    @Nullable
    public ResolveInfo resolveService(@NonNull Intent intent, @NonNull ResolveInfoFlags flags) {
        throw new UnsupportedOperationException(
                "resolveService not implemented in subclass");
    }

    /**
     * @hide
     * @deprecated Use {@link #resolveServiceAsUser(Intent, ResolveInfoFlags, int)} instead.
     */
    @Deprecated
    @SuppressWarnings("HiddenAbstractMethod")
    @Nullable
    public abstract ResolveInfo resolveServiceAsUser(@NonNull Intent intent,
            int flags, @UserIdInt int userId);

    /**
     * See {@link #resolveServiceAsUser(Intent, int, int)}.
     * @hide
     */
    @Nullable
    public ResolveInfo resolveServiceAsUser(@NonNull Intent intent,
            @NonNull ResolveInfoFlags flags, @UserIdInt int userId) {
        throw new UnsupportedOperationException(
                "resolveServiceAsUser not implemented in subclass");
    }

    /**
     * Retrieve all services that can match the given intent.
     *
     * @param intent The desired intent as per resolveService().
     * @param flags Additional option flags to modify the data returned.
     * @return Returns a List of ResolveInfo objects containing one entry for
     *         each matching service, ordered from best to worst. In other
     *         words, the first item is what would be returned by
     *         {@link #resolveService}. If there are no matching services, an
     *         empty list or null is returned.
     * @deprecated Use {@link #queryIntentServices(Intent, ResolveInfoFlags)} instead.
     */
    @Deprecated
    @NonNull
    public abstract List<ResolveInfo> queryIntentServices(@NonNull Intent intent,
            int flags);

    /**
     * See {@link #queryIntentServices(Intent, int)}.
     */
    @NonNull
    public List<ResolveInfo> queryIntentServices(@NonNull Intent intent,
            @NonNull ResolveInfoFlags flags) {
        throw new UnsupportedOperationException(
                "queryIntentServices not implemented in subclass");
    }

    /**
     * Retrieve all services that can match the given intent for a given user.
     *
     * @param intent The desired intent as per resolveService().
     * @param flags Additional option flags to modify the data returned.
     * @param userId The user id.
     * @return Returns a List of ResolveInfo objects containing one entry for
     *         each matching service, ordered from best to worst. In other
     *         words, the first item is what would be returned by
     *         {@link #resolveService}. If there are no matching services, an
     *         empty list or null is returned.
     * @hide
     * @deprecated Use {@link #queryIntentServicesAsUser(Intent, ResolveInfoFlags, int)} instead.
     */
    @Deprecated
    @SuppressWarnings("HiddenAbstractMethod")
    @NonNull
    @UnsupportedAppUsage
    public abstract List<ResolveInfo> queryIntentServicesAsUser(@NonNull Intent intent,
            int flags, @UserIdInt int userId);

    /**
     * See {@link #queryIntentServicesAsUser(Intent, int, int)}.
     * @hide
     */
    @NonNull
    public List<ResolveInfo> queryIntentServicesAsUser(@NonNull Intent intent,
            @NonNull ResolveInfoFlags flags, @UserIdInt int userId) {
        throw new UnsupportedOperationException(
                "queryIntentServicesAsUser not implemented in subclass");
    }

    /**
     * Retrieve all services that can match the given intent for a given user.
     *
     * @param intent The desired intent as per resolveService().
     * @param flags Additional option flags to modify the data returned.
     * @param user The user being queried.
     * @return Returns a List of ResolveInfo objects containing one entry for
     *         each matching service, ordered from best to worst. In other
     *         words, the first item is what would be returned by
     *         {@link #resolveService}. If there are no matching services, an
     *         empty list or null is returned.
     * @hide
     * @deprecated Use {@link #queryIntentServicesAsUser(Intent, ResolveInfoFlags, UserHandle)}
     * instead.
     */
    @Deprecated
    @NonNull
    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS)
    @SystemApi
    public List<ResolveInfo> queryIntentServicesAsUser(@NonNull Intent intent,
            int flags, @NonNull UserHandle user) {
        return queryIntentServicesAsUser(intent, flags, user.getIdentifier());
    }

    /**
     * See {@link #queryIntentServicesAsUser(Intent, int, UserHandle)}.
     * @hide
     */
    @NonNull
    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS)
    @SystemApi
    public List<ResolveInfo> queryIntentServicesAsUser(@NonNull Intent intent,
            @NonNull ResolveInfoFlags flags, @NonNull UserHandle user) {
        return queryIntentServicesAsUser(intent, flags, user.getIdentifier());
    }
    /**
     * Retrieve all providers that can match the given intent.
     *
     * @param intent An intent containing all of the desired specification
     *            (action, data, type, category, and/or component).
     * @param flags Additional option flags to modify the data returned.
     * @param userId The user id.
     * @return Returns a List of ResolveInfo objects containing one entry for
     *         each matching provider, ordered from best to worst. If there are
     *         no matching services, an empty list or null is returned.
     * @hide
     * @deprecated Use {@link #queryIntentContentProvidersAsUser(Intent, ResolveInfoFlags, int)}
     * instead.
     */
    @Deprecated
    @SuppressWarnings("HiddenAbstractMethod")
    @NonNull
    @UnsupportedAppUsage
    public abstract List<ResolveInfo> queryIntentContentProvidersAsUser(
            @NonNull Intent intent, int flags, @UserIdInt int userId);

    /**
     * See {@link #queryIntentContentProvidersAsUser(Intent, int, int)}.
     * @hide
     */
    @NonNull
    protected List<ResolveInfo> queryIntentContentProvidersAsUser(
            @NonNull Intent intent, @NonNull ResolveInfoFlags flags, @UserIdInt int userId) {
        throw new UnsupportedOperationException(
                "queryIntentContentProvidersAsUser not implemented in subclass");
    }

    /**
     * Retrieve all providers that can match the given intent.
     *
     * @param intent An intent containing all of the desired specification
     *            (action, data, type, category, and/or component).
     * @param flags Additional option flags to modify the data returned.
     * @param user The user being queried.
     * @return Returns a List of ResolveInfo objects containing one entry for
     *         each matching provider, ordered from best to worst. If there are
     *         no matching services, an empty list or null is returned.
     * @hide
     * @deprecated Use {@link #queryIntentContentProvidersAsUser(Intent, ResolveInfoFlags,
     * UserHandle)} instead.
     */
    @Deprecated
    @NonNull
    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS)
    @SystemApi
    public List<ResolveInfo> queryIntentContentProvidersAsUser(@NonNull Intent intent,
            int flags, @NonNull UserHandle user) {
        return queryIntentContentProvidersAsUser(intent, flags, user.getIdentifier());
    }

    /**
     * See {@link #queryIntentContentProvidersAsUser(Intent, int, UserHandle)}.
     * @hide
     */
    @NonNull
    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS)
    @SystemApi
    public List<ResolveInfo> queryIntentContentProvidersAsUser(@NonNull Intent intent,
            @NonNull ResolveInfoFlags flags, @NonNull UserHandle user) {
        return queryIntentContentProvidersAsUser(intent, flags, user.getIdentifier());
    }

    /**
     * Retrieve all providers that can match the given intent.
     *
     * @param intent An intent containing all of the desired specification
     *            (action, data, type, category, and/or component).
     * @param flags Additional option flags to modify the data returned.
     * @return Returns a List of ResolveInfo objects containing one entry for
     *         each matching provider, ordered from best to worst. If there are
     *         no matching services, an empty list or null is returned.
     * @deprecated Use {@link #queryIntentContentProviders(Intent, ResolveInfoFlags)} instead.
     */
    @Deprecated
    @NonNull
    public abstract List<ResolveInfo> queryIntentContentProviders(@NonNull Intent intent,
            int flags);

    /**
     * See {@link #queryIntentContentProviders(Intent, int)}.
     */
    @NonNull
    public List<ResolveInfo> queryIntentContentProviders(@NonNull Intent intent,
            @NonNull ResolveInfoFlags flags) {
        throw new UnsupportedOperationException(
                "queryIntentContentProviders not implemented in subclass");
    }

    /**
     * Find a single content provider by its authority.
     * <p>
     * Example:<p>
     * <pre>
     * Uri uri = Uri.parse("content://com.example.app.provider/table1");
     * ProviderInfo info = packageManager.resolveContentProvider(uri.getAuthority(), flags);
     * </pre>
     *
     * @param authority The authority of the provider to find.
     * @param flags Additional option flags to modify the data returned.
     * @return A {@link ProviderInfo} object containing information about the
     *         provider. If a provider was not found, returns null.
     * @deprecated Use {@link #resolveContentProvider(String, ComponentInfoFlags)} instead.
     */
    @Deprecated
    @Nullable
    public abstract ProviderInfo resolveContentProvider(@NonNull String authority,
            int flags);

    /**
     * See {@link #resolveContentProvider(String, int)}.
     */
    @Nullable
    public ProviderInfo resolveContentProvider(@NonNull String authority,
            @NonNull ComponentInfoFlags flags) {
        throw new UnsupportedOperationException(
                "resolveContentProvider not implemented in subclass");
    }

    /**
     * Find a single content provider by its base path name.
     *
     * @param providerName The name of the provider to find.
     * @param flags Additional option flags to modify the data returned.
     * @param userId The user id.
     * @return A {@link ProviderInfo} object containing information about the
     *         provider. If a provider was not found, returns null.
     * @hide
     * @deprecated Use {@link #resolveContentProviderAsUser(String, ComponentInfoFlags, int)}
     * instead.
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @Nullable
    @UnsupportedAppUsage
    public abstract ProviderInfo resolveContentProviderAsUser(@NonNull String providerName,
            int flags, @UserIdInt int userId);

    /**
     * See {@link #resolveContentProviderAsUser(String, int, int)}.
     * @hide
     */
    @Nullable
    public ProviderInfo resolveContentProviderAsUser(@NonNull String providerName,
            @NonNull ComponentInfoFlags flags, @UserIdInt int userId) {
        throw new UnsupportedOperationException(
                "resolveContentProviderAsUser not implemented in subclass");
    }

    /**
     * Retrieve content provider information.
     * <p>
     * <em>Note: unlike most other methods, an empty result set is indicated
     * by a null return instead of an empty list.</em>
     *
     * @param processName If non-null, limits the returned providers to only
     *            those that are hosted by the given process. If null, all
     *            content providers are returned.
     * @param uid If <var>processName</var> is non-null, this is the required
     *            uid owning the requested content providers.
     * @param flags Additional option flags to modify the data returned.
     * @return A list of {@link ProviderInfo} objects containing one entry for
     *         each provider either matching <var>processName</var> or, if
     *         <var>processName</var> is null, all known content providers.
     *         <em>If there are no matching providers, null is returned.</em>
     * @deprecated Use {@link #queryContentProviders(String, int, ComponentInfoFlags)} instead.
     */
    @Deprecated
    @NonNull
    public abstract List<ProviderInfo> queryContentProviders(
            @Nullable String processName, int uid, int flags);

    /**
     * See {@link #queryContentProviders(String, int, int)}.
     */
    @NonNull
    public List<ProviderInfo> queryContentProviders(
            @Nullable String processName, int uid, @NonNull ComponentInfoFlags flags) {
        throw new UnsupportedOperationException(
                "queryContentProviders not implemented in subclass");
    }

    /**
     * Same as {@link #queryContentProviders}, except when {@code metaDataKey} is not null,
     * it only returns providers which have metadata with the {@code metaDataKey} key.
     *
     * <p>DO NOT USE the {@code metaDataKey} parameter, unless you're the contacts provider.
     * You really shouldn't need it.  Other apps should use {@link #queryIntentContentProviders}
     * instead.
     *
     * <p>The {@code metaDataKey} parameter was added to allow the contacts provider to quickly
     * scan the GAL providers on the device.  Unfortunately the discovery protocol used metadata
     * to mark GAL providers, rather than intent filters, so we can't use
     * {@link #queryIntentContentProviders} for that.
     *
     * @hide
     * @deprecated Use {@link #queryContentProviders(String, int, ComponentInfoFlags, String)}
     * instead.
     */
    @Deprecated
    @NonNull
    public List<ProviderInfo> queryContentProviders(@Nullable String processName,
            int uid, int flags, String metaDataKey) {
        // Provide the default implementation for mocks.
        return queryContentProviders(processName, uid, flags);
    }

    /**
     * See {@link #queryContentProviders(String, int, int, String)}.
     * @hide
     */
    @NonNull
    public List<ProviderInfo> queryContentProviders(@Nullable String processName,
            int uid, @NonNull ComponentInfoFlags flags, @Nullable String metaDataKey) {
        // Provide the default implementation for mocks.
        return queryContentProviders(processName, uid, flags);
    }

    /**
     * Retrieve all of the information we know about a particular
     * instrumentation class.
     *
     * @param className The full name (i.e.
     *            com.google.apps.contacts.InstrumentList) of an Instrumentation
     *            class.
     * @param flags Additional option flags to modify the data returned.
     * @return An {@link InstrumentationInfo} object containing information
     *         about the instrumentation.
     * @throws NameNotFoundException if a package with the given name cannot be
     *             found on the system.
     */
    @NonNull
    public abstract InstrumentationInfo getInstrumentationInfo(@NonNull ComponentName className,
            @InstrumentationInfoFlags int flags) throws NameNotFoundException;

    /**
     * Retrieve information about available instrumentation code. May be used to
     * retrieve either all instrumentation code, or only the code targeting a
     * particular package.
     *
     * @param targetPackage If null, all instrumentation is returned; only the
     *            instrumentation targeting this package name is returned.
     * @param flags Additional option flags to modify the data returned.
     * @return A list of {@link InstrumentationInfo} objects containing one
     *         entry for each matching instrumentation. If there are no
     *         instrumentation available, returns an empty list.
     */
    @NonNull
    public abstract List<InstrumentationInfo> queryInstrumentation(@NonNull String targetPackage,
            @InstrumentationInfoFlags int flags);

    /**
     * Retrieve an image from a package.  This is a low-level API used by
     * the various package manager info structures (such as
     * {@link ComponentInfo} to implement retrieval of their associated
     * icon.
     *
     * @param packageName The name of the package that this icon is coming from.
     * Cannot be null.
     * @param resid The resource identifier of the desired image.  Cannot be 0.
     * @param appInfo Overall information about <var>packageName</var>.  This
     * may be null, in which case the application information will be retrieved
     * for you if needed; if you already have this information around, it can
     * be much more efficient to supply it here.
     *
     * @return Returns a Drawable holding the requested image.  Returns null if
     * an image could not be found for any reason.
     */
    @Nullable
    public abstract Drawable getDrawable(@NonNull String packageName, @DrawableRes int resid,
            @Nullable ApplicationInfo appInfo);

    /**
     * Retrieve the icon associated with an activity.  Given the full name of
     * an activity, retrieves the information about it and calls
     * {@link ComponentInfo#loadIcon ComponentInfo.loadIcon()} to return its icon.
     * If the activity cannot be found, NameNotFoundException is thrown.
     *
     * @param activityName Name of the activity whose icon is to be retrieved.
     *
     * @return Returns the image of the icon, or the default activity icon if
     * it could not be found.  Does not return null.
     * @throws NameNotFoundException Thrown if the resources for the given
     * activity could not be loaded.
     *
     * @see #getActivityIcon(Intent)
     */
    @NonNull
    public abstract Drawable getActivityIcon(@NonNull ComponentName activityName)
            throws NameNotFoundException;

    /**
     * Retrieve the icon associated with an Intent.  If intent.getClassName() is
     * set, this simply returns the result of
     * getActivityIcon(intent.getClassName()).  Otherwise it resolves the intent's
     * component and returns the icon associated with the resolved component.
     * If intent.getClassName() cannot be found or the Intent cannot be resolved
     * to a component, NameNotFoundException is thrown.
     *
     * @param intent The intent for which you would like to retrieve an icon.
     *
     * @return Returns the image of the icon, or the default activity icon if
     * it could not be found.  Does not return null.
     * @throws NameNotFoundException Thrown if the resources for application
     * matching the given intent could not be loaded.
     *
     * @see #getActivityIcon(ComponentName)
     */
    @NonNull
    public abstract Drawable getActivityIcon(@NonNull Intent intent)
            throws NameNotFoundException;

    /**
     * Retrieve the banner associated with an activity. Given the full name of
     * an activity, retrieves the information about it and calls
     * {@link ComponentInfo#loadIcon ComponentInfo.loadIcon()} to return its
     * banner. If the activity cannot be found, NameNotFoundException is thrown.
     *
     * @param activityName Name of the activity whose banner is to be retrieved.
     * @return Returns the image of the banner, or null if the activity has no
     *         banner specified.
     * @throws NameNotFoundException Thrown if the resources for the given
     *             activity could not be loaded.
     * @see #getActivityBanner(Intent)
     */
    @Nullable
    public abstract Drawable getActivityBanner(@NonNull ComponentName activityName)
            throws NameNotFoundException;

    /**
     * Retrieve the banner associated with an Intent. If intent.getClassName()
     * is set, this simply returns the result of
     * getActivityBanner(intent.getClassName()). Otherwise it resolves the
     * intent's component and returns the banner associated with the resolved
     * component. If intent.getClassName() cannot be found or the Intent cannot
     * be resolved to a component, NameNotFoundException is thrown.
     *
     * @param intent The intent for which you would like to retrieve a banner.
     * @return Returns the image of the banner, or null if the activity has no
     *         banner specified.
     * @throws NameNotFoundException Thrown if the resources for application
     *             matching the given intent could not be loaded.
     * @see #getActivityBanner(ComponentName)
     */
    @Nullable
    public abstract Drawable getActivityBanner(@NonNull Intent intent)
            throws NameNotFoundException;

    /**
     * Return the generic icon for an activity that is used when no specific
     * icon is defined.
     *
     * @return Drawable Image of the icon.
     */
    @NonNull
    public abstract Drawable getDefaultActivityIcon();

    /**
     * Retrieve the icon associated with an application.  If it has not defined
     * an icon, the default app icon is returned.  Does not return null.
     *
     * @param info Information about application being queried.
     *
     * @return Returns the image of the icon, or the default application icon
     * if it could not be found.
     *
     * @see #getApplicationIcon(String)
     */
    @NonNull
    public abstract Drawable getApplicationIcon(@NonNull ApplicationInfo info);

    /**
     * Retrieve the icon associated with an application.  Given the name of the
     * application's package, retrieves the information about it and calls
     * getApplicationIcon() to return its icon. If the application cannot be
     * found, NameNotFoundException is thrown.
     *
     * @param packageName Name of the package whose application icon is to be
     *                    retrieved.
     *
     * @return Returns the image of the icon, or the default application icon
     * if it could not be found.  Does not return null.
     * @throws NameNotFoundException Thrown if the resources for the given
     * application could not be loaded.
     *
     * @see #getApplicationIcon(ApplicationInfo)
     */
    @NonNull
    public abstract Drawable getApplicationIcon(@NonNull String packageName)
            throws NameNotFoundException;

    /**
     * Retrieve the banner associated with an application.
     *
     * @param info Information about application being queried.
     * @return Returns the image of the banner or null if the application has no
     *         banner specified.
     * @see #getApplicationBanner(String)
     */
    @Nullable
    public abstract Drawable getApplicationBanner(@NonNull ApplicationInfo info);

    /**
     * Retrieve the banner associated with an application. Given the name of the
     * application's package, retrieves the information about it and calls
     * getApplicationIcon() to return its banner. If the application cannot be
     * found, NameNotFoundException is thrown.
     *
     * @param packageName Name of the package whose application banner is to be
     *            retrieved.
     * @return Returns the image of the banner or null if the application has no
     *         banner specified.
     * @throws NameNotFoundException Thrown if the resources for the given
     *             application could not be loaded.
     * @see #getApplicationBanner(ApplicationInfo)
     */
    @Nullable
    public abstract Drawable getApplicationBanner(@NonNull String packageName)
            throws NameNotFoundException;

    /**
     * Retrieve the logo associated with an activity. Given the full name of an
     * activity, retrieves the information about it and calls
     * {@link ComponentInfo#loadLogo ComponentInfo.loadLogo()} to return its
     * logo. If the activity cannot be found, NameNotFoundException is thrown.
     *
     * @param activityName Name of the activity whose logo is to be retrieved.
     * @return Returns the image of the logo or null if the activity has no logo
     *         specified.
     * @throws NameNotFoundException Thrown if the resources for the given
     *             activity could not be loaded.
     * @see #getActivityLogo(Intent)
     */
    @Nullable
    public abstract Drawable getActivityLogo(@NonNull ComponentName activityName)
            throws NameNotFoundException;

    /**
     * Retrieve the logo associated with an Intent.  If intent.getClassName() is
     * set, this simply returns the result of
     * getActivityLogo(intent.getClassName()).  Otherwise it resolves the intent's
     * component and returns the logo associated with the resolved component.
     * If intent.getClassName() cannot be found or the Intent cannot be resolved
     * to a component, NameNotFoundException is thrown.
     *
     * @param intent The intent for which you would like to retrieve a logo.
     *
     * @return Returns the image of the logo, or null if the activity has no
     * logo specified.
     *
     * @throws NameNotFoundException Thrown if the resources for application
     * matching the given intent could not be loaded.
     *
     * @see #getActivityLogo(ComponentName)
     */
    @Nullable
    public abstract Drawable getActivityLogo(@NonNull Intent intent)
            throws NameNotFoundException;

    /**
     * Retrieve the logo associated with an application.  If it has not specified
     * a logo, this method returns null.
     *
     * @param info Information about application being queried.
     *
     * @return Returns the image of the logo, or null if no logo is specified
     * by the application.
     *
     * @see #getApplicationLogo(String)
     */
    @Nullable
    public abstract Drawable getApplicationLogo(@NonNull ApplicationInfo info);

    /**
     * Retrieve the logo associated with an application.  Given the name of the
     * application's package, retrieves the information about it and calls
     * getApplicationLogo() to return its logo. If the application cannot be
     * found, NameNotFoundException is thrown.
     *
     * @param packageName Name of the package whose application logo is to be
     *                    retrieved.
     *
     * @return Returns the image of the logo, or null if no application logo
     * has been specified.
     *
     * @throws NameNotFoundException Thrown if the resources for the given
     * application could not be loaded.
     *
     * @see #getApplicationLogo(ApplicationInfo)
     */
    @Nullable
    public abstract Drawable getApplicationLogo(@NonNull String packageName)
            throws NameNotFoundException;

    /**
     * If the target user is a managed profile, then this returns a badged copy of the given icon
     * to be able to distinguish it from the original icon. For badging an arbitrary drawable use
     * {@link #getUserBadgedDrawableForDensity(
     * android.graphics.drawable.Drawable, UserHandle, android.graphics.Rect, int)}.
     * <p>
     * If the original drawable is a BitmapDrawable and the backing bitmap is
     * mutable as per {@link android.graphics.Bitmap#isMutable()}, the badging
     * is performed in place and the original drawable is returned.
     * </p>
     *
     * @param drawable The drawable to badge.
     * @param user The target user.
     * @return A drawable that combines the original icon and a badge as
     *         determined by the system.
     */
    @NonNull
    public abstract Drawable getUserBadgedIcon(@NonNull Drawable drawable,
            @NonNull UserHandle user);

    /**
     * If the target user is a managed profile of the calling user or the caller
     * is itself a managed profile, then this returns a badged copy of the given
     * drawable allowing the user to distinguish it from the original drawable.
     * The caller can specify the location in the bounds of the drawable to be
     * badged where the badge should be applied as well as the density of the
     * badge to be used.
     * <p>
     * If the original drawable is a BitmapDrawable and the backing bitmap is
     * mutable as per {@link android.graphics.Bitmap#isMutable()}, the badging
     * is performed in place and the original drawable is returned.
     * </p>
     *
     * @param drawable The drawable to badge.
     * @param user The target user.
     * @param badgeLocation Where in the bounds of the badged drawable to place
     *         the badge. If it's {@code null}, the badge is applied on top of the entire
     *         drawable being badged.
     * @param badgeDensity The optional desired density for the badge as per
     *         {@link android.util.DisplayMetrics#densityDpi}. If it's not positive,
     *         the density of the display is used.
     * @return A drawable that combines the original drawable and a badge as
     *         determined by the system.
     */
    @NonNull
    public abstract Drawable getUserBadgedDrawableForDensity(@NonNull Drawable drawable,
            @NonNull UserHandle user, @Nullable Rect badgeLocation, int badgeDensity);

    /**
     * If the target user is a managed profile of the calling user or the caller
     * is itself a managed profile, then this returns a drawable to use as a small
     * icon to include in a view to distinguish it from the original icon.
     *
     * @param user The target user.
     * @param density The optional desired density for the badge as per
     *         {@link android.util.DisplayMetrics#densityDpi}. If not provided
     *         the density of the current display is used.
     * @return the drawable or null if no drawable is required.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @Nullable
    @UnsupportedAppUsage
    public abstract Drawable getUserBadgeForDensity(@NonNull UserHandle user, int density);

    /**
     * If the target user is a managed profile of the calling user or the caller
     * is itself a managed profile, then this returns a drawable to use as a small
     * icon to include in a view to distinguish it from the original icon. This version
     * doesn't have background protection and should be used over a light background instead of
     * a badge.
     *
     * @param user The target user.
     * @param density The optional desired density for the badge as per
     *         {@link android.util.DisplayMetrics#densityDpi}. If not provided
     *         the density of the current display is used.
     * @return the drawable or null if no drawable is required.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @Nullable
    @UnsupportedAppUsage
    public abstract Drawable getUserBadgeForDensityNoBackground(@NonNull UserHandle user,
            int density);

    /**
     * If the target user is a managed profile of the calling user or the caller
     * is itself a managed profile, then this returns a copy of the label with
     * badging for accessibility services like talkback. E.g. passing in "Email"
     * and it might return "Work Email" for Email in the work profile.
     *
     * @param label The label to change.
     * @param user The target user.
     * @return A label that combines the original label and a badge as
     *         determined by the system.
     */
    @NonNull
    public abstract CharSequence getUserBadgedLabel(@NonNull CharSequence label,
            @NonNull UserHandle user);

    /**
     * Retrieve text from a package.  This is a low-level API used by
     * the various package manager info structures (such as
     * {@link ComponentInfo} to implement retrieval of their associated
     * labels and other text.
     *
     * @param packageName The name of the package that this text is coming from.
     * Cannot be null.
     * @param resid The resource identifier of the desired text.  Cannot be 0.
     * @param appInfo Overall information about <var>packageName</var>.  This
     * may be null, in which case the application information will be retrieved
     * for you if needed; if you already have this information around, it can
     * be much more efficient to supply it here.
     *
     * @return Returns a CharSequence holding the requested text.  Returns null
     * if the text could not be found for any reason.
     */
    @Nullable
    public abstract CharSequence getText(@NonNull String packageName, @StringRes int resid,
            @Nullable ApplicationInfo appInfo);

    /**
     * Retrieve an XML file from a package.  This is a low-level API used to
     * retrieve XML meta data.
     *
     * @param packageName The name of the package that this xml is coming from.
     * Cannot be null.
     * @param resid The resource identifier of the desired xml.  Cannot be 0.
     * @param appInfo Overall information about <var>packageName</var>.  This
     * may be null, in which case the application information will be retrieved
     * for you if needed; if you already have this information around, it can
     * be much more efficient to supply it here.
     *
     * @return Returns an XmlPullParser allowing you to parse out the XML
     * data.  Returns null if the xml resource could not be found for any
     * reason.
     */
    @Nullable
    public abstract XmlResourceParser getXml(@NonNull String packageName, @XmlRes int resid,
            @Nullable ApplicationInfo appInfo);

    /**
     * Return the label to use for this application.
     *
     * @return Returns a {@link CharSequence} containing the label associated with
     * this application, or its name the  item does not have a label.
     * @param info The {@link ApplicationInfo} of the application to get the label of.
     */
    @NonNull
    public abstract CharSequence getApplicationLabel(@NonNull ApplicationInfo info);

    /**
     * Retrieve the resources associated with an activity.  Given the full
     * name of an activity, retrieves the information about it and calls
     * getResources() to return its application's resources.  If the activity
     * cannot be found, NameNotFoundException is thrown.
     *
     * @param activityName Name of the activity whose resources are to be
     *                     retrieved.
     *
     * @return Returns the application's Resources.
     * @throws NameNotFoundException Thrown if the resources for the given
     * application could not be loaded.
     *
     * @see #getResourcesForApplication(ApplicationInfo)
     */
    @NonNull
    public abstract Resources getResourcesForActivity(@NonNull ComponentName activityName)
            throws NameNotFoundException;

    /**
     * Retrieve the resources for an application.  Throws NameNotFoundException
     * if the package is no longer installed.
     *
     * @param app Information about the desired application.
     *
     * @return Returns the application's Resources.
     * @throws NameNotFoundException Thrown if the resources for the given
     * application could not be loaded (most likely because it was uninstalled).
     */
    @NonNull
    public abstract Resources getResourcesForApplication(@NonNull ApplicationInfo app)
            throws NameNotFoundException;

    /**
     * Retrieve the resources for an application for the provided configuration.
     *
     * @param app Information about the desired application.
     * @param configuration Overridden configuration when loading the Resources
     *
     * @return Returns the application's Resources.
     * @throws NameNotFoundException Thrown if the resources for the given
     * application could not be loaded (most likely because it was uninstalled).
     */
    @NonNull
    public Resources getResourcesForApplication(@NonNull ApplicationInfo app, @Nullable
            Configuration configuration) throws NameNotFoundException {
        return getResourcesForApplication(app);
    }

    /**
     * Retrieve the resources associated with an application.  Given the full
     * package name of an application, retrieves the information about it and
     * calls getResources() to return its application's resources.  If the
     * appPackageName cannot be found, NameNotFoundException is thrown.
     *
     * @param packageName Package name of the application whose resources
     *                       are to be retrieved.
     *
     * @return Returns the application's Resources.
     * @throws NameNotFoundException Thrown if the resources for the given
     * application could not be loaded.
     *
     * @see #getResourcesForApplication(ApplicationInfo)
     */
    @NonNull
    public abstract Resources getResourcesForApplication(@NonNull String packageName)
            throws NameNotFoundException;

    /**
     * Please don't use this function because it is no longer supported.
     *
     * @deprecated Instead of using this function, please use
     *             {@link Context#createContextAsUser(UserHandle, int)} to create the specified user
     *             context, {@link Context#getPackageManager()} to get PackageManager instance for
     *             the specified user, and then
     *             {@link PackageManager#getResourcesForApplication(String)} to get the same
     *             Resources instance.
     * @see {@link Context#createContextAsUser(android.os.UserHandle, int)}
     * @see {@link Context#getPackageManager()}
     * @see {@link android.content.pm.PackageManager#getResourcesForApplication(java.lang.String)}
     * TODO(b/170852794): mark maxTargetSdk as {@code Build.VERSION_CODES.S}
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @NonNull
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170928809,
            publicAlternatives = "Use {@code Context#createContextAsUser(UserHandle, int)}"
                    + " to create the relevant user context,"
                    + " {@link android.content.Context#getPackageManager()} and"
                    + " {@link android.content.pm.PackageManager#getResourcesForApplication("
                    + "java.lang.String)}"
                    + " instead.")
    @Deprecated
    public abstract Resources getResourcesForApplicationAsUser(@NonNull String packageName,
            @UserIdInt int userId) throws NameNotFoundException;

    /**
     * Retrieve overall information about an application package defined in a
     * package archive file
     *
     * @param archiveFilePath The path to the archive file
     * @param flags Additional option flags to modify the data returned.
     * @return A PackageInfo object containing information about the package
     *         archive. If the package could not be parsed, returns null.
     * @deprecated Use {@link #getPackageArchiveInfo(String, PackageInfoFlags)} instead.
     */
    @Deprecated
    @Nullable
    public PackageInfo getPackageArchiveInfo(@NonNull String archiveFilePath, int flags) {
        return getPackageArchiveInfo(archiveFilePath, PackageInfoFlags.of(flags));
    }

    /**
     * See {@link #getPackageArchiveInfo(String, int)}.
     */
    @Nullable
    public PackageInfo getPackageArchiveInfo(@NonNull String archiveFilePath,
            @NonNull PackageInfoFlags flags) {
        long flagsBits = flags.getValue();
        final PackageParser parser = new PackageParser();
        parser.setCallback(new PackageParser.CallbackImpl(this));
        final File apkFile = new File(archiveFilePath);
        try {
            if ((flagsBits & (MATCH_DIRECT_BOOT_UNAWARE | MATCH_DIRECT_BOOT_AWARE)) != 0) {
                // Caller expressed an explicit opinion about what encryption
                // aware/unaware components they want to see, so fall through and
                // give them what they want
            } else {
                // Caller expressed no opinion, so match everything
                flagsBits |= MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE;
            }

            PackageParser.Package pkg = parser.parsePackage(apkFile, 0, false);
            if ((flagsBits & GET_SIGNATURES) != 0) {
                PackageParser.collectCertificates(pkg, false /* skipVerify */);
            }
            return PackageParser.generatePackageInfo(pkg, null, (int) flagsBits, 0, 0, null,
                    FrameworkPackageUserState.DEFAULT);
        } catch (PackageParser.PackageParserException e) {
            Log.w(TAG, "Failure to parse package archive", e);
            return null;
        }
    }

    /**
     * If there is already an application with the given package name installed
     * on the system for other users, also install it for the calling user.
     * @hide
     *
     * @deprecated use {@link PackageInstaller#installExistingPackage()} instead.
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @Deprecated
    @SystemApi
    public abstract int installExistingPackage(@NonNull String packageName)
            throws NameNotFoundException;

    /**
     * If there is already an application with the given package name installed
     * on the system for other users, also install it for the calling user.
     * @hide
     *
     * @deprecated use {@link PackageInstaller#installExistingPackage()} instead.
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @Deprecated
    @SystemApi
    public abstract int installExistingPackage(@NonNull String packageName,
            @InstallReason int installReason) throws NameNotFoundException;

    /**
     * If there is already an application with the given package name installed
     * on the system for other users, also install it for the specified user.
     * @hide
     *
     * @deprecated use {@link PackageInstaller#installExistingPackage()} instead.
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @Deprecated
    @RequiresPermission(anyOf = {
            Manifest.permission.INSTALL_EXISTING_PACKAGES,
            Manifest.permission.INSTALL_PACKAGES,
            Manifest.permission.INTERACT_ACROSS_USERS_FULL})
    @UnsupportedAppUsage
    public abstract int installExistingPackageAsUser(@NonNull String packageName,
            @UserIdInt int userId) throws NameNotFoundException;

    /**
     * Allows a package listening to the
     * {@link Intent#ACTION_PACKAGE_NEEDS_VERIFICATION package verification
     * broadcast} to respond to the package manager. The response must include
     * the {@code verificationCode} which is one of
     * {@link PackageManager#VERIFICATION_ALLOW} or
     * {@link PackageManager#VERIFICATION_REJECT}.
     *
     * @param id pending package identifier as passed via the
     *            {@link PackageManager#EXTRA_VERIFICATION_ID} Intent extra.
     * @param verificationCode either {@link PackageManager#VERIFICATION_ALLOW}
     *            or {@link PackageManager#VERIFICATION_REJECT}.
     * @throws SecurityException if the caller does not have the
     *            PACKAGE_VERIFICATION_AGENT permission.
     */
    public abstract void verifyPendingInstall(int id, int verificationCode);

    /**
     * Allows a package listening to the
     * {@link Intent#ACTION_PACKAGE_NEEDS_VERIFICATION package verification
     * broadcast} to extend the default timeout for a response and declare what
     * action to perform after the timeout occurs. The response must include
     * the {@code verificationCodeAtTimeout} which is one of
     * {@link PackageManager#VERIFICATION_ALLOW} or
     * {@link PackageManager#VERIFICATION_REJECT}.
     *
     * This method may only be called once per package id. Additional calls
     * will have no effect.
     *
     * @param id pending package identifier as passed via the
     *            {@link PackageManager#EXTRA_VERIFICATION_ID} Intent extra.
     * @param verificationCodeAtTimeout either
     *            {@link PackageManager#VERIFICATION_ALLOW} or
     *            {@link PackageManager#VERIFICATION_REJECT}. If
     *            {@code verificationCodeAtTimeout} is neither
     *            {@link PackageManager#VERIFICATION_ALLOW} or
     *            {@link PackageManager#VERIFICATION_REJECT}, then
     *            {@code verificationCodeAtTimeout} will default to
     *            {@link PackageManager#VERIFICATION_REJECT}.
     * @param millisecondsToDelay the amount of time requested for the timeout.
     *            Must be positive and less than
     *            {@link PackageManager#MAXIMUM_VERIFICATION_TIMEOUT}. If
     *            {@code millisecondsToDelay} is out of bounds,
     *            {@code millisecondsToDelay} will be set to the closest in
     *            bounds value; namely, 0 or
     *            {@link PackageManager#MAXIMUM_VERIFICATION_TIMEOUT}.
     * @throws SecurityException if the caller does not have the
     *            PACKAGE_VERIFICATION_AGENT permission.
     */
    public abstract void extendVerificationTimeout(int id,
            int verificationCodeAtTimeout, long millisecondsToDelay);

    /**
     * Allows a package listening to the
     * {@link Intent#ACTION_INTENT_FILTER_NEEDS_VERIFICATION} intent filter verification
     * broadcast to respond to the package manager. The response must include
     * the {@code verificationCode} which is one of
     * {@link PackageManager#INTENT_FILTER_VERIFICATION_SUCCESS} or
     * {@link PackageManager#INTENT_FILTER_VERIFICATION_FAILURE}.
     *
     * @param verificationId pending package identifier as passed via the
     *            {@link PackageManager#EXTRA_VERIFICATION_ID} Intent extra.
     * @param verificationCode either {@link PackageManager#INTENT_FILTER_VERIFICATION_SUCCESS}
     *            or {@link PackageManager#INTENT_FILTER_VERIFICATION_FAILURE}.
     * @param failedDomains a list of failed domains if the verificationCode is
     *            {@link PackageManager#INTENT_FILTER_VERIFICATION_FAILURE}, otherwise null;
     * @throws SecurityException if the caller does not have the
     *            INTENT_FILTER_VERIFICATION_AGENT permission.
     *
     * @deprecated Use {@link DomainVerificationManager} APIs.
     * @hide
     */
    @Deprecated
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    @RequiresPermission(android.Manifest.permission.INTENT_FILTER_VERIFICATION_AGENT)
    public abstract void verifyIntentFilter(int verificationId, int verificationCode,
            @NonNull List<String> failedDomains);

    /**
     * Get the status of a Domain Verification Result for an IntentFilter. This is
     * related to the {@link android.content.IntentFilter#setAutoVerify(boolean)} and
     * {@link android.content.IntentFilter#getAutoVerify()}
     *
     * This is used by the ResolverActivity to change the status depending on what the User select
     * in the Disambiguation Dialog and also used by the Settings App for changing the default App
     * for a domain.
     *
     * @param packageName The package name of the Activity associated with the IntentFilter.
     * @param userId The user id.
     *
     * @return The status to set to. This can be
     *              {@link #INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK} or
     *              {@link #INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS} or
     *              {@link #INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER} or
     *              {@link #INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED}
     *
     * @deprecated Use {@link DomainVerificationManager} APIs.
     * @hide
     */
    @Deprecated
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS_FULL)
    public abstract int getIntentVerificationStatusAsUser(@NonNull String packageName,
            @UserIdInt int userId);

    /**
     * Allow to change the status of a Intent Verification status for all IntentFilter of an App.
     * This is related to the {@link android.content.IntentFilter#setAutoVerify(boolean)} and
     * {@link android.content.IntentFilter#getAutoVerify()}
     *
     * This is used by the ResolverActivity to change the status depending on what the User select
     * in the Disambiguation Dialog and also used by the Settings App for changing the default App
     * for a domain.
     *
     * @param packageName The package name of the Activity associated with the IntentFilter.
     * @param status The status to set to. This can be
     *              {@link #INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK} or
     *              {@link #INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS} or
     *              {@link #INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER}
     * @param userId The user id.
     *
     * @return true if the status has been set. False otherwise.
     *
     * @deprecated This API represents a very dangerous behavior where Settings or a system app with
     * the right permissions can force an application to be verified for all of its declared
     * domains. This has been removed to prevent unintended usage, and no longer does anything,
     * always returning false. If a caller truly wishes to grant <i></i>every</i> declared web
     * domain to an application, use
     * {@link DomainVerificationManager#setDomainVerificationUserSelection(UUID, Set, boolean)},
     * passing in all of the domains returned inside
     * {@link DomainVerificationManager#getDomainVerificationUserState(String)}.
     *
     * @hide
     */
    @Deprecated
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    @RequiresPermission(android.Manifest.permission.SET_PREFERRED_APPLICATIONS)
    public abstract boolean updateIntentVerificationStatusAsUser(@NonNull String packageName,
            int status, @UserIdInt int userId);

    /**
     * Get the list of IntentFilterVerificationInfo for a specific package and User.
     *
     * @param packageName the package name. When this parameter is set to a non null value,
     *                    the results will be filtered by the package name provided.
     *                    Otherwise, there will be no filtering and it will return a list
     *                    corresponding for all packages
     *
     * @return a list of IntentFilterVerificationInfo for a specific package.
     *
     * @deprecated Use {@link DomainVerificationManager} instead.
     * @hide
     */
    @Deprecated
    @SuppressWarnings("HiddenAbstractMethod")
    @NonNull
    @SystemApi
    public abstract List<IntentFilterVerificationInfo> getIntentFilterVerifications(
            @NonNull String packageName);

    /**
     * Get the list of IntentFilter for a specific package.
     *
     * @param packageName the package name. This parameter is set to a non null value,
     *                    the list will contain all the IntentFilter for that package.
     *                    Otherwise, the list will be empty.
     *
     * @return a list of IntentFilter for a specific package.
     *
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @NonNull
    @SystemApi
    public abstract List<IntentFilter> getAllIntentFilters(@NonNull String packageName);

    /**
     * Get the default Browser package name for a specific user.
     *
     * @param userId The user id.
     *
     * @return the package name of the default Browser for the specified user. If the user id passed
     *         is -1 (all users) it will return a null value.
     *
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @Nullable
    @SystemApi
    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS_FULL)
    public abstract String getDefaultBrowserPackageNameAsUser(@UserIdInt int userId);

    /**
     * Set the default Browser package name for a specific user.
     *
     * @param packageName The package name of the default Browser.
     * @param userId The user id.
     *
     * @return true if the default Browser for the specified user has been set,
     *         otherwise return false. If the user id passed is -1 (all users) this call will not
     *         do anything and just return false.
     *
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    @RequiresPermission(allOf = {
            Manifest.permission.SET_PREFERRED_APPLICATIONS,
            Manifest.permission.INTERACT_ACROSS_USERS_FULL})
    public abstract boolean setDefaultBrowserPackageNameAsUser(@Nullable String packageName,
            @UserIdInt int userId);

    /**
     * Change the installer associated with a given package.  There are limitations
     * on how the installer package can be changed; in particular:
     * <ul>
     * <li> A SecurityException will be thrown if <var>installerPackageName</var>
     * is not signed with the same certificate as the calling application.
     * <li> A SecurityException will be thrown if <var>targetPackage</var> already
     * has an installer package, and that installer package is not signed with
     * the same certificate as the calling application.
     * </ul>
     *
     * @param targetPackage The installed package whose installer will be changed.
     * @param installerPackageName The package name of the new installer.  May be
     * null to clear the association.
     */
    public abstract void setInstallerPackageName(@NonNull String targetPackage,
            @Nullable String installerPackageName);

    /** @hide */
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    @RequiresPermission(Manifest.permission.INSTALL_PACKAGES)
    public abstract void setUpdateAvailable(@NonNull String packageName, boolean updateAvaialble);

    /**
     * Attempts to delete a package. Since this may take a little while, the
     * result will be posted back to the given observer. A deletion will fail if
     * the calling context lacks the
     * {@link android.Manifest.permission#DELETE_PACKAGES} permission, if the
     * named package cannot be found, or if the named package is a system
     * package.
     *
     * @param packageName The name of the package to delete
     * @param observer An observer callback to get notified when the package
     *            deletion is complete.
     *            {@link android.content.pm.IPackageDeleteObserver#packageDeleted}
     *            will be called when that happens. observer may be null to
     *            indicate that no callback is desired.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @RequiresPermission(Manifest.permission.DELETE_PACKAGES)
    @UnsupportedAppUsage
    public abstract void deletePackage(@NonNull String packageName,
            @Nullable IPackageDeleteObserver observer, @DeleteFlags int flags);

    /**
     * Attempts to delete a package. Since this may take a little while, the
     * result will be posted back to the given observer. A deletion will fail if
     * the named package cannot be found, or if the named package is a system
     * package.
     *
     * @param packageName The name of the package to delete
     * @param observer An observer callback to get notified when the package
     *            deletion is complete.
     *            {@link android.content.pm.IPackageDeleteObserver#packageDeleted}
     *            will be called when that happens. observer may be null to
     *            indicate that no callback is desired.
     * @param userId The user Id
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @RequiresPermission(anyOf = {
            Manifest.permission.DELETE_PACKAGES,
            Manifest.permission.INTERACT_ACROSS_USERS_FULL})
    @UnsupportedAppUsage
    public abstract void deletePackageAsUser(@NonNull String packageName,
            @Nullable IPackageDeleteObserver observer, @DeleteFlags int flags,
            @UserIdInt int userId);

    /**
     * Retrieve the package name of the application that installed a package. This identifies
     * which market the package came from.
     *
     * @param packageName The name of the package to query
     * @throws IllegalArgumentException if the given package name is not installed
     *
     * @deprecated use {@link #getInstallSourceInfo(String)} instead
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @Deprecated
    @Nullable
    public abstract String getInstallerPackageName(@NonNull String packageName);

    /**
     * Retrieves information about how a package was installed or updated.
     * <p>
     * If the calling application does not hold the INSTALL_PACKAGES permission then
     * the result will always return {@code null} from
     * {@link InstallSourceInfo#getOriginatingPackageName()}.
     * <p>
     * If the package that requested the install has been uninstalled, then information about it
     * will only be returned from {@link InstallSourceInfo#getInitiatingPackageName()} and
     * {@link InstallSourceInfo#getInitiatingPackageSigningInfo()} if the calling package is
     * requesting its own install information and is not an instant app.
     *
     * @param packageName The name of the package to query
     * @throws NameNotFoundException if the given package name is not installed
     */
    @NonNull
    public InstallSourceInfo getInstallSourceInfo(@NonNull String packageName)
            throws NameNotFoundException {
        throw new UnsupportedOperationException("getInstallSourceInfo not implemented");
    }

    /**
     * Attempts to clear the user data directory of an application.
     * Since this may take a little while, the result will
     * be posted back to the given observer.  A deletion will fail if the
     * named package cannot be found, or if the named package is a "system package".
     *
     * @param packageName The name of the package
     * @param observer An observer callback to get notified when the operation is finished
     * {@link android.content.pm.IPackageDataObserver#onRemoveCompleted(String, boolean)}
     * will be called when that happens.  observer may be null to indicate that
     * no callback is desired.
     *
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    public abstract void clearApplicationUserData(@NonNull String packageName,
            @Nullable IPackageDataObserver observer);
    /**
     * Attempts to delete the cache files associated with an application.
     * Since this may take a little while, the result will
     * be posted back to the given observer.  A deletion will fail if the calling context
     * lacks the {@link android.Manifest.permission#DELETE_CACHE_FILES} permission, if the
     * named package cannot be found, or if the named package is a "system package".
     *
     * @param packageName The name of the package to delete
     * @param observer An observer callback to get notified when the cache file deletion
     * is complete.
     * {@link android.content.pm.IPackageDataObserver#onRemoveCompleted(String, boolean)}
     * will be called when that happens.  observer may be null to indicate that
     * no callback is desired.
     *
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    public abstract void deleteApplicationCacheFiles(@NonNull String packageName,
            @Nullable IPackageDataObserver observer);

    /**
     * Attempts to delete the cache files associated with an application for a given user. Since
     * this may take a little while, the result will be posted back to the given observer. A
     * deletion will fail if the calling context lacks the
     * {@link android.Manifest.permission#DELETE_CACHE_FILES} permission, if the named package
     * cannot be found, or if the named package is a "system package". If {@code userId} does not
     * belong to the calling user, the caller must have
     * {@link android.Manifest.permission#INTERACT_ACROSS_USERS} permission.
     *
     * @param packageName The name of the package to delete
     * @param userId the user for which the cache files needs to be deleted
     * @param observer An observer callback to get notified when the cache file deletion is
     *            complete.
     *            {@link android.content.pm.IPackageDataObserver#onRemoveCompleted(String, boolean)}
     *            will be called when that happens. observer may be null to indicate that no
     *            callback is desired.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    public abstract void deleteApplicationCacheFilesAsUser(@NonNull String packageName,
            @UserIdInt int userId, @Nullable IPackageDataObserver observer);

    /**
     * Free storage by deleting LRU sorted list of cache files across
     * all applications. If the currently available free storage
     * on the device is greater than or equal to the requested
     * free storage, no cache files are cleared. If the currently
     * available storage on the device is less than the requested
     * free storage, some or all of the cache files across
     * all applications are deleted (based on last accessed time)
     * to increase the free storage space on the device to
     * the requested value. There is no guarantee that clearing all
     * the cache files from all applications will clear up
     * enough storage to achieve the desired value.
     * @param freeStorageSize The number of bytes of storage to be
     * freed by the system. Say if freeStorageSize is XX,
     * and the current free storage is YY,
     * if XX is less than YY, just return. if not free XX-YY number
     * of bytes if possible.
     * @param observer call back used to notify when
     * the operation is completed
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void freeStorageAndNotify(long freeStorageSize,
            @Nullable IPackageDataObserver observer) {
        freeStorageAndNotify(null, freeStorageSize, observer);
    }

    /** {@hide} */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    public abstract void freeStorageAndNotify(@Nullable String volumeUuid, long freeStorageSize,
            @Nullable IPackageDataObserver observer);

    /**
     * Free storage by deleting LRU sorted list of cache files across
     * all applications. If the currently available free storage
     * on the device is greater than or equal to the requested
     * free storage, no cache files are cleared. If the currently
     * available storage on the device is less than the requested
     * free storage, some or all of the cache files across
     * all applications are deleted (based on last accessed time)
     * to increase the free storage space on the device to
     * the requested value. There is no guarantee that clearing all
     * the cache files from all applications will clear up
     * enough storage to achieve the desired value.
     * @param freeStorageSize The number of bytes of storage to be
     * freed by the system. Say if freeStorageSize is XX,
     * and the current free storage is YY,
     * if XX is less than YY, just return. if not free XX-YY number
     * of bytes if possible.
     * @param pi IntentSender call back used to
     * notify when the operation is completed.May be null
     * to indicate that no call back is desired.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void freeStorage(long freeStorageSize, @Nullable IntentSender pi) {
        freeStorage(null, freeStorageSize, pi);
    }

    /** {@hide} */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    public abstract void freeStorage(@Nullable String volumeUuid, long freeStorageSize,
            @Nullable IntentSender pi);

    /**
     * Retrieve the size information for a package.
     * Since this may take a little while, the result will
     * be posted back to the given observer.  The calling context
     * should have the {@link android.Manifest.permission#GET_PACKAGE_SIZE} permission.
     *
     * @param packageName The name of the package whose size information is to be retrieved
     * @param userId The user whose size information should be retrieved.
     * @param observer An observer callback to get notified when the operation
     * is complete.
     * {@link android.content.pm.IPackageStatsObserver#onGetStatsCompleted(PackageStats, boolean)}
     * The observer's callback is invoked with a PackageStats object(containing the
     * code, data and cache sizes of the package) and a boolean value representing
     * the status of the operation. observer may be null to indicate that
     * no callback is desired.
     *
     * @deprecated use {@link StorageStatsManager} instead.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @Deprecated
    @UnsupportedAppUsage
    public abstract void getPackageSizeInfoAsUser(@NonNull String packageName,
            @UserIdInt int userId, @Nullable IPackageStatsObserver observer);

    /**
     * Like {@link #getPackageSizeInfoAsUser(String, int, IPackageStatsObserver)}, but
     * returns the size for the calling user.
     *
     * @deprecated use {@link StorageStatsManager} instead.
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public void getPackageSizeInfo(@NonNull String packageName, IPackageStatsObserver observer) {
        getPackageSizeInfoAsUser(packageName, getUserId(), observer);
    }

    /**
     * @deprecated This function no longer does anything. It is the platform's
     * responsibility to assign preferred activities and this cannot be modified
     * directly. To determine the activities resolved by the platform, use
     * {@link #resolveActivity} or {@link #queryIntentActivities}. To configure
     * an app to be responsible for a particular role and to check current role
     * holders, see {@link android.app.role.RoleManager}.
     */
    @Deprecated
    public abstract void addPackageToPreferred(@NonNull String packageName);

    /**
     * @deprecated This function no longer does anything. It is the platform's
     * responsibility to assign preferred activities and this cannot be modified
     * directly. To determine the activities resolved by the platform, use
     * {@link #resolveActivity} or {@link #queryIntentActivities}. To configure
     * an app to be responsible for a particular role and to check current role
     * holders, see {@link android.app.role.RoleManager}.
     */
    @Deprecated
    public abstract void removePackageFromPreferred(@NonNull String packageName);

    /**
     * Retrieve the list of all currently configured preferred packages. The
     * first package on the list is the most preferred, the last is the least
     * preferred.
     *
     * @param flags Additional option flags to modify the data returned.
     * @return A List of PackageInfo objects, one for each preferred
     *         application, in order of preference.
     *
     * @deprecated This function no longer does anything. It is the platform's
     * responsibility to assign preferred activities and this cannot be modified
     * directly. To determine the activities resolved by the platform, use
     * {@link #resolveActivity} or {@link #queryIntentActivities}. To configure
     * an app to be responsible for a particular role and to check current role
     * holders, see {@link android.app.role.RoleManager}.
     */
    @NonNull
    @Deprecated
    public abstract List<PackageInfo> getPreferredPackages(int flags);

    /**
     * Add a new preferred activity mapping to the system.  This will be used
     * to automatically select the given activity component when
     * {@link Context#startActivity(Intent) Context.startActivity()} finds
     * multiple matching activities and also matches the given filter.
     *
     * @param filter The set of intents under which this activity will be
     * made preferred.
     * @param match The IntentFilter match category that this preference
     * applies to.
     * @param set The set of activities that the user was picking from when
     * this preference was made.
     * @param activity The component name of the activity that is to be
     * preferred.
     *
     * @deprecated This function no longer does anything. It is the platform's
     * responsibility to assign preferred activities and this cannot be modified
     * directly. To determine the activities resolved by the platform, use
     * {@link #resolveActivity} or {@link #queryIntentActivities}. To configure
     * an app to be responsible for a particular role and to check current role
     * holders, see {@link android.app.role.RoleManager}.
     */
    @Deprecated
    public abstract void addPreferredActivity(@NonNull IntentFilter filter, int match,
            @Nullable ComponentName[] set, @NonNull ComponentName activity);

    /**
     * Same as {@link #addPreferredActivity(IntentFilter, int,
            ComponentName[], ComponentName)}, but with a specific userId to apply the preference
            to.
     * @hide
     *
     * @deprecated This function no longer does anything. It is the platform's
     * responsibility to assign preferred activities and this cannot be modified
     * directly. To determine the activities resolved by the platform, use
     * {@link #resolveActivity} or {@link #queryIntentActivities}. To configure
     * an app to be responsible for a particular role and to check current role
     * holders, see {@link android.app.role.RoleManager}.
     */
    @Deprecated
    @UnsupportedAppUsage
    public void addPreferredActivityAsUser(@NonNull IntentFilter filter, int match,
            @Nullable ComponentName[] set, @NonNull ComponentName activity, @UserIdInt int userId) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Replaces an existing preferred activity mapping to the system, and if that were not present
     * adds a new preferred activity.  This will be used
     * to automatically select the given activity component when
     * {@link Context#startActivity(Intent) Context.startActivity()} finds
     * multiple matching activities and also matches the given filter.
     *
     * @param filter The set of intents under which this activity will be
     * made preferred.
     * @param match The IntentFilter match category that this preference
     * applies to.
     * @param set The set of activities that the user was picking from when
     * this preference was made.
     * @param activity The component name of the activity that is to be
     * preferred.
     *
     * @hide
     *
     * @deprecated This function no longer does anything. It is the platform's
     * responsibility to assign preferred activities and this cannot be modified
     * directly. To determine the activities resolved by the platform, use
     * {@link #resolveActivity} or {@link #queryIntentActivities}. To configure
     * an app to be responsible for a particular role and to check current role
     * holders, see {@link android.app.role.RoleManager}.
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @Deprecated
    @UnsupportedAppUsage
    public abstract void replacePreferredActivity(@NonNull IntentFilter filter, int match,
            @Nullable ComponentName[] set, @NonNull ComponentName activity);

    /**
     * Replaces an existing preferred activity mapping to the system, and if that were not present
     * adds a new preferred activity.  This will be used to automatically select the given activity
     * component when {@link Context#startActivity(Intent) Context.startActivity()} finds multiple
     * matching activities and also matches the given filter.
     *
     * @param filter The set of intents under which this activity will be made preferred.
     * @param match The IntentFilter match category that this preference applies to. Should be a
     *              combination of {@link IntentFilter#MATCH_CATEGORY_MASK} and
     *              {@link IntentFilter#MATCH_ADJUSTMENT_MASK}).
     * @param set The set of activities that the user was picking from when this preference was
     *            made.
     * @param activity The component name of the activity that is to be preferred.
     *
     * @hide
     */
    @SystemApi
    public void replacePreferredActivity(@NonNull IntentFilter filter, int match,
            @NonNull List<ComponentName> set, @NonNull ComponentName activity) {
        replacePreferredActivity(filter, match, set.toArray(new ComponentName[0]), activity);
    }

    /**
     * @hide
     *
     * @deprecated This function no longer does anything. It is the platform's
     * responsibility to assign preferred activities and this cannot be modified
     * directly. To determine the activities resolved by the platform, use
     * {@link #resolveActivity} or {@link #queryIntentActivities}. To configure
     * an app to be responsible for a particular role and to check current role
     * holders, see {@link android.app.role.RoleManager}.
     */
    @Deprecated
    @UnsupportedAppUsage
    public void replacePreferredActivityAsUser(@NonNull IntentFilter filter, int match,
            @Nullable ComponentName[] set, @NonNull ComponentName activity, @UserIdInt int userId) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Remove all preferred activity mappings, previously added with
     * {@link #addPreferredActivity}, from the
     * system whose activities are implemented in the given package name.
     * An application can only clear its own package(s).
     *
     * @param packageName The name of the package whose preferred activity
     * mappings are to be removed.
     *
     * @deprecated This function no longer does anything. It is the platform's
     * responsibility to assign preferred activities and this cannot be modified
     * directly. To determine the activities resolved by the platform, use
     * {@link #resolveActivity} or {@link #queryIntentActivities}. To configure
     * an app to be responsible for a particular role and to check current role
     * holders, see {@link android.app.role.RoleManager}.
     */
    @Deprecated
    public abstract void clearPackagePreferredActivities(@NonNull String packageName);

    /**
     * Same as {@link #addPreferredActivity(IntentFilter, int, ComponentName[], ComponentName)},
     * but removes all existing entries that match this filter.
     * @hide
     */
    public void addUniquePreferredActivity(@NonNull IntentFilter filter, int match,
            @Nullable ComponentName[] set, @NonNull ComponentName activity) {
        throw new UnsupportedOperationException(
                "addUniquePreferredActivity not implemented in subclass");
    }

    /**
     * Retrieve all preferred activities, previously added with
     * {@link #addPreferredActivity}, that are
     * currently registered with the system.
     *
     * @param outFilters A required list in which to place the filters of all of the
     * preferred activities.
     * @param outActivities A required list in which to place the component names of
     * all of the preferred activities.
     * @param packageName An optional package in which you would like to limit
     * the list.  If null, all activities will be returned; if non-null, only
     * those activities in the given package are returned.
     *
     * @return Returns the total number of registered preferred activities
     * (the number of distinct IntentFilter records, not the number of unique
     * activity components) that were found.
     *
     * @deprecated This function no longer does anything. It is the platform's
     * responsibility to assign preferred activities and this cannot be modified
     * directly. To determine the activities resolved by the platform, use
     * {@link #resolveActivity} or {@link #queryIntentActivities}. To configure
     * an app to be responsible for a particular role and to check current role
     * holders, see {@link android.app.role.RoleManager}.
     */
    @Deprecated
    public abstract int getPreferredActivities(@NonNull List<IntentFilter> outFilters,
            @NonNull List<ComponentName> outActivities, @Nullable String packageName);

    /**
     * Ask for the set of available 'home' activities and the current explicit
     * default, if any.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @Nullable
    @UnsupportedAppUsage
    public abstract ComponentName getHomeActivities(@NonNull List<ResolveInfo> outActivities);

    /**
     * Set the enabled setting for a package component (activity, receiver, service, provider).
     * This setting will override any enabled state which may have been set by the component in its
     * manifest.
     *
     * <p>Consider using {@link #setComponentEnabledSettings(List)} if multiple components need to
     * be updated atomically.
     *
     * @param componentName The component to enable
     * @param newState The new enabled state for the component.
     * @param flags Optional behavior flags.
     */
    @RequiresPermission(value = android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE,
            conditional = true)
    public abstract void setComponentEnabledSetting(@NonNull ComponentName componentName,
            @EnabledState int newState, @EnabledFlags int flags);

    /**
     * Set the enabled settings for package components such as activities, receivers, services and
     * providers. This setting will override any enabled state which may have been set by the
     * component in its manifest.
     *
     * <p>This api accepts a list of component changes, and applies them all atomically. The
     * application can use this api if components have dependencies and need to be updated
     * atomically.
     *
     * <p>The permission is not required if target components are running under the same uid with
     * the caller.
     *
     * @param settings The list of component enabled settings to update. Note that an
     *                 {@link IllegalArgumentException} is thrown if the duplicated component name
     *                 is in the list or there's a conflict {@link #DONT_KILL_APP} flag between
     *                 different components in the same package.
     *
     * @see #setComponentEnabledSetting(ComponentName, int, int)
     */
    @RequiresPermission(value = android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE,
            conditional = true)
    public void setComponentEnabledSettings(@NonNull List<ComponentEnabledSetting> settings) {
        throw new UnsupportedOperationException("setComponentEnabledSettings not implemented"
                + "in subclass");
    }

    /**
     * Return the enabled setting for a package component (activity,
     * receiver, service, provider).  This returns the last value set by
     * {@link #setComponentEnabledSetting(ComponentName, int, int)}; in most
     * cases this value will be {@link #COMPONENT_ENABLED_STATE_DEFAULT} since
     * the value originally specified in the manifest has not been modified.
     *
     * @param componentName The component to retrieve.
     * @return Returns the current enabled state for the component.
     */
    public abstract @EnabledState int getComponentEnabledSetting(
            @NonNull ComponentName componentName);

    /**
     * Set whether a synthetic app details activity will be generated if the app has no enabled
     * launcher activity. Disabling this allows the app to have no launcher icon.
     *
     * @param packageName The package name of the app
     * @param enabled The new enabled state for the synthetic app details activity.
     *
     * @hide
     */
    @RequiresPermission(value = android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE,
            conditional = true)
    @SystemApi
    public void setSyntheticAppDetailsActivityEnabled(@NonNull String packageName,
            boolean enabled) {
        throw new UnsupportedOperationException(
                "setSyntheticAppDetailsActivityEnabled not implemented");
    }


    /**
     * Return whether a synthetic app details activity will be generated if the app has no enabled
     * launcher activity.
     *
     * @param packageName The package name of the app
     * @return Returns the enabled state for the synthetic app details activity.
     *
     *
     */
    public boolean getSyntheticAppDetailsActivityEnabled(@NonNull String packageName) {
        throw new UnsupportedOperationException(
                "getSyntheticAppDetailsActivityEnabled not implemented");
    }

    /**
     * Set the enabled setting for an application
     * This setting will override any enabled state which may have been set by the application in
     * its manifest.  It also overrides the enabled state set in the manifest for any of the
     * application's components.  It does not override any enabled state set by
     * {@link #setComponentEnabledSetting} for any of the application's components.
     *
     * @param packageName The package name of the application to enable
     * @param newState The new enabled state for the application.
     * @param flags Optional behavior flags.
     */
    @RequiresPermission(value = android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE,
            conditional = true)
    public abstract void setApplicationEnabledSetting(@NonNull String packageName,
            @EnabledState int newState, @EnabledFlags int flags);

    /**
     * Return the enabled setting for an application. This returns
     * the last value set by
     * {@link #setApplicationEnabledSetting(String, int, int)}; in most
     * cases this value will be {@link #COMPONENT_ENABLED_STATE_DEFAULT} since
     * the value originally specified in the manifest has not been modified.
     *
     * @param packageName The package name of the application to retrieve.
     * @return Returns the current enabled state for the application.
     * @throws IllegalArgumentException if the named package does not exist.
     */
    public abstract @EnabledState int getApplicationEnabledSetting(@NonNull String packageName);

    /**
     * Flush the package restrictions for a given user to disk. This forces the package restrictions
     * like component and package enabled settings to be written to disk and avoids the delay that
     * is otherwise present when changing those settings.
     *
     * @param userId Ther userId of the user whose restrictions are to be flushed.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    public abstract void flushPackageRestrictionsAsUser(@UserIdInt int userId);

    /**
     * Puts the package in a hidden state, which is almost like an uninstalled state,
     * making the package unavailable, but it doesn't remove the data or the actual
     * package file. Application can be unhidden by either resetting the hidden state
     * or by installing it, such as with {@link #installExistingPackage(String)}
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    public abstract boolean setApplicationHiddenSettingAsUser(@NonNull String packageName,
            boolean hidden, @NonNull UserHandle userHandle);

    /**
     * Returns the hidden state of a package.
     * @see #setApplicationHiddenSettingAsUser(String, boolean, UserHandle)
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    public abstract boolean getApplicationHiddenSettingAsUser(@NonNull String packageName,
            @NonNull UserHandle userHandle);

    /**
     * Sets the state of a system app.
     *
     * This method can be used to change a system app's hidden-until-installed state (via
     * {@link #SYSTEM_APP_STATE_HIDDEN_UNTIL_INSTALLED_HIDDEN} and
     * {@link #SYSTEM_APP_STATE_HIDDEN_UNTIL_INSTALLED_VISIBLE} or its installation state (via
     * {@link #SYSTEM_APP_STATE_INSTALLED} and {@link #SYSTEM_APP_STATE_UNINSTALLED}.
     *
     * This API may only be called from {@link android.os.Process#SYSTEM_UID} or
     * {@link android.os.Process#PHONE_UID}.
     *
     * @param packageName Package name of the app.
     * @param state State of the app.
     * @hide
     */
    @SystemApi
    public void setSystemAppState(@NonNull String packageName, @SystemAppState int state) {
        throw new RuntimeException("Not implemented. Must override in a subclass");
    }

    /**
     * Return whether the device has been booted into safe mode.
     */
    public abstract boolean isSafeMode();

    /**
     * Adds a listener for permission changes for installed packages.
     *
     * @param listener The listener to add.
     *
     * @hide
     */
    //@Deprecated
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    @RequiresPermission(Manifest.permission.OBSERVE_GRANT_REVOKE_PERMISSIONS)
    public abstract void addOnPermissionsChangeListener(
            @NonNull OnPermissionsChangedListener listener);

    /**
     * Remvoes a listener for permission changes for installed packages.
     *
     * @param listener The listener to remove.
     *
     * @hide
     */
    //@Deprecated
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    @RequiresPermission(Manifest.permission.OBSERVE_GRANT_REVOKE_PERMISSIONS)
    public abstract void removeOnPermissionsChangeListener(
            @NonNull OnPermissionsChangedListener listener);

    /**
     * Return the {@link KeySet} associated with the String alias for this
     * application.
     *
     * @param alias The alias for a given {@link KeySet} as defined in the
     *        application's AndroidManifest.xml.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @NonNull
    @UnsupportedAppUsage
    public abstract KeySet getKeySetByAlias(@NonNull String packageName, @NonNull String alias);

    /** Return the signing {@link KeySet} for this application.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @NonNull
    @UnsupportedAppUsage
    public abstract KeySet getSigningKeySet(@NonNull String packageName);

    /**
     * Return whether the package denoted by packageName has been signed by all
     * of the keys specified by the {@link KeySet} ks.  This will return true if
     * the package has been signed by additional keys (a superset) as well.
     * Compare to {@link #isSignedByExactly(String packageName, KeySet ks)}.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    public abstract boolean isSignedBy(@NonNull String packageName, @NonNull KeySet ks);

    /**
     * Return whether the package denoted by packageName has been signed by all
     * of, and only, the keys specified by the {@link KeySet} ks. Compare to
     * {@link #isSignedBy(String packageName, KeySet ks)}.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    public abstract boolean isSignedByExactly(@NonNull String packageName, @NonNull KeySet ks);

    /**
     * Flag to denote no restrictions. This should be used to clear any restrictions that may have
     * been previously set for the package.
     * @hide
     * @see #setDistractingPackageRestrictions(String[], int)
     */
    @SystemApi
    public static final int RESTRICTION_NONE = 0x0;

    /**
     * Flag to denote that a package should be hidden from any suggestions to the user.
     * @hide
     * @see #setDistractingPackageRestrictions(String[], int)
     */
    @SystemApi
    public static final int RESTRICTION_HIDE_FROM_SUGGESTIONS = 0x00000001;

    /**
     * Flag to denote that a package's notifications should be hidden.
     * @hide
     * @see #setDistractingPackageRestrictions(String[], int)
     */
    @SystemApi
    public static final int RESTRICTION_HIDE_NOTIFICATIONS = 0x00000002;

    /**
     * Restriction flags to set on a package that is considered as distracting to the user.
     * These should help the user to restrict their usage of these apps.
     *
     * @see #setDistractingPackageRestrictions(String[], int)
     * @hide
     */
    @IntDef(flag = true, prefix = {"RESTRICTION_"}, value = {
            RESTRICTION_NONE,
            RESTRICTION_HIDE_FROM_SUGGESTIONS,
            RESTRICTION_HIDE_NOTIFICATIONS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DistractionRestriction {}

    /**
     * Mark or unmark the given packages as distracting to the user.
     * These packages can have certain restrictions set that should discourage the user to launch
     * them often. For example, notifications from such an app can be hidden, or the app can be
     * removed from launcher suggestions, so the user is able to restrict their use of these apps.
     *
     * <p>The caller must hold {@link android.Manifest.permission#SUSPEND_APPS} to use this API.
     *
     * @param packages Packages to mark as distracting.
     * @param restrictionFlags Any combination of restrictions to impose on the given packages.
     *                         {@link #RESTRICTION_NONE} can be used to clear any existing
     *                         restrictions.
     * @return A list of packages that could not have the {@code restrictionFlags} set. The system
     * may prevent restricting critical packages to preserve normal device function.
     *
     * @hide
     * @see #RESTRICTION_NONE
     * @see #RESTRICTION_HIDE_FROM_SUGGESTIONS
     * @see #RESTRICTION_HIDE_NOTIFICATIONS
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.SUSPEND_APPS)
    @NonNull
    public String[] setDistractingPackageRestrictions(@NonNull String[] packages,
            @DistractionRestriction int restrictionFlags) {
        throw new UnsupportedOperationException(
                "setDistractingPackageRestrictions not implemented");
    }

    /**
     * Puts the package in a suspended state, where attempts at starting activities are denied.
     *
     * <p>It doesn't remove the data or the actual package file. The application's notifications
     * will be hidden, any of its started activities will be stopped and it will not be able to
     * show toasts or system alert windows or ring the device.
     *
     * <p>When the user tries to launch a suspended app, a system dialog with the given
     * {@code dialogMessage} will be shown instead. Since the message is supplied to the system as
     * a {@link String}, the caller needs to take care of localization as needed.
     * The dialog message can optionally contain a placeholder for the name of the suspended app.
     * The system uses {@link String#format(Locale, String, Object...) String.format} to insert the
     * app name into the message, so an example format string could be {@code "The app %1$s is
     * currently suspended"}. This makes it easier for callers to provide a single message which
     * works for all the packages being suspended in a single call.
     *
     * <p>The package must already be installed. If the package is uninstalled while suspended
     * the package will no longer be suspended. </p>
     *
     * <p>Optionally, the suspending app can provide extra information in the form of
     * {@link PersistableBundle} objects to be shared with the apps being suspended and the
     * launcher to support customization that they might need to handle the suspended state.
     *
     * <p>The caller must hold {@link Manifest.permission#SUSPEND_APPS} to use this API.
     *
     * @param packageNames The names of the packages to set the suspended status.
     * @param suspended If set to {@code true}, the packages will be suspended, if set to
     * {@code false}, the packages will be unsuspended.
     * @param appExtras An optional {@link PersistableBundle} that the suspending app can provide
     *                  which will be shared with the apps being suspended. Ignored if
     *                  {@code suspended} is false.
     * @param launcherExtras An optional {@link PersistableBundle} that the suspending app can
     *                       provide which will be shared with the launcher. Ignored if
     *                       {@code suspended} is false.
     * @param dialogMessage The message to be displayed to the user, when they try to launch a
     *                      suspended app.
     *
     * @return an array of package names for which the suspended status could not be set as
     * requested in this method. Returns {@code null} if {@code packageNames} was {@code null}.
     *
     * @deprecated use {@link #setPackagesSuspended(String[], boolean, PersistableBundle,
     * PersistableBundle, android.content.pm.SuspendDialogInfo)} instead.
     *
     * @hide
     */
    @SystemApi
    @Deprecated
    @RequiresPermission(Manifest.permission.SUSPEND_APPS)
    @Nullable
    public String[] setPackagesSuspended(@Nullable String[] packageNames, boolean suspended,
            @Nullable PersistableBundle appExtras, @Nullable PersistableBundle launcherExtras,
            @Nullable String dialogMessage) {
        throw new UnsupportedOperationException("setPackagesSuspended not implemented");
    }

    /**
     * Puts the given packages in a suspended state, where attempts at starting activities are
     * denied.
     *
     * <p>The suspended application's notifications and all of its windows will be hidden, any
     * of its started activities will be stopped and it won't be able to ring the device.
     * It doesn't remove the data or the actual package file.
     *
     * <p>When the user tries to launch a suspended app, a system dialog alerting them that the app
     * is suspended will be shown instead.
     * The caller can optionally customize the dialog by passing a {@link SuspendDialogInfo} object
     * to this API. This dialog will have a button that starts the
     * {@link Intent#ACTION_SHOW_SUSPENDED_APP_DETAILS} intent if the suspending app declares an
     * activity which handles this action.
     *
     * <p>The packages being suspended must already be installed. If a package is uninstalled, it
     * will no longer be suspended.
     *
     * <p>Optionally, the suspending app can provide extra information in the form of
     * {@link PersistableBundle} objects to be shared with the apps being suspended and the
     * launcher to support customization that they might need to handle the suspended state.
     *
     * <p>The caller must hold {@link Manifest.permission#SUSPEND_APPS} to use this API.
     *
     * @param packageNames The names of the packages to set the suspended status.
     * @param suspended If set to {@code true}, the packages will be suspended, if set to
     * {@code false}, the packages will be unsuspended.
     * @param appExtras An optional {@link PersistableBundle} that the suspending app can provide
     *                  which will be shared with the apps being suspended. Ignored if
     *                  {@code suspended} is false.
     * @param launcherExtras An optional {@link PersistableBundle} that the suspending app can
     *                       provide which will be shared with the launcher. Ignored if
     *                       {@code suspended} is false.
     * @param dialogInfo An optional {@link SuspendDialogInfo} object describing the dialog that
     *                   should be shown to the user when they try to launch a suspended app.
     *                   Ignored if {@code suspended} is false.
     *
     * @return an array of package names for which the suspended status could not be set as
     * requested in this method. Returns {@code null} if {@code packageNames} was {@code null}.
     *
     * @see #isPackageSuspended
     * @see SuspendDialogInfo
     * @see SuspendDialogInfo.Builder
     * @see Intent#ACTION_SHOW_SUSPENDED_APP_DETAILS
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SUSPEND_APPS)
    @Nullable
    public String[] setPackagesSuspended(@Nullable String[] packageNames, boolean suspended,
            @Nullable PersistableBundle appExtras, @Nullable PersistableBundle launcherExtras,
            @Nullable SuspendDialogInfo dialogInfo) {
        throw new UnsupportedOperationException("setPackagesSuspended not implemented");
    }

    /**
     * Returns any packages in a given set of packages that cannot be suspended via a call to {@link
     * #setPackagesSuspended(String[], boolean, PersistableBundle, PersistableBundle,
     * SuspendDialogInfo) setPackagesSuspended}. The platform prevents suspending certain critical
     * packages to keep the device in a functioning state, e.g. the default dialer and launcher.
     * Apps need to hold {@link Manifest.permission#SUSPEND_APPS SUSPEND_APPS} to call this API.
     *
     * <p>
     * Note that this set of critical packages can change with time, so even though a package name
     * was not returned by this call, it does not guarantee that a subsequent call to
     * {@link #setPackagesSuspended(String[], boolean, PersistableBundle, PersistableBundle,
     * SuspendDialogInfo) setPackagesSuspended} for that package will succeed, especially if
     * significant time elapsed between the two calls.
     *
     * @param packageNames The packages to check.
     * @return A list of packages that can not be currently suspended by the system.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SUSPEND_APPS)
    @NonNull
    public String[] getUnsuspendablePackages(@NonNull String[] packageNames) {
        throw new UnsupportedOperationException("getUnsuspendablePackages not implemented");
    }

    /**
     * @see #setPackagesSuspended(String[], boolean, PersistableBundle, PersistableBundle, String)
     * @param packageName The name of the package to get the suspended status of.
     * @param userId The user id.
     * @return {@code true} if the package is suspended or {@code false} if the package is not
     * suspended.
     * @throws IllegalArgumentException if the package was not found.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    public abstract boolean isPackageSuspendedForUser(@NonNull String packageName, int userId);

    /**
     * Query if an app is currently suspended.
     *
     * @return {@code true} if the given package is suspended, {@code false} otherwise
     * @throws NameNotFoundException if the package could not be found.
     *
     * @see #isPackageSuspended()
     */
    public boolean isPackageSuspended(@NonNull String packageName) throws NameNotFoundException {
        throw new UnsupportedOperationException("isPackageSuspended not implemented");
    }

    /**
     * Apps can query this to know if they have been suspended. A system app with the permission
     * {@code android.permission.SUSPEND_APPS} can put any app on the device into a suspended state.
     *
     * <p>While in this state, the application's notifications will be hidden, any of its started
     * activities will be stopped and it will not be able to show toasts or dialogs or play audio.
     * When the user tries to launch a suspended app, the system will, instead, show a
     * dialog to the user informing them that they cannot use this app while it is suspended.
     *
     * <p>When an app is put into this state, the broadcast action
     * {@link Intent#ACTION_MY_PACKAGE_SUSPENDED} will be delivered to any of its broadcast
     * receivers that included this action in their intent-filters, <em>including manifest
     * receivers.</em> Similarly, a broadcast action {@link Intent#ACTION_MY_PACKAGE_UNSUSPENDED}
     * is delivered when a previously suspended app is taken out of this state. Apps are expected to
     * use these to gracefully deal with transitions to and from this state.
     *
     * @return {@code true} if the calling package has been suspended, {@code false} otherwise.
     *
     * @see #getSuspendedPackageAppExtras()
     * @see Intent#ACTION_MY_PACKAGE_SUSPENDED
     * @see Intent#ACTION_MY_PACKAGE_UNSUSPENDED
     */
    public boolean isPackageSuspended() {
        throw new UnsupportedOperationException("isPackageSuspended not implemented");
    }

    /**
     * Returns a {@link Bundle} of extras that was meant to be sent to the calling app when it was
     * suspended. An app with the permission {@code android.permission.SUSPEND_APPS} can supply this
     * to the system at the time of suspending an app.
     *
     * <p>This is the same {@link Bundle} that is sent along with the broadcast
     * {@link Intent#ACTION_MY_PACKAGE_SUSPENDED}, whenever the app is suspended. The contents of
     * this {@link Bundle} are a contract between the suspended app and the suspending app.
     *
     * <p>Note: These extras are optional, so if no extras were supplied to the system, this method
     * will return {@code null}, even when the calling app has been suspended.
     *
     * @return A {@link Bundle} containing the extras for the app, or {@code null} if the
     * package is not currently suspended.
     *
     * @see #isPackageSuspended()
     * @see Intent#ACTION_MY_PACKAGE_UNSUSPENDED
     * @see Intent#ACTION_MY_PACKAGE_SUSPENDED
     * @see Intent#EXTRA_SUSPENDED_PACKAGE_EXTRAS
     */
    public @Nullable Bundle getSuspendedPackageAppExtras() {
        throw new UnsupportedOperationException("getSuspendedPackageAppExtras not implemented");
    }

    /**
     * Provide a hint of what the {@link ApplicationInfo#category} value should
     * be for the given package.
     * <p>
     * This hint can only be set by the app which installed this package, as
     * determined by {@link #getInstallerPackageName(String)}.
     *
     * @param packageName the package to change the category hint for.
     * @param categoryHint the category hint to set.
     */
    @SuppressWarnings("HiddenAbstractMethod")
    public abstract void setApplicationCategoryHint(@NonNull String packageName,
            @ApplicationInfo.Category int categoryHint);

    /** {@hide} */
    public static boolean isMoveStatusFinished(int status) {
        return (status < 0 || status > 100);
    }

    /** {@hide} */
    public static abstract class MoveCallback {
        public void onCreated(int moveId, Bundle extras) {}
        public abstract void onStatusChanged(int moveId, int status, long estMillis);
    }

    /** {@hide} */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    public abstract int getMoveStatus(int moveId);

    /** {@hide} */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    public abstract void registerMoveCallback(@NonNull MoveCallback callback,
            @NonNull Handler handler);
    /** {@hide} */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    public abstract void unregisterMoveCallback(@NonNull MoveCallback callback);

    /** {@hide} */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public abstract int movePackage(@NonNull String packageName, @NonNull VolumeInfo vol);
    /** {@hide} */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public abstract @Nullable VolumeInfo getPackageCurrentVolume(@NonNull ApplicationInfo app);
    /** {@hide} */
    @SuppressWarnings("HiddenAbstractMethod")
    @NonNull
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public abstract List<VolumeInfo> getPackageCandidateVolumes(
            @NonNull ApplicationInfo app);

    /** {@hide} */
    @SuppressWarnings("HiddenAbstractMethod")
    public abstract int movePrimaryStorage(@NonNull VolumeInfo vol);
    /** {@hide} */
    @SuppressWarnings("HiddenAbstractMethod")
    public abstract @Nullable VolumeInfo getPrimaryStorageCurrentVolume();
    /** {@hide} */
    @SuppressWarnings("HiddenAbstractMethod")
    public abstract @NonNull List<VolumeInfo> getPrimaryStorageCandidateVolumes();

    /**
     * Returns the device identity that verifiers can use to associate their scheme to a particular
     * device. This should not be used by anything other than a package verifier.
     *
     * @return identity that uniquely identifies current device
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @NonNull
    public abstract VerifierDeviceIdentity getVerifierDeviceIdentity();

    /**
     * Returns true if the device is upgrading, such as first boot after OTA.
     *
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    public abstract boolean isUpgrade();

    /**
     * Returns true if the device is upgrading, such as first boot after OTA.
     */
    public boolean isDeviceUpgrading() {
        return false;
    }

    /**
     * Return interface that offers the ability to install, upgrade, and remove
     * applications on the device.
     */
    public abstract @NonNull PackageInstaller getPackageInstaller();

    /**
     * Adds a {@code CrossProfileIntentFilter}. After calling this method all
     * intents sent from the user with id sourceUserId can also be be resolved
     * by activities in the user with id targetUserId if they match the
     * specified intent filter.
     *
     * @param filter The {@link IntentFilter} the intent has to match
     * @param sourceUserId The source user id.
     * @param targetUserId The target user id.
     * @param flags The possible values are {@link #SKIP_CURRENT_PROFILE} and
     *            {@link #ONLY_IF_NO_MATCH_FOUND}.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    public abstract void addCrossProfileIntentFilter(@NonNull IntentFilter filter,
            @UserIdInt int sourceUserId, @UserIdInt int targetUserId, int flags);

    /**
     * Clearing {@code CrossProfileIntentFilter}s which have the specified user
     * as their source, and have been set by the app calling this method.
     *
     * @param sourceUserId The source user id.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    public abstract void clearCrossProfileIntentFilters(@UserIdInt int sourceUserId);

    /**
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @NonNull
    @UnsupportedAppUsage
    public abstract Drawable loadItemIcon(@NonNull PackageItemInfo itemInfo,
            @Nullable ApplicationInfo appInfo);

    /**
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @NonNull
    @UnsupportedAppUsage
    public abstract Drawable loadUnbadgedItemIcon(@NonNull PackageItemInfo itemInfo,
            @Nullable ApplicationInfo appInfo);

    /** {@hide} */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    public abstract boolean isPackageAvailable(@NonNull String packageName);

    /** {@hide} */
    @NonNull
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static String installStatusToString(int status, @Nullable String msg) {
        final String str = installStatusToString(status);
        if (msg != null) {
            return str + ": " + msg;
        } else {
            return str;
        }
    }

    /** {@hide} */
    @NonNull
    @UnsupportedAppUsage
    public static String installStatusToString(int status) {
        switch (status) {
            case INSTALL_SUCCEEDED: return "INSTALL_SUCCEEDED";
            case INSTALL_FAILED_ALREADY_EXISTS: return "INSTALL_FAILED_ALREADY_EXISTS";
            case INSTALL_FAILED_INVALID_APK: return "INSTALL_FAILED_INVALID_APK";
            case INSTALL_FAILED_INVALID_URI: return "INSTALL_FAILED_INVALID_URI";
            case INSTALL_FAILED_INSUFFICIENT_STORAGE: return "INSTALL_FAILED_INSUFFICIENT_STORAGE";
            case INSTALL_FAILED_DUPLICATE_PACKAGE: return "INSTALL_FAILED_DUPLICATE_PACKAGE";
            case INSTALL_FAILED_NO_SHARED_USER: return "INSTALL_FAILED_NO_SHARED_USER";
            case INSTALL_FAILED_UPDATE_INCOMPATIBLE: return "INSTALL_FAILED_UPDATE_INCOMPATIBLE";
            case INSTALL_FAILED_SHARED_USER_INCOMPATIBLE: return "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE";
            case INSTALL_FAILED_MISSING_SHARED_LIBRARY: return "INSTALL_FAILED_MISSING_SHARED_LIBRARY";
            case INSTALL_FAILED_REPLACE_COULDNT_DELETE: return "INSTALL_FAILED_REPLACE_COULDNT_DELETE";
            case INSTALL_FAILED_DEXOPT: return "INSTALL_FAILED_DEXOPT";
            case INSTALL_FAILED_OLDER_SDK: return "INSTALL_FAILED_OLDER_SDK";
            case INSTALL_FAILED_CONFLICTING_PROVIDER: return "INSTALL_FAILED_CONFLICTING_PROVIDER";
            case INSTALL_FAILED_NEWER_SDK: return "INSTALL_FAILED_NEWER_SDK";
            case INSTALL_FAILED_TEST_ONLY: return "INSTALL_FAILED_TEST_ONLY";
            case INSTALL_FAILED_CPU_ABI_INCOMPATIBLE: return "INSTALL_FAILED_CPU_ABI_INCOMPATIBLE";
            case INSTALL_FAILED_MISSING_FEATURE: return "INSTALL_FAILED_MISSING_FEATURE";
            case INSTALL_FAILED_CONTAINER_ERROR: return "INSTALL_FAILED_CONTAINER_ERROR";
            case INSTALL_FAILED_INVALID_INSTALL_LOCATION: return "INSTALL_FAILED_INVALID_INSTALL_LOCATION";
            case INSTALL_FAILED_MEDIA_UNAVAILABLE: return "INSTALL_FAILED_MEDIA_UNAVAILABLE";
            case INSTALL_FAILED_VERIFICATION_TIMEOUT: return "INSTALL_FAILED_VERIFICATION_TIMEOUT";
            case INSTALL_FAILED_VERIFICATION_FAILURE: return "INSTALL_FAILED_VERIFICATION_FAILURE";
            case INSTALL_FAILED_PACKAGE_CHANGED: return "INSTALL_FAILED_PACKAGE_CHANGED";
            case INSTALL_FAILED_UID_CHANGED: return "INSTALL_FAILED_UID_CHANGED";
            case INSTALL_FAILED_VERSION_DOWNGRADE: return "INSTALL_FAILED_VERSION_DOWNGRADE";
            case INSTALL_PARSE_FAILED_NOT_APK: return "INSTALL_PARSE_FAILED_NOT_APK";
            case INSTALL_PARSE_FAILED_BAD_MANIFEST: return "INSTALL_PARSE_FAILED_BAD_MANIFEST";
            case INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION: return "INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION";
            case INSTALL_PARSE_FAILED_NO_CERTIFICATES: return "INSTALL_PARSE_FAILED_NO_CERTIFICATES";
            case INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES: return "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES";
            case INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING: return "INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING";
            case INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME: return "INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME";
            case INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID: return "INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID";
            case INSTALL_PARSE_FAILED_MANIFEST_MALFORMED: return "INSTALL_PARSE_FAILED_MANIFEST_MALFORMED";
            case INSTALL_PARSE_FAILED_MANIFEST_EMPTY: return "INSTALL_PARSE_FAILED_MANIFEST_EMPTY";
            case INSTALL_FAILED_INTERNAL_ERROR: return "INSTALL_FAILED_INTERNAL_ERROR";
            case INSTALL_FAILED_USER_RESTRICTED: return "INSTALL_FAILED_USER_RESTRICTED";
            case INSTALL_FAILED_DUPLICATE_PERMISSION: return "INSTALL_FAILED_DUPLICATE_PERMISSION";
            case INSTALL_FAILED_NO_MATCHING_ABIS: return "INSTALL_FAILED_NO_MATCHING_ABIS";
            case INSTALL_FAILED_ABORTED: return "INSTALL_FAILED_ABORTED";
            case INSTALL_FAILED_BAD_DEX_METADATA: return "INSTALL_FAILED_BAD_DEX_METADATA";
            case INSTALL_FAILED_MISSING_SPLIT: return "INSTALL_FAILED_MISSING_SPLIT";
            case INSTALL_FAILED_BAD_SIGNATURE: return "INSTALL_FAILED_BAD_SIGNATURE";
            case INSTALL_FAILED_WRONG_INSTALLED_VERSION: return "INSTALL_FAILED_WRONG_INSTALLED_VERSION";
            case INSTALL_FAILED_PROCESS_NOT_DEFINED: return "INSTALL_FAILED_PROCESS_NOT_DEFINED";
            case INSTALL_FAILED_SESSION_INVALID: return "INSTALL_FAILED_SESSION_INVALID";
            default: return Integer.toString(status);
        }
    }

    /** {@hide} */
    public static int installStatusToPublicStatus(int status) {
        switch (status) {
            case INSTALL_SUCCEEDED: return PackageInstaller.STATUS_SUCCESS;
            case INSTALL_FAILED_ALREADY_EXISTS: return PackageInstaller.STATUS_FAILURE_CONFLICT;
            case INSTALL_FAILED_INVALID_APK: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_FAILED_INVALID_URI: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_FAILED_INSUFFICIENT_STORAGE: return PackageInstaller.STATUS_FAILURE_STORAGE;
            case INSTALL_FAILED_DUPLICATE_PACKAGE: return PackageInstaller.STATUS_FAILURE_CONFLICT;
            case INSTALL_FAILED_NO_SHARED_USER: return PackageInstaller.STATUS_FAILURE_CONFLICT;
            case INSTALL_FAILED_UPDATE_INCOMPATIBLE: return PackageInstaller.STATUS_FAILURE_CONFLICT;
            case INSTALL_FAILED_SHARED_USER_INCOMPATIBLE: return PackageInstaller.STATUS_FAILURE_CONFLICT;
            case INSTALL_FAILED_MISSING_SHARED_LIBRARY: return PackageInstaller.STATUS_FAILURE_INCOMPATIBLE;
            case INSTALL_FAILED_REPLACE_COULDNT_DELETE: return PackageInstaller.STATUS_FAILURE_CONFLICT;
            case INSTALL_FAILED_DEXOPT: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_FAILED_OLDER_SDK: return PackageInstaller.STATUS_FAILURE_INCOMPATIBLE;
            case INSTALL_FAILED_CONFLICTING_PROVIDER: return PackageInstaller.STATUS_FAILURE_CONFLICT;
            case INSTALL_FAILED_NEWER_SDK: return PackageInstaller.STATUS_FAILURE_INCOMPATIBLE;
            case INSTALL_FAILED_TEST_ONLY: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_FAILED_CPU_ABI_INCOMPATIBLE: return PackageInstaller.STATUS_FAILURE_INCOMPATIBLE;
            case INSTALL_FAILED_MISSING_FEATURE: return PackageInstaller.STATUS_FAILURE_INCOMPATIBLE;
            case INSTALL_FAILED_CONTAINER_ERROR: return PackageInstaller.STATUS_FAILURE_STORAGE;
            case INSTALL_FAILED_INVALID_INSTALL_LOCATION: return PackageInstaller.STATUS_FAILURE_STORAGE;
            case INSTALL_FAILED_MEDIA_UNAVAILABLE: return PackageInstaller.STATUS_FAILURE_STORAGE;
            case INSTALL_FAILED_VERIFICATION_TIMEOUT: return PackageInstaller.STATUS_FAILURE_ABORTED;
            case INSTALL_FAILED_VERIFICATION_FAILURE: return PackageInstaller.STATUS_FAILURE_ABORTED;
            case INSTALL_FAILED_PACKAGE_CHANGED: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_FAILED_UID_CHANGED: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_FAILED_VERSION_DOWNGRADE: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_FAILED_PERMISSION_MODEL_DOWNGRADE: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_NOT_APK: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_BAD_MANIFEST: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_NO_CERTIFICATES: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_MANIFEST_MALFORMED: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_MANIFEST_EMPTY: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_FAILED_BAD_DEX_METADATA: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_FAILED_BAD_SIGNATURE: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_FAILED_INTERNAL_ERROR: return PackageInstaller.STATUS_FAILURE;
            case INSTALL_FAILED_USER_RESTRICTED: return PackageInstaller.STATUS_FAILURE_INCOMPATIBLE;
            case INSTALL_FAILED_DUPLICATE_PERMISSION: return PackageInstaller.STATUS_FAILURE_CONFLICT;
            case INSTALL_FAILED_NO_MATCHING_ABIS: return PackageInstaller.STATUS_FAILURE_INCOMPATIBLE;
            case INSTALL_FAILED_ABORTED: return PackageInstaller.STATUS_FAILURE_ABORTED;
            case INSTALL_FAILED_MISSING_SPLIT: return PackageInstaller.STATUS_FAILURE_INCOMPATIBLE;
            default: return PackageInstaller.STATUS_FAILURE;
        }
    }

    /** {@hide} */
    @NonNull
    public static String deleteStatusToString(int status, @Nullable String msg) {
        final String str = deleteStatusToString(status);
        if (msg != null) {
            return str + ": " + msg;
        } else {
            return str;
        }
    }

    /** {@hide} */
    @NonNull
    @UnsupportedAppUsage
    public static String deleteStatusToString(int status) {
        switch (status) {
            case DELETE_SUCCEEDED: return "DELETE_SUCCEEDED";
            case DELETE_FAILED_INTERNAL_ERROR: return "DELETE_FAILED_INTERNAL_ERROR";
            case DELETE_FAILED_DEVICE_POLICY_MANAGER: return "DELETE_FAILED_DEVICE_POLICY_MANAGER";
            case DELETE_FAILED_USER_RESTRICTED: return "DELETE_FAILED_USER_RESTRICTED";
            case DELETE_FAILED_OWNER_BLOCKED: return "DELETE_FAILED_OWNER_BLOCKED";
            case DELETE_FAILED_ABORTED: return "DELETE_FAILED_ABORTED";
            case DELETE_FAILED_USED_SHARED_LIBRARY: return "DELETE_FAILED_USED_SHARED_LIBRARY";
            case DELETE_FAILED_APP_PINNED: return "DELETE_FAILED_APP_PINNED";
            default: return Integer.toString(status);
        }
    }

    /** {@hide} */
    public static int deleteStatusToPublicStatus(int status) {
        switch (status) {
            case DELETE_SUCCEEDED: return PackageInstaller.STATUS_SUCCESS;
            case DELETE_FAILED_INTERNAL_ERROR: return PackageInstaller.STATUS_FAILURE;
            case DELETE_FAILED_DEVICE_POLICY_MANAGER: return PackageInstaller.STATUS_FAILURE_BLOCKED;
            case DELETE_FAILED_USER_RESTRICTED: return PackageInstaller.STATUS_FAILURE_BLOCKED;
            case DELETE_FAILED_OWNER_BLOCKED: return PackageInstaller.STATUS_FAILURE_BLOCKED;
            case DELETE_FAILED_ABORTED: return PackageInstaller.STATUS_FAILURE_ABORTED;
            case DELETE_FAILED_USED_SHARED_LIBRARY: return PackageInstaller.STATUS_FAILURE_CONFLICT;
            case DELETE_FAILED_APP_PINNED: return PackageInstaller.STATUS_FAILURE_BLOCKED;
            default: return PackageInstaller.STATUS_FAILURE;
        }
    }

    /** {@hide} */
    @NonNull
    public static String permissionFlagToString(int flag) {
        switch (flag) {
            case FLAG_PERMISSION_GRANTED_BY_DEFAULT: return "GRANTED_BY_DEFAULT";
            case FLAG_PERMISSION_POLICY_FIXED: return "POLICY_FIXED";
            case FLAG_PERMISSION_SYSTEM_FIXED: return "SYSTEM_FIXED";
            case FLAG_PERMISSION_USER_SET: return "USER_SET";
            case FLAG_PERMISSION_USER_FIXED: return "USER_FIXED";
            case FLAG_PERMISSION_REVIEW_REQUIRED: return "REVIEW_REQUIRED";
            case FLAG_PERMISSION_REVOKE_WHEN_REQUESTED: return "REVOKE_WHEN_REQUESTED";
            case FLAG_PERMISSION_USER_SENSITIVE_WHEN_GRANTED: return "USER_SENSITIVE_WHEN_GRANTED";
            case FLAG_PERMISSION_USER_SENSITIVE_WHEN_DENIED: return "USER_SENSITIVE_WHEN_DENIED";
            case FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT: return "RESTRICTION_INSTALLER_EXEMPT";
            case FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT: return "RESTRICTION_SYSTEM_EXEMPT";
            case FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT: return "RESTRICTION_UPGRADE_EXEMPT";
            case FLAG_PERMISSION_APPLY_RESTRICTION: return "APPLY_RESTRICTION";
            case FLAG_PERMISSION_GRANTED_BY_ROLE: return "GRANTED_BY_ROLE";
            case FLAG_PERMISSION_REVOKED_COMPAT: return "REVOKED_COMPAT";
            case FLAG_PERMISSION_ONE_TIME: return "ONE_TIME";
            case FLAG_PERMISSION_AUTO_REVOKED: return "AUTO_REVOKED";
            default: return Integer.toString(flag);
        }
    }

    /** {@hide} */
    public static class LegacyPackageDeleteObserver extends PackageDeleteObserver {
        private final IPackageDeleteObserver mLegacy;

        public LegacyPackageDeleteObserver(IPackageDeleteObserver legacy) {
            mLegacy = legacy;
        }

        @Override
        public void onPackageDeleted(String basePackageName, int returnCode, String msg) {
            if (mLegacy == null) return;
            try {
                mLegacy.packageDeleted(basePackageName, returnCode);
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Return the install reason that was recorded when a package was first
     * installed for a specific user. Requesting the install reason for another
     * user will require the permission INTERACT_ACROSS_USERS_FULL.
     *
     * @param packageName The package for which to retrieve the install reason
     * @param user The user for whom to retrieve the install reason
     * @return The install reason. If the package is not installed for the given
     *         user, {@code INSTALL_REASON_UNKNOWN} is returned.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @TestApi
    @InstallReason
    public abstract int getInstallReason(@NonNull String packageName, @NonNull UserHandle user);

    /**
     * Checks whether the calling package is allowed to request package installs through package
     * installer. Apps are encouraged to call this API before launching the package installer via
     * intent {@link android.content.Intent#ACTION_INSTALL_PACKAGE}. Starting from Android O, the
     * user can explicitly choose what external sources they trust to install apps on the device.
     * If this API returns false, the install request will be blocked by the package installer and
     * a dialog will be shown to the user with an option to launch settings to change their
     * preference. An application must target Android O or higher and declare permission
     * {@link android.Manifest.permission#REQUEST_INSTALL_PACKAGES} in order to use this API.
     *
     * @return true if the calling package is trusted by the user to request install packages on
     * the device, false otherwise.
     * @see android.content.Intent#ACTION_INSTALL_PACKAGE
     * @see android.provider.Settings#ACTION_MANAGE_UNKNOWN_APP_SOURCES
     */
    public abstract boolean canRequestPackageInstalls();

    /**
     * Return the {@link ComponentName} of the activity providing Settings for the Instant App
     * resolver.
     *
     * @see {@link android.content.Intent#ACTION_INSTANT_APP_RESOLVER_SETTINGS}
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @Nullable
    @SystemApi
    public abstract ComponentName getInstantAppResolverSettingsComponent();

    /**
     * Return the {@link ComponentName} of the activity responsible for installing instant
     * applications.
     *
     * @see {@link android.content.Intent#ACTION_INSTALL_INSTANT_APP_PACKAGE}
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @Nullable
    @SystemApi
    public abstract ComponentName getInstantAppInstallerComponent();

    /**
     * Return the Android Id for a given Instant App.
     *
     * @see {@link android.provider.Settings.Secure#ANDROID_ID}
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @Nullable
    public abstract String getInstantAppAndroidId(@NonNull String packageName,
            @NonNull UserHandle user);

    /**
     * Callback use to notify the callers of module registration that the operation
     * has finished.
     *
     * @hide
     */
    @SystemApi
    public static abstract class DexModuleRegisterCallback {
        public abstract void onDexModuleRegistered(String dexModulePath, boolean success,
                String message);
    }

    /**
     * Register an application dex module with the package manager.
     * The package manager will keep track of the given module for future optimizations.
     *
     * Dex module optimizations will disable the classpath checking at runtime. The client bares
     * the responsibility to ensure that the static assumptions on classes in the optimized code
     * hold at runtime (e.g. there's no duplicate classes in the classpath).
     *
     * Note that the package manager already keeps track of dex modules loaded with
     * {@link dalvik.system.DexClassLoader} and {@link dalvik.system.PathClassLoader}.
     * This can be called for an eager registration.
     *
     * The call might take a while and the results will be posted on the main thread, using
     * the given callback.
     *
     * If the module is intended to be shared with other apps, make sure that the file
     * permissions allow for it.
     * If at registration time the permissions allow for others to read it, the module would
     * be marked as a shared module which might undergo a different optimization strategy.
     * (usually shared modules will generated larger optimizations artifacts,
     * taking more disk space).
     *
     * @param dexModulePath the absolute path of the dex module.
     * @param callback if not null, {@link DexModuleRegisterCallback#onDexModuleRegistered} will
     *                 be called once the registration finishes.
     *
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    public abstract void registerDexModule(@NonNull String dexModulePath,
            @Nullable DexModuleRegisterCallback callback);

    /**
     * Returns the {@link ArtManager} associated with this package manager.
     *
     * @hide
     */
    @SystemApi
    public @NonNull ArtManager getArtManager() {
        throw new UnsupportedOperationException("getArtManager not implemented in subclass");
    }

    /**
     * Sets or clears the harmful app warning details for the given app.
     *
     * When set, any attempt to launch an activity in this package will be intercepted and a
     * warning dialog will be shown to the user instead, with the given warning. The user
     * will have the option to proceed with the activity launch, or to uninstall the application.
     *
     * @param packageName The full name of the package to warn on.
     * @param warning A warning string to display to the user describing the threat posed by the
     *                application, or null to clear the warning.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.SET_HARMFUL_APP_WARNINGS)
    @SystemApi
    public void setHarmfulAppWarning(@NonNull String packageName, @Nullable CharSequence warning) {
        throw new UnsupportedOperationException("setHarmfulAppWarning not implemented in subclass");
    }

    /**
     * Returns the harmful app warning string for the given app, or null if there is none set.
     *
     * @param packageName The full name of the desired package.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.SET_HARMFUL_APP_WARNINGS)
    @Nullable
    @SystemApi
    public CharSequence getHarmfulAppWarning(@NonNull String packageName) {
        throw new UnsupportedOperationException("getHarmfulAppWarning not implemented in subclass");
    }

    /** @hide */
    @IntDef(prefix = { "CERT_INPUT_" }, value = {
            CERT_INPUT_RAW_X509,
            CERT_INPUT_SHA256
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CertificateInputType {}

    /**
     * Certificate input bytes: the input bytes represent an encoded X.509 Certificate which could
     * be generated using an {@code CertificateFactory}
     */
    public static final int CERT_INPUT_RAW_X509 = 0;

    /**
     * Certificate input bytes: the input bytes represent the SHA256 output of an encoded X.509
     * Certificate.
     */
    public static final int CERT_INPUT_SHA256 = 1;

    /**
     * Searches the set of signing certificates by which the given package has proven to have been
     * signed.  This should be used instead of {@code getPackageInfo} with {@code GET_SIGNATURES}
     * since it takes into account the possibility of signing certificate rotation, except in the
     * case of packages that are signed by multiple certificates, for which signing certificate
     * rotation is not supported.  This method is analogous to using {@code getPackageInfo} with
     * {@code GET_SIGNING_CERTIFICATES} and then searching through the resulting {@code
     * signingInfo} field to see if the desired certificate is present.
     *
     * @param packageName package whose signing certificates to check
     * @param certificate signing certificate for which to search
     * @param type representation of the {@code certificate}
     * @return true if this package was or is signed by exactly the certificate {@code certificate}
     */
    public boolean hasSigningCertificate(@NonNull String packageName, @NonNull byte[] certificate,
            @CertificateInputType int type) {
        throw new UnsupportedOperationException(
                "hasSigningCertificate not implemented in subclass");
    }

    /**
     * Searches the set of signing certificates by which the package(s) for the given uid has proven
     * to have been signed.  For multiple packages sharing the same uid, this will return the
     * signing certificates found in the signing history of the "newest" package, where "newest"
     * indicates the package with the newest signing certificate in the shared uid group.  This
     * method should be used instead of {@code getPackageInfo} with {@code GET_SIGNATURES}
     * since it takes into account the possibility of signing certificate rotation, except in the
     * case of packages that are signed by multiple certificates, for which signing certificate
     * rotation is not supported. This method is analogous to using {@code getPackagesForUid}
     * followed by {@code getPackageInfo} with {@code GET_SIGNING_CERTIFICATES}, selecting the
     * {@code PackageInfo} of the newest-signed bpackage , and finally searching through the
     * resulting {@code signingInfo} field to see if the desired certificate is there.
     *
     * @param uid uid whose signing certificates to check
     * @param certificate signing certificate for which to search
     * @param type representation of the {@code certificate}
     * @return true if this package was or is signed by exactly the certificate {@code certificate}
     */
    public boolean hasSigningCertificate(
            int uid, @NonNull byte[] certificate, @CertificateInputType int type) {
        throw new UnsupportedOperationException(
                "hasSigningCertificate not implemented in subclass");
    }

    /**
     * Trust any Installer to provide checksums for the package.
     * @see #requestChecksums
     */
    public static final @NonNull List<Certificate> TRUST_ALL = Collections.singletonList(null);

    /**
     * Don't trust any Installer to provide checksums for the package.
     * This effectively disables optimized Installer-enforced checksums.
     * @see #requestChecksums
     */
    public static final @NonNull List<Certificate> TRUST_NONE = Collections.singletonList(null);

    /** Listener that gets notified when checksums are available. */
    @FunctionalInterface
    public interface OnChecksumsReadyListener {
        /**
         * Called when the checksums are available.
         *
         * @param checksums array of checksums.
         */
        void onChecksumsReady(@NonNull List<ApkChecksum> checksums);
    }

    /**
     * Requests the checksums for APKs within a package.
     * The checksums will be returned asynchronously via onChecksumsReadyListener.
     *
     * By default returns all readily available checksums:
     * - enforced by platform,
     * - enforced by installer.
     * If caller needs a specific checksum kind, they can specify it as required.
     *
     * <b>Caution: Android can not verify installer-provided checksums. Make sure you specify
     * trusted installers.</b>
     *
     * @param packageName whose checksums to return.
     * @param includeSplits whether to include checksums for non-base splits.
     * @param required explicitly request the checksum types. May incur significant
     *                 CPU/memory/disk usage.
     * @param trustedInstallers for checksums enforced by installer, which installers are to be
     *                          trusted.
     *                          {@link #TRUST_ALL} will return checksums from any installer,
     *                          {@link #TRUST_NONE} disables optimized installer-enforced checksums,
     *                          otherwise the list has to be non-empty list of certificates.
     * @param onChecksumsReadyListener called once when the results are available.
     * @throws CertificateEncodingException if an encoding error occurs for trustedInstallers.
     * @throws IllegalArgumentException if the list of trusted installer certificates is empty.
     * @throws NameNotFoundException if a package with the given name cannot be found on the system.
     */
    public void requestChecksums(@NonNull String packageName, boolean includeSplits,
            @Checksum.TypeMask int required, @NonNull List<Certificate> trustedInstallers,
            @NonNull OnChecksumsReadyListener onChecksumsReadyListener)
            throws CertificateEncodingException, NameNotFoundException {
        throw new UnsupportedOperationException("requestChecksums not implemented in subclass");
    }

    /**
     * @return the default text classifier package name, or null if there's none.
     *
     * @hide
     */
    @Nullable
    @TestApi
    public String getDefaultTextClassifierPackageName() {
        throw new UnsupportedOperationException(
                "getDefaultTextClassifierPackageName not implemented in subclass");
    }

    /**
     * @return the system defined text classifier package names, or null if there's none.
     *
     * @hide
     */
    @Nullable
    @TestApi
    public String getSystemTextClassifierPackageName() {
        throw new UnsupportedOperationException(
                "getSystemTextClassifierPackageName not implemented in subclass");
    }

    /**
     * @return  attention service package name, or null if there's none.
     *
     * @hide
     */
    public String getAttentionServicePackageName() {
        throw new UnsupportedOperationException(
                "getAttentionServicePackageName not implemented in subclass");
    }

    /**
     * @return rotation resolver service's package name, or null if there's none.
     *
     * @hide
     */
    public String getRotationResolverPackageName() {
        throw new UnsupportedOperationException(
                "getRotationResolverPackageName not implemented in subclass");
    }

    /**
     * @return the wellbeing app package name, or null if it's not defined by the OEM.
     *
     * @hide
     */
    @Nullable
    @TestApi
    public String getWellbeingPackageName() {
        throw new UnsupportedOperationException(
                "getWellbeingPackageName not implemented in subclass");
    }

    /**
     * @return the system defined app predictor package name, or null if there's none.
     *
     * @hide
     */
    @Nullable
    public String getAppPredictionServicePackageName() {
        throw new UnsupportedOperationException(
            "getAppPredictionServicePackageName not implemented in subclass");
    }

    /**
     * @return the system defined content capture service package name, or null if there's none.
     *
     * @hide
     */
    @Nullable
    public String getSystemCaptionsServicePackageName() {
        throw new UnsupportedOperationException(
                "getSystemCaptionsServicePackageName not implemented in subclass");
    }

    /**
     * @return the system defined setup wizard package name, or null if there's none.
     *
     * @hide
     */
    @Nullable
    public String getSetupWizardPackageName() {
        throw new UnsupportedOperationException(
                "getSetupWizardPackageName not implemented in subclass");
    }

    /**
     * @return the system defined content capture package name, or null if there's none.
     *
     * @hide
     */
    @TestApi
    @Nullable
    public String getContentCaptureServicePackageName() {
        throw new UnsupportedOperationException(
                "getContentCaptureServicePackageName not implemented in subclass");
    }

    /**
     * @return the incident report approver app package name, or null if it's not defined
     * by the OEM.
     *
     * @hide
     */
    @SystemApi
    @Nullable
    public String getIncidentReportApproverPackageName() {
        throw new UnsupportedOperationException(
                "getIncidentReportApproverPackageName not implemented in subclass");
    }

    /**
     * @return whether a given package's state is protected, e.g. package cannot be disabled,
     *         suspended, hidden or force stopped.
     *
     * @hide
     */
    public boolean isPackageStateProtected(@NonNull String packageName, @UserIdInt int userId) {
        throw new UnsupportedOperationException(
            "isPackageStateProtected not implemented in subclass");
    }

    /**
     * Notify to the rest of the system that a new device configuration has
     * been prepared and that it is time to refresh caches.
     *
     * @see android.content.Intent#ACTION_DEVICE_CUSTOMIZATION_READY
     *
     * @hide
     */
    @SystemApi
    public void sendDeviceCustomizationReadyBroadcast() {
        throw new UnsupportedOperationException(
            "sendDeviceCustomizationReadyBroadcast not implemented in subclass");
    }

    /**
     * <p>
     * <strong>Note: </strong>In retrospect it would have been preferred to use
     * more inclusive terminology when naming this API. Similar APIs added will
     * refrain from using the term "whitelist".
     * </p>
     *
     * @return whether this package is whitelisted from having its runtime permission be
     *         auto-revoked if unused for an extended period of time.
     */
    public boolean isAutoRevokeWhitelisted() {
        throw new UnsupportedOperationException(
                "isAutoRevokeWhitelisted not implemented in subclass");
    }

    /**
     * Returns if the provided drawable represents the default activity icon provided by the system.
     *
     * PackageManager silently returns a default application icon for any package/activity if the
     * app itself does not define one or if the system encountered any error when loading the icon.
     *
     * Developers can use this to check implement app specific logic around retrying or caching.
     *
     * @return true if the drawable represents the default activity icon, false otherwise
     * @see #getDefaultActivityIcon()
     * @see #getActivityIcon
     * @see LauncherActivityInfo#getIcon(int)
     */
    public boolean isDefaultApplicationIcon(@NonNull Drawable drawable) {
        int resId = drawable instanceof AdaptiveIconDrawable
                ? ((AdaptiveIconDrawable) drawable).getSourceDrawableResId() : Resources.ID_NULL;
        return resId == com.android.internal.R.drawable.sym_def_app_icon
                || resId == com.android.internal.R.drawable.sym_app_on_sd_unavailable_icon;
    }

    /**
     * Sets MIME group's MIME types.
     *
     * Libraries should use a reverse-DNS prefix followed by a ':' character and library-specific
     * group name to avoid namespace collisions, e.g. "com.example:myFeature".
     *
     * @param mimeGroup MIME group to modify.
     * @param mimeTypes new MIME types contained by MIME group.
     * @throws IllegalArgumentException if the MIME group was not declared in the manifest.
     */
    public void setMimeGroup(@NonNull String mimeGroup, @NonNull Set<String> mimeTypes) {
        throw new UnsupportedOperationException(
                "setMimeGroup not implemented in subclass");
    }

    /**
     * Gets all MIME types contained by MIME group.
     *
     * Libraries should use a reverse-DNS prefix followed by a ':' character and library-specific
     * group name to avoid namespace collisions, e.g. "com.example:myFeature".
     *
     * @param mimeGroup MIME group to retrieve.
     * @return MIME types contained by the MIME group.
     * @throws IllegalArgumentException if the MIME group was not declared in the manifest.
     */
    @NonNull
    public Set<String> getMimeGroup(@NonNull String mimeGroup) {
        throw new UnsupportedOperationException(
                "getMimeGroup not implemented in subclass");
    }

    /**
     * Returns the property defined in the given package's &lt;appliction&gt; tag.
     *
     * @throws NameNotFoundException if either the given package is not installed or if the
     * given property is not defined within the &lt;application&gt; tag.
     */
    @NonNull
    public Property getProperty(@NonNull String propertyName, @NonNull String packageName)
            throws NameNotFoundException {
        throw new UnsupportedOperationException(
                "getProperty not implemented in subclass");
    }

    /**
     * Returns the property defined in the given component declaration.
     *
     * @throws NameNotFoundException if either the given component does not exist or if the
     * given property is not defined within the component declaration.
     */
    @NonNull
    public Property getProperty(@NonNull String propertyName, @NonNull ComponentName component)
            throws NameNotFoundException {
        throw new UnsupportedOperationException(
                "getProperty not implemented in subclass");
    }

    /**
     * Returns the property definition for all &lt;application&gt; tags.
     * <p>If the property is not defined with any &lt;application&gt; tag,
     * returns and empty list.
     */
    @NonNull
    public List<Property> queryApplicationProperty(@NonNull String propertyName) {
        throw new UnsupportedOperationException(
                "qeuryApplicationProperty not implemented in subclass");
    }

    /**
     * Returns the property definition for all &lt;activity&gt; and &lt;activity-alias&gt; tags.
     * <p>If the property is not defined with any &lt;activity&gt; and &lt;activity-alias&gt; tag,
     * returns and empty list.
     */
    @NonNull
    public List<Property> queryActivityProperty(@NonNull String propertyName) {
        throw new UnsupportedOperationException(
                "qeuryActivityProperty not implemented in subclass");
    }

    /**
     * Returns the property definition for all &lt;provider&gt; tags.
     * <p>If the property is not defined with any &lt;provider&gt; tag,
     * returns and empty list.
     */
    @NonNull
    public List<Property> queryProviderProperty(@NonNull String propertyName) {
        throw new UnsupportedOperationException(
                "qeuryProviderProperty not implemented in subclass");
    }

    /**
     * Returns the property definition for all &lt;receiver&gt; tags.
     * <p>If the property is not defined with any &lt;receiver&gt; tag,
     * returns and empty list.
     */
    @NonNull
    public List<Property> queryReceiverProperty(@NonNull String propertyName) {
        throw new UnsupportedOperationException(
                "qeuryReceiverProperty not implemented in subclass");
    }

    /**
     * Returns the property definition for all &lt;service&gt; tags.
     * <p>If the property is not defined with any &lt;service&gt; tag,
     * returns and empty list.
     */
    @NonNull
    public List<Property> queryServiceProperty(@NonNull String propertyName) {
        throw new UnsupportedOperationException(
                "qeuryServiceProperty not implemented in subclass");
    }

    /**
     * Returns {@code true} if the source package is able to query for details about the
     * target package. Applications that share details about other applications should
     * use this API to determine if those details should be withheld from callers that
     * do not otherwise have visibility of them.
     * <p>
     * Note: The caller must be able to query for details about the source and target
     * package. A {@link NameNotFoundException} is thrown if it isn't.
     *
     * @param sourcePackageName The source package that would receive details about the
     *                          target package.
     * @param targetPackageName The target package whose details would be shared with the
     *                          source package.
     * @return {@code true} if the source package is able to query for details about the
     * target package.
     * @throws NameNotFoundException if either a given package can not be found on the
     * system, or if the caller is not able to query for details about the source or
     * target package.
     */
    public boolean canPackageQuery(@NonNull String sourcePackageName,
            @NonNull String targetPackageName) throws NameNotFoundException {
        throw new UnsupportedOperationException(
                "canPackageQuery not implemented in subclass");
    }

    /**
     * Makes a package that provides an authority {@code visibleAuthority} become visible to the
     * application {@code recipientUid}.
     *
     * @throws SecurityException when called by a package other than the contacts provider
     * @hide
     */
    public void makeProviderVisible(int recipientUid, String visibleAuthority) {
        try {
            ActivityThread.getPackageManager().makeProviderVisible(recipientUid, visibleAuthority);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Makes the package associated with the uid {@code visibleUid} become visible to the
     * recipient application. The recipient application can receive the details about the
     * visible package if successful.
     * <p>
     * Read <a href="/training/basics/intents/package-visibility">package visibility</a> for more
     * information.
     *
     * @param recipientUid The uid of the application that is being given access to {@code
     *                     visibleUid}
     * @param visibleUid The uid of the application that is becoming accessible to {@code
     *                   recipientAppId}
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MAKE_UID_VISIBLE)
    @TestApi
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public void makeUidVisible(int recipientUid, int visibleUid) {
        throw new UnsupportedOperationException(
                "makeUidVisible not implemented in subclass");
    }

    // Some of the flags don't affect the query result, but let's be conservative and cache
    // each combination of flags separately.

    private static final class ApplicationInfoQuery {
        final String packageName;
        final long flags;
        final int userId;

        ApplicationInfoQuery(@Nullable String packageName, @ApplicationInfoFlagsBits long flags,
                int userId) {
            this.packageName = packageName;
            this.flags = flags;
            this.userId = userId;
        }

        @Override
        public String toString() {
            return String.format(
                    "ApplicationInfoQuery(packageName=\"%s\", flags=%s, userId=%s)",
                    packageName, flags, userId);
        }

        @Override
        public int hashCode() {
            int hash = Objects.hashCode(packageName);
            hash = hash * 13 + Objects.hashCode(flags);
            hash = hash * 13 + Objects.hashCode(userId);
            return hash;
        }

        @Override
        public boolean equals(@Nullable Object rval) {
            if (rval == null) {
                return false;
            }
            ApplicationInfoQuery other;
            try {
                other = (ApplicationInfoQuery) rval;
            } catch (ClassCastException ex) {
                return false;
            }
            return Objects.equals(packageName, other.packageName)
                    && flags == other.flags
                    && userId == other.userId;
        }
    }

    private static ApplicationInfo getApplicationInfoAsUserUncached(
            String packageName, @ApplicationInfoFlagsBits long flags, int userId) {
        try {
            return ActivityThread.getPackageManager()
                    .getApplicationInfo(packageName, flags, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static final PropertyInvalidatedCache<ApplicationInfoQuery, ApplicationInfo>
            sApplicationInfoCache =
            new PropertyInvalidatedCache<ApplicationInfoQuery, ApplicationInfo>(
                    16, PermissionManager.CACHE_KEY_PACKAGE_INFO,
                    "getApplicationInfo") {
                @Override
                public ApplicationInfo recompute(ApplicationInfoQuery query) {
                    return getApplicationInfoAsUserUncached(
                            query.packageName, query.flags, query.userId);
                }
                @Override
                public boolean resultEquals(ApplicationInfo cached, ApplicationInfo fetched) {
                    // Implementing this debug check for ApplicationInfo would require a
                    // complicated deep comparison, so just bypass it for now.
                    return true;
                }
            };

    /** @hide */
    public static ApplicationInfo getApplicationInfoAsUserCached(
            String packageName, @ApplicationInfoFlagsBits long flags, int userId) {
        return sApplicationInfoCache.query(
                new ApplicationInfoQuery(packageName, flags, userId));
    }

    /**
     * Make getApplicationInfoAsUser() bypass the cache in this process.
     *
     * @hide
     */
    public static void disableApplicationInfoCache() {
        sApplicationInfoCache.disableLocal();
    }

    private static final PropertyInvalidatedCache.AutoCorker sCacheAutoCorker =
            new PropertyInvalidatedCache.AutoCorker(PermissionManager.CACHE_KEY_PACKAGE_INFO);

    /**
     * Invalidate caches of package and permission information system-wide.
     *
     * @hide
     */
    public static void invalidatePackageInfoCache() {
        sCacheAutoCorker.autoCork();
    }

    // Some of the flags don't affect the query result, but let's be conservative and cache
    // each combination of flags separately.

    private static final class PackageInfoQuery {
        final String packageName;
        final long flags;
        final int userId;

        PackageInfoQuery(@Nullable String packageName, @PackageInfoFlagsBits long flags, int userId) {
            this.packageName = packageName;
            this.flags = flags;
            this.userId = userId;
        }

        @Override
        public String toString() {
            return String.format(
                    "PackageInfoQuery(packageName=\"%s\", flags=%s, userId=%s)",
                    packageName, flags, userId);
        }

        @Override
        public int hashCode() {
            int hash = Objects.hashCode(packageName);
            hash = hash * 13 + Objects.hashCode(flags);
            hash = hash * 13 + Objects.hashCode(userId);
            return hash;
        }

        @Override
        public boolean equals(@Nullable Object rval) {
            if (rval == null) {
                return false;
            }
            PackageInfoQuery other;
            try {
                other = (PackageInfoQuery) rval;
            } catch (ClassCastException ex) {
                return false;
            }
            return Objects.equals(packageName, other.packageName)
                    && flags == other.flags
                    && userId == other.userId;
        }
    }

    private static PackageInfo getPackageInfoAsUserUncached(
            String packageName, @PackageInfoFlagsBits long flags, int userId) {
        try {
            return ActivityThread.getPackageManager().getPackageInfo(packageName, flags, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static final PropertyInvalidatedCache<PackageInfoQuery, PackageInfo>
            sPackageInfoCache =
            new PropertyInvalidatedCache<PackageInfoQuery, PackageInfo>(
                    32, PermissionManager.CACHE_KEY_PACKAGE_INFO,
                    "getPackageInfo") {
                @Override
                public PackageInfo recompute(PackageInfoQuery query) {
                    return getPackageInfoAsUserUncached(
                            query.packageName, query.flags, query.userId);
                }
                @Override
                public boolean resultEquals(PackageInfo cached, PackageInfo fetched) {
                    // Implementing this debug check for PackageInfo would require a
                    // complicated deep comparison, so just bypass it for now.
                    return true;
                }
            };

    /** @hide */
    public static PackageInfo getPackageInfoAsUserCached(
            String packageName, @PackageInfoFlagsBits long flags, int userId) {
        return sPackageInfoCache.query(new PackageInfoQuery(packageName, flags, userId));
    }

    /**
     * Make getPackageInfoAsUser() bypass the cache in this process.
     * @hide
     */
    public static void disablePackageInfoCache() {
        sPackageInfoCache.disableLocal();
    }

    /**
     * Inhibit package info cache invalidations when correct.
     *
     * @hide */
    public static void corkPackageInfoCache() {
        PropertyInvalidatedCache.corkInvalidations(PermissionManager.CACHE_KEY_PACKAGE_INFO);
    }

    /**
     * Enable package info cache invalidations.
     *
     * @hide */
    public static void uncorkPackageInfoCache() {
        PropertyInvalidatedCache.uncorkInvalidations(PermissionManager.CACHE_KEY_PACKAGE_INFO);
    }

    /**
     * Returns the token to be used by the subsequent calls to holdLock().
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.INJECT_EVENTS)
    @TestApi
    public IBinder getHoldLockToken() {
        try {
            return ActivityThread.getPackageManager().getHoldLockToken();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Holds the PM lock for the specified amount of milliseconds.
     * Intended for use by the tests that need to imitate lock contention.
     * The token should be obtained by
     * {@link android.content.pm.PackageManager#getHoldLockToken()}.
     * @hide
     */
    @TestApi
    public void holdLock(IBinder token, int durationMs) {
        try {
            ActivityThread.getPackageManager().holdLock(token, durationMs);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set a list of apps to keep around as APKs even if no user has currently installed it.
     * @param packageList List of package names to keep cached.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.KEEP_UNINSTALLED_PACKAGES)
    @TestApi
    public void setKeepUninstalledPackages(@NonNull List<String> packageList) {
        try {
            ActivityThread.getPackageManager().setKeepUninstalledPackages(packageList);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
