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

import static com.android.documentsui.Shared.DEBUG;
import static com.android.documentsui.dirlist.DirectoryFragment.ANIM_NONE;
import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkState;

import android.app.Activity;
import android.app.FragmentManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.DocumentsContract;
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
import com.android.documentsui.dirlist.DirectoryFragment;
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

        final Intent intent = getIntent();
        final Uri uri = intent.getData();

        if (mState.restored) {
            if (DEBUG) Log.d(TAG, "Stack already resolved for uri: " + intent.getData());
            onCurrentDirectoryChanged(ANIM_NONE);
        } else if (!mState.stack.isEmpty()) {
            // If a non-empty stack is present in our state it was read (presumably)
            // from EXTRA_STACK intent extra. In this case, we'll skip other means of
            // loading or restoring the stack.
            //
            // When restoring from a stack, if a URI is present, it should only ever
            // be a launch URI. Launch URIs support sensible activity management, but
            // don't specify a real content target.
            if (DEBUG) Log.d(TAG, "Launching with non-empty stack.");
            checkState(uri == null || LauncherActivity.isLaunchUri(uri));
            onCurrentDirectoryChanged(ANIM_NONE);
        } else if (DocumentsContract.isRootUri(this, uri)) {
            if (DEBUG) Log.d(TAG, "Launching with root URI.");
            // If we've got a specific root to display, restore that root using a dedicated
            // authority. That way a misbehaving provider won't result in an ANR.
            new RestoreRootTask(uri).executeOnExecutor(
                    ProviderExecutor.forAuthority(uri.getAuthority()));
        } else {
            if (DEBUG) Log.d(TAG, "Launching into Home directory.");
            // If all else fails, try to load "Home" directory.
            final Uri homeUri = DocumentsContract.buildHomeUri();
            new RestoreRootTask(homeUri).executeOnExecutor(
                    ProviderExecutor.forAuthority(homeUri.getAuthority()));
        }

        final int failure = intent.getIntExtra(CopyService.EXTRA_FAILURE, 0);
        final int transferMode = intent.getIntExtra(CopyService.EXTRA_TRANSFER_MODE,
                CopyService.TRANSFER_MODE_COPY);
        // DialogFragment takes care of restoring the dialog on configuration change.
        // Only show it manually for the first time (icicle is null).
        if (icicle == null && failure != 0) {
            final ArrayList<DocumentInfo> failedSrcList =
                    intent.getParcelableArrayListExtra(CopyService.EXTRA_SRC_LIST);
            FailureDialogFragment.show(
                    getFragmentManager(),
                    failure,
                    failedSrcList,
                    mState.stack,
                    transferMode);
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

        final DocumentStack stack = intent.getParcelableExtra(Shared.EXTRA_STACK);
        if (stack != null) {
            state.stack = stack;
        }

        return state;
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        // This check avoids a flicker from "Recents" to "Home".
        // Only update action bar at this point if there is an active
        // serach. Why? Because this avoid an early (undesired) load of
        // the recents root...which is the default root in other activities.
        // In Files app "Home" is the default, but it is loaded async.
        // updateActionBar will be called once Home root is loaded.
        // Except while searching we need this call to ensure the
        // search bits get layed out correctly.
        if (mSearchManager.isSearching()) {
            updateActionBar();
        }
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
        super.onPrepareOptionsMenu(menu);

        final MenuItem createDir = menu.findItem(R.id.menu_create_dir);
        final MenuItem newWindow = menu.findItem(R.id.menu_new_window);
        final MenuItem pasteFromCb = menu.findItem(R.id.menu_paste_from_clipboard);

        createDir.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        createDir.setVisible(true);
        createDir.setEnabled(canCreateDirectory());

        pasteFromCb.setEnabled(mClipper.hasItemsToPaste());

        newWindow.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        newWindow.setVisible(mProductivityDevice);

        Menus.disableHiddenItems(menu, pasteFromCb);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_create_dir:
                checkState(canCreateDirectory());
                showCreateDirectoryDialog();
                return true;
            case R.id.menu_new_window:
                createNewWindow();
                return true;
            case R.id.menu_paste_from_clipboard:
                DirectoryFragment dir = DirectoryFragment.get(getFragmentManager());
                dir = DirectoryFragment.get(getFragmentManager());
                dir.pasteFromClipboard();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void createNewWindow() {
        Intent intent = LauncherActivity.createLaunchIntent(this);
        intent.putExtra(Shared.EXTRA_STACK, (Parcelable) mState.stack);
        startActivity(intent);
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
    public void onDocumentPicked(DocumentInfo doc, @Nullable SiblingProvider siblings) {
        if (doc.isContainer()) {
            openContainerDocument(doc);
        } else {
            openDocument(doc, siblings);
        }
    }

    /**
     * Launches an intent to view the specified document.
     */
    private void openDocument(DocumentInfo doc, @Nullable SiblingProvider siblings) {
        Intent intent = null;
        if (siblings != null) {
            QuickViewIntentBuilder builder = new QuickViewIntentBuilder(
                    getPackageManager(), getResources(), doc, siblings);
            intent = builder.build();
        }

        if (intent != null) {
            // TODO: un-work around issue b/24963914. Should be fixed soon.
            try {
                startActivity(intent);
                return;
            } catch (SecurityException e) {
                // Carry on to regular view mode.
                Log.e(TAG, "Caught security error: " + e.getLocalizedMessage());
            }
        }

        // Fallback to traditional VIEW action...
        intent = new Intent(Intent.ACTION_VIEW);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setData(doc.derivedUri);

        if (DEBUG && intent.getClipData() != null) {
            Log.d(TAG, "Starting intent w/ clip data: " + intent.getClipData());
        }

        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Snackbars.makeSnackbar(
                    this, R.string.toast_no_application, Snackbar.LENGTH_SHORT).show();
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
            case KeyEvent.KEYCODE_C:
                // TODO: Should be statically bound using alphabeticShortcut. See b/21330356.
                dir = DirectoryFragment.get(getFragmentManager());
                dir.copySelectedToClipboard();
                return true;
            case KeyEvent.KEYCODE_V:
                // TODO: Should be statically bound using alphabeticShortcut. See b/21330356.
                dir = DirectoryFragment.get(getFragmentManager());
                dir.pasteFromClipboard();
                return true;
            default:
                return super.onKeyShortcut(keyCode, event);
        }
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
