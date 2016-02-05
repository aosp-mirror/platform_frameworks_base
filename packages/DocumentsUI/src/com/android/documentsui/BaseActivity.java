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
import static com.android.documentsui.State.MODE_GRID;
import static com.android.documentsui.dirlist.DirectoryFragment.ANIM_ENTER;
import static com.android.documentsui.dirlist.DirectoryFragment.ANIM_LEAVE;
import static com.android.documentsui.dirlist.DirectoryFragment.ANIM_NONE;
import static com.android.documentsui.dirlist.DirectoryFragment.ANIM_SIDE;
import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkState;

import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Root;
import android.support.annotation.CallSuper;
import android.support.annotation.LayoutRes;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Spinner;
import android.widget.Toolbar;

import com.android.documentsui.RecentsProvider.ResumeColumns;
import com.android.documentsui.SearchManager.SearchManagerListener;
import com.android.documentsui.State.ViewMode;
import com.android.documentsui.dirlist.DirectoryFragment;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.model.DurableUtils;
import com.android.documentsui.model.RootInfo;
import com.android.internal.util.Preconditions;

import libcore.io.IoUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

public abstract class BaseActivity extends Activity
        implements SearchManagerListener, NavigationView.Environment {

    static final String EXTRA_STATE = "state";

    State mState;
    RootsCache mRoots;
    SearchManager mSearchManager;
    DrawerController mDrawer;
    NavigationView mNavigator;

    private final String mTag;
    @LayoutRes
    private int mLayoutId;

    public abstract void onDocumentPicked(DocumentInfo doc, @Nullable SiblingProvider siblings);
    public abstract void onDocumentsPicked(List<DocumentInfo> docs);

    abstract void onTaskFinished(Uri... uris);
    abstract void refreshDirectory(int anim);
    abstract void saveStackBlocking();
    abstract State buildState();

    public BaseActivity(@LayoutRes int layoutId, String tag) {
        mLayoutId = layoutId;
        mTag = tag;
    }

    @CallSuper
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        setContentView(mLayoutId);

        mDrawer = DrawerController.create(this);
        mState = (icicle != null)
                ? icicle.<State>getParcelable(EXTRA_STATE)
                        : buildState();
        Metrics.logActivityLaunch(this, mState, getIntent());

        mRoots = DocumentsApplication.getRootsCache(this);

        mRoots.setOnCacheUpdateListener(
                new RootsCache.OnCacheUpdateListener() {
                    @Override
                    public void onCacheUpdate() {
                        new HandleRootsChangedTask().execute(getCurrentRoot());
                    }
                });

        mSearchManager = new SearchManager(this);

        DocumentsToolbar toolbar = (DocumentsToolbar) findViewById(R.id.toolbar);
        setActionBar(toolbar);
        mNavigator = new NavigationView(
                mDrawer,
                toolbar,
                (Spinner) findViewById(R.id.stack),
                mState,
                this);

        // Base classes must update result in their onCreate.
        setResult(Activity.RESULT_CANCELED);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        boolean showMenu = super.onCreateOptionsMenu(menu);

        getMenuInflater().inflate(R.menu.activity, menu);
        mSearchManager.install((DocumentsToolbar) findViewById(R.id.toolbar));

        return showMenu;
    }

    @Override
    @CallSuper
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        mSearchManager.showMenu(canSearchRoot());

        final boolean inRecents = getCurrentDirectory() == null;

        final MenuItem sort = menu.findItem(R.id.menu_sort);
        final MenuItem sortSize = menu.findItem(R.id.menu_sort_size);
        final MenuItem grid = menu.findItem(R.id.menu_grid);
        final MenuItem list = menu.findItem(R.id.menu_list);
        final MenuItem advanced = menu.findItem(R.id.menu_advanced);
        final MenuItem fileSize = menu.findItem(R.id.menu_file_size);

        // Search uses backend ranking; no sorting, recents doesn't support sort.
        sort.setVisible(!inRecents && !mSearchManager.isSearching());
        sortSize.setVisible(mState.showSize); // Only sort by size when file sizes are visible
        fileSize.setVisible(!mState.forceSize);

        // grid/list is effectively a toggle.
        grid.setVisible(mState.derivedMode != State.MODE_GRID);
        list.setVisible(mState.derivedMode != State.MODE_LIST);

        advanced.setVisible(!mState.forceAdvanced);
        advanced.setTitle(LocalPreferences.getDisplayAdvancedDevices(this)
                ? R.string.menu_advanced_hide : R.string.menu_advanced_show);
        fileSize.setTitle(LocalPreferences.getDisplayFileSize(this)
                ? R.string.menu_file_size_hide : R.string.menu_file_size_show);

        return true;
    }

    @Override
    protected void onDestroy() {
        mRoots.setOnCacheUpdateListener(null);
        super.onDestroy();
    }

    State buildDefaultState() {
        State state = new State();

        final Intent intent = getIntent();

        state.localOnly = intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false);

        state.forceSize = intent.getBooleanExtra(DocumentsContract.EXTRA_SHOW_FILESIZE, false);
        state.showSize = state.forceSize || LocalPreferences.getDisplayFileSize(this);

        state.forceAdvanced = intent.getBooleanExtra(DocumentsContract.EXTRA_SHOW_ADVANCED, false);
        state.showAdvanced = state.forceAdvanced
                || LocalPreferences.getDisplayAdvancedDevices(this);

        state.initAcceptMimes(intent);
        state.excludedAuthorities = getExcludedAuthorities();

        return state;
    }

    void onStackRestored(boolean restored, boolean external) {}

    void onRootPicked(RootInfo root) {
        // Skip refreshing if root didn't change
        if(root.equals(getCurrentRoot())) {
            return;
        }

        mState.derivedMode = LocalPreferences.getViewMode(this, root, MODE_GRID);

        // Clear entire backstack and start in new root
        mState.onRootChanged(root);
        mSearchManager.update(root);

        // Recents is always in memory, so we just load it directly.
        // Otherwise we delegate loading data from disk to a task
        // to ensure a responsive ui.
        if (mRoots.isRecentsRoot(root)) {
            refreshCurrentRootAndDirectory(ANIM_NONE);
        } else {
            new PickRootTask(root).executeOnExecutor(getExecutorForCurrentDirectory());
        }
    }

    void expandMenus(Menu menu) {
        for (int i = 0; i < menu.size(); i++) {
            final MenuItem item = menu.getItem(i);
            switch (item.getItemId()) {
                case R.id.menu_advanced:
                case R.id.menu_file_size:
                case R.id.menu_new_window:
                case R.id.menu_search:
                    break;
                default:
                    item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {
            case android.R.id.home:
                onBackPressed();
                return true;

            case R.id.menu_create_dir:
                showCreateDirectoryDialog();
                return true;

            case R.id.menu_search:
                return false;

            case R.id.menu_sort_name:
                setUserSortOrder(State.SORT_ORDER_DISPLAY_NAME);
                return true;

            case R.id.menu_sort_date:
                setUserSortOrder(State.SORT_ORDER_LAST_MODIFIED);
                return true;
            case R.id.menu_sort_size:
                setUserSortOrder(State.SORT_ORDER_SIZE);
                return true;

            case R.id.menu_grid:
                setViewMode(State.MODE_GRID);
                return true;

            case R.id.menu_list:
                setViewMode(State.MODE_LIST);
                return true;

            case R.id.menu_paste_from_clipboard:
                DirectoryFragment dir = getDirectoryFragment();
                if (dir != null) {
                    dir.pasteFromClipboard();
                }
              return true;

            case R.id.menu_advanced:
                setDisplayAdvancedDevices(!LocalPreferences.getDisplayAdvancedDevices(this));
                return true;

            case R.id.menu_file_size:
                setDisplayFileSize(!LocalPreferences.getDisplayFileSize(this));
                return true;

            case R.id.menu_settings:
                final RootInfo root = getCurrentRoot();
                final Intent intent = new Intent(DocumentsContract.ACTION_DOCUMENT_ROOT_SETTINGS);
                intent.setDataAndType(root.getUri(), DocumentsContract.Root.MIME_TYPE_ITEM);
                startActivity(intent);
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    final @Nullable DirectoryFragment getDirectoryFragment() {
        return DirectoryFragment.get(getFragmentManager());
    }

    void showCreateDirectoryDialog() {
        CreateDirectoryFragment.show(getFragmentManager());
    }

    /**
     * Returns true if a directory can be created in the current location.
     * @return
     */
    boolean canCreateDirectory() {
        final RootInfo root = getCurrentRoot();
        final DocumentInfo cwd = getCurrentDirectory();
        return cwd != null
                && cwd.isCreateSupported()
                && !mSearchManager.isSearching()
                && !root.isRecents()
                && !root.isDownloads();
    }

    void onDirectoryCreated(DocumentInfo doc) {
        checkArgument(doc.isDirectory());
        openContainerDocument(doc);
    }

    void openContainerDocument(DocumentInfo doc) {
        checkArgument(doc.isContainer());
        mState.pushDocument(doc);
        // Show an opening animation only if pressing "back" would get us back to the
        // previous directory. Especially after opening a root document, pressing
        // back, wouldn't go to the previous root, but close the activity.
        final int anim = (mState.hasLocationChanged() && mState.stack.size() > 1)
                ? ANIM_ENTER : ANIM_NONE;
        refreshCurrentRootAndDirectory(anim);
    }

    /**
     * Refreshes the content of the director and the menu/action bar.
     * The current directory name and selection will get updated.
     * @param anim
     */
    @Override
    public final void refreshCurrentRootAndDirectory(int anim) {
        mSearchManager.cancelSearch();

        refreshDirectory(anim);

        final RootsFragment roots = RootsFragment.get(getFragmentManager());
        if (roots != null) {
            roots.onCurrentRootChanged();
        }

        mNavigator.update();
        invalidateOptionsMenu();
    }

    /**
     * Called when search results changed.
     * Refreshes the content of the directory. It doesn't refresh elements on the action bar.
     * e.g. The current directory name displayed on the action bar won't get updated.
     */
    @Override
    public void onSearchChanged() {
        refreshDirectory(ANIM_NONE);
    }

    /**
     * Called when search query changed.
     * Updates the state object.
     * @param query - New query
     */
    @Override
    public void onSearchQueryChanged(String query) {
        mState.currentSearch = query;
    }

    final List<String> getExcludedAuthorities() {
        List<String> authorities = new ArrayList<>();
        if (getIntent().getBooleanExtra(DocumentsContract.EXTRA_EXCLUDE_SELF, false)) {
            // Exclude roots provided by the calling package.
            String packageName = getCallingPackageMaybeExtra();
            try {
                PackageInfo pkgInfo = getPackageManager().getPackageInfo(packageName,
                        PackageManager.GET_PROVIDERS);
                for (ProviderInfo provider: pkgInfo.providers) {
                    authorities.add(provider.authority);
                }
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(mTag, "Calling package name does not resolve: " + packageName);
            }
        }
        return authorities;
    }

    boolean canSearchRoot() {
        final RootInfo root = getCurrentRoot();
        return (root.flags & Root.FLAG_SUPPORTS_SEARCH) != 0;
    }

    final String getCallingPackageMaybeExtra() {
        String callingPackage = getCallingPackage();
        // System apps can set the calling package name using an extra.
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(callingPackage, 0);
            if (info.isSystemApp() || info.isUpdatedSystemApp()) {
                final String extra = getIntent().getStringExtra(DocumentsContract.EXTRA_PACKAGE_NAME);
                if (extra != null) {
                    callingPackage = extra;
                }
            }
        } finally {
            return callingPackage;
        }
    }

    public static BaseActivity get(Fragment fragment) {
        return (BaseActivity) fragment.getActivity();
    }

    public State getDisplayState() {
        return mState;
    }

    void setDisplayAdvancedDevices(boolean display) {
        LocalPreferences.setDisplayAdvancedDevices(this, display);
        mState.showAdvanced = mState.forceAdvanced | display;
        RootsFragment.get(getFragmentManager()).onDisplayStateChanged();
        invalidateOptionsMenu();
    }

    void setDisplayFileSize(boolean display) {
        LocalPreferences.setDisplayFileSize(this, display);
        mState.showSize = display;
        DirectoryFragment dir = getDirectoryFragment();
        if (dir != null) {
            dir.onDisplayStateChanged();
        }
        invalidateOptionsMenu();
    }

    /**
     * Set state sort order based on explicit user action.
     */
    void setUserSortOrder(int sortOrder) {
        mState.userSortOrder = sortOrder;
        DirectoryFragment dir = getDirectoryFragment();
        if (dir != null) {
            dir.onSortOrderChanged();
        };
    }

    /**
     * Set mode based on explicit user action.
     */
    void setViewMode(@ViewMode int mode) {
        checkState(mState.stack.root != null);
        LocalPreferences.setViewMode(this, mState.stack.root, mode);
        mState.derivedMode = mode;

        // view icon needs to be updated, but we *could* do it
        // in onOptionsItemSelected, and not do the full invalidation
        // But! That's a larger refactoring we'll save for another day.
        invalidateOptionsMenu();
        DirectoryFragment dir = getDirectoryFragment();
        if (dir != null) {
            dir.onViewModeChanged();
        };
    }

    public void setPending(boolean pending) {
        final SaveFragment save = SaveFragment.get(getFragmentManager());
        if (save != null) {
            save.setPending(pending);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putParcelable(EXTRA_STATE, mState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
    }

    @Override
    public boolean isSearchExpanded() {
        return mSearchManager.isExpanded();
    }

    @Override
    public RootInfo getCurrentRoot() {
        if (mState.stack.root != null) {
            return mState.stack.root;
        } else {
            return mRoots.getRecentsRoot();
        }
    }

    public DocumentInfo getCurrentDirectory() {
        return mState.stack.peek();
    }

    public Executor getExecutorForCurrentDirectory() {
        final DocumentInfo cwd = getCurrentDirectory();
        if (cwd != null && cwd.authority != null) {
            return ProviderExecutor.forAuthority(cwd.authority);
        } else {
            return AsyncTask.THREAD_POOL_EXECUTOR;
        }
    }

    @Override
    public void onBackPressed() {
        // While action bar is expanded, the state stack UI is hidden.
        if (mSearchManager.cancelSearch()) {
            return;
        }

        DirectoryFragment dir = getDirectoryFragment();
        if (dir != null && dir.onBackPressed()) {
            return;
        }

        if (!mState.hasLocationChanged()) {
            super.onBackPressed();
            return;
        }

        final int size = mState.stack.size();

        if (mDrawer.isOpen()) {
            mDrawer.setOpen(false);
        } else if (size > 1) {
            mState.stack.pop();
            refreshCurrentRootAndDirectory(ANIM_LEAVE);
        } else {
            super.onBackPressed();
        }
    }

    public void onStackPicked(DocumentStack stack) {
        try {
            // Update the restored stack to ensure we have freshest data
            stack.updateDocuments(getContentResolver());
            mState.setStack(stack);
            refreshCurrentRootAndDirectory(ANIM_SIDE);

        } catch (FileNotFoundException e) {
            Log.w(mTag, "Failed to restore stack: " + e);
        }
    }

    DocumentInfo getRootDocumentBlocking(RootInfo root) {
        try {
            final Uri uri = DocumentsContract.buildDocumentUri(
                    root.authority, root.documentId);
            return DocumentInfo.fromUri(getContentResolver(), uri);
        } catch (FileNotFoundException e) {
            Log.w(mTag, "Failed to find root", e);
            return null;
        }
    }

    final class PickRootTask extends AsyncTask<Void, Void, DocumentInfo> {
        private RootInfo mRoot;

        public PickRootTask(RootInfo root) {
            mRoot = root;
        }

        @Override
        protected DocumentInfo doInBackground(Void... params) {
            return getRootDocumentBlocking(mRoot);
        }

        @Override
        protected void onPostExecute(DocumentInfo result) {
            if (result != null && !isDestroyed()) {
                openContainerDocument(result);
            }
        }
    }

    final class RestoreStackTask extends AsyncTask<Void, Void, Void> {
        private volatile boolean mRestoredStack;
        private volatile boolean mExternal;

        @Override
        protected Void doInBackground(Void... params) {
            if (DEBUG && !mState.stack.isEmpty()) {
                Log.w(mTag, "Overwriting existing stack.");
            }
            RootsCache roots = DocumentsApplication.getRootsCache(BaseActivity.this);

            // Restore last stack for calling package
            final String packageName = getCallingPackageMaybeExtra();
            final Cursor cursor = getContentResolver()
                    .query(RecentsProvider.buildResume(packageName), null, null, null, null);
            try {
                if (cursor.moveToFirst()) {
                    mExternal = cursor.getInt(cursor.getColumnIndex(ResumeColumns.EXTERNAL)) != 0;
                    final byte[] rawStack = cursor.getBlob(
                            cursor.getColumnIndex(ResumeColumns.STACK));
                    DurableUtils.readFromArray(rawStack, mState.stack);
                    mRestoredStack = true;
                }
            } catch (IOException e) {
                Log.w(mTag, "Failed to resume: " + e);
            } finally {
                IoUtils.closeQuietly(cursor);
            }

            if (mRestoredStack) {
                // Update the restored stack to ensure we have freshest data
                final Collection<RootInfo> matchingRoots = roots.getMatchingRootsBlocking(mState);
                try {
                    mState.stack.updateRoot(matchingRoots);
                    mState.stack.updateDocuments(getContentResolver());
                } catch (FileNotFoundException e) {
                    Log.w(mTag, "Failed to restore stack: " + e);
                    mState.stack.reset();
                    mRestoredStack = false;
                }
            }

            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            if (isDestroyed()) return;
            mState.restored = true;
            refreshCurrentRootAndDirectory(ANIM_NONE);
            onStackRestored(mRestoredStack, mExternal);
        }
    }

    final class RestoreRootTask extends AsyncTask<Void, Void, RootInfo> {
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
                Log.w(mTag, "Failed to find root: " + mRootUri);
                finish();
            }
        }
    }

    final class HandleRootsChangedTask extends AsyncTask<RootInfo, Void, RootInfo> {
        DocumentInfo mHome;

        @Override
        protected RootInfo doInBackground(RootInfo... roots) {
            checkArgument(roots.length == 1);
            final RootInfo currentRoot = roots[0];
            final Collection<RootInfo> cachedRoots = mRoots.getRootsBlocking();
            RootInfo homeRoot = null;
            for (final RootInfo root : cachedRoots) {
                if (root.isHome()) {
                    homeRoot = root;
                }
                if (root.getUri().equals(currentRoot.getUri())) {
                    // We don't need to change the current root as the current root was not removed.
                    return null;
                }
            }
            Preconditions.checkNotNull(homeRoot);
            mHome = getRootDocumentBlocking(homeRoot);
            return homeRoot;
        }

        @Override
        protected void onPostExecute(RootInfo homeRoot) {
            if (homeRoot != null && mHome != null && !isDestroyed()) {
                // Clear entire backstack and start in new root
                mState.onRootChanged(homeRoot);
                mSearchManager.update(homeRoot);
                openContainerDocument(mHome);
            }
        }
    }

    /**
     * Interface providing access to current view of documents
     * even when all documents are not homed to the same parent.
     */
    public interface SiblingProvider {
        /**
         * Returns the cursor for the selected document. The cursor can be used to retrieve
         * details about a document and its siblings.
         * @return
         */
        Cursor getCursor();
    }
}
