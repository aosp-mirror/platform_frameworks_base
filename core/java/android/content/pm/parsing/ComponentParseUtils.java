/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.content.pm.parsing;

import static android.content.pm.ActivityInfo.FLAG_ALWAYS_FOCUSABLE;
import static android.content.pm.ActivityInfo.FLAG_SUPPORTS_PICTURE_IN_PICTURE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_UNSPECIFIED;

import android.annotation.CallSuper;
import android.annotation.UnsupportedAppUsage;
import android.app.ActivityTaskManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PathPermission;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PatternMatcher;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.util.TypedValue;
import android.view.Gravity;

import com.android.internal.R;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * TODO(b/135203078): Move the inner classes out to separate files.
 * TODO(b/135203078): Expose inner classes as immutable through interface methods.
 *
 * @hide
 */
public class ComponentParseUtils {

    private static final String TAG = ApkParseUtils.TAG;

    // TODO(b/135203078): None of this class's subclasses do anything. Remove in favor of base?
    public static class ParsedIntentInfo extends IntentFilter {

        /**
         * <p>
         * Implementation note: The serialized form for the intent list also contains the name
         * of the concrete class that's stored in the list, and assumes that every element of the
         * list is of the same type. This is very similar to the original parcelable mechanism.
         * We cannot use that directly because IntentInfo extends IntentFilter, which is parcelable
         * and is public API. It also declares Parcelable related methods as final which means
         * we can't extend them. The approach of using composition instead of inheritance leads to
         * a large set of cascading changes in the PackageManagerService, which seem undesirable.
         *
         * <p>
         * <b>WARNING: </b> The list of objects returned by this function might need to be fixed up
         * to make sure their owner fields are consistent. See {@code fixupOwner}.
         */
        public static void writeIntentsList(List<? extends ParsedIntentInfo> list, Parcel out,
                int flags) {
            if (list == null) {
                out.writeInt(-1);
                return;
            }

            final int size = list.size();
            out.writeInt(size);

            // Don't bother writing the component name if the list is empty.
            if (size > 0) {
                ParsedIntentInfo info = list.get(0);
                out.writeString(info.getClass().getName());

                for (int i = 0; i < size; i++) {
                    list.get(i).writeIntentInfoToParcel(out, flags);
                }
            }
        }

        public static <T extends ParsedIntentInfo> ArrayList<T> createIntentsList(Parcel in) {
            int size = in.readInt();
            if (size == -1) {
                return null;
            }

            if (size == 0) {
                return new ArrayList<>(0);
            }

            String className = in.readString();
            final ArrayList<T> intentsList;
            try {
                final Class<T> cls = (Class<T>) Class.forName(className);
                final Constructor<T> cons = cls.getConstructor(Parcel.class);

                intentsList = new ArrayList<>(size);
                for (int i = 0; i < size; ++i) {
                    intentsList.add(cons.newInstance(in));
                }
            } catch (ReflectiveOperationException ree) {
                throw new AssertionError("Unable to construct intent list for: "
                        + className, ree);
            }

            return intentsList;
        }

        protected String packageName;
        protected final String className;

        public boolean hasDefault;
        public int labelRes;
        public CharSequence nonLocalizedLabel;
        public int icon;

        protected List<String> rawDataTypes;

        public void addRawDataType(String dataType) throws MalformedMimeTypeException {
            if (rawDataTypes == null) {
                rawDataTypes = new ArrayList<>();
            }

            rawDataTypes.add(dataType);
            addDataType(dataType);
        }

        public ParsedIntentInfo(String packageName, String className) {
            this.packageName = packageName;
            this.className = className;
        }

        public ParsedIntentInfo(Parcel in) {
            super(in);
            packageName = in.readString();
            className = in.readString();
            hasDefault = (in.readInt() == 1);
            labelRes = in.readInt();
            nonLocalizedLabel = in.readCharSequence();
            icon = in.readInt();
        }

        public void writeIntentInfoToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeString(packageName);
            dest.writeString(className);
            dest.writeInt(hasDefault ? 1 : 0);
            dest.writeInt(labelRes);
            dest.writeCharSequence(nonLocalizedLabel);
            dest.writeInt(icon);
        }

        public String getPackageName() {
            return packageName;
        }

        public String getClassName() {
            return className;
        }
    }

    public static class ParsedActivityIntentInfo extends ParsedIntentInfo {

        public ParsedActivityIntentInfo(String packageName, String className) {
            super(packageName, className);
        }

        public ParsedActivityIntentInfo(Parcel in) {
            super(in);
        }

        public static final Creator<ParsedActivityIntentInfo> CREATOR =
                new Creator<ParsedActivityIntentInfo>() {
                    @Override
                    public ParsedActivityIntentInfo createFromParcel(Parcel source) {
                        return new ParsedActivityIntentInfo(source);
                    }

                    @Override
                    public ParsedActivityIntentInfo[] newArray(int size) {
                        return new ParsedActivityIntentInfo[size];
                    }
                };
    }

    public static class ParsedServiceIntentInfo extends ParsedIntentInfo {

        public ParsedServiceIntentInfo(String packageName, String className) {
            super(packageName, className);
        }

        public ParsedServiceIntentInfo(Parcel in) {
            super(in);
        }

        public static final Creator<ParsedServiceIntentInfo> CREATOR =
                new Creator<ParsedServiceIntentInfo>() {
                    @Override
                    public ParsedServiceIntentInfo createFromParcel(Parcel source) {
                        return new ParsedServiceIntentInfo(source);
                    }

                    @Override
                    public ParsedServiceIntentInfo[] newArray(int size) {
                        return new ParsedServiceIntentInfo[size];
                    }
                };
    }

    public static class ParsedProviderIntentInfo extends ParsedIntentInfo {

        public ParsedProviderIntentInfo(String packageName, String className) {
            super(packageName, className);
        }

        public ParsedProviderIntentInfo(Parcel in) {
            super(in);
        }

        public static final Creator<ParsedProviderIntentInfo> CREATOR =
                new Creator<ParsedProviderIntentInfo>() {
                    @Override
                    public ParsedProviderIntentInfo createFromParcel(Parcel source) {
                        return new ParsedProviderIntentInfo(source);
                    }

                    @Override
                    public ParsedProviderIntentInfo[] newArray(int size) {
                        return new ParsedProviderIntentInfo[size];
                    }
                };
    }

    public static class ParsedQueriesIntentInfo extends ParsedIntentInfo {

        public ParsedQueriesIntentInfo(String packageName, String className) {
            super(packageName, className);
        }

        public ParsedQueriesIntentInfo(Parcel in) {
            super(in);
        }

        public static final Creator<ParsedQueriesIntentInfo> CREATOR =
                new Creator<ParsedQueriesIntentInfo>() {
                    @Override
                    public ParsedQueriesIntentInfo createFromParcel(Parcel source) {
                        return new ParsedQueriesIntentInfo(source);
                    }

                    @Override
                    public ParsedQueriesIntentInfo[] newArray(int size) {
                        return new ParsedQueriesIntentInfo[size];
                    }
                };
    }

    public static class ParsedComponent<IntentInfoType extends ParsedIntentInfo> implements
            Parcelable {

        // TODO(b/135203078): Replace with "name", as not all usages are an actual class
        public String className;
        public int icon;
        public int labelRes;
        public CharSequence nonLocalizedLabel;
        public int logo;
        public int banner;

        public int descriptionRes;

        // TODO(b/135203078): Make subclass that contains these fields only for the necessary
        //  subtypes
        protected boolean enabled = true;
        protected boolean directBootAware;
        public int flags;

        private String packageName;
        private String splitName;

        // TODO(b/135203078): Make nullable
        public List<IntentInfoType> intents = new ArrayList<>();

        private transient ComponentName componentName;

        protected Bundle metaData;

        public void setSplitName(String splitName) {
            this.splitName = splitName;
        }

        public String getSplitName() {
            return splitName;
        }

        @CallSuper
        public void setPackageName(String packageName) {
            this.packageName = packageName;
            this.componentName = null;
        }

        void setPackageNameInternal(String packageName) {
            this.packageName = packageName;
            this.componentName = null;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPackageName() {
            return packageName;
        }

        public final boolean isDirectBootAware() {
            return directBootAware;
        }

        public final boolean isEnabled() {
            return enabled;
        }

        public final String getName() {
            return className;
        }

        public final Bundle getMetaData() {
            return metaData;
        }

        @UnsupportedAppUsage
        public ComponentName getComponentName() {
            if (componentName != null) {
                return componentName;
            }
            if (className != null) {
                componentName = new ComponentName(getPackageName(),
                        className);
            }
            return componentName;
        }

        public void setFrom(ParsedComponent other) {
            this.metaData = other.metaData;
            this.className = other.className;
            this.icon = other.icon;
            this.labelRes = other.labelRes;
            this.nonLocalizedLabel = other.nonLocalizedLabel;
            this.logo = other.logo;
            this.banner = other.banner;

            this.descriptionRes = other.descriptionRes;

            this.enabled = other.enabled;
            this.directBootAware = other.directBootAware;
            this.flags = other.flags;

            this.setPackageName(other.packageName);
            this.setSplitName(other.getSplitName());
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(this.className);
            dest.writeInt(this.icon);
            dest.writeInt(this.labelRes);
            dest.writeCharSequence(this.nonLocalizedLabel);
            dest.writeInt(this.logo);
            dest.writeInt(this.banner);
            dest.writeInt(this.descriptionRes);
            dest.writeBoolean(this.enabled);
            dest.writeBoolean(this.directBootAware);
            dest.writeInt(this.flags);
            dest.writeString(this.packageName);
            dest.writeString(this.splitName);
            ParsedIntentInfo.writeIntentsList(this.intents, dest, flags);
            dest.writeBundle(this.metaData);
        }

        public ParsedComponent() {
        }

        protected ParsedComponent(Parcel in) {
            // We use the boot classloader for all classes that we load.
            final ClassLoader boot = Object.class.getClassLoader();
            this.className = in.readString();
            this.icon = in.readInt();
            this.labelRes = in.readInt();
            this.nonLocalizedLabel = in.readCharSequence();
            this.logo = in.readInt();
            this.banner = in.readInt();
            this.descriptionRes = in.readInt();
            this.enabled = in.readByte() != 0;
            this.directBootAware = in.readByte() != 0;
            this.flags = in.readInt();
            this.packageName = in.readString();
            this.splitName = in.readString();
            this.intents = ParsedIntentInfo.createIntentsList(in);
            this.metaData = in.readBundle(boot);
        }
    }

    // TODO(b/135203078): Document this. Maybe split out ParsedComponent to be actual components
    //  that can have their own processes, rather than something like permission which cannot.
    public static class ParsedMainComponent<IntentInfoType extends ParsedIntentInfo> extends
            ParsedComponent<IntentInfoType> {

        private String processName;
        private String permission;

        public void setProcessName(String appProcessName, String processName) {
            // TODO(b/135203078): Is this even necessary anymore?
            this.processName = TextUtils.safeIntern(
                    processName == null ? appProcessName : processName);
        }

        public String getProcessName() {
            return processName;
        }

        public void setPermission(String permission) {
            this.permission = TextUtils.safeIntern(permission);
        }

        public String getPermission() {
            return permission;
        }

        @Override
        public void setFrom(ParsedComponent other) {
            super.setFrom(other);
            if (other instanceof ParsedMainComponent) {
                ParsedMainComponent otherMainComponent = (ParsedMainComponent) other;
                this.setProcessName(otherMainComponent.getProcessName(),
                        otherMainComponent.getProcessName());
                this.setPermission(otherMainComponent.getPermission());
            }
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeString(this.processName);
            dest.writeString(this.permission);
        }

        public ParsedMainComponent() {
        }

        protected ParsedMainComponent(Parcel in) {
            super(in);
            this.processName = TextUtils.safeIntern(in.readString());
            this.permission = TextUtils.safeIntern(in.readString());
        }

        public static final Creator<ParsedMainComponent> CREATOR =
                new Creator<ParsedMainComponent>() {
                    @Override
                    public ParsedMainComponent createFromParcel(Parcel source) {
                        return new ParsedMainComponent(source);
                    }

                    @Override
                    public ParsedMainComponent[] newArray(int size) {
                        return new ParsedMainComponent[size];
                    }
                };
    }

    public static class ParsedActivity extends ParsedMainComponent<ParsedActivityIntentInfo>
            implements Parcelable {

        public boolean exported;
        public int theme;
        public int uiOptions;

        public String targetActivity;

        public String parentActivityName;
        public String taskAffinity;
        public int privateFlags;

        public int launchMode;
        public int documentLaunchMode;
        public int maxRecents;
        public int configChanges;
        public int softInputMode;
        public int persistableMode;
        public int lockTaskLaunchMode;

        public int screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        public int resizeMode = ActivityInfo.RESIZE_MODE_RESIZEABLE;

        public float maxAspectRatio;
        public boolean hasMaxAspectRatio;

        public float minAspectRatio;
        public boolean hasMinAspectRatio;

        public String requestedVrComponent;
        public int rotationAnimation = -1;
        public int colorMode;
        public int order;

        public ActivityInfo.WindowLayout windowLayout;

        @Override
        public void setPackageName(String packageName) {
            super.setPackageName(packageName);
            for (ParsedIntentInfo intent : this.intents) {
                intent.packageName = packageName;
            }
        }

        public boolean hasMaxAspectRatio() {
            return hasMaxAspectRatio;
        }

        public boolean hasMinAspectRatio() {
            return hasMinAspectRatio;
        }

        public void setMaxAspectRatio(int resizeMode, float maxAspectRatio) {
            if (resizeMode == ActivityInfo.RESIZE_MODE_RESIZEABLE
                    || resizeMode == ActivityInfo.RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION) {
                // Resizeable activities can be put in any aspect ratio.
                return;
            }

            if (maxAspectRatio < 1.0f && maxAspectRatio != 0) {
                // Ignore any value lesser than 1.0.
                return;
            }

            this.maxAspectRatio = maxAspectRatio;
            hasMaxAspectRatio = true;
        }

        public void setMinAspectRatio(int resizeMode, float minAspectRatio) {
            if (resizeMode == RESIZE_MODE_RESIZEABLE
                    || resizeMode == RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION) {
                // Resizeable activities can be put in any aspect ratio.
                return;
            }

            if (minAspectRatio < 1.0f && minAspectRatio != 0) {
                // Ignore any value lesser than 1.0.
                return;
            }

            this.minAspectRatio = minAspectRatio;
            hasMinAspectRatio = true;
        }

        public void addIntent(ParsedActivityIntentInfo intent) {
            this.intents.add(intent);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeBoolean(this.exported);
            dest.writeInt(this.theme);
            dest.writeInt(this.uiOptions);
            dest.writeString(this.targetActivity);
            dest.writeString(this.parentActivityName);
            dest.writeString(this.taskAffinity);
            dest.writeInt(this.privateFlags);
            dest.writeInt(this.launchMode);
            dest.writeInt(this.documentLaunchMode);
            dest.writeInt(this.maxRecents);
            dest.writeInt(this.configChanges);
            dest.writeInt(this.softInputMode);
            dest.writeInt(this.persistableMode);
            dest.writeInt(this.lockTaskLaunchMode);
            dest.writeInt(this.screenOrientation);
            dest.writeInt(this.resizeMode);
            dest.writeFloat(this.maxAspectRatio);
            dest.writeBoolean(this.hasMaxAspectRatio);
            dest.writeFloat(this.minAspectRatio);
            dest.writeBoolean(this.hasMinAspectRatio);
            dest.writeString(this.requestedVrComponent);
            dest.writeInt(this.rotationAnimation);
            dest.writeInt(this.colorMode);
            dest.writeInt(this.order);
            dest.writeBundle(this.metaData);

            if (windowLayout != null) {
                dest.writeInt(1);
                dest.writeInt(windowLayout.width);
                dest.writeFloat(windowLayout.widthFraction);
                dest.writeInt(windowLayout.height);
                dest.writeFloat(windowLayout.heightFraction);
                dest.writeInt(windowLayout.gravity);
                dest.writeInt(windowLayout.minWidth);
                dest.writeInt(windowLayout.minHeight);
            } else {
                dest.writeInt(0);
            }
        }

        public ParsedActivity() {
        }

        protected ParsedActivity(Parcel in) {
            super(in);
            this.exported = in.readByte() != 0;
            this.theme = in.readInt();
            this.uiOptions = in.readInt();
            this.targetActivity = in.readString();
            this.parentActivityName = in.readString();
            this.taskAffinity = in.readString();
            this.privateFlags = in.readInt();
            this.launchMode = in.readInt();
            this.documentLaunchMode = in.readInt();
            this.maxRecents = in.readInt();
            this.configChanges = in.readInt();
            this.softInputMode = in.readInt();
            this.persistableMode = in.readInt();
            this.lockTaskLaunchMode = in.readInt();
            this.screenOrientation = in.readInt();
            this.resizeMode = in.readInt();
            this.maxAspectRatio = in.readFloat();
            this.hasMaxAspectRatio = in.readByte() != 0;
            this.minAspectRatio = in.readFloat();
            this.hasMinAspectRatio = in.readByte() != 0;
            this.requestedVrComponent = in.readString();
            this.rotationAnimation = in.readInt();
            this.colorMode = in.readInt();
            this.order = in.readInt();
            this.metaData = in.readBundle();
            if (in.readInt() == 1) {
                windowLayout = new ActivityInfo.WindowLayout(in);
            }
        }

        public static final Creator<ParsedActivity> CREATOR = new Creator<ParsedActivity>() {
            @Override
            public ParsedActivity createFromParcel(Parcel source) {
                return new ParsedActivity(source);
            }

            @Override
            public ParsedActivity[] newArray(int size) {
                return new ParsedActivity[size];
            }
        };
    }

    public static class ParsedService extends ParsedMainComponent<ParsedServiceIntentInfo> {

        public boolean exported;
        public int flags;
        public int foregroundServiceType;
        public int order;

        @Override
        public void setPackageName(String packageName) {
            super.setPackageName(packageName);
            for (ParsedIntentInfo intent : this.intents) {
                intent.packageName = packageName;
            }
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeBoolean(this.exported);
            dest.writeBundle(this.metaData);
            dest.writeInt(this.flags);
            dest.writeInt(this.foregroundServiceType);
            dest.writeInt(this.order);
        }

        public ParsedService() {
        }

        protected ParsedService(Parcel in) {
            super(in);
            this.exported = in.readByte() != 0;
            this.metaData = in.readBundle();
            this.flags = in.readInt();
            this.foregroundServiceType = in.readInt();
            this.order = in.readInt();
        }

        public static final Creator<ParsedService> CREATOR = new Creator<ParsedService>() {
            @Override
            public ParsedService createFromParcel(Parcel source) {
                return new ParsedService(source);
            }

            @Override
            public ParsedService[] newArray(int size) {
                return new ParsedService[size];
            }
        };
    }

    public static class ParsedProvider extends ParsedMainComponent<ParsedProviderIntentInfo> {

        protected boolean exported;
        protected int flags;
        protected int order;
        private String authority;
        protected boolean isSyncable;
        private String readPermission;
        private String writePermission;
        protected boolean grantUriPermissions;
        protected boolean forceUriPermissions;
        protected boolean multiProcess;
        protected int initOrder;
        protected PatternMatcher[] uriPermissionPatterns;
        protected PathPermission[] pathPermissions;

        protected void setFrom(ParsedProvider other) {
            super.setFrom(other);
            this.exported = other.exported;

            this.intents.clear();
            if (other.intents != null) {
                this.intents.addAll(other.intents);
            }

            this.flags = other.flags;
            this.order = other.order;
            this.setAuthority(other.getAuthority());
            this.isSyncable = other.isSyncable;
            this.setReadPermission(other.getReadPermission());
            this.setWritePermission(other.getWritePermission());
            this.grantUriPermissions = other.grantUriPermissions;
            this.forceUriPermissions = other.forceUriPermissions;
            this.multiProcess = other.multiProcess;
            this.initOrder = other.initOrder;
            this.uriPermissionPatterns = other.uriPermissionPatterns;
            this.pathPermissions = other.pathPermissions;
        }

        @Override
        public void setPackageName(String packageName) {
            super.setPackageName(packageName);
            for (ParsedIntentInfo intent : this.intents) {
                intent.packageName = packageName;
            }
        }

        public boolean isExported() {
            return exported;
        }

        public List<ParsedProviderIntentInfo> getIntents() {
            return intents;
        }

        public int getFlags() {
            return flags;
        }

        public int getOrder() {
            return order;
        }

        public void setAuthority(String authority) {
            this.authority = TextUtils.safeIntern(authority);
        }

        public String getAuthority() {
            return authority;
        }

        public boolean isSyncable() {
            return isSyncable;
        }

        public void setReadPermission(String readPermission) {
            this.readPermission = TextUtils.safeIntern(readPermission);
        }

        public String getReadPermission() {
            return readPermission;
        }

        public void setWritePermission(String writePermission) {
            this.writePermission = TextUtils.safeIntern(writePermission);
        }

        public String getWritePermission() {
            return writePermission;
        }

        public boolean isGrantUriPermissions() {
            return grantUriPermissions;
        }

        public boolean isForceUriPermissions() {
            return forceUriPermissions;
        }

        public boolean isMultiProcess() {
            return multiProcess;
        }

        public int getInitOrder() {
            return initOrder;
        }

        public PatternMatcher[] getUriPermissionPatterns() {
            return uriPermissionPatterns;
        }

        public PathPermission[] getPathPermissions() {
            return pathPermissions;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeBoolean(this.exported);
            dest.writeInt(this.flags);
            dest.writeInt(this.order);
            dest.writeString(this.authority);
            dest.writeBoolean(this.isSyncable);
            dest.writeString(this.readPermission);
            dest.writeString(this.writePermission);
            dest.writeBoolean(this.grantUriPermissions);
            dest.writeBoolean(this.forceUriPermissions);
            dest.writeBoolean(this.multiProcess);
            dest.writeInt(this.initOrder);
            dest.writeTypedArray(this.uriPermissionPatterns, flags);
            dest.writeTypedArray(this.pathPermissions, flags);
        }

        public ParsedProvider() {
        }

        protected ParsedProvider(Parcel in) {
            super(in);
            this.exported = in.readByte() != 0;
            this.flags = in.readInt();
            this.order = in.readInt();
            this.authority = TextUtils.safeIntern(in.readString());
            this.isSyncable = in.readByte() != 0;
            this.readPermission = TextUtils.safeIntern(in.readString());
            this.writePermission = TextUtils.safeIntern(in.readString());
            this.grantUriPermissions = in.readByte() != 0;
            this.forceUriPermissions = in.readByte() != 0;
            this.multiProcess = in.readByte() != 0;
            this.initOrder = in.readInt();
            this.uriPermissionPatterns = in.createTypedArray(PatternMatcher.CREATOR);
            this.pathPermissions = in.createTypedArray(PathPermission.CREATOR);
        }

        public static final Creator<ParsedProvider> CREATOR = new Creator<ParsedProvider>() {
            @Override
            public ParsedProvider createFromParcel(Parcel source) {
                return new ParsedProvider(source);
            }

            @Override
            public ParsedProvider[] newArray(int size) {
                return new ParsedProvider[size];
            }
        };
    }

    public static class ParsedPermission extends ParsedComponent<ParsedIntentInfo> {

        public String backgroundPermission;
        private String group;
        public int requestRes;
        public int protectionLevel;
        public boolean tree;

        public ParsedPermissionGroup parsedPermissionGroup;

        public void setName(String className) {
            this.className = className;
        }

        public void setGroup(String group) {
            this.group = TextUtils.safeIntern(group);
        }

        public String getGroup() {
            return group;
        }

        public boolean isRuntime() {
            return getProtection() == PermissionInfo.PROTECTION_DANGEROUS;
        }

        public boolean isAppOp() {
            return (protectionLevel & PermissionInfo.PROTECTION_FLAG_APPOP) != 0;
        }

        @PermissionInfo.Protection
        public int getProtection() {
            return protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
        }

        public int getProtectionFlags() {
            return protectionLevel & ~PermissionInfo.PROTECTION_MASK_BASE;
        }

        public int calculateFootprint() {
            int size = getName().length();
            if (nonLocalizedLabel != null) {
                size += nonLocalizedLabel.length();
            }
            return size;
        }

        public ParsedPermission() {
        }

        public ParsedPermission(ParsedPermission other) {
            // TODO(b/135203078): Better way to copy this? Maybe refactor to the point where copy
            //  isn't needed.
            this.className = other.className;
            this.icon = other.icon;
            this.labelRes = other.labelRes;
            this.nonLocalizedLabel = other.nonLocalizedLabel;
            this.logo = other.logo;
            this.banner = other.banner;
            this.descriptionRes = other.descriptionRes;
            this.enabled = other.enabled;
            this.directBootAware = other.directBootAware;
            this.flags = other.flags;
            this.setSplitName(other.getSplitName());
            this.setPackageName(other.getPackageName());

            this.intents.addAll(other.intents);

            if (other.metaData != null) {
                this.metaData = new Bundle();
                this.metaData.putAll(other.metaData);
            }

            this.backgroundPermission = other.backgroundPermission;
            this.setGroup(other.group);
            this.requestRes = other.requestRes;
            this.protectionLevel = other.protectionLevel;
            this.tree = other.tree;

            this.parsedPermissionGroup = other.parsedPermissionGroup;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeString(this.backgroundPermission);
            dest.writeString(this.group);
            dest.writeInt(this.requestRes);
            dest.writeInt(this.protectionLevel);
            dest.writeBoolean(this.tree);
            dest.writeParcelable(this.parsedPermissionGroup, flags);
        }

        protected ParsedPermission(Parcel in) {
            super(in);
            // We use the boot classloader for all classes that we load.
            final ClassLoader boot = Object.class.getClassLoader();
            this.backgroundPermission = in.readString();
            this.group = TextUtils.safeIntern(in.readString());
            this.requestRes = in.readInt();
            this.protectionLevel = in.readInt();
            this.tree = in.readBoolean();
            this.parsedPermissionGroup = in.readParcelable(boot);
        }

        public static final Creator<ParsedPermission> CREATOR = new Creator<ParsedPermission>() {
            @Override
            public ParsedPermission createFromParcel(Parcel source) {
                return new ParsedPermission(source);
            }

            @Override
            public ParsedPermission[] newArray(int size) {
                return new ParsedPermission[size];
            }
        };
    }

    public static class ParsedPermissionGroup extends ParsedComponent<ParsedIntentInfo> {

        public int requestDetailResourceId;
        public int backgroundRequestResourceId;
        public int backgroundRequestDetailResourceId;

        public int requestRes;
        public int priority;

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeInt(this.requestDetailResourceId);
            dest.writeInt(this.backgroundRequestResourceId);
            dest.writeInt(this.backgroundRequestDetailResourceId);
            dest.writeInt(this.requestRes);
            dest.writeInt(this.priority);
        }

        public ParsedPermissionGroup() {
        }

        protected ParsedPermissionGroup(Parcel in) {
            super(in);
            this.requestDetailResourceId = in.readInt();
            this.backgroundRequestResourceId = in.readInt();
            this.backgroundRequestDetailResourceId = in.readInt();
            this.requestRes = in.readInt();
            this.priority = in.readInt();
        }

        public static final Creator<ParsedPermissionGroup> CREATOR =
                new Creator<ParsedPermissionGroup>() {
                    @Override
                    public ParsedPermissionGroup createFromParcel(Parcel source) {
                        return new ParsedPermissionGroup(source);
                    }

                    @Override
                    public ParsedPermissionGroup[] newArray(int size) {
                        return new ParsedPermissionGroup[size];
                    }
                };
    }

    public static class ParsedInstrumentation extends ParsedComponent<ParsedIntentInfo> {

        private String targetPackage;
        private String targetProcesses;
        public boolean handleProfiling;
        public boolean functionalTest;

        public ParsedInstrumentation() {
        }

        public void setTargetPackage(String targetPackage) {
            this.targetPackage = TextUtils.safeIntern(targetPackage);
        }

        public String getTargetPackage() {
            return targetPackage;
        }

        public void setTargetProcesses(String targetProcesses) {
            this.targetProcesses = TextUtils.safeIntern(targetProcesses);
        }

        public String getTargetProcesses() {
            return targetProcesses;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeString(this.targetPackage);
            dest.writeString(this.targetProcesses);
            dest.writeBoolean(this.handleProfiling);
            dest.writeBoolean(this.functionalTest);
        }

        protected ParsedInstrumentation(Parcel in) {
            super(in);
            this.targetPackage = TextUtils.safeIntern(in.readString());
            this.targetProcesses = TextUtils.safeIntern(in.readString());
            this.handleProfiling = in.readByte() != 0;
            this.functionalTest = in.readByte() != 0;
        }

        public static final Creator<ParsedInstrumentation> CREATOR =
                new Creator<ParsedInstrumentation>() {
                    @Override
                    public ParsedInstrumentation createFromParcel(Parcel source) {
                        return new ParsedInstrumentation(source);
                    }

                    @Override
                    public ParsedInstrumentation[] newArray(int size) {
                        return new ParsedInstrumentation[size];
                    }
                };
    }

    public static ParsedActivity parseActivity(
            String[] separateProcesses,
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser, int flags, String[] outError,
            boolean receiver, boolean hardwareAccelerated)
            throws XmlPullParserException, IOException {

        TypedArray sa = null;
        boolean visibleToEphemeral;
        boolean setExported;

        int targetSdkVersion = parsingPackage.getTargetSdkVersion();
        String packageName = parsingPackage.getPackageName();
        String packageProcessName = parsingPackage.getProcessName();
        ParsedActivity result = new ParsedActivity();

        try {
            sa = res.obtainAttributes(parser, R.styleable.AndroidManifestActivity);

            String tag = receiver ? "<receiver>" : "<activity>";

            String name = sa.getNonConfigurationString(R.styleable.AndroidManifestActivity_name, 0);
            if (name == null) {
                outError[0] = tag + " does not specify android:name";
                return null;
            } else {
                String className = ApkParseUtils.buildClassName(packageName, name);
                if (PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME.equals(className)) {
                    outError[0] = tag + " invalid android:name";
                    return null;
                } else if (className == null) {
                    outError[0] = "Empty class name in package " + packageName;
                    return null;
                }

                result.className = className;
            }

            int roundIconVal = PackageParser.sUseRoundIcon ? sa.getResourceId(
                    R.styleable.AndroidManifestActivity_roundIcon, 0) : 0;
            if (roundIconVal != 0) {
                result.icon = roundIconVal;
                result.nonLocalizedLabel = null;
            } else {
                int iconVal = sa.getResourceId(R.styleable.AndroidManifestActivity_icon, 0);
                if (iconVal != 0) {
                    result.icon = iconVal;
                    result.nonLocalizedLabel = null;
                }
            }

            int logoVal = sa.getResourceId(R.styleable.AndroidManifestActivity_logo, 0);
            if (logoVal != 0) {
                result.logo = logoVal;
            }

            int bannerVal = sa.getResourceId(R.styleable.AndroidManifestActivity_banner, 0);
            if (bannerVal != 0) {
                result.banner = bannerVal;
            }

            TypedValue v = sa.peekValue(R.styleable.AndroidManifestActivity_label);
            if (v != null && (result.labelRes = v.resourceId) == 0) {
                result.nonLocalizedLabel = v.coerceToString();
            }

            result.setPackageNameInternal(packageName);

            CharSequence pname;
            if (parsingPackage.getTargetSdkVersion() >= Build.VERSION_CODES.FROYO) {
                pname = sa.getNonConfigurationString(R.styleable.AndroidManifestActivity_process,
                        Configuration.NATIVE_CONFIG_VERSION);
            } else {
                // Some older apps have been seen to use a resource reference
                // here that on older builds was ignored (with a warning).  We
                // need to continue to do this for them so they don't break.
                pname = sa.getNonResourceString(R.styleable.AndroidManifestActivity_process);
            }

            result.setProcessName(packageProcessName, PackageParser.buildProcessName(packageName,
                    packageProcessName, pname,
                    flags, separateProcesses, outError));

            result.descriptionRes = sa.getResourceId(
                    R.styleable.AndroidManifestActivity_description, 0);

            result.enabled = sa.getBoolean(R.styleable.AndroidManifestActivity_enabled, true);

            setExported = sa.hasValue(R.styleable.AndroidManifestActivity_exported);
            if (setExported) {
                result.exported = sa.getBoolean(R.styleable.AndroidManifestActivity_exported,
                        false);
            }

            result.theme = sa.getResourceId(R.styleable.AndroidManifestActivity_theme, 0);

            result.uiOptions = sa.getInt(R.styleable.AndroidManifestActivity_uiOptions,
                    parsingPackage.getUiOptions());

            String parentName = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestActivity_parentActivityName,
                    Configuration.NATIVE_CONFIG_VERSION);
            if (parentName != null) {
                String parentClassName = ApkParseUtils.buildClassName(packageName, parentName);
                if (parentClassName == null) {
                    Log.e(TAG,
                            "Activity " + result.className
                                    + " specified invalid parentActivityName " +
                                    parentName);
                } else {
                    result.parentActivityName = parentClassName;
                }
            }

            String str;
            str = sa.getNonConfigurationString(R.styleable.AndroidManifestActivity_permission, 0);
            if (str == null) {
                result.setPermission(parsingPackage.getPermission());
            } else {
                result.setPermission(str);
            }

            str = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestActivity_taskAffinity,
                    Configuration.NATIVE_CONFIG_VERSION);
            result.taskAffinity = PackageParser.buildTaskAffinityName(
                    packageName,
                    parsingPackage.getTaskAffinity(), str, outError);

            result.setSplitName(
                    sa.getNonConfigurationString(R.styleable.AndroidManifestActivity_splitName, 0));

            result.flags = 0;
            if (sa.getBoolean(
                    R.styleable.AndroidManifestActivity_multiprocess, false)) {
                result.flags |= ActivityInfo.FLAG_MULTIPROCESS;
            }

            if (sa.getBoolean(R.styleable.AndroidManifestActivity_finishOnTaskLaunch, false)) {
                result.flags |= ActivityInfo.FLAG_FINISH_ON_TASK_LAUNCH;
            }

            if (sa.getBoolean(R.styleable.AndroidManifestActivity_clearTaskOnLaunch, false)) {
                result.flags |= ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH;
            }

            if (sa.getBoolean(R.styleable.AndroidManifestActivity_noHistory, false)) {
                result.flags |= ActivityInfo.FLAG_NO_HISTORY;
            }

            if (sa.getBoolean(R.styleable.AndroidManifestActivity_alwaysRetainTaskState, false)) {
                result.flags |= ActivityInfo.FLAG_ALWAYS_RETAIN_TASK_STATE;
            }

            if (sa.getBoolean(R.styleable.AndroidManifestActivity_stateNotNeeded, false)) {
                result.flags |= ActivityInfo.FLAG_STATE_NOT_NEEDED;
            }

            if (sa.getBoolean(R.styleable.AndroidManifestActivity_excludeFromRecents, false)) {
                result.flags |= ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS;
            }

            if (sa.getBoolean(R.styleable.AndroidManifestActivity_allowTaskReparenting,
                    (parsingPackage.getFlags() & ApplicationInfo.FLAG_ALLOW_TASK_REPARENTING)
                            != 0)) {
                result.flags |= ActivityInfo.FLAG_ALLOW_TASK_REPARENTING;
            }

            if (sa.getBoolean(R.styleable.AndroidManifestActivity_finishOnCloseSystemDialogs,
                    false)) {
                result.flags |= ActivityInfo.FLAG_FINISH_ON_CLOSE_SYSTEM_DIALOGS;
            }

            if (sa.getBoolean(R.styleable.AndroidManifestActivity_showOnLockScreen, false)
                    || sa.getBoolean(R.styleable.AndroidManifestActivity_showForAllUsers, false)) {
                result.flags |= ActivityInfo.FLAG_SHOW_FOR_ALL_USERS;
            }

            if (sa.getBoolean(R.styleable.AndroidManifestActivity_immersive, false)) {
                result.flags |= ActivityInfo.FLAG_IMMERSIVE;
            }

            if (sa.getBoolean(R.styleable.AndroidManifestActivity_systemUserOnly, false)) {
                result.flags |= ActivityInfo.FLAG_SYSTEM_USER_ONLY;
            }

            boolean directBootAware;

            if (!receiver) {
                if (sa.getBoolean(R.styleable.AndroidManifestActivity_hardwareAccelerated,
                        hardwareAccelerated)) {
                    result.flags |= ActivityInfo.FLAG_HARDWARE_ACCELERATED;
                }

                result.launchMode = sa.getInt(
                        R.styleable.AndroidManifestActivity_launchMode,
                        ActivityInfo.LAUNCH_MULTIPLE);
                result.documentLaunchMode = sa.getInt(
                        R.styleable.AndroidManifestActivity_documentLaunchMode,
                        ActivityInfo.DOCUMENT_LAUNCH_NONE);
                result.maxRecents = sa.getInt(
                        R.styleable.AndroidManifestActivity_maxRecents,
                        ActivityTaskManager.getDefaultAppRecentsLimitStatic());
                result.configChanges = PackageParser.getActivityConfigChanges(
                        sa.getInt(R.styleable.AndroidManifestActivity_configChanges, 0),
                        sa.getInt(R.styleable.AndroidManifestActivity_recreateOnConfigChanges, 0));
                result.softInputMode = sa.getInt(
                        R.styleable.AndroidManifestActivity_windowSoftInputMode, 0);

                result.persistableMode = sa.getInteger(
                        R.styleable.AndroidManifestActivity_persistableMode,
                        ActivityInfo.PERSIST_ROOT_ONLY);

                if (sa.getBoolean(R.styleable.AndroidManifestActivity_allowEmbedded, false)) {
                    result.flags |= ActivityInfo.FLAG_ALLOW_EMBEDDED;
                }

                if (sa.getBoolean(R.styleable.AndroidManifestActivity_autoRemoveFromRecents,
                        false)) {
                    result.flags |= ActivityInfo.FLAG_AUTO_REMOVE_FROM_RECENTS;
                }

                if (sa.getBoolean(R.styleable.AndroidManifestActivity_relinquishTaskIdentity,
                        false)) {
                    result.flags |= ActivityInfo.FLAG_RELINQUISH_TASK_IDENTITY;
                }

                if (sa.getBoolean(R.styleable.AndroidManifestActivity_resumeWhilePausing, false)) {
                    result.flags |= ActivityInfo.FLAG_RESUME_WHILE_PAUSING;
                }

                int screenOrientation = sa.getInt(
                        R.styleable.AndroidManifestActivity_screenOrientation,
                        SCREEN_ORIENTATION_UNSPECIFIED);
                result.screenOrientation = screenOrientation;

                int resizeMode = getActivityResizeMode(parsingPackage, sa, screenOrientation);
                result.resizeMode = resizeMode;

                if (sa.getBoolean(R.styleable.AndroidManifestActivity_supportsPictureInPicture,
                        false)) {
                    result.flags |= FLAG_SUPPORTS_PICTURE_IN_PICTURE;
                }

                if (sa.getBoolean(R.styleable.AndroidManifestActivity_alwaysFocusable, false)) {
                    result.flags |= FLAG_ALWAYS_FOCUSABLE;
                }

                if (sa.hasValue(R.styleable.AndroidManifestActivity_maxAspectRatio)
                        && sa.getType(R.styleable.AndroidManifestActivity_maxAspectRatio)
                        == TypedValue.TYPE_FLOAT) {
                    result.setMaxAspectRatio(resizeMode,
                            sa.getFloat(R.styleable.AndroidManifestActivity_maxAspectRatio,
                                    0 /*default*/));
                }

                if (sa.hasValue(R.styleable.AndroidManifestActivity_minAspectRatio)
                        && sa.getType(R.styleable.AndroidManifestActivity_minAspectRatio)
                        == TypedValue.TYPE_FLOAT) {
                    result.setMinAspectRatio(resizeMode,
                            sa.getFloat(R.styleable.AndroidManifestActivity_minAspectRatio,
                                    0 /*default*/));
                }

                result.lockTaskLaunchMode =
                        sa.getInt(R.styleable.AndroidManifestActivity_lockTaskMode, 0);

                directBootAware = sa.getBoolean(
                        R.styleable.AndroidManifestActivity_directBootAware,
                        false);

                result.requestedVrComponent =
                        sa.getString(R.styleable.AndroidManifestActivity_enableVrMode);

                result.rotationAnimation =
                        sa.getInt(R.styleable.AndroidManifestActivity_rotationAnimation,
                                ROTATION_ANIMATION_UNSPECIFIED);

                result.colorMode = sa.getInt(R.styleable.AndroidManifestActivity_colorMode,
                        ActivityInfo.COLOR_MODE_DEFAULT);

                if (sa.getBoolean(R.styleable.AndroidManifestActivity_showWhenLocked, false)) {
                    result.flags |= ActivityInfo.FLAG_SHOW_WHEN_LOCKED;
                }

                if (sa.getBoolean(R.styleable.AndroidManifestActivity_turnScreenOn, false)) {
                    result.flags |= ActivityInfo.FLAG_TURN_SCREEN_ON;
                }

                if (sa.getBoolean(R.styleable.AndroidManifestActivity_inheritShowWhenLocked,
                        false)) {
                    result.privateFlags |= ActivityInfo.FLAG_INHERIT_SHOW_WHEN_LOCKED;
                }
            } else {
                result.launchMode = ActivityInfo.LAUNCH_MULTIPLE;
                result.configChanges = 0;

                if (sa.getBoolean(R.styleable.AndroidManifestActivity_singleUser, false)) {
                    result.flags |= ActivityInfo.FLAG_SINGLE_USER;
                }
                directBootAware = sa.getBoolean(
                        R.styleable.AndroidManifestActivity_directBootAware,
                        false);
            }

            result.directBootAware = directBootAware;

            if (directBootAware) {
                parsingPackage.setPartiallyDirectBootAware(true);
            }

            // can't make this final; we may set it later via meta-data
            visibleToEphemeral = sa.getBoolean(
                    R.styleable.AndroidManifestActivity_visibleToInstantApps, false);
            if (visibleToEphemeral) {
                result.flags |= ActivityInfo.FLAG_VISIBLE_TO_INSTANT_APP;
                parsingPackage.setVisibleToInstantApps(true);
            }
        } finally {
            if (sa != null) {
                sa.recycle();
            }
        }


        if (receiver && (parsingPackage.getPrivateFlags()
                & ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE) != 0) {
            // A heavy-weight application can not have receives in its main process
            if (result.getProcessName().equals(packageName)) {
                outError[0] = "Heavy-weight applications can not have receivers in main process";
                return null;
            }
        }

        if (outError[0] != null) {
            return null;
        }

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getName().equals("intent-filter")) {
                ParsedActivityIntentInfo intentInfo = new ParsedActivityIntentInfo(packageName,
                        result.className);
                if (!parseIntentInfo(intentInfo, parsingPackage, res, parser,
                        true /*allowGlobs*/,
                        true /*allowAutoVerify*/, outError)) {
                    return null;
                }
                if (intentInfo.countActions() == 0) {
                    Slog.w(TAG, "No actions in intent filter at "
                            + parsingPackage.getBaseCodePath() + " "
                            + parser.getPositionDescription());
                } else {
                    result.order = Math.max(intentInfo.getOrder(), result.order);
                    result.addIntent(intentInfo);
                }
                // adjust activity flags when we implicitly expose it via a browsable filter
                final int visibility = visibleToEphemeral
                        ? IntentFilter.VISIBILITY_EXPLICIT
                        : !receiver && isImplicitlyExposedIntent(intentInfo)
                                ? IntentFilter.VISIBILITY_IMPLICIT
                                : IntentFilter.VISIBILITY_NONE;
                intentInfo.setVisibilityToInstantApp(visibility);
                if (intentInfo.isVisibleToInstantApp()) {
                    result.flags |= ActivityInfo.FLAG_VISIBLE_TO_INSTANT_APP;
                }
                if (intentInfo.isImplicitlyVisibleToInstantApp()) {
                    result.flags |= ActivityInfo.FLAG_IMPLICITLY_VISIBLE_TO_INSTANT_APP;
                }
                if (PackageParser.LOG_UNSAFE_BROADCASTS && receiver
                        && (targetSdkVersion >= Build.VERSION_CODES.O)) {
                    for (int i = 0; i < intentInfo.countActions(); i++) {
                        final String action = intentInfo.getAction(i);
                        if (action == null || !action.startsWith("android.")) continue;
                        if (!PackageParser.SAFE_BROADCASTS.contains(action)) {
                            Slog.w(TAG, "Broadcast " + action + " may never be delivered to "
                                    + packageName + " as requested at: "
                                    + parser.getPositionDescription());
                        }
                    }
                }
            } else if (!receiver && parser.getName().equals("preferred")) {
                ParsedActivityIntentInfo intentInfo = new ParsedActivityIntentInfo(packageName,
                        result.className);
                if (!parseIntentInfo(intentInfo, parsingPackage, res, parser,
                        false /*allowGlobs*/,
                        false /*allowAutoVerify*/, outError)) {
                    return null;
                }
                // adjust activity flags when we implicitly expose it via a browsable filter
                final int visibility = visibleToEphemeral
                        ? IntentFilter.VISIBILITY_EXPLICIT
                        : !receiver && isImplicitlyExposedIntent(intentInfo)
                                ? IntentFilter.VISIBILITY_IMPLICIT
                                : IntentFilter.VISIBILITY_NONE;
                intentInfo.setVisibilityToInstantApp(visibility);
                if (intentInfo.isVisibleToInstantApp()) {
                    result.flags |= ActivityInfo.FLAG_VISIBLE_TO_INSTANT_APP;
                }
                if (intentInfo.isImplicitlyVisibleToInstantApp()) {
                    result.flags |= ActivityInfo.FLAG_IMPLICITLY_VISIBLE_TO_INSTANT_APP;
                }

                if (intentInfo.countActions() == 0) {
                    Slog.w(TAG, "No actions in preferred at "
                            + parsingPackage.getBaseCodePath() + " "
                            + parser.getPositionDescription());
                } else {
                    parsingPackage.addPreferredActivityFilter(intentInfo);
                }
            } else if (parser.getName().equals("meta-data")) {
                if ((result.metaData = ApkParseUtils.parseMetaData(parsingPackage, res, parser,
                        result.metaData,
                        outError)) == null) {
                    return null;
                }
            } else if (!receiver && parser.getName().equals("layout")) {
                result.windowLayout = parseLayout(res, parser);
            } else {
                if (!PackageParser.RIGID_PARSER) {
                    Slog.w(TAG, "Problem in package " + parsingPackage.getBaseCodePath() + ":");
                    if (receiver) {
                        Slog.w(TAG, "Unknown element under <receiver>: " + parser.getName()
                                + " at " + parsingPackage.getBaseCodePath() + " "
                                + parser.getPositionDescription());
                    } else {
                        Slog.w(TAG, "Unknown element under <activity>: " + parser.getName()
                                + " at " + parsingPackage.getBaseCodePath() + " "
                                + parser.getPositionDescription());
                    }
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else {
                    if (receiver) {
                        outError[0] = "Bad element under <receiver>: " + parser.getName();
                    } else {
                        outError[0] = "Bad element under <activity>: " + parser.getName();
                    }
                    return null;
                }
            }
        }

        if (!setExported) {
            result.exported = result.intents.size() > 0;
        }

        return result;
    }

    public static boolean isImplicitlyExposedIntent(ParsedIntentInfo intentInfo) {
        return intentInfo.hasCategory(Intent.CATEGORY_BROWSABLE)
                || intentInfo.hasAction(Intent.ACTION_SEND)
                || intentInfo.hasAction(Intent.ACTION_SENDTO)
                || intentInfo.hasAction(Intent.ACTION_SEND_MULTIPLE);
    }

    public static int getActivityResizeMode(
            ParsingPackage parsingPackage,
            TypedArray sa,
            int screenOrientation
    ) {
        int privateFlags = parsingPackage.getPrivateFlags();
        final boolean appExplicitDefault = (privateFlags
                & (ApplicationInfo.PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE
                | ApplicationInfo.PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_UNRESIZEABLE)) != 0;

        if (sa.hasValue(R.styleable.AndroidManifestActivity_resizeableActivity)
                || appExplicitDefault) {
            // Activity or app explicitly set if it is resizeable or not;
            final boolean appResizeable = (privateFlags
                    & ApplicationInfo.PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE) != 0;
            if (sa.getBoolean(R.styleable.AndroidManifestActivity_resizeableActivity,
                    appResizeable)) {
                return ActivityInfo.RESIZE_MODE_RESIZEABLE;
            } else {
                return ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
            }
        }

        if ((privateFlags
                & ApplicationInfo.PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION)
                != 0) {
            // The activity or app didn't explicitly set the resizing option, however we want to
            // make it resize due to the sdk version it is targeting.
            return ActivityInfo.RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION;
        }

        // resize preference isn't set and target sdk version doesn't support resizing apps by
        // default. For the app to be resizeable if it isn't fixed orientation or immersive.
        if (ActivityInfo.isFixedOrientationPortrait(screenOrientation)) {
            return ActivityInfo.RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY;
        } else if (ActivityInfo.isFixedOrientationLandscape(screenOrientation)) {
            return ActivityInfo.RESIZE_MODE_FORCE_RESIZABLE_LANDSCAPE_ONLY;
        } else if (screenOrientation == ActivityInfo.SCREEN_ORIENTATION_LOCKED) {
            return ActivityInfo.RESIZE_MODE_FORCE_RESIZABLE_PRESERVE_ORIENTATION;
        } else {
            return ActivityInfo.RESIZE_MODE_FORCE_RESIZEABLE;
        }
    }

    public static ParsedService parseService(
            String[] separateProcesses,
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser, int flags, String[] outError
    ) throws XmlPullParserException, IOException {
        TypedArray sa = null;
        boolean visibleToEphemeral;
        boolean setExported;

        String packageName = parsingPackage.getPackageName();
        String packageProcessName = parsingPackage.getProcessName();
        ParsedService result = new ParsedService();

        try {
            sa = res.obtainAttributes(parser,
                    R.styleable.AndroidManifestService);

            String name = sa.getNonConfigurationString(R.styleable.AndroidManifestService_name, 0);
            if (name == null) {
                outError[0] = "<service> does not specify android:name";
                return null;
            } else {
                String className = ApkParseUtils.buildClassName(packageName, name);
                if (PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME.equals(className)) {
                    outError[0] = "<service> invalid android:name";
                    return null;
                } else if (className == null) {
                    outError[0] = "Empty class name in package " + packageName;
                    return null;
                }

                result.className = className;
            }

            int roundIconVal = PackageParser.sUseRoundIcon ? sa.getResourceId(
                    R.styleable.AndroidManifestService_roundIcon, 0) : 0;
            if (roundIconVal != 0) {
                result.icon = roundIconVal;
                result.nonLocalizedLabel = null;
            } else {
                int iconVal = sa.getResourceId(R.styleable.AndroidManifestService_icon, 0);
                if (iconVal != 0) {
                    result.icon = iconVal;
                    result.nonLocalizedLabel = null;
                }
            }

            int logoVal = sa.getResourceId(R.styleable.AndroidManifestService_logo, 0);
            if (logoVal != 0) {
                result.logo = logoVal;
            }

            int bannerVal = sa.getResourceId(R.styleable.AndroidManifestService_banner, 0);
            if (bannerVal != 0) {
                result.banner = bannerVal;
            }

            TypedValue v = sa.peekValue(R.styleable.AndroidManifestService_label);
            if (v != null && (result.labelRes = v.resourceId) == 0) {
                result.nonLocalizedLabel = v.coerceToString();
            }

            result.setPackageNameInternal(packageName);

            CharSequence pname;
            if (parsingPackage.getTargetSdkVersion() >= Build.VERSION_CODES.FROYO) {
                pname = sa.getNonConfigurationString(R.styleable.AndroidManifestService_process,
                        Configuration.NATIVE_CONFIG_VERSION);
            } else {
                // Some older apps have been seen to use a resource reference
                // here that on older builds was ignored (with a warning).  We
                // need to continue to do this for them so they don't break.
                pname = sa.getNonResourceString(R.styleable.AndroidManifestService_process);
            }

            result.setProcessName(packageProcessName, PackageParser.buildProcessName(packageName,
                    packageProcessName, pname,
                    flags, separateProcesses, outError));

            result.descriptionRes = sa.getResourceId(
                    R.styleable.AndroidManifestService_description, 0);

            result.enabled = sa.getBoolean(R.styleable.AndroidManifestService_enabled, true);

            setExported = sa.hasValue(
                    R.styleable.AndroidManifestService_exported);
            if (setExported) {
                result.exported = sa.getBoolean(
                        R.styleable.AndroidManifestService_exported, false);
            }

            String str = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestService_permission, 0);
            if (str == null) {
                result.setPermission(parsingPackage.getPermission());
            } else {
                result.setPermission(str);
            }

            result.setSplitName(
                    sa.getNonConfigurationString(R.styleable.AndroidManifestService_splitName, 0));

            result.foregroundServiceType = sa.getInt(
                    R.styleable.AndroidManifestService_foregroundServiceType,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE);

            result.flags = 0;
            if (sa.getBoolean(
                    R.styleable.AndroidManifestService_stopWithTask,
                    false)) {
                result.flags |= ServiceInfo.FLAG_STOP_WITH_TASK;
            }
            if (sa.getBoolean(
                    R.styleable.AndroidManifestService_isolatedProcess,
                    false)) {
                result.flags |= ServiceInfo.FLAG_ISOLATED_PROCESS;
            }
            if (sa.getBoolean(
                    R.styleable.AndroidManifestService_externalService,
                    false)) {
                result.flags |= ServiceInfo.FLAG_EXTERNAL_SERVICE;
            }
            if (sa.getBoolean(
                    R.styleable.AndroidManifestService_useAppZygote,
                    false)) {
                result.flags |= ServiceInfo.FLAG_USE_APP_ZYGOTE;
            }
            if (sa.getBoolean(
                    R.styleable.AndroidManifestService_singleUser,
                    false)) {
                result.flags |= ServiceInfo.FLAG_SINGLE_USER;
            }

            result.directBootAware = sa.getBoolean(
                    R.styleable.AndroidManifestService_directBootAware,
                    false);
            if (result.directBootAware) {
                parsingPackage.setPartiallyDirectBootAware(true);
            }

            visibleToEphemeral = sa.getBoolean(
                    R.styleable.AndroidManifestService_visibleToInstantApps, false);
            if (visibleToEphemeral) {
                result.flags |= ServiceInfo.FLAG_VISIBLE_TO_INSTANT_APP;
                parsingPackage.setVisibleToInstantApps(true);
            }
        } finally {
            if (sa != null) {
                sa.recycle();
            }
        }

        if (parsingPackage.cantSaveState()) {
            // A heavy-weight application can not have services in its main process
            // We can do direct compare because we intern all strings.
            if (Objects.equals(result.getProcessName(), parsingPackage.getPackageName())) {
                outError[0] = "Heavy-weight applications can not have services in main process";
                return null;
            }
        }

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getName().equals("intent-filter")) {
                ParsedServiceIntentInfo intent = new ParsedServiceIntentInfo(packageName,
                        result.className);
                if (!parseIntentInfo(intent, parsingPackage, res, parser, true /*allowGlobs*/,
                        false /*allowAutoVerify*/,
                        outError)) {
                    return null;
                }
                if (visibleToEphemeral) {
                    intent.setVisibilityToInstantApp(IntentFilter.VISIBILITY_EXPLICIT);
                    result.flags |= ServiceInfo.FLAG_VISIBLE_TO_INSTANT_APP;
                }
                result.order = Math.max(intent.getOrder(), result.order);
                result.intents.add(intent);
            } else if (parser.getName().equals("meta-data")) {
                if ((result.metaData = ApkParseUtils.parseMetaData(parsingPackage, res, parser,
                        result.metaData,
                        outError)) == null) {
                    return null;
                }
            } else {
                if (!PackageParser.RIGID_PARSER) {
                    Slog.w(TAG, "Unknown element under <service>: "
                            + parser.getName() + " at " + parsingPackage.getBaseCodePath() + " "
                            + parser.getPositionDescription());
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else {
                    outError[0] = "Bad element under <service>: " + parser.getName();
                    return null;
                }
            }
        }

        if (!setExported) {
            result.exported = result.intents.size() > 0;
        }

        return result;
    }

    public static ParsedProvider parseProvider(
            String[] separateProcesses,
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser, int flags, String[] outError)
            throws XmlPullParserException, IOException {
        TypedArray sa = null;
        String cpname;
        boolean visibleToEphemeral;

        int targetSdkVersion = parsingPackage.getTargetSdkVersion();
        String packageName = parsingPackage.getPackageName();
        String packageProcessName = parsingPackage.getProcessName();
        ParsedProvider result = new ParsedProvider();

        try {
            sa = res.obtainAttributes(parser,
                    R.styleable.AndroidManifestProvider);

            String name = sa.getNonConfigurationString(R.styleable.AndroidManifestProvider_name, 0);
            if (name == null) {
                outError[0] = "<provider> does not specify android:name";
                return null;
            } else {
                String className = ApkParseUtils.buildClassName(packageName, name);
                if (PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME.equals(className)) {
                    outError[0] = "<provider> invalid android:name";
                    return null;
                } else if (className == null) {
                    outError[0] = "Empty class name in package " + packageName;
                    return null;
                }

                result.className = className;
            }

            int roundIconVal = PackageParser.sUseRoundIcon ? sa.getResourceId(
                    R.styleable.AndroidManifestProvider_roundIcon, 0) : 0;
            if (roundIconVal != 0) {
                result.icon = roundIconVal;
                result.nonLocalizedLabel = null;
            } else {
                int iconVal = sa.getResourceId(R.styleable.AndroidManifestProvider_icon, 0);
                if (iconVal != 0) {
                    result.icon = iconVal;
                    result.nonLocalizedLabel = null;
                }
            }

            int logoVal = sa.getResourceId(R.styleable.AndroidManifestProvider_logo, 0);
            if (logoVal != 0) {
                result.logo = logoVal;
            }

            int bannerVal = sa.getResourceId(R.styleable.AndroidManifestProvider_banner, 0);
            if (bannerVal != 0) {
                result.banner = bannerVal;
            }

            TypedValue v = sa.peekValue(R.styleable.AndroidManifestProvider_label);
            if (v != null && (result.labelRes = v.resourceId) == 0) {
                result.nonLocalizedLabel = v.coerceToString();
            }

            result.setPackageNameInternal(packageName);

            CharSequence pname;
            if (parsingPackage.getTargetSdkVersion() >= Build.VERSION_CODES.FROYO) {
                pname = sa.getNonConfigurationString(R.styleable.AndroidManifestProvider_process,
                        Configuration.NATIVE_CONFIG_VERSION);
            } else {
                // Some older apps have been seen to use a resource reference
                // here that on older builds was ignored (with a warning).  We
                // need to continue to do this for them so they don't break.
                pname = sa.getNonResourceString(R.styleable.AndroidManifestProvider_process);
            }

            result.setProcessName(packageProcessName, PackageParser.buildProcessName(packageName,
                    packageProcessName, pname,
                    flags, separateProcesses, outError));

            result.descriptionRes = sa.getResourceId(
                    R.styleable.AndroidManifestProvider_description, 0);

            result.enabled = sa.getBoolean(R.styleable.AndroidManifestProvider_enabled, true);

            boolean providerExportedDefault = false;

            if (targetSdkVersion < Build.VERSION_CODES.JELLY_BEAN_MR1) {
                // For compatibility, applications targeting API level 16 or lower
                // should have their content providers exported by default, unless they
                // specify otherwise.
                providerExportedDefault = true;
            }

            result.exported = sa.getBoolean(
                    R.styleable.AndroidManifestProvider_exported,
                    providerExportedDefault);

            cpname = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestProvider_authorities, 0);

            result.isSyncable = sa.getBoolean(
                    R.styleable.AndroidManifestProvider_syncable,
                    false);

            String permission = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestProvider_permission, 0);
            String str = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestProvider_readPermission, 0);
            if (str == null) {
                str = permission;
            }
            if (str == null) {
                result.setReadPermission(parsingPackage.getPermission());
            } else {
                result.setReadPermission(str);
            }
            str = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestProvider_writePermission, 0);
            if (str == null) {
                str = permission;
            }
            if (str == null) {
                result.setWritePermission(parsingPackage.getPermission());
            } else {
                result.setWritePermission(str);
            }

            result.grantUriPermissions = sa.getBoolean(
                    R.styleable.AndroidManifestProvider_grantUriPermissions,
                    false);

            result.forceUriPermissions = sa.getBoolean(
                    R.styleable.AndroidManifestProvider_forceUriPermissions,
                    false);

            result.multiProcess = sa.getBoolean(
                    R.styleable.AndroidManifestProvider_multiprocess,
                    false);

            result.initOrder = sa.getInt(
                    R.styleable.AndroidManifestProvider_initOrder,
                    0);

            result.setSplitName(
                    sa.getNonConfigurationString(R.styleable.AndroidManifestProvider_splitName, 0));

            result.flags = 0;

            if (sa.getBoolean(
                    R.styleable.AndroidManifestProvider_singleUser,
                    false)) {
                result.flags |= ProviderInfo.FLAG_SINGLE_USER;
            }

            result.directBootAware = sa.getBoolean(
                    R.styleable.AndroidManifestProvider_directBootAware,
                    false);
            if (result.directBootAware) {
                parsingPackage.setPartiallyDirectBootAware(true);
            }

            visibleToEphemeral =
                    sa.getBoolean(R.styleable.AndroidManifestProvider_visibleToInstantApps, false);
            if (visibleToEphemeral) {
                result.flags |= ProviderInfo.FLAG_VISIBLE_TO_INSTANT_APP;
                parsingPackage.setVisibleToInstantApps(true);
            }
        } finally {
            if (sa != null) {
                sa.recycle();
            }
        }

        if ((parsingPackage.getPrivateFlags() & ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE)
                != 0) {
            // A heavy-weight application can not have providers in its main process
            if (result.getProcessName().equals(packageName)) {
                outError[0] = "Heavy-weight applications can not have providers in main process";
                return null;
            }
        }

        if (cpname == null) {
            outError[0] = "<provider> does not include authorities attribute";
            return null;
        }
        if (cpname.length() <= 0) {
            outError[0] = "<provider> has empty authorities attribute";
            return null;
        }
        result.setAuthority(cpname);

        if (!parseProviderTags(parsingPackage, res, parser, visibleToEphemeral, result, outError)) {
            return null;
        }

        return result;
    }

    public static ParsedQueriesIntentInfo parsedParsedQueriesIntentInfo(
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser,
            String[] outError
    ) throws IOException, XmlPullParserException {
        ParsedQueriesIntentInfo intentInfo = new ParsedQueriesIntentInfo(
                parsingPackage.getPackageName(),
                null
        );
        if (!parseIntentInfo(
                intentInfo,
                parsingPackage,
                res,
                parser,
                true /*allowGlobs*/,
                true /*allowAutoVerify*/,
                outError
        )) {
            return null;
        }
        return intentInfo;
    }

    private static boolean parseProviderTags(
            ParsingPackage parsingPackage,
            Resources res, XmlResourceParser parser,
            boolean visibleToEphemeral, ParsedProvider outInfo, String[] outError)
            throws XmlPullParserException, IOException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getName().equals("intent-filter")) {
                ParsedProviderIntentInfo intent = new ParsedProviderIntentInfo(
                        parsingPackage.getPackageName(), outInfo.className);
                if (!parseIntentInfo(intent, parsingPackage, res, parser, true /*allowGlobs*/,
                        false /*allowAutoVerify*/,
                        outError)) {
                    return false;
                }
                if (visibleToEphemeral) {
                    intent.setVisibilityToInstantApp(IntentFilter.VISIBILITY_EXPLICIT);
                    outInfo.flags |= ProviderInfo.FLAG_VISIBLE_TO_INSTANT_APP;
                }
                outInfo.order = Math.max(intent.getOrder(), outInfo.order);
                outInfo.intents.add(intent);

            } else if (parser.getName().equals("meta-data")) {
                Bundle metaData = ApkParseUtils.parseMetaData(parsingPackage, res, parser,
                        outInfo.metaData, outError);
                if (metaData == null) {
                    return false;
                } else {
                    outInfo.metaData = metaData;
                }

            } else if (parser.getName().equals("grant-uri-permission")) {
                TypedArray sa = res.obtainAttributes(parser,
                        R.styleable.AndroidManifestGrantUriPermission);

                PatternMatcher pa = null;

                String str = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestGrantUriPermission_path, 0);
                if (str != null) {
                    pa = new PatternMatcher(str, PatternMatcher.PATTERN_LITERAL);
                }

                str = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestGrantUriPermission_pathPrefix, 0);
                if (str != null) {
                    pa = new PatternMatcher(str, PatternMatcher.PATTERN_PREFIX);
                }

                str = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestGrantUriPermission_pathPattern, 0);
                if (str != null) {
                    pa = new PatternMatcher(str, PatternMatcher.PATTERN_SIMPLE_GLOB);
                }

                sa.recycle();

                if (pa != null) {
                    if (outInfo.uriPermissionPatterns == null) {
                        outInfo.uriPermissionPatterns = new PatternMatcher[1];
                        outInfo.uriPermissionPatterns[0] = pa;
                    } else {
                        final int N = outInfo.uriPermissionPatterns.length;
                        PatternMatcher[] newp = new PatternMatcher[N + 1];
                        System.arraycopy(outInfo.uriPermissionPatterns, 0, newp, 0, N);
                        newp[N] = pa;
                        outInfo.uriPermissionPatterns = newp;
                    }
                    outInfo.grantUriPermissions = true;
                } else {
                    if (!PackageParser.RIGID_PARSER) {
                        Slog.w(TAG, "Unknown element under <path-permission>: "
                                + parser.getName() + " at " + parsingPackage.getBaseCodePath()
                                + " "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    } else {
                        outError[0] = "No path, pathPrefix, or pathPattern for <path-permission>";
                        return false;
                    }
                }
                XmlUtils.skipCurrentTag(parser);

            } else if (parser.getName().equals("path-permission")) {
                TypedArray sa = res.obtainAttributes(parser,
                        R.styleable.AndroidManifestPathPermission);

                PathPermission pa = null;

                String permission = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestPathPermission_permission, 0);
                String readPermission = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestPathPermission_readPermission, 0);
                if (readPermission == null) {
                    readPermission = permission;
                }
                String writePermission = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestPathPermission_writePermission, 0);
                if (writePermission == null) {
                    writePermission = permission;
                }

                boolean havePerm = false;
                if (readPermission != null) {
                    readPermission = readPermission.intern();
                    havePerm = true;
                }
                if (writePermission != null) {
                    writePermission = writePermission.intern();
                    havePerm = true;
                }

                if (!havePerm) {
                    if (!PackageParser.RIGID_PARSER) {
                        Slog.w(TAG, "No readPermission or writePermssion for <path-permission>: "
                                + parser.getName() + " at " + parsingPackage.getBaseCodePath()
                                + " "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    } else {
                        outError[0] = "No readPermission or writePermssion for <path-permission>";
                        return false;
                    }
                }

                String path = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestPathPermission_path, 0);
                if (path != null) {
                    pa = new PathPermission(path,
                            PatternMatcher.PATTERN_LITERAL, readPermission, writePermission);
                }

                path = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestPathPermission_pathPrefix, 0);
                if (path != null) {
                    pa = new PathPermission(path,
                            PatternMatcher.PATTERN_PREFIX, readPermission, writePermission);
                }

                path = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestPathPermission_pathPattern, 0);
                if (path != null) {
                    pa = new PathPermission(path,
                            PatternMatcher.PATTERN_SIMPLE_GLOB, readPermission, writePermission);
                }

                path = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestPathPermission_pathAdvancedPattern, 0);
                if (path != null) {
                    pa = new PathPermission(path,
                            PatternMatcher.PATTERN_ADVANCED_GLOB, readPermission, writePermission);
                }

                sa.recycle();

                if (pa != null) {
                    if (outInfo.pathPermissions == null) {
                        outInfo.pathPermissions = new PathPermission[1];
                        outInfo.pathPermissions[0] = pa;
                    } else {
                        final int N = outInfo.pathPermissions.length;
                        PathPermission[] newp = new PathPermission[N + 1];
                        System.arraycopy(outInfo.pathPermissions, 0, newp, 0, N);
                        newp[N] = pa;
                        outInfo.pathPermissions = newp;
                    }
                } else {
                    if (!PackageParser.RIGID_PARSER) {
                        Slog.w(TAG, "No path, pathPrefix, or pathPattern for <path-permission>: "
                                + parser.getName() + " at " + parsingPackage.getBaseCodePath()
                                + " "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    outError[0] = "No path, pathPrefix, or pathPattern for <path-permission>";
                    return false;
                }
                XmlUtils.skipCurrentTag(parser);

            } else {
                if (!PackageParser.RIGID_PARSER) {
                    Slog.w(TAG, "Unknown element under <provider>: "
                            + parser.getName() + " at " + parsingPackage.getBaseCodePath() + " "
                            + parser.getPositionDescription());
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else {
                    outError[0] = "Bad element under <provider>: " + parser.getName();
                    return false;
                }
            }
        }
        return true;
    }

    public static ParsedActivity parseActivityAlias(
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser,
            String[] outError)
            throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(parser,
                R.styleable.AndroidManifestActivityAlias);

        String targetActivity = sa.getNonConfigurationString(
                R.styleable.AndroidManifestActivityAlias_targetActivity,
                Configuration.NATIVE_CONFIG_VERSION);
        if (targetActivity == null) {
            outError[0] = "<activity-alias> does not specify android:targetActivity";
            sa.recycle();
            return null;
        }

        String packageName = parsingPackage.getPackageName();
        targetActivity = ApkParseUtils.buildClassName(packageName, targetActivity);
        if (targetActivity == null) {
            outError[0] = "Empty class name in package " + packageName;
            sa.recycle();
            return null;
        }

        ParsedActivity target = null;

        List<ParsedActivity> activities = parsingPackage.getActivities();
        final int NA = activities.size();
        for (int i = 0; i < NA; i++) {
            ParsedActivity t = activities.get(i);
            if (targetActivity.equals(t.className)) {
                target = t;
                break;
            }
        }

        if (target == null) {
            outError[0] = "<activity-alias> target activity " + targetActivity
                    + " not found in manifest with activities = " + parsingPackage.getActivities()
                    + ", parsedActivities = " + activities;
            sa.recycle();
            return null;
        }

        ParsedActivity result = new ParsedActivity();
        result.setPackageNameInternal(target.getPackageName());
        result.targetActivity = targetActivity;
        result.configChanges = target.configChanges;
        result.flags = target.flags;
        result.privateFlags = target.privateFlags;
        result.icon = target.icon;
        result.logo = target.logo;
        result.banner = target.banner;
        result.labelRes = target.labelRes;
        result.nonLocalizedLabel = target.nonLocalizedLabel;
        result.launchMode = target.launchMode;
        result.lockTaskLaunchMode = target.lockTaskLaunchMode;
        result.descriptionRes = target.descriptionRes;
        result.screenOrientation = target.screenOrientation;
        result.taskAffinity = target.taskAffinity;
        result.theme = target.theme;
        result.softInputMode = target.softInputMode;
        result.uiOptions = target.uiOptions;
        result.parentActivityName = target.parentActivityName;
        result.maxRecents = target.maxRecents;
        result.windowLayout = target.windowLayout;
        result.resizeMode = target.resizeMode;
        result.maxAspectRatio = target.maxAspectRatio;
        result.hasMaxAspectRatio = target.hasMaxAspectRatio;
        result.minAspectRatio = target.minAspectRatio;
        result.hasMinAspectRatio = target.hasMinAspectRatio;
        result.requestedVrComponent = target.requestedVrComponent;
        result.directBootAware = target.directBootAware;

        result.setProcessName(parsingPackage.getAppInfoProcessName(), target.getProcessName());

        // Not all attributes from the target ParsedActivity are copied to the alias.
        // Careful when adding an attribute and determine whether or not it should be copied.
//        result.enabled = target.enabled;
//        result.exported = target.exported;
//        result.permission = target.permission;
//        result.splitName = target.splitName;
//        result.documentLaunchMode = target.documentLaunchMode;
//        result.persistableMode = target.persistableMode;
//        result.rotationAnimation = target.rotationAnimation;
//        result.colorMode = target.colorMode;
//        result.intents.addAll(target.intents);
//        result.order = target.order;
//        result.metaData = target.metaData;

        String name = sa.getNonConfigurationString(R.styleable.AndroidManifestActivityAlias_name,
                0);
        if (name == null) {
            outError[0] = "<activity-alias> does not specify android:name";
            return null;
        } else {
            String className = ApkParseUtils.buildClassName(packageName, name);
            if (PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME.equals(className)) {
                outError[0] = "<activity-alias> invalid android:name";
                return null;
            } else if (className == null) {
                outError[0] = "Empty class name in package " + packageName;
                return null;
            }

            result.className = className;
        }

        int roundIconVal = PackageParser.sUseRoundIcon ? sa.getResourceId(
                R.styleable.AndroidManifestActivityAlias_roundIcon, 0) : 0;
        if (roundIconVal != 0) {
            result.icon = roundIconVal;
            result.nonLocalizedLabel = null;
        } else {
            int iconVal = sa.getResourceId(R.styleable.AndroidManifestActivityAlias_icon, 0);
            if (iconVal != 0) {
                result.icon = iconVal;
                result.nonLocalizedLabel = null;
            }
        }

        int logoVal = sa.getResourceId(R.styleable.AndroidManifestActivityAlias_logo, 0);
        if (logoVal != 0) {
            result.logo = logoVal;
        }

        int bannerVal = sa.getResourceId(R.styleable.AndroidManifestActivityAlias_banner, 0);
        if (bannerVal != 0) {
            result.banner = bannerVal;
        }

        TypedValue v = sa.peekValue(R.styleable.AndroidManifestActivityAlias_label);
        if (v != null && (result.labelRes = v.resourceId) == 0) {
            result.nonLocalizedLabel = v.coerceToString();
        }

        result.setPackageNameInternal(packageName);

        result.descriptionRes = sa.getResourceId(
                R.styleable.AndroidManifestActivityAlias_description, 0);

        result.enabled = sa.getBoolean(R.styleable.AndroidManifestActivityAlias_enabled, true);

        final boolean setExported = sa.hasValue(
                R.styleable.AndroidManifestActivityAlias_exported);
        if (setExported) {
            result.exported = sa.getBoolean(
                    R.styleable.AndroidManifestActivityAlias_exported, false);
        }

        String str;
        str = sa.getNonConfigurationString(
                R.styleable.AndroidManifestActivityAlias_permission, 0);
        if (str != null) {
            result.setPermission(str);
        }

        String parentName = sa.getNonConfigurationString(
                R.styleable.AndroidManifestActivityAlias_parentActivityName,
                Configuration.NATIVE_CONFIG_VERSION);
        if (parentName != null) {
            String parentClassName = ApkParseUtils.buildClassName(result.getPackageName(),
                    parentName);
            if (parentClassName == null) {
                Log.e(TAG, "Activity alias " + result.className +
                        " specified invalid parentActivityName " + parentName);
                outError[0] = null;
            } else {
                result.parentActivityName = parentClassName;
            }
        }

        // TODO add visibleToInstantApps attribute to activity alias
        final boolean visibleToEphemeral =
                ((result.flags & ActivityInfo.FLAG_VISIBLE_TO_INSTANT_APP) != 0);

        sa.recycle();

        if (outError[0] != null) {
            return null;
        }

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("intent-filter")) {
                ParsedActivityIntentInfo intent = new ParsedActivityIntentInfo(packageName,
                        result.className);
                if (!parseIntentInfo(intent, parsingPackage, res, parser, true /*allowGlobs*/,
                        true /*allowAutoVerify*/, outError)) {
                    return null;
                }
                if (intent.countActions() == 0) {
                    Slog.w(TAG, "No actions in intent filter at "
                            + parsingPackage.getBaseCodePath() + " "
                            + parser.getPositionDescription());
                } else {
                    result.order = Math.max(intent.getOrder(), result.order);
                    result.addIntent(intent);
                }
                // adjust activity flags when we implicitly expose it via a browsable filter
                final int visibility = visibleToEphemeral
                        ? IntentFilter.VISIBILITY_EXPLICIT
                        : isImplicitlyExposedIntent(intent)
                                ? IntentFilter.VISIBILITY_IMPLICIT
                                : IntentFilter.VISIBILITY_NONE;
                intent.setVisibilityToInstantApp(visibility);
                if (intent.isVisibleToInstantApp()) {
                    result.flags |= ActivityInfo.FLAG_VISIBLE_TO_INSTANT_APP;
                }
                if (intent.isImplicitlyVisibleToInstantApp()) {
                    result.flags |= ActivityInfo.FLAG_IMPLICITLY_VISIBLE_TO_INSTANT_APP;
                }
            } else if (tagName.equals("meta-data")) {
                if ((result.metaData = ApkParseUtils.parseMetaData(parsingPackage, res, parser,
                        result.metaData,
                        outError)) == null) {
                    return null;
                }
            } else {
                if (!PackageParser.RIGID_PARSER) {
                    Slog.w(TAG, "Unknown element under <activity-alias>: " + tagName
                            + " at " + parsingPackage.getBaseCodePath() + " "
                            + parser.getPositionDescription());
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else {
                    outError[0] = "Bad element under <activity-alias>: " + tagName;
                    return null;
                }
            }
        }

        if (!setExported) {
            result.exported = result.intents.size() > 0;
        }

        return result;
    }

    public static ParsedPermission parsePermission(
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser,
            String[] outError
    ) throws IOException, XmlPullParserException {
        TypedArray sa = null;
        String packageName = parsingPackage.getPackageName();
        ParsedPermission result = new ParsedPermission();

        try {
            sa = res.obtainAttributes(parser, R.styleable.AndroidManifestPermission);

            String name = sa.getNonConfigurationString(R.styleable.AndroidManifestPermission_name,
                    0);
            if (name == null) {
                outError[0] = "<permission> does not specify android:name";
                return null;
            } else {
                String className = ApkParseUtils.buildClassName(packageName, name);
                if (PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME.equals(className)) {
                    outError[0] = "<permission> invalid android:name";
                    return null;
                } else if (className == null) {
                    outError[0] = "Empty class name in package " + packageName;
                    return null;
                }

                result.className = className;
            }

            int roundIconVal = PackageParser.sUseRoundIcon ? sa.getResourceId(
                    R.styleable.AndroidManifestPermission_roundIcon, 0) : 0;
            if (roundIconVal != 0) {
                result.icon = roundIconVal;
                result.nonLocalizedLabel = null;
            } else {
                int iconVal = sa.getResourceId(R.styleable.AndroidManifestPermission_icon, 0);
                if (iconVal != 0) {
                    result.icon = iconVal;
                    result.nonLocalizedLabel = null;
                }
            }

            int logoVal = sa.getResourceId(R.styleable.AndroidManifestPermission_logo, 0);
            if (logoVal != 0) {
                result.logo = logoVal;
            }

            int bannerVal = sa.getResourceId(R.styleable.AndroidManifestPermission_banner, 0);
            if (bannerVal != 0) {
                result.banner = bannerVal;
            }

            TypedValue v = sa.peekValue(R.styleable.AndroidManifestPermission_label);
            if (v != null && (result.labelRes = v.resourceId) == 0) {
                result.nonLocalizedLabel = v.coerceToString();
            }

            result.setPackageNameInternal(packageName);

            result.descriptionRes = sa.getResourceId(
                    R.styleable.AndroidManifestPermission_description, 0);

            if (sa.hasValue(
                    R.styleable.AndroidManifestPermission_backgroundPermission)) {
                if ("android".equals(packageName)) {
                    result.backgroundPermission = sa.getNonResourceString(
                            R.styleable
                                    .AndroidManifestPermission_backgroundPermission);
                } else {
                    Slog.w(TAG, packageName + " defines a background permission. Only the "
                            + "'android' package can do that.");
                }
            }

            // Note: don't allow this value to be a reference to a resource
            // that may change.
            result.setGroup(sa.getNonResourceString(
                    R.styleable.AndroidManifestPermission_permissionGroup));

            result.requestRes = sa.getResourceId(
                    R.styleable.AndroidManifestPermission_request, 0);

            result.protectionLevel = sa.getInt(
                    R.styleable.AndroidManifestPermission_protectionLevel,
                    PermissionInfo.PROTECTION_NORMAL);

            result.flags = sa.getInt(
                    R.styleable.AndroidManifestPermission_permissionFlags, 0);

            // For now only platform runtime permissions can be restricted
            if (!result.isRuntime() || !"android".equals(result.getPackageName())) {
                result.flags &= ~PermissionInfo.FLAG_HARD_RESTRICTED;
                result.flags &= ~PermissionInfo.FLAG_SOFT_RESTRICTED;
            } else {
                // The platform does not get to specify conflicting permissions
                if ((result.flags & PermissionInfo.FLAG_HARD_RESTRICTED) != 0
                        && (result.flags & PermissionInfo.FLAG_SOFT_RESTRICTED) != 0) {
                    throw new IllegalStateException("Permission cannot be both soft and hard"
                            + " restricted: " + result.getName());
                }
            }

        } finally {
            if (sa != null) {
                sa.recycle();
            }
        }

        if (result.protectionLevel == -1) {
            outError[0] = "<permission> does not specify protectionLevel";
            return null;
        }

        result.protectionLevel = PermissionInfo.fixProtectionLevel(result.protectionLevel);

        if (result.getProtectionFlags() != 0) {
            if ((result.protectionLevel & PermissionInfo.PROTECTION_FLAG_INSTANT) == 0
                    && (result.protectionLevel & PermissionInfo.PROTECTION_FLAG_RUNTIME_ONLY)
                    == 0
                    && (result.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE) !=
                    PermissionInfo.PROTECTION_SIGNATURE) {
                outError[0] = "<permission>  protectionLevel specifies a non-instant flag but is "
                        + "not based on signature type";
                return null;
            }
        }

        boolean success = parseAllMetaData(parsingPackage, res, parser,
                "<permission>", result, outError);
        if (!success || outError[0] != null) {
            return null;
        }

        return result;
    }

    public static ParsedPermission parsePermissionTree(
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser,
            String[] outError
    ) throws IOException, XmlPullParserException {
        TypedArray sa = null;
        String packageName = parsingPackage.getPackageName();
        ParsedPermission result = new ParsedPermission();

        try {
            sa = res.obtainAttributes(parser, R.styleable.AndroidManifestPermissionTree);

            String name = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestPermissionTree_name, 0);
            if (name == null) {
                outError[0] = "<permission-tree> does not specify android:name";
                return null;
            } else {
                String className = ApkParseUtils.buildClassName(packageName, name);
                if (PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME.equals(className)) {
                    outError[0] = "<permission-tree> invalid android:name";
                    return null;
                } else if (className == null) {
                    outError[0] = "Empty class name in package " + packageName;
                    return null;
                }

                result.className = className;
            }

            int roundIconVal = PackageParser.sUseRoundIcon ? sa.getResourceId(
                    R.styleable.AndroidManifestPermissionTree_roundIcon, 0) : 0;
            if (roundIconVal != 0) {
                result.icon = roundIconVal;
                result.nonLocalizedLabel = null;
            } else {
                int iconVal = sa.getResourceId(R.styleable.AndroidManifestPermissionTree_icon, 0);
                if (iconVal != 0) {
                    result.icon = iconVal;
                    result.nonLocalizedLabel = null;
                }
            }

            int logoVal = sa.getResourceId(R.styleable.AndroidManifestPermissionTree_logo, 0);
            if (logoVal != 0) {
                result.logo = logoVal;
            }

            int bannerVal = sa.getResourceId(R.styleable.AndroidManifestPermissionTree_banner, 0);
            if (bannerVal != 0) {
                result.banner = bannerVal;
            }

            TypedValue v = sa.peekValue(R.styleable.AndroidManifestPermissionTree_label);
            if (v != null && (result.labelRes = v.resourceId) == 0) {
                result.nonLocalizedLabel = v.coerceToString();
            }

            result.setPackageNameInternal(packageName);
        } finally {
            if (sa != null) {
                sa.recycle();
            }
        }

        int index = result.getName().indexOf('.');
        if (index > 0) {
            index = result.getName().indexOf('.', index + 1);
        }
        if (index < 0) {
            outError[0] =
                    "<permission-tree> name has less than three segments: " + result.getName();
            return null;
        }

        result.descriptionRes = 0;
        result.requestRes = 0;
        result.protectionLevel = PermissionInfo.PROTECTION_NORMAL;
        result.tree = true;

        boolean success = parseAllMetaData(parsingPackage, res, parser,
                "<permission-tree>", result, outError);
        if (!success || outError[0] != null) {
            return null;
        }

        return result;
    }

    public static ParsedPermissionGroup parsePermissionGroup(
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser,
            String[] outError
    ) throws IOException, XmlPullParserException {
        TypedArray sa = null;
        String packageName = parsingPackage.getPackageName();
        ParsedPermissionGroup result = new ParsedPermissionGroup();

        try {
            sa = res.obtainAttributes(parser, R.styleable.AndroidManifestPermissionGroup);

            String name = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestPermissionGroup_name, 0);
            if (name == null) {
                outError[0] = "<permission> does not specify android:name";
                return null;
            } else {
                String className = ApkParseUtils.buildClassName(packageName, name);
                if (PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME.equals(className)) {
                    outError[0] = "<permission> invalid android:name";
                    return null;
                } else if (className == null) {
                    outError[0] = "Empty class name in package " + packageName;
                    return null;
                }

                result.className = className;
            }

            int roundIconVal = PackageParser.sUseRoundIcon ? sa.getResourceId(
                    R.styleable.AndroidManifestPermissionGroup_roundIcon, 0) : 0;
            if (roundIconVal != 0) {
                result.icon = roundIconVal;
                result.nonLocalizedLabel = null;
            } else {
                int iconVal = sa.getResourceId(R.styleable.AndroidManifestPermissionGroup_icon, 0);
                if (iconVal != 0) {
                    result.icon = iconVal;
                    result.nonLocalizedLabel = null;
                }
            }

            int logoVal = sa.getResourceId(R.styleable.AndroidManifestPermissionGroup_logo, 0);
            if (logoVal != 0) {
                result.logo = logoVal;
            }

            int bannerVal = sa.getResourceId(R.styleable.AndroidManifestPermissionGroup_banner, 0);
            if (bannerVal != 0) {
                result.banner = bannerVal;
            }

            TypedValue v = sa.peekValue(R.styleable.AndroidManifestPermissionGroup_label);
            if (v != null && (result.labelRes = v.resourceId) == 0) {
                result.nonLocalizedLabel = v.coerceToString();
            }

            result.setPackageNameInternal(packageName);

            result.descriptionRes = sa.getResourceId(
                    R.styleable.AndroidManifestPermissionGroup_description, 0);

            result.requestDetailResourceId = sa.getResourceId(
                    R.styleable.AndroidManifestPermissionGroup_requestDetail, 0);
            result.backgroundRequestResourceId = sa.getResourceId(
                    R.styleable.AndroidManifestPermissionGroup_backgroundRequest,
                    0);
            result.backgroundRequestDetailResourceId = sa.getResourceId(
                    R.styleable
                            .AndroidManifestPermissionGroup_backgroundRequestDetail, 0);

            result.requestRes = sa.getResourceId(
                    R.styleable.AndroidManifestPermissionGroup_request, 0);
            result.flags = sa.getInt(
                    R.styleable.AndroidManifestPermissionGroup_permissionGroupFlags,
                    0);
            result.priority = sa.getInt(
                    R.styleable.AndroidManifestPermissionGroup_priority, 0);

        } finally {
            if (sa != null) {
                sa.recycle();
            }
        }

        boolean success = parseAllMetaData(parsingPackage, res, parser,
                "<permission-group>", result, outError);
        if (!success || outError[0] != null) {
            return null;
        }

        return result;
    }

    public static ParsedInstrumentation parseInstrumentation(
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser,
            String[] outError
    ) throws IOException, XmlPullParserException {
        TypedArray sa = null;
        String packageName = parsingPackage.getPackageName();
        ParsedInstrumentation result = new ParsedInstrumentation();

        try {
            sa = res.obtainAttributes(parser, R.styleable.AndroidManifestInstrumentation);

            // TODO(b/135203078): Re-share all of the configuration for this. ParseComponentArgs was
            //  un-used for this, but can be adjusted and re-added to share all the initial result
            //  parsing for icon/logo/name/etc in all of these parse methods.
            String name = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestInstrumentation_name, 0);
            if (name == null) {
                outError[0] = "<instrumentation> does not specify android:name";
                return null;
            } else {
                String className = ApkParseUtils.buildClassName(packageName, name);
                if (PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME.equals(className)) {
                    outError[0] = "<instrumentation> invalid android:name";
                    return null;
                } else if (className == null) {
                    outError[0] = "Empty class name in package " + packageName;
                    return null;
                }

                result.className = className;
            }

            int roundIconVal = PackageParser.sUseRoundIcon ? sa.getResourceId(
                    R.styleable.AndroidManifestInstrumentation_roundIcon, 0) : 0;
            if (roundIconVal != 0) {
                result.icon = roundIconVal;
                result.nonLocalizedLabel = null;
            } else {
                int iconVal = sa.getResourceId(R.styleable.AndroidManifestInstrumentation_icon, 0);
                if (iconVal != 0) {
                    result.icon = iconVal;
                    result.nonLocalizedLabel = null;
                }
            }

            int logoVal = sa.getResourceId(R.styleable.AndroidManifestInstrumentation_logo, 0);
            if (logoVal != 0) {
                result.logo = logoVal;
            }

            int bannerVal = sa.getResourceId(R.styleable.AndroidManifestInstrumentation_banner, 0);
            if (bannerVal != 0) {
                result.banner = bannerVal;
            }

            TypedValue v = sa.peekValue(R.styleable.AndroidManifestInstrumentation_label);
            if (v != null && (result.labelRes = v.resourceId) == 0) {
                result.nonLocalizedLabel = v.coerceToString();
            }

            result.setPackageNameInternal(packageName);

            String str;
            // Note: don't allow this value to be a reference to a resource
            // that may change.
            str = sa.getNonResourceString(R.styleable.AndroidManifestInstrumentation_targetPackage);
            result.setTargetPackage(str);

            str = sa.getNonResourceString(
                    R.styleable.AndroidManifestInstrumentation_targetProcesses);
            result.setTargetProcesses(str);
            result.handleProfiling = sa.getBoolean(
                    R.styleable.AndroidManifestInstrumentation_handleProfiling, false);
            result.functionalTest = sa.getBoolean(
                    R.styleable.AndroidManifestInstrumentation_functionalTest, false);

        } finally {
            if (sa != null) {
                sa.recycle();
            }
        }

        boolean success = parseAllMetaData(parsingPackage, res, parser,
                "<instrumentation>", result, outError);
        if (!success || outError[0] != null) {
            return null;
        }

        return result;
    }

    public static ActivityInfo.WindowLayout parseLayout(Resources res, AttributeSet attrs) {
        TypedArray sw = res.obtainAttributes(attrs,
                R.styleable.AndroidManifestLayout);
        int width = -1;
        float widthFraction = -1f;
        int height = -1;
        float heightFraction = -1f;
        final int widthType = sw.getType(
                R.styleable.AndroidManifestLayout_defaultWidth);
        if (widthType == TypedValue.TYPE_FRACTION) {
            widthFraction = sw.getFraction(
                    R.styleable.AndroidManifestLayout_defaultWidth,
                    1, 1, -1);
        } else if (widthType == TypedValue.TYPE_DIMENSION) {
            width = sw.getDimensionPixelSize(
                    R.styleable.AndroidManifestLayout_defaultWidth,
                    -1);
        }
        final int heightType = sw.getType(
                R.styleable.AndroidManifestLayout_defaultHeight);
        if (heightType == TypedValue.TYPE_FRACTION) {
            heightFraction = sw.getFraction(
                    R.styleable.AndroidManifestLayout_defaultHeight,
                    1, 1, -1);
        } else if (heightType == TypedValue.TYPE_DIMENSION) {
            height = sw.getDimensionPixelSize(
                    R.styleable.AndroidManifestLayout_defaultHeight,
                    -1);
        }
        int gravity = sw.getInt(
                R.styleable.AndroidManifestLayout_gravity,
                Gravity.CENTER);
        int minWidth = sw.getDimensionPixelSize(
                R.styleable.AndroidManifestLayout_minWidth,
                -1);
        int minHeight = sw.getDimensionPixelSize(
                R.styleable.AndroidManifestLayout_minHeight,
                -1);
        sw.recycle();
        return new ActivityInfo.WindowLayout(width, widthFraction,
                height, heightFraction, gravity, minWidth, minHeight);
    }

    public static boolean parseIntentInfo(
            ParsedIntentInfo intentInfo,
            ParsingPackage parsingPackage,
            Resources res, XmlResourceParser parser, boolean allowGlobs,
            boolean allowAutoVerify, String[] outError
    ) throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(parser,
                R.styleable.AndroidManifestIntentFilter);

        int priority = sa.getInt(
                R.styleable.AndroidManifestIntentFilter_priority, 0);
        intentInfo.setPriority(priority);

        int order = sa.getInt(
                R.styleable.AndroidManifestIntentFilter_order, 0);
        intentInfo.setOrder(order);

        TypedValue v = sa.peekValue(
                R.styleable.AndroidManifestIntentFilter_label);
        if (v != null && (intentInfo.labelRes = v.resourceId) == 0) {
            intentInfo.nonLocalizedLabel = v.coerceToString();
        }

        int roundIconVal = PackageParser.sUseRoundIcon ? sa.getResourceId(
                R.styleable.AndroidManifestIntentFilter_roundIcon, 0) : 0;
        if (roundIconVal != 0) {
            intentInfo.icon = roundIconVal;
        } else {
            intentInfo.icon = sa.getResourceId(
                    R.styleable.AndroidManifestIntentFilter_icon, 0);
        }

        if (allowAutoVerify) {
            intentInfo.setAutoVerify(sa.getBoolean(
                    R.styleable.AndroidManifestIntentFilter_autoVerify,
                    false));
        }

        sa.recycle();

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String nodeName = parser.getName();
            if (nodeName.equals("action")) {
                String value = parser.getAttributeValue(
                        PackageParser.ANDROID_RESOURCES, "name");
                if (TextUtils.isEmpty(value)) {
                    outError[0] = "No value supplied for <android:name>";
                    return false;
                }
                XmlUtils.skipCurrentTag(parser);

                intentInfo.addAction(value);
            } else if (nodeName.equals("category")) {
                String value = parser.getAttributeValue(
                        PackageParser.ANDROID_RESOURCES, "name");
                if (TextUtils.isEmpty(value)) {
                    outError[0] = "No value supplied for <android:name>";
                    return false;
                }
                XmlUtils.skipCurrentTag(parser);

                intentInfo.addCategory(value);

            } else if (nodeName.equals("data")) {
                sa = res.obtainAttributes(parser,
                        R.styleable.AndroidManifestData);

                String str = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestData_mimeType, 0);
                if (str != null) {
                    try {
                        intentInfo.addRawDataType(str);
                    } catch (IntentFilter.MalformedMimeTypeException e) {
                        outError[0] = e.toString();
                        sa.recycle();
                        return false;
                    }
                }

                str = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestData_scheme, 0);
                if (str != null) {
                    intentInfo.addDataScheme(str);
                }

                str = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestData_ssp, 0);
                if (str != null) {
                    intentInfo.addDataSchemeSpecificPart(str, PatternMatcher.PATTERN_LITERAL);
                }

                str = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestData_sspPrefix, 0);
                if (str != null) {
                    intentInfo.addDataSchemeSpecificPart(str, PatternMatcher.PATTERN_PREFIX);
                }

                str = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestData_sspPattern, 0);
                if (str != null) {
                    if (!allowGlobs) {
                        outError[0] = "sspPattern not allowed here; ssp must be literal";
                        return false;
                    }
                    intentInfo.addDataSchemeSpecificPart(str, PatternMatcher.PATTERN_SIMPLE_GLOB);
                }

                String host = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestData_host, 0);
                String port = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestData_port, 0);
                if (host != null) {
                    intentInfo.addDataAuthority(host, port);
                }

                str = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestData_path, 0);
                if (str != null) {
                    intentInfo.addDataPath(str, PatternMatcher.PATTERN_LITERAL);
                }

                str = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestData_pathPrefix, 0);
                if (str != null) {
                    intentInfo.addDataPath(str, PatternMatcher.PATTERN_PREFIX);
                }

                str = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestData_pathPattern, 0);
                if (str != null) {
                    if (!allowGlobs) {
                        outError[0] = "pathPattern not allowed here; path must be literal";
                        return false;
                    }
                    intentInfo.addDataPath(str, PatternMatcher.PATTERN_SIMPLE_GLOB);
                }

                str = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestData_pathAdvancedPattern, 0);
                if (str != null) {
                    if (!allowGlobs) {
                        outError[0] = "pathAdvancedPattern not allowed here; path must be literal";
                        return false;
                    }
                    intentInfo.addDataPath(str, PatternMatcher.PATTERN_ADVANCED_GLOB);
                }

                sa.recycle();
                XmlUtils.skipCurrentTag(parser);
            } else if (!PackageParser.RIGID_PARSER) {
                Slog.w(TAG, "Unknown element under <intent-filter>: "
                        + parser.getName() + " at " + parsingPackage.getBaseCodePath() + " "
                        + parser.getPositionDescription());
                XmlUtils.skipCurrentTag(parser);
            } else {
                outError[0] = "Bad element under <intent-filter>: " + parser.getName();
                return false;
            }
        }

        intentInfo.hasDefault = intentInfo.hasCategory(Intent.CATEGORY_DEFAULT);

        if (PackageParser.DEBUG_PARSER) {
            final StringBuilder cats = new StringBuilder("Intent d=");
            cats.append(intentInfo.hasDefault);
            cats.append(", cat=");

            final Iterator<String> it = intentInfo.categoriesIterator();
            if (it != null) {
                while (it.hasNext()) {
                    cats.append(' ');
                    cats.append(it.next());
                }
            }
            Slog.d(TAG, cats.toString());
        }

        return true;
    }

    private static boolean parseAllMetaData(
            ParsingPackage parsingPackage,
            Resources res, XmlResourceParser parser, String tag,
            ParsedComponent outInfo,
            String[] outError
    ) throws XmlPullParserException, IOException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getName().equals("meta-data")) {
                if ((outInfo.metaData = ApkParseUtils.parseMetaData(parsingPackage, res, parser,
                        outInfo.metaData, outError)) == null) {
                    return false;
                }
            } else {
                if (!PackageParser.RIGID_PARSER) {
                    Slog.w(TAG, "Unknown element under " + tag + ": "
                            + parser.getName() + " at " + parsingPackage.getBaseCodePath() + " "
                            + parser.getPositionDescription());
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else {
                    outError[0] = "Bad element under " + tag + ": " + parser.getName();
                }
            }
        }

        return true;
    }

    public static boolean isImplicitlyExposedIntent(IntentFilter intent) {
        return intent.hasCategory(Intent.CATEGORY_BROWSABLE)
                || intent.hasAction(Intent.ACTION_SEND)
                || intent.hasAction(Intent.ACTION_SENDTO)
                || intent.hasAction(Intent.ACTION_SEND_MULTIPLE);
    }
}
