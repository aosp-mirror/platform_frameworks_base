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

import android.app.ActionBar;
import android.app.ActionBar.OnNavigationListener;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.content.ClipData;
import android.content.ComponentName;
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
import android.database.Cursor;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.DocumentColumns;
import android.support.v4.app.ActionBarDrawerToggle;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v4.widget.DrawerLayout.DrawerListener;
import android.util.Log;
import android.util.Pair;
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
import android.widget.Toast;

import com.android.documentsui.model.Document;
import com.android.documentsui.model.DocumentsProviderInfo;
import com.android.documentsui.model.DocumentsProviderInfo.Icon;
import com.android.documentsui.model.Root;
import com.google.android.collect.Lists;
import com.google.android.collect.Maps;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

public class DocumentsActivity extends Activity {
    public static final String TAG = "Documents";

    // TODO: share backend root cache with recents provider

    private static final String TAG_CREATE_DIRECTORY = "create_directory";

    private static final int ACTION_OPEN = 1;
    private static final int ACTION_CREATE = 2;

    private int mAction;

    private SearchView mSearchView;

    private DrawerLayout mDrawerLayout;
    private ActionBarDrawerToggle mDrawerToggle;

    private Root mCurrentRoot;

    /** Map from authority to cached info */
    private static HashMap<String, DocumentsProviderInfo> sProviders = Maps.newHashMap();
    /** Map from (authority+rootId) to cached info */
    private static HashMap<Pair<String, String>, Root> sRoots = Maps.newHashMap();

    // TODO: remove once adapter split by type
    private static ArrayList<Root> sRootsList = Lists.newArrayList();

    private static Root sRecentOpenRoot;

    private RootsAdapter mRootsAdapter;
    private ListView mRootsList;

    private final DisplayState mDisplayState = new DisplayState();

    private LinkedList<Document> mStack = new LinkedList<Document>();

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        final Intent intent = getIntent();
        final String action = intent.getAction();
        if (Intent.ACTION_OPEN_DOCUMENT.equals(action)) {
            mAction = ACTION_OPEN;
            mDisplayState.allowMultiple = intent.getBooleanExtra(
                    Intent.EXTRA_ALLOW_MULTIPLE, false);
        } else if (Intent.ACTION_CREATE_DOCUMENT.equals(action)) {
            mAction = ACTION_CREATE;
            mDisplayState.allowMultiple = false;
        }

        if (intent.hasExtra(Intent.EXTRA_MIME_TYPES)) {
            mDisplayState.acceptMimes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
        } else {
            mDisplayState.acceptMimes = new String[] { intent.getType() };
        }

        if (MimePredicate.mimeMatches("image/*", mDisplayState.acceptMimes)) {
            mDisplayState.mode = DisplayState.MODE_GRID;
        } else {
            mDisplayState.mode = DisplayState.MODE_LIST;
        }

        setResult(Activity.RESULT_CANCELED);
        setContentView(R.layout.activity);

        if (mAction == ACTION_CREATE) {
            final String mimeType = getIntent().getType();
            final String title = getIntent().getStringExtra(Intent.EXTRA_TITLE);
            SaveFragment.show(getFragmentManager(), mimeType, title);
        }

        mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
        mRootsAdapter = new RootsAdapter(this, sRootsList);
        mRootsList = (ListView) findViewById(R.id.roots_list);
        mRootsList.setAdapter(mRootsAdapter);
        mRootsList.setOnItemClickListener(mRootsListener);

        mDrawerToggle = new ActionBarDrawerToggle(this, mDrawerLayout,
                R.drawable.ic_drawer, R.string.drawer_open, R.string.drawer_close);

        mDrawerLayout.setDrawerListener(mDrawerListener);
        mDrawerLayout.setDrawerShadow(R.drawable.drawer_shadow, GravityCompat.START);

        mDrawerLayout.openDrawer(mRootsList);

        updateRoots();

        // Restore last stack for calling package
        // TODO: move into async loader
        final String packageName = getCallingPackage();
        final Cursor cursor = getContentResolver()
                .query(RecentsProvider.buildResume(packageName), null, null, null, null);
        try {
            if (cursor.moveToFirst()) {
                final String rawStack = cursor.getString(
                        cursor.getColumnIndex(RecentsProvider.COL_PATH));
                restoreStack(rawStack);
            }
        } finally {
            cursor.close();
        }

        // Start in recents if no restored stack
        if (mStack.isEmpty()) {
            onRootPicked(sRecentOpenRoot);
        }

        updateDirectoryFragment();
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
            final Root root = getCurrentRoot();
            actionBar.setIcon(root != null ? root.icon : null);

            if (getCurrentRoot().isRecents) {
                actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
                actionBar.setTitle(root.title);
            } else {
                actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
                actionBar.setTitle(null);
                actionBar.setListNavigationCallbacks(mSortAdapter, mSortListener);
                actionBar.setSelectedNavigationItem(mDisplayState.sortOrder);
            }

            if (mStack.size() > 1) {
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
                // TODO: use second directory stack for searches?
                final Document cwd = getCurrentDirectory();
                final Document searchDoc = Document.fromSearch(cwd.uri, query);
                onDocumentPicked(searchDoc);
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

        final Document cwd = getCurrentDirectory();

        final MenuItem createDir = menu.findItem(R.id.menu_create_dir);
        createDir.setVisible(mAction == ACTION_CREATE);
        createDir.setEnabled(cwd != null && cwd.isCreateSupported());

        // TODO: close any search in-progress when hiding
        final MenuItem search = menu.findItem(R.id.menu_search);
        search.setVisible(cwd != null && cwd.isSearchSupported());

        if (mAction == ACTION_CREATE) {
            final FragmentManager fm = getFragmentManager();
            SaveFragment.get(fm).setSaveEnabled(cwd != null && cwd.isCreateSupported());
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mDrawerToggle.onOptionsItemSelected(item)) {
            return true;
        }

        final int id = item.getItemId();
        if (id == android.R.id.home) {
            onBackPressed();
            return true;
        } else if (id == R.id.menu_create_dir) {
            CreateDirectoryFragment.show(getFragmentManager());
            return true;
        } else if (id == R.id.menu_search) {
            return false;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        final int size = mStack.size();
        if (size > 1) {
            mStack.pop();
            updateDirectoryFragment();
        } else if (size == 1 && !mDrawerLayout.isDrawerOpen(mRootsList)) {
            // TODO: open root drawer once we can capture back key
            super.onBackPressed();
        } else {
            super.onBackPressed();
        }
    }

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

            final Document cwd = getCurrentDirectory();
            if (cwd != null) {
                title.setText(cwd.displayName);
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
            mDisplayState.sortOrder = itemPosition;
            DirectoryFragment.get(getFragmentManager()).updateSortOrder();
            return true;
        }
    };

    public Root getCurrentRoot() {
        return mCurrentRoot;
    }

    public Document getCurrentDirectory() {
        return mStack.peek();
    }

    public DisplayState getDisplayState() {
        return mDisplayState;
    }

    private void updateDirectoryFragment() {
        final FragmentManager fm = getFragmentManager();
        final Document cwd = getCurrentDirectory();
        if (cwd != null) {
            DirectoryFragment.show(fm, cwd.uri);
        }
        updateActionBar();
        invalidateOptionsMenu();
        dumpStack();
    }

    public void onRootPicked(Root root) {
        // Clear entire backstack and start in new root
        mStack.clear();
        mCurrentRoot = root;
        onDocumentPicked(Document.fromRoot(getContentResolver(), root));
    }

    public void onDocumentPicked(Document doc) {
        final FragmentManager fm = getFragmentManager();
        if (DocumentsContract.MIME_TYPE_DIRECTORY.equals(doc.mimeType)) {
            mStack.push(doc);
            updateDirectoryFragment();
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
        // TODO: handle overwrite by using last-selected GUID

        final ContentValues values = new ContentValues();
        values.put(DocumentColumns.MIME_TYPE, mimeType);
        values.put(DocumentColumns.DISPLAY_NAME, displayName);

        final Document cwd = getCurrentDirectory();
        final Uri childUri = getContentResolver().insert(cwd.uri, values);
        if (childUri != null) {
            onFinished(childUri);
        } else {
            Toast.makeText(this, R.string.save_error, Toast.LENGTH_SHORT).show();
        }
    }

    private String saveStack() {
        if (mCurrentRoot.isRecents) return null;

        final JSONArray stack = new JSONArray();
        for (int i = 0; i < mStack.size(); i++) {
            stack.put(mStack.get(i).uri);
        }
        return stack.toString();
    }

    private void restoreStack(String rawStack) {
        Log.d(TAG, "restoreStack: " + rawStack);
        mStack.clear();

        if (rawStack == null) return;
        try {
            final JSONArray stack = new JSONArray(rawStack);
            for (int i = 0; i < stack.length(); i++) {
                final Uri uri = Uri.parse(stack.getString(i));
                final Document doc = Document.fromUri(getContentResolver(), uri);
                mStack.add(doc);
            }
        } catch (JSONException e) {
            Log.w(TAG, "Failed to decode stack", e);
        }

        // TODO: handle roots that have gone missing
        final Document cwd = getCurrentDirectory();
        if (cwd != null) {
            final String authority = cwd.uri.getAuthority();
            final String rootId = DocumentsContract.getRootId(cwd.uri);
            mCurrentRoot = sRoots.get(Pair.create(authority, rootId));
        }
    }

    private void onFinished(Uri... uris) {
        Log.d(TAG, "onFinished() " + Arrays.toString(uris));

        final ContentResolver resolver = getContentResolver();
        final ContentValues values = new ContentValues();

        final String stack = saveStack();
        if (mAction == ACTION_CREATE) {
            // Remember stack for last create
            values.clear();
            values.put(RecentsProvider.COL_PATH, stack);
            resolver.insert(RecentsProvider.buildRecentCreate(), values);

        } else if (mAction == ACTION_OPEN) {
            // Remember opened items
            for (Uri uri : uris) {
                values.clear();
                values.put(RecentsProvider.COL_URI, uri.toString());
                resolver.insert(RecentsProvider.buildRecentOpen(), values);
            }
        }

        // Remember location for next app launch
        final String packageName = getCallingPackage();
        values.clear();
        values.put(RecentsProvider.COL_PATH, stack);
        resolver.insert(RecentsProvider.buildResume(packageName), values);

        final Intent intent = new Intent();
        if (uris.length == 1) {
            intent.setData(uris[0]);
        } else if (uris.length > 1) {
            final ClipData clipData = new ClipData(
                    null, mDisplayState.acceptMimes, new ClipData.Item(uris[0]));
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
        public int mode = MODE_LIST;
        public String[] acceptMimes;
        public int sortOrder = SORT_ORDER_NAME;
        public boolean allowMultiple = false;

        public static final int MODE_LIST = 0;
        public static final int MODE_GRID = 1;

        public static final int SORT_ORDER_NAME = 0;
        public static final int SORT_ORDER_DATE = 1;
    }

    public static Drawable resolveDocumentIcon(Context context, String authority, String mimeType) {
        // Custom icons take precedence
        final DocumentsProviderInfo info = sProviders.get(authority);
        if (info != null) {
            for (Icon icon : info.customIcons) {
                if (MimePredicate.mimeMatches(icon.mimeType, mimeType)) {
                    return icon.icon;
                }
            }
        }

        if (DocumentsContract.MIME_TYPE_DIRECTORY.equals(mimeType)) {
            return context.getResources().getDrawable(R.drawable.ic_dir);
        } else {
            final PackageManager pm = context.getPackageManager();
            final Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setType(mimeType);

            final ResolveInfo activityInfo = pm.resolveActivity(
                    intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (activityInfo != null) {
                return activityInfo.loadIcon(pm);
            } else {
                return null;
            }
        }
    }

    /**
     * Gather roots from all known storage providers.
     */
    private void updateRoots() {
        sProviders.clear();
        sRoots.clear();
        sRootsList.clear();

        final Context context = this;
        final PackageManager pm = getPackageManager();

        // Create special roots, like recents
        {
            final Root root = Root.buildRecentOpen(context);
            sRootsList.add(root);
            sRecentOpenRoot = root;
        }

        // Query for other storage backends
        final List<ProviderInfo> providers = pm.queryContentProviders(
                null, -1, PackageManager.GET_META_DATA);
        for (ProviderInfo providerInfo : providers) {
            if (providerInfo.metaData != null && providerInfo.metaData.containsKey(
                    DocumentsContract.META_DATA_DOCUMENT_PROVIDER)) {
                final DocumentsProviderInfo info = DocumentsProviderInfo.parseInfo(
                        this, providerInfo);
                if (info == null) {
                    Log.w(TAG, "Missing info for " + providerInfo);
                    continue;
                }

                sProviders.put(info.providerInfo.authority, info);

                // TODO: remove deprecated customRoots flag
                // TODO: populate roots on background thread, and cache results
                final Uri uri = DocumentsContract.buildRootsUri(providerInfo.authority);
                final Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                try {
                    while (cursor.moveToNext()) {
                        final Root root = Root.fromCursor(this, info, cursor);
                        sRoots.put(Pair.create(info.providerInfo.authority, root.rootId), root);
                        sRootsList.add(root);
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
            final Root root = mRootsAdapter.getItem(position);
            onRootPicked(root);
            mDrawerLayout.closeDrawers();
        }
    };

    private void dumpStack() {
        Log.d(TAG, "Current stack:");
        for (Document doc : mStack) {
            Log.d(TAG, "--> " + doc);
        }
    }

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

                    final DocumentsActivity activity = (DocumentsActivity) getActivity();
                    final Document cwd = activity.getCurrentDirectory();

                    final Uri childUri = resolver.insert(cwd.uri, values);
                    if (childUri != null) {
                        // Navigate into newly created child
                        final Document childDoc = Document.fromUri(resolver, childUri);
                        activity.onDocumentPicked(childDoc);
                    } else {
                        Toast.makeText(context, R.string.save_error, Toast.LENGTH_SHORT).show();
                    }
                }
            });
            builder.setNegativeButton(android.R.string.cancel, null);

            return builder.create();
        }
    }
}
