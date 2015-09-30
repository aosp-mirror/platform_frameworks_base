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

import static com.android.documentsui.DirectoryFragment.ANIM_NONE;
import static com.android.documentsui.State.ACTION_MANAGE;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.support.design.widget.Snackbar;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.BaseAdapter;
import android.widget.Spinner;
import android.widget.Toolbar;

import com.android.documentsui.RecentsProvider.ResumeColumns;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DurableUtils;
import com.android.documentsui.model.RootInfo;
import com.android.internal.util.Preconditions;

import java.util.Arrays;
import java.util.List;

public class ManageRootActivity extends BaseActivity {
    private static final int CODE_FORWARD = 42;
    private static final String TAG = "ManageRootsActivity";

    private Toolbar mToolbar;
    private Spinner mToolbarStack;

    private ItemSelectedListener mStackListener;
    private BaseAdapter mStackAdapter;

    public ManageRootActivity() {
        super(R.layout.manage_roots_activity, TAG);
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        final Context context = this;

        mDrawer = DrawerController.createDummy();

        mToolbar = (Toolbar) findViewById(R.id.toolbar);
        mToolbar.setTitleTextAppearance(context,
                android.R.style.TextAppearance_DeviceDefault_Widget_ActionBar_Title);

        mStackAdapter = new StackAdapter();
        mStackListener = new ItemSelectedListener();
        mToolbarStack = (Spinner) findViewById(R.id.stack);
        mToolbarStack.setOnItemSelectedListener(mStackListener);

        setActionBar(mToolbar);

        if (!mState.restored) {
            // In this case, we set the activity title in AsyncTask.onPostExecute(). To prevent
            // talkback from reading aloud the default title, we clear it here.
            setTitle("");
            final Uri rootUri = getIntent().getData();
            new RestoreRootTask(rootUri).executeOnExecutor(getExecutorForCurrentDirectory());
        } else {
            onCurrentDirectoryChanged(ANIM_NONE);
        }
    }

    @Override
    State buildState() {
        State state = buildDefaultState();

        state.action = ACTION_MANAGE;
        state.acceptMimes = new String[] { "*/*" };
        state.allowMultiple = true;
        state.showSize = true;
        state.excludedAuthorities = getExcludedAuthorities();

        return state;
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        updateActionBar();
    }

    @Override
    public void updateActionBar() {
        // No navigation in manage root mode.
        mToolbar.setNavigationIcon(null);
        mToolbar.setNavigationOnClickListener(null);

        if (mSearchManager.isExpanded()) {
            mToolbar.setTitle(null);
            mToolbarStack.setVisibility(View.GONE);
            mToolbarStack.setAdapter(null);
        } else {
            if (mState.stack.size() <= 1) {
                mToolbar.setTitle(getCurrentRoot().title);
                mToolbarStack.setVisibility(View.GONE);
                mToolbarStack.setAdapter(null);
            } else {
                mToolbar.setTitle(null);
                mToolbarStack.setVisibility(View.VISIBLE);
                mToolbarStack.setAdapter(mStackAdapter);

                mStackListener.mIgnoreNextNavigation = true;
                mToolbarStack.setSelection(mStackAdapter.getCount() - 1);
            }
        }
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        return true;
    }

    @Override
    void onDirectoryChanged(int anim) {
        final FragmentManager fm = getFragmentManager();
        final RootInfo root = getCurrentRoot();
        final DocumentInfo cwd = getCurrentDirectory();

        // If started in manage roots mode, there has to be a cwd (i.e. the root dir of the managed
        // root).
        Preconditions.checkNotNull(cwd);

        if (mState.currentSearch != null) {
            // Ongoing search
            DirectoryFragment.showSearch(fm, root, mState.currentSearch, anim);
        } else {
            // Normal boring directory
            DirectoryFragment.showNormal(fm, root, cwd, anim);
        }
    }

    @Override
    public void onDocumentPicked(DocumentInfo doc, DocumentContext context) {
        final FragmentManager fm = getFragmentManager();
        if (doc.isDirectory()) {
            openDirectory(doc);
        } else {
            // First try managing the document; we expect manager to filter
            // based on authority, so we don't grant.
            final Intent manage = new Intent(DocumentsContract.ACTION_MANAGE_DOCUMENT);
            manage.setData(doc.derivedUri);

            try {
                startActivity(manage);
            } catch (ActivityNotFoundException ex) {
                // Fall back to viewing
                final Intent view = new Intent(Intent.ACTION_VIEW);
                view.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                view.setData(doc.derivedUri);

                try {
                    startActivity(view);
                } catch (ActivityNotFoundException ex2) {
                    Shared.makeSnackbar(this, R.string.toast_no_application, Snackbar.LENGTH_SHORT)
                            .show();
                }
            }
        }
    }

    @Override
    public void onDocumentsPicked(List<DocumentInfo> docs) {}

    @Override
    void saveStackBlocking() {
        final ContentResolver resolver = getContentResolver();
        final ContentValues values = new ContentValues();

        final byte[] rawStack = DurableUtils.writeToArrayOrNull(mState.stack);

        // Remember location for next app launch
        final String packageName = getCallingPackageMaybeExtra();
        values.clear();
        values.put(ResumeColumns.STACK, rawStack);
        values.put(ResumeColumns.EXTERNAL, 0);
        resolver.insert(RecentsProvider.buildResume(packageName), values);
    }

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

    public static ManageRootActivity get(Fragment fragment) {
        return (ManageRootActivity) fragment.getActivity();
    }
}
