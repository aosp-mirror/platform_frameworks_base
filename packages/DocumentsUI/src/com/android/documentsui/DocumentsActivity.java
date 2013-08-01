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

import static com.android.documentsui.DirectoryFragment.getCursorString;

import android.app.ActionBar;
import android.app.ActionBar.OnNavigationListener;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.app.FragmentManager.OnBackStackChangedListener;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.res.Resources.NotFoundException;
import android.database.Cursor;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.DocumentColumns;
import android.provider.DocumentsContract.RootColumns;
import android.support.v4.app.ActionBarDrawerToggle;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v4.widget.DrawerLayout.DrawerListener;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.SearchView.OnQueryTextListener;
import android.widget.TextView;

import com.google.android.collect.Lists;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DocumentsActivity extends Activity {
    private static final String TAG = "Documents";

    // TODO: fragment to show recently opened documents
    // TODO: pull actionbar icon from current backend

    private static final String TAG_CREATE_DIRECTORY = "create_directory";

    private static final int ACTION_OPEN = 1;
    private static final int ACTION_CREATE = 2;

    private int mAction;
    private String[] mAcceptMimes;

    private SearchView mSearchView;

    private DrawerLayout mDrawerLayout;
    private ActionBarDrawerToggle mDrawerToggle;

    private ArrayList<Root> mRoots = Lists.newArrayList();
    private RootsAdapter mRootsAdapter;
    private ListView mRootsList;

    private final DisplayState mDisplayState = new DisplayState();

    private Root mCurrentRoot;

    private Uri mCurrentDir;
    private boolean mCurrentSupportsCreate;
    private boolean mCurrentSupportsSearch;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        final Intent intent = getIntent();
        final String action = intent.getAction();
        if (Intent.ACTION_OPEN_DOCUMENT.equals(action)) {
            mAction = ACTION_OPEN;
            mDisplayState.allowMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        } else if (Intent.ACTION_CREATE_DOCUMENT.equals(action)) {
            mAction = ACTION_CREATE;
            mDisplayState.allowMultiple = false;
        }

        if (intent.hasExtra(Intent.EXTRA_MIME_TYPES)) {
            mAcceptMimes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
        } else {
            mAcceptMimes = new String[] { intent.getType() };
        }

        if (mimeMatches("image/*", mAcceptMimes)) {
            mDisplayState.mode = DisplayState.MODE_GRID;
        } else {
            mDisplayState.mode = DisplayState.MODE_LIST;
        }

        setResult(Activity.RESULT_CANCELED);
        setContentView(R.layout.activity);

        getFragmentManager().addOnBackStackChangedListener(mStackListener);

        if (mAction == ACTION_CREATE) {
            final String mimeType = getIntent().getType();
            final String title = getIntent().getStringExtra(Intent.EXTRA_TITLE);
            SaveFragment.show(getFragmentManager(), mimeType, title);
        }

        mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
        mRootsAdapter = new RootsAdapter(this, mRoots);
        mRootsList = (ListView) findViewById(R.id.roots_list);
        mRootsList.setAdapter(mRootsAdapter);
        mRootsList.setOnItemClickListener(mRootsListener);

        mDrawerToggle = new ActionBarDrawerToggle(this, mDrawerLayout,
                R.drawable.ic_drawer, R.string.drawer_open, R.string.drawer_close);

        mDrawerLayout.setDrawerListener(mDrawerListener);
        mDrawerLayout.setDrawerShadow(R.drawable.drawer_shadow, GravityCompat.START);

        mDrawerLayout.openDrawer(mRootsList);

        updateActionBar();
        updateRoots();
    }

    private DrawerListener mDrawerListener = new DrawerListener() {
        @Override
        public void onDrawerSlide(View drawerView, float slideOffset) {
            mDrawerToggle.onDrawerSlide(drawerView, slideOffset);
        }

        @Override
        public void onDrawerOpened(View drawerView) {
            mDrawerToggle.onDrawerOpened(drawerView);
            updateActionBar();
        }

        @Override
        public void onDrawerClosed(View drawerView) {
            mDrawerToggle.onDrawerClosed(drawerView);
            updateActionBar();
        }

        @Override
        public void onDrawerStateChanged(int newState) {
            mDrawerToggle.onDrawerStateChanged(newState);
        }
    };

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        mDrawerToggle.syncState();
    }

    public void updateActionBar() {
        final FragmentManager fm = getFragmentManager();
        final ActionBar actionBar = getActionBar();

        actionBar.setDisplayShowHomeEnabled(true);
        actionBar.setDisplayHomeAsUpEnabled(true);

        if (mDrawerLayout.isDrawerOpen(mRootsList)) {
            actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
            actionBar.setIcon(new ColorDrawable());

            if (mAction == ACTION_OPEN) {
                actionBar.setTitle(R.string.title_open);
            } else if (mAction == ACTION_CREATE) {
                actionBar.setTitle(R.string.title_save);
            }

        } else {
            actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
            if (mCurrentRoot != null) {
                actionBar.setIcon(mCurrentRoot.icon);
            }
            actionBar.setTitle(null);
            actionBar.setListNavigationCallbacks(mSortAdapter, mSortListener);

            if (fm.getBackStackEntryCount() > 0) {
                mDrawerToggle.setDrawerIndicatorEnabled(false);
            } else {
                mDrawerToggle.setDrawerIndicatorEnabled(true);
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.activity, menu);

        final MenuItem searchMenu = menu.findItem(R.id.menu_search);
        mSearchView = (SearchView) searchMenu.getActionView();
        mSearchView.setOnQueryTextListener(new OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                // TODO: clear existing directory stack?
                final Uri searchUri = DocumentsContract.buildSearchUri(mCurrentDir, query);
                DirectoryFragment.show(getFragmentManager(), searchUri, query, true);
                mSearchView.setIconified(true);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                return false;
            }
        });

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        final MenuItem createDir = menu.findItem(R.id.menu_create_dir);
        createDir.setVisible(mAction == ACTION_CREATE);
        createDir.setEnabled(mCurrentSupportsCreate);

        // TODO: close any search in-progress when hiding
        final MenuItem search = menu.findItem(R.id.menu_search);
        search.setVisible(mCurrentSupportsSearch);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mDrawerToggle.onOptionsItemSelected(item)) {
            return true;
        }

        final int id = item.getItemId();
        if (id == android.R.id.home) {
            getFragmentManager().popBackStack();
            updateActionBar();
            return true;
        } else if (id == R.id.menu_create_dir) {
            CreateDirectoryFragment.show(getFragmentManager());
            return true;
        } else if (id == R.id.menu_search) {
            return false;
        }
        return super.onOptionsItemSelected(item);
    }

    private OnBackStackChangedListener mStackListener = new OnBackStackChangedListener() {
        @Override
        public void onBackStackChanged() {
            updateActionBar();
        }
    };

    // TODO: support additional sort orders
    private BaseAdapter mSortAdapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return 2;
        }

        @Override
        public Object getItem(int position) {
            switch (position) {
                case 0:
                    return getText(R.string.sort_name);
                case 1:
                    return getText(R.string.sort_date);
                default:
                    return null;
            }
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_title, parent, false);
            }

            final TextView title = (TextView) convertView.findViewById(android.R.id.title);
            final TextView summary = (TextView) convertView.findViewById(android.R.id.summary);

            final FragmentManager fm = getFragmentManager();
            final int count = fm.getBackStackEntryCount();
            if (count > 0) {
                title.setText(fm.getBackStackEntryAt(count - 1).getBreadCrumbTitle());
            } else if (mCurrentRoot != null) {
                title.setText(mCurrentRoot.title);
            } else {
                title.setText(null);
            }

            summary.setText((String) getItem(position));

            return convertView;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(android.R.layout.simple_dropdown_item_1line, parent, false);
            }

            final TextView text1 = (TextView) convertView.findViewById(android.R.id.text1);
            text1.setText((String) getItem(position));

            return convertView;
        }
    };

    private OnNavigationListener mSortListener = new OnNavigationListener() {
        @Override
        public boolean onNavigationItemSelected(int itemPosition, long itemId) {
            // TODO: request updated sort order
            return true;
        }
    };

    public DisplayState getDisplayState() {
        return mDisplayState;
    }

    public void onDirectoryChanged(Uri uri, int flags) {
        mCurrentDir = uri;
        mCurrentSupportsCreate = (flags & DocumentsContract.FLAG_SUPPORTS_CREATE) != 0;
        mCurrentSupportsSearch = (flags & DocumentsContract.FLAG_SUPPORTS_SEARCH) != 0;

        if (mAction == ACTION_CREATE) {
            final FragmentManager fm = getFragmentManager();
            SaveFragment.get(fm).setSaveEnabled(mCurrentSupportsCreate);
        }

        invalidateOptionsMenu();
    }

    public void onDocumentPicked(Document doc) {
        final FragmentManager fm = getFragmentManager();
        if (DocumentsContract.MIME_TYPE_DIRECTORY.equals(doc.mimeType)) {
            // Nested directory picked, recurse using new fragment
            DirectoryFragment.show(fm, doc.uri, doc.displayName, true);
        } else if (mAction == ACTION_OPEN) {
            // Explicit file picked, return
            onFinished(doc.uri);
        } else if (mAction == ACTION_CREATE) {
            // Overwrite current filename
            SaveFragment.get(fm).setDisplayName(doc.displayName);
        }
    }

    public void onDocumentsPicked(List<Document> docs) {
        final int size = docs.size();
        final Uri[] uris = new Uri[size];
        for (int i = 0; i < size; i++) {
            uris[i] = docs.get(i).uri;
        }
        onFinished(uris);
    }

    public void onSaveRequested(String mimeType, String displayName) {
        final ContentValues values = new ContentValues();
        values.put(DocumentColumns.MIME_TYPE, mimeType);
        values.put(DocumentColumns.DISPLAY_NAME, displayName);

        // TODO: handle errors from remote side
        final Uri uri = getContentResolver().insert(mCurrentDir, values);
        onFinished(uri);
    }

    private void onFinished(Uri... uris) {
        Log.d(TAG, "onFinished() " + Arrays.toString(uris));

        final Intent intent = new Intent();
        if (uris.length == 1) {
            intent.setData(uris[0]);
        } else if (uris.length > 1) {
            final ContentResolver resolver = getContentResolver();
            final ClipData clipData = new ClipData(null, mAcceptMimes, new ClipData.Item(uris[0]));
            for (int i = 1; i < uris.length; i++) {
                clipData.addItem(new ClipData.Item(uris[i]));
            }
            intent.setClipData(clipData);
        }

        // TODO: omit WRITE and PERSIST for GET_CONTENT
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_PERSIST_GRANT_URI_PERMISSION);

        setResult(Activity.RESULT_OK, intent);
        finish();
    }

    public static class DisplayState {
        public int mode;
        public int sortBy;
        public boolean allowMultiple;

        public static final int MODE_LIST = 0;
        public static final int MODE_GRID = 1;

        public static final int SORT_BY_NAME = 0;
        public static final int SORT_BY_DATE = 1;
    }

    public static class Root {
        public int rootType;
        public Uri uri;
        public Drawable icon;
        public String title;
        public String summary;

        public static Root fromCursor(Context context, ProviderInfo info, Cursor cursor) {
            final Root root = new Root();

            root.rootType = cursor.getInt(cursor.getColumnIndex(RootColumns.ROOT_TYPE));
            root.uri = DocumentsContract.buildDocumentUri(
                    info.authority, cursor.getString(cursor.getColumnIndex(RootColumns.GUID)));

            final PackageManager pm = context.getPackageManager();
            final int icon = cursor.getInt(cursor.getColumnIndex(RootColumns.ICON));
            if (icon != 0) {
                try {
                    root.icon = pm.getResourcesForApplication(info.applicationInfo)
                            .getDrawable(icon);
                } catch (NotFoundException e) {
                    throw new RuntimeException(e);
                } catch (NameNotFoundException e) {
                    throw new RuntimeException(e);
                }
            } else {
                root.icon = info.loadIcon(pm);
            }

            root.title = cursor.getString(cursor.getColumnIndex(RootColumns.TITLE));
            if (root.title == null) {
                root.title = info.loadLabel(pm).toString();
            }

            root.summary = cursor.getString(cursor.getColumnIndex(RootColumns.SUMMARY));

            return root;
        }
    }

    public static class Document {
        public Uri uri;
        public String mimeType;
        public String displayName;

        public static Document fromCursor(String authority, Cursor cursor) {
            final Document doc = new Document();
            final String guid = getCursorString(cursor, DocumentColumns.GUID);
            doc.uri = DocumentsContract.buildDocumentUri(authority, guid);
            doc.mimeType = getCursorString(cursor, DocumentColumns.MIME_TYPE);
            doc.displayName = getCursorString(cursor, DocumentColumns.DISPLAY_NAME);
            return doc;
        }

        public static Document fromUri(ContentResolver resolver, Uri uri) {
            final Document doc = new Document();
            doc.uri = uri;

            final Cursor cursor = resolver.query(uri, null, null, null, null);
            try {
                if (!cursor.moveToFirst()) {
                    throw new IllegalArgumentException("Missing details for " + uri);
                }
                doc.mimeType = getCursorString(cursor, DocumentColumns.MIME_TYPE);
                doc.displayName = getCursorString(cursor, DocumentColumns.DISPLAY_NAME);
            } finally {
                cursor.close();
            }

            return doc;
        }
    }

    public static boolean mimeMatches(String filter, String[] tests) {
        for (String test : tests) {
            if (mimeMatches(filter, test)) {
                return true;
            }
        }
        return false;
    }

    public static boolean mimeMatches(String filter, String test) {
        if (filter.equals(test)) {
            return true;
        } else if ("*/*".equals(filter)) {
            return true;
        } else if (filter.endsWith("/*")) {
            return filter.regionMatches(0, test, 0, filter.indexOf('/'));
        } else {
            return false;
        }
    }

    public static Drawable resolveDocumentIcon(Context context, String mimeType) {
        // TODO: allow backends to provide custom MIME icons
        if (DocumentsContract.MIME_TYPE_DIRECTORY.equals(mimeType)) {
            return context.getResources().getDrawable(R.drawable.ic_dir);
        } else {
            final PackageManager pm = context.getPackageManager();
            final Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setType(mimeType);

            final ResolveInfo info = pm.resolveActivity(
                    intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (info != null) {
                return info.loadIcon(pm);
            } else {
                return null;
            }
        }
    }

    /**
     * Gather roots from all known storage providers.
     */
    private void updateRoots() {
        mRoots.clear();

        final List<ProviderInfo> providers = getPackageManager()
                .queryContentProviders(null, -1, PackageManager.GET_META_DATA);
        for (ProviderInfo info : providers) {
            if (info.metaData != null
                    && info.metaData.containsKey(DocumentsContract.META_DATA_DOCUMENT_PROVIDER)) {
                // TODO: populate roots on background thread, and cache results
                final Uri uri = DocumentsContract.buildRootsUri(info.authority);
                final Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                try {
                    while (cursor.moveToNext()) {
                        mRoots.add(Root.fromCursor(this, info, cursor));
                    }
                } finally {
                    cursor.close();
                }
            }
        }
    }

    private OnItemClickListener mRootsListener = new OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            // Clear entire backstack and start in new root
            final FragmentManager fm = getFragmentManager();
            while (fm.getBackStackEntryCount() > 0) {
                fm.popBackStackImmediate();
            }

            mCurrentRoot = mRootsAdapter.getItem(position);
            DirectoryFragment.show(
                    getFragmentManager(), mCurrentRoot.uri, mCurrentRoot.title, false);

            mDrawerLayout.closeDrawers();
        }
    };

    public static class RootsAdapter extends ArrayAdapter<Root> {
        public RootsAdapter(Context context, List<Root> list) {
            super(context, android.R.layout.simple_list_item_1, list);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_root, parent, false);
            }

            final ImageView icon = (ImageView) convertView.findViewById(android.R.id.icon);
            final TextView title = (TextView) convertView.findViewById(android.R.id.title);
            final TextView summary = (TextView) convertView.findViewById(android.R.id.summary);

            final Root root = getItem(position);
            icon.setImageDrawable(root.icon);
            title.setText(root.title);

            summary.setText(root.summary);
            summary.setVisibility(root.summary != null ? View.VISIBLE : View.GONE);

            return convertView;
        }
    }

    public static class CreateDirectoryFragment extends DialogFragment {
        public static void show(FragmentManager fm) {
            final CreateDirectoryFragment dialog = new CreateDirectoryFragment();
            dialog.show(fm, TAG_CREATE_DIRECTORY);
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Context context = getActivity();
            final ContentResolver resolver = context.getContentResolver();

            final AlertDialog.Builder builder = new AlertDialog.Builder(context);
            final LayoutInflater dialogInflater = LayoutInflater.from(builder.getContext());

            final View view = dialogInflater.inflate(R.layout.dialog_create_dir, null, false);
            final EditText text1 = (EditText)view.findViewById(android.R.id.text1);

            builder.setTitle(R.string.menu_create_dir);
            builder.setView(view);

            builder.setPositiveButton(android.R.string.ok, new OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    final String displayName = text1.getText().toString();

                    final ContentValues values = new ContentValues();
                    values.put(DocumentColumns.MIME_TYPE, DocumentsContract.MIME_TYPE_DIRECTORY);
                    values.put(DocumentColumns.DISPLAY_NAME, displayName);

                    // TODO: handle errors from remote side
                    final DocumentsActivity activity = (DocumentsActivity) getActivity();
                    final Uri uri = resolver.insert(activity.mCurrentDir, values);

                    // Navigate into newly created child
                    final Document doc = Document.fromUri(resolver, uri);
                    activity.onDocumentPicked(doc);
                }
            });
            builder.setNegativeButton(android.R.string.cancel, null);

            return builder.create();
        }
    }
}
