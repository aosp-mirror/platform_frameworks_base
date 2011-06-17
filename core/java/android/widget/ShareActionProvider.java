/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.widget;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.ActionProvider;
import android.view.MenuItem;
import android.view.View;

import com.android.internal.R;

/**
 * This is a provider for a share action. It is responsible for creating views
 * that enable data sharing and also to perform a default action for showing
 * a share dialog.
 * <p>
 * Here is how to use the action provider with custom backing file in a {@link MenuItem}:
 * </p>
 * <p>
 * <pre>
 * <code>
 *  // In Activity#onCreateOptionsMenu
 *  public boolean onCreateOptionsMenu(Menu menu) {
 *      // Get the menu item.
 *      MenuItem menuItem = menu.findItem(R.id.my_menu_item);
 *      // Get the provider and hold onto it to set/change the share intent.
 *      mShareActionProvider = (ShareActionProvider) menuItem.getActionProvider();
 *      // Set history different from the default before getting the action
 *      // view since a call to {@link MenuItem#getActionView() MenuItem.getActionView()} calls
 *      // {@link ActionProvider#onCreateActionView()} which uses the backing file name. Omit this
 *      // line if using the default share history file is desired.
 *      mShareActionProvider.setShareHistoryFileName("custom_share_history.xml");
 *      // Get the action view and hold onto it to set the share intent.
 *      mActionView = menuItem.getActionView();
 *      . . .
 *  }
 *
 *  // Somewhere in the application.
 *  public void doShare(Intent shareIntent) {
 *      // When you want to share set the share intent.
 *      mShareActionProvider.setShareIntent(mActionView, shareIntent);
 *  }
 * </pre>
 * </code>
 * </p>
 * <p>
 * <strong>Note:</strong> While the sample snippet demonstrates how to use this provider
 * in the context of a menu item, the use of the provider is not limited to menu items.
 * </p>
 *
 * @see ActionProvider
 */
public class ShareActionProvider extends ActionProvider {

    /**
     * The default name for storing share history.
     */
    public static final String DEFAULT_SHARE_HISTORY_FILE_NAME = "share_history.xml";

    private final Context mContext;
    private String mShareHistoryFileName = DEFAULT_SHARE_HISTORY_FILE_NAME;

    /**
     * Creates a new instance.
     *
     * @param context Context for accessing resources.
     */
    public ShareActionProvider(Context context) {
        super(context);
        mContext = context;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public View onCreateActionView() {
        ActivityChooserModel dataModel = ActivityChooserModel.get(mContext, mShareHistoryFileName);
        ActivityChooserView activityChooserView = new ActivityChooserView(mContext);
        activityChooserView.setActivityChooserModel(dataModel);
        TypedValue outTypedValue = new TypedValue();
        mContext.getTheme().resolveAttribute(R.attr.actionModeShareDrawable, outTypedValue, true);
        Drawable drawable = mContext.getResources().getDrawable(outTypedValue.resourceId);
        activityChooserView.setExpandActivityOverflowButtonDrawable(drawable);
        return activityChooserView;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onPerformDefaultAction(View actionView) {
        if (actionView instanceof ActivityChooserView) {
            ActivityChooserView activityChooserView = (ActivityChooserView) actionView;
            activityChooserView.showPopup();
        } else {
            throw new IllegalArgumentException("actionView not instance of ActivityChooserView");
        }
    }

    /**
     * Sets the file name of a file for persisting the share history which
     * history will be used for ordering share targets. This file will be used
     * for all view created by {@link #onCreateActionView()}. Defaults to
     * {@link #DEFAULT_SHARE_HISTORY_FILE_NAME}. Set to <code>null</code>
     * if share history should not be persisted between sessions.
     * <p>
     * <strong>Note:</strong> The history file name can be set any time, however
     * only the action views created by {@link #onCreateActionView()} after setting
     * the file name will be backed by the provided file.
     * <p>
     *
     * @param shareHistoryFile The share history file name.
     */
    public void setShareHistoryFileName(String shareHistoryFile) {
        mShareHistoryFileName = shareHistoryFile;
    }

    /**
     * Sets an intent with information about the share action. Here is a
     * sample for constructing a share intent:
     * <p>
     * <pre>
     * <code>
     *  Intent shareIntent = new Intent(Intent.ACTION_SEND);
     *  shareIntent.setType("image/*");
     *  Uri uri = Uri.fromFile(new File(getFilesDir(), "foo.jpg"));
     *  shareIntent.putExtra(Intent.EXTRA_STREAM, uri.toString());
     * </pre>
     * </code>
     * </p>
     *
     * @param actionView An action view created by {@link #onCreateActionView()}.
     * @param shareIntent The share intent.
     *
     * @see Intent#ACTION_SEND
     * @see Intent#ACTION_SEND_MULTIPLE
     */
    public void setShareIntent(View actionView, Intent shareIntent) {
        if (actionView instanceof ActivityChooserView) {
            ActivityChooserView activityChooserView = (ActivityChooserView) actionView;
            activityChooserView.getDataModel().setIntent(shareIntent);
        } else {
            throw new IllegalArgumentException("actionView not instance of ActivityChooserView");
        }
    }
}
