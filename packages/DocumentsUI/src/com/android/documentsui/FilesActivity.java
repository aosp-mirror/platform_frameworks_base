/*
 * Copyright (C) 2015 The Android Open Source Project
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
import static com.android.documentsui.Shared.DEBUG;
import static com.android.internal.util.Preconditions.checkArgument;

import android.app.Activity;
import android.app.FragmentManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.BaseAdapter;
import android.widget.Spinner;
import android.widget.Toolbar;

import com.android.documentsui.RecentsProvider.ResumeColumns;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.model.DurableUtils;
import com.android.documentsui.model.RootInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Standalone file management activity.
 */
public class FilesActivity extends BaseActivity {

    public static final String TAG = "FilesActivity";

    private Toolbar mToolbar;
    private Spinner mToolbarStack;
    private ItemSelectedListener mStackListener;
    private BaseAdapter mStackAdapter;
    private DocumentClipper mClipper;

    public FilesActivity() {
        super(R.layout.files_activity, TAG);
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mToolbar = (Toolbar) findViewById(R.id.toolbar);

        mStackAdapter = new StackAdapter();
        mStackListener = new ItemSelectedListener();
        mToolbarStack = (Spinner) findViewById(R.id.stack);
        mToolbarStack.setOnItemSelectedListener(mStackListener);

        setActionBar(mToolbar);

        mClipper = new DocumentClipper(this);
        mDrawer = DrawerController.create(this);

        RootsFragment.show(getFragmentManager(), null);
        if (!mState.restored) {
            Uri rootUri = getIntent().getData();

            // If we've got a specific root to display, restore that root using a dedicated
            // authority. That way a misbehaving provider won't result in an ANR.
            if (rootUri != null) {
                new RestoreRootTask(rootUri).executeOnExecutor(
                        ProviderExecutor.forAuthority(rootUri.getAuthority()));
            } else {
                new RestoreStackTask().execute();
            }

            // Show a failure dialog if there was a failed operation.
            final Intent intent = getIntent();
            final DocumentStack dstStack = intent.getParcelableExtra(CopyService.EXTRA_STACK);
            final int failure = intent.getIntExtra(CopyService.EXTRA_FAILURE, 0);
            final int transferMode = intent.getIntExtra(CopyService.EXTRA_TRANSFER_MODE,
                    CopyService.TRANSFER_MODE_NONE);
            if (failure != 0) {
                final ArrayList<DocumentInfo> failedSrcList =
                        intent.getParcelableArrayListExtra(CopyService.EXTRA_SRC_LIST);
                FailureDialogFragment.show(getFragmentManager(), failure, failedSrcList, dstStack,
                        transferMode);
            }
        } else {
            onCurrentDirectoryChanged(ANIM_NONE);
        }
    }

    @Override
    State buildState() {
        State state = buildDefaultState();

        final Intent intent = getIntent();

        state.action = State.ACTION_BROWSE;
        state.allowMultiple = true;

        // Options specific to the DocumentsActivity.
        checkArgument(!intent.hasExtra(Intent.EXTRA_LOCAL_ONLY));

        final DocumentStack stack = intent.getParcelableExtra(CopyService.EXTRA_STACK);
        if (stack != null) {
            state.stack = stack;
        }

        return state;
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        updateActionBar();
    }

    @Override
    public void onResume() {
        super.onResume();

        final RootInfo root = getCurrentRoot();

        // If we're browsing a specific root, and that root went away, then we
        // have no reason to hang around.
        // TODO: Rather than just disappearing, maybe we should inform
        // the user what has happened, let them close us. Less surprising.
        if (mRoots.getRootBlocking(root.authority, root.rootId) == null) {
            finish();
        }
    }

    @Override
    public void updateActionBar() {
        final RootInfo root = getCurrentRoot();

        if (mDrawer.isPresent()) {
            mToolbar.setNavigationIcon(R.drawable.ic_hamburger);
            mToolbar.setNavigationContentDescription(R.string.drawer_open);
            mToolbar.setNavigationOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            mDrawer.setOpen(true);
                        }
                    });
        } else {
            mToolbar.setNavigationIcon(
                    root != null ? root.loadToolbarIcon(mToolbar.getContext()) : null);
            mToolbar.setNavigationContentDescription(R.string.drawer_open);
            mToolbar.setNavigationOnClickListener(null);
        }

        if (mSearchManager.isExpanded()) {
            mToolbar.setTitle(null);
            mToolbarStack.setVisibility(View.GONE);
            mToolbarStack.setAdapter(null);
        } else {
            if (mState.stack.size() <= 1) {
                mToolbar.setTitle(root.title);
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
    public boolean onCreateOptionsMenu(Menu menu) {
        boolean showMenu = super.onCreateOptionsMenu(menu);

        expandMenus(menu);
        return showMenu;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean shown = super.onPrepareOptionsMenu(menu);

        final MenuItem pasteFromCb = menu.findItem(R.id.menu_paste_from_clipboard);
        final MenuItem createDir = menu.findItem(R.id.menu_create_dir);

        boolean canCreateDir = canCreateDirectory();

        createDir.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        createDir.setVisible(canCreateDir);

        pasteFromCb.setVisible(true);
        pasteFromCb.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        pasteFromCb.setEnabled(mClipper.hasItemsToPaste());

        return shown;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int id = item.getItemId();
        if (id == R.id.menu_paste_from_clipboard) {
            DirectoryFragment dir = DirectoryFragment.get(getFragmentManager());
            dir = DirectoryFragment.get(getFragmentManager());
            dir.pasteFromClipboard();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    void onDirectoryChanged(int anim) {
        final FragmentManager fm = getFragmentManager();
        final RootInfo root = getCurrentRoot();
        final DocumentInfo cwd = getCurrentDirectory();

        if (cwd == null) {
            DirectoryFragment.showRecentsOpen(fm, anim);

            // Start recents in grid when requesting visual things
            final boolean visualMimes = MimePredicate.mimeMatches(
                    MimePredicate.VISUAL_MIMES, mState.acceptMimes);
            mState.userMode = visualMimes ? State.MODE_GRID : State.MODE_LIST;
            mState.derivedMode = mState.userMode;
        } else {
            if (mState.currentSearch != null) {
                // Ongoing search
                DirectoryFragment.showSearch(fm, root, mState.currentSearch, anim);
            } else {
                // Normal boring directory
                DirectoryFragment.showNormal(fm, root, cwd, anim);
            }
        }
    }

    @Override
    void onRootPicked(RootInfo root) {
        super.onRootPicked(root);
        mDrawer.setOpen(false);
    }

    @Override
    public void onDocumentsPicked(List<DocumentInfo> docs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void onDocumentPicked(DocumentInfo doc, @Nullable DocumentContext siblings) {
        if (doc.isDirectory()) {
            openDirectory(doc);
        } else {
            openDocument(doc, siblings);
        }
    }

    /**
     * Launches an intent to view the specified document.
     */
    private void openDocument(DocumentInfo doc, @Nullable DocumentContext siblings) {
        Intent intent = null;
        if (siblings != null) {
            QuickViewIntentBuilder builder =
                    new QuickViewIntentBuilder(getPackageManager(), doc, siblings);
            intent = builder.build();
        }

        // fallback to traditional VIEW action...
        if (intent == null) {
            intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setData(doc.derivedUri);
        }

        if (DEBUG && intent.getClipData() != null) {
            Log.d(TAG, "Starting intent w/ clip data: " + intent.getClipData());
        }

        try {
            startActivity(intent);
        } catch (ActivityNotFoundException ex2) {
            Shared.makeSnackbar(this, R.string.toast_no_application, Snackbar.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onKeyShortcut(int keyCode, KeyEvent event) {
        DirectoryFragment dir;
        switch (keyCode) {
            case KeyEvent.KEYCODE_A:
                dir = DirectoryFragment.get(getFragmentManager());
                dir.selectAllFiles();
                return true;
            case KeyEvent.KEYCODE_N:
                if (event.isShiftPressed() && canCreateDirectory()) {
                    showCreateDirectoryDialog();
                    return true;
                }
            case KeyEvent.KEYCODE_C:
                // TODO: Should be statically bound using alphabeticShortcut. See b/21330356.
                dir = DirectoryFragment.get(getFragmentManager());
                dir.copySelectedToClipboard();
                // TODO: Cancel action mode in directory fragment.
        }

        return super.onKeyUp(keyCode, event);
    }

    @Override
    void saveStackBlocking() {
        final ContentResolver resolver = getContentResolver();
        final ContentValues values = new ContentValues();

        final byte[] rawStack = DurableUtils.writeToArrayOrNull(
                getDisplayState().stack);

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
}
