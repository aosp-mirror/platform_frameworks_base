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

package com.android.systemui.car.navigationbar;

import android.annotation.Nullable;
import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.car.CarDeviceProvisionedController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Some CarNavigationButtons can be associated to a {@link RoleManager} role. When they are, it is
 * possible to have them display the icon of the default application (role holder) for the given
 * role.
 *
 * This class monitors the current role holders for each role type and updates the button icon for
 * this buttons with have this feature enabled.
 */
@Singleton
public class ButtonRoleHolderController {
    private static final String TAG = "ButtonRoleHolderController";

    private final Context mContext;
    private final PackageManager mPackageManager;
    private final RoleManager mRoleManager;
    private final CarDeviceProvisionedController mDeviceController;
    private final Map<String, CarNavigationButton> mButtonMap = new HashMap<>();
    private final OnRoleHoldersChangedListener mListener = this::onRoleChanged;
    private boolean mRegistered;

    @Inject
    public ButtonRoleHolderController(Context context, PackageManager packageManager,
            RoleManager roleManager, CarDeviceProvisionedController deviceController) {
        mContext = context;
        mPackageManager = packageManager;
        mRoleManager = roleManager;
        mDeviceController = deviceController;
    }

    /**
     * Iterate through a view looking for CarNavigationButton and add it to this controller if it
     * opted to be associated with a {@link RoleManager} role type.
     *
     * @param v the View that may contain CarFacetButtons
     */
    void addAllButtonsWithRoleName(View v) {
        if (v instanceof CarNavigationButton) {
            CarNavigationButton button = (CarNavigationButton) v;
            String roleName = button.getRoleName();
            if (roleName != null && button.isDefaultAppIconForRoleEnabled()) {
                addButtonWithRoleName(button, roleName);
            }
        } else if (v instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) v;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                addAllButtonsWithRoleName(viewGroup.getChildAt(i));
            }
        }
    }

    private void addButtonWithRoleName(CarNavigationButton button, String roleName) {
        mButtonMap.put(roleName, button);
        updateIcon(roleName);
        if (!mRegistered) {
            mRoleManager.addOnRoleHoldersChangedListenerAsUser(mContext.getMainExecutor(),
                    mListener, UserHandle.ALL);
            mRegistered = true;
        }
    }

    void removeAll() {
        mButtonMap.clear();
        if (mRegistered) {
            mRoleManager.removeOnRoleHoldersChangedListenerAsUser(mListener, UserHandle.ALL);
            mRegistered = false;
        }
    }

    @VisibleForTesting
    void onRoleChanged(String roleName, UserHandle user) {
        if (RoleManager.ROLE_ASSISTANT.equals(roleName)
                && user.getIdentifier() == mDeviceController.getCurrentUser()) {
            updateIcon(roleName);
        }
    }

    private void updateIcon(String roleName) {
        CarNavigationButton button = mButtonMap.get(roleName);
        if (button == null) {
            return;
        }
        List<String> holders = mRoleManager.getRoleHoldersAsUser(button.getRoleName(),
                UserHandle.of(mDeviceController.getCurrentUser()));
        if (holders == null || holders.isEmpty()) {
            button.setAppIcon(null);
        } else {
            button.setAppIcon(loadIcon(holders.get(0)));
        }
    }

    @Nullable
    private Drawable loadIcon(String packageName) {
        try {
            ApplicationInfo appInfo = mPackageManager.getApplicationInfo(packageName,
                    PackageManager.MATCH_ANY_USER);
            return appInfo.loadIcon(mPackageManager);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(ButtonRoleHolderController.TAG, "Package not found: " + packageName, e);
            return null;
        }
    }
}
