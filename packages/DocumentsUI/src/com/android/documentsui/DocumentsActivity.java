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

import static com.android.documentsui.BaseActivity.State.ACTION_BROWSE;
import static com.android.documentsui.BaseActivity.State.ACTION_CREATE;
import static com.android.documentsui.BaseActivity.State.ACTION_GET_CONTENT;
import static com.android.documentsui.BaseActivity.State.ACTION_MANAGE;
import static com.android.documentsui.BaseActivity.State.ACTION_OPEN;
import static com.android.documentsui.BaseActivity.State.ACTION_OPEN_COPY_DESTINATION;
import static com.android.documentsui.BaseActivity.State.ACTION_OPEN_TREE;
import static com.android.documentsui.DirectoryFragment.ANIM_DOWN;
import static com.android.documentsui.DirectoryFragment.ANIM_NONE;
import static com.android.documentsui.DirectoryFragment.ANIM_UP;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import android.app.ActionBar;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Point;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Root;
import android.support.v4.app.ActionBarDrawerToggle;
import android.support.v4.widget.DrawerLayout;
import android.support.v4.widget.DrawerLayout.DrawerListener;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Spinner;
import android.widget.Toast;
import android.widget.Toolbar;

import com.android.documentsui.RecentsProvider.RecentColumns;
import com.android.documentsui.RecentsProvider.ResumeColumns;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.model.DurableUtils;
import com.android.documentsui.model.RootInfo;

public class DocumentsActivity extends BaseActivity {
    private static final int CODE_FORWARD = 42;
    public static final String TAG = "Documents";

    private boolean mShowAsDialog;

    private Toolbar mToolbar;
    private Spinner mToolbarStack;

    private Toolbar mRootsToolbar;

    private DrawerLayout mDrawerLayout;
    private ActionBarDrawerToggle mDrawerToggle;
    private View mRootsDrawer;

    private DirectoryContainerView mDirectoryContainer;

    private State mState;

    private ItemSelectedListener mStackListener;
    private BaseAdapter mStackAdapter;

    public DocumentsActivity() {
        super(TAG);
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        setResult(Activity.RESULT_CANCELED);
        setContentView(R.layout.activity);

        final Context context = this;
        final Resources res = getResources();
        mShowAsDialog = res.getBoolean(R.bool.show_as_dialog);

        if (mShowAsDialog) {
            // Strongly define our horizontal dimension; we leave vertical as
            // WRAP_CONTENT so that system resizes us when IME is showing.
            final WindowManager.LayoutParams a = getWindow().getAttributes();

            final Point size = new Point();
            getWindowManager().getDefaultDisplay().getSize(size);
            a.width = (int) res.getFraction(R.dimen.dialog_width, size.x, size.x);

            getWindow().setAttributes(a);

        } else {
            // Non-dialog means we have a drawer
            mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);

            mDrawerToggle = new ActionBarDrawerToggle(this, mDrawerLayout,
                    R.drawable.ic_hamburger, R.string.drawer_open, R.string.drawer_close);

            mDrawerLayout.setDrawerListener(mDrawerListener);

            mRootsDrawer = findViewById(R.id.drawer_roots);
        }

        mDirectoryContainer = (DirectoryContainerView) findViewById(R.id.container_directory);

        mState = (icicle != null)
                ? icicle.<State>getParcelable(EXTRA_STATE)
                : buildDefaultState();

        mToolbar = (Toolbar) findViewById(R.id.toolbar);
        mToolbar.setTitleTextAppearance(context,
                android.R.style.TextAppearance_DeviceDefault_Widget_ActionBar_Title);

        mStackAdapter = new StackAdapter();
        mStackListener = new ItemSelectedListener();
        mToolbarStack = (Spinner) findViewById(R.id.stack);
        mToolbarStack.setOnItemSelectedListener(mStackListener);

        mRootsToolbar = (Toolbar) findViewById(R.id.roots_toolbar);
        if (mRootsToolbar != null) {
            mRootsToolbar.setTitleTextAppearance(context,
                    android.R.style.TextAppearance_DeviceDefault_Widget_ActionBar_Title);
        }

        setActionBar(mToolbar);

        // Hide roots when we're managing a specific root
        if (mState.action == ACTION_MANAGE || mState.action == ACTION_BROWSE) {
            if (mShowAsDialog) {
                findViewById(R.id.container_roots).setVisibility(View.GONE);
            } else {
                mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
            }
        }

        if (mState.action == ACTION_CREATE) {
            final String mimeType = getIntent().getType();
            final String title = getIntent().getStringExtra(Intent.EXTRA_TITLE);
            SaveFragment.show(getFragmentManager(), mimeType, title);
        } else if (mState.action == ACTION_OPEN_TREE ||
                   mState.action == ACTION_OPEN_COPY_DESTINATION) {
            PickFragment.show(getFragmentManager());
        }

        if (mState.action == ACTION_GET_CONTENT) {
            final Intent moreApps = new Intent(getIntent());
            moreApps.setComponent(null);
            moreApps.setPackage(null);
            RootsFragment.show(getFragmentManager(), moreApps);
        } else if (mState.action == ACTION_OPEN ||
                   mState.action == ACTION_CREATE ||
                   mState.action == ACTION_OPEN_TREE ||
                   mState.action == ACTION_OPEN_COPY_DESTINATION) {
            RootsFragment.show(getFragmentManager(), null);
        }

        if (!mState.restored) {
            // In this case, we set the activity title in AsyncTask.onPostExecute().  To prevent
            // talkback from reading aloud the default title, we clear it here.
            setTitle("");
            if (mState.action == ACTION_MANAGE || mState.action == ACTION_BROWSE) {
                final Uri rootUri = getIntent().getData();
                new RestoreRootTask(rootUri).executeOnExecutor(getCurrentExecutor());
            } else {
                new RestoreStackTask().execute();
            }

            // Show a failure dialog if there was a failed operation.
            final Intent intent = getIntent();
            final DocumentStack dstStack = intent.getParcelableExtra(CopyService.EXTRA_STACK);
            final int failure = intent.getIntExtra(CopyService.EXTRA_FAILURE, 0);
            if (failure != 0) {
                final ArrayList<DocumentInfo> failedSrcList =
                        intent.getParcelableArrayListExtra(CopyService.EXTRA_SRC_LIST);
                FailureDialogFragment.show(getFragmentManager(), failure, failedSrcList, dstStack);
            }
        } else {
            onCurrentDirectoryChanged(ANIM_NONE);
        }
    }

    private State buildDefaultState() {
        State state = new State();

        final Intent intent = getIntent();
        final String action = intent.getAction();
        if (Intent.ACTION_OPEN_DOCUMENT.equals(action)) {
            state.action = ACTION_OPEN;
        } else if (Intent.ACTION_CREATE_DOCUMENT.equals(action)) {
            state.action = ACTION_CREATE;
        } else if (Intent.ACTION_GET_CONTENT.equals(action)) {
            state.action = ACTION_GET_CONTENT;
        } else if (Intent.ACTION_OPEN_DOCUMENT_TREE.equals(action)) {
            state.action = ACTION_OPEN_TREE;
        } else if (DocumentsContract.ACTION_MANAGE_ROOT.equals(action)) {
            state.action = ACTION_MANAGE;
        } else if (DocumentsContract.ACTION_BROWSE_DOCUMENT_ROOT.equals(action)) {
            state.action = ACTION_BROWSE;
        } else if (DocumentsIntent.ACTION_OPEN_COPY_DESTINATION.equals(action)) {
            state.action = ACTION_OPEN_COPY_DESTINATION;
        }

        if (state.action == ACTION_OPEN || state.action == ACTION_GET_CONTENT) {
            state.allowMultiple = intent.getBooleanExtra(
                    Intent.EXTRA_ALLOW_MULTIPLE, false);
        }

        if (state.action == ACTION_MANAGE || state.action == ACTION_BROWSE) {
            state.acceptMimes = new String[] { "*/*" };
            state.allowMultiple = true;
        } else if (intent.hasExtra(Intent.EXTRA_MIME_TYPES)) {
            state.acceptMimes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
        } else {
            state.acceptMimes = new String[] { intent.getType() };
        }

        state.localOnly = intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false);
        state.forceAdvanced = intent.getBooleanExtra(DocumentsContract.EXTRA_SHOW_ADVANCED, false);
        state.showAdvanced = state.forceAdvanced
                | LocalPreferences.getDisplayAdvancedDevices(this);

        if (state.action == ACTION_MANAGE || state.action == ACTION_BROWSE) {
            state.showSize = true;
        } else {
            state.showSize = LocalPreferences.getDisplayFileSize(this);
        }
        if (state.action == ACTION_OPEN_COPY_DESTINATION) {
            state.directoryCopy = intent.getBooleanExtra(
                    BaseActivity.DocumentsIntent.EXTRA_DIRECTORY_COPY, false);
        }

        state.excludedAuthorities = getExcludedAuthorities();

        return state;
    }

    private class RestoreRootTask extends AsyncTask<Void, Void, RootInfo> {
        private Uri mRootUri;

        public RestoreRootTask(Uri rootUri) {
            mRootUri = rootUri;
        }

        @Override
        protected RootInfo doInBackground(Void... params) {
            final String rootId = DocumentsContract.getRootId(mRootUri);
            return mRoots.getRootOneshot(mRootUri.getAuthority(), rootId);
        }

        @Override
        protected void onPostExecute(RootInfo root) {
            if (isDestroyed()) return;
            mState.restored = true;

            if (root != null) {
                onRootPicked(root);
            } else {
                Log.w(TAG, "Failed to find root: " + mRootUri);
                finish();
            }
        }
    }

    @Override
    void onStackRestored(boolean restored, boolean external) {
        // Show drawer when no stack restored, but only when requesting
        // non-visual content. However, if we last used an external app,
        // drawer is always shown.

        boolean showDrawer = false;
        if (!restored) {
            showDrawer = true;
        }
        if (MimePredicate.mimeMatches(MimePredicate.VISUAL_MIMES, mState.acceptMimes)) {
            showDrawer = false;
        }
        if (external && mState.action == ACTION_GET_CONTENT) {
            showDrawer = true;
        }

        if (showDrawer) {
            setRootsDrawerOpen(true);
        }
    }

    public void onAppPicked(ResolveInfo info) {
        final Intent intent = new Intent(getIntent());
        intent.setFlags(intent.getFlags() & ~Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        intent.setComponent(new ComponentName(
                info.activityInfo.applicationInfo.packageName, info.activityInfo.name));
        startActivityForResult(intent, CODE_FORWARD);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d(TAG, "onActivityResult() code=" + resultCode);

        // Only relay back results when not canceled; otherwise stick around to
        // let the user pick another app/backend.
        if (requestCode == CODE_FORWARD && resultCode != RESULT_CANCELED) {

            // Remember that we last picked via external app
            final String packageName = getCallingPackageMaybeExtra();
            final ContentValues values = new ContentValues();
            values.put(ResumeColumns.EXTERNAL, 1);
            getContentResolver().insert(RecentsProvider.buildResume(packageName), values);

            // Pass back result to original caller
            setResult(resultCode, data);
            finish();
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private DrawerListener mDrawerListener = new DrawerListener() {
        @Override
        public void onDrawerSlide(View drawerView, float slideOffset) {
            mDrawerToggle.onDrawerSlide(drawerView, slideOffset);
        }

        @Override
        public void onDrawerOpened(View drawerView) {
            mDrawerToggle.onDrawerOpened(drawerView);
        }

        @Override
        public void onDrawerClosed(View drawerView) {
            mDrawerToggle.onDrawerClosed(drawerView);
        }

        @Override
        public void onDrawerStateChanged(int newState) {
            mDrawerToggle.onDrawerStateChanged(newState);
        }
    };

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        if (mDrawerToggle != null) {
            mDrawerToggle.syncState();
        }
        updateActionBar();
    }

    public void setRootsDrawerOpen(boolean open) {
        if (!mShowAsDialog) {
            if (open) {
                mDrawerLayout.openDrawer(mRootsDrawer);
            } else {
                mDrawerLayout.closeDrawer(mRootsDrawer);
            }
        }
    }

    private boolean isRootsDrawerOpen() {
        if (mShowAsDialog) {
            return false;
        } else {
            return mDrawerLayout.isDrawerOpen(mRootsDrawer);
        }
    }

    @Override
    public void updateActionBar() {
        if (mRootsToolbar != null) {
            final String prompt = getIntent().getStringExtra(DocumentsContract.EXTRA_PROMPT);
            if (prompt != null) {
                mRootsToolbar.setTitle(prompt);
            } else {
                if (mState.action == ACTION_OPEN ||
                    mState.action == ACTION_GET_CONTENT ||
                    mState.action == ACTION_OPEN_TREE) {
                    mRootsToolbar.setTitle(R.string.title_open);
                } else if (mState.action == ACTION_CREATE ||
                           mState.action == ACTION_OPEN_COPY_DESTINATION) {
                    mRootsToolbar.setTitle(R.string.title_save);
                }
            }
        }

        if (!mShowAsDialog && mDrawerLayout.getDrawerLockMode(mRootsDrawer) ==
                DrawerLayout.LOCK_MODE_UNLOCKED) {
            mToolbar.setNavigationIcon(R.drawable.ic_hamburger);
            mToolbar.setNavigationContentDescription(R.string.drawer_open);
            mToolbar.setNavigationOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setRootsDrawerOpen(true);
                }
            });
        } else {
            mToolbar.setNavigationIcon(null);
            mToolbar.setNavigationContentDescription(R.string.drawer_open);
            mToolbar.setNavigationOnClickListener(null);
        }

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
    public boolean onCreateOptionsMenu(Menu menu) {
        boolean showMenu = super.onCreateOptionsMenu(menu);

        // Most actions are visible when showing as dialog
        if (mShowAsDialog) {
            expandMenus(menu);
        }
        return showMenu;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        final RootInfo root = getCurrentRoot();
        final DocumentInfo cwd = getCurrentDirectory();

        final MenuItem createDir = menu.findItem(R.id.menu_create_dir);
        final MenuItem grid = menu.findItem(R.id.menu_grid);
        final MenuItem list = menu.findItem(R.id.menu_list);
        final MenuItem advanced = menu.findItem(R.id.menu_advanced);
        final MenuItem fileSize = menu.findItem(R.id.menu_file_size);
        final MenuItem settings = menu.findItem(R.id.menu_settings);

        boolean fileSizeVisible = !(mState.action == ACTION_MANAGE
                || mState.action == ACTION_BROWSE);
        if (mState.action == ACTION_CREATE
                || mState.action == ACTION_OPEN_TREE
                || mState.action == ACTION_OPEN_COPY_DESTINATION) {
            createDir.setVisible(cwd != null && cwd.isCreateSupported());
            mSearchManager.showMenu(false);

            // No display options in recent directories
            if (cwd == null) {
                grid.setVisible(false);
                list.setVisible(false);
                fileSizeVisible = false;
            }

            if (mState.action == ACTION_CREATE) {
                final FragmentManager fm = getFragmentManager();
                SaveFragment.get(fm).setSaveEnabled(cwd != null && cwd.isCreateSupported());
            }
        } else {
            createDir.setVisible(false);
        }

        advanced.setVisible(!(mState.action == ACTION_MANAGE || mState.action == ACTION_BROWSE));
        fileSize.setVisible(fileSizeVisible);

        settings.setVisible((mState.action == ACTION_MANAGE || mState.action == ACTION_BROWSE)
                && (root.flags & Root.FLAG_HAS_SETTINGS) != 0);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mDrawerToggle != null && mDrawerToggle.onOptionsItemSelected(item)) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        // While action bar is expanded, the state stack UI is hidden.
        if (mSearchManager.cancelSearch()) {
            return;
        }

        if (!mState.stackTouched) {
            super.onBackPressed();
            return;
        }

        final int size = mState.stack.size();
        if (size > 1) {
            mState.stack.pop();
            onCurrentDirectoryChanged(ANIM_UP);
        } else if (size == 1 && !isRootsDrawerOpen()) {
            // TODO: open root drawer once we can capture back key
            super.onBackPressed();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public State getDisplayState() {
        return mState;
    }

    @Override
    void onDirectoryChanged(int anim) {
        final FragmentManager fm = getFragmentManager();
        final RootInfo root = getCurrentRoot();
        final DocumentInfo cwd = getCurrentDirectory();

        mDirectoryContainer.setDrawDisappearingFirst(anim == ANIM_DOWN);

        if (cwd == null) {
            // No directory means recents
            if (mState.action == ACTION_CREATE ||
                mState.action == ACTION_OPEN_TREE ||
                mState.action == ACTION_OPEN_COPY_DESTINATION) {
                RecentsCreateFragment.show(fm);
            } else {
                DirectoryFragment.showRecentsOpen(fm, anim);

                // Start recents in grid when requesting visual things
                final boolean visualMimes = MimePredicate.mimeMatches(
                        MimePredicate.VISUAL_MIMES, mState.acceptMimes);
                mState.userMode = visualMimes ? State.MODE_GRID : State.MODE_LIST;
                mState.derivedMode = mState.userMode;
            }
        } else {
            if (mState.currentSearch != null) {
                // Ongoing search
                DirectoryFragment.showSearch(fm, root, mState.currentSearch, anim);
            } else {
                // Normal boring directory
                DirectoryFragment.showNormal(fm, root, cwd, anim);
            }
        }

        // Forget any replacement target
        if (mState.action == ACTION_CREATE) {
            final SaveFragment save = SaveFragment.get(fm);
            if (save != null) {
                save.setReplaceTarget(null);
            }
        }

        if (mState.action == ACTION_OPEN_TREE ||
            mState.action == ACTION_OPEN_COPY_DESTINATION) {
            final PickFragment pick = PickFragment.get(fm);
            if (pick != null) {
                pick.setPickTarget(mState.action, cwd);
            }
        }
    }

    void onSaveRequested(DocumentInfo replaceTarget) {
        new ExistingFinishTask(replaceTarget.derivedUri).executeOnExecutor(getCurrentExecutor());
    }

    void onSaveRequested(String mimeType, String displayName) {
        new CreateFinishTask(mimeType, displayName).executeOnExecutor(getCurrentExecutor());
    }

    @Override
    void onRootPicked(RootInfo root) {
        super.onRootPicked(root);
        setRootsDrawerOpen(false);
    }

    @Override
    public void onDocumentPicked(DocumentInfo doc) {
        final FragmentManager fm = getFragmentManager();
        if (doc.isDirectory()) {
            mState.stack.push(doc);
            mState.stackTouched = true;
            onCurrentDirectoryChanged(ANIM_DOWN);
        } else if (mState.action == ACTION_OPEN || mState.action == ACTION_GET_CONTENT) {
            // Explicit file picked, return
            new ExistingFinishTask(doc.derivedUri).executeOnExecutor(getCurrentExecutor());
        } else if (mState.action == ACTION_CREATE) {
            // Replace selected file
            SaveFragment.get(fm).setReplaceTarget(doc);
        } else if (mState.action == ACTION_MANAGE) {
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
                    Toast.makeText(this, R.string.toast_no_application, Toast.LENGTH_SHORT).show();
                }
            }
        } else if (mState.action == ACTION_BROWSE) {
            // Go straight to viewing
            final Intent view = new Intent(Intent.ACTION_VIEW);
            view.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            view.setData(doc.derivedUri);

            try {
                startActivity(view);
            } catch (ActivityNotFoundException ex) {
                Toast.makeText(this, R.string.toast_no_application, Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onDocumentsPicked(List<DocumentInfo> docs) {
        if (mState.action == ACTION_OPEN || mState.action == ACTION_GET_CONTENT) {
            final int size = docs.size();
            final Uri[] uris = new Uri[size];
            for (int i = 0; i < size; i++) {
                uris[i] = docs.get(i).derivedUri;
            }
            new ExistingFinishTask(uris).executeOnExecutor(getCurrentExecutor());
        }
    }

    public void onPickRequested(DocumentInfo pickTarget) {
        Uri result;
        if (mState.action == ACTION_OPEN_TREE) {
            result = DocumentsContract.buildTreeDocumentUri(
                    pickTarget.authority, pickTarget.documentId);
        } else if (mState.action == ACTION_OPEN_COPY_DESTINATION) {
            result = pickTarget.derivedUri;
        } else {
            // Should not be reached.
            throw new IllegalStateException("Invalid mState.action.");
        }
        new PickFinishTask(result).executeOnExecutor(getCurrentExecutor());
    }

    @Override
    void saveStackBlocking() {
        final ContentResolver resolver = getContentResolver();
        final ContentValues values = new ContentValues();

        final byte[] rawStack = DurableUtils.writeToArrayOrNull(mState.stack);
        if (mState.action == ACTION_CREATE ||
            mState.action == ACTION_OPEN_TREE ||
            mState.action == ACTION_OPEN_COPY_DESTINATION) {
            // Remember stack for last create
            values.clear();
            values.put(RecentColumns.KEY, mState.stack.buildKey());
            values.put(RecentColumns.STACK, rawStack);
            resolver.insert(RecentsProvider.buildRecent(), values);
        }

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

        if (mState.action == ACTION_GET_CONTENT) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else if (mState.action == ACTION_OPEN_TREE) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        } else if (mState.action == ACTION_OPEN_COPY_DESTINATION) {
            // Picking a copy destination is only used internally by us, so we
            // don't need to extend permissions to the caller.
            intent.putExtra(CopyService.EXTRA_STACK, (Parcelable) mState.stack);
        } else {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        }

        setResult(Activity.RESULT_OK, intent);
        finish();
    }

    public static DocumentsActivity get(Fragment fragment) {
        return (DocumentsActivity) fragment.getActivity();
    }

    private final class PickFinishTask extends AsyncTask<Void, Void, Void> {
        private final Uri mUri;

        public PickFinishTask(Uri uri) {
            mUri = uri;
        }

        @Override
        protected Void doInBackground(Void... params) {
            saveStackBlocking();
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            onTaskFinished(mUri);
        }
    }

    final class ExistingFinishTask extends AsyncTask<Void, Void, Void> {
        private final Uri[] mUris;

        public ExistingFinishTask(Uri... uris) {
            mUris = uris;
        }

        @Override
        protected Void doInBackground(Void... params) {
            saveStackBlocking();
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            onTaskFinished(mUris);
        }
    }

    /**
     * Task that creates a new document in the background.
     */
    final class CreateFinishTask extends AsyncTask<Void, Void, Uri> {
        private final String mMimeType;
        private final String mDisplayName;

        public CreateFinishTask(String mimeType, String displayName) {
            mMimeType = mimeType;
            mDisplayName = displayName;
        }

        @Override
        protected void onPreExecute() {
            setPending(true);
        }

        @Override
        protected Uri doInBackground(Void... params) {
            final ContentResolver resolver = getContentResolver();
            final DocumentInfo cwd = getCurrentDirectory();

            ContentProviderClient client = null;
            Uri childUri = null;
            try {
                client = DocumentsApplication.acquireUnstableProviderOrThrow(
                        resolver, cwd.derivedUri.getAuthority());
                childUri = DocumentsContract.createDocument(
                        client, cwd.derivedUri, mMimeType, mDisplayName);
            } catch (Exception e) {
                Log.w(TAG, "Failed to create document", e);
            } finally {
                ContentProviderClient.releaseQuietly(client);
            }

            if (childUri != null) {
                saveStackBlocking();
            }

            return childUri;
        }

        @Override
        protected void onPostExecute(Uri result) {
            if (result != null) {
                onTaskFinished(result);
            } else {
                Toast.makeText(DocumentsActivity.this, R.string.save_error, Toast.LENGTH_SHORT)
                        .show();
            }

            setPending(false);
        }
    }
}
