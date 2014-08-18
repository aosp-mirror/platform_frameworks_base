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

import android.app.Activity;
import android.app.ActivityThread;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.os.AsyncTask;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Slog;
import android.widget.AbsListView;
import android.widget.GridView;
import com.android.internal.R;
import com.android.internal.content.PackageMonitor;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.AppGlobals;
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
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PatternMatcher;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import com.android.internal.widget.ResolverDrawerLayout;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * This activity is displayed when the system attempts to start an Intent for
 * which there is more than one matching activity, allowing the user to decide
 * which to go to.  It is not normally used directly by application developers.
 */
public class ResolverActivity extends Activity implements AdapterView.OnItemClickListener {
    private static final String TAG = "ResolverActivity";
    private static final boolean DEBUG = false;

    private int mLaunchedFromUid;
    private ResolveListAdapter mAdapter;
    private PackageManager mPm;
    private boolean mSafeForwardingMode;
    private boolean mAlwaysUseOption;
    private boolean mShowExtended;
    private GridView mGridView;
    private Button mAlwaysButton;
    private Button mOnceButton;
    private int mIconDpi;
    private int mIconSize;
    private int mMaxColumns;
    private int mLastSelected = ListView.INVALID_POSITION;
    private boolean mResolvingHome = false;

    private UsageStatsManager mUsm;
    private ArrayMap<String, UsageStats> mStats;
    private static final long USAGE_STATS_PERIOD = 1000 * 60 * 60 * 24 * 14;

    private boolean mRegistered;
    private final PackageMonitor mPackageMonitor = new PackageMonitor() {
        @Override public void onSomePackagesChanged() {
            mAdapter.handlePackagesChanged();
        }
    };

    private enum ActionTitle {
        VIEW(Intent.ACTION_VIEW,
                com.android.internal.R.string.whichViewApplication,
                com.android.internal.R.string.whichViewApplicationNamed),
        EDIT(Intent.ACTION_EDIT,
                com.android.internal.R.string.whichEditApplication,
                com.android.internal.R.string.whichEditApplicationNamed),
        SEND(Intent.ACTION_SEND,
                com.android.internal.R.string.whichSendApplication,
                com.android.internal.R.string.whichSendApplicationNamed),
        SENDTO(Intent.ACTION_SENDTO,
                com.android.internal.R.string.whichSendApplication,
                com.android.internal.R.string.whichSendApplicationNamed),
        SEND_MULTIPLE(Intent.ACTION_SEND_MULTIPLE,
                com.android.internal.R.string.whichSendApplication,
                com.android.internal.R.string.whichSendApplicationNamed),
        DEFAULT(null,
                com.android.internal.R.string.whichApplication,
                com.android.internal.R.string.whichApplicationNamed);

        public final String action;
        public final int titleRes;
        public final int namedTitleRes;

        ActionTitle(String action, int titleRes, int namedTitleRes) {
            this.action = action;
            this.titleRes = titleRes;
            this.namedTitleRes = namedTitleRes;
        }

        public static ActionTitle forAction(String action) {
            for (ActionTitle title : values()) {
                if (action != null && action.equals(title.action)) {
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
        final int titleResource;
        final Intent intent = makeMyIntent();
        final Set<String> categories = intent.getCategories();
        if (Intent.ACTION_MAIN.equals(intent.getAction())
                && categories != null
                && categories.size() == 1
                && categories.contains(Intent.CATEGORY_HOME)) {
            titleResource = com.android.internal.R.string.whichHomeApplication;

            // Note: this field is not set to true in the compatibility version.
            mResolvingHome = true;
        } else {
            titleResource = 0;
        }

        setSafeForwardingMode(true);

        onCreate(savedInstanceState, intent,
                titleResource != 0 ? getResources().getText(titleResource) : null, titleResource,
                null, null, true);
    }

    /**
     * Compatibility version for other bundled services that use this ocerload without
     * a default title resource
     */
    protected void onCreate(Bundle savedInstanceState, Intent intent,
            CharSequence title, Intent[] initialIntents,
            List<ResolveInfo> rList, boolean alwaysUseOption) {
        onCreate(savedInstanceState, intent, title, 0, initialIntents, rList, alwaysUseOption);
    }

    protected void onCreate(Bundle savedInstanceState, Intent intent,
            CharSequence title, int defaultTitleRes, Intent[] initialIntents,
            List<ResolveInfo> rList, boolean alwaysUseOption) {
        setTheme(R.style.Theme_DeviceDefault_Resolver);
        super.onCreate(savedInstanceState);
        try {
            mLaunchedFromUid = ActivityManagerNative.getDefault().getLaunchedFromUid(
                    getActivityToken());
        } catch (RemoteException e) {
            mLaunchedFromUid = -1;
        }
        mPm = getPackageManager();
        mUsm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);

        final long sinceTime = System.currentTimeMillis() - USAGE_STATS_PERIOD;
        mStats = mUsm.queryAndAggregateUsageStats(sinceTime, System.currentTimeMillis());
        Log.d(TAG, "sinceTime=" + sinceTime);

        mMaxColumns = getResources().getInteger(R.integer.config_maxResolverActivityColumns);

        mPackageMonitor.register(this, getMainLooper(), false);
        mRegistered = true;

        final ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        mIconDpi = am.getLauncherLargeIconDensity();
        mIconSize = am.getLauncherLargeIconSize();

        mAdapter = new ResolveListAdapter(this, intent, initialIntents, rList,
                mLaunchedFromUid, alwaysUseOption);

        final int layoutId;
        if (mAdapter.hasFilteredItem()) {
            layoutId = R.layout.resolver_list_with_default;
            alwaysUseOption = false;
        } else {
            layoutId = R.layout.resolver_list;
        }
        mAlwaysUseOption = alwaysUseOption;

        int count = mAdapter.mList.size();
        if (mLaunchedFromUid < 0 || UserHandle.isIsolated(mLaunchedFromUid)) {
            // Gulp!
            finish();
            return;
        } else if (count > 1) {
            setContentView(layoutId);
            mGridView = (GridView) findViewById(R.id.resolver_list);
            mGridView.setAdapter(mAdapter);
            mGridView.setOnItemClickListener(this);
            mGridView.setOnItemLongClickListener(new ItemLongClickListener());

            if (alwaysUseOption) {
                mGridView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
            }

            resizeGrid();
        } else if (count == 1) {
            safelyStartActivity(mAdapter.intentForPosition(0, false));
            mPackageMonitor.unregister();
            mRegistered = false;
            finish();
            return;
        } else {
            setContentView(R.layout.resolver_list);

            final TextView empty = (TextView) findViewById(R.id.empty);
            empty.setVisibility(View.VISIBLE);

            mGridView = (GridView) findViewById(R.id.resolver_list);
            mGridView.setVisibility(View.GONE);
        }

        final ResolverDrawerLayout rdl = (ResolverDrawerLayout) findViewById(R.id.contentPanel);
        if (rdl != null) {
            rdl.setOnClickOutsideListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        final TextView titleView = (TextView) findViewById(R.id.title);
        if (titleView != null) {
            if (title == null) {
                title = getTitleForAction(intent.getAction(), defaultTitleRes);
            }
            titleView.setText(title);
        }

        final ImageView iconView = (ImageView) findViewById(R.id.icon);
        final DisplayResolveInfo iconInfo = mAdapter.getFilteredItem();
        if (iconView != null && iconInfo != null) {
            new LoadIconIntoViewTask(iconView).execute(iconInfo);
        }

        if (alwaysUseOption || mAdapter.hasFilteredItem()) {
            final ViewGroup buttonLayout = (ViewGroup) findViewById(R.id.button_bar);
            if (buttonLayout != null) {
                buttonLayout.setVisibility(View.VISIBLE);
                mAlwaysButton = (Button) buttonLayout.findViewById(R.id.button_always);
                mOnceButton = (Button) buttonLayout.findViewById(R.id.button_once);
            } else {
                mAlwaysUseOption = false;
            }
        }

        if (mAdapter.hasFilteredItem()) {
            setAlwaysButtonEnabled(true, mAdapter.getFilteredPosition(), false);
            mOnceButton.setEnabled(true);
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
        final ActionTitle title = ActionTitle.forAction(action);
        final boolean named = mAdapter.hasFilteredItem();
        if (title == ActionTitle.DEFAULT && defaultTitleRes != 0) {
            return getString(defaultTitleRes);
        } else {
            return named ? getString(title.namedTitleRes, mAdapter.getFilteredItem().displayLabel) :
                    getString(title.titleRes);
        }
    }

    void resizeGrid() {
        final int itemCount = mAdapter.getCount();
        mGridView.setNumColumns(Math.min(itemCount, mMaxColumns));
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
                    return dr;
                }
            }
            final int iconRes = ri.getIconResource();
            if (iconRes != 0) {
                dr = getIcon(mPm.getResourcesForApplication(ri.activityInfo.packageName), iconRes);
                if (dr != null) {
                    return dr;
                }
            }
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Couldn't find resources for package", e);
        }
        return ri.loadIcon(mPm);
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        if (!mRegistered) {
            mPackageMonitor.register(this, getMainLooper(), false);
            mRegistered = true;
        }
        mAdapter.handlePackagesChanged();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mRegistered) {
            mPackageMonitor.unregister();
            mRegistered = false;
        }
        if ((getIntent().getFlags()&Intent.FLAG_ACTIVITY_NEW_TASK) != 0) {
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
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (mAlwaysUseOption) {
            final int checkedPos = mGridView.getCheckedItemPosition();
            final boolean hasValidSelection = checkedPos != ListView.INVALID_POSITION;
            mLastSelected = checkedPos;
            setAlwaysButtonEnabled(hasValidSelection, checkedPos, true);
            mOnceButton.setEnabled(hasValidSelection);
            if (hasValidSelection) {
                mGridView.setSelection(checkedPos);
            }
        }
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        ResolveInfo resolveInfo = mAdapter.resolveInfoForPosition(position, true);
        if (mResolvingHome && hasManagedProfile()
                && !supportsManagedProfiles(resolveInfo)) {
            Toast.makeText(this, String.format(getResources().getString(
                    com.android.internal.R.string.activity_resolver_work_profiles_support),
                    resolveInfo.activityInfo.loadLabel(getPackageManager()).toString()),
                    Toast.LENGTH_LONG).show();
            return;
        }
        final int checkedPos = mGridView.getCheckedItemPosition();
        final boolean hasValidSelection = checkedPos != ListView.INVALID_POSITION;
        if (mAlwaysUseOption && (!hasValidSelection || mLastSelected != checkedPos)) {
            setAlwaysButtonEnabled(hasValidSelection, checkedPos, true);
            mOnceButton.setEnabled(hasValidSelection);
            if (hasValidSelection) {
                mGridView.smoothScrollToPosition(checkedPos);
            }
            mLastSelected = checkedPos;
        } else {
            startSelected(position, false, true);
        }
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
            return versionNumberAtLeastL(appInfo.targetSdkVersion);
        } catch (NameNotFoundException e) {
            return false;
        }
    }

    private boolean versionNumberAtLeastL(int versionNumber) {
        // TODO: remove "|| true" once the build code for L is fixed.
        return versionNumber >= Build.VERSION_CODES.L || true;
    }

    private void setAlwaysButtonEnabled(boolean hasValidSelection, int checkedPos,
            boolean filtered) {
        boolean enabled = false;
        if (hasValidSelection) {
            ResolveInfo ri = mAdapter.resolveInfoForPosition(checkedPos, filtered);
            if (ri.targetUserId == UserHandle.USER_CURRENT) {
                enabled = true;
            }
        }
        mAlwaysButton.setEnabled(enabled);
    }

    public void onButtonClick(View v) {
        final int id = v.getId();
        startSelected(mAlwaysUseOption ?
                mGridView.getCheckedItemPosition() : mAdapter.getFilteredPosition(),
                id == R.id.button_always,
                mAlwaysUseOption);
        dismiss();
    }

    void startSelected(int which, boolean always, boolean filtered) {
        if (isFinishing()) {
            return;
        }
        ResolveInfo ri = mAdapter.resolveInfoForPosition(which, filtered);
        Intent intent = mAdapter.intentForPosition(which, filtered);
        onIntentSelected(ri, intent, always);
        finish();
    }

    /**
     * Replace me in subclasses!
     */
    public Intent getReplacementIntent(String packageName, Intent defIntent) {
        return defIntent;
    }

    protected void onIntentSelected(ResolveInfo ri, Intent intent, boolean alwaysCheck) {
        if ((mAlwaysUseOption || mAdapter.hasFilteredItem()) && mAdapter.mOrigResolveList != null) {
            // Build a reasonable intent filter, based on what matched.
            IntentFilter filter = new IntentFilter();

            if (intent.getAction() != null) {
                filter.addAction(intent.getAction());
            }
            Set<String> categories = intent.getCategories();
            if (categories != null) {
                for (String cat : categories) {
                    filter.addCategory(cat);
                }
            }
            filter.addCategory(Intent.CATEGORY_DEFAULT);

            int cat = ri.match&IntentFilter.MATCH_CATEGORY_MASK;
            Uri data = intent.getData();
            if (cat == IntentFilter.MATCH_CATEGORY_TYPE) {
                String mimeType = intent.resolveType(this);
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
                final int N = mAdapter.mOrigResolveList.size();
                ComponentName[] set = new ComponentName[N];
                int bestMatch = 0;
                for (int i=0; i<N; i++) {
                    ResolveInfo r = mAdapter.mOrigResolveList.get(i);
                    set[i] = new ComponentName(r.activityInfo.packageName,
                            r.activityInfo.name);
                    if (r.match > bestMatch) bestMatch = r.match;
                }
                if (alwaysCheck) {
                    getPackageManager().addPreferredActivity(filter, bestMatch, set,
                            intent.getComponent());
                } else {
                    try {
                        AppGlobals.getPackageManager().setLastChosenActivity(intent,
                                intent.resolveTypeIfNeeded(getContentResolver()),
                                PackageManager.MATCH_DEFAULT_ONLY,
                                filter, bestMatch, intent.getComponent());
                    } catch (RemoteException re) {
                        Log.d(TAG, "Error calling setLastChosenActivity\n" + re);
                    }
                }
            }
        }

        if (intent != null) {
            safelyStartActivity(intent);
        }
    }

    public void safelyStartActivity(Intent intent) {
        if (!mSafeForwardingMode) {
            startActivity(intent);
            return;
        }
        try {
            startActivityAsCaller(intent, null);
        } catch (RuntimeException e) {
            String launchedFromPackage;
            try {
                launchedFromPackage = ActivityManagerNative.getDefault().getLaunchedFromPackage(
                        getActivityToken());
            } catch (RemoteException e2) {
                launchedFromPackage = "??";
            }
            Slog.wtf(TAG, "Unable to launch as uid " + mLaunchedFromUid
                    + " package " + launchedFromPackage + ", while running in "
                    + ActivityThread.currentProcessName(), e);
        }
    }

    void showAppDetails(ResolveInfo ri) {
        Intent in = new Intent().setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", ri.activityInfo.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        startActivity(in);
    }

    private final class DisplayResolveInfo {
        ResolveInfo ri;
        CharSequence displayLabel;
        Drawable displayIcon;
        CharSequence extendedInfo;
        Intent origIntent;

        DisplayResolveInfo(ResolveInfo pri, CharSequence pLabel,
                CharSequence pInfo, Intent pOrigIntent) {
            ri = pri;
            displayLabel = pLabel;
            extendedInfo = pInfo;
            origIntent = pOrigIntent;
        }
    }

    private final class ResolveListAdapter extends BaseAdapter {
        private final Intent[] mInitialIntents;
        private final List<ResolveInfo> mBaseResolveList;
        private ResolveInfo mLastChosen;
        private final Intent mIntent;
        private final int mLaunchedFromUid;
        private final LayoutInflater mInflater;

        List<DisplayResolveInfo> mList;
        List<ResolveInfo> mOrigResolveList;

        private int mLastChosenPosition = -1;
        private boolean mFilterLastUsed;

        public ResolveListAdapter(Context context, Intent intent,
                Intent[] initialIntents, List<ResolveInfo> rList, int launchedFromUid,
                boolean filterLastUsed) {
            mIntent = new Intent(intent);
            mInitialIntents = initialIntents;
            mBaseResolveList = rList;
            mLaunchedFromUid = launchedFromUid;
            mInflater = LayoutInflater.from(context);
            mList = new ArrayList<DisplayResolveInfo>();
            mFilterLastUsed = filterLastUsed;
            rebuildList();
        }

        public void handlePackagesChanged() {
            final int oldItemCount = getCount();
            rebuildList();
            notifyDataSetChanged();
            final int newItemCount = getCount();
            if (newItemCount == 0) {
                // We no longer have any items...  just finish the activity.
                finish();
            } else if (newItemCount != oldItemCount) {
                resizeGrid();
            }
        }

        public DisplayResolveInfo getFilteredItem() {
            if (mFilterLastUsed && mLastChosenPosition >= 0) {
                // Not using getItem since it offsets to dodge this position for the list
                return mList.get(mLastChosenPosition);
            }
            return null;
        }

        public int getFilteredPosition() {
            if (mFilterLastUsed && mLastChosenPosition >= 0) {
                return mLastChosenPosition;
            }
            return AbsListView.INVALID_POSITION;
        }

        public boolean hasFilteredItem() {
            return mFilterLastUsed && mLastChosenPosition >= 0;
        }

        private void rebuildList() {
            List<ResolveInfo> currentResolveList;

            try {
                mLastChosen = AppGlobals.getPackageManager().getLastChosenActivity(
                        mIntent, mIntent.resolveTypeIfNeeded(getContentResolver()),
                        PackageManager.MATCH_DEFAULT_ONLY);
            } catch (RemoteException re) {
                Log.d(TAG, "Error calling setLastChosenActivity\n" + re);
            }

            mList.clear();
            if (mBaseResolveList != null) {
                currentResolveList = mOrigResolveList = mBaseResolveList;
            } else {
                currentResolveList = mOrigResolveList = mPm.queryIntentActivities(
                        mIntent, PackageManager.MATCH_DEFAULT_ONLY
                        | (mFilterLastUsed ? PackageManager.GET_RESOLVED_FILTER : 0));
                // Filter out any activities that the launched uid does not
                // have permission for.  We don't do this when we have an explicit
                // list of resolved activities, because that only happens when
                // we are being subclassed, so we can safely launch whatever
                // they gave us.
                if (currentResolveList != null) {
                    for (int i=currentResolveList.size()-1; i >= 0; i--) {
                        ActivityInfo ai = currentResolveList.get(i).activityInfo;
                        int granted = ActivityManager.checkComponentPermission(
                                ai.permission, mLaunchedFromUid,
                                ai.applicationInfo.uid, ai.exported);
                        if (granted != PackageManager.PERMISSION_GRANTED) {
                            // Access not allowed!
                            if (mOrigResolveList == currentResolveList) {
                                mOrigResolveList = new ArrayList<ResolveInfo>(mOrigResolveList);
                            }
                            currentResolveList.remove(i);
                        }
                    }
                }
            }
            int N;
            if ((currentResolveList != null) && ((N = currentResolveList.size()) > 0)) {
                // Only display the first matches that are either of equal
                // priority or have asked to be default options.
                ResolveInfo r0 = currentResolveList.get(0);
                for (int i=1; i<N; i++) {
                    ResolveInfo ri = currentResolveList.get(i);
                    if (DEBUG) Log.v(
                        TAG,
                        r0.activityInfo.name + "=" +
                        r0.priority + "/" + r0.isDefault + " vs " +
                        ri.activityInfo.name + "=" +
                        ri.priority + "/" + ri.isDefault);
                    if (r0.priority != ri.priority ||
                        r0.isDefault != ri.isDefault) {
                        while (i < N) {
                            if (mOrigResolveList == currentResolveList) {
                                mOrigResolveList = new ArrayList<ResolveInfo>(mOrigResolveList);
                            }
                            currentResolveList.remove(i);
                            N--;
                        }
                    }
                }
                if (N > 1) {
                    Comparator<ResolveInfo> rComparator =
                            new ResolverComparator(ResolverActivity.this);
                    Collections.sort(currentResolveList, rComparator);
                }
                // First put the initial items at the top.
                if (mInitialIntents != null) {
                    for (int i=0; i<mInitialIntents.length; i++) {
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
                        if (ii instanceof LabeledIntent) {
                            LabeledIntent li = (LabeledIntent)ii;
                            ri.resolvePackageName = li.getSourcePackage();
                            ri.labelRes = li.getLabelResource();
                            ri.nonLocalizedLabel = li.getNonLocalizedLabel();
                            ri.icon = li.getIconResource();
                        }
                        mList.add(new DisplayResolveInfo(ri,
                                ri.loadLabel(getPackageManager()), null, ii));
                    }
                }

                // Check for applications with same name and use application name or
                // package name if necessary
                r0 = currentResolveList.get(0);
                int start = 0;
                CharSequence r0Label =  r0.loadLabel(mPm);
                mShowExtended = false;
                for (int i = 1; i < N; i++) {
                    if (r0Label == null) {
                        r0Label = r0.activityInfo.packageName;
                    }
                    ResolveInfo ri = currentResolveList.get(i);
                    CharSequence riLabel = ri.loadLabel(mPm);
                    if (riLabel == null) {
                        riLabel = ri.activityInfo.packageName;
                    }
                    if (riLabel.equals(r0Label)) {
                        continue;
                    }
                    processGroup(currentResolveList, start, (i-1), r0, r0Label);
                    r0 = ri;
                    r0Label = riLabel;
                    start = i;
                }
                // Process last group
                processGroup(currentResolveList, start, (N-1), r0, r0Label);
            }
        }

        private void processGroup(List<ResolveInfo> rList, int start, int end, ResolveInfo ro,
                CharSequence roLabel) {
            // Process labels from start to i
            int num = end - start+1;
            if (num == 1) {
                if (mLastChosen != null
                        && mLastChosen.activityInfo.packageName.equals(
                                ro.activityInfo.packageName)
                        && mLastChosen.activityInfo.name.equals(ro.activityInfo.name)) {
                    mLastChosenPosition = mList.size();
                }
                // No duplicate labels. Use label for entry at start
                mList.add(new DisplayResolveInfo(ro, roLabel, null, null));
            } else {
                mShowExtended = true;
                boolean usePkg = false;
                CharSequence startApp = ro.activityInfo.applicationInfo.loadLabel(mPm);
                if (startApp == null) {
                    usePkg = true;
                }
                if (!usePkg) {
                    // Use HashSet to track duplicates
                    HashSet<CharSequence> duplicates =
                        new HashSet<CharSequence>();
                    duplicates.add(startApp);
                    for (int j = start+1; j <= end ; j++) {
                        ResolveInfo jRi = rList.get(j);
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
                    ResolveInfo add = rList.get(k);
                    if (mLastChosen != null
                            && mLastChosen.activityInfo.packageName.equals(
                                    add.activityInfo.packageName)
                            && mLastChosen.activityInfo.name.equals(add.activityInfo.name)) {
                        mLastChosenPosition = mList.size();
                    }
                    if (usePkg) {
                        // Use application name for all entries from start to end-1
                        mList.add(new DisplayResolveInfo(add, roLabel,
                                add.activityInfo.packageName, null));
                    } else {
                        // Use package name for all entries from start to end-1
                        mList.add(new DisplayResolveInfo(add, roLabel,
                                add.activityInfo.applicationInfo.loadLabel(mPm), null));
                    }
                }
            }
        }

        public ResolveInfo resolveInfoForPosition(int position, boolean filtered) {
            return (filtered ? getItem(position) : mList.get(position)).ri;
        }

        public Intent intentForPosition(int position, boolean filtered) {
            DisplayResolveInfo dri = filtered ? getItem(position) : mList.get(position);

            Intent intent = new Intent(dri.origIntent != null ? dri.origIntent :
                    getReplacementIntent(dri.ri.activityInfo.packageName, mIntent));
            intent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT
                    |Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP);
            ActivityInfo ai = dri.ri.activityInfo;
            intent.setComponent(new ComponentName(
                    ai.applicationInfo.packageName, ai.name));
            return intent;
        }

        public int getCount() {
            int result = mList.size();
            if (mFilterLastUsed && mLastChosenPosition >= 0) {
                result--;
            }
            return result;
        }

        public DisplayResolveInfo getItem(int position) {
            if (mFilterLastUsed && mLastChosenPosition >= 0 && position >= mLastChosenPosition) {
                position++;
            }
            return mList.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View view;
            if (convertView == null) {
                view = mInflater.inflate(
                        com.android.internal.R.layout.resolve_list_item, parent, false);

                final ViewHolder holder = new ViewHolder(view);
                view.setTag(holder);

                // Fix the icon size even if we have different sized resources
                ViewGroup.LayoutParams lp = holder.icon.getLayoutParams();
                lp.width = lp.height = mIconSize;
            } else {
                view = convertView;
            }
            bindView(view, getItem(position));
            return view;
        }

        private final void bindView(View view, DisplayResolveInfo info) {
            final ViewHolder holder = (ViewHolder) view.getTag();
            holder.text.setText(info.displayLabel);
            if (mShowExtended) {
                holder.text2.setVisibility(View.VISIBLE);
                holder.text2.setText(info.extendedInfo);
            } else {
                holder.text2.setVisibility(View.GONE);
            }
            if (info.displayIcon == null) {
                new LoadIconTask().execute(info);
            }
            holder.icon.setImageDrawable(info.displayIcon);
        }
    }

    static class ViewHolder {
        public TextView text;
        public TextView text2;
        public ImageView icon;

        public ViewHolder(View view) {
            text = (TextView) view.findViewById(com.android.internal.R.id.text1);
            text2 = (TextView) view.findViewById(com.android.internal.R.id.text2);
            icon = (ImageView) view.findViewById(R.id.icon);
        }
    }

    class ItemLongClickListener implements AdapterView.OnItemLongClickListener {

        @Override
        public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
            ResolveInfo ri = mAdapter.resolveInfoForPosition(position, true);
            showAppDetails(ri);
            return true;
        }

    }

    class LoadIconTask extends AsyncTask<DisplayResolveInfo, Void, DisplayResolveInfo> {
        @Override
        protected DisplayResolveInfo doInBackground(DisplayResolveInfo... params) {
            final DisplayResolveInfo info = params[0];
            if (info.displayIcon == null) {
                info.displayIcon = loadIconForResolveInfo(info.ri);
            }
            return info;
        }

        @Override
        protected void onPostExecute(DisplayResolveInfo info) {
            mAdapter.notifyDataSetChanged();
        }
    }

    class LoadIconIntoViewTask extends AsyncTask<DisplayResolveInfo, Void, DisplayResolveInfo> {
        final ImageView mTargetView;

        public LoadIconIntoViewTask(ImageView target) {
            mTargetView = target;
        }

        @Override
        protected DisplayResolveInfo doInBackground(DisplayResolveInfo... params) {
            final DisplayResolveInfo info = params[0];
            if (info.displayIcon == null) {
                info.displayIcon = loadIconForResolveInfo(info.ri);
            }
            return info;
        }

        @Override
        protected void onPostExecute(DisplayResolveInfo info) {
            mTargetView.setImageDrawable(info.displayIcon);
        }
    }

    class ResolverComparator implements Comparator<ResolveInfo> {
        private final Collator mCollator;

        public ResolverComparator(Context context) {
            mCollator = Collator.getInstance(context.getResources().getConfiguration().locale);
        }

        @Override
        public int compare(ResolveInfo lhs, ResolveInfo rhs) {
            // We want to put the one targeted to another user at the end of the dialog.
            if (lhs.targetUserId != UserHandle.USER_CURRENT) {
                return 1;
            }

            if (mStats != null) {
                final long timeDiff =
                        getPackageTimeSpent(rhs.activityInfo.packageName) -
                        getPackageTimeSpent(lhs.activityInfo.packageName);

                if (timeDiff != 0) {
                    return timeDiff > 0 ? 1 : -1;
                }
            }

            CharSequence  sa = lhs.loadLabel(mPm);
            if (sa == null) sa = lhs.activityInfo.name;
            CharSequence  sb = rhs.loadLabel(mPm);
            if (sb == null) sb = rhs.activityInfo.name;

            return mCollator.compare(sa.toString(), sb.toString());
        }

        private long getPackageTimeSpent(String packageName) {
            if (mStats != null) {
                final UsageStats stats = mStats.get(packageName);
                if (stats != null) {
                    return stats.getTotalTimeInForeground();
                }

            }
            return 0;
        }
    }
}

