/*
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/
package android.widget;

import android.os.UserHandle;
import com.android.internal.R;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This class contains the SecurityPermissions view implementation.
 * Initially the package's advanced or dangerous security permissions
 * are displayed under categorized
 * groups. Clicking on the additional permissions presents
 * extended information consisting of all groups and permissions.
 * To use this view define a LinearLayout or any ViewGroup and add this
 * view by instantiating AppSecurityPermissions and invoking getPermissionsView.
 * 
 * {@hide}
 */
public class AppSecurityPermissions {

    public static final int WHICH_NEW = 1<<2;
    public static final int WHICH_ALL = 0xffff;

    private final static String TAG = "AppSecurityPermissions";
    private final static boolean localLOGV = false;
    private final Context mContext;
    private final LayoutInflater mInflater;
    private final PackageManager mPm;
    private final Map<String, MyPermissionGroupInfo> mPermGroups
            = new HashMap<String, MyPermissionGroupInfo>();
    private final List<MyPermissionGroupInfo> mPermGroupsList
            = new ArrayList<MyPermissionGroupInfo>();
    private final PermissionGroupInfoComparator mPermGroupComparator =
            new PermissionGroupInfoComparator();
    private final PermissionInfoComparator mPermComparator = new PermissionInfoComparator();
    private final List<MyPermissionInfo> mPermsList = new ArrayList<MyPermissionInfo>();
    private final CharSequence mNewPermPrefix;
    private String mPackageName;

    static class MyPermissionGroupInfo extends PermissionGroupInfo {
        CharSequence mLabel;

        final ArrayList<MyPermissionInfo> mNewPermissions = new ArrayList<MyPermissionInfo>();
        final ArrayList<MyPermissionInfo> mAllPermissions = new ArrayList<MyPermissionInfo>();

        MyPermissionGroupInfo(PermissionInfo perm) {
            name = perm.packageName;
            packageName = perm.packageName;
        }

        MyPermissionGroupInfo(PermissionGroupInfo info) {
            super(info);
        }

        public Drawable loadGroupIcon(Context context, PackageManager pm) {
            if (icon != 0) {
                return loadUnbadgedIcon(pm);
            } else {
                return context.getDrawable(R.drawable.ic_perm_device_info);
            }
        }
    }

    private static class MyPermissionInfo extends PermissionInfo {
        CharSequence mLabel;

        /**
         * PackageInfo.requestedPermissionsFlags for the new package being installed.
         */
        int mNewReqFlags;

        /**
         * PackageInfo.requestedPermissionsFlags for the currently installed
         * package, if it is installed.
         */
        int mExistingReqFlags;

        /**
         * True if this should be considered a new permission.
         */
        boolean mNew;

        MyPermissionInfo(PermissionInfo info) {
            super(info);
        }
    }

    public static class PermissionItemView extends LinearLayout implements View.OnClickListener {
        MyPermissionGroupInfo mGroup;
        MyPermissionInfo mPerm;
        AlertDialog mDialog;
        private boolean mShowRevokeUI = false;
        private String mPackageName;

        public PermissionItemView(Context context, AttributeSet attrs) {
            super(context, attrs);
            setClickable(true);
        }

        public void setPermission(MyPermissionGroupInfo grp, MyPermissionInfo perm,
                boolean first, CharSequence newPermPrefix, String packageName,
                boolean showRevokeUI) {
            mGroup = grp;
            mPerm = perm;
            mShowRevokeUI = showRevokeUI;
            mPackageName = packageName;

            ImageView permGrpIcon = (ImageView) findViewById(R.id.perm_icon);
            TextView permNameView = (TextView) findViewById(R.id.perm_name);

            PackageManager pm = getContext().getPackageManager();
            Drawable icon = null;
            if (first) {
                icon = grp.loadGroupIcon(getContext(), pm);
            }
            CharSequence label = perm.mLabel;
            if (perm.mNew && newPermPrefix != null) {
                // If this is a new permission, format it appropriately.
                SpannableStringBuilder builder = new SpannableStringBuilder();
                Parcel parcel = Parcel.obtain();
                TextUtils.writeToParcel(newPermPrefix, parcel, 0);
                parcel.setDataPosition(0);
                CharSequence newStr = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
                parcel.recycle();
                builder.append(newStr);
                builder.append(label);
                label = builder;
            }

            permGrpIcon.setImageDrawable(icon);
            permNameView.setText(label);
            setOnClickListener(this);
            if (localLOGV) Log.i(TAG, "Made perm item " + perm.name
                    + ": " + label + " in group " + grp.name);
        }

        @Override
        public void onClick(View v) {
            if (mGroup != null && mPerm != null) {
                if (mDialog != null) {
                    mDialog.dismiss();
                }
                PackageManager pm = getContext().getPackageManager();
                AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                builder.setTitle(mGroup.mLabel);
                if (mPerm.descriptionRes != 0) {
                    builder.setMessage(mPerm.loadDescription(pm));
                } else {
                    CharSequence appName;
                    try {
                        ApplicationInfo app = pm.getApplicationInfo(mPerm.packageName, 0);
                        appName = app.loadLabel(pm);
                    } catch (NameNotFoundException e) {
                        appName = mPerm.packageName;
                    }
                    StringBuilder sbuilder = new StringBuilder(128);
                    sbuilder.append(getContext().getString(
                            R.string.perms_description_app, appName));
                    sbuilder.append("\n\n");
                    sbuilder.append(mPerm.name);
                    builder.setMessage(sbuilder.toString());
                }
                builder.setCancelable(true);
                builder.setIcon(mGroup.loadGroupIcon(getContext(), pm));
                addRevokeUIIfNecessary(builder);
                mDialog = builder.show();
                mDialog.setCanceledOnTouchOutside(true);
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (mDialog != null) {
                mDialog.dismiss();
            }
        }

        private void addRevokeUIIfNecessary(AlertDialog.Builder builder) {
            if (!mShowRevokeUI) {
                return;
            }

            final boolean isRequired =
                    ((mPerm.mExistingReqFlags & PackageInfo.REQUESTED_PERMISSION_REQUIRED) != 0);

            if (isRequired) {
                return;
            }

            DialogInterface.OnClickListener ocl = new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    PackageManager pm = getContext().getPackageManager();
                    pm.revokeRuntimePermission(mPackageName, mPerm.name,
                            new UserHandle(mContext.getUserId()));
                    PermissionItemView.this.setVisibility(View.GONE);
                }
            };
            builder.setNegativeButton(R.string.revoke, ocl);
            builder.setPositiveButton(R.string.ok, null);
        }
    }

    private AppSecurityPermissions(Context context) {
        mContext = context;
        mInflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mPm = mContext.getPackageManager();
        // Pick up from framework resources instead.
        mNewPermPrefix = mContext.getText(R.string.perms_new_perm_prefix);
    }

    public AppSecurityPermissions(Context context, String packageName) {
        this(context);
        mPackageName = packageName;
        Set<MyPermissionInfo> permSet = new HashSet<MyPermissionInfo>();
        PackageInfo pkgInfo;
        try {
            pkgInfo = mPm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS);
        } catch (NameNotFoundException e) {
            Log.w(TAG, "Couldn't retrieve permissions for package:"+packageName);
            return;
        }
        // Extract all user permissions
        if((pkgInfo.applicationInfo != null) && (pkgInfo.applicationInfo.uid != -1)) {
            getAllUsedPermissions(pkgInfo.applicationInfo.uid, permSet);
        }
        mPermsList.addAll(permSet);
        setPermissions(mPermsList);
    }

    public AppSecurityPermissions(Context context, PackageInfo info) {
        this(context);
        Set<MyPermissionInfo> permSet = new HashSet<MyPermissionInfo>();
        if(info == null) {
            return;
        }
        mPackageName = info.packageName;

        // Convert to a PackageInfo
        PackageInfo installedPkgInfo = null;
        // Get requested permissions
        if (info.requestedPermissions != null) {
            try {
                installedPkgInfo = mPm.getPackageInfo(info.packageName,
                        PackageManager.GET_PERMISSIONS);
            } catch (NameNotFoundException e) {
            }
            extractPerms(info, permSet, installedPkgInfo);
        }
        // Get permissions related to shared user if any
        if (info.sharedUserId != null) {
            int sharedUid;
            try {
                sharedUid = mPm.getUidForSharedUser(info.sharedUserId);
                getAllUsedPermissions(sharedUid, permSet);
            } catch (NameNotFoundException e) {
                Log.w(TAG, "Couldn't retrieve shared user id for: " + info.packageName);
            }
        }
        // Retrieve list of permissions
        mPermsList.addAll(permSet);
        setPermissions(mPermsList);
    }

    /**
     * Utility to retrieve a view displaying a single permission.  This provides
     * the old UI layout for permissions; it is only here for the device admin
     * settings to continue to use.
     */
    public static View getPermissionItemView(Context context,
            CharSequence grpName, CharSequence description, boolean dangerous) {
        LayoutInflater inflater = (LayoutInflater)context.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        Drawable icon = context.getDrawable(dangerous
                ? R.drawable.ic_bullet_key_permission : R.drawable.ic_text_dot);
        return getPermissionItemViewOld(context, inflater, grpName,
                description, dangerous, icon);
    }
    
    private void getAllUsedPermissions(int sharedUid, Set<MyPermissionInfo> permSet) {
        String sharedPkgList[] = mPm.getPackagesForUid(sharedUid);
        if(sharedPkgList == null || (sharedPkgList.length == 0)) {
            return;
        }
        for(String sharedPkg : sharedPkgList) {
            getPermissionsForPackage(sharedPkg, permSet);
        }
    }
    
    private void getPermissionsForPackage(String packageName, Set<MyPermissionInfo> permSet) {
        try {
            PackageInfo pkgInfo = mPm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS);
            extractPerms(pkgInfo, permSet, pkgInfo);
        } catch (NameNotFoundException e) {
            Log.w(TAG, "Couldn't retrieve permissions for package: " + packageName);
        }
    }

    private void extractPerms(PackageInfo info, Set<MyPermissionInfo> permSet,
            PackageInfo installedPkgInfo) {
        String[] strList = info.requestedPermissions;
        int[] flagsList = info.requestedPermissionsFlags;
        if ((strList == null) || (strList.length == 0)) {
            return;
        }
        for (int i=0; i<strList.length; i++) {
            String permName = strList[i];
            try {
                PermissionInfo tmpPermInfo = mPm.getPermissionInfo(permName, 0);
                if (tmpPermInfo == null) {
                    continue;
                }
                int existingIndex = -1;
                if (installedPkgInfo != null
                        && installedPkgInfo.requestedPermissions != null) {
                    for (int j=0; j<installedPkgInfo.requestedPermissions.length; j++) {
                        if (permName.equals(installedPkgInfo.requestedPermissions[j])) {
                            existingIndex = j;
                            break;
                        }
                    }
                }
                final int existingFlags = existingIndex >= 0 ?
                        installedPkgInfo.requestedPermissionsFlags[existingIndex] : 0;
                if (!isDisplayablePermission(tmpPermInfo, flagsList[i], existingFlags)) {
                    // This is not a permission that is interesting for the user
                    // to see, so skip it.
                    continue;
                }
                final String origGroupName = tmpPermInfo.group;
                String groupName = origGroupName;
                if (groupName == null) {
                    groupName = tmpPermInfo.packageName;
                    tmpPermInfo.group = groupName;
                }
                MyPermissionGroupInfo group = mPermGroups.get(groupName);
                if (group == null) {
                    PermissionGroupInfo grp = null;
                    if (origGroupName != null) {
                        grp = mPm.getPermissionGroupInfo(origGroupName, 0);
                    }
                    if (grp != null) {
                        group = new MyPermissionGroupInfo(grp);
                    } else {
                        // We could be here either because the permission
                        // didn't originally specify a group or the group it
                        // gave couldn't be found.  In either case, we consider
                        // its group to be the permission's package name.
                        tmpPermInfo.group = tmpPermInfo.packageName;
                        group = mPermGroups.get(tmpPermInfo.group);
                        if (group == null) {
                            group = new MyPermissionGroupInfo(tmpPermInfo);
                        }
                        group = new MyPermissionGroupInfo(tmpPermInfo);
                    }
                    mPermGroups.put(tmpPermInfo.group, group);
                }
                final boolean newPerm = installedPkgInfo != null
                        && (existingFlags&PackageInfo.REQUESTED_PERMISSION_GRANTED) == 0;
                MyPermissionInfo myPerm = new MyPermissionInfo(tmpPermInfo);
                myPerm.mNewReqFlags = flagsList[i];
                myPerm.mExistingReqFlags = existingFlags;
                // This is a new permission if the app is already installed and
                // doesn't currently hold this permission.
                myPerm.mNew = newPerm;
                permSet.add(myPerm);
            } catch (NameNotFoundException e) {
                Log.i(TAG, "Ignoring unknown permission:"+permName);
            }
        }
    }
    
    public int getPermissionCount() {
        return getPermissionCount(WHICH_ALL);
    }

    private List<MyPermissionInfo> getPermissionList(MyPermissionGroupInfo grp, int which) {
        if (which == WHICH_NEW) {
            return grp.mNewPermissions;
        } else {
            return grp.mAllPermissions;
        }
    }

    public int getPermissionCount(int which) {
        int N = 0;
        for (int i=0; i<mPermGroupsList.size(); i++) {
            N += getPermissionList(mPermGroupsList.get(i), which).size();
        }
        return N;
    }

    public View getPermissionsView() {
        return getPermissionsView(WHICH_ALL, false);
    }

    public View getPermissionsViewWithRevokeButtons() {
        return getPermissionsView(WHICH_ALL, true);
    }

    public View getPermissionsView(int which) {
        return getPermissionsView(which, false);
    }

    private View getPermissionsView(int which, boolean showRevokeUI) {
        LinearLayout permsView = (LinearLayout) mInflater.inflate(R.layout.app_perms_summary, null);
        LinearLayout displayList = (LinearLayout) permsView.findViewById(R.id.perms_list);
        View noPermsView = permsView.findViewById(R.id.no_permissions);

        displayPermissions(mPermGroupsList, displayList, which, showRevokeUI);
        if (displayList.getChildCount() <= 0) {
            noPermsView.setVisibility(View.VISIBLE);
        }

        return permsView;
    }

    /**
     * Utility method that displays permissions from a map containing group name and
     * list of permission descriptions.
     */
    private void displayPermissions(List<MyPermissionGroupInfo> groups,
            LinearLayout permListView, int which, boolean showRevokeUI) {
        permListView.removeAllViews();

        int spacing = (int)(8*mContext.getResources().getDisplayMetrics().density);

        for (int i=0; i<groups.size(); i++) {
            MyPermissionGroupInfo grp = groups.get(i);
            final List<MyPermissionInfo> perms = getPermissionList(grp, which);
            for (int j=0; j<perms.size(); j++) {
                MyPermissionInfo perm = perms.get(j);
                View view = getPermissionItemView(grp, perm, j == 0,
                        which != WHICH_NEW ? mNewPermPrefix : null, showRevokeUI);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                if (j == 0) {
                    lp.topMargin = spacing;
                }
                if (j == grp.mAllPermissions.size()-1) {
                    lp.bottomMargin = spacing;
                }
                if (permListView.getChildCount() == 0) {
                    lp.topMargin *= 2;
                }
                permListView.addView(view, lp);
            }
        }
    }

    private PermissionItemView getPermissionItemView(MyPermissionGroupInfo grp,
            MyPermissionInfo perm, boolean first, CharSequence newPermPrefix, boolean showRevokeUI) {
        return getPermissionItemView(mContext, mInflater, grp, perm, first, newPermPrefix,
                mPackageName, showRevokeUI);
    }

    private static PermissionItemView getPermissionItemView(Context context, LayoutInflater inflater,
            MyPermissionGroupInfo grp, MyPermissionInfo perm, boolean first,
            CharSequence newPermPrefix, String packageName, boolean showRevokeUI) {
            PermissionItemView permView = (PermissionItemView)inflater.inflate(
                (perm.flags & PermissionInfo.FLAG_COSTS_MONEY) != 0
                        ? R.layout.app_permission_item_money : R.layout.app_permission_item,
                null);
        permView.setPermission(grp, perm, first, newPermPrefix, packageName, showRevokeUI);
        return permView;
    }

    private static View getPermissionItemViewOld(Context context, LayoutInflater inflater,
            CharSequence grpName, CharSequence permList, boolean dangerous, Drawable icon) {
        View permView = inflater.inflate(R.layout.app_permission_item_old, null);

        TextView permGrpView = (TextView) permView.findViewById(R.id.permission_group);
        TextView permDescView = (TextView) permView.findViewById(R.id.permission_list);

        ImageView imgView = (ImageView)permView.findViewById(R.id.perm_icon);
        imgView.setImageDrawable(icon);
        if(grpName != null) {
            permGrpView.setText(grpName);
            permDescView.setText(permList);
        } else {
            permGrpView.setText(permList);
            permDescView.setVisibility(View.GONE);
        }
        return permView;
    }

    private boolean isDisplayablePermission(PermissionInfo pInfo, int newReqFlags,
            int existingReqFlags) {
        final int base = pInfo.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
        final boolean isNormal = (base == PermissionInfo.PROTECTION_NORMAL);

        // We do not show normal permissions in the UI.
        if (isNormal) {
            return false;
        }

        final boolean isDangerous = (base == PermissionInfo.PROTECTION_DANGEROUS)
                || ((pInfo.protectionLevel&PermissionInfo.PROTECTION_FLAG_PRE23) != 0);
        final boolean isRequired =
                ((newReqFlags&PackageInfo.REQUESTED_PERMISSION_REQUIRED) != 0);
        final boolean isDevelopment =
                ((pInfo.protectionLevel&PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0);
        final boolean wasGranted =
                ((existingReqFlags&PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0);
        final boolean isGranted =
                ((newReqFlags&PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0);

        // Dangerous and normal permissions are always shown to the user if the permission
        // is required, or it was previously granted
        if (isDangerous && (isRequired || wasGranted || isGranted)) {
            return true;
        }

        // Development permissions are only shown to the user if they are already
        // granted to the app -- if we are installing an app and they are not
        // already granted, they will not be granted as part of the install.
        if (isDevelopment && wasGranted) {
            if (localLOGV) Log.i(TAG, "Special perm " + pInfo.name
                    + ": protlevel=0x" + Integer.toHexString(pInfo.protectionLevel));
            return true;
        }
        return false;
    }
    
    private static class PermissionGroupInfoComparator implements Comparator<MyPermissionGroupInfo> {
        private final Collator sCollator = Collator.getInstance();
        @Override
        public final int compare(MyPermissionGroupInfo a, MyPermissionGroupInfo b) {
            return sCollator.compare(a.mLabel, b.mLabel);
        }
    }
    
    private static class PermissionInfoComparator implements Comparator<MyPermissionInfo> {
        private final Collator sCollator = Collator.getInstance();
        PermissionInfoComparator() {
        }
        public final int compare(MyPermissionInfo a, MyPermissionInfo b) {
            return sCollator.compare(a.mLabel, b.mLabel);
        }
    }

    private void addPermToList(List<MyPermissionInfo> permList,
            MyPermissionInfo pInfo) {
        if (pInfo.mLabel == null) {
            pInfo.mLabel = pInfo.loadLabel(mPm);
        }
        int idx = Collections.binarySearch(permList, pInfo, mPermComparator);
        if(localLOGV) Log.i(TAG, "idx="+idx+", list.size="+permList.size());
        if (idx < 0) {
            idx = -idx-1;
            permList.add(idx, pInfo);
        }
    }

    private void setPermissions(List<MyPermissionInfo> permList) {
        if (permList != null) {
            // First pass to group permissions
            for (MyPermissionInfo pInfo : permList) {
                if(localLOGV) Log.i(TAG, "Processing permission:"+pInfo.name);
                if(!isDisplayablePermission(pInfo, pInfo.mNewReqFlags, pInfo.mExistingReqFlags)) {
                    if(localLOGV) Log.i(TAG, "Permission:"+pInfo.name+" is not displayable");
                    continue;
                }
                MyPermissionGroupInfo group = mPermGroups.get(pInfo.group);
                if (group != null) {
                    pInfo.mLabel = pInfo.loadLabel(mPm);
                    addPermToList(group.mAllPermissions, pInfo);
                    if (pInfo.mNew) {
                        addPermToList(group.mNewPermissions, pInfo);
                    }
                }
            }
        }

        for (MyPermissionGroupInfo pgrp : mPermGroups.values()) {
            if (pgrp.labelRes != 0 || pgrp.nonLocalizedLabel != null) {
                pgrp.mLabel = pgrp.loadLabel(mPm);
            } else {
                ApplicationInfo app;
                try {
                    app = mPm.getApplicationInfo(pgrp.packageName, 0);
                    pgrp.mLabel = app.loadLabel(mPm);
                } catch (NameNotFoundException e) {
                    pgrp.mLabel = pgrp.loadLabel(mPm);
                }
            }
            mPermGroupsList.add(pgrp);
        }
        Collections.sort(mPermGroupsList, mPermGroupComparator);
    }
}
