/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.benchmark.ui;

import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.TextView;

import com.android.benchmark.R;

import java.lang.ref.WeakReference;
import java.util.HashMap;

public class ImageListViewScrollActivity extends ListViewScrollActivity {

    private static final int LIST_SIZE = 100;

    private static final int[] IMG_RES_ID = new int[]{
            R.drawable.img1,
            R.drawable.img2,
            R.drawable.img3,
            R.drawable.img4,
            R.drawable.img1,
            R.drawable.img2,
            R.drawable.img3,
            R.drawable.img4,
            R.drawable.img1,
            R.drawable.img2,
            R.drawable.img3,
            R.drawable.img4,
            R.drawable.img1,
            R.drawable.img2,
            R.drawable.img3,
            R.drawable.img4,
    };

    private static Bitmap[] mBitmapCache = new Bitmap[IMG_RES_ID.length];

    private static final String[] WORDS = Utils.buildStringList(LIST_SIZE);

    private HashMap<View, BitmapWorkerTask> mInFlight = new HashMap<>();

    @Override
    protected ListAdapter createListAdapter() {
        return new ImageListAdapter();
    }

    @Override
    protected String getName() {
        return getString(R.string.image_list_view_scroll_name);
    }

    class BitmapWorkerTask extends AsyncTask<Integer, Void, Bitmap> {
        private final WeakReference<ImageView> imageViewReference;
        private int data = 0;
        private int cacheIdx = 0;
        volatile boolean cancelled = false;

        public BitmapWorkerTask(ImageView imageView, int cacheIdx) {
            // Use a WeakReference to ensure the ImageView can be garbage collected
            imageViewReference = new WeakReference<>(imageView);
            this.cacheIdx = cacheIdx;
        }

        // Decode image in background.
        @Override
        protected Bitmap doInBackground(Integer... params) {
            data = params[0];
            return Utils.decodeSampledBitmapFromResource(getResources(), data, 100, 100);
        }

        // Once complete, see if ImageView is still around and set bitmap.
        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (bitmap != null) {
                final ImageView imageView = imageViewReference.get();
                if (imageView != null) {
                    if (!cancelled) {
                        imageView.setImageBitmap(bitmap);
                    }
                    mBitmapCache[cacheIdx] = bitmap;
                }
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        for (int i = 0; i < mBitmapCache.length; i++) {
            mBitmapCache[i] = null;
        }
    }

    class ImageListAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return LIST_SIZE;
        }

        @Override
        public Object getItem(int postition) {
            return null;
        }

        @Override
        public long getItemId(int postition) {
            return postition;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getBaseContext())
                        .inflate(R.layout.image_scroll_list_item, parent, false);
            }

            ImageView imageView = (ImageView) convertView.findViewById(R.id.image_scroll_image);
            BitmapWorkerTask inFlight = mInFlight.get(convertView);
            if (inFlight != null) {
                inFlight.cancelled = true;
                mInFlight.remove(convertView);
            }

            int cacheIdx = position % IMG_RES_ID.length;
            Bitmap bitmap = mBitmapCache[(cacheIdx)];
            if (bitmap == null) {
                BitmapWorkerTask bitmapWorkerTask = new BitmapWorkerTask(imageView, cacheIdx);
                bitmapWorkerTask.execute(IMG_RES_ID[(cacheIdx)]);
                mInFlight.put(convertView, bitmapWorkerTask);
            }

            imageView.setImageBitmap(bitmap);

            TextView textView = (TextView) convertView.findViewById(R.id.image_scroll_text);
            textView.setText(WORDS[position]);

            return convertView;
        }
    }
}
