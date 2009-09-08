/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.PaintDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * Displays a list of all activities which can be performed
 * for a given intent. Launches when clicked.
 *
 */
public abstract class LauncherActivity extends ListActivity {
    Intent mIntent;
    PackageManager mPackageManager;
    IconResizer mIconResizer;
    
    /**
     * An item in the list
     */
    public static class ListItem {
        public ResolveInfo resolveInfo;
        public CharSequence label;
        public Drawable icon;
        public String packageName;
        public String className;
        public Bundle extras;
        
        ListItem(PackageManager pm, ResolveInfo resolveInfo, IconResizer resizer) {
            this.resolveInfo = resolveInfo;
            label = resolveInfo.loadLabel(pm);
            ComponentInfo ci = resolveInfo.activityInfo;
            if (ci == null) ci = resolveInfo.serviceInfo;
            if (label == null && ci != null) {
                label = resolveInfo.activityInfo.name;
            }
            
            if (resizer != null) {
                icon = resizer.createIconThumbnail(resolveInfo.loadIcon(pm));
            }
            packageName = ci.applicationInfo.packageName;
            className = ci.name;
        }

        public ListItem() {
        }
    }

    /**
     * Adapter which shows the set of activities that can be performed for a given intent.
     */
    private class ActivityAdapter extends BaseAdapter implements Filterable {
        private final Object lock = new Object();
        private ArrayList<ListItem> mOriginalValues;

        protected final IconResizer mIconResizer;
        protected final LayoutInflater mInflater;

        protected List<ListItem> mActivitiesList;

        private Filter mFilter;
        
        public ActivityAdapter(IconResizer resizer) {
            mIconResizer = resizer;
            mInflater = (LayoutInflater) LauncherActivity.this.getSystemService(
                    Context.LAYOUT_INFLATER_SERVICE);
            mActivitiesList = makeListItems();
        }

        public Intent intentForPosition(int position) {
            if (mActivitiesList == null) {
                return null;
            }

            Intent intent = new Intent(mIntent);
            ListItem item = mActivitiesList.get(position);
            intent.setClassName(item.packageName, item.className);
            if (item.extras != null) {
                intent.putExtras(item.extras);
            }
            return intent;
        }

        public ListItem itemForPosition(int position) {
            if (mActivitiesList == null) {
                return null;
            }

            return mActivitiesList.get(position);
        }

        public int getCount() {
            return mActivitiesList != null ? mActivitiesList.size() : 0;
        }

        public Object getItem(int position) {
            return position;
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View view;
            if (convertView == null) {
                view = mInflater.inflate(
                        com.android.internal.R.layout.activity_list_item_2, parent, false);
            } else {
                view = convertView;
            }
            bindView(view, mActivitiesList.get(position));
            return view;
        }

        private void bindView(View view, ListItem item) {
            TextView text = (TextView) view;
            text.setText(item.label);
            if (item.icon == null) {
                item.icon = mIconResizer.createIconThumbnail(
                        item.resolveInfo.loadIcon(getPackageManager()));
            }
            text.setCompoundDrawablesWithIntrinsicBounds(item.icon, null, null, null);
        }
        
        public Filter getFilter() {
            if (mFilter == null) {
                mFilter = new ArrayFilter();
            }
            return mFilter;
        }
        
        /**
         * An array filters constrains the content of the array adapter with a prefix. Each
         * item that does not start with the supplied prefix is removed from the list.
         */
        private class ArrayFilter extends Filter {
            @Override
            protected FilterResults performFiltering(CharSequence prefix) {
                FilterResults results = new FilterResults();

                if (mOriginalValues == null) {
                    synchronized (lock) {
                        mOriginalValues = new ArrayList<ListItem>(mActivitiesList);
                    }
                }

                if (prefix == null || prefix.length() == 0) {
                    synchronized (lock) {
                        ArrayList<ListItem> list = new ArrayList<ListItem>(mOriginalValues);
                        results.values = list;
                        results.count = list.size();
                    }
                } else {
                    final String prefixString = prefix.toString().toLowerCase();

                    ArrayList<ListItem> values = mOriginalValues;
                    int count = values.size();

                    ArrayList<ListItem> newValues = new ArrayList<ListItem>(count);

                    for (int i = 0; i < count; i++) {
                        ListItem item = values.get(i);

                        String[] words = item.label.toString().toLowerCase().split(" ");
                        int wordCount = words.length;

                        for (int k = 0; k < wordCount; k++) {
                            final String word = words[k];

                            if (word.startsWith(prefixString)) {
                                newValues.add(item);
                                break;
                            }
                        }
                    }

                    results.values = newValues;
                    results.count = newValues.size();
                }

                return results;
            }

            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                //noinspection unchecked
                mActivitiesList = (List<ListItem>) results.values;
                if (results.count > 0) {
                    notifyDataSetChanged();
                } else {
                    notifyDataSetInvalidated();
                }
            }
        }
    }
        
    /**
     * Utility class to resize icons to match default icon size.  
     */
    public class IconResizer {
        // Code is borrowed from com.android.launcher.Utilities. 
        private int mIconWidth = -1;
        private int mIconHeight = -1;

        private final Rect mOldBounds = new Rect();
        private Canvas mCanvas = new Canvas();
        
        public IconResizer() {
            mCanvas.setDrawFilter(new PaintFlagsDrawFilter(Paint.DITHER_FLAG,
                    Paint.FILTER_BITMAP_FLAG));
            
            final Resources resources = LauncherActivity.this.getResources();
            mIconWidth = mIconHeight = (int) resources.getDimension(
                    android.R.dimen.app_icon_size);
        }

        /**
         * Returns a Drawable representing the thumbnail of the specified Drawable.
         * The size of the thumbnail is defined by the dimension
         * android.R.dimen.launcher_application_icon_size.
         *
         * This method is not thread-safe and should be invoked on the UI thread only.
         *
         * @param icon The icon to get a thumbnail of.
         *
         * @return A thumbnail for the specified icon or the icon itself if the
         *         thumbnail could not be created. 
         */
        public Drawable createIconThumbnail(Drawable icon) {
            int width = mIconWidth;
            int height = mIconHeight;

            final int iconWidth = icon.getIntrinsicWidth();
            final int iconHeight = icon.getIntrinsicHeight();

            if (icon instanceof PaintDrawable) {
                PaintDrawable painter = (PaintDrawable) icon;
                painter.setIntrinsicWidth(width);
                painter.setIntrinsicHeight(height);
            }

            if (width > 0 && height > 0) {
                if (width < iconWidth || height < iconHeight) {
                    final float ratio = (float) iconWidth / iconHeight;

                    if (iconWidth > iconHeight) {
                        height = (int) (width / ratio);
                    } else if (iconHeight > iconWidth) {
                        width = (int) (height * ratio);
                    }

                    final Bitmap.Config c = icon.getOpacity() != PixelFormat.OPAQUE ?
                                Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
                    final Bitmap thumb = Bitmap.createBitmap(mIconWidth, mIconHeight, c);
                    final Canvas canvas = mCanvas;
                    canvas.setBitmap(thumb);
                    // Copy the old bounds to restore them later
                    // If we were to do oldBounds = icon.getBounds(),
                    // the call to setBounds() that follows would
                    // change the same instance and we would lose the
                    // old bounds
                    mOldBounds.set(icon.getBounds());
                    final int x = (mIconWidth - width) / 2;
                    final int y = (mIconHeight - height) / 2;
                    icon.setBounds(x, y, x + width, y + height);
                    icon.draw(canvas);
                    icon.setBounds(mOldBounds);
                    icon = new BitmapDrawable(getResources(), thumb);
                } else if (iconWidth < width && iconHeight < height) {
                    final Bitmap.Config c = Bitmap.Config.ARGB_8888;
                    final Bitmap thumb = Bitmap.createBitmap(mIconWidth, mIconHeight, c);
                    final Canvas canvas = mCanvas;
                    canvas.setBitmap(thumb);
                    mOldBounds.set(icon.getBounds());
                    final int x = (width - iconWidth) / 2;
                    final int y = (height - iconHeight) / 2;
                    icon.setBounds(x, y, x + iconWidth, y + iconHeight);
                    icon.draw(canvas);
                    icon.setBounds(mOldBounds);
                    icon = new BitmapDrawable(getResources(), thumb);
                }
            }

            return icon;
        }
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        
        mPackageManager = getPackageManager();
    
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setProgressBarIndeterminateVisibility(true);
        onSetContentView();
            
        mIconResizer = new IconResizer();
        
        mIntent = new Intent(getTargetIntent());
        mIntent.setComponent(null);
        mAdapter = new ActivityAdapter(mIconResizer);
        
        setListAdapter(mAdapter);
        getListView().setTextFilterEnabled(true);
        
        setProgressBarIndeterminateVisibility(false);
    }

    /**
     * Override to call setContentView() with your own content view to
     * customize the list layout.
     */
    protected void onSetContentView() {
        setContentView(com.android.internal.R.layout.activity_list);
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        Intent intent = intentForPosition(position);
        startActivity(intent);
    }
    
    /**
     * Return the actual Intent for a specific position in our
     * {@link android.widget.ListView}.
     * @param position The item whose Intent to return
     */
    protected Intent intentForPosition(int position) {
        ActivityAdapter adapter = (ActivityAdapter) mAdapter;
        return adapter.intentForPosition(position);
    }
    
    /**
     * Return the {@link ListItem} for a specific position in our
     * {@link android.widget.ListView}.
     * @param position The item to return
     */
    protected ListItem itemForPosition(int position) {
        ActivityAdapter adapter = (ActivityAdapter) mAdapter;
        return adapter.itemForPosition(position);
    }
    
    /**
     * Get the base intent to use when running
     * {@link PackageManager#queryIntentActivities(Intent, int)}.
     */
    protected Intent getTargetIntent() {
        return new Intent();
    }

    /**
     * Perform query on package manager for list items.  The default
     * implementation queries for activities.
     */
    protected List<ResolveInfo> onQueryPackageManager(Intent queryIntent) {
        return mPackageManager.queryIntentActivities(queryIntent, /* no flags */ 0);
    }
    
    /**
     * Perform the query to determine which results to show and return a list of them.
     */
    public List<ListItem> makeListItems() {
        // Load all matching activities and sort correctly
        List<ResolveInfo> list = onQueryPackageManager(mIntent);
        Collections.sort(list, new ResolveInfo.DisplayNameComparator(mPackageManager));
        
        ArrayList<ListItem> result = new ArrayList<ListItem>(list.size());
        int listSize = list.size();
        for (int i = 0; i < listSize; i++) {
            ResolveInfo resolveInfo = list.get(i);
            result.add(new ListItem(mPackageManager, resolveInfo, null));
        }

        return result;
    }
}
