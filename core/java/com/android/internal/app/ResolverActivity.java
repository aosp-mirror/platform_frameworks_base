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

import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.UiThread;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.VoiceInteractor.PickOptionRequest;
import android.app.VoiceInteractor.PickOptionRequest.Option;
import android.app.VoiceInteractor.Prompt;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.LabeledIntent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.PatternMatcher;
import android.os.RemoteException;
import android.os.StrictMode;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.IconDrawableFactory;
import android.util.Log;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.widget.ResolverDrawerLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

/**
 * This activity is displayed when the system attempts to start an Intent for
 * which there is more than one matching activity, allowing the user to decide
 * which to go to.  It is not normally used directly by application developers.
 */
@UiThread
public class ResolverActivity extends Activity {

    protected ResolveListAdapter mAdapter;
    private boolean mSafeForwardingMode;
    private AbsListView mAdapterView;
    private Button mAlwaysButton;
    private Button mOnceButton;
    private View mProfileView;
    private int mIconDpi;
    private int mLastSelected = AbsListView.INVALID_POSITION;
    private boolean mResolvingHome = false;
    private int mProfileSwitchMessageId = -1;
    private int mLayoutId;
    private final ArrayList<Intent> mIntents = new ArrayList<>();
    private PickTargetOptionRequest mPickOptionRequest;
    private String mReferrerPackage;
    private CharSequence mTitle;
    private int mDefaultTitleResId;

    // Whether or not this activity supports choosing a default handler for the intent.
    private boolean mSupportsAlwaysUseOption;
    protected ResolverDrawerLayout mResolverDrawerLayout;
    protected PackageManager mPm;
    protected int mLaunchedFromUid;

    private static final String TAG = "ResolverActivity";
    private static final boolean DEBUG = false;
    private Runnable mPostListReadyRunnable;

    private boolean mRegistered;

    /** See {@link #setRetainInOnStop}. */
    private boolean mRetainInOnStop;

    IconDrawableFactory mIconFactory;

    private final PackageMonitor mPackageMonitor = new PackageMonitor() {
        @Override public void onSomePackagesChanged() {
            mAdapter.handlePackagesChanged();
            if (mProfileView != null) {
                bindProfileView();
            }
        }

        @Override
        public boolean onPackageChanged(String packageName, int uid, String[] components) {
            // We care about all package changes, not just the whole package itself which is
            // default behavior.
            return true;
        }
    };

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

    private Intent makeMyIntent() {
        Intent intent = new Intent(getIntent());
        intent.setComponent(null);
        // The resolver activity is set to be hidden from recent tasks.
        // we don't want this attribute to be propagated to the next activity
        // being launched.  Note that if the original Intent also had this
        // flag set, we are now losing it.  That should be a very rare case
        // and we can live with this.
        intent.setFlags(intent.getFlags()&~Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        return intent;
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
    protected void onCreate(Bundle savedInstanceState, Intent intent,
            CharSequence title, Intent[] initialIntents,
            List<ResolveInfo> rList, boolean supportsAlwaysUseOption) {
        onCreate(savedInstanceState, intent, title, 0, initialIntents, rList,
                supportsAlwaysUseOption);
    }

    protected void onCreate(Bundle savedInstanceState, Intent intent,
            CharSequence title, int defaultTitleRes, Intent[] initialIntents,
            List<ResolveInfo> rList, boolean supportsAlwaysUseOption) {
        setTheme(R.style.Theme_DeviceDefault_Resolver);
        super.onCreate(savedInstanceState);

        // Determine whether we should show that intent is forwarded
        // from managed profile to owner or other way around.
        setProfileSwitchMessageId(intent.getContentUserHint());

        try {
            mLaunchedFromUid = ActivityManager.getService().getLaunchedFromUid(
                    getActivityToken());
        } catch (RemoteException e) {
            mLaunchedFromUid = -1;
        }

        if (mLaunchedFromUid < 0 || UserHandle.isIsolated(mLaunchedFromUid)) {
            // Gulp!
            finish();
            return;
        }

        mPm = getPackageManager();

        mPackageMonitor.register(this, getMainLooper(), false);
        mRegistered = true;
        mReferrerPackage = getReferrerPackageName();
        mSupportsAlwaysUseOption = supportsAlwaysUseOption;

        final ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        mIconDpi = am.getLauncherLargeIconDensity();

        // Add our initial intent as the first item, regardless of what else has already been added.
        mIntents.add(0, new Intent(intent));
        mTitle = title;
        mDefaultTitleResId = defaultTitleRes;

        if (configureContentView(mIntents, initialIntents, rList)) {
            return;
        }

        final ResolverDrawerLayout rdl = findViewById(R.id.contentPanel);
        if (rdl != null) {
            rdl.setOnDismissedListener(new ResolverDrawerLayout.OnDismissedListener() {
                @Override
                public void onDismissed() {
                    finish();
                }
            });
            if (isVoiceInteraction()) {
                rdl.setCollapsed(false);
            }
            mResolverDrawerLayout = rdl;
        }

        mProfileView = findViewById(R.id.profile_button);
        if (mProfileView != null) {
            mProfileView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final DisplayResolveInfo dri = mAdapter.getOtherProfile();
                    if (dri == null) {
                        return;
                    }

                    // Do not show the profile switch message anymore.
                    mProfileSwitchMessageId = -1;

                    onTargetSelected(dri, false);
                    finish();
                }
            });
            bindProfileView();
        }

        if (isVoiceInteraction()) {
            onSetupVoiceInteraction();
        }
        final Set<String> categories = intent.getCategories();
        MetricsLogger.action(this, mAdapter.hasFilteredItem()
                ? MetricsProto.MetricsEvent.ACTION_SHOW_APP_DISAMBIG_APP_FEATURED
                : MetricsProto.MetricsEvent.ACTION_SHOW_APP_DISAMBIG_NONE_FEATURED,
                intent.getAction() + ":" + intent.getType() + ":"
                        + (categories != null ? Arrays.toString(categories.toArray()) : ""));
        mIconFactory = IconDrawableFactory.newInstance(this, true);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mAdapter.handlePackagesChanged();
    }

    /**
     * Perform any initialization needed for voice interaction.
     */
    public void onSetupVoiceInteraction() {
        // Do it right now. Subclasses may delay this and send it later.
        sendVoiceChoicesIfNeeded();
    }

    public void sendVoiceChoicesIfNeeded() {
        if (!isVoiceInteraction()) {
            // Clearly not needed.
            return;
        }


        final Option[] options = new Option[mAdapter.getCount()];
        for (int i = 0, N = options.length; i < N; i++) {
            options[i] = optionForChooserTarget(mAdapter.getItem(i), i);
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

    void bindProfileView() {
        final DisplayResolveInfo dri = mAdapter.getOtherProfile();
        if (dri != null) {
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

    private void setProfileSwitchMessageId(int contentUserHint) {
        if (contentUserHint != UserHandle.USER_CURRENT &&
                contentUserHint != UserHandle.myUserId()) {
            UserManager userManager = (UserManager) getSystemService(Context.USER_SERVICE);
            UserInfo originUserInfo = userManager.getUserInfo(contentUserHint);
            boolean originIsManaged = originUserInfo != null ? originUserInfo.isManagedProfile()
                    : false;
            boolean targetIsManaged = userManager.isManagedProfile();
            if (originIsManaged && !targetIsManaged) {
                mProfileSwitchMessageId = com.android.internal.R.string.forward_intent_to_owner;
            } else if (!originIsManaged && targetIsManaged) {
                mProfileSwitchMessageId = com.android.internal.R.string.forward_intent_to_work;
            }
        }
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

    protected CharSequence getTitleForAction(String action, int defaultTitleRes) {
        final ActionTitle title = mResolvingHome ? ActionTitle.HOME : ActionTitle.forAction(action);
        // While there may already be a filtered item, we can only use it in the title if the list
        // is already sorted and all information relevant to it is already in the list.
        final boolean named = mAdapter.getFilteredPosition() >= 0;
        if (title == ActionTitle.DEFAULT && defaultTitleRes != 0) {
            return getString(defaultTitleRes);
        } else {
            return named
                    ? getString(title.namedTitleRes, mAdapter.getFilteredItem().getDisplayLabel())
                    : getString(title.titleRes);
        }
    }

    void dismiss() {
        if (!isFinishing()) {
            finish();
        }
    }

    Drawable getIcon(Resources res, int resId) {
        Drawable result;
        try {
            result = res.getDrawableForDensity(resId, mIconDpi);
        } catch (Resources.NotFoundException e) {
            result = null;
        }

        return result;
    }

    Drawable loadIconForResolveInfo(ResolveInfo ri) {
        Drawable dr;
        try {
            if (ri.resolvePackageName != null && ri.icon != 0) {
                dr = getIcon(mPm.getResourcesForApplication(ri.resolvePackageName), ri.icon);
                if (dr != null) {
                    return mIconFactory.getShadowedIcon(dr);
                }
            }
            final int iconRes = ri.getIconResource();
            if (iconRes != 0) {
                dr = getIcon(mPm.getResourcesForApplication(ri.activityInfo.packageName), iconRes);
                if (dr != null) {
                    return mIconFactory.getShadowedIcon(dr);
                }
            }
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Couldn't find resources for package", e);
        }
        return mIconFactory.getBadgedIcon(ri.activityInfo.applicationInfo);
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        if (!mRegistered) {
            mPackageMonitor.register(this, getMainLooper(), false);
            mRegistered = true;
        }
        mAdapter.handlePackagesChanged();
        if (mProfileView != null) {
            bindProfileView();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mRegistered) {
            mPackageMonitor.unregister();
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
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!isChangingConfigurations() && mPickOptionRequest != null) {
            mPickOptionRequest.cancel();
        }
        if (mPostListReadyRunnable != null) {
            getMainThreadHandler().removeCallbacks(mPostListReadyRunnable);
            mPostListReadyRunnable = null;
        }
        if (mAdapter != null && mAdapter.mResolverListController != null) {
            mAdapter.mResolverListController.destroy();
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        resetAlwaysOrOnceButtonBar();
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
        boolean enabled = false;
        if (hasValidSelection) {
            ResolveInfo ri = mAdapter.resolveInfoForPosition(checkedPos, filtered);
            if (ri == null) {
                Log.e(TAG, "Invalid position supplied to setAlwaysButtonEnabled");
                return;
            } else if (ri.targetUserId != UserHandle.USER_CURRENT) {
                Log.e(TAG, "Attempted to set selection to resolve info for another user");
                return;
            } else {
                enabled = true;
            }
        }
        mAlwaysButton.setEnabled(enabled);
    }

    public void onButtonClick(View v) {
        final int id = v.getId();
        startSelected(mAdapter.hasFilteredItem() ?
                        mAdapter.getFilteredPosition():
                        mAdapterView.getCheckedItemPosition(),
                id == R.id.button_always,
                !mAdapter.hasFilteredItem());
    }

    public void startSelected(int which, boolean always, boolean hasIndexBeenFiltered) {
        if (isFinishing()) {
            return;
        }
        ResolveInfo ri = mAdapter.resolveInfoForPosition(which, hasIndexBeenFiltered);
        if (mResolvingHome && hasManagedProfile() && !supportsManagedProfiles(ri)) {
            Toast.makeText(this, String.format(getResources().getString(
                    com.android.internal.R.string.activity_resolver_work_profiles_support),
                    ri.activityInfo.loadLabel(getPackageManager()).toString()),
                    Toast.LENGTH_LONG).show();
            return;
        }

        TargetInfo target = mAdapter.targetInfoForPosition(which, hasIndexBeenFiltered);
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
            MetricsLogger.action(this, mAdapter.hasFilteredItem()
                            ? MetricsProto.MetricsEvent.ACTION_HIDE_APP_DISAMBIG_APP_FEATURED
                            : MetricsProto.MetricsEvent.ACTION_HIDE_APP_DISAMBIG_NONE_FEATURED);
            finish();
        }
    }

    /**
     * Replace me in subclasses!
     */
    public Intent getReplacementIntent(ActivityInfo aInfo, Intent defIntent) {
        return defIntent;
    }

    protected boolean onTargetSelected(TargetInfo target, boolean alwaysCheck) {
        final ResolveInfo ri = target.getResolveInfo();
        final Intent intent = target != null ? target.getResolvedIntent() : null;

        if (intent != null && (mSupportsAlwaysUseOption || mAdapter.hasFilteredItem())
                && mAdapter.mUnfilteredResolveList != null) {
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
                final int N = mAdapter.mUnfilteredResolveList.size();
                ComponentName[] set;
                // If we don't add back in the component for forwarding the intent to a managed
                // profile, the preferred activity may not be updated correctly (as the set of
                // components we tell it we knew about will have changed).
                final boolean needToAddBackProfileForwardingComponent
                        = mAdapter.mOtherProfile != null;
                if (!needToAddBackProfileForwardingComponent) {
                    set = new ComponentName[N];
                } else {
                    set = new ComponentName[N + 1];
                }

                int bestMatch = 0;
                for (int i=0; i<N; i++) {
                    ResolveInfo r = mAdapter.mUnfilteredResolveList.get(i).getResolveInfoAt(0);
                    set[i] = new ComponentName(r.activityInfo.packageName,
                            r.activityInfo.name);
                    if (r.match > bestMatch) bestMatch = r.match;
                }

                if (needToAddBackProfileForwardingComponent) {
                    set[N] = mAdapter.mOtherProfile.getResolvedComponentName();
                    final int otherProfileMatch = mAdapter.mOtherProfile.getResolveInfo().match;
                    if (otherProfileMatch > bestMatch) bestMatch = otherProfileMatch;
                }

                if (alwaysCheck) {
                    final int userId = getUserId();
                    final PackageManager pm = getPackageManager();

                    // Set the preferred Activity
                    pm.addPreferredActivity(filter, bestMatch, set, intent.getComponent());

                    if (ri.handleAllWebDataURI) {
                        // Set default Browser if needed
                        final String packageName = pm.getDefaultBrowserPackageNameAsUser(userId);
                        if (TextUtils.isEmpty(packageName)) {
                            pm.setDefaultBrowserPackageNameAsUser(ri.activityInfo.packageName, userId);
                        }
                    } else {
                        // Update Domain Verification status
                        ComponentName cn = intent.getComponent();
                        String packageName = cn.getPackageName();
                        String dataScheme = (data != null) ? data.getScheme() : null;

                        boolean isHttpOrHttps = (dataScheme != null) &&
                                (dataScheme.equals(IntentFilter.SCHEME_HTTP) ||
                                        dataScheme.equals(IntentFilter.SCHEME_HTTPS));

                        boolean isViewAction = (action != null) && action.equals(Intent.ACTION_VIEW);
                        boolean hasCategoryBrowsable = (categories != null) &&
                                categories.contains(Intent.CATEGORY_BROWSABLE);

                        if (isHttpOrHttps && isViewAction && hasCategoryBrowsable) {
                            pm.updateIntentVerificationStatusAsUser(packageName,
                                    PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS,
                                    userId);
                        }
                    }
                } else {
                    try {
                        mAdapter.mResolverListController.setLastChosen(intent, filter, bestMatch);
                    } catch (RemoteException re) {
                        Log.d(TAG, "Error calling setLastChosenActivity\n" + re);
                    }
                }
            }
        }

        if (target != null) {
            safelyStartActivity(target);
        }
        return true;
    }

    public void safelyStartActivity(TargetInfo cti) {
        // We're dispatching intents that might be coming from legacy apps, so
        // don't kill ourselves.
        StrictMode.disableDeathOnFileUriExposure();
        try {
            safelyStartActivityInternal(cti);
        } finally {
            StrictMode.enableDeathOnFileUriExposure();
        }
    }

    private void safelyStartActivityInternal(TargetInfo cti) {
        // If needed, show that intent is forwarded
        // from managed profile to owner or other way around.
        if (mProfileSwitchMessageId != -1) {
            Toast.makeText(this, getString(mProfileSwitchMessageId), Toast.LENGTH_LONG).show();
        }
        if (!mSafeForwardingMode) {
            if (cti.start(this, null)) {
                onActivityStarted(cti);
            }
            return;
        }
        try {
            if (cti.startAsCaller(this, null, UserHandle.USER_NULL)) {
                onActivityStarted(cti);
            }
        } catch (RuntimeException e) {
            String launchedFromPackage;
            try {
                launchedFromPackage = ActivityManager.getService().getLaunchedFromPackage(
                        getActivityToken());
            } catch (RemoteException e2) {
                launchedFromPackage = "??";
            }
            Slog.wtf(TAG, "Unable to launch as uid " + mLaunchedFromUid
                    + " package " + launchedFromPackage + ", while running in "
                    + ActivityThread.currentProcessName(), e);
        }
    }

    public void onActivityStarted(TargetInfo cti) {
        // Do nothing
    }

    public boolean shouldGetActivityMetadata() {
        return false;
    }

    public boolean shouldAutoLaunchSingleChoice(TargetInfo target) {
        return true;
    }

    public void showTargetDetails(ResolveInfo ri) {
        Intent in = new Intent().setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", ri.activityInfo.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
        startActivity(in);
    }

    public ResolveListAdapter createAdapter(Context context, List<Intent> payloadIntents,
            Intent[] initialIntents, List<ResolveInfo> rList, int launchedFromUid,
            boolean filterLastUsed) {
        return new ResolveListAdapter(context, payloadIntents, initialIntents, rList,
                launchedFromUid, filterLastUsed, createListController());
    }

    @VisibleForTesting
    protected ResolverListController createListController() {
        return new ResolverListController(
                this,
                mPm,
                getTargetIntent(),
                getReferrerPackageName(),
                mLaunchedFromUid);
    }

    /**
     * Returns true if the activity is finishing and creation should halt
     */
    public boolean configureContentView(List<Intent> payloadIntents, Intent[] initialIntents,
            List<ResolveInfo> rList) {
        // The last argument of createAdapter is whether to do special handling
        // of the last used choice to highlight it in the list.  We need to always
        // turn this off when running under voice interaction, since it results in
        // a more complicated UI that the current voice interaction flow is not able
        // to handle.
        mAdapter = createAdapter(this, payloadIntents, initialIntents, rList,
                mLaunchedFromUid, mSupportsAlwaysUseOption && !isVoiceInteraction());
        boolean rebuildCompleted = mAdapter.rebuildList();

        if (useLayoutWithDefault()) {
            mLayoutId = R.layout.resolver_list_with_default;
        } else {
            mLayoutId = getLayoutResource();
        }
        setContentView(mLayoutId);

        int count = mAdapter.getUnfilteredCount();

        // We only rebuild asynchronously when we have multiple elements to sort. In the case where
        // we're already done, we can check if we should auto-launch immediately.
        if (rebuildCompleted) {
            if (count == 1 && mAdapter.getOtherProfile() == null) {
                // Only one target, so we're a candidate to auto-launch!
                final TargetInfo target = mAdapter.targetInfoForPosition(0, false);
                if (shouldAutoLaunchSingleChoice(target)) {
                    safelyStartActivity(target);
                    mPackageMonitor.unregister();
                    mRegistered = false;
                    finish();
                    return true;
                }
            }
        }


        mAdapterView = findViewById(R.id.resolver_list);

        if (count == 0 && mAdapter.mPlaceholderCount == 0) {
            final TextView emptyView = findViewById(R.id.empty);
            emptyView.setVisibility(View.VISIBLE);
            mAdapterView.setVisibility(View.GONE);
        } else {
            mAdapterView.setVisibility(View.VISIBLE);
            onPrepareAdapterView(mAdapterView, mAdapter);
        }
        return false;
    }

    public void onPrepareAdapterView(AbsListView adapterView, ResolveListAdapter adapter) {
        final boolean useHeader = adapter.hasFilteredItem();
        final ListView listView = adapterView instanceof ListView ? (ListView) adapterView : null;

        adapterView.setAdapter(mAdapter);

        final ItemClickListener listener = new ItemClickListener();
        adapterView.setOnItemClickListener(listener);
        adapterView.setOnItemLongClickListener(listener);

        if (mSupportsAlwaysUseOption) {
            listView.setChoiceMode(AbsListView.CHOICE_MODE_SINGLE);
        }

        // In case this method is called again (due to activity recreation), avoid adding a new
        // header if one is already present.
        if (useHeader && listView != null && listView.getHeaderViewsCount() == 0) {
            listView.addHeaderView(LayoutInflater.from(this).inflate(
                    R.layout.resolver_different_item_header, listView, false));
        }
    }

    public void setTitleAndIcon() {
        if (mAdapter.getCount() == 0 && mAdapter.mPlaceholderCount == 0) {
            final TextView titleView = findViewById(R.id.title);
            if (titleView != null) {
                titleView.setVisibility(View.GONE);
            }
        }

        CharSequence title = mTitle != null
                ? mTitle
                : getTitleForAction(getTargetIntent().getAction(), mDefaultTitleResId);

        if (!TextUtils.isEmpty(title)) {
            final TextView titleView = findViewById(R.id.title);
            if (titleView != null) {
                titleView.setText(title);
            }
            setTitle(title);

            // Try to initialize the title icon if we have a view for it and a title to match
            final ImageView titleIcon = findViewById(R.id.title_icon);
            if (titleIcon != null) {
                ApplicationInfo ai = null;
                try {
                    if (!TextUtils.isEmpty(mReferrerPackage)) {
                        ai = mPm.getApplicationInfo(mReferrerPackage, 0);
                    }
                } catch (NameNotFoundException e) {
                    Log.e(TAG, "Could not find referrer package " + mReferrerPackage);
                }

                if (ai != null) {
                    titleIcon.setImageDrawable(ai.loadIcon(mPm));
                }
            }
        }

        final ImageView iconView = findViewById(R.id.icon);
        final DisplayResolveInfo iconInfo = mAdapter.getFilteredItem();
        if (iconView != null && iconInfo != null) {
            new LoadIconIntoViewTask(iconInfo, iconView).execute();
        }
    }

    public void resetAlwaysOrOnceButtonBar() {
        if (mSupportsAlwaysUseOption) {
            final ViewGroup buttonLayout = findViewById(R.id.button_bar);
            if (buttonLayout != null) {
                buttonLayout.setVisibility(View.VISIBLE);
                mAlwaysButton = (Button) buttonLayout.findViewById(R.id.button_always);
                mOnceButton = (Button) buttonLayout.findViewById(R.id.button_once);
            } else {
                Log.e(TAG, "Layout unexpectedly does not have a button bar");
            }
        }

        if (useLayoutWithDefault()
                && mAdapter.getFilteredPosition() != ListView.INVALID_POSITION) {
            setAlwaysButtonEnabled(true, mAdapter.getFilteredPosition(), false);
            mOnceButton.setEnabled(true);
            return;
        }

        // When the items load in, if an item was already selected, enable the buttons
        if (mAdapterView != null
                && mAdapterView.getCheckedItemPosition() != ListView.INVALID_POSITION) {
            setAlwaysButtonEnabled(true, mAdapterView.getCheckedItemPosition(), true);
            mOnceButton.setEnabled(true);
        }
    }

    private boolean useLayoutWithDefault() {
        return mSupportsAlwaysUseOption && mAdapter.hasFilteredItem();
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
    static boolean resolveInfoMatch(ResolveInfo lhs, ResolveInfo rhs) {
        return lhs == null ? rhs == null
                : lhs.activityInfo == null ? rhs.activityInfo == null
                : Objects.equals(lhs.activityInfo.name, rhs.activityInfo.name)
                && Objects.equals(lhs.activityInfo.packageName, rhs.activityInfo.packageName);
    }

    public final class DisplayResolveInfo implements TargetInfo {
        private final ResolveInfo mResolveInfo;
        private final CharSequence mDisplayLabel;
        private Drawable mDisplayIcon;
        private Drawable mBadge;
        private final CharSequence mExtendedInfo;
        private final Intent mResolvedIntent;
        private final List<Intent> mSourceIntents = new ArrayList<>();
        private boolean mPinned;

        public DisplayResolveInfo(Intent originalIntent, ResolveInfo pri, CharSequence pLabel,
                CharSequence pInfo, Intent pOrigIntent) {
            mSourceIntents.add(originalIntent);
            mResolveInfo = pri;
            mDisplayLabel = pLabel;
            mExtendedInfo = pInfo;

            final Intent intent = new Intent(pOrigIntent != null ? pOrigIntent :
                    getReplacementIntent(pri.activityInfo, getTargetIntent()));
            intent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT
                    | Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP);
            final ActivityInfo ai = mResolveInfo.activityInfo;
            intent.setComponent(new ComponentName(ai.applicationInfo.packageName, ai.name));

            mResolvedIntent = intent;
        }

        private DisplayResolveInfo(DisplayResolveInfo other, Intent fillInIntent, int flags) {
            mSourceIntents.addAll(other.getAllSourceIntents());
            mResolveInfo = other.mResolveInfo;
            mDisplayLabel = other.mDisplayLabel;
            mDisplayIcon = other.mDisplayIcon;
            mExtendedInfo = other.mExtendedInfo;
            mResolvedIntent = new Intent(other.mResolvedIntent);
            mResolvedIntent.fillIn(fillInIntent, flags);
            mPinned = other.mPinned;
        }

        public ResolveInfo getResolveInfo() {
            return mResolveInfo;
        }

        public CharSequence getDisplayLabel() {
            return mDisplayLabel;
        }

        public Drawable getDisplayIcon() {
            return mDisplayIcon;
        }

        public Drawable getBadgeIcon() {
            // We only expose a badge if we have extended info.
            // The badge is a higher-priority disambiguation signal
            // but we don't need one if we wouldn't show extended info at all.
            if (TextUtils.isEmpty(getExtendedInfo())) {
                return null;
            }

            if (mBadge == null && mResolveInfo != null && mResolveInfo.activityInfo != null
                    && mResolveInfo.activityInfo.applicationInfo != null) {
                if (mResolveInfo.activityInfo.icon == 0 || mResolveInfo.activityInfo.icon
                        == mResolveInfo.activityInfo.applicationInfo.icon) {
                    // Badging an icon with exactly the same icon is silly.
                    // If the activityInfo icon resid is 0 it will fall back
                    // to the application's icon, making it a match.
                    return null;
                }
                mBadge = mResolveInfo.activityInfo.applicationInfo.loadIcon(mPm);
            }
            return mBadge;
        }

        @Override
        public CharSequence getBadgeContentDescription() {
            return null;
        }

        @Override
        public TargetInfo cloneFilledIn(Intent fillInIntent, int flags) {
            return new DisplayResolveInfo(this, fillInIntent, flags);
        }

        @Override
        public List<Intent> getAllSourceIntents() {
            return mSourceIntents;
        }

        public void addAlternateSourceIntent(Intent alt) {
            mSourceIntents.add(alt);
        }

        public void setDisplayIcon(Drawable icon) {
            mDisplayIcon = icon;
        }

        public boolean hasDisplayIcon() {
            return mDisplayIcon != null;
        }

        public CharSequence getExtendedInfo() {
            return mExtendedInfo;
        }

        public Intent getResolvedIntent() {
            return mResolvedIntent;
        }

        @Override
        public ComponentName getResolvedComponentName() {
            return new ComponentName(mResolveInfo.activityInfo.packageName,
                    mResolveInfo.activityInfo.name);
        }

        @Override
        public boolean start(Activity activity, Bundle options) {
            activity.startActivity(mResolvedIntent, options);
            return true;
        }

        @Override
        public boolean startAsCaller(Activity activity, Bundle options, int userId) {
            activity.startActivityAsCaller(mResolvedIntent, options, false, userId);
            return true;
        }

        @Override
        public boolean startAsUser(Activity activity, Bundle options, UserHandle user) {
            activity.startActivityAsUser(mResolvedIntent, options, user);
            return false;
        }

        @Override
        public boolean isPinned() {
            return mPinned;
        }

        public void setPinned(boolean pinned) {
            mPinned = pinned;
        }
    }

    /**
     * A single target as represented in the chooser.
     */
    public interface TargetInfo {
        /**
         * Get the resolved intent that represents this target. Note that this may not be the
         * intent that will be launched by calling one of the <code>start</code> methods provided;
         * this is the intent that will be credited with the launch.
         *
         * @return the resolved intent for this target
         */
        Intent getResolvedIntent();

        /**
         * Get the resolved component name that represents this target. Note that this may not
         * be the component that will be directly launched by calling one of the <code>start</code>
         * methods provided; this is the component that will be credited with the launch.
         *
         * @return the resolved ComponentName for this target
         */
        ComponentName getResolvedComponentName();

        /**
         * Start the activity referenced by this target.
         *
         * @param activity calling Activity performing the launch
         * @param options ActivityOptions bundle
         * @return true if the start completed successfully
         */
        boolean start(Activity activity, Bundle options);

        /**
         * Start the activity referenced by this target as if the ResolverActivity's caller
         * was performing the start operation.
         *
         * @param activity calling Activity (actually) performing the launch
         * @param options ActivityOptions bundle
         * @param userId userId to start as or {@link UserHandle#USER_NULL} for activity's caller
         * @return true if the start completed successfully
         */
        boolean startAsCaller(Activity activity, Bundle options, int userId);

        /**
         * Start the activity referenced by this target as a given user.
         *
         * @param activity calling activity performing the launch
         * @param options ActivityOptions bundle
         * @param user handle for the user to start the activity as
         * @return true if the start completed successfully
         */
        boolean startAsUser(Activity activity, Bundle options, UserHandle user);

        /**
         * Return the ResolveInfo about how and why this target matched the original query
         * for available targets.
         *
         * @return ResolveInfo representing this target's match
         */
        ResolveInfo getResolveInfo();

        /**
         * Return the human-readable text label for this target.
         *
         * @return user-visible target label
         */
        CharSequence getDisplayLabel();

        /**
         * Return any extended info for this target. This may be used to disambiguate
         * otherwise identical targets.
         *
         * @return human-readable disambig string or null if none present
         */
        CharSequence getExtendedInfo();

        /**
         * @return The drawable that should be used to represent this target
         */
        Drawable getDisplayIcon();

        /**
         * @return The (small) icon to badge the target with
         */
        Drawable getBadgeIcon();

        /**
         * @return The content description for the badge icon
         */
        CharSequence getBadgeContentDescription();

        /**
         * Clone this target with the given fill-in information.
         */
        TargetInfo cloneFilledIn(Intent fillInIntent, int flags);

        /**
         * @return the list of supported source intents deduped against this single target
         */
        List<Intent> getAllSourceIntents();

        /**
         * @return true if this target should be pinned to the front by the request of the user
         */
        boolean isPinned();
    }

    public class ResolveListAdapter extends BaseAdapter {
        private final List<Intent> mIntents;
        private final Intent[] mInitialIntents;
        private final List<ResolveInfo> mBaseResolveList;
        protected ResolveInfo mLastChosen;
        private DisplayResolveInfo mOtherProfile;
        private boolean mHasExtendedInfo;
        private ResolverListController mResolverListController;
        private int mPlaceholderCount;

        protected final LayoutInflater mInflater;

        List<DisplayResolveInfo> mDisplayList;
        List<ResolvedComponentInfo> mUnfilteredResolveList;

        private int mLastChosenPosition = -1;
        private boolean mFilterLastUsed;

        public ResolveListAdapter(Context context, List<Intent> payloadIntents,
                Intent[] initialIntents, List<ResolveInfo> rList, int launchedFromUid,
                boolean filterLastUsed,
                ResolverListController resolverListController) {
            mIntents = payloadIntents;
            mInitialIntents = initialIntents;
            mBaseResolveList = rList;
            mLaunchedFromUid = launchedFromUid;
            mInflater = LayoutInflater.from(context);
            mDisplayList = new ArrayList<>();
            mFilterLastUsed = filterLastUsed;
            mResolverListController = resolverListController;
        }

        public void handlePackagesChanged() {
            rebuildList();
            if (getCount() == 0) {
                // We no longer have any items...  just finish the activity.
                finish();
            }
        }

        public void setPlaceholderCount(int count) {
            mPlaceholderCount = count;
        }

        public int getPlaceholderCount() { return mPlaceholderCount; }

        @Nullable
        public DisplayResolveInfo getFilteredItem() {
            if (mFilterLastUsed && mLastChosenPosition >= 0) {
                // Not using getItem since it offsets to dodge this position for the list
                return mDisplayList.get(mLastChosenPosition);
            }
            return null;
        }

        public DisplayResolveInfo getOtherProfile() {
            return mOtherProfile;
        }

        public int getFilteredPosition() {
            if (mFilterLastUsed && mLastChosenPosition >= 0) {
                return mLastChosenPosition;
            }
            return AbsListView.INVALID_POSITION;
        }

        public boolean hasFilteredItem() {
            return mFilterLastUsed && mLastChosen != null;
        }

        public float getScore(DisplayResolveInfo target) {
            return mResolverListController.getScore(target);
        }

        public void updateModel(ComponentName componentName) {
            mResolverListController.updateModel(componentName);
        }

        public void updateChooserCounts(String packageName, int userId, String action) {
            mResolverListController.updateChooserCounts(packageName, userId, action);
        }

        /**
         * Rebuild the list of resolvers. In some cases some parts will need some asynchronous work
         * to complete.
         *
         * @return Whether or not the list building is completed.
         */
        protected boolean rebuildList() {
            List<ResolvedComponentInfo> currentResolveList = null;
            // Clear the value of mOtherProfile from previous call.
            mOtherProfile = null;
            mLastChosen = null;
            mLastChosenPosition = -1;
            mDisplayList.clear();
            if (mBaseResolveList != null) {
                currentResolveList = mUnfilteredResolveList = new ArrayList<>();
                mResolverListController.addResolveListDedupe(currentResolveList,
                        getTargetIntent(),
                        mBaseResolveList);
            } else {
                currentResolveList = mUnfilteredResolveList =
                        mResolverListController.getResolversForIntent(shouldGetResolvedFilter(),
                                shouldGetActivityMetadata(),
                                mIntents);
                if (currentResolveList == null) {
                    processSortedList(currentResolveList);
                    return true;
                }
                List<ResolvedComponentInfo> originalList =
                        mResolverListController.filterIneligibleActivities(currentResolveList,
                                true);
                if (originalList != null) {
                    mUnfilteredResolveList = originalList;
                }
            }

            // So far we only support a single other profile at a time.
            // The first one we see gets special treatment.
            for (ResolvedComponentInfo info : currentResolveList) {
                if (info.getResolveInfoAt(0).targetUserId != UserHandle.USER_CURRENT) {
                    mOtherProfile = new DisplayResolveInfo(info.getIntentAt(0),
                            info.getResolveInfoAt(0),
                            info.getResolveInfoAt(0).loadLabel(mPm),
                            info.getResolveInfoAt(0).loadLabel(mPm),
                            getReplacementIntent(info.getResolveInfoAt(0).activityInfo,
                                    info.getIntentAt(0)));
                    currentResolveList.remove(info);
                    break;
                }
            }

            if (mOtherProfile == null) {
                try {
                    mLastChosen = mResolverListController.getLastChosen();
                } catch (RemoteException re) {
                    Log.d(TAG, "Error calling getLastChosenActivity\n" + re);
                }
            }

            int N;
            if ((currentResolveList != null) && ((N = currentResolveList.size()) > 0)) {
                // We only care about fixing the unfilteredList if the current resolve list and
                // current resolve list are currently the same.
                List<ResolvedComponentInfo> originalList =
                        mResolverListController.filterLowPriority(currentResolveList,
                                mUnfilteredResolveList == currentResolveList);
                if (originalList != null) {
                    mUnfilteredResolveList = originalList;
                }

                if (currentResolveList.size() > 1) {
                    int placeholderCount = currentResolveList.size();
                    if (useLayoutWithDefault()) {
                        --placeholderCount;
                    }
                    setPlaceholderCount(placeholderCount);
                    AsyncTask<List<ResolvedComponentInfo>,
                            Void,
                            List<ResolvedComponentInfo>> sortingTask =
                            new AsyncTask<List<ResolvedComponentInfo>,
                                    Void,
                                    List<ResolvedComponentInfo>>() {
                        @Override
                        protected List<ResolvedComponentInfo> doInBackground(
                                List<ResolvedComponentInfo>... params) {
                            mResolverListController.sort(params[0]);
                            return params[0];
                        }

                        @Override
                        protected void onPostExecute(List<ResolvedComponentInfo> sortedComponents) {
                            processSortedList(sortedComponents);
                            if (mProfileView != null) {
                                bindProfileView();
                            }
                            notifyDataSetChanged();
                        }
                    };
                    sortingTask.execute(currentResolveList);
                    postListReadyRunnable();
                    return false;
                } else {
                    processSortedList(currentResolveList);
                    return true;
                }
            } else {
                processSortedList(currentResolveList);
                return true;
            }
        }

        private void processSortedList(List<ResolvedComponentInfo> sortedComponents) {
            int N;
            if (sortedComponents != null && (N = sortedComponents.size()) != 0) {
                // First put the initial items at the top.
                if (mInitialIntents != null) {
                    for (int i = 0; i < mInitialIntents.length; i++) {
                        Intent ii = mInitialIntents[i];
                        if (ii == null) {
                            continue;
                        }
                        ActivityInfo ai = ii.resolveActivityInfo(
                                getPackageManager(), 0);
                        if (ai == null) {
                            Log.w(TAG, "No activity found for " + ii);
                            continue;
                        }
                        ResolveInfo ri = new ResolveInfo();
                        ri.activityInfo = ai;
                        UserManager userManager =
                                (UserManager) getSystemService(Context.USER_SERVICE);
                        if (ii instanceof LabeledIntent) {
                            LabeledIntent li = (LabeledIntent) ii;
                            ri.resolvePackageName = li.getSourcePackage();
                            ri.labelRes = li.getLabelResource();
                            ri.nonLocalizedLabel = li.getNonLocalizedLabel();
                            ri.icon = li.getIconResource();
                            ri.iconResourceId = ri.icon;
                        }
                        if (userManager.isManagedProfile()) {
                            ri.noResourceId = true;
                            ri.icon = 0;
                        }
                        addResolveInfo(new DisplayResolveInfo(ii, ri,
                                ri.loadLabel(getPackageManager()), null, ii));
                    }
                }

                // Check for applications with same name and use application name or
                // package name if necessary
                ResolvedComponentInfo rci0 = sortedComponents.get(0);
                ResolveInfo r0 = rci0.getResolveInfoAt(0);
                int start = 0;
                CharSequence r0Label = r0.loadLabel(mPm);
                mHasExtendedInfo = false;
                for (int i = 1; i < N; i++) {
                    if (r0Label == null) {
                        r0Label = r0.activityInfo.packageName;
                    }
                    ResolvedComponentInfo rci = sortedComponents.get(i);
                    ResolveInfo ri = rci.getResolveInfoAt(0);
                    CharSequence riLabel = ri.loadLabel(mPm);
                    if (riLabel == null) {
                        riLabel = ri.activityInfo.packageName;
                    }
                    if (riLabel.equals(r0Label)) {
                        continue;
                    }
                    processGroup(sortedComponents, start, (i - 1), rci0, r0Label);
                    rci0 = rci;
                    r0 = ri;
                    r0Label = riLabel;
                    start = i;
                }
                // Process last group
                processGroup(sortedComponents, start, (N - 1), rci0, r0Label);
            }

            postListReadyRunnable();
        }

        /**
         * Some necessary methods for creating the list are initiated in onCreate and will also
         * determine the layout known. We therefore can't update the UI inline and post to the
         * handler thread to update after the current task is finished.
         */
        private void postListReadyRunnable() {
            if (mPostListReadyRunnable == null) {
                mPostListReadyRunnable = new Runnable() {
                    @Override
                    public void run() {
                        setTitleAndIcon();
                        resetAlwaysOrOnceButtonBar();
                        onListRebuilt();
                        mPostListReadyRunnable = null;
                    }
                };
                getMainThreadHandler().post(mPostListReadyRunnable);
            }
        }

        public void onListRebuilt() {
            int count = getUnfilteredCount();
            if (count == 1 && getOtherProfile() == null) {
                // Only one target, so we're a candidate to auto-launch!
                final TargetInfo target = targetInfoForPosition(0, false);
                if (shouldAutoLaunchSingleChoice(target)) {
                    safelyStartActivity(target);
                    finish();
                }
            }
        }

        public boolean shouldGetResolvedFilter() {
            return mFilterLastUsed;
        }

        private void processGroup(List<ResolvedComponentInfo> rList, int start, int end,
                ResolvedComponentInfo ro, CharSequence roLabel) {
            // Process labels from start to i
            int num = end - start+1;
            if (num == 1) {
                // No duplicate labels. Use label for entry at start
                addResolveInfoWithAlternates(ro, null, roLabel);
            } else {
                mHasExtendedInfo = true;
                boolean usePkg = false;
                final ApplicationInfo ai = ro.getResolveInfoAt(0).activityInfo.applicationInfo;
                final CharSequence startApp = ai.loadLabel(mPm);
                if (startApp == null) {
                    usePkg = true;
                }
                if (!usePkg) {
                    // Use HashSet to track duplicates
                    HashSet<CharSequence> duplicates =
                        new HashSet<CharSequence>();
                    duplicates.add(startApp);
                    for (int j = start+1; j <= end ; j++) {
                        ResolveInfo jRi = rList.get(j).getResolveInfoAt(0);
                        CharSequence jApp = jRi.activityInfo.applicationInfo.loadLabel(mPm);
                        if ( (jApp == null) || (duplicates.contains(jApp))) {
                            usePkg = true;
                            break;
                        } else {
                            duplicates.add(jApp);
                        }
                    }
                    // Clear HashSet for later use
                    duplicates.clear();
                }
                for (int k = start; k <= end; k++) {
                    final ResolvedComponentInfo rci = rList.get(k);
                    final ResolveInfo add = rci.getResolveInfoAt(0);
                    final CharSequence extraInfo;
                    if (usePkg) {
                        // Use package name for all entries from start to end-1
                        extraInfo = add.activityInfo.packageName;
                    } else {
                        // Use application name for all entries from start to end-1
                        extraInfo = add.activityInfo.applicationInfo.loadLabel(mPm);
                    }
                    addResolveInfoWithAlternates(rci, extraInfo, roLabel);
                }
            }
        }

        private void addResolveInfoWithAlternates(ResolvedComponentInfo rci,
                CharSequence extraInfo, CharSequence roLabel) {
            final int count = rci.getCount();
            final Intent intent = rci.getIntentAt(0);
            final ResolveInfo add = rci.getResolveInfoAt(0);
            final Intent replaceIntent = getReplacementIntent(add.activityInfo, intent);
            final DisplayResolveInfo dri = new DisplayResolveInfo(intent, add, roLabel,
                    extraInfo, replaceIntent);
            dri.setPinned(rci.isPinned());
            addResolveInfo(dri);
            if (replaceIntent == intent) {
                // Only add alternates if we didn't get a specific replacement from
                // the caller. If we have one it trumps potential alternates.
                for (int i = 1, N = count; i < N; i++) {
                    final Intent altIntent = rci.getIntentAt(i);
                    dri.addAlternateSourceIntent(altIntent);
                }
            }
            updateLastChosenPosition(add);
        }

        private void updateLastChosenPosition(ResolveInfo info) {
            // If another profile is present, ignore the last chosen entry.
            if (mOtherProfile != null) {
                mLastChosenPosition = -1;
                return;
            }
            if (mLastChosen != null
                    && mLastChosen.activityInfo.packageName.equals(info.activityInfo.packageName)
                    && mLastChosen.activityInfo.name.equals(info.activityInfo.name)) {
                mLastChosenPosition = mDisplayList.size() - 1;
            }
        }

        // We assume that at this point we've already filtered out the only intent for a different
        // targetUserId which we're going to use.
        private void addResolveInfo(DisplayResolveInfo dri) {
            if (dri != null && dri.mResolveInfo != null
                    && dri.mResolveInfo.targetUserId == UserHandle.USER_CURRENT) {
                // Checks if this info is already listed in display.
                for (DisplayResolveInfo existingInfo : mDisplayList) {
                    if (resolveInfoMatch(dri.mResolveInfo, existingInfo.mResolveInfo)) {
                        return;
                    }
                }
                mDisplayList.add(dri);
            }
        }

        @Nullable
        public ResolveInfo resolveInfoForPosition(int position, boolean filtered) {
            TargetInfo target = targetInfoForPosition(position, filtered);
            if (target != null) {
                return target.getResolveInfo();
             }
             return null;
        }

        @Nullable
        public TargetInfo targetInfoForPosition(int position, boolean filtered) {
            if (filtered) {
                return getItem(position);
            }
            if (mDisplayList.size() > position) {
                return mDisplayList.get(position);
            }
            return null;
        }

        public int getCount() {
            int totalSize = mDisplayList == null || mDisplayList.isEmpty() ? mPlaceholderCount :
                    mDisplayList.size();
            if (mFilterLastUsed && mLastChosenPosition >= 0) {
                totalSize--;
            }
            return totalSize;
        }

        public int getUnfilteredCount() {
            return mDisplayList.size();
        }

        public int getDisplayInfoCount() {
            return mDisplayList.size();
        }

        public DisplayResolveInfo getDisplayInfoAt(int index) {
            return mDisplayList.get(index);
        }

        @Nullable
        public TargetInfo getItem(int position) {
            if (mFilterLastUsed && mLastChosenPosition >= 0 && position >= mLastChosenPosition) {
                position++;
            }
            if (mDisplayList.size() > position) {
                return mDisplayList.get(position);
            } else {
                return null;
            }
        }

        public long getItemId(int position) {
            return position;
        }

        public boolean hasExtendedInfo() {
            return mHasExtendedInfo;
        }

        public boolean hasResolvedTarget(ResolveInfo info) {
            for (int i = 0, N = mDisplayList.size(); i < N; i++) {
                if (resolveInfoMatch(info, mDisplayList.get(i).getResolveInfo())) {
                    return true;
                }
            }
            return false;
        }

        public int getDisplayResolveInfoCount() {
            return mDisplayList.size();
        }

        public DisplayResolveInfo getDisplayResolveInfo(int index) {
            // Used to query services. We only query services for primary targets, not alternates.
            return mDisplayList.get(index);
        }

        public final View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = createView(parent);
            }
            onBindView(view, getItem(position));
            return view;
        }

        public final View createView(ViewGroup parent) {
            final View view = onCreateView(parent);
            final ViewHolder holder = new ViewHolder(view);
            view.setTag(holder);
            return view;
        }

        public View onCreateView(ViewGroup parent) {
            return mInflater.inflate(
                    com.android.internal.R.layout.resolve_list_item, parent, false);
        }

        public boolean showsExtendedInfo(TargetInfo info) {
            return !TextUtils.isEmpty(info.getExtendedInfo());
        }

        public boolean isComponentPinned(ComponentName name) {
            return false;
        }

        public final void bindView(int position, View view) {
            onBindView(view, getItem(position));
        }

        private void onBindView(View view, TargetInfo info) {
            final ViewHolder holder = (ViewHolder) view.getTag();
            if (info == null) {
                holder.icon.setImageDrawable(
                        getDrawable(R.drawable.resolver_icon_placeholder));
                return;
            }
            final CharSequence label = info.getDisplayLabel();
            if (!TextUtils.equals(holder.text.getText(), label)) {
                holder.text.setText(info.getDisplayLabel());
            }
            if (showsExtendedInfo(info)) {
                holder.text2.setVisibility(View.VISIBLE);
                holder.text2.setText(info.getExtendedInfo());
            } else {
                holder.text2.setVisibility(View.GONE);
            }
            if (info instanceof DisplayResolveInfo
                    && !((DisplayResolveInfo) info).hasDisplayIcon()) {
                new LoadAdapterIconTask((DisplayResolveInfo) info).execute();
            }
            holder.icon.setImageDrawable(info.getDisplayIcon());
            if (holder.badge != null) {
                final Drawable badge = info.getBadgeIcon();
                if (badge != null) {
                    holder.badge.setImageDrawable(badge);
                    holder.badge.setContentDescription(info.getBadgeContentDescription());
                    holder.badge.setVisibility(View.VISIBLE);
                } else {
                    holder.badge.setVisibility(View.GONE);
                }
            }
        }
    }

    @VisibleForTesting
    public static final class ResolvedComponentInfo {
        public final ComponentName name;
        private boolean mPinned;
        private final List<Intent> mIntents = new ArrayList<>();
        private final List<ResolveInfo> mResolveInfos = new ArrayList<>();

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
    }

    static class ViewHolder {
        public TextView text;
        public TextView text2;
        public ImageView icon;
        public ImageView badge;

        public ViewHolder(View view) {
            text = (TextView) view.findViewById(com.android.internal.R.id.text1);
            text2 = (TextView) view.findViewById(com.android.internal.R.id.text2);
            icon = (ImageView) view.findViewById(R.id.icon);
            badge = (ImageView) view.findViewById(R.id.target_badge);
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
            if (mAdapter.resolveInfoForPosition(position, true) == null) {
                return;
            }

            final int checkedPos = mAdapterView.getCheckedItemPosition();
            final boolean hasValidSelection = checkedPos != ListView.INVALID_POSITION;
            if (!useLayoutWithDefault()
                    && (!hasValidSelection || mLastSelected != checkedPos)
                    && mAlwaysButton != null) {
                setAlwaysButtonEnabled(hasValidSelection, checkedPos, true);
                mOnceButton.setEnabled(hasValidSelection);
                if (hasValidSelection) {
                    mAdapterView.smoothScrollToPosition(checkedPos);
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
            ResolveInfo ri = mAdapter.resolveInfoForPosition(position, true);
            showTargetDetails(ri);
            return true;
        }

    }

    abstract class LoadIconTask extends AsyncTask<Void, Void, Drawable> {
        protected final DisplayResolveInfo mDisplayResolveInfo;
        private final ResolveInfo mResolveInfo;

        public LoadIconTask(DisplayResolveInfo dri) {
            mDisplayResolveInfo = dri;
            mResolveInfo = dri.getResolveInfo();
        }

        @Override
        protected Drawable doInBackground(Void... params) {
            return loadIconForResolveInfo(mResolveInfo);
        }

        @Override
        protected void onPostExecute(Drawable d) {
            mDisplayResolveInfo.setDisplayIcon(d);
        }
    }

    class LoadAdapterIconTask extends LoadIconTask {
        public LoadAdapterIconTask(DisplayResolveInfo dri) {
            super(dri);
        }

        @Override
        protected void onPostExecute(Drawable d) {
            super.onPostExecute(d);
            if (mProfileView != null && mAdapter.getOtherProfile() == mDisplayResolveInfo) {
                bindProfileView();
            }
            mAdapter.notifyDataSetChanged();
        }
    }

    class LoadIconIntoViewTask extends LoadIconTask {
        private final ImageView mTargetView;

        public LoadIconIntoViewTask(DisplayResolveInfo dri, ImageView target) {
            super(dri);
            mTargetView = target;
        }

        @Override
        protected void onPostExecute(Drawable d) {
            super.onPostExecute(d);
            mTargetView.setImageDrawable(d);
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
                final TargetInfo ti = ra.mAdapter.getItem(selections[0].getIndex());
                if (ra.onTargetSelected(ti, false)) {
                    ra.mPickOptionRequest = null;
                    ra.finish();
                }
            }
        }
    }
}
