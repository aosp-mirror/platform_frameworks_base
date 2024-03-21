/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.app;

import static android.Manifest.permission.INTERACT_ACROSS_PROFILES;
import static android.app.admin.DevicePolicyResources.Strings.Core.FORWARD_INTENT_TO_PERSONAL;
import static android.app.admin.DevicePolicyResources.Strings.Core.FORWARD_INTENT_TO_WORK;
import static android.app.admin.DevicePolicyResources.Strings.Core.MINIRESOLVER_OPEN_IN_PERSONAL;
import static android.app.admin.DevicePolicyResources.Strings.Core.MINIRESOLVER_OPEN_IN_WORK;
import static android.app.admin.DevicePolicyResources.Strings.Core.MINIRESOLVER_USE_PERSONAL_BROWSER;
import static android.app.admin.DevicePolicyResources.Strings.Core.MINIRESOLVER_USE_WORK_BROWSER;
import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_CANT_ACCESS_PERSONAL;
import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_CANT_ACCESS_WORK;
import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_CROSS_PROFILE_BLOCKED_TITLE;
import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_PERSONAL_TAB;
import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_PERSONAL_TAB_ACCESSIBILITY;
import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_WORK_PROFILE_NOT_SUPPORTED;
import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_WORK_TAB;
import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_WORK_TAB_ACCESSIBILITY;
import static android.content.Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.PermissionChecker.PID_UNKNOWN;
import static android.stats.devicepolicy.nano.DevicePolicyEnums.RESOLVER_EMPTY_STATE_NO_SHARING_TO_PERSONAL;
import static android.stats.devicepolicy.nano.DevicePolicyEnums.RESOLVER_EMPTY_STATE_NO_SHARING_TO_WORK;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PROTECTED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.UiThread;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.VoiceInteractor.PickOptionRequest;
import android.app.VoiceInteractor.PickOptionRequest.Option;
import android.app.VoiceInteractor.Prompt;
import android.app.admin.DevicePolicyEventLogger;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyResourcesManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.PermissionChecker;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.PatternMatcher;
import android.os.RemoteException;
import android.os.StrictMode;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.stats.devicepolicy.DevicePolicyEnums;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Space;
import android.widget.TabHost;
import android.widget.TabWidget;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.AbstractMultiProfilePagerAdapter.CompositeEmptyStateProvider;
import com.android.internal.app.AbstractMultiProfilePagerAdapter.CrossProfileIntentsChecker;
import com.android.internal.app.AbstractMultiProfilePagerAdapter.EmptyStateProvider;
import com.android.internal.app.AbstractMultiProfilePagerAdapter.MyUserIdProvider;
import com.android.internal.app.AbstractMultiProfilePagerAdapter.OnSwitchOnWorkSelectedListener;
import com.android.internal.app.AbstractMultiProfilePagerAdapter.Profile;
import com.android.internal.app.AbstractMultiProfilePagerAdapter.QuietModeManager;
import com.android.internal.app.NoCrossProfileEmptyStateProvider.DevicePolicyBlockerEmptyState;
import com.android.internal.app.chooser.ChooserTargetInfo;
import com.android.internal.app.chooser.DisplayResolveInfo;
import com.android.internal.app.chooser.TargetInfo;
import com.android.internal.content.PackageMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.ResolverDrawerLayout;
import com.android.internal.widget.ViewPager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * This activity is displayed when the system attempts to start an Intent for
 * which there is more than one matching activity, allowing the user to decide
 * which to go to.  It is not normally used directly by application developers.
 */
@UiThread
public class ResolverActivity extends Activity implements
        ResolverListAdapter.ResolverListCommunicator {

    @UnsupportedAppUsage
    public ResolverActivity() {
        mIsIntentPicker = getClass().equals(ResolverActivity.class);
    }

    protected ResolverActivity(boolean isIntentPicker) {
        mIsIntentPicker = isIntentPicker;
    }

    private boolean mSafeForwardingMode;
    private Button mAlwaysButton;
    private Button mOnceButton;
    protected View mProfileView;
    private int mLastSelected = AbsListView.INVALID_POSITION;
    private boolean mResolvingHome = false;
    private String mProfileSwitchMessage;
    private int mLayoutId;
    @VisibleForTesting
    protected final ArrayList<Intent> mIntents = new ArrayList<>();
    private PickTargetOptionRequest mPickOptionRequest;
    private String mReferrerPackage;
    private CharSequence mTitle;
    private int mDefaultTitleResId;
    // Expected to be true if this object is ResolverActivity or is ResolverWrapperActivity.
    private final boolean mIsIntentPicker;

    // Whether or not this activity supports choosing a default handler for the intent.
    @VisibleForTesting
    protected boolean mSupportsAlwaysUseOption;
    protected ResolverDrawerLayout mResolverDrawerLayout;
    @UnsupportedAppUsage
    protected PackageManager mPm;
    protected int mLaunchedFromUid;
    private UserHandle mLaunchedFromUserHandle;

    private static final String TAG = "ResolverActivity";
    private static final boolean DEBUG = false;
    private static final String LAST_SHOWN_TAB_KEY = "last_shown_tab_key";

    private boolean mRegistered;

    protected Insets mSystemWindowInsets = null;
    private Space mFooterSpacer = null;

    /** See {@link #setRetainInOnStop}. */
    private boolean mRetainInOnStop;

    private static final String EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args";
    private static final String EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key";
    private static final String OPEN_LINKS_COMPONENT_KEY = "app_link_state";
    protected static final String METRICS_CATEGORY_RESOLVER = "intent_resolver";
    protected static final String METRICS_CATEGORY_CHOOSER = "intent_chooser";

    /** Tracks if we should ignore future broadcasts telling us the work profile is enabled */
    private boolean mWorkProfileHasBeenEnabled = false;

    @VisibleForTesting
    public static boolean ENABLE_TABBED_VIEW = true;
    private static final String TAB_TAG_PERSONAL = "personal";
    private static final String TAB_TAG_WORK = "work";

    private PackageMonitor mPersonalPackageMonitor;
    private PackageMonitor mWorkPackageMonitor;

    @VisibleForTesting
    protected AbstractMultiProfilePagerAdapter mMultiProfilePagerAdapter;

    protected QuietModeManager mQuietModeManager;

    // Intent extra for connected audio devices
    public static final String EXTRA_IS_AUDIO_CAPTURE_DEVICE = "is_audio_capture_device";

    /**
     * Boolean extra to indicate if Resolver Sheet needs to be started in single user mode.
     */
    protected static final String EXTRA_RESTRICT_TO_SINGLE_USER =
            "com.android.internal.app.ResolverActivity.EXTRA_RESTRICT_TO_SINGLE_USER";

    /**
     * Integer extra to indicate which profile should be automatically selected.
     * <p>Can only be used if there is a work profile.
     * <p>Possible values can be either {@link #PROFILE_PERSONAL} or {@link #PROFILE_WORK}.
     */
    protected static final String EXTRA_SELECTED_PROFILE =
            "com.android.internal.app.ResolverActivity.EXTRA_SELECTED_PROFILE";

    /**
     * {@link UserHandle} extra to indicate the user of the user that the starting intent
     * originated from.
     * <p>This is not necessarily the same as {@link #getUserId()} or {@link UserHandle#myUserId()},
     * as there are edge cases when the intent resolver is launched in the other profile.
     * For example, when we have 0 resolved apps in current profile and multiple resolved
     * apps in the other profile, opening a link from the current profile launches the intent
     * resolver in the other one. b/148536209 for more info.
     */
    static final String EXTRA_CALLING_USER =
            "com.android.internal.app.ResolverActivity.EXTRA_CALLING_USER";

    protected static final int PROFILE_PERSONAL = AbstractMultiProfilePagerAdapter.PROFILE_PERSONAL;
    protected static final int PROFILE_WORK = AbstractMultiProfilePagerAdapter.PROFILE_WORK;

    private BroadcastReceiver mWorkProfileStateReceiver;
    private UserHandle mHeaderCreatorUser;
    private UserHandle mPersonalProfileUserHandle;
    private UserHandle mWorkProfileUserHandle;

    @Nullable
    private OnSwitchOnWorkSelectedListener mOnSwitchOnWorkSelectedListener;

    private UserHandle mCloneProfileUserHandle;
    private UserHandle mTabOwnerUserHandleForLaunch;
    private UserHandle mPrivateProfileUserHandle;

    protected final LatencyTracker mLatencyTracker = getLatencyTracker();

    private LatencyTracker getLatencyTracker() {
        return LatencyTracker.getInstance(this);
    }

    /**
     * Get the string resource to be used as a label for the link to the resolver activity for an
     * action.
     *
     * @param action The action to resolve
     *
     * @return The string resource to be used as a label
     */
    public static @StringRes int getLabelRes(String action) {
        return ActionTitle.forAction(action).labelRes;
    }

    private enum ActionTitle {
        VIEW(Intent.ACTION_VIEW,
                com.android.internal.R.string.whichViewApplication,
                com.android.internal.R.string.whichViewApplicationNamed,
                com.android.internal.R.string.whichViewApplicationLabel),
        EDIT(Intent.ACTION_EDIT,
                com.android.internal.R.string.whichEditApplication,
                com.android.internal.R.string.whichEditApplicationNamed,
                com.android.internal.R.string.whichEditApplicationLabel),
        SEND(Intent.ACTION_SEND,
                com.android.internal.R.string.whichSendApplication,
                com.android.internal.R.string.whichSendApplicationNamed,
                com.android.internal.R.string.whichSendApplicationLabel),
        SENDTO(Intent.ACTION_SENDTO,
                com.android.internal.R.string.whichSendToApplication,
                com.android.internal.R.string.whichSendToApplicationNamed,
                com.android.internal.R.string.whichSendToApplicationLabel),
        SEND_MULTIPLE(Intent.ACTION_SEND_MULTIPLE,
                com.android.internal.R.string.whichSendApplication,
                com.android.internal.R.string.whichSendApplicationNamed,
                com.android.internal.R.string.whichSendApplicationLabel),
        CAPTURE_IMAGE(MediaStore.ACTION_IMAGE_CAPTURE,
                com.android.internal.R.string.whichImageCaptureApplication,
                com.android.internal.R.string.whichImageCaptureApplicationNamed,
                com.android.internal.R.string.whichImageCaptureApplicationLabel),
        DEFAULT(null,
                com.android.internal.R.string.whichApplication,
                com.android.internal.R.string.whichApplicationNamed,
                com.android.internal.R.string.whichApplicationLabel),
        HOME(Intent.ACTION_MAIN,
                com.android.internal.R.string.whichHomeApplication,
                com.android.internal.R.string.whichHomeApplicationNamed,
                com.android.internal.R.string.whichHomeApplicationLabel);

        // titles for layout that deals with http(s) intents
        public static final int BROWSABLE_TITLE_RES =
                com.android.internal.R.string.whichOpenLinksWith;
        public static final int BROWSABLE_HOST_TITLE_RES =
                com.android.internal.R.string.whichOpenHostLinksWith;
        public static final int BROWSABLE_HOST_APP_TITLE_RES =
                com.android.internal.R.string.whichOpenHostLinksWithApp;
        public static final int BROWSABLE_APP_TITLE_RES =
                com.android.internal.R.string.whichOpenLinksWithApp;

        public final String action;
        public final int titleRes;
        public final int namedTitleRes;
        public final @StringRes int labelRes;

        ActionTitle(String action, int titleRes, int namedTitleRes, @StringRes int labelRes) {
            this.action = action;
            this.titleRes = titleRes;
            this.namedTitleRes = namedTitleRes;
            this.labelRes = labelRes;
        }

        public static ActionTitle forAction(String action) {
            for (ActionTitle title : values()) {
                if (title != HOME && action != null && action.equals(title.action)) {
                    return title;
                }
            }
            return DEFAULT;
        }
    }

    protected PackageMonitor createPackageMonitor(ResolverListAdapter listAdapter) {
        return new PackageMonitor() {
            @Override
            public void onSomePackagesChanged() {
                listAdapter.handlePackagesChanged();
                updateProfileViewButton();
            }

            @Override
            public boolean onPackageChanged(String packageName, int uid, String[] components) {
                // We care about all package changes, not just the whole package itself which is
                // default behavior.
                return true;
            }
        };
    }

    private Intent makeMyIntent() {
        Intent intent = new Intent(getIntent());
        intent.setComponent(null);
        // The resolver activity is set to be hidden from recent tasks.
        // we don't want this attribute to be propagated to the next activity
        // being launched.  Note that if the original Intent also had this
        // flag set, we are now losing it.  That should be a very rare case
        // and we can live with this.
        intent.setFlags(intent.getFlags()&~Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

        // If FLAG_ACTIVITY_LAUNCH_ADJACENT was set, ResolverActivity was opened in the alternate
        // side, which means we want to open the target app on the same side as ResolverActivity.
        if ((intent.getFlags() & FLAG_ACTIVITY_LAUNCH_ADJACENT) != 0) {
            intent.setFlags(intent.getFlags() & ~FLAG_ACTIVITY_LAUNCH_ADJACENT);
        }
        return intent;
    }

    /**
     * Call {@link Activity#onCreate} without initializing anything further. This should
     * only be used when the activity is about to be immediately finished to avoid wasting
     * initializing steps and leaking resources.
     */
    protected void super_onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Use a specialized prompt when we're handling the 'Home' app startActivity()
        final Intent intent = makeMyIntent();
        final Set<String> categories = intent.getCategories();
        if (Intent.ACTION_MAIN.equals(intent.getAction())
                && categories != null
                && categories.size() == 1
                && categories.contains(Intent.CATEGORY_HOME)) {
            // Note: this field is not set to true in the compatibility version.
            mResolvingHome = true;
        }

        setSafeForwardingMode(true);

        onCreate(savedInstanceState, intent, null, 0, null, null, true);
    }

    /**
     * Compatibility version for other bundled services that use this overload without
     * a default title resource
     */
    @UnsupportedAppUsage
    protected void onCreate(Bundle savedInstanceState, Intent intent,
            CharSequence title, Intent[] initialIntents,
            List<ResolveInfo> rList, boolean supportsAlwaysUseOption) {
        onCreate(savedInstanceState, intent, title, 0, initialIntents, rList,
                supportsAlwaysUseOption);
    }

    protected void onCreate(Bundle savedInstanceState, Intent intent,
            CharSequence title, int defaultTitleRes, Intent[] initialIntents,
            List<ResolveInfo> rList, boolean supportsAlwaysUseOption) {
        setTheme(appliedThemeResId());
        super.onCreate(savedInstanceState);

        mQuietModeManager = createQuietModeManager();

        // Determine whether we should show that intent is forwarded
        // from managed profile to owner or other way around.
        setProfileSwitchMessage(intent.getContentUserHint());

        mLaunchedFromUid = getLaunchedFromUid();
        mLaunchedFromUserHandle = UserHandle.getUserHandleForUid(mLaunchedFromUid);
        if (mLaunchedFromUid < 0 || UserHandle.isIsolated(mLaunchedFromUid)) {
            // Gulp!
            finish();
            return;
        }

        mPm = getPackageManager();

        mReferrerPackage = getReferrerPackageName();

        // Add our initial intent as the first item, regardless of what else has already been added.
        mIntents.add(0, new Intent(intent));
        mTitle = title;
        mDefaultTitleResId = defaultTitleRes;

        mSupportsAlwaysUseOption = supportsAlwaysUseOption;
        mPersonalProfileUserHandle = fetchPersonalProfileUserHandle();
        mWorkProfileUserHandle = fetchWorkProfileUserProfile();
        mCloneProfileUserHandle = fetchCloneProfileUserHandle();
        mPrivateProfileUserHandle = fetchPrivateProfileUserHandle();
        mTabOwnerUserHandleForLaunch = fetchTabOwnerUserHandleForLaunch();

        // The last argument of createResolverListAdapter is whether to do special handling
        // of the last used choice to highlight it in the list.  We need to always
        // turn this off when running under voice interaction, since it results in
        // a more complicated UI that the current voice interaction flow is not able
        // to handle. We also turn it off when the work tab is shown to simplify the UX.
        // We also turn it off when clonedProfile is present on the device, because we might have
        // different "last chosen" activities in the different profiles, and PackageManager doesn't
        // provide any more information to help us select between them.
        boolean filterLastUsed = mSupportsAlwaysUseOption && !isVoiceInteraction()
                && !shouldShowTabs() && !hasCloneProfile();
        mMultiProfilePagerAdapter = createMultiProfilePagerAdapter(initialIntents, rList, filterLastUsed);
        if (configureContentView()) {
            return;
        }

        mPersonalPackageMonitor = createPackageMonitor(
                mMultiProfilePagerAdapter.getPersonalListAdapter());
        mPersonalPackageMonitor.register(
                this, getMainLooper(), getPersonalProfileUserHandle(), false);
        if (shouldShowTabs()) {
            mWorkPackageMonitor = createPackageMonitor(
                    mMultiProfilePagerAdapter.getWorkListAdapter());
            mWorkPackageMonitor.register(this, getMainLooper(), getWorkProfileUserHandle(), false);
        }

        mRegistered = true;

        final ResolverDrawerLayout rdl = findViewById(R.id.contentPanel);
        if (rdl != null) {
            rdl.setOnDismissedListener(new ResolverDrawerLayout.OnDismissedListener() {
                @Override
                public void onDismissed() {
                    finish();
                }
            });

            boolean hasTouchScreen = getPackageManager()
                    .hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);

            if (isVoiceInteraction() || !hasTouchScreen) {
                rdl.setCollapsed(false);
            }

            rdl.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            rdl.setOnApplyWindowInsetsListener(this::onApplyWindowInsets);

            mResolverDrawerLayout = rdl;

            for (int i = 0, size = mMultiProfilePagerAdapter.getCount(); i < size; i++) {
                View view = mMultiProfilePagerAdapter.getItem(i).rootView.findViewById(
                        R.id.resolver_list);
                if (view != null) {
                    view.setAccessibilityDelegate(new AppListAccessibilityDelegate(rdl));
                }
            }
        }

        mProfileView = findViewById(R.id.profile_button);
        if (mProfileView != null) {
            mProfileView.setOnClickListener(this::onProfileClick);
            updateProfileViewButton();
        }

        final Set<String> categories = intent.getCategories();
        MetricsLogger.action(this, mMultiProfilePagerAdapter.getActiveListAdapter().hasFilteredItem()
                ? MetricsProto.MetricsEvent.ACTION_SHOW_APP_DISAMBIG_APP_FEATURED
                : MetricsProto.MetricsEvent.ACTION_SHOW_APP_DISAMBIG_NONE_FEATURED,
                intent.getAction() + ":" + intent.getType() + ":"
                        + (categories != null ? Arrays.toString(categories.toArray()) : ""));
    }

    protected AbstractMultiProfilePagerAdapter createMultiProfilePagerAdapter(
            Intent[] initialIntents,
            List<ResolveInfo> rList,
            boolean filterLastUsed) {
        AbstractMultiProfilePagerAdapter resolverMultiProfilePagerAdapter = null;
        if (shouldShowTabs()) {
            resolverMultiProfilePagerAdapter =
                    createResolverMultiProfilePagerAdapterForTwoProfiles(
                            initialIntents, rList, filterLastUsed);
        } else {
            resolverMultiProfilePagerAdapter = createResolverMultiProfilePagerAdapterForOneProfile(
                    initialIntents, rList, filterLastUsed);
        }
        return resolverMultiProfilePagerAdapter;
    }

    @VisibleForTesting
    protected MyUserIdProvider createMyUserIdProvider() {
        return new MyUserIdProvider();
    }

    @VisibleForTesting
    protected CrossProfileIntentsChecker createCrossProfileIntentsChecker() {
        return new CrossProfileIntentsChecker(getContentResolver());
    }

    @VisibleForTesting
    protected QuietModeManager createQuietModeManager() {
        UserManager userManager = getSystemService(UserManager.class);
        return new QuietModeManager() {

            private boolean mIsWaitingToEnableWorkProfile = false;

            @Override
            public boolean isQuietModeEnabled(UserHandle workProfileUserHandle) {
                return userManager.isQuietModeEnabled(workProfileUserHandle);
            }

            @Override
            public void requestQuietModeEnabled(boolean enabled, UserHandle workProfileUserHandle) {
                AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
                    userManager.requestQuietModeEnabled(enabled, workProfileUserHandle);
                });
                mIsWaitingToEnableWorkProfile = true;
            }

            @Override
            public void markWorkProfileEnabledBroadcastReceived() {
                mIsWaitingToEnableWorkProfile = false;
            }

            @Override
            public boolean isWaitingToEnableWorkProfile() {
                return mIsWaitingToEnableWorkProfile;
            }
        };
    }

    protected EmptyStateProvider createBlockerEmptyStateProvider() {
        final boolean shouldShowNoCrossProfileIntentsEmptyState = getUser().equals(getIntentUser());

        if (!shouldShowNoCrossProfileIntentsEmptyState) {
            // Implementation that doesn't show any blockers
            return new EmptyStateProvider() {};
        }

        final AbstractMultiProfilePagerAdapter.EmptyState
                noWorkToPersonalEmptyState =
                new DevicePolicyBlockerEmptyState(/* context= */ this,
                /* devicePolicyStringTitleId= */ RESOLVER_CROSS_PROFILE_BLOCKED_TITLE,
                /* defaultTitleResource= */ R.string.resolver_cross_profile_blocked,
                /* devicePolicyStringSubtitleId= */ RESOLVER_CANT_ACCESS_PERSONAL,
                /* defaultSubtitleResource= */
                R.string.resolver_cant_access_personal_apps_explanation,
                /* devicePolicyEventId= */ RESOLVER_EMPTY_STATE_NO_SHARING_TO_PERSONAL,
                /* devicePolicyEventCategory= */ ResolverActivity.METRICS_CATEGORY_RESOLVER);

        final AbstractMultiProfilePagerAdapter.EmptyState noPersonalToWorkEmptyState =
                new DevicePolicyBlockerEmptyState(/* context= */ this,
                /* devicePolicyStringTitleId= */ RESOLVER_CROSS_PROFILE_BLOCKED_TITLE,
                /* defaultTitleResource= */ R.string.resolver_cross_profile_blocked,
                /* devicePolicyStringSubtitleId= */ RESOLVER_CANT_ACCESS_WORK,
                /* defaultSubtitleResource= */
                R.string.resolver_cant_access_work_apps_explanation,
                /* devicePolicyEventId= */ RESOLVER_EMPTY_STATE_NO_SHARING_TO_WORK,
                /* devicePolicyEventCategory= */ ResolverActivity.METRICS_CATEGORY_RESOLVER);

        return new NoCrossProfileEmptyStateProvider(
                getPersonalProfileUserHandle(),
                noWorkToPersonalEmptyState,
                noPersonalToWorkEmptyState,
                createCrossProfileIntentsChecker(),
                getTabOwnerUserHandleForLaunch());
    }

    protected EmptyStateProvider createEmptyStateProvider(
            @Nullable UserHandle workProfileUserHandle) {
        final EmptyStateProvider blockerEmptyStateProvider = createBlockerEmptyStateProvider();

        final EmptyStateProvider workProfileOffEmptyStateProvider =
                new WorkProfilePausedEmptyStateProvider(this, workProfileUserHandle,
                        mQuietModeManager,
                        /* onSwitchOnWorkSelectedListener= */
                        () -> { if (mOnSwitchOnWorkSelectedListener != null) {
                            mOnSwitchOnWorkSelectedListener.onSwitchOnWorkSelected();
                        }},
                        getMetricsCategory());

        final EmptyStateProvider noAppsEmptyStateProvider = new NoAppsAvailableEmptyStateProvider(
                this,
                workProfileUserHandle,
                getPersonalProfileUserHandle(),
                getMetricsCategory(),
                getTabOwnerUserHandleForLaunch()
        );

        // Return composite provider, the order matters (the higher, the more priority)
        return new CompositeEmptyStateProvider(
                blockerEmptyStateProvider,
                workProfileOffEmptyStateProvider,
                noAppsEmptyStateProvider
        );
    }

    private ResolverMultiProfilePagerAdapter createResolverMultiProfilePagerAdapterForOneProfile(
            Intent[] initialIntents,
            List<ResolveInfo> rList, boolean filterLastUsed) {
        ResolverListAdapter adapter = createResolverListAdapter(
                /* context */ this,
                /* payloadIntents */ mIntents,
                initialIntents,
                rList,
                filterLastUsed,
                getPersonalProfileUserHandle());

        QuietModeManager quietModeManager = createQuietModeManager();
        return new ResolverMultiProfilePagerAdapter(
                /* context */ this,
                adapter,
                createEmptyStateProvider(/* workProfileUserHandle= */ null),
                quietModeManager,
                /* workProfileUserHandle= */ null,
                getCloneProfileUserHandle());
    }

    private UserHandle getIntentUser() {
        return getIntent().hasExtra(EXTRA_CALLING_USER)
                ? getIntent().getParcelableExtra(EXTRA_CALLING_USER, android.os.UserHandle.class)
                : getUser();
    }

    private ResolverMultiProfilePagerAdapter createResolverMultiProfilePagerAdapterForTwoProfiles(
            Intent[] initialIntents,
            List<ResolveInfo> rList,
            boolean filterLastUsed) {
        // In the edge case when we have 0 apps in the current profile and >1 apps in the other,
        // the intent resolver is started in the other profile. Since this is the only case when
        // this happens, we check for it here and set the current profile's tab.
        int selectedProfile = getCurrentProfile();
        UserHandle intentUser = getIntentUser();
        if (!getTabOwnerUserHandleForLaunch().equals(intentUser)) {
            if (getPersonalProfileUserHandle().equals(intentUser)) {
                selectedProfile = PROFILE_PERSONAL;
            } else if (getWorkProfileUserHandle().equals(intentUser)) {
                selectedProfile = PROFILE_WORK;
            }
        } else {
            int selectedProfileExtra = getSelectedProfileExtra();
            if (selectedProfileExtra != -1) {
                selectedProfile = selectedProfileExtra;
            }
        }
        // We only show the default app for the profile of the current user. The filterLastUsed
        // flag determines whether to show a default app and that app is not shown in the
        // resolver list. So filterLastUsed should be false for the other profile.
        ResolverListAdapter personalAdapter = createResolverListAdapter(
                /* context */ this,
                /* payloadIntents */ mIntents,
                selectedProfile == PROFILE_PERSONAL ? initialIntents : null,
                rList,
                (filterLastUsed && UserHandle.myUserId()
                        == getPersonalProfileUserHandle().getIdentifier()),
                /* userHandle */ getPersonalProfileUserHandle());
        UserHandle workProfileUserHandle = getWorkProfileUserHandle();
        ResolverListAdapter workAdapter = createResolverListAdapter(
                /* context */ this,
                /* payloadIntents */ mIntents,
                selectedProfile == PROFILE_WORK ? initialIntents : null,
                rList,
                (filterLastUsed && UserHandle.myUserId()
                        == workProfileUserHandle.getIdentifier()),
                /* userHandle */ workProfileUserHandle);
        QuietModeManager quietModeManager = createQuietModeManager();
        return new ResolverMultiProfilePagerAdapter(
                /* context */ this,
                personalAdapter,
                workAdapter,
                createEmptyStateProvider(getWorkProfileUserHandle()),
                quietModeManager,
                selectedProfile,
                getWorkProfileUserHandle(),
                getCloneProfileUserHandle());
    }

    protected int appliedThemeResId() {
        return R.style.Theme_DeviceDefault_Resolver;
    }

    /**
     * Returns {@link #PROFILE_PERSONAL} or {@link #PROFILE_WORK} if the {@link
     * #EXTRA_SELECTED_PROFILE} extra was supplied, or {@code -1} if no extra was supplied.
     * @throws IllegalArgumentException if the value passed to the {@link #EXTRA_SELECTED_PROFILE}
     * extra is not {@link #PROFILE_PERSONAL} or {@link #PROFILE_WORK}
     */
    int getSelectedProfileExtra() {
        int selectedProfile = -1;
        if (getIntent().hasExtra(EXTRA_SELECTED_PROFILE)) {
            selectedProfile = getIntent().getIntExtra(EXTRA_SELECTED_PROFILE, /* defValue = */ -1);
            if (selectedProfile != PROFILE_PERSONAL && selectedProfile != PROFILE_WORK) {
                throw new IllegalArgumentException(EXTRA_SELECTED_PROFILE + " has invalid value "
                        + selectedProfile + ". Must be either ResolverActivity.PROFILE_PERSONAL or "
                        + "ResolverActivity.PROFILE_WORK.");
            }
        }
        return selectedProfile;
    }

    protected @Profile int getCurrentProfile() {
        return (UserHandle.myUserId() == getPersonalProfileUserHandle().getIdentifier()
                ? PROFILE_PERSONAL : PROFILE_WORK);
    }

    protected UserHandle getPersonalProfileUserHandle() {
        // When launched in single user mode, only personal tab is populated, so we use
        // tabOwnerUserHandleForLaunch as personal tab's user handle.
        if (privateSpaceEnabled() && isLaunchedInSingleUserMode()) {
            return getTabOwnerUserHandleForLaunch();
        }
        return mPersonalProfileUserHandle;
    }
    protected @Nullable UserHandle getWorkProfileUserHandle() {
        return mWorkProfileUserHandle;
    }

    protected @Nullable UserHandle getCloneProfileUserHandle() {
        return mCloneProfileUserHandle;
    }

    protected UserHandle getTabOwnerUserHandleForLaunch() {
        return mTabOwnerUserHandleForLaunch;
    }

    protected UserHandle getPrivateProfileUserHandle() {
        return mPrivateProfileUserHandle;
    }

    protected UserHandle fetchPersonalProfileUserHandle() {
        // ActivityManager.getCurrentUser() refers to the current Foreground user. When clone/work
        // profile is active, we always make the personal tab from the foreground user.
        // Outside profiles, current foreground user is potentially the same as the sharesheet
        // process's user (UserHandle.myUserId()), so we continue to create personal tab with the
        // current foreground user.
        mPersonalProfileUserHandle = UserHandle.of(ActivityManager.getCurrentUser());
        return mPersonalProfileUserHandle;
    }

    protected @Nullable UserHandle fetchWorkProfileUserProfile() {
        mWorkProfileUserHandle = null;
        UserManager userManager = getSystemService(UserManager.class);
        for (final UserInfo userInfo : userManager
                .getProfiles(mPersonalProfileUserHandle.getIdentifier())) {
            if (userInfo.isManagedProfile()) {
                mWorkProfileUserHandle = userInfo.getUserHandle();
            }
        }
        return mWorkProfileUserHandle;
    }

    protected @Nullable UserHandle fetchCloneProfileUserHandle() {
        mCloneProfileUserHandle = null;
        UserManager userManager = getSystemService(UserManager.class);
        for (final UserInfo userInfo :
                userManager.getProfiles(mPersonalProfileUserHandle.getIdentifier())) {
            if (userInfo.isCloneProfile()) {
                mCloneProfileUserHandle = userInfo.getUserHandle();
            }
        }
        return mCloneProfileUserHandle;
    }

    protected @Nullable UserHandle fetchPrivateProfileUserHandle() {
        mPrivateProfileUserHandle = null;
        UserManager userManager = getSystemService(UserManager.class);
        for (final UserInfo userInfo :
                userManager.getProfiles(mPersonalProfileUserHandle.getIdentifier())) {
            if (userInfo.isPrivateProfile()) {
                mPrivateProfileUserHandle = userInfo.getUserHandle();
                break;
            }
        }
        return mPrivateProfileUserHandle;
    }

    private UserHandle fetchTabOwnerUserHandleForLaunch() {
        // If we are in work or private profile's process, return WorkProfile/PrivateProfile user
        // as owner, otherwise we always return PersonalProfile user as owner
        if (UserHandle.of(UserHandle.myUserId()).equals(getWorkProfileUserHandle())) {
            return mWorkProfileUserHandle;
        } else if (privateSpaceEnabled() && isLaunchedAsPrivateProfile()) {
            return mPrivateProfileUserHandle;
        }
        return mPersonalProfileUserHandle;
    }

    private boolean hasWorkProfile() {
        return getWorkProfileUserHandle() != null;
    }

    private boolean hasCloneProfile() {
        return getCloneProfileUserHandle() != null;
    }

    protected final boolean isLaunchedAsCloneProfile() {
        return hasCloneProfile()
                && (UserHandle.myUserId() == getCloneProfileUserHandle().getIdentifier());
    }

    protected final boolean isLaunchedAsPrivateProfile() {
        return getPrivateProfileUserHandle() != null
                && (UserHandle.myUserId() == getPrivateProfileUserHandle().getIdentifier());
    }

    protected final boolean isLaunchedInSingleUserMode() {
        // When launched from Private Profile, return true
        if (isLaunchedAsPrivateProfile()) {
            return true;
        }
        return getIntent()
                .getBooleanExtra(EXTRA_RESTRICT_TO_SINGLE_USER, /* defaultValue = */ false);
    }

    protected boolean shouldShowTabs() {
        // No Tabs are shown when launched in single user mode.
        if (privateSpaceEnabled() && isLaunchedInSingleUserMode()) {
            return false;
        }
        return hasWorkProfile() && ENABLE_TABBED_VIEW;
    }

    protected void onProfileClick(View v) {
        final DisplayResolveInfo dri =
                mMultiProfilePagerAdapter.getActiveListAdapter().getOtherProfile();
        if (dri == null) {
            return;
        }

        // Do not show the profile switch message anymore.
        mProfileSwitchMessage = null;

        onTargetSelected(dri, false);
        finish();
    }

    /**
     * Numerous layouts are supported, each with optional ViewGroups.
     * Make sure the inset gets added to the correct View, using
     * a footer for Lists so it can properly scroll under the navbar.
     */
    protected boolean shouldAddFooterView() {
        if (useLayoutWithDefault()) return true;

        View buttonBar = findViewById(R.id.button_bar);
        if (buttonBar == null || buttonBar.getVisibility() == View.GONE) return true;

        return false;
    }

    protected void applyFooterView(int height) {
        if (mFooterSpacer == null) {
            mFooterSpacer = new Space(getApplicationContext());
        } else {
            ((ResolverMultiProfilePagerAdapter) mMultiProfilePagerAdapter)
                .getActiveAdapterView().removeFooterView(mFooterSpacer);
        }
        mFooterSpacer.setLayoutParams(new AbsListView.LayoutParams(LayoutParams.MATCH_PARENT,
                                                                   mSystemWindowInsets.bottom));
        ((ResolverMultiProfilePagerAdapter) mMultiProfilePagerAdapter)
            .getActiveAdapterView().addFooterView(mFooterSpacer);
    }

    protected WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
        mSystemWindowInsets = insets.getSystemWindowInsets();

        mResolverDrawerLayout.setPadding(mSystemWindowInsets.left, mSystemWindowInsets.top,
                mSystemWindowInsets.right, 0);

        resetButtonBar();

        if (shouldUseMiniResolver()) {
            View buttonContainer = findViewById(R.id.button_bar_container);
            buttonContainer.setPadding(0, 0, 0, mSystemWindowInsets.bottom
                    + getResources().getDimensionPixelOffset(R.dimen.resolver_button_bar_spacing));
        }

        // Need extra padding so the list can fully scroll up
        if (shouldAddFooterView()) {
            applyFooterView(mSystemWindowInsets.bottom);
        }

        return insets.consumeSystemWindowInsets();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mMultiProfilePagerAdapter.getActiveListAdapter().handlePackagesChanged();
        if (mIsIntentPicker && shouldShowTabs() && !useLayoutWithDefault()
                && !shouldUseMiniResolver()) {
            updateIntentPickerPaddings();
        }

        if (mSystemWindowInsets != null) {
            mResolverDrawerLayout.setPadding(mSystemWindowInsets.left, mSystemWindowInsets.top,
                    mSystemWindowInsets.right, 0);
        }
    }

    private void updateIntentPickerPaddings() {
        View titleCont = findViewById(R.id.title_container);
        titleCont.setPadding(
                titleCont.getPaddingLeft(),
                titleCont.getPaddingTop(),
                titleCont.getPaddingRight(),
                getResources().getDimensionPixelSize(R.dimen.resolver_title_padding_bottom));
        View buttonBar = findViewById(R.id.button_bar);
        buttonBar.setPadding(
                buttonBar.getPaddingLeft(),
                getResources().getDimensionPixelSize(R.dimen.resolver_button_bar_spacing),
                buttonBar.getPaddingRight(),
                getResources().getDimensionPixelSize(R.dimen.resolver_button_bar_spacing));
    }

    @Override // ResolverListCommunicator
    public void sendVoiceChoicesIfNeeded() {
        if (!isVoiceInteraction()) {
            // Clearly not needed.
            return;
        }

        int count = mMultiProfilePagerAdapter.getActiveListAdapter().getCount();
        final Option[] options = new Option[count];
        for (int i = 0, N = options.length; i < N; i++) {
            TargetInfo target = mMultiProfilePagerAdapter.getActiveListAdapter().getItem(i);
            if (target == null) {
                // If this occurs, a new set of targets is being loaded. Let that complete,
                // and have the next call to send voice choices proceed instead.
                return;
            }
            options[i] = optionForChooserTarget(target, i);
        }

        mPickOptionRequest = new PickTargetOptionRequest(
                new Prompt(getTitle()), options, null);
        getVoiceInteractor().submitRequest(mPickOptionRequest);
    }

    Option optionForChooserTarget(TargetInfo target, int index) {
        return new Option(target.getDisplayLabel(), index);
    }

    protected final void setAdditionalTargets(Intent[] intents) {
        if (intents != null) {
            for (Intent intent : intents) {
                mIntents.add(intent);
            }
        }
    }

    @Override // SelectableTargetInfoCommunicator ResolverListCommunicator
    public Intent getTargetIntent() {
        return mIntents.isEmpty() ? null : mIntents.get(0);
    }

    protected String getReferrerPackageName() {
        final Uri referrer = getReferrer();
        if (referrer != null && "android-app".equals(referrer.getScheme())) {
            return referrer.getHost();
        }
        return null;
    }

    public int getLayoutResource() {
        return R.layout.resolver_list;
    }

    @Override // ResolverListCommunicator
    public void updateProfileViewButton() {
        if (mProfileView == null) {
            return;
        }

        final DisplayResolveInfo dri =
                mMultiProfilePagerAdapter.getActiveListAdapter().getOtherProfile();
        if (dri != null && !shouldShowTabs()) {
            mProfileView.setVisibility(View.VISIBLE);
            View text = mProfileView.findViewById(R.id.profile_button);
            if (!(text instanceof TextView)) {
                text = mProfileView.findViewById(R.id.text1);
            }
            ((TextView) text).setText(dri.getDisplayLabel());
        } else {
            mProfileView.setVisibility(View.GONE);
        }
    }

    private void setProfileSwitchMessage(int contentUserHint) {
        if (contentUserHint != UserHandle.USER_CURRENT &&
                contentUserHint != UserHandle.myUserId()) {
            UserManager userManager = (UserManager) getSystemService(Context.USER_SERVICE);
            UserInfo originUserInfo = userManager.getUserInfo(contentUserHint);
            boolean originIsManaged = originUserInfo != null ? originUserInfo.isManagedProfile()
                    : false;
            boolean targetIsManaged = userManager.isManagedProfile();
            if (originIsManaged && !targetIsManaged) {
                mProfileSwitchMessage = getForwardToPersonalMsg();
            } else if (!originIsManaged && targetIsManaged) {
                mProfileSwitchMessage = getForwardToWorkMsg();
            }
        }
    }

    private String getForwardToPersonalMsg() {
        return getSystemService(DevicePolicyManager.class).getResources().getString(
                FORWARD_INTENT_TO_PERSONAL,
                () -> getString(com.android.internal.R.string.forward_intent_to_owner));
    }

    private String getForwardToWorkMsg() {
        return getSystemService(DevicePolicyManager.class).getResources().getString(
                FORWARD_INTENT_TO_WORK,
                () -> getString(com.android.internal.R.string.forward_intent_to_work));
    }

    /**
     * Turn on launch mode that is safe to use when forwarding intents received from
     * applications and running in system processes.  This mode uses Activity.startActivityAsCaller
     * instead of the normal Activity.startActivity for launching the activity selected
     * by the user.
     *
     * <p>This mode is set to true by default if the activity is initialized through
     * {@link #onCreate(android.os.Bundle)}.  If a subclass calls one of the other onCreate
     * methods, it is set to false by default.  You must set it before calling one of the
     * more detailed onCreate methods, so that it will be set correctly in the case where
     * there is only one intent to resolve and it is thus started immediately.</p>
     */
    public void setSafeForwardingMode(boolean safeForwarding) {
        mSafeForwardingMode = safeForwarding;
    }

    protected CharSequence getTitleForAction(Intent intent, int defaultTitleRes) {
        final ActionTitle title = mResolvingHome
                ? ActionTitle.HOME
                : ActionTitle.forAction(intent.getAction());

        // While there may already be a filtered item, we can only use it in the title if the list
        // is already sorted and all information relevant to it is already in the list.
        final boolean named =
                mMultiProfilePagerAdapter.getActiveListAdapter().getFilteredPosition() >= 0;
        if (title == ActionTitle.DEFAULT && defaultTitleRes != 0) {
            return getString(defaultTitleRes);
        } else {
            return named
                    ? getString(title.namedTitleRes, mMultiProfilePagerAdapter
                            .getActiveListAdapter().getFilteredItem().getDisplayLabel())
                    : getString(title.titleRes);
        }
    }

    void dismiss() {
        if (!isFinishing()) {
            finish();
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        if (!mRegistered) {
            mPersonalPackageMonitor.register(this, getMainLooper(),
                    getPersonalProfileUserHandle(), false);
            if (shouldShowTabs()) {
                if (mWorkPackageMonitor == null) {
                    mWorkPackageMonitor = createPackageMonitor(
                            mMultiProfilePagerAdapter.getWorkListAdapter());
                }
                mWorkPackageMonitor.register(this, getMainLooper(),
                        getWorkProfileUserHandle(), false);
            }
            mRegistered = true;
        }
        if (shouldShowTabs() && mQuietModeManager.isWaitingToEnableWorkProfile()) {
            if (mQuietModeManager.isQuietModeEnabled(getWorkProfileUserHandle())) {
                mQuietModeManager.markWorkProfileEnabledBroadcastReceived();
            }
        }
        mMultiProfilePagerAdapter.getActiveListAdapter().handlePackagesChanged();
        updateProfileViewButton();
    }

    @Override
    protected void onStart() {
        super.onStart();

        this.getWindow().addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
        if (shouldShowTabs()) {
            mWorkProfileStateReceiver = createWorkProfileStateReceiver();
            registerWorkProfileStateReceiver();

            mWorkProfileHasBeenEnabled = isWorkProfileEnabled();
        }
    }

    private boolean isWorkProfileEnabled() {
        UserHandle workUserHandle = getWorkProfileUserHandle();
        UserManager userManager = getSystemService(UserManager.class);

        return !userManager.isQuietModeEnabled(workUserHandle)
                && userManager.isUserUnlocked(workUserHandle);
    }

    private void registerWorkProfileStateReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        registerReceiverAsUser(mWorkProfileStateReceiver, UserHandle.ALL, filter, null, null);
    }

    @Override
    protected void onStop() {
        super.onStop();

        final Window window = this.getWindow();
        final WindowManager.LayoutParams attrs = window.getAttributes();
        attrs.privateFlags &= ~SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
        window.setAttributes(attrs);

        if (mRegistered) {
            mPersonalPackageMonitor.unregister();
            if (mWorkPackageMonitor != null) {
                mWorkPackageMonitor.unregister();
            }
            mRegistered = false;
        }
        final Intent intent = getIntent();
        if ((intent.getFlags() & FLAG_ACTIVITY_NEW_TASK) != 0 && !isVoiceInteraction()
                && !mResolvingHome && !mRetainInOnStop) {
            // This resolver is in the unusual situation where it has been
            // launched at the top of a new task.  We don't let it be added
            // to the recent tasks shown to the user, and we need to make sure
            // that each time we are launched we get the correct launching
            // uid (not re-using the same resolver from an old launching uid),
            // so we will now finish ourself since being no longer visible,
            // the user probably can't get back to us.
            if (!isChangingConfigurations()) {
                finish();
            }
        }
        if (mWorkPackageMonitor != null) {
            unregisterReceiver(mWorkProfileStateReceiver);
            mWorkPackageMonitor = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!isChangingConfigurations() && mPickOptionRequest != null) {
            mPickOptionRequest.cancel();
        }
        if (mMultiProfilePagerAdapter != null
                && mMultiProfilePagerAdapter.getActiveListAdapter() != null) {
            mMultiProfilePagerAdapter.getActiveListAdapter().onDestroy();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        ViewPager viewPager = findViewById(R.id.profile_pager);
        if (viewPager != null) {
            outState.putInt(LAST_SHOWN_TAB_KEY, viewPager.getCurrentItem());
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        resetButtonBar();
        ViewPager viewPager = findViewById(R.id.profile_pager);
        if (viewPager != null) {
            viewPager.setCurrentItem(savedInstanceState.getInt(LAST_SHOWN_TAB_KEY));
        }
        mMultiProfilePagerAdapter.clearInactiveProfileCache();
    }

    private boolean hasManagedProfile() {
        UserManager userManager = (UserManager) getSystemService(Context.USER_SERVICE);
        if (userManager == null) {
            return false;
        }

        try {
            List<UserInfo> profiles = userManager.getProfiles(getUserId());
            for (UserInfo userInfo : profiles) {
                if (userInfo != null && userInfo.isManagedProfile()) {
                    return true;
                }
            }
        } catch (SecurityException e) {
            return false;
        }
        return false;
    }

    private boolean supportsManagedProfiles(ResolveInfo resolveInfo) {
        try {
            ApplicationInfo appInfo = getPackageManager().getApplicationInfo(
                    resolveInfo.activityInfo.packageName, 0 /* default flags */);
            return appInfo.targetSdkVersion >= Build.VERSION_CODES.LOLLIPOP;
        } catch (NameNotFoundException e) {
            return false;
        }
    }

    private void setAlwaysButtonEnabled(boolean hasValidSelection, int checkedPos,
            boolean filtered) {
        if (!mMultiProfilePagerAdapter.getCurrentUserHandle().equals(getUser())) {
            // Never allow the inactive profile to always open an app.
            mAlwaysButton.setEnabled(false);
            return;
        }
        // In case of clonedProfile being active, we do not allow the 'Always' option in the
        // disambiguation dialog of Personal Profile as the package manager cannot distinguish
        // between cross-profile preferred activities.
        if (hasCloneProfile() && !mMultiProfilePagerAdapter
                .getCurrentUserHandle().equals(mWorkProfileUserHandle)) {
            mAlwaysButton.setEnabled(false);
            return;
        }
        boolean enabled = false;
        ResolveInfo ri = null;
        if (hasValidSelection) {
            ri = mMultiProfilePagerAdapter.getActiveListAdapter()
                    .resolveInfoForPosition(checkedPos, filtered);
            if (ri == null) {
                Log.e(TAG, "Invalid position supplied to setAlwaysButtonEnabled");
                return;
            } else if (ri.targetUserId != UserHandle.USER_CURRENT) {
                Log.e(TAG, "Attempted to set selection to resolve info for another user");
                return;
            } else {
                enabled = true;
            }

            mAlwaysButton.setText(getResources()
                    .getString(R.string.activity_resolver_use_always));
        }

        if (ri != null) {
            ActivityInfo activityInfo = ri.activityInfo;

            boolean hasRecordPermission =
                    mPm.checkPermission(android.Manifest.permission.RECORD_AUDIO,
                            activityInfo.packageName)
                            == android.content.pm.PackageManager.PERMISSION_GRANTED;

            if (!hasRecordPermission) {
                // OK, we know the record permission, is this a capture device
                boolean hasAudioCapture =
                        getIntent().getBooleanExtra(
                                ResolverActivity.EXTRA_IS_AUDIO_CAPTURE_DEVICE, false);
                enabled = !hasAudioCapture;
            }
        }
        mAlwaysButton.setEnabled(enabled);
    }

    public void onButtonClick(View v) {
        final int id = v.getId();
        ListView listView = (ListView) mMultiProfilePagerAdapter.getActiveAdapterView();
        ResolverListAdapter currentListAdapter = mMultiProfilePagerAdapter.getActiveListAdapter();
        int which = currentListAdapter.hasFilteredItem()
                ? currentListAdapter.getFilteredPosition()
                : listView.getCheckedItemPosition();
        boolean hasIndexBeenFiltered = !currentListAdapter.hasFilteredItem();
        startSelected(which, id == R.id.button_always, hasIndexBeenFiltered);
    }

    public void startSelected(int which, boolean always, boolean hasIndexBeenFiltered) {
        if (isFinishing()) {
            return;
        }
        ResolveInfo ri = mMultiProfilePagerAdapter.getActiveListAdapter()
                .resolveInfoForPosition(which, hasIndexBeenFiltered);
        if (mResolvingHome && hasManagedProfile() && !supportsManagedProfiles(ri)) {
            Toast.makeText(this,
                    getWorkProfileNotSupportedMsg(
                            ri.activityInfo.loadLabel(getPackageManager()).toString()),
                    Toast.LENGTH_LONG).show();
            return;
        }

        TargetInfo target = mMultiProfilePagerAdapter.getActiveListAdapter()
                .targetInfoForPosition(which, hasIndexBeenFiltered);
        if (target == null) {
            return;
        }
        if (onTargetSelected(target, always)) {
            if (always && mSupportsAlwaysUseOption) {
                MetricsLogger.action(
                        this, MetricsProto.MetricsEvent.ACTION_APP_DISAMBIG_ALWAYS);
            } else if (mSupportsAlwaysUseOption) {
                MetricsLogger.action(
                        this, MetricsProto.MetricsEvent.ACTION_APP_DISAMBIG_JUST_ONCE);
            } else {
                MetricsLogger.action(
                        this, MetricsProto.MetricsEvent.ACTION_APP_DISAMBIG_TAP);
            }
            MetricsLogger.action(this,
                    mMultiProfilePagerAdapter.getActiveListAdapter().hasFilteredItem()
                            ? MetricsProto.MetricsEvent.ACTION_HIDE_APP_DISAMBIG_APP_FEATURED
                            : MetricsProto.MetricsEvent.ACTION_HIDE_APP_DISAMBIG_NONE_FEATURED);
            finish();
        }
    }

    private String getWorkProfileNotSupportedMsg(String launcherName) {
        return getSystemService(DevicePolicyManager.class).getResources().getString(
                RESOLVER_WORK_PROFILE_NOT_SUPPORTED,
                () -> getString(
                        com.android.internal.R.string.activity_resolver_work_profiles_support,
                        launcherName),
                launcherName);
    }

    /**
     * Replace me in subclasses!
     */
    @Override // ResolverListCommunicator
    public Intent getReplacementIntent(ActivityInfo aInfo, Intent defIntent) {
        return defIntent;
    }

    @Override // ResolverListCommunicator
    public final void onPostListReady(ResolverListAdapter listAdapter, boolean doPostProcessing,
            boolean rebuildCompleted) {
        if (isDestroyed()) {
            return;
        }
        if (isAutolaunching()) {
            return;
        }
        if (mIsIntentPicker) {
            ((ResolverMultiProfilePagerAdapter) mMultiProfilePagerAdapter)
                    .setUseLayoutWithDefault(useLayoutWithDefault());
        }
        if (mMultiProfilePagerAdapter.shouldShowEmptyStateScreen(listAdapter)) {
            mMultiProfilePagerAdapter.showEmptyResolverListEmptyState(listAdapter);
        } else {
            mMultiProfilePagerAdapter.showListView(listAdapter);
        }
        // showEmptyResolverListEmptyState can mark the tab as loaded,
        // which is a precondition for auto launching
        if (rebuildCompleted && maybeAutolaunchActivity()) {
            return;
        }
        if (doPostProcessing) {
            maybeCreateHeader(listAdapter);
            resetButtonBar();
            onListRebuilt(listAdapter, rebuildCompleted);
        }
    }

    protected void onListRebuilt(ResolverListAdapter listAdapter, boolean rebuildCompleted) {
        final ItemClickListener listener = new ItemClickListener();
        setupAdapterListView((ListView) mMultiProfilePagerAdapter.getActiveAdapterView(), listener);
        if (shouldShowTabs() && mIsIntentPicker) {
            final ResolverDrawerLayout rdl = findViewById(R.id.contentPanel);
            if (rdl != null) {
                rdl.setMaxCollapsedHeight(getResources()
                        .getDimensionPixelSize(useLayoutWithDefault()
                                ? R.dimen.resolver_max_collapsed_height_with_default_with_tabs
                                : R.dimen.resolver_max_collapsed_height_with_tabs));
            }
        }
    }

    protected boolean onTargetSelected(TargetInfo target, boolean always) {
        final ResolveInfo ri = target.getResolveInfo();
        final Intent intent = target != null ? target.getResolvedIntent() : null;

        if (intent != null && (mSupportsAlwaysUseOption
                || mMultiProfilePagerAdapter.getActiveListAdapter().hasFilteredItem())
                && mMultiProfilePagerAdapter.getActiveListAdapter().getUnfilteredResolveList() != null) {
            // Build a reasonable intent filter, based on what matched.
            IntentFilter filter = new IntentFilter();
            Intent filterIntent;

            if (intent.getSelector() != null) {
                filterIntent = intent.getSelector();
            } else {
                filterIntent = intent;
            }

            String action = filterIntent.getAction();
            if (action != null) {
                filter.addAction(action);
            }
            Set<String> categories = filterIntent.getCategories();
            if (categories != null) {
                for (String cat : categories) {
                    filter.addCategory(cat);
                }
            }
            filter.addCategory(Intent.CATEGORY_DEFAULT);

            int cat = ri.match & IntentFilter.MATCH_CATEGORY_MASK;
            Uri data = filterIntent.getData();
            if (cat == IntentFilter.MATCH_CATEGORY_TYPE) {
                String mimeType = filterIntent.resolveType(this);
                if (mimeType != null) {
                    try {
                        filter.addDataType(mimeType);
                    } catch (IntentFilter.MalformedMimeTypeException e) {
                        Log.w("ResolverActivity", e);
                        filter = null;
                    }
                }
            }
            if (data != null && data.getScheme() != null) {
                // We need the data specification if there was no type,
                // OR if the scheme is not one of our magical "file:"
                // or "content:" schemes (see IntentFilter for the reason).
                if (cat != IntentFilter.MATCH_CATEGORY_TYPE
                        || (!"file".equals(data.getScheme())
                                && !"content".equals(data.getScheme()))) {
                    filter.addDataScheme(data.getScheme());

                    // Look through the resolved filter to determine which part
                    // of it matched the original Intent.
                    Iterator<PatternMatcher> pIt = ri.filter.schemeSpecificPartsIterator();
                    if (pIt != null) {
                        String ssp = data.getSchemeSpecificPart();
                        while (ssp != null && pIt.hasNext()) {
                            PatternMatcher p = pIt.next();
                            if (p.match(ssp)) {
                                filter.addDataSchemeSpecificPart(p.getPath(), p.getType());
                                break;
                            }
                        }
                    }
                    Iterator<IntentFilter.AuthorityEntry> aIt = ri.filter.authoritiesIterator();
                    if (aIt != null) {
                        while (aIt.hasNext()) {
                            IntentFilter.AuthorityEntry a = aIt.next();
                            if (a.match(data) >= 0) {
                                int port = a.getPort();
                                filter.addDataAuthority(a.getHost(),
                                        port >= 0 ? Integer.toString(port) : null);
                                break;
                            }
                        }
                    }
                    pIt = ri.filter.pathsIterator();
                    if (pIt != null) {
                        String path = data.getPath();
                        while (path != null && pIt.hasNext()) {
                            PatternMatcher p = pIt.next();
                            if (p.match(path)) {
                                filter.addDataPath(p.getPath(), p.getType());
                                break;
                            }
                        }
                    }
                }
            }

            if (filter != null) {
                final int N = mMultiProfilePagerAdapter.getActiveListAdapter()
                        .getUnfilteredResolveList().size();
                ComponentName[] set;
                // If we don't add back in the component for forwarding the intent to a managed
                // profile, the preferred activity may not be updated correctly (as the set of
                // components we tell it we knew about will have changed).
                final boolean needToAddBackProfileForwardingComponent =
                        mMultiProfilePagerAdapter.getActiveListAdapter().getOtherProfile() != null;
                if (!needToAddBackProfileForwardingComponent) {
                    set = new ComponentName[N];
                } else {
                    set = new ComponentName[N + 1];
                }

                int bestMatch = 0;
                for (int i=0; i<N; i++) {
                    ResolveInfo r = mMultiProfilePagerAdapter.getActiveListAdapter()
                            .getUnfilteredResolveList().get(i).getResolveInfoAt(0);
                    set[i] = new ComponentName(r.activityInfo.packageName,
                            r.activityInfo.name);
                    if (r.match > bestMatch) bestMatch = r.match;
                }

                if (needToAddBackProfileForwardingComponent) {
                    set[N] = mMultiProfilePagerAdapter.getActiveListAdapter()
                            .getOtherProfile().getResolvedComponentName();
                    final int otherProfileMatch = mMultiProfilePagerAdapter.getActiveListAdapter()
                            .getOtherProfile().getResolveInfo().match;
                    if (otherProfileMatch > bestMatch) bestMatch = otherProfileMatch;
                }

                if (always) {
                    final int userId = getUserId();
                    final PackageManager pm = getPackageManager();

                    // Set the preferred Activity
                    pm.addUniquePreferredActivity(filter, bestMatch, set, intent.getComponent());

                    if (ri.handleAllWebDataURI) {
                        // Set default Browser if needed
                        final String packageName = pm.getDefaultBrowserPackageNameAsUser(userId);
                        if (TextUtils.isEmpty(packageName)) {
                            pm.setDefaultBrowserPackageNameAsUser(ri.activityInfo.packageName, userId);
                        }
                    }
                } else {
                    try {
                        mMultiProfilePagerAdapter.getActiveListAdapter()
                                .mResolverListController.setLastChosen(intent, filter, bestMatch);
                    } catch (RemoteException re) {
                        Log.d(TAG, "Error calling setLastChosenActivity\n" + re);
                    }
                }
            }
        }

        if (target != null) {
            safelyStartActivity(target);

            // Rely on the ActivityManager to pop up a dialog regarding app suspension
            // and return false
            if (target.isSuspended()) {
                return false;
            }
        }

        return true;
    }

    /** Start the activity specified by the {@link TargetInfo}.*/
    public final void safelyStartActivity(TargetInfo cti) {
        // In case cloned apps are present, we would want to start those apps in cloned user
        // space, which will not be same as adaptor's userHandle. resolveInfo.userHandle
        // identifies the correct user space in such cases.
        UserHandle activityUserHandle = getResolveInfoUserHandle(
                cti.getResolveInfo(), mMultiProfilePagerAdapter.getCurrentUserHandle());
        safelyStartActivityAsUser(cti, activityUserHandle, null);
    }

    /**
     * Start activity as a fixed user handle.
     * @param cti TargetInfo to be launched.
     * @param user User to launch this activity as.
     */
    public final void safelyStartActivityAsUser(TargetInfo cti, UserHandle user) {
        safelyStartActivityAsUser(cti, user, null);
    }

    protected final void safelyStartActivityAsUser(
            TargetInfo cti, UserHandle user, @Nullable Bundle options) {
        // We're dispatching intents that might be coming from legacy apps, so
        // don't kill ourselves.
        StrictMode.disableDeathOnFileUriExposure();
        try {
            safelyStartActivityInternal(cti, user, options);
        } finally {
            StrictMode.enableDeathOnFileUriExposure();
        }
    }

    @VisibleForTesting
    protected void safelyStartActivityInternal(
            TargetInfo cti, UserHandle user, @Nullable Bundle options) {
        // If the target is suspended, the activity will not be successfully launched.
        // Do not unregister from package manager updates in this case
        if (!cti.isSuspended() && mRegistered) {
            if (mPersonalPackageMonitor != null) {
                mPersonalPackageMonitor.unregister();
            }
            if (mWorkPackageMonitor != null) {
                mWorkPackageMonitor.unregister();
            }
            mRegistered = false;
        }
        // If needed, show that intent is forwarded
        // from managed profile to owner or other way around.
        if (mProfileSwitchMessage != null) {
            Toast.makeText(this, mProfileSwitchMessage, Toast.LENGTH_LONG).show();
        }
        if (!mSafeForwardingMode) {
            if (cti.startAsUser(this, options, user)) {
                onActivityStarted(cti);
                maybeLogCrossProfileTargetLaunch(cti, user);
            }
            return;
        }
        try {
            if (cti.startAsCaller(this, options, user.getIdentifier())) {
                onActivityStarted(cti);
                maybeLogCrossProfileTargetLaunch(cti, user);
            }
        } catch (RuntimeException e) {
            Slog.wtf(TAG, "Unable to launch as uid " + mLaunchedFromUid
                    + " package " + getLaunchedFromPackage() + ", while running in "
                    + ActivityThread.currentProcessName(), e);
        }
    }

    private void maybeLogCrossProfileTargetLaunch(TargetInfo cti, UserHandle currentUserHandle) {
        if (!hasWorkProfile() || currentUserHandle.equals(getUser())) {
            return;
        }
        DevicePolicyEventLogger
                .createEvent(DevicePolicyEnums.RESOLVER_CROSS_PROFILE_TARGET_OPENED)
                .setBoolean(currentUserHandle.equals(getPersonalProfileUserHandle()))
                .setStrings(getMetricsCategory(),
                        cti instanceof ChooserTargetInfo ? "direct_share" : "other_target")
                .write();
    }


    public void onActivityStarted(TargetInfo cti) {
        // Do nothing
    }

    @Override // ResolverListCommunicator
    public boolean shouldGetActivityMetadata() {
        return false;
    }

    public boolean shouldAutoLaunchSingleChoice(TargetInfo target) {
        return !target.isSuspended();
    }

    void showTargetDetails(ResolveInfo ri) {
        Intent in = new Intent().setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", ri.activityInfo.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
        startActivityAsUser(in, mMultiProfilePagerAdapter.getCurrentUserHandle());
    }

    @VisibleForTesting
    protected ResolverListAdapter createResolverListAdapter(Context context,
            List<Intent> payloadIntents, Intent[] initialIntents, List<ResolveInfo> rList,
            boolean filterLastUsed, UserHandle userHandle) {
        Intent startIntent = getIntent();
        boolean isAudioCaptureDevice =
                startIntent.getBooleanExtra(EXTRA_IS_AUDIO_CAPTURE_DEVICE, false);
        UserHandle initialIntentsUserSpace = isLaunchedAsCloneProfile()
                && userHandle.equals(getPersonalProfileUserHandle())
                ? getCloneProfileUserHandle() : userHandle;
        return new ResolverListAdapter(context, payloadIntents, initialIntents, rList,
                filterLastUsed, createListController(userHandle), this,
                isAudioCaptureDevice, initialIntentsUserSpace);
    }

    @VisibleForTesting
    protected ResolverListController createListController(UserHandle userHandle) {
        UserHandle queryIntentsUser = getQueryIntentsUser(userHandle);
        ResolverRankerServiceResolverComparator resolverComparator =
                new ResolverRankerServiceResolverComparator(
                        this,
                        getTargetIntent(),
                        getReferrerPackageName(),
                        null,
                        null,
                        getResolverRankerServiceUserHandleList(userHandle));
        return new ResolverListController(
                this,
                mPm,
                getTargetIntent(),
                getReferrerPackageName(),
                mLaunchedFromUid,
                userHandle,
                resolverComparator,
                queryIntentsUser);
    }

    /**
     * Sets up the content view.
     * @return <code>true</code> if the activity is finishing and creation should halt.
     */
    private boolean configureContentView() {
        if (mMultiProfilePagerAdapter.getActiveListAdapter() == null) {
            throw new IllegalStateException("mMultiProfilePagerAdapter.getCurrentListAdapter() "
                    + "cannot be null.");
        }
        Trace.beginSection("configureContentView");
        // We partially rebuild the inactive adapter to determine if we should auto launch
        // isTabLoaded will be true here if the empty state screen is shown instead of the list.
        boolean rebuildCompleted = mMultiProfilePagerAdapter.rebuildActiveTab(true)
                || mMultiProfilePagerAdapter.getActiveListAdapter().isTabLoaded();
        if (shouldShowTabs()) {
            boolean rebuildInactiveCompleted = mMultiProfilePagerAdapter.rebuildInactiveTab(false)
                    || mMultiProfilePagerAdapter.getInactiveListAdapter().isTabLoaded();
            rebuildCompleted = rebuildCompleted && rebuildInactiveCompleted;
        }

        if (shouldUseMiniResolver()) {
            configureMiniResolverContent();
            Trace.endSection();
            return false;
        }

        if (useLayoutWithDefault()) {
            mLayoutId = R.layout.resolver_list_with_default;
        } else {
            mLayoutId = getLayoutResource();
        }
        setContentView(mLayoutId);
        mMultiProfilePagerAdapter.setupViewPager(findViewById(R.id.profile_pager));
        boolean result = postRebuildList(rebuildCompleted);
        Trace.endSection();
        return result;
    }

    /**
     * Mini resolver is shown when the user is choosing between browser[s] in this profile and a
     * single app in the other profile (see shouldUseMiniResolver()). It shows the single app icon
     * and asks the user if they'd like to open that cross-profile app or use the in-profile
     * browser.
     */
    private void configureMiniResolverContent() {
        mLayoutId = R.layout.miniresolver;
        setContentView(mLayoutId);

        DisplayResolveInfo sameProfileResolveInfo =
                mMultiProfilePagerAdapter.getActiveListAdapter().mDisplayList.get(0);
        boolean inWorkProfile = getCurrentProfile() == PROFILE_WORK;

        final ResolverListAdapter inactiveAdapter =
                mMultiProfilePagerAdapter.getInactiveListAdapter();
        final DisplayResolveInfo otherProfileResolveInfo = inactiveAdapter.mDisplayList.get(0);

        // Load the icon asynchronously
        ImageView icon = findViewById(R.id.icon);
        inactiveAdapter.new LoadIconTask(otherProfileResolveInfo) {
            @Override
            protected void onPostExecute(Drawable drawable) {
                if (!isDestroyed()) {
                    otherProfileResolveInfo.setDisplayIcon(drawable);
                    new ResolverListAdapter.ViewHolder(icon).bindIcon(otherProfileResolveInfo);
                }
            }
        }.execute();

        CharSequence targetDisplayLabel = otherProfileResolveInfo.getDisplayLabel();

        DevicePolicyResourcesManager devicePolicyResourcesManager = getSystemService(
                DevicePolicyManager.class).getResources();

        if (inWorkProfile) {
            ((TextView) findViewById(R.id.open_cross_profile)).setText(
                    devicePolicyResourcesManager.getString(MINIRESOLVER_OPEN_IN_PERSONAL,
                            () -> getString(R.string.miniresolver_open_in_personal,
                                    targetDisplayLabel),
                            targetDisplayLabel));
            ((Button) findViewById(R.id.use_same_profile_browser)).setText(
                    devicePolicyResourcesManager.getString(MINIRESOLVER_USE_WORK_BROWSER,
                            () -> getString(R.string.miniresolver_use_work_browser)));
        } else {
            ((TextView) findViewById(R.id.open_cross_profile)).setText(
                    devicePolicyResourcesManager.getString(MINIRESOLVER_OPEN_IN_WORK,
                            () -> getString(R.string.miniresolver_open_in_work,
                                    targetDisplayLabel),
                            targetDisplayLabel));
            ((Button) findViewById(R.id.use_same_profile_browser)).setText(
                    devicePolicyResourcesManager.getString(MINIRESOLVER_USE_PERSONAL_BROWSER,
                            () -> getString(R.string.miniresolver_use_personal_browser)));
        }

        findViewById(R.id.use_same_profile_browser).setOnClickListener(
                v -> {
                    safelyStartActivity(sameProfileResolveInfo);
                    finish();
                });

        findViewById(R.id.button_open).setOnClickListener(v -> {
            Intent intent = otherProfileResolveInfo.getResolvedIntent();
            safelyStartActivityAsUser(otherProfileResolveInfo,
                    inactiveAdapter.mResolverListController.getUserHandle());
            finish();
        });
    }

    /**
     * Mini resolver should be used when all of the following are true:
     * 1. This is the intent picker (ResolverActivity).
     * 2. This profile only has web browser matches.
     * 3. The other profile has a single non-browser match.
     */
    private boolean shouldUseMiniResolver() {
        if (!mIsIntentPicker) {
            return false;
        }
        if (mMultiProfilePagerAdapter.getActiveListAdapter() == null
                || mMultiProfilePagerAdapter.getInactiveListAdapter() == null) {
            return false;
        }
        List<DisplayResolveInfo> sameProfileList =
                mMultiProfilePagerAdapter.getActiveListAdapter().mDisplayList;
        List<DisplayResolveInfo> otherProfileList =
                mMultiProfilePagerAdapter.getInactiveListAdapter().mDisplayList;

        if (sameProfileList.isEmpty()) {
            Log.d(TAG, "No targets in the current profile");
            return false;
        }

        if (otherProfileList.size() != 1) {
            Log.d(TAG, "Found " + otherProfileList.size() + " resolvers in the other profile");
            return false;
        }

        if (otherProfileList.get(0).getResolveInfo().handleAllWebDataURI) {
            Log.d(TAG, "Other profile is a web browser");
            return false;
        }

        for (DisplayResolveInfo info : sameProfileList) {
            if (!info.getResolveInfo().handleAllWebDataURI) {
                Log.d(TAG, "Non-browser found in this profile");
                return false;
            }
        }

        return true;
    }

    /**
     * Finishing procedures to be performed after the list has been rebuilt.
     * </p>Subclasses must call postRebuildListInternal at the end of postRebuildList.
     * @param rebuildCompleted
     * @return <code>true</code> if the activity is finishing and creation should halt.
     */
    protected boolean postRebuildList(boolean rebuildCompleted) {
        return postRebuildListInternal(rebuildCompleted);
    }

    /**
     * Finishing procedures to be performed after the list has been rebuilt.
     * @param rebuildCompleted
     * @return <code>true</code> if the activity is finishing and creation should halt.
     */
    final boolean postRebuildListInternal(boolean rebuildCompleted) {
        int count = mMultiProfilePagerAdapter.getActiveListAdapter().getUnfilteredCount();

        // We only rebuild asynchronously when we have multiple elements to sort. In the case where
        // we're already done, we can check if we should auto-launch immediately.
        if (rebuildCompleted && maybeAutolaunchActivity()) {
            return true;
        }

        setupViewVisibilities();

        if (shouldShowTabs()) {
            setupProfileTabs();
        }

        return false;
    }

    private int isPermissionGranted(String permission, int uid) {
        return ActivityManager.checkComponentPermission(permission, uid,
                /* owningUid= */-1, /* exported= */ true);
    }

    /**
     * @return {@code true} if a resolved target is autolaunched, otherwise {@code false}
     */
    private boolean maybeAutolaunchActivity() {
        int numberOfProfiles = mMultiProfilePagerAdapter.getItemCount();
        if (numberOfProfiles == 1 && maybeAutolaunchIfSingleTarget()) {
            return true;
        } else if (numberOfProfiles == 2
                && mMultiProfilePagerAdapter.getActiveListAdapter().isTabLoaded()
                && mMultiProfilePagerAdapter.getInactiveListAdapter().isTabLoaded()
                && (maybeAutolaunchIfNoAppsOnInactiveTab()
                        || maybeAutolaunchIfCrossProfileSupported())) {
            return true;
        }
        return false;
    }

    private boolean maybeAutolaunchIfSingleTarget() {
        int count = mMultiProfilePagerAdapter.getActiveListAdapter().getUnfilteredCount();
        if (count != 1) {
            return false;
        }

        if (mMultiProfilePagerAdapter.getActiveListAdapter().getOtherProfile() != null) {
            return false;
        }

        // Only one target, so we're a candidate to auto-launch!
        final TargetInfo target = mMultiProfilePagerAdapter.getActiveListAdapter()
                .targetInfoForPosition(0, false);
        if (shouldAutoLaunchSingleChoice(target)) {
            safelyStartActivity(target);
            finish();
            return true;
        }
        return false;
    }

    private boolean maybeAutolaunchIfNoAppsOnInactiveTab() {
        int count = mMultiProfilePagerAdapter.getActiveListAdapter().getUnfilteredCount();
        if (count != 1) {
            return false;
        }
        ResolverListAdapter inactiveListAdapter =
                mMultiProfilePagerAdapter.getInactiveListAdapter();
        if (inactiveListAdapter.getUnfilteredCount() != 0) {
            return false;
        }
        TargetInfo target = mMultiProfilePagerAdapter.getActiveListAdapter()
                .targetInfoForPosition(0, false);
        safelyStartActivity(target);
        finish();
        return true;
    }

    /**
     * When we have a personal and a work profile, we auto launch in the following scenario:
     * - There is 1 resolved target on each profile
     * - That target is the same app on both profiles
     * - The target app has permission to communicate cross profiles
     * - The target app has declared it supports cross-profile communication via manifest metadata
     */
    private boolean maybeAutolaunchIfCrossProfileSupported() {
        ResolverListAdapter activeListAdapter = mMultiProfilePagerAdapter.getActiveListAdapter();
        int count = activeListAdapter.getUnfilteredCount();
        if (count != 1) {
            return false;
        }
        ResolverListAdapter inactiveListAdapter =
                mMultiProfilePagerAdapter.getInactiveListAdapter();
        if (inactiveListAdapter.getUnfilteredCount() != 1) {
            return false;
        }
        TargetInfo activeProfileTarget = activeListAdapter
                .targetInfoForPosition(0, false);
        TargetInfo inactiveProfileTarget = inactiveListAdapter.targetInfoForPosition(0, false);
        if (!Objects.equals(activeProfileTarget.getResolvedComponentName(),
                inactiveProfileTarget.getResolvedComponentName())) {
            return false;
        }
        if (!shouldAutoLaunchSingleChoice(activeProfileTarget)) {
            return false;
        }
        String packageName = activeProfileTarget.getResolvedComponentName().getPackageName();
        if (!canAppInteractCrossProfiles(packageName)) {
            return false;
        }

        DevicePolicyEventLogger
                .createEvent(DevicePolicyEnums.RESOLVER_AUTOLAUNCH_CROSS_PROFILE_TARGET)
                .setBoolean(activeListAdapter.getUserHandle()
                        .equals(getPersonalProfileUserHandle()))
                .setStrings(getMetricsCategory())
                .write();
        safelyStartActivity(activeProfileTarget);
        finish();
        return true;
    }

    /**
     * Returns whether the package has the necessary permissions to interact across profiles on
     * behalf of a given user.
     *
     * <p>This means meeting the following condition:
     * <ul>
     *     <li>The app's {@link ApplicationInfo#crossProfile} flag must be true, and at least
     *     one of the following conditions must be fulfilled</li>
     *     <li>{@code Manifest.permission.INTERACT_ACROSS_USERS_FULL} granted.</li>
     *     <li>{@code Manifest.permission.INTERACT_ACROSS_USERS} granted.</li>
     *     <li>{@code Manifest.permission.INTERACT_ACROSS_PROFILES} granted, or the corresponding
     *     AppOps {@code android:interact_across_profiles} is set to "allow".</li>
     * </ul>
     *
     */
    private boolean canAppInteractCrossProfiles(String packageName) {
        ApplicationInfo applicationInfo;
        try {
            applicationInfo = getPackageManager().getApplicationInfo(packageName, 0);
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Package " + packageName + " does not exist on current user.");
            return false;
        }
        if (!applicationInfo.crossProfile) {
            return false;
        }

        int packageUid = applicationInfo.uid;

        if (isPermissionGranted(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                packageUid) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        if (isPermissionGranted(android.Manifest.permission.INTERACT_ACROSS_USERS, packageUid)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        if (PermissionChecker.checkPermissionForPreflight(this, INTERACT_ACROSS_PROFILES,
                PID_UNKNOWN, packageUid, packageName) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        return false;
    }

    private boolean isAutolaunching() {
        return !mRegistered && isFinishing();
    }

    private void setupProfileTabs() {
        maybeHideDivider();
        TabHost tabHost = findViewById(R.id.profile_tabhost);
        tabHost.setup();
        ViewPager viewPager = findViewById(R.id.profile_pager);
        viewPager.setSaveEnabled(false);

        Button personalButton = (Button) getLayoutInflater().inflate(
                R.layout.resolver_profile_tab_button, tabHost.getTabWidget(), false);
        personalButton.setText(getPersonalTabLabel());
        personalButton.setContentDescription(getPersonalTabAccessibilityLabel());

        TabHost.TabSpec tabSpec = tabHost.newTabSpec(TAB_TAG_PERSONAL)
                .setContent(R.id.profile_pager)
                .setIndicator(personalButton);
        tabHost.addTab(tabSpec);

        Button workButton = (Button) getLayoutInflater().inflate(
                R.layout.resolver_profile_tab_button, tabHost.getTabWidget(), false);
        workButton.setText(getWorkTabLabel());
        workButton.setContentDescription(getWorkTabAccessibilityLabel());

        tabSpec = tabHost.newTabSpec(TAB_TAG_WORK)
                .setContent(R.id.profile_pager)
                .setIndicator(workButton);
        tabHost.addTab(tabSpec);

        TabWidget tabWidget = tabHost.getTabWidget();
        tabWidget.setVisibility(View.VISIBLE);
        updateActiveTabStyle(tabHost);

        tabHost.setOnTabChangedListener(tabId -> {
            updateActiveTabStyle(tabHost);
            if (TAB_TAG_PERSONAL.equals(tabId)) {
                viewPager.setCurrentItem(0);
            } else {
                viewPager.setCurrentItem(1);
            }
            setupViewVisibilities();
            maybeLogProfileChange();
            onProfileTabSelected();
            DevicePolicyEventLogger
                    .createEvent(DevicePolicyEnums.RESOLVER_SWITCH_TABS)
                    .setInt(viewPager.getCurrentItem())
                    .setStrings(getMetricsCategory())
                    .write();
        });

        viewPager.setVisibility(View.VISIBLE);
        tabHost.setCurrentTab(mMultiProfilePagerAdapter.getCurrentPage());
        mMultiProfilePagerAdapter.setOnProfileSelectedListener(
                new AbstractMultiProfilePagerAdapter.OnProfileSelectedListener() {
                    @Override
                    public void onProfileSelected(int index) {
                        tabHost.setCurrentTab(index);
                        resetButtonBar();
                        resetCheckedItem();
                    }

                    @Override
                    public void onProfilePageStateChanged(int state) {
                        onHorizontalSwipeStateChanged(state);
                    }
                });
        mOnSwitchOnWorkSelectedListener = () -> {
            final View workTab = tabHost.getTabWidget().getChildAt(1);
            workTab.setFocusable(true);
            workTab.setFocusableInTouchMode(true);
            workTab.requestFocus();
        };
    }

    private String getPersonalTabLabel() {
        return getSystemService(DevicePolicyManager.class).getResources().getString(
                RESOLVER_PERSONAL_TAB, () -> getString(R.string.resolver_personal_tab));
    }

    private String getWorkTabLabel() {
        return getSystemService(DevicePolicyManager.class).getResources().getString(
                RESOLVER_WORK_TAB, () -> getString(R.string.resolver_work_tab));
    }

    void onHorizontalSwipeStateChanged(int state) {}

    private void maybeHideDivider() {
        if (!mIsIntentPicker) {
            return;
        }
        final View divider = findViewById(R.id.divider);
        if (divider == null) {
            return;
        }
        divider.setVisibility(View.GONE);
    }

    /**
     * Callback called when user changes the profile tab.
     * <p>This method is intended to be overridden by subclasses.
     */
    protected void onProfileTabSelected() { }

    private void resetCheckedItem() {
        if (!mIsIntentPicker) {
            return;
        }
        mLastSelected = ListView.INVALID_POSITION;
        ListView inactiveListView = (ListView) mMultiProfilePagerAdapter.getInactiveAdapterView();
        if (inactiveListView.getCheckedItemCount() > 0) {
            inactiveListView.setItemChecked(inactiveListView.getCheckedItemPosition(), false);
        }
    }

    private String getPersonalTabAccessibilityLabel() {
        return getSystemService(DevicePolicyManager.class).getResources().getString(
                RESOLVER_PERSONAL_TAB_ACCESSIBILITY,
                () -> getString(R.string.resolver_personal_tab_accessibility));
    }

    private String getWorkTabAccessibilityLabel() {
        return getSystemService(DevicePolicyManager.class).getResources().getString(
                RESOLVER_WORK_TAB_ACCESSIBILITY,
                () -> getString(R.string.resolver_work_tab_accessibility));
    }

    private static int getAttrColor(Context context, int attr) {
        TypedArray ta = context.obtainStyledAttributes(new int[]{attr});
        int colorAccent = ta.getColor(0, 0);
        ta.recycle();
        return colorAccent;
    }

    private void updateActiveTabStyle(TabHost tabHost) {
        int currentTab = tabHost.getCurrentTab();
        TextView selected = (TextView) tabHost.getTabWidget().getChildAt(currentTab);
        TextView unselected = (TextView) tabHost.getTabWidget().getChildAt(1 - currentTab);
        selected.setSelected(true);
        unselected.setSelected(false);
    }

    private void setupViewVisibilities() {
        ResolverListAdapter activeListAdapter = mMultiProfilePagerAdapter.getActiveListAdapter();
        if (!mMultiProfilePagerAdapter.shouldShowEmptyStateScreen(activeListAdapter)) {
            addUseDifferentAppLabelIfNecessary(activeListAdapter);
        }
    }

    /**
     * Add a label to signify that the user can pick a different app.
     * @param adapter The adapter used to provide data to item views.
     */
    public void addUseDifferentAppLabelIfNecessary(ResolverListAdapter adapter) {
        final boolean useHeader = adapter.hasFilteredItem();
        if (useHeader) {
            FrameLayout stub = findViewById(R.id.stub);
            stub.setVisibility(View.VISIBLE);
            TextView textView = (TextView) LayoutInflater.from(this).inflate(
                    R.layout.resolver_different_item_header, null, false);
            if (shouldShowTabs()) {
                textView.setGravity(Gravity.CENTER);
            }
            stub.addView(textView);
        }
    }

    private void setupAdapterListView(ListView listView, ItemClickListener listener) {
        listView.setOnItemClickListener(listener);
        listView.setOnItemLongClickListener(listener);

        if (mSupportsAlwaysUseOption) {
            listView.setChoiceMode(AbsListView.CHOICE_MODE_SINGLE);
        }
    }

    /**
     * Configure the area above the app selection list (title, content preview, etc).
     */
    private void maybeCreateHeader(ResolverListAdapter listAdapter) {
        if (mHeaderCreatorUser != null
                && !listAdapter.getUserHandle().equals(mHeaderCreatorUser)) {
            return;
        }
        if (!shouldShowTabs()
                && listAdapter.getCount() == 0 && listAdapter.getPlaceholderCount() == 0) {
            final TextView titleView = findViewById(R.id.title);
            if (titleView != null) {
                titleView.setVisibility(View.GONE);
            }
        }

        CharSequence title = mTitle != null
                ? mTitle
                : getTitleForAction(getTargetIntent(), mDefaultTitleResId);

        if (!TextUtils.isEmpty(title)) {
            final TextView titleView = findViewById(R.id.title);
            if (titleView != null) {
                titleView.setText(title);
            }
            setTitle(title);
        }

        final ImageView iconView = findViewById(R.id.icon);
        if (iconView != null) {
            listAdapter.loadFilteredItemIconTaskAsync(iconView);
        }
        mHeaderCreatorUser = listAdapter.getUserHandle();
    }

    protected void resetButtonBar() {
        if (!mSupportsAlwaysUseOption) {
            return;
        }
        final ViewGroup buttonLayout = findViewById(R.id.button_bar);
        if (buttonLayout == null) {
            Log.e(TAG, "Layout unexpectedly does not have a button bar");
            return;
        }
        ResolverListAdapter activeListAdapter =
                mMultiProfilePagerAdapter.getActiveListAdapter();
        View buttonBarDivider = findViewById(R.id.resolver_button_bar_divider);
        if (!useLayoutWithDefault()) {
            int inset = mSystemWindowInsets != null ? mSystemWindowInsets.bottom : 0;
            buttonLayout.setPadding(buttonLayout.getPaddingLeft(), buttonLayout.getPaddingTop(),
                    buttonLayout.getPaddingRight(), getResources().getDimensionPixelSize(
                            R.dimen.resolver_button_bar_spacing) + inset);
        }
        if (activeListAdapter.isTabLoaded()
                && mMultiProfilePagerAdapter.shouldShowEmptyStateScreen(activeListAdapter)
                && !useLayoutWithDefault()) {
            buttonLayout.setVisibility(View.INVISIBLE);
            if (buttonBarDivider != null) {
                buttonBarDivider.setVisibility(View.INVISIBLE);
            }
            setButtonBarIgnoreOffset(/* ignoreOffset */ false);
            return;
        }
        if (buttonBarDivider != null) {
            buttonBarDivider.setVisibility(View.VISIBLE);
        }
        buttonLayout.setVisibility(View.VISIBLE);
        setButtonBarIgnoreOffset(/* ignoreOffset */ true);

        mOnceButton = (Button) buttonLayout.findViewById(R.id.button_once);
        mAlwaysButton = (Button) buttonLayout.findViewById(R.id.button_always);

        resetAlwaysOrOnceButtonBar();
    }

    /**
     * Updates the button bar container {@code ignoreOffset} layout param.
     * <p>Setting this to {@code true} means that the button bar will be glued to the bottom of
     * the screen.
     */
    private void setButtonBarIgnoreOffset(boolean ignoreOffset) {
        View buttonBarContainer = findViewById(R.id.button_bar_container);
        if (buttonBarContainer != null) {
            ResolverDrawerLayout.LayoutParams layoutParams =
                    (ResolverDrawerLayout.LayoutParams) buttonBarContainer.getLayoutParams();
            layoutParams.ignoreOffset = ignoreOffset;
            buttonBarContainer.setLayoutParams(layoutParams);
        }
    }

    private void resetAlwaysOrOnceButtonBar() {
        // Disable both buttons initially
        setAlwaysButtonEnabled(false, ListView.INVALID_POSITION, false);
        mOnceButton.setEnabled(false);

        int filteredPosition = mMultiProfilePagerAdapter.getActiveListAdapter()
                .getFilteredPosition();
        if (useLayoutWithDefault() && filteredPosition != ListView.INVALID_POSITION) {
            setAlwaysButtonEnabled(true, filteredPosition, false);
            mOnceButton.setEnabled(true);
            // Focus the button if we already have the default option
            mOnceButton.requestFocus();
            return;
        }

        // When the items load in, if an item was already selected, enable the buttons
        ListView currentAdapterView = (ListView) mMultiProfilePagerAdapter.getActiveAdapterView();
        if (currentAdapterView != null
                && currentAdapterView.getCheckedItemPosition() != ListView.INVALID_POSITION) {
            setAlwaysButtonEnabled(true, currentAdapterView.getCheckedItemPosition(), true);
            mOnceButton.setEnabled(true);
        }
    }

    @Override // ResolverListCommunicator
    public boolean useLayoutWithDefault() {
        // We only use the default app layout when the profile of the active user has a
        // filtered item. We always show the same default app even in the inactive user profile.
        boolean adapterForCurrentUserHasFilteredItem =
                mMultiProfilePagerAdapter.getListAdapterForUserHandle(
                        getTabOwnerUserHandleForLaunch()).hasFilteredItem();
        return mSupportsAlwaysUseOption && adapterForCurrentUserHasFilteredItem;
    }

    /**
     * If {@code retainInOnStop} is set to true, we will not finish ourselves when onStop gets
     * called and we are launched in a new task.
     */
    protected void setRetainInOnStop(boolean retainInOnStop) {
        mRetainInOnStop = retainInOnStop;
    }

    /**
     * Check a simple match for the component of two ResolveInfos.
     */
    @Override // ResolverListCommunicator
    public boolean resolveInfoMatch(ResolveInfo lhs, ResolveInfo rhs) {
        return lhs == null ? rhs == null
                : lhs.activityInfo == null ? rhs.activityInfo == null
                : Objects.equals(lhs.activityInfo.name, rhs.activityInfo.name)
                && Objects.equals(lhs.activityInfo.packageName, rhs.activityInfo.packageName)
                        // Comparing against resolveInfo.userHandle in case cloned apps are present,
                        // as they will have the same activityInfo.
                && Objects.equals(
                        getResolveInfoUserHandle(lhs,
                                mMultiProfilePagerAdapter.getActiveListAdapter().getUserHandle()),
                        getResolveInfoUserHandle(rhs,
                                mMultiProfilePagerAdapter.getActiveListAdapter().getUserHandle()));
    }

    protected String getMetricsCategory() {
        return METRICS_CATEGORY_RESOLVER;
    }

    @Override // ResolverListCommunicator
    public void onHandlePackagesChanged(ResolverListAdapter listAdapter) {
        if (listAdapter == mMultiProfilePagerAdapter.getActiveListAdapter()) {
            if (listAdapter.getUserHandle().equals(getWorkProfileUserHandle())
                    && mQuietModeManager.isWaitingToEnableWorkProfile()) {
                // We have just turned on the work profile and entered the pass code to start it,
                // now we are waiting to receive the ACTION_USER_UNLOCKED broadcast. There is no
                // point in reloading the list now, since the work profile user is still
                // turning on.
                return;
            }
            boolean listRebuilt = mMultiProfilePagerAdapter.rebuildActiveTab(true);
            if (listRebuilt) {
                ResolverListAdapter activeListAdapter =
                        mMultiProfilePagerAdapter.getActiveListAdapter();
                activeListAdapter.notifyDataSetChanged();
                if (activeListAdapter.getCount() == 0 && !inactiveListAdapterHasItems()) {
                    // We no longer have any items...  just finish the activity.
                    finish();
                }
            }
        } else {
            mMultiProfilePagerAdapter.clearInactiveProfileCache();
        }
    }

    private boolean inactiveListAdapterHasItems() {
        if (!shouldShowTabs()) {
            return false;
        }
        return mMultiProfilePagerAdapter.getInactiveListAdapter().getCount() > 0;
    }

    private BroadcastReceiver createWorkProfileStateReceiver() {
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (!TextUtils.equals(action, Intent.ACTION_USER_UNLOCKED)
                        && !TextUtils.equals(action, Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
                        && !TextUtils.equals(action, Intent.ACTION_MANAGED_PROFILE_AVAILABLE)) {
                    return;
                }

                int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);

                if (userId != getWorkProfileUserHandle().getIdentifier()) {
                    return;
                }

                if (isWorkProfileEnabled()) {
                    if (mWorkProfileHasBeenEnabled) {
                        return;
                    }

                    mWorkProfileHasBeenEnabled = true;
                    mQuietModeManager.markWorkProfileEnabledBroadcastReceived();
                } else {
                    // Must be an UNAVAILABLE broadcast, so we watch for the next availability
                    mWorkProfileHasBeenEnabled = false;
                }

                if (mMultiProfilePagerAdapter.getCurrentUserHandle()
                        .equals(getWorkProfileUserHandle())) {
                    mMultiProfilePagerAdapter.rebuildActiveTab(true);
                } else {
                    mMultiProfilePagerAdapter.clearInactiveProfileCache();
                }
            }
        };
    }

    public static final class ResolvedComponentInfo {
        public final ComponentName name;
        private final List<Intent> mIntents = new ArrayList<>();
        private final List<ResolveInfo> mResolveInfos = new ArrayList<>();
        private boolean mPinned;
        private boolean mFixedAtTop;

        public ResolvedComponentInfo(ComponentName name, Intent intent, ResolveInfo info) {
            this.name = name;
            add(intent, info);
        }

        public void add(Intent intent, ResolveInfo info) {
            mIntents.add(intent);
            mResolveInfos.add(info);
        }

        public int getCount() {
            return mIntents.size();
        }

        public Intent getIntentAt(int index) {
            return index >= 0 ? mIntents.get(index) : null;
        }

        public ResolveInfo getResolveInfoAt(int index) {
            return index >= 0 ? mResolveInfos.get(index) : null;
        }

        public int findIntent(Intent intent) {
            for (int i = 0, N = mIntents.size(); i < N; i++) {
                if (intent.equals(mIntents.get(i))) {
                    return i;
                }
            }
            return -1;
        }

        public int findResolveInfo(ResolveInfo info) {
            for (int i = 0, N = mResolveInfos.size(); i < N; i++) {
                if (info.equals(mResolveInfos.get(i))) {
                    return i;
                }
            }
            return -1;
        }

        public boolean isPinned() {
            return mPinned;
        }

        public void setPinned(boolean pinned) {
            mPinned = pinned;
        }

        public boolean isFixedAtTop() {
            return mFixedAtTop;
        }

        public void setFixedAtTop(boolean isFixedAtTop) {
            mFixedAtTop = isFixedAtTop;
        }
    }

    class ItemClickListener implements AdapterView.OnItemClickListener,
            AdapterView.OnItemLongClickListener {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            final ListView listView = parent instanceof ListView ? (ListView) parent : null;
            if (listView != null) {
                position -= listView.getHeaderViewsCount();
            }
            if (position < 0) {
                // Header views don't count.
                return;
            }
            // If we're still loading, we can't yet enable the buttons.
            if (mMultiProfilePagerAdapter.getActiveListAdapter()
                    .resolveInfoForPosition(position, true) == null) {
                return;
            }
            ListView currentAdapterView =
                    (ListView) mMultiProfilePagerAdapter.getActiveAdapterView();
            final int checkedPos = currentAdapterView.getCheckedItemPosition();
            final boolean hasValidSelection = checkedPos != ListView.INVALID_POSITION;
            if (!useLayoutWithDefault()
                    && (!hasValidSelection || mLastSelected != checkedPos)
                    && mAlwaysButton != null) {
                setAlwaysButtonEnabled(hasValidSelection, checkedPos, true);
                mOnceButton.setEnabled(hasValidSelection);
                if (hasValidSelection) {
                    currentAdapterView.smoothScrollToPosition(checkedPos);
                    mOnceButton.requestFocus();
                }
                mLastSelected = checkedPos;
            } else {
                startSelected(position, false, true);
            }
        }

        @Override
        public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
            final ListView listView = parent instanceof ListView ? (ListView) parent : null;
            if (listView != null) {
                position -= listView.getHeaderViewsCount();
            }
            if (position < 0) {
                // Header views don't count.
                return false;
            }
            ResolveInfo ri = mMultiProfilePagerAdapter.getActiveListAdapter()
                    .resolveInfoForPosition(position, true);
            showTargetDetails(ri);
            return true;
        }

    }

    static final boolean isSpecificUriMatch(int match) {
        match = match&IntentFilter.MATCH_CATEGORY_MASK;
        return match >= IntentFilter.MATCH_CATEGORY_HOST
                && match <= IntentFilter.MATCH_CATEGORY_PATH;
    }

    static class PickTargetOptionRequest extends PickOptionRequest {
        public PickTargetOptionRequest(@Nullable Prompt prompt, Option[] options,
                @Nullable Bundle extras) {
            super(prompt, options, extras);
        }

        @Override
        public void onCancel() {
            super.onCancel();
            final ResolverActivity ra = (ResolverActivity) getActivity();
            if (ra != null) {
                ra.mPickOptionRequest = null;
                ra.finish();
            }
        }

        @Override
        public void onPickOptionResult(boolean finished, Option[] selections, Bundle result) {
            super.onPickOptionResult(finished, selections, result);
            if (selections.length != 1) {
                // TODO In a better world we would filter the UI presented here and let the
                // user refine. Maybe later.
                return;
            }

            final ResolverActivity ra = (ResolverActivity) getActivity();
            if (ra != null) {
                final TargetInfo ti = ra.mMultiProfilePagerAdapter.getActiveListAdapter()
                        .getItem(selections[0].getIndex());
                if (ra.onTargetSelected(ti, false)) {
                    ra.mPickOptionRequest = null;
                    ra.finish();
                }
            }
        }
    }

    protected void maybeLogProfileChange() {}

    /**
     * Returns the {@link UserHandle} to use when querying resolutions for intents in a
     * {@link ResolverListController} configured for the provided {@code userHandle}.
     */
    protected final UserHandle getQueryIntentsUser(UserHandle userHandle) {
        // In case launching app is in clonedProfile, and we are building the personal tab, intent
        // resolution will be attempted as clonedUser instead of user 0. This is because intent
        // resolution from user 0 and clonedUser is not guaranteed to return same results.
        // We do not care about the case when personal adapter is started with non-root user
        // (secondary user case), as clone profile is guaranteed to be non-active in that case.
        UserHandle queryIntentsUser = userHandle;
        if (isLaunchedAsCloneProfile() && userHandle.equals(getPersonalProfileUserHandle())) {
            queryIntentsUser = getCloneProfileUserHandle();
        }
        return queryIntentsUser;
    }

    /**
     * Returns the {@link List} of {@link UserHandle} to pass on to the
     * {@link ResolverRankerServiceResolverComparator} as per the provided {@code userHandle}.
     */
    @VisibleForTesting(visibility = PROTECTED)
    public final List<UserHandle> getResolverRankerServiceUserHandleList(UserHandle userHandle) {
        return getResolverRankerServiceUserHandleListInternal(userHandle);
    }

    @VisibleForTesting
    protected List<UserHandle> getResolverRankerServiceUserHandleListInternal(UserHandle
            userHandle) {
        List<UserHandle> userList = new ArrayList<>();
        userList.add(userHandle);
        // Add clonedProfileUserHandle to the list only if we are:
        // a. Building the Personal Tab.
        // b. CloneProfile exists on the device.
        if (userHandle.equals(getPersonalProfileUserHandle())
                && getCloneProfileUserHandle() != null) {
            userList.add(getCloneProfileUserHandle());
        }
        return userList;
    }

    /**
     * This function is temporary in nature, and its usages will be replaced with just
     * resolveInfo.userHandle, once it is available, once sharesheet is stable.
     */
    public static UserHandle getResolveInfoUserHandle(ResolveInfo resolveInfo,
            UserHandle predictedHandle) {
        if (resolveInfo.userHandle == null) {
            Log.e(TAG, "ResolveInfo with null UserHandle found: " + resolveInfo);
        }
        return resolveInfo.userHandle;
    }

    private boolean privateSpaceEnabled() {
        return mIsIntentPicker && android.os.Flags.allowPrivateProfile()
                && android.multiuser.Flags.allowResolverSheetForPrivateSpace()
                && android.multiuser.Flags.enablePrivateSpaceFeatures();
    }

    /**
     * An a11y delegate that expands resolver drawer when gesture navigation reaches a partially
     * invisible target in the list.
     */
    public static class AppListAccessibilityDelegate extends View.AccessibilityDelegate {
        private final ResolverDrawerLayout mDrawer;
        @Nullable
        private final View mBottomBar;
        private final Rect mRect = new Rect();

        public AppListAccessibilityDelegate(ResolverDrawerLayout drawer) {
            mDrawer = drawer;
            mBottomBar = mDrawer.findViewById(R.id.button_bar_container);
        }

        @Override
        public boolean onRequestSendAccessibilityEvent(@androidx.annotation.NonNull ViewGroup host,
                @NonNull View child,
                @NonNull AccessibilityEvent event) {
            boolean result = super.onRequestSendAccessibilityEvent(host, child, event);
            if (result && event.getEventType() == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
                    && mDrawer.isCollapsed()) {
                child.getBoundsOnScreen(mRect);
                int childTop = mRect.top;
                int childBottom = mRect.bottom;
                mDrawer.getBoundsOnScreen(mRect, true);
                int bottomBarHeight = mBottomBar == null ? 0 : mBottomBar.getHeight();
                int drawerTop = mRect.top;
                int drawerBottom = mRect.bottom - bottomBarHeight;
                if (drawerTop > childTop || childBottom > drawerBottom) {
                    mDrawer.setCollapsed(false);
                }
            }
            return result;
        }
    }
}
