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

import com.android.documentsui.SearchManager.SearchManagerListener;
import com.android.documentsui.State.ViewMode;
import com.android.documentsui.dirlist.DirectoryFragment;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.model.RootInfo;
import com.android.internal.util.Preconditions;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

public abstract class BaseActivity extends Activity
        implements SearchManagerListener, NavigationView.Environment {

    static final String EXTRA_STATE = "state";

    // See comments where this const is referenced for details.
    private static final int DRAWER_NO_FIDDLE_DELAY = 1500;

    State mState;
    RootsCache mRoots;
    SearchManager mSearchManager;
    DrawerController mDrawer;
    NavigationView mNavigator;

    private final String mTag;

    @LayoutRes
    private int mLayoutId;

    // Track the time we opened the drawer in response to back being pressed.
    // We use the time gap to figure out whether to close app or reopen the drawer.
    private long mDrawerLastFiddled;

    public abstract void onDocumentPicked(DocumentInfo doc, @Nullable SiblingProvider siblings);
    public abstract void onDocumentsPicked(List<DocumentInfo> docs);

    abstract void onTaskFinished(Uri... uris);
    abstract void refreshDirectory(int anim);
    /** Allows sub-classes to include information in a newly created State instance. */
    abstract void includeState(State initialState);

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
        mState = getState(icicle);
        Metrics.logActivityLaunch(this, mState, getIntent());

        mRoots = DocumentsApplication.getRootsCache(this);

        mRoots.setOnCacheUpdateListener(
                new RootsCache.OnCacheUpdateListener() {
                    @Override
                    public void onCacheUpdate() {
                        new HandleRootsChangedTask(BaseActivity.this)
                                .execute(getCurrentRoot());
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

    private State getState(@Nullable Bundle icicle) {
        if (icicle != null) {
            State state = icicle.<State>getParcelable(EXTRA_STATE);
            if (DEBUG) Log.d(mTag, "Recovered existing state object: " + state);
            return state;
        }

        State state = createSharedState();
        includeState(state);
        if (DEBUG) Log.d(mTag, "Created new state object: " + state);
        return state;
    }

    private State createSharedState() {
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
        // Skip refreshing if root nor directory didn't change
        if (root.equals(getCurrentRoot()) && mState.stack.size() == 1) {
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
            new PickRootTask(this, root).executeOnExecutor(getExecutorForCurrentDirectory());
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

        int size = mState.stack.size();

        // Do some "do what a I want" drawer fiddling, but don't
        // do it if user already hit back recently and we recently
        // did some fiddling.
        if ((System.currentTimeMillis() - mDrawerLastFiddled) > DRAWER_NO_FIDDLE_DELAY) {
            // Close drawer if it is open.
            if (mDrawer.isOpen()) {
                mDrawer.setOpen(false);
                mDrawerLastFiddled = System.currentTimeMillis();
                return;
            }

            // Open the Close drawer if it is closed and we're at the top of a root.
            if (size == 1) {
                mDrawer.setOpen(true);
                // Remember so we don't just close it again if back is pressed again.
                mDrawerLastFiddled = System.currentTimeMillis();
                return;
            }
        }

        if (size > 1) {
            mState.stack.pop();
            refreshCurrentRootAndDirectory(ANIM_LEAVE);
            return;
        }

        super.onBackPressed();
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

    private static final class PickRootTask extends PairedTask<BaseActivity, Void, DocumentInfo> {
        private RootInfo mRoot;

        public PickRootTask(BaseActivity activity, RootInfo root) {
            super(activity);
            mRoot = root;
        }

        @Override
        protected DocumentInfo run(Void... params) {
            return mOwner.getRootDocumentBlocking(mRoot);
        }

        @Override
        protected void finish(DocumentInfo result) {
            if (result != null) {
                mOwner.openContainerDocument(result);
            }
        }
    }

    private static final class HandleRootsChangedTask
            extends PairedTask<BaseActivity, RootInfo, RootInfo> {
        DocumentInfo mHome;

        public HandleRootsChangedTask(BaseActivity activity) {
            super(activity);
        }

        @Override
        protected RootInfo run(RootInfo... roots) {
            checkArgument(roots.length == 1);
            final RootInfo currentRoot = roots[0];
            final Collection<RootInfo> cachedRoots = mOwner.mRoots.getRootsBlocking();
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
            mHome = mOwner.getRootDocumentBlocking(homeRoot);
            return homeRoot;
        }

        @Override
        protected void finish(RootInfo homeRoot) {
            if (homeRoot != null && mHome != null) {
                // Clear entire backstack and start in new root
                mOwner.mState.onRootChanged(homeRoot);
                mOwner.mSearchManager.update(homeRoot);
                mOwner.openContainerDocument(mHome);
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
