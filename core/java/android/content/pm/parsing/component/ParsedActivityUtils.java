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

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.pm.parsing.component.ComponentParseUtils.flag;

import android.annotation.NonNull;
import android.app.ActivityTaskManager;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageParser;
import android.content.pm.parsing.ParsingPackage;
import android.content.pm.parsing.ParsingPackageUtils;
import android.content.pm.parsing.ParsingUtils;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** @hide */
public class ParsedActivityUtils {

    private static final String TAG = ParsingPackageUtils.TAG;

    @NonNull
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public static ParseResult<ParsedActivity> parseActivityOrReceiver(String[] separateProcesses,
            ParsingPackage pkg, Resources res, XmlResourceParser parser, int flags,
            boolean useRoundIcon, ParseInput input)
            throws XmlPullParserException, IOException {
        final String packageName = pkg.getPackageName();
        final ParsedActivity
                activity = new ParsedActivity();

        boolean receiver = "receiver".equals(parser.getName());
        String tag = "<" + parser.getName() + ">";
        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestActivity);
        try {
            ParseResult<ParsedActivity> result =
                    ParsedMainComponentUtils.parseMainComponent(
                    activity, tag, separateProcesses,
                    pkg, sa, flags, useRoundIcon, input,
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
                    R.styleable.AndroidManifestActivity_splitName);
            if (result.isError()) {
                return result;
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
            activity.theme = sa.getResourceId(R.styleable.AndroidManifestActivity_theme, 0);
            activity.uiOptions = sa.getInt(R.styleable.AndroidManifestActivity_uiOptions, pkg.getUiOptions());

            activity.flags |= flag(ActivityInfo.FLAG_ALLOW_TASK_REPARENTING, R.styleable.AndroidManifestActivity_allowTaskReparenting, pkg.isAllowTaskReparenting(), sa)
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
                    | flag(ActivityInfo.FLAG_SYSTEM_USER_ONLY, R.styleable.AndroidManifestActivity_systemUserOnly, sa);

            if (!receiver) {
                activity.flags |= flag(ActivityInfo.FLAG_HARDWARE_ACCELERATED, R.styleable.AndroidManifestActivity_hardwareAccelerated, pkg.isBaseHardwareAccelerated(), sa)
                        | flag(ActivityInfo.FLAG_ALLOW_EMBEDDED, R.styleable.AndroidManifestActivity_allowEmbedded, sa)
                        | flag(ActivityInfo.FLAG_ALWAYS_FOCUSABLE, R.styleable.AndroidManifestActivity_alwaysFocusable, sa)
                        | flag(ActivityInfo.FLAG_AUTO_REMOVE_FROM_RECENTS, R.styleable.AndroidManifestActivity_autoRemoveFromRecents, sa)
                        | flag(ActivityInfo.FLAG_RELINQUISH_TASK_IDENTITY, R.styleable.AndroidManifestActivity_relinquishTaskIdentity, sa)
                        | flag(ActivityInfo.FLAG_RESUME_WHILE_PAUSING, R.styleable.AndroidManifestActivity_resumeWhilePausing, sa)
                        | flag(ActivityInfo.FLAG_SHOW_WHEN_LOCKED, R.styleable.AndroidManifestActivity_showWhenLocked, sa)
                        | flag(ActivityInfo.FLAG_SUPPORTS_PICTURE_IN_PICTURE, R.styleable.AndroidManifestActivity_supportsPictureInPicture, sa)
                        | flag(ActivityInfo.FLAG_TURN_SCREEN_ON, R.styleable.AndroidManifestActivity_turnScreenOn, sa)
                        | flag(ActivityInfo.FLAG_PREFER_MINIMAL_POST_PROCESSING, R.styleable.AndroidManifestActivity_preferMinimalPostProcessing, sa);

                activity.privateFlags |= flag(ActivityInfo.FLAG_INHERIT_SHOW_WHEN_LOCKED, R.styleable.AndroidManifestActivity_inheritShowWhenLocked, sa);

                activity.colorMode = sa.getInt(R.styleable.AndroidManifestActivity_colorMode, ActivityInfo.COLOR_MODE_DEFAULT);
                activity.documentLaunchMode = sa.getInt(R.styleable.AndroidManifestActivity_documentLaunchMode, ActivityInfo.DOCUMENT_LAUNCH_NONE);
                activity.launchMode = sa.getInt(R.styleable.AndroidManifestActivity_launchMode, ActivityInfo.LAUNCH_MULTIPLE);
                activity.lockTaskLaunchMode = sa.getInt(R.styleable.AndroidManifestActivity_lockTaskMode, 0);
                activity.maxRecents = sa.getInt(R.styleable.AndroidManifestActivity_maxRecents, ActivityTaskManager.getDefaultAppRecentsLimitStatic());
                activity.persistableMode = sa.getInteger(R.styleable.AndroidManifestActivity_persistableMode, ActivityInfo.PERSIST_ROOT_ONLY);
                activity.requestedVrComponent = sa.getString(R.styleable.AndroidManifestActivity_enableVrMode);
                activity.rotationAnimation = sa.getInt(R.styleable.AndroidManifestActivity_rotationAnimation, WindowManager.LayoutParams.ROTATION_ANIMATION_UNSPECIFIED);
                activity.softInputMode = sa.getInt(R.styleable.AndroidManifestActivity_windowSoftInputMode, 0);

                activity.configChanges = PackageParser.getActivityConfigChanges(
                        sa.getInt(R.styleable.AndroidManifestActivity_configChanges, 0),
                        sa.getInt(R.styleable.AndroidManifestActivity_recreateOnConfigChanges, 0));

                int screenOrientation = sa.getInt(R.styleable.AndroidManifestActivity_screenOrientation, SCREEN_ORIENTATION_UNSPECIFIED);
                int resizeMode = getActivityResizeMode(pkg, sa, screenOrientation);
                activity.screenOrientation = screenOrientation;
                activity.resizeMode = resizeMode;

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
                activity.launchMode = ActivityInfo.LAUNCH_MULTIPLE;
                activity.configChanges = 0;
                activity.flags |= flag(ActivityInfo.FLAG_SINGLE_USER, R.styleable.AndroidManifestActivity_singleUser, sa);
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

            activity.taskAffinity = affinityNameResult.getResult();

            boolean visibleToEphemeral = sa.getBoolean(R.styleable.AndroidManifestActivity_visibleToInstantApps, false);
            if (visibleToEphemeral) {
                activity.flags |= ActivityInfo.FLAG_VISIBLE_TO_INSTANT_APP;
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
            XmlResourceParser parser, boolean useRoundIcon, ParseInput input)
            throws XmlPullParserException, IOException {
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

            ParsedActivity activity = ParsedActivity.makeAlias(targetActivity, target);
            String tag = "<" + parser.getName() + ">";

            ParseResult<ParsedActivity> result = ParsedMainComponentUtils.parseMainComponent(
                    activity, tag, null, pkg, sa, 0, useRoundIcon, input,
                    R.styleable.AndroidManifestActivityAlias_banner,
                    R.styleable.AndroidManifestActivityAlias_description,
                    null /*directBootAwareAttr*/,
                    R.styleable.AndroidManifestActivityAlias_enabled,
                    R.styleable.AndroidManifestActivityAlias_icon,
                    R.styleable.AndroidManifestActivityAlias_label,
                    R.styleable.AndroidManifestActivityAlias_logo,
                    R.styleable.AndroidManifestActivityAlias_name,
                    null /*processAttr*/,
                    R.styleable.AndroidManifestActivityAlias_roundIcon,
                    null /*splitNameAttr*/);
            if (result.isError()) {
                return result;
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
    private static ParseResult<ParsedActivity> parseActivityOrAlias(ParsedActivity activity,
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
                activity.setParentActivity(parentClassName);
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

        final boolean setExported = array.hasValue(exportedAttr);
        if (setExported) {
            activity.exported = array.getBoolean(exportedAttr, false);
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
                ParseResult<ParsedIntentInfo> intentResult = parseIntentFilter(pkg, activity,
                        !isReceiver, visibleToEphemeral, resources, parser, input);
                if (intentResult.isSuccess()) {
                    ParsedIntentInfo intent = intentResult.getResult();
                    if (intent != null) {
                        activity.order = Math.max(intent.getOrder(), activity.order);
                        activity.addIntent(intent);
                        if (PackageParser.LOG_UNSAFE_BROADCASTS && isReceiver
                                && pkg.getTargetSdkVersion() >= Build.VERSION_CODES.O) {
                            int actionCount = intent.countActions();
                            for (int i = 0; i < actionCount; i++) {
                                final String action = intent.getAction(i);
                                if (action == null || !action.startsWith("android.")) {
                                    continue;
                                }

                                if (!PackageParser.SAFE_BROADCASTS.contains(action)) {
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
            } else if (!isReceiver && !isAlias && parser.getName().equals("preferred")) {
                ParseResult<ParsedIntentInfo> intentResult = parseIntentFilter(pkg, activity,
                        true /*allowImplicitEphemeralVisibility*/, visibleToEphemeral,
                        resources, parser, input);
                if (intentResult.isSuccess()) {
                    ParsedIntentInfo intent = intentResult.getResult();
                    if (intent != null) {
                        pkg.addPreferredActivityFilter(activity.getClassName(), intent);
                    }
                }
                result = intentResult;
            } else if (!isReceiver && !isAlias && parser.getName().equals("layout")) {
                ParseResult<ActivityInfo.WindowLayout> layoutResult = parseLayout(resources, parser,
                        input);
                if (layoutResult.isSuccess()) {
                    activity.windowLayout = layoutResult.getResult();
                }
                result = layoutResult;
            } else {
                result = ParsingUtils.unknownTag(tag, pkg, parser, input);
            }

            if (result.isError()) {
                return input.error(result);
            }
        }

        ParseResult<ActivityInfo.WindowLayout> layoutResult = resolveWindowLayout(activity, input);
        if (layoutResult.isError()) {
            return input.error(layoutResult);
        }
        activity.windowLayout = layoutResult.getResult();

        if (!setExported) {
            activity.exported = activity.getIntents().size() > 0;
        }

        return input.success(activity);
    }

    @NonNull
    private static ParseResult<ParsedIntentInfo> parseIntentFilter(ParsingPackage pkg,
            ParsedActivity activity, boolean allowImplicitEphemeralVisibility,
            boolean visibleToEphemeral, Resources resources, XmlResourceParser parser,
            ParseInput input) throws IOException, XmlPullParserException {
        ParseResult<ParsedIntentInfo> result = ParsedMainComponentUtils.parseIntentFilter(activity,
                pkg, resources, parser, visibleToEphemeral, true /*allowGlobs*/,
                true /*allowAutoVerify*/, allowImplicitEphemeralVisibility,
                true /*failOnNoActions*/, input);
        if (result.isError()) {
            return input.error(result);
        }

        ParsedIntentInfo intent = result.getResult();
        if (intent != null) {
            if (intent.isVisibleToInstantApp()) {
                activity.flags |= ActivityInfo.FLAG_VISIBLE_TO_INSTANT_APP;
            }
            if (intent.isImplicitlyVisibleToInstantApp()) {
                activity.flags |= ActivityInfo.FLAG_IMPLICITLY_VISIBLE_TO_INSTANT_APP;
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
    private static ParseResult<ActivityInfo.WindowLayout> parseLayout(Resources res,
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
            return input.success(new ActivityInfo.WindowLayout(width, widthFraction, height,
                    heightFraction, gravity, minWidth, minHeight));
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
    private static ParseResult<ActivityInfo.WindowLayout> resolveWindowLayout(
            ParsedActivity activity, ParseInput input) {
        // There isn't a metadata for us to fall back. Whatever is in layout is correct.
        if (activity.metaData == null || !activity.metaData.containsKey(
                PackageParser.METADATA_ACTIVITY_WINDOW_LAYOUT_AFFINITY)) {
            return input.success(activity.windowLayout);
        }

        // Layout already specifies a value. We should just use that one.
        if (activity.windowLayout != null && activity.windowLayout.windowLayoutAffinity != null) {
            return input.success(activity.windowLayout);
        }

        String windowLayoutAffinity = activity.metaData.getString(
                PackageParser.METADATA_ACTIVITY_WINDOW_LAYOUT_AFFINITY);
        ActivityInfo.WindowLayout layout = activity.windowLayout;
        if (layout == null) {
            layout = new ActivityInfo.WindowLayout(-1 /* width */, -1 /* widthFraction */,
                    -1 /* height */, -1 /* heightFraction */, Gravity.NO_GRAVITY,
                    -1 /* minWidth */, -1 /* minHeight */);
        }
        layout.windowLayoutAffinity = windowLayoutAffinity;
        return input.success(layout);
    }
}
