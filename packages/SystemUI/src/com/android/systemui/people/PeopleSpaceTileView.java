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

package com.android.systemui.people;

import android.app.people.PeopleSpaceTile;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.res.R;

/**
 * PeopleSpaceTileView renders an individual person's tile with associated status.
 */
public class PeopleSpaceTileView extends LinearLayout {

    private View mTileView;
    private TextView mNameView;
    private ImageView mPersonIconView;

    public PeopleSpaceTileView(Context context, ViewGroup view, String shortcutId, boolean isLast) {
        super(context);
        mTileView = view.findViewWithTag(shortcutId);
        if (mTileView == null) {
            LayoutInflater inflater = LayoutInflater.from(context);
            mTileView = inflater.inflate(R.layout.people_space_tile_view, view, false);
            view.addView(mTileView, LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT);
            mTileView.setTag(shortcutId);

            // If it's not the last conversation in this section, add a divider.
            if (!isLast) {
                inflater.inflate(R.layout.people_space_activity_list_divider, view, true);
            }
        }
        mNameView = mTileView.findViewById(R.id.tile_view_name);
        mPersonIconView = mTileView.findViewById(R.id.tile_view_person_icon);
    }

    /** Sets the name text on the tile. */
    public void setName(String name) {
        mNameView.setText(name);
    }

    /** Sets the person and package drawable on the tile. */
    public void setPersonIcon(Bitmap bitmap) {
        mPersonIconView.setImageBitmap(bitmap);
    }

    /** Sets the click listener of the tile. */
    public void setOnClickListener(LauncherApps launcherApps, PeopleSpaceTile tile) {
        mTileView.setOnClickListener(v ->
                launcherApps.startShortcut(tile.getPackageName(), tile.getId(), null, null,
                        tile.getUserHandle()));
    }

    /** Sets the click listener of the tile directly. */
    public void setOnClickListener(OnClickListener onClickListener) {
        mTileView.setOnClickListener(onClickListener);
    }
}
