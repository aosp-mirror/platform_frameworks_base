/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.app;

import com.android.internal.R;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;

import java.util.List;

/**
 * Utilities for setting up the search source selector.
 *
 * This class has two copies:
 * android.app.SearchSourceSelector
 * com.android.quicksearchbox.ui.SearchSourceSelector
 *
 * They should keep the same look and feel as much as possible,
 * but only the intent details must absolutely stay in sync.
 *
 * @hide
 */
public class SearchSourceSelector implements View.OnClickListener {

    private static final String TAG = "SearchSourceSelector";

    // TODO: This should be defined in android.provider.Applications,
    // and have a less made-up value.
    private static final String APPLICATION_TYPE = "application/vnd.android.application";

    public static final int ICON_VIEW_ID = R.id.search_source_selector_icon;

    private final View mView;

    private final ImageButton mIconView;

    private ComponentName mSource;

    private Bundle mAppSearchData;

    private String mQuery;

    public SearchSourceSelector(View view) {
        mView = view;
        mIconView = (ImageButton) view.findViewById(ICON_VIEW_ID);
        mIconView.setOnClickListener(this);
    }

    /**
     * Sets the icon displayed in the search source selector.
     */
    public void setSourceIcon(Drawable icon) {
        mIconView.setImageDrawable(icon);
    }

    /**
     * Sets the current search source.
     */
    public void setSource(ComponentName source) {
        mSource = source;
    }

    /**
     * Sets the app-specific data that will be passed to the search activity if
     * the user opens the source selector and chooses a source.
     */
    public void setAppSearchData(Bundle appSearchData) {
        mAppSearchData = appSearchData;
    }

     /**
      * Sets the initial query that will be passed to the search activity if
      * the user opens the source selector and chooses a source.
      */
    public void setQuery(String query) {
        mQuery = query;
    }

    public void setVisibility(int visibility) {
        mView.setVisibility(visibility);
    }

    /**
     * Creates an intent for opening the search source selector activity.
     *
     * @param source The current search source.
     * @param query The initial query that will be passed to the search activity if
     *        the user opens the source selector and chooses a source.
     * @param appSearchData The app-specific data that will be passed to the search
     *        activity if the user opens the source selector and chooses a source.
     */
    public static Intent createIntent(ComponentName source, String query, Bundle appSearchData) {
        Intent intent = new Intent(SearchManager.INTENT_ACTION_SELECT_SEARCH_SOURCE);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        Uri sourceUri = componentNameToUri(source);
        if (sourceUri != null) {
            intent.setDataAndType(sourceUri, APPLICATION_TYPE);
        }
        if (query != null) {
            intent.putExtra(SearchManager.QUERY, query);
        }
        if (query != null) {
            intent.putExtra(SearchManager.APP_DATA, appSearchData);
        }
        return intent;
    }

    /**
     * Gets the search source from which the given
     * {@link SearchManager.INTENT_ACTION_SELECT_SEARCH_SOURCE} intent was sent.
     */
    public static ComponentName getSource(Intent intent) {
        return uriToComponentName(intent.getData());
    }

    private static Uri componentNameToUri(ComponentName name) {
        if (name == null) return null;
        // TODO: This URI format is specificed in android.provider.Applications which is @hidden
        return new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority("applications")
                .appendEncodedPath("applications")
                .appendPath(name.getPackageName())
                .appendPath(name.getClassName())
                .build();
    }

    private static ComponentName uriToComponentName(Uri uri) {
        if (uri == null) return null;
        List<String> path = uri.getPathSegments();
        if (path == null || path.size() != 3) return null;
        String pkg = path.get(1);
        String cls = path.get(2);
        if (TextUtils.isEmpty(pkg) || TextUtils.isEmpty(cls)) return null;
        return new ComponentName(pkg, cls);
    }

    public void onClick(View v) {
        trigger();
    }

    private void trigger() {
        try {
            Intent intent = createIntent(mSource, mQuery, mAppSearchData);
            intent.setSourceBounds(getOnScreenRect(mIconView));
            mIconView.getContext().startActivity(intent);
        } catch (ActivityNotFoundException ex) {
            Log.e(TAG, "No source selector activity found", ex);
        }
    }

    // TODO: This code is replicated in lots of places:
    // - android.provider.ContactsContract.QuickContact.showQuickContact()
    // - android.widget.RemoteViews.setOnClickPendingIntent()
    // - com.android.launcher2.Launcher.onClick()
    // - com.android.launcher.Launcher.onClick()
    // - com.android.server.status.StatusBarService.Launcher.onClick()
    private static Rect getOnScreenRect(View v) {
        final float appScale = v.getResources().getCompatibilityInfo().applicationScale;
        final int[] pos = new int[2];
        v.getLocationOnScreen(pos);
        final Rect rect = new Rect();
        rect.left = (int) (pos[0] * appScale + 0.5f);
        rect.top = (int) (pos[1] * appScale + 0.5f);
        rect.right = (int) ((pos[0] + v.getWidth()) * appScale + 0.5f);
        rect.bottom = (int) ((pos[1] + v.getHeight()) * appScale + 0.5f);
        return rect;
    }

}
