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

package com.android.internal.app.chooser;

import android.annotation.Nullable;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.UserHandle;
import android.service.chooser.ChooserTarget;
import android.text.SpannableStringBuilder;
import android.util.Log;

import com.android.internal.app.ChooserActivity;
import com.android.internal.app.ResolverActivity;
import com.android.internal.app.ResolverListAdapter.ActivityInfoPresentationGetter;
import com.android.internal.app.SimpleIconFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Live target, currently selectable by the user.
 * @see NotSelectableTargetInfo
 */
public final class SelectableTargetInfo implements ChooserTargetInfo {
    private static final String TAG = "SelectableTargetInfo";

    private final Context mContext;
    private final DisplayResolveInfo mSourceInfo;
    private final ResolveInfo mBackupResolveInfo;
    private final ChooserTarget mChooserTarget;
    private final String mDisplayLabel;
    private final PackageManager mPm;
    private final SelectableTargetInfoCommunicator mSelectableTargetInfoCommunicator;
    private Drawable mBadgeIcon = null;
    private CharSequence mBadgeContentDescription;
    private Drawable mDisplayIcon;
    private final Intent mFillInIntent;
    private final int mFillInFlags;
    private final boolean mIsPinned;
    private final float mModifiedScore;
    private boolean mIsSuspended = false;

    public SelectableTargetInfo(Context context, DisplayResolveInfo sourceInfo,
            ChooserTarget chooserTarget,
            float modifiedScore, SelectableTargetInfoCommunicator selectableTargetInfoComunicator,
            @Nullable ShortcutInfo shortcutInfo) {
        mContext = context;
        mSourceInfo = sourceInfo;
        mChooserTarget = chooserTarget;
        mModifiedScore = modifiedScore;
        mPm = mContext.getPackageManager();
        mSelectableTargetInfoCommunicator = selectableTargetInfoComunicator;
        mIsPinned = shortcutInfo != null && shortcutInfo.isPinned();
        if (sourceInfo != null) {
            final ResolveInfo ri = sourceInfo.getResolveInfo();
            if (ri != null) {
                final ActivityInfo ai = ri.activityInfo;
                if (ai != null && ai.applicationInfo != null) {
                    final PackageManager pm = mContext.getPackageManager();
                    mBadgeIcon = pm.getApplicationIcon(ai.applicationInfo);
                    mBadgeContentDescription = pm.getApplicationLabel(ai.applicationInfo);
                    mIsSuspended =
                            (ai.applicationInfo.flags & ApplicationInfo.FLAG_SUSPENDED) != 0;
                }
            }
        }
        // TODO(b/121287224): do this in the background thread, and only for selected targets
        mDisplayIcon = getChooserTargetIconDrawable(chooserTarget, shortcutInfo);

        if (sourceInfo != null) {
            mBackupResolveInfo = null;
        } else {
            mBackupResolveInfo =
                    mContext.getPackageManager().resolveActivity(getResolvedIntent(), 0);
        }

        mFillInIntent = null;
        mFillInFlags = 0;

        mDisplayLabel = sanitizeDisplayLabel(chooserTarget.getTitle());
    }

    private SelectableTargetInfo(SelectableTargetInfo other,
            Intent fillInIntent, int flags) {
        mContext = other.mContext;
        mPm = other.mPm;
        mSelectableTargetInfoCommunicator = other.mSelectableTargetInfoCommunicator;
        mSourceInfo = other.mSourceInfo;
        mBackupResolveInfo = other.mBackupResolveInfo;
        mChooserTarget = other.mChooserTarget;
        mBadgeIcon = other.mBadgeIcon;
        mBadgeContentDescription = other.mBadgeContentDescription;
        mDisplayIcon = other.mDisplayIcon;
        mFillInIntent = fillInIntent;
        mFillInFlags = flags;
        mModifiedScore = other.mModifiedScore;
        mIsPinned = other.mIsPinned;

        mDisplayLabel = sanitizeDisplayLabel(mChooserTarget.getTitle());
    }

    private String sanitizeDisplayLabel(CharSequence label) {
        SpannableStringBuilder sb = new SpannableStringBuilder(label);
        sb.clearSpans();
        return sb.toString();
    }

    public boolean isSuspended() {
        return mIsSuspended;
    }

    public DisplayResolveInfo getDisplayResolveInfo() {
        return mSourceInfo;
    }

    private Drawable getChooserTargetIconDrawable(ChooserTarget target,
            @Nullable ShortcutInfo shortcutInfo) {
        Drawable directShareIcon = null;

        // First get the target drawable and associated activity info
        final Icon icon = target.getIcon();
        if (icon != null) {
            directShareIcon = icon.loadDrawable(mContext);
        } else if (shortcutInfo != null) {
            LauncherApps launcherApps = (LauncherApps) mContext.getSystemService(
                    Context.LAUNCHER_APPS_SERVICE);
            directShareIcon = launcherApps.getShortcutIconDrawable(shortcutInfo, 0);
        }

        if (directShareIcon == null) return null;

        ActivityInfo info = null;
        try {
            info = mPm.getActivityInfo(target.getComponentName(), 0);
        } catch (PackageManager.NameNotFoundException error) {
            Log.e(TAG, "Could not find activity associated with ChooserTarget");
        }

        if (info == null) return null;

        // Now fetch app icon and raster with no badging even in work profile
        Bitmap appIcon = mSelectableTargetInfoCommunicator.makePresentationGetter(info)
                .getIconBitmap(null);

        // Raster target drawable with appIcon as a badge
        SimpleIconFactory sif = SimpleIconFactory.obtain(mContext);
        Bitmap directShareBadgedIcon = sif.createAppBadgedIconBitmap(directShareIcon, appIcon);
        sif.recycle();

        return new BitmapDrawable(mContext.getResources(), directShareBadgedIcon);
    }

    public float getModifiedScore() {
        return mModifiedScore;
    }

    @Override
    public Intent getResolvedIntent() {
        if (mSourceInfo != null) {
            return mSourceInfo.getResolvedIntent();
        }

        final Intent targetIntent = new Intent(mSelectableTargetInfoCommunicator.getTargetIntent());
        targetIntent.setComponent(mChooserTarget.getComponentName());
        targetIntent.putExtras(mChooserTarget.getIntentExtras());
        return targetIntent;
    }

    @Override
    public ComponentName getResolvedComponentName() {
        if (mSourceInfo != null) {
            return mSourceInfo.getResolvedComponentName();
        } else if (mBackupResolveInfo != null) {
            return new ComponentName(mBackupResolveInfo.activityInfo.packageName,
                    mBackupResolveInfo.activityInfo.name);
        }
        return null;
    }

    private Intent getBaseIntentToSend() {
        Intent result = getResolvedIntent();
        if (result == null) {
            Log.e(TAG, "ChooserTargetInfo: no base intent available to send");
        } else {
            result = new Intent(result);
            if (mFillInIntent != null) {
                result.fillIn(mFillInIntent, mFillInFlags);
            }
            result.fillIn(mSelectableTargetInfoCommunicator.getReferrerFillInIntent(), 0);
        }
        return result;
    }

    @Override
    public boolean start(Activity activity, Bundle options) {
        throw new RuntimeException("ChooserTargets should be started as caller.");
    }

    @Override
    public boolean startAsCaller(ResolverActivity activity, Bundle options, int userId) {
        final Intent intent = getBaseIntentToSend();
        if (intent == null) {
            return false;
        }
        intent.setComponent(mChooserTarget.getComponentName());
        intent.putExtras(mChooserTarget.getIntentExtras());

        // Important: we will ignore the target security checks in ActivityManager
        // if and only if the ChooserTarget's target package is the same package
        // where we got the ChooserTargetService that provided it. This lets a
        // ChooserTargetService provide a non-exported or permission-guarded target
        // to the chooser for the user to pick.
        //
        // If mSourceInfo is null, we got this ChooserTarget from the caller or elsewhere
        // so we'll obey the caller's normal security checks.
        final boolean ignoreTargetSecurity = mSourceInfo != null
                && mSourceInfo.getResolvedComponentName().getPackageName()
                .equals(mChooserTarget.getComponentName().getPackageName());
        activity.startActivityAsCaller(intent, options, ignoreTargetSecurity, userId);
        return true;
    }

    @Override
    public boolean startAsUser(Activity activity, Bundle options, UserHandle user) {
        throw new RuntimeException("ChooserTargets should be started as caller.");
    }

    @Override
    public ResolveInfo getResolveInfo() {
        return mSourceInfo != null ? mSourceInfo.getResolveInfo() : mBackupResolveInfo;
    }

    @Override
    public CharSequence getDisplayLabel() {
        return mDisplayLabel;
    }

    @Override
    public CharSequence getExtendedInfo() {
        // ChooserTargets have badge icons, so we won't show the extended info to disambiguate.
        return null;
    }

    @Override
    public Drawable getDisplayIcon(Context context) {
        return mDisplayIcon;
    }

    public ChooserTarget getChooserTarget() {
        return mChooserTarget;
    }

    @Override
    public TargetInfo cloneFilledIn(Intent fillInIntent, int flags) {
        return new SelectableTargetInfo(this, fillInIntent, flags);
    }

    @Override
    public List<Intent> getAllSourceIntents() {
        final List<Intent> results = new ArrayList<>();
        if (mSourceInfo != null) {
            // We only queried the service for the first one in our sourceinfo.
            results.add(mSourceInfo.getAllSourceIntents().get(0));
        }
        return results;
    }

    @Override
    public boolean isPinned() {
        return mIsPinned;
    }

    /**
     * Necessary methods to communicate between {@link SelectableTargetInfo}
     * and {@link ResolverActivity} or {@link ChooserActivity}.
     */
    public interface SelectableTargetInfoCommunicator {

        ActivityInfoPresentationGetter makePresentationGetter(ActivityInfo info);

        Intent getTargetIntent();

        Intent getReferrerFillInIntent();
    }
}
