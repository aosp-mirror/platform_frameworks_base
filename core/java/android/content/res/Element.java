/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.content.res;

import android.annotation.NonNull;
import android.util.Pools.SimplePool;

import androidx.annotation.StyleableRes;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/**
 * Defines the string attribute length and child tag count restrictions for a xml element.
 *
 * {@hide}
 */
public class Element {
    private static final int DEFAULT_MAX_STRING_ATTR_LENGTH = 32_768;
    private static final int MAX_POOL_SIZE = 128;

    private static final String ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android";

    protected static final String TAG_ACTION = "action";
    protected static final String TAG_ACTIVITY = "activity";
    protected static final String TAG_ADOPT_PERMISSIONS = "adopt-permissions";
    protected static final String TAG_ACTIVITY_ALIAS = "activity-alias";
    protected static final String TAG_APPLICATION = "application";
    protected static final String TAG_ATTRIBUTION = "attribution";
    protected static final String TAG_CATEGORY = "category";
    protected static final String TAG_COMPATIBLE_SCREENS = "compatible-screens";
    protected static final String TAG_DATA = "data";
    protected static final String TAG_EAT_COMMENT = "eat-comment";
    protected static final String TAG_FEATURE_GROUP = "feature-group";
    protected static final String TAG_GRANT_URI_PERMISSION = "grant-uri-permission";
    protected static final String TAG_INSTRUMENTATION = "instrumentation";
    protected static final String TAG_INTENT = "intent";
    protected static final String TAG_INTENT_FILTER = "intent-filter";
    protected static final String TAG_KEY_SETS = "key-sets";
    protected static final String TAG_LAYOUT = "layout";
    protected static final String TAG_MANIFEST = "manifest";
    protected static final String TAG_META_DATA = "meta-data";
    protected static final String TAG_ORIGINAL_PACKAGE = "original-package";
    protected static final String TAG_OVERLAY = "overlay";
    protected static final String TAG_PACKAGE = "package";
    protected static final String TAG_PACKAGE_VERIFIER = "package-verifier";
    protected static final String TAG_PATH_PERMISSION = "path-permission";
    protected static final String TAG_PERMISSION = "permission";
    protected static final String TAG_PERMISSION_GROUP = "permission-group";
    protected static final String TAG_PERMISSION_TREE = "permission-tree";
    protected static final String TAG_PROFILEABLE = "profileable";
    protected static final String TAG_PROTECTED_BROADCAST = "protected-broadcast";
    protected static final String TAG_PROPERTY = "property";
    protected static final String TAG_PROVIDER = "provider";
    protected static final String TAG_QUERIES = "queries";
    protected static final String TAG_RECEIVER = "receiver";
    protected static final String TAG_RESTRICT_UPDATE = "restrict-update";
    protected static final String TAG_SCREEN = "screen";
    protected static final String TAG_SERVICE = "service";
    protected static final String TAG_SUPPORT_SCREENS = "supports-screens";
    protected static final String TAG_SUPPORTS_GL_TEXTURE = "supports-gl-texture";
    protected static final String TAG_SUPPORTS_INPUT = "supports-input";
    protected static final String TAG_SUPPORTS_SCREENS = "supports-screens";
    protected static final String TAG_USES_CONFIGURATION = "uses-configuration";
    protected static final String TAG_USES_FEATURE = "uses-feature";
    protected static final String TAG_USES_GL_TEXTURE = "uses-gl-texture";
    protected static final String TAG_USES_LIBRARY = "uses-library";
    protected static final String TAG_USES_NATIVE_LIBRARY = "uses-native-library";
    protected static final String TAG_USES_PERMISSION = "uses-permission";
    protected static final String TAG_USES_PERMISSION_SDK_23 = "uses-permission-sdk-23";
    protected static final String TAG_USES_PERMISSION_SDK_M = "uses-permission-sdk-m";
    protected static final String TAG_USES_SDK = "uses-sdk";
    protected static final String TAG_USES_SPLIT = "uses-split";

    protected static final String TAG_ATTR_BACKUP_AGENT = "backupAgent";
    protected static final String TAG_ATTR_CATEGORY = "category";
    protected static final String TAG_ATTR_HOST = "host";
    protected static final String TAG_ATTR_MANAGE_SPACE_ACTIVITY = "manageSpaceActivity";
    protected static final String TAG_ATTR_MIMETYPE = "mimeType";
    protected static final String TAG_ATTR_NAME = "name";
    protected static final String TAG_ATTR_PACKAGE = "package";
    protected static final String TAG_ATTR_PATH = "path";
    protected static final String TAG_ATTR_PATH_ADVANCED_PATTERN = "pathAdvancedPattern";
    protected static final String TAG_ATTR_PATH_PATTERN = "pathPattern";
    protected static final String TAG_ATTR_PATH_PREFIX = "pathPrefix";
    protected static final String TAG_ATTR_PATH_SUFFIX = "pathSuffix";
    protected static final String TAG_ATTR_PARENT_ACTIVITY_NAME = "parentActivityName";
    protected static final String TAG_ATTR_PERMISSION = "permission";
    protected static final String TAG_ATTR_PERMISSION_GROUP = "permissionGroup";
    protected static final String TAG_ATTR_PORT = "port";
    protected static final String TAG_ATTR_PROCESS = "process";
    protected static final String TAG_ATTR_READ_PERMISSION = "readPermission";
    protected static final String TAG_ATTR_REQUIRED_ACCOUNT_TYPE = "requiredAccountType";
    protected static final String TAG_ATTR_REQUIRED_SYSTEM_PROPERTY_NAME =
            "requiredSystemPropertyName";
    protected static final String TAG_ATTR_REQUIRED_SYSTEM_PROPERTY_VALUE =
            "requiredSystemPropertyValue";
    protected static final String TAG_ATTR_RESTRICTED_ACCOUNT_TYPE = "restrictedAccountType";
    protected static final String TAG_ATTR_SCHEME = "scheme";
    protected static final String TAG_ATTR_SHARED_USER_ID = "sharedUserId";
    protected static final String TAG_ATTR_TARGET_ACTIVITY = "targetActivity";
    protected static final String TAG_ATTR_TARGET_NAME = "targetName";
    protected static final String TAG_ATTR_TARGET_PACKAGE = "targetPackage";
    protected static final String TAG_ATTR_TARGET_PROCESSES = "targetProcesses";
    protected static final String TAG_ATTR_TASK_AFFINITY = "taskAffinity";
    protected static final String TAG_ATTR_VALUE = "value";
    protected static final String TAG_ATTR_VERSION_NAME = "versionName";
    protected static final String TAG_ATTR_WRITE_PERMISSION = "writePermission";

    private static final String[] ACTIVITY_STR_ATTR_NAMES = {TAG_ATTR_NAME,
            TAG_ATTR_PARENT_ACTIVITY_NAME, TAG_ATTR_PERMISSION, TAG_ATTR_PROCESS,
            TAG_ATTR_TASK_AFFINITY};
    private static final String[] ACTIVITY_ALIAS_STR_ATTR_NAMES = {TAG_ATTR_NAME,
            TAG_ATTR_PERMISSION, TAG_ATTR_TARGET_ACTIVITY};
    private static final String[] APPLICATION_STR_ATTR_NAMES = {TAG_ATTR_BACKUP_AGENT,
            TAG_ATTR_MANAGE_SPACE_ACTIVITY, TAG_ATTR_NAME, TAG_ATTR_PERMISSION, TAG_ATTR_PROCESS,
            TAG_ATTR_REQUIRED_ACCOUNT_TYPE, TAG_ATTR_RESTRICTED_ACCOUNT_TYPE,
            TAG_ATTR_TASK_AFFINITY};
    private static final String[] DATA_STR_ATTR_NAMES = {TAG_ATTR_SCHEME, TAG_ATTR_HOST,
            TAG_ATTR_PORT, TAG_ATTR_PATH, TAG_ATTR_PATH_PATTERN, TAG_ATTR_PATH_PREFIX,
            TAG_ATTR_PATH_SUFFIX, TAG_ATTR_PATH_ADVANCED_PATTERN, TAG_ATTR_MIMETYPE};
    private static final String[] GRANT_URI_PERMISSION_STR_ATTR_NAMES = {TAG_ATTR_PATH,
            TAG_ATTR_PATH_PATTERN, TAG_ATTR_PATH_PREFIX};
    private static final String[] INSTRUMENTATION_STR_ATTR_NAMES = {TAG_ATTR_NAME,
            TAG_ATTR_TARGET_PACKAGE, TAG_ATTR_TARGET_PROCESSES};
    private static final String[] MANIFEST_STR_ATTR_NAMES = {TAG_ATTR_PACKAGE,
            TAG_ATTR_SHARED_USER_ID, TAG_ATTR_VERSION_NAME};
    private static final String[] OVERLAY_STR_ATTR_NAMES = {TAG_ATTR_CATEGORY,
            TAG_ATTR_REQUIRED_SYSTEM_PROPERTY_NAME, TAG_ATTR_REQUIRED_SYSTEM_PROPERTY_VALUE,
            TAG_ATTR_TARGET_PACKAGE, TAG_ATTR_TARGET_NAME};
    private static final String[] PATH_PERMISSION_STR_ATTR_NAMES = {TAG_ATTR_PATH,
            TAG_ATTR_PATH_PREFIX, TAG_ATTR_PATH_PATTERN, TAG_ATTR_PERMISSION,
            TAG_ATTR_READ_PERMISSION, TAG_ATTR_WRITE_PERMISSION};
    private static final String[] PERMISSION_STR_ATTR_NAMES = {TAG_ATTR_NAME,
            TAG_ATTR_PERMISSION_GROUP};
    private static final String[] PROVIDER_STR_ATTR_NAMES = {TAG_ATTR_NAME, TAG_ATTR_PERMISSION,
            TAG_ATTR_PROCESS, TAG_ATTR_READ_PERMISSION, TAG_ATTR_WRITE_PERMISSION};
    private static final String[] RECEIVER_SERVICE_STR_ATTR_NAMES = {TAG_ATTR_NAME,
            TAG_ATTR_PERMISSION, TAG_ATTR_PROCESS};
    private static final String[] NAME_ATTR = {TAG_ATTR_NAME};
    private static final String[] NAME_VALUE_ATTRS = {TAG_ATTR_NAME, TAG_ATTR_VALUE};

    private String[] mStringAttrNames = new String[0];
    // The length of mTagCounters corresponds to the number of tags defined in getCounterIdx. If new
    // tags are added then the size here should be increased to match.
    private final TagCounter[] mTagCounters = new TagCounter[35];

    String mTag;

    private static final ThreadLocal<SimplePool<Element>> sPool =
            ThreadLocal.withInitial(() -> new SimplePool<>(MAX_POOL_SIZE));

    @NonNull
    static Element obtain(@NonNull String tag) {
        Element element = sPool.get().acquire();
        if (element == null) {
            element = new Element();
        }
        element.init(tag);
        return element;
    }

    void recycle() {
        mStringAttrNames = new String[0];
        mTag = null;
        sPool.get().release(this);
    }

    private long mChildTagMask = 0;

    private static int getCounterIdx(String tag) {
        switch(tag) {
            case TAG_LAYOUT:
                return 0;
            case TAG_META_DATA:
                return 1;
            case TAG_INTENT_FILTER:
                return 2;
            case TAG_PROFILEABLE:
                return 3;
            case TAG_USES_NATIVE_LIBRARY:
                return 4;
            case TAG_RECEIVER:
                return 5;
            case TAG_SERVICE:
                return 6;
            case TAG_ACTIVITY_ALIAS:
                return 7;
            case TAG_USES_LIBRARY:
                return 8;
            case TAG_PROVIDER:
                return 9;
            case TAG_ACTIVITY:
                return 10;
            case TAG_ACTION:
                return 11;
            case TAG_CATEGORY:
                return 12;
            case TAG_DATA:
                return 13;
            case TAG_APPLICATION:
                return 14;
            case TAG_OVERLAY:
                return 15;
            case TAG_INSTRUMENTATION:
                return 16;
            case TAG_PERMISSION_GROUP:
                return 17;
            case TAG_PERMISSION_TREE:
                return 18;
            case TAG_SUPPORTS_GL_TEXTURE:
                return 19;
            case TAG_SUPPORTS_SCREENS:
                return 20;
            case TAG_USES_CONFIGURATION:
                return 21;
            case TAG_USES_PERMISSION_SDK_23:
                return 22;
            case TAG_USES_SDK:
                return 23;
            case TAG_COMPATIBLE_SCREENS:
                return 24;
            case TAG_QUERIES:
                return 25;
            case TAG_ATTRIBUTION:
                return 26;
            case TAG_USES_FEATURE:
                return 27;
            case TAG_PERMISSION:
                return 28;
            case TAG_USES_PERMISSION:
                return 29;
            case TAG_GRANT_URI_PERMISSION:
                return 30;
            case TAG_PATH_PERMISSION:
                return 31;
            case TAG_PACKAGE:
                return 32;
            case TAG_INTENT:
                return 33;
            default:
                // The size of the mTagCounters array should be equal to this value+1
                return 34;
        }
    }

    private void init(String tag) {
        this.mTag = tag;
        mChildTagMask = 0;
        switch (tag) {
            case TAG_ACTION:
            case TAG_CATEGORY:
            case TAG_PACKAGE:
            case TAG_PERMISSION_GROUP:
            case TAG_PERMISSION_TREE:
            case TAG_SUPPORTS_GL_TEXTURE:
            case TAG_USES_FEATURE:
            case TAG_USES_LIBRARY:
            case TAG_USES_NATIVE_LIBRARY:
            case TAG_USES_PERMISSION:
            case TAG_USES_PERMISSION_SDK_23:
            case TAG_USES_SDK:
                setStringAttrNames(NAME_ATTR);
                break;
            case TAG_ACTIVITY:
                setStringAttrNames(ACTIVITY_STR_ATTR_NAMES);
                initializeCounter(TAG_LAYOUT, 1000);
                initializeCounter(TAG_META_DATA, 8000);
                initializeCounter(TAG_INTENT_FILTER, 20000);
                break;
            case TAG_ACTIVITY_ALIAS:
                setStringAttrNames(ACTIVITY_ALIAS_STR_ATTR_NAMES);
                initializeCounter(TAG_META_DATA, 8000);
                initializeCounter(TAG_INTENT_FILTER, 20000);
                break;
            case TAG_APPLICATION:
                setStringAttrNames(APPLICATION_STR_ATTR_NAMES);
                initializeCounter(TAG_PROFILEABLE, 100);
                initializeCounter(TAG_USES_NATIVE_LIBRARY, 100);
                initializeCounter(TAG_RECEIVER, 1000);
                initializeCounter(TAG_SERVICE, 1000);
                initializeCounter(TAG_ACTIVITY_ALIAS, 4000);
                initializeCounter(TAG_USES_LIBRARY, 4000);
                initializeCounter(TAG_PROVIDER, 8000);
                initializeCounter(TAG_META_DATA, 8000);
                initializeCounter(TAG_ACTIVITY, 40000);
                break;
            case TAG_COMPATIBLE_SCREENS:
                initializeCounter(TAG_SCREEN, 4000);
                break;
            case TAG_DATA:
                setStringAttrNames(DATA_STR_ATTR_NAMES);
                break;
            case TAG_GRANT_URI_PERMISSION:
                setStringAttrNames(GRANT_URI_PERMISSION_STR_ATTR_NAMES);
                break;
            case TAG_INSTRUMENTATION:
                setStringAttrNames(INSTRUMENTATION_STR_ATTR_NAMES);
                break;
            case TAG_INTENT:
            case TAG_INTENT_FILTER:
                initializeCounter(TAG_ACTION, 20000);
                initializeCounter(TAG_CATEGORY, 40000);
                initializeCounter(TAG_DATA, 40000);
                break;
            case TAG_MANIFEST:
                setStringAttrNames(MANIFEST_STR_ATTR_NAMES);
                initializeCounter(TAG_APPLICATION, 100);
                initializeCounter(TAG_OVERLAY, 100);
                initializeCounter(TAG_INSTRUMENTATION, 100);
                initializeCounter(TAG_PERMISSION_GROUP, 100);
                initializeCounter(TAG_PERMISSION_TREE, 100);
                initializeCounter(TAG_SUPPORTS_GL_TEXTURE, 100);
                initializeCounter(TAG_SUPPORTS_SCREENS, 100);
                initializeCounter(TAG_USES_CONFIGURATION, 100);
                initializeCounter(TAG_USES_PERMISSION_SDK_23, 100);
                initializeCounter(TAG_USES_SDK, 100);
                initializeCounter(TAG_COMPATIBLE_SCREENS, 200);
                initializeCounter(TAG_QUERIES, 200);
                initializeCounter(TAG_ATTRIBUTION, 400);
                initializeCounter(TAG_USES_FEATURE, 400);
                initializeCounter(TAG_PERMISSION, 2000);
                initializeCounter(TAG_USES_PERMISSION, 20000);
                break;
            case TAG_META_DATA:
            case TAG_PROPERTY:
                setStringAttrNames(NAME_VALUE_ATTRS);
                break;
            case TAG_OVERLAY:
                setStringAttrNames(OVERLAY_STR_ATTR_NAMES);
                break;
            case TAG_PATH_PERMISSION:
                setStringAttrNames(PATH_PERMISSION_STR_ATTR_NAMES);
                break;
            case TAG_PERMISSION:
                setStringAttrNames(PERMISSION_STR_ATTR_NAMES);
                break;
            case TAG_PROVIDER:
                setStringAttrNames(PROVIDER_STR_ATTR_NAMES);
                initializeCounter(TAG_GRANT_URI_PERMISSION, 100);
                initializeCounter(TAG_PATH_PERMISSION, 100);
                initializeCounter(TAG_META_DATA, 8000);
                initializeCounter(TAG_INTENT_FILTER, 20000);
                break;
            case TAG_QUERIES:
                initializeCounter(TAG_PACKAGE, 1000);
                initializeCounter(TAG_INTENT, 2000);
                initializeCounter(TAG_PROVIDER, 8000);
                break;
            case TAG_RECEIVER:
            case TAG_SERVICE:
                setStringAttrNames(RECEIVER_SERVICE_STR_ATTR_NAMES);
                initializeCounter(TAG_META_DATA, 8000);
                initializeCounter(TAG_INTENT_FILTER, 20000);
                break;
        }
    }

    private void setStringAttrNames(String[] attrNames) {
        mStringAttrNames = attrNames;
    }

    private static String getAttrNamespace(String attrName) {
        if (attrName.equals(TAG_ATTR_PACKAGE)) {
            return null;
        }
        return ANDROID_NAMESPACE;
    }

    private static int getAttrStringMaxLength(String attrName) {
        switch (attrName) {
            case TAG_ATTR_HOST:
            case TAG_ATTR_PACKAGE:
            case TAG_ATTR_PERMISSION_GROUP:
            case TAG_ATTR_PORT:
            case TAG_ATTR_REQUIRED_SYSTEM_PROPERTY_VALUE:
            case TAG_ATTR_SCHEME:
            case TAG_ATTR_SHARED_USER_ID:
            case TAG_ATTR_TARGET_PACKAGE:
                return 256;
            case TAG_ATTR_MIMETYPE:
                return 512;
            case TAG_ATTR_BACKUP_AGENT:
            case TAG_ATTR_CATEGORY:
            case TAG_ATTR_MANAGE_SPACE_ACTIVITY:
            case TAG_ATTR_NAME:
            case TAG_ATTR_PARENT_ACTIVITY_NAME:
            case TAG_ATTR_PERMISSION:
            case TAG_ATTR_PROCESS:
            case TAG_ATTR_READ_PERMISSION:
            case TAG_ATTR_REQUIRED_ACCOUNT_TYPE:
            case TAG_ATTR_RESTRICTED_ACCOUNT_TYPE:
            case TAG_ATTR_TARGET_ACTIVITY:
            case TAG_ATTR_TARGET_NAME:
            case TAG_ATTR_TARGET_PROCESSES:
            case TAG_ATTR_TASK_AFFINITY:
            case TAG_ATTR_WRITE_PERMISSION:
                return 1024;
            case TAG_ATTR_PATH:
            case TAG_ATTR_PATH_ADVANCED_PATTERN:
            case TAG_ATTR_PATH_PATTERN:
            case TAG_ATTR_PATH_PREFIX:
            case TAG_ATTR_PATH_SUFFIX:
            case TAG_ATTR_VERSION_NAME:
                return 4000;
            default:
                return DEFAULT_MAX_STRING_ATTR_LENGTH;
        }
    }

    private static int getResStringMaxLength(@StyleableRes int index) {
        switch (index) {
            case R.styleable.AndroidManifestData_host:
            case R.styleable.AndroidManifestData_port:
            case R.styleable.AndroidManifestData_scheme:
                return 255;
            case R.styleable.AndroidManifestData_mimeType:
                return 512;
            default:
                return DEFAULT_MAX_STRING_ATTR_LENGTH;
        }
    }

    private void initializeCounter(String tag, int max) {
        int idx = getCounterIdx(tag);
        if (mTagCounters[idx] == null) {
            mTagCounters[idx] = new TagCounter();
        }
        mTagCounters[idx].reset(max);
        mChildTagMask |= 1 << idx;
    }

    boolean hasChild(String tag) {
        return (mChildTagMask & (1 << getCounterIdx(tag))) != 0;
    }

    void validateStringAttrs(@NonNull XmlPullParser attrs) throws XmlPullParserException {
        for (int i = 0; i < mStringAttrNames.length; i++) {
            String attrName = mStringAttrNames[i];
            String val = attrs.getAttributeValue(getAttrNamespace(attrName), attrName);
            if (val != null && val.length() > getAttrStringMaxLength(attrName)) {
                throw new XmlPullParserException("String length limit exceeded for "
                        + "attribute " + attrName + " in " + mTag);
            }
        }
    }

    void validateResStringAttr(@StyleableRes int index, CharSequence stringValue)
            throws XmlPullParserException {
        if (stringValue != null && stringValue.length() > getResStringMaxLength(index)) {
            throw new XmlPullParserException("String length limit exceeded for "
                    + "attribute in " + mTag);
        }
    }

    void seen(@NonNull Element element) throws XmlPullParserException {
        TagCounter counter = mTagCounters[getCounterIdx(element.mTag)];
        if (counter != null) {
            counter.increment();
            if (!counter.isValid()) {
                throw new XmlPullParserException("The number of child " + element.mTag
                        + " elements exceeded the max allowed in " + this.mTag);
            }
        }
    }
}
