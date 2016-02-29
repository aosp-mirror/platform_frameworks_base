/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.documentsui;

import static com.android.documentsui.State.ACTION_MANAGE;
import static com.android.documentsui.dirlist.DirectoryFragment.ANIM_NONE;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.support.design.widget.Snackbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toolbar;

import com.android.documentsui.dirlist.DirectoryFragment;
import com.android.documentsui.dirlist.Model;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.RootInfo;

import java.util.Arrays;
import java.util.List;

// Let's face it. MANAGE_ROOT is used almost exclusively
// for downloads, and is specialized for this purpose.
// So it is now thusly christened.
public class DownloadsActivity extends BaseActivity {
    private static final String TAG = "DownloadsActivity";

    public DownloadsActivity() {
        super(R.layout.downloads_activity, TAG);
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        final Context context = this;

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        toolbar.setTitleTextAppearance(context,
                android.R.style.TextAppearance_DeviceDefault_Widget_ActionBar_Title);

        if (!mState.restored) {
            // In this case, we set the activity title in AsyncTask.onPostExecute(). To prevent
            // talkback from reading aloud the default title, we clear it here.
            setTitle("");
            final Uri rootUri = getIntent().getData();
            new LoadRootTask(this, rootUri).executeOnExecutor(getExecutorForCurrentDirectory());
        } else {
            refreshCurrentRootAndDirectory(ANIM_NONE);
        }
    }

    @Override
    void includeState(State state) {
        state.action = ACTION_MANAGE;
        state.acceptMimes = new String[] { "*/*" };
        state.allowMultiple = true;
        state.showSize = true;
        state.excludedAuthorities = getExcludedAuthorities();
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        mNavigator.update();
    }

    @Override
    public String getDrawerTitle() {
        return null;  // being and nothingness
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        final MenuItem advanced = menu.findItem(R.id.menu_advanced);
        final MenuItem createDir = menu.findItem(R.id.menu_create_dir);
        final MenuItem pasteFromCb = menu.findItem(R.id.menu_paste_from_clipboard);
        final MenuItem fileSize = menu.findItem(R.id.menu_file_size);

        advanced.setVisible(false);
        createDir.setVisible(false);
        pasteFromCb.setEnabled(false);
        fileSize.setVisible(false);

        Menus.disableHiddenItems(menu);
        return true;
    }

    @Override
    void refreshDirectory(int anim) {
        final FragmentManager fm = getFragmentManager();
        final RootInfo root = getCurrentRoot();
        final DocumentInfo cwd = getCurrentDirectory();

        assert(!mSearchManager.isSearching());

        // If started in manage roots mode, there has to be a cwd (i.e. the root dir of the managed
        // root).
        assert(cwd != null);

        // Normal boring directory
        DirectoryFragment.showDirectory(fm, root, cwd, anim);
    }

    @Override
    public void onDocumentPicked(DocumentInfo doc, Model model) {
        assert(!doc.isDirectory());

        // First try managing the document; we expect manager to filter
        // based on authority, so we don't grant.
        final Intent manage = new Intent(DocumentsContract.ACTION_MANAGE_DOCUMENT);
        manage.setData(doc.derivedUri);

        try {
            startActivity(manage);
        } catch (ActivityNotFoundException ex) {
            // Fall back to viewing.
            final Intent view = new Intent(Intent.ACTION_VIEW);
            view.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            view.setData(doc.derivedUri);

            try {
                startActivity(view);
            } catch (ActivityNotFoundException ex2) {
                Snackbars.makeSnackbar(this, R.string.toast_no_application, Snackbar.LENGTH_SHORT)
                        .show();
            }
        }
    }

    @Override
    public void onDocumentsPicked(List<DocumentInfo> docs) {}

    @Override
    void onTaskFinished(Uri... uris) {
        Log.d(TAG, "onFinished() " + Arrays.toString(uris));

        final Intent intent = new Intent();
        if (uris.length == 1) {
            intent.setData(uris[0]);
        } else if (uris.length > 1) {
            final ClipData clipData = new ClipData(
                    null, mState.acceptMimes, new ClipData.Item(uris[0]));
            for (int i = 1; i < uris.length; i++) {
                clipData.addItem(new ClipData.Item(uris[i]));
            }
            intent.setClipData(clipData);
        }

        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        setResult(Activity.RESULT_OK, intent);
        finish();
    }

    public static DownloadsActivity get(Fragment fragment) {
        return (DownloadsActivity) fragment.getActivity();
    }
}
