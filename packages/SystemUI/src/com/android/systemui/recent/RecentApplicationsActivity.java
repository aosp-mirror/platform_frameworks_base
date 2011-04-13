/*
 * Copyright (C) 2010 The Android Open Source Project
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


package com.android.systemui.recent;

import com.android.systemui.R;

import com.android.ex.carousel.CarouselView;
import com.android.ex.carousel.CarouselViewHelper;
import com.android.ex.carousel.CarouselRS.CarouselCallback;
import com.android.ex.carousel.CarouselViewHelper.DetailTextureParameters;

import java.util.ArrayList;
import java.util.List;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.IThumbnailReceiver;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.PorterDuff;
import android.graphics.Bitmap.Config;
import android.graphics.drawable.Drawable;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.view.View.MeasureSpec;
import android.widget.TextView;

public class RecentApplicationsActivity extends Activity {
    private static final String TAG = "RecentApplicationsActivity";
    private static boolean DBG = false;
    private static final int CARD_SLOTS = 56;
    private static final int VISIBLE_SLOTS = 7;
    private static final int MAX_TASKS = VISIBLE_SLOTS * 2;

    // TODO: these should be configurable
    private static final int DETAIL_TEXTURE_MAX_WIDTH = 200;
    private static final int DETAIL_TEXTURE_MAX_HEIGHT = 80;
    private static final int TEXTURE_WIDTH = 256;
    private static final int TEXTURE_HEIGHT = 256;

    private ActivityManager mActivityManager;
    private List<RunningTaskInfo> mRunningTaskList;
    private boolean mPortraitMode = true;
    private ArrayList<ActivityDescription> mActivityDescriptions
            = new ArrayList<ActivityDescription>();
    private CarouselView mCarouselView;
    private LocalCarouselViewHelper mHelper;
    private View mNoRecentsView;
    private Bitmap mLoadingBitmap;
    private Bitmap mRecentOverlay;
    private boolean mHidden = false;
    private boolean mHiding = false;
    private DetailInfo mDetailInfo;

    /**
     * This class is a container for all items associated with the DetailView we'll
     * be drawing to a bitmap and sending to Carousel.
     *
     */
    static final class DetailInfo {
        public DetailInfo(View _view, TextView _title, TextView _desc) {
            view = _view;
            title = _title;
            description = _desc;
        }

        /**
         * Draws view into the given bitmap, if provided
         * @param bitmap
         */
        public Bitmap draw(Bitmap bitmap) {
            resizeView(view, DETAIL_TEXTURE_MAX_WIDTH, DETAIL_TEXTURE_MAX_HEIGHT);
            int desiredWidth = view.getWidth();
            int desiredHeight = view.getHeight();
            if (bitmap == null || desiredWidth != bitmap.getWidth()
                    || desiredHeight != bitmap.getHeight()) {
                bitmap = Bitmap.createBitmap(desiredWidth, desiredHeight, Config.ARGB_8888);
            }
            Canvas canvas = new Canvas(bitmap);
            view.draw(canvas);
            return bitmap;
        }

        /**
         * Force a layout pass on the given view.
         */
        private void resizeView(View view, int maxWidth, int maxHeight) {
            int widthSpec = MeasureSpec.getMode(MeasureSpec.AT_MOST)
                    | MeasureSpec.getSize(maxWidth);
            int heightSpec = MeasureSpec.getMode(MeasureSpec.AT_MOST)
                    | MeasureSpec.getSize(maxHeight);
            view.measure(widthSpec, heightSpec);
            view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
            Log.v(TAG, "RESIZED VIEW: " + view.getWidth() + ", " + view.getHeight());
        }

        public View view;
        public TextView title;
        public TextView description;
    }

    static class ActivityDescription {
        int id;
        Bitmap thumbnail; // generated by Activity.onCreateThumbnail()
        Drawable icon; // application package icon
        String label; // application package label
        CharSequence description; // generated by Activity.onCreateDescription()
        Intent intent; // launch intent for application
        Matrix matrix; // arbitrary rotation matrix to correct orientation
        int position; // position in list

        public ActivityDescription(Bitmap _thumbnail,
                Drawable _icon, String _label, String _desc, int _id, int _pos)
        {
            thumbnail = _thumbnail;
            icon = _icon;
            label = _label;
            description = _desc;
            id = _id;
            position = _pos;
        }

        public void clear() {
            icon = null;
            thumbnail = null;
            label = null;
            description = null;
            intent = null;
            matrix = null;
            id = -1;
            position = -1;
        }
    };

    private ActivityDescription findActivityDescription(int id) {
        for (int i = 0; i < mActivityDescriptions.size(); i++) {
            ActivityDescription item = mActivityDescriptions.get(i);
            if (item != null && item.id == id) {
                return item;
            }
        }
        return null;
    }

    private class LocalCarouselViewHelper extends CarouselViewHelper {
        private DetailTextureParameters mDetailParams = new DetailTextureParameters(10.0f, 20.0f);

        public LocalCarouselViewHelper(Context context) {
            super(context);
        }

        @Override
        public DetailTextureParameters getDetailTextureParameters(int id) {
            return mDetailParams;
        }

        public void onCardSelected(int n) {
            if (n < mActivityDescriptions.size()) {
                ActivityDescription item = mActivityDescriptions.get(n);
                if (item.id >= 0) {
                    // This is an active task; it should just go to the foreground.
                    final ActivityManager am = (ActivityManager)
                            getSystemService(Context.ACTIVITY_SERVICE);
                    am.moveTaskToFront(item.id, ActivityManager.MOVE_TASK_WITH_HOME);
                } else if (item.intent != null) {
                    // prepare a launch intent and send it
                    item.intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
                            | Intent.FLAG_ACTIVITY_TASK_ON_HOME);
                    try {
                        if (DBG) Log.v(TAG, "Starting intent " + item.intent);
                        startActivity(item.intent);
                        overridePendingTransition(R.anim.recent_app_enter, R.anim.recent_app_leave);
                    } catch (ActivityNotFoundException e) {
                        if (DBG) Log.w("Recent", "Unable to launch recent task", e);
                    }
                    finish();
                }
            }
        }

        @Override
        public Bitmap getTexture(final int id) {
            if (DBG) Log.v(TAG, "onRequestTexture(" + id + ")");
            ActivityDescription info;
            synchronized(mActivityDescriptions) {
                info = mActivityDescriptions.get(id);
            }
            Bitmap bitmap = null;
            if (info != null) {
                bitmap = compositeBitmap(info);
            }
            return bitmap;
        }

        @Override
        public Bitmap getDetailTexture(int n) {
            Bitmap bitmap = null;
            if (n < mActivityDescriptions.size()) {
                ActivityDescription item = mActivityDescriptions.get(n);
                mDetailInfo.title.setText(item.label);
                mDetailInfo.description.setText(item.description);
                bitmap = mDetailInfo.draw(null);
            }
            return bitmap;
        }
    };

    private Bitmap compositeBitmap(ActivityDescription info) {
        final int targetWidth = TEXTURE_WIDTH;
        final int targetHeight = TEXTURE_HEIGHT;
        final int border = 3; // inset along the edge for thumnnail content
        final int overlap = 1; // how many pixels of overlap between border and thumbnail
        final Resources res = getResources();
        if (mRecentOverlay == null) {
            mRecentOverlay = BitmapFactory.decodeResource(res, R.drawable.recent_overlay);
        }

        // Create a bitmap of the proper size/format and set the canvas to draw to it
        final Bitmap result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(result);
        canvas.setDrawFilter(new PaintFlagsDrawFilter(Paint.DITHER_FLAG, Paint.FILTER_BITMAP_FLAG));
        Paint paint = new Paint();
        paint.setFilterBitmap(false);

        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
        canvas.save();
        if (info.thumbnail != null) {
            // Draw the thumbnail
            int sourceWidth = targetWidth - 2 * (border - overlap);
            int sourceHeight = targetHeight - 2 * (border - overlap);
            final float scaleX = (float) sourceWidth / info.thumbnail.getWidth();
            final float scaleY = (float) sourceHeight / info.thumbnail.getHeight();
            canvas.translate(border * 0.5f, border * 0.5f);
            canvas.scale(scaleX, scaleY);
            canvas.drawBitmap(info.thumbnail, 0, 0, paint);
        } else {
            // Draw the Loading bitmap placeholder, TODO: Remove when RS handles blending
            final float scaleX = (float) targetWidth / mLoadingBitmap.getWidth();
            final float scaleY = (float) targetHeight / mLoadingBitmap.getHeight();
            canvas.scale(scaleX, scaleY);
            canvas.drawBitmap(mLoadingBitmap, 0, 0, paint);
        }
        canvas.restore();

        // Draw overlay
        canvas.save();
        final float scaleOverlayX = (float) targetWidth / mRecentOverlay.getWidth();
        final float scaleOverlayY = (float) targetHeight / mRecentOverlay.getHeight();
        canvas.scale(scaleOverlayX, scaleOverlayY);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        canvas.drawBitmap(mRecentOverlay, 0, 0, paint);
        canvas.restore();

        // Draw icon
        if (info.icon != null) {
            canvas.save();
            info.icon.draw(canvas);
            canvas.restore();
        }

        return result;
    }

    private final IThumbnailReceiver mThumbnailReceiver = new IThumbnailReceiver.Stub() {

        public void finished() throws RemoteException {

        }

        public void newThumbnail(final int id, final Bitmap bitmap, CharSequence description)
                throws RemoteException {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            if (DBG) Log.v(TAG, "New thumbnail for id=" + id + ", dimensions=" + w + "x" + h
                    + " description '" + description + "'");
            ActivityDescription info = findActivityDescription(id);
            if (info != null) {
                info.thumbnail = bitmap;
                info.description = description;
                final int thumbWidth = bitmap.getWidth();
                final int thumbHeight = bitmap.getHeight();
                if ((mPortraitMode && thumbWidth > thumbHeight)
                        || (!mPortraitMode && thumbWidth < thumbHeight)) {
                    Matrix matrix = new Matrix();
                    matrix.setRotate(90.0f, (float) thumbWidth / 2, (float) thumbHeight / 2);
                    info.matrix = matrix;
                } else {
                    info.matrix = null;
                }
                // Force Carousel to request new textures for this item.
                mCarouselView.setTextureForItem(info.position, null);
                mCarouselView.setDetailTextureForItem(info.position, 0, 0, 0, 0, null);
            } else {
                if (DBG) Log.v(TAG, "Can't find view for id " + id);
            }
        }
    };

    /**
     * We never really finish() RecentApplicationsActivity, since we don't want to
     * get destroyed and pay the start-up cost to restart it.
     */
    @Override
    public void finish() {
        moveTaskToBack(true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        mHidden = !mHidden;
        if (mHidden) {
            mHiding = true;
            moveTaskToBack(true);
        } else {
            mHiding = false;
        }
        super.onNewIntent(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Resources res = getResources();
        final View decorView = getWindow().getDecorView();

        getWindow().getDecorView().setBackgroundColor(0x80000000);

        if (mCarouselView == null) {
            long t = System.currentTimeMillis();
            setContentView(R.layout.recent_apps_activity);
            long elapsed = System.currentTimeMillis() - t;
            Log.v(TAG, "Recents layout took " + elapsed + "ms to load");
            mLoadingBitmap = BitmapFactory.decodeResource(res, R.drawable.recent_rez_border);
            mCarouselView = (CarouselView)findViewById(R.id.carousel);
            mHelper = new LocalCarouselViewHelper(this);
            mHelper.setCarouselView(mCarouselView);

            mCarouselView.setSlotCount(CARD_SLOTS);
            mCarouselView.setVisibleSlots(VISIBLE_SLOTS);
            mCarouselView.createCards(0);
            mCarouselView.setStartAngle((float) -(2.0f*Math.PI * 5 / CARD_SLOTS));
            mCarouselView.setDefaultBitmap(mLoadingBitmap);
            mCarouselView.setLoadingBitmap(mLoadingBitmap);
            mCarouselView.setRezInCardCount(3.0f);
            mCarouselView.getHolder().setFormat(PixelFormat.TRANSLUCENT);

            mNoRecentsView = (View) findViewById(R.id.no_applications_message);

            mActivityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            mPortraitMode = decorView.getHeight() > decorView.getWidth();

            // Load detail view which will be used to render text
            View detail = getLayoutInflater().inflate(R.layout.recents_detail_view, null);
            TextView title = (TextView) detail.findViewById(R.id.app_title);
            TextView description = (TextView) detail.findViewById(R.id.app_description);
            mDetailInfo = new DetailInfo(detail, title, description);

            refresh();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mPortraitMode = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT;
        if (DBG) Log.v(TAG, "CONFIG CHANGE, mPortraitMode = " + mPortraitMode);
        refresh();
    }

    void updateRunningTasks() {
        mRunningTaskList = mActivityManager.getRunningTasks(MAX_TASKS,
                0, mThumbnailReceiver);
        if (DBG) Log.v(TAG, "Portrait: " + mPortraitMode);
        for (RunningTaskInfo r : mRunningTaskList) {
            if (r.thumbnail != null) {
                int thumbWidth = r.thumbnail.getWidth();
                int thumbHeight = r.thumbnail.getHeight();
                if (DBG) Log.v(TAG, "Got thumbnail " + thumbWidth + "x" + thumbHeight);
                ActivityDescription desc = findActivityDescription(r.id);
                if (desc != null) {
                    desc.thumbnail = r.thumbnail;
                    desc.description = r.description;
                    if ((mPortraitMode && thumbWidth > thumbHeight)
                            || (!mPortraitMode && thumbWidth < thumbHeight)) {
                        Matrix matrix = new Matrix();
                        matrix.setRotate(90.0f, (float) thumbWidth / 2, (float) thumbHeight / 2);
                        desc.matrix = matrix;
                    }
                } else {
                    if (DBG) Log.v(TAG, "Couldn't find ActivityDesc for id=" + r.id);
                }
            } else {
                if (DBG) Log.v(TAG, "*** RUNNING THUMBNAIL WAS NULL ***");
            }
        }
    }

    private void updateRecentTasks() {
        final PackageManager pm = getPackageManager();
        final ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);

        final List<ActivityManager.RecentTaskInfo> recentTasks =
                am.getRecentTasks(MAX_TASKS, ActivityManager.RECENT_IGNORE_UNAVAILABLE);

        ActivityInfo homeInfo = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .resolveActivityInfo(pm, 0);

        // IconUtilities iconUtilities = new IconUtilities(this); // FIXME

        int numTasks = recentTasks.size();
        mActivityDescriptions.clear();
        for (int i = 0, index = 0; i < numTasks && (index < MAX_TASKS); ++i) {
            final ActivityManager.RecentTaskInfo recentInfo = recentTasks.get(i);

            Intent intent = new Intent(recentInfo.baseIntent);
            if (recentInfo.origActivity != null) {
                intent.setComponent(recentInfo.origActivity);
            }

            // Skip the current home activity.
            if (homeInfo != null
                    && homeInfo.packageName.equals(intent.getComponent().getPackageName())
                    && homeInfo.name.equals(intent.getComponent().getClassName())) {
                continue;
            }

            intent.setFlags((intent.getFlags()&~Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            final ResolveInfo resolveInfo = pm.resolveActivity(intent, 0);
            if (resolveInfo != null) {
                final ActivityInfo info = resolveInfo.activityInfo;
                final String title = info.loadLabel(pm).toString();
                Drawable icon = info.loadIcon(pm);

                int id = recentTasks.get(i).id;
                if (id != -1 && title != null && title.length() > 0 && icon != null) {
                    // icon = null; FIXME: iconUtilities.createIconDrawable(icon);
                    ActivityDescription item = new ActivityDescription(
                            null, icon, title, null, id, index);
                    item.intent = intent;
                    mActivityDescriptions.add(item);
                    if (DBG) Log.v(TAG, "Added item[" + index
                            + "], id=" + item.id
                            + ", title=" + item.label);
                    ++index;
                } else {
                    if (DBG) Log.v(TAG, "SKIPPING item " + id);
                }
            }
        }
    }

    private final Runnable mRefreshRunnable = new Runnable() {
        public void run() {
            updateRecentTasks();
            updateRunningTasks();
            showCarousel(mActivityDescriptions.size() > 0);
        }
    };

    private void showCarousel(boolean show) {
        if (show) {
            mCarouselView.createCards(mActivityDescriptions.size());
            for (int i = 1; i < mActivityDescriptions.size(); i++) {
                // Force Carousel to update textures. Note we don't do this for the first item,
                // since it will be updated when mThumbnailReceiver returns a thumbnail.
                // TODO: only do this for apps that have changed.
                mCarouselView.setTextureForItem(i, null);
                mCarouselView.setDetailTextureForItem(i, 0, 0, 0, 0, null);
            }
            // Make carousel visible
            mNoRecentsView.setVisibility(View.GONE);
            mCarouselView.setVisibility(View.VISIBLE);
            mCarouselView.createCards(mActivityDescriptions.size());
        } else {
            // show "No Recent Tasks"
            mNoRecentsView.setVisibility(View.VISIBLE);
            mCarouselView.setVisibility(View.GONE);
        }
    }

    private void refresh() {
        if (!mHiding && mCarouselView != null) {
            // Don't update the view now. Instead, post a request so it happens next time
            // we reach the looper after a delay. This way we can fold multiple refreshes
            // into just the latest.
            mCarouselView.removeCallbacks(mRefreshRunnable);
            mCarouselView.postDelayed(mRefreshRunnable, 50);
        }
    }
}
