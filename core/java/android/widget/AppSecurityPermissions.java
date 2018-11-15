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

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;

import com.android.internal.R;

/**
 * Allows the device admin to show certain dialogs. Should be integrated into settings.
 *
 * @deprecated
 * {@hide}
 */
@Deprecated
public class AppSecurityPermissions {

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

    private static View getPermissionItemViewOld(Context context, LayoutInflater inflater,
            CharSequence grpName, CharSequence permList, boolean dangerous, Drawable icon) {
        View permView = inflater.inflate(R.layout.app_permission_item_old, null);

        TextView permGrpView = permView.findViewById(R.id.permission_group);
        TextView permDescView = permView.findViewById(R.id.permission_list);

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
}
