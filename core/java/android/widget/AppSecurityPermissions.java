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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
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

    private final String TAG = "AppSecurityPermissions";
    private boolean localLOGV = false;
    private Context mContext;
    private LayoutInflater mInflater;
    private PackageManager mPm;
    private LinearLayout mPermsView;
    private HashMap<String, String> mDangerousMap;
    private HashMap<String, String> mNormalMap;
    private ArrayList<PermissionInfo> mPermsList;
    private String mDefaultGrpLabel;
    private String mDefaultGrpName="DefaultGrp";
    private String mPermFormat;
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
    private HashMap<String, String> mGroupLabelCache;
    private View mNoPermsView;

    public AppSecurityPermissions(Context context) {
        this(context, null);
    }

    public AppSecurityPermissions(Context context, ArrayList<PermissionInfo> permList) {
        mContext = context;
        mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mPm = context.getPackageManager();
        mPermsList = permList;
        mPermsView = (LinearLayout) mInflater.inflate(R.layout.app_perms_summary, null);
        mShowMore = mPermsView.findViewById(R.id.show_more);
        mShowMoreIcon = (ImageView) mShowMore.findViewById(R.id.show_more_icon);
        mShowMoreText = (TextView) mShowMore.findViewById(R.id.show_more_text);
        mDangerousList = (LinearLayout) mPermsView.findViewById(R.id.dangerous_perms_list);
        mNonDangerousList = (LinearLayout) mPermsView.findViewById(R.id.non_dangerous_perms_list);
        mNoPermsView = mPermsView.findViewById(R.id.no_permissions);

        // Set up the LinearLayout that acts like a list item.
        mShowMore.setClickable(true);
        mShowMore.setOnClickListener(this);
        mShowMore.setFocusable(true);
        mShowMore.setBackgroundResource(android.R.drawable.list_selector_background);

        // Pick up from framework resources instead.
        mDefaultGrpLabel = mContext.getString(R.string.default_permission_group);
        mPermFormat = mContext.getString(R.string.permissions_format);
        mNormalIcon = mContext.getResources().getDrawable(R.drawable.ic_text_dot);
        mDangerousIcon = mContext.getResources().getDrawable(R.drawable.ic_bullet_key_permission);
        mShowMaxIcon = mContext.getResources().getDrawable(R.drawable.expander_ic_maximized);
        mShowMinIcon = mContext.getResources().getDrawable(R.drawable.expander_ic_minimized);
    }

    public void setSecurityPermissionsView() {
        setPermissions(mPermsList);
    }

    public void setSecurityPermissionsView(Uri pkgURI) {
        final String archiveFilePath = pkgURI.getPath();
        PackageParser packageParser = new PackageParser(archiveFilePath);
        File sourceFile = new File(archiveFilePath);
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.setToDefaults();
        PackageParser.Package pkgInfo = packageParser.parsePackage(sourceFile,
                archiveFilePath, metrics, 0);
        mPermsList = generatePermissionsInfo(pkgInfo.requestedPermissions);
        //For packages that havent been installed we need the application info object
        //to load the labels and other resources.
        setPermissions(mPermsList, pkgInfo.applicationInfo);
    }

    public void setSecurityPermissionsView(PackageInfo pInfo) {
        mPermsList = generatePermissionsInfo(pInfo.requestedPermissions);
        setPermissions(mPermsList);
    }

    public View getPermissionsView() {
        return mPermsView;
    }

    /**
     * Canonicalizes the group description before it is displayed to the user.
     *
     * TODO check for internationalization issues remove trailing '.' in str1
     */
    private String canonicalizeGroupDesc(String groupDesc) {
        if ((groupDesc == null) || (groupDesc.length() == 0)) {
            return null;
        }
        // Both str1 and str2 are non-null and are non-zero in size.
        int len = groupDesc.length();
        if(groupDesc.charAt(len-1) == '.') {
            groupDesc = groupDesc.substring(0, len-1);
        }
        return groupDesc;
    }

    /**
     * Utility method that concatenates two strings defined by mPermFormat.
     * a null value is returned if both str1 and str2 are null, if one of the strings
     * is null the other non null value is returned without formatting
     * this is to placate initial error checks
     */
    private String formatPermissions(String groupDesc, String permDesc) {
        if(groupDesc == null) {
            return permDesc;
        }
        groupDesc = canonicalizeGroupDesc(groupDesc);
        if(permDesc == null) {
            return groupDesc;
        }
        return String.format(mPermFormat, groupDesc, permDesc);
    }

    /**
     * Utility method that concatenates two strings defined by mPermFormat.
     */
    private String formatPermissions(String groupDesc, CharSequence permDesc) {
        groupDesc = canonicalizeGroupDesc(groupDesc);
        if(permDesc == null) {
            return groupDesc;
        }
        // Format only if str1 and str2 are not null.
        return formatPermissions(groupDesc, permDesc.toString());
    }

    private ArrayList<PermissionInfo> generatePermissionsInfo(String[] strList) {
        ArrayList<PermissionInfo> permInfoList = new ArrayList<PermissionInfo>();
        if(strList == null) {
            return permInfoList;
        }
        PermissionInfo tmpPermInfo = null;
        for(int i = 0; i < strList.length; i++) {
            try {
                tmpPermInfo = mPm.getPermissionInfo(strList[i], 0);
                permInfoList.add(tmpPermInfo);
            } catch (NameNotFoundException e) {
                Log.i(TAG, "Ignoring unknown permisison:"+strList[i]);
                continue;
            }
        }
        return permInfoList;
    }

    private ArrayList<PermissionInfo> generatePermissionsInfo(ArrayList<String> strList) {
        ArrayList<PermissionInfo> permInfoList = new ArrayList<PermissionInfo>();
        if(strList != null) {
            PermissionInfo tmpPermInfo = null;
            for(String permName:strList) {
                try {
                    tmpPermInfo = mPm.getPermissionInfo(permName, 0);
                    permInfoList.add(tmpPermInfo);
                } catch (NameNotFoundException e) {
                    Log.i(TAG, "Ignoring unknown permisison:"+permName);
                    continue;
                }
            }
        }
        return permInfoList;
    }

    private String getGroupLabel(String grpName) {
        if (grpName == null) {
            //return default label
            return mDefaultGrpLabel;
        }
        String cachedLabel = mGroupLabelCache.get(grpName);
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
        String label = pgi.loadLabel(mPm).toString();
        mGroupLabelCache.put(grpName, label);
        return label;
    }

    /**
     * Utility method that displays permissions from a map containing group name and
     * list of permission descriptions.
     */
    private void displayPermissions(boolean dangerous) {
        HashMap<String, String> permInfoMap = dangerous ? mDangerousMap : mNormalMap;
        LinearLayout permListView = dangerous ? mDangerousList : mNonDangerousList;
        permListView.removeAllViews();

        Set<String> permInfoStrSet = permInfoMap.keySet();
        for (String loopPermGrpInfoStr : permInfoStrSet) {
            String grpLabel = getGroupLabel(loopPermGrpInfoStr);
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

    private View getPermissionItemView(String grpName, String permList,
            boolean dangerous) {
        View permView = mInflater.inflate(R.layout.app_permission_item, null);
        Drawable icon = dangerous ? mDangerousIcon : mNormalIcon;
        int grpColor = dangerous ? R.color.perms_dangerous_grp_color :
            R.color.perms_normal_grp_color;
        int permColor = dangerous ? R.color.perms_dangerous_perm_color :
            R.color.perms_normal_perm_color;

        TextView permGrpView = (TextView) permView.findViewById(R.id.permission_group);
        TextView permDescView = (TextView) permView.findViewById(R.id.permission_list);
        permGrpView.setTextColor(mContext.getResources().getColor(grpColor));
        permDescView.setTextColor(mContext.getResources().getColor(permColor));

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
            displayPermissions(true);
            break;

        case NORMAL_ONLY:
            displayPermissions(false);
            break;

        case BOTH:
            displayPermissions(true);
            if (mExpanded) {
                displayPermissions(false);
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
    
    private boolean isDisplayablePermission(PermissionInfo pInfo) {
        if(pInfo.protectionLevel == PermissionInfo.PROTECTION_DANGEROUS ||
                pInfo.protectionLevel == PermissionInfo.PROTECTION_NORMAL) {
            return true;
        }
        return false;
    }

    private void setPermissions(ArrayList<PermissionInfo> permList) {
        setPermissions(permList, null);    
    }
    
    private void setPermissions(ArrayList<PermissionInfo> permList, ApplicationInfo appInfo) {
        mDangerousMap = new HashMap<String, String>();
        mNormalMap = new HashMap<String, String>();
        mGroupLabelCache = new HashMap<String, String>();
        //add the default label so that uncategorized permissions can go here
        mGroupLabelCache.put(mDefaultGrpName, mDefaultGrpLabel);
        if (permList != null) {
            for (PermissionInfo pInfo : permList) {
                if(!isDisplayablePermission(pInfo)) {
                    continue;
                }
                String grpName = (pInfo.group == null) ? mDefaultGrpName : pInfo.group;
                HashMap<String, String> permInfoMap =
                    (pInfo.protectionLevel == PermissionInfo.PROTECTION_DANGEROUS) ?
                            mDangerousMap : mNormalMap;
                // Check to make sure we have a label for the group
                if (getGroupLabel(grpName) == null) {
                    continue;
                }
                CharSequence permDesc = pInfo.loadLabel(mPm);
                String grpDesc = permInfoMap.get(grpName);
                permInfoMap.put(grpName, formatPermissions(grpDesc, permDesc));
                if(localLOGV) Log.i(TAG, pInfo.name + "    :  " + permDesc+"    :    " + grpName);
            }
        }

        mCurrentState = State.NO_PERMS;
        if(mDangerousMap.size() > 0) {
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
