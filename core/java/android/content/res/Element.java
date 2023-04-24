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
import android.util.ArrayMap;
import android.util.Pools.SimplePool;

import androidx.annotation.StyleableRes;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.util.Iterator;
import java.util.Map;

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
    private final Map<String, TagCounter> mTagCounters = new ArrayMap<>();

    private String mTag;

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
        Iterator<Map.Entry<String, TagCounter>> it = mTagCounters.entrySet().iterator();
        while (it.hasNext()) {
            it.next().getValue().recycle();
            it.remove();
        }
        mTag = null;
        sPool.get().release(this);
    }

    private void init(String tag) {
        this.mTag = tag;
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
                addTagCounter(1000, TAG_LAYOUT);
                addTagCounter(8000, TAG_META_DATA);
                addTagCounter(20000, TAG_INTENT_FILTER);
                break;
            case TAG_ACTIVITY_ALIAS:
                setStringAttrNames(ACTIVITY_ALIAS_STR_ATTR_NAMES);
                addTagCounter(8000, TAG_META_DATA);
                addTagCounter(20000, TAG_INTENT_FILTER);
                break;
            case TAG_APPLICATION:
                setStringAttrNames(APPLICATION_STR_ATTR_NAMES);
                addTagCounter(100, TAG_PROFILEABLE);
                addTagCounter(100, TAG_USES_NATIVE_LIBRARY);
                addTagCounter(1000, TAG_RECEIVER);
                addTagCounter(1000, TAG_SERVICE);
                addTagCounter(4000, TAG_ACTIVITY_ALIAS);
                addTagCounter(4000, TAG_USES_LIBRARY);
                addTagCounter(8000, TAG_PROVIDER);
                addTagCounter(8000, TAG_META_DATA);
                addTagCounter(40000, TAG_ACTIVITY);
                break;
            case TAG_COMPATIBLE_SCREENS:
                addTagCounter(4000, TAG_SCREEN);
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
                addTagCounter(20000, TAG_ACTION);
                addTagCounter(40000, TAG_CATEGORY);
                addTagCounter(40000, TAG_DATA);
                break;
            case TAG_MANIFEST:
                setStringAttrNames(MANIFEST_STR_ATTR_NAMES);
                addTagCounter(100, TAG_APPLICATION);
                addTagCounter(100, TAG_OVERLAY);
                addTagCounter(100, TAG_INSTRUMENTATION);
                addTagCounter(100, TAG_PERMISSION_GROUP);
                addTagCounter(100, TAG_PERMISSION_TREE);
                addTagCounter(100, TAG_SUPPORTS_GL_TEXTURE);
                addTagCounter(100, TAG_SUPPORTS_SCREENS);
                addTagCounter(100, TAG_USES_CONFIGURATION);
                addTagCounter(100, TAG_USES_PERMISSION_SDK_23);
                addTagCounter(100, TAG_USES_SDK);
                addTagCounter(200, TAG_COMPATIBLE_SCREENS);
                addTagCounter(200, TAG_QUERIES);
                addTagCounter(400, TAG_ATTRIBUTION);
                addTagCounter(400, TAG_USES_FEATURE);
                addTagCounter(2000, TAG_PERMISSION);
                addTagCounter(20000, TAG_USES_PERMISSION);
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
                addTagCounter(100, TAG_GRANT_URI_PERMISSION);
                addTagCounter(100, TAG_PATH_PERMISSION);
                addTagCounter(8000, TAG_META_DATA);
                addTagCounter(20000, TAG_INTENT_FILTER);
                break;
            case TAG_QUERIES:
                addTagCounter(1000, TAG_PACKAGE);
                addTagCounter(2000, TAG_INTENT);
                addTagCounter(8000, TAG_PROVIDER);
                break;
            case TAG_RECEIVER:
            case TAG_SERVICE:
                setStringAttrNames(RECEIVER_SERVICE_STR_ATTR_NAMES);
                addTagCounter(8000, TAG_META_DATA);
                addTagCounter(20000, TAG_INTENT_FILTER);
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

    private void addTagCounter(int max, String tag) {
        mTagCounters.put(tag, TagCounter.obtain(max));
    }

    boolean hasChild(String tag) {
        return mTagCounters.containsKey(tag);
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
        if (mTagCounters.containsKey(element.mTag)) {
            TagCounter counter = mTagCounters.get(element.mTag);
            counter.increment();
            if (!counter.isValid()) {
                throw new XmlPullParserException("The number of child " + element.mTag
                        + " elements exceeded the max allowed in " + this.mTag);
            }
        }
    }
}
