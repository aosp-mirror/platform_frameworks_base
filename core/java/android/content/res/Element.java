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

import static android.os.SystemProperties.PROP_VALUE_MAX;

import android.annotation.NonNull;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;
import android.util.Pools.SimplePool;
import android.util.Slog;

import androidx.annotation.StyleableRes;

import com.android.internal.R;

import java.util.Set;

/**
 * Defines the string attribute length and child tag count restrictions for a xml element.
 *
 * {@hide}
 */
@RavenwoodKeepWholeClass
public class Element {
    private static final int DEFAULT_MAX_STRING_ATTR_LENGTH = 32_768;
    private static final int MAX_POOL_SIZE = 128;
    private static final int MAX_ATTR_LEN_URL_COMPONENT = 256;
    private static final int MAX_ATTR_LEN_PERMISSION_GROUP = 256;
    private static final int MAX_ATTR_LEN_PACKAGE = 256;
    /**
     * The mime type max length restriction here should match the restriction that is also
     * placed in {@link android.content.pm.PackageManager#setMimeGroup(String, Set)}
     */
    private static final int MAX_ATTR_LEN_MIMETYPE = 255;
    private static final int MAX_ATTR_LEN_NAME = 1024;
    private static final int MAX_ATTR_LEN_PATH = 4000;
    private static final int MAX_ATTR_LEN_VALUE = 32_768;

    private static final int MAX_TOTAL_META_DATA_SIZE = 262_144;

    private static final String BAD_COMPONENT_NAME_CHARS = ";,[](){}:?%^*|/\\";

    private static final String TAG = "PackageParsing";
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
    protected static final String TAG_URI_RELATIVE_FILTER_GROUP = "uri-relative-filter-group";
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
    protected static final String TAG_ATTR_FRAGMENT = "fragment";
    protected static final String TAG_ATTR_FRAGMENT_ADVANCED_PATTERN = "fragmentAdvancedPattern";
    protected static final String TAG_ATTR_FRAGMENT_PATTERN = "fragmentPattern";
    protected static final String TAG_ATTR_FRAGMENT_PREFIX = "fragmentPrefix";
    protected static final String TAG_ATTR_FRAGMENT_SUFFIX = "fragmentSuffix";
    protected static final String TAG_ATTR_HOST = "host";
    protected static final String TAG_ATTR_MANAGE_SPACE_ACTIVITY = "manageSpaceActivity";
    protected static final String TAG_ATTR_MIMETYPE = "mimeType";
    protected static final String TAG_ATTR_MIMEGROUP = "mimeGroup";
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
    protected static final String TAG_ATTR_QUERY = "query";
    protected static final String TAG_ATTR_QUERY_ADVANCED_PATTERN = "queryAdvancedPattern";
    protected static final String TAG_ATTR_QUERY_PATTERN = "queryPattern";
    protected static final String TAG_ATTR_QUERY_PREFIX = "queryPrefix";
    protected static final String TAG_ATTR_QUERY_SUFFIX = "querySuffix";
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
    protected static final String TAG_ATTR_ZYGOTE_PRELOAD_NAME = "zygotePreloadName";

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
        mTag = null;
        sPool.get().release(this);
    }

    private long mChildTagMask = 0;
    private int mTotalComponentMetadataSize = 0;

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
            case TAG_USES_SDK:
                return 22;
            case TAG_COMPATIBLE_SCREENS:
                return 23;
            case TAG_QUERIES:
                return 24;
            case TAG_ATTRIBUTION:
                return 25;
            case TAG_USES_FEATURE:
                return 26;
            case TAG_PERMISSION:
                return 27;
            case TAG_USES_PERMISSION:
            case TAG_USES_PERMISSION_SDK_23:
            case TAG_USES_PERMISSION_SDK_M:
                return 28;
            case TAG_GRANT_URI_PERMISSION:
                return 29;
            case TAG_PATH_PERMISSION:
                return 30;
            case TAG_PACKAGE:
                return 31;
            case TAG_INTENT:
                return 32;
            case TAG_URI_RELATIVE_FILTER_GROUP:
                return 33;
            default:
                // The size of the mTagCounters array should be equal to this value+1
                return 34;
        }
    }

    static boolean shouldValidate(String tag) {
        switch (tag) {
            case TAG_ACTION:
            case TAG_ACTIVITY:
            case TAG_ACTIVITY_ALIAS:
            case TAG_APPLICATION:
            case TAG_ATTRIBUTION:
            case TAG_CATEGORY:
            case TAG_COMPATIBLE_SCREENS:
            case TAG_DATA:
            case TAG_GRANT_URI_PERMISSION:
            case TAG_INSTRUMENTATION:
            case TAG_INTENT:
            case TAG_INTENT_FILTER:
            case TAG_LAYOUT:
            case TAG_MANIFEST:
            case TAG_META_DATA:
            case TAG_OVERLAY:
            case TAG_PACKAGE:
            case TAG_PATH_PERMISSION:
            case TAG_PERMISSION:
            case TAG_PERMISSION_GROUP:
            case TAG_PERMISSION_TREE:
            case TAG_PROFILEABLE:
            case TAG_PROPERTY:
            case TAG_PROVIDER:
            case TAG_QUERIES:
            case TAG_RECEIVER:
            case TAG_SCREEN:
            case TAG_SERVICE:
            case TAG_SUPPORTS_GL_TEXTURE:
            case TAG_SUPPORTS_SCREENS:
            case TAG_URI_RELATIVE_FILTER_GROUP:
            case TAG_USES_CONFIGURATION:
            case TAG_USES_FEATURE:
            case TAG_USES_LIBRARY:
            case TAG_USES_NATIVE_LIBRARY:
            case TAG_USES_PERMISSION:
            case TAG_USES_PERMISSION_SDK_23:
            case TAG_USES_PERMISSION_SDK_M:
            case TAG_USES_SDK:
                return true;
            default:
                return false;
        }
    }

    private void init(String tag) {
        this.mTag = tag;
        mChildTagMask = 0;
        mTotalComponentMetadataSize = 0;
        switch (tag) {
            case TAG_ACTIVITY:
                initializeCounter(TAG_LAYOUT, 1000);
                initializeCounter(TAG_META_DATA, 1000);
                initializeCounter(TAG_INTENT_FILTER, 20000);
                break;
            case TAG_ACTIVITY_ALIAS:
            case TAG_RECEIVER:
            case TAG_SERVICE:
                initializeCounter(TAG_META_DATA, 1000);
                initializeCounter(TAG_INTENT_FILTER, 20000);
                break;
            case TAG_APPLICATION:
                initializeCounter(TAG_PROFILEABLE, 100);
                initializeCounter(TAG_USES_NATIVE_LIBRARY, 100);
                initializeCounter(TAG_RECEIVER, 1000);
                initializeCounter(TAG_SERVICE, 1000);
                initializeCounter(TAG_META_DATA, 1000);
                initializeCounter(TAG_USES_LIBRARY, 1000);
                initializeCounter(TAG_ACTIVITY_ALIAS, 4000);
                initializeCounter(TAG_PROVIDER, 8000);
                initializeCounter(TAG_ACTIVITY, 30000);
                break;
            case TAG_COMPATIBLE_SCREENS:
                initializeCounter(TAG_SCREEN, 4000);
                break;
            case TAG_INTENT:
            case TAG_INTENT_FILTER:
                initializeCounter(TAG_URI_RELATIVE_FILTER_GROUP, 100);
                initializeCounter(TAG_ACTION, 20000);
                initializeCounter(TAG_CATEGORY, 40000);
                initializeCounter(TAG_DATA, 40000);
                break;
            case TAG_MANIFEST:
                initializeCounter(TAG_APPLICATION, 100);
                initializeCounter(TAG_OVERLAY, 100);
                initializeCounter(TAG_INSTRUMENTATION, 100);
                initializeCounter(TAG_PERMISSION_GROUP, 100);
                initializeCounter(TAG_PERMISSION_TREE, 100);
                initializeCounter(TAG_SUPPORTS_GL_TEXTURE, 100);
                initializeCounter(TAG_SUPPORTS_SCREENS, 100);
                initializeCounter(TAG_USES_CONFIGURATION, 100);
                initializeCounter(TAG_USES_SDK, 100);
                initializeCounter(TAG_COMPATIBLE_SCREENS, 200);
                initializeCounter(TAG_QUERIES, 200);
                initializeCounter(TAG_ATTRIBUTION, 400);
                initializeCounter(TAG_USES_FEATURE, 400);
                initializeCounter(TAG_PERMISSION, 2000);
                initializeCounter(TAG_USES_PERMISSION, 20000);
                break;
            case TAG_PROVIDER:
                initializeCounter(TAG_GRANT_URI_PERMISSION, 100);
                initializeCounter(TAG_PATH_PERMISSION, 100);
                initializeCounter(TAG_META_DATA, 1000);
                initializeCounter(TAG_INTENT_FILTER, 20000);
                break;
            case TAG_QUERIES:
                initializeCounter(TAG_PACKAGE, 1000);
                initializeCounter(TAG_INTENT, 2000);
                initializeCounter(TAG_PROVIDER, 8000);
                break;
            case TAG_URI_RELATIVE_FILTER_GROUP:
                initializeCounter(TAG_DATA, 100);
                break;
        }
    }

    private static int getAttrStrMaxLen(String attrName) {
        switch (attrName) {
            case TAG_ATTR_HOST:
            case TAG_ATTR_PORT:
            case TAG_ATTR_SCHEME:
                return MAX_ATTR_LEN_URL_COMPONENT;
            case TAG_ATTR_PERMISSION_GROUP:
                return MAX_ATTR_LEN_PERMISSION_GROUP;
            case TAG_ATTR_SHARED_USER_ID:
            case TAG_ATTR_PACKAGE:
            case TAG_ATTR_TARGET_PACKAGE:
                return MAX_ATTR_LEN_PACKAGE;
            case TAG_ATTR_MIMETYPE:
                return MAX_ATTR_LEN_MIMETYPE;
            case TAG_ATTR_BACKUP_AGENT:
            case TAG_ATTR_CATEGORY:
            case TAG_ATTR_MANAGE_SPACE_ACTIVITY:
            case TAG_ATTR_MIMEGROUP:
            case TAG_ATTR_NAME:
            case TAG_ATTR_PARENT_ACTIVITY_NAME:
            case TAG_ATTR_PERMISSION:
            case TAG_ATTR_PROCESS:
            case TAG_ATTR_READ_PERMISSION:
            case TAG_ATTR_REQUIRED_ACCOUNT_TYPE:
            case TAG_ATTR_REQUIRED_SYSTEM_PROPERTY_NAME:
            case TAG_ATTR_RESTRICTED_ACCOUNT_TYPE:
            case TAG_ATTR_TARGET_ACTIVITY:
            case TAG_ATTR_TARGET_NAME:
            case TAG_ATTR_TARGET_PROCESSES:
            case TAG_ATTR_TASK_AFFINITY:
            case TAG_ATTR_WRITE_PERMISSION:
            case TAG_ATTR_VERSION_NAME:
            case TAG_ATTR_ZYGOTE_PRELOAD_NAME:
                return MAX_ATTR_LEN_NAME;
            case TAG_ATTR_FRAGMENT:
            case TAG_ATTR_FRAGMENT_ADVANCED_PATTERN:
            case TAG_ATTR_FRAGMENT_PATTERN:
            case TAG_ATTR_FRAGMENT_PREFIX:
            case TAG_ATTR_FRAGMENT_SUFFIX:
            case TAG_ATTR_PATH:
            case TAG_ATTR_PATH_ADVANCED_PATTERN:
            case TAG_ATTR_PATH_PATTERN:
            case TAG_ATTR_PATH_PREFIX:
            case TAG_ATTR_PATH_SUFFIX:
            case TAG_ATTR_QUERY:
            case TAG_ATTR_QUERY_ADVANCED_PATTERN:
            case TAG_ATTR_QUERY_PATTERN:
            case TAG_ATTR_QUERY_PREFIX:
            case TAG_ATTR_QUERY_SUFFIX:
                return MAX_ATTR_LEN_PATH;
            case TAG_ATTR_VALUE:
                return MAX_ATTR_LEN_VALUE;
            case TAG_ATTR_REQUIRED_SYSTEM_PROPERTY_VALUE:
                return PROP_VALUE_MAX;
            default:
                return DEFAULT_MAX_STRING_ATTR_LENGTH;
        }
    }

    private int getResStrMaxLen(@StyleableRes int index) {
        switch (mTag) {
            case TAG_ACTION:
                return getActionResStrMaxLen(index);
            case TAG_ACTIVITY:
                return getActivityResStrMaxLen(index);
            case TAG_ACTIVITY_ALIAS:
                return getActivityAliasResStrMaxLen(index);
            case TAG_APPLICATION:
                return getApplicationResStrMaxLen(index);
            case TAG_DATA:
                return getDataResStrMaxLen(index);
            case TAG_CATEGORY:
                return getCategoryResStrMaxLen(index);
            case TAG_GRANT_URI_PERMISSION:
                return getGrantUriPermissionResStrMaxLen(index);
            case TAG_INSTRUMENTATION:
                return getInstrumentationResStrMaxLen(index);
            case TAG_MANIFEST:
                return getManifestResStrMaxLen(index);
            case TAG_META_DATA:
                return getMetaDataResStrMaxLen(index);
            case TAG_OVERLAY:
                return getOverlayResStrMaxLen(index);
            case TAG_PATH_PERMISSION:
                return getPathPermissionResStrMaxLen(index);
            case TAG_PERMISSION:
                return getPermissionResStrMaxLen(index);
            case TAG_PERMISSION_GROUP:
                return getPermissionGroupResStrMaxLen(index);
            case TAG_PERMISSION_TREE:
                return getPermissionTreeResStrMaxLen(index);
            case TAG_PROPERTY:
                return getPropertyResStrMaxLen(index);
            case TAG_PROVIDER:
                return getProviderResStrMaxLen(index);
            case TAG_RECEIVER:
                return getReceiverResStrMaxLen(index);
            case TAG_SERVICE:
                return getServiceResStrMaxLen(index);
            case TAG_USES_FEATURE:
                return getUsesFeatureResStrMaxLen(index);
            case TAG_USES_LIBRARY:
                return getUsesLibraryResStrMaxLen(index);
            case TAG_USES_NATIVE_LIBRARY:
                return getUsesNativeLibraryResStrMaxLen(index);
            case TAG_USES_PERMISSION:
            case TAG_USES_PERMISSION_SDK_23:
            case TAG_USES_PERMISSION_SDK_M:
                return getUsesPermissionResStrMaxLen(index);
            default:
                return DEFAULT_MAX_STRING_ATTR_LENGTH;
        }
    }

    private static int getActionResStrMaxLen(@StyleableRes int index) {
        switch (index) {
            case R.styleable.AndroidManifestAction_name:
                return MAX_ATTR_LEN_NAME;
            default:
                return DEFAULT_MAX_STRING_ATTR_LENGTH;
        }
    }

    private static int getActivityResStrMaxLen(@StyleableRes int index) {
        switch (index) {
            case R.styleable.AndroidManifestActivity_name:
            case R.styleable.AndroidManifestActivity_parentActivityName:
            case R.styleable.AndroidManifestActivity_permission:
            case R.styleable.AndroidManifestActivity_process:
            case R.styleable.AndroidManifestActivity_taskAffinity:
                return MAX_ATTR_LEN_NAME;
            default:
                return DEFAULT_MAX_STRING_ATTR_LENGTH;
        }
    }

    private static int getActivityAliasResStrMaxLen(@StyleableRes int index) {
        switch (index) {
            case R.styleable.AndroidManifestActivityAlias_name:
            case R.styleable.AndroidManifestActivityAlias_permission:
            case R.styleable.AndroidManifestActivityAlias_targetActivity:
                return MAX_ATTR_LEN_NAME;
            default:
                return DEFAULT_MAX_STRING_ATTR_LENGTH;
        }
    }

    private static int getApplicationResStrMaxLen(@StyleableRes int index) {
        switch (index) {
            case R.styleable.AndroidManifestApplication_backupAgent:
            case R.styleable.AndroidManifestApplication_manageSpaceActivity:
            case R.styleable.AndroidManifestApplication_name:
            case R.styleable.AndroidManifestApplication_permission:
            case R.styleable.AndroidManifestApplication_process:
            case R.styleable.AndroidManifestApplication_requiredAccountType:
            case R.styleable.AndroidManifestApplication_restrictedAccountType:
            case R.styleable.AndroidManifestApplication_taskAffinity:
            case R.styleable.AndroidManifestApplication_zygotePreloadName:
                return MAX_ATTR_LEN_NAME;
            default:
                return DEFAULT_MAX_STRING_ATTR_LENGTH;
        }
    }

    private static int getCategoryResStrMaxLen(@StyleableRes int index) {
        switch (index) {
            case R.styleable.AndroidManifestCategory_name:
                return MAX_ATTR_LEN_NAME;
            default:
                return DEFAULT_MAX_STRING_ATTR_LENGTH;
        }
    }

    private static int getDataResStrMaxLen(@StyleableRes int index) {
        switch (index) {
            case R.styleable.AndroidManifestData_host:
            case R.styleable.AndroidManifestData_port:
            case R.styleable.AndroidManifestData_scheme:
                return MAX_ATTR_LEN_URL_COMPONENT;
            case R.styleable.AndroidManifestData_mimeType:
                return MAX_ATTR_LEN_MIMETYPE;
            case R.styleable.AndroidManifestData_mimeGroup:
                return MAX_ATTR_LEN_NAME;
            case R.styleable.AndroidManifestData_path:
            case R.styleable.AndroidManifestData_pathPattern:
            case R.styleable.AndroidManifestData_pathPrefix:
            case R.styleable.AndroidManifestData_pathSuffix:
            case R.styleable.AndroidManifestData_pathAdvancedPattern:
            case R.styleable.AndroidManifestData_query:
            case R.styleable.AndroidManifestData_queryPattern:
            case R.styleable.AndroidManifestData_queryPrefix:
            case R.styleable.AndroidManifestData_querySuffix:
            case R.styleable.AndroidManifestData_queryAdvancedPattern:
            case R.styleable.AndroidManifestData_fragment:
            case R.styleable.AndroidManifestData_fragmentPattern:
            case R.styleable.AndroidManifestData_fragmentPrefix:
            case R.styleable.AndroidManifestData_fragmentSuffix:
            case R.styleable.AndroidManifestData_fragmentAdvancedPattern:
                return MAX_ATTR_LEN_PATH;
            default:
                return DEFAULT_MAX_STRING_ATTR_LENGTH;
        }
    }

    private static int getGrantUriPermissionResStrMaxLen(@StyleableRes int index) {
        switch (index) {
            case R.styleable.AndroidManifestGrantUriPermission_path:
            case R.styleable.AndroidManifestGrantUriPermission_pathPattern:
            case R.styleable.AndroidManifestGrantUriPermission_pathPrefix:
                return MAX_ATTR_LEN_PATH;
            default:
                return DEFAULT_MAX_STRING_ATTR_LENGTH;
        }
    }

    private static int getInstrumentationResStrMaxLen(@StyleableRes int index) {
        switch (index) {
            case R.styleable.AndroidManifestInstrumentation_targetPackage:
                return MAX_ATTR_LEN_PACKAGE;
            case R.styleable.AndroidManifestInstrumentation_name:
            case R.styleable.AndroidManifestInstrumentation_targetProcesses:
                return MAX_ATTR_LEN_NAME;
            default:
                return DEFAULT_MAX_STRING_ATTR_LENGTH;
        }
    }

    private static int getManifestResStrMaxLen(@StyleableRes int index) {
        switch (index) {
            case R.styleable.AndroidManifest_sharedUserId:
                return MAX_ATTR_LEN_PACKAGE;
            case R.styleable.AndroidManifest_versionName:
                return MAX_ATTR_LEN_NAME;
            default:
                return DEFAULT_MAX_STRING_ATTR_LENGTH;
        }
    }

    private static int getMetaDataResStrMaxLen(@StyleableRes int index) {
        switch (index) {
            case R.styleable.AndroidManifestMetaData_name:
                return MAX_ATTR_LEN_NAME;
            case R.styleable.AndroidManifestMetaData_value:
                return MAX_ATTR_LEN_VALUE;
            default:
                return DEFAULT_MAX_STRING_ATTR_LENGTH;
        }
    }

    private static int getOverlayResStrMaxLen(@StyleableRes int index) {
        switch (index) {
            case R.styleable.AndroidManifestResourceOverlay_targetPackage:
                return MAX_ATTR_LEN_PACKAGE;
            case R.styleable.AndroidManifestResourceOverlay_category:
            case R.styleable.AndroidManifestResourceOverlay_requiredSystemPropertyName:
            case R.styleable.AndroidManifestResourceOverlay_targetName:
                return MAX_ATTR_LEN_NAME;
            case R.styleable.AndroidManifestResourceOverlay_requiredSystemPropertyValue:
                return PROP_VALUE_MAX;
            default:
                return DEFAULT_MAX_STRING_ATTR_LENGTH;
        }
    }

    private static int getPathPermissionResStrMaxLen(@StyleableRes int index) {
        switch (index) {
            case R.styleable.AndroidManifestPathPermission_permission:
            case R.styleable.AndroidManifestPathPermission_readPermission:
            case R.styleable.AndroidManifestPathPermission_writePermission:
                return MAX_ATTR_LEN_NAME;
            case R.styleable.AndroidManifestPathPermission_path:
            case R.styleable.AndroidManifestPathPermission_pathPattern:
            case R.styleable.AndroidManifestPathPermission_pathPrefix:
                return MAX_ATTR_LEN_PATH;
            default:
                return DEFAULT_MAX_STRING_ATTR_LENGTH;
        }
    }

    private static int getPermissionResStrMaxLen(@StyleableRes int index) {
        switch (index) {
            case R.styleable.AndroidManifestPermission_permissionGroup:
                return MAX_ATTR_LEN_PERMISSION_GROUP;
            case R.styleable.AndroidManifestPermission_name:
                return MAX_ATTR_LEN_NAME;
            default:
                return DEFAULT_MAX_STRING_ATTR_LENGTH;
        }
    }

    private static int getPermissionGroupResStrMaxLen(@StyleableRes int index) {
        switch (index) {
            case R.styleable.AndroidManifestPermissionGroup_name:
                return MAX_ATTR_LEN_NAME;
            default:
                return DEFAULT_MAX_STRING_ATTR_LENGTH;
        }
    }

    private static int getPermissionTreeResStrMaxLen(@StyleableRes int index) {
        switch (index) {
            case R.styleable.AndroidManifestPermissionTree_name:
                return MAX_ATTR_LEN_NAME;
            default:
                return DEFAULT_MAX_STRING_ATTR_LENGTH;
        }
    }

    private static int getPropertyResStrMaxLen(@StyleableRes int index) {
        switch (index) {
            case R.styleable.AndroidManifestProperty_name:
                return MAX_ATTR_LEN_NAME;
            case R.styleable.AndroidManifestProperty_value:
                return MAX_ATTR_LEN_VALUE;
            default:
                return DEFAULT_MAX_STRING_ATTR_LENGTH;
        }
    }

    private static int getProviderResStrMaxLen(@StyleableRes int index) {
        switch (index) {
            case R.styleable.AndroidManifestProvider_name:
            case R.styleable.AndroidManifestProvider_permission:
            case R.styleable.AndroidManifestProvider_process:
            case R.styleable.AndroidManifestProvider_readPermission:
            case R.styleable.AndroidManifestProvider_writePermission:
                return MAX_ATTR_LEN_NAME;
            default:
                return DEFAULT_MAX_STRING_ATTR_LENGTH;
        }
    }

    private static int getReceiverResStrMaxLen(@StyleableRes int index) {
        switch (index) {
            case R.styleable.AndroidManifestReceiver_name:
            case R.styleable.AndroidManifestReceiver_permission:
            case R.styleable.AndroidManifestReceiver_process:
                return MAX_ATTR_LEN_NAME;
            default:
                return DEFAULT_MAX_STRING_ATTR_LENGTH;
        }
    }

    private static int getServiceResStrMaxLen(@StyleableRes int index) {
        switch (index) {
            case R.styleable.AndroidManifestReceiver_name:
            case R.styleable.AndroidManifestReceiver_permission:
            case R.styleable.AndroidManifestReceiver_process:
                return MAX_ATTR_LEN_NAME;
            default:
                return DEFAULT_MAX_STRING_ATTR_LENGTH;
        }
    }

    private static int getUsesFeatureResStrMaxLen(@StyleableRes int index) {
        switch (index) {
            case R.styleable.AndroidManifestUsesFeature_name:
                return MAX_ATTR_LEN_NAME;
            default:
                return DEFAULT_MAX_STRING_ATTR_LENGTH;
        }
    }

    private static int getUsesLibraryResStrMaxLen(@StyleableRes int index) {
        switch (index) {
            case R.styleable.AndroidManifestUsesLibrary_name:
                return MAX_ATTR_LEN_NAME;
            default:
                return DEFAULT_MAX_STRING_ATTR_LENGTH;
        }
    }

    private static int getUsesNativeLibraryResStrMaxLen(@StyleableRes int index) {
        switch (index) {
            case R.styleable.AndroidManifestUsesNativeLibrary_name:
                return MAX_ATTR_LEN_NAME;
            default:
                return DEFAULT_MAX_STRING_ATTR_LENGTH;
        }
    }

    private static int getUsesPermissionResStrMaxLen(@StyleableRes int index) {
        switch (index) {
            case R.styleable.AndroidManifestUsesPermission_name:
                return MAX_ATTR_LEN_NAME;
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

    private boolean isComponentNameAttr(String name) {
        switch (mTag) {
            case TAG_ACTIVITY:
                switch (name) {
                    case TAG_ATTR_NAME:
                    case TAG_ATTR_PARENT_ACTIVITY_NAME:
                        return true;
                    default:
                        return false;
                }
            case TAG_ACTIVITY_ALIAS:
                switch (name) {
                    case TAG_ATTR_TARGET_ACTIVITY:
                        return true;
                    default:
                        return false;
                }
            case TAG_APPLICATION:
                switch (name) {
                    case TAG_ATTR_BACKUP_AGENT:
                    case TAG_ATTR_NAME:
                    case TAG_ATTR_ZYGOTE_PRELOAD_NAME:
                        return true;
                    default:
                        return false;
                }
            case TAG_INSTRUMENTATION:
            case TAG_PROVIDER:
            case TAG_RECEIVER:
            case TAG_SERVICE:
                switch (name) {
                    case TAG_ATTR_NAME:
                        return true;
                    default:
                        return false;
                }
            default:
                return false;
        }
    }

    private boolean isComponentNameAttr(@StyleableRes int index) {
        switch (mTag) {
            case TAG_ACTIVITY:
                return index == R.styleable.AndroidManifestActivity_name
                        || index == R.styleable.AndroidManifestActivity_parentActivityName;
            case TAG_ACTIVITY_ALIAS:
                return index == R.styleable.AndroidManifestActivityAlias_targetActivity;
            case TAG_APPLICATION:
                return index == R.styleable.AndroidManifestApplication_backupAgent
                        || index == R.styleable.AndroidManifestApplication_name
                        || index == R.styleable.AndroidManifestApplication_zygotePreloadName;
            case TAG_INSTRUMENTATION:
                return index ==  R.styleable.AndroidManifestInstrumentation_name;
            case TAG_PROVIDER:
                return index ==  R.styleable.AndroidManifestProvider_name;
            case TAG_RECEIVER:
                return index ==  R.styleable.AndroidManifestReceiver_name;
            case TAG_SERVICE:
                return index ==  R.styleable.AndroidManifestService_name;
            default:
                return false;
        }
    }

    boolean hasChild(String tag) {
        return (mChildTagMask & (1 << getCounterIdx(tag))) != 0;
    }

    void validateComponentName(CharSequence name) {
        boolean isStart = true;
        for (int i = 0; i < name.length(); i++) {
            if (BAD_COMPONENT_NAME_CHARS.indexOf(name.charAt(i)) >= 0) {
                Slog.e(TAG, name + " is not a valid Java class name");
                throw new SecurityException(name + " is not a valid Java class name");
            }
        }
    }

    void validateStrAttr(String attrName, String attrValue) {
        if (attrValue != null && attrValue.length() > getAttrStrMaxLen(attrName)) {
            throw new SecurityException("String length limit exceeded for attribute " + attrName
                    + " in " + mTag);
        }
        if (isComponentNameAttr(attrName)) {
            validateComponentName(attrValue);
        }
    }

    void validateResStrAttr(@StyleableRes int index, CharSequence stringValue) {
        if (stringValue != null && stringValue.length() > getResStrMaxLen(index)) {
            throw new SecurityException("String length limit exceeded for attribute in " + mTag);
        }
        if (isComponentNameAttr(index)) {
            validateComponentName(stringValue);
        }
    }

    void validateComponentMetadata(String value) {
        mTotalComponentMetadataSize += value.length();
        if (mTotalComponentMetadataSize > MAX_TOTAL_META_DATA_SIZE) {
            throw new SecurityException("Max total meta data size limit exceeded for " + mTag);
        }
    }

    void seen(@NonNull Element element) {
        TagCounter counter = mTagCounters[getCounterIdx(element.mTag)];
        if (counter != null) {
            counter.increment();
            if (!counter.isValid()) {
                throw new SecurityException("The number of child " + element.mTag
                        + " elements exceeded the max allowed in " + this.mTag);
            }
        }
    }
}
