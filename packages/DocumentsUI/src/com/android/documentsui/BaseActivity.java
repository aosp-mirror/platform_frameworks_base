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
import static com.android.documentsui.dirlist.DirectoryFragment.ANIM_DOWN;
import static com.android.documentsui.dirlist.DirectoryFragment.ANIM_NONE;
import static com.android.documentsui.dirlist.DirectoryFragment.ANIM_SIDE;
import static com.android.documentsui.dirlist.DirectoryFragment.ANIM_UP;
import static com.android.internal.util.Preconditions.checkArgument;

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
import android.support.annotation.LayoutRes;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.SearchView;
import android.widget.SearchView.OnQueryTextListener;
import android.widget.TextView;

import com.android.documentsui.RecentsProvider.ResumeColumns;
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

public abstract class BaseActivity extends Activity {

    static final String EXTRA_STATE = "state";

    State mState;
    RootsCache mRoots;
    SearchManager mSearchManager;
    DrawerController mDrawer;
    boolean mProductivityDevice;

    private final String mTag;
    @LayoutRes
    private int mLayoutId;
    private DirectoryContainerView mDirectoryContainer;

    public abstract void onDocumentPicked(DocumentInfo doc, @Nullable SiblingProvider siblings);
    public abstract void onDocumentsPicked(List<DocumentInfo> docs);

    abstract void onTaskFinished(Uri... uris);
    abstract void onDirectoryChanged(int anim);
    abstract void updateActionBar();
    abstract void saveStackBlocking();
    abstract State buildState();

    public BaseActivity(@LayoutRes int layoutId, String tag) {
        mLayoutId = layoutId;
        mTag = tag;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mProductivityDevice = getResources().getBoolean(R.bool.productivity_device);
        mState = (icicle != null)
                ? icicle.<State>getParcelable(EXTRA_STATE)
                        : buildState();

        setContentView(mLayoutId);

        mRoots = DocumentsApplication.getRootsCache(this);
        mRoots.setOnCacheUpdateListener(
                new RootsCache.OnCacheUpdateListener() {
                    @Override
                    public void onCacheUpdate() {
                        new HandleRootsChangedTask().execute(getCurrentRoot());
                    }
                });
        mDirectoryContainer = (DirectoryContainerView) findViewById(R.id.container_directory);
        mSearchManager = new SearchManager();

        // Base classes must update result in their onCreate.
        setResult(Activity.RESULT_CANCELED);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        boolean showMenu = super.onCreateOptionsMenu(menu);

        getMenuInflater().inflate(R.menu.activity, menu);
        mSearchManager.install((DocumentsToolBar) findViewById(R.id.toolbar));

        return showMenu;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        final RootInfo root = getCurrentRoot();
        final boolean inRecents = getCurrentDirectory() == null;

        final MenuItem sort = menu.findItem(R.id.menu_sort);
        final MenuItem sortSize = menu.findItem(R.id.menu_sort_size);
        final MenuItem grid = menu.findItem(R.id.menu_grid);
        final MenuItem list = menu.findItem(R.id.menu_list);
        final MenuItem advanced = menu.findItem(R.id.menu_advanced);
        final MenuItem fileSize = menu.findItem(R.id.menu_file_size);
        final MenuItem settings = menu.findItem(R.id.menu_settings);

        // I'm thinkin' this isn't necesary here. If it is...'cuz of a bug....
        // then uncomment the linke and let's get a proper bug reference here.
        // mSearchManager.update(root);

        // Search uses backend ranking; no sorting
        sort.setVisible(!inRecents && !mSearchManager.isSearching());

        // grid/list is effectively a toggle.
        grid.setVisible(mState.derivedMode != State.MODE_GRID);
        list.setVisible(mState.derivedMode != State.MODE_LIST);

        sortSize.setVisible(mState.showSize); // Only sort by size when visible
        fileSize.setVisible(!mState.forceSize);
        advanced.setVisible(!mState.forceAdvanced);
        settings.setVisible((root.flags & Root.FLAG_HAS_SETTINGS) != 0);

        advanced.setTitle(LocalPreferences.getDisplayAdvancedDevices(this)
                ? R.string.menu_advanced_hide : R.string.menu_advanced_show);
        fileSize.setTitle(LocalPreferences.getDisplayFileSize(this)
                ? R.string.menu_file_size_hide : R.string.menu_file_size_show);

        return true;
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
        // Clear entire backstack and start in new root
        mState.onRootChanged(root);
        mSearchManager.update(root);

        // Recents is always in memory, so we just load it directly.
        // Otherwise we delegate loading data from disk to a task
        // to ensure a responsive ui.
        if (mRoots.isRecentsRoot(root)) {
            onCurrentDirectoryChanged(ANIM_SIDE);
        } else {
            new PickRootTask(root, true).executeOnExecutor(getExecutorForCurrentDirectory());
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
                setUserMode(State.MODE_GRID);
                return true;

            case R.id.menu_list:
                setUserMode(State.MODE_LIST);
                return true;

            case R.id.menu_paste_from_clipboard:
                DirectoryFragment.get(getFragmentManager())
                    .pasteFromClipboard();
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
        onCurrentDirectoryChanged(ANIM_DOWN);
    }

    /**
     * Call this when directory changes. Prior to root fragment update
     * the (abstract) directoryChanged method will be called.
     * @param anim
     */
    // TODO: Refactor the usage of the method - now it is called not only when the directory
    // changed, but also to refresh the content of the directory while searching
    final void onCurrentDirectoryChanged(int anim) {
        mDirectoryContainer.setDrawDisappearingFirst(anim == ANIM_DOWN);
        onDirectoryChanged(anim);

        final RootsFragment roots = RootsFragment.get(getFragmentManager());
        if (roots != null) {
            roots.onCurrentRootChanged();
        }

        updateActionBar();

        // Prevents searchView from being recreated while searching
        if (!mSearchManager.isSearching()) {
            invalidateOptionsMenu();
        }
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
        DirectoryFragment.get(getFragmentManager()).onDisplayStateChanged();
        invalidateOptionsMenu();
    }

    public void onStateChanged() {
        invalidateOptionsMenu();
    }

    /**
     * Set state sort order based on explicit user action.
     */
    void setUserSortOrder(int sortOrder) {
        mState.userSortOrder = sortOrder;
        DirectoryFragment.get(getFragmentManager()).onUserSortOrderChanged();
    }

    /**
     * Set state mode based on explicit user action.
     */
    void setUserMode(int mode) {
        mState.userMode = mode;
        DirectoryFragment.get(getFragmentManager()).onUserModeChanged();
    }

    void setPending(boolean pending) {
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

        if (!mState.hasLocationChanged()) {
            super.onBackPressed();
            return;
        }

        final int size = mState.stack.size();

        if (mDrawer.isOpen()) {
            mDrawer.setOpen(false);
        } else if (size > 1) {
            mState.stack.pop();
            onCurrentDirectoryChanged(ANIM_UP);
        } else {
            super.onBackPressed();
        }
    }

    public void onStackPicked(DocumentStack stack) {
        try {
            // Update the restored stack to ensure we have freshest data
            stack.updateDocuments(getContentResolver());
            mState.setStack(stack);
            onCurrentDirectoryChanged(ANIM_SIDE);

        } catch (FileNotFoundException e) {
            Log.w(mTag, "Failed to restore stack: " + e);
        }
    }

    private DocumentInfo getRootDocumentBlocking(RootInfo root) {
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
        private boolean mTouched;

        public PickRootTask(RootInfo root, boolean touched) {
            mRoot = root;
            mTouched = touched;
        }

        @Override
        protected DocumentInfo doInBackground(Void... params) {
            return getRootDocumentBlocking(mRoot);
        }

        @Override
        protected void onPostExecute(DocumentInfo result) {
            if (result != null) {
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
            onCurrentDirectoryChanged(ANIM_NONE);
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
            if (homeRoot != null && mHome != null) {
                // Clear entire backstack and start in new root
                mState.onRootChanged(homeRoot);
                mSearchManager.update(homeRoot);
                openContainerDocument(mHome);
            }
        }
    }

    final class ItemSelectedListener implements OnItemSelectedListener {

        boolean mIgnoreNextNavigation;

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            if (mIgnoreNextNavigation) {
                mIgnoreNextNavigation = false;
                return;
            }

            while (mState.stack.size() > position + 1) {
                mState.popDocument();
            }
            onCurrentDirectoryChanged(ANIM_UP);
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
            // Ignored
        }
    }

    /**
     * Class providing toolbar with runtime access to useful activity data.
     */
    final class StackAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mState.stack.size();
        }

        @Override
        public DocumentInfo getItem(int position) {
            return mState.stack.get(mState.stack.size() - position - 1);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_subdir_title, parent, false);
            }

            final TextView title = (TextView) convertView.findViewById(android.R.id.title);
            final DocumentInfo doc = getItem(position);

            if (position == 0) {
                final RootInfo root = getCurrentRoot();
                title.setText(root.title);
            } else {
                title.setText(doc.displayName);
            }

            return convertView;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_subdir, parent, false);
            }

            final ImageView subdir = (ImageView) convertView.findViewById(R.id.subdir);
            final TextView title = (TextView) convertView.findViewById(android.R.id.title);
            final DocumentInfo doc = getItem(position);

            if (position == 0) {
                final RootInfo root = getCurrentRoot();
                title.setText(root.title);
                subdir.setVisibility(View.GONE);
            } else {
                title.setText(doc.displayName);
                subdir.setVisibility(View.VISIBLE);
            }

            return convertView;
        }
    }

    /**
     * Facade over the various search parts in the menu.
     */
    final class SearchManager implements
            SearchView.OnCloseListener, OnQueryTextListener, OnClickListener, OnFocusChangeListener,
            DocumentsToolBar.OnActionViewCollapsedListener {

        private boolean mSearchExpanded;
        private boolean mIgnoreNextClose;
        private boolean mIgnoreNextCollapse;

        private DocumentsToolBar mActionBar;
        private MenuItem mMenu;
        private SearchView mView;

        public void install(DocumentsToolBar actionBar) {
            assert(mActionBar == null);
            mActionBar = actionBar;
            mMenu = actionBar.getSearchMenu();
            mView = (SearchView) mMenu.getActionView();

            mActionBar.setOnActionViewCollapsedListener(this);
            mView.setOnQueryTextListener(this);
            mView.setOnCloseListener(this);
            mView.setOnSearchClickListener(this);
            mView.setOnQueryTextFocusChangeListener(this);
        }

        /**
         * @param root Info about the current directory.
         */
        void update(RootInfo root) {
            if (mMenu == null) {
                Log.d(mTag, "update called before Search MenuItem installed.");
                return;
            }

            if (mState.currentSearch != null) {
                mMenu.expandActionView();

                mView.setIconified(false);
                mView.clearFocus();
                mView.setQuery(mState.currentSearch, false);
            } else {
                mView.clearFocus();
                if (!mView.isIconified()) {
                    mIgnoreNextClose = true;
                    mView.setIconified(true);
                }

                if (mMenu.isActionViewExpanded()) {
                    mIgnoreNextCollapse = true;
                    mMenu.collapseActionView();
                }
            }

            showMenu(root != null
                    && ((root.flags & Root.FLAG_SUPPORTS_SEARCH) != 0));
        }

        void showMenu(boolean visible) {
            if (mMenu == null) {
                Log.d(mTag, "showMenu called before Search MenuItem installed.");
                return;
            }

            mMenu.setVisible(visible);
            if (!visible) {
                mState.currentSearch = null;
            }
        }

        /**
         * Cancels current search operation.
         * @return True if it cancels search. False if it does not operate
         *     search currently.
         */
        boolean cancelSearch() {
            if (isExpanded() || isSearching()) {
                // If the query string is not empty search view won't get iconified
                mView.setQuery("", false);
                mView.setIconified(true);
                return true;
            }
            return false;
        }

        boolean isSearching() {
            return mState.currentSearch != null;
        }

        boolean isExpanded() {
            return mSearchExpanded;
        }

        /**
         * Clears the search.
         * @return True if the default behavior of clearing/dismissing SearchView should be
         *      overridden. False otherwise.
         */
        @Override
        public boolean onClose() {
            mSearchExpanded = false;
            if (mIgnoreNextClose) {
                mIgnoreNextClose = false;
                return false;
            }

            mView.setBackgroundColor(
                    getResources().getColor(android.R.color.transparent, null));

            // Refresh the directory if a search was done
            if(mState.currentSearch != null) {
                mState.currentSearch = null;
                onCurrentDirectoryChanged(ANIM_NONE);
            }

            return false;
        }

        /**
         * Sets mSearchExpanded.
         * Called when search icon is clicked to start search.
         * Used to detect when the view expanded instead of onMenuItemActionExpand, because
         * SearchView has showAsAction set to always and onMenuItemAction* methods are not called.
         */
        @Override
        public void onClick (View v) {
            mSearchExpanded = true;
            mView.setBackgroundColor(
                    getResources().getColor(R.color.menu_search_background, null));
        }

        @Override
        public boolean onQueryTextSubmit(String query) {
            mState.currentSearch = query;
            mView.clearFocus();
            onCurrentDirectoryChanged(ANIM_NONE);
            return true;
        }

        @Override
        public boolean onQueryTextChange(String newText) {
            return false;
        }

        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            if(!hasFocus) {
                if(mState.currentSearch == null) {
                    mView.setIconified(true);
                }
                else if(TextUtils.isEmpty(mView.getQuery())) {
                    cancelSearch();
                }
            }
        }

        @Override
        public void onActionViewCollapsed() {
            updateActionBar();
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
