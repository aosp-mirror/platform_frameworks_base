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
import static com.android.documentsui.DirectoryFragment.ANIM_SIDE;
import static com.android.documentsui.DirectoryFragment.ANIM_UP;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executor;

import libcore.io.IoUtils;
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
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Root;
import android.util.Log;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuItem.OnActionExpandListener;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.SearchView;
import android.widget.SearchView.OnQueryTextListener;
import android.widget.TextView;

import com.android.documentsui.RecentsProvider.ResumeColumns;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.model.DurableUtils;
import com.android.documentsui.model.RootInfo;
import com.google.common.collect.Maps;

abstract class BaseActivity extends Activity {

    static final String EXTRA_STATE = "state";

    RootsCache mRoots;
    SearchManager mSearchManager;

    private final String mTag;

    public abstract State getDisplayState();
    public abstract void onDocumentPicked(DocumentInfo doc);
    public abstract void onDocumentsPicked(List<DocumentInfo> docs);
    abstract void onTaskFinished(Uri... uris);
    abstract void onDirectoryChanged(int anim);
    abstract void updateActionBar();
    abstract void saveStackBlocking();

    public BaseActivity(String tag) {
        mTag = tag;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        mRoots = DocumentsApplication.getRootsCache(this);
        mSearchManager = new SearchManager();
    }

    @Override
    public void onResume() {
        super.onResume();

        final State state = getDisplayState();
        final RootInfo root = getCurrentRoot();

        // If we're browsing a specific root, and that root went away, then we
        // have no reason to hang around
        if (state.action == State.ACTION_BROWSE && root != null) {
            if (mRoots.getRootBlocking(root.authority, root.rootId) == null) {
                finish();
            }
        }
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
        boolean shown = super.onPrepareOptionsMenu(menu);

        final RootInfo root = getCurrentRoot();
        final DocumentInfo cwd = getCurrentDirectory();

        final MenuItem sort = menu.findItem(R.id.menu_sort);
        final MenuItem sortSize = menu.findItem(R.id.menu_sort_size);
        final MenuItem grid = menu.findItem(R.id.menu_grid);
        final MenuItem list = menu.findItem(R.id.menu_list);

        final MenuItem advanced = menu.findItem(R.id.menu_advanced);
        final MenuItem fileSize = menu.findItem(R.id.menu_file_size);

        mSearchManager.update(root);

        // Search uses backend ranking; no sorting
        sort.setVisible(cwd != null && !mSearchManager.isSearching());

        State state = getDisplayState();
        grid.setVisible(state.derivedMode != State.MODE_GRID);
        list.setVisible(state.derivedMode != State.MODE_LIST);

        // Only sort by size when visible
        sortSize.setVisible(state.showSize);

        advanced.setTitle(LocalPreferences.getDisplayAdvancedDevices(this)
                ? R.string.menu_advanced_hide : R.string.menu_advanced_show);
        fileSize.setTitle(LocalPreferences.getDisplayFileSize(this)
                ? R.string.menu_file_size_hide : R.string.menu_file_size_show);

        return shown;
    }

    void onStackRestored(boolean restored, boolean external) {}

    void onRootPicked(RootInfo root) {
        State state = getDisplayState();

        // Clear entire backstack and start in new root
        state.stack.root = root;
        state.stack.clear();
        state.stackTouched = true;

        mSearchManager.update(root);

        // Recents is always in memory, so we just load it directly.
        // Otherwise we delegate loading data from disk to a task
        // to ensure a responsive ui.
        if (mRoots.isRecentsRoot(root)) {
            onCurrentDirectoryChanged(ANIM_SIDE);
        } else {
            new PickRootTask(root).executeOnExecutor(getCurrentExecutor());
        }
    }

    void expandMenus(Menu menu) {
        for (int i = 0; i < menu.size(); i++) {
            final MenuItem item = menu.getItem(i);
            switch (item.getItemId()) {
                case R.id.menu_advanced:
                case R.id.menu_file_size:
                    break;
                default:
                    item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int id = item.getItemId();
        if (id == android.R.id.home) {
            onBackPressed();
            return true;
        } else if (id == R.id.menu_create_dir) {
            CreateDirectoryFragment.show(getFragmentManager());
            return true;
        } else if (id == R.id.menu_search) {
            return false;
        } else if (id == R.id.menu_sort_name) {
            setUserSortOrder(State.SORT_ORDER_DISPLAY_NAME);
            return true;
        } else if (id == R.id.menu_sort_date) {
            setUserSortOrder(State.SORT_ORDER_LAST_MODIFIED);
            return true;
        } else if (id == R.id.menu_sort_size) {
            setUserSortOrder(State.SORT_ORDER_SIZE);
            return true;
        } else if (id == R.id.menu_grid) {
            setUserMode(State.MODE_GRID);
            return true;
        } else if (id == R.id.menu_list) {
            setUserMode(State.MODE_LIST);
            return true;
        } else if (id == R.id.menu_advanced) {
            setDisplayAdvancedDevices(!LocalPreferences.getDisplayAdvancedDevices(this));
            return true;
        } else if (id == R.id.menu_file_size) {
            setDisplayFileSize(!LocalPreferences.getDisplayFileSize(this));
            return true;
        } else if (id == R.id.menu_settings) {
            final RootInfo root = getCurrentRoot();
            final Intent intent = new Intent(DocumentsContract.ACTION_DOCUMENT_ROOT_SETTINGS);
            intent.setDataAndType(DocumentsContract.buildRootUri(root.authority, root.rootId),
                    DocumentsContract.Root.MIME_TYPE_ITEM);
            startActivity(intent);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    /**
     * Call this when directory changes. Prior to root fragment update
     * the (abstract) directoryChanged method will be called.
     * @param anim
     */
    final void onCurrentDirectoryChanged(int anim) {
        onDirectoryChanged(anim);

        final RootsFragment roots = RootsFragment.get(getFragmentManager());
        if (roots != null) {
            roots.onCurrentRootChanged();
        }

        updateActionBar();
        invalidateOptionsMenu();
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

    public static abstract class DocumentsIntent {
        /** Intent action name to open copy destination. */
        public static String ACTION_OPEN_COPY_DESTINATION =
                "com.android.documentsui.OPEN_COPY_DESTINATION";

        /**
         * Extra boolean flag for ACTION_OPEN_COPY_DESTINATION_STRING, which
         * specifies if the destination directory needs to create new directory or not.
         */
        public static String EXTRA_DIRECTORY_COPY = "com.android.documentsui.DIRECTORY_COPY";
    }

    public static class State implements android.os.Parcelable {
        public int action;
        public String[] acceptMimes;

        /** Explicit user choice */
        public int userMode = MODE_UNKNOWN;
        /** Derived after loader */
        public int derivedMode = MODE_LIST;

        /** Explicit user choice */
        public int userSortOrder = SORT_ORDER_UNKNOWN;
        /** Derived after loader */
        public int derivedSortOrder = SORT_ORDER_DISPLAY_NAME;

        public boolean allowMultiple = false;
        public boolean showSize = false;
        public boolean localOnly = false;
        public boolean forceAdvanced = false;
        public boolean showAdvanced = false;
        public boolean stackTouched = false;
        public boolean restored = false;
        public boolean directoryCopy = false;

        /** Current user navigation stack; empty implies recents. */
        public DocumentStack stack = new DocumentStack();
        /** Currently active search, overriding any stack. */
        public String currentSearch;

        /** Instance state for every shown directory */
        public HashMap<String, SparseArray<Parcelable>> dirState = Maps.newHashMap();

        /** Currently copying file */
        public List<DocumentInfo> selectedDocumentsForCopy = new ArrayList<DocumentInfo>();

        /** Name of the package that started DocsUI */
        public List<String> excludedAuthorities = new ArrayList<>();

        public static final int ACTION_OPEN = 1;
        public static final int ACTION_CREATE = 2;
        public static final int ACTION_GET_CONTENT = 3;
        public static final int ACTION_OPEN_TREE = 4;
        public static final int ACTION_MANAGE = 5;
        public static final int ACTION_BROWSE = 6;
        public static final int ACTION_BROWSE_ALL = 7;
        public static final int ACTION_OPEN_COPY_DESTINATION = 8;

        public static final int MODE_UNKNOWN = 0;
        public static final int MODE_LIST = 1;
        public static final int MODE_GRID = 2;

        public static final int SORT_ORDER_UNKNOWN = 0;
        public static final int SORT_ORDER_DISPLAY_NAME = 1;
        public static final int SORT_ORDER_LAST_MODIFIED = 2;
        public static final int SORT_ORDER_SIZE = 3;

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(action);
            out.writeInt(userMode);
            out.writeStringArray(acceptMimes);
            out.writeInt(userSortOrder);
            out.writeInt(allowMultiple ? 1 : 0);
            out.writeInt(showSize ? 1 : 0);
            out.writeInt(localOnly ? 1 : 0);
            out.writeInt(forceAdvanced ? 1 : 0);
            out.writeInt(showAdvanced ? 1 : 0);
            out.writeInt(stackTouched ? 1 : 0);
            out.writeInt(restored ? 1 : 0);
            DurableUtils.writeToParcel(out, stack);
            out.writeString(currentSearch);
            out.writeMap(dirState);
            out.writeList(selectedDocumentsForCopy);
            out.writeList(excludedAuthorities);
        }

        public static final Creator<State> CREATOR = new Creator<State>() {
            @Override
            public State createFromParcel(Parcel in) {
                final State state = new State();
                state.action = in.readInt();
                state.userMode = in.readInt();
                state.acceptMimes = in.readStringArray();
                state.userSortOrder = in.readInt();
                state.allowMultiple = in.readInt() != 0;
                state.showSize = in.readInt() != 0;
                state.localOnly = in.readInt() != 0;
                state.forceAdvanced = in.readInt() != 0;
                state.showAdvanced = in.readInt() != 0;
                state.stackTouched = in.readInt() != 0;
                state.restored = in.readInt() != 0;
                DurableUtils.readFromParcel(in, state.stack);
                state.currentSearch = in.readString();
                in.readMap(state.dirState, null);
                in.readList(state.selectedDocumentsForCopy, null);
                in.readList(state.excludedAuthorities, null);
                return state;
            }

            @Override
            public State[] newArray(int size) {
                return new State[size];
            }
        };
    }

    void setDisplayAdvancedDevices(boolean display) {
        State state = getDisplayState();
        LocalPreferences.setDisplayAdvancedDevices(this, display);
        state.showAdvanced = state.forceAdvanced | display;
        RootsFragment.get(getFragmentManager()).onDisplayStateChanged();
        invalidateOptionsMenu();
    }

    void setDisplayFileSize(boolean display) {
        LocalPreferences.setDisplayFileSize(this, display);
        getDisplayState().showSize = display;
        DirectoryFragment.get(getFragmentManager()).onDisplayStateChanged();
        invalidateOptionsMenu();
    }

    void onStateChanged() {
        invalidateOptionsMenu();
    }

    /**
     * Set state sort order based on explicit user action.
     */
    void setUserSortOrder(int sortOrder) {
        getDisplayState().userSortOrder = sortOrder;
        DirectoryFragment.get(getFragmentManager()).onUserSortOrderChanged();
    }

    /**
     * Set state mode based on explicit user action.
     */
    void setUserMode(int mode) {
        getDisplayState().userMode = mode;
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
        state.putParcelable(EXTRA_STATE, getDisplayState());
    }

    @Override
    protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
    }

    RootInfo getCurrentRoot() {
        State state = getDisplayState();
        if (state.stack.root != null) {
            return state.stack.root;
        } else {
            return mRoots.getRecentsRoot();
        }
    }

    public DocumentInfo getCurrentDirectory() {
        return getDisplayState().stack.peek();
    }

    public Executor getCurrentExecutor() {
        final DocumentInfo cwd = getCurrentDirectory();
        if (cwd != null && cwd.authority != null) {
            return ProviderExecutor.forAuthority(cwd.authority);
        } else {
            return AsyncTask.THREAD_POOL_EXECUTOR;
        }
    }

    public void onStackPicked(DocumentStack stack) {
        try {
            // Update the restored stack to ensure we have freshest data
            stack.updateDocuments(getContentResolver());

            State state = getDisplayState();
            state.stack = stack;
            state.stackTouched = true;
            onCurrentDirectoryChanged(ANIM_SIDE);

        } catch (FileNotFoundException e) {
            Log.w(mTag, "Failed to restore stack: " + e);
        }
    }

    final class PickRootTask extends AsyncTask<Void, Void, DocumentInfo> {
        private RootInfo mRoot;

        public PickRootTask(RootInfo root) {
            mRoot = root;
        }

        @Override
        protected DocumentInfo doInBackground(Void... params) {
            try {
                final Uri uri = DocumentsContract.buildDocumentUri(
                        mRoot.authority, mRoot.documentId);
                return DocumentInfo.fromUri(getContentResolver(), uri);
            } catch (FileNotFoundException e) {
                Log.w(mTag, "Failed to find root", e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(DocumentInfo result) {
            if (result != null) {
                State state = getDisplayState();
                state.stack.push(result);
                state.stackTouched = true;
                onCurrentDirectoryChanged(ANIM_SIDE);
            }
        }
    }

    final class RestoreStackTask extends AsyncTask<Void, Void, Void> {
        private volatile boolean mRestoredStack;
        private volatile boolean mExternal;

        @Override
        protected Void doInBackground(Void... params) {
            State state = getDisplayState();
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
                    DurableUtils.readFromArray(rawStack, state.stack);
                    mRestoredStack = true;
                }
            } catch (IOException e) {
                Log.w(mTag, "Failed to resume: " + e);
            } finally {
                IoUtils.closeQuietly(cursor);
            }

            if (mRestoredStack) {
                // Update the restored stack to ensure we have freshest data
                final Collection<RootInfo> matchingRoots = roots.getMatchingRootsBlocking(state);
                try {
                    state.stack.updateRoot(matchingRoots);
                    state.stack.updateDocuments(getContentResolver());
                } catch (FileNotFoundException e) {
                    Log.w(mTag, "Failed to restore stack: " + e);
                    state.stack.reset();
                    mRestoredStack = false;
                }
            }

            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            if (isDestroyed()) return;
            getDisplayState().restored = true;
            onCurrentDirectoryChanged(ANIM_NONE);

            onStackRestored(mRestoredStack, mExternal);

            getDisplayState().restored = true;
            onCurrentDirectoryChanged(ANIM_NONE);
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

            State state = getDisplayState();
            while (state.stack.size() > position + 1) {
                state.stackTouched = true;
                state.stack.pop();
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
            return getDisplayState().stack.size();
        }

        @Override
        public DocumentInfo getItem(int position) {
            State state = getDisplayState();
            return state.stack.get(state.stack.size() - position - 1);
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
            SearchView.OnCloseListener, OnActionExpandListener, OnQueryTextListener,
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
            mMenu.setOnActionExpandListener(this);
            mView.setOnQueryTextListener(this);
            mView.setOnCloseListener(this);
        }

        /**
         * @param root Info about the current directory.
         */
        void update(RootInfo root) {
            if (mMenu == null) {
                Log.d(mTag, "update called before Search MenuItem installed.");
                return;
            }

            State state = getDisplayState();
            if (state.currentSearch != null) {
                mMenu.expandActionView();

                mView.setIconified(false);
                mView.clearFocus();
                mView.setQuery(state.currentSearch, false);
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
                getDisplayState().currentSearch = null;
            }
        }

        /**
         * Cancels current search operation.
         * @return True if it cancels search. False if it does not operate
         *     search currently.
         */
        boolean cancelSearch() {
            if (mActionBar.hasExpandedActionView()) {
                mActionBar.collapseActionView();
                return true;
            }
            return false;
        }

        boolean isSearching() {
            return getDisplayState().currentSearch != null;
        }

        boolean isExpanded() {
            return mSearchExpanded;
        }

        @Override
        public boolean onClose() {
            mSearchExpanded = false;
            if (mIgnoreNextClose) {
                mIgnoreNextClose = false;
                return false;
            }

            getDisplayState().currentSearch = null;
            onCurrentDirectoryChanged(ANIM_NONE);
            return false;
        }

        @Override
        public boolean onMenuItemActionExpand(MenuItem item) {
            mSearchExpanded = true;
            updateActionBar();
            return true;
        }

        @Override
        public boolean onMenuItemActionCollapse(MenuItem item) {
            mSearchExpanded = false;
            if (mIgnoreNextCollapse) {
                mIgnoreNextCollapse = false;
                return true;
            }
            getDisplayState().currentSearch = null;
            onCurrentDirectoryChanged(ANIM_NONE);
            return true;
        }

        @Override
        public boolean onQueryTextSubmit(String query) {
            mSearchExpanded = true;
            getDisplayState().currentSearch = query;
            mView.clearFocus();
            onCurrentDirectoryChanged(ANIM_NONE);
            return true;
        }

        @Override
        public boolean onQueryTextChange(String newText) {
            return false;
        }

        @Override
        public void onActionViewCollapsed() {
            updateActionBar();
        }
    }
}
