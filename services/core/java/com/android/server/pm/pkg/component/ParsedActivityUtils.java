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

package com.android.server.pm.pkg.component;

import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE_PER_TASK;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

import static com.android.server.pm.pkg.component.ComponentParseUtils.flag;
import static com.android.server.pm.pkg.parsing.ParsingUtils.NOT_SET;
import static com.android.server.pm.pkg.parsing.ParsingUtils.parseKnownActivityEmbeddingCerts;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityTaskManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseInput.DeferredError;
import android.content.pm.parsing.result.ParseResult;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.server.pm.pkg.parsing.ParsingPackage;
import com.android.server.pm.pkg.parsing.ParsingPackageUtils;
import com.android.server.pm.pkg.parsing.ParsingUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * @hide
 */
public class ParsedActivityUtils {

    private static final String TAG = ParsingUtils.TAG;

    public static final boolean LOG_UNSAFE_BROADCASTS = false;

    // Set of broadcast actions that are safe for manifest receivers
    public static final Set<String> SAFE_BROADCASTS = new ArraySet<>();
    static {
        SAFE_BROADCASTS.add(Intent.ACTION_BOOT_COMPLETED);
    }

    /**
     * Bit mask of all the valid bits that can be set in recreateOnConfigChanges.
     */
    private static final int RECREATE_ON_CONFIG_CHANGES_MASK =
            ActivityInfo.CONFIG_MCC | ActivityInfo.CONFIG_MNC;

    @NonNull
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public static ParseResult<ParsedActivity> parseActivityOrReceiver(String[] separateProcesses,
            ParsingPackage pkg, Resources res, XmlResourceParser parser, int flags,
            boolean useRoundIcon, @Nullable String defaultSplitName, ParseInput input)
            throws XmlPullParserException, IOException {
        final String packageName = pkg.getPackageName();
        final ParsedActivityImpl activity = new ParsedActivityImpl();

        boolean receiver = "receiver".equals(parser.getName());
        String tag = "<" + parser.getName() + ">";
        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestActivity);
        try {
            ParseResult<ParsedActivityImpl> result =
                    ParsedMainComponentUtils.parseMainComponent(activity, tag, separateProcesses,
                            pkg, sa, flags, useRoundIcon, defaultSplitName, input,
                            R.styleable.AndroidManifestActivity_banner,
                            R.styleable.AndroidManifestActivity_description,
                            R.styleable.AndroidManifestActivity_directBootAware,
                            R.styleable.AndroidManifestActivity_enabled,
                            R.styleable.AndroidManifestActivity_icon,
                            R.styleable.AndroidManifestActivity_label,
                            R.styleable.AndroidManifestActivity_logo,
                            R.styleable.AndroidManifestActivity_name,
                            R.styleable.AndroidManifestActivity_process,
                            R.styleable.AndroidManifestActivity_roundIcon,
                            R.styleable.AndroidManifestActivity_splitName,
                            R.styleable.AndroidManifestActivity_attributionTags);
            if (result.isError()) {
                return input.error(result);
            }

            if (receiver && pkg.isCantSaveState()) {
                // A heavy-weight application can not have receivers in its main process
                if (Objects.equals(activity.getProcessName(), packageName)) {
                    return input.error("Heavy-weight applications can not have receivers "
                            + "in main process");
                }
            }

            // The following section has formatting off to make it easier to read the flags.
            // Multi-lining them to fit within the column restriction makes it hard to tell what
            // field is assigned where.
            // @formatter:off
            activity.setTheme(sa.getResourceId(R.styleable.AndroidManifestActivity_theme, 0))
                    .setUiOptions(sa.getInt(R.styleable.AndroidManifestActivity_uiOptions, pkg.getUiOptions()));

            activity.setFlags(activity.getFlags() | (flag(ActivityInfo.FLAG_ALLOW_TASK_REPARENTING, R.styleable.AndroidManifestActivity_allowTaskReparenting, pkg.isAllowTaskReparenting(), sa)
                                | flag(ActivityInfo.FLAG_ALWAYS_RETAIN_TASK_STATE, R.styleable.AndroidManifestActivity_alwaysRetainTaskState, sa)
                                | flag(ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH, R.styleable.AndroidManifestActivity_clearTaskOnLaunch, sa)
                                | flag(ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS, R.styleable.AndroidManifestActivity_excludeFromRecents, sa)
                                | flag(ActivityInfo.FLAG_FINISH_ON_CLOSE_SYSTEM_DIALOGS, R.styleable.AndroidManifestActivity_finishOnCloseSystemDialogs, sa)
                                | flag(ActivityInfo.FLAG_FINISH_ON_TASK_LAUNCH, R.styleable.AndroidManifestActivity_finishOnTaskLaunch, sa)
                                | flag(ActivityInfo.FLAG_IMMERSIVE, R.styleable.AndroidManifestActivity_immersive, sa)
                                | flag(ActivityInfo.FLAG_MULTIPROCESS, R.styleable.AndroidManifestActivity_multiprocess, sa)
                                | flag(ActivityInfo.FLAG_NO_HISTORY, R.styleable.AndroidManifestActivity_noHistory, sa)
                                | flag(ActivityInfo.FLAG_SHOW_FOR_ALL_USERS, R.styleable.AndroidManifestActivity_showForAllUsers, sa)
                                | flag(ActivityInfo.FLAG_SHOW_FOR_ALL_USERS, R.styleable.AndroidManifestActivity_showOnLockScreen, sa)
                                | flag(ActivityInfo.FLAG_STATE_NOT_NEEDED, R.styleable.AndroidManifestActivity_stateNotNeeded, sa)
                                | flag(ActivityInfo.FLAG_SYSTEM_USER_ONLY, R.styleable.AndroidManifestActivity_systemUserOnly, sa)));

            if (!receiver) {
                activity.setFlags(activity.getFlags() | (flag(ActivityInfo.FLAG_HARDWARE_ACCELERATED, R.styleable.AndroidManifestActivity_hardwareAccelerated, pkg.isBaseHardwareAccelerated(), sa)
                                        | flag(ActivityInfo.FLAG_ALLOW_EMBEDDED, R.styleable.AndroidManifestActivity_allowEmbedded, sa)
                                        | flag(ActivityInfo.FLAG_ALWAYS_FOCUSABLE, R.styleable.AndroidManifestActivity_alwaysFocusable, sa)
                                        | flag(ActivityInfo.FLAG_AUTO_REMOVE_FROM_RECENTS, R.styleable.AndroidManifestActivity_autoRemoveFromRecents, sa)
                                        | flag(ActivityInfo.FLAG_RELINQUISH_TASK_IDENTITY, R.styleable.AndroidManifestActivity_relinquishTaskIdentity, sa)
                                        | flag(ActivityInfo.FLAG_RESUME_WHILE_PAUSING, R.styleable.AndroidManifestActivity_resumeWhilePausing, sa)
                                        | flag(ActivityInfo.FLAG_SHOW_WHEN_LOCKED, R.styleable.AndroidManifestActivity_showWhenLocked, sa)
                                        | flag(ActivityInfo.FLAG_SUPPORTS_PICTURE_IN_PICTURE, R.styleable.AndroidManifestActivity_supportsPictureInPicture, sa)
                                        | flag(ActivityInfo.FLAG_TURN_SCREEN_ON, R.styleable.AndroidManifestActivity_turnScreenOn, sa)
                                        | flag(ActivityInfo.FLAG_PREFER_MINIMAL_POST_PROCESSING, R.styleable.AndroidManifestActivity_preferMinimalPostProcessing, sa))
                                        | flag(ActivityInfo.FLAG_ALLOW_UNTRUSTED_ACTIVITY_EMBEDDING, R.styleable.AndroidManifestActivity_allowUntrustedActivityEmbedding, sa));

                activity.setPrivateFlags(activity.getPrivateFlags() | (flag(ActivityInfo.FLAG_INHERIT_SHOW_WHEN_LOCKED,
                                        R.styleable.AndroidManifestActivity_inheritShowWhenLocked, sa)
                                        | flag(ActivityInfo.PRIVATE_FLAG_HOME_TRANSITION_SOUND,
                                        R.styleable.AndroidManifestActivity_playHomeTransitionSound, true, sa)));

                activity.setColorMode(sa.getInt(R.styleable.AndroidManifestActivity_colorMode, ActivityInfo.COLOR_MODE_DEFAULT))
                        .setDocumentLaunchMode(sa.getInt(R.styleable.AndroidManifestActivity_documentLaunchMode, ActivityInfo.DOCUMENT_LAUNCH_NONE))
                        .setLaunchMode(sa.getInt(R.styleable.AndroidManifestActivity_launchMode, ActivityInfo.LAUNCH_MULTIPLE))
                        .setLockTaskLaunchMode(sa.getInt(R.styleable.AndroidManifestActivity_lockTaskMode, 0))
                        .setMaxRecents(sa.getInt(R.styleable.AndroidManifestActivity_maxRecents, ActivityTaskManager.getDefaultAppRecentsLimitStatic()))
                        .setPersistableMode(sa.getInteger(R.styleable.AndroidManifestActivity_persistableMode, ActivityInfo.PERSIST_ROOT_ONLY))
                        .setRequestedVrComponent(sa.getString(R.styleable.AndroidManifestActivity_enableVrMode))
                        .setRotationAnimation(sa.getInt(R.styleable.AndroidManifestActivity_rotationAnimation, WindowManager.LayoutParams.ROTATION_ANIMATION_UNSPECIFIED))
                        .setSoftInputMode(sa.getInt(R.styleable.AndroidManifestActivity_windowSoftInputMode, 0))
                        .setConfigChanges(getActivityConfigChanges(
                                sa.getInt(R.styleable.AndroidManifestActivity_configChanges, 0),
                                sa.getInt(R.styleable.AndroidManifestActivity_recreateOnConfigChanges, 0))
                        );

                int screenOrientation = sa.getInt(R.styleable.AndroidManifestActivity_screenOrientation, SCREEN_ORIENTATION_UNSPECIFIED);
                int resizeMode = getActivityResizeMode(pkg, sa, screenOrientation);
                activity.setScreenOrientation(screenOrientation)
                        .setResizeMode(resizeMode);

                if (sa.hasValue(R.styleable.AndroidManifestActivity_maxAspectRatio)
                        && sa.getType(R.styleable.AndroidManifestActivity_maxAspectRatio)
                        == TypedValue.TYPE_FLOAT) {
                    activity.setMaxAspectRatio(resizeMode,
                            sa.getFloat(R.styleable.AndroidManifestActivity_maxAspectRatio,
                                    0 /*default*/));
                }

                if (sa.hasValue(R.styleable.AndroidManifestActivity_minAspectRatio)
                        && sa.getType(R.styleable.AndroidManifestActivity_minAspectRatio)
                        == TypedValue.TYPE_FLOAT) {
                    activity.setMinAspectRatio(resizeMode,
                            sa.getFloat(R.styleable.AndroidManifestActivity_minAspectRatio,
                                    0 /*default*/));
                }
            } else {
                activity.setLaunchMode(ActivityInfo.LAUNCH_MULTIPLE)
                        .setConfigChanges(0)
                        .setFlags(activity.getFlags()|flag(ActivityInfo.FLAG_SINGLE_USER, R.styleable.AndroidManifestActivity_singleUser, sa));
            }
            // @formatter:on

            String taskAffinity = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestActivity_taskAffinity,
                    Configuration.NATIVE_CONFIG_VERSION);

            ParseResult<String> affinityNameResult = ComponentParseUtils.buildTaskAffinityName(
                    packageName, pkg.getTaskAffinity(), taskAffinity, input);
            if (affinityNameResult.isError()) {
                return input.error(affinityNameResult);
            }

            activity.setTaskAffinity(affinityNameResult.getResult());

            boolean visibleToEphemeral = sa.getBoolean(R.styleable.AndroidManifestActivity_visibleToInstantApps, false);
            if (visibleToEphemeral) {
                activity.setFlags(activity.getFlags() | ActivityInfo.FLAG_VISIBLE_TO_INSTANT_APP);
                pkg.setVisibleToInstantApps(true);
            }

            return parseActivityOrAlias(activity, pkg, tag, parser, res, sa, receiver,
                    false /*isAlias*/, visibleToEphemeral, input,
                    R.styleable.AndroidManifestActivity_parentActivityName,
                    R.styleable.AndroidManifestActivity_permission,
                    R.styleable.AndroidManifestActivity_exported
            );
        } finally {
            sa.recycle();
        }
    }

    @NonNull
    public static ParseResult<ParsedActivity> parseActivityAlias(ParsingPackage pkg, Resources res,
            XmlResourceParser parser, boolean useRoundIcon, @Nullable String defaultSplitName,
            @NonNull ParseInput input) throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestActivityAlias);
        try {
            String targetActivity = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestActivityAlias_targetActivity,
                    Configuration.NATIVE_CONFIG_VERSION);
            if (targetActivity == null) {
                return input.error("<activity-alias> does not specify android:targetActivity");
            }

            String packageName = pkg.getPackageName();
            targetActivity = ParsingUtils.buildClassName(packageName, targetActivity);
            if (targetActivity == null) {
                return input.error("Empty class name in package " + packageName);
            }

            ParsedActivity target = null;

            List<ParsedActivity> activities = pkg.getActivities();
            final int activitiesSize = ArrayUtils.size(activities);
            for (int i = 0; i < activitiesSize; i++) {
                ParsedActivity t = activities.get(i);
                if (targetActivity.equals(t.getName())) {
                    target = t;
                    break;
                }
            }

            if (target == null) {
                return input.error("<activity-alias> target activity " + targetActivity
                        + " not found in manifest with activities = "
                        + pkg.getActivities()
                        + ", parsedActivities = " + activities);
            }

            ParsedActivityImpl activity = ParsedActivityImpl.makeAlias(targetActivity, target);
            String tag = "<" + parser.getName() + ">";

            ParseResult<ParsedActivityImpl> result = ParsedMainComponentUtils.parseMainComponent(
                    activity, tag, null, pkg, sa, 0, useRoundIcon, defaultSplitName, input,
                    R.styleable.AndroidManifestActivityAlias_banner,
                    R.styleable.AndroidManifestActivityAlias_description,
                    NOT_SET /*directBootAwareAttr*/,
                    R.styleable.AndroidManifestActivityAlias_enabled,
                    R.styleable.AndroidManifestActivityAlias_icon,
                    R.styleable.AndroidManifestActivityAlias_label,
                    R.styleable.AndroidManifestActivityAlias_logo,
                    R.styleable.AndroidManifestActivityAlias_name,
                    NOT_SET /*processAttr*/,
                    R.styleable.AndroidManifestActivityAlias_roundIcon,
                    NOT_SET /*splitNameAttr*/,
                    R.styleable.AndroidManifestActivityAlias_attributionTags);
            if (result.isError()) {
                return input.error(result);
            }

            // TODO add visibleToInstantApps attribute to activity alias
            final boolean visibleToEphemeral =
                    ((activity.getFlags() & ActivityInfo.FLAG_VISIBLE_TO_INSTANT_APP) != 0);

            return parseActivityOrAlias(activity, pkg, tag, parser, res, sa, false /*isReceiver*/, true /*isAlias*/,
                    visibleToEphemeral, input,
                    R.styleable.AndroidManifestActivityAlias_parentActivityName,
                    R.styleable.AndroidManifestActivityAlias_permission,
                    R.styleable.AndroidManifestActivityAlias_exported);
        } finally {
            sa.recycle();
        }
    }

    /**
     * This method shares parsing logic between Activity/Receiver/alias instances, but requires
     * passing in booleans for isReceiver/isAlias, since there's no indicator in the other
     * parameters.
     *
     * They're used to filter the parsed tags and their behavior. This makes the method rather
     * messy, but it's more maintainable than writing 3 separate methods for essentially the same
     * type of logic.
     */
    @NonNull
    private static ParseResult<ParsedActivity> parseActivityOrAlias(ParsedActivityImpl activity,
            ParsingPackage pkg, String tag, XmlResourceParser parser, Resources resources,
            TypedArray array, boolean isReceiver, boolean isAlias, boolean visibleToEphemeral,
            ParseInput input, int parentActivityNameAttr, int permissionAttr,
            int exportedAttr) throws IOException, XmlPullParserException {
        String parentActivityName = array.getNonConfigurationString(parentActivityNameAttr, Configuration.NATIVE_CONFIG_VERSION);
        if (parentActivityName != null) {
            String packageName = pkg.getPackageName();
            String parentClassName = ParsingUtils.buildClassName(packageName, parentActivityName);
            if (parentClassName == null) {
                Log.e(TAG, "Activity " + activity.getName()
                        + " specified invalid parentActivityName " + parentActivityName);
            } else {
                activity.setParentActivityName(parentClassName);
            }
        }

        String permission = array.getNonConfigurationString(permissionAttr, 0);
        if (isAlias) {
            // An alias will override permissions to allow referencing an Activity through its alias
            // without needing the original permission. If an alias needs the same permission,
            // it must be re-declared.
            activity.setPermission(permission);
        } else {
            activity.setPermission(permission != null ? permission : pkg.getPermission());
        }

        final ParseResult<Set<String>> knownActivityEmbeddingCertsResult =
                parseKnownActivityEmbeddingCerts(array, resources, isAlias
                        ? R.styleable.AndroidManifestActivityAlias_knownActivityEmbeddingCerts
                        : R.styleable.AndroidManifestActivity_knownActivityEmbeddingCerts, input);
        if (knownActivityEmbeddingCertsResult.isError()) {
            return input.error(knownActivityEmbeddingCertsResult);
        } else {
            final Set<String> knownActivityEmbeddingCerts = knownActivityEmbeddingCertsResult
                    .getResult();
            if (knownActivityEmbeddingCerts != null) {
                activity.setKnownActivityEmbeddingCerts(knownActivityEmbeddingCerts);
            }
        }

        final boolean setExported = array.hasValue(exportedAttr);
        if (setExported) {
            activity.setExported(array.getBoolean(exportedAttr, false));
        }

        final int depth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > depth)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            final ParseResult result;
            if (parser.getName().equals("intent-filter")) {
                ParseResult<ParsedIntentInfoImpl> intentResult = parseIntentFilter(pkg, activity,
                        !isReceiver, visibleToEphemeral, resources, parser, input);
                if (intentResult.isSuccess()) {
                    ParsedIntentInfoImpl intentInfo = intentResult.getResult();
                    if (intentInfo != null) {
                        IntentFilter intentFilter = intentInfo.getIntentFilter();
                        activity.setOrder(Math.max(intentFilter.getOrder(), activity.getOrder()));
                        activity.addIntent(intentInfo);
                        if (LOG_UNSAFE_BROADCASTS && isReceiver
                                && pkg.getTargetSdkVersion() >= Build.VERSION_CODES.O) {
                            int actionCount = intentFilter.countActions();
                            for (int i = 0; i < actionCount; i++) {
                                final String action = intentFilter.getAction(i);
                                if (action == null || !action.startsWith("android.")) {
                                    continue;
                                }

                                if (!SAFE_BROADCASTS.contains(action)) {
                                    Slog.w(TAG,
                                            "Broadcast " + action + " may never be delivered to "
                                                    + pkg.getPackageName() + " as requested at: "
                                                    + parser.getPositionDescription());
                                }
                            }
                        }
                    }
                }
                result = intentResult;
            } else if (parser.getName().equals("meta-data")) {
                result = ParsedComponentUtils.addMetaData(activity, pkg, resources, parser, input);
            } else if (parser.getName().equals("property")) {
                result = ParsedComponentUtils.addProperty(activity, pkg, resources, parser, input);
            } else if (!isReceiver && !isAlias && parser.getName().equals("preferred")) {
                ParseResult<ParsedIntentInfoImpl> intentResult = parseIntentFilter(pkg, activity,
                        true /*allowImplicitEphemeralVisibility*/, visibleToEphemeral,
                        resources, parser, input);
                if (intentResult.isSuccess()) {
                    ParsedIntentInfoImpl intent = intentResult.getResult();
                    if (intent != null) {
                        pkg.addPreferredActivityFilter(activity.getClassName(), intent);
                    }
                }
                result = intentResult;
            } else if (!isReceiver && !isAlias && parser.getName().equals("layout")) {
                ParseResult<ActivityInfo.WindowLayout> layoutResult =
                        parseActivityWindowLayout(resources, parser, input);
                if (layoutResult.isSuccess()) {
                    activity.setWindowLayout(layoutResult.getResult());
                }
                result = layoutResult;
            } else {
                result = ParsingUtils.unknownTag(tag, pkg, parser, input);
            }

            if (result.isError()) {
                return input.error(result);
            }
        }

        if (!isAlias && activity.getLaunchMode() != LAUNCH_SINGLE_INSTANCE_PER_TASK
                && activity.getMetaData().containsKey(
                ParsingPackageUtils.METADATA_ACTIVITY_LAUNCH_MODE)) {
            final String launchMode = activity.getMetaData().getString(
                    ParsingPackageUtils.METADATA_ACTIVITY_LAUNCH_MODE);
            if (launchMode != null && launchMode.equals("singleInstancePerTask")) {
                activity.setLaunchMode(LAUNCH_SINGLE_INSTANCE_PER_TASK);
            }
        }

        if (!isAlias) {
            // Default allow the activity to be displayed on a remote device unless it explicitly
            // set to false.
            boolean canDisplayOnRemoteDevices = array.getBoolean(
                    R.styleable.AndroidManifestActivity_canDisplayOnRemoteDevices, true);
            if (!activity.getMetaData().getBoolean(
                    ParsingPackageUtils.METADATA_CAN_DISPLAY_ON_REMOTE_DEVICES, true)) {
                canDisplayOnRemoteDevices = false;
            }
            if (canDisplayOnRemoteDevices) {
                activity.setFlags(activity.getFlags()
                        | ActivityInfo.FLAG_CAN_DISPLAY_ON_REMOTE_DEVICES);
            }
        }

        ParseResult<ActivityInfo.WindowLayout> layoutResult =
                resolveActivityWindowLayout(activity, input);
        if (layoutResult.isError()) {
            return input.error(layoutResult);
        }
        activity.setWindowLayout(layoutResult.getResult());

        if (!setExported) {
            boolean hasIntentFilters = activity.getIntents().size() > 0;
            if (hasIntentFilters) {
                final ParseResult exportedCheckResult = input.deferError(
                        activity.getName() + ": Targeting S+ (version " + Build.VERSION_CODES.S
                        + " and above) requires that an explicit value for android:exported be"
                        + " defined when intent filters are present",
                        DeferredError.MISSING_EXPORTED_FLAG);
                if (exportedCheckResult.isError()) {
                    return input.error(exportedCheckResult);
                }
            }
            activity.setExported(hasIntentFilters);
        }

        return input.success(activity);
    }

    @NonNull
    private static ParseResult<ParsedIntentInfoImpl> parseIntentFilter(ParsingPackage pkg,
            ParsedActivityImpl activity, boolean allowImplicitEphemeralVisibility,
            boolean visibleToEphemeral, Resources resources, XmlResourceParser parser,
            ParseInput input) throws IOException, XmlPullParserException {
        ParseResult<ParsedIntentInfoImpl> result = ParsedMainComponentUtils.parseIntentFilter(activity,
                pkg, resources, parser, visibleToEphemeral, true /*allowGlobs*/,
                true /*allowAutoVerify*/, allowImplicitEphemeralVisibility,
                true /*failOnNoActions*/, input);
        if (result.isError()) {
            return input.error(result);
        }

        ParsedIntentInfoImpl intent = result.getResult();
        if (intent != null) {
            final IntentFilter intentFilter = intent.getIntentFilter();
            if (intentFilter.isVisibleToInstantApp()) {
                activity.setFlags(activity.getFlags() | ActivityInfo.FLAG_VISIBLE_TO_INSTANT_APP);
            }
            if (intentFilter.isImplicitlyVisibleToInstantApp()) {
                activity.setFlags(
                        activity.getFlags() | ActivityInfo.FLAG_IMPLICITLY_VISIBLE_TO_INSTANT_APP);
            }
        }

        return input.success(intent);
    }

    private static int getActivityResizeMode(ParsingPackage pkg, TypedArray sa,
            int screenOrientation) {
        Boolean resizeableActivity = pkg.getResizeableActivity();

        if (sa.hasValue(R.styleable.AndroidManifestActivity_resizeableActivity)
                || resizeableActivity != null) {
            // Activity or app explicitly set if it is resizeable or not;
            if (sa.getBoolean(R.styleable.AndroidManifestActivity_resizeableActivity,
                    resizeableActivity != null && resizeableActivity)) {
                return ActivityInfo.RESIZE_MODE_RESIZEABLE;
            } else {
                return ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
            }
        }

        if (pkg.isResizeableActivityViaSdkVersion()) {
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

    @NonNull
    private static ParseResult<ActivityInfo.WindowLayout> parseActivityWindowLayout(Resources res,
            AttributeSet attrs, ParseInput input) {
        TypedArray sw = res.obtainAttributes(attrs, R.styleable.AndroidManifestLayout);
        try {
            int width = -1;
            float widthFraction = -1f;
            int height = -1;
            float heightFraction = -1f;
            final int widthType = sw.getType(R.styleable.AndroidManifestLayout_defaultWidth);
            if (widthType == TypedValue.TYPE_FRACTION) {
                widthFraction = sw.getFraction(R.styleable.AndroidManifestLayout_defaultWidth, 1, 1,
                        -1);
            } else if (widthType == TypedValue.TYPE_DIMENSION) {
                width = sw.getDimensionPixelSize(R.styleable.AndroidManifestLayout_defaultWidth,
                        -1);
            }
            final int heightType = sw.getType(R.styleable.AndroidManifestLayout_defaultHeight);
            if (heightType == TypedValue.TYPE_FRACTION) {
                heightFraction = sw.getFraction(R.styleable.AndroidManifestLayout_defaultHeight, 1,
                        1, -1);
            } else if (heightType == TypedValue.TYPE_DIMENSION) {
                height = sw.getDimensionPixelSize(R.styleable.AndroidManifestLayout_defaultHeight,
                        -1);
            }
            int gravity = sw.getInt(R.styleable.AndroidManifestLayout_gravity, Gravity.CENTER);
            int minWidth = sw.getDimensionPixelSize(R.styleable.AndroidManifestLayout_minWidth, -1);
            int minHeight = sw.getDimensionPixelSize(R.styleable.AndroidManifestLayout_minHeight,
                    -1);
            String windowLayoutAffinity =
                    sw.getNonConfigurationString(
                            R.styleable.AndroidManifestLayout_windowLayoutAffinity, 0);
            final ActivityInfo.WindowLayout windowLayout = new ActivityInfo.WindowLayout(width,
                    widthFraction, height, heightFraction, gravity, minWidth, minHeight,
                    windowLayoutAffinity);
            return input.success(windowLayout);
        } finally {
            sw.recycle();
        }
    }

    /**
     * Resolves values in {@link ActivityInfo.WindowLayout}.
     *
     * <p>{@link ActivityInfo.WindowLayout#windowLayoutAffinity} has a fallback metadata used in
     * Android R and some variants of pre-R.
     */
    private static ParseResult<ActivityInfo.WindowLayout> resolveActivityWindowLayout(
            ParsedActivity activity, ParseInput input) {
        // There isn't a metadata for us to fall back. Whatever is in layout is correct.
        if (!activity.getMetaData().containsKey(
                ParsingPackageUtils.METADATA_ACTIVITY_WINDOW_LAYOUT_AFFINITY)) {
            return input.success(activity.getWindowLayout());
        }

        // Layout already specifies a value. We should just use that one.
        if (activity.getWindowLayout() != null && activity.getWindowLayout().windowLayoutAffinity != null) {
            return input.success(activity.getWindowLayout());
        }

        String windowLayoutAffinity = activity.getMetaData().getString(
                ParsingPackageUtils.METADATA_ACTIVITY_WINDOW_LAYOUT_AFFINITY);
        ActivityInfo.WindowLayout layout = activity.getWindowLayout();
        if (layout == null) {
            layout = new ActivityInfo.WindowLayout(-1 /* width */, -1 /* widthFraction */,
                    -1 /* height */, -1 /* heightFraction */, Gravity.NO_GRAVITY,
                    -1 /* minWidth */, -1 /* minHeight */, windowLayoutAffinity);
        } else {
            layout.windowLayoutAffinity = windowLayoutAffinity;
        }
        return input.success(layout);
    }

    /**
     * @param configChanges The bit mask of configChanges fetched from AndroidManifest.xml.
     * @param recreateOnConfigChanges The bit mask recreateOnConfigChanges fetched from
     *                                AndroidManifest.xml.
     * @hide
     */
    public static int getActivityConfigChanges(int configChanges, int recreateOnConfigChanges) {
        return configChanges | ((~recreateOnConfigChanges) & RECREATE_ON_CONFIG_CHANGES_MASK);
    }
}
