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

import com.android.internal.R;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageParser;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
public class AppSecurityPermissions  implements View.OnClickListener {

    private enum State {
        NO_PERMS,
        DANGEROUS_ONLY,
        NORMAL_ONLY,
        BOTH
    }

    static class MyPermissionInfo extends PermissionInfo {
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

        MyPermissionInfo() {
        }

        MyPermissionInfo(PermissionInfo info) {
            super(info);
        }

        MyPermissionInfo(MyPermissionInfo info) {
            super(info);
            mNewReqFlags = info.mNewReqFlags;
            mExistingReqFlags = info.mExistingReqFlags;
            mNew = info.mNew;
        }
    }

    private final static String TAG = "AppSecurityPermissions";
    private boolean localLOGV = false;
    private Context mContext;
    private LayoutInflater mInflater;
    private PackageManager mPm;
    private LinearLayout mPermsView;
    private Map<String, CharSequence> mNewMap;
    private Map<String, CharSequence> mDangerousMap;
    private Map<String, CharSequence> mNormalMap;
    private List<MyPermissionInfo> mPermsList;
    private String mDefaultGrpLabel;
    private String mDefaultGrpName="DefaultGrp";
    private String mPermFormat;
    private CharSequence mNewPermPrefix;
    private Drawable mNormalIcon;
    private Drawable mDangerousIcon;
    private boolean mExpanded;
    private Drawable mShowMaxIcon;
    private Drawable mShowMinIcon;
    private View mShowMore;
    private TextView mShowMoreText;
    private ImageView mShowMoreIcon;
    private State mCurrentState;
    private LinearLayout mNonDangerousList;
    private LinearLayout mDangerousList;
    private LinearLayout mNewList;
    private HashMap<String, CharSequence> mGroupLabelCache;
    private View mNoPermsView;

    public AppSecurityPermissions(Context context, List<PermissionInfo> permList) {
        mContext = context;
        mPm = mContext.getPackageManager();
        for (PermissionInfo pi : permList) {
            mPermsList.add(new MyPermissionInfo(pi));
        }
    }
    
    public AppSecurityPermissions(Context context, String packageName) {
        mContext = context;
        mPm = mContext.getPackageManager();
        mPermsList = new ArrayList<MyPermissionInfo>();
        Set<MyPermissionInfo> permSet = new HashSet<MyPermissionInfo>();
        PackageInfo pkgInfo;
        try {
            pkgInfo = mPm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS);
        } catch (NameNotFoundException e) {
            Log.w(TAG, "Could'nt retrieve permissions for package:"+packageName);
            return;
        }
        // Extract all user permissions
        if((pkgInfo.applicationInfo != null) && (pkgInfo.applicationInfo.uid != -1)) {
            getAllUsedPermissions(pkgInfo.applicationInfo.uid, permSet);
        }
        for(MyPermissionInfo tmpInfo : permSet) {
            mPermsList.add(tmpInfo);
        }
    }
    
    public AppSecurityPermissions(Context context, PackageParser.Package pkg) {
        mContext = context;
        mPm = mContext.getPackageManager();
        mPermsList = new ArrayList<MyPermissionInfo>();
        Set<MyPermissionInfo> permSet = new HashSet<MyPermissionInfo>();
        if(pkg == null) {
            return;
        }

        // Convert to a PackageInfo
        PackageInfo info = PackageParser.generatePackageInfo(pkg, null,
                PackageManager.GET_PERMISSIONS, 0, 0, null);
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
        // Get permissions related to  shared user if any
        if (pkg.mSharedUserId != null) {
            int sharedUid;
            try {
                sharedUid = mPm.getUidForSharedUser(pkg.mSharedUserId);
                getAllUsedPermissions(sharedUid, permSet);
            } catch (NameNotFoundException e) {
                Log.w(TAG, "Could'nt retrieve shared user id for:"+pkg.packageName);
            }
        }
        // Retrieve list of permissions
        for (MyPermissionInfo tmpInfo : permSet) {
            mPermsList.add(tmpInfo);
        }
    }
    
    /**
     * Utility to retrieve a view displaying a single permission.
     */
    public static View getPermissionItemView(Context context,
            CharSequence grpName, CharSequence description, boolean dangerous) {
        LayoutInflater inflater = (LayoutInflater)context.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        Drawable icon = context.getResources().getDrawable(dangerous
                ? R.drawable.ic_bullet_key_permission : R.drawable.ic_text_dot);
        return getPermissionItemView(context, inflater, grpName,
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
    
    private void getPermissionsForPackage(String packageName, 
            Set<MyPermissionInfo> permSet) {
        PackageInfo pkgInfo;
        try {
            pkgInfo = mPm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS);
        } catch (NameNotFoundException e) {
            Log.w(TAG, "Couldn't retrieve permissions for package:"+packageName);
            return;
        }
        if ((pkgInfo != null) && (pkgInfo.requestedPermissions != null)) {
            extractPerms(pkgInfo, permSet, pkgInfo);
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
            // If we are only looking at an existing app, then we only
            // care about permissions that have actually been granted to it.
            if (installedPkgInfo != null && info == installedPkgInfo) {
                if ((flagsList[i]&PackageInfo.REQUESTED_PERMISSION_GRANTED) == 0) {
                    continue;
                }
            }
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
                MyPermissionInfo myPerm = new MyPermissionInfo(tmpPermInfo);
                myPerm.mNewReqFlags = flagsList[i];
                myPerm.mExistingReqFlags = existingFlags;
                // This is a new permission if the app is already installed and
                // doesn't currently hold this permission.
                myPerm.mNew = installedPkgInfo != null
                        && (existingFlags&PackageInfo.REQUESTED_PERMISSION_GRANTED) == 0;
                permSet.add(myPerm);
            } catch (NameNotFoundException e) {
                Log.i(TAG, "Ignoring unknown permission:"+permName);
            }
        }
    }
    
    public int getPermissionCount() {
        return mPermsList.size();
    }

    public View getPermissionsView() {
        
        mInflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mPermsView = (LinearLayout) mInflater.inflate(R.layout.app_perms_summary, null);
        mShowMore = mPermsView.findViewById(R.id.show_more);
        mShowMoreIcon = (ImageView) mShowMore.findViewById(R.id.show_more_icon);
        mShowMoreText = (TextView) mShowMore.findViewById(R.id.show_more_text);
        mNewList = (LinearLayout) mPermsView.findViewById(R.id.new_perms_list);
        mDangerousList = (LinearLayout) mPermsView.findViewById(R.id.dangerous_perms_list);
        mNonDangerousList = (LinearLayout) mPermsView.findViewById(R.id.non_dangerous_perms_list);
        mNoPermsView = mPermsView.findViewById(R.id.no_permissions);

        // Set up the LinearLayout that acts like a list item.
        mShowMore.setClickable(true);
        mShowMore.setOnClickListener(this);
        mShowMore.setFocusable(true);

        // Pick up from framework resources instead.
        mDefaultGrpLabel = mContext.getString(R.string.default_permission_group);
        mPermFormat = mContext.getString(R.string.permissions_format);
        mNewPermPrefix = mContext.getText(R.string.perms_new_perm_prefix);
        mNormalIcon = mContext.getResources().getDrawable(R.drawable.ic_text_dot);
        mDangerousIcon = mContext.getResources().getDrawable(R.drawable.ic_bullet_key_permission);
        mShowMaxIcon = mContext.getResources().getDrawable(R.drawable.expander_close_holo_dark);
        mShowMinIcon = mContext.getResources().getDrawable(R.drawable.expander_open_holo_dark);
        
        // Set permissions view
        setPermissions(mPermsList);
        return mPermsView;
    }

    /**
     * Utility method that concatenates two strings defined by mPermFormat.
     * a null value is returned if both str1 and str2 are null, if one of the strings
     * is null the other non null value is returned without formatting
     * this is to placate initial error checks
     */
    private CharSequence formatPermissions(CharSequence groupDesc, CharSequence permDesc,
            boolean newPerms) {
        if (permDesc == null) {
            return groupDesc;
        }
        // Sometimes people write permission names with a trailing period;
        // strip that if it appears.
        int len = permDesc.length();
        if (len > 0 && permDesc.charAt(len-1) == '.') {
            permDesc = (permDesc.toString()).substring(0, len-1);
        }
        if (newPerms) {
            if (true) {
                // If this is a new permission, format it appropriately.
                SpannableStringBuilder builder = new SpannableStringBuilder();
                if (groupDesc != null) {
                    // The previous permissions go in front, with a newline
                    // separating them.
                    builder.append(groupDesc);
                    builder.append("\n");
                }
                Parcel parcel = Parcel.obtain();
                TextUtils.writeToParcel(mNewPermPrefix, parcel, 0);
                parcel.setDataPosition(0);
                CharSequence newStr = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
                parcel.recycle();
                builder.append(newStr);
                builder.append(permDesc);
                return builder;
            } else {
                // If this is a new permission, format it appropriately.
                SpannableStringBuilder builder = new SpannableStringBuilder(permDesc);
                builder.insert(0, mNewPermPrefix);
                if (groupDesc != null) {
                    // The previous permissions go in front, with a newline
                    // separating them.
                    builder.insert(0, "\n");
                    builder.insert(0, groupDesc);
                }
                return builder;
            }
        }
        if (groupDesc == null) {
            return permDesc;
        }
        // groupDesc and permDesc are non null
        return String.format(mPermFormat, groupDesc, permDesc.toString());
    }

    private CharSequence getGroupLabel(String grpName) {
        if (grpName == null) {
            //return default label
            return mDefaultGrpLabel;
        }
        CharSequence cachedLabel = mGroupLabelCache.get(grpName);
        if (cachedLabel != null) {
            return cachedLabel;
        }
        PermissionGroupInfo pgi;
        try {
            pgi = mPm.getPermissionGroupInfo(grpName, 0);
        } catch (NameNotFoundException e) {
            Log.i(TAG, "Invalid group name:" + grpName);
            return null;
        }
        CharSequence label = pgi.loadLabel(mPm).toString();
        mGroupLabelCache.put(grpName, label);
        return label;
    }

    /**
     * Utility method that displays permissions from a map containing group name and
     * list of permission descriptions.
     */
    private void displayPermissions(Map<String, CharSequence> permInfoMap,
            LinearLayout permListView, boolean dangerous) {
        permListView.removeAllViews();

        Set<String> permInfoStrSet = permInfoMap.keySet();
        for (String loopPermGrpInfoStr : permInfoStrSet) {
            CharSequence grpLabel = getGroupLabel(loopPermGrpInfoStr);
            //guaranteed that grpLabel wont be null since permissions without groups
            //will belong to the default group
            if(localLOGV) Log.i(TAG, "Adding view group:" + grpLabel + ", desc:"
                    + permInfoMap.get(loopPermGrpInfoStr));
            permListView.addView(getPermissionItemView(grpLabel,
                    permInfoMap.get(loopPermGrpInfoStr), dangerous));
        }
    }

    private void displayNoPermissions() {
        mNoPermsView.setVisibility(View.VISIBLE);
    }

    private View getPermissionItemView(CharSequence grpName, CharSequence permList,
            boolean dangerous) {
        return getPermissionItemView(mContext, mInflater, grpName, permList,
                dangerous, dangerous ? mDangerousIcon : mNormalIcon);
    }

    private static View getPermissionItemView(Context context, LayoutInflater inflater,
            CharSequence grpName, CharSequence permList, boolean dangerous, Drawable icon) {
        View permView = inflater.inflate(R.layout.app_permission_item, null);

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

    private void showPermissions() {

        switch(mCurrentState) {
        case NO_PERMS:
            displayNoPermissions();
            break;

        case DANGEROUS_ONLY:
            displayPermissions(mNewMap, mNewList, true);
            displayPermissions(mDangerousMap, mDangerousList, true);
            break;

        case NORMAL_ONLY:
            displayPermissions(mNewMap, mNewList, true);
            displayPermissions(mNormalMap, mNonDangerousList, false);
            break;

        case BOTH:
            displayPermissions(mNewMap, mNewList, true);
            displayPermissions(mDangerousMap, mDangerousList, true);
            if (mExpanded) {
                displayPermissions(mNormalMap, mNonDangerousList, false);
                mShowMoreIcon.setImageDrawable(mShowMaxIcon);
                mShowMoreText.setText(R.string.perms_hide);
                mNonDangerousList.setVisibility(View.VISIBLE);
            } else {
                mShowMoreIcon.setImageDrawable(mShowMinIcon);
                mShowMoreText.setText(R.string.perms_show_all);
                mNonDangerousList.setVisibility(View.GONE);
            }
            mShowMore.setVisibility(View.VISIBLE);
            break;
        }
    }

    private boolean isDisplayablePermission(PermissionInfo pInfo, int newReqFlags,
            int existingReqFlags) {
        final int base = pInfo.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
        // Dangerous and normal permissions are always shown to the user.
        if (base == PermissionInfo.PROTECTION_DANGEROUS ||
                base == PermissionInfo.PROTECTION_NORMAL) {
            return true;
        }
        // Development permissions are only shown to the user if they are already
        // granted to the app -- if we are installing an app and they are not
        // already granted, they will not be granted as part of the install.
        if ((existingReqFlags&PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                && (pInfo.protectionLevel & PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0) {
            return true;
        }
        return false;
    }

    /*
     * Utility method that aggregates all permission descriptions categorized by group
     * Say group1 has perm11, perm12, perm13, the group description will be
     * perm11_Desc, perm12_Desc, perm13_Desc
     */
    private void aggregateGroupDescs(Map<String, List<MyPermissionInfo> > map,
            Map<String, CharSequence> retMap, boolean newPerms) {
        if(map == null) {
            return;
        }
        if(retMap == null) {
           return;
        }
        Set<String> grpNames = map.keySet();
        Iterator<String> grpNamesIter = grpNames.iterator();
        while(grpNamesIter.hasNext()) {
            CharSequence grpDesc = null;
            String grpNameKey = grpNamesIter.next();
            List<MyPermissionInfo> grpPermsList = map.get(grpNameKey);
            if(grpPermsList == null) {
                continue;
            }
            for(PermissionInfo permInfo: grpPermsList) {
                CharSequence permDesc = permInfo.loadLabel(mPm);
                grpDesc = formatPermissions(grpDesc, permDesc, newPerms);
            }
            // Insert grpDesc into map
            if(grpDesc != null) {
                if(localLOGV) Log.i(TAG, "Group:"+grpNameKey+" description:"+grpDesc.toString());
                retMap.put(grpNameKey, grpDesc);
            }
        }
    }
    
    private static class PermissionInfoComparator implements Comparator<PermissionInfo> {
        private PackageManager mPm;
        private final Collator sCollator = Collator.getInstance();
        PermissionInfoComparator(PackageManager pm) {
            mPm = pm;
        }
        public final int compare(PermissionInfo a, PermissionInfo b) {
            CharSequence sa = a.loadLabel(mPm);
            CharSequence sb = b.loadLabel(mPm);
            return sCollator.compare(sa, sb);
        }
    }
    
    private void setPermissions(List<MyPermissionInfo> permList) {
        mGroupLabelCache = new HashMap<String, CharSequence>();
        //add the default label so that uncategorized permissions can go here
        mGroupLabelCache.put(mDefaultGrpName, mDefaultGrpLabel);
        
        // Map containing group names and a list of permissions under that group
        // that are new from the current install
        mNewMap = new HashMap<String, CharSequence>();
        // Map containing group names and a list of permissions under that group
        // categorized as dangerous
        mDangerousMap = new HashMap<String, CharSequence>();
        // Map containing group names and a list of permissions under that group
        // categorized as normal
        mNormalMap = new HashMap<String, CharSequence>();
        
        // Additional structures needed to ensure that permissions are unique under 
        // each group
        Map<String, List<MyPermissionInfo>> newMap =
            new HashMap<String,  List<MyPermissionInfo>>();
        Map<String, List<MyPermissionInfo>> dangerousMap = 
            new HashMap<String,  List<MyPermissionInfo>>();
        Map<String, List<MyPermissionInfo> > normalMap = 
            new HashMap<String,  List<MyPermissionInfo>>();
        PermissionInfoComparator permComparator = new PermissionInfoComparator(mPm);
        
        if (permList != null) {
            // First pass to group permissions
            for (MyPermissionInfo pInfo : permList) {
                if(localLOGV) Log.i(TAG, "Processing permission:"+pInfo.name);
                if(!isDisplayablePermission(pInfo, pInfo.mNewReqFlags, pInfo.mExistingReqFlags)) {
                    if(localLOGV) Log.i(TAG, "Permission:"+pInfo.name+" is not displayable");
                    continue;
                }
                Map<String, List<MyPermissionInfo> > permInfoMap;
                if (pInfo.mNew) {
                    permInfoMap = newMap;
                } else if ((pInfo.protectionLevel&PermissionInfo.PROTECTION_MASK_BASE)
                            == PermissionInfo.PROTECTION_DANGEROUS) {
                    permInfoMap = dangerousMap;
                } else {
                    permInfoMap = normalMap;
                }
                String grpName = (pInfo.group == null) ? mDefaultGrpName : pInfo.group;
                if(localLOGV) Log.i(TAG, "Permission:"+pInfo.name+" belongs to group:"+grpName);
                List<MyPermissionInfo> grpPermsList = permInfoMap.get(grpName);
                if(grpPermsList == null) {
                    grpPermsList = new ArrayList<MyPermissionInfo>();
                    permInfoMap.put(grpName, grpPermsList);
                    grpPermsList.add(pInfo);
                } else {
                    int idx = Collections.binarySearch(grpPermsList, pInfo, permComparator);
                    if(localLOGV) Log.i(TAG, "idx="+idx+", list.size="+grpPermsList.size());
                    if (idx < 0) {
                        idx = -idx-1;
                        grpPermsList.add(idx, pInfo);
                    }
                }
            }
            // Second pass to actually form the descriptions
            // Look at dangerous permissions first
            aggregateGroupDescs(newMap, mNewMap, true);
            aggregateGroupDescs(dangerousMap, mDangerousMap, false);
            aggregateGroupDescs(normalMap, mNormalMap, false);
        }

        mCurrentState = State.NO_PERMS;
        if (mNewMap.size() > 0 || mDangerousMap.size() > 0) {
            mCurrentState = (mNormalMap.size() > 0) ? State.BOTH : State.DANGEROUS_ONLY;
        } else if(mNormalMap.size() > 0) {
            mCurrentState = State.NORMAL_ONLY;
        }
        if(localLOGV) Log.i(TAG, "mCurrentState=" + mCurrentState);
        showPermissions();
    }

    public void onClick(View v) {
        if(localLOGV) Log.i(TAG, "mExpanded="+mExpanded);
        mExpanded = !mExpanded;
        showPermissions();
    }
}
